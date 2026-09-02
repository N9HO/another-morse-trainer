package app.anothermorsetrainer.morsekit

import kotlin.random.Random

/**
 * The decision-making core of the trainer. It chooses which character to
 * play next, builds the multiple-choice question (correct answer plus the
 * closest-sounding distractors), records each result with its TTR, and grows
 * the active character set as the learner gets fast and accurate.
 *
 * It holds no audio or UI — that lives in the app — so all of this logic can
 * be unit-tested on its own.
 *
 * Translated from MorseKit/TrainerEngine.swift. Swift's injectable
 * `RandomNumberGenerator` becomes Kotlin's `kotlin.random.Random` (default
 * `Random.Default`, or a seeded `Random(seed)` in tests); `private(set) var`
 * becomes `var … private set`. The `Codable` [Snapshot] is simplified to a
 * plain holder + [snapshot]/[restore] (kotlinx.serialization would wire real
 * persistence, mirroring the Swift custom encode/decode).
 */
class TrainerEngine(
    var config: Config = Config(),
    seedCount: Int = 2,
    private val rng: Random = Random.Default
) : QuizSource {

    data class Config(
        var wpm: Double = 33.0,
        var ttrThreshold: Double = 1.0,
        /**
         * The most answer choices to ever show. Early on the learner sees
         * fewer — the count grows with how many characters they've met (see
         * [exposedCharacters]) and tops out here.
         */
        var optionCount: Int = 4,
        var masteryWindow: Int = 5,
        var requiredAccuracy: Double = 0.9
    )

    /** A single multiple-choice round. */
    data class Question(
        val target: Char,
        val options: List<Char>   // includes `target`, presentation order
    )

    /** The result of recording one answer. */
    data class Outcome(
        val correct: Boolean,
        /** A newly introduced character, if this answer triggered progression. */
        val addedCharacter: Char?
    )

    var activeCharacters: List<Char>
        private set
    var stats: MutableMap<Char, CharacterStats>
        private set

    /**
     * The introduction order for new characters: the Koch order by default,
     * with any opted-in punctuation appended by the app so it's introduced
     * after the core set. Not part of the snapshot — it's derived from the
     * learner's settings on every launch.
     */
    var studyOrder: List<Char> = MorseCode.kochOrder

    /**
     * Characters the learner has actually *met* — either presented at least
     * once as a question, or granted up front by a declared proficiency level.
     * Answer choices are drawn only from this set, and early on the number of
     * choices grows with it: a single option until a second character has been
     * seen, two until a third, and so on up to [Config.optionCount].
     */
    var exposedCharacters: MutableSet<Char> = mutableSetOf()
        private set

    /**
     * Which character the learner picked when they got one wrong — the raw
     * material for the confusion-pair drills.
     */
    var confusions: ConfusionMatrix = ConfusionMatrix()
        private set

    /**
     * Remembers the most recently handed-out question so the QuizSource
     * `record(choice, ttr)` bridge can score it.
     */
    internal var lastQuestion: Question? = null

    val timing: MorseTiming get() = MorseTiming(config.wpm)

    init {
        val seed = MorseCode.kochOrder.take(maxOf(1, seedCount))
        activeCharacters = seed
        stats = seed.associate { it to CharacterStats(it) }.toMutableMap()
    }

    // ---- Question generation ----

    /**
     * Build the next question: a target weighted toward characters that are
     * missed or slow, plus its closest-sounding distractors.
     */
    fun nextQuestion(): Question {
        val target = pickTarget()
        val distractors = pickDistractors(target)
        val options = (listOf(target) + distractors).shuffled(rng)
        return Question(target, options)
    }

    /**
     * Higher weight = more likely to be drilled. New, missed, and slow
     * characters are favored so practice goes where it's needed.
     */
    fun weight(character: Char): Double {
        val s = stats[character] ?: return 1.0
        val recent = s.recent(config.masteryWindow)
        if (recent.isEmpty()) return 4.0   // unpracticed → drill it
        var w = 1.0
        w += (1.0 - s.accuracy(config.masteryWindow)) * 4.0   // misses hurt
        val median = s.medianTTR(config.masteryWindow)
        if (median != null) {
            if (median > config.ttrThreshold) w += minOf(median / config.ttrThreshold, 3.0)
        } else {
            w += 3.0   // no correct answers recently → needs work
        }
        return w
    }

    private fun pickTarget(): Char {
        val weights = activeCharacters.map { weight(it) }
        val total = weights.sum()
        if (total <= 0) return activeCharacters.random(rng)
        var roll = rng.nextDouble(total)
        for ((char, w) in activeCharacters.zip(weights)) {
            if (roll < w) return char
            roll -= w
        }
        return activeCharacters.last()
    }

    private fun pickDistractors(target: Char): List<Char> {
        // Only ever offer characters the learner has already met. The target is
        // counted as met (this very question introduces it if it's new), so the
        // number of options grows from one and tops out at `optionCount`.
        val exposedPool = activeCharacters.filter { it != target && it in exposedCharacters }
        val cap = maxOf(1, config.optionCount)
        val optionsToShow = minOf(cap, exposedPool.size + 1)   // +1 for the target itself
        val needed = maxOf(0, optionsToShow - 1)
        return MorseDistance.nearestNeighbors(target, exposedPool, needed)
    }

    // ---- Recording answers & progression ----

    fun record(answer: Char, question: Question, ttr: Double): Outcome {
        val correct = noteAttempt(answer, question.target, ttr)
        val added = advanceIfReady()
        return Outcome(correct, added)
    }

    /**
     * Record one attempt's outcome — updating the character's stats and, on a
     * miss, the confusion matrix — *without* advancing the Koch ladder. Returns
     * whether the answer was correct. Used by [record] and by review drills
     * (e.g. the confusion-pair quiz) that shouldn't graduate new characters.
     */
    fun noteAttempt(answer: Char, target: Char, ttr: Double): Boolean {
        exposedCharacters.add(target)   // presenting it counts as meeting it
        val correct = answer == target
        stats.getOrPut(target) { CharacterStats(target) }.record(correct, ttr)
        if (!correct) confusions.record(target, answer)
        return correct
    }

    /**
     * Ease a confused pairing after a correct recognition (used by the
     * confusion-pair drill so sorted-out pairs fade from rotation).
     */
    fun easeConfusion(target: Char, chosen: Char) {
        confusions.ease(target, chosen)
    }

    /**
     * Once every active character is mastered, introduce the next character
     * from the [studyOrder] (one at a time). Returns the character added, if any.
     */
    fun advanceIfReady(): Char? {
        if (!allActiveMastered) return null
        val next = studyOrder.firstOrNull { it !in activeCharacters }
            ?: return null   // whole alphabet learned 🎉
        activeCharacters = activeCharacters + next
        stats[next] = CharacterStats(next)
        return next
    }

    /**
     * Replace the active character set (e.g. when the learner picks a
     * proficiency level). Stats for new characters are created; existing
     * stats are preserved so prior practice isn't lost.
     */
    fun setActiveCharacters(characters: List<Char>) {
        activeCharacters = characters
        for (c in characters) if (stats[c] == null) stats[c] = CharacterStats(c)
    }

    /**
     * Replace the set of characters considered "already met." A declared
     * proficiency level front-loads its characters here so the learner sees a
     * full set of choices right away; a true beginner starts empty and builds
     * up one option at a time as each character is introduced.
     */
    fun setExposedCharacters(characters: List<Char>) {
        exposedCharacters = characters.toMutableSet()
    }

    /** Add one character to the active set (e.g. opting into a punctuation mark). */
    fun addActiveCharacter(character: Char) {
        if (character in activeCharacters) return
        activeCharacters = activeCharacters + character
        if (stats[character] == null) stats[character] = CharacterStats(character)
    }

    /**
     * Remove one character from the active set (e.g. opting back out). The
     * character's stats are kept, so it comes back with its history intact.
     */
    fun removeActiveCharacter(character: Char) {
        activeCharacters = activeCharacters.filter { it != character }
    }

    /**
     * Set the introduction order and reconcile the active set with it: any
     * pickable punctuation mark the learner has already earned but has now
     * opted back out of is removed, so it stops being drilled (issue #133).
     * Returns the characters removed, in active-set order.
     *
     * Only [MorseCode.pickablePunctuation] is ever removed — the Koch core
     * (including `?`) is never touched, and a mark that was never earned is
     * simply not there to remove. Opting back *in* changes nothing here: the
     * mark rejoins the ladder to be earned again, and its [CharacterStats]
     * entry (kept by [removeActiveCharacter]) is waiting for it. Pinned by
     * `fixtures/ladder.json`'s `optOutCases` on both ports.
     */
    fun applyStudyOrder(order: List<Char>): List<Char> {
        studyOrder = order
        val removed = activeCharacters.filter { it in MorseCode.pickablePunctuation && it !in order }
        // NEGATIVE CONTROL: removal skipped (to be reverted)
        return removed
    }

    val allActiveMastered: Boolean
        get() = activeCharacters.all {
            stats[it]?.isMastered(
                ttrThreshold = config.ttrThreshold,
                window = config.masteryWindow,
                requiredAccuracy = config.requiredAccuracy
            ) ?: false
        }

    // ---- Persistence ----

    /** A snapshot of progress for saving (stand-in for Swift's `Codable`). */
    data class Snapshot(
        val activeCharacters: List<Char>,
        val stats: List<CharacterStats>,
        val confusions: Map<String, Int> = emptyMap(),
        /**
         * `null` means the saved snapshot predates exposure tracking, which is
         * different from a snapshot that recorded an empty set. Swift gets this
         * distinction free from `decodeIfPresent`; here it has to be explicit,
         * or a brand-new learner who saves before answering anything is
         * restored as if every active character had already been met.
         */
        val exposedCharacters: Set<Char>? = null
    )

    val snapshot: Snapshot
        get() = Snapshot(
            activeCharacters = activeCharacters,
            stats = stats.values.map { it.copy() },
            confusions = confusions.snapshot(),
            exposedCharacters = exposedCharacters
        )

    fun restore(snapshot: Snapshot) {
        activeCharacters = snapshot.activeCharacters
        stats = snapshot.stats.associateBy({ it.character }, { it.copy() }).toMutableMap()
        confusions = ConfusionMatrix().apply { restore(snapshot.confusions) }
        // Snapshots predating exposure tracking (null, not empty): treat every
        // active character as already met, so existing learners keep their full
        // set of choices. An empty-but-present set is a real beginner state and
        // must survive, or the "choices grow as you meet characters" onboarding
        // is skipped the first time a fresh learner relaunches.
        exposedCharacters = snapshot.exposedCharacters?.toMutableSet()
            ?: snapshot.activeCharacters.toMutableSet()
    }

    // ---- QuizSource bridge (was TrainerEngine+Quiz.swift) ----
    // Swift attached these via an extension; Kotlin can't satisfy an interface
    // from an extension, so the bridge lives here on the class.

    override fun nextDrill(): Drill {
        val q = nextQuestion()
        lastQuestion = q
        return Drill(
            playable = MorseItem.Playable.Text(q.target.toString()),
            options = q.options.map { it.toString() },
            correct = q.target.toString(),
            revealPrimary = q.target.toString(),
            revealSecondary = MorseCode.pattern(q.target) ?: ""
        )
    }

    override fun record(choice: String, ttr: Double): DrillOutcome {
        val q = lastQuestion ?: return DrillOutcome(false, null)
        val answer = choice.firstOrNull() ?: return DrillOutcome(false, null)
        val outcome = record(answer = answer, question = q, ttr = ttr)
        return DrillOutcome(outcome.correct, outcome.addedCharacter?.toString())
    }

    override val summary: String
        get() {
            val n = activeCharacters.size
            return "$n char${if (n == 1) "" else "s"}"
        }
}
