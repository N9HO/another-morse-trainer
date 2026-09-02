package app.anothermorsetrainer

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import app.anothermorsetrainer.morsekit.JourneyCurriculum
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
        prefs.edit {
            putInt("unlockedThrough", progress.unlockedThrough)
            putInt("currentLevel", progress.currentLevel)
            putStringSet("completed", progress.completed.map { it.toString() }.toSet())
        }
    }

    /**
     * Unlock the Journey as far as the declared starting level reaches, so a
     * learner who said they know the letters is not made to re-earn K and M
     * one level at a time (#109). Only ever raises: levels actually cleared are
     * never clawed back, and "I know nothing" leaves level 1 as it is. The
     * current level moves up with the unlock so the map opens where they can
     * start, not on level 1 with the real start somewhere below.
     */
    fun unlockForProficiency() {
        if (Settings.proficiency == Proficiency.NONE) return
        val level = JourneyCurriculum.firstLevelBeyond(Settings.seedCharacters().toSet())
        val progress = load()
        if (level <= progress.unlockedThrough) return
        progress.unlockedThrough = level
        progress.currentLevel = maxOf(progress.currentLevel, level)
        save(progress)
    }

    fun reset() {
        prefs.edit { clear() }
    }
}
