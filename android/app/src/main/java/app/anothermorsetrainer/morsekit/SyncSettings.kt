package app.anothermorsetrainer.morsekit

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToLong

/**
 * Settings sync's pure half: the training preferences all three apps share,
 * one `PUT /v1/sync/state` key each (`setting.<name>`), with one wire shape,
 * a default and a normalising rule per key. The app maps [app.anothermorsetrainer.Settings]
 * and [app.anothermorsetrainer.PileupSettings] to and from these values
 * (`SettingsSync`); the engine normalises everything it sends and receives
 * here, so a value from another platform is clamped into this app's range or
 * ignored, never trusted. The iOS twin is `SyncSettings.swift`.
 *
 * Contract: the accounts Worker's README, section 9 "Settings keys";
 * `fixtures/sync-wire.json` `settings` pins every key, shape, default and
 * rule. Wire values are JSON as org.json holds it: Boolean, Int, Double,
 * String, [JSONArray], [JSONObject]. A number read from JSON may be any
 * [Number] (the JVM org.json reads decimals as BigDecimal), so every rule
 * reads numbers through [Number.toDouble].
 */
object SyncSettings {
    const val PREFIX = "setting."

    /** One setting's wire shape (fixture `settings.keys[].kind`). */
    sealed class Kind {
        data object Bool : Kind()
        /** Any number, rounded to a whole number, then clamped. */
        data class IntRange(val min: Int, val max: Int) : Kind()
        /** Clamped, then rounded to one decimal place (the fixture's step 0.1). */
        data class Tenths(val min: Double, val max: Double) : Kind()
        data class Enum(val values: List<String>) : Kind()
        /** A number, rounded, that must be one of [values]. */
        data class IntChoice(val values: List<Int>) : Kind()
        /** Strings from [values]: unknown and repeated ones dropped, in [values] order. */
        data class Members(val values: List<String>, val minCount: Int) : Kind()
        data class Text(val maxLength: Int, val transform: String) : Kind()
        data class Words(val maxCount: Int, val maxLength: Int) : Kind()
        /** `{ min, max }`, each clamped (whole numbers or tenths), then max raised to min. */
        data class Range(val min: ClosedFloatingPointRange<Double>, val max: ClosedFloatingPointRange<Double>, val whole: Boolean) : Kind()
        /** `{ characterWpm 15..60, farnsworth, effectiveWpm 8..60 }`, effective lowered to character. */
        data object Speed : Kind()
    }

    class Spec(val key: String, val kind: Kind, val default: Any)

    private val FORMATS = CallsignFormat.entries.map { it.code }
    private fun formatDefaults() = JSONArray(CallsignFormat.commonDefaults.map { it.code })

    private fun spec(name: String, kind: Kind, default: Any) = Spec(PREFIX + name, kind, default)

    /** Every settings key, in the fixture's order. */
    val specs: List<Spec> = listOf(
        spec("speed", Kind.Speed, JSONObject().put("characterWpm", 33).put("farnsworth", false).put("effectiveWpm", 18)),
        spec("tonePitch", Kind.IntRange(300, 1000), 601),
        spec("bandNoise", Kind.Enum(listOf("off", "whisper", "low", "medium", "high")), "off"),
        spec("practiceDuration", Kind.Enum(listOf("oneMin", "fiveMin", "tenMin", "fifteenMin", "thirtyMin", "untilStop")), "fiveMin"),
        spec("recognitionTarget", Kind.Tenths(0.5, 3.0), 1.0),
        spec("answerChoices", Kind.IntRange(4, 6), 4),
        spec("answerEntry", Kind.Enum(AnswerEntryMode.entries.map { it.id }), AnswerEntryMode.CHOICES.id),
        spec("reveal", Kind.Enum(listOf("never", "onWrong", "always")), "onWrong"),
        spec("showCorrectness", Kind.Bool, true),
        spec("allowReplay", Kind.Bool, false),
        spec("slashedZero", Kind.Bool, true),
        spec("introduceNewCharacters", Kind.Bool, true),
        spec("punctuation", Kind.Members(MorseCode.pickablePunctuation.map { it.toString() }, 0), JSONArray()),
        spec("journeyDrainOnMiss", Kind.Bool, true),
        spec("wordPool", Kind.Enum(listOf("top100", "top300", "top500", "top1000", "cw77")), "top100"),
        spec("useCustomWords", Kind.Bool, false),
        spec("customWords", Kind.Words(300, 24), JSONArray()),
        spec("cw77IncludeMe", Kind.Bool, false),
        spec("cw77Style", Kind.Enum(Cw77Style.entries.map { it.id }), Cw77Style.DEFAULT.id),
        spec("listenContent", Kind.Enum(listOf("characters", "qsoTop20", "qsoTop100", "cw77", "words", "abbreviations")), "characters"),
        spec("listenGap", Kind.Enum(listOf("standard", "rapidFire", "warp", "icr")), "standard"),
        spec("listenReadback", Kind.Enum(listOf("spelled", "meaningOnly")), "spelled"),
        spec("headCopyRepeats", Kind.IntRange(0, 3), 2),
        spec("headCopyRevealSeconds", Kind.IntRange(0, 10), 5),
        spec("qrqSpeed", Kind.IntChoice(listOf(35, 40, 50, 60)), 35),
        spec("examSpeed", Kind.Enum(ExamSpeed.entries.map { it.code }), ExamSpeed.GENERAL13.code),
        spec("examUseBundled", Kind.Bool, false),
        spec("storyContent", Kind.Enum(listOf("fables", "serials", "news")), "fables"),
        spec("storySerialId", Kind.Text(64, "id"), ""),
        spec("newsSource", Kind.Enum(listOf("hamRadio", "world", "topStories", "technology")), "hamRadio"),
        spec("newsFullStory", Kind.Bool, true),
        spec("contestType", Kind.Enum(ContestType.entries.map { it.code }), ContestType.Sst.code),
        spec("contestLength", Kind.Enum(ContestLength.entries.map { it.code }), ContestLength.TenMin.code),
        spec("rapidFireContent", Kind.Enum(listOf("callsigns", "words", "numbers", "states", "serials", "names", "power", "mixed")), "callsigns"),
        spec("rapidFireResponse", Kind.Enum(listOf("type", "headCopy", "key", "review")), "type"),
        spec("rapidFirePace", Kind.Enum(listOf("relaxed", "steady", "brisk", "blazing")), "steady"),
        spec("rapidFireWordLength", Kind.Range(2.0..12.0, 2.0..12.0, true), JSONObject().put("min", 3).put("max", 6)),
        spec("rapidFireNumberCount", Kind.IntRange(1, 10), 5),
        spec("rapidFireUsOnly", Kind.Bool, true),
        spec("rapidFireFormats", Kind.Members(FORMATS, 1), formatDefaults()),
        spec("rapidFireStatesSections", Kind.Bool, false),
        spec("rapidFireSerialCut", Kind.Bool, false),
        spec("myCall", Kind.Text(12, "callsign"), "W1AW"),
        spec("myName", Kind.Text(32, "trim"), ""),
        spec("myState", Kind.Text(3, "state"), ""),
        spec("pileupMode", Kind.Enum(QSOContestMode.entries.map { it.code }), QSOContestMode.Pota.code),
        spec("pileupMaxStations", Kind.IntRange(1, 8), 4),
        spec("pileupCallerSpeed", Kind.Range(12.0..60.0, 12.0..60.0, true), JSONObject().put("min", 18).put("max", 28)),
        spec("pileupCallerFarnsworth", Kind.Bool, false),
        spec("pileupToneSpread", Kind.IntRange(0, 500), 250),
        spec("pileupDelay", Kind.Range(0.0..3.0, 0.0..4.0, false), JSONObject().put("min", 0.2).put("max", 1.5)),
        spec("pileupQsb", Kind.Bool, false),
        spec("pileupQrn", Kind.Enum(listOf("off", "normal", "moderate", "heavy")), "off"),
        spec("pileupCutNumbers", Kind.Bool, false),
        spec("pileupCutDigits", Kind.Members(CutNumbers.cuttableDigits.map { it.toString() }, 0),
            JSONArray(CutNumbers.cuttableDigits.filter { it in CutNumbers.commonDefaults }.map { it.toString() })),
        spec("pileupRstRequired", Kind.Bool, false),
        spec("pileupBust", Kind.Enum(BustBehavior.entries.map { it.code }), BustBehavior.Forgiving.code),
        spec("pileupGiveUp", Kind.Bool, false),
        spec("pileupFormats", Kind.Members(FORMATS, 1), formatDefaults()),
        spec("pileupUsOnly", Kind.Bool, true),
        spec("pileupKeepPartialCall", Kind.Bool, false),
        spec("pileupMissedCallerFeedback", Kind.Enum(MissedCallerFeedback.entries.map { it.code }), MissedCallerFeedback.EndOfRun.code),
        spec("pileupKeyMySide", Kind.Bool, true),
        spec("pileupAutoRecall", Kind.Bool, true),
    )

    val KEYS: List<String> = specs.map { it.key }
    private val byKey: Map<String, Spec> = specs.associateBy { it.key }

    fun isSetting(key: String): Boolean = key.startsWith(PREFIX)

    fun spec(key: String): Spec? = byKey[key]

    /** The key's default, in its wire shape. */
    fun defaultValue(key: String): Any? = byKey[key]?.default

    /**
     * [value] in its normalised wire form, or null when it is unusable or the
     * key is not a setting this app knows (fixture `settings.normalize`).
     */
    fun normalize(key: String, value: Any?): Any? {
        val spec = byKey[key] ?: return null
        return normalize(spec.kind, value)
    }

    /** Whether [value], normalised, is the key's default (fixture `settings.isDefault`). */
    fun isDefault(key: String, value: Any?): Boolean {
        val spec = byKey[key] ?: return false
        val normal = normalize(spec.kind, value) ?: return false
        return jsonEquals(normal, spec.default)
    }

    // ---- Rules ----

    private fun number(v: Any?): Double? = (v as? Number)?.toDouble()?.takeIf { it.isFinite() }

    /** Half away from zero, as the Swift port's `rounded()`. */
    private fun whole(x: Double): Int {
        val r = if (x < 0) -Math.round(-x).toDouble() else Math.round(x).toDouble()
        return r.coerceIn(Int.MIN_VALUE.toDouble(), Int.MAX_VALUE.toDouble()).toInt()
    }

    /** One decimal place: round(x * 10) / 10. */
    private fun tenths(x: Double): Double = (x * 10).roundToLong() / 10.0

    fun normalize(kind: Kind, v: Any?): Any? = when (kind) {
        Kind.Bool -> v as? Boolean
        is Kind.IntRange -> number(v)?.let { whole(it).coerceIn(kind.min, kind.max) }
        is Kind.Tenths -> number(v)?.let { tenths(it.coerceIn(kind.min, kind.max)) }
        is Kind.Enum -> (v as? String)?.takeIf { it in kind.values }
        is Kind.IntChoice -> number(v)?.let { whole(it) }?.takeIf { it in kind.values }
        is Kind.Members -> (v as? JSONArray)?.let { a ->
            val present = (0 until a.length()).mapNotNull { a.opt(it) as? String }.toSet()
            val kept = kind.values.filter { it in present }
            if (kept.size >= kind.minCount) JSONArray(kept) else null
        }
        is Kind.Text -> (v as? String)?.let { text(it, kind.maxLength, kind.transform) }
        is Kind.Words -> (v as? JSONArray)?.let { a ->
            val out = LinkedHashSet<String>()
            for (i in 0 until a.length()) {
                val raw = a.opt(i) as? String ?: continue
                val word = prefixUtf16(raw.trim().uppercase(), kind.maxLength)
                if (word.isNotEmpty()) out.add(word)
                if (out.size == kind.maxCount) break
            }
            JSONArray(out.toList())
        }
        is Kind.Range -> (v as? JSONObject)?.let { o ->
            val a = number(o.opt("min")) ?: return@let null
            val b = number(o.opt("max")) ?: return@let null
            fun fit(x: Double, r: ClosedFloatingPointRange<Double>): Double {
                val c = x.coerceIn(r.start, r.endInclusive)
                return if (kind.whole) whole(c).toDouble() else tenths(c)
            }
            val low = fit(a, kind.min)
            val high = maxOf(fit(b, kind.max), low)
            if (kind.whole) JSONObject().put("min", low.toInt()).put("max", high.toInt())
            else JSONObject().put("min", low).put("max", high)
        }
        Kind.Speed -> (v as? JSONObject)?.let { o ->
            val c = number(o.opt("characterWpm")) ?: return@let null
            val e = number(o.opt("effectiveWpm")) ?: return@let null
            val f = o.opt("farnsworth") as? Boolean ?: return@let null
            val character = whole(c).coerceIn(15, 60)
            val effective = minOf(whole(e).coerceIn(8, 60), character)
            JSONObject().put("characterWpm", character).put("farnsworth", f).put("effectiveWpm", effective)
        }
    }

    /** The longest prefix of [s] within [max] UTF-16 units that does not split a surrogate pair. */
    fun prefixUtf16(s: String, max: Int): String {
        if (s.length <= max) return s
        var end = max
        if (end > 0 && Character.isHighSurrogate(s[end - 1])) end -= 1
        return s.substring(0, end)
    }

    private val ID = Regex("^[A-Za-z0-9_-]*$")

    fun text(s: String, maxLength: Int, transform: String): String? = when (transform) {
        "callsign" -> s.trim().uppercase().filter { it in 'A'..'Z' || it in '0'..'9' || it == '/' }.take(maxLength)
        "state" -> s.uppercase().filter { it in 'A'..'Z' }.take(maxLength)
        "trim" -> prefixUtf16(s.trim(), maxLength)
        "id" -> s.takeIf { it.length <= maxLength && ID.matches(it) }
        else -> null
    }

    /** JSON equality: numbers by value, arrays in order, objects by key. */
    fun jsonEquals(a: Any?, b: Any?): Boolean {
        val x = if (a == JSONObject.NULL) null else a
        val y = if (b == JSONObject.NULL) null else b
        return when {
            x == null || y == null -> x == null && y == null
            x is Number && y is Number -> x.toDouble() == y.toDouble()
            x is JSONArray && y is JSONArray ->
                x.length() == y.length() && (0 until x.length()).all { jsonEquals(x.opt(it), y.opt(it)) }
            x is JSONObject && y is JSONObject -> {
                val kx = x.keys().asSequence().toSet()
                kx == y.keys().asSequence().toSet() && kx.all { jsonEquals(x.opt(it), y.opt(it)) }
            }
            else -> x == y
        }
    }
}
