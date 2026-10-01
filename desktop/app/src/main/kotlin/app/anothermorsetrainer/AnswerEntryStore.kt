package app.anothermorsetrainer

import app.anothermorsetrainer.morsekit.AnswerEntryLadder
import app.anothermorsetrainer.morsekit.AnswerEntryTier
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists each choice quiz's own answer-entry ladder (#232) — four choices →
 * six → typed — keyed by [SettingsMode] name. Progress, not a setting, so it
 * keeps its own file rather than riding [Settings]. The iOS app keeps the same
 * ladders as JSON in UserDefaults under `MorseTrainer.answerEntryLadders`.
 *
 * JSON: `{MODE: {tier: "sixChoices", recent: [true, false, …]}}`. Anything
 * unreadable loads as a fresh ladder, so a bad save never blocks a drill.
 */
object AnswerEntryStore {
    private lateinit var prefs: Prefs

    fun init() {
        prefs = Prefs.open("amt_answer_entry")
    }

    fun load(mode: SettingsMode): AnswerEntryLadder {
        val json = prefs.getString("ladders", null) ?: return AnswerEntryLadder()
        return runCatching {
            val entry = JSONObject(json).optJSONObject(mode.name) ?: return AnswerEntryLadder()
            val recent = entry.optJSONArray("recent") ?: JSONArray()
            AnswerEntryLadder(
                tier = AnswerEntryTier.fromId(entry.optString("tier", "")),
                recent = (0 until recent.length()).map { recent.optBoolean(it) }
            )
        }.getOrDefault(AnswerEntryLadder())
    }

    fun save(mode: SettingsMode, ladder: AnswerEntryLadder) {
        val all = runCatching { JSONObject(prefs.getString("ladders", null) ?: "{}") }.getOrDefault(JSONObject())
        all.put(mode.name, JSONObject().apply {
            put("tier", ladder.tier.id)
            put("recent", JSONArray(ladder.recent))
        })
        prefs.edit { putString("ladders", all.toString()) }
    }
}
