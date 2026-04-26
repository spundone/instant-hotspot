package com.spandan.instanthotspot.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.spandan.instanthotspot.R

object WifiStatusHelper {
    private fun canReadNetworkState(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_NETWORK_STATE,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Best-effort current Wi‑Fi SSID (null if unknown, "<unknown>" if blocked by privacy).
     */
    fun currentSsid(context: Context): String? {
        if (!canReadNetworkState(context)) return null
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
        if (!canReadNetworkState(context)) return false
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

    /**
     * Best-effort multi-line report for the active Wi‑Fi link (for tools). Location may be required for SSID.
     */
    fun detailsBlock(context: Context): String = buildString {
        if (!canReadNetworkState(context)) {
            appendLine(context.getString(R.string.tools_wifi_no_permission))
            return@buildString
        }
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return@buildString
        val n = cm.activeNetwork ?: return@buildString
        if (cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) {
            appendLine(context.getString(R.string.ob_network_wifi_off))
            return@buildString
        }
        val lp: LinkProperties? = cm.getLinkProperties(n)
        val addrs = lp?.linkAddresses
            ?.map { it.address.hostAddress }
            ?.filter { it?.contains(':') == false } // prefer IPv4 for brevity
            ?.joinToString(", ") ?: "—"
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        @Suppress("MissingPermission", "DEPRECATION")
        val info: WifiInfo? = wm?.connectionInfo
        val ss = currentSsid(context)
            ?: (info?.ssid?.trim('"')?.takeIf { it != "<unknown ssid>" } ?: "?")
        appendLine("SSID: $ss")
        if (info != null) {
            appendLine("RSSI: ${info.rssi} dBm")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && info.frequency > 0) {
                val band = if (info.frequency < 3_000) "2.4 GHz" else "5/6 GHz"
                appendLine("Radio: $band  ${info.frequency} MHz")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                appendLine("Link (PHY): ${info.linkSpeed} Mbps")
            }
        }
        appendLine("IPv4: $addrs")
    }.ifBlank { "—" }
}
