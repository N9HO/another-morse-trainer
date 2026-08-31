package app.anothermorsetrainer.vail

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/** Connection lifecycle, mirroring the iOS VailClient.ConnectionState. */
enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, IDLE_DISCONNECTED, RECONNECTING }

/** Events the client surfaces to its owner (the repeater model). */
sealed class VailEvent {
    data class StateChanged(val state: ConnectionState) : VailEvent()
    /** A tone burst from another user. [atLocalMs] is local-clock ms; play it. */
    data class Tone(val atLocalMs: Long, val durationMs: Int, val fromCandidates: List<String>, val txTone: Int?) : VailEvent()
    data class Chat(val text: String, val callsign: String?, val timestampMs: Long) : VailEvent()
    data class Roster(val users: List<VailMessage.UserInfo>, val rooms: List<VailMessage.Room>?) : VailEvent()
    data class DecoderRoomChanged(val enabled: Boolean) : VailEvent()
    /** Our own transmission echoed back — for lag display, do NOT render audio. */
    data class OwnEcho(val lagMs: Long, val durations: List<Int>) : VailEvent()
    data class Notice(val text: String) : VailEvent()
}

/**
 * The Vail WebSocket client. Connects to a repeater channel, transmits keyed
 * tones, receives others' tones (with clock-synced playback times), maintains the
 * roster, and reconnects through blips. All state is confined to the main thread
 * (every socket callback is posted to [main]), so no locking is needed.
 *
 * Translated from MorseTrainerApp/VailClient.swift. The Swift `actor` +
 * `URLSessionWebSocketTask` + `AsyncStream` become a main-thread-confined class +
 * OkHttp `WebSocket` + an [onEvent] callback. Clock sync, echo suppression, the
 * keepalive ping, and the reconnect/inactivity logic are preserved.
 */
class VailClient(
    var callsign: String,
    var txTone: Int = 72
) {
    var baseUrl: String = "wss://vailmorse.com/chat"

    /** Delivered on the main thread. */
    var onEvent: ((VailEvent) -> Unit)? = null

    private val http = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)   // keep the socket open
        .pingInterval(0, TimeUnit.SECONDS)       // we send app-level keepalives
        .build()
    private val main = Handler(Looper.getMainLooper())

    private var socket: WebSocket? = null
    private val sent = ArrayDeque<VailMessage>()
    private var clockOffsetMs: Long = 0
    private val lagSamples = ArrayDeque<Long>()

    private var currentChannel: String? = null
    private var currentIsPrivate = false
    private var currentIsDecoder = false
    private var wantConnected = false
    private var disconnectedDueToInactivity = false
    private var state = ConnectionState.DISCONNECTED

    private val keepalive = Runnable { keepaliveTick() }
    private var reconnectScheduled = false

    companion object {
        const val SUBPROTOCOL = "json.vailmorse.com"
        const val KEEPALIVE_MS = 15_000L
        const val RECONNECT_MS = 2_000L
    }

    val averageLagMs: Long
        get() = if (lagSamples.isEmpty()) 0 else lagSamples.sum() / lagSamples.size

    // ---- Public API (call on main thread) ----

    fun connect(channel: String, isPrivate: Boolean = false, isDecoder: Boolean = false) {
        currentChannel = channel
        currentIsPrivate = isPrivate
        currentIsDecoder = isDecoder
        wantConnected = true
        disconnectedDueToInactivity = false
        openSocket()
    }

    fun disconnect() {
        wantConnected = false
        main.removeCallbacks(keepalive)
        socket?.close(1000, null)
        socket = null
        setState(ConnectionState.DISCONNECTED)
    }

    fun updateCallsign(value: String) { callsign = value; sendHello() }
    fun updateTxTone(note: Int) { txTone = note; sendHello() }
    fun updatePrivate(value: Boolean) { currentIsPrivate = value; sendHello() }

    /** Transmit a single tone burst — call on key-up with the held duration. */
    fun transmitTone(durationMs: Int, beginLocalMs: Long) {
        if (disconnectedDueToInactivity) {
            disconnectedDueToInactivity = false
            wantConnected = true
            openSocket()
            return   // first tone after inactivity is dropped (matches web client)
        }
        val wireTs = beginLocalMs - clockOffsetMs
        val msg = VailMessage(timestamp = wireTs, duration = listOf(durationMs), txTone = txTone)
        if (send(msg)) {
            sent.addLast(msg)
            while (sent.size > 50) sent.removeFirst()
        }
    }

    fun sendChat(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        if (disconnectedDueToInactivity) {
            disconnectedDueToInactivity = false
            wantConnected = true
            openSocket()
            main.postDelayed({ sendChat(trimmed) }, 1000)
            return
        }
        send(VailMessage(timestamp = now() - clockOffsetMs, text = trimmed, callsign = callsign))
    }

    // ---- Socket lifecycle ----

    private fun openSocket() {
        if (!wantConnected) return
        val channel = currentChannel ?: return
        socket?.cancel()
        socket = null
        main.removeCallbacks(keepalive)
        setState(ConnectionState.CONNECTING)

        clockOffsetMs = 0
        sent.clear()
        // OkHttp's Request.url() silently rewrites wss:// → https:// and upgrades.
        val encoded = java.net.URLEncoder.encode(channel, "UTF-8")
        val request = Request.Builder()
            .url("$baseUrl?repeater=$encoded")
            .addHeader("Sec-WebSocket-Protocol", SUBPROTOCOL)
            .build()
        socket = http.newWebSocket(request, listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            main.post { markConnected(); sendHello(); scheduleKeepalive() }
        }
        override fun onMessage(webSocket: WebSocket, text: String) {
            main.post { handleRaw(text) }
        }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            main.post { handleSocketClose(reason) }
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            main.post { handleSocketClose(t.message ?: "") }
        }
    }

    private fun markConnected() = setState(ConnectionState.CONNECTED)

    private fun scheduleKeepalive() {
        main.removeCallbacks(keepalive)
        main.postDelayed(keepalive, KEEPALIVE_MS)
    }

    private fun keepaliveTick() {
        if (!wantConnected) return
        sendHello()
        scheduleKeepalive()
    }

    private fun sendHello() {
        send(VailMessage(
            timestamp = now(),
            callsign = callsign,
            txTone = txTone,
            private = currentIsPrivate,
            decoder = currentIsDecoder
        ))
    }

    /** Returns true if the frame was handed to the socket. */
    private fun send(msg: VailMessage): Boolean = socket?.send(msg.toJson()) ?: false

    private fun handleSocketClose(reason: String) {
        main.removeCallbacks(keepalive)
        if (!wantConnected) return
        if (reason.lowercase().contains("inactivity")) {
            disconnectedDueToInactivity = true
            wantConnected = false
            setState(ConnectionState.IDLE_DISCONNECTED)
            emit(VailEvent.Notice("Disconnected due to inactivity. Key or chat to reconnect."))
            return
        }
        setState(ConnectionState.RECONNECTING)
        emit(VailEvent.Notice("Repeater disconnected. Reconnecting…"))
        if (!reconnectScheduled) {
            reconnectScheduled = true
            main.postDelayed({ reconnectScheduled = false; openSocket() }, RECONNECT_MS)
        }
    }

    // ---- Inbound handling ----

    private fun handleRaw(text: String) {
        val msg = VailMessage.fromJson(text) ?: return
        handle(msg)
    }

    private fun handle(msg: VailMessage) {
        val nowMs = now()
        if (wantConnected) setState(ConnectionState.CONNECTED)

        msg.text?.let { emit(VailEvent.Chat(it, msg.callsign, msg.timestamp)); return }

        // Echo of our own transmission?
        val echoIdx = sent.indexOfFirst { msg.isEchoOf(it) }
        if (echoIdx >= 0) {
            sent.removeAt(echoIdx)
            val total = msg.duration.sum().toLong()
            val lag = nowMs - clockOffsetMs - msg.timestamp - total
            lagSamples.addFirst(lag)
            while (lagSamples.size > 20) lagSamples.removeLast()
            emit(VailEvent.OwnEcho(lag, msg.duration))
            return
        }

        if (msg.timestamp == 0L) return

        // Duration:[] → clock sync / roster / hello
        if (msg.duration.isEmpty()) {
            clockOffsetMs = nowMs - msg.timestamp
            when {
                msg.usersInfo != null -> emit(VailEvent.Roster(msg.usersInfo, msg.rooms))
                msg.users != null -> emit(VailEvent.Roster(msg.users.map { VailMessage.UserInfo(it, null) }, msg.rooms))
            }
            msg.decoder?.let { emit(VailEvent.DecoderRoomChanged(it)) }
            return
        }

        // Real transmission from another user. Tone relays carry no Callsign;
        // resolve candidate senders from the roster the server echoes.
        val candidates = msg.callsign?.let { listOf(it) }
            ?: resolveSenderCandidates(msg.txTone, msg.usersInfo)

        var cursorWireMs = msg.timestamp
        var isTone = true
        for (dur in msg.duration) {
            if (isTone && dur > 0) {
                emit(VailEvent.Tone(cursorWireMs + clockOffsetMs, dur, candidates, msg.txTone))
            }
            cursorWireMs += dur.toLong()
            isTone = !isTone
        }
    }

    private fun resolveSenderCandidates(txTone: Int?, usersInfo: List<VailMessage.UserInfo>?): List<String> {
        if (usersInfo == null) return emptyList()
        val others = usersInfo.filter { it.callsign != callsign }
        if (others.isEmpty()) return emptyList()
        val matched = when {
            others.size == 1 -> others
            txTone != null -> others.filter { it.txTone == txTone }.ifEmpty { others }
            else -> others
        }
        return matched.map { it.callsign }.distinct()
    }

    // ---- Helpers ----

    private fun setState(newState: ConnectionState) {
        if (newState == state) return
        state = newState
        emit(VailEvent.StateChanged(newState))
    }

    private fun emit(event: VailEvent) {
        main.post { onEvent?.invoke(event) }
    }

    private fun now(): Long = System.currentTimeMillis()
}
