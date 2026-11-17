package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.R
import com.example.ui.components.PsiphonIntroOverlay
import com.example.ui.screens.CloudflareDiagScreen
import com.example.ui.screens.GeoIpTableScreen
import com.example.ui.screens.SavedIpsScreen
import com.example.ui.screens.ScanScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.EmeraldContainer
import com.example.ui.theme.EmeraldShield
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.PsiphonWarmTop
import kotlinx.serialization.Serializable

@Serializable object ScanRoute
@Serializable object SavedIpsRoute
@Serializable object GeoIpRoute
@Serializable object CfDiagRoute
@Serializable object SettingsRoute

private data class NavDestinationItem(
    val route: Any,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IpFounderApp(viewModel: ScannerViewModel) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    var showIntro by rememberSaveable { mutableStateOf(true) }

    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val savedIps by viewModel.savedCleanIps.collectAsStateWithLifecycle()
    val progress by viewModel.scanProgress.collectAsStateWithLifecycle()
    val liveResults by viewModel.liveResults.collectAsStateWithLifecycle()
    val aiSummary by viewModel.aiSummary.collectAsStateWithLifecycle()
    val importedServerEntries by viewModel.importedServerEntries.collectAsStateWithLifecycle()
    val cfDiagState by viewModel.cfDiagState.collectAsStateWithLifecycle()
    val adaptiveDecision by viewModel.adaptiveDecision.collectAsStateWithLifecycle()

    val selectedEntity = remember(savedIps, settings.selectedIpKey) {
        savedIps.firstOrNull { it.idKey == settings.selectedIpKey }
    }

    LaunchedEffect(Unit) {
        viewModel.snackbarEvents.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    val navItems = remember {
        listOf(
            NavDestinationItem(
                route = ScanRoute,
                labelRes = R.string.nav_scan,
                selectedIcon = Icons.Filled.Radar,
                unselectedIcon = Icons.Outlined.Radar,
                testTag = "nav_tab_scanner"
            ),
            NavDestinationItem(
                route = SavedIpsRoute,
                labelRes = R.string.nav_saved,
                selectedIcon = Icons.Filled.Bookmark,
                unselectedIcon = Icons.Outlined.BookmarkBorder,
                testTag = "nav_tab_saved"
            ),
            NavDestinationItem(
                route = GeoIpRoute,
                labelRes = R.string.nav_geoip,
                selectedIcon = Icons.Filled.Public,
                unselectedIcon = Icons.Outlined.Public,
                testTag = "nav_tab_geoip"
            ),
            NavDestinationItem(
                route = CfDiagRoute,
                labelRes = R.string.nav_cf_diag,
                selectedIcon = Icons.Filled.ShowChart,
                unselectedIcon = Icons.Outlined.ShowChart,
                testTag = "nav_tab_cf_diag"
            ),
            NavDestinationItem(
                route = SettingsRoute,
                labelRes = R.string.nav_settings,
                selectedIcon = Icons.Filled.Settings,
                unselectedIcon = Icons.Outlined.Settings,
                testTag = "nav_tab_settings"
            )
        )
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    fun navigateTo(route: Any) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(PsiphonWarmTop, ObsidianBg, ObsidianBg)
                )
            )
    ) {
        val useNavRail = maxWidth >= 600.dp

        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.app_name),
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                            )
                            if (progress.isScanning) {
                                Surface(
                                    color = EmeraldContainer,
                                    shape = MaterialTheme.shapes.small,
                                    modifier = Modifier.padding(start = 10.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.status_scanning_badge),
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = EmeraldShield,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = Color.Transparent
                    )
                )
            },
            bottomBar = {
                if (!useNavRail) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        navItems.forEach { item ->
                            val selected = currentDestination?.hasRoute(item.route::class) == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateTo(item.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = stringResource(item.labelRes)
                                    )
                                },
                                label = { Text(stringResource(item.labelRes)) },
                                modifier = Modifier.testTag(item.testTag)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useNavRail) {
                    NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
                        navItems.forEach { item ->
                            val selected = currentDestination?.hasRoute(item.route::class) == true
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateTo(item.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = stringResource(item.labelRes)
                                    )
                                },
                                label = { Text(stringResource(item.labelRes)) },
                                modifier = Modifier.testTag(item.testTag)
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    NavHost(
                        navController = navController,
                        startDestination = ScanRoute,
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 840.dp)
                    ) {
                        composable<ScanRoute> {
                            ScanScreen(
                                settings = settings,
                                progress = progress,
                                liveResults = liveResults,
                                aiSummary = aiSummary,
                                adaptiveDecision = adaptiveDecision,
                                selectedEntity = selectedEntity,
                                onToggleProvider = viewModel::toggleProvider,
                                onToggleAllCdns = viewModel::toggleAllRealScanCdns,
                                onToggleAllProxies = viewModel::toggleAllProxySources,
                                onSelectMethod = viewModel::setMethod,
                                onSelectFreeProxyCountry = viewModel::setFreeProxyCountry,
                                onSelectFreeProxyPort = viewModel::setFreeProxyPortFilter,
                                onToggleIpv4 = viewModel::toggleIpv4,
                                onToggleIpv6 = viewModel::toggleIpv6,
                                onToggleHttp = viewModel::toggleHttp,
                                onToggleSocks5 = viewModel::toggleSocks5,
                                onToggleAutoRouting = viewModel::toggleAutoRouting,
                                onEvaluateAdaptiveRoute = { viewModel.evaluateAdaptiveRouteNow(emitFeedback = true) },
                                onUpdateSettings = viewModel::updateSettings,
                                onStartScan = viewModel::startScan,
                                onStopScan = viewModel::stopScan,
                                onResetToAuto = viewModel::resetSelectedIp,
                                onSelectIpKey = viewModel::selectCleanIp,
                                onCopyFeedback = viewModel::notifyMessage
                            )
                        }
                        composable<SavedIpsRoute> {
                            SavedIpsScreen(
                                savedIps = savedIps,
                                selectedIpKey = settings.selectedIpKey,
                                isScanning = progress.isScanning,
                                onSelectIp = viewModel::selectCleanIp,
                                onRescanIp = viewModel::rescanSingleEntity,
                                onRescanAll = viewModel::rescanAllSaved,
                                onRemoveFailed = viewModel::removeFailedIps,
                                onDeleteIp = viewModel::deleteCleanIp,
                                onClearAll = viewModel::clearAllSaved,
                                onCopyFeedback = viewModel::notifyMessage,
                                onExportEncryptedBackup = viewModel::exportEncryptedBackupToUri,
                                onValidateBackupFile = viewModel::readAndValidateBackupFile,
                                onImportEncryptedBackup = viewModel::importEncryptedBackupFromUri
                            )
                        }
                        composable<GeoIpRoute> {
                            GeoIpTableScreen(
                                liveResults = liveResults,
                                savedIps = savedIps,
                                selectedIpKey = settings.selectedIpKey,
                                onSelectIp = viewModel::selectCleanIp,
                                onCopyFeedback = viewModel::notifyMessage
                            )
                        }
                        composable<CfDiagRoute> {
                            CloudflareDiagScreen(
                                state = cfDiagState,
                                adaptiveDecision = adaptiveDecision,
                                autoRoutingEnabled = settings.autoRoutingEnabled,
                                onToggleNode = viewModel::toggleCfDiagNode,
                                onAddCustomEdge = viewModel::addCustomCfEdgeNode,
                                onSetInterval = viewModel::setCfDiagInterval,
                                onStartLive = viewModel::startCfDiagLive,
                                onStopLive = viewModel::stopCfDiagLive,
                                onSingleSweep = viewModel::runSingleCfDiagSweep,
                                onClearHistory = viewModel::clearCfDiagHistory,
                                onEvaluateAdaptiveRoute = { viewModel.evaluateAdaptiveRouteNow(emitFeedback = true) }
                            )
                        }
                        composable<SettingsRoute> {
                            SettingsScreen(
                                settings = settings,
                                importedServerCount = importedServerEntries.size,
                                savedProxyCount = savedIps.size,
                                onUpdateSettings = viewModel::updateSettings,
                                onImportServerEntries = viewModel::importServerEntriesText,
                                onExportEncryptedBackup = viewModel::exportEncryptedBackupToUri,
                                onValidateBackupFile = viewModel::readAndValidateBackupFile,
                                onImportEncryptedBackup = viewModel::importEncryptedBackupFromUri
                            )
                        }
                    }
                }
            }
        }

        PsiphonIntroOverlay(
            visible = showIntro,
            onFinished = { showIntro = false }
        )
    }
}
