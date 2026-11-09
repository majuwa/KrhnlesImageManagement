package de.majuwa.android.paper.krhnlesimagemanagement

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import de.majuwa.android.paper.krhnlesimagemanagement.reminder.NewMediaCount
import de.majuwa.android.paper.krhnlesimagemanagement.reminder.REMINDER_CHANNEL_ID
import de.majuwa.android.paper.krhnlesimagemanagement.reminder.ReminderNotifier
import de.majuwa.android.paper.krhnlesimagemanagement.worker.UPLOAD_CHANNEL_ID
import de.majuwa.android.paper.krhnlesimagemanagement.worker.createDownloadNotificationChannel
import de.majuwa.android.paper.krhnlesimagemanagement.worker.createUploadNotificationChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Formats every German text with arguments of its declared types and checks the locale fallbacks. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "de")
class GermanStringsFormatTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val english by lazy { StringResourceScanner.parse(StringResourceScanner.resDir("values")) }

    private fun args(text: String): Array<Any> =
        StringResourceScanner
            .placeholders(text)
            .first
            .sortedBy { it.index }
            .map<Placeholder, Any> {
                when (it.conversion) {
                    'd' -> 2
                    'f' -> 1.5
                    else -> "x"
                }
            }.toTypedArray()

    private fun id(
        type: String,
        name: String,
    ): Int = context.resources.getIdentifier(name, type, context.packageName)

    @Test
    fun `every German string and plural quantity formats`() {
        val resources = context.resources
        for ((name, text) in english.strings) {
            if (name in english.nonTranslatable) continue
            val resId = id("string", name)
            assertTrue("Unknown string $name", resId != 0)
            resources.getString(resId, *args(text))
        }
        for ((name, items) in english.plurals) {
            val resId = id("plurals", name)
            assertTrue("Unknown plural $name", resId != 0)
            for (count in listOf(1, 2)) {
                val template = items[if (count == 1) "one" else "other"].orEmpty()
                resources.getQuantityString(resId, count, *args(template))
            }
        }
    }

    @Test
    fun `German differs from English for a sample of keys`() {
        val german = context.resources
        val default = localized("en")
        for (name in listOf("action_retry", "title_settings", "nav_albums", "action_cancel", "action_upload")) {
            val resId = id("string", name)
            assertNotEquals("$name is not translated", default.getString(resId), german.getString(resId))
        }
        assertEquals("Kröhnle's Image Management", german.getString(R.string.app_name))
    }

    @Test
    @Config(qualifiers = "de-rAT")
    fun `Austrian German uses the German texts`() {
        assertEquals(localized("de").getString(R.string.action_retry), context.getString(R.string.action_retry))
    }

    @Test
    @Config(qualifiers = "de-rCH")
    fun `Swiss German uses the German texts`() {
        assertEquals(localized("de").getString(R.string.action_retry), context.getString(R.string.action_retry))
    }

    @Test
    @Config(qualifiers = "fr")
    fun `unsupported language falls back to English`() {
        assertEquals("Retry", context.getString(R.string.action_retry))
    }

    @Test
    fun `notification channels are created with German names`() {
        context.createUploadNotificationChannel()
        context.createDownloadNotificationChannel()
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ReminderNotifier(context).post(NewMediaCount(3, true, false), sinceLastUpload = false)

        val manager = context.getSystemService(NotificationManager::class.java)
        assertEquals(
            context.getString(R.string.channel_name_uploads),
            manager.getNotificationChannel(UPLOAD_CHANNEL_ID).name,
        )
        assertEquals(
            context.getString(R.string.reminder_channel_name),
            manager.getNotificationChannel(REMINDER_CHANNEL_ID).name,
        )
        val downloads = manager.notificationChannels.map { it.name.toString() }
        assertTrue(downloads.contains(context.getString(R.string.channel_name_downloads)))
        assertNotEquals("Reminders", manager.getNotificationChannel(REMINDER_CHANNEL_ID).name.toString())
        assertNotEquals(
            "Shows upload progress for photo and video backups",
            manager.getNotificationChannel(UPLOAD_CHANNEL_ID).description,
        )
    }

    private fun localized(language: String): Resources {
        val config = android.content.res.Configuration(context.resources.configuration)
        config.setLocale(java.util.Locale.forLanguageTag(language))
        return context.createConfigurationContext(config).resources
    }
}
