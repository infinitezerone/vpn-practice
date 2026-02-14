package com.infinitezerone.pratice

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test

class VpnStopConnectionTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun stopButtonReturnsUiToStoppedState() {
        val args = InstrumentationRegistry.getArguments()
        val proxyHost = args.getString(ARG_PROXY_HOST) ?: DEFAULT_PROXY_HOST
        val proxyPort = args.getString(ARG_PROXY_PORT)?.toIntOrNull() ?: DEFAULT_PROXY_PORT
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

        composeRule.onNodeWithTag("proxy_host_input").performTextClearance()
        composeRule.onNodeWithTag("proxy_host_input").performTextInput(proxyHost)
        composeRule.onNodeWithTag("proxy_port_input").performTextClearance()
        composeRule.onNodeWithTag("proxy_port_input").performTextInput(proxyPort.toString())
        composeRule.onNodeWithTag("protocol_socks_button").performClick()
        composeRule.onNodeWithTag("start_button").performClick()

        acceptVpnDialogIfShown(device)
        composeRule.waitUntil(30_000) { hasText("Stop") }

        composeRule.onNodeWithText("Stop").performClick()
        composeRule.waitUntil(15_000) { hasText("Status: Stopped") }
        composeRule.waitUntil(15_000) { hasText("Start") }
    }

    private fun hasText(text: String): Boolean =
        composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun acceptVpnDialogIfShown(device: UiDevice) {
        val clickable = By.clickable(true)
        if (!device.wait(Until.hasObject(clickable), 5_000)) {
            return
        }
        listOf("OK", "Allow", "Start now").forEach { text ->
            val button = device.findObject(By.textContains(text))
            if (button != null) {
                button.click()
                return
            }
        }
        device.findObject(clickable)?.click()
    }

    private companion object {
        const val ARG_PROXY_HOST = "proxyHost"
        const val ARG_PROXY_PORT = "proxyPort"
        const val DEFAULT_PROXY_HOST = "stg-proxy.travel.rakuten.co.jp"
        const val DEFAULT_PROXY_PORT = 9027
    }
}
