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
      // `set(data, {merge: true})` behaves as an update; a bare `set` replaces the document,
      // which is the distinction `backfillFamilyDocuments` relies on not getting wrong.
      async set(data, options) {
        if (options && options.merge) {
          applyUpdate(collection, id, data);
        } else {
          docs[collection] = docs[collection] || {};
          docs[collection][id] = Object.assign({}, data);
        }
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
        doc: (id) => {
          // The real Admin SDK throws synchronously for a falsy document id (e.g. an
          // invitation missing `fromUserId`) rather than returning a reference to
          // nothing — mirrored here so a malformed document exercises the same
          // failure mode in tests that it would in production.
          if (!id) {
            throw new Error('Value for argument "documentPath" must be a non-empty string.');
          }
          return docRef(name, id);
        },
        async add(data) {
          added.push({collection: name, data});
          return {id: `generated-${added.length}`};
        },
        // An unfiltered scan of the whole collection — what `backfillFamilyDocuments` does
        // over `users`, the way `backfillParentSlots` scans `invitations` with a filter.
        async get() {
          const all = Object.values(docs[name] || {});
          return {
            docs: all.map((doc) => ({
              id: doc.id,
              data: () => doc,
              ref: docRef(name, doc.id),
            })),
          };
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
    // Deliberately the literal, not `['alice', 'bob'].sort().join('__')`: pinning the same
    // formula the production code uses would only ever prove a delete was issued at
    // whatever that formula happens to yield, not that it matches the id the client
    // actually derives (`CustodyKey.of` / `canonicalPairId` in firestore.rules). Each side
    // of that agreement is checked against a literal, not against a shared expression.
    const CUSTODY_KEY = 'alice__bob';

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

      assert.ok(!('alice__bob' in (db._docs.custody_models || {})),
          'the shared custody document should be gone');
    });

    it('deletes the family, because the family is the access', async () => {
      // Not tidiness. `families/{id}.members` is what the security rules read to decide who
      // may see the records a pair shares, so a family left behind is an ex-partner still
      // reading this household — the revocation sweep narrows documents, but a live
      // membership would let them all back in.
      const seed = seedWithCustodyModel();
      seed.families = {[CUSTODY_KEY]: {members: ['alice', 'bob'], createdAt: 1}};
      const db = fakeDb(seed);

      await unpairCoParentImpl(db, 'alice');

      assert.ok(!('alice__bob' in (db._docs.families || {})),
          'the family should be gone');
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

      assert.ok(!('alice__bob' in (db._docs.custody_models || {})));
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

/**
 * The zeroed-out shape of `backfillParentSlotsImpl`'s summary, with `scanned`, `updated`,
 * `skipped`, `failed` and/or individual `skippedReasons` overridden. Centralising the shape
 * means every test asserts the *whole* return value — including the reason counters it does
 * not expect to move — rather than only the fields a given case happens to care about.
 *
 * @param {!Object=} overrides Top-level fields, plus an optional `skippedReasons` map.
 * @return {!Object} The expected summary.
 */
function expectedSummary(overrides) {
  const o = overrides || {};
  return {
    scanned: 'scanned' in o ? o.scanned : 0,
    updated: 'updated' in o ? o.updated : 0,
    skipped: 'skipped' in o ? o.skipped : 0,
    failed: 'failed' in o ? o.failed : 0,
    skippedReasons: Object.assign(
        {noAccepter: 0, missingAccount: 0, notPaired: 0, alreadySeparated: 0},
        o.skippedReasons || {}),
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
    assert.deepStrictEqual(summary, expectedSummary({scanned: 1, updated: 1}));
  });

  it('leaves a pair with no surviving invitation alone', async () => {
    const seed = backfillSeed();
    seed.invitations = {};
    const db = fakeDb(seed);

    const summary = await backfillParentSlotsImpl(db);

    assert.strictEqual(db._docs.users.alice.role, 'mom');
    assert.strictEqual(db._docs.users.bob.role, 'mom');
    assert.deepStrictEqual(summary, expectedSummary({}));
  });

  it('skips a pair whose parents already hold different slots', async () => {
    const db = fakeDb(backfillSeed({bobRole: 'dad'}));

    const summary = await backfillParentSlotsImpl(db);

    assert.strictEqual(db._docs.users.alice.role, 'mom');
    assert.strictEqual(db._docs.users.bob.role, 'dad');
    assert.deepStrictEqual(summary,
        expectedSummary({scanned: 1, skipped: 1, skippedReasons: {alreadySeparated: 1}}));
  });

  it('does not misclassify a pair as already separated when a stale value merely differs ' +
      'textually from mom, rather than holding a distinct slot', async () => {
    // assignSlots normalizes anything but 'dad' to 'mom', so an empty string on one side and
    // 'mom' on the other are the *same* slot as far as assignSlots is concerned. A raw
    // string comparison would disagree and wrongly call this pair already repaired.
    const db = fakeDb(backfillSeed({bobRole: ''}));

    const summary = await backfillParentSlotsImpl(db);

    assert.strictEqual(db._docs.users.alice.role, 'mom');
    assert.strictEqual(db._docs.users.bob.role, 'dad');
    assert.deepStrictEqual(summary, expectedSummary({scanned: 1, updated: 1}));
  });

  it('running it twice is indistinguishable from running it once', async () => {
    const db = fakeDb(backfillSeed());

    await backfillParentSlotsImpl(db);
    const second = await backfillParentSlotsImpl(db);

    assert.strictEqual(db._docs.users.alice.role, 'mom');
    assert.strictEqual(db._docs.users.bob.role, 'dad');
    assert.deepStrictEqual(second,
        expectedSummary({scanned: 1, skipped: 1, skippedReasons: {alreadySeparated: 1}}));
  });

  it('skips an invitation whose pair has since unpaired', async () => {
    const db = fakeDb(backfillSeed({alicePartnerId: '', bobPartnerId: ''}));

    const summary = await backfillParentSlotsImpl(db);

    assert.strictEqual(db._docs.users.alice.role, 'mom');
    assert.strictEqual(db._docs.users.bob.role, 'mom');
    assert.deepStrictEqual(summary,
        expectedSummary({scanned: 1, skipped: 1, skippedReasons: {notPaired: 1}}));
  });

  it('skips an invitation whose accepter has since re-paired with someone else', async () => {
    const db = fakeDb(backfillSeed({bobPartnerId: 'carol'}));

    const summary = await backfillParentSlotsImpl(db);

    assert.strictEqual(db._docs.users.alice.role, 'mom');
    assert.strictEqual(db._docs.users.bob.role, 'mom');
    assert.deepStrictEqual(summary,
        expectedSummary({scanned: 1, skipped: 1, skippedReasons: {notPaired: 1}}));
  });

  it('reports a missing account separately from an ended pairing', async () => {
    // Bob's account was deleted outright, as opposed to the pairing having ended while
    // both accounts still exist (the previous two cases). An operator reading the report
    // afterwards cannot tell those apart unless they are counted separately.
    const seed = backfillSeed();
    delete seed.users.bob;
    const db = fakeDb(seed);

    const summary = await backfillParentSlotsImpl(db);

    assert.strictEqual(db._docs.users.alice.role, 'mom');
    assert.deepStrictEqual(summary,
        expectedSummary({scanned: 1, skipped: 1, skippedReasons: {missingAccount: 1}}));
  });

  it('counts, but does not act on, an invitation with no recorded accepter', async () => {
    // Never accepted, or accepted before `acceptedBy` existed - the one class the spec
    // calls permanently unrepairable. It must still be visible in the report: a run over
    // many legacy invitations that never recorded an accepter must not look identical to a
    // run over an empty collection.
    const seed = backfillSeed();
    seed.invitations.inv1.acceptedBy = null;
    const db = fakeDb(seed);

    const summary = await backfillParentSlotsImpl(db);

    assert.strictEqual(db._docs.users.alice.role, 'mom');
    assert.strictEqual(db._docs.users.bob.role, 'mom');
    assert.deepStrictEqual(summary,
        expectedSummary({scanned: 1, skipped: 1, skippedReasons: {noAccepter: 1}}));
  });

  it('ignores an invitation that is still pending', async () => {
    const seed = backfillSeed();
    seed.invitations.inv1.status = 'pending';
    const db = fakeDb(seed);

    const summary = await backfillParentSlotsImpl(db);

    assert.strictEqual(db._docs.users.bob.role, 'mom');
    assert.deepStrictEqual(summary, expectedSummary({}));
  });

  it('counts a per-invitation failure without aborting the run', async () => {
    // A malformed invitation - here, one missing fromUserId - makes
    // db.collection('users').doc(...) throw synchronously, mirroring the real Admin SDK.
    // The good pair alongside it must still be repaired: a migration with no undo must not
    // let one broken document discard everything already reasoned about, or already
    // written, for every other pair in the same run.
    const seed = backfillSeed();
    seed.invitations.bad = {
      id: 'bad',
      status: 'accepted',
      fromUserId: '',
      acceptedBy: 'bob',
    };
    const db = fakeDb(seed);

    const summary = await backfillParentSlotsImpl(db);

    assert.strictEqual(db._docs.users.alice.role, 'mom', 'the good pair still gets repaired');
    assert.strictEqual(db._docs.users.bob.role, 'dad');
    assert.deepStrictEqual(summary, expectedSummary({scanned: 2, updated: 1, failed: 1}));
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

  it('lets an allow-listed operator through the gate', () => {
    // A gate broken closed - the wrong env var name, an inverted condition - would still
    // pass every "refuses ..." case above, since none of them ever supplies a uid the code
    // is supposed to admit. `isBackfillOperator` is exercised directly (rather than through
    // `test.wrap`, which would carry the call all the way to a real `admin.firestore()`
    // with no Firestore available in this test run) so the affirmative case is checked
    // too, not only the negative ones.
    process.env.BACKFILL_ADMIN_UIDS = 'op-uid, other-op';

    assert.strictEqual(myFunctions.isBackfillOperator({auth: {uid: 'op-uid'}}), true);
    assert.strictEqual(myFunctions.isBackfillOperator({auth: {uid: 'other-op'}}), true);
    assert.strictEqual(myFunctions.isBackfillOperator({auth: {uid: 'someone-else'}}), false);
    assert.strictEqual(myFunctions.isBackfillOperator({}), false);
    assert.strictEqual(myFunctions.isBackfillOperator(undefined), false);
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

describe('backfillFamilyDocumentsImpl', () => {
  let backfillFamilyDocumentsImpl;

  before(() => {
    backfillFamilyDocumentsImpl = require('../index').backfillFamilyDocumentsImpl;
  });

  it('creates the family a pair formed before pairing wrote one never got', async () => {
    const db = fakeDb({
      users: {
        alice: {id: 'alice', partnerId: 'bob', role: 'mom', caresFor: 'CHILDREN'},
        bob: {id: 'bob', partnerId: 'alice', role: 'dad', caresFor: 'PETS'},
      },
    });

    const summary = await backfillFamilyDocumentsImpl(db);

    assert.strictEqual(summary.scanned, 1, 'a pair is one row of work, not two');
    assert.strictEqual(summary.created, 1);
    assert.deepStrictEqual(db._docs.families['alice__bob'], {
      members: ['alice', 'bob'],
      slots: {alice: 'mom', bob: 'dad'},
      caresFor: {alice: 'CHILDREN', bob: 'PETS'},
    });
  });

  it('completes an M-1 family that has members but not the two new fields', async () => {
    // The pairs that accepted between M-1 and M-3. `set` with merge, so `members` — which the
    // read rule keys on — survives rather than being rewritten from scratch.
    const db = fakeDb({
      users: {
        alice: {id: 'alice', partnerId: 'bob', role: 'mom'},
        bob: {id: 'bob', partnerId: 'alice', role: 'dad'},
      },
      families: {
        alice__bob: {id: 'alice__bob', members: ['alice', 'bob'], createdAt: 42},
      },
    });

    const summary = await backfillFamilyDocumentsImpl(db);

    assert.strictEqual(summary.updated, 1);
    assert.strictEqual(summary.created, 0);
    const family = db._docs.families['alice__bob'];
    assert.strictEqual(family.createdAt, 42, 'merge must not drop what was already there');
    assert.deepStrictEqual(family.slots, {alice: 'mom', bob: 'dad'});
  });

  it('leaves a complete family alone, so a second run looks like the first', async () => {
    // And specifically: a `caresFor` either parent has edited through the app since must not
    // be rolled back to whatever their profile happens to say.
    const db = fakeDb({
      users: {
        alice: {id: 'alice', partnerId: 'bob', role: 'mom', caresFor: 'CHILDREN'},
        bob: {id: 'bob', partnerId: 'alice', role: 'dad', caresFor: 'CHILDREN'},
      },
      families: {
        alice__bob: {
          id: 'alice__bob',
          members: ['alice', 'bob'],
          slots: {alice: 'mom', bob: 'dad'},
          caresFor: {alice: 'PETS', bob: ''},
        },
      },
    });

    const summary = await backfillFamilyDocumentsImpl(db);

    assert.strictEqual(summary.skipped, 1);
    assert.strictEqual(summary.skippedReasons.alreadyComplete, 1);
    assert.deepStrictEqual(
        db._docs.families['alice__bob'].caresFor, {alice: 'PETS', bob: ''});
  });

  it('refuses a one-sided pairing, which is what an interrupted unpair leaves', async () => {
    // Bob has moved on; Alice's row still names him. Turning that into a family would hand
    // Alice membership of a relationship Bob has already left.
    const db = fakeDb({
      users: {
        alice: {id: 'alice', partnerId: 'bob', role: 'mom'},
        bob: {id: 'bob', partnerId: '', role: 'dad'},
      },
    });

    const summary = await backfillFamilyDocumentsImpl(db);

    assert.strictEqual(summary.skippedReasons.notMutual, 1);
    assert.strictEqual(db._docs.families, undefined);
  });

  it('reports a deleted account separately from a one-sided link', async () => {
    const db = fakeDb({
      users: {
        alice: {id: 'alice', partnerId: 'ghost', role: 'mom'},
      },
    });

    const summary = await backfillFamilyDocumentsImpl(db);

    assert.strictEqual(summary.skippedReasons.missingAccount, 1);
    assert.strictEqual(summary.skippedReasons.notMutual, 0);
  });

  it('ignores unpaired accounts and a row that names itself', async () => {
    const db = fakeDb({
      users: {
        alice: {id: 'alice', role: 'mom'},
        bob: {id: 'bob', partnerId: '', role: 'dad'},
        carol: {id: 'carol', partnerId: 'carol', role: 'mom'},
      },
    });

    const summary = await backfillFamilyDocumentsImpl(db);

    assert.strictEqual(summary.scanned, 0);
    assert.strictEqual(summary.skipped, 0);
  });

  it('counts a pair whose two parents still share a slot without inventing one', async () => {
    // Who is parent 1 needs the invitation, which only `backfillParentSlots` reads. This
    // records what the profiles actually hold and says how many pairs came out indistinct,
    // rather than guessing and risking re-stamping the wrong person's events.
    const db = fakeDb({
      users: {
        alice: {id: 'alice', partnerId: 'bob', role: 'mom'},
        bob: {id: 'bob', partnerId: 'alice', role: 'mom'},
      },
    });

    const summary = await backfillFamilyDocumentsImpl(db);

    assert.strictEqual(summary.sameSlot, 1);
    assert.deepStrictEqual(
        db._docs.families['alice__bob'].slots, {alice: 'mom', bob: 'mom'});
  });

  it('reads a missing caresFor as empty, never as the string undefined', async () => {
    const db = fakeDb({
      users: {
        alice: {id: 'alice', partnerId: 'bob', role: 'mom'},
        bob: {id: 'bob', partnerId: 'alice', role: 'dad'},
      },
    });

    await backfillFamilyDocumentsImpl(db);

    assert.deepStrictEqual(
        db._docs.families['alice__bob'].caresFor, {alice: '', bob: ''});
  });
});

describe('backfillParentSlots keeps the family in step', () => {
  let backfillParentSlotsImpl;

  before(() => {
    backfillParentSlotsImpl = require('../index').backfillParentSlotsImpl;
  });

  it('updates an existing family\'s slots when it separates a pair', async () => {
    const db = fakeDb({
      invitations: {
        inv1: {id: 'inv1', status: 'accepted', fromUserId: 'alice', acceptedBy: 'bob'},
      },
      users: {
        alice: {id: 'alice', partnerId: 'bob', role: 'mom'},
        bob: {id: 'bob', partnerId: 'alice', role: 'mom'},
      },
      families: {
        alice__bob: {
          id: 'alice__bob',
          members: ['alice', 'bob'],
          slots: {alice: 'mom', bob: 'mom'},
        },
      },
    });

    await backfillParentSlotsImpl(db);

    assert.deepStrictEqual(
        db._docs.families['alice__bob'].slots, {alice: 'mom', bob: 'dad'});
  });

  it('does not create a family that has none, which would be unreadable', async () => {
    // A `set` here would write `slots` with no `members`, and the read rule keys on
    // `members`: a missing key errors in Rules, so neither parent could ever read it again.
    const db = fakeDb({
      invitations: {
        inv1: {id: 'inv1', status: 'accepted', fromUserId: 'alice', acceptedBy: 'bob'},
      },
      users: {
        alice: {id: 'alice', partnerId: 'bob', role: 'mom'},
        bob: {id: 'bob', partnerId: 'alice', role: 'mom'},
      },
    });

    const summary = await backfillParentSlotsImpl(db);

    assert.strictEqual(summary.updated, 1, 'the profiles are still re-slotted');
    assert.strictEqual(db._docs.families, undefined);
  });
});

describe('backfillRecordFamilyIdsImpl', () => {
  let backfillRecordFamilyIdsImpl;

  before(() => {
    backfillRecordFamilyIdsImpl = require('../index').backfillRecordFamilyIdsImpl;
  });

  /**
   * A live, mutually-linked pair plus one document in each shared collection.
   *
   * @param {!Object} overrides Collections to replace wholesale on the default fixture.
   * @return {!Object} The fake Firestore.
   */
  function pairedDb(overrides) {
    return fakeDb(Object.assign({
      users: {
        alice: {id: 'alice', partnerId: 'bob'},
        bob: {id: 'bob', partnerId: 'alice'},
      },
      events: {ev1: {id: 'ev1', createdByFirebaseUid: 'alice'}},
      expenses: {ex1: {id: 'ex1', createdByFirebaseUid: 'alice'}},
      budgets: {bu1: {id: 'bu1', createdByFirebaseUid: 'alice'}},
      child_info: {ch1: {id: 'ch1', createdByFirebaseUid: 'alice'}},
      pets: {pe1: {id: 'pe1', createdByFirebaseUid: 'alice'}},
      change_requests: {cr1: {id: 'cr1', requestedBy: 'alice'}},
    }, overrides));
  }

  it('stamps every shared collection, reading the author field each one actually uses', async () => {
    // A change request names both adults directly, so its author field is `requestedBy` and
    // not the `createdByFirebaseUid` the other five carry. Getting that wrong would leave
    // change requests unstamped and silently unreadable once the rules key on the family.
    const db = pairedDb({});

    const summary = await backfillRecordFamilyIdsImpl(db);

    assert.strictEqual(summary.stamped, 6);
    assert.strictEqual(db._docs.events.ev1.familyId, 'alice__bob');
    assert.strictEqual(db._docs.expenses.ex1.familyId, 'alice__bob');
    assert.strictEqual(db._docs.budgets.bu1.familyId, 'alice__bob');
    assert.strictEqual(db._docs.child_info.ch1.familyId, 'alice__bob');
    assert.strictEqual(db._docs.pets.pe1.familyId, 'alice__bob');
    assert.strictEqual(db._docs.change_requests.cr1.familyId, 'alice__bob');
  });

  it('leaves a document that already names a family alone', async () => {
    // A second run must be a no-op, and a record a newer client already stamped must not be
    // overwritten — the family a record belongs to is never re-derived.
    const db = pairedDb({
      expenses: {ex1: {id: 'ex1', createdByFirebaseUid: 'alice', familyId: 'someone__else'}},
    });

    const summary = await backfillRecordFamilyIdsImpl(db);

    assert.strictEqual(db._docs.expenses.ex1.familyId, 'someone__else');
    assert.strictEqual(summary.perCollection.expenses, 0);
  });

  it('treats an empty familyId as unstamped', async () => {
    // `""` is what a null becomes on the wire, so it is the shape an unpaired-at-the-time
    // record actually carries — not an answer, and this is the pass that gives it one.
    const db = pairedDb({
      expenses: {ex1: {id: 'ex1', createdByFirebaseUid: 'alice', familyId: ''}},
    });

    await backfillRecordFamilyIdsImpl(db);

    assert.strictEqual(db._docs.expenses.ex1.familyId, 'alice__bob');
  });

  it('refuses a one-sided pairing rather than reviving it', async () => {
    const db = pairedDb({
      users: {
        alice: {id: 'alice', partnerId: 'bob'},
        bob: {id: 'bob', partnerId: ''},
      },
    });

    const summary = await backfillRecordFamilyIdsImpl(db);

    assert.strictEqual(summary.skippedReasons.notMutual, 1);
    assert.strictEqual(summary.stamped, 0);
    assert.strictEqual(db._docs.expenses.ex1.familyId, undefined);
  });

  it('skips an unpaired account without counting it as a failure', async () => {
    const db = fakeDb({
      users: {solo: {id: 'solo'}},
      expenses: {ex1: {id: 'ex1', createdByFirebaseUid: 'solo'}},
    });

    const summary = await backfillRecordFamilyIdsImpl(db);

    assert.strictEqual(summary.skippedReasons.unpaired, 1);
    assert.strictEqual(summary.failed, 0);
    assert.strictEqual(db._docs.expenses.ex1.familyId, undefined);
  });

  it('never stamps a record belonging to somebody outside the pair', async () => {
    const db = pairedDb({
      expenses: {
        ex1: {id: 'ex1', createdByFirebaseUid: 'alice'},
        ex2: {id: 'ex2', createdByFirebaseUid: 'stranger'},
      },
    });

    await backfillRecordFamilyIdsImpl(db);

    assert.strictEqual(db._docs.expenses.ex1.familyId, 'alice__bob');
    assert.strictEqual(db._docs.expenses.ex2.familyId, undefined);
  });

  it('counts a per-user failure without abandoning the rest of the run', async () => {
    const db = pairedDb({
      users: {
        alice: {id: 'alice', partnerId: 'bob'},
        bob: {id: 'bob', partnerId: 'alice'},
        broken: {id: 'broken', partnerId: {not: 'a string'}},
      },
    });

    const summary = await backfillRecordFamilyIdsImpl(db);

    // The malformed row is not a string, so it is read as unpaired rather than crashing.
    assert.strictEqual(summary.stamped, 6);
    assert.strictEqual(summary.failed, 0);
  });
});

describe('unpairing one family out of several', () => {
  let unpairCoParentImpl;

  before(() => {
    unpairCoParentImpl = require('../index').unpairCoParentImpl;
  });

  /**
   * Alice co-parents with both Bob and Carol.
   *
   * @return {!Object} The fake Firestore.
   */
  function twoFamilies() {
    return fakeDb({
      users: {
        alice: {
          id: 'alice', name: 'Alice',
          partnerIds: ['bob', 'carol'], partnerId: 'bob', pairedAt: 1,
        },
        bob: {id: 'bob', name: 'Bob', partnerIds: ['alice'], partnerId: 'alice', pairedAt: 1},
        carol: {
          id: 'carol', name: 'Carol', partnerIds: ['alice'], partnerId: 'alice', pairedAt: 2,
        },
      },
      families: {
        alice__bob: {id: 'alice__bob', members: ['alice', 'bob']},
        alice__carol: {id: 'alice__carol', members: ['alice', 'carol']},
      },
    });
  }

  it('ends the named relationship and leaves the other standing', async () => {
    const db = twoFamilies();

    const result = await unpairCoParentImpl(db, 'alice', 'carol');

    assert.strictEqual(result.unpairedFrom, 'carol');
    assert.deepStrictEqual(db._docs.users.alice.partnerIds, ['bob']);
    assert.deepStrictEqual(db._docs.users.carol.partnerIds, []);
    // Bob's family survives; Carol's is gone, and the family document going *is* the
    // revocation — a membership left behind would let her back into everything.
    assert.ok(db._docs.families['alice__bob']);
    assert.strictEqual(db._docs.families['alice__carol'], undefined);
  });

  it('keeps pairedAt while any relationship remains, and clears it with the last', async () => {
    const db = twoFamilies();

    await unpairCoParentImpl(db, 'alice', 'carol');
    assert.strictEqual(db._docs.users.alice.pairedAt, 1, 'still a co-parent, of Bob');

    await unpairCoParentImpl(db, 'alice', 'bob');
    assert.strictEqual(db._docs.users.alice.pairedAt, null, 'no relationships left');
  });

  it('re-points the singular field at whichever relationship survives', async () => {
    // `partnerId` is what a build that predates the array reads. Left naming the ex-partner,
    // that build would keep showing a family the account is no longer in.
    const db = twoFamilies();

    await unpairCoParentImpl(db, 'alice', 'bob');

    assert.strictEqual(db._docs.users.alice.partnerId, 'carol');
  });

  it('refuses to guess when several relationships exist and none was named', async () => {
    // Ending the wrong one deletes that pair's custody schedule and money agreement, and
    // there is no undo.
    const db = twoFamilies();

    await assert.rejects(
        () => unpairCoParentImpl(db, 'alice', null),
        (err) => err.details && err.details.reason === 'ambiguous-partner');
    assert.deepStrictEqual(db._docs.users.alice.partnerIds, ['bob', 'carol']);
  });

  it('accepts an unnamed target when there is exactly one relationship', async () => {
    // What a build that predates multiple families sends.
    const db = fakeDb({
      users: {
        alice: {id: 'alice', name: 'Alice', partnerIds: ['bob'], partnerId: 'bob', pairedAt: 1},
        bob: {id: 'bob', name: 'Bob', partnerIds: ['alice'], partnerId: 'alice', pairedAt: 1},
      },
      families: {alice__bob: {id: 'alice__bob', members: ['alice', 'bob']}},
    });

    const result = await unpairCoParentImpl(db, 'alice', null);

    assert.strictEqual(result.unpairedFrom, 'bob');
    assert.strictEqual(db._docs.users.alice.partnerId, '');
  });

  it('ends nothing when the named person is not a co-parent', async () => {
    const db = twoFamilies();

    const result = await unpairCoParentImpl(db, 'alice', 'stranger');

    assert.strictEqual(result.unpairedFrom, null);
    assert.deepStrictEqual(db._docs.users.alice.partnerIds, ['bob', 'carol']);
  });
});
