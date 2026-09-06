package com.keyscan.core.backup

import com.keyscan.core.model.PasswordEntry
import com.keyscan.core.model.SecureItem
import com.keyscan.core.model.TotpEntry
import com.keyscan.core.model.VaultAttachment
import com.keyscan.core.model.PasswordGroup
import java.util.UUID

data class BackupDomainSnapshot(
    val passwords: List<PasswordEntry> = emptyList(),
    val otpTokens: List<TotpEntry> = emptyList(),
    val vaultItems: List<SecureItem> = emptyList(),
    val vaultAttachments: List<VaultAttachment> = emptyList(),
    val passwordGroups: List<PasswordGroup> = emptyList()
)

object BackupPayloadMapper {
    fun toPayload(snapshot: BackupDomainSnapshot): BackupPayloadV5 = BackupPayloadV5(
        passwordGroups = snapshot.passwordGroups.map { BackupPasswordGroup(it.id, it.name, it.sortOrder, it.isDefault, it.createdAt, it.updatedAt) },
        passwords = snapshot.passwords.map { entry -> BackupPasswordEntry(
            itemId = entry.id, otpItemId = entry.otpItemId, title = entry.title, websiteDomain = entry.websiteDomain,
            appPackageName = entry.appPackageName, username = entry.username, password = entry.password, account = entry.account,
            remark = entry.remark, notes = entry.notes, groupId = entry.groupId, lastUsedAt = entry.lastUsedAt,
            createdAt = entry.createdAt, updatedAt = entry.updatedAt
        ) },
        otpTokens = snapshot.otpTokens.map { token -> BackupOtpToken(token.itemId, token.accountName, token.issuer, token.secret, token.digits, token.period, token.algorithm, token.pinned, token.sortOrder, token.createdAt, token.updatedAt) },
        vaultItems = snapshot.vaultItems.map { item -> BackupVaultItem(item.id, item.type, item.category, item.title, item.fieldsJson, item.notes, item.createdTime, item.updatedTime) },
        vaultAttachments = snapshot.vaultAttachments.map { attachment -> BackupVaultAttachment(attachment.id, attachment.itemId, attachment.filename, attachment.mimeType, attachment.size, attachment.hash, null) }
    )

    /** Attachment content is restored separately from contentReference after its hash has been verified. */
    fun toDomain(payload: BackupPayloadV5): BackupDomainSnapshot = BackupDomainSnapshot(
        passwords = payload.passwords.map { entry -> PasswordEntry(
            entry.itemId?.takeIf(String::isNotBlank) ?: UUID.randomUUID().toString(),
            entry.title.orEmpty(), entry.websiteDomain.orEmpty(), entry.username.orEmpty(), entry.password.orEmpty(), entry.notes.orEmpty(),
            entry.groupId, entry.otpItemId, entry.appPackageName, entry.account, entry.remark, entry.lastUsedAt, entry.createdAt, entry.updatedAt
        ) },
        otpTokens = payload.otpTokens.map { token -> TotpEntry(
            token.itemId?.takeIf(String::isNotBlank) ?: UUID.randomUUID().toString(), token.accountName.orEmpty(), token.issuer.orEmpty(), token.secret.orEmpty(), token.digits, token.period,
            token.algorithm ?: "SHA1", token.pinned, token.sortOrder, token.createdAt, token.updatedAt
        ) },
        vaultItems = payload.vaultItems.filter { it.id.isNotBlank() }.map { item -> SecureItem(item.id, item.type, item.category, item.title, item.fields, item.notes, item.createdTime, item.updatedTime) },
        vaultAttachments = payload.vaultAttachments.filter { it.id.isNotBlank() && it.itemId.isNotBlank() }.map { attachment ->
            VaultAttachment(attachment.id, attachment.itemId, attachment.filename, attachment.mimeType, "", attachment.hash, attachment.size)
        },
        passwordGroups = payload.passwordGroups.filter { it.id.isNotBlank() }.map { PasswordGroup(it.id, it.name.orEmpty(), it.sortOrder, it.isDefault, it.createdAt, it.updatedAt) }
    )
}
