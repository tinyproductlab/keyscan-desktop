package com.keyscan.core.exchange

import com.keyscan.core.model.PasswordEntry
import com.keyscan.core.model.PasswordGroup
import com.keyscan.core.vault.EncryptedVaultStore
import com.keyscan.core.vault.PlaintextExportPolicy
import kotlinx.serialization.json.*
import java.io.PushbackReader
import java.io.Reader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.UUID

data class ImportedPassword(val title: String, val website: String, val username: String, val account: String, val password: String, val notes: String, val folder: String)
enum class ConflictStrategy { SKIP, OVERWRITE, KEEP_BOTH }
data class ImportResult(val added: Int, val overwritten: Int, val skipped: Int)

object PasswordExchange {
    private const val MAX_IMPORT_BYTES = 64L * 1024 * 1024
    private const val MAX_ITEMS = 100_000
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; explicitNulls = true }

    fun importCsv(path: Path): List<ImportedPassword> = checkedReader(path).use { reader ->
        val rows = readCsv(reader); require(rows.isNotEmpty()) { "Missing CSV header" }
        val headers = rows.first().mapIndexed { index, value -> normalizeHeader(value) to index }.toMap()
        fun index(vararg names: String) = names.firstNotNullOfOrNull(headers::get)
        val passwordIndex = index("password", "login_password") ?: error("Unrecognized password CSV header")
        fun value(row: List<String>, vararg names: String): String = index(*names)?.let { row.getOrNull(it) }.orEmpty().let(::restoreCsvValue)
        rows.drop(1).filterNot { row -> row.all(String::isBlank) }.takeChecked().map { row -> ImportedPassword(
            value(row, "name", "title", "item").trim(), value(row, "url", "website", "login_uri", "login_url").trim(),
            value(row, "username", "login_username", "email").trim(), value(row, "account", "account_name", "login_account").trim(),
            restoreCsvValue(row.getOrNull(passwordIndex).orEmpty()), value(row, "notes", "note", "extra").trim(), value(row, "folder", "group", "grouping").trim())
        }
    }

    fun importBitwarden(path: Path): List<ImportedPassword> {
        require(Files.size(path) <= MAX_IMPORT_BYTES) { "Import file is too large" }
        val root = json.parseToJsonElement(Files.readString(path, StandardCharsets.UTF_8).removePrefix("\uFEFF")).jsonObject
        require(root["encrypted"]?.jsonPrimitive?.booleanOrNull != true) { "Encrypted Bitwarden JSON is not supported" }
        val folders = root["folders"]?.jsonArray.orEmpty().mapNotNull { node -> node.jsonObject.let { obj -> obj.string("id")?.let { it to obj.string("name").orEmpty() } } }.toMap()
        return root["items"]?.jsonArray.orEmpty().asSequence().filter { it.jsonObject["type"]?.jsonPrimitive?.intOrNull == 1 }.takeChecked().map { node ->
            val item = node.jsonObject; val login = item["login"]?.jsonObject; val fields = item["fields"]?.jsonArray.orEmpty().map { it.jsonObject }
            fun field(name: String) = fields.firstOrNull { it.string("name")?.equals(name, true) == true }?.string("value")
            ImportedPassword(field("keyscan_original_title") ?: item.string("name").orEmpty(), login?.get("uris")?.jsonArray?.firstOrNull()?.jsonObject?.string("uri").orEmpty(),
                field("keyscan_original_username") ?: login?.string("username").orEmpty(), field("account").orEmpty(), login?.string("password").orEmpty(), item.string("notes").orEmpty(), folders[item.string("folderId")].orEmpty())
        }.toList()
    }

    fun commit(vault: EncryptedVaultStore, imported: List<ImportedPassword>, strategy: ConflictStrategy): ImportResult {
        require(imported.size <= MAX_ITEMS); val snapshot = vault.snapshot(); val passwords = snapshot.passwords.toMutableList(); val groups = snapshot.passwordGroups.toMutableList()
        var added = 0; var overwritten = 0; var skipped = 0
        imported.forEach { item ->
            val duplicateIndex = passwords.indexOfFirst { duplicate(it, item) }
            if (duplicateIndex >= 0 && strategy == ConflictStrategy.SKIP) { skipped++; return@forEach }
            val groupId = item.folder.takeIf(String::isNotBlank)?.let { folder -> groups.firstOrNull { it.name.equals(folder, true) }?.id ?: UUID.randomUUID().toString().also { groups += PasswordGroup(it, folder, groups.size) } }
            val now = System.currentTimeMillis(); val existing = passwords.getOrNull(duplicateIndex)
            val mapped = PasswordEntry(existing?.id ?: UUID.randomUUID().toString(), item.title, item.website, item.username, item.password, item.notes, groupId, account = item.account.ifBlank { null }, remark = item.title, createdAt = existing?.createdAt ?: now, updatedAt = now)
            if (duplicateIndex >= 0 && strategy == ConflictStrategy.OVERWRITE) { passwords[duplicateIndex] = mapped; overwritten++ } else { passwords += mapped; added++ }
        }
        vault.replaceAll(snapshot.copy(passwords = passwords, passwordGroups = groups)); return ImportResult(added, overwritten, skipped)
    }

    fun exportCsv(vault: EncryptedVaultStore, destination: Path) {
        val snapshot = vault.snapshot(); val groups = snapshot.passwordGroups.associate { it.id to it.name }; val out = StringBuilder("\uFEFFtitle,website,username,account,password,notes,folder\n")
        snapshot.passwords.forEach { entry -> out.append(listOf(entry.title, entry.websiteDomain, entry.username, entry.account.orEmpty(), entry.password, entry.notes, groups[entry.groupId].orEmpty()).joinToString(",") { csvCell(it) }).append('\n') }
        atomicWrite(destination, out.toString().toByteArray(StandardCharsets.UTF_8))
    }

    fun exportBitwarden(vault: EncryptedVaultStore, destination: Path) {
        val snapshot = vault.snapshot(); val folderIds = snapshot.passwordGroups.associate { it.id to UUID.randomUUID().toString() }
        val root = buildJsonObject {
            put("encrypted", false)
            putJsonArray("folders") { snapshot.passwordGroups.forEach { group -> addJsonObject { put("id", folderIds.getValue(group.id)); put("name", group.name) } } }
            putJsonArray("items") { snapshot.passwords.forEach { entry -> addJsonObject {
                put("id", UUID.randomUUID().toString()); put("organizationId", JsonNull); put("folderId", entry.groupId?.let(folderIds::get)?.let(::JsonPrimitive) ?: JsonNull)
                put("type", 1); put("reprompt", 0); put("name", entry.title.ifBlank { entry.websiteDomain.ifBlank { entry.username.ifBlank { "KeyScan Login" } } }); put("notes", entry.notes); put("favorite", false)
                putJsonObject("login") { put("username", entry.username.ifBlank { entry.account.orEmpty() }); put("password", entry.password); put("totp", JsonNull); putJsonArray("uris") { if (entry.websiteDomain.isNotBlank()) addJsonObject { put("match", JsonNull); put("uri", entry.websiteDomain) } } }
                putJsonArray("fields") {
                    addJsonObject { put("name", "keyscan_original_title"); put("value", entry.title); put("type", 0) }
                    addJsonObject { put("name", "keyscan_original_username"); put("value", entry.username); put("type", 0) }
                    entry.account?.takeIf(String::isNotBlank)?.let { account -> addJsonObject { put("name", "account"); put("value", account); put("type", 0) } }
                }
                putJsonArray("collectionIds") {}
            } } }
        }
        atomicWrite(destination, json.encodeToString(JsonElement.serializer(), root).toByteArray(StandardCharsets.UTF_8))
    }

    private fun checkedReader(path: Path): Reader { require(Files.isRegularFile(path) && Files.size(path) <= MAX_IMPORT_BYTES); return Files.newBufferedReader(path, StandardCharsets.UTF_8) }
    private fun <T> Sequence<T>.takeChecked(): Sequence<T> = mapIndexed { index, item -> require(index < MAX_ITEMS) { "Import contains too many items" }; item }
    private fun <T> List<T>.takeChecked(): List<T> { require(size <= MAX_ITEMS); return this }
    private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
    private fun normalizeHeader(value: String) = value.removePrefix("\uFEFF").trim().lowercase(Locale.ROOT)
    private fun restoreCsvValue(value: String): String = when { value.length >= 3 && value[0] == '\'' && value[1] == '\'' && value[2] in "=+-@" -> value.drop(1); value.length >= 2 && value[0] == '\'' && value[1] in "=+-@" -> value.drop(1); else -> value }
    private fun csvCell(value: String): String {
        val protected = if (value.isNotEmpty() && value[0] in "=+-@") "'$value" else if (value.length >= 2 && value[0] == '\'' && value[1] in "=+-@") "'$value" else value
        val escaped = protected.replace("\"", "\"\"")
        return if (escaped.any { it == ',' || it == '\"' || it == '\n' || it == '\r' }) "\"$escaped\"" else escaped
    }
    private fun duplicate(entry: PasswordEntry, item: ImportedPassword): Boolean {
        fun norm(value: String?) = value.orEmpty().trim().lowercase(Locale.ROOT)
        fun domain(value: String?) = norm(value).removePrefix("https://").removePrefix("http://").substringBefore('/')
        val user = norm(item.username.ifBlank { item.account })
        val existingUser = norm(entry.username.ifBlank { entry.account.orEmpty() })
        return user.isNotEmpty() && ((domain(item.website).isNotEmpty() && domain(item.website) == domain(entry.websiteDomain) && user == existingUser)
            || (norm(item.title).isNotEmpty() && norm(item.title) == norm(entry.title) && user == existingUser))
    }
    private fun readCsv(source: Reader): List<List<String>> { val reader = PushbackReader(source, 1); val rows = mutableListOf<List<String>>(); var row = mutableListOf<String>(); val field = StringBuilder(); var quoted = false; var afterQuote = false; while (true) { val next = reader.read(); if (next < 0) break; val ch = next.toChar(); if (quoted) { if (ch == '\"') { val following = reader.read(); if (following == '\"'.code) field.append('\"') else { quoted = false; afterQuote = true; if (following >= 0) reader.unread(following) } } else field.append(ch); continue }; if (afterQuote) { when { ch == ',' -> { row += field.toString(); field.clear(); afterQuote = false }; ch == '\n' || ch == '\r' -> { if (ch == '\r') { val following = reader.read(); if (following >= 0 && following != '\n'.code) reader.unread(following) }; row += field.toString(); rows += row; row = mutableListOf(); field.clear(); afterQuote = false }; !ch.isWhitespace() -> error("Invalid text after quoted field") }; continue }; when (ch) { '\"' -> { require(field.isEmpty()) { "Invalid quote in unquoted field" }; quoted = true }; ',' -> { row += field.toString(); field.clear() }; '\n', '\r' -> { if (ch == '\r') { val following = reader.read(); if (following >= 0 && following != '\n'.code) reader.unread(following) }; row += field.toString(); rows += row; row = mutableListOf(); field.clear() }; else -> field.append(ch) } }; require(!quoted) { "Unterminated quoted field" }; if (afterQuote || field.isNotEmpty() || row.isNotEmpty()) { row += field.toString(); rows += row }; return rows }
    private fun atomicWrite(path: Path, bytes: ByteArray) {
        var temp: Path? = null
        try {
            PlaintextExportPolicy.requireOutsideManagedData(path)
            val parent = path.toAbsolutePath().normalize().parent ?: error("Destination requires a parent")
            Files.createDirectories(parent); temp = Files.createTempFile(parent, "export-", ".tmp")
            Files.write(temp, bytes)
            try { Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: Exception) { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING) }
        } finally {
            bytes.fill(0); temp?.let(Files::deleteIfExists)
        }
    }
}
