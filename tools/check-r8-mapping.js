#!/usr/bin/env node
/**
 * Proves, against R8's own output, that the field-keep rules actually held.
 *
 * `tools/check-invariants.js` checks the *intent* — that every type Gson reflects over is named
 * by a `-keepclassmembers ... { <fields>; }` rule. That is a reading of the source, and a rule
 * can be present and still not do what it says: a typo in a package name, a pattern that matches
 * nothing, an optimization pass that renames anyway. This check reads `mapping.txt` after
 * `assembleRelease` and confirms the fields came out carrying the names Gson will look for.
 *
 * The failure it exists to catch is invisible in every other way: it cannot happen in debug,
 * which does not minify; it raises no exception; and the JSON it writes is well-formed — the
 * keys are simply `a`, `b`, `c`. So a co-parent's device reads a record with no field it
 * recognises, and the next app update cannot read back what this one wrote.
 *
 * Usage: node tools/check-r8-mapping.js [path/to/mapping.txt]
 */

const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const PROGUARD = path.join(ROOT, 'app/proguard-rules.pro');
const DEFAULT_MAPPING = path.join(ROOT, 'app/build/outputs/mapping/release/mapping.txt');

const mappingPath = process.argv[2] ? path.resolve(process.argv[2]) : DEFAULT_MAPPING;

if (!fs.existsSync(mappingPath)) {
  console.error('No mapping file at ' + mappingPath + '.');
  console.error('Run `./gradlew assembleRelease` first — R8 writes it only for a minified build.');
  process.exit(1);
}

/** The `-keepclassmembers class X { <fields>; }` patterns. */
function fieldKeepPatterns(proguard) {
  const patterns = [];
  const re = /-keepclassmembers\s+class\s+([\w.$*]+)\s*\{([^}]*)\}/g;
  for (let m; (m = re.exec(proguard)); ) {
    if (!/<fields>|\*\s*;/.test(m[2])) continue;
    patterns.push(m[1]);
  }
  return patterns;
}

/**
 * ProGuard glob semantics: `**` crosses package separators, `*` does not.
 *
 * The two are separated before either is expanded, so the `.*` written for `**` cannot be
 * re-read as a `*` and widened a second time.
 */
function keepMatches(pattern, fqcn) {
  const body = pattern
    .split('**')
    .map((part) => part.replace(/[.$]/g, (c) => '\\' + c).replace(/\*/g, '[^.]*'))
    .join('.*');
  return new RegExp('^' + body + '$').test(fqcn);
}

const patterns = fieldKeepPatterns(fs.readFileSync(PROGUARD, 'utf8'));
if (!patterns.length) {
  console.error(PROGUARD + ' declares no field-keep rule; nothing to verify.');
  process.exit(1);
}

// mapping.txt is line-oriented: a class line `orig -> obfuscated:` followed by indented member
// lines. A member is a field when it carries no parameter list; methods have `(...)` and often a
// `line:line:` prefix.
const problems = [];
let checkedClasses = 0;
let checkedFields = 0;
let current = null;

for (const line of fs.readFileSync(mappingPath, 'utf8').split('\n')) {
  if (!line.trim() || line.trimStart().startsWith('#')) continue;

  if (!/^\s/.test(line)) {
    const m = line.match(/^([\w.$]+)\s*->\s*([\w.$]+):/);
    current = m && patterns.some((p) => keepMatches(p, m[1])) ? m[1] : null;
    if (current) checkedClasses++;
    continue;
  }

  if (!current) continue;

  const member = line.trim().match(/^(?:\d+:\d+:)?([\w.$[\]]+)\s+([\w$]+)\s*->\s*([\w$]+)$/);
  if (!member) continue;

  const original = member[2];
  const mapped = member[3];
  checkedFields++;
  if (original !== mapped) {
    problems.push(
      current + '.' + original + ' was renamed to "' + mapped + '" — Gson derives its JSON key ' +
        'from the field name, so a release build writes "' + mapped + '" where every reader ' +
        'expects "' + original + '"'
    );
  }
}

console.log(
  'r8 mapping: ' + checkedClasses + ' kept classes, ' + checkedFields + ' fields verified (' +
    path.relative(ROOT, mappingPath) + ')'
);

if (checkedClasses === 0) {
  console.error(
    '\nNo class in the mapping matched any field-keep rule. Either the rules name packages that ' +
      'no longer exist, or this mapping is not from this app — both make the check vacuous, ' +
      'which is worse than a failure.'
  );
  process.exit(1);
}

if (problems.length) {
  console.error('\n' + problems.length + ' renamed field(s):');
  for (const p of problems) console.error('  - ' + p);
  process.exit(1);
}

console.log('Every kept field survived R8 with its own name.');
