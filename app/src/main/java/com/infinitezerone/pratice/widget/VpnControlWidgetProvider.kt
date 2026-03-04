package com.infinitezerone.pratice.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.infinitezerone.pratice.MainActivity
import com.infinitezerone.pratice.R
import com.infinitezerone.pratice.config.ProxySettingsStore
import com.infinitezerone.pratice.vpn.AppVpnService
import com.infinitezerone.pratice.vpn.RuntimeStatus
import com.infinitezerone.pratice.vpn.VpnRuntimeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class VpnControlWidgetProvider : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = VpnControlGlanceWidget()

    companion object {
        fun updateAllWidgets(context: Context) {
            VpnControlWidgetUpdater.updateAll(context)
        }
    }
}

private class VpnControlGlanceWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val appContext = LocalContext.current
            val snapshot = VpnRuntimeState.state.value
            val statusText = when (snapshot.status) {
                RuntimeStatus.Stopped -> appContext.getString(R.string.widget_status_stopped)
                RuntimeStatus.Connecting -> appContext.getString(R.string.widget_status_connecting)
                RuntimeStatus.Running -> appContext.getString(R.string.widget_status_running)
                RuntimeStatus.Error -> appContext.getString(R.string.widget_status_error)
            }
            val subtitle = snapshot.lastError
                ?: snapshot.logs.firstOrNull()
                ?: appContext.getString(R.string.widget_subtitle_default)

            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .padding(12.dp)
            ) {
                Text(
                    text = appContext.getString(R.string.widget_title),
                    style = TextStyle(fontWeight = FontWeight.Bold)
                )
                Spacer(modifier = GlanceModifier.height(8.dp))
                Text(text = statusText)
                Spacer(modifier = GlanceModifier.height(4.dp))
                Text(text = subtitle)
                Spacer(modifier = GlanceModifier.height(10.dp))
                ActionChip(
                    text = appContext.getString(R.string.widget_start),
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .clickable(actionRunCallback<StartVpnAction>())
                )
                Spacer(modifier = GlanceModifier.height(6.dp))
                ActionChip(
                    text = appContext.getString(R.string.widget_stop),
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .clickable(actionRunCallback<StopVpnAction>())
                )
                Spacer(modifier = GlanceModifier.height(6.dp))
                ActionChip(
                    text = appContext.getString(R.string.widget_refresh),
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .clickable(actionRunCallback<RefreshWidgetAction>())
                )
                Spacer(modifier = GlanceModifier.height(6.dp))
                ActionChip(
                    text = appContext.getString(R.string.widget_open_app),
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .clickable(actionStartActivity<MainActivity>())
                )
            }
        }
    }
}

private object VpnControlWidgetUpdater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun updateAll(context: Context) {
        scope.launch {
            VpnControlGlanceWidget().updateAll(context)
        }
    }
}

private class StartVpnAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val config = ProxySettingsStore(context).loadConfigOrNull()
        if (config == null) {
            VpnRuntimeState.setError("Cannot start from widget: proxy config is invalid.")
        } else {
            AppVpnService.start(context, config.host, config.port, config.protocol)
        }
        VpnControlGlanceWidget().updateAll(context)
    }
}

private class StopVpnAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        AppVpnService.stop(context)
        VpnControlGlanceWidget().updateAll(context)
    }
}

private class RefreshWidgetAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        VpnControlGlanceWidget().updateAll(context)
    }
}

@Composable
private fun ActionChip(text: String, modifier: GlanceModifier) {
    Text(
        text = text,
        modifier = modifier
            .padding(8.dp),
        style = TextStyle(fontWeight = FontWeight.Medium)
    )
}
