package com.keyscan.core.exchange

import com.keyscan.core.model.PasswordEntry
import com.keyscan.core.model.PasswordGroup
import com.keyscan.core.vault.EncryptedVaultStore
import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PasswordExchangeTest {
    private val key = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() }).toCharArray()
    private fun vault() = EncryptedVaultStore(Files.createTempDirectory("exchange-vault").resolve("vault.ksdb"), databaseKeyProvider = { block -> block(key.copyOf()) })

    @Test fun `KeyScan CSV round trip preserves multiline formula values and groups`() {
        val vault = vault(); val group = PasswordGroup("g1", "工作")
        vault.replaceAll(EncryptedVaultStore.Snapshot(listOf(PasswordEntry("p1", "=Title", "https://example.com/path", "alice", " p@ss ", "line1\nline2", groupId = group.id, account = "main")), emptyList(), emptyList(), emptyList(), listOf(group)))
        val csv = Files.createTempFile("keyscan", ".csv"); PasswordExchange.exportCsv(vault, csv)
        assertFalse(Files.readString(csv).lineSequence().first().contains("=Title"))
        val parsed = PasswordExchange.importCsv(csv).single()
        assertEquals("=Title", parsed.title); assertEquals(" p@ss ", parsed.password); assertEquals("line1\nline2", parsed.notes); assertEquals("工作", parsed.folder)
        val target = vault(); val result = PasswordExchange.commit(target, listOf(parsed), ConflictStrategy.KEEP_BOTH)
        assertEquals(1, result.added); assertEquals("工作", target.listPasswordGroups().single().name)
    }

    @Test fun `imports Bitwarden login and original KeyScan fields`() {
        val json = Files.createTempFile("bitwarden", ".json")
        Files.writeString(json, """{"encrypted":false,"folders":[{"id":"f1","name":"Personal"}],"items":[{"type":1,"name":"Fallback","folderId":"f1","notes":"note","login":{"username":"fallback","password":"secret","uris":[{"uri":"https://example.test"}]},"fields":[{"name":"keyscan_original_title","value":"Original"},{"name":"keyscan_original_username","value":"alice"},{"name":"account","value":"primary"}]}]}""")
        val item = PasswordExchange.importBitwarden(json).single()
        assertEquals("Original", item.title); assertEquals("alice", item.username); assertEquals("primary", item.account); assertEquals("Personal", item.folder)
    }

    @Test fun `Bitwarden export round trip preserves KeyScan fields and folder`() {
        val source = vault(); val group = PasswordGroup("g", "Personal")
        source.replaceAll(EncryptedVaultStore.Snapshot(listOf(PasswordEntry("p", "Original", "https://example.test", "alice", "secret", "note", group.id, account = "primary")), emptyList(), emptyList(), emptyList(), listOf(group)))
        val file = Files.createTempFile("bitwarden-export", ".json"); PasswordExchange.exportBitwarden(source, file)
        val restored = PasswordExchange.importBitwarden(file).single()
        assertEquals("Original", restored.title); assertEquals("alice", restored.username); assertEquals("primary", restored.account); assertEquals("Personal", restored.folder)
    }

    @Test fun `duplicate strategies skip or overwrite using Android rules`() {
        val vault = vault(); vault.savePassword(PasswordEntry("same", "Mail", "https://example.com/old", "Alice", "old"))
        val incoming = ImportedPassword("Other", "example.com/new", "alice", "", "new", "", "")
        assertEquals(1, PasswordExchange.commit(vault, listOf(incoming), ConflictStrategy.SKIP).skipped)
        assertEquals(1, PasswordExchange.commit(vault, listOf(incoming), ConflictStrategy.OVERWRITE).overwritten)
        assertEquals("same", vault.listPasswords().single().id); assertEquals("new", vault.listPasswords().single().password)
    }
}
