package app.anothermorsetrainer

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import app.anothermorsetrainer.morsekit.VoiceProfile
import org.json.JSONObject

/**
 * Persists the learner's [VoiceProfile] — the "this transcript means that
 * answer" tallies built up from voice confirmations and corrections — so the
 * personalization survives restarts. The iOS app keeps the Codable profile in
 * UserDefaults; this is the SharedPreferences twin (JSON:
 * `{heard: {answer: count}}`).
 */
object VoiceProfileStore {
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("amt_voice", Context.MODE_PRIVATE)
    }

    fun load(): VoiceProfile {
        val json = prefs.getString("profile", null) ?: return VoiceProfile()
        return runCatching {
            val obj = JSONObject(json)
            val corrections = mutableMapOf<String, Map<String, Int>>()
            for (heard in obj.keys()) {
                val tallyObj = obj.getJSONObject(heard)
                val tally = mutableMapOf<String, Int>()
                for (answer in tallyObj.keys()) tally[answer] = tallyObj.getInt(answer)
                corrections[heard] = tally
            }
            VoiceProfile(corrections)
        }.getOrDefault(VoiceProfile())
    }

    fun save(profile: VoiceProfile) {
        val obj = JSONObject()
        for ((heard, tally) in profile.snapshot()) {
            val tallyObj = JSONObject()
            for ((answer, count) in tally) tallyObj.put(answer, count)
            obj.put(heard, tallyObj)
        }
        prefs.edit { putString("profile", obj.toString()) }
    }
}
