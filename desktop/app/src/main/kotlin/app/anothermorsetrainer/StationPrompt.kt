package app.anothermorsetrainer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp

/**
 * Set up Your Station on first entry to Pileup Runner or Contest (#297,
 * #320). Both key your callsign when you call CQ and sign off, and until it
 * is set that callsign is the W1AW placeholder: a first-time user found
 * themselves on the air as W1AW with no idea why. So the first time either
 * mode's setup opens while the callsign is still blank or W1AW, it asks for the station:
 * callsign, and optionally name and state (CW 77 and First Four use those).
 *
 * Asked once. "Save" and "Not now" both answer it for good; Settings ›
 * QSO & Pileups is where to change the station later, and the prompt says
 * so. An install whose callsign is already set is never asked. Forked from
 * the Android tree's `StationPrompt.kt`; the iOS twin is `StationPrompt.swift`.
 */
object StationPrompt {
    private const val ASKED = "asked"
    private val prefs: Prefs by lazy { Prefs.open("amt_station_prompt") }

    /** Whether a callsign is still unset: blank or the W1AW placeholder. */
    fun isPlaceholder(call: String): Boolean {
        val c = call.trim().uppercase()
        return c.isEmpty() || c == PileupSettings.DEFAULT_CALL
    }

    fun shouldOffer(): Boolean = isPlaceholder(PileupSettings.myCall) && !prefs.getBoolean(ASKED, false)

    fun markAsked() {
        prefs.edit { putBoolean(ASKED, true) }
    }
}

/**
 * Put in a mode's setup screen: raises the dialog once, the first time the
 * screen appears with the callsign unset. Saved, so a rotation neither loses
 * nor repeats it.
 */
@Composable
fun StationPromptHost() {
    var open by rememberSaveable { mutableStateOf(false) }
    var checked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!checked) {
            checked = true
            if (StationPrompt.shouldOffer()) open = true
        }
    }
    if (open) {
        StationPromptDialog(onDone = {
            StationPrompt.markAsked()
            open = false
        })
    }
}

@Composable
private fun StationPromptDialog(onDone: () -> Unit) {
    var call by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf(PileupSettings.myName) }
    var state by rememberSaveable { mutableStateOf(PileupSettings.myState) }
    val caps = remember { KeyboardOptions(capitalization = KeyboardCapitalization.Characters) }
    AlertDialog(
        // Tapping outside or Back is "Not now".
        onDismissRequest = onDone,
        containerColor = Brand.navyElevated,
        title = { Text(stringResource(R.string.station_prompt_title), color = Brand.textPrimary) },
        text = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.station_prompt_body), color = Brand.textSecondary)
                OutlinedTextField(
                    value = call, onValueChange = { call = it }, singleLine = true,
                    label = { Text(stringResource(R.string.station_prompt_call)) },
                    keyboardOptions = caps, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text(stringResource(R.string.station_prompt_name)) },
                    keyboardOptions = caps, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = state, onValueChange = { state = it }, singleLine = true,
                    label = { Text(stringResource(R.string.station_prompt_state)) },
                    keyboardOptions = caps, modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (call.isNotBlank()) PileupSettings.updateMyCall(call)
                PileupSettings.updateMyName(name.trim())
                PileupSettings.updateMyState(state)
                onDone()
            }) { Text(stringResource(R.string.station_prompt_save), color = Brand.teal, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            TextButton(onClick = onDone) {
                Text(stringResource(R.string.station_prompt_not_now), color = Brand.textSecondary)
            }
        }
    )
}
