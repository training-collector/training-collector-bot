package com.tc.gymtrainer.bot.platform

import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime

// Only the fields the bot reads. The platform may send more; they are ignored.

data class Trainer(
    val id: Long,
    val fullName: String,
    val username: String?,
    val timeZone: String? = null,
)

enum class ClientStatus(val label: String) { ACTIVE("Активен"), ARCHIVED("В архиве") }

enum class Sex(val label: String) { MALE("Мужской"), FEMALE("Женский") }

data class Client(
    val id: Long,
    val fullName: String,
    val goal: String?,
    val notes: String?,
    val status: ClientStatus,
    val firstName: String,
    val lastName: String?,
    val sex: Sex?,
    val age: Int?,
    val telegramUsername: String?,
) {
    fun profile() = ClientProfile(firstName, lastName, sex, age, telegramUsername, goal, notes)
}

data class ClientProfile(
    val firstName: String,
    val lastName: String? = null,
    val sex: Sex? = null,
    val age: Int? = null,
    val telegramUsername: String? = null,
    val goal: String? = null,
    val notes: String? = null,
) {
    val fullName: String get() = listOfNotNull(firstName, lastName).joinToString(" ")
}

data class MuscleGroup(val id: Long, val name: String)

data class ExerciseCatalogItem(
    val id: Long,
    val muscleGroupId: Long,
    val name: String,
    val description: String? = null,
)

enum class WorkoutStage { CHOOSE_EXERCISE, ENTER_SETS, REVIEW, COMPLETED }

data class WorkoutSession(
    val id: Long,
    val clientId: Long,
    val performedAt: OffsetDateTime,
    val durationMin: Int?,
    val note: String?,
    val stage: WorkoutStage = WorkoutStage.COMPLETED,
)

data class WorkoutExercise(val id: Long, val exerciseName: String, val note: String?)

data class ExerciseSet(
    val id: Long,
    val setNumber: Int,
    val weightKg: BigDecimal?,
    val reps: Int?,
    val restSec: Int?,
    val rpe: BigDecimal?,
    val durationSec: Int? = null,
)

data class BodyMeasurement(
    val id: Long,
    val clientId: Long,
    val measuredOn: LocalDate,
    val weightKg: BigDecimal?,
    val bodyFatPct: BigDecimal?,
    val chestCm: BigDecimal?,
    val waistCm: BigDecimal?,
    val hipsCm: BigDecimal?,
    val bicepsCm: BigDecimal?,
    val thighCm: BigDecimal?,
    val note: String?,
    val heightCm: BigDecimal? = null,
    val calfCm: BigDecimal? = null,
    val rightBicepsCm: BigDecimal? = null,
    val leftBicepsCm: BigDecimal? = null,
    val rightThighCm: BigDecimal? = null,
    val leftThighCm: BigDecimal? = null,
    val rightCalfCm: BigDecimal? = null,
    val leftCalfCm: BigDecimal? = null,
    val rightForearmCm: BigDecimal? = null,
    val leftForearmCm: BigDecimal? = null,
    val backWidthCm: BigDecimal? = null,
    val version: Int = 0,
)

data class ExerciseWithSets(
    val exercise: WorkoutExercise,
    val sets: List<ExerciseSet>,
)

data class WorkoutDetail(
    val session: WorkoutSession,
    val exercises: List<ExerciseWithSets>,
)

data class SetDraft(
    val weightKg: BigDecimal? = null,
    val reps: Int? = null,
    val restSec: Int? = null,
    val rpe: BigDecimal? = null,
    val durationSec: Int? = null,
)

data class ExerciseDraft(
    val exerciseCatalogId: Long,
    val exerciseName: String,
    val sets: List<SetDraft> = emptyList(),
)

data class MeasurementInput(
    val measuredOn: LocalDate,
    val weightKg: BigDecimal? = null,
    val bodyFatPct: BigDecimal? = null,
    val chestCm: BigDecimal? = null,
    val waistCm: BigDecimal? = null,
    val hipsCm: BigDecimal? = null,
    val bicepsCm: BigDecimal? = null,
    val thighCm: BigDecimal? = null,
    val note: String? = null,
    val heightCm: BigDecimal? = null,
    val calfCm: BigDecimal? = null,
    val rightBicepsCm: BigDecimal? = null,
    val leftBicepsCm: BigDecimal? = null,
    val rightThighCm: BigDecimal? = null,
    val leftThighCm: BigDecimal? = null,
    val rightCalfCm: BigDecimal? = null,
    val leftCalfCm: BigDecimal? = null,
    val rightForearmCm: BigDecimal? = null,
    val leftForearmCm: BigDecimal? = null,
    val backWidthCm: BigDecimal? = null,
)
