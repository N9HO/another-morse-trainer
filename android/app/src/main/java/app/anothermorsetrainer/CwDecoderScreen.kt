package app.anothermorsetrainer

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

/**
 * Live CW decoder: point the microphone at received Morse audio — a rig's
 * speaker, a WebSDR, a practice recording — and read it as text. The decoding
 * itself is the ported Carrier Wave core (morsekit/cw); this screen just runs
 * the mic and shows what the core hears. Port of the iOS `CWDecoderView`.
 */
@Composable
fun CwDecoderScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val engine = remember { CwDecoderEngine() }
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // start() flags micDenied itself when the permission was refused.
        engine.start(context)
    }

    fun startListening() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            engine.start(context)
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    DisposableEffect(Unit) { onDispose { engine.stop() } }
    BackHandler { onBack() }

    CenteredContent {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack) { Text(stringResource(R.string.common_back)) }
                Text(
                    stringResource(R.string.decoder_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(64.dp))
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Telemetry row: level / tone state, WPM and pitch.
                Row(
                    modifier = Modifier.fillMaxWidth().brandCard()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.GraphicEq,
                        contentDescription = null,
                        tint = when {
                            engine.tonePresent -> Brand.tealBright
                            engine.isListening -> Brand.teal
                            else -> Brand.textSecondary
                        },
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = when {
                            !engine.isListening -> stringResource(R.string.common_off)
                            engine.inputLevel < 0.003f -> stringResource(R.string.decoder_listening_its_quiet)
                            else -> stringResource(R.string.decoder_hearing_audio)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = Brand.textPrimary
                    )
                    Spacer(Modifier.weight(1f))
                    if (engine.wpm > 0f) {
                        Text(
                            stringResource(R.string.common_wpm_value, engine.wpm.roundToInt()),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace,
                            color = Brand.tealBright
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            stringResource(R.string.common_hz_value, engine.toneHz.roundToInt()),
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            color = Brand.textSecondary
                        )
                    }
                }

                // Transcript, auto-following the newest text.
                val scroll = rememberScrollState()
                LaunchedEffect(engine.decodedText) {
                    scroll.animateScrollTo(scroll.maxValue)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .brandCard()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scroll)
                            .padding(16.dp)
                    ) {
                        if (engine.decodedText.isEmpty()) {
                            Text(
                                if (engine.isListening) stringResource(R.string.decoder_waiting_for_cw)
                                else stringResource(R.string.decoder_idle_prompt),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Brand.textSecondary
                            )
                        } else {
                            SlashableText(
                                text = engine.decodedText,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Medium,
                                fontFamily = FontFamily.Monospace,
                                color = Brand.textPrimary
                            )
                        }
                    }
                }

                if (engine.micDenied) {
                    Row(
                        modifier = Modifier.fillMaxWidth().brandCard().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.MicOff,
                            contentDescription = null,
                            tint = Color(0xFFE08A1E),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            stringResource(R.string.decoder_mic_denied),
                            style = MaterialTheme.typography.bodySmall,
                            color = Brand.textSecondary
                        )
                    }
                }

                // Controls: Start/Stop + Clear.
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            if (Settings.hapticsEnabled) Haptics(context).tap()
                            if (engine.isListening) engine.stop() else startListening()
                        },
                        colors = if (engine.isListening) {
                            ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFC62828), contentColor = Color.White
                            )
                        } else {
                            ButtonDefaults.buttonColors(
                                containerColor = Brand.teal, contentColor = Brand.navy
                            )
                        },
                        shape = RoundedCornerShape(Brand.cornerRadius),
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp)
                    ) {
                        Icon(
                            if (engine.isListening) Icons.Filled.Stop else Icons.Filled.Mic,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (engine.isListening) stringResource(R.string.common_stop) else stringResource(R.string.common_start),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    OutlinedButton(
                        onClick = { engine.clear() },
                        enabled = engine.decodedText.isNotEmpty(),
                        shape = RoundedCornerShape(Brand.cornerRadius),
                        modifier = Modifier.heightIn(min = 52.dp)
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.common_clear))
                    }
                }
            }
        }
    }
}
