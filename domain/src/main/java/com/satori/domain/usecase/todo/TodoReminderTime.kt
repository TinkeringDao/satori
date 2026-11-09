package com.satori.domain.usecase.todo

import com.satori.domain.model.TodoKind
import com.satori.domain.time.TimePolicy
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * When a to-do is due and when it reminds, for a due day and time chosen by hand. The reminder is
 * derived, never typed, by the rule [ExtractTodosUseCase] applies to the to-dos it finds:
 * - A to-do with a clock time reminds at that time.
 * - An appointment with a clock time reminds an hour before.
 * - A to-do due on a day only reminds at the hour dates get ([TimePolicy.dateOnlyHour], 9:00).
 * - No due time means no reminder.
 * - A reminder that is already past is not set; an appointment whose lead time has passed
 *   reminds at its start instead, if that is still ahead.
 */
object TodoReminderTime {

    private val APPOINTMENT_LEAD_MS = TimeUnit.HOURS.toMillis(ExtractTodosUseCase.APPOINTMENT_LEAD_HOURS)

    /**
     * The moment a to-do is due: [date] at [time], or at the policy hour when only the day is
     * meant. A to-do due on a day only is stored with that moment too.
     */
    fun dueAt(date: LocalDate, time: LocalTime?, zone: ZoneId, policy: TimePolicy = TimePolicy()): Long =
        date.atTime(time ?: policyHour(policy))
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

    /**
     * When to remind of a to-do due at [dueAt], or null for no reminder.
     *
     * @param dueAt When it is due (epoch ms), or null if it has no due time
     * @param dueHasTime False when only the day was meant; the reminder is then at the policy hour
     *   of that day, wherever [dueAt] sits in it
     * @param nowMs The current time: a reminder before it is not set
     */
    fun of(
        dueAt: Long?,
        dueHasTime: Boolean,
        kind: TodoKind,
        nowMs: Long,
        zone: ZoneId,
        policy: TimePolicy = TimePolicy()
    ): Long? {
        if (dueAt == null) return null
        val due = if (dueHasTime) {
            dueAt
        } else {
            dueAt(Instant.ofEpochMilli(dueAt).atZone(zone).toLocalDate(), time = null, zone = zone, policy = policy)
        }
        val lead = if (kind == TodoKind.APPOINTMENT && dueHasTime) due - APPOINTMENT_LEAD_MS else due
        return sequenceOf(lead, due).firstOrNull { it >= nowMs }
    }

    private fun policyHour(policy: TimePolicy): LocalTime = LocalTime.of(policy.dateOnlyHour.coerceIn(0, 23), 0)
}
