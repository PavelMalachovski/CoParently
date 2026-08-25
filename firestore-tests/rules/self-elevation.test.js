/**
 * Writing yourself into a field the rules trust.
 *
 * The August 2026 audit found four separate holes with one shape: a rule asked whether the
 * caller appeared in some field, and the caller was the one who supplied that field. The suite
 * had cases for every *outsider* — the stranger, the expired grant, the unpaired account — and
 * none for the account that simply nominates itself. These are those cases, kept together
 * because the class matters more than the individual collections: a new rule that trusts a
 * caller-supplied field belongs here before it ships.
 *
 * Covered:
 *   1. `users.partnerId` naming its own owner, which made `isPartnerOf(me)` true for me and
 *      handed an ex-partner back the shared custody schedule after unpairing.
 *   2. `change_requests.requestedBy` naming somebody else, forging an entry in their inbox.
 *   3. `change_requests.requestedTo` naming a stranger, using the inbox to reach them.
 *   4. `events.sharedWith` widened (or narrowed) by a partner editing somebody else's event.
 *
 * The `calendar_friends` member of the same family lives in `friend-calendar.test.js`, beside
 * the rest of that feature.
 */

const {
  CURRENT_RULES, testEnv, seed, assertSucceeds, assertFails,
} = require('../harness');

const PROJECT = 'demo-coplanly-self-elevation';
const MOM = 'uid-mom';
const DAD = 'uid-dad';
const STRANGER = 'uid-stranger';

/** A live, mutual pairing between the two parents. */
const PAIRED = {
  [`users/${MOM}`]: {id: MOM, name: 'Mom', email: 'mom@example.com', partnerId: DAD},
  [`users/${DAD}`]: {id: DAD, name: 'Dad', email: 'dad@example.com', partnerId: MOM},
};

describe('users.partnerId may never name its own owner', () => {
  let env;
  before(async () => { env = await testEnv(PROJECT, CURRENT_RULES); });
  beforeEach(async () => { await env.clearFirestore(); });

  it('refuses creating a profile that is its own partner', async () => {
    await assertFails(
        env.authenticatedContext(MOM).firestore().doc(`users/${MOM}`)
            .set({id: MOM, name: 'Mom', email: 'mom@example.com', partnerId: MOM}));
  });

  it('refuses updating a profile to be its own partner', async () => {
    await seed(env, {[`users/${MOM}`]: {id: MOM, name: 'Mom', email: 'mom@example.com'}});
    await assertFails(
        env.authenticatedContext(MOM).firestore().doc(`users/${MOM}`)
            .update({partnerId: MOM}));
  });

  it('still allows an ordinary profile write', async () => {
    await seed(env, {[`users/${MOM}`]: {id: MOM, name: 'Mom', email: 'mom@example.com'}});
    await assertSucceeds(
        env.authenticatedContext(MOM).firestore().doc(`users/${MOM}`)
            .update({name: 'Mom Novakova'}));
  });

  // The damage the self-reference actually did. `custody_models` get/update demand that the
  // pairing behind the stored `participants` still be live, so that unpairing ends access to
  // the shared schedule. A stored `partnerId` pointing at its own owner satisfied that demand
  // against the ex-partner themselves — so the seeding here is deliberately of the broken
  // shape a pre-fix client could have written, to prove the *helper* refuses it even when the
  // document already exists.
  it('does not let a self-paired ex-partner reach the shared custody schedule', async () => {
    const modelId = [MOM, DAD].sort().join('__');
    await seed(env, {
      [`users/${MOM}`]: {id: MOM, name: 'Mom', email: 'mom@example.com', partnerId: MOM},
      [`users/${DAD}`]: {id: DAD, name: 'Dad', email: 'dad@example.com', partnerId: ''},
      [`custody_models/${modelId}`]: {
        participants: [MOM, DAD].sort(),
        lastModifiedBy: DAD,
        pattern: 'WEEK_ON_WEEK_OFF',
      },
    });

    await assertFails(
        env.authenticatedContext(MOM).firestore().doc(`custody_models/${modelId}`).get());
    await assertFails(
        env.authenticatedContext(MOM).firestore().doc(`custody_models/${modelId}`)
            .update({pattern: 'EVERY_WEEKEND', lastModifiedBy: MOM}));
  });
});

describe('change_requests may not be forged or addressed to strangers', () => {
  let env;
  before(async () => { env = await testEnv(PROJECT, CURRENT_RULES); });
  beforeEach(async () => { await env.clearFirestore(); });

  function request(overrides) {
    return Object.assign({
      requestedBy: MOM,
      requestedTo: DAD,
      eventId: 'ev-1',
      eventTitle: 'Swap Friday',
      status: 'PENDING',
    }, overrides);
  }

  it('lets a parent address their live co-parent', async () => {
    await seed(env, PAIRED);
    await assertSucceeds(
        env.authenticatedContext(MOM).firestore().doc('change_requests/cr-1').set(request({})));
  });

  // The forgery: naming yourself as the *addressee* used to be enough, so anyone could plant a
  // request stamped with the victim's uid as its author. Both fields are watched by the
  // client, so it surfaced in the victim's inbox as something they appeared to have sent.
  it('refuses a request that credits somebody else as its author', async () => {
    await seed(env, PAIRED);
    await assertFails(
        env.authenticatedContext(DAD).firestore().doc('change_requests/cr-2')
            .set(request({requestedBy: MOM, requestedTo: DAD})));
  });

  it('refuses a request addressed to somebody who is not the caller\'s co-parent', async () => {
    await seed(env, PAIRED);
    await assertFails(
        env.authenticatedContext(MOM).firestore().doc('change_requests/cr-3')
            .set(request({requestedBy: MOM, requestedTo: STRANGER})));
  });

  it('refuses an unpaired account addressing anybody', async () => {
    await seed(env, {
      [`users/${STRANGER}`]: {id: STRANGER, name: 'Nobody', email: 'n@example.com', partnerId: ''},
      [`users/${MOM}`]: PAIRED[`users/${MOM}`],
    });
    await assertFails(
        env.authenticatedContext(STRANGER).firestore().doc('change_requests/cr-4')
            .set(request({requestedBy: STRANGER, requestedTo: MOM})));
  });
});

describe('a shared partner may edit an event but not its audience', () => {
  let env;
  before(async () => { env = await testEnv(PROJECT, CURRENT_RULES); });
  beforeEach(async () => { await env.clearFirestore(); });

  const PATH = 'events/ev-shared';

  /** Mom's event, shared read_write with Dad — the ordinary co-parenting case. */
  const SHARED_EVENT = {
    createdByFirebaseUid: MOM,
    title: 'School pickup',
    eventType: 'CUSTODY',
    parentOwner: 'mom',
    startDateTime: '2026-09-01T15:00:00',
    sharedWith: [MOM, DAD],
    permissions: 'read_write',
  };

  it('lets the shared partner edit the event itself', async () => {
    await seed(env, {[PATH]: SHARED_EVENT});
    await assertSucceeds(
        env.authenticatedContext(DAD).firestore().doc(PATH).update({title: 'Later pickup'}));
  });

  // Handing a third account the creator's event. The read rule is plain membership, so an
  // appended uid is a completed disclosure, not a request for one.
  it('refuses the shared partner adding a third uid to the audience', async () => {
    await seed(env, {[PATH]: SHARED_EVENT});
    await assertFails(
        env.authenticatedContext(DAD).firestore().doc(PATH)
            .update({sharedWith: [MOM, DAD, STRANGER]}));
  });

  // Deleting by another name: `delete` on this collection is creator-only, but dropping the
  // creator from `sharedWith` takes the event out of the feed their own client reads.
  it('refuses the shared partner removing the creator from the audience', async () => {
    await seed(env, {[PATH]: SHARED_EVENT});
    await assertFails(
        env.authenticatedContext(DAD).firestore().doc(PATH).update({sharedWith: [DAD]}));
  });

  it('refuses the shared partner promoting their own permissions', async () => {
    await seed(env, {[PATH]: Object.assign({}, SHARED_EVENT, {permissions: 'read_only'})});
    await assertFails(
        env.authenticatedContext(DAD).firestore().doc(PATH)
            .update({permissions: 'read_write'}));
  });

  it('still lets the creator change the audience', async () => {
    await seed(env, {[PATH]: SHARED_EVENT});
    await assertSucceeds(
        env.authenticatedContext(MOM).firestore().doc(PATH).update({sharedWith: [MOM]}));
  });
});

/**
 * `google_oauth/{uid}` — the refresh-token fingerprints (SEC-1 §2).
 *
 * Moving the OAuth client secret out of the APK closes one hole and would open another if the
 * refresh callable refreshed whatever it was handed: it would become an oracle turning any
 * stolen refresh token into an access token. The fingerprint is what stops that, so the
 * document has to be unreadable as well as unwritable — a client that could read it could test
 * candidate tokens offline, and one that could write it could claim somebody else's.
 */
describe('google_oauth fingerprints are closed to every client', () => {
  let env;

  before(async () => { env = await testEnv(PROJECT, CURRENT_RULES); });
  beforeEach(async () => { await env.clearFirestore(); });

  const PATH = `google_oauth/${MOM}`;
  const FINGERPRINT = {refreshTokenHash: 'a'.repeat(64), updatedAtMillis: 1};

  it('refuses the account its own fingerprint', async () => {
    await seed(env, {[PATH]: FINGERPRINT});
    await assertFails(env.authenticatedContext(MOM).firestore().doc(PATH).get());
  });

  it('refuses the account writing its own', async () => {
    await assertFails(
        env.authenticatedContext(MOM).firestore().doc(PATH).set(FINGERPRINT));
  });

  it('refuses somebody else claiming it', async () => {
    await seed(env, {[PATH]: FINGERPRINT});
    await assertFails(
        env.authenticatedContext(DAD).firestore().doc(PATH)
            .set({refreshTokenHash: 'b'.repeat(64)}));
  });

  it('refuses deleting it, which would re-open trust-on-first-use', async () => {
    await seed(env, {[PATH]: FINGERPRINT});
    await assertFails(env.authenticatedContext(MOM).firestore().doc(PATH).delete());
  });
});
