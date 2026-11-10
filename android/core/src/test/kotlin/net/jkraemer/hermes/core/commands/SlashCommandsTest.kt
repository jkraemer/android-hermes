package net.jkraemer.hermes.core.commands

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlashCommandsTest {
    private val catalog = commandCatalogFrom(
        Json.parseToJsonElement(
            """
            {
              "pairs": [["/arxiv", "Search arXiv"], ["/airflow", "Run Airflow"]],
              "canon": {
                "/title": "/title", "/compress": "/compress", "/compact": "/compress", "/clear": "/clear",
                "/model": "/model", "/reasoning": "/reasoning", "/fast": "/fast", "/yolo": "/yolo",
                "/personality": "/personality", "/persona": "/personality", "/approvals": "/approvals",
                "/bg": "/bg", "/background": "/bg", "/btw": "/btw",
                "/density": "/density", "/logs": "/logs", "/mouse": "/mouse", "/sessions": "/sessions"
              },
              "commands": {
                "/clear": {"argument_mode": null, "desktop": "terminal"},
                "/title": {"argument_mode": "text", "desktop": null}
              },
              "categories": [
                {"name": "Session", "pairs": [
                  ["/title", "Set a title (usage: /title [name])"],
                  ["/clear", "Clear the screen"],
                  ["/compress", "Compress the context"],
                  ["/bg", "Run in background"],
                  ["/btw", "Side question"]
                ]},
                {"name": "Configuration", "pairs": [
                  ["/model", "Switch model"], ["/reasoning", "Reasoning"], ["/fast", "Fast"], ["/yolo", "YOLO"],
                  ["/personality", "Personality"], ["/approvals", "Approvals"]
                ]},
                {"name": "TUI", "pairs": [
                  ["/density", "Density"], ["/logs", "Logs"], ["/mouse", "Mouse"], ["/sessions", "Sessions"]
                ]}
              ],
              "skills": {"/arxiv": {"usage": 1, "origin": "bundled"}, "/airflow": {"usage": 0, "origin": "bundled"}}
            }
            """.trimIndent(),
        ).jsonObject,
    )

    private val names get() = catalog.entries.map { it.name }

    @Test
    fun parsesNameAndArg() {
        assertEquals(SlashCommand("/title", "My chat"), parseSlashCommand("/Title  My chat "))
        assertEquals(SlashCommand("/title", ""), parseSlashCommand("  /title"))
    }

    @Test
    fun pathLikeTextIsNotACommand() {
        assertNull(catalog.canonical("/etc/hosts"))
        assertEquals(SlashCommand("/etc/hosts", ""), parseSlashCommand("/etc/hosts"))
        assertNull(parseSlashCommand("/"))
        assertNull(parseSlashCommand("/ title"))
        assertNull(parseSlashCommand("hello /x"))
    }

    @Test
    fun menuExcludesTerminalHiddenTuiAndOptionCommands() {
        val excluded = listOf("/clear", "/model", "/reasoning", "/fast", "/yolo", "/bg", "/background", "/btw", "/density", "/logs", "/mouse", "/sessions")
        assertEquals(emptyList<String>(), names.filter { it in excluded })
        assertTrue("/title" in names)
    }

    @Test
    fun optionCommandsAreStillKnown() {
        assertEquals("/yolo", catalog.canonical("/yolo"))
        assertEquals("/clear", catalog.canonical("/clear"))
    }

    @Test
    fun globalConfigCommandsAreUnknown() {
        assertNull(catalog.canonical("/personality"))
        assertNull(catalog.canonical("/persona"))
        assertNull(catalog.canonical("/approvals"))
        assertFalse("/personality" in names)
        assertFalse("/approvals" in names)
    }

    @Test
    fun skillsFormTheirOwnGroup() {
        val arxiv = catalog.entries.single { it.name == "/arxiv" }
        assertEquals(SlashEntry("/arxiv", "Search arXiv", "Skills"), arxiv)
        assertEquals("/arxiv", catalog.canonical("/arxiv"))
        assertEquals(listOf("/title", "/compress", "/airflow", "/arxiv"), names)
    }

    @Test
    fun aliasResolvesToCanonical() {
        assertEquals("/compress", catalog.canonical("/COMPACT"))
        assertEquals(1, names.count { it == "/compress" })
        assertFalse("/compact" in names)
    }

    @Test
    fun matchingFiltersByPrefix() {
        assertEquals(listOf("/title"), catalog.matching("ti").map { it.name })
        assertEquals(listOf("/arxiv"), catalog.matching("AR").map { it.name })
        assertEquals(catalog.entries, catalog.matching(""))
    }

    @Test
    fun missingFieldsGiveEmptyCatalog() {
        val empty = commandCatalogFrom(JsonObject(emptyMap()))
        assertTrue(empty.entries.isEmpty())
        assertNull(empty.canonical("/title"))
    }
}
