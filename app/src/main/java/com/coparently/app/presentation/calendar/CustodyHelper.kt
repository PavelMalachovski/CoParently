package com.coparently.app.presentation.calendar

import com.coparently.app.data.local.entity.CustodyScheduleEntity
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Helper utility for determining custody based on schedule.
 * Used to show indicators for which parent has custody on a given date.
 */
object CustodyHelper {
    /**
     * Determines which parent has custody on a given date based on schedules.
     *
     * @param date The date to check custody for
     * @param schedules List of active custody schedules
     * @return "mom", "dad", or null if no schedule found
     */
    fun getCustodyForDate(
        date: LocalDate,
        schedules: List<CustodyScheduleEntity>
    ): String? {
        if (schedules.isEmpty()) return null

        val dayOfWeek = date.dayOfWeek.value // 1 = Monday, 7 = Sunday
        val scheduleForDay = schedules.find { it.dayOfWeek == dayOfWeek }

        return scheduleForDay?.parentOwner
    }

    /**
     * Checks if a date is today.
     *
     * @param date The date to check
     * @return true if the date is today, false otherwise
     */
    fun isToday(date: LocalDate): Boolean {
        return date == LocalDate.now()
    }

    /**
     * Gets the day of week value (1-7) from a LocalDate.
     *
     * @param date The date
     * @return Day of week value (1 = Monday, 7 = Sunday)
     */
    fun getDayOfWeekValue(date: LocalDate): Int {
        return date.dayOfWeek.value
    }
}

