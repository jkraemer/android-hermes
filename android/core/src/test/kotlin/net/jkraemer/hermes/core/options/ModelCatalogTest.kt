package net.jkraemer.hermes.core.options

import net.jkraemer.hermes.core.chat.obj
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {
    private val p = "p"

    @Test
    fun parsesProvidersModelsAndCapabilities() {
        val catalog = modelCatalogFrom(
            obj(
                """{"providers":[{"slug":"p","name":"Provider P","authenticated":true,"models":["a","b"],
                "capabilities":{"a":{"fast":true,"reasoning":false,"can_disable_reasoning":false}}}]}""",
            ),
        )

        assertEquals(listOf(OfferedProvider("p", "Provider P", true, listOf("a", "b"))), catalog.providers)
        assertTrue(catalog.offers(ModelRef(p, "a")))
        assertFalse(catalog.offers(ModelRef(p, "c")))
        assertFalse(catalog.offers(ModelRef("q", "a")))
        assertEquals(ModelCapabilities(reasoning = false, canDisableReasoning = false, fast = true), catalog.capabilities(ModelRef(p, "a")))
    }

    @Test
    fun unauthenticatedProviderOffersNothing() {
        val catalog = modelCatalogFrom(obj("""{"providers":[{"slug":"p","name":"P","authenticated":false,"models":["a"]}]}"""))

        assertFalse(catalog.offers(ModelRef(p, "a")))
    }

    @Test
    fun providerWithoutAuthenticatedFlagCountsAsAuthenticated() {
        val catalog = modelCatalogFrom(obj("""{"providers":[{"slug":"p","name":"P","models":["a"]}]}"""))

        assertTrue(catalog.offers(ModelRef(p, "a")))
    }

    @Test
    fun favoritesOfferedSkipsRetired() {
        val catalog = modelCatalogFrom(obj("""{"providers":[{"slug":"p","name":"P","authenticated":true,"models":["a","b"]}]}"""))
        val favorites = listOf(ModelRef("p", "a"), ModelRef("q", "x"), ModelRef("p", "b"))

        assertEquals(listOf(ModelRef("p", "a"), ModelRef("p", "b")), favoritesOffered(favorites, catalog))
    }

    @Test
    fun missingCapabilitiesUseDefaults() {
        val catalog = modelCatalogFrom(obj("""{"providers":[{"slug":"p","name":"P","authenticated":true,"models":["a","b"],"capabilities":{"b":{}}}]}"""))
        val defaults = ModelCapabilities(reasoning = true, canDisableReasoning = true, fast = false)

        assertEquals(defaults, catalog.capabilities(ModelRef(p, "a")))
        assertEquals(defaults, catalog.capabilities(ModelRef(p, "b")))
        assertEquals(defaults, catalog.capabilities(ModelRef("q", "zz")))
    }

    @Test
    fun malformedEntriesAreSkipped() {
        val catalog = modelCatalogFrom(
            obj(
                """{"providers":[{"name":"no slug","models":["x"]},"junk",
                {"slug":"p","name":"P","authenticated":true,"models":["a",3,null,{"id":"b"}],"capabilities":{"a":"bad"}}]}""",
            ),
        )

        assertEquals(listOf(OfferedProvider("p", "P", true, listOf("a"))), catalog.providers)
        assertEquals(ModelCapabilities(true, true, false), catalog.capabilities(ModelRef(p, "a")))
    }

    @Test
    fun missingProvidersGiveEmptyCatalog() {
        assertEquals(emptyList<OfferedProvider>(), modelCatalogFrom(obj("{}")).providers)
    }
}
