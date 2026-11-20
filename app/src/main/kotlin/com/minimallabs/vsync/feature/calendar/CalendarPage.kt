package com.minimallabs.vsync.feature.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.minimallabs.vsync.core.App
import com.minimallabs.vsync.ui.AppCard
import com.minimallabs.vsync.ui.CenterLoader
import com.minimallabs.vsync.ui.DayFormat
import com.minimallabs.vsync.ui.EmptyState
import com.minimallabs.vsync.ui.LocalStatus
import com.minimallabs.vsync.ui.Muted
import com.minimallabs.vsync.ui.Page
import com.minimallabs.vsync.ui.SettingRow
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
fun kindColor(kind: CalendarDayKind): Color {
    val s = LocalStatus.current
    return when (kind) {
        CalendarDayKind.Instructional, CalendarDayKind.Holiday -> s.success
        CalendarDayKind.NoClasses -> s.neutral
        CalendarDayKind.Exam -> s.warning
        CalendarDayKind.Countdown -> Color(0xFF9A7BFF)
        CalendarDayKind.Event -> Color(0xFF7E57C2)
        CalendarDayKind.Outing -> Color(0xFF00A0AD)
    }
}

private fun kindName(kind: CalendarDayKind) = when (kind) {
    CalendarDayKind.Instructional -> "Instructional"
    CalendarDayKind.Holiday -> "Holiday"
    CalendarDayKind.NoClasses -> "No classes"
    CalendarDayKind.Exam -> "Exam"
    CalendarDayKind.Countdown -> "Countdown"
    CalendarDayKind.Event -> "Event"
    CalendarDayKind.Outing -> "Outing"
}

@Composable
fun CalendarPage() {
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { runCatching { CalendarRepo.ensureLoaded() } }
    val months = CalendarRepo.decoratedNow()
    val today = remember { LocalDate.now() }
    var index by remember(months.size) {
        // Open on this month when the calendar has it.
        mutableIntStateOf(months.indexOfFirst { it.year == today.year && it.month == today.monthValue }.coerceAtLeast(0))
    }
    var selected by remember { mutableStateOf<LocalDate?>(null) }

    Page("Calendar", actions = {
        if (CalendarRepo.loading) CircularProgressIndicator(Modifier.padding(12.dp).size(20.dp), strokeWidth = 2.dp)
        else IconButton(onClick = { scope.launch { runCatching { CalendarRepo.refresh() } } }) { Icon(Icons.Filled.Refresh, "Refresh") }
    }) {
        if (months.isEmpty()) {
            when {
                CalendarRepo.loading -> CenterLoader()
                CalendarRepo.error != null -> EmptyState("Couldn't load the calendar", CalendarRepo.error, "Retry") { scope.launch { runCatching { CalendarRepo.refresh() } } }
                else -> EmptyState("No calendar yet", "Fetch the academic calendar from VTOP.", "Load") { scope.launch { runCatching { CalendarRepo.refresh() } } }
            }
            return@Page
        }
        val month = months[index.coerceIn(0, months.lastIndex)]
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (index > 0) { index--; selected = null } }, enabled = index > 0) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous month") }
            Text(month.label, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { if (index < months.lastIndex) { index++; selected = null } }, enabled = index < months.lastIndex) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next month") }
        }
        AppCard {
            Row(Modifier.fillMaxWidth()) {
                for (l in listOf("M", "T", "W", "T", "F", "S", "S")) Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Muted(l) }
            }
            val first = month.firstDay
            val lead = first.dayOfWeek.value - 1
            val length = first.lengthOfMonth()
            val cells = lead + length
            for (row in 0 until (cells + 6) / 7) {
                Row(Modifier.fillMaxWidth()) {
                    for (col in 0..6) {
                        val dayNum = row * 7 + col - lead + 1
                        Box(Modifier.weight(1f).aspectRatio(1f).padding(3.dp), contentAlignment = Alignment.Center) {
                            if (dayNum in 1..length) {
                                val date = first.withDayOfMonth(dayNum)
                                val ring = CalendarEventClassifier.ringKindFor(month.chipsOn(date))
                                val isSel = date == selected
                                Box(
                                    Modifier.fillMaxWidth().aspectRatio(1f).clip(CircleShape)
                                        .background(if (isSel) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent)
                                        .let { if (ring != null) it.border(1.5.dp, kindColor(ring), CircleShape) else it }
                                        .clickable { selected = if (isSel) null else date },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        "$dayNum", style = MaterialTheme.typography.bodyMedium,
                                        color = if (date == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            // The legend names only the colours used this month.
            val kinds = month.chips.filter { !CalendarEventClassifier.isOrdinarySunday(it) && CalendarEventClassifier.isMarked(it.kind) }.map { it.kind }.distinct()
            if (kinds.isNotEmpty()) {
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (k in kinds) Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(kindColor(k)))
                        Spacer(Modifier.width(4.dp)); Muted(kindName(k))
                    }
                }
            }
        }
        AppCard {
            SettingRow("Show countdowns", "Your countdowns and outings on the calendar", checked = !App.prefs.hideCalendarCountdowns,
                onCheckedChange = { on -> App.updatePrefs { it.copy(hideCalendarCountdowns = !on) } })
        }
        val list = (selected?.let { month.chipsOn(it) } ?: month.chips)
            .filter { !CalendarEventClassifier.isOrdinarySunday(it) && (selected != null || CalendarEventClassifier.isMarked(it.kind)) }
        if (list.isEmpty()) Muted(if (selected != null) "Nothing marked on ${selected!!.format(DayFormat)}." else "Nothing marked this month.")
        for (chip in list) AppCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(kindColor(chip.kind)))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(chip.title, style = MaterialTheme.typography.titleSmall)
                    val sub = listOfNotNull(chip.subtitle, chip.detail?.takeIf { it.isNotBlank() && !it.equals(chip.title, true) }).joinToString(" · ")
                    if (sub.isNotBlank()) Muted(sub)
                }
                Muted(chip.date.format(DayFormat))
            }
        }
    }
}
