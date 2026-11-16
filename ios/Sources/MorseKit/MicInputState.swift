// MicInputState.swift
// What can happen to the Sending Analyzer's microphone, and how it moves the
// input's two flags (rows 22 and 24 of the parity audit). Pure: the capture
// class (`SendingMicInput`) keeps its published flags in step with `after`.
// Pinned by `fixtures/gap-analyzer-mic-state.json`, which the Kotlin ports read
// too.

import Foundation

public enum MicEvent: String, Sendable, CaseIterable {
    /// Permission held and the capture opened.
    case startGranted
    /// The system refused the microphone.
    case startDenied
    /// Permission held but the capture could not be opened.
    case startFailed
    /// A call or another app took the audio.
    case interruptionBegan
    /// The input device went away or the capture died.
    case routeLost
    case mediaServicesReset
    /// The interruption is over. The microphone does not resume by itself.
    case interruptionEnded
    /// The screen left, or the input was switched to the key.
    case stop
}

public struct MicInputState: Sendable, Equatable {
    public var isListening: Bool
    public var micDenied: Bool

    public init(isListening: Bool = false, micDenied: Bool = false) {
        self.isListening = isListening
        self.micDenied = micDenied
    }

    public func after(_ event: MicEvent) -> MicInputState {
        switch event {
        case .startGranted:
            return isListening ? self : MicInputState(isListening: true, micDenied: false)
        case .startDenied:
            return isListening ? self : MicInputState(isListening: isListening, micDenied: true)
        case .startFailed:
            return isListening ? self : MicInputState(isListening: false, micDenied: false)
        case .interruptionBegan, .routeLost, .mediaServicesReset:
            return isListening ? MicInputState(isListening: false, micDenied: micDenied) : self
        case .interruptionEnded:
            return self
        case .stop:
            return MicInputState(isListening: false, micDenied: micDenied)
        }
    }
}
