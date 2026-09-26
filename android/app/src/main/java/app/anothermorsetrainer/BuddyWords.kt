package app.anothermorsetrainer

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.anothermorsetrainer.morsekit.BuddyDigest
import app.anothermorsetrainer.morsekit.BuddyStatus

/**
 * The buddy lines' words (#237): the home line and the reminder sentence,
 * built from the list's [BuddyDigest] (`morsekit/Buddy.kt`, pinned by
 * `fixtures/buddy-list.json`) and the templates in strings.xml. The templates
 * are read once — by [current] at composition, or [from] a Context in
 * `ReminderReceiver`, which has no composition — so both surfaces assemble
 * the same sentence the same way. The iOS twin is `BuddyStatusCache.homeLine`
 * and `reminderSentence`.
 */
internal class BuddyWords(
    private val practised: String,
    private val notYet: String,
    private val allPractised: String,
    private val several: String,
    private val waitingOne: String,
    private val waitingTwo: String,
    private val waitingMany: String,
    private val streakDays: String,
    private val streakNone: String,
    private val streakBest: String
) {
    /** "12-day buddy streak", or "no buddy streak yet" at zero. */
    fun streak(days: Int): String = if (days > 0) streakDays.format(days) else streakNone

    /** "W1AW hasn't practised yet today", "W1AW and K1ABC haven't …", "W1AW, K1ABC and 2 more …"; null when nobody is waiting. */
    fun waiting(d: BuddyDigest): String? = when {
        d.waitingNames.isEmpty() -> null
        d.waitingNames.size == 1 -> waitingOne.format(d.waitingNames[0])
        d.waitingMore == 0 -> waitingTwo.format(d.waitingNames[0], d.waitingNames[1])
        else -> waitingMany.format(d.waitingNames[0], d.waitingNames[1], d.waitingMore)
    }

    /**
     * The home line under the streak badge, or null with no buddies. One
     * buddy: the #219 wording. Several: everyone practised, or who is still
     * waiting, then the best streak.
     */
    fun home(status: BuddyStatus, today: String): String? {
        val first = status.buddies.firstOrNull() ?: return null
        val d = status.digest(today)
        if (d.count == 1) {
            return (if (d.allPractised) practised else notYet).format(first.name, streak(first.streak))
        }
        val best = if (d.bestStreak > 0) streakBest.format(streak(d.bestStreak)) else streakNone
        return if (d.allPractised) allPractised.format(d.count, best) else several.format(waiting(d) ?: "", best)
    }

    companion object {
        @Composable
        fun current(): BuddyWords = BuddyWords(
            practised = stringResource(R.string.home_buddy_practised),
            notYet = stringResource(R.string.home_buddy_not_yet),
            allPractised = stringResource(R.string.home_buddies_all),
            several = stringResource(R.string.home_buddies_waiting),
            waitingOne = stringResource(R.string.buddy_waiting_one),
            waitingTwo = stringResource(R.string.buddy_waiting_two),
            waitingMany = stringResource(R.string.buddy_waiting_many),
            streakDays = stringResource(R.string.buddy_streak_days),
            streakNone = stringResource(R.string.buddy_streak_none),
            streakBest = stringResource(R.string.buddy_streak_best)
        )

        fun from(context: Context): BuddyWords = BuddyWords(
            practised = context.getString(R.string.home_buddy_practised),
            notYet = context.getString(R.string.home_buddy_not_yet),
            allPractised = context.getString(R.string.home_buddies_all),
            several = context.getString(R.string.home_buddies_waiting),
            waitingOne = context.getString(R.string.buddy_waiting_one),
            waitingTwo = context.getString(R.string.buddy_waiting_two),
            waitingMany = context.getString(R.string.buddy_waiting_many),
            streakDays = context.getString(R.string.buddy_streak_days),
            streakNone = context.getString(R.string.buddy_streak_none),
            streakBest = context.getString(R.string.buddy_streak_best)
        )
    }
}
