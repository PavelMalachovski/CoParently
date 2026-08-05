package com.coparently.app.data.repository

import android.util.Log
import com.coparently.app.data.local.dao.CustodyModelDao
import com.coparently.app.data.local.entity.CustodyModelEntity
import com.coparently.app.data.remote.firebase.FirestoreCustodyDataSource
import com.coparently.app.domain.custody.CustodyKey
import com.coparently.app.domain.custody.SharedCustody
import com.coparently.app.domain.model.CustodyModel
import com.coparently.app.domain.model.CustodyModelType
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
@Singleton
@Suppress("TooManyFunctions")
class CustodyModelRepository(
    private val custodyModelDao: CustodyModelDao,
    private val userRepository: UserRepository,
    private val firestoreCustodyDataSource: FirestoreCustodyDataSource,
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
        firestoreCustodyDataSource: FirestoreCustodyDataSource
    ) : this(custodyModelDao, userRepository, firestoreCustodyDataSource, defaultScope())

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
     * The pair's shared pattern, read once, or null when there is none.
     *
     * The one-shot counterpart of [observeShared], for the question a stream cannot answer:
     * what the co-parent's pattern is *at the moment* pairing is accepted. A failed read
     * degrades to null with a log rather than propagating — callers run inside
     * `viewModelScope.launch`, where an uncaught `PERMISSION_DENIED` kills the process instead
     * of failing the read.
     */
    suspend fun getShared(): SharedCustody? {
        val pair = currentPair() ?: return null
        return guarded("read", pair.documentId) {
            firestoreCustodyDataSource.getCustody(pair.documentId)
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
     * Creates and saves a week-on-week-off custody model.
     *
     * @param startDate The anchor date for the pattern
     * @param momFirst If true, mom has the first week; if false, dad has the first week
     */
    suspend fun createWeekOnWeekOff(startDate: LocalDate, momFirst: Boolean = true) {
        val model = CustodyModel.weekOnWeekOff(
            id = UUID.randomUUID().toString(),
            startDate = startDate,
            momFirst = momFirst
        )
        saveAndActivate(model)
    }

    /**
     * Creates and saves a 2-2-3 custody model.
     */
    suspend fun createTwoTwoThree(startDate: LocalDate, momStartsFirst: Boolean = true) {
        val model = CustodyModel.twoTwoThree(
            id = UUID.randomUUID().toString(),
            startDate = startDate,
            momStartsFirst = momStartsFirst
        )
        saveAndActivate(model)
    }

    /**
     * Creates and saves a 3-4-4-3 custody model.
     */
    suspend fun createThreeFourFourThree(startDate: LocalDate, momStartsFirst: Boolean = true) {
        val model = CustodyModel.threeFourFourThree(
            id = UUID.randomUUID().toString(),
            startDate = startDate,
            momStartsFirst = momStartsFirst
        )
        saveAndActivate(model)
    }

    /**
     * Creates and saves a custom custody model.
     */
    suspend fun createCustom(
        startDate: LocalDate,
        patternDays: Int,
        momDayIndices: Set<Int>
    ) {
        val model = CustodyModel.custom(
            id = UUID.randomUUID().toString(),
            startDate = startDate,
            patternDays = patternDays,
            momDayIndices = momDayIndices
        )
        saveAndActivate(model)
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
            lastModifiedAt = remote.lastModifiedAt.ifBlank { existing?.lastModifiedAt ?: nowIso() }
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
            val existingCreatedAt = firestoreCustodyDataSource.getCustody(pair.documentId)
                ?.createdAt
                ?.takeIf { it.isNotBlank() }
            firestoreCustodyDataSource.setCustody(
                documentId = pair.documentId,
                participants = pair.participants,
                custody = SharedCustody(
                    model = model,
                    lastModifiedBy = pair.myUid,
                    lastModifiedAt = entity.lastModifiedAt,
                    createdAt = existingCreatedAt ?: entity.createdAt,
                    repeatYearly = entity.repeatYearly
                )
            )
        }
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
        lastModifiedAt: String = createdAt
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
            lastModifiedAt = lastModifiedAt
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
