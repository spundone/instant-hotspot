package com.spandan.instanthotspot.core

import android.content.Context

/**
 * Host-mode onboarding gate: [su] access plus the Magisk module (or a priv-app
 * [TETHER_PRIVILEGED] install) so the user has flashed/installed the project module.
 */
object HostOnboarding {
    fun isHostSetupSatisfied(context: Context): Boolean {
        val app = context.applicationContext
        if (!HotspotController.hasRootPermission()) return false
        val inst = InstallSourceReader.read(app)
        return inst.fromMagiskModuleLayout || inst.tetherPrivilegedGranted
    }
}
