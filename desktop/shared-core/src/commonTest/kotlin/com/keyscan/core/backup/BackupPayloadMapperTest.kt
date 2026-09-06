package com.keyscan.core.backup

import com.keyscan.core.model.PasswordEntry
import com.keyscan.core.model.SecureItem
import com.keyscan.core.model.TotpEntry
import com.keyscan.core.model.PasswordGroup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackupPayloadMapperTest {
    @Test fun domainFieldsSurviveJsonRoundTrip() {
        val password = PasswordEntry("p1", "Mail", "example.com", "alice", "secret", "note", "g1", "o1", "pkg", "account", "remark", 7, 10, 20)
        val otp = TotpEntry("o1", "alice", "Example", "JBSWY3DPEHPK3PXP", 6, 30, "SHA1", true, 2, 11, 21)
        val item = SecureItem("v1", "IDENTITY", "PERSONAL", "Passport", "{\"n\":1}", "private", 12, 22)
        val original = BackupDomainSnapshot(listOf(password), listOf(otp), listOf(item), passwordGroups = listOf(PasswordGroup("g1", "Work", 2, false, 9, 19)))
        val decodedPayload = BackupPayloadV5Codec.decode(BackupPayloadV5Codec.encode(BackupPayloadMapper.toPayload(original)))
        val restored = BackupPayloadMapper.toDomain(decodedPayload)
        assertEquals(original, restored)
    }

    @Test fun localEncryptedAttachmentPathIsNeverExportedAsContentReference() {
        val attachment = com.keyscan.core.model.VaultAttachment("a", "v", "x.pdf", encryptedPath = "private/local/path.ksatt", hash = "abc", size = 1)
        val payload = BackupPayloadMapper.toPayload(BackupDomainSnapshot(vaultAttachments = listOf(attachment)))
        assertNull(payload.vaultAttachments.single().contentReference)
    }

    @Test fun legacyAndroidEntriesWithoutItemIdsAreImportedInsteadOfDropped() {
        val restored = BackupPayloadMapper.toDomain(BackupPayloadV5(
            passwords = listOf(BackupPasswordEntry(title = "Legacy password", password = "secret")),
            otpTokens = listOf(BackupOtpToken(accountName = "legacy@example.com", secret = "JBSWY3DPEHPK3PXP"))
        ))
        assertEquals(1, restored.passwords.size); assertTrue(restored.passwords.single().id.isNotBlank())
        assertEquals("Legacy password", restored.passwords.single().title)
        assertEquals(1, restored.otpTokens.size); assertTrue(restored.otpTokens.single().itemId.isNotBlank())
    }
}
