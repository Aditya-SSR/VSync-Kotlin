package com.minimallabs.vsync.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

val DayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH)
val TimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerPopup(
    initial: LocalDate?,
    onDismiss: () -> Unit,
    allowed: (LocalDate) -> Boolean = { true },
    onPick: (LocalDate) -> Unit,
) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = (initial ?: LocalDate.now()).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) =
                allowed(Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate())
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) { DatePicker(state) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerPopup(initial: LocalTime?, title: String, onDismiss: () -> Unit, onPick: (LocalTime) -> Unit) {
    val state = rememberTimePickerState(initial?.hour ?: 9, initial?.minute ?: 0, is24Hour = false)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Box(contentAlignment = Alignment.Center) { TimePicker(state) } },
        confirmButton = { TextButton(onClick = { onPick(LocalTime.of(state.hour, state.minute)); onDismiss() }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** "Leaving  [Fri, 14 Nov] [9:00 AM]": the date and the time are separate pills. */
@Composable
fun MomentField(
    label: String,
    value: LocalDateTime?,
    timeTitle: String = label,
    allowedDate: (LocalDate) -> Boolean = { true },
    onChange: (LocalDateTime) -> Unit,
) {
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Muted(label)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(value?.format(DayFormat) ?: "Select a date", Modifier.weight(1f)) { pickDate = true }
            PillButton(value?.format(TimeFormat) ?: "Time") { if (value == null) pickDate = true else pickTime = true }
        }
    }
    if (pickDate) DatePickerPopup(value?.toLocalDate(), { pickDate = false }, allowedDate) { d ->
        onChange(LocalDateTime.of(d, value?.toLocalTime() ?: LocalTime.MIDNIGHT))
        if (value == null) pickTime = true
    }
    if (pickTime && value != null) TimePickerPopup(value.toLocalTime(), timeTitle, { pickTime = false }) { t ->
        onChange(LocalDateTime.of(value.toLocalDate(), t))
    }
}

@Composable
fun PillButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        modifier = modifier.clip(RoundedCornerShape(14.dp)).background(LocalTones.current.fill)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        maxLines = 1,
    )
}
