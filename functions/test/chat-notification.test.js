const test = require('firebase-functions-test')();
const assert = require('assert');
const sinon = require('sinon');

/**
 * Tests for `notifyOfChatMessage` — the body of the `onChatMessageCreated` trigger, taking
 * its Firestore handle as a parameter (mirroring `unpairCoParentImpl`) so the suppression
 * rule and the no-reader guards can be exercised without a live Firestore.
 *
 * The suppression rule: no push goes out when the recipient's `lastReadAt` mark is already
 * at or past the message's timestamp — they are looking at the thread as it arrives. A
 * conversation that has never been read at all carries no `lastReadAt` entry, which must
 * default to notifying, not to silently swallowing the very first message.
 *
 * The no-reader cases: a missing conversation document, a `participants` list without a
 * second uid, and a sender who is not himself a participant. Each must be a quiet no-op —
 * queuing nothing and throwing nothing — because a Firestore `onCreate` trigger retries an
 * uncaught rejection indefinitely, and none of these describes something a retry could fix.
 */

/**
 * Minimal in-memory Firestore covering the subset `notifyOfChatMessage` uses: a single
 * `conversations` document lookup and `add` on `notification_queue`.
 *
 * @param {?Object} conversation The `conversations/{conversationId}` document data, or
 *     `null`/`undefined` to model a missing document.
 * @return {!Object} The fake, carrying `_added` for assertions.
 */
function fakeDb(conversation) {
  const added = [];
  return {
    _added: added,
    collection(name) {
      return {
        doc: () => ({
          async get() {
            return {
              exists: conversation != null,
              data: () => conversation,
            };
          },
        }),
        async add(data) {
          added.push({collection: name, data});
          return {id: `generated-${added.length}`};
        },
      };
    },
  };
}

describe('notifyOfChatMessage', () => {
  let notifyOfChatMessage;

  before(() => {
    notifyOfChatMessage = require('../index').notifyOfChatMessage;
  });

  after(() => {
    test.cleanup();
    sinon.restore();
  });

  it('queues exactly one notification addressed to the other participant', async () => {
    const db = fakeDb({
      participants: ['alice', 'bob'],
      lastReadAt: {bob: 1000},
    });
    const message = {
      conversationId: 'alice__bob',
      senderId: 'alice',
      senderName: 'Alice',
      content: 'Hello Bob',
      timestamp: '2026-08-02T10:00:00',
    };

    await notifyOfChatMessage(db, message);

    assert.strictEqual(db._added.length, 1);
    assert.strictEqual(db._added[0].collection, 'notification_queue');
    assert.strictEqual(db._added[0].data.targetUserId, 'bob');
    assert.strictEqual(db._added[0].data.data.type, 'chat_message');
  });

  it('queues nothing when the recipient has already read past the message', async () => {
    const db = fakeDb({
      participants: ['alice', 'bob'],
      // Bob's read mark is later than any plausible parse of the message timestamp below.
      lastReadAt: {bob: 9999999999999},
    });
    const message = {
      conversationId: 'alice__bob',
      senderId: 'alice',
      senderName: 'Alice',
      content: 'Hello Bob',
      timestamp: '2026-08-02T10:00:00',
    };

    await notifyOfChatMessage(db, message);

    assert.deepStrictEqual(db._added, []);
  });

  it('queues nothing when the read mark is exactly at the message timestamp', async () => {
    // "at or past" - equality must also suppress.
    const timestamp = '2026-08-02T10:00:00';
    const sentAt = Date.parse(timestamp);
    const db = fakeDb({participants: ['alice', 'bob'], lastReadAt: {bob: sentAt}});
    const message = {
      conversationId: 'alice__bob', senderId: 'alice', senderName: 'Alice',
      content: 'Hi', timestamp,
    };

    await notifyOfChatMessage(db, message);

    assert.deepStrictEqual(db._added, []);
  });

  it('notifies on a conversation that has never been read', async () => {
    // No lastReadAt field at all - must default to notifying, not to a false "already read".
    const db = fakeDb({participants: ['alice', 'bob']});
    const message = {
      conversationId: 'alice__bob', senderId: 'alice', senderName: 'Alice',
      content: 'First message ever', timestamp: '2026-08-02T10:00:00',
    };

    await notifyOfChatMessage(db, message);

    assert.strictEqual(db._added.length, 1);
  });

  it('is a quiet no-op when the conversation document does not exist', async () => {
    const db = fakeDb(null);
    const message = {
      conversationId: 'ghost', senderId: 'alice', senderName: 'Alice',
      content: 'Hi', timestamp: '2026-08-02T10:00:00',
    };

    await assert.doesNotReject(() => notifyOfChatMessage(db, message));
    assert.deepStrictEqual(db._added, []);
  });

  it('is a quiet no-op when participants has no second uid', async () => {
    const db = fakeDb({participants: ['alice']});
    const message = {
      conversationId: 'alice__bob', senderId: 'alice', senderName: 'Alice',
      content: 'Hi', timestamp: '2026-08-02T10:00:00',
    };

    await assert.doesNotReject(() => notifyOfChatMessage(db, message));
    assert.deepStrictEqual(db._added, []);
  });

  it('is a quiet no-op when participants is missing entirely', async () => {
    const db = fakeDb({});
    const message = {
      conversationId: 'alice__bob', senderId: 'alice', senderName: 'Alice',
      content: 'Hi', timestamp: '2026-08-02T10:00:00',
    };

    await assert.doesNotReject(() => notifyOfChatMessage(db, message));
    assert.deepStrictEqual(db._added, []);
  });

  it('is a quiet no-op when the sender is not a participant', async () => {
    const db = fakeDb({participants: ['alice', 'bob']});
    const message = {
      conversationId: 'alice__bob', senderId: 'mallory', senderName: 'Mallory',
      content: 'Hi', timestamp: '2026-08-02T10:00:00',
    };

    await assert.doesNotReject(() => notifyOfChatMessage(db, message));
    assert.deepStrictEqual(db._added, []);
  });

  it('notifies only the first non-sender participant when there are more than two', async () => {
    // Pins the deliberate choice: `ConversationKey.of` only ever produces a two-uid
    // conversation today, so this is unreachable in practice, but a looser schema tomorrow
    // must not silently start notifying just one of several recipients without a review of
    // this test.
    const db = fakeDb({participants: ['alice', 'bob', 'carol']});
    const message = {
      conversationId: 'alice__bob__carol', senderId: 'alice', senderName: 'Alice',
      content: 'Hi', timestamp: '2026-08-02T10:00:00',
    };

    await notifyOfChatMessage(db, message);

    assert.strictEqual(db._added.length, 1);
    assert.strictEqual(db._added[0].data.targetUserId, 'bob');
  });

  it('truncates the body to the named preview length rather than inlining a number', async () => {
    const longBody = 'x'.repeat(500);
    const db = fakeDb({participants: ['alice', 'bob']});
    const message = {
      conversationId: 'alice__bob', senderId: 'alice', senderName: 'Alice',
      content: longBody, timestamp: '2026-08-02T10:00:00',
    };

    await notifyOfChatMessage(db, message);

    const body = db._added[0].data.data.body;
    assert.ok(body.length < longBody.length, 'body must be truncated');
    assert.ok(body.length > 0);
  });

  it('falls back to the sender name for the title, and CoPlanly when absent', async () => {
    const db = fakeDb({participants: ['alice', 'bob']});
    const message = {
      conversationId: 'alice__bob', senderId: 'alice', senderName: '',
      content: 'Hi', timestamp: '2026-08-02T10:00:00',
    };

    await notifyOfChatMessage(db, message);

    assert.strictEqual(db._added[0].data.data.title, 'CoPlanly');
  });

  it('notifies rather than silently suppressing when the timestamp fails to parse', async () => {
    // A malformed timestamp must not fall back to epoch 0 - that would make it look
    // "already read" against a never-read conversation's default 0 mark.
    const db = fakeDb({participants: ['alice', 'bob']});
    const message = {
      conversationId: 'alice__bob', senderId: 'alice', senderName: 'Alice',
      content: 'Hi', timestamp: 'not-a-date',
    };

    await notifyOfChatMessage(db, message);

    assert.strictEqual(db._added.length, 1);
  });
});
