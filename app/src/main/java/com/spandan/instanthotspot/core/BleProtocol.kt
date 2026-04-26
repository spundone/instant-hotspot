package com.spandan.instanthotspot.core

import java.util.UUID

object BleProtocol {
    // Fixed UUIDs so both devices can discover and communicate reliably.
    val SERVICE_UUID: UUID = UUID.fromString("6d8f3e10-a896-4a2f-b63a-9c1c6ff00101")
    val COMMAND_CHAR_UUID: UUID = UUID.fromString("6d8f3e10-a896-4a2f-b63a-9c1c6ff00102")
    val PAIRING_CHAR_UUID: UUID = UUID.fromString("6d8f3e10-a896-4a2f-b63a-9c1c6ff00103")
    val CONFIG_CHAR_UUID: UUID = UUID.fromString("6d8f3e10-a896-4a2f-b63a-9c1c6ff00104")
    /** Read current soft AP state: UTF-8 `ap=0|1|2` (see [HostStateCodec]). */
    val STATE_CHAR_UUID: UUID = UUID.fromString("6d8f3e10-a896-4a2f-b63a-9c1c6ff00105")
}

object CommandCodec {
    private const val SEP = "|"

    fun payload(command: HotspotCommand, timestampMs: Long, nonce: String): String {
        return listOf(command.name, timestampMs.toString(), nonce).joinToString(SEP)
    }

    fun encode(envelope: CommandEnvelope): ByteArray {
        val wire = listOf(
            envelope.command.name,
            envelope.timestampMs.toString(),
            envelope.nonce,
            envelope.signature,
        ).joinToString(SEP)
        return wire.toByteArray(Charsets.UTF_8)
    }

    fun decode(raw: ByteArray): CommandEnvelope? {
        return try {
            val parts = raw.toString(Charsets.UTF_8).split(SEP)
            if (parts.size != 4) return null
            CommandEnvelope(
                command = HotspotCommand.valueOf(parts[0]),
                timestampMs = parts[1].toLong(),
                nonce = parts[2],
                signature = parts[3],
            )
        } catch (_: Exception) {
            null
        }
    }
}
