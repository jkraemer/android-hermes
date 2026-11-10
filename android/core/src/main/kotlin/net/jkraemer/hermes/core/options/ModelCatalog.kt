package net.jkraemer.hermes.core.options

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import net.jkraemer.hermes.core.chat.RpcPeer

/** A model as Hermes addresses it: the [provider]'s slug and the [model] id. */
data class ModelRef(val provider: String, val model: String)

/** What a model supports; what Hermes does not report is assumed to be the common case. */
data class ModelCapabilities(val reasoning: Boolean, val canDisableReasoning: Boolean, val fast: Boolean)

private val DEFAULT_CAPABILITIES = ModelCapabilities(reasoning = true, canDisableReasoning = true, fast = false)

/** A provider in Hermes' model list; [authenticated] is false for rows that only advertise a provider the user has not set up. */
data class OfferedProvider(val slug: String, val name: String, val authenticated: Boolean, val models: List<String>)

/** The models Hermes offers right now, with what each supports. */
class ModelCatalog(
    val providers: List<OfferedProvider>,
    private val capabilities: Map<ModelRef, ModelCapabilities> = emptyMap(),
) {
    /** Whether [ref] can be chosen: its provider is set up and lists the model. */
    fun offers(ref: ModelRef): Boolean =
        providers.any { it.authenticated && it.slug == ref.provider && ref.model in it.models }

    fun capabilities(ref: ModelRef): ModelCapabilities = capabilities[ref] ?: DEFAULT_CAPABILITIES
}

fun modelCatalogFrom(result: JsonObject): ModelCatalog {
    val capabilities = mutableMapOf<ModelRef, ModelCapabilities>()
    val providers = (result["providers"] as? JsonArray).orEmpty().mapNotNull { row ->
        val provider = row as? JsonObject ?: return@mapNotNull null
        val slug = provider["slug"].text() ?: return@mapNotNull null
        (provider["capabilities"] as? JsonObject).orEmpty().forEach { (model, entry) ->
            (entry as? JsonObject)?.let { capabilities[ModelRef(slug, model)] = it.toCapabilities() }
        }
        OfferedProvider(
            slug = slug,
            name = provider["name"].text() ?: slug,
            authenticated = provider["authenticated"].flag() ?: true,
            models = (provider["models"] as? JsonArray).orEmpty().mapNotNull { it.text() },
        )
    }
    return ModelCatalog(providers, capabilities)
}

/** The catalogue as seen from [sessionId]'s session (its live agent's models); the default one without. */
suspend fun RpcPeer.modelCatalog(sessionId: String?): ModelCatalog =
    modelCatalogFrom(
        call("model.options", buildJsonObject { sessionId?.let { put("session_id", it) } }) as? JsonObject ?: JsonObject(emptyMap()),
    )

/** The [favorites] the [catalog] still offers, in their order. */
fun favoritesOffered(favorites: List<ModelRef>, catalog: ModelCatalog): List<ModelRef> = favorites.filter(catalog::offers)

private fun JsonObject.toCapabilities() = ModelCapabilities(
    reasoning = this["reasoning"].flag() ?: DEFAULT_CAPABILITIES.reasoning,
    canDisableReasoning = this["can_disable_reasoning"].flag() ?: DEFAULT_CAPABILITIES.canDisableReasoning,
    fast = this["fast"].flag() ?: DEFAULT_CAPABILITIES.fast,
)

private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonElement?.flag(): Boolean? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
