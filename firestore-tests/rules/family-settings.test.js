/**
 * The one money-agreement document a pair shares: how a shared expense divides between them.
 *
 * Modelled on `custody_models`, and tested for the same three things: the empty snapshot must
 * be readable (or the listener dies permanently on every new pair), the id must be bound to its
 * own participants (or the document can be squatted at a derivable id), and a proposal must not
 * be able to move the agreed share in silence.
 */

const {
  CURRENT_RULES, testEnv, seed, assertSucceeds, assertFails,
} = require('../harness');

const PROJECT = 'demo-coplanly-family-settings';
const MOM = 'uid-mom';
const DAD = 'uid-dad';
const STRANGER = 'uid-stranger';
const KEY = [MOM, DAD].sort().join('__');
const PATH = `family_settings/${KEY}`;

const PAIRED_USERS = {
  'users/uid-mom': {name: 'Olya', email: 'o@x.test', partnerId: DAD},
  'users/uid-dad': {name: 'Pavel', email: 'p@x.test', partnerId: MOM},
  'users/uid-stranger': {name: 'Carol', email: 'c@x.test', partnerId: ''},
};

/**
 * The document as `FirestoreFamilySettingsDataSource` writes it.
 *
 * @param {!Object} overrides Fields to replace.
 * @return {!Object} The document.
 */
function settingsDoc(overrides) {
  return Object.assign({
    participants: [MOM, DAD].sort(),
    momShareBasisPoints: 5000,
    lastModifiedBy: MOM,
    lastModifiedAtMillis: 1756000000000,
  }, overrides);
}

/**
 * A pending proposal sub-map.
 *
 * @param {string} proposedBy Uid of the parent putting it forward.
 * @param {number} momShareBasisPoints The share being proposed.
 * @return {!Object} The sub-map.
 */
function proposal(proposedBy, momShareBasisPoints) {
  return {
    momShareBasisPoints,
    proposedBy,
    proposedAtMillis: 1756000100000,
  };
}

describe('family_settings', () => {
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
    await assertSucceeds(db.doc(PATH).set(settingsDoc({})));
  });

  it('lets a participant listen before the document exists', async () => {
    // The clause that is not a nicety: `resource` is null for a missing document, so a rule
    // dereferencing `resource.data.participants` errors, and an erroring rule denies. Every new
    // pair's first read is a read of a document that is not there.
    await assertSucceeds(env.authenticatedContext(MOM).firestore().doc(PATH).get());
  });

  it('refuses a third account the empty snapshot too', async () => {
    // Or the rule becomes an existence oracle: the id is derivable from any two uids.
    await assertFails(env.authenticatedContext(STRANGER).firestore().doc(PATH).get());
  });

  it('refuses a document whose id does not name its own participants', async () => {
    // Without this, any account could squat a pair's document at their derivable id and lock
    // both of them out of it forever, since every other clause keys on `participants`.
    const db = env.authenticatedContext(MOM).firestore();
    await assertFails(
        db.doc(`family_settings/${MOM}__${STRANGER}`).set(settingsDoc({})),
    );
  });

  it('refuses a share that is not a share', async () => {
    const db = env.authenticatedContext(MOM).firestore();
    await assertFails(db.doc(PATH).set(settingsDoc({momShareBasisPoints: 10001})));
    await assertFails(db.doc(PATH).set(settingsDoc({momShareBasisPoints: -1})));
  });

  it('refuses a proposal baked into the create', async () => {
    // Whoever wins the race to create the deterministic-id document would otherwise arrive with
    // an unanswerable proposal already on it.
    const db = env.authenticatedContext(MOM).firestore();
    await assertFails(db.doc(PATH).set(settingsDoc({proposal: proposal(MOM, 7000)})));
  });

  it('lets a participant put a proposal without moving the agreed share', async () => {
    await seed(env, {[PATH]: settingsDoc({})});
    const db = env.authenticatedContext(MOM).firestore();
    await assertSucceeds(db.doc(PATH).update({proposal: proposal(MOM, 7000)}));
  });

  it('refuses a proposal write that also moves the agreed share', async () => {
    // The whole point of the split between the two update shapes: a proposal must not be able
    // to change the money in silence, because the co-parent is only told about the proposal.
    await seed(env, {[PATH]: settingsDoc({})});
    const db = env.authenticatedContext(MOM).firestore();
    await assertFails(db.doc(PATH).update({
      proposal: proposal(MOM, 7000),
      momShareBasisPoints: 7000,
    }));
  });

  it('lets the co-parent accept, which moves the share and stamps them', async () => {
    await seed(env, {[PATH]: settingsDoc({proposal: proposal(MOM, 7000)})});
    const db = env.authenticatedContext(DAD).firestore();
    await assertSucceeds(db.doc(PATH).update({
      momShareBasisPoints: 7000,
      lastModifiedBy: DAD,
      lastModifiedAtMillis: 1756000200000,
      proposal: null,
    }));
  });

  it('refuses moving the agreed share with no proposal behind it', async () => {
    // The difference from `custody_models`, and the whole point of this document: a custody
    // pattern is last-write-wins with a banner, a split that prices every future expense is not.
    // Stamping yourself as the author is not enough here.
    await seed(env, {[PATH]: settingsDoc({})});
    const db = env.authenticatedContext(MOM).firestore();
    await assertFails(db.doc(PATH).update({
      momShareBasisPoints: 7000,
      lastModifiedBy: MOM,
      lastModifiedAtMillis: 1756000200000,
    }));
  });

  it('refuses accepting your own proposal', async () => {
    await seed(env, {[PATH]: settingsDoc({proposal: proposal(MOM, 7000)})});
    const db = env.authenticatedContext(MOM).firestore();
    await assertFails(db.doc(PATH).update({
      momShareBasisPoints: 7000,
      lastModifiedBy: MOM,
      lastModifiedAtMillis: 1756000200000,
      proposal: null,
    }));
  });

  it('refuses an acceptance that lands on a figure nobody proposed', async () => {
    // Otherwise "accept" is a door to any number at all, which is the same unilateral change
    // wearing the co-parent's agreement.
    await seed(env, {[PATH]: settingsDoc({proposal: proposal(MOM, 7000)})});
    const db = env.authenticatedContext(DAD).firestore();
    await assertFails(db.doc(PATH).update({
      momShareBasisPoints: 9000,
      lastModifiedBy: DAD,
      lastModifiedAtMillis: 1756000200000,
      proposal: null,
    }));
  });

  it('refuses an acceptance that leaves the proposal in place', async () => {
    // A proposal left standing could be accepted again, at a different figure each time.
    await seed(env, {[PATH]: settingsDoc({proposal: proposal(MOM, 7000)})});
    const db = env.authenticatedContext(DAD).firestore();
    await assertFails(db.doc(PATH).update({
      momShareBasisPoints: 7000,
      lastModifiedBy: DAD,
      lastModifiedAtMillis: 1756000200000,
    }));
  });

  it('lets the co-parent decline, leaving the share alone', async () => {
    await seed(env, {[PATH]: settingsDoc({proposal: proposal(MOM, 7000)})});
    const db = env.authenticatedContext(DAD).firestore();
    await assertSucceeds(db.doc(PATH).update({
      proposal: null,
      lastDecision: {
        outcome: 'DECLINED',
        by: DAD,
        atMillis: 1756000200000,
        proposalAtMillis: 1756000100000,
      },
    }));
  });

  it('refuses swapping a participant out', async () => {
    await seed(env, {[PATH]: settingsDoc({})});
    const db = env.authenticatedContext(MOM).firestore();
    await assertFails(db.doc(PATH).update({
      participants: [MOM, STRANGER].sort(),
      lastModifiedBy: MOM,
    }));
  });

  it('refuses a third account everything', async () => {
    await seed(env, {[PATH]: settingsDoc({})});
    const db = env.authenticatedContext(STRANGER).firestore();
    await assertFails(db.doc(PATH).get());
    await assertFails(db.doc(PATH).update({momShareBasisPoints: 9000}));
    await assertFails(db.doc(PATH).delete());
  });

  it('lets either named participant delete it', async () => {
    await seed(env, {[PATH]: settingsDoc({})});
    await assertSucceeds(env.authenticatedContext(DAD).firestore().doc(PATH).delete());
  });
});
