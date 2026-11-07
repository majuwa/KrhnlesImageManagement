package de.majuwa.android.paper.krhnlesimagemanagement.tls

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.ServerCertificate
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.ServerOrigin
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.TrustedCertificateStore
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.pinFor
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.trustedCertificatesCorruptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.decodeHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TrustedCertificateStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val key = stringPreferencesKey("trusted_certificate")
    private val hex = "ab".repeat(32)

    private fun dataStore(
        scope: CoroutineScope,
        file: File = File(tmp.root, "trusted_certificates.preferences_pb"),
    ): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            corruptionHandler = trustedCertificatesCorruptionHandler,
            scope = scope,
            produceFile = { file },
        )

    private fun cert(
        host: String = "cloud.example",
        sha: String = hex,
    ) = ServerCertificate(ServerOrigin(host, 443), sha.decodeHex(), "CN=a", "CN=b", 1L, 2L)

    @Test
    fun `round trip replace and clear`() =
        runTest(UnconfinedTestDispatcher()) {
            val store = TrustedCertificateStore(dataStore(backgroundScope))
            assertNull(store.trustedCertificate.first())
            store.trust(cert())
            assertEquals(cert(), store.trustedCertificate.first()!!.certificate)
            store.trust(cert(sha = "cd".repeat(32)))
            assertEquals(
                "cd".repeat(32),
                store.trustedCertificate
                    .first()!!
                    .certificate.sha256
                    .hex(),
            )
            store.clear()
            assertNull(store.trustedCertificate.first())
        }

    @Test
    fun `invalid records mean no pin`() =
        runTest(UnconfinedTestDispatcher()) {
            val ds = dataStore(backgroundScope)
            val store = TrustedCertificateStore(ds)
            val good =
                """{"origin":"cloud.example:443","sha256":"$hex","subject":"s","issuer":"i",""" +
                    """"notBefore":1,"notAfter":2,"trustedAt":3}"""
            val bad =
                listOf(
                    "not json",
                    good.replace("\"sha256\":\"$hex\"", "\"sha256\":\"zz\""),
                    good.replace(hex, hex.uppercase()),
                    good.replace(hex, "ab"),
                    good.replace("\"notBefore\":1", "\"notBefore\":\"1\""),
                    good.replace("cloud.example:443", "cloud.example"),
                    good.replace("cloud.example:443", "Cloud.Example:443"),
                    good.replace("\"subject\":\"s\",", ""),
                )
            ds.edit { it[key] = good }
            assertNotNull(store.trustedCertificate.first())
            bad.forEach { record ->
                ds.edit { it[key] = record }
                assertNull(record, store.trustedCertificate.first())
            }
        }

    @Test
    fun `corrupt file means no pin`() =
        runTest(UnconfinedTestDispatcher()) {
            val file = File(tmp.root, "corrupt.preferences_pb").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5)) }
            val store = TrustedCertificateStore(dataStore(backgroundScope, file))
            assertNull(store.trustedCertificate.first())
        }

    @Test
    fun `an IO error while reading means no pin and other errors are not swallowed`() =
        runTest(UnconfinedTestDispatcher()) {
            fun failing(error: Throwable) =
                object : DataStore<Preferences> {
                    override val data = kotlinx.coroutines.flow.flow<Preferences> { throw error }

                    override suspend fun updateData(transform: suspend (Preferences) -> Preferences) =
                        throw UnsupportedOperationException()
                }
            assertNull(TrustedCertificateStore(failing(java.io.IOException("disk"))).trustedCertificate.first())
            val unexpected =
                runCatching {
                    TrustedCertificateStore(failing(IllegalStateException("bug"))).trustedCertificate.first()
                }.exceptionOrNull()
            assertTrue(unexpected is IllegalStateException)
        }

    @Test
    fun `pinFor ignores other origins and non https`() =
        runTest(UnconfinedTestDispatcher()) {
            val store = TrustedCertificateStore(dataStore(backgroundScope))
            store.trust(cert())
            val info = store.trustedCertificate.first()
            assertNotNull(info.pinFor("https://cloud.example/remote.php/dav/files/u/"))
            assertNotNull(info.pinFor("https://CLOUD.example:443/"))
            assertNull(info.pinFor("https://cloud.example:8443/"))
            assertNull(info.pinFor("https://other.example/"))
            assertNull(info.pinFor("http://cloud.example/"))
            assertNull(info.pinFor("not a url"))
        }

    @Test
    fun `names are sanitized and capped`() {
        val raw = "CN=a‮b\u0000c" + "x".repeat(400)
        val clean = ServerCertificate.sanitizeName(raw)
        assertEquals(256, clean.length)
        assertEquals("CN=abc", clean.take(6))
    }
}
