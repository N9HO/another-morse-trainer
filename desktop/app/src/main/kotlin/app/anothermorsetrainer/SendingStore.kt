package app.anothermorsetrainer

import app.anothermorsetrainer.morsekit.SendingRecord
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistence for the Sending Analyzer: its own small settings, and the
 * [SendingRecord] of per-character and per-pair results.
 *
 * The record lives in the `amt_stats` store next to the rest of the
 * progress, so the Settings "Reset all progress" wipe (which clears that file)
 * takes it too — the iOS reset removes the same record. The settings are
 * preferences, not progress, and live in their own file.
 */
object SendingStore {
    private const val RECORD_KEY = "sendingRecord"

    private fun stats(): Prefs = Prefs.open("amt_stats")

    fun prefs(): Prefs = Prefs.open("amt_sending_analyzer")

    fun loadRecord(): SendingRecord {
        val record = SendingRecord()
        val text = stats().getString(RECORD_KEY, null) ?: return record
        try {
            val o = JSONObject(text)
            fun tallies(obj: JSONObject?, into: MutableMap<String, SendingRecord.Tally>) {
                obj ?: return
                for (k in obj.keys()) {
                    val a = obj.optJSONArray(k) ?: continue
                    into[k] = SendingRecord.Tally(a.optInt(0), a.optInt(1))
                }
            }
            tallies(o.optJSONObject("characters"), record.characters)
            tallies(o.optJSONObject("pairs"), record.pairs)
            o.optJSONObject("mixups")?.let { m ->
                record.mixups.restore(m.keys().asSequence().associateWith { m.optInt(it) })
            }
            o.optJSONArray("attempts")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val a = arr.optJSONObject(i) ?: continue
                    record.attempts.add(
                        SendingRecord.Attempt(
                            epochMs = a.optLong("date"),
                            accuracy = a.optDouble("accuracy", 0.0),
                            characterWpm = a.optDouble("characterWpm", 0.0),
                            effectiveWpm = a.optDouble("effectiveWpm", 0.0),
                            characters = a.optInt("characters"),
                            characterGapMean = if (a.has("characterGapMean")) a.optDouble("characterGapMean") else null,
                            wordGapMean = if (a.has("wordGapMean")) a.optDouble("wordGapMean") else null
                        )
                    )
                }
            }
        } catch (_: org.json.JSONException) {
            // A damaged record starts over rather than taking the screen down.
            return SendingRecord()
        }
        return record
    }

    fun saveRecord(record: SendingRecord) {
        val o = JSONObject()
        fun tallies(map: Map<String, SendingRecord.Tally>) = JSONObject().apply {
            map.forEach { (k, t) -> put(k, JSONArray().put(t.attempts).put(t.misses)) }
        }
        o.put("characters", tallies(record.characters))
        o.put("pairs", tallies(record.pairs))
        o.put("mixups", JSONObject().apply { record.mixups.snapshot().forEach { (k, v) -> put(k, v) } })
        o.put("attempts", JSONArray().apply {
            record.attempts.forEach { a ->
                put(JSONObject().apply {
                    put("date", a.epochMs)
                    put("accuracy", a.accuracy)
                    put("characterWpm", a.characterWpm)
                    put("effectiveWpm", a.effectiveWpm)
                    put("characters", a.characters)
                    a.characterGapMean?.let { put("characterGapMean", it) }
                    a.wordGapMean?.let { put("wordGapMean", it) }
                })
            }
        })
        stats().edit { putString(RECORD_KEY, o.toString()) }
    }

    fun clearRecord() {
        stats().edit { remove(RECORD_KEY) }
    }
}
