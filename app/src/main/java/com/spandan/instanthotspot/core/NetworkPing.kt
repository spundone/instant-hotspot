package com.spandan.instanthotspot.core

object NetworkPing {
    /**
     * Run ICMP ping to [host] (toybox/busybox). Captures combined stdout+stderr; may fail on
     * devices without a shell `ping` (returns error text only).
     */
    fun runPing4(host: String = "1.1.1.1"): String = runCatching {
        val safeHost = if (host.matches(Regex("^[0-9a-zA-Z\\.:]+$"))) host else "1.1.1.1"
        val p = ProcessBuilder(
            "sh",
            "-c",
            "ping -c 5 -W 3 $safeHost 2>&1; echo exitcode=\$?",
        )
            .redirectErrorStream(true)
            .start()
        val text = p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor()
        text
    }.getOrElse { e -> (e.message ?: e.toString()) + "\n" + e.stackTraceToString() }
}
