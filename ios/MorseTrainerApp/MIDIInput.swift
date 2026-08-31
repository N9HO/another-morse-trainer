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
        public let isDown: Bool
        /// mach_absolute_time at the moment the key event occurred.
        public let machTimestamp: UInt64
        /// Same event as wall-clock ms since Unix epoch.
        public let timestampMs: Int64
    }

    public var onEvent: (@Sendable (Event) -> Void)?
    /// Fired (from a CoreMIDI thread) whenever the set of connected sources
    /// changes — hot-plug, unplug, or the initial scan. Carries the current
    /// device names so the UI can show what hardware key is attached.
    public var onSourcesChanged: (@Sendable ([String]) -> Void)?

    private var client: MIDIClientRef = 0
    private var port: MIDIPortRef = 0
    /// Endpoints already connected to the input port, so hot-plug re-scans
    /// don't connect (and double-deliver) the same source twice.
    private var connectedRefs = Set<MIDIEndpointRef>()
    private var sourceNames: [String] = []
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
        connectedRefs = present
        let changed = names != sourceNames
        sourceNames = names
        stateLock.unlock()
        if changed { onSourcesChanged?(names) }
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

        let timestampMs = Self.machTimeToWallClockMs(packet.timeStamp)
        for message in messages {
            onEvent?(Event(
                key: message.paddle,
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
