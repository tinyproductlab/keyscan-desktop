package com.keyscan.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.Base64
import com.keyscan.core.vault.VaultBootstrapPath
import java.nio.file.StandardCopyOption

enum class WindowsHelloAvailability { AVAILABLE, DEVICE_BUSY, DEVICE_NOT_PRESENT, DISABLED_BY_POLICY, NOT_CONFIGURED, UNKNOWN, HELPER_MISSING, ERROR }
enum class WindowsHelloVerification { VERIFIED, CANCELED, DEVICE_BUSY, RETRIES_EXHAUSTED, TIMEOUT, NOT_CONFIGURED, UNKNOWN, ERROR }

class WindowsHelloClient(private val executable: Path = defaultExecutable()) {
    fun checkAvailability(): WindowsHelloAvailability {
        if (!Files.isRegularFile(executable)) return WindowsHelloAvailability.HELPER_MISSING
        return run("check", timeoutSeconds = 15)?.let(::parseAvailability) ?: WindowsHelloAvailability.ERROR
    }

    fun verify(windowHandle: Long, message: String): WindowsHelloVerification {
        require(windowHandle != 0L) { "Windows Hello requires the active KeyScan HWND" }
        require(message.isNotBlank() && message.length <= 256) { "Invalid Windows Hello message" }
        if (!Files.isRegularFile(executable)) return WindowsHelloVerification.ERROR
        return run("verify", windowHandle.toString(), message, timeoutSeconds = 90)?.let(::parseVerification)
            ?: WindowsHelloVerification.ERROR
    }

    fun keyExists(keyName: String): Boolean = run("keycheck", keyName, timeoutSeconds = 15) == "KEY:Available"
    fun enrollKey(windowHandle: Long, keyName: String): Boolean =
        run("enroll", windowHandle.toString(), keyName, timeoutSeconds = 90) == "ENROLL:Created"
    fun deleteKey(keyName: String): Boolean = run("delete-key", keyName, timeoutSeconds = 15) == "KEY:Deleted"

    fun sign(windowHandle: Long, keyName: String, challenge: ByteArray): ByteArray? {
        require(windowHandle != 0L && challenge.size == 32)
        val encoded = Base64.getEncoder().encodeToString(challenge)
        return try {
            val value = run("sign", windowHandle.toString(), keyName, encoded, timeoutSeconds = 90)
                ?.takeIf { it.startsWith("SIGNATURE:") }?.substringAfter("SIGNATURE:") ?: return null
            runCatching { Base64.getDecoder().decode(value) }.getOrNull()?.takeIf { it.size in 128..1024 }
        } finally { /* encoded is immutable; challenge ownership remains with the caller */ }
    }

    private fun run(vararg arguments: String, timeoutSeconds: Long): String? {
        val process = ProcessBuilder(listOf(executable.toAbsolutePath().toString()) + arguments).start()
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) { process.destroyForcibly(); return null }
        val stdout = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readLines() }
        // Never accept additional stdout because the helper protocol is intentionally one record.
        return stdout.singleOrNull()
    }

    companion object {
        internal fun parseAvailability(line: String): WindowsHelloAvailability = when (line.substringAfter("AVAILABILITY:", "")) {
            "Available" -> WindowsHelloAvailability.AVAILABLE
            "DeviceBusy" -> WindowsHelloAvailability.DEVICE_BUSY
            "DeviceNotPresent" -> WindowsHelloAvailability.DEVICE_NOT_PRESENT
            "DisabledByPolicy" -> WindowsHelloAvailability.DISABLED_BY_POLICY
            "NotConfiguredForUser" -> WindowsHelloAvailability.NOT_CONFIGURED
            "" -> WindowsHelloAvailability.ERROR
            else -> WindowsHelloAvailability.UNKNOWN
        }

        internal fun parseVerification(line: String): WindowsHelloVerification = when (line.substringAfter("VERIFICATION:", "")) {
            "Verified" -> WindowsHelloVerification.VERIFIED
            "Canceled" -> WindowsHelloVerification.CANCELED
            "DeviceBusy" -> WindowsHelloVerification.DEVICE_BUSY
            "RetriesExhausted" -> WindowsHelloVerification.RETRIES_EXHAUSTED
            "Timeout" -> WindowsHelloVerification.TIMEOUT
            "NotConfiguredForUser" -> WindowsHelloVerification.NOT_CONFIGURED
            "" -> WindowsHelloVerification.ERROR
            else -> WindowsHelloVerification.UNKNOWN
        }

        private fun defaultExecutable(): Path {
            System.getProperty("keyscan.windowsHelloHelper")?.takeIf(String::isNotBlank)?.let { return Path.of(it) }
            val local = Path.of("windows-hello-helper", "build", "KeyScanWindowsHello.exe")
            if (Files.isRegularFile(local)) return local
            if (!System.getProperty("os.name", "").startsWith("Windows", true)) return Path.of("KeyScanWindowsHello.exe")
            val target = VaultBootstrapPath.baseDirectory().resolve("helpers").resolve("KeyScanWindowsHello-v1.exe")
            val resource = WindowsHelloClient::class.java.getResourceAsStream("/native/KeyScanWindowsHello.exe") ?: return target
            resource.use { input ->
                Files.createDirectories(target.parent); val temporary = Files.createTempFile(target.parent, "hello-helper-", ".tmp")
                try {
                    Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING)
                    try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                    catch (_: Exception) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING) }
                } finally { Files.deleteIfExists(temporary) }
            }
            return target
        }
    }
}
