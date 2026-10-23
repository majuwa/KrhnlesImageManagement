package de.majuwa.android.paper.krhnlesimagemanagement

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import de.majuwa.android.paper.krhnlesimagemanagement.data.OnboardingStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun TestScope.dataStore(file: File) =
        PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { file },
        )

    @Test
    fun `defaults to false`() =
        runTest(UnconfinedTestDispatcher()) {
            val store = OnboardingStore(dataStore(File(tmp.root, "a.preferences_pb")))
            assertFalse(store.completed.first())
        }

    @Test
    fun `markCompleted flips the flag`() =
        runTest(UnconfinedTestDispatcher()) {
            val store = OnboardingStore(dataStore(File(tmp.root, "d.preferences_pb")))
            store.markCompleted()
            assertTrue(store.completed.first())
        }

    @Test
    fun `corrupt file reads as unset`() =
        runTest(UnconfinedTestDispatcher()) {
            val file = File(tmp.root, "e.preferences_pb").apply { writeBytes(byteArrayOf(1, 2, 3, 99, 42)) }
            val store = OnboardingStore(dataStore(file))
            assertFalse(store.completed.first())
        }

    @Test
    fun `IOException while reading reads as unset`() =
        runTest {
            val failing =
                object : DataStore<Preferences> {
                    override val data = flow<Preferences> { throw IOException("boom") }

                    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences) =
                        throw IOException("boom")
                }
            assertFalse(OnboardingStore(failing).completed.first())
        }
}
