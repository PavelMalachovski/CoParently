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
    /**
     * Mirrors the remote side into Room and **never returns** — it collects a snapshot
     * listener for as long as its caller's scope lives.
     *
     * Named for the shape rather than for the subject (CQ-10). It was `syncWithFirestore()`,
     * the same name the one-shot repositories use, which made it look safe to await from
     * `SyncService.performFullSync()`. It is not: that call would hang, `SyncWorker` would be
     * killed at WorkManager's ten-minute ceiling, and sync would stop entirely with no
     * exception and no log. Call it from a scope that is allowed to run forever.
     */
    suspend fun observeRemote()
}
