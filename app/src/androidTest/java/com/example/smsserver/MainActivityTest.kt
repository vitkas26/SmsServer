package com.example.smsserver

import android.Manifest
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.Socket

@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @get:Rule val rule = ActivityScenarioRule(MainActivity::class.java)

    @Test fun startsStoppedAndShowsRealMessageList() {
        rule.scenario.onActivity { activity ->
            assertEquals(
                activity.getString(R.string.server_stopped),
                activity.findViewById<TextView>(R.id.serverStatus).text.toString(),
            )
            assertNotNull(activity.findViewById<ListView>(R.id.messageList))
            assertTrue(activity.findViewById<TextView>(R.id.gatewayToken).text.contains("Ключ доступа:"))
            assertNotNull(activity.findViewById<Button>(R.id.inboxButton))
        }
    }

    @Test fun gatewayRejectsUnauthenticatedSmsAndCanRestart() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val gateway = GatewayServer(context, SmsRepository(context), "test-secret", { false }, {}, {})
        try {
            gateway.start()
            assertEquals(200, responseCode("/v1/health"))
            assertEquals(401, responseCode("/v1/sms", "{}"))
            gateway.stop()
            gateway.start()
            assertEquals(200, responseCode("/v1/health"))
        } finally {
            gateway.stop()
        }
    }

    @Test fun gatewayRejectsUnconfiguredNumberWithoutSending() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, Manifest.permission.SEND_SMS)
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, Manifest.permission.READ_PHONE_STATE)
        var rejectedPhone: String? = null
        val gateway = GatewayServer(context, SmsRepository(context), "test-secret", { false }, {}, { rejectedPhone = it })
        try {
            gateway.start()
            val response = responseCode(
                "/v1/sms",
                """{"phone":"1234567890","message":"test"}""",
                "test-secret",
                checkResponse = { body ->
                    assertTrue(body.contains("OPERATOR_CODE_NOT_CONFIGURED"))
                    assertTrue(body.contains("1234567890"))
                    assertTrue(body.contains("message"))
                },
            )
            assertEquals(422, response)
            assertEquals("1234567890", rejectedPhone)
        } finally {
            gateway.stop()
        }
    }

    private fun responseCode(
        path: String,
        body: String? = null,
        token: String? = null,
        checkResponse: ((String) -> Unit)? = null,
    ): Int {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", 9900), 3000)
            socket.soTimeout = 3000
            val bytes = body?.toByteArray() ?: byteArrayOf()
            val method = if (body == null) "GET" else "POST"
            val request = buildString {
                append("$method $path HTTP/1.1\r\n")
                append("Host: 127.0.0.1\r\n")
                append("Connection: close\r\n")
                if (token != null) append("Authorization: Bearer $token\r\n")
                if (body != null) append("Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\n")
                append("\r\n")
            }
            socket.getOutputStream().write(request.toByteArray() + bytes)
            val reader = socket.getInputStream().bufferedReader()
            val statusLine = reader.readLine()
            if (checkResponse != null) checkResponse(reader.readText())
            return statusLine.split(' ')[1].toInt()
        }
    }
}
