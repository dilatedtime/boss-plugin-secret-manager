package ai.rever.boss.plugin.dynamic.secretmanager.ai

import ai.rever.boss.plugin.dynamic.secretmanager.SecretPanelType
import ai.rever.boss.plugin.ui.BossCard
import ai.rever.boss.plugin.ui.BossPrimaryButton
import ai.rever.boss.plugin.ui.BossSecondaryButton
import ai.rever.boss.plugin.ui.BossSection
import ai.rever.boss.plugin.ui.BossTextField
import ai.rever.boss.plugin.ui.BossThemeColors
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * Settings surface for AI providers: credentials, and a model picker driven by each
 * provider's own model list.
 *
 * Rendered in two places from one definition — inside this plugin's panel and, via
 * `LlmProviderSettingsAPI`, in the host's Settings window.
 */
@Composable
fun AiProvidersPanel(
    viewModel: AiProvidersViewModel,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
) {
    LaunchedEffect(viewModel) { viewModel.enterSection() }
    val state by viewModel.state.collectAsState()
    val selected = state.providers.firstOrNull { it.id == state.selectedProviderId }

    // Scrolls itself: the host registers this as an embedded panel and does not wrap it
    // in a scroll container (nesting two would measure with infinite height and crash).
    // Padding is vertical-only — the host's embedded-panel path already pads and renders
    // the section header.
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        state.legacyOffer?.let { offer ->
            LegacyImportBanner(
                offer = offer,
                onImport = viewModel::importLegacyKeys,
                onDismiss = viewModel::dismissLegacyOffer,
            )
        }

        if (!state.storeAvailable) {
            MessageBanner(
                text =
                    "Credential storage is unavailable — sign in to save keys. " +
                        "Providers configured by environment variable still work.",
                tint = BossThemeColors.WarningColor,
            )
        }
        state.error?.let { MessageBanner(it, BossThemeColors.ErrorColor) }
        state.sharedDiscoveryWarning?.let { MessageBanner(it, BossThemeColors.WarningColor) }
        state.providerSelectionWarning?.let { MessageBanner(it, BossThemeColors.WarningColor) }
        if (state.unavailableSharedModel) {
            MessageBanner("The selected shared model is no longer published. Choose another model.", BossThemeColors.WarningColor)
        }
        state.notice?.let { MessageBanner(it, BossThemeColors.SuccessColor) }

        // One heading for the whole surface: a CLI session and an HTTP provider are both
        // just "a way to answer AI requests", and two section titles over what is really
        // one list read as more structure than is here.
        BossSection(
            title = "Available Providers",
        ) {
            // Where the CLI subsection would be. The gateway serving no engines is still
            // silence, but the gateway being *absent* is a thing the user can fix, and
            // telling them costs one row.
            if (state.gatewayNotice != GatewayNotice.NONE) {
                GatewayMissingNotice(
                    notice = state.gatewayNotice,
                    isAsking = state.isAskingForGateway,
                    onRequest = viewModel::requestGateway,
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Above the providers on purpose: for a user who already has a `claude` or
            // `codex` login, this is the whole setup, and burying it under a key-entry
            // form would have them paste a key they never needed. Absent entirely when
            // the gateway serves none, rather than showing an empty subsection nobody can
            // act on.
            if (state.cliEngines.isNotEmpty()) {
                Text(
                    text = "Local CLI sessions",
                    style = SecretPanelType.bodyStrong,
                    color = BossThemeColors.TextPrimary,
                )
                Text(
                    text =
                        "A login you already have. Overrides the provider below.",
                    style = SecretPanelType.meta,
                    color = BossThemeColors.TextSecondary,
                    modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.cliEngines.forEach { engine ->
                        CliEngineRow(
                            engine = engine,
                            health = state.cliHealthOf(engine.id),
                            isActive = engine.id == state.activeCliEngineId,
                            onClick = {
                                // Clicking the active row turns it off rather than doing
                                // nothing: it is the only way back to an HTTP provider without
                                // having to pick one, and a row that cannot be deselected reads
                                // as stuck.
                                viewModel.setActiveCliEngine(
                                    if (engine.id == state.activeCliEngineId) null else engine.id,
                                )
                            },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val listed =
                    state.providers.filter {
                        // The UI keeps unavailable shares visible; machine lists require readiness.
                        isManagedProvider(it.id) || isProviderListed(
                            it,
                            state.connectionOf(it.id),
                            state.catalogOf(it.id),
                            wasAddedByUser = it.id in state.addedProviderIds,
                        )
                    }

                if (listed.isEmpty()) {
                    Text(
                        text = "No providers added yet.",
                        style = SecretPanelType.meta,
                        color = BossThemeColors.TextSecondary,
                    )
                } else {
                    listed.forEach { descriptor ->
                        ProviderRow(
                            descriptor = descriptor,
                            connection = state.connectionOf(descriptor.id),
                            // Only the row whose editor is actually open reads as selected —
                            // a stale selectedProviderId from a previous visit must not paint
                            // a row as open when the card below it is closed.
                            isSelected = state.isEditorOpen && descriptor.id == state.selectedProviderId,
                            isActive = descriptor.id == state.activeProviderId,
                            onClick = { viewModel.toggleProvider(descriptor.id) },
                        )
                        if (state.isEditorOpen && descriptor.id == state.selectedProviderId) {
                            ProviderDetail(
                                descriptor = descriptor,
                                state = state,
                                viewModel = viewModel,
                                onCancel = viewModel::closeEditor,
                                showTitle = false,
                            )
                        }
                    }
                }

                val listedIds = listed.mapTo(mutableSetOf()) { it.id }
                AddProviderRow(
                    addable =
                        state.providers.filter { descriptor ->
                            descriptor.id != ProviderRegistry.CUSTOM &&
                                descriptor.id !in listedIds &&
                                // A machine that cannot usefully run any model through Ollama
                                // is not offered it as something to add — see ProviderDetail's
                                // own blocked-state card for the one already-configured
                                // exception this does not cover. `!= false` rather than a bare
                                // negation: an unprobed machine (null) is offered the provider,
                                // since withholding it would be a claim about hardware nobody
                                // has looked at yet.
                                !(
                                    descriptor.id == ProviderRegistry.OLLAMA &&
                                        state.ollamaSystemInfo?.meetsMinimum == false
                                )
                        },
                    onPick = viewModel::selectProvider,
                    onPickCustom = { viewModel.selectProvider(ProviderRegistry.CUSTOM) },
                )
                if (state.isEditorOpen && selected != null && selected.id !in listedIds) {
                    ProviderDetail(
                        descriptor = selected,
                        state = state,
                        viewModel = viewModel,
                        onCancel = viewModel::closeEditor,
                    )
                }
            }
        }
    }
}

/**
 * "AI Gateway is not installed", with the button that fixes it.
 *
 * This is the section's answer to a silence. Local CLI sessions, brokered organisation providers
 * and the common completion API all come from the gateway, so without it this panel stores keys
 * that only the plugins reading `PluginContext.llmProvider` directly can use - and nothing said
 * so. A user who had signed into `claude` in a terminal specifically to use it here had no way to
 * discover that one plugin stood in the way.
 *
 * Deliberately **not** an error colour. Nothing is broken: HTTP provider keys work without the
 * gateway, which is exactly why this plugin declares the dependency `optional` rather than
 * required. The host says the same thing in its own words at install time - "works without it,
 * but some of its features need it" - and this is that sentence in the place it matters.
 */
@Composable
private fun GatewayMissingNotice(
    notice: GatewayNotice,
    isAsking: Boolean,
    onRequest: () -> Unit,
) {
    BossCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Default.Extension,
                    contentDescription = null,
                    tint = BossThemeColors.TextSecondary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    "AI Gateway is not installed",
                    color = BossThemeColors.TextPrimary,
                    style = SecretPanelType.bodyStrong,
                )
            }
            Text(
                "Provider keys below still work. The gateway adds local CLI sessions - a " +
                    "claude or codex login you already have - and serves one AI interface to " +
                    "every plugin.",
                color = BossThemeColors.TextSecondary,
                style = SecretPanelType.meta,
            )
            when (notice) {
                // The label names what the press does, and the two routes really do differ: one
                // raises the Toolbox's install confirmation, the other just shows the Toolbox.
                GatewayNotice.OFFER_INSTALL ->
                    BossPrimaryButton(
                        text = if (isAsking) "Asking the Toolbox" else "Install AI Gateway",
                        onClick = onRequest,
                        enabled = !isAsking,
                    )

                GatewayNotice.OFFER_TOOLBOX ->
                    BossSecondaryButton(
                        text = if (isAsking) "Opening the Toolbox" else "Open the Toolbox",
                        onClick = onRequest,
                        enabled = !isAsking,
                    )

                // No route: say where to look rather than showing a button that cannot work.
                GatewayNotice.DESCRIBE_ONLY, GatewayNotice.NONE ->
                    Text(
                        "Install it from the Toolbox to enable those.",
                        color = BossThemeColors.TextMuted,
                        style = SecretPanelType.caption,
                    )
            }
        }
    }
}

/**
 * One local CLI engine.
 *
 * Deliberately not a [ProviderRow]: there is no key field, no model list and no status dot
 * driven by a credential source, because the credential is a login this panel never sees. What
 * a user needs here is whether the binary is there and whether it is serving requests.
 */
@Composable
private fun CliEngineRow(
    engine: CliEngineInfo,
    health: CliEngineHealth,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = if (isActive) BossThemeColors.AccentColor else BossThemeColors.BorderColor
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(BossThemeColors.SurfaceColor)
                .border(1.dp, borderColor, RoundedCornerShape(6.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        when (health) {
                            is CliEngineHealth.Ready -> BossThemeColors.SuccessColor
                            is CliEngineHealth.Failed -> BossThemeColors.ErrorColor
                            is CliEngineHealth.NotInstalled -> BossThemeColors.WarningColor
                            CliEngineHealth.Unknown -> BossThemeColors.BorderColor
                        },
                    ),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(engine.displayName, style = SecretPanelType.body, color = BossThemeColors.TextPrimary)
            Text(
                text = healthLine(engine, health),
                style = SecretPanelType.caption,
                color = BossThemeColors.TextSecondary,
            )
        }
        if (isActive) {
            Text("Active", style = SecretPanelType.caption, color = BossThemeColors.AccentColor)
        }
    }
}

/**
 * What to say under an engine's name.
 *
 * Each state names its own fix, which is why they are not collapsed: "install it" is wrong for
 * a broken install, and **"Ready" deliberately does not claim signed in** - a probe runs the
 * CLI's `--version`, which succeeds for an install that has never been logged in. Promising
 * more than that here would send a first-run user looking for a bug in the panel when their
 * first turn fails.
 */
private fun healthLine(
    engine: CliEngineInfo,
    health: CliEngineHealth,
): String =
    when (health) {
        is CliEngineHealth.Ready ->
            "Found ${health.version}. If a turn fails to authenticate, sign in once in a terminal."
        is CliEngineHealth.NotInstalled -> health.hint.ifBlank { engine.installHint }.ifBlank { "Not installed." }
        is CliEngineHealth.Failed -> "Installed but would not run: ${health.message}"
        CliEngineHealth.Unknown -> "Checking…"
    }

/**
 * Two equal-weight ways to gain a provider, not a search box: the catalog is small enough
 * that a plain list beats a filter, and "add one of these" versus "declare a custom
 * endpoint" are different enough flows to deserve their own buttons rather than one menu
 * that mixes both.
 *
 * Picking either just selects the provider — [ProviderDetail] below already renders
 * whatever is selected, configured or not, so there is no separate "add" form to build.
 */
@Composable
private fun AddProviderRow(
    addable: List<ProviderDescriptor>,
    onPick: (String) -> Unit,
    onPickCustom: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            BossSecondaryButton(
                text = "Add provider",
                onClick = { expanded = true },
                enabled = addable.isNotEmpty(),
            )
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.heightIn(max = 320.dp).background(BossThemeColors.SurfaceColor),
            ) {
                addable.forEach { descriptor ->
                    DropdownMenuItem(
                        onClick = {
                            expanded = false
                            onPick(descriptor.id)
                        },
                    ) {
                        Text(
                            text = descriptor.displayName,
                            style = SecretPanelType.body,
                            color = BossThemeColors.TextPrimary,
                        )
                    }
                }
            }
        }
        BossSecondaryButton(text = "Add custom provider", onClick = onPickCustom)
    }
}

@Composable
private fun ProviderRow(
    descriptor: ProviderDescriptor,
    connection: ProviderConnection,
    isSelected: Boolean,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = if (isSelected) BossThemeColors.AccentColor else BossThemeColors.BorderColor
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(BossThemeColors.SurfaceColor)
                .border(1.dp, borderColor, RoundedCornerShape(6.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatusDot(connection.source, noKeyNeeded = !descriptor.requiresApiKey)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = descriptor.displayName,
                style = SecretPanelType.body,
                color = BossThemeColors.TextPrimary,
            )
            descriptor.sharedSourceLabel?.let { source ->
                Text(
                    text = source,
                    style = SecretPanelType.caption,
                    color = BossThemeColors.TextMuted,
                    maxLines = 1,
                )
            }
        }
        if (isActive) {
            Text(
                text = "Active",
                style = SecretPanelType.caption,
                color = BossThemeColors.AccentColor,
            )
        }
        Text(
            text = statusLabel(connection, noKeyNeeded = !descriptor.requiresApiKey),
            style = SecretPanelType.caption,
            color = BossThemeColors.TextMuted,
        )
        // The row itself is already clickable, same target — this is for discoverability:
        // "tap anywhere to edit" is not obvious from a row that otherwise reads as static.
        //
        // No contentDescription, and no separate click target: the row above already carries
        // the provider name and the same action, so describing this would make a screen reader
        // read the provider twice in one row — the double-read AGENTS.md records for
        // QuietCopyButton and SharedSecretBadge. Purely decorative, so it is announced as
        // nothing at all rather than as a second 16dp button.
        Icon(
            imageVector = Icons.Outlined.Edit,
            contentDescription = null,
            tint = BossThemeColors.TextSecondary,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun StatusDot(source: CredentialSource, noKeyNeeded: Boolean) {
    val color =
        if (noKeyNeeded) {
            // A local daemon with no credential is either reachable or it isn't; there
            // is no "not configured" state for it to sit in, so it reads as ready
            // rather than piggybacking on a CredentialSource it will never earn.
            BossThemeColors.SuccessColor
        } else {
            when (source) {
                CredentialSource.STORED -> BossThemeColors.SuccessColor
                CredentialSource.BROKERED -> BossThemeColors.SuccessColor
                CredentialSource.ENVIRONMENT -> BossThemeColors.SecondaryColor
                CredentialSource.NONE -> BossThemeColors.TextMuted
            }
        }
    Box(modifier = Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
}

private fun statusLabel(connection: ProviderConnection, noKeyNeeded: Boolean): String =
    if (noKeyNeeded) {
        "No key needed"
    } else {
        when (connection.source) {
            CredentialSource.STORED -> "Stored"
            CredentialSource.ENVIRONMENT -> connection.label?.let { "From $it" } ?: "From environment"
            CredentialSource.BROKERED -> "Signed in"
            CredentialSource.NONE -> "Not configured"
        }
    }

@Composable
private fun ProviderDetail(
    descriptor: ProviderDescriptor,
    state: AiProvidersUiState,
    viewModel: AiProvidersViewModel,
    onCancel: () -> Unit,
    showTitle: Boolean = true,
) {
    val connection = state.connectionOf(descriptor.id)
    val busy = descriptor.id in state.busyProviderIds
    val fromEnvironment = connection.source == CredentialSource.ENVIRONMENT
    val brokered = descriptor.brokerId != null
    val noKeyNeeded = !descriptor.requiresApiKey
    // A machine below Ollama's own published RAM floor cannot run anything through it, so
    // there is nothing here worth a key field, a model list or an activate button — only an
    // explanation. Any other keyless provider added later would need its own such check;
    // this one is Ollama-specific on purpose rather than folded into requiresApiKey, since
    // requiresApiKey is about the wire protocol and this is about the hardware.
    // `== false`, not `!meetsMinimum`: null is "the probe has not answered", and a card that
    // says "not available on this machine" before anyone read the RAM is a claim, not a wait.
    val ollamaBlocked =
        descriptor.id == ProviderRegistry.OLLAMA && state.ollamaSystemInfo?.meetsMinimum == false

    val revealEditor = remember(descriptor.id) { BringIntoViewRequester() }
    var editorPlaced by remember(descriptor.id) { mutableStateOf(false) }
    LaunchedEffect(descriptor.id, editorPlaced) {
        if (editorPlaced) revealEditor.bringIntoView()
    }

    // One card for the whole editor — title through the activate button — rather than two
    // separate cards (key section, model section) with a header floating above both. Add and
    // edit are the same form, so there is exactly one boundary to open and close.
    BossCard(modifier = Modifier.fillMaxWidth()
        .bringIntoViewRequester(revealEditor)
        .onGloballyPositioned { editorPlaced = true }) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
                if (showTitle) {
                    Text(
                        text = descriptor.displayName,
                        style = SecretPanelType.bodyStrong,
                        color = BossThemeColors.TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                }
                BossSecondaryButton(text = "Cancel", onClick = onCancel, enabled = !busy)
            }

            if (ollamaBlocked) {
                // Nothing below this is reachable: no key to enter, no model list worth
                // fetching, no activate button that could ever resolve a credential.
                OllamaUnavailableContent(info = state.ollamaSystemInfo)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (brokered) {
                        // No key field at all: there is nothing for the user to paste, and
                        // offering one would invite them to store a credential that this
                        // provider mints for itself and that expires within hours.
                        Text(
                            text =
                                if (connection.source == CredentialSource.BROKERED) {
                                    "Authorised by your BOSS sign-in. A short-lived key is fetched " +
                                        "when needed and never stored."
                                } else {
                                    "Sign in to BOSS with an account that has access. This provider " +
                                        "has no API key to enter."
                                },
                            style = SecretPanelType.meta,
                            color = BossThemeColors.TextSecondary,
                        )
                        BossSecondaryButton(
                            text = "Check access",
                            onClick = { viewModel.refreshBrokeredCredential(descriptor.id) },
                            enabled = !busy,
                        )
                    } else if (fromEnvironment) {
                        Text(
                            text =
                                "This key comes from the environment" +
                                    (connection.label?.let { " ($it)" } ?: "") +
                                    " and is read-only here. Unset it to manage the key in BOSS.",
                            style = SecretPanelType.meta,
                            color = BossThemeColors.TextSecondary,
                        )
                    } else if (noKeyNeeded) {
                        // Ollama takes no credential on the wire, so there is nothing to
                        // paste and no "Save key" flow to offer — offering one would just
                        // invite a dummy value nobody needs.
                        OllamaSetupNotice(
                            info = state.ollamaSystemInfo,
                            installingTag = state.installingOllamaModelTag,
                            onInstall = viewModel::openOllamaInstallPage,
                            onInstallModel = viewModel::installOllamaModel,
                        )
                    } else {
                        // A stored key is never rendered back — the field is for replacing
                        // it. Settings has no business displaying credential material.
                        BossTextField(
                            value = state.keyDrafts[descriptor.id].orEmpty(),
                            onValueChange = { viewModel.updateKeyDraft(descriptor.id, it) },
                            label = "API key",
                            placeholder =
                                if (connection.source == CredentialSource.STORED) {
                                    "A key is stored — enter a new one to replace it"
                                } else {
                                    descriptor.keyPlaceholder
                                },
                            enabled = state.storeAvailable && !busy,
                            singleLine = true,
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            BossPrimaryButton(
                                text = "Save key",
                                onClick = { viewModel.saveKey(descriptor.id) },
                                enabled = state.storeAvailable && !busy &&
                                    state.keyDrafts[descriptor.id]?.isNotBlank() == true,
                            )
                            if (connection.source == CredentialSource.STORED) {
                                BossSecondaryButton(
                                    text = "Remove",
                                    onClick = { viewModel.clearKey(descriptor.id) },
                                    enabled = !busy,
                                    isDestructive = true,
                                )
                            }
                            if (descriptor.consoleUrl != null) {
                                BossSecondaryButton(
                                    text = "Get API key",
                                    onClick = { viewModel.openProviderConsole(descriptor.id) },
                                    enabled = !busy,
                                )
                            }
                        }
                    }

                    if (descriptor.envVarNames.isNotEmpty() && !fromEnvironment && !brokered) {
                        Text(
                            text = "Or set ${descriptor.envVarNames.joinToString(" / ")} in the environment.",
                            style = SecretPanelType.caption,
                            color = BossThemeColors.TextMuted,
                        )
                    }
                }

                ModelSectionContent(
                    descriptor = descriptor,
                    connection = connection,
                    catalog = state.catalogOf(descriptor.id),
                    busy = busy,
                    onSelectModel = { viewModel.selectModel(descriptor.id, it) },
                    onRefresh = { viewModel.refreshModels(descriptor.id) },
                    onTest = { viewModel.testConnection(descriptor.id) },
                    onEndpointChange = { viewModel.setCustomEndpoint(descriptor.id, it) },
                    onManualModelChange = { viewModel.setManualModelId(descriptor.id, it) },
                )

                if (connection.isConfigured && descriptor.id != state.activeProviderId) {
                    BossSecondaryButton(
                        text = "Use ${descriptor.displayName} for AI features",
                        onClick = { viewModel.setActiveProvider(descriptor.id) },
                        enabled = !busy,
                    )
                }
            }
        }
    }
}

/** What replaces the rest of the card when [OllamaSystemInfo.meetsMinimum] is false. */
@Composable
private fun OllamaUnavailableContent(info: OllamaSystemInfo?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Not available on this machine",
            style = SecretPanelType.bodyStrong,
            color = BossThemeColors.TextPrimary,
        )
        Text(
            text =
                "This machine reports " +
                    (info?.totalRamGb?.let { "about ${it.roundToInt()} GB" } ?: "an unreadable amount") +
                    " of RAM. Ollama needs at least ${OllamaSystemCheck.MIN_USABLE_RAM_GB.roundToInt()} GB " +
                    "to run any model usefully, so this provider isn't offered here.",
            style = SecretPanelType.meta,
            color = BossThemeColors.TextSecondary,
        )
    }
}

/**
 * What the key section shows for Ollama once it's confirmed to be worth offering at all:
 * whether the binary is here yet, and — either way — a picker over a short, RAM-sized
 * shortlist that pulls a model by calling Ollama's own API, not by naming a terminal command
 * and hoping the user comes back and runs it. The live model picker below this only ever
 * shows models already pulled, so getting one pulled from here is the whole point of this
 * over the generic message every other keyless provider would get.
 */
@Composable
private fun OllamaSetupNotice(
    info: OllamaSystemInfo?,
    installingTag: String?,
    onInstall: () -> Unit,
    onInstallModel: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            // Null is "the probe has not answered". Say so, rather than picking one of the
            // two real answers and being wrong about it for a frame — the same reason
            // CliEngineHealth has an Unknown and does not default to NotInstalled.
            info == null ->
                Text(
                    text = "Checking whether Ollama is installed…",
                    style = SecretPanelType.meta,
                    color = BossThemeColors.TextMuted,
                )
            !info.binaryFound -> {
                Text(
                    text = "Ollama doesn't appear to be installed on this machine.",
                    style = SecretPanelType.meta,
                    color = BossThemeColors.TextSecondary,
                )
                BossSecondaryButton(text = "Install Ollama", onClick = onInstall)
            }
            else ->
                Text(
                    text = "A local Ollama daemon needs no API key. Pick a model below once it's running.",
                    style = SecretPanelType.meta,
                    color = BossThemeColors.TextSecondary,
                )
        }

        if (info != null && info.suggestedModels.isNotEmpty()) {
            Text(
                text =
                    "Suggested for this machine" +
                        (info.totalRamGb?.let { " (~${it.roundToInt()} GB RAM)" } ?: "") + ":",
                style = SecretPanelType.caption,
                color = BossThemeColors.TextMuted,
            )
            OllamaModelInstallPicker(
                models = info.suggestedModels,
                installingTag = installingTag,
                // Pulling before the binary is here is a guaranteed connection-refused, so
                // the control that would do it is disabled rather than left to fail and
                // explain itself. Install Ollama above is the step that comes first.
                enabled = info.binaryFound,
                onInstall = onInstallModel,
            )
        }
    }
}

/**
 * A model picker paired with an Install button, rather than a list of `ollama pull` commands
 * to copy into a terminal — [onInstall] calls Ollama's own API directly. The dropdown mirrors
 * [ModelPicker]'s shape so the two read as the same control at different points in the flow.
 */
@Composable
private fun OllamaModelInstallPicker(
    models: List<SuggestedOllamaModel>,
    installingTag: String?,
    enabled: Boolean,
    onInstall: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var chosen by remember(models) { mutableStateOf(models.firstOrNull()) }
    val busy = installingTag != null || !enabled

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(modifier = Modifier.weight(1f)) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(BossThemeColors.BackgroundColor)
                        .border(1.dp, BossThemeColors.BorderColor, RoundedCornerShape(6.dp))
                        .clickable(enabled = !busy) { expanded = true }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = chosen?.tag ?: "Select a model",
                        style = SecretPanelType.body,
                        fontFamily = FontFamily.Monospace,
                        color = if (chosen == null) BossThemeColors.TextMuted else BossThemeColors.TextPrimary,
                    )
                    chosen?.let {
                        Text(text = it.note, style = SecretPanelType.caption, color = BossThemeColors.TextMuted)
                    }
                }
                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    tint = BossThemeColors.TextSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.heightIn(max = 320.dp).background(BossThemeColors.SurfaceColor),
            ) {
                models.forEach { model ->
                    DropdownMenuItem(
                        onClick = {
                            expanded = false
                            chosen = model
                        },
                    ) {
                        Column {
                            Text(
                                text = model.tag,
                                style = SecretPanelType.body,
                                fontFamily = FontFamily.Monospace,
                                color = BossThemeColors.TextPrimary,
                            )
                            Text(text = model.note, style = SecretPanelType.caption, color = BossThemeColors.TextMuted)
                        }
                    }
                }
            }
        }

        BossPrimaryButton(
            text = if (installingTag != null && installingTag == chosen?.tag) "Installing…" else "Install",
            onClick = { chosen?.let { onInstall(it.tag) } },
            enabled = !busy && chosen != null,
        )
    }
}

@Composable
private fun ModelSectionContent(
    descriptor: ProviderDescriptor,
    connection: ProviderConnection,
    catalog: CatalogState,
    busy: Boolean,
    onSelectModel: (String) -> Unit,
    onRefresh: () -> Unit,
    onTest: () -> Unit,
    onEndpointChange: (String) -> Unit,
    onManualModelChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Model",
                style = SecretPanelType.body,
                fontWeight = FontWeight.Medium,
                color = BossThemeColors.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    color = BossThemeColors.AccentColor,
                    strokeWidth = 2.dp,
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            if (descriptor.modelsEndpoint != null && connection.isConfigured) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = "Refresh model list",
                    tint = BossThemeColors.TextSecondary,
                    modifier =
                        Modifier
                            .size(16.dp)
                            .clickable(enabled = !busy, onClick = onRefresh),
                )
            }
        }

        when {
            // No models endpoint (a custom/self-hosted runtime): there is nothing
            // authoritative to ask, so the endpoint and model id are typed in. Without
            // these two fields a custom provider could be given a key and still never
            // be usable, because activeConfig() requires both.
            ProviderRegistry.needsManualModel(descriptor) -> {
                Text(
                    text =
                        "${descriptor.displayName} has no model list to query — " +
                            "enter the endpoint and the model id it expects.",
                    style = SecretPanelType.meta,
                    color = BossThemeColors.TextSecondary,
                )
                ManualEndpointAndModel(
                    connection = connection,
                    enabled = !busy,
                    onEndpointCommit = onEndpointChange,
                    onModelCommit = onManualModelChange,
                )
            }

            catalog is CatalogState.NotConfigured ->
                Text(
                    text = "Add an API key to load ${descriptor.displayName}'s models.",
                    style = SecretPanelType.meta,
                    color = BossThemeColors.TextSecondary,
                )

            catalog is CatalogState.Loading ->
                Text(
                    text = "Loading models from ${descriptor.displayName}…",
                    style = SecretPanelType.meta,
                    color = BossThemeColors.TextSecondary,
                )

            else -> {
                val loaded =
                    when (catalog) {
                        is CatalogState.Loaded -> catalog
                        is CatalogState.Failed -> catalog.lastKnown
                    }

                if (catalog is CatalogState.Failed) {
                    Text(
                        text = catalog.message,
                        style = SecretPanelType.meta,
                        color = BossThemeColors.ErrorColor,
                    )
                }

                if (loaded == null) {
                    Text(
                        text = "No models available yet.",
                        style = SecretPanelType.meta,
                        color = BossThemeColors.TextSecondary,
                    )
                } else if (loaded.models.isEmpty()) {
                    Text(
                        text = "${descriptor.displayName} reported no installed or available models.",
                        style = SecretPanelType.meta,
                        color = BossThemeColors.TextSecondary,
                    )
                } else {
                    Text(
                        text = "Default for apps that don't pick a model themselves. " +
                            "Those apps need a selection here; agents can choose their own models.",
                        style = SecretPanelType.meta,
                        color = BossThemeColors.TextSecondary,
                    )
                    ModelPicker(
                        models = loaded.models,
                        selectedModelId = connection.selectedModelId,
                        enabled = !busy,
                        onSelect = onSelectModel,
                    )
                    FreshnessLine(loaded)
                    loaded.models
                        .firstOrNull { it.id == connection.selectedModelId }
                        ?.let { ModelFacts(it) }
                }
            }
        }

        if (connection.isConfigured && descriptor.modelsEndpoint != null) {
            BossSecondaryButton(
                text = "Test connection",
                onClick = onTest,
                enabled = !busy,
            )
        }
    }
}

@Composable
private fun ModelPicker(
    models: List<AiModel>,
    selectedModelId: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = models.firstOrNull { it.id == selectedModelId }

    Box {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(BossThemeColors.BackgroundColor)
                    .border(1.dp, BossThemeColors.BorderColor, RoundedCornerShape(6.dp))
                    .clickable(enabled = enabled) { expanded = true }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = selected?.displayName ?: "Select a model",
                style = SecretPanelType.body,
                color = if (selected == null) BossThemeColors.TextMuted else BossThemeColors.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = BossThemeColors.TextSecondary,
                modifier = Modifier.size(18.dp),
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 320.dp).background(BossThemeColors.SurfaceColor),
        ) {
            models.forEach { model ->
                DropdownMenuItem(
                    onClick = {
                        expanded = false
                        onSelect(model.id)
                    },
                ) {
                    Column {
                        Text(
                            text = model.displayName,
                            style = SecretPanelType.body,
                            color = BossThemeColors.TextPrimary,
                        )
                        if (model.displayName != model.id) {
                            Text(
                                text = model.id,
                                style = SecretPanelType.micro,
                                fontFamily = FontFamily.Monospace,
                                color = BossThemeColors.TextMuted,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * States when the list was retrieved. The whole point of fetching live is that the
 * user can tell — a silent list is indistinguishable from the hardcoded one this
 * replaced.
 */
@Composable
private fun FreshnessLine(loaded: CatalogState.Loaded) {
    val ageMinutes = ((System.currentTimeMillis() - loaded.fetchedAtEpochMs) / 60_000L).coerceAtLeast(0)
    val age =
        when {
            ageMinutes < 1 -> "just now"
            ageMinutes < 60 -> "$ageMinutes min ago"
            ageMinutes < 60 * 24 -> "${ageMinutes / 60} h ago"
            else -> "${ageMinutes / (60 * 24)} d ago"
        }
    val origin = if (loaded.fromCache) "cached" else "live"
    Text(
        text = "${loaded.models.size} models · $origin · updated $age",
        style = SecretPanelType.caption,
        color = BossThemeColors.TextMuted,
    )
}

@Composable
private fun ModelFacts(model: AiModel) {
    val facts =
        buildList {
            model.contextLength?.let { add("${formatTokens(it)} context") }
            model.maxOutputTokens?.let { add("${formatTokens(it)} max output") }
            if (model.capabilities.isNotEmpty()) add(model.capabilities.joinToString(", "))
            model.ownedBy?.let { add(it) }
        }
    if (facts.isNotEmpty()) {
        Text(
            text = facts.joinToString(" · "),
            style = SecretPanelType.caption,
            color = BossThemeColors.TextSecondary,
        )
    }
    model.allowanceSummary?.let {
        Text(text = it, style = SecretPanelType.caption, color = BossThemeColors.TextSecondary)
    }
}

private fun formatTokens(tokens: Int): String =
    when {
        tokens >= 1_000_000 -> "${tokens / 1_000_000}M"
        tokens >= 1_000 -> "${tokens / 1_000}K"
        else -> tokens.toString()
    }

@Composable
private fun LegacyImportBanner(
    offer: LegacyImportOffer,
    onImport: () -> Unit,
    onDismiss: () -> Unit,
) {
    BossCard {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Import ${offer.providerIds.size} key(s) from previous settings",
                style = SecretPanelType.body,
                fontWeight = FontWeight.Medium,
                color = BossThemeColors.TextPrimary,
            )
            Text(
                text =
                    "Keys for ${offer.providerIds.joinToString(", ")} were found in " +
                        "${offer.sourcePaths.joinToString(" and ")}. Importing copies them " +
                        "into encrypted storage and renames " +
                        (if (offer.sourcePaths.size > 1) "those files" else "that file") +
                        " to .migrated — the keys stay in " +
                        (if (offer.sourcePaths.size > 1) "them" else "it") +
                        " as plain text, so delete " +
                        (if (offer.sourcePaths.size > 1) "them" else "it") +
                        " yourself once you've confirmed everything works. " +
                        "Model choices are not imported — pick from each provider's current " +
                        "list instead. A custom provider's endpoint has to be re-entered too.",
                style = SecretPanelType.meta,
                color = BossThemeColors.TextSecondary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BossPrimaryButton(text = "Import", onClick = onImport)
                BossSecondaryButton(text = "Not now", onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun MessageBanner(
    text: String,
    tint: Color,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(tint.copy(alpha = 0.12f))
                .border(1.dp, tint.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(text = text, style = SecretPanelType.meta, color = BossThemeColors.TextPrimary)
    }
}

/**
 * Endpoint and model id for a provider with no model list to query.
 *
 * Committed on focus loss rather than per keystroke, so a partly-typed URL isn't
 * persisted and every character doesn't cost a store write.
 */
@Composable
private fun ManualEndpointAndModel(
    connection: ProviderConnection,
    enabled: Boolean,
    onEndpointCommit: (String) -> Unit,
    onModelCommit: (String) -> Unit,
) {
    // Keyed on the values too, not just the provider id: the store read can land after
    // first composition, and keying on the id alone left both fields rendering empty even
    // though values were stored.
    var endpoint by remember(connection.providerId, connection.customEndpoint) {
        mutableStateOf(connection.customEndpoint.orEmpty())
    }
    var modelId by remember(connection.providerId, connection.selectedModelId) {
        mutableStateOf(connection.selectedModelId.orEmpty())
    }
    // onFocusChanged also fires on the initial focus event, so committing on any
    // !isFocused would write whatever the field held at composition — blanking a stored
    // endpoint. Only a genuine focused -> unfocused transition counts.
    var endpointWasFocused by remember(connection.providerId) { mutableStateOf(false) }
    var modelWasFocused by remember(connection.providerId) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BossTextField(
            value = endpoint,
            onValueChange = { endpoint = it },
            label = "Endpoint",
            placeholder = "http://localhost:11434/v1/chat/completions",
            enabled = enabled,
            singleLine = true,
            modifier = Modifier.onFocusChanged { focus ->
                if (endpointWasFocused && !focus.isFocused) onEndpointCommit(endpoint)
                endpointWasFocused = focus.isFocused
            },
        )
        BossTextField(
            value = modelId,
            onValueChange = { modelId = it },
            label = "Model id",
            placeholder = "llama3.1:8b",
            enabled = enabled,
            singleLine = true,
            modifier = Modifier.onFocusChanged { focus ->
                if (modelWasFocused && !focus.isFocused) onModelCommit(modelId)
                modelWasFocused = focus.isFocused
            },
        )
        Text(
            text = "Saved when you click away from a field.",
            style = SecretPanelType.caption,
            color = BossThemeColors.TextMuted,
        )
    }
}
