package com.tc.gymtrainer.bot

import com.tc.gymtrainer.bot.platform.TrainingPlatform
import com.typesafe.config.ConfigFactory
import dev.inmo.kslog.common.KSLog
import dev.inmo.tgbotapi.extensions.behaviour_builder.telegramBotWithBehaviourAndLongPolling
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking

suspend fun pollTrainingBot(token: String, platform: TrainingPlatform) = coroutineScope {
    // The SDK reports cancelled update subscriptions through its default error logger.
    val sdkLog = KSLog.default
    KSLog.default = KSLog { level, tag, message, cause ->
        if (message != "Something web wrong" || cause !is CancellationException || cause is TimeoutCancellationException) {
            sdkLog.performLog(level, tag, message, cause)
        }
    }
    val bot = GymTrainerBot(platform)
    val (_, job) = telegramBotWithBehaviourAndLongPolling(token, scope = this) { bot.install(this) }
    job.join()
}

fun main() {
    val config = ConfigFactory.load("bot").getConfig("app")
    val token = config.getString("bot.token").trim()
    require(token.isNotEmpty()) { "Для запуска основного бота задайте BOT_TOKEN." }
    val platform = TrainingPlatform(config.getString("platform.url"), config.getString("platform.key"))
    when (config.getString("bot.mode").trim().uppercase()) {
        "POLLING" -> runBlocking { pollTrainingBot(token, platform) }
        "WEBHOOK" -> {
            val telegram = dev.inmo.tgbotapi.bot.ktor.telegramBot(token)
            val username = requireNotNull(System.getenv("BOT_USERNAME")) { "Set BOT_USERNAME" }.removePrefix("@")
            val handler = TrainingWebhook(platform, telegram, username)
            webhookServer((System.getenv("PORT") ?: "8080").toInt(),
                System.getenv("TELEGRAM_WEBHOOK_SECRET").orEmpty(), handler::process).start()
        }
        else -> error("BOT_MODE must be POLLING or WEBHOOK")
    }
}
