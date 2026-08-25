/**
 * A co-parent in one family must have nothing to do with a co-parent in another.
 *
 * The scenario throughout: **Alice has two co-parents.** Bob is her co-parent in one family,
 * Carol in another. Bob and Carol have never met and share nothing. Every case here asks the
 * same question from Bob's side — can he reach anything belonging to Alice-and-Carol — and the
 * answer must be no, on every collection.
 *
 * This is the file to run first when touching any read rule. The leak it pins is not
 * hypothetical: before `familyId`, `expenses` and `budgets` were gated on
 * `isPartnerOf(createdByFirebaseUid)`, which asks "am I a co-parent of the author" and never
 * "is this record mine to see" — so Bob read every expense Alice had ever recorded, in both
 * families. See docs/DESIGN-multi-family.md, M-4.
 */

const {
  CURRENT_RULES, testEnv, seed, assertSucceeds, assertFails,
} = require('../harness');

const PROJECT = 'demo-coplanly-family-isolation';
const ALICE = 'alice-uid';
const BOB = 'bob-uid';
const CAROL = 'carol-uid';

/** The family id for a pair, built the way `FamilyKey.of` builds it. */
function familyId(uidA, uidB) {
  return [uidA, uidB].sort().join('__');
}

const WITH_BOB = familyId(ALICE, BOB);
const WITH_CAROL = familyId(ALICE, CAROL);

describe('a second co-parent sees nothing of the first', () => {
  let env;

  before(async () => {
    env = await testEnv(PROJECT, CURRENT_RULES);
  });

  beforeEach(async () => {
    await env.clearFirestore();
    await seed(env, {
      // Alice co-parents with both. `partnerIds` is the array `isPartnerOf` will read; the
      // singular `partnerId` is still written for a co-parent on an older build.
      [`users/${ALICE}`]: {id: ALICE, partnerIds: [BOB, CAROL], partnerId: BOB},
      [`users/${BOB}`]: {id: BOB, partnerIds: [ALICE], partnerId: ALICE},
      [`users/${CAROL}`]: {id: CAROL, partnerIds: [ALICE], partnerId: ALICE},

      [`families/${WITH_BOB}`]: {members: [ALICE, BOB]},
      [`families/${WITH_CAROL}`]: {members: [ALICE, CAROL]},

      // One record of each kind in each family, all created by Alice — the person who is in
      // both, and therefore the only one whose records could cross.
      'expenses/e-bob': {
        id: 'e-bob', createdByFirebaseUid: ALICE, familyId: WITH_BOB, amount: 10,
      },
      'expenses/e-carol': {
        id: 'e-carol', createdByFirebaseUid: ALICE, familyId: WITH_CAROL, amount: 20,
      },
      'budgets/b-bob': {
        id: 'b-bob', createdByFirebaseUid: ALICE, familyId: WITH_BOB,
        category: 'FOOD', monthlyLimit: 100,
      },
      'budgets/b-carol': {
        id: 'b-carol', createdByFirebaseUid: ALICE, familyId: WITH_CAROL,
        category: 'FOOD', monthlyLimit: 200,
      },
      'events/ev-carol': {
        id: 'ev-carol', title: 'Vet', startDateTime: '2026-09-01T10:00:00',
        eventType: 'other', parentOwner: 'mom',
        createdByFirebaseUid: ALICE, familyId: WITH_CAROL, sharedWith: [ALICE, CAROL],
      },
      'child_info/c-carol': {
        id: 'c-carol', childName: 'Ema',
        createdByFirebaseUid: ALICE, familyId: WITH_CAROL, sharedWith: [ALICE, CAROL],
      },
      'pets/p-carol': {
        id: 'p-carol', name: 'Rex',
        createdByFirebaseUid: ALICE, familyId: WITH_CAROL, sharedWith: [ALICE, CAROL],
      },
    });
  });

  const as = (uid) => env.authenticatedContext(uid).firestore();

  it('refuses Bob the other family\'s expense, by document id', async () => {
    // The leak this whole stage exists to close. Bob is a live co-parent of Alice, so the old
    // `isPartnerOf(createdByFirebaseUid)` gate said yes to this.
    await assertFails(as(BOB).doc('expenses/e-carol').get());
  });

  it('still lets Bob read the expense of the family he is in', async () => {
    await assertSucceeds(as(BOB).doc('expenses/e-bob').get());
  });

  it('refuses Bob the other family\'s budget, and lets him read his own', async () => {
    await assertFails(as(BOB).doc('budgets/b-carol').get());
    await assertSucceeds(as(BOB).doc('budgets/b-bob').get());
  });

  it('refuses Bob a query for the other family\'s expenses', async () => {
    // Firestore validates a query's structure: filtered on a family Bob is not in, every
    // possible result fails the rule, so the query is rejected outright rather than returning
    // an empty page that could later be widened.
    await assertFails(
        as(BOB).collection('expenses').where('familyId', '==', WITH_CAROL).get());
  });

  it('lets Bob query his own family\'s expenses', async () => {
    await assertSucceeds(
        as(BOB).collection('expenses').where('familyId', '==', WITH_BOB).get());
  });

  it('refuses Bob the by-author query that used to work', async () => {
    // `whereIn('createdByFirebaseUid', [alice, bob])` is the shape the client used before the
    // switch, and it is exactly the shape that merged two families into one list. It must stop
    // being accepted, or the old query would keep working against the new rule.
    await assertFails(
        as(BOB).collection('expenses')
            .where('createdByFirebaseUid', 'in', [ALICE, BOB]).get());
  });

  it('refuses Bob the other family\'s event, child and pet', async () => {
    // These three carry `sharedWith`, computed from the record's own family — so Bob is simply
    // not in the audience. No rules change was needed for them; the audience is the fix.
    await assertFails(as(BOB).doc('events/ev-carol').get());
    await assertFails(as(BOB).doc('child_info/c-carol').get());
    await assertFails(as(BOB).doc('pets/p-carol').get());
  });

  it('refuses Bob a shared-audience query naming the other family', async () => {
    await assertFails(
        as(BOB).collection('events').where('sharedWith', 'array-contains', CAROL).get());
  });

  it('refuses Bob the other co-parent\'s profile', async () => {
    // Carol is nobody's co-parent but Alice's. Bob knowing Alice grants him nothing about her.
    await assertFails(as(BOB).doc(`users/${CAROL}`).get());
  });

  it('refuses Bob the other family document', async () => {
    await assertFails(as(BOB).doc(`families/${WITH_CAROL}`).get());
  });

  it('refuses Bob editing the other family\'s budget', async () => {
    await assertFails(
        as(BOB).doc('budgets/b-carol').update({monthlyLimit: 1}));
  });

  it('refuses Bob re-pointing his own family\'s budget at a family with a stranger', async () => {
    // Either parent may edit a budget, which is why `familyId` is pinned on update: without
    // the pin Bob could hand Alice's budget to anybody by renaming the family it belongs to.
    await assertFails(
        as(BOB).doc('budgets/b-bob').update({familyId: familyId(BOB, CAROL)}));
  });

  it('refuses Bob blanking a budget\'s family, which the pin also has to stop', async () => {
    // Blanking is the two-step version of the same attack: an empty family falls through to
    // the legacy branch, which `isPartnerOf(Alice)` answers yes to for Bob — so a blank-out
    // would leave him free to set any family he liked on the next write.
    await assertFails(as(BOB).doc('budgets/b-bob').update({familyId: ''}));
  });

  it('lets Bob edit his own family\'s budget when the family is left alone', async () => {
    await assertSucceeds(
        as(BOB).doc('budgets/b-bob').update({monthlyLimit: 150, familyId: WITH_BOB}));
  });

  it('lets Alice, who is in both, read either side', async () => {
    // The person with two co-parents is the one who sees everything, which is correct: both
    // families are hers. Isolation is between the co-parents, not around her.
    await assertSucceeds(as(ALICE).doc('expenses/e-bob').get());
    await assertSucceeds(as(ALICE).doc('expenses/e-carol').get());
  });

  it('leaves an unstamped expense readable only by its author', async () => {
    // The deployment order this pins, and it is not a nicety. There is deliberately no
    // `isPartnerOf` fallback for a document with no family, because a fallback re-opens the
    // leak: Firestore validates a query by its *structure*, so while the rule mentioned
    // `isPartnerOf(createdByFirebaseUid)` on any branch, the old
    // `whereIn('createdByFirebaseUid', […])` query satisfied it structurally and came back
    // with both families' documents. Measured against the emulator, not reasoned about.
    //
    // The price is this: `backfillRecordFamilyIds` must finish **before** these rules are
    // deployed. Run in the other order, a co-parent's existing expenses go quiet until it
    // completes — not lost, since Room is the source of truth, but missing on the other phone.
    await seed(env, {
      'expenses/e-legacy': {id: 'e-legacy', createdByFirebaseUid: ALICE, amount: 5},
    });
    await assertSucceeds(as(ALICE).doc('expenses/e-legacy').get());
    await assertFails(as(BOB).doc('expenses/e-legacy').get());
  });

  it('refuses a signed-out reader everything', async () => {
    const out = env.unauthenticatedContext().firestore();
    await assertFails(out.doc('expenses/e-bob').get());
    await assertFails(out.doc('budgets/b-bob').get());
  });
});
