package net.jkraemer.hermes.sessions

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * When a session was last active, as the session list shows it: relative on the day itself
 * ("just now", "5 minutes ago", "2 hours ago"), the clock time for yesterday, and date plus
 * clock time before that, with the year when it is not the current one.
 */
fun formatSessionTime(lastActive: Instant, now: Instant, zone: ZoneId, locale: Locale): String {
    val day = lastActive.atZone(zone).toLocalDate()
    val today = now.atZone(zone).toLocalDate()
    return when {
        !day.isBefore(today) -> relative(Duration.between(lastActive, now))
        day == today.minusDays(1) -> clockTime(lastActive, zone, locale)
        else -> dateAndClockTime(lastActive, today, zone, locale)
    }
}

/** When a cron run ran, as its job's feed shows it: "Today 10:03", "Yesterday 08:05", else as [formatSessionTime] does. */
fun formatRunTime(time: Instant, now: Instant, zone: ZoneId, locale: Locale): String {
    val day = time.atZone(zone).toLocalDate()
    val today = now.atZone(zone).toLocalDate()
    return when {
        !day.isBefore(today) -> "Today ${clockTime(time, zone, locale)}"
        day == today.minusDays(1) -> "Yesterday ${clockTime(time, zone, locale)}"
        else -> dateAndClockTime(time, today, zone, locale)
    }
}

private fun clockTime(time: Instant, zone: ZoneId, locale: Locale): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(time.atZone(zone))

/** The date, with the year when it is not [today]'s, and the clock time. */
private fun dateAndClockTime(time: Instant, today: LocalDate, zone: ZoneId, locale: Locale): String {
    val day = time.atZone(zone).toLocalDate()
    val pattern = if (day.year == today.year) "MMM d" else "MMM d, yyyy"
    return "${DateTimeFormatter.ofPattern(pattern, locale).format(day)}, ${clockTime(time, zone, locale)}"
}

private fun relative(age: Duration): String {
    val minutes = age.toMinutes()
    val hours = age.toHours()
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> if (minutes == 1L) "1 minute ago" else "$minutes minutes ago"
        else -> if (hours == 1L) "1 hour ago" else "$hours hours ago"
    }
}
