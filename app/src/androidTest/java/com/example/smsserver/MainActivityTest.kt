package com.example.smsserver

import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
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
        val gateway = GatewayServer(context, SmsRepository(context), "test-secret", { false }, {})
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

    private fun responseCode(path: String, body: String? = null): Int {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", 9900), 3000)
            socket.soTimeout = 3000
            val bytes = body?.toByteArray() ?: byteArrayOf()
            val method = if (body == null) "GET" else "POST"
            val request = buildString {
                append("$method $path HTTP/1.1\r\n")
                append("Host: 127.0.0.1\r\n")
                append("Connection: close\r\n")
                if (body != null) append("Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\n")
                append("\r\n")
            }
            socket.getOutputStream().write(request.toByteArray() + bytes)
            val statusLine = socket.getInputStream().bufferedReader().readLine()
            return statusLine.split(' ')[1].toInt()
        }
    }
}
