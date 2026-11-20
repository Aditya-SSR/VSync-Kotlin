package com.minimallabs.vsync.feature.skip

import com.minimallabs.vsync.data.Attendance
import com.minimallabs.vsync.data.Day
import com.minimallabs.vsync.data.Timetable
import com.minimallabs.vsync.feature.academics.vtopPercent
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.roundToInt

enum class SkipStatus { Safe, AtEdge, Below }

class SkipDay(val date: LocalDate, val periods: Int)
class SkipClosure(val date: LocalDate, val reason: String, val classesSaved: Int)

/**
 * One (course, theory|lab) the trip touches.
 * A attended, T total (VTOP), F future-before-leave (assumed attended),
 * M missed, R after-return up to FAT (assumed attended).
 */
data class SkipPlanRow(
    val courseCode: String,
    val courseName: String,
    val isLab: Boolean,
    val missed: Int,
    val attended: Int,
    val total: Int,
    val futureBefore: Int,
    val remainingAfter: Int,
    val days: List<SkipDay> = emptyList(),
) {
    val kindLabel get() = if (isLab) "Lab" else "Theory"
    val hasNoHistory get() = total == 0
    private val endAttended get() = attended + futureBefore + remainingAfter
    private val endTotal get() = total + futureBefore + missed + remainingAfter

    val afterLeave: Double
        get() {
            if (hasNoHistory) return 0.0
            val held = total + futureBefore + missed
            return if (held == 0) 0.0 else (attended + futureBefore).toDouble() / held * 100
        }
    val currentPercent get() = vtopPercent(attended, total)
    val afterLeavePercent get() = if (hasNoHistory) 0 else vtopPercent(attended + futureBefore, total + futureBefore + missed)
    val atEndOfTermPercent get() = if (hasNoHistory) 0 else vtopPercent(endAttended, endTotal)
    val atEndOfTerm: Double get() = if (hasNoHistory || endTotal == 0) 0.0 else endAttended.toDouble() / endTotal * 100
    val projected get() = atEndOfTerm

    /** Exact integer maths: below if 4a < 3t; on the edge if 0 <= 4a-3t < 4. */
    private val spareUncapped: Int
        get() {
            val slack = 4 * endAttended - 3 * endTotal
            return if (slack < 0) -1 else slack / 4
        }

    val canStillSkip: Int
        get() {
            if (hasNoHistory) return 0
            val spare = spareUncapped
            if (spare <= 0) return 0
            return minOf(spare, futureBefore + remainingAfter)
        }

    val status: SkipStatus
        get() {
            if (hasNoHistory) return SkipStatus.Safe
            val spare = spareUncapped
            return when {
                spare < 0 -> SkipStatus.Below
                spare == 0 -> SkipStatus.AtEdge
                else -> SkipStatus.Safe
            }
        }
}

class SkipPlan(val rows: List<SkipPlanRow> = emptyList(), val closures: List<SkipClosure> = emptyList()) {
    val isEmpty get() = rows.isEmpty()
    val totalMissed get() = rows.sumOf { it.missed }
    val missedTheory get() = rows.sumOf { if (it.isLab) 0 else it.missed }
    val missedLab get() = rows.sumOf { if (it.isLab) it.missed else 0 }
    val classesSaved get() = closures.sumOf { it.classesSaved }
    val byRisk: List<SkipPlanRow> get() = rows.sortedWith(compareBy<SkipPlanRow> { it.projected }.thenByDescending { it.missed })
}

/** Pure: no storage, no widgets. A class is missed when it STARTS inside [leaveAt, returnAt). */
object SkipPlanner {
    fun compute(
        timetable: Timetable?,
        attendances: List<Attendance>,
        leaveAt: LocalDateTime,
        returnAt: LocalDateTime,
        now: LocalDateTime,
        until: LocalDate? = null,
        closedDays: Map<LocalDate, String> = emptyMap(),
    ): SkipPlan {
        if (timetable == null || !returnAt.isAfter(leaveAt)) return SkipPlan()
        val periodMinutes = periodLengthInMinutes(timetable)

        val byKind = LinkedHashMap<String, Attendance>()
        val byCode = LinkedHashMap<String, MutableList<Attendance>>()
        for (a in attendances) {
            val code = a.courseCode.trim()
            if (code.isEmpty()) continue
            byKind.putIfAbsent(key(code, isLabAttendance(a)), a)
            byCode.getOrPut(code) { ArrayList() }.add(a)
        }

        val missed = LinkedHashMap<String, Int>()
        val futureBefore = HashMap<String, Int>()
        val remainingAfter = HashMap<String, Int>()
        val names = HashMap<String, String>()
        val codes = HashMap<String, String>()
        val labs = HashMap<String, Boolean>()
        val perDay = HashMap<String, MutableMap<LocalDate, Int>>()
        val closures = LinkedHashMap<LocalDate, Pair<String, Int>>()

        val today = now.toLocalDate()
        val endExclusive = until ?: returnAt.toLocalDate().plusDays(1)

        var day = today
        while (day.isBefore(endExclusive)) {
            val slots = timetable.forWeekday(day.dayOfWeek.value)
            val closure = closedDays[day]
            for (slot in slots) {
                val time = parseClassTime(slot.startTime) ?: continue
                val start = LocalDateTime.of(day, time)
                if (!start.isAfter(now)) continue
                val code = slot.courseCode.trim()
                if (code.isEmpty()) continue
                val periods = periodCount(slot, periodMinutes)
                val inWindow = !start.isBefore(leaveAt) && start.isBefore(returnAt)
                if (closure != null) {
                    // A closed day contributes no classes anywhere.
                    if (inWindow) closures[day] = closure to ((closures[day]?.second ?: 0) + periods)
                    continue
                }
                val isLab = isLabSlot(slot)
                val k = key(code, isLab)
                codes[k] = code
                labs[k] = isLab
                names[k] = slot.courseName.ifBlank { code }.trim()
                when {
                    inWindow -> {
                        missed[k] = (missed[k] ?: 0) + periods
                        val days = perDay.getOrPut(k) { HashMap() }
                        days[day] = (days[day] ?: 0) + periods
                    }
                    start.isBefore(leaveAt) -> futureBefore[k] = (futureBefore[k] ?: 0) + periods
                    else -> remainingAfter[k] = (remainingAfter[k] ?: 0) + periods
                }
            }
            day = day.plusDays(1)
        }

        val rows = missed.keys.map { k ->
            val code = codes[k]!!
            val isLab = labs[k]!!
            val a = byKind[key(code, isLab)] ?: byCode[code]?.takeIf { it.size == 1 }?.first()
            SkipPlanRow(
                courseCode = code,
                courseName = a?.courseName?.trim()?.ifEmpty { null } ?: names[k]?.trim()?.ifEmpty { null } ?: code,
                isLab = isLab,
                missed = missed[k] ?: 0,
                attended = a?.attendedClasses?.trim()?.toIntOrNull() ?: 0,
                total = a?.totalClasses?.trim()?.toIntOrNull() ?: 0,
                futureBefore = futureBefore[k] ?: 0,
                remainingAfter = remainingAfter[k] ?: 0,
                days = (perDay[k] ?: emptyMap()).map { SkipDay(it.key, it.value) }.sortedBy { it.date },
            )
        }
        val closureList = closures.map { SkipClosure(it.key, it.value.first, it.value.second) }.sortedBy { it.date }
        return SkipPlan(rows, closureList)
    }

    fun parseClassTime(time: String?): LocalTime? {
        if (time == null) return null
        val parts = time.trim().split(':')
        if (parts.size != 2) return null
        val h = parts[0].trim().toIntOrNull() ?: return null
        val m = parts[1].trim().toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return LocalTime.of(h, m)
    }

    fun isLabSlot(slot: Day) = looksLikeLab(slot.courseType)
    fun isLabAttendance(a: Attendance) = looksLikeLab(a.courseTypeCode) || looksLikeLab(a.courseType)

    private fun looksLikeLab(value: String?): Boolean {
        val t = (value ?: "").trim().lowercase()
        return t == "ela" || t == "lo" || t.contains("lab")
    }

    private fun key(code: String, isLab: Boolean) = "$code|${if (isLab) "lab" else "th"}"

    /** Labs trust the '+' in the slot string (L31+L32 = 2); theory is counted by clock duration. */
    private fun periodCount(slot: Day, periodMinutes: Int?): Int {
        if (isLabSlot(slot)) {
            val named = slot.slot.count { it == '+' } + 1
            if (named > 1) return named
        }
        if (periodMinutes == null) return 1
        val start = parseClassTime(slot.startTime) ?: return 1
        val end = parseClassTime(slot.endTime) ?: return 1
        val count = (Duration.between(start, end).toMinutes().toDouble() / periodMinutes).roundToInt()
        return if (count < 1) 1 else count
    }

    /** The most common theory class length (ties: the shorter). */
    private fun periodLengthInMinutes(timetable: Timetable): Int? {
        val theory = LinkedHashMap<Int, Int>()
        val any = LinkedHashMap<Int, Int>()
        for (weekday in 1..7) for (slot in timetable.forWeekday(weekday)) {
            val start = parseClassTime(slot.startTime) ?: continue
            val end = parseClassTime(slot.endTime) ?: continue
            val minutes = Duration.between(start, end).toMinutes().toInt()
            if (minutes <= 0) continue
            if (!isLabSlot(slot)) theory[minutes] = (theory[minutes] ?: 0) + 1
            if (minutes <= 60) any[minutes] = (any[minutes] ?: 0) + 1
        }
        val durations = if (theory.isNotEmpty()) theory else any
        if (durations.isEmpty()) return null
        return durations.entries.reduce { a, b ->
            when {
                a.value != b.value -> if (a.value > b.value) a else b
                else -> if (a.key < b.key) a else b
            }
        }.key
    }
}
