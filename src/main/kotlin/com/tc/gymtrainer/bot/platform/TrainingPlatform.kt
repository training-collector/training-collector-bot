package com.tc.gymtrainer.bot.platform

import kotlinx.coroutines.CoroutineScope
import java.math.BigDecimal
import java.time.LocalDate

class TrainingPlatform(url: String, key: String) {
    private val http = PlatformHttp(url, key)
    internal val trainers = TrainerService(http)
    internal val clients = ClientService(http)
    internal val catalog = CatalogService(http)
    internal val workouts = WorkoutService(http)
    internal val measurements = MeasurementService(http)

    suspend fun keepRegistered(scope: CoroutineScope, username: String) = http.keepRegistered(scope, username)
}

internal class TrainerService(private val http: PlatformHttp) {
    suspend fun findByTelegramUserId(sender: Long) = http.call<Trainer?>("GET", "/linked/$sender")
    suspend fun connectTrainingBot(token: String, sender: Long) =
        http.call<Trainer>("POST", "/connect", mapOf("token" to token, "sender" to sender))
}

internal class ClientService(private val http: PlatformHttp) {
    suspend fun create(sender: Long, profile: ClientProfile, measurement: MeasurementInput?) =
        http.call<Client>("POST", "/chats/$sender/clients/profile", mapOf("profile" to profile, "measurement" to measurement))
    suspend fun list(sender: Long, includeArchived: Boolean) =
        http.call<List<Client>>("GET", "/chats/$sender/clients?includeArchived=$includeArchived")
    suspend fun getOwned(sender: Long, id: Long) = http.call<Client>("GET", "/chats/$sender/clients/$id")
}

internal class CatalogService(private val http: PlatformHttp) {
    suspend fun exercisesFor(sender: Long) = http.call<List<ExerciseCatalogItem>>("GET", "/chats/$sender/catalog/exercises")
    suspend fun getExercise(sender: Long, id: Long) = http.call<ExerciseCatalogItem>("GET", "/chats/$sender/catalog/exercises/$id")
    suspend fun addExercise(sender: Long, name: String, description: String? = null) =
        http.call<ExerciseCatalogItem>("POST", "/chats/$sender/catalog/exercises", mapOf("name" to name, "description" to description))
}

internal data class LatestMeasurement(val value: BigDecimal, val measuredOn: LocalDate)

internal class MeasurementService(private val http: PlatformHttp) {
    suspend fun add(sender: Long, client: Long, input: MeasurementInput) =
        http.call<BodyMeasurement>("POST", "/chats/$sender/clients/$client/measurements", input)
    suspend fun history(sender: Long, client: Long) =
        http.call<List<BodyMeasurement>>("GET", "/chats/$sender/clients/$client/measurements")
    suspend fun current(sender: Long, client: Long) =
        http.call<Map<String, LatestMeasurement>>("GET", "/chats/$sender/clients/$client/measurements/current")
    suspend fun get(sender: Long, id: Long) = http.call<BodyMeasurement>("GET", "/chats/$sender/measurements/$id")
    suspend fun update(sender: Long, id: Long, input: MeasurementInput, expected: BodyMeasurement) =
        http.call<BodyMeasurement>("PUT", "/chats/$sender/measurements/$id/revision", mapOf("input" to input, "expected" to expected))
    suspend fun delete(sender: Long, id: Long) = http.call<Unit>("DELETE", "/chats/$sender/measurements/$id")
}

internal class WorkoutService(private val http: PlatformHttp) {
    suspend fun activeForClient(sender: Long, client: Long) =
        http.call<WorkoutSession?>("GET", "/chats/$sender/clients/$client/workouts/active")
    suspend fun startOrResume(sender: Long, client: Long) =
        http.call<WorkoutSession>("POST", "/chats/$sender/clients/$client/workouts/start")
    suspend fun history(sender: Long, client: Long) =
        http.call<List<WorkoutSession>>("GET", "/chats/$sender/clients/$client/workouts")
    suspend fun getDetail(sender: Long, id: Long) = http.call<WorkoutDetail>("GET", "/chats/$sender/workouts/$id")
    suspend fun getSession(sender: Long, id: Long) = getDetail(sender, id).session
    suspend fun skipLegacyTemplate(sender: Long, id: Long) = http.call<Unit>("POST", "/chats/$sender/workouts/$id/skip-template")
    suspend fun selectExercise(sender: Long, id: Long, catalog: Long) = http.call<Unit>("POST", "/chats/$sender/workouts/$id/select/$catalog")
    suspend fun appendActiveSets(sender: Long, id: Long, exercise: Long, sets: List<SetDraft>) =
        http.call<Unit>("POST", "/chats/$sender/workouts/$id/sets/$exercise/append", sets)
    suspend fun undoActiveSet(sender: Long, id: Long, exercise: Long) = http.call<Unit>("POST", "/chats/$sender/workouts/$id/sets/$exercise/undo")
    suspend fun finishExercise(sender: Long, id: Long, exercise: Long) = http.call<Unit>("POST", "/chats/$sender/workouts/$id/sets/$exercise/finish")
    suspend fun chooseAnotherExercise(sender: Long, id: Long) = http.call<Unit>("POST", "/chats/$sender/workouts/$id/another")
    suspend fun finishWorkout(sender: Long, id: Long) = http.call<WorkoutDetail>("POST", "/chats/$sender/workouts/$id/finish")
    suspend fun updateDate(sender: Long, id: Long, date: LocalDate) = http.call<Unit>("PUT", "/chats/$sender/workouts/$id/date", mapOf("date" to date))
    suspend fun updateDuration(sender: Long, id: Long, minutes: Int?) = http.call<Unit>("PUT", "/chats/$sender/workouts/$id/duration", mapOf("minutes" to minutes))
    suspend fun updateNote(sender: Long, id: Long, note: String?) = http.call<Unit>("PUT", "/chats/$sender/workouts/$id/note", mapOf("text" to note))
    suspend fun deleteSession(sender: Long, id: Long) = http.call<Unit>("DELETE", "/chats/$sender/workouts/$id")
    suspend fun addExercise(sender: Long, id: Long, draft: ExerciseDraft) = http.call<Unit>("POST", "/chats/$sender/workouts/$id/exercises", draft)
    suspend fun deleteExercise(sender: Long, id: Long) = http.call<Unit>("DELETE", "/chats/$sender/exercises/$id")
    suspend fun updateSet(sender: Long, id: Long, set: SetDraft) = http.call<Unit>("PUT", "/chats/$sender/sets/$id", set)
}
