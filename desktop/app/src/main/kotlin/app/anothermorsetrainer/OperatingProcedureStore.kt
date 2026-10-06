package app.anothermorsetrainer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.anothermorsetrainer.morsekit.OperatingProcedureProgress
import app.anothermorsetrainer.morsekit.SyncStateCodec
import org.json.JSONArray
import org.json.JSONObject

/**
 * Where CW Operating Procedure's progress lives (#294, #295,
 * docs/operating-procedure-design.md): its own [Prefs] file,
 * `amt_operating_procedure`, not [Settings] — it is progress, not a
 * preference. Twin of the iOS `OperatingProcedureStore` in
 * AppModel+OperatingProcedure.swift (and of the Android tree's own copy).
 *
 * A process-wide singleton initialised in `main()` alongside the other
 * stores. [progress] is Compose state, and [version] is bumped on every save,
 * so a reader recomposes. The screen holds an [OperatingProcedureProgress]
 * (immutable) and hands each new one back through [save].
 *
 * Saved as `{"passed":[…],"cleanRuns":[…],"drillPassed":bool}`, lesson names
 * sorted. The drill's in-a-row streak is not saved: three in a row means
 * three in one sitting.
 */
object OperatingProcedureStore {
    private lateinit var prefs: Prefs
    private const val KEY = "progress"

    /** Bumped on every save, so a reader of [progress] recomposes. */
    var version by mutableIntStateOf(0)
        private set

    var progress by mutableStateOf(OperatingProcedureProgress())
        private set

    fun init() {
        prefs = Prefs.open("amt_operating_procedure")
        progress = decode(prefs.getString(KEY, null))
    }

    fun save(p: OperatingProcedureProgress) {
        val before = syncValue()
        write(p)
        SyncCoordinator.stateSaved(SyncStateCodec.OPERATING_PROCEDURE, before, syncValue())
    }

    private fun write(p: OperatingProcedureProgress) {
        progress = p
        prefs.edit { putString(KEY, encode(p)) }
        version++
    }

    /**
     * "Start over": every lesson back to not passed. Call and state are not
     * touched. A reset is local: it is not stamped or synced (the next real
     * change is).
     */
    fun reset() {
        progress = OperatingProcedureProgress()
        prefs.edit { remove(KEY) }
        version++
    }

    /** The synced `operatingProcedure` value, or null when nothing has been saved on this device. */
    fun syncValue(): JSONObject? =
        if (!prefs.contains(KEY)) null else SyncStateCodec.operatingProcedureToWire(progress)

    /** Newer progress from another device, saved as is (not stamped or sent back). */
    fun applySynced(wire: JSONObject) = write(SyncStateCodec.operatingProcedureFromWire(wire))

    /** The saved form: lesson names, sorted, and whether lesson 8's drill has passed. */
    internal fun encode(p: OperatingProcedureProgress): String = JSONObject()
        .put("passed", JSONArray(p.passedNames))
        .put("cleanRuns", JSONArray(p.cleanRunNames))
        .put("drillPassed", p.drillPassed)
        .toString()

    /** A saved record; anything missing or unreadable starts fresh. */
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
