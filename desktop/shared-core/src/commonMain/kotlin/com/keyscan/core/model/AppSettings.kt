package com.keyscan.core.model

enum class AppLanguage(val tag: String, val label: String) {
    SYSTEM("system", "跟随系统"),
    ENGLISH("en", "English"),
    SIMPLIFIED_CHINESE("zh-CN", "简体中文"),
    TRADITIONAL_CHINESE("zh-TW", "繁體中文"),
    JAPANESE("ja", "日本語"),
    KOREAN("ko", "한국어"),
    GERMAN("de", "Deutsch"),
    SPANISH("es", "Español"),
    FRENCH("fr", "Français"),
    ITALIAN("it", "Italiano"),
    DUTCH("nl", "Nederlands"),
    PORTUGUESE_BRAZIL("pt-BR", "Português (Brasil)"),
    RUSSIAN("ru", "Русский")
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val language: AppLanguage = AppLanguage.SYSTEM,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val viewOnly: Boolean = true,
    val autoLockMinutes: Int = 5,
    val clipboardClearSeconds: Int = 30
)
