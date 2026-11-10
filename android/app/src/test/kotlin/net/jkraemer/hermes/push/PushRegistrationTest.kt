package net.jkraemer.hermes.push

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

private class FakeDistributor(var available: Boolean = true) : PushDistributor {
    val calls = mutableListOf<String>()

    override fun useCurrentOrDefault(activity: Activity, onResult: (Boolean) -> Unit) {
        calls += "useCurrentOrDefault"
        onResult(available)
    }

    override fun pick(activity: Activity, onResult: (Boolean) -> Unit) {
        calls += "pick"
        onResult(available)
    }

    override fun register(context: Context, messageForDistributor: String) {
        calls += "register:$messageForDistributor"
    }
}

@RunWith(RobolectricTestRunner::class)
class PushRegistrationTest {
    private val server = MockWebServer().apply { start() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val distributor = FakeDistributor()
    private var permitted = true
    private val registration = PushRegistration(
        { PushApi(server.url("/"), OkHttpClient()) }, distributor, { permitted }, scope,
    )
    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).get()

    @After fun stop() {
        scope.cancel()
        server.shutdown()
    }

    private fun awaitStatus(expected: PushStatus) = runBlocking { withTimeout(10_000) { registration.status.first { it == expected } } }

    @Test
    fun statusIsRegisteringBeforeStart() {
        assertEquals(PushStatus.Registering, registration.status.value)
    }

    @Test
    fun aFoundDistributorThatNeverAnswersStaysRegistering() {
        registration.start(activity)
        Thread.sleep(200)

        assertEquals(PushStatus.Registering, registration.status.value)
    }

    @Test
    fun notificationPermissionWinsOverRegistering() {
        permitted = false

        registration.start(activity)

        assertEquals(PushStatus.NeedsPermission, registration.status.value)
    }

    @Test
    fun startRegistersWithTheCurrentOrDefaultDistributor() {
        registration.start(activity)

        assertEquals(listOf("useCurrentOrDefault", "register:Hermes"), distributor.calls)
    }

    @Test
    fun startWithoutADistributorIsNoDistributor() {
        distributor.available = false

        registration.start(activity)

        assertEquals(listOf("useCurrentOrDefault"), distributor.calls)
        assertEquals(PushStatus.NoDistributor, registration.status.value)
    }

    @Test
    fun pickingADistributorRegistersWithIt() {
        registration.pickDistributor(activity)

        assertEquals(listOf("pick", "register:Hermes"), distributor.calls)
    }

    @Test
    fun endpointIsSentOnEveryStart() {
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(204))

        registration.onEndpoint("https://ntfy.sh/up1?up=1", "p256dh-key", "auth-key")
        awaitStatus(PushStatus.On)
        registration.onEndpoint("https://ntfy.sh/up1?up=1", "p256dh-key", "auth-key")
        runBlocking { withTimeout(10_000) { while (server.requestCount < 2) delay(10) } }

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("""{"endpoint":"https://ntfy.sh/up1?up=1","p256dh":"p256dh-key","auth":"auth-key"}""", request.body.readUtf8())
        assertEquals(2, server.requestCount)
        assertEquals(PushStatus.On, registration.status.value)
    }

    @Test
    fun withoutNotificationPermissionTheEndpointIsStillSentAndTheStatusAsksForPermission() {
        permitted = false
        server.enqueue(MockResponse().setResponseCode(204))

        registration.onEndpoint("https://ntfy.sh/up1?up=1", "k", "a")
        runBlocking { withTimeout(10_000) { while (server.requestCount < 1) delay(10) } }

        awaitStatus(PushStatus.NeedsPermission)
        permitted = true
        registration.permissionChanged()
        assertEquals(PushStatus.On, registration.status.value)
    }

    @Test
    fun pluginMissingStatus() {
        server.enqueue(MockResponse().setResponseCode(404))

        registration.onEndpoint("https://ntfy.sh/up1?up=1", "k", "a")

        awaitStatus(PushStatus.PluginMissing)
        Thread.sleep(200)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aFailedSubscribeIsFailedWithItsMessage() {
        server.enqueue(MockResponse().setResponseCode(500))

        registration.onEndpoint("https://ntfy.sh/up1?up=1", "k", "a")

        awaitStatus(PushStatus.Failed("PUT /api/plugins/hermes-app/subscription failed with 500"))
    }

    @Test
    fun aFailedRegistrationIsFailedWithItsReason() {
        registration.onRegistrationFailed("NETWORK")

        assertEquals(PushStatus.Failed("NETWORK"), registration.status.value)
    }

    @Test
    fun unregisteringUnsubscribesAndIsNoDistributor() {
        server.enqueue(MockResponse().setResponseCode(204))

        registration.onUnregistered()

        awaitStatus(PushStatus.NoDistributor)
        runBlocking { withTimeout(10_000) { while (server.requestCount < 1) delay(10) } }
        assertEquals("DELETE", server.takeRequest().method)
    }
}
