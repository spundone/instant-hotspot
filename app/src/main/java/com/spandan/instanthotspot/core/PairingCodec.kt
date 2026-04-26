package com.spandan.instanthotspot.core

object PairingCodec {
    private const val SEP = "|"

    fun encode(parts: List<String>): ByteArray = parts.joinToString(SEP).toByteArray(Charsets.UTF_8)

    fun decode(raw: ByteArray): List<String> = raw.toString(Charsets.UTF_8).split(SEP)

    fun confirmationCode(nonce: String, secret: String): String {
        val sig = CommandSecurity.sign(nonce, secret)
        val clean = sig.uppercase().filter { it.isLetterOrDigit() }
        return clean.take(6).padEnd(6, 'X')
    }
}
