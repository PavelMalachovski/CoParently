package com.coparently.app.data.remote.firebase

import com.coparently.app.domain.custody.SharedCustody
import com.coparently.app.domain.model.CustodyModel
import com.coparently.app.domain.model.CustodyModelType
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * The wire mapping in [FirestoreCustodyDataSource], which the repository's own tests mock away.
 *
 * Two things live here and nowhere else. Room stores `momDaysPattern` as a JSON *string* because
 * SQLite has no array type, so the conversion to a real Firestore array happens on this boundary
 * — a JSON blob on the wire is opaque to a security rule and to anyone reading the console. And
 * every number comes back from Firestore as a [Long], never an [Int], so each one is narrowed
 * through [Number]: a `ClassCastException` raised inside a snapshot listener is not something the
 * repository's `retryWhen` can see, it is a crash.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FirestoreCustodyDataSourceTest {

    private lateinit var documentRef: DocumentReference
    private lateinit var dataSource: FirestoreCustodyDataSource

    @Before
    fun setUp() {
        documentRef = mockk()
        val collection = mockk<CollectionReference>()
        every { collection.document(DOCUMENT_ID) } returns documentRef

        val firestore = mockk<FirebaseFirestore>()
        every { firestore.collection("custody_models") } returns collection

        dataSource = FirestoreCustodyDataSource(firestore)
    }

    @Test
    fun `a written document reads back as the same custody`() = runTest {
        val written = writeAndCapture(custody())

        every { documentRef.get() } returns Tasks.forResult(snapshotOf(written))

        assertEquals(custody(), dataSource.getCustody(DOCUMENT_ID))
    }

    @Test
    fun `the day indices cross the wire as an array of numbers, not as Room's JSON string`() =
        runTest {
            val written = writeAndCapture(custody())

            val indices = written["momDayIndices"]
            assertTrue("Expected a list, got ${indices?.javaClass}", indices is List<*>)
            assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), indices)
            // Sorted on the way out, so two devices writing the same pattern produce the same
            // document rather than two orderings of one set.
            assertEquals("2026-08-03", written["startDate"])
        }

    @Test
    fun `numbers arriving as Long are narrowed, not cast`() = runTest {
        // What Firestore actually hands back: its wire format has one integer type, so the Ints
        // this app writes come home as Longs. A blind `as Int` would throw here.
        every { documentRef.get() } returns Tasks.forResult(
            snapshotOf(
                document(
                    "patternDays" to 14L,
                    "momDayIndices" to listOf(0L, 1L, 2L)
                )
            )
        )

        val model = dataSource.getCustody(DOCUMENT_ID)?.model

        assertEquals(14, model?.patternDays)
        assertEquals(setOf(0, 1, 2), model?.momDayIndices)
    }

    @Test
    fun `a document with no startDate is treated as absent rather than half-parsed`() = runTest {
        every { documentRef.get() } returns Tasks.forResult(
            snapshotOf(document().minus("startDate"))
        )

        // Half a pattern would assign the wrong days on every date the calendar asks about,
        // which is worse than having none.
        assertNull(dataSource.getCustody(DOCUMENT_ID))
    }

    @Test
    fun `a document with no id field falls back to the document id`() = runTest {
        every { documentRef.get() } returns Tasks.forResult(snapshotOf(document().minus("id")))

        assertEquals(DOCUMENT_ID, dataSource.getCustody(DOCUMENT_ID)?.model?.id)
    }

    @Test
    fun `participants are sorted at the point of write`() = runTest {
        // firestore.rules enforces participants[0] < participants[1] on create and compares the
        // array order-sensitively on update, so sorting here rather than trusting the caller is
        // what makes an unsorted write structurally impossible.
        val written = writeAndCapture(custody(), participants = listOf(LATER_UID, EARLIER_UID))

        assertEquals(listOf(EARLIER_UID, LATER_UID), written["participants"])
    }

    @Test
    fun `a missing document reads as null`() = runTest {
        every { documentRef.get() } returns Tasks.forResult(snapshotOf(null))

        assertNull(dataSource.getCustody(DOCUMENT_ID))
    }

    // ---- fixtures -----------------------------------------------------------

    /** Runs [FirestoreCustodyDataSource.setCustody] and returns the document it wrote. */
    private suspend fun writeAndCapture(
        custody: SharedCustody,
        participants: List<String> = listOf(EARLIER_UID, LATER_UID)
    ): Map<*, *> {
        val payload = slot<Any>()
        every { documentRef.set(capture(payload)) } returns Tasks.forResult(null)

        dataSource.setCustody(DOCUMENT_ID, participants, custody)

        return payload.captured as Map<*, *>
    }

    private fun custody() = SharedCustody(
        model = CustodyModel(
            id = MODEL_ID,
            modelType = CustodyModelType.WEEK_ON_WEEK_OFF,
            patternDays = 14,
            momDayIndices = (0..6).toSet(),
            startDate = LocalDate.of(2026, 8, 3)
        ),
        lastModifiedBy = LATER_UID,
        lastModifiedAt = "2026-08-04T18:30:00",
        createdAt = "2026-07-01T09:00:00"
    )

    /** A complete document, as this app writes it, with [overrides] applied. */
    private fun document(vararg overrides: Pair<String, Any>): Map<String, Any> = mapOf(
        "id" to MODEL_ID,
        "participants" to listOf(EARLIER_UID, LATER_UID),
        "lastModifiedBy" to LATER_UID,
        "modelType" to "week_on_week_off",
        "patternDays" to 14L,
        "momDayIndices" to listOf(0L, 1L, 2L, 3L, 4L, 5L, 6L),
        "startDate" to "2026-08-03",
        "repeatYearly" to true,
        "createdAt" to "2026-07-01T09:00:00",
        "lastModifiedAt" to "2026-08-04T18:30:00"
    ) + overrides

    private fun snapshotOf(data: Map<*, *>?): DocumentSnapshot = mockk {
        @Suppress("UNCHECKED_CAST")
        every { this@mockk.data } returns data as Map<String, Any>?
    }

    private companion object {
        const val EARLIER_UID = "uidA"
        const val LATER_UID = "uidB"
        const val DOCUMENT_ID = "uidA__uidB"
        const val MODEL_ID = "model-1"
    }
}
