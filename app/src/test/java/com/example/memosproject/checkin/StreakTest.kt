package com.example.memosproject.checkin

import com.example.memosproject.data.checkin.calculateStreaks
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class StreakTest {
    private val today = LocalDate.parse("2026-10-28")
    private fun dates(vararg days: Int) = days.map { today.withDayOfMonth(it) }
    @Test fun yesterdayKeepsCurrentStreakAndGapResetsIt() {
        assertEquals(3, calculateStreaks(dates(25, 26, 27), today).current)
        assertEquals(0, calculateStreaks(dates(25, 26, 27), today.plusDays(1)).current)
    }
    @Test fun longestUsesAllRunsAndDeduplicates() {
        val streaks = calculateStreaks(dates(1, 2, 3, 4, 10, 11, 27, 28, 28), today)
        assertEquals(4, streaks.longest)
        assertEquals(2, streaks.current)
    }
    @Test fun deletingMiddleDateBreaksRun() {
        assertEquals(1, calculateStreaks(dates(26, 28), today).current)
        assertEquals(0, calculateStreaks(emptyList(), today).longest)
    }
}
