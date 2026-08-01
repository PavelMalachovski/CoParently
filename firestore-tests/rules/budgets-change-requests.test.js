/**
 * Part 1d — the remaining collections the client touches: `budgets`, `expenses`
 * (read/create/update) and `change_requests`.
 *
 * `expenses` delete is covered separately in expenses-delete-incident.test.js.
 */

const {
  CURRENT_RULES, testEnv, seed, assertSucceeds, assertFails,
} = require('../harness');

const PROJECT = 'demo-coplanly-misc';
const ALICE = 'alice-uid';
const BOB = 'bob-uid';
const CAROL = 'carol-uid';

const PAIRED_USERS = {
  'users/alice-uid': {name: 'Alice', email: 'a@x.test', partnerId: BOB},
  'users/bob-uid': {name: 'Bob', email: 'b@x.test', partnerId: ALICE},
  'users/carol-uid': {name: 'Carol', email: 'c@x.test', partnerId: ''},
};

/**
 * Builds a budget document as `BudgetRepositoryImpl.addBudget` writes it.
 *
 * @param {!Object} overrides Fields to override on the default document.
 * @return {!Object} The document data.
 */
function budgetDoc(overrides) {
  return Object.assign({
    id: 'budget-1',
    category: 'EDUCATION',
    monthlyLimit: 3000,
    currency: 'CZK',
    createdByFirebaseUid: ALICE,
  }, overrides);
}

/**
 * Builds an expense document as `ExpenseRepositoryImpl.expenseToFirestoreMap` writes it.
 *
 * @param {!Object} overrides Fields to override on the default document.
 * @return {!Object} The document data.
 */
function expenseDoc(overrides) {
  return Object.assign({
    id: 'expense-1', childId: '', title: 'School trip', amount: 42.5, currency: 'CZK',
    category: 'EDUCATION', createdByFirebaseUid: ALICE, paidBy: 'MOM',
    splitBetween: ['MOM', 'DAD'], date: '2026-08-01', receiptUrl: '', notes: '',
    createdAt: '2026-08-01T10:00:00',
  }, overrides);
}

/**
 * Builds a change-request document.
 *
 * @param {!Object} overrides Fields to override on the default document.
 * @return {!Object} The document data.
 */
function changeRequestDoc(overrides) {
  return Object.assign({
    id: 'cr-1',
    eventId: 'event-1',
    requestedBy: ALICE,
    requestedTo: BOB,
    status: 'PENDING',
    reason: 'Work trip',
    createdAt: '2026-08-01T10:00:00',
  }, overrides);
}

describe('Part 1d: budgets', () => {
  let env;

  before(async () => {
    env = await testEnv(PROJECT, CURRENT_RULES);
  });

  beforeEach(async () => {
    await env.clearFirestore();
    await seed(env, PAIRED_USERS);
  });

  it('lets the owner create, read, update and delete', async () => {
    const db = env.authenticatedContext(ALICE).firestore();
    await assertSucceeds(db.doc('budgets/budget-1').set(budgetDoc({})));
    await assertSucceeds(db.doc('budgets/budget-1').get());
    await assertSucceeds(db.doc('budgets/budget-1').update({monthlyLimit: 4000}));
    await assertSucceeds(db.doc('budgets/budget-1').delete());
  });

  it('lets the co-parent read and update, but not delete', async () => {
    await seed(env, {'budgets/budget-1': budgetDoc({})});
    const db = env.authenticatedContext(BOB).firestore();
    await assertSucceeds(db.doc('budgets/budget-1').get());
    await assertSucceeds(db.doc('budgets/budget-1').update({monthlyLimit: 4000}));
    await assertFails(db.doc('budgets/budget-1').delete());
  });

  it('denies the co-parent re-stamping ownership', async () => {
    await seed(env, {'budgets/budget-1': budgetDoc({})});
    const db = env.authenticatedContext(BOB).firestore();
    await assertFails(db.doc('budgets/budget-1').update({createdByFirebaseUid: BOB}));
  });

  it('denies a stranger', async () => {
    await seed(env, {'budgets/budget-1': budgetDoc({})});
    const db = env.authenticatedContext(CAROL).firestore();
    await assertFails(db.doc('budgets/budget-1').get());
    await assertFails(db.doc('budgets/budget-1').update({monthlyLimit: 1}));
  });

  it('denies a create missing the required keys', async () => {
    const doc = budgetDoc({});
    delete doc.monthlyLimit;
    const db = env.authenticatedContext(ALICE).firestore();
    await assertFails(db.doc('budgets/budget-1').set(doc));
  });

  it('serves the owner-filtered query the client runs', async () => {
    await seed(env, {'budgets/budget-1': budgetDoc({})});
    const db = env.authenticatedContext(BOB).firestore();
    await assertSucceeds(db.collection('budgets')
        .where('createdByFirebaseUid', 'in', [ALICE, BOB]).get());
  });
});

describe('Part 1d: expenses (read, create, update)', () => {
  let env;

  before(async () => {
    env = await testEnv(PROJECT, CURRENT_RULES);
  });

  beforeEach(async () => {
    await env.clearFirestore();
    await seed(env, PAIRED_USERS);
  });

  it('lets the owner create and the co-parent read and update', async () => {
    const alice = env.authenticatedContext(ALICE).firestore();
    await assertSucceeds(alice.doc('expenses/expense-1').set(expenseDoc({})));

    const bob = env.authenticatedContext(BOB).firestore();
    await assertSucceeds(bob.doc('expenses/expense-1').get());
    await assertSucceeds(bob.doc('expenses/expense-1').update({amount: 55}));
  });

  it('denies stamping somebody else as the creator', async () => {
    const db = env.authenticatedContext(BOB).firestore();
    await assertFails(db.doc('expenses/expense-1').set(expenseDoc({})));
  });

  it('denies the co-parent re-stamping ownership on update', async () => {
    await seed(env, {'expenses/expense-1': expenseDoc({})});
    const db = env.authenticatedContext(BOB).firestore();
    await assertFails(db.doc('expenses/expense-1').update({createdByFirebaseUid: BOB}));
  });

  it('denies a stranger', async () => {
    await seed(env, {'expenses/expense-1': expenseDoc({})});
    const db = env.authenticatedContext(CAROL).firestore();
    await assertFails(db.doc('expenses/expense-1').get());
  });

  it('serves the owner-filtered query the client runs', async () => {
    await seed(env, {'expenses/expense-1': expenseDoc({})});
    const db = env.authenticatedContext(BOB).firestore();
    await assertSucceeds(db.collection('expenses')
        .where('createdByFirebaseUid', 'in', [ALICE, BOB]).get());
  });
});

describe('Part 1d: change_requests', () => {
  let env;

  before(async () => {
    env = await testEnv(PROJECT, CURRENT_RULES);
  });

  beforeEach(async () => {
    await env.clearFirestore();
    await seed(env, PAIRED_USERS);
  });

  it('lets the requester create and the addressee read and resolve', async () => {
    const alice = env.authenticatedContext(ALICE).firestore();
    await assertSucceeds(alice.doc('change_requests/cr-1').set(changeRequestDoc({})));

    const bob = env.authenticatedContext(BOB).firestore();
    await assertSucceeds(bob.doc('change_requests/cr-1').get());
    await assertSucceeds(bob.doc('change_requests/cr-1').update({status: 'APPROVED'}));
  });

  it('denies an unrelated third party', async () => {
    await seed(env, {'change_requests/cr-1': changeRequestDoc({})});
    const db = env.authenticatedContext(CAROL).firestore();
    await assertFails(db.doc('change_requests/cr-1').get());
    await assertFails(db.doc('change_requests/cr-1').update({status: 'APPROVED'}));
    await assertFails(db.doc('change_requests/cr-1').delete());
  });

  it('denies creating a request between two other people', async () => {
    const db = env.authenticatedContext(CAROL).firestore();
    await assertFails(db.doc('change_requests/cr-1').set(changeRequestDoc({})));
  });

  it('serves the requestedTo query the inbox runs', async () => {
    await seed(env, {'change_requests/cr-1': changeRequestDoc({})});
    const db = env.authenticatedContext(BOB).firestore();
    await assertSucceeds(
        db.collection('change_requests').where('requestedTo', '==', BOB).get());
  });
});
