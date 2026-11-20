package com.minimallabs.vsync.feature.calendar

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.minimallabs.vsync.core.App
import com.minimallabs.vsync.core.Semesters
import com.minimallabs.vsync.core.UserMessage
import com.minimallabs.vsync.data.AcademicCalendar
import com.minimallabs.vsync.data.AppJson
import com.minimallabs.vsync.data.GeneralOuting
import com.minimallabs.vsync.data.RawCalendarDay
import com.minimallabs.vsync.data.RawCalendarEvent
import com.minimallabs.vsync.data.Timetable
import com.minimallabs.vsync.feature.home.Milestone
import com.minimallabs.vsync.feature.home.Milestones
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.time.DayOfWeek
import java.time.LocalDate

enum class CalendarDayKind { Instructional, Holiday, NoClasses, Exam, Countdown, Event, Outing }

data class CalendarChip(
    val date: LocalDate,
    val kind: CalendarDayKind,
    val title: String,
    val subtitle: String? = null,
    val detail: String? = null,
) {
    val precedence: Int
        get() = when (kind) {
            CalendarDayKind.Countdown -> 0
            CalendarDayKind.Outing, CalendarDayKind.Instructional -> 1
            CalendarDayKind.Event -> 2
            CalendarDayKind.Exam -> 3
            CalendarDayKind.Holiday -> 4
            CalendarDayKind.NoClasses -> 5
        }
}

class CalendarMonth(val year: Int, val month: Int, val chips: List<CalendarChip>) {
    val firstDay: LocalDate get() = LocalDate.of(year, month, 1)
    val label: String get() = "${MONTH_NAMES[month - 1]} $year"
    fun chipsOn(date: LocalDate) = chips.filter { it.date == date }
}

val MONTH_NAMES = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

/** Maps VTOP's free text to a day kind. */
object CalendarEventClassifier {
    private fun normalise(v: String) = v.lowercase().replace(Regex("[^a-z0-9 ]"), " ").trim()

    fun classify(event: RawCalendarEvent): CalendarDayKind {
        val text = normalise("${event.label} ${event.description}")
        if (text.contains("no instructional") || text.contains("no class") || text.contains("non instructional")) return CalendarDayKind.NoClasses
        if (text.contains("holiday") || text.contains("deepavali") || text.contains("christmas") ||
            text.contains("independence") || text.contains("vacation")
        ) return CalendarDayKind.Holiday
        if (Regex("\\b(cat|fat)\\b").containsMatchIn(text) || text.contains("exam")) return CalendarDayKind.Exam
        if (text.contains("working") || text.contains("instructional")) return CalendarDayKind.Instructional
        return CalendarDayKind.Event
    }

    fun shortLabel(event: RawCalendarEvent): String {
        val label = event.label.trim()
        if (label.isNotEmpty()) return label
        val head = event.description.trim().split(Regex("\\s[-–]\\s")).first().trim()
        return head.ifEmpty { "Event" }
    }

    fun isMarked(kind: CalendarDayKind?) = kind != null && kind != CalendarDayKind.Instructional

    /** The kind that colours the day's ring. */
    fun ringKindFor(chips: List<CalendarChip>): CalendarDayKind? {
        if (chips.any { it.kind == CalendarDayKind.Countdown }) return CalendarDayKind.Countdown
        var best: CalendarChip? = null
        for (chip in chips) {
            if (isOrdinarySunday(chip) || !isMarked(chip.kind)) continue
            if (best == null || chip.precedence > best.precedence) best = chip
        }
        return best?.kind
    }

    /** VTOP marks every Sunday "Holiday": filler, not a real closure. */
    fun isOrdinarySunday(chip: CalendarChip) =
        chip.kind == CalendarDayKind.Holiday && chip.date.dayOfWeek == DayOfWeek.SUNDAY && chip.title.trim().lowercase() == "holiday"

    fun chipsFor(day: RawCalendarDay): List<CalendarChip> {
        val date = runCatching { LocalDate.parse(day.date.take(10)) }.getOrNull() ?: return emptyList()
        return day.events.map { CalendarChip(date, classify(it), shortLabel(it), detail = it.description.trim()) }
    }
}

/** Port of `AttendanceProjection`. */
object AttendanceProjection {
    fun classesLeftUntil(courseCode: String, timetable: Timetable, months: List<CalendarMonth>, today: LocalDate, until: LocalDate?): Int {
        if (until == null) return 0
        val perWeekday = HashMap<Int, Int>()
        for (i in 1..7) {
            val count = timetable.forWeekday(i).count { it.courseCode.trim() == courseCode.trim() }
            if (count > 0) perWeekday[i] = count
        }
        if (perWeekday.isEmpty()) return 0
        var total = 0
        var day = today
        while (day.isBefore(until)) {
            val count = perWeekday[day.dayOfWeek.value]
            if (count != null && holdsClasses(months, day)) total += count
            day = day.plusDays(1)
        }
        return total
    }

    private fun holdsClasses(months: List<CalendarMonth>, day: LocalDate): Boolean {
        var best: CalendarChip? = null
        for (m in months) {
            if (m.year != day.year || m.month != day.monthValue) continue
            for (chip in m.chips) {
                if (chip.date.dayOfMonth != day.dayOfMonth) continue
                if (best == null || chip.precedence > best.precedence) best = chip
            }
        }
        return when (best?.kind) {
            CalendarDayKind.NoClasses, CalendarDayKind.Holiday -> false
            else -> true
        }
    }

    /** Holidays + no-instructional days (minus ordinary Sundays): date -> reason. */
    fun closedDays(months: List<CalendarMonth>): Map<LocalDate, String> {
        val strongest = LinkedHashMap<LocalDate, CalendarChip>()
        for (m in months) for (chip in m.chips) {
            if (chip.kind != CalendarDayKind.Holiday && chip.kind != CalendarDayKind.NoClasses) continue
            if (CalendarEventClassifier.isOrdinarySunday(chip)) continue
            val existing = strongest[chip.date]
            if (existing == null || chip.precedence > existing.precedence) strongest[chip.date] = chip
        }
        return strongest.mapValues { closureReason(it.value) }
    }

    private fun closureReason(chip: CalendarChip): String {
        val title = chip.title.trim()
        val detail = (chip.detail ?: "").trim()
        if (detail.isNotEmpty() && detail.lowercase() != title.lowercase()) return detail
        return title.ifEmpty { "No classes" }
    }

    /** First THEORY FAT day on/after [from]. Lab FAT days are teaching days. */
    fun fatStart(months: List<CalendarMonth>, from: LocalDate): LocalDate? = firstExam(months, from) { Regex("\\bfat\\b").containsMatchIn(text(it)) }

    fun examStart(months: List<CalendarMonth>, from: LocalDate): LocalDate? = firstExam(months, from) { true }

    private fun firstExam(months: List<CalendarMonth>, from: LocalDate, extra: (CalendarChip) -> Boolean): LocalDate? {
        var best: LocalDate? = null
        for (m in months) for (chip in m.chips) {
            if (chip.kind != CalendarDayKind.Exam) continue
            if (chip.date.isBefore(from)) continue
            if (Regex("\\blab\\b").containsMatchIn(chip.title.lowercase())) continue
            if (!extra(chip)) continue
            if (best == null || chip.date.isBefore(best)) best = chip.date
        }
        return best
    }

    private fun text(chip: CalendarChip) = "${chip.title} ${chip.subtitle ?: ""} ${chip.detail ?: ""}".lowercase()
}

/** Where teaching ends: the first theory FAT day, else the last calendar day known. */
object TermEnd {
    class Result(val date: LocalDate, val isFat: Boolean)

    fun of(months: List<CalendarMonth>, today: LocalDate): Result? {
        if (months.isEmpty()) return null
        AttendanceProjection.fatStart(months, today)?.let { return Result(it, true) }
        val last = months.last()
        var lastDay = LocalDate.of(last.year, last.month, 1)
        for (chip in last.chips) if (chip.date.isAfter(lastDay)) lastDay = chip.date
        return Result(lastDay, false)
    }
}

@Serializable
private data class CalendarWrap(val semSubId: String = "", val payload: String = "")

/** Loads, caches (5 days) and decorates the academic calendar. */
object CalendarRepo {
    private const val PREFIX = "calendar:"
    private const val MAX_AGE_MS = 5 * 86_400_000L
    private val MONTH_NUMBERS = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")

    /** Calendar months WITHOUT countdown/outing decorations. */
    var months by mutableStateOf<List<CalendarMonth>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** Saved outings, set by the outing pages so they can show on the calendar. */
    var outings by mutableStateOf<List<GeneralOuting>>(emptyList())

    private var inFlight: Deferred<Unit>? = null
    private var loadedFor: String? = null

    private fun key(semesterId: String?) = if (semesterId.isNullOrEmpty()) "calendar" else "$PREFIX$semesterId"
    private fun semesterId(): String? = Semesters.selected()?.id ?: App.credentials?.semSubId

    fun reset() { months = emptyList(); loadedFor = null; error = null }

    private fun readCache(): Pair<List<CalendarMonth>, Long>? {
        val entry = App.store.cacheGet(key(semesterId())) ?: return null
        val parsed = runCatching {
            toMonths(AppJson.decodeFromString<AcademicCalendar>(AppJson.decodeFromString<CalendarWrap>(entry.first).payload))
        }.getOrDefault(emptyList())
        if (parsed.isEmpty()) return null
        return parsed to entry.second
    }

    /** Paints from the cache at once; only goes to VTOP when the cache is missing or older than 5 days. */
    suspend fun ensureLoaded() {
        val sem = semesterId()
        val switched = loadedFor != sem
        val cached = readCache()
        if (months.isEmpty() || switched) months = cached?.first ?: emptyList()
        loadedFor = sem
        if (cached != null && System.currentTimeMillis() - cached.second < MAX_AGE_MS) return
        refresh()
    }

    suspend fun refresh() {
        val running = inFlight ?: App.scope.async { doRefresh() }.also { d ->
            inFlight = d
            d.invokeOnCompletion { if (inFlight === d) inFlight = null }
        }
        running.await()
    }

    private suspend fun doRefresh() {
        if (months.isEmpty()) readCache()?.let { months = it.first }
        loading = true
        error = null
        try {
            val c = App.credentials ?: throw UserMessage("User not found. Please Logout and Login.")
            val raw = App.vtop { it.text("academic_calendar", c.semSubId, "COMB") }
            val parsed = toMonths(AppJson.decodeFromString<AcademicCalendar>(raw))
            App.store.cachePut(key(c.semSubId), AppJson.encodeToString(CalendarWrap(c.semSubId, raw)))
            loadedFor = c.semSubId
            months = parsed
        } catch (e: Throwable) {
            // A failed refresh never blanks a calendar that is already on screen.
            if (months.isEmpty()) error = com.minimallabs.vsync.core.Failures.messageFor(e, "Failed to fetch academic calendar")
        } finally {
            loading = false
        }
    }

    fun toMonths(calendar: AcademicCalendar): List<CalendarMonth> {
        val byMonth = HashMap<String, MutableList<CalendarChip>>()
        for (day in calendar.days) {
            val chips = CalendarEventClassifier.chipsFor(day)
            val date = chips.firstOrNull()?.date ?: runCatching { LocalDate.parse(day.date.take(10)) }.getOrNull() ?: continue
            byMonth.getOrPut("${date.year}-${date.monthValue}") { ArrayList() }.addAll(chips)
        }
        val out = ArrayList<CalendarMonth>()
        for (ref in calendar.months) {
            val parts = ref.calDate.split('-')
            if (parts.size != 3) continue
            val month = MONTH_NUMBERS.indexOf(parts[1].uppercase()) + 1
            val year = parts[2].toIntOrNull()
            if (month == 0 || year == null) continue
            out.add(CalendarMonth(year, month, byMonth["$year-$month"] ?: emptyList()))
        }
        out.sortBy { it.firstDay }
        return out
    }

    /** Months with the student's countdowns and outings layered on. */
    fun decorated(base: List<CalendarMonth>, milestones: List<Milestone>, outings: List<GeneralOuting>): List<CalendarMonth> =
        base.map { m ->
            val extra = ArrayList<CalendarChip>(m.chips)
            for (ms in milestones) {
                val d = ms.date
                if (d.year == m.year && d.monthValue == m.month) extra.add(CalendarChip(d, CalendarDayKind.Countdown, ms.title, detail = ms.info))
            }
            for (o in outings) {
                val from = parseOutingDate(o.fromDate) ?: continue
                val to = parseOutingDate(o.toDate) ?: continue
                if (to.isBefore(from)) continue
                var day = from
                while (!day.isAfter(to)) {
                    if (day.year == m.year && day.monthValue == m.month) {
                        val label = when {
                            day == from && day == to -> "Day trip"
                            day == from -> "Leaving"
                            day == to -> "Returning"
                            else -> "Outing"
                        }
                        extra.add(CalendarChip(day, CalendarDayKind.Outing, o.placeOfVisit.trim().ifEmpty { "Outing" }, label, o.purposeOfVisit.trim()))
                    }
                    day = day.plusDays(1)
                }
            }
            CalendarMonth(m.year, m.month, extra.sortedWith(compareBy<CalendarChip> { it.date }.thenByDescending { it.precedence }))
        }

    fun decoratedNow(): List<CalendarMonth> =
        if (App.prefs.hideCalendarCountdowns) months else decorated(months, Milestones.list, outings)

    private fun parseOutingDate(raw: String): LocalDate? {
        val p = raw.trim().split('/')
        if (p.size != 3) return null
        return runCatching { LocalDate.of(p[2].toInt(), p[1].toInt(), p[0].toInt()) }.getOrNull()
    }
}
