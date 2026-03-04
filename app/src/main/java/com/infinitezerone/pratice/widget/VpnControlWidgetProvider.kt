package com.infinitezerone.pratice.widget

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.background
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
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
    override val sizeMode = SizeMode.Responsive(
        setOf(
            DpSize(180.dp, 110.dp),
            DpSize(220.dp, 140.dp),
            DpSize(280.dp, 180.dp)
        )
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val appContext = LocalContext.current
            val size = LocalSize.current
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
            val subtitleSingleLine = subtitle
                .replace('\n', ' ')
                .let { if (it.length > 56) "${it.take(53)}..." else it }
            val tinyLayout = size.height < 130.dp || size.width < 190.dp
            val compactLayout = size.height < 165.dp || size.width < 240.dp

            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(ColorProvider(Color.White))
                    .padding(8.dp)
            ) {
                Text(
                    text = appContext.getString(R.string.widget_title),
                    style = TextStyle(fontWeight = FontWeight.Bold)
                )
                Spacer(modifier = GlanceModifier.height(4.dp))
                Text(text = statusText)
                if (!tinyLayout) {
                    Spacer(modifier = GlanceModifier.height(2.dp))
                    Text(text = subtitleSingleLine)
                }
                Spacer(modifier = GlanceModifier.height(6.dp))

                if (tinyLayout) {
                    Row(modifier = GlanceModifier.fillMaxWidth()) {
                        ActionChip(
                            text = appContext.getString(R.string.widget_start),
                            modifier = GlanceModifier.clickable(actionRunCallback<StartVpnAction>())
                        )
                        Spacer(modifier = GlanceModifier.width(10.dp))
                        ActionChip(
                            text = appContext.getString(R.string.widget_stop),
                            modifier = GlanceModifier.clickable(actionRunCallback<StopVpnAction>())
                        )
                        Spacer(modifier = GlanceModifier.width(10.dp))
                        ActionChip(
                            text = appContext.getString(R.string.widget_open_short),
                            modifier = GlanceModifier.clickable(actionStartActivity<MainActivity>())
                        )
                    }
                } else {
                    Row(modifier = GlanceModifier.fillMaxWidth()) {
                        ActionChip(
                            text = appContext.getString(R.string.widget_start),
                            modifier = GlanceModifier.clickable(actionRunCallback<StartVpnAction>())
                        )
                        Spacer(modifier = GlanceModifier.width(10.dp))
                        ActionChip(
                            text = appContext.getString(R.string.widget_stop),
                            modifier = GlanceModifier.clickable(actionRunCallback<StopVpnAction>())
                        )
                    }
                    Spacer(modifier = GlanceModifier.height(if (compactLayout) 3.dp else 4.dp))
                    Row(modifier = GlanceModifier.fillMaxWidth()) {
                        ActionChip(
                            text = if (compactLayout) {
                                appContext.getString(R.string.widget_refresh_short)
                            } else {
                                appContext.getString(R.string.widget_refresh)
                            },
                            modifier = GlanceModifier.clickable(actionRunCallback<RefreshWidgetAction>())
                        )
                        Spacer(modifier = GlanceModifier.width(10.dp))
                        ActionChip(
                            text = if (compactLayout) {
                                appContext.getString(R.string.widget_open_short)
                            } else {
                                appContext.getString(R.string.widget_open_app)
                            },
                            modifier = GlanceModifier.clickable(actionStartActivity<MainActivity>())
                        )
                    }
                }
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

class StartVpnAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val config = ProxySettingsStore(context).loadConfigOrNull()
        if (config == null) {
            VpnRuntimeState.setError("Cannot start from widget: proxy config is invalid.")
        } else if (VpnService.prepare(context) != null) {
            VpnRuntimeState.setError("VPN permission required. Open app and tap Start once.")
            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(openAppIntent)
        } else {
            AppVpnService.start(context, config.host, config.port, config.protocol)
        }
        VpnControlGlanceWidget().updateAll(context)
    }
}

class StopVpnAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        AppVpnService.stop(context)
        VpnControlGlanceWidget().updateAll(context)
    }
}

class RefreshWidgetAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        VpnControlGlanceWidget().updateAll(context)
    }
}

@Composable
private fun ActionChip(text: String, modifier: GlanceModifier) {
    Text(
        text = text,
        modifier = modifier
            .padding(vertical = 4.dp),
        style = TextStyle(fontWeight = FontWeight.Medium)
    )
}
