package com.minimallabs.vsync

import com.minimallabs.vsync.feature.mess.MessReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** The real November 2026 workbooks the Flutter tests used. */
class MessReaderTest {
    private val now = LocalDate.of(2026, 11, 16)
    private fun read(name: String) = MessReader.parseXlsx(javaClass.classLoader!!.getResourceAsStream(name).readBytes(), now)

    @Test fun merged_date_blocks_workbook() {
        val menu = read("mess-menu-2026-11.xlsx")
        assertEquals(11, menu.month)
        assertEquals(2026, menu.year)
        assertEquals(listOf("Veg & Non-Veg", "Special"), menu.sheetNames)
        for (sheet in menu.sheetNames) {
            val days = menu.daysFor(sheet)
            assertEquals("$sheet is missing days", emptyList<Int>(), (1..30).filter { days[it] == null })
        }
        assertTrue(menu.isReadable)
        assertTrue(menu.menuFor(1, "Special")!!.breakfast.contains("Cornflakes"))
        assertFalse(menu.menuFor(1)!!.breakfast.contains("Cornflakes"))
        assertEquals("Shavige Bath", menu.menuFor(1)!!.breakfast.first())
        assertEquals("Shavige Bath", menu.menuFor(15)!!.breakfast.first()) // 1st, 15th, 29th are Sundays
        assertEquals("Ghee + Veg Pongal", menu.menuFor(2)!!.breakfast.first())
        assertEquals(menu.days, menu.daysFor("Gone"))
    }

    @Test fun final_layout_workbook() {
        val menu = read("mess-menu-final-2026-11.xlsx")
        assertEquals(listOf("Veg & Non-Veg", "Special"), menu.sheetNames)
        for (sheet in menu.sheetNames) assertEquals(sheet, 30, menu.daysFor(sheet).size)
        assertEquals(11, menu.month)
        assertTrue(menu.isReadable)
    }

    @Test fun typo_dates_snap_to_the_weekday() {
        // "Tue 10, 25": 25 Nov 2026 is a Wednesday, the Tuesday next to it is the 24th.
        assertEquals(listOf(10, 24), MessReader.repairDates(2, listOf(10, 25), 2026, 11))
        assertEquals(2026, MessReader.bestYear(11, listOf(MessReader.Block(7, listOf(1, 8, 15))), 2026))
    }

    @Test fun meal_headings() {
        assertEquals("snacks", MessReader.mealOf("Evening Snacks"))
        assertEquals("dinner", MessReader.mealOf("Dinner / Supper"))
        assertEquals(null, MessReader.mealOf("Remarks"))
    }
}
