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
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import app.anothermorsetrainer.morsekit.MorseItem
import app.anothermorsetrainer.morsekit.MorseStories
import kotlinx.coroutines.delay

private enum class StPhase { RUNNING, SUMMARY }

/**
 * **Short Stories** (continuous copy): hear a short passage sent end to end,
 * copy it on paper or in your head, then reveal the text to check yourself.
 * Mirrors the iOS story flow (play → reveal → next) without the per-character
 * scoring the recognition drills use. Runs against the configured session
 * length and ends with a summary of the passages copied.
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
    var startedAtMs by remember { mutableStateOf(System.currentTimeMillis()) }

    // Session phase: passages, then a summary once the timer runs out or End is
    // tapped. Back mid-session still records — it just skips the summary.
    var phase by remember { mutableStateOf(StPhase.RUNNING) }
    var remaining by remember { mutableStateOf(Settings.practiceDuration.seconds) }
    var recorded by remember { mutableStateOf(false) }
    var milestone by remember { mutableStateOf<Int?>(null) }

    val story = stories[((index % stories.size) + stories.size) % stories.size]

    DisposableEffect(Unit) { onDispose { player.release() } }

    fun elapsedSeconds(): Int = ((System.currentTimeMillis() - startedAtMs) / 1000L).toInt()

    // Continuous copy is self-checked, so each revealed passage counts as one
    // completed copy — enough for the streak and practice-time totals.
    fun recordSession(): Int? {
        if (recorded) return null
        recorded = true
        return Stats.record(
            mode = "Stories",
            attempts = passagesCopied,
            correct = passagesCopied,
            bestTtrMs = null,
            durationSeconds = elapsedSeconds()
        )
    }

    fun endSession() {
        if (phase == StPhase.SUMMARY) return
        generation += 1
        player.stop()
        playing = false
        milestone = recordSession()
        phase = StPhase.SUMMARY
    }

    fun finish() {
        generation += 1
        player.stop()
        recordSession()
        onBack()
    }

    fun practiceAgain() {
        passagesCopied = 0
        recorded = false
        milestone = null
        startedAtMs = System.currentTimeMillis()
        remaining = Settings.practiceDuration.seconds
        revealed = false
        phase = StPhase.RUNNING
    }

    // Session countdown: ticks only while running and only when a length is set.
    LaunchedEffect(phase) {
        if (phase != StPhase.RUNNING) return@LaunchedEffect
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

    BackHandler { if (phase == StPhase.SUMMARY) onBack() else finish() }

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
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { if (phase == StPhase.SUMMARY) onBack() else finish() }) { Text("‹ Back") }
            if (phase == StPhase.RUNNING) {
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

        if (phase == StPhase.SUMMARY) {
            StorySummaryContent(
                passages = passagesCopied,
                elapsedSeconds = elapsedSeconds(),
                milestone = milestone,
                onPracticeAgain = { practiceAgain() },
                onDone = onBack
            )
        } else {
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
}

/**
 * Story sessions are self-paced continuous copy, so the summary counts passages
 * and time rather than the per-answer tallies the drill modes show.
 */
@Composable
private fun StorySummaryContent(
    passages: Int,
    elapsedSeconds: Int,
    milestone: Int?,
    onPracticeAgain: () -> Unit,
    onDone: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Session complete", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("Short Stories", style = MaterialTheme.typography.labelMedium, color = Brand.textSecondary)

        Spacer(Modifier.height(24.dp))
        Column(modifier = Modifier.fillMaxWidth().brandCard()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Passages copied", color = Brand.textSecondary)
                Text("$passages", color = Brand.textPrimary, fontWeight = FontWeight.SemiBold)
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Time", color = Brand.textSecondary)
                Text(
                    "%d:%02d".format(elapsedSeconds / 60, elapsedSeconds % 60),
                    color = Brand.textPrimary,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        milestone?.let { day ->
            Spacer(Modifier.height(16.dp))
            Column(
                modifier = Modifier.fillMaxWidth().brandCard().padding(vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(milestoneEmoji(day), fontSize = 40.sp)
                Text("$day-day streak!", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("New milestone reached — keep it going.", color = Brand.textSecondary, fontSize = 13.sp)
            }
        }

        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onPracticeAgain,
            colors = ButtonDefaults.buttonColors(containerColor = Brand.teal, contentColor = Brand.navy),
            shape = RoundedCornerShape(Brand.cornerRadius),
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
        ) { Text("Keep copying", fontWeight = FontWeight.SemiBold) }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("Done")
        }
    }
}
