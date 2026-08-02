package com.opendroid.ai.ui

import android.app.Application
import android.content.Context
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.navigation.testing.TestNavHostController
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Avoids OpenDroidApp because graph navigation does not need Hilt or the Android Keystore. */
class NavigationTestApplication : Application()

/**
 * Drives the real entry-flow back-stack rules against the Navigation runtime.
 *
 * The screens themselves are replaced by empty composables: this guards the graph's
 * routes, `popUpTo` semantics, and saved-state restoration across Navigation upgrades,
 * not the screen content.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = NavigationTestApplication::class)
class OpenDroidNavigationBackStackTest {

    /** A controller with the Compose navigator registered but no graph attached yet. */
    private fun emptyController(): TestNavHostController {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return TestNavHostController(context).apply {
            navigatorProvider.addNavigator(ComposeNavigator())
        }
    }

    private fun TestNavHostController.attachGraph() {
        graph = createGraph(startDestination = OpenDroidRoutes.SPLASH) {
            ALL_ROUTES.forEach { route -> composable(route) {} }
        }
    }

    private fun newController(): TestNavHostController = emptyController().apply { attachGraph() }

    private val NavHostController.routeStack: List<String>
        get() = currentBackStack.value.mapNotNull { it.destination.route }

    @Test
    fun `splash is dropped when onboarding has already been completed`() {
        val navController = newController()

        navController.navigateAfterSplash(isOnboardingCompleted = true)

        assertEquals(OpenDroidRoutes.MAIN, navController.currentDestination?.route)
        assertFalse(navController.routeStack.contains(OpenDroidRoutes.SPLASH))
        // No splash entry left, so system back from the dashboard exits the app.
        assertFalse(navController.popBackStack())
    }

    @Test
    fun `splash is dropped when onboarding is still pending`() {
        val navController = newController()

        navController.navigateAfterSplash(isOnboardingCompleted = false)

        assertEquals(OpenDroidRoutes.ONBOARDING, navController.currentDestination?.route)
        assertFalse(navController.routeStack.contains(OpenDroidRoutes.SPLASH))
    }

    @Test
    fun `finishing onboarding leaves no way back into the flow`() {
        val navController = newController()

        navController.navigateAfterSplash(isOnboardingCompleted = false)
        navController.navigateAfterOnboarding()

        assertEquals(OpenDroidRoutes.MAIN, navController.currentDestination?.route)
        assertFalse(navController.routeStack.contains(OpenDroidRoutes.ONBOARDING))
        assertFalse(navController.popBackStack())
    }

    @Test
    fun `back from a settings destination returns to the dashboard`() {
        val navController = newController()
        navController.navigateAfterSplash(isOnboardingCompleted = true)

        navController.navigate(OpenDroidRoutes.PERMISSIONS)
        assertEquals(OpenDroidRoutes.PERMISSIONS, navController.currentDestination?.route)

        assertTrue(navController.popBackStack())
        assertEquals(OpenDroidRoutes.MAIN, navController.currentDestination?.route)
    }

    @Test
    fun `secondary destinations stack and unwind one at a time`() {
        val navController = newController()
        navController.navigateAfterSplash(isOnboardingCompleted = true)

        navController.navigate(OpenDroidRoutes.BENCHMARK)
        navController.navigate(OpenDroidRoutes.CRASH_LOG)

        assertEquals(
            listOf(OpenDroidRoutes.MAIN, OpenDroidRoutes.BENCHMARK, OpenDroidRoutes.CRASH_LOG),
            navController.routeStack.filter { it in ALL_ROUTES }
        )

        navController.popBackStack()
        assertEquals(OpenDroidRoutes.BENCHMARK, navController.currentDestination?.route)
        navController.popBackStack()
        assertEquals(OpenDroidRoutes.MAIN, navController.currentDestination?.route)
    }

    @Test
    fun `the back stack survives a process-death style save and restore`() {
        val navController = newController()
        navController.navigateAfterSplash(isOnboardingCompleted = true)
        navController.navigate(OpenDroidRoutes.NOTIFICATION_HISTORY)

        val savedState = navController.saveState()
        assertNotNull(savedState)

        // restoreState must be applied before the graph, mirroring NavHost's own order.
        val restored = emptyController().apply {
            restoreState(savedState)
            attachGraph()
        }

        assertEquals(
            OpenDroidRoutes.NOTIFICATION_HISTORY,
            restored.currentDestination?.route
        )
        assertTrue(restored.popBackStack())
        assertEquals(OpenDroidRoutes.MAIN, restored.currentDestination?.route)
        assertFalse(restored.routeStack.contains(OpenDroidRoutes.SPLASH))
    }

    private companion object {
        val ALL_ROUTES = listOf(
            OpenDroidRoutes.SPLASH,
            OpenDroidRoutes.ONBOARDING,
            OpenDroidRoutes.MAIN,
            OpenDroidRoutes.BENCHMARK,
            OpenDroidRoutes.PRIVACY_POLICY,
            OpenDroidRoutes.TERMS_OF_USE,
            OpenDroidRoutes.HELP_CENTER,
            OpenDroidRoutes.LICENSE,
            OpenDroidRoutes.ABOUT,
            OpenDroidRoutes.AUTO_REPLY_SETTINGS,
            OpenDroidRoutes.NOTIFICATION_HISTORY,
            OpenDroidRoutes.PERMISSIONS,
            OpenDroidRoutes.CRASH_LOG
        )
    }
}
