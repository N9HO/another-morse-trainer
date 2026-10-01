package app.anothermorsetrainer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalWindowInfo
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
 * UI thread throughout: presses arrive from pointer or key input and the waits
 * between deadlines resume on [Dispatchers.Main] (the AWT event thread on
 * desktop). Each edge carries the time the keyer scheduled it for, not the
 * moment the wait ended, so a late wake-up shifts when the tone is heard but
 * never the element lengths a consumer measures. Mirrors iOS
 * `PaddleKeyerDriver` (OnScreenPaddles.swift).
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
 * Desktop: the computer keyboard as a Morse key (desktop-only; the phone apps
 * have no equivalent).
 *
 * While an on-screen key ([OnScreenKeySwitch] or [OnScreenPaddles]) is
 * composed and enabled, Space is a straight key (down on key-down, up on
 * key-up, auto-repeat ignored), `[` is the dit paddle and `]` the dah paddle.
 * Every edge goes to the same `onPaddleKey(isDown, atMs)` callback the
 * on-screen paddles feed, stamped in [System.currentTimeMillis] terms as the
 * paddles' are; `[`/`]` run through a [PaddleKeyerDriver] at the Settings
 * paddle mode and speed. When the on-screen paddles are showing, `[`/`]` drive
 * *their* driver, so the paddle on screen lights as it is keyed; a keyboard
 * Space does not light a screen's own straight key (that is the screen's
 * state), but it is heard and decoded the same.
 *
 * Key events reach it two ways:
 * - the on-screen key wraps itself in a focusable box that asks for focus
 *   when it appears, so on a screen that does nothing else the keys work
 *   without a click;
 * - a screen that keeps focus somewhere else (its own root, for letter keys)
 *   forwards from that node's `Modifier.onPreviewKeyEvent { MorseKeyboard.handle(it) }`.
 *   [handle] returns false when no key is on screen, so the screen's own
 *   handling still runs.
 *
 * The innermost enabled on-screen key gets the keys, as [BackDispatcher] does
 * for Escape.
 */
object MorseKeyboard {

    internal class Sink(
        var enabled: Boolean,
        var straight: (Boolean) -> Unit,
        var paddle: (PaddleKeyer.Element, Boolean) -> Unit
    ) {
        private var spaceDown = false
        private var ditDown = false
        private var dahDown = false

        fun handle(event: KeyEvent): Boolean {
            if (!enabled) return false
            val key = event.key
            if (key != Key.Spacebar && key != Key.LeftBracket && key != Key.RightBracket) return false
            val type = event.type
            // Anything else for these keys (a typed-character event) is
            // swallowed too, so a Space never also presses a focused button.
            if (type != KeyEventType.KeyDown && type != KeyEventType.KeyUp) return true
            val down = type == KeyEventType.KeyDown
            when (key) {
                Key.Spacebar -> if (spaceDown != down) { spaceDown = down; straight(down) }
                Key.LeftBracket -> if (ditDown != down) { ditDown = down; paddle(PaddleKeyer.Element.DIT, down) }
                else -> if (dahDown != down) { dahDown = down; paddle(PaddleKeyer.Element.DAH, down) }
            }
            return true
        }

        /** Let go of every key held from the keyboard (focus lost, disabled, gone). */
        fun releaseAll() {
            if (spaceDown) { spaceDown = false; straight(false) }
            if (ditDown) { ditDown = false; paddle(PaddleKeyer.Element.DIT, false) }
            if (dahDown) { dahDown = false; paddle(PaddleKeyer.Element.DAH, false) }
        }
    }

    private val stack = mutableListOf<Sink>()

    internal fun add(s: Sink) { stack.add(s) }
    internal fun remove(s: Sink) { stack.remove(s) }

    /**
     * Offer a key event to the on-screen key. True when it was Space, `[` or
     * `]` and an enabled on-screen key took it.
     */
    fun handle(event: KeyEvent): Boolean {
        val top = stack.lastOrNull { it.enabled } ?: return false
        return top.handle(event)
    }
}

/**
 * Registers [driver] (for `[`/`]`) and [onKey] (for Space) with
 * [MorseKeyboard] while composed, and wraps [content] in a focusable box that
 * takes focus when it appears and forwards the keys itself.
 */
@Composable
private fun KeyboardKeying(
    driver: PaddleKeyerDriver,
    onKey: (Boolean, Long) -> Unit,
    enabled: Boolean,
    content: @Composable () -> Unit
) {
    val currentOnKey by rememberUpdatedState(onKey)
    val sink = remember {
        MorseKeyboard.Sink(
            enabled = enabled,
            straight = { down -> currentOnKey(down, System.currentTimeMillis()) },
            paddle = { element, down -> driver.paddle(element, down) }
        )
    }
    SideEffect { sink.enabled = enabled }
    DisposableEffect(sink) {
        MorseKeyboard.add(sink)
        onDispose {
            sink.releaseAll()
            MorseKeyboard.remove(sink)
        }
    }
    LaunchedEffect(enabled) { if (!enabled) sink.releaseAll() }
    // A key-up that happens while another window has focus never arrives:
    // let go when the window loses focus so no tone is left sounding.
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(windowFocused) { if (!windowFocused) sink.releaseAll() }

    val focus = remember { FocusRequester() }
    var hadFocus by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(
        modifier = Modifier
            .focusRequester(focus)
            .onFocusChanged { state ->
                if (hadFocus && !state.hasFocus) sink.releaseAll()
                hadFocus = state.hasFocus
            }
            .onKeyEvent { event -> sink.handle(event) }
            .focusable()
    ) {
        content()
    }
}

/**
 * Two side-by-side paddles, dit on the left unless swapped in Settings,
 * timed at the character speed in the mode Settings picks (#233). Each paddle
 * is its own press-and-hold target, like the straight key. Mirrors iOS
 * `OnScreenPaddlesView`.
 *
 * Desktop: a mouse has one pointer, so a squeeze needs the keyboard — `[` and
 * `]` press these same paddles (and Space keys straight), see [MorseKeyboard].
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
    val driver = rememberPaddleDriver(onKey, mode, wpm, enabled)

    KeyboardKeying(driver, onKey, enabled) {
        val first = if (swapped) PaddleKeyer.Element.DAH else PaddleKeyer.Element.DIT
        Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Paddle(first, mode, driver, enabled, Modifier.weight(1f).fillMaxHeight())
            Paddle(first.opposite, mode, driver, enabled, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

/** A [PaddleKeyerDriver] that follows Settings and lets go when disabled or disposed. */
@Composable
private fun rememberPaddleDriver(
    onKey: (Boolean, Long) -> Unit,
    mode: PaddleKeyer.Mode,
    wpm: Double,
    enabled: Boolean
): PaddleKeyerDriver {
    val scope = rememberCoroutineScope()
    val driver = remember { PaddleKeyerDriver(mode, wpm, scope) }
    val currentOnKey by rememberUpdatedState(onKey)
    SideEffect { driver.onKey = { down, ms -> currentOnKey(down, ms) } }
    LaunchedEffect(mode, wpm) { driver.configure(mode, wpm) }
    DisposableEffect(Unit) { onDispose { driver.stop() } }
    // Letting go of the key when the screen disables it (an answer revealed).
    LaunchedEffect(enabled) { if (!enabled) driver.stop() }
    return driver
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
 *
 * Desktop: whichever is showing, the keyboard keys too — Space as a straight
 * key and `[`/`]` as paddles, all through [onPaddleKey]. See [MorseKeyboard]
 * for how the key events arrive. The hint line
 * ([DesktopCopy.KEYBOARD_KEY_HINT]) is the calling screen's to show, so it can
 * sit where that screen has room.
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
        // The paddles are not on screen, but `[`/`]` still key them: a driver
        // of the keyboard's own, feeding the same callback.
        val driver = rememberPaddleDriver(onPaddleKey, Settings.paddleMode, Settings.characterWpm, enabled)
        KeyboardKeying(driver, onPaddleKey, enabled) { straight() }
    }
}
