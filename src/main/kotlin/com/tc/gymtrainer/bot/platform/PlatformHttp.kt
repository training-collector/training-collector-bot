package com.tc.gymtrainer.bot.platform

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.tc.gymtrainer.bot.DialogTurn
import kotlinx.coroutines.future.await
import java.io.IOException
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.Collections

open class ServiceException(message: String) : RuntimeException(message)
class ValidationException(message: String) : ServiceException(message)

class PlatformSettings(
    val allowInsecure: Boolean = false,
    val connectTimeout: Duration = Duration.ofSeconds(5),
    val requestTimeout: Duration = Duration.ofSeconds(25),
    val retryDelay: Duration = Duration.ofSeconds(5),
)

internal class PlatformHttp(url: String, private val key: String, private val settings: PlatformSettings) {
    private val base = url.trimEnd('/') + "/internal/v1/training"
    private val http = HttpClient.newBuilder().connectTimeout(settings.connectTimeout).build()
    private val json = jacksonObjectMapper().registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
    /** Recently read catalogs by digest. Small and bounded: a few versions of a few lists. */
    private val catalogs: MutableMap<String, String> = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) = size > 64
        })

    init {
        require(key.isNotBlank()) { "Missing platform service key" }
        // The service key and trainers' data travel on this connection.
        val uri = URI.create(url)
        fun local() = uri.host != null && runCatching { InetAddress.getByName(uri.host).isLoopbackAddress }.getOrDefault(false)
        require(uri.scheme == "https" || (uri.scheme == "http" && (settings.allowInsecure || local()))) {
            "PLATFORM_URL must use https. For a private network set PLATFORM_ALLOW_INSECURE=true"
        }
    }

    /**
     * Calls the platform. Inside a dialog the result is recorded in [turn]: a repeated
     * delivery of the same update replays it, and a write runs once under its idempotency key.
     */
    suspend inline fun <reified T> call(method: String, path: String, body: Any? = null, turn: DialogTurn? = null): T {
        val payload = body?.let { json.writeValueAsString(it) }
        val raw = if (turn == null) exchange(method, path, payload, null) else {
            val signature = UUID.nameUUIDFromBytes("$method $path $payload".toByteArray()).toString()
            turn.effect(signature) { key -> exchange(method, path, payload, key.toString()) }
        }
        return decode(raw)
    }

    /**
     * Reads a shared catalog. It is always fresh when a dialog step first asks for it. Its
     * content is kept in memory instead of the dialog draft, which is stored and sent on every step.
     */
    suspend inline fun <reified T> catalog(path: String, turn: DialogTurn): T =
        decode(turn.shared("catalog $path", catalogs) { exchange("GET", path, null, null) })

    inline fun <reified T> decode(raw: String): T {
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
            .timeout(settings.requestTimeout).header("Authorization", "Bearer $key")
            .header("X-Request-Id", UUID.randomUUID().toString())
            .method(method, payload?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
        if (payload != null) request.header("Content-Type", "application/json")
        if (keyId != null && method != "GET") request.header("Idempotency-Key", keyId)
        val response = http.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString()).await()
        if (response.statusCode() >= 500) throw IOException("Platform HTTP ${response.statusCode()}")
        return "${response.statusCode()} ${response.body()}"
    }
}
