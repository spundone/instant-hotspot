package com.spandan.instanthotspot.tethering

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Uses [TetheringManager] when the app holds [android.permission.TETHER_PRIVILEGED]
 * (optional: priv-app, OEM, or a systemless module that adds allowlisted permissions).
 * Otherwise the app uses root + shell. Kept in a small library so the app stays isolated
 * from platform stubs.
 *
 * API 36+ uses [TetheringRequest] and interface-based callbacks; pre-36 uses the legacy
 * int / boolean / callback-class overload. See [PrivilegedTetheringApi36] and
 * [PrivilegedTetheringLegacy].
 */
object PrivilegedTethering {
    const val TETHER_PRIVILEGED = "android.permission.TETHER_PRIVILEGED"

    /** API level where [TetheringManager] switched to TetheringRequest + interface callbacks. */
    private const val TETHERING_API_36 = 36

    fun canUse(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return ContextCompat.checkSelfPermission(
            context,
            TETHER_PRIVILEGED,
        ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission", "InlinedApi")
    fun startWifiTetheringSync(context: Context, timeoutSec: Long = 8L): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        if (!canUse(context)) return false
        return if (Build.VERSION.SDK_INT >= TETHERING_API_36) {
            PrivilegedTetheringApi36.startWifiTetheringSync(context, timeoutSec)
        } else {
            PrivilegedTetheringLegacy.startWifiTetheringSync(context, timeoutSec)
        }
    }

    @SuppressLint("InlinedApi")
    fun stopWifiTethering(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        if (!canUse(context)) return false
        return if (Build.VERSION.SDK_INT >= TETHERING_API_36) {
            PrivilegedTetheringApi36.stopWifiTethering(context)
        } else {
            PrivilegedTetheringLegacy.stopWifiTethering(context)
        }
    }
}
