package ai.rever.boss.plugin.dynamic.secretmanager.ai

import ai.rever.boss.plugin.api.PanelEventProvider
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.PluginLoaderDelegate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.net.URI

/** What the AI section can say and offer about the AI Gateway, given what this machine can do. */
enum class GatewayNotice {
    /** It is here. The Local CLI sessions section speaks for itself; say nothing. */
    NONE,

    /**
     * Absent, and the Toolbox is new enough to be *asked* to install it: the press raises the
     * Toolbox's own confirm dialog, which names the plugin from the store and installs on the
     * answer. The good case.
     */
    OFFER_INSTALL,

    /** Absent, and the best available route is opening the Toolbox for the user to look. */
    OFFER_TOOLBOX,

    /** Absent, with no route to offer. Say what is missing rather than showing a dead button. */
    DESCRIBE_ONLY,
}

/**
 * Which notice the AI section shows about the gateway.
 *
 * A pure function so the one decision here is testable without a host: this plugin cannot be
 * instantiated in a test at all (`PluginContext` has 71 abstract members), and the three inputs
 * come from places that are awkward to fake and easy to get backwards.
 */
internal fun gatewayNotice(
    installed: Boolean,
    canAskToolboxToInstall: Boolean,
    canOpenToolbox: Boolean,
): GatewayNotice =
    when {
        installed -> GatewayNotice.NONE
        canAskToolboxToInstall -> GatewayNotice.OFFER_INSTALL
        canOpenToolbox -> GatewayNotice.OFFER_TOOLBOX
        else -> GatewayNotice.DESCRIBE_ONLY
    }

/**
 * Whether the AI Gateway plugin is here, and how to offer to install it when it is not.
 *
 * **Why the AI section needs this at all.** `AiProvidersUiState.cliEngines` is empty in three
 * situations the panel used to treat alike, on the stated grounds that "none of them gives the
 * user anything to do here": the gateway is absent, the gateway predates `AiCliSessionAPI`, or
 * this host's api jar cannot link the symbol. That was wrong about the first one. A user who has
 * signed into `claude` in a terminal and comes here to use that login sees no Local CLI sessions
 * section, no explanation, and no way to find out that one plugin stands between them and it.
 *
 * The other two really do leave nothing to do, which is why this asks about the **plugin** rather
 * than reading the empty engine list: an installed gateway serving no engines is a different fact
 * from an absent one, and only the second is actionable.
 *
 * **There is deliberately no install call.** No plugin-facing api installs a plugin, and none
 * dispatches a deep link either, so Install hands a `boss://` URL to the OS - which owns the
 * scheme and routes it back into this same instance, through `SingleInstanceManager`, to the
 * Toolbox's deep-link handler. That handler shows a modal confirm naming the plugin **from the
 * store** and installs on the answer. It exists so a web page can offer Install without being
 * trusted about what is installed, which makes it the right door for a plugin that cannot be
 * trusted about it either.
 *
 * Ported from `user-secret-list`'s `SecretManagerLink`, minus the part that does not apply here:
 * that plugin's floor is 1.0.20, so it had to probe reflectively for `openPanel` (api 1.0.57).
 * This plugin's floor is **1.0.89**, so `openPanel`, `PanelEventProvider`, `PanelId` and
 * `PluginLoaderDelegate` are all below it and are called straight. A guard there would be dead
 * code implying a risk that cannot occur.
 */
class GatewayPresence(
    private val loader: PluginLoaderDelegate?,
    private val panels: PanelEventProvider?,
    private val windowId: String?,
    /** Whether this JVM can hand a URL to the platform. Injected so the route is testable. */
    private val browseSupported: () -> Boolean = {
        runCatching {
            Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)
        }.getOrDefault(false)
    },
    /** Hands [INSTALL_DEEP_LINK] to the platform, which routes it back into this instance. */
    private val browse: (String) -> Boolean = { url ->
        runCatching { Desktop.getDesktop().browse(URI(url)); true }.getOrDefault(false)
    },
) {
    /**
     * Whether the gateway is here **and usable**.
     *
     * Deliberately not `isPluginLoaded`. `DynamicPluginManager.disablePlugin` flips the state to
     * DISABLED and never calls `pluginLoader.unloadPlugin`, so a plugin the user switched off is
     * still in `getLoadedPlugins()` - and a disabled gateway serves no engines and answers no
     * `getPluginAPI`. Reporting it as installed would replace an actionable notice with silence,
     * which is the exact failure this class exists to remove. `healthy` and `isIncompatible`
     * come along for the same reason.
     */
    fun installed(): Boolean =
        runCatching {
            val loaded = loader?.getLoadedPlugins() ?: return@runCatching false
            loaded.any {
                it.pluginId == GATEWAY_ID && it.isEnabled && it.healthy && !it.isIncompatible
            }
        }.getOrDefault(false)

    /**
     * Whether the Toolbox can be *asked* to install the gateway, rather than merely opened.
     *
     * Needs its deep-link handler, which shipped in Toolbox **1.9.14** - checked rather than
     * assumed, because an older Toolbox has nothing listening and the link would be a press that
     * does nothing.
     */
    fun canAskToolboxToInstall(): Boolean {
        if (!browseSupported()) return false
        val toolbox =
            runCatching {
                loader?.getLoadedPlugins()?.firstOrNull { it.pluginId == TOOLBOX_ID }
            }.getOrNull() ?: return false
        if (!toolbox.isEnabled || !toolbox.healthy || toolbox.isIncompatible) return false
        return atLeast(toolbox.version, TOOLBOX_WITH_INSTALL_DEEP_LINK)
    }

    /** Whether a panel can be revealed at all. Both are nullable on the context. */
    fun canOpenToolbox(): Boolean = panels != null && windowId != null

    /** The notice to render. Re-read rather than cached - see [AiProvidersViewModel.checkGateway]. */
    fun notice(): GatewayNotice =
        gatewayNotice(
            installed = installed(),
            canAskToolboxToInstall = canAskToolboxToInstall(),
            canOpenToolbox = canOpenToolbox(),
        )

    /**
     * Asks the Toolbox to install the gateway, which raises **its** confirm dialog: the plugin is
     * named from the store rather than from the link, and nothing installs without the press.
     *
     * On `Dispatchers.IO` because `Desktop.browse` hands off to the platform and can block, and
     * the caller's scope may be the main one.
     */
    suspend fun askToolboxToInstall(): Boolean =
        withContext(Dispatchers.IO) {
            if (!canAskToolboxToInstall()) return@withContext false
            browse(INSTALL_DEEP_LINK)
        }

    /** Reveals the Toolbox, where the gateway can be found by hand. */
    suspend fun openToolbox(): Boolean {
        val provider = panels ?: return false
        val window = windowId ?: return false
        return runCatching { provider.openPanel(TOOLBOX_PANEL, window); true }.getOrDefault(false)
    }

    /**
     * Compares release cores only (`1.9.14`), ignoring anything after a `-` or `+`.
     *
     * Hand-rolled because `SemanticVersion` lives in the host's `plugin-dependency` module and is
     * not on the plugin api - and ignoring the suffix is the behaviour we want anyway, since a
     * prerelease of the release that added the handler does have the handler.
     */
    private fun atLeast(
        version: String,
        minimum: List<Int>,
    ): Boolean {
        val parts =
            version
                .takeWhile { it != '-' && it != '+' }
                .split('.')
                .map { it.toIntOrNull() ?: return false }
        minimum.forEachIndexed { index, floor ->
            val here = parts.getOrNull(index) ?: 0
            if (here != floor) return here > floor
        }
        return true
    }

    companion object {
        const val GATEWAY_ID = "ai.rever.boss.plugin.dynamic.aigateway"

        const val TOOLBOX_ID = "ai.rever.boss.plugin.dynamic.pluginmanager"

        /** The Toolbox release that first carried the install deep-link handler. */
        val TOOLBOX_WITH_INSTALL_DEEP_LINK = listOf(1, 9, 14)

        /**
         * `action=install` rather than `open`: the Toolbox decides from what it finds installed,
         * so the link cannot be wrong about the outcome - only about what it asks for.
         */
        const val INSTALL_DEEP_LINK =
            "boss://plugin?id=$TOOLBOX_ID&action=install&plugin=$GATEWAY_ID"

        /** The Toolbox (plugin store client). Its `PanelInfo` declares order 6. */
        val TOOLBOX_PANEL = PanelId("plugin-manager", 6)

        /** Reads the three providers off the context. All may be absent. */
        fun from(context: PluginContext): GatewayPresence =
            GatewayPresence(
                loader = runCatching { context.getPluginAPI(PluginLoaderDelegate::class.java) }.getOrNull(),
                panels = runCatching { context.panelEventProvider }.getOrNull(),
                windowId = runCatching { context.windowId }.getOrNull(),
            )
    }
}
