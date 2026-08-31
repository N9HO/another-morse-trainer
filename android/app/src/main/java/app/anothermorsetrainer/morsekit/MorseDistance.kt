package app.anothermorsetrainer.morsekit

/**
 * Measures how "sonically close" two Morse characters are, so the trainer
 * can offer plausible wrong answers (distractors) next to the correct one.
 *
 * The default metric is a weighted edit distance on the dot/dash patterns:
 *   - changing one element to another (dit↔dah) costs 1
 *   - inserting or removing an element (a length difference) costs 1.5
 *
 * Because length differences cost more, characters of the *same length* that
 * differ by a single element come out "closest" — which matches the mistakes
 * real CW operators actually make (e.g. X `-..-` vs B `-...`, or X vs Y `-.--`).
 *
 * Translated from MorseKit/Distance.swift. Swift `enum`-namespace → `object`;
 * the `swap(&prev, &curr)` row-swap becomes a plain reassignment of `var`s.
 */
object MorseDistance {

    /** Weighted edit distance between two Morse pattern strings. */
    fun distance(
        a: String,
        b: String,
        substitutionCost: Double = 1.0,
        indelCost: Double = 1.5
    ): Double {
        val n = a.length
        val m = b.length
        if (n == 0) return m * indelCost
        if (m == 0) return n * indelCost

        var prev = DoubleArray(m + 1) { it * indelCost }
        var curr = DoubleArray(m + 1)

        for (i in 1..n) {
            curr[0] = i * indelCost
            for (j in 1..m) {
                val sub = prev[j - 1] + (if (a[i - 1] == b[j - 1]) 0.0 else substitutionCost)
                val del = prev[j] + indelCost
                val ins = curr[j - 1] + indelCost
                curr[j] = minOf(sub, del, ins)
            }
            val tmp = prev; prev = curr; curr = tmp
        }
        return prev[m]
    }

    /** Distance between two characters by their Morse patterns. */
    fun distance(a: Char, b: Char): Double {
        val pa = MorseCode.pattern(a) ?: return Double.POSITIVE_INFINITY
        val pb = MorseCode.pattern(b) ?: return Double.POSITIVE_INFINITY
        return distance(pa, pb)
    }

    /**
     * The [count] characters that sound closest to [target], drawn from [pool],
     * nearest first. The target itself is never included.
     */
    fun nearestNeighbors(target: Char, pool: List<Char>, count: Int): List<Char> =
        pool.filter { it != target }
            .map { it to distance(target, it) }
            // Sort by distance, then alphabetically so ties are deterministic.
            .sortedWith(compareBy({ it.second }, { it.first }))
            .take(count)
            .map { it.first }
}
