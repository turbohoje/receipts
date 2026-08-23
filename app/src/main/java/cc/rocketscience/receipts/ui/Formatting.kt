package cc.rocketscience.receipts.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val mediumDate: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

fun formatDate(epochMillis: Long): String =
    mediumDate.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

/** "12 Mar – 4 Apr 2026", or a single date when the range collapses. */
fun formatDateRange(first: Long?, last: Long?): String? {
    if (first == null || last == null) return null
    val a = formatDate(first)
    val b = formatDate(last)
    return if (a == b) a else "$a – $b"
}

/**
 * The M3 date picker reports UTC midnight for the chosen day. Storing that directly makes
 * the date render a day early anywhere west of UTC, so anchor it to local noon instead —
 * far enough from both midnights that no offset or DST shift can move the calendar day.
 */
fun pickedDateToStoredMillis(utcMidnightMillis: Long): Long =
    Instant.ofEpochMilli(utcMidnightMillis)
        .atZone(java.time.ZoneOffset.UTC)
        .toLocalDate()
        .atTime(12, 0)
        .atZone(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()

/** Inverse of [pickedDateToStoredMillis], for seeding the picker's initial selection. */
fun storedMillisToPickerMillis(storedMillis: Long): Long =
    Instant.ofEpochMilli(storedMillis)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
        .atStartOfDay(java.time.ZoneOffset.UTC)
        .toInstant()
        .toEpochMilli()
