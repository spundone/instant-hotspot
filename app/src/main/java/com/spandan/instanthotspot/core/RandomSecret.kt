package com.spandan.instanthotspot.core

import java.security.SecureRandom

object RandomSecret {
    private val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    fun newReadable(): String {
        val random = SecureRandom()
        return buildString {
            repeat(24) { append(alphabet[random.nextInt(alphabet.length)]) }
        }
    }
}
