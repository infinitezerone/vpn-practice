package com.infinitezerone.pratice

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class VpnProxyForwardingTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun tunTrafficShowsBridgeStatsWhenBrowsing() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val proxyHost = args.getString(ARG_PROXY_HOST) ?: DEFAULT_PROXY_HOST
        val proxyPort = args.getString(ARG_PROXY_PORT)?.toIntOrNull() ?: DEFAULT_PROXY_PORT
        val device = UiDevice.getInstance(instrumentation)

        device.executeShellCommand("logcat -c")

        composeRule.onNodeWithTag("proxy_host_input").performTextClearance()
        composeRule.onNodeWithTag("proxy_host_input").performTextInput(proxyHost)
        composeRule.onNodeWithTag("proxy_port_input").performTextClearance()
        composeRule.onNodeWithTag("proxy_port_input").performTextInput(proxyPort.toString())
        composeRule.onNodeWithTag("start_button").performClick()

        acceptVpnDialogIfShown(device)
        Thread.sleep(9_000)

        device.executeShellCommand("am start -a android.intent.action.VIEW -d https://example.com")
        Thread.sleep(10_000)

        val logs = device.executeShellCommand("logcat -d -s PraticeVPN:I")
        assertTrue("Expected tun2socks to start. Logs:\n$logs", logs.contains("Starting tun2socks bridge."))
        assertTrue("Expected VPN to enter running state. Logs:\n$logs", logs.contains("VPN running via proxy"))
        assertTrue("Expected bridge stats to show non-zero traffic. Logs:\n$logs", hasNonZeroStats(logs))
    }

    private fun acceptVpnDialogIfShown(device: UiDevice) {
        val okSelector = By.clickable(true)
        if (device.wait(Until.hasObject(okSelector), 5_000)) {
            val candidates = listOf("OK", "Allow", "Start now")
            for (text in candidates) {
                val button = device.findObject(By.textContains(text))
                if (button != null) {
                    button.click()
                    return
                }
            }
            val fallback = device.findObject(okSelector)
            fallback?.click()
        }
    }

    private fun hasNonZeroStats(logs: String): Boolean {
        val statsLines = logs.lines().filter { it.contains("tun2socks stats=[") }
        return statsLines.any { line ->
            val values = line.substringAfter("tun2socks stats=[").substringBefore("]")
                .split(",")
                .mapNotNull { it.trim().toLongOrNull() }
            values.any { it > 0L }
        }
    }

    private companion object {
        const val ARG_PROXY_HOST = "proxyHost"
        const val ARG_PROXY_PORT = "proxyPort"
        const val DEFAULT_PROXY_HOST = "stg-proxy.travel.rakuten.co.jp"
        const val DEFAULT_PROXY_PORT = 9027
    }
}
