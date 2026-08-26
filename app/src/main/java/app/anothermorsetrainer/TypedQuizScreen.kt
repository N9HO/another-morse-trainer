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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.anothermorsetrainer.morsekit.MorseData
import app.anothermorsetrainer.morsekit.MorseTiming
import app.anothermorsetrainer.morsekit.PhraseQuiz
import app.anothermorsetrainer.morsekit.QuizSource
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val OK_GREEN = Color(0xFF2E7D32)
private val ERR_RED = Color(0xFFC62828)

private enum class TqPhase { RUNNING, SUMMARY }

/**
 * Free-recall typing loop: play an item in Morse, the learner types exactly what
 * they heard, and we grade the (case/space-normalized) text against the answer.
 * Drives both **Type It** (global speed) and **QRQ Speed** (a faster timing
 * provided by the caller). Mirrors the iOS `submitTyped` path: trim + uppercase,
 * then compare through the same [QuizSource.record]. Runs against the configured
 * session length and ends with the standard summary.
 */
@Composable
fun TypedQuizScreen(
    title: String,
    onBack: () -> Unit,
    makeSource: () -> QuizSource,
    timing: () -> MorseTiming = { Settings.timing() },
    speedControl: (@Composable () -> Unit)? = null
) {
    val context = LocalContext.current
    val player = remember { MorsePlayer() }
    val haptics = remember { Haptics(context) }
    val source = remember { makeSource() }

    var drill by remember { mutableStateOf(source.nextDrill()) }
    // Monotonic counter drives play/reset — never key on the Drill value (a data
    // class can compare equal across rounds and silently skip the effect). See #43.
    var round by remember { mutableStateOf(0) }
    var revealed by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var lastCorrect by remember { mutableStateOf(false) }
    var summary by remember { mutableStateOf(source.summary) }
    var toneFinishedAt by remember { mutableStateOf(0L) }

    var tally by remember { mutableStateOf(Tally()) }
    val focus = remember { FocusRequester() }

    // Session phase: drills, then a summary once the timer runs out or End is
    // tapped. Back mid-session still records — it just skips the summary.
    var phase by remember { mutableStateOf(TqPhase.RUNNING) }
    var remaining by remember { mutableStateOf(Settings.practiceDuration.seconds) }
    var recorded by remember { mutableStateOf(false) }
    var milestone by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(round) {
        revealed = false
        input = ""
        lastCorrect = false
        toneFinishedAt = 0L
        player.play(drill.playable, Settings.sidetoneHz, timing()) { toneFinishedAt = System.nanoTime() }
    }

    // Correct answers keep the rhythm going; a miss waits for the Next tap so the
    // learner can compare what they typed against the answer.
    LaunchedEffect(revealed) {
        if (revealed && lastCorrect && phase == TqPhase.RUNNING) {
            delay(900)
            drill = source.nextDrill()
            round++
        }
    }

    DisposableEffect(Unit) { onDispose { player.release() } }

    /** Persist the session once (summary entry or an early Back, whichever first). */
    fun recordSession(): Int? {
        if (recorded) return null
        recorded = true
        return Stats.record(
            mode = title, attempts = tally.attempts, correct = tally.correct,
            bestTtrMs = tally.bestMs, durationSeconds = tally.elapsedSeconds(),
            // The mode's own timing, so QRQ sessions band at 35/40 WPM.
            characterWpm = timing().wpm.roundToInt(), medianTtrMs = tally.medianMs()
        )
    }

    fun endSession() {
        if (phase == TqPhase.SUMMARY) return
        player.stop()
        milestone = recordSession()
        phase = TqPhase.SUMMARY
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
        phase = TqPhase.RUNNING
        drill = source.nextDrill()
        round++
    }

    // Session countdown: ticks only while running and only when a length is set.
    LaunchedEffect(phase) {
        if (phase != TqPhase.RUNNING) return@LaunchedEffect
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

    BackHandler { if (phase == TqPhase.SUMMARY) onBack() else finish() }

    fun advance() {
        drill = source.nextDrill()
        round++
    }

    fun submit() {
        if (revealed || phase != TqPhase.RUNNING) return
        val normalized = input.trim().uppercase()
        if (normalized.isEmpty()) return
        val ttr = if (toneFinishedAt == 0L) 0.0 else (System.nanoTime() - toneFinishedAt) / 1_000_000_000.0
        val outcome = source.record(choice = normalized, ttr = ttr)
        lastCorrect = outcome.correct
        tally.attempts += 1
        val ms = (ttr * 1000).roundToInt()
        if (outcome.correct) {
            tally.correct += 1
            tally.noteCorrectMs(ms)
        }
        summary = source.summary
        if (Settings.hapticsEnabled) {
            if (outcome.correct) haptics.success() else haptics.error()
        }
        revealed = true
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { if (phase == TqPhase.SUMMARY) onBack() else finish() }) { Text("‹ Back") }
            if (phase == TqPhase.RUNNING) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    remaining?.let {
                        Text(
                            "%d:%02d".format(it / 60, it % 60),
                            style = MaterialTheme.typography.labelMedium,
                            color = Brand.textSecondary
                        )
                    }
                    TextButton(onClick = { endSession() }) { Text("End") }
                }
            }
        }

        if (phase == TqPhase.SUMMARY) {
            SessionSummaryContent(
                title = title,
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
            Text(text = title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text(text = summary, style = MaterialTheme.typography.labelMedium, color = Brand.textSecondary)

            speedControl?.let {
                Spacer(Modifier.height(16.dp))
                it()
            }

            Spacer(Modifier.height(36.dp))

            if (revealed) {
                Text(
                    text = drill.revealPrimary,
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (lastCorrect) "✓ correct" else "✗ you typed “${input.trim().uppercase()}”",
                    color = if (lastCorrect) OK_GREEN else ERR_RED,
                    fontWeight = FontWeight.Medium
                )
            } else {
                Text(text = "Type what you hear", fontSize = 18.sp, color = Brand.teal)
            }

            Spacer(Modifier.height(28.dp))

            MorseNumberRow(
                onKey = { if (!revealed) input += it },
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            )

            OutlinedTextField(
                value = input,
                onValueChange = { if (!revealed) input = it },
                enabled = !revealed,
                singleLine = true,
                textStyle = MaterialTheme.typography.headlineSmall.copy(
                    fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center
                ),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus)
            )

            Spacer(Modifier.height(20.dp))

            if (revealed) {
                if (!lastCorrect) {
                    Button(
                        onClick = { advance() },
                        colors = ButtonDefaults.buttonColors(containerColor = Brand.teal, contentColor = Brand.navy),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Next", fontWeight = FontWeight.SemiBold) }
                }
            } else {
                Button(
                    onClick = { submit() },
                    enabled = input.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Brand.teal, contentColor = Brand.navy),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Check", fontWeight = FontWeight.SemiBold) }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { player.replaySound(drill.playable, Settings.sidetoneHz, timing()) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("▶ Replay") }
            }
        }
        }
    }
}

/**
 * **QRQ Speed**: the same typed free-recall loop as [TypedQuizScreen], but words
 * and call signs are sent at 35 or 40 WPM — too fast to count dits, training
 * instant whole-word recognition. The speed override is local to this mode and
 * does not touch the global WPM setting.
 */
@Composable
fun QrqScreen(onBack: () -> Unit) {
    var wpm by remember { mutableStateOf(35.0) }
    TypedQuizScreen(
        title = "QRQ Speed",
        onBack = onBack,
        makeSource = { PhraseQuiz("QRQ", MorseData.wordAndCallSignItems) },
        timing = { MorseTiming(wpm) },
        speedControl = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(35.0, 40.0).forEach { speed ->
                    val selected = wpm == speed
                    if (selected) {
                        Button(
                            onClick = { wpm = speed },
                            colors = ButtonDefaults.buttonColors(containerColor = Brand.teal, contentColor = Brand.navy)
                        ) { Text("${speed.toInt()} WPM", fontWeight = FontWeight.SemiBold) }
                    } else {
                        OutlinedButton(onClick = { wpm = speed }) { Text("${speed.toInt()} WPM") }
                    }
                }
            }
        }
    )
}
