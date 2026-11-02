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
 * The opt-in streak reminder: around 19:00 local time, if a daily streak is alive and today's cube isn't solved, one
 * notification. Nothing fires once the cube is done, when the streak is gone, or when the setting is off.
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val p = Prefs(ctx)
        val today = utcDay()
        val streak = p.liveStreak(today)
        if (!p.streakReminder || streak <= 0 || p.dailyDone == today) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Streak reminder", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(ctx, 0, Intent(Intent.ACTION_VIEW, Uri.parse("cubit://daily/$today"), ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(ctx))
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Your $streak-day streak ends tonight")
            .setContentText("Today's daily cube is waiting.")
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
                set(Calendar.HOUR_OF_DAY, 19); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
            }
            // Inexact on purpose: no exact-alarm permission, and a few minutes either way doesn't matter here.
            am.setInexactRepeating(AlarmManager.RTC, at.timeInMillis, AlarmManager.INTERVAL_DAY, pi)
        }
    }
}
