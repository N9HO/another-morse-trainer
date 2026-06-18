import Foundation

/// A practice emulation of a real on-air CW contest. Each maps onto the
/// pileup/QSO engine's exchange (`QSOContestMode`) but pins the authentic
/// on-air speed band, a contest-length clock, and a scoring rule — so a session
/// feels like the actual event rather than the generic QSO simulator.
public enum ContestType: String, Codable, CaseIterable, Identifiable, Sendable {
    case sst   // K1USN Slow Speed Test
    case mst   // ICWC Medium Speed Test
    case cwt   // CWops CWT (Mini-CWT)

    public var id: String { rawValue }

    /// The exchange this contest runs on the pileup engine.
    public var qsoMode: QSOContestMode {
        switch self {
        case .sst: return .sst
        case .mst: return .mst
        case .cwt: return .cwt
        }
    }

    /// Short tag for tiles and the live scoreboard.
    public var shortName: String {
        switch self {
        case .sst: return "SST"
        case .mst: return "MST"
        case .cwt: return "CWT"
        }
    }

    /// Full event name.
    public var name: String {
        switch self {
        case .sst: return "K1USN SST"
        case .mst: return "ICWC MST"
        case .cwt: return "CWops CWT"
        }
    }

    public var blurb: String {
        switch self {
        case .sst:
            return "K1USN Slow Speed Test — a friendly, deliberately slow sprint. Work as many stations as you can, copying each operator's name and state. Your score is simply the number of QSOs."
        case .mst:
            return "ICWC Medium Speed Test — a step up from the SST. Copy each station's name and serial number at a medium pace. Your score is QSOs times the number of distinct call signs worked."
        case .cwt:
            return "CWops mini-CWT — a fast hour. Copy each station's name and CWops member number (non-members send their state instead). Your score is QSOs times the number of distinct call signs worked."
        }
    }

    /// Authentic on-air speed band, in WPM. SST is held deliberately slow, MST
    /// runs at a medium pace, and CWT at a brisk contest pace.
    public var minWPM: Double {
        switch self {
        case .sst: return 15
        case .mst: return 20
        case .cwt: return 25
        }
    }
    public var maxWPM: Double {
        switch self {
        case .sst: return 20
        case .mst: return 25
        case .cwt: return 32
        }
    }

    /// The real event's length — each running of these contests is one hour.
    public var fullLengthSeconds: TimeInterval { 3600 }

    /// CWT and MST score QSOs × multipliers; SST is a straight QSO count.
    public var usesMultipliers: Bool {
        switch self {
        case .sst: return false
        case .mst, .cwt: return true
        }
    }

    /// What a multiplier *is*, for the scoreboard label — nil when the contest
    /// has no multipliers.
    public var multiplierLabel: String? {
        switch self {
        case .sst: return nil
        case .mst, .cwt: return "Calls"
        }
    }

    /// Final score from a worked log: one point per QSO, times the multiplier
    /// count (the distinct call signs worked, for CWT). SST has no multiplier,
    /// so its score is just the QSO total.
    public func score(qsoCount: Int, multipliers: Int) -> Int {
        usesMultipliers ? qsoCount * multipliers : qsoCount
    }
}
