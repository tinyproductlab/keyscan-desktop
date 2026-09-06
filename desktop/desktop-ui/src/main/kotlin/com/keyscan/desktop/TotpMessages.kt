package com.keyscan.desktop

import androidx.compose.runtime.Composable
import com.keyscan.core.model.AppLanguage

private val totpKeys = listOf("count", "add", "import", "empty", "seconds", "service", "account", "base32", "save", "delete", "title", "uri_label", "invalid_uri")
private val totpMessages = mapOf(
    AppLanguage.ENGLISH to listOf("%d authenticators", "Add TOTP", "Import URI", "No TOTP records yet", "%d s", "Service name", "Account", "Base32 secret", "Save", "Delete", "Add TOTP"),
    AppLanguage.SIMPLIFIED_CHINESE to listOf("%d 个验证器", "添加 TOTP", "导入 URI", "还没有 TOTP 记录", "%d 秒", "服务名称", "账号", "Base32 密钥", "保存", "删除", "添加 TOTP"),
    AppLanguage.TRADITIONAL_CHINESE to listOf("%d 個驗證器", "新增 TOTP", "匯入 URI", "尚無 TOTP 記錄", "%d 秒", "服務名稱", "帳號", "Base32 金鑰", "儲存", "刪除", "新增 TOTP"),
    AppLanguage.JAPANESE to listOf("%d 件の認証コード", "TOTP を追加", "URI を読み込む", "TOTP はまだありません", "%d 秒", "サービス名", "アカウント", "Base32 シークレット", "保存", "削除", "TOTP を追加"),
    AppLanguage.KOREAN to listOf("인증 코드 %d개", "TOTP 추가", "URI 가져오기", "TOTP 기록이 없습니다", "%d초", "서비스 이름", "계정", "Base32 비밀 키", "저장", "삭제", "TOTP 추가"),
    AppLanguage.GERMAN to listOf("%d Authentifikatoren", "TOTP hinzufügen", "URI importieren", "Noch keine TOTP-Einträge", "%d s", "Dienstname", "Konto", "Base32-Geheimnis", "Speichern", "Löschen", "TOTP hinzufügen"),
    AppLanguage.SPANISH to listOf("%d autenticadores", "Añadir TOTP", "Importar URI", "Aún no hay registros TOTP", "%d s", "Nombre del servicio", "Cuenta", "Secreto Base32", "Guardar", "Eliminar", "Añadir TOTP"),
    AppLanguage.FRENCH to listOf("%d authentificateurs", "Ajouter un TOTP", "Importer URI", "Aucun TOTP", "%d s", "Nom du service", "Compte", "Secret Base32", "Enregistrer", "Supprimer", "Ajouter un TOTP"),
    AppLanguage.ITALIAN to listOf("%d autenticatori", "Aggiungi TOTP", "Importa URI", "Nessun TOTP", "%d s", "Nome del servizio", "Account", "Segreto Base32", "Salva", "Elimina", "Aggiungi TOTP"),
    AppLanguage.DUTCH to listOf("%d authenticators", "TOTP toevoegen", "URI importeren", "Nog geen TOTP-vermeldingen", "%d s", "Servicenaam", "Account", "Base32-geheim", "Opslaan", "Verwijderen", "TOTP toevoegen"),
    AppLanguage.PORTUGUESE_BRAZIL to listOf("%d autenticadores", "Adicionar TOTP", "Importar URI", "Ainda não há registros TOTP", "%d s", "Nome do serviço", "Conta", "Segredo Base32", "Salvar", "Excluir", "Adicionar TOTP"),
    AppLanguage.RUSSIAN to listOf("Кодов TOTP: %d", "Добавить TOTP", "Импорт URI", "Записей TOTP пока нет", "%d с", "Название сервиса", "Учётная запись", "Секрет Base32", "Сохранить", "Удалить", "Добавить TOTP")
)

private val totpAdditionalMessages = mapOf(
    AppLanguage.ENGLISH to listOf("otpauth URI", "Invalid otpauth TOTP URI"),
    AppLanguage.SIMPLIFIED_CHINESE to listOf("otpauth URI", "无效的 otpauth TOTP URI"),
    AppLanguage.TRADITIONAL_CHINESE to listOf("otpauth URI", "無效的 otpauth TOTP URI"),
    AppLanguage.JAPANESE to listOf("otpauth URI", "無効な otpauth TOTP URI です"),
    AppLanguage.KOREAN to listOf("otpauth URI", "유효하지 않은 otpauth TOTP URI입니다"),
    AppLanguage.GERMAN to listOf("otpauth-URI", "Ungültige otpauth-TOTP-URI"),
    AppLanguage.SPANISH to listOf("URI otpauth", "URI TOTP de otpauth no válida"),
    AppLanguage.FRENCH to listOf("URI otpauth", "URI TOTP otpauth non valide"),
    AppLanguage.ITALIAN to listOf("URI otpauth", "URI TOTP otpauth non valido"),
    AppLanguage.DUTCH to listOf("otpauth-URI", "Ongeldige otpauth-TOTP-URI"),
    AppLanguage.PORTUGUESE_BRAZIL to listOf("URI otpauth", "URI TOTP otpauth inválida"),
    AppLanguage.RUSSIAN to listOf("URI otpauth", "Недопустимый URI TOTP otpauth")
)

fun totpMessage(language: AppLanguage, key: String, vararg args: Any): String {
    val index = totpKeys.indexOf(key); require(index >= 0)
    val resolved = resolvedLanguage(language)
    val template = if (index < totpMessages.getValue(AppLanguage.ENGLISH).size) {
        (totpMessages[resolved] ?: totpMessages.getValue(AppLanguage.ENGLISH))[index]
    } else {
        (totpAdditionalMessages[resolved] ?: totpAdditionalMessages.getValue(AppLanguage.ENGLISH))[index - totpMessages.getValue(AppLanguage.ENGLISH).size]
    }
    return if (args.isEmpty()) template else template.format(*args)
}
@Composable fun tm(key: String, vararg args: Any): String = totpMessage(LocalAppLanguage.current, key, *args)
fun totpMessageKeys() = totpKeys
