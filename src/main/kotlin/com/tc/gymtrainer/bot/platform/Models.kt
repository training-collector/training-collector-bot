package com.tc.gymtrainer.bot.platform

import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime

data class Trainer(
    val telegramUserId: Long,
    val fullName: String,
    val username: String?,
)

data class Client(
    val id: Long,
    val trainerId: Long,
    val fullName: String,
    val phone: String?,
    val goal: Goal?,
    val notes: String?,
    val status: ClientStatus,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
    val firstName: String,
    val lastName: String?,
    val sex: Sex?,
    val age: Int?,
    val telegramUsername: String?,
) {
    fun profile() = ClientProfile(firstName, lastName, sex, age, telegramUsername, phone, goal, notes)
}

enum class Sex(val label: String) { MALE("Мужской"), FEMALE("Женский") }

data class ClientProfile(
    val firstName: String,
    val lastName: String? = null,
    val sex: Sex? = null,
    val age: Int? = null,
    val telegramUsername: String? = null,
    val phone: String? = null,
    val goal: Goal? = null,
    val notes: String? = null,
) {
    @get:com.fasterxml.jackson.annotation.JsonIgnore
    val fullName: String get() = listOfNotNull(firstName, lastName).joinToString(" ")
}

data class ExerciseCatalogItem(
    val id: Long,
    val muscleGroupId: Long?,
    val name: String,
    val description: String? = null,
)

enum class WorkoutStage { CHOOSE_TEMPLATE, CHOOSE_EXERCISE, ENTER_SETS, REVIEW, COMPLETED }

data class WorkoutSession(
    val id: Long,
    val clientId: Long,
    val trainerId: Long,
    val performedAt: OffsetDateTime,
    val durationMin: Int?,
    val note: String?,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
    val stage: WorkoutStage = WorkoutStage.COMPLETED,
)

data class WorkoutExercise(
    val id: Long,
    val sessionId: Long,
    val muscleGroupId: Long?,
    val exerciseCatalogId: Long?,
    val exerciseName: String,
    val orderIndex: Int,
    val note: String?,
)

data class ExerciseSet(
    val id: Long,
    val exerciseId: Long,
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
    val createdAt: OffsetDateTime,
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
    val muscleGroupId: Long?,
    val exerciseCatalogId: Long?,
    val exerciseName: String?,
    val note: String? = null,
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
