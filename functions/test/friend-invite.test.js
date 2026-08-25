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

  it('copies the accepter Google picture into the grant', async () => {
    // The parents' "who can see this" list would otherwise need a second read of a document
    // that is not theirs. A Google sign-in puts the account picture in profilePhotoUrl.
    const db = seeded({}, {
      alice: {id: 'alice', name: 'Alice', role: 'mom', partnerId: 'bob'},
      bob: {id: 'bob', name: 'Bob', role: 'dad', partnerId: 'alice'},
      nina: {id: 'nina', name: 'Nina', profilePhotoUrl: 'https://lh3.googleusercontent.com/a/x'},
    });

    await myFunctions.acceptCalendarFriendInvitationImpl(db, 'nina', 'nina@example.com', ref);

    assert.strictEqual(
        db._docs.calendar_friends.nina.photoUrl, 'https://lh3.googleusercontent.com/a/x');
  });

  it('writes no photoUrl key at all when the accepter has no picture', async () => {
    // Never `photoUrl: undefined` — Firestore rejects that outright, so the helper returns an
    // object to merge rather than a value.
    const db = seeded();
    await myFunctions.acceptCalendarFriendInvitationImpl(db, 'nina', 'nina@example.com', ref);
    assert.ok(!('photoUrl' in db._docs.calendar_friends.nina));
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

describe('the grant is scoped to one family (M-6)', () => {
  let myFunctions;

  before(() => {
    myFunctions = require('../index');
  });

  const ref = {code: null, invitationId: 'inv1'};

  /** Alice co-parents with Bob *and* with Carol; Bob's family is the one on her screen. */
  const twoFamilies = {
    alice: {id: 'alice', name: 'Alice', role: 'mom', partnerId: 'bob',
      partnerIds: ['bob', 'carol']},
    bob: {id: 'bob', name: 'Bob', role: 'dad', partnerId: 'alice', partnerIds: ['alice']},
    carol: {id: 'carol', name: 'Carol', role: 'dad', partnerId: 'alice', partnerIds: ['alice']},
    nina: {id: 'nina', name: 'Nina'},
  };

  it('stamps the family id the events read rule keys on', async () => {
    const db = seeded();

    await myFunctions.acceptCalendarFriendInvitationImpl(db, 'nina', 'nina@example.com', ref);

    assert.strictEqual(db._docs.calendar_friends.nina.familyId, 'alice__bob');
  });

  it('honours the family the invitation was generated in', async () => {
    // Alice was looking at her family with Carol when she made the code. Without this the grant
    // would land in whichever family she happens to be showing when the friend redeems it —
    // days later, and invisibly.
    const db = seeded({familyId: 'alice__carol'}, twoFamilies);

    const result = await myFunctions.acceptCalendarFriendInvitationImpl(
        db, 'nina', 'nina@example.com', ref);

    assert.strictEqual(db._docs.calendar_friends.nina.familyId, 'alice__carol');
    assert.deepStrictEqual(result.familyParents, ['alice', 'carol']);
  });

  it('ignores a family the inviter is no longer part of', async () => {
    // The relationship ended between generating the code and redeeming it. The id is a claim,
    // not proof: it is checked against the inviter's live co-parents and falls back to the
    // family they are actually showing.
    const db = seeded({familyId: 'alice__dave'}, twoFamilies);

    await myFunctions.acceptCalendarFriendInvitationImpl(db, 'nina', 'nina@example.com', ref);

    assert.strictEqual(db._docs.calendar_friends.nina.familyId, 'alice__bob');
  });

  it('ignores a family the inviter is not even named in', async () => {
    const db = seeded({familyId: 'bob__carol'}, twoFamilies);

    await myFunctions.acceptCalendarFriendInvitationImpl(db, 'nina', 'nina@example.com', ref);

    assert.strictEqual(db._docs.calendar_friends.nina.familyId, 'alice__bob');
  });

  it('falls back for an invitation made by a build that predates M-6', async () => {
    const db = seeded({}, twoFamilies);

    await myFunctions.acceptCalendarFriendInvitationImpl(db, 'nina', 'nina@example.com', ref);

    assert.strictEqual(db._docs.calendar_friends.nina.familyId, 'alice__bob');
  });
});

describe('partnerFromFamilyId', () => {
  let myFunctions;

  before(() => {
    myFunctions = require('../index');
  });

  it('names the other member, from either side', () => {
    assert.strictEqual(myFunctions.partnerFromFamilyId('alice__bob', 'alice'), 'bob');
    assert.strictEqual(myFunctions.partnerFromFamilyId('alice__bob', 'bob'), 'alice');
  });

  it('refuses an id that does not name the caller', () => {
    assert.strictEqual(myFunctions.partnerFromFamilyId('bob__carol', 'alice'), '');
  });

  it('refuses anything malformed rather than throwing', () => {
    // It is fed a value straight off an invitation document, which anyone may write.
    ['', 'alice', 'a__b__c', 'alice__alice', null, undefined, 42, {}].forEach((value) => {
      assert.strictEqual(myFunctions.partnerFromFamilyId(value, 'alice'), '');
    });
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
