package net.jkraemer.hermes.push

import android.content.pm.PackageManager
import android.content.pm.PackageManager.GET_SERVICES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
class PushManifestTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun notificationsNeedTheRuntimePermission() {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)

        assertTrue("android.permission.POST_NOTIFICATIONS" in info.requestedPermissions!!)
    }

    @Test
    fun theUnifiedPushServiceIsNotExported() {
        val info = context.packageManager.getPackageInfo(context.packageName, GET_SERVICES)

        val service = info.services!!.single { it.name == HermesPushService::class.java.name }
        assertFalse(service.exported)
    }

    @Test
    fun theUnifiedPushServiceReceivesPushEvents() {
        val intent = android.content.Intent("org.unifiedpush.android.connector.PUSH_EVENT").setPackage(context.packageName)

        val services = context.packageManager.queryIntentServices(intent, 0)

        assertEquals(listOf(HermesPushService::class.java.name), services.map { it.serviceInfo.name })
    }

    /** The `domain:path` of every exclusion in the XML resource [id]. */
    private fun exclusions(id: Int): Set<String> {
        val parser = context.resources.getXml(id)
        val found = mutableSetOf<String>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "exclude") {
                found += "${parser.getAttributeValue(null, "domain")}:${parser.getAttributeValue(null, "path")}"
            }
        }
        return found
    }

    private val connectorState = setOf(
        "database:unifiedpush-connector", "database:unifiedpush-connector-journal", "sharedpref:unifiedpush.connector.xml",
    )

    @Test
    fun backupRulesExcludeTheConnectorState() {
        assertTrue(exclusions(net.jkraemer.hermes.R.xml.backup_rules).containsAll(connectorState))
    }

    @Test
    fun dataExtractionRulesExcludeTheConnectorState() {
        assertTrue(exclusions(net.jkraemer.hermes.R.xml.data_extraction_rules).containsAll(connectorState))
    }
}
