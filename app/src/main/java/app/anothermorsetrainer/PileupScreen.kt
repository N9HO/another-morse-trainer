package app.anothermorsetrainer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.anothermorsetrainer.morsekit.MorseTiming
import app.anothermorsetrainer.morsekit.PileupConfig
import app.anothermorsetrainer.morsekit.PileupEngine
import kotlin.math.roundToInt

private const val PILEUP_BASE_HZ = 600.0

/** Map an engine [PileupEngine.Voice] to a renderable [MorsePlayer.PileupVoice]. */
private fun PileupEngine.Voice.toMix() = MorsePlayer.PileupVoice(
    text = text,
    frequency = PILEUP_BASE_HZ + toneOffset,
    timing = MorseTiming(wpm),
    gain = volume,
    startDelay = delay,
    qsbRate = if (qsb) 0.3 else null
)

/**
 * Work a CW pileup: call CQ, hear several stations answer at once, copy one
 * call and send it, then copy that station's exchange and log it. Drives the
 * fully-ported [PileupEngine]; audio is the multi-voice mix from [MorsePlayer].
 */
@Composable
fun PileupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val player = remember { MorsePlayer() }
    val haptics = remember { Haptics(context) }
    val engine = remember { PileupEngine(PileupConfig()) }

    var input by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    // Engine state isn't Compose-observable, so bump this to force recomposition.
    var rev by remember { mutableStateOf(0) }

    DisposableEffect(Unit) { onDispose { player.release() } }
    BackHandler { player.stop(); onBack() }

    fun perform(action: PileupEngine.Action) {
        when (action) {
            is PileupEngine.Action.Play -> player.playPileup(action.voices.map { it.toMix() }) {}
            PileupEngine.Action.Silence -> player.stop()
            is PileupEngine.Action.Logged -> if (Settings.hapticsEnabled) haptics.success()
        }
        rev++
    }

    fun submit() {
        if (input.isBlank()) return
        val raw = input.trim()
        val action = engine.send(raw)
        // A typed repeat request ("W1?") while still hunting keeps the partial
        // call in the box — minus the "?" — so the user builds on it instead of
        // retyping (the iOS #49 fix). Anything that moved the QSO on clears.
        val frag = PileupEngine.fragment(raw)
        input = if (raw.endsWith("?") && frag.isNotEmpty() && engine.phase is PileupEngine.Phase.Pileup) frag else ""
        perform(action)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TextButton(onClick = onBack, modifier = Modifier.padding(8.dp)) { Text("‹ Back") }

      CenteredContent {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("Pileup Runner", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

            key(rev) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "${engine.summary} · ${engine.log.size} worked · ${(engine.accuracy * 100).roundToInt()}% clean",
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(Modifier.height(32.dp))

                when (val phase = engine.phase) {
                    PileupEngine.Phase.Idle -> {
                        Text("Tap to call CQ and start a pileup.", textAlign = TextAlign.Center)
                        Spacer(Modifier.height(20.dp))
                        Button(onClick = { perform(engine.callCQ()) }) { Text("Call CQ") }
                    }

                    PileupEngine.Phase.Pileup -> {
                        Text(
                            "${engine.activeCount} stations calling.",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text("Copy a call and send it.", textAlign = TextAlign.Center)
                        if (reveal) {
                            Text(
                                "calling: ${engine.stations.joinToString(", ") { it.call }}",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        CallEntry(input = input, onChange = { input = it.uppercase() }, onSend = ::submit)
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = { perform(engine.repeatRequest()) }) { Text("▶ Again") }
                            OutlinedButton(onClick = { perform(engine.callCQ()) }) { Text("CQ") }
                        }
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { reveal = !reveal }) {
                            Text(if (reveal) "Hide hint" else "Show hint")
                        }
                    }

                    is PileupEngine.Phase.Working, is PileupEngine.Phase.ReadyToLog -> {
                        val st = engine.workingStation
                        Text("Working ${st?.call ?: "?"}", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text("Copy their exchange and send it.", textAlign = TextAlign.Center)
                        if (reveal) {
                            Text(
                                "expecting: ${engine.expectedCopy ?: "—"}",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        CallEntry(input = input, onChange = { input = it.uppercase() }, onSend = ::submit)
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = { perform(engine.repeatRequest()) }) { Text("▶ Again") }
                            Button(onClick = { perform(engine.logCurrent()) }) { Text("Log (TU)") }
                        }
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { reveal = !reveal }) {
                            Text(if (reveal) "Hide hint" else "Show hint")
                        }
                    }
                }
            }
        }
      }
    }
}

@Composable
private fun CallEntry(input: String, onChange: (String) -> Unit, onSend: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = input,
            onValueChange = onChange,
            singleLine = true,
            label = { Text("Send") },
            modifier = Modifier.fillMaxWidth(0.6f)
        )
        Button(onClick = onSend, modifier = Modifier.height(56.dp)) { Text("Send") }
    }
}
