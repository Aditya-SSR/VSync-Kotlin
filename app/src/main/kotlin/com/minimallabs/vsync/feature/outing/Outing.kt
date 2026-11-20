package com.minimallabs.vsync.feature.outing

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.minimallabs.vsync.core.App
import com.minimallabs.vsync.core.DemoMode
import com.minimallabs.vsync.core.Failures
import com.minimallabs.vsync.core.Files
import com.minimallabs.vsync.data.AppJson
import com.minimallabs.vsync.data.GeneralOuting
import com.minimallabs.vsync.data.WeekendOuting
import com.minimallabs.vsync.ui.Segmented
import com.minimallabs.vsync.feature.calendar.CalendarRepo
import com.minimallabs.vsync.ui.AppCard
import com.minimallabs.vsync.ui.CenterLoader
import com.minimallabs.vsync.ui.DatePickerPopup
import com.minimallabs.vsync.ui.DayFormat
import com.minimallabs.vsync.ui.ErrorLine
import com.minimallabs.vsync.ui.KeyValue
import com.minimallabs.vsync.ui.LocalStatus
import com.minimallabs.vsync.ui.MomentField
import com.minimallabs.vsync.ui.Muted
import com.minimallabs.vsync.ui.Page
import com.minimallabs.vsync.ui.Pill
import com.minimallabs.vsync.ui.PillButton
import com.minimallabs.vsync.ui.SectionTitle
import com.minimallabs.vsync.ui.rememberLoader
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

val OUTING_PLACES = listOf("Vijayawada", "Guntur", "Tenali", "Eluru", "Others")
val OUTING_TIME_SLOTS = listOf("9:30 AM- 3:30PM", "10:30 AM- 4:30PM", "11:30 AM- 5:30PM", "12:30 PM- 6:30PM")
private val VTOP_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH)

object OutingRules {
    /** General outings must leave and return between 6 AM and 10 PM. */
    fun timeAllowed(t: LocalDateTime): Boolean {
        val h = t.hour
        val m = t.minute
        return h >= 6 && (h < 22 || (h == 22 && m == 0))
    }

    /**
     * Weekend outings: for THIS coming Sunday the deadline is Friday 23:59:59,
     * for the coming Monday it is Saturday 23:59:59.
     */
    fun isDeadlinePassed(outing: LocalDate, now: LocalDateTime, bypass: Boolean): Boolean {
        if (bypass) return false
        val today = now.toLocalDate()
        if (outing.dayOfWeek == DayOfWeek.SUNDAY) {
            val target = today.plusDays(((7 - today.dayOfWeek.value) % 7).toLong())
            if (outing == target) return now.isAfter(target.minusDays(2).atTime(23, 59, 59))
        } else if (outing.dayOfWeek == DayOfWeek.MONDAY) {
            val until = (1 - today.dayOfWeek.value + 7) % 7
            val target = today.plusDays((if (until == 0) 7 else until).toLong())
            if (outing == target) return now.isAfter(target.minusDays(2).atTime(23, 59, 59))
        }
        return false
    }

    fun statusLabel(status: String): String = when (status.trim().lowercase()) {
        "leave request accepted", "outing request accepted" -> "Approved"
        "waiting for warden's approval", "waiting for mentor's approval" -> "Pending"
        "rejected" -> "Rejected"
        else -> status.ifBlank { "Unknown" }
    }
}

object OutingRepo {
    fun cachedGeneral(): List<GeneralOuting>? = App.store.cacheGet("outing:general")?.first?.let { runCatching { AppJson.decodeFromString<List<GeneralOuting>>(it) }.getOrNull() }
    fun cachedWeekend(): List<WeekendOuting>? = App.store.cacheGet("outing:weekend")?.first?.let { runCatching { AppJson.decodeFromString<List<WeekendOuting>>(it) }.getOrNull() }

    suspend fun general(): List<GeneralOuting> {
        val rows: List<GeneralOuting> = if (DemoMode.isOn) DemoMode.section("general_outing_reports")
        else AppJson.decodeFromString(App.vtop { it.text("general_outing_reports") })
        if (!DemoMode.isOn) App.store.cachePut("outing:general", AppJson.encodeToString(rows))
        CalendarRepo.outings = rows
        return rows
    }

    suspend fun weekend(): List<WeekendOuting> {
        val rows: List<WeekendOuting> = if (DemoMode.isOn) DemoMode.section("weekend_outing_reports")
        else AppJson.decodeFromString(App.vtop { it.text("weekend_outing_reports") })
        if (!DemoMode.isOn) App.store.cachePut("outing:weekend", AppJson.encodeToString(rows))
        return rows
    }
}

@Composable
private fun statusColor(status: String): Color = when (OutingRules.statusLabel(status)) {
    "Approved" -> LocalStatus.current.success
    "Pending" -> LocalStatus.current.warning
    "Rejected" -> LocalStatus.current.danger
    else -> LocalStatus.current.neutral
}

@Composable
fun OutingPage() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Page("Outing") {
        Segmented(listOf("General", "Weekend"), tab) { tab = it }
        if (tab == 0) GeneralTab() else WeekendTab()
    }
}

@Composable
private fun Choices(options: List<String>, selected: String?, onPick: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (o in options) {
            val on = o == selected
            Pill(
                o,
                color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                container = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.clickable { onPick(o) },
            )
        }
    }
}

@Composable
private fun SubmitRow(busy: Boolean, onClear: () -> Unit, onSubmit: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        com.minimallabs.vsync.ui.SecondaryButton("Clear", Modifier.weight(1f), enabled = !busy, onClick = onClear)
        com.minimallabs.vsync.ui.PrimaryButton("Apply", Modifier.weight(1f), busy = busy, onClick = onSubmit)
    }
}

@Composable
private fun GeneralTab() {
    val scope = rememberCoroutineScope()
    var place by rememberSaveable { mutableStateOf("") }
    var purpose by rememberSaveable { mutableStateOf("") }
    var from by remember { mutableStateOf<LocalDateTime?>(null) }
    var to by remember { mutableStateOf<LocalDateTime?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val history = rememberLoader(OutingRepo.cachedGeneral())
    LaunchedEffect(Unit) { history.run { OutingRepo.general() } }

    fun submit() {
        val f = from; val t = to
        error = when {
            place.isBlank() -> "Please enter the place of visit"
            purpose.isBlank() -> "Please enter the purpose of visit"
            f == null -> "Please pick the day and time you leave"
            t == null -> "Please pick the day and time you are back"
            !OutingRules.timeAllowed(f) || !OutingRules.timeAllowed(t) -> "Please select a time between 06:00 AM and 10:00 PM"
            !t.isAfter(f) -> "You must be back after the time you leave"
            else -> null
        }
        if (error != null || f == null || t == null) return
        busy = true
        scope.launch {
            try {
                val hhmm = DateTimeFormatter.ofPattern("HH:mm")
                val reply = App.vtop { it.text("submit_general_outing", place.trim(), purpose.trim(), f.format(VTOP_DAY), f.format(hhmm), t.format(VTOP_DAY), t.format(hhmm)) }
                App.toast(reply.ifBlank { "Outing request submitted." })
                place = ""; purpose = ""; from = null; to = null
                history.run { OutingRepo.general() }
            } catch (e: Throwable) {
                error = Failures.messageFor(e)
            } finally {
                busy = false
            }
        }
    }

    AppCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle("Where to")
            OutlinedTextField(place, { place = it }, label = { Text("Place of visit") }, placeholder = { Text("Where are you going?") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(purpose, { purpose = it }, label = { Text("Purpose of visit") }, placeholder = { Text("Why are you going?") }, modifier = Modifier.fillMaxWidth())
            SectionTitle("When")
            val today = LocalDate.now()
            MomentField("Leaving", from, allowedDate = { !it.isBefore(today) }) { from = it }
            MomentField("Back", to, allowedDate = { !it.isBefore(from?.toLocalDate() ?: today) }) { to = it }
            Muted("Leaving and returning must be between 6:00 AM and 10:00 PM.")
            error?.let { ErrorLine(it) }
            SubmitRow(busy, { place = ""; purpose = ""; from = null; to = null; error = null }) { submit() }
        }
    }
    SectionTitle("History")
    val rows = history.value
    when {
        rows == null && history.error != null -> ErrorLine(history.error!!)
        rows == null -> CenterLoader()
        rows.isEmpty() -> Muted("No general outing requests yet.")
        else -> for (o in rows) {
            var confirm by remember(o.leaveId) { mutableStateOf(false) }
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(o.placeOfVisit, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Pill(OutingRules.statusLabel(o.status), color = statusColor(o.status))
                }
                Muted(o.purposeOfVisit)
                Spacer(Modifier.height(6.dp))
                KeyValue("Leaving", "${o.fromDate} ${o.fromTime}")
                KeyValue("Back", "${o.toDate} ${o.toTime}")
                Row {
                    if (o.canDownload) TextButton(onClick = {
                        scope.launch { Files.fetchSaveOpen("general_outing_${o.leaveId}", "General Outing Pass", "pdf") { App.vtop { c -> c.bytes("general_outing_pdf", o.leaveId) } } }
                    }) { Text("Download pass") }
                    if (o.leaveId.isNotBlank() && OutingRules.statusLabel(o.status) == "Pending") TextButton(onClick = { confirm = true }) { Text("Cancel request", color = LocalStatus.current.danger) }
                }
            }
            if (confirm) ConfirmDialog("Cancel this outing request?", { confirm = false }) {
                scope.launch {
                    try { App.toast(App.vtop { it.text("delete_general_outing", o.leaveId) }.ifBlank { "Request cancelled." }) }
                    catch (e: Throwable) { App.toast(Failures.messageFor(e)) }
                    history.run { OutingRepo.general() }
                }
            }
        }
    }
}

@Composable
private fun WeekendTab() {
    val scope = rememberCoroutineScope()
    var place by rememberSaveable { mutableStateOf<String?>(null) }
    var purpose by rememberSaveable { mutableStateOf("") }
    var date by remember { mutableStateOf<LocalDate?>(null) }
    var slot by rememberSaveable { mutableStateOf<String?>(null) }
    var contact by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pick by remember { mutableStateOf(false) }
    val history = rememberLoader(OutingRepo.cachedWeekend())
    LaunchedEffect(Unit) { history.run { OutingRepo.weekend() } }
    val bypass = App.prefs.bypassWeekendOutingRestriction

    fun submit() {
        val d = date
        error = when {
            place == null -> "Please select a place"
            purpose.isBlank() -> "Please enter purpose"
            d == null -> "Please select a date"
            slot == null -> "Please select a time slot"
            contact.isBlank() -> "Please enter contact number"
            !Regex("^\\d{10}$").matches(contact.trim()) -> "Contact number must be 10 digits"
            OutingRules.isDeadlinePassed(d, LocalDateTime.now(), bypass) -> "Application deadline has passed for this date"
            else -> null
        }
        if (error != null || d == null) return
        busy = true
        scope.launch {
            try {
                val reply = App.vtop { it.text("submit_weekend_outing", place!!, purpose.trim(), d.format(VTOP_DAY), slot!!, contact.trim()) }
                App.toast(reply.ifBlank { "Outing request submitted." })
                place = null; purpose = ""; date = null; slot = null; contact = ""
                history.run { OutingRepo.weekend() }
            } catch (e: Throwable) {
                error = Failures.messageFor(e)
            } finally {
                busy = false
            }
        }
    }

    AppCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle("Where to")
            Muted("Place of visit")
            Choices(OUTING_PLACES, place) { place = it }
            OutlinedTextField(purpose, { purpose = it }, label = { Text("Purpose of visit") }, placeholder = { Text("Why are you going?") }, modifier = Modifier.fillMaxWidth())
            SectionTitle("When")
            Muted("Outing date (Sunday or Monday)")
            PillButton(date?.format(DayFormat) ?: "Select a date") { pick = true }
            Muted("Time slot")
            Choices(OUTING_TIME_SLOTS, slot) { slot = it }
            SectionTitle("Contact")
            OutlinedTextField(contact, { v -> contact = v.filter { it.isDigit() }.take(10) }, label = { Text("Contact number") }, placeholder = { Text("10 digit mobile number") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
            error?.let { ErrorLine(it) }
            SubmitRow(busy, { place = null; purpose = ""; date = null; slot = null; contact = ""; error = null }) { submit() }
        }
    }
    if (pick) {
        val today = LocalDate.now()
        DatePickerPopup(date, { pick = false }, allowed = {
            !it.isBefore(today) && (it.dayOfWeek == DayOfWeek.SUNDAY || it.dayOfWeek == DayOfWeek.MONDAY) &&
                (bypass || !OutingRules.isDeadlinePassed(it, LocalDateTime.now(), false))
        }) { date = it }
    }
    SectionTitle("History")
    val rows = history.value
    when {
        rows == null && history.error != null -> ErrorLine(history.error!!)
        rows == null -> CenterLoader()
        rows.isEmpty() -> Muted("No weekend outing requests yet.")
        else -> for (o in rows) {
            var confirm by remember(o.bookingId) { mutableStateOf(false) }
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(o.placeOfVisit, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Pill(OutingRules.statusLabel(o.status), color = statusColor(o.status))
                }
                Muted(o.purposeOfVisit)
                Spacer(Modifier.height(6.dp))
                KeyValue("Date", o.date)
                KeyValue("Time", o.time)
                KeyValue("Contact", o.contactNumber)
                Row {
                    if (o.canDownload) TextButton(onClick = {
                        scope.launch { Files.fetchSaveOpen("weekend_outing_${o.bookingId}", "Weekend Outing Pass", "pdf") { App.vtop { c -> c.bytes("weekend_outing_pdf", o.bookingId) } } }
                    }) { Text("Download pass") }
                    if (o.bookingId.isNotBlank() && OutingRules.statusLabel(o.status) == "Pending") TextButton(onClick = { confirm = true }) { Text("Cancel request", color = LocalStatus.current.danger) }
                }
            }
            if (confirm) ConfirmDialog("Cancel this outing request?", { confirm = false }) {
                scope.launch {
                    try { App.toast(App.vtop { it.text("delete_weekend_outing", o.bookingId) }.ifBlank { "Request cancelled." }) }
                    catch (e: Throwable) { App.toast(Failures.messageFor(e)) }
                    history.run { OutingRepo.weekend() }
                }
            }
        }
    }
}

@Composable
fun ConfirmDialog(title: String, onDismiss: () -> Unit, confirmLabel: String = "Yes", text: String? = null, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = text?.let { { Text(it) } },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("No") } },
    )
}
