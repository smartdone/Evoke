package com.smartdone.vm.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.NavHostController
import androidx.navigation.NavDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.smartdone.vm.ui.components.BottomNavigationBar
import com.smartdone.vm.ui.detail.AppDetailScreen
import com.smartdone.vm.ui.home.HomeScreen
import com.smartdone.vm.ui.install.InstallScreen
import com.smartdone.vm.ui.running.RunningAppsScreen
import com.smartdone.vm.ui.settings.SettingsScreen

@Composable
fun VmApp(startDestination: String = Destination.Home.route) {
    val navController = rememberNavController()
    val backStack = navController.currentBackStackEntryAsState()
    val currentDestination = backStack.value?.destination

    Scaffold(
        bottomBar = {
            BottomNavigationBar(
                currentDestination = currentDestination?.route,
                onNavigate = { destination ->
                    navController.navigateToTopLevel(destination)
                }
            )
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(padding)
        ) {
            composable(Destination.Home.route) {
                HomeScreen(
                    onAddAppClick = { tab -> navController.navigateToInstall(tab) },
                    onAppClick = { pkgName -> navController.navigate(Destination.detailRoute(pkgName)) }
                )
            }
            composable(
                route = Destination.Install.pattern,
                arguments = listOf(
                    navArgument(Destination.Install.argument) {
                        type = NavType.IntType
                        defaultValue = 0
                    }
                )
            ) { backStackEntry ->
                InstallScreen(initialTab = backStackEntry.arguments?.getInt(Destination.Install.argument) ?: 0)
            }
            composable(Destination.Running.route) {
                RunningAppsScreen()
            }
            composable(Destination.Settings.route) {
                SettingsScreen()
            }
            composable(Destination.Detail.pattern) {
                AppDetailScreen(
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}

private fun NavHostController.navigateToTopLevel(destination: Destination) {
    if (destination.matches(currentDestination)) {
        return
    }
    if (popBackStack(destination.route, inclusive = false) && destination.matches(currentDestination)) {
        return
    }
    navigate(destination.route) {
        launchSingleTop = true
        restoreState = true
        popUpTo(graph.startDestinationId) {
            saveState = true
        }
    }
}

private fun NavHostController.navigateToInstall(tab: Int) {
    val route = Destination.installRoute(tab)
    if (currentDestination?.route == Destination.Install.pattern) {
        navigate(route) {
            launchSingleTop = true
            restoreState = false
        }
        return
    }
    navigate(route) {
        launchSingleTop = true
        restoreState = true
        popUpTo(graph.startDestinationId) {
            saveState = true
        }
    }
}

sealed class Destination(val route: String) {
    data object Home : Destination("home")
    data object Install : Destination("install/0") {
        const val argument = "tab"
        const val pattern = "install/{tab}"
    }
    data object Running : Destination("running")
    data object Settings : Destination("settings")
    data object Detail : Destination("detail/{packageName}") {
        const val argument = "packageName"
        const val pattern = "detail/{packageName}"
    }

    companion object {
        fun detailRoute(packageName: String) = "detail/$packageName"
        fun installRoute(tab: Int) = "install/$tab"
    }
}

private fun Destination.matches(destination: NavDestination?): Boolean {
    val route = destination?.route ?: return false
    return when (this) {
        Destination.Install -> route == Destination.Install.pattern || route.startsWith("install/")
        else -> route == this.route
    }
}
