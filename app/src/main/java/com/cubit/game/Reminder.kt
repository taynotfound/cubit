package com.cubit.game

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import java.util.Calendar

/**
 * The opt-in daily reminder: at the hour the player picked (19:00 unless changed), one notification if today's cube
 * isn't solved yet. It talks about the streak when there is one, and the advent door in December. Nothing fires once
 * the cube is done or when the setting is off.
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val p = Prefs(ctx)
        // Alarms don't survive a reboot; set it again or the reminder stays silent until the app is next opened.
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) { schedule(ctx, p.streakReminder); return }
        val today = utcDay()
        val streak = p.liveStreak(today)
        if (!p.streakReminder || p.dailyDone == today) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Daily reminder", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(ctx, 0, Intent(Intent.ACTION_VIEW, Uri.parse("cubit://daily/$today"), ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(ctx))
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(reminderTitle(streak, p.reminderHour))
            .setContentText(reminderText(today, Advent.season(today)?.let { (y, open) -> open.takeIf { it !in p.adventDoors(y) } }))
            .setContentIntent(open).setAutoCancel(true)
            .build()
        runCatching { nm.notify(1, n) } // denied notification permission: stay quiet
    }

    companion object {
        private const val CHANNEL = "streak"

        /** Sets or clears the daily alarm to match the setting. Called on app start and when the switch changes. */
        fun schedule(ctx: Context, on: Boolean) {
            val am = ctx.getSystemService(AlarmManager::class.java) ?: return
            val pi = PendingIntent.getBroadcast(ctx, 0, Intent(ctx, ReminderReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            am.cancel(pi)
            if (!on) return
            val at = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, Prefs(ctx).reminderHour); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
            }
            // Inexact on purpose: no exact-alarm permission, and a few minutes either way doesn't matter here.
            am.setInexactRepeating(AlarmManager.RTC, at.timeInMillis, AlarmManager.INTERVAL_DAY, pi)
        }
    }
}

/** The reminder's title: the streak when there is one ("ends tonight" from 5 pm on), otherwise the cube. */
fun reminderTitle(streak: Int, hour: Int) = when {
    streak > 0 && hour >= 17 -> "Your $streak-day streak ends tonight"
    streak > 0 -> "Keep your $streak-day streak going"
    else -> "Today's daily cube is ready"
}

/** The line under it: [adventDoor] is today's unsolved advent door, if there is one. */
fun reminderText(@Suppress("UNUSED_PARAMETER") today: Int, adventDoor: Int?) =
    if (adventDoor != null) "Advent door $adventDoor is open too." else "One cube, the same for everyone. A minute or two."

/** The hours the reminder can be set to. */
val REMINDER_HOURS = intArrayOf(8, 12, 17, 19, 21)
