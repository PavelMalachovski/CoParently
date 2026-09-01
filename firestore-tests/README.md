# Firestore security-rules tests

Offline tests for `firestore.rules` **and `storage.rules`**, run against the Firestore and
Cloud Storage emulators with `@firebase/rules-unit-testing`. No deploy, no device, no
production project.

The directory name predates the Storage half. Those rules had no coverage of any kind until
`rules/storage.test.js`, which is how `pet_photos/**` reached a state where the ruleset here
grants an upload the live bucket refuses — the tests prove the ruleset in this repository, and
say nothing about what is deployed.

They exist because the alternative — deploying a rule change to `coparently-a39c9` and
watching a phone — is how a broken `expenses` delete rule reached production once already.

## Running

```bash
cd firestore-tests
npm install          # once
npm test             # starts the emulator, runs the suite, shuts it down
```

The emulator needs a JDK 21+. On the Windows dev machine the system `JAVA_HOME` is broken,
so put the Android Studio JBR on `PATH` first:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio1\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
npm test
```

To iterate against an emulator you started yourself:

```bash
firebase emulators:start --only firestore --project demo-coplanly   # terminal 1
npm run test:only                                                    # terminal 2
```

## Layout

| Path | What it covers |
| --- | --- |
| `harness.js` | Test-environment factory, seeding with rules disabled, ruleset paths |
| `fixtures/rules-b2bf6b83-incident.rules` | Frozen copy of the ruleset blamed for the expenses-delete incident |
| `rules/expenses-delete-incident.test.js` | The incident, run against **both** that ruleset and the current one |
| `rules/is-partner-of.test.js` | The `isPartnerOf` helper against every shape a `users` document can have |
| `rules/storage.test.js` | `storage.rules`: the four prefixes, the JPEG and 5 MB limits, and the closing deny-all |
| `rules/notification-queue.test.js` | The enqueue allow path (co-parent target) and the phishing denials |
| `rules/events.test.js` | `events` read/create/update/delete and the `sharedWith` sync query |
| `rules/child-info.test.js` | `child_info`, including a document whose `sharedWith` was stripped |
| `rules/invitations.test.js` | Pairing invitations: mint, withdraw, decline, and the queries the listeners run |
| `rules/conversations-messages.test.js` | Chat: membership immutability and the `isRead`-only message update |
| `rules/budgets-change-requests.test.js` | `budgets`, `expenses` (non-delete) and `change_requests` |
| `rules/unpair-revocation.test.js` | What the ex-partner can still reach after `unpairCoParent` sweeps `sharedWith` |

Each test file uses its own emulator project id, so several rulesets can be loaded at
once — that is what lets the incident reproduction run beside the shipped rules.

## Conventions

- Document shapes must mirror what the client actually writes. The authoritative sources
  are `EventRepositoryImpl.toFirestoreMap()`, `SyncService`, `ExpenseRepositoryImpl`,
  `PairingRepositoryImpl.writeNewInvite` and `MessageRepositoryImpl`.
- Cover the collection **queries** the client runs, not only single-document access — a
  rule can admit a document by id and still reject the listener that fetches it.
- The emulator proves rule *semantics*. It does not prove production behaviour: deployed
  rulesets, real documents and client-side offline caching are all outside its reach.
