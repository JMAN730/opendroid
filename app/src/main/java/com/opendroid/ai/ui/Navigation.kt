package com.opendroid.ai.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.opendroid.ai.ui.screens.*
import com.opendroid.ai.ui.theme.*
import com.opendroid.ai.ui.viewmodel.*

/**
 * Route names of the top-level navigation graph.
 *
 * Kept next to [navigateAfterSplash] and [navigateAfterOnboarding] so the back-stack
 * rules of the entry flow can be exercised without composing the screens themselves.
 */
object OpenDroidRoutes {
    const val SPLASH = "splash"
    const val ONBOARDING = "onboarding"
    const val MAIN = "main"
    const val BENCHMARK = "benchmark"
    const val PRIVACY_POLICY = "privacy_policy"
    const val TERMS_OF_USE = "terms_of_use"
    const val HELP_CENTER = "help_center"
    const val LICENSE = "license"
    const val ABOUT = "about"
    const val AUTO_REPLY_SETTINGS = "auto_reply_settings"
    const val NOTIFICATION_HISTORY = "notification_history"
    const val PERMISSIONS = "permissions"
    const val CRASH_LOG = "crash_log"
    const val ROUTINES = "routines"
}

/**
 * Leaves the splash screen for onboarding or the main dashboard, dropping splash from the
 * back stack so system back from the first real screen exits the app instead of replaying it.
 */
fun NavHostController.navigateAfterSplash(isOnboardingCompleted: Boolean) {
    val destination = if (isOnboardingCompleted) OpenDroidRoutes.MAIN else OpenDroidRoutes.ONBOARDING
    navigate(destination) {
        popUpTo(OpenDroidRoutes.SPLASH) { inclusive = true }
    }
}

/** Enters the dashboard after onboarding, so back never returns to the completed flow. */
fun NavHostController.navigateAfterOnboarding() {
    navigate(OpenDroidRoutes.MAIN) {
        popUpTo(OpenDroidRoutes.ONBOARDING) { inclusive = true }
    }
}

@Composable
fun OpenDroidNavigation(
    navController: NavHostController = rememberNavController()
) {
    NavHost(
        navController = navController,
        startDestination = OpenDroidRoutes.SPLASH,
        modifier = Modifier.fillMaxSize().background(AppTheme.colors.background)
    ) {
        composable(OpenDroidRoutes.SPLASH) {
            val startupViewModel: StartupViewModel = hiltViewModel()
            val startDestination by startupViewModel.startDestination.collectAsState()
            var splashFinished by remember { mutableStateOf(false) }

            SplashScreen(onNavigateNext = { splashFinished = true })

            // The destination is decided off the main thread, so wait for both the animation and
            // the decrypted profile check rather than guessing a route.
            LaunchedEffect(splashFinished, startDestination) {
                val destination = startDestination
                if (splashFinished && destination != null) {
                    navController.navigateAfterSplash(
                        isOnboardingCompleted = destination == StartDestination.MAIN
                    )
                }
            }
        }

        composable(OpenDroidRoutes.ONBOARDING) {
            OnboardingScreen(
                onFinished = { navController.navigateAfterOnboarding() }
            )
        }

        composable(OpenDroidRoutes.MAIN) {
            MainDashboard(
                onNavigateToBenchmark = {
                    navController.navigate(OpenDroidRoutes.BENCHMARK)
                },
                onNavigateToPrivacyPolicy = {
                    navController.navigate(OpenDroidRoutes.PRIVACY_POLICY)
                },
                onNavigateToTermsOfUse = {
                    navController.navigate(OpenDroidRoutes.TERMS_OF_USE)
                },
                onNavigateToHelpCenter = {
                    navController.navigate(OpenDroidRoutes.HELP_CENTER)
                },
                onNavigateToLicense = {
                    navController.navigate(OpenDroidRoutes.LICENSE)
                },
                onNavigateToAbout = {
                    navController.navigate(OpenDroidRoutes.ABOUT)
                },
                onNavigateToAutoReply = {
                    navController.navigate(OpenDroidRoutes.AUTO_REPLY_SETTINGS)
                },
                onNavigateToNotificationHistory = {
                    navController.navigate(OpenDroidRoutes.NOTIFICATION_HISTORY)
                },
                onNavigateToPermissions = {
                    navController.navigate(OpenDroidRoutes.PERMISSIONS)
                },
                onNavigateToCrashLog = {
                    navController.navigate(OpenDroidRoutes.CRASH_LOG)
                },
                onNavigateToRoutines = {
                    navController.navigate(OpenDroidRoutes.ROUTINES)
                }
            )
        }

        composable(OpenDroidRoutes.BENCHMARK) {
            val settingsViewModel: SettingsViewModel = hiltViewModel()
            BenchmarkScreen(
                viewModel = settingsViewModel,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(OpenDroidRoutes.PRIVACY_POLICY) {
            PrivacyPolicyScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(OpenDroidRoutes.ABOUT) {
            AboutScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(OpenDroidRoutes.TERMS_OF_USE) {
            TermsOfUseScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(OpenDroidRoutes.HELP_CENTER) {
            HelpCenterScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(OpenDroidRoutes.LICENSE) {
            LicenseScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(OpenDroidRoutes.AUTO_REPLY_SETTINGS) {
            val settingsRepo = hiltViewModel<AutoReplyViewModel>().settingsRepository
            AutoReplySettingsScreen(
                settingsRepository = settingsRepo,
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(OpenDroidRoutes.NOTIFICATION_HISTORY) {
            val notifDao = hiltViewModel<NotificationHistoryViewModel>().notificationDao
            NotificationHistoryScreen(
                notificationDao = notifDao,
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(OpenDroidRoutes.PERMISSIONS) {
            PermissionsScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(OpenDroidRoutes.CRASH_LOG) {
            CrashLogScreen(
                viewModel = hiltViewModel(),
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(OpenDroidRoutes.ROUTINES) {
            val routineViewModel: com.opendroid.ai.ui.viewmodel.RoutineViewModel = hiltViewModel()
            com.opendroid.ai.ui.screens.RoutinesScreen(
                viewModel = routineViewModel,
                onBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Chat : Screen("chat", "Chat", Icons.Default.Chat)
    object Plan : Screen("plan", "Plan", Icons.Default.List)
    object Memory : Screen("memory", "Memory", Icons.Default.Star)
    object Macros : Screen("macros", "Macros", Icons.Default.Build)
    object History : Screen("history", "Logs", Icons.Default.History)
    object Settings : Screen("settings", "Settings", Icons.Default.Settings)
}

@Composable
fun MainDashboard(
    onNavigateToBenchmark: () -> Unit,
    onNavigateToPrivacyPolicy: () -> Unit,
    onNavigateToTermsOfUse: () -> Unit,
    onNavigateToHelpCenter: () -> Unit,
    onNavigateToLicense: () -> Unit,
    onNavigateToAbout: () -> Unit,
    onNavigateToAutoReply: () -> Unit = {},
    onNavigateToNotificationHistory: () -> Unit = {},
    onNavigateToPermissions: () -> Unit = {},
    onNavigateToCrashLog: () -> Unit = {},
    onNavigateToRoutines: () -> Unit = {}
) {
    val context = LocalContext.current

    // Start the service as soon as RECORD_AUDIO is granted, and keep checking on every
    // resume - not just once on first composition - so a user who grants the microphone
    // from Settings > Permissions (rather than at onboarding) gets the service started
    // immediately on returning here, with no app restart required.
    var recordAudioServiceStarted by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                if (granted && !recordAudioServiceStarted) {
                    recordAudioServiceStarted = true
                    com.opendroid.ai.core.service.OpenDroidService.start(context)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    var currentTab by remember { mutableStateOf<Screen>(Screen.Chat) }

    val chatViewModel: ChatViewModel = hiltViewModel()
    val planViewModel: PlanViewModel = hiltViewModel()
    val memoryViewModel: MemoryViewModel = hiltViewModel()
    val macroViewModel: MacroViewModel = hiltViewModel()
    val historyViewModel: HistoryViewModel = hiltViewModel()
    val settingsViewModel: SettingsViewModel = hiltViewModel()

    val tabs = listOf(
        Screen.Chat,
        Screen.Plan,
        Screen.Memory,
        Screen.Macros,
        Screen.History,
        Screen.Settings
    )

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = DarkSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .border(1.dp, BorderColor, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)),
                tonalElevation = 8.dp
            ) {
                tabs.forEach { tab ->
                    val isSelected = currentTab == tab
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = { currentTab = tab },
                        icon = {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.title,
                                tint = if (isSelected) AccentNeonGreen else TextSecondary
                            )
                        },
                        label = {
                            Text(
                                text = tab.title,
                                fontSize = 10.sp,
                                color = if (isSelected) AccentNeonGreen else TextSecondary,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = AccentNeonGreen.copy(alpha = 0.15f)
                        )
                    )
                }
            }
        },
        containerColor = DarkBackground
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .consumeWindowInsets(paddingValues)
        ) {
            when (currentTab) {
                Screen.Chat -> ChatScreen(viewModel = chatViewModel)
                Screen.Plan -> PlanScreen(viewModel = planViewModel)
                Screen.Memory -> MemoryScreen(viewModel = memoryViewModel)
                Screen.Macros -> MacrosScreen(
                    viewModel = macroViewModel,
                    onNavigateToRoutines = onNavigateToRoutines
                )
                Screen.History -> LogsScreen(viewModel = historyViewModel)
                Screen.Settings -> SettingsScreen(
                    viewModel = settingsViewModel,
                    onNavigateToBenchmark = onNavigateToBenchmark,
                    onNavigateToPrivacyPolicy = onNavigateToPrivacyPolicy,
                    onNavigateToTermsOfUse = onNavigateToTermsOfUse,
                    onNavigateToHelpCenter = onNavigateToHelpCenter,
                    onNavigateToLicense = onNavigateToLicense,
                    onNavigateToAbout = onNavigateToAbout,
                    onNavigateToAutoReply = onNavigateToAutoReply,
                    onNavigateToNotificationHistory = onNavigateToNotificationHistory,
                    onNavigateToPermissions = onNavigateToPermissions,
                    onNavigateToCrashLog = onNavigateToCrashLog,
                    onNavigateToRoutines = onNavigateToRoutines
                )
            }
        }
    }
}
