package app.anothermorsetrainer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.anothermorsetrainer.vail.SignalEvent
import app.anothermorsetrainer.vail.ConnectionState
import app.anothermorsetrainer.vail.VailRepeater

/**
 * **Repeater** (live practice over the network): connect to a Vail channel, key
 * with the on-screen straight key (or a MIDI key), hear other operators in real
 * time, and — with break-in on — transmit your keying to the channel.
 *
 * A focused port of the iOS RepeaterView: callsign/channel, connect, roster,
 * break-in, hold-to-key, lag. The signal-timeline visualizer and chat panel are
 * follow-ups; the audio + connection core is here. Requires the network, so this
 * screen can't be exercised on a dev machine — verified by build only.
 */
@Composable
fun RepeaterScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repeater = remember { VailRepeater(context) }

    var callsignField by remember { mutableStateOf(repeater.callsign) }
    var channelField by remember { mutableStateOf(repeater.channel) }
    var keyPressed by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        repeater.start()
        onDispose { repeater.stop() }
    }
    BackHandler { onBack() }

    val connected = repeater.connectionState == ConnectionState.CONNECTED
    val statusText = when (repeater.connectionState) {
        ConnectionState.DISCONNECTED -> "Disconnected"
        ConnectionState.CONNECTING -> "Connecting…"
        ConnectionState.CONNECTED -> "Connected · ${repeater.users.size} on channel"
        ConnectionState.IDLE_DISCONNECTED -> "Idle — key to reconnect"
        ConnectionState.RECONNECTING -> "Reconnecting…"
    }
    val statusColor = if (connected) Color(0xFF2E7D32) else Brand.textSecondary

    Column(modifier = Modifier.fillMaxSize()) {
        TextButton(onClick = onBack, modifier = Modifier.padding(8.dp)) { Text("‹ Back", color = Brand.teal) }

        CenteredContent {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                Text(
                    "Repeater",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = Brand.textPrimary
                )
                Text("Live CW practice over the network (Vail)", color = Brand.textSecondary, fontSize = 13.sp)

                Spacer(Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = callsignField,
                        onValueChange = { callsignField = it },
                        label = { Text("Callsign") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = channelField,
                        onValueChange = { channelField = it },
                        label = { Text("Channel") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        if (connected || repeater.connectionState == ConnectionState.CONNECTING) {
                            repeater.disconnect()
                        } else {
                            repeater.updateCallsign(callsignField)
                            repeater.updateChannel(channelField)
                            repeater.connect()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (connected) Brand.navyRaised else Brand.teal,
                        contentColor = if (connected) Brand.textPrimary else Brand.navy
                    ),
                    modifier = Modifier.fillMaxWidth().height(50.dp)
                ) { Text(if (connected || repeater.connectionState == ConnectionState.CONNECTING) "Disconnect" else "Connect", fontWeight = FontWeight.Bold) }

                Spacer(Modifier.height(10.dp))
                Text(statusText, color = statusColor, fontWeight = FontWeight.Medium)
                repeater.midiDevice?.let { Text("🎹 $it", color = Brand.teal, fontSize = 12.sp) }
                repeater.notice?.let { Text(it, color = Brand.textSecondary, fontSize = 12.sp) }
                if (repeater.lagMs != 0L) Text("Lag ${repeater.lagMs} ms", color = Brand.textSecondary, fontSize = 12.sp)

                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Break-in (transmit)", color = Brand.textPrimary, fontWeight = FontWeight.Medium)
                        Text("Send your keying to the channel", color = Brand.textSecondary, fontSize = 12.sp)
                    }
                    Switch(
                        checked = repeater.breakInEnabled,
                        onCheckedChange = { repeater.setBreakIn(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Brand.navy,
                            checkedTrackColor = Brand.teal,
                            uncheckedThumbColor = Brand.textSecondary,
                            uncheckedTrackColor = Brand.navyRaised
                        )
                    )
                }

                Spacer(Modifier.height(16.dp))
                // Hold-to-key straight key (always sounds local sidetone; transmits
                // only when break-in is on).
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp)
                        .background(if (keyPressed) Brand.teal else Brand.navyRaised, RoundedCornerShape(Brand.cornerRadius))
                        .border(
                            width = if (keyPressed) 2.dp else 1.dp,
                            color = if (keyPressed) Brand.tealBright else Brand.hairline,
                            shape = RoundedCornerShape(Brand.cornerRadius)
                        )
                        .pointerInput(Unit) {
                            detectTapGestures(onPress = {
                                keyPressed = true
                                repeater.touchKey(true)
                                try { tryAwaitRelease() } finally {
                                    keyPressed = false
                                    repeater.touchKey(false)
                                }
                            })
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("⠿", fontSize = 24.sp, color = if (keyPressed) Color.White else Brand.teal)
                        Text(
                            "HOLD TO KEY",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (keyPressed) Color.White else Brand.textSecondary
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                ActivityTimeline(repeater)

                Spacer(Modifier.height(16.dp))
                Column(modifier = Modifier.fillMaxWidth().brandCard().padding(16.dp)) {
                    SliderRow("TX tone", repeater.txTone.toString(), repeater.txTone.toFloat(), 48f..96f) {
                        repeater.updateTxTone(it.toInt())
                    }
                    Spacer(Modifier.height(8.dp))
                    SliderRow("RX delay", "${repeater.rxDelayMs} ms", repeater.rxDelayMs.toFloat(), 0f..5000f) {
                        repeater.updateRxDelayMs(it.toInt())
                    }
                }

                // Vail Adapter keyer config (only when the device has MIDI).
                if (repeater.midiSupported) {
                    Spacer(Modifier.height(16.dp))
                    Column(modifier = Modifier.fillMaxWidth().brandCard().padding(16.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("ADAPTER KEYER", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Brand.textSecondary)
                            TextButton(onClick = { repeater.wakeAdapter() }) { Text("Wake adapter", color = Brand.teal) }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            MidiKeyOutput.KeyerMode.entries.forEach { mode ->
                                val sel = mode == repeater.keyerMode
                                Box(
                                    modifier = Modifier
                                        .background(if (sel) Brand.teal else Brand.navyRaised, RoundedCornerShape(8.dp))
                                        .clickable { repeater.updateKeyerMode(mode) }
                                        .padding(horizontal = 12.dp, vertical = 7.dp)
                                ) {
                                    Text(
                                        mode.displayName,
                                        color = if (sel) Brand.navy else Brand.textSecondary,
                                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }
                    }
                }

                if (repeater.users.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text("ON CHANNEL", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Brand.textSecondary)
                    Spacer(Modifier.height(6.dp))
                    Column(modifier = Modifier.fillMaxWidth().brandCard()) {
                        repeater.users.forEachIndexed { i, u ->
                            if (i > 0) Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(Brand.hairline))
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    u.callsign + if (u.callsign == repeater.callsign) "  (you)" else "",
                                    color = Brand.textPrimary,
                                    fontFamily = FontFamily.Monospace
                                )
                                u.txTone?.let { Text("♪ $it", color = Brand.textSecondary, fontSize = 12.sp) }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                ChatPanel(repeater)

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** A 12-second scrolling activity timeline: sent tones above the midline,
 *  received below, chat as dots, and a live growing bar while keying. */
@Composable
private fun ActivityTimeline(repeater: VailRepeater) {
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { withFrameMillis { }; nowMs = System.currentTimeMillis() } }
    val windowMs = 12_000.0
    val chatColor = Color(0xFFFFA000)

    Box(modifier = Modifier.fillMaxWidth().height(72.dp).brandCard().padding(10.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val midY = size.height / 2f
            drawLine(Brand.hairline, Offset(0f, midY), Offset(w, midY), 1f)
            val startMs = nowMs - windowMs
            fun x(ms: Long): Float = ((ms - startMs) / windowMs * w).toFloat()
            val barH = 12f
            repeater.signalEvents.forEach { e ->
                if (e.endLocalMs < startMs) return@forEach
                val sent = e.origin == SignalEvent.Origin.SENT
                val color = if (sent) Brand.teal else Brand.tealBright
                when (val k = e.kind) {
                    is SignalEvent.Kind.Tone -> {
                        val x0 = x(e.startLocalMs).coerceAtLeast(0f)
                        val x1 = x(e.startLocalMs + k.durationMs).coerceAtMost(w)
                        if (x1 > x0) {
                            val y = if (sent) midY - barH - 3 else midY + 3
                            drawRoundRect(color, Offset(x0, y), Size(maxOf(2f, x1 - x0), barH), CornerRadius(2f))
                        }
                    }
                    is SignalEvent.Kind.Chat -> {
                        val cx = x(e.startLocalMs)
                        if (cx in 0f..w) drawCircle(chatColor, 3f, Offset(cx, midY))
                    }
                }
            }
            repeater.liveOwnKeyStarts.forEach { begin ->
                val x0 = x(begin).coerceAtLeast(0f)
                val x1 = x(nowMs).coerceAtMost(w)
                if (x1 > x0) drawRoundRect(Brand.tealBright, Offset(x0, midY - barH - 3), Size(maxOf(2f, x1 - x0), barH), CornerRadius(2f))
            }
        }
        Text("ACTIVITY", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Brand.textSecondary)
    }
}

@Composable
private fun ChatPanel(repeater: VailRepeater) {
    var input by remember { mutableStateOf("") }
    Text("CHAT", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Brand.textSecondary)
    Spacer(Modifier.height(6.dp))
    Column(modifier = Modifier.fillMaxWidth().brandCard().heightIn(min = 60.dp).padding(12.dp)) {
        val recent = repeater.chatMessages.takeLast(40)
        if (recent.isEmpty()) {
            Text("No messages yet.", color = Brand.textSecondary, fontSize = 13.sp)
        } else {
            recent.forEach { line ->
                Row {
                    Text((line.callsign ?: "?") + ": ", color = Brand.teal, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    Text(line.text, color = Brand.textPrimary, fontSize = 13.sp)
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            placeholder = { Text("Message") },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        OutlinedButton(onClick = {
            if (input.isNotBlank()) { repeater.sendChat(input); input = "" }
        }) { Text("Send") }
    }
}

@Composable
private fun SliderRow(label: String, value: String, position: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Brand.textPrimary, fontWeight = FontWeight.Medium)
        Text(value, color = Brand.teal, fontWeight = FontWeight.SemiBold)
    }
    Slider(
        value = position,
        onValueChange = onChange,
        valueRange = range,
        colors = SliderDefaults.colors(
            thumbColor = Brand.teal,
            activeTrackColor = Brand.teal,
            inactiveTrackColor = Brand.navyRaised
        ),
        modifier = Modifier.height(24.dp)
    )
}
