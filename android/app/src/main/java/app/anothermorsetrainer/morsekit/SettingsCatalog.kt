package app.anothermorsetrainer.morsekit

import java.text.Normalizer

/*
 * The map of the Settings screen (#236): the categories its root lists, the
 * sections each category's sub-screen shows, and the search table the root's
 * search field filters. Pure data plus one matching rule, so JUnit can pin
 * it; the rows themselves are Compose in `SettingsScreen.kt`.
 *
 * The iOS port carries its own copy (`MorseKit/SettingsCatalog.swift`), and
 * `fixtures/settings-catalog.json` pins both to the same category names and
 * order, the same category for every setting the two apps share, and the
 * same answers to a set of search queries. Nothing is shared but that file.
 *
 * Adding a setting: put its row in the right section of `SettingsScreen`,
 * then add one [SettingsSearchEntry] to [SettingsCatalog.entries], in that
 * section's place in the list. If it is on both apps, add its id and category
 * to the fixture too. A new section is a new [SettingsSection] entry, placed
 * where it should appear in its category.
 */

/**
 * The rows on the Settings root, in the order they are listed. Titles are
 * behaviour: the guide and support answers name them, so they match the iOS
 * app word for word.
 */
enum class SettingsCategory(val id: String, val title: String) {
    SOUND("sound", "Sound"),
    SPEED("speed", "Speed & Timing"),
    CHARACTERS("characters", "Characters & Lessons"),
    PRACTICE("practice", "Practice & Feedback"),
    KEYS("keys", "Keys & Sending"),
    QSO("qso", "QSO & Pileups"),
    REMINDERS("reminders", "Reminders"),
    DISPLAY("display", "Display"),
    LEADERBOARD("leaderboard", "Leaderboard & Buddy"),
    ACCOUNT("account", "Account & Sync"),
    ABOUT("about", "Help & About");

    /** This category's sections, in screen order. */
    val sections: List<SettingsSection> get() = SettingsSection.entries.filter { it.category == this }

    companion object {
        fun byId(id: String?): SettingsCategory? = entries.firstOrNull { it.id == id }
    }
}

/**
 * One titled group of rows on a category's sub-screen. Declaration order is
 * the order within the category; a search result scrolls to its section.
 */
enum class SettingsSection(val category: SettingsCategory) {
    SOUND(SettingsCategory.SOUND),
    SPEED(SettingsCategory.SPEED),
    FARNSWORTH(SettingsCategory.SPEED),
    PROFICIENCY(SettingsCategory.CHARACTERS),
    NEW_CHARACTERS(SettingsCategory.CHARACTERS),
    TRACK_STAGE(SettingsCategory.CHARACTERS),
    PUNCTUATION(SettingsCategory.CHARACTERS),
    PREVIEW_STAGE(SettingsCategory.CHARACTERS),
    RESET(SettingsCategory.CHARACTERS),
    LEARNING(SettingsCategory.PRACTICE),
    ANSWER_ENTRY(SettingsCategory.PRACTICE),
    FEEDBACK(SettingsCategory.PRACTICE),
    HEAD_COPY(SettingsCategory.PRACTICE),
    HARDWARE_KEY(SettingsCategory.KEYS),
    ON_SCREEN_KEY(SettingsCategory.KEYS),
    YOUR_STATION(SettingsCategory.QSO),
    PILEUP_RUNNER(SettingsCategory.QSO),
    QSO_SIGNALS(SettingsCategory.QSO),
    QSO_REALISM(SettingsCategory.QSO),
    QSO_CALLSIGNS(SettingsCategory.QSO),
    REMINDERS(SettingsCategory.REMINDERS),
    DISPLAY(SettingsCategory.DISPLAY),
    LEADERBOARD(SettingsCategory.LEADERBOARD),
    BUDDY(SettingsCategory.LEADERBOARD),
    ACCOUNT(SettingsCategory.ACCOUNT),
    DEVICES(SettingsCategory.ACCOUNT),
    DELETE_ACCOUNT(SettingsCategory.ACCOUNT),
    BUG_REPORTS(SettingsCategory.ABOUT),
    ABOUT(SettingsCategory.ABOUT)
}

/** One searchable setting: what the result row says, the other words a person might type for it, and where it lives. */
data class SettingsSearchEntry(
    val id: String,
    val title: String,
    val keywords: List<String>,
    val section: SettingsSection
) {
    val category: SettingsCategory get() = section.category
}

object SettingsCatalog {
    private fun e(id: String, title: String, keywords: List<String>, section: SettingsSection) =
        SettingsSearchEntry(id, title, keywords, section)

    /** Every searchable setting, in category then section order — the order results keep within a ranking tier. */
    val entries: List<SettingsSearchEntry> = listOf(
        // Sound
        e("sidetone", "Sidetone pitch", listOf("side tone", "frequency", "hz", "tone", "note"), SettingsSection.SOUND),
        e("previewTone", "Preview tone", listOf("play", "test", "listen", "hear", "sample", "paris"), SettingsSection.SOUND),
        e("bluetoothKeepAlive", "Keep Bluetooth audio awake",
            listOf("earbuds", "headphones", "headset", "airpods", "clipping", "first character", "silence", "keep alive"), SettingsSection.SOUND),
        e("bandNoise", "Band noise", listOf("qrn", "static", "background", "hiss", "noise", "realism"), SettingsSection.SOUND),

        // Speed & Timing
        e("speed", "Character speed", listOf("speed", "wpm", "words per minute", "qrq", "fast", "slow", "koch"), SettingsSection.SPEED),
        e("farnsworth", "Farnsworth spacing", listOf("spacing", "gaps", "slow", "effective", "stretch"), SettingsSection.FARNSWORTH),
        e("effectiveSpeed", "Effective speed", listOf("farnsworth", "wpm", "spacing", "overall"), SettingsSection.FARNSWORTH),

        // Characters & Lessons
        e("proficiency", "Proficiency",
            listOf("starting level", "i already know", "beginner", "experience", "level", "skip"), SettingsSection.PROFICIENCY),
        e("introduceNew", "Introduce new characters", listOf("new letter", "first time", "lesson", "meet"), SettingsSection.NEW_CHARACTERS),
        e("trackStage", "Track stage", listOf("stage", "pairs", "triples", "words", "hold", "pin", "go back"), SettingsSection.TRACK_STAGE),
        e("punctuation", "Punctuation", listOf("period", "full stop", "comma", "slash", "symbols", "marks"), SettingsSection.PUNCTUATION),
        e("previewStage", "Preview stage (developer)", listOf("developer", "jump", "stage", "test", "debug"), SettingsSection.PREVIEW_STAGE),
        e("resetProgress", "Reset all progress", listOf("erase", "clear", "start over", "wipe", "delete", "stats"), SettingsSection.RESET),

        // Practice & Feedback
        e("recognizeWithin", "Recognize within",
            listOf("recognition target", "time", "seconds", "ttr", "mastered", "threshold", "new letter"), SettingsSection.LEARNING),
        e("answerChoices", "Answer choices", listOf("buttons", "options", "multiple choice"), SettingsSection.LEARNING),
        e("wordPool", "Word pool", listOf("common words", "vocabulary", "top", "100", "300", "500", "1000", "cw 77", "cwops"), SettingsSection.LEARNING),
        e("customWords", "Use my word list", listOf("my words", "custom words", "own list", "vocabulary"), SettingsSection.LEARNING),
        e("sessionLength", "Session length", listOf("duration", "timer", "minutes", "time limit"), SettingsSection.LEARNING),
        e("answerEntry", "Answer entry",
            listOf("keyboard", "type", "typing", "typed", "progressive", "tap", "buttons", "input"), SettingsSection.ANSWER_ENTRY),
        e("voiceAnswers", "Voice answers", listOf("speak", "speech", "microphone", "say", "spoken"), SettingsSection.ANSWER_ENTRY),
        e("showCorrectness", "Show right / wrong",
            listOf("correct", "incorrect", "colour", "color", "feedback", "mistakes"), SettingsSection.FEEDBACK),
        e("reveal", "Reveal answer", listOf("reveal the letter", "show answer", "correct answer"), SettingsSection.FEEDBACK),
        e("showReplay", "Show replay button", listOf("repeat", "again", "hear again"), SettingsSection.FEEDBACK),
        e("haptics", "Haptic feedback", listOf("vibration", "vibrate", "buzz"), SettingsSection.FEEDBACK),
        e("headCopyRepeats", "Head Copy auto-repeats", listOf("head copy", "replay", "repeat", "again"), SettingsSection.HEAD_COPY),
        e("headCopyReveal", "Head Copy auto-reveal", listOf("head copy", "countdown", "reveal", "answer", "seconds"), SettingsSection.HEAD_COPY),

        // Keys & Sending
        e("keyerMode", "Keyer mode",
            listOf("vail", "adapter", "paddle", "iambic", "straight key", "hardware key", "midi", "key", "bug", "cootie", "mode a", "mode b"),
            SettingsSection.HARDWARE_KEY),
        e("onScreenKey", "On-screen key",
            listOf("touch key", "straight key", "paddles", "pad", "tap", "on screen", "iambic"), SettingsSection.ON_SCREEN_KEY),
        e("paddleMode", "Paddle mode",
            listOf("iambic", "iambic a", "iambic b", "ultimatic", "keyer", "squeeze", "touch paddles"), SettingsSection.ON_SCREEN_KEY),
        e("paddleSwap", "Dah on the left",
            listOf("left handed", "lefty", "swap", "reverse", "paddles", "dit"), SettingsSection.ON_SCREEN_KEY),

        // QSO & Pileups
        e("myCall", "Your callsign", listOf("call sign", "call", "station", "my call", "w1aw"), SettingsSection.YOUR_STATION),
        e("myName", "Your name", listOf("name", "operator", "op", "cw 77", "cwops"), SettingsSection.YOUR_STATION),
        e("myState", "Your state", listOf("state", "province", "qth", "first four", "pota"), SettingsSection.YOUR_STATION),
        e("exchange", "Pileup Runner mode", listOf("exchange", "pota", "contest", "sprint", "cwt", "sst", "single caller", "mode"), SettingsSection.PILEUP_RUNNER),
        e("maxCallers", "Max callers", listOf("callers", "pileup size", "stations", "how many"), SettingsSection.PILEUP_RUNNER),
        e("callerMinSpeed", "Callers' min speed", listOf("caller speed", "slowest caller", "min speed", "wpm"), SettingsSection.PILEUP_RUNNER),
        e("callerMaxSpeed", "Callers' max speed", listOf("caller speed", "fastest caller", "max speed", "wpm"), SettingsSection.PILEUP_RUNNER),
        e("callerFarnsworth", "Callers' Farnsworth spacing", listOf("farnsworth", "spacing", "callers"), SettingsSection.PILEUP_RUNNER),
        e("toneSpread", "Tone spread", listOf("zero beat", "pitch", "frequency", "spread"), SettingsSection.PILEUP_RUNNER),
        e("qsb", "QSB (fading)", listOf("fading", "fade", "signal strength", "propagation"), SettingsSection.QSO_SIGNALS),
        e("qsoQrn", "QRN (noise)", listOf("static", "noise", "crashes", "atmospheric"), SettingsSection.QSO_SIGNALS),
        e("minWait", "Min wait", listOf("delay", "pause", "reply", "answer time"), SettingsSection.QSO_SIGNALS),
        e("maxWait", "Max wait", listOf("delay", "pause", "reply"), SettingsSection.QSO_SIGNALS),
        e("rstRequired", "Copy RST too", listOf("require the rst copied", "signal report", "599", "report"), SettingsSection.QSO_REALISM),
        e("keepPartialCall", "Keep my partial call after \"?\"", listOf("keep partial call in box", "partial", "question mark", "fill"), SettingsSection.QSO_REALISM),
        e("keyMySide", "Key my side in Morse", listOf("transmit", "send", "my side", "cq", "silent"), SettingsSection.QSO_REALISM),
        e("autoRecall", "Pileup re-calls after TU", listOf("recall", "call again", "tu", "agn"), SettingsSection.QSO_REALISM),
        e("bustBehavior", "On a busted call", listOf("bust", "wrong call", "mistake", "correction"), SettingsSection.QSO_REALISM),
        e("giveUp", "Callers can give up", listOf("impatient callers give up", "impatient", "give up", "drop out"), SettingsSection.QSO_REALISM),
        e("missedCallerFeedback", "Tell me who got away", listOf("missed", "lost", "summary"), SettingsSection.QSO_REALISM),
        e("cutNumbers", "Cut numbers", listOf("cut", "abbreviated numbers", "digits", "t for 0", "n for 9"), SettingsSection.QSO_REALISM),
        e("usOnly", "US callsigns only", listOf("dx", "prefix", "foreign", "us", "usa"), SettingsSection.QSO_CALLSIGNS),
        e("callsignFormats", "Callsign formats", listOf("shape", "1x1", "1x2", "2x3", "format", "prefix"), SettingsSection.QSO_CALLSIGNS),

        // Reminders
        e("dailyReminder", "Daily reminder", listOf("notification", "nudge", "alert", "streak", "reminder"), SettingsSection.REMINDERS),
        e("reminderTime", "Remind me at", listOf("reminder time", "time", "when", "hour", "alarm"), SettingsSection.REMINDERS),

        // Display
        e("slashedZero", "Slashed zero", listOf("0", "zero", "letter o", "slash", "font"), SettingsSection.DISPLAY),

        // Leaderboard & Buddy
        e("shareScores", "Share scores to the leaderboard",
            listOf("leaderboard", "post", "upload", "ranking", "high scores", "opt in"), SettingsSection.LEADERBOARD),
        e("displayName", "Display name", listOf("name", "callsign", "nickname", "handle"), SettingsSection.LEADERBOARD),
        e("deleteScores", "Delete my scores", listOf("remove", "erase", "privacy", "data"), SettingsSection.LEADERBOARD),
        e("buddyStreak", "Buddy streak", listOf("buddy", "buddies", "friend", "partner", "pair", "invite", "join", "code", "streak", "leave", "home screen", "hide"), SettingsSection.BUDDY),

        // Account & Sync (the optional account: sign-in, devices, delete)
        e("account", "Account",
            listOf("sign in", "sign out", "email", "sync", "callsign"), SettingsSection.ACCOUNT),
        e("devices", "Devices", listOf("signed in", "sign out", "phone", "computer"), SettingsSection.DEVICES),
        e("deleteAccount", "Delete account", listOf("remove", "erase", "privacy", "data"), SettingsSection.DELETE_ACCOUNT),

        // Help & About
        e("copyDiagnostics", "Copy diagnostic info",
            listOf("bug report", "debug", "support", "version", "logs", "problem"), SettingsSection.BUG_REPORTS),
        e("supportProject", "Support the project", listOf("donate", "tip", "coffee", "support"), SettingsSection.ABOUT),
        e("discord", "Join the Discord", listOf("community", "chat", "help"), SettingsSection.ABOUT),
        e("sourceCode", "Source on GitHub", listOf("source code", "github", "open source"), SettingsSection.ABOUT),
        e("licenses", "Licenses", listOf("licence", "gpl", "mit", "legal", "open source", "notices"), SettingsSection.ABOUT)
    )

    /**
     * The search rule, the same on both apps (and written out in the fixture's
     * `derivation`): the query and each entry are folded to lowercase without
     * accents and split into words at anything that is not a letter or digit.
     * An entry matches when every query word begins some word of its title,
     * its keywords or its category title. Entries whose title alone covers
     * every query word come first; otherwise catalog order. A query with no
     * words matches nothing.
     */
    fun search(query: String, within: List<SettingsSearchEntry> = entries): List<SettingsSearchEntry> {
        val tokens = words(query)
        if (tokens.isEmpty()) return emptyList()
        fun covers(haystack: List<String>) = tokens.all { token -> haystack.any { it.startsWith(token) } }
        val titleHits = mutableListOf<SettingsSearchEntry>()
        val otherHits = mutableListOf<SettingsSearchEntry>()
        for (entry in within) {
            val title = words(entry.title)
            if (covers(title)) {
                titleHits += entry
            } else if (covers(title + entry.keywords.flatMap { words(it) } + words(entry.category.title))) {
                otherHits += entry
            }
        }
        return titleHits + otherHits
    }

    /** Lowercased, accent-folded words: runs of letters and digits. */
    internal fun words(text: String): List<String> {
        val folded = Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
        return folded.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
    }
}
