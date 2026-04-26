package com.spandan.instanthotspot.core

enum class HotspotCommand {
    HOTSPOT_ON,
    HOTSPOT_OFF,
    HOTSPOT_TOGGLE,
    /** Controller rings the host (sound + vibration on host). */
    RING_REMOTE,
    /** Set cellular preferred mode to 5G / NR only (root + device-dependent). */
    NET_5G_ONLY,
    /** Restore [AppPrefs] saved preferred_network_mode. */
    NET_REVERT_MODE,
}

data class CommandEnvelope(
    val command: HotspotCommand,
    val timestampMs: Long = System.currentTimeMillis(),
    val nonce: String,
    val signature: String,
)
