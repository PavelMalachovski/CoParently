/**
 * CQ-3 — a deletion that the co-parent can actually receive.
 *
 * A delete used to be `document().delete()`, and a deleted document is not a fact anybody can
 * be told: the downstream half of the sync only ever inserted, so the other parent kept the
 * cancelled event forever. A delete is now a **tombstone** — the document survives and gains
 * `deletedAtMillis`, which reaches the co-parent through the same query that delivers every
 * other change.
 *
 * That turns a `delete` into an `update`, so the rules that decide who may delete are no longer
 * the `allow delete` clauses. These tests pin what actually governs it now, in both directions:
 * that the tombstone can be written by the people who could already delete, that it *cannot* be
 * written by anyone else, and — the part with no equivalent before — that a tombstoned document
 * is still readable, because a deletion nobody may read is a deletion nobody is told about.
 */

const {
  CURRENT_RULES, testEnv, seed, assertSucceeds, assertFails,
} = require('../harness');

const PROJECT = 'demo-coplanly-tombstones';
const ALICE = 'alice-uid';
const BOB = 'bob-uid';
const CAROL = 'carol-uid';

const DELETED_AT = 1787000000000;

/** @return {!Object} A tombstone write, exactly as `Tombstone.fields()` builds it. */
function tombstone(by) {
  return {deletedAtMillis: DELETED_AT, deletedBy: by};
}

/**
 * Builds an event document as `EventRepositoryImpl.toFirestoreMap()` writes it.
 *
 * @param {!Object} overrides Fields to override.
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
    parentOwner: 'mom',
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

/**
 * Builds an expense document as `ExpenseRepositoryImpl` writes it.
 *
 * @param {!Object} overrides Fields to override.
 * @return {!Object} The document data.
 */
function expenseDoc(overrides) {
  return Object.assign({
    id: 'expense-1',
    title: 'School trip',
    amount: 42.5,
    currency: 'CZK',
    category: 'EDUCATION',
    createdByFirebaseUid: ALICE,
    paidBy: ALICE,
    splitBetween: [],
    date: '2026-08-01',
    createdAt: '2026-08-01T10:00:00',
  }, overrides);
}

/** @return {!Object} The two linked parents plus an unrelated third user. */
function users() {
  return {
    'users/alice-uid': {name: 'Alice', email: 'a@x.test', partnerId: BOB},
    'users/bob-uid': {name: 'Bob', email: 'b@x.test', partnerId: ALICE},
    'users/carol-uid': {name: 'Carol', email: 'c@x.test', partnerId: ''},
  };
}

describe('CQ-3: tombstones', () => {
  let env;

  before(async () => {
    env = await testEnv(PROJECT, CURRENT_RULES);
  });

  beforeEach(async () => {
    await env.clearFirestore();
    await seed(env, users());
  });

  describe('events', () => {
    it('lets the creator tombstone their own event', async () => {
      await seed(env, {'events/event-1': eventDoc({})});
      const db = env.authenticatedContext(ALICE).firestore();
      await assertSucceeds(db.doc('events/event-1').update(tombstone(ALICE)));
    });

    it('lets a shared co-parent with read_write tombstone it', async () => {
      // Wider than the old `allow delete`, which was creator-only — but not a new power. A
      // read_write co-parent could already rewrite every field of this event, so refusing them
      // the one field that says "cancelled" would only mean they blanked it out instead.
      await seed(env, {'events/event-1': eventDoc({})});
      const db = env.authenticatedContext(BOB).firestore();
      await assertSucceeds(db.doc('events/event-1').update(tombstone(BOB)));
    });

    it('denies a shared co-parent when permissions are read_only', async () => {
      await seed(env, {'events/event-1': eventDoc({permissions: 'read_only'})});
      const db = env.authenticatedContext(BOB).firestore();
      await assertFails(db.doc('events/event-1').update(tombstone(BOB)));
    });

    it('denies a stranger', async () => {
      await seed(env, {'events/event-1': eventDoc({})});
      const db = env.authenticatedContext(CAROL).firestore();
      await assertFails(db.doc('events/event-1').update(tombstone(CAROL)));
    });

    it('denies a tombstone that also rewrites the audience', async () => {
      // The pin that makes the wider write above safe: a co-parent may say "deleted", not
      // "deleted, and also nobody else may see this" — dropping the creator from `sharedWith`
      // removes the event from their sync feed, which is a silent deletion by another name.
      await seed(env, {'events/event-1': eventDoc({})});
      const db = env.authenticatedContext(BOB).firestore();
      await assertFails(db.doc('events/event-1').update(
          Object.assign(tombstone(BOB), {sharedWith: [BOB]})));
    });

    it('keeps a tombstoned event readable by the co-parent', async () => {
      // The whole point. A deletion is delivered by being read; a tombstone the other parent
      // may not fetch would leave the event on their calendar exactly as a hard delete did.
      await seed(env, {'events/event-1': eventDoc(tombstone(ALICE))});
      await assertSucceeds(
          env.authenticatedContext(BOB).firestore().doc('events/event-1').get());
    });

    it('keeps a tombstoned event inside the sharedWith query the down-sync runs', async () => {
      // Readability per document is not enough: the download half issues one collection query,
      // and a tombstone that fell outside it would never be fetched at all.
      await seed(env, {'events/event-1': eventDoc(tombstone(ALICE))});
      const db = env.authenticatedContext(BOB).firestore();
      await assertSucceeds(
          db.collection('events').where('sharedWith', 'array-contains', BOB).get());
    });
  });

  describe('expenses', () => {
    it('lets the creator tombstone their own expense', async () => {
      await seed(env, {'expenses/expense-1': expenseDoc({})});
      const db = env.authenticatedContext(ALICE).firestore();
      await assertSucceeds(db.doc('expenses/expense-1').update(tombstone(ALICE)));
    });

    it('denies the co-parent, exactly as the old delete did', async () => {
      // Deliberately unchanged. `expenses` update is creator-only by an owner decision from the
      // August 2026 walkthrough — a co-parent must not rewrite the other's recorded expense —
      // and `delete` was creator-only for the same reason. Tombstoning does not widen it. What
      // changed is only that the refusal is now retried and visible instead of logged and lost.
      await seed(env, {'expenses/expense-1': expenseDoc({})});
      const db = env.authenticatedContext(BOB).firestore();
      await assertFails(db.doc('expenses/expense-1').update(tombstone(BOB)));
    });

    it('denies a stranger', async () => {
      await seed(env, {'expenses/expense-1': expenseDoc({})});
      const db = env.authenticatedContext(CAROL).firestore();
      await assertFails(db.doc('expenses/expense-1').update(tombstone(CAROL)));
    });

    it('keeps a tombstoned expense readable by the co-parent', async () => {
      await seed(env, {'expenses/expense-1': expenseDoc(tombstone(ALICE))});
      await assertSucceeds(
          env.authenticatedContext(BOB).firestore().doc('expenses/expense-1').get());
    });

    it('keeps a tombstoned expense inside the creator-filtered query the sync runs', async () => {
      await seed(env, {'expenses/expense-1': expenseDoc(tombstone(ALICE))});
      const db = env.authenticatedContext(BOB).firestore();
      await assertSucceeds(
          db.collection('expenses').where('createdByFirebaseUid', 'in', [ALICE, BOB]).get());
    });
  });
});
