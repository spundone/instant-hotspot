package com.spandan.instanthotspot.core

import java.nio.charset.StandardCharsets

/**
 * Wire format for the BLE "state" characteristic: UTF-8, `ap=0|1|2` (0=off, 1=on, 2=unknown).
 */
object HostStateCodec {
    const val PREF_ON = 1
    const val PREF_OFF = 0
    const val PREF_UNKNOWN = -1

    fun encodeFromProbe(probe: HotspotStateProbe.ApState): ByteArray {
        val v = when (probe) {
            HotspotStateProbe.ApState.UP -> 1
            HotspotStateProbe.ApState.DOWN -> 0
            HotspotStateProbe.ApState.UNKNOWN -> 2
        }
        return "ap=$v".toByteArray(StandardCharsets.UTF_8)
    }

    /**
     * Returns [PREF_ON], [PREF_OFF], or [PREF_UNKNOWN].
     */
    fun parseClient(bytes: ByteArray?): Int {
        if (bytes == null || bytes.isEmpty()) return PREF_UNKNOWN
        val s = String(bytes, StandardCharsets.UTF_8).trim().lowercase()
        val m = Regex("""\bap\s*=\s*([012u])""").find(s) ?: return PREF_UNKNOWN
        return when (m.groupValues[1]) {
            "1" -> PREF_ON
            "0" -> PREF_OFF
            "2", "u" -> PREF_UNKNOWN
            else -> PREF_UNKNOWN
        }
    }
}
