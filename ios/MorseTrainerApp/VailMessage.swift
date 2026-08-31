// VailMessage.swift
// The single JSON envelope used in both directions on the Vail WebSocket.
// Different "message types" are distinguished by which fields are populated.
// See CLAUDE.md §1 for the protocol details.

import Foundation

/// A single envelope. All Vail traffic — TX, RX, chat, keepalive — uses this
/// same struct with different fields set.
///
/// Timestamps are **always in server clock** on the wire. The client
/// translates to/from local clock using `VailClient.clockOffsetMs`.
public struct VailMessage: Codable, Equatable, Sendable {
    /// Milliseconds since Unix epoch, in server clock.
    public var timestamp: Int64

    /// Alternating tone/silence durations in ms.
    /// - Outbound: a single-element `[tone_ms]` for a transmission, or `[]`
    ///   for keepalive/chat.
    /// - Inbound: multi-element for forwarded transmissions from other users,
    ///   or `[]` for server hello / roster updates.
    public var duration: [UInt16] = []

    /// Sender's callsign. Set on initial connect, keepalives, chat, and
    /// per-transmission (server fills it in on relayed messages).
    public var callsign: String?

    /// Sender's TX tone as MIDI note number (default 72 = C5).
    public var txTone: Int?

    /// Mark this room as private (sent on initial connect only).
    public var `private`: Bool?

    /// Mark this room as decoder-enabled (sent on initial connect only).
    /// Echoed back by server for all clients to honor.
    public var decoder: Bool?

    /// Chat message text. Presence of this field signals "this is a chat
    /// message, not a tone transmission".
    public var text: String?

    // MARK: - Server-added fields (received only)

    /// Total connected clients on this channel.
    public var clients: Int?

    /// Legacy roster — just callsigns.
    public var users: [String]?

    /// Modern roster — callsign + TX tone per user.
    public var usersInfo: [UserInfo]?

    /// Active public rooms with user counts.
    public var rooms: [Room]?

    public init(
        timestamp: Int64,
        duration: [UInt16] = [],
        callsign: String? = nil,
        txTone: Int? = nil,
        private: Bool? = nil,
        decoder: Bool? = nil,
        text: String? = nil
    ) {
        self.timestamp = timestamp
        self.duration = duration
        self.callsign = callsign
        self.txTone = txTone
        self.private = `private`
        self.decoder = decoder
        self.text = text
    }

    /// Case-tolerant decoding. The envelope historically uses capitalized
    /// keys (`Callsign`, `TxTone`, ...) but the server has been observed to
    /// emit lowercase forms on at least some message paths (the `UsersInfo`
    /// item shape uses lowercase, and relayed tone messages have started
    /// arriving without an uppercase `Callsign`). Accept both so callsigns
    /// survive round-tripping.
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: AnyKey.self)
        timestamp = try Self.decode(Int64.self, from: c, "Timestamp", "timestamp") ?? 0
        duration = try Self.decode([UInt16].self, from: c, "Duration", "duration") ?? []
        callsign = try Self.decode(String.self, from: c, "Callsign", "callsign")
        txTone = try Self.decode(Int.self, from: c, "TxTone", "txTone")
        self.private = try Self.decode(Bool.self, from: c, "Private", "private")
        self.decoder = try Self.decode(Bool.self, from: c, "Decoder", "decoder")
        text = try Self.decode(String.self, from: c, "Text", "text")
        clients = try Self.decode(Int.self, from: c, "Clients", "clients")
        users = try Self.decode([String].self, from: c, "Users", "users")
        usersInfo = try Self.decode([UserInfo].self, from: c, "UsersInfo", "usersInfo")
        rooms = try Self.decode([Room].self, from: c, "Rooms", "rooms")
    }

    private static func decode<T: Decodable>(
        _ type: T.Type,
        from container: KeyedDecodingContainer<AnyKey>,
        _ keys: String...
    ) throws -> T? {
        for k in keys {
            guard let key = AnyKey(stringValue: k) else { continue }
            if container.contains(key) {
                return try container.decodeIfPresent(type, forKey: key)
            }
        }
        return nil
    }

    /// Dynamic CodingKey for case-tolerant lookups during decoding.
    private struct AnyKey: CodingKey {
        var stringValue: String
        var intValue: Int? {
            nil
        }

        init?(stringValue: String) {
            self.stringValue = stringValue
        }

        init?(intValue _: Int) {
            nil
        }
    }

    public struct UserInfo: Codable, Equatable, Sendable, Hashable {
        public let callsign: String
        public let txTone: Int?

        public init(callsign: String, txTone: Int?) {
            self.callsign = callsign
            self.txTone = txTone
        }

        /// Server sends these as lowercase inside UsersInfo arrays even though
        /// the top-level envelope uses uppercase keys. Confirmed against the
        /// wire on 2026-05-21.
        enum CodingKeys: String, CodingKey {
            case callsign
            case txTone
        }
    }

    public struct Room: Codable, Equatable, Sendable, Hashable {
        public let name: String
        public let users: Int

        public init(name: String, users: Int) {
            self.name = name
            self.users = users
        }

        /// See UserInfo: server uses lowercase keys inside Rooms arrays.
        enum CodingKeys: String, CodingKey {
            case name
            case users
        }
    }

    enum CodingKeys: String, CodingKey {
        case timestamp = "Timestamp"
        case duration = "Duration"
        case callsign = "Callsign"
        case txTone = "TxTone"
        case `private` = "Private"
        case decoder = "Decoder"
        case text = "Text"
        case clients = "Clients"
        case users = "Users"
        case usersInfo = "UsersInfo"
        case rooms = "Rooms"
    }
}

// MARK: - Equality for echo suppression

public extension VailMessage {
    /// Match against a previously-sent message for echo detection.
    /// Server echoes our own transmissions back; we identify them by
    /// (timestamp, duration) equality.
    func isEchoOf(_ sent: VailMessage) -> Bool {
        timestamp == sent.timestamp && duration == sent.duration
    }
}
