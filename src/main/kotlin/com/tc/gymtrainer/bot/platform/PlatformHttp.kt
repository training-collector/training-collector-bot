package com.tc.gymtrainer.bot.platform

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.IOException
import com.tc.gymtrainer.bot.DialogTurn
import kotlinx.coroutines.currentCoroutineContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

open class ServiceException(message: String) : RuntimeException(message)
class ValidationException(message: String) : ServiceException(message)

internal class PlatformHttp(url: String, private val key: String) {
    private val base = url.trimEnd('/') + "/internal/v1/training"
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val json = jacksonObjectMapper().registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)

    init { require(key.isNotBlank()) { "Missing platform service key" } }

    suspend inline fun <reified T> call(method: String, path: String, body: Any? = null): T {
        val payload = body?.let { json.writeValueAsString(it) }
        val turn = currentCoroutineContext()[DialogTurn]
        val raw = if (turn == null || path.startsWith("/updates/")) exchange(method, path, payload, null) else {
            val signature = java.util.UUID.nameUUIDFromBytes("$method $path $payload".toByteArray()).toString()
            turn.effect(signature) { key -> exchange(method, path, payload, key.toString()) }
        }
        val status = raw.substringBefore(' ').toInt()
        val responseBody = raw.substringAfter(' ')
        if (status !in 200..299) {
            val error = runCatching { json.readTree(responseBody).path("error").textValue() }.getOrNull()
            if (status == 400) throw ValidationException(error ?: "Некорректный запрос.")
            throw ServiceException(error ?: "Доступ к платформе отклонён (HTTP $status).")
        }
        @Suppress("UNCHECKED_CAST")
        if (T::class == Unit::class) return Unit as T
        return json.readValue<T>(responseBody.ifBlank { "null" })
    }

    private suspend fun exchange(method: String, path: String, payload: String?, keyId: String?): String {
        val request = HttpRequest.newBuilder(URI.create(base + path))
            .timeout(Duration.ofSeconds(25)).header("Authorization", "Bearer $key")
            .method(method, payload?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
        if (payload != null) request.header("Content-Type", "application/json")
        if (keyId != null && method != "GET") request.header("Idempotency-Key", keyId)
        val response = http.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString()).await()
        if (response.statusCode() >= 500) throw IOException("Platform HTTP ${response.statusCode()}")
        return "${response.statusCode()} ${response.body()}"
    }

    suspend fun keepRegistered(scope: CoroutineScope, username: String) {
        suspend fun register() = call<Unit>("POST", "/identity", mapOf("username" to username))
        val log = LoggerFactory.getLogger(PlatformHttp::class.java)
        while (scope.isActive) {
            try {
                register()
                break
            } catch (e: IOException) {
                log.warn("Платформа пока недоступна, повтор через 5 секунд")
                delay(5000)
            }
        }
        scope.launch {
            while (isActive) {
                delay(5000)
                try {
                    register()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("Не удалось обновить имя бота ({})", e.javaClass.simpleName)
                }
            }
        }
    }
}
