#!/usr/bin/env node
/**
 * Offline checks for three invariants CLAUDE.md states and no CI job enforces.
 *
 * All three are pure text comparisons over resources and sources, so they need neither an
 * Android SDK nor a device — which is the point. Each of them has a reason it is not already
 * covered:
 *
 *  1. **Locale completeness.** `MissingTranslation` is *disabled* in `app/build.gradle.kts`,
 *     not demoted — a disabled check reports nothing under any severity, so a key added to
 *     `values/` alone falls back to English at runtime and nothing says so. CLAUDE.md tells a
 *     reader to verify by grep; this is that grep, over every key at once, and it also catches
 *     a duplicate key, which the lint check never would.
 *  2. **Format-argument agreement.** A translation that drops or renumbers a `%1$s` throws
 *     `IllegalFormatException` on the device of whoever reads that language, and only there.
 *  3. **Push-type agreement (SEC-3, CLAUDE.md item 15).** A type has to be named in four
 *     places; missing from any one of them it is a push that silently never appears, and a
 *     server-only type that leaks into the client allow-list is a forgeable notification.
 *
 * Exits non-zero listing every problem found, so it can gate a build.
 */

const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const RES = path.join(ROOT, 'app/src/main/res');
const BASE_DIR = 'values';
const LOCALE_DIRS = ['values-cs', 'values-de', 'values-ru', 'values-uk'];

const problems = [];
const fail = (msg) => problems.push(msg);
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8');

// XML comments carry prose — apostrophes, percent signs, stray tags — that must never be read
// as resource content. Strip them before any parsing.
const stripComments = (xml) => xml.replace(/<!--[\s\S]*?-->/g, '');

/**
 * Every `<string>`, `<plurals>` and `<string-array>` in one `values*` directory.
 *
 * Plurals are keyed with a `plurals:` prefix so a plural and a string of the same name stay
 * distinct, and their whole body is kept: a format argument may appear in any one `<item>`.
 */
function collectStrings(dir) {
  const out = new Map();
  const dirPath = path.join(RES, dir);
  if (!fs.existsSync(dirPath)) {
    fail(`resource directory is missing: ${dir}`);
    return out;
  }
  for (const file of fs.readdirSync(dirPath).filter((f) => f.endsWith('.xml'))) {
    const xml = stripComments(fs.readFileSync(path.join(dirPath, file), 'utf8'));

    const strings = /<string\s+([^>]*?)name="([^"]+)"([^>]*?)>([\s\S]*?)<\/string>/g;
    for (let m; (m = strings.exec(xml)); ) {
      const attrs = m[1] + m[3];
      add(out, m[2], { file, body: m[4], translatable: !/translatable\s*=\s*"false"/.test(attrs) }, dir);
    }

    const plurals = /<plurals\s+([^>]*?)name="([^"]+)"([^>]*?)>([\s\S]*?)<\/plurals>/g;
    for (let m; (m = plurals.exec(xml)); ) {
      const attrs = m[1] + m[3];
      add(out, `plurals:${m[2]}`, { file, body: m[4], translatable: !/translatable\s*=\s*"false"/.test(attrs) }, dir);
    }

    const arrays = /<string-array\s+([^>]*?)name="([^"]+)"([^>]*?)>([\s\S]*?)<\/string-array>/g;
    for (let m; (m = arrays.exec(xml)); ) {
      const attrs = m[1] + m[3];
      add(out, `array:${m[2]}`, { file, body: m[4], translatable: !/translatable\s*=\s*"false"/.test(attrs) }, dir);
    }
  }
  return out;
}

function add(map, name, value, dir) {
  if (map.has(name)) {
    fail(`duplicate resource "${name}" in ${dir}: ${map.get(name).file} and ${value.file}`);
  }
  map.set(name, value);
}

/**
 * The set of format arguments a resource body uses.
 *
 * Positional (`%1$s`) and implicit (`%s`) arguments are counted separately because Android
 * treats them as different things; `%%` is a literal percent and not an argument at all.
 */
function formatArgs(body) {
  const args = new Set();
  const re = /%(?:(\d+)\$[a-zA-Z]|%|([a-zA-Z]))/g;
  let implicit = 0;
  for (let m; (m = re.exec(body)); ) {
    if (m[1]) args.add(`#${m[1]}`);
    else if (m[2]) args.add(`implicit#${++implicit}`);
  }
  return args;
}

// ---- 1 + 2: locales ---------------------------------------------------------

function checkLocales() {
  const base = collectStrings(BASE_DIR);
  let translatable = 0;
  for (const v of base.values()) if (v.translatable) translatable++;

  for (const dir of LOCALE_DIRS) {
    const loc = collectStrings(dir);

    for (const [name, v] of base) {
      if (!v.translatable) continue;
      if (!loc.has(name)) {
        fail(`missing translation: "${name}" (${v.file}) has no ${dir} entry`);
        continue;
      }
      const baseArgs = formatArgs(v.body);
      const locArgs = formatArgs(loc.get(name).body);
      const missing = [...baseArgs].filter((a) => !locArgs.has(a));
      const extra = [...locArgs].filter((a) => !baseArgs.has(a));
      if (missing.length || extra.length) {
        fail(
          `format arguments disagree for "${name}" in ${dir}: ` +
            `base uses {${[...baseArgs].join(', ')}}, ${dir} uses {${[...locArgs].join(', ')}}`
        );
      }
    }

    for (const name of loc.keys()) {
      if (!base.has(name)) fail(`orphan resource: "${name}" exists in ${dir} but not in ${BASE_DIR}`);
    }
  }

  console.log(`locales: ${base.size} base resources (${translatable} translatable) x ${LOCALE_DIRS.length} locales`);
}

// ---- 3: push types ----------------------------------------------------------

const PAYLOAD_KT = 'app/src/main/java/com/coparently/app/data/remote/firebase/PushPayload.kt';
const SERVICE_KT = 'app/src/main/java/com/coparently/app/data/remote/firebase/CoPlanlyMessagingService.kt';
const RULES = 'firestore.rules';
const PUSH_STRINGS = 'app/src/main/res/values/push_strings.xml';

/**
 * `chat_message` is composed by a branch of its own in `CoPlanlyMessagingService.compose()`,
 * ahead of the `PUSH_TEXT` lookup, because its text is not a frame: the title is who sent the
 * message and the body is what they wrote, and only the Cloud Function that saw the message
 * could supply either. It is renderable without a `PUSH_TEXT` entry — the one type that is.
 */
const COMPOSED_WITHOUT_SPEC = new Set(['chat_message']);

/** Types declared between two `// ---- <marker> ----` section headers in PushPayload.kt. */
function typesInSection(source, marker) {
  const start = source.indexOf(marker);
  if (start === -1) {
    fail(`${PAYLOAD_KT} no longer contains the section marker "${marker}" this check parses`);
    return { values: new Set(), constants: new Map() };
  }
  const rest = source.slice(start + marker.length);
  const end = rest.search(/\n\s*\/\/ ----/);
  const body = end === -1 ? rest : rest.slice(0, end);
  const values = new Set();
  const constants = new Map();
  const re = /const val ([A-Z_0-9]+)\s*=\s*"([a-z_]+)"/g;
  for (let m; (m = re.exec(body)); ) {
    constants.set(m[1], m[2]);
    values.add(m[2]);
  }
  return { values, constants };
}

function checkPushTypes() {
  const payload = read(PAYLOAD_KT);
  const service = read(SERVICE_KT);
  const rules = read(RULES);
  const strings = stripComments(read(PUSH_STRINGS));

  const client = typesInSection(payload, '// ---- types a client may produce');
  const server = typesInSection(payload, '// ---- types only a Cloud Function may produce');

  // The CLIENT_TYPES set the sending code reads.
  const setBlock = payload.match(/val CLIENT_TYPES:\s*Set<String>\s*=\s*setOf\(([\s\S]*?)\)/);
  const clientSet = new Set();
  if (!setBlock) fail(`${PAYLOAD_KT}: could not find the CLIENT_TYPES set`);
  else {
    for (const m of setBlock[1].matchAll(/([A-Z_0-9]+)/g)) {
      const value = client.constants.get(m[1]) || server.constants.get(m[1]);
      if (!value) fail(`CLIENT_TYPES names ${m[1]}, which is not a declared push type`);
      else clientSet.add(value);
    }
  }

  // The allow-list firestore.rules actually enforces.
  const ruleBlock = rules.match(/function isClientPushType\(type\)\s*\{[\s\S]*?return type in \[([\s\S]*?)\];/);
  const ruleAllowed = new Set();
  if (!ruleBlock) fail(`${RULES}: could not find isClientPushType's allow-list`);
  else for (const m of ruleBlock[1].matchAll(/'([a-z_]+)'/g)) ruleAllowed.add(m[1]);

  // Types the receiving device can put words to.
  const rendered = new Set();
  for (const m of service.matchAll(/PushPayload\.([A-Z_0-9]+)\s+to\s+PushTextSpec/g)) {
    const value = client.constants.get(m[1]) || server.constants.get(m[1]);
    if (value) rendered.add(value);
  }
  // Match the branch itself, not a mention of the type: `chat_message` appears throughout the
  // file's prose, so an `includes` on the name would go on passing after the branch was deleted
  // — which is exactly the regression this is here to catch.
  if (/if\s*\(type == TYPE_CHAT_MESSAGE\)/.test(service)) {
    for (const t of COMPOSED_WITHOUT_SPEC) rendered.add(t);
  }

  const declared = [...client.values, ...server.values];

  for (const type of declared) {
    if (!rendered.has(type)) {
      fail(`push type "${type}" is declared but CoPlanlyMessagingService has no wording for it — the push would be silently dropped on arrival`);
    }
  }

  for (const type of client.values) {
    if (!clientSet.has(type)) fail(`client push type "${type}" is missing from PushPayload.CLIENT_TYPES — it would fail to enqueue`);
    if (!ruleAllowed.has(type)) fail(`client push type "${type}" is missing from isClientPushType in ${RULES} — the write would be denied`);
  }

  for (const type of server.values) {
    if (clientSet.has(type)) fail(`SECURITY: server-only push type "${type}" appears in PushPayload.CLIENT_TYPES`);
    if (ruleAllowed.has(type)) fail(`SECURITY: server-only push type "${type}" appears in the ${RULES} client allow-list — a co-parent could forge it`);
  }

  for (const type of ruleAllowed) {
    if (!client.values.has(type)) fail(`${RULES} allows push type "${type}", which PushPayload does not declare as a client type`);
  }

  // Every string resource the service names must exist.
  const defined = new Set([...strings.matchAll(/<(?:string|plurals)\s+[^>]*name="([^"]+)"/g)].map((m) => m[1]));
  const referenced = new Set(
    [...service.matchAll(/R\.(?:string|plurals)\.(push_[a-z_0-9]+)/g)].map((m) => m[1])
  );
  for (const name of referenced) {
    if (!defined.has(name)) fail(`CoPlanlyMessagingService references R.string/plurals.${name}, which ${PUSH_STRINGS} does not define`);
  }

  console.log(
    `push types: ${client.values.size} client, ${server.values.size} server-only, ` +
      `${ruleAllowed.size} in the rules allow-list, ${referenced.size} string resources referenced`
  );
}

// ---- run --------------------------------------------------------------------

checkLocales();
checkPushTypes();

if (problems.length) {
  console.error(`\n${problems.length} problem(s):`);
  for (const p of problems) console.error(`  - ${p}`);
  process.exit(1);
}
console.log('\nAll invariants hold.');
