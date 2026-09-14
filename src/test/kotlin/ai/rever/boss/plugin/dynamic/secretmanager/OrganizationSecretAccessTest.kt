package ai.rever.boss.plugin.dynamic.secretmanager

import ai.rever.boss.plugin.api.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class OrganizationSecretAccessTest {
    @Test
    fun `load retains server organisation identity and management decision`() = runTest {
        val vm = viewModel(this, RecordingProvider(listOf(access(secret("s1"), canManage = true))))
        advanceUntilIdle()

        assertEquals(listOf("s1"), vm.state.secrets.map { it.id })
        assertEquals("acme", vm.accessFor("s1").orgSlug)
        assertEquals("org-1", vm.accessFor("s1").orgId)
        assertTrue(vm.accessFor("s1").isOrgOwned)
        assertTrue(vm.canManageSecret("s1"))
    }

    @Test
    fun `ordinary organisation member receives a readable but unmanageable row`() = runTest {
        val vm = viewModel(this, RecordingProvider(listOf(access(secret("s1"), canManage = false))))
        advanceUntilIdle()

        assertEquals("hunter2", vm.state.secrets.single().password)
        assertFalse(vm.canManageSecret("s1"))
    }

    @Test
    fun `missing access metadata fails closed`() = runTest {
        val vm = viewModel(this, LegacyProvider(secret("legacy")))
        advanceUntilIdle()

        assertEquals(listOf("legacy"), vm.state.secrets.map { it.id })
        assertFalse(vm.canManageSecret("legacy"))
        assertEquals(SecretAccessState.READ_ONLY, vm.accessFor("unknown"))
    }

    @Test
    fun `managed row may open every management dialog`() = runTest {
        val row = secret("s1")
        val vm = viewModel(this, RecordingProvider(listOf(access(row, canManage = true))))
        advanceUntilIdle()

        vm.showEditDialog(row)
        assertTrue(vm.state.showEditDialog)
        vm.hideEditDialog()
        vm.showDeleteDialog(row)
        assertTrue(vm.state.showDeleteDialog)
        vm.hideDeleteDialog()
        vm.showShareDialog(row)
        assertTrue(vm.state.showShareDialog)
    }

    @Test
    fun `unmanaged row cannot open a management dialog`() = runTest {
        val row = secret("s1")
        val vm = viewModel(this, RecordingProvider(listOf(access(row, canManage = false))))
        advanceUntilIdle()

        vm.showEditDialog(row)
        vm.showDeleteDialog(row)
        vm.showShareDialog(row)

        assertFalse(vm.state.showEditDialog)
        assertFalse(vm.state.showDeleteDialog)
        assertFalse(vm.state.showShareDialog)
        assertNull(vm.state.selectedSecret)
    }

    @Test
    fun `unmanaged row is rejected by every mutation entry point`() = runTest {
        val row = secret("s1")
        val provider = RecordingProvider(listOf(access(row, canManage = false)))
        val vm = viewModel(this, provider)
        advanceUntilIdle()

        vm.updateSecret(update("s1"))
        vm.deleteSecret("s1")
        vm.shareSecret(ShareSecretRequestData("s1", targetUserId = "u1"))
        vm.unshareSecret("s1", userId = "u1")
        advanceUntilIdle()

        assertTrue(provider.updates.isEmpty())
        assertTrue(provider.deletes.isEmpty())
        assertTrue(provider.sharesSent.isEmpty())
        assertTrue(provider.unshares.isEmpty())
        assertFalse(vm.state.isOperationInProgress)
    }

    @Test
    fun `managed row forwards every mutation entry point`() = runTest {
        val row = secret("s1")
        val provider = RecordingProvider(listOf(access(row, canManage = true)))
        val vm = viewModel(this, provider)
        advanceUntilIdle()

        vm.updateSecret(update("s1"))
        advanceUntilIdle()
        vm.shareSecret(ShareSecretRequestData("s1", targetUserId = "u1"))
        advanceUntilIdle()
        vm.unshareSecret("s1", userId = "u1")
        advanceUntilIdle()
        vm.deleteSecret("s1")
        advanceUntilIdle()

        assertEquals(1, provider.updates.size)
        assertEquals(1, provider.sharesSent.size)
        assertEquals(1, provider.unshares.size)
        assertEquals(listOf("s1"), provider.deletes)
    }

    @Test
    fun `search replaces a stale allow decision with the latest deny`() = runTest {
        val row = secret("s1")
        val provider = RecordingProvider(listOf(access(row, canManage = true)))
        val vm = viewModel(this, provider)
        advanceUntilIdle()
        assertTrue(vm.canManageSecret("s1"))

        provider.searchRows = listOf(access(row, canManage = false))
        vm.searchSecrets("github")
        advanceUntilIdle()

        assertFalse(vm.canManageSecret("s1"))
    }

    @Test
    fun `cancelled initial load cannot restore an older allow decision`() = runTest {
        val row = secret("s1")
        val provider = NonCooperativeInitialProvider(row)
        val vm = viewModel(this, provider)
        advanceUntilIdle()

        vm.searchSecrets("github")
        advanceUntilIdle()
        assertFalse(vm.canManageSecret("s1"), "newer search denial must land")

        provider.releaseInitial.complete(Unit)
        advanceUntilIdle()

        assertFalse(vm.canManageSecret("s1"), "cancelled allow response must be discarded")
    }

    @Test
    fun `authorization revocation closes an already open management dialog`() = runTest {
        val row = secret("s1")
        val provider = RecordingProvider(listOf(access(row, canManage = true)))
        val vm = viewModel(this, provider)
        advanceUntilIdle()
        vm.showEditDialog(row)
        assertTrue(vm.state.showEditDialog)

        provider.searchRows = listOf(access(row, canManage = false))
        vm.searchSecrets("github")
        advanceUntilIdle()

        assertFalse(vm.state.showEditDialog)
        assertNull(vm.state.selectedSecret)
    }

    @Test
    fun `failed refresh fails cached management access closed`() = runTest {
        val row = secret("s1")
        val provider = RecordingProvider(listOf(access(row, canManage = true)))
        val vm = viewModel(this, provider)
        advanceUntilIdle()
        vm.showEditDialog(row)

        provider.failReads = true
        vm.loadSecrets()
        advanceUntilIdle()

        assertFalse(vm.canManageSecret("s1"))
        assertFalse(vm.state.showEditDialog)
        assertEquals("offline", vm.state.errorMessage)
    }

    @Test
    fun `pagination merges access decisions for both pages`() = runTest {
        val rows = (1..51).map { index -> access(secret("s$index"), canManage = index != 51) }
        val vm = viewModel(this, RecordingProvider(rows))
        advanceUntilIdle()
        assertEquals(50, vm.state.secrets.size)

        vm.loadMoreSecrets()
        advanceUntilIdle()

        assertEquals(51, vm.state.secrets.size)
        assertTrue(vm.canManageSecret("s50"))
        assertFalse(vm.canManageSecret("s51"))
    }

    @Test
    fun `organisation share identity survives dialog loading`() = runTest {
        val row = secret("s1")
        val provider = RecordingProvider(listOf(access(row, canManage = true)))
        provider.shareRows = listOf(
            SecretShareWithTargetData(
                share = share("share-org"),
                sharedWithOrgId = "org-1",
                sharedWithOrgSlug = "acme",
            ),
        )
        val vm = viewModel(this, provider)
        advanceUntilIdle()

        vm.showShareDialog(row)
        advanceUntilIdle()

        assertEquals("acme", vm.state.secretShareTargets.getValue("share-org").orgSlug)
        assertEquals("share-org", vm.state.secretShares.single().shareId)
    }

    @Test
    fun `share response arriving after dialog close is discarded`() = runTest {
        val row = secret("s1")
        val provider = RecordingProvider(listOf(access(row, canManage = true)))
        provider.shareRows = listOf(
            SecretShareWithTargetData(share("share-org"), "org-1", "acme"),
        )
        provider.shareGate = CompletableDeferred()
        val vm = viewModel(this, provider)
        advanceUntilIdle()

        vm.showShareDialog(row)
        advanceUntilIdle()
        vm.hideShareDialog()
        provider.shareGate?.complete(Unit)
        advanceUntilIdle()

        assertTrue(vm.state.secretShares.isEmpty())
        assertTrue(vm.state.secretShareTargets.isEmpty())
        assertFalse(vm.state.isLoadingShares)
    }

    @Test
    fun `organisation share is labelled accurately and cannot submit an invalid revoke`() {
        val presentation = presentShareTarget(
            share("share-org"),
            SecretShareTargetState(orgId = "org-1", orgSlug = "acme"),
        )

        assertEquals(ShareTargetPresentation("acme", "Organization", false), presentation)
    }

    @Test
    fun `user role and unknown share targets have distinct safe presentations`() {
        val user = presentShareTarget(share("u", userId = "u1", userEmail = "u@example.com"), null)
        val role = presentShareTarget(share("r", roleId = "r1", roleName = "Admins"), null)
        val unknown = presentShareTarget(share("x"), null)

        assertEquals(ShareTargetPresentation("u@example.com", "User", true), user)
        assertEquals(ShareTargetPresentation("Admins", "Role", true), role)
        assertEquals(ShareTargetPresentation("Unknown target", "Unknown", false), unknown)
    }

    @Test
    fun `refresh during pagination clears the cancelled page spinner and permits more pages`() = runTest {
        val provider = RecordingProvider((1..51).map { access(secret("s$it"), canManage = true) })
        val vm = viewModel(this, provider)
        advanceUntilIdle()
        provider.pageGate = CompletableDeferred()
        vm.loadMoreSecrets()
        advanceUntilIdle()
        assertTrue(vm.state.isLoadingMore)

        vm.loadSecrets()
        advanceUntilIdle()
        assertFalse(vm.state.isLoadingMore)
        assertEquals(50, vm.state.secrets.size)

        provider.pageGate?.complete(Unit)
        vm.loadMoreSecrets()
        advanceUntilIdle()
        assertEquals(51, vm.state.secrets.size)
        assertFalse(vm.state.isLoadingMore)
    }

    @Test
    fun `old share mutation cannot invalidate the newly opened secret share request`() = runTest {
        val first = secret("s1")
        val second = secret("s2")
        val provider = RecordingProvider(listOf(access(first, true), access(second, true)))
        val vm = viewModel(this, provider)
        advanceUntilIdle()
        vm.showShareDialog(first)
        advanceUntilIdle()
        provider.mutationGate = CompletableDeferred()
        vm.shareSecret(ShareSecretRequestData(first.id, targetUserId = "u1"))
        advanceUntilIdle()
        vm.hideShareDialog()

        provider.shareRows = listOf(SecretShareWithTargetData(share("second-share"), "org-2", "second-org"))
        provider.shareGate = CompletableDeferred()
        vm.showShareDialog(second)
        advanceUntilIdle()
        provider.mutationGate?.complete(Unit)
        advanceUntilIdle()
        provider.shareGate?.complete(Unit)
        advanceUntilIdle()

        assertEquals(second.id, vm.state.selectedSecret?.id)
        assertEquals("second-share", vm.state.secretShares.single().shareId)
        assertFalse(vm.state.isLoadingShares)
    }

    private fun viewModel(
        scope: kotlinx.coroutines.CoroutineScope,
        provider: SecretDataProvider,
    ) = SecretManagerViewModel(
        secretDataProvider = provider,
        supabaseDataProvider = null,
        pluginStoreApiKeyProvider = null,
        scope = scope,
    ).also { it.initialize() }

    private class RecordingProvider(
        var rows: List<SecretEntryWithAccessData>,
    ) : SecretDataProvider {
        var searchRows: List<SecretEntryWithAccessData> = rows
        var shareRows: List<SecretShareWithTargetData> = emptyList()
        var shareGate: CompletableDeferred<Unit>? = null
        var pageGate: CompletableDeferred<Unit>? = null
        var mutationGate: CompletableDeferred<Unit>? = null
        var failReads: Boolean = false
        val updates = mutableListOf<UpdateSecretRequestData>()
        val deletes = mutableListOf<String>()
        val sharesSent = mutableListOf<ShareSecretRequestData>()
        val unshares = mutableListOf<UnshareSecretRequestData>()

        override suspend fun getUserSecrets(limit: Int, offset: Int): Result<PaginatedSecretsData> =
            page(rows, limit, offset).map { page -> PaginatedSecretsData(page.data.map { it.secret }, page.hasMore) }

        override suspend fun getUserSecretsWithAccess(limit: Int, offset: Int): Result<PaginatedSecretsWithAccessData> {
            if (offset > 0) pageGate?.await()
            return page(rows, limit, offset)
        }

        override suspend fun searchSecrets(query: String, limit: Int, offset: Int): Result<PaginatedSecretsData> =
            page(searchRows, limit, offset).map { page -> PaginatedSecretsData(page.data.map { it.secret }, page.hasMore) }

        override suspend fun searchSecretsWithAccess(query: String, limit: Int, offset: Int) =
            page(searchRows, limit, offset)

        override suspend fun getSecretShares(secretId: String): Result<List<SecretShareData>> =
            Result.success(shareRows.map { it.share })

        override suspend fun getSecretSharesWithTargets(secretId: String): Result<List<SecretShareWithTargetData>> {
            shareGate?.await()
            return Result.success(shareRows)
        }

        override suspend fun updateSecret(request: UpdateSecretRequestData): Result<Unit> {
            updates += request
            return Result.success(Unit)
        }

        override suspend fun deleteSecret(id: String): Result<Unit> {
            deletes += id
            rows = rows.filterNot { it.secret.id == id }
            return Result.success(Unit)
        }

        override suspend fun shareSecret(request: ShareSecretRequestData): Result<Unit> {
            sharesSent += request
            mutationGate?.await()
            return Result.success(Unit)
        }

        override suspend fun unshareSecret(request: UnshareSecretRequestData): Result<Unit> {
            unshares += request
            return Result.success(Unit)
        }

        override suspend fun getUserSecretsWithSharingInfo(limit: Int, offset: Int) =
            Result.failure<PaginatedSecretsWithSharingData>(UnsupportedOperationException())

        override suspend fun createSecret(request: CreateSecretRequestData) = Result.success(Unit)

        private fun page(source: List<SecretEntryWithAccessData>, limit: Int, offset: Int): Result<PaginatedSecretsWithAccessData> {
            if (failReads) return Result.failure(IllegalStateException("offline"))
            val values = source.drop(offset).take(limit)
            return Result.success(PaginatedSecretsWithAccessData(values, offset + values.size < source.size))
        }
    }

    /** Simulates a transport that catches cancellation and still returns its old response. */
    private class NonCooperativeInitialProvider(row: SecretEntryData) : SecretDataProvider by
        RecordingProvider(listOf(access(row, canManage = false))) {
        val releaseInitial = CompletableDeferred<Unit>()

        override suspend fun getUserSecretsWithAccess(
            limit: Int,
            offset: Int,
        ): Result<PaginatedSecretsWithAccessData> {
            try {
                releaseInitial.await()
            } catch (_: CancellationException) {
                withContext(NonCancellable) { releaseInitial.await() }
            }
            return Result.success(
                PaginatedSecretsWithAccessData(listOf(access(secret("s1"), canManage = true)), false),
            )
        }

        override suspend fun searchSecretsWithAccess(
            query: String,
            limit: Int,
            offset: Int,
        ): Result<PaginatedSecretsWithAccessData> =
            Result.success(
                PaginatedSecretsWithAccessData(listOf(access(secret("s1"), canManage = false)), false),
            )
    }

    /** Implements only the original API surface so its new default is exercised. */
    private class LegacyProvider(private val row: SecretEntryData) : SecretDataProvider {
        override suspend fun getUserSecrets(limit: Int, offset: Int) =
            Result.success(PaginatedSecretsData(listOf(row), false))

        override suspend fun searchSecrets(query: String, limit: Int, offset: Int) =
            Result.success(PaginatedSecretsData(emptyList(), false))

        override suspend fun getSecretShares(secretId: String) = Result.success(emptyList<SecretShareData>())
        override suspend fun getUserSecretsWithSharingInfo(limit: Int, offset: Int) =
            Result.failure<PaginatedSecretsWithSharingData>(UnsupportedOperationException())
        override suspend fun createSecret(request: CreateSecretRequestData) = Result.success(Unit)
        override suspend fun updateSecret(request: UpdateSecretRequestData) = Result.success(Unit)
        override suspend fun deleteSecret(id: String) = Result.success(Unit)
        override suspend fun shareSecret(request: ShareSecretRequestData) = Result.success(Unit)
        override suspend fun unshareSecret(request: UnshareSecretRequestData) = Result.success(Unit)
    }

    private companion object {
        fun secret(id: String) = SecretEntryData(
            id = id,
            website = "github.com",
            username = "octocat",
            password = "hunter2",
            createdAt = "then",
            updatedAt = "now",
        )

        fun access(secret: SecretEntryData, canManage: Boolean) = SecretEntryWithAccessData(
            secret = secret,
            orgId = "org-1",
            orgSlug = "acme",
            isOrgOwned = true,
            canManage = canManage,
        )

        fun update(id: String) = UpdateSecretRequestData(id, "github.com", "octocat", "replacement")

        fun share(
            id: String,
            userId: String? = null,
            userEmail: String? = null,
            roleId: String? = null,
            roleName: String? = null,
        ) = SecretShareData(
            shareId = id,
            sharedWithUserId = userId,
            sharedWithUserEmail = userEmail,
            sharedWithRoleId = roleId,
            sharedWithRoleName = roleName,
            accessLevel = "read",
            sharedByEmail = "owner@example.com",
            createdAt = "now",
        )
    }
}
