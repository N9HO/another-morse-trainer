package app.anothermorsetrainer

import app.anothermorsetrainer.morsekit.AnswerEntryMode
import app.anothermorsetrainer.morsekit.BustBehavior
import app.anothermorsetrainer.morsekit.CallsignFormat
import app.anothermorsetrainer.morsekit.ContestLength
import app.anothermorsetrainer.morsekit.ContestType
import app.anothermorsetrainer.morsekit.Cw77Style
import app.anothermorsetrainer.morsekit.ExamSpeed
import app.anothermorsetrainer.morsekit.MissedCallerFeedback
import app.anothermorsetrainer.morsekit.QSOContestMode
import app.anothermorsetrainer.morsekit.RapidFireContent
import app.anothermorsetrainer.morsekit.RapidFirePace
import app.anothermorsetrainer.morsekit.RapidFireResponse
import app.anothermorsetrainer.morsekit.SyncSettings
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * [Settings] and [PileupSettings] as the account's settings keys see them:
 * each synced setting's wire value, and a received value written back through
 * the stores' own update functions (so their clamps and side effects run).
 * The keys, shapes, defaults and normalising rules are [SyncSettings]
 * (`fixtures/sync-wire.json` `settings`); the iOS twin is
 * `AppModel+SettingsSync.swift`. [AppSyncLocal] calls [apply] with
 * [SyncCoordinator.applyingSettings] set, so the stores' save hook does not
 * stamp what the account just handed us.
 *
 * Device settings (keep-alive, haptics, the on-screen key and paddles, spoken
 * and keyed answers, reminders) and the leaderboard and buddy consents are
 * not keys and never pass through here.
 */
object SettingsSync {

    /**
     * The local enum constants for each enum key, in the order of the key's
     * wire values ([SyncSettings.Kind.Enum.values]); fixture `kotlin`.
     */
    val enums: Map<String, List<Enum<*>>> = mapOf(
        "setting.bandNoise" to listOf(
            BackgroundNoiseLevel.OFF, BackgroundNoiseLevel.WHISPER, BackgroundNoiseLevel.LOW,
            BackgroundNoiseLevel.MEDIUM, BackgroundNoiseLevel.HIGH
        ),
        "setting.practiceDuration" to PracticeDuration.entries,
        "setting.answerEntry" to AnswerEntryMode.entries,
        "setting.reveal" to RevealMode.entries,
        "setting.cw77Style" to Cw77Style.entries,
        "setting.listenContent" to ListenContent.entries,
        "setting.listenGap" to ListenGap.entries,
        "setting.listenReadback" to ListenReadback.entries,
        "setting.examSpeed" to ExamSpeed.entries,
        "setting.storyContent" to StoryContent.entries,
        "setting.newsSource" to NewsSource.entries,
        "setting.contestType" to ContestType.entries,
        "setting.contestLength" to ContestLength.entries,
        "setting.rapidFireContent" to RapidFireContent.entries,
        "setting.rapidFireResponse" to RapidFireResponse.entries,
        "setting.rapidFirePace" to RapidFirePace.entries,
        "setting.pileupMode" to QSOContestMode.entries,
        "setting.pileupQrn" to QrnPreset.entries,
        "setting.pileupBust" to BustBehavior.entries,
        "setting.pileupMissedCallerFeedback" to MissedCallerFeedback.entries,
    )

    /** `setting.wordPool`'s wire values as [Settings.wordCount] stores them (fixture `kotlin`). */
    val wordCounts: List<Int> = listOf(100, 300, 500, 1000, Settings.WORD_POOL_CW77)

    private fun wireValues(key: String): List<String> =
        (SyncSettings.spec(key)?.kind as? SyncSettings.Kind.Enum)?.values ?: emptyList()

    /** [value]'s wire string for enum [key], or null when it has none. */
    fun wireOf(key: String, value: Enum<*>): String? {
        val i = enums[key]?.indexOf(value) ?: return null
        return wireValues(key).getOrNull(i)
    }

    /** The local constant for a wire string of enum [key], or null for one this app does not know. */
    fun localConstant(key: String, wire: Any?): Enum<*>? {
        val i = wireValues(key).indexOf(wire as? String ?: return null)
        return enums[key]?.getOrNull(i)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <E : Enum<E>> localOf(key: String, wire: Any?): E? = localConstant(key, wire) as E?

    private fun strings(items: Iterable<String>) = JSONArray(items.toList())

    /** Every synced setting's current value, normalised, keyed by state key. */
    fun values(): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        for (key in SyncSettings.KEYS) value(key)?.let { out[key] = it }
        return out
    }

    /** One setting's current value, normalised; null for a key this app does not sync. */
    fun value(key: String): Any? {
        if (SyncSettings.spec(key) == null) return null
        return raw(key)?.let { SyncSettings.normalize(key, it) } ?: SyncSettings.defaultValue(key)
    }

    private fun raw(key: String): Any? {
        val s = Settings
        val p = PileupSettings
        return when (key) {
            "setting.speed" -> JSONObject().put("characterWpm", s.characterWpm.roundToInt())
                .put("farnsworth", s.farnsworthEnabled).put("effectiveWpm", s.effectiveWpm.roundToInt())
            "setting.tonePitch" -> s.sidetoneHz.roundToInt()
            "setting.bandNoise" -> wireOf(key, s.bandNoise) ?: "off"
            "setting.practiceDuration" -> wireOf(key, s.practiceDuration)
            "setting.recognitionTarget" -> s.recognitionTargetSec
            "setting.answerChoices" -> s.answerChoices
            "setting.answerEntry" -> wireOf(key, s.answerEntry)
            "setting.reveal" -> wireOf(key, s.revealMode)
            "setting.showCorrectness" -> s.showCorrectness
            "setting.allowReplay" -> s.allowReplay
            "setting.slashedZero" -> s.slashedZero
            "setting.introduceNewCharacters" -> s.introduceNewCharacters
            "setting.punctuation" -> strings(s.punctuationChars.map { it.toString() })
            "setting.journeyDrainOnMiss" -> s.journeyDrainOnMiss
            "setting.wordPool" -> wireValues(key).getOrNull(wordCounts.indexOf(s.wordCount))
            "setting.useCustomWords" -> s.useCustomWords
            "setting.customWords" -> strings(s.customWords)
            "setting.cw77IncludeMe" -> s.cw77IncludeMe
            "setting.cw77Style" -> wireOf(key, s.cw77Style)
            "setting.listenContent" -> wireOf(key, s.listenContent)
            "setting.listenGap" -> wireOf(key, s.listenGap)
            "setting.listenReadback" -> wireOf(key, s.listenReadback)
            "setting.headCopyRepeats" -> s.headCopyRepeats
            "setting.headCopyRevealSeconds" -> s.headCopyRevealSec
            "setting.qrqSpeed" -> s.qrqWpm.roundToInt()
            "setting.examSpeed" -> wireOf(key, s.examSpeed)
            "setting.examUseBundled" -> s.examUseBundled
            "setting.storyContent" -> wireOf(key, s.storyContent)
            "setting.storySerialId" -> s.storySerialId
            "setting.newsSource" -> wireOf(key, s.newsSource)
            "setting.newsFullStory" -> s.newsFullStory
            "setting.contestType" -> wireOf(key, s.contestType)
            "setting.contestLength" -> wireOf(key, s.contestLength)
            "setting.rapidFireContent" -> wireOf(key, s.rapidFireContent)
            "setting.rapidFireResponse" -> wireOf(key, s.rapidFireResponse)
            "setting.rapidFirePace" -> wireOf(key, s.rapidFirePace)
            "setting.rapidFireWordLength" -> JSONObject().put("min", s.rapidFireWordMin).put("max", s.rapidFireWordMax)
            "setting.rapidFireNumberCount" -> s.rapidFireNumberCount
            "setting.rapidFireUsOnly" -> s.rapidFireUsOnly
            "setting.rapidFireFormats" -> strings(s.rapidFireFormats.map { it.code })
            "setting.rapidFireStatesSections" -> s.rapidFireSections
            "setting.rapidFireSerialCut" -> s.rapidFireSerialCut
            "setting.myCall" -> p.myCall
            "setting.myName" -> p.myName
            "setting.myState" -> p.myState
            "setting.pileupMode" -> wireOf(key, p.mode)
            "setting.pileupMaxStations" -> p.maxStations
            "setting.pileupCallerSpeed" -> JSONObject().put("min", p.minWpm.roundToInt()).put("max", p.maxWpm.roundToInt())
            "setting.pileupCallerFarnsworth" -> p.callerFarnsworth
            "setting.pileupToneSpread" -> p.toneSpread.roundToInt()
            "setting.pileupDelay" -> JSONObject().put("min", p.minDelay).put("max", p.maxDelay)
            "setting.pileupQsb" -> p.qsbEnabled
            "setting.pileupQrn" -> wireOf(key, p.qrn)
            "setting.pileupCutNumbers" -> p.cutNumbersEnabled
            "setting.pileupCutDigits" -> strings(p.cutDigits.map { it.toString() })
            "setting.pileupRstRequired" -> p.rstRequired
            "setting.pileupBust" -> wireOf(key, p.bustBehavior)
            "setting.pileupGiveUp" -> p.giveUpEnabled
            "setting.pileupFormats" -> strings(p.formats.map { it.code })
            "setting.pileupUsOnly" -> p.usOnly
            "setting.pileupKeepPartialCall" -> p.keepPartialCall
            "setting.pileupMissedCallerFeedback" -> wireOf(key, p.missedCallerFeedback)
            "setting.pileupKeyMySide" -> p.keyMySide
            "setting.pileupAutoRecall" -> p.autoRecall
            else -> null
        }
    }

    private fun num(v: Any?): Double? = (v as? Number)?.toDouble()
    private fun int(v: Any?): Int? = num(v)?.roundToInt()
    private fun list(v: Any?): List<String>? =
        (v as? JSONArray)?.let { a -> (0 until a.length()).mapNotNull { a.opt(it) as? String } }

    /**
     * Write a received, already normalised [value] through the stores' own
     * update functions. Returns false when this app cannot hold it, so the
     * local setting stands. The engine marks the write as applying, so the
     * stores' save hook does not stamp it.
     */
    fun apply(key: String, value: Any): Boolean {
        val s = Settings
        val p = PileupSettings
        fun <T> set(v: T?, write: (T) -> Unit): Boolean = if (v == null) false else { write(v); true }
        return when (key) {
            "setting.speed" -> {
                val o = value as? JSONObject ?: return false
                val c = num(o.opt("characterWpm")) ?: return false
                val e = num(o.opt("effectiveWpm")) ?: return false
                val f = o.opt("farnsworth") as? Boolean ?: return false
                s.updateCharacterWpm(c)
                s.updateFarnsworthEnabled(f)
                s.updateEffectiveWpm(e)
                true
            }
            "setting.tonePitch" -> set(num(value)) { s.updateSidetoneHz(it) }
            "setting.bandNoise" -> set(localOf<BackgroundNoiseLevel>(key, value)) { s.updateBandNoise(it) }
            "setting.practiceDuration" -> set(localOf<PracticeDuration>(key, value)) { s.updatePracticeDuration(it) }
            "setting.recognitionTarget" -> set(num(value)) { s.updateRecognitionTargetSec(it) }
            "setting.answerChoices" -> set(int(value)) { s.updateAnswerChoices(it) }
            "setting.answerEntry" -> set(localOf<AnswerEntryMode>(key, value)) { s.updateAnswerEntry(it) }
            "setting.reveal" -> set(localOf<RevealMode>(key, value)) { s.updateRevealMode(it) }
            "setting.showCorrectness" -> set(value as? Boolean) { s.updateShowCorrectness(it) }
            "setting.allowReplay" -> set(value as? Boolean) { s.updateAllowReplay(it) }
            "setting.slashedZero" -> set(value as? Boolean) { s.updateSlashedZero(it) }
            "setting.introduceNewCharacters" -> set(value as? Boolean) { s.updateIntroduceNewCharacters(it) }
            "setting.punctuation" -> set(list(value)) { l -> s.updatePunctuation(l.mapNotNull { it.firstOrNull() }.toSet()) }
            "setting.journeyDrainOnMiss" -> set(value as? Boolean) { s.updateJourneyDrainOnMiss(it) }
            "setting.wordPool" ->
                set((value as? String)?.let { wordCounts.getOrNull(wireValues(key).indexOf(it)) }) { s.updateWordCount(it) }
            "setting.useCustomWords" -> set(value as? Boolean) { s.updateUseCustomWords(it) }
            // The custom-word parser (sendable characters only) runs on the text, as for typed words.
            "setting.customWords" -> set(list(value)) { s.updateCustomWordsText(it.joinToString("\n")) }
            "setting.cw77IncludeMe" -> set(value as? Boolean) { s.updateCw77IncludeMe(it) }
            "setting.cw77Style" -> set(localOf<Cw77Style>(key, value)) { s.updateCw77Style(it) }
            "setting.listenContent" -> set(localOf<ListenContent>(key, value)) { s.updateListenContent(it) }
            "setting.listenGap" -> set(localOf<ListenGap>(key, value)) { s.updateListenGap(it) }
            "setting.listenReadback" -> set(localOf<ListenReadback>(key, value)) { s.updateListenReadback(it) }
            "setting.headCopyRepeats" -> set(int(value)) { s.updateHeadCopyRepeats(it) }
            "setting.headCopyRevealSeconds" -> set(int(value)) { s.updateHeadCopyRevealSec(it) }
            "setting.qrqSpeed" -> set(num(value)) { s.updateQrqWpm(it) }
            "setting.examSpeed" -> set(localOf<ExamSpeed>(key, value)) { s.updateExamSpeed(it) }
            "setting.examUseBundled" -> set(value as? Boolean) { s.updateExamUseBundled(it) }
            "setting.storyContent" -> set(localOf<StoryContent>(key, value)) { s.updateStoryContent(it) }
            "setting.storySerialId" -> set(value as? String) { s.updateStorySerialId(it) }
            "setting.newsSource" -> set(localOf<NewsSource>(key, value)) { s.updateNewsSource(it) }
            "setting.newsFullStory" -> set(value as? Boolean) { s.updateNewsFullStory(it) }
            "setting.contestType" -> set(localOf<ContestType>(key, value)) { s.updateContestType(it) }
            "setting.contestLength" -> set(localOf<ContestLength>(key, value)) { s.updateContestLength(it) }
            "setting.rapidFireContent" -> set(localOf<RapidFireContent>(key, value)) { s.updateRapidFireContent(it) }
            "setting.rapidFireResponse" -> set(localOf<RapidFireResponse>(key, value)) { s.updateRapidFireResponse(it) }
            "setting.rapidFirePace" -> set(localOf<RapidFirePace>(key, value)) { s.updateRapidFirePace(it) }
            "setting.rapidFireWordLength" -> {
                val o = value as? JSONObject ?: return false
                val lo = int(o.opt("min")) ?: return false
                val hi = int(o.opt("max")) ?: return false
                s.updateRapidFireWordMin(lo)
                s.updateRapidFireWordMax(hi)
                true
            }
            "setting.rapidFireNumberCount" -> set(int(value)) { s.updateRapidFireNumberCount(it) }
            "setting.rapidFireUsOnly" -> set(value as? Boolean) { s.updateRapidFireUsOnly(it) }
            "setting.rapidFireFormats" ->
                set(list(value)?.let { l -> CallsignFormat.entries.filter { it.code in l }.toSet() }?.ifEmpty { null }) { s.updateRapidFireFormats(it) }
            "setting.rapidFireStatesSections" -> set(value as? Boolean) { s.updateRapidFireSections(it) }
            "setting.rapidFireSerialCut" -> set(value as? Boolean) { s.updateRapidFireSerialCut(it) }
            "setting.myCall" -> set(value as? String) { p.updateMyCall(it) }
            "setting.myName" -> set(value as? String) { p.updateMyName(it) }
            "setting.myState" -> set(value as? String) { p.updateMyState(it) }
            "setting.pileupMode" -> set(localOf<QSOContestMode>(key, value)) { p.updateMode(it) }
            "setting.pileupMaxStations" -> set(int(value)) { p.updateMaxStations(it) }
            "setting.pileupCallerSpeed" -> {
                val o = value as? JSONObject ?: return false
                val lo = num(o.opt("min")) ?: return false
                val hi = num(o.opt("max")) ?: return false
                p.updateMinWpm(lo)
                p.updateMaxWpm(hi)
                true
            }
            "setting.pileupCallerFarnsworth" -> set(value as? Boolean) { p.updateCallerFarnsworth(it) }
            "setting.pileupToneSpread" -> set(num(value)) { p.updateToneSpread(it) }
            "setting.pileupDelay" -> {
                val o = value as? JSONObject ?: return false
                val lo = num(o.opt("min")) ?: return false
                val hi = num(o.opt("max")) ?: return false
                p.updateMinDelay(lo)
                p.updateMaxDelay(hi)
                true
            }
            "setting.pileupQsb" -> set(value as? Boolean) { p.updateQsbEnabled(it) }
            "setting.pileupQrn" -> set(localOf<QrnPreset>(key, value)) { p.updateQrn(it) }
            "setting.pileupCutNumbers" -> set(value as? Boolean) { p.updateCutNumbersEnabled(it) }
            "setting.pileupCutDigits" -> set(list(value)) { l -> p.updateCutDigits(l.mapNotNull { it.firstOrNull() }.toSet()) }
            "setting.pileupRstRequired" -> set(value as? Boolean) { p.updateRstRequired(it) }
            "setting.pileupBust" -> set(localOf<BustBehavior>(key, value)) { p.updateBustBehavior(it) }
            "setting.pileupGiveUp" -> set(value as? Boolean) { p.updateGiveUpEnabled(it) }
            "setting.pileupFormats" ->
                set(list(value)?.let { l -> CallsignFormat.entries.filter { it.code in l }.toSet() }?.ifEmpty { null }) { p.updateFormats(it) }
            "setting.pileupUsOnly" -> set(value as? Boolean) { p.updateUsOnly(it) }
            "setting.pileupKeepPartialCall" -> set(value as? Boolean) { p.updateKeepPartialCall(it) }
            "setting.pileupMissedCallerFeedback" -> set(localOf<MissedCallerFeedback>(key, value)) { p.updateMissedCallerFeedback(it) }
            "setting.pileupKeyMySide" -> set(value as? Boolean) { p.updateKeyMySide(it) }
            "setting.pileupAutoRecall" -> set(value as? Boolean) { p.updateAutoRecall(it) }
            else -> false
        }
    }
}
