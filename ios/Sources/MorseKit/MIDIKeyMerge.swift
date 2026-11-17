// MIDIKeyMerge.swift
// A MIDI key's notes → the one logical key (#380), between the note parser
// and the app's paddle keyer. Pure and clock-free: the caller supplies every
// time, runs the keyer (`PaddleKeyerDriver` in the app) on its own clock,
// and feeds the keyer's tone back in. Pinned by `fixtures/midi-key-merge.json`,
// which the Kotlin ports (`MidiKeyMerge`) read too.

import Foundation

public struct MIDIKeyMerge: Sendable {
    /// What the caller does for one input, in order.
    public enum Action: Sendable, Equatable {
        /// The logical key went down or up, at `at`.
        case key(isDown: Bool, at: Int64)
        /// Press or release one paddle of the app's keyer.
        case paddle(PaddleKeyer.Element, isDown: Bool)
        /// Stop the app's keyer: let go of both paddles and cut its tone now.
        case stopKeyer
    }

    /// Held notes down now: they key the logical key as they come.
    private var held = Set<UInt8>()
    /// Raw paddle notes down now, by the paddle each pressed when it went
    /// down, so its release goes the same way even if the adapter's mode
    /// changed meanwhile, and two notes on one paddle (1 and 20) hold it
    /// until both are up.
    private var keyed: [UInt8: PaddleKeyer.Element] = [:]
    private var keyerDown = false
    private var reported = false

    public init() {}

    /// Whether a note holds a paddle of the keyer now. The app reads its
    /// settings into the keyer only while none does.
    public var holdsAPaddle: Bool { !keyed.isEmpty }

    /// One note went down or up at `at`. A press goes where
    /// `MIDIKeyParser.route` sends it for the adapter's Keyer mode
    /// (`rawPaddles`: `VailKeyerMode.sendsRawPaddles`); a release goes where
    /// its press went.
    public mutating func note(_ note: UInt8, isDown: Bool, rawPaddles: Bool, at: Int64) -> [Action] {
        if !isDown {
            if let element = keyed.removeValue(forKey: note) {
                // Another note may still hold this paddle.
                return keyed.values.contains(element) ? [] : [.paddle(element, isDown: false)]
            }
            guard held.remove(note) != nil else { return [] }
            return report(at: at)
        }
        guard held.contains(note) == false, keyed[note] == nil else { return [] }
        switch MIDIKeyParser.route(forNote: note, adapterSendsRawPaddles: rawPaddles) {
        case nil:
            return []
        case .held?:
            held.insert(note)
            return report(at: at)
        case .keyerDit?:
            return press(note, .dit)
        case .keyerDah?:
            return press(note, .dah)
        }
    }

    /// The keyer's tone went on or off at `at`, the time it scheduled.
    public mutating func keyerTone(isDown: Bool, at: Int64) -> [Action] {
        keyerDown = isDown
        return report(at: at)
    }

    /// Let go of everything at `at`: the screen is going away, or the key was
    /// unplugged. The keyer is always stopped first (its element is cut, not
    /// finished): with no paddle held and its tone off it may still owe a
    /// remembered dit or dah, or iambic B's extra element, which the merge
    /// can't see, and stopping an idle keyer emits nothing. Then the key goes
    /// up now. The stopped keyer's own tone-off then changes nothing.
    public mutating func releaseAll(at: Int64) -> [Action] {
        let actions: [Action] = [.stopKeyer]
        held.removeAll()
        keyed.removeAll()
        keyerDown = false
        return actions + report(at: at)
    }

    private mutating func press(_ note: UInt8, _ element: PaddleKeyer.Element) -> [Action] {
        let alreadyHeld = keyed.values.contains(element)
        keyed[note] = element
        return alreadyHeld ? [] : [.paddle(element, isDown: true)]
    }

    /// Down while a held note is down or the keyer's tone is on; an edge only
    /// when that changes.
    private mutating func report(at: Int64) -> [Action] {
        let now = !held.isEmpty || keyerDown
        guard now != reported else { return [] }
        reported = now
        return [.key(isDown: now, at: at)]
    }
}
