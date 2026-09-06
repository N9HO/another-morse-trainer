package app.anothermorsetrainer.morsekit

import java.time.LocalDate
import java.util.TreeMap

/**
 * How much the learner practiced on each calendar day, for the Stats
 * screen's activity grid (#181): one tile per day, shaded by that day's
 * total session time.
 *
 * The session history keeps only the newest hundred sessions, which is a few
 * weeks for a daily learner, so the grid keeps its own ledger: a map of local
 * calendar day to whole seconds practiced, capped at [CAP_DAYS] days with the
 * oldest dropped first. A day is a calendar day in the user's current time
 * zone, the same boundary [PracticeStreak] uses.
 *
 * Shaded in five levels from fixed thresholds — `fixtures/activity.json`
 * pins the thresholds, the per-day totals and the cap on both ports.
 *
 * Translated from MorseKit/ActivityLedger.swift. The Swift `yyyy-MM-dd`
 * string key becomes a [LocalDate] (whose ISO `toString` is that format);
 * the backing [TreeMap] keeps the days in order so the cap can drop the
 * earliest. Swift `struct` + `mutating func` → a class with ordinary methods.
 */
class ActivityLedger(days: Map<LocalDate, Int> = emptyMap()) {
    private val _days = TreeMap<LocalDate, Int>(days)

    /** Whole seconds practiced per local day, earliest first. */
    val days: Map<LocalDate, Int> get() = _days

    companion object {
        /** The most days the ledger keeps; the earliest fall off beyond it. */
        const val CAP_DAYS = 400

        /**
         * Seconds at which shade levels 1…4 begin: a recorded day below the
         * first is level 1 anyway (see [level]), 300 s (5 min) is level 2,
         * 900 s (15 min) level 3, 1800 s (30 min) level 4.
         */
        val LEVEL_THRESHOLDS = listOf(1, 300, 900, 1800)

        /** The number of shade levels, 0 (nothing) through 4 (most). */
        const val MAX_LEVEL = 4

        /**
         * The shade level for a seconds total, 0…4, from [LEVEL_THRESHOLDS]
         * alone: 0 s is level 0. A pure function so the fixture can pin it.
         */
        fun levelForSeconds(seconds: Int): Int = LEVEL_THRESHOLDS.count { seconds >= it }
    }

    /**
     * Add [seconds] of practice to [day]. A session with no duration records
     * 0 and still marks the day; negative input counts as 0. Trims the
     * earliest days once the cap is exceeded.
     */
    fun record(day: LocalDate, seconds: Int) {
        _days[day] = (_days[day] ?: 0) + maxOf(0, seconds)
        while (_days.size > CAP_DAYS) _days.pollFirstEntry()
    }

    /** Whether anything was recorded on [day]. */
    fun isRecorded(day: LocalDate): Boolean = _days.containsKey(day)

    /** Seconds practiced on [day] (0 when nothing was recorded). */
    fun seconds(day: LocalDate): Int = _days[day] ?: 0

    /**
     * The tile shade for [day]: [levelForSeconds] of its total, but never
     * below 1 for a day that was recorded — showing up with a session that
     * logged no time is still showing up. Unrecorded days are 0.
     */
    fun level(day: LocalDate): Int {
        val total = _days[day] ?: return 0
        return maxOf(1, levelForSeconds(total))
    }

    /** Distinct days recorded (at most [CAP_DAYS]). */
    val dayCount: Int get() = _days.size

    /** Every recorded day, earliest first. */
    val recordedDays: List<LocalDate> get() = _days.keys.toList()

    override fun equals(other: Any?): Boolean = other is ActivityLedger && other._days == _days
    override fun hashCode(): Int = _days.hashCode()
}
