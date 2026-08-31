package app.anothermorsetrainer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Phone-first screens stretch ugly on tablets; cap readable content to this width. */
val CONTENT_MAX_WIDTH: Dp = 640.dp

/** True on tablets / landscape where a two-column menu reads better than one tall column. */
@Composable
fun isWideScreen(): Boolean = LocalConfiguration.current.screenWidthDp >= 600

/**
 * Centre a screen's content and cap it at [maxWidth] so it stays readable on
 * large screens, while still filling the height (so vertical scroll/centring
 * inside [content] behaves). On phones this is a no-op (content is narrower
 * than the cap anyway).
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
