package com.keyscan.core.model

data class PasswordEntry(
    val id: String,
    val title: String,
    val websiteDomain: String,
    val username: String,
    val password: String,
    val notes: String = "",
    val groupId: String? = null,
    val otpItemId: String? = null,
    val appPackageName: String? = null,
    val account: String? = null,
    val remark: String? = null,
    val lastUsedAt: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt
)

data class PasswordGroup(
    val id: String,
    val name: String,
    val sortOrder: Int = 0,
    val isDefault: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt
)

data class PasswordHistory(
    val historyId: String,
    val entryItemId: String,
    val oldPassword: String,
    val createdAt: Long = System.currentTimeMillis(),
    val source: String = "manual_edit",
    val deviceId: String? = null,
    val note: String? = null
)

enum class TrashType { PASSWORD, OTP, VAULT }
data class TrashEntry(
    val id: String,
    val type: TrashType,
    val originalId: String,
    val title: String,
    val deletedAt: Long,
    val password: PasswordEntry? = null,
    val totp: TotpEntry? = null,
    val secureItem: SecureItem? = null,
    val attachments: List<VaultAttachment> = emptyList()
)

data class TotpEntry(
    val itemId: String,
    val accountName: String,
    val issuer: String,
    val secret: String,
    val digits: Int = 6,
    val period: Int = 30,
    val algorithm: String = "SHA1",
    val pinned: Boolean = false,
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt
) { val id: String get() = itemId }

data class SecureItem(
    val id: String,
    val type: String = "CUSTOM",
    val category: String = "CUSTOM",
    val title: String,
    val fieldsJson: String = "{}",
    val notes: String = "",
    val createdTime: Long = System.currentTimeMillis(),
    val updatedTime: Long = createdTime
)

data class VaultAttachment(
    val id: String,
    val itemId: String,
    val filename: String,
    val mimeType: String = "application/octet-stream",
    val encryptedPath: String,
    val hash: String,
    val size: Long,
    val createdTime: Long = System.currentTimeMillis()
)
