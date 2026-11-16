// KeyMerge.swift
// Many physical keys, one logical key (the Sending Analyzer, row-23 of the
// parity audit).
//
// The key is down while ANY source is held and up only when the last held
// source is released. This is the rule `SendingKeyer.handle` applies to its
// paddles and the on-screen key; it lives here as pure logic so that
// `fixtures/gap-analyzer-merge.json` pins it, and the Kotlin ports (Android and
// desktop) read the same file. No clock, no sidetone: the caller stamps
// whatever edge this reports.

import Foundation

public struct KeyMerge<Source: Hashable & Sendable>: Sendable {
    private var held = Set<Source>()

    public init() {}

    /// Whether the logical key is down now.
    public var isDown: Bool { !held.isEmpty }

    /// `source` went down or up. Returns the new state of the logical key when
    /// this changed it (true = down), otherwise nil.
    @discardableResult
    public mutating func update(_ source: Source, isDown: Bool) -> Bool? {
        let was = !held.isEmpty
        if isDown { held.insert(source) } else { held.remove(source) }
        let now = !held.isEmpty
        return now != was ? now : nil
    }

    /// Drop every held source. Returns false (an "up" edge) if the key was
    /// down, otherwise nil.
    @discardableResult
    public mutating func releaseAll() -> Bool? {
        let was = !held.isEmpty
        held.removeAll()
        return was ? false : nil
    }
}
