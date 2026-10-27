package com.example.memosproject.goals

import com.example.memosproject.data.goals.Goal
import com.example.memosproject.data.goals.GoalDraft
import com.example.memosproject.data.goals.parseGoalAmount
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class GoalTest {
    @Test fun moneyParsingPreservesPrecisionAndAcceptsBothDecimalSeparators() {
        assertEquals(BigDecimal("9999999999999999.99"), parseGoalAmount("9999999999999999,99"))
        assertEquals(BigDecimal("1234.50"), parseGoalAmount(" 1234.50 "))
        assertEquals(BigDecimal.ZERO, parseGoalAmount("0"))
    }

    @Test fun moneyParsingRejectsAmbiguousOrOutOfRangeValues() {
        listOf("", "-1", "1.000.000", "1,000,000", "0.001", "1e3", "NaN", "Infinity",
            "10000000000000000", "1,000", "+1", ".50").forEach {
            assertNull("Should reject $it", parseGoalAmount(it))
        }
    }

    @Test fun draftRejectsZeroTargetAndNegativeSavings() {
        assertThrows(IllegalArgumentException::class.java) { draft(target = "0").validate() }
        assertThrows(IllegalArgumentException::class.java) { draft(saved = "-0.01").validate() }
        assertThrows(IllegalArgumentException::class.java) { draft(target = "-10").validate() }
    }

    @Test fun draftChecksPrecisionAndTextLimits() {
        assertThrows(IllegalArgumentException::class.java) { draft(target = "0.001").validate() }
        assertThrows(IllegalArgumentException::class.java) { draft(saved = "10000000000000000").validate() }
        assertThrows(IllegalArgumentException::class.java) { draft().copy(name = " ").validate() }
        assertThrows(IllegalArgumentException::class.java) { draft().copy(name = "x".repeat(101)).validate() }
        assertThrows(IllegalArgumentException::class.java) { draft().copy(description = "x".repeat(2001)).validate() }
        draft(target = "0.01", saved = "9999999999999999.99").validate()
    }

    @Test fun progressUsesActualRatioAndCapsOnlyTheBar() {
        val quarter = goal(target = "200", saved = "50")
        assertEquals(0.25f, quarter.progress, 0.00001f)
        assertEquals(BigDecimal("25.0"), quarter.progressPercent)
        assertEquals(BigDecimal("150"), quarter.remainingAmount)
        assertFalse(quarter.isComplete)

        val exceeded = goal(target = "200", saved = "250")
        assertEquals(1f, exceeded.progress, 0f)
        assertEquals(BigDecimal("125.0"), exceeded.progressPercent)
        assertEquals(BigDecimal.ZERO, exceeded.remainingAmount)
        assertTrue(exceeded.isComplete)
    }

    @Test fun emptySavingsAndCompletedGoalHaveCorrectProgress() {
        assertEquals(0f, goal(saved = "0").progress, 0f)
        assertFalse(goal(saved = "0").isComplete)
        assertTrue(goal(saved = "100").isComplete)
        assertEquals(1f, goal(saved = "100").progress, 0f)
        assertEquals(0f, goal(target = "0").progress, 0f)
    }

    @Test fun supabaseNumericSerializationRoundTripsLargeAmountsExactly() {
        val source = """{"id":"goal","user_id":"owner","name":"Rumah","target_amount":9999999999999999.99,"saved_amount":1234567890123456.78,"image_path":null}"""
        val decoded = Json.decodeFromString<Goal>(source)
        assertEquals(BigDecimal("9999999999999999.99"), decoded.targetAmount)
        assertEquals(BigDecimal("1234567890123456.78"), decoded.savedAmount)
        val encoded = Json.encodeToString(decoded)
        assertTrue(encoded.contains("\"target_amount\":9999999999999999.99"))
        assertEquals(decoded, Json.decodeFromString<Goal>(encoded))
    }

    @Test fun existingTitleColumnDecodesToGoalNameWithoutLosingOtherFields() {
        val source = """{"id":"goal","user_id":"owner","title":"Mobil","target_amount":10000.00,"saved_amount":1000.00,"description":"Perjalanan","image_path":null}"""
        val goal = Json.decodeFromString<Goal>(source)
        assertEquals("Mobil", goal.name)
        assertEquals(BigDecimal("10000.00"), goal.targetAmount)
        assertEquals(BigDecimal("1000.00"), goal.savedAmount)
        assertEquals("Perjalanan", goal.description)
    }

    private fun draft(target: String = "100", saved: String = "0") =
        GoalDraft("Liburan", target.toBigDecimal(), saved.toBigDecimal(), null)

    private fun goal(target: String = "100", saved: String = "25") =
        Goal("goal", "owner", "Liburan", target.toBigDecimal(), saved.toBigDecimal())
}
