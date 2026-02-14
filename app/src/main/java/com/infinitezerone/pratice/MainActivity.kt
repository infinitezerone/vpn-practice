package com.infinitezerone.pratice

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.infinitezerone.pratice.config.ProxyConfigValidator
import com.infinitezerone.pratice.config.ProxyProtocol
import com.infinitezerone.pratice.config.ProxySettingsStore
import com.infinitezerone.pratice.config.RoutingMode
import com.infinitezerone.pratice.vpn.AppVpnService
import com.infinitezerone.pratice.vpn.EndpointSanitizer
import com.infinitezerone.pratice.vpn.ProxyConnectivityChecker
import com.infinitezerone.pratice.vpn.RuntimeStatus
import com.infinitezerone.pratice.vpn.VpnRuntimeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                VpnHome()
            }
        }
    }
}

private data class InstalledApp(
    val packageName: String,
    val appLabel: String
)

private const val TAG_PROXY_HOST_INPUT = "proxy_host_input"
private const val TAG_PROXY_PORT_INPUT = "proxy_port_input"
private const val TAG_PROXY_BYPASS_INPUT = "proxy_bypass_input"
private const val TAG_PROTOCOL_SOCKS_BUTTON = "protocol_socks_button"
private const val TAG_PROTOCOL_HTTP_BUTTON = "protocol_http_button"
private const val TAG_START_BUTTON = "start_button"

@Composable
private fun VpnHome() {
    val context = LocalContext.current
    val settingsStore = remember(context) { ProxySettingsStore(context) }
    val installedApps = remember(context) { loadInstalledApps(context) }

    var hostInput by rememberSaveable { mutableStateOf(settingsStore.loadHost()) }
    var portInput by rememberSaveable { mutableStateOf(settingsStore.loadPortText()) }
    var proxyProtocol by remember { mutableStateOf(settingsStore.loadProxyProtocol()) }
    var proxyBypassRawInput by rememberSaveable { mutableStateOf(settingsStore.loadProxyBypassRawInput()) }
    var bypassPackages by remember { mutableStateOf(settingsStore.loadBypassPackages()) }
    var routingMode by remember { mutableStateOf(settingsStore.loadRoutingMode()) }
    var autoReconnect by remember { mutableStateOf(settingsStore.loadAutoReconnectEnabled()) }
    var showBypassDialog by remember { mutableStateOf(false) }
    var infoMessage by remember { mutableStateOf<String?>(null) }
    var isTestingConnection by remember { mutableStateOf(false) }
    val runtimeSnapshot by VpnRuntimeState.state.collectAsState()
    val uiScope = rememberCoroutineScope()

    val validationError = ProxyConfigValidator.validate(hostInput.trim(), portInput.trim())
    val configIsValid = validationError == null

    fun persistBaseSettings(host: String, port: Int) {
        settingsStore.save(host, port)
        settingsStore.saveProxyProtocol(proxyProtocol)
        settingsStore.saveRoutingMode(routingMode)
        settingsStore.saveAutoReconnectEnabled(autoReconnect)
        settingsStore.saveProxyBypassRawInput(proxyBypassRawInput)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val host = hostInput.trim()
            val port = portInput.trim().toIntOrNull()
            if (port == null) {
                VpnRuntimeState.setError("Failed to start VPN: invalid proxy settings.")
                return@rememberLauncherForActivityResult
            }

            persistBaseSettings(host, port)
            AppVpnService.start(context, host, port, proxyProtocol)
            infoMessage = null
        } else {
            VpnRuntimeState.setError("VPN permission denied.")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "VPN Controller",
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = hostInput,
            onValueChange = { hostInput = it },
            label = { Text("Proxy Host") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TAG_PROXY_HOST_INPUT)
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = portInput,
            onValueChange = { portInput = it },
            label = { Text("Proxy Port") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TAG_PROXY_PORT_INPUT)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Proxy protocol",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    proxyProtocol = ProxyProtocol.Socks5
                    settingsStore.saveProxyProtocol(proxyProtocol)
                    infoMessage = "Proxy protocol set to SOCKS5."
                },
                modifier = Modifier.testTag(TAG_PROTOCOL_SOCKS_BUTTON)
            ) {
                Text(if (proxyProtocol == ProxyProtocol.Socks5) "SOCKS5 (active)" else "SOCKS5")
            }
            OutlinedButton(
                onClick = {
                    proxyProtocol = ProxyProtocol.Http
                    settingsStore.saveProxyProtocol(proxyProtocol)
                    infoMessage = "Proxy protocol set to HTTP."
                },
                modifier = Modifier.testTag(TAG_PROTOCOL_HTTP_BUTTON)
            ) {
                Text(if (proxyProtocol == ProxyProtocol.Http) "HTTP (active)" else "HTTP")
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = proxyBypassRawInput,
            onValueChange = { proxyBypassRawInput = it },
            label = { Text("Bypass proxy for (comma-separated)") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TAG_PROXY_BYPASS_INPUT)
        )
        validationError?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    autoReconnect = !autoReconnect
                    settingsStore.saveAutoReconnectEnabled(autoReconnect)
                    infoMessage = if (autoReconnect) {
                        "Auto-reconnect on boot enabled."
                    } else {
                        "Auto-reconnect on boot disabled."
                    }
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = autoReconnect,
                onCheckedChange = { checked ->
                    autoReconnect = checked
                    settingsStore.saveAutoReconnectEnabled(checked)
                    infoMessage = if (checked) {
                        "Auto-reconnect on boot enabled."
                    } else {
                        "Auto-reconnect on boot disabled."
                    }
                }
            )
            Text("Auto-reconnect on boot")
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Routing mode",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    routingMode = RoutingMode.Bypass
                    settingsStore.saveRoutingMode(routingMode)
                    infoMessage = "Routing mode set to bypass."
                }
            ) {
                Text(if (routingMode == RoutingMode.Bypass) "Bypass (active)" else "Bypass")
            }
            OutlinedButton(
                onClick = {
                    routingMode = RoutingMode.Allowlist
                    settingsStore.saveRoutingMode(routingMode)
                    infoMessage = "Routing mode set to allowlist."
                }
            ) {
                Text(if (routingMode == RoutingMode.Allowlist) "Allowlist (active)" else "Allowlist")
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (routingMode == RoutingMode.Bypass) {
                    "Bypass apps: ${bypassPackages.size}"
                } else {
                    "Allowlist apps: ${bypassPackages.size}"
                },
                style = MaterialTheme.typography.bodyMedium
            )
            Button(onClick = { showBypassDialog = true }) {
                Text(if (routingMode == RoutingMode.Bypass) "Select Bypass Apps" else "Select Allowlist Apps")
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Status: ${runtimeSnapshot.status.name}",
            style = MaterialTheme.typography.bodyLarge
        )
        runtimeSnapshot.lastError?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        infoMessage?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = it, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(modifier = Modifier.height(24.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = {
                    val host = hostInput.trim()
                    val port = portInput.trim().toIntOrNull() ?: return@Button
                    persistBaseSettings(host, port)
                    infoMessage = "Proxy settings saved."
                },
                enabled = configIsValid
            ) {
                Text("Save")
            }
            Button(
                onClick = {
                    val host = hostInput.trim()
                    val port = portInput.trim().toIntOrNull() ?: return@Button
                    persistBaseSettings(host, port)
                    uiScope.launch {
                        isTestingConnection = true
                        val error = withContext(Dispatchers.IO) {
                            ProxyConnectivityChecker.testConnection(host, port)
                        }
                        if (error == null) {
                            infoMessage = "Proxy test succeeded."
                            VpnRuntimeState.appendLog(
                                "Proxy test succeeded for ${proxyProtocol.name} ${EndpointSanitizer.sanitizeHost(host)}:$port"
                            )
                        } else {
                            VpnRuntimeState.setError(error)
                        }
                        isTestingConnection = false
                    }
                },
                enabled = configIsValid && !isTestingConnection
            ) {
                if (isTestingConnection) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(20.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("Test Connection")
                }
            }
            if (runtimeSnapshot.status == RuntimeStatus.Running ||
                runtimeSnapshot.status == RuntimeStatus.Connecting
            ) {
                Button(onClick = {
                    AppVpnService.stop(context)
                    infoMessage = null
                }) {
                    Text("Stop")
                }
            } else {
                Button(
                    onClick = {
                        val host = hostInput.trim()
                        val port = portInput.trim().toIntOrNull()
                        if (port == null) {
                            VpnRuntimeState.setError("Failed to start VPN: invalid proxy settings.")
                            return@Button
                        }

                        persistBaseSettings(host, port)
                        infoMessage = null
                        val intent: Intent? = VpnService.prepare(context)
                        if (intent != null) {
                            permissionLauncher.launch(intent)
                        } else {
                            AppVpnService.start(context, host, port, proxyProtocol)
                        }
                    },
                    enabled = configIsValid && runtimeSnapshot.status != RuntimeStatus.Connecting,
                    modifier = Modifier.testTag(TAG_START_BUTTON)
                ) {
                    Text("Start")
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Recent logs",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
        ) {
            itemsIndexed(runtimeSnapshot.logs) { _, line ->
                Text(text = line, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (showBypassDialog) {
        BypassAppsDialog(
            installedApps = installedApps,
            initialSelection = bypassPackages,
            routingMode = routingMode,
            onDismiss = { showBypassDialog = false },
            onApply = { selectedPackages ->
                bypassPackages = selectedPackages
                settingsStore.saveBypassPackages(selectedPackages)
                infoMessage = if (routingMode == RoutingMode.Bypass) {
                    "Bypass app list saved."
                } else {
                    "Allowlist app list saved."
                }
                VpnRuntimeState.appendLog("App routing list updated (${selectedPackages.size} apps)")
                showBypassDialog = false
            }
        )
    }
}

@Composable
private fun BypassAppsDialog(
    installedApps: List<InstalledApp>,
    initialSelection: Set<String>,
    routingMode: RoutingMode,
    onDismiss: () -> Unit,
    onApply: (Set<String>) -> Unit
) {
    var selectedPackages by remember(initialSelection) { mutableStateOf(initialSelection.toMutableSet()) }
    var query by rememberSaveable { mutableStateOf("") }
    val filteredApps = remember(installedApps, query) {
        val normalizedQuery = query.trim().lowercase(Locale.US)
        if (normalizedQuery.isBlank()) {
            installedApps
        } else {
            installedApps.filter { app ->
                app.appLabel.lowercase(Locale.US).contains(normalizedQuery) ||
                    app.packageName.lowercase(Locale.US).contains(normalizedQuery)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (routingMode == RoutingMode.Bypass) {
                    "Select bypass apps"
                } else {
                    "Select allowlist apps"
                }
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search apps") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.height(280.dp)) {
                    itemsIndexed(filteredApps, key = { _, app -> app.packageName }) { _, app ->
                        val checked = selectedPackages.contains(app.packageName)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedPackages = selectedPackages.toMutableSet().also { set ->
                                        if (set.contains(app.packageName)) {
                                            set.remove(app.packageName)
                                        } else {
                                            set.add(app.packageName)
                                        }
                                    }
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { isChecked ->
                                    selectedPackages = selectedPackages.toMutableSet().also { set ->
                                        if (isChecked) {
                                            set.add(app.packageName)
                                        } else {
                                            set.remove(app.packageName)
                                        }
                                    }
                                }
                            )
                            Text(text = "${app.appLabel} (${app.packageName})")
                        }
                    }
                }
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        confirmButton = {
            Button(onClick = { onApply(selectedPackages.toSet()) }) {
                Text("Apply")
            }
        }
    )
}

private fun loadInstalledApps(context: Context): List<InstalledApp> {
    val packageManager = context.packageManager
    val installedApplications = getInstalledApplicationsCompat(packageManager)
    return installedApplications
        .asSequence()
        .filter { appInfo ->
            appInfo.packageName != context.packageName &&
                packageManager.getLaunchIntentForPackage(appInfo.packageName) != null
        }
        .map { appInfo ->
            InstalledApp(
                packageName = appInfo.packageName,
                appLabel = packageManager.getApplicationLabel(appInfo).toString()
            )
        }
        .sortedBy { app -> app.appLabel.lowercase(Locale.US) }
        .toList()
}

@Suppress("DEPRECATION")
private fun getInstalledApplicationsCompat(packageManager: PackageManager) =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
    } else {
        packageManager.getInstalledApplications(0)
    }
