package com.minimallabs.vsync

import com.minimallabs.vsync.bridge.VtopApi
import com.minimallabs.vsync.bridge.VtopError
import com.minimallabs.vsync.core.VtopSession
import com.minimallabs.vsync.data.AppJson
import com.minimallabs.vsync.data.UserData
import com.minimallabs.vsync.feature.academics.AttendanceStanding
import com.minimallabs.vsync.feature.academics.parseExamDate
import com.minimallabs.vsync.feature.academics.stripFacultyTitle
import com.minimallabs.vsync.feature.academics.vtopPercent
import com.minimallabs.vsync.feature.auth.ImapOtpMailbox
import com.minimallabs.vsync.feature.auth.SemesterGrouping
import com.minimallabs.vsync.feature.auth.VtopOtpEmail
import com.minimallabs.vsync.feature.gpa.GpaPlanner
import com.minimallabs.vsync.feature.gpa.PlannerGrade
import com.minimallabs.vsync.feature.outing.OutingRules
import com.minimallabs.vsync.feature.timetable.FreeTime
import com.minimallabs.vsync.data.Day
import com.minimallabs.vsync.data.SemesterInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicInteger

/** A fake scraper session: counts credentials POSTs, can ask for an OTP or expire. */
private class FakeClient(
    val logins: AtomicInteger,
    val askOtp: Boolean = false,
    val loginDelayMs: Long = 50,
    var expireNext: Int = 0,
) : VtopApi {
    var otpSubmitted: String? = null
    override suspend fun text(method: String, vararg args: String): String = when (method) {
        "login" -> {
            logins.incrementAndGet(); delay(loginDelayMs)
            if (askOtp) throw VtopError("LoginOtpRequired", "", "", "Login OTP Required")
            ""
        }
        "login_otp" -> { otpSubmitted = args[0]; "" }
        "attendance" -> if (expireNext-- > 0) throw VtopError("SessionExpired", "", "", "Session has expired") else "[]"
        else -> ""
    }
    override suspend fun bytes(method: String, vararg args: String) = ByteArray(0)
    override suspend fun upload(method: String, payload: ByteArray, vararg args: String) = ""
}

class SessionTest {
    private fun session(make: () -> FakeClient, now: () -> Long = { System.currentTimeMillis() }) = VtopSession(
        createClient = { _, _, _ -> make() }, now = now, userAgent = { "test-ua" }, isDemo = { false },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default), retryDelayMs = 1,
    )

    @Test fun concurrent_callers_share_one_login() = runBlocking {
        val logins = AtomicInteger()
        val s = session({ FakeClient(logins) })
        val clients = (1..8).map { async(Dispatchers.Default) { s.getClient("REG", "pw") } }.awaitAll()
        assertEquals(1, logins.get())
        clients.forEach { assertSame(clients[0], it) }
    }

    @Test fun otp_pause_is_joined_not_restarted() = runBlocking {
        val logins = AtomicInteger()
        val s = session({ FakeClient(logins, askOtp = true) })
        val first = async(Dispatchers.Default) { s.getClient("REG", "pw") }
        withTimeout(2000) { while (!s.isOtpPending) delay(5) }
        val second = async(Dispatchers.Default) { s.getClient("REG", "pw") }
        delay(100)
        s.submitLoginOtp("123456")
        assertSame(first.await(), second.await())
        assertEquals("only one credentials POST, so only one OTP email", 1, logins.get())
        assertEquals("123456", (first.await() as FakeClient).otpSubmitted)
    }

    @Test fun cancelled_otp_fails_the_waiting_call() = runBlocking {
        val s = session({ FakeClient(AtomicInteger(), askOtp = true) })
        val call = async(Dispatchers.Default) { runCatching { s.getClient("REG", "pw") } }
        withTimeout(2000) { while (!s.isOtpPending) delay(5) }
        s.cancelOtp()
        assertTrue(call.await().isFailure)
        assertTrue(!s.isInitialized)
    }

    @Test fun expired_session_is_relogged_once_and_retried() = runBlocking {
        val logins = AtomicInteger()
        var made = 0
        val s = session({ FakeClient(logins, expireNext = if (made++ == 0) 1 else 0) })
        assertEquals("[]", s.execute("REG", "pw") { it.text("attendance") })
        assertEquals(2, logins.get())
    }

    @Test fun otp_expired_is_not_retried() = runBlocking {
        val logins = AtomicInteger()
        val s = session({ FakeClient(logins) })
        val r = runCatching { s.execute("REG", "pw") { throw VtopError("LoginOtpExpired", "", "", "") } }
        assertTrue(r.isFailure)
        assertEquals("no second credentials POST", 1, logins.get())
    }

    @Test fun renews_only_after_14_minutes_idle() = runBlocking {
        val logins = AtomicInteger()
        var clock = 0L
        val s = session({ FakeClient(logins) }, now = { clock })
        s.getClient("REG", "pw")
        repeat(5) { clock += 10 * 60_000L; s.getClient("REG", "pw") } // in use every 10 min: never renewed
        assertEquals(1, logins.get())
        clock += 14 * 60_000L
        s.getClient("REG", "pw")
        assertEquals(2, logins.get())
    }

    @Test fun auth_failure_is_reported() = runBlocking {
        val s = VtopSession(
            createClient = { _, _, _ -> object : VtopApi by FakeClient(AtomicInteger()) {
                override suspend fun text(method: String, vararg args: String): String = throw VtopError("InvalidCredentials", "", "", "")
            } }, userAgent = { "ua" }, isDemo = { false }, scope = CoroutineScope(Dispatchers.Default),
        )
        val reported = async(Dispatchers.Default) { withTimeout(2000) { s.onAuthFailure.first() } }
        delay(50)
        runCatching { s.getClient("REG", "bad") }
        assertEquals("Invalid username or password.", reported.await())
    }
}

class LogicTest {
    @Test fun otp_code_extraction() {
        assertEquals("482913", VtopOtpEmail.extractCode("<p>Your OTP is <b>482913</b>. Valid for 3 minutes.</p>"))
        assertEquals("482913", VtopOtpEmail.extractCode("Code: 4 8 2 9 1 3"))
        assertNull(VtopOtpEmail.extractCode("Reference 12345678 only"))
        assertTrue(VtopOtpEmail.isFromVtop(" NoReply.SDC@vitap.ac.in "))
    }

    @Test fun imap_internal_date() {
        val ms = ImapOtpMailbox.internalDate("INTERNALDATE \"13-Nov-2026 11:22:05 +0530\"")!!
        assertEquals(java.time.Instant.parse("2026-11-13T05:52:05Z").toEpochMilli(), ms)
    }

    @Test fun vtop_rounds_up() {
        assertEquals(92, vtopPercent(31, 34))
        assertEquals(75, vtopPercent(149, 200)) // 74.5% reads 75 ...
        assertTrue(!AttendanceStanding(149, 200).isSafe) // ... but is still below
    }

    @Test fun lab_advice_in_whole_labs() {
        assertEquals("Can skip 1 lab", AttendanceStanding(17, 20, isLab = true).advice)
        assertEquals("Attend 3 labs", AttendanceStanding(10, 15, isLab = true).advice)
        assertEquals("Don't skip", AttendanceStanding(3, 4).advice)
    }

    @Test fun free_gaps() {
        val day = listOf(Day(startTime = "09:00", endTime = "09:50"), Day(startTime = "10:00", endTime = "10:50"), Day(startTime = "12:00", endTime = "12:50"))
        val gaps = FreeTime.before(day)
        assertNull(gaps[1])
        assertEquals("Free for 1 hr 10 min", gaps[2]!!.label)
    }

    @Test fun semester_grouping() {
        val g = SemesterGrouping.group(listOf(SemesterInfo("a", "Fall Semester 2024-25"), SemesterInfo("b", "Winter Semester 2025-26"), SemesterInfo("c", "Odd")))
        assertEquals(listOf("2025-26", "2024-25", "Other"), g.map { it.label })
        assertEquals("Winter Semester", g[0].entries[0].shortName)
    }

    @Test fun exam_dates_and_titles() {
        assertEquals(LocalDate.of(2026, 12, 5), parseExamDate("05-Dec-2026"))
        assertEquals("Aastha Sathe", stripFacultyTitle("Dr. Aastha Sathe"))
        assertEquals("Prof. Aastha Sathe", stripFacultyTitle("Dr. Prof. Aastha Sathe")) // same as the Flutter app
    }

    @Test fun gpa_goal() {
        val goal = GpaPlanner.goal(8.0, 100.0, 20.0, 8.5)!!
        assertEquals(11.0, goal.required, 1e-9)
        assertTrue(!goal.reachable)
        assertEquals(PlannerGrade.S, goal.suggested)
        assertEquals(PlannerGrade.A, PlannerGrade.atLeast(8.2))
    }

    @Test fun weekend_deadline() {
        val friNight = LocalDateTime.of(2026, 11, 20, 23, 0) // Friday
        assertTrue(!OutingRules.isDeadlinePassed(LocalDate.of(2026, 11, 22), friNight, false))
        assertTrue(OutingRules.isDeadlinePassed(LocalDate.of(2026, 11, 22), friNight.plusHours(2), false))
        assertTrue(!OutingRules.isDeadlinePassed(LocalDate.of(2026, 11, 22), friNight.plusHours(2), true))
        assertTrue(OutingRules.timeAllowed(LocalDateTime.of(2026, 1, 1, 22, 0)))
        assertTrue(!OutingRules.timeAllowed(LocalDateTime.of(2026, 1, 1, 22, 1)))
    }

    /** The bundled demo dataset (same shape the scraper emits) decodes into the app's models. */
    @Test fun demo_dataset_decodes() {
        val f = listOf("src/main/assets/demo/demo_dataset.json", "app/src/main/assets/demo/demo_dataset.json").map(::File).first { it.exists() }
        val root = AppJson.parseToJsonElement(f.readText()) as kotlinx.serialization.json.JsonObject
        val user = AppJson.decodeFromJsonElement(UserData.serializer(), root["user"]!!)
        assertTrue(user.attendance.isNotEmpty())
        assertTrue(!user.timetable.isEmpty)
        assertTrue(user.profile.gradeHistory.courses.isNotEmpty())
    }
}
