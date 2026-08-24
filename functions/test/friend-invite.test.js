const test = require('firebase-functions-test')();
const assert = require('assert');
const sinon = require('sinon');

/**
 * Minimal in-memory Firestore covering what `acceptCalendarFriendInvitation` needs: reading an
 * invitation and the inviter's profile, writing the grant with `set` inside a transaction, and
 * adding notifications. Modeled on the fake in `guest-invite.test.js`, with `set` staged as well
 * as `update` — the friend path creates a `calendar_friends` document that does not exist yet.
 *
 * @param {!Object<string, !Object<string, !Object>>} seed Documents keyed by collection then
 *     document id.
 * @return {!Object} The fake, carrying `_docs` and `_added` for assertions.
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
    _added: [],
    collection(name) {
      const self = this;
      return {
        doc: (id) => docRef(name, id),
        async add(data) {
          self._added.push({collection: name, data});
          return {id: 'generated-1', data};
        },
      };
    },
    async runTransaction(fn) {
      const staged = [];
      const result = await fn({
        get: (ref) => ref.get(),
        update: (ref, update) => staged.push({ref, update, whole: false}),
        set: (ref, value) => staged.push({ref, update: value, whole: true}),
      });
      staged.forEach(({ref, update, whole}) => {
        docs[ref.collection] = docs[ref.collection] || {};
        docs[ref.collection][ref.id] = whole ?
          update : Object.assign({}, docs[ref.collection][ref.id], update);
      });
      return result;
    },
  };
}

/** Far enough out that no test run is ever near it. */
const GRANT_ENDS = Date.parse('2099-01-01T00:00:00Z');

/**
 * A world with paired parents Alice and Bob, and a pending friend invitation Alice made.
 *
 * @param {!Object=} inviteOverrides Fields to change on the invitation.
 * @param {!Object=} userOverrides Users map to replace the default.
 * @return {!Object} The fake db.
 */
function seeded(inviteOverrides, userOverrides) {
  return fakeDb({
    invitations: {
      inv1: Object.assign({
        id: 'inv1',
        code: '4F7K2M',
        kind: 'friend',
        status: 'pending',
        fromUserId: 'alice',
        toEmail: '',
        friendExpiresAt: GRANT_ENDS,
      }, inviteOverrides || {}),
    },
    users: userOverrides || {
      alice: {id: 'alice', name: 'Alice', role: 'mom', partnerId: 'bob'},
      bob: {id: 'bob', name: 'Bob', role: 'dad', partnerId: 'alice'},
      nina: {id: 'nina', name: 'Nina'},
    },
  });
}

describe('acceptCalendarFriendInvitation', () => {
  let myFunctions;

  before(() => {
    myFunctions = require('../index');
  });

  after(() => {
    test.cleanup();
    sinon.restore();
  });

  const ref = {code: null, invitationId: 'inv1'};

  it('rejects an unauthenticated caller', async () => {
    const wrapped = test.wrap(myFunctions.acceptCalendarFriendInvitation);
    await assert.rejects(
        () => wrapped({code: '4F7K2M'}, {}),
        (err) => err.code === 'unauthenticated');
  });

  it('requires exactly one of code or invitationId', async () => {
    const wrapped = test.wrap(myFunctions.acceptCalendarFriendInvitation);
    await assert.rejects(
        () => wrapped({}, {auth: {uid: 'nina', token: {email: 'nina@example.com'}}}),
        (err) => err.code === 'invalid-argument');
  });

  it('writes one grant naming both parents, and no user document', async () => {
    const db = seeded();

    const result = await myFunctions.acceptCalendarFriendInvitationImpl(
        db, 'nina', 'nina@example.com', ref);

    const grant = db._docs.calendar_friends.nina;
    assert.deepStrictEqual(grant.familyParents, ['alice', 'bob']);
    assert.strictEqual(grant.grantedBy, 'alice');
    assert.strictEqual(grant.expiresAtMillis, GRANT_ENDS);
    assert.strictEqual(grant.name, 'Nina');
    assert.deepStrictEqual(result.familyParents, ['alice', 'bob']);
    // The whole point of the central grant: no parent's profile is rewritten, and nothing
    // fans out over the family's events.
    assert.strictEqual(db._docs.users.nina.partnerId, undefined);
    assert.strictEqual(db._docs.users.alice.partnerId, 'bob');
  });

  it('marks the invitation accepted', async () => {
    const db = seeded();
    await myFunctions.acceptCalendarFriendInvitationImpl(db, 'nina', 'nina@example.com', ref);
    assert.strictEqual(db._docs.invitations.inv1.status, 'accepted');
    assert.strictEqual(db._docs.invitations.inv1.acceptedBy, 'nina');
  });

  it('tells both parents, not only the inviter', async () => {
    const db = seeded();
    await myFunctions.acceptCalendarFriendInvitationImpl(db, 'nina', 'nina@example.com', ref);
    const targets = db._added
        .filter((a) => a.collection === 'notification_queue')
        .map((a) => a.data.targetUserId)
        .sort();
    assert.deepStrictEqual(targets, ['alice', 'bob']);
  });

  it('refuses a guest invitation offered to the friend path', async () => {
    const db = seeded({kind: 'guest'});
    await assert.rejects(
        () => myFunctions.acceptCalendarFriendInvitationImpl(db, 'nina', 'n@e.com', ref),
        (err) => err.code === 'failed-precondition');
  });

  it('refuses when the inviter is not paired', async () => {
    const db = seeded({}, {
      alice: {id: 'alice', name: 'Alice', role: 'mom', partnerId: ''},
      nina: {id: 'nina', name: 'Nina'},
    });
    await assert.rejects(
        () => myFunctions.acceptCalendarFriendInvitationImpl(db, 'nina', 'n@e.com', ref),
        (err) => err.code === 'failed-precondition');
  });

  it('refuses the co-parent taking a friend grant on their own family', async () => {
    const db = seeded();
    await assert.rejects(
        () => myFunctions.acceptCalendarFriendInvitationImpl(db, 'bob', 'bob@e.com', ref),
        (err) => err.code === 'failed-precondition');
  });

  it('refuses an invitation whose grant has already ended', async () => {
    const db = seeded({friendExpiresAt: 1000});
    await assert.rejects(
        () => myFunctions.acceptCalendarFriendInvitationImpl(db, 'nina', 'n@e.com', ref),
        (err) => err.code === 'failed-precondition');
  });

  it('refuses an invitation that is no longer pending', async () => {
    const db = seeded({status: 'accepted'});
    await assert.rejects(
        () => myFunctions.acceptCalendarFriendInvitationImpl(db, 'nina', 'n@e.com', ref),
        (err) => err.code === 'failed-precondition');
  });

  it('refuses the inviter accepting their own invitation', async () => {
    const db = seeded();
    await assert.rejects(
        () => myFunctions.acceptCalendarFriendInvitationImpl(db, 'alice', 'a@e.com', ref),
        (err) => err.code === 'invalid-argument');
  });
});

describe('acceptPairingInvitation refuses a friend invitation', () => {
  let myFunctions;

  before(() => {
    myFunctions = require('../index');
  });

  it('never turns a friend into a co-parent', async () => {
    // The dangerous direction of the three-callable split: `assignSlots` would hand a friend a
    // permanent parent slot and write `partnerId` on both users.
    const db = seeded();
    await assert.rejects(
        () => myFunctions.acceptPairingInvitationImpl(db, 'nina', 'nina@example.com',
            {code: null, invitationId: 'inv1'}),
        (err) => err.code === 'failed-precondition' &&
                 err.details && err.details.reason === 'friend-invitation');
  });
});
