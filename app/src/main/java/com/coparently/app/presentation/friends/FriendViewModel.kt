package com.coparently.app.presentation.friends

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coparently.app.domain.friends.CalendarFriendGrant
import com.coparently.app.domain.friends.FriendProfile
import com.coparently.app.domain.friends.FriendRole
import com.coparently.app.domain.guests.GuestAccessDuration
import com.coparently.app.domain.guests.GuestInvite
import com.coparently.app.domain.repository.FriendRepository
import com.coparently.app.presentation.pairing.pairingMessageRes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

/**
 * The friend-invite sheet's state.
 *
 * Mirrors `GuestInviteState`, and for the same reason its two steps are ordered the way they are:
 * the length of access is chosen **before** the code exists, because by the time a code has been
 * read out to somebody the invitation document already carries its expiry and cannot be changed.
 *
 * @property isOpen False when the sheet is closed.
 * @property duration How long the access will last.
 * @property invite The minted invitation, or null before [FriendViewModel.createInvite] has run.
 * @property isBusy A call is in flight.
 * @property errorRes Why the last attempt failed, as a resource the composable resolves — a
 *   ViewModel has no `Context` and must not be given one.
 */
data class FriendInviteState(
    val isOpen: Boolean = false,
    val duration: GuestAccessDuration = GuestAccessDuration.DEFAULT,
    val invite: GuestInvite? = null,
    val isBusy: Boolean = false,
    @StringRes val errorRes: Int? = null
)

/**
 * The friend's own redemption state.
 *
 * @property code What they have typed, upper-cased as they go — invite codes are upper-case and
 *   a lower-case one would fail a check the user cannot see.
 * @property isBusy A call is in flight.
 * @property accepted The grant landed; the screen switches to showing it.
 * @property errorRes Why the last attempt failed, as a resource the composable resolves.
 */
data class FriendRedeemState(
    val code: String = "",
    val isBusy: Boolean = false,
    val accepted: Boolean = false,
    @StringRes val errorRes: Int? = null
)

/**
 * Admitting, listing and removing the trusted third person (item 16), and — on the friend's own
 * device — their profile.
 *
 * One ViewModel for both sides because they are one relationship seen from its two ends, and the
 * flows are disjoint: a parent's device has grants and no profile of its own, a friend's device
 * has a profile and one grant.
 */
@HiltViewModel
class FriendViewModel @Inject constructor(
    private val friendRepository: FriendRepository
) : ViewModel() {

    /** The family's live friends, for the parents' "who can see this" list. */
    val friends: StateFlow<List<CalendarFriendGrant>> = friendRepository.observeFamilyFriends()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    /** This account's own grant, when the signed-in user is a friend rather than a parent. */
    val myGrant: StateFlow<CalendarFriendGrant?> = friendRepository.observeMyGrant()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** The friend's own profile, or null before they have written one. */
    val myProfile: StateFlow<FriendProfile?> = friendRepository.observeMyProfile()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private val _invite = MutableStateFlow(FriendInviteState())
    val invite: StateFlow<FriendInviteState> = _invite.asStateFlow()

    private val _saveError = MutableStateFlow<Int?>(null)

    /** Why the last profile save failed, or null. */
    val saveError: StateFlow<Int?> = _saveError.asStateFlow()

    /** Opens the invite sheet on the default length. */
    fun openInvite() {
        _invite.value = FriendInviteState(isOpen = true)
    }

    /** Picks a different length. Ignored once a code exists — by then it is already stamped. */
    fun chooseDuration(duration: GuestAccessDuration) {
        _invite.update { if (it.invite != null) it else it.copy(duration = duration) }
    }

    /** Mints the invitation. */
    fun createInvite() {
        val state = _invite.value
        if (state.isBusy || state.invite != null) return
        _invite.value = state.copy(isBusy = true, errorRes = null)
        viewModelScope.launch {
            friendRepository.inviteFriend(state.duration.expiryFrom(Instant.now())).fold(
                onSuccess = { created ->
                    _invite.update { it.copy(invite = created, isBusy = false) }
                },
                onFailure = { e ->
                    _invite.update { it.copy(isBusy = false, errorRes = errorRes(e)) }
                }
            )
        }
    }

    /** Closes the sheet and forgets the minted code — the invitation itself stands. */
    fun dismissInvite() {
        _invite.value = FriendInviteState()
    }

    private val _redeem = MutableStateFlow(FriendRedeemState())

    /** The friend's own "I have a code" state. */
    val redeem: StateFlow<FriendRedeemState> = _redeem.asStateFlow()

    /** Types into the code field. */
    fun updateCode(code: String) {
        _redeem.update { it.copy(code = code.uppercase(), errorRes = null, accepted = false) }
    }

    /**
     * Redeems the typed code.
     *
     * Reaches the friend callable only. A co-parent or guest code offered here comes back as
     * [com.coparently.app.domain.model.PairingError.NotFriendInvitation] and is *said*, rather
     * than quietly doing the other thing — the mistake is easy to make when somebody has been
     * sent two codes, and the remedy differs for each.
     */
    fun redeemCode() {
        val state = _redeem.value
        if (state.isBusy || state.code.isBlank()) return
        _redeem.value = state.copy(isBusy = true, errorRes = null)
        viewModelScope.launch {
            friendRepository.acceptFriendInvite(state.code).fold(
                onSuccess = { _redeem.value = FriendRedeemState(accepted = true) },
                onFailure = { e ->
                    _redeem.update { it.copy(isBusy = false, errorRes = errorRes(e)) }
                }
            )
        }
    }

    private val _viewedFriend = MutableStateFlow<String?>(null)

    /**
     * The friend whose profile the parents are reading, resolved from `friend_profiles`.
     *
     * Read on demand rather than carried on the grant: the grant holds only what the list needs
     * (a name and a face), while a phone number and a blood group are the reason the profile
     * exists at all and are worth a read of the one document being looked at.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val viewedProfile: StateFlow<FriendProfile?> = _viewedFriend
        .flatMapLatest { uid ->
            if (uid == null) flowOf(null) else friendRepository.observeFriendProfile(uid)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** Points [viewedProfile] at [friendUid]. */
    fun openFriend(friendUid: String) {
        _viewedFriend.value = friendUid
    }

    /** Ends [friendUid]'s access. */
    fun revoke(friendUid: String) {
        viewModelScope.launch { friendRepository.revokeFriend(friendUid) }
    }

    /**
     * Saves the signed-in friend's own profile.
     *
     * `familyParents` comes from the grant rather than from the form: it is the read gate, the
     * rule refuses a write that changes it, and it is not the friend's to choose.
     */
    fun saveProfile(
        name: String,
        role: FriendRole,
        phones: List<String>,
        bloodGroup: String?,
        photoUrl: String?
    ) {
        val grant = myGrant.value
        viewModelScope.launch {
            friendRepository.saveMyProfile(
                FriendProfile(
                    uid = "",
                    name = name.trim(),
                    role = role,
                    phones = phones.map { it.trim() }.filter { it.isNotEmpty() },
                    bloodGroup = bloodGroup?.trim()?.takeIf { it.isNotEmpty() },
                    photoUrl = photoUrl,
                    familyParents = grant?.familyParents ?: myProfile.value?.familyParents.orEmpty()
                )
            ).onFailure { e -> _saveError.value = errorRes(e) }
        }
    }

    /** Clears the last save error once the UI has shown it. */
    fun clearSaveError() {
        _saveError.value = null
    }

    private fun errorRes(e: Throwable): Int = e.pairingMessageRes()

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
