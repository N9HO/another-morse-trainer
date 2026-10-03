package app.anothermorsetrainer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp

/**
 * Shown under Listen & Learn's content chips and Common Words' pool picker
 * while CW 77 is chosen (#240), as on iOS (`IntroView.cw77Options`): Bob
 * Carter WR7Q's playback as a one-tap preset — an explicit tap that sets the
 * global speed, never a silent override — your callsign and name, and the
 * switch that adds them to the set, offered only when there is one to add.
 *
 * The callsign and name are the Your Station fields (Settings › QSO &
 * Pileups) edited in place (#293): the switch promised your name, but the
 * only place to type one was inside Pileup Runner's options, so nobody
 * drilling CW 77 could find it. [onChange] runs after any of them changes,
 * so a playing Listen loop can pick it up.
 */
@Composable
fun Cw77Options(modifier: Modifier = Modifier, onChange: () -> Unit = {}) {
    val focusManager = LocalFocusManager.current
    var edited by remember { mutableStateOf(false) }
    fun settle() {
        if (edited) {
            edited = false
            onChange()
        }
    }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (Settings.atCw77RecommendedSpeed) {
            Text(
                stringResource(R.string.cw77_at_recommended),
                style = MaterialTheme.typography.bodySmall,
                color = Brand.textSecondary
            )
        } else {
            TextButton(onClick = {
                Settings.applyCw77RecommendedSpeed()
                onChange()
            }) {
                Text(stringResource(R.string.cw77_use_recommended), color = Brand.teal, fontWeight = FontWeight.Bold)
            }
            Text(
                stringResource(R.string.cw77_recommended_footer),
                style = MaterialTheme.typography.bodySmall,
                color = Brand.textSecondary
            )
        }

        Text(
            stringResource(R.string.cw77_your_station),
            color = Brand.textPrimary,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 8.dp)
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Saved on every keystroke, but [onChange] (which restarts a
            // playing Listen loop) waits until a field is left, not per letter.
            OutlinedTextField(
                value = PileupSettings.myCall,
                onValueChange = {
                    PileupSettings.updateMyCall(it)
                    edited = true
                },
                singleLine = true,
                label = { Text(stringResource(R.string.cw77_callsign)) },
                placeholder = { Text(PileupSettings.DEFAULT_CALL) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Next),
                modifier = Modifier.weight(1f).onFocusChanged { if (!it.isFocused) settle() }
            )
            OutlinedTextField(
                value = PileupSettings.myName,
                onValueChange = {
                    PileupSettings.updateMyName(it)
                    edited = true
                },
                singleLine = true,
                label = { Text(stringResource(R.string.cw77_name)) },
                placeholder = { Text(stringResource(R.string.pileup_your_name_placeholder)) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                modifier = Modifier.weight(1f).onFocusChanged { if (!it.isFocused) settle() }
            )
        }
        Text(
            stringResource(R.string.cw77_station_footer),
            style = MaterialTheme.typography.bodySmall,
            color = Brand.textSecondary
        )

        val personal = Settings.cw77PersonalTokens()
        if (personal.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.cw77_include_me), color = Brand.textPrimary, fontWeight = FontWeight.Medium)
                    Text(
                        stringResource(R.string.cw77_include_me_adds, personal.joinToString(" and ") { it.token }),
                        style = MaterialTheme.typography.bodySmall,
                        color = Brand.textSecondary
                    )
                }
                Switch(
                    checked = Settings.cw77IncludeMe,
                    onCheckedChange = {
                        Settings.updateCw77IncludeMe(it)
                        onChange()
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Brand.navy,
                        checkedTrackColor = Brand.teal,
                        uncheckedThumbColor = Brand.textSecondary,
                        uncheckedTrackColor = Brand.navyRaised
                    )
                )
            }
        }
    }
}
