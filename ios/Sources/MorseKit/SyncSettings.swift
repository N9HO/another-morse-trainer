// SyncSettings.swift
// Settings sync's pure half: the training preferences all three apps share,
// one `PUT /v1/sync/state` key each (`setting.<name>`), with one wire shape,
// a default and a normalising rule per key. The app maps `AppSettings` to and
// from these values (AppModel+SettingsSync.swift); the engine normalises
// everything it sends and receives here, so a value from another platform is
// clamped into this app's range or ignored, never trusted.
//
// The contract is the accounts Worker's README (section 9, "Settings keys").
// `fixtures/sync-wire.json` `settings` pins every key, shape, default and
// normalising rule; the Android and desktop ports read the same file.

import Foundation

/// How one setting looks on the wire, and how a value is normalised.
public enum SyncSettingKind: Sendable, Equatable {
    case bool
    /// Any JSON number, rounded to a whole number, then clamped.
    case int(min: Int, max: Int)
    /// Clamped, then rounded to one decimal place (the fixture's step 0.1).
    case tenths(min: Double, max: Double)
    /// One of these strings.
    case enumeration([String])
    /// A number, rounded, that must be one of these.
    case intChoice([Int])
    /// Strings from `values`: unknown and repeated ones dropped, in the order
    /// of `values`; fewer than `minCount` left is unusable.
    case members([String], minCount: Int)
    case text(maxLength: Int, transform: SyncTextTransform)
    /// Trimmed, uppercased, empty dropped, cut to `maxLength`, deduplicated,
    /// the first `maxCount` kept.
    case words(maxCount: Int, maxLength: Int)
    /// `{ min, max }`, each clamped to its bounds (whole numbers or tenths),
    /// then a max below min raised to min.
    case range(min: ClosedRange<Double>, max: ClosedRange<Double>, whole: Bool)
    /// `{ characterWpm 15...60, farnsworth, effectiveWpm 8...60 }`, the
    /// effective speed then lowered to the character speed.
    case speed
}

public enum SyncTextTransform: String, Sendable, Equatable {
    /// Trim, uppercase, keep A-Z 0-9 and /, cut to the length.
    case callsign
    /// Uppercase, keep A-Z, cut to the length.
    case state
    /// Trim whitespace, cut to the length (UTF-16 units, as the server counts).
    case trim
    /// `^[A-Za-z0-9_-]{0,64}$` or unusable.
    case id
}

public struct SyncSettingSpec: Sendable {
    public let key: SyncStateKey
    public let kind: SyncSettingKind
    public let defaultValue: JSONValue
}

public enum SyncSettings {
    public static let keyPrefix = "setting."

    private static func spec(_ name: String, _ kind: SyncSettingKind, _ value: JSONValue) -> SyncSettingSpec {
        SyncSettingSpec(key: SyncStateKey(uncheckedRawValue: keyPrefix + name), kind: kind, defaultValue: value)
    }

    private static let formats = CallsignFormat.allCases.map(\.rawValue)
    private static let formatDefaults: JSONValue = .array(CallsignFormat.commonDefaults.map { .string($0.rawValue) })

    /// Every settings key, in the fixture's order.
    public static let specs: [SyncSettingSpec] = [
        spec("speed", .speed, .object(["characterWpm": .int(33), "farnsworth": .bool(false), "effectiveWpm": .int(18)])),
        spec("tonePitch", .int(min: 300, max: 1000), .int(600)),
        spec("bandNoise", .enumeration(["off", "whisper", "low", "medium", "high"]), .string("off")),
        spec("practiceDuration", .enumeration(["oneMin", "fiveMin", "tenMin", "fifteenMin", "thirtyMin", "untilStop"]), .string("fiveMin")),
        spec("recognitionTarget", .tenths(min: 0.5, max: 3.0), .double(1.0)),
        spec("answerChoices", .int(min: 4, max: 6), .int(4)),
        spec("answerEntry", .enumeration(AnswerEntryMode.allCases.map(\.rawValue)), .string(AnswerEntryMode.choices.rawValue)),
        spec("reveal", .enumeration(["never", "onWrong", "always"]), .string("onWrong")),
        spec("showCorrectness", .bool, .bool(true)),
        spec("allowReplay", .bool, .bool(false)),
        spec("slashedZero", .bool, .bool(true)),
        spec("introduceNewCharacters", .bool, .bool(true)),
        spec("punctuation", .members(MorseCode.pickablePunctuation.map(String.init), minCount: 0), .array([])),
        spec("journeyDrainOnMiss", .bool, .bool(true)),
        spec("wordPool", .enumeration(["top100", "top300", "top500", "top1000", "cw77"]), .string("top100")),
        spec("useCustomWords", .bool, .bool(false)),
        spec("customWords", .words(maxCount: 300, maxLength: 24), .array([])),
        spec("cw77IncludeMe", .bool, .bool(false)),
        spec("cw77Style", .enumeration(CW77Style.allCases.map(\.rawValue)), .string(CW77Style.default.rawValue)),
        spec("listenContent", .enumeration(["characters", "qsoTop20", "qsoTop100", "cw77", "words", "abbreviations"]), .string("characters")),
        spec("listenGap", .enumeration(["standard", "rapidFire", "warp", "icr"]), .string("standard")),
        spec("listenReadback", .enumeration(["spelled", "meaningOnly"]), .string("spelled")),
        spec("headCopyRepeats", .int(min: 0, max: 3), .int(2)),
        spec("headCopyRevealSeconds", .int(min: 0, max: 10), .int(5)),
        spec("qrqSpeed", .intChoice([35, 40, 50, 60]), .int(35)),
        spec("examSpeed", .enumeration(ExamSpeed.allCases.map(\.rawValue)), .string(ExamSpeed.general13.rawValue)),
        spec("examUseBundled", .bool, .bool(false)),
        spec("storyContent", .enumeration(["fables", "serials", "news"]), .string("fables")),
        spec("storySerialId", .text(maxLength: 64, transform: .id), .string("")),
        spec("newsSource", .enumeration(["hamRadio", "world", "topStories", "technology"]), .string("hamRadio")),
        spec("newsFullStory", .bool, .bool(true)),
        spec("contestType", .enumeration(ContestType.allCases.map(\.rawValue)), .string(ContestType.sst.rawValue)),
        spec("contestLength", .enumeration(["tenMin", "thirtyMin", "fullHour", "untilStop"]), .string("tenMin")),
        spec("rapidFireContent", .enumeration(RapidFireContent.allCases.map(\.rawValue)), .string(RapidFireContent.callsigns.rawValue)),
        spec("rapidFireResponse", .enumeration(["type", "headCopy", "key", "review"]), .string("type")),
        spec("rapidFirePace", .enumeration(["relaxed", "steady", "brisk", "blazing"]), .string("steady")),
        spec("rapidFireWordLength", .range(min: 2...12, max: 2...12, whole: true), .object(["min": .int(3), "max": .int(6)])),
        spec("rapidFireNumberCount", .int(min: 1, max: 10), .int(5)),
        spec("rapidFireUsOnly", .bool, .bool(true)),
        spec("rapidFireFormats", .members(formats, minCount: 1), formatDefaults),
        spec("rapidFireStatesSections", .bool, .bool(false)),
        spec("rapidFireSerialCut", .bool, .bool(false)),
        spec("myCall", .text(maxLength: 12, transform: .callsign), .string("W1AW")),
        spec("myName", .text(maxLength: 32, transform: .trim), .string("")),
        spec("myState", .text(maxLength: 3, transform: .state), .string("")),
        spec("pileupMode", .enumeration(QSOContestMode.allCases.map(\.rawValue)), .string(QSOContestMode.pota.rawValue)),
        spec("pileupMaxStations", .int(min: 1, max: 8), .int(4)),
        spec("pileupCallerSpeed", .range(min: 12...60, max: 12...60, whole: true), .object(["min": .int(18), "max": .int(28)])),
        spec("pileupCallerFarnsworth", .bool, .bool(false)),
        spec("pileupToneSpread", .int(min: 0, max: 500), .int(250)),
        spec("pileupDelay", .range(min: 0...3, max: 0...4, whole: false), .object(["min": .double(0.2), "max": .double(1.5)])),
        spec("pileupQsb", .bool, .bool(false)),
        spec("pileupQrn", .enumeration(["off", "normal", "moderate", "heavy"]), .string("off")),
        spec("pileupCutNumbers", .bool, .bool(false)),
        spec("pileupCutDigits", .members(CutNumbers.cuttableDigits.map(String.init), minCount: 0),
             .array(CutNumbers.cuttableDigits.filter(CutNumbers.commonDefaults.contains).map { .string(String($0)) })),
        spec("pileupRstRequired", .bool, .bool(false)),
        spec("pileupBust", .enumeration(BustBehavior.allCases.map(\.rawValue)), .string(BustBehavior.forgiving.rawValue)),
        spec("pileupGiveUp", .bool, .bool(false)),
        spec("pileupFormats", .members(formats, minCount: 1), formatDefaults),
        spec("pileupUsOnly", .bool, .bool(true)),
        spec("pileupKeepPartialCall", .bool, .bool(false)),
        spec("pileupMissedCallerFeedback", .enumeration(MissedCallerFeedback.allCases.map(\.rawValue)),
             .string(MissedCallerFeedback.endOfRun.rawValue)),
        spec("pileupKeyMySide", .bool, .bool(true)),
        spec("pileupAutoRecall", .bool, .bool(true)),
    ]

    public static let keys: [SyncStateKey] = specs.map(\.key)
    private static let byKey: [String: SyncSettingSpec] =
        Dictionary(specs.map { ($0.key.rawValue, $0) }, uniquingKeysWith: { a, _ in a })

    public static func spec(for key: String) -> SyncSettingSpec? { byKey[key] }

    public static func defaultValue(_ key: String) -> JSONValue? { byKey[key]?.defaultValue }

    /// `value` in its normalised wire form, or nil when it is unusable or the
    /// key is not a setting this app knows (fixture `settings.normalize`).
    public static func normalize(_ key: String, _ value: JSONValue) -> JSONValue? {
        guard let spec = byKey[key] else { return nil }
        return normalize(spec.kind, value)
    }

    /// Whether `value`, normalised, is the key's default (fixture `settings.isDefault`).
    public static func isDefault(_ key: String, _ value: JSONValue) -> Bool {
        guard let spec = byKey[key], let normal = normalize(spec.kind, value) else { return false }
        return normal == spec.defaultValue
    }

    // MARK: Rules

    static func normalize(_ kind: SyncSettingKind, _ value: JSONValue) -> JSONValue? {
        switch kind {
        case .bool:
            if case .bool = value { return value }
            return nil
        case let .int(lo, hi):
            return number(value).map { .int(clamp(whole($0), lo, hi)) }
        case let .tenths(lo, hi):
            return number(value).map { .double(tenths(Swift.min(Swift.max($0, lo), hi))) }
        case .enumeration(let values):
            guard case .string(let s) = value, values.contains(s) else { return nil }
            return value
        case .intChoice(let values):
            guard let n = number(value).map(whole), values.contains(n) else { return nil }
            return .int(n)
        case let .members(values, minCount):
            guard case .array(let items) = value else { return nil }
            let present = Set(items.compactMap { item -> String? in
                if case .string(let s) = item { return s }
                return nil
            })
            let kept = values.filter(present.contains)
            return kept.count >= minCount ? .array(kept.map(JSONValue.string)) : nil
        case let .text(maxLength, transform):
            guard case .string(let s) = value else { return nil }
            return text(s, maxLength: maxLength, transform: transform).map(JSONValue.string)
        case let .words(maxCount, maxLength):
            guard case .array(let items) = value else { return nil }
            var seen = Set<String>()
            var out: [JSONValue] = []
            for item in items {
                guard case .string(let raw) = item else { continue }
                let word = prefixUTF16(raw.trimmingCharacters(in: .whitespacesAndNewlines).uppercased(), maxLength)
                guard !word.isEmpty, seen.insert(word).inserted else { continue }
                out.append(.string(word))
                if out.count == maxCount { break }
            }
            return .array(out)
        case let .range(lo, hi, isWhole):
            guard let a = number(field(value, "min")), let b = number(field(value, "max")) else { return nil }
            func fit(_ x: Double, _ r: ClosedRange<Double>) -> Double {
                let c = Swift.min(Swift.max(x, r.lowerBound), r.upperBound)
                return isWhole ? c.rounded() : tenths(c)
            }
            let low = fit(a, lo)
            let high = Swift.max(fit(b, hi), low)
            if isWhole { return .object(["min": .int(Int(low)), "max": .int(Int(high))]) }
            return .object(["min": .double(low), "max": .double(high)])
        case .speed:
            guard let c = number(field(value, "characterWpm")), let e = number(field(value, "effectiveWpm")),
                  case .bool(let farnsworth) = field(value, "farnsworth") else { return nil }
            let character = clamp(whole(c), 15, 60)
            let effective = Swift.min(clamp(whole(e), 8, 60), character)
            return .object(["characterWpm": .int(character), "farnsworth": .bool(farnsworth), "effectiveWpm": .int(effective)])
        }
    }

    private static func field(_ value: JSONValue, _ name: String) -> JSONValue {
        if case .object(let o) = value { return o[name] ?? .null }
        return .null
    }

    private static func number(_ value: JSONValue) -> Double? {
        switch value {
        case .int(let i): return Double(i)
        case .double(let d) where d.isFinite: return d
        default: return nil
        }
    }

    private static func whole(_ x: Double) -> Int {
        let r = x.rounded()
        if r >= Double(Int.max) { return Int.max }
        if r <= Double(Int.min) { return Int.min }
        return Int(r)
    }

    private static func clamp(_ x: Int, _ lo: Int, _ hi: Int) -> Int { Swift.min(Swift.max(x, lo), hi) }

    /// One decimal place: round(x * 10) / 10.
    private static func tenths(_ x: Double) -> Double { (x * 10).rounded() / 10 }

    /// The longest prefix of `s` that fits in `max` UTF-16 units, never
    /// splitting a character.
    static func prefixUTF16(_ s: String, _ max: Int) -> String {
        var out = ""
        var units = 0
        for ch in s {
            let n = ch.utf16.count
            if units + n > max { break }
            out.append(ch)
            units += n
        }
        return out
    }

    static func text(_ s: String, maxLength: Int, transform: SyncTextTransform) -> String? {
        switch transform {
        case .callsign:
            let kept = s.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
                .filter { ($0.isASCII && ($0.isLetter || $0.isNumber)) || $0 == "/" }
            return String(kept.prefix(maxLength))
        case .state:
            let kept = s.uppercased().filter { $0.isASCII && $0.isLetter }
            return String(kept.prefix(maxLength))
        case .trim:
            return prefixUTF16(s.trimmingCharacters(in: .whitespacesAndNewlines), maxLength)
        case .id:
            guard s.utf16.count <= maxLength,
                  s.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "_" || $0 == "-") }) else { return nil }
            return s
        }
    }
}
