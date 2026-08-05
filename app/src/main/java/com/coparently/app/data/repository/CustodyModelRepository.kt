package com.coparently.app.data.repository

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.coparently.app.data.local.dao.CustodyModelDao
import com.coparently.app.data.local.entity.CustodyModelEntity
import com.coparently.app.data.remote.firebase.FirestoreCustodyDataSource
import com.coparently.app.domain.custody.CustodyKey
import com.coparently.app.domain.custody.SharedCustody
import com.coparently.app.domain.model.CustodyModel
import com.coparently.app.domain.model.CustodyModelType
import com.coparently.app.domain.repository.UserRepository
import kotlinx.coroutines.CancellationException
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
class CustodyModelRepository @Inject constructor(
    private val custodyModelDao: CustodyModelDao,
    private val userRepository: UserRepository,
    private val firestoreCustodyDataSource: FirestoreCustodyDataSource
) {
    /**
     * Scope the shared upstream runs in. Owned here rather than injected, the same way
     * `ParentsSource` owns its own: the object is a process-lifetime singleton and nothing
     * outside it has any reason to cancel the sharing. `SupervisorJob` so a failure in one
     * collector cannot cancel it.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
     * The unshared upstream behind [observeShared]: the pair's document, retried with backoff,
     * mirrored into Room on the way past.
     *
     * The mirror sits here rather than on a separate collector so it runs exactly once no matter
     * how many screens observe the stream, and it is the same mirror-and-merge shape
     * `MessageRepositoryImpl` uses — minus the terminal `catch` that shape gets wrong.
     *
     * Internal rather than private so the retry and the mirror can be driven as a cold flow in
     * tests, on the test scheduler's virtual clock. Collecting [observeShared] instead would put
     * the backoff delay on the sharing scope's real dispatcher, where a test could only wait it
     * out in wall-clock time.
     */
    @VisibleForTesting
    @OptIn(ExperimentalCoroutinesApi::class)
    internal fun sharedUpstream(): Flow<SharedCustody?> =
        observePair()
            .flatMapLatest { pair ->
                if (pair == null) {
                    flowOf(null)
                } else {
                    firestoreCustodyDataSource.observeCustody(pair.documentId)
                        .retryWhen { cause, attempt ->
                            Log.w(
                                TAG,
                                "Custody listener failed for custody_models/${pair.documentId} " +
                                    "(attempt $attempt), retrying. A PERMISSION_DENIED here can " +
                                    "simply mean the pairing write has not landed yet — the " +
                                    "rules require a live pairing to read the document.",
                                cause
                            )
                            delay(RETRY_BASE_MS shl attempt.coerceAtMost(MAX_BACKOFF_SHIFT).toInt())
                            true
                        }
                }
            }
            .onEach { remote -> mirrorIntoRoom(remote) }

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
     */
    private suspend fun mirrorIntoRoom(remote: SharedCustody?) {
        if (remote == null) return
        val existing = custodyModelDao.getModelById(remote.model.id)
        val entity = remote.model.toEntity(
            createdAt = remote.createdAt.ifBlank { existing?.createdAt ?: nowIso() },
            lastModifiedAt = remote.lastModifiedAt.ifBlank { existing?.lastModifiedAt ?: nowIso() }
        ).copy(isActive = true, repeatYearly = remote.repeatYearly)
        if (entity == existing) return

        custodyModelDao.deactivateAllModels()
        custodyModelDao.insertModel(entity)
    }

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
            "Custody $operation failed for custody_models/$documentId. " +
                "Room keeps the local copy and the next save retries.",
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

        /** Keeps the shared listener warm across a tab switch or a config change. */
        const val STOP_TIMEOUT_MS = 5_000L

        /** First retry delay; doubled per attempt up to [MAX_BACKOFF_SHIFT]. */
        const val RETRY_BASE_MS = 1_000L

        /** Caps the backoff at `RETRY_BASE_MS shl 5` — 32 seconds — rather than growing forever. */
        const val MAX_BACKOFF_SHIFT = 5L
    }
}
