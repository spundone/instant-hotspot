package com.spandan.instanthotspot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingCryptoTest {
    @Test
    fun ecdhDerivedPairingSecretsMatchOnBothSides() {
        val nonce = "nonce-xyz-123"
        val a = PairingCrypto.generateKeyPair()
        val b = PairingCrypto.generateKeyPair()
        val aPub = PairingCrypto.encodePublicKey(a.public)
        val bPub = PairingCrypto.encodePublicKey(b.public)

        val aMaterial = PairingCrypto.deriveSharedSecretMaterial(a.private, PairingCrypto.decodePublicKey(bPub))
        val bMaterial = PairingCrypto.deriveSharedSecretMaterial(b.private, PairingCrypto.decodePublicKey(aPub))

        val aSecret = PairingCrypto.derivePairingSecret(aMaterial, nonce, aPub, bPub)
        val bSecret = PairingCrypto.derivePairingSecret(bMaterial, nonce, aPub, bPub)
        assertEquals(aSecret, bSecret)

        val aCode = PairingCrypto.shortAuthCode(aMaterial, nonce, aPub, bPub)
        val bCode = PairingCrypto.shortAuthCode(bMaterial, nonce, aPub, bPub)
        assertEquals(aCode, bCode)
        assertEquals(6, aCode.length)
        assertTrue(aCode.all { it.isUpperCase() || it.isDigit() })
    }

    @Test
    fun sasChangesWhenNonceChanges() {
        val a = PairingCrypto.generateKeyPair()
        val b = PairingCrypto.generateKeyPair()
        val aPub = PairingCrypto.encodePublicKey(a.public)
        val bPub = PairingCrypto.encodePublicKey(b.public)
        val material = PairingCrypto.deriveSharedSecretMaterial(a.private, PairingCrypto.decodePublicKey(bPub))

        val code1 = PairingCrypto.shortAuthCode(material, "nonce-one", aPub, bPub)
        val code2 = PairingCrypto.shortAuthCode(material, "nonce-two", aPub, bPub)
        assertNotEquals(code1, code2)
    }
}
