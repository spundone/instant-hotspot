package com.spandan.instanthotspot.core

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.KeyAgreement

object PairingCrypto {
    private const val CURVE_ALGO = "EC"
    private const val KEY_AGREEMENT_ALGO = "ECDH"

    fun generateKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance(CURVE_ALGO)
        generator.initialize(256)
        return generator.generateKeyPair()
    }

    fun encodePublicKey(publicKey: PublicKey): String {
        return Base64.getEncoder().withoutPadding().encodeToString(publicKey.encoded)
    }

    fun decodePublicKey(encoded: String): PublicKey {
        val bytes = Base64.getDecoder().decode(encoded)
        val spec = X509EncodedKeySpec(bytes)
        return KeyFactory.getInstance(CURVE_ALGO).generatePublic(spec)
    }

    fun deriveSharedSecretMaterial(privateKey: java.security.PrivateKey, peerPublicKey: PublicKey): ByteArray {
        val agreement = KeyAgreement.getInstance(KEY_AGREEMENT_ALGO)
        agreement.init(privateKey)
        agreement.doPhase(peerPublicKey, true)
        return agreement.generateSecret()
    }

    fun derivePairingSecret(
        secretMaterial: ByteArray,
        nonce: String,
        localPublicKeyB64: String,
        remotePublicKeyB64: String,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(secretMaterial)
        digest.update(nonce.toByteArray(Charsets.UTF_8))
        digest.update(localPublicKeyB64.toByteArray(Charsets.UTF_8))
        digest.update(remotePublicKeyB64.toByteArray(Charsets.UTF_8))
        val hash = digest.digest()
        return Base64.getEncoder().withoutPadding().encodeToString(hash)
    }

    fun shortAuthCode(
        secretMaterial: ByteArray,
        nonce: String,
        localPublicKeyB64: String,
        remotePublicKeyB64: String,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(secretMaterial)
        digest.update("SAS".toByteArray(Charsets.UTF_8))
        digest.update(nonce.toByteArray(Charsets.UTF_8))
        digest.update(localPublicKeyB64.toByteArray(Charsets.UTF_8))
        digest.update(remotePublicKeyB64.toByteArray(Charsets.UTF_8))
        val hash = digest.digest()
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val builder = StringBuilder()
        for (i in 0 until 6) {
            val index = (hash[i].toInt() and 0xFF) % chars.length
            builder.append(chars[index])
        }
        return builder.toString()
    }
}
