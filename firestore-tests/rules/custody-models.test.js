/**
 * The one custody document a pair shares. Gated on a `participants` array that must match the
 * derived document id; read/update additionally require the pairing to still be live, so an
 * ex-partner loses access without the document itself ever changing. Read by id only, so no
 * list query has to mirror the rule — enforced with `allow get`, not `allow read`.
 */

const {
  CURRENT_RULES, testEnv, seed, assertSucceeds, assertFails,
} = require('../harness');

const PROJECT = 'demo-coplanly-custody';
const MOM = 'uid-mom';
const DAD = 'uid-dad';
const STRANGER = 'uid-stranger';
const KEY = [MOM, DAD].sort().join('__');
const PATH = `custody_models/${KEY}`;

/**
 * The instant the document is ordered by, matching the ISO string beside it.
 *
 * Epoch millis rather than the naive local string this schema used to compare: that string had
 * no zone, so two parents two hours apart could have the wrong pattern win *and overwrite*.
 * `CustodyTimestamps` on the Kotlin side is the statement all of this agrees with.
 */
const MODIFIED_AT_MILLIS = Date.parse('2026-08-03T08:00:00Z');

const PAIRED_USERS = {
  'users/uid-mom': {name: 'Olya', email: 'o@x.test', partnerId: DAD},
  'users/uid-dad': {name: 'Pavel', email: 'p@x.test', partnerId: MOM},
  'users/uid-stranger': {name: 'Carol', email: 'c@x.test', partnerId: ''},
};

/** Builds the document as `FirestoreCustodyDataSource` writes it. */
function custodyDoc(overrides) {
  return Object.assign({
    participants: [MOM, DAD].sort(),
    lastModifiedBy: MOM,
    modelType: 'WEEK_ON_WEEK_OFF',
    patternDays: 14,
    momDayIndices: [0, 1, 2, 3, 4, 5, 6],
    startDate: '2026-08-03',
    repeatYearly: true,
    createdAt: '2026-08-03T10:00:00',
    lastModifiedAtMillis: MODIFIED_AT_MILLIS,
    lastModifiedAt: '2026-08-03T10:00:00',
  }, overrides);
}

/**
 * One pending swap, as `FirestoreCustodyDataSource` writes it.
 *
 * @param {string} requestedBy Uid of the parent offering the day.
 * @param {string} toParent Slot taking the day.
 * @return {!Object} The sub-map stored under its ISO date.
 */
function pendingSwap(requestedBy, toParent) {
  return {
    toParent,
    requestedBy,
    requestedAt: '2026-08-23T10:00:00',
    status: 'PENDING',
  };
}

/**
 * Applies what `unpairCoParent` does to the pairing relationship, with security rules
 * disabled — the emulator cannot invoke the Cloud Function, so the effect is applied
 * directly, mirroring `unpair-revocation.test.js`'s `applyUnpairSweep`.
 *
 * @param {!Object} env Rules test environment.
 * @param {string} uidA One former co-parent.
 * @param {string} uidB The other former co-parent.
 * @return {!Promise<void>} Resolves once both profiles are cleared.
 */
async function clearPairing(env, uidA, uidB) {
  await env.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await db.doc(`users/${uidA}`).set({partnerId: ''}, {merge: true});
    await db.doc(`users/${uidB}`).set({partnerId: ''}, {merge: true});
  });
}

describe('custody_models', () => {
  let env;

  before(async () => {
    env = await testEnv(PROJECT, CURRENT_RULES);
  });

  beforeEach(async () => {
    await env.clearFirestore();
    await seed(env, PAIRED_USERS);
  });

  it('lets a participant create the pair document', async () => {
    const db = env.authenticatedContext(MOM).firestore();
    await assertSucceeds(db.doc(PATH).set(custodyDoc({})));
  });

  it('lets both participants read it', async () => {
    await seed(env, {[PATH]: custodyDoc({})});
    await assertSucceeds(env.authenticatedContext(MOM).firestore().doc(PATH).get());
    await assertSucceeds(env.authenticatedContext(DAD).firestore().doc(PATH).get());
  });

  it('lets a participant listen before the document exists', async () => {
    // What every client actually does first. `CustodyModelRepository` subscribes the shared
    // listener on startup, long before either parent has saved a schedule, so the very first
    // read of a brand-new pair is a read of a document that is not there yet. `resource` is
    // null for a missing document, so a rule that dereferences `resource.data.participants`
    // errors — and a rule error is a denial. The listener then fails permanently and the
    // stream's retry loop spins forever, which is what both handsets were observed doing.
    await assertSucceeds(env.authenticatedContext(MOM).firestore().doc(PATH).get());
  });

  it('refuses a third account the empty snapshot too', async () => {
    // The missing-document clause keys on the document id rather than on `participants`,
    // which do not exist yet. It must still name the caller, or the rule becomes an
    // existence oracle: `canonicalPairId` is derivable from any two uids, so anyone could
    // otherwise probe whether a given pair has a schedule.
    await assertFails(env.authenticatedContext(STRANGER).firestore().doc(PATH).get());
  });

  it('lets the other participant overwrite it, which is last-write-wins', async () => {
    await seed(env, {[PATH]: custodyDoc({})});
    const db = env.authenticatedContext(DAD).firestore();
    await assertSucceeds(db.doc(PATH).update({
      momDayIndices: [7, 8, 9, 10, 11, 12, 13], lastModifiedBy: DAD,
    }));
  });

  it('refuses a third account', async () => {
    await seed(env, {[PATH]: custodyDoc({})});
    const db = env.authenticatedContext(STRANGER).firestore();
    await assertFails(db.doc(PATH).get());
    await assertFails(db.doc(PATH).update({patternDays: 7}));
    await assertFails(db.doc(PATH).delete());
  });

  it('refuses a create that leaves the author out of participants', async () => {
    const db = env.authenticatedContext(STRANGER).firestore();
    // Self-stamped, so the only clause this can fail on is the membership one under test.
    await assertFails(db.doc(PATH).set(custodyDoc({lastModifiedBy: STRANGER})));
  });

  describe('the author a write claims must be the caller', () => {
    // `lastModifiedBy` is not bookkeeping: `CustodyChangeAnnouncement.toAnnounce` suppresses any
    // change whose `lastModifiedBy` equals the reader's own uid, which is how a device ignores
    // the echo of its own write. Leave the field unvalidated and either parent can overwrite the
    // shared schedule while stamping the *other's* uid on it — the co-parent's phone then files
    // the change as its own echo and says nothing. The spec's guarantee is "last write wins, but
    // never silently", and this is the one field that defeats it, in a product whose premise is
    // an adversarial counterparty.

    it('refuses a create that stamps the co-parent as the author', async () => {
      const db = env.authenticatedContext(MOM).firestore();
      await assertFails(db.doc(PATH).set(custodyDoc({lastModifiedBy: DAD})));
    });

    it('refuses an update that stamps the co-parent as the author', async () => {
      await seed(env, {[PATH]: custodyDoc({})});
      const db = env.authenticatedContext(DAD).firestore();
      await assertFails(db.doc(PATH).update({
        momDayIndices: [7, 8, 9, 10, 11, 12, 13], lastModifiedBy: MOM,
      }));
    });

    it('refuses an update that silently leaves the co-parent as the author', async () => {
      // The shape that needs no bad faith to write, only a partial update: the field is simply
      // not touched, so DAD's change keeps MOM's uid and is suppressed on MOM's phone. Every
      // real client write goes through `FirestoreCustodyDataSource.setCustody`, which always
      // stamps the signed-in uid, so nothing legitimate is refused here.
      await seed(env, {[PATH]: custodyDoc({})});
      const db = env.authenticatedContext(DAD).firestore();
      await assertFails(db.doc(PATH).update({momDayIndices: [7, 8, 9]}));
    });

    it('lets a participant stamp themselves, which is all any client ever does', async () => {
      await seed(env, {[PATH]: custodyDoc({})});
      const db = env.authenticatedContext(DAD).firestore();
      await assertSucceeds(db.doc(PATH).update({momDayIndices: [7], lastModifiedBy: DAD}));
    });
  });

  it('refuses a create whose participants are not a pair', async () => {
    const db = env.authenticatedContext(MOM).firestore();
    await assertFails(db.doc(PATH).set(custodyDoc({participants: [MOM]})));
    await assertFails(
        db.doc(PATH).set(custodyDoc({participants: [MOM, DAD, STRANGER]})));
  });

  it('refuses an update that removes the other participant', async () => {
    await seed(env, {[PATH]: custodyDoc({})});
    const db = env.authenticatedContext(MOM).firestore();
    await assertFails(db.doc(PATH).update({participants: [MOM]}));
  });

  it('refuses an update that swaps a stranger in for the co-parent', async () => {
    // Without the immutability check this passes every other clause: the author is still in
    // participants and there are still two of them - but the document's id no longer names
    // the pair it is now shared with, and the co-parent silently loses their schedule.
    await seed(env, {[PATH]: custodyDoc({})});
    const db = env.authenticatedContext(MOM).firestore();
    await assertFails(
        db.doc(PATH).update({participants: [MOM, STRANGER].sort()}));
  });

  it('lets a participant delete it, which is what unpairing does', async () => {
    await seed(env, {[PATH]: custodyDoc({})});
    const db = env.authenticatedContext(DAD).firestore();
    await assertSucceeds(db.doc(PATH).delete());
  });

  describe('the id must match its own participants (squatting)', () => {
    // Without this check, an account that IS one of the two named participants — the
    // realistic attacker is the co-parent themselves — could still create the document with
    // participants that don't actually name the pair the id encodes. That would permanently
    // squat the real pair's document: Mom could never again pass read/update/delete (she is
    // not in the stored array), and her own genuine create attempt would be evaluated as an
    // update against someone else's data and denied the same way.
    it('refuses a create whose participants do not match the derived id', async () => {
      const db = env.authenticatedContext(MOM).firestore();
      await assertFails(
          db.doc(PATH).set(custodyDoc({participants: [MOM, STRANGER].sort()})));
    });

    it('lets a participant create at the id their real pairing derives', async () => {
      // Sanity check for the same clause from the other side: this is not "any two names
      // matching the id succeeds" — the id must be canonicalPairId(participants), and here
      // it genuinely is.
      const db = env.authenticatedContext(DAD).firestore();
      await assertSucceeds(db.doc(PATH).set(custodyDoc({lastModifiedBy: DAD})));
    });
  });

  describe('the stored participants array must itself be sorted', () => {
    // canonicalPairId normalises order for the *id* comparison: [MOM, DAD] and [DAD, MOM]
    // both resolve to the same id, since the function tries both orderings internally. That
    // is not enough on its own — nothing else re-sorts what actually gets *stored*, and
    // `update` demands exact array equality, which is order-sensitive (see the "reorders"
    // case below). A client that creates with the right pair in the wrong order would
    // therefore brick the document for every future write from a client that sends
    // `CustodyKey.of`'s sorted order — permanently, since no rule path can rewrite
    // `participants` back into the matching order (delete-then-recreate is the only way out,
    // and no client path does that; Task 9 only ever `.set()`s an existing document, which
    // Firestore evaluates as `update`).
    it('refuses a create whose participants are the right pair but stored in the wrong order',
        async () => {
          const db = env.authenticatedContext(MOM).firestore();
          // [MOM, DAD] is the right pair - MOM and DAD really are each other's partner, and
          // canonicalPairId(['uid-mom', 'uid-dad']) is still KEY - but it is not the sorted
          // order ([DAD, MOM], since 'uid-dad' < 'uid-mom'), which is what must be stored.
          await assertFails(db.doc(PATH).set(custodyDoc({participants: [MOM, DAD]})));
        });

    it('leaves an unsorted document permanently un-updatable by a client that sorts',
        async () => {
          // Stands in for a document that reached this state despite the rule above - an
          // older client build, or (before this fix existed) the create case just above.
          // seed() bypasses rules entirely, which is exactly the point: this is what the rule
          // can no longer let happen, and what already-existing bad data would still suffer.
          await seed(env, {[PATH]: custodyDoc({participants: [MOM, DAD]})});
          const db = env.authenticatedContext(DAD).firestore();
          await assertFails(db.doc(PATH).update({
            participants: [MOM, DAD].sort(), patternDays: 7, lastModifiedBy: DAD,
          }));
        });
  });

  describe('access follows the live pairing, not just stored participants', () => {
    it('refuses a create between two accounts that are not currently paired', async () => {
      // MOM and STRANGER are both real, authenticated accounts, and STRANGER would be a
      // legitimate second participant by every other clause (in participants, pair size 2,
      // id matches) - but they have never paired, so isPartnerOf is false on both sides.
      const otherKey = [MOM, STRANGER].sort().join('__');
      const db = env.authenticatedContext(MOM).firestore();
      await assertFails(
          db.doc(`custody_models/${otherKey}`).set(custodyDoc({
            participants: [MOM, STRANGER].sort(),
          })));
    });

    it('denies read and update once the pairing is cleared, but still allows delete', async () => {
      await seed(env, {[PATH]: custodyDoc({})});
      await clearPairing(env, MOM, DAD);

      const momDb = env.authenticatedContext(MOM).firestore();
      const dadDb = env.authenticatedContext(DAD).firestore();

      await assertFails(momDb.doc(PATH).get());
      await assertFails(dadDb.doc(PATH).get());
      await assertFails(momDb.doc(PATH).update({patternDays: 7}));
      await assertFails(dadDb.doc(PATH).update({patternDays: 7}));

      // The document must still be deletable by either side - a stale document with a
      // cleared pairing must not become permanent by a different route than the squatting
      // one already closed above.
      await assertSucceeds(dadDb.doc(PATH).delete());
    });
  });

  describe('the .set() write path Task 9 actually uses', () => {
    // FirestoreCustodyDataSource calls `.set()` over a document that may already exist, which
    // Firestore's rules evaluate as an `update`, not a `create` - the two verbs are keyed on
    // whether the document exists yet, not on which SDK method the client called.
    it('lets a full .set() with the identical sorted participants overwrite the document',
        async () => {
          await seed(env, {[PATH]: custodyDoc({})});
          const db = env.authenticatedContext(MOM).firestore();
          await assertSucceeds(db.doc(PATH).set(custodyDoc({
            patternDays: 7, momDayIndices: [0, 1, 2],
          })));
        });

    it('refuses a full .set() that omits participants over an existing document', async () => {
      await seed(env, {[PATH]: custodyDoc({})});
      const withoutParticipants = custodyDoc({});
      delete withoutParticipants.participants;
      const db = env.authenticatedContext(MOM).firestore();
      await assertFails(db.doc(PATH).set(withoutParticipants));
    });
  });

  it('refuses an update that only reorders participants', async () => {
    // The id is the sorted join, so the stored array is only ever meaningfully "the same
    // pair" in one order; `==` on the array is order-sensitive, and that is the right
    // answer here, not an accident of how Firestore compares arrays.
    await seed(env, {[PATH]: custodyDoc({})});
    const db = env.authenticatedContext(MOM).firestore();
    await assertFails(db.doc(PATH).update({participants: [MOM, DAD]}));
  });

  describe('one-off day swaps', () => {
    // A swap is an agreement, so the rule has to enforce the one thing that makes it one: the
    // parent who offered a day may not be the one who grants it. `DayOverrideTransition` refuses
    // it client-side too, but a client is not where an adversarial counterparty is stopped.
    const DATE = '2026-09-05';

    it('lets a participant offer a day', async () => {
      await seed(env, {[PATH]: custodyDoc({})});
      const db = env.authenticatedContext(MOM).firestore();
      await assertSucceeds(db.doc(PATH).update({
        dayOverrides: {[DATE]: pendingSwap(MOM, 'dad')},
        lastModifiedBy: MOM,
        lastModifiedKind: 'SWAP',
        lastSwapDate: DATE,
      }));
    });

    it('lets the other parent decide a swap they did not request', async () => {
      await seed(env, {
        [PATH]: custodyDoc({dayOverrides: {[DATE]: pendingSwap(MOM, 'dad')}}),
      });
      const db = env.authenticatedContext(DAD).firestore();
      await assertSucceeds(db.doc(PATH).update({
        dayOverrides: {
          [DATE]: Object.assign(pendingSwap(MOM, 'dad'), {
            status: 'ACCEPTED', decidedBy: DAD, decidedAt: '2026-08-23T11:00:00',
          }),
        },
        lastModifiedBy: DAD,
        lastModifiedKind: 'SWAP',
        lastSwapDate: DATE,
      }));
    });

    it('refuses the requester deciding their own swap', async () => {
      // The whole point. Without this either parent grants themselves a day and the other is
      // merely told - which is an announcement, not an agreement.
      await seed(env, {
        [PATH]: custodyDoc({dayOverrides: {[DATE]: pendingSwap(MOM, 'dad')}}),
      });
      const db = env.authenticatedContext(MOM).firestore();
      await assertFails(db.doc(PATH).update({
        dayOverrides: {
          [DATE]: Object.assign(pendingSwap(MOM, 'dad'), {
            status: 'ACCEPTED', decidedBy: MOM, decidedAt: '2026-08-23T11:00:00',
          }),
        },
        lastModifiedBy: MOM,
        lastModifiedKind: 'SWAP',
        lastSwapDate: DATE,
      }));
    });

    it('refuses the requester declining their own swap too', async () => {
      // Declining one's own offer looks harmless, but it writes the record the other parent
      // reads: it would tell them they turned down something they were never shown.
      await seed(env, {
        [PATH]: custodyDoc({dayOverrides: {[DATE]: pendingSwap(MOM, 'dad')}}),
      });
      const db = env.authenticatedContext(MOM).firestore();
      await assertFails(db.doc(PATH).update({
        dayOverrides: {
          [DATE]: Object.assign(pendingSwap(MOM, 'dad'), {
            status: 'DECLINED', decidedBy: MOM, decidedAt: '2026-08-23T11:00:00',
          }),
        },
        lastModifiedBy: MOM,
        lastModifiedKind: 'SWAP',
        lastSwapDate: DATE,
      }));
    });

    it('refuses a decision that stamps the other parent as the decider', async () => {
      // The mirror of the `lastModifiedBy` rule, one level down: an unvalidated `decidedBy`
      // would let the requester grant themselves the day while crediting the co-parent.
      await seed(env, {
        [PATH]: custodyDoc({dayOverrides: {[DATE]: pendingSwap(MOM, 'dad')}}),
      });
      const db = env.authenticatedContext(DAD).firestore();
      await assertFails(db.doc(PATH).update({
        dayOverrides: {
          [DATE]: Object.assign(pendingSwap(MOM, 'dad'), {
            status: 'ACCEPTED', decidedBy: MOM, decidedAt: '2026-08-23T11:00:00',
          }),
        },
        lastModifiedBy: DAD,
        lastModifiedKind: 'SWAP',
        lastSwapDate: DATE,
      }));
    });

    it('refuses a swap write that names the wrong date', async () => {
      // The named date is what the rule checks the entry at; Rules cannot iterate a map, so a
      // lie here has to be self-defeating rather than merely useless.
      await seed(env, {[PATH]: custodyDoc({})});
      const db = env.authenticatedContext(MOM).firestore();
      await assertFails(db.doc(PATH).update({
        dayOverrides: {[DATE]: pendingSwap(MOM, 'dad')},
        lastModifiedBy: MOM,
        lastModifiedKind: 'SWAP',
        lastSwapDate: '2026-09-06',
      }));
    });

    it('refuses an offer that credits the co-parent as its author', async () => {
      // `requestedBy` is what stops a parent deciding their own swap, so a forged author here
      // would defeat the whole mechanism one level down: offer as "them", then accept as you.
      await seed(env, {[PATH]: custodyDoc({})});
      const db = env.authenticatedContext(MOM).firestore();
      await assertFails(db.doc(PATH).update({
        dayOverrides: {[DATE]: pendingSwap(DAD, 'mom')},
        lastModifiedBy: MOM,
        lastModifiedKind: 'SWAP',
        lastSwapDate: DATE,
      }));
    });

    it('refuses a pattern rewrite riding along on a write stamped as a swap', async () => {
      // `CustodyChangeAnnouncement` suppresses the banner for a SWAP stamp. Without the
      // only-the-swap clause, that stamp becomes a way to replace the co-parent's schedule in
      // total silence - the same silencing the `lastModifiedBy` rule exists to prevent.
      await seed(env, {[PATH]: custodyDoc({})});
      const db = env.authenticatedContext(MOM).firestore();
      await assertFails(db.doc(PATH).update({
        dayOverrides: {[DATE]: pendingSwap(MOM, 'dad')},
        momDayIndices: [7, 8, 9, 10, 11, 12, 13],
        lastModifiedBy: MOM,
        lastModifiedKind: 'SWAP',
        lastSwapDate: DATE,
      }));
    });

    it('refuses a swap write that re-dates the document', async () => {
      // The legacy string. Still refused, and for a second reason now: it is a naive local
      // date-time, so a co-parent in another zone re-deriving it from the same instant would
      // produce a different string and have their swap denied - which is why the client carries
      // it verbatim rather than recomputing it.
      await seed(env, {[PATH]: custodyDoc({})});
      const db = env.authenticatedContext(MOM).firestore();
      await assertFails(db.doc(PATH).update({
        dayOverrides: {[DATE]: pendingSwap(MOM, 'dad')},
        lastModifiedAt: '2026-09-01T08:00:00',
        lastModifiedBy: MOM,
        lastModifiedKind: 'SWAP',
        lastSwapDate: DATE,
      }));
    });

    it('refuses a swap write that moves the instant the document is ordered by', async () => {
      // `lastModifiedAtMillis` is what decides which phone's document survives, and the winner
      // is re-pushed over the loser - so a swap that moved it would make this device win every
      // future comparison and quietly overwrite the co-parent's pattern.
      await seed(env, {[PATH]: custodyDoc({})});
      const db = env.authenticatedContext(MOM).firestore();
      await assertFails(db.doc(PATH).update({
        dayOverrides: {[DATE]: pendingSwap(MOM, 'dad')},
        lastModifiedAtMillis: MODIFIED_AT_MILLIS + 86400000,
        lastModifiedBy: MOM,
        lastModifiedKind: 'SWAP',
        lastSwapDate: DATE,
      }));
    });

    it('allows a swap that leaves both timestamps alone', async () => {
      // The companion to the two above: the rule must still admit the ordinary swap. Without
      // this, a `hasOnly` list that accidentally excluded a key a real swap does write would
      // read as "correctly strict" while breaking the feature outright.
      await seed(env, {[PATH]: custodyDoc({})});
      const db = env.authenticatedContext(MOM).firestore();
      await assertSucceeds(db.doc(PATH).update({
        dayOverrides: {[DATE]: pendingSwap(MOM, 'dad')},
        lastModifiedBy: MOM,
        lastModifiedKind: 'SWAP',
        lastSwapDate: DATE,
      }));
    });

    it('refuses a create that bakes in an already-accepted swap', async () => {
      // Whoever's client wins the race to create the deterministic-id document would otherwise
      // grant themselves a day before any restriction on deciding one can apply.
      const db = env.authenticatedContext(MOM).firestore();
      await assertFails(db.doc(PATH).set(custodyDoc({
        dayOverrides: {
          [DATE]: Object.assign(pendingSwap(MOM, 'dad'), {status: 'ACCEPTED', decidedBy: DAD}),
        },
      })));
    });

    it('refuses a non-participant either half', async () => {
      await seed(env, {
        [PATH]: custodyDoc({dayOverrides: {[DATE]: pendingSwap(MOM, 'dad')}}),
      });
      const db = env.authenticatedContext(STRANGER).firestore();
      await assertFails(db.doc(PATH).update({
        dayOverrides: {[DATE]: pendingSwap(STRANGER, 'mom')},
        lastModifiedBy: STRANGER,
        lastModifiedKind: 'SWAP',
        lastSwapDate: DATE,
      }));
    });
  });

  it('denies a list query, even one a participant could otherwise satisfy per-document',
      async () => {
        await seed(env, {[PATH]: custodyDoc({})});
        const db = env.authenticatedContext(MOM).firestore();
        await assertFails(
            db.collection('custody_models')
                .where('participants', 'array-contains', MOM).get());
      });
});
