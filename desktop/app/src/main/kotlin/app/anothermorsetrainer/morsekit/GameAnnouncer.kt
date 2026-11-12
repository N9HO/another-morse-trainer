package app.anothermorsetrainer.morsekit

/**
 * The hear-it arcade games' one sound channel: Morse Invaders, CW Galaga and
 * Morse Defender announce each new enemy by its character (or callsign), and
 * a tap replays the nearest. Both used to replace whatever was sounding, so a
 * tap cut into an announcement (or one announcement into the next)
 * mid-character and the two ran together.
 *
 * Now a sound asked for while another plays waits until that one has ended
 * and a word space has passed; on a free channel it starts at once. New
 * enemies are announced in the order they arrived, and one already waiting is
 * not queued twice. A tap queues one replay, heard after any announcement
 * already waiting, and a later tap replaces it. An enemy shot, destroyed or
 * landed before its turn is never announced; one already sounding plays out.
 * Pinned by `fixtures/game-announcer.json`; the Swift twin is
 * `GameAnnouncer.swift`.
 *
 * The announcer only decides; the screen plays. Each frame (and after a tap)
 * it asks [next] for a sound to start, plays it, and reports how long it lasts
 * with [started]. Times are seconds on any one clock the caller keeps to.
 */
class GameAnnouncer {
    sealed interface Sound {
        /** What to send. */
        val text: String

        /** A new enemy's character or callsign. */
        data class Arrival(val id: Int, override val text: String) : Sound

        /** What a tap asked to hear again. */
        data class Replay(override val text: String) : Sound
    }

    private val arrivals = ArrayDeque<Sound.Arrival>()
    private var waitingReplay: String? = null
    /** When the channel is free: the last sound's end plus a word space. */
    private var freeAt = Double.NEGATIVE_INFINITY

    /** A new enemy is on the field: announce it in its turn, unless it is already waiting. */
    fun arrived(id: Int, text: String) {
        if (arrivals.any { it.id == id }) return
        arrivals.addLast(Sound.Arrival(id, text))
    }

    /** A tap asked to hear [text] again; replaces a replay still waiting. */
    fun replay(text: String) {
        waitingReplay = text
    }

    /** The enemy was shot, destroyed or landed: drop its announcement if it is still waiting. */
    fun forget(id: Int) {
        arrivals.removeAll { it.id == id }
    }

    /** A new game, or the end of one: nothing waits, and the channel is free. */
    fun reset() {
        arrivals.clear()
        waitingReplay = null
        freeAt = Double.NEGATIVE_INFINITY
    }

    /**
     * The sound to start at [now], if the channel is free and one is waiting.
     * The caller plays it and reports its length with [started].
     */
    fun next(now: Double): Sound? {
        if (now < freeAt) return null
        arrivals.removeFirstOrNull()?.let { return it }
        val text = waitingReplay ?: return null
        waitingReplay = null
        return Sound.Replay(text)
    }

    /**
     * The sound [next] returned started at [now] and lasts [duration]; the
     * channel is free again a [wordGap] after it ends.
     */
    fun started(now: Double, duration: Double, wordGap: Double) {
        freeAt = now + duration + wordGap
    }
}
