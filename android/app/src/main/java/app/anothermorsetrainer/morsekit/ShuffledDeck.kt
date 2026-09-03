package app.anothermorsetrainer.morsekit

import kotlin.random.Random

/**
 * Draws from a fixed pool without replacement: every element comes out once,
 * in random order, before any comes out a second time. A spent pass is
 * reshuffled on the next draw, and the new pass never opens with the element
 * that closed the last one, so two draws in a row are never the same.
 *
 * Listen & Learn used to pick each item with `random()`, which repeats early
 * and often — with a few hundred words the odds of hearing one twice pass
 * even money inside thirty items (issue #158). A deck spaces every item a
 * whole pass apart.
 *
 * Translated from MorseKit/ShuffledDeck.swift.
 */
class ShuffledDeck<T>(val pool: List<T>, private val rng: Random = Random.Default) {
    /** The current pass, drawn from the end. */
    private val remaining = ArrayList<T>()
    private var last: T? = null
    private var hasLast = false

    /** Elements still to come in the current pass — 0 once it is spent, until the next draw reshuffles. */
    val remainingInPass: Int get() = remaining.size

    /** The next element, or null for an empty pool. */
    fun draw(): T? {
        if (pool.isEmpty()) return null
        if (remaining.isEmpty()) {
            remaining.addAll(pool.shuffled(rng))
            // Draws come off the end, so the end is the head of the new pass.
            // If it repeats the last draw, swap it somewhere else in the pass.
            val end = remaining.size - 1
            if (pool.size > 1 && hasLast && remaining[end] == last) {
                val other = rng.nextInt(end)
                val tmp = remaining[end]
                remaining[end] = remaining[other]
                remaining[other] = tmp
            }
        }
        val next = remaining.removeAt(remaining.size - 1)
        last = next
        hasLast = true
        return next
    }
}
