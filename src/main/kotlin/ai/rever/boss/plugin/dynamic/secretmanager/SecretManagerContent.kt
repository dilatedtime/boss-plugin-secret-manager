package ai.rever.boss.plugin.dynamic.secretmanager

import ai.rever.boss.plugin.api.*
import ai.rever.boss.plugin.dynamic.secretmanager.ai.AiProvidersPanel
import ai.rever.boss.plugin.dynamic.secretmanager.ai.AiProvidersViewModel
import ai.rever.boss.plugin.dynamic.secretmanager.ai.CredentialSource
import ai.rever.boss.plugin.dynamic.secretmanager.ai.ProviderRegistry
import ai.rever.boss.plugin.scrollbar.getPanelScrollbarConfig
import ai.rever.boss.plugin.scrollbar.lazyListScrollbar
import ai.rever.boss.plugin.ui.BossAlertDialog
import ai.rever.boss.plugin.ui.BossBadge
import ai.rever.boss.plugin.ui.BossCard
import ai.rever.boss.plugin.ui.BossDialog
import ai.rever.boss.plugin.ui.BossEmptyState
import ai.rever.boss.plugin.ui.BossSearchBar
import ai.rever.boss.plugin.ui.BossTabIndicator
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.plugin.ui.BossThemeColors
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The two halves of the panel.
 *
 * They partition the vault rather than overlap it: [SECRETS] is what the caller can manage
 * (their own secrets and their organisation's), [SHARED_WITH_ME] is what other people have
 * shared with them, read-only. These were two separate plugins, and both listed the caller's
 * own secrets - which is the confusion this split removes.
 */
enum class SecretPanelSection {
    SECRETS,
    SHARED_WITH_ME,

    /**
     * AI provider configuration: the same panel the host serves at Settings, AI Providers.
     *
     * One definition rendered in two places rather than a second copy. It is here because this
     * plugin owns every AI credential in BOSS and the panel that holds them was reachable only
     * through the host's Settings window - two clicks and a different window away from the vault
     * the keys are actually stored in.
     */
    AI_PROVIDERS,
}

/**
 * Secret Manager panel content (Dynamic Plugin).
 *
 * Displays and manages user secrets with CRUD and sharing operations, plus a read-only
 * section for secrets shared with the caller.
 * Also supports Plugin Store API key management for admin/plugin_admin users.
 * UI matches the bundled plugin's Card-based design.
 *
 * Both ViewModels and the selected section are owned by [SecretManagerComponent] so state
 * survives the panel leaving and re-entering composition.
 */
@Composable
fun SecretManagerContent(
    viewModel: SecretManagerViewModel,
    sharedSecretsViewModel: SharedSecretsViewModel,
    selectedSection: SecretPanelSection,
    onSelectSection: (SecretPanelSection) -> Unit,
    /**
     * The AI providers ViewModel, or null on a host that cannot serve one.
     *
     * A **supplier**, not the value: it is built inside `registerAiProviderSettings`'s
     * `LinkageError` guard, which runs after `registerPanel`, so anything reading it at
     * registration time would read null forever. Resolved when the section is first shown
     * instead. Null means AI settings registration could not link, and the tab is not offered
     * at all - a tab whose only content is "not available here" is noise.
     */
    aiProvidersViewModel: () -> AiProvidersViewModel? = { null },
) {
    BossTheme {
        if (!viewModel.isAvailable()) {
            NoProviderMessage()
        } else {
            SecretManagerView(
                viewModel = viewModel,
                sharedSecretsViewModel = sharedSecretsViewModel,
                aiProvidersViewModel = aiProvidersViewModel,
                selectedSection = selectedSection,
                onSelectSection = onSelectSection,
            )
        }
    }
}

@Composable
private fun NoProviderMessage() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BossThemeColors.BackgroundColor)
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                Icons.Default.Lock,
                contentDescription = null,
                tint = BossThemeColors.TextSecondary,
                modifier = Modifier.size(48.dp)
            )
            Text(
                "Secret Manager",
                color = BossThemeColors.TextPrimary,
                style = SecretPanelType.title
            )
            Text(
                "Secret provider not available",
                color = BossThemeColors.TextSecondary,
                style = SecretPanelType.body
            )
            Text(
                "Please ensure the host provides secret management access",
                color = BossThemeColors.TextSecondary.copy(alpha = 0.6f),
                style = SecretPanelType.caption
            )
        }
    }
}

/**
 * Main view composable for Secret Manager panel
 */
@Composable
private fun SecretManagerView(
    viewModel: SecretManagerViewModel,
    sharedSecretsViewModel: SharedSecretsViewModel,
    selectedSection: SecretPanelSection,
    onSelectSection: (SecretPanelSection) -> Unit,
    aiProvidersViewModel: () -> AiProvidersViewModel? = { null },
) {
    val state = viewModel.state
    val sharedState by sharedSecretsViewModel.state.collectAsState()
    val listState = rememberLazyListState()
    // Hoisted for the same reason `listState` is: each section leaves composition while the
    // other is on screen (and on every Refresh), so a state remembered down there would drop
    // the scroll position every time the user looks at the other tab and comes back.
    val sharedListState = rememberLazyListState()
    val clipboardManager = LocalClipboardManager.current
    // Resolved here rather than at registration: see the parameter's own note. `remember` with no
    // key is right - the supplier reads a field that is set once, before any panel is created.
    val aiViewModel = remember { aiProvidersViewModel() }
    var showAddDropdown by remember { mutableStateOf(false) }

    // Fetch on first entry into the section, not on panel open: this is a second secrets RPC
    // and most panel opens never reach the section. Re-runs only when the section changes.
    LaunchedEffect(selectedSection) {
        if (selectedSection == SecretPanelSection.SHARED_WITH_ME) {
            sharedSecretsViewModel.ensureLoaded()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BossThemeColors.BackgroundColor)
            .padding(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // One row switches section and carries the panel's two actions. There is no title:
            // the panel chrome prints "Secret Manager" directly above, and at a real sidebar width
            // the in-panel copy truncated to "Secret M..." - which is how the duplication was
            // noticed. Deleting it then left a 56dp band holding two icons and nothing else, so
            // the actions came down onto the tab strip's baseline instead of floating above it.
            SectionTabs(
                selectedSection = selectedSection,
                // allShared, not the filtered view: typing in the shared section's filter
                // would otherwise make the tab report "(1)" while forty are loaded.
                sharedCount = sharedState.allShared.size,
                showAiSection = aiViewModel != null,
                onSelectSection = onSelectSection,
            ) {
                // Refresh button. Refetches whichever section is on screen - the two read
                // different RPCs, so refreshing the hidden one would look like doing nothing.
                val isRefreshing =
                    when (selectedSection) {
                        SecretPanelSection.SECRETS -> state.isLoading
                        SecretPanelSection.SHARED_WITH_ME -> sharedState.isLoading
                        // The AI section's own rows carry their spinners, and its refresh is
                        // several independent fetches rather than one load, so there is no single
                        // flag to disable the button on.
                        SecretPanelSection.AI_PROVIDERS -> false
                    }
                IconButton(
                    onClick = {
                        when (selectedSection) {
                            SecretPanelSection.SECRETS -> viewModel.loadSecrets()
                            SecretPanelSection.SHARED_WITH_ME -> sharedSecretsViewModel.refresh()
                            SecretPanelSection.AI_PROVIDERS ->
                                aiViewModel?.let {
                                    // All three, because all three can go stale while the panel
                                    // sits open: a key edited elsewhere, a gateway installed in
                                    // the Toolbox, a CLI signed into in a terminal.
                                    it.refreshConnections()
                                    it.checkGateway()
                                    it.refreshCliEngines()
                                }
                        }
                    },
                    enabled = !isRefreshing,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Refresh",
                        tint = if (isRefreshing) BossThemeColors.TextMuted else BossThemeColors.TextSecondary,
                        modifier = Modifier.size(17.dp)
                    )
                }

                // Add button with dropdown menu
                Box {
                    IconButton(
                        onClick = {
                            showAddDropdown = true
                            // Fallback trigger; normally pre-warmed after the first secrets load
                            viewModel.checkApiKeyPermission()
                        },
                        enabled = !state.isLoading,
                        modifier = Modifier.size(28.dp)
                    ) {
                        // Accent, not success-green: this is the panel's primary action, and
                        // green here reads as a state ("all good") rather than an invitation.
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "Add",
                            tint = BossThemeColors.AccentColor,
                            modifier = Modifier.size(19.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = showAddDropdown,
                        onDismissRequest = { showAddDropdown = false },
                        modifier = Modifier.background(BossThemeColors.SurfaceColor)
                    ) {
                        // Add Secret option (always visible)
                        DropdownMenuItem(
                            onClick = {
                                showAddDropdown = false
                                // Back to Secrets first: a secret created from the read-only
                                // tab appears in the other one, so leaving the user here would
                                // look like the create silently did nothing. (Refresh is
                                // section-aware for the same reason.)
                                onSelectSection(SecretPanelSection.SECRETS)
                                viewModel.showCreateDialog()
                            }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = BossThemeColors.TextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text("Add secret", color = BossThemeColors.TextPrimary, style = SecretPanelType.body)
                            }
                        }

                        // Add an AI provider API key. Written through
                        // ProviderCredentialStore so Settings → AI Providers recognises it.
                        if (state.canAddAiProviderKey) {
                            DropdownMenuItem(
                                onClick = {
                                    showAddDropdown = false
                                    // Same reason as Add Secret: the new entry lands in the
                                    // managed list.
                                    onSelectSection(SecretPanelSection.SECRETS)
                                    viewModel.showAiProviderKeyDialog()
                                }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        Icons.Default.AutoAwesome,
                                        contentDescription = null,
                                        tint = BossThemeColors.TextSecondary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        "Add AI provider key",
                                        color = BossThemeColors.TextPrimary,
                                        style = SecretPanelType.body
                                    )
                                }
                            }
                        }

                        // Named for the job, not the mechanism. "Create API Key" was the third
                        // unrelated thing in this panel called an API key - alongside an AI
                        // provider's key and the "this is an API key" tag on an ordinary secret -
                        // and the only one that publishes a plugin to the store. A user who came
                        // here to release a plugin could not tell which item to press.
                        // Visible for admin/plugin_admin.
                        if (state.canManageApiKeys) {
                            Divider(color = BossThemeColors.BorderColor)

                            DropdownMenuItem(
                                onClick = {
                                    showAddDropdown = false
                                    viewModel.showCreateApiKeyDialog()
                                }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        Icons.Default.VpnKey,
                                        contentDescription = null,
                                        tint = BossThemeColors.TextSecondary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        "Create Plugin Store publish key",
                                        color = BossThemeColors.TextPrimary,
                                        style = SecretPanelType.body
                                    )
                                }
                            }

                            DropdownMenuItem(
                                onClick = {
                                    showAddDropdown = false
                                    viewModel.showApiKeysListDialog()
                                }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        Icons.Default.List,
                                        contentDescription = null,
                                        tint = BossThemeColors.TextSecondary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        "Manage publish keys",
                                        color = BossThemeColors.TextPrimary,
                                        style = SecretPanelType.body
                                    )
                                }
                            }
                        }
                    }
                }
            }

            when (selectedSection) {
                SecretPanelSection.SECRETS ->
                    SecretsSection(
                        viewModel = viewModel,
                        listState = listState,
                        clipboardManager = clipboardManager,
                        onOpenAiProvider = { providerId ->
                            if (aiViewModel?.requestProviderOnEntry(providerId) == true) {
                                onSelectSection(SecretPanelSection.AI_PROVIDERS)
                            } else {
                                viewModel.reportAiProviderUnavailable()
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )

                SecretPanelSection.SHARED_WITH_ME ->
                    SharedSecretsSection(
                        state = sharedState,
                        listState = sharedListState,
                        onSearch = { sharedSecretsViewModel.search(it) },
                        onToggleMetadata = { sharedSecretsViewModel.toggleMetadataExpanded(it) },
                        onCopySecret = { sharedSecretsViewModel.copySecretToClipboard(it, clipboardManager) },
                        onLoadMore = { sharedSecretsViewModel.loadMore() },
                        onRefresh = { sharedSecretsViewModel.refresh() },
                        onDismissError = { sharedSecretsViewModel.clearError() },
                        modifier = Modifier.weight(1f),
                    )

                SecretPanelSection.AI_PROVIDERS ->
                    // The same composable the host renders at Settings, AI Providers, from one
                    // definition. It scrolls itself, so it takes the remaining height and no
                    // scroll container of its own - nesting two would measure with infinite
                    // height and crash.
                    //
                    // `aiViewModel` cannot be null here: the tab is only offered when it is not.
                    // Guarded anyway rather than asserted, because the day the tab is offered
                    // some other way, a blank section beats a crash inside a credentials panel.
                    aiViewModel?.let { model ->
                        AiProvidersPanel(viewModel = model, modifier = Modifier.weight(1f))
                    }
            }
        }
    }

    // Dialogs
    if (state.showCreateDialog) {
        CreateSecretDialog(
            onConfirm = { viewModel.createSecret(it) },
            onDismiss = { viewModel.hideCreateDialog() },
            isLoading = state.isOperationInProgress
        )
    }



    if (state.showAiProviderKeyDialog) {
        AiProviderKeyDialog(
            selectedProviderId = state.aiProviderKeyProviderId,
            sources = state.aiProviderSources,
            keyDraft = state.aiProviderKeyDraft,
            isLoading = state.isOperationInProgress,
            errorMessage = state.errorMessage,
            onProviderChange = { viewModel.setAiProviderKeyProvider(it) },
            onKeyChange = { viewModel.setAiProviderKeyDraft(it) },
            onOpenConsole = { viewModel.openAiProviderConsole() },
            onConfirm = { viewModel.saveAiProviderKey() },
            onDismiss = { viewModel.hideAiProviderKeyDialog() }
        )
    }

    if (state.showEditDialog && state.selectedSecret != null) {
        EditSecretDialog(
            secret = state.selectedSecret,
            onConfirm = { viewModel.updateSecret(it) },
            onDismiss = { viewModel.hideEditDialog() },
            isLoading = state.isOperationInProgress
        )
    }

    if (state.showDeleteDialog && state.selectedSecret != null) {
        DeleteConfirmationDialog(
            secret = state.selectedSecret,
            onConfirm = { viewModel.deleteSecret(state.selectedSecret.id) },
            onDismiss = { viewModel.hideDeleteDialog() },
            isLoading = state.isOperationInProgress
        )
    }

    if (state.showShareDialog && state.selectedSecret != null) {
        ShareSecretDialog(
            secret = state.selectedSecret,
            shares = state.secretShares,
            availableUsers = state.availableUsers,
            availableRoles = state.availableRoles,
            canShareWithRoles = state.canShareWithRoles,
            onShare = { viewModel.shareSecret(it) },
            onRevoke = { userId, roleId ->
                viewModel.unshareSecret(state.selectedSecret.id, userId, roleId)
            },
            onDismiss = { viewModel.hideShareDialog() },
            onSearchUsers = { query ->
                if (query.isBlank()) viewModel.loadAvailableUsers()
                else viewModel.searchUsersForSharing(query)
            },
            isLoading = state.isOperationInProgress,
            isLoadingShares = state.isLoadingShares,
            isLoadingUsers = state.isLoadingUsers
        )
    }

    // API Key dialogs
    if (state.showCreateApiKeyDialog) {
        CreateApiKeyDialog(
            onConfirm = { name, scopes, expiresInDays ->
                viewModel.createApiKey(name, scopes, expiresInDays)
            },
            onDismiss = { viewModel.hideCreateApiKeyDialog() },
            isSuccess = state.apiKeyCreatedSuccessfully,
            isLoading = state.isOperationInProgress
        )
    }

    if (state.showApiKeysListDialog) {
        ApiKeysListDialog(
            apiKeys = state.apiKeys,
            onRevoke = { keyId -> viewModel.revokeApiKey(keyId) },
            onDismiss = { viewModel.hideApiKeysListDialog() },
            onCreateNew = {
                viewModel.hideApiKeysListDialog()
                viewModel.showCreateApiKeyDialog()
            },
            isLoading = state.isLoadingApiKeys || state.isOperationInProgress
        )
    }
}

/**
 * The section that manages secrets: the caller's own plus their organisation's, read through
 * `getUserSecrets` with server-side search.
 *
 * Extracted from [SecretManagerView] unchanged when the panel gained sections. [listState] is
 * hoisted rather than remembered here on purpose: this composable leaves composition when the
 * other section is on screen, and a local `rememberLazyListState` would drop the scroll
 * position every time the user looks at their shared secrets and comes back.
 */
@Composable
private fun SecretsSection(
    viewModel: SecretManagerViewModel,
    listState: LazyListState,
    clipboardManager: ClipboardManager,
    /** Reveal a provider in the AI section. Given its id, which is the secret's `website`. */
    onOpenAiProvider: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = viewModel.state

    Column(modifier = modifier.fillMaxSize()) {
        // Search bar
        PanelSearchField(
            query = state.searchQuery,
            onQueryChange = { viewModel.searchSecrets(it) },
            placeholder = "Search secrets",
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
        )

        // Secret count
        Text(
            "${state.secrets.size} secret${if (state.secrets.size != 1) "s" else ""}" +
                (state.lastLoadDurationMs?.let { " · last fetch ${formatLoadDuration(it)}" } ?: ""),
            color = BossThemeColors.TextSecondary,
            style = SecretPanelType.meta,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // Content based on state
        when {
            state.isLoading -> {
                LoadingView()
            }
            state.errorMessage != null -> {
                ErrorView(
                    message = state.errorMessage,
                    onRetry = { viewModel.loadSecrets() },
                    onDismiss = { viewModel.clearError() }
                )
            }
            state.secrets.isEmpty() -> {
                EmptyView(
                    searchQuery = state.searchQuery,
                    onAddSecret = { viewModel.showCreateDialog() }
                )
            }
            else -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .lazyListScrollbar(
                            listState = listState,
                            direction = Orientation.Vertical,
                            config = getPanelScrollbarConfig()
                        ),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(state.secrets, key = { it.id }) { secret ->
                        SecretCard(
                            secret = secret,
                            isPasswordVisible = state.visiblePasswordIds.contains(secret.id),
                            isExpanded = state.expandedSecretIds.contains(secret.id),
                            onTogglePassword = { viewModel.togglePasswordVisibility(secret.id) },
                            onToggleExpand = { viewModel.toggleMetadataExpanded(secret.id) },
                            onEdit = { viewModel.showEditDialog(secret) },
                            onDelete = { viewModel.showDeleteDialog(secret) },
                            onShare = { viewModel.showShareDialog(secret) },
                            onCopyPassword = { viewModel.copyPasswordToClipboard(secret, clipboardManager) },
                            isAiProvider = viewModel.isAiProviderSecret(secret),
                            aiProviderLabel = viewModel.aiProviderDisplayName(secret),
                            // `website` holds the provider id, which is what makes this land on
                            // the right row rather than at the top of the list.
                            onOpenAiProviderSettings = { onOpenAiProvider(secret.website) }
                        )
                    }

                    // Load more trigger
                    if (state.hasMore && !state.isLoadingMore) {
                        item {
                            LaunchedEffect(Unit) {
                                viewModel.loadMoreSecrets()
                            }
                        }
                    }

                    // Loading more indicator
                    if (state.isLoadingMore) {
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = BossThemeColors.AccentColor,
                                    strokeWidth = 2.dp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The section switcher.
 *
 * Hand-built rather than a Material `TabRow`: that paints its own indicator, ripple and 48dp
 * minimum height, none of which belong to this design system, and at a real sidebar width it wrapped
 * "Shared with me" onto two lines. This is the system's own vocabulary instead - `label` type for the
 * tab names, `BossTabIndicator` (the shared 3dp accent marker) under the selected one, and a hairline
 * rule carrying the full width so the tabs read as attached to the content below rather than floating.
 *
 * The shared count is a `BossBadge`, the same count chip the rest of BOSS uses, and it appears only
 * once that section has loaded: it loads lazily, and a "0" printed before anything was fetched states
 * a fact nobody has checked.
 */
@Composable
private fun SectionTabs(
    selectedSection: SecretPanelSection,
    sharedCount: Int,
    showAiSection: Boolean,
    onSelectSection: (SecretPanelSection) -> Unit,
    actions: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        // Bottom-aligned so the selected tab's indicator lands on the rule below the row. The
        // tabs take their natural width rather than half each: an underline stretched across
        // half a sidebar stops reading as "this word is selected".
        Row(
            modifier = Modifier.fillMaxWidth().selectableGroup(),
            verticalAlignment = Alignment.Bottom,
        ) {
            SectionTab(
                label = "SECRETS",
                selected = selectedSection == SecretPanelSection.SECRETS,
                onClick = { onSelectSection(SecretPanelSection.SECRETS) },
            )
            SectionTab(
                label = "SHARED",
                selected = selectedSection == SecretPanelSection.SHARED_WITH_ME,
                onClick = { onSelectSection(SecretPanelSection.SHARED_WITH_ME) },
                // Straight through. There was a `hasLoadedShared` guard here to keep a `0` off
                // the tab before anything had been fetched, but `BossBadge` declines to draw a
                // zero itself (`if (count > 0)`), so the flag decided nothing and the local
                // `badge > 0` check restated the component's own rule.
                badge = sharedCount,
                modifier = Modifier.padding(start = 20.dp),
            )
            // Absent, not disabled, when AI settings registration could not link: the section
            // cannot render there at all, and a tab that only ever says "not available" is worse
            // than one tab fewer.
            if (showAiSection) {
                SectionTab(
                    label = "AI",
                    selected = selectedSection == SecretPanelSection.AI_PROVIDERS,
                    onClick = { onSelectSection(SecretPanelSection.AI_PROVIDERS) },
                    modifier = Modifier.padding(start = 20.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Row(
                modifier = Modifier.padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                actions()
            }
        }
        // The rule runs the full width under both tabs; the indicator sits on top of it.
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(BossThemeColors.BorderColor),
        )
    }
}

@Composable
private fun SectionTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: Int = 0,
) {
    // `IntrinsicSize.Max` is load-bearing, not tidying. The indicator is `fillMaxWidth()`, and
    // in an unweighted Row that resolves to the whole *remaining* width - so the first tab ate
    // the row, pushed the second one off the edge and took the actions with it. Constraining the
    // Column to its content's natural width makes the underline measure the label. (The tabs used
    // to be `weight(1f)` each, which bounded it by accident and is why this only broke now.)
    // `selectable` rather than `clickable`: Material's `Tab` supplied the role and the selected
    // state to the accessibility tree, and hand-building the strip dropped both. A screen reader
    // otherwise announces two unlabelled buttons and never says which one is current.
    Column(
        modifier =
            modifier
                .width(IntrinsicSize.Max)
                .selectable(selected = selected, onClick = onClick, role = Role.Tab),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.padding(top = 2.dp, bottom = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = SecretPanelType.label,
                // AccentColor for the selected tab is the design system's "signal = live/now".
                color = if (selected) BossThemeColors.AccentColor else BossThemeColors.TextSecondary,
                maxLines = 1,
            )
            BossBadge(count = badge)
        }
        // Always rendered, transparent when unselected: selecting a tab must not shift the row,
        // and the previous `else Box(height(3.dp))` hardcoded a second copy of the indicator's
        // height - so a host that changed it would have made this row jump.
        BossTabIndicator(
            modifier = Modifier.fillMaxWidth().alpha(if (selected) 1f else 0f),
        )
    }
}

/**
 * The panel's search field: `BossSearchBar` plus the clear button it does not carry.
 *
 * `BossSearchBar` is the same field the rest of BOSS paints (surface fill, hairline border, 14dp
 * muted magnifier, accent caret), and this panel had **two** hand-rolled copies of it that had
 * already drifted apart in padding and placeholder colour. Now there is one, used by both
 * sections, which is what the two wrappers this replaced each claimed to be.
 *
 * **The clear button is not decoration.** The managed section's hand-rolled field had one and
 * `BossSearchBar` does not, so adopting the component on its own silently removed the only
 * pointer-driven way to reset a filter - a control lost inside a change that was supposed to be
 * about type and colour. It goes beside the field rather than inside it, because the component
 * gives no slot within its border and overlaying one would put the button on top of the tail of
 * a long query.
 *
 * **It takes the space only when there is something to clear.** Reserving the slot permanently
 * was the first version and left the field ending 26dp short of the cards below it - a visible
 * step in the panel's left-to-right edge, in the state the user is looking at almost all the
 * time. Trading that for a one-off narrowing on the first keystroke is the right way round: the
 * resting state is the one that has to line up, and while typing the eye is on the text.
 *
 * The shared section gains the button it never had, since two search fields one tab apart
 * behaving differently is the same incoherence in a different place.
 */
@Composable
internal fun PanelSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BossSearchBar(
            query = query,
            onQueryChange = onQueryChange,
            modifier = Modifier.weight(1f).height(32.dp),
            placeholder = placeholder,
        )
        if (query.isNotEmpty()) {
            IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(26.dp)) {
                Icon(
                    Icons.Default.Clear,
                    contentDescription = "Clear search",
                    tint = BossThemeColors.TextSecondary,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}

private fun formatLoadDuration(ms: Long): String = when {
    ms < 1000 -> "${ms}ms"
    ms < 60_000 -> "${ms / 1000}.${(ms % 1000) / 100}s"
    else -> "${ms / 60_000}m ${(ms % 60_000) / 1000}s"
}

@Composable
private fun LoadingView() {
    var elapsedSeconds by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            elapsedSeconds++
        }
    }
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = BossThemeColors.AccentColor)
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                if (elapsedSeconds < 3) "Loading secrets..." else "Loading secrets... ${elapsedSeconds}s",
                color = BossThemeColors.TextSecondary,
                style = SecretPanelType.meta
            )
            if (elapsedSeconds >= 10) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Still waiting on the server — the network may be slow",
                    color = BossThemeColors.TextSecondary.copy(alpha = 0.6f),
                    style = SecretPanelType.caption
                )
            }
        }
    }
}

@Composable
private fun ErrorView(
    message: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(24.dp)
        ) {
            Icon(
                Icons.Default.Error,
                contentDescription = null,
                tint = BossThemeColors.ErrorColor,
                modifier = Modifier.size(32.dp)
            )
            Text(message, color = BossThemeColors.TextSecondary, style = SecretPanelType.meta)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(backgroundColor = BossThemeColors.AccentColor)
                ) {
                    Text("Retry", color = BossThemeColors.TextPrimary, style = SecretPanelType.meta)
                }
                TextButton(onClick = onDismiss) {
                    Text("Dismiss", color = BossThemeColors.TextSecondary, style = SecretPanelType.meta)
                }
            }
        }
    }
}

@Composable
private fun EmptyView(
    searchQuery: String,
    onAddSecret: () -> Unit
) {
    // `BossEmptyState` here too, not just in the shared section. Half-converting it left the two
    // sections hand-rolling and importing the same empty state one tab apart, with the copy for
    // the identical situation already drifting ("No results found" against "No results").
    // `BossEmptyState` has no action slot, so the button sits under it in the same Column.
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            BossEmptyState(
                icon = if (searchQuery.isBlank()) Icons.Default.Lock else Icons.Default.Search,
                message = if (searchQuery.isBlank()) "No secrets yet" else "No results",
                // Each description names its own control: this section searches the server,
                // the shared one filters what it already has, and telling the user to change
                // a "filter" when the box says Search sends them looking for one.
                description =
                    if (searchQuery.isBlank()) "Add your first secret to get started"
                    else "Try a different search term"
            )
            if (searchQuery.isBlank()) {
                Button(
                    onClick = onAddSecret,
                    colors = ButtonDefaults.buttonColors(backgroundColor = BossThemeColors.AccentColor)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add secret", color = BossThemeColors.TextPrimary, style = SecretPanelType.meta)
                }
            }
        }
    }
}

@Composable
private fun SecretCard(
    secret: SecretEntryData,
    isPasswordVisible: Boolean,
    isExpanded: Boolean,
    onTogglePassword: () -> Unit,
    onToggleExpand: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    onCopyPassword: () -> Unit,
    isAiProvider: Boolean = false,
    aiProviderLabel: String = "",
    onOpenAiProviderSettings: () -> Unit = {}
) {
    val copyScope = rememberCoroutineScope()
    var justCopied by remember { mutableStateOf(false) }
    val isApiKey = secret.tags.contains("api_key")
    val metadata = secret.metadata
    val hasDetails = secret.tags.isNotEmpty() ||
        !secret.notes.isNullOrBlank() ||
        secret.expirationDate != null ||
        (metadata != null && metadata.twofaEnabled)

    BossCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // AI provider entries are configuration, not a password: the useful action is to
            // open the place where the key can be tested and a model picked. That used to be the
            // host's Settings window, two clicks and a different window away from the vault the
            // key is stored in; it is now the AI tab of this panel, with this provider selected.
            if (isAiProvider) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(BossThemeColors.AccentColor.copy(alpha = 0.12f))
                        .clickable(onClick = onOpenAiProviderSettings)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = BossThemeColors.AccentColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "AI provider${if (aiProviderLabel.isNotBlank()) " · $aiProviderLabel" else ""}",
                        color = BossThemeColors.TextPrimary,
                        style = SecretPanelType.metaStrong,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "Open →",
                        color = BossThemeColors.AccentColor,
                        style = SecretPanelType.meta
                    )
                }
            }

            // Header: Website/Service and Username with icons, actions on the right
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Website/Service with icon
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            if (isApiKey) Icons.Default.Api else Icons.Default.Language,
                            contentDescription = if (isApiKey) "Service" else "Website",
                            tint = if (isApiKey) BossThemeColors.WarningColor else BossThemeColors.TextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = secret.website,
                            color = BossThemeColors.TextPrimary,
                            style = SecretPanelType.bodyStrong,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    // Username/Key Name with icon
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            if (isApiKey) Icons.Default.Key else Icons.Default.Person,
                            contentDescription = if (isApiKey) "Key Name" else "Username",
                            tint = BossThemeColors.TextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = secret.username,
                            color = BossThemeColors.TextSecondary,
                            style = SecretPanelType.meta,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // The three actions were a hardcoded blue, success-green and error-red, on
                // every card - a row of traffic lights repeated down the list, none of which
                // meant anything. Colour here is reserved for the one action that cannot be
                // undone; Share and Edit are ordinary controls and read as text does.
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    IconButton(onClick = onShare, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = "Share",
                            tint = BossThemeColors.TextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    IconButton(onClick = onEdit, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "Edit",
                            tint = BossThemeColors.TextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = BossThemeColors.ErrorColor.copy(alpha = 0.75f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Divider(color = BossThemeColors.BorderColor, thickness = 1.dp)

            // Password field with copy and visibility toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BossThemeColors.BackgroundColor, RoundedCornerShape(4.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    // The confirmation was "Copied - clipboard clears in 45s", which wrapped or
                    // ellipsised at a real sidebar width. The middot form is the one the count
                    // line above the list already uses.
                    text = when {
                        justCopied -> "Copied · clears in 45s"
                        isPasswordVisible -> secret.password
                        else -> "••••••••"
                    },
                    color = when {
                        justCopied -> BossThemeColors.SuccessColor
                        isPasswordVisible -> BossThemeColors.TextPrimary
                        else -> BossThemeColors.TextSecondary
                    },
                    // A credential is read a character at a time, which is what the data role
                    // is for. The confirmation borrows it rather than switching family mid-row.
                    style = SecretPanelType.data,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                IconButton(
                    onClick = {
                        onCopyPassword()
                        justCopied = true
                        copyScope.launch {
                            delay(4000)
                            justCopied = false
                        }
                    },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        if (justCopied) Icons.Default.Check else Icons.Default.ContentCopy,
                        contentDescription = if (isApiKey) "Copy API Key" else "Copy password",
                        tint = if (justCopied) BossThemeColors.SuccessColor else BossThemeColors.TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(onClick = onTogglePassword, modifier = Modifier.size(24.dp)) {
                    Icon(
                        if (isPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (isPasswordVisible) "Hide password" else "Show password",
                        tint = BossThemeColors.TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // Show/Hide details (tags, notes, expiration, 2FA)
            if (hasDetails) {
                // Left-aligned and quiet. Full-width SpaceBetween put the label and its own
                // chevron at opposite ends of the card, reading as two unrelated controls, and
                // success-green made the least important thing on the card the loudest.
                Row(
                    modifier = Modifier
                        .clickable { onToggleExpand() }
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (isExpanded) "Hide details" else "Show details",
                        color = BossThemeColors.TextSecondary,
                        style = SecretPanelType.meta
                    )
                    Icon(
                        if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (isExpanded) "Hide details" else "Show details",
                        tint = BossThemeColors.TextSecondary,
                        modifier = Modifier.size(14.dp)
                    )
                }

                if (isExpanded) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(BossThemeColors.BackgroundColor, RoundedCornerShape(4.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Tags
                        if (secret.tags.isNotEmpty()) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Label,
                                    contentDescription = "Tags",
                                    tint = BossThemeColors.AccentColor,
                                    modifier = Modifier.size(14.dp)
                                )
                                secret.tags.forEach { tag ->
                                    TagBadge(tag)
                                }
                            }
                        }

                        // Notes
                        secret.notes?.takeIf { it.isNotBlank() }?.let { notes ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Icon(
                                    Icons.Default.Notes,
                                    contentDescription = "Notes",
                                    tint = BossThemeColors.TextSecondary,
                                    modifier = Modifier.size(14.dp).padding(top = 2.dp)
                                )
                                Text(
                                    notes,
                                    color = BossThemeColors.TextSecondary,
                                    style = SecretPanelType.meta
                                )
                            }
                        }

                        // Expiration date
                        secret.expirationDate?.let { expirationDate ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Event,
                                    contentDescription = "Expires",
                                    tint = BossThemeColors.WarningColor,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    "Expires: $expirationDate",
                                    color = BossThemeColors.WarningColor,
                                    style = SecretPanelType.meta
                                )
                            }
                        }

                        // 2FA details
                        if (metadata != null && metadata.twofaEnabled) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Security,
                                    contentDescription = "2FA",
                                    tint = BossThemeColors.SuccessColor,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    "2FA: ${metadata.twofaType?.uppercase() ?: "ENABLED"}",
                                    color = BossThemeColors.TextPrimary,
                                    style = SecretPanelType.meta
                                )
                            }
                            if (metadata.recoveryCodes.isNotEmpty()) {
                                Text(
                                    text = "Recovery Codes:",
                                    color = BossThemeColors.TextSecondary,
                                    style = SecretPanelType.metaStrong
                                )
                                metadata.recoveryCodes.forEach { code ->
                                    Text(
                                        text = "• $code",
                                        color = BossThemeColors.TextPrimary,
                                        style = SecretPanelType.caption,
                                        modifier = Modifier.padding(start = 8.dp)
                                    )
                                }
                            }
                        }

                        // Created date
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Schedule,
                                contentDescription = "Created",
                                tint = BossThemeColors.TextSecondary,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                "Created: ${secret.createdAt}",
                                color = BossThemeColors.TextSecondary,
                                style = SecretPanelType.caption
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Tag badge component
 */
@Composable
private fun TagBadge(tag: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = BossThemeColors.AccentColor.copy(alpha = 0.2f)
    ) {
        Text(
            text = tag,
            color = BossThemeColors.AccentColor,
            style = SecretPanelType.caption,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

// ==================== DIALOGS ====================

@Composable
private fun CreateSecretDialog(
    onConfirm: (CreateSecretRequestData) -> Unit,
    onDismiss: () -> Unit,
    isLoading: Boolean
) {
    var website by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var isApiKey by remember { mutableStateOf(false) }

    BossDialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.width(400.dp),
            color = BossThemeColors.SurfaceColor,
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Add New Secret",
                    color = BossThemeColors.TextPrimary,
                    style = SecretPanelType.title
                )

                Spacer(modifier = Modifier.height(16.dp))

                DialogTextField(
                    value = website,
                    onValueChange = { website = it },
                    label = if (isApiKey) "Service Name" else "Website",
                    placeholder = if (isApiKey) "e.g., OpenAI API" else "e.g., github.com"
                )

                Spacer(modifier = Modifier.height(12.dp))

                DialogTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = if (isApiKey) "Key Name" else "Username / Email",
                    placeholder = if (isApiKey) "e.g., production-key" else "e.g., user@example.com"
                )

                Spacer(modifier = Modifier.height(12.dp))

                DialogTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = if (isApiKey) "API Key" else "Password",
                    placeholder = if (isApiKey) "Enter API key" else "Enter password",
                    isPassword = true,
                    showPassword = showPassword,
                    onTogglePassword = { showPassword = !showPassword }
                )

                Spacer(modifier = Modifier.height(12.dp))

                DialogTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = "Notes (optional)",
                    placeholder = "Additional notes",
                    singleLine = false
                )

                Spacer(modifier = Modifier.height(12.dp))

                // API Key checkbox
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isApiKey = !isApiKey }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = isApiKey,
                        onCheckedChange = { isApiKey = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = BossThemeColors.AccentColor,
                            uncheckedColor = BossThemeColors.TextSecondary
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "This is an API Key",
                        color = BossThemeColors.TextPrimary,
                        style = SecretPanelType.bodyStrong
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss, enabled = !isLoading) {
                        Text("Cancel", color = BossThemeColors.TextSecondary)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (website.isNotBlank() && username.isNotBlank() && password.isNotBlank()) {
                                onConfirm(CreateSecretRequestData(
                                    website = website,
                                    username = username,
                                    password = password,
                                    notes = notes.takeIf { it.isNotBlank() },
                                    tags = if (isApiKey) listOf("api_key") else emptyList()
                                ))
                            }
                        },
                        enabled = !isLoading && website.isNotBlank() && username.isNotBlank() && password.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(backgroundColor = BossThemeColors.AccentColor)
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = BossThemeColors.TextPrimary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Create", color = BossThemeColors.TextPrimary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditSecretDialog(
    secret: SecretEntryData,
    onConfirm: (UpdateSecretRequestData) -> Unit,
    onDismiss: () -> Unit,
    isLoading: Boolean
) {
    var website by remember { mutableStateOf(secret.website) }
    var username by remember { mutableStateOf(secret.username) }
    var password by remember { mutableStateOf(secret.password) }
    var notes by remember { mutableStateOf(secret.notes ?: "") }
    var showPassword by remember { mutableStateOf(false) }
    var isApiKey by remember { mutableStateOf(secret.tags.contains("api_key")) }

    BossDialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.width(400.dp),
            color = BossThemeColors.SurfaceColor,
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Edit Secret",
                    color = BossThemeColors.TextPrimary,
                    style = SecretPanelType.title
                )

                Spacer(modifier = Modifier.height(16.dp))

                DialogTextField(
                    value = website,
                    onValueChange = { website = it },
                    label = if (isApiKey) "Service Name" else "Website"
                )

                Spacer(modifier = Modifier.height(12.dp))

                DialogTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = if (isApiKey) "Key Name" else "Username / Email"
                )

                Spacer(modifier = Modifier.height(12.dp))

                DialogTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = if (isApiKey) "API Key" else "Password",
                    isPassword = true,
                    showPassword = showPassword,
                    onTogglePassword = { showPassword = !showPassword }
                )

                Spacer(modifier = Modifier.height(12.dp))

                DialogTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = "Notes (optional)",
                    singleLine = false
                )

                Spacer(modifier = Modifier.height(12.dp))

                // API Key checkbox
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isApiKey = !isApiKey }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = isApiKey,
                        onCheckedChange = { isApiKey = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = BossThemeColors.AccentColor,
                            uncheckedColor = BossThemeColors.TextSecondary
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "This is an API Key",
                        color = BossThemeColors.TextPrimary,
                        style = SecretPanelType.bodyStrong
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss, enabled = !isLoading) {
                        Text("Cancel", color = BossThemeColors.TextSecondary)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (website.isNotBlank() && username.isNotBlank() && password.isNotBlank()) {
                                // Preserve existing tags, add/remove api_key as needed
                                val updatedTags = if (isApiKey) {
                                    if (secret.tags.contains("api_key")) secret.tags else secret.tags + "api_key"
                                } else {
                                    secret.tags.filter { it != "api_key" }
                                }
                                onConfirm(UpdateSecretRequestData(
                                    secretId = secret.id,
                                    website = website,
                                    username = username,
                                    password = password,
                                    notes = notes.takeIf { it.isNotBlank() },
                                    tags = updatedTags
                                ))
                            }
                        },
                        enabled = !isLoading && website.isNotBlank() && username.isNotBlank() && password.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(backgroundColor = BossThemeColors.AccentColor)
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = BossThemeColors.TextPrimary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Save", color = BossThemeColors.TextPrimary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeleteConfirmationDialog(
    secret: SecretEntryData,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    isLoading: Boolean
) {
    BossAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Delete Secret?", color = BossThemeColors.TextPrimary, fontWeight = FontWeight.Bold)
        },
        text = {
            Column {
                Text(
                    "Are you sure you want to delete this secret?",
                    color = BossThemeColors.TextPrimary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "${secret.website} - ${secret.username}",
                    color = BossThemeColors.AccentColor,
                    style = SecretPanelType.bodyStrong
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "This action cannot be undone.",
                    color = BossThemeColors.ErrorColor,
                    style = SecretPanelType.caption
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !isLoading,
                colors = ButtonDefaults.buttonColors(backgroundColor = BossThemeColors.ErrorColor)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = BossThemeColors.TextPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("Delete", color = BossThemeColors.TextPrimary)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isLoading) {
                Text("Cancel", color = BossThemeColors.TextSecondary)
            }
        },
        backgroundColor = BossThemeColors.SurfaceColor
    )
}

@Composable
private fun ShareSecretDialog(
    secret: SecretEntryData,
    shares: List<SecretShareData>,
    availableUsers: List<ShareUserRow>,
    availableRoles: List<ShareRoleRow>,
    canShareWithRoles: Boolean,
    onShare: (ShareSecretRequestData) -> Unit,
    onRevoke: (userId: String?, roleId: String?) -> Unit,
    onDismiss: () -> Unit,
    onSearchUsers: (String) -> Unit,
    isLoading: Boolean,
    isLoadingShares: Boolean,
    isLoadingUsers: Boolean
) {
    var searchQuery by remember { mutableStateOf("") }
    // Clicks write `tabSelection`; every read goes through the clamped `selectedTab`.
    // Derived rather than written back, because a permission can be revoked while the
    // dialog is open (the claim refreshes on a timer) and writing snapshot state during
    // composition costs an extra recomposition and leaves the invariant depending on
    // statement order inside this composable.
    var tabSelection by remember { mutableStateOf(0) } // 0 = Users, 1 = Roles
    val selectedTab = if (canShareWithRoles) tabSelection else 0

    BossDialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.width(450.dp).heightIn(max = 500.dp),
            color = BossThemeColors.SurfaceColor,
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Share Secret",
                    color = BossThemeColors.TextPrimary,
                    style = SecretPanelType.title
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    "${secret.website} - ${secret.username}",
                    color = BossThemeColors.TextSecondary,
                    style = SecretPanelType.meta
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Current shares
                if (shares.isNotEmpty() || isLoadingShares) {
                    Text(
                        "Currently shared with:",
                        color = BossThemeColors.TextPrimary,
                        style = SecretPanelType.metaStrong
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    if (isLoadingShares) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = BossThemeColors.AccentColor,
                            strokeWidth = 2.dp
                        )
                    } else {
                        shares.forEach { share ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(BossThemeColors.BackgroundColor, RoundedCornerShape(4.dp))
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text(
                                        share.sharedWithUserEmail ?: share.sharedWithRoleName ?: "Unknown",
                                        color = BossThemeColors.TextPrimary,
                                        style = SecretPanelType.meta
                                    )
                                    Text(
                                        if (share.sharedWithUserId != null) "User" else "Role",
                                        color = BossThemeColors.TextSecondary,
                                        style = SecretPanelType.micro
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        onRevoke(share.sharedWithUserId, share.sharedWithRoleId)
                                    },
                                    modifier = Modifier.size(24.dp),
                                    enabled = !isLoading
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Revoke",
                                        tint = BossThemeColors.ErrorColor,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Tabs
                TabRow(
                    selectedTabIndex = selectedTab,
                    backgroundColor = BossThemeColors.BackgroundColor,
                    contentColor = BossThemeColors.AccentColor
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { tabSelection = 0 },
                        text = { Text("Users", style = SecretPanelType.meta) }
                    )
                    // Hidden without `secret.share.role`. A role share reaches every
                    // holder of that role, and `user` is a descendant of every role, so
                    // this tab is the one control in the panel that can publish a
                    // credential deployment-wide. share_secret refuses it server-side
                    // either way; this keeps a button that cannot work off the screen.
                    if (canShareWithRoles) {
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { tabSelection = 1 },
                            text = { Text("Roles", style = SecretPanelType.meta) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (selectedTab == 0) {
                    // User search
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = {
                            searchQuery = it
                            onSearchUsers(it)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(32.dp)
                            .background(BossThemeColors.BackgroundColor, RoundedCornerShape(4.dp))
                            .border(1.dp, BossThemeColors.BorderColor, RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.body2.copy(color = BossThemeColors.TextPrimary),
                        cursorBrush = SolidColor(BossThemeColors.AccentColor),
                        decorationBox = { innerTextField ->
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Search,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = BossThemeColors.TextSecondary
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Box(modifier = Modifier.weight(1f)) {
                                    if (searchQuery.isEmpty()) {
                                        Text(
                                            "Search users by email...",
                                            color = BossThemeColors.TextSecondary,
                                            style = SecretPanelType.meta
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // User list
                    if (isLoadingUsers) {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(100.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = BossThemeColors.AccentColor,
                                strokeWidth = 2.dp
                            )
                        }
                    } else {
                        val usersListState = rememberLazyListState()
                        LazyColumn(
                            state = usersListState,
                            modifier = Modifier
                                .height(150.dp)
                                .lazyListScrollbar(
                                    listState = usersListState,
                                    direction = Orientation.Vertical,
                                    config = getPanelScrollbarConfig()
                                )
                        ) {
                            items(availableUsers) { user ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onShare(ShareSecretRequestData(
                                                secretId = secret.id,
                                                targetUserId = user.id
                                            ))
                                        }
                                        .padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Person,
                                        contentDescription = null,
                                        tint = BossThemeColors.TextSecondary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        user.email,
                                        color = BossThemeColors.TextPrimary,
                                        style = SecretPanelType.meta
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // Roles list
                    val rolesListState = rememberLazyListState()
                    LazyColumn(
                        state = rolesListState,
                        modifier = Modifier
                            .height(150.dp)
                            .lazyListScrollbar(
                                listState = rolesListState,
                                direction = Orientation.Vertical,
                                config = getPanelScrollbarConfig()
                            )
                    ) {
                        items(availableRoles) { role ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onShare(ShareSecretRequestData(
                                            secretId = secret.id,
                                            targetRoleId = role.id
                                        ))
                                    }
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Group,
                                    contentDescription = null,
                                    tint = BossThemeColors.TextSecondary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        role.name,
                                        color = BossThemeColors.TextPrimary,
                                        style = SecretPanelType.meta
                                    )
                                    val description = role.description
                                    if (description != null) {
                                        Text(
                                            description,
                                            color = BossThemeColors.TextSecondary,
                                            style = SecretPanelType.micro
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(backgroundColor = BossThemeColors.SurfaceColor)
                    ) {
                        Text("Done", color = BossThemeColors.TextPrimary)
                    }
                }
            }
        }
    }
}

@Composable
private fun DialogTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String = "",
    isPassword: Boolean = false,
    showPassword: Boolean = false,
    onTogglePassword: (() -> Unit)? = null,
    singleLine: Boolean = true
) {
    Column {
        Text(
            label,
            color = BossThemeColors.TextSecondary,
            style = SecretPanelType.caption,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BossThemeColors.BackgroundColor, RoundedCornerShape(4.dp))
                .border(1.dp, BossThemeColors.BorderColor, RoundedCornerShape(4.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                singleLine = singleLine,
                textStyle = MaterialTheme.typography.body2.copy(color = BossThemeColors.TextPrimary),
                cursorBrush = SolidColor(BossThemeColors.AccentColor),
                visualTransformation = if (isPassword && !showPassword)
                    PasswordVisualTransformation() else VisualTransformation.None,
                decorationBox = { innerTextField ->
                    Box {
                        if (value.isEmpty() && placeholder.isNotEmpty()) {
                            Text(
                                placeholder,
                                color = BossThemeColors.TextSecondary,
                                style = SecretPanelType.body
                            )
                        }
                        innerTextField()
                    }
                }
            )

            if (isPassword && onTogglePassword != null) {
                IconButton(
                    onClick = onTogglePassword,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = "Toggle password visibility",
                        tint = BossThemeColors.TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

// ==================== API KEY DIALOGS ====================

/**
 * Dialog for creating a new Plugin Store API key.
 * The API key is automatically stored as a secret after creation.
 */
@Composable
private fun CreateApiKeyDialog(
    onConfirm: (name: String, scopes: List<String>, expiresInDays: Int?) -> Unit,
    onDismiss: () -> Unit,
    isSuccess: Boolean,
    isLoading: Boolean
) {
    var name by remember { mutableStateOf("") }
    var publishScope by remember { mutableStateOf(true) }
    var versionScope by remember { mutableStateOf(true) }
    var finalizeScope by remember { mutableStateOf(true) }
    var hasExpiration by remember { mutableStateOf(false) }
    var expirationDays by remember { mutableStateOf("90") }

    BossDialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.width(450.dp),
            color = BossThemeColors.SurfaceColor,
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (isSuccess) Icons.Default.CheckCircle else Icons.Default.VpnKey,
                            contentDescription = null,
                            tint = if (isSuccess) BossThemeColors.SuccessColor else BossThemeColors.WarningColor,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            if (isSuccess) "Publish key created" else "Create publish key",
                            color = BossThemeColors.TextPrimary,
                            style = SecretPanelType.title
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (isSuccess) {
                    // Success message
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(BossThemeColors.BackgroundColor, RoundedCornerShape(4.dp))
                            .border(1.dp, BossThemeColors.SuccessColor, RoundedCornerShape(4.dp))
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = null,
                            tint = BossThemeColors.SuccessColor,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            "Publish key stored",
                            color = BossThemeColors.TextPrimary,
                            style = SecretPanelType.bodyStrong
                        )
                        Text(
                            "The key is saved to your secrets. This is the only time it is shown.",
                            color = BossThemeColors.TextSecondary,
                            style = SecretPanelType.meta
                        )
                        Divider(color = BossThemeColors.BorderColor, modifier = Modifier.padding(vertical = 8.dp))
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Language,
                                    contentDescription = null,
                                    tint = BossThemeColors.TextSecondary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    "Website: boss_plugin_store_api_key",
                                    color = BossThemeColors.TextPrimary,
                                    style = SecretPanelType.caption
                                )
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Person,
                                    contentDescription = null,
                                    tint = BossThemeColors.TextSecondary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    "Username: Your key name",
                                    color = BossThemeColors.TextPrimary,
                                    style = SecretPanelType.caption
                                )
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.VpnKey,
                                    contentDescription = null,
                                    tint = BossThemeColors.TextSecondary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    "Password: the key itself",
                                    color = BossThemeColors.TextPrimary,
                                    style = SecretPanelType.caption
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        "Send it as the X-API-Key header when publishing.",
                        color = BossThemeColors.TextSecondary,
                        style = SecretPanelType.caption
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Button(
                            onClick = onDismiss,
                            colors = ButtonDefaults.buttonColors(backgroundColor = BossThemeColors.AccentColor)
                        ) {
                            Text("Done", color = BossThemeColors.TextPrimary)
                        }
                    }
                } else {
                    // Creation form
                    Text(
                        "Publishes plugins to the BOSS Plugin Store from CI or a script. The key is stored in your " +
                            "secrets automatically.",
                        color = BossThemeColors.TextSecondary,
                        style = SecretPanelType.meta
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Name field
                    DialogTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = "Key Name",
                        placeholder = "e.g., github-actions-release"
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Scopes
                    Text(
                        "Scopes",
                        color = BossThemeColors.TextSecondary,
                        style = SecretPanelType.caption
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ScopeCheckbox(
                            checked = publishScope,
                            onCheckedChange = { publishScope = it },
                            label = "publish",
                            description = "Create new plugin versions"
                        )
                        ScopeCheckbox(
                            checked = versionScope,
                            onCheckedChange = { versionScope = it },
                            label = "version",
                            description = "Upload version files"
                        )
                        ScopeCheckbox(
                            checked = finalizeScope,
                            onCheckedChange = { finalizeScope = it },
                            label = "finalize",
                            description = "Finalize version uploads"
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Expiration
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = hasExpiration,
                            onCheckedChange = { hasExpiration = it },
                            colors = CheckboxDefaults.colors(
                                checkedColor = BossThemeColors.AccentColor,
                                uncheckedColor = BossThemeColors.TextSecondary
                            )
                        )
                        Column {
                            Text(
                                "Set expiration",
                                color = BossThemeColors.TextPrimary,
                                style = SecretPanelType.meta
                            )
                            Text(
                                "Key will expire after specified days",
                                color = BossThemeColors.TextSecondary,
                                style = SecretPanelType.micro
                            )
                        }
                    }

                    if (hasExpiration) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            BasicTextField(
                                value = expirationDays,
                                onValueChange = { newValue ->
                                    // Only allow digits
                                    if (newValue.isEmpty() || newValue.all { it.isDigit() }) {
                                        expirationDays = newValue
                                    }
                                },
                                modifier = Modifier
                                    .width(80.dp)
                                    .background(BossThemeColors.BackgroundColor, RoundedCornerShape(4.dp))
                                    .border(1.dp, BossThemeColors.BorderColor, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                singleLine = true,
                                textStyle = MaterialTheme.typography.body2.copy(color = BossThemeColors.TextPrimary),
                                cursorBrush = SolidColor(BossThemeColors.AccentColor)
                            )
                            Text(
                                "days",
                                color = BossThemeColors.TextSecondary,
                                style = SecretPanelType.meta
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Action buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onDismiss, enabled = !isLoading) {
                            Text("Cancel", color = BossThemeColors.TextSecondary)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val scopes = buildList {
                                    if (publishScope) add("publish")
                                    if (versionScope) add("version")
                                    if (finalizeScope) add("finalize")
                                }
                                val expDays = if (hasExpiration) expirationDays.toIntOrNull() else null
                                onConfirm(name, scopes, expDays)
                            },
                            enabled = !isLoading && name.isNotBlank() && (publishScope || versionScope || finalizeScope),
                            colors = ButtonDefaults.buttonColors(backgroundColor = BossThemeColors.WarningColor)
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = BossThemeColors.TextPrimary,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text("Create Key", color = Color.Black, style = SecretPanelType.body)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Checkbox component for scope selection.
 */
@Composable
private fun ScopeCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    description: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(
                checkedColor = BossThemeColors.AccentColor,
                uncheckedColor = BossThemeColors.TextSecondary
            )
        )
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(
                label,
                color = BossThemeColors.TextPrimary,
                style = SecretPanelType.metaStrong
            )
            Text(
                description,
                color = BossThemeColors.TextSecondary,
                style = SecretPanelType.micro
            )
        }
    }
}

/**
 * Dialog for listing and managing API keys.
 */
@Composable
private fun ApiKeysListDialog(
    apiKeys: List<ApiKeyInfo>,
    onRevoke: (keyId: String) -> Unit,
    onDismiss: () -> Unit,
    onCreateNew: () -> Unit,
    isLoading: Boolean
) {
    var keyToRevoke by remember { mutableStateOf<ApiKeyInfo?>(null) }

    BossDialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.width(500.dp).heightIn(max = 450.dp),
            color = BossThemeColors.SurfaceColor,
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.VpnKey,
                            contentDescription = null,
                            tint = BossThemeColors.AccentColor,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            "Plugin Store publish keys",
                            color = BossThemeColors.TextPrimary,
                            style = SecretPanelType.title
                        )
                    }
                    IconButton(
                        onClick = onCreateNew,
                        enabled = !isLoading
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "Create new",
                            tint = BossThemeColors.AccentColor
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (isLoading) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(150.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = BossThemeColors.AccentColor,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                } else if (apiKeys.isEmpty()) {
                    // Empty state
                    Box(
                        modifier = Modifier.fillMaxWidth().height(150.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.VpnKey,
                                contentDescription = null,
                                tint = BossThemeColors.TextSecondary,
                                modifier = Modifier.size(48.dp)
                            )
                            Text(
                                "No publish keys",
                                color = BossThemeColors.TextPrimary,
                                style = SecretPanelType.bodyStrong
                            )
                            Text(
                                "Create a key for CI/CD publishing",
                                color = BossThemeColors.TextSecondary,
                                style = SecretPanelType.meta
                            )
                            Button(
                                onClick = onCreateNew,
                                colors = ButtonDefaults.buttonColors(backgroundColor = BossThemeColors.WarningColor)
                            ) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("New publish key", color = Color.Black, style = SecretPanelType.meta)
                            }
                        }
                    }
                } else {
                    // Keys list
                    val listState = rememberLazyListState()
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .lazyListScrollbar(
                                listState = listState,
                                direction = Orientation.Vertical,
                                config = getPanelScrollbarConfig()
                            ),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(apiKeys, key = { it.id }) { key ->
                            ApiKeyCard(
                                apiKey = key,
                                onRevoke = { keyToRevoke = key },
                                isLoading = isLoading
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(backgroundColor = BossThemeColors.SurfaceColor)
                    ) {
                        Text("Close", color = BossThemeColors.TextPrimary)
                    }
                }
            }
        }
    }

    // Revoke confirmation dialog
    keyToRevoke?.let { key ->
        BossAlertDialog(
            onDismissRequest = { keyToRevoke = null },
            title = {
                Text("Revoke publish key?", color = BossThemeColors.TextPrimary, fontWeight = FontWeight.Bold)
            },
            text = {
                Column {
                    Text(
                        "Are you sure you want to revoke this publish key?",
                        color = BossThemeColors.TextPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        key.name,
                        color = BossThemeColors.AccentColor,
                        style = SecretPanelType.bodyStrong
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "This will immediately invalidate the key. CI/CD pipelines using this key will fail.",
                        color = BossThemeColors.ErrorColor,
                        style = SecretPanelType.caption
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRevoke(key.id)
                        keyToRevoke = null
                    },
                    enabled = !isLoading,
                    colors = ButtonDefaults.buttonColors(backgroundColor = BossThemeColors.ErrorColor)
                ) {
                    Text("Revoke", color = BossThemeColors.TextPrimary)
                }
            },
            dismissButton = {
                TextButton(onClick = { keyToRevoke = null }) {
                    Text("Cancel", color = BossThemeColors.TextSecondary)
                }
            },
            backgroundColor = BossThemeColors.SurfaceColor
        )
    }
}

/**
 * Card component for displaying a single API key.
 */
@Composable
private fun ApiKeyCard(
    apiKey: ApiKeyInfo,
    onRevoke: () -> Unit,
    isLoading: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        backgroundColor = BossThemeColors.SurfaceColor,
        elevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        apiKey.name,
                        color = BossThemeColors.TextPrimary,
                        style = SecretPanelType.bodyStrong
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Key prefix
                        Text(
                            apiKey.keyPrefix + "...",
                            color = BossThemeColors.TextSecondary,
                            style = SecretPanelType.caption
                        )
                        // Scopes
                        apiKey.scopes.forEach { scope ->
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = BossThemeColors.AccentColor.copy(alpha = 0.2f)
                            ) {
                                Text(
                                    scope,
                                    color = BossThemeColors.AccentColor,
                                    style = SecretPanelType.micro,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }

                IconButton(
                    onClick = onRevoke,
                    enabled = !isLoading,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Revoke",
                        tint = BossThemeColors.ErrorColor,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Metadata row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Created date
                Column {
                    Text(
                        "Created",
                        color = BossThemeColors.TextSecondary,
                        style = SecretPanelType.micro
                    )
                    Text(
                        formatTimestamp(apiKey.createdAt),
                        color = BossThemeColors.TextPrimary,
                        style = SecretPanelType.caption
                    )
                }

                // Last used
                apiKey.lastUsedAt?.let { lastUsed ->
                    Column {
                        Text(
                            "Last used",
                            color = BossThemeColors.TextSecondary,
                            style = SecretPanelType.micro
                        )
                        Text(
                            formatTimestamp(lastUsed),
                            color = BossThemeColors.TextPrimary,
                            style = SecretPanelType.caption
                        )
                    }
                }

                // Expires
                apiKey.expiresAt?.let { expiresAt ->
                    Column {
                        Text(
                            "Expires",
                            color = BossThemeColors.TextSecondary,
                            style = SecretPanelType.micro
                        )
                        Text(
                            formatTimestamp(expiresAt),
                            color = BossThemeColors.WarningColor,
                            style = SecretPanelType.caption
                        )
                    }
                }
            }
        }
    }
}

/**
 * Format a timestamp (milliseconds) to a readable date string.
 */
private fun formatTimestamp(timestamp: Long): String {
    return try {
        val instant = java.time.Instant.ofEpochMilli(timestamp)
        val dateTime = java.time.LocalDateTime.ofInstant(instant, java.time.ZoneId.systemDefault())
        val formatter = java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy")
        dateTime.format(formatter)
    } catch (_: Exception) {
        "Unknown"
    }
}

/**
 * Dialog for adding an AI provider API key.
 *
 * Separate from [CreateSecretDialog] because these entries are not passwords: the
 * provider is picked from the registry rather than typed as a website, and the key is
 * written through ProviderCredentialStore so Settings → AI Providers recognises the
 * result. A hand-made secret with the same fields would not be picked up.
 */
@Composable
private fun AiProviderKeyDialog(
    selectedProviderId: String,
    sources: Map<String, CredentialSource>,
    keyDraft: String,
    isLoading: Boolean,
    errorMessage: String?,
    onProviderChange: (String) -> Unit,
    onKeyChange: (String) -> Unit,
    onOpenConsole: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val descriptor = ProviderRegistry.findOrDefault(selectedProviderId)
    var providerMenuOpen by remember { mutableStateOf(false) }

    val existingSource = sources[descriptor.id] ?: CredentialSource.NONE
    val alreadyStored = existingSource == CredentialSource.STORED
    // An env-supplied key cannot be stored here — saveKey rejects it — so say that up
    // front instead of letting the save fail after the key has been typed.
    val fromEnvironment = existingSource == CredentialSource.ENVIRONMENT

    BossAlertDialog(
        onDismissRequest = onDismiss,
        backgroundColor = BossThemeColors.SurfaceColor,
        title = {
            Text(
                if (alreadyStored) "Change AI provider key" else "Add AI provider key",
                color = BossThemeColors.TextPrimary,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Stored as an encrypted secret. Pick a model afterwards in Settings → AI Providers.",
                    color = BossThemeColors.TextSecondary,
                    style = SecretPanelType.meta
                )

                // Say plainly what saving will do to an existing credential.
                if (alreadyStored) {
                    Text(
                        "${descriptor.standardKeyName} is already stored. Entering a new key replaces it.",
                        color = BossThemeColors.WarningColor,
                        style = SecretPanelType.meta
                    )
                } else if (fromEnvironment) {
                    Text(
                        "${descriptor.displayName} is supplied by the environment " +
                            "(${descriptor.envVarNames.joinToString(" / ")}) and can't be stored here. " +
                            "Unset that variable to manage the key in BOSS.",
                        color = BossThemeColors.WarningColor,
                        style = SecretPanelType.meta
                    )
                }

                // Provider picker
                Box {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(BossThemeColors.BackgroundColor)
                            .clickable(enabled = !isLoading) { providerMenuOpen = true }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = descriptor.displayName,
                            color = BossThemeColors.TextPrimary,
                            style = SecretPanelType.body,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            Icons.Default.ArrowDropDown,
                            contentDescription = "Choose provider",
                            tint = BossThemeColors.TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = providerMenuOpen,
                        onDismissRequest = { providerMenuOpen = false },
                        modifier = Modifier.background(BossThemeColors.SurfaceColor)
                    ) {
                        // userKeyed, not all: a keyless or brokered provider has no key to
                        // store here. See ProviderRegistry.userKeyed.
                        ProviderRegistry.userKeyed.forEach { candidate ->
                            DropdownMenuItem(
                                onClick = {
                                    providerMenuOpen = false
                                    onProviderChange(candidate.id)
                                }
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        candidate.displayName,
                                        color = BossThemeColors.TextPrimary,
                                        style = SecretPanelType.body,
                                        modifier = Modifier.weight(1f)
                                    )
                                    // Marking configured providers means "already set" is
                                    // visible before picking, not after.
                                    when (sources[candidate.id]) {
                                        CredentialSource.STORED -> Text(
                                            "set",
                                            color = BossThemeColors.SuccessColor,
                                            style = SecretPanelType.caption
                                        )
                                        CredentialSource.ENVIRONMENT -> Text(
                                            "env",
                                            color = BossThemeColors.SecondaryColor,
                                            style = SecretPanelType.caption
                                        )
                                        else -> Unit
                                    }
                                }
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = keyDraft,
                    onValueChange = onKeyChange,
                    label = {
                        Text(
                            if (alreadyStored) "New API key" else "API key",
                            color = BossThemeColors.TextSecondary
                        )
                    },
                    placeholder = {
                        Text(
                            if (alreadyStored) "Enter a new key to replace the stored one"
                            else descriptor.keyPlaceholder,
                            color = BossThemeColors.TextMuted
                        )
                    },
                    singleLine = true,
                    enabled = !isLoading && !fromEnvironment,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )

                // Same affordance as Settings → AI Providers: don't make someone without a
                // key hunt for the console themselves.
                if (descriptor.consoleUrl != null) {
                    OutlinedButton(
                        onClick = onOpenConsole,
                        enabled = !isLoading,
                        border = BorderStroke(1.dp, BossThemeColors.BorderColor)
                    ) {
                        Icon(
                            Icons.Default.OpenInNew,
                            contentDescription = null,
                            tint = BossThemeColors.TextSecondary,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "Get API key",
                            color = BossThemeColors.TextPrimary,
                            style = SecretPanelType.meta
                        )
                    }
                }

                if (descriptor.envVarNames.isNotEmpty()) {
                    Text(
                        "Or set ${descriptor.envVarNames.joinToString(" / ")} in the environment.",
                        color = BossThemeColors.TextMuted,
                        style = SecretPanelType.caption
                    )
                }

                errorMessage?.let {
                    Text(it, color = BossThemeColors.ErrorColor, style = SecretPanelType.meta)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !isLoading && !fromEnvironment && keyDraft.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = BossThemeColors.AccentColor
                )
            ) {
                Text(
                    when {
                        isLoading -> "Saving…"
                        alreadyStored -> "Replace"
                        else -> "Save"
                    }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isLoading) {
                Text("Cancel", color = BossThemeColors.TextSecondary)
            }
        }
    )
}
