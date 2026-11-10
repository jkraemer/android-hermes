package net.jkraemer.hermes.core.options

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import net.jkraemer.hermes.core.chat.ChatController
import net.jkraemer.hermes.core.chat.ChatInput

/**
 * What a chat session runs on. [reasoning] is "" for Hermes' default and "none" for off. [yolo] is what Hermes
 * applies: on when the session's flag is, or when [approvalsOff] (Hermes' global approvals mode) is.
 */
data class SessionOptions(
    val model: String,
    val provider: String,
    val reasoning: String,
    val fast: Boolean,
    val yolo: Boolean,
    val approvalsOff: Boolean = false,
)

/** The fields of [SessionOptions] a call's result tells; null leaves a field as it was. */
data class OptionsPatch(
    val model: String? = null,
    val provider: String? = null,
    val reasoning: String? = null,
    val fast: Boolean? = null,
    val yolo: Boolean? = null,
)

/** The reasoning levels the options sheet offers; the display words of `/reasoning` (show, hide, ...) are not levels. */
val REASONING_LEVELS = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra")

/** The options in a session's `info` object, or null for a lazy session's, which carries no model yet. */
fun sessionOptionsFrom(info: JsonObject): SessionOptions? {
    val model = info.text("model")?.takeIf { it.isNotBlank() } ?: return null
    return SessionOptions(
        model = model,
        provider = info.text("provider").orEmpty(),
        reasoning = info.text("reasoning_effort").orEmpty(),
        fast = info.flag("fast"),
        yolo = info.flag("yolo"),
        approvalsOff = info.text("approval_mode") == "off",
    )
}

internal fun SessionOptions.patched(patch: OptionsPatch) = SessionOptions(
    model = patch.model ?: model,
    provider = patch.provider ?: provider,
    reasoning = patch.reasoning ?: reasoning,
    fast = patch.fast ?: fast,
    yolo = patch.yolo?.let { it || approvalsOff } ?: yolo,
    approvalsOff = approvalsOff,
)

sealed interface ModelSwitch {
    /** The switch took effect; [scope] is where Hermes recorded it. */
    data class Applied(val warning: String?, val model: String, val scope: String) : ModelSwitch

    /** A turn is running; Hermes applies the switch when the next one starts. */
    data class Deferred(val warning: String?, val model: String) : ModelSwitch

    /** Hermes applied nothing and asks the user to confirm first. */
    data class NeedsConfirmation(val message: String) : ModelSwitch
}

/** A typed option command's outcome; [global] when it changed Hermes' default as well as the session. */
data class OptionChange(val value: String, val global: Boolean)

private const val MODEL_CALL_TIMEOUT_MS = 60_000L
private const val GLOBAL_FLAG = "--global"
private const val PROVIDER_FLAG = "--provider"

/** Switches the session's model only; the global default is left alone. */
suspend fun ChatController.switchModel(ref: ModelRef, confirmed: Boolean = false): ModelSwitch =
    setModel("${ref.model} $PROVIDER_FLAG ${ref.provider} --session", confirmed)

/** Typed `/model <args>`: [args] go to Hermes verbatim, so its own flags (`--global`, `--session`) decide the scope. */
suspend fun ChatController.runModelCommand(args: String, confirmed: Boolean = false): ModelSwitch =
    setModel(args, confirmed)

suspend fun ChatController.setReasoning(level: String) {
    require(level in REASONING_LEVELS) { "Not a reasoning level: $level" }
    applyOptions(optionsPatchFor("reasoning", configSet(sessionParams("reasoning", level)).text("value").orEmpty()))
}

suspend fun ChatController.setFast(on: Boolean) {
    val reported = configSet(sessionParams("fast", if (on) "fast" else "normal")).text("value").orEmpty()
    applyOptions(OptionsPatch(fast = reported in FAST_WORDS))
}

/** Sets YOLO with an explicit value: Hermes toggles on any other word. */
suspend fun ChatController.setYolo(on: Boolean) {
    applyOptions(optionsPatchFor("yolo", configSet(sessionParams("yolo", if (on) "1" else "0")).text("value").orEmpty()))
}

/**
 * Typed `/reasoning`, `/fast` or `/yolo` with [arg] ([key] is "reasoning", "fast" or "yolo"). A `--global` token
 * makes the change Hermes' default as well as the session's; without it only the session changes.
 */
suspend fun ChatController.runOptionCommand(key: String, arg: String): OptionChange {
    val words = arg.trim().split(WHITESPACE).filter { it.isNotEmpty() }
    val global = GLOBAL_FLAG in words
    val value = words.filter { it != GLOBAL_FLAG }.joinToString(" ")
    // Hermes ignores a fast scope; the default is written by a call without a session.
    if (global && key == "fast") configSet(buildJsonObject { put("key", key); put("value", value) })
    val result = configSet(buildJsonObject {
        put("key", key)
        put("session_id", liveSessionId())
        put("value", value)
        if (global && key != "fast") put("scope", "global")
    })
    val reported = result.text("value").orEmpty()
    applyOptions(optionsPatchFor(key, reported))
    return OptionChange(reported, global)
}

/** The reported values of a service tier faster than normal. */
private val FAST_WORDS = setOf("fast", "ultrafast")

private val WHITESPACE = Regex("\\s+")

/** What a reported option value means for the options; display words (`/reasoning show`) change none. */
private fun optionsPatchFor(key: String, reported: String) = when (key) {
    "reasoning" -> OptionsPatch(reasoning = reported.takeIf { it in REASONING_LEVELS })
    "fast" -> OptionsPatch(fast = when (reported) {
        in FAST_WORDS -> true
        "normal" -> false
        else -> null
    })
    "yolo" -> OptionsPatch(yolo = when (reported) {
        "1" -> true
        "0" -> false
        else -> null
    })
    else -> OptionsPatch()
}

private suspend fun ChatController.setModel(value: String, confirmed: Boolean): ModelSwitch {
    val result = configSet(
        buildJsonObject {
            put("key", "model")
            put("session_id", liveSessionId())
            put("value", value)
            if (confirmed) put("confirm_expensive_model", true)
        },
        MODEL_CALL_TIMEOUT_MS,
    )
    if (result.flag("confirm_required")) return ModelSwitch.NeedsConfirmation(result.text("confirm_message").orEmpty())
    val model = result.text("value").orEmpty()
    val warning = result.text("warning")?.takeIf { it.isNotBlank() }
    val words = value.split(WHITESPACE)
    val provider = words.indexOf(PROVIDER_FLAG).takeIf { it >= 0 }?.let { words.getOrNull(it + 1) }
    applyOptions(OptionsPatch(model = model, provider = provider))
    return if (result.flag("deferred")) ModelSwitch.Deferred(warning, model)
    else ModelSwitch.Applied(warning, model, result.text("scope").orEmpty())
}

private fun ChatController.sessionParams(key: String, value: String) = buildJsonObject {
    put("key", key)
    put("session_id", liveSessionId())
    put("value", value)
}

private fun ChatController.applyOptions(patch: OptionsPatch) = feed(ChatInput.OptionsChanged(patch))

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true
