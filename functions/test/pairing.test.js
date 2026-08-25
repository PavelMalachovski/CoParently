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
        docs[collection][id] = Object.assign({}, docs[collection][id], update);
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
          Object.assign({}, docs[ref.collection][ref.id], update);
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
});
