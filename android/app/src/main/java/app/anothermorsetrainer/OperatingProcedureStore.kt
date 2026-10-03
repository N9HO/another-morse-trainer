package app.anothermorsetrainer

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import app.anothermorsetrainer.morsekit.OperatingProcedureProgress
import org.json.JSONArray
import org.json.JSONObject

/**
 * Where CW Operating Procedure's progress lives (#294, #295,
 * docs/operating-procedure-design.md): its own preferences file, not
 * [Settings] — it is progress, not a preference. Twin of the iOS
 * `OperatingProcedureStore` in AppModel+OperatingProcedure.swift.
 *
 * [progress] is snapshot state, so the screen recomposes on every [save].
 * [OperatingProcedureProgress] is immutable: the screen records an answer and
 * hands the result back here. The drill's in-a-row streak is held in memory
 * only; the saved form leaves it out.
 */
object OperatingProcedureStore {
    private lateinit var prefs: SharedPreferences
    private const val KEY = "progress"

    /** Bumped on every save, so a reader can key on it. */
    var version by mutableIntStateOf(0)
        private set

    var progress by mutableStateOf(OperatingProcedureProgress())
        private set

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("amt_operating_procedure", Context.MODE_PRIVATE)
        progress = decode(prefs.getString(KEY, null))
    }

    fun save(p: OperatingProcedureProgress) {
        progress = p
        prefs.edit { putString(KEY, encode(p)) }
        version++
    }

    fun reset() {
        progress = OperatingProcedureProgress()
        prefs.edit { remove(KEY) }
        version++
    }

    /** The saved form: `{"passed":[…],"cleanRuns":[…],"drillPassed":bool}`, names sorted. */
    internal fun encode(p: OperatingProcedureProgress): String = JSONObject().apply {
        put("passed", JSONArray(p.passedNames))
        put("cleanRuns", JSONArray(p.cleanRunNames))
        put("drillPassed", p.drillPassed)
    }.toString()

    /** A saved record; anything missing or unreadable starts fresh, unknown lesson names are skipped. */
    internal fun decode(json: String?): OperatingProcedureProgress {
        if (json.isNullOrBlank()) return OperatingProcedureProgress()
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return OperatingProcedureProgress()
        fun names(key: String): List<String> {
            val a = o.optJSONArray(key) ?: return emptyList()
            return (0 until a.length()).map { a.optString(it) }
        }
        return OperatingProcedureProgress.restore(names("passed"), names("cleanRuns"), o.optBoolean("drillPassed", false))
    }
}
