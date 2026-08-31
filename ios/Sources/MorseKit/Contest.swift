import Foundation

/// What a contest's multiplier counts — so the score and scoreboard can derive
/// it from the worked log without the engine knowing about contests.
public enum MultiplierKind: Sendable, Equatable {
    case none           // no multiplier; score is a straight QSO/point total
    case calls          // each distinct call sign worked (CWT, MST)
    case spc            // each distinct state / province / country (NS)
}

/// A practice emulation of a real on-air CW contest. Each maps onto the
/// pileup/QSO engine's exchange (`QSOContestMode`) but pins the authentic
/// on-air speed band, a contest-length clock, and a scoring rule — so a session
/// feels like the actual event rather than the generic QSO simulator.
public enum ContestType: String, Codable, CaseIterable, Identifiable, Sendable {
    case sst        // K1USN Slow Speed Test
    case mst        // ICWC Medium Speed Test
    case cwt        // CWops CWT (Mini-CWT)
    case nsSprint   // NCCC Sprint (NS)
    case fieldDay   // ARRL Field Day

    public var id: String { rawValue }

    /// The exchange this contest runs on the pileup engine.
    public var qsoMode: QSOContestMode {
        switch self {
        case .sst:      return .sst
        case .mst:      return .mst
        case .cwt:      return .cwt
        case .nsSprint: return .sprint
        case .fieldDay: return .fieldDay
        }
    }

    /// Short tag for tiles and the live scoreboard.
    public var shortName: String {
        switch self {
        case .sst:      return "SST"
        case .mst:      return "MST"
        case .cwt:      return "CWT"
        case .nsSprint: return "NS"
        case .fieldDay: return "FD"
        }
    }

    /// Full event name.
    public var name: String {
        switch self {
        case .sst:      return "K1USN SST"
        case .mst:      return "ICWC MST"
        case .cwt:      return "CWops CWT"
        case .nsSprint: return "NCCC Sprint"
        case .fieldDay: return "ARRL Field Day"
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
        case .nsSprint:
            return "NCCC Sprint (NS) — a fast half-hour Thursday-night practice sprint. Copy a serial number, the operator's name, and their state/province/country. Your score is QSOs times the distinct SPCs worked."
        case .fieldDay:
            return "ARRL Field Day — the big summer emergency-ops exercise. Copy each station's class and ARRL section (e.g. 2A OH). Every CW QSO is worth 2 points."
        }
    }

    /// Authentic on-air speed band, in WPM. SST is held deliberately slow, MST
    /// runs at a medium pace, CWT at a brisk contest pace, and NS faster still;
    /// Field Day spans a wide range of operators.
    public var minWPM: Double {
        switch self {
        case .sst:      return 15
        case .mst:      return 20
        case .cwt:      return 25
        case .nsSprint: return 28
        case .fieldDay: return 18
        }
    }
    public var maxWPM: Double {
        switch self {
        case .sst:      return 20
        case .mst:      return 25
        case .cwt:      return 32
        case .nsSprint: return 38
        case .fieldDay: return 32
        }
    }

    /// Points awarded per QSO — Field Day pays 2 for a CW contact; the rest pay 1.
    public var pointsPerQSO: Int {
        switch self {
        case .fieldDay: return 2
        default:        return 1
        }
    }

    /// What this contest's multiplier counts (if any).
    public var multiplierKind: MultiplierKind {
        switch self {
        case .sst, .fieldDay: return .none
        case .mst, .cwt:      return .calls
        case .nsSprint:       return .spc
        }
    }

    /// Whether a multiplier applies to the score.
    public var usesMultipliers: Bool { multiplierKind != .none }

    /// What a multiplier *is*, for the scoreboard label — nil when the contest
    /// has no multipliers.
    public var multiplierLabel: String? {
        switch multiplierKind {
        case .none:  return nil
        case .calls: return "Calls"
        case .spc:   return "SPC"
        }
    }

    /// Final score from a worked log: QSO points (`pointsPerQSO` each) times the
    /// multiplier count, where the contest has multipliers.
    public func score(qsoCount: Int, multipliers: Int) -> Int {
        let points = qsoCount * pointsPerQSO
        return usesMultipliers ? points * multipliers : points
    }

    /// Pull this contest's multiplier count out of a worked log — distinct call
    /// signs, distinct SPCs (the last exchange token), or none.
    public func multiplierCount(calls: [String], exchanges: [String]) -> Int {
        switch multiplierKind {
        case .none:
            return 0
        case .calls:
            return Set(calls).count
        case .spc:
            return Set(exchanges.compactMap { $0.split(separator: " ").last.map(String.init) }).count
        }
    }
}
