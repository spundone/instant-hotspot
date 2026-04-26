package com.spandan.instanthotspot.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.spandan.instanthotspot.R
import com.spandan.instanthotspot.core.HotspotCommand
import com.spandan.instanthotspot.controller.ControllerCommandSender

class HotspotWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        for (appWidgetId in appWidgetIds) {
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
            appWidgetManager.updateAppWidget(appWidgetId, views)
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
    }
}
