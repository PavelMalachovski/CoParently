const test = require('firebase-functions-test')();
const assert = require('assert');
const sinon = require('sinon');

/**
 * Builds a fake Firestore that serves `array-contains` queries from in-memory documents
 * and records every batched update.
 *
 * The `sharedWith` writes go out as `FieldValue.arrayRemove` sentinels, whose value is not
 * introspectable, so the assertions are on *which* documents were narrowed and how the
 * writes were batched — the part `revokeSharedAudience` is responsible for. What
 * `arrayRemove` then does to the stored array is Firestore's contract, and the effect of
 * a narrowed audience on access is covered by
 * firestore-tests/rules/unpair-revocation.test.js.
 *
 * @param {!Object<string, !Array<!Object>>} collections Documents keyed by collection name.
 * @return {!Object} A fake with `_updates`, `_commits` and `_queries` recorders.
 */
function fakeDb(collections) {
  const updates = [];
  const commits = [];
  const queries = [];

  return {
    _updates: updates,
    _commits: commits,
    _queries: queries,
    collection(name) {
      return {
        where(field, op, value) {
          queries.push({collection: name, field, op, value});
          return {
            async get() {
              const matched = (collections[name] || []).filter((doc) =>
                (doc.sharedWith || []).includes(value));
              return {
                docs: matched.map((doc) => ({
                  id: doc.id,
                  data: () => doc,
                  ref: {id: doc.id, collection: name},
                })),
              };
            },
          };
        },
      };
    },
    batch() {
      const ops = [];
      return {
        update(ref, update) {
          ops.push({collection: ref.collection, id: ref.id, update});
        },
        async commit() {
          ops.forEach((op) => updates.push(op));
          commits.push(ops.length);
        },
      };
    },
  };
}

describe('revokeSharedAudience', () => {
  let revokeSharedAudience;

  before(() => {
    revokeSharedAudience = require('../index').revokeSharedAudience;
  });

  after(() => {
    test.cleanup();
    sinon.restore();
  });

  it('narrows both directions, so revocation is symmetric', async () => {
    const db = fakeDb({
      events: [
        {id: 'e1', createdByFirebaseUid: 'a', sharedWith: ['a', 'b']},
        {id: 'e2', createdByFirebaseUid: 'b', sharedWith: ['b', 'a']},
      ],
      child_info: [],
    });

    const revoked = await revokeSharedAudience(db, 'a', 'b');

    assert.strictEqual(revoked, 2);
    assert.deepStrictEqual(db._updates.map((u) => u.id).sort(), ['e1', 'e2']);
  });

  it('never removes a uid from a document it created', async () => {
    // sharedWith is what the down-sync queries on, so dropping the creator would hide
    // the document from the parent it belongs to.
    const db = fakeDb({
      events: [{id: 'e1', createdByFirebaseUid: 'b', sharedWith: ['a', 'b']}],
      child_info: [],
    });

    await revokeSharedAudience(db, 'a', 'b');

    const removedFromCreator = db._updates.filter((u) => u.id === 'e1').length;
    assert.strictEqual(removedFromCreator, 1, 'only the non-creator should be removed');
  });

  it('leaves documents that never listed the other parent alone', async () => {
    const db = fakeDb({
      events: [
        {id: 'own', createdByFirebaseUid: 'a', sharedWith: ['a']},
        {id: 'foreign', createdByFirebaseUid: 'c', sharedWith: ['c']},
      ],
      child_info: [],
    });

    const revoked = await revokeSharedAudience(db, 'a', 'b');

    assert.strictEqual(revoked, 0);
    assert.deepStrictEqual(db._updates, []);
  });

  it('sweeps child_info as well as events', async () => {
    const db = fakeDb({
      events: [],
      child_info: [{id: 'c1', createdByFirebaseUid: 'a', sharedWith: ['a', 'b']}],
    });

    const revoked = await revokeSharedAudience(db, 'a', 'b');

    assert.strictEqual(revoked, 1);
    assert.strictEqual(db._updates[0].collection, 'child_info');
  });

  it('does not touch expenses or budgets, which gate on the live partnerId', async () => {
    const db = fakeDb({
      events: [], child_info: [],
      expenses: [{id: 'x1', createdByFirebaseUid: 'a', sharedWith: ['a', 'b']}],
      budgets: [{id: 'b1', createdByFirebaseUid: 'a', sharedWith: ['a', 'b']}],
    });

    await revokeSharedAudience(db, 'a', 'b');

    const swept = db._queries.map((q) => q.collection);
    assert.ok(!swept.includes('expenses'), 'expenses must not be swept');
    assert.ok(!swept.includes('budgets'), 'budgets must not be swept');
  });

  it('queries by array-contains on sharedWith in both directions per collection', async () => {
    const db = fakeDb({events: [], child_info: []});

    await revokeSharedAudience(db, 'a', 'b');

    assert.strictEqual(db._queries.length, 4);
    db._queries.forEach((q) => {
      assert.strictEqual(q.field, 'sharedWith');
      assert.strictEqual(q.op, 'array-contains');
    });
    assert.deepStrictEqual(db._queries.map((q) => q.value), ['a', 'b', 'a', 'b']);
  });

  it('splits large sweeps into batches below the 500-operation write cap', async () => {
    const events = [];
    for (let i = 0; i < 950; i++) {
      events.push({id: `e${i}`, createdByFirebaseUid: 'a', sharedWith: ['a', 'b']});
    }
    const db = fakeDb({events, child_info: []});

    const revoked = await revokeSharedAudience(db, 'a', 'b');

    assert.strictEqual(revoked, 950);
    db._commits.forEach((size) => {
      assert.ok(size <= 400, `a batch carried ${size} operations`);
    });
    assert.ok(db._commits.length >= 3, 'expected the sweep to span several batches');
  });

  it('commits nothing when a query returns no documents', async () => {
    const db = fakeDb({events: [], child_info: []});

    await revokeSharedAudience(db, 'a', 'b');

    assert.deepStrictEqual(db._commits, []);
  });
});
