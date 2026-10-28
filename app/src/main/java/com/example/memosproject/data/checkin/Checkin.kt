package com.example.memosproject.data.checkin

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate

@Serializable
data class Checkin(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("checkin_date") val date: String,
    val status: String,
    val reflection: String,
    val plan: String
)

data class Streaks(val current: Int, val longest: Int)

fun calculateStreaks(dates: Collection<LocalDate>, today: LocalDate): Streaks {
    val sorted = dates.distinct().filter { !it.isAfter(today) }.sorted()
    var longest = 0
    var run = 0
    var previous: LocalDate? = null
    for (date in sorted) {
        run = if (previous?.plusDays(1) == date) run + 1 else 1
        longest = maxOf(longest, run)
        previous = date
    }
    val current = if (previous == today || previous == today.minusDays(1)) run else 0
    return Streaks(current, longest)
}
