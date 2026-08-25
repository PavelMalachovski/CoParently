const test = require('firebase-functions-test')();
const assert = require('assert');
const sinon = require('sinon');
const admin = require('firebase-admin');

/**
 * Minimal in-memory Firestore covering what `acceptPairingInvitation` needs: reading an
 * invitation and two user documents inside a transaction, updating all three, and adding a
 * notification. Modeled on the fake in `unpair-callable.test.js`, trimmed to this callable's
 * calls (no queries, no batches).
 *
 * @param {!Object<string, !Object<string, !Object>>} seed Documents keyed by collection then
 *     document id.
 * @return {!Object} The fake, carrying `_docs` for assertions.
 */
function fakeDb(seed) {
  const docs = JSON.parse(JSON.stringify(seed));

  /**
   * Applies one field value, interpreting the `arrayUnion`/`arrayRemove` sentinels the real
   * Admin SDK resolves server-side.
   *
   * Without this the fake stored the sentinel object itself, and a test asserting on
   * `partnerIds` compared an `ArrayUnionTransform` against a list — which is a fake that
   * quietly disagrees with production about the one field multi-family pairing turns on.
   *
   * @param {*} current The value the field holds now.
   * @param {*} incoming The value the code under test wrote.
   * @return {*} The value to store.
   */
  function resolveValue(current, incoming) {
    if (!(incoming instanceof admin.firestore.FieldValue)) {
      return incoming;
    }
    const existing = Array.isArray(current) ? current : [];
    const elements = incoming.elements || [];
    // `_methodName` is how the SDK labels a sentinel; both spellings have shipped.
    const name = incoming._methodName || incoming.methodName || '';
    if (name.indexOf('arrayRemove') >= 0) {
      return existing.filter((v) => !elements.includes(v));
    }
    if (name.indexOf('arrayUnion') >= 0) {
      return existing.concat(elements.filter((v) => !existing.includes(v)));
    }
    return incoming;
  }

  /**
   * Merges an update map onto a stored document, resolving sentinels.
   *
   * @param {?Object} stored The document as it stands.
   * @param {!Object} update The update map.
   * @return {!Object} The merged document.
   */
  function merge(stored, update) {
    const next = Object.assign({}, stored);
    Object.keys(update).forEach((key) => {
      next[key] = resolveValue(next[key], update[key]);
    });
    return next;
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
        return {exists: data !== undefined, data: () => data, ref: docRef(collection, id)};
      },
      async update(update) {
        docs[collection] = docs[collection] || {};
        docs[collection][id] = merge(docs[collection][id], update);
      },
    };
  }

  return {
    _docs: docs,
    collection(name) {
      return {
        doc: (id) => docRef(name, id),
        async add(data) {
          return {id: 'generated-1', data};
        },
      };
    },
    async runTransaction(fn) {
      const staged = [];
      const result = await fn({
        get: (ref) => ref.get(),
        update: (ref, update) => staged.push({ref, update}),
        // `set` replaces where `update` merges, which is the difference that matters for the
        // family document: it is written whole, once, at pairing.
        set: (ref, value) => staged.push({ref, value}),
      });
      staged.forEach(({ref, update, value}) => {
        docs[ref.collection] = docs[ref.collection] || {};
        docs[ref.collection][ref.id] = value !== undefined ?
          value :
          merge(docs[ref.collection][ref.id], update);
      });
      return result;
    },
  };
}

describe('acceptPairingInvitation', () => {
  let myFunctions;

  before(() => {
    myFunctions = require('../index');
  });

  after(() => {
    test.cleanup();
    sinon.restore();
  });

  it('rejects an unauthenticated caller', async () => {
    const wrapped = test.wrap(myFunctions.acceptPairingInvitation);
    await assert.rejects(
        () => wrapped({code: '4F7K2M'}, {}),
        (err) => err.code === 'unauthenticated',
    );
  });

  it('requires exactly one of code or invitationId', async () => {
    const wrapped = test.wrap(myFunctions.acceptPairingInvitation);
    await assert.rejects(
        () => wrapped({}, {auth: {uid: 'u1', token: {email: 'a@b.c'}}}),
        (err) => err.code === 'invalid-argument',
    );
  });

  it('puts the two parents in different slots', () => {
    const {assignSlots} = require('../index');
    assert.deepStrictEqual(
        assignSlots('mom'),
        {inviterRole: 'mom', accepterRole: 'dad'},
        'a pair where both defaulted to mom must be separated');
  });

  it('keeps the inviter slot and gives the accepter the other one', () => {
    const {assignSlots} = require('../index');
    assert.deepStrictEqual(
        assignSlots('dad'),
        {inviterRole: 'dad', accepterRole: 'mom'});
  });

  it('is idempotent for a pair that is already separated', () => {
    const {assignSlots} = require('../index');
    assert.deepStrictEqual(
        assignSlots('mom'),
        {inviterRole: 'mom', accepterRole: 'dad'});
  });

  it('falls back to mom for the inviter when no slot is stored', () => {
    const {assignSlots} = require('../index');
    assert.deepStrictEqual(
        assignSlots(undefined),
        {inviterRole: 'mom', accepterRole: 'dad'});
  });

  it('returns the accepter\'s newly assigned role alongside partnerId', async () => {
    const db = fakeDb({
      invitations: {
        inv1: {id: 'inv1', status: 'pending', fromUserId: 'alice', toEmail: 'bob@example.com'},
      },
      users: {
        alice: {id: 'alice', name: 'Alice', role: 'mom'},
        bob: {id: 'bob', name: 'Bob'},
      },
    });

    const result = await myFunctions.acceptPairingInvitationImpl(
        db, 'bob', 'bob@example.com', {code: null, invitationId: 'inv1'});

    assert.deepStrictEqual(result, {partnerId: 'alice', role: 'dad'});
    assert.strictEqual(db._docs.users.alice.role, 'mom');
    assert.strictEqual(db._docs.users.bob.role, 'dad');
  });

  it('records the relationship as a family document naming both adults', async () => {
    // `families/{id}.members` is what the security rules read to decide who may see the
    // records a pair shares. It is written here, as admin, and by no client ever — the
    // membership is the grant, so a client create path would let anyone name themselves a
    // member of any pair.
    const db = fakeDb({
      invitations: {
        inv1: {id: 'inv1', status: 'pending', fromUserId: 'alice', toEmail: 'bob@example.com'},
      },
      users: {
        alice: {id: 'alice', name: 'Alice', role: 'mom'},
        bob: {id: 'bob', name: 'Bob'},
      },
    });

    await myFunctions.acceptPairingInvitationImpl(
        db, 'bob', 'bob@example.com', {code: null, invitationId: 'inv1'});

    // The same derived id the custody model and the conversation already use, so the three
    // subsystems that were pair-keyed all along need no migration.
    const family = db._docs.families['alice__bob'];
    assert.ok(family, 'pairing must record the family');
    assert.deepStrictEqual(family.members, ['alice', 'bob']);
  });

  it('sorts the two members, so the id and the array agree', async () => {
    // The id is the sorted join; an unsorted array would still resolve to the same id and pass
    // every membership check, which is how a mismatch could sit there unnoticed until
    // something compared the two.
    const db = fakeDb({
      invitations: {
        inv1: {id: 'inv1', status: 'pending', fromUserId: 'zoe', toEmail: 'adam@example.com'},
      },
      users: {
        zoe: {id: 'zoe', name: 'Zoe', role: 'mom'},
        adam: {id: 'adam', name: 'Adam'},
      },
    });

    await myFunctions.acceptPairingInvitationImpl(
        db, 'adam', 'adam@example.com', {code: null, invitationId: 'inv1'});

    assert.deepStrictEqual(db._docs.families['adam__zoe'].members, ['adam', 'zoe']);
  });

  it('records the two slots on the family, keyed by uid', async () => {
    // The slot belongs to the pair, not to the person: somebody who co-parents with two
    // others holds two of them, and one `users/{uid}.role` cannot carry both. Written here
    // as admin and by no client, because a parent who could set their own would take the
    // co-parent's colour and re-point what `parentOwner` means across the calendar.
    const db = fakeDb({
      invitations: {
        inv1: {id: 'inv1', status: 'pending', fromUserId: 'alice', toEmail: 'bob@example.com'},
      },
      users: {
        alice: {id: 'alice', name: 'Alice', role: 'dad'},
        bob: {id: 'bob', name: 'Bob'},
      },
    });

    await myFunctions.acceptPairingInvitationImpl(
        db, 'bob', 'bob@example.com', {code: null, invitationId: 'inv1'});

    // The inviter keeps the slot they had; the accepter takes the other. Exactly what lands
    // on the two profiles, so the family and the profiles cannot disagree.
    assert.deepStrictEqual(db._docs.families['alice__bob'].slots, {alice: 'dad', bob: 'mom'});
    assert.strictEqual(db._docs.users.alice.role, 'dad');
    assert.strictEqual(db._docs.users.bob.role, 'mom');
  });

  it('carries each parent\'s own caresFor answer onto the family', async () => {
    // Per family, not per person: a man with children by one woman and a dog with another
    // must not get child sections in the pet family. The stored form is the one
    // `users/{uid}.caresFor` already uses, so there is a single spelling to parse.
    const db = fakeDb({
      invitations: {
        inv1: {id: 'inv1', status: 'pending', fromUserId: 'alice', toEmail: 'bob@example.com'},
      },
      users: {
        alice: {id: 'alice', name: 'Alice', role: 'mom', caresFor: 'CHILDREN|PETS'},
        bob: {id: 'bob', name: 'Bob', caresFor: 'PETS'},
      },
    });

    await myFunctions.acceptPairingInvitationImpl(
        db, 'bob', 'bob@example.com', {code: null, invitationId: 'inv1'});

    assert.deepStrictEqual(db._docs.families['alice__bob'].caresFor, {
      alice: 'CHILDREN|PETS',
      bob: 'PETS',
    });
  });

  it('reads an account that never answered as \'\', not as the string undefined', async () => {
    // `String(undefined)` is four characters that `FamilyKind.fromStored` drops as an unknown
    // name — but only after both phones have read it. Empty is the honest value, and an empty
    // union already reads as "show everything", so silence hides nothing.
    const db = fakeDb({
      invitations: {
        inv1: {id: 'inv1', status: 'pending', fromUserId: 'alice', toEmail: 'bob@example.com'},
      },
      users: {
        alice: {id: 'alice', name: 'Alice', role: 'mom'},
        bob: {id: 'bob', name: 'Bob'},
      },
    });

    await myFunctions.acceptPairingInvitationImpl(
        db, 'bob', 'bob@example.com', {code: null, invitationId: 'inv1'});

    assert.deepStrictEqual(db._docs.families['alice__bob'].caresFor, {alice: '', bob: ''});
  });
});

describe('pairing with more than one co-parent', () => {
  let myFunctions;

  before(() => {
    myFunctions = require('../index');
  });

  it('lets an already-paired account take a second co-parent', async () => {
    // The refusal used to be "one of the accounts is already paired". A person may co-parent
    // with more than one other adult, and this is where the second relationship is created.
    const db = fakeDb({
      invitations: {
        inv2: {id: 'inv2', status: 'pending', fromUserId: 'alice', toEmail: 'carol@example.com'},
      },
      users: {
        alice: {id: 'alice', name: 'Alice', role: 'mom', partnerIds: ['bob'], partnerId: 'bob'},
        carol: {id: 'carol', name: 'Carol'},
      },
    });

    const result = await myFunctions.acceptPairingInvitationImpl(
        db, 'carol', 'carol@example.com', {code: null, invitationId: 'inv2'});

    assert.strictEqual(result.partnerId, 'alice');
    assert.deepStrictEqual(db._docs.families['alice__carol'].members, ['alice', 'carol']);
  });

  it('accumulates co-parents rather than replacing the first', async () => {
    const db = fakeDb({
      invitations: {
        inv2: {id: 'inv2', status: 'pending', fromUserId: 'alice', toEmail: 'carol@example.com'},
      },
      users: {
        alice: {id: 'alice', name: 'Alice', role: 'mom', partnerIds: ['bob'], partnerId: 'bob'},
        carol: {id: 'carol', name: 'Carol'},
      },
    });

    await myFunctions.acceptPairingInvitationImpl(
        db, 'carol', 'carol@example.com', {code: null, invitationId: 'inv2'});

    assert.deepStrictEqual(db._docs.users.alice.partnerIds, ['bob', 'carol']);
    // The singular field stays pinned to the first. A build that predates the array can only
    // cope with one relationship, so it should keep showing the one it already knew rather
    // than being silently moved to a family it has never heard of.
    assert.strictEqual(db._docs.users.alice.partnerId, 'bob');
    // And the newcomer, who had none, gets it as their first.
    assert.strictEqual(db._docs.users.carol.partnerId, 'alice');
  });

  it('still refuses a repeat of the same pair', async () => {
    // Two people are one family, and a second `families/{id}` between them is the same
    // document — accepting again would re-run `assignSlots` and could flip the slots their
    // whole history is stamped with.
    const db = fakeDb({
      invitations: {
        inv2: {id: 'inv2', status: 'pending', fromUserId: 'alice', toEmail: 'bob@example.com'},
      },
      users: {
        alice: {id: 'alice', name: 'Alice', role: 'mom', partnerIds: ['bob'], partnerId: 'bob'},
        bob: {id: 'bob', name: 'Bob', role: 'dad', partnerIds: ['alice'], partnerId: 'alice'},
      },
    });

    await assert.rejects(
        () => myFunctions.acceptPairingInvitationImpl(
            db, 'bob', 'bob@example.com', {code: null, invitationId: 'inv2'}),
        (err) => err.details && err.details.reason === 'already-paired');
  });

  it('reads a document that carries only the old singular field', async () => {
    // Mid-migration: the array has not been written yet, but the account is paired. The two
    // shapes are unioned rather than one winning, so the existing relationship is still seen.
    const db = fakeDb({
      invitations: {
        inv2: {id: 'inv2', status: 'pending', fromUserId: 'alice', toEmail: 'bob@example.com'},
      },
      users: {
        alice: {id: 'alice', name: 'Alice', role: 'mom', partnerId: 'bob'},
        bob: {id: 'bob', name: 'Bob', role: 'dad', partnerId: 'alice'},
      },
    });

    await assert.rejects(
        () => myFunctions.acceptPairingInvitationImpl(
            db, 'bob', 'bob@example.com', {code: null, invitationId: 'inv2'}),
        (err) => err.details && err.details.reason === 'already-paired');
  });
});
