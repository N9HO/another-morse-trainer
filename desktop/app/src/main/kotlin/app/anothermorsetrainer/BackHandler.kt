package app.anothermorsetrainer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/**
 * Escape is the desktop's Back.
 *
 * The screens ported from android/ intercept the system Back gesture with
 * `BackHandler { … }` to close a sheet, end a run or step out of a sub-page.
 * A desktop window has no Back gesture; Escape is the key every desktop user
 * reaches for instead. This keeps the same call shape: the most recently
 * composed enabled handler gets the press, as the innermost one does on
 * Android. The window routes Escape here from its preview key handler
 * (Main.kt); a press no handler claims does nothing.
 */
object BackDispatcher {
    internal class Entry(var enabled: Boolean, var onBack: () -> Unit)

    private val stack = mutableListOf<Entry>()

    internal fun add(e: Entry) { stack.add(e) }
    internal fun remove(e: Entry) { stack.remove(e) }

    /** Offer a key event; true when it was Escape and a handler took it. */
    fun handle(event: KeyEvent): Boolean {
        if (event.key != Key.Escape || event.type != KeyEventType.KeyDown) return false
        val top = stack.lastOrNull { it.enabled } ?: return false
        top.onBack()
        return true
    }
}

@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    val current = rememberUpdatedState(onBack)
    val entry = remember { BackDispatcher.Entry(enabled) { current.value() } }
    SideEffect { entry.enabled = enabled }
    DisposableEffect(entry) {
        BackDispatcher.add(entry)
        onDispose { BackDispatcher.remove(entry) }
    }
}
