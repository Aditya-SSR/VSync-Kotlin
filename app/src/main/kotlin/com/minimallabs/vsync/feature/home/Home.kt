package com.minimallabs.vsync.feature.home

import com.minimallabs.vsync.ui.Lucide
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.minimallabs.vsync.core.App
import com.minimallabs.vsync.core.Notifications
import com.minimallabs.vsync.feature.mess.MessSection
import com.minimallabs.vsync.feature.outing.ConfirmDialog
import com.minimallabs.vsync.feature.timetable.formatTimeRange
import com.minimallabs.vsync.feature.timetable.formatTo12Hour
import com.minimallabs.vsync.feature.timetable.isLabClass
import com.minimallabs.vsync.feature.timetable.minutesOf
import com.minimallabs.vsync.feature.timetable.sortedClasses
import com.minimallabs.vsync.ui.AppCard
import com.minimallabs.vsync.ui.CardShape
import com.minimallabs.vsync.ui.ChipRow
import com.minimallabs.vsync.ui.Detail
import com.minimallabs.vsync.ui.ErrorLine
import com.minimallabs.vsync.ui.Group
import com.minimallabs.vsync.ui.ListRow
import com.minimallabs.vsync.ui.LocalNav
import com.minimallabs.vsync.ui.LocalTones
import com.minimallabs.vsync.ui.MomentField
import com.minimallabs.vsync.ui.Page
import com.minimallabs.vsync.ui.Pill
import com.minimallabs.vsync.ui.PrimaryButton
import com.minimallabs.vsync.ui.SectionTitle
import com.minimallabs.vsync.ui.TextAction
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** For You: countdowns, the class card (optional), the mess menu. Like the Flutter home. */
@Composable
fun HomeTab() {
    val nav = LocalNav.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        Milestones.removeExpired()
        while (true) { delay(30_000); now = System.currentTimeMillis() }
    }
    Page("", back = false) {
        if (App.prefs.classStackEnabled) { ClassCard(); Spacer(Modifier.height(6.dp)) }

        SectionTitle("Countdowns", Modifier.padding(top = 0.dp), action = { TextAction("Manage") { nav.push { ManageMilestonesPage() } } })
        val list = Milestones.list
        if (list.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().height(120.dp).clip(CardShape).border(1.2.dp, MaterialTheme.colorScheme.outlineVariant, CardShape)
                    .clickable { nav.push { AddMilestonePage() } },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Icon(Lucide.CirclePlus, null, Modifier.size(30.dp), tint = LocalTones.current.secondaryLabel)
                Spacer(Modifier.height(8.dp))
                Text("Track your first countdown", style = MaterialTheme.typography.bodyLarge, color = LocalTones.current.secondaryLabel)
            }
        } else {
            val pager = rememberPagerState { list.size }
            HorizontalPager(pager, Modifier.fillMaxWidth().height(185.dp), pageSpacing = 12.dp) { i -> MilestoneCard(list[i], now) }
            if (list.size > 1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                list.indices.forEach { i ->
                    val w by animateDpAsState(if (pager.currentPage == i) 18.dp else 6.dp, label = "dot")
                    Box(Modifier.padding(horizontal = 3.dp).size(w, 6.dp).clip(CircleShape)
                        .background(if (pager.currentPage == i) MaterialTheme.colorScheme.onSurface else LocalTones.current.tertiaryLabel))
                }
            }
        }

        // The soft divider between the two halves of Home.
        Box(
            Modifier.fillMaxWidth().padding(vertical = 20.dp).height(1.5.dp).background(
                Brush.horizontalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.colorScheme.outlineVariant, Color.Transparent)),
            ),
        )
        MessSection()
    }
}

/** "APPROACHING IN / 12 days / Title" as in the Flutter card. */
@Composable
private fun MilestoneCard(m: Milestone, now: Long) {
    val left = m.targetMs - now
    val days = m.daysLeft()
    val (header, count, unit) = when {
        left <= 0 -> Triple("WRAPPED UP", "Done", "")
        days >= 1 -> Triple("APPROACHING IN", "$days", if (days == 1) "day" else "days")
        left >= 3_600_000L * 2 -> Triple("APPROACHING IN", "${left / 3_600_000L}", "hours")
        left >= 3_600_000L -> Triple("APPROACHING IN", "1", "hour")
        else -> Triple("APPROACHING IN", "${(left / 60_000L).coerceAtLeast(1)}", "min")
    }
    AppCard(Modifier.fillMaxSize()) {
        Text(m.title, style = MaterialTheme.typography.headlineSmall.copy(color = MaterialTheme.colorScheme.onSurface), maxLines = 1)
        Spacer(Modifier.height(4.dp))
        Detail(m.dateTime.format(DateTimeFormatter.ofPattern("EEE, d MMM · h:mm a", Locale.ENGLISH)) + (m.info?.let { "  ·  $it" } ?: ""), maxLines = 1)
        Spacer(Modifier.weight(1f))
        Text(header, style = MaterialTheme.typography.labelSmall, color = LocalTones.current.secondaryLabel)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(count, style = MaterialTheme.typography.displaySmall.copy(fontSize = 44.sp, color = MaterialTheme.colorScheme.onSurface))
            if (unit.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(unit, style = MaterialTheme.typography.bodyLarge, color = LocalTones.current.secondaryLabel, modifier = Modifier.padding(bottom = 8.dp))
            }
        }
    }
}

@Composable
private fun ManageMilestonesPage() {
    val nav = LocalNav.current
    var confirm by remember { mutableStateOf<Milestone?>(null) }
    Page("Countdowns") {
        PrimaryButton("Add countdown", Modifier.fillMaxWidth()) { nav.push { AddMilestonePage() } }
        if (Milestones.list.isEmpty()) Detail("Nothing counting down yet.", Modifier.padding(4.dp))
        else Group {
            for (m in Milestones.list) row {
                ListRow(m.title, m.dateTime.format(DateTimeFormatter.ofPattern("EEE, d MMM · h:mm a", Locale.ENGLISH)) +
                    if (m.reminderEnabled) "  ·  reminder" else "", Icons.Outlined.Delete, chevron = false, onClick = { confirm = m })
            }
        }
    }
    confirm?.let { m -> ConfirmDialog("Remove \"${m.title}\"?", { confirm = null }, "Remove") { Milestones.remove(m.id) } }
}

@Composable
private fun AddMilestonePage() {
    val nav = LocalNav.current
    var title by remember { mutableStateOf("") }
    var info by remember { mutableStateOf("") }
    var at by remember { mutableStateOf<LocalDateTime?>(null) }
    var remind by remember { mutableStateOf(false) }
    var before by remember { mutableIntStateOf(30) }
    var error by remember { mutableStateOf<String?>(null) }
    val permission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted -> if (!granted) { remind = false; App.toast("Notifications are off, so no reminder can be shown.") } }
    val leads = listOf(10 to "10 min", 30 to "30 min", 60 to "1 hour", 1440 to "1 day")
    Page("New countdown") {
        OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(info, { info = it }, label = { Text("Details (optional)") }, modifier = Modifier.fillMaxWidth())
        MomentField("When", at, allowedDate = { !it.isBefore(LocalDate.now()) }) { at = it }
        // The only notification prompt in the app is here, at the point of use.
        Group {
            row {
                ListRow("Remind me before", checked = remind, onCheckedChange = { on ->
                    remind = on
                    if (on && android.os.Build.VERSION.SDK_INT >= 33 && !Notifications.allowed(App.context)) {
                        permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                })
            }
            if (remind) row {
                Box(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
                    ChipRow(leads.map { it.first.toString() }, before.toString(), label = { k -> leads.first { it.first.toString() == k }.second }) { before = it.toInt() }
                }
            }
        }
        error?.let { ErrorLine(it) }
        PrimaryButton("Save", Modifier.fillMaxWidth()) {
            val t = at
            error = when {
                title.isBlank() -> "Give it a title."
                t == null -> "Pick a date and time."
                !t.isAfter(LocalDateTime.now()) -> "Pick a moment in the future."
                else -> null
            }
            if (error == null && t != null) {
                Milestones.add(title, info, t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), remind, before)
                nav.pop()
            }
        }
    }
}

/** The class on now (time left) or the next one, from the saved timetable. */
@Composable
private fun ClassCard() {
    val t = App.user?.timetable ?: return
    val minute = LocalTime.now().let { it.hour * 60 + it.minute }
    val classes = sortedClasses(t, LocalDate.now().dayOfWeek.value)
    val current = classes.firstOrNull { (minutesOf(it.startTime) ?: 0) <= minute && minute < (minutesOf(it.endTime) ?: 0) }
    val next = classes.firstOrNull { (minutesOf(it.startTime) ?: 0) > minute }
    val shown = current ?: next
    AppCard {
        if (shown == null) {
            Text(if (classes.isEmpty()) "No classes today" else "Done for the day", style = MaterialTheme.typography.headlineSmall)
            return@AppCard
        }
        val on = shown == current
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(if (on) "ON NOW" else "NEXT", color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                container = if (on) MaterialTheme.colorScheme.primary else Color.Transparent)
            Spacer(Modifier.width(10.dp))
            Detail(formatTimeRange(shown.startTime, shown.endTime))
            Spacer(Modifier.weight(1f))
            if (isLabClass(shown)) Pill("LAB")
        }
        Spacer(Modifier.height(10.dp))
        Text(shown.courseName, style = MaterialTheme.typography.titleLarge.copy(color = MaterialTheme.colorScheme.onSurface), maxLines = 2)
        Detail(listOf(shown.venue, shown.courseCode).filter { it.isNotBlank() }.joinToString("  ·  "))
        if (on) {
            val start = minutesOf(shown.startTime) ?: 0
            val end = minutesOf(shown.endTime) ?: 1
            Spacer(Modifier.height(14.dp))
            LinearProgressIndicator(
                progress = { ((minute - start).toFloat() / (end - start).coerceAtLeast(1)).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape), strokeCap = StrokeCap.Round,
                color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            )
            Spacer(Modifier.height(6.dp))
            Detail("${end - minute} min left")
        } else {
            Spacer(Modifier.height(4.dp))
            Detail("Starts at ${formatTo12Hour(shown.startTime)}")
        }
    }
}

