package app.anothermorsetrainer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * The training modes the mid-session switcher can jump between — the Android
 * twin of the iOS `TrainingMode` list behind the toolbar mode menu
 * (ContentView.swift `modeMenu`), in the same order.
 *
 * Labels are the home tiles' own: the quiz modes carry the [QuizMode.title]
 * they are keyed by in [QUIZ_MODES], everything else the `mode_*` string the
 * home screen shows, so the menu and the tile grid never disagree.
 */
enum class TrainingMode(private val quizTitle: String? = null, private val titleRes: Int = 0) {
    JOURNEY(titleRes = R.string.mode_journey),
    CHARACTERS(quizTitle = "Characters"),
    WORDS(quizTitle = "Common Words"),
    ABBREVIATIONS(quizTitle = "Abbreviations"),
    QCODES(quizTitle = "Q-Codes"),
    PROSIGNS(quizTitle = "Prosigns"),
    HEAD_COPY(titleRes = R.string.mode_head_copy),
    TYPE_IT(titleRes = R.string.mode_type_it),
    SENDING(titleRes = R.string.mode_sending_practice),
    CONFUSION(quizTitle = "Confusion Drill"),
    LISTEN(titleRes = R.string.mode_listen_and_learn),
    PILEUP(titleRes = R.string.mode_pileup_runner),
    CONTEST(titleRes = R.string.mode_contest),
    STORY(titleRes = R.string.mode_short_stories),
    EXAM(titleRes = R.string.mode_code_exam),
    QRQ(titleRes = R.string.mode_qrq_speed),
    RAPID_FIRE(titleRes = R.string.mode_rapid_fire),
    INVADERS(titleRes = R.string.mode_invaders);

    /** The menu label — the home tile's title. */
    @Composable
    fun title(): String = quizTitle ?: stringResource(titleRes)

    /** The [QUIZ_MODES] entry this mode drives through QuizScreen, if it is one. */
    val quizMode: QuizMode?
        get() = quizTitle?.let { t -> QUIZ_MODES.firstOrNull { it.title == t } }
}

/** The switcher entry a QuizScreen run belongs to, from the scope it opened with. */
fun trainingModeFor(settingsMode: SettingsMode): TrainingMode? = when (settingsMode) {
    SettingsMode.CHARACTERS -> TrainingMode.CHARACTERS
    SettingsMode.WORDS -> TrainingMode.WORDS
    SettingsMode.ABBREVIATIONS -> TrainingMode.ABBREVIATIONS
    SettingsMode.QCODES -> TrainingMode.QCODES
    SettingsMode.PROSIGNS -> TrainingMode.PROSIGNS
    SettingsMode.CONFUSION -> TrainingMode.CONFUSION
    SettingsMode.JOURNEY -> TrainingMode.JOURNEY
    SettingsMode.TYPE_IT -> TrainingMode.TYPE_IT
    SettingsMode.QRQ -> TrainingMode.QRQ
    SettingsMode.HEAD_COPY -> TrainingMode.HEAD_COPY
    SettingsMode.LISTEN -> TrainingMode.LISTEN
    SettingsMode.STORY -> TrainingMode.STORY
    SettingsMode.EXAM -> TrainingMode.EXAM
    SettingsMode.PILEUP -> TrainingMode.PILEUP
    SettingsMode.CONTEST -> TrainingMode.CONTEST
    SettingsMode.RAPID_FIRE -> TrainingMode.RAPID_FIRE
    SettingsMode.SENDING -> TrainingMode.SENDING
}

/**
 * The session countdown as a menu (iOS ContentView `sessionBar`, issue #41):
 * tap the remaining time to add five minutes or one, or to drop the limit and
 * keep going open-ended. An open-ended session shows a timer glyph instead,
 * and adding time to it starts a countdown.
 *
 * [remaining] is the screen's own countdown state — the seconds left, or null
 * for no limit — and the callbacks write it back; the ticking stays where it
 * always was, in the screen.
 */
@Composable
fun SessionTimerMenu(
    remaining: Int?,
    onAddSeconds: (Int) -> Unit,
    onRemoveLimit: () -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val description = stringResource(R.string.session_timer_adjust)
    Box {
        if (remaining != null) {
            TextButton(onClick = { open = true }) {
                Text(
                    "%d:%02d".format(remaining / 60, remaining % 60),
                    style = MaterialTheme.typography.labelMedium,
                    color = Brand.textSecondary
                )
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = description,
                    tint = Brand.textSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        } else {
            IconButton(onClick = { open = true }) {
                Icon(
                    Icons.Filled.Timer,
                    contentDescription = description,
                    tint = Brand.textSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.session_add_5_minutes)) },
                onClick = { open = false; onAddSeconds(300) }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.session_add_1_minute)) },
                onClick = { open = false; onAddSeconds(60) }
            )
            if (remaining != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.session_remove_time_limit)) },
                    onClick = { open = false; onRemoveLimit() }
                )
            }
        }
    }
}

/**
 * The mid-session mode switcher (iOS ContentView `modeMenu`, issue #42): a
 * menu of every training mode, the current one ticked. Picking another mode
 * ends the run the way the screen's own Back does — recording it — and lands
 * on that mode's setup, so the next session begins only on an explicit start.
 * The caller supplies [onSwitch] with the screen's own close-out folded in.
 */
@Composable
fun SwitchModeButton(current: TrainingMode?, onSwitch: (TrainingMode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                Icons.Filled.SwapHoriz,
                contentDescription = stringResource(R.string.session_switch_mode),
                tint = Brand.textSecondary,
                modifier = Modifier.size(20.dp)
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            TrainingMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.title()) },
                    leadingIcon = {
                        if (mode == current) {
                            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(20.dp))
                        } else {
                            Spacer(Modifier.size(20.dp))
                        }
                    },
                    onClick = {
                        open = false
                        // The session's own mode: nothing to end (iOS setMode's guard).
                        if (mode != current) onSwitch(mode)
                    }
                )
            }
        }
    }
}
