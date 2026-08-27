package app.anothermorsetrainer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.anothermorsetrainer.morsekit.ProgressiveCharacters
import app.anothermorsetrainer.morsekit.TrainerEngine
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val OK_GREEN = Color(0xFF2E7D32)
private val ERR_RED = Color(0xFFC62828)

/**
 * **Sending Practice**: hear a character or word, then *key it back* on the
 * on-screen straight key. The keying is decoded live (via [SendingKeyer] /
 * `MorseDecoder`) and graded against the drill through the shared
 * [app.anothermorsetrainer.morsekit.QuizSource] — the same Koch ladder that
 * drives the Characters mode, so it adapts to what you can already send.
 *
 * Mirrors the iOS "answer by keying" panel (`SendingKeyerView`): hold-to-key,
 * a "YOU SENT" readout, Clear/Submit, and auto-submit once the decoded text is
 * at least as long as the answer and the key is idle.
 */
@Composable
fun SendingPracticeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val player = remember { MorsePlayer() }
    val haptics = remember { Haptics(context) }
    val source = remember {
        val engine = TrainerEngine(Settings.engineConfig())
        Settings.applyProficiency(engine)
        ProgressiveCharacters(engine)
    }
    val keyer = remember { SendingKeyer(wpm = Settings.characterWpm, toneHz = Settings.sidetoneHz) }
    val midi = remember { MidiKeyInput(context) }
    val scope = rememberCoroutineScope()

    var midiDevice by remember { mutableStateOf<String?>(null) }

    var drill by remember { mutableStateOf(source.nextDrill()) }
    var round by remember { mutableStateOf(0) }
    var revealed by remember { mutableStateOf(false) }
    var lastCorrect by remember { mutableStateOf(false) }
    var sentAnswer by remember { mutableStateOf("") }
    var summary by remember { mutableStateOf(source.summary) }
    var toneFinishedAt by remember { mutableStateOf(0L) }
    var keyPressed by remember { mutableStateOf(false) }

    val tally = remember { Tally() }

    DisposableEffect(Unit) {
        keyer.scope = scope
        keyer.start()
        // Hardware key (Vail Adapter / BLE-MIDI): route note on/off through the
        // same keyer as the on-screen key.
        midi.start(
            onKey = { down -> keyer.touchKey(down) },
            onConnected = { name -> midiDevice = name }
        )
        onDispose { midi.stop(); keyer.stop(); player.release() }
    }

    LaunchedEffect(round) {
        revealed = false
        lastCorrect = false
        sentAnswer = ""
        toneFinishedAt = 0L
        keyer.clear()
        player.play(drill.playable, Settings.sidetoneHz, Settings.timing()) { toneFinishedAt = System.nanoTime() }
    }

    fun grade(answer: String) {
        if (revealed || answer.isEmpty()) return
        val ttr = if (toneFinishedAt == 0L) 0.0 else (System.nanoTime() - toneFinishedAt) / 1_000_000_000.0
        sentAnswer = answer
        val outcome = source.record(choice = answer, ttr = ttr)
        lastCorrect = outcome.correct
        tally.attempts += 1
        if (outcome.correct) tally.correct += 1
        if (drill.correct.length == 1) {
            Stats.recordChar(drill.correct, outcome.correct, null)
        }
        if (Settings.hapticsEnabled) {
            if (outcome.correct) haptics.success() else haptics.error()
        }
        summary = source.summary
        revealed = true
    }

    // Auto-submit once the decoded text is at least as long as the answer and the
    // key has gone idle (matches the iOS maybeAutoSubmit rhythm).
    LaunchedEffect(keyer.decodedText, keyer.isKeying, revealed) {
        if (revealed || keyer.isKeying) return@LaunchedEffect
        val typed = keyer.decodedText.trim()
        if (typed.isNotEmpty() && typed.length >= drill.correct.length) {
            grade(typed.uppercase())
        }
    }

    // After grading, pause on the result, then advance.
    LaunchedEffect(revealed) {
        if (revealed) {
            delay(1200)
            // Clear the reveal state in the same recomposition as the new
            // drill — LaunchedEffect(round) resets it a frame later, and that
            // stale frame flashed the next item's answer (issue #63).
            drill = source.nextDrill()
            revealed = false
            sentAnswer = ""
            round++
        }
    }

    fun finish() {
        Stats.record(
            mode = "Sending", attempts = tally.attempts, correct = tally.correct,
            bestTtrMs = null, durationSeconds = tally.elapsedSeconds(),
            characterWpm = Settings.characterWpm.roundToInt()
        )
        onBack()
    }
    BackHandler { finish() }

    Box(modifier = Modifier.fillMaxSize()) {
        TextButton(onClick = { finish() }, modifier = Modifier.padding(8.dp)) { Text("‹ Back") }

        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("Sending Practice", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text(summary, style = MaterialTheme.typography.labelMedium, color = Brand.textSecondary)
            midiDevice?.let {
                Spacer(Modifier.height(2.dp))
                Text("🎹 $it", style = MaterialTheme.typography.labelSmall, color = Brand.teal)
            }

            Spacer(Modifier.height(20.dp))

            if (revealed) {
                SlashableText(
                    text = drill.revealPrimary,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (lastCorrect) "✓ you sent it" else "✗ you sent “$sentAnswer”",
                    color = if (lastCorrect) OK_GREEN else ERR_RED,
                    fontWeight = FontWeight.Medium
                )
            } else {
                Text("Listen, then key it back", fontSize = 16.sp, color = Brand.teal)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    player.replaySound(drill.playable, Settings.sidetoneHz, Settings.timing())
                }) { Text("▶ Replay") }
            }

            Spacer(Modifier.height(20.dp))

            // "YOU SENT" decoded readout.
            Column(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(Brand.cornerRadius)).brandCard()
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("YOU SENT", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Brand.textSecondary)
                Spacer(Modifier.height(4.dp))
                val decoded = keyer.decodedText
                Text(
                    text = if (decoded.isEmpty()) "—" else decoded,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    color = if (decoded.isEmpty()) Brand.textSecondary else Brand.textPrimary,
                    maxLines = 1
                )
            }

            Spacer(Modifier.height(20.dp))

            // Hold-to-key straight key.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .clip(RoundedCornerShape(Brand.cornerRadius))
                    .background(if (keyPressed) Brand.teal else Brand.navyRaised)
                    .border(
                        width = if (keyPressed) 2.dp else 1.dp,
                        color = if (keyPressed) Brand.tealBright else Brand.hairline,
                        shape = RoundedCornerShape(Brand.cornerRadius)
                    )
                    .pointerInput(revealed) {
                        if (revealed) return@pointerInput
                        detectTapGestures(
                            onPress = {
                                keyPressed = true
                                keyer.touchKey(true)
                                try {
                                    tryAwaitRelease()
                                } finally {
                                    keyPressed = false
                                    keyer.touchKey(false)
                                }
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("⠿", fontSize = 26.sp, color = if (keyPressed) Brand.navy else Brand.teal)
                    Text(
                        "HOLD TO KEY",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (keyPressed) Brand.navy else Brand.textSecondary
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { keyer.clear() },
                    enabled = !revealed,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) { Text("Clear") }
                Button(
                    onClick = { grade(keyer.submit().uppercase()) },
                    enabled = !revealed && keyer.decodedText.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Brand.teal, contentColor = Brand.navy),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) { Text("Submit", fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}
