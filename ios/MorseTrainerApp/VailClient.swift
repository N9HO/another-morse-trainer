// VailClient.swift
// The Vail WebSocket client. An actor — all state mutation serialized.
// See CLAUDE.md §1, §5 for protocol details and connection state machine.

import Foundation
import OSLog

private let log = Logger(subsystem: "com.justinrogers.MorseTrainer", category: "protocol")

public actor VailClient {
    // MARK: - Configuration

    /// vailmorse.com is the default. Override for testing or alternative
    /// servers (vail.woozle.org speaks the same JSON protocol family).
    public var baseURL: URL = .init(string: "wss://vailmorse.com/chat")!

    /// Subprotocol the modern server speaks.
    /// Legacy clients can use "binary.vail.woozle.org" or "json.vail.woozle.org"
    /// but the modern client uses this.
    public static let subprotocol = "json.vailmorse.com"

    /// How often to send a keepalive ping. The server (Cloud Run) drops idle
    /// connections aggressively — 15s matches the web client.
    public static let keepaliveInterval: Duration = .seconds(15)

    // MARK: - Public state

    public private(set) var clockOffsetMs: Int64 = 0
    public private(set) var lagSamplesMs: [Int64] = []
    public var averageLagMs: Int64 {
        guard !lagSamplesMs.isEmpty else { return 0 }
        return lagSamplesMs.reduce(0, +) / Int64(lagSamplesMs.count)
    }

    public var callsign: String
    public var txTone: Int

    // MARK: - Internal state

    /// Single URLSession reused across reconnects. Creating a new URLSession per
    /// openSocket() left old sessions alive in memory and could leave their
    /// websocket tasks half-open on the server until ARC collected them —
    /// resulting in the same callsign appearing multiple times in roster.
    private let urlSession = URLSession(configuration: .default)
    private var task: URLSessionWebSocketTask?
    private var sent: [VailMessage] = []
    private var receiveTask: Task<Void, Never>?
    private var keepaliveTask: Task<Void, Never>?

    private var currentChannel: String?
    private var currentIsPrivate: Bool = false
    private var currentIsDecoder: Bool = false

    private var wantConnected: Bool = false
    private var disconnectedDueToInactivity: Bool = false
    private var state: ConnectionState = .disconnected

    private let eventStream: AsyncStream<Event>
    private let eventContinuation: AsyncStream<Event>.Continuation

    // MARK: - Events

    public enum Event: Sendable {
        case stateChanged(ConnectionState)
        /// A tone burst from another user. `at` is in local clock ms; play it.
        /// `fromCandidates` is who could have sent it: one entry when the
        /// sender is certain, several when ops share a TX tone (the wire
        /// carries no Callsign on tone relays), empty when unknown.
        case tone(at: Int64, durationMs: UInt16, fromCandidates: [String], txTone: Int?)
        /// Chat from another user.
        case chat(text: String, callsign: String?, timestampMs: Int64)
        /// Roster update — full list of connected users with their TX tones.
        case roster(users: [VailMessage.UserInfo], rooms: [VailMessage.Room]?)
        /// Server tells us this room has decoder enabled.
        case decoderRoomChanged(Bool)
        /// Our own transmission came back as echo. Use for lag display; do
        /// NOT render audio (already played as local sidetone).
        case ownEcho(lagMs: Int64, durations: [UInt16])
        /// Server-pushed toast. Display to user.
        case notice(String)
    }

    public enum ConnectionState: Sendable, Equatable {
        case disconnected
        case connecting
        case connected
        case idleDisconnected // closed for inactivity; will reconnect on next user action
        case reconnecting
    }

    // MARK: - Init

    public init(callsign: String, txTone: Int = 72) {
        self.callsign = callsign
        self.txTone = txTone
        (eventStream, eventContinuation) = Self.makeStream()
    }

    private static func makeStream() -> (AsyncStream<Event>, AsyncStream<Event>.Continuation) {
        var c: AsyncStream<Event>.Continuation!
        let s = AsyncStream<Event> { c = $0 }
        return (s, c)
    }

    /// Subscribe to client events. Iterate this from the consumer (typically
    /// `VailSession`):
    /// ```
    /// for await event in await client.events() { ... }
    /// ```
    public func events() -> AsyncStream<Event> {
        eventStream
    }

    // MARK: - Public API

    public func connect(
        channel: String,
        isPrivate: Bool = false,
        isDecoder: Bool = false
    ) {
        appLog(.notice, "protocol", "connect channel=\(channel) private=\(isPrivate) decoder=\(isDecoder) callsign=\(callsign)")
        currentChannel = channel
        currentIsPrivate = isPrivate
        currentIsDecoder = isDecoder
        wantConnected = true
        disconnectedDueToInactivity = false
        openSocket()
    }

    public func disconnect() async {
        appLog(.notice, "protocol", "disconnect (user-initiated)")
        wantConnected = false
        keepaliveTask?.cancel()
        keepaliveTask = nil
        receiveTask?.cancel()
        receiveTask = nil
        task?.cancel(with: .normalClosure, reason: nil)
        task = nil
        // Brief pause to let the close frame propagate before any subsequent
        // reconnect creates a new socket. Without this the server may briefly
        // see both the old and new socket for the same callsign.
        try? await Task.sleep(for: .milliseconds(250))
        setState(.disconnected)
    }

    /// Single funnel for connection-state changes; deduplicates so consumers
    /// only see transitions.
    private func setState(_ newState: ConnectionState) {
        guard newState != state else { return }
        state = newState
        eventContinuation.yield(.stateChanged(newState))
    }

    /// Point the client at a different repeater server. Takes effect on the next
    /// `connect()` (or channel switch). vailmorse.com and vail.woozle.org speak
    /// the same JSON protocol family.
    public func setBaseURL(_ url: URL) {
        baseURL = url
    }

    public func setCallsign(_ callsign: String) {
        self.callsign = callsign
        Task { try? await sendHello() }
    }

    public func setTxTone(_ note: Int) {
        txTone = note
        Task { try? await sendHello() }
    }

    public func setPrivate(_ isPrivate: Bool) {
        currentIsPrivate = isPrivate
        Task { try? await sendHello() }
    }

    /// Transmit a single tone burst. Call on key-up with the duration of the
    /// tone you just held. See CLAUDE.md §1 — outbound transmissions use a
    /// one-element `Duration: [ms]` array.
    public func transmitTone(durationMs: UInt16, beginLocalMs: Int64) async {
        if disconnectedDueToInactivity {
            // Lazy reconnect on user activity.
            appLog(.notice, "protocol", "reconnect triggered by TX after inactivity")
            disconnectedDueToInactivity = false
            wantConnected = true
            openSocket()
            // Don't try to send yet — sending requires established connection.
            // The first tone after inactivity is dropped; the user will key
            // again. Match the web client's behavior here.
            return
        }

        // Timestamp is when the tone *started*, not when we're sending.
        // Receivers schedule playback at `timestamp + clockOffset + rxDelay`,
        // so anchoring to begin-time saves key_duration of perceived latency.
        let wireTs = beginLocalMs - clockOffsetMs
        var msg = VailMessage(timestamp: wireTs)
        msg.duration = [durationMs]
        msg.txTone = txTone

        do {
            try await sendRaw(msg)
            sent.append(msg)
            // Bound the queue. Anything older than ~10s never came back.
            if sent.count > 50 { sent.removeFirst(sent.count - 50) }
        } catch {
            log.error("transmitTone failed: \(error.localizedDescription)")
        }
    }

    /// Send a chat message.
    public func sendChat(_ text: String) async {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }

        if disconnectedDueToInactivity {
            appLog(.notice, "protocol", "reconnect triggered by chat after inactivity")
            disconnectedDueToInactivity = false
            wantConnected = true
            openSocket()
            // Delay the send slightly to allow the socket to open.
            // The web client does the same with a 1s timeout.
            Task {
                try? await Task.sleep(for: .seconds(1))
                await sendChat(trimmed)
            }
            return
        }

        var msg = VailMessage(timestamp: currentTimeMs() - clockOffsetMs)
        msg.text = trimmed
        msg.callsign = callsign

        do {
            try await sendRaw(msg)
        } catch {
            log.error("sendChat failed: \(error.localizedDescription)")
        }
    }

    // MARK: - Socket lifecycle

    private func openSocket() {
        guard wantConnected else {
            appLog(.info, "protocol", "openSocket suppressed (wantConnected=false)")
            return
        }
        guard let channel = currentChannel else {
            appLog(.error, "protocol", "openSocket called with no channel")
            return
        }

        // Explicitly cancel any prior socket and its loops before opening a
        // new one. Without this a fast-reconnect path can leave the previous
        // task in memory long enough for the server to list us twice.
        task?.cancel(with: .goingAway, reason: nil)
        task = nil
        receiveTask?.cancel()
        receiveTask = nil
        keepaliveTask?.cancel()
        keepaliveTask = nil

        appLog(.notice, "protocol", "openSocket channel=\(channel)")
        setState(.connecting)

        var components = URLComponents(url: baseURL, resolvingAgainstBaseURL: false)!
        components.queryItems = [URLQueryItem(name: "repeater", value: channel)]
        guard let url = components.url else {
            log.error("Invalid URL components")
            return
        }

        // URLSessionWebSocketTask handles subprotocol negotiation when passed
        // via the protocols: argument.
        clockOffsetMs = 0
        sent.removeAll()
        let newTask = urlSession.webSocketTask(with: url, protocols: [Self.subprotocol])
        task = newTask
        newTask.resume()

        // On URLSessionWebSocketTask there's no explicit "open" callback;
        // we proceed to send the hello immediately. The framework queues it
        // until the handshake completes.
        Task {
            do {
                try await sendHello()
                self.markConnected()
            } catch {
                appLog(.error, "protocol", "sendHello failed: \(error.localizedDescription)")
            }
        }

        receiveTask?.cancel()
        receiveTask = Task { [weak self] in
            await self?.receiveLoop()
        }

        keepaliveTask?.cancel()
        keepaliveTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: Self.keepaliveInterval)
                guard let self else { return }
                try? await self.sendHello()
            }
        }
    }

    private func markConnected() {
        appLog(.notice, "protocol", "connected (hello sent OK)")
        setState(.connected)
    }

    /// Send the initial / keepalive message. Has Duration:[] and announces
    /// our callsign, TX tone, and room flags.
    private func sendHello() async throws {
        var msg = VailMessage(timestamp: currentTimeMs())
        msg.callsign = callsign
        msg.txTone = txTone
        msg.private = currentIsPrivate
        msg.decoder = currentIsDecoder
        try await sendRaw(msg)
    }

    private func sendRaw(_ msg: VailMessage) async throws {
        guard let task else { throw VailClientError.notConnected }
        let encoder = JSONEncoder()
        encoder.outputFormatting = []
        let data = try encoder.encode(msg)
        guard let string = String(data: data, encoding: .utf8) else {
            throw VailClientError.encodingFailure
        }
        try await task.send(.string(string))
    }

    private func receiveLoop() async {
        guard let task else { return }
        do {
            while !Task.isCancelled {
                let frame = try await task.receive()
                let data: Data? = switch frame {
                case let .string(s): s.data(using: .utf8)
                case let .data(d): d
                @unknown default: nil
                }
                guard let data else { continue }
                do {
                    let msg = try JSONDecoder().decode(VailMessage.self, from: data)
                    // Diagnostic: surface tone messages with no candidate
                    // sender at all — neither a Callsign nor any roster to
                    // resolve against. Logging the raw frame lets us see if
                    // the server's roster/key naming has drifted again.
                    if !msg.duration.isEmpty, msg.callsign == nil, msg.text == nil,
                       resolveSenderCandidates(txTone: msg.txTone, usersInfo: msg.usersInfo).isEmpty
                    {
                        let preview = String(data: data, encoding: .utf8) ?? "<binary>"
                        appLog(.warning, "protocol", "unattributable tone: \(preview.prefix(240))")
                    }
                    handle(msg)
                } catch {
                    log.error("Failed to decode message: \(error.localizedDescription)")
                }
            }
        } catch {
            // Our own cancellation (disconnect, reconnect, channel switch)
            // throws CancellationError out of `task.receive()`. That is not
            // a server-side close and must not trigger the reconnect path,
            // or we end up in a reconnect storm where every channel switch
            // races a stale reconnect against the fresh socket.
            if Task.isCancelled || error is CancellationError {
                return
            }
            handleSocketClose(error: error)
        }
    }

    private func handleSocketClose(error: Error) {
        let nsError = error as NSError
        let reason = nsError.userInfo[NSLocalizedDescriptionKey] as? String ?? ""
        let wsCode = task?.closeCode.rawValue ?? -1
        let wsReason: String = {
            guard let data = task?.closeReason, !data.isEmpty else { return "<none>" }
            return String(data: data, encoding: .utf8) ?? "<\(data.count) bytes>"
        }()
        appLog(
            .warning,
            "protocol",
            """
            socket closed: \(error.localizedDescription) \
            [domain=\(nsError.domain) code=\(nsError.code) \
            reason=\(reason.isEmpty ? "<empty>" : reason) \
            wsCode=\(wsCode) wsReason=\(wsReason)]
            """
        )

        keepaliveTask?.cancel()
        keepaliveTask = nil

        // If the user (or a channel switch) tore us down deliberately,
        // wantConnected is false. Don't schedule a reconnect — a fresh
        // connect() is either already in flight or is the user's choice
        // not to be connected.
        guard wantConnected else {
            appLog(.notice, "protocol", "socket closed during teardown; not reconnecting")
            return
        }

        // The server's "inactivity" close arrives as a websocket close-frame
        // reason; transport errors may also carry it in the error description.
        // Check both.
        if reason.lowercased().contains("inactivity") || wsReason.lowercased().contains("inactivity") {
            appLog(.notice, "protocol", "inactivity disconnect — will reconnect on next user action")
            disconnectedDueToInactivity = true
            wantConnected = false
            setState(.idleDisconnected)
            eventContinuation.yield(.notice("Disconnected due to inactivity. Send morse or chat to reconnect."))
            return
        }

        appLog(.notice, "protocol", "scheduling reconnect in 2s")
        setState(.reconnecting)
        eventContinuation.yield(.notice("Repeater disconnected. Reconnecting…"))

        // Reconnect with a short delay. Match web client behavior.
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(2))
            await self?.openSocket()
        }
    }

    // MARK: - Inbound handling

    private func handle(_ msg: VailMessage) {
        let nowMs = currentTimeMs()

        // Inbound traffic proves the link is up. This recovers the published
        // state even if the post-handshake hello send raced and failed (which
        // otherwise left the UI stuck on "Reconnecting" after the app was
        // foregrounded, despite a working connection).
        if wantConnected {
            setState(.connected)
        }

        // Chat message?
        if let text = msg.text {
            eventContinuation.yield(.chat(
                text: text,
                callsign: msg.callsign,
                timestampMs: msg.timestamp
            ))
            return
        }

        // Echo of our own transmission?
        if let idx = sent.firstIndex(where: { msg.isEchoOf($0) }) {
            sent.remove(at: idx)
            let total = msg.duration.reduce(Int64(0)) { $0 + Int64($1) }
            let lag = nowMs - clockOffsetMs - msg.timestamp - total
            lagSamplesMs.insert(lag, at: 0)
            if lagSamplesMs.count > 20 { lagSamplesMs.removeLast(lagSamplesMs.count - 20) }
            eventContinuation.yield(.ownEcho(lagMs: lag, durations: msg.duration))
            return
        }

        // Server sometimes sends Timestamp=0; skip without processing.
        if msg.timestamp == 0 {
            log.debug("Received Timestamp=0 message; skipping")
            return
        }

        // Duration:[] → clock-sync / roster / hello
        if msg.duration.isEmpty {
            clockOffsetMs = nowMs - msg.timestamp
            if let users = msg.usersInfo {
                eventContinuation.yield(.roster(users: users, rooms: msg.rooms))
            } else if let users = msg.users {
                // Legacy fallback: synthesize UserInfo with no TxTone.
                let info = users.map { VailMessage.UserInfo(callsign: $0, txTone: nil) }
                eventContinuation.yield(.roster(users: info, rooms: msg.rooms))
            }
            if let decoder = msg.decoder {
                eventContinuation.yield(.decoderRoomChanged(decoder))
            }
            return
        }

        // Real transmission from another user. Tone messages never carry a
        // Callsign on the wire (confirmed 2026-06-11) — the server forwards
        // them with only Duration and TxTone. Resolve candidate senders from
        // the roster (UsersInfo) the server echoes on every message.
        let candidates = msg.callsign.map { [$0] }
            ?? resolveSenderCandidates(txTone: msg.txTone, usersInfo: msg.usersInfo)

        // Walk the Duration array, emitting one .tone event per tone
        // (even-indexed) entry.
        var cursorWireMs = msg.timestamp
        var isTone = true
        for dur in msg.duration {
            if isTone, dur > 0 {
                let playAtLocalMs = cursorWireMs + clockOffsetMs
                eventContinuation.yield(.tone(
                    at: playAtLocalMs,
                    durationMs: dur,
                    fromCandidates: candidates,
                    txTone: msg.txTone
                ))
            }
            cursorWireMs += Int64(dur)
            isTone.toggle()
        }
    }

    /// Tone messages arrive without a Callsign — only a TxTone. The server
    /// includes the full roster (UsersInfo: callsign + TxTone) on every
    /// message, so attribute from that snapshot:
    ///
    /// - A single other operator in the room is unambiguously the sender
    ///   (the common 1-on-1 QSO case), regardless of TX tone.
    /// - Otherwise, everyone matching the tone's TxTone is a candidate. When
    ///   exactly one matches, the sender is certain.
    /// - If the TxTone matches nobody (stale roster, mid-session tone change),
    ///   fall back to all other operators rather than hiding the activity.
    ///
    /// Returns [] only when there is no roster to consult.
    private func resolveSenderCandidates(txTone: Int?, usersInfo: [VailMessage.UserInfo]?) -> [String] {
        guard let usersInfo else { return [] }
        let others = usersInfo.filter { $0.callsign != callsign }
        guard !others.isEmpty else { return [] }

        let matched: [VailMessage.UserInfo]
        if others.count == 1 {
            matched = others
        } else if let txTone {
            let toneMatches = others.filter { $0.txTone == txTone }
            matched = toneMatches.isEmpty ? others : toneMatches
        } else {
            matched = others
        }

        // Zombie sockets can briefly list the same callsign twice; dedupe.
        var seen = Set<String>()
        return matched.map(\.callsign).filter { seen.insert($0).inserted }
    }

    // MARK: - Helpers

    private func currentTimeMs() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }
}

public enum VailClientError: Error {
    case notConnected
    case encodingFailure
}
