package de.majuwa.android.paper.krhnlesimagemanagement.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import de.majuwa.android.paper.krhnlesimagemanagement.R
import de.majuwa.android.paper.krhnlesimagemanagement.ui.theme.KrhnlesImageManagementTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BottomNavLabelsTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `Photos nav label comes from string resource`() {
        composeTestRule.setContent {
            KrhnlesImageManagementTheme {
                NavigationBar {
                    NavigationBarItem(
                        selected = true,
                        onClick = {},
                        icon = { Icon(Icons.Default.PhotoLibrary, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_photos)) },
                    )
                }
            }
        }
        composeTestRule.onNodeWithText("Photos").assertIsDisplayed()
    }

    @Test
    fun `Albums nav label comes from string resource`() {
        composeTestRule.setContent {
            KrhnlesImageManagementTheme {
                NavigationBar {
                    NavigationBarItem(
                        selected = true,
                        onClick = {},
                        icon = { Icon(Icons.Default.Collections, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_albums)) },
                    )
                }
            }
        }
        composeTestRule.onNodeWithText("Albums").assertIsDisplayed()
    }
}
