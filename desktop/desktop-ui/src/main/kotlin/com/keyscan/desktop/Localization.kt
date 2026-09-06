package com.keyscan.desktop

import androidx.compose.runtime.staticCompositionLocalOf
import com.keyscan.core.model.AppLanguage
import java.util.Locale

data class UiText(
    val safeFree: String, val home: String, val passwords: String, val totp: String, val secureVault: String,
    val generator: String, val security: String, val backup: String, val trash: String, val settings: String,
    val viewOnly: String, val editMode: String, val lock: String, val language: String, val theme: String,
    val system: String, val light: String, val dark: String, val windowsHello: String
) { val pages get() = listOf(home, passwords, totp, secureVault, generator, security, backup, trash, settings) }

val LocalUiText = staticCompositionLocalOf { uiText(AppLanguage.ENGLISH) }

fun resolvedLanguage(language: AppLanguage, locale: Locale = Locale.getDefault()): AppLanguage {
    if (language != AppLanguage.SYSTEM) return language
    return when (locale.language.lowercase(Locale.ROOT)) {
        "zh" -> if (locale.country.equals("TW", true) || locale.country.equals("HK", true) || locale.script.equals("Hant", true)) AppLanguage.TRADITIONAL_CHINESE else AppLanguage.SIMPLIFIED_CHINESE
        "ja" -> AppLanguage.JAPANESE; "ko" -> AppLanguage.KOREAN; "de" -> AppLanguage.GERMAN; "es" -> AppLanguage.SPANISH
        "fr" -> AppLanguage.FRENCH; "it" -> AppLanguage.ITALIAN; "nl" -> AppLanguage.DUTCH; "pt" -> AppLanguage.PORTUGUESE_BRAZIL; "ru" -> AppLanguage.RUSSIAN
        else -> AppLanguage.ENGLISH
    }
}

fun uiText(language: AppLanguage): UiText = when (resolvedLanguage(language)) {
    AppLanguage.SIMPLIFIED_CHINESE -> UiText("安全 免费", "首页", "密码账本", "TOTP", "安全保险箱", "随机密码", "安全状态", "安全备份", "回收站", "设置", "仅查看", "编辑模式", "锁屏", "语言", "主题", "跟随系统", "浅色", "深色", "Windows Hello")
    AppLanguage.TRADITIONAL_CHINESE -> UiText("安全 免費", "首頁", "密碼帳本", "TOTP", "安全保險箱", "隨機密碼", "安全狀態", "安全備份", "垃圾桶", "設定", "僅檢視", "編輯模式", "鎖定", "語言", "主題", "跟隨系統", "淺色", "深色", "Windows Hello")
    AppLanguage.JAPANESE -> UiText("安全 無料", "ホーム", "パスワード", "TOTP", "セキュア保管庫", "パスワード生成", "セキュリティ", "安全なバックアップ", "ごみ箱", "設定", "表示のみ", "編集モード", "ロック", "言語", "テーマ", "システム", "ライト", "ダーク", "Windows Hello")
    AppLanguage.KOREAN -> UiText("안전 무료", "홈", "비밀번호", "TOTP", "보안 금고", "비밀번호 생성", "보안 상태", "안전 백업", "휴지통", "설정", "보기 전용", "편집 모드", "잠금", "언어", "테마", "시스템 설정", "라이트", "다크", "Windows Hello")
    AppLanguage.GERMAN -> UiText("safe free", "Start", "Passwörter", "TOTP", "Sicherer Tresor", "Generator", "Sicherheitsstatus", "Sichere Sicherung", "Papierkorb", "Einstellungen", "Nur ansehen", "Bearbeiten", "Sperren", "Sprache", "Design", "System", "Hell", "Dunkel", "Windows Hello")
    AppLanguage.SPANISH -> UiText("safe free", "Inicio", "Contraseñas", "TOTP", "Bóveda segura", "Generador", "Estado de seguridad", "Copia segura", "Papelera", "Ajustes", "Solo lectura", "Modo edición", "Bloquear", "Idioma", "Tema", "Sistema", "Claro", "Oscuro", "Windows Hello")
    AppLanguage.FRENCH -> UiText("safe free", "Accueil", "Mots de passe", "TOTP", "Coffre sécurisé", "Générateur", "État de sécurité", "Sauvegarde sécurisée", "Corbeille", "Paramètres", "Lecture seule", "Mode édition", "Verrouiller", "Langue", "Thème", "Système", "Clair", "Sombre", "Windows Hello")
    AppLanguage.ITALIAN -> UiText("safe free", "Home", "Password", "TOTP", "Cassaforte", "Generatore", "Stato sicurezza", "Backup sicuro", "Cestino", "Impostazioni", "Solo visualizzazione", "Modalità modifica", "Blocca", "Lingua", "Tema", "Sistema", "Chiaro", "Scuro", "Windows Hello")
    AppLanguage.DUTCH -> UiText("safe free", "Start", "Wachtwoorden", "TOTP", "Veilige kluis", "Generator", "Beveiligingsstatus", "Veilige back-up", "Prullenbak", "Instellingen", "Alleen bekijken", "Bewerkmodus", "Vergrendelen", "Taal", "Thema", "Systeem", "Licht", "Donker", "Windows Hello")
    AppLanguage.PORTUGUESE_BRAZIL -> UiText("safe free", "Início", "Senhas", "TOTP", "Cofre seguro", "Gerador", "Status de segurança", "Backup seguro", "Lixeira", "Configurações", "Somente leitura", "Modo de edição", "Bloquear", "Idioma", "Tema", "Sistema", "Claro", "Escuro", "Windows Hello")
    AppLanguage.RUSSIAN -> UiText("safe free", "Главная", "Пароли", "TOTP", "Защищённое хранилище", "Генератор", "Состояние безопасности", "Безопасная копия", "Корзина", "Настройки", "Только просмотр", "Редактирование", "Блокировать", "Язык", "Тема", "Системная", "Светлая", "Тёмная", "Windows Hello")
    else -> UiText("safe free", "Home", "Passwords", "TOTP", "Secure Vault", "Generator", "Security Status", "Secure Backup", "Trash", "Settings", "View only", "Edit mode", "Lock", "Language", "Theme", "System", "Light", "Dark", "Windows Hello")
}
