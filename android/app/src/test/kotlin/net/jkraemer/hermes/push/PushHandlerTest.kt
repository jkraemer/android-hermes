package net.jkraemer.hermes.push

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.jkraemer.hermes.core.push.PushApi
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.data.PublicKeySet
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

private object NoDistributor : PushDistributor {
    override fun useCurrentOrDefault(activity: Activity, onResult: (Boolean) -> Unit) = onResult(false)
    override fun pick(activity: Activity, onResult: (Boolean) -> Unit) = onResult(false)
    override fun register(context: Context, messageForDistributor: String) = Unit
}

class PushHandlerTest {
    private val server = MockWebServer().apply { start() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val registration = PushRegistration({ PushApi(server.url("/"), OkHttpClient()) }, NoDistributor, { true }, scope)
    private val handler = PushHandler(registration)

    @After fun stop() {
        scope.cancel()
        server.shutdown()
    }

    @Test
    fun aNewEndpointIsSentWithItsKeys() {
        server.enqueue(MockResponse().setResponseCode(204))

        handler.onNewEndpoint(PushEndpoint("https://ntfy.sh/up1?up=1", PublicKeySet("pub", "auth"), temporary = false))

        runBlocking { withTimeout(10_000) { registration.status.first { it == PushStatus.On } } }
        assertEquals("""{"endpoint":"https://ntfy.sh/up1?up=1","p256dh":"pub","auth":"auth"}""", server.takeRequest().body.readUtf8())
    }

    @Test
    fun aTemporaryEndpointIsStillSent() {
        server.enqueue(MockResponse().setResponseCode(204))

        handler.onNewEndpoint(PushEndpoint("https://ntfy.sh/up1?up=1", PublicKeySet("pub", "auth"), temporary = true))

        runBlocking { withTimeout(10_000) { registration.status.first { it == PushStatus.On } } }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun anEndpointWithoutKeysCannotReceiveEncryptedPushes() {
        handler.onNewEndpoint(PushEndpoint("https://ntfy.sh/up1?up=1", null, temporary = false))

        assertEquals(PushStatus.Failed("The distributor gave no encryption keys"), registration.status.value)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aFailedRegistrationShowsItsReason() {
        handler.onRegistrationFailed(FailedReason.NETWORK)

        assertEquals(PushStatus.Failed("NETWORK"), registration.status.value)
    }

    @Test
    fun unregisteringIsNoDistributor() {
        server.enqueue(MockResponse().setResponseCode(204))

        handler.onUnregistered()

        runBlocking { withTimeout(10_000) { registration.status.first { it == PushStatus.NoDistributor } } }
    }

    @Test
    fun messagesAreDroppedUntilTheyAreShown() {
        handler.onMessage(PushMessage("{}".toByteArray(), decrypted = true))

        assertEquals(0, server.requestCount)
    }
}
