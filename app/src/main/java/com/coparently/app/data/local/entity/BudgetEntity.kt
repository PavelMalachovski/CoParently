package com.coparently.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDateTime

/**
 * Entity representing a budget in the local Room database.
 */
@Entity(tableName = "budgets")
data class BudgetEntity(
    @PrimaryKey
    val id: String,
    /**
     * Dead column, kept because dropping one needs a table rebuild that cannot be tested here.
     *
     * Superseded by [forMembersJson]. Nothing has ever written a non-null value here: no screen
     * passed a child, and the two DAO queries that read it had no callers — so the v26 -> v27
     * migration converted, in practice, nothing. Room compares the whole table against the
     * entity, so leaving the column in the database while removing it from the class fails
     * validation; the alternative is a `CREATE TABLE`/`INSERT SELECT`/`DROP`/`RENAME` rebuild.
     * `DatabaseMigrations.MIGRATION_12_13` is one and is proved row by row by
     * `CoPlanlyDatabaseMigrationTest` — but only because `app/schemas/12.json` exists for
     * `MigrationTestHelper` to build from. `app/schemas/` stops at v14 (CQ-1), so the same test
     * cannot be written for a v26 database. Delete the column when CQ-1 lands.
     */
    val childId: String? = null,
    /** Who this budget is about. See [ExpenseEntity.forMembersJson]. */
    val forMembersJson: String = "[]",
    val category: String, // Stored as string enum name
    val monthlyLimit: Double,
    val currency: String = "USD",
    val alertThreshold: Double = 0.8,
    val isActive: Boolean = true,
    val createdAt: LocalDateTime,
    val syncedToFirestore: Boolean = false
)
