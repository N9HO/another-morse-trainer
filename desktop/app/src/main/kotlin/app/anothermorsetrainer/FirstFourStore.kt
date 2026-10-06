package app.anothermorsetrainer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import app.anothermorsetrainer.morsekit.FirstFour
import app.anothermorsetrainer.morsekit.FirstFourProgress
import app.anothermorsetrainer.morsekit.FirstFourStage
import app.anothermorsetrainer.morsekit.SyncStateCodec
import org.json.JSONArray
import org.json.JSONObject

/**
 * Where First Four's progress lives (#265, #280, docs/first-four-design.md):
 * its own [Prefs] file, not [Settings] — it is progress, not a preference.
 * Twin of the iOS `FirstFourStore` in AppModel+FirstFour.swift; same key
 * names and JSON shape as the Android store.
 *
 * [progress] is snapshot state so the home card follows it; the screen
 * mutates a [FirstFourProgress] and hands it back through [save].
 */
object FirstFourStore {
    private lateinit var prefs: Prefs
    private const val KEY = "progress"

    /** Bumped on every save, so a reader of [progress] recomposes. */
    var version by mutableIntStateOf(0)
        private set

    var progress: FirstFourProgress = FirstFourProgress()
        private set

    fun init() {
        prefs = Prefs.open("amt_first_four")
        progress = decode(prefs.getString(KEY, null))
    }

    fun save(p: FirstFourProgress) {
        val before = syncValue()
        write(p)
        SyncCoordinator.stateSaved(SyncStateCodec.FIRST_FOUR, before, syncValue())
    }

    private fun write(p: FirstFourProgress) {
        progress = p
        prefs.edit { putString(KEY, encode(p)) }
        version++
    }

    /** The synced `firstFour` value, or null when nothing has been saved on this device. */
    fun syncValue(): JSONObject? = if (!prefs.contains(KEY)) null else SyncStateCodec.firstFourToWire(progress)

    /** Newer progress from another device, saved as is (not stamped or sent back). */
    fun applySynced(wire: JSONObject) = write(SyncStateCodec.firstFourFromWire(wire))

    /** "Start over". A reset is local: it is not stamped or synced (the next real change is). */
    fun reset() = write(FirstFourProgress())

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
