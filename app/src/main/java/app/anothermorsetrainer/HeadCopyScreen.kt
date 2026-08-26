package app.anothermorsetrainer

import androidx.activity.compose.BackHandler
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

private val OK_GREEN = Color(0xFF2E7D32)
private val ERR_RED = Color(0xFFC62828)

/** A guaranteed non-match, so a self-graded miss never scores as correct. */
private const val MISS_SENTINEL = "miss"

/**
 * **Head Copy**: hear a word or call sign, copy it in your head with no choices
 * on screen, then reveal and self-grade. Builds true head-copy. Mirrors the iOS
 * Head Copy flow in its manual-reveal configuration (replay on demand, reveal
 * when ready, then "Got it" / "Missed").
 */
@Composable
fun HeadCopyScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val player = remember { MorsePlayer() }
    val haptics = remember { Haptics(context) }
    val source = remember { PhraseQuiz("Head Copy", MorseData.wordAndCallSignItems) }

    var drill by remember { mutableStateOf(source.nextDrill()) }
    // Monotonic counter drives play/reset — never key on the Drill value. See #43.
    var round by remember { mutableStateOf(0) }
    var revealed by remember { mutableStateOf(false) }
    var summary by remember { mutableStateOf(source.summary) }
    // When the tone finished, so recall time (tone end → Reveal) feeds stats
    // like every other mode instead of a flat zero.
    var toneFinishedAt by remember { mutableStateOf(0L) }
    var lastRecallSec by remember { mutableStateOf(0.0) }

    val tally = remember { Tally() }

    LaunchedEffect(round) {
        revealed = false
        toneFinishedAt = 0L
        lastRecallSec = 0.0
        player.play(drill.playable, Settings.sidetoneHz, Settings.timing()) { toneFinishedAt = System.nanoTime() }
    }

    DisposableEffect(Unit) { onDispose { player.release() } }

    fun finish() {
        Stats.record(
            mode = "Head Copy", attempts = tally.attempts, correct = tally.correct,
            bestTtrMs = tally.bestMs, durationSeconds = tally.elapsedSeconds(),
            characterWpm = Settings.characterWpm.roundToInt(), medianTtrMs = tally.medianMs()
        )
        onBack()
    }
    BackHandler { finish() }

    fun grade(gotIt: Boolean) {
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
        drill = source.nextDrill()
        round++
    }

    Box(modifier = Modifier.fillMaxSize()) {
        TextButton(onClick = { finish() }, modifier = Modifier.padding(8.dp)) { Text("‹ Back") }

        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(text = "Head Copy", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text(text = summary, style = MaterialTheme.typography.labelMedium, color = Brand.textSecondary)

            Spacer(Modifier.height(48.dp))

            if (revealed) {
                Text(
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

            Spacer(Modifier.height(48.dp))

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
                    onClick = {
                        // Recall time runs from the end of the tone to the reveal.
                        lastRecallSec = if (toneFinishedAt == 0L) 0.0
                                        else (System.nanoTime() - toneFinishedAt) / 1_000_000_000.0
                        revealed = true
                    },
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
}
