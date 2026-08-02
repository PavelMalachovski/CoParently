/**
 * Part 2 — what an ex-partner can still reach after `unpairCoParent` runs.
 *
 * The emulator cannot invoke the Cloud Function, so these tests apply the sweep's effect
 * directly — remove the ex-partner from `sharedWith`, clear both `partnerId` fields — and
 * then assert what the *rules* allow. That is the property that matters: the sweep is
 * only worth anything if narrowing `sharedWith` actually closes the door.
 *
 * `helpers.applyUnpairSweep` mirrors `revokeSharedAudience` in functions/index.js,
 * including its rule that a uid is never dropped from a document it created.
 */

const {
  CURRENT_RULES, testEnv, seed, assertSucceeds, assertFails,
} = require('../harness');

const PROJECT = 'demo-coplanly-unpair';
const ALICE = 'alice-uid';
const BOB = 'bob-uid';
const CAROL = 'carol-uid';

/**
 * Builds an event document as `EventRepositoryImpl.toFirestoreMap` writes it.
 *
 * @param {!Object} overrides Fields to override on the default document.
 * @return {!Object} The document data.
 */
function eventDoc(overrides) {
  return Object.assign({
    id: 'event-1', title: 'Swimming lesson', description: '',
    startDateTime: '2026-08-05T16:00:00', endDateTime: '2026-08-05T17:00:00',
    eventType: 'ACTIVITY', parentOwner: 'MOM', isRecurring: false,
    recurrencePattern: '', recurrenceEndDate: '', pickupConfirmedBy: '',
    pickupConfirmedAt: '', createdAt: '2026-08-01T10:00:00',
    updatedAt: '2026-08-01T10:00:00', createdByFirebaseUid: ALICE,
    sharedWith: [ALICE, BOB], lastModifiedBy: ALICE, permissions: 'read_write',
    imageUrl: '',
  }, overrides);
}

/**
 * Builds a child_info document as `SyncService.syncChildInfo` writes it.
 *
 * @param {!Object} overrides Fields to override on the default document.
 * @return {!Object} The document data.
 */
function childInfoDoc(overrides) {
  return Object.assign({
    id: 'child-1', childName: 'Ema', dateOfBirth: '2018-03-04T00:00:00',
    medications: [], activities: [], allergies: [], medicalNotes: null,
    emergencyContacts: [], schoolInfo: null, createdAt: '2026-08-01T10:00:00',
    updatedAt: '2026-08-01T10:00:00', createdByFirebaseUid: ALICE,
    lastModifiedBy: ALICE, sharedWith: [ALICE, BOB],
  }, overrides);
}

/**
 * Applies what `revokeSharedAudience` does, with security rules disabled.
 *
 * @param {!Object} env Rules test environment.
 * @param {string} uidA One former co-parent.
 * @param {string} uidB The other former co-parent.
 * @return {!Promise<number>} How many documents were narrowed.
 */
async function applyUnpairSweep(env, uidA, uidB) {
  let revoked = 0;
  await env.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    for (const collection of ['events', 'child_info']) {
      for (const [reader, removed] of [[uidA, uidB], [uidB, uidA]]) {
        const snap = await db.collection(collection)
            .where('sharedWith', 'array-contains', reader).get();
        for (const doc of snap.docs) {
          const docData = doc.data();
          if (docData.createdByFirebaseUid === removed) continue;
          if (!(docData.sharedWith || []).includes(removed)) continue;
          await doc.ref.update({
            sharedWith: docData.sharedWith.filter((uid) => uid !== removed),
          });
          revoked++;
        }
      }
    }
    await db.doc(`users/${uidA}`).set({partnerId: ''}, {merge: true});
    await db.doc(`users/${uidB}`).set({partnerId: ''}, {merge: true});
  });
  return revoked;
}

/**
 * Mirrors `EventRepositoryImpl.shareTargets` — the audience the *edit* path uploads.
 *
 * @param {!Array<string>} stored The event's local Room `sharedWith` copy.
 * @param {string} creatorUid The document's `createdByFirebaseUid`.
 * @param {string} currentUid The signed-in user's UID.
 * @param {?string} partnerId The signed-in user's current co-parent, or null.
 * @return {!Array<string>} The uploaded `sharedWith`.
 */
function editAudience(stored, creatorUid, currentUid, partnerId) {
  const entitled = [currentUid, creatorUid, partnerId]
      .filter((uid) => uid) .filter((uid, i, all) => all.indexOf(uid) === i);
  return stored.filter((uid) => entitled.includes(uid)).concat(entitled)
      .filter((uid, i, all) => all.indexOf(uid) === i);
}

/**
 * Mirrors `SyncService.shareTargets` — the audience the *upload* path uploads.
 *
 * @param {!Array<string>} stored The entity's local Room `sharedWith` copy.
 * @param {?string} creatorUid The entity's `createdByFirebaseUid`.
 * @param {string} userId The uploading user's UID.
 * @param {?string} partnerId The uploading user's current co-parent, or null.
 * @return {!Array<string>} The uploaded `sharedWith`.
 */
function uploadAudience(stored, creatorUid, userId, partnerId) {
  const entitled = [userId, creatorUid, partnerId]
      .filter((uid) => uid).filter((uid, i, all) => all.indexOf(uid) === i);
  return stored.filter((uid) => entitled.includes(uid)).concat(entitled)
      .filter((uid, i, all) => all.indexOf(uid) === i);
}

/**
 * The pre-fix widen-only rule both call sites used, kept so the tests can show that the
 * rules alone do not stop the re-widening — only the client change does.
 *
 * @param {!Array<string>} stored The local Room `sharedWith` copy.
 * @param {string} creatorUid The document's `createdByFirebaseUid`.
 * @param {string} currentUid The signed-in user's UID.
 * @param {?string} partnerId The signed-in user's current co-parent, or null.
 * @return {!Array<string>} The audience the old code would have uploaded.
 */
function widenOnlyAudience(stored, creatorUid, currentUid, partnerId) {
  return stored.concat([currentUid, creatorUid], partnerId ? [partnerId] : [])
      .filter((uid) => uid).filter((uid, i, all) => all.indexOf(uid) === i);
}

describe('Part 2: access after unpair', () => {
  let env;

  before(async () => {
    env = await testEnv(PROJECT, CURRENT_RULES);
  });

  beforeEach(async () => {
    await env.clearFirestore();
    await seed(env, {
      'users/alice-uid': {name: 'Alice', email: 'a@x.test', partnerId: BOB},
      'users/bob-uid': {name: 'Bob', email: 'b@x.test', partnerId: ALICE},
      // Alice created and shared; Bob created and shared. Revocation must be symmetric.
      'events/event-1': eventDoc({}),
      'events/event-2': eventDoc({
        id: 'event-2', createdByFirebaseUid: BOB, lastModifiedBy: BOB,
        sharedWith: [BOB, ALICE],
      }),
      'child_info/child-1': childInfoDoc({}),
    });
  });

  describe('before the sweep (the leak this closes)', () => {
    it('lets the soon-to-be ex-partner read and update a shared event', async () => {
      const bob = env.authenticatedContext(BOB).firestore();
      await assertSucceeds(bob.doc('events/event-1').get());
      await assertSucceeds(bob.doc('events/event-1').update({title: 'Moved to 18:00'}));
    });
  });

  describe('after the sweep', () => {
    beforeEach(async () => {
      const revoked = await applyUnpairSweep(env, ALICE, BOB);
      if (revoked !== 3) {
        throw new Error(`expected 3 documents narrowed, got ${revoked}`);
      }
    });

    it('denies the ex-partner reading an event the caller created', async () => {
      await assertFails(
          env.authenticatedContext(BOB).firestore().doc('events/event-1').get());
    });

    it('denies the ex-partner updating an event the caller created', async () => {
      await assertFails(env.authenticatedContext(BOB).firestore()
          .doc('events/event-1').update({title: 'Moved to 18:00'}));
    });

    it('is symmetric: denies the caller reading and updating the ex-partner event', async () => {
      const alice = env.authenticatedContext(ALICE).firestore();
      await assertFails(alice.doc('events/event-2').get());
      await assertFails(alice.doc('events/event-2').update({title: 'Moved'}));
    });

    it('denies the ex-partner reading shared child info', async () => {
      await assertFails(
          env.authenticatedContext(BOB).firestore().doc('child_info/child-1').get());
    });

    it('denies the ex-partner updating shared child info', async () => {
      await assertFails(env.authenticatedContext(BOB).firestore()
          .doc('child_info/child-1').update({medicalNotes: 'changed'}));
    });

    it('drops the revoked documents out of the ex-partner sync query', async () => {
      const bob = env.authenticatedContext(BOB).firestore();
      const snap = await bob.collection('events')
          .where('sharedWith', 'array-contains', BOB).get();
      const ids = snap.docs.map((d) => d.id);
      if (ids.includes('event-1')) {
        throw new Error('event-1 is still visible to the ex-partner sync query');
      }
    });

    it('leaves each creator full access to their own documents', async () => {
      const alice = env.authenticatedContext(ALICE).firestore();
      await assertSucceeds(alice.doc('events/event-1').get());
      await assertSucceeds(alice.doc('events/event-1').update({title: 'Still mine'}));
      await assertSucceeds(alice.doc('child_info/child-1').get());
      await assertSucceeds(alice.doc('child_info/child-1').update({medicalNotes: 'x'}));

      const bob = env.authenticatedContext(BOB).firestore();
      await assertSucceeds(bob.doc('events/event-2').get());
      await assertSucceeds(bob.doc('events/event-2').update({title: 'Still mine'}));
    });

    it('keeps each creator in their own sync query', async () => {
      const alice = env.authenticatedContext(ALICE).firestore();
      const snap = await alice.collection('events')
          .where('sharedWith', 'array-contains', ALICE).get();
      const ids = snap.docs.map((d) => d.id);
      if (!ids.includes('event-1')) {
        throw new Error('the creator lost their own event from the sync query');
      }
    });

    it('revokes expenses and budgets for free, via the cleared partnerId', async () => {
      // Neither collection carries `sharedWith`; both gate on the live isPartnerOf
      // relationship, so clearing partnerId is the whole revocation.
      await seed(env, {
        'expenses/expense-1': {
          id: 'expense-1', title: 'School trip', amount: 42.5, currency: 'CZK',
          category: 'EDUCATION', createdByFirebaseUid: ALICE, paidBy: 'MOM',
          splitBetween: [], date: '2026-08-01', createdAt: '2026-08-01T10:00:00',
        },
        'budgets/budget-1': {
          id: 'budget-1', category: 'EDUCATION', monthlyLimit: 3000,
          currency: 'CZK', createdByFirebaseUid: ALICE,
        },
      });
      const bob = env.authenticatedContext(BOB).firestore();
      await assertFails(bob.doc('expenses/expense-1').get());
      await assertFails(bob.doc('budgets/budget-1').get());
    });

    it('stops the ex-partner enqueueing notifications', async () => {
      const bob = env.authenticatedContext(BOB).firestore();
      await assertFails(bob.collection('notification_queue').add({
        targetUserId: ALICE, data: {title: 'x', body: 'y'},
        status: 'pending', createdAt: 1,
      }));
    });
  });

  describe('the client writing back after the sweep', () => {
    // The sweep narrows the *remote* documents; it never touches either device's Room
    // copy. Both write paths run from that stale local copy, and the creator's write is
    // allowed by the events update rule whatever audience it carries — so if the client
    // recomputes the audience by widening, the revocation is undone permanently and the
    // rules cannot stop it. These tests drive the mirrors of both client paths.
    beforeEach(async () => {
      const revoked = await applyUnpairSweep(env, ALICE, BOB);
      if (revoked !== 3) {
        throw new Error(`expected 3 documents narrowed, got ${revoked}`);
      }
    });

    // Alice's Room copy is still pre-sweep; her user row is unpaired.
    const STALE_LOCAL = [ALICE, BOB];

    it('a user edit no longer re-grants the ex-partner (the edit entry path)', async () => {
      const audience = editAudience(STALE_LOCAL, ALICE, ALICE, null);
      if (audience.includes(BOB)) {
        throw new Error(`the edit path still uploads the ex-partner: ${audience}`);
      }

      const alice = env.authenticatedContext(ALICE).firestore();
      await assertSucceeds(alice.doc('events/event-1')
          .update({title: 'Moved to 18:00', sharedWith: audience}));

      const bob = env.authenticatedContext(BOB).firestore();
      await assertFails(bob.doc('events/event-1').get());
      await assertFails(bob.doc('events/event-1').update({title: 'Mine now'}));
    });

    it('the pre-fix edit audience did re-grant it — this is the leak being closed', async () => {
      // Pins the defect rather than the fix: nothing in the rules refuses this write, so
      // the widen-only client handed the ex-partner read *and* read_write back for good.
      const audience = widenOnlyAudience(STALE_LOCAL, ALICE, ALICE, null);
      if (!audience.includes(BOB)) {
        throw new Error('the pre-fix mirror no longer models the old behaviour');
      }

      const alice = env.authenticatedContext(ALICE).firestore();
      await assertSucceeds(alice.doc('events/event-1')
          .update({title: 'Moved to 18:00', sharedWith: audience}));

      const bob = env.authenticatedContext(BOB).firestore();
      await assertSucceeds(bob.doc('events/event-1').get());
      await assertSucceeds(bob.doc('events/event-1').update({title: 'Mine now'}));
    });

    it('an unsynced event upload no longer re-grants the ex-partner (the sync entry path)',
        async () => {
          // `syncEvents` uploads unsynced events *before* it downloads, so an event still
          // sitting `syncedToFirestore = false` at unpair time reaches Firestore with the
          // stale audience before the down-sync that would have healed it.
          const audience = uploadAudience(STALE_LOCAL, ALICE, ALICE, null);
          if (audience.includes(BOB)) {
            throw new Error(`the upload path still uploads the ex-partner: ${audience}`);
          }

          const alice = env.authenticatedContext(ALICE).firestore();
          await assertSucceeds(alice.doc('events/unsynced-1').set(eventDoc({
            id: 'unsynced-1', title: 'Created offline before the unpair',
            sharedWith: audience,
          })));

          const bob = env.authenticatedContext(BOB).firestore();
          await assertFails(bob.doc('events/unsynced-1').get());

          const snap = await bob.collection('events')
              .where('sharedWith', 'array-contains', BOB).get();
          if (snap.docs.map((d) => d.id).includes('unsynced-1')) {
            throw new Error('the re-uploaded event is back in the ex-partner sync query');
          }
        });

    it('the pre-fix upload audience did re-grant it', async () => {
      const audience = widenOnlyAudience(STALE_LOCAL, ALICE, ALICE, null);

      const alice = env.authenticatedContext(ALICE).firestore();
      await assertSucceeds(alice.doc('events/unsynced-1').set(eventDoc({
        id: 'unsynced-1', title: 'Created offline before the unpair',
        sharedWith: audience,
      })));

      const bob = env.authenticatedContext(BOB).firestore();
      await assertSucceeds(bob.doc('events/unsynced-1').get());
    });

    it('holds on the device that never called unpair', async () => {
      // Only one of the two devices calls `unpairCoParent`, so a purely local cleanup on
      // the caller would leave Bob's Room copy stale — and Bob is a writer too. Deriving
      // the audience from live pairing state is what makes the other device safe as well:
      // once Bob's own row is unpaired, his edit of the event *he* created drops Alice.
      const bobsAudience = editAudience([BOB, ALICE], BOB, BOB, null);
      if (bobsAudience.includes(ALICE)) {
        throw new Error(`the other device still uploads its ex-partner: ${bobsAudience}`);
      }

      const bob = env.authenticatedContext(BOB).firestore();
      await assertSucceeds(bob.doc('events/event-2')
          .update({title: 'Rescheduled', sharedWith: bobsAudience}));

      await assertFails(
          env.authenticatedContext(ALICE).firestore().doc('events/event-2').get());
    });

    it('cannot be re-widened by the ex-partner, whose writes the rules reject', async () => {
      // Bob's device may still hold a stale copy and try to push it. Unlike the creator,
      // he is refused by the rules — the client fix is only needed for the creator.
      const bob = env.authenticatedContext(BOB).firestore();
      await assertFails(bob.doc('events/event-1')
          .update({title: 'Back in', sharedWith: [ALICE, BOB]}));
      await assertFails(bob.doc('child_info/child-1')
          .update({medicalNotes: 'x', sharedWith: [ALICE, BOB]}));
    });

    it('still admits a new co-parent, so intersecting costs no legitimate visibility',
        async () => {
          // Alice re-pairs with Carol. Old events must reach the new co-parent on the next
          // write, which is the only mechanism that ever shares them.
          const audience = editAudience(STALE_LOCAL, ALICE, ALICE, CAROL);
          if (!audience.includes(CAROL) || audience.includes(BOB)) {
            throw new Error(`wrong audience after re-pairing: ${audience}`);
          }

          const alice = env.authenticatedContext(ALICE).firestore();
          await assertSucceeds(alice.doc('events/event-1')
              .update({title: 'Now shared with Carol', sharedWith: audience}));

          await assertSucceeds(
              env.authenticatedContext(CAROL).firestore().doc('events/event-1').get());
          await assertFails(
              env.authenticatedContext(BOB).firestore().doc('events/event-1').get());
        });
  });

  describe('sweep edge cases', () => {
    it('is idempotent — a second run narrows nothing', async () => {
      await applyUnpairSweep(env, ALICE, BOB);
      const second = await applyUnpairSweep(env, ALICE, BOB);
      if (second !== 0) {
        throw new Error(`expected a repeat sweep to be a no-op, narrowed ${second}`);
      }
    });

    it('never drops a creator listed only in their own audience', async () => {
      await env.withSecurityRulesDisabled(async (ctx) => {
        await ctx.firestore().doc('events/event-3')
            .set(eventDoc({id: 'event-3', sharedWith: [ALICE]}));
      });
      await applyUnpairSweep(env, ALICE, BOB);
      await assertSucceeds(
          env.authenticatedContext(ALICE).firestore().doc('events/event-3').get());
    });

    it('leaves a private, never-shared event of a third party alone', async () => {
      await env.withSecurityRulesDisabled(async (ctx) => {
        await ctx.firestore().doc('events/event-4').set(eventDoc({
          id: 'event-4', createdByFirebaseUid: 'carol-uid',
          sharedWith: ['carol-uid'], lastModifiedBy: 'carol-uid',
        }));
      });
      await applyUnpairSweep(env, ALICE, BOB);
      await assertSucceeds(env.authenticatedContext('carol-uid').firestore()
          .doc('events/event-4').get());
    });
  });
});
