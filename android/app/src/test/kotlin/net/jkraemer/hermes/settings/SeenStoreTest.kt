package net.jkraemer.hermes.settings

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class SeenStoreTest {
    private val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("seen-test", Context.MODE_PRIVATE)

    @Test
    fun baselineIsTheFirstStartAndStaysThere() {
        val first = SeenStore(prefs) { Instant.parse("2026-11-01T08:00:00Z") }
        val second = SeenStore(prefs) { Instant.parse("2026-11-09T08:00:00Z") }

        assertEquals(Instant.parse("2026-11-01T08:00:00Z"), first.baseline)
        assertEquals(Instant.parse("2026-11-01T08:00:00Z"), second.baseline)
    }

    @Test
    fun remembersWhenEachSessionWasLastOpened() {
        val store = SeenStore(prefs)
        store.markOpened("a:1", Instant.parse("2026-11-02T10:00:00Z"))
        store.markOpened("b", Instant.parse("2026-11-03T10:00:00Z"))
        store.markOpened("a:1", Instant.parse("2026-11-04T10:00:00Z"))

        assertEquals(
            mapOf("a:1" to Instant.parse("2026-11-04T10:00:00Z"), "b" to Instant.parse("2026-11-03T10:00:00Z")),
            SeenStore(prefs).lastOpened(),
        )
    }
}
