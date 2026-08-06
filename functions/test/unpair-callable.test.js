const test = require('firebase-functions-test')();
const assert = require('assert');
const admin = require('firebase-admin');

/**
 * Tests for `unpairCoParentImpl` — the body of the `unpairCoParent` callable, which takes
 * its Firestore handle as a parameter so the transaction, the `pendingRevocationOf`
 * bookkeeping and the notification ordering can be exercised offline.
 *
 * Three properties are pinned here, all of which the pre-fix code got wrong:
 *
 * 1. `pendingRevocationOf` accumulates. It used to be overwritten with the current
 *    `partnerId`, so a failed sweep followed by a re-pair and a second unpair discarded the
 *    first ex-partner's marker — and with `partnerId` long since cleared, nothing anywhere
 *    remembered whose access still had to be revoked.
 * 2. The `pairing_removed` notification is queued before the sweep. Queued after it, it was
 *    lost on exactly the path that needs it: the sweep threw, and on the retry
 *    `unpairedFrom` was already null, so neither attempt told the ex-partner anything.
 * 3. Each marker entry is cleared as its own sweep finishes, so a later failure cannot undo
 *    the bookkeeping for the ones that already completed.
 */

/**
 * Interprets a real `FieldValue` sentinel against a stored value.
 *
 * @param {*} current The value currently held by the field.
 * @param {!Object} sentinel The sentinel written by the code under test.
 * @return {{drop: boolean, value: *}} Whether to delete the key, else its new value.
 */
function applySentinel(current, sentinel) {
  const kind = sentinel.constructor.name;
  if (kind === 'DeleteTransform') {
    return {drop: true, value: undefined};
  }
  if (kind === 'ArrayRemoveTransform') {
    return {
      drop: false,
      value: (current || []).filter((entry) => !sentinel.elements.includes(entry)),
    };
  }
  if (kind === 'ServerTimestampTransform') {
    return {drop: false, value: '2026-08-02T00:00:00Z'};
  }
  throw new Error(`unhandled FieldValue sentinel: ${kind}`);
}

/**
 * Minimal in-memory Firestore covering the subset `unpairCoParentImpl` uses: transactions
 * over `users`, `add` on `notification_queue`, `array-contains` queries and batched updates.
 *
 * @param {!Object<string, !Object<string, !Object>>} seed Documents keyed by collection
 *     then document id.
 * @param {{failSweepFor: ?string}=} options Set `failSweepFor` to make the sweep throw as
 *     soon as it queries on behalf of that uid, modelling a partial failure.
 * @return {!Object} The fake, carrying `_docs` and `_added` for assertions.
 */
function fakeDb(seed, options) {
  const opts = options || {};
  const docs = JSON.parse(JSON.stringify(seed));
  const added = [];

  /**
   * Applies one update map to a stored document.
   *
   * @param {string} collection Collection name.
   * @param {string} id Document id.
   * @param {!Object} update Field map, possibly carrying FieldValue sentinels.
   */
  function applyUpdate(collection, id, update) {
    docs[collection] = docs[collection] || {};
    const current = docs[collection][id] || {};
    for (const [key, value] of Object.entries(update)) {
      if (value instanceof admin.firestore.FieldValue) {
        const outcome = applySentinel(current[key], value);
        if (outcome.drop) {
          delete current[key];
        } else {
          current[key] = outcome.value;
        }
      } else {
        current[key] = value;
      }
    }
    docs[collection][id] = current;
  }

  /**
   * Builds a document reference.
   *
   * @param {string} collection Collection name.
   * @param {string} id Document id.
   * @return {!Object} The reference.
   */
  function docRef(collection, id) {
    return {
      id,
      collection,
      async get() {
        const data = (docs[collection] || {})[id];
        return {exists: data !== undefined, data: () => data};
      },
      async update(update) {
        applyUpdate(collection, id, update);
      },
      async delete() {
        if (docs[collection]) {
          delete docs[collection][id];
        }
      },
    };
  }

  /**
   * Whether a stored field value matches a query clause.
   *
   * @param {*} fieldValue The document's value for the queried field.
   * @param {string} op One of `'array-contains'` or `'=='` — the two this fake supports.
   * @param {*} value The value the clause compares against.
   * @return {boolean} Whether the document matches.
   */
  function matchesClause(fieldValue, op, value) {
    if (op === 'array-contains') {
      return Array.isArray(fieldValue) && fieldValue.includes(value);
    }
    if (op === '==') {
      return fieldValue === value;
    }
    throw new Error(`fakeDb: unsupported query operator ${op}`);
  }

  return {
    _docs: docs,
    _added: added,
    collection(name) {
      return {
        doc: (id) => docRef(name, id),
        async add(data) {
          added.push({collection: name, data});
          return {id: `generated-${added.length}`};
        },
        where(field, op, value) {
          return {
            async get() {
              if (opts.failSweepFor && opts.failSweepFor === value) {
                throw new Error('simulated sweep failure');
              }
              const matched = Object.values(docs[name] || {})
                  .filter((doc) => matchesClause(doc[field], op, value));
              return {
                docs: matched.map((doc) => ({
                  id: doc.id,
                  data: () => doc,
                  ref: docRef(name, doc.id),
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
          ops.push({ref, update});
        },
        async commit() {
          ops.forEach((op) => applyUpdate(op.ref.collection, op.ref.id, op.update));
        },
      };
    },
    async runTransaction(fn) {
      const staged = [];
      const result = await fn({
        get: (ref) => ref.get(),
        update: (ref, update) => staged.push({type: 'update', ref, update}),
        delete: (ref) => staged.push({type: 'delete', ref}),
      });
      staged.forEach((op) => {
        if (op.type === 'delete') {
          if (docs[op.ref.collection]) {
            delete docs[op.ref.collection][op.ref.id];
          }
        } else {
          applyUpdate(op.ref.collection, op.ref.id, op.update);
        }
      });
      return result;
    },
  };
}

/**
 * Seeds two mutually paired users plus one event Alice created and shared with Bob.
 *
 * @param {!Object=} aliceOverrides Extra fields merged onto Alice's user document.
 * @return {!Object} The seed map.
 */
function pairedSeed(aliceOverrides) {
  return {
    users: {
      alice: Object.assign(
          {id: 'alice', name: 'Alice', partnerId: 'bob'}, aliceOverrides || {}),
      bob: {id: 'bob', name: 'Bob', partnerId: 'alice'},
    },
    events: {
      e1: {id: 'e1', createdByFirebaseUid: 'alice', sharedWith: ['alice', 'bob']},
    },
    child_info: {},
  };
}

/**
 * Counts the `pairing_removed` notifications a fake recorded.
 *
 * @param {!Object} db The fake.
 * @return {!Array<!Object>} The queued documents.
 */
function queuedNotifications(db) {
  return db._added.filter((entry) => entry.collection === 'notification_queue');
}

describe('unpairCoParentImpl', () => {
  let unpairCoParentImpl;

  before(() => {
    unpairCoParentImpl = require('../index').unpairCoParentImpl;
  });

  after(() => {
    test.cleanup();
  });

  describe('the pendingRevocationOf marker', () => {
    it('records the ex-partner as a list, not a bare string', async () => {
      const db = fakeDb(pairedSeed(), {failSweepFor: 'alice'});

      await assert.rejects(() => unpairCoParentImpl(db, 'alice'),
          (err) => err.code === 'internal');

      assert.deepStrictEqual(db._docs.users.alice.pendingRevocationOf, ['bob']);
    });

    it('accumulates a second ex-partner instead of clobbering the first', async () => {
      // Sweep failed against Bob, Alice re-paired with Carol, now unpairs again. Losing
      // Bob's marker leaves his access on every document the first sweep missed, with
      // nothing left anywhere that remembers who he was.
      const seed = pairedSeed({partnerId: 'carol', pendingRevocationOf: ['bob']});
      seed.users.carol = {id: 'carol', name: 'Carol', partnerId: 'alice'};
      const db = fakeDb(seed, {failSweepFor: 'alice'});

      await assert.rejects(() => unpairCoParentImpl(db, 'alice'));

      assert.deepStrictEqual(
          db._docs.users.alice.pendingRevocationOf, ['bob', 'carol']);
    });

    it('reads the legacy single-string marker and carries it forward', async () => {
      // Live `users` documents written by the deployed version hold a bare UID string.
      const seed = pairedSeed({partnerId: 'carol', pendingRevocationOf: 'bob'});
      seed.users.carol = {id: 'carol', name: 'Carol', partnerId: 'alice'};
      const db = fakeDb(seed, {failSweepFor: 'alice'});

      await assert.rejects(() => unpairCoParentImpl(db, 'alice'));

      assert.deepStrictEqual(
          db._docs.users.alice.pendingRevocationOf, ['bob', 'carol']);
    });

    it('does not duplicate a marker that already names the current partner', async () => {
      const db = fakeDb(
          pairedSeed({pendingRevocationOf: ['bob']}), {failSweepFor: 'alice'});

      await assert.rejects(() => unpairCoParentImpl(db, 'alice'));

      assert.deepStrictEqual(db._docs.users.alice.pendingRevocationOf, ['bob']);
    });

    it('clears the marker once every pending sweep finishes', async () => {
      const seed = pairedSeed({partnerId: 'carol', pendingRevocationOf: ['bob']});
      seed.users.carol = {id: 'carol', name: 'Carol', partnerId: 'alice'};
      const db = fakeDb(seed);

      await unpairCoParentImpl(db, 'alice');

      assert.ok(!('pendingRevocationOf' in db._docs.users.alice),
          'the marker should be gone once nothing is pending');
      assert.deepStrictEqual(db._docs.events.e1.sharedWith, ['alice']);
    });

    it('resumes an unfinished sweep on a later call with no partner left', async () => {
      const db = fakeDb(pairedSeed({partnerId: '', pendingRevocationOf: ['bob']}));

      const result = await unpairCoParentImpl(db, 'alice');

      assert.strictEqual(result.unpairedFrom, null);
      assert.strictEqual(result.revokedDocuments, 1);
      assert.deepStrictEqual(db._docs.events.e1.sharedWith, ['alice']);
      assert.ok(!('pendingRevocationOf' in db._docs.users.alice));
    });

    it('keeps the entries a failing sweep never reached', async () => {
      // Carol's sweep succeeds, Bob's is the one that fails: Bob must survive in the
      // marker, Carol must not.
      const seed = pairedSeed({partnerId: 'carol', pendingRevocationOf: ['carol']});
      seed.users.carol = {id: 'carol', name: 'Carol', partnerId: 'alice'};
      seed.users.alice.partnerId = 'bob';
      seed.users.bob.partnerId = 'alice';
      const db = fakeDb(seed, {failSweepFor: 'bob'});

      await assert.rejects(() => unpairCoParentImpl(db, 'alice'));

      assert.deepStrictEqual(db._docs.users.alice.pendingRevocationOf, ['bob']);
    });

    it('writes nothing when there is neither a partner nor a marker', async () => {
      const db = fakeDb(pairedSeed({partnerId: ''}));

      const result = await unpairCoParentImpl(db, 'alice');

      assert.deepStrictEqual(
          result, {unpairedFrom: null, revokedDocuments: 0});
      assert.deepStrictEqual(db._docs.events.e1.sharedWith, ['alice', 'bob']);
    });
  });

  describe('the pairing_removed notification', () => {
    it('is queued even when the sweep behind it then fails', async () => {
      const db = fakeDb(pairedSeed(), {failSweepFor: 'alice'});

      await assert.rejects(() => unpairCoParentImpl(db, 'alice'),
          (err) => err.code === 'internal');

      const queued = queuedNotifications(db);
      assert.strictEqual(queued.length, 1);
      assert.strictEqual(queued[0].data.targetUserId, 'bob');
      assert.strictEqual(queued[0].data.data.type, 'pairing_removed');
    });

    it('is not sent twice when the retry resumes the sweep', async () => {
      // Exactly once across both attempts: the retry sees partnerId already cleared, so
      // `unpairedFrom` is null and the notification block is skipped.
      const first = fakeDb(pairedSeed(), {failSweepFor: 'alice'});
      await assert.rejects(() => unpairCoParentImpl(first, 'alice'));
      assert.strictEqual(queuedNotifications(first).length, 1);

      const retry = fakeDb(first._docs);
      const result = await unpairCoParentImpl(retry, 'alice');

      assert.strictEqual(result.unpairedFrom, null);
      assert.strictEqual(queuedNotifications(retry).length, 0);
      assert.deepStrictEqual(retry._docs.events.e1.sharedWith, ['alice']);
      assert.ok(!('pendingRevocationOf' in retry._docs.users.alice));
    });

    it('is queued exactly once on the happy path', async () => {
      const db = fakeDb(pairedSeed());

      const result = await unpairCoParentImpl(db, 'alice');

      assert.strictEqual(result.unpairedFrom, 'bob');
      assert.strictEqual(queuedNotifications(db).length, 1);
      assert.strictEqual(db._docs.users.alice.partnerId, '');
      assert.strictEqual(db._docs.users.bob.partnerId, '');
      assert.deepStrictEqual(db._docs.events.e1.sharedWith, ['alice']);
    });

    it('is skipped when the link was already half-torn, but the sweep still runs',
        async () => {
          const seed = pairedSeed();
          seed.users.bob.partnerId = 'carol';
          const db = fakeDb(seed);

          const result = await unpairCoParentImpl(db, 'alice');

          assert.strictEqual(result.unpairedFrom, null);
          assert.strictEqual(queuedNotifications(db).length, 0);
          assert.deepStrictEqual(db._docs.events.e1.sharedWith, ['alice']);
        });
  });

  describe('the shared custody_models document', () => {
    const CUSTODY_KEY = ['alice', 'bob'].sort().join('__');

    /**
     * Seeds a pair with a shared custody document alongside the usual pairing fixture.
     *
     * @return {!Object} The seed map.
     */
    function seedWithCustodyModel() {
      const seed = pairedSeed();
      seed.custody_models = {
        [CUSTODY_KEY]: {
          participants: ['alice', 'bob'].sort(),
          modelType: 'WEEK_ON_WEEK_OFF',
        },
      };
      return seed;
    }

    it('deletes the shared custody document when unpairing', async () => {
      const db = fakeDb(seedWithCustodyModel());

      await unpairCoParentImpl(db, 'alice');

      assert.ok(!(CUSTODY_KEY in (db._docs.custody_models || {})),
          'the shared custody document should be gone');
    });

    it('leaves the co-parent local copies alone when unpairing', async () => {
      // The custody_models document is the one *shared* Firestore document a pair has;
      // each parent's own Room copy never leaves the device and this call has no way to
      // touch it. What this test pins, at the Firestore layer this fake models, is that
      // the deletion is scoped to that one shared document — neither parent's own `users`
      // document is deleted, only detached from the other (partnerId cleared).
      const db = fakeDb(seedWithCustodyModel());

      await unpairCoParentImpl(db, 'alice');

      assert.ok(db._docs.users.alice, 'alice\'s own document must still exist');
      assert.ok(db._docs.users.bob, 'bob\'s own document must still exist');
      assert.strictEqual(db._docs.users.alice.partnerId, '');
      assert.strictEqual(db._docs.users.bob.partnerId, '');
    });

    it('is also deleted when the link was already half-torn', async () => {
      // Bob re-paired with Carol before Alice pressed unpair. The document at Alice/Bob's
      // old key is stale either way — the rule's live-pairing gate will never admit
      // anyone to it again — so it goes even though this branch does not clear Bob's side.
      const seed = seedWithCustodyModel();
      seed.users.bob.partnerId = 'carol';
      const db = fakeDb(seed);

      await unpairCoParentImpl(db, 'alice');

      assert.ok(!(CUSTODY_KEY in (db._docs.custody_models || {})));
    });
  });
});

describe('pendingRevocations', () => {
  let pendingRevocations;

  before(() => {
    pendingRevocations = require('../index').pendingRevocations;
  });

  it('accepts the legacy single-string shape', () => {
    assert.deepStrictEqual(pendingRevocations('bob'), ['bob']);
  });

  it('accepts the list shape', () => {
    assert.deepStrictEqual(pendingRevocations(['bob', 'carol']), ['bob', 'carol']);
  });

  it('treats an absent or malformed marker as nothing pending', () => {
    assert.deepStrictEqual(pendingRevocations(undefined), []);
    assert.deepStrictEqual(pendingRevocations(null), []);
    assert.deepStrictEqual(pendingRevocations(''), []);
    assert.deepStrictEqual(pendingRevocations(42), []);
    assert.deepStrictEqual(pendingRevocations([null, 'bob', 7]), ['bob']);
  });
});

/**
 * Seeds one accepted invitation from `alice` to `bob`, plus both user documents, with a
 * given slot ("role") for each. Both default to `'mom'`, matching every pair created before
 * pairing started assigning distinct slots.
 *
 * @param {{aliceRole: (string|undefined), bobRole: (string|undefined),
 *   alicePartnerId: (string|undefined), bobPartnerId: (string|undefined)}=} overrides
 *   Per-user overrides.
 * @return {!Object} The seed map.
 */
function backfillSeed(overrides) {
  const o = overrides || {};
  return {
    invitations: {
      inv1: {
        id: 'inv1',
        status: 'accepted',
        fromUserId: 'alice',
        acceptedBy: 'bob',
      },
    },
    users: {
      alice: {
        id: 'alice',
        partnerId: 'alicePartnerId' in o ? o.alicePartnerId : 'bob',
        role: 'aliceRole' in o ? o.aliceRole : 'mom',
      },
      bob: {
        id: 'bob',
        partnerId: 'bobPartnerId' in o ? o.bobPartnerId : 'alice',
        role: 'bobRole' in o ? o.bobRole : 'mom',
      },
    },
  };
}

describe('backfillParentSlotsImpl', () => {
  let backfillParentSlotsImpl;

  before(() => {
    backfillParentSlotsImpl = require('../index').backfillParentSlotsImpl;
  });

  after(() => {
    test.cleanup();
  });

  it('gives the accepter the other slot, reading acceptedBy from the invitation', async () => {
    const db = fakeDb(backfillSeed());

    const summary = await backfillParentSlotsImpl(db);

    assert.strictEqual(db._docs.users.alice.role, 'mom', 'the inviter keeps their slot');
    assert.strictEqual(db._docs.users.bob.role, 'dad', 'the accepter takes the other one');
    assert.deepStrictEqual(summary, {
      scanned: 1,
      updated: 1,
      skipped: 0,
      skippedReasons: {notPaired: 0, alreadySeparated: 0},
    });
  });

  it('leaves a pair with no surviving invitation alone', async () => {
    const seed = backfillSeed();
    seed.invitations = {};
    const db = fakeDb(seed);

    const summary = await backfillParentSlotsImpl(db);

    assert.strictEqual(db._docs.users.alice.role, 'mom');
    assert.strictEqual(db._docs.users.bob.role, 'mom');
    assert.strictEqual(summary.scanned, 0);
    assert.strictEqual(summary.updated, 0);
  });

  it('skips a pair whose parents already hold different slots', async () => {
    const db = fakeDb(backfillSeed({bobRole: 'dad'}));

    const summary = await backfillParentSlotsImpl(db);

    assert.strictEqual(db._docs.users.alice.role, 'mom');
    assert.strictEqual(db._docs.users.bob.role, 'dad');
    assert.strictEqual(summary.updated, 0);
    assert.strictEqual(summary.skipped, 1);
    assert.strictEqual(summary.skippedReasons.alreadySeparated, 1);
  });

  it('running it twice is indistinguishable from running it once', async () => {
    const db = fakeDb(backfillSeed());

    await backfillParentSlotsImpl(db);
    const second = await backfillParentSlotsImpl(db);

    assert.strictEqual(db._docs.users.alice.role, 'mom');
    assert.strictEqual(db._docs.users.bob.role, 'dad');
    assert.deepStrictEqual(second, {
      scanned: 1,
      updated: 0,
      skipped: 1,
      skippedReasons: {notPaired: 0, alreadySeparated: 1},
    });
  });

  it('skips an invitation whose pair has since unpaired', async () => {
    const db = fakeDb(backfillSeed({alicePartnerId: '', bobPartnerId: ''}));

    const summary = await backfillParentSlotsImpl(db);

    assert.strictEqual(db._docs.users.alice.role, 'mom');
    assert.strictEqual(db._docs.users.bob.role, 'mom');
    assert.strictEqual(summary.updated, 0);
    assert.strictEqual(summary.skipped, 1);
    assert.strictEqual(summary.skippedReasons.notPaired, 1);
  });

  it('skips an invitation whose accepter has since re-paired with someone else', async () => {
    const db = fakeDb(backfillSeed({bobPartnerId: 'carol'}));

    const summary = await backfillParentSlotsImpl(db);

    assert.strictEqual(db._docs.users.alice.role, 'mom');
    assert.strictEqual(db._docs.users.bob.role, 'mom');
    assert.strictEqual(summary.skippedReasons.notPaired, 1);
  });

  it('does not read an invitation with no recorded accepter', async () => {
    // Never accepted, or accepted before `acceptedBy` existed. Either way it must not be
    // counted as scanned, let alone acted on.
    const seed = backfillSeed();
    seed.invitations.inv1.acceptedBy = null;
    const db = fakeDb(seed);

    const summary = await backfillParentSlotsImpl(db);

    assert.strictEqual(db._docs.users.alice.role, 'mom');
    assert.strictEqual(db._docs.users.bob.role, 'mom');
    assert.strictEqual(summary.scanned, 0);
  });

  it('ignores an invitation that is still pending', async () => {
    const seed = backfillSeed();
    seed.invitations.inv1.status = 'pending';
    const db = fakeDb(seed);

    const summary = await backfillParentSlotsImpl(db);

    assert.strictEqual(db._docs.users.bob.role, 'mom');
    assert.strictEqual(summary.scanned, 0);
  });
});

describe('backfillParentSlots operator gate', () => {
  const ORIGINAL_ADMIN_UIDS = process.env.BACKFILL_ADMIN_UIDS;
  let myFunctions;

  before(() => {
    myFunctions = require('../index');
  });

  afterEach(() => {
    if (ORIGINAL_ADMIN_UIDS === undefined) {
      delete process.env.BACKFILL_ADMIN_UIDS;
    } else {
      process.env.BACKFILL_ADMIN_UIDS = ORIGINAL_ADMIN_UIDS;
    }
  });

  after(() => {
    test.cleanup();
  });

  it('refuses a caller who is not an operator', async () => {
    process.env.BACKFILL_ADMIN_UIDS = 'op-uid, other-op';
    const wrapped = test.wrap(myFunctions.backfillParentSlots);

    await assert.rejects(
        () => wrapped({}, {auth: {uid: 'someone-else'}}),
        (err) => err.code === 'permission-denied');
    await assert.rejects(
        () => wrapped({}, {}),
        (err) => err.code === 'permission-denied');
  });

  it('reads a comma-separated allow-list and trims whitespace around each uid', () => {
    process.env.BACKFILL_ADMIN_UIDS = ' op-uid , other-op ,,';
    assert.deepStrictEqual(myFunctions.backfillAdminUids(), ['op-uid', 'other-op']);
  });

  it('is empty when no allow-list is configured', () => {
    delete process.env.BACKFILL_ADMIN_UIDS;
    assert.deepStrictEqual(myFunctions.backfillAdminUids(), []);
  });
});
