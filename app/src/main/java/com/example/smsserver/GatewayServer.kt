package com.example.smsserver

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.gson.gson
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class SmsRequest(val phone: String = "", val message: String = "")
data class CallRequest(val phone: String = "")

/** Bound to loopback only. ADB port forwarding is required for a remote client. */
class GatewayServer(
    private val context: Context,
    private val repository: SmsRepository,
    private val token: String,
    private val placeCall: suspend (String) -> Boolean,
    private val onSent: () -> Unit,
) {
    @Volatile private var server: EmbeddedServer<*, *>? = null

    suspend fun start() = suspendCancellableCoroutine<Unit> { completion ->
        Thread({
            try {
                check(server == null)
                val candidate = embeddedServer(CIO, host = "127.0.0.1", port = 9900) {
            install(ContentNegotiation) { gson() }
            routing {
                route("/v1") {
                    get("/health") { call.respond(mapOf("status" to "running")) }

                    get("/messages") {
                        if (!GatewayPolicy.authorized(call.request.headers["Authorization"], token)) {
                            call.respond(HttpStatusCode.Unauthorized)
                            return@get
                        }
                        val kind = call.request.queryParameters["kind"] ?: "inbox"
                        if (kind != "inbox" && kind != "sent") {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "kind must be inbox or sent"))
                            return@get
                        }
                        if (kind == "inbox" && ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
                            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "READ_SMS permission is missing"))
                            return@get
                        }
                        val fromInput = call.request.queryParameters["from"]
                        val toInput = call.request.queryParameters["to"]
                        if ((fromInput != null && fromInput.toLongOrNull() == null) ||
                            (toInput != null && toInput.toLongOrNull() == null)) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Dates must be epoch milliseconds"))
                            return@get
                        }
                        val today = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        val tomorrow = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        val from = fromInput?.toLong() ?: today
                        val to = toInput?.toLong() ?: tomorrow
                        if (from >= to) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid date range"))
                            return@get
                        }
                        try {
                            val messages = if (kind == "inbox") {
                                withContext(Dispatchers.IO) { repository.inbox(Date(from), Date(to)) }
                            } else {
                                repository.sent(Date(from), Date(to))
                            }
                            call.respond(mapOf("messages" to messages))
                        } catch (_: Exception) {
                            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Could not read messages"))
                        }
                    }

                    post("/sms") {
                        if (!GatewayPolicy.authorized(call.request.headers["Authorization"], token)) {
                            call.respond(HttpStatusCode.Unauthorized)
                            return@post
                        }
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "SEND_SMS permission is missing"))
                            return@post
                        }
                        val request = try { call.receive<SmsRequest>() } catch (_: Exception) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid JSON"))
                            return@post
                        }
                        if (!GatewayPolicy.validPhone(request.phone) || !GatewayPolicy.validMessage(request.message)) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid phone or message"))
                            return@post
                        }
                        try {
                            withContext(Dispatchers.IO) { repository.send(request.phone, request.message) }
                            onSent()
                            call.respond(HttpStatusCode.Accepted, mapOf("status" to "queued"))
                        } catch (_: Exception) {
                            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "SMS could not be queued"))
                        }
                    }

                    post("/call") {
                        if (!GatewayPolicy.authorized(call.request.headers["Authorization"], token)) {
                            call.respond(HttpStatusCode.Unauthorized)
                            return@post
                        }
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
                            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "CALL_PHONE permission is missing"))
                            return@post
                        }
                        val request = try { call.receive<CallRequest>() } catch (_: Exception) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid JSON"))
                            return@post
                        }
                        if (!GatewayPolicy.validPhone(request.phone)) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid phone"))
                            return@post
                        }
                        val placed = try { placeCall(request.phone) } catch (_: Exception) { false }
                        if (placed) call.respond(HttpStatusCode.Accepted, mapOf("status" to "started"))
                        else call.respond(HttpStatusCode.Conflict, mapOf("error" to "Could not start call"))
                    }
                }
            }
        }
                candidate.start(wait = false)
                server = candidate
                if (completion.isActive) completion.resume(Unit) else {
                    candidate.stop(100, 1000)
                    server = null
                }
            } catch (error: Exception) {
                if (completion.isActive) completion.resumeWithException(error)
            }
        }, "sms-gateway-start").start()
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        server?.stop(100, 1000)
        server = null
    }
}
