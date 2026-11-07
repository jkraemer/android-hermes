package net.jkraemer.hermes.core.timeline

import net.jkraemer.hermes.core.cron.CronJob
import net.jkraemer.hermes.core.cron.CronJobSummary
import net.jkraemer.hermes.core.cron.ShownRun
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

    private fun session(id: String, lastActive: Instant, pinned: Boolean = false, source: String = "tui") = SessionSummary(
        id = id, title = id, preview = null, source = source, startedAt = lastActive, lastActive = lastActive,
        messageCount = 1, pinned = pinned, archived = false,
    )

    /** A job whose newest shown run was at [time], or that has shown none when it is null. */
    private fun job(id: String, time: Instant?, unread: Boolean = false) = CronJobSummary(
        CronJob(id, id, lastRunAt = null), time?.let { ShownRun("$id-run", it, "Report") }, unread, emptyList(),
    )

    private fun build(
        sessions: List<SessionSummary>,
        lastOpened: Map<String, Instant> = emptyMap(),
        needsAnswer: Set<String> = emptySet(),
        jobs: List<CronJobSummary> = emptyList(),
    ) = buildTimeline(sessions, jobs, now, berlin, lastOpened, baseline, needsAnswer)

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

    private fun drawer(sessions: List<SessionSummary>, needsAnswer: Set<String> = emptySet(), jobs: List<CronJobSummary> = emptyList()) =
        buildDrawerEntries(sessions, jobs, emptyMap(), baseline, needsAnswer)

    @Test
    fun drawerListsEveryPinnedSessionNewestFirst() {
        val pins = (1..12).map { session("p$it", at(5, 1, it), pinned = true) }

        val entries = drawer(pins + session("r", at(5, 11)))

        assertEquals((12 downTo 1).map { "p$it" }, entries.pinned.map { it.session.id })
    }

    @Test
    fun drawerRecentsAreTheTenNewestUnpinnedSessions() {
        val sessions = (1..14).map { session("s$it", at(5, 1, it)) } + session("pin", at(5, 11), pinned = true)

        val entries = drawer(sessions)

        assertEquals((14 downTo 5).map { "s$it" }, entries.recents.map { it.session.id })
        assertEquals(listOf("pin"), entries.pinned.map { it.session.id })
    }

    @Test
    fun drawerLeavesOutChatsWithoutMessages() {
        val empty = session("empty", at(5, 11)).copy(title = "", messageCount = 0)
        val emptyPinned = session("emptyPinned", at(5, 10), pinned = true).copy(messageCount = 0)

        val entries = drawer(listOf(empty, emptyPinned, session("chat", at(5, 9))))

        assertEquals(listOf("chat"), entries.recents.map { it.session.id })
        assertEquals(emptyList<String>(), entries.pinned.map { it.session.id })
    }

    @Test
    fun drawerRecentsStillCountTenWhenEmptyChatsAreLeftOut() {
        val sessions = (1..10).map { session("s$it", at(5, 1, it)) } + session("empty", at(5, 11)).copy(messageCount = 0)

        assertEquals((10 downTo 1).map { "s$it" }, drawer(sessions).recents.map { it.session.id })
    }

    @Test
    fun timelineLeavesOutChatsWithoutMessages() {
        val empty = session("empty", at(5, 11)).copy(title = "", messageCount = 0)

        assertEquals(listOf("chat"), build(listOf(empty, session("chat", at(5, 9)))).rows().map { it.session.id })
    }

    @Test
    fun drawerRowsCarryTheUnreadAndNeedsAnswerFlags() {
        val read = session("read", at(5, 9))
        val unread = session("unread", at(5, 11))

        val entries = buildDrawerEntries(listOf(read, unread), emptyList(), mapOf("read" to at(5, 10)), baseline, setOf("unread"))

        assertEquals(
            listOf(TimelineItem.Row(unread, unread = true, needsAnswer = true), TimelineItem.Row(read, unread = false, needsAnswer = false)),
            entries.recents,
        )
    }

    @Test
    fun timelineLeavesOutCronRunsPinnedOrNot() {
        val sessions = listOf(session("run", at(5, 11), source = "cron"), session("pinnedRun", at(5, 10), pinned = true, source = "cron"), session("chat", at(5, 9)))

        assertEquals(listOf("chat"), build(sessions).rows().map { it.session.id })
    }

    @Test
    fun jobsSitInTheDayBucketOfTheirTimeAmongTheSessions() {
        val items = build(
            listOf(session("chat", at(5, 10)), session("old", at(3, 9))),
            jobs = listOf(job("morning", at(5, 11), unread = true), job("nightly", at(4, 23)), job("quiet", null)),
        )

        assertEquals(
            listOf(
                TimelineItem.Header(Bucket.TODAY), TimelineItem.Job(job("morning", at(5, 11), unread = true)),
                TimelineItem.Row(session("chat", at(5, 10)), unread = true, needsAnswer = false),
                TimelineItem.Header(Bucket.YESTERDAY), TimelineItem.Job(job("nightly", at(4, 23))),
                TimelineItem.Header(Bucket.OLDER), TimelineItem.Row(session("old", at(3, 9)), unread = true, needsAnswer = false),
            ),
            items,
        )
    }

    @Test
    fun drawerRecentsLeaveOutCronRunsButStillCountTen() {
        val sessions = (1..10).map { session("s$it", at(5, 1, it)) } + session("run", at(5, 11), source = "cron")

        assertEquals((10 downTo 1).map { "s$it" }, drawer(sessions).recents.map { it.session.id })
    }

    @Test
    fun drawerLeavesOutAPinnedCronRunToo() {
        val entries = drawer(listOf(session("pinnedRun", at(5, 10), pinned = true, source = "cron"), session("pin", at(5, 9), pinned = true)))

        assertEquals(listOf("pin"), entries.pinned.map { it.session.id })
    }

    @Test
    fun drawerListsTheJobsNewestFirstAndThoseThatNeverReportedLast() {
        val jobs = listOf(job("quiet", null), job("nightly", at(4, 23)), job("idle", null), job("morning", at(5, 11)))

        assertEquals(listOf("morning", "nightly", "idle", "quiet"), drawer(emptyList(), jobs = jobs).scheduled.map { it.job.id })
    }
}
