package de.majuwa.android.paper.krhnlesimagemanagement

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import de.majuwa.android.paper.krhnlesimagemanagement.data.CredentialCipher
import de.majuwa.android.paper.krhnlesimagemanagement.data.CredentialSaveException
import de.majuwa.android.paper.krhnlesimagemanagement.data.CredentialStore
import de.majuwa.android.paper.krhnlesimagemanagement.data.credentialDataStore
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.SoftwareKeyProvider
import de.majuwa.android.paper.krhnlesimagemanagement.model.BlurSensitivity
import de.majuwa.android.paper.krhnlesimagemanagement.model.CredentialCorruption
import de.majuwa.android.paper.krhnlesimagemanagement.model.CredentialState
import de.majuwa.android.paper.krhnlesimagemanagement.ui.settings.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import java.security.KeyStoreException
import java.security.ProviderException
import java.security.UnrecoverableKeyException

/**
 * Regression tests for issue #31 / #50: undecryptable credentials must surface as
 * [CredentialState.Corrupted] (and be logged) instead of looking like "never configured".
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class CredentialStoreTest {
    private lateinit var context: Context
    private lateinit var keys: SoftwareKeyProvider
    private lateinit var store: CredentialStore

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        keys = SoftwareKeyProvider()
        store = CredentialStore(context, CredentialCipher(keys))
        runBlocking { store.clear() }
        ShadowLog.clear()
    }

    @Test
    fun `state is missing when nothing is stored`() =
        runTest {
            assertEquals(CredentialState.Missing, store.credentialState.first())
            assertFalse(store.isConfigured.first())
            assertTrue(warnings().isEmpty())
        }

    @Test
    fun `state is valid after save`() =
        runTest {
            store.save("https://cloud.example/remote.php/dav/files/u/", "user", "pw")
            assertEquals(CredentialState.Valid, store.credentialState.first())
            assertTrue(store.isConfigured.first())
            assertEquals("pw", store.password())
            assertEquals("user", store.username())
        }

    @Test
    fun `garbage ciphertext is corrupted, fails closed and is logged without the value`() =
        runTest {
            store.save("https://cloud.example/", "user", "pw")
            val garbage = "R0FSQkFHRS1DSVBIRVJURVhU"
            securePrefs().edit().putString("password", garbage).commit()

            assertEquals(
                CredentialState.Corrupted(CredentialCorruption.UNREADABLE),
                store.credentialState.first(),
            )
            assertFalse(store.isConfigured.first())
            assertNull(store.password())
            assertFalse(store.isLoggedIn())
            assertEquals("", store.webDavConfig.first().password)

            val logs = warnings()
            assertTrue("expected a warning to be logged", logs.any { "password" in it.msg })
            assertTrue(logs.none { garbage in it.msg || "pw" == it.msg })
        }

    @Test
    fun `lost key makes both fields unreadable`() =
        runTest {
            store.save("https://cloud.example/", "user", "pw")
            keys.rotate()
            assertEquals(
                CredentialState.Corrupted(CredentialCorruption.UNREADABLE),
                store.credentialState.first(),
            )
            assertTrue(warnings().any { "username" in it.msg })
        }

    @Test
    fun `invalidated key is reported as key invalidated`() =
        runTest {
            store.save("https://cloud.example/", "user", "pw")
            keys.invalidated = true
            assertEquals(
                CredentialState.Corrupted(CredentialCorruption.KEY_INVALIDATED),
                store.credentialState.first(),
            )
            assertFalse(store.isConfigured.first())
            assertTrue(warnings().any { "KEY_INVALIDATED" in it.msg })
        }

    @Test
    fun `re-login after key invalidation recovers to valid`() =
        runTest {
            store.save("https://cloud.example/", "user", "pw")
            keys.invalidated = true

            store.save("https://cloud.example/", "user", "new-pw")

            assertEquals(CredentialState.Valid, store.credentialState.first())
            assertEquals("new-pw", store.password())
            assertTrue(store.isConfigured.first())
        }

    @Test
    fun `clear after corruption returns to missing`() =
        runTest {
            store.save("https://cloud.example/", "user", "pw")
            keys.rotate()
            store.clear()
            assertEquals(CredentialState.Missing, store.credentialState.first())
        }

    @Test
    fun `long-lived collectors observe recovery after re-login with the same url`() =
        runTest {
            store.save(URL, "user", "pw")
            keys.invalidated = true
            val state = collectLatest(store.credentialState)
            val configured = collectLatest(store.isConfigured)
            awaitValue(state) { it == CredentialState.Corrupted(CredentialCorruption.KEY_INVALIDATED) }
            awaitValue(configured) { it == false }

            // Same URL: no DataStore value changes except the credentials version bump.
            store.save(URL, "user", "new-pw")

            awaitValue(state) { it == CredentialState.Valid }
            awaitValue(configured) { it == true }
            assertEquals("new-pw", store.webDavConfig.first().password)
        }

    @Test
    fun `long-lived collectors observe unreadable prefs being replaced by a save`() =
        runTest {
            store.save(URL, "user", "pw")
            securePrefs().edit().putString("password", "R0FSQkFHRQ==").commit()
            val state = collectLatest(store.credentialState)
            awaitValue(state) { it == CredentialState.Corrupted(CredentialCorruption.UNREADABLE) }

            store.save(URL, "user", "pw")

            awaitValue(state) { it == CredentialState.Valid }
        }

    @Test
    fun `long-lived collectors observe clear even when DataStore holds nothing else`() =
        runTest {
            securePrefs().edit().putString("password", "R0FSQkFHRQ==").commit()
            val state = collectLatest(store.credentialState)
            awaitValue(state) { it == CredentialState.Corrupted(CredentialCorruption.UNREADABLE) }

            store.clear()

            awaitValue(state) { it == CredentialState.Missing }
        }

    @Test
    fun `failed encryption leaves previously stored credentials and url unchanged`() =
        runTest {
            store.save(URL, "user", "pw")
            val before = securePrefs().all.toMap()
            keys.keyFailure = { KeyStoreException("boom") }

            val error = runCatching { store.save("https://other.example/", "other", "other-pw") }.exceptionOrNull()

            assertTrue("expected CredentialSaveException but was $error", error is CredentialSaveException)
            assertEquals(0, keys.deleteCount)
            keys.keyFailure = null
            assertEquals(URL, store.serverUrl.first())
            assertEquals(before, securePrefs().all.toMap())
            assertEquals("pw", store.password())
            assertEquals("user", store.username())
        }

    @Test
    fun `re-login recovers from an unrecoverable key thrown by the key provider`() =
        runTest {
            store.save(URL, "user", "pw")
            keys.keyFailure = { ProviderException(UnrecoverableKeyException("gone")) }

            store.save(URL, "user", "new-pw")

            assertEquals(1, keys.deleteCount)
            assertEquals(CredentialState.Valid, store.credentialState.first())
            assertEquals("new-pw", store.password())
        }

    @Test
    fun `a persisting decrypt failure is logged once, not on every read`() =
        runTest {
            store.save(URL, "user", "pw")
            securePrefs().edit().putString("password", "R0FSQkFHRQ==").commit()

            repeat(3) {
                store.credentialState.first()
                store.isConfigured.first()
                store.webDavConfig.first()
                store.password()
            }

            assertEquals(1, warnings().count { "password" in it.msg })
        }

    private fun <T> TestScope.collectLatest(flow: Flow<T>): StateFlow<T?> {
        val latest = MutableStateFlow<T?>(null)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect { latest.value = it } }
        return latest
    }

    private suspend fun <T> awaitValue(
        flow: StateFlow<T?>,
        predicate: (T?) -> Boolean,
    ) {
        withContext(Dispatchers.Default) {
            withTimeout(AWAIT_TIMEOUT_MS) { flow.first(predicate) }
        }
    }

    private fun securePrefs() = context.getSharedPreferences("krhnles_secure", Context.MODE_PRIVATE)

    private fun warnings() = ShadowLog.getLogsForTag("CredentialStore").filter { it.type == Log.WARN }

    private companion object {
        const val URL = "https://cloud.example/remote.php/dav/files/u/"
        const val AWAIT_TIMEOUT_MS = 5_000L
        const val SETTLE_MS = 300L
    }

    // ── Blur sensitivity (issue #20 / #39) ───────────────────────────────────

    @Test
    fun `blur sensitivity defaults to Medium and round-trips every level`() =
        runTest {
            assertEquals(BlurSensitivity.MEDIUM, store.blurSensitivity.first())
            BlurSensitivity.entries.forEach { level ->
                store.saveBlurSensitivity(level)
                assertEquals(level, store.blurSensitivity.first())
            }
        }

    @Test
    fun `blur sensitivity survives a new store instance`() =
        runTest {
            store.saveBlurSensitivity(BlurSensitivity.LOW)

            assertEquals(
                BlurSensitivity.LOW,
                CredentialStore(context, CredentialCipher(keys)).blurSensitivity.first(),
            )
        }

    @Test
    fun `unknown and blank stored blur sensitivity fall back to Medium`() =
        runTest {
            listOf("EXTREME", "", "  ", "low").forEach { stored ->
                context.credentialDataStore.edit { it[stringPreferencesKey("blur_sensitivity")] = stored }
                assertEquals(stored, BlurSensitivity.MEDIUM, store.blurSensitivity.first())
            }
        }

    @Test
    fun `wrong-typed stored blur sensitivity falls back to Medium without throwing`() =
        runTest {
            context.credentialDataStore.edit { it[intPreferencesKey("blur_sensitivity")] = 5 }
            assertEquals(BlurSensitivity.MEDIUM, store.blurSensitivity.first())

            context.credentialDataStore.edit { it[booleanPreferencesKey("blur_sensitivity")] = true }
            assertEquals(BlurSensitivity.MEDIUM, store.blurSensitivity.first())
        }

    @Test
    fun `logout resets the blur sensitivity to Medium`() =
        runTest {
            store.save("https://cloud.example.com/dav/", "user", "secret")
            store.saveBlurSensitivity(BlurSensitivity.HIGH)

            store.clear()

            assertEquals(BlurSensitivity.MEDIUM, store.blurSensitivity.first())
        }

    @Test
    fun `auto date folders default to off, round trip and are reset by clear`() =
        runTest {
            assertFalse(store.autoDateFoldersEnabled.first())

            store.saveAutoDateFolders(true)
            assertTrue(store.autoDateFoldersEnabled.first())
            store.saveAutoDateFolders(false)
            assertFalse(store.autoDateFoldersEnabled.first())

            store.saveAutoDateFolders(true)
            store.clear()
            assertFalse(store.autoDateFoldersEnabled.first())
        }

    @Test
    fun `wifi only defaults to off, survives a new store instance and is reset by clear`() =
        runTest {
            assertFalse(store.wifiOnly.first())

            store.saveWifiOnly(true)
            assertTrue(store.wifiOnly.first())
            assertTrue(CredentialStore(context, CredentialCipher(keys)).wifiOnly.first())

            store.clear()
            assertFalse(store.wifiOnly.first())
        }

    @Test
    fun `verify by download defaults to off, survives a new store instance and is reset by clear`() =
        runTest {
            assertFalse(store.verifyByDownload.first())

            store.saveVerifyByDownload(true)
            assertTrue(store.verifyByDownload.first())
            assertTrue(CredentialStore(context, CredentialCipher(keys)).verifyByDownload.first())

            store.clear()
            assertFalse(store.verifyByDownload.first())
        }

    @Test
    fun `blur sensitivity writes do not re-emit the credential flows`() =
        runTest {
            store.save("https://cloud.example.com/dav/", "user", "secret")
            val emissions = mutableListOf<CredentialState>()
            val job =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    store.credentialState.collect {
                        emissions +=
                            it
                    }
                }
            withContext(Dispatchers.Default) { withTimeout(AWAIT_TIMEOUT_MS) { while (emissions.isEmpty()) delay(10) } }
            val before = emissions.size

            store.saveBlurSensitivity(BlurSensitivity.HIGH)
            store.saveWifiOnly(true)
            store.saveAutoDateFolders(true)
            store.saveVerifyByDownload(true)
            // Fence: a credentials write is observed (and emitted) only after the earlier writes.
            store.saveBaseFolder("Photos")
            withContext(Dispatchers.Default) { delay(SETTLE_MS) }

            assertEquals("non-credential writes must not re-emit (base folder did)", before + 1, emissions.size)
            job.cancel()
        }

    @Test
    fun `typed base folder survives a blur sensitivity change in the settings view model`() =
        runTest {
            // The paused Robolectric main looper would never run viewModelScope; use an eager Main.
            Dispatchers.setMain(UnconfinedTestDispatcher())
            try {
                store.save("https://cloud.example.com/dav/", "user", "secret")
                val viewModel = SettingsViewModel(ApplicationProvider.getApplicationContext(), store)
                withContext(Dispatchers.Default) {
                    withTimeout(AWAIT_TIMEOUT_MS) { while (!viewModel.uiState.value.isLoggedIn) delay(10) }
                }

                viewModel.onBaseFolderChange("unsaved/edit")
                viewModel.setBlurSensitivity(BlurSensitivity.HIGH)
                withContext(Dispatchers.Default) {
                    withTimeout(AWAIT_TIMEOUT_MS) {
                        while (store.blurSensitivity.first() !=
                            BlurSensitivity.HIGH
                        ) {
                            delay(10)
                        }
                    }
                    delay(SETTLE_MS)
                }

                assertEquals("unsaved/edit", viewModel.uiState.value.baseFolder)
                assertEquals(BlurSensitivity.HIGH, viewModel.uiState.value.blurSensitivity)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `saving the blur sensitivity does not touch the credentials`() =
        runTest {
            store.save("https://cloud.example.com/dav/", "user", "secret")

            store.saveBlurSensitivity(BlurSensitivity.HIGH)

            assertEquals(CredentialState.Valid, store.credentialState.first())
            assertEquals("secret", store.password())
        }
}
