package app.anothermorsetrainer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.anothermorsetrainer.morsekit.MorseItem
import app.anothermorsetrainer.morsekit.MorseStories

/**
 * **Short Stories** (continuous copy): hear a short passage sent end to end,
 * copy it on paper or in your head, then reveal the text to check yourself.
 * Mirrors the iOS story flow (play → reveal → next) without the per-character
 * scoring the recognition drills use.
 */
@Composable
fun StoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val player = remember { MorsePlayer() }
    val stories = remember { MorseStories.all }

    var index by remember { mutableStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var revealed by remember { mutableStateOf(false) }
    // Bumped on every stop/next so an in-flight completion callback from a
    // superseded transmission can't flip state for the wrong passage.
    var generation by remember { mutableStateOf(0) }
    // Passages the learner copied through to the reveal — the session's "answers".
    var passagesCopied by remember { mutableStateOf(0) }
    val startedAtMs = remember { System.currentTimeMillis() }

    val story = stories[((index % stories.size) + stories.size) % stories.size]

    DisposableEffect(Unit) { onDispose { player.release() } }

    // Continuous copy is self-checked, so each revealed passage counts as one
    // completed copy — enough for the streak and practice-time totals.
    fun finish() {
        player.stop()
        Stats.record(
            mode = "Stories",
            attempts = passagesCopied,
            correct = passagesCopied,
            bestTtrMs = null,
            durationSeconds = ((System.currentTimeMillis() - startedAtMs) / 1000L).toInt()
        )
        onBack()
    }
    BackHandler { finish() }

    fun play() {
        revealed = false
        playing = true
        generation += 1
        val gen = generation
        player.play(MorseItem.Playable.Text(story.text), Settings.sidetoneHz, Settings.timing()) {
            if (gen == generation) playing = false
        }
    }

    fun stop() {
        generation += 1
        player.stop()
        playing = false
    }

    fun next() {
        generation += 1
        player.stop()
        playing = false
        revealed = false
        index += 1
    }

    Box(modifier = Modifier.fillMaxSize()) {
        TextButton(onClick = { finish() }, modifier = Modifier.padding(8.dp)) { Text("‹ Back") }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
                .padding(top = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = "Short Stories", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Story ${(((index % stories.size) + stories.size) % stories.size) + 1} of ${stories.size} · ${story.lengthLabel}",
                style = MaterialTheme.typography.labelMedium,
                color = Brand.textSecondary
            )

            Spacer(Modifier.height(24.dp))
            Text(
                text = story.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Brand.textPrimary,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(32.dp))

            if (revealed) {
                Text(
                    text = story.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Brand.textPrimary,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Text(
                    text = "Copy the passage, then reveal to check.",
                    color = Brand.textSecondary,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(Modifier.height(36.dp))

            Button(
                onClick = { if (playing) stop() else play() },
                colors = ButtonDefaults.buttonColors(containerColor = Brand.teal, contentColor = Brand.navy),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
            ) { Text(if (playing) "■ Stop" else "▶ Play passage", fontWeight = FontWeight.SemiBold) }

            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    revealed = true
                    passagesCopied += 1
                },
                enabled = !revealed,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Reveal text") }

            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { next() },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Next story ›") }
        }
    }
}
