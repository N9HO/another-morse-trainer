// MIDIKeyMessage.swift
// Parsing of the MIDI byte stream a Morse key sends, kept free of CoreMIDI so
// the wire format can be checked by the MorseKitCheck harness on any machine.
//
// A single CoreMIDI packet is a *stream*, not one message: a BLE-MIDI key
// routinely batches several events into one packet (the BLE-MIDI spec exists
// precisely to amortize the radio's ~7.5 ms connection interval), and within a
// packet it may drop repeated status bytes and rely on running status. Reading
// only the first three bytes therefore loses events — and the one most often
// lost is a key-up that arrived in the same radio burst as its key-down, which
// leaves the key stuck down.

import Foundation

/// Which contact of a Morse key an event came from.
public enum MIDIKeyPaddle: Sendable, Hashable, CaseIterable {
    case straight
    case dit
    case dah
}

/// One key-down or key-up decoded from a MIDI stream.
public struct MIDIKeyMessage: Sendable, Hashable {
    public let paddle: MIDIKeyPaddle
    public let isDown: Bool

    public init(paddle: MIDIKeyPaddle, isDown: Bool) {
        self.paddle = paddle
        self.isDown = isDown
    }
}

public enum MIDIKeyParser {
    /// Note numbers the supported keys send, across firmwares and keyer modes:
    ///   straight key = 0 (Vail Adapter: the straight key, and every element
    ///                  its keyer modes send, already timed), or 30
    ///                  (Morserino-32 Pocket, USB Output = MIDI, keyer in
    ///                  Straight Key mode)
    ///   dit paddle   = 1 (Vail Adapter in Passthrough), 20 (N6ARA TinyMIDI,
    ///                  the Pocket), or 61 (C#4)
    ///   dah paddle   = 2 (Vail Adapter in Passthrough), 21 (N6ARA TinyMIDI,
    ///                  the Pocket), or 62 (D4)
    ///
    /// Notes 1, 2, 20 and 21 are raw paddle contacts: the device does no
    /// timing and expects the host to. 61 and 62 are older mappings with no
    /// known device source, kept as existing behaviour. Every other note is
    /// unmapped: we listen to *all* MIDI sources, so an unrelated synth must
    /// never register as keying. Pinned by fixtures/midi-key-notes.json.
    public static func paddle(forNote note: UInt8) -> MIDIKeyPaddle? {
        switch note {
        case 0, 30: return .straight
        case 1, 20, 61: return .dit
        case 2, 21, 62: return .dah
        default: return nil
        }
    }

    /// Every key event in one packet's bytes, in order.
    ///
    /// Handles running status (a status byte omitted because it repeats the
    /// previous one) and System Real-Time bytes, which the spec allows to
    /// appear *between* the bytes of another message without disturbing it.
    public static func messages(in bytes: [UInt8]) -> [MIDIKeyMessage] {
        var messages: [MIDIKeyMessage] = []
        var runningStatus: UInt8 = 0
        var index = 0

        while index < bytes.count {
            let byte = bytes[index]

            // System Real-Time (0xF8...0xFF): one byte, may interleave anywhere.
            if byte >= 0xF8 {
                index += 1
                continue
            }

            let status: UInt8
            if byte & 0x80 != 0 {
                // System Common (0xF0...0xF7) cancels running status, and its
                // payload length varies — skip to the next status byte.
                if byte >= 0xF0 {
                    runningStatus = 0
                    index += 1
                    while index < bytes.count, bytes[index] & 0x80 == 0 { index += 1 }
                    continue
                }
                status = byte
                runningStatus = byte
                index += 1
            } else {
                // A data byte with no status of its own continues the last
                // channel message. Without one there is nothing to continue.
                guard runningStatus != 0 else {
                    index += 1
                    continue
                }
                status = runningStatus
            }

            let expected = dataByteCount(forStatus: status)
            var data: [UInt8] = []
            while data.count < expected, index < bytes.count {
                let next = bytes[index]
                if next >= 0xF8 { index += 1; continue }   // interleaved real-time
                if next & 0x80 != 0 { break }              // truncated message
                data.append(next)
                index += 1
            }
            // A message cut short by the end of the packet ends the walk.
            guard data.count == expected else { break }

            let kind = status & 0xF0
            guard kind == 0x80 || kind == 0x90 else { continue }
            guard let paddle = paddle(forNote: data[0]) else { continue }
            // Note On with velocity 0 is the conventional Note Off.
            messages.append(MIDIKeyMessage(paddle: paddle, isDown: kind == 0x90 && data[1] > 0))
        }

        return messages
    }

    /// Data bytes carried by a channel message, by status nibble.
    private static func dataByteCount(forStatus status: UInt8) -> Int {
        switch status & 0xF0 {
        case 0xC0, 0xD0: return 1   // Program Change, Channel Pressure
        default: return 2           // Note Off/On, Aftertouch, CC, Pitch Bend
        }
    }
}
