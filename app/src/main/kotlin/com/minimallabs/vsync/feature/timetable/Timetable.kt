package com.minimallabs.vsync.feature.timetable

import com.minimallabs.vsync.core.App
import com.minimallabs.vsync.core.DemoMode
import com.minimallabs.vsync.data.AppJson
import com.minimallabs.vsync.data.Day
import com.minimallabs.vsync.data.Timetable
import java.time.LocalDate

// ------------------------------------------------------------------ pure helpers

/** "09:00" -> 540; null when unreadable. */
fun minutesOf(time: String?): Int? {
    if (time == null) return null
    val parts = time.trim().split(':')
    if (parts.size < 2) return null
    val h = parts[0].trim().toIntOrNull() ?: return null
    val m = parts[1].trim().toIntOrNull() ?: return null
    return h * 60 + m
}

fun formatTo12Hour(time: String?): String {
    if (time.isNullOrBlank()) return ""
    val parts = time.trim().split(':')
    if (parts.size != 2) return time
    val h = parts[0].toIntOrNull() ?: return time
    val m = parts[1].toIntOrNull() ?: return time
    val hour12 = if (h % 12 == 0) 12 else h % 12
    return "%d:%02d %s".format(hour12, m, if (h < 12) "AM" else "PM")
}

fun formatTimeRange(start: String?, end: String?): String {
    val s = formatTo12Hour(start)
    val e = formatTo12Hour(end)
    return when {
        s.isEmpty() -> e
        e.isEmpty() -> s
        else -> "$s - $e"
    }
}

fun isLabClass(c: Day): Boolean {
    if (c.courseType.lowercase().contains("lab")) return true
    if (c.venue.lowercase().contains("lab")) return true
    if (Regex("^L\\d").containsMatchIn(c.slot.trim().uppercase())) return true
    return (minutesOf(c.endTime) ?: 0) - (minutesOf(c.startTime) ?: 0) >= 90
}

class FreeGap(val minutes: Int, val startsAt: Int) {
    val label get() = "Free for ${formatFreeDuration(minutes)}"
}

fun formatFreeDuration(minutes: Int): String {
    if (minutes < 60) return "$minutes min"
    val h = minutes / 60
    val rest = minutes % 60
    return if (rest == 0) "$h hr" else "$h hr $rest min"
}

object FreeTime {
    const val CHANGEOVER = 10

    /** For each class, the free gap BEFORE it (longer than the changeover), or null. */
    fun before(classes: List<Day>): List<FreeGap?> {
        val gaps = ArrayList<FreeGap?>()
        var latestEnd: Int? = null
        for (c in classes) {
            val start = minutesOf(c.startTime)
            val end = minutesOf(c.endTime)
            var gap: FreeGap? = null
            if (latestEnd != null && start != null) {
                val minutes = start - latestEnd
                if (minutes > CHANGEOVER) gap = FreeGap(minutes, latestEnd)
            }
            gaps.add(gap)
            val reach = end ?: start
            if (reach != null && (latestEnd == null || reach > latestEnd)) latestEnd = reach
        }
        return gaps
    }
}

fun sortedClasses(t: Timetable, isoDay: Int): List<Day> = t.forWeekday(isoDay).sortedBy { minutesOf(it.startTime) ?: 0 }

object TimetableRepo {
    suspend fun refresh(): Timetable {
        val user = App.user
        if (DemoMode.isOn) return user?.timetable ?: Timetable()
        val sem = App.semesterId
        val fresh = AppJson.decodeFromString<Timetable>(App.vtop { it.text("timetable", sem) })
        App.user?.let { App.updateUser(it.copy(timetable = fresh)) }
        return fresh
    }
}

