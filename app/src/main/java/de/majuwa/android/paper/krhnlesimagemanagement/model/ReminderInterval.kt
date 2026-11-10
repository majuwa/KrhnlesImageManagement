package de.majuwa.android.paper.krhnlesimagemanagement.model

/**
 * How often the new-photos reminder checks. "Monthly" is a fixed 30-day period (periodic work has
 * no calendar months). Persisted by enum name, never by number.
 */
enum class ReminderInterval(
    val days: Long,
) {
    Weekly(7),
    EveryTwoWeeks(14),
    Monthly(30),
    ;

    companion object {
        val DEFAULT = Weekly

        /** The interval stored under [name]; [DEFAULT] for null, blank or unknown values. */
        fun fromStored(name: String?): ReminderInterval = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * The stored reminder setting. [sinceMillis] is when it was turned on (0 while unset), [lastShownMillis]
 * when the last reminder was posted (0 = none since it was turned on).
 */
data class NewPhotosReminderSettings(
    val enabled: Boolean = false,
    val interval: ReminderInterval = ReminderInterval.DEFAULT,
    val sinceMillis: Long = 0L,
    val lastShownMillis: Long = 0L,
)
