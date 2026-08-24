package com.coparently.app.domain.usecase.ai

import com.coparently.app.data.remote.ai.AIService
import com.coparently.app.domain.model.Event
import com.coparently.app.domain.model.Expense
import com.coparently.app.domain.model.ai.*
import com.coparently.app.domain.repository.EventRepository
import com.coparently.app.domain.repository.ExpenseRepository
import java.time.Duration
import java.time.LocalDateTime
import javax.inject.Inject

/**
 * День 4.1: AI-powered parenting insights
 *
 * Use case для генерации insights о воспитании на основе данных
 */
class FamilyInsightsEngine @Inject constructor(
    private val eventRepository: EventRepository,
    private val expenseRepository: ExpenseRepository,
    private val aiService: AIService
) {

    suspend fun generateFamilyInsights(
        childId: String,
        timeRange: DateRange = DateRange.LAST_30_DAYS
    ): FamilyInsights {

        val calendarData = getChildEvents(childId, timeRange)
        val expenseData = getChildExpenses(childId, timeRange)

        val prompt = buildInsightsPrompt(calendarData, expenseData, timeRange)
        val aiInsights = aiService.generateFamilyInsights(prompt)

        return FamilyInsights(
            timeDistribution = analyzeTimeDistribution(calendarData),
            activityPatterns = analyzeActivityPatterns(calendarData),
            expenseAnalysis = analyzeExpensePatterns(expenseData),
            recommendations = aiInsights.recommendations,
            concerns = aiInsights.concerns,
            positiveTrends = aiInsights.positiveTrends,
            generatedAt = LocalDateTime.now()
        )
    }

    private suspend fun getChildEvents(childId: String, timeRange: DateRange): List<Event> {
        // Get events from event repository
        // For now return empty list, implementation needed
        // TODO: Add method to EventRepository to filter events by child
        return emptyList()
    }

    private suspend fun getChildExpenses(childId: String, timeRange: DateRange): List<Expense> {
        // Get expenses from repository
        // For now return empty list, implementation needed
        // TODO: Add method to ExpenseRepository to filter by child and date range
        return emptyList()
    }

    private fun analyzeTimeDistribution(events: List<Event>): TimeDistribution {
        if (events.isEmpty()) {
            return TimeDistribution(emptyMap(), emptyMap())
        }

        val totalTime = events.sumOf { event ->
            event.endDateTime?.let { end ->
                Duration.between(event.startDateTime, end).toMinutes()
            } ?: 0L
        }

        if (totalTime == 0L) {
            return TimeDistribution(emptyMap(), emptyMap())
        }

        val byParent = events.groupBy { it.parentOwner }
            .mapValues { (_, parentEvents) ->
                val parentTime = parentEvents.sumOf { event ->
                    event.endDateTime?.let { end ->
                        Duration.between(event.startDateTime, end).toMinutes()
                    } ?: 0L
                }
                parentTime / totalTime.toDouble()
            }

        val byActivity = events.groupBy { it.eventType }
            .mapValues { (_, typeEvents) ->
                val activityTime = typeEvents.sumOf { event ->
                    event.endDateTime?.let { end ->
                        Duration.between(event.startDateTime, end).toMinutes()
                    } ?: 0L
                }
                activityTime / totalTime.toDouble()
            }

        return TimeDistribution(byParent, byActivity)
    }

    private fun analyzeActivityPatterns(events: List<Event>): ActivityPatterns {
        val byDayOfWeek = events.groupBy { it.startDateTime.dayOfWeek }
            .mapValues { (_, dayEvents) -> dayEvents.size }

        val byHourOfDay = events.groupBy { it.startDateTime.hour }
            .mapValues { (_, hourEvents) -> hourEvents.size }

        return ActivityPatterns(byDayOfWeek, byHourOfDay)
    }

    private fun analyzeExpensePatterns(expenses: List<Expense>): ExpenseAnalysis {
        if (expenses.isEmpty()) {
            return ExpenseAnalysis(
                totalSpent = 0.0,
                averagePerWeek = 0.0,
                byCategory = emptyMap(),
                topExpenses = emptyList(),
                trend = ExpenseTrend.STABLE
            )
        }

        val totalSpent = expenses.sumOf { it.amount }
        val weeks = 4.0 // Approximate for 30 days
        val averagePerWeek = totalSpent / weeks

        val byCategory = expenses.groupBy { it.category.name }
            .mapValues { (_, categoryExpenses) ->
                categoryExpenses.sumOf { it.amount }
            }

        val topExpenses = expenses
            .sortedByDescending { it.amount }
            .take(5)
            .map { expense ->
                ExpenseItem(
                    description = expense.title,
                    amount = expense.amount,
                    category = expense.category.name,
                    date = expense.date
                )
            }

        val trend = calculateTrend(expenses)

        return ExpenseAnalysis(
            totalSpent = totalSpent,
            averagePerWeek = averagePerWeek,
            byCategory = byCategory,
            topExpenses = topExpenses,
            trend = trend
        )
    }

    private fun calculateTrend(expenses: List<Expense>): ExpenseTrend {
        if (expenses.size < 2) return ExpenseTrend.STABLE

        val sorted = expenses.sortedBy { it.date }
        val midpoint = sorted.size / 2

        val firstHalfAvg = sorted.take(midpoint).map { it.amount }.average()
        val secondHalfAvg = sorted.drop(midpoint).map { it.amount }.average()

        val changePercent = ((secondHalfAvg - firstHalfAvg) / firstHalfAvg) * 100

        return when {
            changePercent > 10 -> ExpenseTrend.INCREASING
            changePercent < -10 -> ExpenseTrend.DECREASING
            else -> ExpenseTrend.STABLE
        }
    }

    private fun buildInsightsPrompt(
        calendarData: List<Event>,
        expenseData: List<Expense>,
        timeRange: DateRange
    ): String {
        return """
            Analyze family parenting data and provide insights.

            ${PromptSafety.DATA_ONLY_PREAMBLE}

            Time Period: Last ${timeRange.toDays()} days

            Calendar Activities:
            ${PromptSafety.fence(
                "CALENDAR",
                calendarData.take(20)
                    .joinToString("\n") { "- ${it.title} (${it.eventType}) on ${it.startDateTime}" }
            )}
            ${if (calendarData.size > 20) "... and ${calendarData.size - 20} more events" else ""}

            Expenses:
            ${PromptSafety.fence(
                "EXPENSES",
                expenseData.take(15)
                    .joinToString("\n") { "- ${it.title}: ${it.amount} (${it.category})" }
            )}
            ${if (expenseData.size > 15) "... and ${expenseData.size - 15} more expenses" else ""}

            Provide:
            1. Actionable recommendations for parents
            2. Areas of concern or potential issues
            3. Positive trends and patterns

            Focus on:
            - Balance of activities and family time
            - Child development and wellness
            - Financial planning for child-related expenses
            - Co-parenting effectiveness

            Return in JSON format with:
            - recommendations: array of specific, actionable recommendations
            - concerns: array of potential issues or areas needing attention
            - positiveTrends: array of positive patterns identified
        """.trimIndent()
    }
}
