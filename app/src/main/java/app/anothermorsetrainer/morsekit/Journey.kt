package app.anothermorsetrainer.morsekit

import kotlin.random.Random

/**
 * The "Journey" track: a gamified, level-based path through Morse — inspired by
 * the level ladders in apps like Morse Mania, but built on the same [Drill] /
 * [QuizSource] plumbing as every other AMT mode.
 *
 * Where the `Characters` ladder is *adaptive* (it auto-advances when your recent
 * accuracy and speed are good enough), the Journey is *explicit and gamey*:
 *
 *   • A fixed sequence of numbered levels, each introducing two new symbols and
 *     mixing in everything learned before.
 *   • A per-level progress bar that fills on a correct answer and *drains* on a
 *     miss — you have to fight the bar up to a threshold to clear the level.
 *   • Clearing a level unlocks the next one; you climb a single visible map that
 *     runs letters → numbers → punctuation → prosigns → Q-codes →
 *     abbreviations → words → call signs.
 *
 * Translated from MorseKit/Journey.swift. Swift's injectable
 * `RandomNumberGenerator` becomes Kotlin's [Random]; the curriculum is computed
 * once as a lazy `val`.
 */

/**
 * One step on the journey: a small set of newly introduced items plus the full
 * cumulative pool of everything learned up to and including this level.
 */
data class JourneyLevel(
    /** 1-based position on the map. */
    val number: Int,
    /** The section this level belongs to ("Letters", "Q-Codes", …). */
    val section: String,
    /** A short title, usually the new items ("K M", "Words I"). */
    val title: String,
    /** The items introduced for the first time at this level (drilled hardest). */
    val newItems: List<MorseItem>,
    /** Everything learnable at this level: this level's new items plus all earlier ones. */
    val pool: List<MorseItem>
)

/** Builds the ordered list of journey levels from existing MorseKit data. */
object JourneyCurriculum {

    /** A section of the curriculum: an ordered list of items and how many new ones per level. */
    private data class Section(val title: String, val items: List<MorseItem>, val perLevel: Int)

    /** Single-character items built straight from the code table, in the given order. */
    private fun charItems(chars: List<Char>): List<MorseItem> =
        chars.mapNotNull { ch ->
            if (MorseCode.pattern(ch) == null) return@mapNotNull null
            val s = ch.toString()
            MorseItem(id = "char-$s", playable = MorseItem.Playable.Text(s), answer = s, display = s)
        }

    /** The full ordered curriculum, computed once. */
    val levels: List<JourneyLevel> by lazy { build() }

    private fun build(): List<JourneyLevel> {
        val koch = MorseCode.kochOrder
        val letterDigits = koch.filter { it.isLetter() || it.isDigit() }
        val punctuation = koch.filter { !(it.isLetter() || it.isDigit()) }

        val sections = listOf(
            Section("Letters & Numbers", charItems(letterDigits), 2),
            Section("Punctuation", charItems(punctuation), 1),
            Section("Prosigns", MorseData.prosignItems, 2),
            Section("Q-Codes", MorseData.qCodeItems, 2),
            Section("Abbreviations", MorseData.abbreviationItems, 2),
            Section("Words", MorseData.topWordItems(60), 4),
            Section("Call Signs", MorseData.callSignItems, 3)
        )

        val out = mutableListOf<JourneyLevel>()
        val pool = mutableListOf<MorseItem>()   // accumulates across every section
        var number = 0

        for (section in sections) {
            if (section.items.isEmpty()) continue
            var index = 0
            while (index < section.items.size) {
                val end = minOf(index + section.perLevel, section.items.size)
                val newItems = section.items.subList(index, end).toList()
                pool.addAll(newItems)
                number += 1
                out.add(
                    JourneyLevel(
                        number = number,
                        section = section.title,
                        title = levelTitle(section.title, newItems),
                        newItems = newItems,
                        pool = pool.toList()
                    )
                )
                index = end
            }
        }
        return out
    }

    private fun levelTitle(section: String, newItems: List<MorseItem>): String {
        val labels = newItems.map { it.display }
        val joined = labels.joinToString(" ")
        return if (joined.length <= 14) joined else "$section: ${labels.firstOrNull() ?: ""}…"
    }
}

/**
 * Tunable rules for the per-level progress bar. Defaults give Morse-Mania-style
 * tension: a handful of correct answers clears a level, but each miss drains real
 * ground so you can't coast.
 */
data class JourneyScoring(
    /** Points needed to clear a level. */
    val target: Int = 100,
    /** Points added for a correct answer on a review (already-seen) item. */
    val fill: Int = 12,
    /** Bonus added when the correct item was newly introduced this level. */
    val newItemBonus: Int = 4,
    /** Points removed on a wrong answer. Set to 0 for a fill-only bar. */
    val drain: Int = 9
) {
    companion object {
        val Default = JourneyScoring()
        /** Gentler variant with no penalty for misses. */
        val FillOnly = JourneyScoring(drain = 0)
    }
}

/**
 * Drives a single journey level: hands out drills weighted toward the newest
 * symbols, scores the progress bar, and advances to the next level once the bar
 * is cleared. Conforms to [QuizSource] so the existing play→answer→reveal loop
 * drives it unchanged; the extra level/bar state is read by the app layer to
 * render the map and the progress bar.
 */
class JourneyQuiz(
    val levels: List<JourneyLevel> = JourneyCurriculum.levels,
    startIndex: Int = 0,
    var scoring: JourneyScoring = JourneyScoring.Default,
    var config: PhraseQuiz.Config = PhraseQuiz.Config(),
    private val rng: Random = Random.Default
) : QuizSource {

    /** Index into [levels] of the level currently being played. */
    var levelIndex: Int = minOf(maxOf(0, startIndex), maxOf(0, levels.size - 1))
        private set
    /** Current points on the bar for this level (0..scoring.target). */
    var points: Int = 0
        private set

    private val attemptsById = HashMap<String, MutableList<CharacterStats.Attempt>>()
    private var lastItem: MorseItem? = null

    // ---- Derived state (read by the app/UI) ----

    val level: JourneyLevel get() = levels[levelIndex]
    val levelNumber: Int get() = level.number
    val isLastLevel: Boolean get() = levelIndex >= levels.size - 1
    /** Bar fill in 0..1. */
    val progress: Double
        get() = if (scoring.target > 0) minOf(1.0, points.toDouble() / scoring.target) else 0.0

    /** Jump to a specific level (e.g. the player picked one off the map). Resets the bar; clamps. */
    fun select(index: Int) {
        levelIndex = minOf(maxOf(0, index), maxOf(0, levels.size - 1))
        points = 0
        lastItem = null
    }

    // ---- QuizSource ----

    override val summary: String
        get() = "Level $levelNumber of ${levels.size} — ${level.section}"

    override fun nextDrill(): Drill {
        val target = pickTarget()
        lastItem = target
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
        val item = lastItem ?: return DrillOutcome(false, null)
        val correct = choice == item.answer
        val history = attemptsById.getOrPut(item.id) { mutableListOf() }
        history.add(CharacterStats.Attempt(correct, ttr))
        if (history.size > CharacterStats.historyLimit) history.removeAt(0)

        if (correct) {
            val isNew = level.newItems.any { it.id == item.id }
            points += scoring.fill + (if (isNew) scoring.newItemBonus else 0)
        } else {
            points = maxOf(0, points - scoring.drain)
        }

        // Level cleared: surface the unlock and advance to the next level.
        if (points >= scoring.target) {
            val clearedNumber = levelNumber
            val wasLast = isLastLevel
            if (!wasLast) levelIndex += 1
            points = 0
            lastItem = null
            val label = if (wasLast) "Journey complete!" else "Level $clearedNumber complete!"
            return DrillOutcome(correct, label)
        }
        return DrillOutcome(correct, null)
    }

    // ---- Selection ----

    /**
     * Distinct answer labels: the correct one plus the closest-sounding others,
     * drawn only from the current level's learned pool.
     */
    private fun buildOptions(target: MorseItem): List<String> {
        val cap = maxOf(1, config.optionCount)
        val others = level.pool.filter { it.id != target.id && it.answer != target.answer }
        val optionsToShow = minOf(cap, others.size + 1)
        val needed = maxOf(0, optionsToShow - 1)
        val sorted = others
            .map { it.answer to MorseDistance.distance(target.soundKey, it.soundKey) }
            .sortedWith(compareBy({ it.second }, { it.first }))

        val distractors = mutableListOf<String>()
        for ((answer, _) in sorted) {
            if (distractors.size >= needed) break
            if (answer !in distractors) distractors.add(answer)
        }
        return (listOf(target.answer) + distractors).shuffled(rng)
    }

    private fun pickTarget(): MorseItem {
        val pool = level.pool
        val weights = pool.map { weight(it) }
        val total = weights.sum()
        if (total <= 0) return pool.random(rng)
        var roll = rng.nextDouble(total)
        for ((item, w) in pool.zip(weights)) {
            if (roll < w) return item
            roll -= w
        }
        return pool.last()
    }

    /**
     * Favor the newest items heavily (so each level teaches its new symbols),
     * then items that are missed or slow — the old/new mix the journey is built on.
     */
    fun weight(item: MorseItem): Double {
        var w = if (level.newItems.any { it.id == item.id }) 4.0 else 1.0
        val recent = (attemptsById[item.id] ?: emptyList()).takeLast(5)
        if (recent.isEmpty()) return w + 2.0   // never-seen-this-session bump
        val accuracy = recent.count { it.correct }.toDouble() / recent.size
        w += (1.0 - accuracy) * 4.0
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

/**
 * Persisted journey progress: how far the player has unlocked and where they
 * currently are. Stored in SharedPreferences by the app layer.
 */
data class JourneyProgress(
    /** Highest level number the player has unlocked (1-based). Level 1 is always unlocked. */
    var unlockedThrough: Int = 1,
    /** The level the player last had selected (1-based). */
    var currentLevel: Int = 1,
    /** Level numbers the player has cleared at least once. */
    val completed: MutableSet<Int> = mutableSetOf()
) {
    /** Record clearing the given level number: mark it complete and unlock the next one. */
    fun clear(level: Int, totalLevels: Int) {
        completed.add(level)
        unlockedThrough = minOf(totalLevels, maxOf(unlockedThrough, level + 1))
    }

    fun isUnlocked(level: Int): Boolean = level <= unlockedThrough
}
