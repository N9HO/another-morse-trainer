package app.anothermorsetrainer

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import app.anothermorsetrainer.morsekit.FirstFour
import app.anothermorsetrainer.morsekit.FirstFourProgress
import app.anothermorsetrainer.morsekit.FirstFourStage
import app.anothermorsetrainer.morsekit.SyncState
import org.json.JSONArray
import org.json.JSONObject

/**
 * Where First Four's progress lives (#265, docs/first-four-design.md): its
 * own preferences file, not [Settings] — it is progress, not a preference.
 * Twin of the iOS `FirstFourStore` in AppModel+FirstFour.swift.
 *
 * [progress] is snapshot state so the home card follows it; the screen
 * mutates a [FirstFourProgress] and hands it back through [save].
 */
object FirstFourStore {
    private lateinit var prefs: SharedPreferences
    private const val KEY = "progress"
    private const val OPEN_ON_HOME = "openOnHome"

    /** Bumped on every save, so a reader of [progress] recomposes. */
    var version by mutableIntStateOf(0)
        private set

    var progress: FirstFourProgress = FirstFourProgress()
        private set

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("amt_first_four", Context.MODE_PRIVATE)
        progress = decode(prefs.getString(KEY, null))
    }

    fun save(p: FirstFourProgress) {
        write(p)
        SyncCoordinator.stateChanged(SyncState.FIRST_FOUR)
    }

    /** The Settings reset: this device only, so it is not pushed as a change. */
    fun reset() = write(FirstFourProgress())

    private fun write(p: FirstFourProgress) {
        progress = p
        prefs.edit { putString(KEY, encode(p)) }
        version++
    }

    /** False until the first save: untouched progress is not pushed over an account's real one. */
    val hasSaved: Boolean get() = prefs.contains(KEY)

    /**
     * Set by onboarding's "first POTA contact" button; Home reads it once it
     * appears and opens First Four.
     */
    var openOnHome: Boolean
        get() = prefs.getBoolean(OPEN_ON_HOME, false)
        set(value) = prefs.edit { putBoolean(OPEN_ON_HOME, value) }

    /** The saved form: passed and copyPassed as sorted names, clean runs by name. */
    internal fun encode(p: FirstFourProgress): String = JSONObject().apply {
        put("passed", JSONArray(p.passed.map { it.raw }.sorted()))
        put("copyPassed", JSONArray(p.copyPassed.map { it.raw }.sorted()))
        put("cleanRuns", JSONObject().apply { p.cleanRunsByStage.forEach { (k, v) -> put(k.raw, v) } })
    }.toString()

    /** A saved record; anything missing or unreadable starts fresh. */
    internal fun decode(json: String?): FirstFourProgress {
        if (json.isNullOrBlank()) return FirstFourProgress()
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return FirstFourProgress()
        fun stages(key: String): List<FirstFourStage> {
            val a = o.optJSONArray(key) ?: return emptyList()
            return (0 until a.length()).mapNotNull { FirstFourStage.fromRaw(a.optString(it)) }
        }
        val runs = mutableMapOf<FirstFourStage, Int>()
        o.optJSONObject("cleanRuns")?.let { r ->
            r.keys().forEach { k -> FirstFourStage.fromRaw(k)?.let { runs[it] = r.optInt(k) } }
        }
        return FirstFourProgress.restore(stages("passed"), stages("copyPassed"), runs)
    }

    /** The call field's starting value (the settings placeholder counts as empty). */
    fun prefillCall(): String = FirstFour.prefillCall(PileupSettings.myCall)
}
