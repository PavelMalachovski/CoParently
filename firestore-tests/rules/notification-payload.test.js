/**
 * SEC-3 — a client cannot write what a notification says.
 *
 * A queued notification used to carry `title` and `body` composed by the *sending* device, and
 * `sendNotification` relayed them for the other phone to render verbatim, with the app's own
 * icon, on a lock screen. Nothing between the two devices decided what a notification was
 * allowed to say: the bounds that used to be here capped its length, which stops a wall of text
 * and nothing else.
 *
 * A payload now carries a **type** and the few names that type needs, and the receiving device
 * writes the sentence from its own string resources. These tests pin the two halves that make
 * that hold rather than merely be the convention:
 *
 * 1. a client may not write `title` or `body` at all, so it cannot go back to composing;
 * 2. a client may not claim one of the three types only a Cloud Function produces, so it cannot
 *    announce a pairing that did not happen or a chat message under a name that is not its own.
 *
 * Cloud Functions are unaffected by any of this — they write with admin credentials, which
 * bypass rules. That is what makes an allow-list safe here.
 */

const {
  CURRENT_RULES, testEnv, seed, assertSucceeds, assertFails,
} = require('../harness');

const PROJECT = 'demo-coplanly-push-payload';
const ALICE = 'alice-uid';
const BOB = 'bob-uid';
const CAROL = 'carol-uid';

/**
 * A queue document as `FcmService.queueNotificationForUser` writes it.
 *
 * @param {!Object} data The `data` payload.
 * @param {string=} target Recipient uid; defaults to Bob.
 * @return {!Object} The document.
 */
function queued(data, target) {
  return {
    targetUserId: target || BOB,
    data: data,
    createdAt: 1787000000000,
    status: 'pending',
  };
}

describe('SEC-3: notification payloads', () => {
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

  describe('what a client may say', () => {
    it('accepts a typed payload with the names it needs', async () => {
      const db = env.authenticatedContext(ALICE).firestore();
      await assertSucceeds(db.collection('notification_queue').add(queued({
        type: 'event_created',
        eventId: 'event-1',
        subject: 'Swimming lesson',
        actorName: 'Alice',
      })));
    });

    it('refuses a payload carrying a title', async () => {
      // The whole point. A title is a sentence somebody else's phone would render as if the
      // app had written it.
      const db = env.authenticatedContext(ALICE).firestore();
      await assertFails(db.collection('notification_queue').add(queued({
        type: 'event_created',
        title: 'Security alert: verify your account',
      })));
    });

    it('refuses a payload carrying a body', async () => {
      const db = env.authenticatedContext(ALICE).firestore();
      await assertFails(db.collection('notification_queue').add(queued({
        type: 'event_created',
        body: 'Tap here to confirm',
      })));
    });

    it('refuses an empty title just as firmly as a long one', async () => {
      // A length bound would have let this through — the old rule accepted 0..200. The key
      // being present at all is what is refused now.
      const db = env.authenticatedContext(ALICE).firestore();
      await assertFails(db.collection('notification_queue').add(queued({
        type: 'event_created',
        title: '',
      })));
    });

    it('still bounds the names that do ride along', async () => {
      const db = env.authenticatedContext(ALICE).firestore();
      await assertFails(db.collection('notification_queue').add(queued({
        type: 'event_created',
        subject: 'x'.repeat(201),
      })));
    });

    it('bounds the actor name too', async () => {
      const db = env.authenticatedContext(ALICE).firestore();
      await assertFails(db.collection('notification_queue').add(queued({
        type: 'event_created',
        actorName: 'x'.repeat(101),
      })));
    });
  });

  describe('what a client may claim to be', () => {
    it('refuses a forged pairing notification', async () => {
      // Only `acceptPairingInvitation` produces this, and it writes as admin.
      const db = env.authenticatedContext(ALICE).firestore();
      await assertFails(db.collection('notification_queue').add(queued({
        type: 'pairing_accepted',
        actorName: 'Alice',
      })));
    });

    it('refuses a forged unpair notification', async () => {
      const db = env.authenticatedContext(ALICE).firestore();
      await assertFails(db.collection('notification_queue').add(queued({
        type: 'pairing_removed',
        actorName: 'Alice',
      })));
    });

    it('refuses a forged chat message', async () => {
      // The one type whose text the app still relays rather than composes — which is exactly
      // why a client must not be able to produce it.
      const db = env.authenticatedContext(ALICE).firestore();
      await assertFails(db.collection('notification_queue').add(queued({
        type: 'chat_message',
        conversationId: 'c1',
        actorName: 'CoPlanly Support',
        preview: 'Confirm your password',
      })));
    });

    it('refuses a type nobody defines', async () => {
      const db = env.authenticatedContext(ALICE).firestore();
      await assertFails(db.collection('notification_queue').add(queued({
        type: 'account_suspended',
      })));
    });

    it('refuses a payload with no type at all', async () => {
      const db = env.authenticatedContext(ALICE).firestore();
      await assertFails(db.collection('notification_queue').add(queued({
        eventId: 'event-1',
      })));
    });

    it('accepts each of the other client types', async () => {
      const db = env.authenticatedContext(ALICE).firestore();
      const types = [
        'event_updated', 'event_deleted', 'child_info_updated',
        'change_request_created', 'change_request_accepted',
        'change_request_declined', 'change_request_cancelled',
        'custody_proposal_proposed', 'custody_proposal_accepted',
        'custody_proposal_declined',
        'day_swap_offered', 'day_swap_accepted', 'day_swap_declined',
      ];
      for (const type of types) {
        await assertSucceeds(
            db.collection('notification_queue').add(queued({type: type})));
      }
    });
  });

  describe('who a client may notify', () => {
    it('still refuses a stranger', async () => {
      // Unchanged by SEC-3, and re-pinned here because the rule was rewritten around it.
      const db = env.authenticatedContext(CAROL).firestore();
      await assertFails(db.collection('notification_queue').add(queued({
        type: 'event_created',
      })));
    });

    it('still lets a user queue one for themselves', async () => {
      const db = env.authenticatedContext(CAROL).firestore();
      await assertSucceeds(db.collection('notification_queue').add(queued({
        type: 'event_created',
      }, CAROL)));
    });
  });
});
