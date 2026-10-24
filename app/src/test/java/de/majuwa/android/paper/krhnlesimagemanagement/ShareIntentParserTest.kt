package de.majuwa.android.paper.krhnlesimagemanagement

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.BadParcelableException
import android.os.Bundle
import android.os.Parcelable
import androidx.test.core.app.ApplicationProvider
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeShareProvider
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.ProviderGate
import de.majuwa.android.paper.krhnlesimagemanagement.util.MAX_SHARED_PHOTOS
import de.majuwa.android.paper.krhnlesimagemanagement.util.SharedImageType
import de.majuwa.android.paper.krhnlesimagemanagement.util.isShareIntent
import de.majuwa.android.paper.krhnlesimagemanagement.util.isValidRemoteFileName
import de.majuwa.android.paper.krhnlesimagemanagement.util.parseSharedPhotos
import de.majuwa.android.paper.krhnlesimagemanagement.util.resolveSharedPhotos
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class ShareIntentParserTest {
    private val contentResolver =
        ApplicationProvider.getApplicationContext<Application>().contentResolver

    @Before
    fun setUp() {
        Robolectric.setupContentProvider(FakeShareProvider::class.java, FakeShareProvider.AUTHORITY)
    }

    @After
    fun tearDown() {
        FakeShareProvider.items.clear()
    }

    private fun send(uri: Uri?) =
        Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            if (uri != null) putExtra(Intent.EXTRA_STREAM, uri)
        }

    private fun sendMultiple(vararg uris: String) =
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris.map { Uri.parse(it) }))
        }

    private fun parse(intent: Intent) = parseSharedPhotos(intent, OWN_PACKAGE)

    // ── extraction ───────────────────────────────────────────────────────────

    @Test
    fun nonShareIntents_areEmptyAndNotShareIntents() {
        listOf(Intent(Intent.ACTION_VIEW), Intent()).forEach { intent ->
            assertTrue(parse(intent).uris.isEmpty())
            assertFalse(isShareIntent(intent))
        }
        assertTrue(isShareIntent(Intent(Intent.ACTION_SEND)))
        assertTrue(isShareIntent(Intent(Intent.ACTION_SEND_MULTIPLE)))
    }

    @Test
    fun actionSend_readsTheSingleStreamUri() {
        val uri = Uri.parse("content://media/external/images/media/42")

        assertEquals(listOf(uri), parse(send(uri)).uris)
        assertTrue(parse(send(null)).uris.isEmpty())
    }

    @Test
    fun actionSendMultiple_readsTheListInOrder() {
        val result = parse(sendMultiple("content://media/x/1", "content://media/x/2"))

        assertEquals(listOf("content://media/x/1", "content://media/x/2"), result.uris.map { it.toString() })
        assertTrue(parse(Intent(Intent.ACTION_SEND_MULTIPLE)).uris.isEmpty())
    }

    @Test
    fun fileUri_isRejectedAndCounted() {
        val result = parse(send(Uri.parse("file:///data/data/$OWN_PACKAGE/files/upload_queue_1_a.json")))

        assertTrue(result.uris.isEmpty())
        assertEquals(1, result.rejected)
    }

    @Test
    fun ownAuthoritiesAndOtherSchemes_areRejected_foreignContentUrisKept() {
        val result =
            parse(
                sendMultiple(
                    "content://$OWN_PACKAGE/x/1",
                    "content://$OWN_PACKAGE.fileprovider/x/2",
                    "content://10@$OWN_PACKAGE.fileprovider/x/3",
                    "http://example.com/a.jpg",
                    "content://media/external/images/media/4",
                    "content://${OWN_PACKAGE}evil.provider/x/5",
                ),
            )

        assertEquals(
            listOf("content://media/external/images/media/4", "content://${OWN_PACKAGE}evil.provider/x/5"),
            result.uris.map { it.toString() },
        )
        assertEquals(4, result.rejected)
    }

    @Test
    fun duplicates_areDropped() {
        val result = parse(sendMultiple("content://media/x/1", "content://media/x/2", "content://media/x/1"))

        assertEquals(listOf("content://media/x/1", "content://media/x/2"), result.uris.map { it.toString() })
        assertEquals(0, result.truncated)
    }

    @Test
    fun countCap_keepsTheFirst100AndCountsTheRest() {
        val result = parse(sendMultiple(*Array(150) { "content://media/x/$it" }))

        assertEquals(MAX_SHARED_PHOTOS, result.uris.size)
        assertEquals("content://media/x/0", result.uris.first().toString())
        assertEquals("content://media/x/99", result.uris.last().toString())
        assertEquals(50, result.truncated)
    }

    @Test
    fun wrongExtraTypes_areIgnoredWithoutCrashing() {
        val stringExtra = Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_STREAM, "content://media/x/1") }
        val listInSend =
            Intent(Intent.ACTION_SEND).apply {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(Uri.parse("content://media/x/1")))
            }
        val stringList =
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                putStringArrayListExtra(Intent.EXTRA_STREAM, arrayListOf("content://media/x/1"))
            }
        val uri = Uri.parse("content://media/x/2")
        val mixedList =
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf<Parcelable>(uri, Bundle()))
            }

        assertTrue(parse(stringExtra).uris.isEmpty())
        assertTrue(parse(listInSend).uris.isEmpty())
        assertTrue(parse(stringList).uris.isEmpty())
        assertTrue(parse(mixedList).uris.all { it == uri })
    }

    @Test
    fun badParcelableExtra_isIgnored() {
        val intent = mockk<Intent>()
        every { intent.action } returns Intent.ACTION_SEND
        every { intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) } throws BadParcelableException("x")

        assertTrue(parse(intent).uris.isEmpty())
    }

    @Test
    fun anyRuntimeExceptionWhileReadingTheExtra_isIgnored() {
        val single = mockk<Intent>()
        every { single.action } returns Intent.ACTION_SEND
        every { single.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) } throws RuntimeException("Parcel")
        val multiple = mockk<Intent>()
        every { multiple.action } returns Intent.ACTION_SEND_MULTIPLE
        every {
            multiple.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } throws IllegalArgumentException("bad")

        assertTrue(parse(single).uris.isEmpty())
        assertTrue(parse(multiple).uris.isEmpty())
    }

    // ── provider metadata ────────────────────────────────────────────────────

    private fun item(
        id: String,
        item: FakeShareProvider.Item,
    ): Uri {
        FakeShareProvider.items[id] = item
        return FakeShareProvider.uri(id)
    }

    private fun resolve(
        vararg uris: Uri,
        intentType: String? = "image/*",
        itemTimeoutMillis: Long = 10_000,
    ) = runBlocking {
        resolveSharedPhotos(
            uris.toList(),
            intentType,
            contentResolver,
            TODAY,
            NOW,
            dispatcher = Dispatchers.IO,
            itemTimeoutMillis = itemTimeoutMillis,
        )
    }

    @Test
    fun imageItem_isResolvedWithProviderMetadata_noMediaStoreId_andToday() {
        val uri = item("1", FakeShareProvider.Item(type = "image/png", name = "a.png", size = 3))

        val photo = resolve(uri).photos.single()

        assertEquals(-1L, photo.id)
        assertEquals(uri, photo.uri)
        assertEquals("a.png", photo.displayName)
        assertEquals("image/png", photo.mimeType)
        assertEquals(3L, photo.size)
        assertEquals(TODAY, photo.dateTaken)
    }

    @Test
    fun nonImageItem_isDropped() {
        val pdf = item("1", FakeShareProvider.Item(type = "application/pdf", name = "a.pdf"))

        val result = resolve(pdf, intentType = "image/jpeg")

        assertTrue(result.photos.isEmpty())
        assertEquals(1, result.rejected)
    }

    @Test
    fun unknownProviderType_withAnImageName_isAcceptedForAWildcardIntentType() {
        // Regression: provider type null + intent type image/* used to drop every item.
        val unknown = item("1", FakeShareProvider.Item(type = null, name = "a.jpg"))

        val photo = resolve(unknown, intentType = "image/*").photos.single()

        assertEquals("image/jpeg", photo.mimeType)
        assertEquals("a.jpg", photo.displayName)
        assertEquals("image/jpeg", resolve(unknown, intentType = null).photos.single().mimeType)
        // The name's extension wins over the intent type.
        assertEquals("image/jpeg", resolve(unknown, intentType = "image/png").photos.single().mimeType)
    }

    @Test
    fun octetStreamProviderType_isDecidedByTheNameExtension() {
        val png = item("1", FakeShareProvider.Item(type = "application/octet-stream", name = "x.PNG"))
        val dng = item("2", FakeShareProvider.Item(type = "application/octet-stream", name = "raw.dng"))

        assertEquals(
            listOf("image/png", "image/x-adobe-dng"),
            resolve(png, dng, intentType = "image/jpeg").photos.map { it.mimeType },
        )
    }

    @Test
    fun unknownProviderTypeWithoutAKnownExtension_usesAConcreteIntentType_neverAWildcard() {
        val noName = item("1", FakeShareProvider.Item(type = null, name = null))
        val octet = item("2", FakeShareProvider.Item(type = "application/octet-stream", name = "photo"))

        assertEquals(
            listOf("image/jpeg", "image/jpeg"),
            resolve(noName, octet, intentType = "image/jpeg").photos.map { it.mimeType },
        )
        assertTrue(resolve(noName, octet, intentType = "image/*").photos.isEmpty())
        assertTrue(resolve(noName, octet, intentType = null).photos.isEmpty())
        assertTrue(resolve(noName, octet, intentType = "text/plain").photos.isEmpty())
        assertTrue(resolve(noName, intentType = "image/svg+xml").photos.isEmpty())
    }

    @Test
    fun unknownProviderTypeWithANonImageExtension_isDropped_evenForAnImageIntentType() {
        val pdf = item("1", FakeShareProvider.Item(type = null, name = "a.pdf"))

        assertTrue(resolve(pdf, intentType = "image/jpeg").photos.isEmpty())
    }

    @Test
    fun concreteProviderType_decidesAlone() {
        val pdfNamedJpg = item("1", FakeShareProvider.Item(type = "application/pdf", name = "a.jpg"))
        val svg = item("2", FakeShareProvider.Item(type = "image/svg+xml", name = "a.svg"))
        val svgNamedPng = item("3", FakeShareProvider.Item(type = "image/svg+xml", name = "a.png"))
        val icon = item("4", FakeShareProvider.Item(type = "image/x-icon", name = "a.ico"))

        val result = resolve(pdfNamedJpg, svg, svgNamedPng, icon, intentType = "image/jpeg")

        assertTrue(result.photos.isEmpty())
        assertEquals(4, result.rejected)
    }

    @Test
    fun allowList_coversRasterTypes_andNormalizesAliases() {
        val accepted =
            mapOf(
                "image/jpeg" to "image/jpeg",
                "IMAGE/JPEG; charset=binary" to "image/jpeg",
                "image/jpg" to "image/jpeg",
                "image/pjpeg" to "image/jpeg",
                "image/png" to "image/png",
                "image/gif" to "image/gif",
                "image/webp" to "image/webp",
                "image/heic" to "image/heic",
                "image/heif" to "image/heif",
                "image/avif" to "image/avif",
                "image/bmp" to "image/bmp",
                "image/x-ms-bmp" to "image/bmp",
                "image/x-adobe-dng" to "image/x-adobe-dng",
                "image/dng" to "image/x-adobe-dng",
            )
        accepted.forEach { (type, canonical) -> assertEquals(type, canonical, SharedImageType.of(type)?.mimeType) }
        listOf("image/svg+xml", "image/*", "image/tiff", "image/x-icon", "text/html", "", null).forEach {
            assertNull(it, SharedImageType.of(it))
        }
    }

    @Test
    fun remoteName_endsWithTheValidatedTypesExtension() {
        val svgNamedPng = item("1", FakeShareProvider.Item(type = "image/png", name = "evil.svg"))
        val noExtension = item("2", FakeShareProvider.Item(type = "image/webp", name = "holiday"))
        val jpeg = item("3", FakeShareProvider.Item(type = "image/jpeg", name = "IMG_1.JPEG"))
        val heifAsHeic = item("4", FakeShareProvider.Item(type = "image/heic", name = "photo.heif"))

        val names = resolve(svgNamedPng, noExtension, jpeg, heifAsHeic).photos.map { it.displayName }

        assertEquals(listOf("evil.svg.png", "holiday.webp", "IMG_1.JPEG", "photo.heif"), names)
    }

    @Test
    fun namesWithInvisibleFormattingCharacters_areNotUsed() {
        listOf(
            "abc\u202Egnp.jpg", // right-to-left override disguising the extension
            "a\u2066b.jpg",
            "a\u200Eb.jpg",
            "a\u200Fb.jpg",
            "a\u200Bb.jpg", // zero-width space (Cf)
            "a\uFEFFb.jpg",
            "a\u2028b.jpg",
            "a\uD800b.jpg", // unpaired surrogate
        ).forEach { assertFalse(it, isValidRemoteFileName(it)) }
        assertTrue(isValidRemoteFileName("Ürlaub 2026 – 日本.jpg"))
        assertTrue(isValidRemoteFileName("\uD83D\uDE00.jpg")) // a paired surrogate (emoji)

        val bidi = item("1", FakeShareProvider.Item(type = "image/jpeg", name = "abc\u202Egnp.jpg"))
        assertTrue(
            resolve(bidi)
                .photos
                .single()
                .displayName
                .matches(Regex("shared_${NOW}_[0-9a-f]{8}\\.jpg")),
        )
    }

    @Test
    fun providerNotAnsweringTheQuery_dropsTheItemAfterTheTimeout_andCancelsTheSignal() {
        val gate = ProviderGate(honoursCancel = false)
        val hanging = item("1", FakeShareProvider.Item(name = "a.jpg", queryGate = gate))
        val fine = item("2", FakeShareProvider.Item(name = "b.jpg"))
        try {
            val started = System.currentTimeMillis()

            val result = resolve(hanging, fine, itemTimeoutMillis = 200)

            assertTrue(System.currentTimeMillis() - started < 5_000)
            assertEquals(listOf("b.jpg"), result.photos.map { it.displayName })
            assertEquals(1, result.rejected)
            assertTrue(gate.cancelled)
        } finally {
            gate.release()
        }
    }

    @Test
    fun providerFailures_doNotCrash() {
        val typeDenied = item("1", FakeShareProvider.Item(typeFailure = SecurityException("no")))
        val queryDenied = item("2", FakeShareProvider.Item(queryFailure = IllegalArgumentException("bad")))
        val queryState = item("3", FakeShareProvider.Item(queryFailure = IllegalStateException("closed")))

        val result = resolve(typeDenied, queryDenied, queryState, intentType = "image/jpeg")

        assertEquals(3, result.photos.size)
        assertTrue(result.photos.all { it.displayName.startsWith("shared_${NOW}_") && it.size == 0L })
    }

    @Test
    fun maliciousOrMissingName_usesAGeneratedSafeName() {
        val traversal = item("1", FakeShareProvider.Item(type = "image/jpeg", name = "../x.jpg"))
        val control = item("2", FakeShareProvider.Item(type = "image/png", name = "a\u0000b.png"))
        val missing = item("3", FakeShareProvider.Item(type = "image/webp", name = null))

        val names = resolve(traversal, control, missing).photos.map { it.displayName }

        assertTrue(names[0].matches(Regex("shared_${NOW}_[0-9a-f]{8}\\.jpg")))
        assertTrue(names[1].matches(Regex("shared_${NOW}_[0-9a-f]{8}\\.png")))
        assertTrue(names[2].matches(Regex("shared_${NOW}_[0-9a-f]{8}\\.webp")))
    }

    @Test
    fun duplicateNames_areMadeUniqueWithinTheShare() {
        val a = item("1", FakeShareProvider.Item(name = "IMG.jpg"))
        val b = item("2", FakeShareProvider.Item(name = "img.JPG"))
        val c = item("3", FakeShareProvider.Item(name = "IMG.jpg"))

        val names = resolve(a, b, c).photos.map { it.displayName }

        assertEquals("IMG.jpg", names[0])
        assertEquals(3, names.map { it.lowercase() }.toSet().size)
        assertNotEquals("img.JPG", names[1])
    }

    private companion object {
        const val OWN_PACKAGE = "de.majuwa.android.paper.krhnlesimagemanagement"
        const val NOW = 1_700_000_000_000L
        val TODAY: LocalDate = LocalDate.of(2026, 10, 24)
    }
}
