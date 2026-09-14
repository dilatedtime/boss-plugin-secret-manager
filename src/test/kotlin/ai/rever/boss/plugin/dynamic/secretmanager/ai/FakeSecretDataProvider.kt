package ai.rever.boss.plugin.dynamic.secretmanager.ai

import ai.rever.boss.plugin.api.CreateSecretRequestData
import ai.rever.boss.plugin.api.PaginatedSecretsData
import ai.rever.boss.plugin.api.PaginatedSecretsWithSharingData
import ai.rever.boss.plugin.api.SecretDataProvider
import ai.rever.boss.plugin.api.SecretEntryData
import ai.rever.boss.plugin.api.SecretShareData
import ai.rever.boss.plugin.api.ShareSecretRequestData
import ai.rever.boss.plugin.api.UnshareSecretRequestData
import ai.rever.boss.plugin.api.UpdateSecretRequestData
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Fake store: records writes and serves [entries] in pages, so paging and precedence are
 * observable without a signed-in session. Only the members the credential store touches do
 * anything; the sharing surface is unreachable from it.
 *
 * Shared by [ProviderCredentialStoreTest] and [LegacySettingsImportTest] — the import path
 * asserts on the same [created] list, so a second copy would be free to drift from the real
 * write behaviour.
 */
internal class FakeSecretDataProvider(
    @Volatile var entries: List<SecretEntryData>,
    private val failReads: Boolean = false,
    private val failWrites: Boolean = false,
) : SecretDataProvider {
    val created = mutableListOf<CreateSecretRequestData>()
    val updated = mutableListOf<UpdateSecretRequestData>()
    val deleted = mutableListOf<String>()
    val pageRequests = CopyOnWriteArrayList<Pair<Int, Int>>()

    override suspend fun getUserSecrets(
        limit: Int,
        offset: Int,
    ): Result<PaginatedSecretsData> {
        if (failReads) return Result.failure(IllegalStateException("not signed in"))
        pageRequests += limit to offset
        val page = entries.drop(offset).take(limit)
        return Result.success(PaginatedSecretsData(page, hasMore = offset + page.size < entries.size))
    }

    override suspend fun createSecret(request: CreateSecretRequestData): Result<Unit> {
        if (failWrites) return Result.failure(IllegalStateException("read-only store"))
        created += request
        return Result.success(Unit)
    }

    override suspend fun updateSecret(request: UpdateSecretRequestData): Result<Unit> {
        if (failWrites) return Result.failure(IllegalStateException("read-only store"))
        updated += request
        return Result.success(Unit)
    }

    override suspend fun deleteSecret(id: String): Result<Unit> {
        deleted += id
        entries = entries.filterNot { it.id == id }
        return Result.success(Unit)
    }

    override suspend fun getUserSecretsWithSharingInfo(
        limit: Int,
        offset: Int,
    ): Result<PaginatedSecretsWithSharingData> = Result.failure(UnsupportedOperationException())

    override suspend fun searchSecrets(
        query: String,
        limit: Int,
        offset: Int,
    ): Result<PaginatedSecretsData> = Result.failure(UnsupportedOperationException())

    override suspend fun getSecretShares(secretId: String): Result<List<SecretShareData>> =
        Result.failure(UnsupportedOperationException())

    override suspend fun shareSecret(request: ShareSecretRequestData): Result<Unit> =
        Result.failure(UnsupportedOperationException())

    override suspend fun unshareSecret(request: UnshareSecretRequestData): Result<Unit> =
        Result.failure(UnsupportedOperationException())
}
