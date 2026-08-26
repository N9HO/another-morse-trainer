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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.anothermorsetrainer.morsekit.ExamGrading
import app.anothermorsetrainer.morsekit.ExamSession
import app.anothermorsetrainer.morsekit.ExamSpeed
import app.anothermorsetrainer.morsekit.MorseItem
import kotlin.math.roundToInt

private val OK_GREEN = Color(0xFF2E7D32)
private val ERR_RED = Color(0xFFC62828)
private const val EXAM_SIDETONE_HZ = 600.0

/**
 * The historical FCC/VEC Morse code proficiency exam, reproduced in format.
 *
 * Three steps: pick a [ExamSpeed] and an [ExamGrading] mode, then run it.
 * Solid-copy mode plays the whole QSO-style passage and asks you to type what
 * you got (graded on the longest run of consecutive-correct characters, the old
 * "25 in a row" rule). Question mode plays the passage once, then asks fill-in
 * questions about what was sent. Both drive the fully-ported [ExamSession].
 */
@Composable
fun CodeExamScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val player = remember { MorsePlayer() }
    val haptics = remember { Haptics(context) }

    var speed by remember { mutableStateOf(ExamSpeed.GENERAL13) }
    var grading by remember { mutableStateOf(ExamGrading.SOLID_COPY) }
    var session by remember { mutableStateOf<ExamSession?>(null) }

    DisposableEffect(Unit) { onDispose { player.release() } }
    BackHandler { player.stop(); onBack() }

    Column(modifier = Modifier.fillMaxSize()) {
        TextButton(onClick = onBack, modifier = Modifier.padding(8.dp)) { Text("‹ Back") }

      CenteredContent {
        val s = session
        if (s == null) {
            ExamSetup(
                speed = speed,
                grading = grading,
                onSpeed = { speed = it },
                onGrading = { grading = it },
                onStart = { session = ExamSession.forRandom(speed = speed, grading = grading) }
            )
        } else {
            when (s.grading) {
                ExamGrading.SOLID_COPY -> SolidCopyExam(
                    session = s,
                    player = player,
                    haptics = haptics,
                    onNew = { session = ExamSession.forRandom(speed = speed, grading = grading) },
                    onQuit = { player.stop(); session = null }
                )
                ExamGrading.QUESTIONS -> QuestionsExam(
                    session = s,
                    player = player,
                    haptics = haptics,
                    onNew = { session = ExamSession.forRandom(speed = speed, grading = grading) },
                    onQuit = { player.stop(); session = null }
                )
            }
        }
      }
    }
}

// MARK: - Setup

@Composable
private fun ExamSetup(
    speed: ExamSpeed,
    grading: ExamGrading,
    onSpeed: (ExamSpeed) -> Unit,
    onGrading: (ExamGrading) -> Unit,
    onStart: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Code Exam", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "The old FCC code test: copy a 5-minute QSO at speed, then prove it.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))

        Text("Speed", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        ExamSpeed.allCases.forEach { option ->
            ChoiceRow(
                label = option.label,
                selected = option == speed,
                onClick = { onSpeed(option) }
            )
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(16.dp))
        Text("How you'll pass", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        ExamGrading.allCases.forEach { option ->
            ChoiceRow(
                label = option.label,
                selected = option == grading,
                onClick = { onGrading(option) }
            )
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(24.dp))
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("Start Exam", fontSize = 18.sp)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = if (selected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        },
        elevation = CardDefaults.cardElevation(defaultElevation = if (selected) 4.dp else 1.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(if (selected) "●  " else "○  ", fontSize = 16.sp)
            Text(label, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

// MARK: - Solid copy

@Composable
private fun SolidCopyExam(
    session: ExamSession,
    player: MorsePlayer,
    haptics: Haptics,
    onNew: () -> Unit,
    onQuit: () -> Unit
) {
    var typed by remember { mutableStateOf("") }
    var graded by remember { mutableStateOf(false) }
    var revealed by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }

    fun playPassage() {
        playing = true
        player.play(MorseItem.Playable.Text(session.passage.sentText), EXAM_SIDETONE_HZ, session.speed.timing) {
            playing = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Code Exam · ${session.speed.wpmLabel}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Play the transmission, then type what you copied. " +
                "Pass = ${ExamSession.requiredRun} characters in a row.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))

        Button(onClick = { playPassage() }, enabled = !playing, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(if (playing) "Sending…" else "▶ Play transmission", fontSize = 18.sp)
        }
        Spacer(Modifier.height(16.dp))

        MorseNumberRow(
            onKey = { if (!graded) typed += it },
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        )
        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it.uppercase() },
            label = { Text("Your copy") },
            modifier = Modifier.fillMaxWidth().height(160.dp)
        )
        Spacer(Modifier.height(12.dp))

        if (!graded) {
            Button(
                onClick = {
                    val result = session.gradeSolidCopy(typed)
                    graded = true
                    // One exam = one recorded attempt; a pass counts as correct.
                    Stats.record(
                        mode = "Code Exam",
                        attempts = 1,
                        correct = if (result.passed) 1 else 0,
                        bestTtrMs = null,
                        characterWpm = session.speed.characterWpm.roundToInt()
                    )
                    if (Settings.hapticsEnabled) {
                        if (result.passed) haptics.success() else haptics.error()
                    }
                },
                enabled = typed.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Text("Grade my copy", fontSize = 18.sp)
            }
        } else {
            val result = session.gradeSolidCopy(typed)
            Text(
                if (result.passed) "✓ PASS" else "✗ Not yet",
                color = if (result.passed) OK_GREEN else ERR_RED,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Longest solid run: ${result.longestRun} / ${result.required}",
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(Modifier.height(16.dp))

            OutlinedButton(onClick = { revealed = !revealed }, modifier = Modifier.fillMaxWidth()) {
                Text(if (revealed) "Hide what was sent" else "Show what was sent")
            }
            if (revealed) {
                Spacer(Modifier.height(8.dp))
                Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
                    Text(
                        session.passage.displayText,
                        modifier = Modifier.padding(16.dp),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onQuit, modifier = Modifier.weight(1f)) { Text("Done") }
                Button(
                    onClick = { player.stop(); onNew() },
                    modifier = Modifier.weight(1f)
                ) { Text("New passage") }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// MARK: - Questions

@Composable
private fun QuestionsExam(
    session: ExamSession,
    player: MorsePlayer,
    haptics: Haptics,
    onNew: () -> Unit,
    onQuit: () -> Unit
) {
    // Drive display off a LOCAL index: ExamSession.record() advances its own
    // questionIndex when we score, so reading it back during the reveal would
    // jump to the next question. qIndex stays put until the user taps Next.
    var qIndex by remember { mutableStateOf(0) }
    var chosen by remember { mutableStateOf<String?>(null) }
    var revealed by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var heardPassage by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }

    val total = session.questions.size

    fun playPassage() {
        playing = true
        player.play(MorseItem.Playable.Text(session.passage.sentText), EXAM_SIDETONE_HZ, session.speed.timing) {
            playing = false
            heardPassage = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        run {
            if (!heardPassage) {
                Text("Code Exam · ${session.speed.wpmLabel}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Listen to the whole QSO once, then answer $total questions about it.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(20.dp))
                Button(onClick = { playPassage() }, enabled = !playing, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text(if (playing) "Sending…" else "▶ Play transmission", fontSize = 18.sp)
                }
            } else if (!finished) {
                val q = session.questions[qIndex]
                Text("Q ${qIndex + 1} of $total", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(16.dp))
                Text(
                    q.prompt,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(24.dp))
                q.options.forEach { option ->
                    val colors = when {
                        revealed && option == q.answer -> ButtonDefaults.buttonColors(containerColor = OK_GREEN)
                        revealed && option == chosen -> ButtonDefaults.buttonColors(containerColor = ERR_RED)
                        else -> ButtonDefaults.buttonColors()
                    }
                    Button(
                        onClick = {
                            if (!revealed) {
                                chosen = option
                                val outcome = session.record(option, 0.0)
                                if (Settings.hapticsEnabled) {
                                    if (outcome.correct) haptics.success() else haptics.error()
                                }
                                revealed = true
                            }
                        },
                        colors = colors,
                        modifier = Modifier.fillMaxWidth().height(52.dp).padding(vertical = 2.dp)
                    ) {
                        Text(option, fontSize = 17.sp, textAlign = TextAlign.Center)
                    }
                }
                if (revealed) {
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = {
                            chosen = null
                            revealed = false
                            if (qIndex >= total - 1) {
                                finished = true
                                // The finished exam lands in stats: one row per
                                // question, correct answers counted.
                                Stats.record(
                                    mode = "Code Exam",
                                    attempts = total,
                                    correct = session.correctCount,
                                    bestTtrMs = null,
                                    characterWpm = session.speed.characterWpm.roundToInt()
                                )
                            } else {
                                qIndex += 1
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Text(if (qIndex >= total - 1) "See results" else "Next question")
                    }
                }
            } else {
                val score = session.correctCount
                val passed = total > 0 && score.toDouble() / total >= 0.7  // historical bar ≈ 70%
                Spacer(Modifier.height(24.dp))
                Text(
                    if (passed) "✓ PASS" else "✗ Not yet",
                    color = if (passed) OK_GREEN else ERR_RED,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Text("$score / $total correct", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(28.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = onQuit, modifier = Modifier.weight(1f)) { Text("Done") }
                    Button(onClick = onNew, modifier = Modifier.weight(1f)) { Text("New exam") }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
