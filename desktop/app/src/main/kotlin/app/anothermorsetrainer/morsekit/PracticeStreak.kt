package app.anothermorsetrainer.morsekit

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Tracks how many consecutive calendar days the learner has practiced, to
 * motivate daily engagement (issue #20).
 *
 * A "day" is a calendar day in the user's current time zone, and *any* recorded
 * drill answer counts as having practiced — the goal is to reward showing up,
 * not to gate on session length. The streak stays alive as long as you practice
 * at least once every day: practice today and it holds, practice the next day
 * and it grows, miss a whole day and it resets.
 *
 * Translated from MorseKit/PracticeStreak.swift. Swift's `struct` +
 * `mutating func` becomes a Kotlin `class` with ordinary methods. The Swift
 * `Date`/`Calendar` start-of-day logic maps onto [LocalDate] (already
 * day-granular); the clock is injected as a `today: LocalDate` parameter
 * mirroring the Swift `date`/`calendar` arguments. Swift `Codable` would map to
 * kotlinx.serialization; here the fields are plain and exposed directly.
 */
class PracticeStreak(
    /**
     * Consecutive-day count as of [lastPracticeDay]. Use [display] to read the
     * streak as it stands *today* — `current` does not self-expire.
     */
    current: Int = 0,
    /** Best streak ever reached, kept as a personal record even after a lapse. */
    longest: Int = 0,
    /** The most recent day the learner practiced (null = never). */
    lastPracticeDay: LocalDate? = null
) {
    var current: Int = current
        private set
    var longest: Int = longest
        private set
    var lastPracticeDay: LocalDate? = lastPracticeDay
        private set

    companion object {
        /** Streak lengths worth celebrating with a milestone badge. */
        val milestones = listOf(3, 7, 14, 30, 60, 100, 365)

        /**
         * Whether [day] is exactly a celebrated milestone (use to fire a
         * one-time celebration the day it's reached).
         */
        fun isMilestone(day: Int): Boolean = milestones.contains(day)

        /**
         * The highest milestone reached at [day], or null if none yet — gives
         * the streak badge its tier.
         */
        fun milestone(forDay: Int): Int? = milestones.lastOrNull { it <= forDay }
    }

    /**
     * Record that the learner practiced on [today]. Idempotent within a day:
     * the first practice of a day advances the streak, later ones are no-ops.
     *
     * @return `true` if this was the day's first practice (the streak counter
     *   changed), so callers can fire day-one-only UI (a toast, a haptic)
     *   without repeating it on every drill.
     */
    fun record(today: LocalDate = LocalDate.now()): Boolean {
        val last = lastPracticeDay ?: run {
            current = 1
            longest = maxOf(longest, current)
            lastPracticeDay = today
            return true
        }
        val gap = ChronoUnit.DAYS.between(last, today).toInt()
        when {
            gap <= 0 -> return false        // same day (or clock skew) — already counted
            gap == 1 -> current += 1        // consecutive day — extend the streak
            else -> current = 1             // a full day was missed — start over
        }
        longest = maxOf(longest, current)
        lastPracticeDay = today
        return true
    }

    /**
     * The streak as it should read *today*. A streak the learner already let
     * lapse must show 0, not a stale count — so this returns [current] only
     * while the streak is still alive (practiced today or yesterday) and 0
     * once a full day has been missed.
     */
    fun display(today: LocalDate = LocalDate.now()): Int {
        val last = lastPracticeDay ?: return 0
        val gap = ChronoUnit.DAYS.between(last, today).toInt()
        return if (gap <= 1) current else 0
    }

    override fun equals(other: Any?): Boolean =
        other is PracticeStreak &&
            other.current == current &&
            other.longest == longest &&
            other.lastPracticeDay == lastPracticeDay

    override fun hashCode(): Int {
        var result = current
        result = 31 * result + longest
        result = 31 * result + (lastPracticeDay?.hashCode() ?: 0)
        return result
    }
}
