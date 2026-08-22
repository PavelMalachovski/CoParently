/**
 * The `users/{uid}` profile write that `UserRepositoryImpl.ensureProfile` performs.
 *
 * Background: nothing in the app ever wrote the signed-in user's own `name` and `email`
 * into `users/{uid}`. The document existed only because `FcmService.updateUserToken`
 * merges a lone `fcmToken` key into it, so the co-parent — who reads `name`/`email` from
 * that same document to render the paired-partner card — saw "Unknown"/"Email unavailable"
 * for a perfectly valid pairing.
 *
 * `ensureProfile` closes that gap with a `set(..., merge)` carrying only the identity keys.
 * Two things have to hold, and both are checked here against the real ruleset rather than
 * argued from the source:
 *
 *   1. the write is *permitted* — it hits `allow create` when no document exists yet and
 *      `allow update` when the FCM registration got there first; and
 *   2. the write is *non-destructive* — `partnerId`, `pairedAt`, `fcmToken` and
 *      `pendingRevocationOf` all belong to other writers (the pairing callable, the unpair
 *      sweep, the FCM registration) and must survive it.
 */

const {
  CURRENT_RULES, testEnv, seed, assertSucceeds, assertFails,
} = require('../harness');

const PROJECT = 'demo-coplanly-user-profile';
const ALICE = 'alice-uid';
const BOB = 'bob-uid';
const STRANGER = 'stranger-uid';

/** The exact map `ensureProfile` merges when the stored document carries no identity. */
const IDENTITY_PATCH = {
  id: ALICE,
  firebaseUid: ALICE,
  name: 'Alice Novak',
  email: 'alice@example.com',
};

/**
 * Performs the profile write as the signed-in owner.
 *
 * @param {!Object} env Rules test environment.
 * @param {!Object} patch Keys to merge into the profile.
 * @return {!Promise} The pending write.
 */
function mergeProfile(env, patch) {
  return env.authenticatedContext(ALICE).firestore()
      .doc(`users/${ALICE}`).set(patch, {merge: true});
}

/**
 * Reads a document back with the rules bypassed.
 *
 * @param {!Object} env Rules test environment.
 * @param {string} refPath Document path.
 * @return {!Promise<!Object>} The stored data.
 */
async function readRaw(env, refPath) {
  let data;
  await env.withSecurityRulesDisabled(async (ctx) => {
    data = (await ctx.firestore().doc(refPath).get()).data();
  });
  return data;
}

describe('users profile: the identity write ensureProfile performs', () => {
  let env;

  before(async () => {
    env = await testEnv(PROJECT, CURRENT_RULES);
  });

  beforeEach(async () => {
    await env.clearFirestore();
  });

  it('creates the profile when no document exists yet', async () => {
    // A merge onto a missing document is a create, so the `allow create` clause applies:
    // it demands both `email` and `name`, which is why the patch always carries them.
    await assertSucceeds(mergeProfile(env, IDENTITY_PATCH));
  });

  it('updates the fcm-token-only document the app actually produces on a device', async () => {
    await seed(env, {[`users/${ALICE}`]: {fcmToken: 'token-1'}});

    await assertSucceeds(mergeProfile(env, IDENTITY_PATCH));
  });

  it('leaves partnerId, pairedAt, fcmToken and pendingRevocationOf intact', async () => {
    await seed(env, {
      [`users/${ALICE}`]: {
        fcmToken: 'token-1',
        partnerId: BOB,
        pairedAt: 1754000000000,
        pendingRevocationOf: [BOB],
      },
    });

    await assertSucceeds(mergeProfile(env, IDENTITY_PATCH));

    const stored = await readRaw(env, `users/${ALICE}`);
    if (stored.partnerId !== BOB) throw new Error('partnerId was lost');
    if (stored.pairedAt !== 1754000000000) throw new Error('pairedAt was lost');
    if (stored.fcmToken !== 'token-1') throw new Error('fcmToken was lost');
    if (!Array.isArray(stored.pendingRevocationOf)) {
      throw new Error('pendingRevocationOf was lost');
    }
    if (stored.name !== 'Alice Novak') throw new Error('name was not written');
    if (stored.email !== 'alice@example.com') throw new Error('email was not written');
  });

  it('would have lost that state under a full set, which the rules do not prevent', async () => {
    // The hazard the removed `upsertUser` carried: the rules happily allow an owner to
    // replace their own document, so nothing server-side would have caught the deletion
    // of `pendingRevocationOf` — the marker the unpair sweep leaves behind to remember
    // whose shared access it has not finished revoking. Merge is the only safeguard.
    await seed(env, {
      [`users/${ALICE}`]: {partnerId: BOB, pendingRevocationOf: [BOB]},
    });

    await assertSucceeds(
        env.authenticatedContext(ALICE).firestore()
            .doc(`users/${ALICE}`).set(IDENTITY_PATCH));

    const stored = await readRaw(env, `users/${ALICE}`);
    if (stored.pendingRevocationOf !== undefined) {
      throw new Error('expected the full set to have erased pendingRevocationOf');
    }
  });

  it('rejects a create that carries no name', async () => {
    // `ensureProfile` refuses to write when it cannot derive a name; this is the rule that
    // makes that refusal the right call rather than an over-cautious one.
    await assertFails(mergeProfile(env, {id: ALICE, email: 'alice@example.com'}));
  });

  it('accepts a name-less merge into a document that already exists', async () => {
    // The other half of the same question, and the one that decides how much
    // `ensureProfile` may salvage when no name can be derived: `allow update` places no
    // requirement on `name`, so an existing document can take the email, the photo and
    // the ids without one. A device stuck without a display name therefore does not have
    // to discard everything else it knows — it only has to skip the name.
    await seed(env, {[`users/${ALICE}`]: {fcmToken: 'token-1'}});

    await assertSucceeds(mergeProfile(env, {
      id: ALICE,
      firebaseUid: ALICE,
      email: 'alice@example.com',
      profilePhotoUrl: 'https://lh3.googleusercontent.com/a/alice',
    }));

    const stored = await readRaw(env, `users/${ALICE}`);
    if (stored.name !== undefined) throw new Error('no name should have been written');
    if (stored.fcmToken !== 'token-1') throw new Error('fcmToken was lost');
  });

  it('still rejects the same name-less merge when no document exists', async () => {
    // Same patch, no seeded document: now it is a create, and the create rule wins. This
    // pair is why `ensureProfile` keys the salvage attempt on having read a document.
    await assertFails(mergeProfile(env, {
      id: ALICE,
      firebaseUid: ALICE,
      email: 'alice@example.com',
      profilePhotoUrl: 'https://lh3.googleusercontent.com/a/alice',
    }));
  });

  it('rejects a firebaseUid that does not match the caller', async () => {
    await assertFails(mergeProfile(env, {...IDENTITY_PATCH, firebaseUid: BOB}));
  });

  it('does not let the co-parent write the profile', async () => {
    await seed(env, {
      [`users/${ALICE}`]: {partnerId: BOB, name: 'Alice Novak'},
      [`users/${BOB}`]: {partnerId: ALICE, name: 'Bob Novak'},
    });

    await assertFails(
        env.authenticatedContext(BOB).firestore()
            .doc(`users/${ALICE}`).set({name: 'Hacked'}, {merge: true}));
  });

  it('lets a co-parent read the other parent\'s medical profile and phone', async () => {
    // Deliberate: if something happens to one parent, the other can tell a paramedic the blood
    // group. The questionnaire exists for exactly this.
    await seed(env, {
      [`users/${ALICE}`]: {
        name: 'Alice Novak',
        partnerId: BOB,
        phone: '+420123456789',
        medicalProfile: {bloodType: 'O_NEGATIVE', intolerances: ['lactose']},
      },
      [`users/${BOB}`]: {name: 'Bob Novak', partnerId: ALICE},
    });

    const snap = await assertSucceeds(
        env.authenticatedContext(BOB).firestore().doc(`users/${ALICE}`).get());
    const data = snap.data();
    if (data.phone !== '+420123456789') throw new Error('phone did not come back with the document');
    if (data.medicalProfile.bloodType !== 'O_NEGATIVE') {
      throw new Error('medicalProfile did not come back with the document');
    }
  });

  it('refuses to let a co-parent write the other parent\'s medical profile', async () => {
    // Load-bearing. Client writes to another user's document are what forced the permissive
    // firestore.rules.simple to be deployed once already.
    await seed(env, {
      [`users/${ALICE}`]: {
        name: 'Alice Novak',
        partnerId: BOB,
        medicalProfile: {bloodType: 'O_NEGATIVE'},
      },
      [`users/${BOB}`]: {name: 'Bob Novak', partnerId: ALICE},
    });

    await assertFails(
        env.authenticatedContext(BOB).firestore()
            .doc(`users/${ALICE}`).set({medicalProfile: {bloodType: 'A_POSITIVE'}}, {merge: true}));
  });

  it('refuses a stranger who is nobody\'s co-parent', async () => {
    await seed(env, {
      [`users/${ALICE}`]: {
        name: 'Alice Novak',
        partnerId: BOB,
        medicalProfile: {bloodType: 'O_NEGATIVE'},
      },
    });

    await assertFails(
        env.authenticatedContext(STRANGER).firestore().doc(`users/${ALICE}`).get());
  });
});
