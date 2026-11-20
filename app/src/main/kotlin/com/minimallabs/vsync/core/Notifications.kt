package com.minimallabs.vsync.core

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.minimallabs.vsync.R

/**
 * Only two kinds of notification exist (as in the Flutter app):
 * a finished download, and a countdown reminder the student switched on.
 * Channels are created lazily, so nothing is registered until first use.
 */
object Notifications {
    private const val DOWNLOADS = "file_downloads"
    private const val COUNTDOWNS = "countdown_reminders"

    fun allowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun channel(context: Context, id: String, name: String, description: String) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(id) == null) {
            nm.createNotificationChannel(NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH).apply { this.description = description })
        }
    }

    /** "<label> Ready" with a tap that opens the saved file. */
    fun downloadComplete(label: String, fileName: String, uri: Uri?, mime: String?) {
        val context = App.context
        if (!allowed(context)) return
        channel(context, DOWNLOADS, "File Downloads", "Notifications for file download completion")
        val open = uri?.let {
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(it, mime ?: "*/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            PendingIntent.getActivity(context, fileName.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val n = NotificationCompat.Builder(context, DOWNLOADS)
            .setSmallIcon(R.drawable.ic_stat_vsync)
            .setContentTitle("$label Ready")
            .setContentText("$fileName downloaded successfully. Tap to open.")
            .setAutoCancel(true)
            .apply { if (open != null) setContentIntent(open) }
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(fileName.hashCode(), n) }
    }

    // ---- countdown reminders ----

    private fun reminderIntent(context: Context, id: String, title: String?, body: String?): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = "com.minimallabs.vsync.COUNTDOWN"
            data = Uri.parse("vsync://countdown/$id")
            putExtra("id", id); putExtra("title", title); putExtra("body", body)
        }
        return PendingIntent.getBroadcast(context, "milestone_$id".hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun formatLeadTime(minutes: Int): String {
        val days = minutes / 1440
        val hours = (minutes % 1440) / 60
        val mins = minutes % 60
        val parts = ArrayList<String>()
        if (days > 0) parts.add("$days day${if (days == 1) "" else "s"}")
        if (hours > 0) parts.add("$hours hour${if (hours == 1) "" else "s"}")
        if (mins > 0) parts.add("$mins minute${if (mins == 1) "" else "s"}")
        return if (parts.isEmpty()) "0 minutes" else parts.joinToString(" ")
    }

    fun scheduleReminder(id: String, title: String, targetMs: Long, minutesBefore: Int) {
        val context = App.context
        val at = targetMs - minutesBefore * 60_000L
        if (at <= System.currentTimeMillis()) return
        val pi = reminderIntent(context, id, title, "Your task is due in ${formatLeadTime(minutesBefore)}")
        val am = context.getSystemService(AlarmManager::class.java)
        runCatching {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        }
    }

    fun cancelReminder(id: String) {
        val context = App.context
        context.getSystemService(AlarmManager::class.java).cancel(reminderIntent(context, id, null, null))
        runCatching { NotificationManagerCompat.from(context).cancel("milestone_$id".hashCode()) }
    }

    fun cancelAll() { runCatching { NotificationManagerCompat.from(App.context).cancelAll() } }

    internal fun showReminder(context: Context, id: String, title: String, body: String) {
        if (!allowed(context)) return
        channel(context, COUNTDOWNS, "Countdown Reminders", "Reminders before your countdowns hit zero")
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val n = NotificationCompat.Builder(context, COUNTDOWNS)
            .setSmallIcon(R.drawable.ic_stat_vsync)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .apply {
                if (launch != null) setContentIntent(PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE))
            }
            .build()
        runCatching { NotificationManagerCompat.from(context).notify("milestone_$id".hashCode(), n) }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Notifications.showReminder(
            context,
            intent.getStringExtra("id") ?: return,
            intent.getStringExtra("title") ?: "Countdown",
            intent.getStringExtra("body") ?: "",
        )
    }
}

/** Alarms do not survive a reboot: put the countdown reminders back. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runCatching { com.minimallabs.vsync.feature.home.Milestones.syncReminders() }
    }
}

const val PRIVACY_POLICY_URL = "https://v-sync-minimallabs.vercel.app/privacypolicy"
