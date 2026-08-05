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
      });
      staged.forEach(({ref, update}) => {
        docs[ref.collection] = docs[ref.collection] || {};
        docs[ref.collection][ref.id] = Object.assign({}, docs[ref.collection][ref.id], update);
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
});
