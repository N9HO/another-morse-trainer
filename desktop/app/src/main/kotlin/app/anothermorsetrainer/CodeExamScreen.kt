package app.anothermorsetrainer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.anothermorsetrainer.morsekit.ExamData
import app.anothermorsetrainer.morsekit.ExamResult
import app.anothermorsetrainer.morsekit.ExamSession
import app.anothermorsetrainer.morsekit.ExamSpeed
import app.anothermorsetrainer.morsekit.MorseItem
import kotlin.math.roundToInt

private val OK_GREEN = Color(0xFF2E7D32)
private val ERR_RED = Color(0xFFC62828)
private const val EXAM_SIDETONE_HZ = 600.0

/** The prosign keys the exam's copy row adds (iOS ContentView's examCopyView). */
private val EXAM_PROSIGN_KEYS = listOf("<AR>", "<BT>", "<SK>")

/**
 * The historical FCC/VEC Morse code proficiency exam, graded the way the ARRL
 * VEC graded it.
 *
 * Pick an [ExamSpeed], then sit it: play the QSO-style passage and type what
 * you copy, prosigns included; hand the copy in; fill in ten blanks about the
 * message from that copy. It passes on either path, as the ARRL VEC passed it:
 * one minute of solid copy (25 / 65 / 100 at 5 / 13 / 20 WPM, letters counting
 * one and numerals, punctuation and prosigns two) or seven of the ten blanks.
 * The result shows both grades and which one passed. Drives [ExamSession].
 *
 * Desktop is keyboard-first: the copy field has focus when the sitting opens
 * and Enter hands the copy in; prosigns can be typed as `<AR>` / `<BT>` /
 * `<SK>` or as `+` (AR) and `=` (BT), which grading reads the same as the
 * prosign keys. In a blank, Enter checks the answer and Enter again moves on.
 */
@Composable
fun CodeExamScreen(onBack: () -> Unit, onSwitchMode: (TrainingMode) -> Unit = {}) {
    val player = remember { MorsePlayer() }
    val haptics = remember { Haptics() }

    // The choices persist in Settings across launches (iOS examSpeed /
    // examUseBundled); the exam itself does not. An exam only reaches Stats
    // when it is graded, so a process reclaimed mid-passage has nothing to
    // record — it comes back to setup as chosen.
    val speed = Settings.examSpeed
    val useBundled = Settings.examUseBundled
    var session by remember { mutableStateOf<ExamSession?>(null) }

    // Mid-session Settings, drawn over the exam so its state lives on.
    var showSettings by remember { mutableStateOf(false) }

    /**
     * A fresh session at the chosen speed (iOS makeExamSession): the next
     * bundled passage for that speed when "Use a built-in passage" is on and
     * one exists, otherwise a freshly generated one.
     */
    fun makeSession(): ExamSession {
        if (useBundled) {
            val samples = ExamData.examSamples(speed)
            if (samples.isNotEmpty()) {
                val n = samples.size
                val sample = samples[((Settings.examSampleIndex % n) + n) % n]
                return ExamSession(speed = speed, passage = sample.passage)
            }
        }
        return ExamSession.forRandom(speed = speed)
    }

    /** "New exam": step to the next bundled sample, then rebuild (iOS newExam). */
    fun newSession() {
        Settings.advanceExamSample()
        session = makeSession()
    }

    DisposableEffect(Unit) { onDispose { player.release() } }
    BackHandler { player.stop(); onBack() }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.common_back)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                SessionSettingsButton { showSettings = true }
                // Nothing to record on the way out: an exam reaches Stats only when graded.
                SwitchModeButton(TrainingMode.EXAM) { mode ->
                    player.stop()
                    onSwitchMode(mode)
                }
            }
        }

        val s = session
        if (s == null) {
            ExamSetup(
                speed = speed,
                useBundled = useBundled,
                onSpeed = { Settings.updateExamSpeed(it) },
                onUseBundled = { Settings.updateExamUseBundled(it) },
                onStart = { session = makeSession() }
            )
        } else {
            ExamSitting(
                session = s,
                player = player,
                haptics = haptics,
                onNew = { player.stop(); newSession() },
                onQuit = { player.stop(); session = null }
            )
        }
    }

    if (showSettings) {
        SessionSettingsOverlay(scope = SettingsMode.EXAM, onClose = { showSettings = false })
    }
    }
}

// MARK: - Setup

@Composable
private fun ExamSetup(
    speed: ExamSpeed,
    useBundled: Boolean,
    onSpeed: (ExamSpeed) -> Unit,
    onUseBundled: (Boolean) -> Unit,
    onStart: () -> Unit
) {
    CenteredScrollColumn(
        contentModifier = Modifier.padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(stringResource(R.string.mode_code_exam), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.exam_intro),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))

        Text(stringResource(R.string.common_speed), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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
        Text(stringResource(R.string.exam_how_youll_pass), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(
                R.string.exam_how_youll_pass_body,
                ExamSession.QUESTION_COUNT, speed.requiredRun, ExamSession.QUESTIONS_TO_PASS
            ),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )

        // "Use a built-in passage" (iOS IntroView's exam setup card): a
        // ready-made text for the chosen speed instead of a generated one.
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.exam_use_bundled), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.exam_use_bundled_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = Brand.textSecondary
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = useBundled,
                onCheckedChange = onUseBundled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Brand.navy,
                    checkedTrackColor = Brand.teal,
                    uncheckedThumbColor = Brand.textSecondary,
                    uncheckedTrackColor = Brand.navyRaised
                )
            )
        }

        Spacer(Modifier.height(24.dp))
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(stringResource(R.string.exam_start_exam), fontSize = 18.sp)
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

// MARK: - The sitting: copy, then the blanks, then both grades

private enum class ExamStep { COPY, QUESTIONS, RESULTS }

@Composable
private fun ExamSitting(
    session: ExamSession,
    player: MorsePlayer,
    haptics: Haptics,
    onNew: () -> Unit,
    onQuit: () -> Unit
) {
    var step by remember(session) { mutableStateOf(ExamStep.COPY) }
    var typed by remember(session) { mutableStateOf("") }
    var playing by remember(session) { mutableStateOf(false) }
    // The question on screen, read from a LOCAL index: ExamSession.answer()
    // advances its own index when it scores, so reading it back during the
    // feedback would jump to the next blank. qIndex stays until Next.
    var qIndex by remember(session) { mutableIntStateOf(0) }
    var answer by remember(session) { mutableStateOf("") }
    var lastRight by remember(session) { mutableStateOf<Boolean?>(null) }
    var result by remember(session) { mutableStateOf<ExamResult?>(null) }
    var revealed by remember(session) { mutableStateOf(false) }

    val total = session.questions.size

    fun playPassage() {
        playing = true
        player.play(MorseItem.Playable.Text(session.passage.sentText), EXAM_SIDETONE_HZ, session.speed.timing) {
            playing = false
        }
    }

    fun check() {
        if (lastRight != null) return
        val right = session.answer(answer)
        lastRight = right
        if (Settings.hapticsEnabled) {
            if (right) haptics.success() else haptics.error()
        }
    }

    fun handIn() {
        if (step != ExamStep.COPY) return
        player.stop()
        playing = false
        session.submitCopy(typed)
        step = ExamStep.QUESTIONS
    }

    fun next() {
        if (lastRight == null) return
        answer = ""
        lastRight = null
        if (qIndex >= total - 1) {
            // One exam = one recorded attempt, passed on either path.
            val r = session.result
            result = r
            step = ExamStep.RESULTS
            Stats.record(
                mode = "Code Exam",
                attempts = 1,
                correct = if (r.passed) 1 else 0,
                bestTtrMs = null,
                characterWpm = session.speed.characterWpm.roundToInt(),
                effectiveWpm = session.speed.effectiveWpm.roundToInt()
            )
            if (Settings.hapticsEnabled) {
                if (r.passed) haptics.success() else haptics.error()
            }
        } else {
            qIndex += 1
        }
    }

    // Desktop: the field for the current step has the keyboard as soon as the
    // step opens — the copy when the sitting starts, the answer for each blank.
    val copyFocus = remember(session) { FocusRequester() }
    val answerFocus = remember(session) { FocusRequester() }
    LaunchedEffect(step, qIndex) {
        when (step) {
            ExamStep.COPY -> runCatching { copyFocus.requestFocus() }
            ExamStep.QUESTIONS -> runCatching { answerFocus.requestFocus() }
            ExamStep.RESULTS -> Unit
        }
    }

    // Scrolling alone did not reach the grade button on Android (issue #44):
    // the scroll viewport ran on behind the on-screen keyboard. imePadding
    // sits outside the scroll so the viewport itself shrinks. Desktop: there
    // is no on-screen keyboard and the modifier does nothing; kept so the
    // layout matches the Android tree.
    CenteredScrollColumn(
        modifier = Modifier.imePadding(),
        contentModifier = Modifier.padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(stringResource(R.string.exam_title_with_speed, session.speed.wpmLabel), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(session.speed.passLabel, style = MaterialTheme.typography.bodySmall, color = Brand.textSecondary)
        Spacer(Modifier.height(8.dp))

        when (step) {
            ExamStep.COPY -> {
                Text(
                    stringResource(
                        R.string.exam_solid_copy_instructions,
                        total, session.requiredRun, ExamSession.QUESTIONS_TO_PASS
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(20.dp))
                Button(onClick = { playPassage() }, enabled = !playing, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text(if (playing) stringResource(R.string.exam_sending) else stringResource(R.string.exam_play_transmission), fontSize = 18.sp)
                }
                Spacer(Modifier.height(16.dp))
                MorseNumberRow(
                    onKey = { typed = appendKey(typed, it) },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    prosigns = EXAM_PROSIGN_KEYS
                )
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it.uppercase() },
                    label = { Text(stringResource(R.string.exam_your_copy)) },
                    supportingText = { Text(stringResource(R.string.exam_copy_enter_hint)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .focusRequester(copyFocus)
                        // Enter hands the copy in (it is one run of text; a
                        // line break adds nothing).
                        .onPreviewKeyEvent { event ->
                            if (event.key == Key.Enter || event.key == Key.NumPadEnter) {
                                if (event.type == KeyEventType.KeyDown) handIn()
                                true
                            } else false
                        }
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { handIn() },
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Text(stringResource(R.string.exam_grade_my_copy), fontSize = 18.sp)
                }
            }

            ExamStep.QUESTIONS -> {
                val q = session.questions[qIndex]
                Text(stringResource(R.string.exam_question_counter, qIndex + 1, total), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(8.dp))
                // The copy stays in view: the ARRL candidate answered from it.
                if (typed.isNotBlank()) {
                    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(stringResource(R.string.exam_your_copy_label), style = MaterialTheme.typography.labelSmall, color = Brand.textSecondary)
                            Text(typed, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                Text(
                    q.prompt,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                MorseNumberRow(
                    onKey = { if (lastRight == null) answer = appendKey(answer, it) },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                )
                OutlinedTextField(
                    value = answer,
                    onValueChange = { if (lastRight == null) answer = it.uppercase() },
                    label = { Text(stringResource(R.string.exam_answer)) },
                    singleLine = true,
                    // Read-only rather than disabled once checked, so the field
                    // keeps the keyboard and the next Enter moves on.
                    readOnly = lastRight != null,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { if (lastRight == null) check() else next() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(answerFocus)
                        // Enter checks the blank; Enter again moves to the next.
                        .onPreviewKeyEvent { event ->
                            if (event.key == Key.Enter || event.key == Key.NumPadEnter) {
                                if (event.type == KeyEventType.KeyDown) {
                                    if (lastRight == null) check() else next()
                                }
                                true
                            } else false
                        }
                )
                Spacer(Modifier.height(12.dp))
                val right = lastRight
                if (right == null) {
                    Button(onClick = { check() }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Text(stringResource(R.string.exam_check))
                    }
                } else {
                    Text(
                        if (right) stringResource(R.string.exam_correct) else stringResource(R.string.exam_not_quite, q.answer),
                        color = if (right) OK_GREEN else ERR_RED,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { next() }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Text(if (qIndex >= total - 1) stringResource(R.string.exam_see_results) else stringResource(R.string.exam_next_question))
                    }
                }
            }

            ExamStep.RESULTS -> {
                val r = result ?: session.result
                Spacer(Modifier.height(8.dp))
                Text(
                    if (r.passed) stringResource(R.string.exam_pass) else stringResource(R.string.exam_not_yet),
                    color = if (r.passed) OK_GREEN else ERR_RED,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(r.pathText, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Text(
                    (if (r.passedByCopy) "✓ " else "✗ ") +
                        stringResource(R.string.exam_longest_run, r.copy.longestRun, r.copy.required),
                    color = if (r.passedByCopy) OK_GREEN else Brand.textSecondary,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    (if (r.passedByQuestions) "✓ " else "✗ ") +
                        stringResource(R.string.exam_questions_score, r.questionsCorrect, r.questionsAsked, r.questionsRequired),
                    color = if (r.passedByQuestions) OK_GREEN else Brand.textSecondary,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    stringResource(R.string.exam_counting_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = Brand.textSecondary,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))

                OutlinedButton(onClick = { revealed = !revealed }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (revealed) stringResource(R.string.exam_hide_what_was_sent) else stringResource(R.string.exam_show_what_was_sent))
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
                    OutlinedButton(onClick = onQuit, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.common_done)) }
                    Button(onClick = onNew, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.exam_new_exam)) }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
