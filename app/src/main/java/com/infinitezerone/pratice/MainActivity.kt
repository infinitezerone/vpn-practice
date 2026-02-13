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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.infinitezerone.pratice.config.ProxyConfigValidator
import com.infinitezerone.pratice.config.ProxySettingsStore
import com.infinitezerone.pratice.vpn.AppVpnService
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

@Composable
private fun VpnHome() {
    val context = LocalContext.current
    val settingsStore = remember(context) { ProxySettingsStore(context) }
    val installedApps = remember(context) { loadInstalledApps(context) }

    var hostInput by rememberSaveable { mutableStateOf(settingsStore.loadHost()) }
    var portInput by rememberSaveable { mutableStateOf(settingsStore.loadPortText()) }
    var bypassPackages by remember { mutableStateOf(settingsStore.loadBypassPackages()) }
    var showBypassDialog by remember { mutableStateOf(false) }
    var infoMessage by remember { mutableStateOf<String?>(null) }
    var isTestingConnection by remember { mutableStateOf(false) }
    val runtimeSnapshot by VpnRuntimeState.state.collectAsState()
    val uiScope = rememberCoroutineScope()

    val validationError = ProxyConfigValidator.validate(hostInput.trim(), portInput.trim())
    val configIsValid = validationError == null

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

            settingsStore.save(host, port)
            AppVpnService.start(context, host, port)
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
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = portInput,
            onValueChange = { portInput = it },
            label = { Text("Proxy Port") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
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
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Bypass apps: ${bypassPackages.size}",
                style = MaterialTheme.typography.bodyMedium
            )
            Button(onClick = { showBypassDialog = true }) {
                Text("Select Bypass Apps")
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
                    settingsStore.save(host, port)
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
                    settingsStore.save(host, port)
                    uiScope.launch {
                        isTestingConnection = true
                        val error = withContext(Dispatchers.IO) {
                            ProxyConnectivityChecker.testConnection(host, port)
                        }
                        if (error == null) {
                            infoMessage = "Proxy test succeeded."
                            VpnRuntimeState.appendLog("Proxy test succeeded for $host:$port")
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

                        settingsStore.save(host, port)
                        infoMessage = null
                        val intent: Intent? = VpnService.prepare(context)
                        if (intent != null) {
                            permissionLauncher.launch(intent)
                        } else {
                            AppVpnService.start(context, host, port)
                        }
                    },
                    enabled = configIsValid && runtimeSnapshot.status != RuntimeStatus.Connecting
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
            onDismiss = { showBypassDialog = false },
            onApply = { selectedPackages ->
                bypassPackages = selectedPackages
                settingsStore.saveBypassPackages(selectedPackages)
                infoMessage = "Bypass app list saved."
                VpnRuntimeState.appendLog("Bypass list updated (${selectedPackages.size} apps)")
                showBypassDialog = false
            }
        )
    }
}

@Composable
private fun BypassAppsDialog(
    installedApps: List<InstalledApp>,
    initialSelection: Set<String>,
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
        title = { Text("Select bypass apps") },
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
