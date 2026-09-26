package app.anothermorsetrainer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.anothermorsetrainer.morsekit.PaddleKeyer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToLong

/**
 * Runs a [PaddleKeyer] against the wall clock and hands each key edge to
 * [onKey] — the same `(isDown, atMs)` a straight key's press and release would
 * produce, so whatever the straight key feeds (sidetone, the sending decoder,
 * the Vail repeater) is fed the keyer's elements instead.
 *
 * Main thread throughout: presses arrive from pointer input and the waits
 * between deadlines resume on [Dispatchers.Main]. Each edge carries the time
 * the keyer scheduled it for, not the moment the wait ended, so a late wake-up
 * shifts when the tone is heard but never the element lengths a consumer
 * measures. Mirrors iOS `PaddleKeyerDriver` (OnScreenPaddles.swift).
 */
class PaddleKeyerDriver(mode: PaddleKeyer.Mode, wpm: Double, private val scope: CoroutineScope) {

    var ditHeld by mutableStateOf(false)
        private set
    var dahHeld by mutableStateOf(false)
        private set

    var onKey: ((Boolean, Long) -> Unit)? = null

    private var keyer = PaddleKeyer(mode, wpm)
    /** A mode or speed change made while keying, applied once the keyer is idle. */
    private var pending: Pair<PaddleKeyer.Mode, Double>? = null
    private var tick: Job? = null

    fun configure(mode: PaddleKeyer.Mode, wpm: Double) {
        pending = mode to wpm
        applyPendingIfIdle()
    }

    fun isHeld(element: PaddleKeyer.Element) =
        if (element == PaddleKeyer.Element.DIT) ditHeld else dahHeld

    fun paddle(element: PaddleKeyer.Element, isDown: Boolean) {
        if (isHeld(element) == isDown) return
        if (element == PaddleKeyer.Element.DIT) ditHeld = isDown else dahHeld = isDown
        emit(keyer.paddle(element, isDown, nowMs()))
        schedule()
    }

    /** The screen is going away: let go of both paddles and cut any tone. */
    fun stop() {
        tick?.cancel()
        tick = null
        ditHeld = false
        dahHeld = false
        emit(keyer.releaseAll(nowMs()))
        applyPendingIfIdle()
    }

    private fun schedule() {
        tick?.cancel()
        tick = null
        val deadline = keyer.nextDeadlineMs ?: run { applyPendingIfIdle(); return }
        val delayMs = ceil((deadline - nowMs()).coerceAtLeast(0.0)).toLong()
        tick = scope.launch(Dispatchers.Main) {
            delay(delayMs)
            tick = null
            emit(keyer.advance(maxOf(deadline, nowMs())))
            schedule()
        }
    }

    private fun applyPendingIfIdle() {
        val (mode, wpm) = pending ?: return
        if (keyer.isBusy || ditHeld || dahHeld) return
        pending = null
        if (mode != keyer.mode || abs(1200.0 / wpm.coerceAtLeast(1.0) - keyer.unitMs) > 1e-9) {
            keyer = PaddleKeyer(mode, wpm)
        }
    }

    private fun emit(edges: List<PaddleKeyer.Edge>) {
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
    modifier: Modifier
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
 * key, or the paddles in its place, sized by [paddleModifier]. [onPaddleKey]
 * receives each key edge and when it happened; the straight key keeps calling
 * its screen directly. Mirrors iOS `OnScreenKeySwitch`.
 */
@Composable
fun OnScreenKeySwitch(
    onPaddleKey: (Boolean, Long) -> Unit,
    paddleModifier: Modifier,
    enabled: Boolean = true,
    straight: @Composable () -> Unit
) {
    if (Settings.onScreenKey == OnScreenKeyType.PADDLES) {
        OnScreenPaddles(onPaddleKey, paddleModifier, enabled)
    } else {
        straight()
    }
}
