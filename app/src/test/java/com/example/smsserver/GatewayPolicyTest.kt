package com.example.smsserver

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayPolicyTest {
    @Test fun validNumbersAllowInternationalDigitsOnly() {
        assertTrue(GatewayPolicy.validPhone("+996555123456"))
        assertTrue(GatewayPolicy.validPhone("0550123456"))
        for (bad in listOf("", "12", "+", "123 456", "123;echo", "+1234567890123456")) {
            assertFalse(bad, GatewayPolicy.validPhone(bad))
        }
    }

    @Test fun messagesNeedTextAndHaveBoundedLength() {
        assertFalse(GatewayPolicy.validMessage(""))
        assertFalse(GatewayPolicy.validMessage("  \n"))
        assertTrue(GatewayPolicy.validMessage("Привет"))
        assertTrue(GatewayPolicy.validMessage("a".repeat(1600)))
        assertFalse(GatewayPolicy.validMessage("a".repeat(1601)))
    }

    @Test fun requestsRequireExactBearerToken() {
        val token = "test-token"
        assertTrue(GatewayPolicy.authorized("Bearer test-token", token))
        assertFalse(GatewayPolicy.authorized(null, token))
        assertFalse(GatewayPolicy.authorized("test-token", token))
        assertFalse(GatewayPolicy.authorized("Bearer test-tokex", token))
        assertFalse(GatewayPolicy.authorized("Bearer test-token-extra", token))
    }
}
