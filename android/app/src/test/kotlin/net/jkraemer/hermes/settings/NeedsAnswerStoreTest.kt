package net.jkraemer.hermes.settings

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class NeedsAnswerStoreTest {
    private val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("needs-answer-test", Context.MODE_PRIVATE)
    private var now = Instant.parse("2026-11-10T12:00:00Z")
    private lateinit var store: NeedsAnswerStore

    @Before fun setUp() {
        prefs.edit().clear().commit()
        store = NeedsAnswerStore(prefs) { now }
    }

    private fun later(seconds: Long) = now.plusSeconds(seconds)

    @Test
    fun startsEmpty() {
        assertEquals(emptySet<String>(), store.sessions.value)
    }

    @Test
    fun aRequestAddedAgainIsStoredOnce() {
        store.add("s1", "r1", later(300))
        store.add("s2", "r1", later(300))

        assertEquals(setOf("s2"), store.sessions.value)
    }

    @Test
    fun clearingASessionDropsAllItsRequests() {
        store.add("s1", "r1", later(300))
        store.add("s1", "r2", later(300))
        store.add("s2", "r3", later(300))

        store.clearSession("s1")

        assertEquals(setOf("s2"), store.sessions.value)
    }

    @Test
    fun staleEntriesExpire() {
        store.add("s1", "r1", later(300))
        store.add("s2", "r2", later(3600))

        now = later(300)
        store.dropExpired()

        assertEquals(setOf("s2"), store.sessions.value)
    }

    @Test
    fun anExpiredEntryIsNotKept() {
        store.add("s1", "r1", later(300))
        now = later(301)

        store.add("s2", "r2", later(300))

        assertEquals(setOf("s2"), store.sessions.value)
    }

    @Test
    fun entriesSurviveARestart() {
        store.add("s:1", "r:1", later(300))

        assertEquals(setOf("s:1"), NeedsAnswerStore(prefs) { now }.sessions.value)
    }

    @Test
    fun entriesThatExpiredBeforeARestartAreNotLoaded() {
        store.add("s1", "r1", later(300))
        now = later(300)

        assertEquals(emptySet<String>(), NeedsAnswerStore(prefs) { now }.sessions.value)
    }

    @Test
    fun unreadableStoredEntriesAreTreatedAsEmpty() {
        prefs.edit().putString("needs_answer", "{not json").commit()

        val reloaded = NeedsAnswerStore(prefs) { now }

        assertEquals(emptySet<String>(), reloaded.sessions.value)
        reloaded.add("s1", "r1", later(300))
        assertEquals(setOf("s1"), reloaded.sessions.value)
    }

    @Test
    fun entriesOfAnOldShapeAreTreatedAsEmpty() {
        prefs.edit().putString("needs_answer", """[{"session":"s1"}]""").commit()

        assertEquals(emptySet<String>(), NeedsAnswerStore(prefs) { now }.sessions.value)
    }
}
