package net.jkraemer.hermes.core.timeline

import net.jkraemer.hermes.core.sessions.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class TimelineTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private val now = ZonedDateTime.of(2026, 11, 5, 12, 0, 0, 0, berlin).toInstant()
    private val baseline = Instant.parse("2026-11-01T00:00:00Z")

    private fun at(day: Int, hour: Int, minute: Int = 0) =
        ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, berlin).toInstant()

    private fun session(id: String, lastActive: Instant, pinned: Boolean = false) = SessionSummary(
        id = id, title = id, preview = null, source = "tui", startedAt = lastActive, lastActive = lastActive,
        messageCount = 1, pinned = pinned, archived = false,
    )

    private fun build(
        sessions: List<SessionSummary>,
        lastOpened: Map<String, Instant> = emptyMap(),
        needsAnswer: Set<String> = emptySet(),
    ) = buildTimeline(sessions, now, berlin, lastOpened, baseline, needsAnswer)

    private fun List<TimelineItem>.rows() = filterIsInstance<TimelineItem.Row>()

    @Test
    fun groupsByDayInZone() {
        // 23:30 Berlin on 4 Nov is 22:30 UTC on 4 Nov; 00:30 Berlin on 5 Nov is 23:30 UTC on 4 Nov.
        val lateYesterday = session("late", at(4, 23, 30))
        val earlyToday = session("early", at(5, 0, 30))
        val older = session("old", at(3, 12))

        val items = build(listOf(earlyToday, lateYesterday, older))

        assertEquals(
            listOf(
                TimelineItem.Header(Bucket.TODAY), TimelineItem.Row(earlyToday, unread = true, needsAnswer = false),
                TimelineItem.Header(Bucket.YESTERDAY), TimelineItem.Row(lateYesterday, unread = true, needsAnswer = false),
                TimelineItem.Header(Bucket.OLDER), TimelineItem.Row(older, unread = true, needsAnswer = false),
            ),
            items,
        )
    }

    @Test
    fun needsAnswerComesFirst() {
        val pinned = session("pinned", at(5, 11), pinned = true)
        val asking = session("asking", at(3, 9), pinned = true)

        val items = build(listOf(pinned, asking), needsAnswer = setOf("asking"))

        assertEquals(
            listOf(
                TimelineItem.Header(Bucket.NEEDS_ANSWER), TimelineItem.Row(asking, unread = true, needsAnswer = true),
                TimelineItem.Header(Bucket.PINNED), TimelineItem.Row(pinned, unread = true, needsAnswer = false),
            ),
            items,
        )
    }

    @Test
    fun pinnedAboveToday() {
        val today = session("today", at(5, 11))
        val pinned = session("pinned", at(1, 9), pinned = true)

        val items = build(listOf(today, pinned))

        assertEquals(
            listOf(Bucket.PINNED, Bucket.TODAY),
            items.filterIsInstance<TimelineItem.Header>().map { it.bucket },
        )
        assertEquals(listOf("pinned", "today"), items.rows().map { it.session.id })
    }

    @Test
    fun emptyBucketsHaveNoHeader() {
        assertEquals(emptyList<TimelineItem>(), build(emptyList()))
        val items = build(listOf(session("a", at(5, 9)), session("b", at(5, 10))))
        assertEquals(listOf(TimelineItem.Header(Bucket.TODAY)), items.filterIsInstance<TimelineItem.Header>())
        assertEquals(listOf("b", "a"), items.rows().map { it.session.id })
    }

    @Test
    fun unreadComparesWithLastOpened() {
        val read = session("read", at(5, 10))
        val changed = session("changed", at(5, 11))

        val rows = build(
            listOf(read, changed),
            lastOpened = mapOf("read" to at(5, 10, 30), "changed" to at(5, 10, 30)),
        ).rows().associate { it.session.id to it.unread }

        assertEquals(mapOf("read" to false, "changed" to true), rows)
    }

    @Test
    fun neverOpenedUsesBaseline() {
        val beforeBaseline = session("before", Instant.parse("2026-10-30T10:00:00Z"))
        val afterBaseline = session("after", at(5, 10))

        val rows = build(listOf(beforeBaseline, afterBaseline)).rows().associate { it.session.id to it.unread }

        assertEquals(mapOf("before" to false, "after" to true), rows)
    }
}
