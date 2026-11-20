package com.minimallabs.vsync.feature.skip

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.minimallabs.vsync.core.App
import com.minimallabs.vsync.feature.calendar.AttendanceProjection
import com.minimallabs.vsync.feature.calendar.CalendarRepo
import com.minimallabs.vsync.feature.calendar.TermEnd
import com.minimallabs.vsync.feature.timetable.TimetableRepo
import com.minimallabs.vsync.ui.AppCard
import com.minimallabs.vsync.ui.DayFormat
import com.minimallabs.vsync.ui.EmptyState
import com.minimallabs.vsync.ui.LocalStatus
import com.minimallabs.vsync.ui.MomentField
import com.minimallabs.vsync.ui.Muted
import com.minimallabs.vsync.ui.Page
import com.minimallabs.vsync.ui.Pill
import com.minimallabs.vsync.ui.SectionTitle
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun SkipPlannerPage() {
    val scope = rememberCoroutineScope()
    var leaveAt by remember { mutableStateOf<LocalDateTime?>(null) }
    var returnAt by remember { mutableStateOf<LocalDateTime?>(null) }
    var note by remember { mutableStateOf<String?>(null) }

    // Opens from the SAVED timetable; only fetches when none is stored
    // (a fetch can mean a login and an OTP email).
    LaunchedEffect(Unit) {
        if (App.user?.timetable?.isEmpty != false) runCatching { TimetableRepo.refresh() }
        runCatching { CalendarRepo.ensureLoaded() }
    }

    val months = CalendarRepo.months
    val now = remember(leaveAt, returnAt) { LocalDateTime.now() }
    val endOfTerm = TermEnd.of(months, now.toLocalDate())
    val plan = if (leaveAt != null && returnAt != null) SkipPlanner.compute(
        timetable = App.user?.timetable,
        attendances = App.user?.attendance ?: emptyList(),
        leaveAt = leaveAt!!, returnAt = returnAt!!, now = now,
        until = endOfTerm?.date,
        closedDays = AttendanceProjection.closedDays(months),
    ) else null

    Page("Skip planner", actions = {
        if (leaveAt != null) TextButton(onClick = { leaveAt = null; returnAt = null; note = null }) { Text("Reset") }
    }) {
        Muted("Pick when you leave and when you are back. A class counts as missed if it starts between the two times, and the academic calendar takes out holidays.")
        AppCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MomentField("Leaving", leaveAt, "Leaving at") { picked ->
                    leaveAt = picked
                    val back = returnAt
                    // Keep "back" after "leaving": default to the next morning.
                    if (back == null || !back.isAfter(picked)) returnAt = picked.toLocalDate().plusDays(1).atStartOfDay()
                }
                MomentField("Back on", returnAt, "Back at") { picked ->
                    val leave = leaveAt
                    if (leave != null && !picked.isAfter(leave)) note = "Pick a time after you leave."
                    else { returnAt = picked; note = null }
                }
                note?.let { Text(it, color = LocalStatus.current.danger, style = MaterialTheme.typography.bodySmall) }
            }
        }
        if (months.isEmpty()) {
            AppCard {
                Muted(
                    if (CalendarRepo.loading) "Loading the academic calendar"
                    else "The academic calendar is not loaded, so holidays are not taken out yet and the plan may count too many classes."
                )
                if (!CalendarRepo.loading) TextButton(onClick = { scope.launch { runCatching { CalendarRepo.refresh() } } }) { Text("Retry") }
            }
        }
        when {
            plan == null -> EmptyState("Pick when you are away", "The planner shows which classes you would miss and whether that leaves you above 75%.")
            plan.isEmpty && plan.closures.isEmpty() -> EmptyState("Nothing on your timetable is held in that window.")
            else -> {
                val total = plan.totalMissed
                AppCard {
                    Text("$total ${if (total == 1) "class" else "classes"} missed", style = MaterialTheme.typography.headlineSmall)
                    Muted("${plan.missedTheory} theory · ${plan.missedLab} lab periods")
                    (App.prefs.attendanceLastSync ?: App.prefs.lastSync)?.let {
                        val at = LocalDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault())
                        Muted("Attendance synced ${at.format(DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.ENGLISH))}")
                    }
                }
                if (plan.closures.isNotEmpty()) {
                    AppCard {
                        Text("Not counted, no classes", style = MaterialTheme.typography.titleSmall)
                        for (c in plan.closures) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(c.date.format(DayFormat), style = MaterialTheme.typography.bodyMedium)
                                    Muted("No classes: ${c.reason}")
                                }
                                Muted("saves ${c.classesSaved}")
                            }
                        }
                    }
                }
                if (plan.rows.isNotEmpty()) SectionTitle("If you skip these classes")
                for (row in plan.byRisk) SkipRow(row, endOfTerm?.isFat == true)
                if (plan.rows.isNotEmpty()) Muted("Assumes you attend every other class. Based on your timetable and the academic calendar.")
            }
        }
    }
}

@Composable
private fun SkipRow(row: SkipPlanRow, isFat: Boolean) {
    val status = LocalStatus.current
    val (color, label) = when (row.status) {
        SkipStatus.Safe -> status.success to "Safe"
        SkipStatus.AtEdge -> status.warning to "On the edge"
        SkipStatus.Below -> status.danger to "Drops below 75%"
    }
    val spare = row.canStillSkip
    val hint = when (row.status) {
        SkipStatus.Safe -> if (spare > 0) "You could still skip $spare more" else null
        SkipStatus.AtEdge -> "No room for another miss"
        SkipStatus.Below -> null
    }
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(row.courseName, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                Muted("${row.courseCode} · ${row.kindLabel}")
            }
            if (row.hasNoHistory) Pill("No records yet") else Pill(label, color = color)
        }
        Spacer(Modifier.height(8.dp))
        Text("Misses ${row.missed} ${if (row.missed == 1) "class" else "classes"}", style = MaterialTheme.typography.bodyMedium)
        Muted(row.days.joinToString("  ") { "${it.date.format(DateTimeFormatter.ofPattern("EEE d", Locale.ENGLISH))}: ${it.periods}" })
        if (!row.hasNoHistory) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat("Now", "${row.currentPercent}%")
                Stat("After trip", "${row.afterLeavePercent}%")
                Stat(if (isFat) "By FAT" else "End of term", "${row.atEndOfTermPercent}%", color)
            }
            hint?.let { Spacer(Modifier.height(6.dp)); Muted(it) }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface) {
    Column {
        Muted(label)
        Text(value, style = MaterialTheme.typography.titleMedium, color = color)
    }
}
