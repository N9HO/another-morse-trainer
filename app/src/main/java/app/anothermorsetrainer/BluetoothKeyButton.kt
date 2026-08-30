package app.anothermorsetrainer

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/**
 * "Find Bluetooth key" — scan for a BLE-MIDI key and open it, with the runtime
 * permission prompt and the progress readout that go with it.
 *
 * Android never opens BLE-MIDI devices by itself (see [BleMidi]): until
 * something scans for the BLE-MIDI service and calls `openBluetoothDevice`, a
 * key that is paired and shown as "Connected" in Android's Bluetooth settings
 * is invisible to [android.media.midi.MidiManager] — and therefore to every
 * screen in this app. Only the Vail repeater ever ran that scan, so a BLE key
 * simply could not be attached in Sending Practice, Common Words, or Rapid
 * Fire. That is the Android twin of the iOS report N9HO/another-morse-trainer#91,
 * whose Bluetooth browser had the same reachability gap on its sending page.
 *
 * Renders nothing where BLE MIDI isn't supported, so it is safe to drop into
 * any keying screen.
 */
@Composable
fun BluetoothKeyButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bleMidi = remember { BleMidi(context) }
    var status by remember { mutableStateOf<String?>(null) }

    val permissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) bleMidi.scanAndOpen { status = it }
        else status = "Bluetooth permission is needed to find a key."
    }

    // Releasing stops the scan and closes the BLE link, so the key is handed
    // back when the screen goes away.
    DisposableEffect(Unit) { onDispose { bleMidi.release() } }

    if (bleMidi.isSupported) {
        Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            OutlinedButton(onClick = {
                val missing = bleMidi.requiredPermissions().filter {
                    ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                }
                if (missing.isEmpty()) bleMidi.scanAndOpen { status = it }
                else permissions.launch(missing.toTypedArray())
            }) {
                Text("Find Bluetooth key")
            }
            status?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, color = Brand.textSecondary, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
