package app.anothermorsetrainer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.anothermorsetrainer.morsekit.CWText
import app.anothermorsetrainer.morsekit.MorseItem
import app.anothermorsetrainer.morsekit.MorseSerials
import app.anothermorsetrainer.morsekit.MorseStories
import kotlinx.coroutines.delay

private enum class StPhase { RUNNING, SUMMARY }

/** One passage ready to send: what the header shows and what gets keyed. */
private data class Passage(val title: String, val text: String, val counter: String)

/** The fables shelf shares one bookmark under a reserved key. */
private const val FABLES_BOOKMARK_KEY = "fables"

/** Reuse a news fetch this recent (ms) instead of hitting the feed again. */
private const val NEWS_FRESH_WINDOW_MS = 30L * 60L * 1000L

/**
 * **Short Stories** (continuous copy): hear a passage sent end to end, copy it
 * on paper or in your head, then reveal the text to check yourself. Ported
 * from the iOS story flow, including the build-15 additions: pick a fable, a
 * longer classic sent in parts with a bookmark that keeps your place, or
 * todays news — real headlines hidden until you reveal, so the only way to
 * read them is to copy the code.
 */
@Composable
fun StoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val player = remember { MorsePlayer() }
    val fetcher = remember { NewsFetcher(context) }

    val content = Settings.storyContent
    val serial = remember(Settings.storySerialId) {
        MorseSerials.all.firstOrNull { it.id == Settings.storySerialId } ?: MorseSerials.all.first()
    }

    // The passage cursor for the active shelf, restored from its bookmark.
    var index by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var revealed by remember { mutableStateOf(false) }
    // Mid-session Settings, drawn over the session so its state lives on.
    var showSettings by remember { mutableStateOf(false) }
    // Bumped on every stop/next/content switch so an in-flight completion
    // callback (or a stale news fetch) can't flip state for the wrong passage.
    var generation by remember { mutableIntStateOf(0) }
    // The session itself — count, start and (below) phase, clock and whether
    // it was recorded — rides the saved-instance-state bundle, so a process
    // Android reclaims in the background comes back mid-session rather than
    // dropping it. The passage cursor needs no saving: it is bookmarked.
    var passagesCopied by rememberSaveable { mutableIntStateOf(0) }
    var startedAtMs by rememberSaveable { mutableLongStateOf(System.currentTimeMillis()) }

    // News-in-Morse: headlines are fetched, sanitized to the sendable charset,
    // and kept hidden until revealed — decoding is the only way to read them.
    var newsItems by remember { mutableStateOf<List<NewsFetcher.Item>>(emptyList()) }
    var newsFetching by remember { mutableStateOf(false) }
    var newsError by remember { mutableStateOf<String?>(null) }
    var newsFetchedAt by remember { mutableLongStateOf(0L) }
    var newsFetchedSource by remember { mutableStateOf<NewsSource?>(null) }
    var newsIsFromCache by remember { mutableStateOf(false) }

    var phase by rememberSaveable { mutableStateOf(StPhase.RUNNING) }
    var remaining by rememberSaveable(stateSaver = OptionalIntSaver) {
        mutableStateOf<Int?>(Settings.practiceDuration.seconds)
    }
    var recorded by rememberSaveable { mutableStateOf(false) }
    var milestone by remember { mutableStateOf<Int?>(null) }

    /** Headline (and optionally its clipped summary after a BT break) as a
     *  sendable passage list. */
    fun decodableNews(items: List<NewsFetcher.Item>): List<String> = items.mapNotNull { item ->
        val headline = CWText.sanitized(CWText.strippedHTML(item.title))
        if (headline.isEmpty()) return@mapNotNull null
        if (Settings.newsFullStory) {
            val summary = CWText.clipped(
                CWText.sanitized(CWText.strippedHTML(item.summary)), maxWords = 45
            )
            if (summary.isNotEmpty() && summary != headline) "$headline = $summary" else headline
        } else {
            headline
        }
    }

    val newsPassages = remember(newsItems, Settings.newsFullStory) { decodableNews(newsItems) }

    /** The current passage for the active shelf, or null (news not loaded). */
    val passage: Passage? = when (content) {
        StoryContent.FABLES -> {
            val stories = MorseStories.all
            val idx = ((index % stories.size) + stories.size) % stories.size
            val story = stories[idx]
            Passage(story.title, story.text, "Story ${idx + 1} of ${stories.size} · ${story.lengthLabel}")
        }
        StoryContent.SERIALS -> {
            val idx = index.coerceIn(0, serial.parts.size - 1)
            Passage(serial.title, serial.parts[idx], "Part ${idx + 1} of ${serial.parts.size}")
        }
        StoryContent.NEWS -> {
            if (newsPassages.isEmpty()) null
            else {
                val idx = ((index % newsPassages.size) + newsPassages.size) % newsPassages.size
                // The title stays generic so nothing is given away before reveal.
                Passage(Settings.newsSource.attribution, newsPassages[idx],
                    "Headline ${idx + 1} of ${newsPassages.size}")
            }
        }
    }

    /** The line under the title (what the passage is and where it came from). */
    val subtitle = when (content) {
        StoryContent.FABLES -> "Public-domain fable · continuous copy"
        StoryContent.SERIALS -> "${serial.author} · your bookmark keeps your place"
        StoryContent.NEWS -> {
            val source = Settings.newsSource.attribution
            if (newsIsFromCache) "$source · saved headlines" else "$source · decode the news"
        }
    }

    fun stopPlayback() {
        generation += 1
        player.stop()
        playing = false
    }

    /** Move the bookmark: opening a passage IS how far you have gotten. */
    fun rememberSpot() {
        when (content) {
            StoryContent.FABLES -> Settings.setStoryBookmark(FABLES_BOOKMARK_KEY, index)
            StoryContent.SERIALS -> Settings.setStoryBookmark(serial.id, index)
            StoryContent.NEWS -> {}
        }
    }

    /** Make sure headlines are loaded: reuse a recent fetch, hit the feed,
     *  or fall back to the last successful fetch on disk. */
    fun ensureNews() {
        val source = Settings.newsSource
        if (newsFetchedSource == source && newsItems.isNotEmpty() &&
            System.currentTimeMillis() - newsFetchedAt < NEWS_FRESH_WINDOW_MS
        ) {
            return
        }
        newsFetching = true
        newsError = null
        val gen = generation
        fetcher.fetch(
            source,
            onResult = { items ->
                newsFetching = false
                if (gen != generation) return@fetch
                newsItems = items
                newsFetchedSource = source
                newsFetchedAt = System.currentTimeMillis()
                newsIsFromCache = false
                index = 0
            },
            onError = { message ->
                newsFetching = false
                if (gen != generation) return@fetch
                val cachedResult = fetcher.cached(source)
                if (cachedResult != null) {
                    newsItems = cachedResult.first
                    newsFetchedSource = source
                    newsFetchedAt = cachedResult.second
                    newsIsFromCache = true
                    index = 0
                } else {
                    newsItems = emptyList()
                    newsFetchedSource = null
                    newsError = message
                }
            }
        )
    }

    /** Drop what we have and hit the feed again (Retry / fresh headlines). */
    fun refreshNews() {
        if (newsFetching) return
        stopPlayback()
        revealed = false
        newsFetchedSource = null
        newsFetchedAt = 0L
        ensureNews()
    }

    // Entering the screen, or switching the shelf/serial/source, lands on the
    // remembered spot (news always starts from the freshest headline).
    LaunchedEffect(content, serial.id, Settings.newsSource) {
        stopPlayback()
        revealed = false
        when (content) {
            StoryContent.FABLES -> index = Settings.storyBookmark(FABLES_BOOKMARK_KEY)
            StoryContent.SERIALS -> index =
                Settings.storyBookmark(serial.id).coerceIn(0, serial.parts.size - 1)
            StoryContent.NEWS -> {
                index = 0
                ensureNews()
            }
        }
        rememberSpot()
    }

    DisposableEffect(Unit) { onDispose { player.release() } }

    fun elapsedSeconds(): Int = ((System.currentTimeMillis() - startedAtMs) / 1000L).toInt()

    fun recordSession(): Int? {
        if (recorded) return null
        recorded = true
        return Stats.record(
            mode = "Stories",
            attempts = passagesCopied,
            correct = passagesCopied,
            bestTtrMs = null,
            durationSeconds = elapsedSeconds()
        )
    }

    fun endSession() {
        if (phase == StPhase.SUMMARY) return
        stopPlayback()
        milestone = recordSession()
        phase = StPhase.SUMMARY
    }

    fun finish() {
        stopPlayback()
        recordSession()
        onBack()
    }

    fun practiceAgain() {
        passagesCopied = 0
        recorded = false
        milestone = null
        startedAtMs = System.currentTimeMillis()
        remaining = Settings.practiceDuration.seconds
        revealed = false
        phase = StPhase.RUNNING
    }

    LaunchedEffect(phase) {
        if (phase != StPhase.RUNNING) return@LaunchedEffect
        while (true) {
            val r = remaining ?: return@LaunchedEffect
            if (r <= 0) {
                endSession()
                return@LaunchedEffect
            }
            delay(1000)
            remaining = remaining?.minus(1)
        }
    }

    BackHandler { if (phase == StPhase.SUMMARY) onBack() else finish() }

    fun play() {
        val text = passage?.text ?: return
        revealed = false
        playing = true
        generation += 1
        val gen = generation
        player.play(MorseItem.Playable.Text(text), Settings.sidetoneHz, Settings.timing()) {
            if (gen == generation) playing = false
        }
    }

    /** Advance to the next passage (does not auto-play). A serial wraps from
     *  its final part back to part one — rereading from the top. */
    fun next() {
        stopPlayback()
        revealed = false
        index = if (content == StoryContent.SERIALS) {
            (index + 1) % serial.parts.size
        } else {
            index + 1
        }
        rememberSpot()
    }

    /** Step back one part of a serial (re-copy what you missed). The bookmark
     *  moves back with you. */
    fun previous() {
        if (content != StoryContent.SERIALS || index <= 0) return
        stopPlayback()
        revealed = false
        index -= 1
        rememberSpot()
    }

    // The Box lets the mid-session Settings draw over the running session
    // without unmounting it (playback and the timer live on underneath).
    Box(modifier = Modifier.fillMaxSize()) {
    CenteredContent {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { if (phase == StPhase.SUMMARY) onBack() else finish() }) { Text("‹ Back") }
                if (phase == StPhase.RUNNING) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        remaining?.let {
                            Text(
                                "%d:%02d".format(it / 60, it % 60),
                                style = MaterialTheme.typography.labelMedium,
                                color = Brand.textSecondary
                            )
                        }
                        SessionSettingsButton { showSettings = true }
                        TextButton(onClick = { endSession() }) { Text("End") }
                    }
                }
            }

            if (phase == StPhase.SUMMARY) {
                StorySummaryContent(
                    passages = passagesCopied,
                    elapsedSeconds = elapsedSeconds(),
                    milestone = milestone,
                    onPracticeAgain = { practiceAgain() },
                    onDone = onBack
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Short Stories", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(10.dp))

                    // What to copy: fables, a serialized classic, or the news.
                    StoryContentRow(content) { pick ->
                        if (pick != content) {
                            stopPlayback()
                            revealed = false
                            Settings.updateStoryContent(pick)
                        }
                    }

                    if (content == StoryContent.SERIALS) {
                        Spacer(Modifier.height(8.dp))
                        SerialPickerRow(selected = serial) { pick ->
                            if (pick.id != serial.id) {
                                stopPlayback()
                                revealed = false
                                Settings.updateStorySerialId(pick.id)
                            }
                        }
                        val resume = Settings.storyBookmark(serial.id)
                            .coerceIn(0, serial.parts.size - 1)
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Bookmark, contentDescription = null,
                                tint = Brand.teal, modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (resume == 0)
                                    "A longer tale sent in short parts. Your bookmark moves as you go, so you can pick the story back up any day."
                                else
                                    "Your bookmark picks this story back up at part ${resume + 1} of ${serial.parts.size}.",
                                style = MaterialTheme.typography.labelMedium,
                                color = Brand.textSecondary
                            )
                        }
                    }

                    if (content == StoryContent.NEWS) {
                        Spacer(Modifier.height(8.dp))
                        NewsSourceRow(selected = Settings.newsSource) { pick ->
                            if (pick != Settings.newsSource) {
                                stopPlayback()
                                revealed = false
                                Settings.updateNewsSource(pick)
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Include the summary",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Brand.textPrimary,
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = Settings.newsFullStory,
                                onCheckedChange = {
                                    stopPlayback()
                                    revealed = false
                                    Settings.updateNewsFullStory(it)
                                },
                                colors = SwitchDefaults.colors(
                                    checkedTrackColor = Brand.teal,
                                    checkedThumbColor = Brand.navy
                                )
                            )
                        }
                    }

                    Spacer(Modifier.height(20.dp))
                    passage?.let {
                        Text(
                            text = it.title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = Brand.textPrimary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(it.counter, style = MaterialTheme.typography.labelMedium, color = Brand.textSecondary)
                    }
                    Text(subtitle, style = MaterialTheme.typography.labelMedium, color = Brand.textSecondary)

                    Spacer(Modifier.height(24.dp))

                    when {
                        content == StoryContent.NEWS && newsFetching -> {
                            CircularProgressIndicator(color = Brand.teal)
                            Spacer(Modifier.height(10.dp))
                            Text("Fetching todays headlines…", color = Brand.textSecondary)
                        }
                        content == StoryContent.NEWS && newsError != null -> {
                            Icon(
                                Icons.Filled.WifiOff, contentDescription = null,
                                tint = Brand.textSecondary, modifier = Modifier.size(44.dp)
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(newsError ?: "", color = Brand.textSecondary, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(10.dp))
                            OutlinedButton(onClick = { refreshNews() }) {
                                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Try Again")
                            }
                        }
                        revealed && passage != null -> {
                            SlashableText(
                                text = passage.text,
                                style = MaterialTheme.typography.bodyLarge,
                                color = Brand.textPrimary,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        else -> {
                            Icon(
                                if (content == StoryContent.NEWS) Icons.Filled.Newspaper
                                else Icons.AutoMirrored.Filled.MenuBook,
                                contentDescription = null,
                                tint = Brand.teal,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                when {
                                    playing -> "Sending… copy along"
                                    content == StoryContent.NEWS -> "Press Play, then decode the headline"
                                    else -> "Copy the passage, then reveal to check."
                                },
                                color = Brand.textSecondary,
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    Spacer(Modifier.height(28.dp))

                    val ready = passage != null && !(content == StoryContent.NEWS && newsFetching)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (content == StoryContent.SERIALS && index > 0) {
                            OutlinedButton(
                                onClick = { previous() },
                                enabled = !playing,
                                shape = RoundedCornerShape(Brand.cornerRadius),
                                modifier = Modifier.heightIn(min = 56.dp)
                            ) {
                                Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous part", modifier = Modifier.size(20.dp))
                            }
                        }
                        Button(
                            onClick = { if (playing) stopPlayback() else play() },
                            enabled = ready,
                            colors = ButtonDefaults.buttonColors(containerColor = Brand.teal, contentColor = Brand.navy),
                            shape = RoundedCornerShape(Brand.cornerRadius),
                            modifier = Modifier.weight(1f).heightIn(min = 56.dp)
                        ) { Text(if (playing) "■ Stop" else "▶ Play passage", fontWeight = FontWeight.SemiBold) }
                    }

                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            revealed = true
                            passagesCopied += 1
                        },
                        enabled = !revealed && !playing && ready,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Reveal text") }

                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { next() },
                        enabled = ready,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (content == StoryContent.NEWS) "Next headline ›" else "Next story ›") }
                }
            }
        }
    }

    if (showSettings) {
        SessionSettingsOverlay(scope = SettingsMode.STORY, onClose = { showSettings = false })
    }
    }
}

/** Three-way picker for what the mode sends. */
@Composable
private fun StoryContentRow(selected: StoryContent, onPick: (StoryContent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StoryContent.entries.forEach { c ->
            val on = c == selected
            OutlinedButton(
                onClick = { onPick(c) },
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = if (on) Brand.teal else Brand.navyElevated,
                    contentColor = if (on) Brand.navy else Brand.textSecondary
                ),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    c.label,
                    fontSize = 12.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1
                )
            }
        }
    }
}

/** Horizontal chips for picking which long tale to serialize. */
@Composable
private fun SerialPickerRow(selected: MorseSerials.Serial, onPick: (MorseSerials.Serial) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        MorseSerials.all.forEach { s ->
            val on = s.id == selected.id
            OutlinedButton(
                onClick = { onPick(s) },
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = if (on) Brand.teal else Brand.navyElevated,
                    contentColor = if (on) Brand.navy else Brand.textSecondary
                ),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(s.title, fontSize = 12.sp, maxLines = 1,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Medium)
            }
        }
    }
}

/** Horizontal chips for the news feed to decode. */
@Composable
private fun NewsSourceRow(selected: NewsSource, onPick: (NewsSource) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        NewsSource.entries.forEach { s ->
            val on = s == selected
            OutlinedButton(
                onClick = { onPick(s) },
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = if (on) Brand.teal else Brand.navyElevated,
                    contentColor = if (on) Brand.navy else Brand.textSecondary
                ),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(s.label, fontSize = 12.sp, maxLines = 1,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Medium)
            }
        }
    }
}

/**
 * Story sessions are self-paced continuous copy, so the summary counts passages
 * and time rather than the per-answer tallies the drill modes show.
 */
@Composable
private fun StorySummaryContent(
    passages: Int,
    elapsedSeconds: Int,
    milestone: Int?,
    onPracticeAgain: () -> Unit,
    onDone: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Session complete", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("Short Stories", style = MaterialTheme.typography.labelMedium, color = Brand.textSecondary)

        Spacer(Modifier.height(24.dp))
        Column(modifier = Modifier.fillMaxWidth().brandCard()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Passages copied", color = Brand.textSecondary)
                Text("$passages", color = Brand.textPrimary, fontWeight = FontWeight.SemiBold)
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Time", color = Brand.textSecondary)
                Text(
                    "%d:%02d".format(elapsedSeconds / 60, elapsedSeconds % 60),
                    color = Brand.textPrimary,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        milestone?.let { day ->
            Spacer(Modifier.height(16.dp))
            Column(
                modifier = Modifier.fillMaxWidth().brandCard().padding(vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(milestoneEmoji(day), fontSize = 40.sp)
                Text("$day-day streak!", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("New milestone reached — keep it going.", color = Brand.textSecondary, fontSize = 13.sp)
            }
        }

        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onPracticeAgain,
            colors = ButtonDefaults.buttonColors(containerColor = Brand.teal, contentColor = Brand.navy),
            shape = RoundedCornerShape(Brand.cornerRadius),
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
        ) { Text("Keep copying", fontWeight = FontWeight.SemiBold) }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("Return home")
        }
    }
}
