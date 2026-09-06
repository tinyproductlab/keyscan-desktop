package com.keyscan.core.vault

import com.keyscan.core.model.PasswordEntry
import com.keyscan.core.model.TotpEntry
import com.keyscan.core.model.SecureItem
import com.keyscan.core.model.VaultAttachment
import com.keyscan.core.model.PasswordGroup
import com.keyscan.core.model.PasswordHistory
import com.keyscan.core.model.TrashEntry
import com.keyscan.core.model.TrashType
import java.util.UUID
import com.keyscan.core.security.VaultAuthenticationException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class EncryptedVaultStore(
    private val vaultFile: Path,
    private val databaseKeyProvider: ((CharArray) -> Unit) -> Unit,
    private val random: SecureRandom = SecureRandom()
) {
    data class Snapshot(
        val passwords: List<PasswordEntry>,
        val totp: List<TotpEntry>,
        val secureItems: List<SecureItem>,
        val attachments: List<VaultAttachment>,
        val passwordGroups: List<PasswordGroup> = emptyList(),
        val passwordHistory: List<PasswordHistory> = emptyList(),
        val trash: List<TrashEntry> = emptyList()
    )

    @Synchronized fun snapshot(): Snapshot = load().let { Snapshot(it.passwords.toList(), it.totp.toList(), it.secureItems.toList(), it.attachments.toList(), it.passwordGroups.toList(), it.passwordHistory.toList(), it.trash.toList()) }

    @Synchronized fun replaceAll(snapshot: Snapshot) {
        require(snapshot.passwords.size <= MAX_ENTRIES && snapshot.totp.size <= MAX_ENTRIES && snapshot.secureItems.size <= MAX_ENTRIES &&
            snapshot.attachments.size <= MAX_ENTRIES && snapshot.passwordGroups.size <= MAX_ENTRIES && snapshot.passwordHistory.size <= MAX_ENTRIES &&
            snapshot.trash.size <= MAX_ENTRIES) { "Vault snapshot contains too many entries" }
        require(snapshot.passwords.map { it.id }.distinct().size == snapshot.passwords.size) { "Duplicate password ids" }
        require(snapshot.totp.map { it.itemId }.distinct().size == snapshot.totp.size) { "Duplicate TOTP ids" }
        require(snapshot.secureItems.map { it.id }.distinct().size == snapshot.secureItems.size) { "Duplicate secure item ids" }
        require(snapshot.attachments.map { it.id }.distinct().size == snapshot.attachments.size) { "Duplicate attachment ids" }
        require(snapshot.passwordGroups.map { it.id }.distinct().size == snapshot.passwordGroups.size) { "Duplicate password group ids" }
        require(snapshot.passwordHistory.map { it.historyId }.distinct().size == snapshot.passwordHistory.size) { "Duplicate password history ids" }
        require(snapshot.trash.map { it.id }.distinct().size == snapshot.trash.size) { "Duplicate trash ids" }
        val itemIds = snapshot.secureItems.mapTo(hashSetOf()) { it.id }
        require(snapshot.attachments.all { it.itemId in itemIds }) { "Attachment references a missing secure item" }
        save(VaultData(snapshot.passwords.toMutableList(), snapshot.totp.toMutableList(), snapshot.secureItems.toMutableList(), snapshot.attachments.toMutableList(), snapshot.passwordGroups.toMutableList(), snapshot.passwordHistory.toMutableList(), snapshot.trash.toMutableList()))
    }
    @Synchronized
    fun listPasswords(): List<PasswordEntry> = load().passwords.toList()

    @Synchronized
    fun findPassword(id: String): PasswordEntry? = load().passwords.firstOrNull { it.id == id }

    @Synchronized
    fun savePassword(entry: PasswordEntry) {
        require(entry.id.isNotBlank()) { "Password entry id must not be blank" }
        val vault = load()
        val index = vault.passwords.indexOfFirst { it.id == entry.id }
        if (index >= 0) {
            val previous = vault.passwords[index]
            if (previous.password != entry.password) vault.passwordHistory.add(PasswordHistory(UUID.randomUUID().toString(), previous.id, previous.password))
            vault.passwords[index] = entry
        } else vault.passwords.add(entry)
        save(vault)
    }

    @Synchronized fun listPasswordHistory(entryId: String): List<PasswordHistory> = load().passwordHistory.filter { it.entryItemId == entryId }.sortedByDescending { it.createdAt }
    @Synchronized fun restorePasswordHistory(historyId: String): Boolean {
        val vault = load(); val history = vault.passwordHistory.firstOrNull { it.historyId == historyId } ?: return false
        val index = vault.passwords.indexOfFirst { it.id == history.entryItemId }; if (index < 0) return false
        val current = vault.passwords[index]; if (current.password == history.oldPassword) return true
        vault.passwordHistory.add(PasswordHistory(UUID.randomUUID().toString(), current.id, current.password, source = "restore"))
        vault.passwords[index] = current.copy(password = history.oldPassword, updatedAt = System.currentTimeMillis()); save(vault); return true
    }
    @Synchronized fun deletePasswordHistory(historyId: String): Boolean {
        val vault = load(); val removed = vault.passwordHistory.removeAll { it.historyId == historyId }; if (removed) save(vault); return removed
    }

    @Synchronized
    fun deletePassword(id: String): Boolean {
        val vault = load()
        val value = vault.passwords.firstOrNull { it.id == id } ?: return false
        vault.passwords.remove(value); vault.trash.add(TrashEntry(UUID.randomUUID().toString(), TrashType.PASSWORD, value.id, value.title.ifBlank { value.username }, System.currentTimeMillis(), password = value)); save(vault); return true
    }

    @Synchronized fun listPasswordGroups(): List<PasswordGroup> = load().passwordGroups.sortedWith(compareBy<PasswordGroup> { it.sortOrder }.thenBy { it.name })
    @Synchronized fun savePasswordGroup(group: PasswordGroup) {
        require(group.id.isNotBlank() && group.name.isNotBlank())
        val vault = load(); val index = vault.passwordGroups.indexOfFirst { it.id == group.id }
        if (index >= 0) vault.passwordGroups[index] = group else vault.passwordGroups.add(group)
        save(vault)
    }

    @Synchronized fun listTotp(): List<TotpEntry> = load().totp.toList()
    @Synchronized fun saveTotp(entry: TotpEntry) {
        require(entry.itemId.isNotBlank()) { "TOTP item id must not be blank" }
        val vault = load(); val index = vault.totp.indexOfFirst { it.itemId == entry.itemId }
        if (index >= 0) vault.totp[index] = entry else vault.totp.add(entry)
        save(vault)
    }
    @Synchronized fun deleteTotp(id: String): Boolean {
        val vault = load(); val value = vault.totp.firstOrNull { it.itemId == id } ?: return false
        vault.totp.remove(value); vault.trash.add(TrashEntry(UUID.randomUUID().toString(), TrashType.OTP, value.itemId, value.issuer.ifBlank { value.accountName }, System.currentTimeMillis(), totp = value)); save(vault); return true
    }

    @Synchronized fun listSecureItems(): List<SecureItem> = load().secureItems.toList()
    @Synchronized fun saveSecureItem(entry: SecureItem) {
        require(entry.id.isNotBlank()) { "Secure item id must not be blank" }
        val vault = load(); val index = vault.secureItems.indexOfFirst { it.id == entry.id }
        if (index >= 0) vault.secureItems[index] = entry else vault.secureItems.add(entry); save(vault)
    }
    @Synchronized fun deleteSecureItem(id: String): Boolean {
        val vault = load(); val value = vault.secureItems.firstOrNull { it.id == id } ?: return false
        val related = vault.attachments.filter { it.itemId == id }; vault.secureItems.remove(value); vault.attachments.removeAll(related)
        vault.trash.add(TrashEntry(UUID.randomUUID().toString(), TrashType.VAULT, value.id, value.title, System.currentTimeMillis(), secureItem = value, attachments = related)); save(vault); return true
    }
    @Synchronized fun listAttachments(itemId: String): List<VaultAttachment> = load().attachments.filter { it.itemId == itemId }
    @Synchronized fun saveAttachment(entry: VaultAttachment) {
        require(entry.id.isNotBlank() && entry.itemId.isNotBlank()) { "Attachment ids must not be blank" }
        val vault = load(); val index = vault.attachments.indexOfFirst { it.id == entry.id }
        if (index >= 0) vault.attachments[index] = entry else vault.attachments.add(entry); save(vault)
    }
    @Synchronized fun deleteAttachment(id: String): Boolean {
        val vault = load(); val removed = vault.attachments.removeAll { it.id == id }; if (removed) save(vault); return removed
    }

    @Synchronized fun listTrash(): List<TrashEntry> = load().trash.sortedByDescending { it.deletedAt }
    @Synchronized fun restoreTrash(id: String): Boolean {
        val vault = load(); val item = vault.trash.firstOrNull { it.id == id } ?: return false
        when (item.type) {
            TrashType.PASSWORD -> { val value = item.password ?: return false; if (vault.passwords.any { it.id == value.id }) return false; vault.passwords.add(value) }
            TrashType.OTP -> { val value = item.totp ?: return false; if (vault.totp.any { it.itemId == value.itemId }) return false; vault.totp.add(value) }
            TrashType.VAULT -> { val value = item.secureItem ?: return false; if (vault.secureItems.any { it.id == value.id } || item.attachments.any { incoming -> vault.attachments.any { it.id == incoming.id } }) return false; vault.secureItems.add(value); vault.attachments.addAll(item.attachments) }
        }
        vault.trash.remove(item); save(vault); return true
    }
    @Synchronized fun permanentlyDeleteTrash(id: String): TrashEntry? {
        val vault = load(); val item = vault.trash.firstOrNull { it.id == id } ?: return null; vault.trash.remove(item); save(vault); return item
    }
    @Synchronized fun clearTrash(): List<TrashEntry> {
        val vault = load(); val removed = vault.trash.toList(); if (removed.isNotEmpty()) { vault.trash.clear(); save(vault) }; return removed
    }

    private fun load(): VaultData {
        if (!Files.exists(vaultFile)) return VaultData()
        val bytes = Files.readAllBytes(vaultFile)
        require(bytes.size <= MAX_VAULT_BYTES) { "Vault file is too large" }
        return decode(decrypt(bytes))
    }

    private fun save(vault: VaultData) {
        require(vault.passwords.size <= MAX_ENTRIES) { "Too many password entries" }
        require(vault.totp.size <= MAX_ENTRIES) { "Too many TOTP entries" }
        require(vault.secureItems.size <= MAX_ENTRIES) { "Too many secure items" }
        require(vault.attachments.size <= MAX_ENTRIES) { "Too many attachments" }
        require(vault.passwordGroups.size <= MAX_ENTRIES) { "Too many password groups" }
        require(vault.passwordHistory.size <= MAX_ENTRIES) { "Too many password history entries" }
        require(vault.trash.size <= MAX_ENTRIES) { "Too many trash entries" }
        val plaintext = encode(vault)
        val encrypted = try { encrypt(plaintext) } finally { plaintext.fill(0) }
        Files.createDirectories(vaultFile.parent)
        val temporary = Files.createTempFile(vaultFile.parent, "vault-data-", ".tmp")
        try {
            Files.write(temporary, encrypted, StandardOpenOption.TRUNCATE_EXISTING)
            try {
                Files.move(temporary, vaultFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Exception) {
                Files.move(temporary, vaultFile, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            encrypted.fill(0)
            Files.deleteIfExists(temporary)
        }
    }

    private fun encrypt(plaintext: ByteArray): ByteArray {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val key = databaseAesKey()
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            MAGIC + iv + cipher.doFinal(plaintext)
        } finally { key.encoded.fill(0); iv.fill(0) }
    }

    private fun decrypt(container: ByteArray): ByteArray {
        if (container.size < MAGIC.size + IV_BYTES + TAG_BYTES || !container.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw VaultAuthenticationException("Invalid encrypted vault container")
        }
        val iv = container.copyOfRange(MAGIC.size, MAGIC.size + IV_BYTES)
        val ciphertext = container.copyOfRange(MAGIC.size + IV_BYTES, container.size)
        val key = databaseAesKey()
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            cipher.doFinal(ciphertext)
        } catch (_: AEADBadTagException) {
            throw VaultAuthenticationException("Vault authentication failed")
        } finally { key.encoded.fill(0); iv.fill(0); ciphertext.fill(0) }
    }

    private fun databaseAesKey(): SecretKeySpec {
        var result: ByteArray? = null
        databaseKeyProvider { chars ->
            val encoded = ByteArray(chars.size)
            try {
                chars.forEachIndexed { index, char ->
                    if (char.code > 0x7f) throw VaultAuthenticationException("Invalid database key encoding")
                    encoded[index] = char.code.toByte()
                }
                val decoded = try { Base64.getDecoder().decode(encoded) } catch (_: IllegalArgumentException) {
                    throw VaultAuthenticationException("Invalid database key encoding")
                }
                if (decoded.size != 32) { decoded.fill(0); throw VaultAuthenticationException("Invalid database key length") }
                result = decoded
            } finally { encoded.fill(0) }
        }
        return SecretKeySpec(checkNotNull(result), "AES")
    }

    private fun encode(vault: VaultData): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(PAYLOAD_VERSION)
            output.writeInt(vault.passwords.size)
            vault.passwords.forEach { entry ->
                output.writeLimited(entry.id)
                output.writeLimited(entry.title)
                output.writeLimited(entry.websiteDomain)
                output.writeLimited(entry.username)
                output.writeLimited(entry.password)
                output.writeLimited(entry.notes)
                output.writeNullable(entry.groupId); output.writeNullable(entry.otpItemId); output.writeNullable(entry.appPackageName)
                output.writeNullable(entry.account); output.writeNullable(entry.remark); output.writeLong(entry.lastUsedAt); output.writeLong(entry.createdAt); output.writeLong(entry.updatedAt)
            }
            output.writeInt(vault.totp.size)
            vault.totp.forEach { entry ->
                output.writeLimited(entry.itemId); output.writeLimited(entry.accountName); output.writeLimited(entry.issuer); output.writeLimited(entry.secret)
                output.writeInt(entry.digits); output.writeInt(entry.period); output.writeLimited(entry.algorithm); output.writeBoolean(entry.pinned)
                output.writeInt(entry.sortOrder); output.writeLong(entry.createdAt); output.writeLong(entry.updatedAt)
            }
            output.writeInt(vault.secureItems.size)
            vault.secureItems.forEach { entry ->
                output.writeLimited(entry.id); output.writeLimited(entry.type); output.writeLimited(entry.category); output.writeLimited(entry.title)
                output.writeLimited(entry.fieldsJson); output.writeLimited(entry.notes); output.writeLong(entry.createdTime); output.writeLong(entry.updatedTime)
            }
            output.writeInt(vault.attachments.size)
            vault.attachments.forEach { entry ->
                output.writeLimited(entry.id); output.writeLimited(entry.itemId); output.writeLimited(entry.filename); output.writeLimited(entry.mimeType)
                output.writeLimited(entry.encryptedPath); output.writeLimited(entry.hash); output.writeLong(entry.size); output.writeLong(entry.createdTime)
            }
            output.writeInt(vault.passwordGroups.size)
            vault.passwordGroups.forEach { group ->
                output.writeLimited(group.id); output.writeLimited(group.name); output.writeInt(group.sortOrder); output.writeBoolean(group.isDefault); output.writeLong(group.createdAt); output.writeLong(group.updatedAt)
            }
            output.writeInt(vault.passwordHistory.size)
            vault.passwordHistory.forEach { history ->
                output.writeLimited(history.historyId); output.writeLimited(history.entryItemId); output.writeLimited(history.oldPassword); output.writeLong(history.createdAt); output.writeLimited(history.source); output.writeNullable(history.deviceId); output.writeNullable(history.note)
            }
            output.writeInt(vault.trash.size)
            vault.trash.forEach { item ->
                output.writeLimited(item.id); output.writeInt(item.type.ordinal); output.writeLimited(item.originalId); output.writeLimited(item.title); output.writeLong(item.deletedAt)
                when (item.type) {
                    TrashType.PASSWORD -> output.writePassword(checkNotNull(item.password))
                    TrashType.OTP -> output.writeTotp(checkNotNull(item.totp))
                    TrashType.VAULT -> { output.writeSecureItem(checkNotNull(item.secureItem)); output.writeInt(item.attachments.size); item.attachments.forEach { output.writeAttachment(it) } }
                }
            }
        }
        bytes.toByteArray()
    }

    private fun decode(bytes: ByteArray): VaultData = try {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val version = input.readInt()
            require(version in 1..PAYLOAD_VERSION) { "Unsupported vault payload version" }
            val count = input.readInt()
            require(count in 0..MAX_ENTRIES) { "Invalid password entry count" }
            val entries = MutableList(count) {
                val base = PasswordEntry(input.readLimited(), input.readLimited(), input.readLimited(), input.readLimited(), input.readLimited(), input.readLimited())
                if (version >= 4) base.copy(groupId = input.readNullable(), otpItemId = input.readNullable(), appPackageName = input.readNullable(), account = input.readNullable(), remark = input.readNullable(), lastUsedAt = input.readLong(), createdAt = input.readLong(), updatedAt = input.readLong()) else base
            }
            val totp = if (version >= 2) {
                val totpCount = input.readInt(); require(totpCount in 0..MAX_ENTRIES) { "Invalid TOTP entry count" }
                MutableList(totpCount) { TotpEntry(input.readLimited(), input.readLimited(), input.readLimited(), input.readLimited(), input.readInt(), input.readInt(), input.readLimited(), input.readBoolean(), input.readInt(), input.readLong(), input.readLong()) }
            } else mutableListOf()
            val secureItems = if (version >= 3) {
                val itemCount = input.readInt(); require(itemCount in 0..MAX_ENTRIES) { "Invalid secure item count" }
                MutableList(itemCount) { SecureItem(input.readLimited(), input.readLimited(), input.readLimited(), input.readLimited(), input.readLimited(), input.readLimited(), input.readLong(), input.readLong()) }
            } else mutableListOf()
            val attachments = if (version >= 3) {
                val attachmentCount = input.readInt(); require(attachmentCount in 0..MAX_ENTRIES) { "Invalid attachment count" }
                MutableList(attachmentCount) { VaultAttachment(input.readLimited(), input.readLimited(), input.readLimited(), input.readLimited(), input.readLimited(), input.readLimited(), input.readLong(), input.readLong()) }
            } else mutableListOf()
            val passwordGroups = if (version >= 5) {
                val groupCount = input.readInt(); require(groupCount in 0..MAX_ENTRIES) { "Invalid password group count" }
                MutableList(groupCount) { PasswordGroup(input.readLimited(), input.readLimited(), input.readInt(), input.readBoolean(), input.readLong(), input.readLong()) }
            } else mutableListOf()
            val passwordHistory = if (version >= 6) {
                val historyCount = input.readInt(); require(historyCount in 0..MAX_ENTRIES) { "Invalid password history count" }
                MutableList(historyCount) { PasswordHistory(input.readLimited(), input.readLimited(), input.readLimited(), input.readLong(), input.readLimited(), input.readNullable(), input.readNullable()) }
            } else mutableListOf()
            val trash = if (version >= 7) {
                val trashCount = input.readInt(); require(trashCount in 0..MAX_ENTRIES) { "Invalid trash item count" }
                MutableList(trashCount) {
                    val id = input.readLimited(); val type = TrashType.entries.getOrNull(input.readInt()) ?: error("Invalid trash item type"); val originalId = input.readLimited(); val title = input.readLimited(); val deletedAt = input.readLong()
                    when (type) {
                        TrashType.PASSWORD -> TrashEntry(id, type, originalId, title, deletedAt, password = input.readPassword())
                        TrashType.OTP -> TrashEntry(id, type, originalId, title, deletedAt, totp = input.readTotp())
                        TrashType.VAULT -> { val secureItem = input.readSecureItem(); val count = input.readInt(); require(count in 0..MAX_ENTRIES); TrashEntry(id, type, originalId, title, deletedAt, secureItem = secureItem, attachments = MutableList(count) { input.readAttachment() }) }
                    }
                }
            } else mutableListOf()
            require(input.available() == 0) { "Unexpected trailing vault data" }
            VaultData(entries, totp, secureItems, attachments, passwordGroups, passwordHistory, trash)
        }
    } finally { bytes.fill(0) }

    private fun DataOutputStream.writeLimited(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_FIELD_BYTES) { "Vault field is too large" }
        writeInt(bytes.size); write(bytes); bytes.fill(0)
    }

    private fun DataInputStream.readLimited(): String {
        val length = readInt()
        require(length in 0..MAX_FIELD_BYTES) { "Invalid vault field length" }
        val bytes = ByteArray(length); readFully(bytes)
        return try { String(bytes, StandardCharsets.UTF_8) } finally { bytes.fill(0) }
    }

    private fun DataOutputStream.writeNullable(value: String?) { writeBoolean(value != null); if (value != null) writeLimited(value) }
    private fun DataInputStream.readNullable(): String? = if (readBoolean()) readLimited() else null

    private fun DataOutputStream.writePassword(entry: PasswordEntry) { writeLimited(entry.id); writeLimited(entry.title); writeLimited(entry.websiteDomain); writeLimited(entry.username); writeLimited(entry.password); writeLimited(entry.notes); writeNullable(entry.groupId); writeNullable(entry.otpItemId); writeNullable(entry.appPackageName); writeNullable(entry.account); writeNullable(entry.remark); writeLong(entry.lastUsedAt); writeLong(entry.createdAt); writeLong(entry.updatedAt) }
    private fun DataInputStream.readPassword() = PasswordEntry(readLimited(), readLimited(), readLimited(), readLimited(), readLimited(), readLimited(), readNullable(), readNullable(), readNullable(), readNullable(), readNullable(), readLong(), readLong(), readLong())
    private fun DataOutputStream.writeTotp(entry: TotpEntry) { writeLimited(entry.itemId); writeLimited(entry.accountName); writeLimited(entry.issuer); writeLimited(entry.secret); writeInt(entry.digits); writeInt(entry.period); writeLimited(entry.algorithm); writeBoolean(entry.pinned); writeInt(entry.sortOrder); writeLong(entry.createdAt); writeLong(entry.updatedAt) }
    private fun DataInputStream.readTotp() = TotpEntry(readLimited(), readLimited(), readLimited(), readLimited(), readInt(), readInt(), readLimited(), readBoolean(), readInt(), readLong(), readLong())
    private fun DataOutputStream.writeSecureItem(entry: SecureItem) { writeLimited(entry.id); writeLimited(entry.type); writeLimited(entry.category); writeLimited(entry.title); writeLimited(entry.fieldsJson); writeLimited(entry.notes); writeLong(entry.createdTime); writeLong(entry.updatedTime) }
    private fun DataInputStream.readSecureItem() = SecureItem(readLimited(), readLimited(), readLimited(), readLimited(), readLimited(), readLimited(), readLong(), readLong())
    private fun DataOutputStream.writeAttachment(entry: VaultAttachment) { writeLimited(entry.id); writeLimited(entry.itemId); writeLimited(entry.filename); writeLimited(entry.mimeType); writeLimited(entry.encryptedPath); writeLimited(entry.hash); writeLong(entry.size); writeLong(entry.createdTime) }
    private fun DataInputStream.readAttachment() = VaultAttachment(readLimited(), readLimited(), readLimited(), readLimited(), readLimited(), readLimited(), readLong(), readLong())

    private data class VaultData(
        val passwords: MutableList<PasswordEntry> = mutableListOf(),
        val totp: MutableList<TotpEntry> = mutableListOf(),
        val secureItems: MutableList<SecureItem> = mutableListOf(),
        val attachments: MutableList<VaultAttachment> = mutableListOf(),
        val passwordGroups: MutableList<PasswordGroup> = mutableListOf(),
        val passwordHistory: MutableList<PasswordHistory> = mutableListOf(),
        val trash: MutableList<TrashEntry> = mutableListOf()
    )

    companion object {
        private val MAGIC = byteArrayOf('K'.code.toByte(), 'S'.code.toByte(), 'V'.code.toByte(), '1'.code.toByte(), 'D'.code.toByte(), 'B'.code.toByte(), '1'.code.toByte(), 0)
        private const val PAYLOAD_VERSION = 7
        private const val IV_BYTES = 12
        private const val TAG_BYTES = 16
        private const val TAG_BITS = 128
        private const val MAX_FIELD_BYTES = 1_048_576
        private const val MAX_ENTRIES = 100_000
        private const val MAX_VAULT_BYTES = 256 * 1024 * 1024

        fun defaultVaultPath(): Path = VaultBootstrapPath.baseDirectory().resolve("vault.ksdb")
    }
}

object VaultBootstrapPath {
    fun baseDirectory(): Path {
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("win") -> System.getenv("APPDATA")?.let(Path::of)
            os.contains("mac") -> Path.of(System.getProperty("user.home"), "Library", "Application Support")
            else -> System.getenv("XDG_CONFIG_HOME")?.let(Path::of) ?: Path.of(System.getProperty("user.home"), ".config")
        }?.resolve("KeyScan") ?: Path.of(System.getProperty("user.home"), "KeyScan")
    }
}
