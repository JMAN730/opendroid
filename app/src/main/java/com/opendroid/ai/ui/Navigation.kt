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

@Composable
fun OpenDroidNavigation(
    navController: NavHostController = rememberNavController()
) {
    NavHost(
        navController = navController,
        startDestination = "splash",
        modifier = Modifier.fillMaxSize().background(AppTheme.colors.background)
    ) {
        composable("splash") {
            val startupViewModel: StartupViewModel = hiltViewModel()
            val startDestination by startupViewModel.startDestination.collectAsState()
            var splashFinished by remember { mutableStateOf(false) }

            SplashScreen(onNavigateNext = { splashFinished = true })

            // The destination is decided off the main thread, so wait for both the animation and
            // the decrypted profile check rather than guessing a route.
            LaunchedEffect(splashFinished, startDestination) {
                val destination = startDestination
                if (splashFinished && destination != null) {
                    navController.navigate(destination.route) {
                        popUpTo("splash") { inclusive = true }
                    }
                }
            }
        }

        composable("onboarding") {
            OnboardingScreen(
                onFinished = {
                    navController.navigate("main") {
                        popUpTo("onboarding") { inclusive = true }
                    }
                }
            )
        }

        composable("main") {
            MainDashboard(
                onNavigateToBenchmark = {
                    navController.navigate("benchmark")
                },
                onNavigateToPrivacyPolicy = {
                    navController.navigate("privacy_policy")
                },
                onNavigateToTermsOfUse = {
                    navController.navigate("terms_of_use")
                },
                onNavigateToHelpCenter = {
                    navController.navigate("help_center")
                },
                onNavigateToLicense = {
                    navController.navigate("license")
                },
                onNavigateToAbout = {
                    navController.navigate("about")
                },
                onNavigateToAutoReply = {
                    navController.navigate("auto_reply_settings")
                },
                onNavigateToNotificationHistory = {
                    navController.navigate("notification_history")
                },
                onNavigateToPermissions = {
                    navController.navigate("permissions")
                },
                onNavigateToCrashLog = {
                    navController.navigate("crash_log")
                }
            )
        }

        composable("benchmark") {
            val settingsViewModel: SettingsViewModel = hiltViewModel()
            BenchmarkScreen(
                viewModel = settingsViewModel,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable("privacy_policy") {
            PrivacyPolicyScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable("about") {
            AboutScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable("terms_of_use") {
            TermsOfUseScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable("help_center") {
            HelpCenterScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable("license") {
            LicenseScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable("auto_reply_settings") {
            val settingsRepo = hiltViewModel<AutoReplyViewModel>().settingsRepository
            AutoReplySettingsScreen(
                settingsRepository = settingsRepo,
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable("notification_history") {
            val notifDao = hiltViewModel<NotificationHistoryViewModel>().notificationDao
            NotificationHistoryScreen(
                notificationDao = notifDao,
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable("permissions") {
            PermissionsScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable("crash_log") {
            CrashLogScreen(
                viewModel = hiltViewModel(),
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
    onNavigateToCrashLog: () -> Unit = {}
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
                Screen.Macros -> MacrosScreen(viewModel = macroViewModel)
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
                    onNavigateToCrashLog = onNavigateToCrashLog
                )
            }
        }
    }
}
