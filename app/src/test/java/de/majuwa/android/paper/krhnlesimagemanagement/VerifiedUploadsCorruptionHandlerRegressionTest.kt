package de.majuwa.android.paper.krhnlesimagemanagement

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The production DataStore delegate for the verified-upload records cannot be driven in tests, so this
 * pins that it installs the corruption handler: an unreadable file must mean "nothing removable".
 */
class VerifiedUploadsCorruptionHandlerRegressionTest {
    private val moduleDir: File = File(System.getProperty("krhnles.moduleDir") ?: ".").absoluteFile

    @Test
    fun `verified uploads delegate installs the corruption handler`() {
        val source =
            File(
                moduleDir,
                "src/main/java/de/majuwa/android/paper/krhnlesimagemanagement/data/VerifiedUploadsStore.kt",
            )
        assertTrue("Expected ${source.path}", source.isFile)

        val text = source.readText()
        val start = text.indexOf("by preferencesDataStore(")
        assertTrue("preferencesDataStore delegate not found", start >= 0)
        val block = text.substring(start, text.indexOf(")\n", start))

        assertTrue(
            "The delegate must set corruptionHandler = verifiedUploadsCorruptionHandler",
            "corruptionHandler = verifiedUploadsCorruptionHandler" in block,
        )
    }
}
