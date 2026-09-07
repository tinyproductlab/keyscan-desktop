package com.keyscan.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import com.keyscan.core.security.VaultAuthenticationException
import com.keyscan.core.security.VaultBootstrapStore
import com.keyscan.core.security.VaultSession
import com.keyscan.core.security.KeyDerivation
import com.keyscan.core.security.WindowsDpapiSecretStore
import com.keyscan.core.security.AutoLockPolicy
import com.keyscan.core.security.SecureClipboard
import com.keyscan.core.security.PasswordGenerator
import com.keyscan.core.security.PasswordGeneratorOptions
import com.keyscan.core.security.MacKeychainSecretStore
import com.keyscan.core.model.PasswordEntry
import com.keyscan.core.model.TotpEntry
import com.keyscan.core.model.SecureItem
import com.keyscan.core.model.AppSettings
import com.keyscan.core.model.AppSettingsStore
import com.keyscan.core.model.PasswordGroup
import com.keyscan.core.model.PasswordHistory
import com.keyscan.core.model.TrashEntry
import com.keyscan.core.model.TrashType
import com.keyscan.core.totp.TotpGenerator
import com.keyscan.core.vault.EncryptedVaultStore
import com.keyscan.core.vault.EncryptedAttachmentStore
import com.keyscan.core.backup.BackupHistoryEntry
import com.keyscan.core.backup.BackupHistoryManager
import com.keyscan.core.backup.BackupIntegrity
import com.keyscan.core.backup.LocalBackupService
import com.keyscan.core.webdav.*
import com.keyscan.core.exchange.PasswordExchange
import com.keyscan.core.exchange.ImportedPassword
import com.keyscan.core.exchange.ConflictStrategy
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.UUID
import java.security.SecureRandom
import java.time.Duration
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import java.io.InputStream
import java.nio.file.Path
import java.nio.file.Files
import java.util.Comparator
import java.util.zip.ZipInputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.sun.jna.Native
import com.sun.jna.Pointer

private val KeyBlue = Color(0xFF3979ED)
private val KeyGreen = Color(0xFF20B982)
private val KeyPurple = Color(0xFF7957D5)
private val AndroidPrimaryPageBackground = Color(0xFFF7FAFE)
private val AndroidPrimaryTitle = Color(0xFF142A4A)
private val AndroidBorderSoft = Color(0xFFDCE6F0)
private val ScreenInk = Color(0xFF070D1C)
private val PanelInk = Color(0xFF101A30)
private val DESKTOP_VERSION = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "0.1.66" else "1.0.20"
private enum class AppScreen { SETUP, UNLOCKED, LOCKED }
private data class CloudBackupEntry(val targetId: String, val targetName: String, val file: WebDavRemoteFile)
private const val DESKTOP_PAGE_HOME = "home"
private const val DESKTOP_PAGE_PASSWORDS = "passwords"
private const val DESKTOP_PAGE_TOTP = "totp"
private const val DESKTOP_PAGE_VAULT = "vault"
private const val DESKTOP_PAGE_GENERATOR = "generator"
private const val DESKTOP_PAGE_SECURITY = "security"
private const val DESKTOP_PAGE_SHARE = "share"
private const val DESKTOP_PAGE_DATA = "data"
private const val DESKTOP_PAGE_SETTINGS = "settings"
private val DesktopHomePrimaryPages = setOf(DESKTOP_PAGE_HOME, DESKTOP_PAGE_PASSWORDS, DESKTOP_PAGE_TOTP, DESKTOP_PAGE_VAULT, DESKTOP_PAGE_GENERATOR)
private data class DesktopModule(val id: String, val title: String, val icon: ImageVector, val color: Color)

private fun installBundledNativeHostIfAvailable() {
    val os = System.getProperty("os.name").orEmpty()
    if (!os.startsWith("Windows", ignoreCase = true)) return
    val loader = Thread.currentThread().contextClassLoader ?: object {}.javaClass.classLoader
    val localAppData = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() } ?: return
    val appData = System.getenv("APPDATA")?.takeIf { it.isNotBlank() } ?: return
    val installRoot = Path.of(localAppData, "KeyScan", "native-host", DESKTOP_VERSION)
    runCatching {
        val stream = bundledNativeHostZipStream(loader) ?: run {
            logNativeHostInstall("zip not found")
            return@runCatching
        }
        if (Files.exists(installRoot)) {
            Files.walk(installRoot).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
        Files.createDirectories(installRoot)
        ZipInputStream(stream).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val output = installRoot.resolve(entry.name).normalize()
                if (!output.startsWith(installRoot)) error("Invalid native-host archive entry")
                if (entry.isDirectory) Files.createDirectories(output)
                else {
                    Files.createDirectories(output.parent)
                    Files.copy(zip, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
                zip.closeEntry()
            }
        }
        val hostExe = installRoot.resolve("KeyScanNativeHost.exe")
        if (!Files.isRegularFile(hostExe)) return@runCatching
        val configRoot = Path.of(appData, "KeyScan")
        val manifestRoot = configRoot.resolve("native-messaging")
        Files.createDirectories(manifestRoot)
        val knownChromiumIds = listOf(
            // Stable unpacked development ID from the extension manifest "key".
            "ccehabgiddlgfkhgkmpdehddiekngjel",
            // Another unpacked Chrome ID observed in the Windows test profile.
            "pidlgiekeckkninmgbhnjmpempcifjjh",
            // Current unpacked Chrome ID used in the Windows test VM.
            "eipijgmbbiajbndkcfbcapdjelhdfpim",
            // Earlier unpacked ID kept so existing developer profiles do not break.
            "eiaedaglbbchjdgcbnklddopdjanimaj",
        ).distinct()
        val origins = knownChromiumIds.joinToString(",\n") { """    "chrome-extension://$it/"""" }
        val chromiumManifest = """
            {
              "name": "com.keyscan.desktop",
              "description": "KeyScan password manager native messaging host",
              "path": "${hostExe.toString().replace("\\", "\\\\")}",
              "type": "stdio",
              "allowed_origins": [
            $origins
              ]
            }
        """.trimIndent()
        val manifest = manifestRoot.resolve("com.keyscan.desktop.chromium.json")
        Files.writeString(manifest, chromiumManifest)
        val firefoxManifest = """
            {
              "name": "com.keyscan.desktop",
              "description": "KeyScan password manager native messaging host",
              "path": "${hostExe.toString().replace("\\", "\\\\")}",
              "type": "stdio",
              "allowed_extensions": ["keyscan@keyscan.app"]
            }
        """.trimIndent()
        Files.writeString(manifestRoot.resolve("com.keyscan.desktop.firefox.json"), firefoxManifest)
        val allowlist = buildList {
            knownChromiumIds.forEach {
                add("chrome-extension://$it")
                add("chrome-extension://$it/")
            }
            add("keyscan@keyscan.app")
        }
        Files.writeString(configRoot.resolve("native-host-allowlist.txt"), allowlist.joinToString(System.lineSeparator()))
        val manifestPath = manifest.toString()
        listOf(
            "HKCU\\Software\\Google\\Chrome\\NativeMessagingHosts\\com.keyscan.desktop",
            "HKCU\\Software\\Microsoft\\Edge\\NativeMessagingHosts\\com.keyscan.desktop",
            "HKCU\\Software\\BraveSoftware\\Brave-Browser\\NativeMessagingHosts\\com.keyscan.desktop",
        ).forEach { key ->
            runCatching {
                ProcessBuilder("reg", "add", key, "/ve", "/t", "REG_SZ", "/d", manifestPath, "/f")
                    .redirectErrorStream(true)
                    .start()
                    .waitFor()
            }
        }
        logNativeHostInstall("installed $hostExe")
    }.onFailure { logNativeHostInstall("failed: ${it.message}") }
}

private fun bundledNativeHostZipStream(loader: ClassLoader): InputStream? {
    loader.getResourceAsStream("windows/KeyScanNativeHost.zip")?.let { return it }
    loader.getResourceAsStream("KeyScanNativeHost.zip")?.let { return it }
    val candidates = listOf(
        System.getProperty("compose.application.resources.dir", "").takeIf { it.isNotBlank() }?.let { Path.of(it, "KeyScanNativeHost.zip") },
        Path.of(System.getProperty("user.dir", ""), "app", "resources", "KeyScanNativeHost.zip"),
        Path.of(System.getProperty("user.dir", ""), "resources", "KeyScanNativeHost.zip"),
        Path.of("C:\\Program Files\\KeyScan\\app\\resources\\KeyScanNativeHost.zip"),
    ).filterNotNull()
    return candidates.firstOrNull { Files.isRegularFile(it) }?.let { Files.newInputStream(it) }
}

private fun logNativeHostInstall(message: String) {
    runCatching {
        val appData = System.getenv("APPDATA")?.takeIf { it.isNotBlank() } ?: return@runCatching
        val file = Path.of(appData, "KeyScan", "native-host-install.log")
        Files.createDirectories(file.parent)
        Files.writeString(file, "${java.time.Instant.now()} $message${System.lineSeparator()}", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
    }
}

fun main() {
    runCatching { installBundledNativeHostIfAvailable() }.onFailure { logNativeHostInstall("startup failed: ${it.message}") }
    application {
    val platform = remember { DesktopPlatform.current() }
    var visible by remember { mutableStateOf(true) }; var lockRequest by remember { mutableStateOf(0) }; var trayInstalled by remember { mutableStateOf(false) }
    val tray = remember { DesktopTrayController() }
    // The native-messaging bridge is the desktop Agent. It must outlive the
    // window: closing KeyScan to the tray locks the vault but must not make
    // installed browser extensions report that the desktop app disappeared.
    val nativeBridge = remember(platform) {
        if (platform.supportsWindowsNativeMessaging) {
            runCatching { NativeBridgeServer.startWindows() }.getOrNull()
        } else null
    }
    DisposableEffect(Unit) {
        trayInstalled = tray.install(onShow = { visible = true }, onLock = { lockRequest++; visible = true }, onExit = ::exitApplication)
        onDispose { nativeBridge?.close(); tray.close() }
    }
    val windowState = remember(platform) {
        WindowState(
            width = if (platform == DesktopPlatform.MACOS) 1220.dp else 560.dp,
            height = 860.dp,
            position = WindowPosition(Alignment.Center),
        )
    }
    Window(onCloseRequest = { if (trayInstalled) { lockRequest++; visible = false } else exitApplication() }, visible = visible, title = "KeyScan", state = windowState) {
        DisposableEffect(window) {
            val previousIcon = window.iconImage
            DesktopAppIcon.image?.let { window.iconImage = it }
            onDispose { window.iconImage = previousIcon }
        }
        val hwnd = remember(window, platform) {
            if (platform.supportsWindowsHello) runCatching { Pointer.nativeValue(Native.getComponentPointer(window)) }.getOrDefault(0L) else 0L
        }
        KeyScanApp(lockRequest, hwnd, platform, nativeBridge, onBrowserUnlockRequested = {
            visible = true
            // A browser-initiated authorization must be impossible to miss;
            // bring the trusted desktop dialog in front of browser windows.
            window.toFront()
            window.requestFocus()
        })
    }
    }
}

@Composable
private fun KeyScanApp(lockRequest: Int, windowHandle: Long, platform: DesktopPlatform, nativeBridge: NativeBridgeServer?, onBrowserUnlockRequested: () -> Unit) {
    // Pass the path explicitly so release shrinking never relies on Kotlin's
    // synthetic default-constructor bridge across module boundaries.
    val appSettingsStore = remember { AppSettingsStore(AppSettingsStore.defaultFile()) }
    var globalSettings by remember { mutableStateOf(appSettingsStore.load()) }
    val dark = when (globalSettings.themeMode) { com.keyscan.core.model.ThemeMode.DARK -> true; com.keyscan.core.model.ThemeMode.LIGHT -> false; com.keyscan.core.model.ThemeMode.SYSTEM -> isSystemInDarkTheme() }
    val colors = if (dark) darkColorScheme(primary = Color(0xFF83AAFF), secondary = Color(0xFF55D6A3), tertiary = Color(0xFFB59AF5)) else lightColorScheme(primary = KeyBlue, secondary = KeyGreen, tertiary = KeyPurple)
    CompositionLocalProvider(LocalUiText provides uiText(globalSettings.language), LocalAppLanguage provides globalSettings.language) { MaterialTheme(colorScheme = colors) {
        val pendingApproval by (nativeBridge?.approvals?.pending?.collectAsState()
            ?: remember { mutableStateOf<NativeCredentialApproval?>(null) })
        val pendingPluginPairing by (nativeBridge?.pairings?.pending?.collectAsState()
            ?: remember { mutableStateOf<NativeBrowserPairingRequest?>(null) })
        val pendingBrowserUnlock by (nativeBridge?.unlockRequests?.pending?.collectAsState()
            ?: remember { mutableStateOf<Long?>(null) })
        LaunchedEffect(pendingBrowserUnlock) {
            if (pendingBrowserUnlock != null) onBrowserUnlockRequested()
        }
        LaunchedEffect(pendingPluginPairing) {
            if (pendingPluginPairing != null) onBrowserUnlockRequested()
        }
        val store = remember { VaultBootstrapStore(VaultBootstrapStore.defaultConfigPath()) }
        val macKeychain = remember(platform) { if (platform == DesktopPlatform.MACOS) runCatching { MacKeychainSecretStore() }.getOrNull() else null }
        val pinQuickUnlock = remember(platform, macKeychain) {
            when (platform) {
                DesktopPlatform.WINDOWS -> runCatching { WindowsPinQuickUnlockStore() }.getOrNull()
                DesktopPlatform.MACOS -> macKeychain?.let { runCatching { WindowsPinQuickUnlockStore(WindowsPinQuickUnlockStore.defaultMacFile(), it) }.getOrNull() }
                DesktopPlatform.OTHER -> null
            }
        }
        val helloClient = remember { WindowsHelloClient() }
        val helloStore = remember { WindowsHelloQuickUnlockStore() }
        var helloAvailability by remember { mutableStateOf(WindowsHelloAvailability.UNKNOWN) }
        var helloConfigured by remember(platform) { mutableStateOf(platform.supportsWindowsHello && helloStore.isEnabled()) }
        LaunchedEffect(windowHandle, platform) {
            helloAvailability = if (platform.supportsWindowsHello) withContext(Dispatchers.IO) { helloClient.checkAvailability() }
            else WindowsHelloAvailability.DEVICE_NOT_PRESENT
        }
        var session by remember { mutableStateOf<VaultSession?>(null) }
        // Always destroy the previous database-key session when it is replaced, locked,
        // or the application composition is disposed during process exit.
        DisposableEffect(session) {
            val managedSession = session
            onDispose { managedSession?.close() }
        }
        var screen by remember { mutableStateOf(if (store.isConfigured()) AppScreen.LOCKED else AppScreen.SETUP) }
        LaunchedEffect(lockRequest) { if (lockRequest > 0 && screen == AppScreen.UNLOCKED) { nativeBridge?.locked(); session?.close(); session = null; screen = AppScreen.LOCKED } }
        when (screen) {
            AppScreen.SETUP -> FirstRunSetup { pin, key ->
                session = store.create(pin, key)
                session?.useDatabaseKey { databaseKey -> pinQuickUnlock?.enable(pin, databaseKey) }
                screen = AppScreen.UNLOCKED
            }
            AppScreen.LOCKED -> UnlockScreen(
                requireDataKey = pinQuickUnlock?.isEnabled() != true,
                browserPairing = pendingPluginPairing,
                onResolveBrowserPairing = { requestId, approved -> nativeBridge?.pairings?.resolve(requestId, approved) },
                onUnlock = { pin, key ->
                    session = if (pinQuickUnlock?.isEnabled() == true) pinQuickUnlock.unlock(pin) else store.unlock(pin, key)
                    if (pinQuickUnlock?.isEnabled() != true) session?.useDatabaseKey { databaseKey -> pinQuickUnlock?.enable(pin, databaseKey) }
                    screen = AppScreen.UNLOCKED
                },
                helloEnabled = platform.supportsWindowsHello && helloConfigured && helloAvailability == WindowsHelloAvailability.AVAILABLE && windowHandle != 0L,
                onHelloUnlock = {
                    val enrollment = runCatching { helloStore.enrollment() }.getOrNull() ?: return@UnlockScreen false
                    val signature = withContext(Dispatchers.IO) { helloClient.sign(windowHandle, enrollment.keyName, enrollment.challenge) }
                        ?: return@UnlockScreen false
                    try { session = helloStore.unlock(signature); screen = AppScreen.UNLOCKED; true }
                    catch (_: Exception) { false }
                    finally { signature.fill(0); enrollment.challenge.fill(0) }
                },
            )
            AppScreen.UNLOCKED -> {
                val activeSession = checkNotNull(session)
                val vault = remember(activeSession) {
                    EncryptedVaultStore(EncryptedVaultStore.defaultVaultPath(), databaseKeyProvider = { block -> activeSession.useDatabaseKey(block) })
                }
                DisposableEffect(vault) { nativeBridge?.unlockedWith(vault); onDispose { nativeBridge?.locked() } }
                val attachmentStore = remember(activeSession) { EncryptedAttachmentStore(EncryptedAttachmentStore.defaultDirectory(), databaseKeyProvider = { block -> activeSession.useDatabaseKey(block) }) }
                val history = remember(activeSession) { BackupHistoryManager(LocalBackupService(vault, attachmentStore) { block -> activeSession.useDatabaseKey(block) }, BackupHistoryManager.defaultDirectory()) }
                val webDavSettings = remember(platform, macKeychain) {
                    when (platform) {
                        DesktopPlatform.WINDOWS -> WebDavSettingsStore(WebDavSettingsStore.defaultFile(), WindowsDpapiSecretStore(WindowsDpapiSecretStore.defaultDirectory()))
                        DesktopPlatform.MACOS -> macKeychain?.let { WebDavSettingsStore(WebDavSettingsStore.defaultFile(), it) }
                        DesktopPlatform.OTHER -> null
                    }
                }
                val clipboard = remember { SecureClipboard() }
                DisposableEffect(clipboard) { onDispose { clipboard.close() } }
                DesktopHome(vault, attachmentStore, history, webDavSettings, globalSettings, onSettings = { globalSettings = it; appSettingsStore.save(it) }, clipboard, browserPlugins = { nativeBridge?.browserPlugins().orEmpty() }, revokeBrowserPlugin = { nativeBridge?.revokeBrowserPlugin(it) }, authenticateRoot = { pin, key ->
                    try { store.unlock(pin, key).close(); KeyDerivation.deriveRootKey(pin, key) } catch (_: Exception) { null }
                }, reauthenticate = { pin, key ->
                    try { store.unlock(pin, key).close(); true } catch (_: VaultAuthenticationException) { false }
                }, quickPinAvailable = pinQuickUnlock?.isEnabled() == true, verifyQuickPin = { pin ->
                    try { pinQuickUnlock?.unlock(pin)?.close(); true } catch (_: Exception) { false }
                }, onLock = { nativeBridge?.locked(); session?.close(); session = null; screen = AppScreen.LOCKED },
                    changeDataProtectionKey = { pin, oldKey, nextKey -> runCatching { store.changeDataProtectionKey(pin, oldKey, nextKey) }.isSuccess },
                    helloReauthenticationAvailable = platform.supportsWindowsHello && helloConfigured && helloAvailability == WindowsHelloAvailability.AVAILABLE && windowHandle != 0L,
                    onHelloReauthenticate = {
                        val enrollment = runCatching { helloStore.enrollment() }.getOrNull() ?: return@DesktopHome false
                        val signature = withContext(Dispatchers.IO) { helloClient.sign(windowHandle, enrollment.keyName, enrollment.challenge) }
                            ?: return@DesktopHome false
                        try { helloStore.unlock(signature).close(); true }
                        catch (_: Exception) { false }
                        finally { signature.fill(0); enrollment.challenge.fill(0) }
                    },
                    windowsHelloSupported = platform.supportsWindowsHello, helloAvailability = helloAvailability, helloConfigured = helloConfigured,
                    onEnableHello = {
                        if (windowHandle == 0L || helloAvailability != WindowsHelloAvailability.AVAILABLE) return@DesktopHome false
                        val enrollment = helloStore.newEnrollment()
                        val enrolled = withContext(Dispatchers.IO) { helloClient.enrollKey(windowHandle, enrollment.keyName) }
                        if (!enrolled) { enrollment.challenge.fill(0); return@DesktopHome false }
                        val signature = withContext(Dispatchers.IO) { helloClient.sign(windowHandle, enrollment.keyName, enrollment.challenge) }
                        if (signature == null) { withContext(Dispatchers.IO) { helloClient.deleteKey(enrollment.keyName) }; enrollment.challenge.fill(0); return@DesktopHome false }
                        try {
                            activeSession.useDatabaseKey { databaseKey -> helloStore.enable(enrollment, databaseKey, signature) }
                            helloConfigured = true; true
                        } catch (_: Exception) {
                            helloStore.disable(); withContext(Dispatchers.IO) { helloClient.deleteKey(enrollment.keyName) }; false
                        } finally { signature.fill(0); enrollment.challenge.fill(0) }
                    },
                    onDisableHello = { helloStore.disable(); withContext(Dispatchers.IO) { helloClient.deleteKey(WindowsHelloQuickUnlockStore.KEY_NAME) }; helloConfigured = false }
                )
            }
        }
        pendingApproval?.let { request ->
            val approvalLanguage = LocalAppLanguage.current
            AlertDialog(
                onDismissRequest = { nativeBridge?.approvals?.resolve(request.requestId, false) },
                title = { Text(AndroidStringCatalog.text(approvalLanguage, "autofill_auth_title")) },
                text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(AndroidStringCatalog.text(approvalLanguage, "autofill_auth_message", request.origin, request.username))
                    Text(request.origin, fontWeight = FontWeight.SemiBold)
                    Text("${request.label}  ·  ${request.username}")
                } },
                confirmButton = { Button(onClick = { nativeBridge?.approvals?.resolve(request.requestId, true) }) { Text(AndroidStringCatalog.text(approvalLanguage, "autofill_use_password")) } },
                dismissButton = { TextButton(onClick = { nativeBridge?.approvals?.resolve(request.requestId, false) }) { Text(m("cancel")) } },
            )
        }
        pendingPluginPairing?.let { request ->
            val pairingLanguage = LocalAppLanguage.current
            AlertDialog(
                onDismissRequest = { nativeBridge?.pairings?.resolve(request.requestId, false) },
                title = { Text(AndroidStringCatalog.text(pairingLanguage, "desktop_plugin_allow_title")) },
                text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(AndroidStringCatalog.text(pairingLanguage, "desktop_plugin_allow_message"))
                    Text(AndroidStringCatalog.text(pairingLanguage, "desktop_plugin_browser_line", request.browser.replaceFirstChar { it.uppercase() }), fontWeight = FontWeight.SemiBold)
                    Text(AndroidStringCatalog.text(pairingLanguage, "desktop_plugin_extension_id_line", request.extensionId))
                    if (request.version.isNotBlank()) Text(AndroidStringCatalog.text(pairingLanguage, "desktop_plugin_version_line", request.version))
                } },
                confirmButton = { Button(onClick = { nativeBridge?.pairings?.resolve(request.requestId, true) }) { Text(AndroidStringCatalog.text(pairingLanguage, "desktop_allow")) } },
                dismissButton = { TextButton(onClick = { nativeBridge?.pairings?.resolve(request.requestId, false) }) { Text(m("cancel")) } },
            )
        }
    } }
}

@Composable
private fun Brand(modifier: Modifier = Modifier) {
    val text = LocalUiText.current
    val slogan = text.safeFree.trim().split(Regex("\\s+"), limit = 2)
    val safe = slogan.firstOrNull().orEmpty().ifBlank { AndroidStringCatalog.text(LocalAppLanguage.current, "desktop_tag_safe") }
    val free = slogan.getOrNull(1).orEmpty().ifBlank { AndroidStringCatalog.text(LocalAppLanguage.current, "desktop_tag_free") }
    Column(modifier.height(82.dp), verticalArrangement = Arrangement.Center) {
        Text(
            "KeyScan",
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            style = TextStyle(brush = Brush.horizontalGradient(listOf(Color(0xFF159DFF), Color(0xFF346DFF), Color(0xFFC84CDA), Color(0xFFFF6B63), Color(0xFFFFB82E)))),
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp)) {
            Icon(Icons.Filled.VerifiedUser, null, tint = Color(0xFF149DFF), modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(4.dp)); Text(safe, color = Color(0xFF137EEA), fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Spacer(Modifier.width(8.dp)); Icon(Icons.Filled.Eco, null, tint = Color(0xFF11B977), modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(3.dp)); Text(free, color = Color(0xFF08AD6D), fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
    }
}

@Composable
private fun FirstRunSetup(onComplete: (String, String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    var pinAgain by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var keyAgain by remember { mutableStateOf("") }
    var keyLength by remember { mutableStateOf(16) }
    var importedKey by remember { mutableStateOf(false) }
    var keyPreserved by remember { mutableStateOf(false) }
    val language = LocalAppLanguage.current; fun t(name: String, vararg args: Any) = AndroidStringCatalog.text(language, name, *args)
    val compactKey = key.replace("-", "")
    val valid = pin.matches(Regex("\\d{4,6}")) && pin == pinAgain && compactKey.matches(Regex("[A-Z0-9]{8,32}")) && key == keyAgain && (importedKey || keyPreserved)
    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(Modifier.weight(.42f).fillMaxHeight().background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.primaryContainer))).padding(56.dp)) {
            Column(Modifier.align(Alignment.CenterStart), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                Brand()
                Text(m("setup_title"), fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Text(m("setup_intro"), fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Box(Modifier.weight(.58f).fillMaxHeight().padding(horizontal = 64.dp, vertical = 32.dp), contentAlignment = Alignment.Center) {
            Card(Modifier.widthIn(max = 520.dp).fillMaxHeight(), shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(34.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                    Text(m("setup_heading"), fontSize = 25.sp, fontWeight = FontWeight.Bold)
                    Text(m("custody"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SecretField(m("pin_new"), pin) { pin = it }
                    SecretField(m("pin_again"), pinAgain) { pinAgain = it }
                    Text(t("vault_setup_length_current", keyLength), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                    Slider(value = keyLength.toFloat(), onValueChange = { keyLength = it.toInt() }, valueRange = 8f..32f, steps = 23, enabled = !importedKey)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { val generated = generateDesktopDataKey(keyLength); key = generated; keyAgain = generated; importedKey = false; keyPreserved = false }) { Text(t("action_generate")) }
                        OutlinedButton(onClick = { chooseFile(FileDialog.LOAD, t("data_key_import_downloaded"))?.let { path -> parseDesktopDataKey(runCatching { Files.readString(path) }.getOrDefault(""))?.let { imported -> key = imported; keyAgain = imported; importedKey = true; keyPreserved = false } } }) { Text(t("data_key_import_downloaded")) }
                    }
                    SecretField(m("key_new"), key) { key = it.trim().uppercase(); importedKey = false; keyPreserved = false }
                    SecretField(m("key_again"), keyAgain) { keyAgain = it.trim().uppercase() }
                    if (!importedKey) Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(keyPreserved, { keyPreserved = it }); Text(t("vault_setup_key_preserved")) }
                    if ((pinAgain.isNotEmpty() && pin != pinAgain) || (keyAgain.isNotEmpty() && key != keyAgain)) Text(m("mismatch"), color = MaterialTheme.colorScheme.error)
                    Button(onClick = { onComplete(pin, key); pin = ""; pinAgain = ""; key = ""; keyAgain = "" }, enabled = valid, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text(m("create")) }
                }
            }
        }
    }
}

internal fun generateDesktopDataKey(length: Int = 16): String {
    val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; val random = SecureRandom()
    return (1..length.coerceIn(8, 32)).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("").chunked(4).joinToString("-")
}

internal fun parseDesktopDataKey(document: String): String? = Regex("(?m)^\\s*([A-Za-z0-9-]{8,39})\\s*$").findAll(document)
    .map { it.groupValues[1].trim().uppercase() }.firstOrNull { candidate -> candidate.replace("-", "").matches(Regex("[A-Z0-9]{8,32}")) }

@Composable private fun SecretField(label: String, value: String, onChange: (String) -> Unit) = OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true, visualTransformation = PasswordVisualTransformation())

@Composable
private fun UnlockScreen(
    requireDataKey: Boolean,
    browserPairing: NativeBrowserPairingRequest?,
    onResolveBrowserPairing: (Long, Boolean) -> Unit,
    onUnlock: (String, String) -> Unit,
    helloEnabled: Boolean,
    onHelloUnlock: suspend () -> Boolean,
) {
    var pin by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var helloWorking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.primaryContainer))), contentAlignment = Alignment.Center) {
        Card(Modifier.widthIn(max = 520.dp).padding(horizontal = 24.dp), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(36.dp), verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Brand()
                browserPairing?.let { request ->
                    BrowserPairingNotice(request, onResolveBrowserPairing)
                }
                Text(m("unlock_title"), fontSize = 25.sp, fontWeight = FontWeight.Bold)
                Text(m("unlock_intro"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                SecretField("PIN", pin) { pin = it; error = false }
                if (requireDataKey) SecretField(m("key_new"), key) { key = it; error = false }
                if (error) Text(m("bad_credentials"), color = MaterialTheme.colorScheme.error)
                Button(onClick = {
                    try { onUnlock(pin, key); pin = ""; key = "" }
                    catch (_: VaultAuthenticationException) { error = true }
                }, enabled = pin.matches(Regex("\\d{4,6}")) && (!requireDataKey || key.isNotEmpty()), modifier = Modifier.fillMaxWidth().height(48.dp)) { Text(m("unlock")) }
                if (helloEnabled) OutlinedButton(onClick = {
                    helloWorking = true; error = false
                    scope.launch { if (!onHelloUnlock()) error = true; helloWorking = false }
                }, enabled = !helloWorking, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text(if (helloWorking) "${LocalUiText.current.windowsHello}…" else LocalUiText.current.windowsHello) }
            }
        }
    }
}

@Composable
private fun BrowserPairingNotice(request: NativeBrowserPairingRequest, onResolve: (Long, Boolean) -> Unit) {
    val language = LocalAppLanguage.current
    fun t(key: String, vararg args: Any) = AndroidStringCatalog.text(language, key, *args)

    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .72f)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Extension, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(t("desktop_plugin_request_title"), fontWeight = FontWeight.Bold)
            }
            Text(
                t("desktop_plugin_request_message"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
            )
            Text(t("desktop_plugin_browser_line", request.browser.replaceFirstChar { it.uppercase() }), fontSize = 13.sp)
            Text(t("desktop_plugin_extension_id_line", request.extensionId), fontSize = 13.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                TextButton(onClick = { onResolve(request.requestId, false) }) { Text(t("share_decline")) }
                Button(onClick = { onResolve(request.requestId, true) }) { Text(t("desktop_allow")) }
            }
        }
    }
}

@Composable
private fun DesktopHome(vault: EncryptedVaultStore, attachmentStore: EncryptedAttachmentStore, history: BackupHistoryManager, webDavSettings: WebDavSettingsStore?, appSettings: AppSettings, onSettings: (AppSettings) -> Unit, clipboard: SecureClipboard, browserPlugins: () -> List<BrowserPluginRecord>, revokeBrowserPlugin: (String) -> Unit, authenticateRoot: (String, String) -> String?, reauthenticate: (String, String) -> Boolean, quickPinAvailable: Boolean, verifyQuickPin: (String) -> Boolean, onLock: () -> Unit, changeDataProtectionKey: (String, String, String) -> Boolean, helloReauthenticationAvailable: Boolean, onHelloReauthenticate: suspend () -> Boolean, windowsHelloSupported: Boolean, helloAvailability: WindowsHelloAvailability, helloConfigured: Boolean, onEnableHello: suspend () -> Boolean, onDisableHello: suspend () -> Unit) {
    val text = LocalUiText.current
    val language = LocalAppLanguage.current; fun t(key: String, vararg args: Any) = AndroidStringCatalog.text(language, key, *args)
    val modules = listOf(
        DesktopModule(DESKTOP_PAGE_HOME, text.home, Icons.Filled.Home, KeyBlue),
        DesktopModule(DESKTOP_PAGE_PASSWORDS, t("home_password_forge"), Icons.Filled.Password, Color(0xFF2F7CF6)),
        DesktopModule(DESKTOP_PAGE_TOTP, t("home_otp"), Icons.Filled.Timer, KeyPurple),
        DesktopModule(DESKTOP_PAGE_VAULT, t("home_password_notes"), Icons.Filled.Inventory2, Color(0xFFFF9700)),
        DesktopModule(DESKTOP_PAGE_GENERATOR, t("random_password_title"), Icons.Filled.AutoAwesome, Color(0xFFFF6B63)),
        DesktopModule(DESKTOP_PAGE_SECURITY, t("security_center_title"), Icons.Filled.Security, Color(0xFF8B5CF6)),
        DesktopModule(DESKTOP_PAGE_SHARE, t("home_generate"), Icons.Filled.Share, Color(0xFF11B977)),
        DesktopModule(DESKTOP_PAGE_DATA, t("home_webdav"), Icons.Filled.Backup, KeyGreen),
        DesktopModule(DESKTOP_PAGE_SETTINGS, text.settings, Icons.Filled.Settings, Color(0xFF718096)),
    )
    var selected by remember { mutableStateOf(DESKTOP_PAGE_HOME) }
    val selectedTitle = modules.firstOrNull { it.id == selected }?.title ?: text.home
    LaunchedEffect(modules.map { it.id }) { if (modules.none { it.id == selected }) selected = DESKTOP_PAGE_HOME }
    var viewOnly by remember { mutableStateOf(appSettings.viewOnly) }
    var showReauthentication by remember { mutableStateOf(false) }
    var overflowExpanded by remember { mutableStateOf(false) }
    var showHomeAbout by remember { mutableStateOf(false) }
    var homeSupportMessage by remember { mutableStateOf("") }
    val support = supportText(appSettings.language)
    val lockPolicy = remember(appSettings.autoLockMinutes) { AutoLockPolicy(Duration.ofMinutes(appSettings.autoLockMinutes.toLong())).also { it.start(System.nanoTime()) } }
    LaunchedEffect(lockPolicy) { while (true) { delay(1000); if (lockPolicy.shouldLock(System.nanoTime())) { clipboard.clearIfOwned(); onLock(); break } } }
    val activityModifier = Modifier.onPreviewKeyEvent { lockPolicy.recordActivity(System.nanoTime()); false }.pointerInput(lockPolicy) { awaitPointerEventScope { while (true) { awaitPointerEvent(); lockPolicy.recordActivity(System.nanoTime()) } } }
    BoxWithConstraints(Modifier.fillMaxSize().then(activityModifier).background(AndroidPrimaryPageBackground)) {
        val compactLayout = maxWidth < 760.dp
        if (compactLayout) {
    Column(Modifier.fillMaxSize()) {
        Surface(Modifier.fillMaxWidth().height(122.dp), color = Color.White, tonalElevation = 2.dp) {
            Row(Modifier.fillMaxSize().padding(start = 30.dp, top = 24.dp, end = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (selected == DESKTOP_PAGE_HOME) {
                    Box(Modifier.width(260.dp).height(86.dp), contentAlignment = Alignment.CenterStart) { Brand() }
                    Spacer(Modifier.weight(1f))
                    Box {
                        IconButton(onClick = { overflowExpanded = true }, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.MoreVert, contentDescription = null, tint = AndroidPrimaryTitle) }
                        DropdownMenu(expanded = overflowExpanded, onDismissRequest = { overflowExpanded = false }) {
                            modules.filterNot { it.id in DesktopHomePrimaryPages }.forEach { module ->
                                DropdownMenuItem(
                                    text = { Text(module.title) },
                                    leadingIcon = { Icon(module.icon, contentDescription = null, tint = module.color) },
                                    onClick = { selected = module.id; overflowExpanded = false }
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(support.help) },
                                leadingIcon = { Icon(Icons.Filled.Help, contentDescription = null, tint = AndroidPrimaryTitle) },
                                onClick = {
                                    overflowExpanded = false
                                    homeSupportMessage = runCatching { DesktopDocuments.openHelp(appSettings.language); "" }.getOrElse { support.openFailed }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(support.about) },
                                leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null, tint = AndroidPrimaryTitle) },
                                onClick = { overflowExpanded = false; showHomeAbout = true }
                            )
                        }
                    }
                } else {
                    IconButton(onClick = { selected = DESKTOP_PAGE_HOME }, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.ArrowBack, contentDescription = null, tint = AndroidPrimaryTitle) }
                    Spacer(Modifier.width(4.dp))
                    Text(selectedTitle, color = AndroidPrimaryTitle, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 22.dp, top = 18.dp, end = 22.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (selected == DESKTOP_PAGE_PASSWORDS) PasswordLedger(vault, viewOnly, clipboard, appSettings.clipboardClearSeconds, reauthenticate, quickPinAvailable, verifyQuickPin, if (helloReauthenticationAvailable) onHelloReauthenticate else null, onRequestEdit = { showReauthentication = true })
            else if (selected == DESKTOP_PAGE_TOTP) TotpLedger(vault, viewOnly, onRequestEdit = { showReauthentication = true })
            else if (selected == DESKTOP_PAGE_VAULT) SecureVaultLedger(vault, attachmentStore, viewOnly, reauthenticate, quickPinAvailable, verifyQuickPin, if (helloReauthenticationAvailable) onHelloReauthenticate else null, onRequestEdit = { showReauthentication = true })
            else if (selected == DESKTOP_PAGE_SETTINGS) SettingsPage(webDavSettings, appSettings, onSettings, browserPlugins, revokeBrowserPlugin, windowsHelloSupported, helloAvailability, helloConfigured, onEnableHello, onDisableHello)
            else if (selected == DESKTOP_PAGE_DATA) DataManagementPage(vault, attachmentStore, history, webDavSettings, appSettings, onSettings, viewOnly, browserPlugins, revokeBrowserPlugin, authenticateRoot, reauthenticate, quickPinAvailable, verifyQuickPin, helloReauthenticationAvailable, onHelloReauthenticate, onLock, windowsHelloSupported, helloAvailability, helloConfigured, onEnableHello, onDisableHello)
            else if (selected == DESKTOP_PAGE_GENERATOR) RandomPasswordPage(clipboard, appSettings.clipboardClearSeconds)
            else if (selected == DESKTOP_PAGE_SHARE) DesktopShareCenterPage(clipboard, appSettings.clipboardClearSeconds)
            else if (selected == DESKTOP_PAGE_SECURITY) SecurityStatusPage(vault, history, webDavSettings, appSettings, helloConfigured, changeDataProtectionKey)
            else if (selected == DESKTOP_PAGE_HOME) HomeOverviewPage(vault, browserPlugins, revokeBrowserPlugin, onNavigate = { selected = it })
            else Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(28.dp)) { Text(selectedTitle, fontSize = 20.sp, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(8.dp)); Text(t("operation_view_summary"), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        Box(Modifier.fillMaxWidth().height(118.dp).padding(top = 2.dp, bottom = 26.dp), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .background(Color.White.copy(alpha = .72f), RoundedCornerShape(24.dp))
                    .padding(horizontal = 18.dp, vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Box(
                    Modifier
                        .size(38.dp)
                        .background(Color(0xFFF2F6FC), RoundedCornerShape(20.dp))
                        .pointerInput(viewOnly) {
                            detectTapGestures(
                                onDoubleTap = { onLock() },
                                onTap = { if (viewOnly) showReauthentication = true else viewOnly = true },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(if (viewOnly) Icons.Filled.Lock else Icons.Filled.LockOpen, contentDescription = null, tint = AndroidPrimaryTitle, modifier = Modifier.size(19.dp))
                }
                Text(if (viewOnly) text.viewOnly else text.editMode, color = AndroidPrimaryTitle, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(if (viewOnly) t("desktop_toggle_edit_hint") else t("desktop_toggle_viewonly_hint"), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, maxLines = 1)
            }
        }
    }
        } else {
    Row(Modifier.fillMaxSize()) {
        Surface(Modifier.width(246.dp).fillMaxHeight().padding(12.dp), shape = RoundedCornerShape(26.dp), color = Color.White.copy(alpha = .86f), tonalElevation = 2.dp, shadowElevation = 10.dp) {
            Column(Modifier.fillMaxHeight().padding(horizontal = 14.dp, vertical = 18.dp), horizontalAlignment = Alignment.Start) {
                Brand()
                Spacer(Modifier.height(22.dp))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    modules.forEach { module ->
                        val active = selected == module.id
                        TextButton(onClick = { selected = module.id }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.textButtonColors(containerColor = if (active) MaterialTheme.colorScheme.primary.copy(alpha = .13f) else Color.Transparent, contentColor = if (active) MaterialTheme.colorScheme.primary else AndroidPrimaryTitle)) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(32.dp).background(module.color.copy(alpha = .14f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) { Icon(module.icon, module.title, Modifier.size(20.dp), tint = module.color) }
                                Text(module.title, fontSize = 15.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth().background(Color(0xFFF6F8FC), RoundedCornerShape(18.dp)).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (viewOnly) Icons.Filled.Lock else Icons.Filled.LockOpen, null, tint = AndroidPrimaryTitle, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (viewOnly) text.viewOnly else text.editMode, color = AndroidPrimaryTitle, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    IconButton(onClick = onLock, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.Lock, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(17.dp)) }
                }
            }
        }
        VerticalDivider(Modifier.fillMaxHeight(), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
        Column(Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 18.dp).verticalScroll(rememberScrollState())) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val compactHeader = maxWidth < 720.dp
                if (compactHeader) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Column { Text(selectedTitle, fontSize = 30.sp, fontWeight = FontWeight.Bold); if (selected == DESKTOP_PAGE_HOME) Text(t("desktop_home_welcome"), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp)) }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = { if (viewOnly) showReauthentication = true else { viewOnly = true } }, shape = RoundedCornerShape(24.dp)) { Icon(if (viewOnly) Icons.Filled.Visibility else Icons.Filled.Edit, null); Spacer(Modifier.width(7.dp)); Text(if (viewOnly) text.viewOnly else text.editMode) }
                            OutlinedButton(onClick = onLock, shape = RoundedCornerShape(24.dp)) { Icon(Icons.Filled.Lock, null); Spacer(Modifier.width(7.dp)); Text(text.lock) }
                        }
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column { Text(selectedTitle, fontSize = 34.sp, fontWeight = FontWeight.Bold); if (selected == DESKTOP_PAGE_HOME) Text(t("desktop_home_welcome"), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp)) }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = { if (viewOnly) showReauthentication = true else { viewOnly = true } }, shape = RoundedCornerShape(24.dp)) { Icon(if (viewOnly) Icons.Filled.Visibility else Icons.Filled.Edit, null); Spacer(Modifier.width(7.dp)); Text(if (viewOnly) text.viewOnly else text.editMode) }
                            OutlinedButton(onClick = onLock, shape = RoundedCornerShape(24.dp)) { Icon(Icons.Filled.Lock, null); Spacer(Modifier.width(7.dp)); Text(text.lock) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            if (selected == DESKTOP_PAGE_PASSWORDS) PasswordLedger(vault, viewOnly, clipboard, appSettings.clipboardClearSeconds, reauthenticate, quickPinAvailable, verifyQuickPin, if (helloReauthenticationAvailable) onHelloReauthenticate else null, onRequestEdit = { showReauthentication = true })
            else if (selected == DESKTOP_PAGE_TOTP) TotpLedger(vault, viewOnly, onRequestEdit = { showReauthentication = true })
            else if (selected == DESKTOP_PAGE_VAULT) SecureVaultLedger(vault, attachmentStore, viewOnly, reauthenticate, quickPinAvailable, verifyQuickPin, if (helloReauthenticationAvailable) onHelloReauthenticate else null, onRequestEdit = { showReauthentication = true })
            else if (selected == DESKTOP_PAGE_SETTINGS) SettingsPage(webDavSettings, appSettings, onSettings, browserPlugins, revokeBrowserPlugin, windowsHelloSupported, helloAvailability, helloConfigured, onEnableHello, onDisableHello)
            else if (selected == DESKTOP_PAGE_DATA) DataManagementPage(vault, attachmentStore, history, webDavSettings, appSettings, onSettings, viewOnly, browserPlugins, revokeBrowserPlugin, authenticateRoot, reauthenticate, quickPinAvailable, verifyQuickPin, helloReauthenticationAvailable, onHelloReauthenticate, onLock, windowsHelloSupported, helloAvailability, helloConfigured, onEnableHello, onDisableHello)
            else if (selected == DESKTOP_PAGE_GENERATOR) RandomPasswordPage(clipboard, appSettings.clipboardClearSeconds)
            else if (selected == DESKTOP_PAGE_SHARE) DesktopShareCenterPage(clipboard, appSettings.clipboardClearSeconds)
            else if (selected == DESKTOP_PAGE_SECURITY) SecurityStatusPage(vault, history, webDavSettings, appSettings, helloConfigured, changeDataProtectionKey)
            else if (selected == DESKTOP_PAGE_HOME) HomeOverviewPage(vault, browserPlugins, revokeBrowserPlugin, onNavigate = { selected = it })
            else Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(28.dp)) { Text(selectedTitle, fontSize = 20.sp, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(8.dp)); Text(t("operation_view_summary"), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
        }
    }
    if (showReauthentication) ReauthenticationDialog(requireDataKey = !quickPinAvailable, onDismiss = { showReauthentication = false }, onHelloVerify = if (helloReauthenticationAvailable) onHelloReauthenticate else null) { pin, key ->
        val verified = if (quickPinAvailable) verifyQuickPin(pin) else reauthenticate(pin, key)
        if (verified) { viewOnly = false; showReauthentication = false; true } else false
    }
    if (homeSupportMessage.isNotBlank()) AlertDialog(
        onDismissRequest = { homeSupportMessage = "" },
        title = { Text(support.section) },
        text = { Text(homeSupportMessage) },
        confirmButton = { TextButton(onClick = { homeSupportMessage = "" }) { Text(support.close) } },
    )
    if (showHomeAbout) AlertDialog(
        onDismissRequest = { showHomeAbout = false },
        title = { Text(support.about) },
        text = { SelectionContainer { Text("${support.aboutBody}\n\nVersion $DESKTOP_VERSION") } },
        confirmButton = { TextButton(onClick = { showHomeAbout = false }) { Text(support.close) } },
    )
}

@Composable
private fun HomeOverviewPage(vault: EncryptedVaultStore, browserPlugins: () -> List<BrowserPluginRecord>, revokeBrowserPlugin: (String) -> Unit, onNavigate: (String) -> Unit) {
    val passwords = vault.listPasswords().size; val totp = vault.listTotp().size; val secure = vault.listSecureItems().size
    BoxWithConstraints(Modifier.fillMaxWidth().background(AndroidPrimaryPageBackground, RoundedCornerShape(28.dp)).padding(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            AndroidHomeHero()
            AndroidStyleCoreCards(passwords, secure, totp, onNavigate)
            AndroidHomePluginCard(browserPlugins, revokeBrowserPlugin)
        }
    }
}

@Composable
private fun AndroidHomeHero() {
    val language = LocalAppLanguage.current
    fun t(key: String, vararg args: Any) = AndroidStringCatalog.text(language, key, *args)

    Box(Modifier.fillMaxWidth().height(88.dp).background(Color.White.copy(alpha = .68f), RoundedCornerShape(28.dp))) {
        Row(Modifier.fillMaxSize().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("KeyScan", color = AndroidPrimaryTitle, fontSize = 30.sp, fontWeight = FontWeight.Black)
                Text(t("desktop_home_banner_tags"), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            Spacer(Modifier.weight(1f))
            Surface(
                modifier = Modifier.size(width = 76.dp, height = 50.dp),
                shape = RoundedCornerShape(25.dp),
                color = Color.Transparent,
                shadowElevation = 10.dp,
            ) {
                Box(Modifier.background(Brush.horizontalGradient(listOf(Color(0xFF4EDB76), Color(0xFFFF914D)))), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Security, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
                }
            }
        }
    }
}

@Composable
private fun AndroidHomePluginCard(browserPlugins: () -> List<BrowserPluginRecord>, revokeBrowserPlugin: (String) -> Unit) {
    val language = LocalAppLanguage.current
    fun t(key: String, vararg args: Any) = AndroidStringCatalog.text(language, key, *args)

    var pairedPlugins by remember { mutableStateOf(browserPlugins()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(10_000)
            pairedPlugins = browserPlugins()
        }
    }
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, AndroidBorderSoft), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.fillMaxWidth().padding(start = 18.dp, top = 14.dp, end = 18.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.height(34.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(t("desktop_plugin_status"), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AndroidPrimaryTitle)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { pairedPlugins = browserPlugins() }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Refresh, contentDescription = t("share_refresh"), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                }
            }
            val byBrowser = pairedPlugins.groupBy { it.browser.lowercase() }
            val expected = listOf("chrome" to "Chrome", "edge" to "Edge", "firefox" to "Firefox", "brave" to "Brave", "safari" to "Safari")
            Text(t("desktop_plugin_supported_browsers", expected.joinToString(t("desktop_list_separator")) { it.second }), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1)
            if (!DesktopPlatform.current().supportsWindowsNativeMessaging) {
                Text(t("desktop_plugin_windows_only"), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                return@Column
            }
            val onlineCutoff = java.time.Instant.now().minusSeconds(90)
            fun BrowserPluginRecord.isOnline(): Boolean = lastSeenAt?.isAfter(onlineCutoff) == true
            val connected = expected.mapNotNull { (key, label) -> byBrowser[key]?.firstOrNull { it.isOnline() }?.let { label to it } } +
                byBrowser.filterKeys { key -> expected.none { it.first == key } }.mapNotNull { (browser, records) -> records.firstOrNull { it.isOnline() }?.let { browser.replaceFirstChar { it.uppercase() } to it } }
            if (connected.isEmpty()) {
                AndroidPluginEmptyConnectedRow()
            } else {
                connected.take(4).forEach { (label, plugin) ->
                    AndroidPluginConnectedRow(label, plugin) { revokeBrowserPlugin(plugin.caller); pairedPlugins = browserPlugins() }
                }
            }
        }
    }
}

@Composable
private fun AndroidPluginConnectedRow(browserName: String, plugin: BrowserPluginRecord, onRevoke: () -> Unit) {
    val language = LocalAppLanguage.current
    fun t(key: String, vararg args: Any) = AndroidStringCatalog.text(language, key, *args)

    Row(Modifier.fillMaxWidth().heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(Color(0xFF2EAF9A), RoundedCornerShape(4.dp)))
        Spacer(Modifier.width(8.dp))
        Text(t("desktop_plugin_connected", browserName), color = AndroidPrimaryTitle, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, modifier = Modifier.weight(1f))
        TextButton(onClick = onRevoke) { Text(t("desktop_revoke"), fontSize = 12.sp) }
    }
}

@Composable
private fun AndroidPluginEmptyConnectedRow() {
    val language = LocalAppLanguage.current
    fun t(key: String, vararg args: Any) = AndroidStringCatalog.text(language, key, *args)

    Row(Modifier.fillMaxWidth().heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(4.dp)))
        Spacer(Modifier.width(8.dp))
        Text(t("desktop_plugin_connected_none"), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
    }
}

@Composable
private fun AndroidHomeStatusRow(icon: ImageVector, title: String, value: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 34.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = Color(0xFF2EAF9A), modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(10.dp))
        Text(title, color = AndroidPrimaryTitle, fontWeight = FontWeight.Medium)
        Spacer(Modifier.weight(1f))
        Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
    }
}

@Composable
private fun AndroidStyleCoreCards(passwords: Int, secure: Int, totp: Int, onNavigate: (String) -> Unit) {
    val language = LocalAppLanguage.current
    fun t(key: String, vararg args: Any) = AndroidStringCatalog.text(language, key, *args)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AndroidCoreCard(Icons.Filled.Password, t("home_password_forge"), t("desktop_count_accounts", passwords), t("desktop_card_passwords_sub"), listOf("Local Encrypt", "Autofill"), Brush.verticalGradient(listOf(Color(0xFFEAF5FF), Color(0xFFD8ECFF))), Color(0xFFB8DAFA), Color(0xFF0C5F9F), Color(0xFF8DC7F5), { onNavigate(DESKTOP_PAGE_PASSWORDS) }, Modifier.weight(1f))
                AndroidCoreCard(Icons.Filled.Inventory2, t("vault_title"), t("desktop_count_secure_items", secure), t("desktop_card_vault_sub"), listOf("Secure"), Brush.verticalGradient(listOf(Color(0xFFEAFBEA), Color(0xFFD9F4D6))), Color(0xFFC0E8BC), Color(0xFF217534), Color(0xFFA1DEA0), { onNavigate(DESKTOP_PAGE_VAULT) }, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AndroidCoreCard(Icons.Filled.Timer, "TOTP", t("desktop_count_codes", totp), t("desktop_card_totp_sub"), listOf("Time-based"), Brush.verticalGradient(listOf(Color(0xFFF2E8FF), Color(0xFFEBD8FF))), Color(0xFFDDBEFA), Color(0xFF6F2791), Color(0xFFD09CF0), { onNavigate(DESKTOP_PAGE_TOTP) }, Modifier.weight(1f))
                AndroidCoreCard(Icons.Filled.AutoAwesome, t("desktop_card_random_title"), t("desktop_badge_local"), t("desktop_card_random_sub"), listOf("Autofill", "Secure"), Brush.verticalGradient(listOf(Color(0xFFFFF1DF), Color(0xFFFFDFC0))), Color(0xFFF9C69B), Color(0xFF9B3F06), Color(0xFFFF894D), { onNavigate(DESKTOP_PAGE_GENERATOR) }, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun AndroidCoreCard(icon: ImageVector, title: String, summary: String, subtitle: String, chips: List<String>, brush: Brush, borderColor: Color, titleColor: Color, iconColor: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(onClick = onClick, modifier = modifier.heightIn(min = 166.dp), shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, borderColor.copy(alpha = .78f)), colors = CardDefaults.cardColors(containerColor = Color.Transparent)) {
        Box(Modifier.fillMaxSize().background(brush).padding(14.dp)) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                Box(Modifier.fillMaxWidth().height(62.dp)) {
                    Box(Modifier.size(58.dp).align(Alignment.Center).background(iconColor.copy(alpha = .72f), RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
                        Icon(icon, null, tint = Color.White, modifier = Modifier.size(31.dp))
                    }
                    chips.take(1).forEach { chip ->
                        AndroidCardChip(chip, borderColor.copy(alpha = .88f), Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-2).dp))
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, color = titleColor, fontSize = 21.sp, fontWeight = FontWeight.Black, lineHeight = 23.sp, maxLines = 2)
                    Text(subtitle, color = ScreenInk.copy(alpha = .82f), fontSize = 13.sp, lineHeight = 17.sp, maxLines = 2)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                        Text(summary, color = titleColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.background(Color.White.copy(alpha = .52f), RoundedCornerShape(8.dp)).padding(horizontal = 7.dp, vertical = 2.dp), maxLines = 1)
                        chips.drop(1).take(1).forEach { AndroidCardChip(it, borderColor.copy(alpha = .82f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AndroidCardChip(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(text, color = ScreenInk.copy(alpha = .74f), fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = modifier.background(color, RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 3.dp), maxLines = 1)
}

@Composable
private fun BackupSummary(latest: BackupHistoryEntry?, onNavigate: (String) -> Unit, t: (String, Array<out Any>) -> String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(t("local_backup_title", emptyArray()), fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Text(t("backup_encryption_note", emptyArray()), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(if (latest == null) t("backup_latest_none", emptyArray()) else t("recovery_latest_backup_line", arrayOf(latest.path.fileName.toString())), color = MaterialTheme.colorScheme.primary)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Button(onClick = { onNavigate(DESKTOP_PAGE_DATA) }) { Text(t("local_backup_create", emptyArray())) }; OutlinedButton(onClick = { onNavigate(DESKTOP_PAGE_DATA) }) { Text(t("restore_this_backup", emptyArray())) } }
    }
}

@Composable
private fun QuickActionsCard(onNavigate: (String) -> Unit, modifier: Modifier) {
    val language = LocalAppLanguage.current
    fun t(key: String, vararg args: Any) = AndroidStringCatalog.text(language, key, *args)

    Card(modifier, shape = RoundedCornerShape(20.dp)) { Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(t("desktop_quick_actions"), fontSize = 21.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) { HomeActionButton(Icons.Filled.PersonAdd, t("desktop_add_account"), { onNavigate(DESKTOP_PAGE_PASSWORDS) }, Modifier.weight(1f)); HomeActionButton(Icons.Filled.Key, t("desktop_generate_password"), { onNavigate(DESKTOP_PAGE_GENERATOR) }, Modifier.weight(1f)) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) { HomeActionButton(Icons.Filled.Inventory2, t("vault_title"), { onNavigate(DESKTOP_PAGE_VAULT) }, Modifier.weight(1f)); HomeActionButton(Icons.Filled.Timer, t("desktop_totp_authenticator"), { onNavigate(DESKTOP_PAGE_TOTP) }, Modifier.weight(1f)) }
    } }
}

@Composable
private fun SecurityTipsCard(viewOnly: Boolean, t: (String, Array<out Any>) -> String, modifier: Modifier) {
    Card(modifier, shape = RoundedCornerShape(20.dp)) { Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Info, null, tint = KeyBlue); Spacer(Modifier.width(8.dp)); Text(t("desktop_security_tips", emptyArray()), fontSize = 21.sp, fontWeight = FontWeight.Bold) }
        Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.CheckCircle, null, tint = KeyGreen); Spacer(Modifier.width(8.dp)); Text(t("desktop_tip_strong_password", emptyArray())) }
        Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Backup, null, tint = KeyBlue); Spacer(Modifier.width(8.dp)); Text(t("desktop_tip_backup", emptyArray())) }
        Text(if (viewOnly) t("operation_view_mode", emptyArray()) else t("operation_edit_mode", emptyArray()), color = MaterialTheme.colorScheme.primary)
    } }
}

@Composable
private fun HomeMetricCard(icon: ImageVector, title: String, color: Color, modifier: Modifier = Modifier) {
    Card(modifier.heightIn(min = 142.dp), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .35f))) { Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).background(color.copy(alpha = .2f), RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = color, modifier = Modifier.size(27.dp)) }
        Text(title, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp)
    } }
}

@Composable
private fun HomeActionButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(onClick = onClick, modifier = modifier.heightIn(min = 68.dp), shape = RoundedCornerShape(14.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary); Text(label) }
    }
}

@Composable
private fun ModuleActionButton(icon: ImageVector, label: String, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val content: @Composable RowScope.() -> Unit = {
        Icon(icon, null, Modifier.size(18.dp))
        Spacer(Modifier.width(7.dp))
        Text(label)
    }
    if (primary) Button(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(14.dp), content = content)
    else OutlinedButton(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(14.dp), content = content)
}

@Composable
private fun BackupCloudIllustration() {
    Box(Modifier.size(width = 220.dp, height = 150.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(132.dp).background(Brush.radialGradient(listOf(KeyBlue.copy(alpha = .92f), KeyPurple.copy(alpha = .72f), Color.Transparent)), RoundedCornerShape(70.dp)))
        Card(Modifier.size(82.dp, 64.dp), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = KeyBlue.copy(alpha = .12f))) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.CloudUpload, null, tint = KeyBlue, modifier = Modifier.size(40.dp)) } }
        Icon(Icons.Filled.VerifiedUser, null, tint = KeyGreen, modifier = Modifier.size(32.dp).align(Alignment.BottomStart).padding(start = 4.dp))
    }
}

@Composable
private fun RandomPasswordPage(clipboard: SecureClipboard, clipboardClearSeconds: Int) {
    val language = LocalAppLanguage.current; fun t(key: String, vararg args: Any) = AndroidStringCatalog.text(language, key, *args)
    var options by remember { mutableStateOf(PasswordGeneratorOptions()) }
    var generated by remember { mutableStateOf(PasswordGenerator.generate(options)) }
    var copied by remember { mutableStateOf(false) }
    val anySet = options.uppercase || options.lowercase || options.digits || options.symbols
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(t("random_password_desc"), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) { SelectionContainer { Text(generated, Modifier.fillMaxWidth().padding(26.dp), fontSize = 22.sp, fontWeight = FontWeight.SemiBold) } }
        Text(t("autofill_password_length", options.length), fontWeight = FontWeight.SemiBold)
        Slider(options.length.toFloat(), { options = options.copy(length = it.toInt()); copied = false }, valueRange = 8f..64f, steps = 55)
        listOf(
            t("uppercase_letters") to options.uppercase,
            t("lowercase_letters") to options.lowercase,
            t("digits") to options.digits,
            t("special_symbols") to options.symbols,
            t("exclude_confusing_chars") to options.excludeConfusing,
        ).forEachIndexed { index, (label, checked) -> Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked, { value -> options = when(index) { 0 -> options.copy(uppercase = value); 1 -> options.copy(lowercase = value); 2 -> options.copy(digits = value); 3 -> options.copy(symbols = value); else -> options.copy(excludeConfusing = value) }; copied = false })
            Text(label)
        } }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { generated = PasswordGenerator.generate(options); copied = false }, enabled = anySet) { Text(t("regenerate")) }
            OutlinedButton(onClick = { clipboard.copy(generated, clipboardClearSeconds); copied = true }) { Text(if (copied) t("copied") else t("copy")) }
        }
    }
}

@Composable
private fun SecurityStatusPage(vault: EncryptedVaultStore, history: BackupHistoryManager, webDavSettings: WebDavSettingsStore?, appSettings: AppSettings, helloConfigured: Boolean, changeDataProtectionKey: (String, String, String) -> Boolean) {
    val language = LocalAppLanguage.current; fun t(key: String, vararg args: Any) = AndroidStringCatalog.text(language, key, *args)
    val backup = remember { history.list().firstOrNull() }
    val webDavEnabled = webDavSettings?.load()?.let { it.primary.enabled || it.secondary.enabled } == true
    val rows = listOf(
        t("security_vault_initialized"), t("security_data_key_active"), desktopDatabaseStatus(language),
        t("security_attachment_encryption_summary"), t("security_auto_lock_minutes", appSettings.autoLockMinutes),
        if (helloConfigured) t("primary_security_biometric_enabled") else t("primary_security_biometric_disabled"),
        if (webDavEnabled) t("security_webdav_key_protected") else t("security_webdav_not_configured"),
        if (backup?.integrity == BackupIntegrity.VERIFIED) t("integrity_ok") else t("security_backup_not_verified"),
    )
    var showDataKeyChange by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(desktopSecurityHelp(language), color = MaterialTheme.colorScheme.onSurfaceVariant)
        rows.forEachIndexed { index, value -> val attention = (index == 6 && !webDavEnabled) || (index == 7 && backup?.integrity != BackupIntegrity.VERIFIED); Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) { Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (attention) "!" else "✓", color = if (attention) MaterialTheme.colorScheme.error else KeyGreen, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(12.dp)); Text(value)
        } } }
        OutlinedButton(onClick = { showDataKeyChange = true }) { Text(t("data_key_modify")) }
    }
    if (showDataKeyChange) DataProtectionKeyChangeDialog(onDismiss = { showDataKeyChange = false }, onChange = { pin, oldKey, nextKey ->
        if (changeDataProtectionKey(pin, oldKey, nextKey)) { showDataKeyChange = false; true } else false
    })
}

@Composable
private fun DataProtectionKeyChangeDialog(onDismiss: () -> Unit, onChange: (String, String, String) -> Boolean) {
    val language = LocalAppLanguage.current; fun t(key: String, vararg args: Any) = AndroidStringCatalog.text(language, key, *args)
    var pin by remember { mutableStateOf("") }; var oldKey by remember { mutableStateOf("") }; var nextKey by remember { mutableStateOf("") }; var nextAgain by remember { mutableStateOf("") }
    var keyLength by remember { mutableStateOf(16) }; var imported by remember { mutableStateOf(false) }; var preserved by remember { mutableStateOf(false) }; var error by remember { mutableStateOf(false) }
    val compactNext = nextKey.replace("-", "")
    val valid = pin.matches(Regex("\\d{4,6}")) && oldKey.replace("-", "").matches(Regex("[A-Z0-9]{8,32}")) && compactNext.matches(Regex("[A-Z0-9]{8,32}")) && nextKey == nextAgain && (imported || preserved)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(t("data_protection_key_change_title")) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(t("data_key_change_message"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(t("vault_setup_restore_warning"), color = MaterialTheme.colorScheme.error)
            SecretField("PIN", pin) { pin = it; error = false }
            SecretField(t("data_key_old_input"), oldKey) { oldKey = it.trim().uppercase(); error = false }
            Text(t("vault_setup_length_current", keyLength), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
            Slider(value = keyLength.toFloat(), onValueChange = { keyLength = it.toInt() }, valueRange = 8f..32f, steps = 23, enabled = !imported)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { val generated = generateDesktopDataKey(keyLength); nextKey = generated; nextAgain = generated; imported = false; preserved = false }) { Text(t("action_generate")) }
                OutlinedButton(onClick = { chooseFile(FileDialog.LOAD, t("data_key_import_downloaded"))?.let { path -> parseDesktopDataKey(runCatching { Files.readString(path) }.getOrDefault(""))?.let { value -> nextKey = value; nextAgain = value; imported = true; preserved = false } } }) { Text(t("data_key_import_downloaded")) }
            }
            SecretField(t("data_key_new_input"), nextKey) { nextKey = it.trim().uppercase(); imported = false; preserved = false; error = false }
            SecretField(t("data_key_new_confirm"), nextAgain) { nextAgain = it.trim().uppercase(); error = false }
            if (!imported) Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(preserved, { preserved = it }); Text(t("data_key_saved_check")) }
            if (nextAgain.isNotEmpty() && nextKey != nextAgain) Text(m("mismatch"), color = MaterialTheme.colorScheme.error)
            if (error) Text(t("data_key_change_rolled_back"), color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { Button(onClick = { error = !onChange(pin, oldKey, nextKey) }, enabled = valid) { Text(t("data_key_modify")) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(m("cancel")) } })
}

@Composable
private fun BackupHistoryPage(history: BackupHistoryManager, settingsStore: WebDavSettingsStore?, viewOnly: Boolean, authenticateRoot: (String, String) -> String?, reauthenticate: (String, String) -> Boolean, quickPinAvailable: Boolean, verifyQuickPin: (String) -> Boolean, helloReauthenticationAvailable: Boolean, onHelloReauthenticate: suspend () -> Boolean, onRestored: () -> Unit) {
    val language = LocalAppLanguage.current; fun t(key: String, vararg args: Any) = AndroidStringCatalog.text(language, key, *args)
    var entries by remember { mutableStateOf(history.list()) }; var pending by remember { mutableStateOf<BackupHistoryEntry?>(null) }; var creating by remember { mutableStateOf(false) }; var uploadAfterCreate by remember { mutableStateOf(false) }; var message by remember { mutableStateOf("") }; var messageError by remember { mutableStateOf(false) }
    var cloudEntries by remember { mutableStateOf<List<CloudBackupEntry>>(emptyList()) }
    var cloudLoading by remember { mutableStateOf(false) }
    var pendingCloud by remember { mutableStateOf<CloudBackupEntry?>(null) }
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column { Text(t("local_backup_title"), fontSize = 20.sp, fontWeight = FontWeight.SemiBold); Text(t("backup_encryption_note"), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { uploadAfterCreate = false; creating = true }) { Text(t("local_backup_create")) }
                Button(onClick = { uploadAfterCreate = true; creating = true }, enabled = settingsStore?.load()?.let { it.primary.enabled || it.secondary.enabled } == true) { Text(t("network_backup_sync_both")) }
            }
        }
        if (message.isNotBlank()) Text(message, color = if (messageError) MaterialTheme.colorScheme.error else KeyGreen)
        if (entries.isEmpty()) Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) { Text(t("local_backup_empty_status"), Modifier.padding(30.dp)) }
        entries.forEach { entry -> Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column { Text(entry.path.fileName.toString(), fontWeight = FontWeight.SemiBold); Text("${entry.size / 1024} KB · ${when(entry.integrity){ BackupIntegrity.VERIFIED -> t("integrity_ok"); BackupIntegrity.CORRUPTED -> t("database_corrupted"); BackupIntegrity.UNCHECKED -> t("status_not_verified") }}", color = if(entry.integrity == BackupIntegrity.CORRUPTED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
                Button(onClick = { pending = entry }, enabled = !viewOnly && entry.integrity != BackupIntegrity.CORRUPTED) { Text(t("restore_this_backup")) }
            }
        } }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column { Text(t("webdav_cloud_backup_title"), fontSize = 20.sp, fontWeight = FontWeight.SemiBold); Text(t("cloud_backup_hint"), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            OutlinedButton(onClick = {
                if (!cloudLoading && settingsStore != null) scope.launch {
                    cloudLoading = true; message = ""; messageError = false
                    val result = withContext(Dispatchers.IO) { runCatching {
                        configuredWebDavTargets(settingsStore).flatMap { target ->
                            val name = if (target.id == "primary") t("main_webdav_title") else t("backup_webdav_title")
                            target.client.listBackupFiles().map { CloudBackupEntry(target.id, name, it) }
                        }.distinctBy { "${it.targetId}:${it.file.path}" }.sortedByDescending { it.file.name }
                    } }
                    result.onSuccess { cloudEntries = it; if (it.isEmpty()) message = t("network_backup_none") }
                        .onFailure { message = webDavExceptionMessage(it, ::t); messageError = true }
                    cloudLoading = false
                }
            }, enabled = !cloudLoading && settingsStore != null) { Text(if (cloudLoading) "…" else t("share_refresh")) }
        }
        cloudEntries.forEach { entry -> Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(entry.file.name, fontWeight = FontWeight.SemiBold); Text("${entry.targetName} · ${entry.file.size / 1024} KB${entry.file.lastModified?.let { " · $it" } ?: ""}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Button(onClick = { pendingCloud = entry }, enabled = !viewOnly) { Text(t("webdav_restore_backup_button")) }
            }
        } }
        HorizontalDivider()
        // WebDAV is a backup destination, not a general application preference. Keeping it
        // next to backup/restore makes the two independently configured targets discoverable.
        WebDavSettingsPage(settingsStore, reauthenticate, quickPinAvailable, verifyQuickPin, if (helloReauthenticationAvailable) onHelloReauthenticate else null)
    }
    if (creating || pending != null || pendingCloud != null) ReauthenticationDialog(onDismiss = { creating = false; pending = null; pendingCloud = null }) { pin, key ->
        val root = authenticateRoot(pin, key) ?: return@ReauthenticationDialog false
        runCatching {
            if (creating) {
                val created = history.create(root); entries = history.list()
                if (uploadAfterCreate && settingsStore != null) {
                    val targets = configuredWebDavTargets(settingsStore)
                    val result = DualWebDavBackupService().upload(created.path, targets, settingsStore.load().prefix)
                    message = t("webdav_cloud_added", created.path.fileName.toString(), result.successCount, result.targets.size)
                } else message = t("backup_complete_title")
                messageError = false
            }
            else if (pendingCloud != null && settingsStore != null) {
                val selected = checkNotNull(pendingCloud)
                val target = configuredWebDavTargets(settingsStore).singleOrNull { it.id == selected.targetId } ?: error("WebDAV target is unavailable")
                val temporary = Files.createTempFile("keyscan-cloud-restore-", ".dat")
                try {
                    require(target.client.download(selected.file.path, temporary)) { "WebDAV download failed" }
                    val imported = history.importDownloaded(temporary)
                    history.restoreReplace(imported.path, root)
                    entries = history.list(); message = t("webdav_restore_success"); messageError = false; onRestored()
                } finally { Files.deleteIfExists(temporary) }
            }
            else { history.restoreReplace(checkNotNull(pending).path, root); message = t("recovery_completed_reopen"); messageError = false; onRestored() }
        }.onFailure {
            message = if (creating) t("recovery_backup_failed") else t("webdav_restore_failed", localizedReason(it, language))
            messageError = true
        }
        creating = false; uploadAfterCreate = false; pending = null; pendingCloud = null
        true
    }
}

@Composable
private fun TrashPage(vault: EncryptedVaultStore, attachments: EncryptedAttachmentStore, viewOnly: Boolean) {
    val language = LocalAppLanguage.current; fun t(key: String, vararg args: Any) = AndroidStringCatalog.text(language, key, *args)
    var items by remember { mutableStateOf(vault.listTrash()) }; var pendingDelete by remember { mutableStateOf<TrashEntry?>(null) }; var confirmClear by remember { mutableStateOf(false) }; var message by remember { mutableStateOf("") }; var messageError by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column { Text(t("trash_title"), fontSize = 20.sp, fontWeight = FontWeight.SemiBold); Text(t("trash_settings_summary"), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            OutlinedButton(onClick = { confirmClear = true }, enabled = !viewOnly && items.isNotEmpty()) { Text(t("trash_clear_title")) }
        }
        if (viewOnly) Text(t("operation_scope_summary"), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (message.isNotBlank()) Text(message, color = if (messageError) MaterialTheme.colorScheme.error else KeyGreen)
        if (items.isEmpty()) Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) { Text(t("trash_empty"), Modifier.padding(28.dp)) }
        items.forEach { item -> Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) { Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column { Text(item.title.ifBlank { t("trash_unnamed") }, fontWeight = FontWeight.SemiBold); Text("${when(item.type) { TrashType.PASSWORD -> t("trash_type_password"); TrashType.OTP -> t("trash_type_otp"); TrashType.VAULT -> t("trash_type_vault") }} · ${java.time.Instant.ofEpochMilli(item.deletedAt)}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }
            Row { Button(onClick = { if (vault.restoreTrash(item.id)) { items = vault.listTrash(); message = t("trash_restore_success"); messageError = false } else { message = t("trash_restore_failed"); messageError = true } }, enabled = !viewOnly) { Text(t("trash_action_restore")) }; Spacer(Modifier.width(7.dp)); OutlinedButton(onClick = { pendingDelete = item }, enabled = !viewOnly) { Text(t("trash_action_delete_permanently")) } }
        } } }
    }
    pendingDelete?.let { item -> AlertDialog(onDismissRequest = { pendingDelete = null }, title = { Text(t("trash_delete_permanently_title")) }, text = { Text(t("trash_delete_permanently_message")) }, confirmButton = { Button(onClick = { vault.permanentlyDeleteTrash(item.id)?.attachments?.forEach(attachments::deleteEncryptedFile); items = vault.listTrash(); pendingDelete = null; message = t("trash_action_delete_permanently"); messageError = false }) { Text(t("trash_action_delete_permanently")) } }, dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(t("cancel")) } }) }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text(t("trash_clear_title")) }, text = { Text(t("trash_clear_message")) }, confirmButton = { Button(onClick = { vault.clearTrash().flatMap { it.attachments }.forEach(attachments::deleteEncryptedFile); items = emptyList(); confirmClear = false; message = t("trash_clear_title"); messageError = false }) { Text(t("trash_clear")) } }, dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(t("cancel")) } })
}

private fun configuredWebDavTargets(store: WebDavSettingsStore): List<WebDavTarget> {
    val settings = store.load()
    return listOf("primary" to settings.primary, "secondary" to settings.secondary).mapNotNull { (id, value) ->
        if (!value.enabled) return@mapNotNull null
        val credentials = store.credentials(id, value.username) ?: return@mapNotNull null
        try { WebDavTarget(id, HttpWebDavClient(value.url, credentials)) } finally { credentials.password.fill('\u0000') }
    }
}

@Composable
private fun DataManagementPage(
    vault: EncryptedVaultStore,
    attachmentStore: EncryptedAttachmentStore,
    history: BackupHistoryManager,
    webDavSettings: WebDavSettingsStore?,
    appSettings: AppSettings,
    onSettings: (AppSettings) -> Unit,
    viewOnly: Boolean,
    browserPlugins: () -> List<BrowserPluginRecord>,
    revokeBrowserPlugin: (String) -> Unit,
    authenticateRoot: (String, String) -> String?,
    reauthenticate: (String, String) -> Boolean,
    quickPinAvailable: Boolean,
    verifyQuickPin: (String) -> Boolean,
    helloReauthenticationAvailable: Boolean,
    onHelloReauthenticate: suspend () -> Boolean,
    onLock: () -> Unit,
    windowsHelloSupported: Boolean,
    helloAvailability: WindowsHelloAvailability,
    helloConfigured: Boolean,
    onEnableHello: suspend () -> Boolean,
    onDisableHello: suspend () -> Unit,
) {
    val language = LocalAppLanguage.current; fun t(key: String, vararg args: Any) = AndroidStringCatalog.text(language, key, *args)
    var section by remember { mutableStateOf("backup") }
    val backupCount = remember { history.list().size }
    val trashCount = remember { vault.listTrash().size }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(t("data_insurance_subtitle"), color = MaterialTheme.colorScheme.onSurfaceVariant)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val compact = maxWidth < 780.dp
            if (compact) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    DataManagementEntry(Icons.Filled.Backup, t("home_webdav"), t("primary_backup_summary"), KeyGreen, section == "backup") { section = "backup" }
                    DataManagementEntry(Icons.Filled.Delete, t("trash_title"), "$trashCount", Color(0xFFEF5350), section == "trash") { section = "trash" }
                    DataManagementEntry(Icons.Filled.Settings, LocalUiText.current.settings, t("settings_title"), Color(0xFF718096), section == "settings") { section = "settings" }
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DataManagementEntry(Icons.Filled.Backup, t("home_webdav"), "${t("primary_backup_summary")} · $backupCount", KeyGreen, section == "backup", Modifier.weight(1f)) { section = "backup" }
                    DataManagementEntry(Icons.Filled.Delete, t("trash_title"), "$trashCount", Color(0xFFEF5350), section == "trash", Modifier.weight(1f)) { section = "trash" }
                    DataManagementEntry(Icons.Filled.Settings, LocalUiText.current.settings, t("settings_title"), Color(0xFF718096), section == "settings", Modifier.weight(1f)) { section = "settings" }
                }
            }
        }
        HorizontalDivider()
        when (section) {
            "trash" -> TrashPage(vault, attachmentStore, viewOnly)
            "settings" -> SettingsPage(webDavSettings, appSettings, onSettings, browserPlugins, revokeBrowserPlugin, windowsHelloSupported, helloAvailability, helloConfigured, onEnableHello, onDisableHello)
            else -> BackupHistoryPage(history, webDavSettings, viewOnly, authenticateRoot, reauthenticate, quickPinAvailable, verifyQuickPin, helloReauthenticationAvailable, onHelloReauthenticate, onLock)
        }
    }
}

@Composable
private fun DataManagementEntry(icon: ImageVector, title: String, detail: String, color: Color, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier.heightIn(min = 82.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.outlinedButtonColors(containerColor = if (active) color.copy(alpha = .10f) else Color.Transparent)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).background(color.copy(alpha = .16f), RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) { Icon(icon, title, tint = color, modifier = Modifier.size(24.dp)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 2)
            }
        }
    }
}

@Composable
private fun SettingsPage(store: WebDavSettingsStore?, appSettings: AppSettings, onSettings: (AppSettings) -> Unit, browserPlugins: () -> List<BrowserPluginRecord>, revokeBrowserPlugin: (String) -> Unit, windowsHelloSupported: Boolean, helloAvailability: WindowsHelloAvailability, helloConfigured: Boolean, onEnableHello: suspend () -> Boolean, onDisableHello: suspend () -> Unit) {
    val language = LocalAppLanguage.current; fun t(key: String, vararg args: Any) = AndroidStringCatalog.text(language, key, *args)
    var defaultView by remember(appSettings) { mutableStateOf(appSettings.viewOnly) }
    var languageMenu by remember { mutableStateOf(false) }; val text = LocalUiText.current
    var helloWorking by remember { mutableStateOf(false) }; var helloMessage by remember { mutableStateOf("") }; val scope = rememberCoroutineScope()
    var showPolicy by remember { mutableStateOf(false) }; var showAbout by remember { mutableStateOf(false) }; var supportMessage by remember { mutableStateOf("") }
    var pairedPlugins by remember { mutableStateOf(browserPlugins()) }
    val support = supportText(appSettings.language)
    // DesktopHome already owns the page scroll state. A second verticalScroll
    // here receives unbounded height and crashes Compose in a small window.
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Text(text.language, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Box { OutlinedButton(onClick = { languageMenu = true }) { Text(com.keyscan.core.model.AppLanguage.entries.first { it == appSettings.language }.label) }; DropdownMenu(languageMenu, { languageMenu = false }) {
            com.keyscan.core.model.AppLanguage.entries.forEach { language -> DropdownMenuItem({ Text(language.label) }, onClick = { languageMenu = false; onSettings(appSettings.copy(language = language)) }) }
        } }
        Text(text.theme, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                com.keyscan.core.model.ThemeMode.SYSTEM to "${text.system}  •  ◐",
                com.keyscan.core.model.ThemeMode.LIGHT to "${text.light}  •  ☀",
                com.keyscan.core.model.ThemeMode.DARK to "${text.dark}  •  ◑",
            ).forEach { (mode, label) ->
                val selected = appSettings.themeMode == mode
                OutlinedButton(onClick = { onSettings(appSettings.copy(themeMode = mode)) }, modifier = Modifier.fillMaxWidth(), colors = if (selected) ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else ButtonDefaults.outlinedButtonColors()) { Text(label, modifier = Modifier.fillMaxWidth()) }
            }
        } }
        if (windowsHelloSupported) {
            HorizontalDivider()
            Text(text.windowsHello, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(when {
                helloConfigured -> "${t("biometric_unlock_enabled")}. ${t("security_biometric_note")}"
                helloAvailability == WindowsHelloAvailability.AVAILABLE -> t("biometric_unlock_subtitle")
                else -> t("biometric_unlock_unavailable")
            }, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (helloMessage.isNotBlank()) Text(helloMessage, color = if (helloMessage == t("biometric_unlock_unavailable")) MaterialTheme.colorScheme.error else KeyGreen)
            if (helloConfigured) OutlinedButton(onClick = { helloWorking = true; scope.launch { onDisableHello(); helloMessage = t("disabled"); helloWorking = false } }, enabled = !helloWorking) { Text("${t("disable")} ${text.windowsHello}") }
            else Button(onClick = { helloWorking = true; scope.launch { helloMessage = if (onEnableHello()) t("biometric_unlock_enabled") else t("biometric_unlock_unavailable"); helloWorking = false } }, enabled = !helloWorking && helloAvailability == WindowsHelloAvailability.AVAILABLE) { Text(if (helloWorking) "…" else "${t("enable")} ${text.windowsHello}") }
        }
        HorizontalDivider()
        Text(t("operation_mode_title"), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(defaultView, { defaultView = it; onSettings(appSettings.copy(viewOnly = it)) }); Spacer(Modifier.width(10.dp)); Column { Text(t("operation_view_mode")); Text(t("operation_view_summary"), color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        Text(t("security_auto_lock"), fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(1, 5, 10, 15, 30).forEach { minutes -> FilterChip(appSettings.autoLockMinutes == minutes, { onSettings(appSettings.copy(autoLockMinutes = minutes)) }, { Text(t("security_auto_lock_minutes", minutes)) }) } }
        Text(t("clipboard_auto_clear"), fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(15, 30, 60, 120).forEach { seconds -> FilterChip(appSettings.clipboardClearSeconds == seconds, { onSettings(appSettings.copy(clipboardClearSeconds = seconds)) }, { Text("$seconds ${t("seconds_unit")}") }) } }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column { Text(t("desktop_browser_plugins"), fontSize = 20.sp, fontWeight = FontWeight.SemiBold); Text(t("desktop_plugin_scope_note"), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            OutlinedButton(onClick = { pairedPlugins = browserPlugins() }) { Text(t("share_refresh")) }
        }
        if (!DesktopPlatform.current().supportsWindowsNativeMessaging) {
            Text(t("desktop_plugin_windows_only"), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (pairedPlugins.isEmpty()) Text(t("desktop_plugin_none_authorized"), color = MaterialTheme.colorScheme.onSurfaceVariant)
        pairedPlugins.forEach { plugin -> Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
            Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(plugin.browser.replaceFirstChar { it.uppercase() }, fontWeight = FontWeight.SemiBold)
                    Text(t("desktop_plugin_ext_line", plugin.extensionId, plugin.version.ifBlank { t("desktop_unknown_version") }), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    val state = when {
                        plugin.lastSeenAt == null -> t("desktop_plugin_state_never")
                        java.time.Duration.between(plugin.lastSeenAt, java.time.Instant.now()).seconds <= 90 -> t("desktop_plugin_state_connected")
                        else -> t("desktop_plugin_state_disconnected")
                    }
                    Text(t("desktop_plugin_state_line", state, plugin.lastSeenAt?.toString() ?: t("desktop_not_connected_yet")), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
                OutlinedButton(onClick = { revokeBrowserPlugin(plugin.caller); pairedPlugins = browserPlugins() }) { Text(t("desktop_revoke_authorization")) }
            }
        } }
        HorizontalDivider()
        Text(support.section, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { supportMessage = runCatching { DesktopDocuments.openHelp(appSettings.language); "" }.getOrElse { support.openFailed } }) { Text(support.help) }
            OutlinedButton(onClick = { showPolicy = true }) { Text(support.policy) }
            OutlinedButton(onClick = { showAbout = true }) { Text(support.about) }
            OutlinedButton(onClick = { supportMessage = runCatching { DesktopDocuments.openFeedback(); "" }.getOrElse { support.openFailed } }) { Text(support.feedback) }
        }
        if (supportMessage.isNotBlank()) Text(supportMessage, color = MaterialTheme.colorScheme.error)
    }
    if (showPolicy) AlertDialog(
        onDismissRequest = { showPolicy = false },
        title = { Text(support.policy) },
        text = { SelectionContainer { Text(DesktopDocuments.policy(appSettings.language), Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) } },
        confirmButton = { TextButton(onClick = { showPolicy = false }) { Text(support.close) } },
    )
    if (showAbout) AlertDialog(
        onDismissRequest = { showAbout = false },
        title = { Text(support.about) },
        text = { SelectionContainer { Text("${support.aboutBody}\n\nVersion $DESKTOP_VERSION") } },
        confirmButton = { TextButton(onClick = { showAbout = false }) { Text(support.close) } },
    )
}

@Composable
private fun WebDavSettingsPage(store: WebDavSettingsStore?, reauthenticate: (String, String) -> Boolean, quickPinAvailable: Boolean, verifyQuickPin: (String) -> Boolean, onHelloVerify: (suspend () -> Boolean)?) {
    val language = LocalAppLanguage.current; fun t(key: String, vararg args: Any) = AndroidStringCatalog.text(language, key, *args)
    if (store == null) { Text(t("webdav_unconfigured")); return }
    val scope = rememberCoroutineScope()
    val initial = remember { store.load() }
    var primary by remember { mutableStateOf(initial.primary) }; var secondary by remember { mutableStateOf(initial.secondary) }
    var primaryPassword by remember { mutableStateOf("") }; var secondaryPassword by remember { mutableStateOf("") }
    var prefix by remember { mutableStateOf(initial.prefix) }; var message by remember { mutableStateOf("") }; var messageError by remember { mutableStateOf(false) }
    var primaryTested by remember { mutableStateOf(false) }; var secondaryTested by remember { mutableStateOf(false) }
    var primaryTesting by remember { mutableStateOf(false) }; var secondaryTesting by remember { mutableStateOf(false) }
    fun testFailure(result: WebDavConnectionResult) = when (result.statusCode) {
        401, 403 -> "${t("authentication_failed")} (HTTP ${result.statusCode})"
        404 -> "${t("network_test_connection_failed")} (HTTP 404 · WebDAV path not found)"
        405 -> "${t("network_test_connection_failed")} (HTTP 405 · PROPFIND not supported at this address)"
        in 300..399 -> "${t("network_test_connection_failed")} (HTTP ${result.statusCode} · redirect${result.redirectLocation?.let { ": ${it.take(160)}" } ?: ""})"
        else -> "${t("network_test_connection_failed")} (HTTP ${result.statusCode ?: "—"})"
    }
    var pendingReveal by remember { mutableStateOf<String?>(null) }; var revealed by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    // A password is only revealed after explicit reauthentication.  Remove the
    // visible copy promptly so navigating away is not the only way to hide it.
    LaunchedEffect(revealed) {
        if (revealed.isNotEmpty()) { delay(30_000); revealed = emptyMap() }
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(t("webdav_title"), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text(t("webdav_desc"), color = MaterialTheme.colorScheme.onSurfaceVariant)
        WebDavTargetEditor(t("main_webdav_title"), "primary", primary, primaryPassword, revealed["primary"], primaryTesting, { primary = it; primaryTested = false }, { primaryPassword = it; primaryTested = false }, { pendingReveal = "primary" }, onTest = {
            if (!primaryTesting) scope.launch {
                primaryTesting = true; message = "${t("main_webdav_title")}: ${t("test_connection")}…"; messageError = false
                val outcome = withContext(Dispatchers.IO) { runCatching { testWebDavTarget(store, "primary", primary, primaryPassword) } }
                message = outcome.fold({ result -> primaryTested = result.success; messageError = !result.success; if (result.success) "${t("main_webdav_title")}: ${t("network_test_connection_success", "${result.latencyMillis} ms")}" else testFailure(result) }, { error -> primaryTested = false; messageError = true; webDavExceptionMessage(error, ::t) })
                primaryTesting = false
            }
        }, onSave = {
            message = runCatching { require(primaryTested) { "Test first" }; requireWebDavPassword(store, "primary", primary, primaryPassword); store.save(WebDavSettings(primary, secondary, prefix), primaryPassword.takeIf(String::isNotEmpty)?.toCharArray(), secondaryPassword.takeIf(String::isNotEmpty)?.toCharArray()); primaryPassword = ""; secondaryPassword = ""; revealed = emptyMap(); t("webdav_saved") }.fold({ messageError = false; it }, { error -> messageError = true; if (error.message == "Test first") t("test_connection_first") else if (error.message == "WebDAV password is required") t("webdav_password_required") else "${t("connection_failed")} (${error.javaClass.simpleName})" })
        })
        WebDavTargetEditor(t("backup_webdav_title"), "secondary", secondary, secondaryPassword, revealed["secondary"], secondaryTesting, { secondary = it; secondaryTested = false }, { secondaryPassword = it; secondaryTested = false }, { pendingReveal = "secondary" }, onTest = {
            if (!secondaryTesting) scope.launch {
                secondaryTesting = true; message = "${t("backup_webdav_title")}: ${t("test_connection")}…"; messageError = false
                val outcome = withContext(Dispatchers.IO) { runCatching { testWebDavTarget(store, "secondary", secondary, secondaryPassword) } }
                message = outcome.fold({ result -> secondaryTested = result.success; messageError = !result.success; if (result.success) "${t("backup_webdav_title")}: ${t("network_test_connection_success", "${result.latencyMillis} ms")}" else testFailure(result) }, { error -> secondaryTested = false; messageError = true; webDavExceptionMessage(error, ::t) })
                secondaryTesting = false
            }
        }, onSave = {
            message = runCatching { require(secondaryTested) { "Test first" }; requireWebDavPassword(store, "secondary", secondary, secondaryPassword); store.save(WebDavSettings(primary, secondary, prefix), primaryPassword.takeIf(String::isNotEmpty)?.toCharArray(), secondaryPassword.takeIf(String::isNotEmpty)?.toCharArray()); primaryPassword = ""; secondaryPassword = ""; revealed = emptyMap(); t("webdav_saved") }.fold({ messageError = false; it }, { error -> messageError = true; if (error.message == "Test first") t("test_connection_first") else if (error.message == "WebDAV password is required") t("webdav_password_required") else "${t("connection_failed")} (${error.javaClass.simpleName})" })
        })
        OutlinedTextField(prefix, { prefix = it }, label = { Text(t("backup_file_prefix_title")) }, supportingText = { Text(t("backup_file_prefix_hint")) }, singleLine = true)
        if (message.isNotBlank()) Text(message, color = if (messageError) MaterialTheme.colorScheme.error else KeyGreen)
    }
    pendingReveal?.let { target -> ReauthenticationDialog(requireDataKey = !quickPinAvailable, onDismiss = { pendingReveal = null }, onHelloVerify = onHelloVerify) { pin, key ->
        val verified = if (quickPinAvailable) verifyQuickPin(pin) else reauthenticate(pin, key)
        if (!verified) false else {
            val settings = if (target == "primary") primary else secondary
            val credentials = runCatching { store.credentials(target, settings.username) }.getOrNull()
            if (credentials == null) { message = t("webdav_password_required"); messageError = true; false }
            else try { revealed = revealed + (target to String(credentials.password)); pendingReveal = null; true } finally { credentials.password.fill('\u0000') }
        }
    } }
}

@Composable
private fun WebDavTargetEditor(title: String, id: String, value: WebDavTargetSettings, password: String, revealedPassword: String?, testing: Boolean, onValue: (WebDavTargetSettings) -> Unit, onPassword: (String) -> Unit, onReveal: () -> Unit, onTest: () -> Unit, onSave: () -> Unit) {
    val language = LocalAppLanguage.current; fun t(key: String, vararg args: Any) = AndroidStringCatalog.text(language, key, *args)
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(value.enabled, { onValue(value.copy(enabled = it)) }); Spacer(Modifier.width(10.dp)); Text(title, fontWeight = FontWeight.SemiBold) }
        OutlinedTextField(value.url, { onValue(value.copy(url = it)) }, Modifier.fillMaxWidth(), label = { Text(t("webdav_address")) }, placeholder = { Text("https://example.com/dav/path") }, singleLine = true, enabled = value.enabled)
        // A row makes both fields too narrow in the compact desktop window.
        // Stack them so account, password and validation are all reachable.
        OutlinedTextField(value.username, { onValue(value.copy(username = it)) }, Modifier.fillMaxWidth(), label = { Text(t("username")) }, singleLine = true, enabled = value.enabled)
        OutlinedTextField(revealedPassword ?: password, onPassword, Modifier.fillMaxWidth(), label = { Text(t("webdav_password")) }, singleLine = true, enabled = value.enabled, readOnly = revealedPassword != null, visualTransformation = if (revealedPassword == null) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None)
        if (value.enabled && revealedPassword == null) TextButton(onClick = onReveal) { Text(t("webdav_visibility_show")) }
        if (value.enabled) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { OutlinedButton(onClick = onTest, enabled = !testing) { Text(if (testing) "${t("test_connection")}…" else t("test_connection")) }; Button(onClick = onSave, enabled = !testing) { Text(t("save_webdav_settings")) } }
    } }
}

private fun requireWebDavPassword(store: WebDavSettingsStore, id: String, target: WebDavTargetSettings, entered: String) {
    require(target.enabled && target.url.isNotBlank() && target.username.isNotBlank()) { "WebDAV password is required" }
    require(entered.isNotEmpty() || store.credentials(id, target.username) != null) { "WebDAV password is required" }
}

private fun testWebDavTarget(store: WebDavSettingsStore, id: String, target: WebDavTargetSettings, entered: String): WebDavConnectionResult {
    requireWebDavPassword(store, id, target, entered)
    val password = if (entered.isNotEmpty()) entered.toCharArray() else store.credentials(id, target.username)!!.password
    return try { HttpWebDavClient(target.url.trim().trimEnd('/'), WebDavCredentials(target.username.trim(), password)).testConnection() } finally { password.fill('\u0000') }
}

private fun webDavExceptionMessage(error: Throwable, t: (String, Array<out Any>) -> String): String {
    val reason = when (error) {
        is java.net.UnknownHostException -> t("dns_resolution_failed", emptyArray())
        is java.net.http.HttpConnectTimeoutException, is java.net.http.HttpTimeoutException -> t("connection_timeout", emptyArray())
        is javax.net.ssl.SSLException -> t("tls_certificate_error", emptyArray())
        is java.net.ConnectException, is java.net.NoRouteToHostException -> t("host_unreachable", emptyArray())
        is IllegalArgumentException -> t("address_format_error", emptyArray())
        else -> t("webdav_connection_failed", emptyArray())
    }
    val detail = error.message?.replace(Regex("[\\r\\n]+"), " ")?.take(180)?.takeIf(String::isNotBlank)
    return if (detail == null) "$reason (${error.javaClass.simpleName})" else "$reason (${error.javaClass.simpleName}: $detail)"
}

@Composable
private fun PasswordLedger(vault: EncryptedVaultStore, viewOnly: Boolean, clipboard: SecureClipboard, clipboardClearSeconds: Int, reauthenticate: (String, String) -> Boolean, quickPinAvailable: Boolean, verifyQuickPin: (String) -> Boolean, onHelloVerify: (suspend () -> Boolean)?, onRequestEdit: () -> Unit) {
    val language = LocalAppLanguage.current; fun t(key: String, vararg args: Any) = AndroidStringCatalog.text(language, key, *args)
    var entries by remember { mutableStateOf(vault.listPasswords()) }
    var groups by remember { mutableStateOf(vault.listPasswordGroups()) }
    var showEditor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<PasswordEntry?>(null) }
    var query by remember { mutableStateOf("") }
    var imported by remember { mutableStateOf<List<ImportedPassword>?>(null) }
    var exchangeMessage by remember { mutableStateOf("") }
    var exchangeError by remember { mutableStateOf(false) }
    var showExportWarning by remember { mutableStateOf(false) }; var pendingExportFormat by remember { mutableStateOf<String?>(null) }
    var ledgerMenuExpanded by remember { mutableStateOf(false) }
    var sortMode by remember { mutableStateOf("name") }
    var selectedGroup by remember { mutableStateOf<String?>(null) }; var showGroupEditor by remember { mutableStateOf(false) }
    var historyAuthEntry by remember { mutableStateOf<PasswordEntry?>(null) }; var historyEntry by remember { mutableStateOf<PasswordEntry?>(null) }
    val visible = entries.filter { (selectedGroup == null || it.groupId == selectedGroup) && (query.isBlank() || listOf(it.title, it.username, it.websiteDomain).any { value -> value.contains(query, ignoreCase = true) }) }
        .let { values -> if (sortMode == "time") values.sortedByDescending { it.updatedAt } else values.sortedWith(compareBy<PasswordEntry> { it.title.lowercase() }.thenBy { it.username.lowercase() }) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(t("password_forge_title"), color = AndroidPrimaryTitle, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Box {
                IconButton(onClick = { ledgerMenuExpanded = true }) { Icon(Icons.Filled.MoreVert, contentDescription = null, tint = AndroidPrimaryTitle) }
                DropdownMenu(expanded = ledgerMenuExpanded, onDismissRequest = { ledgerMenuExpanded = false }) {
                    DropdownMenuItem(text = { Text(pm("add")) }, leadingIcon = { Icon(Icons.Filled.PersonAdd, contentDescription = null) }, onClick = { ledgerMenuExpanded = false; if (viewOnly) onRequestEdit() else { editing = null; showEditor = true } })
                    DropdownMenuItem(text = { Text(pm("new_group")) }, leadingIcon = { Icon(Icons.Filled.Inventory2, contentDescription = null) }, onClick = { ledgerMenuExpanded = false; if (viewOnly) onRequestEdit() else showGroupEditor = true })
                    DropdownMenuItem(text = { Text(pm("import")) }, leadingIcon = { Icon(Icons.Filled.FileUpload, contentDescription = null) }, onClick = {
                        ledgerMenuExpanded = false
                        if (viewOnly) onRequestEdit() else {
                            chooseFile(FileDialog.LOAD, t("import_choose_file"))?.let { path ->
                                runCatching { if (path.fileName.toString().endsWith(".csv", true)) PasswordExchange.importCsv(path) else PasswordExchange.importBitwarden(path) }
                                    .onSuccess { imported = it; exchangeMessage = "${t("import_password_count", it.size)} · ${t("import_continue_preview")}"; exchangeError = false }
                                    .onFailure { exchangeMessage = t("file_read_failed"); exchangeError = true }
                            }
                        }
                    })
                    DropdownMenuItem(text = { Text(pm("export")) }, leadingIcon = { Icon(Icons.Filled.FileDownload, contentDescription = null) }, enabled = entries.isNotEmpty(), onClick = { ledgerMenuExpanded = false; showExportWarning = true })
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query, { query = it }, label = { Text(pm("search")) }, singleLine = true, leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) }, modifier = Modifier.weight(1f))
            FilterChip(sortMode == "name", { sortMode = "name" }, { Text(t("vault_field_name")) })
            FilterChip(sortMode == "time", { sortMode = "time" }, { Text(t("history_column_time")) })
        }
        Text(pm("accounts", entries.size), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (exchangeMessage.isNotBlank()) Text(exchangeMessage, color = if (exchangeError) MaterialTheme.colorScheme.error else KeyGreen)
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            FilterChip(selectedGroup == null, { selectedGroup = null }, { Text(pm("all")) })
            groups.take(6).forEach { group -> FilterChip(selectedGroup == group.id, { selectedGroup = group.id }, { Text(group.name) }) }
            if (!viewOnly) AssistChip(onClick = { showGroupEditor = true }, label = { Text(pm("new_group")) })
        }
        if (visible.isEmpty()) Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(30.dp)) { Text(pm("empty"), fontSize = 20.sp, fontWeight = FontWeight.SemiBold); Text(pm("empty_hint"), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else visible.forEach { entry ->
            var itemMenuExpanded by remember(entry.id) { mutableStateOf(false) }
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.fillMaxWidth().padding(start = 18.dp, top = 16.dp, end = 10.dp, bottom = 16.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(entry.title, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(entry.username, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, maxLines = 1)
                        Text(entry.websiteDomain, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp, maxLines = 1)
                        groups.firstOrNull { it.id == entry.groupId }?.let { Text(it.name, color = MaterialTheme.colorScheme.tertiary, fontSize = 12.sp, maxLines = 1) }
                    }
                    Box {
                        IconButton(onClick = { itemMenuExpanded = true }, modifier = Modifier.size(34.dp)) {
                            Icon(Icons.Filled.MoreVert, contentDescription = null, tint = AndroidPrimaryTitle, modifier = Modifier.size(19.dp))
                        }
                        DropdownMenu(expanded = itemMenuExpanded, onDismissRequest = { itemMenuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text(pm("copy")) },
                                leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                                onClick = { itemMenuExpanded = false; clipboard.copy(entry.password, clipboardClearSeconds) }
                            )
                            DropdownMenuItem(
                                text = { Text(pm("history")) },
                                leadingIcon = { Icon(Icons.Filled.History, contentDescription = null) },
                                onClick = { itemMenuExpanded = false; historyAuthEntry = entry }
                            )
                            DropdownMenuItem(
                                text = { Text(pm("edit")) },
                                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                                onClick = {
                                    itemMenuExpanded = false
                                    if (viewOnly) onRequestEdit() else { editing = entry; showEditor = true }
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(pm("delete")) },
                                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    itemMenuExpanded = false
                                    if (viewOnly) onRequestEdit() else { vault.deletePassword(entry.id); entries = vault.listPasswords() }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
    if (showEditor && !viewOnly) PasswordEditor(editing, groups, onDismiss = { showEditor = false }) { entry ->
        vault.savePassword(entry); entries = vault.listPasswords(); showEditor = false
    }
    if (showGroupEditor && !viewOnly) NewGroupDialog(onDismiss = { showGroupEditor = false }) { name -> val now = System.currentTimeMillis(); vault.savePasswordGroup(PasswordGroup(UUID.randomUUID().toString(), name, groups.size, createdAt = now, updatedAt = now)); groups = vault.listPasswordGroups(); showGroupEditor = false }
    historyAuthEntry?.let { entry -> ReauthenticationDialog(requireDataKey = !quickPinAvailable, onDismiss = { historyAuthEntry = null }, onHelloVerify = onHelloVerify, onHelloVerified = { historyEntry = entry; historyAuthEntry = null }) { pin, key ->
        val verified = if (quickPinAvailable) verifyQuickPin(pin) else reauthenticate(pin, key)
        if (verified) { historyEntry = entry; historyAuthEntry = null; true } else false
    } }
    historyEntry?.let { entry -> PasswordHistoryDialog(entry, vault.listPasswordHistory(entry.id), viewOnly, onDismiss = { historyEntry = null }, onRestore = { id -> vault.restorePasswordHistory(id); entries = vault.listPasswords(); historyEntry = null }, onDelete = { id -> vault.deletePasswordHistory(id); historyEntry = null }) }
    imported?.takeIf { !viewOnly }?.let { preview -> ImportPreviewDialog(preview, viewOnly, onDismiss = { imported = null }) { strategy ->
        runCatching { PasswordExchange.commit(vault, preview, strategy) }.onSuccess { result ->
            entries = vault.listPasswords(); imported = null; exchangeMessage = listOf(t("import_result_success", result.added), "${t("import_conflict_overwrite")}: ${result.overwritten}", t("import_result_skipped", result.skipped)).joinToString(" · "); exchangeError = false
        }.onFailure { exchangeMessage = t("import_failed", t("import_unrecognized")); exchangeError = true }
    } }
    if (showExportWarning) ExportWarningDialog(onDismiss = { showExportWarning = false }) { format ->
        pendingExportFormat = format; showExportWarning = false
    }
    pendingExportFormat?.let { format -> ReauthenticationDialog(requireDataKey = !quickPinAvailable, onDismiss = { pendingExportFormat = null }, onHelloVerify = onHelloVerify, onHelloVerified = {
        val extension = if (format == "csv") ".csv" else ".json"
        chooseFile(FileDialog.SAVE, t("export_choose_location"), "keyscan_passwords$extension")?.let { selected ->
            val destination = if (selected.fileName.toString().endsWith(extension, true)) selected else selected.resolveSibling(selected.fileName.toString() + extension)
            runCatching { if (format == "csv") PasswordExchange.exportCsv(vault, destination) else PasswordExchange.exportBitwarden(vault, destination) }
                .onSuccess { exchangeMessage = "${t("export_completed")}: ${destination.fileName}. ${t("export_security_warning")}"; exchangeError = false }
                .onFailure { exchangeMessage = t("export_failed", t("export_failed_short")); exchangeError = true }
        }
        pendingExportFormat = null
    }) { pin, key ->
        val verified = if (quickPinAvailable) verifyQuickPin(pin) else reauthenticate(pin, key)
        if (!verified) false else {
            val extension = if (format == "csv") ".csv" else ".json"
            chooseFile(FileDialog.SAVE, t("export_choose_location"), "keyscan_passwords$extension")?.let { selected ->
                val destination = if (selected.fileName.toString().endsWith(extension, true)) selected else selected.resolveSibling(selected.fileName.toString() + extension)
                runCatching { if (format == "csv") PasswordExchange.exportCsv(vault, destination) else PasswordExchange.exportBitwarden(vault, destination) }
                    .onSuccess { exchangeMessage = "${t("export_completed")}: ${destination.fileName}. ${t("export_security_warning")}"; exchangeError = false }
                    .onFailure { exchangeMessage = t("export_failed", t("export_failed_short")); exchangeError = true }
            }
            pendingExportFormat = null; true
        }
    } }
}

@Composable
private fun ImportPreviewDialog(items: List<ImportedPassword>, viewOnly: Boolean, onDismiss: () -> Unit, onCommit: (ConflictStrategy) -> Unit) {
    val language = LocalAppLanguage.current; fun t(key: String, vararg args: Any) = AndroidStringCatalog.text(language, key, *args)
    var strategy by remember { mutableStateOf(ConflictStrategy.SKIP) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(t("import_preview_title")) }, text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("${t("import_password_count", items.size)}. ${t("import_supported_intro")}")
        items.take(5).forEach { Text("• ${it.title.ifBlank { it.website.ifBlank { it.username } }}", maxLines = 1) }
        if (items.size > 5) Text(t("import_preview_limit", 5), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(t("import_conflict_message"), fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            FilterChip(strategy == ConflictStrategy.SKIP, { strategy = ConflictStrategy.SKIP }, { Text(t("import_conflict_skip")) })
            FilterChip(strategy == ConflictStrategy.KEEP_BOTH, { strategy = ConflictStrategy.KEEP_BOTH }, { Text(t("import_conflict_keep_both")) })
            FilterChip(strategy == ConflictStrategy.OVERWRITE, { strategy = ConflictStrategy.OVERWRITE }, { Text(t("import_conflict_overwrite")) }, enabled = !viewOnly)
        }
        if (viewOnly) Text(t("operation_scope_summary"), color = MaterialTheme.colorScheme.onSurfaceVariant)
    } }, confirmButton = { Button(onClick = { onCommit(strategy) }, enabled = items.isNotEmpty() && !viewOnly) { Text(t("import_confirm_action")) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(t("cancel")) } })
}

@Composable
private fun ExportWarningDialog(onDismiss: () -> Unit, onExport: (String) -> Unit) {
    val language = LocalAppLanguage.current; fun t(key: String, vararg args: Any) = AndroidStringCatalog.text(language, key, *args)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(t("export_confirm_title")) }, text = { Text(t("export_confirm_message")) },
        confirmButton = { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick = { onExport("csv") }) { Text("${t("export_action")} CSV") }; Button(onClick = { onExport("json") }) { Text("${t("export_action")} Bitwarden JSON") } } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("cancel")) } })
}

private fun chooseFile(mode: Int, title: String, suggestedName: String? = null): Path? {
    val dialog = FileDialog(null as Frame?, title, mode).apply { if (suggestedName != null) file = suggestedName; isVisible = true }
    val directory = dialog.directory ?: return null; val filename = dialog.file ?: return null
    return Path.of(directory, filename)
}

@Composable
private fun PasswordEditor(existing: PasswordEntry?, groups: List<PasswordGroup>, onDismiss: () -> Unit, onSave: (PasswordEntry) -> Unit) {
    var title by remember(existing) { mutableStateOf(existing?.title.orEmpty()) }; var website by remember(existing) { mutableStateOf(existing?.websiteDomain.orEmpty()) }
    var username by remember(existing) { mutableStateOf(existing?.username.orEmpty()) }; var password by remember(existing) { mutableStateOf(existing?.password.orEmpty()) }; var notes by remember(existing) { mutableStateOf(existing?.notes.orEmpty()) }; var groupId by remember(existing) { mutableStateOf(existing?.groupId) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (existing == null) pm("add") else "${pm("edit")}: ${pm("password")}") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(title, { title = it }, label = { Text(pm("title")) }, singleLine = true)
            OutlinedTextField(website, { website = it }, label = { Text(pm("website")) }, singleLine = true)
            OutlinedTextField(username, { username = it }, label = { Text(pm("username")) }, singleLine = true)
            SecretField(pm("password"), password) { password = it }
            OutlinedTextField(notes, { notes = it }, label = { Text(pm("notes")) })
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { FilterChip(groupId == null, { groupId = null }, { Text(pm("ungrouped")) }); groups.take(5).forEach { group -> FilterChip(groupId == group.id, { groupId = group.id }, { Text(group.name) }) } }
        }
    }, confirmButton = { Button(onClick = {
        val now = System.currentTimeMillis()
        onSave(existing?.copy(title = title, websiteDomain = website, username = username, password = password, notes = notes, groupId = groupId, updatedAt = now)
            ?: PasswordEntry(UUID.randomUUID().toString(), title, website, username, password, notes, groupId = groupId, createdAt = now, updatedAt = now))
    }, enabled = title.isNotBlank() && password.isNotEmpty()) { Text(pm("save")) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(m("cancel")) } })
}

@Composable
private fun NewGroupDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(pm("new_group")) }, text = { OutlinedTextField(name, { name = it }, label = { Text(pm("title")) }, singleLine = true) },
        confirmButton = { Button(onClick = { onSave(name.trim()) }, enabled = name.trim().isNotEmpty()) { Text(m("create")) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(m("cancel")) } })
}

@Composable
private fun PasswordHistoryDialog(entry: PasswordEntry, histories: List<PasswordHistory>, viewOnly: Boolean, onDismiss: () -> Unit, onRestore: (String) -> Unit, onDelete: (String) -> Unit) {
    val language = LocalAppLanguage.current; fun t(key: String, vararg args: Any) = AndroidStringCatalog.text(language, key, *args)
    var revealed by remember { mutableStateOf<Set<String>>(emptySet()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("${entry.title} · ${pm("history")}") }, text = { Column(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(t("password_history_local_notice"), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (histories.isEmpty()) Text(t("password_history_empty"))
        histories.take(20).forEach { history -> Card(Modifier.fillMaxWidth()) { Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(if (history.historyId in revealed) history.oldPassword else "••••••••"); Text("${history.source} · ${java.time.Instant.ofEpochMilli(history.createdAt)}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }
            Row { TextButton(onClick = { revealed = if (history.historyId in revealed) revealed - history.historyId else revealed + history.historyId }) { Text(if (history.historyId in revealed) "◉" else "◎") }; if (!viewOnly) { TextButton(onClick = { onRestore(history.historyId) }) { Text("↶") }; TextButton(onClick = { onDelete(history.historyId) }) { Text(pm("delete")) } } }
        } } }
    } }, confirmButton = { TextButton(onClick = onDismiss) { Text(t("ok")) } })
}

@Composable
private fun TotpLedger(vault: EncryptedVaultStore, viewOnly: Boolean, onRequestEdit: () -> Unit) {
    val language = LocalAppLanguage.current
    fun t(key: String, vararg args: Any) = AndroidStringCatalog.text(language, key, *args)

    var entries by remember { mutableStateOf(vault.listTotp()) }; var showEditor by remember { mutableStateOf(false) }; var showImport by remember { mutableStateOf(false) }; var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var editingToken by remember { mutableStateOf<TotpEntry?>(null) }
    var query by remember { mutableStateOf("") }; var menuExpanded by remember { mutableStateOf(false) }; var message by remember { mutableStateOf("") }; var messageError by remember { mutableStateOf(false) }
    var sortMode by remember { mutableStateOf("name") }
    fun refreshTotp() { entries = vault.listTotp() }
    fun orderedTotp(): List<TotpEntry> = entries.sortedWith(compareByDescending<TotpEntry> { it.pinned }.thenBy { it.sortOrder }.thenBy { it.issuer }.thenBy { it.accountName })
    fun normalizeTotpOrder(): List<TotpEntry> {
        orderedTotp().forEachIndexed { index, token -> vault.saveTotp(token.copy(sortOrder = index * 10, updatedAt = System.currentTimeMillis())) }
        refreshTotp()
        return orderedTotp()
    }
    fun moveTotp(token: TotpEntry, delta: Int) {
        val ordered = normalizeTotpOrder()
        val index = ordered.indexOfFirst { it.itemId == token.itemId }
        val targetIndex = (index + delta).takeIf { index >= 0 && it in ordered.indices } ?: return
        val current = ordered[index]
        val target = ordered[targetIndex]
        vault.saveTotp(current.copy(sortOrder = target.sortOrder, updatedAt = System.currentTimeMillis()))
        vault.saveTotp(target.copy(sortOrder = current.sortOrder, updatedAt = System.currentTimeMillis()))
        refreshTotp()
    }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1_000) } }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(t("desktop_totp_codes"), color = AndroidPrimaryTitle, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Box {
                IconButton(onClick = { menuExpanded = true }) { Icon(Icons.Filled.MoreVert, contentDescription = null, tint = AndroidPrimaryTitle) }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(text = { Text(t("desktop_add_manually")) }, leadingIcon = { Icon(Icons.Filled.Timer, contentDescription = null) }, onClick = { menuExpanded = false; if (viewOnly) onRequestEdit() else { editingToken = null; showEditor = true } })
                    DropdownMenuItem(text = { Text(t("desktop_import_totp")) }, leadingIcon = { Icon(Icons.Filled.QrCodeScanner, contentDescription = null) }, onClick = { menuExpanded = false; if (viewOnly) onRequestEdit() else showImport = true })
                    DropdownMenuItem(text = { Text(t("otp_export_title")) }, leadingIcon = { Icon(Icons.Filled.FileDownload, contentDescription = null) }, enabled = entries.isNotEmpty(), onClick = {
                        menuExpanded = false
                        if (viewOnly) onRequestEdit() else chooseFile(FileDialog.SAVE, t("otp_export_title"), "keyscan_totp.json")?.let { destination ->
                            runCatching { exportTotpJson(entries, destination) }
                                .onSuccess { message = t("desktop_exported_to", destination.fileName); messageError = false }
                                .onFailure { message = t("export_failed_short"); messageError = true }
                        }
                    })
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query, { query = it }, label = { Text(t("desktop_search_totp")) }, singleLine = true, leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) }, modifier = Modifier.weight(1f))
            FilterChip(sortMode == "name", { sortMode = "name" }, { Text(t("vault_field_name")) })
            FilterChip(sortMode == "time", { sortMode = "time" }, { Text(t("history_column_time")) })
        }
        Text(tm("count", entries.size), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (message.isNotBlank()) Text(message, color = if (messageError) MaterialTheme.colorScheme.error else KeyGreen, fontSize = 13.sp)
        val visibleEntries = entries
            .filter { token -> query.isBlank() || token.issuer.contains(query, true) || token.accountName.contains(query, true) }
            .let { values ->
                if (sortMode == "time") values.sortedWith(compareByDescending<TotpEntry> { it.pinned }.thenByDescending { it.updatedAt })
                else values.sortedWith(compareByDescending<TotpEntry> { it.pinned }.thenBy { it.issuer.lowercase() }.thenBy { it.accountName.lowercase() })
            }
        if (entries.isEmpty()) Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) { Text(tm("empty"), Modifier.padding(30.dp), fontSize = 20.sp, fontWeight = FontWeight.SemiBold) }
        else if (visibleEntries.isEmpty()) Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) { Text(t("desktop_no_matching_totp"), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        visibleEntries.forEach { token ->
            val code = remember(token, now / 1000) { runCatching { TotpGenerator.code(token, now) }.getOrElse { "------" } }
            var itemMenuExpanded by remember(token.itemId) { mutableStateOf(false) }
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(token.issuer.ifBlank { token.accountName }, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(token.accountName, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, maxLines = 1)
                        }
                        Box {
                            IconButton(onClick = { if (viewOnly) onRequestEdit() else itemMenuExpanded = true }, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.MoreVert, contentDescription = null, tint = AndroidPrimaryTitle, modifier = Modifier.size(18.dp)) }
                            DropdownMenu(expanded = itemMenuExpanded, onDismissRequest = { itemMenuExpanded = false }) {
                                DropdownMenuItem(text = { Text(t("action_edit")) }, leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) }, onClick = { itemMenuExpanded = false; editingToken = token; showEditor = true })
                                DropdownMenuItem(text = { Text(if (token.pinned) t("desktop_unpin_item") else t("desktop_pin_item")) }, leadingIcon = { Icon(Icons.Filled.PushPin, contentDescription = null) }, onClick = { itemMenuExpanded = false; vault.saveTotp(token.copy(pinned = !token.pinned, updatedAt = System.currentTimeMillis())); refreshTotp() })
                                DropdownMenuItem(text = { Text(t("desktop_move_up")) }, leadingIcon = { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null) }, onClick = { itemMenuExpanded = false; moveTotp(token, -1) })
                                DropdownMenuItem(text = { Text(t("desktop_move_down")) }, leadingIcon = { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null) }, onClick = { itemMenuExpanded = false; moveTotp(token, 1) })
                                HorizontalDivider()
                                DropdownMenuItem(text = { Text(tm("delete")) }, leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) }, onClick = { itemMenuExpanded = false; vault.deleteTotp(token.itemId); refreshTotp() })
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(code, fontSize = 24.sp, color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(12.dp))
                        Text(tm("seconds", TotpGenerator.remainingSeconds(token, now)), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    }
                }
            }
        }
    }
    if (showEditor && !viewOnly) TotpEditor(existing = editingToken, onDismiss = { showEditor = false; editingToken = null }) { token -> vault.saveTotp(token); refreshTotp(); showEditor = false; editingToken = null }
    if (showImport && !viewOnly) TotpImportDialog(onDismiss = { showImport = false }) { token -> vault.saveTotp(token); entries = vault.listTotp(); showImport = false }
}

@Composable
private fun TotpImportDialog(onDismiss: () -> Unit, onSave: (TotpEntry) -> Unit) {
    val language = LocalAppLanguage.current
    fun t(key: String, vararg args: Any) = AndroidStringCatalog.text(language, key, *args)

    val importTitle = tm("import")
    var value by remember { mutableStateOf("") }; var error by remember { mutableStateOf(false) }; var imported by remember { mutableStateOf<List<TotpEntry>>(emptyList()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(t("desktop_import_totp")) }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("otpauth://totp/Issuer:account?secret=BASE32&issuer=Issuer")
        OutlinedButton(onClick = {
            val uri = runCatching { decodeQrFromClipboard() }.getOrNull()
            val token = uri?.let(::parseTotpUri)
            if (token != null) {
                imported = listOf(token)
                value = uri
                error = false
            } else {
                imported = emptyList()
                error = true
            }
        }, modifier = Modifier.fillMaxWidth()) { Text(t("desktop_scan_clipboard_image")) }
        OutlinedButton(onClick = {
            chooseFile(FileDialog.LOAD, t("desktop_choose_totp_qr_image"))?.let { path ->
                val uri = runCatching { decodeQrImage(path) }.getOrNull()
                val token = uri?.let(::parseTotpUri)
                if (token != null) {
                    imported = listOf(token)
                    value = uri
                    error = false
                } else {
                    imported = emptyList()
                    error = true
                }
            }
        }, modifier = Modifier.fillMaxWidth()) { Text(t("import_qr_image")) }
        OutlinedButton(onClick = {
            chooseFile(FileDialog.LOAD, importTitle)?.let { path ->
                imported = runCatching { parseTotpImportFile(path) }.getOrDefault(emptyList())
                error = imported.isEmpty()
            }
        }, modifier = Modifier.fillMaxWidth()) { Text(t("desktop_import_file_txt_json")) }
        if (imported.isNotEmpty()) Text(t("desktop_read_n_authenticators", imported.size), color = KeyGreen)
        OutlinedTextField(value, { value = it; error = false }, label = { Text(tm("uri_label")) }, minLines = 3)
        if (error) Text(tm("invalid_uri"), color = MaterialTheme.colorScheme.error)
    } }, confirmButton = { Button(onClick = {
        if (imported.isNotEmpty()) imported.forEach(onSave) else parseTotpUri(value)?.let(onSave) ?: run { error = true }
    }) { Text(tm("save")) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(m("cancel")) } })
}

private fun decodeQrImage(path: Path): String? {
    val image = ImageIO.read(path.toFile()) ?: return null
    return decodeQrImage(image)
}

private fun decodeQrFromClipboard(): String? {
    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
    if (!clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)) return null
    val image = clipboard.getData(DataFlavor.imageFlavor) as? Image ?: return null
    return decodeQrImage(image.toBufferedImage())
}

private fun decodeQrImage(image: BufferedImage): String? = runCatching {
    MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(BufferedImageLuminanceSource(image)))).text
}.getOrNull()

private fun Image.toBufferedImage(): BufferedImage {
    if (this is BufferedImage) return this
    val buffered = BufferedImage(getWidth(null).coerceAtLeast(1), getHeight(null).coerceAtLeast(1), BufferedImage.TYPE_INT_ARGB)
    val graphics = buffered.createGraphics()
    graphics.drawImage(this, 0, 0, null)
    graphics.dispose()
    return buffered
}

private fun exportTotpJson(entries: List<TotpEntry>, destination: Path) {
    val content = buildString {
        append("{\n  \"otpTokens\": [\n")
        entries.forEachIndexed { index, token ->
            if (index > 0) append(",\n")
            append("    {\n")
            append("      \"accountName\": \"").append(jsonEscape(token.accountName)).append("\",\n")
            append("      \"issuer\": \"").append(jsonEscape(token.issuer)).append("\",\n")
            append("      \"secret\": \"").append(jsonEscape(token.secret)).append("\",\n")
            append("      \"digits\": ").append(token.digits).append(",\n")
            append("      \"period\": ").append(token.period).append(",\n")
            append("      \"algorithm\": \"").append(jsonEscape(token.algorithm)).append("\"\n")
            append("    }")
        }
        append("\n  ]\n}\n")
    }
    Files.writeString(destination, content)
}

private fun jsonEscape(value: String): String = buildString {
    value.forEach { char ->
        when (char) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(char)
        }
    }
}

/** Reads Android KeyScan OTP JSON exports, a bare Android OTP array, or text files containing one otpauth URI per line. */
private fun parseTotpImportFile(path: Path): List<TotpEntry> {
    val content = Files.readString(path)
    if (path.fileName.toString().endsWith(".txt", ignoreCase = true)) {
        return content.lineSequence().mapNotNull(::parseTotpUri).distinctBy { it.issuer to it.accountName }.toList()
    }
    val root = Json.parseToJsonElement(content)
    val tokens = when (root) {
        is JsonArray -> root
        is JsonObject -> root["otpTokens"] as? JsonArray ?: throw IllegalArgumentException("Not a KeyScan OTP JSON export")
        else -> throw IllegalArgumentException("Not a KeyScan OTP JSON export")
    }
    return tokens.mapNotNull { element ->
        val token = element as? JsonObject ?: return@mapNotNull null
        val secret = TotpGenerator.normalizeSecret(token["secret"]?.jsonPrimitive?.content.orEmpty())
        val account = token["accountName"]?.jsonPrimitive?.content.orEmpty().ifBlank { token["account"]?.jsonPrimitive?.content.orEmpty() }
        if (secret.isBlank() || account.isBlank()) return@mapNotNull null
        val issuer = token["issuer"]?.jsonPrimitive?.content.orEmpty()
        val digits = token["digits"]?.jsonPrimitive?.content?.toIntOrNull()?.takeIf { it in setOf(6, 8) } ?: 6
        val period = token["period"]?.jsonPrimitive?.content?.toIntOrNull()?.takeIf { it in 15..120 } ?: 30
        val algorithm = token["algorithm"]?.jsonPrimitive?.content?.uppercase()?.takeIf { it in setOf("SHA1", "SHA256", "SHA512") } ?: "SHA1"
        TotpEntry(UUID.randomUUID().toString(), account, issuer, secret, digits, period, algorithm)
    }.distinctBy { it.issuer to it.accountName }
}

private fun parseTotpUri(value: String): TotpEntry? = runCatching {
    val uri = java.net.URI(value.trim()); require(uri.scheme.equals("otpauth", true) && uri.host.equals("totp", true))
    val parameters = uri.rawQuery.orEmpty().split('&').mapNotNull { part -> part.substringBefore('=').takeIf { it.isNotBlank() }?.let { java.net.URLDecoder.decode(it, "UTF-8") to java.net.URLDecoder.decode(part.substringAfter('=', ""), "UTF-8") } }.toMap()
    val secret = TotpGenerator.normalizeSecret(parameters["secret"].orEmpty()); require(secret.isNotBlank())
    val label = java.net.URLDecoder.decode(uri.rawPath.orEmpty().removePrefix("/"), "UTF-8"); val split = label.split(':', limit = 2)
    val account = split.getOrElse(1) { split.firstOrNull().orEmpty() }; val issuer = parameters["issuer"].orEmpty().ifBlank { split.firstOrNull().orEmpty() }
    require(account.isNotBlank()); TotpEntry(UUID.randomUUID().toString(), account, issuer, secret, parameters["digits"]?.toIntOrNull()?.takeIf { it in setOf(6, 8) } ?: 6, parameters["period"]?.toIntOrNull()?.takeIf { it in 15..120 } ?: 30, parameters["algorithm"]?.uppercase()?.takeIf { it in setOf("SHA1", "SHA256", "SHA512") } ?: "SHA1")
}.getOrNull()

@Composable
private fun TotpEditor(existing: TotpEntry? = null, onDismiss: () -> Unit, onSave: (TotpEntry) -> Unit) {
    var issuer by remember(existing?.itemId) { mutableStateOf(existing?.issuer.orEmpty()) }
    var account by remember(existing?.itemId) { mutableStateOf(existing?.accountName.orEmpty()) }
    var secret by remember(existing?.itemId) { mutableStateOf(existing?.secret.orEmpty()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (existing == null) tm("title") else tm("otp_auth_edit")) }, text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(issuer, { issuer = it }, label = { Text(tm("service")) }, singleLine = true)
        OutlinedTextField(account, { account = it }, label = { Text(tm("account")) }, singleLine = true)
        SecretField(tm("base32"), secret) { secret = it }
    } }, confirmButton = { Button(onClick = {
        val now = System.currentTimeMillis()
        onSave((existing ?: TotpEntry(UUID.randomUUID().toString(), account, issuer, TotpGenerator.normalizeSecret(secret))).copy(accountName = account, issuer = issuer, secret = TotpGenerator.normalizeSecret(secret), updatedAt = now))
    }, enabled = account.isNotBlank() && secret.isNotBlank()) { Text(tm("save")) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(m("cancel")) } })
}

@Composable
private fun SecureVaultLedger(vault: EncryptedVaultStore, attachmentStore: EncryptedAttachmentStore, viewOnly: Boolean, reauthenticate: (String, String) -> Boolean, quickPinAvailable: Boolean, verifyQuickPin: (String) -> Boolean, onHelloVerify: (suspend () -> Boolean)?, onRequestEdit: () -> Unit) {
    val language = LocalAppLanguage.current; fun t(key: String, vararg args: Any) = AndroidStringCatalog.text(language, key, *args)
    var entries by remember { mutableStateOf(vault.listSecureItems()) }; var editing by remember { mutableStateOf<SecureItem?>(null) }; var showEditor by remember { mutableStateOf(false) }; var query by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf<SecureItem?>(null) }; var pendingExport by remember { mutableStateOf<com.keyscan.core.model.VaultAttachment?>(null) }; var message by remember { mutableStateOf("") }; var messageError by remember { mutableStateOf(false) }
    val chooseAttachmentTitle = vm("choose_attachment"); val exportAttachmentTitle = vm("export_attachment")
    val visible = entries.filter { query.isBlank() || it.title.contains(query, true) || it.category.contains(query, true) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(vm("count", entries.size), color = MaterialTheme.colorScheme.onSurfaceVariant)
            ModuleActionButton(Icons.Filled.Inventory2, vm("add"), primary = true) { if (viewOnly) onRequestEdit() else { editing = null; showEditor = true } }
        }
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text(vm("search")) }, singleLine = true)
        if (message.isNotBlank()) Text(message, color = if (messageError) MaterialTheme.colorScheme.error else KeyGreen)
        if (visible.isEmpty()) Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) { Text(vm("empty"), Modifier.padding(30.dp), fontSize = 20.sp, fontWeight = FontWeight.SemiBold) }
        visible.forEach { item -> Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column { Text(item.title, fontWeight = FontWeight.SemiBold); Text("${item.category} · ${item.type}", color = MaterialTheme.colorScheme.secondary); if (item.notes.isNotBlank()) Text(item.notes, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Row { TextButton(onClick = { detail = item }) { Text(vm("details")) }; if (!viewOnly) { TextButton(onClick = { editing = item; showEditor = true }) { Text(vm("edit")) }; TextButton(onClick = { vault.deleteSecureItem(item.id); entries = vault.listSecureItems() }) { Text(vm("delete")) } } }
            }
        } }
    }
    if (showEditor && !viewOnly) SecureItemEditor(editing, onDismiss = { showEditor = false }) { item -> vault.saveSecureItem(item); entries = vault.listSecureItems(); showEditor = false }
    detail?.let { item -> SecureItemDetailDialog(item, vault.listAttachments(item.id), viewOnly, onDismiss = { detail = null }, onImport = {
        chooseFile(FileDialog.LOAD, chooseAttachmentTitle)?.let { source -> runCatching {
            val attachment = attachmentStore.importFile(item.id, source, Files.probeContentType(source) ?: "application/octet-stream")
            try { vault.saveAttachment(attachment) } catch (error: Exception) { attachmentStore.deleteEncryptedFile(attachment); throw error }
        }.onSuccess { message = t("vault_attachment_saved"); messageError = false; detail = null }.onFailure { message = t("vault_attachment_save_failed"); messageError = true } }
    }, onExport = { pendingExport = it }, onDelete = { attachment -> vault.deleteAttachment(attachment.id); attachmentStore.deleteEncryptedFile(attachment); message = t("history_delete_selected_success"); messageError = false; detail = null }) }
    pendingExport?.let { attachment -> ReauthenticationDialog(requireDataKey = !quickPinAvailable, onDismiss = { pendingExport = null }, onHelloVerify = onHelloVerify, onHelloVerified = {
        chooseFile(FileDialog.SAVE, exportAttachmentTitle, attachment.filename)?.let { destination -> runCatching { attachmentStore.exportFile(attachment, destination) }.onSuccess { message = "${t("vault_attachment_downloaded")}: ${destination.fileName}"; messageError = false }.onFailure { message = t("vault_attachment_download_failed", t("vault_attachment_open_failed")); messageError = true } }; pendingExport = null
    }) { pin, key ->
        val verified = if (quickPinAvailable) verifyQuickPin(pin) else reauthenticate(pin, key)
        if (!verified) false else { chooseFile(FileDialog.SAVE, exportAttachmentTitle, attachment.filename)?.let { destination -> runCatching { attachmentStore.exportFile(attachment, destination) }.onSuccess { message = "${t("vault_attachment_downloaded")}: ${destination.fileName}"; messageError = false }.onFailure { message = t("vault_attachment_download_failed", t("vault_attachment_open_failed")); messageError = true } }; pendingExport = null; true }
    } }
}

@Composable
private fun SecureItemDetailDialog(item: SecureItem, attachments: List<com.keyscan.core.model.VaultAttachment>, viewOnly: Boolean, onDismiss: () -> Unit, onImport: () -> Unit, onExport: (com.keyscan.core.model.VaultAttachment) -> Unit, onDelete: (com.keyscan.core.model.VaultAttachment) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(item.title) }, text = { Column(Modifier.heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("${item.category} · ${item.type}", color = MaterialTheme.colorScheme.secondary); if (item.fieldsJson.isNotBlank() && item.fieldsJson != "{}") Text(item.fieldsJson); if (item.notes.isNotBlank()) Text(item.notes, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text(vm("attachments", attachments.size), fontWeight = FontWeight.SemiBold); if (!viewOnly) OutlinedButton(onClick = onImport) { Text(vm("add_attachment")) } }
        if (attachments.isEmpty()) Text(vm("no_attachment"), color = MaterialTheme.colorScheme.onSurfaceVariant)
        attachments.forEach { attachment -> Card(Modifier.fillMaxWidth()) { Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(attachment.filename, maxLines = 1); Text("${attachment.size / 1024} KB · ${attachment.mimeType}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }
            Row { TextButton(onClick = { onExport(attachment) }) { Text(vm("verify_export")) }; if (!viewOnly) TextButton(onClick = { onDelete(attachment) }) { Text(vm("delete")) } }
        } } }
    } }, confirmButton = { TextButton(onClick = onDismiss) { Text(vm("close")) } })
}

@Composable
private fun SecureItemEditor(existing: SecureItem?, onDismiss: () -> Unit, onSave: (SecureItem) -> Unit) {
    var title by remember(existing) { mutableStateOf(existing?.title.orEmpty()) }; var category by remember(existing) { mutableStateOf(existing?.category ?: "CUSTOM") }
    var fields by remember(existing) { mutableStateOf(existing?.fieldsJson ?: "{}") }; var notes by remember(existing) { mutableStateOf(existing?.notes.orEmpty()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (existing == null) vm("add") else vm("edit")) }, text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(title, { title = it }, label = { Text(vm("title")) }, singleLine = true)
        OutlinedTextField(category, { category = it }, label = { Text(vm("category")) }, singleLine = true)
        OutlinedTextField(fields, { fields = it }, label = { Text(vm("fields")) }, minLines = 3)
        OutlinedTextField(notes, { notes = it }, label = { Text(vm("notes")) }, minLines = 2)
    } }, confirmButton = { Button(onClick = { val now = System.currentTimeMillis(); onSave(SecureItem(existing?.id ?: UUID.randomUUID().toString(), existing?.type ?: "CUSTOM", category, title, fields, notes, existing?.createdTime ?: now, now)) }, enabled = title.isNotBlank()) { Text(vm("save")) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(m("cancel")) } })
}

@Composable
private fun ReauthenticationDialog(requireDataKey: Boolean = true, onDismiss: () -> Unit, onHelloVerify: (suspend () -> Boolean)? = null, onHelloVerified: () -> Unit = onDismiss, onVerify: (String, String) -> Boolean) {
    var pin by remember { mutableStateOf("") }; var key by remember { mutableStateOf("") }; var error by remember { mutableStateOf(false) }
    var helloWorking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = onDismiss, title = { Text(m("reauth_title")) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(m("reauth_intro"))
            SecretField("PIN", pin) { pin = it; error = false }
            if (requireDataKey) SecretField(m("key_new"), key) { key = it; error = false }
            if (error) Text(m("bad_credentials"), color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            onHelloVerify?.let { verifyHello ->
                OutlinedButton(onClick = {
                    helloWorking = true; error = false
                    scope.launch { if (verifyHello()) onHelloVerified() else error = true; helloWorking = false }
                }, enabled = !helloWorking) { Text(if (helloWorking) "${LocalUiText.current.windowsHello}…" else LocalUiText.current.windowsHello) }
            }
            Button(onClick = { error = !onVerify(pin, key) }, enabled = !helloWorking && pin.matches(Regex("\\d{4,6}")) && (!requireDataKey || key.isNotEmpty())) { Text(m("verify")) }
        }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text(m("cancel")) } })
}
