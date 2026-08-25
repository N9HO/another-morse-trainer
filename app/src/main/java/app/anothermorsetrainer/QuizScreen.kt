package app.anothermorsetrainer

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
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
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.anothermorsetrainer.morsekit.Drill
import app.anothermorsetrainer.morsekit.QuizSource
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val OK_GREEN = Color(0xFF2E7D32)
private val ERR_RED = Color(0xFFC62828)

/** Mutable per-session tally, accumulated as the learner answers. */
internal class Tally {
    var attempts = 0
    var correct = 0
    var bestMs: Int? = null
    /** Correct recognition times this session — the median feeds speed-band stats. */
    private val ttrsMs = mutableListOf<Int>()
    /** Wall-clock when this session began — Tally is remembered at screen entry. */
    val startedAtMs: Long = System.currentTimeMillis()
    /** Whole seconds of practice elapsed since the session began. */
    fun elapsedSeconds(): Int = ((System.currentTimeMillis() - startedAtMs) / 1000L).toInt()

    /** Note one correct answer's recognition time (and keep the fastest). */
    fun noteCorrectMs(ms: Int) {
        if (ms <= 0) return
        ttrsMs.add(ms)
        if (bestMs == null || ms < bestMs!!) bestMs = ms
    }

    /** Median correct recognition time this session, or null if none recorded. */
    fun medianMs(): Int? {
        if (ttrsMs.isEmpty()) return null
        val s = ttrsMs.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
    }
}

/**
 * One reusable quiz loop that drives ANY [QuizSource] — character practice
 * (ProgressiveCharacters), word/abbreviation/Q-code drills (PhraseQuiz), the
 * confusion-pair drill, or the code exam (ExamSession) all flow through here.
 * Plays the drill in Morse via [MorsePlayer], scores the tap, gives haptic +
 * colour feedback, then advances.
 */
@Composable
fun QuizScreen(
    title: String,
    onBack: () -> Unit,
    makeSource: () -> QuizSource
) {
    val context = LocalContext.current
    val player = remember { MorsePlayer() }
    val haptics = remember { Haptics(context) }
    val source = remember { makeSource() }

    var drill by remember { mutableStateOf(source.nextDrill()) }
    // Monotonic round counter drives the play/reset effect. We must NOT key that
    // effect on `drill` itself: `Drill` is a data class, so when nextDrill()
    // happens to return a value-equal round (common with small option sets like a
    // 2-character drill), assigning it is a no-op to Compose and the effect never
    // relaunches — leaving the screen frozen on the answered state. (issue #43)
    var round by remember { mutableStateOf(0) }
    var revealed by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf<String?>(null) }
    var lastTtr by remember { mutableStateOf(0.0) }
    var summary by remember { mutableStateOf(source.summary) }
    var toneFinishedAt by remember { mutableStateOf(0L) }

    // Session tallies, persisted to Stats when the learner leaves.
    val tally = remember { Tally() }

    // Optional spoken answers (microphone).
    val recognizer = remember { VoiceRecognizer(context) }
    var listening by remember { mutableStateOf(false) }
    var voiceNote by remember { mutableStateOf<String?>(null) }
    var listenTick by remember { mutableStateOf(0) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) listenTick++ else voiceNote = "Microphone permission is needed for voice answers."
    }

    LaunchedEffect(round) {
        revealed = false
        chosen = null
        toneFinishedAt = 0L
        listening = false
        voiceNote = null
        recognizer.cancel()
        player.play(drill.playable, Settings.sidetoneHz, Settings.timing()) { toneFinishedAt = System.nanoTime() }
    }

    LaunchedEffect(revealed) {
        if (revealed) {
            delay(1100)
            drill = source.nextDrill()
            round++
        }
    }

    DisposableEffect(Unit) { onDispose { player.release(); recognizer.release() } }

    fun finish() {
        Stats.record(
            mode = title, attempts = tally.attempts, correct = tally.correct,
            bestTtrMs = tally.bestMs, durationSeconds = tally.elapsedSeconds(),
            characterWpm = Settings.characterWpm.roundToInt(), medianTtrMs = tally.medianMs()
        )
        onBack()
    }

    // Hardware/gesture back records the session too, then leaves.
    BackHandler { finish() }

    fun answer(choice: String) {
        if (revealed) return
        val ttr = if (toneFinishedAt == 0L) 0.0
                  else (System.nanoTime() - toneFinishedAt) / 1_000_000_000.0
        lastTtr = ttr
        chosen = choice
        val outcome = source.record(choice = choice, ttr = ttr)
        summary = source.summary
        tally.attempts += 1
        val ms = (ttr * 1000).roundToInt()
        if (outcome.correct) {
            tally.correct += 1
            tally.noteCorrectMs(ms)
        }
        // Single-character drills feed the per-character recognition chart.
        if (drill.correct.length == 1) {
            Stats.recordChar(drill.correct, outcome.correct, if (outcome.correct && ms > 0) ms else null)
        }
        if (Settings.hapticsEnabled) {
            if (outcome.correct) haptics.success() else haptics.error()
        }
        revealed = true
    }

    // Listen for a spoken answer when the mic is tapped (listenTick bumps).
    LaunchedEffect(listenTick) {
        if (listenTick == 0 || revealed) return@LaunchedEffect
        listening = true
        voiceNote = null
        recognizer.start(
            hints = drill.options,
            onResult = { candidates ->
                listening = false
                val matched = AnswerMatch.match(candidates, drill.options)
                if (matched != null) answer(matched)
                else voiceNote = "Didn't catch that — tap an option or try again."
            },
            onError = {
                listening = false
                voiceNote = "Didn't catch that — tap an option or try again."
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        TextButton(onClick = { finish() }, modifier = Modifier.padding(8.dp)) { Text("‹ Back") }

        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(text = title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text(text = summary, style = MaterialTheme.typography.labelMedium)

            // Exam-style comprehension prompt (empty for plain recognition drills).
            if (drill.question.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = drill.question,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(36.dp))

            if (revealed) {
                val ok = chosen == drill.correct
                // Show the answer per the user's reveal preference; the ✓/✗ line
                // always shows so they still know if they were right.
                val showAnswer = when (Settings.revealMode) {
                    RevealMode.ALWAYS -> true
                    RevealMode.ON_WRONG -> !ok
                    RevealMode.NEVER -> false
                }
                if (showAnswer) {
                    Text(
                        text = drill.revealPrimary,
                        fontSize = 44.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center
                    )
                    if (drill.revealSecondary.isNotEmpty()) {
                        Text(text = drill.revealSecondary, fontSize = 20.sp, color = Brand.textSecondary)
                    }
                    Spacer(Modifier.height(4.dp))
                }
                Text(
                    text = when {
                        ok -> "✓ recalled in %.1f s".format(lastTtr)
                        showAnswer -> "✗ it was “${drill.correct}”"
                        else -> "✗ not quite"
                    },
                    color = if (ok) OK_GREEN else ERR_RED,
                    fontWeight = FontWeight.Medium
                )
            } else {
                Text(text = "?", fontSize = 52.sp, fontWeight = FontWeight.Bold, color = Brand.teal)
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = { player.replaySound(drill.playable, Settings.sidetoneHz, Settings.timing()) }) {
                    Text("▶ Replay")
                }
            }

            Spacer(Modifier.height(36.dp))
            OptionsGrid(drill = drill, revealed = revealed, chosen = chosen, onPick = ::answer)

            // Voice answers: a mic to speak instead of tap (options stay as fallback).
            if (Settings.voiceAnswersEnabled && recognizer.isAvailable && !revealed) {
                Spacer(Modifier.height(20.dp))
                OutlinedButton(
                    onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            listenTick++
                        } else {
                            micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    enabled = !listening
                ) {
                    Icon(Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(if (listening) "  Listening…" else "  Speak answer")
                }
                voiceNote?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.labelMedium, color = Brand.textSecondary, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
private fun OptionsGrid(drill: Drill, revealed: Boolean, chosen: String?, onPick: (String) -> Unit) {
    // Two-column grid of bold teal buttons (matches the iOS choice grid).
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        drill.options.chunked(2).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                row.forEach { option ->
                    val colors = when {
                        revealed && option == drill.correct -> ButtonDefaults.buttonColors(containerColor = OK_GREEN, contentColor = Color.White)
                        revealed && option == chosen -> ButtonDefaults.buttonColors(containerColor = ERR_RED, contentColor = Color.White)
                        revealed -> ButtonDefaults.buttonColors(containerColor = Brand.navyRaised, contentColor = Brand.textSecondary)
                        else -> ButtonDefaults.buttonColors(containerColor = Brand.teal, contentColor = Brand.navy)
                    }
                    // Single letters/numbers get a big monospaced glyph; words stay readable.
                    val short = option.length <= 3
                    Button(
                        onClick = { onPick(option) },
                        colors = colors,
                        shape = RoundedCornerShape(Brand.cornerRadius),
                        modifier = Modifier.weight(1f).heightIn(min = 80.dp)
                    ) {
                        Text(
                            text = option,
                            fontSize = if (short) 34.sp else 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = if (short) FontFamily.Monospace else null,
                            textAlign = TextAlign.Center,
                            maxLines = 2
                        )
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
