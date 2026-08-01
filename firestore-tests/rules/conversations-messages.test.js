/**
 * Part 1d — the `conversations` and `messages` blocks (chat).
 *
 * Shapes come from `MessageRepositoryImpl`. Conversation membership is immutable after
 * creation, and a message may only ever have its `isRead` flag flipped by a participant.
 */

const {
  CURRENT_RULES, testEnv, seed, assertSucceeds, assertFails,
} = require('../harness');

const PROJECT = 'demo-coplanly-chat';
const ALICE = 'alice-uid';
const BOB = 'bob-uid';
const CAROL = 'carol-uid';

const PAIRED_USERS = {
  'users/alice-uid': {name: 'Alice', email: 'a@x.test', partnerId: BOB},
  'users/bob-uid': {name: 'Bob', email: 'b@x.test', partnerId: ALICE},
  'users/carol-uid': {name: 'Carol', email: 'c@x.test', partnerId: ''},
};

/**
 * Builds a conversation document as `MessageRepositoryImpl.createConversation` writes it.
 *
 * @param {!Object} overrides Fields to override on the default document.
 * @return {!Object} The document data.
 */
function conversationDoc(overrides) {
  return Object.assign({
    id: 'conv-1',
    participants: [ALICE, BOB],
    title: 'Co-parent chat',
    unreadCount: 0,
    createdAt: '2026-08-01T10:00:00',
  }, overrides);
}

/**
 * Builds a message document as `MessageRepositoryImpl.sendMessage` writes it.
 *
 * @param {!Object} overrides Fields to override on the default document.
 * @return {!Object} The document data.
 */
function messageDoc(overrides) {
  return Object.assign({
    id: 'msg-1',
    conversationId: 'conv-1',
    senderId: ALICE,
    senderName: 'Alice',
    content: 'Pickup at six?',
    timestamp: '2026-08-01T10:05:00',
    messageType: 'TEXT',
    attachments: [],
    isRead: false,
    replyToMessageId: '',
  }, overrides);
}

describe('Part 1d: conversations', () => {
  let env;

  before(async () => {
    env = await testEnv(PROJECT, CURRENT_RULES);
  });

  beforeEach(async () => {
    await env.clearFirestore();
    await seed(env, PAIRED_USERS);
  });

  it('lets a paired parent create the 1:1 thread', async () => {
    const db = env.authenticatedContext(ALICE).firestore();
    await assertSucceeds(db.doc('conversations/conv-1').set(conversationDoc({})));
  });

  it('denies creating a thread the caller is not part of', async () => {
    const db = env.authenticatedContext(CAROL).firestore();
    await assertFails(db.doc('conversations/conv-1').set(conversationDoc({})));
  });

  it('denies creating a thread with an unpaired counterpart', async () => {
    const db = env.authenticatedContext(ALICE).firestore();
    await assertFails(db.doc('conversations/conv-1')
        .set(conversationDoc({participants: [ALICE, CAROL]})));
  });

  it('denies a group thread of three', async () => {
    const db = env.authenticatedContext(ALICE).firestore();
    await assertFails(db.doc('conversations/conv-1')
        .set(conversationDoc({participants: [ALICE, BOB, CAROL]})));
  });

  it('lets participants read, and denies outsiders', async () => {
    await seed(env, {'conversations/conv-1': conversationDoc({})});
    await assertSucceeds(
        env.authenticatedContext(BOB).firestore().doc('conversations/conv-1').get());
    await assertFails(
        env.authenticatedContext(CAROL).firestore().doc('conversations/conv-1').get());
  });

  it('serves the participants query the conversation list runs', async () => {
    await seed(env, {'conversations/conv-1': conversationDoc({})});
    const db = env.authenticatedContext(BOB).firestore();
    await assertSucceeds(db.collection('conversations')
        .where('participants', 'array-contains', BOB).get());
  });

  it('lets a participant update the thread metadata', async () => {
    await seed(env, {'conversations/conv-1': conversationDoc({})});
    const db = env.authenticatedContext(BOB).firestore();
    await assertSucceeds(db.doc('conversations/conv-1').update({unreadCount: 3}));
  });

  it('denies changing the participant list (membership is immutable)', async () => {
    await seed(env, {'conversations/conv-1': conversationDoc({})});
    const db = env.authenticatedContext(ALICE).firestore();
    await assertFails(db.doc('conversations/conv-1').update({participants: [ALICE, CAROL]}));
  });

  it('denies adding a third participant', async () => {
    await seed(env, {'conversations/conv-1': conversationDoc({})});
    const db = env.authenticatedContext(ALICE).firestore();
    await assertFails(
        db.doc('conversations/conv-1').update({participants: [ALICE, BOB, CAROL]}));
  });

  it('denies reordering the participant list', async () => {
    // The rule compares the list by value, so order counts. Documented here so a
    // future client that rebuilds the list in a different order is not a surprise.
    await seed(env, {'conversations/conv-1': conversationDoc({})});
    const db = env.authenticatedContext(ALICE).firestore();
    await assertFails(db.doc('conversations/conv-1').update({participants: [BOB, ALICE]}));
  });

  it('denies deletes outright', async () => {
    await seed(env, {'conversations/conv-1': conversationDoc({})});
    await assertFails(
        env.authenticatedContext(ALICE).firestore().doc('conversations/conv-1').delete());
  });
});

describe('Part 1d: messages', () => {
  let env;

  before(async () => {
    env = await testEnv(PROJECT, CURRENT_RULES);
  });

  beforeEach(async () => {
    await env.clearFirestore();
    await seed(env, Object.assign({
      'conversations/conv-1': conversationDoc({}),
    }, PAIRED_USERS));
  });

  it('lets a participant send as themselves', async () => {
    const db = env.authenticatedContext(ALICE).firestore();
    await assertSucceeds(db.doc('messages/msg-1').set(messageDoc({})));
  });

  it('denies sending under somebody else name', async () => {
    const db = env.authenticatedContext(BOB).firestore();
    await assertFails(db.doc('messages/msg-1').set(messageDoc({})));
  });

  it('denies an outsider sending into the thread', async () => {
    const db = env.authenticatedContext(CAROL).firestore();
    await assertFails(db.doc('messages/msg-1').set(messageDoc({senderId: CAROL})));
  });

  it('denies sending into a conversation that does not exist', async () => {
    const db = env.authenticatedContext(ALICE).firestore();
    await assertFails(db.doc('messages/msg-1').set(messageDoc({conversationId: 'nope'})));
  });

  it('lets participants read, and denies outsiders', async () => {
    await seed(env, {'messages/msg-1': messageDoc({})});
    await assertSucceeds(
        env.authenticatedContext(BOB).firestore().doc('messages/msg-1').get());
    await assertFails(
        env.authenticatedContext(CAROL).firestore().doc('messages/msg-1').get());
  });

  it('serves the conversationId query the chat screen runs', async () => {
    await seed(env, {'messages/msg-1': messageDoc({})});
    const db = env.authenticatedContext(BOB).firestore();
    await assertSucceeds(
        db.collection('messages').where('conversationId', '==', 'conv-1').get());
  });

  describe('update is restricted to the isRead flag', () => {
    beforeEach(async () => {
      await seed(env, {'messages/msg-1': messageDoc({})});
    });

    it('lets the recipient flip isRead', async () => {
      const db = env.authenticatedContext(BOB).firestore();
      await assertSucceeds(db.doc('messages/msg-1').update({isRead: true}));
    });

    it('lets the sender flip isRead too', async () => {
      const db = env.authenticatedContext(ALICE).firestore();
      await assertSucceeds(db.doc('messages/msg-1').update({isRead: true}));
    });

    it('denies editing the content', async () => {
      const db = env.authenticatedContext(ALICE).firestore();
      await assertFails(db.doc('messages/msg-1').update({content: 'rewritten'}));
    });

    it('denies smuggling a content edit alongside isRead', async () => {
      const db = env.authenticatedContext(BOB).firestore();
      await assertFails(
          db.doc('messages/msg-1').update({isRead: true, content: 'rewritten'}));
    });

    it('denies reassigning the sender', async () => {
      const db = env.authenticatedContext(BOB).firestore();
      await assertFails(db.doc('messages/msg-1').update({senderId: BOB}));
    });

    it('denies moving a message into another conversation', async () => {
      const db = env.authenticatedContext(ALICE).firestore();
      await assertFails(db.doc('messages/msg-1').update({conversationId: 'conv-2'}));
    });

    it('denies an outsider flipping isRead', async () => {
      const db = env.authenticatedContext(CAROL).firestore();
      await assertFails(db.doc('messages/msg-1').update({isRead: true}));
    });
  });

  it('denies deletes outright', async () => {
    await seed(env, {'messages/msg-1': messageDoc({})});
    await assertFails(
        env.authenticatedContext(ALICE).firestore().doc('messages/msg-1').delete());
  });
});
