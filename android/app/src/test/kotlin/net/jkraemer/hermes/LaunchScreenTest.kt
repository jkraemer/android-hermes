package net.jkraemer.hermes

import net.jkraemer.hermes.core.sessions.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.Instant

class LaunchScreenTest {
    private val now = Instant.parse("2026-11-05T12:00:00Z")

    private fun session(id: String, ago: Duration, source: String = "tui", title: String? = id) =
        SessionSummary(id, title, null, source, now - ago, now - ago, 1, pinned = false, archived = false)

    private fun launch(vararg sessions: SessionSummary) = launchScreen(sessions.toList(), now) as Screen.Chat

    private fun assertNewChat(chat: Screen.Chat) = assertEquals(null, chat.storedSessionId)

    @Test fun aChatActiveWithinThirtyMinutesOpens() {
        val chat = launch(session("a", Duration.ofMinutes(29), title = "Morning"))
        assertEquals("a" to "Morning", chat.storedSessionId to chat.title)
    }

    @Test fun aChatActiveThirtyMinutesAgoOrMoreStartsANewChat() {
        assertNewChat(launch(session("a", Duration.ofMinutes(30))))
        assertNewChat(launch(session("a", Duration.ofHours(5))))
    }

    @Test fun theMostRecentlyActiveChatWins() {
        assertEquals(
            "new",
            launch(session("old", Duration.ofMinutes(20)), session("new", Duration.ofMinutes(2))).storedSessionId,
        )
    }

    @Test fun cronRunsNeverOpenAutomatically() {
        assertNewChat(launch(session("c", Duration.ofMinutes(1), source = "cron")))
    }

    @Test fun aRecentCronRunDoesNotHideAnOlderRecentChat() {
        assertEquals(
            "chat",
            launch(session("c", Duration.ofMinutes(1), source = "cron"), session("chat", Duration.ofMinutes(25))).storedSessionId,
        )
    }

    @Test fun noSessionsStartsANewChat() = assertNewChat(launch())

    @Test fun aReopenedChatIsNotOpenedFromTheList() {
        assertEquals(null, launch(session("a", Duration.ofMinutes(1))).fromList)
    }
}
