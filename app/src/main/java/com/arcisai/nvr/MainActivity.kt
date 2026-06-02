package com.arcisai.nvr

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.arcisai.nvr.ui.Tab
import com.arcisai.nvr.ui.screens.DeviceInfoScreen
import com.arcisai.nvr.ui.screens.EncodingScreen
import com.arcisai.nvr.ui.screens.GeneralScreen
import com.arcisai.nvr.ui.screens.ImageColorScreen
import com.arcisai.nvr.ui.screens.LiveScreen
import com.arcisai.nvr.ui.screens.LiveTabScreen
import com.arcisai.nvr.ui.screens.LoginScreen
import com.arcisai.nvr.ui.screens.MaintenanceScreen
import com.arcisai.nvr.ui.screens.ManageScreen
import com.arcisai.nvr.ui.screens.NetworkScreen
import com.arcisai.nvr.ui.screens.PasswordScreen
import com.arcisai.nvr.ui.screens.PlaybackTabScreen
import com.arcisai.nvr.ui.screens.SettingsHubScreen
import com.arcisai.nvr.ui.screens.SmtpScreen
import com.arcisai.nvr.ui.screens.TimeScreen
import com.arcisai.nvr.ui.screens.WifiScreen
import com.arcisai.nvr.ui.theme.ArcisNvrTheme
import com.arcisai.nvr.viewmodel.NvrViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: NvrViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ArcisNvrTheme {
                Surface(modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background) {
                    val rootNav = rememberNavController()
                    // Always open on the connection page (LAN / Remote P2P) — never
                    // resume straight into the last session the user left.
                    val startRoute = "login"
                    NavHost(navController = rootNav, startDestination = startRoute) {
                        composable("login") {
                            LoginScreen(viewModel) {
                                rootNav.navigate("main") {
                                    popUpTo("login") { inclusive = true }
                                }
                            }
                        }
                        composable("main") {
                            MainScaffold(viewModel) {
                                viewModel.logout()
                                rootNav.navigate("login") {
                                    popUpTo("main") { inclusive = true }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MainScaffold(vm: NvrViewModel, onLogout: () -> Unit) {
    val nav = rememberNavController()
    Scaffold(
        bottomBar = { BottomTabBar(nav) },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Tab.LIVE.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Tab.LIVE.route) {
                LiveTabScreen(vm, onChannelTap = { ch -> nav.navigate("live/$ch") })
            }
            composable("live/{ch}") { entry ->
                val ch = entry.arguments?.getString("ch")?.toIntOrNull() ?: 0
                LiveScreen(vm, channelId = ch, onBack = { nav.popBackStack() })
            }
            composable(Tab.PLAYBACK.route) { PlaybackTabScreen() }
            composable(Tab.MANAGE.route)   { ManageScreen(vm) }
            composable(Tab.SETTINGS.route) {
                SettingsHubScreen(
                    onPick = { key -> nav.navigate("settings/$key") },
                    onLogout = onLogout,
                )
            }
            composable("settings/{key}") { entry ->
                when (entry.arguments?.getString("key")) {
                    "encode"   -> EncodingScreen(vm, onBack = { nav.popBackStack() })
                    "device"   -> DeviceInfoScreen(vm, onBack = { nav.popBackStack() })
                    "general"  -> GeneralScreen(vm, onBack = { nav.popBackStack() })
                    "network"  -> NetworkScreen(vm, onBack = { nav.popBackStack() })
                    "smtp"     -> SmtpScreen(vm, onBack = { nav.popBackStack() })
                    "wifi"     -> WifiScreen(vm, onBack = { nav.popBackStack() })
                    "time"     -> TimeScreen(vm, onBack = { nav.popBackStack() })
                    "maint"    -> MaintenanceScreen(vm, onBack = { nav.popBackStack() })
                    "password" -> PasswordScreen(vm, onBack = { nav.popBackStack() })
                    "color"    -> ImageColorScreen(vm, onBack = { nav.popBackStack() })
                    else -> SettingsHubScreen(
                        onPick = { key -> nav.navigate("settings/$key") },
                        onLogout = onLogout,
                    )
                }
            }
        }
    }
}

@Composable
private fun BottomTabBar(nav: NavController) {
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination
    NavigationBar {
        Tab.values().forEach { tab ->
            val selected = current?.hierarchy?.any { it.route == tab.route } == true
            NavigationBarItem(
                selected = selected,
                onClick = {
                    nav.navigate(tab.route) {
                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(tab.icon, contentDescription = tab.label) },
                label = { Text(tab.label) },
            )
        }
    }
}
