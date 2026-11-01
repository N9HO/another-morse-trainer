package app.anothermorsetrainer.morsekit

/**
 * "Continue with the key after a miss" (issues #352, #347): the decision logic.
 *
 * The trap. When a keyed answer is wrong the screen holds the correction —
 * "it was D" — and waits for a Next tap, so a learner on a Vail adapter, a BLE
 * key or the on-screen paddles has to let go of the key and reach for the
 * screen after every miss.
 *
 * The design. A miss puts the screen in an *acknowledge* state. Keying the
 * correct answer again — which is exactly what the correction invites — acts as
 * Next. Why this and not retry-until-correct or "any key after a pause": the
 * re-key is the useful practice (you send the thing you just got wrong), a
 * wrong re-key costs nothing and changes nothing (no second miss is counted, so
 * fumbling the retry cannot drag accuracy down twice for one error), and a stray
 * touch cannot skip the correction the way "any key" would. Next and the tap
 * still work; this only adds a way forward.
 *
 * The debounce. The keyer is still decoding when the miss lands — the last
 * dits of the wrong answer, or the sidetone replay's key-up, can arrive right
 * after feedback is drawn. Anything keyed in the first [DEBOUNCE_MS] after the
 * miss is ignored (right or wrong), so the learner never advances by accident
 * before they have seen the correction. The caller clears its keyer on every
 * non-[Outcome.ADVANCE] result so a wrong attempt does not stay in the decoder
 * and spoil the next one.
 *
 * Pure: no clock, no keyer, no settings. The caller passes times in and applies
 * the outcome. Pinned by `fixtures/continue-after-miss.json`. Twin of
 * MorseKit/ContinueAfterMiss.swift (and of the desktop tree's copy) — keep in step.
 */
class ContinueAfterMiss {

    enum class Outcome {
        /** Not acknowledging a miss; the input is not this class's business. */
        INACTIVE,
        /** The correct answer was keyed: behave as Next. */
        ADVANCE,
        /** A wrong re-key: ignore it, stay on the miss, count nothing. */
        WRONG,
        /** Keyed too soon after the miss feedback: ignore it. */
        DEBOUNCED
    }

    private var target: String? = null
    private var missAtMs: Long = 0

    /** True between a miss that armed this and the answer, [reset], or a new miss. */
    val isAwaiting: Boolean get() = target != null

    /**
     * A keyed answer was just graded wrong. Arms the acknowledge state when the
     * setting is [enabled] and [target] has something to key; otherwise it is
     * (left) idle.
     */
    fun miss(target: String, atMs: Long, enabled: Boolean) {
        val wanted = normalize(target)
        if (!enabled || wanted.isEmpty()) {
            this.target = null
            return
        }
        this.target = wanted
        this.missAtMs = atMs
    }

    /** What to do with [text], decoded from the key at [atMs]. */
    fun input(text: String, atMs: Long): Outcome {
        val wanted = target ?: return Outcome.INACTIVE
        if (atMs - missAtMs < DEBOUNCE_MS) return Outcome.DEBOUNCED
        if (normalize(text) == wanted) {
            target = null
            return Outcome.ADVANCE
        }
        return Outcome.WRONG
    }

    /** Next was pressed by hand, or a new drill began. */
    fun reset() {
        target = null
    }

    private fun normalize(s: String): String = s.trim().uppercase()

    companion object {
        /** Ignore keying this long after the miss feedback appears. */
        const val DEBOUNCE_MS = 600L
    }
}
