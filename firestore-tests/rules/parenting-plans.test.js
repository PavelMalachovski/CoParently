/**
 * `parenting_plans/{familyId}` — the two halves of one family's parenting plan (MON-5).
 *
 * The feature copies the paper practice: each parent fills the form in **separately**, and a
 * mediator or an OSPOD worker lays the two side by side. So the document has one map per field,
 * keyed by uid, and the entire security model is that a parent writes only their own key.
 *
 * That is what these tests are for. A parent who could edit the other's half could put words in
 * their mouth in a document the two of them may hand to a court, and no other layer stops it —
 * the client sends whatever it sends. The three cases that matter are the last three: writing
 * into the co-parent's key, smuggling a second key in beside your own, and removing a map
 * outright, which is the one that used to *error* rather than deny in this file's other rules.
 */

const {
  CURRENT_RULES, testEnv, seed, assertSucceeds, assertFails,
} = require('../harness');

const PROJECT = 'demo-coplanly-parenting-plans';
const MOM = 'uid-mom';
const DAD = 'uid-dad';
const STRANGER = 'uid-stranger';
const KEY = [MOM, DAD].sort().join('__');
const PATH = `parenting_plans/${KEY}`;

/**
 * One parent's half, as `FirestoreParentingPlanDataSource` writes it.
 *
 * @param {string} uid Whose half.
 * @param {!Object<string, string>} answers Question id to answer text.
 * @param {!Object<string, string>} agreedTo Question id to the co-parent's agreed wording.
 * @return {!Object} The document fragment.
 */
function half(uid, answers, agreedTo) {
  return {
    answers: {[uid]: answers},
    agreedTo: {[uid]: agreedTo || {}},
    catalogueVersions: {[uid]: 1},
    updatedAt: {[uid]: 1756000000000},
  };
}

describe('parenting plans: each parent writes their own half and nobody else\'s', () => {
  let env;

  before(async () => {
    env = await testEnv(PROJECT, CURRENT_RULES);
  });

  beforeEach(async () => {
    await env.clearFirestore();
  });

  const as = (uid) => env.authenticatedContext(uid).firestore();

  it('lets either parent read the plan before anybody has written it', async () => {
    // The screen subscribes on open, which for every new pair is a read of a document that does
    // not exist. `resource` is null there, so a rule reading through it would error — and a rule
    // that errors denies, which is how a listener dies permanently on its first attempt.
    await assertSucceeds(as(MOM).doc(PATH).get());
    await assertSucceeds(as(DAD).doc(PATH).get());
  });

  it('refuses a stranger, existing document or not', async () => {
    await assertFails(as(STRANGER).doc(PATH).get());

    await seed(env, {[PATH]: half(MOM, {residence_home: 'With me in Brno'})});
    await assertFails(as(STRANGER).doc(PATH).get());
  });

  it('refuses an unauthenticated read', async () => {
    await assertFails(env.unauthenticatedContext().firestore().doc(PATH).get());
  });

  it('lets a parent create the document with their own half', async () => {
    await assertSucceeds(
        as(MOM).doc(PATH).set(half(MOM, {residence_home: 'With me in Brno'})));
  });

  it('refuses a create that carries the co-parent\'s half', async () => {
    // The whole document is written at once on a create, so this is the only chance to stop a
    // parent seeding the other's answers before they have ever opened the screen.
    await assertFails(
        as(MOM).doc(PATH).set(half(DAD, {residence_home: 'With Mom, obviously'})));
  });

  it('lets the co-parent add their half beside an existing one', async () => {
    await seed(env, {[PATH]: half(MOM, {residence_home: 'With me in Brno'})});

    await assertSucceeds(as(DAD).doc(PATH).set({
      answers: {[DAD]: {residence_home: 'Alternating, week about'}},
      agreedTo: {[DAD]: {}},
      catalogueVersions: {[DAD]: 1},
      updatedAt: {[DAD]: 1756000000001},
    }, {merge: true}));
  });

  it('refuses a write into the co-parent\'s key', async () => {
    // The case the whole rule exists for: putting words in the other parent's mouth.
    await seed(env, {[PATH]: half(MOM, {residence_home: 'With me in Brno'})});

    await assertFails(as(MOM).doc(PATH).set({
      answers: {[DAD]: {residence_home: 'I agree with everything she says'}},
    }, {merge: true}));
  });

  it('refuses a write that touches both keys at once', async () => {
    // Smuggling: the caller's own key is there, so a rule that only checked "is my key present"
    // would pass this.
    await seed(env, {[PATH]: half(MOM, {residence_home: 'With me in Brno'})});

    await assertFails(as(MOM).doc(PATH).set({
      answers: {
        [MOM]: {residence_home: 'With me in Brno'},
        [DAD]: {residence_home: 'I agree'},
      },
    }, {merge: true}));
  });

  it('refuses an agreement recorded on the co-parent\'s behalf', async () => {
    // `agreedTo` is what turns two answers into "we have settled this". Forging the other
    // parent's entry manufactures an agreement that never happened.
    await seed(env, {[PATH]: half(MOM, {care_weekday: 'Week about'})});

    await assertFails(as(MOM).doc(PATH).set({
      agreedTo: {[DAD]: {care_weekday: 'Week about'}},
    }, {merge: true}));
  });

  it('refuses removing a map outright', async () => {
    // Removal diffs as *both* parents' entries changing rather than erroring, which is the point
    // of reading each side through `.get(field, {})`. Without it this is an evaluation error, and
    // an erroring rule denies for the wrong reason — one that would stop denying the moment the
    // expression was rearranged.
    await seed(env, {
      [PATH]: {
        answers: {[MOM]: {care_weekday: 'Week about'}, [DAD]: {care_weekday: 'Two and two'}},
        agreedTo: {[MOM]: {}, [DAD]: {}},
        catalogueVersions: {[MOM]: 1, [DAD]: 1},
        updatedAt: {[MOM]: 1, [DAD]: 2},
      },
    });

    await assertFails(as(MOM).doc(PATH).set({
      agreedTo: {[MOM]: {}},
      catalogueVersions: {[MOM]: 1},
      updatedAt: {[MOM]: 3},
    }));
  });

  it('refuses a field the rule does not know about', async () => {
    // An unlisted key is a place one parent could write something the per-key checks never look
    // at — a "signed" flag, say, on a document meant for a mediator.
    await assertFails(as(MOM).doc(PATH).set(
        Object.assign(half(MOM, {residence_home: 'With me'}), {signedByBoth: true})));
  });

  it('refuses a delete from either parent', async () => {
    await seed(env, {[PATH]: half(MOM, {residence_home: 'With me in Brno'})});

    await assertFails(as(MOM).doc(PATH).delete());
    await assertFails(as(DAD).doc(PATH).delete());
  });

  it('refuses a collection query', async () => {
    // The rule keys on the document id, which a query cannot be constrained by, so allowing
    // `list` would be allowing a scan of every family's plan.
    await assertFails(as(MOM).collection('parenting_plans').get());
  });
});
