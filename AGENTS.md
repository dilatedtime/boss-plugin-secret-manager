# AGENTS.md

## Project Overview

**Secret Manager (Dynamic)** (`ai.rever.boss.plugin.dynamic.secretmanager`) is a dynamic plugin for the BOSS desktop application.

Your credentials, secrets shared with you, Plugin Store API keys and AI provider settings

- **Plugin ID**: `ai.rever.boss.plugin.dynamic.secretmanager`
- **Main Class**: `ai.rever.boss.plugin.dynamic.secretmanager.SecretManagerDynamicPlugin`
- **API Version**: 1.0.89 (`plugin.json` `apiVersion` and `minApiVersion`)

## Essential Commands

```bash
./gradlew buildPluginJar    # Build plugin JAR (output: build/libs/)
./gradlew build              # Full build
./gradlew processResources   # Process resources (syncs version)
```

## Workflow Rules

- Do NOT run the BOSS application to test. The user will test manually.
- After building, copy JAR to `~/.boss/plugins/` for local testing.

## Architecture

### Plugin Structure
```
src/main/kotlin/   → Plugin source code (package: ai.rever.boss.plugin.dynamic.*)
src/main/resources/META-INF/boss-plugin/plugin.json → Plugin manifest
build.gradle.kts   → Build config + version (single source of truth)
```

### Key Patterns
- Entry point: `DynamicPlugin` interface with `register(context)` and `dispose()`
- UI: `PanelComponentWithUI` with `@Composable Content()`
- State: ViewModel pattern with `StateFlow`
- Providers from `PluginContext`: `workspaceDataProvider`, `splitViewOperations`, `contextMenuProvider`, `activeTabsProvider`
- Null-safe provider access: providers may be null, UI must handle gracefully

### Dependencies
- **boss-plugin-api**: compileOnly (provided by host app at runtime)
- **Compose Desktop**: UI framework
- **Decompose**: Navigation and component lifecycle
- **Coroutines**: Async operations

## Version Management

**`build.gradle.kts` is the single source of truth for version.**

The `processResources` task automatically syncs the version into `plugin.json` at build time. Never manually edit the version in `plugin.json` - only change it in `build.gradle.kts`.

## Two sections, one plugin

The panel is segmented into **Secrets** (own + organisation, full CRUD, `getUserSecrets`) and
**Shared with me** (read-only, `getUserSecretsWithSharingInfo`). The second half arrived by
absorbing the `user-secret-list` plugin, whose "My Secrets" panel sat next to this one in the
sidebar and listed *everything* the caller could read - including their own secrets, which this
panel already showed. Two panels, overlapping lists, and the wizard installed the read-only one
by default and this one not at all.

Three things about it that are easy to get wrong:

**The partition key is `accessLevel`, never `isOwner`.** `get_user_secrets_with_shared` assigns
the level per UNION source (`supabase/migrations/20260802000000_secrets_org_ownership.sql:536`):
`owner` for source 1, `org` for source 4, and the share's own level for sources 2, 3 and 5.
Source 4 is the trap - it returns `is_owner = (s.user_id = auth.uid())`, so a **colleague's**
organisation secret arrives with `isOwner = false` while nobody shared it with anyone. Splitting
on `isOwner` files it under "Shared with me" and tells the user someone shared it with them.
`SecretAccess.isShare` also treats an *unrecognised* level as a share, so a source added
server-side surfaces in the read-only section rather than in the one offering Edit and Delete.
Mutation-verified: swapping the predicate for `!isOwner` fails *an organisation secret created
by a colleague is not a share*.

**The scroll prefetch only fires on a list that actually scrolls, and that is load-bearing.**
`shouldPrefetchMore` is a pure function with an overflow test (`renderedItemCount >
visibleItemCount`) because without it the section showing 2 shares out of a 10,000-secret vault
prefetched on its first frame with nothing scrolled - `lastVisibleIndex (1) >= loadedCount (2) - 3`
is true by arithmetic - and then again every time the spinner appearing and disappearing changed
the last visible index. One tab switch became roughly 200 sequential RPCs, each materialising 50
decrypted passwords. `MAX_AUTO_PAGES` did **not** bound that: the cap governs the ViewModel's
first-load auto-continue, and each of those was a separate `loadMore()`.

It is a pure function rather than an inline condition because the alternative was reasoning about
`snapshotFlow` dedup in review comments, which is how the loop survived a round of that. The
footer "Keep looking for more" button carries the case prefetch now declines - a handful of shares
in a large vault - and doubles as the recovery when a long list's last visible index stops
changing.

**The section pages over the unfiltered set, so it auto-continues.** A page is 50 entries of
everything readable, filtered client-side down to the shares, so a user with 50 of their own
secrets and one shared with them gets a first page that filters to nothing - and a section
reporting "nothing shared with you" while the server still has some is a lie the user cannot
tell from the truth. `load()` therefore keeps fetching while a page yields no shares, capped at
`MAX_AUTO_PAGES` (5 pages / 250 rows), after which the empty state offers "Keep looking". Two
details are load-bearing and both are pinned: the offset advances by the **raw** row count (by
the filtered count it re-reads the same page forever), and an **empty page ends the scan**
whatever `hasMore` says (the host derives that flag from `size >= limit`, so it should never be
true for an empty page, but a scan trusting it alone spins if it ever is).

**It loads lazily and needs cancelling.** `ensureLoaded()` fires on first entry into the
section, not in `init` - a fetch per panel open would be a second secrets RPC for a section most
opens never reach. And like `SecretManagerViewModel`, it runs on the *plugin* scope while being
per panel instance, so `SharedSecretsViewModel.dispose()` is called from the same
`lifecycle.doOnDestroy` hook: an auto-continue can be five round trips deep when the panel goes
away, and its state holds decrypted passwords.

**`dispose()` has to clear, not only cancel** - the same lesson as `SecretManagerViewModel`.
Cancellation is cooperative and lands only at a suspension point, so a page that has already
returned runs on to the terminal `_state.update` and seats a list of decrypted passwords onto a
ViewModel the panel just destroyed; and a load that merely *finished* leaves that list in state
with nothing to cancel. Hence the `disposed` flag checked before every in-coroutine update, plus
clearing `allShared` / `shared` in `dispose()`. Mutation-verified both ways.

The one thing `dispose()` must **not** touch is `clipboardCopyGeneration`. Bumping it there
invalidates the pending wipe's generation check, so the credential stays on the clipboard
forever - the outcome the wipe exists to prevent. That was written, caught by
*dispose does not cancel a pending clipboard wipe*, and removed. Same rule as `copySecret`.

**The clipboard wipe is the section's, not the card's.** `copySecretToClipboard` mirrors
`SecretManagerViewModel.copyPasswordToClipboard` (45s, generation token, value check) because the
ported panel had no wipe and the read-only half should not carry the weaker rule. The generation
token is only load-bearing when the *same* value is copied twice - with different values the
value check already saves the second copy - which is why the test copies one value twice.

`SecretsSection` was extracted from `SecretManagerView` unchanged when the sections landed. Its
`LazyListState` stays **hoisted** in the parent: the composable leaves composition whenever the
other section is on screen, so a local `rememberLazyListState` would drop the scroll position
every time the user glances at their shared secrets and comes back. Same reasoning for
`selectedSection`, which lives on `SecretManagerComponent` rather than being `remember`ed.

**The MCP tools label on `accessLevel` too.** They shipped labelling on `isOwner`, which told an
agent `shared(org)` about a colleague's organisation secret - the exact claim the sections exist to
avoid, on the surface a model actually reads rather than the one a person looks at. `accessLabel`
goes through `SecretAccess.isShare`, and `my_secret_get` uses `fold` rather than `getOrNull` so a
network or auth failure is not reported as "no secret with id X".

**`SharedSecretsViewModel` holds a `ComponentLogger` instance property and is passed as a
`@Composable` parameter** - the shape that made 1.2.6 and 1.2.7 unloadable. It is safe only
because `compose-stability.conf` resolves that package's stability at compile time, and
`buildPluginJar`'s `javap` guard proves no `$stable` read was emitted. Do not take that guard as
optional when adding another class here.

The two adopted MCP tools (`my_secrets_list`, `my_secret_get`) keep their original names because
agents, prompts and skills already call them, and `getUserSecretsWithSharingInfo` is the only
call that reports how a secret was reached. `my_secret_get` shares `secret_get`'s provider-key
refusal through one function - see "Provider keys are withheld from `secret_get`" for why
that matters.

## The panel's design system

The panel had **118 loose `fontSize = N.sp` literals** across nine sizes with no rule about which
meant what, a hardcoded Material blue, and `SuccessColor` doing duty as the accent. It is now on
the host's "Operator's Console" scale.

**`SecretPanelType` is a local copy of the type scale, and it has to be.** `BossTypography` and
`object BossTheme` live in the host's `plugin-ui-core` and are **not** on the plugin api - the api
jar ships `BossThemeColors`, `BossColors`, the `BossTheme` wrapper and `BossComponents`, so a
plugin cannot read `BossTheme.type`. The values come from `bossTypography()` and are cross-checked
against what the plugin-facing `BossComponents` actually paint, since those render beside this
panel's own text and any disagreement shows. Delete the object and point its call sites at
`BossTheme.type` if the tokens ever reach the api.

**Mono is confined to `label` and `data` on purpose.** The system's voice is mono for display and
data, but the host injects **MesloLGS** into its own typography and a plugin cannot reach that
`FontFamily` - so mono here resolves to the platform's, and using it for every heading would put a
*different* mono beside the host's chrome.

**`SuccessColor` is not the accent.** Twenty-nine sites used it for spinners, primary buttons,
checkbox ticks, text cursors, tag chips and the `+` glyph. That looks correct only because this
theme's accent happens to be green too: under Blueprint, where accent is amber, every control in
the panel would have gone green while the chrome went amber. The seven remaining uses all mean
"succeeded" - the copy confirmation, the "key created" panel, a 2FA-enabled badge, a passing key
test, and a provider whose credential is already stored.

**One row carries the sections and the actions.** Removing the duplicated in-panel title (the
chrome prints it, and at a real sidebar width the second copy truncated to "Secret M...") left a
56dp band holding two icons, so the refresh and `+` buttons moved onto the tab strip's baseline.

**`IntrinsicSize.Max` on `SectionTab` is load-bearing.** The selected-tab indicator is
`fillMaxWidth()`, which in an unweighted `Row` resolves to the whole *remaining* width - so the
first tab ate the row, pushed the second one off the edge and took the actions with it. The tabs
were `weight(1f)` each before, which bounded it by accident, which is why this only broke when
they stopped being stretched. Per `compose-layout-bugs-need-a-screen` it was caught by looking at
the panel, not by a test.

**Colour is spent on one thing per card.** Share, Edit and Delete were a hardcoded blue,
success-green and error-red on every row - a set of traffic lights repeated down the list, none of
which meant anything. Only Delete is coloured now. Same reasoning retired the filled `Shared ·
read` pill in the shared section for a 12% tinted chip carrying the access level alone: the tab
above it already says everything in the list is a share, and a fill is the system's "signal",
worth spending on something that changes between rows.

The two "Create API Key" buttons still hardcode `Color.Black` labels on a `WarningColor` fill.
`BossPrimaryButton` is the right answer and is present at the floor, but those buttons carry an
inline spinner and enable logic, so converting them is its own change.

**Adopting a host component can delete a control, silently.** `BossSearchBar` has no clear
button and the field it replaced did, so the first pass removed the only pointer-driven way to
reset a filter - inside a change that was supposed to be about type and colour. `PanelSearchField`
wraps the component and puts the button back beside it (there is no slot inside its border, and an
overlay would sit on the tail of a long query). It takes the space only when there is something to
clear: reserving the slot permanently left the field ending 26dp short of the cards below it, a
visible step in the panel's edge in the state the user looks at almost all the time. Both sections
use it, which is what the two wrappers it replaced each claimed to be. **Read what a component
does before swapping a hand-rolled one for it**, and diff the controls, not just the pixels.

**`BossBadge` declines to draw a zero itself** (`if (count > 0)`). The tab strip carried a
`hasLoadedShared` flag plus a local `badge > 0` check to keep a `0` off the tab before anything
was fetched; both restated the component's own rule, so the flag decided nothing. The count goes
straight through now.

**The unselected tab renders the indicator at `alpha(0f)`**, rather than an `else` branch with a
matching `height(3.dp)`. That constant was a second copy of the host component's height, so a host
that changed it would have made the row jump when a tab was selected.

**The tabs are `selectable(role = Role.Tab)` inside a `selectableGroup()`.** Material's `Tab`
supplied the role and the selected state to the accessibility tree, and hand-building the strip
dropped both - a screen reader otherwise announces two unlabelled buttons and never says which is
current. Same reason `QuietCopyButton` and `SharedSecretBadge` pass `contentDescription = null`:
a glyph beside its own label makes the label read twice.

**Weight belongs to the token, not the call site.** `SecretPanelType.title` declares SemiBold and
all seven call sites passed `fontWeight = FontWeight.Bold` straight after it, so the token's weight
never reached the screen and the panel's headings sat a step heavier than the host's. Three more
sites paired `bodyStrong` with `fontWeight = Medium`, which it already is. The four that wanted a
genuinely heavier `meta` got a token (`metaStrong`) instead of an inline override - a scale with
named sizes and hand-set weights is the same problem one field over.

**`verifyNoLooseFontSizes()` in `build.gradle.kts` pins this at build time**, over the two panel
files and `ai/AiProvidersPanel.kt`. Nothing here renders in the test suite, so no test can watch
the scale erode: a contributor adding one `fontSize = 13.sp` gets a green build and the drift is
invisible until it is 118 again. Same class of guard as the `javap` `$stable` check and the
plugin.json stamp assertion. It fails on a **missing** file too, so a rename cannot quietly retire
it. Mutation-verified: putting one literal back into `AiProvidersPanel` fails the build with that
file and line. Note that a mutation has to *compile* to reach the guard - the first attempt put a
literal into a file whose `sp` import the same pass had removed, so it failed at compile and proved
nothing.

`ai/AiProvidersPanel.kt` is on the scale too (24 literals). It is the one surface where a user sees
this plugin's chrome inside the host's own Settings window, so it is where a disagreement with the
host shows most.

### The publish keys are not "API keys"

The `+` menu's third item said **Create API Key**, which named the mechanism and not the job. This
panel has *three* unrelated things called an API key - an AI provider's key, the "this is an API
key" tag any secret can carry, and this - and only this one publishes a plugin to the store, with
`publish` / `version` / `finalize` scopes against the Plugin Store API. A user who opened the menu
to release a plugin could not tell which item to press, which is how it was reported.

They are **Plugin Store publish keys** now, everywhere a person reads: the two menu items, both
dialogs, the revoke confirmation, the error strings, the note written onto the stored secret, the
manifest `description` and the README. The AI-provider strings and the `api_key` *tag* keep the
generic name, because for those it is the right one.

**`website = "boss_plugin_store_api_key"` is deliberately unchanged.** That is the identifier the
stored secret is found by, not a label - renaming it would orphan every key already created. Same
for the `api_key` tag and the `X-API-Key` header, which is what the server actually reads.

While the menu was open it was also clear that its four items came from four different apps: two in
Title Case and two in sentence case, over a white, a green, an orange and a second green glyph, none
of which distinguished anything. One casing (sentence, as everywhere else in the panel now) and one
muted glyph colour.

**The menu was checked by opening it, not by reading the strings.** A `DropdownMenu` sizes to its
content, and "Create Plugin Store publish key" is roughly twice the old label - worth seeing against
a real sidebar width rather than assuming the popup would cope. `showAddDropdown`'s initial value was
flipped to `true` for one build to get it on screen, the same trick as defaulting `selectedSection` to
the shared tab; both are reverted, and `git diff` on those two lines is empty.

## Three sections, and the AI one is not owned by the panel

The panel is segmented into **Secrets**, **Shared with me** and **AI**. `AiProvidersPanel` also
remains available through the compatibility settings API for older hosts, from one definition. It is there because this plugin owns every AI credential in BOSS while the panel
holding them was reachable only through the host's Settings window: two clicks and a different
window away from the vault the keys are stored in.

Four things about it that are easy to get wrong:

**Cross-plugin navigation is a short-lived command, not durable state.** The event contract is
`CustomPluginEvent(eventName = "secret-manager.open-ai", payload["windowId"] = <host window>)`.
`ProviderNavigation` retains it because the click can beat panel construction, scopes it by window,
and consumes it once. It expires after 30 seconds so opening the panel much later cannot jump the
user for a forgotten click; a panel without an AI ViewModel consumes-and-declines it too. The event
collector starts `UNDISPATCHED` only to establish the subscription before `register()` returns;
unlike `checkGateway`, it performs no host registry or network work on that thread. Collection
failures are contained because the shared plugin scope is not a supervisor.

**The ViewModel arrives as a supplier, and the order forces that.** `AiProvidersViewModel` is built
inside `registerAiProviderSettings`'s `LinkageError` guard - it starts a `catalog.states` collector,
which on a host that cannot link `LlmProviderSettingsApiImpl` would be started and then orphaned -
and that guard runs **after** `registerPanel`, so a *non*-linkage failure in it cannot cost the user
their secrets panel. So the value does not exist when the panel factory is registered. A captured
`var` read through `() -> AiProvidersViewModel?` closes the gap: Kotlin compiles it to a shared
reference, `registerPanel` only stores a factory, and the host does not call that factory until
after `register()` returns. Do not "simplify" it to a value.

**The panel does not own it and must not dispose it.** Every other ViewModel on
`SecretManagerComponent` is per panel instance and cancelled in `lifecycle.doOnDestroy`; this one is
the plugin's single instance, serving consumer APIs and compatibility settings through
`LlmProviderSettingsApiImpl`. Disposing it with the sidebar panel would break those consumers too.

**The tab is absent, not disabled, when there is no ViewModel.** Registration still contains an
unexpected `LinkageError` so the secrets panel survives a malformed host API, and a tab whose only
content is "not available here" is worse than one tab fewer. `showAiSection` is that check.

**`checkGateway()` launches; it does not read the registry on the registration thread.** The work
is one in-memory list read, but `getLoadedPlugins()` asks the plugin loader about its own registry
while that loader is part-way through loading *this* plugin - the shape that deadlocks if the host
ever holds a lock across `register()`. The notice is allowed to arrive a beat late, which is also
why `gatewayNotice` starts at `NONE` rather than at a "checking" state: a section that flashes
"install the gateway" for one frame on every open, for the many users who have it, is a worse lie
than a notice that appears late for the few who do not.

**Refresh rereads all setup sources.** `refreshConnections()` refreshes environment variables,
local Ollama presence, legacy import offers and credentials without closing the editor.
`checkGateway()` and `refreshCliEngines()` also run, since gateway installation and CLI login can
change while the panel sits open. Registry and engine health work launches on IO.
`refreshConnections` had to be added - `ensureConnectionsLoaded` is `compareAndSet(false, true)` and
loads once per ViewModel, so it is not a refresh.

### The AI Gateway is an optional dependency, and the section says so

`AiProvidersUiState.cliEngines` is empty in three situations, and the panel used to treat all three
alike on the stated grounds that "none of them gives the user anything to do here". That was wrong
about one of them. The gateway being **absent** is fixable; the gateway predating `AiCliSessionAPI`
and this host's api jar not linking the symbol are not. A user who had signed into `claude` in a
terminal specifically to use it here saw no Local CLI sessions section, no explanation, and no way
to discover that one plugin stood in the way.

`GatewayPresence` asks about the **plugin**, not the engine list, precisely because an installed
gateway serving no engines is a different fact from an absent one. It is ported from
`user-secret-list`'s `SecretManagerLink` minus the part that does not apply: that plugin's floor is
1.0.20, so it had to probe reflectively for `openPanel` (api 1.0.57). This plugin's floor is
**1.0.89**, so `PluginLoaderDelegate`, `PanelEventProvider`, `PanelId` and `openPanel` are all below
it and are called straight - a guard there would be dead code implying a risk that cannot occur.

Two rules carried over from that port, both mutation-verified here:

- **"Installed" is not `isPluginLoaded`.** `disablePlugin` flips the state and never unloads, so a
  disabled gateway is still in `getLoadedPlugins()` - while answering no `getPluginAPI` and serving
  no engines. Reporting it as present puts the section back to unexplained silence. `isEnabled`,
  `healthy` and `!isIncompatible` are all required. Dropping them fails *a disabled gateway is not
  installed*.
- **The install deep link is gated on Toolbox 1.9.14**, the release that first carried the handler.
  On an older one nothing is listening, so the link is not sent at all and the notice degrades to
  "Open the Toolbox" - a link that silently goes nowhere reads as a broken button. Sending it
  unconditionally fails *nothing is handed to the platform when the Toolbox is too old*.

**`plugin.json` declares the gateway `"optional": true`.** That is not a hedge, it is what the
manifest means: HTTP provider keys work without it, so it must not veto loading. The host still
prompts - `PluginDependencyResolution.missingFor` deliberately returns optional dependencies, "worth
telling someone about ... flagged so the prompt can be a suggestion rather than a warning" - and its
own words at install time are "works without it, but some of its features need it". The in-panel
notice is that sentence in the place it matters. All three of the gateway's other consumers declare
it optional too, so this follows the established convention rather than inventing one.

**The dependency entry carries no `version` key, on purpose.** `processResources` is line-based and
rewrites *every* `"version": "..."` line in the file, so a `"version": "*"` inside a dependency block
comes out of the built jar as this plugin's own version - "requires aigateway 1.2.17". The host
ignores the field anyway (`missingFor` matches on id alone). This exact trap was hit once in
`user-secret-list` and was invisible in the committed file.

## AI Providers (`ai/` package)

This plugin owns AI provider configuration **and is now the only place it is edited**. The host's
`Settings > AI Providers` section is gone, along with the `LlmProviderAPIAccess` singleton that
served it: the credentials live in this panel's vault, so the page that manages them belongs beside
them rather than two clicks away in another window. `LlmProviderSettingsAPI` is still registered -
that is what `PluginContext.llmProvider` relays to other plugins, which is the part they consume.

**The AI-provider secret cards jump into the AI section, not into Settings.** A card's chip used to
call `SettingsProvider.openSettings(window, "LLM_PROVIDERS")`; it now selects that provider and
switches the panel's section. `secret.website` holds the provider id, which is what makes it land on
the right row rather than at the top of the list. `settingsProvider` and `windowId` were removed
from `SecretManagerViewModel` with it - that jump was their only reader.

Navigation resolves the website first and then known provider tags through the same resolver as
credential storage. Edited metadata must not send a key into a different provider's form.
Unknown providers and unavailable AI support show an error in Secrets. The AI scroll state stays
hoisted alongside the two secret lists so a tab switch does not discard the reader's place.

### The section loads lazily, because `init` is `register()`

`ensureSectionLoaded()` runs the CLI probes and the gateway check on first entry, not from the
ViewModel's `init`. This object is constructed during `register()`, on **every launch**, for a
section most launches never open - and `refreshCliEngines()` runs `<engine> --version`, so two
engines meant two processes spawned during startup to fill in rows nobody had asked to see.

`ensureConnectionsLoaded()` deliberately did **not** move. Other plugins read
`PluginContext.llmProvider` without this panel ever being opened, so the warm-up stays eager - it
exists so the first AI action after a restart does not race the load.

Mutation-verified: putting the two calls back in `init` fails *nothing is probed until the section is
opened*, which counts engine-list reads rather than watching state, so a probe that merely starts
slowly still fails it. `openingTheSectionTwiceProbesOnce` pins the idempotence the panel relies on,
since it calls `ensureSectionLoaded()` from a `LaunchedEffect` on every entry.

Consumer discovery still reads the selected CLI id (a preference, without enumerating engines or
running health probes) before choosing an automatic HTTP default. The panel's `activeCliEngineId`
is populated only on entry now, so trusting it during startup or sign-in recovery would silently
select BOSS AI over an existing CLI choice. `BossAiDiscoveryTest` covers both paths before panel entry.

### The provider list is an accordion

The selected provider's detail renders **under its own row**, inside the same section. It used to be
a separate titled section below the whole eight-row list, which was fine in the host's Settings
window where it was all on screen at once - and wrong in a sidebar one row wide, where tapping the
fourth provider put the response off the bottom of the panel and the tap read as doing nothing.

Expanding in place removes the duplicate detail title for listed providers. New-provider forms
retain their title. Clicking an open row closes it and clears its draft, and a placed editor
requests scrolling into view so a secret-card link can reveal a provider below the fold.

Section descriptions are one short line. `BossSection`'s description is set for the width of the
Settings window; at sidebar width, two sentences of guidance is three lines of text above content
that explains itself. Keep the fact a first-time reader cannot infer (a CLI login overrides the
providers below) and drop the instructions.

### Automatic BOSS AI discovery

BOSS AI is plugin-owned. `BossAiCredentialSource` uses the existing generic
`SupabaseDataProvider.rpc("boss_ai_create_exchange_ticket")` to request an AI-only,
single-use ticket for the authenticated user. It exchanges that ticket at the fixed
BOSS AI `/auth/exchange` endpoint; no host broker registration, login-token exposure,
or user-created vault entry is required. RISA GLM and optional legacy shares still
use the host broker bridge.

`BossAiDiscovery` reads authenticated `/v1/provider` metadata. The provider publishes
its name, base URL and default recommendation; `/v1/models` publishes model names,
capabilities, defaults, limits and allowances. The credential source owns the trusted
bootstrap scope and rejects metadata outside it. HTTP redirects are not followed.
`managed:boss-ai` is the stable provider id. Never write its credentials, metadata or
account-specific catalog to disk; only explicit provider/model preferences persist.
The managed-provider predicates include both this id and legacy `shared:` ids.

Discovery failures keep a display-only BOSS AI row with a retry message, never a
configured connection. Metadata caches are generation-scoped, expire after five minutes
(or 15 seconds on failure), and are invalidated by sign-out and Refresh. Automatic
BOSS AI suppresses duplicate legacy BOSS AI shares. A server-recommended default fills
an absent preference, including recovery after initial authentication failure; it
never overrides an explicit provider/model choice or an active CLI engine.
The backend migration and function must be deployed, but no desktop host upgrade is
required. `BossAiDiscoveryTest` covers the first consumer read with no host broker,
a failed vault read, endpoint confinement, retry, local preference storage and
credential/catalog disk isolation.

### Legacy shared managed provider definitions

Provider definitions tagged `ai-provider-definition` are discovered through
`getUserSecretsWithSharingInfo`, including existing role and organisation shares.
`SharedProviderDefinition` parses their versioned notes and derives stable provider
ids from secret UUIDs. They are per-ViewModel descriptors, never mutations to the
process-global `ProviderRegistry`. The owner controls the shared configuration;
recipients keep model selections in local preferences and never write the share.

Legacy definitions can still be published with the permission-checked
`managed_ai_provider_publish` MCP tool. BOSS AI itself is automatic; its former
"Add BOSS AI provider" vault action has been removed.

The host broker owns the credential destination. Both catalog and inference URLs
must fit its `BrokerInfo.scopedTo` boundary before minting. Shared definitions
contain no upstream key; broker credentials stay in memory. Account-specific
catalogs are not persisted. Discovery caches only descriptors (including an empty
result) for five minutes, rechecking on the next credential reload after expiry;
invalidation and the panel's Refresh clear it immediately. Refresh only expires
the shared-definition cache, not minted credentials or the account generation.
Failed scans retry no
sooner than 15 seconds. A per-scan mutex coalesces renewals and a generation stamp
prevents a pre-sign-out scan from repopulating the cache. No decrypted vault rows
are retained by this cache. A capped scan retains the discovered prefix and shows
a separate shared-discovery warning, never a false credential-storage failure.
On a successful recheck,
removal drops the connection and invalidates its catalog. Inference authorization
is independently enforced by the server's live model permissions and allowances.

The machine-facing listing predicate requires both a credential and a successfully
fetched shared catalog. A transient catalog failure may reuse that same session's
last-known list; 401/403 failures, missing credentials and missing initial catalogs
remain unavailable. Metadata is not authorization: the broker enforces current
model access on every inference request. The panel keeps an unavailable shared row visible
so the user can check access. `activeConfig()` starts catalog discovery because
shared model defaults cannot be resolved from a credential alone. Shared catalogs
use `SHARED_CACHE_TTL_MS` (60 seconds); consumer polling can refresh them while the
panel is closed, bounded by the existing 30-second sweep floor. This is intentional
for changing allowances, not a vault rescan on every consumer read.

Trust is attached to the host's broker scope, not to the note author's display
name. Any readable definition, including a direct share, may name a trusted broker;
names and default recommendations are publisher-supplied, not an official-identity
badge. Role-wide publishing remains gated by the existing `secret.share.role` RPC.
Deploy the official definition from an admin-owned entry. Neither `isOwner` nor
`accessLevel` proves that another entry's author is an admin, so neither is used as
a fabricated publisher-verification check.

| Transition | What may change |
|---|---|
| First discovery with no saved or configured provider | Shared default recommendation may select a provider |
| Existing explicit provider/model choice | Keep the user's preference |
| Startup with a saved id absent from a complete discovery | Ignore the stale id for this session; prefer an existing configured provider |
| Shared endpoint edited | Revalidate host scope and invalidate the catalog |
| Complete scan proves the active share was removed | Clear active provider and catalog; ask the user to choose another, without silently rerouting |
| Failed or capped scan omits the active share | Keep its selected id, fail closed without credentials, retry discovery on subsequent consumer reads |
| Host scope cannot authorize a readable definition | Treat discovery as incomplete, withhold that credential, retain the selection for recovery |
| Transient catalog failure after a successful fetch | Retain in-memory model choices; rejected credentials never use stale catalog fallback |
| Account invalidated during discovery | Discard shared descriptors and credentials from that load |
| Recipient selects a model | Write local prefs only; shared vault entry stays read-only |

Review-round state rules: an incomplete or failed scan does not revoke the active
selection; unavailable credentials fail closed while a later successful discovery
restores the same selection. A generation-invalidated first load retries instead
of reporting a vault outage. Manual discovery refresh does not invalidate session
credentials. Exhausted startup retries report a retryable error and must not run
an empty catalog sweep or publish readiness. Cancelled discovery is rethrown,
never cached as a vault failure. Model-limit clamping is read-only; writers use raw connections.
Successful shared-token rotation within one invalidation generation preserves its
catalog; an account/secret invalidation still discards it, even if a token string
is reused. Failed discovery may retain bounded display-only rows from that same
generation, never credentials. A new generation cannot inherit old display metadata.
Consumer discovery retries have an in-flight guard and a completion-based rate floor;
exhausted initial loads are also paced. Manual Refresh may retry immediately.
Catalog HTTP work is limited to four concurrent requests, and at most 32 shared
definitions are admitted (UUID order, deterministic when defaults tie).
Incomplete same-generation discovery may additionally retain up to 32 display-only
rows, prioritizing active/open selections. Removing the token-rotation exemption,
display-row retention, or discovery rate floor each fails its corresponding
SharedProviderReviewTest regression (mutation-verified).
When shared discovery is enabled, its sharing-aware RPC is the single cold vault
scan: owned provider credentials and managed definitions are derived from that one
snapshot, while a failed sharing scan falls back to the narrower owned-secret RPC.
Rows label their provenance (shared by, organisation, or the user's vault), and
publisher names are stripped of control characters before display. Broker scope
checks for both endpoints use one host-registry snapshot. The 32-provider admission
cap warns but remains an authoritative completed scan; the 2000-row scan cap and a
scope refusal remain incomplete. Startup warns before falling back from a revoked
saved share, and a Refresh job completes only after its initial load completes.
The single-scan path, publisher-name sanitization, and startup fallback warning are
mutation-verified by the shared-provider regression tests.
Host broker exceptions are converted to a failed, unconfigured provider while coroutine
cancellation still propagates, so the initial consumer load cannot cancel a non-supervisor
plugin scope merely because a host bridge violated its Result-returning contract.
The unavailable-model banner and effective shared connection both resolve transient
catalog fallback through `usableSharedCatalog`; they cannot disagree about whether a
saved model disappeared. `connectionsLoaded` is a one-way "loaded at least once" latch,
so an exhausted later refresh reports its error without making waiting consumers regress.
Only explicit `owner` and `org` access levels can supply personal provider credentials;
unknown levels remain read-only and cannot silently become the user's API key.
Replacing the banner's fallback resolver with a Loaded-only cast fails the dedicated
transient-fallback regression (mutation-verified).
The sharing-aware RPC necessarily decrypts bounded pages containing readable shares;
the store immediately projects them to non-secret provider metadata and retains no shared
password. A host with no currently scoped broker skips that wider scan entirely. Competing
default recommendations rank own-vault, then organisation, then direct-share provenance;
that provenance is presentation/default ordering only and never an authorization signal.

Review regressions were mutation-checked: reading the derived connection in
`selectModel` fails the default consumer test (100 instead of the original 2000
token preference); removing the discovery scope guard fails the malicious-share
assertion. The malicious id sorts inside the 32-provider cap, so the cap cannot
make that security assertion pass accidentally.

Absent shared preference entries are retained, not pruned on discovery: a transient
read failure or capped scan cannot prove revocation, and deleting them would lose
the user's model choice if access returns. `readModels` filters a view; it never
pruned the on-disk preference file even before shared ids were introduced.

`SharedProviderReviewTest` covers scan caching/expiry/invalidation, unsupported
hosts, capped scans, separate error reporting, startup selection, disk isolation,
and active-share removal. `ModelCatalogClientParseTest` uses the backend's numeric
bigint allowance shape as well as string-encoded counts and malformed capabilities.
Mutation-verified: removing scope confinement fails both original scope tests;
removing each disk guard fails its separate write/seed test; accepting stale saved
ids fails startup selection; unconditional machine listing fails its gate test;
and restoring string-only allowance parsing fails the numeric-envelope test.

The deployment/definition schema is documented in the host repository at
`supabase/functions/boss-ai/README.md`. A host release must register the trusted
broker for legacy shares; automatic BOSS AI uses the plugin-owned ticket flow instead.

This plugin owns **all** AI provider configuration. Older hosts can render
`LlmProviderSettingsPanel` through `LlmProviderSettingsAPI`; current hosts use this panel.
`PluginContext.llmProvider` is relayed from the same registered instance. Provider registry, credentials, environment-variable resolution
and the model catalogue all live here.

### Consumer discovery is connection-first and credential-free

`configuredProviders()` returns resolved connections for consumers that own model selection;
`activeConfig()` keeps the stricter ready-to-send-default-or-null contract. A model-independent
connection may therefore carry a blank `modelId`, but never a missing required credential; a blank
`apiKey` means the service is keyless and the consumer omits credential headers. Google-style
model-in-path providers are omitted until a default exists because their complete endpoint cannot
otherwise be formed. This semantic split is part of the API KDoc, not merely an implementation
detail, because already-installed consumers can call either method.

`availableModels()` is credential-free and asynchronous. Missing catalog state is omitted, a
successfully fetched empty catalog is returned as an empty list, and `Failed.lastKnown` remains
available so an offline refresh does not empty a consumer's picker. It uses the same machine-facing
listing rule as `configuredProviders`: an Ollama daemon appears only after its catalog loaded.
`addedProviderIds` is deliberately excluded because it is a UI-session affordance for keeping the
Install card visible, not evidence that another plugin can call localhost. Both consumer methods
start catalog discovery so their answers do not depend on which one another plugin called first.

`ensureCatalogsLoaded` always awaits the Ollama system probe before a sweep, bounds its wait for the
credential load, spaces consumer-triggered sweeps, and releases its in-flight guard on every exit.
Transient provider failures have their own five-minute retry floor; 401/403 failures remain parked
until the credential changes. `load()` marks discovery started too: invalidating a changed
credential must never clear a catalog without scheduling its replacement. `catalogsLoaded` drops
false across that invalidation and becomes true only after the replacement sweep completes.

### Legacy plaintext key import

`LegacySettingsImport` migrates keys out of the plaintext files that predate this plugin. It
iterates a list of `LegacySource`s (file + parser), so adding another historical location is a
one-entry change rather than a second code path:

- `~/.boss/llm_settings.json` — the host's old file, nested `apiKeys` map.
- `~/.boss/config/llm-settings.json` — `llmrpa`'s own file, one flat field per provider. That
  plugin rewrote it on every keystroke of its own API-key field; it now reads
  `PluginContext.llmProvider` instead, so without this source migrating it would have silently
  stranded whatever the user had typed there.

The llmrpa source resolves against the real `~/.boss`, **not** `bossRootDir`: llmrpa hardcoded
`File(user.home, ".boss/config")` and never honoured `boss.dev.mode`, so under a dev host its
keys are still in `.boss` and following the root would miss them.

Three invariants exist to avoid losing or leaking a key, each retained per-file:

- a key already supplied by an environment variable is **not** imported and its file is **not**
  retired — storing it would recreate the plaintext-persistence leak this feature removed, and
  renaming would strand a real key behind a file the user was never told about;
- a file is retired only when every key it offered was stored, so a failed write can be retried;
- retiring is a rename to `.migrated`, never a delete. The host kernel's self-healing resolves
  its repair key before any plugin loads and cannot reach this store, so
  `llm_settings.json.migrated` is its only remaining legacy source.

Model ids are dropped (both files name retired models, and the picker's whole purpose is to be
live), and so is llmrpa's `customEndpoint` — writing it needs `saveSettings`, which would blank
any `selectedModelId` already chosen for `CUSTOM`. The banner tells the user to re-enter it.

`LegacySettingsImportTest` (8 cases) mutation-verified twice: ignoring env shadowing fails
*a key shadowed by an environment variable is neither imported nor retired*, and retiring
regardless of write failures fails *a file whose writes all fail is kept so the import can be
retried*. `FakeSecretDataProvider` was lifted out of `ProviderCredentialStoreTest` so both
suites assert against one fake rather than two that can drift.

**Model lists are always fetched live** from each provider's own models endpoint
(`ModelCatalogClient`), cached with a timestamp and a 6-hour TTL. There is
deliberately no bundled fallback list: the host implementation this replaced shipped
hardcoded models that drifted years out of date, and a provider with no credential
now reports "not configured" instead of guessing.

### `ai.rever.boss.plugin.logging` must never trigger a runtime `$stable` read

The host bundles `plugin-logging-desktop.jar`, whose `ComponentLogger` has **no** Compose
`$stable` field, and it shadows the boss-plugin-api copy (which *does* have one) parent-first at
runtime. So a `ComponentLogger`-typed **property** makes the Compose compiler emit a `$stable`
field on that class whose initialiser reads `ComponentLogger.$stable` - it resolves against the
api jar at build time and is missing at load time. `BinaryCompatibilityValidator` then rejects
the plugin outright and the host disables it as binary incompatible:

```
SecretManagerDynamicPlugin -> ai.rever.boss.plugin.logging.ComponentLogger.$stable: field not found
```

**This shipped broken in 1.2.6 and 1.2.7** - the plugin could not load on any host, and the
store served it for hours.

The primary defence is **`compose-stability.conf`**, which lists
`ai.rever.boss.plugin.logging.**` and is wired in via `composeCompiler.stabilityConfigurationFiles`.
That resolves the stability at compile time, so no runtime read is emitted *anywhere* in the
module - including from the eight other classes that hold a logger as an ordinary instance
property. Those are safe today only because they infer as unstable outright and the compiler bakes
in a constant; a refactor leaving one all-`val` with otherwise-stable types would have resurrected
this. Do not remove that file to "clean up".

`SecretManagerDynamicPlugin` additionally keeps its logger on the `companion object`. That is
belt-and-braces for the one class whose failure takes the entire plugin down, not a rule to apply
everywhere - and note a companion `val` is still a *property*, just of the companion class, so the
reason it helps is that it is no longer a property of the class whose stability is being computed.
Moving a logger to a nested class or top-level object is **not** equivalent reasoning.

No unit test can catch this: on the test classpath the api jar *is* the ComponentLogger, so
everything links. `buildPluginJar` therefore runs `javap` over the packaged classes and fails
the build if any of them references a `$stable` field on `ai.rever.boss.plugin.logging`.
Mutation-verified - putting the logger back as an instance property fails the build.

### The version must come from Gradle, not from a second copy of plugin.json

`version` reads the bundled `plugin.json` that `processResources` stamps. Two traps here, both
hit once already:

- `javaClass.package?.implementationVersion` does **not** work, even though `buildPluginJar`
  writes `Implementation-Version` into the manifest. `getImplementationVersion()` returns null
  under a plain `URLClassLoader`, which is what the host's `PluginClassLoader` extends, so the
  plugin reported `"unknown"` to the host and store for a whole release.
- The `pluginId` filter alone is not enough either: a bundled or stale copy of *our own*
  manifest on the host classpath matches it, and `getResources` is parent-first. `PluginVersionSource`
  therefore prefers the jar the plugin class itself came from, keeping the `pluginId` match as
  the fallback so IDE and test runs still resolve. The selection rules live in that object,
  away from the classloader, precisely so they can be tested - they have been wrong twice.
- `getResourceAsStream` is the wrong lookup: every BOSS plugin ships `plugin.json` at the same
  path and resource lookup is parent-first, so a neighbour's manifest could win and the plugin
  would report *someone else's* version. Enumerate with `getResources` and pick the document
  whose `pluginId` is ours.
- The jar tasks must **not** also `from("src/main/resources")`. `sourceSets.main.output` already
  carries the stamped copy; adding the raw directory put an *unstamped* `plugin.json` in the jar
  too (the committed one says `1.0.9`), and with `duplicatesStrategy = EXCLUDE` the winner was
  decided by `from` order alone.
- The ordinary `jar` and `buildPluginJar` otherwise resolve to the same filename. The ordinary
  jar lives under `build/intermediates/jar`, not `build/libs`: the shared release workflow globs
  `build/libs/*.jar`, and two tasks writing the release path let a plain jar overwrite the verified
  plugin artifact after `verifyPackagedJar` had already passed.

`PluginVersionTest` asserts against `boss.plugin.expectedVersion`, injected by the Test task from
the Gradle version. Comparing the reported version to the bundled `plugin.json` would be circular -
both read the same file, so any value in it passes, and `1.0.9` is valid semver.

### The panel is for everyone; two controls inside it are not

`secret.read` reached the baseline `user` role in migration `20260809000000`, so every
authenticated user gets this panel. Read that migration's header before assuming it widened
anything: the vault has always been per-user server-side (grants to `authenticated`,
`auth.uid()` self-scoping, RLS on `auth.uid() = user_id`), and `secret.read` was a
client-side visibility gate translated forward verbatim from the pre-RBAC `requiresAdmin`
flag. The practical consequence of leaving it admin-only was not that secrets were safer, it
was that `DynamicPluginManager` skips `register()` for an inaccessible plugin - so no
non-admin got `Settings > AI Providers`, and `PluginContext.llmProvider` was null in every
other plugin. All AI in BOSS was admin-only by accident.

What did NOT become everyone's is sharing with a **role**. `share_secret` gated role targets
on `can_manage_secret` alone, i.e. any owner could share with any global role - including
`user`, which is a descendant of every role and therefore means "everyone" (see
`20260802010000`'s own header). That was survivable only while non-admins could not open this
panel. It now requires `secret.share.role`, enforced in the RPC and mirrored here by
`SecretManagerState.canShareWithRoles`, which hides the share dialog's Roles tab.

Three things about that flag:

- **It is collected, not read once.** The panel is constructed as soon as the plugin
  registers, which can precede the permission claim landing, so a one-shot read in
  `initialize()` leaves an admin looking at a hidden tab until they reopen the panel.
  `observeRoleSharePermission` combines `userPermissions` and `isAdmin` - both, because
  `hasPermission` answers true for an admin regardless of the permission set, so an admin
  whose claim arrives without a permissions change still needs a recompute.
- **It fails closed on a null `authDataProvider`**, and `selectedTab` is clamped back to
  Users if the permission disappears while the dialog is open (remembered state does not
  re-derive itself).
- **It is not the enforcement.** The RPC is. `RoleShareGateTest` is mutation-verified three
  ways: hardcoding the flag true fails the no-permission, late-arrival and revocation cases;
  collecting `userPermissions` alone (dropping the `isAdmin` half of the combine) fails
  *an admin claim arriving after initialize*; and a `dispose()` that does not cancel fails
  *dispose stops tracking the permission*.

**The collector is the only launch in this ViewModel that never completes, and it needs a
destroy hook.** `scope` is the *plugin* scope while the ViewModel is per panel instance, so an
uncancelled `collect` on a StateFlow roots the ViewModel for the plugin's whole lifetime - and
`state.secrets` holds `SecretEntryData` with the decrypted `password`, so each panel open would
strand a full plaintext credential list. `SecretManagerComponent` calls `viewModel.dispose()`
from `lifecycle.doOnDestroy`, which cancels the collector and clears the secrets. Nothing needed
this before because every other launch here terminates - so if you add a second collector,
cancel it in `dispose()` too.

`loadAvailableRoles()` is gated on the same flag: a user who will never see the Roles tab should
not pay a round-trip for its contents on every share-dialog open. The `roles` table is readable
by any authenticated user ("Anyone can view roles" RLS plus a table grant), so this is a cost
question, not an error-banner one - checked rather than assumed.

**But the gate cannot be a plain "fetch on dialog open" check**, because the Tab appears the
instant the flag flips. A claim landing while the dialog is already open would otherwise present
a Roles pane backed by an empty list, with no spinner and no empty state to explain it,
recoverable only by reopening the dialog with nothing to say so. The collector therefore kicks
the fetch itself when the flag turns true and the dialog is open. Reasoning that "the flag
settles before any dialog can open" assumes exactly what the collector exists to deny - that was
the first version of this and it was wrong.

**`dispose()` needs the `disposed` flag, not just the job cancels.** `createSecret` and
`updateSecret` call `loadSecrets()` on success, which assigns a *fresh* `loadJob` - so saving a
secret and closing the panel before the round-trip returned put the plaintext list back on a
ViewModel nobody could see. (`deleteSecret` is safe: it filters the existing list, which dispose
has already emptied.) Do **not** "simplify" this into cancelling a child scope: `copySecret`'s
clipboard wipe is *meant* to outlive the panel by `CLIPBOARD_CLEAR_DELAY_MS`, and cancelling it
would leave a copied credential on the system clipboard indefinitely - an unbounded OS-level
exposure traded for a bounded in-memory one.

Plugin Store API keys were already gated, on `api_key.create` via
`PluginStoreApiKeyProvider.canManageApiKeys()`. Nothing changed there.

`lifecycle.doOnDestroy` is this plugin's first **Essenty extension** symbol (previously only
`ComponentContext` / `lifecycle`), and it sits on the always-taken component-construction path.
`buildPluginJar` packages only `sourceSets.main.output`, so `LifecycleExtKt` comes from the host
at runtime. Checked with `javap` rather than assumed: `doOnDestroy` is present in
`lifecycle-jvm-2.4.0` and `2.5.0` (the plugin compiles against 2.5.0), so a host on either is
fine.

`context.authDataProvider` is read on the always-taken registration path, and so are the four
`AuthDataProvider` members the ViewModel touches - a member newer than the floor is a
`NoSuchMethodError` there that takes the whole plugin down, not just the AI section. All five
were checked with `javap` against the released `boss-plugin-api-1.0.73.jar` (which predates the
current floor), not assumed: `PluginContext.getAuthDataProvider`, plus `getCurrentUser`,
`isAdmin`, `hasPermission(String)` and `getUserPermissions`.

### Provider keys are withheld from `secret_get`

Storing provider keys as ordinary secrets buys encryption, RLS and an audit trail for free.
The cost would have been agent readability: `secrets_list` hands out ids and `secret_get`
hands out the plaintext `password`, so ungated it is **two model-directed tool calls from a
prompt-injected agent to every configured provider key**.

`secret_get` therefore refuses entries tagged `TAG_AI_PROVIDER`. The asymmetry that decided
it: `PluginContext.llmProvider` also exposes `LlmConfig.apiKey`, but that is plugin code the
operator chose to install, whereas the MCP path is directed by a model. An agent that needs to
*use* a provider goes through `llmProvider`/`activeConfig()` and never needs the raw value.

Deleting the tag check restores the old behaviour; four tests cover both halves of both tools
(provider key withheld, ordinary secret still returned).

**`secret_get` and `my_secret_get` both call one `aiProviderRefusal` function, and that is
deliberate rather than tidiness.** The check was originally inlined in `secret_get` here, and
`user-secret-list`'s `my_secret_get` - same vault, same `secret.read` gate, a different repo -
had no equivalent, so for three days the withheld keys were readable through the sibling tool.
A gate on one tool and not its sibling is no gate. Now that both tools live here, any new one
that returns a password calls the same function. Mutation-verified: dropping the call from
`my_secret_get` fails *my_secret_get withholds an AI provider key*.

### Do not add OAuth without re-checking the docs

Sign-in is intentionally absent. As of July 2026:

- **Anthropic** prohibits third-party OAuth outright (policy 2026-02-20, billing
  enforcement 2026-04-04). Subscription tokens are Claude Code / claude.ai only.
  Wiring their OAuth client here would breach their terms.
- **OpenAI**'s "Sign in with ChatGPT" ships only inside Codex tooling; there is no
  third-party program.
- **xAI** and **Moonshot (Kimi)** publish Bearer-API-key auth only in their REST
  references. Their OAuth/device-code flows belong to their own coding CLIs - the
  same category as Anthropic's, and not a documented third-party surface.
- **Google** does have a documented installed-app OAuth flow, but it runs through
  **Vertex AI** - a different base URL needing a GCP project, region and ADC, not the
  `generativelanguage` key path used here. That is tracked as separate work.
- **Together** has no OAuth.

Providers instead get an assisted flow: a "Get API key" button opening
`ProviderDescriptor.consoleUrl` in a BOSS tab.

### Linkage containment

The manifest's declared `apiVersion` floor is **1.0.89** (`plugin.json`, both `apiVersion` and
`minApiVersion`). Check it rather than trusting prose: this section has lagged the manifest twice.
The registration guard remains a final containment boundary for malformed host installations,
not a substitute for declaring every type in a public method signature. In particular,
`AiProviderModels` and `AiAvailableModel` first ship in **v1.0.89**; they occur in
`availableModels()`'s signature, can resolve after guarded construction, and may be inspected by
the host's binary validator before registration. That is why the floor moved instead of claiming
the guard made older hosts safe.

Earlier audits remain useful evidence. Verified against the api tags:
`PluginContext.windowId`, `PluginContext.settingsProvider`, `SettingsProvider` and
`openSettings` all landed in **1.0.16** and are present in the `v1.0.20` tag. (That check
predates the floor moving and still holds: the api is additive-only, so presence in
an earlier tag implies presence in every later one. Do not read it as the floor being 1.0.20.) That matters
because they are read on the always-taken registration path (`registerPanel`), outside any
guard - a member newer than the floor would throw `NoSuchMethodError` there and take the
*whole* plugin down, not just the AI section. `cacheProvider` is inside the guard and so is
unconstrained.

The audit also has to cover the UI kit, not just `PluginContext`: the AI section added
first-time uses of `BossSection`, `BossCard`, `BossTextField`, `BossPrimaryButton` and
`BossSecondaryButton` (only `BossTheme`/`BossThemeColors` were used before). Containment held
because `AiProvidersPanel` is reachable only from `LlmProviderSettingsApiImpl`, so those
symbols never loaded on a pre-1.0.71 host - and the paragraph warned that this "stops being
true the moment the panel is rendered from `SecretManagerContent`".

**That has now happened, deliberately.** The design pass renders `BossCard`, `BossSearchBar`,
`BossBadge`, `BossTabIndicator` and `BossEmptyState` from `SecretManagerContent` and
`SharedSecretsSection`, i.e. on the always-taken path, so a host missing any of them throws
`NoSuchMethodError` where nothing can catch it. All five were therefore checked **against the
then-declared floor rather than the local jar** - `git show v1.0.73:.../BossComponents.kt` in the api
checkout - along with `BossThemeColors.TextMuted`, `AccentColor` and `BorderColor`. Reading the
sibling checkout's newest jar (1.0.84 at the time) would have proved nothing about 1.0.73. The
rule for the next component: check the tag, not the jar, and add it here.

The cross-plugin navigation path was checked against **v1.0.73**, not the newest jar:
`PluginContext.applicationEventBus`, `ApplicationEventBus.eventsOfType(Class)`, and
`CustomPluginEvent.eventName`/`payload` are all present there and therefore below today's floor.

`BrokerInfo.scopedTo` is also read by `BrokeredCredentialBridge`. Verified against
the released `v1.0.74` source, it predates the 1.0.89 floor. Scope is looked up live:
the current host lists signed-out brokers with `available=false`, but the API does
not promise every host keeps the same list throughout registration and sign-in.

`LlmProviderSettingsApiImpl`, `BrokeredCredentialBridge` and `GatewayCliEngineAccess` remain the
only files that name the newer AI API types (`LlmProviderSettingsAPI` and
`LlmApiFormat.GOOGLE_GENERATIVE` from 1.0.71; `BrokeredCredentialProvider`,
`PluginContext.brokeredCredentialProvider` and `LlmApiFormat.OPENAI_RESPONSES` from 1.0.74;
`AiCliSessionAPI` and `AiCliHealth` from 1.0.78; model discovery types from 1.0.89).
Everything else uses the plugin-local `WireFormat` enum, the plugin-local `BrokeredKeySource`
seam, and the plugin-local `CliEngineAccess` seam. Keep the adapter boundary even with the higher
floor: it limits blast radius when a host API installation is incoherent.

`ProviderCredentialStore` is constructed **outside** the guard, which is why it cannot
hold an api type and gets `brokeredKeys` assigned after the fact. Left null, brokered
providers report unconfigured - the same answer a host with no broker should give.

### Wire formats are direct at the declared API floor

`LlmApiFormat.OPENAI_RESPONSES` once needed reflective resolution because it landed in 1.0.74
while the plugin admitted 1.0.73 hosts. The 1.0.89 model-discovery signature raised the floor, so
every enum constant used by `LlmProviderSettingsApiImpl` is now guaranteed and the reflective
branch became misleading dead compatibility code. Map them directly; a new constant still requires
checking its release against the manifest before use.

### Local CLI sessions

The panel's other way to have working AI: a `claude` or `codex` login the user already made in
a terminal. No key, no endpoint, no model list - which is exactly why an engine is **not** a
`ProviderDescriptor` and gets a section of its own above the providers. Burying it under a
key-entry form would have a user paste a key they never needed.

The gateway owns the engines; this panel owns the choice. `CliEngineAccess` is a plugin-local
mirror of `AiCliSessionAPI` for the same reason `WireFormat` mirrors `LlmApiFormat`, and
`GatewayCliEngineAccess` is the only file naming the api types. The types predate today's floor,
but keeping them at one adapter preserves the optional-gateway boundary and keeps the panel seam
host-independent in tests.

**Exactly one thing is active, and both setters enforce it.** `setActiveCliEngine` clears the
HTTP provider and `setActiveProvider` calls `selectEngine(null)`. The second direction is the
one that misroutes if it is missing: the gateway prefers a selected engine over any configured
key, so without the release the panel would show a provider as active while every request still
went to the CLI. Mutation-verified - removing the release fails two cases.

A refusal is surfaced, not swallowed: `selectEngine` returns false for an engine the gateway
does not have, and a row that silently springs back is worse than one that says why. A refused
*release* deliberately leaves the engine shown as active, because it is still serving requests
and clearing the panel's copy would be the disagreement all of this is guarding against.

`CliEngineHealth.Unknown` has no api counterpart and is this panel's own: a row renders before
its probe answers, and "not installed" would be a claim rather than a wait. And **`Ready` does
not mean signed in** - the probe runs `--version`, which succeeds for an install that has never
been logged in, so the row says so rather than promising a turn will work.

### Brokered providers (RISA Codex GLM)

`ProviderDescriptor.brokerId` marks a provider whose credential nobody types in: the user
is signed in to BOSS and an organisation gateway mints a short-lived model-scoped key for
that identity. Such a provider has **no** `envVarNames`, **no** `consoleUrl` and **no**
`keyPlaceholder`, and the panel renders no key field. `ProviderRegistryTest` pins all
three, because each is a path by which a minted credential would end up somewhere durable
(`envVarNames` in particular also names the secret entry).

Two rules the tests hold, both mutation-verified:

- a brokered credential is **never** written to the secret store, and lives only in
  `ProviderCredentialStore`'s in-memory `brokeredCache`. It expires within hours and is
  cheap to re-obtain, so persisting it trades a credential that self-heals for one that
  leaks. Same rule as `CredentialSource.ENVIRONMENT`, different reason;
- `invalidate()` clears that cache. Sign-out invalidates, and a credential minted for the
  previous session must not be served to the next one.

A failed mint is **not** cached, so a user who signs in can retry without anything else
having to clear the cache. And a `BROKERED` source with a blank key collapses to `NONE`:
`isConfigured` reads the key, so a blank one would offer the provider as usable and fail
on the first request rather than where the user can act on it.

`RISA_GLM` is second in `all`, not first, and that is load-bearing - `default` is
`all.first()`, so leading with an organisation-only provider would make it the default
selection for every user outside RISA, for whom it can never resolve a credential.
`ProviderRegistryTest` pins that too.

### One predicate decides "can this provider's models be known"

`ProviderRegistry.hasKnownModels` / `needsManualModel`. Both the ViewModel
(`refreshStale`, `refreshOne`) and the panel (`ModelSection`) branch on it. They used to
test `modelsEndpoint == null` independently, and the result was that a provider serving a
**fixed** set was treated as one nobody can ask: `catalog.refresh` was never called for it,
so its state stayed `NotConfigured`, and the panel offered an endpoint-and-model-id form
for a provider that has neither. Worse, that form's model field went through
`saveSettings`, and a stored `selectedModelId` used to win outright - so a typo durably
replaced the single model the gateway serves. `resolveModelId` now constrains a
fixed-model provider's selection to its own list.

Mutation-verified: narrowing `hasKnownModels` back to `modelsEndpoint != null` fails
*a fixed-model provider reports known models and needs no manual entry*.

### Brokered mints are guarded twice

- **A generation guard**, mirroring `loadStoredSecrets`: a `fetch` that started before
  `invalidate()` and returns after it is handed to its caller but **not** seated, because
  it belongs to the session that just ended.
- **A per-broker mint lock.** "Check access" calls `invalidate()` then
  `reloadConnections()` while the `invalidations` collector reloads too, so two `loadAll()`s
  run at once, both miss the cache, and both would call the broker. The second waits and
  finds the first one's result. The explicit `reloadConnections()` stays because the next
  line reads `_state.value` to report the outcome.

### The reuse window is capped by the credential's own expiry

`ProviderCredentialStore.reuseUntil` caches a brokered credential for
`min(refreshAfterSeconds, expiresAt - 30s)`, not for the window the broker reported.

Trusting the window alone wedges the provider for its whole duration whenever a broker
reports a window that outlives its key, and that is not hypothetical. RISA's gateway
reported an hour-long reuse window on a key that expired in about three minutes; LLM RPA
then failed `401 Authentication Error - Expired Key` on three consecutive runs spanning
eleven minutes, re-sending the same dead token each time, because nothing re-minted until
the window lapsed. Only a plugin reload (which drops the memory-only cache) cleared it.

Three things worth keeping right:

- **The api calls `expiresAt` "informational" and `refreshAfterSeconds` the thing to act
  on.** This acts on both, deliberately: the window is still what bounds reuse, the expiry
  only ever shortens it. A broker that wants renewal well before expiry keeps that.
- **Every branch of the expiry parser has a test.** Four shapes were covered and two branches
  were not: `removeSuffix(" UTC")` and the compact-offset (`+0000`) regex could each be deleted
  with the suite still green. Both are pinned now, along with the already-`T`-separated-with-space
  case that an unconditional `replaceFirst(' ', 'T')` corrupted into a second `T`.
- **The expiry parser is tolerant on purpose.** The api documents RFC 3339, but the value
  originates in LiteLLM and has been seen space-separated instead of `T`-separated, and
  offset-less. A parser accepting only the documented shape returns null for the real value
  and silently disables the cap - which is worse than no cap, because it looks fixed.
  Offset-less is read as UTC, which is what LiteLLM stores.
- **Absent or unparseable falls back to the reported window**, i.e. exactly the old
  behaviour. Failing closed instead would re-mint on every read for any broker that omits
  the field.

`BrokeredCredential.expiresAt` shipped in api **1.0.74**, verified in the released jar - the
same version `BrokeredCredentialBridge` already requires for `BrokeredCredentialProvider`. So it
is read straight, with no `runCatching`: on any host that can load that class the field exists,
and a guard there would be dead code implying a risk that cannot occur. Add it to the
"Linkage containment" list above if that file ever gains a newer symbol.

**The cap alone was not enough, and that is the part worth remembering.**
`ProviderCredentialStore.brokeredCache` is not what hands a token to a consumer.
`LlmProviderSettingsApiImpl.activeConfig` reads `state.connections`, which
`ensureConnectionsLoaded` fills exactly once (`compareAndSet(false, true)`), and nothing else
calls `loadAll` between a panel visit and a secret edit. So the cap could shorten a window that
nobody ever re-read: the eleven-minute wedge would have reproduced with the cap in place, and
the store-level tests would still have passed, because they call `loadAll` directly.

`configFor` therefore calls `refreshLapsedBrokeredCredential`, which asks
`brokeredCredentialLapsed` and kicks an async `reloadConnections`. Four things to keep:

- **The hook is in `configFor`, not `activeConfig`.** Both api methods funnel through it, and
  hooking only `activeConfig` left `configuredProviders` handing out the same dead token - the
  identical wedge one method over.
- **That call still returns the stale token**; the next one is fresh. `activeConfig` cannot
  suspend, so the alternative was blocking a non-suspending api on a network mint. One failed
  request beats a wedge lasting the whole window.
- **An in-flight flag** collapses a burst of reads into one reload, and a **minimum interval**
  (`minBrokeredRefreshIntervalMs`, 5s) bounds the rate. Both are needed: the flag alone leaves a
  collapsed window driving refreshes back-to-back for as long as a consumer polls, each one a
  full `loadAll()` with its paginated secret scan. The first refresh is never blocked, so a
  genuinely lapsed credential does not wait out the interval.
- **A failed mint is retried, not terminal.** A failure is deliberately never cached, and calling
  "nothing cached" *not lapsed* made one network blip permanent on this path: nothing calls
  `loadAll` again, so the provider stayed unconfigured until the panel was opened.
  `lastMintFailureMs` gives bounded retry (`mintRetryBackoffMs`, 15s). It cannot be expressed as
  a blank-token cache entry - `resolveBrokered` would serve that while the deadline was ahead.

**The interval and the backoff are constructor parameters, not constants.** Hard-coded, every
test of them had to outwait them or be written around them, which is how an untested guard ends
up wrong - and both of these *were* wrong first time.

**The duration guards use `nanoTime`, the expiry cap uses wall time.** The interval floor and the
retry backoff measure *elapsed* time, so a wall clock stepping backwards (VM resume, first NTP
sync, a manual change) would make the difference negative and disable them for the length of the
step - the same symptom as the wedge, from a different cause. The cap has to stay on
`currentTimeMillis`, because it compares against an absolute timestamp the broker sent.

**`reloadConnections` has a generation guard, and it is not covered by a test.** It mirrors the
store's own: capture `invalidations.value`, skip the `_state.update` if it changed, so a refresh in
flight when the user signs out cannot seat a pre-invalidate snapshot in front of every consumer.
The obvious test for it passes either way, because the store's guard already refuses to seat a
*minted* pre-invalidate token - `mintBrokered` returns blank and the provider reads as
unconfigured. The remaining window needs a **cache hit** (not a mint) inside a reload slow enough
to straddle the invalidate, which the current harness cannot arrange. Kept because it is cheap and
mirrors a documented pattern; recorded here because it is unproven rather than proven.

**Clock skew is asymmetric.** The cap compares `expiry - 30s` against the *local* clock, so a
machine behind the broker under-caps (harmless) and one ahead over-caps into exactly the
refresh loop the floor now bounds. The log line names the reported and effective windows so that
case is greppable rather than mysterious.

The refresh runs on `Dispatchers.IO` and wraps `reloadConnections` in `runCatching`. Both matter
now that it can fire from any consumer read rather than only panel entry: `pluginScope` falls back
to `Dispatchers.Main`, and a host `exchange`/`listSecrets` that throws instead of returning a
failed `Result` would escape and cancel a scope that is not a supervisor - silently killing every
later launch in the plugin. The in-flight flag is cleared from `invokeOnCompletion`, not a
`finally`, so a body that never runs on an already-cancelled scope cannot latch it true.

`BrokeredReadPathTest` covers this by driving the api rather than `loadAll`, and uses
`runBlocking` with a real scope rather than `runTest`: the load ends up off the test dispatcher
(the store has no `withContext` of its own - it relies on the host's suspend functions
dispatching), so `advanceUntilIdle()` returns without waiting and every assertion reads an empty
snapshot. It waits
on `connectionsLoaded` and on the mint count instead of sleeping.

Two traps that suite has already fallen into, both caught by mutation:

- **Capturing a mint-count baseline while a refresh is in flight.** The helper reads the api
  twice and the second read can itself trigger a refresh, so the in-flight guard then swallowed
  the read under test. It settles the count first.
- **Firing rapid reads to test the interval floor.** The in-flight guard alone collapses those
  into one mint, so the test passed with the floor removed. The reads have to be *spaced* for the
  floor to be the thing under test.

Still open, and not this change's job: **nothing invalidates on a 401.** A credential that dies
earlier than it claimed - revoked, or a gateway that miscomputes - still wedges until the (now
shorter) window lapses. The durable fix is re-minting once on an auth failure, which needs a way
for the gateway plugin to signal "this credential is dead".

### `ProviderRegistry.fixedModels` is not a return to hardcoded catalogues

The gateway serves one model to one scoped key, so there is no models endpoint and nothing
for a live fetch to correct. `fixedModels` covers exactly that case, and two tests fence
it: every endpoint-less provider must be either `CUSTOM` (the user types the id) or have a
fixed list, and nothing with an endpoint may have a fixed list. Without the second, this
becomes the drifting hardcoded list `ModelCatalogClient` replaced.

### Keyless providers: Ollama, and the four rules it needs

`ProviderDescriptor.requiresApiKey` is about **the wire**: false means the provider takes no
credential on a request, which is true of a local Ollama daemon and of nothing else here. It is
deliberately not a general "is this provider usable" flag, and four separate rules hang off that
distinction — each of which was got wrong once:

- **The hardware gate is not `requiresApiKey`.** A machine below Ollama's own published floor
  (`MIN_USABLE_RAM_GB`, 8 GB, from its README) can run no model at all, so `ProviderDetail`
  replaces the whole card with an explanation and `AddProviderRow` stops offering it. That check
  is Ollama-specific *on purpose*: `requiresApiKey` is a protocol fact, this is a hardware fact,
  and folding them together would mean any future keyless provider inherited a RAM threshold that
  has nothing to do with it.
- **Listing is not `isConfigured`.** `ProviderConnection.isConfigured` is unconditionally true for
  a keyless provider — there is no credential to wait on — so listing on it would put Ollama in
  every user's provider list from first launch, whether or not they had ever run it. The rule
  (`isProviderListed`) is instead "its catalog actually loaded", i.e. the daemon answered, **or**
  the user explicitly added it this session (`addedProviderIds`). The second half is not optional:
  without it a user with no daemon picks Ollama from Add provider, gets the card and the Install
  button, closes it, and the row silently vanishes — which is precisely the user the flow exists
  for. Session-scoped, not persisted: on the next launch "is the daemon answering" is the honest
  rule again.
- **The catalog fetch is gated on the binary.** A keyless provider is always `isConfigured`, so
  `refreshStale` would reach `http://localhost:11434` for *every* user, on every panel entry and
  on every store invalidation (which the secrets list triggers on any create/update/delete). It
  fails fast and the `Failed` state is correctly hidden, but "the binary is not on this machine"
  answers the same question for free. Only a *probed* absence skips it — `ollamaSystemInfo` is
  null until then, and `load()` awaits the probe for exactly this reason.
- **The key dialog must not offer it.** `ProviderRegistry.userKeyed` (`requiresApiKey &&
  brokerId == null`) is what the secrets section's "Add AI provider key" dialog iterates. Over
  `all`, that dialog offered Ollama — writing an `OLLAMA_API_KEY` into the vault that nothing
  ever reads, over a plain-`http` endpoint with a bearer transport — and `RISA_GLM`, which is the
  one place the "a brokered credential is never written to disk" rule was still reachable.

`OllamaSystemInfo` is **null until probed**, not `binaryFound = false`. Same reason
`CliEngineHealth` has an `Unknown` and `gatewayNotice` starts at `NONE`: the probe is async, and
"Ollama doesn't appear to be installed on this machine" rendered in the frame before it lands is
a claim, not a wait — to a user who does have it. Everything reading the field tests `== false`
rather than negating, so an unprobed machine is never blocked or accused.

### `/api/pull` is Ollama's own API, and success is the positive rule

`OllamaModelInstaller` pulls a model by calling `POST /api/pull` directly rather than printing an
`ollama pull` command for the user to run. That endpoint is Ollama's **native** API, not the
OpenAI-compatible one `WireFormat` speaks — pulling is a management operation with no OpenAI
equivalent — which is why it has its own fixed base URL rather than deriving one from the
descriptor's `chatEndpoint`.

**A pull succeeded iff the stream's terminal line is `{"status":"success"}`.** Do not go back to
scanning for an error status: two real failures carry no error status at all, and both end with
the ViewModel persisting a `selectedModelId` for a model that is not on disk — which `configFor`
then hands to every other plugin as `LlmConfig.modelId`.

- A **mid-stream failure** is its own object with no `status` key (`{"error":"pull model
  manifest: file does not exist"}`), arriving after the HTTP 200, so neither the status code nor
  a `status` scan can see it. That `error` field is the message the user can act on.
- A **truncated stream** — daemon killed, disk full, laptop asleep — ends on an ordinary
  `downloading` record.

Mutation-verified: restoring the `contains("error")` rule fails three installer cases and the
ViewModel's *a pull that fails mid-stream does not select a model that is not there*.

`RAM_TIERS` is the one hardcoded model list in this plugin and a deliberate exception to the rule
`ModelCatalogClient` exists to enforce. It is a *suggestion shortlist for a machine that has
pulled nothing yet* — there is no endpoint to ask, because the honest answer from an empty daemon
is an empty list — and the live picker stays the only authority on what is installed. The tags
will drift; that file is the only place to change them.

### The editor card is state, and this ViewModel outlives every visit

`isEditorOpen` is separate from `selectedProviderId`, and only one of them survives a reload.
Remembering which provider you were looking at is useful; reopening a transient form nobody asked
for this time is not — so `enterSection()` closes the old editor and keeps the selection. A validated, one-shot
`requestProviderOnEntry()` command is the exception: it opens that provider on the next entry.
The shared panel owns this path, including environment refresh, legacy import and a local Ollama
probe before vault loading; the compatibility host wrapper must not start a second load.

This matters because the ViewModel is the plugin's **single instance**, shared between the sidebar
AI tab, consumer APIs and older hosts' compatibility settings (see "Three sections, and the AI one is not owned
by the panel"). A per-panel ViewModel would reset the flag for free by being reconstructed; this
one carries whatever the last visit left, to both surfaces. `ProviderRow`'s
`isSelected = state.isEditorOpen && …` guard depends on the reset too — without it the stale
selection and the stale open flag survive together and the guard cannot do what it says.

`closeEditor` also drops the provider's `keyDrafts` entry. That is what Cancel implies, and it is
what the rest of this plugin's handling of plaintext requires: a pasted-but-unsaved key would
otherwise sit in a process-lifetime ViewModel *and* reappear in the other surface's field.

Two new classes landed here (`OllamaSystemCheck`, `OllamaModelInstaller`). Neither holds a
`ComponentLogger`, so `buildPluginJar`'s `javap` guard passes — but that is a fact to re-check,
not to assume, whenever a class is added to this package.

### Out-of-process caveat

`plugin.json` declares `isolationMode: out-of-process`, which only engages under
`BOSS_MODE=KERNEL`. In-process (the default) the `@Composable` panel renders
directly. Under KERNEL mode the API crosses a process boundary and the panel is not
expected to render - the host falls back to its "plugin isn't loaded yet" notice
rather than failing.

### Tests

`./gradlew test` - host-independent cases, no live credential needed, run on every
pull request by `.github/workflows/test.yml`. The
model-list parsers are the point: each was written from a provider's published
reference, and xAI's and Together's envelopes aren't documented at all, so
`ModelCatalogClientParseTest` pins the captured shapes (Google's `models/` prefix
stripping, Together's type filter, Anthropic's capability tree, and that a rejected key
never reaches an error message). Also covered: `env_vars` parsing (`=` inside values),
`ProviderSettings` round-trip and tolerance, the catalog TTL / cache-seeding rules, and the
preference file's read-modify-write (one file holds the active provider *and* every model
selection, and for env-keyed providers it is the only record of that choice).

`ProviderCredentialStore` is covered through a fake `SecretDataProvider`, because its
invariants *are* the security story: env-then-stored-then-none precedence, refusing to write
an env-supplied key back to disk, updating rather than duplicating a provider entry, paging
past the first page, and the cache honouring `invalidate()`. `ModelCatalogClientPagingTest`
uses a response *queue* rather than one fixed body, which is what makes cursor-following, the
`MAX_PAGES` bound and the xAI primary-then-fallback path reachable at all.

**Every `AiProvidersViewModel` a test builds must be handed `noOllamaOnThisMachine()`.** Panel entry and
consumer catalog discovery probe the machine, so the default `OllamaSystemCheck()` reads the real `PATH`, the
real `user.home` and the real JMX bean - which quietly falsifies `envIn`'s "every source of
variables is injected" and makes the result depend on whether the machine running the suite
happens to have Ollama installed. `OllamaSystemCheckTest` has the same rule for
`physicalMemoryBytes`. The one case that genuinely needs the real predicate -
"a directory carrying the x bit is not mistaken for the binary" - asserts on
`OllamaSystemCheck.isRunnableBinary` directly rather than through `current()`, because the
candidate list includes absolute paths like `/opt/homebrew/bin` that no injection reaches.

**A test that races the ViewModel's own `init` is a test that eventually fails a release.**
`anUnprobedEngineReadsAsCheckingRatherThanMissing` constructed the ViewModel and read
`state.value` on the next line, racing `init`'s `refreshCliEngines()` launch on
`Dispatchers.Default`. It passed for as long as the test thread happened to win - then adding one
more call to `init` widened the window, CI lost the race, and it failed **after** the merge, in the
Release workflow, where the version had already been bumped. Measured rather than guessed: the old
form fails about 2 runs in 20 locally, the gated form 0 in 20.

The fix is a fake whose `health()` awaits a `CompletableDeferred` the test controls, so "the probe
has not answered yet" is a state the test is *in* rather than a window it has to hit. It then
releases the gate and asserts the row updates - without that second half the test would pass
against a ViewModel that never probed at all. Any new test here that asserts on a value `init`
fills in asynchronously needs the same treatment; `loadedEngines()` exists for the other direction.

`load()` marks `isLoading` **before** the launch, not inside it, so `load(); state.first { !it.isLoading }`
is a deterministic wait rather than a race a test has to win. `AiProvidersPanelStateTest` depends
on that; so does any future test of a `load()`-driven transition.

Two suites were validated against deliberate mutations, because a test that passes
unconditionally is indistinguishable from no test:

- dropping the `after_id` parameter fails the paging suite;
- removing the write-path cache-version check fails
  `a rejected cache version is not laundered back in by the write path`.

`buildPluginJar` asserts the packaged `plugin.json` declares the Gradle version. Do not replace
that with a count of `plugin.json` entries: `duplicatesStrategy = EXCLUDE` means the jar always
holds exactly one, so counting can never fail (verified - the count check was tried first and
did nothing). Asserting the content is what catches a `from` reorder.

`seedFromCache` fills an **absence** - `if (seeded.containsKey(providerId)) return@forEach`.
Do not go back to enumerating states to skip: skipping only `Loaded` papered over a rejected
key (a 401 replaced by a within-TTL cached list, which `isStale` then called fresh), and adding
`Failed` still left `NotConfigured` - which `clearKey` sets precisely to drop a stale list.

Relatedly, `refresh` must carry `lastKnown` through a *chain* of failures
(`Failed -> current.lastKnown`), not just the first. `as? Loaded` only ever worked because
seeding used to convert `Failed` back to `Loaded`; once `Failed` survived, a second consecutive
failure emptied the picker for an offline user who merely reopened the section. Mutation-checked.

A third env case: reverting the `EnvResolver` stubbing in `ProviderCredentialStoreTest` fails 9 tests
*only if* provider variables are exported. `EnvResolver` consults the process environment and
system properties **before** the `env_vars` file, and these tests must use the registry's real
variable names, so they are hermetic only because all three sources are injected. CI never
caught this because CI exports none of them - run the suite with `OPENAI_API_KEY` set if you
touch it.

The cache-laundering test only got teeth after a correction worth remembering: the first version of that
test seeded the stale entry under the *same* provider it then refreshed, so the merge
replaced it either way and the test passed against the bug. The laundering only shows up on a
*bystander* provider's entry. If you extend these, re-run the mutation.

The api jar path is resolved by picking the newest `boss-plugin-api-*.jar` in the sibling
checkout rather than naming a version, and CI tracks `latest` - the api is additive-only, so
a pin would just mean hand-bumping this repo on every api release.

Two test-only dependencies exist because the api is `compileOnly`: the api jar itself,
and an slf4j backend - `BossLogger` binds slf4j at class-init, so without one every
class holding a logger fails with `NoClassDefFoundError`.

## Code Quality

- Use Compose Multiplatform APIs (not Android-specific)
- All Kotlin files must end with a newline
- Handle null providers gracefully - show fallback UI, never crash

## CI/CD

Pushes to `main` trigger the release workflow which:
1. Builds the plugin JAR
2. Creates a GitHub release
3. Publishes to the BOSS Plugin Store

The workflow is defined in `.github/workflows/build.yml` and delegates to the shared workflow in `risa-labs-inc/BossConsole-Releases`.

## Brokered credentials renew themselves

A brokered key (RISA GLM) is short-lived by design, so it expiring is **routine**, not an error the
user should be asked to fix. They have access already; minting a replacement is this plugin's job.

The read path noticing a lapsed credential (`refreshLapsedBrokeredCredential`) is a *backstop*, not
the mechanism: it is asynchronous, so the read that noticed still returns the dead token and the
user gets one rejected request reading "The provider rejected the credential. Check Settings, AI
Providers" - which sends them somewhere there is nothing to do. `scheduleBrokeredRenewal` arms a
timer at `reuseUntil - BROKERED_RENEWAL_LEAD_MS` and each renewal arms the next, so nothing asks for
a key that has expired.

**A renewal must drop the cache first.** The first version called `reloadConnections()` and renewed
nothing: `resolveBrokered` serves the cached token while its reuse window is open, so a reload
*before* the deadline hands back the same credential. That made it a poller for lapse rather than a
renewal - and the test that caught it only did so because its key had ten minutes of life left. With
a short-lived key, a load-path re-mint satisfies the assertion and the test passes with the whole
feature removed. `expireBrokeredCache()` is narrower than `invalidate()` on purpose: it says "this
key is old", not "the session changed".

The armed delay has a floor (`MIN_BROKERED_RENEWAL_DELAY_MS`). A key whose reuse window has already
collapsed puts the deadline in the past, and without the floor the loop re-mints as fast as the
broker answers.

Both timings are constructor parameters for the same reason `minBrokeredRefreshIntervalMs` is: a
test that waits two minutes is a test nobody runs.
