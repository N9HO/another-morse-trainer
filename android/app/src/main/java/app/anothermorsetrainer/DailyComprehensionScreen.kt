package app.anothermorsetrainer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.anothermorsetrainer.morsekit.DailyComprehension
import app.anothermorsetrainer.morsekit.MorseItem

private val OPTION_LETTERS = listOf("A", "B", "C", "D")
private val RIGHT_GREEN = Color(0xFF3D9E5C)
private val WRONG_RED = Color(0xFFB34040)

/**
 * **Daily Comprehension** (#353): the day's short sentence, sent in Morse, the
 * same for everyone, answered by choosing one of four options instead of by
 * copying it exactly. Hear it as often as you like; you get one answer. It
 * counts toward the daily streak and is not ranked.
 *
 * Ported from MorseTrainerApp/DailyComprehensionView.swift. The sentence is
 * sent at the learner's own speed ([Settings.timing]). A highlighted option is
 * not final until it is locked in, whether by tap, keyboard or a hardware key
 * (key A, B, C or D, then stop keying).
 */
@Composable
fun DailyComprehensionScreen(onBack: () -> Unit) {
    BackHandler { onBack() }
    val player = remember { MorsePlayer() }
    val context = LocalContext.current
    val haptics = remember { Haptics(context) }
    // The highlighted-but-not-locked option survives rotation.
    var pending by rememberSaveable { mutableStateOf<Int?>(null) }

    LaunchedEffect(Unit) { DailyComprehensionStore.refresh() }
    DisposableEffect(Unit) {
        onDispose {
            player.stop()
            player.release()
        }
    }

    val game = DailyComprehensionStore.game
    val listensText = pluralStringResource(R.plurals.daily_comprehension_listens, game.listens, game.listens)

    fun lockIn() {
        val pick = pending ?: return
        if (DailyComprehensionStore.choose(pick)) {
            pending = null
            if (Settings.hapticsEnabled) {
                if (pick == DailyComprehensionStore.game.correctPosition) haptics.success() else haptics.error()
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.common_back), color = Brand.teal) }
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(R.string.daily_comprehension_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Brand.textPrimary
            )
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(64.dp))
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .widthIn(max = CONTENT_MAX_WIDTH)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(R.string.daily_comprehension_puzzle_number, game.puzzleNumber),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Brand.textPrimary
                )
                Text(listensText, style = MaterialTheme.typography.bodyMedium, color = Brand.textSecondary)
            }

            Button(
                onClick = {
                    val sentence = DailyComprehensionStore.listen()
                    player.replaySound(MorseItem.Playable.Text(sentence), Settings.sidetoneHz, Settings.timing())
                },
                modifier = Modifier.fillMaxWidth().height(54.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Brand.teal, contentColor = Brand.navy)
            ) {
                Text(
                    stringResource(if (game.isFinished) R.string.daily_dit_hear_again else R.string.daily_comprehension_play),
                    fontWeight = FontWeight.SemiBold
                )
            }

            Text(
                game.item.question,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Brand.textPrimary
            )

            game.options.forEachIndexed { i, text ->
                val finished = game.isFinished
                val isRight = i == game.correctPosition
                val isChosen = i == game.chosenPosition
                val selected = !finished && pending == i
                val container = when {
                    finished && isRight -> RIGHT_GREEN
                    finished && isChosen -> WRONG_RED
                    selected -> Brand.teal.copy(alpha = 0.35f)
                    else -> Brand.navyElevated
                }
                OutlinedButton(
                    onClick = { if (!finished) pending = i },
                    enabled = !finished,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) Brand.tealBright else Brand.hairline),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = container,
                        contentColor = Brand.textPrimary,
                        disabledContainerColor = container,
                        disabledContentColor = Brand.textPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "${OPTION_LETTERS[i]}: $text" }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(OPTION_LETTERS[i], fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        Text(text, modifier = Modifier.weight(1f))
                        if (finished && isRight) Text("✓", fontWeight = FontWeight.Bold)
                        else if (finished && isChosen) Text("✗", fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (game.isFinished) {
                Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        stringResource(if (game.isCorrect) R.string.daily_comprehension_correct else R.string.daily_comprehension_wrong),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Brand.textPrimary
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        game.item.sentence,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = Brand.tealBright,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.daily_comprehension_as_sent),
                        style = MaterialTheme.typography.bodySmall,
                        color = Brand.textSecondary,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                // A Vail Adapter / BLE-MIDI key answers too: key A, B, C or D,
                // then stop keying to lock it in (the Daily Dit keyed-guess model).
                HardwareKeyInput(
                    onText = { chunk ->
                        val letter = chunk.uppercase().lastOrNull { it.toString() in OPTION_LETTERS }
                        if (letter != null) pending = OPTION_LETTERS.indexOf(letter.toString())
                    },
                    onPause = { lockIn() }
                )
                Button(
                    onClick = { lockIn() },
                    enabled = pending != null,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Brand.tealBright, contentColor = Brand.navy)
                ) {
                    Text(
                        pending?.let { stringResource(R.string.daily_comprehension_answer, OPTION_LETTERS[it]) }
                            ?: stringResource(R.string.daily_comprehension_choose),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Text(
                stringResource(R.string.daily_comprehension_how_it_works),
                style = MaterialTheme.typography.bodySmall,
                color = Brand.textSecondary
            )
        }
    }
}
