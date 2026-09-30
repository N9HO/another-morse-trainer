package app.anothermorsetrainer

/**
 * The Android port's audio-focus holder, kept as a bookkeeping shell.
 *
 * On Android this requests and abandons audio focus so the trainer pauses the
 * user's music and gets out of the way of a phone call. Windows and Linux have
 * no audio-focus protocol an application joins: every stream mixes with every
 * other, and a call app ducks others itself if it wants to. So on desktop
 * nothing is requested, no [Event] is ever delivered, and the trainer plays
 * alongside whatever else is playing — which is the platform's own idiom, not
 * a missing feature (PARITY.md, "Same feature, platform mechanism").
 *
 * The API is the Android one, unchanged, so [MorsePlayer], [SidetoneGenerator],
 * [BackgroundNoise], the Listen loop and the repeater keep their acquire and
 * release calls; the holder set is still kept, so [isHeld] can say whether
 * anything is sounding.
 */
object AudioFocus {

    /** How much to take from other apps while a holder is making sound. Recorded, not enforced, on desktop. */
    enum class Gain {
        /** Others pause — the drills. */
        EXCLUSIVE,
        /** Others turn down and carry on — the repeater. */
        DUCK,
    }

    /** What happened to focus. Never delivered on desktop. */
    enum class Event {
        LOST,
        LOST_TRANSIENT,
        REGAINED,
    }

    /** Handle for an [observe] subscription. */
    class Observation internal constructor(internal val id: Int)

    private val holders = mutableSetOf<Any>()
    private val listeners = mutableMapOf<Int, (Event) -> Unit>()
    private var nextListenerId = 0

    /** Nothing to look up on desktop; kept so start-up reads like the Android port's. */
    fun init() {}

    /** True while any holder says it is making sound. */
    val isHeld: Boolean @Synchronized get() = holders.isNotEmpty()

    /** Record that [owner] is making sound. Always "granted": there is nobody to refuse. */
    @Synchronized
    @Suppress("UNUSED_PARAMETER")
    fun acquire(owner: Any, gain: Gain = Gain.EXCLUSIVE): Boolean {
        holders.add(owner)
        return true
    }

    @Synchronized
    fun release(owner: Any) {
        holders.remove(owner)
    }

    @Synchronized
    fun observe(listener: (Event) -> Unit): Observation {
        nextListenerId += 1
        listeners[nextListenerId] = listener
        return Observation(nextListenerId)
    }

    @Synchronized
    fun removeObserver(observation: Observation) {
        listeners.remove(observation.id)
    }
}
