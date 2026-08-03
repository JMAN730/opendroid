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

    @Test
    fun `system bars keep the app theme when a configuration change reapplies edge-to-edge`() {
        val activity = Robolectric.buildActivity(EdgeToEdgeTestActivity::class.java).setup().get()

        activity.withSystemNightMode(Configuration.UI_MODE_NIGHT_NO) {
            activity.enableOpenDroidEdgeToEdge(isDarkTheme = true)
        }

        // Activity 1.13 reinvokes enableEdgeToEdge on configuration changes, so the
        // second application must still resolve the scrim from the in-app theme.
        activity.withSystemNightMode(Configuration.UI_MODE_NIGHT_YES) {
            activity.enableOpenDroidEdgeToEdge(isDarkTheme = true)

            assertEquals(DarkPalette.background.toArgb(), activity.window.navigationBarColor)
            assertFalse(activity.window.decorView.hasLightNavigationBar())
        }
    }

    @Test
    fun `an in-app theme switch repaints the system bars for the new palette`() {
        val activity = Robolectric.buildActivity(EdgeToEdgeTestActivity::class.java).setup().get()

        activity.enableOpenDroidEdgeToEdge(isDarkTheme = true)
        assertEquals(DarkPalette.background.toArgb(), activity.window.navigationBarColor)

        activity.enableOpenDroidEdgeToEdge(isDarkTheme = false)

        assertEquals(LightPalette.background.toArgb(), activity.window.navigationBarColor)
        assertTrue(activity.window.decorView.hasLightNavigationBar())
    }

    @Test
    @Config(sdk = [36], application = EdgeToEdgeTestApplication::class)
    fun `both system bars stay transparent on API 29 and later`() {
        val activity = Robolectric.buildActivity(EdgeToEdgeTestActivity::class.java).setup().get()

        activity.enableOpenDroidEdgeToEdge(isDarkTheme = true)

        assertEquals(android.graphics.Color.TRANSPARENT, activity.window.statusBarColor)
        assertEquals(android.graphics.Color.TRANSPARENT, activity.window.navigationBarColor)
        // On API 29+ the bars are transparent and the platform, not the app, decides
        // when a navigation-bar scrim is needed.
        assertTrue(activity.window.isNavigationBarContrastEnforced)
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
