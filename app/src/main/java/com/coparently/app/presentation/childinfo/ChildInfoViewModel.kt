package com.coparently.app.presentation.childinfo

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coparently.app.data.analytics.AnalyticsManager
import com.coparently.app.data.crashlytics.CrashlyticsManager
import com.coparently.app.R
import com.coparently.app.data.remote.firebase.FirebaseAuthService
import com.coparently.app.data.remote.firebase.PairingException
import com.coparently.app.domain.guests.GuestAccessDuration
import com.coparently.app.domain.guests.GuestInvite
import com.coparently.app.domain.model.ChildInfo
import com.coparently.app.domain.model.PairingError
import com.coparently.app.domain.repository.ChildInfoRepository
import com.coparently.app.domain.repository.GuestRepository
import com.coparently.app.domain.repository.MedicalPhotoStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

/**
 * Why a photograph did not make it on or off the record.
 *
 * A code, not a message: a ViewModel has no `Context` and must not acquire one to build
 * user-facing text — the same rule that keeps `GoogleCalendarSyncState.message` on the
 * localization follow-up list. The screen resolves it in composable scope, exactly as
 * `CalendarViewModel.SwapError` is resolved.
 */
enum class MedicalPhotoError {
    /** The upload failed. The photograph is not on the record and nothing is in the bucket. */
    UPLOAD_FAILED,

    /**
     * The object could not be deleted, so its URL was **kept** on the record.
     *
     * Dropping the reference anyway is the one thing this must not do: the image would stay in
     * the bucket, readable by anyone holding the link, with nothing left pointing at it to
     * delete it by.
     */
    DELETE_FAILED
}

/**
 * The guest-invite sheet on the child's screen.
 *
 * @property isOpen Whether the sheet is showing
 * @property childInfoId The record being opened up — exactly one, always
 * @property duration How long the access will last, before a code exists
 * @property invite The minted invitation, or null while none has been created yet. Once
 *   non-null the length can no longer be changed: the code already carries the expiry, and a
 *   sheet that let the parent move it afterwards would be showing a number the document does
 *   not hold
 * @property isBusy A mint is in flight
 * @property errorRes The one message to show, or null
 */
data class GuestInviteState(
    val isOpen: Boolean = false,
    val childInfoId: String = "",
    val duration: GuestAccessDuration = GuestAccessDuration.DEFAULT,
    val invite: GuestInvite? = null,
    val isBusy: Boolean = false,
    @StringRes val errorRes: Int? = null
)

/**
 * ViewModel for managing child information.
 * Handles CRUD operations and UI state for child info screens.
 */
@HiltViewModel
class ChildInfoViewModel @Inject constructor(
    private val childInfoRepository: ChildInfoRepository,
    private val medicalPhotoStorage: MedicalPhotoStorage,
    private val guestRepository: GuestRepository,
    private val firebaseAuthService: FirebaseAuthService,
    private val analyticsManager: AnalyticsManager,
    private val crashlyticsManager: CrashlyticsManager
) : ViewModel() {

    private val _uiState = MutableStateFlow<ChildInfoUiState>(ChildInfoUiState.Loading)
    val uiState: StateFlow<ChildInfoUiState> = _uiState.asStateFlow()

    private val _currentChildInfo = MutableStateFlow<ChildInfo?>(null)
    val currentChildInfo: StateFlow<ChildInfo?> = _currentChildInfo.asStateFlow()

    /** Why a photograph did not make it on or off the record, or null when nothing is wrong. */
    private val _photoError = MutableStateFlow<MedicalPhotoError?>(null)
    val photoError: StateFlow<MedicalPhotoError?> = _photoError.asStateFlow()

    /** Clears [photoError] once the screen has shown it. */
    fun clearPhotoError() {
        _photoError.value = null
    }

    /**
     * Set when revoking a guest failed, so the screen can say so.
     *
     * A silent failure here is the worst kind this feature has: the parent has decided
     * somebody should no longer be able to read their child's medical record, the row would
     * disappear from the list on the next emission if the local write went through, and they
     * would walk away believing the access is gone while `sharedWith` still holds the uid.
     */
    private val _guestRevokeFailed = MutableStateFlow(false)
    val guestRevokeFailed: StateFlow<Boolean> = _guestRevokeFailed.asStateFlow()

    /** Clears [guestRevokeFailed] once the screen has shown it. */
    fun clearGuestRevokeFailed() {
        _guestRevokeFailed.value = false
    }

    /**
     * Takes a guest's access back, now.
     *
     * Removing the grant from the map is the whole of it: `sharedWith` is **derived** at
     * upload time by `ChildInfoAudience.entitled` from the grants that are still active, so
     * dropping the entry drops the uid from the audience in the same write. Nothing here
     * touches `sharedWith` directly, and nothing should — a second place computing the
     * audience is a second place for it to disagree with the first.
     *
     * `upsertChildInfo` writes Room and Firestore in one call, so this takes effect on the
     * next read rather than at the next sync tick. If the remote write fails the row is
     * marked unsynced and retried, and [guestRevokeFailed] tells the parent it has not
     * happened yet.
     */
    fun revokeGuest(childInfo: ChildInfo, guestUid: String) {
        if (guestUid !in childInfo.guests) return
        viewModelScope.launch {
            try {
                childInfoRepository.upsertChildInfo(
                    childInfo.copy(guests = childInfo.guests - guestUid)
                )
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception
            ) {
                crashlyticsManager.recordException(e)
                _guestRevokeFailed.value = true
            }
        }
    }

    /** The guest-invite sheet's state; [GuestInviteState.isOpen] is false when it is closed. */
    private val _guestInvite = MutableStateFlow(GuestInviteState())
    val guestInvite: StateFlow<GuestInviteState> = _guestInvite.asStateFlow()

    /** Opens the invite sheet for [childInfoId], on the default length. */
    fun openGuestInvite(childInfoId: String) {
        _guestInvite.value = GuestInviteState(isOpen = true, childInfoId = childInfoId)
    }

    /** Closes the sheet and forgets the code it was showing. */
    fun dismissGuestInvite() {
        _guestInvite.value = GuestInviteState()
    }

    /** Changes how long the access being offered will last. Ignored once a code exists. */
    fun chooseGuestDuration(duration: GuestAccessDuration) {
        val current = _guestInvite.value
        if (current.invite != null) return
        _guestInvite.value = current.copy(duration = duration, errorRes = null)
    }

    /**
     * Mints the invitation, using the length currently chosen.
     *
     * The expiry is computed here, from `Instant.now()`, rather than by the repository or the
     * callable: it is the parent's choice, and it has to be the same value the sheet has been
     * showing them. Recomputing it further down would silently move the date they agreed to.
     */
    fun createGuestInvite() {
        val current = _guestInvite.value
        if (current.isBusy || current.invite != null || current.childInfoId.isEmpty()) return
        _guestInvite.value = current.copy(isBusy = true, errorRes = null)
        viewModelScope.launch {
            val result = guestRepository.inviteGuest(
                childInfoId = current.childInfoId,
                grantExpiresAtMillis = current.duration.expiryFrom(Instant.now())
            )
            _guestInvite.value = result.fold(
                onSuccess = { _guestInvite.value.copy(isBusy = false, invite = it) },
                onFailure = {
                    _guestInvite.value.copy(isBusy = false, errorRes = guestMessageFor(it))
                }
            )
        }
    }

    /**
     * The one message for a failed invitation.
     *
     * [PairingError.GrantEnded] is reachable here in exactly one way — the sheet sat open
     * long enough for a chosen expiry to fall into the past — and it deserves its own line
     * rather than "something went wrong", because the fix is to pick a length again.
     */
    @StringRes
    private fun guestMessageFor(throwable: Throwable): Int =
        when ((throwable as? PairingException)?.error) {
            PairingError.Network -> R.string.pairing_error_network
            PairingError.GrantEnded -> R.string.guest_error_grant_ended
            else -> R.string.guest_invite_error_unknown
        }

    init {
        loadChildInfo()
    }

    /**
     * Loads all child information.
     */
    fun loadChildInfo() {
        viewModelScope.launch {
            _uiState.value = ChildInfoUiState.Loading
            try {
                childInfoRepository.getAllChildInfo().collect { childInfoList ->
                    _uiState.value = ChildInfoUiState.Success(childInfoList)
                    if (childInfoList.isNotEmpty()) {
                        _currentChildInfo.value = childInfoList.first()
                    }
                }
            } catch (e: Exception) {
                _uiState.value = ChildInfoUiState.Error(e.message ?: "Failed to load child info")
            }
        }
    }

    /**
     * Loads specific child information by ID.
     */
    fun loadChildInfoById(id: String) {
        viewModelScope.launch {
            childInfoRepository.observeChildInfoById(id).collect { childInfo ->
                _currentChildInfo.value = childInfo
            }
        }
    }

    /**
     * Creates or updates child information.
     *
     * Takes the whole [ChildInfo] rather than one parameter per field: the caller builds it by
     * copying the loaded snapshot ([currentChildInfo]), the same rule `AddEditEventScreen` follows
     * for events, so fields the form does not surface (sync/ownership stamps, and
     * [ChildInfo.medicalProfile] before this editor existed) are never silently reset to their
     * defaults on save.
     *
     * @param childInfo The child info to persist, with all form fields already applied via `copy()`
     * @param isNewChild Whether this call creates a brand-new child, for analytics only
     */
    fun upsertChildInfo(childInfo: ChildInfo, isNewChild: Boolean) {
        viewModelScope.launch {
            try {
                val currentUser = firebaseAuthService.getCurrentUser()
                    ?: throw IllegalStateException("User not authenticated")

                val finalChildInfo = childInfo.copy(
                    createdByFirebaseUid = childInfo.createdByFirebaseUid ?: currentUser.uid,
                    lastModifiedBy = currentUser.uid,
                    syncedToFirestore = false
                )

                childInfoRepository.upsertChildInfo(finalChildInfo)

                // Log analytics event
                if (isNewChild) {
                    analyticsManager.logChildInfoAdded()
                } else {
                    analyticsManager.logChildInfoUpdated()
                }
            } catch (e: Exception) {
                crashlyticsManager.recordExceptionWithContext(
                    e,
                    // The child's *id*, never their name: a Crashlytics custom key is uploaded
                    // to Firebase and, being set on the session rather than the report, rides
                    // along on every later report from this install. A minor's name is not a
                    // diagnostic — the id points at the same row and identifies nobody.
                    mapOf("action" to "upsert_child_info", "child_id" to childInfo.id)
                )
                _uiState.value = ChildInfoUiState.Error(e.message ?: "Failed to save child info")
            }
        }
    }

    /**
     * Saves the child, having first settled its photographs.
     *
     * Photographs are uploaded **on save**, never on pick, and this is the reason: a parent who
     * attaches three photographs while adding a child and then backs out would otherwise leave
     * three objects in the bucket under a child id that was never written — unreachable and
     * undeletable forever, and medical images at that. Nothing is uploaded until there is a
     * record to hang it on. It is also the flow `AddExpenseScreen` already uses for receipts.
     *
     * **A removal deletes the object first and keeps the URL if that fails.** A reference dropped
     * from a record whose object is still in the bucket is an image nobody can see and nobody can
     * delete, still readable by anyone who kept the link. Failing loudly and leaving the
     * photograph attached is the recoverable half of that trade.
     *
     * A failed *upload* is the opposite case and is simply skipped: nothing reached the bucket,
     * so there is nothing to reference and nothing to leak. Both are reported through
     * [photoError].
     *
     * @param childInfo The record to save, carrying the photographs it already had.
     * @param isNewChild Whether this is an add rather than an edit.
     * @param newPhotoUris Content URIs of photographs picked on this device and not yet uploaded.
     * @param removedPhotoUrls URLs the user removed, to be deleted from the bucket.
     */
    fun upsertChildInfoWithPhotos(
        childInfo: ChildInfo,
        isNewChild: Boolean,
        newPhotoUris: List<String>,
        removedPhotoUrls: List<String>
    ) {
        viewModelScope.launch {
            val kept = childInfo.medicalPhotos.filter { url ->
                url !in removedPhotoUrls || !deletePhoto(url)
            }
            val added = newPhotoUris.mapNotNull { uri -> uploadPhoto(childInfo.id, uri) }
            upsertChildInfo(childInfo.copy(medicalPhotos = kept + added), isNewChild)
        }
    }

    /**
     * Deletes one photograph's object.
     *
     * @return true when the object is gone and its URL may be dropped from the record.
     */
    private suspend fun deletePhoto(url: String): Boolean = try {
        medicalPhotoStorage.deleteMedicalPhoto(url)
        true
    } catch (e: CancellationException) {
        // Not a failure: the screen went away. Swallowing it here would leave the coroutine
        // machinery believing this scope is still alive. Same shape as `CustodyModelRepository`.
        throw e
    } catch (
        @Suppress("TooGenericExceptionCaught") e: Exception
    ) {
        crashlyticsManager.recordExceptionWithContext(
            e,
            mapOf("action" to "delete_medical_photo")
        )
        _photoError.value = MedicalPhotoError.DELETE_FAILED
        false
    }

    /**
     * Uploads one picked photograph.
     *
     * The photo id is a fresh UUID, and that is load-bearing rather than merely unique: the
     * Storage read rule cannot tell one signed-in user from another, so the unguessable path is
     * what stands in for one. See the `medical_photos` block in `storage.rules`, which says at
     * length that this is obscurity and not access control.
     *
     * @return the download URL, or null when the upload failed.
     */
    private suspend fun uploadPhoto(childInfoId: String, localUri: String): String? = try {
        medicalPhotoStorage.uploadMedicalPhoto(
            childInfoId = childInfoId,
            photoId = UUID.randomUUID().toString(),
            localUri = localUri
        )
    } catch (e: CancellationException) {
        throw e
    } catch (
        @Suppress("TooGenericExceptionCaught") e: Exception
    ) {
        crashlyticsManager.recordExceptionWithContext(
            e,
            mapOf("action" to "upload_medical_photo", "child_id" to childInfoId)
        )
        _photoError.value = MedicalPhotoError.UPLOAD_FAILED
        null
    }

    /**
     * Deletes child information.
     */
    fun deleteChildInfo(childInfo: ChildInfo) {
        viewModelScope.launch {
            try {
                childInfoRepository.deleteChildInfo(childInfo)
                analyticsManager.logChildInfoDeleted()
                loadChildInfo()
            } catch (e: Exception) {
                crashlyticsManager.recordExceptionWithContext(
                    e,
                    mapOf("action" to "delete_child_info", "child_id" to childInfo.id)
                )
                _uiState.value = ChildInfoUiState.Error(e.message ?: "Failed to delete child info")
            }
        }
    }

    /**
     * Syncs child information with Firestore.
     */
    fun syncChildInfo() {
        viewModelScope.launch {
            try {
                childInfoRepository.syncWithFirestore()
            } catch (e: Exception) {
                _uiState.value = ChildInfoUiState.Error(e.message ?: "Failed to sync child info")
            }
        }
    }
}

/**
 * UI state for child information screen.
 */
sealed class ChildInfoUiState {
    data object Loading : ChildInfoUiState()
    data class Success(val childInfoList: List<ChildInfo>) : ChildInfoUiState()
    data class Error(val message: String) : ChildInfoUiState()
}

