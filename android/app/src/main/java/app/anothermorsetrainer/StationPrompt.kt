package app.anothermorsetrainer

import android.content.Context
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.core.content.edit

/**
 * Set up Your Station on first entry to Pileup Runner (#297). Pileup Runner
 * keys your callsign when you call CQ and sign off, and until it is set that
 * callsign is the W1AW placeholder: a first-time user found themselves on
 * the air as W1AW with no idea why. So the first time Pileup Runner's setup
 * opens while the callsign is still blank or W1AW, it asks for the station:
 * callsign, and optionally name and state (CW 77 and First Four use those).
 *
 * Asked once. "Save" and "Not now" both answer it for good; Settings ›
 * QSO & Pileups is where to change the station later, and the prompt says
 * so. An install whose callsign is already set is never asked. The iOS twin
 * is `StationPrompt.swift`.
 */
object StationPrompt {
    private const val PREFS = "amt_station_prompt"
    private const val ASKED = "asked"

    /** Whether a callsign is still unset: blank or the W1AW placeholder. */
    fun isPlaceholder(call: String): Boolean {
        val c = call.trim().uppercase()
        return c.isEmpty() || c == PileupSettings.DEFAULT_CALL
    }

    fun shouldOffer(context: Context): Boolean =
        isPlaceholder(PileupSettings.myCall) &&
            !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ASKED, false)

    fun markAsked(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(ASKED, true) }
    }
}

/**
 * Put in a mode's setup screen: raises the dialog once, the first time the
 * screen appears with the callsign unset. Saved, so a rotation neither loses
 * nor repeats it.
 */
@Composable
fun StationPromptHost() {
    val context = LocalContext.current
    var open by rememberSaveable { mutableStateOf(false) }
    var checked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!checked) {
            checked = true
            if (StationPrompt.shouldOffer(context)) open = true
        }
    }
    if (open) {
        StationPromptDialog(onDone = {
            StationPrompt.markAsked(context)
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
