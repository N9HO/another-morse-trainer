// MIDIInput.swift
// CoreMIDI client for the Vail Adapter and BLE MIDI keyers.
//
// Supported keys send MIDI note-on/off; velocity > 0 = pressed, velocity 0
// (or Note Off status) = released. The note map (MorseKit's MIDIKeyParser):
//   straight = 0 (Vail Adapter straight key) or 30 (Morserino-32 Pocket,
//              keyer in Straight Key mode)
//   dit      = 1 (Vail Adapter), 20 (N6ARA TinyMIDI, Pocket), or 61
//   dah      = 2 (Vail Adapter), 21 (N6ARA TinyMIDI, Pocket), or 62
// 61/62 are older mappings with no known source. `MIDIInput` reports each
// note's edges; `MIDIKeyRouter` (below) turns them into the one logical key,
// holding some notes and timing raw paddle contacts with the app's own
// paddle keyer (#380).
//
// MIDIPacket.timeStamp is in mach_absolute_time units — use this for accurate
// key event timing (sub-millisecond), not the host's Date().
// See CLAUDE.md §4.
//
// The wire format itself — the note map and walking a packet that holds more
// than one message — lives in MorseKit's MIDIKeyParser, where it can be
// checked without a device. A BLE-MIDI key must first be connected through
// BluetoothMIDISheet before CoreMIDI will enumerate it at all.

import CoreMIDI
import Foundation
import OSLog

private let log = Logger(subsystem: "com.justinrogers.MorseTrainer", category: "midi")

public final class MIDIInput {
    /// Which contact fired. Shared with the wire-format parser so the app and
    /// the MorseKitCheck harness talk about keys in the same terms.
    public typealias Key = MIDIKeyPaddle

    public struct Event: Sendable {
        public let key: Key
        /// The note it came on, which decides its route (`MIDIKeyRouter`).
        public let note: UInt8
        public let isDown: Bool
        /// mach_absolute_time at the moment the key event occurred.
        public let machTimestamp: UInt64
        /// Same event as wall-clock ms since Unix epoch.
        public let timestampMs: Int64
    }

    /// Fired from a CoreMIDI thread for every key edge.
    ///
    /// Both callbacks go through `stateLock`. They are set on the main actor
    /// (a view appearing, a keyer being wired up) and read on a CoreMIDI
    /// thread, which is a data race on a plain `var` — a torn read of a closure
    /// is a crash, not a glitch. Nothing diagnoses it: the reader is a block
    /// handed to a C API, so even `complete` concurrency checking sees no
    /// construct here to check. It has to be held by hand.
    public var onEvent: (@Sendable (Event) -> Void)? {
        get { stateLock.lock(); defer { stateLock.unlock() }; return storedOnEvent }
        set { stateLock.lock(); storedOnEvent = newValue; stateLock.unlock() }
    }

    /// Fired (from a CoreMIDI thread) whenever the set of connected sources
    /// changes — hot-plug, unplug, or the initial scan. Carries the current
    /// device names so the UI can show what hardware key is attached.
    public var onSourcesChanged: (@Sendable ([String]) -> Void)? {
        get { stateLock.lock(); defer { stateLock.unlock() }; return storedOnSourcesChanged }
        set { stateLock.lock(); storedOnSourcesChanged = newValue; stateLock.unlock() }
    }

    private var storedOnEvent: (@Sendable (Event) -> Void)?
    private var storedOnSourcesChanged: (@Sendable ([String]) -> Void)?

    private var client: MIDIClientRef = 0
    private var port: MIDIPortRef = 0
    /// Endpoints already connected to the input port, so hot-plug re-scans
    /// don't connect (and double-deliver) the same source twice.
    private var connectedRefs = Set<MIDIEndpointRef>()
    private var sourceNames: [String] = []
    /// Notes currently held, by the last edge each one sent. Kept so an
    /// unplugged key — which can no longer send its note-offs — gets a key-up
    /// delivered on its behalf (see `connectAllSources`). Behind `stateLock`
    /// like the callbacks: written on the CoreMIDI packet thread, read and
    /// cleared on the notification thread.
    private var heldNotes = Set<UInt8>()
    private let stateLock = NSLock()

    /// Names of the MIDI sources currently connected (e.g. "Vail Adapter").
    public var connectedSourceNames: [String] {
        stateLock.lock(); defer { stateLock.unlock() }
        return sourceNames
    }

    /// Re-enumerate and connect any source that appeared since the last scan.
    ///
    /// CoreMIDI does notify us, but a BLE-MIDI key connected through the
    /// Bluetooth browser (see BluetoothMIDISheet) is worth confirming on demand
    /// rather than trusting a notification we may have raced.
    public func rescan() {
        connectAllSources()
    }

    /// Timebase for mach_absolute_time → nanoseconds conversion. Cached.
    private static let timebase: mach_timebase_info_data_t = {
        var info = mach_timebase_info_data_t()
        mach_timebase_info(&info)
        return info
    }()

    public init() throws {
        try createClient()
        try createInputPort()
        connectAllSources()
    }

    deinit {
        if port != 0 { MIDIPortDispose(port) }
        if client != 0 { MIDIClientDispose(client) }
    }

    private func createClient() throws {
        let status = MIDIClientCreateWithBlock(
            "VailMorseMIDIClient" as CFString,
            &client
        ) { [weak self] notification in
            self?.handleNotification(notification)
        }
        try check(status, op: "MIDIClientCreateWithBlock")
    }

    private func createInputPort() throws {
        let status = MIDIInputPortCreateWithBlock(
            client,
            "VailMorseInput" as CFString,
            &port
        ) { [weak self] packetListPtr, _ in
            self?.process(packetList: packetListPtr)
        }
        try check(status, op: "MIDIInputPortCreateWithBlock")
    }

    private func connectAllSources() {
        stateLock.lock()
        var present = Set<MIDIEndpointRef>()
        var names: [String] = []
        let count = MIDIGetNumberOfSources()
        for i in 0 ..< count {
            let src = MIDIGetSource(i)
            if !connectedRefs.contains(src) {
                let result = MIDIPortConnectSource(port, src, nil)
                if result != noErr {
                    log.warning("Failed to connect MIDI source \(i): \(result)")
                    continue   // left out of `present` so a later re-scan retries
                }
                log.info("Connected MIDI source: \(self.endpointName(src) ?? "(unnamed)")")
            }
            present.insert(src)
            if let name = endpointName(src) { names.append(name) }
        }
        let removed = !connectedRefs.isSubset(of: present)
        connectedRefs = present
        let changed = names != sourceNames
        sourceNames = names
        // A key that has gone can't send its note-offs any more: release
        // whatever it left held, through the same callback the packets use,
        // so a keyer sees an ordinary key-up rather than a transmit that
        // never ends (Android `MidiKeyInput.disconnect`). Taken under the
        // lock, delivered outside it — the handler may re-enter us.
        let released: Set<UInt8> = removed ? heldNotes : []
        if removed { heldNotes.removeAll() }
        let handler = released.isEmpty ? nil : storedOnEvent
        stateLock.unlock()
        if changed { onSourcesChanged?(names) }
        if let handler, !released.isEmpty {
            let mach = mach_absolute_time()
            let ms = Int64(Date().timeIntervalSince1970 * 1000)
            for note in released {
                guard let key = MIDIKeyParser.paddle(forNote: note) else { continue }
                handler(Event(key: key, note: note, isDown: false, machTimestamp: mach, timestampMs: ms))
            }
        }
    }

    private func handleNotification(_ notification: UnsafePointer<MIDINotification>) {
        let id = notification.pointee.messageID
        // A device appearing fires .msgObjectAdded, but a USB MIDI device being
        // plugged in can surface only as .msgSetupChanged on some iOS versions.
        // Re-scan on any of these so a hot-plugged Vail Adapter always connects
        // and an unplugged one drops off the connected-device readout.
        if id == .msgObjectAdded || id == .msgObjectRemoved || id == .msgSetupChanged {
            connectAllSources()
        }
    }

    // MARK: - Packet processing

    private func process(packetList: UnsafePointer<MIDIPacketList>) {
        let list = packetList.pointee
        var packet = list.packet
        for _ in 0 ..< list.numPackets {
            handle(packet: packet)
            packet = withUnsafePointer(to: &packet) { MIDIPacketNext($0).pointee }
        }
    }

    private func handle(packet: MIDIPacket) {
        // packet.data is a 256-byte tuple. We need the first `length` bytes —
        // all of them: one packet can carry several messages, and a BLE-MIDI
        // key regularly sends a key-down and its key-up in the same burst.
        let length = Int(packet.length)
        guard length >= 2 else { return }

        var bytes = [UInt8](repeating: 0, count: length)
        withUnsafeBytes(of: packet.data) { rawPtr in
            for i in 0 ..< length {
                bytes[i] = rawPtr[i]
            }
        }

        let messages = MIDIKeyParser.messages(in: bytes)
        guard !messages.isEmpty else {
            log.debug("MIDI in: \(length) byte(s), no key events")
            return
        }

        // Read the handler once, not once per message: a packet carrying a
        // key-down and its key-up must deliver both to the same closure even if
        // the UI swaps handlers between them. The held set is updated under
        // the same lock acquisition so the unplug path always sees the state
        // as of the last packet delivered.
        stateLock.lock()
        for message in messages {
            if message.isDown { heldNotes.insert(message.note) } else { heldNotes.remove(message.note) }
        }
        let handler = storedOnEvent
        stateLock.unlock()
        guard let handler else { return }
        let timestampMs = Self.machTimeToWallClockMs(packet.timeStamp)
        for message in messages {
            handler(Event(
                key: message.paddle,
                note: message.note,
                isDown: message.isDown,
                machTimestamp: packet.timeStamp,
                timestampMs: timestampMs
            ))
        }
    }

    // MARK: - Helpers

    /// Convert mach_absolute_time to wall-clock ms since Unix epoch.
    ///
    /// This uses a snapshot of (mach, wall) taken now and extrapolates. Good
    /// enough for sub-ms precision since the MIDI packet timestamp is
    /// typically within microseconds of now.
    private static func machTimeToWallClockMs(_ machTime: UInt64) -> Int64 {
        let nowMach = mach_absolute_time()
        let nowWallMs = Int64(Date().timeIntervalSince1970 * 1000)

        // Difference in mach units, converted to nanoseconds.
        let deltaMach = Int64(machTime) - Int64(nowMach)
        let deltaNs = deltaMach * Int64(timebase.numer) / Int64(timebase.denom)
        let deltaMs = deltaNs / 1_000_000
        return nowWallMs + deltaMs
    }

    private func endpointName(_ endpoint: MIDIEndpointRef) -> String? {
        var param: Unmanaged<CFString>?
        let status = MIDIObjectGetStringProperty(endpoint, kMIDIPropertyName, &param)
        guard status == noErr, let cf = param?.takeRetainedValue() else { return nil }
        return cf as String
    }

    private func check(_ status: OSStatus, op: String) throws {
        guard status == noErr else {
            log.error("\(op) failed: \(status)")
            throw MIDIInputError.osStatus(op, status)
        }
    }
}

public enum MIDIInputError: Error {
    case osStatus(String, OSStatus)
}

/// Turns a MIDI key's notes into the one logical key the app keys from, the
/// way a Vail Adapter in a keyer mode sends it (#380). Two kinds of note
/// (`MIDIKeyParser.route`):
///
/// - Held notes key for as long as they are held: the straight keys, and 1/2
///   while the adapter Keyer mode is anything but Passthrough (there the
///   adapter has already timed each element, or the paddle is a straight
///   key). Overlapping presses hold the key: down on the first, up only when
///   the last is released.
/// - Raw paddle contacts — 1/2 from a Vail Adapter in Passthrough, 20/21 from
///   an N6ARA TinyMIDI or a Morserino-32 Pocket, 61/62 — go into a
///   `PaddleKeyerDriver`, the on-screen paddles' keyer, at the Settings paddle
///   mode and speed, so a squeeze is iambic keying rather than one long tone.
///   Its elements key the same logical key.
///
/// Main-actor: `MIDIInput` delivers on a CoreMIDI thread, and its consumers
/// hop here before calling `handle`. Mirrors the routing inside the Android
/// and desktop `MidiKeyInput`.
@MainActor
final class MIDIKeyRouter {
    /// Every change of the logical key, with when it happened in wall-clock
    /// ms: the packet's own time for a held note, the time the keyer
    /// scheduled it for an element.
    var onKey: ((_ isDown: Bool, _ atMs: Int64) -> Void)?
    /// A raw paddle press went to the app's keyer: the elements that follow
    /// are the app's, made at the Settings speed, which the Sending Analyzer
    /// judges as paddles through a keyer.
    var onAppKeyer: (() -> Void)?

    private var heldNotes = Set<UInt8>()
    /// Raw paddle notes down, by the paddle each was routed to when it went
    /// down — so its release goes the same way even if the keyer mode changed
    /// meanwhile, and two notes on one paddle (1 and 20) hold it until both
    /// are up.
    private var keyedNotes: [UInt8: PaddleKeyer.Element] = [:]
    private var keyerDown = false
    private var reported = false
    private let paddles: PaddleKeyerDriver

    init() {
        let settings = AppSettings.load()
        paddles = PaddleKeyerDriver(mode: settings.paddleMode, wpm: settings.wpm)
        paddles.onKey = { [weak self] isDown, atMs in
            self?.keyerEdge(isDown: isDown, atMs: atMs)
        }
    }

    func handle(_ event: MIDIInput.Event) {
        let note = event.note
        if !event.isDown {
            if let element = keyedNotes.removeValue(forKey: note) {
                paddles.paddle(element, isDown: keyedNotes.values.contains(element))
            } else if heldNotes.remove(note) != nil {
                report(atMs: event.timestampMs)
            }
            return
        }
        let adapterMode = VailKeyerMode(rawValue: RepeaterModel.storedKeyerMode) ?? .straightKey
        switch MIDIKeyParser.route(forNote: note, adapterSendsRawPaddles: adapterMode.sendsRawPaddles) {
        case nil:
            return
        case .held?:
            heldNotes.insert(note)
            report(atMs: event.timestampMs)
        case .keyerDit?:
            keyerPress(note: note, element: .dit)
        case .keyerDah?:
            keyerPress(note: note, element: .dah)
        }
    }

    /// Let go of everything: the screen is going away, or the key unplugged.
    func releaseAll() {
        heldNotes.removeAll()
        keyedNotes.removeAll()
        paddles.stop()
        report(atMs: Int64(Date().timeIntervalSince1970 * 1000))
    }

    private func keyerPress(note: UInt8, element: PaddleKeyer.Element) {
        // Follow Settings, as the on-screen paddles do; the driver applies a
        // change only once it is idle, so an element in flight keeps its
        // timing. Read only from idle, so a squeeze doesn't decode settings.
        if !paddles.isHeld(.dit), !paddles.isHeld(.dah) {
            let settings = AppSettings.load()
            paddles.configure(mode: settings.paddleMode, wpm: settings.wpm)
        }
        keyedNotes[note] = element
        onAppKeyer?()
        paddles.paddle(element, isDown: true)
    }

    private func keyerEdge(isDown: Bool, atMs: Int64) {
        keyerDown = isDown
        report(atMs: atMs)
    }

    /// Down while any held note is down or the keyer's tone is on; reported
    /// only when that changes.
    private func report(atMs: Int64) {
        let now = !heldNotes.isEmpty || keyerDown
        guard now != reported else { return }
        reported = now
        onKey?(now, atMs)
    }
}
