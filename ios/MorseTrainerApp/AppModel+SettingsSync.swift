// AppModel+SettingsSync.swift
// `AppSettings` as the account's settings keys see it: each synced setting's
// wire value, and a received value written back. The keys, shapes, defaults
// and normalising rules are MorseKit's `SyncSettings` (fixtures/sync-wire.json
// `settings`); the engine normalises both ways, so a value arriving here is
// already in range. Device settings (audio route, keep-alive, haptics, keys,
// spoken and keyed answers, reminders) and the leaderboard and buddy consents
// are not keys and never pass through here.

import Foundation

extension AppModel {
    /// A setting's current value in its wire shape, normalised. Never nil for
    /// a settings key: a setting always has a value, and the engine decides
    /// whether it goes (only once stamped).
    func syncSettingValue(_ key: SyncStateKey) -> JSONValue? {
        guard key.isSetting, let raw = Self.rawSettingValue(key.rawValue, settings) else { return nil }
        return SyncSettings.normalize(key.rawValue, raw) ?? SyncSettings.defaultValue(key.rawValue)
    }

    /// Every synced setting's value, for noting changes after a save.
    var syncSettingValues: [SyncStateKey: JSONValue] {
        var out: [SyncStateKey: JSONValue] = [:]
        for key in SyncSettings.keys { out[key] = syncSettingValue(key) }
        return out
    }

    // swiftlint:disable:next cyclomatic_complexity function_body_length
    private static func rawSettingValue(_ key: String, _ s: AppSettings) -> JSONValue? {
        func strings<S: Sequence>(_ items: S) -> JSONValue where S.Element == String { .array(items.map(JSONValue.string)) }
        func whole(_ x: Double) -> JSONValue { .int(Int(x.rounded())) }
        switch key {
        case "setting.speed":
            return .object(["characterWpm": whole(s.wpm), "farnsworth": .bool(s.farnsworth), "effectiveWpm": whole(s.effectiveWpm)])
        case "setting.tonePitch": return whole(s.toneFrequency)
        case "setting.bandNoise": return .string(s.bandNoise == .keepAlive ? BackgroundNoiseLevel.off.rawValue : s.bandNoise.rawValue)
        case "setting.practiceDuration": return .string(s.practiceDuration.rawValue)
        case "setting.recognitionTarget": return .double(s.ttrThreshold)
        case "setting.answerChoices": return .int(s.maxAnswerChoices)
        case "setting.answerEntry": return .string(s.answerEntry.rawValue)
        case "setting.reveal": return .string(s.reveal.rawValue)
        case "setting.showCorrectness": return .bool(s.showCorrectness)
        case "setting.allowReplay": return .bool(s.allowReplay)
        case "setting.slashedZero": return .bool(s.slashedZero)
        case "setting.introduceNewCharacters": return .bool(s.introduceNewCharacters)
        case "setting.punctuation": return strings(s.selectedPunctuation)
        case "setting.journeyDrainOnMiss": return .bool(s.journeyDrainOnMiss)
        case "setting.wordPool": return .string(s.wordTier.rawValue)
        case "setting.useCustomWords": return .bool(s.useCustomWords)
        case "setting.customWords": return strings(s.customWords)
        case "setting.cw77IncludeMe": return .bool(s.cw77IncludeMe)
        case "setting.cw77Style": return .string(s.cw77Style.rawValue)
        case "setting.listenContent": return .string(s.listenContent.rawValue)
        case "setting.listenGap": return .string(s.listenGap.rawValue)
        case "setting.listenReadback": return .string(s.listenReadback.rawValue)
        case "setting.headCopyRepeats": return .int(s.headCopyRepeats)
        case "setting.headCopyRevealSeconds": return whole(s.headCopyRevealSeconds)
        case "setting.qrqSpeed": return whole(s.qrqSpeed.wpm)
        case "setting.examSpeed": return .string(s.examSpeed.rawValue)
        case "setting.examUseBundled": return .bool(s.examUseBundled)
        case "setting.storyContent": return .string(s.story.content.rawValue)
        case "setting.storySerialId": return .string(s.story.serialId)
        case "setting.newsSource": return .string(s.story.newsSource.rawValue)
        case "setting.newsFullStory": return .bool(s.story.newsFullStory)
        case "setting.contestType": return .string(s.contest.type.rawValue)
        case "setting.contestLength": return .string(s.contest.length.rawValue)
        case "setting.rapidFireContent": return .string(s.rapidFire.content.rawValue)
        case "setting.rapidFireResponse": return .string(s.rapidFire.response.rawValue)
        case "setting.rapidFirePace": return .string(s.rapidFire.pace.rawValue)
        case "setting.rapidFireWordLength":
            return .object(["min": .int(s.rapidFire.wordMinLength), "max": .int(s.rapidFire.wordMaxLength)])
        case "setting.rapidFireNumberCount": return .int(s.rapidFire.numberCount)
        case "setting.rapidFireUsOnly": return .bool(s.rapidFire.callsignUSOnly)
        case "setting.rapidFireFormats": return strings(s.rapidFire.callsignFormats.map(\.rawValue))
        case "setting.rapidFireStatesSections": return .bool(s.rapidFire.statesIncludeSections)
        case "setting.rapidFireSerialCut": return .bool(s.rapidFire.serialCutNumbers)
        case "setting.myCall": return .string(s.qso.myCall)
        case "setting.myName": return .string(s.qso.myName)
        case "setting.myState": return .string(s.qso.myState)
        case "setting.pileupMode": return .string(s.qso.mode.rawValue)
        case "setting.pileupMaxStations": return .int(s.qso.maxStations)
        case "setting.pileupCallerSpeed": return .object(["min": whole(s.qso.minWPM), "max": whole(s.qso.maxWPM)])
        case "setting.pileupCallerFarnsworth": return .bool(s.qso.farnsworth)
        case "setting.pileupToneSpread": return whole(s.qso.toneSpread)
        case "setting.pileupDelay": return .object(["min": .double(s.qso.minDelay), "max": .double(s.qso.maxDelay)])
        case "setting.pileupQsb": return .bool(s.qso.qsbEnabled)
        case "setting.pileupQrn": return .string(s.qso.qrn.rawValue)
        case "setting.pileupCutNumbers": return .bool(s.qso.cutNumbersEnabled)
        case "setting.pileupCutDigits": return strings(s.qso.cutDigits)
        case "setting.pileupRstRequired": return .bool(s.qso.rstRequired)
        case "setting.pileupBust": return .string(s.qso.bustBehavior.rawValue)
        case "setting.pileupGiveUp": return .bool(s.qso.giveUpEnabled)
        case "setting.pileupFormats": return strings(s.qso.formats.map(\.rawValue))
        case "setting.pileupUsOnly": return .bool(s.qso.usOnly)
        case "setting.pileupKeepPartialCall": return .bool(s.qso.keepPartialCall)
        case "setting.pileupMissedCallerFeedback": return .string(s.qso.missedCallerFeedback.rawValue)
        case "setting.pileupKeyMySide": return .bool(s.qso.keyMySide)
        case "setting.pileupAutoRecall": return .bool(s.qso.autoRecall)
        default: return nil
        }
    }

    /// Write a setting received from the account (already normalised by the
    /// engine) into `settings`, saved once and not noted as a change of ours.
    /// False when the value is not one this app can hold, so the local
    /// setting and its stamp stand.
    func applySyncedSetting(_ key: SyncStateKey, value: JSONValue) -> Bool {
        var s = settings
        guard Self.write(key.rawValue, value, into: &s) else { return false }
        guard s != settings else { return true }
        applyingSyncedSettings = true
        settings = s
        applyingSyncedSettings = false
        return true
    }

    // swiftlint:disable:next cyclomatic_complexity function_body_length
    private static func write(_ key: String, _ v: JSONValue, into s: inout AppSettings) -> Bool {
        func bool() -> Bool? { if case .bool(let b) = v { return b }; return nil }
        func int(_ x: JSONValue = .null) -> Int? {
            switch x == .null ? v : x {
            case .int(let i): return i
            case .double(let d): return Int(d.rounded())
            default: return nil
            }
        }
        func double(_ x: JSONValue = .null) -> Double? {
            switch x == .null ? v : x {
            case .int(let i): return Double(i)
            case .double(let d): return d
            default: return nil
            }
        }
        func string() -> String? { if case .string(let t) = v { return t }; return nil }
        func strings() -> [String]? {
            guard case .array(let items) = v else { return nil }
            return items.compactMap { if case .string(let t) = $0 { return t }; return nil }
        }
        func field(_ name: String) -> JSONValue {
            if case .object(let o) = v { return o[name] ?? .null }
            return .null
        }
        func set<T>(_ value: T?, _ apply: (T) -> Void) -> Bool {
            guard let value else { return false }
            apply(value)
            return true
        }
        switch key {
        case "setting.speed":
            guard let c = double(field("characterWpm")), let e = double(field("effectiveWpm")),
                  case .bool(let f) = field("farnsworth") else { return false }
            s.wpm = c
            s.farnsworth = f
            s.effectiveWpm = min(e, c)
            return true
        case "setting.tonePitch": return set(double()) { s.toneFrequency = $0 }
        case "setting.bandNoise": return set(string().flatMap(BackgroundNoiseLevel.init(rawValue:))) { s.bandNoise = $0 }
        case "setting.practiceDuration": return set(string().flatMap(PracticeDuration.init(rawValue:))) { s.practiceDuration = $0 }
        case "setting.recognitionTarget": return set(double()) { s.ttrThreshold = $0 }
        case "setting.answerChoices":
            return set(int()) { s.maxAnswerChoices = min(max($0, AppSettings.answerChoiceRange.lowerBound), AppSettings.answerChoiceRange.upperBound) }
        case "setting.answerEntry": return set(string().flatMap(AnswerEntryMode.init(rawValue:))) { s.answerEntry = $0 }
        case "setting.reveal": return set(string().flatMap(RevealMode.init(rawValue:))) { s.reveal = $0 }
        case "setting.showCorrectness": return set(bool()) { s.showCorrectness = $0 }
        case "setting.allowReplay": return set(bool()) { s.allowReplay = $0 }
        case "setting.slashedZero": return set(bool()) { s.slashedZero = $0 }
        case "setting.introduceNewCharacters": return set(bool()) { s.introduceNewCharacters = $0 }
        case "setting.punctuation": return set(strings()) { s.selectedPunctuation = Set($0) }
        case "setting.journeyDrainOnMiss": return set(bool()) { s.journeyDrainOnMiss = $0 }
        case "setting.wordPool": return set(string().flatMap(WordTier.init(rawValue:))) { s.wordTier = $0 }
        case "setting.useCustomWords": return set(bool()) { s.useCustomWords = $0 }
        case "setting.customWords":
            // This app's own word rules (sendable characters only) after the wire's.
            return set(strings()) { s.customWords = MorseData.parseWordList($0.joined(separator: " ")) }
        case "setting.cw77IncludeMe": return set(bool()) { s.cw77IncludeMe = $0 }
        case "setting.cw77Style": return set(string().flatMap(CW77Style.init(rawValue:))) { s.cw77Style = $0 }
        case "setting.listenContent": return set(string().flatMap(ListenContent.init(rawValue:))) { s.listenContent = $0 }
        case "setting.listenGap": return set(string().flatMap(AnswerGap.init(rawValue:))) { s.listenGap = $0 }
        case "setting.listenReadback": return set(string().flatMap(ListenReadback.init(rawValue:))) { s.listenReadback = $0 }
        case "setting.headCopyRepeats":
            return set(int()) { s.headCopyRepeats = min(max($0, AppSettings.headCopyRepeatRange.lowerBound), AppSettings.headCopyRepeatRange.upperBound) }
        case "setting.headCopyRevealSeconds":
            return set(double()) { s.headCopyRevealSeconds = min(max($0, AppSettings.headCopyRevealRange.lowerBound), AppSettings.headCopyRevealRange.upperBound) }
        case "setting.qrqSpeed": return set(int().flatMap { n in QrqSpeed.allCases.first { Int($0.wpm) == n } }) { s.qrqSpeed = $0 }
        case "setting.examSpeed": return set(string().flatMap(ExamSpeed.init(rawValue:))) { s.examSpeed = $0 }
        case "setting.examUseBundled": return set(bool()) { s.examUseBundled = $0 }
        case "setting.storyContent": return set(string().flatMap(StoryContent.init(rawValue:))) { s.story.content = $0 }
        case "setting.storySerialId": return set(string()) { s.story.serialId = $0 }
        case "setting.newsSource": return set(string().flatMap(NewsSource.init(rawValue:))) { s.story.newsSource = $0 }
        case "setting.newsFullStory": return set(bool()) { s.story.newsFullStory = $0 }
        case "setting.contestType": return set(string().flatMap(ContestType.init(rawValue:))) { s.contest.type = $0 }
        case "setting.contestLength": return set(string().flatMap(ContestLength.init(rawValue:))) { s.contest.length = $0 }
        case "setting.rapidFireContent": return set(string().flatMap(RapidFireContent.init(rawValue:))) { s.rapidFire.content = $0 }
        case "setting.rapidFireResponse": return set(string().flatMap(RapidFireResponse.init(rawValue:))) { s.rapidFire.response = $0 }
        case "setting.rapidFirePace": return set(string().flatMap(RapidFirePace.init(rawValue:))) { s.rapidFire.pace = $0 }
        case "setting.rapidFireWordLength":
            guard let lo = int(field("min")), let hi = int(field("max")) else { return false }
            let range = RapidFireSettings.wordLengthRange
            s.rapidFire.wordMinLength = min(max(lo, range.lowerBound), range.upperBound)
            s.rapidFire.wordMaxLength = max(s.rapidFire.wordMinLength, min(hi, range.upperBound))
            return true
        case "setting.rapidFireNumberCount":
            let range = RapidFireSettings.numberCountRange
            return set(int()) { s.rapidFire.numberCount = min(max($0, range.lowerBound), range.upperBound) }
        case "setting.rapidFireUsOnly": return set(bool()) { s.rapidFire.callsignUSOnly = $0 }
        case "setting.rapidFireFormats":
            let formats = Set((strings() ?? []).compactMap(CallsignFormat.init(rawValue:)))
            return set(formats.isEmpty ? nil : formats) { s.rapidFire.callsignFormats = $0 }
        case "setting.rapidFireStatesSections": return set(bool()) { s.rapidFire.statesIncludeSections = $0 }
        case "setting.rapidFireSerialCut": return set(bool()) { s.rapidFire.serialCutNumbers = $0 }
        case "setting.myCall": return set(string()) { s.qso.myCall = $0 }
        case "setting.myName": return set(string()) { s.qso.myName = $0 }
        case "setting.myState": return set(string()) { s.qso.myState = $0 }
        case "setting.pileupMode": return set(string().flatMap(QSOContestMode.init(rawValue:))) { s.qso.mode = $0 }
        case "setting.pileupMaxStations": return set(int()) { s.qso.maxStations = min(max($0, 1), 8) }
        case "setting.pileupCallerSpeed":
            guard let lo = double(field("min")), let hi = double(field("max")) else { return false }
            s.qso.minWPM = lo
            s.qso.maxWPM = max(lo, hi)
            return true
        case "setting.pileupCallerFarnsworth": return set(bool()) { s.qso.farnsworth = $0 }
        case "setting.pileupToneSpread": return set(double()) { s.qso.toneSpread = $0 }
        case "setting.pileupDelay":
            guard let lo = double(field("min")), let hi = double(field("max")) else { return false }
            s.qso.minDelay = lo
            s.qso.maxDelay = max(lo, hi)
            return true
        case "setting.pileupQsb": return set(bool()) { s.qso.qsbEnabled = $0 }
        case "setting.pileupQrn": return set(string().flatMap(QRNLevel.init(rawValue:))) { s.qso.qrn = $0 }
        case "setting.pileupCutNumbers": return set(bool()) { s.qso.cutNumbersEnabled = $0 }
        case "setting.pileupCutDigits": return set(strings()) { s.qso.cutDigits = Set($0) }
        case "setting.pileupRstRequired": return set(bool()) { s.qso.rstRequired = $0 }
        case "setting.pileupBust": return set(string().flatMap(BustBehavior.init(rawValue:))) { s.qso.bustBehavior = $0 }
        case "setting.pileupGiveUp": return set(bool()) { s.qso.giveUpEnabled = $0 }
        case "setting.pileupFormats":
            let formats = Set((strings() ?? []).compactMap(CallsignFormat.init(rawValue:)))
            return set(formats.isEmpty ? nil : formats) { s.qso.formats = $0 }
        case "setting.pileupUsOnly": return set(bool()) { s.qso.usOnly = $0 }
        case "setting.pileupKeepPartialCall": return set(bool()) { s.qso.keepPartialCall = $0 }
        case "setting.pileupMissedCallerFeedback":
            return set(string().flatMap(MissedCallerFeedback.init(rawValue:))) { s.qso.missedCallerFeedback = $0 }
        case "setting.pileupKeyMySide": return set(bool()) { s.qso.keyMySide = $0 }
        case "setting.pileupAutoRecall": return set(bool()) { s.qso.autoRecall = $0 }
        default: return false
        }
    }
}
