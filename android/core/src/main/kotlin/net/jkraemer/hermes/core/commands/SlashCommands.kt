package net.jkraemer.hermes.core.commands

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import net.jkraemer.hermes.core.chat.RpcPeer

/** A command in the slash menu; [name] carries its leading slash. */
data class SlashEntry(val name: String, val description: String, val group: String)

/** A typed slash command: [name] lowercased with its leading slash, [arg] trimmed, "" when absent. */
data class SlashCommand(val name: String, val arg: String)

/** Commands the options sheet covers; the menu leaves them out, but they stay known to the app. */
val OPTIONS_COMMANDS = setOf("/model", "/reasoning", "/fast", "/yolo")

/** Commands that write Hermes' global config; to the app they are plain text, not commands. */
val GLOBAL_CONFIG_COMMANDS = setOf("/personality", "/approvals", "/skin", "/voice", "/login")

private const val SKILLS_GROUP = "Skills"
private const val TUI_CATEGORY = "TUI"

/** Commands whose result never reaches the chat when run through `slash.exec`. */
private val RESULT_LOST_COMMANDS = setOf("/bg", "/background", "/btw")

/** The text as a slash command, or null unless it starts (after leading space) with a slash and a name. */
fun parseSlashCommand(text: String): SlashCommand? {
    val trimmed = text.trimStart()
    if (trimmed.length < 2 || trimmed[0] != '/' || trimmed[1].isWhitespace()) return null
    val name = trimmed.takeWhile { !it.isWhitespace() }
    return SlashCommand(name.lowercase(), trimmed.substring(name.length).trim())
}

/** What Hermes offers as slash commands: the menu [entries] and the names the app treats as commands. */
class CommandCatalog internal constructor(val entries: List<SlashEntry>, private val canonicalNames: Map<String, String>) {
    /** The entries whose name, without the slash, starts with [typed] (what follows the slash up to the first space). */
    fun matching(typed: String): List<SlashEntry> =
        entries.filter { it.name.drop(1).startsWith(typed, ignoreCase = true) }

    /** The canonical name of the command or alias [name], or null when it is not a command to the app. */
    fun canonical(name: String): String? = canonicalNames[name.lowercase()]
}

fun commandCatalogFrom(result: JsonObject): CommandCatalog {
    val canon = (result["canon"] as? JsonObject).orEmpty().mapNotNull { (alias, target) ->
        target.text()?.let { alias.lowercase() to it }
    }.toMap().filterValues { it !in GLOBAL_CONFIG_COMMANDS }
    val skills = (result["skills"] as? JsonObject)?.keys.orEmpty()
    val pluginCommands = (result["plugin_commands"] as? JsonObject)?.keys.orEmpty().map { "/$it" }
    val descriptions = result["pairs"].pairs().toMap()
    val meta = result["commands"] as? JsonObject

    fun inMenu(key: String): Boolean {
        val target = canon[key.lowercase()] ?: key
        return target !in GLOBAL_CONFIG_COMMANDS && target !in OPTIONS_COMMANDS && target !in RESULT_LOST_COMMANDS &&
            (meta?.get(key) as? JsonObject)?.get("desktop").text() !in setOf("hidden", "terminal")
    }

    val categoryEntries = (result["categories"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.flatMap { category ->
        val group = category["name"].text() ?: return@flatMap emptyList()
        if (group == TUI_CATEGORY) return@flatMap emptyList()
        category["pairs"].pairs().filter { (key, _) -> inMenu(key) }.map { (key, description) -> SlashEntry(key, description, group) }
    }
    val skillEntries = skills.sorted().filter(::inMenu).map { SlashEntry(it, descriptions[it].orEmpty(), SKILLS_GROUP) }

    val known = canon + (skills + pluginCommands).associate { it.lowercase() to it }
    return CommandCatalog(categoryEntries + skillEntries, known)
}

/** The catalogue of [sessionId]'s session (its skills depend on the session's directory); the default one without. */
suspend fun RpcPeer.commandCatalog(sessionId: String?): CommandCatalog =
    commandCatalogFrom(call("commands.catalog", buildJsonObject { sessionId?.let { put("session_id", it) } }) as JsonObject)

private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

/** The `[key, description]` rows of a catalogue `pairs` array. */
private fun JsonElement?.pairs(): List<Pair<String, String>> = (this as? JsonArray).orEmpty().mapNotNull { row ->
    val cells = row as? JsonArray ?: return@mapNotNull null
    val key = cells.getOrNull(0).text() ?: return@mapNotNull null
    key to cells.getOrNull(1).text().orEmpty()
}
