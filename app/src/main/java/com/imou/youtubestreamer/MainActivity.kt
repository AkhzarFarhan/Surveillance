package com.imou.youtubestreamer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.ContextCompat
import com.imou.youtubestreamer.ui.MainViewModel
import com.imou.youtubestreamer.ui.components.AppUpdateDialog
import com.imou.youtubestreamer.ui.screens.DashboardScreen
import com.imou.youtubestreamer.ui.screens.LogsScreen
import com.imou.youtubestreamer.ui.screens.SettingsScreen
import com.imou.youtubestreamer.ui.theme.DarkSurface
import com.imou.youtubestreamer.ui.theme.SurveillanceGreen
import com.imou.youtubestreamer.ui.theme.SurveillanceTheme
import com.imou.youtubestreamer.ui.theme.TextMuted

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Dashboard : Screen("dashboard", "Dashboard", Icons.Default.Dashboard)
    object Settings : Screen("settings", "Settings", Icons.Default.Settings)
    object Logs : Screen("logs", "Diagnostics", Icons.AutoMirrored.Filled.ListAlt)
}

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            SurveillanceTheme {
                var currentScreen by remember { mutableStateOf<Screen>(Screen.Dashboard) }
                val updateStatus by viewModel.updateStatus.collectAsState()

                // Check for updates automatically on startup if enabled
                LaunchedEffect(Unit) {
                    if (viewModel.settings.autoCheckUpdates) {
                        viewModel.checkForUpdates(this@MainActivity, manual = false)
                    }
                }

                // Global In-App Update Dialog (Handles UpdateAvailable, Downloading, ReadyToInstall, Error)
                AppUpdateDialog(
                    status = updateStatus,
                    viewModel = viewModel
                )

                // 1-Tap Google Sign-In launcher
                val googleSignInLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    viewModel.handleSignInResult(result.data)
                }

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
                                    onClick = { currentScreen = screen },
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
                            Screen.Dashboard -> DashboardScreen(
                                viewModel = viewModel,
                                onLaunchSignIn = {
                                    val client = viewModel.authManager.getGoogleSignInClient()
                                    googleSignInLauncher.launch(client.signInIntent)
                                }
                            )
                            Screen.Settings -> SettingsScreen(viewModel = viewModel)
                            Screen.Logs -> LogsScreen(viewModel = viewModel)
                        }
                    }
                }
            }
        }
    }
}
