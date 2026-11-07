package net.jkraemer.hermes.core.cron

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The cases of Hermes' tests/gateway/test_response_filters.py and tests/cron/test_scheduler.py (TestSilentDelivery). */
class SilenceTest {
    private fun assertSilent(text: String) = assertTrue("\"$text\" should be silent", isSilent(text))
    private fun assertNotSilent(text: String) = assertFalse("\"$text\" should not be silent", isSilent(text))

    @Test
    fun exactMarkersAreSilent() {
        for (marker in listOf("[SILENT]", " SILENT ", "NO_REPLY", "no reply", "NO REPLY", "no_reply", "SILENT")) assertSilent(marker)
    }

    @Test
    fun aMarkerWithANoteOnItsOwnLineIsSilent() {
        assertSilent("[SILENT]\n\nNothing new this tick.")
        assertSilent("2 deals filtered\n\n[SILENT]")
        assertSilent("2 deals filtered out (like<10, reply<15).\n\n[SILENT]")
        assertSilent("no_reply\nduplicate inbound, already handled")
    }

    @Test
    fun theBracketedMarkerOpeningTheResponseIsSilent() {
        assertSilent("[SILENT] No changes detected")
        assertSilent("[silent] nothing new")
    }

    @Test
    fun translatedMarkersAreSilentInEveryFormTheEnglishOneIs() {
        assertSilent("[静默]")
        assertSilent("**沉默**")
        assertSilent("[静默]\n\nNothing new this tick.")
        assertSilent("2 deals filtered\n\n[沉默]")
        assertSilent("静默")
    }

    @Test
    fun edgePunctuationAroundAMarkerIsIgnored() {
        for (variant in listOf("【静默】", "静默。", "【沉默】", "沉默。", "**[静默]**", "NO_REPLY.", ".NO_REPLY", "*NO_REPLY*")) assertSilent(variant)
    }

    @Test
    fun aMarkerMidSentenceIsNotSilent() {
        assertNotSilent("I considered staying [SILENT] but here is the summary: 3 items merged.")
        assertNotSilent("the lane said 静默 mid-sentence and kept talking")
        assertNotSilent("status: 静默 means the lane is quiet")
    }

    @Test
    fun onlyTheBracketedMarkerCountsAsAPrefix() {
        assertNotSilent("Silent retry succeeded")
        assertNotSilent("NO_REPLY needed here, the report follows")
    }

    @Test
    fun squareBracketsAreStructuralSoAMalformedMarkerIsNotSilent() {
        assertNotSilent("[SILENT")
        assertNotSilent("SILENT]")
    }

    @Test
    fun blankOrOrdinaryTextIsNotSilent() {
        assertNotSilent("")
        assertNotSilent("   \n ")
        assertNotSilent("All quiet on the western front.")
    }

    @Test
    fun aLineThatOnlyEndsInAMarkerIsNotSilent() {
        assertNotSilent("This report is quite long and ends with the words no reply")
    }

    @Test
    fun aMarkerInMoreThan64CharactersOfPunctuationIsNotSilent() {
        assertSilent("*".repeat(29) + "SILENT" + "*".repeat(29))
        assertNotSilent("*".repeat(30) + "SILENT" + "*".repeat(30))
    }
}
