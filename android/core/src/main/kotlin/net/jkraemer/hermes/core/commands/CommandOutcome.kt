package net.jkraemer.hermes.core.commands

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import net.jkraemer.hermes.core.chat.ChatController
import net.jkraemer.hermes.core.chat.ChatInput
import net.jkraemer.hermes.core.chat.ChatItem
import net.jkraemer.hermes.core.options.ModelSwitch
import net.jkraemer.hermes.core.options.runModelCommand
import net.jkraemer.hermes.core.options.runOptionCommand
import net.jkraemer.hermes.core.rpc.RpcException

/** What the screen does once a command has run; everything Hermes said is already in the chat as notices. */
sealed interface CommandOutcome {
    data object Done : CommandOutcome
    data object OpenOptions : CommandOutcome
    data object NewChat : CommandOutcome

    /** Put [text] into the composer. */
    data class Prefill(val text: String) : CommandOutcome

    /** Switching to the model of `/model [args]` needs the user's go-ahead first; [message] says why. */
    data class ConfirmModel(val args: String, val message: String) : CommandOutcome
}

private const val EXEC_TIMEOUT_MS = 60_000L
private const val SKILL_COMMAND_ERROR = 4018
private const val MAX_ALIAS_HOPS = 3
private const val SKILL_DISPLAY_KIND = "skill_invocation"

/**
 * Runs [command]: `/title`, `/new`, `/stop` and the option commands locally, the rest through Hermes'
 * `slash.exec`, with `command.dispatch` for what it answers is a skill. A failure becomes an error item.
 */
suspend fun ChatController.runCommand(command: SlashCommand, catalog: CommandCatalog): CommandOutcome =
    try {
        execute(command, command, catalog, hops = 0)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        show(ChatItem.Error(e.message ?: "Command failed"))
        CommandOutcome.Done
    }

private suspend fun ChatController.execute(command: SlashCommand, typed: SlashCommand, catalog: CommandCatalog, hops: Int): CommandOutcome {
    val name = catalog.canonical(command.name) ?: command.name
    return when {
        name == "/title" -> setTitle(command.arg)
        name == "/new" -> CommandOutcome.NewChat
        name == "/stop" -> CommandOutcome.Done.also { interrupt() }
        name in OPTIONS_COMMANDS && command.arg.isEmpty() -> CommandOutcome.OpenOptions
        name == "/model" -> runModel(command.arg)
        name in OPTIONS_COMMANDS -> CommandOutcome.Done.also { runOption(name.removePrefix("/"), command.arg) }
        else -> exec(SlashCommand(name, command.arg), typed, catalog, hops)
    }
}

private suspend fun ChatController.setTitle(title: String): CommandOutcome {
    if (title.isEmpty()) show(ChatItem.Notice("Usage: /title <name>"))
    else call("session.title", buildJsonObject {
        put("session_id", liveSessionId())
        put("title", title)
    })
    return CommandOutcome.Done
}

private suspend fun ChatController.runModel(args: String): CommandOutcome {
    when (val switch = runModelCommand(args)) {
        is ModelSwitch.NeedsConfirmation -> return CommandOutcome.ConfirmModel(args, switch.message)
        is ModelSwitch.Applied -> {
            show(ChatItem.Notice("Model: ${switch.model}" + if (switch.scope.isEmpty()) "" else " (${switch.scope})"))
            switch.warning?.let { show(ChatItem.Notice(it)) }
        }
        is ModelSwitch.Deferred -> {
            show(ChatItem.Notice("Model: ${switch.model} from the next message"))
            switch.warning?.let { show(ChatItem.Notice(it)) }
        }
    }
    return CommandOutcome.Done
}

private suspend fun ChatController.runOption(key: String, arg: String) {
    val change = runOptionCommand(key, arg)
    val label = if (key == "fast") "Fast mode" else if (key == "yolo") "YOLO" else "Reasoning"
    show(ChatItem.Notice("$label: ${change.value}" + if (change.global) " (global default)" else ""))
}

/** [typed] is the command the user ran, which names an alias loop. */
private suspend fun ChatController.exec(command: SlashCommand, typed: SlashCommand, catalog: CommandCatalog, hops: Int): CommandOutcome {
    val bareName = command.name.removePrefix("/")
    val result = try {
        call("slash.exec", buildJsonObject {
            put("session_id", liveSessionId())
            put("command", "$bareName ${command.arg}".trim())
        }, EXEC_TIMEOUT_MS)
    } catch (e: RpcException) {
        if (e.error.code != SKILL_COMMAND_ERROR) throw e
        call("command.dispatch", buildJsonObject {
            put("session_id", liveSessionId())
            put("name", bareName)
            put("arg", command.arg)
        })
    }
    return directive(result, command, typed, catalog, hops)
}

/** Does what a `slash.exec` or `command.dispatch` [result] asks: show its output, send its message, follow its alias. */
private suspend fun ChatController.directive(result: JsonObject, command: SlashCommand, typed: SlashCommand, catalog: CommandCatalog, hops: Int): CommandOutcome {
    val message = result.text("message")
    when (result.text("type")) {
        "alias" -> {
            val target = result.text("target") ?: return CommandOutcome.Done
            if (hops >= MAX_ALIAS_HOPS) show(ChatItem.Notice("Command alias loop: ${typed.name}"))
            else return execute(SlashCommand("/${target.removePrefix("/")}", command.arg), typed, catalog, hops + 1)
        }
        "send" -> {
            result.text("notice")?.let { show(ChatItem.Notice(it)) }
            message?.let { submitShown(it) }
        }
        "skill" -> message?.let { submitShown(it, shownAs = result.text("display") ?: command.name, displayKind = SKILL_DISPLAY_KIND) }
        "prefill" -> return CommandOutcome.Prefill(message.orEmpty())
        else -> {
            result.text("output")?.let { show(ChatItem.Notice(it)) }
            result.text("warning")?.let { show(ChatItem.Notice(it)) }
        }
    }
    return CommandOutcome.Done
}

/** A failed submit is already an error item in the chat; it is not reported twice. */
private suspend fun ChatController.submitShown(text: String, shownAs: String = text, displayKind: String? = null) {
    try {
        submit(text, shownAs, displayKind)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
    }
}

private fun ChatController.show(item: ChatItem) = feed(ChatInput.ItemAdded(item))

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
