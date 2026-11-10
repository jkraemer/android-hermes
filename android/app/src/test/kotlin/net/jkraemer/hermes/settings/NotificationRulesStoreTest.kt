package net.jkraemer.hermes.settings

import android.content.Context
import net.jkraemer.hermes.core.push.NotificationRule
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class NotificationRulesStoreTest {
    private val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("rules-test", Context.MODE_PRIVATE)

    @Test
    fun aJobWithoutARuleIsAnnouncedAlways() {
        val store = NotificationRulesStore(prefs)

        assertEquals(NotificationRule.Always, store.ruleFor("j1"))
        assertEquals(emptyMap<String, NotificationRule>(), store.rules.value)
    }

    @Test
    fun eachRuleSurvivesANewStore() {
        val store = NotificationRulesStore(prefs)
        store.set("j1", NotificationRule.Off)
        store.set("j2", NotificationRule.Match("ALERT: (\\d+)"))
        store.set("j3", NotificationRule.Always)

        val reopened = NotificationRulesStore(prefs)

        assertEquals(NotificationRule.Off, reopened.ruleFor("j1"))
        assertEquals(NotificationRule.Match("ALERT: (\\d+)"), reopened.ruleFor("j2"))
        assertEquals(NotificationRule.Always, reopened.ruleFor("j3"))
        assertEquals(store.rules.value, reopened.rules.value)
    }

    @Test
    fun aRuleIsStoredPerJobAsText() {
        NotificationRulesStore(prefs).apply {
            set("j1", NotificationRule.Off)
            set("j2", NotificationRule.Match("a:b"))
            set("j3", NotificationRule.Always)
        }

        assertEquals("off", prefs.getString("notification_rule:j1", null))
        assertEquals("match:a:b", prefs.getString("notification_rule:j2", null))
        assertEquals("always", prefs.getString("notification_rule:j3", null))
    }

    @Test
    fun theRulesFlowFollowsChanges() {
        val store = NotificationRulesStore(prefs)
        store.set("j1", NotificationRule.Off)
        store.set("j1", NotificationRule.Match("x"))

        assertEquals(mapOf("j1" to NotificationRule.Match("x")), store.rules.value)
    }

    @Test
    fun aDamagedValueCountsAsAlways() {
        prefs.edit().putString("notification_rule:j1", "bogus").putInt("notification_rule:j2", 7).apply()

        val store = NotificationRulesStore(prefs)

        assertEquals(NotificationRule.Always, store.ruleFor("j1"))
        assertEquals(NotificationRule.Always, store.ruleFor("j2"))
    }
}
