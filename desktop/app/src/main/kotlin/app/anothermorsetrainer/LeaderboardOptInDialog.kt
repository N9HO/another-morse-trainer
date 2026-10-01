package app.anothermorsetrainer

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight

/**
 * The shared leaderboard's first-play prompt (#226). On the phone apps a game
 * that is about to start, on an install that does not share scores and has not
 * said "Don't ask again", asks once whether to turn sharing on, and whichever
 * answer is given, the game then starts. The iOS twin is
 * `LeaderboardOptInPrompt.swift`.
 *
 * Desktop: the desktop app is unranked — it has no way to prove a genuine
 * install, so it never posts a run — and there is nothing to opt in to.
 * [LeaderboardOptIn.shouldOffer] is always false, so no game asks. The
 * composable is kept with the same signature so the ported game screens that
 * reference it still compile; if one ever shows it, it explains
 * [DesktopCopy.UNRANKED_NOTE] with a single OK that starts the game.
 */
object LeaderboardOptIn {
    /**
     * Whether a game's Start should first ask about sharing scores.
     * Desktop: never — desktop scores are unranked.
     */
    fun shouldOffer(): Boolean = false
}

/** The dialog itself. [onDone] starts the game and runs on every exit. */
@Composable
fun LeaderboardOptInDialog(onDone: () -> Unit) {
    // Escape answers the dialog (the game starts), not the screen underneath.
    BackHandler { onDone() }
    AlertDialog(
        // Clicking outside or Escape still starts the game.
        onDismissRequest = onDone,
        containerColor = Brand.navyElevated,
        title = { Text(stringResource(R.string.leaderboard_title), color = Brand.textPrimary) },
        text = { Text(DesktopCopy.UNRANKED_NOTE, color = Brand.textSecondary) },
        confirmButton = {
            TextButton(onClick = onDone) {
                // Desktop-only copy: strings.xml has no plain "OK".
                Text("OK", color = Brand.teal, fontWeight = FontWeight.SemiBold)
            }
        }
    )
}
