const test = require('firebase-functions-test')();
const assert = require('assert');
const sinon = require('sinon');

/**
 * Minimal in-memory Firestore covering what `acceptGuestInvitation` needs: reading an
 * invitation, a child record and the accepter's profile, updating the first two inside a
 * transaction, and adding a notification. Modeled on the fake in `pairing.test.js`, with a
 * `set` on the transaction so a rewritten `sharedWith` array can be asserted whole.
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

/** Far enough out that no test run is ever near it. */
const GRANT_ENDS = Date.parse('2099-01-01T00:00:00Z');

/**
 * A world with one child record Alice owns and shares with co-parent Bob, and a pending
 * guest invitation Alice made out to Nina.
 *
 * @param {!Object=} inviteOverrides Fields to change on the invitation.
 * @return {!Object} The fake db.
 */
function seeded(inviteOverrides) {
  return fakeDb({
    invitations: {
      inv1: Object.assign({
        id: 'inv1',
        code: '4F7K2M',
        kind: 'guest',
        status: 'pending',
        fromUserId: 'alice',
        toEmail: '',
        childInfoId: 'child1',
        guestExpiresAt: GRANT_ENDS,
      }, inviteOverrides || {}),
    },
    child_info: {
      child1: {
        id: 'child1',
        childName: 'Tomas',
        createdByFirebaseUid: 'alice',
        sharedWith: ['alice', 'bob'],
      },
      child2: {
        id: 'child2',
        childName: 'Eva',
        createdByFirebaseUid: 'alice',
        sharedWith: ['alice', 'bob'],
      },
    },
    users: {
      alice: {id: 'alice', name: 'Alice', role: 'mom', partnerId: 'bob'},
      bob: {id: 'bob', name: 'Bob', role: 'dad', partnerId: 'alice'},
      nina: {id: 'nina', name: 'Nina'},
    },
  });
}

describe('acceptGuestInvitation', () => {
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
    const wrapped = test.wrap(myFunctions.acceptGuestInvitation);
    await assert.rejects(
        () => wrapped({code: '4F7K2M'}, {}),
        (err) => err.code === 'unauthenticated');
  });

  it('requires exactly one of code or invitationId', async () => {
    const wrapped = test.wrap(myFunctions.acceptGuestInvitation);
    await assert.rejects(
        () => wrapped({}, {auth: {uid: 'nina', token: {email: 'nina@example.com'}}}),
        (err) => err.code === 'invalid-argument');
  });

  it('makes the accepter a guest of the child they were invited to', async () => {
    const db = seeded();

    const result = await myFunctions.acceptGuestInvitationImpl(
        db, 'nina', 'nina@example.com', ref);

    const child = db._docs.child_info.child1;
    assert.deepStrictEqual(Object.keys(child.guests), ['nina']);
    assert.strictEqual(child.guests.nina.name, 'Nina');
    assert.strictEqual(child.guests.nina.grantedBy, 'alice');
    assert.strictEqual(child.guests.nina.expiresAtMillis, GRANT_ENDS);
    assert.ok(child.guests.nina.grantedAtMillis > 0, 'the grant must record when it was made');
    assert.deepStrictEqual(result, {childInfoId: 'child1', expiresAtMillis: GRANT_ENDS});
  });

  it('puts the guest in sharedWith, keeping both parents', async () => {
    const db = seeded();

    await myFunctions.acceptGuestInvitationImpl(db, 'nina', 'nina@example.com', ref);

    assert.deepStrictEqual(
        db._docs.child_info.child1.sharedWith, ['alice', 'bob', 'nina'],
        'the guest is added to the audience, not substituted into it');
  });

  it('touches exactly the one child record it was invited to', async () => {
    const db = seeded();

    await myFunctions.acceptGuestInvitationImpl(db, 'nina', 'nina@example.com', ref);

    const other = db._docs.child_info.child2;
    assert.strictEqual(other.guests, undefined, 'a sibling record must not gain a guest');
    assert.deepStrictEqual(other.sharedWith, ['alice', 'bob']);
  });

  it('never writes partnerId or a parent slot on anybody', async () => {
    // The whole point of a second callable. A guest who came out of this holding a partnerId
    // or a slot is a co-parent: they would see every event, expense and message, and they
    // would occupy one of the two parent colours.
    const db = seeded();

    await myFunctions.acceptGuestInvitationImpl(db, 'nina', 'nina@example.com', ref);

    assert.strictEqual(db._docs.users.nina.partnerId, undefined);
    assert.strictEqual(db._docs.users.nina.role, undefined);
    assert.strictEqual(db._docs.users.alice.partnerId, 'bob', 'Alice keeps her real co-parent');
    assert.strictEqual(db._docs.users.alice.role, 'mom');
    assert.strictEqual(db._docs.users.bob.partnerId, 'alice');
  });

  it('marks the invitation accepted', async () => {
    const db = seeded();

    await myFunctions.acceptGuestInvitationImpl(db, 'nina', 'nina@example.com', ref);

    assert.strictEqual(db._docs.invitations.inv1.status, 'accepted');
    assert.strictEqual(db._docs.invitations.inv1.acceptedBy, 'nina');
  });

  it('refuses a co-parent invitation', async () => {
    // Two callables exist so this can be a refusal rather than a branch. A pairing invite
    // redeemed here must not quietly become the weaker grant either.
    const db = seeded({kind: undefined});

    await assert.rejects(
        () => myFunctions.acceptGuestInvitationImpl(db, 'nina', 'nina@example.com', ref),
        (err) => err.details.reason === 'not-a-guest-invitation');
    assert.strictEqual(db._docs.child_info.child1.guests, undefined);
  });

  it('refuses a grant whose end has already passed', async () => {
    // Fail closed: an invite whose window elapsed before it was redeemed grants nothing.
    const db = seeded({guestExpiresAt: Date.parse('2020-01-01T00:00:00Z')});

    await assert.rejects(
        () => myFunctions.acceptGuestInvitationImpl(db, 'nina', 'nina@example.com', ref),
        (err) => err.details.reason === 'grant-expired');
    assert.strictEqual(db._docs.child_info.child1.guests, undefined);
  });

  it('refuses an invitation carrying no grant expiry at all', async () => {
    // The one default that must never be "forever".
    const db = seeded({guestExpiresAt: undefined});

    await assert.rejects(
        () => myFunctions.acceptGuestInvitationImpl(db, 'nina', 'nina@example.com', ref),
        (err) => err.details.reason === 'grant-expired');
  });

  it('refuses an invitation naming no child', async () => {
    const db = seeded({childInfoId: ''});

    await assert.rejects(
        () => myFunctions.acceptGuestInvitationImpl(db, 'nina', 'nina@example.com', ref),
        (err) => err.details.reason === 'invitation-malformed');
  });

  it('refuses an invitation naming a child that does not exist', async () => {
    const db = seeded({childInfoId: 'ghost'});

    await assert.rejects(
        () => myFunctions.acceptGuestInvitationImpl(db, 'nina', 'nina@example.com', ref),
        (err) => err.code === 'not-found');
  });

  it('refuses an inviter who has no access to the record themselves', async () => {
    // Nothing stops a client writing an invitation naming somebody else's child id. The
    // rules shape that document; this is what stops it meaning anything.
    const db = seeded({fromUserId: 'stranger'});

    await assert.rejects(
        () => myFunctions.acceptGuestInvitationImpl(db, 'nina', 'nina@example.com', ref),
        (err) => err.details.reason === 'inviter-not-entitled');
    assert.strictEqual(db._docs.child_info.child1.guests, undefined);
  });

  it('refuses an inviter who is themselves only a guest', async () => {
    // A guest passing their access on is how a thirty-day grant becomes permanent: each
    // hand-off restarts the clock and no parent ever sees who is actually reading.
    const db = seeded({fromUserId: 'uncle'});
    db._docs.child_info.child1.sharedWith.push('uncle');
    db._docs.child_info.child1.guests = {
      uncle: {name: 'Uncle', grantedBy: 'alice', grantedAtMillis: 1, expiresAtMillis: GRANT_ENDS},
    };

    await assert.rejects(
        () => myFunctions.acceptGuestInvitationImpl(db, 'nina', 'nina@example.com', ref),
        (err) => err.details.reason === 'inviter-not-entitled');
  });

  it('refuses the inviter accepting their own invitation', async () => {
    const db = seeded();

    await assert.rejects(
        () => myFunctions.acceptGuestInvitationImpl(db, 'alice', 'alice@example.com', ref),
        (err) => err.details.reason === 'self-pairing');
  });

  it('refuses an invitation that is no longer pending', async () => {
    const db = seeded({status: 'cancelled'});

    await assert.rejects(
        () => myFunctions.acceptGuestInvitationImpl(db, 'nina', 'nina@example.com', ref),
        (err) => err.details.reason === 'invitation-not-pending');
  });

  it('refuses an invitation addressed to a different email', async () => {
    const db = seeded({toEmail: 'someone@else.example'});

    await assert.rejects(
        () => myFunctions.acceptGuestInvitationImpl(db, 'nina', 'nina@example.com', ref),
        (err) => err.details.reason === 'wrong-recipient');
  });

  it('names a guest whose profile has none, rather than storing a blank', async () => {
    // `ChildInfoGuests.decode` drops a grant with no name, so a blank one would be a guest
    // the rules serve and the app cannot show. Falling back to the email keeps the two
    // halves agreeing about who is in the record.
    const db = seeded();
    delete db._docs.users.nina.name;

    await myFunctions.acceptGuestInvitationImpl(db, 'nina', 'nina@example.com', ref);

    assert.strictEqual(db._docs.child_info.child1.guests.nina.name, 'nina@example.com');
  });

  it('re-accepting is refused, so a second grant cannot overwrite the first', async () => {
    const db = seeded();
    await myFunctions.acceptGuestInvitationImpl(db, 'nina', 'nina@example.com', ref);

    await assert.rejects(
        () => myFunctions.acceptGuestInvitationImpl(db, 'nina', 'nina@example.com', ref),
        (err) => err.details.reason === 'invitation-not-pending');
  });

  it('tells the inviter their guest arrived', async () => {
    const db = seeded();

    await myFunctions.acceptGuestInvitationImpl(db, 'nina', 'nina@example.com', ref);

    const queued = db._added.filter((a) => a.collection === 'notification_queue');
    assert.strictEqual(queued.length, 1);
    assert.strictEqual(queued[0].data.targetUserId, 'alice');
    assert.strictEqual(queued[0].data.data.type, 'guest_accepted');
  });
});

describe('acceptPairingInvitation refuses a guest invitation', () => {
  let myFunctions;

  before(() => {
    myFunctions = require('../index');
  });

  it('does not pair the two accounts', async () => {
    // The dangerous direction. A guest invite redeemed through the pairing callable would
    // run `assignSlots` and write `partnerId` on both users — a grandmother would become a
    // co-parent, with a parent colour and a full view of the family.
    // Both accounts unpaired, so nothing but the `kind` check can stop this pairing.
    const db = seeded();
    delete db._docs.users.alice.partnerId;
    delete db._docs.users.bob.partnerId;

    await assert.rejects(
        () => myFunctions.acceptPairingInvitationImpl(db, 'nina', 'nina@example.com',
            {code: null, invitationId: 'inv1'}),
        (err) => err.details.reason === 'guest-invitation');
    assert.strictEqual(db._docs.users.alice.partnerId, undefined);
    assert.strictEqual(db._docs.users.nina.partnerId, undefined);
    assert.strictEqual(db._docs.users.nina.role, undefined);
    assert.strictEqual(db._docs.invitations.inv1.status, 'pending');
  });
});
