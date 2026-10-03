package app.anothermorsetrainer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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
 * do?", a scenario mode over them. The rules are morsekit's
 * [OperatingProcedure]; this screen only sounds, shows and records them. Twin
 * of the iOS `OperatingProcedureView.swift`.
 *
 * Teaching material, not a session: no [Stats] session, no leaderboard. A
 * graded answer counts as practice for the day ([Stats.recordPracticeDay]).
 * Progress lives in [OperatingProcedureStore]; which level is open survives
 * process death, a run in flight does not.
 */
@Composable
fun OperatingProcedureScreen(onBack: () -> Unit, onOpenFirstFour: () -> Unit) {
    val player = remember { MorsePlayer() }
    val scope = rememberCoroutineScope()
    val audio = remember { OpAudio(player, scope) }
    DisposableEffect(Unit) {
        onDispose {
            audio.stop()
            player.release()
        }
    }
    // Three in a row means three in one sitting: the streak starts over each
    // time the section opens, as on iOS (which loads progress on open).
    LaunchedEffect(Unit) {
        val p = OperatingProcedureStore.progress
        if (p.drillStreak != 0) OperatingProcedureStore.save(p.copy(drillStreak = 0))
    }

    val progress = OperatingProcedureStore.progress
    var screenTag by rememberSaveable { mutableStateOf(OP_HOME) }
    var editingStation by rememberSaveable { mutableStateOf(false) }
    var confirmingReset by rememberSaveable { mutableStateOf(false) }

    val call = OperatingProcedure.normalizeCall(PileupSettings.myCall)
    val state = OperatingProcedure.normalizeState(PileupSettings.myState)
    val stationReady = OperatingProcedure.prefillCall(call).isNotEmpty() &&
        OperatingProcedure.isValidCall(call) && OperatingProcedure.isValidState(state)
    val showingStation = !stationReady || editingStation
    val lesson = opLessonOf(screenTag)

    fun open(tag: String) {
        audio.stop()
        screenTag = tag
    }

    BackHandler {
        when {
            editingStation && stationReady -> editingStation = false
            screenTag != OP_HOME && !showingStation -> open(OP_HOME)
            else -> onBack()
        }
    }

    if (confirmingReset) {
        AlertDialog(
            onDismissRequest = { confirmingReset = false },
            containerColor = Brand.navyElevated,
            title = { Text(stringResource(R.string.op_start_over_title), color = Brand.textPrimary) },
            text = { Text(stringResource(R.string.op_start_over_body), color = Brand.textSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingReset = false
                    OperatingProcedureStore.reset()
                }) { Text(stringResource(R.string.op_start_over), color = Brand.warning) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingReset = false }) {
                    Text(stringResource(R.string.common_cancel), color = Brand.teal)
                }
            }
        )
    }

    val wide = isWideLayout()
    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (screenTag != OP_HOME && !showingStation) {
                TextButton(onClick = { open(OP_HOME) }) {
                    Text(stringResource(R.string.op_lessons_back), color = Brand.teal)
                }
            } else {
                TextButton(onClick = onBack) { Text(stringResource(R.string.common_back), color = Brand.teal) }
            }
            Spacer(Modifier.weight(1f))
            Text(
                when {
                    lesson != null -> opLessonTitle(lesson)
                    screenTag == OP_SCENARIOS -> stringResource(R.string.op_scenarios_title)
                    else -> stringResource(R.string.op_title)
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Brand.textPrimary,
                maxLines = 1
            )
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(72.dp))
        }

        // The wide column is for the two-column lesson list and a lesson's
        // two columns; the station card and the scenario mode stay phone-width.
        val roomy = wide && !showingStation && screenTag != OP_SCENARIOS
        CenteredScrollColumn(
            contentModifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 32.dp),
            maxWidth = if (roomy) WIDE_CONTENT_MAX_WIDTH else CONTENT_MAX_WIDTH,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when {
                showingStation -> OpStationCard(editing = editingStation, onSaved = { editingStation = false })
                screenTag == OP_SCENARIOS -> OpScenarioMode(
                    call = call,
                    state = state,
                    audio = audio,
                    onOpenLesson = { open(opLessonTag(it)) }
                )
                lesson != null -> key(lesson) {
                    OpLessonScreen(
                        lesson = lesson,
                        call = call,
                        state = state,
                        progress = progress,
                        wide = wide,
                        audio = audio,
                        onNext = { open(OperatingProcedureStore.progress.nextLesson?.let(::opLessonTag) ?: OP_HOME) }
                    )
                }
                else -> OpHome(
                    call = call,
                    state = state,
                    progress = progress,
                    wide = wide,
                    onOpen = { open(it) },
                    onChange = { editingStation = true },
                    onReset = { confirmingReset = true },
                    onOpenFirstFour = {
                        audio.stop()
                        onOpenFirstFour()
                    }
                )
            }
        }
    }
}

/** WB0RLJ's "Advice for CW POTA Hunters", and his channel (the section's credit). */
internal const val OP_ADVICE_URL = "https://www.qrz.com/db/WB0RLJ#Advice"
internal const val OP_YOUTUBE_URL = "https://www.youtube.com/@WB0RLJ"

// The open level, as a saveable string.
private const val OP_HOME = "home"
private const val OP_SCENARIOS = "scenarios"
private const val OP_LESSON_PREFIX = "lesson:"

private fun opLessonTag(lesson: OpLesson): String = OP_LESSON_PREFIX + lesson.raw

private fun opLessonOf(tag: String): OpLesson? =
    tag.removePrefix(OP_LESSON_PREFIX).takeIf { it != tag }?.let(OpLesson::fromRaw)

/** Pause between a demo's lines, as on the air. */
private const val OP_LINE_GAP_SECONDS = 0.6

/**
 * How the section sounds (iOS AppModel+OperatingProcedure.swift): one clip at
 * the learner's speed and tone, a demo's lines one after another, the pileup
 * demo through the Pileup Runner's mixer, and the drill's station (with your
 * sidetone beside it for "Together"). Each call replaces what was playing.
 */
private class OpAudio(private val player: MorsePlayer, private val scope: CoroutineScope) {
    private var job: Job? = null

    /**
     * Sound one transmission, piece by piece: text at the learner's timing,
     * and each error (`<ERR>`) in its own shape — 5 to 8 dits, run together
     * or slapped, at its own speed (maintainer, 2026-10-03) — a word gap apart.
     * Suspends until it has played.
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

    /** One transmission; a newer sound replaces it. */
    fun play(text: String) {
        job?.cancel()
        job = scope.launch { sound(text) }
    }

    /** A demo's lines in turn; [onLine] is told which is sounding, then null once all have played. */
    fun lines(lines: List<OpDemo.Line>, onLine: (Int?) -> Unit) {
        job?.cancel()
        job = scope.launch {
            for (i in lines.indices) {
                onLine(i)
                sound(lines[i].text)
                delay((OP_LINE_GAP_SECONDS * 1000).toLong())
            }
            onLine(null)
        }
    }

    /** One pass of the pileup demo. */
    fun pileup(voices: List<OpPileupVoice>, onFinished: () -> Unit) {
        job?.cancel()
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
    fun tone(text: String, pitch: Double, sidetone: Double? = null) {
        job?.cancel()
        val t = Settings.timing()
        val voices = mutableListOf(
            MorsePlayer.PileupVoice(text, OperatingProcedure.audible(pitch), t, 0.8f, 0.0, null)
        )
        if (sidetone != null) {
            voices.add(MorsePlayer.PileupVoice(text, OperatingProcedure.audible(sidetone), t, 0.8f, 0.0, null))
        }
        player.playPileup(voices, 0f) {}
    }

    fun stop() {
        job?.cancel()
        player.stop()
    }
}

private fun opPractised() = Stats.recordPracticeDay()

private fun signed(n: Int): String = if (n >= 0) "+$n" else "$n"

@Composable
private fun OpCardHeader(text: String) {
    Text(text, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, color = Brand.textSecondary)
}

@Composable
private fun OpBadge(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Brand.tealBright, modifier = Modifier.size(14.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = Brand.tealBright)
    }
}

@Composable
private fun OpPrimaryButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = Brand.teal, contentColor = Brand.navy),
        modifier = Modifier.fillMaxWidth().height(48.dp)
    ) { Text(text, fontWeight = FontWeight.Bold) }
}

// MARK: Your station

@Composable
private fun OpStationCard(editing: Boolean, onSaved: () -> Unit) {
    var callField by rememberSaveable { mutableStateOf(OperatingProcedure.prefillCall(PileupSettings.myCall)) }
    var stateField by rememberSaveable { mutableStateOf(OperatingProcedure.normalizeState(PileupSettings.myState)) }
    val valid = OperatingProcedure.isValidCall(callField) && OperatingProcedure.isValidState(stateField) &&
        OperatingProcedure.prefillCall(callField).isNotEmpty()
    Column(
        modifier = Modifier.fillMaxWidth().brandCard().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            stringResource(R.string.op_station_heading),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = Brand.textPrimary
        )
        Text(stringResource(R.string.op_station_blurb), style = MaterialTheme.typography.bodyMedium, color = Brand.textSecondary)
        OutlinedTextField(
            value = callField,
            onValueChange = { callField = it.uppercase() },
            singleLine = true,
            label = { Text(stringResource(R.string.op_your_call)) },
            placeholder = { Text("N9HO") },
            textStyle = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = stateField,
            onValueChange = { stateField = it.uppercase() },
            singleLine = true,
            label = { Text(stringResource(R.string.op_your_state)) },
            placeholder = { Text("WI") },
            textStyle = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth()
        )
        if (callField.isNotEmpty() && !OperatingProcedure.isValidCall(callField)) {
            Text(stringResource(R.string.op_call_invalid), style = MaterialTheme.typography.labelSmall, color = Brand.warning)
        }
        if (stateField.isNotEmpty() && !OperatingProcedure.isValidState(stateField)) {
            Text(stringResource(R.string.op_state_invalid), style = MaterialTheme.typography.labelSmall, color = Brand.warning)
        }
        Text(stringResource(R.string.op_station_saved_where), style = MaterialTheme.typography.labelSmall, color = Brand.textSecondary)
        Button(
            onClick = {
                PileupSettings.updateMyCall(OperatingProcedure.normalizeCall(callField))
                PileupSettings.updateMyState(OperatingProcedure.normalizeState(stateField))
                onSaved()
            },
            enabled = valid,
            colors = ButtonDefaults.buttonColors(containerColor = Brand.teal, contentColor = Brand.navy),
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) {
            Text(stringResource(if (editing) R.string.op_save else R.string.op_start), fontWeight = FontWeight.Bold)
        }
    }
}

// MARK: The lesson list

@Composable
private fun OpHome(
    call: String,
    state: String,
    progress: OperatingProcedureProgress,
    wide: Boolean,
    onOpen: (String) -> Unit,
    onChange: () -> Unit,
    onReset: () -> Unit,
    onOpenFirstFour: () -> Unit
) {
    Text(stringResource(R.string.op_home_intro), style = MaterialTheme.typography.bodyMedium, color = Brand.textSecondary)

    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            SlashableText(
                "$call · $state",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                fontSize = 22.sp,
                color = Brand.textPrimary
            )
            Text(
                stringResource(R.string.op_lessons_passed, progress.passedCount, OpLesson.entries.size),
                style = MaterialTheme.typography.labelSmall,
                color = Brand.textSecondary
            )
        }
        TextButton(onClick = onChange) { Text(stringResource(R.string.op_change), color = Brand.teal) }
    }

    OpFirstFourRow(onOpenFirstFour)

    val next = progress.nextLesson
    if (next != null) {
        val title = opLessonTitle(next)
        OpPrimaryButton(
            stringResource(if (progress.passedCount == 0) R.string.op_begin else R.string.op_continue, title)
        ) { onOpen(opLessonTag(next)) }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Brand.tealBright)
            Text(stringResource(R.string.op_all_passed), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = Brand.tealBright)
        }
    }

    // Two columns on a big tablet window, so eight lessons fit without a
    // long scroll; one on a phone.
    val lessons = OpLesson.entries
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (wide) {
            lessons.chunked(2).forEachIndexed { r, pair ->
                Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    pair.forEachIndexed { j, l ->
                        OpLessonRow(r * 2 + j + 1, l, progress.hasPassed(l), Modifier.weight(1f).fillMaxHeight()) {
                            onOpen(opLessonTag(l))
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        } else {
            lessons.forEachIndexed { i, l ->
                OpLessonRow(i + 1, l, progress.hasPassed(l), Modifier.fillMaxWidth()) { onOpen(opLessonTag(l)) }
            }
        }
    }

    OpScenarioModeRow { onOpen(OP_SCENARIOS) }

    Text(
        stringResource(R.string.op_speed_note, Settings.characterWpm.roundToInt()),
        style = MaterialTheme.typography.labelSmall,
        color = Brand.textSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
    // The credit (maintainer, 2026-10-03): WB0RLJ's advice, linked, and his
    // channel of daily activation recordings.
    Text(
        stringResource(R.string.op_credit),
        style = MaterialTheme.typography.labelSmall,
        color = Brand.textSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
    val uriHandler = LocalUriHandler.current
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        TextButton(onClick = { uriHandler.openUri(OP_ADVICE_URL) }) {
            Text(stringResource(R.string.op_credit_advice), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = Brand.teal)
        }
        TextButton(onClick = { uriHandler.openUri(OP_YOUTUBE_URL) }) {
            Text(stringResource(R.string.op_credit_youtube), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = Brand.teal)
        }
    }

    if (progress.passedCount > 0 || progress.drillPassed || progress.cleanRuns.isNotEmpty()) {
        TextButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.op_start_over), color = Brand.warning)
        }
    }
}

/** First Four is the on-ramp: linked from here, not moved in (design note). */
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
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            if (done) Icons.Filled.CheckCircle else Icons.Filled.SettingsInputAntenna,
            contentDescription = null,
            tint = if (done) Brand.tealBright else Brand.teal
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(if (done) R.string.op_first_four_done else R.string.op_first_four_new),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Brand.textPrimary
            )
            Text(stringResource(R.string.op_first_four_sub), style = MaterialTheme.typography.labelSmall, color = Brand.textSecondary)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Brand.textSecondary)
    }
}

@Composable
private fun OpLessonRow(number: Int, lesson: OpLesson, passed: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val title = opLessonTitle(lesson)
    val label = stringResource(if (passed) R.string.op_lesson_label_passed else R.string.op_lesson_label, number, title)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Brand.navyElevated)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier.size(32.dp).clip(CircleShape).background(if (passed) Brand.teal else Brand.navyRaised),
            contentAlignment = Alignment.Center
        ) {
            if (passed) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = Brand.navy, modifier = Modifier.size(18.dp))
            } else {
                Text("$number", color = Brand.textPrimary, fontWeight = FontWeight.SemiBold)
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Brand.textPrimary)
            Text(opLessonSubtitle(lesson), style = MaterialTheme.typography.labelSmall, color = Brand.textSecondary)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Brand.textSecondary)
    }
}

@Composable
private fun OpScenarioModeRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Brand.navyElevated)
            .border(1.dp, Brand.tealBright.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(Icons.Filled.QuestionAnswer, contentDescription = null, tint = Brand.teal)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.op_scenarios_title),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Brand.textPrimary
            )
            Text(
                stringResource(R.string.op_scenarios_sub, OperatingProcedure.SCENARIO_RUN_LENGTH),
                style = MaterialTheme.typography.labelSmall,
                color = Brand.textSecondary
            )
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Brand.textSecondary)
    }
}

// MARK: A lesson

@Composable
private fun OpLessonScreen(
    lesson: OpLesson,
    call: String,
    state: String,
    progress: OperatingProcedureProgress,
    wide: Boolean,
    audio: OpAudio,
    onNext: () -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            opLessonSubtitle(lesson),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = Brand.textSecondary,
            modifier = Modifier.weight(1f)
        )
        if (progress.hasPassed(lesson)) OpBadge(stringResource(R.string.op_passed))
    }
    if (wide) {
        // Concept and demos beside the practice, so the rule stays in view
        // while you answer.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OpLearnColumn(lesson, call, state, audio)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OpPracticeColumn(lesson, call, state, progress, audio, onNext)
            }
        }
    } else {
        OpLearnColumn(lesson, call, state, audio)
        OpPracticeColumn(lesson, call, state, progress, audio, onNext)
    }
}

/** The concept card and the demos; emits into the caller's column. */
@Composable
private fun OpLearnColumn(lesson: OpLesson, call: String, state: String, audio: OpAudio) {
    OpConceptCard(lesson, call, state)
    if (lesson != OpLesson.OFFSET) {
        OpDemoCard(OperatingProcedure.demos(lesson, call, state), audio)
    } else {
        OpPileupDemoCard(call, audio)
        OpRitDemoCard(audio)
    }
}

/** The drill (lesson 8) and the scenario run; emits into the caller's column. */
@Composable
private fun OpPracticeColumn(
    lesson: OpLesson,
    call: String,
    state: String,
    progress: OperatingProcedureProgress,
    audio: OpAudio,
    onNext: () -> Unit
) {
    if (lesson == OpLesson.OFFSET) OpZeroBeatDrill(progress, audio)
    OpLessonRun(lesson, call, state, progress, audio, onNext)
}

/** The idea: a few short paragraphs (and, for lesson 1, the signal table; for lesson 8, the rig table). */
@Composable
private fun OpConceptCard(lesson: OpLesson, call: String, state: String) {
    Column(
        modifier = Modifier.fillMaxWidth().brandCard().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OpCardHeader(stringResource(R.string.op_the_idea))
        for (p in opConcept(lesson, call, state)) {
            Text(p, style = MaterialTheme.typography.bodyMedium, color = Brand.textPrimary)
        }
        if (lesson == OpLesson.SIGNALS) {
            for ((code, meaning) in OP_SIGNALS) {
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SlashableText(
                        code,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = Brand.textPrimary,
                        modifier = Modifier.width(64.dp)
                    )
                    Text(
                        stringResource(meaning),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Brand.textSecondary,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        if (lesson == OpLesson.OFFSET) {
            Text(
                stringResource(R.string.op_on_your_rig),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Brand.textPrimary,
                modifier = Modifier.padding(top = 4.dp)
            )
            for (rig in opRigTable()) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(rig.maker, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Brand.textPrimary)
                    Text(
                        stringResource(R.string.op_rig_row, rig.rit, rig.xit, rig.pitch, rig.aid),
                        style = MaterialTheme.typography.labelSmall,
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
    var playing by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    Column(
        modifier = Modifier.fillMaxWidth().brandCard().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OpCardHeader(stringResource(R.string.op_hear_it))
        demos.forEachIndexed { i, demo ->
            val kind = opKindLabel(demo.kind)
            val listen = demo.kind == OpDemo.Kind.LISTEN
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                IconButton(
                    onClick = { audio.lines(demo.lines) { line -> playing = line?.let { i to it } } },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        if (playing?.first == i) Icons.Filled.GraphicEq else Icons.Filled.PlayCircleOutline,
                        contentDescription = stringResource(R.string.op_play_example, kind),
                        tint = Brand.teal,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    if (!listen) {
                        Text(
                            kind,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (demo.kind == OpDemo.Kind.RIGHT) Brand.tealBright else Brand.warning
                        )
                    }
                    demo.lines.forEachIndexed { j, line ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (!listen) {
                                val you = line.who == OpDemo.Who.YOU
                                Text(
                                    stringResource(if (you) R.string.op_you else R.string.op_them),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (you) Brand.tealBright else Brand.textSecondary,
                                    modifier = Modifier.width(36.dp)
                                )
                            }
                            val lit = playing == (i to j)
                            SlashableText(
                                OperatingProcedure.display(line.text),
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp,
                                color = if (lit) Brand.tealBright else Brand.textPrimary,
                                modifier = if (listen) Modifier else Modifier.weight(1f)
                            )
                            if (listen) {
                                OP_SIGNALS.firstOrNull { it.first == line.text }?.let { (_, meaning) ->
                                    Text(
                                        stringResource(meaning),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Brand.textSecondary,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// MARK: Lesson 8's demos and drill (#294)

/** The same pileup three times: everyone zero beat, only you offset, everyone offset — as the activator hears it. */
@Composable
private fun OpPileupDemoCard(call: String, audio: OpAudio) {
    var playing by remember { mutableStateOf<OperatingProcedure.PileupPass?>(null) }
    Column(
        modifier = Modifier.fillMaxWidth().brandCard().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OpCardHeader(stringResource(R.string.op_the_pileup))
        Text(stringResource(R.string.op_pileup_intro, call), style = MaterialTheme.typography.bodyMedium, color = Brand.textPrimary)
        for (pass in OperatingProcedure.PileupPass.entries) {
            val lit = playing == pass
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Brand.navyRaised.copy(alpha = if (lit) 1f else 0.5f))
                    .clickable {
                        playing = pass
                        val voices = OperatingProcedure.pileupVoices(pass, call, Settings.sidetoneHz, Settings.characterWpm)
                        audio.pileup(voices) { if (playing == pass) playing = null }
                    }
                    .padding(10.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    if (lit) Icons.Filled.GraphicEq else Icons.Filled.PlayCircleOutline,
                    contentDescription = null,
                    tint = Brand.teal,
                    modifier = Modifier.size(28.dp)
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(opPassTitle(pass), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Brand.textPrimary)
                    Text(opPassCaption(pass, call), style = MaterialTheme.typography.labelSmall, color = Brand.textSecondary)
                }
            }
        }
    }
}

/** RIT moves what you hear, not where you transmit. */
@Composable
private fun OpRitDemoCard(audio: OpAudio) {
    var rit by remember { mutableFloatStateOf(0f) }
    val station = OperatingProcedure.RIT_DEMO_STATION_OFFSET_HZ
    val tone = Settings.sidetoneHz
    val ritHz = rit.roundToInt()
    val heard = OperatingProcedure.heardPitch(tone, station, 0.0, ritHz.toDouble())
    fun play() {
        val pitch = OperatingProcedure.heardPitch(Settings.sidetoneHz, station, 0.0, rit.roundToInt().toDouble())
        audio.tone("CQ POTA", pitch)
    }
    val range = OperatingProcedure.RIT_RANGE_HZ.toFloat()
    val steps = (2 * OperatingProcedure.RIT_RANGE_HZ / OperatingProcedure.RIT_STEP_HZ).toInt() - 1
    val ritLabel = stringResource(R.string.op_rit)
    Column(
        modifier = Modifier.fillMaxWidth().brandCard().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OpCardHeader(ritLabel)
        Text(stringResource(R.string.op_rit_intro, station.toInt()), style = MaterialTheme.typography.bodyMedium, color = Brand.textPrimary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.op_rit_value, signed(ritHz)),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = Brand.textPrimary,
                modifier = Modifier.width(120.dp)
            )
            Slider(
                value = rit,
                onValueChange = { rit = it },
                valueRange = -range..range,
                steps = steps,
                onValueChangeFinished = { play() },
                colors = SliderDefaults.colors(
                    thumbColor = Brand.teal,
                    activeTrackColor = Brand.teal,
                    inactiveTrackColor = Brand.navyRaised
                ),
                modifier = Modifier.weight(1f).semantics { contentDescription = ritLabel }
            )
        }
        Text(
            stringResource(R.string.op_rit_heard, OperatingProcedure.audible(heard).toInt(), tone.toInt()),
            style = MaterialTheme.typography.labelSmall,
            color = Brand.textSecondary
        )
        Text(
            stringResource(R.string.op_rit_still_call, abs(OperatingProcedure.transmitOffset(station, 0.0, 0.0)).toInt()),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = Brand.warning
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OpIconButton(Icons.Filled.PlayArrow, stringResource(R.string.op_play)) { play() }
            OpIconButton(Icons.Filled.Refresh, stringResource(R.string.op_rit_off)) {
                rit = 0f
                play()
            }
        }
    }
}

@Composable
private fun OpIconButton(icon: ImageVector, text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        modifier = modifier
    ) {
        Icon(icon, contentDescription = null, tint = Brand.teal, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, color = Brand.teal, maxLines = 1)
    }
}

/** The drill: tune until the activator sounds like your sidetone. Three in a row within the tolerance pass it. */
@Composable
private fun OpZeroBeatDrill(progress: OperatingProcedureProgress, audio: OpAudio) {
    val context = LocalContext.current
    val haptics = remember { Haptics(context) }
    val resources = LocalResources.current
    var round by remember { mutableIntStateOf(Random.nextInt(OperatingProcedure.drillStarts.size)) }
    var vfo by remember { mutableDoubleStateOf(0.0) }
    var feedback by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var answered by remember { mutableStateOf(false) }

    fun start() = OperatingProcedure.drillStart(round)
    fun heard() = OperatingProcedure.heardPitch(Settings.sidetoneHz, start(), vfo, 0.0)

    fun grade() {
        val begin = start()
        val offset = OperatingProcedure.transmitOffset(begin, vfo, 0.0)
        val right = OperatingProcedure.isZeroBeat(offset)
        opPractised()
        val rec = OperatingProcedureStore.progress.recordDrill(right)
        OperatingProcedureStore.save(rec.progress)
        answered = true
        val hz = abs(offset).toInt()
        if (right) {
            if (Settings.hapticsEnabled) haptics.success()
            val p = rec.progress
            val note = when {
                rec.passedNow -> resources.getString(R.string.op_drill_passed_note)
                p.drillPassed -> ""
                else -> resources.getString(R.string.op_drill_streak_note, p.drillStreak, OperatingProcedure.ZERO_BEAT_STREAK_TO_PASS)
            }
            val head = resources.getString(R.string.op_drill_right, hz)
            feedback = (if (note.isEmpty()) head else "$head $note") to true
        } else {
            if (Settings.hapticsEnabled) haptics.error()
            feedback = resources.getString(
                if (offset > 0) R.string.op_drill_wrong_above else R.string.op_drill_wrong_below,
                hz,
                signed(begin.toInt())
            ) to false
        }
    }

    fun nextRound() {
        round++
        vfo = 0.0
        answered = false
        feedback = null
        audio.tone("VVV", heard())
    }

    Column(
        modifier = Modifier.fillMaxWidth().brandCard().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val streakLabel = stringResource(R.string.op_in_a_row, progress.drillStreak, OperatingProcedure.ZERO_BEAT_STREAK_TO_PASS)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OpCardHeader(stringResource(R.string.op_zero_beat_it))
            Spacer(Modifier.weight(1f))
            if (progress.drillPassed) OpBadge(stringResource(R.string.op_drill_passed))
            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier.semantics { contentDescription = streakLabel }
            ) {
                repeat(OperatingProcedure.ZERO_BEAT_STREAK_TO_PASS) { i ->
                    Box(
                        Modifier.size(10.dp).clip(CircleShape)
                            .background(if (i < progress.drillStreak) Brand.tealBright else Brand.navyRaised)
                    )
                }
            }
        }
        Text(stringResource(R.string.op_drill_intro), style = MaterialTheme.typography.bodyMedium, color = Brand.textPrimary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OpIconButton(Icons.Filled.SettingsInputAntenna, stringResource(R.string.op_station), Modifier.weight(1f)) {
                audio.tone("VVV", heard())
            }
            OpIconButton(Icons.Filled.Tune, stringResource(R.string.op_spot), Modifier.weight(1f)) {
                audio.tone("VVV", Settings.sidetoneHz)
            }
            OpIconButton(Icons.Filled.GraphicEq, stringResource(R.string.op_together), Modifier.weight(1f)) {
                audio.tone("TTT", heard(), sidetone = Settings.sidetoneHz)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (step in OperatingProcedure.knobSteps) {
                val hz = abs(step).toInt()
                val description = stringResource(if (step > 0) R.string.op_tune_up else R.string.op_tune_down, hz)
                OutlinedButton(
                    onClick = {
                        vfo += step
                        answered = false
                        feedback = null
                        audio.tone("VVV", heard())
                    },
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                    modifier = Modifier.weight(1f).semantics { contentDescription = description }
                ) { Text(signed(step.toInt()), color = Brand.teal, maxLines = 1) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.op_tuning, signed(vfo.toInt())),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = Brand.textPrimary,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = { if (answered) nextRound() else grade() },
                colors = ButtonDefaults.buttonColors(containerColor = Brand.teal, contentColor = Brand.navy)
            ) { Text(stringResource(if (answered) R.string.op_next else R.string.op_done), fontWeight = FontWeight.Bold) }
        }
        feedback?.let { (message, good) ->
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (good) Brand.tealBright else Brand.warning
            )
        }
    }
}

// MARK: Scenarios

/**
 * One scenario on screen: the situation, the clip, the shuffled choices, and
 * after an answer the explanation. Used by a lesson's run and by "What should
 * you do?".
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
    val order = remember(scenario.id) { scenario.choices.shuffled() }
    LaunchedEffect(scenario.id) { if (scenario.clip.isNotEmpty()) audio.play(scenario.clip) }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(opSituation(scenario, call), style = MaterialTheme.typography.bodyMedium, color = Brand.textSecondary)
        if (scenario.clip.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SlashableText(
                    OperatingProcedure.display(scenario.clip),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                    color = Brand.textPrimary,
                    modifier = Modifier.weight(1f)
                )
                OpIconButton(Icons.Filled.Refresh, stringResource(R.string.op_replay)) { audio.play(scenario.clip) }
            }
        }
        Text(opQuestion(scenario), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Brand.textPrimary)
        for (choice in order) {
            val background = when {
                picked == null -> Brand.navyRaised
                scenario.accepts(choice) -> Brand.teal.copy(alpha = 0.35f)
                choice == picked -> Brand.warning.copy(alpha = 0.3f)
                else -> Brand.navyRaised.copy(alpha = 0.6f)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(background)
                    .clickable(enabled = picked == null) { onPick(choice) }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    opChoiceLabel(choice),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Brand.textPrimary,
                    modifier = Modifier.weight(1f)
                )
                if (picked != null && scenario.accepts(choice)) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Brand.tealBright)
                } else if (picked == choice) {
                    Icon(Icons.Filled.Close, contentDescription = null, tint = Brand.warning)
                }
            }
        }
        if (picked != null) {
            val right = scenario.accepts(picked)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(if (right) R.string.op_right else R.string.op_not_this_time),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (right) Brand.tealBright else Brand.warning
                )
                Text(opExplanation(scenario, call, state), style = MaterialTheme.typography.bodyMedium, color = Brand.textPrimary)
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
    audio: OpAudio,
    onNext: () -> Unit
) {
    val context = LocalContext.current
    val haptics = remember { Haptics(context) }
    var run by remember { mutableStateOf(OpScenarioRun(emptyList())) }
    var tick by remember { mutableIntStateOf(0) }   // bumped when the run moves
    var picked by remember { mutableStateOf<OpChoice?>(null) }
    var started by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<Boolean, Boolean>?>(null) }  // clean, passed now

    fun newRun() {
        run = OpScenarioRun(OperatingProcedure.scenarios(lesson, call, state))
        picked = null
        result = null
        tick++
    }

    fun advance() {
        val p = picked ?: return
        run.answer(p)
        picked = null
        tick++
        if (run.isFinished) {
            val clean = run.isClean
            val rec = OperatingProcedureStore.progress.recordRun(lesson, clean)
            OperatingProcedureStore.save(rec.progress)
            result = clean to rec.passedNow
            if (clean && Settings.hapticsEnabled) haptics.success()
        }
    }

    // Read so a moved run recomposes.
    @Suppress("UNUSED_VARIABLE")
    val moved = tick

    Column(
        modifier = Modifier.fillMaxWidth().brandCard().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val finished = result
        Row(verticalAlignment = Alignment.CenterVertically) {
            OpCardHeader(stringResource(R.string.op_try_it))
            Spacer(Modifier.weight(1f))
            if (started && finished == null) {
                Text(
                    stringResource(R.string.op_count, minOf(run.index + 1, run.scenarios.size), run.scenarios.size),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Brand.textSecondary
                )
            }
        }
        val scenario = run.current
        when {
            finished != null -> {
                val (clean, passedNow) = finished
                val passed = progress.hasPassed(lesson)
                if (clean) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Brand.tealBright)
                        Text(
                            stringResource(
                                when {
                                    !passed -> R.string.op_clean_run_drill
                                    passedNow -> R.string.op_lesson_passed
                                    else -> R.string.op_clean_run
                                }
                            ),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = Brand.tealBright
                        )
                    }
                } else {
                    Text(
                        stringResource(R.string.op_run_mistakes, run.mistakes),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Brand.textPrimary
                    )
                }
                if (passed) {
                    val next = progress.nextLesson
                    OpPrimaryButton(
                        if (next != null) stringResource(R.string.op_next_lesson, opLessonTitle(next))
                        else stringResource(R.string.op_back_to_lessons),
                        onNext
                    )
                }
                OutlinedButton(onClick = { newRun() }) {
                    Text(stringResource(if (clean) R.string.op_run_again else R.string.op_go_again), color = Brand.teal)
                }
            }
            !started -> {
                val count = OperatingProcedure.scenarios(lesson, call, state).size
                Text(
                    stringResource(if (lesson == OpLesson.OFFSET) R.string.op_run_intro_drill else R.string.op_run_intro, count),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Brand.textPrimary
                )
                OpPrimaryButton(stringResource(R.string.op_start)) {
                    newRun()
                    started = true
                }
            }
            scenario != null -> {
                OpScenarioCard(scenario, call, state, picked, audio) { choice ->
                    picked = choice
                    opPractised()
                    if (Settings.hapticsEnabled) {
                        if (scenario.accepts(choice)) haptics.success() else haptics.error()
                    }
                }
                if (picked != null) {
                    OpPrimaryButton(
                        stringResource(if (run.index + 1 >= run.scenarios.size) R.string.op_finish else R.string.op_next)
                    ) { advance() }
                }
            }
        }
    }
}

/** "What should you do?": ten action scenarios from every lesson, shuffled. */
@Composable
private fun OpScenarioMode(call: String, state: String, audio: OpAudio, onOpenLesson: (OpLesson) -> Unit) {
    val context = LocalContext.current
    val haptics = remember { Haptics(context) }
    fun deal() = OperatingProcedure.actionPool(call, state).shuffled().take(OperatingProcedure.SCENARIO_RUN_LENGTH)
    var deck by remember { mutableStateOf(deal()) }
    var index by remember { mutableIntStateOf(0) }
    var picked by remember { mutableStateOf<OpChoice?>(null) }
    var right by remember { mutableIntStateOf(0) }

    Text(stringResource(R.string.op_mode_intro), style = MaterialTheme.typography.bodyMedium, color = Brand.textSecondary)
    if (index < deck.size) {
        val scenario = deck[index]
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.op_count, index + 1, deck.size),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = Brand.textSecondary,
                modifier = Modifier.weight(1f)
            )
            Text(
                stringResource(R.string.op_right_count, right),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = Brand.tealBright
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth().brandCard().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OpScenarioCard(scenario, call, state, picked, audio) { choice ->
                picked = choice
                opPractised()
                if (scenario.accepts(choice)) {
                    right++
                    if (Settings.hapticsEnabled) haptics.success()
                } else if (Settings.hapticsEnabled) {
                    haptics.error()
                }
            }
            if (picked != null) {
                OpIconButton(
                    Icons.AutoMirrored.Filled.MenuBook,
                    stringResource(R.string.op_open_lesson, opLessonTitle(scenario.lesson))
                ) { onOpenLesson(scenario.lesson) }
                OpPrimaryButton(
                    stringResource(if (index + 1 >= deck.size) R.string.op_see_how else R.string.op_next)
                ) {
                    picked = null
                    index++
                }
            }
        }
    } else if (deck.isNotEmpty()) {
        Column(
            modifier = Modifier.fillMaxWidth().brandCard().padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                stringResource(R.string.op_count, right, deck.size),
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                color = Brand.tealBright
            )
            Text(
                stringResource(if (right == deck.size) R.string.op_mode_all_right else R.string.op_mode_some_wrong),
                style = MaterialTheme.typography.bodyMedium,
                color = Brand.textPrimary,
                textAlign = TextAlign.Center
            )
            OpPrimaryButton(stringResource(R.string.op_go_again)) {
                deck = deal()
                index = 0
                right = 0
                picked = null
            }
        }
    }
}

// MARK: The words
//
// The section's words. The morsekit rules pin ids and keys; the words are
// this platform's own, kept in step with iOS's OpCopy and desktop's strings.

/** Lesson 1's signal table: what is sent, and what it asks of you. */
private val OP_SIGNALS: List<Pair<String, Int>> = listOf(
    "?" to R.string.op_signal_question,
    "AGN?" to R.string.op_signal_agn,
    "<AS>" to R.string.op_signal_as,
    "BK" to R.string.op_signal_bk,
    "SRI" to R.string.op_signal_sri,
    "QRZ?" to R.string.op_signal_qrz,
    "E E" to R.string.op_signal_ee,
)

private class OpRig(val maker: String, val rit: String, val xit: String, val pitch: String, val aid: String)

/** Lesson 8's rig table: each maker's name for the controls (panel labels, not translated). */
@Composable
private fun opRigTable(): List<OpRig> = listOf(
    OpRig("Icom", "RIT", "∂TX (delta TX)", "CW PITCH", "AUTOTUNE"),
    OpRig("Yaesu", "CLAR (RX)", "CLAR (TX)", "CW PITCH", "ZIN/SPOT"),
    OpRig("Kenwood", "RIT", "XIT", "CW pitch", "CW T. (auto tune)"),
    OpRig("Elecraft", "RIT", "XIT", "PITCH", "SPOT, auto-spot"),
    OpRig("FlexRadio", "RIT", "XIT", stringResource(R.string.op_rig_flex_pitch), stringResource(R.string.op_rig_flex_aid)),
)

@Composable
private fun opLessonTitle(lesson: OpLesson): String = stringResource(
    when (lesson) {
        OpLesson.SIGNALS -> R.string.op_lesson_signals
        OpLesson.WHEN -> R.string.op_lesson_when
        OpLesson.ONCE -> R.string.op_lesson_once
        OpLesson.PARTIAL -> R.string.op_lesson_partial
        OpLesson.ME -> R.string.op_lesson_me
        OpLesson.EXCHANGE -> R.string.op_lesson_exchange
        OpLesson.MISTAKE -> R.string.op_lesson_mistake
        OpLesson.OFFSET -> R.string.op_lesson_offset
    }
)

@Composable
private fun opLessonSubtitle(lesson: OpLesson): String = stringResource(
    when (lesson) {
        OpLesson.SIGNALS -> R.string.op_sub_signals
        OpLesson.WHEN -> R.string.op_sub_when
        OpLesson.ONCE -> R.string.op_sub_once
        OpLesson.PARTIAL -> R.string.op_sub_partial
        OpLesson.ME -> R.string.op_sub_me
        OpLesson.EXCHANGE -> R.string.op_sub_exchange
        OpLesson.MISTAKE -> R.string.op_sub_mistake
        OpLesson.OFFSET -> R.string.op_sub_offset
    }
)

@Composable
private fun opConcept(lesson: OpLesson, call: String, state: String): List<String> = when (lesson) {
    OpLesson.SIGNALS -> listOf(stringResource(R.string.op_concept_signals_1))
    OpLesson.WHEN -> listOf(
        stringResource(R.string.op_concept_when_1),
        stringResource(R.string.op_concept_when_2),
        stringResource(R.string.op_concept_when_3),
    )
    OpLesson.ONCE -> listOf(
        stringResource(R.string.op_concept_once_1),
        stringResource(R.string.op_concept_once_2),
        stringResource(R.string.op_concept_once_3),
    )
    OpLesson.PARTIAL -> listOf(
        stringResource(R.string.op_concept_partial_1),
        stringResource(R.string.op_concept_partial_2),
        stringResource(R.string.op_concept_partial_3),
    )
    OpLesson.ME -> listOf(
        stringResource(R.string.op_concept_me_1),
        stringResource(R.string.op_concept_me_2),
        stringResource(R.string.op_concept_me_3, OperatingProcedure.nearMiss(call), call),
        stringResource(R.string.op_concept_me_4, OperatingProcedure.nearMiss(call)),
    )
    OpLesson.EXCHANGE -> listOf(
        stringResource(R.string.op_concept_exchange_1, OperatingProcedure.reply(state)),
        stringResource(R.string.op_concept_exchange_2, OperatingProcedure.replyBK(state), OperatingProcedure.CLOSE_BK),
        stringResource(R.string.op_concept_exchange_3),
        stringResource(R.string.op_concept_exchange_4),
    )
    OpLesson.MISTAKE -> listOf(
        stringResource(R.string.op_concept_mistake_1),
        stringResource(R.string.op_concept_mistake_2),
        stringResource(R.string.op_concept_mistake_3, OperatingProcedure.ERROR_DISPLAY),
    )
    OpLesson.OFFSET -> listOf(
        stringResource(R.string.op_concept_offset_1),
        stringResource(R.string.op_concept_offset_2),
        stringResource(R.string.op_concept_offset_3),
        stringResource(R.string.op_concept_offset_4),
    )
}

@Composable
private fun opKindLabel(kind: OpDemo.Kind): String = stringResource(
    when (kind) {
        OpDemo.Kind.LISTEN -> R.string.op_kind_listen
        OpDemo.Kind.RIGHT -> R.string.op_right
        OpDemo.Kind.WRONG -> R.string.op_wrong
    }
)

@Composable
private fun opPassTitle(pass: OperatingProcedure.PileupPass): String = when (pass) {
    OperatingProcedure.PileupPass.ZERO_BEAT -> stringResource(R.string.op_pass_zero_beat)
    OperatingProcedure.PileupPass.YOU_OFFSET ->
        stringResource(R.string.op_pass_you_offset, OperatingProcedure.DEMO_YOUR_OFFSET_HZ.toInt())
    OperatingProcedure.PileupPass.ALL_OFFSET -> stringResource(R.string.op_pass_all_offset)
}

@Composable
private fun opPassCaption(pass: OperatingProcedure.PileupPass, call: String): String = when (pass) {
    OperatingProcedure.PileupPass.ZERO_BEAT -> stringResource(R.string.op_pass_zero_beat_caption, call)
    OperatingProcedure.PileupPass.YOU_OFFSET ->
        stringResource(R.string.op_pass_you_offset_caption, OperatingProcedure.DEMO_YOUR_OFFSET_HZ.toInt())
    OperatingProcedure.PileupPass.ALL_OFFSET -> stringResource(R.string.op_pass_all_offset_caption)
}

@Composable
private fun opSituation(s: OpScenario, call: String): String {
    val act = OperatingProcedure.activator(call).call
    return when (s.id) {
        "signals.as", "signals.qrz", "signals.ee", "signals.bk", "signals.agn" ->
            stringResource(R.string.op_sit_activator_sends)
        "when.dits", "when.inProgress", "when.as", "when.sriQrz" -> stringResource(R.string.op_sit_waiting, act)
        "once.cq", "once.qrz", "once.dits" -> stringResource(R.string.op_sit_time_to_call, act)
        "exchange.agn" -> stringResource(R.string.op_sit_exchange_agn)
        "exchange.stop" -> stringResource(R.string.op_sit_exchange_stop)
        "mistake.call" -> stringResource(R.string.op_sit_mistake_call, s.detail)
        "mistake.last" -> stringResource(R.string.op_sit_mistake_last, s.detail)
        "mistake.state" -> stringResource(R.string.op_sit_mistake_state, s.detail)
        "offset.pileup" -> stringResource(R.string.op_sit_offset_pileup)
        "offset.rit" -> stringResource(R.string.op_sit_offset_rit)
        "offset.xit" -> stringResource(R.string.op_sit_offset_xit)
        "offset.tune" -> stringResource(R.string.op_sit_offset_tune)
        else -> stringResource(R.string.op_sit_called, act)
    }
}

@Composable
private fun opQuestion(s: OpScenario): String = stringResource(
    when (s.lesson) {
        OpLesson.SIGNALS -> R.string.op_q_meaning
        OpLesson.MISTAKE -> R.string.op_q_what_next
        OpLesson.OFFSET -> when (s.id) {
            "offset.pileup" -> R.string.op_q_where_call
            "offset.tune" -> R.string.op_q_where_tune
            else -> R.string.op_q_which_control
        }
        else -> R.string.op_q_what_do
    }
)

@Composable
private fun opChoiceLabel(c: OpChoice): String = when (c) {
    is OpChoice.Send -> stringResource(R.string.op_choice_send, OperatingProcedure.display(c.text))
    OpChoice.Silent -> stringResource(R.string.op_choice_silent)
    is OpChoice.Option -> opOption(c.key)
}

@Composable
private fun opOption(key: String): String {
    val res = when (key) {
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
    return stringResource(res)
}

@Composable
private fun opExplanation(s: OpScenario, call: String, state: String): String {
    val other = OperatingProcedure.otherHunter(call)
    return when (s.id) {
        "signals.as" -> stringResource(R.string.op_ex_signals_as)
        "signals.qrz" -> stringResource(R.string.op_ex_signals_qrz)
        "signals.ee" -> stringResource(R.string.op_ex_signals_ee)
        "signals.bk" -> stringResource(R.string.op_ex_signals_bk)
        "signals.agn" -> stringResource(R.string.op_ex_signals_agn)
        "when.dits" -> stringResource(R.string.op_ex_when_dits, other)
        "when.inProgress" -> stringResource(R.string.op_ex_when_in_progress, other)
        "when.as" -> stringResource(R.string.op_ex_when_as)
        "when.sriQrz" -> stringResource(R.string.op_ex_when_sri_qrz)
        "once.cq" -> stringResource(R.string.op_ex_once_cq)
        "once.qrz" -> stringResource(R.string.op_ex_once_qrz)
        "once.dits" -> stringResource(R.string.op_ex_once_dits)
        "partial.prefix" -> stringResource(R.string.op_ex_partial_prefix, s.clip)
        "partial.notMine" -> stringResource(R.string.op_ex_partial_not_mine, s.clip)
        "partial.suffix" -> stringResource(R.string.op_ex_partial_suffix, s.clip)
        "partial.fullCall" -> stringResource(R.string.op_ex_partial_full_call)
        "me.other" -> stringResource(R.string.op_ex_me_other, other)
        "me.mine" -> stringResource(R.string.op_ex_me_mine, OperatingProcedure.reply(state), OperatingProcedure.replyBK(state))
        "me.close" -> stringResource(R.string.op_ex_me_close, s.detail)
        "me.closeAsked" -> stringResource(R.string.op_ex_me_close_asked, s.detail)
        "exchange.reply" -> stringResource(R.string.op_ex_exchange_reply)
        "exchange.agn" -> stringResource(R.string.op_ex_exchange_agn)
        "exchange.dits" -> stringResource(R.string.op_ex_exchange_dits)
        "exchange.stop" -> stringResource(R.string.op_ex_exchange_stop)
        "mistake.call" -> stringResource(R.string.op_ex_mistake_call)
        "mistake.last" -> stringResource(R.string.op_ex_mistake_last)
        "mistake.state" -> stringResource(R.string.op_ex_mistake_state)
        "mistake.hear" -> stringResource(R.string.op_ex_mistake_hear, s.detail)
        "offset.pileup" -> stringResource(R.string.op_ex_offset_pileup)
        "offset.rit" -> stringResource(R.string.op_ex_offset_rit)
        "offset.xit" -> stringResource(R.string.op_ex_offset_xit)
        "offset.tune" -> stringResource(R.string.op_ex_offset_tune)
        else -> ""
    }
}
