/**
 * `storage.rules`, which had no test coverage of any kind until this file.
 *
 * That gap is not theoretical. `pet_photos/**` is in the ruleset in this repository and,
 * on the evidence, not in the deployed one, so every pet photo upload is refused in
 * production while the file here says it should succeed — see the `storage.rules` entry
 * under "Known issues" in CLAUDE.md. Nothing catches that, because `firebase.json`
 * configured a Firestore emulator only and Storage rules were never exercised.
 *
 * What these tests can and cannot do is worth stating, since the bug above is exactly the
 * difference: they prove the ruleset **in this repository** behaves as written. They say
 * nothing about what is deployed to the live bucket, which only `firebase deploy --only
 * storage` and a check against the console can settle.
 *
 * Deliberately not tested: the cross-service `firestore.get()` rule the file's header
 * argues for. The Storage emulator does not resolve cross-service calls
 * (firebase-js-sdk#6803), so a test of it would fail for a reason unrelated to the rule —
 * which is why `storage.rules` tells a future implementer to find another way to verify it
 * before deploying.
 */

const {ref, uploadBytes, getBytes, deleteObject} = require('firebase/storage');

const {
  storageTestEnv, assertSucceeds, assertFails,
} = require('../harness');

const PROJECT = 'demo-coplanly-storage';
const ALICE = 'alice-uid';
const BOB = 'bob-uid';

/** Under the 5 MB cap, so size is never the reason a test fails. */
const SMALL_JPEG = new Uint8Array(64).fill(7);

/**
 * Exactly the cap, which the rule spells `size < 5 * 1024 * 1024` — so this must be
 * refused. A megabyte over would also fail and would not tell us whether the comparison
 * is `<` or `<=`.
 */
const AT_CAP_JPEG = new Uint8Array(5 * 1024 * 1024).fill(7);

const JPEG = {contentType: 'image/jpeg'};

/**
 * Writes an object with the rules bypassed, so a read test fails on permission rather
 * than on the object not being there.
 *
 * @param {!Object} env Rules test environment.
 * @param {string} objectPath Path within the bucket.
 * @return {!Promise<void>} Resolves once the object exists.
 */
async function seedObject(env, objectPath) {
  await env.withSecurityRulesDisabled(async (ctx) => {
    await uploadBytes(ref(ctx.storage(), objectPath), SMALL_JPEG, JPEG);
  });
}

/**
 * Reads an object as the given user, or anonymously when uid is null.
 *
 * @param {!Object} env Rules test environment.
 * @param {?string} uid Caller, or null for an unauthenticated caller.
 * @param {string} objectPath Path within the bucket.
 * @return {!Promise} The pending read.
 */
function readAs(env, uid, objectPath) {
  const ctx = uid ? env.authenticatedContext(uid) : env.unauthenticatedContext();
  return getBytes(ref(ctx.storage(), objectPath));
}

/**
 * Uploads as the given user, or anonymously when uid is null.
 *
 * @param {!Object} env Rules test environment.
 * @param {?string} uid Caller, or null for an unauthenticated caller.
 * @param {string} objectPath Path within the bucket.
 * @param {!Uint8Array} bytes Payload.
 * @param {!Object} metadata Upload metadata; carries the content type.
 * @return {!Promise} The pending upload.
 */
function uploadAs(env, uid, objectPath, bytes = SMALL_JPEG, metadata = JPEG) {
  const ctx = uid ? env.authenticatedContext(uid) : env.unauthenticatedContext();
  return uploadBytes(ref(ctx.storage(), objectPath), bytes, metadata);
}

/**
 * Deletes as the given user, or anonymously when uid is null.
 *
 * @param {!Object} env Rules test environment.
 * @param {?string} uid Caller, or null for an unauthenticated caller.
 * @param {string} objectPath Path within the bucket.
 * @return {!Promise} The pending delete.
 */
function deleteAs(env, uid, objectPath) {
  const ctx = uid ? env.authenticatedContext(uid) : env.unauthenticatedContext();
  return deleteObject(ref(ctx.storage(), objectPath));
}

describe('storage.rules', function() {
  let env;

  before(async function() {
    env = await storageTestEnv(PROJECT);
  });

  beforeEach(async function() {
    await env.clearStorage();
  });

  // The four blocks carry identical rules, so they are exercised identically. Writing them
  // out per prefix rather than looping over one assertion keeps a failure naming the path
  // that broke — and `pet_photos` is here for a reason no loop would have recorded.
  const prefixes = [
    ['receipts', 'receipts/expense-1.jpg'],
    ['event_images', 'event_images/event-1.jpg'],
    ['medical_photos', 'medical_photos/child-1/photo-uuid.jpg'],
    ['pet_photos', 'pet_photos/pet-1/photo-uuid.jpg'],
  ];

  for (const [name, objectPath] of prefixes) {
    describe(name, function() {
      it('lets a signed-in user upload a JPEG', async function() {
        await assertSucceeds(uploadAs(env, ALICE, objectPath));
      });

      it('lets a signed-in user read it', async function() {
        await seedObject(env, objectPath);
        await assertSucceeds(readAs(env, ALICE, objectPath));
      });

      it('refuses an unauthenticated read', async function() {
        await seedObject(env, objectPath);
        await assertFails(readAs(env, null, objectPath));
      });

      it('refuses an unauthenticated upload', async function() {
        await assertFails(uploadAs(env, null, objectPath));
      });

      it('refuses a content type other than JPEG', async function() {
        await assertFails(
            uploadAs(env, ALICE, objectPath, SMALL_JPEG, {contentType: 'image/png'}));
      });

      it('refuses an upload at the 5 MB cap, which the rule excludes', async function() {
        await assertFails(uploadAs(env, ALICE, objectPath, AT_CAP_JPEG, JPEG));
      });

      // Not a bug being pinned — a documented consequence. `storage.rules` says at length
      // that a delete carries `request.resource == null` and every block admits it from any
      // signed-in user, so an ex-partner holding a path can delete another parent's object.
      // The test records the behaviour the file describes, so that changing it is a visible
      // change to this suite rather than a silent one.
      it('lets any signed-in user delete it, per the documented gap', async function() {
        await seedObject(env, objectPath);
        await assertSucceeds(deleteAs(env, BOB, objectPath));
      });

      it('refuses an unauthenticated delete', async function() {
        await seedObject(env, objectPath);
        await assertFails(deleteAs(env, null, objectPath));
      });
    });
  }

  describe('everything else is closed', function() {
    it('refuses an upload to an unmatched prefix', async function() {
      await assertFails(uploadAs(env, ALICE, 'avatars/alice.jpg'));
    });

    it('refuses a read from an unmatched prefix', async function() {
      await seedObject(env, 'avatars/alice.jpg');
      await assertFails(readAs(env, ALICE, 'avatars/alice.jpg'));
    });

    // `match /medical_photos/{childInfoId}/{fileName}` binds exactly two segments, so a
    // deeper path falls through to the closing `match /{allPaths=**}` and is refused. Worth
    // pinning: the app builds these paths from ids, and a future nested layout would be
    // denied in production while looking correct in the ruleset.
    it('refuses a path one segment deeper than medical_photos matches', async function() {
      await assertFails(
          uploadAs(env, ALICE, 'medical_photos/child-1/2026/photo.jpg'));
    });

    it('refuses a path one segment deeper than pet_photos matches', async function() {
      await assertFails(uploadAs(env, ALICE, 'pet_photos/pet-1/2026/photo.jpg'));
    });

    it('refuses the bucket root', async function() {
      await assertFails(uploadAs(env, ALICE, 'stray.jpg'));
    });
  });
});
