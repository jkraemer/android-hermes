package net.jkraemer.hermes.core.sessions

import org.junit.Assert.assertEquals
import org.junit.Test

class SnippetTest {
    @Test
    fun markersAreStrippedAndTheMatchedSpanIsKept() {
        assertEquals(Snippet("a sourdough starter", listOf(2..10)), parseSnippet("a >>>sourdough<<< starter"))
    }

    @Test
    fun everyMatchIsKept() {
        assertEquals(Snippet("red and blue", listOf(0..2, 8..11)), parseSnippet(">>>red<<< and >>>blue<<<"))
    }

    @Test
    fun whitespaceAndNewlinesCollapseToSingleSpaces() {
        assertEquals(Snippet("one two three", emptyList()), parseSnippet("  one\n\n two \t three \n"))
    }

    @Test
    fun matchPositionsFollowTheCollapsedText() {
        assertEquals(Snippet("a b", listOf(2..2)), parseSnippet("a\n\n\n>>>b<<<"))
    }

    @Test
    fun textWithoutMarkersHasNoMatches() {
        assertEquals(Snippet("Session ID: 20260101_abc", emptyList()), parseSnippet("Session ID: 20260101_abc"))
    }

    @Test
    fun aStrayMarkerIsDropped() {
        assertEquals(Snippet("x y", emptyList()), parseSnippet("x <<<y"))
        assertEquals(Snippet("x y", listOf(2..2)), parseSnippet("x >>>y"))
    }
}
