package de.majuwa.android.paper.krhnlesimagemanagement

import android.app.Application
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.AlbumsViewModel
import de.majuwa.android.paper.krhnlesimagemanagement.ui.photogrid.PhotoGridViewModel
import de.majuwa.android.paper.krhnlesimagemanagement.ui.settings.SettingsViewModel
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Ensures every [AndroidViewModel] has a single-argument (Application) constructor
 * so that [AndroidViewModelFactory] can instantiate it via reflection.
 *
 * Kotlin default parameters do NOT generate a JVM overload with fewer parameters
 * unless [@JvmOverloads] is present — this test catches that mistake.
 */
@RunWith(Parameterized::class)
class ViewModelFactoryRegressionTest(
    private val vmClass: Class<*>,
) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun viewModelClasses(): List<Array<Any>> =
            listOf(
                arrayOf(PhotoGridViewModel::class.java),
                arrayOf(SettingsViewModel::class.java),
                arrayOf(AlbumsViewModel::class.java),
            )
    }

    @Test
    fun `has Application-only constructor for AndroidViewModelFactory`() {
        val ctor = vmClass.getConstructor(Application::class.java)
        assertNotNull(
            "${vmClass.simpleName} must have a (Application) constructor " +
                "for AndroidViewModelFactory — did you forget @JvmOverloads?",
            ctor,
        )
    }
}
