package app.anothermorsetrainer.morsekit

/**
 * The arithmetic behind the session timer menu (iOS `AppModel.addSessionTime`
 * / `reduceSessionTime`, issue #41), kept pure so it is pinned by
 * `fixtures/gap-timers-session-clock.json`.
 *
 * `remaining` is the seconds left on the countdown, or null for an open-ended
 * session. Reaching 0 ends the session, or holds it for a drill still waiting
 * for its answer ([endsAfterTimeUp]); the screen's own countdown does that.
 */
object SessionClock {
    /**
     * The longest a run-out countdown holds the session for the drill that
     * was waiting (`timeUp.graceSeconds` in the fixture).
     */
    const val TIME_UP_GRACE_SECONDS = 15

    /**
     * Whether a session whose countdown ran out [overdueSeconds] ago ends now.
     * [held] is whether the run-out found a drill sent and waiting for its
     * answer: that drill may still be answered, and answering it ends the
     * session where the next drill would have started, so the last item heard
     * is never one the learner had no time to answer. The hold lasts at most
     * [TIME_UP_GRACE_SECONDS], so an abandoned session still ends. Unheld, it
     * ends at once.
     */
    fun endsAfterTimeUp(held: Boolean, overdueSeconds: Int): Boolean =
        !held || overdueSeconds >= TIME_UP_GRACE_SECONDS

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
