package app.anothermorsetrainer.morsekit

/**
 * Translated from MorseKit/Contest.swift (plus ContestLength from the iOS
 * AppSettings.swift — Android keeps contest choices in-screen, so the length
 * enum lives with the contest definitions here).
 */

/**
 * What a contest's multiplier counts — so the score and scoreboard can derive
 * it from the worked log without the engine knowing about contests.
 */
enum class MultiplierKind {
    NONE,       // no multiplier; score is a straight QSO/point total
    CALLS,      // each distinct call sign worked (CWT, MST)
    SPC         // each distinct state / province / country (NS)
}

/**
 * A practice emulation of a real on-air CW contest. Each maps onto the
 * pileup/QSO engine's exchange ([QSOContestMode]) but pins the authentic
 * on-air speed band, a contest-length clock, and a scoring rule — so a session
 * feels like the actual event rather than the generic QSO simulator.
 */
enum class ContestType(val code: String) {
    Sst("sst"),             // K1USN Slow Speed Test
    Mst("mst"),             // ICWC Medium Speed Test
    Cwt("cwt"),             // CWops CWT (Mini-CWT)
    NsSprint("nsSprint"),   // NCCC Sprint (NS)
    FieldDay("fieldDay");   // ARRL Field Day

    val id: String get() = code

    /** The exchange this contest runs on the pileup engine. */
    val qsoMode: QSOContestMode
        get() = when (this) {
            Sst -> QSOContestMode.Sst
            Mst -> QSOContestMode.Mst
            Cwt -> QSOContestMode.Cwt
            NsSprint -> QSOContestMode.Sprint
            FieldDay -> QSOContestMode.FieldDay
        }

    /** Short tag for tiles and the live scoreboard. */
    val shortName: String
        get() = when (this) {
            Sst -> "SST"
            Mst -> "MST"
            Cwt -> "CWT"
            NsSprint -> "NS"
            FieldDay -> "FD"
        }

    /** Full event name. */
    val eventName: String
        get() = when (this) {
            Sst -> "K1USN SST"
            Mst -> "ICWC MST"
            Cwt -> "CWops CWT"
            NsSprint -> "NCCC Sprint"
            FieldDay -> "ARRL Field Day"
        }

    val blurb: String
        get() = when (this) {
            Sst -> "K1USN Slow Speed Test — a friendly, deliberately slow sprint. Work as many stations as you can, copying each operator's name and state. Your score is simply the number of QSOs."
            Mst -> "ICWC Medium Speed Test — a step up from the SST. Copy each station's name and serial number at a medium pace. Your score is QSOs times the number of distinct call signs worked."
            Cwt -> "CWops mini-CWT — a fast hour. Copy each station's name and CWops member number (non-members send their state instead). Your score is QSOs times the number of distinct call signs worked."
            NsSprint -> "NCCC Sprint (NS) — a fast half-hour Thursday-night practice sprint. Copy a serial number, the operator's name, and their state/province/country. Your score is QSOs times the distinct SPCs worked."
            FieldDay -> "ARRL Field Day — the big summer emergency-ops exercise. Copy each station's class and ARRL section (e.g. 2A OH). Every CW QSO is worth 2 points."
        }

    /**
     * Authentic on-air speed band, in WPM. SST is held deliberately slow, MST
     * runs at a medium pace, CWT at a brisk contest pace, and NS faster still;
     * Field Day spans a wide range of operators.
     */
    val minWPM: Double
        get() = when (this) {
            Sst -> 15.0
            Mst -> 20.0
            Cwt -> 25.0
            NsSprint -> 28.0
            FieldDay -> 18.0
        }
    val maxWPM: Double
        get() = when (this) {
            Sst -> 20.0
            Mst -> 25.0
            Cwt -> 32.0
            NsSprint -> 38.0
            FieldDay -> 32.0
        }

    /** Points awarded per QSO — Field Day pays 2 for a CW contact; the rest pay 1. */
    val pointsPerQSO: Int
        get() = when (this) {
            FieldDay -> 2
            else -> 1
        }

    /** What this contest's multiplier counts (if any). */
    val multiplierKind: MultiplierKind
        get() = when (this) {
            Sst, FieldDay -> MultiplierKind.NONE
            Mst, Cwt -> MultiplierKind.CALLS
            NsSprint -> MultiplierKind.SPC
        }

    /** Whether a multiplier applies to the score. */
    val usesMultipliers: Boolean get() = multiplierKind != MultiplierKind.NONE

    /**
     * What a multiplier *is*, for the scoreboard label — null when the contest
     * has no multipliers.
     */
    val multiplierLabel: String?
        get() = when (multiplierKind) {
            MultiplierKind.NONE -> null
            MultiplierKind.CALLS -> "Calls"
            MultiplierKind.SPC -> "SPC"
        }

    /**
     * Final score from a worked log: QSO points ([pointsPerQSO] each) times the
     * multiplier count, where the contest has multipliers.
     */
    fun score(qsoCount: Int, multipliers: Int): Int {
        val points = qsoCount * pointsPerQSO
        return if (usesMultipliers) points * multipliers else points
    }

    /**
     * Pull this contest's multiplier count out of a worked log — distinct call
     * signs, distinct SPCs (the last exchange token), or none.
     */
    fun multiplierCount(calls: List<String>, exchanges: List<String>): Int =
        when (multiplierKind) {
            MultiplierKind.NONE -> 0
            MultiplierKind.CALLS -> calls.toSet().size
            MultiplierKind.SPC -> exchanges
                .mapNotNull { it.split(" ").lastOrNull()?.takeIf { t -> t.isNotEmpty() } }
                .toSet().size
        }

    companion object {
        val allCases: List<ContestType> = entries.toList()
    }
}

/** How long a practice contest run lasts. */
enum class ContestLength(val code: String) {
    TenMin("tenMin"),
    ThirtyMin("thirtyMin"),
    FullHour("fullHour"),
    UntilStop("untilStop");

    val id: String get() = code

    /** Run length in seconds, or null for an open-ended run. */
    val seconds: Int?
        get() = when (this) {
            TenMin -> 600
            ThirtyMin -> 1800
            FullHour -> 3600
            UntilStop -> null
        }

    val label: String
        get() = when (this) {
            TenMin -> "10-minute sprint"
            ThirtyMin -> "30 minutes"
            FullHour -> "Full hour"
            UntilStop -> "Until I stop"
        }

    companion object {
        val allCases: List<ContestLength> = entries.toList()
    }
}
