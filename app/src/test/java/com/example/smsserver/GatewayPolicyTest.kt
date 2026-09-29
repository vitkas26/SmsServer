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

    @Test fun destinationIsRoutedOnlyToItsConfiguredSimSlot() {
        assertTrue(SimRouting.slotFor("0880173333", "0880173333", "0550890380") == 0)
        assertTrue(SimRouting.slotFor("0550890380", "0880173333", "0550890380") == 1)
        assertTrue(SimRouting.slotFor("1234567890", "0880173333", "0550890380") == null)
    }

    @Test fun operatorPrefixesChooseLongestMatchAndNormalizeInternationalNumbers() {
        assertTrue(SimRouting.slotFor("+996550123456", "0880173333", "0550890380", listOf("055"), listOf("0550")) == 1)
        assertTrue(SimRouting.slotFor("996770123456", "0880173333", "0550890380", listOf("0770"), emptyList()) == 0)
        assertTrue(SimRouting.slotFor("0550890380", "0880173333", "0550890380", listOf("0550"), emptyList()) == 1)
        assertTrue(SimRouting.slotFor("+996700123456", "0880173333", "0550890380", listOf("0550"), emptyList()) == null)
    }

    @Test fun operatorPrefixInputRejectsMalformedCodes() {
        assertTrue(SimRouting.parsePrefixes("0550, 0770\n0880") == listOf("0550", "0770", "0880"))
        for (bad in listOf("550", "+996550", "0", "0550abc")) {
            try {
                SimRouting.parsePrefixes(bad)
                throw AssertionError("Accepted invalid prefix: $bad")
            } catch (_: IllegalArgumentException) {
                // Expected.
            }
        }
    }
}
