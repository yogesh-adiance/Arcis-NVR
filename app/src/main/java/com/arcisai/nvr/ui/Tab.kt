package com.arcisai.nvr.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.ui.graphics.vector.ImageVector

enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    LIVE    ("tab/live",    "Live",     Icons.Default.Videocam),
    PLAYBACK("tab/playback","Playback", Icons.Default.History),
    MANAGE  ("tab/manage",  "Cameras",  Icons.Default.Cameraswitch),
    SETTINGS("tab/settings","Settings", Icons.Default.Settings),
}
