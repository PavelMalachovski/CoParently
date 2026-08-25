package com.coparently.app.domain.model

import com.coparently.app.domain.family.FamilyMemberRef
import java.time.LocalDateTime

/**
 * Domain model representing a budget for a specific expense category.
 * This is the clean architecture model used in the domain layer.
 *
 * @property id Unique identifier for the budget
 * @property forMembers The children and pets this budget is about. Empty means the whole
 *   family, which is what every record written before the reference type holds — see
 *   [com.coparently.app.domain.family.FamilyMemberRef]. It replaces a single `childId`, which
 *   had nowhere to put a vet's bill.
 * @property category Expense category this budget applies to
 * @property monthlyLimit Monthly spending limit
 * @property currency Currency code (default: USD)
 * @property alertThreshold Percentage threshold for alerts (0.0-1.0, default 0.8 = 80%)
 * @property isActive Whether this budget is currently active
 * @property createdAt Timestamp when the budget was created
 * @property syncedToFirestore Whether the budget has been synced to Firestore
 */
data class Budget(
    val id: String,
    val forMembers: List<FamilyMemberRef> = emptyList(),
    val category: ExpenseCategory,
    val monthlyLimit: Double,
    val currency: String = "USD",
    val alertThreshold: Double = 0.8,
    val isActive: Boolean = true,
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val syncedToFirestore: Boolean = false
)

/**
 * Alert for a budget that has exceeded its threshold.
 *
 * @property budgetId ID of the budget
 * @property currentSpent Amount spent so far
 * @property limit Budget limit
 * @property percentage Percentage of budget used (0.0-1.0+)
 * @property category Expense category
 */
data class BudgetAlert(
    val budgetId: String,
    val currentSpent: Double,
    val limit: Double,
    val percentage: Double,
    val category: ExpenseCategory
) {
    val isOverBudget: Boolean
        get() = percentage >= 1.0

    val isNearLimit: Boolean
        get() = percentage >= 0.8 && percentage < 1.0
}
