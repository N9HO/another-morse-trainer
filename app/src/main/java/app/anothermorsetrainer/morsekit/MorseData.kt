package app.anothermorsetrainer.morsekit

/**
 * One thing the trainer can play and quiz on — a character, a word, an
 * abbreviation, or a prosign. The user hears [playable] in Morse and the
 * correct multiple-choice answer is [answer].
 *
 * Translated from MorseKit/MorseData.swift. Swift's `enum Playable` with
 * associated values becomes a Kotlin `sealed class`; the named-tuple lists
 * (`(token:, meaning:)` etc.) become small `data class`es.
 */
data class MorseItem(
    val id: String,
    /** What gets sounded out in Morse. */
    val playable: Playable,
    /** The correct multiple-choice answer (a meaning, a word, or a character). */
    val answer: String,
    /** Big label shown when revealing the answer (e.g. "ES", "<AR>", "X"). */
    val display: String
) {
    sealed class Playable {
        /** characters sent with normal spacing (word/abbr) */
        data class Text(val value: String) : Playable()
        /** raw dot-dash sent run-together (prosigns) */
        data class Pattern(val value: String) : Playable()
    }

    /** Concatenated dot-dash pattern, used to find sound-alike distractors. */
    val soundKey: String
        get() = when (playable) {
            is Playable.Pattern -> playable.value
            is Playable.Text -> playable.value.mapNotNull { MorseCode.pattern(it) }.joinToString("")
        }
}

/** A token paired with its plain-language meaning (abbreviations, Q-codes). */
data class TokenMeaning(val token: String, val meaning: String)

/** A prosign: its display name, run-together pattern, and meaning. */
data class Prosign(val name: String, val pattern: String, val meaning: String)

/**
 * A bundled practice passage for continuous copy (the "Short Stories" mode).
 * Translated from MorseKit/MorseDataStories.swift.
 */
data class Story(val id: String, val title: String, val text: String) {
    /** Rough length label for the picker (word count bucket). */
    val lengthLabel: String
        get() {
            val words = text.split(" ").size
            return when {
                words <= 30 -> "short"
                words <= 55 -> "medium"
                else -> "long"
            }
        }
}

/**
 * Curated ham-radio reference data (high-frequency words, abbreviations,
 * Q-codes, and prosigns) used to build the quiz modes. Sourced from Morse
 * Code Ninja, ARRL, KB6NU, and the ITU prosign spec.
 */
object MorseData {

    // ---- Common words (frequency-ordered; the basis of MCN's "Top N Words") ----

    val commonWords: List<String> = listOf(
        "THE", "OF", "AND", "TO", "A", "IN", "FOR", "IS", "ON", "THAT",
        "BY", "THIS", "WITH", "YOU", "IT", "NOT", "OR", "BE", "ARE", "FROM",
        "AT", "AS", "YOUR", "ALL", "HAVE", "NEW", "MORE", "WAS", "WE", "WILL",
        "HOME", "CAN", "ABOUT", "IF", "MY", "HAS", "BUT", "OUR", "ONE", "DO",
        "TIME", "THEY", "UP", "WHAT", "WHICH", "OUT", "ANY", "THERE", "SEE", "ONLY",
        "SO", "HIS", "WHEN", "HERE", "WHO", "NOW", "HELP", "GET", "FIRST", "BEEN",
        "HOW", "SOME", "LIKE", "THAN", "FIND", "BACK", "NAME", "JUST", "OVER", "YEAR",
        "DAY", "TWO", "NEXT", "GO", "WORK", "LAST", "MOST", "MAKE", "GOOD", "WELL",
        "VERY", "NEED", "KNOW", "WAY", "PART", "GREAT", "REAL", "MUST", "MADE", "LINE",
        "SEND", "RIGHT", "WANT", "LONG", "CODE", "SHOW", "SAME", "FOUND", "BOTH", "CALL",
        "WORD", "LOOK", "COME", "SOUND", "THING", "WRITE"
    )

    // ---- Ham/QSO vocabulary (heard constantly on the air) ----

    val hamWords: List<String> = listOf(
        "NAME", "RIG", "ANT", "WX", "HR", "HW", "QSO", "QTH", "RST", "RPT",
        "TNX", "PWR", "FB", "OM", "YL", "XYL", "DX", "SIG", "TEMP", "KEY",
        "GUD", "COPY", "HOPE", "AGN", "FREQ", "BAND", "DIPOLE", "BEAM", "WIRE", "CONTEST"
    )

    // ---- Abbreviations → meaning ("what are they saying?") ----

    val abbreviations: List<TokenMeaning> = listOf(
        TokenMeaning("ABT", "about"), TokenMeaning("AGN", "again"), TokenMeaning("ANT", "antenna"), TokenMeaning("BCNU", "be seeing you"),
        TokenMeaning("BK", "break"), TokenMeaning("B4", "before"), TokenMeaning("CFM", "confirm"), TokenMeaning("CL", "closing down"),
        TokenMeaning("CPY", "copy"), TokenMeaning("CQ", "calling any station"), TokenMeaning("CUL", "see you later"),
        TokenMeaning("DE", "this is / from"), TokenMeaning("DR", "dear"), TokenMeaning("DX", "distance"), TokenMeaning("ES", "and"),
        TokenMeaning("FB", "fine business (great)"), TokenMeaning("FER", "for"), TokenMeaning("GA", "good afternoon"),
        TokenMeaning("GE", "good evening"), TokenMeaning("GM", "good morning"), TokenMeaning("GN", "good night"),
        TokenMeaning("GND", "ground"), TokenMeaning("GUD", "good"), TokenMeaning("HI", "laughter"), TokenMeaning("HR", "here"),
        TokenMeaning("HV", "have"), TokenMeaning("HW", "how do you copy"), TokenMeaning("NR", "number"), TokenMeaning("OB", "old boy"),
        TokenMeaning("OM", "old man"), TokenMeaning("OP", "operator"), TokenMeaning("PSE", "please"), TokenMeaning("PWR", "power"),
        TokenMeaning("RPT", "repeat / report"), TokenMeaning("RST", "signal report"), TokenMeaning("RIG", "radio"),
        TokenMeaning("SED", "said"), TokenMeaning("SIG", "signal"), TokenMeaning("SKED", "schedule"), TokenMeaning("SN", "soon"),
        TokenMeaning("SRI", "sorry"), TokenMeaning("TFC", "traffic"), TokenMeaning("TNX", "thanks"), TokenMeaning("TU", "thank you"),
        TokenMeaning("UR", "your / you're"), TokenMeaning("VY", "very"), TokenMeaning("WID", "with"), TokenMeaning("WKD", "worked"),
        TokenMeaning("WL", "well"), TokenMeaning("WX", "weather"), TokenMeaning("YL", "young lady"),
        TokenMeaning("73", "best regards"), TokenMeaning("88", "love and kisses")
    )

    // ---- Q-codes → meaning ----

    val qCodes: List<TokenMeaning> = listOf(
        TokenMeaning("QRG", "your exact frequency is"), TokenMeaning("QRL", "is this frequency in use?"),
        TokenMeaning("QRM", "man-made interference"), TokenMeaning("QRN", "atmospheric noise / static"),
        TokenMeaning("QRO", "increase power"), TokenMeaning("QRP", "low power"),
        TokenMeaning("QRQ", "send faster"), TokenMeaning("QRS", "send slower"),
        TokenMeaning("QRT", "stop sending / going off air"), TokenMeaning("QRU", "I have nothing for you"),
        TokenMeaning("QRV", "I am ready"), TokenMeaning("QRX", "wait / stand by"),
        TokenMeaning("QRZ", "who is calling me?"), TokenMeaning("QSB", "your signals are fading"),
        TokenMeaning("QSK", "full break-in"), TokenMeaning("QSL", "acknowledge / received"),
        TokenMeaning("QSO", "a contact"), TokenMeaning("QSP", "relay a message"),
        TokenMeaning("QSY", "change frequency"), TokenMeaning("QTH", "my location is"),
        TokenMeaning("QTR", "the correct time is")
    )

    // ---- Call signs (realistic structure for word/call-sign practice) ----

    val callSigns: List<String> = listOf(
        "W1AW", "K9LA", "N0AX", "AA3B", "K4XYZ", "W7PHX", "N5XJ", "K0XYZ",
        "VE3KP", "G3ABC", "DL1XX", "JA1ABC", "VK2DEF", "KH6OO", "WB2OSZ",
        "W5KFT", "K3LR", "N2IC", "W6OAT", "K1TTT"
    )

    // ---- Operator names & QTHs (for the QSO simulator) ----

    /** Common operator first names heard on the air (ragchew "NAME" field). */
    val opNames: List<String> = listOf(
        "JIM", "BOB", "TOM", "DAVE", "JOHN", "MIKE", "BILL", "STEVE", "DAN", "PAUL",
        "GARY", "KEN", "RON", "RICK", "JOE", "FRANK", "ED", "AL", "PHIL", "MARK",
        "PETE", "SAM", "CARL", "RAY", "LARRY", "JACK", "ROY", "HANK", "WALT", "ART"
    )

    /** All US state (plus DC) abbreviations — for Rapid Fire's state-abbreviation stream. */
    val usStates: List<String> = listOf(
        "AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "FL", "GA",
        "HI", "ID", "IL", "IN", "IA", "KS", "KY", "LA", "ME", "MD",
        "MA", "MI", "MN", "MS", "MO", "MT", "NE", "NV", "NH", "NJ",
        "NM", "NY", "NC", "ND", "OH", "OK", "OR", "PA", "RI", "SC",
        "SD", "TN", "TX", "UT", "VT", "VA", "WA", "WV", "WI", "WY", "DC"
    )

    /** QTH locations (US state abbreviations) for the ragchew "QTH" field. */
    val qthList: List<String> = listOf(
        "OH", "TX", "CA", "NY", "FL", "PA", "IL", "MI", "GA", "NC",
        "VA", "WA", "AZ", "CO", "OR", "TN", "MO", "IN", "WI", "MN",
        "KS", "IA", "OK", "AR", "UT", "NV", "ME", "NH", "VT", "ID"
    )

    /** Common signal reports given in a QSO. */
    val rstValues: List<String> = listOf(
        "599", "579", "559", "569", "589", "539", "449", "459", "558", "578"
    )

    // ---- Short public-domain passages (Aesop's fables, plainly retold) for ----
    // continuous copy. Kept short and free of apostrophes/quotes so displayed
    // text matches what can be sent in Morse cleanly. Original retellings — no
    // copyrighted text copied verbatim.

    val stories: List<Story> = listOf(
        Story("fox-grapes", "The Fox and the Grapes",
            "A hungry fox saw clusters of ripe grapes hanging high on a vine. He jumped again and again but could not reach them. At last he gave up and walked away, saying the grapes were surely sour."),
        Story("tortoise-hare", "The Tortoise and the Hare",
            "A hare mocked a tortoise for being slow, so they agreed to race. The hare ran ahead and lay down to nap, sure of winning. The tortoise kept a steady pace and passed the sleeping hare to win."),
        Story("lion-mouse", "The Lion and the Mouse",
            "A lion caught a tiny mouse but let it go. Later the lion was caught in a hunters net. The little mouse heard him roar and gnawed the ropes until the lion was free. Even the small can help the great."),
        Story("crow-pitcher", "The Crow and the Pitcher",
            "A thirsty crow found a pitcher with a little water at the bottom, too low to reach. One by one she dropped in pebbles until the water rose to the top. Then she drank her fill. Patience and wit win the day."),
        Story("ant-grasshopper", "The Ant and the Grasshopper",
            "All summer the ant stored grain while the grasshopper sang and played. When winter came the grasshopper was hungry and cold. The ant had plenty. It is wise to prepare today for the needs of tomorrow."),
        Story("north-wind-sun", "The North Wind and the Sun",
            "The wind and the sun argued over who was stronger. They agreed the winner would make a traveler remove his coat. The wind blew hard but the man held tight. Then the sun shone warmly and he took it off."),
        Story("dog-bone", "The Dog and the Bone",
            "A dog carried a bone across a bridge and saw his own shadow in the water below. Thinking it was another dog with a larger bone, he snapped at it. His own bone fell into the river and was lost."),
        Story("golden-egg", "The Goose and the Golden Egg",
            "A farmer owned a goose that laid one golden egg each day. Greedy for more, he cut the goose open to take all the gold at once. He found nothing inside, and the goose was gone. Greed can ruin good fortune."),
        Story("wolf-crane", "The Wolf and the Crane",
            "A wolf had a bone stuck in his throat and begged a crane for help. The crane reached in with her long beak and pulled it out. When she asked for her reward, the wolf only laughed and walked away."),
        Story("oak-reeds", "The Oak and the Reeds",
            "A mighty oak stood proud beside a bed of slender reeds. A great storm came and the reeds bent low with the wind, but the stiff oak resisted and was torn up by the roots. Yielding can be its own strength.")
    )

    // ---- Prosigns → (run-together pattern, meaning) ----

    val prosigns: List<Prosign> = listOf(
        Prosign("<AR>", ".-.-.", "over — end of message"),
        Prosign("<K>", "-.-", "go ahead — any station"),
        Prosign("<KN>", "-.--.", "go ahead — named station only"),
        Prosign("<BT>", "-...-", "separator / new section"),
        Prosign("<SK>", "...-.-", "end of contact"),
        Prosign("<AS>", ".-...", "wait / stand by"),
        Prosign("<BK>", "-...-.-", "break — back to you"),
        Prosign("<CT>", "-.-.-", "attention / start"),
        Prosign("<CL>", "-.-..-..", "closing — going off air"),
        Prosign("<SN>", "...-.", "understood")
    )

    /** 500 words, most useful first, for the Top N word tiers (see MorseDataWords.kt). */
    val rankedWords: List<String> = rankedWordsData

    // ---- Item builders for each quiz mode ----

    /**
     * Words mode: hear the word, choose the word. Drawn from the ranked
     * (ham-weighted, frequency-ordered) list in MorseDataWords.kt, deduped
     * so every item id is unique.
     */
    val wordItems: List<MorseItem>
        get() = topWordItems(rankedWords.size)

    /** The most-useful [limit] words (the QRQ "Top N" tiers), deduplicated. */
    fun topWordItems(limit: Int): List<MorseItem> {
        val seen = mutableSetOf<String>()
        val items = mutableListOf<MorseItem>()
        for (w in rankedWords) {
            if (!seen.add(w)) continue
            items.add(MorseItem(id = "word-$w", playable = MorseItem.Playable.Text(w), answer = w, display = w))
            if (items.size >= limit) break
        }
        return items
    }

    /** Abbreviations mode: hear the abbreviation, choose its meaning. */
    val abbreviationItems: List<MorseItem>
        get() = abbreviations.map {
            MorseItem(id = it.token, playable = MorseItem.Playable.Text(it.token), answer = it.meaning, display = it.token)
        }

    /** Q-code mode: hear the three-letter Q-signal, choose what it means. */
    val qCodeItems: List<MorseItem>
        get() = qCodes.map {
            MorseItem(id = it.token, playable = MorseItem.Playable.Text(it.token), answer = it.meaning, display = it.token)
        }

    /** Prosign mode: hear the run-together prosign, choose its meaning. */
    val prosignItems: List<MorseItem>
        get() = prosigns.map {
            MorseItem(id = it.name, playable = MorseItem.Playable.Pattern(it.pattern), answer = it.meaning, display = it.name)
        }

    /** Call signs as drill items, where the answer is the call sign itself. */
    val callSignItems: List<MorseItem>
        get() = callSigns.map {
            MorseItem(id = "call-$it", playable = MorseItem.Playable.Text(it), answer = it, display = it)
        }

    /**
     * Words + call signs, where the answer is the text itself (used by the
     * advanced "Words & Call Signs" stage of the character ladder).
     */
    val wordAndCallSignItems: List<MorseItem>
        get() {
            val words = commonWords + hamWords.filter { it !in commonWords }
            val all = words + callSigns
            return all.map { MorseItem(id = it, playable = MorseItem.Playable.Text(it), answer = it, display = it) }
        }

    /**
     * Prosigns where the answer is the prosign token itself (recognize-by-sound,
     * used when prosigns are mixed into the advanced character stages).
     */
    val prosignTokenItems: List<MorseItem>
        get() = prosigns.map {
            MorseItem(id = it.name, playable = MorseItem.Playable.Pattern(it.pattern), answer = it.name, display = it.name)
        }
}
