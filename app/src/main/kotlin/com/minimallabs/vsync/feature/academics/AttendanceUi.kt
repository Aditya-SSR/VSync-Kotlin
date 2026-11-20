package com.minimallabs.vsync.feature.academics

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.minimallabs.vsync.core.App
import com.minimallabs.vsync.data.Attendance
import com.minimallabs.vsync.feature.calendar.AttendanceProjection
import com.minimallabs.vsync.feature.calendar.CalendarRepo
import com.minimallabs.vsync.feature.skip.SkipPlannerPage
import com.minimallabs.vsync.ui.AppCard
import com.minimallabs.vsync.ui.BarButton
import com.minimallabs.vsync.ui.BarSpinner
import com.minimallabs.vsync.ui.CenterLoader
import com.minimallabs.vsync.ui.Detail
import com.minimallabs.vsync.ui.EmptyState
import com.minimallabs.vsync.ui.ErrorLine
import com.minimallabs.vsync.ui.Group
import com.minimallabs.vsync.ui.ListRow
import com.minimallabs.vsync.ui.LocalNav
import com.minimallabs.vsync.ui.LocalStatus
import com.minimallabs.vsync.ui.LocalTones
import com.minimallabs.vsync.ui.Page
import com.minimallabs.vsync.ui.SectionTitle
import com.minimallabs.vsync.ui.Segmented
import com.minimallabs.vsync.ui.StatusPill
import com.minimallabs.vsync.ui.rememberLoader
import java.time.LocalDate

@Composable
fun statusColorFor(s: AttendanceStanding): Color = when (s.kind) {
    AdviceKind.MustAttend -> LocalStatus.current.danger
    AdviceKind.AtEdge -> LocalStatus.current.warning
    AdviceKind.CanSkip -> LocalStatus.current.success
    AdviceKind.None -> LocalStatus.current.neutral
}

/** A thin ring showing a percentage, with the number inside it. */
@Composable
fun PercentRing(percent: Int, color: Color, size: Dp = 54.dp, stroke: Dp = 4.dp, big: Boolean = false) {
    val track = LocalTones.current.fill
    val sweep by animateFloatAsState(percent.coerceIn(0, 100) * 3.6f, label = "ring")
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val s = Stroke(stroke.toPx(), cap = StrokeCap.Round)
            val inset = stroke.toPx() / 2
            val arc = Size(this.size.width - inset * 2, this.size.height - inset * 2)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arc, style = s)
            drawArc(color, -90f, sweep, false, Offset(inset, inset), arc, style = s)
        }
        Text("$percent", style = if (big) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun AttendancePage() {
    val nav = LocalNav.current
    val loader = rememberLoader<List<Attendance>>()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val all = App.user?.attendance ?: emptyList()
    Page(
        "Attendance",
        subtitle = "Synced ${timeAgo(App.prefs.attendanceLastSync ?: App.prefs.lastSync)}",
        actions = { if (loader.loading) BarSpinner() else BarButton(Icons.Rounded.Refresh, "Refresh") { loader.run { AttendanceRepo.refresh() } } },
    ) {
        loader.error?.let { ErrorLine(it) }
        Segmented(listOf("Theory", "Lab"), tab) { tab = it }
        Group {
            row { ListRow("Want to skip days?", "See what each day costs you", Icons.Outlined.EventBusy, onClick = { nav.push { SkipPlannerPage() } }) }
        }
        val filter = if (tab == 0) "Theory" else "Lab"
        val rows = all.filter { it.courseType.contains(filter) }
        if (rows.isEmpty()) EmptyState("No $filter courses", "Nothing to show here.")
        for (a in rows) AttendanceCard(a) { nav.push { AttendanceCalculatorPage(a) } }
    }
}

@Composable
private fun AttendanceCard(a: Attendance, onClick: () -> Unit) {
    val s = AttendanceStanding.of(a)
    val color = statusColorFor(s)
    // As in the Flutter card: the percentage big in the status colour, how far it is from 75%,
    // the advice on the right, then the course.
    val pct = vtopPercent(s.attended, s.total)
    val delta = pct - 75
    AppCard(onClick = onClick, padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 22.dp, vertical = 22.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
                Text("$pct%", style = MaterialTheme.typography.displayMedium.copy(fontSize = 48.sp, lineHeight = 52.sp, color = color))
                if (s.total > 0) {
                    Spacer(Modifier.width(8.dp))
                    Text((if (delta >= 0) "+" else "") + "$delta%", style = MaterialTheme.typography.titleLarge.copy(color = color),
                        modifier = Modifier.padding(bottom = 6.dp))
                }
            }
            Text(s.advice, style = MaterialTheme.typography.titleLarge.copy(fontSize = 19.sp, color = color.copy(alpha = 0.85f)), modifier = Modifier.padding(top = 6.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(a.courseName, style = MaterialTheme.typography.titleLarge.copy(color = MaterialTheme.colorScheme.onSurface), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text("${a.courseCode}  ·  ${s.attended}/${s.total}", style = MaterialTheme.typography.bodyLarge, color = LocalTones.current.secondaryLabel)
    }
}

/** One course: where it stands, a what-if (attend / skip N more), classes left, and day-wise history. */
@Composable
fun AttendanceCalculatorPage(a: Attendance) {
    val base = AttendanceStanding.of(a)
    var extraAttended by rememberSaveable { mutableIntStateOf(0) }
    var extraSkipped by rememberSaveable { mutableIntStateOf(0) }
    val s = AttendanceStanding(base.attended + extraAttended, base.total + extraAttended + extraSkipped, base.isLab)
    val color = statusColorFor(s)

    val type = a.courseTypeCode.ifBlank { a.courseType }
    val detail = rememberLoader(AttendanceRepo.cachedDetail(a.courseId, type))
    LaunchedEffect(a.courseId) { detail.run { AttendanceRepo.detail(a.courseId, type) } }

    Page(a.courseName, subtitle = "${a.courseCode}  ·  ${a.courseType}  ·  ${a.faculty}") {
        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PercentRing(vtopPercent(s.attended, s.total), color, size = 108.dp, stroke = 7.dp, big = true)
                Spacer(Modifier.width(20.dp))
                Column {
                    Text(s.advice, style = MaterialTheme.typography.titleMedium, color = color)
                    Detail("${s.attended} of ${s.total} attended")
                    if (extraAttended + extraSkipped > 0) Detail("If you attend $extraAttended and skip $extraSkipped more")
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Counter("Attend", extraAttended, Modifier.weight(1f)) { extraAttended = it }
                Counter("Skip", extraSkipped, Modifier.weight(1f)) { extraSkipped = it }
            }
        }
        // Classes left until the next exam VTOP lists (FAT first, else any exam).
        val months = CalendarRepo.months
        val timetable = App.user?.timetable
        val today = LocalDate.now()
        val nextExam = if (months.isEmpty()) null else AttendanceProjection.fatStart(months, today) ?: AttendanceProjection.examStart(months, today)
        val left = if (nextExam == null || timetable == null || a.courseCode.isBlank()) null
        else AttendanceProjection.classesLeftUntil(a.courseCode, timetable, months, today, nextExam)
        Group {
            row {
                ListRow("Classes before CAT/FAT", if (left == null) "Open the Calendar once to work this out" else null,
                    Icons.Outlined.CalendarMonth, value = left?.toString(), chevron = false)
            }
        }
        SectionTitle("History")
        val rows = detail.value
        when {
            rows == null && detail.loading -> CenterLoader()
            rows.isNullOrEmpty() && detail.error != null -> { ErrorLine("Failed to load day-wise attendance"); Detail(detail.error ?: "") }
            rows.isNullOrEmpty() -> Detail("No day-wise attendance posted yet.", Modifier.padding(horizontal = 4.dp))
            else -> Group {
                for (d in rows) row {
                    val st = d.status.lowercase()
                    val c = when {
                        st == "present" -> LocalStatus.current.success
                        st == "absent" -> LocalStatus.current.danger
                        else -> LocalTones.current.secondaryLabel
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(d.date, style = MaterialTheme.typography.bodyLarge)
                            Detail(d.dayTime)
                        }
                        StatusPill(d.status.ifBlank { "-" }, c)
                    }
                }
            }
        }
    }
}

@Composable
private fun Counter(label: String, value: Int, modifier: Modifier, onChange: (Int) -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(16.dp)).background(LocalTones.current.fill).padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { if (value > 0) onChange(value - 1) }) { Icon(Icons.Rounded.Remove, "Less") }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$value", style = MaterialTheme.typography.titleMedium)
            Detail(label)
        }
        IconButton(onClick = { onChange(value + 1) }) { Icon(Icons.Rounded.Add, "More") }
    }
}
