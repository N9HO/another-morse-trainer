package app.anothermorsetrainer.morsekit

/**
 * Which keyboard key answers which multiple-choice option (issue #69), and the
 * rule that keeps a digit you *heard* from being read as a position (issue #30).
 *
 * The original scheme had two modes: when every option was a single character,
 * each option was answered by its own key; otherwise the options were numbered
 * 1–9 by position. The mode was chosen for the drill as a whole, so a single
 * multi-character distractor — a prosign, an abbreviation, anything from a later
 * Journey level's cumulative pool — flipped the *entire* grid to positional
 * numbering. In a drill that sent numbers that was silently wrong: you heard
 * `4`, pressed `4`, and answered whatever happened to sit fourth in the list.
 * With the options in order that reads as a consistent off-by-one.
 *
 * So the mode is now per option, not per drill, and value always beats
 * position: an option that *is* a single character is answered by that
 * character, and only the options that have no character of their own fall back
 * to a digit. Digits already spoken for by a single-character option are never
 * handed out as positions, so no two options share a key.
 *
 * Twin of MorseKit/AnswerKeys.swift — keep the two in step.
 */
object AnswerKeys {

    /** Digits offered as positional answers, in the order they're handed out. */
    private val positionDigits: List<Char> = ('1'..'9').toList()

    /**
     * The key that answers each option, aligned with [options]. `null` means the
     * option has no key — there were more options than free digits.
     *
     * Keys are lowercase; match against a pressed key with [optionFor].
     */
    fun assign(options: List<String>): List<Char?> {
        val keys = arrayOfNulls<Char>(options.size)
        val taken = mutableSetOf<Char>()

        // Pass 1: an option that is one character answers to its own key.
        for ((index, option) in options.withIndex()) {
            if (option.length != 1) continue
            val key = option[0].lowercaseChar()
            if (taken.add(key)) keys[index] = key
        }

        // Pass 2: everything else takes the next digit nobody has claimed.
        val free = ArrayDeque(positionDigits.filter { it !in taken })
        for (index in options.indices) {
            if (keys[index] != null) continue
            keys[index] = free.removeFirstOrNull() ?: break
        }
        return keys.toList()
    }

    /**
     * The index of the option answered by [key], or `null` when the key answers
     * nothing in this drill. Case-insensitive.
     */
    fun optionFor(key: Char, options: List<String>): Int? =
        assign(options).indexOf(key.lowercaseChar()).takeIf { it >= 0 }

    /**
     * True when `options[index]` is answered by a positional digit rather than
     * by its own text — the only case where the 1–9 hint is worth showing,
     * since a single-character option already *is* its own key.
     */
    fun needsPositionHint(options: List<String>, index: Int): Boolean =
        index in options.indices && options[index].length != 1
}
