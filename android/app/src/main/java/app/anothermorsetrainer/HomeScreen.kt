package app.anothermorsetrainer

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Abc
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Print
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
import androidx.compose.ui.res.stringResource
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
    onPickStartHere: () -> Unit,
    onPickJourney: () -> Unit,
    onPickQuiz: (QuizMode) -> Unit,
    onPickPileup: () -> Unit,
    onPickContest: () -> Unit,
    onPickExam: () -> Unit,
    onPickListen: () -> Unit,
    onPickHeadCopy: () -> Unit,
    onPickTypeIt: () -> Unit,
    onPickQrq: () -> Unit,
    onPickRapidFire: () -> Unit,
    onPickStory: () -> Unit,
    onPickSending: () -> Unit,
    onPickSendingDrills: () -> Unit,
    onPickRepeater: () -> Unit,
    onPickCwDecoder: () -> Unit,
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
        "Characters" to stringResource(R.string.home_core_koch_drill),
        "Common Words" to stringResource(R.string.home_whole_ham_words),
        "Abbreviations" to stringResource(R.string.home_cw_abbreviations),
        "Q-Codes" to stringResource(R.string.home_q_signal_shorthand),
        "Prosigns" to stringResource(R.string.home_run_together_signals),
        "Confusion Drill" to stringResource(R.string.home_drill_your_mix_ups)
    )
    val items = listOf(
        HomeItem(stringResource(R.string.mode_journey), stringResource(R.string.home_leveled_path), Icons.Filled.Map, onPickJourney)
    ) + QUIZ_MODES.map { mode ->
        HomeItem(
            mode.title,
            modeTaglines[mode.title] ?: mode.subtitle,
            modeIcons[mode.title] ?: Icons.Filled.Abc
        ) { onPickQuiz(mode) }
    } + HomeItem(stringResource(R.string.mode_head_copy), stringResource(R.string.common_copy_in_your_head), Icons.Filled.Psychology, onPickHeadCopy) +
        HomeItem(stringResource(R.string.mode_type_it), stringResource(R.string.common_free_recall_typing), Icons.Filled.Keyboard, onPickTypeIt) +
        HomeItem(stringResource(R.string.mode_qrq_speed), stringResource(R.string.common_high_speed_copy), Icons.Filled.Bolt, onPickQrq) +
        HomeItem(stringResource(R.string.mode_rapid_fire), stringResource(R.string.home_back_to_back_copy), Icons.Filled.FlashOn, onPickRapidFire) +
        HomeItem(stringResource(R.string.mode_sending_practice), stringResource(R.string.common_key_it_back), Icons.Filled.Vibration, onPickSending) +
        HomeItem(stringResource(R.string.mode_sending_drills), stringResource(R.string.home_printable_sheets), Icons.Filled.Print, onPickSendingDrills) +
        HomeItem(stringResource(R.string.mode_repeater), stringResource(R.string.home_live_over_the_network), Icons.Filled.Wifi, onPickRepeater) +
        HomeItem(stringResource(R.string.mode_short_stories), stringResource(R.string.home_continuous_copy), Icons.AutoMirrored.Filled.MenuBook, onPickStory) +
        HomeItem(stringResource(R.string.mode_pileup_runner), stringResource(R.string.home_work_a_cw_pileup), Icons.Filled.RecordVoiceOver, onPickPileup) +
        HomeItem(stringResource(R.string.mode_contest), stringResource(R.string.home_timed_contest_runs), Icons.Filled.EmojiEvents, onPickContest) +
        HomeItem(stringResource(R.string.mode_code_exam), stringResource(R.string.home_arrl_fcc_code_exam), Icons.Filled.WorkspacePremium, onPickExam) +
        HomeItem(stringResource(R.string.mode_listen_and_learn), stringResource(R.string.home_hands_free_eyes_free), Icons.Filled.Headphones, onPickListen) +
        HomeItem(stringResource(R.string.mode_reference), stringResource(R.string.home_look_it_up), Icons.AutoMirrored.Filled.ListAlt, onPickReference)

    CenteredScrollColumn(
        contentModifier = Modifier
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp, bottom = 32.dp)
    ) {
            // Top bar: CW decoder + Stats + Settings, like the iOS toolbar.
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onPickCwDecoder) {
                    Icon(
                        Icons.Filled.GraphicEq,
                        contentDescription = stringResource(R.string.home_cw_decoder_description),
                        tint = Brand.teal
                    )
                }
                IconButton(onClick = onPickStats) {
                    Icon(Icons.Filled.BarChart, contentDescription = stringResource(R.string.common_progress), tint = Brand.teal)
                }
                IconButton(onClick = onPickSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.common_settings), tint = Brand.teal)
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
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = Brand.textPrimary,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.home_tagline),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Brand.textSecondary,
                    textAlign = TextAlign.Center
                )
                if (Stats.currentStreak > 0) {
                    Spacer(Modifier.height(12.dp))
                    StreakBadge(Stats.currentStreak)
                }
            }

            Spacer(Modifier.height(20.dp))
            // The newcomer's way in (#96): the site's guide explains how to
            // begin and why the code is fast, but nothing on the tile grid
            // said so. Always visible — as useful in week three as on day one.
            StartHereCard(onPickStartHere)

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

@Composable
private fun StartHereCard(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .brandCard(cornerRadius = 14.dp)
            .border(1.5.dp, Brand.teal.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, tint = Brand.teal)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.start_here_home_button),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Brand.textPrimary
            )
            Text(
                stringResource(R.string.start_here_home_button_sub),
                style = MaterialTheme.typography.labelSmall,
                color = Brand.textSecondary
            )
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Brand.textSecondary)
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
        Text(stringResource(R.string.home_streak, days), color = Brand.textPrimary, fontWeight = FontWeight.SemiBold)
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
