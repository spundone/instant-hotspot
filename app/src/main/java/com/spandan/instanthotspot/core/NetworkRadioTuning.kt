package com.spandan.instanthotspot.core

import android.content.Context

/**
 * Best-effort cellular mode tuning via [settings global preferred_network_mode] as root.
 * [NR_5G_ONLY_MODE] is AOSP RIL [NETWORK_MODE_NR_ONLY]; OEMs may use different encodings.
 */
object NetworkRadioTuning {
    private const val NR_5G_ONLY_MODE = "33"

    fun apply5gOnly(context: Context): Boolean {
        if (!HotspotController.hasRootPermission()) {
            DebugLog.append(context, "NET", "5G only: not rooted")
            return false
        }
        val cur = HotspotController.shOutputRoot("/system/bin/settings get global preferred_network_mode")
            .lineSequence()
            .firstOrNull()
            ?.trim()
            .orEmpty()
        if (cur.isNotEmpty() && !cur.equals("null", true) && !cur.equals("undefined", true)) {
            AppPrefs.setPreferredNetworkModeBackup(context, cur)
        }
        val line = "/system/bin/settings put global preferred_network_mode $NR_5G_ONLY_MODE"
        val ok = HotspotController.shLineRootSuccess(line)
        DebugLog.append(context, "NET", "5G only apply $ok (was: $cur)")
        return ok
    }

    fun revertPreferredMode(context: Context): Boolean {
        if (!HotspotController.hasRootPermission()) return false
        val backup = AppPrefs.preferredNetworkModeBackup(context)
        if (backup.isNullOrBlank()) {
            DebugLog.append(context, "NET", "revert: no backup")
            return false
        }
        val ok = HotspotController.shLineRootSuccess(
            "/system/bin/settings put global preferred_network_mode $backup",
        )
        if (ok) {
            AppPrefs.setPreferredNetworkModeBackup(context, null)
        }
        DebugLog.append(context, "NET", "revert $ok")
        return ok
    }
}
