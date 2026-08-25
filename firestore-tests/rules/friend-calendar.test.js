/**
 * The friend (item 16): a trusted third person with their own account who reads a family's
 * calendar without occupying a parent slot.
 *
 * Three collections work together:
 *   - `friend_profiles/{uid}` — the friend authors their own profile; the two parents read it.
 *   - `calendar_friends/{uid}` — a parent grants calendar read access, with an expiry.
 *   - `events` — read now also admits a *live* calendar friend of the creator, so the friend
 *     queries the family's events without any event document being rewritten.
 */

const {
  CURRENT_RULES, testEnv, seed, assertSucceeds, assertFails,
} = require('../harness');

const PROJECT = 'demo-coplanly-friend';
const MOM = 'uid-mom';
const DAD = 'uid-dad';
const FRIEND = 'uid-friend';
const STRANGER = 'uid-stranger';

// Mom's *second* co-parent. A person may co-parent with more than one other adult (M-4), and the
// two families that person belongs to must have nothing in common — which is what M-6 finally
// makes true of a calendar friend.
const OTHER_PARENT = 'uid-other-parent';

// `FamilyKey.of` — the two uids sorted and joined. Written out rather than computed so a test
// that fails says which family it meant.
const FAMILY = 'uid-dad__uid-mom';
const OTHER_FAMILY = 'uid-mom__uid-other-parent';

const FAR_FUTURE = 4102444800000; // 2100-01-01
const PAST = 1000; // 1970

function profile(overrides) {
  return Object.assign({
    uid: FRIEND, name: 'Babushka', role: 'GRANDPARENT',
    phones: ['+420111222333'], bloodGroup: 'A+', familyParents: [MOM, DAD],
  }, overrides);
}

function grant(overrides) {
  return Object.assign({
    familyParents: [MOM, DAD], familyId: FAMILY, grantedBy: MOM,
    grantedAtMillis: 1, expiresAtMillis: FAR_FUTURE,
  }, overrides);
}

function event(overrides) {
  return Object.assign({
    createdByFirebaseUid: MOM, title: 'School pickup', eventType: 'CUSTODY',
    parentOwner: 'mom', startDateTime: '2026-09-01T15:00:00', sharedWith: [MOM, DAD],
    familyId: FAMILY,
  }, overrides);
}

describe('friend_profiles', () => {
  let env;
  before(async () => { env = await testEnv(PROJECT, CURRENT_RULES); });
  beforeEach(async () => { await env.clearFirestore(); });

  const PATH = `friend_profiles/${FRIEND}`;

  it('lets the friend create their own profile with a two-parent gate', async () => {
    await assertSucceeds(env.authenticatedContext(FRIEND).firestore().doc(PATH).set(profile({})));
  });

  it('refuses a profile whose familyParents is not a pair', async () => {
    await assertFails(
        env.authenticatedContext(FRIEND).firestore().doc(PATH).set(profile({familyParents: [MOM]})));
  });

  it('refuses one account creating another account\'s profile', async () => {
    await assertFails(env.authenticatedContext(STRANGER).firestore().doc(PATH).set(profile({})));
  });

  it('lets a listed parent and the friend read it, and refuses a stranger', async () => {
    await seed(env, {[PATH]: profile({})});
    await assertSucceeds(env.authenticatedContext(MOM).firestore().doc(PATH).get());
    await assertSucceeds(env.authenticatedContext(FRIEND).firestore().doc(PATH).get());
    await assertFails(env.authenticatedContext(STRANGER).firestore().doc(PATH).get());
  });

  it('lets the friend edit name/photo but not the read gate, and refuses a parent editing', async () => {
    await seed(env, {[PATH]: profile({})});
    const friend = env.authenticatedContext(FRIEND).firestore();
    await assertSucceeds(friend.doc(PATH).update({name: 'Grandma Olya', photoUrl: 'https://x/y.jpg'}));
    await assertFails(friend.doc(PATH).update({familyParents: [MOM, STRANGER]}));
    await assertFails(env.authenticatedContext(MOM).firestore().doc(PATH).update({name: 'Renamed'}));
  });
});

describe('calendar_friends', () => {
  let env;
  before(async () => { env = await testEnv(PROJECT, CURRENT_RULES); });
  beforeEach(async () => { await env.clearFirestore(); });

  const PATH = `calendar_friends/${FRIEND}`;

  // No client writes a grant. `acceptCalendarFriendInvitation` does, on Admin credentials,
  // and it is the only thing that proves the inviter is a paired parent before doing so.
  // These cases used to assert the opposite — that a parent could write one directly — which
  // is what let anybody write one for themselves. See the block comment in `firestore.rules`.
  it('refuses a parent writing a grant directly (the callable is the only writer)', async () => {
    await assertFails(env.authenticatedContext(MOM).firestore().doc(PATH).set(grant({})));
  });

  it('refuses a friend granting themselves access', async () => {
    await assertFails(
        env.authenticatedContext(FRIEND).firestore().doc(PATH).set(grant({grantedBy: FRIEND})));
  });

  it('refuses a grant stamped by a non-parent', async () => {
    await assertFails(env.authenticatedContext(MOM).firestore().doc(PATH).set(grant({grantedBy: DAD})));
  });

  // The breach this rule was closed for: the old condition asked only that the written
  // document name the caller among its own two `familyParents` and credit them as
  // `grantedBy` — both attacker-supplied. So a stranger could write a grant at their *own*
  // uid naming their victim as the other "parent", and the third disjunct of the `events`
  // read rule then served the victim's whole calendar.
  it('refuses a stranger self-granting access to a victim they name as a parent', async () => {
    const selfGrant = {
      familyParents: [STRANGER, MOM], grantedBy: STRANGER,
      grantedAtMillis: 1, expiresAtMillis: FAR_FUTURE,
    };
    await assertFails(
        env.authenticatedContext(STRANGER).firestore()
            .doc(`calendar_friends/${STRANGER}`).set(selfGrant));
  });

  // Even with a grant seeded past the rules, a second account must not be able to rewrite it
  // — repointing somebody else's grant at themselves both revokes the real friend and admits
  // the writer.
  it('refuses overwriting an existing grant', async () => {
    await seed(env, {[PATH]: grant({})});
    await assertFails(
        env.authenticatedContext(STRANGER).firestore().doc(PATH)
            .update({familyParents: [STRANGER, MOM]}));
  });

  it('lets the friend and both parents read the grant, refuses a stranger', async () => {
    await seed(env, {[PATH]: grant({})});
    await assertSucceeds(env.authenticatedContext(FRIEND).firestore().doc(PATH).get());
    await assertSucceeds(env.authenticatedContext(DAD).firestore().doc(PATH).get());
    await assertFails(env.authenticatedContext(STRANGER).firestore().doc(PATH).get());
  });

  it('lets a parent revoke, but not the friend', async () => {
    await seed(env, {[PATH]: grant({})});
    await assertFails(env.authenticatedContext(FRIEND).firestore().doc(PATH).delete());
    await assertSucceeds(env.authenticatedContext(DAD).firestore().doc(PATH).delete());
  });
});

describe('events read for a calendar friend', () => {
  let env;
  before(async () => { env = await testEnv(PROJECT, CURRENT_RULES); });
  beforeEach(async () => { await env.clearFirestore(); });

  const EVENT = 'events/ev-1';
  const GRANT = `calendar_friends/${FRIEND}`;

  it('lets a live friend read a family event they are not in the audience of', async () => {
    await seed(env, {[EVENT]: event({sharedWith: [MOM, DAD]}), [GRANT]: grant({})});
    await assertSucceeds(env.authenticatedContext(FRIEND).firestore().doc(EVENT).get());
  });

  it('refuses a friend with no grant', async () => {
    await seed(env, {[EVENT]: event({})});
    await assertFails(env.authenticatedContext(FRIEND).firestore().doc(EVENT).get());
  });

  it('refuses a friend whose grant has expired', async () => {
    await seed(env, {[EVENT]: event({}), [GRANT]: grant({expiresAtMillis: PAST})});
    await assertFails(env.authenticatedContext(FRIEND).firestore().doc(EVENT).get());
  });

  it('refuses a friend an event created by someone outside their family', async () => {
    await seed(env, {[EVENT]: event({createdByFirebaseUid: STRANGER}), [GRANT]: grant({})});
    await assertFails(env.authenticatedContext(FRIEND).firestore().doc(EVENT).get());
  });

  it('refuses a friend writing an event (read-only)', async () => {
    await seed(env, {[EVENT]: event({}), [GRANT]: grant({})});
    await assertFails(
        env.authenticatedContext(FRIEND).firestore().doc(EVENT).update({title: 'hijacked'}));
  });

  // ---- M-6: the grant is scoped to one family, not to a person ---------------------------
  //
  // Mom co-parents with Dad *and* with OTHER_PARENT. The grandmother was admitted by Mom and Dad.
  // Before M-6 the rule asked only "did this event's creator appear among my two parents", and
  // Mom is one of them — so the grandmother read Mom's events in the other household too.

  it('refuses a friend an event from the inviter\'s other family', async () => {
    await seed(env, {
      [EVENT]: event({familyId: OTHER_FAMILY, sharedWith: [MOM, OTHER_PARENT]}),
      [GRANT]: grant({}),
    });
    await assertFails(env.authenticatedContext(FRIEND).firestore().doc(EVENT).get());
  });

  it('refuses a friend an event that has no familyId yet', async () => {
    // Everything written before M-2, until `backfillRecordFamilyIds` has run. Deliberately a
    // denial rather than a fallback: a fallback to "a co-parent of the author" is what re-opened
    // this same leak in `expenses`, because Firestore validates a query by its structure.
    await seed(env, {[EVENT]: event({familyId: ''}), [GRANT]: grant({})});
    await assertFails(env.authenticatedContext(FRIEND).firestore().doc(EVENT).get());
  });

  it('refuses a grant written before M-6, which carries no familyId', async () => {
    await seed(env, {[EVENT]: event({}), [GRANT]: grant({familyId: ''})});
    await assertFails(env.authenticatedContext(FRIEND).firestore().doc(EVENT).get());
  });

  it('refuses an outsider who stamps this family\'s id onto their own event', async () => {
    // Why `ownerUid in familyParents` stays alongside the familyId check: an event's `familyId`
    // is client-written and the create rule does not pin it, so without the second check any
    // account could inject an event into a family's friend view.
    await seed(env, {
      [EVENT]: event({createdByFirebaseUid: STRANGER, familyId: FAMILY}),
      [GRANT]: grant({}),
    });
    await assertFails(env.authenticatedContext(FRIEND).firestore().doc(EVENT).get());
  });

  it('rejects the by-creator query the friend client used to be told to run', async () => {
    // The shape CLAUDE.md item 16 documented. It is not merely wider than it should be — with
    // the rule keyed on the record's own family it is now structurally undecidable, so Firestore
    // refuses it outright rather than serving a subset. Same lesson as the expenses leak: the
    // client query and the rule have to be keyed on the same field.
    await seed(env, {[EVENT]: event({}), [GRANT]: grant({})});
    await assertFails(
        env.authenticatedContext(FRIEND).firestore().collection('events')
            .where('createdByFirebaseUid', 'in', [MOM, DAD]).get());
  });

  it('serves the family-scoped query a friend client must run instead', async () => {
    await seed(env, {[EVENT]: event({}), [GRANT]: grant({})});
    await assertSucceeds(
        env.authenticatedContext(FRIEND).firestore().collection('events')
            .where('familyId', '==', FAMILY)
            .where('createdByFirebaseUid', 'in', [MOM, DAD]).get());
  });
});
