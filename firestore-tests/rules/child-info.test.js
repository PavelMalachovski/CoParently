/**
 * Part 1d / Part 3 — the `child_info` block.
 *
 * `child_info` is visible purely through its per-document `sharedWith` list — both for
 * the read rule and for `FirestoreChildInfoDataSource.getChildInfoForParent`, which
 * queries `whereArrayContains("sharedWith", parentId)`. A document that loses that field
 * is therefore invisible to everybody and, because the update rule reads
 * `resource.data.sharedWith`, permanently un-updatable as well.
 */

const {
  CURRENT_RULES, testEnv, seed, assertSucceeds, assertFails,
} = require('../harness');

const PROJECT = 'demo-coplanly-childinfo';
const ALICE = 'alice-uid';
const BOB = 'bob-uid';
const CAROL = 'carol-uid';

/**
 * Builds a child_info document as `SyncService.syncChildInfo` writes it.
 *
 * @param {!Object} overrides Fields to override on the default document.
 * @return {!Object} The document data.
 */
function childInfoDoc(overrides) {
  return Object.assign({
    id: 'child-1',
    childName: 'Ema',
    dateOfBirth: '2018-03-04T00:00:00',
    medications: [],
    activities: [],
    allergies: [],
    medicalNotes: null,
    emergencyContacts: [],
    schoolInfo: null,
    createdAt: '2026-08-01T10:00:00',
    updatedAt: '2026-08-01T10:00:00',
    createdByFirebaseUid: ALICE,
    lastModifiedBy: ALICE,
    sharedWith: [ALICE, BOB],
  }, overrides);
}

describe('Part 1d: child_info', () => {
  let env;

  before(async () => {
    env = await testEnv(PROJECT, CURRENT_RULES);
  });

  beforeEach(async () => {
    await env.clearFirestore();
    await seed(env, {
      'users/alice-uid': {name: 'Alice', email: 'a@x.test', partnerId: BOB},
      'users/bob-uid': {name: 'Bob', email: 'b@x.test', partnerId: ALICE},
      'users/carol-uid': {name: 'Carol', email: 'c@x.test', partnerId: ''},
    });
  });

  it('lets both listed parents read', async () => {
    await seed(env, {'child_info/child-1': childInfoDoc({})});
    await assertSucceeds(
        env.authenticatedContext(ALICE).firestore().doc('child_info/child-1').get());
    await assertSucceeds(
        env.authenticatedContext(BOB).firestore().doc('child_info/child-1').get());
  });

  it('denies an unlisted user', async () => {
    await seed(env, {'child_info/child-1': childInfoDoc({})});
    await assertFails(
        env.authenticatedContext(CAROL).firestore().doc('child_info/child-1').get());
  });

  it('serves the sharedWith query the sync runs', async () => {
    await seed(env, {'child_info/child-1': childInfoDoc({})});
    const db = env.authenticatedContext(BOB).firestore();
    await assertSucceeds(
        db.collection('child_info').where('sharedWith', 'array-contains', BOB).get());
  });

  it('allows a create that lists the creator', async () => {
    const db = env.authenticatedContext(ALICE).firestore();
    await assertSucceeds(db.doc('child_info/child-1').set(childInfoDoc({})));
  });

  it('denies a create that omits the creator from sharedWith', async () => {
    const db = env.authenticatedContext(ALICE).firestore();
    await assertFails(db.doc('child_info/child-1').set(childInfoDoc({sharedWith: [BOB]})));
  });

  it('denies a create stamping somebody else as creator', async () => {
    const db = env.authenticatedContext(BOB).firestore();
    await assertFails(db.doc('child_info/child-1').set(childInfoDoc({})));
  });

  it('lets either listed parent update', async () => {
    await seed(env, {'child_info/child-1': childInfoDoc({})});
    await assertSucceeds(env.authenticatedContext(BOB).firestore()
        .doc('child_info/child-1').update({medicalNotes: 'Pollen allergy'}));
  });

  it('denies reassigning the creator on update', async () => {
    await seed(env, {'child_info/child-1': childInfoDoc({})});
    await assertFails(env.authenticatedContext(BOB).firestore()
        .doc('child_info/child-1').update({createdByFirebaseUid: BOB}));
  });

  it('lets only the creator delete', async () => {
    await seed(env, {'child_info/child-1': childInfoDoc({})});
    await assertFails(
        env.authenticatedContext(BOB).firestore().doc('child_info/child-1').delete());
    await assertSucceeds(
        env.authenticatedContext(ALICE).firestore().doc('child_info/child-1').delete());
  });

  it('lets a co-parent read once sharedWith names them (the audience backfill outcome)', async () => {
    // The state SyncService.backfillAudienceForPartner produces: a row created before pairing,
    // re-uploaded afterwards with the co-parent added to sharedWith.
    await seed(env, {'child_info/child-1': childInfoDoc({sharedWith: [ALICE, BOB]})});
    await assertSucceeds(
        env.authenticatedContext(BOB).firestore().doc('child_info/child-1').get());
  });

  it('keeps a child_info document private while sharedWith names only its creator', async () => {
    // The state every document was in before this branch: the pre-pairing upload, never
    // revisited because the backfill only re-queues once the pairing exists.
    await seed(env, {'child_info/child-1': childInfoDoc({sharedWith: [ALICE]})});
    await assertFails(
        env.authenticatedContext(BOB).firestore().doc('child_info/child-1').get());
  });

  it('lets a co-parent in sharedWith add an emergency contact', async () => {
    // Item 5 says the second parent may add to the contacts. This is the document where that
    // is allowed - the parent's own users/{uid} is not, and must not become so.
    await seed(env, {'child_info/child-1': childInfoDoc({sharedWith: [ALICE, BOB]})});
    await assertSucceeds(env.authenticatedContext(BOB).firestore()
        .doc('child_info/child-1').update({
          emergencyContacts: [{name: 'Grandma', relationship: 'grandmother', phone: '+420...'}],
        }));
  });

  it('refuses to let a co-parent rewrite who created the document', async () => {
    await seed(env, {'child_info/child-1': childInfoDoc({sharedWith: [ALICE, BOB]})});
    await assertFails(env.authenticatedContext(BOB).firestore()
        .doc('child_info/child-1').update({createdByFirebaseUid: BOB}));
  });

  describe('Part 3: a document whose sharedWith has been stripped', () => {
    beforeEach(async () => {
      const stripped = childInfoDoc({});
      delete stripped.sharedWith;
      await seed(env, {'child_info/child-1': stripped});
    });

    it('is unreadable by its own creator', async () => {
      await assertFails(
          env.authenticatedContext(ALICE).firestore().doc('child_info/child-1').get());
    });

    it('is unreadable by the co-parent', async () => {
      await assertFails(
          env.authenticatedContext(BOB).firestore().doc('child_info/child-1').get());
    });

    it('is un-updatable by its own creator (missing-field error on the update rule)', async () => {
      await assertFails(env.authenticatedContext(ALICE).firestore()
          .doc('child_info/child-1').update({medicalNotes: 'x'}));
    });

    it('is invisible to the sharedWith query both parents sync through', async () => {
      const db = env.authenticatedContext(ALICE).firestore();
      const snap = await db.collection('child_info')
          .where('sharedWith', 'array-contains', ALICE).get();
      if (!snap.empty) {
        throw new Error('expected the stripped document to be invisible to the sync query');
      }
    });

    it('can still be deleted by the creator, which is the only way out', async () => {
      await assertSucceeds(
          env.authenticatedContext(ALICE).firestore().doc('child_info/child-1').delete());
    });
  });
});
