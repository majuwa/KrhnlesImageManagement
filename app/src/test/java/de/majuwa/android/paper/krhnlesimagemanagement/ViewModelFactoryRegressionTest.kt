package de.majuwa.android.paper.krhnlesimagemanagement

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.AlbumDetailViewModel
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.AlbumsViewModel
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.BlurViewModel
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.DuplicatesViewModel
import de.majuwa.android.paper.krhnlesimagemanagement.ui.onboarding.OnboardingViewModel
import de.majuwa.android.paper.krhnlesimagemanagement.ui.onboarding.StartupViewModel
import de.majuwa.android.paper.krhnlesimagemanagement.ui.photogrid.PhotoGridViewModel
import de.majuwa.android.paper.krhnlesimagemanagement.ui.photogrid.UploadedRemovalViewModel
import de.majuwa.android.paper.krhnlesimagemanagement.ui.settings.SettingsViewModel
import de.majuwa.android.paper.krhnlesimagemanagement.ui.share.ShareViewModel
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Ensures every `AndroidViewModel` has a single-argument (Application) constructor
 * so that `AndroidViewModelFactory` can instantiate it via reflection.
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
                arrayOf(AlbumDetailViewModel::class.java),
                arrayOf(StartupViewModel::class.java),
                arrayOf(ShareViewModel::class.java),
                arrayOf(UploadedRemovalViewModel::class.java),
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

/**
 * `OnboardingViewModel` is created by the default `SavedStateViewModelFactory`, which needs an
 * `(Application, SavedStateHandle)` constructor; without it the page would not survive process death.
 */
class OnboardingViewModelFactoryRegressionTest {
    @Test
    fun `has Application and SavedStateHandle constructor for SavedStateViewModelFactory`() {
        assertNotNull(
            OnboardingViewModel::class.java.getConstructor(Application::class.java, SavedStateHandle::class.java),
        )
    }
}

/** Plain `ViewModel`s need a no-argument constructor for the default `NewInstanceFactory`. */
@RunWith(Parameterized::class)
class PlainViewModelFactoryRegressionTest(
    private val vmClass: Class<*>,
) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun viewModelClasses(): List<Array<Any>> =
            listOf(
                arrayOf(DuplicatesViewModel::class.java),
                arrayOf(BlurViewModel::class.java),
            )
    }

    @Test
    fun `has a no-argument constructor`() {
        assertNotNull(
            "${vmClass.simpleName} must have a no-arg constructor — did you forget @JvmOverloads?",
            vmClass.getConstructor(),
        )
    }
}
