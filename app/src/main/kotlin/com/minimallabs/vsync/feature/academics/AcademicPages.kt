package com.minimallabs.vsync.feature.academics

import com.minimallabs.vsync.ui.Lucide
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.FlightTakeoff
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.minimallabs.vsync.core.App
import com.minimallabs.vsync.core.DemoMode
import com.minimallabs.vsync.core.Failures
import com.minimallabs.vsync.core.UserMessage
import com.minimallabs.vsync.core.shouldKeepSaved
import com.minimallabs.vsync.data.AppJson
import com.minimallabs.vsync.data.AttendanceWithCapstone
import com.minimallabs.vsync.data.Biometric
import com.minimallabs.vsync.data.CapstoneAttendance
import com.minimallabs.vsync.data.ExamSchedule
import com.minimallabs.vsync.data.ExamSubject
import com.minimallabs.vsync.data.FacultyBrief
import com.minimallabs.vsync.data.FacultyDetails
import com.minimallabs.vsync.data.Mark
import com.minimallabs.vsync.data.PaymentReceipt
import com.minimallabs.vsync.data.PendingPayment
import com.minimallabs.vsync.feature.calendar.CalendarPage
import com.minimallabs.vsync.feature.course.CoursePage
import com.minimallabs.vsync.feature.da.DigitalAssignmentPage
import com.minimallabs.vsync.feature.gpa.GpaPlannerPage
import com.minimallabs.vsync.feature.outing.OutingPage
import com.minimallabs.vsync.feature.skip.SkipPlannerPage
import com.minimallabs.vsync.feature.webview.VtopWebViewPage
import com.minimallabs.vsync.ui.AppCard
import com.minimallabs.vsync.ui.CenterLoader
import com.minimallabs.vsync.ui.DatePickerPopup
import com.minimallabs.vsync.ui.EmptyState
import com.minimallabs.vsync.ui.ErrorLine
import com.minimallabs.vsync.ui.KeyValue
import com.minimallabs.vsync.ui.LocalNav
import com.minimallabs.vsync.ui.LocalStatus
import com.minimallabs.vsync.ui.Muted
import com.minimallabs.vsync.ui.Page
import com.minimallabs.vsync.ui.Pill
import com.minimallabs.vsync.ui.SectionTitle
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import com.minimallabs.vsync.ui.Segmented
import com.minimallabs.vsync.ui.StatusPill
import com.minimallabs.vsync.ui.TabScreen
import com.minimallabs.vsync.ui.BarButton
import com.minimallabs.vsync.ui.BarSpinner
import com.minimallabs.vsync.ui.Group
import com.minimallabs.vsync.ui.ListRow
import com.minimallabs.vsync.ui.Detail
import com.minimallabs.vsync.ui.CardShape
import com.minimallabs.vsync.ui.LocalTones
import com.minimallabs.vsync.ui.glass
import com.minimallabs.vsync.ui.ChipRow
import androidx.compose.material.icons.rounded.Refresh
import com.minimallabs.vsync.ui.rememberLoader
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// ------------------------------------------------------------------ hub

private class Tile(val icon: ImageVector, val title: String, val open: @Composable () -> Unit)

@Composable
private fun TileGrid(tiles: List<Tile>, list: Boolean) {
    val nav = LocalNav.current
    if (list) {
        Group { for (t in tiles) row { ListRow(t.title, icon = t.icon, onClick = { nav.push(t.open) }) } }
        return
    }
    // Two per row, like small home-screen widgets.
    for (pair in tiles.chunked(2)) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (t in pair) {
                Column(
                    Modifier.weight(1f).height(142.dp).glass(CardShape).clip(CardShape)
                        .clickable { nav.push(t.open) }.padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    Icon(t.icon, null, Modifier.size(32.dp), tint = LocalTones.current.heading)
                    Spacer(Modifier.height(14.dp))
                    Text(t.title, style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
fun AcademicsTab() {
    val p = App.prefs
    val tiles = buildList {
        add(Tile(Lucide.ClipboardCheck, "Attendance") { AttendancePage() })
        add(Tile(Lucide.ListChecks, "Marks") { MarksPage() })
        add(Tile(Lucide.CalendarClock, "Exam Schedule") { ExamSchedulePage() })
        add(Tile(Lucide.BookText, "Course Page") { CoursePage() })
        if (!p.hideAcademicCalendar) add(Tile(Lucide.CalendarRange, "Calendar") { CalendarPage() })
        if (!p.hideGrades) add(Tile(Lucide.GraduationCap, "Grades") { GradesPage() })
        if (!p.hideBiometric) add(Tile(Lucide.Fingerprint, "Biometric Log") { BiometricPage() })
        if (!p.hideDigitalAssignments) add(Tile(Lucide.FileUp, "DA Upload") { DigitalAssignmentPage() })
        if (!p.hideOuting) add(Tile(Lucide.PlaneTakeoff, "Outing") { OutingPage() })
        if (!p.hideFacultyInfo) add(Tile(Lucide.Users, "Faculty Info") { FacultyPage() })
        if (!p.hideOpenVtop) add(Tile(Lucide.Globe, "Open VTOP") { VtopWebViewPage() })
        add(Tile(Lucide.Ellipsis, "More") { MorePage() })
    }
    TabScreen("Academics") {
        TileGrid(tiles, p.academicsListView)
    }
}

@Composable
fun MorePage() {
    Page("More") {
        TileGrid(
            listOf(
                Tile(Lucide.CalendarX, "Skip Planner") { SkipPlannerPage() },
                Tile(Lucide.Calculator, "GPA Planner") { GpaPlannerPage() },
                Tile(Lucide.Wallet, "Payments") { PaymentsPage() },
                Tile(Lucide.Rocket, "Capstone") { CapstonePage() },
            ),
            App.prefs.academicsListView,
        )
    }
}

@Composable
private fun RefreshAction(loading: Boolean, onRefresh: () -> Unit) {
    if (loading) BarSpinner() else BarButton(Icons.Rounded.Refresh, "Refresh", onRefresh)
}

// ------------------------------------------------------------------ marks

object MarksRepo {
    suspend fun refresh(): List<Mark> {
        val saved = App.user?.marks ?: emptyList()
        if (DemoMode.isOn) return saved
        val sem = App.semesterId
        val fresh = AppJson.decodeFromString<List<Mark>>(App.vtop { it.text("marks", sem) })
        // VTOP answers a mishandled request with an empty page: never let that wipe saved marks.
        if (shouldKeepSaved(fresh, saved)) {
            throw UserMessage("VTOP sent no marks this time, so your saved marks are being shown. Try again in a little while.")
        }
        App.user?.let { App.updateUser(it.copy(marks = fresh)) }
        App.updatePrefs { it.copy(marksLastSync = System.currentTimeMillis()) }
        return fresh
    }
}

@Composable
fun MarksPage() {
    val loader = rememberLoader<List<Mark>>()
    val marks = App.user?.marks ?: emptyList()
    val types = remember(marks) { marks.map { it.courseType.trim() }.filter { it.isNotEmpty() }.distinct() }
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    Page("Marks", actions = { RefreshAction(loader.loading) { loader.run { MarksRepo.refresh() } } }) {
        Muted("Last synced ${timeAgo(App.prefs.marksLastSync ?: App.prefs.lastSync)}")
        loader.error?.let { ErrorLine(it) }
        if (types.size > 1) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (t in listOf<String?>(null) + types) {
                    val on = filter == t
                    Pill(
                        t ?: "All",
                        color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        container = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.clickable { filter = t },
                    )
                }
            }
        }
        val shown = marks.filter { filter == null || it.courseType.trim() == filter }
        if (shown.isEmpty()) EmptyState("No marks found", "Marks appear here once your faculty post them.")
        for (m in shown) {
            AppCard {
                Text(m.courseTitle, style = MaterialTheme.typography.titleSmall)
                Muted("${m.courseCode} · ${m.courseType} · ${m.faculty}")
                val total = m.details.sumOf { it.weightageMark.toDoubleOrNull() ?: 0.0 }
                val max = m.details.sumOf { it.weightage.toDoubleOrNull() ?: 0.0 }
                Spacer(Modifier.height(8.dp))
                for (d in m.details) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(d.markTitle, style = MaterialTheme.typography.bodyMedium)
                            Muted("${d.scoredMark} / ${d.maxMark}" + if (d.remark.isNotBlank() && d.remark != "-") " · ${d.remark}" else "")
                        }
                        Text("${d.weightageMark} / ${d.weightage}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (m.details.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    KeyValue("Total (weightage)", "${trimNumber(total)} / ${trimNumber(max)}")
                }
            }
        }
    }
}

fun trimNumber(v: Double): String = if (v == Math.floor(v)) v.toLong().toString() else "%.2f".format(v).trimEnd('0').trimEnd('.')

// ------------------------------------------------------------------ exams

private val EXAM_MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** VTOP writes exam dates as 05-Dec-2026. */
fun parseExamDate(s: String): LocalDate? {
    val p = s.trim().split('-')
    if (p.size != 3) return null
    val month = EXAM_MONTHS.indexOf(p[1]) + 1
    val day = p[0].toIntOrNull()
    val year = p[2].toIntOrNull()
    if (month == 0 || day == null || year == null) return null
    return runCatching { LocalDate.of(year, month, day) }.getOrNull()
}

object ExamRepo {
    suspend fun refresh(): List<ExamSchedule> {
        if (DemoMode.isOn) return App.user?.examSchedule ?: emptyList()
        val sem = App.semesterId
        val fresh = AppJson.decodeFromString<List<ExamSchedule>>(App.vtop { it.text("exam_schedule", sem) })
        App.user?.let { App.updateUser(it.copy(examSchedule = fresh)) }
        App.updatePrefs { it.copy(examScheduleLastSync = System.currentTimeMillis()) }
        return fresh
    }
}

@Composable
fun ExamSchedulePage() {
    val loader = rememberLoader<List<ExamSchedule>>()
    val schedule = App.user?.examSchedule ?: emptyList()
    val labels = listOf("CAT1", "CAT2", "FAT")
    val today = LocalDate.now()
    // Open on the exam type holding the earliest upcoming exam.
    val initial = remember(schedule) {
        var best: Pair<LocalDate, String>? = null
        for (block in schedule) for (s in block.subjects) {
            val d = parseExamDate(s.examDate) ?: continue
            if (d.isBefore(today)) continue
            if (best == null || d.isBefore(best.first)) best = d to block.examType
        }
        labels.indexOf(best?.second?.uppercase()?.trim()).coerceAtLeast(0)
    }
    var tab by rememberSaveable(initial) { mutableIntStateOf(initial) }
    Page("Exam Schedule", actions = { RefreshAction(loader.loading) { loader.run { ExamRepo.refresh() } } }) {
        Muted("Last synced ${timeAgo(App.prefs.examScheduleLastSync ?: App.prefs.lastSync)}")
        loader.error?.let { ErrorLine(it) }
        Segmented(labels, tab) { tab = it }
        val subjects = schedule.firstOrNull { it.examType.uppercase().trim() == labels[tab] }?.subjects ?: emptyList()
        if (subjects.isEmpty()) EmptyState("No ${labels[tab]} exams listed", "VTOP has not published this schedule yet.")
        for (s in subjects.sortedBy { parseExamDate(it.examDate) ?: LocalDate.MAX }) ExamCard(s, today)
    }
}

@Composable
private fun ExamCard(s: ExamSubject, today: LocalDate) {
    val date = parseExamDate(s.examDate)
    val status = LocalStatus.current
    val (label, color) = when {
        date == null -> "Scheduled" to status.neutral
        date == today -> "Today" to status.warning
        date.isAfter(today) -> "Upcoming" to status.neutral
        else -> "Completed" to status.success
    }
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.courseName, style = MaterialTheme.typography.titleSmall)
                Muted("${s.courseCode} · ${s.courseType} · ${s.slot}")
            }
            Pill(label, color = color)
        }
        Spacer(Modifier.height(8.dp))
        KeyValue("Date", "${s.examDate} ${s.examSession}".trim())
        KeyValue("Time", s.examTime)
        KeyValue("Reporting", s.reportingTime)
        KeyValue("Venue", s.venue)
        KeyValue("Seat", listOf(s.seatLocation, s.seatNumber).filter { it.isNotBlank() }.joinToString(" · "))
    }
}

// ------------------------------------------------------------------ grades

@Composable
fun GradesPage() {
    val nav = LocalNav.current
    val gh = App.user?.profile?.gradeHistory
    Page("Grades") {
        if (gh == null) { EmptyState("No grade history"); return@Page }
        AppCard {
            Muted("CGPA")
            Text(gh.cgpa.ifBlank { "-" }, style = MaterialTheme.typography.displaySmall)
            KeyValue("Credits registered", gh.creditsRegistered)
            KeyValue("Credits earned", gh.creditsEarned)
        }
        AppCard(onClick = { nav.push { GpaPlannerPage() } }) {
            Text("GPA Planner", style = MaterialTheme.typography.titleSmall)
            Muted("Set a target CGPA and see the grades you need")
        }
        SectionTitle("Course history")
        if (gh.courses.isEmpty()) Muted("No graded courses yet.")
        for ((month, courses) in gh.courses.groupBy { it.examMonth }) {
            Muted(month.ifBlank { "Other" })
            AppCard {
                for (c in courses) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.courseTitle, style = MaterialTheme.typography.bodyMedium)
                            Muted("${c.courseCode} · ${c.courseType} · ${c.credits} credits")
                        }
                        Text(c.grade, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ biometric

private val VTOP_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

@Composable
fun BiometricPage() {
    val scope = rememberCoroutineScope()
    val today = remember { LocalDate.now() }
    var selected by remember { mutableStateOf(today) }
    // Loaded days are kept in memory; a reply for a day the student moved off is filed under ITS day.
    val loaded = remember { mutableStateMapOf<String, List<Biometric>>() }
    val errors = remember { mutableStateMapOf<String, String>() }
    val loading = remember { mutableStateMapOf<String, Boolean>() }
    var pick by remember { mutableStateOf(false) }

    fun load(day: LocalDate, force: Boolean = false) {
        selected = day
        val key = day.format(VTOP_DATE)
        if (!force && loaded.containsKey(key)) return
        if (loading[key] == true) return
        loading[key] = true
        errors.remove(key)
        scope.launch {
            try {
                val rows: List<Biometric> = if (DemoMode.isOn) DemoMode.section("biometric")
                else AppJson.decodeFromString(App.vtop { it.text("biometric", key) })
                loaded[key] = rows
            } catch (e: Throwable) {
                errors[key] = Failures.messageFor(e)
            } finally {
                loading.remove(key)
            }
        }
    }
    LaunchedEffect(Unit) { load(today) }

    val key = selected.format(VTOP_DATE)
    Page("Biometric Log", actions = { RefreshAction(loading[key] == true) { load(selected, force = true) } }) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (i in 0..6) {
                val d = today.minusDays(i.toLong())
                val on = d == selected
                Pill(
                    when (i) { 0 -> "Today"; 1 -> "Yesterday"; else -> d.format(DateTimeFormatter.ofPattern("EEE d", Locale.ENGLISH)) },
                    color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    container = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.clickable { load(d) },
                )
            }
            Pill("Pick date", modifier = Modifier.clickable { pick = true })
        }
        Muted(selected.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH)))
        val rows = loaded[key]
        when {
            rows == null && errors[key] != null -> ErrorLine(errors[key]!!)
            rows == null -> CenterLoader()
            rows.isEmpty() -> EmptyState("No biometric entries", "Nothing was recorded on this day.")
            else -> for (b in rows) AppCard {
                Text(b.location.ifBlank { "Entry ${b.serial}" }, style = MaterialTheme.typography.titleSmall)
                KeyValue("In", b.inTime)
                KeyValue("Out", b.outTime)
                KeyValue("Duration", b.duration)
            }
        }
    }
    if (pick) DatePickerPopup(selected, { pick = false }, allowed = { !it.isAfter(today) && it.year >= 2024 }) { load(it) }
}

// ------------------------------------------------------------------ faculty

private val TITLES = setOf("dr", "prof", "mr", "mrs", "ms", "miss", "er", "smt", "engr", "eng")

/** "Dr. Prof. A B" -> "A B", so search and sorting ignore titles. */
fun stripFacultyTitle(raw: String): String {
    var name = raw.trim()
    var matched = true
    while (matched) {
        matched = false
        val lower = name.lowercase()
        for (title in TITLES) {
            if (lower.length <= title.length) {
                if (lower.replace(".", "") == title) return ""
                continue
            }
            val prefix = lower.substring(0, title.length)
            val rest = lower.substring(title.length)
            if (prefix.replace(".", "") == title && (rest.startsWith(" ") || rest.startsWith("."))) {
                name = name.substring(title.length).trim()
                matched = true
                break
            }
        }
    }
    return name.replaceFirst(Regex("^[\\s.,)\\-–—:]+"), "").trim()
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun FacultyPage() {
    val all = remember {
        runCatching {
            AppJson.decodeFromString<List<FacultyBrief>>(App.context.assets.open("faculty_list.json").bufferedReader().use { it.readText() })
        }.getOrDefault(emptyList()).sortedBy { stripFacultyTitle(it.facultyName).lowercase() }
    }
    var query by rememberSaveable { mutableStateOf("") }
    var open by remember { mutableStateOf<FacultyBrief?>(null) }
    val shown = remember(query, all) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) all else all.filter { it.facultyName.lowercase().contains(q) || it.schoolOrCentre.lowercase().contains(q) || it.empId.contains(q) }
    }
    Page("Faculty Info", scroll = false) {
        OutlinedTextField(query, { query = it }, label = { Text("Search faculty") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (all.isEmpty()) ErrorLine("Could not load faculty list. Please try again.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
            items(shown, key = { it.empId + it.facultyName }) { f ->
                AppCard(onClick = { open = f }) {
                    Text(f.facultyName, style = MaterialTheme.typography.titleSmall)
                    Muted("${f.designation} · ${f.schoolOrCentre}")
                }
            }
            item { Spacer(Modifier.height(120.dp)) }
        }
    }
    open?.let { f ->
        val loader = rememberLoader<FacultyDetails>()
        LaunchedEffect(f.empId) {
            loader.run {
                if (DemoMode.isOn) DemoMode.section("faculty_details")
                else AppJson.decodeFromString(App.vtop { it.text("faculty_data", f.empId) })
            }
        }
        ModalBottomSheet(onDismissRequest = { open = null }) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(f.facultyName, style = MaterialTheme.typography.titleLarge)
                Muted(f.designation)
                val d = loader.value
                when {
                    d != null -> {
                        KeyValue("Department", d.department)
                        KeyValue("School", d.schoolCentre)
                        KeyValue("Email", d.email)
                        KeyValue("Cabin", d.cabinNumber)
                        if (d.officeHours.isNotEmpty()) SectionTitle("Open hours")
                        for (h in d.officeHours) KeyValue(h.day, h.timings)
                    }
                    loader.error != null -> ErrorLine(loader.error!!)
                    else -> CenterLoader()
                }
            }
        }
    }
}

// ------------------------------------------------------------------ payments

@Composable
fun PaymentsPage() {
    val pending = rememberLoader<List<PendingPayment>>()
    val receipts = rememberLoader<List<PaymentReceipt>>()
    fun load() {
        pending.run {
            if (DemoMode.isOn) DemoMode.section("pending_payments") else AppJson.decodeFromString(App.vtop { it.text("pending_payments") })
        }
        receipts.run {
            if (DemoMode.isOn) DemoMode.section("payment_receipts") else AppJson.decodeFromString(App.vtop { it.text("payment_receipts") })
        }
    }
    LaunchedEffect(Unit) { load() }
    Page("Payments", actions = { RefreshAction(pending.loading || receipts.loading) { load() } }) {
        SectionTitle("Pending")
        val p = pending.value
        when {
            p == null && pending.error != null -> ErrorLine(pending.error!!)
            p == null -> CenterLoader()
            p.isEmpty() -> Muted("Nothing is due.")
            else -> for (x in p) AppCard {
                Text(x.feesHeads, style = MaterialTheme.typography.titleSmall)
                KeyValue("Amount", x.amount)
                KeyValue("Fine", x.fine)
                KeyValue("Total", x.totalAmount)
                KeyValue("Due by", x.endDate)
                Pill(x.paymentStatus.ifBlank { "Unpaid" }, color = LocalStatus.current.warning)
            }
        }
        SectionTitle("Receipts")
        val r = receipts.value
        when {
            r == null && receipts.error != null -> ErrorLine(receipts.error!!)
            r == null -> CenterLoader()
            r.isEmpty() -> Muted("No receipts yet.")
            else -> for (x in r) AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Receipt ${x.receiptNumber}", style = MaterialTheme.typography.titleSmall)
                        Muted("${x.date} · ${x.campusCode}")
                    }
                    Text("₹${x.amount}", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ capstone

object CapstoneRepo {
    private fun key() = "capstone:${App.semesterId}"

    fun saved(): CapstoneAttendance? = App.store.cacheGet(key())?.first?.let {
        runCatching { AppJson.decodeFromString<CapstoneAttendance>(it) }.getOrNull()
    }

    /** A refresh that finds none never wipes a saved capstone (the scraper turns a failed capstone request into none). */
    suspend fun refresh(): CapstoneAttendance? {
        if (DemoMode.isOn) return saved()
        val sem = App.semesterId
        val body = AppJson.decodeFromString<AttendanceWithCapstone>(App.vtop { it.text("attendance_with_capstone", sem) })
        val fresh = body.capstone ?: return saved()
        App.store.cachePut(key(), AppJson.encodeToString(fresh))
        return fresh
    }
}

@Composable
fun CapstonePage() {
    var capstone by remember { mutableStateOf(CapstoneRepo.saved()) }
    val loader = rememberLoader<Boolean>()
    fun load() = loader.run { capstone = CapstoneRepo.refresh(); true }
    LaunchedEffect(Unit) { load() }
    Page("Capstone", actions = { RefreshAction(loader.loading) { load() } }) {
        loader.error?.let { ErrorLine(it) }
        val c = capstone
        if (c == null) {
            if (loader.loading) CenterLoader()
            else EmptyState("No capstone attendance", "VTOP shows capstone / SDP attendance only for students registered for one.")
            return@Page
        }
        AppCard {
            Text(c.title.ifBlank { "Capstone project" }, style = MaterialTheme.typography.titleMedium)
            KeyValue("Guide evaluation", c.guideEvaluationStatus)
            KeyValue("Registered on", c.dateOfRegistration)
        }
        AppCard {
            Muted("Attendance")
            Text(c.percentage.ifBlank { "-" }.let { if (it.endsWith("%") || it == "-") it else "$it%" }, style = MaterialTheme.typography.displaySmall)
            KeyValue("Present", c.present)
            KeyValue("On duty", c.onDuty)
            KeyValue("Absent", c.absent)
        }
        if (c.punches.isNotEmpty()) SectionTitle("Day-wise")
        for (p in c.punches) AppCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${p.date} · ${p.day}", style = MaterialTheme.typography.bodyMedium)
                    Muted(listOf(p.dayType, p.punchTime).filter { it.isNotBlank() }.joinToString(" · "))
                }
                val st = p.status.lowercase()
                Pill(p.status.ifBlank { "-" }, color = if (st.contains("present")) LocalStatus.current.success else if (st.contains("absent")) LocalStatus.current.danger else LocalStatus.current.neutral)
            }
        }
    }
}
