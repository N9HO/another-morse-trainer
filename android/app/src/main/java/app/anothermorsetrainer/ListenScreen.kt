package app.anothermorsetrainer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Hands-free, eyes-free practice — UI controller only. The actual loop runs in
 * [ListenService] (a foreground service) so it keeps playing with the screen
 * locked; this screen reads [ListenState] for display and sends start/pause/
 * stop commands. Leaving the screen stops the service (matches iOS, which ends
 * Listen mode when you leave it).
 */
@Composable
fun ListenScreen(onBack: () -> Unit, onSwitchMode: (TrainingMode) -> Unit = {}) {
    val context = LocalContext.current

    fun leave() {
        ListenService.stop(context)
        onBack()
    }
    BackHandler { leave() }

    val running = ListenState.running
    val paused = ListenState.paused

    // Mid-session Settings, drawn over the screen; the Listen foreground
    // service keeps playing underneath either way.
    var showSettings by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { leave() }) { Text(stringResource(R.string.common_back), color = Brand.teal) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Switching stops the loop the way Back does, then lands on
                // the picked mode's setup (iOS #42).
                SwitchModeButton(TrainingMode.LISTEN) { mode ->
                    ListenService.stop(context)
                    onSwitchMode(mode)
                }
                SessionSettingsButton { showSettings = true }
            }
        }

        CenteredContent {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.mode_listen_and_learn), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Brand.textPrimary)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.listen_blurb),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Brand.textSecondary,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(20.dp))
                ChipRow(
                    options = ListenContent.entries,
                    selected = ListenState.contentSel,
                    label = { it.label },
                    onSelect = {
                        ListenState.contentSel = it
                        if (running && !paused) ListenService.start(context)  // restart with the new config
                    }
                )
                Spacer(Modifier.height(8.dp))
                ChipRow(
                    options = ListenGap.entries,
                    selected = ListenState.gapSel,
                    label = { it.label },
                    onSelect = {
                        ListenState.gapSel = it
                        if (running && !paused) ListenService.start(context)
                    }
                )

                Spacer(Modifier.height(16.dp))
                // Session readout: items heard, and time left when a length is set.
                if (running) {
                    val left = ListenState.limitSeconds?.let { maxOf(0, it - ListenState.activeSeconds) }
                    Text(
                        stringResource(R.string.listen_heard_count, ListenState.itemsHeard) +
                            (left?.let { stringResource(R.string.listen_time_left, it / 60, it % 60) } ?: ""),
                        style = MaterialTheme.typography.labelMedium,
                        color = Brand.textSecondary
                    )
                }

                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier.fillMaxWidth().height(170.dp).brandCard(),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        !running -> Text(
                            ListenState.finishedNote ?: stringResource(R.string.listen_tap_play_prompt),
                            color = if (ListenState.finishedNote != null) Brand.teal else Brand.textSecondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(24.dp)
                        )
                        paused -> Text(stringResource(R.string.listen_status_paused), color = Brand.textSecondary, fontSize = 20.sp)
                        ListenState.playing -> Text(stringResource(R.string.listen_status_listening), color = Brand.teal, fontSize = 22.sp)
                        // #167: an abbreviation answer carries its meaning
                        // ("RPT — repeat / report") and wraps to three lines.
                        // The theme's body line height (24.sp) is shorter than a
                        // 40.sp glyph, so wrapped lines overlapped; the line
                        // height is pinned to the font in em so it scales with
                        // it, and the size steps down until the text fits the
                        // card. Short answers still land at 40.sp.
                        else -> FittedAnswer(ListenState.display)
                    }
                }

                Spacer(Modifier.height(28.dp))
                Surface(
                    onClick = {
                        if (!running) ListenService.start(context) else ListenService.toggle(context)
                    },
                    shape = CircleShape,
                    color = Brand.teal,
                    modifier = Modifier.size(84.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        val showPause = running && !paused
                        Icon(
                            if (showPause) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (showPause) stringResource(R.string.listen_pause) else stringResource(R.string.common_play),
                            tint = Brand.navy,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    when {
                        running && !paused -> stringResource(R.string.listen_status_running)
                        paused -> stringResource(R.string.listen_paused_tap_to_resume)
                        else -> stringResource(R.string.listen_ready)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = Brand.textSecondary
                )
            }
        }
    }

    if (showSettings) {
        SessionSettingsOverlay(scope = SettingsMode.LISTEN, onClose = { showSettings = false })
    }
    }
}

@Composable
private fun <T> ChipRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    // Scrolls sideways: the four answer-gap tiers carry their timings in
    // their labels and do not all fit across a phone.
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(label(option)) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Brand.teal,
                    selectedLabelColor = Brand.navy,
                    labelColor = Brand.textSecondary
                )
            )
        }
    }
}

/**
 * The revealed answer at 40.sp, stepping down (to no less than 18.sp) only
 * when the laid-out text would overflow the card, so single characters and
 * words keep their size while "RPT — repeat / report" shrinks and wraps
 * legibly (#167). Measured with onTextLayout rather than a text auto-size
 * API so it compiles against any Compose version the app has shipped on.
 */
@Composable
private fun FittedAnswer(text: String) {
    var size by remember(text) { mutableStateOf(40f) }
    Text(
        text,
        color = Brand.textPrimary,
        fontSize = size.sp,
        lineHeight = 1.2.em,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(16.dp),
        onTextLayout = { result ->
            if (result.didOverflowHeight && size > 18f) size = (size - 2f).coerceAtLeast(18f)
        }
    )
}
