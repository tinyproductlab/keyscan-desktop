package com.keyscan.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.keyscan.core.model.AppLanguage
import com.keyscan.core.security.SecureClipboard
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage
import java.awt.FileDialog
import java.awt.Frame
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.Locale
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

private val ShareGreen = Color(0xFF11B977)

@Composable
internal fun DesktopShareCenterPage(clipboard: SecureClipboard, clipboardClearSeconds: Int) {
    val language = LocalAppLanguage.current
    fun t(key: String, vararg args: Any) = AndroidStringCatalog.text(language, key, *args)
    val scope = rememberCoroutineScope()
    var qrText by remember { mutableStateOf("") }
    var qrImage by remember { mutableStateOf<BufferedImage?>(null) }
    var shareText by remember { mutableStateOf("") }
    var selectedFiles by remember { mutableStateOf<List<Path>>(emptyList()) }
    var ttl by remember { mutableStateOf(10) }
    var session by remember { mutableStateOf<LanShareSession?>(null) }
    var shareBusy by remember { mutableStateOf(false) }
    var receiveLink by remember { mutableStateOf("") }
    var receiveMessage by remember { mutableStateOf("") }
    var receiveError by remember { mutableStateOf(false) }
    var receiveBusy by remember { mutableStateOf(false) }
    var showQr by remember { mutableStateOf(false) }

    DisposableEffect(Unit) { onDispose { session?.close() } }
    LaunchedEffect(session) {
        while (session != null) {
            delay(1_000)
            if (session?.isExpired() == true) { session?.close(); session = null }
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        SharePanel(Icons.Filled.QrCode2, t("desktop_share_qr_title"), Color(0xFF7C55E7)) {
            OutlinedTextField(qrText, { qrText = it }, Modifier.fillMaxWidth().heightIn(min = 108.dp), label = { Text(t("desktop_share_qr_input")) }, maxLines = 5)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { qrImage = createQrImage(qrText); showQr = true }, enabled = qrText.isNotBlank()) { Icon(Icons.Filled.QrCode2, null); Spacer(Modifier.width(7.dp)); Text(t("share_generate_qr")) }
                OutlinedButton(onClick = { clipboard.copy(qrText, clipboardClearSeconds) }, enabled = qrText.isNotBlank()) { Icon(Icons.Filled.ContentCopy, null); Spacer(Modifier.width(7.dp)); Text(t("history_generated_copy_content")) }
                if (qrImage != null) OutlinedButton(onClick = { saveQrImage(qrImage!!, language) }) { Icon(Icons.Filled.Download, null); Spacer(Modifier.width(7.dp)); Text(t("desktop_save_png")) }
            }
        }

        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val compact = maxWidth < 760.dp
            if (compact) Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                SendSharePanel(shareText, { shareText = it }, selectedFiles, { selectedFiles = chooseShareFiles(language) }, { selectedFiles = emptyList() }, ttl, { ttl = it }, session, shareBusy, onStart = {
                    shareBusy = true; scope.launch { runCatching { withContext(Dispatchers.IO) { LanShareSession.start(selectedFiles, shareText, ttl, language) } }.onSuccess { session?.close(); session = it }.onFailure { receiveError = true; receiveMessage = t("desktop_share_start_failed", t("desktop_unexpected_error")) }; shareBusy = false }
                }, onStop = { session?.close(); session = null }, onCopy = { clipboard.copy(it, clipboardClearSeconds) }, onQr = { qrImage = createQrImage(it); showQr = true })
                ReceiveSharePanel(receiveLink, { receiveLink = it }, receiveBusy, receiveMessage, receiveError) {
                    val destination = chooseReceiveDestination(receiveLink, language) ?: return@ReceiveSharePanel
                    receiveBusy = true; receiveError = false; receiveMessage = t("desktop_receiving")
                    scope.launch { runCatching { withContext(Dispatchers.IO) { receiveSharedFile(receiveLink, destination, language) } }.onSuccess { receiveError = false; receiveMessage = t("desktop_saved_to", it) }.onFailure { receiveError = true; receiveMessage = t("desktop_receive_failed", localizedReason(it, language)) }; receiveBusy = false }
                }
            } else Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.weight(1f)) { SendSharePanel(shareText, { shareText = it }, selectedFiles, { selectedFiles = chooseShareFiles(language) }, { selectedFiles = emptyList() }, ttl, { ttl = it }, session, shareBusy, onStart = {
                    shareBusy = true; scope.launch { runCatching { withContext(Dispatchers.IO) { LanShareSession.start(selectedFiles, shareText, ttl, language) } }.onSuccess { session?.close(); session = it }.onFailure { receiveError = true; receiveMessage = t("desktop_share_start_failed", t("desktop_unexpected_error")) }; shareBusy = false }
                }, onStop = { session?.close(); session = null }, onCopy = { clipboard.copy(it, clipboardClearSeconds) }, onQr = { qrImage = createQrImage(it); showQr = true }) }
                Box(Modifier.weight(1f)) { ReceiveSharePanel(receiveLink, { receiveLink = it }, receiveBusy, receiveMessage, receiveError) {
                    val destination = chooseReceiveDestination(receiveLink, language) ?: return@ReceiveSharePanel
                    receiveBusy = true; receiveError = false; receiveMessage = t("desktop_receiving")
                    scope.launch { runCatching { withContext(Dispatchers.IO) { receiveSharedFile(receiveLink, destination, language) } }.onSuccess { receiveError = false; receiveMessage = t("desktop_saved_to", it) }.onFailure { receiveError = true; receiveMessage = t("desktop_receive_failed", localizedReason(it, language)) }; receiveBusy = false }
                } }
            }
        }
    }

    // Rendered as an in-window Popup rather than a DialogWindow: a second Compose window resolves
    // fonts separately and rasterises every glyph to a smudge on Windows, so the QR sheet stays
    // inside the main scene.
    if (showQr && qrImage != null) Popup(alignment = Alignment.Center, onDismissRequest = { showQr = false }, properties = PopupProperties(focusable = true)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .38f)), contentAlignment = Alignment.Center) {
            Surface(shape = RoundedCornerShape(22.dp), tonalElevation = 6.dp, shadowElevation = 20.dp) {
                Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text(t("desktop_share_qr_sheet_title"), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Image(bufferedImageBitmap(qrImage!!), null, Modifier.size(320.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton({ saveQrImage(qrImage!!, language) }) { Text(t("desktop_save_png")) }
                        Button({ showQr = false }) { Text(t("import_result_done")) }
                    }
                }
            }
        }
    }
}

@Composable private fun SharePanel(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, color: Color, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) { Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(42.dp).background(color.copy(alpha = .13f), RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = color) }; Spacer(Modifier.width(12.dp)); Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        content()
    } }
}

@Composable private fun SendSharePanel(text: String, onText: (String) -> Unit, files: List<Path>, onFiles: () -> Unit, onClear: () -> Unit, ttl: Int, onTtl: (Int) -> Unit, session: LanShareSession?, busy: Boolean, onStart: () -> Unit, onStop: () -> Unit, onCopy: (String) -> Unit, onQr: (String) -> Unit) {
    val language = LocalAppLanguage.current
    fun t(key: String, vararg args: Any) = AndroidStringCatalog.text(language, key, *args)
    SharePanel(Icons.Filled.UploadFile, t("desktop_share_send_title"), ShareGreen) {

    OutlinedTextField(text, onText, Modifier.fillMaxWidth(), label = { Text(t("desktop_share_temp_text")) }, maxLines = 3, enabled = session == null)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(onFiles, enabled = session == null) { Icon(Icons.Filled.AttachFile, null); Spacer(Modifier.width(6.dp)); Text(t("lan_select_files_title")) }; if (files.isNotEmpty()) TextButton(onClear, enabled = session == null) { Text(t("share_clear")) } }
    if (files.isNotEmpty()) Text(files.joinToString("\n") { it.fileName.toString() }, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { Text(t("desktop_share_ttl")); listOf(5, 10, 30).forEach { FilterChip(ttl == it, { onTtl(it) }, { Text(t("desktop_n_minutes", it)) }, enabled = session == null) } }
    if (session == null) Button(onStart, enabled = !busy && (files.isNotEmpty() || text.isNotBlank()), modifier = Modifier.fillMaxWidth()) { Icon(Icons.Filled.WifiTethering, null); Spacer(Modifier.width(7.dp)); Text(if (busy) t("desktop_starting") else t("desktop_start_lan_share")) }
    else {
        Surface(color = ShareGreen.copy(alpha = .09f), shape = RoundedCornerShape(14.dp)) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(t("desktop_share_active"), color = ShareGreen, fontWeight = FontWeight.Bold); Text(t("desktop_share_password_line", session.password)); Text(session.url, fontSize = 12.sp); Text(t("desktop_share_expires_line", session.expiresAt), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button({ onCopy(session.url) }) { Text(t("lan_copy_link")) }; OutlinedButton({ onQr(session.url) }) { Text(t("desktop_show_qr")) }; TextButton(onStop) { Text(t("lan_end_share")) } }
    }
    }
}

@Composable private fun ReceiveSharePanel(link: String, onLink: (String) -> Unit, busy: Boolean, message: String, isError: Boolean, onReceive: () -> Unit) {
    val language = LocalAppLanguage.current
    fun t(key: String, vararg args: Any) = AndroidStringCatalog.text(language, key, *args)
    SharePanel(Icons.Filled.Download, t("desktop_share_receive_title"), Color(0xFF2F7CF6)) {
    val language = LocalAppLanguage.current
    fun t(key: String, vararg args: Any) = AndroidStringCatalog.text(language, key, *args)

    Text(t("desktop_share_receive_hint"), color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedTextField(link, onLink, Modifier.fillMaxWidth(), label = { Text(t("desktop_share_link")) }, singleLine = true)
    Button(onReceive, enabled = !busy && link.startsWith("http"), modifier = Modifier.fillMaxWidth()) { Icon(Icons.Filled.SaveAlt, null); Spacer(Modifier.width(7.dp)); Text(if (busy) t("desktop_receiving") else t("desktop_choose_location_receive")) }
    if (message.isNotBlank()) Text(message, color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
    }
}

private fun createQrImage(text: String): BufferedImage {
    val hints = mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2)
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 900, 900, hints)
    return BufferedImage(matrix.width, matrix.height, BufferedImage.TYPE_INT_RGB).also { image ->
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) image.setRGB(x, y, if (matrix[x, y]) 0x000000 else 0xFFFFFF)
    }
}

private fun bufferedImageBitmap(image: BufferedImage) = ByteArrayOutputStream().use { bytes -> ImageIO.write(image, "png", bytes); SkiaImage.makeFromEncoded(bytes.toByteArray()).toComposeImageBitmap() }
private fun saveQrImage(image: BufferedImage, language: AppLanguage) { val dialog = FileDialog(null as Frame?, AndroidStringCatalog.text(language, "desktop_save_qr_dialog"), FileDialog.SAVE).apply { file = "KeyScan-QRCode.png"; isVisible = true }; val dir = dialog.directory ?: return; val name = dialog.file ?: return; ImageIO.write(image, "png", Path.of(dir, if (name.endsWith(".png", true)) name else "$name.png").toFile()) }
private fun chooseShareFiles(language: AppLanguage): List<Path> { val dialog = FileDialog(null as Frame?, AndroidStringCatalog.text(language, "desktop_choose_share_files"), FileDialog.LOAD).apply { isMultipleMode = true; isVisible = true }; return dialog.files.map { it.toPath() } }
private fun chooseReceiveDestination(link: String, language: AppLanguage): Path? { val suggested = runCatching { URI(link).path.substringAfterLast('/').ifBlank { "KeyScan-Share" } }.getOrDefault("KeyScan-Share"); val dialog = FileDialog(null as Frame?, AndroidStringCatalog.text(language, "desktop_save_received_file"), FileDialog.SAVE).apply { file = URLDecoder.decode(suggested, StandardCharsets.UTF_8); isVisible = true }; return dialog.directory?.let { dir -> dialog.file?.let { Path.of(dir, it) } } }

private class LanShareSession(private val server: HttpServer, private val payload: Path, private val temporary: Boolean, val password: String, val url: String, val expiresAt: Instant) : AutoCloseable {
    fun isExpired() = Instant.now().isAfter(expiresAt)
    override fun close() { server.stop(0); if (temporary) runCatching { Files.deleteIfExists(payload) } }
    companion object {
        fun start(files: List<Path>, text: String, ttlMinutes: Int, language: AppLanguage): LanShareSession {
            val prepared = preparePayload(files, text, language)
            val random = SecureRandom(); val token = ByteArray(18).also(random::nextBytes).joinToString("") { "%02x".format(it) }; val password = (100000 + random.nextInt(900000)).toString()
            val expires = Instant.now().plus(Duration.ofMinutes(ttlMinutes.toLong()))
            val server = HttpServer.create(InetSocketAddress(0), 0); server.executor = Executors.newCachedThreadPool()
            val encodedName = URLEncoder.encode(prepared.first.fileName.toString(), StandardCharsets.UTF_8).replace("+", "%20")
            val path = "/keyscan-share/$token/$encodedName"
            server.createContext(path) { exchange -> serve(exchange, prepared.first, password, expires, language) }; server.start()
            val ip = localIpv4(); val url = "http://$ip:${server.address.port}$path?p=$password"
            return LanShareSession(server, prepared.first, prepared.second, password, url, expires)
        }
        private fun serve(exchange: HttpExchange, payload: Path, password: String, expires: Instant, language: AppLanguage) {
            try {
                val supplied = exchange.requestURI.rawQuery?.split('&')?.mapNotNull { item -> item.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to URLDecoder.decode(it[1], StandardCharsets.UTF_8) } }?.toMap()?.get("p")
                if (Instant.now().isAfter(expires)) return reply(exchange, 410, AndroidStringCatalog.text(language, "desktop_share_expired"))
                if (supplied != password) return reply(exchange, 403, AndroidStringCatalog.text(language, "desktop_share_wrong_password"))
                val name = payload.fileName.toString().replace("\"", "")
                exchange.responseHeaders.add("Content-Type", "application/octet-stream")
                exchange.responseHeaders.add("Content-Disposition", "attachment; filename*=UTF-8''${URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20")}")
                exchange.sendResponseHeaders(200, Files.size(payload)); Files.newInputStream(payload).use { input -> exchange.responseBody.use { input.copyTo(it) } }
            } catch (_: Exception) { runCatching { exchange.close() } }
        }
        private fun reply(exchange: HttpExchange, code: Int, message: String) { val bytes = message.toByteArray(); exchange.sendResponseHeaders(code, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) } }
        private fun preparePayload(files: List<Path>, text: String, language: AppLanguage): Pair<Path, Boolean> {
            if (files.size == 1 && text.isBlank()) return files.first() to false
            if (files.isEmpty()) { val temp = Files.createTempFile("KeyScan-Share-", ".txt"); Files.writeString(temp, text); return temp to true }
            val zip = Files.createTempFile("KeyScan-Share-", ".zip")
            ZipOutputStream(Files.newOutputStream(zip)).use { out ->
                files.forEach { file -> out.putNextEntry(ZipEntry(file.fileName.toString())); Files.newInputStream(file).use { it.copyTo(out) }; out.closeEntry() }
                if (text.isNotBlank()) { out.putNextEntry(ZipEntry(AndroidStringCatalog.text(language, "desktop_share_text_filename"))); out.write(text.toByteArray()); out.closeEntry() }
            }
            return zip to true
        }
        // Interfaces named like a virtual adapter. On Windows these come through as eth*/wlan*,
        // so the friendly display name is matched as well.
        private val VIRTUAL_INTERFACE_NAMES = listOf(
            "vmnet", "vboxnet", "bridge", "docker", "veth", "utun", "tun", "tap", "awdl", "llw", "ppp", "zt", "wg",
        )
        private val VIRTUAL_DISPLAY_NAMES = listOf(
            "vmware", "virtualbox", "hyper-v", "loopback", "tunnel", "tap-", "vpn", "docker", "bluetooth",
        )

        private fun NetworkInterface.isLanSegment(): Boolean {
            if (!isUp || isLoopback || isVirtual || isPointToPoint) return false
            val id = name.lowercase(Locale.ROOT)
            if (VIRTUAL_INTERFACE_NAMES.any { id.startsWith(it) }) return false
            val label = displayName?.lowercase(Locale.ROOT).orEmpty()
            return VIRTUAL_DISPLAY_NAMES.none { label.contains(it) }
        }

        /**
         * The peer has to be able to reach this address. Enumerating interfaces and taking the first
         * hit lands on a VM or VPN subnet on any machine that has one, and the OS routing table is no
         * better: with a VPN up, the default route is the tunnel. So pick a real broadcast segment,
         * lowest interface index first, which is the physical LAN adapter.
         */
        private fun localIpv4(): String = NetworkInterface.getNetworkInterfaces().toList()
            .filter { runCatching { it.isLanSegment() }.getOrDefault(false) }
            .sortedBy { it.index }
            .flatMap { candidate -> candidate.interfaceAddresses.map { candidate to it } }
            .firstOrNull { (_, address) ->
                address.address is Inet4Address && address.address.isSiteLocalAddress && address.broadcast != null
            }?.second?.address?.hostAddress
            ?: NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual && !it.isPointToPoint }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }?.hostAddress
            ?: InetAddress.getLocalHost().hostAddress
    }
}

/** require() throws carry catalog text; anything else could leak a local path or a raw server
 *  response, so it is reported as a generic localized failure. */
internal fun localizedReason(error: Throwable, language: AppLanguage): String =
    (error as? IllegalArgumentException)?.message?.takeIf { it.isNotBlank() }
        ?: AndroidStringCatalog.text(language, "desktop_unexpected_error")

private fun receiveSharedFile(link: String, destination: Path, language: AppLanguage): Path {
    require(link.startsWith("http://") || link.startsWith("https://")) { AndroidStringCatalog.text(language, "desktop_share_bad_link") }
    val request = HttpRequest.newBuilder(URI(link)).timeout(Duration.ofMinutes(10)).GET().build()
    val temp = Files.createTempFile(destination.parent ?: Path.of("."), ".keyscan-receive-", ".tmp")
    try {
        val response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build().send(request, HttpResponse.BodyHandlers.ofFile(temp))
        require(response.statusCode() == 200) { when (response.statusCode()) { 403 -> AndroidStringCatalog.text(language, "desktop_share_wrong_password"); 410 -> AndroidStringCatalog.text(language, "desktop_share_expired"); else -> AndroidStringCatalog.text(language, "desktop_server_returned", response.statusCode()) } }
        return Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING)
    } catch (error: Exception) { Files.deleteIfExists(temp); throw error }
}
