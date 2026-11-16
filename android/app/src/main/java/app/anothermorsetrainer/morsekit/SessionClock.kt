package app.anothermorsetrainer.morsekit

/**
 * The arithmetic behind the session timer menu (iOS `AppModel.addSessionTime`
 * / `reduceSessionTime`, issue #41), kept pure so it is pinned by
 * `fixtures/gap-timers-session-clock.json`.
 *
 * `remaining` is the seconds left on the countdown, or null for an open-ended
 * session. Reaching 0 ends the session; the screen's own countdown does that.
 */
object SessionClock {
    /**
     * "Add N minutes": on a timed session the seconds are added to what is
     * left; on an open-ended one they start a countdown of exactly [seconds].
     * Zero or less changes nothing.
     */
    fun added(remaining: Int?, seconds: Int): Int? {
        if (seconds <= 0) return remaining
        return (remaining ?: 0) + seconds
    }

    /**
     * "Subtract 1 minute" (offered only while timed): what is left drops by
     * [seconds], never below 0. An open-ended session has nothing to subtract
     * from and stays open-ended. Zero or less changes nothing.
     */
    fun reduced(remaining: Int?, seconds: Int): Int? {
        if (remaining == null || seconds <= 0) return remaining
        return maxOf(0, remaining - seconds)
    }
}
