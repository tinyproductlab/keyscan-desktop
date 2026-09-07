package com.keyscan.desktop

import com.keyscan.core.model.AppLanguage
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalizationTest {
    @Test fun `every supported language has complete nonblank primary navigation`() {
        AppLanguage.entries.filterNot { it == AppLanguage.SYSTEM }.forEach { language ->
            val text = uiText(language); assertEquals(9, text.pages.size); assertFalse(text.pages.any(String::isBlank)); assertFalse(text.safeFree.contains('·')); assertFalse(text.windowsHello.isBlank())
        }
    }
    @Test fun `home tagline follows the specified language rule`() {
        listOf(AppLanguage.ENGLISH, AppLanguage.GERMAN, AppLanguage.SPANISH, AppLanguage.FRENCH, AppLanguage.ITALIAN, AppLanguage.DUTCH, AppLanguage.PORTUGUESE_BRAZIL, AppLanguage.RUSSIAN)
            .forEach { assertEquals("safe free", uiText(it).safeFree, it.name) }
        // "free" here is free of charge, which is what every Latin-script language says.
        // The CJK translations once used the "liberty" sense instead; keep that out.
        assertEquals("安全 免费", uiText(AppLanguage.SIMPLIFIED_CHINESE).safeFree)
        assertEquals("安全 免費", uiText(AppLanguage.TRADITIONAL_CHINESE).safeFree)
        assertEquals("安全 無料", uiText(AppLanguage.JAPANESE).safeFree)
        assertEquals("안전 무료", uiText(AppLanguage.KOREAN).safeFree)
        AppLanguage.entries.forEach { language ->
            val tagline = uiText(language).safeFree
            assertFalse("自由" in tagline || "자유" in tagline, "${language.name} tagline uses the liberty sense of free: $tagline")
        }
    }
    @Test fun `system language distinguishes simplified and traditional Chinese`() {
        assertEquals(AppLanguage.SIMPLIFIED_CHINESE, resolvedLanguage(AppLanguage.SYSTEM, Locale.forLanguageTag("zh-CN")))
        assertEquals(AppLanguage.TRADITIONAL_CHINESE, resolvedLanguage(AppLanguage.SYSTEM, Locale.forLanguageTag("zh-TW")))
        assertEquals(AppLanguage.ENGLISH, resolvedLanguage(AppLanguage.SYSTEM, Locale.forLanguageTag("sv-SE")))
    }
    @Test fun `onboarding and verification messages exist in every language`() {
        AppLanguage.entries.filterNot { it == AppLanguage.SYSTEM }.forEach { language -> messageKeys().forEach { key -> assertFalse(message(language, key).isBlank(), "$language:$key") } }
        assertFalse(message(AppLanguage.KOREAN, "setup_intro").contains("无需")); assertFalse(message(AppLanguage.TRADITIONAL_CHINESE, "custody").contains("数据加密密钥"))
    }
    @Test fun `onboarding labels match actual PIN and data-key limits`() {
        AppLanguage.entries.filterNot { it == AppLanguage.SYSTEM }.forEach { language ->
            assertTrue(message(language, "pin_new").contains("4") && message(language, "pin_new").contains("6"), "$language PIN range")
            assertTrue(message(language, "key_new").contains("8") && message(language, "key_new").contains("32"), "$language data-key range")
        }
    }
    @Test fun `password ledger messages exist in every language`() {
        AppLanguage.entries.filterNot { it == AppLanguage.SYSTEM }.forEach { language -> passwordMessageKeys().forEach { key -> assertFalse(passwordMessage(language, key).isBlank(), "$language:$key") } }
        assertFalse(passwordMessage(AppLanguage.KOREAN, "empty_hint").contains("添加密码")); assertFalse(passwordMessage(AppLanguage.TRADITIONAL_CHINESE, "search").contains("用户名"))
    }
    @Test fun `TOTP messages exist in every language`() {
        AppLanguage.entries.filterNot { it == AppLanguage.SYSTEM }.forEach { language -> totpMessageKeys().forEach { key -> assertFalse(totpMessage(language, key).isBlank(), "$language:$key") } }
        assertFalse(totpMessage(AppLanguage.KOREAN, "empty").contains("记录")); assertFalse(totpMessage(AppLanguage.TRADITIONAL_CHINESE, "service").contains("服务"))
    }
    @Test fun `secure vault messages exist in every language`() {
        AppLanguage.entries.filterNot { it == AppLanguage.SYSTEM }.forEach { language -> vaultMessageKeys().forEach { key -> assertFalse(vaultMessage(language, key).isBlank(), "$language:$key") } }
        assertFalse(vaultMessage(AppLanguage.KOREAN, "no_attachment").contains("附件")); assertFalse(vaultMessage(AppLanguage.TRADITIONAL_CHINESE, "search").contains("搜索"))
    }
    @Test fun `support labels and legal documents exist in every supported language`() {
        AppLanguage.entries.filterNot { it == AppLanguage.SYSTEM }.forEach { language ->
            val support = supportText(language)
            assertTrue(listOf(support.section, support.help, support.policy, support.about, support.feedback, support.close, support.aboutBody).all(String::isNotBlank), language.name)
            val policy = DesktopDocuments.policy(language)
            assertTrue(policy.length > 500, "Legal document is unexpectedly short: $language")
            assertTrue("keyscan.feedback@zohomail.com" in policy, "Feedback email missing: $language")
            val manual = DesktopHelpDocument.build(language)
            assertTrue(manual.countOccurrences("<section id=") == 13, "Desktop help sections missing: $language")
            assertTrue("keyscan.feedback@zohomail.com" in manual, "Help feedback email missing: $language")
            uiText(language).pages.forEach { assertTrue(escapeForTest(it) in manual, "Desktop page absent from help: $language:$it") }
            listOf("锟", "鈫", "鏃", "娴", "鐎", "袪").forEach {
                assertFalse(it in manual, "Possible mojibake in desktop help: $language:$it")
            }
        }
        val english = DesktopHelpDocument.build(AppLanguage.ENGLISH)
        listOf("Enable system AutoFill", "Android settings", "cloud button", "three-dot menu", "floating menu",
            "scanner.db", "watermark", "generation history")
            .forEach { assertFalse(it in english, "Android-only help leaked into desktop manual: $it") }
    }
    @Test fun `backup and trash translations are directly present in every language`() {
        val keys = listOf(
            "local_backup_title", "backup_encryption_note", "local_backup_create", "network_backup_sync_both",
            "local_backup_empty_status", "integrity_ok", "database_corrupted", "status_not_verified",
            "restore_this_backup", "webdav_cloud_added", "backup_complete_title", "recovery_completed_reopen",
            "trash_title", "trash_settings_summary", "trash_clear_title", "operation_scope_summary", "trash_empty",
            "trash_unnamed", "trash_type_password", "trash_type_otp", "trash_type_vault", "trash_restore_success",
            "trash_restore_failed", "trash_action_restore", "trash_action_delete_permanently",
            "trash_delete_permanently_title", "trash_delete_permanently_message", "trash_clear_message", "trash_clear", "cancel",
        )
        AppLanguage.entries.filterNot { it == AppLanguage.SYSTEM }.forEach { language ->
            keys.forEach { key ->
                assertTrue(AndroidStringCatalog.containsDirect(language, key), "Direct Android translation missing: $language:$key")
                assertTrue(AndroidStringCatalog.text(language, key, *formatArguments(key)).isNotBlank(), "$language:$key")
            }
        }
    }

    @Test fun `settings WebDAV operation and biometric translations are directly present`() {
        val keys = listOf(
            "biometric_unlock_enabled", "security_biometric_note", "biometric_unlock_subtitle", "biometric_unlock_unavailable",
            "enable", "disable", "disabled", "operation_mode_title", "operation_view_mode", "operation_view_summary",
            "security_auto_lock", "security_auto_lock_minutes", "clipboard_auto_clear", "seconds_unit", "webdav_unconfigured",
            "webdav_title", "webdav_desc", "main_webdav_title", "backup_webdav_title", "backup_file_prefix_title",
            "backup_file_prefix_hint", "webdav_saved", "connection_failed", "save_webdav_settings", "webdav_complete_config_required",
            "network_test_connection_success", "network_test_connection_failed", "webdav_connection_failed", "test_connection",
            "webdav_address", "username", "webdav_password",
        )
        AppLanguage.entries.filterNot { it == AppLanguage.SYSTEM }.forEach { language -> keys.forEach { key ->
            assertTrue(AndroidStringCatalog.containsDirect(language, key), "Direct settings translation missing: $language:$key")
            assertTrue(AndroidStringCatalog.text(language, key, *formatArguments(key)).isNotBlank(), "$language:$key")
        } }
    }

    @Test fun `password import and export translations are directly present`() {
        val keys = listOf(
            "import_choose_file", "import_password_count", "import_continue_preview", "file_read_failed",
            "import_result_success", "import_conflict_overwrite", "import_result_skipped", "import_failed", "import_unrecognized",
            "export_choose_location", "export_completed", "export_security_warning", "export_failed", "export_failed_short",
            "import_preview_title", "import_supported_intro", "import_preview_limit", "import_conflict_message",
            "import_conflict_skip", "import_conflict_keep_both", "operation_scope_summary", "import_confirm_action",
            "export_confirm_title", "export_confirm_message", "export_action", "cancel",
        )
        AppLanguage.entries.filterNot { it == AppLanguage.SYSTEM }.forEach { language -> keys.forEach { key ->
            assertTrue(AndroidStringCatalog.containsDirect(language, key), "Direct import/export translation missing: $language:$key")
            assertTrue(AndroidStringCatalog.text(language, key, *formatArguments(key)).isNotBlank(), "$language:$key")
        } }
    }

    @Test fun `history and attachment result translations are directly present`() {
        val keys = listOf(
            "password_history_local_notice", "password_history_empty", "vault_attachment_saved",
            "vault_attachment_save_failed", "history_delete_selected_success", "vault_attachment_downloaded",
            "vault_attachment_download_failed", "vault_attachment_open_failed",
            "autofill_auth_title", "autofill_auth_message", "autofill_use_password",
        )
        AppLanguage.entries.filterNot { it == AppLanguage.SYSTEM }.forEach { language -> keys.forEach { key ->
            assertTrue(AndroidStringCatalog.containsDirect(language, key), "Direct history/attachment translation missing: $language:$key")
            assertTrue(AndroidStringCatalog.text(language, key, *formatArguments(key)).isNotBlank(), "$language:$key")
        } }
    }

    @Test fun `home generator and security status translations are directly present`() {
        val keys = listOf(
            "primary_password_status_count", "primary_otp_status_count", "primary_vault_status_count", "primary_backup_summary",
            "backup_latest_none", "recovery_latest_backup_line", "operation_view_mode", "operation_edit_mode",
            "random_password_desc", "autofill_password_length", "uppercase_letters", "lowercase_letters", "digits",
            "special_symbols", "exclude_confusing_chars", "regenerate", "copied", "copy",
            "security_vault_initialized", "security_data_key_active", "security_database_dynamic", "security_attachment_encryption_summary",
            "security_auto_lock_minutes", "primary_security_biometric_enabled", "primary_security_biometric_disabled",
            "security_webdav_key_protected", "security_webdav_not_configured", "security_backup_not_verified",
            "primary_security_duplicate_none", "primary_security_duplicate_found", "security_status_source_note",
        )
        AppLanguage.entries.filterNot { it == AppLanguage.SYSTEM }.forEach { language -> keys.forEach { key ->
            assertTrue(AndroidStringCatalog.containsDirect(language, key), "Direct home/security translation missing: $language:$key")
            assertTrue(AndroidStringCatalog.text(language, key, *formatArguments(key)).isNotBlank(), "$language:$key")
        } }
    }

    private fun formatArguments(key: String): Array<Any> = when (key) {
        "webdav_cloud_added" -> arrayOf("filebackup", 1, 2)
        "security_auto_lock_minutes" -> arrayOf(5)
        "network_test_connection_success" -> arrayOf("20 ms")
        "import_password_count", "import_result_success", "import_result_skipped", "import_preview_limit" -> arrayOf(3)
        "import_failed", "export_failed" -> arrayOf("test")
        "vault_attachment_download_failed" -> arrayOf("test")
        "autofill_auth_message" -> arrayOf("https://example.com", "alice")
        "primary_password_status_count", "primary_otp_status_count", "primary_vault_status_count", "autofill_password_length", "primary_security_duplicate_found" -> arrayOf(3)
        "recovery_latest_backup_line" -> arrayOf("filebackup.ksb")
        else -> emptyArray()
    }

    private fun String.countOccurrences(value: String): Int = windowed(value.length).count { it == value }
    private fun escapeForTest(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
