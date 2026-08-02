const test = require('firebase-functions-test')();
const assert = require('assert');
const sinon = require('sinon');

describe('buildFcmMessage', () => {
  let buildFcmMessage;

  before(() => {
    buildFcmMessage = require('../index').buildFcmMessage;
  });

  after(() => {
    test.cleanup();
    sinon.restore();
  });

  it('is data-only: there is no top-level notification key', () => {
    // A top-level `notification` block makes the OS auto-display the push from the
    // system tray while the app is backgrounded or killed, and onMessageReceived is
    // never called - which would strip the deep link, the icon and the per-type
    // notification id the client builds itself.
    const message = buildFcmMessage('token-1', {title: 'T', body: 'B'});

    assert.ok(!('notification' in message), 'message must not carry a notification block');
    assert.strictEqual(message.token, 'token-1');
  });

  it('sets android.priority to high', () => {
    // High priority is what makes a data-only message reach onMessageReceived
    // uniformly in all three app states.
    const message = buildFcmMessage('token-1', {title: 'T', body: 'B'});

    assert.strictEqual(message.android.priority, 'high');
  });

  it('coerces every data value to a string', () => {
    const message = buildFcmMessage('token-1', {
      title: 'T',
      body: 'B',
      eventId: 42,
      unread: true,
      ratio: 1.5,
    });

    Object.keys(message.data).forEach((key) => {
      assert.strictEqual(
          typeof message.data[key],
          'string',
          `data.${key} must be a string, got ${typeof message.data[key]}`,
      );
    });
    assert.strictEqual(message.data.eventId, '42');
    assert.strictEqual(message.data.unread, 'true');
    assert.strictEqual(message.data.ratio, '1.5');
  });

  it('coerces a non-string title and body rather than throwing', () => {
    // title/body were the only two values excluded from the String(...) coercion, so a
    // queue document with a non-string title made admin.messaging().send throw and the
    // push was lost.
    const message = buildFcmMessage('token-1', {title: 7, body: {a: 1}});

    assert.strictEqual(message.data.title, '7');
    assert.strictEqual(typeof message.data.body, 'string');
  });

  it('defaults a missing title and body to empty strings, not "undefined"', () => {
    const message = buildFcmMessage('token-1', {type: 'event_created'});

    assert.strictEqual(message.data.title, '');
    assert.strictEqual(message.data.body, '');
  });

  it('tolerates a missing data payload entirely', () => {
    const message = buildFcmMessage('token-1', undefined);

    assert.strictEqual(message.data.title, '');
    assert.strictEqual(message.data.type, 'general');
  });

  it('falls back to the general type when type is absent or empty', () => {
    assert.strictEqual(buildFcmMessage('t', {title: 'T'}).data.type, 'general');
    assert.strictEqual(buildFcmMessage('t', {title: 'T', type: ''}).data.type, 'general');
    assert.strictEqual(buildFcmMessage('t', {title: 'T', type: 'pairing_accepted'}).data.type, 'pairing_accepted');
  });
});
