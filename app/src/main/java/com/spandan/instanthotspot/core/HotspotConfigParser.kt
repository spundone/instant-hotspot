package com.spandan.instanthotspot.core

import org.json.JSONObject

/**
 * Best-effort SSID/password extraction from [HotspotController.hotspotConfigSummary] and
 * similar ROM-specific dumps (Settings keys, get-softap-config, tether status, JSON fragments).
 */
object HotspotConfigParser {

    fun parseSsidPassword(raw: String): HotspotCredentials? {
        if (raw.isBlank()) return null
        val normalized = raw.replace("\r", "\n")
        fromSettingsGlobalLine(normalized)?.let { return it }
        fromJsonFragment(normalized)?.let { return it }
        fromKeyValueScans(normalized)?.let { return it }
        return null
    }

    private val nullish = setOf("null", "undefined", "none")

    private fun isNullish(s: String) = s.isBlank() || s.lowercase() in nullish

    private fun fromSettingsGlobalLine(text: String): HotspotCredentials? {
        // Single blob or "ssid=.. ; password=.. ; source=...": scan tokens
        var ssid: String? = null
        var pass: String? = null
        val segments = text.split(";", "\n", "|").map { it.trim() }.filter { it.isNotEmpty() }
        for (t in segments) {
            Regex("""(?i)ssid\s*[:=]\s*([^;|]+)""").find(t)?.groupValues?.get(1)?.trim()?.let { s ->
                if (!isNullish(s)) ssid = unquote(s)
            }
            Regex("""(?i)password\s*[:=]\s*([^;|]+)""").find(t)?.groupValues?.get(1)?.trim()?.let { p ->
                if (!isNullish(p)) pass = unquote(p)
            }
        }
        if (ssid == null) {
            ssid = Regex("""(?i)soft_ap_ssid[=\s]+['"]?([^'"\n;]+)['"]?""").find(text)?.groupValues?.get(1)
        }
        if (pass == null) {
            pass = Regex("""(?i)soft_ap_passphrase[=\s]+['"]?([^'"\n;]+)['"]?""").find(text)?.groupValues?.get(1)
        }
        val s1 = ssid
        val p1 = pass
        return if (s1 != null && p1 != null && !isNullish(s1) && !isNullish(p1)) {
            HotspotCredentials(s1.trim(), p1.trim())
        } else {
            null
        }
    }

    private fun fromJsonFragment(text: String): HotspotCredentials? {
        for (m in Regex("""\{[^{}]*}""").findAll(text)) {
            val o = runCatching { JSONObject(m.value) }.getOrNull() ?: continue
            val s = o.optString("Ssid", o.optString("ssid", o.optString("WifiSsid", "")))
            val p = o.optString("PreSharedKey", o.optString("preSharedKey", o.optString("Passphrase", "")))
            if (s.isNotBlank() && p.isNotBlank() && !isNullish(s) && !isNullish(p)) {
                return HotspotCredentials(s, p)
            }
        }
        return null
    }

    private val ssidRes = listOf(
        Regex("""(?i)\bssid\b\s*[:=]\s*['"`]([^'"`\n,;]+)['"`]?"""),
        Regex("""(?i)\b(?:WifiApConfig)?[Ss]sid\b\s*[:=]\s*['"`]([^'"`\n,;]+)['"`]?"""),
        Regex("""(?i)\b(?:m)?[Ss]sid\b\s*[:=]\s*['"`]([^'"`\n,;]+)['"`]?"""),
        Regex("""(?i)\b(?:NetworkName|networkName)\b\s*[:=]\s*['"`]([^'"`\n,;]+)['"`]?"""),
        Regex("""(?i)SSID\s*=\s*['"`]([^'"`\n,;]+)['"`]?"""),
    )
    private val passRes = listOf(
        Regex("""(?i)\b(?:passphrase|PreSharedKey|preSharedKey|Password|PSK|psk|wpa[._-]?key|mPreSharedKey)\b\s*[:=]\s*['"`]([^'"`\n,;]+)['"`]?"""),
        Regex("""(?i)\bPassphrase\s*:\s*(\S.+)"""),
    )

    private fun fromKeyValueScans(text: String): HotspotCredentials? {
        var ssid: String? = null
        for (r in ssidRes) {
            val g = r.find(text)?.groupValues?.getOrNull(1)?.trim() ?: continue
            if (!isNullish(g)) {
                ssid = unquote(g)
                break
            }
        }
        var pass: String? = null
        for (r in passRes) {
            val g = r.find(text)?.groupValues?.getOrNull(1)?.trim() ?: continue
            if (!isNullish(g)) {
                pass = unquote(g)
                break
            }
        }
        val s2 = ssid
        val p2 = pass
        return if (s2 != null && p2 != null) HotspotCredentials(s2, p2) else null
    }

    private fun unquote(s: String): String {
        var t = s.trim()
        if (t.length >= 2) {
            val q = t.first()
            if ((q == '"' || q == '\'' || q == '`') && t.last() == q) {
                t = t.substring(1, t.length - 1)
            }
        }
        return t
    }
}
