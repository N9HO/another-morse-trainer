package app.anothermorsetrainer.morsekit

// The CWOps "CW 77" list (#240): the most-used words and abbreviations in a
// CW contact, which CW Academy advisors have students listen to at speed. The
// maintainer-supplied file (77_all_current.txt) is the authoritative source;
// it has 75 entries with RR, CL, WX, BEAM and AR each twice, so the set is
// those entries with repeats dropped, first occurrence kept: 70. The "CW 77"
// name is kept as the list is known. Pinned for both ports by
// fixtures/cw77.json — change the list there and here together.
//
// Token spelling is fixtures/qso-elements.json's: the five prosigns in the
// list (AR, AS, BT, KN, SK) are bracketed as [MorseData.prosigns] spells them
// and sent run-together; everything else is text — BK and CL as the
// abbreviations table's letter forms, K as a letter (its pattern is <K>'s),
// HW? with its question mark. Meanings repeat the wording of the
// abbreviation, Q-code, prosign and QSO-element tables where the token lives
// there too.
//
// Translated from MorseKit/MorseDataCW77.swift. Swift attaches this to
// MorseData via an extension; Kotlin objects can't be split across files, so
// the table lives here as an internal top-level val and MorseData re-exposes
// it with the item builders.

/**
 * How the standalone CW 77 mode (the #240 follow-up) runs the list. [LISTEN]
 * is Listen & Learn's loop: hear the token, then see it and hear the
 * readback, hands-free and kept alive in the background. [QUIZ] is Common
 * Words' drill: hear the token and answer it by choices, typing or keying,
 * scored. Pinned by `styles` in fixtures/cw77.json; the Swift twin is
 * `CW77Style`. [id] is the fixture's (and the Swift raw value's) spelling.
 */
enum class Cw77Style(val id: String, val label: String) {
    LISTEN("listen", "Listen"),
    QUIZ("quiz", "Quiz");

    companion object {
        /** What the mode's setup sheet selects before a style has been chosen. */
        val DEFAULT: Cw77Style = LISTEN

        fun fromId(id: String?): Cw77Style? = entries.firstOrNull { it.id == id }
    }
}

/** The CW 77 list, de-duplicated, in the source file's order. */
internal val cw77Data: List<TokenMeaning> = listOf(
    TokenMeaning("VVV", "test signal — tuning up"),
    TokenMeaning("VERT", "vertical antenna"),
    TokenMeaning("OM", "old man"),
    TokenMeaning("HR", "here"),
    TokenMeaning("XYL", "wife (ex young lady)"),
    TokenMeaning("QTH", "my location is"),
    TokenMeaning("RR", "roger roger — all received"),
    TokenMeaning("AGE", "age"),
    TokenMeaning("FB", "fine business (great)"),
    TokenMeaning("LID", "poor operator"),
    TokenMeaning("QSL", "acknowledge / received"),
    TokenMeaning("TKS", "thanks"),
    TokenMeaning("RIG", "radio"),
    TokenMeaning("QSB", "your signals are fading"),
    TokenMeaning("CQ", "calling any station"),
    TokenMeaning("CL", "closing down"),
    TokenMeaning("<KN>", "go ahead — named station only"),
    TokenMeaning("NAME", "name"),
    TokenMeaning("PKT", "packet radio"),
    TokenMeaning("QSO", "a contact"),
    TokenMeaning("TEST", "contest — calling CQ contest"),
    TokenMeaning("DE", "this is / from"),
    TokenMeaning("<BT>", "separator / new section"),
    TokenMeaning("73", "best regards"),
    TokenMeaning("QRM", "man-made interference"),
    TokenMeaning("BK", "break"),
    TokenMeaning("AGN", "again"),
    TokenMeaning("DIPOLE", "dipole antenna"),
    TokenMeaning("<SK>", "end of contact"),
    TokenMeaning("HW?", "how do you copy?"),
    TokenMeaning("QRP", "low power"),
    TokenMeaning("TNX", "thanks"),
    TokenMeaning("YRS", "years"),
    TokenMeaning("YL", "young lady"),
    TokenMeaning("QRX", "wait / stand by"),
    TokenMeaning("QRL", "this frequency is busy / in use"),
    TokenMeaning("ES", "and"),
    TokenMeaning("WX", "weather"),
    TokenMeaning("QRT", "stop sending / going off air"),
    TokenMeaning("K", "go ahead — any station"),
    TokenMeaning("QRS", "send slower"),
    TokenMeaning("88", "love and kisses"),
    TokenMeaning("PWR", "power"),
    TokenMeaning("RUNS", "runs — as in rig runs 100 watts"),
    TokenMeaning("CALL", "call sign"),
    TokenMeaning("VY", "very"),
    TokenMeaning("WIRE", "wire antenna"),
    TokenMeaning("YAGI", "Yagi beam antenna"),
    TokenMeaning("NR", "number"),
    TokenMeaning("RPT", "repeat / report"),
    TokenMeaning("OP", "operator"),
    TokenMeaning("<AR>", "over — end of message"),
    TokenMeaning("PSE", "please"),
    TokenMeaning("EL", "elements — of a beam antenna"),
    TokenMeaning("LOOP", "loop antenna"),
    TokenMeaning("ABT", "about"),
    TokenMeaning("QSY", "change frequency"),
    TokenMeaning("TU", "thank you"),
    TokenMeaning("HI", "laughter"),
    TokenMeaning("BEAM", "beam antenna"),
    TokenMeaning("RST", "signal report"),
    TokenMeaning("WATT", "watt"),
    TokenMeaning("<AS>", "wait / stand by"),
    TokenMeaning("TEMP", "temperature"),
    TokenMeaning("CPY", "copy"),
    TokenMeaning("QRZ", "who is calling me?"),
    TokenMeaning("ANT", "antenna"),
    TokenMeaning("QRN", "atmospheric noise / static"),
    TokenMeaning("CW", "continuous wave — Morse code"),
    TokenMeaning("DX", "distance")
)
