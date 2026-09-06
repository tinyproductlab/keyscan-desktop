package com.keyscan.desktop

import com.keyscan.core.model.AppLanguage

internal object DesktopHelpDocument {
    fun build(language: AppLanguage): String {
        val resolved = resolvedLanguage(language)
        val ui = uiText(resolved)
        val support = supportText(resolved)
        fun t(key: String, vararg args: Any) = AndroidStringCatalog.text(resolved, key, *args)
        val sections = listOf(
            support.about to support.aboutBody,
            message(resolved, "setup_title") to "${message(resolved, "setup_intro")}\n\n${message(resolved, "custody")}",
            ui.home to "${t("primary_password_locked_summary")}\n${t("primary_vault_locked_summary")}\n${t("primary_otp_locked_summary")}\n${t("operation_view_summary")}",
            ui.passwords to "${t("import_supported_intro")}\n${t("primary_vault_status_local_encrypted")}\n${t("export_security_warning")}",
            ui.totp to "${t("primary_otp_status_key_encrypted")}\n${t("primary_otp_status_secure_storage")}",
            ui.secureVault to "${t("primary_vault_status_local_encrypted")}\n${t("security_attachment_encryption_summary")}",
            ui.generator to t("random_password_desc"),
            ui.security to "${desktopSecurityHelp(resolved)}\n${t("security_biometric_note")}",
            ui.backup to "${t("backup_encryption_note")}\n${t("webdav_desc")}\n${t("data_protection_key_explanation")}",
            ui.trash to "${t("trash_settings_summary")}\n${t("trash_delete_permanently_message")}\n${t("trash_clear_message")}",
            ui.settings to "${t("operation_scope_summary")}\n${t("security_auto_lock_explanation")}\n${t("security_clipboard_explanation")}",
            t("autofill_guide_title") to browserHelp(resolved),
            support.feedback to "$FEEDBACK_ADDRESS\n${feedbackSafety(resolved)}",
        )
        val toc = sections.mapIndexed { index, (title, _) -> "<a href=\"#s${index + 1}\">${escape(title)}</a>" }.joinToString("")
        val content = sections.mapIndexed { index, (title, body) ->
            val paragraphs = body.split(Regex("\\n\\s*\\n|\\n")).filter(String::isNotBlank).joinToString("") { "<p>${escape(it)}</p>" }
            "<section id=\"s${index + 1}\"><h2>${index + 1}. ${escape(title)}</h2>$paragraphs<a class=\"back\" href=\"#top\">↑</a></section>"
        }.joinToString("")
        return """<!doctype html><html lang="${escape(resolved.tag)}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${escape(support.help)}</title><style>
body{margin:0;background:#071426;color:#edf5ff;font:15px/1.65 system-ui,sans-serif}.top{position:sticky;top:0;background:#0a1b31f2;border-bottom:1px solid #224360;padding:16px}.wrap{max-width:900px;margin:auto}.brand{font-size:24px;font-weight:800}.sub{color:#9cb4ce}.toc{display:flex;flex-wrap:wrap;gap:8px;padding:16px 0}.toc a,.back{color:#8de4ed;border:1px solid #27627a;border-radius:9px;padding:6px 10px;text-decoration:none}.content{padding:0 18px 30px}section{background:#0b2038;border:1px solid #1e4666;border-radius:16px;padding:18px;margin:14px 0}h2{margin:0 0 10px;font-size:19px}p{white-space:pre-wrap}.back{display:inline-block;margin-top:8px}
</style></head><body><header class="top" id="top"><div class="wrap"><div class="brand">KeyScan</div><div class="sub">${escape(support.help)} · ${escape(resolved.label)}</div><nav class="toc">$toc</nav></div></header><main class="wrap content">$content</main></body></html>"""
    }

    private fun browserHelp(language: AppLanguage) = localized(language,
        "浏览器扩展仅在 HTTPS 页面请求匹配账号。每次填充都必须由你在 KeyScan 中明确批准；只填写用户名和密码，不会自动提交表单。",
        "瀏覽器擴充功能只會在 HTTPS 頁面要求相符帳號。每次填入都必須由你在 KeyScan 中明確核准；只填入使用者名稱和密碼，不會自動送出表單。",
        "ブラウザー拡張機能は HTTPS ページでのみ一致するアカウントを要求します。入力のたびに KeyScan で明示的な承認が必要です。ユーザー名とパスワードを入力しますが、フォームを自動送信しません。",
        "브라우저 확장 프로그램은 HTTPS 페이지에서만 일치하는 계정을 요청합니다. 입력할 때마다 KeyScan에서 명시적으로 승인해야 하며 사용자 이름과 비밀번호만 채우고 양식을 자동 제출하지 않습니다.",
        "Die Browser-Erweiterung fordert passende Konten nur auf HTTPS-Seiten an. Jedes Ausfüllen muss in KeyScan ausdrücklich bestätigt werden; das Formular wird nie automatisch gesendet.",
        "La extensión solo solicita cuentas coincidentes en páginas HTTPS. Cada relleno requiere aprobación explícita en KeyScan y el formulario nunca se envía automáticamente.",
        "L’extension ne demande des comptes correspondants que sur les pages HTTPS. Chaque remplissage exige votre accord explicite dans KeyScan et le formulaire n’est jamais envoyé automatiquement.",
        "L’estensione richiede account corrispondenti solo nelle pagine HTTPS. Ogni compilazione richiede l’approvazione esplicita in KeyScan e il modulo non viene mai inviato automaticamente.",
        "De browserextensie vraagt alleen op HTTPS-pagina’s om overeenkomende accounts. Elke invulling vereist uw uitdrukkelijke toestemming in KeyScan en het formulier wordt nooit automatisch verzonden.",
        "A extensão solicita contas correspondentes somente em páginas HTTPS. Cada preenchimento exige aprovação explícita no KeyScan e o formulário nunca é enviado automaticamente.",
        "Расширение запрашивает подходящие учётные записи только на страницах HTTPS. Каждое заполнение требует явного подтверждения в KeyScan; форма никогда не отправляется автоматически.",
        "The browser extension requests matching accounts only on HTTPS pages. Every fill requires your explicit approval in KeyScan; it never submits the form automatically.")

    private fun feedbackSafety(language: AppLanguage) = localized(language,
        "请勿发送真实密码、OTP 密钥、数据保护密钥或完整备份。", "請勿傳送真實密碼、OTP 金鑰、資料保護金鑰或完整備份。",
        "実際のパスワード、OTP シークレット、データ保護キー、完全なバックアップは送信しないでください。", "실제 비밀번호, OTP 비밀 키, 데이터 보호 키 또는 전체 백업을 보내지 마세요.",
        "Senden Sie keine echten Passwörter, OTP-Geheimnisse, Datenschutzschlüssel oder vollständigen Sicherungen.", "No envíes contraseñas reales, secretos OTP, claves de protección de datos ni copias completas.",
        "N’envoyez aucun mot de passe réel, secret OTP, clé de protection des données ni sauvegarde complète.", "Non inviare password reali, segreti OTP, chiavi di protezione dei dati o backup completi.",
        "Stuur geen echte wachtwoorden, OTP-geheimen, gegevensbeveiligingssleutels of volledige back-ups.", "Não envie senhas reais, segredos OTP, chaves de proteção de dados nem backups completos.",
        "Не отправляйте настоящие пароли, секреты OTP, ключи защиты данных или полные резервные копии.", "Never send real passwords, OTP secrets, data protection keys, or complete backups.")

    private fun localized(language: AppLanguage, zh: String, tw: String, ja: String, ko: String, de: String, es: String, fr: String, it: String, nl: String, pt: String, ru: String, en: String) = when (language) {
        AppLanguage.SIMPLIFIED_CHINESE -> zh; AppLanguage.TRADITIONAL_CHINESE -> tw; AppLanguage.JAPANESE -> ja; AppLanguage.KOREAN -> ko
        AppLanguage.GERMAN -> de; AppLanguage.SPANISH -> es; AppLanguage.FRENCH -> fr; AppLanguage.ITALIAN -> it
        AppLanguage.DUTCH -> nl; AppLanguage.PORTUGUESE_BRAZIL -> pt; AppLanguage.RUSSIAN -> ru; else -> en
    }

    private fun escape(value: String): String = buildString(value.length) { value.forEach { append(when (it) { '&' -> "&amp;"; '<' -> "&lt;"; '>' -> "&gt;"; '"' -> "&quot;"; '\'' -> "&#39;"; else -> it }) } }
}

internal fun desktopSecurityHelp(language: AppLanguage): String = when (language) {
    AppLanguage.SIMPLIFIED_CHINESE -> "安全状态反映当前电脑的实际配置，包括本地加密、生物识别可用性、WebDAV 备份、备份验证和自动锁定。"
    AppLanguage.TRADITIONAL_CHINESE -> "安全狀態反映目前電腦的實際設定，包括本機加密、生物辨識可用性、WebDAV 備份、備份驗證和自動鎖定。"
    AppLanguage.JAPANESE -> "セキュリティ状態には、ローカル暗号化、生体認証の利用可否、WebDAV バックアップ、バックアップ検証、自動ロックなど、このコンピューターの実際の設定が反映されます。"
    AppLanguage.KOREAN -> "보안 상태는 로컬 암호화, 생체 인증 사용 가능 여부, WebDAV 백업, 백업 검증, 자동 잠금 등 현재 컴퓨터의 실제 구성을 반영합니다."
    AppLanguage.GERMAN -> "Der Sicherheitsstatus zeigt die aktuelle Konfiguration dieses Computers, einschließlich lokaler Verschlüsselung, Biometrie, WebDAV-Sicherung, Sicherungsprüfung und automatischer Sperre."
    AppLanguage.SPANISH -> "El estado de seguridad refleja la configuración actual del equipo, incluido el cifrado local, la biometría, la copia WebDAV, su verificación y el bloqueo automático."
    AppLanguage.FRENCH -> "L’état de sécurité reflète la configuration actuelle de cet ordinateur : chiffrement local, biométrie, sauvegarde WebDAV, vérification des sauvegardes et verrouillage automatique."
    AppLanguage.ITALIAN -> "Lo stato di sicurezza riflette la configurazione attuale del computer, inclusi crittografia locale, biometria, backup WebDAV, verifica dei backup e blocco automatico."
    AppLanguage.DUTCH -> "De beveiligingsstatus toont de huidige configuratie van deze computer, waaronder lokale versleuteling, biometrie, WebDAV-back-up, back-upcontrole en automatische vergrendeling."
    AppLanguage.PORTUGUESE_BRAZIL -> "O status de segurança reflete a configuração atual do computador, incluindo criptografia local, biometria, backup WebDAV, verificação de backup e bloqueio automático."
    AppLanguage.RUSSIAN -> "Состояние безопасности отражает текущую конфигурацию компьютера: локальное шифрование, биометрию, резервное копирование WebDAV, проверку копий и автоблокировку."
    else -> "Security status reflects this computer’s current configuration, including local encryption, biometrics, WebDAV backup, backup verification, and automatic locking."
}

internal fun desktopDatabaseStatus(language: AppLanguage): String = when (language) {
    AppLanguage.SIMPLIFIED_CHINESE -> "本地保险箱使用动态数据库保护密钥"
    AppLanguage.TRADITIONAL_CHINESE -> "本機保險箱使用動態資料庫保護金鑰"
    AppLanguage.JAPANESE -> "ローカル保管庫は動的なデータベース保護キーを使用しています"
    AppLanguage.KOREAN -> "로컬 보관소는 동적 데이터베이스 보호 키를 사용합니다"
    AppLanguage.GERMAN -> "Der lokale Tresor verwendet einen dynamischen Datenbankschutzschlüssel"
    AppLanguage.SPANISH -> "La caja fuerte local usa una clave dinámica de protección de base de datos"
    AppLanguage.FRENCH -> "Le coffre-fort local utilise une clé dynamique de protection de la base de données"
    AppLanguage.ITALIAN -> "La cassaforte locale usa una chiave dinamica di protezione del database"
    AppLanguage.DUTCH -> "De lokale kluis gebruikt een dynamische databasebeveiligingssleutel"
    AppLanguage.PORTUGUESE_BRAZIL -> "O cofre local usa uma chave dinâmica de proteção do banco de dados"
    AppLanguage.RUSSIAN -> "Локальное хранилище использует динамический ключ защиты базы данных"
    else -> "The local vault uses a dynamic database protection key"
}

internal const val FEEDBACK_ADDRESS = "keyscan.feedback@zohomail.com"
