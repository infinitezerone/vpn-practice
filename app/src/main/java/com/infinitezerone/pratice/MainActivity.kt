package com.infinitezerone.pratice

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.infinitezerone.pratice.config.ProxyConfigValidator
import com.infinitezerone.pratice.config.ProxySettingsStore
import com.infinitezerone.pratice.vpn.AppVpnService

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

@Composable
private fun VpnHome() {
    val context = LocalContext.current
    val settingsStore = remember(context) { ProxySettingsStore(context) }

    var status by remember { mutableStateOf(VpnStatus.Stopped) }
    var message by remember { mutableStateOf<String?>(null) }
    var hostInput by rememberSaveable { mutableStateOf(settingsStore.loadHost()) }
    var portInput by rememberSaveable { mutableStateOf(settingsStore.loadPortText()) }
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
}
