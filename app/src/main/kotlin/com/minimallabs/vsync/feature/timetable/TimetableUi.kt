package com.minimallabs.vsync.feature.timetable

import com.minimallabs.vsync.ui.Lucide
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Coffee
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.minimallabs.vsync.core.App
import com.minimallabs.vsync.data.Day
import com.minimallabs.vsync.data.Timetable
import com.minimallabs.vsync.ui.BarButton
import com.minimallabs.vsync.ui.BarSpinner
import com.minimallabs.vsync.ui.Detail
import com.minimallabs.vsync.ui.EmptyState
import com.minimallabs.vsync.ui.ErrorLine
import com.minimallabs.vsync.ui.Hairline
import com.minimallabs.vsync.ui.KeyValue
import com.minimallabs.vsync.ui.LocalTones
import com.minimallabs.vsync.ui.Page
import com.minimallabs.vsync.ui.Pill
import com.minimallabs.vsync.ui.rememberLoader
import java.time.LocalDate
import java.time.LocalTime

private val DAY_NAMES = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

@Composable
fun TimetableTab() {
    val timetable = App.user?.timetable
    val loader = rememberLoader<Timetable>()
    val todayIso = LocalDate.now().dayOfWeek.value
    // Sunday-first like the Flutter strip; only days that have classes.
    val order = listOf(7, 1, 2, 3, 4, 5, 6)
    val active = timetable?.let { t -> order.filter { t.forWeekday(it).isNotEmpty() } } ?: emptyList()
    val todayPos = order.indexOf(todayIso)
    var selected by rememberSaveable(active.size) {
        // Today if it has classes, otherwise the next day that does.
        mutableIntStateOf(if (todayIso in active) todayIso else active.firstOrNull { order.indexOf(it) > todayPos } ?: active.firstOrNull() ?: todayIso)
    }
    val classes = timetable?.let { sortedClasses(it, selected) } ?: emptyList()
    val labs = classes.count { isLabClass(it) }
    val theory = classes.size - labs
    val parts = buildList {
        if (labs > 0) add("$labs lab${if (labs == 1) "" else "s"}")
        if (theory > 0) add("$theory theory class${if (theory == 1) "" else "es"}")
    }
    val summary = if (parts.isEmpty()) "No classes" else "You have ${parts.joinToString(", ")} ${if (selected == todayIso) "today" else "on ${DAY_NAMES[selected - 1]}"}"

    Page("", back = false) {
        loader.error?.let { ErrorLine(it) }
        if (timetable == null || active.isEmpty()) {
            EmptyState("No classes this week", "Pull a fresh timetable from VTOP.", "Refresh") { loader.run { TimetableRepo.refresh() } }
            return@Page
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
            for (d in active) {
                val on = d == selected
                Box(
                    Modifier.size(46.dp).clip(CircleShape)
                        .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent)
                        .clickable { selected = d },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(DAY_NAMES[d - 1].take(1), style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp),
                        color = if (on) MaterialTheme.colorScheme.onPrimary else if (d == todayIso) MaterialTheme.colorScheme.onSurface else LocalTones.current.secondaryLabel)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = LocalTones.current.secondaryLabel, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            if (loader.loading) BarSpinner() else BarButton(Icons.Rounded.Refresh, "Refresh") { loader.run { TimetableRepo.refresh() } }
        }
        Schedule(classes, isToday = selected == todayIso)
    }
}

@Composable
private fun Schedule(classes: List<Day>, isToday: Boolean) {
    // Anything starting before 1:00 PM is morning; lunch sits between the two halves.
    val morning = classes.filter { (minutesOf(it.startTime) ?: 0) < 780 }
    val afternoon = classes.filter { (minutesOf(it.startTime) ?: 0) >= 780 }
    var open by remember(classes) { mutableStateOf<Day?>(null) }
    val now = LocalTime.now().let { it.hour * 60 + it.minute }
    for ((title, part) in listOf("Morning" to morning, "Afternoon" to afternoon)) {
        if (part.isEmpty()) continue
        if (title == "Afternoon" && morning.isNotEmpty()) Hairline(Modifier.padding(vertical = 8.dp))
        Text(title.uppercase(), style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.6.sp), color = LocalTones.current.secondaryLabel,
            modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 4.dp))
        // Free time is measured inside a stretch; lunch is not repeated as free time.
        val gaps = FreeTime.before(part)
        part.forEachIndexed { i, c ->
            if (i > 0) {
                val gap = gaps[i]
                if (gap != null) Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Lucide.Coffee, null, Modifier.size(15.dp), tint = LocalTones.current.secondaryLabel)
                    Spacer(Modifier.width(8.dp))
                    Detail(gap.label)
                } else Hairline(Modifier.padding(horizontal = 16.dp))
            }
            val s = minutesOf(c.startTime) ?: 0
            val e = minutesOf(c.endTime) ?: 0
            ClassRow(c, open == c, isToday && now in s until e) { open = if (open == c) null else c }
        }
    }
}

@Composable
private fun ClassRow(c: Day, expanded: Boolean, live: Boolean, onTap: () -> Unit) {
    val lab = isLabClass(c)
    Column(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large)
            .background(if (expanded || live) MaterialTheme.colorScheme.surfaceContainerLow else Color.Transparent)
            .clickable(onClick = onTap).padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(c.courseName.ifBlank { "N/A" }, style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            if (live) Pill("NOW", color = MaterialTheme.colorScheme.onPrimary, container = MaterialTheme.colorScheme.primary)
            else Pill(if (lab) "LAB" else "THEORY")
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Lucide.Clock, null, Modifier.size(16.dp), tint = LocalTones.current.secondaryLabel)
            Spacer(Modifier.width(8.dp))
            Text(listOf(formatTimeRange(c.startTime, c.endTime), c.venue).filter { it.isNotBlank() }.joinToString("  ·  "),
                style = MaterialTheme.typography.bodyMedium, color = LocalTones.current.secondaryLabel)
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(top = 12.dp)) {
                Hairline(Modifier.padding(bottom = 8.dp))
                KeyValue("Faculty", c.faculty)
                KeyValue("Course code", c.courseCode)
                KeyValue("Timing", formatTimeRange(c.startTime, c.endTime))
                KeyValue("Slot", c.slot)
                KeyValue("Venue", c.venue)
            }
        }
    }
}
