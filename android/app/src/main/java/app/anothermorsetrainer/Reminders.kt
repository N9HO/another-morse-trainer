package app.anothermorsetrainer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import java.util.Calendar

/**
 * Schedules a daily "time to practice" reminder, mirroring the iOS app's
 * Reminders setting. Uses an inexact daily [AlarmManager] alarm (a nudge
 * doesn't need to be exact, so no exact-alarm permission is required) that
 * fires [ReminderReceiver].
 */
object Reminders {
    const val CHANNEL_ID = "practice_reminders"
    const val NOTIFICATION_ID = 1001
    const val ACTION_REMIND = "app.anothermorsetrainer.action.REMIND"
    private const val REQUEST_CODE = 100

    /** Create the notification channel (idempotent; safe to call repeatedly). */
    fun ensureChannel(context: Context) {
        val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
            .setName("Practice reminders")
            .setDescription("A daily nudge to keep your Morse streak alive.")
            .build()
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }

    /** Schedule (or reschedule) the daily reminder at the time in [Settings]. */
    fun schedule(context: Context) {
        ensureChannel(context)
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            nextTriggerMillis(Settings.reminderHour, Settings.reminderMinute),
            AlarmManager.INTERVAL_DAY,
            pendingIntent(context)
        )
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(context))
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).setAction(ACTION_REMIND)
        return PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Next occurrence of [hour]:[minute] — today if still ahead, else tomorrow. */
    private fun nextTriggerMillis(hour: Int, minute: Int): Long {
        val now = Calendar.getInstance()
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (!next.after(now)) next.add(Calendar.DAY_OF_MONTH, 1)
        return next.timeInMillis
    }
}
