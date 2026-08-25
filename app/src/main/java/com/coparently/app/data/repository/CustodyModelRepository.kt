package com.coparently.app.data.repository

import android.util.Log
import com.coparently.app.data.local.dao.CustodyModelDao
import com.coparently.app.data.local.entity.CustodyModelEntity
import com.coparently.app.data.remote.firebase.FcmService
import com.coparently.app.data.remote.firebase.FirestoreCustodyDataSource
import com.coparently.app.data.remote.firebase.PushPayload
import com.coparently.app.domain.activity.ActivityAnnouncement
import com.coparently.app.domain.activity.ActivityAnnouncer
import com.coparently.app.domain.activity.ActivityEntityType
import com.coparently.app.domain.activity.ActivityKind
import com.coparently.app.domain.custody.CustodyKey
import com.coparently.app.domain.custody.CustodyProposalTransition
import com.coparently.app.domain.custody.CustodyWriteKind
import com.coparently.app.domain.custody.DayOverride
import com.coparently.app.domain.custody.DayOverrideStatus
import com.coparently.app.domain.custody.SharedCustody
import com.coparently.app.domain.custody.SharedCustodyRead
import com.coparently.app.domain.model.CustodyModel
import com.coparently.app.domain.model.CustodyModelType
import com.coparently.app.domain.model.MidweekContact
import com.coparently.app.domain.repository.UserRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.transform
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for managing custody model configurations.
 * Handles conversion between entities and domain models.
 *
 * Room is the source of truth and Firestore mirrors it. The pair shares exactly one custody
 * document, whose id is derived from the two UIDs by [CustodyKey], so both devices reach it
 * without a query and creating it is idempotent. An unpaired user writes Room and nothing else:
 * there is no second parent to share a document with.
 *
 * Two over detekt's function threshold, and deliberately so: the overflow is the four `create*`
 * pattern helpers, which are one-liners over [saveAndActivate] and belong beside it. Splitting
 * the class to satisfy the count would separate the write path from the patterns it writes.
 */
/**
 * Whether a submitted custody pattern took effect immediately or is waiting for the co-parent.
 *
 * [ACTIVATED] on an unpaired account or a pair's very first schedule (nobody to ask, nothing to
 * protect); [PROPOSED] once a pair has an agreed pattern that a change must not overwrite without
 * consent (owner decision, Aug 2026 walkthrough, item 7).
 */
enum class PatternSubmission { ACTIVATED, PROPOSED }

@Singleton
@Suppress("TooManyFunctions")
class CustodyModelRepository(
    private val custodyModelDao: CustodyModelDao,
    private val userRepository: UserRepository,
    private val firestoreCustodyDataSource: FirestoreCustodyDataSource,
    private val activityAnnouncer: ActivityAnnouncer,
    private val fcmService: FcmService,
    private val scope: CoroutineScope
) {
    /**
     * The constructor Hilt uses. [scope] is not part of the graph — it is a process-lifetime
     * detail of this singleton, the same way `ParentsSource` owns its own — so it is supplied
     * here rather than bound in a module.
     *
     * The four-argument constructor above exists so tests can hand in a scope backed by the test
     * scheduler and drive [observeShared] itself, backoff delays and all, on virtual time.
     * Without it the only testable seam was the unshared upstream, exposed as `internal` — a
     * production-visible way to attach a second, unshared snapshot listener, which is precisely
     * what the sharing exists to prevent.
     */
    @Inject
    constructor(
        custodyModelDao: CustodyModelDao,
        userRepository: UserRepository,
        firestoreCustodyDataSource: FirestoreCustodyDataSource,
        activityAnnouncer: ActivityAnnouncer,
        fcmService: FcmService
    ) : this(
        custodyModelDao,
        userRepository,
        firestoreCustodyDataSource,
        activityAnnouncer,
        fcmService,
        defaultScope()
    )

    /**
     * The shared remote stream. **Every subscriber gets this one flow**, because each raw
     * collection would attach its own Firestore snapshot listener, and there are two collectors
     * by design: the mirror behind [getActiveModel] and the "your co-parent changed the
     * schedule" banner.
     *
     * `replayExpirationMillis = 0` is not optional. Without it the replay cache outlives the
     * last subscriber indefinitely, and the first collector on the *next* signed-in account
     * would be served the previous account's custody schedule for a frame — Room is not cleared
     * on sign-out either, so nothing else would catch it.
     *
     * `by lazy` so that merely constructing this singleton does not reach for the user
     * repository or start anything.
     */
    private val shared: Flow<SharedCustody?> by lazy {
        sharedUpstream().shareIn(
            scope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS, replayExpirationMillis = 0),
            replay = 1
        )
    }

    /**
     * Gets the active custody model as a Flow.
     *
     * Room decides what the caller sees; the remote branch is merged in purely for its side
     * effect of folding the shared document into Room (see [mirrorOnly]). Without that branch a
     * change made on the co-parent's phone would never reach this device's calendar.
     */
    fun getActiveModel(): Flow<CustodyModel?> {
        val local = custodyModelDao.getActiveModel().map { entity -> entity?.toDomainModel() }
        return merge(shared.mirrorOnly(), local)
    }

    /**
     * The pair's one-off day swaps, keyed by ISO date.
     *
     * Read from Room, for the same reason [getActiveModel] is: Room is the source of truth and
     * the calendar must paint the agreed days offline. The remote branch is merged in only for
     * its mirroring side effect.
     *
     * Deliberately not folded onto [CustodyModel]. That type is the *pattern* — what the grid
     * asks "whose day is this" — and a swap is a fact about the shared document, the same
     * separation [SharedCustody] already draws for `lastModifiedBy` and `createdAt`. Callers join
     * the two through `CustodyResolver`, which is the one place the precedence lives.
     */
    fun observeDayOverrides(): Flow<Map<String, DayOverride>> {
        val local = custodyModelDao.getActiveModel()
            .map { entity -> DayOverrideJson.decode(entity?.dayOverridesJson) }
            .distinctUntilChanged()
        return merge(shared.mirrorOnly(), local)
    }

    /**
     * Gets the active custody model synchronously.
     */
    suspend fun getActiveModelSync(): CustodyModel? {
        return custodyModelDao.getActiveModelSync()?.toDomainModel()
    }

    /**
     * Gets all custody models.
     */
    fun getAllModels(): Flow<List<CustodyModel>> {
        return custodyModelDao.getAllModels().map { entities ->
            entities.map { it.toDomainModel() }
        }
    }

    /**
     * The pair's shared pattern, or null when there is none — no partner, or no document yet.
     *
     * Retries with backoff rather than ending on the first failure. This project already ships
     * a defect of exactly the opposite shape: both mirror branches in `MessageRepositoryImpl`
     * end in `.catch { Log.w(...) }`, which *completes* the flow, so one denied read leaves the
     * chat running on Room alone for the rest of the process — the app looks entirely healthy
     * and receives nothing. It is in CLAUDE.md's known issues with the fix named. A new
     * listener has no excuse to repeat it.
     *
     * The window that makes this concrete: the rules gate the document on a *live* pairing, so
     * a listener attached seconds before the pairing callable finishes writing `partnerId` is
     * denied — once, transiently, exactly the case a terminal `catch` would turn permanent.
     */
    fun observeShared(): Flow<SharedCustody?> = shared

    /**
     * The pair's shared pattern, read once.
     *
     * The one-shot counterpart of [observeShared], for the question a stream cannot answer:
     * what the co-parent's pattern is *at the moment* pairing is accepted.
     *
     * Answers with a [SharedCustodyRead] rather than a nullable [SharedCustody] because the
     * caller's next move differs by *why* there is nothing: [SharedCustodyRead.Absent] means the
     * pair has no document and one may safely be created, while [SharedCustodyRead.Unavailable]
     * means the question could not be answered and nothing may be published on the strength of
     * it. Collapsing the two is how a device that merely failed to read decides the co-parent has
     * no schedule and replaces it.
     *
     * Never propagates: callers run inside `viewModelScope.launch`, where an uncaught
     * `PERMISSION_DENIED` kills the process instead of failing the read. Cancellation is
     * rethrown — it is not a failure.
     */
    suspend fun readShared(): SharedCustodyRead {
        val pair = currentPair() ?: return SharedCustodyRead.Unavailable
        return try {
            firestoreCustodyDataSource.getCustody(pair.documentId)
                ?.let { SharedCustodyRead.Found(it) }
                ?: SharedCustodyRead.Absent
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception
        ) {
            Log.w(
                TAG,
                "Could not read custody_models/${pair.documentId}. Reported as Unavailable, not " +
                    "as an absent document: a caller that mistook the two would publish its own " +
                    "pattern over a co-parent's that is simply unreadable right now.",
                e
            )
            SharedCustodyRead.Unavailable
        }
    }

    /**
     * Saves a new custody model, sets it as active, and mirrors it to the pair's document.
     *
     * Room first, always: it is the source of truth, and the remote write is best-effort on top
     * of it. The four `create*` helpers below all funnel through here, so this is the single
     * write-through point — a pattern that synced from one entry point and not another would be
     * worse than one that did not sync at all.
     */
    suspend fun saveAndActivate(model: CustodyModel) {
        val entity = model.toEntity().copy(isActive = true)
        custodyModelDao.deactivateAllModels()
        custodyModelDao.insertModel(entity)
        pushToFirestore(model, entity)
    }

    /**
     * Submits [model] the right way for the account's state (owner decision, Aug 2026
     * walkthrough, item 7): a pattern change must not land on the co-parent's calendar until
     * they agree to it.
     *
     * - **Paired, with a shared document already in place** → a *proposal*. The active pattern
     *   stays in force on both phones; the co-parent gets an Accept/Decline. The proposer sees
     *   their own proposal drawn as the calendar's preview overlay plus a "waiting" banner.
     * - **Unpaired, or no shared document yet** → [saveAndActivate], because there is nobody to
     *   ask and a schedule that waited forever for an approval that can never come is worse than
     *   one that simply applies. The very first schedule of a pair also lands this way: there is
     *   no agreed pattern to protect yet.
     *
     * @return whether the pattern was activated or merely proposed, so the UI can say which.
     */
    suspend fun submitPattern(model: CustodyModel): PatternSubmission {
        val pair = currentPair()
        val existing = pair?.let { firestoreCustodyDataSource.getCustody(it.documentId) }
        if (pair == null || existing == null) {
            saveAndActivate(model)
            return PatternSubmission.ACTIVATED
        }
        val proposed = CustodyProposalTransition.propose(
            current = existing,
            model = model,
            repeatYearly = model.toEntity().repeatYearly,
            byUid = pair.myUid,
            atIso = nowIso()
        ).getOrElse { return PatternSubmission.ACTIVATED.also { saveAndActivate(model) } }

        val written = guarded("propose", pair.documentId) {
            firestoreCustodyDataSource.setCustody(pair.documentId, pair.participants, proposed)
        }
        if (written == null) {
            // The proposal write was refused or failed. Fall back to a local-only save so the
            // parent's work is not lost; the mirror settles the rest.
            saveAndActivate(model)
            return PatternSubmission.ACTIVATED
        }
        announceProposal(pair, ActivityKind.CUSTODY_PROPOSED)
        notifyPartnerOfProposal(pair, "proposed")
        return PatternSubmission.PROPOSED
    }

    /**
     * Takes up the co-parent's pending proposal: it becomes the agreed pattern and is mirrored
     * into Room so this device's calendar follows immediately, without waiting for the snapshot
     * to round-trip.
     */
    suspend fun acceptProposal(): Result<Unit> = decideProposal { current, uid, now ->
        CustodyProposalTransition.accept(current, uid, now)
    }.onSuccess { announceDecision(ActivityKind.CUSTODY_ACCEPTED) }

    /** Turns the co-parent's pending proposal down; the agreed pattern is untouched. */
    suspend fun declineProposal(note: String? = null): Result<Unit> = decideProposal { current, uid, now ->
        CustodyProposalTransition.decline(current, uid, now, note)
    }.onSuccess { announceDecision(ActivityKind.CUSTODY_DECLINED) }

    /** Withdraws this device's own pending proposal. Nothing is decided; the co-parent is told. */
    suspend fun withdrawProposal(): Result<Unit> = decideProposal { current, uid, _ ->
        CustodyProposalTransition.withdraw(current, uid)
    }

    /**
     * Reads the shared document, applies [transform], writes it back, and — when the write moved
     * the agreed pattern — mirrors the result into Room.
     *
     * The transform runs against whatever the document holds *now*, never a held snapshot: both
     * parents write one document, and a stale snapshot would drop whatever the other just did.
     */
    private suspend fun decideProposal(
        transform: (SharedCustody, String, String) -> Result<SharedCustody>
    ): Result<Unit> {
        val pair = currentPair()
            ?: return Result.failure(IllegalStateException("No co-parent to agree with"))
        val existing = firestoreCustodyDataSource.getCustody(pair.documentId)
            ?: return Result.failure(IllegalStateException("The pair has no shared schedule yet"))
        val next = transform(existing, pair.myUid, nowIso()).getOrElse { return Result.failure(it) }
        // `setCustody` returns Unit, so `guarded` yields `Unit?` — null on failure, and that is
        // all this value can say. It is the success sentinel the two sibling call sites also
        // treat it as; the thing to mirror is `next`, the document that was just written.
        guarded("decide-proposal", pair.documentId) {
            firestoreCustodyDataSource.setCustody(pair.documentId, pair.participants, next)
        } ?: return Result.failure(IllegalStateException("The decision could not be written"))
        mirrorIntoRoom(next)
        return Result.success(Unit)
    }

    private suspend fun announceProposal(pair: CustodyPair, kind: ActivityKind) {
        activityAnnouncer.announce(
            announcement = ActivityAnnouncement(
                kind = kind,
                entityType = ActivityEntityType.CUSTODY_PROPOSAL,
                entityId = pair.documentId,
                title = pair.documentId,
                whenIso = nowIso()
            )
        )
    }

    private suspend fun announceDecision(kind: ActivityKind) {
        val pair = currentPair() ?: return
        announceProposal(pair, kind)
        notifyPartnerOfProposal(pair, if (kind == ActivityKind.CUSTODY_ACCEPTED) "accepted" else "declined")
    }

    /**
     * Best-effort push to the parent who did not perform this proposal write — same mechanism
     * as [notifyPartnerOfSwap], and like it, carries no text of its own.
     */
    private suspend fun notifyPartnerOfProposal(pair: CustodyPair, action: String) {
        val partnerUid = pair.participants.firstOrNull { it != pair.myUid } ?: return
        fcmService.queueNotificationForUser(
            targetUserId = partnerUid,
            notificationData = mapOf(
                // The type is the whole payload: this notification names nothing a person
                // typed, so the receiving device has everything it needs to write the sentence
                // in the reader's language (SEC-3).
                PushPayload.TYPE to "custody_proposal_$action"
            )
        )
    }

    /**
     * Rewrites the active pattern in place: Room only, and without re-dating it.
     *
     * For a *re-expression* of an arrangement rather than a change to one — complementing a
     * pattern after pairing moved this device to the other slot, where `momDayIndices` would
     * otherwise silently start describing the co-parent's days. Nothing about who has the child
     * on which date changes; only which slot the same schedule is written from.
     *
     * Both departures from [saveAndActivate] are the point:
     *
     * - **The dates are kept.** [toEntity]'s default stamps `lastModifiedAt` with now, which
     *   would make this device the unconditional winner of every later comparison in
     *   [mirrorIntoRoom] — so the re-slot alone would cause this pattern to be republished over
     *   the co-parent's document, with nobody having chosen anything.
     * - **Nothing is pushed.** The shared document may hold a pattern this device has not been
     *   allowed to see, or one the user is at that moment being asked to choose between. Writing
     *   locally leaves that decision where it belongs and lets the ordinary last-write-wins
     *   mirror settle the rest.
     *
     * The equality guard is [mirrorIntoRoom]'s, for the same reason: an identical re-insert would
     * tick Room's invalidation tracker and re-emit to every observer for no change at all.
     */
    suspend fun saveReslotted(model: CustodyModel) {
        val existing = custodyModelDao.getModelById(model.id)
        val entity = model.toEntity(
            createdAt = existing?.createdAt ?: nowIso(),
            lastModifiedAt = existing?.lastModifiedAt ?: nowIso()
        ).copy(isActive = true, repeatYearly = existing?.repeatYearly ?: true)
        if (entity == existing) return
        custodyModelDao.deactivateAllModels()
        custodyModelDao.insertModel(entity)
    }

    /**
     * Keeps a deactivated copy of a pattern that is about to be replaced by one sharing its id.
     *
     * Room's insert REPLACEs on the primary key, so when the accepter's pattern and the shared
     * document's carry the *same* id — a re-pair whose document was created from this device's
     * own model — [saveAndActivate] would delete the rejected one rather than deactivate it.
     * The copy is stored under a fresh id, inactive, keeping the original's dates: nobody's
     * schedule disappears because of a choice made in one moment.
     *
     * Inactive rows are never pushed, so the fresh id stays local and cannot confuse the pair's
     * document.
     */
    suspend fun archiveRejected(model: CustodyModel) {
        val original = custodyModelDao.getModelById(model.id)
        val entity = model.toEntity(
            createdAt = original?.createdAt ?: nowIso(),
            lastModifiedAt = original?.lastModifiedAt ?: nowIso()
        ).copy(id = UUID.randomUUID().toString(), isActive = false)
        custodyModelDao.insertModel(entity)
    }

    /**
     * Creates and saves a week-on-week-off custody model.
     *
     * @param startDate The anchor date for the pattern
     * @param momFirst If true, mom has the first week; if false, dad has the first week
     */
    suspend fun createWeekOnWeekOff(startDate: LocalDate, momFirst: Boolean = true): PatternSubmission {
        val model = CustodyModel.weekOnWeekOff(
            id = UUID.randomUUID().toString(),
            startDate = startDate,
            momFirst = momFirst
        )
        return submitPattern(model)
    }

    /**
     * Creates and saves an every-other-weekend model — `výhradní péče se stykem` (MON-6).
     *
     * @param startDate The anchor date, expected to be the Monday the fortnight opens on
     * @param momIsResident True when slot 1 is the parent the child lives with
     * @param midweek The midweek contact day, or null for alternate weekends only
     */
    suspend fun createEveryOtherWeekend(
        startDate: LocalDate,
        momIsResident: Boolean = true,
        midweek: MidweekContact? = null
    ): PatternSubmission {
        val model = CustodyModel.everyOtherWeekend(
            id = UUID.randomUUID().toString(),
            startDate = startDate,
            momIsResident = momIsResident,
            midweek = midweek
        )
        return submitPattern(model)
    }

    /**
     * Creates and saves a 2-2-3 custody model.
     */
    suspend fun createTwoTwoThree(startDate: LocalDate, momStartsFirst: Boolean = true): PatternSubmission {
        val model = CustodyModel.twoTwoThree(
            id = UUID.randomUUID().toString(),
            startDate = startDate,
            momStartsFirst = momStartsFirst
        )
        return submitPattern(model)
    }

    /**
     * Creates and saves a 3-4-4-3 custody model.
     */
    suspend fun createThreeFourFourThree(startDate: LocalDate, momStartsFirst: Boolean = true): PatternSubmission {
        val model = CustodyModel.threeFourFourThree(
            id = UUID.randomUUID().toString(),
            startDate = startDate,
            momStartsFirst = momStartsFirst
        )
        return submitPattern(model)
    }

    /**
     * Creates and saves a custom custody model.
     */
    suspend fun createCustom(
        startDate: LocalDate,
        patternDays: Int,
        momDayIndices: Set<Int>
    ): PatternSubmission {
        val model = CustodyModel.custom(
            id = UUID.randomUUID().toString(),
            startDate = startDate,
            patternDays = patternDays,
            momDayIndices = momDayIndices
        )
        return submitPattern(model)
    }

    /**
     * Deletes a custody model.
     */
    suspend fun deleteModel(id: String) {
        custodyModelDao.deleteModelById(id)
    }

    // ---- the shared document ----------------------------------------------

    /**
     * The unshared upstream behind [observeShared]: the pair's document, mirrored into Room on
     * the way past, the whole chain retried with backoff.
     *
     * The mirror sits here rather than on a separate collector so it runs exactly once no matter
     * how many screens observe the stream, and it is the same mirror-and-merge shape
     * `MessageRepositoryImpl` uses — minus the terminal `catch` that shape gets wrong.
     *
     * **[retryWhen] wraps the whole chain, not just the snapshot listener.** Applied inside
     * [flatMapLatest] it covered only the listener, leaving two uncovered sources of failure on
     * the same coroutine: [observePair], which collects a Room flow, and the mirror below, which
     * performs two DAO writes. An exception from either escaped into the sharing coroutine,
     * where it would kill the process — while every [getActiveModel] collector saw nothing at
     * all, because a shared flow never delivers an upstream failure to its subscribers. Both
     * failure shapes this class exists to prevent, on the one path neither guard covered.
     * Retrying from the top re-derives the pair and re-attaches the listener, which is the only
     * safe reset when the failure could have come from any stage.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun sharedUpstream(): Flow<SharedCustody?> =
        observePair()
            .flatMapLatest { pair ->
                if (pair == null) {
                    flowOf(null)
                } else {
                    firestoreCustodyDataSource.observeCustody(pair.documentId)
                }
            }
            .onEach { remote -> mirrorIntoRoom(remote) }
            .retryWhen { cause, attempt ->
                Log.w(
                    TAG,
                    "Shared custody stream failed (attempt $attempt), retrying — this covers " +
                        "the pair lookup, the custody_models listener and the Room mirror. A " +
                        "PERMISSION_DENIED here can simply mean the pairing write has not " +
                        "landed yet: the rules require a live pairing to read the document.",
                    cause
                )
                delay(RETRY_BASE_MS shl attempt.coerceAtMost(MAX_BACKOFF_SHIFT).toInt())
                true
            }

    /**
     * Folds the shared document into Room, under the id it arrived with.
     *
     * Reusing the writer's id is what makes the two devices converge on one row instead of
     * accumulating a copy per sync. The document is by definition the pair's current
     * arrangement, so the mirrored row is the active one.
     *
     * The equality guard is not an optimisation: Firestore echoes every write this device makes
     * straight back, and re-inserting an identical row would tick Room's invalidation tracker,
     * re-emit to every observer, and do it again on the next echo.
     *
     * **The staleness guard is what stops this function from undoing the user's own save.**
     * [saveAndActivate] deactivates every model before inserting the new one, so a previously
     * mirrored row is left `isActive = false`; if the remote write behind it was then swallowed
     * by [guarded], the document still holds the old pattern. The equality guard alone does not
     * fire on the replay — the stored row differs from the computed one precisely in `isActive`
     * — so the mirror used to deactivate the model the user had just chosen and re-activate the
     * stale one, silently, with no error anywhere: `CustodySetupViewModel` only catches what
     * [saveAndActivate] rethrows, and it rethrows nothing. Comparing `lastModifiedAt` is the
     * same last-write-wins rule the shared document already runs on, applied in the one
     * direction it was missing.
     */
    private suspend fun mirrorIntoRoom(remote: SharedCustody?) {
        if (remote == null) return
        val existing = custodyModelDao.getModelById(remote.model.id)
        val entity = remote.model.toEntity(
            createdAt = remote.createdAt.ifBlank { existing?.createdAt ?: nowIso() },
            lastModifiedAt = remote.lastModifiedAt.ifBlank { existing?.lastModifiedAt ?: nowIso() },
            dayOverrides = remote.dayOverrides
        ).copy(isActive = true, repeatYearly = remote.repeatYearly)
        if (entity == existing) return

        val localActive = custodyModelDao.getActiveModelSync()
        if (localActive != null && isNewer(localActive.lastModifiedAt, entity.lastModifiedAt)) {
            republish(localActive)
            return
        }

        custodyModelDao.deactivateAllModels()
        custodyModelDao.insertModel(entity)
    }

    /**
     * Re-sends a local model that the document is older than.
     *
     * Not strictly required to stop the revert — refusing to mirror would do that on its own —
     * but refusing alone leaves the pair permanently disagreeing, with the co-parent's phone
     * (and Task 10's banner) reading a document that is this device's *own* lost write. Since
     * the mirror has just proved the listener is alive, the write that failed is worth one more
     * attempt: it turns a swallowed write into a recovered one at the cost of a guarded call
     * that is already written.
     *
     * This cannot loop. The re-push echoes back carrying the same `lastModifiedAt` the local row
     * holds, so the next pass finds neither side newer and mirrors normally.
     */
    private suspend fun republish(local: CustodyModelEntity) {
        pushToFirestore(local.toDomainModel(), local)
    }

    /**
     * Whether the ISO date-time [candidate] is strictly later than [reference].
     *
     * Parsed rather than compared as strings, and an unparseable value on either side answers
     * `false` — "not newer" — so a malformed timestamp degrades to the mirror behaving as it did
     * before this guard existed rather than to a local row that can never be updated again.
     *
     * Caveat worth naming: `CustodyModelEntity.lastModifiedAt` is a *naive local* date-time, so
     * two parents in different time zones do not order their writes by real time. That is a
     * property of the stored schema, not of this comparison — changing it is a Room schema
     * change, and `CLAUDE.md` records the same open question for `Event`/`Expense`/`Budget`
     * dates. Within one device, which is the case this guard exists for, it is exact.
     */
    private fun isNewer(candidate: String, reference: String): Boolean {
        val left = candidate.toLocalDateTimeOrNull() ?: return false
        val right = reference.toLocalDateTimeOrNull() ?: return false
        return left.isAfter(right)
    }

    private fun String.toLocalDateTimeOrNull(): LocalDateTime? =
        runCatching { LocalDateTime.parse(this) }.getOrNull()

    /**
     * Pushes the just-saved model to the pair's document, guarded.
     *
     * Guarded because an uncaught `PERMISSION_DENIED` from a suspend call inside
     * `viewModelScope.launch` crashes the app rather than failing the sync — three ViewModels
     * call [saveAndActivate] from exactly there. `addBudget`/`deleteBudget` gained the same
     * guard for the same reason.
     *
     * `createdAt` comes from the existing document when there is one, so an update does not
     * re-date the pair's arrangement, and `lastModifiedBy` is the signed-in uid on every write:
     * it is what tells the co-parent's device that the change was not its own echo.
     */
    private suspend fun pushToFirestore(model: CustodyModel, entity: CustodyModelEntity) {
        val pair = currentPair() ?: return
        guarded("write", pair.documentId) {
            val existing = firestoreCustodyDataSource.getCustody(pair.documentId)
            val existingCreatedAt = existing?.createdAt?.takeIf { it.isNotBlank() }
            firestoreCustodyDataSource.setCustody(
                documentId = pair.documentId,
                participants = pair.participants,
                custody = SharedCustody(
                    model = model,
                    lastModifiedBy = pair.myUid,
                    lastModifiedAt = entity.lastModifiedAt,
                    createdAt = existingCreatedAt ?: entity.createdAt,
                    repeatYearly = entity.repeatYearly,
                    // Carried over, not dropped. `setCustody` replaces the whole document, so a
                    // pattern save that rebuilt this object from scratch would silently delete
                    // every agreed swap — including from `republish`, which runs with no user
                    // action behind it at all, during ordinary mirroring.
                    dayOverrides = existing?.dayOverrides.orEmpty(),
                    lastModifiedKind = CustodyWriteKind.PATTERN
                )
            )
        }
    }

    /**
     * Applies [transform] to the pair's one-off day swaps and writes the result back.
     *
     * The whole document is re-sent — `setCustody` is a `set()` — so everything else it holds is
     * read first and carried across unchanged. Two fields are handled deliberately:
     *
     * - **`lastModifiedAt` keeps the pattern's value.** It is what [isNewer] compares to decide
     *   which phone's document survives, and the winner is then *re-pushed over the loser*.
     *   Re-dating for a swap would make this device win every future comparison.
     * - **`lastModifiedBy` becomes this device's uid**, because `firestore.rules` requires every
     *   update to stamp it with the caller. [CustodyWriteKind.SWAP] is what stops the co-parent's
     *   phone reading that stamp as a pattern change; see `CustodyChangeAnnouncement`.
     *
     * Refuses rather than silently no-ops when there is no pair or no shared document: a swap
     * needs somebody to accept it, and an unpaired parent moving their own day is an edit the
     * custody editor already does.
     *
     * @param date The ISO date this write touches. Named rather than derived because
     *   `firestore.rules` requires the document to carry it: Rules cannot iterate a map, so the
     *   write names its one date and the rule checks the diff affects only that key.
     * @param transform The transition to apply, from [DayOverrideTransition].
     * @return The applied map, or the transition's own failure.
     */
    suspend fun applyDayOverrides(
        date: String,
        transform: (Map<String, DayOverride>) -> Result<Map<String, DayOverride>>
    ): Result<Map<String, DayOverride>> {
        val pair = currentPair()
            ?: return Result.failure(IllegalStateException("No co-parent to agree a swap with"))
        val next = writeSwap(pair, date, transform).getOrElse { return Result.failure(it) }.overrides

        // Package C left this gap on purpose rather than adding a bespoke card the way the
        // change-request path once had: there must be exactly one way a change reaches the
        // thread, and this is it. The status the transition produced *is* the announcement —
        // offered, agreed or turned down — so nothing here has to be told which it was.
        next[date]?.let { entry ->
            announceSwap(pair, date, entry.status, dayCount = 1)
        }
        return Result.success(next)
    }

    /**
     * Applies one transition across a run of days, and announces it **once**.
     *
     * Written one date at a time because `firestore.rules` validates a `dayOverrides` diff by
     * naming its single date — Rules cannot iterate a map — so a five-day offer is five writes to
     * the same document. What must not be five times is what the co-parent receives: before this,
     * a week of swapped days produced five chat cards, five inbox rows, five stacked system
     * notifications and five sequential modal dialogs, each asking about one day of the same
     * agreement.
     *
     * **Not atomic, and the caller is told so.** The loop stops at the first refused write and
     * returns a failure carrying how many days did land; the days already written stay written,
     * as pending offers the co-parent can still answer. The alternative — an all-or-nothing write
     * — needs the rule to validate a multi-date diff, which Rules can only do by unrolling a
     * fixed number of per-date checks, and an off-by-one there silently admits an unvalidated key.
     *
     * @param dates The ISO dates to apply, in the order they should be written.
     * @param transform The transition for one date, from [DayOverrideTransition].
     * @return The map after the last successful write.
     */
    suspend fun applyDayOverridesForDates(
        dates: List<String>,
        transform: (Map<String, DayOverride>, String) -> Result<Map<String, DayOverride>>
    ): Result<Map<String, DayOverride>> {
        if (dates.isEmpty()) {
            return Result.failure(IllegalArgumentException("A swap needs at least one day"))
        }
        val pair = currentPair()
            ?: return Result.failure(IllegalStateException("No co-parent to agree a swap with"))

        var latest: Map<String, DayOverride>? = null
        val changed = mutableListOf<String>()
        for (date in dates) {
            val result = writeSwap(pair, date) { current -> transform(current, date) }
            val applied = result.getOrElse { cause ->
                // Announce whatever *did* land before reporting the failure. Returning straight
                // out left the days already written with no chat card and no push at all: the
                // co-parent's schedule had moved and nothing had told them.
                announceRun(pair, latest, changed)
                return if (changed.isEmpty()) {
                    Result.failure(cause)
                } else {
                    Result.failure(
                        IllegalStateException(
                            "Only ${changed.size} of ${dates.size} days could be offered",
                            cause
                        )
                    )
                }
            }
            latest = applied.overrides
            if (applied.changed) changed += date
        }

        val map = latest ?: return Result.failure(IllegalStateException("Nothing was written"))
        announceRun(pair, map, changed)
        return Result.success(map)
    }

    /**
     * One chat card and one push for the days a run actually moved.
     *
     * Counts [changed] rather than the dates asked for: a group being finished skips the days
     * already answered, and telling the co-parent "5 days" when two moved is the same kind of
     * wrong as telling them nothing. The status of the first changed day speaks for the run —
     * `decideGroup` and `offerAll` both give every day of a group the same status.
     */
    private suspend fun announceRun(
        pair: CustodyPair,
        map: Map<String, DayOverride>?,
        changed: List<String>
    ) {
        val first = changed.firstOrNull() ?: return
        map?.get(first)?.let { entry ->
            announceSwap(pair, first, entry.status, dayCount = changed.size)
        }
    }

    /**
     * The document write behind one day of a swap, with no announcement.
     *
     * Split out so a group can write N days and announce once; [applyDayOverrides] is this plus
     * a single-day announcement.
     */
    private suspend fun writeSwap(
        pair: CustodyPair,
        date: String,
        transform: (Map<String, DayOverride>) -> Result<Map<String, DayOverride>>
    ): Result<SwapWrite> {
        val existing = firestoreCustodyDataSource.getCustody(pair.documentId)
            ?: return Result.failure(IllegalStateException("The pair has no shared schedule yet"))

        val next = transform(existing.dayOverrides).getOrElse { return Result.failure(it) }
        if (next == existing.dayOverrides) {
            // The transform left this day alone — an already-answered day of a group being
            // finished. Writing it anyway would cost a document write and re-stamp
            // `lastModifiedBy`, which `CustodyChangeAnnouncement` reads to decide whether to
            // tell the other parent their schedule moved.
            return Result.success(SwapWrite(existing.dayOverrides, changed = false))
        }
        val written = guarded("swap", pair.documentId) {
            firestoreCustodyDataSource.setCustody(
                documentId = pair.documentId,
                participants = pair.participants,
                custody = existing.copy(
                    lastModifiedBy = pair.myUid,
                    dayOverrides = next,
                    lastSwapDate = date,
                    lastModifiedKind = CustodyWriteKind.SWAP
                )
            )
        }
        return if (written == null) {
            Result.failure(IllegalStateException("The swap could not be written"))
        } else {
            Result.success(SwapWrite(next, changed = true))
        }
    }

    /**
     * One day's write, and whether it moved anything.
     *
     * @property overrides The map as it now stands.
     * @property changed False when the transform left the day exactly as it found it, so the
     *   caller can count and announce the days that really landed rather than the days it tried.
     */
    private data class SwapWrite(
        val overrides: Map<String, DayOverride>,
        val changed: Boolean
    )

    /**
     * One chat card and one push for a swap, however many days it covers.
     *
     * @param dayCount Days in the offer. One picks the single-day wording on the other phone;
     *   more picks the grouped one, which is the whole point of the group.
     */
    private suspend fun announceSwap(
        pair: CustodyPair,
        date: String,
        status: DayOverrideStatus,
        dayCount: Int
    ) {
        activityAnnouncer.announce(
            announcement = ActivityAnnouncement(
                kind = when (status) {
                    DayOverrideStatus.PENDING -> ActivityKind.DAY_SWAP_OFFERED
                    DayOverrideStatus.ACCEPTED -> ActivityKind.DAY_SWAP_ACCEPTED
                    DayOverrideStatus.DECLINED -> ActivityKind.DAY_SWAP_DECLINED
                },
                entityType = ActivityEntityType.DAY_SWAP,
                entityId = date,
                title = date,
                whenIso = date
            )
        )
        notifyPartnerOfSwap(pair, date, status, dayCount)
    }

    /**
     * A best-effort push to the parent who did **not** perform this write, mirroring
     * `ChangeRequestRepositoryImpl.notifyCounterparty`: the owner's walkthrough asked for a
     * pop-up on the other phone (items 4/13), and the Cloud Function behind `notification_queue`
     * already fans these out. Delivery is a nicety — the document write above is the record.
     *
     * The payload carries the type and the date and no text at all: the receiving device writes
     * the sentence from its own resources, in the reader's language (SEC-3). It used to be
     * composed here, in English, which is what made a push able to claim to be anything.
     */
    private suspend fun notifyPartnerOfSwap(
        pair: CustodyPair,
        date: String,
        status: DayOverrideStatus,
        dayCount: Int
    ) {
        val partnerUid = pair.participants.firstOrNull { it != pair.myUid } ?: return
        val action = when (status) {
            DayOverrideStatus.PENDING -> "offered"
            DayOverrideStatus.ACCEPTED -> "accepted"
            DayOverrideStatus.DECLINED -> "declined"
        }
        // A run of days gets its own type carrying the count, rather than a date the reader
        // would have to expand into a range. The count is a string like every other payload
        // value: `notification_queue` documents hold strings, and the receiving device parses
        // it — a type it cannot parse composes nothing at all, which is the SEC-3 rule.
        val data = if (dayCount > 1) {
            mapOf(
                PushPayload.TYPE to "day_swap_group_$action",
                PushPayload.DATE to date,
                PushPayload.DAY_COUNT to dayCount.toString()
            )
        } else {
            mapOf(
                PushPayload.TYPE to "day_swap_$action",
                PushPayload.DATE to date
            )
        }
        fcmService.queueNotificationForUser(targetUserId = partnerUid, notificationData = data)
    }

    /**
     * The pair this device currently belongs to, or null when nobody is signed in or nobody is
     * paired.
     *
     * The two UIDs come from [UserRepository], the domain interface for exactly this lookup.
     * Never from the UI-layer `ParentsSource`, whose `observe()` would drag a whole pairing
     * subscription in here across a layer boundary this class has no business crossing —
     * `CalendarSyncRepository` had the tree's last such edge and it was removed one task ago.
     */
    private suspend fun currentPair(): CustodyPair? =
        userRepository.getCurrentUserId()?.let { uid ->
            custodyPairOf(uid, userRepository.getUserById(uid)?.partnerId)
        }

    /**
     * The pair as a stream: re-derived on sign-in, sign-out, account switch, and on the pairing
     * being mirrored into the local profile row, so the listener follows the account rather than
     * whichever pairing happened to exist when the first screen opened.
     */
    private fun observePair(): Flow<CustodyPair?> = combine(
        userRepository.observeCurrentUserId(),
        userRepository.getAllUsers()
    ) { uid, users ->
        // Matched by uid, never "the only row": sign-out does not clear Room, so a device where
        // two accounts have signed in over time holds two rows.
        custodyPairOf(uid, uid?.let { id -> users.firstOrNull { it.id == id }?.partnerId })
    }.distinctUntilChanged()

    /**
     * The document id and the sorted participants for [uid]/[partnerId], or null when there is
     * no pair to share anything with.
     *
     * [CustodyKey.of] rejects a blank, self-referential or separator-bearing uid by throwing;
     * that is a programming error at its own call sites but merely bad stored data here, so it
     * degrades to "no shared document" rather than taking down whatever collected the flow.
     */
    private fun custodyPairOf(uid: String?, partnerId: String?): CustodyPair? {
        if (uid.isNullOrBlank() || partnerId.isNullOrBlank()) return null
        val documentId = runCatching { CustodyKey.of(uid, partnerId) }
            .onFailure { Log.w(TAG, "Cannot derive a custody document id for this pair", it) }
            .getOrNull() ?: return null
        return CustodyPair(documentId, listOf(uid, partnerId).sorted(), uid)
    }

    /**
     * Runs a remote call, degrading a failure to null with a log.
     *
     * Room already holds the truth by the time this runs, so a refused or unreachable call
     * degrades to "local for now" rather than to an exception in the caller's coroutine.
     * Cancellation is rethrown — it is not a failure.
     */
    private suspend fun <T> guarded(
        operation: String,
        documentId: String,
        block: suspend () -> T
    ): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (
        @Suppress("TooGenericExceptionCaught") e: Exception
    ) {
        Log.w(
            TAG,
            "Custody $operation failed for custody_models/$documentId. Room keeps the local " +
                "copy, which the mirror will not overwrite with the older document, and " +
                "re-sends on the next snapshot.",
            e
        )
        null
    }

    // ---- helpers ----------------------------------------------------------

    /**
     * Turns the mirroring flow into one that never emits, so exactly one of the two branches
     * merged by [getActiveModel] — Room — decides what the caller sees.
     */
    private fun <T> Flow<*>.mirrorOnly(): Flow<T> = transform { }

    /**
     * Converts CustodyModelEntity to CustodyModel domain model.
     */
    private fun CustodyModelEntity.toDomainModel(): CustodyModel {
        val momDays = momDaysPattern
            .removeSurrounding("[", "]")
            .split(",")
            .filter { it.isNotBlank() }
            .map { it.trim().toInt() }
            .toSet()

        return CustodyModel(
            id = id,
            modelType = CustodyModelType.fromString(modelType),
            patternDays = patternDays,
            momDayIndices = momDays,
            startDate = LocalDate.parse(startDate),
            isActive = isActive
        )
    }

    /**
     * Converts CustodyModel domain model to CustodyModelEntity.
     *
     * @param createdAt ISO date-time to stamp; defaults to now for a locally created model and
     *   is supplied from the document when mirroring, so a synced row keeps the pair's dates.
     * @param lastModifiedAt ISO date-time of the change; defaults to [createdAt] rather than to
     *   a second `now()`, which would differ from it by a stray millisecond.
     */
    private fun CustodyModel.toEntity(
        createdAt: String = nowIso(),
        lastModifiedAt: String = createdAt,
        dayOverrides: Map<String, DayOverride> = emptyMap()
    ): CustodyModelEntity {
        val momDaysJson = momDayIndices.sorted().joinToString(",", "[", "]")

        return CustodyModelEntity(
            id = id,
            modelType = CustodyModelType.toString(modelType),
            patternDays = patternDays,
            momDaysPattern = momDaysJson,
            startDate = startDate.format(DateTimeFormatter.ISO_LOCAL_DATE),
            isActive = isActive,
            repeatYearly = true,
            createdAt = createdAt,
            lastModifiedAt = lastModifiedAt,
            // Null rather than "{}" for none, so a row that has never carried a swap is
            // byte-identical to one written before the column existed — which the equality
            // guard in `mirrorIntoRoom` depends on to stay quiet.
            dayOverridesJson = DayOverrideJson.encode(dayOverrides)
        )
    }

    private fun nowIso(): String =
        LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

    /**
     * The two parents behind one custody document.
     *
     * @property documentId The derived id, from [CustodyKey.of].
     * @property participants Both UIDs, sorted — the order `firestore.rules` requires the stored
     *   array to be in, and to stay in for every later update.
     * @property myUid The signed-in parent, stamped as `lastModifiedBy`.
     */
    private data class CustodyPair(
        val documentId: String,
        val participants: List<String>,
        val myUid: String
    )

    private companion object {
        const val TAG = "CustodyModelRepo"

        /**
         * The scope the production sharing runs in: process-lifetime, `SupervisorJob` so a
         * failure in one collector cannot cancel it.
         *
         * The [CoroutineExceptionHandler] is the backstop the sharing coroutine had none of.
         * `shareIn` launches a root coroutine here; a root coroutine's uncaught exception goes to
         * the handler if there is one and to Android's default uncaught handler if there is not —
         * which kills the app. [sharedUpstream]'s `retryWhen` now covers every stage and should
         * leave nothing to escape, but "should" is what the chat listener's author had too. If
         * one ever does, it must fail the sync, not the process.
         */
        fun defaultScope(): CoroutineScope = CoroutineScope(
            SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, throwable ->
                Log.e(
                    TAG,
                    "The shared custody stream died with an uncaught exception. Custody no " +
                        "longer syncs for the rest of this process; Room keeps serving the " +
                        "local pattern. This should be unreachable — retryWhen covers the " +
                        "whole upstream — so treat it as a bug report, not as weather.",
                    throwable
                )
            }
        )

        /** Keeps the shared listener warm across a tab switch or a config change. */
        const val STOP_TIMEOUT_MS = 5_000L

        /** First retry delay; doubled per attempt up to [MAX_BACKOFF_SHIFT]. */
        const val RETRY_BASE_MS = 1_000L

        /** Caps the backoff at `RETRY_BASE_MS shl 5` — 32 seconds — rather than growing forever. */
        const val MAX_BACKOFF_SHIFT = 5L
    }
}
