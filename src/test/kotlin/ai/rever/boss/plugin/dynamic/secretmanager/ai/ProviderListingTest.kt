package ai.rever.boss.plugin.dynamic.secretmanager.ai

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins [isProviderListed] — the rule deciding which providers appear in the compact
 * "your providers" list versus only being reachable through "Add provider".
 *
 * The one non-obvious case is a keyless provider: [ProviderConnection.isConfigured] is
 * unconditionally true for one (there is no credential to wait on), so listing has to key
 * off something else or Ollama would appear for every user from first launch.
 */
class ProviderListingTest {
    private fun connection(providerId: String, configured: Boolean): ProviderConnection =
        ProviderConnection(
            providerId = providerId,
            apiKey = if (configured) "sk-test" else "",
            source = if (configured) CredentialSource.STORED else CredentialSource.NONE,
        )

    @Test
    fun `an ordinary provider is listed exactly when it is configured`() {
        val descriptor = ProviderRegistry.find(ProviderRegistry.OPENROUTER)!!

        assertTrue(isProviderListed(descriptor, connection(descriptor.id, configured = true), CatalogState.NotConfigured))
        assertFalse(isProviderListed(descriptor, connection(descriptor.id, configured = false), CatalogState.NotConfigured))
    }

    @Test
    fun `a keyless provider is not listed until its catalog has actually loaded`() {
        val ollama = ProviderRegistry.find(ProviderRegistry.OLLAMA)!!
        val alwaysConfigured = connection(ollama.id, configured = false) // no key stored, still isConfigured == true

        assertTrue(alwaysConfigured.isConfigured, "requiresApiKey = false should be unconditionally configured")
        assertFalse(isProviderListed(ollama, alwaysConfigured, CatalogState.NotConfigured))
        assertFalse(isProviderListed(ollama, alwaysConfigured, CatalogState.Loading))
        assertFalse(
            isProviderListed(
                ollama,
                alwaysConfigured,
                CatalogState.Failed(message = "offline"),
            ),
        )
        assertTrue(
            isProviderListed(
                ollama,
                alwaysConfigured,
                CatalogState.Loaded(models = listOf(AiModel(id = "llama3.2:latest", displayName = "llama3.2:latest")), fetchedAtEpochMs = 0L),
            ),
        )
    }

    @Test
    fun `a keyless provider the user added stays listed while its catalog is still failing`() {
        // The user with no daemon yet is the one the install flow exists for. Picking Ollama
        // from "Add provider" and closing the card must not silently undo the add.
        val ollama = ProviderRegistry.find(ProviderRegistry.OLLAMA)!!
        val alwaysConfigured = connection(ollama.id, configured = false)

        assertTrue(
            isProviderListed(
                ollama,
                alwaysConfigured,
                CatalogState.Failed(message = "Connection refused"),
                wasAddedByUser = true,
            ),
        )
        assertTrue(
            isProviderListed(ollama, alwaysConfigured, CatalogState.NotConfigured, wasAddedByUser = true),
        )
    }

    @Test
    fun `an unconfigured ordinary provider is not listed just because it was added`() {
        // The add flag only substitutes for the catalog rule, which is keyless-only. An
        // ordinary provider still earns its row by having a credential - otherwise opening
        // and cancelling the Add dropdown would leave a row that can never be used.
        val openRouter = ProviderRegistry.find(ProviderRegistry.OPENROUTER)!!

        assertFalse(
            isProviderListed(
                openRouter,
                connection(openRouter.id, configured = false),
                CatalogState.NotConfigured,
                wasAddedByUser = true,
            ),
        )
    }

    @Test
    fun `the key dialog is offered only providers that have a key to store`() {
        // ProviderRegistry.userKeyed backs the secrets section's "Add AI provider key" dialog.
        // Ollama has nothing to store; RISA GLM mints its own and must never be written down.
        val offered = ProviderRegistry.userKeyed.map { it.id }

        assertFalse(ProviderRegistry.OLLAMA in offered)
        assertFalse(ProviderRegistry.RISA_GLM in offered)
        assertTrue(ProviderRegistry.OPENROUTER in offered)
        assertTrue(ProviderRegistry.ANTHROPIC in offered)
        assertTrue(
            ProviderRegistry.userKeyed.all { it.requiresApiKey && it.brokerId == null },
            "userKeyed must hold exactly the providers a user can hand a key to",
        )
    }
}
