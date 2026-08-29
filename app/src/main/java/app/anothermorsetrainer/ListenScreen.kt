package app.anothermorsetrainer

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Hands-free, eyes-free practice — UI controller only. The actual loop runs in
 * [ListenService] (a foreground service) so it keeps playing with the screen
 * locked; this screen reads [ListenState] for display and sends start/pause/
 * stop commands. Leaving the screen stops the service (matches iOS, which ends
 * Listen mode when you leave it).
 */
@Composable
fun ListenScreen(onBack: () -> Unit) {
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
            TextButton(onClick = { leave() }) { Text("‹ Back", color = Brand.teal) }
            SessionSettingsButton { showSettings = true }
        }

        CenteredContent {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(8.dp))
                Text("Listen & Learn", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Brand.textPrimary)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Hear the code, then the answer spoken aloud — no tapping. Keeps playing with the screen locked.",
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
                        "${ListenState.itemsHeard} heard" +
                            (left?.let { " · %d:%02d left".format(it / 60, it % 60) } ?: ""),
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
                            ListenState.finishedNote ?: "Tap play to start a hands-free loop.",
                            color = if (ListenState.finishedNote != null) Brand.teal else Brand.textSecondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(24.dp)
                        )
                        paused -> Text("paused", color = Brand.textSecondary, fontSize = 20.sp)
                        ListenState.playing -> Text("🎧  listening…", color = Brand.teal, fontSize = 22.sp)
                        else -> Text(
                            ListenState.display,
                            color = Brand.textPrimary,
                            fontSize = 40.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(16.dp)
                        )
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
                            contentDescription = if (showPause) "Pause" else "Play",
                            tint = Brand.navy,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    when {
                        running && !paused -> "Listening — lock the screen and keep going"
                        paused -> "Paused — tap to resume"
                        else -> "Ready"
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
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
