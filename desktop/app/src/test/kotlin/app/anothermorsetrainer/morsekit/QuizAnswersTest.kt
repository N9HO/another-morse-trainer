package app.anothermorsetrainer.morsekit

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The audit #332 asked for: every multiple-choice question the app asks has
 * its right answer among its choices, a right-answer index in range, and no
 * choice twice. Operating Procedure's authored scenarios, for a spread of
 * stations; then every drill source the quiz modes run (characters at each
 * stage, confusion, each phrase table, every Journey level, each Rapid Fire
 * content), answered for a few hundred rounds so their pools grow.
 *
 * Held to the same expectations as the Swift MorseKitCheck section "Every
 * multiple-choice question offers its answer" and the Android tree's own copy
 * of this test. These are rules over each port's own tables, not shared
 * values, so there is no fixture: `fixtures/operating-procedure.json` already
 * pins the scenarios themselves. (#332 itself was an iOS view bug, which no
 * data check can see; this tree's data and screen were right.)
 */
class QuizAnswersTest {

    private val stations = listOf(
        "K9QRO" to "WI", "K4RTZ" to "VA", "W1AW" to "CT", "KB3/P" to "PA",
        "AA1" to "NY", "N3N5" to "MD", "VE3ABC/QRP" to "ON", "W0PQA" to "CO",
    )

    @Test
    fun everyOperatingProcedureScenarioOffersItsAnswer() {
        val problems = mutableListOf<String>()
        for ((call, state) in stations) {
            val all = OperatingProcedure.scenarios(call, state)
            if (all.map { it.id }.toSet().size != all.size) problems += "$call: duplicate scenario ids"
            for (s in all) {
                val n = s.choices.size
                val where = "$call ${s.id}"
                if (n < 2) problems += "$where: fewer than two choices"
                if (s.choices.toSet().size != n) problems += "$where: a choice appears twice"
                if (s.accepted.isEmpty() || 0 !in s.accepted) problems += "$where: the primary answer (choice 0) is not accepted"
                if (s.accepted.any { it < 0 || it >= n }) problems += "$where: an accepted index is out of range"
                if (s.accepted.toSet().size != s.accepted.size) problems += "$where: an accepted index twice"
                if (s.accepted.size >= n) problems += "$where: no wrong choice to pick"
                if (!s.accepts(s.correct)) problems += "$where: its correct choice is not accepted"
                for (c in s.choices) {
                    if (c is OpChoice.Send && c.text.isBlank()) problems += "$where: an empty send choice"
                    if (c is OpChoice.Option && c.key.isEmpty()) problems += "$where: an empty option key"
                }
            }
            for (s in OperatingProcedure.actionPool(call, state)) {
                if (!s.isAction) problems += "$call ${s.id}: in the action pool without being an action"
            }
        }
        assertTrue("Operating Procedure scenarios: ${problems.take(5)}", problems.isEmpty())
    }

    /**
     * Draw [rounds] drills from [source], answering most right (so pools grow
     * and stages advance) and every fourth wrong; the first bad one, or null.
     */
    private fun sweep(name: String, source: QuizSource, rounds: Int = 300): String? {
        for (round in 0 until rounds) {
            val d = source.nextDrill()
            if (d.options.isEmpty()) return "$name round $round: no options"
            if (d.correct !in d.options) return "$name round $round: ${d.correct} not among ${d.options}"
            if (d.options.toSet().size != d.options.size) return "$name round $round: duplicate option in ${d.options}"
            val wrong = d.options.firstOrNull { it != d.correct }
            source.record(if (round % 4 == 3) (wrong ?: d.correct) else d.correct, 0.4)
        }
        return null
    }

    @Test
    fun everyDrillSourceOffersItsAnswer() {
        val problems = mutableListOf<String>()
        fun note(r: String?) { if (r != null) problems += r }
        note(sweep("characters", TrainerEngine(rng = Random(332))))
        for (stage in ProgressiveCharacters.Stage.values()) {
            val p = ProgressiveCharacters(TrainerEngine(seedCount = 12, rng = Random(21)), Random(22))
            p.pin(stage)
            note(sweep("character ladder, $stage", p))
        }
        note(sweep("confusion", ConfusionQuiz(TrainerEngine(seedCount = 12, rng = Random(31)), Random(32))))
        val tables = listOf(
            "words" to MorseData.wordItems, "CW 77" to MorseData.cw77Items(),
            "abbreviations" to MorseData.abbreviationItems, "Q-codes" to MorseData.qCodeItems,
            "prosigns" to MorseData.prosignItems, "words & calls" to MorseData.wordAndCallSignItems,
        )
        for ((name, items) in tables) {
            note(sweep(name, PhraseQuiz(name, items, rng = Random(41)), rounds = 600))
        }
        JourneyCurriculum.levels.forEachIndexed { i, level ->
            note(sweep("Journey level ${level.number}", JourneyQuiz(startIndex = i, rng = Random(51 + i)), rounds = 80))
        }
        for (content in RapidFireContent.values()) {
            note(sweep("Rapid Fire $content", RapidFireQuiz(RapidFireQuiz.Config(content = content), Random(61)), rounds = 100))
        }
        assertTrue("drill sources: ${problems.take(5)}", problems.isEmpty())
    }
}
