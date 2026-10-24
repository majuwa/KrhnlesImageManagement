package de.majuwa.android.paper.krhnlesimagemanagement

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import de.majuwa.android.paper.krhnlesimagemanagement.data.MediaPermissionStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The two independent flags of [MediaPermissionStore] (test DataStore, never the app delegate). */
@OptIn(ExperimentalCoroutinesApi::class)
class MediaPermissionStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun TestScope.store(name: String) =
        MediaPermissionStore(
            PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { File(tmp.root, name) }),
        )

    @Test
    fun `both flags default to false`() =
        runTest(UnconfinedTestDispatcher()) {
            val store = store("a.preferences_pb")
            assertFalse(store.videoAccessRequested.first())
            assertFalse(store.videoRationaleShown.first())
        }

    @Test
    fun `rationale flag is set independently of the request flag`() =
        runTest(UnconfinedTestDispatcher()) {
            val store = store("b.preferences_pb")

            store.markVideoRationaleShown()

            assertTrue(store.videoRationaleShown.first())
            assertFalse(store.videoAccessRequested.first())
        }
}
