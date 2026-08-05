/**
 * The one custody document a pair shares. Gated on a `participants` array that must match the
 * derived document id; read by id only, so no list query has to mirror the rule.
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
});
