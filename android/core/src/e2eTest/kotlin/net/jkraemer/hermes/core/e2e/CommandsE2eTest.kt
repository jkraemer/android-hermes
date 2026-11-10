package net.jkraemer.hermes.core.e2e

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.jkraemer.hermes.core.chat.ChatItem
import net.jkraemer.hermes.core.commands.commandCatalog
import net.jkraemer.hermes.core.commands.parseSlashCommand
import net.jkraemer.hermes.core.commands.runCommand
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CommandsE2eTest {
    @get:Rule
    val folder = TemporaryFolder()

    private var client: E2eClient? = null

    @After
    fun tearDown() {
        client?.close()
    }

    @Test
    fun catalogListsTitleAndSkills() = runBlocking {
        val app = E2eClient.connect(folder.newFile()).also { client = it }
        val chat = app.newChat().also { it.startNew() }
        val liveId = chat.state.value.liveSessionId
        assertNotNull("no live session", liveId)

        val catalog = app.rpc.commandCatalog(liveId)

        val title = catalog.entries.singleOrNull { it.name == "/title" }
        assertNotNull("/title missing: ${catalog.entries.map { it.name }}", title)
        assertTrue("/title is in the Skills group", title!!.group != "Skills")
        assertTrue("no skills: ${catalog.entries}", catalog.entries.any { it.group == "Skills" })
        assertEquals("/title", catalog.canonical("/TITLE"))
    }

    @Test
    fun statusRunsAndSkillDispatchReturnsSkill() = runBlocking {
        val app = E2eClient.connect(folder.newFile()).also { client = it }
        val chat = app.newChat().also { it.startNew() }
        val liveId = chat.state.value.liveSessionId!!
        val catalog = app.rpc.commandCatalog(liveId)

        chat.runCommand(parseSlashCommand("/status")!!, catalog)

        val notice = chat.state.value.items.last()
        assertTrue("no notice: ${chat.state.value.items}", notice is ChatItem.Notice && notice.text.isNotBlank())
        val skill = catalog.entries.first { it.group == "Skills" }.name.removePrefix("/")
        val dispatched = app.rpc.call("command.dispatch", buildJsonObject {
            put("session_id", liveId)
            put("name", skill)
        }).jsonObject
        assertEquals("skill", dispatched["type"]?.jsonPrimitive?.content)
        assertTrue("blank display: $dispatched", dispatched["display"]?.jsonPrimitive?.content.orEmpty().isNotBlank())
    }
}
