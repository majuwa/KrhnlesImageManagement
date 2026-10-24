package de.majuwa.android.paper.krhnlesimagemanagement

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The production DataStore delegate for the upload history cannot be driven in tests, so this pins
 * that it installs the corruption handler: an unreadable file must mean "no history", not a crash.
 */
class UploadHistoryCorruptionHandlerRegressionTest {
    private val moduleDir: File = File(System.getProperty("krhnles.moduleDir") ?: ".").absoluteFile

    @Test
    fun `upload history delegate installs the corruption handler`() {
        val source =
            File(
                moduleDir,
                "src/main/java/de/majuwa/android/paper/krhnlesimagemanagement/data/UploadHistoryStore.kt",
            )
        assertTrue("Expected ${source.path}", source.isFile)

        val text = source.readText()
        val start = text.indexOf("by preferencesDataStore(")
        assertTrue("preferencesDataStore delegate not found", start >= 0)
        val block = text.substring(start, text.indexOf(")\n", start))

        assertTrue(
            "The delegate must set corruptionHandler = uploadHistoryCorruptionHandler",
            "corruptionHandler = uploadHistoryCorruptionHandler" in block,
        )
    }
}
