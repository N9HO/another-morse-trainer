package app.anothermorsetrainer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.anothermorsetrainer.morsekit.CallsignFormat
import app.anothermorsetrainer.morsekit.Drill
import app.anothermorsetrainer.morsekit.RapidFireContent
import app.anothermorsetrainer.morsekit.RapidFirePace
import app.anothermorsetrainer.morsekit.RapidFireQuiz
import app.anothermorsetrainer.morsekit.RapidFireResponse
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val OK = Color(0xFF2E7D32)
private val ERR = Color(0xFFC62828)

private enum class RfPhase { SETUP, RUNNING, SUMMARY }

/** One streamed item and (for type/head-copy) the learner's copy of it. */
private data class RfResult(val sent: String, val typed: String?, val correct: Boolean?)

/**
 * The "Rapid Fire" mode: a stream of call signs / words / number groups / state
 * abbreviations sent back to back at a chosen pace. Type each one as it lands,
 * copy it in your head then type, or just listen and review the full list at the
 * end.
 *
 * Ported from MorseKit/RapidFire.swift and the iOS ContentView Rapid Fire flow.
 * The AppModel-driven stream loop becomes a step-counter state machine here.
 */
@Composable
fun RapidFireScreen(onBack: () -> Unit, onSwitchMode: (TrainingMode) -> Unit = {}) {
    val context = LocalContext.current
    val player = remember { MorsePlayer() }
    val haptics = remember { Haptics(context) }

    var phase by rememberSaveable { mutableStateOf(RfPhase.SETUP) }

    // Setup selections persist across launches in Settings, field for field
    // with the iOS RapidFireSettings — so a reclaimed process and the next
    // launch alike come back to the same setup.
    val content = Settings.rapidFireContent
    val response = Settings.rapidFireResponse
    val pace = Settings.rapidFirePace
    val wordMin = Settings.rapidFireWordMin
    val wordMax = Settings.rapidFireWordMax
    val numberCount = Settings.rapidFireNumberCount
    val usOnly = Settings.rapidFireUsOnly
    val formats = Settings.rapidFireFormats
    val sections = Settings.rapidFireSections
    val serialCut = Settings.rapidFireSerialCut

    // Run state.
    var quiz by remember { mutableStateOf<RapidFireQuiz?>(null) }
    var drill by remember { mutableStateOf<Drill?>(null) }
    var step by remember { mutableIntStateOf(0) }
    var typed by remember { mutableStateOf("") }
    var toneEndedStep by remember { mutableIntStateOf(-1) }
    var revealBox by remember { mutableStateOf(false) }
    val transcript = remember { mutableStateListOf<RfResult>() }
    var startedAtMs by rememberSaveable { mutableLongStateOf(0L) }
    // The run's score, mirrored out of the transcript on every item so it
    // rides the saved-instance-state bundle. The quiz and the transcript die
    // with the process; the phase, the start and these do not.
    var runAttempts by rememberSaveable { mutableIntStateOf(0) }
    var runCorrect by rememberSaveable { mutableIntStateOf(0) }
    var lastSeenMs by rememberSaveable { mutableLongStateOf(0L) }

    // "Key each one": the straight key + decoder, live only during a keyed run.
    val keyer = remember { SendingKeyer(wpm = Settings.characterWpm, toneHz = Settings.sidetoneHz) }
    val midi = remember { HardwareKey(context) }
    // Push a keyer mode or speed picked in the Settings sheet to the adapter
    // now, rather than at the next wake — i.e. after leaving the module (#46).
    AdapterConfigSync(midi)
    val scope = rememberCoroutineScope()
    var keyPressed by remember { mutableStateOf(false) }
    var midiDevice by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) { onDispose { player.release() } }

    DisposableEffect(phase, response) {
        if (phase == RfPhase.RUNNING && response == RapidFireResponse.KEY) {
            keyer.scope = scope
            keyer.start()
            // A hardware key (Vail Adapter / BLE-MIDI) drives the same decoder.
            midi.start(
                onKey = { down -> keyer.touchKey(down) },
                onConnected = { name -> midiDevice = name }
            )
        }
        onDispose { midi.stop(); keyer.stop() }
    }

    fun buildConfig() = RapidFireQuiz.Config(
        content = content,
        // In enum order so the same selection always yields the same list.
        callsignFormats = CallsignFormat.entries.filter { it in formats },
        callsignUSOnly = usOnly,
        wordMinLength = wordMin,
        wordMaxLength = wordMax,
        numberCount = numberCount,
        statesIncludeSections = sections,
        serialCutNumbers = serialCut
    )

    fun startRun() {
        val q = RapidFireQuiz(buildConfig())
        quiz = q
        transcript.clear()
        drill = q.nextDrill()
        typed = ""
        toneEndedStep = -1
        revealBox = response == RapidFireResponse.TYPE
        startedAtMs = System.currentTimeMillis()
        runAttempts = 0
        runCorrect = 0
        lastSeenMs = startedAtMs
        phase = RfPhase.RUNNING
        step = 1
    }

    /** Stop the stream and record the run; the caller decides where to land. */
    fun recordRun() {
        player.stop()
        val attempts = transcript.count { it.correct != null }
        val correct = transcript.count { it.correct == true }
        val secs = ((System.currentTimeMillis() - startedAtMs) / 1000L).toInt()
        if (response != RapidFireResponse.REVIEW && attempts > 0) {
            Stats.record(
                mode = "Rapid Fire", attempts = attempts, correct = correct,
                bestTtrMs = null, durationSeconds = secs,
                characterWpm = Settings.characterWpm.roundToInt()
            )
        }
    }

    fun finishRun() {
        recordRun()
        phase = RfPhase.SUMMARY
    }

    /** The mode switcher (iOS #42): close a running stream out as Done would, then go. */
    fun switchTo(mode: TrainingMode) {
        if (phase == RfPhase.RUNNING) recordRun() else player.stop()
        onSwitchMode(mode)
    }

    // A run the system reclaimed mid-way cannot resume — the quiz and the
    // transcript died with the process — but its score need not die with it.
    // Close it out from the saved score, exactly as finishRun would have, and
    // land on setup. A summary whose transcript is gone goes the same way;
    // that run was recorded when it finished.
    LaunchedEffect(Unit) {
        if (quiz != null) return@LaunchedEffect
        if (phase == RfPhase.RUNNING && response != RapidFireResponse.REVIEW) {
            Stats.record(
                mode = "Rapid Fire", attempts = runAttempts, correct = runCorrect,
                bestTtrMs = null,
                durationSeconds = ((lastSeenMs - startedAtMs) / 1000L).toInt().coerceAtLeast(0),
                characterWpm = Settings.characterWpm.roundToInt()
            )
        }
        if (phase != RfPhase.SETUP) phase = RfPhase.SETUP
    }

    // Grade the current item (type/head-copy/keyed) or just log it (review),
    // then stream the next.
    fun advance() {
        val q = quiz ?: return
        val d = drill ?: return
        if (response == RapidFireResponse.REVIEW) {
            transcript.add(RfResult(d.correct, null, null))
        } else {
            // A keyed run's copy is whatever the decoder heard, not the text box.
            if (response == RapidFireResponse.KEY) typed = keyer.submit().uppercase()
            val ok = q.record(typed, 0.0).correct
            if (Settings.hapticsEnabled) { if (ok) haptics.success() else haptics.error() }
            transcript.add(RfResult(d.correct, typed, ok))
        }
        runAttempts = transcript.count { it.correct != null }
        runCorrect = transcript.count { it.correct == true }
        lastSeenMs = System.currentTimeMillis()
        drill = q.nextDrill()
        typed = ""
        keyer.clear()
        revealBox = response == RapidFireResponse.TYPE
        step += 1
    }

    // Play each streamed item.
    LaunchedEffect(step) {
        if (phase != RfPhase.RUNNING || step <= 0) return@LaunchedEffect
        val d = drill ?: return@LaunchedEffect
        revealBox = response == RapidFireResponse.TYPE
        player.play(d.playable, Settings.sidetoneHz, Settings.timing()) { toneEndedStep = step }
    }

    // After a tone ends, wait the pace gap, then advance (auto-stream). A keyed
    // run opens the key instead — keying, not the pace clock, moves it on.
    LaunchedEffect(toneEndedStep) {
        if (toneEndedStep <= 0 || phase != RfPhase.RUNNING || toneEndedStep != step) return@LaunchedEffect
        if (response == RapidFireResponse.HEAD_COPY || response == RapidFireResponse.KEY) revealBox = true
        if (response == RapidFireResponse.KEY) return@LaunchedEffect
        delay((pace.seconds * 1000).toLong())
        if (toneEndedStep == step && phase == RfPhase.RUNNING) advance()
    }

    // Keyed runs auto-submit once the decoded copy reaches the item's length
    // and the key has gone idle (the Sending Practice rhythm).
    LaunchedEffect(keyer.decodedText, keyer.isKeying, step, phase) {
        if (phase != RfPhase.RUNNING || response != RapidFireResponse.KEY || keyer.isKeying) return@LaunchedEffect
        if (toneEndedStep != step) return@LaunchedEffect
        val sent = keyer.decodedText.trim()
        val d = drill ?: return@LaunchedEffect
        if (sent.isNotEmpty() && sent.length >= d.correct.length) advance()
    }

    // Mid-session Settings, drawn over the run so the stream lives on.
    var showSettings by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
    when (phase) {
        RfPhase.SETUP -> {
            BackHandler { onBack() }
            RapidFireSetup(
                content = content, onContent = { Settings.updateRapidFireContent(it) },
                response = response, onResponse = { Settings.updateRapidFireResponse(it) },
                pace = pace, onPace = { Settings.updateRapidFirePace(it) },
                wordMin = wordMin, wordMax = wordMax,
                onWordMin = { Settings.updateRapidFireWordMin(it) },
                onWordMax = { Settings.updateRapidFireWordMax(it) },
                numberCount = numberCount, onNumberCount = { Settings.updateRapidFireNumberCount(it) },
                usOnly = usOnly, onUsOnly = { Settings.updateRapidFireUsOnly(it) },
                formats = formats, onToggleFormat = { Settings.toggleRapidFireFormat(it) },
                sections = sections, onSections = { Settings.updateRapidFireSections(it) },
                serialCut = serialCut, onSerialCut = { Settings.updateRapidFireSerialCut(it) },
                onStart = { startRun() },
                onBack = onBack,
                onSwitchMode = ::switchTo
            )
        }

        RfPhase.RUNNING -> {
            BackHandler { finishRun() }
            RapidFireRun(
                summary = quiz?.summary ?: "",
                count = transcript.size,
                onSettings = { showSettings = true },
                onSwitchMode = ::switchTo,
                response = response,
                revealBox = revealBox,
                typed = typed,
                onTyped = { typed = it },
                decoded = keyer.decodedText,
                keyPressed = keyPressed,
                onKey = { down ->
                    keyPressed = down
                    keyer.touchKey(down)
                },
                onClearKey = { keyer.clear() },
                midiDevice = midiDevice,
                onNext = {
                    // Submit the current copy now and stream the next.
                    if (response != RapidFireResponse.REVIEW) advance() else { /* review: auto only */ }
                },
                onDone = { finishRun() }
            )
        }

        RfPhase.SUMMARY -> {
            BackHandler { onBack() }
            RapidFireSummary(
                results = transcript.toList(),
                response = response,
                onAgain = { phase = RfPhase.SETUP },
                onBack = onBack
            )
        }
    }

    if (showSettings) {
        SessionSettingsOverlay(scope = SettingsMode.RAPID_FIRE, onClose = { showSettings = false })
    }
    }
}

@Composable
private fun RapidFireSetup(
    content: RapidFireContent, onContent: (RapidFireContent) -> Unit,
    response: RapidFireResponse, onResponse: (RapidFireResponse) -> Unit,
    pace: RapidFirePace, onPace: (RapidFirePace) -> Unit,
    wordMin: Int, wordMax: Int, onWordMin: (Int) -> Unit, onWordMax: (Int) -> Unit,
    numberCount: Int, onNumberCount: (Int) -> Unit,
    usOnly: Boolean, onUsOnly: (Boolean) -> Unit,
    formats: Set<CallsignFormat>, onToggleFormat: (CallsignFormat) -> Unit,
    sections: Boolean, onSections: (Boolean) -> Unit,
    serialCut: Boolean, onSerialCut: (Boolean) -> Unit,
    onStart: () -> Unit, onBack: () -> Unit,
    onSwitchMode: (TrainingMode) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.common_back), color = Brand.teal) }
            Text(stringResource(R.string.mode_rapid_fire), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            SwitchModeButton(TrainingMode.RAPID_FIRE, onSwitchMode)
        }
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SectionLabel(stringResource(R.string.rapidfire_what_to_copy))
            Pills(RapidFireContent.entries.map { it to it.label }, content, onContent)
            Text(content.blurb, style = MaterialTheme.typography.bodySmall, color = Brand.textSecondary)
            when (content) {
                // The 71 ARRL/RAC sections (ContestData.arrlSections) join the
                // state pool: EPA, STX, SDG beside OH (#173).
                RapidFireContent.STATES ->
                    ToggleRow(stringResource(R.string.rapidfire_include_sections), sections, onSections)
                // Sent as the pileup sends them (T for 0, N for 9 …); "TTA",
                // "001" and "1" all copy 001 (#173).
                RapidFireContent.SERIALS ->
                    ToggleRow(stringResource(R.string.rapidfire_cut_numbers), serialCut, onSerialCut)
                RapidFireContent.WORDS -> {
                    Stepper(stringResource(R.string.rapidfire_min_length), wordMin, onWordMin)
                    Stepper(stringResource(R.string.rapidfire_max_length), wordMax, onWordMax)
                }
                RapidFireContent.NUMBERS -> Stepper(stringResource(R.string.rapidfire_digits_per_group), numberCount, onNumberCount)
                RapidFireContent.CALLSIGNS, RapidFireContent.MIXED -> {
                    if (content == RapidFireContent.MIXED) {
                        ToggleRow(stringResource(R.string.rapidfire_include_sections), sections, onSections)
                    }
                    ToggleRow(stringResource(R.string.common_us_calls_only), usOnly, onUsOnly)
                    // Call-sign shapes (iOS rapidFireFormatChip): one chip per
                    // format; at least one always stays on.
                    SectionLabel(stringResource(R.string.rapidfire_callsign_shapes))
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CallsignFormat.entries.forEach { fmt ->
                            val sel = fmt in formats
                            Box(
                                modifier = Modifier
                                    .background(if (sel) Brand.teal else Brand.navyRaised, RoundedCornerShape(8.dp))
                                    .clickable { onToggleFormat(fmt) }
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    fmt.label,
                                    color = if (sel) Brand.navy else Brand.textSecondary,
                                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }
                else -> {}
            }

            SectionLabel(stringResource(R.string.rapidfire_how_to_copy))
            Column(modifier = Modifier.fillMaxWidth().brandCard()) {
                RapidFireResponse.entries.forEach { r ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onResponse(r) }.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(r.label, color = if (r == response) Brand.teal else Brand.textPrimary, fontWeight = FontWeight.SemiBold)
                            Text(r.blurb, style = MaterialTheme.typography.bodySmall, color = Brand.textSecondary)
                        }
                        if (r == response) Text("✓", color = Brand.teal, fontWeight = FontWeight.Bold)
                    }
                }
            }

            SectionLabel(stringResource(R.string.rapidfire_pace))
            Pills(RapidFirePace.entries.map { it to it.label }, pace, onPace)

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(Brand.cornerRadius)
            ) { Text(stringResource(R.string.common_start), fontWeight = FontWeight.Bold, fontSize = 17.sp) }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun RapidFireRun(
    summary: String,
    count: Int,
    response: RapidFireResponse,
    revealBox: Boolean,
    typed: String,
    onTyped: (String) -> Unit,
    decoded: String,
    keyPressed: Boolean,
    onKey: (Boolean) -> Unit,
    onClearKey: () -> Unit,
    midiDevice: String?,
    onNext: () -> Unit,
    onSettings: () -> Unit,
    onSwitchMode: (TrainingMode) -> Unit,
    onDone: () -> Unit
) {
    // Same IME occlusion as the QRQ drill (issue #44): edge-to-edge, so the
    // window does not resize and the Next/Done row ends up behind the keyboard.
    // The middle section carries weight(1f), so shrinking the column takes the
    // space out of the drill area and leaves the buttons reachable.
    Column(modifier = Modifier.fillMaxSize().imePadding().padding(horizontal = 24.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(summary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.rapidfire_count_sent, count), style = MaterialTheme.typography.bodySmall, color = Brand.textSecondary)
                SessionSettingsButton(onOpen = onSettings)
                SwitchModeButton(TrainingMode.RAPID_FIRE, onSwitchMode)
            }
        }
        if (response == RapidFireResponse.KEY) {
            if (midiDevice != null) {
                Text("🎹 $midiDevice", style = MaterialTheme.typography.labelSmall, color = Brand.teal)
            }
            // As in Sending Practice: a BLE-MIDI key is invisible to
            // MidiManager until something scans for it and opens it, and this
            // is the screen it would be used on. Not gated on midiDevice —
            // the button owns the BLE link, so hiding it once a key attached
            // would close the link and bounce the key straight back off.
            BluetoothKeyButton()
        }

        Column(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (response != RapidFireResponse.KEY) {
                Box(
                    modifier = Modifier.size(120.dp).background(Brand.navyRaised, RoundedCornerShape(60.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("📡", fontSize = 44.sp)
                }
                Spacer(Modifier.height(20.dp))
            }

            when (response) {
                RapidFireResponse.REVIEW -> Text(stringResource(R.string.rapidfire_prompt_review), color = Brand.textSecondary, textAlign = TextAlign.Center)
                RapidFireResponse.HEAD_COPY -> if (!revealBox) {
                    Text(stringResource(R.string.common_copy_it_in_your_head), color = Brand.textSecondary, textAlign = TextAlign.Center)
                }
                RapidFireResponse.KEY -> if (!revealBox) {
                    Text(stringResource(R.string.rapidfire_prompt_key), color = Brand.textSecondary, textAlign = TextAlign.Center)
                }
                else -> {}
            }

            if (response == RapidFireResponse.KEY) {
                Spacer(Modifier.height(12.dp))
                // The decoded readout, live as you key.
                Column(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(Brand.cornerRadius)).brandCard()
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(stringResource(R.string.common_you_sent), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Brand.textSecondary)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = decoded.ifEmpty { "—" },
                        fontSize = 30.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        color = if (decoded.isEmpty()) Brand.textSecondary else Brand.textPrimary,
                        maxLines = 1
                    )
                }
                Spacer(Modifier.height(14.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp)
                        .clip(RoundedCornerShape(Brand.cornerRadius))
                        .background(if (keyPressed) Brand.teal else Brand.navyRaised)
                        .border(
                            width = if (keyPressed) 2.dp else 1.dp,
                            color = if (keyPressed) Brand.tealBright else Brand.hairline,
                            shape = RoundedCornerShape(Brand.cornerRadius)
                        )
                        .pointerInput(revealBox) {
                            if (!revealBox) return@pointerInput
                            detectTapGestures(
                                onPress = {
                                    onKey(true)
                                    try {
                                        tryAwaitRelease()
                                    } finally {
                                        onKey(false)
                                    }
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("⠿", fontSize = 24.sp, color = if (keyPressed) Brand.navy else Brand.teal)
                        Text(
                            stringResource(R.string.common_hold_to_key),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (keyPressed) Brand.navy else Brand.textSecondary
                        )
                    }
                }
            } else if (response != RapidFireResponse.REVIEW && revealBox) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = typed,
                    onValueChange = onTyped,
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.rapidfire_type_what_you_copy), color = Brand.textSecondary) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onNext() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Brand.teal,
                        unfocusedBorderColor = Brand.hairline,
                        focusedTextColor = Brand.textPrimary,
                        unfocusedTextColor = Brand.textPrimary,
                        cursorColor = Brand.teal
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (response == RapidFireResponse.KEY) {
                OutlinedButton(onClick = onClearKey, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.common_clear)) }
            }
            if (response != RapidFireResponse.REVIEW) {
                OutlinedButton(onClick = onNext, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.rapidfire_next)) }
            }
            Button(onClick = onDone, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.common_done)) }
        }
    }
}

@Composable
private fun RapidFireSummary(
    results: List<RfResult>,
    response: RapidFireResponse,
    onAgain: () -> Unit,
    onBack: () -> Unit
) {
    val graded = results.count { it.correct != null }
    val correct = results.count { it.correct == true }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.common_back), color = Brand.teal) }
            Text(stringResource(R.string.rapidfire_results_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        if (response != RapidFireResponse.REVIEW && graded > 0) {
            Text(
                stringResource(R.string.rapidfire_score, correct, graded, (100.0 * correct / graded).roundToInt()),
                style = MaterialTheme.typography.titleMedium,
                color = Brand.teal,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
        } else {
            Text(stringResource(R.string.rapidfire_count_sent, results.size), style = MaterialTheme.typography.titleMedium, color = Brand.teal, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 12.dp)
        ) {
            items(results.size, key = { it }) { i ->
                val r = results[i]
                Row(
                    modifier = Modifier.fillMaxWidth().brandCard().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(r.sent, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Brand.textPrimary, modifier = Modifier.weight(1f))
                    r.typed?.let {
                        Text(stringResource(R.string.rapidfire_you_typed, it.ifEmpty { "—" }), fontFamily = FontFamily.Monospace, color = Brand.textSecondary)
                    }
                    r.correct?.let {
                        Text(if (it) "✓" else "✗", color = if (it) OK else ERR, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onAgain, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.common_practice_again)) }
            Button(onClick = onBack, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.common_return_home)) }
        }
    }
}

// ---- Small reusable bits ----

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Brand.textSecondary, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun <T> Pills(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (value, label) ->
            val sel = value == selected
            Box(
                modifier = Modifier
                    .background(if (sel) Brand.teal else Brand.navyRaised, RoundedCornerShape(8.dp))
                    .clickable { onSelect(value) }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(label, color = if (sel) Brand.navy else Brand.textSecondary, fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun Stepper(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().brandCard().padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Brand.textPrimary, fontWeight = FontWeight.Medium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onChange(value - 1) }) { Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.rapidfire_decrease), tint = Brand.teal) }
            Text("$value", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Brand.textPrimary, modifier = Modifier.padding(horizontal = 6.dp))
            IconButton(onClick = { onChange(value + 1) }) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.rapidfire_increase), tint = Brand.teal) }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().brandCard().padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Brand.textPrimary, fontWeight = FontWeight.Medium)
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedThumbColor = Brand.navy, checkedTrackColor = Brand.teal))
    }
}
