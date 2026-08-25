/**
 * Part 1b — the `isPartnerOf` helper, exercised across every shape a `users` document
 * can actually have in production.
 *
 * `FcmService.updateUserToken` writes `users/{uid}` with
 * `set(mapOf("fcmToken" to token), SetOptions.merge())`, so an account can end up with
 * a users document that exists but carries no `partnerId` key at all. Reading a missing
 * key in Firestore Rules raises an evaluation error rather than yielding null, and
 * `exists()` does not guard against that — only against a missing document.
 *
 * The helper is probed through two callers with different shapes:
 *   - `notification_queue` create, where `isPartnerOf` is the only thing that can grant
 *     access (the self-target disjunct is deliberately made false); and
 *   - `expenses` read, where it sits behind a creator check.
 */

const {
  CURRENT_RULES, testEnv, seed, assertSucceeds, assertFails,
} = require('../harness');

const PROJECT = 'demo-coplanly-partner';
const ALICE = 'alice-uid';
const BOB = 'bob-uid';
const CAROL = 'carol-uid';

/**
 * Attempts a notification_queue enqueue addressed at another user.
 *
 * Reaching `allow` requires `isPartnerOf(target)` to return true, because the
 * `targetUserId == request.auth.uid` disjunct is false whenever target != caller.
 *
 * @param {!Object} env Rules test environment.
 * @param {string} callerUid Authenticated caller.
 * @param {string} targetUid Notification addressee.
 * @return {!Promise} The pending write.
 */
function enqueueFor(env, callerUid, targetUid) {
  return env.authenticatedContext(callerUid).firestore()
      .collection('notification_queue').add({
        targetUserId: targetUid,
        data: {type: 'event_created', subject: 'Swimming', actorName: 'Alice'},
        createdAt: Date.now(),
        status: 'pending',
      });
}

describe('Part 1b: isPartnerOf against every users-document shape', () => {
  let env;

  before(async () => {
    env = await testEnv(PROJECT, CURRENT_RULES);
  });

  beforeEach(async () => {
    await env.clearFirestore();
  });

  describe('via notification_queue create (isPartnerOf is the only gate)', () => {
    it('grants when the target document carries a matching partnerId', async () => {
      await seed(env, {
        'users/alice-uid': {name: 'Alice', email: 'a@x.test', partnerId: BOB},
        'users/bob-uid': {name: 'Bob', email: 'b@x.test', partnerId: ALICE},
      });
      await assertSucceeds(enqueueFor(env, ALICE, BOB));
    });

    it('denies when the target partnerId points at somebody else', async () => {
      await seed(env, {
        'users/alice-uid': {name: 'Alice', email: 'a@x.test', partnerId: BOB},
        'users/bob-uid': {name: 'Bob', email: 'b@x.test', partnerId: CAROL},
      });
      await assertFails(enqueueFor(env, ALICE, BOB));
    });

    it('denies when the target partnerId is an empty string', async () => {
      await seed(env, {
        'users/alice-uid': {name: 'Alice', email: 'a@x.test', partnerId: ''},
        'users/bob-uid': {name: 'Bob', email: 'b@x.test', partnerId: ''},
      });
      await assertFails(enqueueFor(env, ALICE, BOB));
    });

    it('denies when the target document has NO partnerId key (the FcmService shape)', async () => {
      await seed(env, {
        'users/alice-uid': {name: 'Alice', email: 'a@x.test', partnerId: BOB},
        'users/bob-uid': {fcmToken: 'token-bob'},
      });
      await assertFails(enqueueFor(env, ALICE, BOB));
    });

    it('denies when the target document does not exist', async () => {
      await seed(env, {'users/alice-uid': {name: 'Alice', email: 'a@x.test', partnerId: BOB}});
      await assertFails(enqueueFor(env, ALICE, BOB));
    });
  });

  describe('via child_info update (isPartnerOf behind a creator check)', () => {
    // This block used to probe `expenses` read, which no longer consults `isPartnerOf` at all:
    // an expense is now gated on membership of its own family, so a second co-parent cannot
    // see the first one's money (docs/DESIGN-multi-family.md, M-4). The shapes it pins are
    // still worth pinning, so it moved to a rule that does still ask the question —
    // `child_info` update, where `isPartnerOf(creator)` sits behind a creator check exactly as
    // it did there.

    /**
     * Seeds one child record owned by Alice and shared with Bob, plus the supplied users.
     *
     * @param {!Object<string, !Object>} users Documents keyed by `users/{uid}`.
     * @return {!Promise<void>} Resolves when seeded.
     */
    function seedChild(users) {
      return seed(env, Object.assign({
        'child_info/child-1': {
          id: 'child-1', childName: 'Ema',
          createdByFirebaseUid: ALICE, sharedWith: [ALICE, BOB],
        },
      }, users));
    }

    it('lets the co-parent write when both documents are linked', async () => {
      await seedChild({
        'users/alice-uid': {name: 'Alice', email: 'a@x.test', partnerId: BOB},
        'users/bob-uid': {name: 'Bob', email: 'b@x.test', partnerId: ALICE},
      });
      const db = env.authenticatedContext(BOB).firestore();
      await assertSucceeds(
          db.doc('child_info/child-1').update({childName: 'Ema N.'}));
    });

    it('still lets the creator write when their own document has no partnerId key', async () => {
      // The creator disjunct is true; the isPartnerOf disjunct raises an evaluation error on
      // the missing key. This pins the error-absorption behaviour the `expenses` delete
      // incident hinged on — a rule that errors denies, so a disjunction must reach its true
      // branch first.
      await seedChild({'users/alice-uid': {fcmToken: 'token-alice'}});
      const db = env.authenticatedContext(ALICE).firestore();
      await assertSucceeds(
          db.doc('child_info/child-1').update({childName: 'Ema N.'}));
    });

    it('denies a co-parent when the creator document has no partnerId key', async () => {
      await seedChild({
        'users/alice-uid': {fcmToken: 'token-alice'},
        'users/bob-uid': {name: 'Bob', email: 'b@x.test', partnerId: ALICE},
      });
      const db = env.authenticatedContext(BOB).firestore();
      await assertFails(
          db.doc('child_info/child-1').update({childName: 'Ema N.'}));
    });

    it('denies a stranger', async () => {
      await seedChild({
        'users/alice-uid': {name: 'Alice', email: 'a@x.test', partnerId: BOB},
        'users/carol-uid': {name: 'Carol', email: 'c@x.test', partnerId: ''},
      });
      const db = env.authenticatedContext(CAROL).firestore();
      await assertFails(
          db.doc('child_info/child-1').update({childName: 'Ema N.'}));
    });
  });

  describe('users profile reads', () => {
    it('lets a partner read the linked profile', async () => {
      await seed(env, {
        'users/alice-uid': {name: 'Alice', email: 'a@x.test', partnerId: BOB},
        'users/bob-uid': {name: 'Bob', email: 'b@x.test', partnerId: ALICE},
      });
      const db = env.authenticatedContext(ALICE).firestore();
      await assertSucceeds(db.doc('users/bob-uid').get());
    });

    it('denies reading a profile with no partnerId key', async () => {
      await seed(env, {
        'users/alice-uid': {name: 'Alice', email: 'a@x.test', partnerId: BOB},
        'users/bob-uid': {fcmToken: 'token-bob'},
      });
      const db = env.authenticatedContext(ALICE).firestore();
      await assertFails(db.doc('users/bob-uid').get());
    });

    it('lets a user read and merge-write their own profile', async () => {
      await seed(env, {'users/alice-uid': {name: 'Alice', email: 'a@x.test', partnerId: ''}});
      const db = env.authenticatedContext(ALICE).firestore();
      await assertSucceeds(db.doc('users/alice-uid').get());
      await assertSucceeds(db.doc('users/alice-uid').set({fcmToken: 'tok'}, {merge: true}));
    });
  });
});
