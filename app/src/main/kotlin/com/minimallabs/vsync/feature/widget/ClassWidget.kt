package com.minimallabs.vsync.feature.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.minimallabs.vsync.MainActivity
import com.minimallabs.vsync.R
import com.minimallabs.vsync.core.App
import com.minimallabs.vsync.feature.timetable.formatTimeRange
import com.minimallabs.vsync.feature.timetable.minutesOf
import com.minimallabs.vsync.feature.timetable.sortedClasses
import java.time.LocalDate
import java.time.LocalTime

/**
 * "Next class" home-screen widget. Shows the class on now (in its first 20
 * minutes) or the next one today, from the saved timetable. Tapping opens the app.
 */
class ClassWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) manager.updateAppWidget(id, views(context))
    }

    companion object {
        fun refresh(context: Context) = runCatching {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, ClassWidget::class.java))
            for (id in ids) manager.updateAppWidget(id, views(context))
        }

        private fun views(context: Context): RemoteViews {
            val v = RemoteViews(context.packageName, R.layout.upcoming_class_widget)
            val timetable = runCatching { App.user?.timetable ?: App.store.loadUser()?.timetable }.getOrNull()
            val now = LocalTime.now().let { it.hour * 60 + it.minute }
            val classes = timetable?.let { sortedClasses(it, LocalDate.now().dayOfWeek.value) } ?: emptyList()
            val current = classes.firstOrNull { val s = minutesOf(it.startTime) ?: -1; now in s until s + 20 }
            val next = current ?: classes.firstOrNull { (minutesOf(it.startTime) ?: -1) > now }
            if (next != null) {
                v.setTextViewText(R.id.widget_title, if (current != null) "ON NOW" else "NEXT CLASS")
                v.setTextViewText(R.id.course_name, next.courseName)
                v.setTextViewText(R.id.timing, formatTimeRange(next.startTime, next.endTime))
                v.setTextViewText(R.id.venue, listOf(next.venue, next.courseCode).filter { it.isNotBlank() }.joinToString(" · "))
            } else {
                v.setTextViewText(R.id.widget_title, "NEXT CLASS")
                v.setTextViewText(R.id.course_name, if (timetable == null) "Sign in to VSync" else "No more classes today")
                v.setTextViewText(R.id.timing, "")
                v.setTextViewText(R.id.venue, "")
            }
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            v.setOnClickPendingIntent(R.id.widget_root, open)
            return v
        }
    }
}

