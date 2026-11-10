package net.jkraemer.hermes.push

import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/** What [HermesPushService] does with the distributor's events; the service itself needs the connector's KeyStore code. */
class PushHandler(private val registration: PushRegistration) {
    /** The endpoint is sent even when it is temporary: the distributor may take long to get a permanent one. */
    fun onNewEndpoint(endpoint: PushEndpoint) {
        val keys = endpoint.pubKeySet
        if (keys == null) {
            registration.onRegistrationFailed("The distributor gave no encryption keys")
        } else {
            registration.onEndpoint(endpoint.url, keys.pubKey, keys.auth)
        }
    }

    fun onRegistrationFailed(reason: FailedReason) = registration.onRegistrationFailed(reason.name)

    fun onUnregistered() = registration.onUnregistered()

    /** Messages are dropped: nothing shows them yet. */
    fun onMessage(message: PushMessage) = Unit
}
