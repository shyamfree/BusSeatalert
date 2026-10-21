package com.busseatalert.ui

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val PRETTY = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.ENGLISH)

/** "2026-10-22" -> "Thu, 22 Oct 2026", so dates can't be confused with the site's dd/MM format. */
fun prettyDate(isoDate: String): String = runCatching { LocalDate.parse(isoDate).format(PRETTY) }.getOrDefault(isoDate)

private val SHORT = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

/** "2026-10-22" -> "Thu 22 Oct", for compact notification lines and date chips. */
fun shortDate(isoDate: String): String = runCatching { LocalDate.parse(isoDate).format(SHORT) }.getOrDefault(isoDate)

/** Each site's colour: Kerala red, Karnataka blue, Tamil Nadu green, in bright (not dark) tones. */
val com.busseatalert.model.Operator.color: androidx.compose.ui.graphics.Color
    get() = when (this) {
        com.busseatalert.model.Operator.KERALA_RTC -> androidx.compose.ui.graphics.Color(0xFFE53935)
        com.busseatalert.model.Operator.KARNATAKA_RTC -> androidx.compose.ui.graphics.Color(0xFF1E88E5)
        com.busseatalert.model.Operator.TNSTC -> androidx.compose.ui.graphics.Color(0xFF2E9E4F)
    }

/** Soft background for a site's bus cards. */
val com.busseatalert.model.Operator.tint: androidx.compose.ui.graphics.Color
    get() = when (this) {
        com.busseatalert.model.Operator.KERALA_RTC -> androidx.compose.ui.graphics.Color(0xFFFFEBEE)
        com.busseatalert.model.Operator.KARNATAKA_RTC -> androidx.compose.ui.graphics.Color(0xFFE6F2FD)
        com.busseatalert.model.Operator.TNSTC -> androidx.compose.ui.graphics.Color(0xFFE8F6EC)
    }

/** A bus time split into its calendar date and clock time. */
data class DatedTime(val date: LocalDate, val time: String)

private val DAY_OFFSET = Regex("""\(([+-]?\d+) day\)""")

/**
 * Works out the calendar date of a bus time on [journeyDate]. Kerala/Karnataka times carry
 * "(+1 day)"; TNSTC doesn't, so an arrival earlier than the departure is taken as the next day.
 */
fun datedTime(time: String, journeyDate: String, departure: String? = null): DatedTime? {
    val base = runCatching { LocalDate.parse(journeyDate) }.getOrNull() ?: return null
    val clock = Regex("""\d{1,2}:\d{2}""").find(time)?.value ?: return null
    val offset = DAY_OFFSET.find(time)?.groupValues?.get(1)?.toLongOrNull()
        ?: departure?.let { dep ->
            val depClock = Regex("""\d{1,2}:\d{2}""").find(dep)?.value
            val depOffset = DAY_OFFSET.find(dep)?.groupValues?.get(1)?.toLongOrNull() ?: 0
            if (depClock != null && clock.padStart(5, '0') < depClock.padStart(5, '0')) depOffset + 1 else depOffset
        } ?: 0
    return DatedTime(base.plusDays(offset), clock)
}

fun shortDate(date: LocalDate): String = date.format(SHORT)
