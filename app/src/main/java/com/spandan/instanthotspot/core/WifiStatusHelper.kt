package com.spandan.instanthotspot.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import com.spandan.instanthotspot.R

object WifiStatusHelper {
    /**
     * Best-effort current Wi‑Fi SSID (null if unknown, "<unknown>" if blocked by privacy).
     */
    fun currentSsid(context: Context): String? {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                val n = cm?.activeNetwork ?: return null
                val caps = cm.getNetworkCapabilities(n) ?: return null
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
            }
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val info = wm?.connectionInfo
            var ssid = info?.ssid?.trim('"', '<', '>')
            if (ssid == "<unknown ssid>") ssid = null
            ssid
        }.getOrNull()
    }

    fun isOnWifi(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val n = cm.activeNetwork ?: return false
        return cm.getNetworkCapabilities(n)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    fun line(context: Context): String {
        if (!isOnWifi(context)) {
            return context.getString(R.string.ob_network_wifi_off)
        }
        val ssid = currentSsid(context)
        return if (ssid.isNullOrBlank()) {
            context.getString(R.string.ob_network_wifi_on_no_ssid)
        } else {
            context.getString(R.string.ob_network_wifi_ssid, ssid)
        }
    }
}
