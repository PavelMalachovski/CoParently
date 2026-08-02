/**
 * Chat sync task 3 — the per-user read/delivery marks on `conversations`.
 *
 * `lastReadAt` and `lastDeliveredAt` are `{uid: epochMillis}` maps written one key at a
 * time (see MEMORY / task brief for the document shape from Task 2). Before this rule
 * change, the `conversations` update rule only protected `participants`, so either parent
 * could write the *other* participant's key in either map — marking their own messages as
 * read/delivered on the recipient's behalf. This suite locks that down while keeping every
 * other legitimate write (`lastMessageAt`, `archived`, `title`) working.
 */

const {
  CURRENT_RULES, testEnv, seed, assertSucceeds, assertFails,
} = require('../harness');

const PROJECT = 'demo-coplanly-chat-read-state';
const ALICE = 'alice-uid';
const BOB = 'bob-uid';
const CAROL = 'carol-uid';

const PAIRED_USERS = {
  'users/alice-uid': {name: 'Alice', email: 'a@x.test', partnerId: BOB},
  'users/bob-uid': {name: 'Bob', email: 'b@x.test', partnerId: ALICE},
  'users/carol-uid': {name: 'Carol', email: 'c@x.test', partnerId: ''},
};

/**
 * Builds a conversation document as `MessageRepositoryImpl.createConversation` writes it,
 * post-Task-2 (adds `lastReadAt`, `lastDeliveredAt`, `lastMessageAt`, `archived`).
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
    lastMessageAt: '2026-08-01T10:00:00',
    lastReadAt: {},
    lastDeliveredAt: {},
    archived: false,
  }, overrides);
}

describe('Part 1e: conversation read/delivery marks', () => {
  let env;

  before(async () => {
    env = await testEnv(PROJECT, CURRENT_RULES);
  });

  beforeEach(async () => {
    await env.clearFirestore();
    await seed(env, PAIRED_USERS);
  });

  describe('lastReadAt', () => {
    it('lets a participant write their own key', async () => {
      await seed(env, {'conversations/conv-1': conversationDoc({})});
      const db = env.authenticatedContext(ALICE).firestore();
      await assertSucceeds(
          db.doc('conversations/conv-1').update({'lastReadAt.alice-uid': 1722500000000}));
    });

    it('denies a participant writing the other participant key', async () => {
      await seed(env, {'conversations/conv-1': conversationDoc({})});
      const db = env.authenticatedContext(ALICE).firestore();
      await assertFails(
          db.doc('conversations/conv-1').update({'lastReadAt.bob-uid': 1722500000000}));
    });

    it('accepts the owner first mark when the field is entirely absent', async () => {
      // Every conversation created before Task 2 has no `lastReadAt` key at all. Reading a
      // missing key in Rules is an evaluation error, not null, so the helper must tolerate
      // this via get(key, {}) on both sides -- this is the state every existing conversation
      // is in right now.
      const withoutLastReadAt = conversationDoc({});
      delete withoutLastReadAt.lastReadAt;
      await seed(env, {'conversations/conv-1': withoutLastReadAt});
      const db = env.authenticatedContext(ALICE).firestore();
      await assertSucceeds(
          db.doc('conversations/conv-1').update({'lastReadAt.alice-uid': 1722500000000}));
    });
  });

  describe('lastDeliveredAt', () => {
    it('lets a participant write their own key', async () => {
      await seed(env, {'conversations/conv-1': conversationDoc({})});
      const db = env.authenticatedContext(BOB).firestore();
      await assertSucceeds(
          db.doc('conversations/conv-1').update({'lastDeliveredAt.bob-uid': 1722500000000}));
    });

    it('denies a participant writing the other participant key', async () => {
      await seed(env, {'conversations/conv-1': conversationDoc({})});
      const db = env.authenticatedContext(BOB).firestore();
      await assertFails(
          db.doc('conversations/conv-1').update({'lastDeliveredAt.alice-uid': 1722500000000}));
    });

    it('accepts the owner first mark when the field is entirely absent', async () => {
      const withoutLastDeliveredAt = conversationDoc({});
      delete withoutLastDeliveredAt.lastDeliveredAt;
      await seed(env, {'conversations/conv-1': withoutLastDeliveredAt});
      const db = env.authenticatedContext(BOB).firestore();
      await assertSucceeds(
          db.doc('conversations/conv-1').update({'lastDeliveredAt.bob-uid': 1722500000000}));
    });
  });

  it('still lets the sender bump lastMessageAt on every send', async () => {
    await seed(env, {'conversations/conv-1': conversationDoc({})});
    const db = env.authenticatedContext(ALICE).firestore();
    await assertSucceeds(
        db.doc('conversations/conv-1').update({lastMessageAt: '2026-08-01T11:00:00'}));
  });

  it('still lets a participant flip archived (the Task 5 merge writes this)', async () => {
    await seed(env, {'conversations/conv-1': conversationDoc({})});
    const db = env.authenticatedContext(BOB).firestore();
    await assertSucceeds(db.doc('conversations/conv-1').update({archived: true}));
  });

  it('still lets a participant rename the thread title', async () => {
    await seed(env, {'conversations/conv-1': conversationDoc({})});
    const db = env.authenticatedContext(ALICE).firestore();
    await assertSucceeds(db.doc('conversations/conv-1').update({title: 'New title'}));
  });

  it('still denies rewriting participants', async () => {
    await seed(env, {'conversations/conv-1': conversationDoc({})});
    const db = env.authenticatedContext(ALICE).firestore();
    await assertFails(
        db.doc('conversations/conv-1').update({participants: [ALICE, CAROL]}));
  });

  it('denies a non-participant from reading the conversation', async () => {
    await seed(env, {'conversations/conv-1': conversationDoc({})});
    const db = env.authenticatedContext(CAROL).firestore();
    await assertFails(db.doc('conversations/conv-1').get());
  });

  it('denies a non-participant from writing any mark', async () => {
    await seed(env, {'conversations/conv-1': conversationDoc({})});
    const db = env.authenticatedContext(CAROL).firestore();
    await assertFails(
        db.doc('conversations/conv-1').update({'lastReadAt.carol-uid': 1722500000000}));
  });
});
