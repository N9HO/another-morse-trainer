package app.anothermorsetrainer.morsekit

import kotlin.random.Random

/**
 * A targeted review mode that drills the character pairs you actually confuse.
 *
 * It reads the confusion data the Koch [TrainerEngine] has been collecting
 * during normal practice (which character you picked when you got one wrong),
 * then builds drills that deliberately place a confusable look-alike on the
 * buttons next to the correct answer — weighted toward the mix-ups that happen
 * most. Getting one right eases that pairing, so resolved confusions naturally
 * drop out of rotation.
 *
 * Before any errors have been recorded it falls back to your slowest active
 * character paired with its nearest-sounding neighbor, so the mode is always
 * useful.
 *
 * Translated from MorseKit/ConfusionQuiz.swift. Swift's injectable
 * `RandomNumberGenerator` becomes Kotlin's `kotlin.random.Random`.
 */
class ConfusionQuiz(
    val engine: TrainerEngine,
    private val rng: Random = Random.Default
) : QuizSource {

    private var lastTarget: Char? = null
    private var lastConfuser: Char? = null

    // MARK: - QuizSource

    override val summary: String
        get() {
            val n = engine.confusions.pairs().size
            return if (n == 0) "no mix-ups yet" else "$n mix-up${if (n == 1) "" else "s"}"
        }

    override fun nextDrill(): Drill {
        val (target, confuser) = pickPair()
        lastTarget = target
        lastConfuser = confuser

        val options = mutableListOf(target.toString())
        if (confuser != null) options.add(confuser.toString())

        // Fill the remaining slots with the nearest-sounding characters the
        // learner has already met, so the lineup stays plausibly confusable
        // without ever introducing an unfamiliar character.
        val cap = maxOf(1, engine.config.optionCount)
        val pool = engine.activeCharacters.filter {
            it != target && it != confuser && engine.exposedCharacters.contains(it)
        }
        val extras = MorseDistance.nearestNeighbors(target, pool, maxOf(0, cap - options.size))
        options.addAll(extras.map { it.toString() })

        return Drill(
            playable = MorseItem.Playable.Text(target.toString()),
            options = options.shuffled(rng),
            correct = target.toString(),
            revealPrimary = target.toString(),
            revealSecondary = MorseCode.pattern(target) ?: ""
        )
    }

    override fun record(choice: String, ttr: Double): DrillOutcome {
        val target = lastTarget ?: return DrillOutcome(correct = false, unlocked = null)
        val answer = choice.firstOrNull() ?: return DrillOutcome(correct = false, unlocked = null)
        // Record the attempt without graduating new Koch characters — this is
        // review, not progression. A correct call eases the drilled pairing.
        val correct = engine.noteAttempt(answer = answer, target = target, ttr = ttr)
        val confuser = lastConfuser
        if (correct && confuser != null) {
            engine.easeConfusion(target = target, chosen = confuser)
        }
        return DrillOutcome(correct = correct, unlocked = null)
    }

    // MARK: - Selection

    /**
     * Choose a `(target, confuser)` to drill. Prefer real confusions, weighted
     * by how often they happen; if there are none yet, fall back to the slowest
     * active character paired with its nearest-sounding neighbor.
     */
    private fun pickPair(): Pair<Char, Char?> {
        val entries = engine.confusions.entries()
        if (entries.isNotEmpty()) {
            val total = entries.sumOf { it.count }
            var roll = rng.nextInt(total)
            for (e in entries) {
                if (roll < e.count) return e.target to e.chosen
                roll -= e.count
            }
            return entries[0].target to entries[0].chosen
        }
        val target = slowestActiveCharacter() ?: engine.activeCharacters.firstOrNull() ?: 'E'
        val neighbor = MorseDistance.nearestNeighbors(target, engine.activeCharacters, 1).firstOrNull()
        return target to neighbor
    }

    /**
     * The active character with the highest median TTR (unpracticed characters,
     * whose TTR is unknown, sort as "slow" so they get attention too).
     */
    private fun slowestActiveCharacter(): Char? =
        engine.activeCharacters.maxByOrNull {
            engine.stats[it]?.medianTTR(window = CharacterStats.historyLimit) ?: Double.POSITIVE_INFINITY
        }
}
