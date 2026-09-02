package app.anothermorsetrainer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.anothermorsetrainer.morsekit.BustBehavior
import app.anothermorsetrainer.morsekit.CallsignFormat
import app.anothermorsetrainer.morsekit.MorseItem
import app.anothermorsetrainer.morsekit.MorseTiming
import app.anothermorsetrainer.morsekit.MissedCallerFeedback
import app.anothermorsetrainer.morsekit.PileupEngine
import app.anothermorsetrainer.morsekit.QSOContestMode
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private enum class PuPhase { SETUP, RUNNING, SUMMARY }

/**
 * Map an engine [PileupEngine.Voice] to a renderable [MorsePlayer.PileupVoice].
 * The pileup sits around your sidetone pitch — tune the radio, tune the pileup.
 */
private fun PileupEngine.Voice.toMix() = MorsePlayer.PileupVoice(
    text = text,
    frequency = Settings.sidetoneHz + toneOffset,
    timing = MorseTiming(wpm),
    gain = volume,
    startDelay = delay,
    qsbRate = if (qsb) 0.3 else null
)

/** What you'd key to start a run in this flavour (self-keying uses it verbatim). */
private fun cqText(mode: QSOContestMode, call: String): String = when (mode) {
    QSOContestMode.SingleCaller -> "CQ CQ DE $call K"
    QSOContestMode.Pota -> "CQ POTA $call"
    QSOContestMode.FieldDay -> "CQ FD $call"
    else -> "CQ TEST $call"
}

/**
 * Work a CW pileup: pick the exchange flavour and realism, call CQ, hear
 * several stations answer at once, copy one call and send it, then copy that
 * station's exchange and log it. Drives the fully-ported [PileupEngine];
 * audio is the multi-voice mix from [MorsePlayer], optionally with your own
 * side keyed in Morse. Mirrors the iOS QSO Simulator surface.
 */
@Composable
fun PileupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val player = remember { MorsePlayer() }
    val haptics = remember { Haptics(context) }

    var phase by rememberSaveable { mutableStateOf(PuPhase.SETUP) }
    var engine by remember { mutableStateOf<PileupEngine?>(null) }
    var input by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    var startedAtMs by rememberSaveable { mutableLongStateOf(0L) }
    var endedAtMs by remember { mutableLongStateOf(0L) }
    // Engine state isn't Compose-observable; the clock also rides this tick.
    var rev by remember { mutableIntStateOf(0) }
    // The run's scoreboard, mirrored out of the engine on every action and
    // clock tick so it rides the saved-instance-state bundle. The engine and
    // its callers die with the process; the phase, the start and these do not.
    var runQsos by rememberSaveable { mutableIntStateOf(0) }
    var runBusts by rememberSaveable { mutableIntStateOf(0) }
    var lastSeenMs by rememberSaveable { mutableLongStateOf(0L) }

    DisposableEffect(Unit) { onDispose { player.release() } }

    fun elapsedSeconds(): Int {
        if (startedAtMs == 0L) return 0
        val end = if (endedAtMs > 0L) endedAtMs else System.currentTimeMillis()
        return ((end - startedAtMs) / 1000L).toInt()
    }

    /** Mirror the engine's score into the saveable scoreboard (see runQsos). */
    fun syncRun() {
        val e = engine ?: return
        runQsos = e.qsoCount
        runBusts = e.bustCount
        lastSeenMs = System.currentTimeMillis()
    }

    // A run the system reclaimed mid-way cannot resume — the engine and its
    // callers died with the process — but its score need not die with it.
    // Close it out from the saved scoreboard, exactly as endRun would have,
    // and land on setup. A summary whose engine is gone goes the same way;
    // that run was recorded when it ended.
    LaunchedEffect(Unit) {
        if (engine != null) return@LaunchedEffect
        if (phase == PuPhase.RUNNING) {
            Stats.record(
                mode = "Pileup",
                attempts = runQsos + runBusts,
                correct = runQsos,
                bestTtrMs = null,
                durationSeconds = ((lastSeenMs - startedAtMs) / 1000L).toInt().coerceAtLeast(0)
            )
        }
        if (phase != PuPhase.SETUP) phase = PuPhase.SETUP
    }

    // Record the run so pileup practice counts toward stats and the streak: a
    // pileup answer is a whole worked exchange — clean contacts are correct,
    // busts are misses (same accounting as Contest). Mixed caller speeds, so no
    // single WPM is recorded (keeps it out of the speed-band table).
    fun endRun() {
        val e = engine ?: return
        player.stop()
        endedAtMs = System.currentTimeMillis()
        Stats.record(
            mode = "Pileup",
            attempts = e.qsoCount + e.bustCount,
            correct = e.qsoCount,
            bestTtrMs = null,
            durationSeconds = elapsedSeconds()
        )
        phase = PuPhase.SUMMARY
    }

    fun startRun() {
        engine = PileupEngine(PileupSettings.config())
        input = ""
        reveal = false
        startedAtMs = System.currentTimeMillis()
        endedAtMs = 0L
        runQsos = 0
        runBusts = 0
        lastSeenMs = startedAtMs
        rev++
        phase = PuPhase.RUNNING
    }

    /** Key my side first (when enabled), then hand the audio back to the pileup. */
    fun playSelfThen(text: String?, then: () -> Unit) {
        val t = text?.takeIf { PileupSettings.keyMySide && it.isNotBlank() }
        if (t == null) { then(); return }
        player.play(MorseItem.Playable.Text(t), Settings.sidetoneHz, Settings.timing(), onFinished = then)
    }

    fun perform(action: PileupEngine.Action, selfText: String? = null) {
        val e = engine ?: return
        when (action) {
            is PileupEngine.Action.Play -> playSelfThen(selfText) {
                player.playPileup(action.voices.map { it.toMix() }, qrn = PileupSettings.qrn.level) {}
            }
            PileupEngine.Action.Silence -> {
                player.stop()
                playSelfThen(selfText) {}
            }
            is PileupEngine.Action.Logged -> {
                if (Settings.hapticsEnabled) haptics.success()
                playSelfThen("TU") {
                    // Back to the run without another button press (iOS #35): the
                    // rest of the pileup calls again, or a fresh CQ tops it up.
                    if (PileupSettings.autoRecall) {
                        val next = if (e.stations.isEmpty()) e.callCQ() else e.repeatRequest()
                        if (next is PileupEngine.Action.Play) {
                            player.playPileup(next.voices.map { it.toMix() }, qrn = PileupSettings.qrn.level) {}
                        }
                        rev++
                        syncRun()
                    }
                }
            }
        }
        rev++
        syncRun()
    }

    fun submit() {
        val e = engine ?: return
        if (input.isBlank()) return
        val raw = input.trim()
        val action = e.send(raw)
        // A typed repeat request ("W1?") while still hunting keeps the partial
        // call in the box — minus the "?" — so the user builds on it instead of
        // retyping (the iOS #49 fix). "Still hunting" includes the idle phase
        // (every caller gave up but you're re-CQing for the same call); anything
        // that moved the QSO on clears. Opt-outable in setup.
        val frag = PileupEngine.fragment(raw)
        val stillHunting = e.phase is PileupEngine.Phase.Pileup || e.phase is PileupEngine.Phase.Idle
        input = if (PileupSettings.keepPartialCall && raw.endsWith("?") && frag.isNotEmpty() && stillHunting) frag else ""
        perform(action, selfText = raw)
    }

    // The run clock: tick once a second so the rate and elapsed readouts move.
    LaunchedEffect(phase) {
        if (phase != PuPhase.RUNNING) return@LaunchedEffect
        while (true) {
            delay(1000)
            rev++
            syncRun()
        }
    }

    BackHandler {
        when (phase) {
            PuPhase.SETUP -> { player.stop(); onBack() }
            PuPhase.RUNNING -> endRun()
            PuPhase.SUMMARY -> onBack()
        }
    }

    // Mid-session Settings, drawn over the run so the pileup lives on.
    var showSettings by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
    when (phase) {
        PuPhase.SETUP -> PileupSetup(
            onStart = ::startRun,
            onBack = { player.stop(); onBack() }
        )
        PuPhase.RUNNING -> engine?.let { e ->
            // rev rides in as a plain parameter, NOT a key(): keying the subtree
            // on it rebuilt the run UI every clock tick, which yanked focus from
            // the Send box and closed the keyboard as soon as it opened (#24).
            PileupRun(
                engine = e,
                tick = rev,
                elapsedSeconds = elapsedSeconds(),
                input = input,
                onInput = { input = it.uppercase() },
                onSend = ::submit,
                reveal = reveal,
                onToggleReveal = { reveal = !reveal },
                onCQ = { perform(e.callCQ(), selfText = cqText(PileupSettings.mode, PileupSettings.effectiveCall)) },
                onRepeat = { perform(e.repeatRequest()) },
                onLog = { perform(e.logCurrent()) },
                onSettings = { showSettings = true },
                // The engine isn't Compose-observable, so clearing it has to
                // ride the same rev bump everything else does or the banner
                // would sit there until the next clock tick.
                onDismissMissed = { e.clearLastMissedCaller(); rev++ },
                onEnd = ::endRun
            )
        }
        PuPhase.SUMMARY -> engine?.let { e ->
            PileupSummary(
                engine = e,
                elapsedSeconds = elapsedSeconds(),
                onAgain = { phase = PuPhase.SETUP },
                onBack = onBack
            )
        }
    }

    if (showSettings) {
        SessionSettingsOverlay(scope = SettingsMode.PILEUP, onClose = { showSettings = false })
    }
    }
}

// MARK: - Setup

@Composable
private fun PileupSetup(onStart: () -> Unit, onBack: () -> Unit) {
    val focusManager = LocalFocusManager.current
    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.common_back), color = Brand.teal) }
            Text(stringResource(R.string.mode_pileup_runner), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        CenteredScrollColumn(
            // imePadding keeps the whole setup list above the soft keyboard
            // (#40). The app targets SDK 36, so it is always edge-to-edge and
            // the window no longer resizes for the IME; AppBackground insets
            // the system bars but not the keyboard, which left the Start
            // button under it with no way to scroll it into view. It sits
            // outside verticalScroll so the scroll viewport itself shrinks.
            // A tap on any empty space drops focus and dismisses the keyboard;
            // children are hit-tested first, so buttons and sliders still get
            // their own taps. Both ride the full-width outer column, so the
            // tablet gutters dismiss the keyboard as well as scroll.
            modifier = Modifier
                .imePadding()
                .pointerInput(Unit) { detectTapGestures(onTap = { focusManager.clearFocus() }) },
            contentModifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            PuSectionLabel(stringResource(R.string.pileup_your_call))
            OutlinedTextField(
                value = PileupSettings.myCall,
                onValueChange = { PileupSettings.updateMyCall(it) },
                singleLine = true,
                placeholder = { Text("N0CALL") },
                // A call sign is one field, so the IME's action key closes the
                // keyboard rather than offering a newline you cannot use (#40).
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                modifier = Modifier.fillMaxWidth()
            )

            PuSectionLabel(stringResource(R.string.pileup_exchange))
            PuPills(QSOContestMode.allCases.map { it to it.label }, PileupSettings.mode) {
                PileupSettings.updateMode(it)
            }
            Text(
                PileupSettings.mode.blurb,
                style = MaterialTheme.typography.bodySmall,
                color = Brand.textSecondary
            )

            PuSectionLabel(stringResource(R.string.pileup_the_pileup))
            PuSlider(
                label = stringResource(R.string.pileup_callers),
                value = stringResource(R.string.pileup_callers_value, PileupSettings.maxStations),
                position = PileupSettings.maxStations.toFloat(),
                range = 1f..8f, steps = 6,
                onChange = { PileupSettings.updateMaxStations(it.roundToInt()) },
                enabled = PileupSettings.mode.isPileup
            )
            PuSlider(
                label = stringResource(R.string.pileup_slowest_caller),
                value = stringResource(R.string.common_wpm_value, PileupSettings.minWpm.roundToInt()),
                position = PileupSettings.minWpm.toFloat(),
                range = PileupSettings.MIN_CALLER_WPM.toFloat()..PileupSettings.MAX_CALLER_WPM.toFloat(),
                steps = 49,
                onChange = { PileupSettings.updateMinWpm(it.toDouble()) }
            )
            PuSlider(
                label = stringResource(R.string.pileup_fastest_caller),
                value = stringResource(R.string.common_wpm_value, PileupSettings.maxWpm.roundToInt()),
                position = PileupSettings.maxWpm.toFloat(),
                range = PileupSettings.MIN_CALLER_WPM.toFloat()..PileupSettings.MAX_CALLER_WPM.toFloat(),
                steps = 49,
                onChange = { PileupSettings.updateMaxWpm(it.toDouble()) }
            )
            PuSlider(
                label = stringResource(R.string.pileup_tone_spread),
                value = if (PileupSettings.toneSpread <= 0.0) stringResource(R.string.pileup_zero_beat) else stringResource(R.string.pileup_tone_spread_value, PileupSettings.toneSpread.roundToInt()),
                position = PileupSettings.toneSpread.toFloat(),
                range = 0f..400f, steps = 0,
                onChange = { PileupSettings.updateToneSpread(it.toDouble()) }
            )
            PuToggle(stringResource(R.string.pileup_qsb_fading), PileupSettings.qsbEnabled) { PileupSettings.updateQsbEnabled(it) }
            Column {
                PuSectionLabel(stringResource(R.string.pileup_qrn_static))
                PuPills(QrnPreset.entries.map { it to it.label }, PileupSettings.qrn) {
                    PileupSettings.updateQrn(it)
                }
            }

            PuSectionLabel(stringResource(R.string.pileup_callsigns))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CallsignFormat.entries.forEach { fmt ->
                    val sel = fmt in PileupSettings.formats
                    Box(
                        modifier = Modifier
                            .background(if (sel) Brand.teal else Brand.navyRaised, RoundedCornerShape(8.dp))
                            .clickable { PileupSettings.toggleFormat(fmt) }
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
            PuToggle(stringResource(R.string.common_us_calls_only), PileupSettings.usOnly) { PileupSettings.updateUsOnly(it) }

            PuSectionLabel(stringResource(R.string.pileup_operating))
            PuToggle(stringResource(R.string.pileup_cut_numbers), PileupSettings.cutNumbersEnabled) {
                PileupSettings.updateCutNumbersEnabled(it)
            }
            if (PileupSettings.mode.includesRST) {
                PuToggle(stringResource(R.string.pileup_require_the_rst_copied), PileupSettings.rstRequired) {
                    PileupSettings.updateRstRequired(it)
                }
            }
            Column {
                PuSectionLabel(stringResource(R.string.pileup_on_a_busted_call))
                PuPills(BustBehavior.allCases.map { it to it.label }, PileupSettings.bustBehavior) {
                    PileupSettings.updateBustBehavior(it)
                }
            }
            PuToggle(stringResource(R.string.pileup_impatient_callers_give_up), PileupSettings.giveUpEnabled) {
                PileupSettings.updateGiveUpEnabled(it)
            }
            if (PileupSettings.giveUpEnabled) {
                Column {
                    PuSectionLabel(stringResource(R.string.pileup_tell_me_who_got_away))
                    PuPills(
                        MissedCallerFeedback.allCases.map { it to it.label },
                        PileupSettings.missedCallerFeedback
                    ) { PileupSettings.updateMissedCallerFeedback(it) }
                }
            }
            PuToggle(stringResource(R.string.pileup_keep_my_partial_call_after), PileupSettings.keepPartialCall) {
                PileupSettings.updateKeepPartialCall(it)
            }
            PuToggle(stringResource(R.string.pileup_key_my_side_in_morse), PileupSettings.keyMySide) {
                PileupSettings.updateKeyMySide(it)
            }
            PuToggle(stringResource(R.string.pileup_recall_after_tu), PileupSettings.autoRecall) {
                PileupSettings.updateAutoRecall(it)
            }

            Spacer(Modifier.height(4.dp))
            Button(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(Brand.cornerRadius)
            ) { Text(stringResource(R.string.pileup_start_button, PileupSettings.mode.label), fontWeight = FontWeight.Bold, fontSize = 17.sp) }
            Spacer(Modifier.height(16.dp))
        }
    }
}

// MARK: - Run

@Composable
private fun PileupRun(
    engine: PileupEngine,
    // Deliberately unread: the engine mutates outside Compose, so this bumped
    // counter is what makes each tick/action recompose the run (strong
    // skipping would otherwise see identical parameters and skip).
    tick: Int,
    elapsedSeconds: Int,
    input: String,
    onInput: (String) -> Unit,
    onSend: () -> Unit,
    reveal: Boolean,
    onToggleReveal: () -> Unit,
    onCQ: () -> Unit,
    onRepeat: () -> Unit,
    onLog: () -> Unit,
    onSettings: () -> Unit,
    onDismissMissed: () -> Unit,
    onEnd: () -> Unit
) {
    // The in-run Send box sits behind the IME on an edge-to-edge window too
    // (issue #44's defect, in the QSO screens). This shifts the run layout up;
    // it does not touch focus, so #24/#25 — keeping the keyboard up across
    // sends — still hold.
    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onEnd) { Text(stringResource(R.string.common_end), color = Brand.teal) }
            Spacer(Modifier.weight(1f))
            Text(
                "${PileupSettings.mode.label} · %d:%02d".format(elapsedSeconds / 60, elapsedSeconds % 60),
                style = MaterialTheme.typography.labelMedium,
                color = Brand.textSecondary
            )
            SessionSettingsButton(onOpen = onSettings)
        }

      CenteredContent {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(4.dp))
            PileupScoreboard(engine, elapsedSeconds)

            // A caller just walked off. Naming the call you lost, and what you
            // had it as, turns a station that quietly vanished into a lesson.
            val walkedOff = engine.lastMissedCaller
            if (walkedOff != null &&
                PileupSettings.missedCallerFeedback == MissedCallerFeedback.Immediate) {
                MissedCallerBanner(walkedOff, onDismissMissed)
            }

            Column(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                when (engine.phase) {
                    PileupEngine.Phase.Idle -> {
                        Text(stringResource(R.string.pileup_idle_prompt), textAlign = TextAlign.Center)
                        Spacer(Modifier.height(20.dp))
                        Button(onClick = onCQ) { Text(stringResource(R.string.common_call_cq)) }
                    }

                    PileupEngine.Phase.Pileup -> {
                        Text(
                            stringResource(R.string.common_stations_calling, engine.activeCount),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(stringResource(R.string.common_copy_a_call_and_send_it), textAlign = TextAlign.Center)
                        if (reveal) {
                            Text(
                                stringResource(R.string.common_calling_list, engine.stations.joinToString(", ") { it.call }),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        CallEntry(input = input, onChange = onInput, onSend = onSend)
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = onRepeat) { Text(stringResource(R.string.common_again)) }
                            OutlinedButton(onClick = onCQ) { Text(stringResource(R.string.common_cq)) }
                        }
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = onToggleReveal) {
                            Text(if (reveal) stringResource(R.string.common_hide_hint) else stringResource(R.string.common_show_hint))
                        }
                    }

                    is PileupEngine.Phase.Working, is PileupEngine.Phase.ReadyToLog -> {
                        val st = engine.workingStation
                        Text(stringResource(R.string.common_working_station, st?.call ?: "?"), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.common_copy_their_exchange_and_send_it), textAlign = TextAlign.Center)
                        if (reveal) {
                            Text(
                                stringResource(R.string.common_expecting, engine.expectedCopy ?: "—"),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        CallEntry(input = input, onChange = onInput, onSend = onSend)
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = onRepeat) { Text(stringResource(R.string.common_again)) }
                            Button(onClick = onLog) { Text(stringResource(R.string.common_log_tu)) }
                        }
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = onToggleReveal) {
                            Text(if (reveal) stringResource(R.string.common_hide_hint) else stringResource(R.string.common_show_hint))
                        }
                    }
                }
            }

            // The live log: latest contact on top, so the run reads like a real one.
            if (engine.log.isNotEmpty()) {
                Text(
                    stringResource(R.string.pileup_log),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Brand.textSecondary,
                    modifier = Modifier.padding(top = 4.dp, bottom = 6.dp)
                )
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(engine.log.asReversed(), key = { it.id }) { q ->
                        Row(
                            modifier = Modifier.fillMaxWidth().brandCard(cornerRadius = 10.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(q.call, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Brand.textPrimary, modifier = Modifier.weight(1f))
                            Text(q.exchange, fontFamily = FontFamily.Monospace, color = Brand.textSecondary)
                            Text(stringResource(R.string.common_wpm_lower, q.wpm), style = MaterialTheme.typography.labelSmall, color = Brand.textSecondary)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
      }
    }
}

/** The amber this app already uses for "you got this wrong" (Journey, decoder). */
private val PuWarn = Color(0xFFE08A1E)

/**
 * A caller gave up: which call you lost, and what you had them as. Shown only
 * while the setting asks for it live; the run summary lists them either way.
 */
@Composable
private fun MissedCallerBanner(
    missed: PileupEngine.MissedCaller,
    onDismiss: () -> Unit
) {
    val detail = missed.miscopiedAs?.let { had ->
        pluralStringResource(R.plurals.pileup_missed_you_had, missed.attempts, had, missed.attempts)
    } ?: run {
        pluralStringResource(R.plurals.pileup_missed_never_copied, missed.attempts, missed.attempts)
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).brandCard().padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.pileup_gave_up, missed.call),
                fontWeight = FontWeight.Bold,
                color = PuWarn,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(detail, style = MaterialTheme.typography.labelSmall, color = Brand.textSecondary)
        }
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.pileup_got_it), color = Brand.teal) }
    }
}

/** The live readout: contacts, busts, clean-copy rate, and the hourly rate. */
@Composable
private fun PileupScoreboard(engine: PileupEngine, elapsedSeconds: Int) {
    val rate = if (elapsedSeconds <= 0) 0 else (engine.qsoCount * 3600.0 / elapsedSeconds).roundToInt()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterHorizontally)
    ) {
        PuStat(stringResource(R.string.pileup_worked), "${engine.qsoCount}")
        PuStat(stringResource(R.string.common_busts), "${engine.bustCount}")
        PuStat(stringResource(R.string.pileup_clean), "${(engine.accuracy * 100).roundToInt()}%")
        PuStat(stringResource(R.string.common_rate), stringResource(R.string.common_rate_per_hour, rate))
    }
}

@Composable
private fun PuStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Brand.textPrimary)
        Text(label, style = MaterialTheme.typography.labelSmall, color = Brand.textSecondary)
    }
}

// MARK: - Summary

@Composable
private fun PileupSummary(
    engine: PileupEngine,
    elapsedSeconds: Int,
    onAgain: () -> Unit,
    onBack: () -> Unit
) {
    val rate = if (elapsedSeconds <= 0) 0 else (engine.qsoCount * 3600.0 / elapsedSeconds).roundToInt()
    val cleanTotal = engine.qsoCount + engine.bustCount

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.common_back), color = Brand.teal) }
            Text(stringResource(R.string.pileup_summary_title, PileupSettings.mode.label), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }

        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).brandCard().padding(14.dp)) {
            PuSummaryRow(stringResource(R.string.pileup_worked), "${engine.qsoCount}")
            PuSummaryRow(stringResource(R.string.common_rate), stringResource(R.string.common_rate_per_hour, rate))
            PuSummaryRow(stringResource(R.string.common_clean_copy), if (cleanTotal == 0) "—" else "${(engine.accuracy * 100).roundToInt()}%")
            PuSummaryRow(stringResource(R.string.common_busts), "${engine.bustCount}")
            PuSummaryRow(stringResource(R.string.common_time), "%d:%02d".format(elapsedSeconds / 60, elapsedSeconds % 60))
        }

        val missed = engine.missedCallers
        if (missed.isNotEmpty() && PileupSettings.missedCallerFeedback != MissedCallerFeedback.Off) {
            Text(
                stringResource(R.string.pileup_got_away_header, missed.size),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Brand.textSecondary,
                modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 6.dp)
            )
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                missed.forEach { m ->
                    Row(
                        modifier = Modifier.fillMaxWidth().brandCard().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            m.call,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Brand.textPrimary
                        )
                        Text(
                            m.miscopiedAs?.let { stringResource(R.string.pileup_you_had, it) } ?: stringResource(R.string.pileup_never_copied),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (m.miscopiedAs != null) PuWarn else Brand.textSecondary,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "${m.attempts}×",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelSmall,
                            color = Brand.textSecondary
                        )
                    }
                }
            }
        }

        if (engine.log.isNotEmpty()) {
            Text(
                stringResource(R.string.common_worked),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Brand.textSecondary,
                modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 6.dp)
            )
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(engine.log, key = { it.id }) { q ->
                Row(
                    modifier = Modifier.fillMaxWidth().brandCard().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(q.call, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Brand.textPrimary, modifier = Modifier.weight(1f))
                    Text(q.exchange, fontFamily = FontFamily.Monospace, color = Brand.textSecondary)
                    Text(stringResource(R.string.common_wpm_lower, q.wpm), style = MaterialTheme.typography.labelSmall, color = Brand.textSecondary)
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onAgain, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.common_run_again)) }
            Button(onClick = onBack, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.common_return_home)) }
        }
    }
}

@Composable
private fun PuSummaryRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, color = Brand.textSecondary)
        Spacer(Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.Medium, color = Brand.textPrimary)
    }
}

// MARK: - Small shared bits

@Composable
private fun CallEntry(input: String, onChange: (String) -> Unit, onSend: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = input,
            onValueChange = onChange,
            singleLine = true,
            label = { Text(stringResource(R.string.common_send)) },
            modifier = Modifier.fillMaxWidth(0.6f)
        )
        Button(onClick = onSend, modifier = Modifier.height(56.dp)) { Text(stringResource(R.string.common_send)) }
    }
}

@Composable
private fun PuSectionLabel(text: String) {
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Brand.textSecondary, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun <T> PuPills(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
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
private fun PuToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Brand.textPrimary, fontWeight = FontWeight.Medium)
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Brand.navy,
                checkedTrackColor = Brand.teal,
                uncheckedThumbColor = Brand.textSecondary,
                uncheckedTrackColor = Brand.navyRaised
            )
        )
    }
}

@Composable
private fun PuSlider(
    label: String,
    value: String,
    position: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
    enabled: Boolean = true
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = if (enabled) Brand.textPrimary else Brand.textSecondary, fontWeight = FontWeight.Medium)
            Text(if (enabled) value else "—", color = Brand.teal, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = position,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = Brand.teal,
                activeTrackColor = Brand.teal,
                inactiveTrackColor = Brand.navyRaised,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent
            ),
            modifier = Modifier.height(28.dp)
        )
    }
}
