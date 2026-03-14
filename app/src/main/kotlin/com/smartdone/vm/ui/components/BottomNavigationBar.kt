package com.smartdone.vm.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddBox
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.smartdone.vm.ui.navigation.Destination

@Composable
fun BottomNavigationBar(
    currentDestination: String?,
    onNavigate: (Destination) -> Unit
) {
    val items = listOf(
        Destination.Home to Icons.Outlined.Home,
        Destination.Install to Icons.Outlined.AddBox,
        Destination.Running to Icons.Outlined.PlayCircle,
        Destination.Settings to Icons.Outlined.Settings
    )
    NavigationBar {
        items.forEach { (destination, icon) ->
            NavigationBarItem(
                selected = destination.matchesRoute(currentDestination),
                onClick = { onNavigate(destination) },
                icon = { Icon(icon, contentDescription = null) },
                label = { Text(destination.label) }
            )
        }
    }
}

private fun Destination.matchesRoute(route: String?): Boolean =
    when (this) {
        Destination.Install -> route == Destination.Install.pattern || route?.startsWith("install/") == true
        else -> route == this.route
    }

private val Destination.label: String
    get() = when (this) {
        Destination.Home -> "Home"
        Destination.Install -> "Install"
        Destination.Running -> "Running"
        Destination.Settings -> "Settings"
        Destination.Detail -> "Detail"
    }
