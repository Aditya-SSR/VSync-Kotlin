package com.minimallabs.vsync.feature.academics

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.minimallabs.vsync.core.App
import com.minimallabs.vsync.core.DemoMode
import com.minimallabs.vsync.data.AppJson
import com.minimallabs.vsync.data.Attendance
import com.minimallabs.vsync.data.AttendanceDetail
import com.minimallabs.vsync.feature.calendar.AttendanceProjection
import com.minimallabs.vsync.feature.calendar.CalendarRepo
import com.minimallabs.vsync.feature.skip.SkipPlannerPage
import com.minimallabs.vsync.ui.AppCard
import com.minimallabs.vsync.ui.CenterLoader
import com.minimallabs.vsync.ui.EmptyState
import com.minimallabs.vsync.ui.ErrorLine
import com.minimallabs.vsync.ui.LocalNav
import com.minimallabs.vsync.ui.LocalStatus
import com.minimallabs.vsync.ui.Muted
import com.minimallabs.vsync.ui.Page
import com.minimallabs.vsync.ui.Pill
import com.minimallabs.vsync.ui.SectionTitle
import com.minimallabs.vsync.ui.rememberLoader
import kotlinx.serialization.encodeToString
import java.time.LocalDate

// ------------------------------------------------------------------ logic

const val ATTENDANCE_THRESHOLD = 75.0

/** Rounds UP like VTOP does (31/34 = 92%, not 91%). */
fun vtopPercent(attended: Int, total: Int): Int = if (total <= 0) 0 else (attended * 100 + total - 1) / total

enum class AdviceKind { None, CanSkip, AtEdge, MustAttend }

/** Where a subject stands against 75%, in classes (or whole labs: 2 periods = 1 lab). */
class AttendanceStanding(val attended: Int, val total: Int, val isLab: Boolean = false) {
    val percent: Double get() = if (total == 0) 0.0 else attended.toDouble() / total * 100
    val isSafe: Boolean get() = total == 0 || percent >= ATTENDANCE_THRESHOLD
    val canSkip: Int
        get() {
            if (total == 0) return 0
            val k = Math.floorDiv(4 * attended - 3 * total, 3)
            return if (k > 0) k else 0
        }
    val mustAttend: Int get() = (3 * total - 4 * attended).coerceAtLeast(0)
    val canSkipUnits: Int get() = if (isLab) canSkip / PERIODS_PER_LAB else canSkip
    val mustAttendUnits: Int get() = if (isLab) (mustAttend + PERIODS_PER_LAB - 1) / PERIODS_PER_LAB else mustAttend
    val kind: AdviceKind
        get() = when {
            total == 0 -> AdviceKind.None
            !isSafe -> AdviceKind.MustAttend
            canSkipUnits == 0 -> AdviceKind.AtEdge
            else -> AdviceKind.CanSkip
        }

    private fun count(n: Int) = if (isLab) "$n ${if (n == 1) "lab" else "labs"}" else "$n"

    val advice: String
        get() = when (kind) {
            AdviceKind.None -> "No classes yet"
            AdviceKind.MustAttend -> "Attend ${count(mustAttendUnits)}"
            AdviceKind.AtEdge -> "Don't skip"
            AdviceKind.CanSkip -> "Can skip ${count(canSkipUnits)}"
        }

    companion object {
        const val PERIODS_PER_LAB = 2

        fun isLabRow(a: Attendance): Boolean {
            fun looks(v: String): Boolean {
                val t = v.trim().lowercase()
                return t == "ela" || t == "lo" || t.contains("lab")
            }
            return looks(a.courseTypeCode) || looks(a.courseType)
        }

        fun of(a: Attendance) = AttendanceStanding(
            a.attendedClasses.trim().toIntOrNull() ?: 0,
            a.totalClasses.trim().toIntOrNull() ?: 0,
            isLabRow(a),
        )
    }
}

object AttendanceRepo {
    suspend fun refresh(): List<Attendance> {
        if (DemoMode.isOn) return App.user?.attendance ?: emptyList()
        val sem = App.semesterId
        val fresh = AppJson.decodeFromString<List<Attendance>>(App.vtop { it.text("attendance", sem) })
        App.user?.let { App.updateUser(it.copy(attendance = fresh)) }
        App.updatePrefs { it.copy(attendanceLastSync = System.currentTimeMillis()) }
        return fresh
    }

    private fun detailKey(courseId: String, courseType: String) = "attendance-detail:$courseId:$courseType"

    fun cachedDetail(courseId: String, courseType: String): List<AttendanceDetail>? =
        App.store.cacheGet(detailKey(courseId, courseType))?.first?.let {
            runCatching { AppJson.decodeFromString<List<AttendanceDetail>>(it) }.getOrNull()
        }

    suspend fun detail(courseId: String, courseType: String): List<AttendanceDetail> {
        if (DemoMode.isOn) return DemoMode.section("detailed_attendance")
        val sem = App.semesterId
        val fresh = AppJson.decodeFromString<List<AttendanceDetail>>(App.vtop { it.text("attendance_detail", sem, courseId, courseType) })
        App.store.cachePut(detailKey(courseId, courseType), AppJson.encodeToString(fresh))
        return fresh
    }
}

fun timeAgo(ms: Long?): String {
    if (ms == null) return "never"
    val d = (System.currentTimeMillis() - ms) / 1000
    return when {
        d < 45 -> "a moment ago"
        d < 3600 -> "${(d / 60).coerceAtLeast(1)} minutes ago"
        d < 86400 -> "${d / 3600} hours ago"
        else -> "${d / 86400} days ago"
    }
}

