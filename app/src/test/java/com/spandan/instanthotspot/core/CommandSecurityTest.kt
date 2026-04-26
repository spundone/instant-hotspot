package com.spandan.instanthotspot.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandSecurityTest {
    @Test
    fun verifyReturnsTrueForMatchingPayloadAndSecret() {
        val payload = "HOTSPOT_ON|12345|nonceA"
        val secret = "test-secret-123"
        val signature = CommandSecurity.sign(payload, secret)

        assertTrue(CommandSecurity.verify(payload, signature, secret))
    }

    @Test
    fun verifyReturnsFalseForDifferentPayload() {
        val secret = "test-secret-123"
        val signature = CommandSecurity.sign("HOTSPOT_ON|12345|nonceA", secret)

        assertFalse(CommandSecurity.verify("HOTSPOT_OFF|12345|nonceA", signature, secret))
    }
}
