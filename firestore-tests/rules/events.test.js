/**
 * Part 1d / Part 2 — the `events` block.
 *
 * The document shape mirrors `EventRepositoryImpl.toFirestoreMap()`, which is the single
 * definition of the wire schema. Access is keyed on `createdByFirebaseUid` and the
 * per-document `sharedWith` audience; `permissions` gates whether a shared reader may
 * also write.
 */

const {
  CURRENT_RULES, testEnv, seed, assertSucceeds, assertFails,
} = require('../harness');

const PROJECT = 'demo-coplanly-events';
const ALICE = 'alice-uid';
const BOB = 'bob-uid';
const CAROL = 'carol-uid';

/**
 * Builds an event document exactly as `toFirestoreMap()` writes it.
 *
 * @param {!Object} overrides Fields to override on the default document.
 * @return {!Object} The document data.
 */
function eventDoc(overrides) {
  return Object.assign({
    id: 'event-1',
    title: 'Swimming lesson',
    description: '',
    startDateTime: '2026-08-05T16:00:00',
    endDateTime: '2026-08-05T17:00:00',
    eventType: 'ACTIVITY',
    parentOwner: 'MOM',
    isRecurring: false,
    recurrencePattern: '',
    recurrenceEndDate: '',
    pickupConfirmedBy: '',
    pickupConfirmedAt: '',
    createdAt: '2026-08-01T10:00:00',
    updatedAt: '2026-08-01T10:00:00',
    createdByFirebaseUid: ALICE,
    sharedWith: [ALICE, BOB],
    lastModifiedBy: ALICE,
    permissions: 'read_write',
    imageUrl: '',
    acceptance: 'NOT_REQUIRED',
    acceptedBy: '',
    acceptedAt: '',
  }, overrides);
}

describe('Part 1d: events', () => {
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

  describe('read', () => {
    it('allows the creator', async () => {
      await seed(env, {'events/event-1': eventDoc({})});
      await assertSucceeds(
          env.authenticatedContext(ALICE).firestore().doc('events/event-1').get());
    });

    it('allows a uid listed in sharedWith', async () => {
      await seed(env, {'events/event-1': eventDoc({})});
      await assertSucceeds(
          env.authenticatedContext(BOB).firestore().doc('events/event-1').get());
    });

    it('denies a uid absent from sharedWith', async () => {
      await seed(env, {'events/event-1': eventDoc({})});
      await assertFails(
          env.authenticatedContext(CAROL).firestore().doc('events/event-1').get());
    });

    it('denies a paired co-parent who is not in sharedWith', async () => {
      // Pairing alone grants nothing on events: the audience is per document.
      await seed(env, {'events/event-1': eventDoc({sharedWith: [ALICE]})});
      await assertFails(
          env.authenticatedContext(BOB).firestore().doc('events/event-1').get());
    });

    it('allows the sharedWith query the down-sync runs', async () => {
      await seed(env, {'events/event-1': eventDoc({})});
      const db = env.authenticatedContext(BOB).firestore();
      await assertSucceeds(
          db.collection('events').where('sharedWith', 'array-contains', BOB).get());
    });

    it('denies an unfiltered collection read', async () => {
      await seed(env, {'events/event-1': eventDoc({})});
      await assertFails(env.authenticatedContext(BOB).firestore().collection('events').get());
    });
  });

  describe('create', () => {
    it('allows the creator stamping their own uid', async () => {
      const db = env.authenticatedContext(ALICE).firestore();
      await assertSucceeds(db.doc('events/event-1').set(eventDoc({})));
    });

    it('denies stamping somebody else as the creator', async () => {
      const db = env.authenticatedContext(BOB).firestore();
      await assertFails(db.doc('events/event-1').set(eventDoc({})));
    });

    it('denies a document missing a required key', async () => {
      const doc = eventDoc({});
      delete doc.eventType;
      const db = env.authenticatedContext(ALICE).firestore();
      await assertFails(db.doc('events/event-1').set(doc));
    });

    it('denies an empty title', async () => {
      const db = env.authenticatedContext(ALICE).firestore();
      await assertFails(db.doc('events/event-1').set(eventDoc({title: ''})));
    });
  });

  describe('update', () => {
    it('allows the creator', async () => {
      await seed(env, {'events/event-1': eventDoc({})});
      const db = env.authenticatedContext(ALICE).firestore();
      await assertSucceeds(db.doc('events/event-1').update({title: 'Renamed'}));
    });

    it('allows a shared uid when permissions are read_write', async () => {
      await seed(env, {'events/event-1': eventDoc({})});
      const db = env.authenticatedContext(BOB).firestore();
      await assertSucceeds(db.doc('events/event-1').update({title: 'Renamed by Bob'}));
    });

    it('denies a shared uid when permissions are read_only', async () => {
      await seed(env, {'events/event-1': eventDoc({permissions: 'read_only'})});
      const db = env.authenticatedContext(BOB).firestore();
      await assertFails(db.doc('events/event-1').update({title: 'Renamed by Bob'}));
    });

    it('denies reassigning the creator', async () => {
      await seed(env, {'events/event-1': eventDoc({})});
      const db = env.authenticatedContext(BOB).firestore();
      await assertFails(db.doc('events/event-1').update({createdByFirebaseUid: BOB}));
    });

    it('denies a uid absent from sharedWith', async () => {
      await seed(env, {'events/event-1': eventDoc({})});
      const db = env.authenticatedContext(CAROL).firestore();
      await assertFails(db.doc('events/event-1').update({title: 'Renamed by Carol'}));
    });

    describe('acceptance', () => {
      // No rule was added for these fields: they live on a document the audience already reads,
      // and the update rule already requires `createdByFirebaseUid` to be unchanged, which
      // permits the recipient. That is exactly why it is pinned — "it should already work" is
      // the kind of belief CLAUDE.md forbids checking on a phone.

      it('lets the recipient accept an event they did not create', async () => {
        await seed(env, {
          'events/event-1': eventDoc({acceptance: 'PENDING'}),
        });
        const db = env.authenticatedContext(BOB).firestore();
        await assertSucceeds(db.doc('events/event-1').update({
          acceptance: 'ACCEPTED',
          acceptedBy: BOB,
          acceptedAt: '2026-08-05T12:00:00',
        }));
      });

      it('lets the recipient decline one too', async () => {
        await seed(env, {
          'events/event-1': eventDoc({acceptance: 'PENDING'}),
        });
        const db = env.authenticatedContext(BOB).firestore();
        await assertSucceeds(db.doc('events/event-1').update({
          acceptance: 'DECLINED',
          acceptedBy: BOB,
          acceptedAt: '2026-08-05T12:00:00',
        }));
      });

      it('still denies a stranger, who is in nobody\'s audience', async () => {
        await seed(env, {
          'events/event-1': eventDoc({acceptance: 'PENDING'}),
        });
        const db = env.authenticatedContext(CAROL).firestore();
        await assertFails(db.doc('events/event-1').update({
          acceptance: 'ACCEPTED', acceptedBy: CAROL,
        }));
      });

      it('denies a read_only recipient, same as any other field', async () => {
        await seed(env, {
          'events/event-1': eventDoc({acceptance: 'PENDING', permissions: 'read_only'}),
        });
        const db = env.authenticatedContext(BOB).firestore();
        await assertFails(db.doc('events/event-1').update({
          acceptance: 'ACCEPTED', acceptedBy: BOB,
        }));
      });
    });
  });

  describe('delete', () => {
    it('allows the creator', async () => {
      await seed(env, {'events/event-1': eventDoc({})});
      await assertSucceeds(
          env.authenticatedContext(ALICE).firestore().doc('events/event-1').delete());
    });

    it('denies a shared non-creator', async () => {
      await seed(env, {'events/event-1': eventDoc({})});
      await assertFails(
          env.authenticatedContext(BOB).firestore().doc('events/event-1').delete());
    });
  });
});
