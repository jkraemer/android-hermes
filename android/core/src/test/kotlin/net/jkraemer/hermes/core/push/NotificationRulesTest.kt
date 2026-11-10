package net.jkraemer.hermes.core.push

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationRulesTest {
    @Test
    fun offAllowsNothingAndAlwaysAllowsEverything() {
        assertFalse(NotificationRule.Off.allows("anything"))
        assertTrue(NotificationRule.Always.allows(""))
    }

    @Test
    fun matchAllowsOutputContainingThePattern() {
        val rule = NotificationRule.Match("err(or)?\\b")
        assertTrue(rule.allows("all fine\nan error occurred"))
        assertFalse(rule.allows("all fine"))
    }

    @Test
    fun invalidPatternAllows() {
        assertTrue(NotificationRule.Match("(unclosed").allows("anything"))
    }

    @Test
    fun validPatternTellsCompilableFromBroken() {
        assertTrue(validPattern("a+b"))
        assertFalse(validPattern("(unclosed"))
    }
}
