package app.anothermorsetrainer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.anothermorsetrainer.morsekit.MorseData
import app.anothermorsetrainer.morsekit.MorseItem
import app.anothermorsetrainer.morsekit.MorseReference

/**
 * A browsable, tap-to-hear reference for the signals every operator wants at
 * their fingertips: prosigns, Q-codes, CW abbreviations, contest cut numbers,
 * and the full Morse chart. Unlike the quiz modes (which test recall), this is
 * the "remind me / look it up" companion — scan a table, tap any row to hear it
 * at your configured speed and pitch, or open it for the full detail.
 *
 * Ported from MorseTrainerApp/ReferenceView.swift. iOS's NavigationStack push to
 * the detail screen becomes in-screen state here (a selected [MorseItem]); the
 * `@AppStorage`-bound audio controls become the same shared [Settings] sliders
 * the drills use, so adjusting them here changes playback everywhere.
 */
private enum class RefCategory(val label: String, val blurb: String) {
    PROSIGNS("Prosigns", "Procedural signals, sent run-together as one character."),
    QCODES("Q-Codes", "Q-signal shorthand for common questions and answers."),
    ABBR("Abbr", "Everyday CW abbreviations heard in every QSO."),
    CUTNUMBERS("Cut #", "Contest shorthand: a digit sent as a single letter to save time."),
    CHART("Chart", "The full alphabet, numbers, and punctuation with their rhythm.");

    val items: List<MorseItem>
        get() = when (this) {
            PROSIGNS -> MorseData.prosignItems
            QCODES -> MorseData.qCodeItems
            ABBR -> MorseData.abbreviationItems
            CUTNUMBERS -> MorseReference.cutNumberItems
            CHART -> MorseReference.chartItems
        }
}

@Composable
fun ReferenceScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val player = remember { MorsePlayer() }
    val haptics = remember { Haptics(context) }

    var category by remember { mutableStateOf(RefCategory.PROSIGNS) }
    var query by remember { mutableStateOf("") }
    var showAudio by remember { mutableStateOf(false) }
    var playingId by remember { mutableStateOf<String?>(null) }
    var detailItem by remember { mutableStateOf<MorseItem?>(null) }

    DisposableEffect(Unit) { onDispose { player.release() } }

    // Switching tables stops any tone still ringing out from the old one.
    LaunchedEffect(category) {
        player.stop()
        playingId = null
    }

    fun play(item: MorseItem) {
        if (Settings.hapticsEnabled) haptics.tap()
        playingId = item.id
        player.play(item.playable, Settings.sidetoneHz, Settings.timing()) {
            if (playingId == item.id) playingId = null
        }
    }

    val current = detailItem
    if (current != null) {
        BackHandler { detailItem = null }
        ReferenceDetailScreen(
            item = current,
            isPlaying = playingId == current.id,
            onPlay = { play(current) },
            onBack = { player.stop(); playingId = null; detailItem = null }
        )
        return
    }

    BackHandler { onBack() }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(onClick = onBack) { Text("‹ Back", color = Brand.teal) }
                Text("Reference", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                IconButton(onClick = { showAudio = !showAudio }) {
                    Icon(Icons.Filled.Tune, contentDescription = "Playback settings", tint = Brand.teal)
                }
            }

            // Category selector — a scrollable row of pills (matches the iOS segmented picker).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RefCategory.entries.forEach { cat ->
                    val sel = cat == category
                    Box(
                        modifier = Modifier
                            .background(if (sel) Brand.teal else Brand.navyRaised, RoundedCornerShape(8.dp))
                            .clickable { category = cat }
                            .padding(horizontal = 14.dp, vertical = 7.dp)
                    ) {
                        Text(
                            cat.label,
                            color = if (sel) Brand.navy else Brand.textSecondary,
                            fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                category.blurb,
                style = MaterialTheme.typography.bodySmall,
                color = Brand.textSecondary,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            )

            if (showAudio) {
                Spacer(Modifier.height(10.dp))
                ReferenceAudioControls(modifier = Modifier.padding(horizontal = 16.dp))
            }

            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("Search", color = Brand.textSecondary) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = Brand.textSecondary) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Clear, contentDescription = "Clear search", tint = Brand.textSecondary)
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Brand.teal,
                    unfocusedBorderColor = Brand.hairline,
                    focusedTextColor = Brand.textPrimary,
                    unfocusedTextColor = Brand.textPrimary,
                    cursorColor = Brand.teal
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            )

            Spacer(Modifier.height(10.dp))

            val q = query.trim()
            val rows = if (q.isEmpty()) category.items
            else category.items.filter {
                it.display.contains(q, ignoreCase = true) || it.answer.contains(q, ignoreCase = true)
            }

            if (rows.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = Brand.teal.copy(alpha = 0.5f), modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("No matches for “$query”", style = MaterialTheme.typography.bodySmall, color = Brand.textSecondary)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)
                ) {
                    items(rows, key = { it.id }) { item ->
                        ReferenceRow(
                            item = item,
                            isPlaying = playingId == item.id,
                            onOpen = { detailItem = item },
                            onPlay = { play(item) }
                        )
                    }
                }
            }
        }
    }
}

/** One reference entry: token + meaning, the dot-dash trailing, a speaker, and a tap target that opens detail. */
@Composable
private fun ReferenceRow(item: MorseItem, isPlaying: Boolean, onOpen: () -> Unit, onPlay: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .brandCard()
            .clickable(onClick = onOpen)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.display,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                color = Brand.textPrimary
            )
            Text(item.answer, style = MaterialTheme.typography.bodySmall, color = Brand.textSecondary)
        }
        Text(
            MorseReference.morseString(item),
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            color = Brand.teal,
            maxLines = 1
        )
        IconButton(onClick = onPlay) {
            Icon(
                if (isPlaying) Icons.Filled.VolumeUp else Icons.Filled.PlayCircleOutline,
                contentDescription = "Play ${item.display} in Morse code",
                tint = if (isPlaying) Brand.tealBright else Brand.teal
            )
        }
    }
}

/** The full per-signal screen: the token large, its rhythm, meaning, prosign detail, and playback controls. */
@Composable
private fun ReferenceDetailScreen(item: MorseItem, isPlaying: Boolean, onPlay: () -> Unit, onBack: () -> Unit) {
    val detail = MorseReference.detail(forDisplay = item.display)
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("‹ Back", color = Brand.teal) }
        }
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // Header
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(item.display, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 44.sp, color = Brand.textPrimary)
                Text(MorseReference.morseString(item), fontFamily = FontFamily.Monospace, fontSize = 22.sp, color = Brand.teal)
                MorseReference.rhythm(item)?.let {
                    Text(it, style = MaterialTheme.typography.bodyLarge, color = Brand.textSecondary)
                }
                Box(
                    modifier = Modifier
                        .background(Brand.teal, RoundedCornerShape(24.dp))
                        .clickable(onClick = onPlay)
                        .padding(horizontal = 18.dp, vertical = 10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(if (isPlaying) Icons.Filled.VolumeUp else Icons.Filled.PlayCircleOutline, contentDescription = null, tint = Brand.navy, modifier = Modifier.size(20.dp))
                        Text(if (isPlaying) "Playing…" else "Play", color = Brand.navy, fontWeight = FontWeight.Bold)
                    }
                }
            }

            detail?.summary?.takeIf { it.isNotEmpty() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = Brand.textSecondary)
            }
            detail?.ituName?.let { Field("ITU / operational name", it) }
            detail?.alsoWritten?.takeIf { it.isNotEmpty() }?.let { Field("Also written", it.joinToString("   ")) }
            Field("Meaning", item.answer)
            detail?.description?.takeIf { it.isNotEmpty() }?.let { Field("About", it) }
            detail?.citations?.takeIf { it.isNotEmpty() }?.let { Citations(it) }

            HorizontalDivider(color = Brand.hairline)
            Text("Playback", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Brand.textPrimary)
            ReferenceAudioControls()
        }
    }
}

@Composable
private fun Field(title: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Brand.textSecondary)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = Brand.textPrimary)
    }
}

@Composable
private fun Citations(citations: List<MorseReference.Citation>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("HISTORY & SOURCES", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Brand.textSecondary)
        citations.forEach { c ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(c.date, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Brand.teal, modifier = Modifier.width(64.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(c.label, style = MaterialTheme.typography.bodyMedium, color = Brand.textPrimary)
                    Text(c.source, fontSize = 11.sp, color = Brand.textSecondary)
                }
            }
        }
    }
}

/**
 * Speed / pitch / Farnsworth sliders bound to the shared [Settings], so adjusting
 * them here changes playback everywhere (the same settings the drills use).
 */
@Composable
fun ReferenceAudioControls(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().brandCard().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        RefSlider("Side tone", "${Settings.sidetoneHz.toInt()} Hz", Settings.sidetoneHz.toFloat(), 300f..1000f) {
            Settings.updateSidetoneHz(it.toDouble())
        }
        RefSlider("Speed", "${Settings.characterWpm.toInt()} WPM", Settings.characterWpm.toFloat(), 15f..40f) {
            Settings.updateCharacterWpm(it.toDouble())
        }
        val farnsworth = Settings.effectiveWpm < Settings.characterWpm
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Farnsworth spacing", style = MaterialTheme.typography.bodyMedium, color = Brand.textPrimary)
            Switch(
                checked = farnsworth,
                onCheckedChange = { on ->
                    // On → ease the effective speed below the character speed; off → lock them together.
                    if (on) Settings.updateEffectiveWpm((Settings.characterWpm - 5.0).coerceAtLeast(5.0))
                    else Settings.updateEffectiveWpm(Settings.characterWpm)
                },
                colors = SwitchDefaults.colors(checkedThumbColor = Brand.navy, checkedTrackColor = Brand.teal)
            )
        }
        if (farnsworth) {
            RefSlider("Effective speed", "${Settings.effectiveWpm.toInt()} WPM",
                Settings.effectiveWpm.toFloat(), 5f..Settings.characterWpm.toFloat().coerceAtLeast(6f)) {
                Settings.updateEffectiveWpm(it.toDouble())
            }
        }
    }
}

@Composable
private fun RefSlider(label: String, value: String, position: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = Brand.textPrimary)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = Brand.teal, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = position.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = Brand.teal,
                activeTrackColor = Brand.teal,
                inactiveTrackColor = Brand.navyRaised
            ),
            modifier = Modifier.height(28.dp)
        )
    }
}
