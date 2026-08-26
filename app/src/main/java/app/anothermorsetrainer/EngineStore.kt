package app.anothermorsetrainer

import android.content.Context
import android.content.SharedPreferences
import app.anothermorsetrainer.morsekit.CharacterStats
import app.anothermorsetrainer.morsekit.ProgressiveCharacters
import app.anothermorsetrainer.morsekit.TrainerEngine
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists the Characters track — the Koch ladder's active/met characters,
 * per-character stats, confusion matrix, stage, and any pinned stage — so
 * practice resumes where it left off instead of restarting on every entry.
 * The iOS app stores the equivalent snapshot as JSON in UserDefaults under
 * `MorseTrainer.progress`; this is the SharedPreferences twin.
 *
 * A process-wide singleton like [Settings]/[Stats]/[JourneyStore]. The
 * Confusion Drill shares the same restored engine, so the mix-ups it drills
 * are the ones actually recorded in past practice.
 */
object EngineStore {
    private lateinit var prefs: SharedPreferences

    /** The live track most recently handed out, saved by [save]. */
    private var tracked: ProgressiveCharacters? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("amt_engine", Context.MODE_PRIVATE)
    }

    /**
     * The Characters track, restored from the last save when one exists, or
     * freshly seeded from the declared proficiency when not. The returned
     * track is remembered so [save] can snapshot it after each answer.
     */
    fun characters(): ProgressiveCharacters {
        val engine = TrainerEngine(Settings.engineConfig())
        val chars = ProgressiveCharacters(engine)
        val saved = load()
        if (saved != null) chars.restore(saved) else Settings.applyProficiency(engine)
        tracked = chars
        return chars
    }

    /** Persist the tracked track's current snapshot (no-op when none is live). */
    fun save() {
        val chars = tracked ?: return
        prefs.edit().putString("engine", encode(chars.snapshot)).apply()
    }

    /**
     * Restart the ladder from the (possibly new) proficiency seed while keeping
     * per-character stats and recorded confusions — mirrors the iOS behavior
     * where changing proficiency "restarts your active set" but prior practice
     * isn't lost. No-op when nothing has ever been saved.
     */
    fun reseed() {
        val chars = tracked ?: load()?.let { saved ->
            ProgressiveCharacters(TrainerEngine(Settings.engineConfig())).apply { restore(saved) }
        } ?: return
        Settings.applyProficiency(chars.engine)
        chars.resetToSingles()
        tracked = chars
        save()
    }

    /** Forget everything (the Settings "Reset all progress" path). */
    fun reset() {
        tracked = null
        prefs.edit().clear().apply()
    }

    // ---- Serialization (stand-in for the Swift Codable snapshot) ----

    private fun load(): ProgressiveCharacters.Snapshot? {
        val json = prefs.getString("engine", null) ?: return null
        return runCatching { decode(json) }.getOrNull()
    }

    private fun encode(snap: ProgressiveCharacters.Snapshot): String {
        val statsArr = JSONArray()
        for (s in snap.engine.stats) {
            val attempts = JSONArray()
            for (a in s.attempts) {
                attempts.put(JSONArray().put(if (a.correct) 1 else 0).put(a.ttr))
            }
            statsArr.put(JSONObject().put("c", s.character.toString()).put("a", attempts))
        }
        val confusions = JSONObject()
        for ((k, v) in snap.engine.confusions) confusions.put(k, v)
        val obj = JSONObject()
            .put("active", snap.engine.activeCharacters.joinToString(""))
            .put("exposed", snap.engine.exposedCharacters.joinToString(""))
            .put("stats", statsArr)
            .put("conf", confusions)
            .put("stage", snap.stage.name)
        snap.pinnedStage?.let { obj.put("pin", it.name) }
        return obj.toString()
    }

    private fun decode(json: String): ProgressiveCharacters.Snapshot {
        val obj = JSONObject(json)
        val stats = ArrayList<CharacterStats>()
        val statsArr = obj.getJSONArray("stats")
        for (i in 0 until statsArr.length()) {
            val o = statsArr.getJSONObject(i)
            val ch = o.getString("c").firstOrNull() ?: continue
            val attemptsArr = o.getJSONArray("a")
            val attempts = ArrayList<CharacterStats.Attempt>(attemptsArr.length())
            for (j in 0 until attemptsArr.length()) {
                val pair = attemptsArr.getJSONArray(j)
                attempts.add(CharacterStats.Attempt(pair.getInt(0) == 1, pair.getDouble(1)))
            }
            stats.add(CharacterStats(ch, attempts))
        }
        val confusions = mutableMapOf<String, Int>()
        val confObj = obj.getJSONObject("conf")
        for (key in confObj.keys()) confusions[key] = confObj.getInt(key)
        val stage = runCatching { ProgressiveCharacters.Stage.valueOf(obj.getString("stage")) }
            .getOrDefault(ProgressiveCharacters.Stage.Singles)
        val pin = obj.optString("pin", "").takeIf { it.isNotEmpty() }?.let {
            runCatching { ProgressiveCharacters.Stage.valueOf(it) }.getOrNull()
        }
        return ProgressiveCharacters.Snapshot(
            engine = TrainerEngine.Snapshot(
                activeCharacters = obj.getString("active").toList(),
                stats = stats,
                confusions = confusions,
                exposedCharacters = obj.getString("exposed").toSet()
            ),
            stage = stage,
            pinnedStage = pin
        )
    }
}
