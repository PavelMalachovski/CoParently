/**
 * Part 1a — reproduction of the "creator cannot delete their own expense" incident.
 *
 * Commit b2bf6b83 widened the `expenses` delete rule from creator-only to
 * `creator || isPartnerOf(creator)`. A device sweep after that deploy reported
 * PERMISSION_DENIED on the *creator's own* delete, and 413c0e61 reverted the clause.
 * The mechanism was never established, and the deploy carried a second, unreverted
 * change (`notification_queue`), so the field attribution was not clean.
 *
 * The matrix below runs the identical delete scenarios against BOTH rulesets — the
 * b2bf6b83 one and the reverted one that ships today — so any behavioural difference
 * between them is visible directly rather than inferred from a device.
 *
 * Note that `isPartnerOf(resource.data.createdByFirebaseUid)` reads the *caller's own*
 * profile when the caller is the creator, so every shape that profile can have in
 * production is covered, including the key-less one FcmService leaves behind.
 */

const {
  CURRENT_RULES, INCIDENT_RULES, testEnv, seed, assertSucceeds, assertFails,
} = require('../harness');

const CREATOR = 'creator-uid';
const PARTNER = 'partner-uid';
const STRANGER = 'stranger-uid';

const RULESETS = [
  {name: 'b2bf6b83 (incident: delete admits the co-parent)',
    project: 'demo-coplanly-incident', path: INCIDENT_RULES, coParentDelete: 'allow'},
  {name: 'current (reverted: delete admits the creator only)',
    project: 'demo-coplanly-current-expenses', path: CURRENT_RULES, coParentDelete: 'deny'},
];

/**
 * Builds an expense document as `ExpenseRepositoryImpl.expenseToFirestoreMap` writes it.
 *
 * @param {string} ownerUid Value for `createdByFirebaseUid`.
 * @return {!Object} The document data.
 */
function expenseDoc(ownerUid) {
  return {
    id: 'expense-1',
    childId: '',
    title: 'School trip',
    amount: 42.5,
    currency: 'CZK',
    category: 'EDUCATION',
    createdByFirebaseUid: ownerUid,
    familyId: [CREATOR, PARTNER].sort().join('__'),
    paidBy: 'MOM',
    splitBetween: ['MOM', 'DAD'],
    date: '2026-08-01',
    receiptUrl: '',
    notes: '',
    createdAt: '2026-08-01T10:00:00',
  };
}

for (const ruleset of RULESETS) {
  describe(`Part 1a: expenses delete under ${ruleset.name}`, () => {
    let env;

    before(async () => {
      env = await testEnv(ruleset.project, ruleset.path);
    });

    beforeEach(async () => {
      await env.clearFirestore();
    });

    describe('creator deleting their own expense', () => {
      it('succeeds with a fully paired users document', async () => {
        await seed(env, {
          'users/creator-uid': {name: 'Creator', email: 'c@x.test', partnerId: PARTNER},
          'users/partner-uid': {name: 'Partner', email: 'p@x.test', partnerId: CREATOR},
          'expenses/expense-1': expenseDoc(CREATOR),
        });
        const db = env.authenticatedContext(CREATOR).firestore();
        await assertSucceeds(db.doc('expenses/expense-1').delete());
      });

      it('succeeds when unpaired (partnerId is an empty string)', async () => {
        await seed(env, {
          'users/creator-uid': {name: 'Creator', email: 'c@x.test', partnerId: ''},
          'expenses/expense-1': expenseDoc(CREATOR),
        });
        const db = env.authenticatedContext(CREATOR).firestore();
        await assertSucceeds(db.doc('expenses/expense-1').delete());
      });

      it('succeeds when the users document has NO partnerId key at all', async () => {
        // The shape FcmService.updateUserToken leaves behind:
        // set(mapOf("fcmToken" to token), SetOptions.merge()) on a fresh uid.
        await seed(env, {
          'users/creator-uid': {fcmToken: 'token-abc'},
          'expenses/expense-1': expenseDoc(CREATOR),
        });
        const db = env.authenticatedContext(CREATOR).firestore();
        await assertSucceeds(db.doc('expenses/expense-1').delete());
      });

      it('succeeds when the creator has no users document at all', async () => {
        await seed(env, {'expenses/expense-1': expenseDoc(CREATOR)});
        const db = env.authenticatedContext(CREATOR).firestore();
        await assertSucceeds(db.doc('expenses/expense-1').delete());
      });

      it('succeeds when partnerId points at somebody else', async () => {
        await seed(env, {
          'users/creator-uid': {name: 'Creator', email: 'c@x.test', partnerId: STRANGER},
          'expenses/expense-1': expenseDoc(CREATOR),
        });
        const db = env.authenticatedContext(CREATOR).firestore();
        await assertSucceeds(db.doc('expenses/expense-1').delete());
      });
    });

    describe('ruleset-independent denials (candidate explanations for the field report)', () => {
      it('denies the creator when the remote document does not exist', async () => {
        // `ExpenseRepositoryImpl.deleteExpense` fires a remote delete by id after
        // dropping the Room row. `pushToFirestore` swallows a failed create, so an
        // expense can exist locally and never remotely — and then `resource` is null,
        // the rule raises "Null value error", and the delete is PERMISSION_DENIED.
        const db = env.authenticatedContext(CREATOR).firestore();
        await assertFails(db.doc('expenses/never-existed').delete());
      });

      it('denies the creator when the document has no createdByFirebaseUid field', async () => {
        // Documents written before ownership stamping was introduced.
        const legacy = expenseDoc(CREATOR);
        delete legacy.createdByFirebaseUid;
        await seed(env, {'expenses/expense-1': legacy});
        const db = env.authenticatedContext(CREATOR).firestore();
        await assertFails(db.doc('expenses/expense-1').delete());
      });
    });

    describe('other callers', () => {
      const coParentIt = ruleset.coParentDelete === 'allow' ?
        'lets the paired co-parent delete the creator\'s expense' :
        'denies the paired co-parent (known gap on the shipped ruleset)';

      it(coParentIt, async () => {
        await seed(env, {
          'users/creator-uid': {name: 'Creator', email: 'c@x.test', partnerId: PARTNER},
          'users/partner-uid': {name: 'Partner', email: 'p@x.test', partnerId: CREATOR},
          'expenses/expense-1': expenseDoc(CREATOR),
        });
        const db = env.authenticatedContext(PARTNER).firestore();
        const attempt = db.doc('expenses/expense-1').delete();
        await (ruleset.coParentDelete === 'allow' ?
          assertSucceeds(attempt) : assertFails(attempt));
      });

      it('denies a stranger', async () => {
        await seed(env, {
          'users/creator-uid': {name: 'Creator', email: 'c@x.test', partnerId: PARTNER},
          'expenses/expense-1': expenseDoc(CREATOR),
        });
        const db = env.authenticatedContext(STRANGER).firestore();
        await assertFails(db.doc('expenses/expense-1').delete());
      });

      it('denies an unauthenticated caller', async () => {
        await seed(env, {'expenses/expense-1': expenseDoc(CREATOR)});
        const db = env.unauthenticatedContext().firestore();
        await assertFails(db.doc('expenses/expense-1').delete());
      });
    });
  });
}
