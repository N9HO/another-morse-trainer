package app.anothermorsetrainer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.anothermorsetrainer.morsekit.Leaderboard

/**
 * The shared leaderboard (docs/high-scores-design.md, step 2): one board per
 * ranked mode, read straight from `GET /v1/board/{mode}`. Public and
 * read-only, so it needs neither the opt-in nor Play Integrity; a fresh
 * install can look before it ever posts. Reached from the Progress screen.
 * The iOS twin is `LeaderboardView`.
 *
 * Board numbers are the server's metric — the speed summed over correctly
 * copied items — and the lead paragraph says so, because a game's number
 * here is deliberately not the score its end card showed.
 */
@Composable
fun LeaderboardScreen(onBack: () -> Unit) {
    BackHandler { onBack() }

    val modes = Leaderboard.rankedModes
    // Which board is open, by position; saved so a process death while
    // reading comes back to the same mode.
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val modeId = modes[selected.coerceIn(0, modes.size - 1)]

    var rows by remember { mutableStateOf<List<LeaderboardClient.BoardRow>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    // Bumped by Retry so the same mode fetches again.
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(modeId, attempt) {
        loading = true
        error = null
        rows = null
        when (val r = LeaderboardClient.board(modeId)) {
            is LeaderboardClient.BoardResult.Rows -> rows = r.rows
            is LeaderboardClient.BoardResult.Failed -> error = r.message
        }
        loading = false
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.common_back), color = Brand.teal) }
            Text(
                stringResource(R.string.leaderboard_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Brand.textPrimary
            )
        }

        CenteredScrollColumn(
            contentModifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)
        ) {
            Text(
                stringResource(R.string.leaderboard_blurb),
                fontSize = 13.sp,
                color = Brand.textSecondary,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // The nine modes as pills, in the order the server lists them.
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                modes.forEachIndexed { i, id ->
                    val on = i == selected
                    Box(
                        modifier = Modifier
                            .background(if (on) Brand.teal else Brand.navyRaised, RoundedCornerShape(8.dp))
                            .clickable { selected = i }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(
                            stringResource(modeTitle(id)),
                            color = if (on) Brand.navy else Brand.textSecondary,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                            fontSize = 13.sp
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))

            val list = rows
            when {
                loading -> StatusText(stringResource(R.string.leaderboard_loading))
                error != null -> {
                    StatusText(stringResource(R.string.leaderboard_error, error ?: ""))
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { attempt += 1 }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.leaderboard_retry))
                    }
                }
                list == null || list.isEmpty() -> StatusText(stringResource(R.string.leaderboard_empty))
                else -> Column(modifier = Modifier.fillMaxWidth().brandCard(14.dp)) {
                    list.forEachIndexed { i, row ->
                        BoardRowView(row)
                        if (i < list.size - 1) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp)
                                    .height(1.dp)
                                    .background(Brand.hairline)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The home-menu title for a server mode id, so the pills read like the tiles. */
private fun modeTitle(modeId: String): Int = when (modeId) {
    "rapidFire" -> R.string.mode_rapid_fire
    "contest" -> R.string.mode_contest
    "pileup" -> R.string.mode_pileup_runner
    "invaders" -> R.string.mode_invaders
    "galaga" -> R.string.mode_galaga
    "defender" -> R.string.mode_defender
    "dungeon" -> R.string.mode_dungeon
    "frogger" -> R.string.mode_frogger
    "asteroids" -> R.string.mode_asteroids
    else -> R.string.leaderboard_title
}

@Composable
private fun StatusText(text: String) {
    Text(
        text,
        color = Brand.textSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp)
    )
}

/** Rank, name, metric, platform and day — the server's row, nothing derived. */
@Composable
private fun BoardRowView(row: LeaderboardClient.BoardRow) {
    val platform = when (row.platform) {
        "ios" -> stringResource(R.string.leaderboard_platform_ios)
        "android" -> stringResource(R.string.leaderboard_platform_android)
        else -> row.platform
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "#${row.rank}",
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = if (row.rank <= 3) Brand.tealBright else Brand.textSecondary,
            modifier = Modifier.width(44.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(row.displayName, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Brand.textPrimary)
            Text(
                listOf(platform, row.date).filter { it.isNotEmpty() }.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = Brand.textSecondary
            )
        }
        Text("${row.metric}", fontWeight = FontWeight.Bold, color = Brand.teal)
    }
}
