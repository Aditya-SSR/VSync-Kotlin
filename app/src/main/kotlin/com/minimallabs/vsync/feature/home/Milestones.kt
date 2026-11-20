package com.minimallabs.vsync.feature.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.minimallabs.vsync.core.App
import com.minimallabs.vsync.core.Notifications
import com.minimallabs.vsync.data.AppJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** A countdown the student added ("Project review", a date and time). */
@Serializable
data class Milestone(
    val id: String,
    val title: String,
    val info: String? = null,
    /** Epoch millis of the target moment. */
    val targetMs: Long,
    val reminderEnabled: Boolean = false,
    val reminderMinutesBefore: Int = 30,
) {
    val dateTime: LocalDateTime get() = LocalDateTime.ofInstant(Instant.ofEpochMilli(targetMs), ZoneId.systemDefault())
    val date: LocalDate get() = dateTime.toLocalDate()
    fun daysLeft(): Int = ChronoUnit.DAYS.between(LocalDate.now(), date).toInt()
    fun hoursLeft(): Long = (targetMs - System.currentTimeMillis()) / 3_600_000L
    fun isPassed(): Boolean = targetMs < System.currentTimeMillis()
}

object Milestones {
    private const val KEY = "milestones_v1"

    var list by mutableStateOf<List<Milestone>>(emptyList())
        private set
    private var loaded = false

    fun load() {
        if (loaded) return
        loaded = true
        list = App.store.sp.getString(KEY, null)?.let {
            runCatching { AppJson.decodeFromString<List<Milestone>>(it) }.getOrNull()
        }?.sortedBy { it.targetMs } ?: emptyList()
    }

    private fun persist() = App.store.sp.edit().putString(KEY, AppJson.encodeToString(list)).apply()

    fun add(title: String, info: String?, targetMs: Long, reminderEnabled: Boolean, reminderMinutesBefore: Int) {
        load()
        val m = Milestone(
            id = (System.nanoTime() / 1000).toString(),
            title = title.trim(),
            info = info?.trim()?.ifEmpty { null },
            targetMs = targetMs,
            reminderEnabled = reminderEnabled,
            reminderMinutesBefore = reminderMinutesBefore,
        )
        list = (list + m).sortedBy { it.targetMs }
        persist()
        if (m.reminderEnabled) Notifications.scheduleReminder(m.id, m.title, m.targetMs, m.reminderMinutesBefore)
    }

    fun remove(id: String) {
        load()
        list = list.filter { it.id != id }
        persist()
        Notifications.cancelReminder(id)
    }

    /** Drops countdowns that ended more than 10 minutes ago. */
    fun removeExpired() {
        load()
        val cutoff = System.currentTimeMillis() - 10 * 60_000L
        val kept = list.filter { it.targetMs > cutoff }
        if (kept.size == list.size) return
        list = kept
        persist()
    }

    fun syncReminders() {
        load()
        for (m in list) if (m.reminderEnabled) Notifications.scheduleReminder(m.id, m.title, m.targetMs, m.reminderMinutesBefore)
    }

    fun clear() {
        load()
        list.forEach { Notifications.cancelReminder(it.id) }
    }
}
