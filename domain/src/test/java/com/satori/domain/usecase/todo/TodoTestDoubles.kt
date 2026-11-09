package com.satori.domain.usecase.todo

import com.satori.domain.fakes.FakeLlmRepository
import com.satori.domain.repository.LlmRepository
import com.satori.domain.repository.OutputGrammar
import com.satori.domain.time.TimeCandidate
import com.satori.domain.time.TimeExpressionParser
import com.satori.domain.time.TimePolicy
import com.satori.domain.time.TimePrecision
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * Finds the phrases it was set up with, in any case and every time they occur, and resolves
 * each to its fixed date or time, the way the real parser would for the anchors these tests use.
 * A date-only phrase lands on the policy's dateOnlyHour, as the TimeCandidate contract says; so
 * do the first, likely and last day of a run of days.
 *
 * A rule set up with onlyWith applies only when that word is in the text too, the way the real
 * parser lends a bare clock time the day said elsewhere in its sentence ("Tomorrow ... at 3").
 * Rules are tried in the order they were set up, and a phrase already matched by an earlier
 * rule is not matched again. Records every text and anchor it was asked about.
 */
class FakeTimeParser : TimeExpressionParser {
    private class Rule(
        val phrase: String,
        val date: LocalDate,
        val time: LocalTime?,
        val confidence: Float,
        val onlyWith: String?,
        val days: ClosedRange<LocalDate>? = null
    )

    private val rules = mutableListOf<Rule>()
    val calls = mutableListOf<Pair<String, ZonedDateTime>>()
    val policies = mutableListOf<TimePolicy>()
    var failWith: RuntimeException? = null

    /** [phrase] means [dateTime], a moment with a clock time. */
    fun at(phrase: String, dateTime: LocalDateTime, confidence: Float = 0.9f, onlyWith: String? = null) = apply {
        rules += Rule(phrase, dateTime.toLocalDate(), dateTime.toLocalTime(), confidence, onlyWith)
    }

    /** [phrase] means the day [date], without a clock time. */
    fun on(phrase: String, date: LocalDate, confidence: Float = 0.9f, onlyWith: String? = null) = apply {
        rules += Rule(phrase, date, null, confidence, onlyWith)
    }

    /** [phrase] means the run of days [days], most likely the day [likely], tentatively. */
    fun days(phrase: String, days: ClosedRange<LocalDate>, likely: LocalDate, confidence: Float = 0.45f) = apply {
        rules += Rule(phrase, likely, null, confidence, null, days)
    }

    override fun find(text: String, anchor: ZonedDateTime, policy: TimePolicy): List<TimeCandidate> {
        calls += text to anchor
        policies += policy
        failWith?.let { throw it }
        val found = mutableListOf<TimeCandidate>()
        for (rule in rules) {
            if (rule.onlyWith != null && !text.contains(rule.onlyWith, ignoreCase = true)) continue
            var start = text.indexOf(rule.phrase, ignoreCase = true)
            while (start >= 0) {
                val end = start + rule.phrase.length
                if (found.none { it.start < end && start < it.end }) {
                    val time = rule.time ?: LocalTime.of(policy.dateOnlyHour, 0)
                    val days = rule.days
                    found += TimeCandidate(
                        phrase = text.substring(start, end),
                        start = start,
                        end = end,
                        at = ZonedDateTime.of(rule.date, time, anchor.zone),
                        hasTime = rule.time != null,
                        confidence = rule.confidence,
                        endAt = days?.let { ZonedDateTime.of(it.endInclusive, time, anchor.zone) },
                        precision = if (days != null) TimePrecision.DAYS_RANGE else if (rule.time != null) TimePrecision.EXACT else TimePrecision.DAY,
                        startAt = days?.let { ZonedDateTime.of(it.start, time, anchor.zone) }
                    )
                }
                start = text.indexOf(rule.phrase, end, ignoreCase = true)
            }
        }
        return found.sortedBy { it.start }
    }
}

/**
 * Gives one answer per call, in order, and repeats the last one; a null answer fails that call
 * the way a native error would. Records prompts like [FakeLlmRepository]; a call with a lead (a
 * Malayalam text's note) is recorded with the lead and a blank line before its text, as the
 * interface's default sends it, and [leads] holds the lead of every call, null for none.
 */
class ScriptedLlmRepository(
    private val answers: List<String?>
) : LlmRepository by FakeLlmRepository() {
    val prompts = mutableListOf<Pair<String, String?>>()
    val leads = mutableListOf<String?>()

    override fun generateInsight(
        text: String,
        systemPrompt: String?,
        maxTokens: Int,
        grammar: OutputGrammar?
    ): Flow<String> = generateInsight(text, systemPrompt, maxTokens, grammar, lead = null)

    override fun generateInsight(
        text: String,
        systemPrompt: String?,
        maxTokens: Int,
        grammar: OutputGrammar?,
        lead: String?
    ): Flow<String> = flow {
        val answer = answers[minOf(prompts.size, answers.lastIndex)]
        prompts += (if (lead.isNullOrBlank()) text else "$lead\n\n$text") to systemPrompt
        leads += lead
        emit(answer ?: throw IllegalStateException("native crash"))
    }
}
