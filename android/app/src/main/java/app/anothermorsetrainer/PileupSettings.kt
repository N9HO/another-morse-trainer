package app.anothermorsetrainer

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import app.anothermorsetrainer.morsekit.BustBehavior
import app.anothermorsetrainer.morsekit.CallsignFormat
import app.anothermorsetrainer.morsekit.CutNumbers
import app.anothermorsetrainer.morsekit.MissedCallerFeedback
import app.anothermorsetrainer.morsekit.PileupConfig
import app.anothermorsetrainer.morsekit.QSOContestMode

/**
 * Atmospheric-noise presets for the pileup mix (the [PileupConfig.qrnLevel]
 * gain). Levels and labels are the iOS `QRNLevel` ones; the older Android
 * Light/Medium (0.04/0.09/0.16) installs are mapped onto these on load.
 */
enum class QrnPreset(val level: Float, val label: String) {
    OFF(0f, "Off"),
    NORMAL(0.04f, "Normal"),
    MODERATE(0.10f, "Moderate"),
    HEAVY(0.20f, "Heavy")
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

    /** The station keyed when no call has been entered — the iOS default. */
    const val DEFAULT_CALL = "W1AW"

    /** Slowest and fastest a simulated caller may send. The top matches the
     *  global character-speed ceiling so QRQ practice carries into a pileup
     *  (issue #79). */
    const val MIN_CALLER_WPM = 12.0
    const val MAX_CALLER_WPM = 60.0
    /** Widest pitch spread across callers, in Hz (the iOS slider tops out at 500). */
    const val MAX_TONE_SPREAD = 500.0
    /** Longest "min wait" / "max wait" before a caller answers, in seconds (iOS 0…3 / 0…4). */
    const val MAX_MIN_DELAY = 3.0
    const val MAX_MAX_DELAY = 4.0

    /** Your station callsign, keyed on your side of the QSO (iOS default W1AW; "" falls back to it). */
    var myCall by mutableStateOf(DEFAULT_CALL)
        private set
    var mode by mutableStateOf(QSOContestMode.Pota)
        private set
    var maxStations by mutableIntStateOf(4)
        private set
    var minWpm by mutableDoubleStateOf(18.0)
        private set
    var maxWpm by mutableDoubleStateOf(28.0)
        private set
    /** Callers send with Farnsworth spacing, stretched to your effective speed (iOS qso.farnsworth). */
    var callerFarnsworth by mutableStateOf(false)
        private set
    var toneSpread by mutableDoubleStateOf(250.0)
        private set
    /** Shortest and longest pause before a caller answers, in seconds (iOS minDelay/maxDelay). */
    var minDelay by mutableDoubleStateOf(0.2)
        private set
    var maxDelay by mutableDoubleStateOf(1.5)
        private set
    var qsbEnabled by mutableStateOf(false)
        private set
    var qrn by mutableStateOf(QrnPreset.OFF)
        private set
    var cutNumbersEnabled by mutableStateOf(false)
        private set
    /** Which digits are sent as cut letters when cut numbers are on (iOS cutDigits, default 0 and 9). */
    var cutDigits by mutableStateOf(CutNumbers.commonDefaults)
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
    /** Keep the partial call in the entry box after a typed repeat request ("W1?"). Off by default, as on iOS. */
    var keepPartialCall by mutableStateOf(false)
        private set
    /** Key your own transmissions (CQ, sends, TU) in Morse at the sidetone pitch. */
    var keyMySide by mutableStateOf(true)
        private set
    /** After a logged QSO, the remaining pileup calls again unprompted (iOS #35). */
    var autoRecall by mutableStateOf(true)
        private set

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("amt_pileup", Context.MODE_PRIVATE)
        myCall = prefs.getString("myCall", DEFAULT_CALL) ?: DEFAULT_CALL
        mode = QSOContestMode.allCases.firstOrNull { it.code == prefs.getString("mode", null) }
            ?: QSOContestMode.Pota
        maxStations = prefs.getInt("maxStations", 4).coerceIn(1, 8)
        minWpm = prefs.getFloat("minWpm", 18f).toDouble().coerceIn(MIN_CALLER_WPM, MAX_CALLER_WPM)
        maxWpm = prefs.getFloat("maxWpm", 28f).toDouble().coerceIn(minWpm, MAX_CALLER_WPM)
        callerFarnsworth = prefs.getBoolean("farnsworth", false)
        toneSpread = prefs.getFloat("toneSpread", 250f).toDouble().coerceIn(0.0, MAX_TONE_SPREAD)
        minDelay = prefs.getFloat("minDelay", 0.2f).toDouble().coerceIn(0.0, MAX_MIN_DELAY)
        maxDelay = prefs.getFloat("maxDelay", 1.5f).toDouble().coerceIn(minDelay, MAX_MAX_DELAY)
        qsbEnabled = prefs.getBoolean("qsb", false)
        // The pre-iOS-parity presets were Off/Light/Medium/Heavy at
        // 0/0.04/0.09/0.16; an install still on one of the renamed names lands
        // on the nearest iOS level (0.09 → 0.10, 0.16 → 0.20).
        qrn = when (val stored = prefs.getString("qrn", null) ?: "OFF") {
            "LIGHT" -> QrnPreset.NORMAL
            "MEDIUM" -> QrnPreset.MODERATE
            else -> runCatching { QrnPreset.valueOf(stored) }.getOrDefault(QrnPreset.OFF)
        }
        cutNumbersEnabled = prefs.getBoolean("cutNumbers", false)
        cutDigits = (prefs.getString("cutDigits", null) ?: CutNumbers.commonDefaults.joinToString(""))
            .filter { it in CutNumbers.cuttableDigits }.toSet()
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
        keepPartialCall = prefs.getBoolean("keepPartial", false)
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

    fun updateCallerFarnsworth(value: Boolean) { callerFarnsworth = value; persist() }
    fun updateToneSpread(value: Double) { toneSpread = value.coerceIn(0.0, MAX_TONE_SPREAD); persist() }

    /** Reply-delay edges keep min ≤ max by dragging the other edge along, like the speed band. */
    fun updateMinDelay(value: Double) {
        minDelay = value.coerceIn(0.0, MAX_MIN_DELAY)
        if (maxDelay < minDelay) maxDelay = minDelay
        persist()
    }

    fun updateMaxDelay(value: Double) {
        maxDelay = value.coerceIn(0.0, MAX_MAX_DELAY)
        if (minDelay > maxDelay) minDelay = maxDelay
        persist()
    }

    fun updateQsbEnabled(value: Boolean) { qsbEnabled = value; persist() }
    fun updateQrn(value: QrnPreset) { qrn = value; persist() }
    fun updateCutNumbersEnabled(value: Boolean) { cutNumbersEnabled = value; persist() }

    /** Toggle one digit in or out of the cut set (an empty set is allowed, as on iOS). */
    fun toggleCutDigit(digit: Char) {
        if (digit !in CutNumbers.cuttableDigits) return
        cutDigits = if (digit in cutDigits) cutDigits - digit else cutDigits + digit
        persist()
    }
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
    val effectiveCall: String get() = myCall.ifBlank { DEFAULT_CALL }

    /** The engine config the current settings imply. */
    fun config(): PileupConfig = PileupConfig(
        mode = mode,
        maxStations = maxStations,
        minWPM = minWpm,
        maxWPM = maxWpm,
        toneSpread = toneSpread,
        minDelay = minDelay,
        maxDelay = maxDelay,
        qsbEnabled = qsbEnabled,
        qrnLevel = qrn.level,
        cutNumbersEnabled = cutNumbersEnabled,
        cutDigits = cutDigits,
        rstRequired = rstRequired,
        bustBehavior = bustBehavior,
        giveUpEnabled = giveUpEnabled,
        formats = CallsignFormat.entries.filter { it in formats },
        usOnly = usOnly
    )

    private fun persist() {
        prefs.edit {
            putString("myCall", myCall)
            putString("mode", mode.code)
            putInt("maxStations", maxStations)
            putFloat("minWpm", minWpm.toFloat())
            putFloat("maxWpm", maxWpm.toFloat())
            putBoolean("farnsworth", callerFarnsworth)
            putFloat("toneSpread", toneSpread.toFloat())
            putFloat("minDelay", minDelay.toFloat())
            putFloat("maxDelay", maxDelay.toFloat())
            putBoolean("qsb", qsbEnabled)
            putString("qrn", qrn.name)
            putBoolean("cutNumbers", cutNumbersEnabled)
            putString("cutDigits", cutDigits.joinToString(""))
            putBoolean("rstRequired", rstRequired)
            putString("bust", bustBehavior.code)
            putBoolean("giveUp", giveUpEnabled)
            putString("missedFeedback", missedCallerFeedback.code)
            putStringSet("formats", formats.map { it.code }.toSet())
            putBoolean("usOnly", usOnly)
            putBoolean("keepPartial", keepPartialCall)
            putBoolean("keyMySide", keyMySide)
            putBoolean("autoRecall", autoRecall)
        }
    }
}
