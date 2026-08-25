/**
 * `families/{familyId}` — the document that says who co-parents with whom.
 *
 * It exists because a person may have more than one co-parenting relationship (see
 * docs/DESIGN-multi-family.md), and every record those two share will be named with this id.
 * The id is the same string `custody_models`, `family_settings` and `conversations` are already
 * keyed by.
 *
 * The case worth having here is the last one. The obvious way to record membership is a
 * `families` array on the caller's own `users/{uid}` — and it is an escalation, because a user
 * may write their own profile. These tests pin the alternative: the membership lives in a
 * document no client can write at all.
 */

const {
  CURRENT_RULES, testEnv, seed, assertSucceeds, assertFails,
} = require('../harness');

const PROJECT = 'demo-coplanly-families';
const ALICE = 'alice-uid';
const BOB = 'bob-uid';
const CAROL = 'carol-uid';

/** The family id for a pair, built the way `FamilyKey.of` builds it. */
function familyId(uidA, uidB) {
  return [uidA, uidB].sort().join('__');
}

const ALICE_BOB = familyId(ALICE, BOB);
const ALICE_CAROL = familyId(ALICE, CAROL);

describe('families: membership is the grant, and no client may write it', () => {
  let env;

  before(async () => {
    env = await testEnv(PROJECT, CURRENT_RULES);
  });

  beforeEach(async () => {
    await env.clearFirestore();
    await seed(env, {
      [`families/${ALICE_BOB}`]: {
        members: [ALICE, BOB],
        createdAt: 1,
        slots: {[ALICE]: 'mom', [BOB]: 'dad'},
        caresFor: {[ALICE]: 'CHILDREN', [BOB]: ''},
      },
      [`families/${ALICE_CAROL}`]: {members: [ALICE, CAROL], createdAt: 2},
    });
  });

  const as = (uid) => env.authenticatedContext(uid).firestore();

  it('lets each of the two adults read their own family', async () => {
    await assertSucceeds(as(ALICE).doc(`families/${ALICE_BOB}`).get());
    await assertSucceeds(as(BOB).doc(`families/${ALICE_BOB}`).get());
  });

  it('refuses somebody the family does not name', async () => {
    // Carol co-parents with Alice, which grants her nothing about Alice and Bob.
    await assertFails(as(CAROL).doc(`families/${ALICE_BOB}`).get());
  });

  it('lets a person list the families they are in, and only those', async () => {
    // Firestore validates a query's structure, not its results: the filtered query is
    // guaranteed to return only documents that pass, so it is allowed.
    const mine = as(ALICE).collection('families')
        .where('members', 'array-contains', ALICE);
    await assertSucceeds(mine.get());

    // Alice is in two families and Bob in one; the same query for Bob cannot reach Carol's.
    const bobs = as(BOB).collection('families')
        .where('members', 'array-contains', BOB);
    await assertSucceeds(bobs.get());
  });

  it('refuses an unfiltered listing of every family there is', async () => {
    await assertFails(as(ALICE).collection('families').get());
  });

  it('refuses a listing filtered on somebody else', async () => {
    await assertFails(
        as(CAROL).collection('families')
            .where('members', 'array-contains', BOB).get());
  });

  it('lets nobody create a family, not even for themselves', async () => {
    // The membership is the grant. A client create path would let anyone name themselves a
    // member of any pair — which is exactly the escalation that a `families` array on the
    // caller's own users document would have shipped, since a user may write their own profile.
    // Pairing writes this document through the admin SDK, which bypasses rules.
    await assertFails(
        as(CAROL).doc(`families/${familyId(CAROL, BOB)}`)
            .set({members: [CAROL, BOB], createdAt: 3}));
  });

  it('lets a member neither add somebody nor remove them', async () => {
    await assertFails(
        as(ALICE).doc(`families/${ALICE_BOB}`)
            .update({members: [ALICE, BOB, CAROL]}));
    await assertFails(
        as(ALICE).doc(`families/${ALICE_BOB}`).update({members: [ALICE]}));
  });

  it('lets a member write themselves into a family they are not in', async () => {
    // Named for what an attacker would try; the assertion is that they cannot.
    await assertFails(
        as(CAROL).doc(`families/${ALICE_BOB}`)
            .update({members: [ALICE, BOB, CAROL]}));
  });

  it('lets nobody delete a family', async () => {
    // Unpairing goes through `unpairCoParent`, which writes as admin. A client delete would let
    // either adult silently drop the other's access to everything the pair shares.
    await assertFails(as(ALICE).doc(`families/${ALICE_BOB}`).delete());
  });

  it('refuses a signed-out reader', async () => {
    await assertFails(
        env.unauthenticatedContext().firestore()
            .doc(`families/${ALICE_BOB}`).get());
  });

  // ---- caresFor: the one field a member may write --------------------------

  it('lets a member set their own caresFor entry', async () => {
    await assertSucceeds(
        as(ALICE).doc(`families/${ALICE_BOB}`)
            .update({[`caresFor.${ALICE}`]: 'CHILDREN|PETS'}));
  });

  it('refuses a member answering for the other parent', async () => {
    // The whole point of keying `caresFor` by uid: one parent's answer is theirs alone, and
    // the union of the two is what the app shows. Writing the co-parent's entry would let
    // either of them put words in the other's mouth — and, through the union, hide a section
    // the other is using.
    await assertFails(
        as(ALICE).doc(`families/${ALICE_BOB}`)
            .update({[`caresFor.${BOB}`]: 'PETS'}));
  });

  it('refuses a write that changes both entries at once', async () => {
    await assertFails(
        as(ALICE).doc(`families/${ALICE_BOB}`)
            .update({caresFor: {[ALICE]: 'PETS', [BOB]: 'PETS'}}));
  });

  it('refuses a caresFor write that also touches slots', async () => {
    // The dangerous combination: a legitimate-looking field carrying an illegitimate one.
    // `slots` decides whose events are whose, so a parent who could set their own would take
    // the co-parent's colour and their side of the custody grid.
    await assertFails(
        as(ALICE).doc(`families/${ALICE_BOB}`).update({
          [`caresFor.${ALICE}`]: 'PETS',
          [`slots.${ALICE}`]: 'dad',
        }));
  });

  it('refuses a caresFor write that also touches members', async () => {
    await assertFails(
        as(ALICE).doc(`families/${ALICE_BOB}`).update({
          [`caresFor.${ALICE}`]: 'PETS',
          members: [ALICE, BOB, CAROL],
        }));
  });

  it('refuses a stranger writing caresFor at all', async () => {
    await assertFails(
        as(CAROL).doc(`families/${ALICE_BOB}`)
            .update({[`caresFor.${CAROL}`]: 'PETS'}));
  });

  it('lets a member write caresFor onto a family that has none yet', async () => {
    // Every pair that accepted before this field existed. Reading a missing key in Rules is an
    // evaluation error, so without `.get('caresFor', {})` on the stored side the rule would
    // error — and an erroring rule denies — locking exactly those pairs out of the first write
    // they need to make.
    await assertSucceeds(
        as(ALICE).doc(`families/${ALICE_CAROL}`)
            .update({[`caresFor.${ALICE}`]: 'PETS'}));
  });

  it('refuses removing the caresFor map outright', async () => {
    // Not an escalation, but not a write the app makes either: clearing an answer is an empty
    // string, and dropping the map would take the co-parent's entry with it.
    const {deleteField} = require('firebase/firestore');
    await assertFails(
        as(ALICE).doc(`families/${ALICE_BOB}`)
            .update({caresFor: deleteField()}));
  });

  it('refuses a member editing slots even on its own', async () => {
    await assertFails(
        as(ALICE).doc(`families/${ALICE_BOB}`)
            .update({[`slots.${ALICE}`]: 'dad'}));
  });
});
