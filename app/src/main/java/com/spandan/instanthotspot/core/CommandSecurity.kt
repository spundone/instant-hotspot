package com.spandan.instanthotspot.core

import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object CommandSecurity {
    private const val HMAC_ALGO = "HmacSHA256"

    fun sign(payload: String, secret: String): String {
        val mac = Mac.getInstance(HMAC_ALGO)
        mac.init(SecretKeySpec(secret.toByteArray(), HMAC_ALGO))
        return Base64.getEncoder().withoutPadding().encodeToString(mac.doFinal(payload.toByteArray()))
    }

    fun verify(payload: String, signature: String, secret: String): Boolean {
        return sign(payload, secret) == signature
    }
}
