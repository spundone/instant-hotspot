package com.spandan.instanthotspot.core

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

data class InstallSource(
    val summaryLine: String,
    val fromMagiskModuleLayout: Boolean,
    val isSystemPrivilegedApp: Boolean,
    val tetherPrivilegedGranted: Boolean,
    val apksPath: String,
)

object InstallSourceReader {

    const val MAGISK_MODULE_ID = "instanthotspot"
    const val MODULE_MARKER = "ih_marks_magisk"
    const val PRIMARY_MARKER_PATH = "/data/adb/modules/$MAGISK_MODULE_ID/$MODULE_MARKER"

    fun read(context: Context): InstallSource {
        val appInfo = try {
            context.packageManager.getApplicationInfo(context.packageName, 0)
        } catch (_: Exception) {
            return InstallSource("Install: unknown", false, false, false, "")
        }
        val path = listOf(
            appInfo.sourceDir,
            appInfo.publicSourceDir,
        ).filterNotNull().joinToString(" | ")
        val inPrivPath = path.contains("priv-app")
        val privRuntime = isPrivilegedAppMethod(appInfo)
        val tetherOk = hasTetherPrivileged(context)
        val magisk = pathIndicatesMagiskLayout(path) || (HotspotController.hasRootPermission() && rootFileExists(
            PRIMARY_MARKER_PATH,
        ))
        val label = when {
            magisk && inPrivPath -> "Install: Magisk systemless (priv-app), marker or module path"
            inPrivPath && tetherOk -> "Install: system/priv-app (TETHER_PRIVILEGED granted)"
            inPrivPath -> "Install: system/priv-app (TETHER_PRIVILEGED not granted; allowlist/ROM?)"
            tetherOk && !inPrivPath -> "Install: user app, but TETHER_PRIVILEGED granted (unusual)"
            else -> "Install: user/Play or sideload (no priv-app path)"
        }
        return InstallSource(
            summaryLine = label,
            fromMagiskModuleLayout = magisk,
            isSystemPrivilegedApp = inPrivPath || privRuntime,
            tetherPrivilegedGranted = tetherOk,
            apksPath = path,
        )
    }

    private fun isPrivilegedAppMethod(appInfo: ApplicationInfo): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return false
        }
        return runCatching {
            ApplicationInfo::class.java
                .getMethod("isPrivilegedApp")
                .invoke(appInfo) as Boolean
        }.getOrDefault(false)
    }

    private fun hasTetherPrivileged(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            "android.permission.TETHER_PRIVILEGED",
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun pathIndicatesMagiskLayout(path: String): Boolean {
        if (path.contains("/data/adb/modules/") || path.contains("modules_update")) return true
        if (path.contains(MAGISK_MODULE_ID, ignoreCase = true)) return true
        return false
    }

    private fun rootFileExists(absolute: String): Boolean {
        if (!HotspotController.hasRootPermission()) return false
        return try {
            val p = ProcessBuilder("su", "-c", "test -e $absolute && echo ok")
                .redirectErrorStream(true)
                .start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor() == 0 && out.contains("ok")
        } catch (_: Exception) {
            false
        }
    }
}
