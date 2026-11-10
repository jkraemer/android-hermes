package net.jkraemer.hermes.core.push

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class PushDecisionTest {
    private fun decide(
        event: PushEvent,
        rule: NotificationRule = NotificationRule.Always,
        open: String? = null,
        fullOutput: suspend (String) -> String? = { null },
    ) = runBlocking { decide(event, { rule }, open, fullOutput) }

    private val cron = PushEvent.Cron("s1", "j1", "Backup", "preview", failed = false)

    @Test
    fun cronOffShowsNothing() {
        assertEquals(PushAction.Nothing, decide(cron, NotificationRule.Off))
    }

    @Test
    fun cronAlwaysShowsTheJobNameAndPreview() {
        assertEquals(
            PushAction.Show(PushNotification("session:s1", Channel.CRON, "Backup", "preview", "s1")),
            decide(cron, NotificationRule.Always),
        )
    }

    @Test
    fun failedCronSaysSo() {
        val action = decide(cron.copy(failed = true)) as PushAction.Show
        assertEquals("Backup failed", action.notification.title)
    }

    @Test
    fun cronRuleIsLookedUpByJobId() {
        val asked = mutableListOf<String>()
        runBlocking { decide(cron, { asked += it; NotificationRule.Off }, null) { null } }
        assertEquals(listOf("j1"), asked)
    }

    @Test
    fun matchTestsTheFullOutput() {
        val rule = NotificationRule.Match("ALERT")
        assertEquals(PushAction.Nothing, decide(cron.copy(text = "ALERT in preview"), rule) { "all quiet" })
        assertEquals(PushAction.Show(PushNotification("session:s1", Channel.CRON, "Backup", "preview", "s1")), decide(cron, rule) { "late ALERT" })
    }

    @Test
    fun matchFallsBackToThePreview() {
        val rule = NotificationRule.Match("ALERT")
        val hit = cron.copy(text = "ALERT")
        assertEquals(PushAction.Show(PushNotification("session:s1", Channel.CRON, "Backup", "ALERT", "s1")), decide(hit, rule) { null })
        assertEquals(PushAction.Show(PushNotification("session:s1", Channel.CRON, "Backup", "ALERT", "s1")), decide(hit, rule) { error("offline") })
        assertEquals(PushAction.Nothing, decide(cron, rule) { throw java.io.IOException("offline") })
    }

    @Test
    fun approvalAlwaysShowsEvenInTheOpenSessionAndWhateverTheRule() {
        assertEquals(
            PushAction.Show(PushNotification("request:r1", Channel.APPROVALS, "Approval needed", "rm -rf x", "s1")),
            decide(PushEvent.Approval("s1", "r1", "rm -rf x"), NotificationRule.Off, open = "s1"),
        )
    }

    @Test
    fun clarifyShowsTheQuestion() {
        assertEquals(
            PushAction.Show(PushNotification("request:r1", Channel.APPROVALS, "Hermes has a question", "Which?", "s1")),
            decide(PushEvent.Clarify("s1", "r1", "Which?")),
        )
    }

    @Test
    fun resolvedCancelsTheApprovalNotification() {
        assertEquals(PushAction.Cancel("request:r1"), decide(PushEvent.Resolved("s1", "r1")))
    }

    @Test
    fun replyIsSilentInTheOpenSessionAndShownElsewhere() {
        assertEquals(PushAction.Nothing, decide(PushEvent.Reply("s1", "hi"), open = "s1"))
        val expected = PushAction.Show(PushNotification("session:s1", Channel.REPLIES, "Hermes replied", "hi", "s1"))
        assertEquals(expected, decide(PushEvent.Reply("s1", "hi"), open = "s2"))
        assertEquals(expected, decide(PushEvent.Reply("s1", "hi"), open = null))
    }

    @Test
    fun testShowsAConfirmation() {
        assertEquals(
            PushAction.Show(PushNotification("test", Channel.REPLIES, "Hermes", "Notifications work.", null)),
            decide(PushEvent.Test),
        )
    }
}
