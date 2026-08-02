/**
 * Shared harness for the Firestore security-rules suite.
 *
 * Every test file asks for a `RulesFixture` bound to a ruleset file and a project id.
 * Distinct project ids let one emulator process host several rulesets at once, which is
 * what lets the incident reproduction (an old ruleset) run beside the current rules.
 */

const fs = require('fs');
const path = require('path');
const {
  initializeTestEnvironment,
  assertSucceeds,
  assertFails,
} = require('@firebase/rules-unit-testing');

/** Path to the ruleset the app actually deploys. */
const CURRENT_RULES = path.join(__dirname, '..', 'firestore.rules');

/** Path to the ruleset from commit b2bf6b83, the deploy blamed for the delete incident. */
const INCIDENT_RULES = path.join(
    __dirname, 'fixtures', 'rules-b2bf6b83-incident.rules');

const environments = new Map();

/**
 * Returns (and memoises) a rules test environment for one ruleset.
 *
 * @param {string} projectId Emulator project id; must be unique per ruleset.
 * @param {string} rulesPath Absolute path to the `.rules` file to load.
 * @return {Promise<import('@firebase/rules-unit-testing').RulesTestEnvironment>} Env.
 */
async function testEnv(projectId, rulesPath) {
  if (!environments.has(projectId)) {
    environments.set(projectId, initializeTestEnvironment({
      projectId,
      firestore: {rules: fs.readFileSync(rulesPath, 'utf8')},
    }));
  }
  return environments.get(projectId);
}

/** Tears down every environment created during the run. */
async function cleanupAll() {
  for (const pending of environments.values()) {
    const env = await pending;
    await env.cleanup();
  }
  environments.clear();
}

/**
 * Seeds documents with security rules bypassed.
 *
 * @param {import('@firebase/rules-unit-testing').RulesTestEnvironment} env Environment.
 * @param {!Object<string, !Object>} docs Map of `collection/docId` to document data.
 * @return {Promise<void>} Resolves once every document is written.
 */
async function seed(env, docs) {
  await env.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    for (const [refPath, data] of Object.entries(docs)) {
      await db.doc(refPath).set(data);
    }
  });
}

module.exports = {
  CURRENT_RULES,
  INCIDENT_RULES,
  testEnv,
  cleanupAll,
  seed,
  assertSucceeds,
  assertFails,
};
