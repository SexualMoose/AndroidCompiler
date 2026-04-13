package com.androidcompiler.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.androidcompiler.feature.compiler.ui.CompilerScreen
import com.androidcompiler.feature.components.ui.ComponentsScreen
import com.androidcompiler.feature.monitor.ui.MonitorScreen
import com.androidcompiler.feature.settings.ui.SettingsScreen
import kotlinx.serialization.Serializable

@Serializable data object CompilerRoute
@Serializable data object MonitorRoute
@Serializable data object ComponentsRoute
@Serializable data object SettingsRoute

data class TopLevelDestination(
    val route: Any,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val label: String
)

val topLevelDestinations = listOf(
    TopLevelDestination(CompilerRoute, Icons.Filled.Build, Icons.Outlined.Build, "Compiler"),
    TopLevelDestination(MonitorRoute, Icons.Filled.Dashboard, Icons.Outlined.Dashboard, "Monitor"),
    TopLevelDestination(ComponentsRoute, Icons.Filled.Extension, Icons.Outlined.Extension, "Components"),
    TopLevelDestination(SettingsRoute, Icons.Filled.Settings, Icons.Outlined.Settings, "Settings")
)

@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    Scaffold(
        bottomBar = {
            NavigationBar {
                topLevelDestinations.forEach { destination ->
                    val selected = currentDestination?.hierarchy?.any {
                        it.hasRoute(destination.route::class)
                    } == true

                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = if (selected) destination.selectedIcon else destination.unselectedIcon,
                                contentDescription = destination.label
                            )
                        },
                        label = { Text(destination.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = CompilerRoute,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable<CompilerRoute> { CompilerScreen() }
            composable<MonitorRoute> { MonitorScreen() }
            composable<ComponentsRoute> { ComponentsScreen() }
            composable<SettingsRoute> { SettingsScreen() }
        }
    }
}
