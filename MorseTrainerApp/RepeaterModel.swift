// RepeaterModel.swift
// Top-level orchestrator. Owns the VailClient, KeyerEngine, and MIDIInput.
// Publishes observable state for SwiftUI views.
//
// Responsibilities:
//   - Wire MIDI key events to TX (local sidetone + WebSocket send)
//   - Wire VailClient events to RX (scheduled audio + roster updates)
//   - Stuck-key safety (10s auto-cutoff)
//   - Hold @Published state for SwiftUI
//
// See CLAUDE.md §6 for the full data flow.

import Foundation
import OSLog
import SwiftUI

private let log = Logger(subsystem: "com.justinrogers.MorseTrainer", category: "session")

@MainActor
public final class RepeaterModel: ObservableObject {
    // MARK: - Published state

    @Published public var connectionState: VailClient.ConnectionState = .disconnected
    @Published public var channel: String = "General" {
        didSet { UserDefaults.standard.set(channel, forKey: Self.channelDefaultsKey) }
    }

    @Published public var callsign: String = "" {
        didSet { UserDefaults.standard.set(callsign, forKey: Self.callsignDefaultsKey) }
    }

    @Published public var txTone: Int = 72 {
        didSet { UserDefaults.standard.set(txTone, forKey: Self.txToneDefaultsKey) }
    }

    @Published public var rxDelayMs: Int = 2000 {
        didSet { UserDefaults.standard.set(rxDelayMs, forKey: Self.rxDelayDefaultsKey) }
    }

    /// The repeater server WebSocket URL. Applied to the client on the next
    /// connect. Defaults to vailmorse.com; vail.woozle.org speaks the same
    /// protocol family.
    @Published public var serverURLString: String = "wss://vailmorse.com/chat" {
        didSet { UserDefaults.standard.set(serverURLString, forKey: Self.serverURLDefaultsKey) }
    }

    /// Well-known Vail-protocol repeater servers, offered in the settings picker.
    public static let knownServers: [(name: String, url: String)] = [
        ("vailmorse.com", "wss://vailmorse.com/chat"),
        ("vail.woozle.org", "wss://vail.woozle.org/chat")
    ]

    @Published public var users: [VailMessage.UserInfo] = []
    @Published public var rooms: [VailMessage.Room] = []
    @Published public var chatMessages: [ChatMessage] = []
    /// Count of chat messages received while the chat view was not active.
    /// Drives the tab badge. Reset when the chat view appears or the user
    /// switches channels.
    @Published public var unreadChatCount: Int = 0
    /// Set by ChatView via onAppear/onDisappear. While true, incoming chat
    /// messages do not increment `unreadChatCount` — the user is looking
    /// right at them.
    public var isChatViewActive: Bool = false
    /// Rolling log of keyed-tone bursts and chat events for the on-screen
    /// timeline visualizer. Capped at `maxSignalEvents`.
    @Published public var signalEvents: [SignalEvent] = []
    public static let maxSignalEvents = 5000

    /// Start times (local clock ms) of keys currently held down on this device.
    /// Used by the timeline to draw a live, growing bar for the local op's
    /// in-progress transmission before it finalizes on key-up.
    @Published public private(set) var liveOwnKeyStarts: [Int64] = []
    @Published public var lagMs: Int64 = 0
    @Published public var breakInEnabled: Bool = false {
        didSet {
            UserDefaults.standard.set(breakInEnabled, forKey: Self.breakInDefaultsKey)
            if !breakInEnabled {
                // Drop any in-flight live bars — they're no longer being transmitted.
                liveOwnKeyStarts.removeAll()
            }
        }
    }

    /// When true, channel joins set the protocol `Private` flag and the contact
    /// presence scanner also probes with `Private: true`. Hides this client's
    /// rooms from the server's public Rooms list. Defaults to true; toggleable
    /// from Settings.
    @Published public var privateMode: Bool = true {
        didSet {
            UserDefaults.standard.set(privateMode, forKey: Self.privateModeDefaultsKey)
            let p = privateMode
            Task { await client.setPrivate(p) }
        }
    }

    @Published public var lastNotice: String?
    @Published public var clientCount: Int = 0
    @Published public var roomDecoderEnabled: Bool = false

    /// Whether a Vail Adapter is connected for MIDI output (config + RX piezo).
    @Published public var midiAdapterConnected: Bool = false

    /// Keyer mode pushed to the adapter (raw value of MIDIOutput.KeyerMode).
    @Published public var keyerMode: Int = MIDIOutput.KeyerMode.straightKey.rawValue {
        didSet {
            UserDefaults.standard.set(keyerMode, forKey: Self.keyerModeDefaultsKey)
            let mode = MIDIOutput.KeyerMode(rawValue: keyerMode) ?? .straightKey
            Task { await midiOutput?.setKeyerMode(mode) }
        }
    }

    /// Keyer speed in WPM, sent to the adapter as a dit duration. Only affects
    /// adapter-generated keying (iambic/bug modes), not straight key.
    @Published public var keyerWPM: Int = 20 {
        didSet {
            UserDefaults.standard.set(keyerWPM, forKey: Self.keyerWPMDefaultsKey)
            let wpm = keyerWPM
            Task { await midiOutput?.setSpeed(wpm: wpm) }
        }
    }

    /// When enabled, received tones also buzz the adapter's piezo.
    @Published public var adapterRxFeedbackEnabled: Bool = false {
        didSet { UserDefaults.standard.set(adapterRxFeedbackEnabled, forKey: Self.adapterRxFeedbackDefaultsKey) }
    }

    public struct ChatMessage: Identifiable, Sendable, Equatable {
        public let id = UUID()
        public let text: String
        public let callsign: String?
        public let timestampMs: Int64
    }

    // MARK: - Components

    private let client: VailClient
    private let keyer: KeyerEngine
    private var midi: MIDIInput?
    private var midiOutput: MIDIOutput?
    private var clientEventTask: Task<Void, Never>?
    private var rosterPruneTask: Task<Void, Never>?

    /// Last local-clock ms we saw each callsign in a roster snapshot. Pruning
    /// drops users whose entry is older than `userTtlMs`. This is a backstop
    /// for cases where the server doesn't push a fresh roster after a user
    /// leaves (channel switch races, brief network blips, etc.).
    private var userLastSeenMs: [String: Int64] = [:]
    public static let userTtlMs: Int64 = 60000
    public static let userPruneIntervalMs: Int64 = 10000

    /// Per-key TX state.
    private struct KeyState {
        var isDown: Bool = false
        var beginLocalMs: Int64 = 0
    }

    private var keyState: [MIDIInput.Key: KeyState] = [
        .straight: KeyState(),
        .dit: KeyState(),
        .dah: KeyState(),
    ]
    private var stuckKeyTask: Task<Void, Never>?

    /// The channel of the most recent successful connection. Used on
    /// `.connecting` to tell a channel switch (where roster, signal events,
    /// and chat are no longer relevant) apart from a plain network-blip
    /// reconnect (where they are).
    private var lastConnectedChannel: String?

    /// Auto-disable break-in if a key has been down for >10s.
    /// Matches the web client's stuck-key safety.
    public static let stuckKeyTimeoutMs: Int64 = 10000

    private static let callsignDefaultsKey = "RepeaterModel.callsign"
    private static let channelDefaultsKey = "RepeaterModel.channel"
    private static let txToneDefaultsKey = "RepeaterModel.txTone"
    private static let rxDelayDefaultsKey = "RepeaterModel.rxDelayMs"
    private static let breakInDefaultsKey = "RepeaterModel.breakInEnabled"
    private static let keyerModeDefaultsKey = "RepeaterModel.keyerMode"
    private static let keyerWPMDefaultsKey = "RepeaterModel.keyerWPM"
    private static let adapterRxFeedbackDefaultsKey = "RepeaterModel.adapterRxFeedback"
    private static let privateModeDefaultsKey = "RepeaterModel.privateMode"
    private static let chatReadWatermarkDefaultsKey = "RepeaterModel.chatReadWatermarkMs"
    private static let serverURLDefaultsKey = "RepeaterModel.serverURL"

    public init(initialCallsign: String? = nil) {
        let defaults = UserDefaults.standard

        let storedCallsign = defaults.string(forKey: Self.callsignDefaultsKey)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let cs = initialCallsign
            ?? (storedCallsign?.isEmpty == false ? storedCallsign! : Self.generateAnonymousCallsign())

        let storedChannel = defaults.string(forKey: Self.channelDefaultsKey)
        let resolvedChannel = (storedChannel?.isEmpty == false) ? storedChannel! : "General"

        let resolvedTxTone = defaults.object(forKey: Self.txToneDefaultsKey) != nil
            ? defaults.integer(forKey: Self.txToneDefaultsKey)
            : 72
        let resolvedRxDelay = defaults.object(forKey: Self.rxDelayDefaultsKey) != nil
            ? defaults.integer(forKey: Self.rxDelayDefaultsKey)
            : 2000
        let resolvedBreakIn = defaults.object(forKey: Self.breakInDefaultsKey) != nil
            ? defaults.bool(forKey: Self.breakInDefaultsKey)
            : false

        // Stored let properties first.
        client = VailClient(callsign: cs, txTone: resolvedTxTone)
        keyer = KeyerEngine()

        // didSet does not fire from designated initializer, so these loads do not
        // re-write themselves to UserDefaults.
        callsign = cs
        channel = resolvedChannel
        txTone = resolvedTxTone
        rxDelayMs = resolvedRxDelay
        breakInEnabled = resolvedBreakIn

        keyerMode = defaults.object(forKey: Self.keyerModeDefaultsKey) != nil
            ? defaults.integer(forKey: Self.keyerModeDefaultsKey)
            : MIDIOutput.KeyerMode.straightKey.rawValue
        keyerWPM = defaults.object(forKey: Self.keyerWPMDefaultsKey) != nil
            ? defaults.integer(forKey: Self.keyerWPMDefaultsKey)
            : 20
        adapterRxFeedbackEnabled = defaults.object(forKey: Self.adapterRxFeedbackDefaultsKey) != nil
            ? defaults.bool(forKey: Self.adapterRxFeedbackDefaultsKey)
            : false
        privateMode = defaults.object(forKey: Self.privateModeDefaultsKey) != nil
            ? defaults.bool(forKey: Self.privateModeDefaultsKey)
            : true
        if let storedURL = defaults.string(forKey: Self.serverURLDefaultsKey), !storedURL.isEmpty {
            serverURLString = storedURL
        }

        keyer.localTxToneMIDI = resolvedTxTone
        applyServerURL()
    }

    /// Push the configured server URL into the client. Called at init and before
    /// each connect so a settings change takes effect on the next connection.
    private func applyServerURL() {
        guard let url = URL(string: serverURLString.trimmingCharacters(in: .whitespacesAndNewlines)) else { return }
        Task { await client.setBaseURL(url) }
    }

    // MARK: - Lifecycle

    public func start() {
        do {
            try keyer.start()
        } catch {
            log.error("KeyerEngine.start failed: \(error.localizedDescription)")
        }

        do {
            let m = try MIDIInput()
            m.onEvent = { [weak self] event in
                guard let self else { return }
                Task { @MainActor in
                    self.handleMIDIEvent(event)
                }
            }
            midi = m
        } catch {
            log.error("MIDIInput init failed: \(error.localizedDescription)")
        }

        do {
            let out = try MIDIOutput()
            midiOutput = out
            let mode = MIDIOutput.KeyerMode(rawValue: keyerMode) ?? .straightKey
            let wpm = keyerWPM
            let tone = txTone
            Task {
                await out.setOnConnectionChange { [weak self] connected in
                    Task { @MainActor in self?.midiAdapterConnected = connected }
                }
                await out.configure(keyerMode: mode, wpm: wpm, sidetoneMIDINote: tone)
                await out.connectToAdapter()
            }
        } catch {
            log.error("MIDIOutput init failed: \(error.localizedDescription)")
        }

        clientEventTask?.cancel()
        clientEventTask = Task { [weak self] in
            guard let self else { return }
            for await event in await client.events() {
                await MainActor.run {
                    self.handleClientEvent(event)
                }
            }
        }

        rosterPruneTask?.cancel()
        let interval = Self.userPruneIntervalMs
        rosterPruneTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(Int(interval)))
                if Task.isCancelled { return }
                await MainActor.run { self?.pruneStaleUsers() }
            }
        }
    }

    public func stop() {
        clientEventTask?.cancel()
        clientEventTask = nil
        rosterPruneTask?.cancel()
        rosterPruneTask = nil
        stuckKeyTask?.cancel()
        stuckKeyTask = nil
        Task { await client.disconnect() }
        keyer.stop()
    }

    public func connect() {
        applyServerURL()
        let (isPrivate, isDecoder) = Self.flags(for: channel, defaultPrivate: privateMode)
        Task {
            await client.connect(channel: channel, isPrivate: isPrivate, isDecoder: isDecoder)
        }
    }

    public func disconnect() {
        Task { await client.disconnect() }
    }

    public func switchChannel(_ name: String) {
        channel = name
        chatMessages.removeAll()
        unreadChatCount = 0
        users.removeAll()
        signalEvents.removeAll()
        liveOwnKeyStarts.removeAll()
        let (isPrivate, isDecoder) = Self.flags(for: name, defaultPrivate: privateMode)
        Task {
            await client.disconnect()
            await client.connect(channel: name, isPrivate: isPrivate, isDecoder: isDecoder)
        }
    }

    /// Channel-specific overrides for the hello flags. The "Decoder" room
    /// drops us immediately (POSIX 57 ENOTCONN right after the hello) when
    /// we send the defaults; both Decoder=true (it's a server-side-decoder
    /// room) and Private=false (it's a public broadcast room) are plausible
    /// requirements, so we set both for that channel.
    private static func flags(for channel: String, defaultPrivate: Bool) -> (isPrivate: Bool, isDecoder: Bool) {
        if channel.caseInsensitiveCompare("Decoder") == .orderedSame {
            return (false, true)
        }
        return (defaultPrivate, false)
    }

    /// Deterministic private-channel name for a one-to-one QSO with another
    /// operator. Both parties derive the same name (callsigns are sorted), so
    /// either can "Start a QSO" and meet on the same channel.
    public func qsoChannelName(with otherCallsign: String) -> String {
        let mine = callsign.uppercased()
        let theirs = otherCallsign.uppercased()
        let joined = [mine, theirs].sorted().joined(separator: "-")
        let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-"))
        let sanitized = String(joined.unicodeScalars.filter { allowed.contains($0) })
        return "QSO-\(sanitized)"
    }

    public func setCallsign(_ newCallsign: String) {
        let trimmed = newCallsign.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        callsign = trimmed
        Task { await client.setCallsign(trimmed) }
    }

    public func setTxTone(_ note: Int) {
        let clamped = max(0, min(127, note))
        txTone = clamped
        keyer.localTxToneMIDI = clamped
        Task { await client.setTxTone(clamped) }
        Task { await midiOutput?.setSidetone(midiNote: clamped) }
    }

    public func sendChat(_ text: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmed.isEmpty {
            recordSignal(SignalEvent(
                callsign: callsign,
                startLocalMs: Int64(Date().timeIntervalSince1970 * 1000),
                kind: .chat(text: trimmed),
                origin: .sent
            ))
        }
        Task { await client.sendChat(text) }
    }

    /// Clear the unread chat badge. ChatView calls this on appear.
    public func markChatRead() {
        unreadChatCount = 0
        if let newest = chatMessages.map(\.timestampMs).max() {
            advanceChatReadWatermark(to: newest)
        }
    }

    // MARK: - Chat read watermark

    /// Per-channel timestamp (server clock ms) of the newest chat message the
    /// user has seen, persisted across launches. The server replays the whole
    /// chat backlog on every join; without this every launch re-badged the
    /// same old messages as unread.
    private func chatReadWatermarkMs() -> Int64 {
        let dict = UserDefaults.standard.dictionary(forKey: Self.chatReadWatermarkDefaultsKey)
        return (dict?[channel] as? NSNumber)?.int64Value ?? 0
    }

    private func advanceChatReadWatermark(to timestampMs: Int64) {
        guard timestampMs > chatReadWatermarkMs() else { return }
        var dict = UserDefaults.standard.dictionary(forKey: Self.chatReadWatermarkDefaultsKey) ?? [:]
        dict[channel] = NSNumber(value: timestampMs)
        UserDefaults.standard.set(dict, forKey: Self.chatReadWatermarkDefaultsKey)
    }

    // MARK: - Signal timeline

    private func recordSignal(_ event: SignalEvent) {
        signalEvents.append(event)
        if signalEvents.count > Self.maxSignalEvents {
            signalEvents.removeFirst(signalEvents.count - Self.maxSignalEvents)
        }
    }

    /// Re-broadcast the adapter wake/config sequence. Use when the adapter is
    /// plugged in but not responding (it may have powered up in keyboard mode).
    public func wakeMidiAdapter() {
        Task { await midiOutput?.wakeAdapter() }
    }

    // MARK: - Key event handling

    private func handleMIDIEvent(_ event: MIDIInput.Event) {
        let wasDown = keyState[event.key]?.isDown ?? false
        if event.isDown, !wasDown {
            handleKeyDown(event)
        } else if !event.isDown, wasDown {
            handleKeyUp(event)
        }
    }

    /// Public for the on-screen touch key.
    public func touchKey(isDown: Bool) {
        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
        let event = MIDIInput.Event(
            key: .straight,
            isDown: isDown,
            machTimestamp: mach_absolute_time(),
            timestampMs: nowMs
        )
        handleMIDIEvent(event)
    }

    private func handleKeyDown(_ event: MIDIInput.Event) {
        keyState[event.key]?.isDown = true
        keyState[event.key]?.beginLocalMs = event.timestampMs

        // Local sidetone fires immediately, regardless of break-in.
        keyer.beginTx()
        // Only show on the timeline when we're actually transmitting.
        if breakInEnabled {
            liveOwnKeyStarts.append(event.timestampMs)
        }

        startStuckKeyWatchdog()
    }

    private func handleKeyUp(_ event: MIDIInput.Event) {
        guard let begin = keyState[event.key]?.beginLocalMs else { return }
        let durationMs = max(0, event.timestampMs - begin)
        keyState[event.key]?.isDown = false

        keyer.endTx()

        // Finalize: swap the live bar for a recorded event atomically so the
        // bar doesn't visually flicker between the two.
        if let idx = liveOwnKeyStarts.firstIndex(of: begin) {
            liveOwnKeyStarts.remove(at: idx)
        }

        if breakInEnabled, durationMs > 0 {
            recordSignal(SignalEvent(
                callsign: callsign,
                startLocalMs: begin,
                kind: .tone(durationMs: Int(durationMs), midiNote: txTone),
                origin: .sent
            ))
        }

        if breakInEnabled, durationMs > 0, durationMs <= UInt16.max {
            Task { [client] in
                await client.transmitTone(
                    durationMs: UInt16(durationMs),
                    beginLocalMs: begin
                )
            }
        }

        // If no keys are down, cancel the stuck-key watchdog.
        if keyState.values.allSatisfy({ !$0.isDown }) {
            stuckKeyTask?.cancel()
            stuckKeyTask = nil
        }
    }

    private func startStuckKeyWatchdog() {
        guard stuckKeyTask == nil else { return }
        let timeout = Self.stuckKeyTimeoutMs
        stuckKeyTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(Int(timeout)))
            if Task.isCancelled { return }
            guard let self else { return }
            self.handleStuckKey()
        }
    }

    private func handleStuckKey() {
        // Force all keys up and disable break-in.
        for key in keyState.keys {
            keyState[key]?.isDown = false
        }
        keyer.endTx()
        keyer.panic()
        liveOwnKeyStarts.removeAll()
        breakInEnabled = false
        lastNotice = "Stuck key detected. Break-in disabled."
        log.warning("Stuck key — panic")
        stuckKeyTask = nil
    }

    // MARK: - Client event handling

    private func handleClientEvent(_ event: VailClient.Event) {
        switch event {
        case let .stateChanged(s):
            connectionState = s
            // A fresh socket invalidates whatever roster we had from the
            // previous channel/session. Clearing here (rather than in
            // switchChannel) closes a race: any stale roster event from the
            // old socket that was still in the AsyncStream gets processed
            // BEFORE this state transition, then we wipe before the new
            // socket's hello-response roster lands.
            if s == .connecting {
                users.removeAll()
                userLastSeenMs.removeAll()
                // Channel-scoped buffers (chat, activity timeline) only get
                // wiped if we're actually moving to a different channel.
                // Reconnect-after-blip stays on the same channel and should
                // preserve history.
                if let last = lastConnectedChannel, last != channel {
                    chatMessages.removeAll()
                    unreadChatCount = 0
                    signalEvents.removeAll()
                    liveOwnKeyStarts.removeAll()
                }
            }
            if s == .connected {
                lastConnectedChannel = channel
                // Any "Reconnecting…" / disconnect notice is obsolete the
                // moment we're connected again. Without this the notice text
                // stayed on screen forever after a successful reconnect.
                lastNotice = nil
            }

        case let .tone(at, durationMs, fromCandidates, txTone):
            let note = txTone ?? 69
            let playAt = at + Int64(rxDelayMs)
            keyer.scheduleReceivedTone(
                midiNote: note,
                durationMs: durationMs,
                playAtLocalMs: playAt
            )
            if adapterRxFeedbackEnabled {
                let buzzNote = UInt8(max(0, min(127, note)))
                Task { await midiOutput?.scheduleBuzz(note: buzzNote, durationMs: durationMs, playAtLocalMs: playAt) }
            }
            // Chart the bar at the moment audio actually plays so it lines up
            // with what the operator hears. When several operators share a TX
            // tone the wire can't say who keyed (tone relays carry no
            // Callsign), so those draw in a shared lane listing all candidates
            // rather than being hidden.
            let lane: String = switch fromCandidates.count {
            case 0: "?"
            case 1: fromCandidates[0]
            default: fromCandidates.sorted().joined(separator: "/")
            }
            recordSignal(SignalEvent(
                callsign: lane,
                startLocalMs: playAt,
                kind: .tone(durationMs: Int(durationMs), midiNote: txTone),
                origin: .received
            ))

        case let .chat(text, cs, ts):
            // The server replays the full chat backlog on every (re)join,
            // with original timestamps. Drop exact duplicates so a reconnect
            // on the same channel doesn't repeat history.
            if chatMessages.contains(where: {
                $0.timestampMs == ts && $0.callsign == cs && $0.text == text
            }) {
                break
            }
            chatMessages.append(ChatMessage(text: text, callsign: cs, timestampMs: ts))
            // Cap chat history to avoid unbounded growth.
            if chatMessages.count > 500 { chatMessages.removeFirst(chatMessages.count - 500) }
            // Badge only messages newer than the persisted last-read
            // watermark — replayed backlog the user already saw is not "new".
            if isChatViewActive || cs == callsign {
                advanceChatReadWatermark(to: ts)
            } else if ts > chatReadWatermarkMs() {
                unreadChatCount += 1
            }
            recordSignal(SignalEvent(
                callsign: cs ?? "?",
                startLocalMs: ts,
                kind: .chat(text: text),
                origin: .received
            ))

        case let .roster(userList, roomList):
            users = userList
            let now = Int64(Date().timeIntervalSince1970 * 1000)
            // Refresh last-seen for every callsign in the snapshot; do NOT
            // touch entries for callsigns not in the snapshot. The prune
            // task drops those once they age out past userTtlMs.
            for u in userList {
                userLastSeenMs[u.callsign] = now
            }
            if let r = roomList { rooms = r }
            clientCount = userList.count

        case let .decoderRoomChanged(enabled):
            roomDecoderEnabled = enabled

        case let .ownEcho(lag, _):
            // For v0, show the latest instantaneous lag. Smoothing/averaging
            // is a polish task — see CLAUDE.md §6.
            lagMs = lag

        case let .notice(text):
            lastNotice = text
        }
    }

    // MARK: - Roster pruning

    /// Drop users from the local roster whose last-seen timestamp is older
    /// than `userTtlMs`. Server roster snapshots are full replacements, so in
    /// the steady state this never trims anything — it only catches stragglers
    /// from a stale snapshot the server forgot to update.
    private func pruneStaleUsers() {
        guard !users.isEmpty else { return }
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let ttl = Self.userTtlMs
        let alive = users.filter { user in
            guard let seen = userLastSeenMs[user.callsign] else { return false }
            return now - seen <= ttl
        }
        if alive.count != users.count {
            users = alive
            for (cs, _) in userLastSeenMs where !alive.contains(where: { $0.callsign == cs }) {
                userLastSeenMs.removeValue(forKey: cs)
            }
        }
    }

    // MARK: - Helpers

    private static func generateAnonymousCallsign() -> String {
        let n = Int.random(in: 1000 ... 9999)
        return "anon\(n)"
    }
}
