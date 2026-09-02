package app.anothermorsetrainer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Phone-first screens stretch ugly on tablets; cap readable content to this width. */
val CONTENT_MAX_WIDTH: Dp = 640.dp

/** True on tablets / landscape where a two-column menu reads better than one tall column. */
@Composable
fun isWideScreen(): Boolean {
    // The window, not the display: in split screen the app's own width is
    // the one that decides whether two columns fit.
    val widthPx = LocalWindowInfo.current.containerSize.width
    return with(LocalDensity.current) { widthPx.toDp() } >= 600.dp
}

/**
 * Centre a screen's content and cap it at [maxWidth] so it stays readable on
 * large screens, while still filling the height (so vertical centring inside
 * [content] behaves). On phones this is a no-op (content is narrower than the
 * cap anyway).
 *
 * Not for content that scrolls: a `verticalScroll` column inside this box is
 * only as wide as the box, so on a tablet the gutters either side of it do
 * not scroll. Use [CenteredScrollColumn] for a scrolling screen.
 */
@Composable
fun CenteredContent(
    maxWidth: Dp = CONTENT_MAX_WIDTH,
    content: @Composable () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(modifier = Modifier.fillMaxHeight().widthIn(max = maxWidth)) {
            content()
        }
    }
}

/**
 * A vertically scrolling column whose content is centred and capped at
 * [maxWidth], where the scroll itself spans the whole window.
 *
 * [CenteredContent] around a `verticalScroll` column had this inside out: the
 * cap sat outside the scroll, so the scrollable region was the 640dp column
 * and the gutters either side of it — most of the screen on a landscape
 * tablet — swallowed every drag (#112). Here the outer column carries the
 * scroll at full width and the inner one carries the cap, so a drag anywhere
 * across the screen scrolls the page.
 *
 * [modifier] goes on the outer, full-width column (imePadding, a tap-anywhere
 * gesture); [contentModifier] on the inner, capped one (the content padding).
 *
 * Inline, like [Column] itself, so a `return` inside [content] leaves the
 * calling screen exactly as it did when the content sat in a bare Column.
 */
@Composable
inline fun CenteredScrollColumn(
    modifier: Modifier = Modifier,
    contentModifier: Modifier = Modifier,
    maxWidth: Dp = CONTENT_MAX_WIDTH,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // widthIn before fillMaxWidth: the cap has to narrow the constraints
        // before the fill takes all of them, or the fill wins and there is no cap.
        Column(
            modifier = Modifier.widthIn(max = maxWidth).fillMaxWidth().then(contentModifier),
            horizontalAlignment = horizontalAlignment,
            verticalArrangement = verticalArrangement,
            content = content
        )
    }
}
