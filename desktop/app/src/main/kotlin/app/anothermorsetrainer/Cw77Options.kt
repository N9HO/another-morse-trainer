package app.anothermorsetrainer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Shown under Listen & Learn's content chips and Common Words' pool picker
 * while CW 77 is chosen (#240), as on iOS (`IntroView.cw77Options`): Bob
 * Carter WR7Q's playback as a one-tap preset — an explicit tap that sets the
 * global speed, never a silent override — and the switch that adds your own
 * callsign and name, offered only when there is one to add. [onChange] runs
 * after either changes, so a playing Listen loop can pick it up.
 */
@Composable
fun Cw77Options(modifier: Modifier = Modifier, onChange: () -> Unit = {}) {
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
        val personal = Settings.cw77PersonalTokens()
        if (personal.isEmpty()) {
            Text(
                stringResource(R.string.cw77_personal_unset),
                style = MaterialTheme.typography.bodySmall,
                color = Brand.textSecondary
            )
        } else {
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
