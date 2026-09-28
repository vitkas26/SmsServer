package com.example.smsserver

import java.security.MessageDigest

/** Rules shared by the HTTP gateway and its local tests. */
object GatewayPolicy {
    private val phonePattern = Regex("\\+?[0-9]{3,15}")

    fun validPhone(phone: String): Boolean = phonePattern.matches(phone)

    fun validMessage(message: String): Boolean = message.isNotBlank() && message.length <= 1600

    fun authorized(header: String?, token: String): Boolean {
        val supplied = header?.removePrefix("Bearer ") ?: return false
        if (supplied == header || supplied.length != token.length) return false
        return MessageDigest.isEqual(supplied.toByteArray(), token.toByteArray())
    }
}
