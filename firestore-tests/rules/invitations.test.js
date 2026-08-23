/**
 * Part 1d — the `invitations` block (pairing).
 *
 * The document shape is `PairingRepositoryImpl.writeNewInvite`. Redemption goes through
 * the `acceptPairingInvitation` callable, which uses Admin credentials and bypasses
 * rules, so clients never need to read an invitation addressed to somebody else — the
 * rules deliberately do not allow a lookup by code.
 */

const {
  CURRENT_RULES, testEnv, seed, assertSucceeds, assertFails,
} = require('../harness');

const PROJECT = 'demo-coplanly-invitations';
const ALICE = 'alice-uid';
const ALICE_EMAIL = 'alice@x.test';
const BOB = 'bob-uid';
const BOB_EMAIL = 'bob@x.test';
const CAROL = 'carol-uid';
const CAROL_EMAIL = 'carol@x.test';

/**
 * Builds an invitation document as `writeNewInvite` writes it.
 *
 * @param {!Object} overrides Fields to override on the default document.
 * @return {!Object} The document data.
 */
function inviteDoc(overrides) {
  return Object.assign({
    id: 'invite-1',
    code: 'K7M2QX',
    fromUserId: ALICE,
    fromUserName: 'Alice',
    fromUserEmail: ALICE_EMAIL,
    toEmail: BOB_EMAIL,
    status: 'pending',
    createdAt: 1754000000000,
    expiresAt: 1754600000000,
    acceptedBy: null,
  }, overrides);
}

/**
 * Authenticated context carrying an email claim, as Firebase Auth issues.
 *
 * @param {!Object} env Rules test environment.
 * @param {string} uid Firebase uid.
 * @param {string} email Verified email claim.
 * @return {!Object} Firestore instance.
 */
function asUser(env, uid, email) {
  return env.authenticatedContext(uid, {email, email_verified: true}).firestore();
}

describe('Part 1d: invitations', () => {
  let env;

  before(async () => {
    env = await testEnv(PROJECT, CURRENT_RULES);
  });

  beforeEach(async () => {
    await env.clearFirestore();
  });

  describe('create', () => {
    it('allows the inviter to mint their own code invite', async () => {
      const db = asUser(env, ALICE, ALICE_EMAIL);
      await assertSucceeds(db.doc('invitations/invite-1').set(inviteDoc({toEmail: ''})));
    });

    it('allows the inviter to mint an email invite', async () => {
      const db = asUser(env, ALICE, ALICE_EMAIL);
      await assertSucceeds(db.doc('invitations/invite-1').set(inviteDoc({})));
    });

    it('denies minting an invite on somebody else behalf', async () => {
      const db = asUser(env, CAROL, CAROL_EMAIL);
      await assertFails(db.doc('invitations/invite-1').set(inviteDoc({})));
    });

    it('denies a code that is not six characters', async () => {
      const db = asUser(env, ALICE, ALICE_EMAIL);
      await assertFails(db.doc('invitations/invite-1').set(inviteDoc({code: 'K7M2'})));
    });

    it('denies minting an invite that is already accepted', async () => {
      const db = asUser(env, ALICE, ALICE_EMAIL);
      await assertFails(db.doc('invitations/invite-1').set(inviteDoc({status: 'accepted'})));
    });

    it('denies a document missing a required key', async () => {
      const doc = inviteDoc({});
      delete doc.expiresAt;
      const db = asUser(env, ALICE, ALICE_EMAIL);
      await assertFails(db.doc('invitations/invite-1').set(doc));
    });
  });

  describe('read', () => {
    beforeEach(async () => {
      await seed(env, {'invitations/invite-1': inviteDoc({})});
    });

    it('allows the inviter', async () => {
      await assertSucceeds(asUser(env, ALICE, ALICE_EMAIL).doc('invitations/invite-1').get());
    });

    it('allows the addressed recipient', async () => {
      await assertSucceeds(asUser(env, BOB, BOB_EMAIL).doc('invitations/invite-1').get());
    });

    it('denies an unrelated user', async () => {
      await assertFails(asUser(env, CAROL, CAROL_EMAIL).doc('invitations/invite-1').get());
    });

    it('serves the inviter own-invites listener query', async () => {
      const db = asUser(env, ALICE, ALICE_EMAIL);
      await assertSucceeds(db.collection('invitations')
          .where('fromUserId', '==', ALICE).where('status', '==', 'pending').get());
    });

    it('serves the recipient incoming-invites listener query', async () => {
      const db = asUser(env, BOB, BOB_EMAIL);
      await assertSucceeds(db.collection('invitations')
          .where('toEmail', '==', BOB_EMAIL).where('status', '==', 'pending').get());
    });

    it('denies a lookup by code, which would leak other people invites', async () => {
      const db = asUser(env, CAROL, CAROL_EMAIL);
      await assertFails(db.collection('invitations').where('code', '==', 'K7M2QX').get());
    });

    it('denies an email query for somebody else address', async () => {
      const db = asUser(env, CAROL, CAROL_EMAIL);
      await assertFails(db.collection('invitations')
          .where('toEmail', '==', BOB_EMAIL).where('status', '==', 'pending').get());
    });
  });

  describe('update', () => {
    beforeEach(async () => {
      await seed(env, {'invitations/invite-1': inviteDoc({})});
    });

    it('lets the inviter withdraw their invite', async () => {
      await assertSucceeds(asUser(env, ALICE, ALICE_EMAIL)
          .doc('invitations/invite-1').update({status: 'cancelled'}));
    });

    it('lets the recipient decline', async () => {
      await assertSucceeds(asUser(env, BOB, BOB_EMAIL)
          .doc('invitations/invite-1').update({status: 'rejected'}));
    });

    it('denies the inviter self-accepting (that is the callable job)', async () => {
      await assertFails(asUser(env, ALICE, ALICE_EMAIL)
          .doc('invitations/invite-1').update({status: 'accepted'}));
    });

    it('denies the recipient accepting client-side', async () => {
      await assertFails(asUser(env, BOB, BOB_EMAIL)
          .doc('invitations/invite-1').update({status: 'accepted'}));
    });

    it('denies rewriting the code alongside a cancel', async () => {
      await assertFails(asUser(env, ALICE, ALICE_EMAIL)
          .doc('invitations/invite-1').update({status: 'cancelled', code: 'AAAAAA'}));
    });

    it('denies extending the expiry alongside a cancel', async () => {
      await assertFails(asUser(env, ALICE, ALICE_EMAIL)
          .doc('invitations/invite-1').update({status: 'cancelled', expiresAt: 9999999999999}));
    });

    it('denies re-cancelling an already cancelled invite', async () => {
      await seed(env, {'invitations/invite-2': inviteDoc({id: 'invite-2', status: 'cancelled'})});
      await assertFails(asUser(env, ALICE, ALICE_EMAIL)
          .doc('invitations/invite-2').update({status: 'cancelled'}));
    });

    it('denies an unrelated user', async () => {
      await assertFails(asUser(env, CAROL, CAROL_EMAIL)
          .doc('invitations/invite-1').update({status: 'rejected'}));
    });
  });

  describe('Package G2: a guest invitation is a different shape, not a different value', () => {
    /**
     * A guest invitation as `PairingRepositoryImpl.writeGuestInvite` writes it.
     *
     * @param {!Object} overrides Fields to override.
     * @return {!Object} The document data.
     */
    function guestInvite(overrides) {
      return inviteDoc(Object.assign({
        toEmail: '',
        kind: 'guest',
        childInfoId: 'child-1',
        guestExpiresAt: Date.parse('2099-01-01T00:00:00Z'),
      }, overrides));
    }

    it('allows a parent to mint one', async () => {
      const db = asUser(env, ALICE, ALICE_EMAIL);
      await assertSucceeds(db.doc('invitations/invite-1').set(guestInvite({})));
    });

    it('still allows an invitation carrying no kind at all', async () => {
      // Every invitation written before guests existed. If this ever fails, pairing has
      // stopped working for anybody upgrading with a code already in flight.
      const db = asUser(env, ALICE, ALICE_EMAIL);
      await assertSucceeds(db.doc('invitations/invite-1').set(inviteDoc({toEmail: ''})));
    });

    it('denies a kind this file does not understand', async () => {
      // Fail closed on shape: an unrecognised kind must not fall through to "co-parent".
      const db = asUser(env, ALICE, ALICE_EMAIL);
      await assertFails(db.doc('invitations/invite-1').set(guestInvite({kind: 'sibling'})));
    });

    it('denies a guest invitation naming no child', async () => {
      const db = asUser(env, ALICE, ALICE_EMAIL);
      await assertFails(db.doc('invitations/invite-1').set(guestInvite({childInfoId: ''})));
    });

    it('denies a guest invitation with no child field at all', async () => {
      const doc = guestInvite({});
      delete doc.childInfoId;
      const db = asUser(env, ALICE, ALICE_EMAIL);
      await assertFails(db.doc('invitations/invite-1').set(doc));
    });

    it('denies a guest invitation with no end to the access', async () => {
      // The one default this feature must never have is "forever".
      const doc = guestInvite({});
      delete doc.guestExpiresAt;
      const db = asUser(env, ALICE, ALICE_EMAIL);
      await assertFails(db.doc('invitations/invite-1').set(doc));
    });

    it('denies a guest invitation whose access already ended', async () => {
      const db = asUser(env, ALICE, ALICE_EMAIL);
      await assertFails(db.doc('invitations/invite-1').set(
          guestInvite({guestExpiresAt: Date.parse('2020-01-01T00:00:00Z')})));
    });

    it('denies an expiry that is not a number', async () => {
      const db = asUser(env, ALICE, ALICE_EMAIL);
      await assertFails(db.doc('invitations/invite-1').set(
          guestInvite({guestExpiresAt: '2099-01-01T00:00:00Z'})));
    });

    it('still denies minting one on somebody else behalf', async () => {
      const db = asUser(env, CAROL, CAROL_EMAIL);
      await assertFails(db.doc('invitations/invite-1').set(guestInvite({})));
    });
  });

  describe('delete', () => {
    beforeEach(async () => {
      await seed(env, {'invitations/invite-1': inviteDoc({})});
    });

    it('allows the inviter', async () => {
      await assertSucceeds(asUser(env, ALICE, ALICE_EMAIL).doc('invitations/invite-1').delete());
    });

    it('denies the recipient', async () => {
      await assertFails(asUser(env, BOB, BOB_EMAIL).doc('invitations/invite-1').delete());
    });
  });
});
