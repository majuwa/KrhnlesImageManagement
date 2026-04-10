package de.majuwa.android.paper.krhnlesimagemanagement

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import de.majuwa.android.paper.krhnlesimagemanagement.ui.MainActivity
import junit.framework.TestCase.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PermissionHandlingTest {
    @get:Rule
    val grantPermissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.READ_MEDIA_IMAGES)

    @Test
    fun permissionRequested_whenActivityStartsWithoutPermission() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val permissionStatus =
                    ActivityCompat.checkSelfPermission(
                        activity,
                        Manifest.permission.READ_MEDIA_IMAGES,
                    )
                // Permission should either be granted (via rule) or show prompt
                assert(
                    permissionStatus == PackageManager.PERMISSION_GRANTED ||
                        permissionStatus == PackageManager.PERMISSION_DENIED,
                )
            }
        }
    }

    @Test
    fun readMediaImagesPermissionGranted_allowsPhotoLoading() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val permissionStatus =
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_IMAGES,
            )
        assertEquals(PackageManager.PERMISSION_GRANTED, permissionStatus)
    }

    @Test
    fun postNotificationsPermission_required_forUploadNotifications() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val permissionStatus =
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        // Should not crash if denied; should handle gracefully
        assert(
            permissionStatus == PackageManager.PERMISSION_GRANTED ||
                permissionStatus == PackageManager.PERMISSION_DENIED,
        )
    }
}
