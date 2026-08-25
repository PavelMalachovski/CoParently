const test = require('firebase-functions-test')();
const assert = require('assert');

/**
 * The Google OAuth token exchange (SEC-1 §2).
 *
 * The web OAuth client's secret used to be compiled into every APK, so anyone who installed the
 * app could mint tokens against the project's client. It now lives only in the functions'
 * environment, and these pin the two properties that make that worth doing: the secret reaches
 * Google and nothing else, and the refresh grant is not an oracle that will refresh anybody's
 * token for anybody.
 */

/**
 * A fake Firestore holding just `google_oauth/{uid}`.
 *
 * @param {!Object=} seed Documents keyed by collection then id.
 * @return {!Object} The fake, carrying `_docs` for assertions.
 */
function fakeDb(seed) {
  const docs = JSON.parse(JSON.stringify(seed || {}));
  return {
    _docs: docs,
    collection(name) {
      return {
        doc: (id) => ({
          async get() {
            const data = (docs[name] || {})[id];
            return {exists: data !== undefined, data: () => data};
          },
          async set(value) {
            docs[name] = docs[name] || {};
            docs[name][id] = value;
          },
        }),
      };
    },
  };
}

const CONFIG = {clientId: 'client-id.apps.googleusercontent.com', clientSecret: 's3cret'};
const ALICE = 'uid-alice';
const REFRESH = 'refresh-token-value';

/**
 * Records what was posted and answers with [response].
 *
 * @param {!Object} response What Google is pretending to say.
 * @return {function(!Object): !Promise<!Object>} The post function, carrying `calls`.
 */
function recordingPost(response) {
  const calls = [];
  const post = async (form) => {
    calls.push(form);
    return response;
  };
  post.calls = calls;
  return post;
}

describe('exchangeGoogleAuthCode', () => {
  let index;

  before(() => {
    index = require('../index');
  });

  after(() => {
    test.cleanup();
  });

  it('sends the secret to Google and hands the tokens back', async () => {
    const db = fakeDb({});
    const post = recordingPost({
      access_token: 'access', refresh_token: REFRESH, expires_in: 3599,
    });

    const result = await index.exchangeGoogleAuthCodeImpl(
        db, CONFIG, post, ALICE, 'auth-code');

    assert.strictEqual(post.calls[0].client_secret, 's3cret');
    assert.strictEqual(post.calls[0].grant_type, 'authorization_code');
    assert.strictEqual(post.calls[0].code, 'auth-code');
    assert.deepStrictEqual(result, {
      accessToken: 'access', refreshToken: REFRESH, expiresInSeconds: 3599,
    });
  });

  it('records a fingerprint of the refresh token, never the token', async () => {
    // The store exists so a stolen token cannot be used; storing the token itself would make
    // the store worth stealing.
    const db = fakeDb({});

    await index.exchangeGoogleAuthCodeImpl(
        db, CONFIG, recordingPost({access_token: 'a', refresh_token: REFRESH, expires_in: 1}),
        ALICE, 'auth-code');

    const stored = db._docs.google_oauth[ALICE];
    assert.strictEqual(stored.refreshTokenHash, index.refreshTokenFingerprint(REFRESH));
    assert.strictEqual(JSON.stringify(stored).includes(REFRESH), false);
  });

  it('records nothing when Google returns no refresh token', async () => {
    // Google omits it when the account has already granted consent and the app did not ask for
    // offline access again. Overwriting the recorded fingerprint with nothing would lock the
    // account out of its own working token.
    const db = fakeDb({google_oauth: {[ALICE]: {refreshTokenHash: 'previous'}}});

    await index.exchangeGoogleAuthCodeImpl(
        db, CONFIG, recordingPost({access_token: 'a', expires_in: 1}), ALICE, 'auth-code');

    assert.strictEqual(db._docs.google_oauth[ALICE].refreshTokenHash, 'previous');
  });

  it('fails loudly when Google returns no access token', async () => {
    await assert.rejects(
        () => index.exchangeGoogleAuthCodeImpl(
            fakeDb({}), CONFIG, recordingPost({}), ALICE, 'auth-code'),
        (err) => err.details && err.details.reason === 'no-access-token');
  });
});

describe('refreshGoogleAccessToken', () => {
  let index;

  before(() => {
    index = require('../index');
  });

  it('refreshes the token this account was issued', async () => {
    const db = fakeDb({
      google_oauth: {[ALICE]: {refreshTokenHash: require('crypto')
          .createHash('sha256').update(REFRESH).digest('hex')}},
    });
    const post = recordingPost({access_token: 'fresh', expires_in: 3599});

    const result = await index.refreshGoogleAccessTokenImpl(db, CONFIG, post, ALICE, REFRESH);

    assert.strictEqual(post.calls[0].grant_type, 'refresh_token');
    assert.strictEqual(post.calls[0].client_secret, 's3cret');
    assert.deepStrictEqual(result, {accessToken: 'fresh', expiresInSeconds: 3599});
  });

  it('refuses a refresh token issued to somebody else', async () => {
    // Without this the function would be an oracle turning any stolen refresh token into an
    // access token — exactly the capability taking the secret out of the APK removes.
    const db = fakeDb({
      google_oauth: {[ALICE]: {refreshTokenHash: index.refreshTokenFingerprint('mine')}},
    });
    const post = recordingPost({access_token: 'fresh', expires_in: 1});

    await assert.rejects(
        () => index.refreshGoogleAccessTokenImpl(db, CONFIG, post, ALICE, 'somebody-elses'),
        (err) => err.code === 'permission-denied' &&
                 err.details.reason === 'unknown-refresh-token');
    assert.strictEqual(post.calls.length, 0, 'Google is never asked');
  });

  it('refuses a token with no fingerprint rather than trusting it on first use', async () => {
    // Trust-on-first-use would let whoever presents a stolen token first bind it to themselves.
    // The cost is one re-consent for an account whose Calendar was connected before this
    // shipped, which is a screen the app already prompts for when a refresh fails.
    const post = recordingPost({access_token: 'fresh', expires_in: 1});

    await assert.rejects(
        () => index.refreshGoogleAccessTokenImpl(fakeDb({}), CONFIG, post, ALICE, REFRESH),
        (err) => err.details.reason === 'unknown-refresh-token');
    assert.strictEqual(post.calls.length, 0);
  });
});

describe('googleOAuthConfig', () => {
  let index;

  before(() => {
    index = require('../index');
  });

  afterEach(() => {
    delete process.env.GOOGLE_OAUTH_CLIENT_ID;
    delete process.env.GOOGLE_OAUTH_CLIENT_SECRET;
  });

  it('is null until both halves are set, so a deployment is visibly unconfigured', () => {
    assert.strictEqual(index.googleOAuthConfig(), null);

    process.env.GOOGLE_OAUTH_CLIENT_ID = 'id';
    assert.strictEqual(index.googleOAuthConfig(), null, 'an id alone is not a client');

    process.env.GOOGLE_OAUTH_CLIENT_SECRET = 'secret';
    assert.deepStrictEqual(
        index.googleOAuthConfig(), {clientId: 'id', clientSecret: 'secret'});
  });

  it('is read fresh, so a re-deploy can change it', () => {
    // Bound at module load, the value would be one a re-deploy could not move — the reason
    // `backfillAdminUids` reads its own env on every call.
    process.env.GOOGLE_OAUTH_CLIENT_ID = 'first';
    process.env.GOOGLE_OAUTH_CLIENT_SECRET = 'secret';
    assert.strictEqual(index.googleOAuthConfig().clientId, 'first');

    process.env.GOOGLE_OAUTH_CLIENT_ID = 'second';
    assert.strictEqual(index.googleOAuthConfig().clientId, 'second');
  });
});
