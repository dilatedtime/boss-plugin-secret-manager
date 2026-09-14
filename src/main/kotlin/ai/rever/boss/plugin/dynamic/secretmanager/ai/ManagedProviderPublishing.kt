package ai.rever.boss.plugin.dynamic.secretmanager.ai

import ai.rever.boss.plugin.api.CreateSecretRequestData
import ai.rever.boss.plugin.api.SecretEntryData
import ai.rever.boss.plugin.api.UpdateSecretRequestData

/** The one canonical vault shape consumed by shared managed-provider discovery. */
internal fun bossAiDefinitionRequest(): CreateSecretRequestData {
    val definition = SharedProviderDefinition.bossAi()
    return CreateSecretRequestData(
        website = SharedProviderDefinition.BOSS_AI_WEBSITE,
        username = SharedProviderDefinition.BOSS_AI_USERNAME,
        password = SharedProviderDefinition.INERT_PASSWORD,
        notes = definition.canonicalNotes(),
        expirationDate = null,
        tags = listOf(SharedProviderDefinition.TAG),
        twofaEnabled = false,
        twofaType = null,
        recoveryCodes = emptyList(),
    )
}

/**
 * Turn an existing inert definition into the canonical tagged entry without ever
 * treating an arbitrary secret as something safe to broadcast.
 */
internal fun managedProviderUpdate(entry: SecretEntryData): Result<UpdateSecretRequestData> = runCatching {
    require(entry.password == SharedProviderDefinition.INERT_PASSWORD) {
        "Managed AI provider definitions must use the inert '${SharedProviderDefinition.INERT_PASSWORD}' placeholder."
    }
    require(entry.metadata?.twofaEnabled != true) {
        "A secret with 2FA material cannot be published as a managed AI provider definition."
    }
    val definition = requireNotNull(SharedProviderDefinition.parse(entry.notes)) {
        "The secret notes are not a valid boss-managed-provider-v1 definition."
    }
    UpdateSecretRequestData(
        secretId = entry.id,
        website = definition.name,
        username = SharedProviderDefinition.BOSS_AI_USERNAME,
        password = SharedProviderDefinition.INERT_PASSWORD,
        notes = definition.canonicalNotes(),
        expirationDate = entry.expirationDate,
        tags = (entry.tags + SharedProviderDefinition.TAG).distinct(),
        twofaEnabled = false,
        twofaType = null,
        recoveryCodes = emptyList(),
    )
}
