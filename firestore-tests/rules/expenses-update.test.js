/**
 * Expenses update is creator-only (owner decision, Aug 2026 walkthrough).
 *
 * The update rule used to admit the co-parent via `isPartnerOf`, so either parent could
 * rewrite the other's recorded expense — which is exactly the cheating vector the owner
 * asked to close ("Второй родитель не должен иметь возможность менять расход"). Delete was
 * already creator-only; update now matches it. The partner keeps read access: the pair's
 * balance is the reason both parents opened the app.
 */

const {
  CURRENT_RULES, testEnv, seed, assertSucceeds, assertFails,
} = require('../harness');

const PROJECT = 'demo-coplanly-expense-update';
const ALICE = 'alice-uid';
const BOB = 'bob-uid';

/**
 * Seeds a linked pair and one expense created by Alice.
 *
 * @param {!Object} env Rules test environment.
 * @return {!Promise<void>} Resolves when seeded.
 */
function seedPairAndExpense(env) {
  return seed(env, {
    'users/alice-uid': {name: 'Alice', email: 'a@x.test', partnerId: BOB},
    'users/bob-uid': {name: 'Bob', email: 'b@x.test', partnerId: ALICE},
    'expenses/expense-1': {
      id: 'expense-1', title: 'School trip', amount: 42.5, currency: 'CZK',
      category: 'EDUCATION', createdByFirebaseUid: ALICE, paidBy: ALICE,
      familyId: [ALICE, BOB].sort().join('__'),
      splitBetween: [], date: '2026-08-01', createdAt: '2026-08-01T10:00:00',
    },
  });
}

describe('Expenses update is creator-only', () => {
  let env;

  before(async () => {
    env = await testEnv(PROJECT, CURRENT_RULES);
  });

  beforeEach(async () => {
    await env.clearFirestore();
  });

  it('lets the creator update their own expense', async () => {
    await seedPairAndExpense(env);
    const db = env.authenticatedContext(ALICE).firestore();
    await assertSucceeds(db.doc('expenses/expense-1').update({amount: 50.0}));
  });

  it('denies the co-parent an update, even on a linked pair', async () => {
    await seedPairAndExpense(env);
    const db = env.authenticatedContext(BOB).firestore();
    await assertFails(db.doc('expenses/expense-1').update({amount: 0.01}));
  });

  it('still lets the co-parent read the expense', async () => {
    await seedPairAndExpense(env);
    const db = env.authenticatedContext(BOB).firestore();
    await assertSucceeds(db.doc('expenses/expense-1').get());
  });

  it('denies the creator an update that reassigns ownership', async () => {
    await seedPairAndExpense(env);
    const db = env.authenticatedContext(ALICE).firestore();
    await assertFails(
        db.doc('expenses/expense-1').update({createdByFirebaseUid: BOB}));
  });
});
