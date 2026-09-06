package com.keyscan.core.backup

import kotlin.test.*

class BackupPayloadV5Test {
    @Test fun preservesAndroidKeysUnicodeAndVersionFive() {
        val payload = BackupPayloadV5(
            passwordGroups = listOf(BackupPasswordGroup("group-1", "工作", 1, false, 10, 20)),
            passwords = listOf(BackupPasswordEntry("pw-1", title = "邮箱", websiteDomain = "例子.测试", username = "用户", password = "秘密🔐", groupId = "group-1")),
            otpTokens = listOf(BackupOtpToken("otp-1", "用户@example.com", "示例", "JBSWY3DPEHPK3PXP")),
            vaultItems = listOf(BackupVaultItem("vault-1", title = "证件", fields = "{\"号码\":\"一二三\"}")),
            vaultAttachments = listOf(BackupVaultAttachment("a-1", "vault-1", "证件.pdf", "application/pdf", 12, "abc", "attachments/a-1"))
        )
        val encoded = BackupPayloadV5Codec.encode(payload)
        val text = encoded.toString(Charsets.UTF_8)
        assertTrue(text.contains("\"version\":5")); assertTrue(text.contains("\"vaultAttachments\"")); assertTrue(text.contains("秘密🔐"))
        assertEquals(payload, BackupPayloadV5Codec.decode(encoded))
    }

    @Test fun missingNullableFieldsAndUnknownFutureKeysAreAccepted() {
        val raw = """{"version":5,"records":[],"passwordGroups":[],"passwords":[{"itemId":"1","unknown":"future"}],"otpTokens":[],"passwordGenerations":[],"vaultItems":[],"vaultAttachments":[]}""".toByteArray()
        val decoded = BackupPayloadV5Codec.decode(raw)
        assertEquals("1", decoded.passwords.single().itemId); assertNull(decoded.passwords.single().password)
    }

    @Test fun wrongVersionAndMalformedJsonAreRejected() {
        assertFails { BackupPayloadV5Codec.decode("{\"version\":6}".toByteArray()) }
        assertFails { BackupPayloadV5Codec.decode("not-json".toByteArray()) }
    }
}
