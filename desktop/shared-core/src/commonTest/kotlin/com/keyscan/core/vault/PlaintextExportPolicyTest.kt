package com.keyscan.core.vault

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlaintextExportPolicyTest {
    @Test fun rejectsManagedDataAndAllowsExternalDestination() {
        val root = Files.createTempDirectory("keyscan-managed-root").toRealPath()
        val outside = Files.createTempDirectory("keyscan-export-root").resolve("passwords.csv")
        assertFailsWith<IllegalArgumentException> { PlaintextExportPolicy.requireOutsideManagedData(root.resolve("vault.ksdb"), root) }
        assertFailsWith<IllegalArgumentException> { PlaintextExportPolicy.requireOutsideManagedData(root.resolve("documents/export.json"), root) }
        assertEquals(outside, PlaintextExportPolicy.requireOutsideManagedData(outside, root))
    }
}
