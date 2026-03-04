package com.infinitezerone.pratice.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.infinitezerone.pratice.MainActivity
import com.infinitezerone.pratice.R
import com.infinitezerone.pratice.config.ProxySettingsStore
import com.infinitezerone.pratice.vpn.AppVpnService
import com.infinitezerone.pratice.vpn.RuntimeStatus
import com.infinitezerone.pratice.vpn.VpnRuntimeState

class VpnControlWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { widgetId ->
            appWidgetManager.updateAppWidget(widgetId, buildRemoteViews(context))
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_START -> startVpnFromWidget(context)
            ACTION_STOP -> AppVpnService.stop(context)
            ACTION_REFRESH -> Unit
            else -> return
        }
        updateAllWidgets(context)
    }

    private fun startVpnFromWidget(context: Context) {
        val config = ProxySettingsStore(context).loadConfigOrNull()
        if (config == null) {
            VpnRuntimeState.setError("Cannot start from widget: proxy config is invalid.")
            return
        }
        AppVpnService.start(context, config.host, config.port, config.protocol)
    }

    private fun buildRemoteViews(context: Context): RemoteViews {
        val snapshot = VpnRuntimeState.state.value
        val statusText = when (snapshot.status) {
            RuntimeStatus.Stopped -> context.getString(R.string.widget_status_stopped)
            RuntimeStatus.Connecting -> context.getString(R.string.widget_status_connecting)
            RuntimeStatus.Running -> context.getString(R.string.widget_status_running)
            RuntimeStatus.Error -> context.getString(R.string.widget_status_error)
        }
        val subtitle = snapshot.lastError ?: snapshot.logs.firstOrNull()
            ?: context.getString(R.string.widget_subtitle_default)

        return RemoteViews(context.packageName, R.layout.widget_vpn_control).apply {
            setTextViewText(R.id.widget_status, statusText)
            setTextViewText(R.id.widget_subtitle, subtitle)
            setOnClickPendingIntent(R.id.widget_start_button, broadcastIntent(context, ACTION_START))
            setOnClickPendingIntent(R.id.widget_stop_button, broadcastIntent(context, ACTION_STOP))
            setOnClickPendingIntent(R.id.widget_refresh_button, broadcastIntent(context, ACTION_REFRESH))
            setOnClickPendingIntent(R.id.widget_open_button, openAppIntent(context))
        }
    }

    private fun broadcastIntent(context: Context, action: String): PendingIntent {
        val intent = Intent(context, VpnControlWidgetProvider::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            REQUEST_OPEN_APP,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        private const val REQUEST_OPEN_APP = 1000
        private const val ACTION_START = "com.infinitezerone.pratice.widget.START"
        private const val ACTION_STOP = "com.infinitezerone.pratice.widget.STOP"
        private const val ACTION_REFRESH = "com.infinitezerone.pratice.widget.REFRESH"

        fun updateAllWidgets(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, VpnControlWidgetProvider::class.java)
            val widgetIds = appWidgetManager.getAppWidgetIds(component)
            if (widgetIds.isEmpty()) {
                return
            }
            val provider = VpnControlWidgetProvider()
            provider.onUpdate(context, appWidgetManager, widgetIds)
        }
    }
}
