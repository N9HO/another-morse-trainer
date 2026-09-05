package app.anothermorsetrainer.vail

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import app.anothermorsetrainer.AdapterKeyer
import app.anothermorsetrainer.AudioFocus
import app.anothermorsetrainer.MidiKeyInput
import app.anothermorsetrainer.MidiKeyOutput
import app.anothermorsetrainer.SidetoneGenerator
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Top-level orchestrator for the Vail repeater: owns the [VailClient], the local
 * TX [SidetoneGenerator], the RX [RepeaterTonePlayer], and the optional MIDI key.
 * Wires key down/up → local sidetone + (when break-in is on) a network
 * transmission, and inbound tones → clock-synced playback. Holds Compose state.
 *
 * Port of MorseTrainerApp/RepeaterModel.swift. Keeps clock sync, break-in, the
 * 10 s stuck-key cutoff, reconnect, and the adapter-piezo/[MidiKeyOutput]
 * keyer-config path (keyer mode, RX buzz feedback, wake/identify).
 */
class VailRepeater(context: Context) {

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("amt_vail", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())

    private val client = VailClient(callsign = "", txTone = 72)
    private val tonePlayer = RepeaterTonePlayer()
    private val midi = MidiKeyInput(context)
    private val midiOut = MidiKeyOutput(context)
    private var sidetone: SidetoneGenerator? = null

    // ---- Compose state ----
    var connectionState by mutableStateOf(ConnectionState.DISCONNECTED); private set
    var callsign by mutableStateOf(""); private set
    var channel by mutableStateOf("General"); private set
    var serverUrl by mutableStateOf(DEFAULT_SERVER); private set
    var privateChannel by mutableStateOf(true); private set
    var txTone by mutableIntStateOf(72); private set
    var rxDelayMs by mutableIntStateOf(2000); private set
    var breakInEnabled by mutableStateOf(false); private set
    var rxBuzzEnabled by mutableStateOf(true); private set
    var keyerWpm by mutableIntStateOf(20); private set
    var users by mutableStateOf<List<VailMessage.UserInfo>>(emptyList()); private set
    /** The server's public Rooms list (name + occupant count), from the last roster. */
    var rooms by mutableStateOf<List<VailMessage.Room>>(emptyList()); private set
    /** The server's Decoder flag for the room we are in. Mirrors iOS `roomDecoderEnabled`. */
    var roomDecoderEnabled by mutableStateOf(false); private set
    var notice by mutableStateOf<String?>(null); private set
    var lagMs by mutableLongStateOf(0L); private set
    var isKeying by mutableStateOf(false); private set
    var midiDevice by mutableStateOf<String?>(null); private set
    /** The MIDI device the outbound (piezo/keyer-config) path is talking to. */
    var adapterName by mutableStateOf<String?>(null); private set
    var keyerMode by mutableStateOf(MidiKeyOutput.KeyerMode.STRAIGHT_KEY); private set

    /** True when the device exposes MIDI at all — gates the adapter keyer UI. */
    val midiSupported: Boolean get() = midiOut.isSupported

    // Activity timeline + chat.
    var signalEvents by mutableStateOf<List<SignalEvent>>(emptyList()); private set
    var liveOwnKeyStarts by mutableStateOf<List<Long>>(emptyList()); private set
    var chatMessages by mutableStateOf<List<ChatLine>>(emptyList()); private set
    /**
     * Chat messages received while the chat panel was not showing. Drives the
     * badge on the chat header. Reset when the panel opens or on a new connect.
     */
    var unreadChatCount by mutableIntStateOf(0); private set
    /**
     * Set by the screen while the chat panel is visible. While true, incoming
     * chat does not count as unread — the user is looking right at it.
     */
    var isChatViewActive: Boolean = false

    private var keyDown = false
    private var keyBeginMs = 0L
    private val stuckKey = Runnable { handleStuckKey() }

    /**
     * Last local-clock ms each callsign appeared in a roster snapshot. The
     * prune tick drops users older than [USER_TTL_MS] — a backstop for a server
     * that forgets to push a fresh roster after someone leaves (channel-switch
     * races, brief blips). Steady-state snapshots are full replacements, so
     * it normally trims nothing.
     */
    private val userLastSeenMs = mutableMapOf<String, Long>()
    private val rosterPrune = object : Runnable {
        override fun run() {
            pruneStaleUsers()
            main.postDelayed(this, USER_PRUNE_INTERVAL_MS)
        }
    }

    companion object {
        /** The known public Vail servers for the picker; any wss:// URL works too. */
        const val DEFAULT_SERVER = "wss://vailmorse.com/chat"
        val KNOWN_SERVERS: List<Pair<String, String>> = listOf(
            "Vailmorse" to DEFAULT_SERVER,
            "Vail (woozle)" to "wss://vail.woozle.org/chat"
        )
        private const val MAX_SIGNALS = 5000
        private const val MAX_CHAT = 500
        /** RX delay slider: 0–4000 ms in 250 ms steps, as on iOS. */
        const val RX_DELAY_MAX_MS = 4000
        const val RX_DELAY_STEP_MS = 250
        const val USER_TTL_MS = 60_000L
        const val USER_PRUNE_INTERVAL_MS = 10_000L
        /** Prefix of the per-channel chat-read watermark keys in [prefs]. */
        private const val CHAT_READ_PREFIX = "chatRead:"
    }

    init {
        callsign = prefs.getString("callsign", null)?.takeIf { it.isNotBlank() } ?: anonCallsign()
        channel = prefs.getString("channel", null)?.takeIf { it.isNotBlank() } ?: "General"
        serverUrl = prefs.getString("server", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_SERVER
        privateChannel = prefs.getBoolean("private", true)
        txTone = prefs.getInt("txTone", 72)
        rxDelayMs = snapRxDelay(prefs.getInt("rxDelay", 2000))
        breakInEnabled = prefs.getBoolean("breakIn", false)
        rxBuzzEnabled = prefs.getBoolean("rxBuzz", true)
        keyerWpm = prefs.getInt("keyerWpm", 20).coerceIn(5, 50)
        keyerMode = AdapterKeyer.mode(app)
        client.callsign = callsign
        client.txTone = txTone
        client.baseUrl = serverUrl
    }

    fun start() {
        client.onEvent = { handleEvent(it) }
        sidetone = newSidetone(txTone)
        midi.start(
            onKey = { down -> touchKey(down) },
            onConnected = { midiDevice = it }
        )
        // Drive the adapter's piezo/keyer: wake it into MIDI mode, push the keyer
        // mode, speed, and sidetone, and (via RX buzz) feed received tones to it.
        // The mode is re-read rather than trusted from construction time: it is
        // shared with Settings (#43), which may have changed it since.
        keyerMode = AdapterKeyer.mode(app)
        midiOut.configure(keyerMode = keyerMode, wpm = keyerWpm, sidetoneMidiNote = txTone)
        midiOut.start { name ->
            adapterName = name
            if (midiDevice == null) midiDevice = name
        }
    }

    fun stop() {
        main.removeCallbacks(stuckKey)
        main.removeCallbacks(rosterPrune)
        client.disconnect()
        client.onEvent = null
        midi.stop()
        midiOut.stop()
        sidetone?.stop(); sidetone = null
        tonePlayer.release()
    }

    // ---- Connection ----

    fun connect() {
        signalEvents = emptyList()
        chatMessages = emptyList()
        unreadChatCount = 0
        liveOwnKeyStarts = emptyList()
        client.baseUrl = serverUrl
        val isDecoder = channel.equals("Decoder", ignoreCase = true)
        client.connect(channel, isPrivate = privateChannel && !isDecoder, isDecoder = isDecoder)
        // Roster pruning runs for the life of the connection; see [userLastSeenMs].
        main.removeCallbacks(rosterPrune)
        main.postDelayed(rosterPrune, USER_PRUNE_INTERVAL_MS)
    }

    fun disconnect() {
        main.removeCallbacks(rosterPrune)
        client.disconnect()
    }

    /**
     * Deterministic private-channel name for a one-to-one QSO with another
     * operator. Both parties derive the same name (callsigns are sorted), so
     * either can start the QSO and meet on the same channel. Same derivation as
     * iOS `qsoChannelName(with:)`: upper-cased, joined with "-", stripped to
     * letters, digits and "-".
     */
    fun qsoChannelName(otherCallsign: String): String {
        val mine = callsign.uppercase()
        val theirs = otherCallsign.uppercase()
        val joined = listOf(mine, theirs).sorted().joinToString("-")
        val sanitized = joined.filter { it.isLetterOrDigit() || it == '-' }
        return "QSO-$sanitized"
    }

    // ---- Config ----

    fun updateCallsign(value: String) {
        val t = value.trim()
        if (t.isEmpty()) return
        callsign = t; prefs.edit { putString("callsign", t) }
        client.updateCallsign(t)
    }

    fun updateChannel(value: String) {
        val t = value.trim().ifEmpty { "General" }
        channel = t; prefs.edit { putString("channel", t) }
    }

    /** Point at a different Vail server (takes effect on the next connect). */
    fun updateServer(url: String) {
        val t = url.trim().ifEmpty { DEFAULT_SERVER }
        serverUrl = t; prefs.edit { putString("server", t) }
        val wasConnected = connectionState == ConnectionState.CONNECTED ||
            connectionState == ConnectionState.CONNECTING
        client.baseUrl = t
        if (wasConnected) {
            // Move over right away rather than leaving the socket on the old host.
            disconnect()
            connect()
        }
    }

    /** Join channels privately (unlisted) or on the public roster. */
    fun updatePrivateChannel(value: Boolean) {
        privateChannel = value; prefs.edit { putBoolean("private", value) }
        client.updatePrivate(value && !channel.equals("Decoder", ignoreCase = true))
    }

    fun updateTxTone(note: Int) {
        val n = note.coerceIn(48, 96)
        txTone = n; prefs.edit { putInt("txTone", n) }
        client.updateTxTone(n)
        // Sidetone pitch follows the TX tone.
        sidetone?.let { it.stop(); sidetone = newSidetone(n) }
        // The adapter's piezo sidetone note follows the TX tone too.
        midiOut.setSidetone(n)
    }

    /** Set the adapter's internal keyer mode (straight key, iambic A/B, …). */
    fun updateKeyerMode(mode: MidiKeyOutput.KeyerMode) {
        keyerMode = mode; AdapterKeyer.setMode(app, mode)
        midiOut.setKeyerMode(mode)
    }

    /** Push the adapter's iambic keyer speed (dit duration) — was stuck at 20 WPM. */
    fun updateKeyerWpm(wpm: Int) {
        keyerWpm = wpm.coerceIn(5, 50); prefs.edit { putInt("keyerWpm", keyerWpm) }
        midiOut.setSpeed(keyerWpm)
    }

    /** RX piezo feedback on the adapter, opt-outable. */
    fun updateRxBuzzEnabled(value: Boolean) {
        rxBuzzEnabled = value; prefs.edit { putBoolean("rxBuzz", value) }
    }

    /** Re-run the adapter wake/identify sequence (e.g. after plugging it in). */
    fun wakeAdapter() = midiOut.wakeAdapter()

    fun updateRxDelayMs(ms: Int) {
        rxDelayMs = snapRxDelay(ms); prefs.edit { putInt("rxDelay", rxDelayMs) }
    }

    /** Clamp to 0–[RX_DELAY_MAX_MS] and snap to the nearest [RX_DELAY_STEP_MS]. */
    private fun snapRxDelay(ms: Int): Int =
        ((ms.toDouble() / RX_DELAY_STEP_MS).roundToInt() * RX_DELAY_STEP_MS).coerceIn(0, RX_DELAY_MAX_MS)

    fun setBreakIn(enabled: Boolean) {
        breakInEnabled = enabled; prefs.edit { putBoolean("breakIn", enabled) }
    }

    fun sendChat(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        recordSignal(SignalEvent(callsign, System.currentTimeMillis(), SignalEvent.Kind.Chat(trimmed), SignalEvent.Origin.SENT))
        client.sendChat(trimmed)
    }

    /** Clear the unread chat badge. The screen calls this when the chat panel opens. */
    fun markChatRead() {
        unreadChatCount = 0
        chatMessages.maxOfOrNull { it.timestampMs }?.let { advanceChatReadWatermark(it) }
    }

    // ---- Chat read watermark ----

    /**
     * Per-channel timestamp (server clock ms) of the newest chat message the
     * user has seen, persisted across launches. The server replays the whole
     * chat backlog on every join; without this every launch re-badged the same
     * old messages as unread.
     */
    private fun chatReadWatermarkMs(): Long = prefs.getLong(CHAT_READ_PREFIX + channel, 0L)

    private fun advanceChatReadWatermark(timestampMs: Long) {
        if (timestampMs <= chatReadWatermarkMs()) return
        prefs.edit { putLong(CHAT_READ_PREFIX + channel, timestampMs) }
    }

    // ---- Keying ----

    fun touchKey(isDown: Boolean) {
        val nowMs = System.currentTimeMillis()
        if (isDown && !keyDown) {
            keyDown = true
            keyBeginMs = nowMs
            isKeying = true
            sidetone?.setKeyDown(true)
            if (breakInEnabled) liveOwnKeyStarts = liveOwnKeyStarts + nowMs
            startStuckKeyWatchdog()
        } else if (!isDown && keyDown) {
            keyDown = false
            isKeying = false
            sidetone?.setKeyDown(false)
            val duration = (nowMs - keyBeginMs).coerceAtLeast(0)
            liveOwnKeyStarts = liveOwnKeyStarts - keyBeginMs
            if (breakInEnabled && duration in 1..65535) {
                recordSignal(SignalEvent(callsign, keyBeginMs, SignalEvent.Kind.Tone(duration.toInt(), txTone), SignalEvent.Origin.SENT))
                client.transmitTone(duration.toInt(), keyBeginMs)
            }
            main.removeCallbacks(stuckKey)
        }
    }

    private fun recordSignal(event: SignalEvent) {
        signalEvents = (signalEvents + event).let { if (it.size > MAX_SIGNALS) it.drop(it.size - MAX_SIGNALS) else it }
    }

    private fun startStuckKeyWatchdog() {
        main.removeCallbacks(stuckKey)
        main.postDelayed(stuckKey, 10_000)
    }

    private fun handleStuckKey() {
        keyDown = false
        isKeying = false
        sidetone?.setKeyDown(false)
        liveOwnKeyStarts = emptyList()
        setBreakIn(false)
        notice = "Stuck key detected. Break-in disabled."
    }

    // ---- Client events ----

    private fun handleEvent(event: VailEvent) {
        when (event) {
            is VailEvent.StateChanged -> {
                connectionState = event.state
                if (event.state == ConnectionState.CONNECTING) {
                    users = emptyList()
                    userLastSeenMs.clear()
                }
                if (event.state == ConnectionState.CONNECTED) notice = null
            }
            is VailEvent.Tone -> {
                val note = event.txTone ?: 69
                val playAt = event.atLocalMs + rxDelayMs
                tonePlayer.scheduleTone(note, event.durationMs, playAt)
                // Buzz the adapter's piezo in sync with the audio playback.
                if (rxBuzzEnabled) midiOut.scheduleBuzz(note, event.durationMs, playAt)
                val lane = when (event.fromCandidates.size) {
                    0 -> "?"
                    1 -> event.fromCandidates[0]
                    else -> event.fromCandidates.sorted().joinToString("/")
                }
                recordSignal(SignalEvent(lane, playAt, SignalEvent.Kind.Tone(event.durationMs, event.txTone), SignalEvent.Origin.RECEIVED))
            }
            is VailEvent.Roster -> {
                users = event.users
                // Refresh last-seen for every callsign in the snapshot; do NOT
                // touch entries not in it — the prune tick ages those out.
                val nowMs = System.currentTimeMillis()
                event.users.forEach { userLastSeenMs[it.callsign] = nowMs }
                event.rooms?.let { rooms = it }
            }
            is VailEvent.OwnEcho -> lagMs = event.lagMs
            is VailEvent.Notice -> notice = event.text
            is VailEvent.Chat -> {
                // The server replays chat backlog on every join; drop exact dupes.
                val dup = chatMessages.any { it.timestampMs == event.timestampMs && it.callsign == event.callsign && it.text == event.text }
                if (!dup) {
                    chatMessages = (chatMessages + ChatLine(event.text, event.callsign, event.timestampMs))
                        .let { if (it.size > MAX_CHAT) it.drop(it.size - MAX_CHAT) else it }
                    // Badge only messages newer than the persisted last-read
                    // watermark — replayed backlog the user already saw is not "new".
                    if (isChatViewActive || event.callsign == callsign) {
                        advanceChatReadWatermark(event.timestampMs)
                    } else if (event.timestampMs > chatReadWatermarkMs()) {
                        unreadChatCount += 1
                    }
                    recordSignal(SignalEvent(event.callsign ?: "?", event.timestampMs, SignalEvent.Kind.Chat(event.text), SignalEvent.Origin.RECEIVED))
                }
            }
            is VailEvent.DecoderRoomChanged -> roomDecoderEnabled = event.enabled
        }
    }

    // ---- Roster pruning ----

    /**
     * Drop users whose last-seen stamp is older than [USER_TTL_MS]. Server
     * roster snapshots are full replacements, so in the steady state this never
     * trims anything — it only catches stragglers from a snapshot the server
     * forgot to update.
     */
    private fun pruneStaleUsers() {
        if (users.isEmpty()) return
        val nowMs = System.currentTimeMillis()
        val alive = users.filter { u ->
            val seen = userLastSeenMs[u.callsign] ?: return@filter false
            nowMs - seen <= USER_TTL_MS
        }
        if (alive.size != users.size) {
            users = alive
            userLastSeenMs.keys.retainAll { cs -> alive.any { it.callsign == cs } }
        }
    }

    // ---- Helpers ----

    private fun midiToHz(note: Int): Double = 440.0 * 2.0.pow((note - 69) / 12.0)

    /**
     * The repeater's sidetone ducks other apps rather than pausing them, so a
     * radio app can run alongside — iOS claims its `repeaterMix` session for the
     * same reason. Drills keep the exclusive default; see [AudioFocus].
     */
    private fun newSidetone(note: Int): SidetoneGenerator =
        SidetoneGenerator(midiToHz(note), AudioFocus.Gain.DUCK).also { it.start() }

    private fun anonCallsign(): String = "anon" + Random.nextInt(1000, 10000)
}
