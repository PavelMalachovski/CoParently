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
    familyParents: [MOM, DAD], grantedBy: MOM,
    grantedAtMillis: 1, expiresAtMillis: FAR_FUTURE,
  }, overrides);
}

function event(overrides) {
  return Object.assign({
    createdByFirebaseUid: MOM, title: 'School pickup', eventType: 'CUSTODY',
    parentOwner: 'mom', startDateTime: '2026-09-01T15:00:00', sharedWith: [MOM, DAD],
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

  it('lets a parent grant a friend calendar access', async () => {
    await assertSucceeds(env.authenticatedContext(MOM).firestore().doc(PATH).set(grant({})));
  });

  it('refuses a friend granting themselves access', async () => {
    await assertFails(
        env.authenticatedContext(FRIEND).firestore().doc(PATH).set(grant({grantedBy: FRIEND})));
  });

  it('refuses a grant stamped by a non-parent', async () => {
    await assertFails(env.authenticatedContext(MOM).firestore().doc(PATH).set(grant({grantedBy: DAD})));
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

  it('serves the by-creator query the friend client runs', async () => {
    await seed(env, {[EVENT]: event({}), [GRANT]: grant({})});
    await assertSucceeds(
        env.authenticatedContext(FRIEND).firestore().collection('events')
            .where('createdByFirebaseUid', 'in', [MOM, DAD]).get());
  });
});
