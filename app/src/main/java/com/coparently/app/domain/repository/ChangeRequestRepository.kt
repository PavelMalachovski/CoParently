package com.coparently.app.domain.repository

import com.coparently.app.domain.model.ChangeRequest
import com.coparently.app.domain.model.ChangeRequestStatus
import kotlinx.coroutines.flow.Flow

/**
 * Repository for event change requests. Room is the offline-first source of truth;
 * Firestore syncs requests between the two parents.
 */
interface ChangeRequestRepository {

    /** All change requests known locally, newest first. */
    fun getAllChangeRequests(): Flow<List<ChangeRequest>>

    /** Number of pending requests waiting for [userId] to respond. */
    fun getPendingIncomingCount(userId: String): Flow<Int>

    /** Requests attached to a specific event, newest first. */
    fun getChangeRequestsForEvent(eventId: String): Flow<List<ChangeRequest>>

    suspend fun getChangeRequestById(id: String): ChangeRequest?

    /** Creates the request locally, syncs it to Firestore and notifies the other parent. */
    suspend fun createChangeRequest(request: ChangeRequest)

    /**
     * Transitions [requestId] to [status] (accept/decline/cancel), stamps `respondedAt`,
     * syncs and notifies the counterparty. The caller is responsible for applying the
     * proposed time to the event when accepting.
     */
    suspend fun updateStatus(requestId: String, status: ChangeRequestStatus)

    /**
     * Mirrors remote change requests into Room for as long as it is collected.
     * Never completes on its own — launch it in a scope tied to the UI.
     */
    suspend fun syncWithFirestore()

    /**
     * Re-uploads every change request this device wrote locally but never got onto the server.
     *
     * One pass, no loop. Safe to call at any time and cheap when nothing is queued.
     */
    suspend fun flushOutbox()
}
