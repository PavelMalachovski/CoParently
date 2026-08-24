package com.coparently.app.data.repository

import android.util.Log
import com.coparently.app.data.local.preferences.EncryptedPreferences
import com.coparently.app.data.remote.firebase.FcmService
import com.coparently.app.data.remote.firebase.FirestoreFamilySettingsDataSource
import com.coparently.app.data.remote.firebase.PushPayload
import com.coparently.app.domain.custody.CustodyKey
import com.coparently.app.domain.expenses.FamilySettings
import com.coparently.app.domain.expenses.SplitRatio
import com.coparently.app.domain.expenses.SplitRatioTransition
import com.coparently.app.domain.repository.UserRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How a submitted ratio was applied.
 *
 * The screen has to say which happened: "saved" and "sent to your co-parent" are different
 * outcomes and a parent who is told the first when the second is true will believe a split they
 * have not agreed.
 */
enum class RatioSubmission {
    /** Applied straight away — nobody had to agree, because there is no co-parent yet. */
    APPLIED,

    /** Put to the co-parent. The agreed ratio does not move until they answer. */
    PROPOSED
}

/**
 * The two parents' agreement about how a shared cost divides.
 *
 * Shaped after `CustodyModelRepository`, and the one piece worth copying deliberately is
 * [submitRatio]'s branch: unpaired, or a pair with no document yet, applies the ratio outright;
 * a pair that already has one proposes it. That is what makes "set it at registration" work even
 * though pairing is the last step of the wizard — there is nobody to agree with yet, so there is
 * nothing to agree.
 *
 * **The agreed ratio is cached locally.** The balance math is offline-first and runs on every
 * expense screen, so it cannot wait on a document read; the cache is written on every observed
 * change and read by [agreedRatioOrDefault]. The *proposal* is deliberately not cached — it is
 * Firestore-only, exactly as the custody proposal is, because a pending question is not state
 * the app should answer from memory.
 */
@Singleton
class FamilySettingsRepository @Inject constructor(
    private val dataSource: FirestoreFamilySettingsDataSource,
    private val userRepository: UserRepository,
    private val preferences: EncryptedPreferences,
    private val fcmService: FcmService
) {

    /**
     * The pair's settings, or null while they have none.
     *
     * Emits `null` and completes for an unpaired account rather than failing: a family of one
     * has no agreement to read, and a screen must render that as "even split", not as an error.
     */
    fun observeSettings(): Flow<FamilySettings?> = flow {
        val pair = currentPair()
        if (pair == null) {
            emit(null)
            return@flow
        }
        emitAll(
            dataSource.observeSettings(pair.documentId)
                .catch { cause ->
                    // Room is not the source of truth here — the cached ratio is — so a denial
                    // or an offline cold cache degrades to "what we last agreed", never to a
                    // crash inside a `viewModelScope.launch`.
                    Log.w(TAG, "The family settings listener ended", cause)
                    emit(null)
                }
        )
    }

    /**
     * The agreed ratio to price a **new** expense at.
     *
     * Read from the local cache rather than from Firestore: this is called on the save path of
     * every expense, and blocking a save on a document read would make an offline save fail for
     * a reason that has nothing to do with the expense.
     */
    fun agreedRatioOrDefault(): SplitRatio =
        SplitRatio.fromStored(preferences.getSplitRatioBasisPoints()) ?: SplitRatio.EVEN

    /** Records the agreed ratio locally, so the save path can read it without a round trip. */
    fun cacheAgreedRatio(ratio: SplitRatio) {
        preferences.putSplitRatioBasisPoints(ratio.momShareBasisPoints)
    }

    /**
     * Applies [ratio], or puts it to the co-parent when there is one to ask.
     *
     * @return which of the two happened, or a failure carrying the transition's own refusal.
     */
    suspend fun submitRatio(ratio: SplitRatio): Result<RatioSubmission> {
        val pair = currentPair()
        if (pair == null) {
            // Nobody to agree with. Cache it so the expense screen prices by it immediately; the
            // first write after pairing publishes it.
            cacheAgreedRatio(ratio)
            return Result.success(RatioSubmission.APPLIED)
        }

        val now = System.currentTimeMillis()
        val existing = read(pair.documentId)
        if (existing == null) {
            val settings = FamilySettings(
                ratio = ratio,
                participants = pair.participants,
                lastModifiedBy = pair.myUid,
                lastModifiedAtMillis = now
            )
            return write(pair, settings).map {
                cacheAgreedRatio(ratio)
                RatioSubmission.APPLIED
            }
        }

        val proposed = SplitRatioTransition.propose(existing, ratio, pair.myUid, now)
            .getOrElse { return Result.failure(it) }
        return write(pair, proposed).map {
            notifyPartner(pair, PushPayload.SPLIT_RATIO_PROPOSED)
            RatioSubmission.PROPOSED
        }
    }

    /** Agrees to the co-parent's proposal; the proposed ratio becomes the agreed one. */
    suspend fun acceptProposal(): Result<Unit> = decide(accept = true)

    /** Turns the co-parent's proposal down; the agreed ratio does not move. */
    suspend fun declineProposal(): Result<Unit> = decide(accept = false)

    private suspend fun decide(accept: Boolean): Result<Unit> {
        val pair = currentPair()
            ?: return Result.failure(IllegalStateException("No co-parent to agree a split with"))
        val existing = read(pair.documentId)
            ?: return Result.failure(IllegalStateException("There is no proposal to answer"))
        val now = System.currentTimeMillis()
        val next = if (accept) {
            SplitRatioTransition.accept(existing, pair.myUid, now)
        } else {
            SplitRatioTransition.decline(existing, pair.myUid, now)
        }.getOrElse { return Result.failure(it) }

        return write(pair, next).map {
            if (accept) cacheAgreedRatio(next.ratio)
            notifyPartner(
                pair,
                if (accept) PushPayload.SPLIT_RATIO_ACCEPTED else PushPayload.SPLIT_RATIO_DECLINED
            )
        }
    }

    private suspend fun read(documentId: String): FamilySettings? = try {
        dataSource.getSettings(documentId)
    } catch (e: CancellationException) {
        throw e
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        Log.w(TAG, "Could not read the family settings for $documentId", e)
        null
    }

    private suspend fun write(pair: SettingsPair, settings: FamilySettings): Result<Unit> = try {
        dataSource.setSettings(pair.documentId, settings)
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        Log.w(TAG, "The family settings write was refused for ${pair.documentId}", e)
        Result.failure(e)
    }

    /**
     * A best-effort push carrying a **type and nothing else** (SEC-3): the receiving device
     * writes the sentence from its own resources, in the reader's language.
     */
    private suspend fun notifyPartner(pair: SettingsPair, type: String) {
        val partnerUid = pair.participants.firstOrNull { it != pair.myUid } ?: return
        fcmService.queueNotificationForUser(
            targetUserId = partnerUid,
            notificationData = mapOf(PushPayload.TYPE to type)
        )
    }

    private suspend fun currentPair(): SettingsPair? {
        val uid = userRepository.getCurrentUserId() ?: return null
        val partnerId = userRepository.getUserById(uid)?.partnerId?.takeIf { it.isNotBlank() }
            ?: return null
        // The same derived id the custody document uses: two uids, sorted, joined. One formula,
        // so a pair's two documents can never disagree about which pair they belong to.
        val documentId = runCatching { CustodyKey.of(uid, partnerId) }
            .onFailure { Log.w(TAG, "Cannot derive a settings document id for this pair", it) }
            .getOrNull() ?: return null
        return SettingsPair(documentId, listOf(uid, partnerId).sorted(), uid)
    }

    private data class SettingsPair(
        val documentId: String,
        val participants: List<String>,
        val myUid: String
    )

    private companion object {
        const val TAG = "FamilySettingsRepo"
    }
}
