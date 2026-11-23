package com.tc.gymtrainer.bot

import dev.inmo.tgbotapi.bot.exceptions.RequestException

private val BOT_TOKEN = Regex("bot\\d+:[A-Za-z0-9_-]+")

/**
 * Describes a failure for the log: the chain of exception types with their messages and
 * the stack, without credentials. Messages of Telegram SDK exceptions are not quoted
 * because they can contain the request, which is what the user wrote.
 */
internal fun safeFailure(e: Throwable, secret: String): RuntimeException {
    val text = generateSequence(e) { it.cause.takeIf { cause -> cause !== it } }.take(6).joinToString(" <- ") {
        val detail = when {
            it is RequestException -> "${it.response.errorCode} ${it.response.description}"
            it.javaClass.name.startsWith("dev.inmo.") -> ""
            else -> it.message.orEmpty()
        }
        (it.javaClass.simpleName + " " + detail).trim().take(300)
    }
    val masked = BOT_TOKEN.replace(if (secret.isEmpty()) text else text.replace(secret, "<token>"), "bot<token>")
    return RuntimeException(masked).also { it.stackTrace = generateSequence(e) { c -> c.cause.takeIf { cause -> cause !== c } }.last().stackTrace }
}
