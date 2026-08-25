const test = require('firebase-functions-test')();
const assert = require('assert');

/**
 * Fake Firestore serving one filtered query per collection and recording batched deletes.
 *
 * The fake applies the `where('deletedAtMillis', '<=', cutoff)` filter itself rather than
 * handing back everything, because that filter is the safety property under test: a live
 * document has no `deletedAtMillis` at all, and Firestore does not return a document missing
 * the field a range query is on. A fake that ignored the filter would let a sweep that deleted
 * indiscriminately pass.
 *
 * @param {!Object<string, !Array<!Object>>} collections Documents per collection name.
 * @return {!Object} A fake with `_deleted` and `_commits` recorders.
 */
function fakeDb(collections) {
  const deleted = [];
  const commits = [];

  return {
    _deleted: deleted,
    _commits: commits,
    collection(name) {
      const docs = collections[name] || [];
      return {
        where(field, op, value) {
          assert.strictEqual(field, 'deletedAtMillis');
          assert.strictEqual(op, '<=');
          return {
            async get() {
              return {
                docs: docs
                    .filter((doc) => typeof doc.deletedAtMillis === 'number' &&
                        doc.deletedAtMillis <= value)
                    .map((doc) => ({
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
        delete(ref) {
          ops.push({id: ref.id, collection: ref.collection});
        },
        async commit() {
          ops.forEach((op) => deleted.push(op));
          commits.push(ops.length);
        },
      };
    },
  };
}

const NOW = Date.parse('2026-08-24T12:00:00Z');
const DAY = 24 * 60 * 60 * 1000;

/**
 * A tombstone deleted a given number of days before [NOW].
 *
 * @param {string} id Document id.
 * @param {number} daysAgo How long ago it was deleted.
 * @return {!Object} The document.
 */
function tombstonedDaysAgo(id, daysAgo) {
  return {id, deletedAtMillis: NOW - daysAgo * DAY, deletedBy: 'alice-uid'};
}

describe('sweepDeletedDocuments', () => {
  let index;

  before(() => {
    index = require('../index');
  });

  after(() => {
    test.cleanup();
  });

  it('removes a tombstone older than the retention window', async () => {
    const db = fakeDb({events: [tombstonedDaysAgo('e-old', 100)]});

    const removed = await index.sweepDeletedDocumentsImpl(db, NOW);

    assert.strictEqual(removed, 1);
    assert.deepStrictEqual(db._deleted, [{id: 'e-old', collection: 'events'}]);
  });

  it('keeps a tombstone the co-parent could still be coming back for', async () => {
    // The asymmetry the window exists for: sweeping this one leaves the cancelled event on a
    // returning parent's calendar with nothing left to correct it — CQ-3, reintroduced by its
    // own cleanup.
    const db = fakeDb({events: [tombstonedDaysAgo('e-recent', 3)]});

    const removed = await index.sweepDeletedDocumentsImpl(db, NOW);

    assert.strictEqual(removed, 0);
    assert.deepStrictEqual(db._deleted, []);
  });

  it('never touches a live document', async () => {
    // A live document carries no `deletedAtMillis`, and a range query on a field a document
    // does not have returns nothing. This is what makes an unattended scheduled delete safe.
    const db = fakeDb({
      events: [
        {id: 'e-alive', title: 'Swimming lesson', startDateTime: '2020-01-01T00:00:00'},
        tombstonedDaysAgo('e-old', 200),
      ],
    });

    await index.sweepDeletedDocumentsImpl(db, NOW);

    assert.deepStrictEqual(db._deleted, [{id: 'e-old', collection: 'events'}]);
  });

  it('sweeps expenses as well as events', async () => {
    const db = fakeDb({
      events: [tombstonedDaysAgo('e-old', 120)],
      expenses: [tombstonedDaysAgo('x-old', 120)],
    });

    const removed = await index.sweepDeletedDocumentsImpl(db, NOW);

    assert.strictEqual(removed, 2);
    assert.deepStrictEqual(db._deleted, [
      {id: 'e-old', collection: 'events'},
      {id: 'x-old', collection: 'expenses'},
    ]);
  });

  it('sweeps the child and pet tombstones CQ-19 added', async () => {
    const db = fakeDb({
      child_info: [tombstonedDaysAgo('c-old', 500)],
      pets: [tombstonedDaysAgo('p-old', 500)],
    });

    const removed = await index.sweepDeletedDocumentsImpl(db, NOW);

    assert.strictEqual(removed, 2);
    assert.deepStrictEqual(db._deleted, [
      {id: 'c-old', collection: 'child_info'},
      {id: 'p-old', collection: 'pets'},
    ]);
  });

  it('leaves collections that are not tombstoned alone', async () => {
    // `budgets` and `change_requests` delete by other means or not at all. A sweep that widened
    // to every collection would be a scheduled job that removes documents no client ever marked.
    // The two halves have to be added together: a collection listed with no client writing
    // tombstones sweeps nothing, and a client writing them into an unlisted collection keeps
    // them for ever.
    const db = fakeDb({budgets: [tombstonedDaysAgo('b-old', 500)]});

    const removed = await index.sweepDeletedDocumentsImpl(db, NOW);

    assert.strictEqual(removed, 0);
    assert.deepStrictEqual(db._deleted, []);
  });

  it('treats the cutoff itself as due, not as still waiting', async () => {
    const db = fakeDb({
      events: [tombstonedDaysAgo('e-exact', index.TOMBSTONE_RETENTION_DAYS)],
    });

    const removed = await index.sweepDeletedDocumentsImpl(db, NOW);

    assert.strictEqual(removed, 1);
  });

  it('honours an explicit retention window', async () => {
    const db = fakeDb({events: [tombstonedDaysAgo('e-week', 8)]});

    assert.strictEqual(await index.sweepDeletedDocumentsImpl(db, NOW, 7), 1);
  });

  it('commits in batches rather than one write per document', async () => {
    // Firestore caps a batch at 500 operations. A sweep that ignored that would throw on the
    // first busy family and delete nothing at all.
    const many = [];
    for (let i = 0; i < 401; i++) {
      many.push(tombstonedDaysAgo(`e-${i}`, 200));
    }
    const db = fakeDb({events: many});

    const removed = await index.sweepDeletedDocumentsImpl(db, NOW);

    assert.strictEqual(removed, 401);
    assert.deepStrictEqual(db._commits, [400, 1]);
  });

  it('does nothing, and commits nothing, when there is nothing to sweep', async () => {
    const db = fakeDb({});

    assert.strictEqual(await index.sweepDeletedDocumentsImpl(db, NOW), 0);
    assert.deepStrictEqual(db._commits, []);
  });
});
