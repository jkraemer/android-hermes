package net.jkraemer.hermes.chat

import android.content.Context
import android.os.Looper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import net.jkraemer.hermes.core.chat.ApprovalChoice
import net.jkraemer.hermes.core.rpc.ConnectionState
import net.jkraemer.hermes.settings.SeenStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant

private val NOW = Instant.parse("2026-11-05T12:00:00Z")
private const val OPEN_APPROVAL =
    """{"session_id":"live1","stored_session_id":"stored1","messages":[],"running":true,"open_requests":[
        {"id":"srq-1","method":"approval","params":{"session_id":"live1","request_id":"r1","command":"rm x"}}]}"""

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatViewModelTest {
    private val peer = FakePeer()
    private val needsAnswer = MutableStateFlow(emptySet<String>())
    private lateinit var seenStore: SeenStore

    @Before
    fun setUp() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("hermes-vm-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        seenStore = SeenStore(prefs)
    }

    private fun viewModel(id: String? = "stored1") = ChatViewModel(peer, id, null, needsAnswer, seenStore) { NOW }

    /** Lets the view model's coroutines, which run on the main looper, catch up. */
    private fun settle() {
        repeat(5) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun aClosedChatDoesNotOpenWhenTheConnectionReturns() {
        peer.state.value = ConnectionState.Connecting
        val chat = viewModel()
        settle()
        chat.close()

        peer.state.value = ConnectionState.Ready
        settle()

        assertTrue(peer.calls.none { it.first == "session.resume" || it.first == "session.create" })
    }

    @Test
    fun aClosedNewChatDoesNotCreateASession() {
        peer.state.value = ConnectionState.Connecting
        val chat = viewModel(id = null)
        settle()
        chat.close()

        peer.state.value = ConnectionState.Ready
        settle()

        assertTrue(peer.calls.none { it.first == "session.create" })
    }

    @Test
    fun aChatClosedWhileOpeningStaysOutOfNeedsAnswerAndSeenStore() {
        peer.resume = OPEN_APPROVAL
        val gate = CompletableDeferred<Unit>()
        peer.openGate = gate
        val chat = viewModel()
        chat.setVisible(true)
        settle()
        assertTrue(peer.calls.any { it.first == "session.resume" })
        chat.close()

        gate.complete(Unit)
        settle()

        assertTrue(needsAnswer.value.isEmpty())
        assertTrue(seenStore.lastOpened().isEmpty())
    }

    @Test
    fun aReplyCompletedWhileNotVisibleIsMarkedSeenOnceVisible() {
        peer.resume = """{"session_id":"live1","stored_session_id":"stored1","messages":[],"running":true,"open_requests":[]}"""
        val chat = viewModel()
        settle()
        assertFalse(seenStore.lastOpened().containsKey("stored1"))

        chat.setVisible(true)
        settle()
        assertEquals(NOW, seenStore.lastOpened()["stored1"])
    }

    @Test
    fun aReplyCompletingInTheBackgroundIsNotMarkedSeen() {
        peer.resume = """{"session_id":"live1","stored_session_id":"stored1","messages":[],"running":true,"open_requests":[]}"""
        val chat = viewModel()
        chat.setVisible(true)
        settle()
        chat.setVisible(false)
        seenStore.markOpened("stored1", Instant.EPOCH)

        peer.event("message.complete", """{"text":"done"}""")
        settle()

        assertEquals(Instant.EPOCH, seenStore.lastOpened()["stored1"])
    }
}
