package app.anothermorsetrainer

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.anothermorsetrainer.morsekit.DailyDit
import app.anothermorsetrainer.morsekit.DailyDitGame
import app.anothermorsetrainer.morsekit.DailyDitOutcome
import app.anothermorsetrainer.morsekit.DailyDitSubmission
import app.anothermorsetrainer.morsekit.DailyDitTile
import app.anothermorsetrainer.morsekit.MorseCode
import app.anothermorsetrainer.morsekit.MorseItem
import app.anothermorsetrainer.morsekit.MorseTiming
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val TILE_CORRECT = Color(0xFF3D9E5C)
private val TILE_PRESENT = Color(0xFFCAA033)

/**
 * **Daily Dit** — the day's five-letter word, sent in Morse, the same for
 * everyone (#155).
 *
 * The screen is the game: a play button, the grid of what you've guessed, and a
 * box to type the next one. Speed is chosen before the first guess and then
 * belongs to the day — the ladder walks it down as listens and wrong guesses
 * are spent (#168), and the slowest speed you heard it at is what the share
 * text brags about.
 *
 * Ported from MorseTrainerApp/DailyDitView.swift, by way of the Android tree.
 * Desktop: the iOS `ShareLink` (Android's share sheet) becomes a small menu —
 * copy the result card image to the clipboard, or save it as a PNG — and the
 * Copy button puts the same card on the clipboard with the share text as its
 * fallback flavour ([DailyDitShareCard.copy], #266); game state lives in
 * [DailyDitStore] rather than on an `AppModel`.
 */
@Composable
fun DailyDitScreen(
    onBack: () -> Unit,
    /** Opens Past puzzles (#333). */
    onOpenHistory: () -> Unit = {},
    /**
     * Null plays today's puzzle. A number plays that past day from the history
     * as practice (#333): the game is held here, never handed to
     * [DailyDitStore], so nothing is saved or archived and
     * [Stats.recordPracticeDay] is never reached — no streak, no account day
     * record or sync, no buddy report. Daily Dit has no leaderboard board and
     * no session record, so there is nothing else to bypass. There is no
     * Share or Copy: a practice result is not that day's result.
     */
    practicePuzzle: Int? = null
) {
    val resources = LocalResources.current
    BackHandler { onBack() }

    val player = remember { MorsePlayer() }
    val haptics = remember { Haptics() }
    // The typed guess is saveable as on Android; the game itself is in the
    // store, which is where it has to be anyway to outlive the process.
    var entry by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var showReference by rememberSaveable { mutableStateOf(false) }
    // Desktop: Android asks "Your device appears to be muted — play anyway?"
    // (#252) before a play that spends a listen with the media volume at zero.
    // A desktop JVM has no portable way to read the system output volume or
    // mute state, so that prompt is not offered here; every play just plays.

    // Desktop: the outcome of "Copy image" / "Save image…", shown briefly.
    var shareNote by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(shareNote) {
        if (shareNote != null) {
            delay(3000)
            shareNote = null
        }
    }
    val shareTitle = resources.getString(R.string.daily_dit_title)

    // The app can sit open across midnight; don't serve yesterday's grid.
    LaunchedEffect(Unit) { DailyDitStore.refresh() }
    DisposableEffect(Unit) {
        onDispose {
            player.stop()
            player.release()
        }
    }

    val isPractice = practicePuzzle != null
    // The practice game, saveable like the typed guess (as on Android) through
    // the store's own JSON codec. Unused (and never consulted) for today.
    var practiceGame by rememberSaveable(practicePuzzle, stateSaver = PracticeGameSaver) {
        mutableStateOf(
            DailyDitGame.forPuzzle(practicePuzzle ?: 1, DailyDitStore.startingWpm, DailyDitStore.hideReference)
        )
    }
    val game = if (isPractice) practiceGame else DailyDitStore.game
    // Every way the screen changes the game goes through these three, so the
    // practice path cannot reach the store.
    val listen: () -> Double = {
        if (isPractice) {
            val play = practiceGame.listen()
            practiceGame = play.game
            play.wpm
        } else {
            DailyDitStore.listen()
        }
    }
    val offer: (String) -> DailyDitSubmission = { word ->
        if (isPractice) {
            practiceGame.submit(word).also { if (it is DailyDitSubmission.Scored) practiceGame = it.game }
        } else {
            DailyDitStore.submit(word)
        }
    }
    val configure: (Double, Boolean) -> Unit = { wpm, hide ->
        if (isPractice) {
            // Practice never writes the preference, and, as for today's, stops
            // re-basing once a guess is made.
            if (practiceGame.guessesUsed == 0) {
                practiceGame = practiceGame.copy(startingWpm = wpm, hideReference = hide)
            }
        } else {
            DailyDitStore.configure(wpm, hide)
        }
    }
    val scroll = rememberScrollState()
    val entryFocus = remember { FocusRequester() }
    LaunchedEffect(game.isFinished) {
        if (!game.isFinished) runCatching { entryFocus.requestFocus() }
    }
    // "1 guess · 3 listens" — the counts the share text carries, in the same words.
    val guessesText = pluralStringResource(R.plurals.daily_dit_guesses, game.guessesUsed, game.guessesUsed)
    val listensText = pluralStringResource(R.plurals.daily_dit_listens, game.listens, game.listens)

    // Send the word. The store records the listen (a no-op once the day is
    // won) and hands back the speed to send it at.
    val playWord: () -> Unit = {
        if (game.answer.isNotEmpty()) {
            val wpm = listen()
            player.replaySound(
                MorseItem.Playable.Text(game.answer),
                Settings.sidetoneHz,
                MorseTiming(wpm)
            )
        }
    }

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
                stringResource(if (isPractice) R.string.daily_dit_practice_title else R.string.daily_dit_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Brand.textPrimary
            )
            Spacer(Modifier.weight(1f))
            if (game.isFinished && !isPractice) {
                ShareImageMenu(game = game, title = shareTitle, onNote = { shareNote = it }) { open ->
                    TextButton(onClick = open) {
                        Text(stringResource(R.string.drills_share), color = Brand.teal)
                    }
                }
            } else {
                Spacer(Modifier.width(64.dp))
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scroll)
                .widthIn(max = CONTENT_MAX_WIDTH)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            if (isPractice) {
                Column(
                    modifier = Modifier.fillMaxWidth().brandCard(cornerRadius = 12.dp).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        stringResource(R.string.daily_dit_practice_title),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Brand.tealBright
                    )
                    Text(
                        stringResource(R.string.daily_dit_practice_banner, dailyDitDateLabel(game.puzzleNumber)),
                        style = MaterialTheme.typography.labelMedium,
                        color = Brand.textSecondary
                    )
                }
            }

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
                            stringResource(R.string.daily_dit_status_solved, wpm, guessesText, listensText)
                        }
                        // The speed readout is the live ladder: listens step
                        // it as well as wrong guesses, so it can change
                        // without a guess being made.
                        DailyDitOutcome.PLAYING ->
                            stringResource(
                                R.string.daily_dit_status_playing,
                                DailyDit.formatWpm(game.currentWpm),
                                guessesText,
                                listensText
                            )
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Brand.textSecondary
                )
            }

            // Play — every play counts (#168): the store records the listen
            // and hands back the speed to send it at. (Desktop: no
            // low-volume "play anyway?" check first; see above.)
            Button(
                onClick = { playWord() },
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

            if (game.isFinished && isPractice) {
                PracticeResultCard(
                    answer = game.answer,
                    lowestWpm = game.solvedWpm?.let { DailyDit.formatWpm(it) } ?: "—",
                    guesses = game.guessesUsed,
                    listens = game.listens,
                    onPlayAgain = {
                        if (Settings.hapticsEnabled) haptics.selection()
                        practiceGame = DailyDitGame.forPuzzle(
                            game.puzzleNumber, DailyDitStore.startingWpm, DailyDitStore.hideReference
                        )
                    }
                )
            } else if (game.isFinished) {
                ResultCard(
                    shareText = game.shareText,
                    answer = game.answer,
                    lowestWpm = game.solvedWpm?.let { DailyDit.formatWpm(it) } ?: "—",
                    guesses = game.guessesUsed,
                    listens = game.listens,
                    onCopy = {
                        DailyDitShareCard.copy(game)
                        message = resources.getString(R.string.daily_dit_copied)
                    },
                    shareButton = {
                        ShareImageMenu(game = game, title = shareTitle, onNote = { shareNote = it }) { open ->
                            Button(
                                onClick = open,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Brand.navyRaised,
                                    contentColor = Brand.textPrimary
                                )
                            ) { Text(stringResource(R.string.drills_share)) }
                        }
                    },
                    note = shareNote ?: message
                )
            } else {
                // A Vail Adapter / USB MIDI key types the guess too (#251).
                // Stopping keying with five letters in guesses; a keyed guess
                // that is not taken is cleared (its reason stays on screen) so
                // the next can be keyed without reaching for the screen.
                HardwareKeyInput(
                    // Letters only, and no more than five — a sixth keyed
                    // letter is dropped, the same on iOS.
                    onText = { chunk ->
                        entry = (entry + chunk).uppercase().filter { c -> c.isLetter() }.take(DailyDit.WORD_LENGTH)
                        // Keying is how you recover from a rejection too.
                        if (entry.isNotEmpty() && message != null) message = null
                    },
                    onPause = {
                        if (entry.length == DailyDit.WORD_LENGTH) {
                            message = submit(entry, haptics, offer) { entry = "" }
                            if (message != null) entry = ""
                        }
                    }
                )
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
                            message = submit(entry, haptics, offer) { entry = "" }
                        }),
                        // Desktop: the guess box has the keyboard from the
                        // start, and Return guesses (taken in the preview
                        // pass so it does not hang on the IME action).
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Brand.textPrimary,
                            unfocusedTextColor = Brand.textPrimary,
                            focusedBorderColor = Brand.teal,
                            unfocusedBorderColor = Brand.hairline,
                            cursorColor = Brand.teal
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(entryFocus)
                            .onPreviewKeyEvent { event ->
                                if (event.key == Key.Enter || event.key == Key.NumPadEnter) {
                                    if (event.type == KeyEventType.KeyDown) {
                                        message = submit(entry, haptics, offer) { entry = "" }
                                    }
                                    true
                                } else false
                            }
                    )
                    Button(
                        onClick = { message = submit(entry, haptics, offer) { entry = "" } },
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
                SetupCard(game, isPractice, configure)
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

            if (!isPractice) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .brandCard(cornerRadius = Brand.cornerRadius)
                        .clickable { onOpenHistory() }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.daily_dit_history_link),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Brand.textPrimary
                        )
                        Text(
                            stringResource(R.string.daily_dit_history_link_sub, DailyDit.HISTORY_DAYS),
                            style = MaterialTheme.typography.labelMedium,
                            color = Brand.textSecondary
                        )
                    }
                    Text("›", fontSize = 22.sp, color = Brand.textSecondary)
                }
            }

            Text(
                stringResource(
                    R.string.daily_dit_how_it_works,
                    DailyDit.LISTENS_PER_SPEED_STEP,
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
private fun submit(
    entry: String,
    haptics: Haptics,
    offer: (String) -> DailyDitSubmission,
    onAccepted: () -> Unit
): String? =
    when (val result = offer(entry)) {
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
private fun ResultStat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Brand.textPrimary
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = Brand.textSecondary
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
private fun SetupCard(game: DailyDitGame, isPractice: Boolean, configure: (Double, Boolean) -> Unit) {
    val haptics = remember { Haptics() }
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
                DailyDit.LISTENS_PER_SPEED_STEP,
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
                        val selected = game.startingWpm == speed
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(CircleShape)
                                .background(if (selected) Brand.teal else Brand.navyRaised)
                                .clickable {
                                    if (Settings.hapticsEnabled) haptics.selection()
                                    configure(speed, game.hideReference)
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
                    stringResource(
                        if (isPractice) R.string.daily_dit_hide_chart_sub_practice else R.string.daily_dit_hide_chart_sub
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = Brand.textSecondary
                )
            }
            Switch(
                checked = game.hideReference,
                onCheckedChange = { configure(game.startingWpm, it) },
                colors = SwitchDefaults.colors(checkedTrackColor = Brand.teal)
            )
        }
    }
}

@Composable
private fun ResultCard(
    shareText: String,
    answer: String,
    lowestWpm: String,
    guesses: Int,
    listens: Int,
    onCopy: () -> Unit,
    /** Desktop: the Share control, a button that opens [ShareImageMenu]. */
    shareButton: @Composable () -> Unit,
    note: String?
) {
    Column(
        modifier = Modifier.fillMaxWidth().brandCard(cornerRadius = Brand.cornerRadius).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            stringResource(R.string.daily_dit_solid_copy),
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
        // The three numbers the day is scored on (#168): the slowest speed the
        // word was heard at, and what it cost in guesses and listens.
        Row(modifier = Modifier.fillMaxWidth()) {
            ResultStat(lowestWpm, stringResource(R.string.daily_dit_result_lowest), Modifier.weight(1f))
            ResultStat(
                guesses.toString(),
                pluralStringResource(R.plurals.daily_dit_result_guess_label, guesses),
                Modifier.weight(1f)
            )
            ResultStat(
                listens.toString(),
                pluralStringResource(R.plurals.daily_dit_result_listen_label, listens),
                Modifier.weight(1f)
            )
        }
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
            Box(modifier = Modifier.weight(1f)) { shareButton() }
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

/**
 * Desktop: the share control's menu. There is no system share sheet to hand
 * the result card to, so [anchor] (given the "open" action) opens a menu that
 * copies the card image, with the share text alongside, to the clipboard, or
 * saves it as a PNG; [onNote] gets what to say about it.
 */
@Composable
private fun ShareImageMenu(
    game: DailyDitGame,
    title: String,
    onNote: (String?) -> Unit,
    anchor: @Composable (open: () -> Unit) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        anchor { expanded = true }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(DesktopCopy.COPY_IMAGE) },
                onClick = {
                    expanded = false
                    if (DailyDitShareCard.share(game, title)) onNote(DesktopCopy.COPIED)
                }
            )
            DropdownMenuItem(
                text = { Text(DesktopCopy.SAVE_IMAGE) },
                onClick = {
                    expanded = false
                    if (DailyDitShareCard.save(game) != null) onNote(DesktopCopy.SAVED)
                }
            )
        }
    }
}

/** The practice game, through [DailyDitStore]'s JSON codec (#333). */
private val PracticeGameSaver: Saver<DailyDitGame, String> = Saver(
    save = { DailyDitStore.encode(it) },
    restore = { runCatching { DailyDitStore.decode(it) }.getOrNull() }
)

/**
 * A practice result (#333): the word and its three numbers, then Play it
 * again. No share text, Copy or Share — it isn't that day's result, and a
 * share card would read as if it were.
 */
@Composable
private fun PracticeResultCard(
    answer: String,
    lowestWpm: String,
    guesses: Int,
    listens: Int,
    onPlayAgain: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().brandCard(cornerRadius = Brand.cornerRadius).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            stringResource(R.string.daily_dit_solid_copy),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = Brand.textPrimary
        )
        Text(answer, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Brand.tealBright)
        Row(modifier = Modifier.fillMaxWidth()) {
            ResultStat(lowestWpm, stringResource(R.string.daily_dit_result_lowest), Modifier.weight(1f))
            ResultStat(
                guesses.toString(),
                pluralStringResource(R.plurals.daily_dit_result_guess_label, guesses),
                Modifier.weight(1f)
            )
            ResultStat(
                listens.toString(),
                pluralStringResource(R.plurals.daily_dit_result_listen_label, listens),
                Modifier.weight(1f)
            )
        }
        Button(
            onClick = onPlayAgain,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Brand.teal, contentColor = Brand.navy)
        ) { Text(stringResource(R.string.daily_dit_practice_again)) }
        Text(
            stringResource(R.string.daily_dit_practice_note),
            style = MaterialTheme.typography.labelMedium,
            color = Brand.textSecondary
        )
    }
}
