package com.coparently.app.presentation.pairing

import android.graphics.Bitmap
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coparently.app.R
import com.coparently.app.data.analytics.AnalyticsManager
import com.coparently.app.data.remote.firebase.PairingException
import com.coparently.app.data.remote.firebase.QRCodeService
import com.coparently.app.data.repository.CustodyModelRepository
import com.coparently.app.data.repository.ParentSlotMigrator
import com.coparently.app.data.session.SignedInAccountSource
import com.coparently.app.domain.custody.SharedCustodyRead
import com.coparently.app.domain.model.AccountSummary
import com.coparently.app.domain.model.PairingError
import com.coparently.app.domain.model.PairingState
import com.coparently.app.domain.pairing.InviteCodeGenerator
import com.coparently.app.domain.pairing.PairingUri
import com.coparently.app.domain.repository.PairingRepository
import com.coparently.app.domain.repository.UserRepository
import com.coparently.app.utils.ValidationResult
import com.coparently.app.utils.ValidationUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Transient state of the pairing form — everything that is not the pairing
 * itself. Errors are string resource ids, never English literals, so the
 * screen renders them in the user's language.
 *
 * Errors are split by where they belong: [codeErrorRes] and [emailErrorRes]
 * sit under their respective input field (validation, or a redeem/invite
 * failure that concerns exactly what the user typed there), while
 * [actionErrorRes] is for actions with no associated field — accepting or
 * declining an incoming invite, unpairing, regenerating a code — and is
 * meant to be surfaced once (e.g. a snackbar) and then cleared with
 * [PairingViewModel.consumeActionError]. Without this split, a failed
 * `unpair()` — which has no field to attach to — would have nowhere to
 * show and the button would look dead.
 */
data class PairingFormState(
    val codeInput: String = "",
    val emailInput: String = "",
    val isBusy: Boolean = false,
    @StringRes val codeErrorRes: Int? = null,
    @StringRes val emailErrorRes: Int? = null,
    @StringRes val actionErrorRes: Int? = null,
    val qrBitmap: Bitmap? = null
)

/**
 * One-shot signals the pairing screen acts on once and does not keep.
 *
 * Distinct from [PairingFormState] on purpose: state is re-read on every recomposition, and a
 * navigation instruction re-read on every recomposition navigates twice.
 */
sealed interface PairingEvent {
    /**
     * Both parents have an active custody pattern and the two disagree — the accepter has to
     * choose which one the pair keeps. The two patterns themselves travel in
     * [PendingCustodyConflict]; this only says the screen should open.
     */
    data object ChooseCustodySchedule : PairingEvent
}

/**
 * ViewModel for the pairing screen: exposes the realtime [PairingState] from
 * the repository plus the local form state, and forwards its actions.
 */
@HiltViewModel
// Eight collaborators, all injected: this is a Hilt graph edge list, not a call signature
// anybody writes by hand, and grouping them behind a wrapper type would only hide which
// dependencies this screen actually has.
@Suppress("LongParameterList")
class PairingViewModel @Inject constructor(
    private val pairingRepository: PairingRepository,
    private val qrCodeService: QRCodeService,
    private val analyticsManager: AnalyticsManager,
    private val userRepository: UserRepository,
    private val parentSlotMigrator: ParentSlotMigrator,
    private val custodyModelRepository: CustodyModelRepository,
    private val pendingCustodyConflict: PendingCustodyConflict,
    signedInAccountSource: SignedInAccountSource
) : ViewModel() {

    /**
     * Navigation instructions, delivered exactly once each.
     *
     * A [Channel] rather than a `StateFlow`, because "open the conflict screen" is an event, not
     * a state to be in: buffered so emitting never blocks the accept path, and consumed by
     * whichever collector is attached.
     */
    private val _events = Channel<PairingEvent>(Channel.BUFFERED)
    val events: Flow<PairingEvent> = _events.receiveAsFlow()

    val state: StateFlow<PairingState> = pairingRepository.observePairingState()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), PairingState.Loading)

    /**
     * The account this phone is signed in as, or null while signed out.
     *
     * Both parents' phones render the same invite-code layout, so without this the screen
     * carries nothing that distinguishes one device from the other — which is precisely
     * how two accounts get mixed up during pairing.
     */
    val account: StateFlow<AccountSummary?> = signedInAccountSource.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private val _form = MutableStateFlow(PairingFormState())
    val form: StateFlow<PairingFormState> = _form.asStateFlow()

    init {
        viewModelScope.launch {
            // Only mint a code once the account is known to be unpaired. Minting one
            // unconditionally left an already-paired account holding a live 24-hour invite:
            // harmless while the callable rejects it, but the document stays redeemable and
            // becomes live again the moment the user unpairs.
            state.first { it !is PairingState.Loading }
            if (state.value is PairingState.NotPaired) refreshInvite()
        }
    }

    /** Ensures an invite code exists so the hero card always has one to show. */
    fun refreshInvite() {
        viewModelScope.launch { pairingRepository.createOrReuseInviteCode() }
    }

    /**
     * Withdraws the current code and issues a fresh one. Has no field of its
     * own, so a failure surfaces through [PairingFormState.actionErrorRes].
     */
    fun regenerateInvite() = launchAction {
        val revoked = pairingRepository.revokeActiveInvite()
        if (revoked.isSuccess) pairingRepository.createOrReuseInviteCode() else revoked
    }

    /**
     * Accepts typed or pasted input: a bare code, a full pairing URI, or share
     * text containing one. Anything else is kept as up-cased characters so the
     * user can keep typing.
     */
    fun onCodeInputChange(raw: String) {
        val code = PairingUri.extractCode(raw)
            ?: raw.trim().uppercase().filter { it in InviteCodeGenerator.ALPHABET }
                .take(InviteCodeGenerator.LENGTH)
        _form.value = _form.value.copy(codeInput = code, codeErrorRes = null)
    }

    /** Updates the email-invite field, clearing its previous validation error. */
    fun onEmailInputChange(email: String) {
        _form.value = _form.value.copy(emailInput = email, emailErrorRes = null)
    }

    /**
     * Redeems the code currently in the input field.
     *
     * Reaches the same `acceptPairingInvitation` callable as [acceptIncoming] — manual code
     * entry, QR scan and deep link all funnel into [PairingRepository.redeem] — so it can
     * flip this device's parent slot the same way and needs the same re-stamp; see
     * [withSlotReslot].
     */
    fun redeemCode() {
        val code = _form.value.codeInput
        if (!InviteCodeGenerator.isValid(code)) {
            _form.value = _form.value.copy(codeErrorRes = R.string.pairing_error_code_incomplete)
            return
        }
        launchAction(
            onError = { res -> _form.value = _form.value.copy(codeErrorRes = res) }
        ) { withSlotReslot { pairingRepository.redeem(code) } }
    }

    /** Sends an email invitation to the address currently in the email field. */
    fun sendEmailInvitation() {
        val email = _form.value.emailInput
        val validation = ValidationUtils.validateEmail(email)
        if (validation is ValidationResult.Error) {
            _form.value = _form.value.copy(emailErrorRes = R.string.pairing_error_invalid_email)
            return
        }
        launchAction(
            onSuccess = {
                analyticsManager.logInvitationSent()
                _form.value = _form.value.copy(emailInput = "")
            },
            onError = { res -> _form.value = _form.value.copy(emailErrorRes = res) }
        ) { pairingRepository.sendEmailInvitation(email) }
    }

    /**
     * Accepts an invitation addressed to this user.
     *
     * Pairing may move this device from one parent slot to the other (the inviter keeps its
     * slot, the accepter gets the other one — see `assignSlots` in `functions/index.js`), so
     * a successful accept re-stamps this user's records via [withSlotReslot].
     */
    fun acceptIncoming(invitationId: String) = launchAction(
        onSuccess = { analyticsManager.logInvitationAccepted() }
    ) { withSlotReslot { pairingRepository.acceptIncoming(invitationId) } }

    /**
     * Runs a pairing [action] that can move this device to a different parent slot, and
     * re-stamps this user's records if it did.
     *
     * Shared by [acceptIncoming] and [redeemCode]: both ultimately call the same
     * `acceptPairingInvitation` callable through different [PairingRepository] entry points
     * (accepting an addressed invitation vs. redeeming a code/QR/deep-link), and either one
     * can flip this device's slot — the same comparison has to run on both paths, or one of
     * them silently stops re-stamping.
     *
     * The before-slot is read from Room, this device's own state, before the network call;
     * the after-slot comes straight from [action]'s response, because
     * [UserRepository.getCurrentUser] reads Room only and a second local read after the call
     * would still show the stale, pre-accept value.
     *
     * The custody reconciliation runs from the same guarded block, and in this order: the slot
     * moves, then the local pattern is complemented for it, and only then are the two patterns
     * compared — see [reconcileCustody]. It is skipped whenever the re-stamp is, and for the
     * same reason: with no reported slot there is no way to know whether the pattern needs
     * complementing, and comparing an un-complemented pattern is the one outcome worse than
     * comparing none at all.
     */
    private suspend fun withSlotReslot(action: suspend () -> Result<String?>): Result<Unit> {
        val user = userRepository.getCurrentUser()
        val before = user?.role
        val result = action()
        // `getOrNull()` is null both on a genuine failure and on a success carrying a null
        // role (server did not report one) — both are cases to skip the re-stamp, not just
        // the failure, so no separate `result.isSuccess` check is needed here.
        val after = result.getOrNull()
        if (user != null && before != null && after != null) {
            reslotIfChanged(myUid = user.id, from = before, to = after)
            // Detached, and launched *after* the re-stamp so the order of the two is still
            // fixed. The reconciliation waits for the pairing to be mirrored into Room, and the
            // accept must not sit behind that wait with its button spinning: the pairing itself
            // has already succeeded server-side, and saying so is not contingent on custody.
            viewModelScope.launch {
                reconcileCustody(slotChanged = before != after, mySlot = after)
            }
        }
        return result.map { }
    }

    /**
     * Brings this device's custody pattern and the pair's shared one into one answer, asking the
     * user only when the two genuinely disagree.
     *
     * The order is the sharpest thing on this branch. `CustodyModel.momDayIndices` means "the
     * days slot 1 has custody", so an accepter moved from slot 1 to slot 2 owns a pattern that
     * has silently started describing the *co-parent's* days. Complementing it is what keeps it
     * meaning "my days" — and it has to happen **before** the comparison, or the screen offers
     * the accepter their own schedule inverted, they reject it, and hand over exactly the days
     * they meant to keep.
     *
     * The shared document is read *first*, before anything touches Room's active model, and its
     * pattern is then carried by value. `CustodyModelRepository`'s mirror re-pushes a local model
     * it considers newer over the shared one, and a pre-pairing local model usually is newer —
     * so "theirs" can be gone from Firestore seconds after this runs. Whatever the user picks is
     * written last, and last write wins.
     *
     * **The complement is persisted before anything else can go wrong, and it is not a decision.**
     * It re-expresses the same arrangement for this device's new slot, so it is written through
     * [CustodyModelRepository.saveReslotted] — locally, keeping its dates — the moment it is
     * computed, whether the two patterns then turn out to agree, disagree, or be unknowable.
     * Deferring it to the conflict screen was wrong: the pending prompt lives only in memory, so
     * process death, leaving the pairing screen before the event is collected, or any failure
     * below would all have left Room holding the *un-complemented* pattern, still active, now
     * assigning the accepter's days to the co-parent with nothing to say so.
     *
     * **Only one write from here reaches Firestore**, and only when the read *proved* the pair has
     * no document. Everything else is left to the mirror, which settles Room and the document
     * against each other by the last-write-wins rule the rest of custody sync already runs on.
     * Re-stamping a pattern here would make this device win that comparison forever.
     *
     * A failure is logged and swallowed, exactly as in [reslotIfChanged]: the pairing has already
     * committed server-side and cannot be retried, so a Room or Firestore error here must fail
     * the reconciliation, not the pairing that did not fail.
     *
     * @param slotChanged Whether pairing actually moved this device to the other slot. False for
     *   the rare accept that keeps the slot, where the stored pattern already means "my days".
     * @param mySlot The slot pairing has just assigned to this device, as reported by the
     *   callable. Carried to the conflict screen because Room's own copy of it lags — see
     *   [CustodyConflictPrompt].
     */
    private suspend fun reconcileCustody(slotChanged: Boolean, mySlot: String) {
        runCatching {
            awaitPairingVisibleLocally()
            val read = custodyModelRepository.readShared()
            val mine = custodyModelRepository.getActiveModelSync()
            val mineAfterFlip = if (slotChanged) mine?.complemented() else mine
            if (slotChanged && mineAfterFlip != null) {
                custodyModelRepository.saveReslotted(mineAfterFlip)
            }

            val theirs = when (read) {
                is SharedCustodyRead.Found -> read.custody.model
                SharedCustodyRead.Absent -> null
                // Not "they have no pattern" — "we could not ask". Publishing on the strength of
                // this is exactly how a co-parent's schedule gets replaced by a device that
                // merely failed to read it. The complement above has already landed, so the
                // accepter's own calendar is right; the mirror reconciles the pair later.
                SharedCustodyRead.Unavailable -> {
                    Log.w(TAG, "The shared pattern could not be read; leaving it to the mirror")
                    return@runCatching
                }
            }

            val outcome = CustodyConflictResolver.resolve(mineAfterFlip, theirs)
            if (outcome is CustodyConflict.Conflict) {
                pendingCustodyConflict.set(CustodyConflictPrompt(outcome, mySlot))
                _events.send(PairingEvent.ChooseCustodySchedule)
                return@runCatching
            }
            // Not a question. The one case still needing a write is a pattern on this phone and
            // a document the read proved absent: publishing creates the pair's document rather
            // than replacing one, and without it pairing would leave the arrangement stranded on
            // a single phone. When they simply agree, `theirs` is non-null and nothing is
            // written — Room already holds the re-slotted pattern.
            if (theirs == null) outcome.settled?.let { custodyModelRepository.saveAndActivate(it) }
        }.onFailure { e ->
            // Cancellation is not a failure — leaving the pairing screen mid-reconcile cancels
            // this scope, and reporting that as an error would bury the ones that matter.
            if (e is CancellationException) throw e
            Log.e(TAG, "Failed to reconcile the custody pattern after pairing", e)
        }
    }

    /**
     * Waits, briefly, for the pairing to reach Room.
     *
     * `CustodyModelRepository` derives the pair — and therefore the shared document's id — from
     * Room's `partnerId`, which is written by `PairingRepositoryImpl.onPairingStateObserved` off
     * the pairing snapshot listener, *not* by the accept callable's response. Reading the shared
     * document the instant the callable returns therefore finds no pair at all, which would leave
     * the conflict screen unreachable in practice. The mirror hook is an `onEach` upstream of
     * [state], so waiting for [PairingState.Paired] usually means the Room write has already run.
     *
     * **This narrows the window; it does not close it.** `onPairingStateObserved` dedupes on a
     * single `AtomicReference` shared by every subscriber, and `observePairingState()` has four
     * independent collectors, each with its own snapshot listener. If another collector wins the
     * `getAndSet`, this chain forwards `Paired` downstream while the winner's `userDao.updateUser`
     * is still in flight, and the pair lookup below still finds a stale `partnerId`.
     *
     * That residue, and the timeout, are both safe for one reason and it is not this wait:
     * [CustodyModelRepository.readShared] reports an unknown pair as
     * [SharedCustodyRead.Unavailable] rather than as an absent document, and
     * [reconcileCustody] publishes nothing on that answer. So the worst outcome of this wait
     * being wrong is a reconciliation deferred to the mirror — never a co-parent's pattern
     * replaced by a device that could not read it.
     *
     * Bounded rather than indefinite: if the transition never arrives — a snapshot listener that
     * has not recovered, a Room write that failed — the reconciliation must still finish.
     */
    private suspend fun awaitPairingVisibleLocally() {
        withTimeoutOrNull(PAIRING_VISIBLE_TIMEOUT_MS) {
            state.first { it is PairingState.Paired }
        } ?: Log.w(TAG, "The pairing did not reach Room in time; reconciling on local data alone")
    }

    /**
     * Runs [ParentSlotMigrator.reslot] only when pairing actually moved this device from
     * [from] to [to]. Split out of [withSlotReslot] so its null-checks and this change-check
     * stay two separate conditions rather than one long expression.
     *
     * The pairing itself has already committed server-side by the time this runs, and for
     * [acceptIncoming] specifically the invitation is no longer pending — there is no way to
     * retry the accept. A migration failure (the blank-uid guard, or any `SQLiteException`)
     * must therefore never propagate: it is logged and swallowed, not reported as a failed
     * pairing, which is the one thing that did *not* fail here. The row count is logged on
     * success too, because this is a one-shot, unrepeatable pass — a silent zero would
     * otherwise be undetectable in the field.
     */
    private suspend fun reslotIfChanged(myUid: String, from: String, to: String) {
        if (from == to) return
        runCatching { parentSlotMigrator.reslot(from = from, to = to, myUid = myUid) }
            .onSuccess { changed -> Log.i(TAG, "Re-stamped $changed record(s) from $from to $to") }
            .onFailure { e -> Log.e(TAG, "Failed to re-stamp records from $from to $to", e) }
    }

    /**
     * Declines an invitation addressed to this user. Has no field of its own,
     * so a failure surfaces through [PairingFormState.actionErrorRes].
     */
    fun rejectIncoming(invitationId: String) =
        launchAction { pairingRepository.rejectIncoming(invitationId) }

    /**
     * Ends the co-parent link. Has no field of its own, so a failure surfaces
     * through [PairingFormState.actionErrorRes] rather than being silent.
     */
    fun unpair() = launchAction { pairingRepository.unpair() }

    /**
     * Renders the active invite's link as a QR bitmap.
     *
     * The QR used to live behind a dialog this opened; it is now shown inline on the pairing
     * screen, so this is called as soon as there is a code to encode rather than on a tap.
     */
    fun showQr() {
        val invite = (state.value as? PairingState.NotPaired)?.activeInvite ?: return
        viewModelScope.launch {
            val bitmap = qrCodeService.generatePairingQRCode(content = PairingUri.build(invite.code))
            _form.value = _form.value.copy(qrBitmap = bitmap)
        }
    }

    /** Clears the two field-level errors, e.g. when the user starts over. */
    fun clearError() {
        _form.value = _form.value.copy(codeErrorRes = null, emailErrorRes = null)
    }

    /** Marks a field-less action error as shown; call once it has been presented (e.g. a snackbar). */
    fun consumeActionError() {
        _form.value = _form.value.copy(actionErrorRes = null)
    }

    private fun launchAction(
        onSuccess: () -> Unit = {},
        onError: (Int) -> Unit = { res -> _form.value = _form.value.copy(actionErrorRes = res) },
        action: suspend () -> Result<*>
    ) {
        _form.value = _form.value.copy(isBusy = true, actionErrorRes = null)
        viewModelScope.launch {
            val result = action()
            _form.value = _form.value.copy(isBusy = false)
            val failure = result.exceptionOrNull()
            if (failure != null) onError(messageFor(failure)) else onSuccess()
        }
    }

    @StringRes
    private fun messageFor(throwable: Throwable): Int =
        when ((throwable as? PairingException)?.error) {
            PairingError.NotFound -> R.string.pairing_error_not_found
            PairingError.Expired -> R.string.pairing_error_expired
            PairingError.NotPending -> R.string.pairing_error_not_pending
            PairingError.SelfPairing -> R.string.pairing_error_self_pairing
            PairingError.AlreadyPaired -> R.string.pairing_error_already_paired
            PairingError.WrongRecipient -> R.string.pairing_error_wrong_recipient
            PairingError.Network -> R.string.pairing_error_network
            else -> R.string.pairing_error_unknown
        }

    private companion object {
        const val TAG = "PairingViewModel"
        const val STOP_TIMEOUT_MS = 5_000L

        /**
         * How long [awaitPairingVisibleLocally] waits for the pairing to be mirrored into Room.
         * One Firestore snapshot round-trip in practice; the cap only matters when the listener
         * is not delivering at all, and then the accept must not hang behind it.
         */
        const val PAIRING_VISIBLE_TIMEOUT_MS = 5_000L
    }
}
