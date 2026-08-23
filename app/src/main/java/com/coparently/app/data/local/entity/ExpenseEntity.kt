package com.coparently.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Entity representing an expense in the local Room database.
 */
@Entity(tableName = "expenses")
data class ExpenseEntity(
    @PrimaryKey
    val id: String,
    val childId: String? = null,
    val title: String,
    val amount: Double,
    val currency: String = "USD",
    val category: String, // Stored as string enum name
    val paidBy: String,
    val splitBetweenJson: String = "[]", // JSON array of user IDs
    val date: LocalDate,
    val receiptUrl: String? = null,
    val notes: String? = null,
    val createdAt: LocalDateTime,
    val syncedToFirestore: Boolean = false,
    /**
     * Who created this expense — the uid the Firestore rules gate edits on. Null on rows
     * written before schema 23 (and on legacy documents that never carried the field); a null
     * reads as "editable by both", which is exactly what those rows were.
     */
    val createdByFirebaseUid: String? = null
)
