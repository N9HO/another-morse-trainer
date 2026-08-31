// SignalEvent.swift
// Records of sent/received activity (tone bursts and chat) for the timeline
// visualizer. Times are in local clock ms (the same clock VailSession uses for
// scheduling RX audio).

import Foundation

public struct SignalEvent: Identifiable, Sendable, Equatable {
    public enum Kind: Sendable, Equatable {
        /// A keyed tone burst. `durationMs` is how long the key was down.
        /// `midiNote` is the sender's TxTone (nil if unknown).
        case tone(durationMs: Int, midiNote: Int?)
        /// A chat message. Drawn as a point marker on the lane.
        case chat(text: String)
    }

    public enum Origin: Sendable, Equatable {
        case sent
        case received
    }

    public let id = UUID()
    public let callsign: String
    public let startLocalMs: Int64
    public let kind: Kind
    public let origin: Origin

    public init(callsign: String, startLocalMs: Int64, kind: Kind, origin: Origin) {
        self.callsign = callsign
        self.startLocalMs = startLocalMs
        self.kind = kind
        self.origin = origin
    }

    /// End time (ms) for layout. Chat events are zero-duration markers.
    public var endLocalMs: Int64 {
        switch kind {
        case let .tone(d, _): return startLocalMs + Int64(d)
        case .chat: return startLocalMs
        }
    }
}
