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
            "Keep your $streak-day streak alive — a few minutes of CW is all it takes."
        } else {
            "A few minutes of code practice keeps your ear sharp. Tap to start."
        }

        val tapIntent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val contentIntent = PendingIntent.getActivity(
            context, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, Reminders.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_morse)
            .setContentTitle("Time to practice Morse")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(context).notify(Reminders.NOTIFICATION_ID, notification)
    }
}
