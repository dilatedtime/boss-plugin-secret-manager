package ai.rever.boss.plugin.dynamic.secretmanager

import ai.rever.boss.plugin.api.*
import ai.rever.boss.plugin.dynamic.secretmanager.ai.CredentialSource
import ai.rever.boss.plugin.dynamic.secretmanager.ai.ProviderCredentialStore
import ai.rever.boss.plugin.dynamic.secretmanager.ai.ProviderRegistry
import ai.rever.boss.plugin.dynamic.secretmanager.ai.SharedProviderDefinition
import ai.rever.boss.plugin.dynamic.secretmanager.ai.bossAiDefinitionRequest
import ai.rever.boss.plugin.dynamic.secretmanager.ai.managedProviderUpdate
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

// Long enough to paste into an external tool, short enough that a copied
// secret doesn't linger on the system clipboard indefinitely
private const val CLIPBOARD_CLEAR_DELAY_MS = 45_000L

// Server-side counterpart: share_secret refuses a role target without this
// (migration 20260809000000). Held by admin and boss_admin, not by `user`.
internal const val PERMISSION_SHARE_WITH_ROLE = "secret.share.role"

// The host's SettingsSection enum entry for AI provider settings. Matched
// case-insensitively by the host, and still named LLM_PROVIDERS for compatibility
// even though the section now displays as "AI Providers".
private const val AI_PROVIDERS_SETTINGS_SECTION = "LLM_PROVIDERS"

@Serializable
data class ShareUserRow(val id: String, val email: String)

@Serializable
data class ShareRoleRow(val id: String, val name: String, val description: String? = null)

/**
 * ViewModel for Secret Manager panel.
 *
 * Uses SecretDataProvider, SupabaseDataProvider, and PluginStoreApiKeyProvider
 * interfaces for data operations. Matches the bundled plugin's state management pattern.
 */
class SecretManagerViewModel(
    private val secretDataProvider: SecretDataProvider?,
    private val supabaseDataProvider: SupabaseDataProvider?,
    private val pluginStoreApiKeyProvider: PluginStoreApiKeyProvider?,
    private val scope: CoroutineScope,
    /** Writes AI provider keys through the same store the AI Providers panel uses. */
    private val aiProviderStore: ProviderCredentialStore? = null,
    /** Used to jump to Settings → AI Providers from an AI provider entry. */
    private val settingsProvider: SettingsProvider? = null,
    private val windowId: String? = null,
    /** Opens a provider's key console, same affordance as the AI Providers panel. */
    private val splitViewOperations: SplitViewOperations? = null,
    /** Read-only: decides whether the share dialog offers role targets at all. */
    private val authDataProvider: AuthDataProvider? = null
) {
    private val logger = BossLogger.forComponent("SecretManager")

    // Job tracking to prevent race conditions
    private var loadJob: Job? = null
    private var searchJob: Job? = null
    // Provider cancellation is best-effort: a transport may catch CancellationException and
    // still return. Generations ensure only the newest authorization snapshot may mutate state.
    private var secretsRequestGeneration = 0L
    private var shareRequestGeneration = 0L

    /**
     * The permission collector, which is the only launch here that never completes.
     *
     * `scope` is the *plugin* scope while this ViewModel is per panel instance, so an
     * uncancelled `collect` on a StateFlow roots this object for as long as the plugin is
     * loaded. What that retains is the problem: `state.secrets` holds `SecretEntryData`
     * with the decrypted `password` in it, so every panel open would strand a full
     * plaintext credential list. Every other launch in this class terminates, which is
     * why nothing needed a destroy hook before.
     */
    private var permissionJob: Job? = null

    /**
     * Set by [dispose]; guards the paths that would refill [SecretManagerState.secrets]
     * afterwards.
     *
     * Cancelling jobs is not enough on its own. A provider may catch cancellation and return a
     * Result, and `createSecret` / `updateSecret` can start a replacement load. The disposed flag
     * and request generations are therefore checked before every state write that carries
     * decrypted secrets, independent of transport cancellation behaviour.
     *
     * `deleteSecret` needs no guard: it filters the existing list, which [dispose] has already
     * emptied, so it cannot repopulate.
     *
     * Deliberately a flag rather than a child scope this class could cancel wholesale:
     * [copySecret]'s clipboard wipe is *meant* to outlive the panel by
     * [CLIPBOARD_CLEAR_DELAY_MS], and cancelling it would leave a copied credential on the
     * system clipboard indefinitely - trading a bounded in-memory reference for an unbounded
     * OS-level one.
     */
    private var disposed = false

    // Lazy-load guards for share-dialog data and the API-key permission check
    private var usersLoaded = false
    private var rolesLoaded = false
    // True while availableUsers holds a search-filtered subset rather than the full list
    private var usersListFiltered = false
    private var apiKeyPermissionChecked = false

    // State
    var state by mutableStateOf(SecretManagerState())
        private set

    /**
     * Initialize by loading secrets.
     *
     * Users and roles are NOT loaded here — they are only needed by the share
     * dialog and are fetched lazily when it opens, so opening the panel costs
     * a single network round-trip instead of three.
     */
    fun initialize() {
        state = state.copy(canAddAiProviderKey = aiProviderStore != null)
        observeRoleSharePermission()
        if (secretDataProvider != null) {
            loadSecrets()
        }
    }

    /**
     * Keep [SecretManagerState.canShareWithRoles] in step with the signed-in user.
     *
     * Collected rather than read once in [initialize]: the panel is constructed as soon
     * as the plugin registers, which can precede the permission claim landing, and a
     * one-shot read would leave the Roles tab hidden for an admin until they reopened
     * the panel. Both flows are combined because `hasPermission` answers true for an
     * admin regardless of the permission set, so an admin whose claim arrives without
     * a permissions change still needs a recompute.
     */
    private fun observeRoleSharePermission() {
        val auth = authDataProvider ?: return
        permissionJob = scope.launch {
            combine(auth.userPermissions, auth.isAdmin) { _, _ ->
                auth.hasPermission(PERMISSION_SHARE_WITH_ROLE)
            }.collect { canShare ->
                if (canShare != state.canShareWithRoles) {
                    state = state.copy(canShareWithRoles = canShare)
                    // The Tab appears the instant the flag flips, so a claim landing while
                    // the dialog is open would otherwise present a pane backed by an empty
                    // availableRoles, with no spinner and no empty state to explain it -
                    // recoverable only by reopening the dialog, with nothing to say so.
                    // Gating the fetch on "the flag settles before any dialog opens" would
                    // assume exactly what the collector exists to deny.
                    if (canShare && state.showShareDialog && !rolesLoaded && !state.isLoadingRoles) {
                        loadAvailableRoles()
                    }
                }
            }
        }
    }

    /**
     * Release panel-scoped resources. Called from the component's `doOnDestroy`.
     *
     * Cancels the permission collector and drops the decrypted secret list rather than
     * waiting for the ViewModel to be collected: the panel is closed, nothing can render
     * it, and holding plaintext credentials past that point buys nothing.
     */
    fun dispose() {
        disposed = true
        secretsRequestGeneration++
        shareRequestGeneration++
        permissionJob?.cancel()
        permissionJob = null
        loadJob?.cancel()
        searchJob?.cancel()
        state = state.copy(
            secrets = emptyList(),
            secretAccess = emptyMap(),
            selectedSecret = null,
            secretShares = emptyList(),
            secretShareTargets = emptyMap(),
            // Closing the panel with the AI-provider dialog open would otherwise leave the
            // raw key in state; hideAiProviderKeyDialog() clears it for the same reason.
            aiProviderKeyDraft = "",
        )
    }

    /**
     * Elapsed milliseconds since a System.nanoTime() mark — monotonic, so
     * durations are immune to wall-clock (NTP) jumps.
     */
    private fun elapsedMsSince(startedAtNanos: Long): Long =
        (System.nanoTime() - startedAtNanos) / 1_000_000

    /**
     * Log elapsed time for a data operation so intermittent slow loads are
     * diagnosable from the host console (search for "SecretManager").
     */
    private fun logTiming(operation: String, elapsedMs: Long, outcome: String, failed: Boolean = false) {
        val message = "$operation: ${if (failed) "FAILED ($outcome)" else outcome} in $elapsedMs ms"
        if (failed) {
            logger.warn(LogCategory.NETWORK, message)
        } else {
            logger.info(LogCategory.NETWORK, message)
        }
    }

    /**
     * Check if the provider is available.
     */
    fun isAvailable(): Boolean {
        return secretDataProvider != null
    }

    /**
     * Load all secrets for the current user
     */
    fun loadSecrets() {
        if (disposed) return
        val generation = ++secretsRequestGeneration
        loadJob?.cancel()
        searchJob?.cancel()
        searchJob = null
        state = state.copy(
            isLoading = true,
            // Refresh replaces a paginated load, including its progress state.
            isLoadingMore = false,
            errorMessage = null,
            searchQuery = "",
            currentOffset = 0,
            hasMore = true,
            lastLoadDurationMs = null
        )

        loadJob = scope.launch {
            val startedAt = System.nanoTime()
            val result = secretDataProvider?.getUserSecretsWithAccess(limit = state.pageSize, offset = 0)
            val elapsedMs = elapsedMsSince(startedAt)

            result?.onSuccess { paginatedResult ->
                val secrets = paginatedResult.data.map { it.secret }
                val access = paginatedResult.data.associate { it.secret.id to it.toAccessState() }
                logTiming("getUserSecrets", elapsedMs, "${secrets.size} secrets")
                // Re-check after the round-trip as well as cancelling the job: a provider may
                // convert cancellation into a returned Result, and an older authorization
                // snapshot must never replace a newer one.
                if (disposed || generation != secretsRequestGeneration) return@onSuccess
                state = state.copy(
                    secrets = secrets,
                    secretAccess = access,
                    isLoading = false,
                    currentOffset = secrets.size,
                    hasMore = paginatedResult.hasMore,
                    lastLoadDurationMs = elapsedMs
                )
                closeManagementDialogsIfAccessRevoked()
                // Pre-warm the API-key permission check off the critical open
                // path so the Add menu is populated by the time it's opened
                checkApiKeyPermission()
            }?.onFailure { exception ->
                if (exception is CancellationException || disposed || generation != secretsRequestGeneration) {
                    return@onFailure
                }
                val error = exception.message ?: "Unknown error"
                logTiming("getUserSecrets", elapsedMs, error, failed = true)
                state = state.copy(
                    isLoading = false,
                    errorMessage = error,
                    secretAccess = emptyMap(),
                )
                closeManagementDialogsIfAccessRevoked()
            }
        }
    }

    /**
     * Load more secrets (pagination)
     */
    fun loadMoreSecrets() {
        if (state.isLoadingMore || !state.hasMore || state.isLoading || state.searchQuery.isNotBlank()) {
            return
        }
        if (searchJob?.isActive == true) {
            return
        }

        val generation = ++secretsRequestGeneration
        loadJob?.cancel()
        val offset = state.currentOffset
        state = state.copy(isLoadingMore = true)

        loadJob = scope.launch {
            val startedAt = System.nanoTime()
            val result = secretDataProvider?.getUserSecretsWithAccess(
                limit = state.pageSize,
                offset = offset
            )
            val elapsedMs = elapsedMsSince(startedAt)

            result?.onSuccess { paginatedResult ->
                val newSecrets = paginatedResult.data.map { it.secret }
                val newAccess = paginatedResult.data.associate { it.secret.id to it.toAccessState() }
                logTiming("getUserSecrets(offset=$offset)", elapsedMs, "${newSecrets.size} secrets")
                if (disposed || generation != secretsRequestGeneration) return@onSuccess
                state = state.copy(
                    secrets = state.secrets + newSecrets,
                    secretAccess = state.secretAccess + newAccess,
                    isLoadingMore = false,
                    currentOffset = offset + newSecrets.size,
                    hasMore = paginatedResult.hasMore,
                    lastLoadDurationMs = elapsedMs
                )
                closeManagementDialogsIfAccessRevoked()
            }?.onFailure { exception ->
                if (exception is CancellationException || disposed || generation != secretsRequestGeneration) {
                    return@onFailure
                }
                val error = exception.message ?: "Unknown error"
                logTiming("getUserSecrets(offset=$offset)", elapsedMs, error, failed = true)
                state = state.copy(
                    isLoadingMore = false,
                    errorMessage = error
                )
            }
        }
    }

    /**
     * Search secrets by website or username
     */
    fun searchSecrets(query: String) {
        val generation = ++secretsRequestGeneration
        loadJob?.cancel()
        loadJob = null
        searchJob?.cancel()

        state = state.copy(
            searchQuery = query,
            isLoading = true,
            isLoadingMore = false,
            errorMessage = null,
            currentOffset = 0,
            hasMore = false,
            lastLoadDurationMs = null
        )

        if (query.isBlank()) {
            searchJob = null
            loadSecrets()
            return
        }

        searchJob = scope.launch {
            val startedAt = System.nanoTime()
            val result = secretDataProvider?.searchSecretsWithAccess(query = query, limit = 100, offset = 0)
            val elapsedMs = elapsedMsSince(startedAt)

            result?.onSuccess { paginatedResult ->
                val secrets = paginatedResult.data.map { it.secret }
                val access = paginatedResult.data.associate { it.secret.id to it.toAccessState() }
                logTiming("searchSecrets", elapsedMs, "${secrets.size} secrets")
                // Same reason as loadSecrets: `?.onFailure { if (exception is
                // CancellationException) ... }` below is this code conceding the provider can
                // hand cancellation back as a returned Result rather than throwing at the
                // suspension point. Where it does, the resumption is not cancelled and there
                // is no suspension point before this write, so searchJob?.cancel() alone does
                // not stop the decrypted list landing after dispose.
                if (disposed || generation != secretsRequestGeneration) return@onSuccess
                state = state.copy(
                    secrets = secrets,
                    // Replace rather than merge: keeping an entry from the previous result can
                    // retain a now-revoked canManage=true decision for an id returned by neither
                    // the current search nor its authorization snapshot.
                    secretAccess = access,
                    isLoading = false,
                    isLoadingMore = false,
                    currentOffset = 0,
                    hasMore = false,
                    lastLoadDurationMs = elapsedMs
                )
                closeManagementDialogsIfAccessRevoked()
            }?.onFailure { exception ->
                if (exception is CancellationException || disposed || generation != secretsRequestGeneration) {
                    return@onFailure
                }
                val error = exception.message ?: "Unknown error"
                logTiming("searchSecrets", elapsedMs, error, failed = true)
                state = state.copy(
                    isLoading = false,
                    isLoadingMore = false,
                    errorMessage = error,
                    secretAccess = emptyMap(),
                )
                closeManagementDialogsIfAccessRevoked()
            }
        }
    }

    fun showCreateDialog() {
        state = state.copy(showCreateDialog = true, selectedSecret = null)
    }

    fun hideCreateDialog() {
        state = state.copy(showCreateDialog = false)
    }

    fun showEditDialog(secret: SecretEntryData) {
        if (refuseUnmanaged(secret.id, "edit")) return
        state = state.copy(showEditDialog = true, selectedSecret = secret)
    }

    fun hideEditDialog() {
        state = state.copy(showEditDialog = false, selectedSecret = null)
    }

    fun showDeleteDialog(secret: SecretEntryData) {
        if (refuseUnmanaged(secret.id, "delete")) return
        state = state.copy(showDeleteDialog = true, selectedSecret = secret)
    }

    fun hideDeleteDialog() {
        state = state.copy(showDeleteDialog = false, selectedSecret = null)
    }

    // ==================== AI provider keys ====================

    /** True when [secret] is AI provider configuration rather than a password entry. */
    fun isAiProviderSecret(secret: SecretEntryData): Boolean =
        secret.tags.contains(ProviderCredentialStore.TAG_AI_PROVIDER)

    /** Display name for an AI provider entry, falling back to the stored website value. */
    fun aiProviderDisplayName(secret: SecretEntryData): String =
        ProviderRegistry.find(secret.website)?.displayName ?: secret.website

    /**
     * Open the provider-key dialog, then load which providers already have a credential.
     *
     * The dialog needs this to tell adding from changing: without it, a provider whose key
     * is already stored looks identical to one that has none, and saving silently replaces
     * a working key with no warning.
     */
    fun showAiProviderKeyDialog() {
        state = state.copy(
            showAiProviderKeyDialog = true,
            aiProviderKeyProviderId = ProviderRegistry.default.id,
            aiProviderKeyDraft = "",
            errorMessage = null
        )

        val store = aiProviderStore ?: return
        scope.launch {
            val sources =
                store.loadAll().connections.mapValues { (_, connection) -> connection.source }

            // Pre-select the first provider that has no key yet, since adding is the
            // common case; changing an existing one is then an explicit pick.
            // Over the same list the dialog offers, or this pre-selects a provider the user
            // cannot then see in the picker.
            val firstUnset = ProviderRegistry.userKeyed
                .firstOrNull { (sources[it.id] ?: CredentialSource.NONE) == CredentialSource.NONE }
                ?.id

            // Only pre-select if the user hasn't chosen in the meantime. loadAll() pages the
            // whole store, so on a large one this lands well after the dialog is usable and
            // would otherwise overwrite an explicit pick.
            val userHasPicked = state.aiProviderKeyProviderId != ProviderRegistry.default.id ||
                state.aiProviderKeyDraft.isNotEmpty()

            state = state.copy(
                aiProviderSources = sources,
                aiProviderKeyProviderId =
                    if (userHasPicked) {
                        state.aiProviderKeyProviderId
                    } else {
                        firstUnset ?: state.aiProviderKeyProviderId
                    }
            )
        }
    }

    fun hideAiProviderKeyDialog() {
        // errorMessage is shared panel state, so a failed save would otherwise leave its
        // banner behind after the dialog it belonged to is gone.
        // Clear the draft on close so key material doesn't linger in state.
        state = state.copy(showAiProviderKeyDialog = false, aiProviderKeyDraft = "", errorMessage = null)
    }

    fun setAiProviderKeyProvider(providerId: String) {
        state = state.copy(aiProviderKeyProviderId = providerId, errorMessage = null)
    }

    fun setAiProviderKeyDraft(value: String) {
        state = state.copy(aiProviderKeyDraft = value, errorMessage = null)
    }

    /**
     * Save the entered key through [ProviderCredentialStore] rather than `createSecret`,
     * so it lands with the tags, website and notes shape the AI Providers panel reads —
     * a hand-rolled secret would not be recognised as provider configuration.
     */
    fun saveAiProviderKey() {
        val store = aiProviderStore ?: return
        val providerId = state.aiProviderKeyProviderId
        val key = state.aiProviderKeyDraft.trim()
        if (key.isBlank()) {
            state = state.copy(errorMessage = "Enter an API key first.")
            return
        }

        scope.launch {
            state = state.copy(isOperationInProgress = true)
            store.saveKey(providerId, key)
                .onSuccess {
                    state = state.copy(
                        isOperationInProgress = false,
                        showAiProviderKeyDialog = false,
                        aiProviderKeyDraft = "",
                        errorMessage = null,
                        aiProviderSources = state.aiProviderSources +
                            (providerId to CredentialSource.STORED)
                    )
                    loadSecrets()
                }
                .onFailure { error ->
                    state = state.copy(
                        isOperationInProgress = false,
                        errorMessage = error.message ?: "Could not save the provider key."
                    )
                }
        }
    }

    /**
     * Create the canonical BOSS AI definition, or upgrade the matching inert entry
     * someone already entered by hand. Sharing remains an explicit second action on
     * the resulting card so the existing permission-checked share dialog owns targets.
     */
    fun prepareBossAiProviderDefinition() {
        val provider = secretDataProvider ?: return
        state = state.copy(isOperationInProgress = true, errorMessage = null)
        scope.launch {
            val candidates = provider.searchSecrets(SharedProviderDefinition.BOSS_AI_WEBSITE, limit = 50)
                .getOrElse { error ->
                    state = state.copy(
                        isOperationInProgress = false,
                        errorMessage = error.message ?: "Could not look for an existing BOSS AI definition.",
                    )
                    return@launch
                }
                .data
                .filter {
                    it.website == SharedProviderDefinition.BOSS_AI_WEBSITE &&
                        it.username == SharedProviderDefinition.BOSS_AI_USERNAME
                }

            if (candidates.size > 1) {
                state = state.copy(
                    isOperationInProgress = false,
                    errorMessage = "More than one BOSS AI definition exists. Remove duplicates before publishing.",
                )
                return@launch
            }

            val write = candidates.singleOrNull()?.let { existing ->
                managedProviderUpdate(existing).fold(
                    onSuccess = { provider.updateSecret(it) },
                    onFailure = { Result.failure(it) },
                )
            } ?: provider.createSecret(bossAiDefinitionRequest())

            write.onSuccess {
                aiProviderStore?.invalidate()
                state = state.copy(isOperationInProgress = false, errorMessage = null)
                loadSecrets()
            }.onFailure { error ->
                state = state.copy(
                    isOperationInProgress = false,
                    errorMessage = error.message ?: "Could not prepare the BOSS AI definition.",
                )
            }
        }
    }

    /**
     * Open the selected provider's key console in a BOSS tab.
     *
     * Same affordance as the AI Providers settings panel, so the dialog isn't a dead end
     * for someone who doesn't have a key yet.
     */
    fun openAiProviderConsole() {
        val descriptor = ProviderRegistry.findOrDefault(state.aiProviderKeyProviderId)
        val url = descriptor.consoleUrl
        if (url == null) {
            state = state.copy(errorMessage = "${descriptor.displayName} has no key console.")
            return
        }
        val operations = splitViewOperations
        if (operations == null) {
            state = state.copy(errorMessage = "Open $url to create a key.")
            return
        }
        runCatching {
            operations.openUrlInActivePanel(url, "${descriptor.displayName} API keys", forceNewTab = true)
        }.onFailure {
            state = state.copy(errorMessage = "Open $url to create a key.")
        }
    }

    /**
     * Open Settings → AI Providers, where the key can be tested and a model chosen.
     *
     * The section name is the host's `SettingsSection` enum entry; the host matches it
     * case-insensitively.
     */
    fun openAiProviderSettings() {
        val provider = settingsProvider
        val window = windowId
        if (provider == null || window == null) {
            state = state.copy(
                errorMessage = "Open Settings → AI Providers to manage this key."
            )
            return
        }
        runCatching { provider.openSettings(window, AI_PROVIDERS_SETTINGS_SECTION) }
            .onFailure {
                logger.warn(
                    LogCategory.GENERAL,
                    "Could not open AI provider settings",
                    mapOf("exception" to (it::class.simpleName ?: "Exception"))
                )
                state = state.copy(errorMessage = "Open Settings → AI Providers to manage this key.")
            }
    }

    fun showShareDialog(secret: SecretEntryData) {
        if (refuseUnmanaged(secret.id, "share")) return
        state = state.copy(
            showShareDialog = true,
            selectedSecret = secret,
            secretShares = emptyList(),
            secretShareTargets = emptyMap(),
            isLoadingShares = false
        )
        loadSecretShares(secret.id)
        // Share targets are fetched lazily on first dialog open (see initialize);
        // the loaded flags avoid re-fetching a legitimately empty list. A reload
        // is also needed when a previous search left a filtered subset behind.
        if ((!usersLoaded || usersListFiltered) && !state.isLoadingUsers) {
            loadAvailableUsers()
        }
        // Not fetched for a user who will never see the Roles tab. This is only the
        // steady-state check: a flag that flips while the dialog is already open is handled
        // by observeRoleSharePermission, which kicks the fetch itself. (An earlier version of
        // this comment claimed the flag always settles before a dialog can open, which is
        // exactly what the collector exists to deny.)
        if (state.canShareWithRoles && !rolesLoaded && !state.isLoadingRoles) {
            loadAvailableRoles()
        }
    }

    fun hideShareDialog() {
        shareRequestGeneration++
        state = state.copy(
            showShareDialog = false,
            selectedSecret = null,
            secretShares = emptyList(),
            secretShareTargets = emptyMap(),
            isLoadingShares = false
        )
    }

    /**
     * Create a new secret
     */
    fun createSecret(request: CreateSecretRequestData) {
        state = state.copy(isOperationInProgress = true)

        scope.launch {
            val result = secretDataProvider?.createSecret(request)
            // The AI provider cache is keyed off these same secrets; without this a
            // deleted or hand-edited ai-provider entry keeps being served to other
            // plugins (and shown as connected) for the rest of the session.
            aiProviderStore?.invalidate()

            result?.onSuccess {
                state = state.copy(isOperationInProgress = false)
                hideCreateDialog()
                loadSecrets()
            }?.onFailure { exception ->
                state = state.copy(
                    isOperationInProgress = false,
                    errorMessage = exception.message ?: "Unknown error"
                )
            }
        }
    }

    /**
     * Update an existing secret
     */
    fun updateSecret(request: UpdateSecretRequestData) {
        if (refuseUnmanaged(request.secretId, "update")) return
        state = state.copy(isOperationInProgress = true)

        scope.launch {
            val result = secretDataProvider?.updateSecret(request)
            // The AI provider cache is keyed off these same secrets; without this a
            // deleted or hand-edited ai-provider entry keeps being served to other
            // plugins (and shown as connected) for the rest of the session.
            aiProviderStore?.invalidate()

            result?.onSuccess {
                state = state.copy(isOperationInProgress = false)
                hideEditDialog()
                loadSecrets()
            }?.onFailure { exception ->
                state = state.copy(
                    isOperationInProgress = false,
                    errorMessage = exception.message ?: "Unknown error"
                )
            }
        }
    }

    /**
     * Delete a secret
     */
    fun deleteSecret(secretId: String) {
        if (refuseUnmanaged(secretId, "delete")) return
        state = state.copy(isOperationInProgress = true)

        scope.launch {
            val result = secretDataProvider?.deleteSecret(secretId)
            // The AI provider cache is keyed off these same secrets; without this a
            // deleted or hand-edited ai-provider entry keeps being served to other
            // plugins (and shown as connected) for the rest of the session.
            aiProviderStore?.invalidate()

            result?.onSuccess {
                state = state.copy(isOperationInProgress = false)
                hideDeleteDialog()
                state = state.copy(
                    secrets = state.secrets.filter { it.id != secretId },
                    secretAccess = state.secretAccess - secretId,
                )
            }?.onFailure { exception ->
                state = state.copy(
                    isOperationInProgress = false,
                    errorMessage = exception.message ?: "Unknown error"
                )
            }
        }
    }

    // Generation token: bumped per copy so only the latest copy's timer may clear
    private var clipboardCopyGeneration = 0L

    /**
     * Copy a secret's password/API key to the clipboard, then best-effort
     * clear it after a delay. Runs in the ViewModel scope so the pending
     * clear survives the card scrolling out of composition. The clear only
     * fires from the most recent copy's timer, and only if the clipboard
     * still holds that value — so re-copies get their full window and
     * anything the user copied afterwards is never clobbered.
     *
     * Known limits of the mitigation: if the panel scope is cancelled
     * (panel closed, app quit) before the delay elapses, the clear never
     * runs; and OS clipboard-history managers may retain the value
     * regardless. Copying to the system clipboard is inherently exposed.
     */
    fun copyPasswordToClipboard(secret: SecretEntryData, clipboard: ClipboardManager) {
        val copied = secret.password
        val generation = ++clipboardCopyGeneration
        clipboard.setText(AnnotatedString(copied))
        scope.launch {
            delay(CLIPBOARD_CLEAR_DELAY_MS)
            if (generation == clipboardCopyGeneration && clipboard.getText()?.text == copied) {
                clipboard.setText(AnnotatedString(""))
            }
        }
    }

    fun togglePasswordVisibility(secretId: String) {
        val current = state.visiblePasswordIds
        state = if (current.contains(secretId)) {
            state.copy(visiblePasswordIds = current - secretId)
        } else {
            state.copy(visiblePasswordIds = current + secretId)
        }
    }

    fun toggleMetadataExpanded(secretId: String) {
        val current = state.expandedSecretIds
        state = if (current.contains(secretId)) {
            state.copy(expandedSecretIds = current - secretId)
        } else {
            state.copy(expandedSecretIds = current + secretId)
        }
    }

    fun clearError() {
        state = state.copy(errorMessage = null)
    }

    /** Server-published access for a row; an absent decision is always read-only. */
    fun accessFor(secretId: String): SecretAccessState =
        state.secretAccess[secretId] ?: SecretAccessState.READ_ONLY

    /** The single policy check used by both rendering and every mutation entry point. */
    fun canManageSecret(secretId: String): Boolean = accessFor(secretId).canManage

    private fun refuseUnmanaged(secretId: String, operation: String): Boolean {
        if (canManageSecret(secretId)) return false
        logger.warn(
            LogCategory.SYSTEM,
            "Refusing $operation for secret without server-authoritative management access",
            mapOf("secretId" to secretId),
        )
        return true
    }

    private fun closeManagementDialogsIfAccessRevoked() {
        val selectedId = state.selectedSecret?.id ?: return
        if (canManageSecret(selectedId)) return
        shareRequestGeneration++
        state = state.copy(
            showEditDialog = false,
            showDeleteDialog = false,
            showShareDialog = false,
            selectedSecret = null,
            secretShares = emptyList(),
            secretShareTargets = emptyMap(),
            isLoadingShares = false,
        )
    }

    fun loadSecretShares(secretId: String) {
        // A mutation can finish after its dialog closed or another secret was opened.
        // It must not invalidate that dialog's request or leave an orphaned spinner.
        if (disposed || !state.showShareDialog || state.selectedSecret?.id != secretId ||
            !canManageSecret(secretId)
        ) return
        val generation = ++shareRequestGeneration
        state = state.copy(isLoadingShares = true)

        scope.launch {
            val startedAt = System.nanoTime()
            val result = secretDataProvider?.getSecretSharesWithTargets(secretId)

            result?.onSuccess { targetedShares ->
                if (
                    disposed ||
                    generation != shareRequestGeneration ||
                    state.selectedSecret?.id != secretId
                ) {
                    return@onSuccess
                }
                logTiming("getSecretShares", elapsedMsSince(startedAt), "${targetedShares.size} shares")
                state = state.copy(
                    secretShares = targetedShares.map { it.share },
                    secretShareTargets = targetedShares.associate { target ->
                        target.share.shareId to SecretShareTargetState(
                            orgId = target.sharedWithOrgId,
                            orgSlug = target.sharedWithOrgSlug,
                        )
                    },
                    isLoadingShares = false,
                )
            }?.onFailure { exception ->
                if (exception is CancellationException || disposed || generation != shareRequestGeneration) {
                    return@onFailure
                }
                val error = exception.message ?: "Unknown error"
                logTiming("getSecretShares", elapsedMsSince(startedAt), error, failed = true)
                state = state.copy(
                    isLoadingShares = false,
                    errorMessage = error
                )
            }
        }
    }

    fun shareSecret(request: ShareSecretRequestData) {
        if (refuseUnmanaged(request.secretId, "share")) return
        // Defence in depth. `share_secret` refuses an ungranted role target server-side and
        // the dialog is the only caller, but that makes the invariant rest on the UI being
        // the sole entry point. Checked here so it holds for any future caller too.
        if (request.targetRoleId != null && !state.canShareWithRoles) {
            // Log and return without touching state. `errorMessage` renders in the panel
            // body *behind* the modal, so the user would see nothing now and an error page
            // after closing; and clearing isOperationInProgress here would stomp a flag this
            // call never set, killing the spinner of a genuinely in-flight operation.
            // Unreachable from the UI (the tab is hidden) - this exists for a future caller.
            logger.warn(LogCategory.SYSTEM, "Refusing a role share without $PERMISSION_SHARE_WITH_ROLE")
            return
        }

        state = state.copy(isOperationInProgress = true)

        scope.launch {
            val result = secretDataProvider?.shareSecret(request)

            result?.onSuccess {
                state = state.copy(isOperationInProgress = false)
                loadSecretShares(request.secretId)
            }?.onFailure { exception ->
                state = state.copy(
                    isOperationInProgress = false,
                    errorMessage = exception.message ?: "Unknown error"
                )
            }
        }
    }

    fun unshareSecret(secretId: String, userId: String? = null, roleId: String? = null) {
        if (refuseUnmanaged(secretId, "revoke share")) return
        state = state.copy(isOperationInProgress = true)

        scope.launch {
            val request = UnshareSecretRequestData(
                secretId = secretId,
                targetUserId = userId,
                targetRoleId = roleId
            )

            val result = secretDataProvider?.unshareSecret(request)

            result?.onSuccess {
                state = state.copy(isOperationInProgress = false)
                loadSecretShares(secretId)
            }?.onFailure { exception ->
                state = state.copy(
                    isOperationInProgress = false,
                    errorMessage = exception.message ?: "Unknown error"
                )
            }
        }
    }

    fun loadAvailableUsers() {
        state = state.copy(isLoadingUsers = true)

        scope.launch {
            val startedAt = System.nanoTime()
            val result = supabaseDataProvider?.select(
                table = "users_with_roles",
                columns = "id,email",
                range = QueryRange(0, 9)
            )

            result?.onSuccess { jsonStr ->
                val users = json.decodeFromString<List<ShareUserRow>>(jsonStr)
                logTiming("select(users_with_roles)", elapsedMsSince(startedAt), "${users.size} users")
                usersLoaded = true
                usersListFiltered = false
                state = state.copy(availableUsers = users, isLoadingUsers = false)
            }?.onFailure { exception ->
                val error = exception.message ?: "Unknown error"
                logTiming("select(users_with_roles)", elapsedMsSince(startedAt), error, failed = true)
                state = state.copy(
                    isLoadingUsers = false,
                    errorMessage = error
                )
            }
        }
    }

    fun searchUsersForSharing(query: String) {
        state = state.copy(isLoadingUsers = true)

        scope.launch {
            val startedAt = System.nanoTime()
            val result = supabaseDataProvider?.select(
                table = "users_with_roles",
                columns = "id,email",
                filters = listOf(QueryFilter("email", FilterOperator.ILIKE, "%$query%")),
                range = QueryRange(0, 9)
            )

            result?.onSuccess { jsonStr ->
                val users = json.decodeFromString<List<ShareUserRow>>(jsonStr)
                logTiming("select(users_with_roles, search)", elapsedMsSince(startedAt), "${users.size} users")
                usersLoaded = true
                usersListFiltered = query.isNotBlank()
                state = state.copy(availableUsers = users, isLoadingUsers = false)
            }?.onFailure { exception ->
                val error = exception.message ?: "Unknown error"
                logTiming("select(users_with_roles, search)", elapsedMsSince(startedAt), error, failed = true)
                state = state.copy(
                    isLoadingUsers = false,
                    errorMessage = error
                )
            }
        }
    }

    fun loadAvailableRoles() {
        state = state.copy(isLoadingRoles = true)

        scope.launch {
            val startedAt = System.nanoTime()
            val result = supabaseDataProvider?.select(table = "roles", columns = "id,name,description")

            // Without this, a null supabaseDataProvider (documented optional) runs neither
            // callback: the Roles tab spins forever, AND observeRoleSharePermission's kick is
            // gated on !isLoadingRoles, so a later permission grant can never retry it.
            if (result == null) {
                state = state.copy(isLoadingRoles = false)
                return@launch
            }

            result.onSuccess { jsonStr ->
                val roles = json.decodeFromString<List<ShareRoleRow>>(jsonStr)
                logTiming("select(roles)", elapsedMsSince(startedAt), "${roles.size} roles")
                rolesLoaded = true
                state = state.copy(availableRoles = roles, isLoadingRoles = false)
            }.onFailure { exception ->
                val error = exception.message ?: "Unknown error"
                logTiming("select(roles)", elapsedMsSince(startedAt), error, failed = true)
                state = state.copy(
                    isLoadingRoles = false,
                    errorMessage = error
                )
            }
        }
    }

    // ============================================================================
    // Plugin Store API Key Management
    // ============================================================================

    /**
     * Check if the current user can manage API keys.
     *
     * Kept off the panel's critical open path: pre-warmed after the first
     * successful secrets load, with the Add dropdown's open as a fallback
     * trigger. Runs at most once; a failed check allows a retry.
     */
    fun checkApiKeyPermission() {
        if (apiKeyPermissionChecked) return
        apiKeyPermissionChecked = true // also dedupes concurrent triggers
        scope.launch {
            val canManage = try {
                pluginStoreApiKeyProvider?.canManageApiKeys() ?: false
            } catch (e: CancellationException) {
                apiKeyPermissionChecked = false
                throw e
            } catch (e: Exception) {
                logger.warn(LogCategory.NETWORK, "canManageApiKeys FAILED: ${e.message}")
                apiKeyPermissionChecked = false // allow the next trigger to retry
                return@launch
            }
            state = state.copy(canManageApiKeys = canManage)
        }
    }

    /**
     * Show the Create API Key dialog.
     */
    fun showCreateApiKeyDialog() {
        state = state.copy(showCreateApiKeyDialog = true)
    }

    /**
     * Hide the Create API Key dialog.
     */
    fun hideCreateApiKeyDialog() {
        state = state.copy(
            showCreateApiKeyDialog = false,
            apiKeyCreatedSuccessfully = false
        )
    }

    /**
     * Load all API keys for the current user.
     */
    fun loadApiKeys() {
        if (pluginStoreApiKeyProvider == null) return

        state = state.copy(isLoadingApiKeys = true)

        scope.launch {
            val result = pluginStoreApiKeyProvider.listApiKeys()

            result.onSuccess { keys ->
                state = state.copy(
                    apiKeys = keys,
                    isLoadingApiKeys = false
                )
            }.onFailure { exception ->
                state = state.copy(
                    isLoadingApiKeys = false,
                    errorMessage = exception.message ?: "Failed to load publish keys"
                )
            }
        }
    }

    /**
     * Create a new API key and automatically store it as a secret.
     *
     * @param name Display name for the key
     * @param scopes List of scopes
     * @param expiresInDays Optional expiration in days
     */
    fun createApiKey(
        name: String,
        scopes: List<String> = listOf("publish", "version", "finalize"),
        expiresInDays: Int? = null
    ) {
        if (pluginStoreApiKeyProvider == null) return

        state = state.copy(isOperationInProgress = true)

        scope.launch {
            val result = pluginStoreApiKeyProvider.createApiKey(name, scopes, expiresInDays)

            result.onSuccess { creationResult ->
                // Automatically store the publish key as a secret
                val secretRequest = CreateSecretRequestData(
                    website = "boss_plugin_store_api_key",
                    username = name,
                    password = creationResult.apiKey,
                    notes = "Plugin Store publish key\nScopes: ${scopes.joinToString(", ")}" +
                            (expiresInDays?.let { "\nExpires in: $it days" } ?: ""),
                    tags = listOf("api_key")
                )

                val secretResult = secretDataProvider?.createSecret(secretRequest)

                secretResult?.onSuccess {
                    state = state.copy(
                        isOperationInProgress = false,
                        apiKeyCreatedSuccessfully = true,
                        apiKeys = state.apiKeys + creationResult.keyInfo
                    )
                    // Reload secrets to show the newly created one
                    loadSecrets()
                }?.onFailure { secretException ->
                    // API key created but secret storage failed - still show as partial success
                    state = state.copy(
                        isOperationInProgress = false,
                        apiKeyCreatedSuccessfully = true,
                        apiKeys = state.apiKeys + creationResult.keyInfo,
                        errorMessage = "Publish key created but not stored as a secret: ${secretException.message}"
                    )
                }

                // If secretDataProvider is null, just mark as success
                if (secretDataProvider == null) {
                    state = state.copy(
                        isOperationInProgress = false,
                        apiKeyCreatedSuccessfully = true,
                        apiKeys = state.apiKeys + creationResult.keyInfo
                    )
                }
            }.onFailure { exception ->
                state = state.copy(
                    isOperationInProgress = false,
                    errorMessage = exception.message ?: "Failed to create the publish key"
                )
            }
        }
    }

    /**
     * Clear the API key created success flag.
     */
    fun clearApiKeyCreatedFlag() {
        state = state.copy(apiKeyCreatedSuccessfully = false)
    }

    /**
     * Revoke an API key.
     */
    fun revokeApiKey(keyId: String) {
        if (pluginStoreApiKeyProvider == null) return

        state = state.copy(isOperationInProgress = true)

        scope.launch {
            val result = pluginStoreApiKeyProvider.revokeApiKey(keyId)

            result.onSuccess {
                state = state.copy(
                    isOperationInProgress = false,
                    apiKeys = state.apiKeys.filter { it.id != keyId }
                )
            }.onFailure { exception ->
                state = state.copy(
                    isOperationInProgress = false,
                    errorMessage = exception.message ?: "Failed to revoke the publish key"
                )
            }
        }
    }

    /**
     * Show the API Keys list dialog.
     */
    fun showApiKeysListDialog() {
        state = state.copy(showApiKeysListDialog = true)
        loadApiKeys()
    }

    /**
     * Hide the API Keys list dialog.
     */
    fun hideApiKeysListDialog() {
        state = state.copy(showApiKeysListDialog = false)
    }
}

/**
 * State for Secret Manager panel - matches bundled plugin state
 */
data class SecretManagerState(
    val secrets: List<SecretEntryData> = emptyList(),
    /** Per-row server authorization, separated from the binary-stable secret value object. */
    val secretAccess: Map<String, SecretAccessState> = emptyMap(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val isOperationInProgress: Boolean = false,
    val errorMessage: String? = null,
    val searchQuery: String = "",
    val showCreateDialog: Boolean = false,
    val showEditDialog: Boolean = false,
    val showDeleteDialog: Boolean = false,
    val selectedSecret: SecretEntryData? = null,
    val expandedSecretIds: Set<String> = emptySet(),
    val visiblePasswordIds: Set<String> = emptySet(),
    val pageSize: Int = 50,
    val currentOffset: Int = 0,
    val hasMore: Boolean = true,
    val lastLoadDurationMs: Long? = null,
    // Sharing-related state
    val showShareDialog: Boolean = false,
    val secretShares: List<SecretShareData> = emptyList(),
    val secretShareTargets: Map<String, SecretShareTargetState> = emptyMap(),
    val isLoadingShares: Boolean = false,
    // Available users and roles for sharing
    val availableUsers: List<ShareUserRow> = emptyList(),
    val availableRoles: List<ShareRoleRow> = emptyList(),
    val isLoadingUsers: Boolean = false,
    val isLoadingRoles: Boolean = false,
    /**
     * Whether to offer role targets in the share dialog.
     *
     * A role share fans out to every holder, and `user` is a descendant of every role,
     * so sharing with it publishes the credential to the whole deployment. Until
     * 20260809000000 the only thing preventing that was that non-admins could not open
     * this panel; now that everyone can, the server refuses an ungranted role share and
     * this hides the tab that would produce one. UI convenience, not the enforcement.
     */
    val canShareWithRoles: Boolean = false,
    // Plugin Store API Key management
    val canManageApiKeys: Boolean = false,
    val showCreateApiKeyDialog: Boolean = false,
    val showApiKeysListDialog: Boolean = false,
    val apiKeys: List<ApiKeyInfo> = emptyList(),
    val isLoadingApiKeys: Boolean = false,
    val apiKeyCreatedSuccessfully: Boolean = false, // Flag to show success message
    // AI provider key entry (writes through ProviderCredentialStore, not createSecret,
    // so the entry is tagged and shaped the way the AI Providers panel expects)
    val showAiProviderKeyDialog: Boolean = false,
    val aiProviderKeyProviderId: String = ProviderRegistry.default.id,
    val aiProviderKeyDraft: String = "",
    val canAddAiProviderKey: Boolean = false,
    /** Where each provider's credential currently comes from, for add-vs-change. */
    val aiProviderSources: Map<String, CredentialSource> = emptyMap()
)

/** UI-safe projection of the API access envelope. */
data class SecretAccessState(
    val orgId: String? = null,
    val orgSlug: String? = null,
    val isOrgOwned: Boolean = false,
    val canManage: Boolean = false,
) {
    companion object {
        val READ_ONLY = SecretAccessState()
    }
}

/** Organisation identity carried beside the binary-stable share row. */
data class SecretShareTargetState(
    val orgId: String? = null,
    val orgSlug: String? = null,
)

private fun SecretEntryWithAccessData.toAccessState(): SecretAccessState =
    SecretAccessState(
        orgId = orgId,
        orgSlug = orgSlug,
        isOrgOwned = isOrgOwned,
        canManage = canManage,
    )
