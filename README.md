# BOSS Secret Manager

Encrypted credentials, the secrets other people share with you, Plugin Store publish keys, and
every AI provider setting in BOSS.

A right-hand sidebar panel over the host's `SecretDataProvider`. Its three sections are
**Secrets**, **Shared with me**, and **AI**. This plugin owns all AI provider configuration for
the application. The host has none of its own, and `PluginContext.llmProvider` is relayed from
the instance registered here.

This repository is a fork of [risa-labs-inc/boss-plugin-secret-manager](https://github.com/risa-labs-inc/boss-plugin-secret-manager).

It is also the *only* secrets plugin: the separate **My Secrets** (`user-secret-list`) panel
is retired, and its list is this panel's "Shared with me" section. See
[Two sections, not two panels](#two-sections-not-two-panels).

## What it does

- **Secret CRUD**: website, username, password, notes, tags, expiry, 2FA type and secret, and
  recovery codes. Paginated at 50 per page with server-side search plus a client filter,
  copy-to-clipboard, and a per-secret reveal toggle.
- **Shared with me**: a read-only second section listing what other people have shared with
  you, by user, role or organisation, with the access level and who shared it. No edit, delete
  or re-share control exists anywhere in that section.
- **Sharing**: share a secret with individual users (searched through Supabase) or with whole
  RBAC roles, each at an access level, and unshare again.
- **AI**: a third section holding every AI credential in BOSS - local CLI sessions (a `claude` or
  `codex` login you already have), provider API keys, managed BOSS AI access, shared managed
  providers, Ollama, and a model picker driven by each provider's live list. The keys live in this
  vault, so the page that manages them sits beside the secrets instead of in host Settings.
  When the AI Gateway plugin is missing the section says so and offers to install it, because
  without the gateway there are no CLI sessions and no common AI interface for other plugins.
- **Plugin Store publish keys**: create, list and revoke them, with a `publish` scope checkbox. The
  key is shown once at creation and never again.
- **AI providers**: Anthropic, OpenAI, Google Gemini, xAI Grok, Moonshot (Kimi), Together AI,
  and any custom OpenAI-compatible endpoint.
- **Model lists are fetched live** from each provider's own models endpoint and cached for six
  hours. There is deliberately no bundled fallback list: a provider with no credential reports
  "not configured" rather than guessing from a hardcoded list that drifts out of date.

Credential precedence is environment, then stored, then none. Environment values resolve from
the process environment, system properties, macOS `launchctl`, and finally `~/.boss/env_vars`.
**A key supplied by the environment is never written back to disk.**

## Two secret sections, not two panels

The first two sections partition the vault rather than overlap it:

| Section | Reads | Rows | Controls |
|---|---|---|---|
| **Secrets** | `getUserSecrets` | your own plus your organisation's | full CRUD, sharing, API keys, AI provider keys |
| **Shared with me** | `getUserSecretsWithSharingInfo` | only what was shared with you | none - read-only |

Those were two separate plugins, `secret-manager` and `user-secret-list`, and **both listed
your own secrets** - the confusion this merge removes. `user-secret-list` was also the panel the
first-run wizard installed by default while this one was not, so the typical install had the
read-only half and not the half that can add a key.

**The partition key is `accessLevel`, not `isOwner`.** `get_user_secrets_with_shared` assigns it
per UNION source: `owner` for your own, `org` for an organisation's, and the share's own level
(`read` / `write`) for an actual share. Source 4 returns `is_owner = (s.user_id = auth.uid())`,
so **a colleague's organisation secret arrives with `isOwner = false`** while nobody shared it
with anyone - splitting on `isOwner` files it under "Shared with me" and tells the user someone
shared it with them. An unrecognised level counts as a share, so a new source added server-side
surfaces in the read-only section rather than in the one offering Edit and Delete.

**A copied credential is wiped from the clipboard after 45 seconds**, the same policy the
managed list applies. The panel this section came from had no wipe at all, and one panel holding
two clipboard policies for the same class of data would be an oversight rather than a decision.
The wipe deliberately outlives the panel: cancelling it on close would leave the credential
there indefinitely.

**The section loads lazily and may scan several pages.** A page is 50 entries of everything you
can read, filtered down to the shares, so someone with 60 of their own secrets and two shared
ones gets a first page that filters to nothing. An empty page auto-continues (capped at 5 pages
/ 250 rows, then a "Keep looking" control takes over), because a section reporting "nothing
shared with you" while the server still has some is a lie the user cannot tell from the truth.
The offset advances by the **raw** row count, not the filtered one.

Past that first load, paging is scroll-driven and only on a list that actually scrolls: a short
list of shares in a large vault gets a footer button instead of an invisible scan. Auto-prefetch
without that test turned one tab switch into hundreds of sequential requests.

## MCP tools

| Tool | Purpose |
|---|---|
| `secrets_list` | List secrets as metadata only (id, website, username) |
| `secret_search` | Search secrets by query, metadata only |
| `secret_get` | Reveal password, notes and 2FA for one secret id |
| `secret_create` | Create a secret |
| `secret_delete` | Delete a secret |
| `my_secrets_list` | Your own **and** shared-with-you secrets, with owner/access, metadata only |
| `my_secret_get` | Reveal one secret by id, including one shared with you |

The `my_*` pair came from the retired `user-secret-list` plugin and keeps its original names:
agents, prompts and skills already call them, and `getUserSecretsWithSharingInfo` is the only
call that reports *how* a secret reached you. `secrets_list` / `secret_get` still read only
what you can manage.

**`secret_get` and `my_secret_get` both refuse any secret tagged `ai-provider`.** A `*_list`
tool hands out ids and a `*_get` tool hands out the plaintext password, so without that gate a
prompt-injected agent is two tool calls away from every configured provider key. An agent that
needs to *use* a provider goes through `PluginContext.llmProvider` and never needs the raw
value.

Both call one function (`aiProviderRefusal`) rather than repeating the check, because repeating
it is exactly how `my_secret_get` shipped *without* it in the sibling plugin and read the keys
`secret_get` withheld. Any new tool here that returns a password calls it too.

## Permissions

Manifest `requiredPermissions` is `["secret.read"]`, and every MCP tool carries the same gate.

**`secret.read` is part of the baseline `user` role**, so this panel is available to every
authenticated user. It was admin-only until migration `20260809000000`, which is a correction
rather than a widening: the vault was always per-user server-side (every RPC is granted to
`authenticated` and self-scopes with `auth.uid()`, and all four RLS policies on `secrets` are
`auth.uid() = user_id`). The old gate was inherited from the pre-RBAC `requiresAdmin` flag, and
since AI provider settings moved into this plugin it also meant no non-admin could add a model
API key at all. The permission is still revocable, so a locked-down deployment removes it from
`user`.

Writes are intentionally gated on `secret.read` rather than granular `secrets.create` /
`secrets.delete`: those are not seeded in the RBAC catalog, so gating on them would silently
make writes admin-only. Server-side RLS scopes every RPC to `auth.uid()` regardless.

Two surfaces inside the panel carry their own gate, because they reach past the signed-in user:

| Surface | Permission | Held by |
|---|---|---|
| Share with a role (the share dialog's Roles tab) | `secret.share.role` | admin, boss_admin |
| Plugin Store publish keys | `api_key.create` | admin, boss_admin |

The role-share gate exists because a role share reaches every holder of that role, and `user`
is a descendant of every role - so a role target is the one control here that can publish a
credential deployment-wide, with no undo. `share_secret` refuses it server-side; hiding the tab
only keeps a control that cannot work off the screen. Sharing with an individual user is
ungated, and sharing with an organisation already requires membership of it.

## Requirements

- BOSS >= 9.4.2, boss-plugin-api >= 1.0.89
- The panel is visible to every authenticated user only on a host carrying migration
  `20260809000000`, which grants `secret.read` to the baseline `user` role. On an older
  host only admins and `boss_admin` can open it, and nothing else changes.
- `secretDataProvider` is required. Without it only a no-provider stub panel registers and no
  MCP tools are contributed.
- Optional: `supabaseDataProvider` (user and role search for sharing),
  `pluginStoreApiKeyProvider`, `settingsProvider`, `splitViewOperations`, `cacheProvider`.
- Network egress to each provider's models endpoint.
- The AI section is registered behind a `LinkageError` guard so a malformed host API installation
  cannot take down secret management. The declared 1.0.89 API floor covers the model-discovery
  types in its public interface.

## Build

```bash
./gradlew buildPluginJar
cp build/libs/boss-plugin-secret-manager-*.jar ~/.boss/plugins/
./gradlew test    # 302 host-independent cases, no live credential needed
```

Do not delete `compose-stability.conf`. It stops the Compose compiler emitting a `$stable` read
against `ai.rever.boss.plugin.logging`, which the host shadows at runtime. Without it the
plugin fails binary-compatibility validation and will not load at all - this shipped broken
twice, in 1.2.6 and 1.2.7.

See [AGENTS.md](AGENTS.md) for architecture and conventions.

## License

Proprietary - Risa Labs Inc.
