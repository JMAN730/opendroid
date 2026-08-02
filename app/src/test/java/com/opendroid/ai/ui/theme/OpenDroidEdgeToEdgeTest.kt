package com.opendroid.ai.ui.theme

import android.app.Application
import android.content.res.Configuration
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class EdgeToEdgeTestApplication : Application()

private class EdgeToEdgeTestActivity : ComponentActivity()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = EdgeToEdgeTestApplication::class)
@Suppress("DEPRECATION") // Exercises AndroidX's API 28 system-bar implementation directly.
class OpenDroidEdgeToEdgeTest {

    @Test
    fun `dark app theme overrides a light system theme for navigation contrast`() {
        val activity = Robolectric.buildActivity(EdgeToEdgeTestActivity::class.java).setup().get()

        activity.withSystemNightMode(Configuration.UI_MODE_NIGHT_NO) {
            activity.enableOpenDroidEdgeToEdge(isDarkTheme = true)

            assertEquals(DarkPalette.background.toArgb(), activity.window.navigationBarColor)
            assertFalse(activity.window.decorView.hasLightNavigationBar())
        }
    }

    @Test
    fun `light app theme overrides a dark system theme for navigation contrast`() {
        val activity = Robolectric.buildActivity(EdgeToEdgeTestActivity::class.java).setup().get()

        activity.withSystemNightMode(Configuration.UI_MODE_NIGHT_YES) {
            activity.enableOpenDroidEdgeToEdge(isDarkTheme = false)

            assertEquals(LightPalette.background.toArgb(), activity.window.navigationBarColor)
            assertTrue(activity.window.decorView.hasLightNavigationBar())
        }
    }

    private fun View.hasLightNavigationBar(): Boolean =
        systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR != 0

    private fun ComponentActivity.withSystemNightMode(nightMode: Int, block: () -> Unit) {
        val originalConfiguration = Configuration(resources.configuration)
        try {
            val configuration = Configuration(originalConfiguration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
            }
            resources.updateConfiguration(configuration, resources.displayMetrics)
            block()
        } finally {
            resources.updateConfiguration(originalConfiguration, resources.displayMetrics)
        }
    }
}
