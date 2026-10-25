package app.anothermorsetrainer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.anothermorsetrainer.morsekit.DailyDit
import app.anothermorsetrainer.morsekit.DailyDitGame
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * **Past puzzles** — the last [DailyDit.HISTORY_DAYS] Daily Dits (#333).
 *
 * Each day shows its puzzle number and date and how it was left: copied (with
 * the result as it was played that day, from [DailyDitStore.history]),
 * started and not solved, or missed. Any past day opens as practice through
 * [onPractice] — a missed one to catch up on, a copied one to replay — and
 * practice never reaches the store, so it counts toward no streak and never
 * changes the result shown here. Today's row goes back to today's game.
 *
 * Ported from MorseTrainerApp/DailyDitHistoryView.swift, by way of the
 * Android tree.
 */
@Composable
fun DailyDitHistoryScreen(
    onBack: () -> Unit,
    onOpenToday: () -> Unit,
    onPractice: (Int) -> Unit
) {
    BackHandler { onBack() }
    // The app can sit open across midnight; the list moves with the day.
    LaunchedEffect(Unit) { DailyDitStore.refresh() }

    val today = DailyDitStore.game.puzzleNumber
    val history = DailyDitStore.history

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text(stringResource(R.string.common_back), color = Brand.teal)
            }
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(R.string.daily_dit_history_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Brand.textPrimary
            )
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(64.dp))
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth().widthIn(max = CONTENT_MAX_WIDTH),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
        ) {
            item {
                Text(
                    stringResource(R.string.daily_dit_history_intro, DailyDit.HISTORY_DAYS),
                    style = MaterialTheme.typography.labelMedium,
                    color = Brand.textSecondary
                )
            }
            items(DailyDit.historyPuzzles(today), key = { it }) { number ->
                val isToday = number == today
                HistoryRow(
                    number = number,
                    game = if (isToday) DailyDitStore.game else history[number],
                    isToday = isToday,
                    onClick = { if (isToday) onOpenToday() else onPractice(number) }
                )
            }
        }
    }
}

@Composable
private fun HistoryRow(number: Int, game: DailyDitGame?, isToday: Boolean, onClick: () -> Unit) {
    val solved = game?.isFinished == true
    val started = game?.hasStarted == true
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .brandCard(cornerRadius = 12.dp)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (solved) "✓" else if (started) "◐" else "○",
            style = MaterialTheme.typography.titleMedium,
            color = if (solved) Brand.tealBright else Brand.textSecondary,
            modifier = Modifier.width(28.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.daily_dit_history_row, number, dailyDitDateLabel(number)),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Brand.textPrimary
            )
            Text(
                historyStatus(game, isToday),
                style = MaterialTheme.typography.labelMedium,
                color = Brand.textSecondary
            )
        }
        Text(
            stringResource(
                when {
                    isToday -> R.string.daily_dit_history_today
                    solved -> R.string.daily_dit_history_replay
                    else -> R.string.daily_dit_history_play
                }
            ),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (isToday) Brand.textSecondary else Brand.teal
        )
    }
}

/** The day as it was left — the stored result, never a practice one. */
@Composable
private fun historyStatus(game: DailyDitGame?, isToday: Boolean): String {
    if (game == null || !game.hasStarted) {
        return stringResource(if (isToday) R.string.daily_dit_history_not_played else R.string.daily_dit_history_missed)
    }
    val guesses = pluralStringResource(R.plurals.daily_dit_guesses, game.guessesUsed, game.guessesUsed)
    val listens = pluralStringResource(R.plurals.daily_dit_listens, game.listens, game.listens)
    return if (game.isFinished) {
        stringResource(
            R.string.daily_dit_status_solved,
            game.solvedWpm?.let { DailyDit.formatWpm(it) } ?: "",
            guesses,
            listens
        )
    } else {
        stringResource(R.string.daily_dit_history_unsolved, guesses, listens)
    }
}

/** "Wed, Oct 7" — the day everyone played a puzzle. */
internal fun dailyDitDateLabel(puzzleNumber: Int): String =
    DailyDit.date(puzzleNumber).format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault()))
