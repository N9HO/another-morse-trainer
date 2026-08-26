package app.anothermorsetrainer

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

    // Sensible defaults that match what the screens used before settings existed.
    var characterWpm by mutableStateOf(18.0)
        private set
    var effectiveWpm by mutableStateOf(18.0)   // Farnsworth target; == character ⇒ standard timing
        private set
    var sidetoneHz by mutableStateOf(600.0)
        private set
    var hapticsEnabled by mutableStateOf(true)
        private set

    // Speak your answer instead of tapping (uses the microphone).
    var voiceAnswersEnabled by mutableStateOf(false)
        private set

    // ---- Practice (drill difficulty / presentation) ----

    /** Most answer choices to ever show (grows with what you've met, up to this). */
    var answerChoices by mutableStateOf(4)
        private set
    /** "Fast enough" recognition-time bar, in seconds — drives mastery/weighting. */
    var recognitionTargetSec by mutableStateOf(1.0)
        private set
    /** How big a pool Common Words draws from (Top-N ranked ham words). */
    var wordCount by mutableStateOf(100)
        private set
    /** When to reveal the correct answer after a response. */
    var revealMode by mutableStateOf(RevealMode.ALWAYS)
        private set
    /** How long a practice session runs before it ends with a summary. */
    var practiceDuration by mutableStateOf(PracticeDuration.UNTIL_STOP)
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
    var reminderHour by mutableStateOf(19)     // 7pm default
        private set
    var reminderMinute by mutableStateOf(0)
        private set

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("amt_settings", Context.MODE_PRIVATE)
        characterWpm = prefs.getFloat("charWpm", 18f).toDouble()
        effectiveWpm = prefs.getFloat("effWpm", 18f).toDouble()
        sidetoneHz = prefs.getFloat("sidetone", 600f).toDouble()
        hapticsEnabled = prefs.getBoolean("haptics", true)
        voiceAnswersEnabled = prefs.getBoolean("voiceAnswers", false)
        answerChoices = prefs.getInt("answerChoices", 4).coerceIn(4, 6)
        recognitionTargetSec = prefs.getFloat("recogTarget", 1.0f).toDouble()
        wordCount = prefs.getInt("wordCount", 100)
        revealMode = runCatching { RevealMode.valueOf(prefs.getString("revealMode", null) ?: "ALWAYS") }
            .getOrDefault(RevealMode.ALWAYS)
        practiceDuration = runCatching { PracticeDuration.valueOf(prefs.getString("practiceDuration", null) ?: "UNTIL_STOP") }
            .getOrDefault(PracticeDuration.UNTIL_STOP)
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
        characterWpm = value.coerceIn(5.0, 40.0)
        if (effectiveWpm > characterWpm) effectiveWpm = characterWpm  // effective can't exceed character speed
        persist()
    }

    fun updateEffectiveWpm(value: Double) {
        effectiveWpm = value.coerceIn(5.0, characterWpm)
        persist()
    }

    fun updateSidetoneHz(value: Double) {
        sidetoneHz = value.coerceIn(300.0, 1000.0)
        persist()
    }

    fun updateHapticsEnabled(value: Boolean) {
        hapticsEnabled = value
        persist()
    }

    fun updateVoiceAnswersEnabled(value: Boolean) {
        voiceAnswersEnabled = value
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
    fun studyOrder(): List<Char> =
        MorseCode.kochOrder + MorseCode.pickablePunctuation.filter { it in punctuationChars }

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
        prefs.edit()
            .putFloat("charWpm", characterWpm.toFloat())
            .putFloat("effWpm", effectiveWpm.toFloat())
            .putFloat("sidetone", sidetoneHz.toFloat())
            .putBoolean("haptics", hapticsEnabled)
            .putBoolean("voiceAnswers", voiceAnswersEnabled)
            .putInt("answerChoices", answerChoices)
            .putFloat("recogTarget", recognitionTargetSec.toFloat())
            .putInt("wordCount", wordCount)
            .putString("revealMode", revealMode.name)
            .putString("practiceDuration", practiceDuration.name)
            .putString("punctuation", punctuationChars.joinToString(""))
            .putString("customWords", customWordsText)
            .putBoolean("useCustomWords", useCustomWords)
            .putString("proficiency", proficiency.name)
            .putBoolean("onboardingDone", onboardingDone)
            .putBoolean("reminders", remindersEnabled)
            .putInt("reminderHour", reminderHour)
            .putInt("reminderMinute", reminderMinute)
            .apply()
    }
}
