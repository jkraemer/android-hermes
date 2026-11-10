package net.jkraemer.hermes.push

import android.app.Activity
import android.content.Context
import org.unifiedpush.android.connector.UnifiedPush

/** The UnifiedPush connector's distributor handling. */
object UnifiedPushDistributor : PushDistributor {
    override fun useCurrentOrDefault(activity: Activity, onResult: (Boolean) -> Unit) =
        UnifiedPush.tryUseCurrentOrDefaultDistributor(activity) { onResult(it) }

    override fun pick(activity: Activity, onResult: (Boolean) -> Unit) =
        UnifiedPush.tryPickDistributor(activity) { onResult(it) }

    override fun register(context: Context, messageForDistributor: String) =
        UnifiedPush.register(context, messageForDistributor = messageForDistributor)
}
