package app.anothermorsetrainer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import app.anothermorsetrainer.morsekit.SessionClock
import kotlinx.coroutines.delay

// The pieces every drill screen's session shares: the countdown, what reaching
// zero does, and the timer menu's actions. The rules are SessionClock's,
// pinned by fixtures/gap-timers-session-clock.json; iOS keeps the same flow in
// AppModel.

/**
 * Counts [remaining] down a second at a time while [running] and time is left.
 * Keyed on whether a limit exists and whether time is left, so the timer menu
 * starting a countdown, dropping one, or adding time to a held session at zero
 * restarts it. Apart from [rememberSessionTimeUp] so a screen can keep the
 * clock running where it does not compose the rest (Journey's map).
 */
@Composable
fun SessionTick(running: Boolean, remaining: MutableState<Int?>) {
    val r = remaining.value
    LaunchedEffect(running, r == null, (r ?: 0) > 0) {
        if (!running) return@LaunchedEffect
        while (true) {
            val left = remaining.value ?: return@LaunchedEffect
            if (left <= 0) return@LaunchedEffect
            delay(1000)
            remaining.value = remaining.value?.minus(1)
        }
    }
}

/**
 * What the countdown reaching zero does ([SessionClock.endsAfterTimeUp]): it
 * ends the session through [onEnd], unless [awaitingAnswer] says a drill is
 * still waiting for its answer. Then the session is [held] until that drill is
 * answered (the screen's next-drill step ends a held session) or the grace
 * runs out. Returns the timer menu's actions on the same state.
 */
@Composable
fun rememberSessionTimeUp(
    running: Boolean,
    remaining: MutableState<Int?>,
    held: MutableState<Boolean>,
    awaitingAnswer: () -> Boolean,
    onEnd: () -> Unit
): SessionTimerActions {
    val currentAwaiting by rememberUpdatedState(awaitingAnswer)
    val currentOnEnd by rememberUpdatedState(onEnd)
    val r = remaining.value
    LaunchedEffect(running, r) {
        if (!running || r == null || r > 0) {
            held.value = false
            return@LaunchedEffect
        }
        var overdue = 0
        while (!SessionClock.endsAfterTimeUp(held.value || currentAwaiting(), overdue)) {
            held.value = true
            delay(1000)
            overdue++
        }
        currentOnEnd()
    }
    return remember(remaining, held) { SessionTimerActions(remaining, held) { currentOnEnd() } }
}

/**
 * The timer menu's actions on a session's countdown: adding time lifts a hold
 * at once; subtracting to zero ends the session at once, as iOS does; removing
 * the limit lifts a hold.
 */
class SessionTimerActions(
    private val remaining: MutableState<Int?>,
    private val held: MutableState<Boolean>,
    private val onEnd: () -> Unit
) {
    fun add(seconds: Int) {
        remaining.value = SessionClock.added(remaining.value, seconds)
        held.value = false
    }

    fun subtract(seconds: Int) {
        remaining.value = SessionClock.reduced(remaining.value, seconds)
        if (remaining.value == 0) onEnd()
    }

    fun removeLimit() {
        remaining.value = null
        held.value = false
    }
}


