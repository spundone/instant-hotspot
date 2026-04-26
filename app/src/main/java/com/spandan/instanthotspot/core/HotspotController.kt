package com.spandan.instanthotspot.core

import android.content.Context
import com.spandan.instanthotspot.tethering.PrivilegedTethering
import java.io.BufferedReader
import java.io.InputStreamReader

object HotspotController {
    /**
     * Order: prefer `cmd connectivity` (same subsystem as [android.net.TetheringManager] / Wi-Fi tethering)
     * over `cmd wifi` / `ndc` so sideload+root users match the "dialed in" TETHER_PRIVILEGED path as closely
     * as shell allows.
     */
    private val startCommands = listOf(
        "/system/bin/cmd connectivity tether start wifi",
        "/system/bin/cmd connectivity tether start",
        "/system/bin/cmd connectivity tether start 0",
        "/system/bin/cmd wifi start-softap",
        "/system/bin/ndc tether start",
    )
    private val stopCommands = listOf(
        "/system/bin/cmd connectivity tether stop wifi",
        "/system/bin/cmd connectivity tether stop",
        "/system/bin/cmd connectivity tether stop 0",
        "/system/bin/cmd wifi stop-softap",
        "/system/bin/ndc tether stop",
    )
    private val configCommands = listOf(
        "/system/bin/cmd wifi get-softap-config",
        "/system/bin/cmd connectivity tether status",
        "/system/bin/ndc tether status",
    )
    private var lastRootCheckAtMs: Long = 0L
    private var lastRootCheckResult: Boolean = false
    @Volatile private var lastExecutionReport: String = "No hotspot command executed yet."

    /**
     * 1) If the app has [TETHER_PRIVILEGED], use [TetheringManager] (no root shell), e.g. priv-app
     *    install, or optional systemless module that grants the permission.
     * 2) Otherwise, typical **sideload + root** flow: run `cmd` / `ndc` as root (default for this app)
     *    without any Magisk module.
     */
    fun enableHotspot(context: Context): Boolean {
        val app = context.applicationContext
        if (PrivilegedTethering.canUse(app) && PrivilegedTethering.startWifiTetheringSync(app)) {
            lastExecutionReport = "SUCCESS | TETHER_PRIVILEGED TetheringManager.startTethering"
            return true
        }
        return enableHotspotRoot()
    }

    fun disableHotspot(context: Context): Boolean {
        val app = context.applicationContext
        if (PrivilegedTethering.canUse(app) && PrivilegedTethering.stopWifiTethering(app)) {
            lastExecutionReport = "SUCCESS | TETHER_PRIVILEGED TetheringManager.stopTethering"
            return true
        }
        return disableHotspotRoot()
    }

    fun enableHotspotRoot(): Boolean = runFirstSuccessful(startCommands)

    fun disableHotspotRoot(): Boolean = runFirstSuccessful(stopCommands)

    fun lastHotspotExecutionReport(): String = lastExecutionReport

    fun hasRootPermission(): Boolean {
        val now = System.currentTimeMillis()
        // Keep checks responsive while avoiding repeated `su` prompts.
        if (now - lastRootCheckAtMs < ROOT_CHECK_CACHE_MS) {
            return lastRootCheckResult
        }
        val granted = try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            val exitCode = process.waitFor()
            exitCode == 0 && output.contains("uid=0")
        } catch (_: Exception) {
            false
        }
        lastRootCheckAtMs = now
        lastRootCheckResult = granted
        return granted
    }

    fun hotspotConfigSummary(): String {
        if (!hasRootPermission()) return "Root unavailable for config"
        val settingsSsid = runAsRootForOutput("/system/bin/settings get global soft_ap_ssid")
            .lineSequence().firstOrNull()?.trim().orEmpty()
        val settingsPass = runAsRootForOutput("/system/bin/settings get global soft_ap_passphrase")
            .lineSequence().firstOrNull()?.trim().orEmpty()
        if (settingsSsid.isNotBlank() && settingsSsid.lowercase() != "null" &&
            settingsPass.isNotBlank() && settingsPass.lowercase() != "null"
        ) {
            return "ssid=$settingsSsid ; password=$settingsPass ; source=settings-global"
        }
        for (command in configCommands) {
            val output = runAsRootForOutput(command)
            if (output.isNotBlank()) {
                return output.lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .take(8)
                    .joinToString(" ; ")
            }
        }
        return "Hotspot config unavailable"
    }

    private fun runFirstSuccessful(commands: List<String>): Boolean {
        if (!hasRootPermission()) {
            lastExecutionReport = "Root check failed before hotspot command execution."
            return false
        }
        val attempts = mutableListOf<String>()
        for (command in commands) {
            val attempt = runAsRootDetailed(command)
            attempts += "${attempt.command} => exit=${attempt.exitCode}, ok=${attempt.success}" +
                (if (attempt.stderr.isNotBlank()) ", err=${attempt.stderr.take(120)}" else "")
            if (attempt.success) {
                lastExecutionReport = "SUCCESS | " + attempts.joinToString(" | ")
                return true
            }
        }
        lastExecutionReport = "FAILED | " + attempts.joinToString(" | ")
        return false
    }

    private fun runAsRoot(command: String): Boolean {
        // Back-compat helper (unused by newer execution path).
        return runAsRootWithStrategies(command, label = "legacy").any { it.success }
    }

    private data class CommandAttempt(
        val label: String,
        val command: String,
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val success: Boolean,
    )

    private fun runAsRootDetailed(command: String): CommandAttempt {
        val attempts = runAsRootWithStrategies(command, label = "cmd")
        return attempts.lastOrNull()
            ?: CommandAttempt(
                label = "none",
                command = command,
                exitCode = -1,
                stdout = "",
                stderr = "no attempts",
                success = false,
            )
    }

    private fun runAsRootForOutput(command: String): String {
        val attempts = runAsRootWithStrategies(command, label = "cfg")
        for (a in attempts) {
            if (a.success && a.stdout.isNotBlank()) {
                return a.stdout.trim()
            }
        }
        return ""
    }

    private const val ROOT_CHECK_CACHE_MS = 5_000L

    private fun runAsRootWithStrategies(
        fullCommand: String,
        label: String,
    ): List<CommandAttempt> {
        val results = ArrayList<CommandAttempt>()
        val strategies = listOf(
            { arrayOf("su", "0", "sh", "-c", fullCommand) },
            { arrayOf("su", "root", "sh", "-c", fullCommand) },
            { arrayOf("su", "-c", fullCommand) },
        )
        for ((idx, strategy) in strategies.withIndex()) {
            val attempt = runProcess(strategy(), "$label#${idx + 1}", fullCommand)
            results.add(attempt)
            if (attempt.success) return results
        }
        return results
    }

    private fun runProcess(
        args: Array<String>,
        label: String,
        command: String,
    ): CommandAttempt {
        return try {
            val process = Runtime.getRuntime().exec(args)
            val stdout = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }.trim()
            val stderr = BufferedReader(InputStreamReader(process.errorStream)).use { it.readText() }.trim()
            val exitCode = process.waitFor()
            CommandAttempt(
                label = label,
                command = command,
                exitCode = exitCode,
                stdout = stdout,
                stderr = stderr,
                success = exitCode == 0,
            )
        } catch (e: Exception) {
            CommandAttempt(
                label = label,
                command = command,
                exitCode = -1,
                stdout = "",
                stderr = e.message ?: "exception",
                success = false,
            )
        }
    }
}
