package com.keyscan.desktop

import androidx.compose.runtime.Composable
import com.keyscan.core.model.AppLanguage

private val passwordKeys = listOf("accounts", "import", "export", "add", "all", "new_group", "search", "empty", "empty_hint", "copy", "history", "edit", "delete", "save", "title", "website", "username", "password", "notes", "ungrouped")
private val passwordMessages = mapOf(
    AppLanguage.ENGLISH to listOf("%d accounts", "Import", "Export", "Add password", "All", "New group", "Search title, username, or website", "No password records yet", "Select Add password to create the first record.", "Copy password", "History", "Edit", "Delete", "Save", "Title", "Website domain", "Username", "Password", "Notes", "Ungrouped"),
    AppLanguage.SIMPLIFIED_CHINESE to listOf("%d 个账号", "导入", "导出", "添加密码", "全部", "新建分组", "搜索标题、用户名或网站", "还没有密码记录", "点击“添加密码”创建第一条记录。", "复制密码", "历史", "编辑", "删除", "保存", "标题", "网站域名", "用户名", "密码", "备注", "未分组"),
    AppLanguage.TRADITIONAL_CHINESE to listOf("%d 個帳號", "匯入", "匯出", "新增密碼", "全部", "新增群組", "搜尋標題、使用者名稱或網站", "尚無密碼記錄", "選擇「新增密碼」建立第一筆記錄。", "複製密碼", "歷史記錄", "編輯", "刪除", "儲存", "標題", "網站網域", "使用者名稱", "密碼", "備註", "未分組"),
    AppLanguage.JAPANESE to listOf("%d 件のアカウント", "インポート", "エクスポート", "パスワードを追加", "すべて", "新しいグループ", "タイトル、ユーザー名、サイトを検索", "パスワードはまだありません", "「パスワードを追加」から最初の項目を作成できます。", "パスワードをコピー", "履歴", "編集", "削除", "保存", "タイトル", "Webサイトのドメイン", "ユーザー名", "パスワード", "メモ", "未分類"),
    AppLanguage.KOREAN to listOf("계정 %d개", "가져오기", "내보내기", "비밀번호 추가", "전체", "새 그룹", "제목, 사용자 이름 또는 웹사이트 검색", "비밀번호 기록이 없습니다", "비밀번호 추가를 눌러 첫 기록을 만드세요.", "비밀번호 복사", "기록", "편집", "삭제", "저장", "제목", "웹사이트 도메인", "사용자 이름", "비밀번호", "메모", "그룹 없음"),
    AppLanguage.GERMAN to listOf("%d Konten", "Importieren", "Exportieren", "Passwort hinzufügen", "Alle", "Neue Gruppe", "Titel, Benutzername oder Website suchen", "Noch keine Passwörter", "Wählen Sie „Passwort hinzufügen“, um den ersten Eintrag anzulegen.", "Passwort kopieren", "Verlauf", "Bearbeiten", "Löschen", "Speichern", "Titel", "Website-Domain", "Benutzername", "Passwort", "Notizen", "Ohne Gruppe"),
    AppLanguage.SPANISH to listOf("%d cuentas", "Importar", "Exportar", "Añadir contraseña", "Todas", "Nuevo grupo", "Buscar título, usuario o sitio web", "Aún no hay contraseñas", "Selecciona Añadir contraseña para crear el primer registro.", "Copiar contraseña", "Historial", "Editar", "Eliminar", "Guardar", "Título", "Dominio del sitio web", "Nombre de usuario", "Contraseña", "Notas", "Sin grupo"),
    AppLanguage.FRENCH to listOf("%d comptes", "Importer", "Exporter", "Ajouter un mot de passe", "Tous", "Nouveau groupe", "Rechercher un titre, un identifiant ou un site", "Aucun mot de passe", "Sélectionnez Ajouter un mot de passe pour créer la première entrée.", "Copier le mot de passe", "Historique", "Modifier", "Supprimer", "Enregistrer", "Titre", "Domaine du site", "Nom d’utilisateur", "Mot de passe", "Notes", "Sans groupe"),
    AppLanguage.ITALIAN to listOf("%d account", "Importa", "Esporta", "Aggiungi password", "Tutti", "Nuovo gruppo", "Cerca titolo, nome utente o sito", "Nessuna password", "Seleziona Aggiungi password per creare il primo elemento.", "Copia password", "Cronologia", "Modifica", "Elimina", "Salva", "Titolo", "Dominio del sito", "Nome utente", "Password", "Note", "Senza gruppo"),
    AppLanguage.DUTCH to listOf("%d accounts", "Importeren", "Exporteren", "Wachtwoord toevoegen", "Alle", "Nieuwe groep", "Zoek op titel, gebruikersnaam of website", "Nog geen wachtwoorden", "Kies Wachtwoord toevoegen om de eerste vermelding te maken.", "Wachtwoord kopiëren", "Geschiedenis", "Bewerken", "Verwijderen", "Opslaan", "Titel", "Websitedomein", "Gebruikersnaam", "Wachtwoord", "Notities", "Geen groep"),
    AppLanguage.PORTUGUESE_BRAZIL to listOf("%d contas", "Importar", "Exportar", "Adicionar senha", "Todas", "Novo grupo", "Pesquisar título, usuário ou site", "Ainda não há senhas", "Selecione Adicionar senha para criar o primeiro registro.", "Copiar senha", "Histórico", "Editar", "Excluir", "Salvar", "Título", "Domínio do site", "Nome de usuário", "Senha", "Notas", "Sem grupo"),
    AppLanguage.RUSSIAN to listOf("Учётных записей: %d", "Импорт", "Экспорт", "Добавить пароль", "Все", "Новая группа", "Поиск по названию, имени или сайту", "Паролей пока нет", "Выберите «Добавить пароль», чтобы создать первую запись.", "Копировать пароль", "История", "Изменить", "Удалить", "Сохранить", "Название", "Домен сайта", "Имя пользователя", "Пароль", "Заметки", "Без группы")
)

fun passwordMessage(language: AppLanguage, key: String, vararg args: Any): String {
    val index = passwordKeys.indexOf(key); require(index >= 0)
    val template = (passwordMessages[resolvedLanguage(language)] ?: passwordMessages.getValue(AppLanguage.ENGLISH))[index]
    return if (args.isEmpty()) template else template.format(*args)
}
@Composable fun pm(key: String, vararg args: Any): String = passwordMessage(LocalAppLanguage.current, key, *args)
fun passwordMessageKeys() = passwordKeys
