package com.keyscan.desktop

import com.keyscan.core.model.AppLanguage
import com.keyscan.core.vault.VaultBootstrapPath
import java.awt.Desktop
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files

internal object DesktopDocuments {
    fun policy(language: AppLanguage): String {
        val resolved = resolvedLanguage(language)
        val tag = if (resolved == AppLanguage.SYSTEM) "en" else resolved.tag
        val path = "/legal/$tag/policy.txt"
        return checkNotNull(javaClass.getResourceAsStream(path)) { "Missing legal document: $path" }
            .bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    }

    fun openHelp(language: AppLanguage) {
        require(Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
        val docs = VaultBootstrapPath.baseDirectory().resolve("documents")
        Files.createDirectories(docs)
        val target = docs.resolve("KeyScan-help.html")
        Files.writeString(target, DesktopHelpDocument.build(language), StandardCharsets.UTF_8)
        Desktop.getDesktop().browse(target.toUri())
    }

    fun openFeedback() {
        require(Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.MAIL))
        Desktop.getDesktop().mail(URI.create("mailto:$FEEDBACK_ADDRESS?subject=KeyScan%20Desktop%20Feedback"))
    }
}

data class SupportText(
    val section: String,
    val help: String,
    val policy: String,
    val about: String,
    val feedback: String,
    val close: String,
    val aboutBody: String,
    val openFailed: String,
)

internal fun supportText(language: AppLanguage): SupportText = when (resolvedLanguage(language)) {
    AppLanguage.SIMPLIFIED_CHINESE -> SupportText("帮助与信息", "帮助说明书", "用户协议与隐私政策", "关于 KeyScan", "意见反馈", "关闭", "KeyScan 桌面版 · 无需账号 · 无 Pro 限制\n密码由你掌控，自由无需登录。\n反馈邮箱：$FEEDBACK_ADDRESS", "无法打开，请检查系统默认浏览器或邮件应用。")
    AppLanguage.TRADITIONAL_CHINESE -> SupportText("說明與資訊", "說明手冊", "使用者協議與隱私政策", "關於 KeyScan", "意見回饋", "關閉", "KeyScan 桌面版 · 無需帳號 · 無 Pro 限制\n密碼由你掌控，自由無需登入。\n意見信箱：$FEEDBACK_ADDRESS", "無法開啟，請檢查系統預設瀏覽器或郵件應用程式。")
    AppLanguage.JAPANESE -> SupportText("ヘルプと情報", "ヘルプマニュアル", "利用規約とプライバシーポリシー", "KeyScan について", "フィードバック", "閉じる", "KeyScan デスクトップ版・アカウント不要・Pro 制限なし\nパスワードはあなたが管理し、自由にログインは不要です。\n連絡先: $FEEDBACK_ADDRESS", "開けません。既定のブラウザーまたはメールアプリを確認してください。")
    AppLanguage.KOREAN -> SupportText("도움말 및 정보", "도움말 설명서", "이용 약관 및 개인정보 처리방침", "KeyScan 정보", "의견 보내기", "닫기", "KeyScan 데스크톱 · 계정 불필요 · Pro 제한 없음\n비밀번호는 사용자가 관리하며 자유에는 로그인이 필요 없습니다.\n문의: $FEEDBACK_ADDRESS", "열 수 없습니다. 기본 브라우저 또는 메일 앱을 확인하세요.")
    AppLanguage.GERMAN -> SupportText("Hilfe und Informationen", "Hilfehandbuch", "Nutzungsbedingungen und Datenschutz", "Über KeyScan", "Feedback", "Schließen", "KeyScan Desktop · kein Konto · keine Pro-Beschränkung\nIhre Passwörter bleiben unter Ihrer Kontrolle; Freiheit erfordert keine Anmeldung.\nKontakt: $FEEDBACK_ADDRESS", "Öffnen nicht möglich. Standardbrowser oder E-Mail-App prüfen.")
    AppLanguage.SPANISH -> SupportText("Ayuda e información", "Manual de ayuda", "Acuerdo y privacidad", "Acerca de KeyScan", "Comentarios", "Cerrar", "KeyScan Desktop · sin cuenta · sin límites Pro\nTus contraseñas están bajo tu control; la libertad no requiere iniciar sesión.\nContacto: $FEEDBACK_ADDRESS", "No se pudo abrir. Comprueba el navegador o correo predeterminado.")
    AppLanguage.FRENCH -> SupportText("Aide et informations", "Manuel d’aide", "Conditions et confidentialité", "À propos de KeyScan", "Commentaires", "Fermer", "KeyScan Desktop · sans compte · aucune limite Pro\nVos mots de passe restent sous votre contrôle ; la liberté ne nécessite aucune connexion.\nContact : $FEEDBACK_ADDRESS", "Ouverture impossible. Vérifiez le navigateur ou l’application de messagerie par défaut.")
    AppLanguage.ITALIAN -> SupportText("Guida e informazioni", "Manuale di aiuto", "Accordo e privacy", "Informazioni su KeyScan", "Feedback", "Chiudi", "KeyScan Desktop · nessun account · nessun limite Pro\nLe password restano sotto il tuo controllo; la libertà non richiede l’accesso.\nContatto: $FEEDBACK_ADDRESS", "Impossibile aprire. Controlla il browser o l’app e-mail predefinita.")
    AppLanguage.DUTCH -> SupportText("Hulp en informatie", "Help-handleiding", "Overeenkomst en privacy", "Over KeyScan", "Feedback", "Sluiten", "KeyScan Desktop · geen account · geen Pro-beperking\nUw wachtwoorden blijven onder uw beheer; vrijheid vereist geen aanmelding.\nContact: $FEEDBACK_ADDRESS", "Kan niet openen. Controleer de standaardbrowser of e-mailapp.")
    AppLanguage.PORTUGUESE_BRAZIL -> SupportText("Ajuda e informações", "Manual de ajuda", "Contrato e privacidade", "Sobre o KeyScan", "Feedback", "Fechar", "KeyScan Desktop · sem conta · sem limites Pro\nSuas senhas ficam sob seu controle; liberdade não exige login.\nContato: $FEEDBACK_ADDRESS", "Não foi possível abrir. Verifique o navegador ou app de e-mail padrão.")
    AppLanguage.RUSSIAN -> SupportText("Справка и сведения", "Руководство", "Соглашение и конфиденциальность", "О KeyScan", "Обратная связь", "Закрыть", "KeyScan Desktop · без учётной записи · без ограничений Pro\nВаши пароли остаются под вашим контролем; свобода не требует входа.\nКонтакт: $FEEDBACK_ADDRESS", "Не удалось открыть. Проверьте браузер или почтовое приложение по умолчанию.")
    else -> SupportText("Help & information", "Help manual", "User agreement & privacy policy", "About KeyScan", "Feedback", "Close", "KeyScan Desktop · no account · no Pro restrictions\nYour passwords stay under your control; freedom requires no login.\nFeedback: $FEEDBACK_ADDRESS", "Could not open it. Check the default browser or mail application.")
}
