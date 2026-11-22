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
        val data = body?.let { HttpRequest.BodyPublishers.ofString(json.writeValueAsString(it)) }
        val request = HttpRequest.newBuilder(URI.create(base + path))
            .timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer $key")
            .method(method, data ?: HttpRequest.BodyPublishers.noBody())
        if (data != null) request.header("Content-Type", "application/json")
        val response = http.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString()).await()
        val status = response.statusCode()
        if (status !in 200..299) {
            val error = runCatching { json.readTree(response.body()).path("error").textValue() }.getOrNull()
            if (status == 400) throw ValidationException(error ?: "Некорректный запрос.")
            if (status >= 500) throw IOException("Platform HTTP $status")
            throw ServiceException(error ?: "Доступ к платформе отклонён (HTTP $status).")
        }
        @Suppress("UNCHECKED_CAST")
        if (T::class == Unit::class) return Unit as T
        return json.readValue<T>(response.body().ifBlank { "null" })
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
