package app.anothermorsetrainer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.anothermorsetrainer.morsekit.PaddleKeyer
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val OK_GREEN = Color(0xFF2E7D32)
private val ERR_RED = Color(0xFFC62828)

/**
 * **Sending Practice**: hear a character or word, then *key it back* on the
 * on-screen straight key. The keying is decoded live (via [SendingKeyer] /
 * `MorseDecoder`) and graded against the drill through the shared
 * [app.anothermorsetrainer.morsekit.QuizSource] — the same Koch ladder that
 * drives the Characters mode, so it adapts to what you can already send.
 *
 * Mirrors the iOS "answer by keying" panel (`SendingKeyerView`): hold-to-key,
 * a "YOU SENT" readout, Clear/Submit, and auto-submit once the decoded text is
 * at least as long as the answer and the key is idle.
 *
 * Desktop: the keyboard keys too — Space is the straight key and `[` / `]`
 * the paddles (see [rememberKeyboardKeying]), into the same [SendingKeyer]
 * the on-screen key feeds. The Bluetooth-key button is gone (BLE MIDI is not
 * supported on desktop); a hint on connecting a USB key takes its place.
 */
@Composable
fun SendingPracticeScreen(onBack: () -> Unit, onSwitchMode: (TrainingMode) -> Unit = {}) {
    val player = remember { MorsePlayer() }
    val haptics = remember { Haptics() }
    // The same persisted Koch ladder the Characters drill uses, not a fresh
    // engine seeded from proficiency. Sending practice drills what you are
    // learning to copy, so it should start where copying left off and its
    // answers should count toward the ladder — as on iOS, where AppModel hands
    // the sending mode the one shared charLadder. A freshly seeded engine also
    // meant a pinned track stage silently did nothing here.
    val source = remember { EngineStore.characters() }
    val keyer = remember { SendingKeyer(wpm = Settings.characterWpm, toneHz = Settings.sidetoneHz) }
    val midi = remember { HardwareKey() }
    // Push a keyer mode or speed picked in the Settings sheet to the adapter
    // now, rather than at the next wake — i.e. after leaving the module (#46).
    AdapterConfigSync(midi)
    val scope = rememberCoroutineScope()

    var midiDevice by remember { mutableStateOf<String?>(null) }

    var drill by remember { mutableStateOf(source.nextDrill()) }
    var round by remember { mutableIntStateOf(0) }
    var revealed by remember { mutableStateOf(false) }
    var lastCorrect by remember { mutableStateOf(false) }
    var sentAnswer by remember { mutableStateOf("") }
    var summary by remember { mutableStateOf(source.summary) }
    // Pinning a stage mutates the shared track in place, so it has no Compose
    // state of its own — bump a revision to redraw the pills after a pick.
    var stageRev by remember { mutableIntStateOf(0) }
    var toneFinishedAt by remember { mutableLongStateOf(0L) }
    var keyPressed by remember { mutableStateOf(false) }

    // Saved with the instance state, so a process reclaimed mid-session still
    // records what it had on the way out; the ladder itself is in EngineStore.
    val tally = rememberSaveable(saver = TallySaver) { Tally() }

    DisposableEffect(Unit) {
        keyer.scope = scope
        keyer.start()
        // Hardware key (Vail Adapter or another USB MIDI key): route note on/off through the
        // same keyer as the on-screen key.
        midi.start(
            onKey = { down -> keyer.touchKey(down) },
            onConnected = { name -> midiDevice = name }
        )
        onDispose { midi.stop(); keyer.stop(); player.release() }
    }

    LaunchedEffect(round) {
        revealed = false
        lastCorrect = false
        sentAnswer = ""
        toneFinishedAt = 0L
        keyer.clear()
        player.play(drill.playable, Settings.sidetoneHz, Settings.timing()) { toneFinishedAt = System.nanoTime() }
    }

    fun grade(answer: String) {
        if (revealed || answer.isEmpty()) return
        val ttr = if (toneFinishedAt == 0L) 0.0 else (System.nanoTime() - toneFinishedAt) / 1_000_000_000.0
        sentAnswer = answer
        val outcome = source.record(choice = answer, ttr = ttr)
        lastCorrect = outcome.correct
        tally.attempts += 1
        if (outcome.correct) tally.correct += 1
        if (drill.correct.length == 1) {
            Stats.recordChar(drill.correct, outcome.correct, null)
        }
        // Keep the track durable — the ladder resumes next time, and progress
        // made by keying now counts the same as progress made by copying.
        EngineStore.save()
        if (Settings.hapticsEnabled) {
            if (outcome.correct) haptics.success() else haptics.error()
        }
        summary = source.summary
        revealed = true
    }

    // Auto-submit once the decoded text is at least as long as the answer and the
    // key has gone idle (matches the iOS maybeAutoSubmit rhythm).
    LaunchedEffect(keyer.decodedText, keyer.isKeying, revealed) {
        if (revealed || keyer.isKeying) return@LaunchedEffect
        val typed = keyer.decodedText.trim()
        if (typed.isNotEmpty() && typed.length >= drill.correct.length) {
            grade(typed.uppercase())
        }
    }

    /**
     * Next drill, clearing the reveal state in the same recomposition as the
     * new drill — LaunchedEffect(round) resets it a frame later, and that
     * stale frame flashed the next item's answer (issue #63).
     */
    fun advanceNow() {
        drill = source.nextDrill()
        revealed = false
        sentAnswer = ""
        round++
    }

    // After grading, pause on the result, then advance — unless it was a
    // miss: that holds the correction until Next is pressed (issue #77),
    // repeating the target after a beat so you re-hear what you should
    // have keyed while the comparison shows.
    LaunchedEffect(revealed) {
        if (revealed) {
            if (!lastCorrect) {
                delay(450)
                player.replaySound(drill.playable, Settings.sidetoneHz, Settings.timing())
                return@LaunchedEffect
            }
            delay(1200)
            advanceNow()
        }
    }

    fun recordRun() {
        Stats.record(
            mode = "Sending", attempts = tally.attempts, correct = tally.correct,
            bestTtrMs = null, durationSeconds = tally.elapsedSeconds(),
            characterWpm = Settings.characterWpm.roundToInt(),
            effectiveWpm = Settings.effectiveWpmInUse.roundToInt()
        )
    }

    fun finish() {
        recordRun()
        onBack()
    }
    BackHandler { finish() }

    // Mid-session Settings, drawn over the session so its state lives on.
    var showSettings by remember { mutableStateOf(false) }

    // Desktop: Space / [ ] key into the same keyer as the on-screen key. The
    // root takes focus on entry so the keys work without a click first; the
    // preview handler claims Space before a focused button can treat it as a click.
    val keyboardKeying = rememberKeyboardKeying(
        enabled = !revealed && !showSettings,
        onStraightKey = { down -> keyPressed = down; keyer.touchKey(down) },
        onPaddleKey = { down, ms -> keyer.touchKey(down, ms) }
    )
    val rootFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { rootFocus.requestFocus() } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { keyboardKeying(it) }
            .focusRequester(rootFocus)
            .focusable()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { finish() }) { Text(stringResource(R.string.common_back)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Switching records the run the way Back does, then lands on
                // the picked mode's setup (iOS #42).
                SwitchModeButton(TrainingMode.SENDING) { mode ->
                    recordRun()
                    onSwitchMode(mode)
                }
                SessionSettingsButton { showSettings = true }
            }
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(stringResource(R.string.mode_sending_practice), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text(summary, style = MaterialTheme.typography.labelMedium, color = Brand.textSecondary)

            // The same hold the Characters quiz offers (#51): this drills the
            // same track, and keying whole words with no way back to single
            // characters was the complaint in #95.
            Spacer(Modifier.height(10.dp))
            key(stageRev) {
                StagePinRow(pinned = source.pinnedStage) { pick ->
                    if (pick == null) source.unpin() else source.pin(pick)
                    EngineStore.save()
                    summary = source.summary
                    stageRev++
                    advanceNow()
                }
            }
            Spacer(Modifier.height(2.dp))
            val connectedKey = midiDevice
            if (connectedKey != null) {
                Text("🎹 $connectedKey", style = MaterialTheme.typography.labelSmall, color = Brand.teal)
            } else {
                // "MIDI unavailable" used to cover both the setup failing and
                // nothing being plugged in, which read as a dead end. Only the
                // first is a fault; the second just needs a key connected.
                // Same two readouts as the iOS SendingKeyerView.
                val midiUnavailable = remember { midi.isUnavailable }
                Text(
                    if (midiUnavailable) stringResource(R.string.sending_midi_unavailable)
                    else stringResource(R.string.sending_no_hardware_key),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (midiUnavailable) Color(0xFFEF6C00) else Brand.textSecondary,
                    textAlign = TextAlign.Center
                )
            }
            // Desktop: where Android offered its Bluetooth-key button, say
            // how a key connects here (USB MIDI only).
            if (connectedKey == null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    DesktopCopy.KEY_CONNECT_HINT,
                    style = MaterialTheme.typography.labelSmall,
                    color = Brand.textSecondary,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(Modifier.height(20.dp))

            if (revealed) {
                SlashableText(
                    text = drill.revealPrimary,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (lastCorrect) stringResource(R.string.sending_you_sent_it) else stringResource(R.string.sending_wrong, sentAnswer),
                    color = if (lastCorrect) OK_GREEN else ERR_RED,
                    fontWeight = FontWeight.Medium
                )
                if (!lastCorrect) {
                    // The held correction (issue #77): re-hear the target as
                    // often as needed, move on when ready.
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = {
                            player.replaySound(drill.playable, Settings.sidetoneHz, Settings.timing())
                        }) { Text(stringResource(R.string.common_replay)) }
                        Button(
                            onClick = { advanceNow() },
                            colors = ButtonDefaults.buttonColors(containerColor = Brand.teal, contentColor = Brand.navy)
                        ) { Text(stringResource(R.string.common_next), fontWeight = FontWeight.SemiBold) }
                    }
                }
            } else {
                Text(stringResource(R.string.sending_listen_then_key_it_back), fontSize = 16.sp, color = Brand.teal)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    player.replaySound(drill.playable, Settings.sidetoneHz, Settings.timing())
                }) { Text(stringResource(R.string.common_replay)) }
            }

            Spacer(Modifier.height(20.dp))

            // "YOU SENT" decoded readout.
            Column(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(Brand.cornerRadius)).brandCard()
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(stringResource(R.string.common_you_sent), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Brand.textSecondary)
                Spacer(Modifier.height(4.dp))
                val decoded = keyer.decodedText
                Text(
                    text = if (decoded.isEmpty()) "—" else decoded,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    color = if (decoded.isEmpty()) Brand.textSecondary else Brand.textPrimary,
                    maxLines = 1
                )
            }

            Spacer(Modifier.height(20.dp))

            // Hold-to-key straight key.
            // Straight key or paddles, as chosen in Settings (#233).
            OnScreenKeySwitch(
                onPaddleKey = { down, ms -> keyer.touchKey(down, ms) },
                modifier = Modifier.fillMaxWidth().height(120.dp),
                enabled = !revealed
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .clip(RoundedCornerShape(Brand.cornerRadius))
                        .background(if (keyPressed) Brand.teal else Brand.navyRaised)
                        .border(
                            width = if (keyPressed) 2.dp else 1.dp,
                            color = if (keyPressed) Brand.tealBright else Brand.hairline,
                            shape = RoundedCornerShape(Brand.cornerRadius)
                        )
                        .pointerInput(revealed) {
                            if (revealed) return@pointerInput
                            detectTapGestures(
                                onPress = {
                                    keyPressed = true
                                    keyer.touchKey(true)
                                    try {
                                        tryAwaitRelease()
                                    } finally {
                                        keyPressed = false
                                        keyer.touchKey(false)
                                    }
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("⠿", fontSize = 26.sp, color = if (keyPressed) Brand.navy else Brand.teal)
                        Text(
                            stringResource(R.string.common_hold_to_key),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (keyPressed) Brand.navy else Brand.textSecondary
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                DesktopCopy.KEYBOARD_KEY_HINT,
                style = MaterialTheme.typography.labelSmall,
                color = Brand.textSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { keyer.clear() },
                    enabled = !revealed,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) { Text(stringResource(R.string.common_clear)) }
                Button(
                    onClick = { grade(keyer.submit().uppercase()) },
                    enabled = !revealed && keyer.decodedText.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Brand.teal, contentColor = Brand.navy),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) { Text(stringResource(R.string.common_submit), fontWeight = FontWeight.SemiBold) }
            }
        }

        if (showSettings) {
            SessionSettingsOverlay(scope = SettingsMode.SENDING, onClose = { showSettings = false })
        }
    }
}

/**
 * Desktop: the computer keyboard as a Morse key, for the screens that key
 * (Sending Practice, Rapid Fire's keyed answers, the Sending Analyzer, the
 * Vail Repeater).
 *
 * - Space is a straight key: down on KeyDown, up on KeyUp, with the OS
 *   auto-repeat of a held key ignored. It calls [onStraightKey], the same
 *   path the screen's on-screen straight key uses.
 * - `[` and `]` are the dit and dah paddles, timed by a [PaddleKeyerDriver] at
 *   the Settings paddle mode and character speed, whose edges go to
 *   [onPaddleKey] — the path the on-screen paddles use. They are handled here
 *   only while the straight key is the on-screen key: when Settings shows the
 *   on-screen paddles, `OnScreenPaddles` owns `[` / `]`, and handling them here
 *   too would key every element twice.
 *
 * Returns a key-event handler for the screen root's `onPreviewKeyEvent` /
 * `onKeyEvent`; it answers true for the keys it used. While [enabled] is false
 * it ignores the keys and lets go of anything held.
 */
@Composable
internal fun rememberKeyboardKeying(
    enabled: Boolean,
    onStraightKey: (Boolean) -> Unit,
    onPaddleKey: (Boolean, Long) -> Unit
): (KeyEvent) -> Boolean {
    val scope = rememberCoroutineScope()
    val mode = Settings.paddleMode
    val wpm = Settings.characterWpm
    val paddlesOnScreen = Settings.onScreenKey == OnScreenKeyType.PADDLES
    val driver = remember { PaddleKeyerDriver(mode, wpm, scope) }
    val straight by rememberUpdatedState(onStraightKey)
    val paddle by rememberUpdatedState(onPaddleKey)
    val isEnabled by rememberUpdatedState(enabled)
    val ownsPaddles by rememberUpdatedState(!paddlesOnScreen)
    // One-element holder rather than state: key handling needs no recomposition.
    val spaceHeld = remember { BooleanArray(1) }

    SideEffect { driver.onKey = { down, ms -> paddle(down, ms) } }
    LaunchedEffect(mode, wpm) { driver.configure(mode, wpm) }

    val releaseAll: () -> Unit = {
        driver.stop()
        if (spaceHeld[0]) {
            spaceHeld[0] = false
            straight(false)
        }
    }
    LaunchedEffect(enabled, paddlesOnScreen) { if (!enabled || paddlesOnScreen) releaseAll() }
    DisposableEffect(Unit) { onDispose { releaseAll() } }

    return remember {
        { event: KeyEvent ->
            when (event.key) {
                Key.Spacebar -> {
                    if (!isEnabled) {
                        false
                    } else when (event.type) {
                        KeyEventType.KeyDown -> {
                            if (!spaceHeld[0]) {
                                spaceHeld[0] = true
                                straight(true)
                            }
                            true
                        }
                        KeyEventType.KeyUp -> {
                            if (spaceHeld[0]) {
                                spaceHeld[0] = false
                                straight(false)
                            }
                            true
                        }
                        else -> false
                    }
                }
                Key.LeftBracket, Key.RightBracket -> {
                    if (!isEnabled) {
                        false
                    } else if (!ownsPaddles) {
                        // The on-screen paddles own [ and ]; hand the key to
                        // them even when this screen's root holds focus.
                        MorseKeyboard.handle(event)
                    } else {
                        val element = if (event.key == Key.LeftBracket) PaddleKeyer.Element.DIT
                        else PaddleKeyer.Element.DAH
                        when (event.type) {
                            // paddle() ignores a repeat of the state it already has.
                            KeyEventType.KeyDown -> { driver.paddle(element, true); true }
                            KeyEventType.KeyUp -> { driver.paddle(element, false); true }
                            else -> false
                        }
                    }
                }
                else -> false
            }
        }
    }
}
