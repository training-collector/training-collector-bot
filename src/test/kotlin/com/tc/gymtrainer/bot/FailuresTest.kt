package com.tc.gymtrainer.bot

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FailuresTest {
    @Test
    fun `a long poll cut short by the HTTP client is told apart from real failures`() {
        val timeout = io.ktor.client.plugins.HttpRequestTimeoutException("https://api.telegram.org/bot<token>/getUpdates", 25_000)
        assertEquals(true, RuntimeException("wrapped", timeout).isRequestTimeout())
        assertEquals(false, java.io.IOException("Connection refused").isRequestTimeout())
        assertEquals(true, MAX_POLL_TIMEOUT.seconds < 25)
    }

    @Test
    fun `a logged failure names its cause without the bot token or what the SDK was sending`() {
        val token = "123456:AAH-secret_token"
        val cause = java.io.IOException("Request timeout has expired [url=https://api.telegram.org/bot$token/sendMessage]")
        val logged = safeFailure(UpdateFailure("update=5 chat=77 attempt=2", IllegalStateException("wrapped $token", cause)), token)
        val text = logged.message.orEmpty()
        assertEquals(false, token in text)
        assertEquals(false, "AAH-secret_token" in text)
        assertEquals(true, text.startsWith("UpdateFailure update=5 chat=77 attempt=2 <- IllegalStateException wrapped <token> <- IOException"))
        assertEquals(true, "bot<token>/sendMessage" in text)
        assertEquals(cause.stackTrace.toList(), logged.stackTrace.toList())
    }
}
