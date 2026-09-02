package app.anothermorsetrainer

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
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
        // A mark opted out while no track was live (Settings before Characters
        // after a relaunch) is reconciled here instead, and saved for the same
        // reason applyStudyOrder saves.
        if (engine.applyStudyOrder(Settings.studyOrder()).isNotEmpty()) save()
        return chars
    }

    /**
     * Re-derive the introduction order (Koch core + opted-in punctuation) on
     * the live engine after a settings change. The order itself is derived
     * state, so nothing to save — but opting *out* of a mark already earned
     * removes it from the active set (issue #133), and the active set is in
     * the snapshot, so a removal is saved right away rather than left to the
     * next answer. Its stats stay, so opting back in and re-earning it loses
     * no history.
     */
    fun applyStudyOrder() {
        val engine = tracked?.engine ?: return
        if (engine.applyStudyOrder(Settings.studyOrder()).isNotEmpty()) save()
    }

    /** Persist the tracked track's current snapshot (no-op when none is live). */
    fun save() {
        val chars = tracked ?: return
        prefs.edit { putString("engine", encode(chars.snapshot)) }
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
        prefs.edit { clear() }
    }

    // ---- Serialization (stand-in for the Swift Codable snapshot) ----

    private fun load(): ProgressiveCharacters.Snapshot? {
        val json = prefs.getString("engine", null) ?: return null
        return runCatching { decode(json) }.getOrNull()
    }

    // `internal`, not `private`: EngineStoreCodecTest round-trips these without
    // a Context, which is the only way the format gets tested at all.
    internal fun encode(snap: ProgressiveCharacters.Snapshot): String {
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
            .put("exposed", snap.engine.exposedCharacters.orEmpty().joinToString(""))
            .put("stats", statsArr)
            .put("conf", confusions)
            .put("stage", snap.stage.name)
        snap.pinnedStage?.let { obj.put("pin", it.name) }
        return obj.toString()
    }

    internal fun decode(json: String): ProgressiveCharacters.Snapshot {
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
        // `getString` throws when the key is absent, and `decode` is called
        // inside a `runCatching { }.getOrNull()` — so reading "exposed" that way
        // turned any pre-exposure-tracking save into a decode failure, which
        // `characters()` cannot distinguish from "never saved" and answers by
        // reseeding from proficiency. That silently discarded the learner's
        // whole Koch ladder on upgrade. Absent stays absent (null) here and the
        // engine's restore backfills it.
        val exposed = if (obj.has("exposed")) obj.getString("exposed").toSet() else null
        return ProgressiveCharacters.Snapshot(
            engine = TrainerEngine.Snapshot(
                activeCharacters = obj.getString("active").toList(),
                stats = stats,
                confusions = confusions,
                exposedCharacters = exposed
            ),
            stage = stage,
            pinnedStage = pin
        )
    }
}
