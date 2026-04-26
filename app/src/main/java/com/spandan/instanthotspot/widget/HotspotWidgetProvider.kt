package com.spandan.instanthotspot.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.spandan.instanthotspot.R
import com.spandan.instanthotspot.core.AppPrefs
import com.spandan.instanthotspot.core.HotspotCommand
import com.spandan.instanthotspot.core.HostStateCodec
import com.spandan.instanthotspot.controller.ControllerCommandSender

class HotspotWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        for (appWidgetId in appWidgetIds) {
            appWidgetManager.updateAppWidget(
                appWidgetId,
                buildRemoteViews(context),
            )
        }
        if (AppPrefs.isClientPaired(context) && !AppPrefs.shouldThrottleStateRefresh(context, 20_000L)) {
            ControllerCommandSender.refreshHostStateAsync(
                context,
                force = false,
            ) {
                // Second pass after optional BLE read; cheap no-op if throttled
                val am = AppWidgetManager.getInstance(context)
                val ids = am.getAppWidgetIds(
                    ComponentName(context, HotspotWidgetProvider::class.java),
                )
                for (id in ids) {
                    am.updateAppWidget(id, buildRemoteViews(context))
                }
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_WIDGET_ON -> ControllerCommandSender.send(context, HotspotCommand.HOTSPOT_ON)
            ACTION_WIDGET_OFF -> ControllerCommandSender.send(context, HotspotCommand.HOTSPOT_OFF)
            else -> return
        }
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, HotspotWidgetProvider::class.java))
        onUpdate(context, manager, ids)
    }

    companion object {
        const val ACTION_WIDGET_ON = "com.spandan.instanthotspot.ACTION_WIDGET_ON"
        const val ACTION_WIDGET_OFF = "com.spandan.instanthotspot.ACTION_WIDGET_OFF"

        fun buildRemoteViews(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.hotspot_widget)
            val intentOn = Intent(context, HotspotWidgetProvider::class.java).apply {
                action = ACTION_WIDGET_ON
            }
            val intentOff = Intent(context, HotspotWidgetProvider::class.java).apply {
                action = ACTION_WIDGET_OFF
            }
            val pOn = PendingIntent.getBroadcast(
                context,
                0,
                intentOn,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val pOff = PendingIntent.getBroadcast(
                context,
                1,
                intentOff,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widgetHotspotOn, pOn)
            views.setOnClickPendingIntent(R.id.widgetHotspotOff, pOff)

            val paired = AppPrefs.isClientPaired(context)
            val ap = AppPrefs.lastHostApState(context)
            if (!paired) {
                views.setTextViewText(
                    R.id.widgetPairedName,
                    context.getString(R.string.widget_paired_name_none),
                )
                views.setViewVisibility(R.id.widgetApState, android.view.View.GONE)
            } else {
                val name = AppPrefs.pairedHostDisplayName(context)
                val addr = AppPrefs.lastPairedHost(context)
                val line = when {
                    !name.isNullOrBlank() && addr != null && addr != "manual-secret" ->
                        context.getString(R.string.widget_paired_name_format, "$name · $addr")
                    !name.isNullOrBlank() -> context.getString(R.string.widget_paired_name_format, name)
                    addr != null && addr != "manual-secret" -> addr
                    else -> context.getString(R.string.widget_paired_name_format, "Paired (manual)")
                }
                views.setTextViewText(R.id.widgetPairedName, line)
                views.setViewVisibility(R.id.widgetApState, android.view.View.VISIBLE)
                val apText = when (ap) {
                    HostStateCodec.PREF_ON -> context.getString(R.string.widget_ap_state_on)
                    HostStateCodec.PREF_OFF -> context.getString(R.string.widget_ap_state_off)
                    else -> context.getString(R.string.widget_ap_state_unknown)
                }
                views.setTextViewText(R.id.widgetApState, apText)
            }
            return views
        }

        fun requestUpdateAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, HotspotWidgetProvider::class.java))
            for (id in ids) {
                mgr.updateAppWidget(id, buildRemoteViews(context))
            }
        }
    }
}
