package ai.rever.boss.plugin.dynamic.secretmanager.ai

import ai.rever.boss.plugin.api.BrokeredCredentialProvider
import ai.rever.boss.plugin.api.PluginContext

/**
 * Adapts the host's [BrokeredCredentialProvider] onto the plugin-local
 * [BrokeredKeySource] seam.
 *
 * This adapter keeps host API types out of the credential store even though they predate
 * the manifest's 1.0.89 floor. The boundary still makes the store independently testable
 * and limits an incoherent host installation to AI settings registration. See AGENTS.md.
 *
 * BOSS AI uses the generic authenticated RPC API through [BossAiCredentialSource].
 * The host broker relay remains only for legacy providers such as RISA GLM.
 * Returns null only when neither facility is available.
 */
internal object BrokeredCredentialBridge {

    fun from(context: PluginContext): BrokeredKeySource? {
        val legacy = context.brokeredCredentialProvider?.let { from(it) }
        return context.supabaseDataProvider?.let { BossAiCredentialSource(it, legacy) } ?: legacy
    }

    internal fun from(provider: BrokeredCredentialProvider): BrokeredKeySource {
        return object : BrokeredKeySource {
            override val supportsSharedProviders: Boolean = true
            override fun canDiscoverSharedProviders(): Boolean =
                provider.availableBrokers().any { !it.scopedTo.isNullOrBlank() }
            override fun permitsEndpoint(brokerId: String, endpoint: String): Boolean =
                SharedProviderDefinition.withinScope(
                    endpoint, provider.availableBrokers().firstOrNull { it.id == brokerId }?.scopedTo,
                )

            override fun permitsEndpoints(brokerId: String, endpoints: List<String>): Boolean {
                val scope = provider.availableBrokers().firstOrNull { it.id == brokerId }?.scopedTo
                return endpoints.all { SharedProviderDefinition.withinScope(it, scope) }
            }

            override suspend fun fetch(brokerId: String): Result<BrokeredKey> = provider.exchange(brokerId).map { credential ->
                BrokeredKey(
                    token = credential.token,
                    refreshAfterSeconds = credential.refreshAfterSeconds,
                    // Read straight, no guard: `expiresAt` shipped in api 1.0.74 (verified in
                    // the released jar), which is the same version this whole file already
                    // requires - so on any host that can load this class the field exists. A
                    // runCatching here would be dead code implying a risk that cannot occur.
                    expiresAt = credential.expiresAt,
                )
            }
        }
    }

    /**
     * Whether [brokerId] is something this host can actually exchange with.
     *
     * Lets the panel say "not available on this host" instead of offering an action
     * that can only fail. Absent from [BrokeredCredentialProvider.availableBrokers]
     * means this host does not currently advertise that broker. BOSS's current host
     * keeps brokers listed while signed out and sets `available=false`; do not assume
     * every host implementation advertises an unchanging list at registration.
     */
    fun isAvailable(
        context: PluginContext,
        brokerId: String,
    ): Boolean {
        val provider = context.brokeredCredentialProvider ?: return false
        return provider.availableBrokers().any { it.id == brokerId && it.available }
    }
}
