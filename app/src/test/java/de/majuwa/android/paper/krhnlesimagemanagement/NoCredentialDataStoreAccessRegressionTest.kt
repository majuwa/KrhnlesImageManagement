package de.majuwa.android.paper.krhnlesimagemanagement

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `Context.credentialDataStore` is internal only so tests can plant invalid stored values. Production
 * code must go through `CredentialStore`; this fails if any other main source touches the DataStore directly.
 */
class NoCredentialDataStoreAccessRegressionTest {
    private val moduleDir: File = File(System.getProperty("krhnles.moduleDir") ?: ".").absoluteFile

    @Test
    fun `credentialDataStore is only used inside CredentialStore`() {
        val mainDir = File(moduleDir, "src/main")
        assertTrue("Expected app module sources at ${mainDir.path}", mainDir.isDirectory)

        val offenders =
            mainDir
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" && it.name != "CredentialStore.kt" }
                .filter { "credentialDataStore" in it.readText() }
                .map { it.relativeTo(moduleDir).invariantSeparatorsPath }
                .toList()

        assertTrue("Use CredentialStore instead of credentialDataStore. Offenders: $offenders", offenders.isEmpty())
    }
}
