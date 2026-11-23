package com.tc.gymtrainer.bot

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.*

class WebhookServerTest {
    @Test
    fun `only signed requests reach the handler and failed updates remain retryable`() {
        val received = mutableListOf<Long>()
        val server = webhookServer(0, "test-secret", { update ->
            if (update.updateId.long == 8L) error("transient failure")
            received.add(update.updateId.long)
        })
        server.start()
        try {
            val client = HttpClient.newHttpClient()
            fun post(secret: String, body: String): Int {
                val req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${server.address.port}/telegram"))
                    .header("X-Telegram-Bot-Api-Secret-Token", secret).POST(HttpRequest.BodyPublishers.ofString(body)).build()
                return client.send(req, HttpResponse.BodyHandlers.discarding()).statusCode()
            }
            assertEquals(403, post("wrong", """{"update_id":7}"""))
            assertEquals(400, post("test-secret", "{"))
            assertEquals(204, post("test-secret", """{"update_id":7}"""))
            assertEquals(503, post("test-secret", """{"update_id":8}"""))
            assertEquals(listOf(7L), received)
        } finally {
            server.stop(0)
        }
    }
}
