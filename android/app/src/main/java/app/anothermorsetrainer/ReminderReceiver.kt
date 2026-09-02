package app.anothermorsetrainer

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Fires the daily practice reminder, and re-arms the alarm after a reboot
 * (alarms don't survive a restart). Registered in the manifest so it runs even
 * when the app process isn't alive.
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Settings.init(context)

        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            if (Settings.remindersEnabled) Reminders.schedule(context)
            return
        }

        // Respect the runtime notification permission on Android 13+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        Reminders.ensureChannel(context)
        Stats.init(context)
        val streak = Stats.currentStreak
        val text = if (streak > 0) {
            context.getString(R.string.reminder_body_streak, streak)
        } else {
            context.getString(R.string.reminder_body_default)
        }

        val tapIntent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val contentIntent = PendingIntent.getActivity(
            context, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, Reminders.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_morse)
            .setContentTitle(context.getString(R.string.reminder_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(context).notify(Reminders.NOTIFICATION_ID, notification)
    }
}
