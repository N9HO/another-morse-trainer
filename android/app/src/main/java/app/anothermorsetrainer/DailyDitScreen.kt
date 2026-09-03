package app.anothermorsetrainer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.anothermorsetrainer.morsekit.DailyDit
import app.anothermorsetrainer.morsekit.DailyDitOutcome
import app.anothermorsetrainer.morsekit.DailyDitSubmission
import app.anothermorsetrainer.morsekit.DailyDitTile
import app.anothermorsetrainer.morsekit.MorseCode
import app.anothermorsetrainer.morsekit.MorseItem
import app.anothermorsetrainer.morsekit.MorseTiming
import kotlin.math.roundToInt

private val TILE_CORRECT = Color(0xFF3D9E5C)
private val TILE_PRESENT = Color(0xFFCAA033)

/**
 * **Daily Dit** — the day's five-letter word, sent in Morse, the same for
 * everyone (#155).
 *
 * The screen is the game: a play button, the grid of what you've guessed, and a
 * box to type the next one. Speed is chosen before the first guess and then
 * belongs to the day — the ladder walks it down as guesses are spent, and the
 * speed you were still at when you got it is what the share text brags about.
 *
 * Ported from MorseTrainerApp/DailyDitView.swift. The iOS `ShareLink` becomes
 * an ACTION_SEND chooser and the pasteboard write becomes [ClipboardManager];
 * game state lives in [DailyDitStore] rather than on an `AppModel`.
 */
@Composable
fun DailyDitScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    BackHandler { onBack() }

    val player = remember { MorsePlayer() }
    val haptics = remember { Haptics(context) }
    // The typed guess survives rotation; the game itself is in the store, which
    // is where it has to be anyway to outlive the process.
    var entry by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var showReference by rememberSaveable { mutableStateOf(false) }

    // The app can sit open across midnight; don't serve yesterday's grid.
    LaunchedEffect(Unit) { DailyDitStore.refresh() }
    DisposableEffect(Unit) {
        onDispose {
            player.stop()
            player.release()
        }
    }

    val game = DailyDitStore.game
    val scroll = rememberScrollState()

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
                stringResource(R.string.daily_dit_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Brand.textPrimary
            )
            Spacer(Modifier.weight(1f))
            if (game.isFinished) {
                TextButton(onClick = { shareText(context, game.shareText, resources.getString(R.string.daily_dit_title)) }) {
                    Text(stringResource(R.string.drills_share), color = Brand.teal)
                }
            } else {
                Spacer(Modifier.width(64.dp))
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scroll)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // Header
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    stringResource(R.string.daily_dit_puzzle_number, game.puzzleNumber),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Brand.textPrimary
                )
                Text(
                    text = when (game.outcome) {
                        DailyDitOutcome.SOLVED -> {
                            val wpm = game.solvedWpm?.let { DailyDit.formatWpm(it) } ?: ""
                            stringResource(R.string.daily_dit_status_solved, game.guessesUsed, wpm)
                        }
                        DailyDitOutcome.LOST ->
                            stringResource(R.string.daily_dit_status_lost, game.answer)
                        DailyDitOutcome.PLAYING ->
                            stringResource(
                                R.string.daily_dit_status_playing,
                                DailyDit.formatWpm(game.currentWpm),
                                game.guessesLeft
                            )
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Brand.textSecondary
                )
            }

            // Play — replays are free, only guesses count.
            Button(
                onClick = {
                    if (game.answer.isNotEmpty()) {
                        player.replaySound(
                            MorseItem.Playable.Text(game.answer),
                            Settings.sidetoneHz,
                            MorseTiming(game.currentWpm)
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth().height(54.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Brand.teal,
                    contentColor = Brand.navy
                )
            ) {
                Text(
                    stringResource(
                        if (game.isFinished) R.string.daily_dit_hear_again else R.string.daily_dit_play
                    ),
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (game.isFinished) {
                ResultCard(
                    shareText = game.shareText,
                    answer = game.answer,
                    solved = game.outcome == DailyDitOutcome.SOLVED,
                    onCopy = {
                        copyText(context, game.shareText)
                        message = resources.getString(R.string.daily_dit_copied)
                    },
                    onShare = { shareText(context, game.shareText, resources.getString(R.string.daily_dit_title)) },
                    note = message
                )
            } else {
                // Guess entry
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = entry,
                        onValueChange = {
                            entry = it.uppercase().filter { c -> c.isLetter() }.take(DailyDit.WORD_LENGTH)
                            // Typing is how you recover from a rejection.
                            if (message != null) message = null
                        },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.daily_dit_five_letters), color = Brand.textSecondary) },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = {
                            message = submit(entry, haptics) { entry = "" }
                        }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Brand.textPrimary,
                            unfocusedTextColor = Brand.textPrimary,
                            focusedBorderColor = Brand.teal,
                            unfocusedBorderColor = Brand.hairline,
                            cursorColor = Brand.teal
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = { message = submit(entry, haptics) { entry = "" } },
                        enabled = entry.length == DailyDit.WORD_LENGTH,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Brand.tealBright,
                            contentColor = Brand.navy
                        )
                    ) { Text(stringResource(R.string.daily_dit_guess)) }
                }
                message?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = Color(0xFFE0A33A))
                }
            }

            // The grid
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (round in game.rounds) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        round.guess.forEachIndexed { i, letter ->
                            Tile(letter, round.tiles.getOrNull(i), Modifier.weight(1f))
                        }
                        SpeedTag(round.wpm)
                    }
                }
                if (!game.isFinished) {
                    // The live row, so typing lands in the grid rather than off
                    // to the side of it.
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        for (i in 0 until DailyDit.WORD_LENGTH) {
                            Tile(entry.getOrNull(i), null, Modifier.weight(1f))
                        }
                        SpeedTag(game.currentWpm)
                    }
                }
            }

            if (!game.isFinished) {
                val dead = game.eliminatedLetters
                Column(
                    modifier = Modifier.fillMaxWidth().brandCard(cornerRadius = 12.dp).padding(12.dp)
                ) {
                    Text(
                        stringResource(R.string.daily_dit_ruled_out),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Brand.textSecondary
                    )
                    Text(
                        text = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".filter { it in dead }.ifEmpty { "—" },
                        fontFamily = FontFamily.Monospace,
                        color = Brand.textSecondary
                    )
                }
            }

            if (game.guessesUsed == 0) {
                SetupCard()
            } else {
                Text(
                    stringResource(
                        R.string.daily_dit_started_at,
                        DailyDit.formatWpm(game.startingWpm)
                    ) + if (game.hideReference) stringResource(R.string.daily_dit_no_reference_suffix) else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = Brand.textSecondary
                )
            }

            if (!game.hideReference) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .brandCard(cornerRadius = Brand.cornerRadius)
                        .clickable { showReference = !showReference }
                        .padding(14.dp)
                ) {
                    Text(
                        stringResource(R.string.daily_dit_chart),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Brand.textPrimary
                    )
                    if (showReference) {
                        Spacer(Modifier.height(10.dp))
                        // Fixed columns rather than a flow layout: the chart
                        // is a reference table, and a ragged right edge makes
                        // it harder to scan mid-puzzle.
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (row in ('A'..'Z').chunked(4)) {
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    for (letter in row) {
                                        Row(
                                            modifier = Modifier.weight(1f),
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Text(
                                                letter.toString(),
                                                fontWeight = FontWeight.Bold,
                                                color = Brand.textPrimary
                                            )
                                            Text(
                                                MorseCode.pattern(letter) ?: "",
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 13.sp,
                                                color = Brand.teal
                                            )
                                        }
                                    }
                                    // Keep the last, short row's columns the
                                    // same width as every other row's.
                                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                }
            }

            Text(
                stringResource(
                    R.string.daily_dit_how_it_works,
                    DailyDit.GUESSES_PER_SPEED_STEP,
                    DailyDit.SPEED_STEP_WPM.roundToInt()
                ),
                style = MaterialTheme.typography.labelMedium,
                color = Brand.textSecondary
            )
        }
    }
}

/** Offer a guess; returns the message to show, or null when it was accepted. */
private fun submit(entry: String, haptics: Haptics, onAccepted: () -> Unit): String? =
    when (val result = DailyDitStore.submit(entry)) {
        is DailyDitSubmission.Scored -> {
            onAccepted()
            if (Settings.hapticsEnabled) haptics.selection()
            null
        }
        is DailyDitSubmission.Rejected -> {
            if (Settings.hapticsEnabled) haptics.error()
            result.reason.message
        }
    }

@Composable
private fun Tile(letter: Char?, tile: DailyDitTile?, modifier: Modifier = Modifier) {
    val fill = when (tile) {
        DailyDitTile.CORRECT -> TILE_CORRECT
        DailyDitTile.PRESENT -> TILE_PRESENT
        DailyDitTile.ABSENT -> Brand.navyRaised
        null -> Brand.navyElevated
    }
    // Colour carries the result, but never alone: the share grid is emoji and
    // every tile also says its state out loud to TalkBack.
    val shown = letter?.toString() ?: ""
    val spoken = when {
        letter == null -> stringResource(R.string.daily_dit_tile_empty)
        tile == DailyDitTile.CORRECT -> stringResource(R.string.daily_dit_tile_correct, shown)
        tile == DailyDitTile.PRESENT -> stringResource(R.string.daily_dit_tile_present, shown)
        tile == DailyDitTile.ABSENT -> stringResource(R.string.daily_dit_tile_absent, shown)
        else -> stringResource(R.string.daily_dit_tile_pending, shown)
    }
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(fill)
            .then(
                if (tile == null) Modifier.border(1.5.dp, Brand.hairline, RoundedCornerShape(10.dp))
                else Modifier
            )
            .clearAndSetSemantics { contentDescription = spoken },
        contentAlignment = Alignment.Center
    ) {
        Text(
            shown,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = Brand.textPrimary
        )
    }
}

@Composable
private fun SpeedTag(wpm: Double) {
    Text(
        DailyDit.formatWpm(wpm),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = Brand.textSecondary,
        modifier = Modifier
            .width(28.dp)
            .clearAndSetSemantics {
                contentDescription = "sent at ${DailyDit.formatWpm(wpm)} words per minute"
            }
    )
}

@Composable
private fun SetupCard() {
    // Hoisted, not read inside the remember lambda: LocalContext.current is a
    // @Composable read, and the lambda is not one.
    val context = LocalContext.current
    val haptics = remember { Haptics(context) }
    Column(
        modifier = Modifier.fillMaxWidth().brandCard(cornerRadius = Brand.cornerRadius).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            stringResource(R.string.daily_dit_starting_speed),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = Brand.textPrimary
        )
        Text(
            stringResource(
                R.string.daily_dit_ladder_explainer,
                DailyDit.SPEED_STEP_WPM.roundToInt(),
                DailyDit.GUESSES_PER_SPEED_STEP,
                DailyDit.MINIMUM_WPM.roundToInt()
            ),
            style = MaterialTheme.typography.labelMedium,
            color = Brand.textSecondary
        )
        // Four to a row: a single Row of seven crushes them into unreadable
        // slivers on a phone, and equal weights keep every one tappable.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (row in DailyDit.startingSpeeds.chunked(4)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (speed in row) {
                        val selected = DailyDitStore.game.startingWpm == speed
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(CircleShape)
                                .background(if (selected) Brand.teal else Brand.navyRaised)
                                .clickable {
                                    if (Settings.hapticsEnabled) haptics.selection()
                                    DailyDitStore.configure(speed, DailyDitStore.game.hideReference)
                                }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                DailyDit.formatWpm(speed),
                                fontWeight = FontWeight.SemiBold,
                                color = if (selected) Brand.navy else Brand.textPrimary
                            )
                        }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.daily_dit_hide_chart),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Brand.textPrimary
                )
                Text(
                    stringResource(R.string.daily_dit_hide_chart_sub),
                    style = MaterialTheme.typography.labelMedium,
                    color = Brand.textSecondary
                )
            }
            Switch(
                checked = DailyDitStore.game.hideReference,
                onCheckedChange = {
                    DailyDitStore.configure(DailyDitStore.game.startingWpm, it)
                },
                colors = SwitchDefaults.colors(checkedTrackColor = Brand.teal)
            )
        }
    }
}

@Composable
private fun ResultCard(
    shareText: String,
    answer: String,
    solved: Boolean,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    note: String?
) {
    Column(
        modifier = Modifier.fillMaxWidth().brandCard(cornerRadius = Brand.cornerRadius).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            stringResource(if (solved) R.string.daily_dit_solid_copy else R.string.daily_dit_tomorrow),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = Brand.textPrimary
        )
        Text(
            answer,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            color = Brand.tealBright
        )
        Text(
            shareText,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            color = Brand.textSecondary,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Brand.navyRaised)
                .padding(12.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = onCopy,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Brand.teal,
                    contentColor = Brand.navy
                )
            ) { Text(stringResource(R.string.daily_dit_copy)) }
            Button(
                onClick = onShare,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Brand.navyRaised,
                    contentColor = Brand.textPrimary
                )
            ) { Text(stringResource(R.string.drills_share)) }
        }
        note?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = Brand.tealBright)
        }
        Text(
            stringResource(R.string.daily_dit_next_tomorrow),
            style = MaterialTheme.typography.labelMedium,
            color = Brand.textSecondary
        )
    }
}

private fun shareText(context: Context, text: String, subject: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, subject))
}

private fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("Daily Dit", text))
}
