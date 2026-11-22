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

fun trainingBotToken(): String? {
    val bot = ConfigFactory.load("bot").getConfig("app.bot")
    val token = bot.getString("token").takeIf { it.isNotBlank() }
    require(token == null || bot.getString("mode").trim().equals("POLLING", ignoreCase = true)) {
        "Для Telegram-бота поддерживается BOT_MODE=POLLING."
    }
    return token
}

fun main() {
    val token = requireNotNull(trainingBotToken()) { "Для запуска основного бота задайте BOT_TOKEN." }
    val config = ConfigFactory.load("bot").getConfig("app.platform")
    val platform = TrainingPlatform(config.getString("url"), config.getString("key"))
    runBlocking { pollTrainingBot(token, platform) }
}
