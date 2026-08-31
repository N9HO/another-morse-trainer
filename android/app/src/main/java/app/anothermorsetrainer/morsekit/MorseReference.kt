package app.anothermorsetrainer.morsekit

/**
 * Reference-only data and helpers for the browsable Reference screen. These
 * aren't quiz modes — they back the "look it up" tables (cut numbers, the full
 * alphabet/number chart) and the per-signal detail view (spoken rhythm, ITU
 * name, "also written", description). Kept apart from the quiz item builders in
 * [MorseData] so the training data stays untouched.
 *
 * Translated from MorseKit/MorseReference.swift. Swift extended `MorseData`;
 * Kotlin keeps the reference material in its own [MorseReference] object so the
 * curated quiz data and the encyclopedic detail stay cleanly separated.
 */
object MorseReference {

    // ---- Cut numbers (contest shorthand) ----

    /** A contest "cut number": you *hear* [letter]; it *means* [digit]. */
    data class CutNumber(val digit: String, val letter: String)

    /**
     * Contest "cut numbers": a digit shortened to a single letter to save time.
     * You *hear* the letter; it *means* the digit.
     */
    val cutNumbers: List<CutNumber> = listOf(
        CutNumber("0", "T"), CutNumber("1", "A"), CutNumber("2", "U"), CutNumber("3", "V"),
        CutNumber("5", "E"), CutNumber("7", "G"), CutNumber("8", "D"), CutNumber("9", "N")
    )

    /**
     * Cut numbers as reference rows: the token shown is the letter you hear on
     * the air, the meaning is the digit it stands for, and the Morse played is
     * that letter's pattern.
     */
    val cutNumberItems: List<MorseItem>
        get() = cutNumbers.map {
            MorseItem(
                id = "cut-${it.digit}",
                playable = MorseItem.Playable.Text(it.letter),
                answer = "${it.digit} — cut ${spokenDigit(it.digit)}",
                display = it.letter
            )
        }

    // ---- Operating lingo (the spoken culture of ham radio) ----

    /**
     * Ham-radio jargon a newcomer hears in QSOs and club talk that isn't
     * (only) a sendable shorthand token — the *language* of operating, from
     * "ragchew" to "zero beat" (issue #76). Terms that are also on-air
     * shorthand (ES, 73, CQ, the Q-codes…) live in their own tables; this
     * list is the culture around them. Reference-only: it never feeds the
     * quiz pools, so adding terms can't change anyone's drills.
     */
    val lingo: List<TokenMeaning> = listOf(
        TokenMeaning("HAM",          "an amateur radio operator"),
        TokenMeaning("CW",           "continuous wave — what hams call Morse code on the air"),
        TokenMeaning("RAGCHEW",      "a long, relaxed conversational contact"),
        TokenMeaning("PILEUP",       "many stations calling the same station at once"),
        TokenMeaning("ELMER",        "a mentor who helps newcomers into the hobby"),
        TokenMeaning("FIST",         "the personal rhythm of an operator's hand-sent code"),
        TokenMeaning("ZERO BEAT",    "tuning your pitch to exactly match the other station"),
        TokenMeaning("SHACK",        "the corner of home where the radio gear lives"),
        TokenMeaning("LID",          "a sloppy or inconsiderate operator"),
        TokenMeaning("SILENT KEY",   "an operator who has passed away — written SK"),
        TokenMeaning("STRAIGHT KEY", "the classic up-and-down key: you time every element"),
        TokenMeaning("PADDLE",       "a lever key that feeds an electronic keyer"),
        TokenMeaning("KEYER",        "electronics that turn paddle squeezes into perfect code"),
        TokenMeaning("BUG",          "a semi-automatic mechanical key — it machine-makes the dits"),
        TokenMeaning("COPY",         "to receive code, on paper or in your head"),
        TokenMeaning("HEAD COPY",    "reading code entirely in your head, nothing written"),
        TokenMeaning("WORK",         "to complete a contact — you worked that station"),
        TokenMeaning("BUSTED CALL",  "a call sign copied or sent wrong"),
        TokenMeaning("POTA",         "Parks on the Air — portable operating from parks"),
        TokenMeaning("SOTA",         "Summits on the Air — operating from mountain tops"),
        TokenMeaning("WPM",          "words per minute — code speed, measured by the word PARIS"),
        TokenMeaning("FARNSWORTH",   "full-speed characters with stretched gaps while learning"),
        TokenMeaning("KOCH METHOD",  "learning at full speed, adding one character at a time")
    )

    /**
     * Lingo as reference rows: read the meaning, and — for the curious — tap
     * to hear the term itself in Morse like any other entry.
     */
    val lingoItems: List<MorseItem>
        get() = lingo.map {
            MorseItem(
                id = "lingo-${it.token}",
                playable = MorseItem.Playable.Text(it.token),
                answer = it.meaning,
                display = it.token
            )
        }

    // ---- Full Morse chart (letters · digits · punctuation) ----

    /**
     * The complete alphabet/number/punctuation chart, in reading order. The
     * "meaning" line carries the spoken rhythm (e.g. "dah-di-dah") since there's
     * nothing to translate — the value is hearing the shape.
     */
    val chartItems: List<MorseItem>
        get() {
            val order = ("ABCDEFGHIJKLMNOPQRSTUVWXYZ" + "0123456789" + ".,?'!/()&:;=+-_\"$@").toList()
            return order.mapNotNull { ch ->
                val pattern = MorseCode.pattern(ch) ?: return@mapNotNull null
                MorseItem(
                    id = "chart-$ch",
                    playable = MorseItem.Playable.Text(ch.toString()),
                    answer = spokenRhythm(pattern),
                    display = ch.toString()
                )
            }
        }

    // ---- Spoken rhythm ----

    /**
     * Turn a dot-dash pattern into the way operators say it out loud: dits are
     * "di" mid-character and "dit" when final, dahs are always "dah". So `-.-`
     * becomes "dah-di-dah" and `...-` becomes "di-di-di-dah".
     */
    fun spokenRhythm(pattern: String): String {
        val elements = pattern.toList()
        if (elements.isEmpty()) return ""
        return elements.mapIndexed { index, element ->
            if (element == '-') "dah"
            else if (index == elements.size - 1) "dit" else "di"
        }.joinToString("-")
    }

    /** Spell a single digit, for the cut-number meaning line. */
    private fun spokenDigit(digit: String): String = when (digit) {
        "0" -> "zero"; "1" -> "one"; "2" -> "two"; "3" -> "three"; "4" -> "four"
        "5" -> "five"; "6" -> "six"; "7" -> "seven"; "8" -> "eight"; "9" -> "nine"
        else -> digit
    }

    // ---- Per-signal encyclopedic detail ----

    /**
     * One dated definition from a primary source — the apparatus that turns a
     * reference entry into something you can trust and chase down.
     */
    data class Citation(
        /** Year (or year range) of the source, e.g. "1925" or "2010/2021". */
        val date: String,
        /** The publication or standard, e.g. "ITU-R M.1677-1". */
        val source: String,
        /** How that source defines or labels the signal. */
        val label: String
    )

    /**
     * Extra detail shown on the per-signal screen. Not every token has one — the
     * detail view falls back to the row's meaning and computed rhythm when
     * absent. Currently populated for prosigns and cut numbers, the signals that
     * most reward an explanation of when and why they're sent.
     */
    data class ReferenceDetail(
        /** The formal ITU/operational name, when the signal has one. */
        val ituName: String?,
        /** Other ways the same signal is written (e.g. "AR", "+"). */
        val alsoWritten: List<String>,
        /** A sentence or two on what it means and when it's used. */
        val description: String,
        /** A short, one-line gist shown above the full description. */
        val summary: String? = null,
        /** Dated, sourced definitions tracing how the signal has been defined over time. */
        val citations: List<Citation> = emptyList()
    )

    /** Detail keyed by the prosign token exactly as it appears in `prosigns`. */
    val prosignDetail: Map<String, ReferenceDetail> = mapOf(
        "<AR>" to ReferenceDetail(
            ituName = "End of message / Cross or addition sign (+)",
            alsoWritten = listOf("AR", "+"),
            description = "Ends a transmission when you are not handing over to a " +
                "named station — sent at the end of a CQ call and on blind " +
                "transmissions. Once a contact is established, K replaces it for " +
                "handing over. Identical to the punctuation \"+\".",
            summary = "End of transmission, no specific reply expected.",
            citations = listOf(
                Citation("1925", "QST Apr 1925 (Wallace)", "Finish sign"),
                Citation("1955", "ARRL Learning the Code, 7th ed.", "End of transmission / end of message"),
                Citation("2009", "ITU-R M.1677-1", "End of message / cross or addition sign (+)"),
                Citation("2010/2021", "IARU Ethics & Operating Procedures", "End of a transmission (not end of contact)")
            )
        ),
        "<K>" to ReferenceDetail(
            ituName = "Invitation to transmit (any station)",
            alsoWritten = listOf("K"),
            description = "\"Go ahead.\" Hands the transmission over and invites any " +
                "station to reply. Sent at the end of a call once contact is " +
                "established."
        ),
        "<KN>" to ReferenceDetail(
            ituName = "Invitation to a specific named station",
            alsoWritten = listOf("KN", "(", "[K]"),
            description = "\"Go ahead — you only.\" Like K, but invites a reply from " +
                "the specific station you're working and asks others to stand by."
        ),
        "<BT>" to ReferenceDetail(
            ituName = "Break / new section (double dash, =)",
            alsoWritten = listOf("BT", "=", "="),
            description = "Separates parts of a message — a pause or new paragraph. " +
                "Identical to the punctuation \"=\"."
        ),
        "<SK>" to ReferenceDetail(
            ituName = "End of work / end of contact",
            alsoWritten = listOf("SK", "VA"),
            description = "Closes out the whole contact, not just one transmission. " +
                "Often sent as the last thing before you sign clear.",
            summary = "End of contact — you're done working this station.",
            citations = listOf(
                Citation("1955", "ARRL Learning the Code, 7th ed.", "End of work (VA)"),
                Citation("2009", "ITU-R M.1677-1", "End of work …−·−·−")
            )
        ),
        "<AS>" to ReferenceDetail(
            ituName = "Wait / stand by",
            alsoWritten = listOf("AS"),
            description = "\"Wait a moment.\" Asks the other station to hold while you " +
                "do something — look up a detail, deal with a distraction."
        ),
        "<BK>" to ReferenceDetail(
            ituName = "Break-in",
            alsoWritten = listOf("BK"),
            description = "\"Back to you.\" A quick informal hand-over used in a " +
                "relaxed rag-chew to pass the transmission without a full sign-off."
        ),
        "<CT>" to ReferenceDetail(
            ituName = "Commencing / attention (KA)",
            alsoWritten = listOf("CT", "KA"),
            description = "Marks the start of a formal transmission — \"attention, here " +
                "it comes.\" Sent before the body of a message."
        ),
        "<CL>" to ReferenceDetail(
            ituName = "Closing station",
            alsoWritten = listOf("CL"),
            description = "\"Closing down — going off the air.\" Sent when you are " +
                "shutting the station, not merely ending a contact."
        ),
        "<SN>" to ReferenceDetail(
            ituName = "Understood / verified (VE)",
            alsoWritten = listOf("SN", "VE"),
            description = "\"Understood\" — acknowledges that what was sent was " +
                "received and verified."
        )
    )

    /**
     * Detail for the cut numbers, keyed by the letter you hear on the air. Cut
     * numbers are a modern amateur-contest convention, not part of the
     * historical ITU Morse standard — worth saying plainly, since the depth here
     * is knowing what *isn't* official.
     */
    val cutNumberDetail: Map<String, ReferenceDetail> = mapOf(
        "T" to ReferenceDetail(
            ituName = "Cut number for 0 (zero)",
            alsoWritten = listOf("0", "T"),
            description = "A long dah heard for 0 — the most common cut number, used " +
                "in RST reports and serials (5NN TT = 599 00). The T=0 cut has no " +
                "Paris 1865 precedent; it is a modern amateur contest convention, " +
                "not part of the ITU Morse standard.",
            summary = "Contest shorthand: 0 sent as the single letter T.",
            citations = listOf(
                Citation("2010/2021", "IARU Ethics & Operating Procedures", "599 RST courtesy context (implicit cut numbers)")
            )
        ),
        "N" to ReferenceDetail(
            ituName = "Cut number for 9 (nine)",
            alsoWritten = listOf("9", "N"),
            description = "A dah-dit heard for 9, most often inside RST reports " +
                "(599 → 5NN). Like T=0, the N=9 cut has no Paris 1865 precedent — " +
                "it is a modern amateur convention. Cut numbers must not be used " +
                "in call signs.",
            summary = "Contest shorthand: 9 sent as the single letter N.",
            citations = listOf(
                Citation("1865", "Paris Convention", "9 = ----. (no abbreviated/cut variant)"),
                Citation("2009", "ITU-R M.1677-1", "9 = ----. (cut form not in the standard)")
            )
        ),
        "A" to ReferenceDetail(
            ituName = "Cut number for 1 (one)",
            alsoWritten = listOf("1", "A"),
            description = "A di-dah heard for 1. A modern contest convention to save " +
                "time in serial numbers and reports, not part of the ITU standard.",
            summary = "Contest shorthand: 1 sent as the single letter A."
        ),
        "U" to ReferenceDetail(
            ituName = "Cut number for 2 (two)",
            alsoWritten = listOf("2", "U"),
            description = "Di-di-dah heard for 2, used in serials and exchanges. " +
                "An amateur convention rather than an official Morse character.",
            summary = "Contest shorthand: 2 sent as the single letter U."
        )
    )

    /** Detail for any reference token, checking prosigns first, then cut numbers. */
    fun detail(forDisplay: String): ReferenceDetail? =
        prosignDetail[forDisplay] ?: cutNumberDetail[forDisplay]

    // ---- Rendering helpers (were free functions in ReferenceView.swift) ----

    /**
     * Render an item's dot-dash as readable glyphs. Prosigns are one run-together
     * pattern; text tokens show each character's code separated by spaces.
     */
    fun morseString(item: MorseItem): String {
        fun glyphs(pattern: String) = pattern.map { if (it == '.') '·' else '−' }.joinToString("")
        return when (val p = item.playable) {
            is MorseItem.Playable.Pattern -> glyphs(p.value)
            is MorseItem.Playable.Text -> p.value.mapNotNull { ch ->
                MorseCode.pattern(ch)?.let { glyphs(it) }
            }.joinToString(" ")
        }
    }

    /**
     * The spoken rhythm for an item, when it's a single run-together shape (a
     * prosign or a single character). Multi-character tokens get null — their
     * per-character glyphs already tell the story.
     */
    fun rhythm(item: MorseItem): String? {
        return when (val p = item.playable) {
            is MorseItem.Playable.Pattern -> spokenRhythm(p.value)
            is MorseItem.Playable.Text -> {
                if (p.value.length != 1) return null
                val pattern = MorseCode.pattern(p.value[0]) ?: return null
                spokenRhythm(pattern)
            }
        }
    }
}
