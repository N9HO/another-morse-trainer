package app.anothermorsetrainer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The shared leaderboard's first-play prompt (#226): a game that is about to
 * start, on an install that does not share scores and has not said "Don't
 * ask again", asks once whether to turn sharing on. It is asked at the
 * game's own Start (and Play again) button, never mid-game, and at most once
 * per start attempt; whichever answer is given, the game then starts.
 *
 * The prompt writes the same [Settings] Settings › Leaderboard does, so
 * turning sharing on here is exactly the switch there, and the run about to
 * begin is registered by the screen's `startGame()` the way any opted-in run
 * is. The iOS twin is `LeaderboardOptInPrompt.swift`.
 *
 * A screen keeps "the prompt is up" under `rememberSaveable`, so a rotation
 * or a process death while it is open neither loses nor repeats it.
 */
object LeaderboardOptIn {
    /**
     * Whether a game's Start should first ask about sharing scores: sharing
     * is off, the question has not been answered, and this build could
     * actually post (an unconfigured build is never asked).
     */
    fun shouldOffer(): Boolean =
        LeaderboardClient.isConfigured && !Settings.leaderboardEnabled && !Settings.leaderboardPromptDismissed
}

/** The dialog itself. [onDone] starts the game and runs on every button. */
@Composable
fun LeaderboardOptInDialog(onDone: () -> Unit) {
    AlertDialog(
        // Tapping outside or Back is "Not now": the game still starts.
        onDismissRequest = onDone,
        containerColor = Brand.navyElevated,
        title = { Text(stringResource(R.string.leaderboard_optin_title), color = Brand.textPrimary) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.leaderboard_optin_body), color = Brand.textSecondary)
                Spacer(Modifier.height(12.dp))
                // Edits the setting as typed, like the field in Settings, so
                // the name is there whichever button follows.
                OutlinedTextField(
                    value = Settings.leaderboardName,
                    onValueChange = { Settings.updateLeaderboardName(it) },
                    label = { Text(stringResource(R.string.settings_leaderboard_name)) },
                    placeholder = { Text(stringResource(R.string.settings_leaderboard_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                Settings.updateLeaderboardEnabled(true)   // also answers the prompt for good
                onDone()
            }) { Text(stringResource(R.string.leaderboard_optin_share), color = Brand.teal, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = {
                    Settings.dismissLeaderboardPrompt()
                    onDone()
                }) { Text(stringResource(R.string.leaderboard_optin_never), color = Brand.textSecondary) }
                TextButton(onClick = onDone) {
                    Text(stringResource(R.string.leaderboard_optin_not_now), color = Brand.teal)
                }
            }
        }
    )
}
