package app.anothermorsetrainer.morsekit

import java.text.Normalizer

/**
 * Turns arbitrary "real world" text (news headlines, RSS summaries, pasted
 * prose) into text this trainer can actually key: uppercase letters, digits,
 * and the small set of punctuation in [MorseCode]. The sanitized string is
 * both what gets sent and what gets revealed, so the learner's copy can be
 * checked character for character against what was played.
 *
 * Ported from MorseKit/CWText.swift.
 */
object CWText {

    // ---- Sendability ----

    /** True when the player can key this character (space separates words and
     *  counts as sendable). */
    fun isSendable(ch: Char): Boolean {
        if (ch == ' ') return true
        return MorseCode.pattern(ch) != null
    }

    /** True when every character of [text] can be keyed as-is. */
    fun isFullySendable(text: String): Boolean = text.all { isSendable(it) }

    // ---- HTML stripping (RSS descriptions) ----

    /**
     * Removes markup tags and decodes character entities, leaving plain prose.
     * RSS descriptions arrive either as escaped HTML ("&lt;p&gt;…") or as raw
     * tags inside CDATA, so entities are decoded both before and after the
     * tags are dropped.
     */
    fun strippedHTML(raw: String): String {
        val s = decodedEntities(raw)
        val out = StringBuilder(s.length)
        var inTag = false
        for (ch in s) {
            when {
                ch == '<' -> inTag = true
                ch == '>' -> if (inTag) { inTag = false; out.append(' ') } else out.append(ch)
                !inTag -> out.append(ch)
            }
        }
        return decodedEntities(out.toString())
    }

    /** The named entities that actually show up in news feeds. Unknown
     *  entities are left untouched. */
    private val namedEntities: Map<String, String> = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to " ", "ndash" to "–", "mdash" to "—",
        "hellip" to "…", "lsquo" to "‘", "rsquo" to "’",
        "ldquo" to "“", "rdquo" to "”",
        "copy" to "", "reg" to "", "trade" to "", "shy" to ""
    )

    private fun decodedEntities(s: String): String {
        if ('&' !in s) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            if (s[i] == '&') {
                val semi = s.indexOf(';', i)
                if (semi in (i + 1)..(i + 9)) {
                    val body = s.substring(i + 1, semi)
                    val named = namedEntities[body.lowercase()]
                    if (named != null) {
                        out.append(named)
                        i = semi + 1
                        continue
                    }
                    if (body.startsWith("#")) {
                        val digits = body.drop(1)
                        val value = if (digits.startsWith("x") || digits.startsWith("X")) {
                            digits.drop(1).toIntOrNull(16)
                        } else {
                            digits.toIntOrNull()
                        }
                        if (value != null && value in 1..0x10FFFF) {
                            out.appendCodePoint(value)
                            i = semi + 1
                            continue
                        }
                    }
                }
            }
            out.append(s[i])
            i++
        }
        return out.toString()
    }

    private fun StringBuilder.appendCodePoint(cp: Int): StringBuilder =
        append(String(Character.toChars(cp)))

    // ---- Sanitizing to the sendable set ----

    /** Typographic characters folded to plain ASCII before the main pass. */
    private val typographic: Map<Char, String> = mapOf(
        '‘' to "'", '’' to "'", '‚' to "'", 'ʼ' to "'", '`' to "'", '´' to "'",
        '“' to "\"", '”' to "\"", '„' to "\"", '«' to "\"", '»' to "\"",
        '–' to "-", '—' to "-", '―' to "-", '−' to "-",
        '…' to "...",
        '\u00A0' to " ", '\u2007' to " ", '\u2009' to " ", '\u200A' to " ", '\u202F' to " ",
        '\u200B' to "", '\uFEFF' to "",
        // Letters that diacritic folding leaves alone.
        'Ø' to "O", 'ø' to "o", 'Æ' to "AE", 'æ' to "ae",
        'Œ' to "OE", 'œ' to "oe", 'ß' to "ss",
        'Ð' to "D", 'ð' to "d", 'Þ' to "TH", 'þ' to "th",
        'Ł' to "L", 'ł' to "l", 'Đ' to "D", 'đ' to "d"
    )

    /**
     * Reduces [raw] to uppercase text made only of characters the player can
     * key (letters, digits, space, and . , / ? =). Symbols with a spoken CW
     * convention are spelled out (& → AND); everything unmappable becomes a
     * word break. Idempotent on already-clean text apart from uppercasing.
     */
    fun sanitized(raw: String): String {
        // 1) Typographic → ASCII, then fold accents (é → e) and uppercase.
        val pre = StringBuilder(raw.length)
        for (ch in raw) {
            val mapped = typographic[ch]
            if (mapped != null) pre.append(mapped) else pre.append(ch)
        }
        val folded = Normalizer.normalize(pre.toString(), Normalizer.Form.NFKD)
            .replace(COMBINING_MARKS, "")
            .uppercase()

        // 2) Character-by-character mapping into the sendable set.
        val chars = folded.toCharArray()
        val out = StringBuilder(chars.size)
        for ((i, ch) in chars.withIndex()) {
            when {
                ch in 'A'..'Z' || ch in '0'..'9' -> out.append(ch)
                ch == ',' -> {
                    // "1,234" is keyed as 1234 — drop the thousands separator.
                    val prevDigit = i > 0 && chars[i - 1].isDigit()
                    val nextDigit = i + 1 < chars.size && chars[i + 1].isDigit()
                    if (!(prevDigit && nextDigit)) out.append(ch)
                }
                ch == '.' || ch == '/' || ch == '?' || ch == '=' -> out.append(ch)
                ch == '\'' -> {}                    // DON'T → DONT, house style
                ch == ':' || ch == ';' -> out.append(',')
                ch == '!' -> out.append('.')
                ch == '&' -> out.append(" AND ")
                ch == '+' -> out.append(" PLUS ")
                ch == '%' -> out.append(" PERCENT ")
                ch == '@' -> out.append(" AT ")
                ch == '$' || ch == '£' || ch == '€' || ch == '¥' -> {}
                else -> out.append(' ')             // hyphens, quotes, anything else
            }
        }

        // 3) Tidy up: collapse spaces, reattach punctuation, squash runs.
        var t = out.toString().split(' ').filter { it.isNotEmpty() }.joinToString(" ")
        for (p in listOf(".", ",", "?")) {
            while (t.contains(" $p")) t = t.replace(" $p", p)
        }
        for (run in listOf("..", ",,", ",.", ".,", "?.", "?,")) {
            while (t.contains(run)) t = t.replace(run, run.first().toString())
        }
        while (t.isNotEmpty() && (t.first() == '.' || t.first() == ',')) {
            t = t.drop(1).trim()
        }
        return t.trim()
    }

    private val COMBINING_MARKS = Regex("\\p{Mn}+")

    /**
     * Clips [text] to at most [maxWords] words, preferring to end at a
     * sentence boundary so a trimmed news summary still reads whole.
     */
    fun clipped(text: String, maxWords: Int): String {
        val words = text.split(' ').filter { it.isNotEmpty() }
        if (words.size <= maxWords) return text
        val kept = words.take(maxWords)
        // Cut back to the last sentence end, if one lands in the kept range.
        val lastStop = kept.indexOfLast { it.endsWith(".") || it.endsWith("?") }
        if (lastStop >= maxWords / 3) {
            return kept.subList(0, lastStop + 1).joinToString(" ")
        }
        var body = kept.joinToString(" ")
        while (body.isNotEmpty() && (body.last() == '.' || body.last() == ',')) {
            body = body.dropLast(1)
        }
        return "$body."
    }
}
