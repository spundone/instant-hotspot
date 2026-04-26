package com.spandan.instanthotspot.core

enum class HotspotCommand {
    HOTSPOT_ON,
    HOTSPOT_OFF,
    HOTSPOT_TOGGLE,
}

data class CommandEnvelope(
    val command: HotspotCommand,
    val timestampMs: Long = System.currentTimeMillis(),
    val nonce: String,
    val signature: String,
)
