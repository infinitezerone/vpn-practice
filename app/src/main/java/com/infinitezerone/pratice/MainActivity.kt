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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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

private enum class VpnStatus {
    Stopped,
    Running,
    Error
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

    var status by remember { mutableStateOf(VpnStatus.Stopped) }
    var message by remember { mutableStateOf<String?>(null) }
    var hostInput by rememberSaveable { mutableStateOf(settingsStore.loadHost()) }
    var portInput by rememberSaveable { mutableStateOf(settingsStore.loadPortText()) }
    var bypassPackages by remember { mutableStateOf(settingsStore.loadBypassPackages()) }
    var showBypassDialog by remember { mutableStateOf(false) }
    val validationError = ProxyConfigValidator.validate(hostInput.trim(), portInput.trim())
    val configIsValid = validationError == null

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val host = hostInput.trim()
            val port = portInput.trim().toIntOrNull()
            if (port == null) {
                status = VpnStatus.Error
                message = "Failed to start VPN: invalid proxy settings."
                return@rememberLauncherForActivityResult
            }

            settingsStore.save(host, port)
            AppVpnService.start(context, host, port)
            status = VpnStatus.Running
            message = null
        } else {
            status = VpnStatus.Error
            message = "VPN permission denied."
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
            text = "Status: ${status.name}",
            style = MaterialTheme.typography.bodyLarge
        )
        message?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    val host = hostInput.trim()
                    val port = portInput.trim().toIntOrNull() ?: return@Button
                    settingsStore.save(host, port)
                    message = "Proxy settings saved."
                },
                enabled = configIsValid
            ) {
                Text("Save")
            }
            if (status == VpnStatus.Running) {
                Button(onClick = {
                    AppVpnService.stop(context)
                    status = VpnStatus.Stopped
                    message = null
                }) {
                    Text("Stop")
                }
            } else {
                Button(
                    onClick = {
                        val host = hostInput.trim()
                        val port = portInput.trim().toIntOrNull()
                        if (port == null) {
                            status = VpnStatus.Error
                            message = "Failed to start VPN: invalid proxy settings."
                            return@Button
                        }

                        settingsStore.save(host, port)
                        val intent: Intent? = VpnService.prepare(context)
                        if (intent != null) {
                            permissionLauncher.launch(intent)
                        } else {
                            AppVpnService.start(context, host, port)
                            status = VpnStatus.Running
                            message = null
                        }
                    },
                    enabled = configIsValid
                ) {
                    Text("Start")
                }
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
                message = "Bypass app list saved."
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select bypass apps") },
        text = {
            LazyColumn(modifier = Modifier.height(320.dp)) {
                items(installedApps, key = { it.packageName }) { app ->
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
