package com.bambu.nfc.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.bambu.nfc.ui.detail.SpoolDetailScreen
import com.bambu.nfc.ui.detail.SpoolDetailViewModel
import com.bambu.nfc.ui.inventory.InventoryScreen
import com.bambu.nfc.ui.qr.QrScannerScreen
import com.bambu.nfc.ui.inventory.InventoryViewModel
import com.bambu.nfc.ui.scan.ScanScreen
import com.bambu.nfc.ui.scan.ScanViewModel
import com.bambu.nfc.ui.settings.SettingsScreen

sealed class Screen(val route: String, val label: String) {
    data object Scan : Screen("scan", "Scan")
    data object Inventory : Screen("inventory", "Inventory")
    data object Settings : Screen("settings", "Settings")
    data object Detail : Screen("detail/{spoolId}", "Detail") {
        fun createRoute(spoolId: Long) = "detail/$spoolId"
    }
}

@Composable
fun AppNavGraph(scanViewModel: ScanViewModel) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val bottomBarScreens = listOf(Screen.Scan, Screen.Inventory, Screen.Settings)
    val showBottomBar = currentDestination?.hierarchy?.any { dest -> bottomBarScreens.any { it.route == dest.route } } == true

    // A tag tap, QR code or App Link shows its result on the Scan tab, whichever tab is open.
    LaunchedEffect(scanViewModel) {
        scanViewModel.showScan.collect {
            if (navController.currentDestination?.route != Screen.Scan.route) {
                navController.navigate(Screen.Scan.route) {
                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                }
            }
        }
    }
    val qrCamera by scanViewModel.qrCamera.collectAsState()

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomBarScreens.forEach { screen ->
                        NavigationBarItem(
                            icon = {
                                Icon(
                                    imageVector = when (screen) {
                                        Screen.Scan -> Icons.Default.Nfc
                                        Screen.Settings -> Icons.Default.Settings
                                        else -> Icons.Default.Inventory2
                                    },
                                    contentDescription = screen.label
                                )
                            },
                            label = { Text(screen.label) },
                            selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Scan.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Scan.route) { ScanScreen(viewModel = scanViewModel) }
            composable(Screen.Inventory.route) {
                InventoryScreen(
                    viewModel = hiltViewModel<InventoryViewModel>(),
                    onSpoolClick = { navController.navigate(Screen.Detail.createRoute(it)) }
                )
            }
            composable(Screen.Settings.route) { SettingsScreen(viewModel = hiltViewModel()) }
            composable(
                route = Screen.Detail.route,
                arguments = listOf(navArgument("spoolId") { type = NavType.LongType })
            ) {
                SpoolDetailScreen(
                    viewModel = hiltViewModel<SpoolDetailViewModel>(),
                    onBack = { navController.popBackStack() },
                    onWriteSticker = scanViewModel::armWrite,
                )
            }
        }
    }

    // Drawn last so it covers the bottom bar too.
    qrCamera?.let { QrScannerScreen(state = it, onClose = scanViewModel::closeQrScanner) }

    WriteStickerDialog(scanViewModel)
}

/** Shown on any screen while a sticker write is armed; the next tag tap performs the write. */
@Composable
private fun WriteStickerDialog(scanViewModel: ScanViewModel) {
    val write by scanViewModel.writeState.collectAsState()
    val w = write ?: return
    AlertDialog(
        onDismissRequest = { if (w !is ScanViewModel.WriteState.Writing) scanViewModel.cancelWrite() },
        title = { Text("Write NFC sticker for spool #${w.spool.id}") },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                when (w) {
                    is ScanViewModel.WriteState.Armed -> Text("Hold the phone against the NTAG215 sticker now.")
                    is ScanViewModel.WriteState.Writing -> { CircularProgressIndicator(); Text("Writing…") }
                    is ScanViewModel.WriteState.Written -> Text("Done. The sticker now holds ${w.uri}.")
                    is ScanViewModel.WriteState.Failed -> Text(
                        "${w.message}\n\nTap the sticker again to retry.",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            if (w is ScanViewModel.WriteState.Written) TextButton(onClick = scanViewModel::cancelWrite) { Text("Done") }
        },
        dismissButton = {
            if (w is ScanViewModel.WriteState.Armed || w is ScanViewModel.WriteState.Failed) {
                TextButton(onClick = scanViewModel::cancelWrite) { Text("Cancel") }
            }
        },
    )
}
