package ai.rever.boss.plugin.dynamic.secretmanager.ai

import ai.rever.boss.plugin.api.AiCliHealth
import ai.rever.boss.plugin.api.AiCliSessionAPI
import ai.rever.boss.plugin.api.PluginContext

/**
 * [CliEngineAccess] backed by the AI Gateway plugin.
 *
 * **The only file in this plugin that names `AiCliSessionAPI`**. The type predates the
 * manifest floor, but the adapter keeps the optional gateway boundary narrow and testable.
 *
 * The gateway is resolved **per call, never cached**. Plugin load order is not guaranteed, so
 * a value read once would usually be null forever - and the gateway can be installed, updated
 * or hot-reloaded while this panel is open, which is exactly when a settings page needs to
 * notice.
 */
internal class GatewayCliEngineAccess private constructor(
    private val context: PluginContext,
) : CliEngineAccess {

    private fun api(): AiCliSessionAPI? =
        runCatching { context.getPluginAPI(AiCliSessionAPI::class.java) }.getOrNull()

    override fun engines(): List<CliEngineInfo> =
        runCatching {
            api()?.engines()?.map {
                CliEngineInfo(
                    id = it.id,
                    displayName = it.displayName,
                    description = it.description,
                    installHint = it.installHint,
                )
            }
        }.getOrNull().orEmpty()

    override suspend fun health(engineId: String): CliEngineHealth {
        val health = runCatching { api()?.health(engineId) }.getOrNull() ?: return CliEngineHealth.Unknown
        return when (health) {
            is AiCliHealth.Ready -> CliEngineHealth.Ready(health.version)
            is AiCliHealth.NotInstalled -> CliEngineHealth.NotInstalled(health.hint)
            is AiCliHealth.Failed -> CliEngineHealth.Failed(health.message)
            // The hierarchy is open by design, so a case this build has never heard of is
            // expected. Unknown rather than a guess: a newer gateway reporting something new
            // must not be rendered as "not installed".
            else -> CliEngineHealth.Unknown
        }
    }

    override fun selectedEngineId(): String? = runCatching { api()?.selectedEngineId() }.getOrNull()

    override fun selectEngine(engineId: String?): Boolean =
        runCatching { api()?.selectEngine(engineId) }.getOrNull() ?: false

    companion object {
        /**
         * An adapter, or null when this host cannot serve one.
         *
         * Null when the gateway is not installed or an incoherent host installation cannot
         * link the type. `LinkageError` is caught because it is an `Error`, not an `Exception`.
         */
        fun orNull(context: PluginContext): CliEngineAccess? =
            try {
                GatewayCliEngineAccess(context).takeIf { it.linksOnThisHost() }
            } catch (_: LinkageError) {
                null
            }

        /**
         * Touch the api type once, here, so a host that cannot link it fails inside the guard
         * above rather than later from a composable.
         */
        private fun GatewayCliEngineAccess.linksOnThisHost(): Boolean =
            runCatching { AiCliSessionAPI::class.java.name }.isSuccess
    }
}
