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

  describe('Package G2: audience membership must not imply write', () => {
    // The rule this whole package rests on. A guest is put into `sharedWith` so the *read*
    // rule works unchanged — which, under an update rule that allows any member of the
    // audience to write, would make a grandparent an editor of a child's medical record.
    //
    // Written before the rule changed, and every pre-existing case in this file has to keep
    // passing afterwards: if one stops, the audience model differs from what the design
    // assumed and the plan needs revisiting, not the test.

    it('denies an update from a uid in sharedWith who is neither creator nor their partner',
        async () => {
          // Carol is in the audience and nothing else — exactly a guest's position.
          await seed(env, {'child_info/child-1': childInfoDoc({sharedWith: [ALICE, BOB, CAROL]})});
          await assertFails(env.authenticatedContext(CAROL).firestore()
              .doc('child_info/child-1').update({medicalNotes: 'I changed this'}));
        });

    it('denies such a uid adding a photograph to the record', async () => {
      // The same hole, pointed at what package G1 just added. A guest who could write the
      // record could attach or remove medical photographs on a child that is not theirs.
      await seed(env, {'child_info/child-1': childInfoDoc({sharedWith: [ALICE, BOB, CAROL]})});
      await assertFails(env.authenticatedContext(CAROL).firestore()
          .doc('child_info/child-1').update({medicalPhotos: ['https://example.test/x.jpg']}));
    });

    it('still lets the creator update', async () => {
      await seed(env, {'child_info/child-1': childInfoDoc({sharedWith: [ALICE, BOB, CAROL]})});
      await assertSucceeds(env.authenticatedContext(ALICE).firestore()
          .doc('child_info/child-1').update({medicalNotes: 'Pollen allergy'}));
    });

    it("still lets the creator's partner update", async () => {
      await seed(env, {'child_info/child-1': childInfoDoc({sharedWith: [ALICE, BOB, CAROL]})});
      await assertSucceeds(env.authenticatedContext(BOB).firestore()
          .doc('child_info/child-1').update({medicalNotes: 'Pollen allergy'}));
    });

    it('lets the partner update a record the partner created', async () => {
      // The mirror of the case above: Bob creates, Alice edits. `isPartnerOf` is not
      // symmetric by construction — it reads one uid's `partnerId` — so both directions are
      // pinned rather than assumed.
      await seed(env, {
        'child_info/child-2': childInfoDoc({
          id: 'child-2', createdByFirebaseUid: BOB, lastModifiedBy: BOB,
        }),
      });
      await assertSucceeds(env.authenticatedContext(ALICE).firestore()
          .doc('child_info/child-2').update({medicalNotes: 'Pollen allergy'}));
    });

    it('denies a uid outside sharedWith entirely, as it always did', async () => {
      await seed(env, {'child_info/child-1': childInfoDoc({sharedWith: [ALICE, BOB]})});
      await assertFails(env.authenticatedContext(CAROL).firestore()
          .doc('child_info/child-1').update({medicalNotes: 'I changed this'}));
    });
  });

  describe('Package G2: a guest reads, and only a parent may let one in', () => {
    // A guest is a uid in `sharedWith` — which is what makes the read rule work unchanged —
    // plus an entry in `guests`, which is what makes them a guest rather than a parent. The
    // update rule tightened above is what keeps the first half from implying the second.

    // Epoch millis, not an ISO string: `firestore.rules` has no way to parse a string into a
    // timestamp, so millis is the only representation the app, the rule and the sweep can all
    // compare. See `GuestGrant.expiresAtMillis`.
    const FUTURE = Date.parse('2099-01-01T00:00:00Z');
    const PAST = Date.parse('2020-01-01T00:00:00Z');

    /**
     * A guests map holding one grant.
     *
     * @param {string} uid The guest's UID.
     * @param {number} expiresAtMillis Epoch millis the grant ends at.
     * @return {!Object} The map to store under `guests`.
     */
    function guests(uid, expiresAtMillis) {
      return {[uid]: {
        name: 'Nina',
        grantedBy: ALICE,
        grantedAtMillis: Date.parse('2026-08-23T09:30:00Z'),
        expiresAtMillis,
      }};
    }

    it('lets a guest read the child record', async () => {
      await seed(env, {'child_info/child-1': childInfoDoc({
        sharedWith: [ALICE, BOB, CAROL], guests: guests(CAROL, FUTURE),
      })});
      await assertSucceeds(
          env.authenticatedContext(CAROL).firestore().doc('child_info/child-1').get());
    });

    it('denies a guest whose grant has expired', async () => {
      // The rule is the first of two defences and the weaker one: it stops the read, but the
      // uid stays in `sharedWith`, so the record keeps coming back from the audience query.
      // The scheduled sweep is what actually removes them. Both are needed.
      await seed(env, {'child_info/child-1': childInfoDoc({
        sharedWith: [ALICE, BOB, CAROL], guests: guests(CAROL, PAST),
      })});
      await assertFails(
          env.authenticatedContext(CAROL).firestore().doc('child_info/child-1').get());
    });

    it('denies a guest whose grant carries no usable expiry', async () => {
      // Fail closed, the same way GuestGrantPolicy does: a non-positive value is a field that
      // was absent, defaulted, or written by something that did not know it had to fill it in,
      // and it must not read as "no expiry".
      await seed(env, {'child_info/child-1': childInfoDoc({
        sharedWith: [ALICE, BOB, CAROL], guests: guests(CAROL, 0),
      })});
      await assertFails(
          env.authenticatedContext(CAROL).firestore().doc('child_info/child-1').get());
    });

    it('denies a guest whose grant has no expiry field at all', async () => {
      await seed(env, {'child_info/child-1': childInfoDoc({
        sharedWith: [ALICE, BOB, CAROL],
        guests: {[CAROL]: {name: 'Nina', grantedBy: ALICE}},
      })});
      await assertFails(
          env.authenticatedContext(CAROL).firestore().doc('child_info/child-1').get());
    });

    it('lets a participant parent add a guest', async () => {
      await seed(env, {'child_info/child-1': childInfoDoc({})});
      await assertSucceeds(env.authenticatedContext(BOB).firestore()
          .doc('child_info/child-1').update({
            sharedWith: [ALICE, BOB, CAROL], guests: guests(CAROL, FUTURE),
          }));
    });

    it('denies a guest adding another guest', async () => {
      // The escalation this package must not have: a grandparent handing the record on.
      await seed(env, {'child_info/child-1': childInfoDoc({
        sharedWith: [ALICE, BOB, CAROL], guests: guests(CAROL, FUTURE),
      })});
      await assertFails(env.authenticatedContext(CAROL).firestore()
          .doc('child_info/child-1').update({
            sharedWith: [ALICE, BOB, CAROL, 'dave-uid'],
            guests: Object.assign(guests(CAROL, FUTURE), guests('dave-uid', FUTURE)),
          }));
    });

    it('denies a guest removing themselves from the guests map to become a plain member',
        async () => {
          // Without this, a guest could drop their own grant while staying in `sharedWith` and
          // read the record forever — the expiry rule only bites on uids the map still names.
          await seed(env, {'child_info/child-1': childInfoDoc({
            sharedWith: [ALICE, BOB, CAROL], guests: guests(CAROL, FUTURE),
          })});
          await assertFails(env.authenticatedContext(CAROL).firestore()
              .doc('child_info/child-1').update({guests: {}}));
        });

    it('denies a non-participant adding a guest', async () => {
      await seed(env, {'child_info/child-1': childInfoDoc({})});
      await assertFails(env.authenticatedContext(CAROL).firestore()
          .doc('child_info/child-1').update({
            sharedWith: [ALICE, BOB, CAROL], guests: guests(CAROL, FUTURE),
          }));
    });

    it('lets a parent revoke a guest', async () => {
      await seed(env, {'child_info/child-1': childInfoDoc({
        sharedWith: [ALICE, BOB, CAROL], guests: guests(CAROL, FUTURE),
      })});
      await assertSucceeds(env.authenticatedContext(ALICE).firestore()
          .doc('child_info/child-1').update({sharedWith: [ALICE, BOB], guests: {}}));
    });

    it('a guest may not read the record once revoked', async () => {
      await seed(env, {'child_info/child-1': childInfoDoc({sharedWith: [ALICE, BOB], guests: {}})});
      await assertFails(
          env.authenticatedContext(CAROL).firestore().doc('child_info/child-1').get());
    });

    it('a record with no guests key at all still reads for its parents', async () => {
      // Every document written before this field existed. `guests` is absent, not empty, and a
      // rule that indexed it directly would fail with a missing-field error rather than
      // falling through — the hazard this file has already been bitten by twice.
      await seed(env, {'child_info/child-1': childInfoDoc({})});
      await assertSucceeds(
          env.authenticatedContext(BOB).firestore().doc('child_info/child-1').get());
    });
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
