import Foundation

/// The Vail Adapter's keyer modes, sent with a Program Change (`C0 vv`).
/// Values match the Vail firmware; the order is the order every Settings
/// picker lists them in, and all three apps agree (#348).
/// Pinned by `fixtures/keyer-modes.json`. Used on the app side through
/// `MIDIOutput.KeyerMode`.
public enum VailKeyerMode: Int, CaseIterable, Sendable {
    case passthrough = 0
    case straightKey = 1
    case bug = 2
    case electricBug = 3
    case singleDot = 4
    case ultimatic = 5
    case plainIambic = 6
    case iambicA = 7
    case iambicB = 8
    case keyahead = 9

    public var displayName: String {
        switch self {
        case .passthrough: "Passthrough"
        case .straightKey: "Straight Key"
        case .bug: "Bug"
        case .electricBug: "Electric Bug"
        case .singleDot: "Single Dot"
        case .ultimatic: "Ultimatic"
        case .plainIambic: "Plain Iambic"
        case .iambicA: "Iambic A"
        case .iambicB: "Iambic B"
        case .keyahead: "Keyahead"
        }
    }
}
