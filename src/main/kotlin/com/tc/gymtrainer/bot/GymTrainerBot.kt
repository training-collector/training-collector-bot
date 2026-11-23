package com.tc.gymtrainer.bot

import com.tc.gymtrainer.bot.platform.Trainer
import com.tc.gymtrainer.bot.platform.WorkoutStage
import com.tc.gymtrainer.bot.platform.TrainingPlatform
import dev.inmo.tgbotapi.extensions.api.bot.getMe
import com.tc.gymtrainer.bot.platform.ValidationException
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onCommand
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onText
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onMessageDataCallbackQuery
import dev.inmo.tgbotapi.extensions.api.answers.answerCallbackQuery
import dev.inmo.tgbotapi.types.chat.Chat
import dev.inmo.tgbotapi.types.chat.PrivateChat
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import dev.inmo.tgbotapi.types.message.abstracts.PossiblyMarkedUp
import dev.inmo.tgbotapi.types.queries.callback.MessageDataCallbackQuery
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
                welcome(message.chat, sender, message.commandArgument())
            }
        }
        bc.onText(initialFilter = { TrainingAction.from(it.content.text, username) != null }, markerFactory = null) { message ->
            val sender = message.privateSenderId ?: return@onText
            val action = TrainingAction.from(message.content.text, username) ?: return@onText
            trainerDialog(message.chat, sender) { trainer ->
                handleAction(message.chat, sender, trainer, action)
            }
        }

        bc.onMessageDataCallbackQuery(initialFilter = { menuCallback.matches(it.data) }, markerFactory = null,
            subcontextUpdatesFilter = null) { query ->
            val chat = query.message.chat
            val sender = query.userId
            if (chat !is PrivateChat || chat.id.chatId.long != sender) return@onMessageDataCallbackQuery
            answerCallbackQuery(query)
            trainerDialog(chat, sender) { trainer -> handleMenu(chat, sender, trainer, query.menuDestination()) }
        }
    }

    internal suspend fun BehaviourContext.welcome(chat: Chat, sender: Long, token: String?) {
        val trainer = if (token != null) platform.trainers.connectTrainingBot(token, sender)
            else requireTrainer(chat, sender, trainerService) ?: return
        val greeting = if (token != null) "Бот для тренировок подключён к профилю ${trainer.username ?: trainer.fullName}."
            else "С возвращением, ${trainer.fullName}! Ваш логин: ${trainer.username ?: trainer.fullName}."
        sendDialogText(chat, "$greeting\n\nВыберите действие.", replyMarkup = trainingKeyboard)
    }

    internal suspend fun BehaviourContext.handleAction(chat: Chat, sender: Long, trainer: Trainer, action: TrainingAction) {
        when (action) {
            TrainingAction.ADD_CLIENT -> addClient(chat, sender, trainer, clientService)
            TrainingAction.EXERCISES -> showExerciseGroups(chat, trainer.telegramUserId, catalogService)
            TrainingAction.CLIENTS -> showClients(chat, trainer, clientService)
            TrainingAction.WORKOUT -> {
                val client = selectClient(chat, sender, trainer, clientService, "Для кого записываем тренировку?") ?: return
                recordWorkout(chat, sender, trainer.telegramUserId, client, catalogService, workoutService)
            }
            TrainingAction.HISTORY -> {
                val client = selectClient(chat, sender, trainer, clientService, "Чью историю показать?", includeArchived = true) ?: return
                showWorkoutHistory(chat, trainer.telegramUserId, client, workoutService)
            }
            TrainingAction.MEASURE -> {
                val client = selectClient(chat, sender, trainer, clientService, "Чьи замеры?", includeArchived = true) ?: return
                showMeasurementHistory(chat, trainer.telegramUserId, client, measurementService)
            }
            TrainingAction.HELP -> sendDialogText(chat, entities = trainingHelp, replyMarkup = trainingKeyboard)
            TrainingAction.MENU -> sendDialogText(chat, "Выберите действие.", replyMarkup = trainingKeyboard)
            TrainingAction.CANCEL -> sendDialogText(chat, "Отменено.", replyMarkup = trainingKeyboard)
        }
    }

    internal suspend fun BehaviourContext.handleMenu(chat: Chat, sender: Long, trainer: Trainer, data: String) {
        val parts = data.split(':')
        val action = parts.take(2).joinToString(":")
        fun id(index: Int = 2) = parts.getOrNull(index)?.toLongOrNull() ?: throw ValidationException("Некорректная запись.")
        fun page(index: Int) = parts.getOrNull(index)?.toIntOrNull() ?: 0
        when (action) {
            "cl:page" -> showClients(chat, trainer, clientService, page(2))
            "cl:add" -> addClient(chat, sender, trainer, clientService)
            "cl:view" -> showClientCard(chat, trainer.telegramUserId, clientService.getOwned(trainer.telegramUserId, id()), measurementService, workoutService)
            "ex:page" -> showExerciseGroups(chat, trainer.telegramUserId, catalogService, page(2))
            "ex:group" -> showGroupExercises(chat, trainer.telegramUserId, catalogService, id(), page(3))
            "ex:view" -> showCatalogExercise(chat, catalogService.getExercise(trainer.telegramUserId, id()))
            "w:add", "w:hist" -> {
                val client = clientService.getOwned(trainer.telegramUserId, id())
                when {
                    action == "w:add" -> recordWorkout(chat, sender, trainer.telegramUserId, client, catalogService, workoutService)
                    parts.size == 3 -> showWorkoutMenu(chat, trainer.telegramUserId, client, workoutService)
                    else -> showWorkoutHistory(chat, trainer.telegramUserId, client, workoutService, page(3))
                }
            }
            "w:view" -> showCompletedWorkout(chat, sender, trainer.telegramUserId, id(), catalogService, workoutService)
            "w:cancel", "w:discard" -> {
                val client = clientService.getOwned(trainer.telegramUserId, id())
                val sessionId = id(3)
                if (action == "w:cancel") {
                    confirmWorkoutCancellation(chat, trainer.telegramUserId, client, workoutService, sessionId)
                } else {
                    val removed = workoutService.cancelActiveWorkout(trainer.telegramUserId, client.id, sessionId)
                    showWorkoutMenu(chat, trainer.telegramUserId, client, workoutService,
                        notice = if (removed) "Тренировка отменена." else WORKOUT_NO_LONGER_ACTIVE)
                }
            }
            "w:resume", "w:finish" -> {
                val session = workoutService.getSession(trainer.telegramUserId, id())
                val client = clientService.getOwned(trainer.telegramUserId, session.clientId)
                if (action == "w:finish" && session.stage != WorkoutStage.COMPLETED) {
                    finishWorkout(chat, trainer.telegramUserId, client, workoutService, session.id, parts.getOrNull(3))
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
            "m:close" -> sendDialogText(chat, "Главное меню", replyMarkup = trainingKeyboard)
        }
    }
}

// Telegram keeps the original keyboard on messages sent before the workout menu changed.
internal fun MessageDataCallbackQuery.menuDestination(): String {
    val keyboard = (message as? PossiblyMarkedUp)?.replyMarkup?.keyboard ?: return data
    val buttons = keyboard.flatten().filterIsInstance<CallbackDataInlineKeyboardButton>()
    val oldClose = buttons.firstOrNull { it.text == CLOSE_WORKOUT } ?: return data
    return when {
        oldClose.callbackData == data && data.startsWith("cl:view:") -> "w:hist:" + data.substringAfterLast(':')
        oldClose.callbackData.startsWith("w:finish:") && (data == oldClose.callbackData || data.startsWith("w:resume:")) ->
            buttons.firstOrNull { it.callbackData.startsWith("w:hist:") }?.callbackData ?: data
        else -> data
    }
}
