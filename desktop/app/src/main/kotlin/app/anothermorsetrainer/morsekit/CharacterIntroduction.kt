package app.anothermorsetrainer.morsekit

/**
 * What to show a learner the first time the Characters track presents an
 * item (issue #162): the character or prosign on its own, its pattern, and how
 * that pattern is voiced — before it is ever mixed into a drill, where the
 * only way to learn a new sound was to guess wrong at it.
 *
 * Translated from MorseKit/CharacterIntroduction.swift.
 */
data class CharacterIntroduction(
    /** "K" or "<AR>" — what the drill's `correct` answer will be. */
    val id: String,
    /** What to show large. */
    val display: String,
    /** The dot-dash pattern, run together for a prosign. */
    val pattern: String,
    /** A prosign's meaning; null for a plain character. */
    val meaning: String?,
    /** What to sound out, as the drill will send it. */
    val playable: MorseItem.Playable
) {
    val isProsign: Boolean get() = meaning != null
    /** "dah-di-dah". */
    val spokenPattern: String get() = spokenPattern(pattern)
    /** "− · −". */
    val symbolPattern: String get() = symbolPattern(pattern)

    companion object {
        /**
         * The introduction [drill] calls for, or null when there is nothing to
         * introduce: a group, a word, or a single item [isMet] vouches for. Only
         * single characters and prosigns are ever introduced — they are the only
         * things the track presents that have a sound of their own to learn.
         */
        fun forDrill(drill: Drill, isMet: (String) -> Boolean): CharacterIntroduction? {
            val intro = if (drill.correct.length == 1) {
                val ch = drill.correct[0]
                val pattern = MorseCode.pattern(ch) ?: return null
                val s = ch.uppercaseChar().toString()
                CharacterIntroduction(id = s, display = s, pattern = pattern, meaning = null,
                                      playable = MorseItem.Playable.Text(s))
            } else {
                val prosign = MorseData.prosigns.firstOrNull { it.name == drill.correct } ?: return null
                CharacterIntroduction(id = prosign.name, display = prosign.name, pattern = prosign.pattern,
                                      meaning = prosign.meaning,
                                      playable = MorseItem.Playable.Pattern(prosign.pattern))
            }
            return if (isMet(intro.id)) null else intro
        }

        /**
         * The pattern as an operator says it: every dit but the last is "di",
         * the last is "dit", a dah is always "dah" — so K is "dah-di-dah" and S
         * is "di-di-dit". Pinned for both ports in `fixtures/introduction.json`.
         */
        fun spokenPattern(pattern: String): String =
            pattern.mapIndexed { index, element ->
                when {
                    element == '-' -> "dah"
                    index == pattern.length - 1 -> "dit"
                    else -> "di"
                }
            }.joinToString("-")

        /**
         * The pattern spaced out for reading, with a real minus sign (U+2212)
         * so a dah does not render as a hyphen next to a dot.
         */
        fun symbolPattern(pattern: String): String =
            pattern.map { if (it == '-') "−" else "·" }.joinToString(" ")
    }
}
