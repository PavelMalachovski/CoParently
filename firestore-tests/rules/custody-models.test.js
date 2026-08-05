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
    lastModifiedAt: '2026-08-03T10:00:00',
  }, overrides);
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
    await assertFails(db.doc(PATH).set(custodyDoc({})));
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
      await assertSucceeds(db.doc(PATH).set(custodyDoc({})));
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

  it('denies a list query, even one a participant could otherwise satisfy per-document',
      async () => {
        await seed(env, {[PATH]: custodyDoc({})});
        const db = env.authenticatedContext(MOM).firestore();
        await assertFails(
            db.collection('custody_models')
                .where('participants', 'array-contains', MOM).get());
      });
});
