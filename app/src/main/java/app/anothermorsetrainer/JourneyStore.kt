package app.anothermorsetrainer

import android.content.Context
import android.content.SharedPreferences
import app.anothermorsetrainer.morsekit.JourneyProgress

/**
 * Persists [JourneyProgress] (unlock/completion state) in SharedPreferences.
 * The iOS app stores the equivalent as JSON in UserDefaults; here the few fields
 * are stored as plain keys. A process-wide singleton, initialised in
 * [MainActivity] alongside [Settings] and [Stats].
 */
object JourneyStore {
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("amt_journey", Context.MODE_PRIVATE)
    }

    fun load(): JourneyProgress {
        val unlocked = prefs.getInt("unlockedThrough", 1)
        val current = prefs.getInt("currentLevel", 1)
        val completed = prefs.getStringSet("completed", emptySet())
            ?.mapNotNull { it.toIntOrNull() }?.toMutableSet() ?: mutableSetOf()
        return JourneyProgress(unlockedThrough = unlocked, currentLevel = current, completed = completed)
    }

    fun save(progress: JourneyProgress) {
        prefs.edit()
            .putInt("unlockedThrough", progress.unlockedThrough)
            .putInt("currentLevel", progress.currentLevel)
            .putStringSet("completed", progress.completed.map { it.toString() }.toSet())
            .apply()
    }

    fun reset() {
        prefs.edit().clear().apply()
    }
}
