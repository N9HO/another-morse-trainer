package app.anothermorsetrainer.morsekit

import kotlin.random.Random

/**
 * Generates printable *sending* practice sheets: pages of random character
 * groups you read aloud on the air or key on a paddle. This is the send-side
 * companion to the recognition quizzes — the app can't grade your fist, so the
 * value is a fresh, well-mixed sheet drawn from exactly the characters you've
 * studied (optionally weighted toward your weak ones), ready to share or print.
 *
 * Mirrors the "Sending Drills" feature on cwsignals.com — basic drills from the
 * letters you know, a personalized drill that leans on your weak spots, and a
 * numbers-and-punctuation drill — but built from this app's own progress.
 *
 * Translated from MorseKit/SendingDrill.swift.
 */
data class SendingDrill(
    val kind: Kind,
    val groupSize: Int,
    val rows: List<String>
) {

    /** Which pool the groups are drawn from. */
    enum class Kind(val code: String) {
        /** Even mix of the letters/characters you've studied. */
        Studied("studied"),

        /**
         * Studied characters, weighted toward the ones you answer slowest or
         * least accurately, so practice lands where it helps most.
         */
        Personalized("personalized"),

        /** Digits and common CW punctuation — the characters drills usually skip. */
        NumbersAndPunctuation("numbersAndPunctuation");

        val id: String get() = code

        val title: String
            get() = when (this) {
                Studied -> "Studied"
                Personalized -> "Personalized"
                NumbersAndPunctuation -> "Numbers & punctuation"
            }

        val blurb: String
            get() = when (this) {
                Studied -> "An even mix of every character you've learned so far."
                Personalized -> "Weighted toward the characters you're slowest or least accurate on."
                NumbersAndPunctuation -> "Digits and the punctuation that recognition drills tend to skip."
            }

        companion object {
            val allCases: List<Kind> = entries.toList()
        }
    }

    /**
     * The whole sheet as one plain-text block — a title, a description line, the
     * group rows, and a footer — suitable for the share sheet or printing.
     */
    fun plainText(title: String = "CW Sending Practice", subtitle: String? = null): String {
        val lines = mutableListOf(title)
        if (subtitle != null) lines.add(subtitle)
        lines.add("${kind.title} · ${rows.size} lines · groups of $groupSize")
        lines.add("")
        lines.addAll(rows)
        lines.add("")
        lines.add("Read each group and key it on your paddle. — Another Morse Trainer")
        return lines.joinToString("\n")
    }

    companion object {
        /**
         * The digits and punctuation used by the numbers-and-punctuation drill —
         * the marks an operator actually sends (period, comma, query, slash, the
         * `=` break, plus/`AR`), kept to what's worth practising.
         */
        val numberPunctuationPool: List<Char> = "0123456789".toList() + ".,?/=+".toList()

        /**
         * Build a sheet.
         *
         * @param kind which pool to draw from.
         * @param studied the characters the learner has studied (used by
         *   [Kind.Studied] and [Kind.Personalized]).
         * @param weights per-character difficulty weights (higher = drill more),
         *   used only by [Kind.Personalized]. Missing characters default to 1.
         * @param groupCount how many groups to emit (e.g. 50).
         * @param groupSize characters per group (classic CW practice uses 5).
         * @param groupsPerRow how many groups to lay out per line.
         * @param rng random source (injectable for testing).
         */
        fun generate(
            kind: Kind,
            studied: List<Char>,
            weights: Map<Char, Double> = emptyMap(),
            groupCount: Int = 50,
            groupSize: Int = 5,
            groupsPerRow: Int = 5,
            rng: Random = Random.Default
        ): SendingDrill {
            val pool: List<Char> = when (kind) {
                Kind.Studied, Kind.Personalized ->
                    if (studied.isEmpty()) "ETIANMSURWDKGO".toList() else studied
                Kind.NumbersAndPunctuation -> numberPunctuationPool
            }

            // For personalized drills, build a weighted bag so weak characters appear
            // proportionally more often; otherwise every character is equally likely.
            val weighted: List<Pair<Char, Double>> =
                if (kind == Kind.Personalized) pool.map { it to maxOf(0.1, weights[it] ?: 1.0) }
                else pool.map { it to 1.0 }

            fun pick(): Char {
                val total = weighted.sumOf { it.second }
                var t = rng.nextDouble() * total
                for ((ch, w) in weighted) {
                    t -= w
                    if (t < 0) return ch
                }
                return weighted.lastOrNull()?.first ?: 'E'
            }

            val size = maxOf(1, groupSize)
            val groups = ArrayList<String>(maxOf(0, groupCount))
            repeat(maxOf(0, groupCount)) {
                groups.add(String(CharArray(size) { pick() }))
            }

            val perRow = maxOf(1, groupsPerRow)
            val rows = mutableListOf<String>()
            var i = 0
            while (i < groups.size) {
                rows.add(groups.subList(i, minOf(i + perRow, groups.size)).joinToString(" "))
                i += perRow
            }

            return SendingDrill(kind = kind, groupSize = size, rows = rows)
        }
    }
}
