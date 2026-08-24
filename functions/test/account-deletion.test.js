const assert = require('assert');

/**
 * Erasing an account.
 *
 * `deleteAccountDataImpl` is the half of Play's in-app-deletion requirement and GDPR Art. 17
 * that the client cannot perform: it removes documents the signing-out user cannot read, let
 * alone write. These cases pin the two decisions that are easy to get wrong and impossible to
 * notice afterwards — **what is deleted versus merely narrowed**, and **the order**.
 *
 * The fake below is fuller than the one in `unpair.test.js` because this path uses more of
 * Firestore: equality and array-contains queries, per-document deletes, and the transaction
 * `unpairCoParentImpl` runs. It stores documents by collection and applies writes to them, so
 * assertions are about the resulting state rather than about which calls were made.
 *
 * @param {!Object<string, !Array<!Object>>} collections Seed documents, keyed by collection.
 * @return {!Object} A Firestore-shaped fake exposing its documents as `_store`.
 */
function fakeDb(collections) {
  const store = {};
  Object.keys(collections).forEach((name) => {
    store[name] = collections[name].map((doc) => Object.assign({}, doc));
  });

  const docsOf = (name) => (store[name] = store[name] || []);

  const matches = (doc, field, op, value) => {
    const actual = doc[field];
    if (op === '==') return actual === value;
    if (op === 'array-contains') return Array.isArray(actual) && actual.includes(value);
    throw new Error(`fakeDb: unsupported operator ${op}`);
  };

  const wrap = (name, doc) => ({
    id: doc.id,
    exists: true,
    data: () => doc,
    ref: {_collection: name, _id: doc.id},
  });

  const applyUpdate = (name, id, update) => {
    const doc = docsOf(name).find((d) => d.id === id);
    if (!doc) return;
    Object.keys(update).forEach((key) => {
      const value = update[key];
      // Real `FieldValue` sentinels, applied rather than stored. They arrive as
      // `ArrayRemoveTransform` / `DeleteTransform` instances from firebase-admin; storing one
      // verbatim would replace the array with an opaque object and quietly pass a test that
      // asserts only "the field changed".
      const transform = value && value.constructor && value.constructor.name;
      if (transform === 'ArrayRemoveTransform') {
        doc[key] = (doc[key] || []).filter((v) => !value.elements.includes(v));
      } else if (transform === 'ArrayUnionTransform') {
        doc[key] = (doc[key] || []).concat(
            value.elements.filter((v) => !(doc[key] || []).includes(v)));
      } else if (transform === 'DeleteTransform') {
        delete doc[key];
      } else {
        doc[key] = value;
      }
    });
  };

  const removeDoc = (name, id) => {
    store[name] = docsOf(name).filter((d) => d.id !== id);
  };

  const collection = (name) => ({
    where(field, op, value) {
      const build = (predicates) => ({
        where(f2, o2, v2) {
          return build(predicates.concat([[f2, o2, v2]]));
        },
        async get() {
          const found = docsOf(name).filter((doc) =>
            predicates.every(([f, o, v]) => matches(doc, f, o, v)));
          return {docs: found.map((doc) => wrap(name, doc)), size: found.length};
        },
      });
      return build([[field, op, value]]);
    },
    doc(id) {
      return {
        _collection: name,
        _id: id,
        async get() {
          const doc = docsOf(name).find((d) => d.id === id);
          return doc ?
            wrap(name, doc) :
            {id, exists: false, data: () => undefined, ref: {_collection: name, _id: id}};
        },
        async update(update) {
          applyUpdate(name, id, update);
        },
        async delete() {
          removeDoc(name, id);
        },
      };
    },
    async add(doc) {
      docsOf(name).push(Object.assign({id: `gen-${docsOf(name).length}`}, doc));
    },
  });

  return {
    _store: store,
    collection,
    batch() {
      const ops = [];
      return {
        delete(ref) {
          ops.push({kind: 'delete', ref});
        },
        update(ref, update) {
          ops.push({kind: 'update', ref, update});
        },
        async commit() {
          ops.forEach((op) => {
            if (op.kind === 'delete') removeDoc(op.ref._collection, op.ref._id);
            else applyUpdate(op.ref._collection, op.ref._id, op.update);
          });
          ops.length = 0;
        },
      };
    },
    async runTransaction(fn) {
      return fn({
        async get(ref) {
          return collection(ref._collection).doc(ref._id).get();
        },
        update(ref, update) {
          applyUpdate(ref._collection, ref._id, update);
        },
        set(ref, value) {
          removeDoc(ref._collection, ref._id);
          docsOf(ref._collection).push(Object.assign({id: ref._id}, value));
        },
        delete(ref) {
          removeDoc(ref._collection, ref._id);
        },
      });
    },
  };
}

const ALICE = 'alice';
const BOB = 'bob';

/**
 * A paired family with one document of every shape the erasure has to reason about.
 *
 * @return {!Object<string, !Array<!Object>>} Seed documents for [fakeDb].
 */
function family() {
  return {
    users: [
      {id: ALICE, name: 'Alice', partnerId: BOB},
      {id: BOB, name: 'Bob', partnerId: ALICE},
    ],
    events: [
      {id: 'ev-alice', createdByFirebaseUid: ALICE, sharedWith: [ALICE, BOB], title: 'Pickup'},
      {id: 'ev-bob', createdByFirebaseUid: BOB, sharedWith: [ALICE, BOB], title: 'Dentist'},
    ],
    child_info: [
      {id: 'ch-1', createdByFirebaseUid: ALICE, sharedWith: [ALICE, BOB], childName: 'Ema'},
    ],
    pets: [],
    expenses: [{id: 'ex-1', createdByFirebaseUid: ALICE, amount: 100}],
    budgets: [{id: 'bu-1', createdByFirebaseUid: ALICE, category: 'school'}],
    change_requests: [
      {id: 'cr-1', requestedBy: ALICE, requestedTo: BOB},
      {id: 'cr-2', requestedBy: BOB, requestedTo: ALICE},
    ],
    conversations: [{id: `${ALICE}__${BOB}`, participants: [ALICE, BOB]}],
    messages: [
      {id: 'm-1', conversationId: `${ALICE}__${BOB}`, senderId: ALICE, content: 'hi'},
      {id: 'm-2', conversationId: `${ALICE}__${BOB}`, senderId: BOB, content: 'hello'},
    ],
    custody_models: [{id: `${ALICE}__${BOB}`, participants: [ALICE, BOB]}],
    calendar_friends: [],
    friend_profiles: [],
    invitations: [{id: 'inv-1', fromUserId: ALICE, status: 'pending'}],
    notification_queue: [{id: 'n-1', targetUserId: ALICE}],
  };
}

describe('deleteAccountDataImpl', () => {
  let myFunctions;

  before(() => {
    myFunctions = require('../index');
  });

  it('removes the account profile itself', async () => {
    const db = fakeDb(family());
    await myFunctions.deleteAccountDataImpl(db, ALICE);
    assert.deepStrictEqual(db._store.users.map((u) => u.id), [BOB]);
  });

  // The decision this whole function turns on: what the departing user *authored* goes, what
  // the co-parent authored stays. Getting this backwards either fails the erasure request or
  // deletes the other parent's records.
  it('deletes documents the user authored and keeps the co-parent\'s', async () => {
    const db = fakeDb(family());
    await myFunctions.deleteAccountDataImpl(db, ALICE);

    assert.deepStrictEqual(db._store.events.map((e) => e.id), ['ev-bob']);
    assert.deepStrictEqual(db._store.child_info, []);
    assert.deepStrictEqual(db._store.expenses, []);
    assert.deepStrictEqual(db._store.budgets, []);
  });

  // The other half: a surviving document must not still name the deleted account in its
  // audience, or the co-parent's screen keeps listing somebody who no longer exists.
  it('scrubs the uid from the audience of documents it did not author', async () => {
    const db = fakeDb(family());
    await myFunctions.deleteAccountDataImpl(db, ALICE);

    const survivor = db._store.events.find((e) => e.id === 'ev-bob');
    assert.ok(!survivor.sharedWith.includes(ALICE), 'ex-account still in sharedWith');
    assert.ok(survivor.sharedWith.includes(BOB), 'the author lost their own audience');
  });

  it('deletes the conversation and every message in it', async () => {
    const db = fakeDb(family());
    await myFunctions.deleteAccountDataImpl(db, ALICE);

    assert.deepStrictEqual(db._store.conversations, []);
    assert.deepStrictEqual(db._store.messages, [], 'half a thread was left behind');
  });

  it('deletes change requests in both directions', async () => {
    const db = fakeDb(family());
    await myFunctions.deleteAccountDataImpl(db, ALICE);
    assert.deepStrictEqual(db._store.change_requests, []);
  });

  it('deletes the shared custody schedule and the account\'s invitations', async () => {
    const db = fakeDb(family());
    await myFunctions.deleteAccountDataImpl(db, ALICE);

    assert.deepStrictEqual(db._store.custody_models, []);
    assert.deepStrictEqual(db._store.invitations, []);
  });

  // Queued pushes *addressed to* the deleted account go — they would otherwise be delivered
  // to whatever device still holds its token. The one unpair queues *for the co-parent* is a
  // different thing and must survive: it is how Bob learns the link ended, and it is the last
  // message this account will ever cause.
  it('drops pushes addressed to the account, keeps the one telling the co-parent', async () => {
    const db = fakeDb(family());
    await myFunctions.deleteAccountDataImpl(db, ALICE);

    const remaining = db._store.notification_queue;
    assert.ok(!remaining.some((n) => n.targetUserId === ALICE),
        'a push addressed to the deleted account survived');
    assert.deepStrictEqual(remaining.map((n) => n.targetUserId), [BOB]);
  });

  // Unpairing first is what lets the co-parent's own client notice the link ended, while both
  // accounts still exist. Running it afterwards would clear a partnerId pointing at a user
  // document that had already gone.
  it('tears the co-parent link down and reports who it unpaired', async () => {
    const db = fakeDb(family());
    const result = await myFunctions.deleteAccountDataImpl(db, ALICE);

    assert.strictEqual(result.unpairedFrom, BOB);
    assert.strictEqual(db._store.users.find((u) => u.id === BOB).partnerId, '');
  });

  // An erasure request must not be blocked by the state of somebody's pairing.
  it('erases an account that was never paired', async () => {
    const solo = family();
    solo.users = [{id: ALICE, name: 'Alice', partnerId: ''}];
    solo.conversations = [];
    solo.messages = [];
    const db = fakeDb(solo);

    const result = await myFunctions.deleteAccountDataImpl(db, ALICE);

    assert.strictEqual(result.unpairedFrom, null);
    assert.deepStrictEqual(db._store.users, []);
    assert.deepStrictEqual(db._store.events.map((e) => e.id), ['ev-bob']);
  });

  it('removes the friend grant in both directions', async () => {
    const withFriend = family();
    withFriend.calendar_friends = [
      {id: 'granny', familyParents: [ALICE, BOB], expiresAtMillis: 4102444800000},
      {id: ALICE, familyParents: ['other-a', 'other-b'], expiresAtMillis: 4102444800000},
    ];
    withFriend.friend_profiles = [{id: ALICE, name: 'Alice', familyParents: ['other-a', 'other-b']}];
    const db = fakeDb(withFriend);

    await myFunctions.deleteAccountDataImpl(db, ALICE);

    assert.deepStrictEqual(db._store.calendar_friends, [],
        'a grant naming the deleted family, or held by it, survived');
    assert.deepStrictEqual(db._store.friend_profiles, []);
  });
});
