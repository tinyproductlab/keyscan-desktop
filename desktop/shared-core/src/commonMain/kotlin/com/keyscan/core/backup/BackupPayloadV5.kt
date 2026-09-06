package com.keyscan.core.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class BackupPayloadV5(
    val version: Int = 5,
    val records: List<BackupScanRecord> = emptyList(),
    val passwordGroups: List<BackupPasswordGroup> = emptyList(),
    val passwords: List<BackupPasswordEntry> = emptyList(),
    val otpTokens: List<BackupOtpToken> = emptyList(),
    val passwordGenerations: List<BackupPasswordGeneration> = emptyList(),
    val vaultItems: List<BackupVaultItem> = emptyList(),
    val vaultAttachments: List<BackupVaultAttachment> = emptyList()
)

@Serializable data class BackupScanRecord(val content: String? = null, val type: String? = null, val title: String? = null, val source: String? = null, val thumbnailBase64: String? = null, val isStarred: Boolean = false, val timestamp: Long = 0)
@Serializable data class BackupPasswordGroup(val id: String = "", val name: String? = null, val sortOrder: Int = 0, val isDefault: Boolean = false, val createdAt: Long = 0, val updatedAt: Long = 0)
@Serializable data class BackupPasswordEntry(
    val itemId: String? = null, val otpItemId: String? = null, val title: String? = null, val websiteDomain: String? = null,
    val appPackageName: String? = null, val username: String? = null, val password: String? = null, val account: String? = null,
    val remark: String? = null, val notes: String? = null, val groupId: String? = null, val lastUsedAt: Long = 0, val createdAt: Long = 0, val updatedAt: Long = 0
)
@Serializable data class BackupOtpToken(
    val itemId: String? = null, val accountName: String? = null, val issuer: String? = null, val secret: String? = null,
    val digits: Int = 6, val period: Int = 30, val algorithm: String? = "SHA1", val pinned: Boolean = false,
    val sortOrder: Int = 0, val createdAt: Long = 0, val updatedAt: Long = 0
)
@Serializable data class BackupPasswordGeneration(
    val itemId: String? = null, val password: String? = null, val remark: String? = null, val length: Int = 0,
    val configSummary: String? = null, val createdAt: Long = 0, val source: String? = null, val website: String? = null,
    val account: String? = null, val linkedPasswordEntryItemId: String? = null
)
@Serializable data class BackupVaultItem(
    val id: String = "", val type: String = "CUSTOM", val category: String = "CUSTOM", val title: String = "",
    val fields: String = "{}", val notes: String = "", val createdTime: Long = 0, val updatedTime: Long = 0
)
@Serializable data class BackupVaultAttachment(
    val id: String = "", val itemId: String = "", val filename: String = "", val mimeType: String = "application/octet-stream",
    val size: Long = 0, val hash: String = "", val contentReference: String? = null
)

object BackupPayloadV5Codec {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        isLenient = false
    }

    fun encode(payload: BackupPayloadV5): ByteArray {
        require(payload.version == 5) { "Android backup payload version must remain 5" }
        return json.encodeToString(BackupPayloadV5.serializer(), payload.copy(version = 5)).toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray): BackupPayloadV5 {
        require(bytes.size <= MAX_JSON_BYTES) { "Backup JSON is too large" }
        val payload = json.decodeFromString(BackupPayloadV5.serializer(), bytes.toString(Charsets.UTF_8))
        require(payload.version == 5) { "Unsupported backup payload version: ${payload.version}" }
        require(payload.records.size <= MAX_ITEMS && payload.passwordGroups.size <= MAX_ITEMS && payload.passwords.size <= MAX_ITEMS &&
            payload.otpTokens.size <= MAX_ITEMS && payload.passwordGenerations.size <= MAX_ITEMS && payload.vaultItems.size <= MAX_ITEMS && payload.vaultAttachments.size <= MAX_ITEMS) {
            "Backup contains too many items"
        }
        return payload
    }

    private const val MAX_JSON_BYTES = 256 * 1024 * 1024
    private const val MAX_ITEMS = 100_000
}
