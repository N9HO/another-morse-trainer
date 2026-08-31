// LogStore.swift
// In-app log buffer. Mirrors entries to OSLog (Console.app) and to a published
// ring buffer that LogView renders. Use `appLog(.notice, "protocol", "...")`
// at lifecycle events you want to inspect from inside the app — Vail socket
// open/close/reconnect, MIDI adapter detect/lose, etc.

import Foundation
import OSLog
import SwiftUI

public struct LogEntry: Identifiable, Sendable {
    public enum Level: String, Sendable, CaseIterable {
        case debug, info, notice, warning, error
    }

    public let id = UUID()
    public let timestamp: Date
    public let level: Level
    public let category: String
    public let message: String
}

@MainActor
public final class LogStore: ObservableObject {
    public static let shared = LogStore()
    /// Bounded ring buffer. Anything older falls off — debugging flap symptoms
    /// only needs the most recent few hundred events.
    private static let capacity = 500

    @Published public private(set) var entries: [LogEntry] = []

    private init() {}

    public func append(_ entry: LogEntry) {
        entries.append(entry)
        if entries.count > Self.capacity {
            entries.removeFirst(entries.count - Self.capacity)
        }
    }

    public func clear() {
        entries.removeAll()
    }
}

/// Top-level log helper. Safe to call from any actor / thread — the OSLog
/// emission is synchronous; the in-app buffer append hops to the main actor.
public func appLog(
    _ level: LogEntry.Level,
    _ category: String,
    _ message: String
) {
    let logger = Logger(subsystem: "com.justinrogers.MorseTrainer", category: category)
    switch level {
    case .debug: logger.debug("\(message, privacy: .public)")
    case .info: logger.info("\(message, privacy: .public)")
    case .notice: logger.notice("\(message, privacy: .public)")
    case .warning: logger.warning("\(message, privacy: .public)")
    case .error: logger.error("\(message, privacy: .public)")
    }

    let entry = LogEntry(
        timestamp: Date(),
        level: level,
        category: category,
        message: message
    )
    Task { @MainActor in
        LogStore.shared.append(entry)
    }
}
