package com.keyscan.core.model

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppSettingsStoreTest {
    @Test fun `defaults to recommended view mode and persists bounded settings`() {
        val file = Files.createTempDirectory("settings").resolve("settings.properties"); val store = AppSettingsStore(file)
        assertTrue(store.load().viewOnly)
        store.save(AppSettings(AppLanguage.JAPANESE, ThemeMode.DARK, false, 999, 1))
        assertEquals(AppSettings(AppLanguage.JAPANESE, ThemeMode.DARK, false, 120, 5), store.load())
    }
}
