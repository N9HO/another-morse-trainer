// MIDIInput.swift
// CoreMIDI client for the Vail Adapter and BLE MIDI keyers.
//
// The Vail Adapter sends MIDI on channel 1 with these mappings:
//   Note 0 = straight key, Note 1 = dit paddle, Note 2 = dah paddle
//   Velocity > 0 = pressed, Velocity = 0 (or Note Off status) = released
//
// MIDIPacket.timeStamp is in mach_absolute_time units — use this for accurate
// key event timing (sub-millisecond), not the host's Date().
// See CLAUDE.md §4.

import CoreMIDI
import Foundation
import OSLog

private let log = Logger(subsystem: "com.justinrogers.MorseTrainer", category: "midi")

public final class MIDIInput {
    public enum Key: Sendable {
        case straight
        case dit
        case dah
    }

    public struct Event: Sendable {
        public let key: Key
        public let isDown: Bool
        /// mach_absolute_time at the moment the key event occurred.
        public let machTimestamp: UInt64
        /// Same event as wall-clock ms since Unix epoch.
        public let timestampMs: Int64
    }

    public var onEvent: (@Sendable (Event) -> Void)?

    private var client: MIDIClientRef = 0
    private var port: MIDIPortRef = 0

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
        let count = MIDIGetNumberOfSources()
        for i in 0 ..< count {
            let src = MIDIGetSource(i)
            let result = MIDIPortConnectSource(port, src, nil)
            if result != noErr {
                log.warning("Failed to connect MIDI source \(i): \(result)")
            } else if let name = endpointName(src) {
                log.info("Connected MIDI source: \(name)")
            }
        }
    }

    private func handleNotification(_ notification: UnsafePointer<MIDINotification>) {
        let id = notification.pointee.messageID
        // A device appearing fires .msgObjectAdded, but a USB MIDI device being
        // plugged in can surface only as .msgSetupChanged on some iOS versions.
        // Re-scan on either so a hot-plugged Vail Adapter always connects.
        if id == .msgObjectAdded || id == .msgSetupChanged {
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
        // packet.data is a 256-byte tuple. We need the first `length` bytes.
        let length = Int(packet.length)
        guard length >= 3 else { return }

        var bytes = [UInt8](repeating: 0, count: length)
        withUnsafeBytes(of: packet.data) { rawPtr in
            for i in 0 ..< length {
                bytes[i] = rawPtr[i]
            }
        }

        let status = bytes[0] & 0xF0
        let note = bytes[1]
        let velocity = bytes[2]
        log.debug("MIDI in: status=0x\(String(status, radix: 16)) note=\(note) vel=\(velocity)")

        let isDown: Bool
        switch status {
        case 0x90 where velocity > 0:
            isDown = true
        case 0x80:
            isDown = false
        case 0x90:
            // Note On with velocity 0 = Note Off (standard MIDI running status)
            isDown = false
        default:
            return
        }

        // The adapter sends different note numbers per firmware/keyer mode:
        //   straight key = 0
        //   dit paddle   = 1, 20, or (passthrough) 61 (C#4)
        //   dah paddle   = 2, 21, or (passthrough) 62 (D4)
        // Match the Vail web repeater's full set so paddle input works
        // regardless of mode. Unknown notes are ignored (we connect to all
        // sources, so an unrelated synth must not register as keying).
        let key: Key
        switch note {
        case 0: key = .straight
        case 1, 20, 61: key = .dit
        case 2, 21, 62: key = .dah
        default:
            log.debug("Ignoring unmapped MIDI note \(note)")
            return
        }

        let timestampMs = Self.machTimeToWallClockMs(packet.timeStamp)
        let event = Event(
            key: key,
            isDown: isDown,
            machTimestamp: packet.timeStamp,
            timestampMs: timestampMs
        )
        onEvent?(event)
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
