package de.majuwa.android.paper.krhnlesimagemanagement

import de.majuwa.android.paper.krhnlesimagemanagement.data.APP_PREFERENCES_NAME
import de.majuwa.android.paper.krhnlesimagemanagement.data.CREDENTIALS_DATASTORE_NAME
import de.majuwa.android.paper.krhnlesimagemanagement.data.PREFS_FILE_NAME
import de.majuwa.android.paper.krhnlesimagemanagement.data.UPLOADED_PHOTOS_DATASTORE_NAME
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Credentials and the onboarding flag must be excluded together, so a restore never splits them,
 * and the device-specific uploaded-photo markers are excluded from every backup path.
 */
class BackupRulesTest {
    private val xmlDir = File(System.getProperty("krhnles.moduleDir"), "src/main/res/xml")

    private val expected =
        listOf(
            "file" to "datastore/$CREDENTIALS_DATASTORE_NAME.preferences_pb",
            "sharedpref" to "$PREFS_FILE_NAME.xml",
            "file" to "datastore/$APP_PREFERENCES_NAME.preferences_pb",
            "file" to "datastore/$UPLOADED_PHOTOS_DATASTORE_NAME.preferences_pb",
        )

    private fun excludes(
        file: String,
        section: String?,
    ): List<Pair<String, String>> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(xmlDir, file))
        val root = doc.documentElement
        val scope = if (section == null) root else root.getElementsByTagName(section).item(0) as Element
        val nodes = scope.getElementsByTagName("exclude")
        return (0 until nodes.length).map {
            val node = nodes.item(it) as Element
            node.getAttribute("domain") to node.getAttribute("path")
        }
    }

    private fun assertExcludesAll(actual: List<Pair<String, String>>) {
        expected.forEach { assertTrue("missing exclude $it in $actual", it in actual) }
    }

    @Test
    fun `full backup rules exclude credentials, the onboarding flag and uploaded markers`() =
        assertExcludesAll(excludes("backup_rules.xml", null))

    @Test
    fun `cloud backup excludes credentials, the onboarding flag and uploaded markers`() =
        assertExcludesAll(excludes("data_extraction_rules.xml", "cloud-backup"))

    @Test
    fun `device transfer excludes credentials, the onboarding flag and uploaded markers`() =
        assertExcludesAll(excludes("data_extraction_rules.xml", "device-transfer"))
}
