package net.jkraemer.hermes.push

import net.jkraemer.hermes.HermesApp
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/** Receives the distributor's events and hands them to the [PushHandler]. */
class HermesPushService : PushService() {
    private val handler get() = (application as HermesApp).graph.pushHandler

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) = handler.onNewEndpoint(endpoint)

    override fun onMessage(message: PushMessage, instance: String) = handler.onMessage(message.content, message.decrypted)

    override fun onRegistrationFailed(reason: FailedReason, instance: String) = handler.onRegistrationFailed(reason)

    override fun onUnregistered(instance: String) = handler.onUnregistered()
}
