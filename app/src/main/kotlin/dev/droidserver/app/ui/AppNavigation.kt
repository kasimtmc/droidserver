package dev.droidserver.app.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.droidserver.app.AppGraph
import dev.droidserver.app.ui.components.ComponentsScreen
import dev.droidserver.app.ui.config.ApiExplorerScreen
import dev.droidserver.app.ui.config.AutomationsScreen
import dev.droidserver.app.ui.config.BackupsScreen
import dev.droidserver.app.ui.config.ProfilesScreen
import dev.droidserver.app.ui.config.TransferScreen
import dev.droidserver.app.ui.config.WebhooksScreen
import dev.droidserver.app.ui.config.HistoryScreen
import dev.droidserver.app.ui.config.JsonEditorScreen
import dev.droidserver.app.ui.config.AlertsScreen
import dev.droidserver.app.ui.config.AuditScreen
import dev.droidserver.app.ui.config.FirewallScreen
import dev.droidserver.app.ui.config.JobsScreen
import dev.droidserver.app.ui.config.AppsScreen
import dev.droidserver.app.ui.config.EnvScreen
import dev.droidserver.app.ui.config.AiScreen
import dev.droidserver.app.ui.config.DriveScreen
import dev.droidserver.app.ui.config.FormsScreen
import dev.droidserver.app.ui.config.ScriptsScreen
import dev.droidserver.app.ui.config.ServerScreen
import dev.droidserver.app.ui.config.CliScreen
import dev.droidserver.app.ui.config.MqttScreen
import dev.droidserver.app.ui.config.TeamsScreen
import dev.droidserver.app.ui.config.UsersScreen
import dev.droidserver.app.ui.status.ConnectScreen
import dev.droidserver.app.ui.logs.LogsScreen
import dev.droidserver.app.ui.status.StatusScreen
import dev.droidserver.app.ui.status.TroubleshootScreen
import dev.droidserver.app.ui.wizard.WizardScreen
import dev.droidserver.app.ui.wizard.WizardStep
import dev.droidserver.app.ui.wizard.WizardViewModel
import dev.droidserver.core.diagnostics.Fix
import dev.droidserver.core.i18n.tr

object Routes {
    const val STATUS = "status"
    const val SERVER = "server"
    const val COMPONENTS = "components"
    const val LOGS = "logs"
    const val WIZARD = "wizard"
    const val JSON = "json"
    const val HISTORY = "history"
    const val API = "api"
    const val USERS = "users"
    const val TEAMS = "teams"
    const val CLI = "cli"
    const val MQTT = "mqtt"
    const val SCRIPTS = "scripts"
    const val BACKUPS = "backups"
    const val WEBHOOKS = "webhooks"
    const val TRANSFER = "transfer"
    const val PROFILES = "profiles"
    const val AUDIT = "audit"
    const val FIREWALL = "firewall"
    const val ALERTS = "alerts"
    const val JOBS = "jobs"
    const val CONNECT = "connect"
    const val TROUBLESHOOT = "troubleshoot"
    const val ENV = "env"
    const val AUTOMATIONS = "automations"
    const val FORMS = "forms"
    const val AI = "ai"
    const val DRIVE = "drive"
    const val APPS = "apps"
    const val SETTINGS = "settings"
}

private data class TopLevel(val route: String, val label: String, val icon: ImageVector)

private val topLevel = listOf(
    TopLevel(Routes.STATUS, tr("Durum"), Icons.Default.Dashboard),
    TopLevel(Routes.SERVER, tr("Sunucu"), Icons.Default.Dns),
    TopLevel(Routes.COMPONENTS, tr("Bileşenler"), Icons.Default.Extension),
    TopLevel(Routes.LOGS, tr("Loglar"), Icons.Default.Terminal),
)

private fun NavController.navigateTopLevel(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

/**
 * Alt çubuk: Durum, Sunucu, Bileşenler, Loglar. Sihirbaz, JSON düzenleyici, Geçmiş ve API gezgini tam ekran.
 * Sihirbaz ve JSON düzenleyici aynı [WizardViewModel] örneğini (aynı taslağı) paylaşır.
 */
@Composable
fun AppNavigation(g: AppGraph) {
    val nav = rememberNavController()
    val wizard: WizardViewModel = viewModel(factory = WizardViewModel.factory(g))
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route

    // İlk açılışta konfigürasyon yoksa doğrudan sihirbaz (döndürmede tekrar açılmasın).
    var firstRunHandled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!firstRunHandled) {
            firstRunHandled = true
            if (!g.configRepo.hasConfig()) nav.navigate(Routes.WIZARD)
        }
    }

    val back: () -> Unit = { nav.popBackStack() }
    val toLogs: () -> Unit = { nav.navigateTopLevel(Routes.LOGS) }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (topLevel.any { it.route == route }) {
                NavigationBar {
                    topLevel.forEach { item ->
                        NavigationBarItem(
                            selected = route == item.route,
                            onClick = { nav.navigateTopLevel(item.route) },
                            icon = { Icon(item.icon, contentDescription = null) },
                            label = { Text(item.label) },
                        )
                    }
                }
            }
        },
    ) { pad ->
        NavHost(nav, startDestination = Routes.STATUS, modifier = Modifier.padding(pad).consumeWindowInsets(pad)) {
            composable(Routes.STATUS) {
                StatusScreen(
                    g, wizard,
                    onStartWizard = { nav.navigate(Routes.WIZARD) },
                    onConnect = { nav.navigate(Routes.CONNECT) },
                    onTroubleshoot = { nav.navigate(Routes.TROUBLESHOOT) },
                    onSettings = { nav.navigate(Routes.SETTINGS) },
                    onOpen = { r -> if (r == Routes.COMPONENTS) nav.navigateTopLevel(r) else nav.navigate(r) },
                )
            }
            composable(Routes.SERVER) {
                ServerScreen(
                    g, wizard,
                    onWizard = { nav.navigate(Routes.WIZARD) },
                    onJson = { nav.navigate(Routes.JSON) },
                    onHistory = { nav.navigate(Routes.HISTORY) },
                    onApi = { nav.navigate(Routes.API) },
                    onUsers = { nav.navigate(Routes.USERS) },
                    onScripts = { nav.navigate(Routes.SCRIPTS) },
                    onBackups = { nav.navigate(Routes.BACKUPS) },
                    onWebhooks = { nav.navigate(Routes.WEBHOOKS) },
                    onTransfer = { nav.navigate(Routes.TRANSFER) },
                    onProfiles = { nav.navigate(Routes.PROFILES) },
                    onOpen = { nav.navigate(it) },
                )
            }
            composable(Routes.COMPONENTS) { ComponentsScreen(g, wizard) }
            composable(Routes.LOGS) { LogsScreen(g) }
            composable(Routes.WIZARD) {
                WizardScreen(
                    g, wizard, onClose = back, onDeployed = toLogs,
                    onAdvanced = { nav.navigate(Routes.JSON) { popUpTo(Routes.WIZARD) { inclusive = true } } },
                )
            }
            composable(Routes.JSON) {
                JsonEditorScreen(
                    g, wizard, onBack = back, onDeployed = toLogs,
                    onWizard = { nav.navigate(Routes.WIZARD) { popUpTo(Routes.JSON) { inclusive = true } } },
                )
            }
            composable(Routes.HISTORY) { HistoryScreen(g, wizard, onBack = back, onDeployed = toLogs) }
            composable(Routes.API) { ApiExplorerScreen(g, onBack = back) }
            composable(Routes.USERS) { UsersScreen(g, onBack = back) }
            composable(Routes.TEAMS) { TeamsScreen(g, onBack = back) }
            composable(Routes.CLI) { CliScreen(g, wizard.active.collectAsState().value, onBack = back) }
            composable(Routes.SCRIPTS) { ScriptsScreen(g, onBack = back) }
            composable(Routes.BACKUPS) { BackupsScreen(g, onBack = back) }
            composable(Routes.WEBHOOKS) { WebhooksScreen(g, onBack = back) }
            composable(Routes.TRANSFER) { TransferScreen(g, onBack = back) }
            composable(Routes.PROFILES) { ProfilesScreen(g, onBack = back) }
            composable(Routes.AUDIT) { AuditScreen(g, onBack = back) }
            composable(Routes.FIREWALL) { FirewallScreen(g, onBack = back) }
            composable(Routes.ALERTS) { AlertsScreen(g, onBack = back) }
            composable(Routes.JOBS) { JobsScreen(g, onBack = back) }
            composable(Routes.SETTINGS) { dev.droidserver.app.ui.status.SettingsScreen(g, onBack = back) }
            composable(Routes.ENV) { EnvScreen(g, wizard, onBack = back) }
            composable(Routes.AUTOMATIONS) { AutomationsScreen(g, wizard, onBack = back) }
            composable(Routes.FORMS) { FormsScreen(g, wizard, onBack = back) }
            composable(Routes.AI) { AiScreen(g, wizard, onBack = back) }
            composable(Routes.DRIVE) { DriveScreen(g, wizard, onBack = back) }
            composable(Routes.MQTT) { MqttScreen(g, wizard, onBack = back) }
            composable(Routes.APPS) { AppsScreen(g, wizard, onBack = back, onLogs = toLogs, onComponents = { nav.navigateTopLevel(Routes.COMPONENTS) }) }
            composable(Routes.CONNECT) { ConnectScreen(g, onBack = back) }
            composable(Routes.TROUBLESHOOT) {
                TroubleshootScreen(g, onBack = back) { fix ->
                    when (fix) {
                        Fix.OPEN_WIZARD -> nav.navigate(Routes.WIZARD)
                        Fix.OPEN_ACCESS -> { wizard.goTo(WizardStep.ACCESS); nav.navigate(Routes.WIZARD) }
                        Fix.OPEN_COMPONENTS -> nav.navigateTopLevel(Routes.COMPONENTS)
                        Fix.OPEN_LOGS -> toLogs()
                        else -> {}
                    }
                }
            }
        }
    }
}
