/**
 * The `pets` block.
 *
 * `pets` is visible purely through its per-document `sharedWith` list, the same shape as
 * `child_info` — `FirestorePetDataSource.getPetsForParent` queries
 * `whereArrayContains("sharedWith", parentId)`. Pets have no guest grants, so this is
 * `child_info` without the guest-expiry read gate: read is bare membership, write is
 * creator-or-partner, delete is creator-only, and `createdByFirebaseUid` is immutable.
 */

const {
  CURRENT_RULES, testEnv, seed, assertSucceeds, assertFails,
} = require('../harness');

const PROJECT = 'demo-coplanly-pets';
const ALICE = 'alice-uid';
const BOB = 'bob-uid';
const CAROL = 'carol-uid';

/**
 * Builds a pet document as `PetRepositoryImpl.toFirestoreMap` writes it.
 *
 * @param {!Object} overrides Fields to override on the default document.
 * @return {!Object} The document data.
 */
function petDoc(overrides) {
  return Object.assign({
    id: 'pet-1',
    name: 'Rex',
    species: 'DOG',
    breed: 'Labrador',
    dateOfBirth: '2020-05-01T00:00:00',
    medications: [],
    vaccinations: [],
    specialNeeds: null,
    feedingNotes: null,
    vetName: null,
    vetPhone: null,
    photos: [],
    createdAt: '2026-08-01T10:00:00',
    updatedAt: '2026-08-01T10:00:00',
    createdByFirebaseUid: ALICE,
    lastModifiedBy: ALICE,
    sharedWith: [ALICE, BOB],
  }, overrides);
}

describe('pets', () => {
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
    await seed(env, {'pets/pet-1': petDoc({})});
    await assertSucceeds(
        env.authenticatedContext(ALICE).firestore().doc('pets/pet-1').get());
    await assertSucceeds(
        env.authenticatedContext(BOB).firestore().doc('pets/pet-1').get());
  });

  it('denies an unlisted user', async () => {
    await seed(env, {'pets/pet-1': petDoc({})});
    await assertFails(
        env.authenticatedContext(CAROL).firestore().doc('pets/pet-1').get());
  });

  it('serves the sharedWith query the sync runs', async () => {
    await seed(env, {'pets/pet-1': petDoc({})});
    const db = env.authenticatedContext(BOB).firestore();
    await assertSucceeds(
        db.collection('pets').where('sharedWith', 'array-contains', BOB).get());
  });

  it('allows a create that lists the creator', async () => {
    const db = env.authenticatedContext(ALICE).firestore();
    await assertSucceeds(db.doc('pets/pet-1').set(petDoc({})));
  });

  it('denies a create that omits the creator from sharedWith', async () => {
    const db = env.authenticatedContext(ALICE).firestore();
    await assertFails(db.doc('pets/pet-1').set(petDoc({sharedWith: [BOB]})));
  });

  it('denies a create stamping somebody else as creator', async () => {
    const db = env.authenticatedContext(BOB).firestore();
    await assertFails(db.doc('pets/pet-1').set(petDoc({})));
  });

  it('denies a create with a blank name', async () => {
    const db = env.authenticatedContext(ALICE).firestore();
    await assertFails(db.doc('pets/pet-1').set(petDoc({name: ''})));
  });

  it('lets either listed parent update', async () => {
    await seed(env, {'pets/pet-1': petDoc({})});
    await assertSucceeds(env.authenticatedContext(BOB).firestore()
        .doc('pets/pet-1').update({feedingNotes: 'Twice a day'}));
  });

  it('denies reassigning the creator on update', async () => {
    await seed(env, {'pets/pet-1': petDoc({})});
    await assertFails(env.authenticatedContext(BOB).firestore()
        .doc('pets/pet-1').update({createdByFirebaseUid: BOB}));
  });

  it('lets only the creator delete', async () => {
    await seed(env, {'pets/pet-1': petDoc({})});
    await assertFails(
        env.authenticatedContext(BOB).firestore().doc('pets/pet-1').delete());
    await assertSucceeds(
        env.authenticatedContext(ALICE).firestore().doc('pets/pet-1').delete());
  });

  it('keeps a pet private while sharedWith names only its creator', async () => {
    // The pre-pairing upload state, before the audience repair adds the co-parent.
    await seed(env, {'pets/pet-1': petDoc({sharedWith: [ALICE]})});
    await assertFails(
        env.authenticatedContext(BOB).firestore().doc('pets/pet-1').get());
  });

  it('denies an update from a member who is neither creator nor their partner', async () => {
    // Carol is in the audience and nothing else — audience membership must not imply write.
    await seed(env, {'pets/pet-1': petDoc({sharedWith: [ALICE, BOB, CAROL]})});
    await assertFails(env.authenticatedContext(CAROL).firestore()
        .doc('pets/pet-1').update({feedingNotes: 'I changed this'}));
  });

  it('lets the partner update a pet the partner created', async () => {
    // isPartnerOf is not symmetric by construction, so both directions are pinned.
    await seed(env, {
      'pets/pet-2': petDoc({id: 'pet-2', createdByFirebaseUid: BOB, lastModifiedBy: BOB}),
    });
    await assertSucceeds(env.authenticatedContext(ALICE).firestore()
        .doc('pets/pet-2').update({feedingNotes: 'Once a day'}));
  });
});
