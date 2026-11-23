package com.tc.gymtrainer.bot

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.tc.gymtrainer.bot.platform.TrainingPlatform
import dev.inmo.tgbotapi.bot.exceptions.RequestException
import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.extensions.api.answers.answerCallbackQuery
import dev.inmo.tgbotapi.extensions.api.send.sendTextMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.DefaultBehaviourContext
import dev.inmo.tgbotapi.types.buttons.ReplyKeyboardRemove
import dev.inmo.tgbotapi.types.chat.Chat
import dev.inmo.tgbotapi.types.chat.PrivateChat
import dev.inmo.tgbotapi.types.message.abstracts.CommonMessage
import dev.inmo.tgbotapi.types.message.content.TextContent
import dev.inmo.tgbotapi.types.queries.callback.MessageDataCallbackQuery
import dev.inmo.tgbotapi.types.update.CallbackQueryUpdate
import dev.inmo.tgbotapi.types.update.MessageUpdate
import dev.inmo.tgbotapi.types.update.abstracts.Update
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

private data class Lease(val status: String, val lease: String?, val draft: String?, val checkpoint: String?)
private data class Input(val chat: Chat, val text: String, val prompt: Long? = null, val query: MessageDataCallbackQuery? = null)

class TrainingWebhook(private val platform: TrainingPlatform, private val telegram: TelegramBot, private val username: String) {
    private val json = jacksonObjectMapper()
    private val bot = GymTrainerBot(platform)

    suspend fun process(update: Update) {
        val input = when (update) {
            is MessageUpdate -> {
                val message = update.data as? CommonMessage<*> ?: return
                if (message.privateSenderId == null) return
                Input(message.chat, (message.content as? TextContent)?.text ?: return)
            }
            is CallbackQueryUpdate -> {
                val query = update.data as? MessageDataCallbackQuery ?: return
                val chat = query.message.chat
                if (chat !is PrivateChat || chat.id.chatId.long != query.userId) return
                Input(chat, query.menuDestination(), query.message.messageId.long, query)
            }
            else -> return
        }
        val sender = input.chat.id.chatId.long
        val path = "/updates/$sender/${update.updateId.long}"
        val lease = platform.http.call<Lease>("POST", "$path/begin")
        if (lease.status == "done") return
        check(lease.status == "claimed") { "Update is already being processed" }
        // An expired callback can no longer show a spinner. It must not block its action.
        input.query?.let { query ->
            try { telegram.answerCallbackQuery(query) }
            catch (e: RequestException) {
                val detail = e.response.description.orEmpty()
                if (e.response.errorCode != 400 || (!detail.contains("query is too old", true) && !detail.contains("query ID is invalid", true))) throw e
            }
        }
        val old = lease.draft?.let { json.readValue<DialogDraft>(it) }
        val trainer = platform.trainers.findByTelegramUserId(sender)
        val command = input.text.substringBefore(' ')
        val start = input.prompt == null && (command == "/start" || command.equals("/start@$username", true))
        val action = if (input.prompt == null) TrainingAction.from(input.text, username) else null
        val menu = input.prompt != null && menuCallback.matches(input.text)
        val draft = when {
            lease.checkpoint != null -> json.readValue<DialogDraft>(lease.checkpoint)
            start -> DialogDraft("start", input.text.substringAfter(' ', "").trim().ifEmpty { null }, trainer?.id)
            action != null -> DialogDraft("action", action.name, trainer?.id)
            menu -> DialogDraft("menu", input.text, trainer?.id)
            old != null && old.owner == trainer?.id && old.expectsText == (input.prompt == null) && old.prompt == input.prompt ->
                old.apply { inputs.add(InputStep(input.text.trim(), input.prompt)) }
            else -> {
                if (old != null && trainer == null) telegram.sendTextMessage(input.chat, "Доступ к боту закрыт.", replyMarkup = ReplyKeyboardRemove())
                finish(path, lease, old.takeIf { trainer != null && it?.owner == trainer.id })
                return
            }
        }
        suspend fun save() = platform.http.call<Unit>("POST", "$path/checkpoint",
            mapOf("lease" to lease.lease, "draft" to json.writeValueAsString(draft)))
        if (lease.checkpoint == null) save()
        val turn = DialogTurn(draft, sender, update.updateId.long, ::save)
        val next = try {
            coroutineScope {
                val bc = DefaultBehaviourContext(telegram, this)
                withContext(turn) {
                    with(bot) {
                        bc.runFlow(input.chat, dialog = draft.entry != "start") {
                            when {
                                draft.entry == "start" -> bc.welcome(input.chat, sender, draft.argument)
                                trainer == null || trainer.id != draft.owner ->
                                    bc.sendDialogText(input.chat, "Активация не подтверждена. Войдите на сайт и подключите бота в профиле.", replyMarkup = ReplyKeyboardRemove())
                                draft.entry == "action" -> bc.handleAction(input.chat, sender, trainer, TrainingAction.valueOf(requireNotNull(draft.argument)))
                                draft.entry == "menu" -> bc.handleMenu(input.chat, sender, trainer, requireNotNull(draft.argument))
                            }
                        }
                    }
                }
            }
            null
        } catch (_: WaitingForInput) {
            draft
        } catch (_: ChangedDialog) {
            telegram.sendTextMessage(input.chat, "Меню обновилось. Выберите действие.", replyMarkup = trainingKeyboard)
            null
        }
        finish(path, lease, next)
    }

    private suspend fun finish(path: String, lease: Lease, draft: DialogDraft?) =
        platform.http.call<Unit>("POST", "$path/finish", mapOf("lease" to lease.lease, "draft" to draft?.let(json::writeValueAsString)))
}
