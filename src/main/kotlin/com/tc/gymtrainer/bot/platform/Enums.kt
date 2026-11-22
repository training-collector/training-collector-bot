package com.tc.gymtrainer.bot.platform

enum class Goal(val label: String) {
    MASS_GAIN("Набор массы"),
    WEIGHT_LOSS("Похудение"),
    REHAB("Реабилитация"),
    MAINTENANCE("Поддержание");

    companion object {
        val available = entries.filter { it != REHAB }
        fun fromLabel(label: String): Goal? = available.firstOrNull { it.label == label }
    }
}

enum class ClientStatus(val label: String) {
    ACTIVE("Активен"),
    ARCHIVED("В архиве");
}
