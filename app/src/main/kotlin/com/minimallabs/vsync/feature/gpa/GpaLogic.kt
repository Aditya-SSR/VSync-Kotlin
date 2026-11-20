package com.minimallabs.vsync.feature.gpa

import com.minimallabs.vsync.data.GradeHistory
import com.minimallabs.vsync.data.Timetable
import java.time.YearMonth

enum class PlannerGrade(val letter: String, val points: Int) {
    S("S", 10), A("A", 9), B("B", 8), C("C", 7), D("D", 6), E("E", 5), F("F", 0);

    companion object {
        /** The lowest grade worth at least [required] points; null if even S is not enough. */
        fun atLeast(required: Double): PlannerGrade? {
            if (required > 10) return null
            var best: PlannerGrade? = null
            for (g in entries) if (g.points >= required) best = g
            return best
        }

        fun of(raw: String): PlannerGrade? = entries.firstOrNull { it.letter == raw.trim().uppercase() }
    }
}

data class SemesterCourse(val code: String, val name: String, val credits: Double?)

/** This semester's courses with credits, from VTOP's course table (or the weekly grid for an old save). */
object SemesterCourses {
    fun isComplete(t: Timetable?) = t != null && !t.courses.isNullOrEmpty()

    private class Part(val code: String, val name: String, val kind: String, val credits: String)

    fun from(t: Timetable?): List<SemesterCourse> {
        if (t == null) return emptyList()
        val table = t.courses
        val parts = if (!table.isNullOrEmpty()) table.map { Part(it.code, it.name, it.kind.ifEmpty { it.typeCode }, it.credits) }
        else partsFromGrid(t)
        return combine(parts)
    }

    private fun partsFromGrid(t: Timetable): List<Part> {
        val byKey = LinkedHashMap<String, Part>()
        for (d in 1..7) for (slot in t.forWeekday(d)) {
            val code = slot.courseCode.trim()
            if (code.isEmpty()) continue
            val kind = slot.courseType.trim()
            val key = "${code.uppercase()}|${kind.uppercase()}"
            val part = Part(code, slot.courseName.trim(), kind, slot.credits.trim())
            val seen = byKey[key]
            if (seen == null || (creditsOf(seen.credits) == null && creditsOf(part.credits) != null)) byKey[key] = part
        }
        return byKey.values.toList()
    }

    /** Groups by code, counts each part once, sums parts; one unreadable part makes the course's credits unknown (never guessed). */
    private fun combine(parts: List<Part>): List<SemesterCourse> {
        val names = HashMap<String, String>()
        val credits = LinkedHashMap<String, Double?>()
        val seenParts = HashSet<String>()
        for (p in parts) {
            val code = p.code.trim().uppercase()
            if (code.isEmpty()) continue
            if (!seenParts.add("$code|${p.kind.uppercase()}")) continue
            if (names[code].isNullOrEmpty() && p.name.isNotEmpty()) names[code] = p.name
            val value = creditsOf(p.credits)
            if (credits.containsKey(code) && credits[code] == null) continue
            credits[code] = if (value == null) null else (credits[code] ?: 0.0) + value
        }
        return credits.entries
            .filter { it.value == null || it.value!! > 0 }
            .map { SemesterCourse(it.key, names[it.key]?.ifEmpty { null } ?: it.key, it.value) }
            .sortedBy { it.code }
    }

    fun creditsOf(raw: String?): Double? {
        val text = (raw ?: "").trim().replace(',', '.')
        return Regex("\\d+(?:\\.\\d+)?").find(text)?.value?.toDoubleOrNull()
    }
}

class GpaProjection(val semesterCredits: Double, val semesterGpa: Double, val newCgpa: Double, val change: Double)

class GpaGoal(val required: Double, val reachable: Boolean, val alreadyThere: Boolean, val best: Double) {
    val suggested: PlannerGrade
        get() = when {
            !reachable -> PlannerGrade.S
            alreadyThere -> PlannerGrade.A
            else -> PlannerGrade.atLeast(required) ?: PlannerGrade.S
        }
}

object GpaPlanner {
    fun project(cgpa: Double, creditsDone: Double, courses: List<Pair<Double, PlannerGrade>>): GpaProjection {
        val credits = courses.sumOf { it.first }
        val points = courses.sumOf { it.first * it.second.points }
        val sem = if (credits == 0.0) 0.0 else points / credits
        val total = creditsDone + credits
        val newCgpa = if (total == 0.0) 0.0 else (cgpa * creditsDone + points) / total
        return GpaProjection(credits, sem, newCgpa, newCgpa - cgpa)
    }

    fun goal(cgpa: Double, creditsDone: Double, semesterCredits: Double, target: Double): GpaGoal? {
        if (semesterCredits <= 0) return null
        val total = creditsDone + semesterCredits
        val required = (target * total - cgpa * creditsDone) / semesterCredits
        val best = (cgpa * creditsDone + semesterCredits * 10) / total
        return GpaGoal(required, required <= 10, required <= 0, best)
    }
}

// ------------------------------------------------------------------ improve CGPA

class PastCourse(
    val key: String, val code: String, val title: String, val credits: Double,
    val grade: String, val gradeValue: PlannerGrade?, val superseded: Boolean,
) {
    val canChange get() = gradeValue != null && credits > 0 && !superseded
    val hasPoints get() = gradeValue != null && credits > 0
}

class PastSession(val label: String, val month: YearMonth?, val courses: List<PastCourse>)
class SessionOutcome(val credits: Double, val now: Double?, val after: Double?)
class CgpaOutcome(val now: Double, val after: Double, val matchesVtop: Boolean) { val change get() = after - now }

object CgpaImprover {
    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    fun month(raw: String): YearMonth? {
        val m = Regex("^\\s*([A-Za-z]{3})[A-Za-z]*[\\s\\-/]+(\\d{4})\\s*$").find(raw) ?: return null
        val month = MONTHS.indexOf(m.groupValues[1].lowercase()) + 1
        val year = m.groupValues[2].toIntOrNull() ?: return null
        return if (month == 0) null else YearMonth.of(year, month)
    }

    private fun label(raw: String): String {
        val m = month(raw) ?: return raw.trim().ifEmpty { "Other" }
        return "${MONTHS[m.monthValue - 1].replaceFirstChar { it.uppercase() }} ${m.year}"
    }

    /** Past sessions oldest first; a re-taken course counts only its latest attempt. */
    fun sessions(history: GradeHistory): List<PastSession> {
        val rows = history.courses.mapIndexed { i, c -> Triple(c, month(c.examMonth), i) }
        val latest = HashMap<String, YearMonth>()
        for ((c, m, _) in rows) {
            val code = c.courseCode.trim().uppercase()
            if (code.isEmpty() || m == null) continue
            val seen = latest[code]
            if (seen == null || m.isAfter(seen)) latest[code] = m
        }
        val byLabel = LinkedHashMap<String, MutableList<PastCourse>>()
        val monthOf = HashMap<String, YearMonth?>()
        for ((c, m, i) in rows) {
            val code = c.courseCode.trim().uppercase()
            val l = label(c.examMonth)
            val last = latest[code]
            byLabel.getOrPut(l) { ArrayList() }.add(
                PastCourse(
                    "$code|${c.examMonth.trim()}|$i", code, c.courseTitle.trim(),
                    SemesterCourses.creditsOf(c.credits) ?: 0.0, c.grade.trim().uppercase(), PlannerGrade.of(c.grade),
                    last != null && m != null && m.isBefore(last),
                )
            )
            monthOf[l] = m
        }
        return byLabel.map { PastSession(it.key, monthOf[it.key], it.value) }.sortedWith { a, b ->
            val x = a.month; val y = b.month
            when {
                x != null && y != null -> x.compareTo(y)
                x != null -> -1
                y != null -> 1
                else -> a.label.compareTo(b.label)
            }
        }
    }

    private fun tried(c: PastCourse, picks: Map<String, PlannerGrade>) = if (c.canChange) picks[c.key] ?: c.gradeValue!! else c.gradeValue!!

    fun session(s: PastSession, picks: Map<String, PlannerGrade>): SessionOutcome {
        var credits = 0.0; var now = 0.0; var after = 0.0
        for (c in s.courses) {
            if (!c.hasPoints) continue
            credits += c.credits
            now += c.credits * c.gradeValue!!.points
            after += c.credits * tried(c, picks).points
        }
        return if (credits == 0.0) SessionOutcome(0.0, null, null) else SessionOutcome(credits, now / credits, after / credits)
    }

    /** Anchored to VTOP's own CGPA: new = VTOP CGPA + the change worked out from the list. */
    fun cgpa(vtopCgpa: Double, sessions: List<PastSession>, picks: Map<String, PlannerGrade>): CgpaOutcome {
        var credits = 0.0; var now = 0.0; var after = 0.0
        for (s in sessions) for (c in s.courses) {
            if (!c.hasPoints || c.superseded) continue
            credits += c.credits
            now += c.credits * c.gradeValue!!.points
            after += c.credits * tried(c, picks).points
        }
        if (credits == 0.0) return CgpaOutcome(vtopCgpa, vtopCgpa, false)
        val rebuiltNow = now / credits
        val rebuiltAfter = after / credits
        return CgpaOutcome(vtopCgpa, (vtopCgpa + (rebuiltAfter - rebuiltNow)).coerceIn(0.0, 10.0), Math.abs(rebuiltNow - vtopCgpa) <= 0.015)
    }
}
