package app.anothermorsetrainer

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.anothermorsetrainer.morsekit.BustBehavior
import app.anothermorsetrainer.morsekit.CallsignFormat
import app.anothermorsetrainer.morsekit.MissedCallerFeedback
import app.anothermorsetrainer.morsekit.PileupConfig
import app.anothermorsetrainer.morsekit.QSOContestMode

/** Atmospheric-noise presets for the pileup mix (the [PileupConfig.qrnLevel] gain). */
enum class QrnPreset(val level: Float, val label: String) {
    OFF(0f, "Off"),
    LIGHT(0.04f, "Light"),
    MEDIUM(0.09f, "Medium"),
    HEAVY(0.16f, "Heavy")
}

/**
 * Persisted configuration for the Pileup Runner (the iOS QSO Simulator surface):
 * your callsign, the exchange flavour, and the realism knobs that feed
 * [PileupConfig]. Kept apart from [Settings] so the QSO sim's many options don't
 * crowd the app-wide preferences; same pattern — each property is both persisted
 * and a Compose state, so the setup screen recomposes as it edits.
 */
object PileupSettings {
    private lateinit var prefs: SharedPreferences

    /** Slowest and fastest a simulated caller may send. The top matches the
     *  global character-speed ceiling so QRQ practice carries into a pileup
     *  (issue #79). */
    const val MIN_CALLER_WPM = 10.0
    const val MAX_CALLER_WPM = 60.0

    /** Your station callsign, keyed on your side of the QSO ("" falls back to N0CALL). */
    var myCall by mutableStateOf("")
        private set
    var mode by mutableStateOf(QSOContestMode.Pota)
        private set
    var maxStations by mutableStateOf(4)
        private set
    var minWpm by mutableStateOf(18.0)
        private set
    var maxWpm by mutableStateOf(28.0)
        private set
    var toneSpread by mutableStateOf(250.0)
        private set
    var qsbEnabled by mutableStateOf(false)
        private set
    var qrn by mutableStateOf(QrnPreset.OFF)
        private set
    var cutNumbersEnabled by mutableStateOf(false)
        private set
    var rstRequired by mutableStateOf(false)
        private set
    var bustBehavior by mutableStateOf(BustBehavior.Forgiving)
        private set
    var giveUpEnabled by mutableStateOf(false)
        private set
    /** Whether — and when — to say who gave up on you, and what you had them as. */
    var missedCallerFeedback by mutableStateOf(MissedCallerFeedback.EndOfRun)
        private set
    var formats by mutableStateOf(CallsignFormat.commonDefaults.toSet())
        private set
    var usOnly by mutableStateOf(true)
        private set
    /** Keep the partial call in the entry box after a typed repeat request ("W1?"). */
    var keepPartialCall by mutableStateOf(true)
        private set
    /** Key your own transmissions (CQ, sends, TU) in Morse at the sidetone pitch. */
    var keyMySide by mutableStateOf(true)
        private set
    /** After a logged QSO, the remaining pileup calls again unprompted (iOS #35). */
    var autoRecall by mutableStateOf(true)
        private set

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("amt_pileup", Context.MODE_PRIVATE)
        myCall = prefs.getString("myCall", "") ?: ""
        mode = QSOContestMode.allCases.firstOrNull { it.code == prefs.getString("mode", null) }
            ?: QSOContestMode.Pota
        maxStations = prefs.getInt("maxStations", 4).coerceIn(1, 8)
        minWpm = prefs.getFloat("minWpm", 18f).toDouble()
        maxWpm = prefs.getFloat("maxWpm", 28f).toDouble()
        toneSpread = prefs.getFloat("toneSpread", 250f).toDouble()
        qsbEnabled = prefs.getBoolean("qsb", false)
        qrn = runCatching { QrnPreset.valueOf(prefs.getString("qrn", null) ?: "OFF") }
            .getOrDefault(QrnPreset.OFF)
        cutNumbersEnabled = prefs.getBoolean("cutNumbers", false)
        rstRequired = prefs.getBoolean("rstRequired", false)
        bustBehavior = BustBehavior.allCases.firstOrNull { it.code == prefs.getString("bust", null) }
            ?: BustBehavior.Forgiving
        giveUpEnabled = prefs.getBoolean("giveUp", false)
        missedCallerFeedback = MissedCallerFeedback.allCases
            .firstOrNull { it.code == prefs.getString("missedFeedback", null) }
            ?: MissedCallerFeedback.EndOfRun
        val stored = prefs.getStringSet("formats", null)
        if (stored != null) {
            formats = CallsignFormat.entries.filter { it.code in stored }.toSet()
                .ifEmpty { CallsignFormat.commonDefaults.toSet() }
        }
        usOnly = prefs.getBoolean("usOnly", true)
        keepPartialCall = prefs.getBoolean("keepPartial", true)
        keyMySide = prefs.getBoolean("keyMySide", true)
        autoRecall = prefs.getBoolean("autoRecall", true)
    }

    fun updateMyCall(value: String) {
        myCall = value.uppercase().filter { it.isLetterOrDigit() || it == '/' }.take(12)
        persist()
    }

    fun updateMode(value: QSOContestMode) { mode = value; persist() }

    fun updateMaxStations(value: Int) { maxStations = value.coerceIn(1, 8); persist() }

    /** Speed band edges keep min ≤ max by dragging the other edge along. */
    fun updateMinWpm(value: Double) {
        minWpm = value.coerceIn(MIN_CALLER_WPM, MAX_CALLER_WPM)
        if (maxWpm < minWpm) maxWpm = minWpm
        persist()
    }

    fun updateMaxWpm(value: Double) {
        maxWpm = value.coerceIn(MIN_CALLER_WPM, MAX_CALLER_WPM)
        if (minWpm > maxWpm) minWpm = maxWpm
        persist()
    }

    fun updateToneSpread(value: Double) { toneSpread = value.coerceIn(0.0, 400.0); persist() }
    fun updateQsbEnabled(value: Boolean) { qsbEnabled = value; persist() }
    fun updateQrn(value: QrnPreset) { qrn = value; persist() }
    fun updateCutNumbersEnabled(value: Boolean) { cutNumbersEnabled = value; persist() }
    fun updateRstRequired(value: Boolean) { rstRequired = value; persist() }
    fun updateBustBehavior(value: BustBehavior) { bustBehavior = value; persist() }
    fun updateGiveUpEnabled(value: Boolean) { giveUpEnabled = value; persist() }
    fun updateMissedCallerFeedback(value: MissedCallerFeedback) { missedCallerFeedback = value; persist() }

    fun toggleFormat(format: CallsignFormat) {
        val next = if (format in formats) formats - format else formats + format
        // At least one shape stays on, or the generator would have nothing to build.
        if (next.isNotEmpty()) { formats = next; persist() }
    }

    fun updateUsOnly(value: Boolean) { usOnly = value; persist() }
    fun updateKeepPartialCall(value: Boolean) { keepPartialCall = value; persist() }
    fun updateKeyMySide(value: Boolean) { keyMySide = value; persist() }
    fun updateAutoRecall(value: Boolean) { autoRecall = value; persist() }

    /** The callsign to key on-air, never blank. */
    val effectiveCall: String get() = myCall.ifBlank { "N0CALL" }

    /** The engine config the current settings imply. */
    fun config(): PileupConfig = PileupConfig(
        mode = mode,
        maxStations = maxStations,
        minWPM = minWpm,
        maxWPM = maxWpm,
        toneSpread = toneSpread,
        qsbEnabled = qsbEnabled,
        qrnLevel = qrn.level,
        cutNumbersEnabled = cutNumbersEnabled,
        rstRequired = rstRequired,
        bustBehavior = bustBehavior,
        giveUpEnabled = giveUpEnabled,
        formats = CallsignFormat.entries.filter { it in formats },
        usOnly = usOnly
    )

    private fun persist() {
        prefs.edit()
            .putString("myCall", myCall)
            .putString("mode", mode.code)
            .putInt("maxStations", maxStations)
            .putFloat("minWpm", minWpm.toFloat())
            .putFloat("maxWpm", maxWpm.toFloat())
            .putFloat("toneSpread", toneSpread.toFloat())
            .putBoolean("qsb", qsbEnabled)
            .putString("qrn", qrn.name)
            .putBoolean("cutNumbers", cutNumbersEnabled)
            .putBoolean("rstRequired", rstRequired)
            .putString("bust", bustBehavior.code)
            .putBoolean("giveUp", giveUpEnabled)
            .putString("missedFeedback", missedCallerFeedback.code)
            .putStringSet("formats", formats.map { it.code }.toSet())
            .putBoolean("usOnly", usOnly)
            .putBoolean("keepPartial", keepPartialCall)
            .putBoolean("keyMySide", keyMySide)
            .putBoolean("autoRecall", autoRecall)
            .apply()
    }
}
