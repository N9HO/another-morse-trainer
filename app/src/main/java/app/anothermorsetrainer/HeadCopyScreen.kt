package app.anothermorsetrainer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import app.anothermorsetrainer.morsekit.MorseData
import app.anothermorsetrainer.morsekit.PhraseQuiz
import kotlinx.coroutines.delay

private val OK_GREEN = Color(0xFF2E7D32)
private val ERR_RED = Color(0xFFC62828)

/** A guaranteed non-match, so a self-graded miss never scores as correct. */
private const val MISS_SENTINEL = "miss"

private enum class HcPhase { RUNNING, SUMMARY }

/**
 * **Head Copy**: hear a word or call sign, copy it in your head with no choices
 * on screen, then reveal and self-grade. Builds true head-copy. Mirrors the iOS
 * Head Copy flow: replay on demand, reveal when ready (or on a timer), optional
 * auto-repeats until you reveal, then "Got it" / "Missed". Runs against the
 * configured session length and ends with the standard summary.
 */
@Composable
fun HeadCopyScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val player = remember { MorsePlayer() }
    val haptics = remember { Haptics(context) }
    val source = remember { PhraseQuiz("Head Copy", MorseData.wordAndCallSignItems, summaryNoun = "words & calls") }

    var drill by remember { mutableStateOf(source.nextDrill()) }
    // Monotonic counter drives play/reset — never key on the Drill value. See #43.
    var round by remember { mutableStateOf(0) }
    var revealed by remember { mutableStateOf(false) }
    var summary by remember { mutableStateOf(source.summary) }
    // Mid-session Settings, drawn over the session so its state lives on.
    var showSettings by remember { mutableStateOf(false) }
    // When the tone finished, so recall time (tone end → Reveal) feeds stats
    // like every other mode instead of a flat zero.
    var toneFinishedAt by remember { mutableStateOf(0L) }
    var lastRecallSec by remember { mutableStateOf(0.0) }

    // Session phase: drills, then a summary once the timer runs out or End is
    // tapped. Back mid-session still records — it just skips the summary.
    var phase by remember { mutableStateOf(HcPhase.RUNNING) }
    var tally by remember { mutableStateOf(Tally()) }
    var remaining by remember { mutableStateOf(Settings.practiceDuration.seconds) }
    var recorded by remember { mutableStateOf(false) }
    var milestone by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(round) {
        revealed = false
        toneFinishedAt = 0L
        lastRecallSec = 0.0
        player.play(drill.playable, Settings.sidetoneHz, Settings.timing()) { toneFinishedAt = System.nanoTime() }
    }

    // Auto-repeat: once the tone has finished, replay the item every couple of
    // seconds until the reveal (or the round moves on).
    LaunchedEffect(round, revealed, toneFinishedAt) {
        if (!Settings.headCopyAutoRepeat || revealed || phase != HcPhase.RUNNING || toneFinishedAt == 0L) return@LaunchedEffect
        while (true) {
            delay(2000)
            if (revealed || phase != HcPhase.RUNNING) break
            val dur = player.replaySound(drill.playable, Settings.sidetoneHz, Settings.timing())
            delay((dur * 1000).toLong())
        }
    }

    fun doReveal() {
        if (revealed) return
        // Recall time runs from the end of the tone to the reveal.
        lastRecallSec = if (toneFinishedAt == 0L) 0.0
                        else (System.nanoTime() - toneFinishedAt) / 1_000_000_000.0
        revealed = true
    }

    // Timed reveal: show the answer automatically N seconds after the tone.
    LaunchedEffect(round, toneFinishedAt) {
        val secs = Settings.headCopyRevealSec
        if (secs <= 0 || toneFinishedAt == 0L) return@LaunchedEffect
        delay(secs * 1000L)
        if (phase == HcPhase.RUNNING) doReveal()
    }

    DisposableEffect(Unit) { onDispose { player.release() } }

    /** Persist the session once (summary entry or an early Back, whichever first). */
    fun recordSession(): Int? {
        if (recorded) return null
        recorded = true
        return Stats.record(
            mode = "Head Copy", attempts = tally.attempts, correct = tally.correct,
            bestTtrMs = tally.bestMs, durationSeconds = tally.elapsedSeconds(),
            characterWpm = Settings.characterWpm.roundToInt(), medianTtrMs = tally.medianMs()
        )
    }

    fun endSession() {
        if (phase == HcPhase.SUMMARY) return
        player.stop()
        milestone = recordSession()
        phase = HcPhase.SUMMARY
    }

    fun finish() {
        recordSession()
        onBack()
    }

    fun practiceAgain() {
        tally = Tally()
        recorded = false
        milestone = null
        remaining = Settings.practiceDuration.seconds
        phase = HcPhase.RUNNING
        drill = source.nextDrill()
        revealed = false
        round++
    }

    // Session countdown: ticks only while running and only when a length is set.
    LaunchedEffect(phase) {
        if (phase != HcPhase.RUNNING) return@LaunchedEffect
        while (true) {
            val r = remaining ?: return@LaunchedEffect
            if (r <= 0) {
                endSession()
                return@LaunchedEffect
            }
            delay(1000)
            remaining = remaining?.minus(1)
        }
    }

    BackHandler { if (phase == HcPhase.SUMMARY) onBack() else finish() }

    fun grade(gotIt: Boolean) {
        if (phase != HcPhase.RUNNING) return
        val outcome = source.record(choice = if (gotIt) drill.correct else MISS_SENTINEL, ttr = lastRecallSec)
        tally.attempts += 1
        if (outcome.correct) {
            tally.correct += 1
            tally.noteCorrectMs((lastRecallSec * 1000).roundToInt())
        }
        if (Settings.hapticsEnabled) {
            if (outcome.correct) haptics.success() else haptics.error()
        }
        summary = source.summary
        // Hide the reveal in the same recomposition as the new drill —
        // LaunchedEffect(round) resets it a frame later, and that stale frame
        // flashed the next item's answer (issue #63).
        drill = source.nextDrill()
        revealed = false
        round++
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { if (phase == HcPhase.SUMMARY) onBack() else finish() }) { Text("‹ Back") }
            if (phase == HcPhase.RUNNING) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    remaining?.let {
                        Text(
                            "%d:%02d".format(it / 60, it % 60),
                            style = MaterialTheme.typography.labelMedium,
                            color = Brand.textSecondary
                        )
                    }
                    SessionSettingsButton { showSettings = true }
                    TextButton(onClick = { endSession() }) { Text("End") }
                }
            }
        }

        if (phase == HcPhase.SUMMARY) {
            SessionSummaryContent(
                title = "Head Copy",
                tally = tally,
                milestone = milestone,
                onPracticeAgain = { practiceAgain() },
                onDone = onBack
            )
        } else {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(text = "Head Copy", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text(text = summary, style = MaterialTheme.typography.labelMedium, color = Brand.textSecondary)

            // How each item plays out: repeats until reveal, and when to reveal.
            Spacer(Modifier.height(10.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Repeat", style = MaterialTheme.typography.labelMedium, color = Brand.textSecondary)
                HcPill("Off", selected = !Settings.headCopyAutoRepeat) { Settings.updateHeadCopyAutoRepeat(false) }
                HcPill("Auto", selected = Settings.headCopyAutoRepeat) { Settings.updateHeadCopyAutoRepeat(true) }
            }
            Spacer(Modifier.height(6.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Reveal", style = MaterialTheme.typography.labelMedium, color = Brand.textSecondary)
                listOf(0 to "Manual", 2 to "2s", 4 to "4s", 6 to "6s").forEach { (secs, label) ->
                    HcPill(label, selected = Settings.headCopyRevealSec == secs) {
                        Settings.updateHeadCopyRevealSec(secs)
                    }
                }
            }

            Spacer(Modifier.height(36.dp))

            if (revealed) {
                SlashableText(
                    text = drill.revealPrimary,
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Text("Did you copy it?", color = Brand.textSecondary)
            } else {
                Text(text = "🧠", fontSize = 52.sp)
                Spacer(Modifier.height(8.dp))
                Text("Copy it in your head…", fontSize = 18.sp, color = Brand.teal)
            }

            Spacer(Modifier.height(36.dp))

            if (revealed) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Button(
                        onClick = { grade(false) },
                        colors = ButtonDefaults.buttonColors(containerColor = ERR_RED, contentColor = Color.White),
                        modifier = Modifier.weight(1f).heightIn(min = 64.dp)
                    ) { Text("✗ Missed", fontWeight = FontWeight.SemiBold) }
                    Button(
                        onClick = { grade(true) },
                        colors = ButtonDefaults.buttonColors(containerColor = OK_GREEN, contentColor = Color.White),
                        modifier = Modifier.weight(1f).heightIn(min = 64.dp)
                    ) { Text("✓ Got it", fontWeight = FontWeight.SemiBold) }
                }
            } else {
                Button(
                    onClick = { doReveal() },
                    colors = ButtonDefaults.buttonColors(containerColor = Brand.teal, contentColor = Brand.navy),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                ) { Text("Reveal", fontWeight = FontWeight.SemiBold) }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { player.replaySound(drill.playable, Settings.sidetoneHz, Settings.timing()) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("▶ Replay") }
            }
        }
        }

        if (showSettings) {
            SessionSettingsOverlay(scope = SettingsMode.HEAD_COPY, onClose = { showSettings = false })
        }
    }
}

@Composable
private fun HcPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(
                if (selected) Brand.teal else Brand.navyRaised,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            label,
            color = if (selected) Brand.navy else Brand.textSecondary,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            fontSize = 12.sp
        )
    }
}
