package com.tc.gymtrainer.bot

import com.tc.gymtrainer.bot.platform.Trainer
import com.tc.gymtrainer.bot.platform.WorkoutStage
import com.tc.gymtrainer.bot.platform.TrainingPlatform
import dev.inmo.tgbotapi.extensions.api.bot.getMe
import com.tc.gymtrainer.bot.platform.ValidationException
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.api.send.sendTextMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onCommand
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onText
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onMessageDataCallbackQuery
import dev.inmo.tgbotapi.extensions.api.answers.answerCallbackQuery
import dev.inmo.tgbotapi.types.chat.Chat
import dev.inmo.tgbotapi.types.chat.PrivateChat
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.job
import java.util.concurrent.ConcurrentHashMap

/**
 * Тонкий адаптер тренерского бота над сервисным слоем. Не содержит бизнес-логики:
 * только диалоги, разбор ввода и форматирование. Регистрирует обработчики команд
 * по фичам (меню, подопечные, тренировки, замеры).
 */
class GymTrainerBot(private val platform: TrainingPlatform) {
    private val trainerService = platform.trainers
    private val catalogService = platform.catalog
    private val workoutService = platform.workouts
    private val measurementService = platform.measurements
    private val clientService = platform.clients

    suspend fun install(bc: BehaviourContext) {
        val username = requireNotNull(bc.getMe().username).withoutAt
        platform.keepRegistered(bc, username)
        val dialogs = ConcurrentHashMap<Long, Job>()
        suspend fun BehaviourContext.trainerDialog(chat: Chat, sender: Long, action: suspend (Trainer) -> Unit) {
            runFlow(chat, dialog = true) {
                val trainer = requireTrainer(chat, sender, trainerService)
                if (trainer == null) {
                    dialogs.remove(sender)?.cancelAndJoin()
                    return@runFlow
                }
                coroutineScope {
                    val current = coroutineContext.job
                    try {
                        dialogs.put(sender, current)?.cancelAndJoin()
                        withContext(DialogAccess { trainerService.findByTelegramUserId(sender)?.id == trainer.id }) {
                            action(trainer)
                        }
                    } finally {
                        dialogs.remove(sender, current)
                    }
                }
            }
        }

        bc.onCommand("start", requireOnlyCommandInMessage = false) { message ->
            val sender = message.privateSenderId ?: return@onCommand
            runFlow(message.chat) {
                dialogs.remove(sender)?.cancelAndJoin()
                val token = message.commandArgument()
                val trainer = if (token != null) platform.trainers.connectTrainingBot(token, sender)
                    else requireTrainer(message.chat, sender, trainerService) ?: return@runFlow
                val greeting = if (token != null) "Бот для тренировок подключён к профилю ${trainer.username ?: trainer.fullName}."
                    else "С возвращением, ${trainer.fullName}! Ваш логин: ${trainer.username ?: trainer.fullName}."
                sendTextMessage(message.chat, "$greeting\n\nВыберите действие.", replyMarkup = trainingKeyboard)
            }
        }
        bc.onText(initialFilter = { TrainingAction.from(it.content.text, username) != null }, markerFactory = null) { message ->
            val sender = message.privateSenderId ?: return@onText
            val action = TrainingAction.from(message.content.text, username) ?: return@onText
            trainerDialog(message.chat, sender) { trainer ->
                when (action) {
                    TrainingAction.ADD_CLIENT -> addClient(message.chat, sender, trainer, clientService)
                    TrainingAction.EXERCISES -> showExercises(message.chat, trainer.telegramUserId, catalogService)
                    TrainingAction.CLIENTS -> showClients(message.chat, trainer, clientService)
                    TrainingAction.WORKOUT -> {
                        val client = selectClient(message.chat, sender, trainer, clientService, "Для кого записываем тренировку?") ?: return@trainerDialog
                        recordWorkout(message.chat, sender, trainer.telegramUserId, client, catalogService, workoutService)
                    }
                    TrainingAction.HISTORY -> {
                        val client = selectClient(message.chat, sender, trainer, clientService, "Чью историю показать?", includeArchived = true) ?: return@trainerDialog
                        showWorkoutHistory(message.chat, trainer.telegramUserId, client, workoutService)
                    }
                    TrainingAction.MEASURE -> {
                        val client = selectClient(message.chat, sender, trainer, clientService, "Чьи замеры?", includeArchived = true) ?: return@trainerDialog
                        showMeasurementHistory(message.chat, trainer.telegramUserId, client, measurementService)
                    }
                    TrainingAction.HELP -> sendTextMessage(message.chat, entities = trainingHelp, replyMarkup = trainingKeyboard)
                    TrainingAction.MENU -> sendTextMessage(message.chat, "Выберите действие.", replyMarkup = trainingKeyboard)
                    TrainingAction.CANCEL -> sendTextMessage(message.chat, "Отменено.", replyMarkup = trainingKeyboard)
                }
            }
        }

        val menuAction = Regex(
            """cl:add|(?:cl|ex):(?:page:\d+|view:[1-9]\d*)|w:(?:add|view|resume|finish):[1-9]\d*|w:hist:[1-9]\d*(?::\d+)?""" +
                """|m:(?:view:[1-9]\d*|add(?::[1-9]\d*)?|hist(?::[1-9]\d*(?::\d+)?)?|close)"""
        )
        bc.onMessageDataCallbackQuery(initialFilter = { menuAction.matches(it.data) }, markerFactory = null,
            subcontextUpdatesFilter = null) { query ->
            val chat = query.message.chat
            val sender = query.userId
            if (chat !is PrivateChat || chat.id.chatId.long != sender) return@onMessageDataCallbackQuery
            answerCallbackQuery(query)
            trainerDialog(chat, sender) { trainer -> handleMenu(chat, sender, trainer, query.data) }
        }
    }

    private suspend fun BehaviourContext.handleMenu(chat: Chat, sender: Long, trainer: Trainer, data: String) {
        val parts = data.split(':')
        val action = parts.take(2).joinToString(":")
        fun id() = parts.getOrNull(2)?.toLongOrNull() ?: throw ValidationException("Некорректная запись.")
        fun page(index: Int) = parts.getOrNull(index)?.toIntOrNull() ?: 0
        when (action) {
            "cl:page" -> showClients(chat, trainer, clientService, page(2))
            "cl:add" -> addClient(chat, sender, trainer, clientService)
            "cl:view" -> showClientCard(chat, trainer.telegramUserId, clientService.getOwned(trainer.telegramUserId, id()), measurementService, workoutService)
            "ex:page" -> showExercises(chat, trainer.telegramUserId, catalogService, page(2))
            "ex:view" -> showCatalogExercise(chat, catalogService.getExercise(trainer.telegramUserId, id()))
            "w:add", "w:hist" -> {
                val client = clientService.getOwned(trainer.telegramUserId, id())
                if (action == "w:add") recordWorkout(chat, sender, trainer.telegramUserId, client, catalogService, workoutService)
                else showWorkoutHistory(chat, trainer.telegramUserId, client, workoutService, page(3))
            }
            "w:view" -> showCompletedWorkout(chat, sender, trainer.telegramUserId, id(), catalogService, workoutService)
            "w:resume", "w:finish" -> {
                val session = workoutService.getSession(trainer.telegramUserId, id())
                val client = clientService.getOwned(trainer.telegramUserId, session.clientId)
                if (action == "w:finish" && session.stage != WorkoutStage.COMPLETED) {
                    finishWorkout(chat, trainer.telegramUserId, client, workoutService, session.id)
                } else {
                    recordWorkout(chat, sender, trainer.telegramUserId, client, catalogService, workoutService, session.id)
                }
            }
            "m:add", "m:hist" -> {
                // Older messages carry only the action and need an explicit client choice.
                val client = if (parts.size == 2) {
                    selectClient(chat, sender, trainer, clientService, "Выберите подопечного для замеров.", includeArchived = true) ?: return
                } else clientService.getOwned(trainer.telegramUserId, id())
                if (action == "m:add") addMeasurement(chat, sender, trainer.telegramUserId, client, measurementService)
                else showMeasurementHistory(chat, trainer.telegramUserId, client, measurementService, page(3))
            }
            "m:view" -> showMeasurement(chat, sender, trainer, id(), clientService, measurementService)
            "m:close" -> sendTextMessage(chat, "Главное меню", replyMarkup = trainingKeyboard)
        }
    }
}
