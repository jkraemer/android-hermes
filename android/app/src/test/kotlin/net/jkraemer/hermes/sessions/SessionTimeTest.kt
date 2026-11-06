package net.jkraemer.hermes.sessions

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class SessionTimeTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private val now = ZonedDateTime.of(2026, 11, 5, 15, 30, 0, 0, berlin).toInstant()

    private fun at(month: Int, day: Int, hour: Int, minute: Int, year: Int = 2026): Instant =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, berlin).toInstant()

    private fun format(lastActive: Instant) = formatSessionTime(lastActive, now, berlin, Locale.UK)

    @Test fun lessThanAMinuteAgoIsJustNow() {
        assertEquals("just now", format(now))
        assertEquals("just now", format(now.minusSeconds(59)))
    }

    @Test fun aTimeInTheFutureIsJustNow() = assertEquals("just now", format(now.plusSeconds(30)))

    @Test fun minutesAgoToday() {
        assertEquals("1 minute ago", format(now.minusSeconds(60)))
        assertEquals("5 minutes ago", format(now.minusSeconds(5 * 60 + 20)))
        assertEquals("59 minutes ago", format(now.minusSeconds(59 * 60)))
    }

    @Test fun hoursAgoToday() {
        assertEquals("1 hour ago", format(now.minusSeconds(3600)))
        assertEquals("1 hour ago", format(now.minusSeconds(119 * 60)))
        assertEquals("2 hours ago", format(now.minusSeconds(2 * 3600)))
        assertEquals("15 hours ago", format(at(11, 5, 0, 30)))
    }

    @Test fun yesterdayIsTheClockTime() {
        assertEquals("14:03", format(at(11, 4, 14, 3)))
        // Less than 24 hours ago, but on the previous calendar day.
        assertEquals("23:50", format(at(11, 4, 23, 50)))
    }

    @Test fun olderIsDateAndClockTime() {
        assertEquals("Nov 3, 14:03", format(at(11, 3, 14, 3)))
        assertEquals("Jan 9, 08:15", format(at(1, 9, 8, 15)))
    }

    @Test fun olderFromAnotherYearHasTheYear() {
        assertEquals("Dec 30, 2025, 22:00", format(at(12, 30, 22, 0, year = 2025)))
    }

    @Test fun theDayBoundaryFollowsTheZone() {
        // 23:30 UTC on 4 Nov is 00:30 on 5 Nov in Berlin: today.
        assertEquals("15 hours ago", formatSessionTime(Instant.parse("2026-11-04T23:30:00Z"), now, berlin, Locale.UK))
        // The same instant is yesterday in UTC.
        assertEquals("23:30", formatSessionTime(Instant.parse("2026-11-04T23:30:00Z"), now, ZoneId.of("UTC"), Locale.UK))
    }
}
