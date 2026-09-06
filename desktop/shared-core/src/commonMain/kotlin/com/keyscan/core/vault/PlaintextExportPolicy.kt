package com.keyscan.core.vault

import java.nio.file.Files
import java.nio.file.Path

/** Prevents plaintext exports from overwriting encrypted application state. */
object PlaintextExportPolicy {
    fun requireOutsideManagedData(destination: Path, managedRoot: Path = VaultBootstrapPath.baseDirectory()): Path {
        val target = resolveExistingAncestors(destination.toAbsolutePath().normalize())
        val root = resolveExistingAncestors(managedRoot.toAbsolutePath().normalize())
        require(!target.startsWith(root)) { "Plaintext exports cannot be written inside the KeyScan data directory" }
        return destination
    }

    private fun resolveExistingAncestors(path: Path): Path {
        var existing: Path? = path
        val missing = ArrayDeque<String>()
        while (existing != null && !Files.exists(existing)) {
            existing.fileName?.toString()?.let(missing::addFirst)
            existing = existing.parent
        }
        var resolved = existing?.toRealPath() ?: path.root ?: path
        missing.forEach { resolved = resolved.resolve(it) }
        return resolved.normalize()
    }
}
