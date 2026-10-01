package app.anothermorsetrainer.morsekit

/**
 * Static data for the QSO/contest simulator: ARRL sections, common operator
 * names heard on CW, and Field Day class categories.
 *
 * Translated from MorseKit/ContestData.swift (Swift `enum`-namespace → `object`).
 */
object ContestData {

    /** US ARRL/RAC section abbreviations sent as contest exchanges. */
    val arrlSections: List<String> = listOf(
        // New England
        "CT", "EMA", "ME", "NH", "RI", "VT", "WMA",
        // Hudson / Atlantic
        "ENY", "NLI", "NNJ", "NNY", "SNJ", "WNY", "DE", "EPA", "MDC", "WPA",
        // Central / Great Lakes
        "IL", "IN", "WI", "KY", "MI", "OH",
        // Dakota / Midwest
        "MN", "ND", "SD", "IA", "KS", "MO", "NE", "AR", "LA", "MS", "TN",
        // Roanoke / Southeastern
        "NC", "SC", "VA", "WV", "AL", "GA", "NFL", "SFL", "WCF", "PR", "VI",
        // Delta / Texas
        "NTX", "STX", "WTX", "OK",
        // Rocky / Pacific
        "CO", "NM", "UT", "WY", "AZ", "ID", "MT", "NV", "OR", "EWA", "WWA", "AK",
        // Southwestern / Pacific
        "EB", "LAX", "ORG", "SB", "SCV", "SDG", "SF", "SJV", "SV", "PAC"
    )

    /** Short first names commonly heard in CW QSOs and contest exchanges. */
    val names: List<String> = listOf(
        "BOB", "JIM", "TOM", "DAN", "AL", "JOHN", "MIKE", "DAVE", "BILL", "JOE",
        "STEVE", "RICK", "KEN", "GARY", "PAUL", "MARK", "TIM", "RON", "ED", "DON",
        "BUD", "HANK", "WALT", "RAY", "FRED", "CARL", "GLEN", "PHIL", "ART", "LOU",
        "SAM", "PETE", "CHET", "ELMER", "DOC", "JACK", "ROY", "LEO", "NED", "GUS",
        "PAT", "SUE", "JAN", "KAY", "ANN", "LIZ", "MEG", "JO", "DEB", "PAM"
    )

    /** ARRL Field Day class categories (the letter after the transmitter count). */
    val fieldDayCategories: List<Char> = listOf('A', 'B', 'C', 'D', 'E', 'F')
}
