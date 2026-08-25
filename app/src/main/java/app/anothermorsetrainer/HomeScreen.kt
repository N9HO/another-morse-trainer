package app.anothermorsetrainer

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Abc
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A tappable home-menu tile. */
private data class HomeItem(
    val title: String,
    val tagline: String,
    val icon: ImageVector,
    val onClick: () -> Unit
)

/** The app's landing menu: pick a training mode. Styled to match the iOS IntroView. */
@Composable
fun HomeScreen(
    onPickJourney: () -> Unit,
    onPickQuiz: (QuizMode) -> Unit,
    onPickPileup: () -> Unit,
    onPickExam: () -> Unit,
    onPickListen: () -> Unit,
    onPickHeadCopy: () -> Unit,
    onPickTypeIt: () -> Unit,
    onPickQrq: () -> Unit,
    onPickRapidFire: () -> Unit,
    onPickStory: () -> Unit,
    onPickSending: () -> Unit,
    onPickRepeater: () -> Unit,
    onPickReference: () -> Unit,
    onPickSettings: () -> Unit,
    onPickStats: () -> Unit
) {
    val modeIcons = mapOf(
        "Characters" to Icons.Filled.Abc,
        "Common Words" to Icons.Filled.TextFields,
        "Abbreviations" to Icons.AutoMirrored.Filled.Chat,
        "Q-Codes" to Icons.Filled.QuestionAnswer,
        "Prosigns" to Icons.Filled.Podcasts,
        "Confusion Drill" to Icons.Filled.SwapHoriz
    )
    val modeTaglines = mapOf(
        "Characters" to "Core Koch drill",
        "Common Words" to "Whole ham words",
        "Abbreviations" to "CW abbreviations",
        "Q-Codes" to "Q-signal shorthand",
        "Prosigns" to "Run-together signals",
        "Confusion Drill" to "Drill your mix-ups"
    )
    val items = listOf(
        HomeItem("Journey", "Leveled path", Icons.Filled.Map, onPickJourney)
    ) + QUIZ_MODES.map { mode ->
        HomeItem(
            mode.title,
            modeTaglines[mode.title] ?: mode.subtitle,
            modeIcons[mode.title] ?: Icons.Filled.Abc
        ) { onPickQuiz(mode) }
    } + HomeItem("Head Copy", "Copy in your head", Icons.Filled.Psychology, onPickHeadCopy) +
        HomeItem("Type It", "Free-recall typing", Icons.Filled.Keyboard, onPickTypeIt) +
        HomeItem("QRQ Speed", "High-speed copy", Icons.Filled.Bolt, onPickQrq) +
        HomeItem("Rapid Fire", "Back-to-back copy", Icons.Filled.FlashOn, onPickRapidFire) +
        HomeItem("Sending Practice", "Key it back", Icons.Filled.Vibration, onPickSending) +
        HomeItem("Repeater", "Live over the network", Icons.Filled.Wifi, onPickRepeater) +
        HomeItem("Short Stories", "Continuous copy", Icons.AutoMirrored.Filled.MenuBook, onPickStory) +
        HomeItem("Pileup Runner", "Work a CW pileup", Icons.Filled.RecordVoiceOver, onPickPileup) +
        HomeItem("Code Exam", "ARRL/FCC code exam", Icons.Filled.WorkspacePremium, onPickExam) +
        HomeItem("Listen & Learn", "Hands-free, eyes-free", Icons.Filled.Headphones, onPickListen) +
        HomeItem("Reference", "Look it up", Icons.AutoMirrored.Filled.ListAlt, onPickReference)

    CenteredContent {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 12.dp, bottom = 32.dp)
        ) {
            // Top bar: Stats + Settings, like the iOS toolbar.
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onPickStats) {
                    Icon(Icons.Filled.BarChart, contentDescription = "Progress", tint = Brand.teal)
                }
                IconButton(onClick = onPickSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = Brand.teal)
                }
            }

            Spacer(Modifier.height(8.dp))
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.size(112.dp)
                )
                Text(
                    text = "Another Morse Trainer",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = Brand.textPrimary,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "A proud part of the Carrier Wave ecosystem.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Brand.textSecondary,
                    textAlign = TextAlign.Center
                )
                if (Stats.currentStreak > 0) {
                    Spacer(Modifier.height(12.dp))
                    StreakBadge(Stats.currentStreak)
                }
            }

            Spacer(Modifier.height(24.dp))

            // Two-column tile grid (matches iOS mode picker).
            items.chunked(2).forEach { pair ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    pair.forEach { item -> ModeTile(item, Modifier.weight(1f)) }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(14.dp))
            }
        }
    }
}

@Composable
private fun StreakBadge(days: Int) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .brandCard(cornerRadius = 24.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("🔥  $days day streak", color = Brand.textPrimary, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ModeTile(item: HomeItem, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .heightIn(min = 132.dp)
            .clip(RoundedCornerShape(Brand.cornerRadius))
            .brandCard()
            .clickable(onClick = item.onClick)
            .padding(horizontal = 12.dp, vertical = 16.dp)
    ) {
        Box(
            modifier = Modifier.size(46.dp).clip(CircleShape).background(Brand.navyRaised),
            contentAlignment = Alignment.Center
        ) {
            Icon(item.icon, contentDescription = null, tint = Brand.teal, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text(
            item.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Brand.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Text(
            item.tagline,
            fontSize = 12.sp,
            color = Brand.textSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
