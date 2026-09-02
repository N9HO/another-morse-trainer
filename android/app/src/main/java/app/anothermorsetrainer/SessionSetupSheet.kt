package app.anothermorsetrainer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.anothermorsetrainer.morsekit.ProgressiveCharacters

/**
 * The pre-flight sheet for a training mode — the Android twin of the iOS
 * `SessionSetupSheet` in IntroView.swift.
 *
 * Picking a mode used to drop you straight into it, so the knobs that shape a
 * run (where the Koch ladder starts, which stage the Characters track is held
 * at, how long the session lasts) were only reachable by detouring through
 * Settings before starting — or mid-session, once the run was already shaped.
 * iOS asks first; this does too.
 *
 * Only the controls that apply to [settingsMode] are shown, so the sheet never
 * appears empty — see [sessionSetupHasOptions], which gates whether it is worth
 * presenting at all. Modes that own a fuller setup step of their own (Rapid
 * Fire, Pileup, Contest, the exam, Journey's map) are deliberately left alone.
 *
 * Every control writes through to the same persisted [Settings] the mid-session
 * overlay edits, so this is a shortcut to those knobs rather than a second,
 * divergent copy of them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionSetupSheet(
    title: String,
    blurb: String,
    settingsMode: SettingsMode,
    progressive: ProgressiveCharacters?,
    onStart: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Pinning a stage mutates the shared track in place, so it has no Compose
    // state of its own — bump a revision to redraw the pills after a pick.
    var stageRev by remember { mutableIntStateOf(0) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Brand.navy
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(blurb, style = MaterialTheme.typography.bodySmall, color = Brand.textSecondary)

            if (settingsMode in LADDER_MODES) {
                SetupCard(stringResource(R.string.setup_where_are_you_starting)) {
                    Proficiency.entries.forEach { level ->
                        SetupRadioRow(
                            label = level.label,
                            selected = Settings.proficiency == level
                        ) {
                            Settings.updateProficiency(level)
                            // Restart the ladder from the new seed; per-character
                            // stats and recorded confusions are kept.
                            EngineStore.reseed()
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.setup_proficiency_blurb),
                        style = MaterialTheme.typography.labelSmall,
                        color = Brand.textSecondary
                    )
                }
            }

            // The Characters track grows singles → pairs → triples → words on
            // its own. Surface that here and let the learner hold it at a stage,
            // so "Characters" serving words is never a mystery with no way back
            // (issue #51). Sending Practice drills the same persisted track, so
            // it gets the same control — keying whole words is a big step up.
            if (progressive != null) {
                SetupCard(stringResource(R.string.setup_track_stage)) {
                    key(stageRev) {
                        StagePinRow(pinned = progressive.pinnedStage) { pick ->
                            if (pick == null) progressive.unpin() else progressive.pin(pick)
                            EngineStore.save()
                            stageRev++
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stageNote(progressive),
                        style = MaterialTheme.typography.labelSmall,
                        color = Brand.textSecondary
                    )
                }
            }

            if (settingsMode in DURATION_MODES) {
                SetupCard(stringResource(R.string.setup_duration_question)) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PracticeDuration.entries.forEach { option ->
                            StagePill(
                                label = option.shortLabel,
                                selected = option == Settings.practiceDuration
                            ) { Settings.updatePracticeDuration(option) }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onStart,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Brand.teal,
                    contentColor = Brand.navy
                ),
                shape = RoundedCornerShape(Brand.cornerRadius),
                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)
            ) {
                Text(stringResource(R.string.setup_start_training), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.common_cancel), color = Brand.textSecondary)
            }
        }
    }
}

/** A labelled container holding one control, in the brand card style. */
@Composable
private fun SetupCard(title: String, content: @Composable () -> Unit) {
    Spacer(Modifier.height(16.dp))
    Column(modifier = Modifier.fillMaxWidth().brandCard().padding(16.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = Brand.textSecondary
        )
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun SetupRadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .background(
                    if (selected) Brand.teal else Brand.navyRaised,
                    shape = RoundedCornerShape(50)
                )
                .padding(horizontal = 8.dp, vertical = 8.dp)
        )
        Text(
            label,
            modifier = Modifier.padding(start = 12.dp),
            color = if (selected) Brand.textPrimary else Brand.textSecondary,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

/** What the track is doing right now, and what pinning would change (iOS parity). */
private fun stageNote(track: ProgressiveCharacters): String {
    val pinned = track.pinnedStage
    if (pinned != null) {
        return "Holding at ${pinned.displayName} — the track stays here until you switch back to Auto."
    }
    if (track.stage != ProgressiveCharacters.Stage.Singles) {
        return "You've grown past single characters — the track is at ${track.stage.displayName}. " +
            "Pick a stage to drill it specifically, or leave on Auto to keep growing."
    }
    return "Starts with single characters and grows into pairs, triples, then words & call signs " +
        "as you improve. Pick a stage to hold the track there instead."
}

/**
 * Is there anything for the pre-flight sheet to ask about this mode?
 *
 * Presenting an empty sheet would be a pure extra tap between the menu and the
 * drill, so modes with no applicable control skip it and launch directly — as
 * do the modes that already own a fuller setup step (Rapid Fire, Pileup,
 * Contest, the exam, Journey's map), which are never routed through here.
 */
fun sessionSetupHasOptions(settingsMode: SettingsMode): Boolean =
    settingsMode in LADDER_MODES || settingsMode in DURATION_MODES
