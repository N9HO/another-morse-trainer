package app.anothermorsetrainer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.anothermorsetrainer.morsekit.MorseItem
import app.anothermorsetrainer.morsekit.MorseTiming
import app.anothermorsetrainer.morsekit.OpChoice
import app.anothermorsetrainer.morsekit.OpDemo
import app.anothermorsetrainer.morsekit.OpLesson
import app.anothermorsetrainer.morsekit.OpPileupVoice
import app.anothermorsetrainer.morsekit.OpScenario
import app.anothermorsetrainer.morsekit.OpScenarioRun
import app.anothermorsetrainer.morsekit.OperatingProcedure
import app.anothermorsetrainer.morsekit.OperatingProcedureProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * **CW Operating Procedure** (#294, #295, docs/operating-procedure-design.md):
 * on-air etiquette for hunting POTA activators, one rule at a time. Eight
 * lessons (concept, demo, quick scenarios — the eighth is #294's zero beat,
 * RIT and XIT lesson with its pileup demo and drill) and "What should you
 * do?", a scenario mode over them. The rules are MorseKit's
 * [OperatingProcedure]; this screen only sounds, shows and records them.
 *
 * Ported from MorseTrainerApp/OperatingProcedureView.swift (with the glue in
 * AppModel+OperatingProcedure.swift); twin of the Android tree's own
 * OperatingProcedureScreen.
 *
 * Teaching material, not a session: no session record, no leaderboard. A
 * graded answer counts as practice for the day ([Stats.recordPracticeDay],
 * as Daily Dit does).
 *
 * First Four (#280 on desktop) is linked from the lesson list, not moved in.
 */
@Composable
fun OperatingProcedureScreen(onBack: () -> Unit, onOpenFirstFour: () -> Unit) {
    val scope = rememberCoroutineScope()
    val audio = remember { OpAudio(scope) }
    DisposableEffect(Unit) {
        onDispose { audio.release() }
    }

    // The screen's own copy of the progress, as iOS loads it on open: the
    // drill's in-a-row streak belongs to this sitting, not to the store.
    var progress by remember { mutableStateOf(OperatingProcedureStore.progress.copy(drillStreak = 0)) }
    val updateProgress: (OperatingProcedureProgress) -> Unit = { p ->
        progress = p
        OperatingProcedureStore.save(p)
    }

    var page by remember { mutableStateOf<OpPage>(OpPage.Home) }
    var editingStation by remember { mutableStateOf(false) }
    var callField by remember { mutableStateOf(OperatingProcedure.prefillCall(PileupSettings.myCall)) }
    var stateField by remember { mutableStateOf(OperatingProcedure.normalizeState(PileupSettings.myState)) }
    var confirmingReset by remember { mutableStateOf(false) }

    val call = OperatingProcedure.normalizeCall(PileupSettings.myCall)
    val state = OperatingProcedure.normalizeState(PileupSettings.myState)
    val stationReady = OperatingProcedure.prefillCall(call).isNotEmpty() &&
        OperatingProcedure.isValidCall(call) && OperatingProcedure.isValidState(state)
    val showingStation = !stationReady || editingStation

    fun open(p: OpPage) {
        audio.stop()
        page = p
    }

    // Escape: out of a lesson or the scenario mode to the lesson list; out of
    // an edit of a station that is already set; otherwise home.
    BackHandler {
        when {
            editingStation && stationReady -> editingStation = false
            !showingStation && page != OpPage.Home -> open(OpPage.Home)
            else -> onBack()
        }
    }

    val title = when {
        showingStation -> stringResource(R.string.op_title)
        else -> when (val p = page) {
            OpPage.Home -> stringResource(R.string.op_title)
            is OpPage.Lesson -> OpCopy.title(p.lesson)
            OpPage.Scenarios -> stringResource(R.string.op_scenarios_title)
        }
    }
    val inSubPage = !showingStation && page != OpPage.Home
    val wide = isWideLayout()

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { if (inSubPage) open(OpPage.Home) else onBack() }) {
                Text(
                    stringResource(if (inSubPage) R.string.op_back_lessons else R.string.common_back),
                    color = Brand.teal
                )
            }
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Brand.textPrimary,
                maxLines = 1
            )
        }

        CenteredScrollColumn(
            contentModifier = Modifier
                .padding(horizontal = 20.dp)
                .padding(top = 8.dp, bottom = 32.dp),
            maxWidth = if (wide) WIDE_CONTENT_MAX_WIDTH else CONTENT_MAX_WIDTH,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            if (showingStation) {
                OpStationCard(
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    callField = callField,
                    stateField = stateField,
                    editing = editingStation,
                    onCall = { callField = it },
                    onState = { stateField = it },
                    onSave = {
                        PileupSettings.updateMyCall(OperatingProcedure.normalizeCall(callField))
                        PileupSettings.updateMyState(OperatingProcedure.normalizeState(stateField))
                        editingStation = false
                    }
                )
            } else {
                when (val p = page) {
                    OpPage.Home -> OpHome(
                        call = call,
                        state = state,
                        progress = progress,
                        wide = wide,
                        onChange = {
                            callField = call
                            stateField = state
                            editingStation = true
                        },
                        onOpen = { open(it) },
                        onStartOver = { confirmingReset = true },
                        onOpenFirstFour = {
                            audio.stop()
                            onOpenFirstFour()
                        }
                    )
                    is OpPage.Lesson -> key(p.lesson) {
                        OpLessonPage(
                            lesson = p.lesson,
                            call = call,
                            state = state,
                            progress = progress,
                            onProgress = updateProgress,
                            audio = audio,
                            wide = wide,
                            onNext = { open(progress.nextLesson?.let { OpPage.Lesson(it) } ?: OpPage.Home) }
                        )
                    }
                    OpPage.Scenarios -> OpScenarioMode(
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                        call = call,
                        state = state,
                        audio = audio,
                        onOpenLesson = { open(OpPage.Lesson(it)) }
                    )
                }
            }
        }
    }

    if (confirmingReset) {
        AlertDialog(
            onDismissRequest = { confirmingReset = false },
            containerColor = Brand.navyElevated,
            title = { Text(stringResource(R.string.op_reset_title), color = Brand.textPrimary) },
            text = { Text(stringResource(R.string.op_reset_body), color = Brand.textSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    OperatingProcedureStore.reset()
                    progress = OperatingProcedureProgress()
                    confirmingReset = false
                }) { Text(stringResource(R.string.op_start_over), color = OP_WRONG, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingReset = false }) {
                    Text(stringResource(R.string.common_cancel), color = Brand.teal)
                }
            }
        )
    }
}

private sealed interface OpPage {
    data object Home : OpPage
    data class Lesson(val lesson: OpLesson) : OpPage
    data object Scenarios : OpPage
}

/** iOS's `.orange` for a wrong answer or a warning. */
private val OP_WRONG = Brand.warning

// ---- Audio --------------------------------------------------------------------

/**
 * The section's sounds, at the learner's own speed and tone (iOS
 * AppModel+OperatingProcedure.swift): single clips, a demo's lines one after
 * another with a 0.6 s pause, the pileup demo through the Pileup Runner's
 * mixer, and the drill's and RIT demo's tones. A newer play cancels the rest
 * of an older demo; leaving the screen stops everything.
 */
/** WB0RLJ's "Advice for CW POTA Hunters", and his channel (the section's credit). */
internal const val OP_ADVICE_URL = "https://www.qrz.com/db/WB0RLJ#Advice"
internal const val OP_YOUTUBE_URL = "https://www.youtube.com/@WB0RLJ"

private class OpAudio(private val scope: CoroutineScope) {
    private val player = MorsePlayer()
    private var lines: Job? = null

    private fun cancelLines() {
        lines?.cancel()
        lines = null
    }

    /**
     * Sound one transmission, piece by piece: text at the learner's timing,
     * and each error (`<ERR>`) in its own shape — 5 to 8 dits, run together
     * or slapped, at its own speed (maintainer, 2026-10-03) — a word gap
     * apart. Suspends until it has played.
     */
    private suspend fun sound(text: String) {
        val timing = Settings.timing()
        val parts = OperatingProcedure.clipParts(text)
        parts.forEachIndexed { i, part ->
            val seconds = when (part) {
                is OperatingProcedure.ClipPart.Text ->
                    player.replaySound(MorseItem.Playable.Text(part.text), Settings.sidetoneHz, timing)
                is OperatingProcedure.ClipPart.Error -> {
                    val v = OperatingProcedure.errorVariant(
                        part.row ?: Random.nextInt(OperatingProcedure.errorVariants.size)
                    )
                    val t = MorseTiming(maxOf(OperatingProcedure.MINIMUM_WPM, Settings.characterWpm * v.speed))
                    val playable = if (v.runTogether) MorseItem.Playable.Pattern(v.pattern)
                    else MorseItem.Playable.Text(v.spacedText)
                    player.replaySound(playable, Settings.sidetoneHz, t)
                }
            }
            val gap = if (i < parts.lastIndex) timing.wordGap else 0.0
            delay(((seconds + gap) * 1000).toLong())
        }
    }

    fun play(text: String) {
        cancelLines()
        lines = scope.launch { sound(text) }
    }

    /** [onLine] is told which line is sounding, and null once it has all played (or was cut short). */
    fun playLines(demoLines: List<OpDemo.Line>, onLine: (Int?) -> Unit) {
        cancelLines()
        lines = scope.launch {
            try {
                for (i in demoLines.indices) {
                    onLine(i)
                    sound(demoLines[i].text)
                    delay(600)
                }
            } finally {
                onLine(null)
            }
        }
    }

    /** One pass of the pileup demo. */
    fun playPileup(voices: List<OpPileupVoice>, onFinished: () -> Unit) {
        cancelLines()
        player.playPileup(
            voices.map { v ->
                MorsePlayer.PileupVoice(
                    text = v.text,
                    frequency = v.pitch,
                    timing = MorseTiming(v.wpm),
                    gain = v.gain.toFloat(),
                    startDelay = v.delay,
                    qsbRate = null
                )
            },
            0f
        ) { onFinished() }
    }

    /** A station at [pitch]; with [sidetone], your own sidetone sounding with it, so the beat can be heard. */
    fun playTone(text: String, pitch: Double, sidetone: Double? = null) {
        cancelLines()
        val t = Settings.timing()
        val voices = mutableListOf(
            MorsePlayer.PileupVoice(text, OperatingProcedure.audible(pitch), t, 0.8f, 0.0, null)
        )
        if (sidetone != null) {
            voices += MorsePlayer.PileupVoice(text, OperatingProcedure.audible(sidetone), t, 0.8f, 0.0, null)
        }
        player.playPileup(voices, 0f) {}
    }

    fun stop() {
        cancelLines()
        player.stop()
    }

    fun release() {
        cancelLines()
        player.stop()
        player.release()
    }
}

/** A graded answer is practice for the day, as a Daily Dit guess is. */
private fun noteOperatingPractice() = Stats.recordPracticeDay()

private fun signed(v: Double): String {
    val i = v.roundToInt()
    return if (i >= 0) "+$i" else "$i"
}

// ---- Shared bits ----------------------------------------------------------------

@Composable
private fun OpCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier.fillMaxWidth().brandCard(14.dp).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) { content() }
}

@Composable
private fun OpEyebrow(text: String) {
    Text(text, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, color = Brand.textSecondary)
}

@Composable
private fun OpPrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 44.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Brand.teal, contentColor = Brand.navy)
    ) {
        Text(text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}

@Composable
private fun OpSecondaryButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier) {
        Text(text, color = Brand.teal)
    }
}

@Composable
private fun OpChevron() {
    Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = Brand.textSecondary, modifier = Modifier.size(18.dp))
}

// ---- Your station -------------------------------------------------------------

@Composable
private fun OpStationCard(
    modifier: Modifier,
    callField: String,
    stateField: String,
    editing: Boolean,
    onCall: (String) -> Unit,
    onState: (String) -> Unit,
    onSave: () -> Unit
) {
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Brand.textPrimary,
        unfocusedTextColor = Brand.textPrimary,
        focusedBorderColor = Brand.teal,
        unfocusedBorderColor = Brand.navyRaised,
        cursorColor = Brand.teal,
        focusedContainerColor = Brand.navyRaised,
        unfocusedContainerColor = Brand.navyRaised
    )
    val canSave = OperatingProcedure.isValidCall(callField) && OperatingProcedure.isValidState(stateField) &&
        OperatingProcedure.prefillCall(callField).isNotEmpty()
    Column(
        modifier = modifier.widthIn(max = CONTENT_MAX_WIDTH).fillMaxWidth().brandCard().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            stringResource(R.string.op_station_heading),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = Brand.textPrimary
        )
        Text(stringResource(R.string.op_station_lead), fontSize = 14.sp, color = Brand.textSecondary)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.op_station_call_label), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Brand.textSecondary)
            OutlinedTextField(
                value = callField,
                onValueChange = { onCall(it.uppercase().filter { c -> c.isLetterOrDigit() || c == '/' }.take(10)) },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.op_station_call_placeholder), color = Brand.textSecondary, fontFamily = FontFamily.Monospace) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                textStyle = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                stringResource(R.string.op_station_state_label),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = Brand.textSecondary,
                modifier = Modifier.padding(top = 6.dp)
            )
            OutlinedTextField(
                value = stateField,
                onValueChange = { onState(it.uppercase().filter { c -> c.isLetter() }.take(3)) },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.op_station_state_placeholder), color = Brand.textSecondary, fontFamily = FontFamily.Monospace) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                textStyle = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (callField.isNotEmpty() && !OperatingProcedure.isValidCall(callField)) {
            Text(stringResource(R.string.op_station_call_invalid), fontSize = 12.sp, color = OP_WRONG)
        }
        if (stateField.isNotEmpty() && !OperatingProcedure.isValidState(stateField)) {
            Text(stringResource(R.string.op_station_state_invalid), fontSize = 12.sp, color = OP_WRONG)
        }
        Text(stringResource(R.string.op_station_saved_note), fontSize = 12.sp, color = Brand.textSecondary)
        OpPrimaryButton(
            stringResource(if (editing) R.string.op_station_save else R.string.common_start),
            modifier = Modifier.heightIn(min = 50.dp),
            enabled = canSave,
            onClick = onSave
        )
    }
}

// ---- The lesson list ----------------------------------------------------------

@Composable
private fun OpHome(
    call: String,
    state: String,
    progress: OperatingProcedureProgress,
    wide: Boolean,
    onChange: () -> Unit,
    onOpen: (OpPage) -> Unit,
    onStartOver: () -> Unit,
    onOpenFirstFour: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.op_home_lead), fontSize = 14.sp, color = Brand.textSecondary)

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SlashableText(
                    stringResource(R.string.op_home_station, call, state),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    color = Brand.textPrimary
                )
                Text(
                    stringResource(R.string.op_home_passed_count, progress.passedCount, OpLesson.entries.size),
                    fontSize = 12.sp,
                    color = Brand.textSecondary
                )
            }
            TextButton(onClick = onChange) { Text(stringResource(R.string.op_change), color = Brand.teal) }
        }

        // First Four is the on-ramp: linked from here, not moved in (design note).
        OpFirstFourRow(onOpenFirstFour)

        val next = progress.nextLesson
        if (next != null) {
            Button(
                onClick = { onOpen(OpPage.Lesson(next)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Brand.teal, contentColor = Brand.navy)
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(
                        if (progress.passedCount == 0) R.string.op_begin else R.string.op_continue,
                        OpCopy.title(next)
                    ),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Verified, contentDescription = null, tint = Brand.tealBright, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.op_all_passed), fontWeight = FontWeight.SemiBold, color = Brand.tealBright)
            }
        }

        // Two columns on a wide window, so eight lessons fit without a long
        // scroll; one otherwise.
        val numbered = OpLesson.entries.mapIndexed { i, l -> (i + 1) to l }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (wide) {
                numbered.chunked(2).forEach { pair ->
                    Row(
                        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        pair.forEach { (n, l) ->
                            OpLessonRow(n, l, progress.hasPassed(l), Modifier.weight(1f).fillMaxHeight()) { onOpen(OpPage.Lesson(l)) }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            } else {
                numbered.forEach { (n, l) ->
                    OpLessonRow(n, l, progress.hasPassed(l), Modifier.fillMaxWidth()) { onOpen(OpPage.Lesson(l)) }
                }
            }
        }

        // "What should you do?"
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Brand.navyElevated)
                .border(1.dp, Brand.tealBright.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                .clickable { onOpen(OpPage.Scenarios) }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.QuestionAnswer, contentDescription = null, tint = Brand.teal, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.op_scenarios_title), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Brand.textPrimary)
                Text(
                    stringResource(R.string.op_scenarios_row_sub, OperatingProcedure.SCENARIO_RUN_LENGTH),
                    fontSize = 12.sp,
                    color = Brand.textSecondary
                )
            }
            OpChevron()
        }

        Text(
            stringResource(R.string.op_home_footer, Settings.characterWpm.toInt()),
            fontSize = 12.sp,
            color = Brand.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        // The credit (maintainer, 2026-10-03): WB0RLJ's advice, linked, and
        // his channel of daily activation recordings.
        Text(
            stringResource(R.string.op_credit),
            fontSize = 12.sp,
            color = Brand.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        val uriHandler = LocalUriHandler.current
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            TextButton(onClick = { uriHandler.openUri(OP_ADVICE_URL) }) {
                Text(stringResource(R.string.op_credit_advice), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Brand.teal)
            }
            TextButton(onClick = { uriHandler.openUri(OP_YOUTUBE_URL) }) {
                Text(stringResource(R.string.op_credit_youtube), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Brand.teal)
            }
        }

        if (progress.passedCount > 0 || progress.drillPassed || progress.cleanRuns.isNotEmpty()) {
            TextButton(onClick = onStartOver, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(stringResource(R.string.op_start_over), color = OP_WRONG)
            }
        }
    }
}

@Composable
private fun OpFirstFourRow(onClick: () -> Unit) {
    // Read so a First Four save recomposes the row.
    @Suppress("UNUSED_VARIABLE")
    val version = FirstFourStore.version
    val done = FirstFourStore.progress.isComplete
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Brand.navyElevated)
            .border(1.dp, Brand.teal.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (done) Icons.Filled.CheckCircle else Icons.Filled.CellTower,
            contentDescription = null,
            tint = if (done) Brand.tealBright else Brand.teal
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(if (done) R.string.op_first_four_done else R.string.op_first_four_new),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Brand.textPrimary
            )
            Text(stringResource(R.string.op_first_four_sub), fontSize = 12.sp, color = Brand.textSecondary)
        }
        OpChevron()
    }
}

@Composable
private fun OpLessonRow(number: Int, lesson: OpLesson, passed: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val title = OpCopy.title(lesson)
    val a11y = if (passed) stringResource(R.string.op_lesson_row_passed_a11y, number, title)
        else stringResource(R.string.op_lesson_row_a11y, number, title)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Brand.navyElevated)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = a11y }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(32.dp).clip(CircleShape).background(if (passed) Brand.teal else Brand.navyRaised),
            contentAlignment = Alignment.Center
        ) {
            if (passed) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = Brand.navy, modifier = Modifier.size(18.dp))
            } else {
                Text("$number", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Brand.textPrimary)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Brand.textPrimary)
            Text(OpCopy.subtitle(lesson), fontSize = 12.sp, color = Brand.textSecondary)
        }
        OpChevron()
    }
}

// ---- A lesson -----------------------------------------------------------------

@Composable
private fun OpLessonPage(
    lesson: OpLesson,
    call: String,
    state: String,
    progress: OperatingProcedureProgress,
    onProgress: (OperatingProcedureProgress) -> Unit,
    audio: OpAudio,
    wide: Boolean,
    onNext: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                OpCopy.subtitle(lesson),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = Brand.textSecondary,
                modifier = Modifier.weight(1f)
            )
            if (progress.hasPassed(lesson)) OpPassedBadge(stringResource(R.string.op_passed))
        }
        val learn: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OpConceptCard(lesson, call, state)
                if (lesson != OpLesson.OFFSET) {
                    OpDemoCard(OperatingProcedure.demos(lesson, call, state), audio)
                } else {
                    OpPileupDemoCard(call, audio)
                    OpRitDemoCard(audio)
                }
            }
        }
        val practice: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (lesson == OpLesson.OFFSET) OpZeroBeatDrill(progress, onProgress, audio)
                OpLessonRun(lesson, call, state, progress, onProgress, audio, onNext)
            }
        }
        if (wide) {
            // Concept and demos beside the practice, so the rule stays in view
            // while you answer.
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.weight(1f)) { learn() }
                Box(Modifier.weight(1f)) { practice() }
            }
        } else {
            learn()
            practice()
        }
    }
}

@Composable
private fun OpPassedBadge(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Verified, contentDescription = null, tint = Brand.tealBright, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Brand.tealBright)
    }
}

/** The idea: a few short paragraphs (and, for lesson 1, the signal table; for lesson 8, the rig table). */
@Composable
private fun OpConceptCard(lesson: OpLesson, call: String, state: String) {
    OpCard {
        OpEyebrow(stringResource(R.string.op_the_idea))
        OpCopy.concept(lesson, call, state).forEach { p ->
            Text(p, fontSize = 14.sp, color = Brand.textPrimary)
        }
        if (lesson == OpLesson.SIGNALS) {
            OpCopy.signalTable.forEach { (code, meaning) ->
                Row(verticalAlignment = Alignment.Top) {
                    SlashableText(
                        code,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = Brand.textPrimary,
                        modifier = Modifier.width(64.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(meaning), fontSize = 14.sp, color = Brand.textSecondary)
                }
            }
        }
        if (lesson == OpLesson.OFFSET) {
            Text(
                stringResource(R.string.op_on_your_rig),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Brand.textPrimary,
                modifier = Modifier.padding(top = 4.dp)
            )
            OpCopy.rigTable().forEach { rig ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(rig.maker, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Brand.textPrimary)
                    Text(
                        stringResource(R.string.op_rig_line, rig.rit, rig.xit, rig.pitch, rig.aid),
                        fontSize = 12.sp,
                        color = Brand.textSecondary
                    )
                }
            }
            Text(stringResource(R.string.op_rig_note), fontSize = 11.sp, color = Brand.textSecondary)
        }
    }
}

/** Hear it: the lesson's right and wrong examples, each a short transcript. */
@Composable
private fun OpDemoCard(demos: List<OpDemo>, audio: OpAudio) {
    // (demo, line) sounding now. A token per play, so a cancelled older play
    // reporting "done" late cannot clear a newer one's highlight.
    var playing by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var token by remember { mutableIntStateOf(0) }
    OpCard {
        OpEyebrow(stringResource(R.string.op_hear_it))
        demos.forEachIndexed { i, demo ->
            val kind = when (demo.kind) {
                OpDemo.Kind.LISTEN -> stringResource(R.string.op_demo_listen)
                OpDemo.Kind.RIGHT -> stringResource(R.string.op_demo_right)
                OpDemo.Kind.WRONG -> stringResource(R.string.op_demo_wrong)
            }
            val playA11y = stringResource(R.string.op_demo_play_a11y, kind)
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    if (playing?.first == i) Icons.AutoMirrored.Filled.VolumeUp else Icons.Filled.PlayCircle,
                    contentDescription = playA11y,
                    tint = Brand.teal,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable {
                            token += 1
                            val t = token
                            audio.playLines(demo.lines) { line ->
                                if (token == t) playing = line?.let { i to it }
                            }
                        }
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    if (demo.kind != OpDemo.Kind.LISTEN) {
                        Text(
                            kind,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (demo.kind == OpDemo.Kind.RIGHT) Brand.tealBright else OP_WRONG
                        )
                    }
                    demo.lines.forEachIndexed { j, line ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (demo.kind != OpDemo.Kind.LISTEN) {
                                Text(
                                    stringResource(if (line.who == OpDemo.Who.YOU) R.string.op_who_you else R.string.op_who_them),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (line.who == OpDemo.Who.YOU) Brand.tealBright else Brand.textSecondary,
                                    modifier = Modifier.width(36.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                            SlashableText(
                                OperatingProcedure.display(line.text),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = FontFamily.Monospace,
                                color = if (playing == (i to j)) Brand.tealBright else Brand.textPrimary,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (demo.kind == OpDemo.Kind.LISTEN) {
                                OpCopy.signalTable.firstOrNull { it.first == line.text }?.let { (_, meaning) ->
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(meaning), fontSize = 12.sp, color = Brand.textSecondary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---- Lesson 8's demos and drill (#294) ---------------------------------------

/** The same pileup three times: everyone zero beat, only you offset, everyone offset — as the activator hears it. */
@Composable
private fun OpPileupDemoCard(call: String, audio: OpAudio) {
    var playing by remember { mutableStateOf<OperatingProcedure.PileupPass?>(null) }
    OpCard {
        OpEyebrow(stringResource(R.string.op_the_pileup))
        Text(stringResource(R.string.op_pileup_lead, call), fontSize = 14.sp, color = Brand.textPrimary)
        OperatingProcedure.PileupPass.entries.forEach { pass ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Brand.navyRaised.copy(alpha = if (playing == pass) 1f else 0.5f))
                    .clickable {
                        playing = pass
                        val voices = OperatingProcedure.pileupVoices(pass, call, Settings.sidetoneHz, Settings.characterWpm)
                        audio.playPileup(voices) { if (playing == pass) playing = null }
                    }
                    .padding(10.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    if (playing == pass) Icons.AutoMirrored.Filled.VolumeUp else Icons.Filled.PlayCircle,
                    contentDescription = null,
                    tint = Brand.teal,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(OpCopy.passTitle(pass), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Brand.textPrimary)
                    Text(OpCopy.passCaption(pass, call), fontSize = 12.sp, color = Brand.textSecondary)
                }
            }
        }
    }
}

/** RIT moves what you hear, not where you transmit. */
@Composable
private fun OpRitDemoCard(audio: OpAudio) {
    var rit by remember { mutableDoubleStateOf(0.0) }
    val station = OperatingProcedure.RIT_DEMO_STATION_OFFSET_HZ
    val tone = Settings.sidetoneHz
    fun heard() = OperatingProcedure.heardPitch(tone = Settings.sidetoneHz, station = station, vfo = 0.0, rit = rit)
    fun play() = audio.playTone("CQ POTA", heard())
    val range = OperatingProcedure.RIT_RANGE_HZ
    val ritLabel = stringResource(R.string.op_rit_heading)
    val step = OperatingProcedure.RIT_STEP_HZ
    OpCard {
        OpEyebrow(stringResource(R.string.op_rit_heading))
        Text(stringResource(R.string.op_rit_lead, station.toInt()), fontSize = 14.sp, color = Brand.textPrimary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.op_rit_value, signed(rit)),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                color = Brand.textPrimary,
                modifier = Modifier.width(120.dp)
            )
            Slider(
                value = rit.toFloat(),
                onValueChange = { rit = (it / step).roundToInt() * step },
                onValueChangeFinished = { play() },
                valueRange = (-range).toFloat()..range.toFloat(),
                steps = ((2 * range) / step).toInt() - 1,
                colors = SliderDefaults.colors(
                    thumbColor = Brand.teal,
                    activeTrackColor = Brand.teal,
                    inactiveTrackColor = Brand.navyRaised,
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent
                ),
                modifier = Modifier.weight(1f).semantics { contentDescription = ritLabel }
            )
        }
        Text(
            stringResource(R.string.op_rit_heard, OperatingProcedure.audible(heard()).toInt(), tone.toInt()),
            fontSize = 12.sp,
            color = Brand.textSecondary
        )
        Text(
            stringResource(
                R.string.op_rit_tx,
                abs(OperatingProcedure.transmitOffset(station = station, vfo = 0.0, xit = 0.0)).toInt()
            ),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = OP_WRONG
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { play() }) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Brand.teal, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.op_play), color = Brand.teal)
            }
            OutlinedButton(onClick = { rit = 0.0; play() }) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null, tint = Brand.teal, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.op_rit_off), color = Brand.teal)
            }
        }
    }
}

/** The drill: tune until the activator sounds like your sidetone. Three in a row within the tolerance pass it. */
@Composable
private fun OpZeroBeatDrill(
    progress: OperatingProcedureProgress,
    onProgress: (OperatingProcedureProgress) -> Unit,
    audio: OpAudio
) {
    var round by remember { mutableIntStateOf(Random.nextInt(OperatingProcedure.drillStarts.size)) }
    var vfo by remember { mutableDoubleStateOf(0.0) }
    var feedback by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var answered by remember { mutableStateOf(false) }

    val start = OperatingProcedure.drillStart(round)
    val tone = Settings.sidetoneHz
    fun heard() = OperatingProcedure.heardPitch(tone = Settings.sidetoneHz, station = OperatingProcedure.drillStart(round), vfo = vfo, rit = 0.0)
    val streakA11y = stringResource(R.string.op_streak_a11y, progress.drillStreak, OperatingProcedure.ZERO_BEAT_STREAK_TO_PASS)

    fun grade() {
        val offset = OperatingProcedure.transmitOffset(station = start, vfo = vfo, xit = 0.0)
        val right = OperatingProcedure.isZeroBeat(offset)
        noteOperatingPractice()
        val recorded = progress.recordDrill(right)
        onProgress(recorded.progress)
        val p = recorded.progress
        answered = true
        val hz = abs(offset).toInt()
        feedback = if (right) {
            val text = when {
                recorded.passedNow -> AppResources.getString(R.string.op_drill_right_passed, hz)
                p.drillPassed -> AppResources.getString(R.string.op_drill_right, hz)
                else -> AppResources.getString(R.string.op_drill_right_streak, hz, p.drillStreak, OperatingProcedure.ZERO_BEAT_STREAK_TO_PASS)
            }
            text to true
        } else {
            val id = if (offset > 0) R.string.op_drill_wrong_above else R.string.op_drill_wrong_below
            AppResources.getString(id, hz, signed(start)) to false
        }
    }

    OpCard {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.weight(1f)) { OpEyebrow(stringResource(R.string.op_drill_heading)) }
            if (progress.drillPassed) {
                OpPassedBadge(stringResource(R.string.op_drill_passed))
                Spacer(Modifier.width(8.dp))
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = streakA11y }
            ) {
                repeat(OperatingProcedure.ZERO_BEAT_STREAK_TO_PASS) { i ->
                    Box(
                        Modifier.size(10.dp).clip(CircleShape)
                            .background(if (i < progress.drillStreak) Brand.tealBright else Brand.navyRaised)
                    )
                }
            }
        }
        Text(stringResource(R.string.op_drill_lead), fontSize = 14.sp, color = Brand.textPrimary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OpIconButton(stringResource(R.string.op_station), Icons.Filled.CellTower, Modifier.weight(1f)) {
                audio.playTone("VVV", heard())
            }
            OpIconButton(stringResource(R.string.op_spot), Icons.Filled.Tune, Modifier.weight(1f)) {
                audio.playTone("VVV", tone)
            }
            OpIconButton(stringResource(R.string.op_together), Icons.Filled.GraphicEq, Modifier.weight(1f)) {
                audio.playTone("TTT", heard(), sidetone = tone)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OperatingProcedure.knobSteps.forEach { step ->
                val label = if (step > 0) "+${step.toInt()}" else "${step.toInt()}"
                val a11y = stringResource(
                    if (step > 0) R.string.op_tune_up_a11y else R.string.op_tune_down_a11y,
                    abs(step).toInt()
                )
                OutlinedButton(
                    onClick = {
                        vfo += step
                        answered = false
                        feedback = null
                        audio.playTone("VVV", heard())
                    },
                    modifier = Modifier.weight(1f).semantics { contentDescription = a11y }
                ) {
                    Text(label, color = Brand.teal, maxLines = 1)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.op_tuning, signed(vfo)),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                color = Brand.textPrimary,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = {
                    if (answered) {
                        round += 1
                        vfo = 0.0
                        answered = false
                        feedback = null
                        audio.playTone("VVV", heard())
                    } else {
                        grade()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Brand.teal, contentColor = Brand.navy)
            ) {
                Text(stringResource(if (answered) R.string.common_next else R.string.common_done), fontWeight = FontWeight.SemiBold)
            }
        }
        feedback?.let { (text, right) ->
            Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (right) Brand.tealBright else OP_WRONG)
        }
    }
}

@Composable
private fun OpIconButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier) {
        Icon(icon, contentDescription = null, tint = Brand.teal, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, color = Brand.teal, maxLines = 1)
    }
}

// ---- Scenarios ----------------------------------------------------------------

/**
 * One scenario on screen: the situation, the clip, the shuffled choices, and
 * after an answer the explanation. Used by a lesson's run and by "What should
 * you do?". Callers key it by scenario id, so a new scenario reshuffles and
 * plays its clip.
 */
@Composable
private fun OpScenarioCard(
    scenario: OpScenario,
    call: String,
    state: String,
    picked: OpChoice?,
    audio: OpAudio,
    onPick: (OpChoice) -> Unit
) {
    val order = remember { scenario.choices.shuffled() }
    LaunchedEffect(Unit) {
        if (scenario.clip.isNotEmpty()) audio.play(scenario.clip)
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Text(OpCopy.situation(scenario, call), fontSize = 14.sp, color = Brand.textSecondary)
        if (scenario.clip.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                SlashableText(
                    OperatingProcedure.display(scenario.clip),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = Brand.textPrimary,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = { audio.play(scenario.clip) }) {
                    Icon(Icons.Filled.Replay, contentDescription = null, tint = Brand.teal, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.op_replay), color = Brand.teal)
                }
            }
        }
        Text(OpCopy.question(scenario), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Brand.textPrimary)
        order.forEach { choice ->
            val bg = when {
                picked == null -> Brand.navyRaised
                scenario.accepts(choice) -> Brand.teal.copy(alpha = 0.35f)
                choice == picked -> OP_WRONG.copy(alpha = 0.3f)
                else -> Brand.navyRaised.copy(alpha = 0.6f)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(bg)
                    .clickable(enabled = picked == null) { onPick(choice) }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SlashableText(
                    OpCopy.label(choice),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Brand.textPrimary,
                    modifier = Modifier.weight(1f)
                )
                if (picked != null && scenario.accepts(choice)) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Brand.tealBright, modifier = Modifier.size(20.dp))
                } else if (picked == choice) {
                    Icon(Icons.Filled.Cancel, contentDescription = null, tint = OP_WRONG, modifier = Modifier.size(20.dp))
                }
            }
        }
        if (picked != null) {
            val right = scenario.accepts(picked)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(if (right) R.string.op_right else R.string.op_not_this_time),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (right) Brand.tealBright else OP_WRONG
                )
                Text(OpCopy.explanation(scenario, call, state), fontSize = 14.sp, color = Brand.textPrimary)
            }
        }
    }
}

/** A lesson's scenarios, once through. Clean passes it. */
@Composable
private fun OpLessonRun(
    lesson: OpLesson,
    call: String,
    state: String,
    progress: OperatingProcedureProgress,
    onProgress: (OperatingProcedureProgress) -> Unit,
    audio: OpAudio,
    onNext: () -> Unit
) {
    var run by remember { mutableStateOf(OpScenarioRun(emptyList())) }
    // OpScenarioRun is a plain class: bumped after each answer so its new
    // index is read.
    var answeredCount by remember { mutableIntStateOf(0) }
    var picked by remember { mutableStateOf<OpChoice?>(null) }
    var started by remember { mutableStateOf(false) }
    // (clean, passedNow) once the run is finished.
    var result by remember { mutableStateOf<Pair<Boolean, Boolean>?>(null) }

    fun restart() {
        run = OpScenarioRun(OperatingProcedure.scenarios(lesson, call, state))
        answeredCount = 0
        picked = null
        result = null
        started = true
    }

    fun advance() {
        val p = picked ?: return
        run.answer(p)
        answeredCount += 1
        picked = null
        if (run.isFinished) {
            val clean = run.isClean
            val recorded = progress.recordRun(lesson, clean)
            onProgress(recorded.progress)
            result = clean to recorded.passedNow
        }
    }

    OpCard {
        // Read so a recomposition follows each answer.
        @Suppress("UNUSED_VARIABLE") val tick = answeredCount
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.weight(1f)) { OpEyebrow(stringResource(R.string.op_try_it)) }
            if (started && result == null) {
                Text(
                    stringResource(R.string.op_count_of, minOf(run.index + 1, run.scenarios.size), run.scenarios.size),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Brand.textSecondary
                )
            }
        }
        val r = result
        val scenario = run.current
        when {
            r != null -> {
                val (clean, passedNow) = r
                if (clean) {
                    val text = when {
                        !progress.hasPassed(lesson) -> stringResource(R.string.op_clean_run_drill)
                        passedNow -> stringResource(R.string.op_lesson_passed)
                        else -> stringResource(R.string.op_clean_run)
                    }
                    OpPassedBadgeLarge(text)
                } else {
                    Text(
                        stringResource(R.string.op_run_mistakes, run.mistakes),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Brand.textPrimary
                    )
                }
                if (progress.hasPassed(lesson)) {
                    val next = progress.nextLesson
                    OpPrimaryButton(
                        if (next != null) stringResource(R.string.op_next_lesson, OpCopy.title(next))
                        else stringResource(R.string.op_back_to_lessons),
                        onClick = onNext
                    )
                }
                OpSecondaryButton(stringResource(if (clean) R.string.op_run_again else R.string.op_go_again)) { restart() }
            }
            !started -> {
                val count = OperatingProcedure.scenarios(lesson, call, state).size
                Text(
                    stringResource(if (lesson == OpLesson.OFFSET) R.string.op_run_intro_drill else R.string.op_run_intro, count),
                    fontSize = 14.sp,
                    color = Brand.textPrimary
                )
                OpPrimaryButton(stringResource(R.string.common_start)) { restart() }
            }
            scenario != null -> {
                key(answeredCount, scenario.id) {
                    OpScenarioCard(scenario, call, state, picked, audio) { choice ->
                        picked = choice
                        noteOperatingPractice()
                    }
                }
                if (picked != null) {
                    OpPrimaryButton(
                        stringResource(if (run.index + 1 >= run.scenarios.size) R.string.op_finish else R.string.common_next)
                    ) { advance() }
                }
            }
        }
    }
}

@Composable
private fun OpPassedBadgeLarge(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Verified, contentDescription = null, tint = Brand.tealBright, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Brand.tealBright)
    }
}

/** "What should you do?": ten action scenarios from every lesson, shuffled. */
@Composable
private fun OpScenarioMode(modifier: Modifier, call: String, state: String, audio: OpAudio, onOpenLesson: (OpLesson) -> Unit) {
    fun dealt() = OperatingProcedure.actionPool(call, state).shuffled().take(OperatingProcedure.SCENARIO_RUN_LENGTH)
    var deck by remember { mutableStateOf(dealt()) }
    var round by remember { mutableIntStateOf(0) }
    var index by remember { mutableIntStateOf(0) }
    var picked by remember { mutableStateOf<OpChoice?>(null) }
    var right by remember { mutableIntStateOf(0) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = modifier.widthIn(max = CONTENT_MAX_WIDTH).fillMaxWidth()) {
        Text(stringResource(R.string.op_mode_lead), fontSize = 14.sp, color = Brand.textSecondary)
        if (index < deck.size) {
            val scenario = deck[index]
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.op_count_of, index + 1, deck.size),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Brand.textSecondary,
                    modifier = Modifier.weight(1f)
                )
                Text(stringResource(R.string.op_mode_right_count, right), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Brand.tealBright)
            }
            OpCard {
                key(round, index) {
                    OpScenarioCard(scenario, call, state, picked, audio) { choice ->
                        picked = choice
                        noteOperatingPractice()
                        if (scenario.accepts(choice)) right += 1
                    }
                }
                if (picked != null) {
                    OutlinedButton(onClick = { onOpenLesson(scenario.lesson) }) {
                        Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, tint = Brand.teal, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.op_mode_lesson_link, OpCopy.title(scenario.lesson)), color = Brand.teal)
                    }
                    OpPrimaryButton(
                        stringResource(if (index + 1 >= deck.size) R.string.op_see_how else R.string.common_next)
                    ) {
                        picked = null
                        index += 1
                    }
                }
            }
        } else if (deck.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().brandCard(14.dp).padding(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    stringResource(R.string.op_count_of, right, deck.size),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = Brand.tealBright
                )
                Text(
                    stringResource(if (right == deck.size) R.string.op_mode_all_right else R.string.op_mode_some_wrong),
                    fontSize = 14.sp,
                    color = Brand.textPrimary,
                    textAlign = TextAlign.Center
                )
                OpPrimaryButton(stringResource(R.string.op_go_again)) {
                    deck = dealt()
                    round += 1
                    index = 0
                    right = 0
                    picked = null
                }
            }
        }
    }
}

// ---- The words ----------------------------------------------------------------

/**
 * The section's words, from strings.xml. The MorseKit rules pin ids and keys;
 * the words are kept in step with iOS's `OpCopy` and Android's strings.
 */
private object OpCopy {
    fun title(l: OpLesson): String = AppResources.getString(
        when (l) {
            OpLesson.SIGNALS -> R.string.op_lesson_signals_title
            OpLesson.WHEN -> R.string.op_lesson_when_title
            OpLesson.ONCE -> R.string.op_lesson_once_title
            OpLesson.PARTIAL -> R.string.op_lesson_partial_title
            OpLesson.ME -> R.string.op_lesson_me_title
            OpLesson.EXCHANGE -> R.string.op_lesson_exchange_title
            OpLesson.MISTAKE -> R.string.op_lesson_mistake_title
            OpLesson.OFFSET -> R.string.op_lesson_offset_title
        }
    )

    fun subtitle(l: OpLesson): String = AppResources.getString(
        when (l) {
            OpLesson.SIGNALS -> R.string.op_lesson_signals_sub
            OpLesson.WHEN -> R.string.op_lesson_when_sub
            OpLesson.ONCE -> R.string.op_lesson_once_sub
            OpLesson.PARTIAL -> R.string.op_lesson_partial_sub
            OpLesson.ME -> R.string.op_lesson_me_sub
            OpLesson.EXCHANGE -> R.string.op_lesson_exchange_sub
            OpLesson.MISTAKE -> R.string.op_lesson_mistake_sub
            OpLesson.OFFSET -> R.string.op_lesson_offset_sub
        }
    )

    /** Each signal as written (and played), with its meaning's string id. */
    val signalTable: List<Pair<String, Int>> = listOf(
        "?" to R.string.op_signal_q,
        "AGN?" to R.string.op_signal_agn,
        "<AS>" to R.string.op_signal_as,
        "BK" to R.string.op_signal_bk,
        "SRI" to R.string.op_signal_sri,
        "QRZ?" to R.string.op_signal_qrz,
        "E E" to R.string.op_signal_ee
    )

    class Rig(val maker: String, val rit: String, val xit: String, val pitch: String, val aid: String)

    /** From the manuals listed in the design note. Control names are the rigs' own labels. */
    fun rigTable(): List<Rig> = listOf(
        Rig("Icom", "RIT", AppResources.getString(R.string.op_rig_icom_xit), "CW PITCH", "AUTOTUNE"),
        Rig("Yaesu", "CLAR (RX)", "CLAR (TX)", "CW PITCH", "ZIN/SPOT"),
        Rig("Kenwood", "RIT", "XIT", "CW pitch", AppResources.getString(R.string.op_rig_kenwood_aid)),
        Rig("Elecraft", "RIT", "XIT", "PITCH", AppResources.getString(R.string.op_rig_elecraft_aid)),
        Rig(
            "FlexRadio", "RIT", "XIT",
            AppResources.getString(R.string.op_rig_flex_pitch),
            AppResources.getString(R.string.op_rig_flex_aid)
        )
    )

    fun concept(l: OpLesson, call: String, state: String): List<String> {
        val near = OperatingProcedure.nearMiss(call)
        fun s(id: Int, vararg args: Any) = AppResources.getString(id, *args)
        return when (l) {
            OpLesson.SIGNALS -> listOf(s(R.string.op_concept_signals_1))
            OpLesson.WHEN -> listOf(s(R.string.op_concept_when_1), s(R.string.op_concept_when_2), s(R.string.op_concept_when_3))
            OpLesson.ONCE -> listOf(s(R.string.op_concept_once_1), s(R.string.op_concept_once_2), s(R.string.op_concept_once_3))
            OpLesson.PARTIAL -> listOf(s(R.string.op_concept_partial_1), s(R.string.op_concept_partial_2), s(R.string.op_concept_partial_3))
            OpLesson.ME -> listOf(
                s(R.string.op_concept_me_1),
                s(R.string.op_concept_me_2),
                s(R.string.op_concept_me_3, near, call),
                s(R.string.op_concept_me_4, near)
            )
            OpLesson.EXCHANGE -> listOf(
                s(R.string.op_concept_exchange_1, OperatingProcedure.reply(state)),
                s(R.string.op_concept_exchange_2, OperatingProcedure.replyBK(state), OperatingProcedure.CLOSE_BK),
                s(R.string.op_concept_exchange_3),
                s(R.string.op_concept_exchange_4)
            )
            OpLesson.MISTAKE -> listOf(
                s(R.string.op_concept_mistake_1),
                s(R.string.op_concept_mistake_2),
                s(R.string.op_concept_mistake_3, OperatingProcedure.ERROR_DISPLAY)
            )
            OpLesson.OFFSET -> listOf(
                s(R.string.op_concept_offset_1),
                s(R.string.op_concept_offset_2),
                s(R.string.op_concept_offset_3),
                s(R.string.op_concept_offset_4)
            )
        }
    }

    fun passTitle(p: OperatingProcedure.PileupPass): String = when (p) {
        OperatingProcedure.PileupPass.ZERO_BEAT -> AppResources.getString(R.string.op_pass_zero_title)
        OperatingProcedure.PileupPass.YOU_OFFSET ->
            AppResources.getString(R.string.op_pass_you_title, OperatingProcedure.DEMO_YOUR_OFFSET_HZ.toInt())
        OperatingProcedure.PileupPass.ALL_OFFSET -> AppResources.getString(R.string.op_pass_all_title)
    }

    fun passCaption(p: OperatingProcedure.PileupPass, call: String): String = when (p) {
        OperatingProcedure.PileupPass.ZERO_BEAT -> AppResources.getString(R.string.op_pass_zero_caption, call)
        OperatingProcedure.PileupPass.YOU_OFFSET ->
            AppResources.getString(R.string.op_pass_you_caption, OperatingProcedure.DEMO_YOUR_OFFSET_HZ.toInt())
        OperatingProcedure.PileupPass.ALL_OFFSET -> AppResources.getString(R.string.op_pass_all_caption)
    }

    fun situation(s: OpScenario, call: String): String {
        val act = OperatingProcedure.activator(call).call
        fun r(id: Int, vararg args: Any) = AppResources.getString(id, *args)
        return when (s.id) {
            "signals.as", "signals.qrz", "signals.ee", "signals.bk", "signals.agn" -> r(R.string.op_sit_signals)
            "when.dits", "when.inProgress", "when.as", "when.sriQrz" -> r(R.string.op_sit_when, act)
            "once.cq", "once.qrz", "once.dits" -> r(R.string.op_sit_once, act)
            "exchange.agn" -> r(R.string.op_sit_exchange_agn)
            "exchange.stop" -> r(R.string.op_sit_exchange_stop)
            "mistake.call" -> r(R.string.op_sit_mistake_call, s.detail)
            "mistake.last" -> r(R.string.op_sit_mistake_last, s.detail)
            "mistake.state" -> r(R.string.op_sit_mistake_state, s.detail)
            "offset.pileup" -> r(R.string.op_sit_offset_pileup)
            "offset.rit" -> r(R.string.op_sit_offset_rit)
            "offset.xit" -> r(R.string.op_sit_offset_xit)
            "offset.tune" -> r(R.string.op_sit_offset_tune)
            else -> r(R.string.op_sit_default, act)
        }
    }

    fun question(s: OpScenario): String = AppResources.getString(
        when (s.lesson) {
            OpLesson.SIGNALS -> R.string.op_q_meaning
            OpLesson.MISTAKE -> R.string.op_q_next
            OpLesson.OFFSET -> when (s.id) {
                "offset.pileup" -> R.string.op_q_where_call
                "offset.tune" -> R.string.op_q_where_tune
                else -> R.string.op_q_control
            }
            else -> R.string.op_q_do
        }
    )

    fun label(c: OpChoice): String = when (c) {
        is OpChoice.Send -> AppResources.getString(R.string.op_choice_send, OperatingProcedure.display(c.text))
        OpChoice.Silent -> AppResources.getString(R.string.op_choice_silent)
        is OpChoice.Option -> option(c.key)
    }

    fun option(key: String): String {
        val id = when (key) {
            "wait" -> R.string.op_opt_wait
            "goAhead" -> R.string.op_opt_go_ahead
            "goodbye" -> R.string.op_opt_goodbye
            "whoIsCalling" -> R.string.op_opt_who_is_calling
            "sayAgain" -> R.string.op_opt_say_again
            "sorry" -> R.string.op_opt_sorry
            "error" -> R.string.op_opt_error
            "backToYou" -> R.string.op_opt_back_to_you
            "offsetSmall" -> R.string.op_opt_offset_small
            "zeroBeat" -> R.string.op_opt_zero_beat
            "twoKUp" -> R.string.op_opt_two_k_up
            "rit" -> R.string.op_opt_rit
            "xit" -> R.string.op_opt_xit
            "pitch" -> R.string.op_opt_pitch
            "tuneAway" -> R.string.op_opt_tune_away
            "tuneOnQuick" -> R.string.op_opt_tune_on_quick
            "tuneOnLow" -> R.string.op_opt_tune_on_low
            else -> return key
        }
        return AppResources.getString(id)
    }

    fun explanation(s: OpScenario, call: String, state: String): String {
        val other = OperatingProcedure.otherHunter(call)
        fun r(id: Int, vararg args: Any) = AppResources.getString(id, *args)
        return when (s.id) {
            "signals.as" -> r(R.string.op_exp_signals_as)
            "signals.qrz" -> r(R.string.op_exp_signals_qrz)
            "signals.ee" -> r(R.string.op_exp_signals_ee)
            "signals.bk" -> r(R.string.op_exp_signals_bk)
            "signals.agn" -> r(R.string.op_exp_signals_agn)
            "when.dits" -> r(R.string.op_exp_when_dits, other)
            "when.inProgress" -> r(R.string.op_exp_when_in_progress, other)
            "when.as" -> r(R.string.op_exp_when_as)
            "when.sriQrz" -> r(R.string.op_exp_when_sri_qrz)
            "once.cq" -> r(R.string.op_exp_once_cq)
            "once.qrz" -> r(R.string.op_exp_once_qrz)
            "once.dits" -> r(R.string.op_exp_once_dits)
            "partial.prefix" -> r(R.string.op_exp_partial_prefix, s.clip)
            "partial.notMine" -> r(R.string.op_exp_partial_not_mine, s.clip)
            "partial.suffix" -> r(R.string.op_exp_partial_suffix, s.clip)
            "partial.fullCall" -> r(R.string.op_exp_partial_full_call)
            "me.other" -> r(R.string.op_exp_me_other, other)
            "me.mine" -> r(R.string.op_exp_me_mine, OperatingProcedure.reply(state), OperatingProcedure.replyBK(state))
            "me.close" -> r(R.string.op_exp_me_close, s.detail)
            "me.closeAsked" -> r(R.string.op_exp_me_close_asked, s.detail)
            "exchange.reply" -> r(R.string.op_exp_exchange_reply)
            "exchange.agn" -> r(R.string.op_exp_exchange_agn)
            "exchange.dits" -> r(R.string.op_exp_exchange_dits)
            "exchange.stop" -> r(R.string.op_exp_exchange_stop)
            "mistake.call" -> r(R.string.op_exp_mistake_call)
            "mistake.last" -> r(R.string.op_exp_mistake_last)
            "mistake.state" -> r(R.string.op_exp_mistake_state)
            "mistake.hear" -> r(R.string.op_exp_mistake_hear, s.detail)
            "offset.pileup" -> r(R.string.op_exp_offset_pileup)
            "offset.rit" -> r(R.string.op_exp_offset_rit)
            "offset.xit" -> r(R.string.op_exp_offset_xit)
            "offset.tune" -> r(R.string.op_exp_offset_tune)
            else -> ""
        }
    }
}
