package app.anothermorsetrainer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
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
 *
 * Desktop: the keyboard keys too — Space is the straight key and `[` / `]` the
 * paddles (see [rememberKeyboardKeying]) — except while a text field (call,
 * channel, server, chat) has focus, so typing a space still types one.
 * Connect and chat Send hand focus back to the screen so keying works straight
 * after. Bluetooth MIDI keys are not supported on desktop, so the "find
 * Bluetooth key" button is replaced by a hint on connecting a USB key.
 */
@Composable
fun RepeaterScreen(onBack: () -> Unit) {
    val repeater = remember { VailRepeater() }

    var callsignField by remember { mutableStateOf(repeater.callsign) }
    var channelField by remember { mutableStateOf(repeater.channel) }
    var serverField by remember { mutableStateOf(repeater.serverUrl) }
    var keyPressed by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        repeater.start()
        onDispose {
            repeater.stop()
        }
    }
    BackHandler { onBack() }

    // Desktop: which text fields hold focus; keyboard keying stands down
    // while any does, so Space types into the field instead of keying.
    val textFocus = remember { mutableStateMapOf<String, Boolean>() }
    val typing = textFocus.values.any { it }
    val keyboardKeying = rememberKeyboardKeying(
        enabled = !typing,
        onStraightKey = { down ->
            keyPressed = down
            repeater.touchKey(down)
        },
        onPaddleKey = { down, ms -> repeater.touchKey(down, ms) }
    )
    val rootFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { rootFocus.requestFocus() } }
    val focusScreen: () -> Unit = { runCatching { rootFocus.requestFocus() } }

    val connected = repeater.connectionState == ConnectionState.CONNECTED
    val statusText = when (repeater.connectionState) {
        ConnectionState.DISCONNECTED -> stringResource(R.string.repeater_disconnected)
        ConnectionState.CONNECTING -> stringResource(R.string.repeater_connecting)
        ConnectionState.CONNECTED -> stringResource(R.string.repeater_status_connected, repeater.users.size)
        ConnectionState.IDLE_DISCONNECTED -> stringResource(R.string.repeater_idle_key_to_reconnect)
        ConnectionState.RECONNECTING -> stringResource(R.string.repeater_reconnecting)
    }
    val statusColor = if (connected) Color(0xFF2E7D32) else Brand.textSecondary

    Column(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { keyboardKeying(it) }
            .focusRequester(rootFocus)
            .focusable()
    ) {
        TextButton(onClick = onBack, modifier = Modifier.padding(8.dp)) { Text(stringResource(R.string.common_back), color = Brand.teal) }

            CenteredScrollColumn(
                contentModifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                Text(
                    stringResource(R.string.mode_repeater),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = Brand.textPrimary
                )
                Text(stringResource(R.string.repeater_subtitle), color = Brand.textSecondary, fontSize = 13.sp)

                Spacer(Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = callsignField,
                        onValueChange = { callsignField = it },
                        label = { Text(stringResource(R.string.repeater_callsign)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f).onFocusChanged { textFocus["callsign"] = it.isFocused }
                    )
                    OutlinedTextField(
                        value = channelField,
                        onValueChange = { channelField = it },
                        label = { Text(stringResource(R.string.repeater_channel)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f).onFocusChanged { textFocus["channel"] = it.isFocused }
                    )
                }

                Spacer(Modifier.height(12.dp))
                // Which Vail server to use: the known public ones, or any wss:// URL.
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.repeater_server), color = Brand.textSecondary, fontSize = 12.sp)
                    VailRepeater.KNOWN_SERVERS.forEach { (label, url) ->
                        val sel = serverField == url
                        Box(
                            modifier = Modifier
                                .background(if (sel) Brand.teal else Brand.navyRaised, RoundedCornerShape(8.dp))
                                .clickable {
                                    serverField = url
                                    repeater.updateServer(url)
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                label,
                                color = if (sel) Brand.navy else Brand.textSecondary,
                                fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
                if (VailRepeater.KNOWN_SERVERS.none { it.second == serverField }) {
                    Spacer(Modifier.height(6.dp))
                }
                // Free-typed URLs apply on Connect (a pill press applies at once).
                OutlinedTextField(
                    value = serverField,
                    onValueChange = { serverField = it },
                    label = { Text(stringResource(R.string.repeater_server_url)) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().onFocusChanged { textFocus["server"] = it.isFocused }
                )

                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        if (connected || repeater.connectionState == ConnectionState.CONNECTING) {
                            repeater.disconnect()
                        } else {
                            repeater.updateCallsign(callsignField)
                            repeater.updateChannel(channelField)
                            repeater.updateServer(serverField)
                            repeater.connect()
                        }
                        // Desktop: leave the text fields so Space keys at once.
                        focusScreen()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (connected) Brand.navyRaised else Brand.teal,
                        contentColor = if (connected) Brand.textPrimary else Brand.navy
                    ),
                    modifier = Modifier.fillMaxWidth().height(50.dp)
                ) { Text(if (connected || repeater.connectionState == ConnectionState.CONNECTING) stringResource(R.string.repeater_disconnect) else stringResource(R.string.repeater_connect), fontWeight = FontWeight.Bold) }

                Spacer(Modifier.height(10.dp))
                Text(statusText, color = statusColor, fontWeight = FontWeight.Medium)
                repeater.midiDevice?.let { Text("🎹 $it", color = Brand.teal, fontSize = 12.sp) }
                repeater.notice?.let { Text(it, color = Brand.textSecondary, fontSize = 12.sp) }
                if (repeater.lagMs != 0L) Text(stringResource(R.string.repeater_lag, repeater.lagMs), color = Brand.textSecondary, fontSize = 12.sp)

                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(stringResource(R.string.repeater_break_in_transmit), color = Brand.textPrimary, fontWeight = FontWeight.Medium)
                        Text(stringResource(R.string.repeater_send_your_keying_to_the_channel), color = Brand.textSecondary, fontSize = 12.sp)
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

                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(stringResource(R.string.repeater_private_channel), color = Brand.textPrimary, fontWeight = FontWeight.Medium)
                        Text(stringResource(R.string.repeater_stay_off_the_public_room_list), color = Brand.textSecondary, fontSize = 12.sp)
                    }
                    Switch(
                        checked = repeater.privateChannel,
                        onCheckedChange = { repeater.updatePrivateChannel(it) },
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
                // Straight key or paddles, as chosen in Settings (#233).
                OnScreenKeySwitch(
                    onPaddleKey = { down, ms -> repeater.touchKey(down, ms) },
                    modifier = Modifier.fillMaxWidth().height(110.dp)
                ) {
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
                            Text("⠿", fontSize = 24.sp, color = if (keyPressed) Brand.navy else Brand.teal)
                            Text(
                                stringResource(R.string.common_hold_to_key),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (keyPressed) Brand.navy else Brand.textSecondary
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(DesktopCopy.KEYBOARD_KEY_HINT, color = Brand.textSecondary, fontSize = 12.sp)

                Spacer(Modifier.height(16.dp))
                ActivityTimeline(repeater)

                Spacer(Modifier.height(16.dp))
                Column(modifier = Modifier.fillMaxWidth().brandCard().padding(16.dp)) {
                    SliderRow(stringResource(R.string.repeater_tx_tone), repeater.txTone.toString(), repeater.txTone.toFloat(), 48f..96f) {
                        repeater.updateTxTone(it.toInt())
                    }
                    Spacer(Modifier.height(8.dp))
                    // 0–4000 ms in 250 ms steps, the same stepper range as iOS.
                    SliderRow(
                        stringResource(R.string.repeater_rx_delay),
                        stringResource(R.string.repeater_ms_value, repeater.rxDelayMs),
                        repeater.rxDelayMs.toFloat(),
                        0f..VailRepeater.RX_DELAY_MAX_MS.toFloat(),
                        steps = VailRepeater.RX_DELAY_MAX_MS / VailRepeater.RX_DELAY_STEP_MS - 1
                    ) {
                        repeater.updateRxDelayMs(it.toInt())
                    }
                }

                // Vail Adapter keyer config (only when MIDI is available).
                if (repeater.midiSupported) {
                    Spacer(Modifier.height(16.dp))
                    Column(modifier = Modifier.fillMaxWidth().brandCard().padding(16.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.repeater_adapter_keyer), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Brand.textSecondary)
                            TextButton(onClick = { repeater.wakeAdapter() }) { Text(stringResource(R.string.repeater_wake_adapter), color = Brand.teal) }
                        }
                        // Live connected-state for the adapter itself.
                        Text(
                            repeater.adapterName?.let { stringResource(R.string.repeater_connected_to, it) } ?: stringResource(R.string.repeater_no_adapter_connected),
                            color = if (repeater.adapterName != null) Color(0xFF2E7D32) else Brand.textSecondary,
                            fontSize = 12.sp
                        )
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
                        Spacer(Modifier.height(10.dp))
                        SliderRow(stringResource(R.string.repeater_keyer_speed), stringResource(R.string.common_wpm_value, repeater.keyerWpm), repeater.keyerWpm.toFloat(), 5f..50f) {
                            repeater.updateKeyerWpm(it.toInt())
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(stringResource(R.string.repeater_rx_buzz), color = Brand.textPrimary, fontWeight = FontWeight.Medium)
                                Text(stringResource(R.string.repeater_rx_buzz_description), color = Brand.textSecondary, fontSize = 12.sp)
                            }
                            Switch(
                                checked = repeater.rxBuzzEnabled,
                                onCheckedChange = { repeater.updateRxBuzzEnabled(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Brand.navy,
                                    checkedTrackColor = Brand.teal,
                                    uncheckedThumbColor = Brand.textSecondary,
                                    uncheckedTrackColor = Brand.navyRaised
                                )
                            )
                        }
                        // Desktop: in place of Android's "find Bluetooth key" button.
                        Spacer(Modifier.height(8.dp))
                        Text(DesktopCopy.KEY_CONNECT_HINT, color = Brand.textSecondary, fontSize = 12.sp)
                    }
                }

                if (repeater.users.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.repeater_on_channel), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Brand.textSecondary)
                    Spacer(Modifier.height(6.dp))
                    Column(modifier = Modifier.fillMaxWidth().brandCard()) {
                        repeater.users.forEachIndexed { i, u ->
                            if (i > 0) Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(Brand.hairline))
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    u.callsign + if (u.callsign == repeater.callsign) stringResource(R.string.repeater_you_suffix) else "",
                                    color = Brand.textPrimary,
                                    fontFamily = FontFamily.Monospace
                                )
                                u.txTone?.let { Text("♪ $it", color = Brand.textSecondary, fontSize = 12.sp) }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                ChatPanel(
                    repeater,
                    onTextFocus = { focused -> textFocus["chat"] = focused },
                    onSent = focusScreen
                )

                Spacer(Modifier.height(24.dp))
            }
    }
}

/** A 12-second scrolling activity timeline: sent tones above the midline,
 *  received below, chat as dots, and a live growing bar while keying. */
@Composable
private fun ActivityTimeline(repeater: VailRepeater) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
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
        Text(stringResource(R.string.repeater_activity), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Brand.textSecondary)
    }
}

/**
 * Chat behind a Show/Hide header, the way iOS keeps it behind a toolbar button:
 * while hidden, messages arriving count as unread and badge the header; showing
 * the panel clears the badge and advances the per-channel read watermark, so a
 * replayed backlog does not re-badge on the next launch.
 */
@Composable
private fun ChatPanel(
    repeater: VailRepeater,
    // Desktop: reports the message field's focus (keyboard keying pauses while
    // it has it) and hands focus back to the screen after a send.
    onTextFocus: (Boolean) -> Unit = {},
    onSent: () -> Unit = {}
) {
    var input by remember { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf(false) }
    DisposableEffect(expanded) {
        repeater.isChatViewActive = expanded
        if (expanded) repeater.markChatRead()
        onDispose { repeater.isChatViewActive = false }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.repeater_chat), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Brand.textSecondary)
            if (repeater.unreadChatCount > 0) {
                Box(
                    modifier = Modifier
                        .background(Color(0xFFFFA000), RoundedCornerShape(8.dp))
                        .padding(horizontal = 6.dp, vertical = 1.dp)
                ) {
                    Text(
                        stringResource(R.string.repeater_chat_unread, repeater.unreadChatCount),
                        color = Brand.navy,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        TextButton(onClick = { expanded = !expanded }) {
            Text(
                if (expanded) stringResource(R.string.repeater_hide_chat) else stringResource(R.string.repeater_show_chat),
                color = Brand.teal
            )
        }
    }
    if (!expanded) return
    // Desktop: a hidden panel's field can no longer hold focus.
    DisposableEffect(Unit) { onDispose { onTextFocus(false) } }
    Spacer(Modifier.height(6.dp))
    Column(modifier = Modifier.fillMaxWidth().brandCard().heightIn(min = 60.dp).padding(12.dp)) {
        val recent = repeater.chatMessages.takeLast(40)
        if (recent.isEmpty()) {
            Text(stringResource(R.string.repeater_no_messages_yet), color = Brand.textSecondary, fontSize = 13.sp)
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
            placeholder = { Text(stringResource(R.string.repeater_message)) },
            singleLine = true,
            modifier = Modifier
                .weight(1f)
                .onFocusChanged { onTextFocus(it.isFocused) }
                // Desktop: a hardware Enter sends the message; focus stays in
                // the box for the next line.
                .onPreviewKeyEvent { e ->
                    if ((e.key == Key.Enter || e.key == Key.NumPadEnter) && e.type == KeyEventType.KeyDown) {
                        if (input.isNotBlank()) { repeater.sendChat(input); input = "" }
                        true
                    } else false
                }
        )
        OutlinedButton(onClick = {
            if (input.isNotBlank()) { repeater.sendChat(input); input = "" }
            onSent()
        }) { Text(stringResource(R.string.common_send)) }
    }
}

@Composable
private fun SliderRow(label: String, value: String, position: Float, range: ClosedFloatingPointRange<Float>, steps: Int = 0, onChange: (Float) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Brand.textPrimary, fontWeight = FontWeight.Medium)
        Text(value, color = Brand.teal, fontWeight = FontWeight.SemiBold)
    }
    Slider(
        value = position,
        onValueChange = onChange,
        valueRange = range,
        steps = steps,
        colors = SliderDefaults.colors(
            thumbColor = Brand.teal,
            activeTrackColor = Brand.teal,
            inactiveTrackColor = Brand.navyRaised
        ),
        modifier = Modifier.height(24.dp)
    )
}
