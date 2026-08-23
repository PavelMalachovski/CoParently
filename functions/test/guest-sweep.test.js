const test = require('firebase-functions-test')();
const assert = require('assert');
const sinon = require('sinon');

/**
 * Fake Firestore serving a whole-collection `get()` and recording batched updates.
 *
 * `sharedWith` goes out as an `arrayRemove` sentinel whose value is not introspectable, so
 * the assertions are on the `guests` map — which the sweep writes whole — and on *which*
 * documents were touched at all. What `arrayRemove` does to the stored array is Firestore's
 * contract; that a narrowed audience actually revokes access is covered by
 * firestore-tests/rules/child-info.test.js.
 *
 * @param {!Array<!Object>} childInfo Documents in the `child_info` collection.
 * @return {!Object} A fake with `_updates` and `_commits` recorders.
 */
function fakeDb(childInfo) {
  const updates = [];
  const commits = [];

  return {
    _updates: updates,
    _commits: commits,
    collection(name) {
      return {
        async get() {
          const docs = name === 'child_info' ? childInfo : [];
          return {
            docs: docs.map((doc) => ({
              id: doc.id,
              data: () => doc,
              ref: {id: doc.id, collection: name},
            })),
          };
        },
      };
    },
    batch() {
      const ops = [];
      return {
        update(ref, update) {
          ops.push({id: ref.id, update});
        },
        async commit() {
          ops.forEach((op) => updates.push(op));
          commits.push(ops.length);
        },
      };
    },
  };
}

const NOW = Date.parse('2026-08-23T12:00:00Z');
const ACTIVE = Date.parse('2026-09-30T00:00:00Z');
const ENDED = Date.parse('2026-08-01T00:00:00Z');

/**
 * A grant.
 *
 * @param {number|undefined} expiresAtMillis When it ends.
 * @return {!Object} The stored grant.
 */
function grant(expiresAtMillis) {
  return {
    name: 'Nina',
    grantedBy: 'alice',
    grantedAtMillis: Date.parse('2026-07-01T00:00:00Z'),
    expiresAtMillis,
  };
}

describe('sweepExpiredGuests', () => {
  let myFunctions;

  before(() => {
    myFunctions = require('../index');
  });

  after(() => {
    test.cleanup();
    sinon.restore();
  });

  it('removes a grant that has ended, and keeps the ones that have not', async () => {
    const db = fakeDb([{
      id: 'child1',
      createdByFirebaseUid: 'alice',
      sharedWith: ['alice', 'bob', 'nina', 'otto'],
      guests: {nina: grant(ENDED), otto: grant(ACTIVE)},
    }]);

    const removed = await myFunctions.sweepExpiredGuestsImpl(db, NOW);

    assert.strictEqual(removed, 1);
    assert.strictEqual(db._updates.length, 1);
    assert.deepStrictEqual(
        Object.keys(db._updates[0].update.guests), ['otto'],
        'the grant that has not ended must survive the sweep');
  });

  it('takes the swept uid out of the audience too', async () => {
    // The rule alone is not enough: it stops the read, but a uid left in `sharedWith` keeps
    // the document coming back from every audience query the guest issues.
    const db = fakeDb([{
      id: 'child1',
      createdByFirebaseUid: 'alice',
      sharedWith: ['alice', 'bob', 'nina'],
      guests: {nina: grant(ENDED)},
    }]);

    await myFunctions.sweepExpiredGuestsImpl(db, NOW);

    assert.ok(
        'sharedWith' in db._updates[0].update,
        'the sweep must narrow the audience, not just the map');
  });

  it('leaves a record whose guests are all still active untouched', async () => {
    const db = fakeDb([{
      id: 'child1',
      createdByFirebaseUid: 'alice',
      sharedWith: ['alice', 'nina'],
      guests: {nina: grant(ACTIVE)},
    }]);

    const removed = await myFunctions.sweepExpiredGuestsImpl(db, NOW);

    assert.strictEqual(removed, 0);
    assert.deepStrictEqual(db._updates, []);
    assert.deepStrictEqual(db._commits, [], 'nothing to write means nothing to commit');
  });

  it('skips a record with no guests at all', async () => {
    const db = fakeDb([
      {id: 'child1', createdByFirebaseUid: 'alice', sharedWith: ['alice', 'bob']},
      {id: 'child2', createdByFirebaseUid: 'alice', sharedWith: ['alice'], guests: {}},
    ]);

    assert.strictEqual(await myFunctions.sweepExpiredGuestsImpl(db, NOW), 0);
    assert.deepStrictEqual(db._updates, []);
  });

  it('ends a grant exactly at its expiry, matching the rule and the app', async () => {
    // All three implement the same strict comparison. If this one rounded the other way, a
    // grant would be live for the rules and swept by the sweep, or the reverse.
    const db = fakeDb([{
      id: 'child1',
      createdByFirebaseUid: 'alice',
      sharedWith: ['alice', 'nina'],
      guests: {nina: grant(NOW)},
    }]);

    assert.strictEqual(await myFunctions.sweepExpiredGuestsImpl(db, NOW), 1);
  });

  it('sweeps a grant with no expiry, rather than treating it as permanent', async () => {
    // The one default this feature must never have. A grant that reached the record without
    // an end — an older client, a partial write — is removed, not kept forever.
    const db = fakeDb([{
      id: 'child1',
      createdByFirebaseUid: 'alice',
      sharedWith: ['alice', 'nina', 'otto', 'pia'],
      guests: {nina: grant(undefined), otto: grant(0), pia: grant('2099-01-01')},
    }]);

    const removed = await myFunctions.sweepExpiredGuestsImpl(db, NOW);

    assert.strictEqual(removed, 3);
    assert.deepStrictEqual(Object.keys(db._updates[0].update.guests), []);
  });

  it('never removes the uid that created the record', async () => {
    // Belt to the callable's brace. A parent should never end up in `guests` at all, but if
    // one ever does, sweeping them out of `sharedWith` would hide the child from the parent
    // who entered them — the failure `revokeSharedAudience` guards against by the same rule.
    const db = fakeDb([{
      id: 'child1',
      createdByFirebaseUid: 'alice',
      sharedWith: ['alice', 'bob'],
      guests: {alice: grant(ENDED)},
    }]);

    const removed = await myFunctions.sweepExpiredGuestsImpl(db, NOW);

    assert.strictEqual(removed, 1, 'the stale grant itself is still cleaned off the map');
    assert.deepStrictEqual(Object.keys(db._updates[0].update.guests), []);
    assert.ok(
        !('sharedWith' in db._updates[0].update),
        'the creator must not be taken out of the audience of their own record');
  });

  it('sweeps across records, one update each', async () => {
    const db = fakeDb([
      {
        id: 'child1',
        createdByFirebaseUid: 'alice',
        sharedWith: ['alice', 'nina'],
        guests: {nina: grant(ENDED)},
      },
      {
        id: 'child2',
        createdByFirebaseUid: 'carol',
        sharedWith: ['carol', 'otto'],
        guests: {otto: grant(ENDED)},
      },
    ]);

    assert.strictEqual(await myFunctions.sweepExpiredGuestsImpl(db, NOW), 2);
    assert.deepStrictEqual(db._updates.map((u) => u.id), ['child1', 'child2']);
    assert.deepStrictEqual(db._commits, [2], 'one batch is enough for two records');
  });

  it('splits a large sweep below the 500-operation write cap', async () => {
    const many = [];
    for (let i = 0; i < 401; i++) {
      many.push({
        id: `child${i}`,
        createdByFirebaseUid: 'alice',
        sharedWith: ['alice', 'nina'],
        guests: {nina: grant(ENDED)},
      });
    }
    const db = fakeDb(many);

    await myFunctions.sweepExpiredGuestsImpl(db, NOW);

    assert.deepStrictEqual(db._commits, [400, 1]);
    db._commits.forEach((size) => assert.ok(size <= 400, 'a batch exceeded the cap'));
  });

  it('ignores a guests field that is not a map', async () => {
    const db = fakeDb([
      {id: 'child1', createdByFirebaseUid: 'alice', sharedWith: ['alice'], guests: 'nina'},
      {id: 'child2', createdByFirebaseUid: 'alice', sharedWith: ['alice'], guests: ['nina']},
    ]);

    assert.strictEqual(await myFunctions.sweepExpiredGuestsImpl(db, NOW), 0);
  });
});

describe('acceptGuestInvitation refuses somebody who already reads the record', () => {
  let myFunctions;

  before(() => {
    myFunctions = require('../index');
  });

  it('does not make the co-parent a guest of their own child', async () => {
    // Found while writing the sweep. A co-parent redeeming a guest code passed every check —
    // they are not the inviter, and the inviter is entitled — and landed in `guests` while
    // already being a parent in `sharedWith`. The grant then expires, and the sweep takes a
    // *parent* out of the audience of their own child's record.
    const db = {
      collection(name) {
        const doc = (id) => ({
          id,
          collection: name,
          async get() {
            const data = (db._docs[name] || {})[id];
            return {exists: data !== undefined, data: () => data, ref: doc(id)};
          },
        });
        return {doc};
      },
      _docs: {
        invitations: {
          inv1: {
            id: 'inv1',
            kind: 'guest',
            status: 'pending',
            fromUserId: 'alice',
            toEmail: '',
            childInfoId: 'child1',
            guestExpiresAt: ACTIVE,
          },
        },
        child_info: {
          child1: {
            id: 'child1',
            createdByFirebaseUid: 'alice',
            sharedWith: ['alice', 'bob'],
          },
        },
        users: {bob: {id: 'bob', name: 'Bob'}},
      },
      async runTransaction(fn) {
        return fn({get: (ref) => ref.get(), update: () => {}});
      },
    };

    await assert.rejects(
        () => myFunctions.acceptGuestInvitationImpl(db, 'bob', 'bob@example.com',
            {code: null, invitationId: 'inv1'}),
        (err) => err.details.reason === 'already-entitled');
  });
});
