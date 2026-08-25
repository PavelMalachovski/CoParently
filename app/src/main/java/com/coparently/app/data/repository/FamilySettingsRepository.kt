package com.coparently.app.data.repository

import android.util.Log
import com.coparently.app.data.local.preferences.EncryptedPreferences
import com.coparently.app.data.remote.firebase.FcmService
import com.coparently.app.data.remote.firebase.FirestoreFamilySettingsDataSource
import com.coparently.app.data.remote.firebase.PushPayload
import com.coparently.app.domain.custody.CustodyKey
import com.coparently.app.domain.expenses.FULL_SHARE_BASIS_POINTS
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

    /**
     * Records the agreed ratio locally, so the save path can read it without a round trip.
     *
     * Clears any capture slot: this is a figure the pair's document already carries — or is about
     * to — and from then on the document is the record. Only [rememberUnpairedRatio] stores a
     * value that still needs anchoring.
     */
    fun cacheAgreedRatio(ratio: SplitRatio) {
        preferences.putSplitRatioBasisPoints(ratio.momShareBasisPoints)
        preferences.putSplitRatioSlot(null)
    }

    /**
     * Records a ratio chosen while there is nobody to agree it with, **and the slot it means**.
     *
     * The stored share is slot 1's — that is the schema, and `Expense.splitBasisPoints` and
     * `firestore.rules` both speak it — while what a parent sets is *their own* share. Those
     * coincide only while this device holds slot 1, which an unpaired account does by default
     * (`UserRepositoryImpl.DEFAULT_ROLE`) and which pairing can change: `assignSlots` hands out
     * the two slots, nobody chooses one. Without the capture slot, a parent who set "I pay 70",
     * then paired into slot 2, would have published a document giving *the co-parent* 70.
     */
    private suspend fun rememberUnpairedRatio(ratio: SplitRatio) {
        preferences.putSplitRatioBasisPoints(ratio.momShareBasisPoints)
        preferences.putSplitRatioSlot(signedInSlot())
    }

    /**
     * Applies [ratio], or puts it to the co-parent when there is one to ask.
     *
     * @return which of the two happened, or a failure carrying the transition's own refusal.
     */
    suspend fun submitRatio(ratio: SplitRatio): Result<RatioSubmission> {
        val pair = currentPair()
        if (pair == null) {
            // Nobody to agree with, and no document to write it to. Cached so the expense screen
            // prices by it immediately; [publishCachedRatioIfMissing] is what carries it across
            // to the pair, and it runs on the next sync after pairing. The slot goes with it —
            // see [rememberUnpairedRatio] for why a bare number is not enough.
            rememberUnpairedRatio(ratio)
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

    /**
     * Carries a ratio agreed while unpaired across to the pair, once there is one.
     *
     * The unpaired branch of [submitRatio] can only cache: there is no pair document to write and
     * nobody to ask. Nothing then published it — the comment there used to claim "the first write
     * after pairing publishes it" and no such write existed — so a parent who set 70/30 in the
     * wizard paired, saw 70/30 in Settings, and had both phones go on splitting every expense
     * evenly. The onboarding step's own wording promised the co-parent would confirm it; they
     * never saw it at all.
     *
     * Writes **only when the pair has no agreement yet**, which is what makes it safe to run on
     * every sync: it can never overwrite one the two of them have since made, nor the co-parent's
     * own cached ratio if theirs published first. Whoever's tick lands first sets the pair's
     * opening figure, and the other's next change is a proposal like any other.
     *
     * Silent about an unset cache: `null` means this parent never chose a ratio, and publishing
     * an even split on their behalf would be inventing an agreement.
     */
    suspend fun publishCachedRatioIfMissing() {
        val cached = SplitRatio.fromStored(preferences.getSplitRatioBasisPoints()) ?: return
        val pair = currentPair() ?: return
        if (read(pair.documentId) != null) return

        val anchored = reanchored(cached) ?: return
        Log.i(TAG, "Publishing the ratio agreed before pairing to ${pair.documentId}")
        write(
            pair,
            FamilySettings(
                ratio = anchored,
                participants = pair.participants,
                lastModifiedBy = pair.myUid,
                lastModifiedAtMillis = System.currentTimeMillis()
            )
        ).onSuccess {
            // The document is the record now; the cache goes back to merely mirroring it, and the
            // capture slot has done its one job.
            cacheAgreedRatio(anchored)
        }
    }

    /**
     * The cached share expressed as **slot 1's**, whatever slot it was captured under.
     *
     * Flips it when pairing moved this device to the other slot: what the parent set was their
     * own share, and the stored form is slot 1's. Returns null — publishing nothing — when the
     * current slot is unknown, because a coin toss here writes the wrong number into the one
     * document that prices every future expense; and when there is **no capture slot at all**,
     * which marks a figure that already belongs to a pair rather than one chosen before there
     * was one. Silence merely leaves a pair on an even split they can still change.
     */
    private suspend fun reanchored(cached: SplitRatio): SplitRatio? {
        val capturedSlot = preferences.getSplitRatioSlot()
            // No capture slot means this figure is already some pair's agreement of record —
            // every paired write clears it, see [cacheAgreedRatio] — so it belongs to that pair
            // and to no other. The cache is one device-wide integer with no pair key, and
            // nothing clears it on unpair: the client only invokes the callable, and
            // `AccountSwitchGuard` wipes Room and deliberately not the preferences. Publishing
            // it here would hand the *next* co-parent a split neither of them made, reviving on
            // the client the very document `unpairCoParent` deletes on the server for that
            // reason — "left behind, it would silently reattach if these two ever re-paired".
            ?: return null
        val currentSlot = signedInSlot() ?: return null
        return if (capturedSlot == currentSlot) {
            cached
        } else {
            SplitRatio(FULL_SHARE_BASIS_POINTS - cached.momShareBasisPoints)
        }
    }

    /** This device's slot, or null when it has no local profile row yet. */
    private suspend fun signedInSlot(): String? {
        val uid = userRepository.getCurrentUserId() ?: return null
        return userRepository.getUserById(uid)?.role?.takeIf { it.isNotBlank() }
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
