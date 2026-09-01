/**
 * Shared harness for the security-rules suites.
 *
 * Every test file asks for a `RulesFixture` bound to a ruleset file and a project id.
 * Distinct project ids let one emulator process host several rulesets at once, which is
 * what lets the incident reproduction (an old ruleset) run beside the current rules.
 *
 * It covers `storage.rules` as well as `firestore.rules`, despite this directory's name. The
 * Storage rules had no coverage at all until then, which is how `pet_photos/**` reached a state
 * where the file in this repository grants an upload the live bucket refuses — see the
 * `storage.rules` entry under "Known issues" in CLAUDE.md. What these tests can prove is that
 * the ruleset in the repository is correct; nothing here can see what is deployed.
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

/** Path to the Cloud Storage ruleset the app deploys. */
const STORAGE_RULES = path.join(__dirname, '..', 'storage.rules');

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

/**
 * Returns (and memoises) a rules test environment holding the Cloud Storage ruleset.
 *
 * Separate from `testEnv` rather than a flag on it: a project id may carry one ruleset per
 * service, and giving the two suites their own ids keeps a Firestore test from being denied by
 * a Storage rule it never meant to load.
 *
 * @param {string} projectId Emulator project id; must be unique per ruleset.
 * @return {Promise<import('@firebase/rules-unit-testing').RulesTestEnvironment>} Env.
 */
async function storageTestEnv(projectId) {
  if (!environments.has(projectId)) {
    environments.set(projectId, initializeTestEnvironment({
      projectId,
      storage: {rules: fs.readFileSync(STORAGE_RULES, 'utf8')},
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
  STORAGE_RULES,
  testEnv,
  storageTestEnv,
  cleanupAll,
  seed,
  assertSucceeds,
  assertFails,
};
