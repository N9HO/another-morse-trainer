package app.anothermorsetrainer.morsekit

import kotlin.random.Random

/**
 * A quiz over a fixed set of items (words, abbreviations, or prosigns). Like
 * the character engine it plays one item, offers the correct answer plus the
 * three closest-sounding distractors, tracks time-to-recognize, and drills
 * harder on the items you miss or are slow on — but it doesn't "graduate"
 * items the way the Koch character ladder does.
 *
 * Translated from MorseKit/PhraseQuiz.swift. Swift's injectable
 * `RandomNumberGenerator` becomes Kotlin's `kotlin.random.Random` (default
 * `Random.Default`, or a seeded `Random(seed)` in tests).
 */
class PhraseQuiz(
    val name: String,
    val items: List<MorseItem>,
    var config: Config = Config(),
    /** What the pool holds, for the "N …" session readout. Defaults to the
     *  quiz name, which reads fine for "words"/"abbreviations" but turned
     *  mode-named quizzes into gibberish ("155 head copy" — issue #61). */
    summaryNoun: String? = null,
    private val rng: Random = Random.Default
) : QuizSource {

    private val summaryNoun: String = summaryNoun ?: name.lowercase()

    data class Config(
        var ttrThreshold: Double = 1.5,
        var optionCount: Int = 4
    )

    private val attemptsById: MutableMap<String, MutableList<CharacterStats.Attempt>> = mutableMapOf()

    /**
     * Items the learner has actually heard at least once. Choices are drawn
     * only from here, and their number grows with it (just the answer at first)
     * up to [Config.optionCount].
     */
    private val exposedIds: MutableSet<String> = mutableSetOf()
    private var lastItem: MorseItem? = null

    // MARK: QuizSource

    override val summary: String
        get() = "${items.size} $summaryNoun"

    override fun nextDrill(): Drill {
        val target = pickTarget()
        lastItem = target
        exposedIds.add(target.id)   // hearing it counts as meeting it
        val options = buildOptions(target)
        return Drill(
            playable = target.playable,
            options = options,
            correct = target.answer,
            revealPrimary = target.display,
            revealSecondary = if (target.answer == target.display) "" else target.answer
        )
    }

    override fun record(choice: String, ttr: Double): DrillOutcome {
        val item = lastItem ?: return DrillOutcome(correct = false, unlocked = null)
        val correct = choice == item.answer
        val history = attemptsById.getOrPut(item.id) { mutableListOf() }
        history.add(CharacterStats.Attempt(correct = correct, ttr = ttr))
        if (history.size > CharacterStats.historyLimit) {
            history.removeAt(0)
        }
        return DrillOutcome(correct = correct, unlocked = null)
    }

    // MARK: Selection

    /**
     * Distinct answer labels: correct one plus closest-sounding others. Only
     * items the learner has already heard are eligible, and the number of
     * choices grows with that set up to [Config.optionCount].
     */
    private fun buildOptions(target: MorseItem): List<String> {
        val cap = maxOf(1, config.optionCount)
        val pool = items.filter {
            exposedIds.contains(it.id) && it.id != target.id && it.answer != target.answer
        }
        val optionsToShow = minOf(cap, pool.size + 1)   // +1 for the target itself
        val needed = maxOf(0, optionsToShow - 1)
        val others = pool
            .map { it.answer to MorseDistance.distance(target.soundKey, it.soundKey) }
            .sortedWith(compareBy({ it.second }, { it.first }))

        val distractors = mutableListOf<String>()
        for (candidate in others) {
            if (distractors.size >= needed) break
            if (!distractors.contains(candidate.first)) distractors.add(candidate.first)
        }
        val options = (listOf(target.answer) + distractors).toMutableList()
        return options.shuffled(rng)
    }

    private fun pickTarget(): MorseItem {
        val weights = items.map { weight(it) }
        val total = weights.sum()
        if (total <= 0) return items.random(rng)
        var roll = rng.nextDouble(total)
        for ((item, w) in items.zip(weights)) {
            if (roll < w) return item
            roll -= w
        }
        return items.last()
    }

    /** Favor items that are new, missed, or slow. */
    fun weight(item: MorseItem): Double {
        val recent = (attemptsById[item.id] ?: emptyList()).takeLast(5)
        if (recent.isEmpty()) return 4.0
        val correct = recent.count { it.correct }
        val accuracy = correct.toDouble() / recent.size
        var w = 1.0 + (1.0 - accuracy) * 4.0
        val correctTimes = recent.filter { it.correct }.map { it.ttr }.sorted()
        if (correctTimes.isEmpty()) {
            w += 3.0
        } else {
            val median = correctTimes[correctTimes.size / 2]
            if (median > config.ttrThreshold) w += minOf(median / config.ttrThreshold, 3.0)
        }
        return w
    }
}
