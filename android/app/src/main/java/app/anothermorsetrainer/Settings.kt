package app.anothermorsetrainer

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import app.anothermorsetrainer.morsekit.MorseCode
import app.anothermorsetrainer.morsekit.MorseData
import app.anothermorsetrainer.morsekit.MorseItem
import app.anothermorsetrainer.morsekit.MorseTiming
import app.anothermorsetrainer.morsekit.PhraseQuiz
import app.anothermorsetrainer.morsekit.TrainerEngine

/** When to reveal the correct answer after a response (mirrors iOS RevealMode). */
enum class RevealMode(val label: String, val shortLabel: String) {
    NEVER("Never", "Never"),
    ON_WRONG("Only when wrong", "Wrong"),
    ALWAYS("Always", "Always")
}

/**
 * How long a practice session runs before it stops and shows a summary
 * (mirrors iOS PracticeDuration). `seconds == null` means open-ended.
 */
enum class PracticeDuration(val seconds: Int?, val label: String, val shortLabel: String) {
    ONE_MIN(60, "1 minute", "1m"),
    FIVE_MIN(300, "5 minutes", "5m"),
    TEN_MIN(600, "10 minutes", "10m"),
    FIFTEEN_MIN(900, "15 minutes", "15m"),
    THIRTY_MIN(1800, "30 minutes", "30m"),
    UNTIL_STOP(null, "Until I stop", "∞")
}

/**
 * What the Short Stories (continuous copy) mode sends: bundled fables, a
 * longer serialized tale with a bookmark, or fresh news headlines fetched from
 * a public feed and hidden until revealed (mirrors iOS StoryContent).
 */
enum class StoryContent(val label: String) {
    FABLES("Short stories"),
    SERIALS("Longer stories"),
    NEWS("Todays news")
}

/**
 * A continuous, low-level noise floor played underneath everything (issue #29).
 *
 * Two jobs in one control. The one that prompted it: Bluetooth earbuds power
 * their receiver down during digital silence and wake a moment late, clipping
 * the first character. The other: it is band noise (QRN), so practising against
 * it is more like copying off the air than off a silent tone generator.
 *
 * Amplitudes are against a tone amplitude of 0.9, and are far quieter than the
 * pileup simulator's own QRN — that one is mixed into a burst of loud callers,
 * this one has to sit under a single tone for a whole session. The lowpass
 * leaves the noise peaking at ~0.74 of the figure below, so the top level is
 * capped where tone plus noise still fits under 1.0: a floor that clipped the
 * tone it sits under would be worse than no floor. Mirrors iOS
 * `BackgroundNoiseLevel`.
 */
enum class BackgroundNoiseLevel(val amplitude: Float, val label: String) {
    OFF(0f, "Off"),
    // Keep-alive: non-zero PCM so the Bluetooth sink never sees digital
    // silence, but ~56 dB under the tone — below hearing at any level you would
    // set to copy comfortably. OFF stops the stream outright, which is what
    // lets the link idle, so this is the quietest setting that still does the
    // job issue #29 added it for (N9HO/another-morse-trainer#92).
    KEEP_ALIVE(0.0015f, "Keep-alive"),
    WHISPER(0.010f, "Whisper"),
    LOW(0.025f, "Low"),
    MEDIUM(0.060f, "Moderate"),
    HIGH(0.130f, "Heavy")
}

/** How much Morse the learner already knows — seeds the Koch starting set. */
enum class Proficiency(val label: String) {
    NONE("I know nothing"),
    SOME_LETTERS("I know some of the letters"),
    ALL_LETTERS("I know all the letters"),
    ALL_LETTERS_AND_NUMBERS("I know all the letters and numbers")
}

/**
 * App-wide, persisted preferences. A process-wide singleton (rather than a
 * threaded-through object) so any Composable can read it reactively and any
 * helper — e.g. [Haptics] — can consult it without plumbing.
 *
 * Backed by [SharedPreferences]; each property mirrors a stored key and is also
 * a Compose [mutableStateOf], so writing it both persists and recomposes
 * readers. The iOS app keeps the equivalent in `@AppStorage`/UserDefaults.
 */
object Settings {
    private lateinit var prefs: SharedPreferences

    /** Top of the character-speed range, shared by the setter and the slider. */
    const val MAX_CHARACTER_WPM = 60.0
    /** Bottom of the character-speed range. */
    const val MIN_CHARACTER_WPM = 5.0

    /**
     * 33 WPM, matching iOS. The Koch method depends on characters arriving too
     * fast to count: below about 33 you can tick off the dits and dahs and
     * assemble the letter, which is the habit the whole method exists to avoid,
     * and it caps you around 10 WPM later. A learner who needs more time should
     * widen the gaps (Farnsworth) or raise the recognition target, not slow the
     * characters — see the warning under the speed slider.
     *
     * Existing installs are unaffected: [completeOnboarding] persists, and
     * [persist] always writes charWpm, so anyone past the first-run screen has
     * an explicit stored value and never falls back to this.
     */
    var characterWpm by mutableDoubleStateOf(33.0)
        private set
    /** Equal to the character speed ⇒ standard timing, i.e. Farnsworth off, as on iOS. */
    var effectiveWpm by mutableDoubleStateOf(33.0)
        private set
    var sidetoneHz by mutableDoubleStateOf(600.0)
        private set

    /**
     * Continuous background noise under everything (issue #29). Defaults to
     * [BackgroundNoiseLevel.KEEP_ALIVE]: the Bluetooth clipping it prevents is
     * a silent accuracy tax nobody would think to go looking for a setting
     * about, and at ~56 dB under the tone that floor does the job without
     * anyone hearing it. The old WHISPER default sat ~39 dB under the tone,
     * which was audible enough to be reported as too loud
     * (N9HO/another-morse-trainer#92). Louder levels remain for people who want
     * band noise to copy through.
     */
    var backgroundNoise by mutableStateOf(BackgroundNoiseLevel.KEEP_ALIVE)
        private set
    var hapticsEnabled by mutableStateOf(true)
        private set

    // Speak your answer instead of tapping (uses the microphone).
    var voiceAnswersEnabled by mutableStateOf(false)
        private set

    // Key your answer instead of tapping, in drills where the heard text is the
    // answer (Characters, Common Words, Confusion). Toggled in the quiz itself.
    var answerByKeying by mutableStateOf(false)
        private set

    // ---- Practice (drill difficulty / presentation) ----

    /** Most answer choices to ever show (grows with what you've met, up to this). */
    var answerChoices by mutableIntStateOf(4)
        private set
    /** "Fast enough" recognition-time bar, in seconds — drives mastery/weighting. */
    var recognitionTargetSec by mutableDoubleStateOf(1.0)
        private set
    /** How big a pool Common Words draws from (Top-N ranked ham words). */
    var wordCount by mutableIntStateOf(100)
        private set
    /** When to reveal the correct answer after a response. */
    var revealMode by mutableStateOf(RevealMode.ALWAYS)
        private set
    /** How long a practice session runs before it ends with a summary. */
    var practiceDuration by mutableStateOf(PracticeDuration.UNTIL_STOP)
        private set

    /** Show the digit 0 with a slash through it wherever copy text is displayed
     *  (the operator's handwriting convention — issue #62). */
    var slashedZero by mutableStateOf(true)
        private set

    // ---- Short Stories (fables / serials / news) ----

    /** What the Short Stories mode sends. */
    var storyContent by mutableStateOf(StoryContent.FABLES)
        private set
    /** Which long tale to serialize (a MorseSerials id; empty = the first). */
    var storySerialId by mutableStateOf("")
        private set
    /** Which feed to pull headlines from when the content is NEWS. */
    var newsSource by mutableStateOf(NewsSource.HAM_RADIO)
        private set
    /** Send the item's summary after the headline (separated by a BT break). */
    var newsFullStory by mutableStateOf(true)
        private set
    /** Story bookmarks: how far you got, per shelf ("fables") or serial id. */
    private var storyBookmarks by mutableStateOf(mapOf<String, Int>())

    /** Head Copy: replay the item every few seconds until revealed. */
    var headCopyAutoRepeat by mutableStateOf(false)
        private set
    /** Head Copy: reveal automatically this many seconds after the tone (0 = manual). */
    var headCopyRevealSec by mutableIntStateOf(0)
        private set

    /**
     * Punctuation opted into the study ladder (a subset of
     * [MorseCode.pickablePunctuation]). Joins the Koch order at the end, so
     * it's introduced after the core letters and numbers.
     */
    var punctuationChars by mutableStateOf(emptySet<Char>())
        private set

    /** The learner's own word list, raw as typed (one word per line or space-separated). */
    var customWordsText by mutableStateOf("")
        private set
    /** Whether Common Words drills draw from the custom list instead of the ranked pool. */
    var useCustomWords by mutableStateOf(false)
        private set

    /** How much the learner already knows — seeds the Characters Koch ladder. */
    var proficiency by mutableStateOf(Proficiency.NONE)
        private set
    /** False until the first-run onboarding (comfort-level pick) is completed. */
    var onboardingDone by mutableStateOf(false)
        private set

    // Daily practice reminder (a notification to keep the streak alive).
    var remindersEnabled by mutableStateOf(false)
        private set
    var reminderHour by mutableIntStateOf(19)     // 7pm default
        private set
    var reminderMinute by mutableIntStateOf(0)
        private set

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("amt_settings", Context.MODE_PRIVATE)
        characterWpm = prefs.getFloat("charWpm", 33f).toDouble()
        effectiveWpm = prefs.getFloat("effWpm", 33f).toDouble()
        sidetoneHz = prefs.getFloat("sidetone", 600f).toDouble()
        backgroundNoise = runCatching {
            BackgroundNoiseLevel.valueOf(prefs.getString("backgroundNoise", null) ?: "KEEP_ALIVE")
        }.getOrDefault(BackgroundNoiseLevel.KEEP_ALIVE)
        // One-time move off the old Whisper default (issue #92). Whisper was
        // the shipped default, so almost everyone sitting on it never chose it
        // — they just got the loudest thing that was ever the default. Drop
        // those installs to the inaudible keep-alive floor, which does the same
        // job for the link. Guarded by a flag so anyone who deliberately picks
        // Whisper afterwards keeps it.
        if (!prefs.getBoolean("noiseFloorMigrated", false)) {
            if (backgroundNoise == BackgroundNoiseLevel.WHISPER) {
                backgroundNoise = BackgroundNoiseLevel.KEEP_ALIVE
            }
            prefs.edit { putBoolean("noiseFloorMigrated", true) }
        }
        hapticsEnabled = prefs.getBoolean("haptics", true)
        voiceAnswersEnabled = prefs.getBoolean("voiceAnswers", false)
        answerByKeying = prefs.getBoolean("answerByKeying", false)
        answerChoices = prefs.getInt("answerChoices", 4).coerceIn(4, 6)
        recognitionTargetSec = prefs.getFloat("recogTarget", 1.0f).toDouble()
        wordCount = prefs.getInt("wordCount", 100)
        revealMode = runCatching { RevealMode.valueOf(prefs.getString("revealMode", null) ?: "ALWAYS") }
            .getOrDefault(RevealMode.ALWAYS)
        practiceDuration = runCatching { PracticeDuration.valueOf(prefs.getString("practiceDuration", null) ?: "UNTIL_STOP") }
            .getOrDefault(PracticeDuration.UNTIL_STOP)
        slashedZero = prefs.getBoolean("slashedZero", true)
        storyContent = runCatching { StoryContent.valueOf(prefs.getString("storyContent", null) ?: "FABLES") }
            .getOrDefault(StoryContent.FABLES)
        storySerialId = prefs.getString("storySerialId", "") ?: ""
        newsSource = runCatching { NewsSource.valueOf(prefs.getString("newsSource", null) ?: "HAM_RADIO") }
            .getOrDefault(NewsSource.HAM_RADIO)
        newsFullStory = prefs.getBoolean("newsFullStory", true)
        storyBookmarks = decodeBookmarks(prefs.getString("storyBookmarks", "") ?: "")
        headCopyAutoRepeat = prefs.getBoolean("hcAutoRepeat", false)
        headCopyRevealSec = prefs.getInt("hcRevealSec", 0).coerceIn(0, 10)
        punctuationChars = (prefs.getString("punctuation", "") ?: "")
            .toSet().filter { it in MorseCode.pickablePunctuation }.toSet()
        customWordsText = prefs.getString("customWords", "") ?: ""
        useCustomWords = prefs.getBoolean("useCustomWords", false)
        proficiency = runCatching { Proficiency.valueOf(prefs.getString("proficiency", null) ?: "NONE") }
            .getOrDefault(Proficiency.NONE)
        onboardingDone = prefs.getBoolean("onboardingDone", false)
        remindersEnabled = prefs.getBoolean("reminders", false)
        reminderHour = prefs.getInt("reminderHour", 19)
        reminderMinute = prefs.getInt("reminderMinute", 0)
    }

    /** The playback timing implied by the speed settings (Farnsworth when effective < character). */
    fun timing(): MorseTiming =
        if (effectiveWpm < characterWpm) MorseTiming.farnsworth(characterWpm, effectiveWpm)
        else MorseTiming(characterWpm)

    /** Engine config carrying the user's speed, recognition target, and choice count. */
    fun engineConfig(wpm: Double = characterWpm): TrainerEngine.Config =
        TrainerEngine.Config(wpm = wpm, ttrThreshold = recognitionTargetSec, optionCount = answerChoices)

    /** Phrase-quiz config carrying the user's recognition target and choice count. */
    fun phraseConfig(): PhraseQuiz.Config =
        PhraseQuiz.Config(ttrThreshold = recognitionTargetSec, optionCount = answerChoices)

    fun updateCharacterWpm(value: Double) {
        // 60 WPM ceiling: QRQ operators work well past 40 and asked to practise
        // there (issue #79). Matches the iOS/iPadOS/macOS slider.
        characterWpm = value.coerceIn(MIN_CHARACTER_WPM, MAX_CHARACTER_WPM)
        if (effectiveWpm > characterWpm) effectiveWpm = characterWpm  // effective can't exceed character speed
        persist()
    }

    fun updateEffectiveWpm(value: Double) {
        effectiveWpm = value.coerceIn(MIN_CHARACTER_WPM, characterWpm)
        persist()
    }

    fun updateSidetoneHz(value: Double) {
        sidetoneHz = value.coerceIn(300.0, 1000.0)
        persist()
    }

    fun updateBackgroundNoise(value: BackgroundNoiseLevel) {
        backgroundNoise = value
        persist()
        BackgroundNoise.refresh()   // the floor is live; retarget it now
    }

    fun updateHapticsEnabled(value: Boolean) {
        hapticsEnabled = value
        persist()
    }

    fun updateVoiceAnswersEnabled(value: Boolean) {
        voiceAnswersEnabled = value
        persist()
    }

    fun updateAnswerByKeying(value: Boolean) {
        answerByKeying = value
        persist()
    }

    fun updateAnswerChoices(value: Int) {
        answerChoices = value.coerceIn(4, 6)
        persist()
    }

    fun updateRecognitionTargetSec(value: Double) {
        recognitionTargetSec = value.coerceIn(0.5, 2.5)
        persist()
    }

    fun updateWordCount(value: Int) {
        wordCount = value
        persist()
    }

    fun updateRevealMode(value: RevealMode) {
        revealMode = value
        persist()
    }

    fun updatePracticeDuration(value: PracticeDuration) {
        practiceDuration = value
        persist()
    }

    fun updateSlashedZero(value: Boolean) {
        slashedZero = value
        persist()
    }

    fun updateStoryContent(value: StoryContent) {
        storyContent = value
        persist()
    }

    fun updateStorySerialId(value: String) {
        storySerialId = value
        persist()
    }

    fun updateNewsSource(value: NewsSource) {
        newsSource = value
        persist()
    }

    fun updateNewsFullStory(value: Boolean) {
        newsFullStory = value
        persist()
    }

    /** Where the given story shelf/serial was left off (0 when never opened). */
    fun storyBookmark(key: String): Int = storyBookmarks[key] ?: 0

    /** Remember how far the listener got on a shelf/serial. */
    fun setStoryBookmark(key: String, index: Int) {
        if (storyBookmarks[key] == index) return
        storyBookmarks = storyBookmarks + (key to index)
        persist()
    }

    // Bookmarks persist as "key=index" pairs joined with '|' — ids are slugs
    // ("fables", "speckled-band"), so neither separator can occur in a key.
    private fun decodeBookmarks(raw: String): Map<String, Int> =
        raw.split('|').mapNotNull { entry ->
            val eq = entry.lastIndexOf('=')
            if (eq <= 0) return@mapNotNull null
            val idx = entry.substring(eq + 1).toIntOrNull() ?: return@mapNotNull null
            entry.substring(0, eq) to idx
        }.toMap()

    private fun encodeBookmarks(map: Map<String, Int>): String =
        map.entries.joinToString("|") { "${it.key}=${it.value}" }

    fun updateHeadCopyAutoRepeat(value: Boolean) {
        headCopyAutoRepeat = value
        persist()
    }

    fun updateHeadCopyRevealSec(value: Int) {
        headCopyRevealSec = value.coerceIn(0, 10)
        persist()
    }

    fun updateProficiency(value: Proficiency) {
        proficiency = value
        persist()
    }

    /** Toggle one punctuation character in or out of the study ladder. */
    fun togglePunctuation(ch: Char) {
        if (ch !in MorseCode.pickablePunctuation) return
        punctuationChars = if (ch in punctuationChars) punctuationChars - ch else punctuationChars + ch
        persist()
    }

    /** The ladder's introduction order: the Koch core plus opted-in punctuation at the end. */
    fun studyOrder(): List<Char> = MorseCode.studyOrder(punctuationChars)

    fun updateCustomWordsText(value: String) {
        customWordsText = value.take(4000)
        persist()
    }

    fun updateUseCustomWords(value: Boolean) {
        useCustomWords = value
        persist()
    }

    /**
     * The parsed custom pool: split on whitespace/commas, uppercased, filtered
     * to sendable characters, deduplicated, in entry order.
     */
    val customWords: List<String>
        get() {
            val seen = LinkedHashSet<String>()
            for (raw in customWordsText.uppercase().split('\n', '\r', ' ', ',', ';', '\t')) {
                val w = raw.filter { MorseCode.pattern(it) != null }.take(24)
                if (w.isNotEmpty()) seen.add(w)
            }
            return seen.toList()
        }

    /**
     * The Common Words pool: the learner's own list when enabled and big
     * enough to offer a distractor, otherwise the ranked Top-N ham words.
     */
    fun wordPoolItems(): List<MorseItem> {
        val custom = customWords
        return if (useCustomWords && custom.size >= 2) MorseData.customWordItems(custom)
        else MorseData.topWordItems(wordCount)
    }

    /** Mark first-run onboarding complete (also records the chosen level). */
    fun completeOnboarding(level: Proficiency) {
        proficiency = level
        onboardingDone = true
        persist()
    }

    /** The Koch starting characters implied by the chosen [proficiency]. */
    fun seedCharacters(): List<Char> = when (proficiency) {
        Proficiency.NONE -> MorseCode.kochOrder.take(2)
        Proficiency.SOME_LETTERS -> MorseCode.kochOrder.filter { it.isLetter() }.take(13)
        Proficiency.ALL_LETTERS -> MorseCode.kochOrder.filter { it.isLetter() }
        Proficiency.ALL_LETTERS_AND_NUMBERS -> MorseCode.kochOrder
    }

    /**
     * Seed an engine's active/met characters from the chosen proficiency. A
     * declared level front-loads its characters as "already met" (full choice
     * grid right away); a true beginner starts from a single option and builds up.
     */
    fun applyProficiency(engine: TrainerEngine) {
        val chars = seedCharacters()
        engine.setActiveCharacters(chars)
        engine.setExposedCharacters(if (proficiency == Proficiency.NONE) emptyList() else chars)
    }

    fun updateRemindersEnabled(value: Boolean) {
        remindersEnabled = value
        persist()
    }

    fun updateReminderTime(hour: Int, minute: Int) {
        reminderHour = hour.coerceIn(0, 23)
        reminderMinute = minute.coerceIn(0, 59)
        persist()
    }

    private fun persist() {
        prefs.edit {
            putFloat("charWpm", characterWpm.toFloat())
            putFloat("effWpm", effectiveWpm.toFloat())
            putFloat("sidetone", sidetoneHz.toFloat())
            putString("backgroundNoise", backgroundNoise.name)
            putBoolean("haptics", hapticsEnabled)
            putBoolean("voiceAnswers", voiceAnswersEnabled)
            putBoolean("answerByKeying", answerByKeying)
            putInt("answerChoices", answerChoices)
            putFloat("recogTarget", recognitionTargetSec.toFloat())
            putInt("wordCount", wordCount)
            putString("revealMode", revealMode.name)
            putString("practiceDuration", practiceDuration.name)
            putBoolean("slashedZero", slashedZero)
            putString("storyContent", storyContent.name)
            putString("storySerialId", storySerialId)
            putString("newsSource", newsSource.name)
            putBoolean("newsFullStory", newsFullStory)
            putString("storyBookmarks", encodeBookmarks(storyBookmarks))
            putBoolean("hcAutoRepeat", headCopyAutoRepeat)
            putInt("hcRevealSec", headCopyRevealSec)
            putString("punctuation", punctuationChars.joinToString(""))
            putString("customWords", customWordsText)
            putBoolean("useCustomWords", useCustomWords)
            putString("proficiency", proficiency.name)
            putBoolean("onboardingDone", onboardingDone)
            putBoolean("reminders", remindersEnabled)
            putInt("reminderHour", reminderHour)
            putInt("reminderMinute", reminderMinute)
        }
    }
}
