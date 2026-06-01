package com.arcisai.nvr

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.arcisai.nvr.ui.Tab
import com.arcisai.nvr.ui.screens.ColorScreen
import com.arcisai.nvr.ui.screens.DeviceInfoScreen
import com.arcisai.nvr.ui.screens.EncodingScreen
import com.arcisai.nvr.ui.screens.EventScreen
import com.arcisai.nvr.ui.screens.GeneralScreen
import com.arcisai.nvr.ui.screens.LiveScreen
import com.arcisai.nvr.ui.screens.LiveTabScreen
import com.arcisai.nvr.ui.screens.LoginScreen
import com.arcisai.nvr.ui.screens.LogScreen
import com.arcisai.nvr.ui.screens.MaintenanceScreen
import com.arcisai.nvr.ui.screens.ManageScreen
import com.arcisai.nvr.ui.screens.NetworkScreen
import com.arcisai.nvr.ui.screens.OsdScreen
import com.arcisai.nvr.ui.screens.PasswordScreen
import com.arcisai.nvr.ui.screens.PlaybackTabScreen
import com.arcisai.nvr.ui.screens.PppoeScreen
import com.arcisai.nvr.ui.screens.RecordScreen
import com.arcisai.nvr.ui.screens.SettingsHubScreen
import com.arcisai.nvr.ui.screens.SmtpScreen
import com.arcisai.nvr.ui.screens.StorageScreen
import com.arcisai.nvr.ui.screens.TimeScreen
import com.arcisai.nvr.ui.screens.UsersScreen
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
                    val startRoute = if (viewModel.credentials != null) "main" else "login"
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
            composable(Tab.PLAYBACK.route) { PlaybackTabScreen(vm) }
            composable(Tab.MANAGE.route)   { ManageScreen(vm) }
            composable(Tab.SETTINGS.route) {
                SettingsHubScreen(
                    onPick = { key ->
                        // PTZ control lives on the per-channel Live screen, so the
                        // PTZ settings row just jumps to the Live tab (pick a
                        // channel there to get the PTZ pad).
                        if (key == "ptz") nav.navigate(Tab.LIVE.route) { launchSingleTop = true }
                        else nav.navigate("settings/$key")
                    },
                    onLogout = onLogout,
                )
            }
            composable("settings/{key}") { entry ->
                val back = { nav.popBackStack(); Unit }
                when (entry.arguments?.getString("key")) {
                    "encode"   -> EncodingScreen(vm, onBack = back)
                    "color"    -> ColorScreen(vm, onBack = back)
                    "osd"      -> OsdScreen(vm, onBack = back)
                    "device"   -> DeviceInfoScreen(vm, onBack = back)
                    "general"  -> GeneralScreen(vm, onBack = back)
                    "network"  -> NetworkScreen(vm, onBack = back)
                    "wifi"     -> WifiScreen(vm, onBack = back)
                    "smtp"     -> SmtpScreen(vm, onBack = back)
                    "pppoe"    -> PppoeScreen(vm, onBack = back)
                    "event"    -> EventScreen(vm, onBack = back)
                    "record"   -> RecordScreen(vm, onBack = back)
                    "time"     -> TimeScreen(vm, onBack = back)
                    "users"    -> UsersScreen(vm, onBack = back)
                    "password" -> PasswordScreen(vm, onBack = back)
                    "disk"     -> StorageScreen(vm, onBack = back)
                    "log"      -> LogScreen(vm, onBack = back)
                    "maint"    -> MaintenanceScreen(vm, onBack = back)
                    else -> ComingSoonScreen(
                        key = entry.arguments?.getString("key") ?: "",
                        onBack = back,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComingSoonScreen(key: String, onBack: () -> Unit) {
    val title = when (key) {
        "color" -> "Image / Color"; "osd" -> "OSD"; "ptz" -> "PTZ"
        "wifi" -> "Wi-Fi"; "smtp" -> "Email (SMTP)"; "pppoe" -> "PPPoE"
        "event" -> "Alarms / Events"; "record" -> "Record schedule"; "log" -> "Logs"
        else -> "Settings"
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        androidx.compose.foundation.layout.Box(
            modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            Text(
                "“$title” isn't built yet — coming in the next settings pass.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
