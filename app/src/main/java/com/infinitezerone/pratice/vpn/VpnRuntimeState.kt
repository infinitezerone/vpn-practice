package com.infinitezerone.pratice.vpn

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalTime
import java.time.format.DateTimeFormatter

enum class RuntimeStatus {
    Stopped,
    Connecting,
    Running,
    Error
}

data class RuntimeSnapshot(
    val status: RuntimeStatus = RuntimeStatus.Stopped,
    val lastError: String? = null,
    val logs: List<String> = emptyList()
)

object VpnRuntimeState {
    private const val TAG = "PraticeVPN"
    private val timestampFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    private const val MAX_LOG_LINES = 40

    private val _state = MutableStateFlow(RuntimeSnapshot())
    val state: StateFlow<RuntimeSnapshot> = _state

    fun setConnecting(host: String, port: Int) {
        appendLog("Connecting to proxy ${EndpointSanitizer.sanitizeHost(host)}:$port")
        _state.update { it.copy(status = RuntimeStatus.Connecting, lastError = null) }
    }

    fun setRunning(host: String, port: Int) {
        appendLog("VPN running via proxy ${EndpointSanitizer.sanitizeHost(host)}:$port")
        _state.update { it.copy(status = RuntimeStatus.Running, lastError = null) }
    }

    fun setStopped(reason: String? = null) {
        reason?.let { appendLog(it) }
        _state.update { it.copy(status = RuntimeStatus.Stopped) }
    }

    fun setError(errorMessage: String) {
        appendLog("Error: $errorMessage")
        _state.update { it.copy(status = RuntimeStatus.Error, lastError = errorMessage) }
    }

    fun appendLog(message: String) {
        val entry = "[${LocalTime.now().format(timestampFormatter)}] $message"
        Log.i(TAG, message)
        _state.update { snapshot ->
            val updatedLogs = (listOf(entry) + snapshot.logs).take(MAX_LOG_LINES)
            snapshot.copy(logs = updatedLogs)
        }
    }
}
