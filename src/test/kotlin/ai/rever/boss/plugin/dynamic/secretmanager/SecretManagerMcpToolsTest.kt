package ai.rever.boss.plugin.dynamic.secretmanager

import ai.rever.boss.plugin.api.CreateSecretRequestData
import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.PaginatedSecretsData
import ai.rever.boss.plugin.api.PaginatedSecretsWithSharingData
import ai.rever.boss.plugin.api.QueryFilter
import ai.rever.boss.plugin.api.QueryRange
import ai.rever.boss.plugin.api.SecretDataProvider
import ai.rever.boss.plugin.api.SecretEntryData
import ai.rever.boss.plugin.api.SecretEntryWithSharingData
import ai.rever.boss.plugin.api.SecretShareData
import ai.rever.boss.plugin.api.ShareSecretRequestData
import ai.rever.boss.plugin.api.SupabaseDataProvider
import ai.rever.boss.plugin.api.UnshareSecretRequestData
import ai.rever.boss.plugin.api.UpdateSecretRequestData
import ai.rever.boss.plugin.dynamic.secretmanager.ai.EnvResolver
import ai.rever.boss.plugin.dynamic.secretmanager.ai.ProviderCredentialStore
import ai.rever.boss.plugin.dynamic.secretmanager.ai.SharedProviderDefinition
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Covers the MCP write tools' effect on the AI provider cache.
 *
 * These existed with no tests at all, which is how a miswired constructor shipped: the
 * provider took an `aiProviderStore` with a `= null` default, the sole call site was never
 * updated, and both `invalidate()` calls were unreachable in the released jar while the
 * change looked done. Behaviour is asserted here; the parameter is non-null so the wiring
 * itself cannot regress without a compile error.
 */
class SecretManagerMcpToolsTest {
    private fun storeWith(entries: List<SecretEntryData>): Pair<ProviderCredentialStore, FakeSecrets> {
        val provider = FakeSecrets(entries)
        val dir: File = Files.createTempDirectory("mcp-env").toFile()
        File(dir, "env_vars").writeText("")
        val resolver =
            EnvResolver(
                bossRootDir = dir,
                processEnv = { null },
                systemProperty = { null },
                useLaunchctl = false,
            )
        return ProviderCredentialStore(provider, resolver) to provider
    }

    private fun tool(
        store: ProviderCredentialStore,
        secrets: SecretDataProvider,
        name: String,
    ) = SecretManagerMcpToolProvider("test", secrets, FakeSupabase, store)
        .tools()
        .single { it.name == name }

    @Test
    fun `secret_delete invalidates the provider cache`() =
        runTest {
            // An agent deleting an ai-provider entry must not leave activeConfig() serving the
            // revoked credential for the rest of the session.
            val (store, secrets) = storeWith(listOf(aiProviderSecret("1", "OPENAI", "sk-live")))
            val before = store.invalidations.value

            val result =
                tool(store, secrets, "secret_delete")
                    .handler
                    .call(McpToolArgs(mapOf("id" to "1")))

            assertFalse(result.isError, result.text)
            assertEquals(listOf("1"), secrets.deleted)
            assertTrue(store.invalidations.value > before, "delete did not invalidate the cache")
        }

    @Test
    fun `secret_create invalidates the provider cache`() =
        runTest {
            // Invalidation is unconditional on a successful write. Note what this does NOT
            // prove: secret_create hardcodes tags = emptyList(), and loadStoredSecrets filters
            // on TAG_AI_PROVIDER, so an agent-created entry cannot be recognised as provider
            // configuration today — the invalidation here only costs a re-page. Kept because it
            // is the cheap, future-proof side, and it becomes load-bearing the moment
            // secret_create accepts tags. secret_delete is the half that matters now.
            val (store, secrets) = storeWith(emptyList())
            val before = store.invalidations.value

            val result =
                tool(store, secrets, "secret_create")
                    .handler
                    .call(
                        McpToolArgs(
                            mapOf(
                                "website" to "OPENAI",
                                "username" to "OPENAI_API_KEY",
                                "password" to "sk-new",
                            ),
                        ),
                    )

            assertFalse(result.isError, result.text)
            assertEquals(1, secrets.created.size)
            assertTrue(store.invalidations.value > before, "create did not invalidate the cache")
        }

    @Test
    fun `managed provider publish canonicalizes tags shares and invalidates`() =
        runTest {
            val entry = managedProviderSecret("managed-1")
            val (store, secrets) = storeWith(listOf(entry))
            val before = store.invalidations.value

            val result =
                tool(store, secrets, "managed_ai_provider_publish")
                    .handler
                    .call(McpToolArgs(mapOf("id" to entry.id, "target_role" to "user")))

            assertFalse(result.isError, result.text)
            val update = assertNotNull(secrets.updated.singleOrNull())
            assertTrue(update.tags.contains(SharedProviderDefinition.TAG))
            assertEquals(SharedProviderDefinition.INERT_PASSWORD, update.password)
            assertEquals(SharedProviderDefinition.bossAi(), SharedProviderDefinition.parse(update.notes))
            assertEquals("role-user", secrets.shared.single().targetRoleId)
            assertTrue(store.invalidations.value > before, "publish did not invalidate discovery")
        }

    @Test
    fun `managed provider publish refuses malformed or credential bearing secrets`() =
        runTest {
            val valid = managedProviderSecret("managed-1")
            for (entry in listOf(valid.copy(notes = "not-json"), valid.copy(password = "real-secret"))) {
                val (store, secrets) = storeWith(listOf(entry))
                val before = store.invalidations.value

                val result =
                    tool(store, secrets, "managed_ai_provider_publish")
                        .handler
                        .call(McpToolArgs(mapOf("id" to entry.id, "target_role" to "user")))

                assertTrue(result.isError)
                assertTrue(secrets.updated.isEmpty())
                assertTrue(secrets.shared.isEmpty())
                assertEquals(before, store.invalidations.value)
            }
        }

    @Test
    fun `a failed write does not invalidate`() =
        runTest {
            // Invalidation costs a full re-page of the secret store, so it should follow a
            // change that actually happened.
            val (store, secrets) = storeWith(emptyList())
            secrets.failWrites = true
            val before = store.invalidations.value

            val result =
                tool(store, secrets, "secret_delete")
                    .handler
                    .call(McpToolArgs(mapOf("id" to "1")))

            assertTrue(result.isError)
            assertEquals(before, store.invalidations.value, "invalidated after a failed write")
        }

    @Test
    fun `a missing argument is rejected before any write`() =
        runTest {
            val (store, secrets) = storeWith(emptyList())
            val before = store.invalidations.value

            val result =
                tool(store, secrets, "secret_delete")
                    .handler
                    .call(McpToolArgs(emptyMap()))

            assertTrue(result.isError)
            assertEquals(0, secrets.deleted.size)
            assertEquals(before, store.invalidations.value)
        }

    @Test
    fun `secret_get withholds an AI provider key`() =
        runTest {
            // secrets_list hands out ids and secret_get hands out plaintext, so ungated this is
            // two model-directed calls from a prompt-injected agent to every provider key.
            val (store, secrets) = storeWith(listOf(aiProviderSecret("1", "OPENAI", "sk-live-secret")))

            val result =
                tool(store, secrets, "secret_get")
                    .handler
                    .call(McpToolArgs(mapOf("id" to "1")))

            assertTrue(result.isError)
            assertFalse(result.text.contains("sk-live-secret"), "the key leaked: ${result.text}")
        }

    @Test
    fun `secret_get still returns an ordinary secret`() =
        runTest {
            // The gate must be scoped to provider keys — this tool's whole purpose otherwise.
            val ordinary =
                SecretEntryData(
                    id = "2",
                    website = "example.com",
                    username = "me",
                    password = "hunter2",
                    tags = listOf("personal"),
                    createdAt = "2026-01-01",
                    updatedAt = "2026-01-01",
                )
            val (store, secrets) = storeWith(listOf(ordinary))

            val result =
                tool(store, secrets, "secret_get")
                    .handler
                    .call(McpToolArgs(mapOf("id" to "2")))

            assertFalse(result.isError, result.text)
            assertTrue(result.text.contains("hunter2"))
        }

    @Test
    fun `my_secret_get withholds an AI provider key`() =
        runTest {
            // The sibling gate. This tool came from the retired user-secret-list plugin, where
            // it had no such check for three days: same vault, same secret.read gate, so it
            // read exactly the keys secret_get refuses. A gate on one tool and not the other
            // is no gate, which is why both now call one function.
            val (store, secrets) =
                storeWith(listOf(aiProviderSecret("1", "OPENAI", "sk-live-secret")))
            secrets.sharingEntries = listOf(sharedProviderKey("1", "OPENAI", "sk-live-secret"))

            val result =
                tool(store, secrets, "my_secret_get")
                    .handler
                    .call(McpToolArgs(mapOf("id" to "1")))

            assertTrue(result.isError)
            assertFalse(result.text.contains("sk-live-secret"), "the key leaked: ${result.text}")
        }

    @Test
    fun `my_secret_get returns a secret shared with me`() =
        runTest {
            // The gate must not cost this tool its purpose: reading something a colleague
            // shared, which is the one thing secret_get cannot see at all.
            val (store, secrets) = storeWith(emptyList())
            secrets.sharingEntries =
                listOf(
                    SecretEntryWithSharingData(
                        id = "9",
                        website = "stripe.com",
                        username = "ops",
                        password = "shared-pw",
                        tags = listOf("billing"),
                        createdAt = "2026-01-01",
                        updatedAt = "2026-01-01",
                        isOwner = false,
                        sharedByEmail = "anu@example.com",
                        accessLevel = "read",
                    ),
                )

            val result =
                tool(store, secrets, "my_secret_get")
                    .handler
                    .call(McpToolArgs(mapOf("id" to "9")))

            assertFalse(result.isError, result.text)
            assertTrue(result.text.contains("shared-pw"))
            assertTrue(result.text.contains("shared(read)"), result.text)
        }

    @Test
    fun `my_secrets_list reports how each secret was reached`() =
        runTest {
            val (store, secrets) = storeWith(emptyList())
            secrets.sharingEntries =
                listOf(
                    sharingEntry("1", "mine.com", accessLevel = "owner", isOwner = true),
                    sharingEntry("2", "theirs.com", accessLevel = "read", isOwner = false),
                )

            val result =
                tool(store, secrets, "my_secrets_list")
                    .handler
                    .call(McpToolArgs(emptyMap()))

            assertFalse(result.isError, result.text)
            assertTrue(result.text.contains("[owner]"), result.text)
            assertTrue(result.text.contains("[shared(read)]"), result.text)
        }

    @Test
    fun `a colleague's organisation secret is not reported as shared`() =
        runTest {
            // The exact trap the panel's sections exist to avoid, on the surface a model reads.
            // Source 4 of get_user_secrets_with_shared returns is_owner = (s.user_id =
            // auth.uid()), so a colleague's org secret arrives with isOwner = false - and
            // labelling off that field told the agent somebody shared it.
            val (store, secrets) = storeWith(emptyList())
            secrets.sharingEntries =
                listOf(sharingEntry("1", "org-owned.com", accessLevel = "org", isOwner = false))

            val result =
                tool(store, secrets, "my_secrets_list")
                    .handler
                    .call(McpToolArgs(emptyMap()))

            assertFalse(result.isError, result.text)
            assertTrue(result.text.contains("[org]"), result.text)
            assertFalse(result.text.contains("shared("), "reported as shared: ${result.text}")
        }

    @Test
    fun `my_secrets_list caps the defaulted page`() =
        runTest {
            // An MCP result is re-read on every later request in the session, so this default
            // is a per-request cost. At the old default of 100 the tool measured ~4,080 tokens
            // against a real vault; the size is row count alone.
            val (store, secrets) = storeWith(emptyList())
            secrets.sharingEntries = vaultOf(50)

            val result =
                tool(store, secrets, "my_secrets_list")
                    .handler
                    .call(McpToolArgs(emptyMap()))

            assertFalse(result.isError, result.text)
            assertEquals(20, rowsIn(result.text), "defaulted page was not capped: ${result.text}")
        }

    @Test
    fun `my_secrets_list says so when the defaulted page truncates`() =
        runTest {
            // A silently capped list reads as "these are all my secrets", which is the one
            // wrong answer this tool must not give.
            val (store, secrets) = storeWith(emptyList())
            secrets.sharingEntries = vaultOf(50)

            val result =
                tool(store, secrets, "my_secrets_list")
                    .handler
                    .call(McpToolArgs(emptyMap()))

            assertFalse(result.isError, result.text)
            assertTrue(result.text.contains(MORE_EXIST), "truncation was not announced: ${result.text}")
        }

    @Test
    fun `an explicit limit is returned without a truncation notice`() =
        runTest {
            // The property that guarantees existing callers lost nothing: an explicit limit is
            // the caller's own cap, so it returns exactly what it returned before the default
            // changed - rows only, even though more entries exist beyond the page.
            val (store, secrets) = storeWith(emptyList())
            secrets.sharingEntries = vaultOf(50)

            val result =
                tool(store, secrets, "my_secrets_list")
                    .handler
                    .call(McpToolArgs(mapOf("limit" to 5)))

            assertFalse(result.isError, result.text)
            assertEquals(5, rowsIn(result.text), result.text)
            assertFalse(
                result.text.contains(MORE_EXIST),
                "a caller-chosen limit gained a notice it did not have before: ${result.text}",
            )
        }

    @Test
    fun `a vault that fits inside the default is returned whole and unannotated`() =
        runTest {
            // The notice is about truncation, not about defaulting: a vault under the cap has
            // nothing withheld, so telling the reader to look again would be noise.
            val (store, secrets) = storeWith(emptyList())
            secrets.sharingEntries = vaultOf(3)

            val result =
                tool(store, secrets, "my_secrets_list")
                    .handler
                    .call(McpToolArgs(emptyMap()))

            assertFalse(result.isError, result.text)
            assertEquals(3, rowsIn(result.text), result.text)
            assertFalse(result.text.contains(MORE_EXIST), "nothing was withheld: ${result.text}")
        }

    @Test
    fun `an explicit limit above the maximum is capped and says so`() =
        runTest {
            // The gap the `requested == null` gate left open. The schema says "max 500", so a
            // caller asking for 1000 is following this tool's own documentation - and it got
            // 500 rows with nothing said, which is "these are all my secrets" arriving through
            // the notice's front door.
            val (store, secrets) = storeWith(emptyList())
            secrets.sharingEntries = vaultOf(700)

            val result =
                tool(store, secrets, "my_secrets_list")
                    .handler
                    .call(McpToolArgs(mapOf("limit" to 1000)))

            assertFalse(result.isError, result.text)
            assertEquals(500, rowsIn(result.text), "the ceiling moved: ${result.text}")
            assertTrue(result.text.contains(MORE_EXIST), "a clamped page said nothing: ${result.text}")
            // The advice has to be one the caller can take: this one already asked for more
            // than the ceiling, so "pass a larger `limit`" would be an impossible next step,
            // and an impossible next step reads as "there is nothing more to get".
            assertFalse(result.text.contains(PASS_A_LARGER), result.text)
        }

    @Test
    fun `a limit below one is clamped up and still says more exist`() =
        runTest {
            // The same clamp at the other end. `limit: 0` is nonsense, but it returns one row,
            // and one row presented as the whole vault is the same wrong answer.
            val (store, secrets) = storeWith(emptyList())
            secrets.sharingEntries = vaultOf(50)

            val result =
                tool(store, secrets, "my_secrets_list")
                    .handler
                    .call(McpToolArgs(mapOf("limit" to 0)))

            assertFalse(result.isError, result.text)
            assertEquals(1, rowsIn(result.text), result.text)
            assertTrue(result.text.contains(MORE_EXIST), result.text)
        }

    @Test
    fun `a vault of exactly the default is announced as truncated`() =
        runTest {
            // The accepted false positive, pinned so it stays a decision. The host derives
            // hasMore from `size >= limit`, so a full page always reports more - including the
            // last one. Erring toward "look again" is the safe direction for a list of
            // secrets, and the alternative is decrypting the rest of the vault to count it.
            val (store, secrets) = storeWith(emptyList())
            secrets.hostHasMore = true
            secrets.sharingEntries = vaultOf(20)

            val result =
                tool(store, secrets, "my_secrets_list")
                    .handler
                    .call(McpToolArgs(emptyMap()))

            assertFalse(result.isError, result.text)
            assertEquals(20, rowsIn(result.text), result.text)
            assertTrue(result.text.contains(MORE_EXIST), result.text)
        }

    @Test
    fun `my_secret_get finds a secret past the first 500`() =
        runTest {
            // The bug. One `limit = 500` call answered "No secret with id X" for every id past
            // row 500 - stated as fact about a secret that was sitting right there.
            val (store, secrets) = storeWith(emptyList())
            secrets.sharingEntries = vaultWith(size = 700, deepIndex = 599, password = "deep-pw")

            val result =
                tool(store, secrets, "my_secret_get")
                    .handler
                    .call(McpToolArgs(mapOf("id" to "600")))

            assertFalse(result.isError, result.text)
            assertTrue(result.text.contains("deep-pw"), result.text)
        }

    @Test
    fun `a single-page vault still reports a genuinely absent id as absent`() =
        runTest {
            // The other half: refusing to claim absence must not become refusing to answer.
            // One page is one consistent snapshot with nothing to re-sort, so it supports the
            // flat statement - and this is the common case, which must keep a clean answer.
            val (store, secrets) = storeWith(emptyList())
            secrets.sharingEntries = vaultOf(20)

            val result =
                tool(store, secrets, "my_secret_get")
                    .handler
                    .call(McpToolArgs(mapOf("id" to "999")))

            assertTrue(result.isError)
            assertTrue(result.text.contains("No secret with id 999"), result.text)
            assertFalse(result.text.contains(NOT_SEARCHED), "hedged about a vault it fully read: ${result.text}")
        }

    @Test
    fun `my_secret_get does not claim absence beyond the walk bound`() =
        runTest {
            // The distinction the whole fix is for. Past the bound the tool has not looked, and
            // "I stopped looking" must not be delivered as "it does not exist" - an agent acts
            // on the second one (recreates the credential, reports it missing) and not on the
            // first. The id here exists; the tool simply never reaches it.
            val (store, secrets) = storeWith(emptyList())
            secrets.sharingEntries = vaultWith(size = 1500, deepIndex = 1399, password = "unreachable-pw")

            val result =
                tool(store, secrets, "my_secret_get")
                    .handler
                    .call(McpToolArgs(mapOf("id" to "1400")))

            assertTrue(result.isError)
            assertTrue(result.text.contains(NOT_SEARCHED), result.text)
            assertFalse(
                result.text.contains("No secret with id"),
                "reported a secret that exists as non-existent: ${result.text}",
            )
            assertFalse(result.text.contains("unreachable-pw"), result.text)
        }

    @Test
    fun `my_secret_get stops at the page holding the id`() =
        runTest {
            // The second defect, which no assertion on the returned text can see: every row
            // carries a decrypted password, so the old single call materialised 500 plaintexts
            // to read one. An early hit must cost one page, not the vault.
            val (store, secrets) = storeWith(emptyList())
            secrets.sharingEntries = vaultWith(size = 700, deepIndex = 2, password = "shallow-pw")

            val result =
                tool(store, secrets, "my_secret_get")
                    .handler
                    .call(McpToolArgs(mapOf("id" to "3")))

            assertFalse(result.isError, result.text)
            assertTrue(result.text.contains("shallow-pw"), result.text)
            assertEquals(1, secrets.sharingCalls.size, "walked past the page holding the id: ${secrets.sharingCalls}")
            // The row count is the plaintext count, so this is the assertion that pins the cost.
            assertTrue(
                secrets.sharingCalls.all { it.first <= 100 },
                "asked for a page big enough to decrypt the vault: ${secrets.sharingCalls}",
            )
        }

    @Test
    fun `my_secret_get reports a failed first read as a failure`() =
        runTest {
            // Not "No secret with id X". The fake could not fail a read until now, which is
            // exactly how this branch was free to rot: an auth or network failure delivered as
            // absence is a fact an agent acts on - it recreates the credential, or reports it
            // gone - and the `fold` here exists for no other reason.
            val (store, secrets) = storeWith(emptyList())
            secrets.sharingEntries = vaultOf(50)
            secrets.failSharingReadsAfter = 0

            val result =
                tool(store, secrets, "my_secret_get")
                    .handler
                    .call(McpToolArgs(mapOf("id" to "1")))

            assertTrue(result.isError)
            assertTrue(result.text.startsWith("Failed:"), result.text)
            assertFalse(
                result.text.contains("No secret with id"),
                "a failed read was reported as absence: ${result.text}",
            )
        }

    @Test
    fun `my_secret_get does not conclude absence when a later page fails`() =
        runTest {
            // The half no other test reaches: one page read, no match, then the connection
            // goes. A partially walked vault knows strictly less than an unwalked one, so it
            // must not answer with more confidence than the first-page failure does.
            val (store, secrets) = storeWith(emptyList())
            secrets.sharingEntries = vaultOf(120)
            secrets.failSharingReadsAfter = 1

            val result =
                tool(store, secrets, "my_secret_get")
                    .handler
                    .call(McpToolArgs(mapOf("id" to "100")))

            assertTrue(result.isError)
            assertEquals(2, secrets.sharingCalls.size, secrets.sharingCalls.toString())
            assertTrue(result.text.startsWith("Failed:"), result.text)
            assertFalse(
                result.text.contains("No secret with id"),
                "a partially walked vault claimed absence: ${result.text}",
            )
        }

    @Test
    fun `my_secret_get does not claim absence after a multi-page walk`() =
        runTest {
            // `get_user_secrets_with_shared` pages with `ORDER BY s.created_at DESC` and no
            // tiebreaker, over a column with no unique constraint that defaults to the
            // transaction clock - so secrets written together tie, and two reads are two
            // different sorts. A row can sit at the end of page 1 on one read and the start of
            // page 2 on the next and be returned by neither, which makes "I read all of it" no
            // proof of absence. Same denial the sealed type exists to prevent, one cause over.
            val (store, secrets) = storeWith(emptyList())
            secrets.sharingEntries = vaultOf(120)

            val result =
                tool(store, secrets, "my_secret_get")
                    .handler
                    .call(McpToolArgs(mapOf("id" to "999")))

            assertTrue(result.isError)
            assertFalse(
                result.text.contains("No secret with id"),
                "claimed absence across an unstable page boundary: ${result.text}",
            )
            assertTrue(result.text.contains(NOT_ABSENCE), result.text)
            assertTrue(result.text.contains("120"), "did not say how much it read: ${result.text}")
        }

    @Test
    fun `my_secret_get reports an empty vault as absent`() =
        runTest {
            // The degenerate single page: no second read, so nothing could be re-sorted past
            // the walk. Refusing to answer here would be hedging about the one case that is
            // genuinely certain.
            val (store, secrets) = storeWith(emptyList())

            val result =
                tool(store, secrets, "my_secret_get")
                    .handler
                    .call(McpToolArgs(mapOf("id" to "1")))

            assertTrue(result.isError)
            assertTrue(result.text.contains("No secret with id 1"), result.text)
            assertFalse(result.text.contains(NOT_ABSENCE), result.text)
        }

    @Test
    fun `a vault of exactly the walk bound is not reported as absent`() =
        runTest {
            // The bound's own edge, and reachable only under the host's hasMore rule: twenty
            // full pages, every one of them claiming more behind it, so the walk ends having
            // read 1000 rows and knowing nothing whatever about row 1001.
            val (store, secrets) = storeWith(emptyList())
            secrets.hostHasMore = true
            secrets.sharingEntries = vaultOf(1000)

            val result =
                tool(store, secrets, "my_secret_get")
                    .handler
                    .call(McpToolArgs(mapOf("id" to "9999")))

            assertTrue(result.isError)
            assertEquals(20, secrets.sharingCalls.size, secrets.sharingCalls.toString())
            assertTrue(result.text.contains(NOT_SEARCHED), result.text)
            assertFalse(result.text.contains("No secret with id"), result.text)
        }

    @Test
    fun `a vault ending on a page boundary stops at the empty page`() =
        runTest {
            // Under the host's rule the last full page still reports more, so the walk asks
            // once more and gets nothing back. It has to stop there - an empty page advances
            // the offset by zero, so a walk that carried on would spin - and having crossed a
            // page boundary to get there it still cannot call the id absent.
            val (store, secrets) = storeWith(emptyList())
            secrets.hostHasMore = true
            secrets.sharingEntries = vaultOf(50)

            val result =
                tool(store, secrets, "my_secret_get")
                    .handler
                    .call(McpToolArgs(mapOf("id" to "999")))

            assertTrue(result.isError)
            assertEquals(2, secrets.sharingCalls.size, secrets.sharingCalls.toString())
            assertFalse(result.text.contains("No secret with id"), result.text)
            assertTrue(result.text.contains(NOT_ABSENCE), result.text)
        }

    @Test
    fun `an empty page that still claims more does not conclude absence`() =
        runTest {
            // The branch no honest fake reaches: a host reporting more over no rows at all.
            // The walk must not spin on it, and must not call the vault read - the page it was
            // promised never arrived, so nothing was searched.
            val (store, secrets) = storeWith(emptyList())
            secrets.emptyPageClaimsMore = true

            val result =
                tool(store, secrets, "my_secret_get")
                    .handler
                    .call(McpToolArgs(mapOf("id" to "1")))

            assertTrue(result.isError)
            assertEquals(1, secrets.sharingCalls.size, secrets.sharingCalls.toString())
            assertFalse(result.text.contains("No secret with id"), result.text)
            assertTrue(result.text.contains(NOT_ABSENCE), result.text)
            // "Searched the first 0 secrets" describes a search that did not happen.
            assertFalse(result.text.contains("Searched the first 0"), result.text)
        }

    /** Rows carry tab-separated columns; the truncation notice does not. */
    private fun rowsIn(text: String): Int = text.lines().count { it.contains('\t') }

    /** [vaultOf] with one entry at [deepIndex] carrying a password nothing else has. */
    private fun vaultWith(
        size: Int,
        deepIndex: Int,
        password: String,
    ): List<SecretEntryWithSharingData> =
        vaultOf(size).mapIndexed { i, e -> if (i == deepIndex) e.copy(password = password) else e }

    private fun vaultOf(size: Int): List<SecretEntryWithSharingData> =
        (1..size).map { sharingEntry("$it", "site$it.com", accessLevel = "owner", isOwner = true) }

    private fun sharedProviderKey(
        id: String,
        providerId: String,
        password: String,
    ) = SecretEntryWithSharingData(
        id = id,
        website = providerId,
        username = "${providerId}_API_KEY",
        password = password,
        tags = listOf(ProviderCredentialStore.TAG_AI_PROVIDER, providerId),
        createdAt = "2026-01-01",
        updatedAt = "2026-01-01",
        isOwner = true,
        accessLevel = "owner",
    )

    private fun sharingEntry(
        id: String,
        website: String,
        accessLevel: String,
        isOwner: Boolean,
    ) = SecretEntryWithSharingData(
        id = id,
        website = website,
        username = "user",
        password = "pw",
        createdAt = "2026-01-01",
        updatedAt = "2026-01-01",
        isOwner = isOwner,
        accessLevel = accessLevel,
    )

    private fun aiProviderSecret(
        id: String,
        providerId: String,
        password: String,
    ) = SecretEntryData(
        id = id,
        website = providerId,
        username = "${providerId}_API_KEY",
        password = password,
        tags = listOf(ProviderCredentialStore.TAG_AI_PROVIDER, providerId),
        createdAt = "2026-01-01",
        updatedAt = "2026-01-01",
    )

    private fun managedProviderSecret(id: String) = SecretEntryData(
        id = id,
        website = SharedProviderDefinition.BOSS_AI_WEBSITE,
        username = SharedProviderDefinition.BOSS_AI_USERNAME,
        password = SharedProviderDefinition.INERT_PASSWORD,
        notes = SharedProviderDefinition.bossAi().canonicalNotes(),
        createdAt = "2026-01-01",
        updatedAt = "2026-01-01",
    )

    private companion object {
        val FakeSupabase =
            object : SupabaseDataProvider {
                override suspend fun select(
                    table: String,
                    columns: String,
                    filters: List<QueryFilter>,
                    range: QueryRange?,
                ): Result<String> = Result.success("""[{"id":"role-user","name":"user"}]""")

                override suspend fun rpc(function: String, parameters: String): Result<String> =
                    Result.failure(UnsupportedOperationException())
            }

        /**
         * The distinguishing half of the truncation notice. Deliberately a fragment rather
         * than the whole line: the tests are about whether the notice is present, not about
         * its wording, and pinning the full sentence would fail on a copy edit.
         */
        const val MORE_EXIST = "More secrets exist"

        /**
         * The distinguishing half of the past-the-bound answer, matched as a fragment for the
         * same reason as [MORE_EXIST]: what matters is that the tool hedged, not how it worded
         * it. It must never share wording with the flat "No secret with id X".
         */
        const val NOT_SEARCHED = "Searched the first"

        /**
         * The clause every non-denial answer ends on. `my_secret_get` has five outcomes and
         * only one of them - [Absent] - is a statement about existence; asserting this
         * fragment is how a test says "whatever it answered, it did not deny the id".
         */
        const val NOT_ABSENCE = "not a statement that the id does not exist"

        /**
         * The half of the truncation notice that is *advice*. A caller already over the
         * ceiling cannot act on it, so the notice must not offer it there.
         */
        const val PASS_A_LARGER = "pass a larger `limit`"
    }

    /** Records writes; only the members the tools touch do anything. */
    private class FakeSecrets(
        var entries: List<SecretEntryData>,
        /**
         * What `getUserSecretsWithSharingInfo` serves, separate from [entries] because the
         * two RPCs behind them return different sets: `get_user_secrets` is own + organisation
         * secrets, `get_user_secrets_with_shared` adds everything shared with the caller.
         */
        var sharingEntries: List<SecretEntryWithSharingData> = emptyList(),
        /**
         * Derive `hasMore` the way the host does - `size >= limit` - rather than from the true
         * total, which the host has never had.
         *
         * Off by default so the tests written before it keep the semantics they were written
         * against, but this is the honest rule (see the comment in `my_secrets_list` and
         * AGENTS.md), and it is the only way to reach two real cases: a page that is exactly
         * `limit` long and therefore claims more with nothing behind it, and the trailing
         * empty page a vault that is a multiple of the chunk produces.
         */
        var hostHasMore: Boolean = false,
        /**
         * Fail `getUserSecretsWithSharingInfo` from the read after this one onward; `null`
         * never fails. `0` fails the first read, `1` the second.
         *
         * `failWrites` only reaches `createSecret`/`deleteSecret`, so until this a read could
         * not fail at all - which is why `SharedLookup.Failed` could be turned into `Absent`
         * with the whole suite still green, on the one path this file exists to keep honest.
         */
        var failSharingReadsAfter: Int? = null,
        /**
         * Report `hasMore` on a page carrying no rows: the host pathology the walk's
         * empty-page branch guards against, and unreachable under either rule above.
         */
        var emptyPageClaimsMore: Boolean = false,
    ) : SecretDataProvider {
        val created = mutableListOf<CreateSecretRequestData>()
        val updated = mutableListOf<UpdateSecretRequestData>()
        val shared = mutableListOf<ShareSecretRequestData>()
        val deleted = mutableListOf<String>()
        var failWrites = false

        /**
         * Every `(limit, offset)` [getUserSecretsWithSharingInfo] was asked for, oldest first.
         *
         * Recorded because the cost this fix is about is invisible in the returned text: a
         * lookup that answers correctly while decrypting the whole vault to do it looks
         * identical to one that stopped at the first page.
         */
        val sharingCalls = mutableListOf<Pair<Int, Int>>()

        override suspend fun getUserSecrets(
            limit: Int,
            offset: Int,
        ): Result<PaginatedSecretsData> {
            val page = entries.drop(offset).take(limit)
            return Result.success(PaginatedSecretsData(page, hasMore = offset + page.size < entries.size))
        }

        override suspend fun createSecret(request: CreateSecretRequestData): Result<Unit> {
            if (failWrites) return Result.failure(IllegalStateException("write refused"))
            created += request
            return Result.success(Unit)
        }

        override suspend fun deleteSecret(id: String): Result<Unit> {
            if (failWrites) return Result.failure(IllegalStateException("write refused"))
            deleted += id
            entries = entries.filterNot { it.id == id }
            return Result.success(Unit)
        }

        override suspend fun updateSecret(request: UpdateSecretRequestData): Result<Unit> {
            if (failWrites) return Result.failure(IllegalStateException("write refused"))
            updated += request
            return Result.success(Unit)
        }

        override suspend fun getUserSecretsWithSharingInfo(
            limit: Int,
            offset: Int,
        ): Result<PaginatedSecretsWithSharingData> {
            sharingCalls += limit to offset
            failSharingReadsAfter?.let { after ->
                if (sharingCalls.size > after) return Result.failure(IllegalStateException("read refused"))
            }
            val page = sharingEntries.drop(offset).take(limit)
            val hasMore =
                when {
                    page.isEmpty() && emptyPageClaimsMore -> true
                    hostHasMore -> page.size >= limit
                    else -> offset + page.size < sharingEntries.size
                }
            return Result.success(PaginatedSecretsWithSharingData(page, hasMore = hasMore))
        }

        override suspend fun searchSecrets(
            query: String,
            limit: Int,
            offset: Int,
        ): Result<PaginatedSecretsData> = Result.failure(UnsupportedOperationException())

        override suspend fun getSecretShares(secretId: String): Result<List<SecretShareData>> =
            Result.failure(UnsupportedOperationException())

        override suspend fun shareSecret(request: ShareSecretRequestData): Result<Unit> {
            if (failWrites) return Result.failure(IllegalStateException("write refused"))
            shared += request
            return Result.success(Unit)
        }

        override suspend fun unshareSecret(request: UnshareSecretRequestData): Result<Unit> =
            Result.failure(UnsupportedOperationException())
    }
}
