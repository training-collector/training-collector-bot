package com.tc.gymtrainer.bot

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.inmo.tgbotapi.types.update.abstracts.Update
import dev.inmo.tgbotapi.types.update.abstracts.UpdateDeserializationStrategy
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.Executors

internal fun webhookServer(
    port: Int, secret: String, update: suspend (Update) -> Unit,
): HttpServer {
    require(secret.matches(Regex("[A-Za-z0-9_-]{1,256}"))) { "Set TELEGRAM_WEBHOOK_SECRET" }
    val json = Json { ignoreUnknownKeys = true }
    val log = LoggerFactory.getLogger("TelegramWebhook")
    return HttpServer.create(InetSocketAddress("0.0.0.0", port), 0).apply {
        executor = Executors.newVirtualThreadPerTaskExecutor()
        createContext("/") { call ->
            call.use {
                try {
                    when {
                        call.requestURI.path == "/health" && call.requestMethod == "GET" -> call.reply(200, "OK")
                        call.requestMethod != "POST" -> call.reply(405)
                        call.requestURI.path != "/telegram" -> call.reply(404)
                        !matches(secret, call.requestHeaders.getFirst("X-Telegram-Bot-Api-Secret-Token")) -> call.reply(403)
                        else -> {
                            val bytes = call.requestBody.readNBytes(1_048_577)
                            if (bytes.size > 1_048_576) call.reply(413)
                            else {
                                val event = try { json.decodeFromString(UpdateDeserializationStrategy, bytes.decodeToString()) }
                                    catch (_: SerializationException) { call.reply(400); return@use }
                                runBlocking { update(event) }
                                call.reply(204)
                            }
                        }
                    }
                } catch (e: Exception) {
                    // SDK exception messages can include chat data and credentials.
                    log.warn("Webhook processing failed ({})", e.javaClass.simpleName)
                    call.reply(503)
                }
            }
        }
    }
}

private fun matches(expected: String, supplied: String?) = supplied != null &&
    MessageDigest.isEqual(expected.toByteArray(), supplied.toByteArray())

private fun HttpExchange.reply(status: Int, body: String = "") {
    val bytes = body.toByteArray()
    responseHeaders.set("Cache-Control", "no-store")
    sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
    if (bytes.isNotEmpty()) responseBody.write(bytes)
}
