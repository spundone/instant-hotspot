package com.spandan.instanthotspot.core

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import com.spandan.instanthotspot.tethering.PrivilegedTethering

object HotspotStateProbe {

    enum class ApState { UP, DOWN, UNKNOWN }

    /**
     * Best-effort soft AP / tethering state. Prefers [WifiManager] hidden APIs, then
     * root `dumpsys` heuristics (works on most rooted OEM builds).
     */
    fun currentApState(context: Context): ApState {
        softApStateFromWifiManager(context)?.let { up ->
            return if (up) ApState.UP else ApState.DOWN
        }
        return dumpsysOrSuFallback()
    }

    private fun softApStateFromWifiManager(context: Context): Boolean? {
        return try {
            val wm = context.applicationContext
                .getSystemService(WifiManager::class.java) ?: return null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val m = WifiManager::class.java.getMethod("getSoftApState")
                val s = m.invoke(wm) as Int
                val enabledConst = apEnabledConstant(WifiManager::class.java, preferSoft = true)
                s == enabledConst
            } else {
                @Suppress("DEPRECATION")
                val m = WifiManager::class.java.getMethod("getWifiApState")
                val s = m.invoke(wm) as Int
                s == apEnabledConstant(WifiManager::class.java, preferSoft = false)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun apEnabledConstant(wmClass: Class<*>, preferSoft: Boolean): Int {
        val names = if (preferSoft) {
            listOf("WIFI_AP_STATE_ENABLED", "SOFT_AP_ENABLED")
        } else {
            listOf("WIFI_AP_STATE_ENABLED")
        }
        for (n in names) {
            runCatching { wmClass.getField(n).getInt(null) }
                .onSuccess { return it }
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 14 else 13
    }

    private fun dumpsysOrSuFallback(): ApState {
        if (!HotspotController.hasRootPermission()) return ApState.UNKNOWN
        val d = runSuForOutput("dumpsys connectivity 2>/dev/null; dumpsys wifi 2>/dev/null")
        if (d.isBlank()) return ApState.UNKNOWN
        if (d.contains("SoftAp", ignoreCase = true) &&
            (d.contains("enabled: true", ignoreCase = true) || d.contains("mActiveMode true", true))
        ) {
            return ApState.UP
        }
        if (d.contains("Tethering [", true) && d.contains("WIFI: true", true)) return ApState.UP
        if (d.contains("SoftAp", true) && d.contains("enabled: false", true)) return ApState.DOWN
        return ApState.UNKNOWN
    }

    private fun runSuForOutput(cmd: String): String {
        return try {
            val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            p.inputStream.bufferedReader().readText()
        } catch (_: Exception) {
            ""
        }
    }
}

object HotspotTetheringController {
    /**
     * Whether the process holds [android.permission.TETHER_PRIVILEGED] (e.g. priv-app / OEM
     * or optional module allowlist), same check used by [PrivilegedTethering].
     */
    fun hasTetherPrivilege(context: Context): Boolean {
        return PrivilegedTethering.canUse(context)
    }
}
