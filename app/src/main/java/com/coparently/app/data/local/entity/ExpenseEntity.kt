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
    /**
     * Dead column, kept because dropping one needs a table rebuild this environment cannot test.
     *
     * Superseded by [forMembersJson]. Nothing has ever written a non-null value here: no screen
     * passed a child, and the two DAO queries that read it had no callers — so the v26 -> v27
     * migration converted, in practice, nothing. Room compares the whole table against the
     * entity, so leaving the column in the database while removing it from the class fails
     * validation; the alternative is a `CREATE TABLE`/`INSERT SELECT`/`DROP`/`RENAME` rebuild,
     * and `app/schemas/` stops at v14 (CQ-1) with no instrumented migration job in CI, so there
     * would be nothing to check it against. Delete it when CQ-1 lands.
     */
    val childId: String? = null,
    /**
     * Who this expense is about — a JSON array of `FamilyMemberRef` stored strings.
     *
     * `[]` means the whole family, which is what every row written before schema 27 holds.
     * A JSON array of plain strings rather than a serialised type, for the reason
     * `FamilyMemberRef` states: R8 has rewritten Gson field names in this app before.
     */
    val forMembersJson: String = "[]",
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
    val createdByFirebaseUid: String? = null,
    /**
     * When this expense was deleted, epoch millis — or null while it is alive.
     *
     * Same meaning as [com.coparently.app.data.local.entity.EventEntity.deletedAtMillis]: a
     * pending tombstone, hidden from every query, kept only until the deletion reaches
     * Firestore. Expenses had the identical defect and one of its own — the `expenses` delete
     * rule admits only the creator, so a co-parent's delete lost the local row and left the
     * document standing, and the next sync restored it.
     */
    val deletedAtMillis: Long? = null,
    /**
     * Slot 1's agreed share of this expense when it was recorded, in basis points.
     *
     * Null on every row that predates the agreement, and the balance math reads that as an even
     * split — which is what those rows were priced at. Snapshotted rather than looked up live so
     * a renegotiated split cannot re-price a settled month.
     */
    val splitBasisPoints: Int? = null
)
