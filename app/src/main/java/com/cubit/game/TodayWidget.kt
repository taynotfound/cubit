package com.cubit.game

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews

/**
 * The home-screen widget: the daily streak and whether today's cube is done (and in December, the advent door).
 * Tapping it opens today's daily cube. It refreshes every 30 minutes (so it notices midnight) and whenever the app
 * finishes a level.
 */
class TodayWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val v = views(ctx)
        ids.forEach { mgr.updateAppWidget(it, v) }
    }

    companion object {
        /** Redraws every placed widget; cheap, and does nothing when there are none. */
        fun refresh(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx) ?: return
            val ids = runCatching { mgr.getAppWidgetIds(ComponentName(ctx, TodayWidget::class.java)) }.getOrNull() ?: return
            if (ids.isNotEmpty()) { val v = views(ctx); ids.forEach { mgr.updateAppWidget(it, v) } }
        }

        private fun views(ctx: Context): RemoteViews {
            val p = Prefs(ctx)
            val today = utcDay()
            val (title, text) = widgetLines(p.liveStreak(today), p.dailyDone == today,
                Advent.season(today)?.let { (y, open) -> open.takeIf { it !in p.adventDoors(y) } })
            val streak = p.liveStreak(today)
            return RemoteViews(ctx.packageName, R.layout.widget_today).apply {
                setTextViewText(R.id.widget_streak, if (streak > 0) "🔥$streak" else "🟪")
                setTextViewText(R.id.widget_streak_label, if (streak > 0) "day streak" else "Cubit")
                setTextViewText(R.id.widget_title, title)
                setTextViewText(R.id.widget_text, text)
                setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(ctx, 2,
                    Intent(Intent.ACTION_VIEW, Uri.parse("cubit://daily/$today"), ctx, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
        }
    }
}

/** The widget's two lines: what to do today, and a nudge (the advent door in December). */
fun widgetLines(streak: Int, doneToday: Boolean, adventDoor: Int?): Pair<String, String> = when {
    doneToday -> "Daily done ✓" to (if (adventDoor != null) "Advent door $adventDoor is still open." else "See you tomorrow for the next cube.")
    streak > 0 -> "Daily cube waiting" to "Solve it to make it ${streak + 1} days." + if (adventDoor != null) " Door $adventDoor too." else ""
    else -> "Today's daily cube" to (if (adventDoor != null) "And advent door $adventDoor. Tap to play." else "Same cube for everyone. Tap to play.")
}
