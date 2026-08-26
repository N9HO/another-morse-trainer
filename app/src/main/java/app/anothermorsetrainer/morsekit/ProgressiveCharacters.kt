package app.anothermorsetrainer.morsekit

import kotlin.random.Random

/**
 * The full "Characters" track. It starts with the single-character Koch ladder
 * (delegated to [TrainerEngine]) and, once every character is mastered, keeps
 * progressing on its own:
 *
 *   singles → pairs → triples → words & call signs
 *
 * In the multi-character stages it plays groups of learned characters and
 * offers sound-alike groups as distractors, and it mixes in prosigns
 * (`<AR>`, `<SK>`, …) to recognize by ear. Each stage advances once recent
 * accuracy and time-to-recognize are good enough.
 *
 * Translated from MorseKit/ProgressiveCharacters.swift. The private
 * `enum LastKind` with associated values becomes a Kotlin `sealed class`;
 * Swift `for x in xs where cond` loops become `for` + an early `break` once
 * the option cap is reached.
 */
class ProgressiveCharacters(
    val engine: TrainerEngine,
    private val rng: Random = Random.Default
) : QuizSource {

    enum class Stage {
        Singles, Pairs, Triples, Phrases;

        val displayName: String
            get() = when (this) {
                Singles -> "Characters"
                Pairs -> "Pairs"
                Triples -> "Triples"
                Phrases -> "Words & Call Signs"
            }

        val groupSize: Int get() = if (this == Pairs) 2 else if (this == Triples) 3 else 0
    }

    var stage: Stage = Stage.Singles
        private set

    /**
     * A learner-chosen stage. While set, the track serves exactly this stage
     * and never auto-advances (or auto-leaves singles), so picking "Characters"
     * keeps serving characters even with the whole ladder mastered (issue #51).
     * `null` = automatic progression, the default.
     */
    var pinnedStage: Stage? = null
        private set

    private val prosignItems = MorseData.prosignTokenItems
    private val phraseItems = MorseData.wordAndCallSignItems

    /** One scored round within an advanced stage. */
    private data class StageResult(val correct: Boolean, val ttr: Double)

    /** Rolling results within the current advanced stage (for advancement). */
    private val stageResults = mutableListOf<StageResult>()
    private val stageWindow = 12

    /** What kind of drill we last handed out, so [record] scores it correctly. */
    private sealed class LastKind {
        object Singles : LastKind()
        data class Group(val answer: String) : LastKind()
        data class Item(val answer: String) : LastKind()
    }

    private var lastKind: LastKind = LastKind.Singles

    private val ttrThreshold: Double get() = engine.config.ttrThreshold

    // ---- QuizSource ----

    override val summary: String
        get() = if (stage == Stage.Singles) engine.summary else stage.displayName

    override fun nextDrill(): Drill {
        // Leave the singles stage automatically once the ladder is complete —
        // unless the learner pinned a stage, which always holds.
        if (pinnedStage == null && stage == Stage.Singles && singlesComplete) {
            advance(Stage.Pairs)
        }

        return when (stage) {
            Stage.Singles -> {
                lastKind = LastKind.Singles
                engine.nextDrill()
            }
            Stage.Pairs, Stage.Triples -> {
                // Occasionally drill a prosign by ear instead of a character group.
                if (shouldDoProsign()) prosignDrill() else groupDrill(stage.groupSize)
            }
            Stage.Phrases -> {
                if (shouldDoProsign()) prosignDrill() else phraseDrill()
            }
        }
    }

    override fun record(choice: String, ttr: Double): DrillOutcome {
        return when (val kind = lastKind) {
            is LastKind.Singles -> {
                val outcome = engine.record(choice = choice, ttr = ttr)
                // The very answer that completes the ladder unlocks the pairs stage.
                if (pinnedStage == null && stage == Stage.Singles && singlesComplete) {
                    advance(Stage.Pairs)
                    DrillOutcome(outcome.correct, Stage.Pairs.displayName)
                } else {
                    outcome
                }
            }
            is LastKind.Group -> scoreStage(kind.answer, choice, ttr)
            is LastKind.Item -> scoreStage(kind.answer, choice, ttr)
        }
    }

    private fun scoreStage(answer: String, choice: String, ttr: Double): DrillOutcome {
        val correct = choice == answer
        stageResults.add(StageResult(correct, ttr))
        if (stageResults.size > stageWindow) stageResults.removeAt(0)
        val next = if (pinnedStage == null) advanceIfStageMastered() else null
        return DrillOutcome(correct, next?.displayName)
    }

    // ---- Stage advancement ----

    private val singlesComplete: Boolean
        get() = engine.allActiveMastered &&
            engine.studyOrder.all { it in engine.activeCharacters }

    /** Time allowed to recognize, in seconds, scaled to the current stage. */
    private val stageAllowedTTR: Double
        get() = when (stage) {
            Stage.Singles -> ttrThreshold
            Stage.Pairs -> ttrThreshold * 2.5
            Stage.Triples -> ttrThreshold * 3.5
            Stage.Phrases -> ttrThreshold * 4.0
        }

    private fun advanceIfStageMastered(): Stage? {
        if (stageResults.size < stageWindow) return null
        val accuracy = stageResults.count { it.correct }.toDouble() / stageResults.size
        if (accuracy < 0.9) return null
        val times = stageResults.filter { it.correct }.map { it.ttr }.sorted()
        if (times.isEmpty()) return null
        val median = times[times.size / 2]
        if (median > stageAllowedTTR) return null

        return when (stage) {
            Stage.Pairs -> { advance(Stage.Triples); Stage.Triples }
            Stage.Triples -> { advance(Stage.Phrases); Stage.Phrases }
            Stage.Singles, Stage.Phrases -> null   // phrases is the final stage
        }
    }

    private fun advance(newStage: Stage) {
        stage = newStage
        stageResults.clear()
    }

    /**
     * Restart the ladder at the single-character stage (e.g. after the user
     * changes their proficiency level). Also returns to automatic progression —
     * a declared restart supersedes any earlier stage choice.
     */
    fun resetToSingles() {
        pinnedStage = null
        stage = Stage.Singles
        stageResults.clear()
    }

    /**
     * Hold the track at a learner-chosen stage. Unlike [jumpToStage] this does
     * not touch the active character set: group drills draw from whatever the
     * learner has studied, and the phrase stage uses its own fixed pool.
     */
    fun pin(newStage: Stage) {
        pinnedStage = newStage
        stage = newStage
        stageResults.clear()
    }

    /** Return to automatic progression, continuing from the current stage. */
    fun unpin() {
        pinnedStage = null
    }

    /**
     * Developer/testing aid: jump directly to a stage. For the multi-character
     * stages it makes sure there are enough learned characters to form varied
     * groups (expanding to the full letter+number set if needed). A jump
     * previews automatic progression from that stage, so it clears any pin —
     * otherwise the pin would claim one stage while the track serves another.
     */
    fun jumpToStage(newStage: Stage) {
        if (newStage != Stage.Singles && engine.activeCharacters.size < 10) {
            engine.setActiveCharacters(MorseCode.kochOrder)
            engine.setExposedCharacters(MorseCode.kochOrder)   // dev jump: treat all as met
        }
        pinnedStage = null
        stage = newStage
        stageResults.clear()
    }

    // ---- Drill builders ----

    private fun shouldDoProsign(): Boolean = rng.nextDouble() < 0.25

    private fun groupDrill(size: Int): Drill {
        val pool = engine.activeCharacters
        val cap = maxOf(1, engine.config.optionCount)
        val group = (0 until size).map { pool.random(rng) }.joinToString("")
        val options = mutableListOf(group)
        var attempts = 0
        while (options.size < cap && attempts < cap * 10) {
            attempts++
            val candidate = mutate(group, pool)
            if (candidate != group && candidate !in options) options.add(candidate)
        }
        // Fallback: pad with fresh random groups if mutation didn't find enough.
        // A small studied set can have fewer possible groups than the option
        // cap (two characters form only four pairs — reachable with a pinned
        // stage), so give up after enough tries instead of spinning forever;
        // a shorter option list still plays fine.
        var padAttempts = 0
        while (options.size < cap && padAttempts < cap * 20) {
            padAttempts++
            val g = (0 until size).map { pool.random(rng) }.joinToString("")
            if (g !in options) options.add(g)
        }
        val shuffled = options.shuffled(rng)
        lastKind = LastKind.Group(group)
        return Drill(
            playable = MorseItem.Playable.Text(group), options = shuffled, correct = group,
            revealPrimary = group, revealSecondary = ""
        )
    }

    /** Change one character of [group] to a sound-alike, yielding a confusable group. */
    private fun mutate(group: String, pool: List<Char>): String {
        val chars = group.toCharArray()
        val pos = rng.nextInt(chars.size)
        val neighbors = MorseDistance.nearestNeighbors(chars[pos], pool, 3)
        val replacement = neighbors.randomOrNull(rng)
        if (replacement != null) chars[pos] = replacement
        return String(chars)
    }

    private fun prosignDrill(): Drill {
        val cap = maxOf(1, engine.config.optionCount)
        val target = prosignItems.random(rng)
        val options = mutableListOf(target.answer)
        val others = prosignItems
            .filter { it.id != target.id }
            .sortedBy { MorseDistance.distance(target.soundKey, it.soundKey) }
        for (item in others) {
            if (options.size >= cap) break
            if (item.answer !in options) options.add(item.answer)
        }
        val shuffled = options.shuffled(rng)
        lastKind = LastKind.Item(target.answer)
        return Drill(
            playable = target.playable, options = shuffled, correct = target.answer,
            revealPrimary = target.display,
            revealSecondary = MorseData.prosigns.firstOrNull { it.name == target.id }?.meaning ?: ""
        )
    }

    private fun phraseDrill(): Drill {
        val cap = maxOf(1, engine.config.optionCount)
        val target = phraseItems.random(rng)
        val options = mutableListOf(target.answer)
        val others = phraseItems
            .filter { it.id != target.id }
            .sortedBy { MorseDistance.distance(target.soundKey, it.soundKey) }
        for (item in others) {
            if (options.size >= cap) break
            if (item.answer !in options) options.add(item.answer)
        }
        val shuffled = options.shuffled(rng)
        lastKind = LastKind.Item(target.answer)
        return Drill(
            playable = target.playable, options = shuffled, correct = target.answer,
            revealPrimary = target.display, revealSecondary = ""
        )
    }

    // ---- Persistence ----

    data class Snapshot(
        val engine: TrainerEngine.Snapshot,
        val stage: Stage,
        /** A held stage choice survives restarts (absent on older saves). */
        val pinnedStage: Stage? = null
    )

    val snapshot: Snapshot
        get() = Snapshot(engine = engine.snapshot, stage = stage, pinnedStage = pinnedStage)

    fun restore(snapshot: Snapshot) {
        engine.restore(snapshot.engine)
        stage = snapshot.stage
        pinnedStage = snapshot.pinnedStage
        stageResults.clear()
    }
}
