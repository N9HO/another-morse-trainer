package app.anothermorsetrainer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.anothermorsetrainer.morsekit.MidiKeyPaddle
import app.anothermorsetrainer.morsekit.MidiKeyer
import app.anothermorsetrainer.morsekit.PaddleKeyer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.roundToLong

/**
 * Runs a [MidiKeyer] against the wall clock, pressing only its dit and dah
 * contacts, and hands each key edge to
 * [onKey] — the same `(isDown, atMs)` a straight key's press and release would
 * produce, so whatever the straight key feeds (sidetone, the sending decoder,
 * the Vail repeater) is fed the keyer's elements instead.
 *
 * Main thread throughout: presses arrive from pointer input and the waits
 * between deadlines resume on [Dispatchers.Main]. Each edge carries the time
 * the keyer scheduled it for, not the moment the wait ended, so a late wake-up
 * shifts when the tone is heard but never the element lengths a consumer
 * measures. Mirrors iOS `KeyerDriver` (OnScreenPaddles.swift).
 *
 * A settings change made while keying waits for idle by [MidiKeyer.configure];
 * `MidiKeyerTest` proves [MidiKeyer] times dit and dah exactly as [PaddleKeyer].
 */
class PaddleKeyerDriver(mode: PaddleKeyer.Mode, wpm: Double, private val scope: CoroutineScope) {

    var ditHeld by mutableStateOf(false)
        private set
    var dahHeld by mutableStateOf(false)
        private set

    var onKey: ((Boolean, Long) -> Unit)? = null

    private val keyer = MidiKeyer(mode, wpm)
    private var tick: Job? = null

    /** A new mode or speed, taken up once the keyer is idle ([MidiKeyer.configure]). */
    fun configure(mode: PaddleKeyer.Mode, wpm: Double) {
        emit(keyer.configure(mode, wpm, nowMs()))
        schedule()
    }

    fun isHeld(element: PaddleKeyer.Element) =
        if (element == PaddleKeyer.Element.DIT) ditHeld else dahHeld

    fun paddle(element: PaddleKeyer.Element, isDown: Boolean) {
        if (isHeld(element) == isDown) return
        if (element == PaddleKeyer.Element.DIT) ditHeld = isDown else dahHeld = isDown
        val contact = if (element == PaddleKeyer.Element.DIT) MidiKeyPaddle.DIT else MidiKeyPaddle.DAH
        emit(keyer.contact(contact, isDown, nowMs()))
        schedule()
    }

    /** The screen is going away: let go of both paddles and cut any tone. */
    fun stop() {
        tick?.cancel()
        tick = null
        ditHeld = false
        dahHeld = false
        emit(keyer.releaseAll(nowMs()))
    }

    private fun schedule() {
        tick?.cancel()
        tick = null
        val deadline = keyer.nextDeadlineMs ?: return
        val delayMs = ceil((deadline - nowMs()).coerceAtLeast(0.0)).toLong()
        tick = scope.launch(Dispatchers.Main) {
            delay(delayMs)
            tick = null
            emit(keyer.advance(maxOf(deadline, nowMs())))
            schedule()
        }
    }

    private fun emit(edges: List<MidiKeyer.Edge>) {
        for (edge in edges) onKey?.invoke(edge.isDown, edge.atMs.roundToLong())
    }

    private fun nowMs(): Double = System.currentTimeMillis().toDouble()
}

/**
 * Two side-by-side touch paddles, dit on the left unless swapped in Settings,
 * timed at the character speed in the mode Settings picks (#233). Each paddle
 * is its own press-and-hold target, like the straight key, so two fingers can
 * hold both at once for a squeeze. Mirrors iOS `OnScreenPaddlesView`.
 */
@Composable
fun OnScreenPaddles(
    onKey: (Boolean, Long) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val mode = Settings.paddleMode
    val wpm = Settings.characterWpm
    val swapped = Settings.paddleSwap
    val scope = rememberCoroutineScope()
    val driver = remember { PaddleKeyerDriver(mode, wpm, scope) }
    val currentOnKey by rememberUpdatedState(onKey)
    SideEffect { driver.onKey = { down, ms -> currentOnKey(down, ms) } }
    LaunchedEffect(mode, wpm) { driver.configure(mode, wpm) }
    DisposableEffect(Unit) { onDispose { driver.stop() } }
    // Letting go of the key when the screen disables it (an answer revealed).
    LaunchedEffect(enabled) { if (!enabled) driver.stop() }

    val first = if (swapped) PaddleKeyer.Element.DAH else PaddleKeyer.Element.DIT
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Paddle(first, mode, driver, enabled, Modifier.weight(1f).fillMaxHeight())
        Paddle(first.opposite, mode, driver, enabled, Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun Paddle(
    element: PaddleKeyer.Element,
    mode: PaddleKeyer.Mode,
    driver: PaddleKeyerDriver,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    val pressed = driver.isHeld(element)
    val isDit = element == PaddleKeyer.Element.DIT
    val description = stringResource(if (isDit) R.string.paddle_dit_description else R.string.paddle_dah_description)
    Box(
        modifier = modifier
            .background(if (pressed) Brand.teal else Brand.navyRaised, RoundedCornerShape(Brand.cornerRadius))
            .border(
                width = if (pressed) 2.dp else 1.dp,
                color = if (pressed) Brand.tealBright else Brand.hairline,
                shape = RoundedCornerShape(Brand.cornerRadius)
            )
            .semantics { contentDescription = description }
            .pointerInput(element, enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onPress = {
                    driver.paddle(element, true)
                    try { tryAwaitRelease() } finally { driver.paddle(element, false) }
                })
            },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (isDit) "•" else "—",
                fontSize = 24.sp, fontWeight = FontWeight.Bold,
                color = if (pressed) Brand.navy else Brand.teal
            )
            Text(
                stringResource(if (isDit) R.string.paddle_dit else R.string.paddle_dah),
                fontSize = 12.sp, fontWeight = FontWeight.Bold,
                color = if (pressed) Brand.navy else Brand.textSecondary
            )
            Text(
                mode.label,
                fontSize = 11.sp,
                color = if (pressed) Brand.navy else Brand.textSecondary
            )
        }
    }
}

/**
 * The on-screen key the operator chose in Settings: the screen's own straight
 * key, or the paddles in its place. [modifier] sizes the paddles; the straight
 * key sizes itself. [onPaddleKey] receives each key edge and when it happened;
 * the straight key keeps calling its screen directly. Mirrors iOS
 * `OnScreenKeySwitch`.
 */
@Composable
fun OnScreenKeySwitch(
    onPaddleKey: (Boolean, Long) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    straight: @Composable () -> Unit
) {
    if (Settings.onScreenKey == OnScreenKeyType.PADDLES) {
        OnScreenPaddles(onPaddleKey, modifier, enabled)
    } else {
        straight()
    }
}

// ---- Shared key views ----

/**
 * The straight key's hold-to-key pad, lit while held. [pressed] is the
 * screen's, so a screen that also keys from the keyboard can light it; [onKey]
 * gets every press and release, a cancelled touch included. Mirrors iOS
 * `StraightKeyPad`.
 */
@Composable
fun StraightKeyPad(
    pressed: Boolean,
    onKey: (Boolean) -> Unit,
    height: Dp,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: String = stringResource(R.string.common_hold_to_key),
    labelSize: TextUnit = 12.sp,
    /** A fixed label colour; null is navy while pressed, secondary while up. */
    labelColor: Color? = null,
    /** The ⠿ mark above the label, at this size; null for the label alone. */
    glyphSize: TextUnit? = null
) {
    val currentOnKey by rememberUpdatedState(onKey)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(Brand.cornerRadius))
            .background(if (pressed) Brand.teal else Brand.navyRaised)
            .border(
                width = if (pressed) 2.dp else 1.dp,
                color = if (pressed) Brand.tealBright else Brand.hairline,
                shape = RoundedCornerShape(Brand.cornerRadius)
            )
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onPress = {
                    currentOnKey(true)
                    try { tryAwaitRelease() } finally { currentOnKey(false) }
                })
            },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            glyphSize?.let { Text("⠿", fontSize = it, color = if (pressed) Brand.navy else Brand.teal) }
            Text(
                label,
                fontSize = labelSize,
                fontWeight = FontWeight.Bold,
                color = labelColor ?: if (pressed) Brand.navy else Brand.textSecondary
            )
        }
    }
}

/**
 * The on-screen key chosen in Settings: the paddles, timed by their own keyer
 * and handed to [onPaddleKey], or a [StraightKeyPad] keyed through [onKey].
 * Mirrors iOS `OnScreenKey`.
 */
@Composable
fun OnScreenKey(
    pressed: Boolean,
    onKey: (Boolean) -> Unit,
    onPaddleKey: (Boolean, Long) -> Unit,
    height: Dp,
    enabled: Boolean = true,
    label: String = stringResource(R.string.common_hold_to_key),
    labelSize: TextUnit = 12.sp,
    labelColor: Color? = null,
    glyphSize: TextUnit? = null
) {
    // Straight key or paddles, as chosen in Settings (#233).
    OnScreenKeySwitch(
        onPaddleKey = onPaddleKey,
        modifier = Modifier.fillMaxWidth().height(height),
        enabled = enabled
    ) {
        StraightKeyPad(
            pressed = pressed, onKey = onKey, height = height, enabled = enabled,
            label = label, labelSize = labelSize, labelColor = labelColor, glyphSize = glyphSize
        )
    }
}

/**
 * A keying game's prompt line: what to key, what has been keyed so far (with
 * the Slashed zero setting), and a mark when a hardware key is connected.
 * Mirrors iOS `KeyedTextHeader`.
 */
@Composable
fun KeyedTextHeader(prompt: String, text: String, hardwareKeyConnected: Boolean) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(prompt, style = MaterialTheme.typography.labelSmall, color = Brand.textSecondary)
        Spacer(Modifier.weight(1f))
        SlashableText(text.ifEmpty { "—" }, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, color = Brand.textPrimary)
        if (hardwareKeyConnected) {
            Text("  🎹", color = Brand.teal, modifier = Modifier.semantics { contentDescription = "Hardware key connected" })
        }
    }
}

/**
 * For a game answered one keyed character at a time (Invaders, Galaga,
 * Asteroids): while [active], hand each character [keyer] finishes to
 * [onCharacter] and start the next one clean. Mirrors iOS `CharacterKeyPanel`.
 */
@Composable
fun KeyedCharacters(keyer: SendingKeyer, active: Boolean, onCharacter: (Char) -> Unit) {
    val currentOnCharacter by rememberUpdatedState(onCharacter)
    LaunchedEffect(keyer.decodedText, keyer.isKeying, active) {
        if (!active || keyer.isKeying) return@LaunchedEffect
        val ch = keyer.decodedText.trim().firstOrNull() ?: return@LaunchedEffect
        keyer.clear()
        currentOnCharacter(ch)
    }
}
