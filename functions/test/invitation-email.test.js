const assert = require('assert');

/**
 * Delivering an invitation email.
 *
 * The function this covers used to build the message, `console.log` it, and return — under a
 * `// TODO: Replace with actual email sending service`. So "Invite by email" created an
 * invitation nobody was told about while the inviter watched the field clear as though it had
 * worked. These cases pin the two things that must now hold: something is actually sent, and
 * whatever happens is *recorded* rather than swallowed.
 *
 * @param {?Object} sender The inviting user's document, or null when it is missing.
 * @return {!Object} A Firestore-shaped fake exposing the invitation's writes as `_updates`.
 */
function fakeDb(sender) {
  return {
    collection() {
      return {
        doc() {
          return {
            async get() {
              return {exists: sender !== null, data: () => sender};
            },
          };
        },
      };
    },
  };
}

/**
 * An invitation reference that records every update applied to it.
 *
 * @return {!Object} A reference with an `_updates` array.
 */
function fakeRef() {
  const updates = [];
  return {_updates: updates, async update(patch) {
    updates.push(patch);
  }};
}

const INVITE = {
  code: 'ABC123',
  toEmail: 'other@example.com',
  fromUserId: 'alice',
  status: 'pending',
};

describe('deliverInvitationEmailImpl', () => {
  let myFunctions;

  before(() => {
    myFunctions = require('../index');
  });

  it('sends the message and records that it went', async () => {
    const sent = [];
    const ref = fakeRef();

    const outcome = await myFunctions.deliverInvitationEmailImpl(
        fakeDb({name: 'Alice'}), ref, INVITE, async (m) => {
          sent.push(m);
        });

    assert.strictEqual(outcome, myFunctions.EmailDelivery.SENT);
    assert.strictEqual(sent.length, 1);
    assert.strictEqual(sent[0].to, 'other@example.com');
    assert.strictEqual(ref._updates[0].emailDelivery, 'sent');
  });

  // The code is what a recipient without the app installed can actually use. A message
  // carrying only a deep link strands exactly the person it is addressed to.
  it('carries the invite code in both parts of the message', async () => {
    const sent = [];
    await myFunctions.deliverInvitationEmailImpl(
        fakeDb({name: 'Alice'}), fakeRef(), INVITE, async (m) => {
          sent.push(m);
        });

    assert.ok(sent[0].text.includes('ABC123'), 'plain-text part has no code');
    assert.ok(sent[0].html.includes('ABC123'), 'html part has no code');
    assert.ok(sent[0].subject.includes('Alice'), 'subject does not say who invited');
  });

  // Code, QR and share-link invitations carry no address, and this trigger fires for all of
  // them. Attempting delivery would be a guaranteed provider error on every pairing.
  it('does not attempt delivery for an invitation with no address', async () => {
    const sent = [];
    const ref = fakeRef();

    const outcome = await myFunctions.deliverInvitationEmailImpl(
        fakeDb({name: 'Alice'}), ref, Object.assign({}, INVITE, {toEmail: ''}),
        async (m) => {
          sent.push(m);
        });

    assert.strictEqual(outcome, myFunctions.EmailDelivery.NOT_APPLICABLE);
    assert.strictEqual(sent.length, 0);
  });

  // The defect this replaced: a bounced email wrote `status: 'failed'`, and `status` is what
  // every redemption path gates on — so a delivery problem destroyed a working invite code the
  // inviter could still have read out over the phone.
  it('records a failure without touching the invitation status', async () => {
    const ref = fakeRef();

    const outcome = await myFunctions.deliverInvitationEmailImpl(
        fakeDb({name: 'Alice'}), ref, INVITE,
        async () => {
          throw new Error('550 mailbox unavailable');
        });

    assert.strictEqual(outcome, myFunctions.EmailDelivery.FAILED);
    const patch = ref._updates[0];
    assert.strictEqual(patch.emailDelivery, 'failed');
    assert.ok(patch.emailDeliveryError.includes('550'));
    assert.ok(!('status' in patch), 'delivery failure must not change redeemability');
  });

  it('still sends when the sender profile is missing', async () => {
    const sent = [];
    const outcome = await myFunctions.deliverInvitationEmailImpl(
        fakeDb(null), fakeRef(), INVITE, async (m) => {
          sent.push(m);
        });

    assert.strictEqual(outcome, myFunctions.EmailDelivery.SENT);
    assert.ok(sent[0].subject.length > 0);
  });
});

describe('emailProviderConfig', () => {
  let myFunctions;
  let saved;

  before(() => {
    myFunctions = require('../index');
  });

  beforeEach(() => {
    saved = {
      key: process.env.SENDGRID_API_KEY,
      from: process.env.INVITE_FROM_EMAIL,
      name: process.env.INVITE_FROM_NAME,
    };
  });

  afterEach(() => {
    if (saved.key === undefined) delete process.env.SENDGRID_API_KEY;
    else process.env.SENDGRID_API_KEY = saved.key;
    if (saved.from === undefined) delete process.env.INVITE_FROM_EMAIL;
    else process.env.INVITE_FROM_EMAIL = saved.from;
    if (saved.name === undefined) delete process.env.INVITE_FROM_NAME;
    else process.env.INVITE_FROM_NAME = saved.name;
  });

  it('is null when nothing is configured', () => {
    delete process.env.SENDGRID_API_KEY;
    delete process.env.INVITE_FROM_EMAIL;
    assert.strictEqual(myFunctions.emailProviderConfig(), null);
  });

  // Half a configuration is not a configuration: a key with no from-address would fail on
  // every send, and the point of this check is that an unconfigured deployment says so.
  it('is null when only half of it is set', () => {
    process.env.SENDGRID_API_KEY = 'SG.test';
    delete process.env.INVITE_FROM_EMAIL;
    assert.strictEqual(myFunctions.emailProviderConfig(), null);
  });

  it('reads the environment fresh on every call', () => {
    process.env.SENDGRID_API_KEY = 'SG.test';
    process.env.INVITE_FROM_EMAIL = 'hello@coplanly.app';
    process.env.INVITE_FROM_NAME = 'CoPlanly';

    const config = myFunctions.emailProviderConfig();
    assert.strictEqual(config.apiKey, 'SG.test');
    assert.strictEqual(config.from, 'hello@coplanly.app');
    assert.strictEqual(config.fromName, 'CoPlanly');
  });
});
