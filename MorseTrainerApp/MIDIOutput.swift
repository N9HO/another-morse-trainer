// MIDIOutput.swift
// CoreMIDI output to the Vail Adapter.
//
// The adapter boots in HID keyboard mode and emits Ctrl key up/down events. It
// also enumerates as a MIDI device. Sending any Control Change switches it into
// MIDI mode and suppresses the keyboard output. We send `B0 00 00` on connect —
// matching the web client (inputs.mjs) and the Dry Run app — so the adapter
// stops typing into iOS while VailMorse drives it.
//
// Outbound message vocabulary (channel 1, see CLAUDE.md §4):
//   B0 00 vv   Mode: vv >= 0x40 = Keyboard, vv < 0x40 = MIDI (we send 0x00)
//   B0 01 vv   Dit duration: ms = vv * 2
//   B0 02 vv   Sidetone MIDI note (also drives the adapter's piezo)
//   C0 vv      Keyer mode (Program Change)
//   90 NN 7F   Buzz the adapter at MIDI note NN (RX feedback)
//   80 NN 00   Silence the adapter
//
// This is the output counterpart to MIDIInput. See CLAUDE.md §6 (data flow).

import CoreMIDI
import Foundation
import OSLog

private let log = Logger(subsystem: "com.justinrogers.MorseTrainer", category: "midi.out")

public actor MIDIOutput {
    /// Keyer mode set on the adapter via Program Change. Values match the
    /// Vail adapter firmware (see CLAUDE.md §4).
    public enum KeyerMode: Int, CaseIterable, Sendable {
        case passthrough = 0
        case straightKey = 1
        case bug = 2
        case electricBug = 3
        case singleDot = 4
        case ultimatic = 5
        case plainIambic = 6
        case iambicA = 7
        case iambicB = 8
        case keyahead = 9

        public var displayName: String {
            switch self {
            case .passthrough: "Passthrough"
            case .straightKey: "Straight Key"
            case .bug: "Bug"
            case .electricBug: "Electric Bug"
            case .singleDot: "Single Dot"
            case .ultimatic: "Ultimatic"
            case .plainIambic: "Plain Iambic"
            case .iambicA: "Iambic A"
            case .iambicB: "Iambic B"
            case .keyahead: "Keyahead"
            }
        }
    }

    /// Adapter configuration mirrored locally so it can be (re)applied whenever
    /// the adapter (re)connects.
    private struct Config {
        var ditDurationMs: Int = 60 // 20 WPM (1200 / 20)
        var keyerMode: KeyerMode = .straightKey
        var sidetoneMIDINote: Int = 72 // C5
    }

    private nonisolated(unsafe) var client: MIDIClientRef = 0
    private nonisolated(unsafe) var port: MIDIPortRef = 0
    private var destination: MIDIEndpointRef = 0
    private var config = Config()

    public private(set) var isConnected = false

    private var onConnectionChange: (@Sendable (Bool) -> Void)?

    /// Timebase for ms → mach_absolute_time conversion (scheduled RX buzzes).
    private static let timebase: mach_timebase_info_data_t = {
        var info = mach_timebase_info_data_t()
        mach_timebase_info(&info)
        return info
    }()

    public init() throws {
        var newClient: MIDIClientRef = 0
        let clientStatus = MIDIClientCreateWithBlock(
            "VailMorseMIDIOutClient" as CFString,
            &newClient
        ) { [weak self] notificationPtr in
            let messageID = notificationPtr.pointee.messageID
            Task { [weak self] in await self?.handleNotification(messageID) }
        }
        guard clientStatus == noErr else {
            log.error("MIDIClientCreateWithBlock failed: \(clientStatus)")
            throw MIDIOutputError.osStatus("MIDIClientCreateWithBlock", clientStatus)
        }

        var newPort: MIDIPortRef = 0
        let portStatus = MIDIOutputPortCreate(newClient, "VailMorseOutput" as CFString, &newPort)
        guard portStatus == noErr else {
            MIDIClientDispose(newClient)
            log.error("MIDIOutputPortCreate failed: \(portStatus)")
            throw MIDIOutputError.osStatus("MIDIOutputPortCreate", portStatus)
        }

        client = newClient
        port = newPort
    }

    deinit {
        if port != 0 { MIDIPortDispose(port) }
        if client != 0 { MIDIClientDispose(client) }
    }

    // MARK: - Callbacks

    public func setOnConnectionChange(_ callback: (@Sendable (Bool) -> Void)?) {
        onConnectionChange = callback
    }

    // MARK: - Configuration

    /// Apply settings without sending. Used to seed config before the first
    /// connect so the init sequence reflects the user's preferences.
    public func configure(keyerMode: KeyerMode, wpm: Int, sidetoneMIDINote: Int) {
        config.keyerMode = keyerMode
        config.ditDurationMs = Self.ditDurationMs(forWPM: wpm)
        config.sidetoneMIDINote = max(0, min(127, sidetoneMIDINote))
    }

    public func setKeyerMode(_ mode: KeyerMode) {
        config.keyerMode = mode
        guard destination != 0 else { return }
        send([0xC0, UInt8(mode.rawValue)], to: destination)
        log.info("Set keyer mode \(mode.rawValue) (\(mode.displayName))")
    }

    public func setSpeed(wpm: Int) {
        config.ditDurationMs = Self.ditDurationMs(forWPM: wpm)
        guard destination != 0 else { return }
        send([0xB0, 0x01, UInt8(min(127, config.ditDurationMs / 2))], to: destination)
        let dit = config.ditDurationMs
        log.info("Set keyer speed \(wpm) WPM (dit \(dit)ms)")
    }

    public func setSidetone(midiNote: Int) {
        config.sidetoneMIDINote = max(0, min(127, midiNote))
        guard destination != 0 else { return }
        send([0xB0, 0x02, UInt8(config.sidetoneMIDINote)], to: destination)
    }

    // MARK: - Connection

    /// Wake the adapter and identify it as the RX-feedback target.
    ///
    /// The adapter boots in HID keyboard mode and only starts sending MIDI note
    /// events once it receives a Control Change. So this broadcast — sending
    /// the init sequence (mode switch, dit duration, keyer mode, sidetone) to
    /// *every* non-network destination — is what actually makes MIDI **input**
    /// work. We broadcast rather than only targeting a name-matched device
    /// because the adapter enumerates under different names across firmwares
    /// ("Vail", "QT Py M0", etc.) and may not match our heuristics. The
    /// positively-identified adapter (if any) becomes the buzzer destination.
    ///
    /// Idempotent — safe to call repeatedly, including from CoreMIDI
    /// setup-change notifications.
    public func connectToAdapter() {
        let destinations = nonNetworkDestinations()
        for dest in destinations {
            sendInitSequence(to: dest)
        }

        let adapter = findVailAdapterDestination()
        destination = adapter ?? 0
        let connected = adapter != nil
        let stateChanged = connected != isConnected
        let previous = isConnected
        if stateChanged {
            isConnected = connected
            onConnectionChange?(connected)
        }

        if stateChanged {
            // Log every enumerated destination on transition so we can see what
            // CoreMIDI is showing us when the adapter heuristic fails. Includes
            // manufacturer because some firmwares enumerate as "QT Py M0" /
            // "Adafruit" rather than "Vail" — see findVailAdapterDestination().
            let inventory = destinations.map { ref -> String in
                let name = endpointStringProperty(ref, kMIDIPropertyDisplayName)
                let mfr = endpointStringProperty(ref, kMIDIPropertyManufacturer)
                return "{name=\(name.isEmpty ? "?" : name), mfr=\(mfr.isEmpty ? "?" : mfr)}"
            }.joined(separator: ", ")
            let prev = previous ? "connected" : "disconnected"
            let now = connected ? "connected" : "disconnected"
            log.info("Adapter \(prev) -> \(now); \(destinations.count) destination(s): [\(inventory, privacy: .public)]")
        } else {
            log.debug("Adapter scan: \(destinations.count) destination(s), still \(connected ? "connected" : "disconnected")")
        }
    }

    /// User-triggered retry of the wake/identify sequence (Settings button).
    public func wakeAdapter() {
        connectToAdapter()
    }

    /// RX piezo feedback: buzz the adapter for a received tone, scheduled to
    /// land at the same local time as the audio playback. Note-on and note-off
    /// are timestamped via mach_absolute_time so CoreMIDI delivers them
    /// sample-accurately rather than back-to-back.
    public func scheduleBuzz(note: UInt8, durationMs: UInt16, playAtLocalMs: Int64) {
        guard destination != 0, durationMs > 0 else { return }
        let clamped = min(note, 127)
        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
        let leadMs = max(0, playAtLocalMs - nowMs)
        let onTs = machTime(forMillisFromNow: leadMs)
        let offTs = machTime(forMillisFromNow: leadMs + Int64(durationMs))
        send([0x90, clamped, 0x7F], to: destination, at: onTs)
        send([0x80, clamped, 0x00], to: destination, at: offTs)
    }

    // MARK: - Init sequence

    /// Mirrors the web repeater's connection sequence (inputs.mjs): disable
    /// keyboard mode, set dit duration, set keyer mode, set sidetone.
    private func sendInitSequence(to dest: MIDIEndpointRef) {
        send([0xB0, 0x00, 0x00], to: dest) // disable keyboard mode (enable MIDI mode)
        send([0xB0, 0x01, UInt8(min(127, config.ditDurationMs / 2))], to: dest)
        send([0xC0, UInt8(config.keyerMode.rawValue)], to: dest)
        send([0xB0, 0x02, UInt8(config.sidetoneMIDINote)], to: dest)
    }

    // MARK: - Device discovery

    /// All MIDI destinations except CoreMIDI network sessions.
    private func nonNetworkDestinations() -> [MIDIEndpointRef] {
        var result: [MIDIEndpointRef] = []
        let count = MIDIGetNumberOfDestinations()
        for i in 0 ..< count {
            let dest = MIDIGetDestination(i)
            let name = endpointStringProperty(dest, kMIDIPropertyDisplayName).lowercased()
            if name.contains("network"), name.contains("session") { continue }
            result.append(dest)
        }
        return result
    }

    private func findVailAdapterDestination() -> MIDIEndpointRef? {
        let count = MIDIGetNumberOfDestinations()
        for i in 0 ..< count {
            let dest = MIDIGetDestination(i)
            let name = endpointStringProperty(dest, kMIDIPropertyDisplayName).lowercased()
            let manufacturer = endpointStringProperty(dest, kMIDIPropertyManufacturer).lowercased()

            // Skip CoreMIDI network sessions.
            if name.contains("network"), name.contains("session") { continue }

            // The adapter may appear as "Vail" or as the raw board it's built on
            // (Adafruit QT Py M0). Match either. See CLAUDE.md §4.
            if name.contains("vail") || manufacturer.contains("vail")
                || name.contains("qt py") || manufacturer.contains("adafruit")
            {
                return dest
            }
        }
        return nil
    }

    private func endpointStringProperty(_ endpoint: MIDIEndpointRef, _ property: CFString) -> String {
        var value: Unmanaged<CFString>?
        let status = MIDIObjectGetStringProperty(endpoint, property, &value)
        guard status == noErr, let cf = value?.takeRetainedValue() else { return "" }
        return cf as String
    }

    // MARK: - Notifications

    private func handleNotification(_ messageID: MIDINotificationMessageID) {
        switch messageID {
        case .msgSetupChanged, .msgObjectAdded, .msgObjectRemoved:
            connectToAdapter()
        default:
            break
        }
    }

    // MARK: - Sending

    private func send(_ message: [UInt8], to dest: MIDIEndpointRef, at timeStamp: MIDITimeStamp = 0) {
        guard port != 0, dest != 0, message.count <= 256 else { return }

        var packet = MIDIPacket()
        packet.timeStamp = timeStamp
        packet.length = UInt16(message.count)
        withUnsafeMutableBytes(of: &packet.data) { raw in
            for (i, byte) in message.enumerated() {
                raw[i] = byte
            }
        }

        var list = MIDIPacketList(numPackets: 1, packet: packet)
        let status = MIDISend(port, dest, &list)
        if status != noErr {
            log.error("MIDISend failed: \(status)")
        }
    }

    // MARK: - Helpers

    private static func ditDurationMs(forWPM wpm: Int) -> Int {
        // PARIS standard: dit (ms) = 1200 / WPM.
        1200 / max(5, wpm)
    }

    private func machTime(forMillisFromNow ms: Int64) -> MIDITimeStamp {
        let now = mach_absolute_time()
        guard ms > 0 else { return now }
        let ns = UInt64(ms) * 1_000_000
        let machUnits = ns * UInt64(Self.timebase.denom) / UInt64(Self.timebase.numer)
        return now &+ machUnits
    }

    private func check(_ status: OSStatus, op: String) throws {
        guard status == noErr else {
            log.error("\(op) failed: \(status)")
            throw MIDIOutputError.osStatus(op, status)
        }
    }
}

public enum MIDIOutputError: Error {
    case osStatus(String, OSStatus)
}
