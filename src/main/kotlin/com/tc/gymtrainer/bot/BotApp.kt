package com.tc.gymtrainer.bot

import com.tc.gymtrainer.bot.platform.PlatformSettings
import com.tc.gymtrainer.bot.platform.TrainingPlatform
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.bot.ktor.telegramBot
import dev.inmo.tgbotapi.extensions.api.bot.getMe
import dev.inmo.tgbotapi.requests.GetUpdates
import dev.inmo.tgbotapi.requests.webhook.DeleteWebhook
import dev.inmo.tgbotapi.types.UpdateId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.ZoneId

private val log = LoggerFactory.getLogger("TrainingBot")

/** The Telegram SDK aborts any request after 25 s. A long poll must end before that. */
internal val MAX_POLL_TIMEOUT: Duration = Duration.ofSeconds(24)

/** A long poll that the HTTP client cut short. It is not an error: nothing arrived meanwhile. */
internal fun Throwable.isRequestTimeout() =
    generateSequence(this) { it.cause.takeIf { cause -> cause !== it } }.any { it.javaClass.simpleName == "HttpRequestTimeoutException" }

/**
 * Reads updates by long polling and hands them to the handler one by one, in order.
 * A failed update is tried again; the handler gives up on it after a few attempts,
 * so one bad update cannot stop the bot.
 */
suspend fun pollTrainingBot(
    telegram: TelegramBot, token: String, platform: TrainingPlatform, pollTimeout: Duration = Duration.ofSeconds(20),
    settings: (username: String) -> BotSettings = { BotSettings(it) },
) {
    suspend fun <T> untilDone(what: String, action: suspend () -> T): T {
        while (true) {
            try {
                return action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("{} failed, retrying", what, safeFailure(e, token))
                delay(platform.settings.retryDelay.toMillis())
            }
        }
    }
    val username = untilDone("Reading the bot profile") { requireNotNull(telegram.getMe().username).withoutAt }
    untilDone("Registering the bot on the platform") { platform.register(username) }
    untilDone("Switching the bot to polling") { telegram.execute(DeleteWebhook()) }
    val handler = UpdateHandler(platform, telegram, settings(username))
    var offset: UpdateId? = null
    while (currentCoroutineContext().isActive) {
        val updates = untilDone("Reading updates") {
            try {
                telegram.execute(GetUpdates(offset, timeout = pollTimeout.seconds.toInt(), allowed_updates = listOf("message", "callback_query")))
            } catch (e: Exception) {
                if (e is CancellationException || !e.isRequestTimeout()) throw e
                emptyList()
            }
        }
        for (update in updates) {
            untilDone("Handling an update") { handler.process(update) }
            offset = UpdateId(update.updateId.long + 1)
        }
    }
}

private fun positive(config: Config, path: String) = config.getDuration(path).also {
    require(!it.isNegative && !it.isZero) { "app.$path must be positive" }
}

fun main() {
    val config = ConfigFactory.load("bot").getConfig("app")
    val token = config.getString("bot.token").trim()
    require(token.isNotEmpty()) { "Для запуска основного бота задайте BOT_TOKEN." }
    val platform = TrainingPlatform(config.getString("platform.url"), config.getString("platform.key"), PlatformSettings(
        allowInsecure = config.getBoolean("platform.allowInsecure"),
        connectTimeout = positive(config, "platform.connectTimeout"),
        requestTimeout = positive(config, "platform.requestTimeout"),
        retryDelay = positive(config, "platform.retryDelay"),
    ))
    val attempts = config.getInt("bot.maxUpdateAttempts").also { require(it > 0) { "app.bot.maxUpdateAttempts must be positive" } }
    val zone = ZoneId.of(config.getString("bot.defaultTimeZone"))
    fun settings(username: String) = BotSettings(username, zone, attempts)
    val telegram = telegramBot(token)
    val pollTimeout = positive(config, "bot.pollTimeout")
    require(pollTimeout <= MAX_POLL_TIMEOUT) { "app.bot.pollTimeout must not exceed ${MAX_POLL_TIMEOUT.seconds}s" }
    runBlocking { pollTrainingBot(telegram, token, platform, pollTimeout, ::settings) }
}
