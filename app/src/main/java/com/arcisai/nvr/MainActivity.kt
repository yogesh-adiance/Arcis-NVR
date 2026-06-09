package com.arcisai.nvr

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.arcisai.nvr.ui.screens.DiskScreen
import com.arcisai.nvr.ui.screens.EncodingScreen
import com.arcisai.nvr.ui.screens.GeneralScreen
import com.arcisai.nvr.ui.screens.ImageColorScreen
import com.arcisai.nvr.ui.screens.LiveScreen
import com.arcisai.nvr.ui.screens.LiveTabScreen
import com.arcisai.nvr.ui.screens.LogsScreen
import com.arcisai.nvr.ui.screens.LoginScreen
import com.arcisai.nvr.ui.screens.MaintenanceScreen
import com.arcisai.nvr.ui.screens.ManageScreen
import com.arcisai.nvr.ui.screens.MyNvrsScreen
import com.arcisai.nvr.ui.screens.NetworkScreen
import com.arcisai.nvr.ui.screens.OsdScreen
import com.arcisai.nvr.ui.screens.PasswordScreen
import com.arcisai.nvr.ui.screens.PppoeScreen
import com.arcisai.nvr.ui.screens.UsersScreen
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
                    // Always open on the login page. We deliberately do NOT
                    // auto-restore the NVR session or auto-resume the cloud
                    // account on cold start — the user signs in each launch
                    // (requested behaviour). Within a running session, login()
                    // navigates onward normally.
                    val startRoute = "login"
                    NavHost(navController = rootNav, startDestination = startRoute) {
                        composable("login") {
                            LoginScreen(
                                vm = viewModel,
                                onLanConnected = {
                                    rootNav.navigate("main") {
                                        popUpTo("login") { inclusive = true }
                                    }
                                },
                                onCloudAuthenticated = {
                                    rootNav.navigate("my_nvrs") {
                                        popUpTo("login") { inclusive = true }
                                    }
                                },
                            )
                        }
                        composable("my_nvrs") {
                            MyNvrsScreen(
                                vm = viewModel,
                                onNvrSelected = {
                                    rootNav.navigate("main") {
                                        popUpTo("my_nvrs") { inclusive = true }
                                    }
                                },
                                onLogout = {
                                    viewModel.logout()
                                    rootNav.navigate("login") {
                                        popUpTo("my_nvrs") { inclusive = true }
                                    }
                                },
                            )
                        }
                        composable("main") {
                            MainScaffold(
                                vm = viewModel,
                                onLogout = {
                                    viewModel.logout()
                                    rootNav.navigate("login") {
                                        popUpTo("main") { inclusive = true }
                                    }
                                },
                                onSwitchNvr = if (viewModel.accountSignedIn) {
                                    {
                                        viewModel.releaseSelectedNvr()
                                        rootNav.navigate("my_nvrs") {
                                            popUpTo("main") { inclusive = true }
                                        }
                                    }
                                } else null,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MainScaffold(
    vm: NvrViewModel,
    onLogout: () -> Unit,
    onSwitchNvr: (() -> Unit)?,
) {
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
            composable(Tab.PLAYBACK.route) { PlaybackTabScreen(vm) }
            composable(Tab.MANAGE.route)   { ManageScreen(vm) }
            composable(Tab.SETTINGS.route) {
                SettingsHubScreen(
                    onPick = { key -> nav.navigate("settings/$key") },
                    onLogout = onLogout,
                    onSwitchNvr = onSwitchNvr,
                    currentNvrName = vm.credentials?.accountAbdName?.ifBlank { vm.credentials?.deviceId },
                    accountEmail = vm.accountEmail,
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
                    "osd"      -> OsdScreen(vm, onBack = { nav.popBackStack() })
                    "pppoe"    -> PppoeScreen(vm, onBack = { nav.popBackStack() })
                    "disk"     -> DiskScreen(vm, onBack = { nav.popBackStack() })
                    "users"    -> UsersScreen(vm, onBack = { nav.popBackStack() })
                    "log"      -> LogsScreen(vm, onBack = { nav.popBackStack() })
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
