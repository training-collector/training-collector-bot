package com.tc.gymtrainer.bot.platform

import com.tc.gymtrainer.bot.DialogTurn
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime

class TrainingPlatform(url: String, key: String, val settings: PlatformSettings = PlatformSettings()) {
    private val http = PlatformHttp(url, key, settings)

    suspend fun register(username: String) = http.call<Unit>("POST", "/identity", mapOf("username" to username))
    internal fun updates(sender: Long, update: Long) = UpdateApi(http, sender, update)
    internal fun chat(sender: Long, turn: DialogTurn) = ChatApi(http, turn, sender)
}

/** [attempt] counts deliveries of this update. [trainer] is the linked trainer of the chat, if any. */
internal data class Lease(
    val status: String, val lease: String?, val draft: String?, val checkpoint: String?,
    val attempt: Int = 1, val trainer: Trainer? = null,
)

/** Claims one Telegram update for a chat and stores the dialog between requests. */
internal class UpdateApi(private val http: PlatformHttp, sender: Long, update: Long) {
    private val path = "/updates/$sender/$update"
    suspend fun begin() = http.call<Lease>("POST", "$path/begin")
    suspend fun checkpoint(lease: Lease, draft: String) = save("checkpoint", lease, draft)
    suspend fun release(lease: Lease) = save("release", lease, null)
    suspend fun finish(lease: Lease, draft: String?) = save("finish", lease, draft)
    private suspend fun save(step: String, lease: Lease, draft: String?) =
        http.call<Unit>("POST", "$path/$step", mapOf("lease" to lease.lease, "draft" to draft))
}

internal data class LatestMeasurement(val value: BigDecimal, val measuredOn: LocalDate)
private data class GoalItem(val name: String)
private data class NewClient(
    val firstName: String, val lastName: String?, val sex: Sex?, val age: Int?, val telegramUsername: String?,
    val goal: String?, val notes: String?, val measurement: MeasurementInput?,
)
private data class SessionEdit(val performedAt: OffsetDateTime, val durationMin: Int?, val note: String?)

/** The platform as seen from one chat during one dialog turn. Every call is replay-safe. */
internal class ChatApi(private val http: PlatformHttp, private val turn: DialogTurn, private val sender: Long) {
    private val chat = "/chats/$sender"
    private suspend inline fun <reified T> get(path: String): T = http.call("GET", chat + path, turn = turn)
    private suspend inline fun <reified T> send(method: String, path: String, body: Any? = null): T = http.call(method, chat + path, body, turn)

    suspend fun connect(token: String): Trainer = http.call("POST", "/connect", mapOf("token" to token, "sender" to sender), turn)

    suspend fun goals(): List<String> = http.catalog<List<GoalItem>>("$chat/catalog/goals", turn).map { it.name }
    suspend fun groups(): List<MuscleGroup> = http.catalog("$chat/catalog/muscle-groups", turn)
    suspend fun exercises(groupId: Long? = null): List<ExerciseCatalogItem> =
        http.catalog(chat + if (groupId == null) "/catalog/exercises" else "/catalog/muscle-groups/$groupId/exercises", turn)
    suspend fun exercise(id: Long): ExerciseCatalogItem = get("/catalog/exercises/$id")

    suspend fun clients(includeArchived: Boolean): List<Client> = get("/clients?includeArchived=$includeArchived")
    suspend fun client(id: Long): Client = get("/clients/$id")
    suspend fun createClient(profile: ClientProfile, measurement: MeasurementInput?): Client = send("POST", "/clients",
        NewClient(profile.firstName, profile.lastName, profile.sex, profile.age, profile.telegramUsername, profile.goal, profile.notes, measurement))

    suspend fun measurements(client: Long): List<BodyMeasurement> = get("/clients/$client/measurements")
    suspend fun currentMeasurements(client: Long): Map<String, LatestMeasurement> = get("/clients/$client/measurements/current")
    suspend fun measurement(id: Long): BodyMeasurement = get("/measurements/$id")
    suspend fun addMeasurement(client: Long, input: MeasurementInput): BodyMeasurement = send("POST", "/clients/$client/measurements", input)
    /** Rejected if the record changed after [shown] was read. */
    suspend fun updateMeasurement(shown: BodyMeasurement, input: MeasurementInput): BodyMeasurement =
        send("PUT", "/measurements/${shown.id}?expectedVersion=${shown.version}", input)
    suspend fun deleteMeasurement(id: Long): Unit = send("DELETE", "/measurements/$id")

    suspend fun activeWorkout(client: Long): WorkoutSession? = get("/clients/$client/workouts/active")
    suspend fun startOrResume(client: Long): WorkoutSession = send("POST", "/clients/$client/workouts/start")
    suspend fun cancelActiveWorkout(client: Long, session: Long): Boolean = send("DELETE", "/clients/$client/workouts/active/$session")
    suspend fun history(client: Long): List<WorkoutSession> = get("/clients/$client/workouts")
    suspend fun workout(id: Long): WorkoutDetail = get("/workouts/$id")
    suspend fun selectExercise(id: Long, catalog: Long): Unit = send("POST", "/workouts/$id/select/$catalog")
    suspend fun appendSets(id: Long, exercise: Long, sets: List<SetDraft>): Unit = send("POST", "/workouts/$id/sets/$exercise/append", sets)
    suspend fun undoSet(id: Long, exercise: Long): Unit = send("POST", "/workouts/$id/sets/$exercise/undo")
    suspend fun finishExercise(id: Long, exercise: Long): Unit = send("POST", "/workouts/$id/sets/$exercise/finish")
    suspend fun anotherExercise(id: Long): Unit = send("POST", "/workouts/$id/another")
    suspend fun finishWorkout(id: Long, durationMin: Int?): WorkoutDetail =
        send("POST", "/workouts/$id/finish" + durationMin?.let { "?durationMin=$it" }.orEmpty())
    /** Replaces the date, duration and note of a finished workout. Pass the current values for the fields that stay. */
    suspend fun updateWorkout(session: WorkoutSession): Unit =
        send("PUT", "/workouts/${session.id}", SessionEdit(session.performedAt, session.durationMin, session.note))
    suspend fun deleteWorkout(id: Long): Unit = send("DELETE", "/workouts/$id")
    suspend fun addExercise(id: Long, draft: ExerciseDraft): Unit = send("POST", "/workouts/$id/exercises", draft)
    suspend fun deleteExercise(id: Long): Unit = send("DELETE", "/exercises/$id")
    suspend fun updateSet(id: Long, set: SetDraft): Unit = send("PUT", "/sets/$id", set)
}
