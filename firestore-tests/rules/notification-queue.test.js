/**
 * Part 1c — the `notification_queue` create rule's allow path.
 *
 * The first disjunct (`targetUserId == request.auth.uid`) is false for every real client
 * write: `FcmService.queueNotificationForUser` is only ever called with the co-parent's
 * uid (`SyncService.notifyEventUpdate` / `notifyChildInfoUpdate`,
 * `ChangeRequestRepositoryImpl`). So every legitimate enqueue depends on `isPartnerOf`
 * returning true — a path b2bf6b83 introduced and nothing in the project ever executed.
 */

const {
  CURRENT_RULES, testEnv, seed, assertSucceeds, assertFails,
} = require('../harness');

const PROJECT = 'demo-coplanly-notifications';
const ALICE = 'alice-uid';
const BOB = 'bob-uid';
const CAROL = 'carol-uid';

/** The exact document shape `FcmService.queueNotificationForUser` writes. */
const PAIRED_USERS = {
  'users/alice-uid': {name: 'Alice', email: 'a@x.test', partnerId: BOB},
  'users/bob-uid': {name: 'Bob', email: 'b@x.test', partnerId: ALICE},
};

/**
 * Builds the queue document `FcmService.queueNotificationForUser` writes.
 *
 * @param {string} targetUid Notification addressee.
 * @return {!Object} The document data.
 */
function queueDoc(targetUid) {
  return {
    targetUserId: targetUid,
    data: {
      type: 'event_created',
      eventId: 'event-1',
      title: 'New Event: Swimming',
      body: 'Alice created an event',
      timestamp: '1754000000000',
    },
    createdAt: 1754000000000,
    status: 'pending',
  };
}

describe('Part 1c: notification_queue create', () => {
  let env;

  before(async () => {
    env = await testEnv(PROJECT, CURRENT_RULES);
  });

  beforeEach(async () => {
    await env.clearFirestore();
  });

  it('allows the real client path: enqueue addressed at the paired co-parent', async () => {
    await seed(env, PAIRED_USERS);
    const db = env.authenticatedContext(ALICE).firestore();
    await assertSucceeds(db.collection('notification_queue').add(queueDoc(BOB)));
  });

  it('allows an enqueue addressed at the caller themselves', async () => {
    await seed(env, PAIRED_USERS);
    const db = env.authenticatedContext(ALICE).firestore();
    await assertSucceeds(db.collection('notification_queue').add(queueDoc(ALICE)));
  });

  it('denies an enqueue addressed at an unrelated user (the phishing primitive)', async () => {
    await seed(env, Object.assign({
      'users/carol-uid': {name: 'Carol', email: 'c@x.test', partnerId: ''},
    }, PAIRED_USERS));
    const db = env.authenticatedContext(ALICE).firestore();
    await assertFails(db.collection('notification_queue').add(queueDoc(CAROL)));
  });

  it('denies an enqueue with no targetUserId', async () => {
    await seed(env, PAIRED_USERS);
    const db = env.authenticatedContext(ALICE).firestore();
    await assertFails(db.collection('notification_queue').add({
      data: {title: 'x', body: 'y'}, status: 'pending', createdAt: 1,
    }));
  });

  it('denies an unauthenticated enqueue', async () => {
    const db = env.unauthenticatedContext().firestore();
    await assertFails(db.collection('notification_queue').add(queueDoc(BOB)));
  });

  it('denies reads, updates and deletes even for the addressee', async () => {
    await seed(env, Object.assign({
      'notification_queue/queued-1': queueDoc(BOB),
    }, PAIRED_USERS));
    const db = env.authenticatedContext(BOB).firestore();
    await assertFails(db.doc('notification_queue/queued-1').get());
    await assertFails(db.doc('notification_queue/queued-1').update({status: 'sent'}));
    await assertFails(db.doc('notification_queue/queued-1').delete());
  });

  it('denies the ex-partner path once the link is cleared on the target side', async () => {
    await seed(env, {
      'users/alice-uid': {name: 'Alice', email: 'a@x.test', partnerId: BOB},
      'users/bob-uid': {name: 'Bob', email: 'b@x.test', partnerId: ''},
    });
    const db = env.authenticatedContext(ALICE).firestore();
    await assertFails(db.collection('notification_queue').add(queueDoc(BOB)));
  });
});
