package com.minimallabs.vsync

import com.minimallabs.vsync.data.Attendance
import com.minimallabs.vsync.data.Day
import com.minimallabs.vsync.data.Timetable
import com.minimallabs.vsync.feature.skip.SkipPlan
import com.minimallabs.vsync.feature.skip.SkipPlanRow
import com.minimallabs.vsync.feature.skip.SkipPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/** Ported one-for-one from test/features/skip_planner/skip_planner_test.dart (Flutter app). */
class SkipPlannerTest {
    private fun slot(code: String, start: String, end: String, type: String = "Theory", slot: String = "A1") =
        Day(courseCode = code, courseName = code, startTime = start, endTime = end, slot = slot, courseType = type)

    private fun att(code: String, a: String, t: String, type: String = "Theory", typeCode: String = "ETH") = Attendance(
        classNumber = "1", faculty = "Someone", courseId = code, courseCode = code, courseName = code, courseType = type,
        courseTypeCode = typeCode, courseSlot = "MON", attendedClasses = a, totalClasses = t, attendancePercentage = "75",
    )

    private fun week(mon: List<Day> = emptyList(), wed: List<Day> = emptyList(), thu: List<Day> = emptyList(), fri: List<Day> = emptyList()) =
        Timetable(monday = mon, wednesday = wed, thursday = thu, friday = fri)

    private fun timetable() = week(
        mon = listOf(slot("MAT1007", "09:00", "09:50")),
        wed = listOf(slot("MAT1007", "11:00", "11:50")),
        thu = listOf(slot("SWE5002", "14:00", "15:40", "Lab", "L31+L32")),
        fri = listOf(slot("MAT1007", "09:00", "09:50"), slot("MGT1040", "14:00", "14:50")),
    )

    private val wedD = LocalDate.of(2026, 10, 21)
    private val friD = LocalDate.of(2026, 10, 23)
    private val monD = LocalDate.of(2026, 10, 26)
    private fun d(y: Int, m: Int, day: Int, h: Int = 0, min: Int = 0) = LocalDateTime.of(y, m, day, h, min)

    private fun plan(
        t: Timetable, a: List<Attendance>, leave: LocalDateTime, back: LocalDateTime,
        now: LocalDateTime = d(2026, 10, 20), until: LocalDate? = null, closed: Map<LocalDate, String> = emptyMap(),
    ) = SkipPlanner.compute(t, a, leave, back, now, until, closed)

    private fun SkipPlan.row(code: String): SkipPlanRow = rows.first { it.courseCode == code }

    // ---- the trip from the bug report ----
    private fun bugWeek() = week(
        mon = listOf(slot("A", "08:00", "08:50"), slot("B", "09:00", "09:50")),
        wed = listOf(slot("A", "08:00", "08:50"), slot("B", "09:00", "09:50"), slot("C", "10:00", "10:50")),
        fri = listOf(slot("A", "08:00", "08:50"), slot("B", "09:00", "09:50"), slot("C", "10:00", "10:50"), slot("D", "11:00", "11:50"), slot("E", "14:00", "14:50")),
    )
    private val bugAtt = listOf("A", "B", "C", "D", "E").map { att(it, "40", "50") }
    private val bugMonday = LocalDate.of(2026, 10, 26)

    @Test fun friday_partial_monday_closed_back_wednesday() {
        val p = plan(bugWeek(), bugAtt, d(2026, 10, 23, 10), d(2026, 10, 28, 9), d(2026, 10, 22, 12), closed = mapOf(bugMonday to "No Instructional Day"))
        assertEquals(1, p.row("C").missed); assertEquals(1, p.row("D").missed); assertEquals(1, p.row("E").missed); assertEquals(1, p.row("A").missed)
        assertFalse(p.rows.any { it.courseCode == "B" })
        assertEquals(4, p.totalMissed)
        assertEquals(LocalDate.of(2026, 10, 28), p.row("A").days.single().date)
    }

    @Test fun monday_closure_is_classes_saved() {
        val p = plan(bugWeek(), bugAtt, d(2026, 10, 23, 10), d(2026, 10, 28, 9), d(2026, 10, 22, 12), closed = mapOf(bugMonday to "No Instructional Day"))
        assertEquals(1, p.closures.size)
        assertEquals(bugMonday, p.closures.single().date)
        assertEquals("No Instructional Day", p.closures.single().reason)
        assertEquals(2, p.closures.single().classesSaved)
        assertEquals(2, p.classesSaved)
    }

    @Test fun without_calendar_monday_is_missed() {
        val p = plan(bugWeek(), bugAtt, d(2026, 10, 23, 10), d(2026, 10, 28, 9), d(2026, 10, 22, 12))
        assertEquals(6, p.totalMissed)
        assertTrue(p.closures.isEmpty())
    }

    @Test fun closed_day_costs_nothing_outside_window() {
        val p = plan(bugWeek(), bugAtt, friD.atStartOfDay(), d(2026, 10, 24), d(2026, 10, 19, 7), LocalDate.of(2026, 11, 6), mapOf(bugMonday to "No Instructional Day"))
        assertEquals(4, p.row("A").remainingAfter)
    }

    // ---- the requested model ----
    @Test fun full_day_two_theory_one_lab_is_four_periods() {
        val t = week(thu = listOf(slot("SWE5002", "14:00", "15:40", "Lab", "L31+L32")), fri = listOf(slot("MAT1007", "09:00", "09:50"), slot("MGT1040", "14:00", "14:50")))
        val p = plan(t, listOf(att("SWE5002", "9", "12"), att("MAT1007", "80", "100"), att("MGT1040", "70", "100")), d(2026, 10, 22), d(2026, 10, 25))
        assertEquals(2, p.row("SWE5002").missed); assertEquals(1, p.row("MAT1007").missed); assertEquals(1, p.row("MGT1040").missed)
        assertEquals(4, p.totalMissed)
    }

    @Test fun leaving_at_1230_keeps_morning() {
        val p = plan(timetable(), listOf(att("MAT1007", "80", "100"), att("MGT1040", "80", "100")), d(2026, 10, 23, 12, 30), monD.atStartOfDay())
        assertEquals(1, p.totalMissed); assertEquals(1, p.row("MGT1040").missed); assertEquals(0, p.row("MGT1040").let { if (it.isLab) it.missed else 0 })
    }

    @Test fun holiday_in_window_not_counted() {
        val p = plan(timetable(), listOf(att("MAT1007", "80", "100"), att("MGT1040", "80", "100")), d(2026, 10, 22), d(2026, 10, 25), closed = mapOf(friD to "Deepavali"))
        assertFalse(p.rows.any { it.courseCode == "MGT1040" })
        assertEquals(2, p.row("SWE5002").missed)
        assertEquals("Deepavali", p.closures.single().reason)
        assertEquals(2, p.closures.single().classesSaved)
    }

    @Test fun begun_classes_not_counted() {
        val p = plan(timetable(), listOf(att("MAT1007", "80", "100"), att("MGT1040", "80", "100")), friD.atStartOfDay(), d(2026, 10, 26), d(2026, 10, 22, 15, 45))
        assertEquals(2, p.totalMissed)
    }

    @Test fun multi_slot_theory_is_one_class() {
        val p = plan(week(mon = listOf(slot("MAT1007", "09:00", "09:50", slot = "A1+A2"))), listOf(att("MAT1007", "80", "100")), d(2026, 10, 19), d(2026, 10, 20), d(2026, 10, 18))
        assertFalse(p.isEmpty); assertEquals(1, p.row("MAT1007").missed)
    }

    @Test fun real_friday_two_labs_four_theory() {
        val t = week(fri = listOf(
            slot("SWE2007", "08:00", "09:40", "ELA", "L1+L2"), slot("SWE2001", "09:50", "11:30", "ELA", "L3+L4"),
            slot("SWE2001", "14:00", "14:50", "ETH", "A1+TA1"), slot("SWE1002", "15:00", "15:50", "ETH", "C1+TC1+TCC1"),
            slot("MAT1007", "16:00", "16:50", "ETH", "D1+TD1"), slot("STS2010", "17:00", "17:50", "ETH", "E1+TE1+TEE1"),
        ))
        val a = listOf(att("SWE2007", "5", "10", "Embedded Lab", "ELA"), att("SWE2001", "5", "10", "Embedded Lab", "ELA"),
            att("SWE2001", "5", "10"), att("SWE1002", "5", "10"), att("MAT1007", "5", "10"), att("STS2010", "5", "10"))
        val p = plan(t, a, d(2026, 10, 23), d(2026, 10, 24), d(2026, 10, 22))
        assertEquals(4, p.missedLab); assertEquals(4, p.missedTheory); assertEquals(1, p.row("SWE1002").missed)
    }

    @Test fun hand_computed_example() {
        val t = week(fri = listOf(slot("MAT1007", "09:00", "09:50"), slot("MAT1007", "10:00", "10:50", slot = "A2"),
            slot("MAT1007", "11:00", "11:50", slot = "A3"), slot("MAT1007", "14:00", "14:50", slot = "B1")))
        val m = plan(t, listOf(att("MAT1007", "29", "34")), friD.atStartOfDay(), monD.atStartOfDay(), d(2026, 10, 22)).row("MAT1007")
        assertEquals(4, m.missed); assertEquals(0, m.futureBefore); assertEquals(0, m.remainingAfter)
        assertEquals(76.315, m.afterLeave, 0.01); assertEquals(76.315, m.atEndOfTerm, 0.01)
    }

    @Test fun same_day_window() {
        val p = plan(timetable(), listOf(att("MAT1007", "80", "100"), att("MGT1040", "80", "100")), d(2026, 10, 23, 14), d(2026, 10, 23, 23, 59))
        assertEquals(1, p.totalMissed); assertEquals(1, p.row("MGT1040").missed)
    }

    // ---- F, M and R ----
    @Test fun f_counts_before_leave() {
        val m = plan(timetable(), listOf(att("MAT1007", "80", "100")), wedD.atStartOfDay(), monD.atStartOfDay(), d(2026, 10, 18, 8)).row("MAT1007")
        assertTrue(m.futureBefore > 0); assertEquals(2, m.missed)
    }

    @Test fun r_counts_after_return_to_fat() {
        val m = plan(timetable(), listOf(att("MAT1007", "80", "100")), friD.atStartOfDay(), monD.atStartOfDay(), d(2026, 10, 22), LocalDate.of(2026, 11, 6)).row("MAT1007")
        assertEquals(1, m.missed); assertTrue(m.remainingAfter > 0)
    }

    @Test fun fat_day_is_not_a_class() {
        val t = week(wed = listOf(slot("MAT1007", "09:00", "09:50")), fri = listOf(slot("MAT1007", "09:00", "09:50")))
        val a = listOf(att("MAT1007", "80", "100"))
        assertEquals(0, plan(t, a, wedD.atStartOfDay(), d(2026, 10, 22), d(2026, 10, 20), friD).row("MAT1007").remainingAfter)
        assertEquals(1, plan(t, a, wedD.atStartOfDay(), d(2026, 10, 22), d(2026, 10, 20), LocalDate.of(2026, 10, 24)).row("MAT1007").remainingAfter)
    }
}
