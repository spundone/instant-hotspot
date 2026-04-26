package com.spandan.instanthotspot.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PairingCodecTest {
    @Test
    fun encodeDecodeRoundTripPreservesParts() {
        val parts = listOf("PAIR_ECDH_INIT", "nonce123", "pubKeyAbc")
        val encoded = PairingCodec.encode(parts)
        val decoded = PairingCodec.decode(encoded)

        assertEquals(parts, decoded)
    }
}
