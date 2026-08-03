package com.coparently.app.data.remote.firebase

import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.Source
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * That [FirestoreMessageDataSource.fetchMessageIds] reads the **server**, never the offline
 * cache.
 *
 * This one-shot read is the only thing that can discover a message which reached Firestore under
 * a legacy conversation id but never made it into this device's Room, and
 * `ConversationMigrator` archives the legacy conversation once it believes it has seen them all.
 * With offline persistence on, a `Source.DEFAULT` read may be answered from the local cache —
 * which, for a legacy id nothing ever listens on, holds nothing. The read would then succeed with
 * an empty result and the migration would archive the thread on it, stranding the very message it
 * was looking for.
 *
 * Both tests below pass under `Source.DEFAULT` semantics only if the cache happens to agree with
 * the server, so each stubs the two apart: the cache is empty, the server is not.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FirestoreMessageDataSourceServerReadTest {

    private lateinit var query: Query
    private lateinit var dataSource: FirestoreMessageDataSource

    @Before
    fun setUp() {
        query = mockk()
        val messagesCollection = mockk<CollectionReference>()
        every { messagesCollection.whereEqualTo("conversationId", LEGACY_ID) } returns query

        val firestore = mockk<FirebaseFirestore>()
        every { firestore.collection("conversations") } returns mockk()
        every { firestore.collection("messages") } returns messagesCollection

        // The offline cache holds nothing for a legacy conversation id — nothing in the app has
        // ever attached a listener to one, so nothing ever populated it. This is what a
        // `Source.DEFAULT` read can be answered with.
        every { query.get() } returns Tasks.forResult(snapshotOf())

        dataSource = FirestoreMessageDataSource(firestore)
    }

    @Test
    fun `it returns what the server holds, not what the empty offline cache holds`() = runTest {
        every { query.get(Source.SERVER) } returns
            Tasks.forResult(snapshotOf("msg-1", "msg-remote-only"))

        assertEquals(setOf("msg-1", "msg-remote-only"), dataSource.fetchMessageIds(LEGACY_ID))
    }

    @Test
    fun `an offline device fails the read instead of reporting an empty thread`() = runTest {
        every { query.get(Source.SERVER) } returns Tasks.forException(unavailable())

        // Not an empty set: "there are no remote messages" and "this device could not find out"
        // must not look the same to ConversationMigrator, which archives the legacy conversation
        // on the first of those and retries on the second.
        val outcome = runCatching { dataSource.fetchMessageIds(LEGACY_ID) }

        val thrown = outcome.exceptionOrNull()
        assertTrue(
            "Expected the server read to fail; got ${outcome.getOrNull()}",
            thrown is FirebaseFirestoreException
        )
        assertEquals(
            FirebaseFirestoreException.Code.UNAVAILABLE,
            (thrown as FirebaseFirestoreException).code
        )
    }

    // ---- fixtures -----------------------------------------------------------

    private fun snapshotOf(vararg ids: String): QuerySnapshot {
        val documents = ids.map { id -> mockk<DocumentSnapshot> { every { this@mockk.id } returns id } }
        return mockk { every { this@mockk.documents } returns documents }
    }

    private fun unavailable() = FirebaseFirestoreException(
        "Failed to get documents from server.",
        FirebaseFirestoreException.Code.UNAVAILABLE
    )

    private companion object {
        const val LEGACY_ID = "random-uuid-legacy"
    }
}
