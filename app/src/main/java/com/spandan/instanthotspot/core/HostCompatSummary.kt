package com.spandan.instanthotspot.core

import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build

object HostCompatSummary {
    /**
     * Human-readable block for the host device (install type, API, Bluetooth, etc.).
     */
    fun build(context: Context): String {
        val install = InstallSourceReader.read(context)
        val ble = bleLine(context)
        return buildString {
            append("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            append('\n')
            append(install.summaryLine)
            append("\nTETHER_PRIVILEGED: ")
            append(
                if (install.tetherPrivilegedGranted) {
                    "granted (priv-app / module allowlist — use project Magisk zip on host)"
                } else {
                    "not held — flash the Magisk module on the host; root alone is usually insufficient"
                },
            )
            append("\nModule/Magisk path hint: ")
            append(if (install.fromMagiskModuleLayout) "yes" else "no / unknown")
            append('\n')
            append(ble)
        }
    }

    private fun bleLine(context: Context): String {
        val m = context.getSystemService(BluetoothManager::class.java) ?: return "BLE: no BluetoothManager"
        val a = m.adapter
        if (a == null) {
            return "BLE: no adapter"
        }
        if (!a.isEnabled) {
            return "BLE: adapter present (Bluetooth off — turn on to use this app)"
        }
        return "BLE: adapter on"
    }
}
