package com.minimallabs.vsync.feature.mess

import kotlinx.serialization.Serializable
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.time.LocalDate
import java.time.YearMonth
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

@Serializable
data class MessDayMenu(
    val breakfast: List<String> = emptyList(),
    val lunch: List<String> = emptyList(),
    val snacks: List<String> = emptyList(),
    val dinner: List<String> = emptyList(),
) {
    fun forMeal(index: Int) = when (index) { 0 -> breakfast; 1 -> lunch; 2 -> snacks; else -> dinner }
}

@Serializable
data class MessSheet(val name: String, val days: Map<Int, MessDayMenu>)

/** A month's menu. A workbook can hold several menus ("Veg & Non-Veg", "Special"): the first is [days], the rest [extraSheets]. */
@Serializable
data class MessMenu(
    val monthName: String,
    val month: Int,
    val year: Int,
    val days: Map<Int, MessDayMenu>,
    val sheetName: String = "",
    val extraSheets: List<MessSheet> = emptyList(),
) {
    val sheetNames: List<String> get() = listOf(sheetName.ifEmpty { "Menu" }) + extraSheets.map { it.name }
    fun daysFor(sheet: String?): Map<Int, MessDayMenu> = extraSheets.firstOrNull { it.name == sheet }?.days ?: days
    fun menuFor(day: Int, sheet: String? = null): MessDayMenu? = daysFor(sheet)[day]

    /** At least 10 days, and 70% of them with breakfast, lunch and dinner. */
    val isReadable: Boolean
        get() {
            if (days.size < 10) return false
            val complete = days.values.count { d -> listOf(d.breakfast, d.lunch, d.dinner).count { it.isNotEmpty() } >= 3 }
            return complete.toDouble() / days.size >= 0.7
        }

    fun hasEnded(now: LocalDate) = now.year > year || (now.year == year && now.monthValue > month)
}

class MessFormatException(message: String) : Exception(message)

/**
 * Reads a mess menu `.xlsx` (port of the Flutter MessMenuService). Handles:
 * merged or unmerged date blocks, the weekday pattern (women's hostel), one row
 * per day, days across the top, differently worded meal headings, and typos
 * in the dates ("Tue 10, 25" where 25 is a Wednesday).
 */
object MessReader {
    private class Sheet(val grid: List<List<String>>, val dayBlockRanges: List<Pair<Int, Int>>)
    private class DayInfo(val day: Int, val month: Int?, val year: Int?, val weekday: Int?)
    private class DayRow(val day: Int, val month: Int?, val year: Int?, val weekday: Int?, val meals: Map<String, List<String>>)
    class Block(val weekday: Int?, val numbers: List<Int>)

    private val MONTHS = listOf("january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december")
    private val MONTH_WORDS = MONTHS.map { it.take(3) }
    private val WEEKDAYS = mapOf("mon" to 1, "tue" to 2, "wed" to 3, "thu" to 4, "fri" to 5, "sat" to 6, "sun" to 7)

    // ---------------------------------------------------------------- workbook

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (!e.isDirectory) out[e.name] = zip.readBytes()
                }
            }
        } catch (_: Exception) {
            throw MessFormatException("Not a valid Excel workbook")
        }
        if (out.isEmpty()) throw MessFormatException("Not a valid Excel workbook")
        return out
    }

    private fun xml(bytes: ByteArray): Element {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = true
        return f.newDocumentBuilder().parse(ByteArrayInputStream(bytes)).documentElement
    }

    private fun Element.all(local: String): List<Element> {
        val nodes = getElementsByTagNameNS("*", local)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Element.children(local: String): List<Element> {
        val out = ArrayList<Element>()
        var n = firstChild
        while (n != null) {
            if (n is Element && (n.localName ?: n.nodeName.substringAfter(':')) == local) out.add(n)
            n = n.nextSibling
        }
        return out
    }

    private fun Element.attr(local: String): String? {
        val a = attributes
        for (i in 0 until a.length) {
            val node = a.item(i)
            val name = node.localName ?: node.nodeName.substringAfter(':')
            if (name == local) return node.nodeValue
        }
        return null
    }

    private fun texts(e: Element) = e.all("t").joinToString("") { it.textContent }

    private class Book(val files: Map<String, ByteArray>, val shared: List<String>, val sheets: List<Pair<String, String>>)

    private fun open(bytes: ByteArray): Book {
        val files = unzip(bytes)
        fun find(name: String) = files.entries.firstOrNull { it.key.endsWith(name) }?.value
        val shared = find("xl/sharedStrings.xml")?.let { s -> xml(s).all("si").map { texts(it) } } ?: emptyList()
        val workbook = find("xl/workbook.xml") ?: throw MessFormatException("Not a valid Excel workbook")
        val rels = find("xl/_rels/workbook.xml.rels") ?: throw MessFormatException("Workbook relationships missing")
        val ridToTarget = HashMap<String, String>()
        for (rel in xml(rels).all("Relationship")) {
            val id = rel.attr("Id") ?: continue
            val target = rel.attr("Target") ?: continue
            ridToTarget[id] = if (target.startsWith("/xl/")) target.substring(1) else "xl/" + target.removePrefix("xl/")
        }
        val sheets = xml(workbook).all("sheet").mapNotNull { s ->
            val rid = s.attr("id") ?: return@mapNotNull null
            val target = ridToTarget[rid] ?: return@mapNotNull null
            (s.attr("name") ?: "") to target
        }
        return Book(files, shared, sheets)
    }

    private fun Book.file(target: String) = files.entries.firstOrNull { it.key.endsWith(target) }?.value

    /** Every menu sheet that reads; the "Veg & Non-Veg" one first. */
    fun parseXlsx(bytes: ByteArray, now: LocalDate = LocalDate.now()): MessMenu {
        val book = open(bytes)
        var monthHint = -1
        val monthOf = Regex("month of\\s*[:\\- ]*([a-z]+)")
        for ((name, content) in book.files) {
            if (!name.endsWith(".xml")) continue
            val m = monthOf.find(String(content, Charsets.UTF_8).lowercase()) ?: continue
            val idx = monthIndexFromName(m.groupValues[1])
            if (idx != -1) { monthHint = idx; break }
        }
        val ordered = book.sheets.sortedBy { if (it.first.lowercase().contains("veg & non-veg")) 0 else 1 }
        var lastError: Exception? = null
        val found = ArrayList<Pair<String, MessMenu>>()
        for ((name, target) in ordered) {
            val content = book.file(target) ?: continue
            try {
                val sheet = readSheet(content, book.shared)
                menuFromGrid(sheet.grid, sheet.dayBlockRanges, monthHint, now)?.let { found.add(name.trim() to it) }
            } catch (e: Exception) {
                lastError = e
            }
        }
        if (found.isEmpty()) throw MessFormatException("Could not find a mess menu table in this file. ${lastError?.message ?: ""}".trim())
        return found.first().second.copy(
            sheetName = found.first().first,
            extraSheets = found.drop(1).map { MessSheet(it.first, it.second.days) },
        )
    }

    /** The first sheet as a plain table, for a workbook that does not read as a menu. */
    fun rawGrid(bytes: ByteArray): List<List<String>> {
        val book = open(bytes)
        for ((_, target) in book.sheets) {
            val content = book.file(target) ?: continue
            val grid = readSheet(content, book.shared).grid.filter { row -> row.any { it.isNotEmpty() } }
            if (grid.isEmpty()) continue
            var width = 0
            for (row in grid) for (c in row.indices.reversed()) if (row[c].isNotEmpty()) { if (c + 1 > width) width = c + 1; break }
            return grid.map { row -> (0 until width).map { c -> row.getOrElse(c) { "" } } }
        }
        return emptyList()
    }

    private fun readSheet(content: ByteArray, shared: List<String>): Sheet {
        val doc = xml(content)
        val grid = ArrayList<MutableList<String>>()
        for (row in doc.all("row")) {
            val rowIndex = (row.attr("r")?.toIntOrNull() ?: (grid.size + 1)) - 1
            while (grid.size <= rowIndex) grid.add(ArrayList())
            val cells = grid[rowIndex]
            for (c in row.children("c")) {
                val col = columnIndexFromRef(c.attr("r") ?: "")
                if (col < 0) continue
                val type = c.attr("t")
                var value = ""
                if (type == "inlineStr") {
                    value = texts(c)
                } else {
                    val v = c.children("v").firstOrNull()
                    if (v != null) {
                        value = v.textContent
                        if (type == "s") value = value.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                    }
                }
                while (cells.size <= col) cells.add("")
                cells[col] = value.trim()
            }
        }
        val ranges = ArrayList<Pair<Int, Int>>()
        for (merge in doc.all("mergeCell")) {
            val ref = merge.attr("ref") ?: ""
            if (!ref.uppercase().startsWith("A")) continue
            val parts = ref.split(':')
            if (parts.size != 2) continue
            val r1 = parts[0].replace(Regex("[^0-9]"), "").toIntOrNull()
            val r2 = parts[1].replace(Regex("[^0-9]"), "").toIntOrNull()
            if (r1 != null && r2 != null && r2 > r1) ranges.add(r1 - 1 to r2 - 1)
        }
        ranges.sortBy { it.first }
        return Sheet(grid, ranges)
    }

    private fun columnIndexFromRef(ref: String): Int {
        val letters = ref.replace(Regex("[^A-Za-z]"), "").uppercase()
        var index = 0
        for (ch in letters) index = index * 26 + (ch.code - 64)
        return index - 1
    }

    // ---------------------------------------------------------------- dates

    /** Which year a month belongs to, from today: within six months either way. */
    fun yearFor(month: Int, now: LocalDate): Int = when {
        month - now.monthValue > 6 -> now.year - 1
        now.monthValue - month > 6 -> now.year + 1
        else -> now.year
    }

    fun weekdayOfLabel(label: String): Int? =
        Regex("\\b(mon|tue|wed|thu|fri|sat|sun)", RegexOption.IGNORE_CASE).find(label)?.let { WEEKDAYS[it.groupValues[1].lowercase()] }

    private fun weekday(year: Int, month: Int, day: Int) = LocalDate.of(year, month, day).dayOfWeek.value
    private fun lengthOf(year: Int, month: Int) = YearMonth.of(year, month).lengthOfMonth()

    /** The year (this, last or next) whose calendar best fits the sheet's weekday labels. */
    fun bestYear(month: Int, blocks: List<Block>, preferred: Int): Int {
        var best = preferred
        var bestScore = -1
        for (year in listOf(preferred, preferred - 1, preferred + 1)) {
            val length = lengthOf(year, month)
            var score = 0
            for (b in blocks) {
                val wd = b.weekday ?: continue
                for (n in b.numbers) if (n in 1..length && weekday(year, month, n) == wd) score++
            }
            if (score > bestScore) { best = year; bestScore = score }
        }
        return best
    }

    /** Snaps a typo'd date to the nearest day (within 3) of the block's weekday. */
    fun repairDates(weekday: Int?, numbers: List<Int>, year: Int, month: Int): List<Int> {
        val length = lengthOf(year, month)
        val inMonth = numbers.filter { it in 1..length }
        if (weekday == null) return inMonth
        val onWeekday = (1..length).filter { weekday(year, month, it) == weekday }
        val kept = LinkedHashSet(inMonth.filter { weekday(year, month, it) == weekday })
        for (n in inMonth) {
            if (n in kept || n in onWeekday) continue
            var nearest: Int? = null
            for (c in onWeekday) {
                if (c in kept || c in inMonth) continue
                if (Math.abs(c - n) > 3) continue
                if (nearest == null || Math.abs(c - n) < Math.abs(nearest - n)) nearest = c
            }
            if (nearest != null) kept.add(nearest)
        }
        return kept.sorted()
    }

    private fun monthIndexFromName(name: String): Int {
        val lower = name.lowercase()
        MONTHS.forEachIndexed { i, m -> if (lower.startsWith(m.take(3))) return i + 1 }
        return -1
    }

    private fun capitalised(month: Int) = MONTHS[month - 1].replaceFirstChar { it.uppercase() }

    /** "(week 1,3)" / "(alt)" tags: keep the line only on matching weeks, without the tag. */
    private fun applyWeekTag(line: String, weekOfMonth: Int): String? {
        val tag = Regex("\\((?:week|alt)\\s*[^)]*\\)", RegexOption.IGNORE_CASE).find(line) ?: return line
        val t = tag.value.lowercase()
        val keep = if (t.contains("alt")) weekOfMonth % 2 == 1 else Regex("\\d+").findAll(t).map { it.value.toInt() }.toSet().contains(weekOfMonth)
        if (!keep) return null
        return line.replaceFirst(tag.value, "").replace(Regex("[/+]\\s*$"), "").trim()
    }

    private fun items(cell: String) = cell.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    private fun build(month: Int, year: Int, days: Map<Int, Map<String, List<String>>>) = MessMenu(
        monthName = capitalised(month), month = month, year = year,
        days = days.mapValues { (_, m) -> MessDayMenu(m["breakfast"] ?: emptyList(), m["lunch"] ?: emptyList(), m["snacks"] ?: emptyList(), m["dinner"] ?: emptyList()) },
    )

    // ---------------------------------------------------------------- layouts

    private fun menuFromGrid(grid: List<List<String>>, ranges: List<Pair<Int, Int>>, monthHint: Int, now: LocalDate): MessMenu? {
        if (ranges.isEmpty() && looksPerRow(grid)) menuFromRows(grid, monthHint, now)?.let { return it }
        menuFromBlocks(grid, ranges, monthHint, now)?.let { return it }
        withCanonicalHeader(grid)?.let { c -> menuFromBlocks(c, ranges, monthHint, now)?.let { return it } }
        return menuFromRows(grid, monthHint, now) ?: menuFromColumns(grid, monthHint, now)
    }

    private fun menuFromBlocks(grid: List<List<String>>, ranges: List<Pair<Int, Int>>, monthHint: Int, now: LocalDate): MessMenu? {
        var headerRow = -1
        var breakfastCol = -1; var lunchCol = -1; var snacksCol = -1; var dinnerCol = -1
        for (r in grid.indices) {
            for (c in grid[r].indices) {
                when (grid[r][c].lowercase()) {
                    "breakfast" -> breakfastCol = c
                    "lunch" -> lunchCol = c
                    "snacks" -> snacksCol = c
                    "dinner" -> dinnerCol = c
                }
            }
            if (breakfastCol != -1 && lunchCol != -1) { headerRow = r; break }
        }
        if (headerRow == -1) return null
        val mealCols = listOf(breakfastCol, lunchCol, snacksCol, dinnerCol)

        // Weekday pattern: labels are weekday names, no numbers.
        val weekdayLabels = LinkedHashMap<Int, List<Int>>()
        var weekly = true
        for (r in headerRow + 1 until grid.size) {
            val label = grid[r].getOrElse(0) { "" }
            if (label.isEmpty()) continue
            if (Regex("\\d").containsMatchIn(label)) { weekly = false; break }
            weekdaysFromLabel(label)?.let { weekdayLabels[r] = it }
        }
        if (weekly && weekdayLabels.size >= 2) return menuFromWeeklyPattern(grid, weekdayLabels, monthHint, mealCols, now)

        var month = monthHint
        val everything = grid.flatten().joinToString("\n").lowercase()
        if (month == -1) Regex("month of\\s*[:\\- ]*([a-z]+)").find(everything)?.let { month = monthIndexFromName(it.groupValues[1]) }
        if (month == -1) for (m in 12 downTo 1) if (everything.contains(MONTHS[m - 1])) { month = m; break }
        if (month == -1) month = now.monthValue
        var year = yearFor(month, now)

        val days = LinkedHashMap<Int, MutableMap<String, MutableList<String>>>()
        fun weekOfMonth(day: Int) = (day - 1) / 7 + 1
        fun addItems(targetDays: List<Int>, r: Int) {
            val row = grid[r]
            for (col in mealCols) {
                val value = if (col >= 0) row.getOrElse(col) { "" } else ""
                if (value.isEmpty()) continue
                val key = when (col) { lunchCol -> "lunch"; snacksCol -> "snacks"; dinnerCol -> "dinner"; else -> "breakfast" }
                for (day in targetDays) {
                    val resolved = applyWeekTag(value, weekOfMonth(day))
                    if (resolved.isNullOrEmpty()) continue
                    days.getOrPut(day) { LinkedHashMap() }.getOrPut(key) { ArrayList() }.add(resolved)
                }
            }
        }
        val labelWidth = if (breakfastCol > 0) breakfastCol else 1

        if (ranges.isNotEmpty()) {
            class B(val start: Int, val end: Int, val weekday: Int?, val numbers: List<Int>)
            val blocks = ArrayList<B>()
            for ((start, end) in ranges) {
                if (end <= headerRow) continue
                val label = StringBuilder()
                for (r in start..end) {
                    if (r >= grid.size) break
                    for (c in 0 until labelWidth) grid[r].getOrNull(c)?.let { label.append(it).append(' ') }
                }
                val numbers = Regex("\\d+").findAll(label).map { it.value.toInt() }.filter { it in 1..31 }.toList()
                if (numbers.isEmpty()) continue
                blocks.add(B(start, end, weekdayOfLabel(label.toString()), numbers))
            }
            year = bestYear(month, blocks.map { Block(it.weekday, it.numbers) }, year)
            for (b in blocks) {
                val dates = repairDates(b.weekday, b.numbers, year, month)
                if (dates.isEmpty()) continue
                for (r in b.start..b.end) {
                    if (r >= grid.size) break
                    if (r <= headerRow) continue
                    addItems(dates, r)
                }
            }
        } else {
            var currentDays = emptyList<Int>()
            var prevHadItems = false
            fun hasItems(r: Int) = mealCols.any { c -> c >= 0 && grid[r].getOrElse(c) { "" }.isNotEmpty() }
            for (r in headerRow + 1 until grid.size) {
                if (!hasItems(r)) { prevHadItems = false; continue }
                if (!prevHadItems) {
                    var groupEnd = r
                    while (groupEnd + 1 < grid.size && hasItems(groupEnd + 1)) groupEnd++
                    val label = StringBuilder()
                    for (lr in r..minOf(r + 3, groupEnd)) for (c in 0 until labelWidth) grid[lr].getOrNull(c)?.let { label.append(it).append(' ') }
                    currentDays = Regex("\\d+").findAll(label).map { it.value.toInt() }.filter { it in 1..31 }.toList()
                }
                addItems(currentDays, r)
                prevHadItems = true
            }
        }
        if (days.isEmpty()) return null
        return build(month, year, days)
    }

    private fun weekdaysFromLabel(label: String): List<Int>? {
        val lower = label.lowercase()
        val result = WEEKDAYS.filter { lower.contains(it.key) }.values.toSet()
        return if (result.isEmpty()) null else result.toList()
    }

    private fun menuFromWeeklyPattern(grid: List<List<String>>, labels: Map<Int, List<Int>>, monthHint: Int, mealCols: List<Int>, now: LocalDate): MessMenu {
        val month = if (monthHint == -1) now.monthValue else monthHint
        val year = yearFor(month, now)
        val length = lengthOf(year, month)
        val days = LinkedHashMap<Int, MutableMap<String, MutableList<String>>>()
        for ((r, weekdays) in labels) {
            val row = grid.getOrElse(r) { emptyList() }
            for (col in mealCols) {
                val cell = if (col >= 0) row.getOrElse(col) { "" } else ""
                val lines = items(cell)
                if (lines.isEmpty()) continue
                val key = when (mealCols.indexOf(col)) { 0 -> "breakfast"; 1 -> "lunch"; 2 -> "snacks"; else -> "dinner" }
                for (day in 1..length) {
                    if (weekday(year, month, day) !in weekdays) continue
                    for (line in lines) {
                        val resolved = applyWeekTag(line, (day - 1) / 7 + 1)
                        if (resolved.isNullOrEmpty()) continue
                        days.getOrPut(day) { LinkedHashMap() }.getOrPut(key) { ArrayList() }.add(resolved)
                    }
                }
            }
        }
        return build(month, year, days)
    }

    /** "Evening Snacks", "Dinner / Supper" -> the canonical meal key. */
    fun mealOf(cell: String): String? {
        if (cell.length > 40) return null
        val t = cell.lowercase().replace(Regex("[^a-z]"), "")
        if (t.isEmpty()) return null
        return when {
            t.startsWith("breakfast") || t.startsWith("brkfast") -> "breakfast"
            t.startsWith("lunch") -> "lunch"
            t.contains("snack") || t.startsWith("tea") || t.startsWith("eveningtea") -> "snacks"
            t.startsWith("dinner") || t.startsWith("supper") -> "dinner"
            else -> null
        }
    }

    private class Header(val row: Int, val cols: Map<String, Int>)

    private fun locateMealHeader(grid: List<List<String>>): Header? {
        for (r in grid.indices) {
            val cols = LinkedHashMap<String, Int>()
            for (c in grid[r].indices) mealOf(grid[r][c])?.let { cols.putIfAbsent(it, c) }
            if (cols.containsKey("breakfast") && cols.containsKey("lunch") && cols.containsKey("dinner")) return Header(r, cols)
        }
        return null
    }

    private fun withCanonicalHeader(grid: List<List<String>>): List<List<String>>? {
        val header = locateMealHeader(grid) ?: return null
        val names = mapOf("breakfast" to "Breakfast", "lunch" to "Lunch", "snacks" to "Snacks", "dinner" to "Dinner")
        val copy = grid.map { it.toMutableList() }
        for ((meal, col) in header.cols) copy[header.row][col] = names[meal]!!
        return copy
    }

    /** One cell as a date: Excel serial, dd-mm-yyyy, "15 Nov", "Nov 15", "Mon 3", "3". */
    private fun dayOf(raw: String): DayInfo? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        text.toDoubleOrNull()?.let { serial ->
            if (serial > 30000 && serial < 80000) {
                val d = LocalDate.of(1899, 12, 30).plusDays(Math.floor(serial).toLong())
                return DayInfo(d.dayOfMonth, d.monthValue, d.year, d.dayOfWeek.value)
            }
        }
        Regex("(\\d{1,2})\\s*[-/.]\\s*(\\d{1,2})\\s*[-/.]\\s*(\\d{2,4})").find(text)?.let { m ->
            val day = m.groupValues[1].toInt(); val month = m.groupValues[2].toInt()
            var year = m.groupValues[3].toInt(); if (year < 100) year += 2000
            if (day in 1..31 && month in 1..12) return DayInfo(day, month, year, null)
        }
        val lower = text.lowercase()
        Regex("(\\d{1,2})(?:st|nd|rd|th)?\\s*[-/ ]?\\s*(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.?(?:[\\s,-]*(\\d{4}))?").find(lower)?.let { m ->
            val day = m.groupValues[1].toInt()
            if (day in 1..31) return DayInfo(day, MONTH_WORDS.indexOf(m.groupValues[2]) + 1, m.groupValues[3].toIntOrNull(), weekdayOfLabel(text))
        }
        Regex("(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.?\\s*(\\d{1,2})(?:st|nd|rd|th)?\\b").find(lower)?.let { m ->
            val day = m.groupValues[2].toInt()
            if (day in 1..31) return DayInfo(day, MONTH_WORDS.indexOf(m.groupValues[1]) + 1, null, weekdayOfLabel(text))
        }
        val numbers = Regex("\\d+").findAll(text).toList()
        if (numbers.size == 1) {
            val day = numbers[0].value.toInt()
            val rest = text.replaceFirst(numbers[0].value, "").trim()
            val onlyWeekday = rest.isEmpty() ||
                (Regex("^[a-zA-Z]{2,9}\\.?[\\s,.:-]*$").matches(rest) && weekdayOfLabel(rest) != null) ||
                Regex("^(st|nd|rd|th)$", RegexOption.IGNORE_CASE).matches(rest)
            if (day in 1..31 && onlyWeekday) return DayInfo(day, null, null, weekdayOfLabel(text))
        }
        return null
    }

    private fun looksPerRow(grid: List<List<String>>): Boolean {
        val header = locateMealHeader(grid) ?: return false
        val firstMeal = header.cols.values.min()
        if (firstMeal == 0) return false
        var withFood = 0; var withDay = 0
        for (r in header.row + 1 until grid.size) {
            val row = grid[r]
            if (header.cols.values.none { c -> row.getOrElse(c) { "" }.isNotBlank() }) continue
            withFood++
            if (dayOf((0 until minOf(firstMeal, row.size)).joinToString(" ") { row[it] }) != null) withDay++
        }
        return withDay >= 5 && withDay.toDouble() / withFood >= 0.8
    }

    private fun menuFromRows(grid: List<List<String>>, monthHint: Int, now: LocalDate): MessMenu? {
        val header = locateMealHeader(grid) ?: return null
        val firstMeal = header.cols.values.min()
        if (firstMeal == 0) return null
        val rows = ArrayList<DayRow>()
        var withFood = 0
        for (r in header.row + 1 until grid.size) {
            val row = grid[r]
            if (header.cols.values.any { c -> row.getOrElse(c) { "" }.isNotBlank() }) withFood++
            val info = dayOf((0 until minOf(firstMeal, row.size)).joinToString(" ") { row[it] }) ?: continue
            val meals = LinkedHashMap<String, List<String>>()
            for ((meal, c) in header.cols) items(row.getOrElse(c) { "" }).takeIf { it.isNotEmpty() }?.let { meals[meal] = it }
            if (meals.isEmpty()) continue
            rows.add(DayRow(info.day, info.month, info.year, info.weekday, meals))
        }
        if (rows.size < 5 || rows.size.toDouble() / withFood < 0.8) return null
        return assemble(rows, monthHint, now)
    }

    private fun bareWeekday(label: String): Int? {
        val text = label.trim()
        if (text.isEmpty() || text.length > 12 || Regex("\\d").containsMatchIn(text)) return null
        return weekdayOfLabel(text)
    }

    private fun menuFromColumns(grid: List<List<String>>, monthHint: Int, now: LocalDate): MessMenu? {
        val mealRows = LinkedHashMap<Int, String>()
        var labelCol = -1
        for (r in grid.indices) for (c in 0 until minOf(3, grid[r].size)) {
            val meal = mealOf(grid[r][c]) ?: continue
            if (labelCol == -1) labelCol = c
            if (c == labelCol) mealRows.putIfAbsent(r, meal)
        }
        if (labelCol == -1 || mealRows.size < 3) return null
        if (!mealRows.values.toSet().containsAll(listOf("breakfast", "lunch", "dinner"))) return null
        val firstMealRow = mealRows.keys.min()
        var headerRow: Int? = null
        var best = 0
        var r = firstMealRow - 1
        while (r >= 0 && r >= firstMealRow - 4) {
            var score = 0
            for (c in labelCol + 1 until grid[r].size) if (dayOf(grid[r][c]) != null || bareWeekday(grid[r][c]) != null) score++
            if (score > best) { best = score; headerRow = r }
            r--
        }
        if (headerRow == null || best < 5) return null
        val sortedMealRows = mealRows.keys.sorted()
        fun mealForRow(row: Int): String? = sortedMealRows.lastOrNull { it <= row }?.let { mealRows[it] }
        val columns = ArrayList<DayRow>()
        val weekly = ArrayList<Pair<Int, Map<String, List<String>>>>()
        for (c in labelCol + 1 until grid[headerRow].size) {
            val label = grid[headerRow][c]
            val info = dayOf(label)
            val wd = if (info == null) bareWeekday(label) else null
            if (info == null && wd == null) continue
            val dayMeals = LinkedHashMap<String, MutableList<String>>()
            for (row in firstMealRow until grid.size) {
                val meal = mealForRow(row) ?: continue
                if (c >= grid[row].size) continue
                dayMeals.getOrPut(meal) { ArrayList() }.addAll(items(grid[row][c]))
            }
            dayMeals.entries.removeAll { it.value.isEmpty() }
            if (dayMeals.isEmpty()) continue
            if (info != null) columns.add(DayRow(info.day, info.month, info.year, info.weekday, dayMeals)) else weekly.add(wd!! to dayMeals)
        }
        if (columns.size >= 5) return assemble(columns, monthHint, now)
        if (weekly.size >= 5) {
            val month = if (monthHint == -1) now.monthValue else monthHint
            val year = yearFor(month, now)
            val all = ArrayList<DayRow>()
            for (d in 1..lengthOf(year, month)) for ((wd, meals) in weekly) if (weekday(year, month, d) == wd) all.add(DayRow(d, month, year, wd, meals))
            return assemble(all, month, now)
        }
        return null
    }

    private fun assemble(days: List<DayRow>, monthHint: Int, now: LocalDate): MessMenu? {
        if (days.isEmpty()) return null
        fun mostCommon(values: List<Int?>): Int? = values.filterNotNull().groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        val month = mostCommon(days.map { it.month }) ?: if (monthHint != -1) monthHint else now.monthValue
        var year = mostCommon(days.map { it.year }) ?: yearFor(month, now)
        year = bestYear(month, days.filter { it.weekday != null }.map { Block(it.weekday, listOf(it.day)) }, year)
        val length = lengthOf(year, month)
        val menu = LinkedHashMap<Int, MutableMap<String, MutableList<String>>>()
        for (d in days) {
            if (d.month != null && d.month != month) continue
            if (d.day !in 1..length) continue
            val target = menu.getOrPut(d.day) { LinkedHashMap() }
            for ((meal, list) in d.meals) {
                target.getOrPut(meal) { ArrayList() }.addAll(list.mapNotNull { applyWeekTag(it, (d.day - 1) / 7 + 1) }.filter { it.isNotEmpty() })
            }
        }
        if (menu.isEmpty()) return null
        return build(month, year, menu)
    }
}
