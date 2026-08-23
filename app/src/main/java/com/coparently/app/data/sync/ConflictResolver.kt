package com.coparently.app.data.sync

import com.coparently.app.data.local.entity.EventEntity
import com.coparently.app.data.local.entity.ChildInfoEntity
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles conflict resolution when local and remote data differs.
 * Implements various strategies for resolving data conflicts during synchronization.
 */
@Singleton
class ConflictResolver @Inject constructor() {

    /**
     * Resolves conflict between local and remote event.
     *
     * Strategy:
     * 1. If one is deleted, mark as deleted
     * 2. Latest timestamp wins
     * 3. For ties, prefer current user's changes
     *
     * @param local Local event entity
     * @param remote Remote event entity
     * @param currentUserId Current user's Firebase UID
     * @return Resolved event entity
     */
    fun resolveEventConflict(
        local: EventEntity,
        remote: EventEntity,
        currentUserId: String
    ): ConflictResolution<EventEntity> {
        // Check if either is marked as deleted (if we implement soft delete in the future)
        // For now, we'll use timestamp-based resolution

        return when {
            // Remote is newer - use remote
            remote.updatedAt > local.updatedAt -> {
                ConflictResolution.UseRemote(
                    data = remote,
                    reason = "Remote version is newer (${remote.updatedAt} > ${local.updatedAt})"
                )
            }

            // Local is newer - use local
            local.updatedAt > remote.updatedAt -> {
                ConflictResolution.UseLocal(
                    data = local,
                    reason = "Local version is newer (${local.updatedAt} > ${remote.updatedAt})"
                )
            }

            // Same timestamp - prefer current user's changes
            else -> {
                if (local.lastModifiedBy == currentUserId) {
                    ConflictResolution.UseLocal(
                        data = local,
                        reason = "Timestamps match, preferring current user's changes"
                    )
                } else {
                    ConflictResolution.UseRemote(
                        data = remote,
                        reason = "Timestamps match, preferring remote user's changes"
                    )
                }
            }
        }
    }

    /**
     * Resolves conflict between local and remote child info.
     *
     * Strategy:
     * 1. If one is deleted, mark as deleted
     * 2. Latest timestamp wins
     * 3. For ties, merge data intelligently
     *
     * @param local Local child info entity
     * @param remote Remote child info entity
     * @param currentUserId Current user's Firebase UID
     * @return Resolved child info entity
     */
    fun resolveChildInfoConflict(
        local: ChildInfoEntity,
        remote: ChildInfoEntity,
        currentUserId: String
    ): ConflictResolution<ChildInfoEntity> {
        return when {
            // Remote is newer - use remote
            remote.updatedAt > local.updatedAt -> {
                ConflictResolution.UseRemote(
                    data = remote,
                    reason = "Remote version is newer"
                )
            }

            // Local is newer - use local
            local.updatedAt > remote.updatedAt -> {
                ConflictResolution.UseLocal(
                    data = local,
                    reason = "Local version is newer"
                )
            }

            // Same timestamp - try to merge
            else -> {
                // For child info, we can be more intelligent
                // Prefer the one with more complete data
                val localFieldCount = countNonNullFields(local)
                val remoteFieldCount = countNonNullFields(remote)

                if (localFieldCount > remoteFieldCount) {
                    ConflictResolution.UseLocal(
                        data = local,
                        reason = "Local has more complete data ($localFieldCount vs $remoteFieldCount fields)"
                    )
                } else if (remoteFieldCount > localFieldCount) {
                    ConflictResolution.UseRemote(
                        data = remote,
                        reason = "Remote has more complete data ($remoteFieldCount vs $localFieldCount fields)"
                    )
                } else {
                    // Same completeness - prefer current user's changes
                    if (local.lastModifiedBy == currentUserId) {
                        ConflictResolution.UseLocal(
                            data = local,
                            reason = "Equal data, preferring current user's changes"
                        )
                    } else {
                        ConflictResolution.UseRemote(
                            data = remote,
                            reason = "Equal data, preferring remote user's changes"
                        )
                    }
                }
            }
        }
    }

    /**
     * Counts non-null/non-empty fields in child info entity.
     */
    private fun countNonNullFields(childInfo: ChildInfoEntity): Int {
        var count = 1 // childName is always present
        if (childInfo.dateOfBirth != null) count++
        if (!childInfo.medicationsJson.isNullOrBlank() && childInfo.medicationsJson != "[]") count++
        if (!childInfo.activitiesJson.isNullOrBlank() && childInfo.activitiesJson != "[]") count++
        if (!childInfo.allergiesJson.isNullOrBlank() && childInfo.allergiesJson != "[]") count++
        if (!childInfo.medicalNotes.isNullOrBlank()) count++
        if (!childInfo.emergencyContactsJson.isNullOrBlank() && childInfo.emergencyContactsJson != "[]") count++
        if (!childInfo.schoolInfoJson.isNullOrBlank()) count++
        // Photographs count. This heuristic decides which side of a conflict survives, so a
        // version holding three photographs and one holding none must not score equal: without
        // this line the remote could win a tie and the photographs would be gone with no error
        // anywhere. It is the eighth place this field has to be carried, and the only one that
        // is not a map.
        if (!childInfo.medicalPhotosJson.isNullOrBlank() && childInfo.medicalPhotosJson != "[]") count++
        return count
    }
}

/**
 * Represents the result of conflict resolution.
 */
sealed class ConflictResolution<out T> {
    /**
     * Use the local version.
     */
    data class UseLocal<T>(
        val data: T,
        val reason: String
    ) : ConflictResolution<T>()

    /**
     * Use the remote version.
     */
    data class UseRemote<T>(
        val data: T,
        val reason: String
    ) : ConflictResolution<T>()

    /**
     * Merged version (future enhancement).
     */
    data class Merged<T>(
        val data: T,
        val reason: String
    ) : ConflictResolution<T>()
}

