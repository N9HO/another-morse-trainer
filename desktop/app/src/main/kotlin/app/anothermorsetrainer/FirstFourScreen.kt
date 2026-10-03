package app.anothermorsetrainer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.anothermorsetrainer.morsekit.FirstFour
import app.anothermorsetrainer.morsekit.FirstFourBeat
import app.anothermorsetrainer.morsekit.FirstFourPhase
import app.anothermorsetrainer.morsekit.FirstFourProgress
import app.anothermorsetrainer.morsekit.FirstFourResponse
import app.anothermorsetrainer.morsekit.FirstFourScene
import app.anothermorsetrainer.morsekit.FirstFourStage
import app.anothermorsetrainer.morsekit.FirstFourStation
import app.anothermorsetrainer.morsekit.FirstFourVerdict
import app.anothermorsetrainer.morsekit.MorseItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * **First Four** (#265, docs/first-four-design.md): just enough CW to hunt
 * one POTA activator. Your call, your state, "?" and "73" — heard and typed,
 * then keyed — and three short scenes: a busted call, a call nobody answers,
 * and a whole contact. The rules are morsekit's [FirstFour]; this screen only
 * sounds, shows and records them. Twin of the iOS `FirstFourView.swift`; the
 * desktop port (#280) of the Android screen.
 *
 * A tutorial, not a session: no [Stats] session, no leaderboard. A graded
 * answer counts as practice for the day ([Stats.recordPracticeDay]). Progress
 * lives in [FirstFourStore].
 *
 * Desktop: keyboard-first. A typed copy answer submits on Enter; while a key
 * is on screen, Space is the straight key and `[` / `]` the paddles
 * ([MorseKeyboard], via [OnScreenKeySwitch]), as are a USB Vail adapter or
 * other MIDI key ([HardwareKey]). Enter also takes the one obvious next step
 * where there is no text to type: Continue, Wait and listen, Next, Go again.
 * The screen root holds focus and forwards those keys in the preview pass, so
 * they work without a click and a focused button never also acts on them.
 */
@Composable
fun FirstFourScreen(onBack: () -> Unit) {
    val player = remember { MorsePlayer() }
    DisposableEffect(Unit) {
        onDispose {
            player.stop()
            player.release()
        }
    }

    // Recompose on every save; the progress object itself is mutated in place.
    @Suppress("UNUSED_VARIABLE")
    val version = FirstFourStore.version
    val progress = FirstFourStore.progress

    var openStageRaw by rememberSaveable { mutableStateOf("") }
    val openStage = FirstFourStage.fromRaw(openStageRaw)
    var editingStation by rememberSaveable { mutableStateOf(false) }
    var confirmingReset by rememberSaveable { mutableStateOf(false) }
    val enter = remember { FirstFourEnter() }
    val rootFocus = remember { FocusRequester() }
    val focusRoot: () -> Unit = remember { { runCatching { rootFocus.requestFocus() } } }

    val call = FirstFour.normalizeCall(PileupSettings.myCall)
    val state = FirstFour.normalizeState(PileupSettings.myState)
    val stationReady = FirstFour.prefillCall(call).isNotEmpty() &&
        FirstFour.isValidCall(call) && FirstFour.isValidState(state)

    val play: (String) -> Double = { text ->
        if (text.isEmpty()) 0.0
        else player.replaySound(MorseItem.Playable.Text(text), Settings.sidetoneHz, Settings.timing())
    }

    fun closeStage() {
        player.stop()
        openStageRaw = ""
    }

    BackHandler { if (openStage != null) closeStage() else onBack() }

    if (confirmingReset) {
        AlertDialog(
            onDismissRequest = { confirmingReset = false },
            containerColor = Brand.navyElevated,
            title = { Text(stringResource(R.string.first_four_start_over_title), color = Brand.textPrimary) },
            text = { Text(stringResource(R.string.first_four_start_over_body), color = Brand.textSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingReset = false
                    FirstFourStore.reset()
                }) { Text(stringResource(R.string.first_four_start_over), color = Brand.warning) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingReset = false }) {
                    Text(stringResource(R.string.common_cancel), color = Brand.teal)
                }
            }
        )
    }

    // Launched before the content's own requests (a text field, the key), so
    // those win: the root only keeps focus where nothing else asks for it.
    LaunchedEffect(openStageRaw, editingStation, stationReady) { focusRoot() }

    CompositionLocalProvider(LocalFirstFourEnter provides enter, LocalFirstFourFocusRoot provides focusRoot) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { event ->
                    !confirmingReset && (MorseKeyboard.handle(event) || enter.handle(event))
                }
                .focusRequester(rootFocus)
                .focusable()
        ) {
            Row(modifier = Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (openStage != null) {
                    TextButton(onClick = { closeStage() }) {
                        Text(stringResource(R.string.first_four_stages_back), color = Brand.teal)
                    }
                } else {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.common_back), color = Brand.teal) }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    openStage?.let { firstFourStageTitle(it) } ?: stringResource(R.string.first_four_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Brand.textPrimary
                )
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(72.dp))
            }

            CenteredScrollColumn(
                contentModifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                when {
                    openStage != null -> {
                        val next = {
                            player.stop()
                            openStageRaw = FirstFourStore.progress.nextStage?.raw ?: ""
                        }
                        val element = FirstFour.element(openStage, call, state)
                        if (element != null) {
                            // Keyed by stage: a new stage starts its own state.
                            androidx.compose.runtime.key(openStage) {
                                FirstFourElementStage(openStage, element, progress, play, next)
                            }
                        } else {
                            androidx.compose.runtime.key(openStage) {
                                FirstFourSceneStage(openStage, call, state, progress, play, player::stop, next)
                            }
                        }
                    }
                    !stationReady || editingStation -> FirstFourStationCard(
                        editing = editingStation,
                        onSaved = { editingStation = false }
                    )
                    else -> FirstFourHome(
                        call = call,
                        state = state,
                        progress = progress,
                        onOpen = { openStageRaw = it.raw },
                        onChange = { editingStation = true },
                        onReset = { confirmingReset = true }
                    )
                }
            }
        }
    }
}

/**
 * Desktop: the action Enter takes where no text field or key wants it. The
 * most recent [OnEnter] registered wins; with none, Enter is left alone.
 */
private class FirstFourEnter {
    var action: (() -> Unit)? = null

    fun handle(event: KeyEvent): Boolean {
        if (event.key != Key.Enter && event.key != Key.NumPadEnter) return false
        val act = action ?: return false
        if (event.type == KeyEventType.KeyDown) act()
        return true
    }
}

private val LocalFirstFourEnter = staticCompositionLocalOf { FirstFourEnter() }
private val LocalFirstFourFocusRoot = staticCompositionLocalOf<() -> Unit> { {} }

/** Enter does [action] while this is composed and [enabled]. */
@Composable
private fun OnEnter(enabled: Boolean = true, action: () -> Unit) {
    val slot = LocalFirstFourEnter.current
    val current by rememberUpdatedState(action)
    val entry: () -> Unit = remember { { current() } }
    DisposableEffect(enabled) {
        if (enabled) slot.action = entry
        onDispose { if (slot.action === entry) slot.action = null }
    }
}

/** Desktop: Enter in a text field does [onEnter], taken before the field sees it. */
private fun Modifier.enterSubmits(onEnter: () -> Unit): Modifier = onPreviewKeyEvent { event ->
    if (event.key == Key.Enter || event.key == Key.NumPadEnter) {
        if (event.type == KeyEventType.KeyDown) onEnter()
        true
    } else false
}

@Composable
private fun firstFourStageTitle(stage: FirstFourStage): String = stringResource(
    when (stage) {
        FirstFourStage.CALL -> R.string.first_four_stage_call
        FirstFourStage.STATE -> R.string.first_four_stage_state
        FirstFourStage.QUESTION -> R.string.first_four_stage_question
        FirstFourStage.SEVENTY_THREE -> R.string.first_four_stage_73
        FirstFourStage.BUSTED_CALL -> R.string.first_four_stage_busted
        FirstFourStage.NO_REPLY -> R.string.first_four_stage_no_reply
        FirstFourStage.WALKTHROUGH -> R.string.first_four_stage_walkthrough
    }
)

private fun practised() = Stats.recordPracticeDay()

// MARK: Your station

@Composable
private fun FirstFourStationCard(editing: Boolean, onSaved: () -> Unit) {
    var callField by rememberSaveable { mutableStateOf(FirstFourStore.prefillCall()) }
    var stateField by rememberSaveable { mutableStateOf(FirstFour.normalizeState(PileupSettings.myState)) }
    val valid = FirstFour.isValidCall(callField) && FirstFour.isValidState(stateField)
    val callFocus = remember { FocusRequester() }
    val stateFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { callFocus.requestFocus() } }
    val save = {
        if (valid) {
            PileupSettings.updateMyCall(FirstFour.normalizeCall(callField))
            PileupSettings.updateMyState(FirstFour.normalizeState(stateField))
            onSaved()
        }
    }
    Column(
        modifier = Modifier.fillMaxWidth().brandCard().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            stringResource(R.string.first_four_station_heading),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = Brand.textPrimary
        )
        Text(stringResource(R.string.first_four_station_blurb), style = MaterialTheme.typography.bodyMedium, color = Brand.textSecondary)
        OutlinedTextField(
            value = callField,
            onValueChange = { callField = it.uppercase() },
            singleLine = true,
            label = { Text(stringResource(R.string.first_four_your_call)) },
            placeholder = { Text("N9HO") },
            textStyle = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Next),
            // Desktop: Enter goes on to the state.
            modifier = Modifier.fillMaxWidth().focusRequester(callFocus)
                .enterSubmits { runCatching { stateFocus.requestFocus() } }
        )
        OutlinedTextField(
            value = stateField,
            onValueChange = { stateField = it.uppercase() },
            singleLine = true,
            label = { Text(stringResource(R.string.first_four_your_state)) },
            placeholder = { Text("WI") },
            textStyle = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
            // Desktop: Enter saves, once both are valid.
            modifier = Modifier.fillMaxWidth().focusRequester(stateFocus).enterSubmits { save() }
        )
        if (callField.isNotEmpty() && !FirstFour.isValidCall(callField)) {
            Text(stringResource(R.string.first_four_call_invalid), style = MaterialTheme.typography.labelSmall, color = Brand.warning)
        }
        if (stateField.isNotEmpty() && !FirstFour.isValidState(stateField)) {
            Text(stringResource(R.string.first_four_state_invalid), style = MaterialTheme.typography.labelSmall, color = Brand.warning)
        }
        Text(stringResource(R.string.first_four_station_saved_where), style = MaterialTheme.typography.labelSmall, color = Brand.textSecondary)
        Button(
            onClick = { save() },
            enabled = valid,
            colors = ButtonDefaults.buttonColors(containerColor = Brand.teal, contentColor = Brand.navy),
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) {
            Text(stringResource(if (editing) R.string.first_four_save else R.string.first_four_start), fontWeight = FontWeight.Bold)
        }
    }
}

// MARK: The stage list

@Composable
private fun FirstFourHome(
    call: String,
    state: String,
    progress: FirstFourProgress,
    onOpen: (FirstFourStage) -> Unit,
    onChange: () -> Unit,
    onReset: () -> Unit
) {
    val total = FirstFourStage.entries.size
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "$call · $state",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                fontSize = 22.sp,
                color = Brand.textPrimary
            )
            Text(
                stringResource(R.string.first_four_stages_passed, progress.passedCount, total),
                style = MaterialTheme.typography.labelSmall,
                color = Brand.textSecondary
            )
        }
        TextButton(onClick = onChange) { Text(stringResource(R.string.first_four_change), color = Brand.teal) }
    }

    if (progress.isComplete) FirstFourFinale()

    progress.nextStage?.let { next ->
        OnEnter { onOpen(next) }
        Button(
            onClick = { onOpen(next) },
            colors = ButtonDefaults.buttonColors(containerColor = Brand.teal, contentColor = Brand.navy),
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) {
            val title = firstFourStageTitle(next)
            Text(
                stringResource(if (progress.passedCount == 0) R.string.first_four_begin else R.string.first_four_continue_stage, title),
                fontWeight = FontWeight.Bold
            )
        }
    }

    FirstFourStage.entries.forEachIndexed { i, stage ->
        val passed = progress.hasPassed(stage)
        val title = firstFourStageTitle(stage)
        val subtitle = when (stage) {
            FirstFourStage.CALL -> stringResource(R.string.first_four_sub_element_named, call)
            FirstFourStage.STATE -> stringResource(R.string.first_four_sub_element_named, state)
            FirstFourStage.QUESTION, FirstFourStage.SEVENTY_THREE -> stringResource(R.string.first_four_sub_element)
            FirstFourStage.BUSTED_CALL -> stringResource(R.string.first_four_sub_busted)
            FirstFourStage.NO_REPLY -> stringResource(R.string.first_four_sub_no_reply)
            FirstFourStage.WALKTHROUGH -> stringResource(R.string.first_four_sub_walkthrough)
        }
        val label = stringResource(R.string.first_four_stage_number, i + 1, title)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Brand.navyElevated)
                .clickable { onOpen(stage) }
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
                    Text("${i + 1}", color = Brand.textPrimary, fontWeight = FontWeight.SemiBold)
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Brand.textPrimary)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = Brand.textSecondary)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Brand.textSecondary)
        }
    }

    Text(
        stringResource(R.string.first_four_speed_note, Settings.characterWpm.roundToInt()),
        style = MaterialTheme.typography.labelSmall,
        color = Brand.textSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )

    if (progress.passedCount > 0) {
        TextButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.first_four_start_over), color = Brand.warning)
        }
    }
}

/** Shown once the walkthrough has passed: ready for the air, and say thank you afterwards (#265). */
@Composable
private fun FirstFourFinale() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Brand.navyElevated)
            .border(1.5.dp, Brand.tealBright.copy(alpha = 0.7f), RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(stringResource(R.string.first_four_finale_title), fontWeight = FontWeight.SemiBold, color = Brand.tealBright)
        Text(stringResource(R.string.first_four_finale_body1), style = MaterialTheme.typography.bodyMedium, color = Brand.textPrimary)
        Text(stringResource(R.string.first_four_finale_body2), style = MaterialTheme.typography.bodyMedium, color = Brand.textSecondary)
    }
}

// MARK: Element stages: copy, then send

@Composable
private fun FirstFourElementStage(
    stage: FirstFourStage,
    text: String,
    progress: FirstFourProgress,
    play: (String) -> Double,
    onNext: () -> Unit
) {
    val haptics = remember { Haptics() }
    val scope = rememberCoroutineScope()
    val focusRoot = LocalFirstFourFocusRoot.current
    var phase by remember { mutableStateOf(progress.openingPhase(stage)) }
    var typed by remember { mutableStateOf("") }
    var feedback by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var stagePassed by remember { mutableStateOf(false) }
    var keyReset by remember { mutableIntStateOf(0) }
    val needed = if (phase == FirstFourPhase.COPY) FirstFour.COPY_STREAK_TO_PASS else FirstFour.SEND_STREAK_TO_PASS

    val resources = LocalResources.current

    LaunchedEffect(Unit) { if (phase == FirstFourPhase.COPY) play(text) }

    fun record(correct: Boolean, heard: String) {
        practised()
        val done = progress.recordElement(stage, phase, correct)
        FirstFourStore.save(progress)
        if (Settings.hapticsEnabled) { if (correct) haptics.success() else haptics.error() }
        when {
            done && phase == FirstFourPhase.COPY -> {
                feedback = resources.getString(R.string.first_four_copied_now_send, FirstFour.COPY_STREAK_TO_PASS) to true
                phase = FirstFourPhase.SEND
            }
            done -> {
                feedback = null
                stagePassed = true
            }
            correct -> {
                feedback = resources.getString(R.string.first_four_right, progress.streak, needed) to true
                if (phase == FirstFourPhase.COPY) scope.launch { delay(600); play(text) }
            }
            else -> {
                val shown = if (FirstFour.compact(heard).isEmpty()) {
                    resources.getString(R.string.first_four_nothing)
                } else {
                    "“${heard.uppercase()}”"
                }
                feedback = resources.getString(R.string.first_four_wrong, shown, text) to false
            }
        }
    }

    Text(
        stringResource(
            when (stage) {
                FirstFourStage.CALL -> R.string.first_four_explain_call
                FirstFourStage.STATE -> R.string.first_four_explain_state
                FirstFourStage.QUESTION -> R.string.first_four_explain_question
                else -> R.string.first_four_explain_73
            }
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = Brand.textSecondary
    )

    val streakLabel = stringResource(R.string.first_four_streak, progress.streak, needed)
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PhasePill(stringResource(R.string.first_four_phase_copy), phase == FirstFourPhase.COPY, stage in progress.copyPassed)
        PhasePill(stringResource(R.string.first_four_phase_send), phase == FirstFourPhase.SEND, progress.hasPassed(stage))
        Spacer(Modifier.weight(1f))
        Row(
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier.semantics { contentDescription = streakLabel }
        ) {
            repeat(needed) { i ->
                Box(
                    Modifier.size(10.dp).clip(CircleShape)
                        .background(if (i < progress.streak) Brand.tealBright else Brand.navyRaised)
                )
            }
        }
    }

    when {
        stagePassed -> Column(
            modifier = Modifier.fillMaxWidth().brandCard().padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            LaunchedEffect(Unit) { focusRoot() }
            OnEnter(action = onNext)
            Text(stringResource(R.string.first_four_stage_passed), fontWeight = FontWeight.SemiBold, color = Brand.tealBright)
            NextButton(progress, onNext)
            TextButton(onClick = {
                stagePassed = false
                feedback = null
                phase = FirstFourPhase.COPY
                play(text)
            }) { Text(stringResource(R.string.first_four_practice_again), color = Brand.teal) }
        }
        phase == FirstFourPhase.COPY -> Column(
            modifier = Modifier.fillMaxWidth().brandCard().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = { play(text) },
                colors = ButtonDefaults.buttonColors(containerColor = Brand.teal, contentColor = Brand.navy),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) { Text(stringResource(R.string.first_four_play), fontWeight = FontWeight.Bold) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val typeFocus = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { typeFocus.requestFocus() } }
                val check = {
                    if (typed.isNotBlank()) {
                        record(FirstFour.copyMatches(typed, text), typed)
                        typed = ""
                    }
                }
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.first_four_type_hint)) },
                    textStyle = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { check() }),
                    // Desktop: the answer box has the keyboard, and Enter checks.
                    modifier = Modifier.weight(1f).focusRequester(typeFocus).enterSubmits { check() }
                )
                OutlinedButton(onClick = { check() }, enabled = typed.isNotBlank()) {
                    Text(stringResource(R.string.first_four_check), color = Brand.teal)
                }
            }
            Text(stringResource(R.string.first_four_copy_needed, needed), style = MaterialTheme.typography.labelSmall, color = Brand.textSecondary)
        }
        else -> Column(
            modifier = Modifier.fillMaxWidth().brandCard().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.first_four_send_label), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Brand.textSecondary)
                    Text(text, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 30.sp, color = Brand.textPrimary)
                }
                OutlinedButton(onClick = { play(text) }) { Text(stringResource(R.string.first_four_hear_it), color = Brand.teal) }
            }
            FirstFourKeyPanel(expected = text, resetToken = keyReset) { sent ->
                record(FirstFour.sendMatches(sent, text), sent)
                keyReset++
            }
            Text(stringResource(R.string.first_four_send_needed, needed), style = MaterialTheme.typography.labelSmall, color = Brand.textSecondary)
        }
    }

    feedback?.let { (message, good) ->
        Text(
            message,
            fontWeight = FontWeight.SemiBold,
            color = if (good) Brand.tealBright else Brand.warning,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun PhasePill(title: String, active: Boolean, done: Boolean) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (active) Brand.teal else Brand.navyRaised)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (done) Icon(Icons.Filled.Check, contentDescription = null, tint = if (active) Brand.navy else Brand.textPrimary, modifier = Modifier.size(14.dp))
        Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (active) Brand.navy else Brand.textPrimary)
    }
}

@Composable
private fun NextButton(progress: FirstFourProgress, onNext: () -> Unit) {
    val next = progress.nextStage
    val label = if (next != null) {
        stringResource(R.string.first_four_next, firstFourStageTitle(next))
    } else {
        stringResource(R.string.first_four_back_to_stages)
    }
    Button(
        onClick = onNext,
        colors = ButtonDefaults.buttonColors(containerColor = Brand.teal, contentColor = Brand.navy),
        modifier = Modifier.fillMaxWidth().height(48.dp)
    ) { Text(label, fontWeight = FontWeight.Bold) }
}

// MARK: Scenes

@Composable
private fun FirstFourSceneStage(
    stage: FirstFourStage,
    call: String,
    state: String,
    progress: FirstFourProgress,
    play: (String) -> Double,
    stopPlayer: () -> Unit,
    onNext: () -> Unit
) {
    val haptics = remember { Haptics() }
    val scope = rememberCoroutineScope()
    val focusRoot = LocalFirstFourFocusRoot.current

    var scene by remember { mutableStateOf(FirstFourScene(emptyList())) }
    var tick by remember { mutableIntStateOf(0) }   // bumped when the scene moves
    var activator by remember { mutableStateOf(FirstFour.activators[0]) }
    var round by remember { mutableIntStateOf(progress.cleanRuns(stage)) }
    val log = remember { mutableStateListOf<Pair<String, String>>() }
    var heard by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    var feedback by remember { mutableStateOf<String?>(null) }
    var keyReset by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<Pair<Boolean, Boolean>?>(null) }  // clean, passed now
    var playToken by remember { mutableIntStateOf(0) }

    val resources = LocalResources.current
    val youLabel = stringResource(R.string.first_four_you)

    /** Sound the beat if it is a transmission, and log it once heard. */
    fun beginBeat() {
        heard = false
        val beat = scene.current ?: return
        when (beat.kind) {
            FirstFourBeat.Kind.HEAR, FirstFourBeat.Kind.COPY -> {
                playToken++
                val token = playToken
                val seconds = play(beat.text)
                val sceneNow = scene
                scope.launch {
                    delay(((seconds + 0.2) * 1000).toLong())
                    if (token == playToken && sceneNow === scene && scene.current == beat) {
                        heard = true
                        log.add(activator.call to beat.text)
                    }
                }
            }
            FirstFourBeat.Kind.SILENCE -> {
                heard = true
                log.add(activator.call to "…")
            }
            FirstFourBeat.Kind.SEND, FirstFourBeat.Kind.WAIT -> Unit
        }
    }

    fun startRun() {
        stopPlayer()
        val a: FirstFourStation = FirstFour.activator(Random.nextInt(FirstFour.activators.size), call)
        activator = a
        val beats = when (stage) {
            FirstFourStage.BUSTED_CALL -> FirstFour.bustedCallScene(call, FirstFour.partial(call, round), a)
            FirstFourStage.NO_REPLY ->
                if (FirstFour.noReplyUsesSceneB(progress.cleanRuns(FirstFourStage.NO_REPLY))) {
                    val other = FirstFour.otherHunter(Random.nextInt(FirstFour.otherHunters.size), call)
                    FirstFour.noReplySceneB(call, a, other)
                } else {
                    FirstFour.noReplySceneA(call, a)
                }
            else -> FirstFour.walkthroughScene(call, state, a)
        }
        scene = FirstFourScene(beats)
        tick++
        log.clear()
        result = null
        feedback = null
        typed = ""
        keyReset++
        beginBeat()
    }

    fun respond(response: FirstFourResponse) {
        val beat = scene.current ?: return
        val verdict = scene.respond(response)
        tick++
        practised()
        when (verdict) {
            FirstFourVerdict.ADVANCE -> {
                feedback = null
                if (Settings.hapticsEnabled) haptics.tap()
                when (response) {
                    is FirstFourResponse.Sent -> log.add(youLabel to response.text.uppercase())
                    is FirstFourResponse.Copied ->
                        log.add(youLabel to resources.getString(R.string.first_four_log_copied, response.text.uppercase()))
                    FirstFourResponse.Waited -> log.add(youLabel to resources.getString(R.string.first_four_log_waiting))
                    FirstFourResponse.Continued -> Unit
                }
                typed = ""
                keyReset++
                if (scene.isFinished) {
                    val clean = scene.isClean
                    val passedNow = progress.recordScene(stage, clean)
                    FirstFourStore.save(progress)
                    round++
                    if (clean && Settings.hapticsEnabled) haptics.success()
                    result = clean to passedNow
                } else {
                    // A short pause before the activator answers, as on the air.
                    val kind = scene.current?.kind
                    val pause = if (kind == FirstFourBeat.Kind.HEAR || kind == FirstFourBeat.Kind.COPY) 600L else 0L
                    scope.launch { delay(pause); beginBeat() }
                }
            }
            FirstFourVerdict.FINISHED -> Unit
            else -> {
                if (Settings.hapticsEnabled) haptics.error()
                feedback = when (verdict) {
                    FirstFourVerdict.SENT_WRONG -> resources.getString(
                        R.string.first_four_v_sent_wrong,
                        (response as? FirstFourResponse.Sent)?.text?.uppercase() ?: "",
                        beat.text
                    )
                    FirstFourVerdict.COPY_WRONG -> resources.getString(R.string.first_four_v_copy_wrong)
                    FirstFourVerdict.YOUR_TURN -> resources.getString(R.string.first_four_v_your_turn)
                    FirstFourVerdict.STAY_QUIET -> resources.getString(R.string.first_four_v_stay_quiet)
                    else -> resources.getString(R.string.first_four_v_not_your_turn)
                }
                keyReset++
            }
        }
    }

    LaunchedEffect(Unit) { startRun() }

    // Read so a moved scene recomposes this stage.
    @Suppress("UNUSED_VARIABLE")
    val moved = tick

    Text(
        stringResource(
            when (stage) {
                FirstFourStage.BUSTED_CALL -> R.string.first_four_intro_busted
                FirstFourStage.NO_REPLY -> R.string.first_four_intro_no_reply
                else -> R.string.first_four_intro_walkthrough
            }
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = Brand.textSecondary
    )

    val needed = FirstFour.cleanRunsToPass(stage) ?: 0
    val runs = minOf(progress.cleanRuns(stage), needed)
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (stage == FirstFourStage.NO_REPLY) {
                stringResource(
                    R.string.first_four_scene_counter,
                    if (FirstFour.noReplyUsesSceneB(progress.cleanRuns(stage))) "B" else "A",
                    runs, needed
                )
            } else {
                stringResource(R.string.first_four_runs_counter, runs, needed)
            },
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = Brand.textSecondary,
            modifier = Modifier.weight(1f)
        )
        if (progress.hasPassed(stage)) {
            Text(stringResource(R.string.first_four_passed), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = Brand.tealBright)
        }
    }

    // The transcript.
    Column(
        modifier = Modifier.fillMaxWidth().brandCard().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (log.isEmpty()) {
            Text(stringResource(R.string.first_four_listen), color = Brand.textSecondary)
        }
        for ((who, line) in log) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    who,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (who == youLabel) Brand.tealBright else Brand.textSecondary,
                    modifier = Modifier.width(72.dp)
                )
                Text(line, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = Brand.textPrimary)
            }
        }
    }

    val finished = result
    val beat = scene.current
    // Desktop: where the step has no field or key of its own, the root takes
    // the keyboard back so Enter (and Escape) still reach the screen.
    val beatKind = beat?.kind
    LaunchedEffect(tick, finished) {
        if (finished != null || beatKind == FirstFourBeat.Kind.HEAR || beatKind == FirstFourBeat.Kind.SILENCE) focusRoot()
    }
    if (finished != null) {
        val (clean, passedNow) = finished
        Column(
            modifier = Modifier.fillMaxWidth().brandCard().padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (clean) {
                Text(
                    stringResource(if (passedNow) R.string.first_four_stage_passed else R.string.first_four_clean_run),
                    fontWeight = FontWeight.SemiBold,
                    color = Brand.tealBright
                )
            } else {
                Text(
                    pluralStringResource(R.plurals.first_four_slips, scene.mistakes, scene.mistakes),
                    fontWeight = FontWeight.SemiBold,
                    color = Brand.textPrimary,
                    textAlign = TextAlign.Center
                )
            }
            if (stage == FirstFourStage.WALKTHROUGH && progress.hasPassed(FirstFourStage.WALKTHROUGH)) FirstFourFinale()
            if (progress.hasPassed(stage)) NextButton(progress, onNext)
            OnEnter { if (progress.hasPassed(stage)) onNext() else startRun() }
            OutlinedButton(onClick = { startRun() }) {
                Text(
                    stringResource(if (progress.hasPassed(stage)) R.string.first_four_run_again else R.string.first_four_go_again),
                    color = Brand.teal
                )
            }
        }
    } else if (beat != null) {
        Column(
            modifier = Modifier.fillMaxWidth().brandCard().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(cueText(beat, activator), style = MaterialTheme.typography.bodyMedium, color = Brand.textPrimary)
            when (beat.kind) {
                FirstFourBeat.Kind.HEAR -> Row(verticalAlignment = Alignment.CenterVertically) {
                    OnEnter(enabled = heard) { respond(FirstFourResponse.Continued) }
                    OutlinedButton(onClick = { play(beat.text) }) { Text(stringResource(R.string.first_four_replay), color = Brand.teal) }
                    Spacer(Modifier.weight(1f))
                    ContinueButton(enabled = heard) { respond(FirstFourResponse.Continued) }
                }
                FirstFourBeat.Kind.SILENCE -> Row {
                    OnEnter { respond(FirstFourResponse.Continued) }
                    Spacer(Modifier.weight(1f))
                    ContinueButton(enabled = true) { respond(FirstFourResponse.Continued) }
                }
                FirstFourBeat.Kind.COPY -> {
                    OutlinedButton(onClick = { play(beat.text) }) { Text(stringResource(R.string.first_four_replay), color = Brand.teal) }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val typeFocus = remember { FocusRequester() }
                        LaunchedEffect(Unit) { runCatching { typeFocus.requestFocus() } }
                        OutlinedTextField(
                            value = typed,
                            onValueChange = { typed = it },
                            singleLine = true,
                            placeholder = { Text(stringResource(R.string.first_four_their_state)) },
                            textStyle = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { respond(FirstFourResponse.Copied(typed)) }),
                            // Desktop: the answer box has the keyboard, and Enter checks.
                            modifier = Modifier.weight(1f).focusRequester(typeFocus).enterSubmits {
                                if (typed.isNotBlank()) respond(FirstFourResponse.Copied(typed))
                            }
                        )
                        OutlinedButton(onClick = { respond(FirstFourResponse.Copied(typed)) }, enabled = typed.isNotBlank()) {
                            Text(stringResource(R.string.first_four_check), color = Brand.teal)
                        }
                    }
                }
                FirstFourBeat.Kind.SEND -> {
                    Text(stringResource(R.string.first_four_send_label), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Brand.textSecondary)
                    Text(beat.text, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 26.sp, color = Brand.textPrimary)
                    FirstFourKeyPanel(expected = beat.text, resetToken = keyReset) { respond(FirstFourResponse.Sent(it)) }
                }
                FirstFourBeat.Kind.WAIT -> {
                    OnEnter { respond(FirstFourResponse.Waited) }
                    Button(
                        onClick = { respond(FirstFourResponse.Waited) },
                        colors = ButtonDefaults.buttonColors(containerColor = Brand.teal, contentColor = Brand.navy),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) { Text(stringResource(R.string.first_four_wait), fontWeight = FontWeight.Bold) }
                    FirstFourKeyPanel(expected = call, resetToken = keyReset) { respond(FirstFourResponse.Sent(it)) }
                }
            }
        }
    }

    feedback?.let {
        Text(it, fontWeight = FontWeight.SemiBold, color = Brand.warning, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun ContinueButton(enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = Brand.teal, contentColor = Brand.navy)
    ) { Text(stringResource(R.string.first_four_continue), fontWeight = FontWeight.Bold) }
}

@Composable
private fun cueText(beat: FirstFourBeat, activator: FirstFourStation): String = when (beat.cue) {
    FirstFourBeat.Cue.CQ -> stringResource(R.string.first_four_cue_cq, activator.call)
    FirstFourBeat.Cue.CALL_THEM -> stringResource(R.string.first_four_cue_call_them)
    FirstFourBeat.Cue.PARTIAL ->
        if (beat.text == "?") stringResource(R.string.first_four_cue_partial_bare)
        else stringResource(R.string.first_four_cue_partial, beat.text)
    FirstFourBeat.Cue.RESEND -> stringResource(R.string.first_four_cue_resend)
    FirstFourBeat.Cue.ACK -> stringResource(R.string.first_four_cue_ack)
    FirstFourBeat.Cue.NO_REPLY -> stringResource(R.string.first_four_cue_no_reply)
    FirstFourBeat.Cue.CALL_AGAIN -> stringResource(R.string.first_four_cue_call_again)
    FirstFourBeat.Cue.OTHER_STATION -> stringResource(R.string.first_four_cue_other_station)
    FirstFourBeat.Cue.STAY_QUIET -> stringResource(R.string.first_four_cue_stay_quiet)
    FirstFourBeat.Cue.QRZ -> stringResource(R.string.first_four_cue_qrz)
    FirstFourBeat.Cue.THEIR_EXCHANGE -> stringResource(R.string.first_four_cue_their_exchange)
    FirstFourBeat.Cue.YOUR_EXCHANGE -> stringResource(R.string.first_four_cue_your_exchange)
    FirstFourBeat.Cue.SIGN_OFF -> stringResource(R.string.first_four_cue_sign_off)
}

// MARK: The key

/**
 * The on-screen key (straight or paddles, per Settings), the keyboard
 * (Space, `[` `]`, through [OnScreenKeySwitch]) and any USB Vail / MIDI key,
 * decoded by the same [SendingKeyer] every keying mode uses. Submits by itself once the decoded text is as long as [expected] and
 * the key is idle, as Sending Practice does; Submit and Clear are there too.
 * [resetToken] clears it from outside.
 */
@Composable
private fun FirstFourKeyPanel(expected: String, resetToken: Int, onSubmit: (String) -> Unit) {
    val keyer = remember { SendingKeyer(wpm = Settings.characterWpm, toneHz = Settings.sidetoneHz) }
    val midi = remember { HardwareKey() }
    AdapterConfigSync(midi)
    val scope = rememberCoroutineScope()
    var keyPressed by remember { mutableStateOf(false) }
    var midiDevice by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        keyer.scope = scope
        keyer.start()
        midi.start(onKey = { down -> keyer.touchKey(down) }, onConnected = { midiDevice = it })
        onDispose {
            midi.stop()
            keyer.stop()
        }
    }
    LaunchedEffect(resetToken) { keyer.clear() }

    fun submit() {
        val answer = keyer.submit()
        if (answer.isNotEmpty()) onSubmit(answer)
    }

    // Auto-submit once the answer is as long as expected and the key is idle.
    LaunchedEffect(keyer.decodedText, keyer.isKeying) {
        if (keyer.isKeying) return@LaunchedEffect
        val sent = FirstFour.compact(keyer.decodedText)
        if (sent.isNotEmpty() && sent.length >= FirstFour.compact(expected).length) submit()
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.first_four_you_sent), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Brand.textSecondary)
            Spacer(Modifier.weight(1f))
            midiDevice?.let { Text("🎹 $it", fontSize = 12.sp, color = Brand.teal, maxLines = 1) }
        }
        Text(
            keyer.decodedText.ifEmpty { "—" },
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            fontSize = 26.sp,
            color = if (keyer.decodedText.isEmpty()) Brand.textSecondary else Brand.textPrimary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp)
        )
        // Straight key or paddles, as chosen in Settings (#233).
        OnScreenKeySwitch(
            onPaddleKey = { down, ms -> keyer.touchKey(down, ms) },
            modifier = Modifier.fillMaxWidth().height(100.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .clip(RoundedCornerShape(Brand.cornerRadius))
                    .background(if (keyPressed) Brand.teal else Brand.navyRaised)
                    .border(
                        width = if (keyPressed) 2.dp else 1.dp,
                        color = if (keyPressed) Brand.tealBright else Brand.hairline,
                        shape = RoundedCornerShape(Brand.cornerRadius)
                    )
                    .pointerInput(Unit) {
                        detectTapGestures(onPress = {
                            keyPressed = true
                            keyer.touchKey(true)
                            try { tryAwaitRelease() } finally {
                                keyPressed = false
                                keyer.touchKey(false)
                            }
                        })
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(R.string.common_hold_to_key),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (keyPressed) Brand.navy else Brand.textSecondary
                )
            }
        }
        Text(
            DesktopCopy.KEYBOARD_KEY_HINT,
            style = MaterialTheme.typography.labelSmall,
            color = Brand.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { keyer.clear() }, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.first_four_clear), color = Brand.textSecondary)
            }
            Button(
                onClick = { submit() },
                enabled = keyer.decodedText.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Brand.teal, contentColor = Brand.navy),
                modifier = Modifier.weight(1f)
            ) { Text(stringResource(R.string.first_four_submit), fontWeight = FontWeight.Bold) }
        }
    }
}
