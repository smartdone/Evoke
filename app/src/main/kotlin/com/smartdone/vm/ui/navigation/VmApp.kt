package com.smartdone.vm.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
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
fun VmApp() {
    val navController = rememberNavController()
    val backStack = navController.currentBackStackEntryAsState()
    val currentRoute = backStack.value?.destination

    Scaffold(
        bottomBar = {
            BottomNavigationBar(
                currentDestination = currentRoute?.route,
                onNavigate = { destination ->
                    navController.navigate(destination.route) {
                        launchSingleTop = true
                        restoreState = true
                        popUpTo(navController.graph.startDestinationId) {
                            saveState = true
                        }
                    }
                }
            )
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Destination.Home.route,
            modifier = Modifier.padding(padding)
        ) {
            composable(Destination.Home.route) {
                HomeScreen(
                    onAddAppClick = { tab -> navController.navigate(Destination.installRoute(tab)) },
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
