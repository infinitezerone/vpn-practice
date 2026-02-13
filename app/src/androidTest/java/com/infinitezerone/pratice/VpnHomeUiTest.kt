package com.infinitezerone.pratice

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test

class VpnHomeUiTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun startButtonFollowsProxyValidation() {
        composeTestRule.onNodeWithTag("proxy_host_input").performTextClearance()
        composeTestRule.onNodeWithTag("proxy_port_input").performTextClearance()
        composeTestRule.onNodeWithTag("start_button").assertIsNotEnabled()

        composeTestRule.onNodeWithTag("proxy_host_input").performTextInput("127.0.0.1")
        composeTestRule.onNodeWithTag("proxy_port_input").performTextInput("1080")
        composeTestRule.onNodeWithTag("start_button").assertIsEnabled()
    }
}
