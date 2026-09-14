package ai.rever.boss.plugin.dynamic.secretmanager

import ai.rever.boss.plugin.api.CustomPluginEvent
import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.SecretDataProvider
import ai.rever.boss.plugin.dynamic.secretmanager.ai.ActiveProviderPrefs
import ai.rever.boss.plugin.dynamic.secretmanager.ai.AiProvidersViewModel
import ai.rever.boss.plugin.dynamic.secretmanager.ai.BrokeredCredentialBridge
import ai.rever.boss.plugin.dynamic.secretmanager.ai.EnvResolver
import ai.rever.boss.plugin.dynamic.secretmanager.ai.GatewayCliEngineAccess
import ai.rever.boss.plugin.dynamic.secretmanager.ai.GatewayPresence
import ai.rever.boss.plugin.dynamic.secretmanager.ai.LegacySettingsImport
import ai.rever.boss.plugin.dynamic.secretmanager.ai.LlmProviderSettingsApiImpl
import ai.rever.boss.plugin.dynamic.secretmanager.ai.ModelCatalog
import ai.rever.boss.plugin.dynamic.secretmanager.ai.ProviderCredentialStore
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * Secret Manager dynamic plugin - Loaded from external JAR.
 *
 * Manage encrypted credentials and secrets with CRUD and sharing.
 * Uses SecretDataProvider, SupabaseDataProvider, and PluginStoreApiKeyProvider from PluginContext.
 */
class SecretManagerDynamicPlugin : DynamicPlugin {
    override val pluginId: String = PluginVersionSource.PLUGIN_ID
    override val displayName: String = "Secret Manager (Dynamic)"
    /** Resolved from the bundled `plugin.json` — see [PluginVersionSource] for why not the manifest. */
    override val version: String = PluginVersionSource.read()
    override val description: String =
        "Your credentials, secrets shared with you, Plugin Store publish keys and AI provider settings"
    override val author: String = "Risa Labs"
    override val url: String = "https://github.com/risa-labs-inc/boss-plugin-secret-manager"

    private companion object {
        /** Minimum api release required by this implementation's model-discovery signature. */
        const val REQUIRED_API_VERSION = "1.0.89"

        /**
         * Deliberately on the companion, not an instance property.
         *
         * A `ComponentLogger`-typed property on this class makes the Compose compiler emit a
         * `$stable` field for the class whose initialiser *reads*
         * `ai.rever.boss.plugin.logging.ComponentLogger.$stable`. That field exists in the
         * boss-plugin-api jar we compile against but NOT in the host's bundled
         * `plugin-logging-desktop` jar, which shadows it parent-first at runtime — so
         * BinaryCompatibilityValidator rejected the whole plugin with
         * "ComponentLogger.$stable: field not found" and the host disabled it as binary
         * incompatible. Shipped broken in 1.2.6 and 1.2.7. Now prevented module-wide by
         * compose-stability.conf and proved by the bytecode guard in buildPluginJar — there is
         * deliberately no unit test, because on the test classpath the api jar IS ComponentLogger
         * and everything links. This companion placement is belt-and-braces for the one class the
         * validator takes the whole plugin down over.
         */
        private val logger = BossLogger.forComponent("SecretManagerPlugin")
    }

    override fun register(context: PluginContext) {
        val secretDataProvider = context.secretDataProvider
        val supabaseDataProvider = context.supabaseDataProvider
        val pluginStoreApiKeyProvider = context.pluginStoreApiKeyProvider
        val pluginScope = context.pluginScope ?: CoroutineScope(Dispatchers.Main)

        if (secretDataProvider == null) {
            context.panelRegistry.registerPanel(SecretManagerInfo) { ctx, panelInfo ->
                // Named, like the other call site: six positional arguments against an
                // eleven-parameter list whose neighbours are all nullable and same-typed
                // would silently mis-bind on a reorder and still compile.
                SecretManagerComponent(
                    ctx = ctx,
                    panelInfo = panelInfo,
                    secretDataProvider = null,
                    supabaseDataProvider = null,
                    pluginStoreApiKeyProvider = null,
                    scope = pluginScope,
                )
            }
            return
        }

        val navigation = ProviderNavigation()
        // Verified against the v1.0.73 tag (which predates the current 1.0.89 floor): PluginContext's
        // applicationEventBus, eventsOfType(Class<T>), and CustomPluginEvent's
        // eventName/payload all exist there, so this path needs no linkage adapter.
        context.applicationEventBus?.let { bus ->
            // Subscribe before register returns so a racing management action is retained;
            // collection suspends immediately and performs no credential or catalog I/O.
            pluginScope.launch(start = CoroutineStart.UNDISPATCHED) {
                navigation.collect({ bus.eventsOfType(CustomPluginEvent::class.java) }) { failure ->
                    logger.warn(
                        LogCategory.SYSTEM,
                        "AI provider navigation stopped (${failure.javaClass.simpleName}); secret management remains available",
                    )
                }
            }
        }

        // Built once and shared: the panel's "Add AI Provider Key" action and the
        // settings panel must write through the same store, or an entry added from one
        // wouldn't be recognised as provider configuration by the other.
        //
        // Safe to construct outside the LinkageError guard below — ProviderCredentialStore
        // and ProviderRegistry reference only api symbols covered by the 1.0.89 floor.
        val envResolver = EnvResolver()
        val credentialStore = ProviderCredentialStore(secretDataProvider, envResolver)

        // Assigned by registerAiProviderSettings below, read when the panel is first composed.
        //
        // The order is forced and this is what makes it safe: the ViewModel has to be built inside
        // the LinkageError guard (it starts a catalog collector, which on a host that cannot link
        // the api impl would be started and then orphaned), and the guard has to run after the
        // panel is registered so that a *non*-linkage failure in it cannot cost the user their
        // secrets panel. A captured `var` read through a lambda closes that gap: Kotlin compiles it
        // to a shared reference, `registerPanel` only stores a factory, and the host does not call
        // that factory until after `register()` has returned.
        var aiProvidersViewModel: AiProvidersViewModel? = null

        context.panelRegistry.registerPanel(SecretManagerInfo) { ctx, panelInfo ->
            SecretManagerComponent(
                ctx = ctx,
                panelInfo = panelInfo,
                secretDataProvider = secretDataProvider,
                supabaseDataProvider = supabaseDataProvider,
                pluginStoreApiKeyProvider = pluginStoreApiKeyProvider,
                scope = pluginScope,
                aiProviderStore = credentialStore,
                settingsProvider = context.settingsProvider,
                windowId = context.windowId,
                splitViewOperations = context.splitViewOperations,
                authDataProvider = context.authDataProvider,
                aiProvidersViewModel = { aiProvidersViewModel },
                providerNavigation = navigation.state,
                consumeProviderRequest = navigation::consume,
            )
        }

        // Contribute secret_* MCP tools (expose secret values to agents; auto-removed on disable/unload).
        context.registerMcpToolProvider(
            SecretManagerMcpToolProvider(pluginId, secretDataProvider, supabaseDataProvider, credentialStore),
        )

        aiProvidersViewModel =
            registerAiProviderSettings(context, credentialStore, envResolver, pluginScope)
    }

    /**
     * Serve AI provider configuration (credentials, env resolution, live model lists)
     * to the host's Settings → AI Providers section and to other plugins via
     * PluginContext.llmProvider.
     *
     * Guarded as a final containment boundary for linkage failures. The declared api floor is
     * still authoritative: it is 1.0.89 because [LlmProviderSettingsApiImpl.availableModels]
     * exposes types introduced there, including in a lazily resolved method signature.
     */
    private fun registerAiProviderSettings(
        context: PluginContext,
        credentialStore: ProviderCredentialStore,
        envResolver: EnvResolver,
        pluginScope: CoroutineScope,
    ): AiProvidersViewModel? {
        try {
            // Constructed inside the guard: the ViewModel starts a catalog.states
            // collector, and on a host that can't link the impl below that coroutine
            // would be started and then orphaned.
            val cacheDir =
                context.cacheProvider
                    ?.getPluginCacheDirectory(pluginId)
                    ?.let { File(it) }

            // Inside the guard on purpose: a malformed host can still violate the declared
            // 1.0.89 floor. Left unset when the host has no broker relay, which makes
            // brokered providers report unconfigured instead of failing.
            credentialStore.brokeredKeys = BrokeredCredentialBridge.from(context)

            val viewModel =
                AiProvidersViewModel(
                    store = credentialStore,
                    catalog = ModelCatalog(cacheDir = cacheDir),
                    prefs = ActiveProviderPrefs(),
                    legacyImport = LegacySettingsImport(credentialStore, envResolver),
                    envResolver = envResolver,
                    splitViewOperations = context.splitViewOperations,
                    scope = pluginScope,
                    // The adapter names host types only at this boundary and resolves the
                    // optional gateway per call. Null costs the Local CLI section and nothing else.
                    cliEngines = GatewayCliEngineAccess.orNull(context),
                    // Not guarded by anything: every symbol it touches (PluginLoaderDelegate,
                    // PanelEventProvider, PanelId, openPanel) predates this plugin's 1.0.89 floor.
                    // It lives inside the guard only because the ViewModel that holds it does.
                    gateway = GatewayPresence.from(context),
                )

            context.registerPluginAPI(LlmProviderSettingsApiImpl(viewModel))

            // Warm the credentials so the first AI action after a restart doesn't race
            // the load. Network-free — model lists are still fetched lazily, when the
            // panel is opened or refreshed, so this costs nothing at startup.
            viewModel.ensureConnectionsLoaded()
            return viewModel
        } catch (_: LinkageError) {
            // A malformed or unexpectedly old host api reached registration despite the
            // manifest floor. Logged rather than swallowed so a missing AI section is visible.
            logger.info(
                LogCategory.SYSTEM,
                "AI provider settings not served — host API linkage failed",
                mapOf("requiredApiVersion" to REQUIRED_API_VERSION),
            )
            return null
        }
    }
}
