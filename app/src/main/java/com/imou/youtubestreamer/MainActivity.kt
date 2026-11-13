package com.imou.youtubestreamer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.ContextCompat
import com.imou.youtubestreamer.data.AppLogger
import com.imou.youtubestreamer.ui.MainViewModel
import com.imou.youtubestreamer.ui.components.AppUpdateDialog
import com.imou.youtubestreamer.ui.screens.DashboardScreen
import com.imou.youtubestreamer.ui.screens.LogsScreen
import com.imou.youtubestreamer.ui.screens.SettingsScreen
import com.imou.youtubestreamer.ui.theme.AppIcons
import com.imou.youtubestreamer.ui.theme.DarkSurface
import com.imou.youtubestreamer.ui.theme.SurveillanceGreen
import com.imou.youtubestreamer.ui.theme.SurveillanceTheme
import com.imou.youtubestreamer.ui.theme.TextMuted

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Dashboard : Screen("dashboard", "Dashboard", AppIcons.Dashboard)
    object Settings : Screen("settings", "Settings", Icons.Default.Settings)
    object Logs : Screen("logs", "Diagnostics", AppIcons.ListAlt)
}

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private val viewModel: MainViewModel by viewModels()

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            AppLogger.step(TAG, "notificationPermissionLauncher", "POST_NOTIFICATIONS granted=$granted")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppLogger.enter(TAG, "onCreate", "savedInstanceState=${savedInstanceState != null}")
        super.onCreate(savedInstanceState)

        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasPerm = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            AppLogger.step(TAG, "onCreate", "Android 13+ POST_NOTIFICATIONS alreadyGranted=$hasPerm")
            if (!hasPerm) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            SurveillanceTheme {
                var currentScreen by remember { mutableStateOf<Screen>(Screen.Dashboard) }
                val updateStatus by viewModel.updateStatus.collectAsState()

                // Check for updates automatically on startup if enabled
                LaunchedEffect(Unit) {
                    if (viewModel.settings.autoCheckUpdates) {
                        viewModel.checkForUpdates(applicationContext, manual = false)
                    }
                }

                // Global In-App Update Dialog (Handles UpdateAvailable, Downloading, ReadyToInstall, Error)
                AppUpdateDialog(
                    status = updateStatus,
                    viewModel = viewModel
                )

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        NavigationBar(
                            containerColor = DarkSurface
                        ) {
                            val items = listOf(Screen.Dashboard, Screen.Settings, Screen.Logs)
                            items.forEach { screen ->
                                NavigationBarItem(
                                    icon = { Icon(screen.icon, contentDescription = screen.title) },
                                    label = { Text(screen.title) },
                                    selected = currentScreen == screen,
                                    onClick = {
                                        AppLogger.step(TAG, "NavigationBarItem.onClick", "Switching screen to ${screen.route}")
                                        currentScreen = screen
                                    },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = SurveillanceGreen,
                                        selectedTextColor = SurveillanceGreen,
                                        indicatorColor = DarkSurface,
                                        unselectedIconColor = TextMuted,
                                        unselectedTextColor = TextMuted
                                    )
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        when (currentScreen) {
                            Screen.Dashboard -> DashboardScreen(viewModel = viewModel)
                            Screen.Settings -> SettingsScreen(viewModel = viewModel)
                            Screen.Logs -> LogsScreen(viewModel = viewModel)
                        }
                    }
                }
            }
        }
        AppLogger.exit(TAG, "onCreate", "UI content set")
    }

    override fun onResume() {
        super.onResume()
        AppLogger.step(TAG, "onResume", "Activity resumed")
    }

    override fun onPause() {
        super.onPause()
        AppLogger.step(TAG, "onPause", "Activity paused")
    }

    override fun onDestroy() {
        super.onDestroy()
        AppLogger.step(TAG, "onDestroy", "Activity destroyed")
    }
}
