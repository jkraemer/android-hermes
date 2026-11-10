package net.jkraemer.hermes.push

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import net.jkraemer.hermes.core.push.PluginMissingException
import net.jkraemer.hermes.core.push.PushApi

/** Whether the app gets pushes, as the settings show it. */
sealed interface PushStatus {
    data object On : PushStatus

    /** Registered, but notifications are not allowed, so nothing would be shown. */
    data object NeedsPermission : PushStatus

    /** No UnifiedPush distributor (such as ntfy) is installed or chosen. */
    data object NoDistributor : PushStatus

    data object PluginMissing : PushStatus

    data class Failed(val message: String) : PushStatus
}

/** The UnifiedPush distributor choice and registration; the connector's own object behind it cannot run in unit tests. */
interface PushDistributor {
    /** Picks the distributor already in use, or the default one, and reports whether there is one. */
    fun useCurrentOrDefault(activity: Activity, onResult: (Boolean) -> Unit)

    /** Lets the user choose a distributor, and reports whether there is one. */
    fun pick(activity: Activity, onResult: (Boolean) -> Unit)

    fun register(context: Context, messageForDistributor: String)
}

/**
 * Registers the app with the UnifiedPush distributor and hands the endpoint it gets, with the keys to encrypt for,
 * to the server's plugin. [api] is the plugin client of the current server; [notificationsAllowed] is whether the
 * system lets the app show notifications.
 */
class PushRegistration(
    private val api: () -> PushApi,
    private val distributor: PushDistributor,
    private val notificationsAllowed: () -> Boolean,
    private val scope: CoroutineScope,
) {
    private sealed interface Outcome {
        data object Registered : Outcome
        data object NoDistributor : Outcome
        data object PluginMissing : Outcome
        data class Failed(val message: String) : Outcome
    }

    private var outcome: Outcome = Outcome.NoDistributor
    private val _status = MutableStateFlow<PushStatus>(PushStatus.NoDistributor)
    val status: StateFlow<PushStatus> = _status

    /** Registers again; the distributor may have changed or forgotten the registration since the last start. */
    fun start(activity: Activity) = distributor.useCurrentOrDefault(activity) { register(activity, it) }

    fun pickDistributor(activity: Activity) = distributor.pick(activity) { register(activity, it) }

    private fun register(context: Context, found: Boolean) {
        if (found) distributor.register(context, MESSAGE_FOR_DISTRIBUTOR) else update(Outcome.NoDistributor)
    }

    /** The system's notification permission may have changed. */
    fun permissionChanged() = update(outcome)

    fun onEndpoint(url: String, p256dh: String, auth: String) {
        scope.launch {
            update(
                try {
                    api().subscribe(url, p256dh, auth)
                    Outcome.Registered
                } catch (e: CancellationException) {
                    throw e
                } catch (e: PluginMissingException) {
                    Outcome.PluginMissing
                } catch (e: Exception) {
                    Outcome.Failed(e.message ?: e::class.java.simpleName)
                },
            )
        }
    }

    fun onRegistrationFailed(reason: String) = update(Outcome.Failed(reason))

    fun onUnregistered() {
        update(Outcome.NoDistributor)
        scope.launch {
            try {
                api().unsubscribe()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The server stops pushing to an endpoint that no longer exists by itself.
            }
        }
    }

    @Synchronized
    private fun update(new: Outcome) {
        outcome = new
        _status.value = when (new) {
            Outcome.NoDistributor -> PushStatus.NoDistributor
            Outcome.PluginMissing -> PushStatus.PluginMissing
            is Outcome.Failed -> PushStatus.Failed(new.message)
            Outcome.Registered -> if (notificationsAllowed()) PushStatus.On else PushStatus.NeedsPermission
        }
    }

    private companion object {
        const val MESSAGE_FOR_DISTRIBUTOR = "Hermes"
    }
}
