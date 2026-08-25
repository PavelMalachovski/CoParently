# CoPlanly — roadmap and backlog

One document. It replaces `docs/BACKLOG.md` (everything known to be missing, broken or worth
improving) and `docs/CoPlanly/MVP_phases.md` (the original three-phase feature plan), which were
merged here on 2026-08-25 and deleted. Audits written before that date cite them by their old
names; the content is all below.

It answers three questions, in this order:

1. **Where can each remaining piece of work actually be done** — a Claude session in the cloud, or
   your own computer and phone? §1.
2. **What did the three MVP phases ask for, and what is actually built?** §2.
3. **What is left, item by item, and why** — §3 through §9, with the dependency order in §10.

Last updated: 2026-08-25, after PR #76 (multi-family, M-1 … M-4). The reasoning behind most items
lives in `docs/AUDIT-2026-08.md` under the § numbers cited; `docs/DESIGN-multi-family.md` is the
plan of record for the `M-*` items.

## How to read this

**Every item has a stable id** (`REL-3`, `SEC-2`, `CQ-7`, `UX-11`, `MON-5`, `FAM-4`, `M-6`). Use it
in commit messages and PR titles, so an item can be traced from the plan to the diff without
matching prose. Ids are never reused: an item that turns out to be done keeps its number and gains
**DONE**, so a reader who finds it cited somewhere else can still find out what it was.

| Priority | Means |
| --- | --- |
| **P0** | Ships broken, loses data, or blocks the release outright. Do before anything else. |
| **P1** | A user hits it in normal use and cannot work around it. |
| **P2** | Real, but survivable and not on the launch path. |
| **P3** | Hygiene. Do it while touching the area anyway. |

Sizes are **S** (a day or less), **M** (a few days), **L** (a week or more).

Every open item also carries a **Where** line:

| Mark | Means |
| --- | --- |
| ☁️ **Cloud** | A Claude session does it end to end — Kotlin, rules, Cloud Functions, docs, tests. CI is the Android compiler and the unit-test runner. |
| ⚙️ **Cloud + a CI job that does not exist yet** | The work itself is code, but proving it needs a Gradle task or an emulator no workflow runs today. Add the job once, and it becomes ordinary cloud work. |
| 👁 **Cloud writes it, a device answers it** | It can be written and compiled here; whether it is *right* is only visible on a phone, in a console, or against a real Firebase project. |
| 💻 **Yours only** | A keystore, a console, a deploy, a second phone, a lawyer, a phone call. No session can do it. |

**Why the line exists.** The Claude sessions that do most of this work run in a container with **no
Android SDK**: Gradle cannot be invoked here at all, so nothing Android is compiled, run or seen
locally. What *is* verifiable in the session is the Firestore rules suite against the emulator
(`firestore-tests/`, needs a JDK 21+) and the Cloud Functions suite (`functions/`, mocha + eslint).
Everything Kotlin is proved by **CI** — `assembleDebug`, `testDebugUnitTest`, `lint`, `detekt`
(reporting only, **CQ-12**) and `assembleRelease` so R8 runs. CI has an Android SDK but **no
emulator job**, so nothing instrumented runs anywhere today (**CQ-1**). And no session holds
Firebase or Play credentials: every `firebase deploy`, every console change and every callable
invocation is yours.

---

## 1. Where the remaining work can be done

### ☁️ Cloud — a session can take these now

| Id | What | Pri | Size |
| --- | --- | --- | --- |
| **M-5** | Multi-family cleanup: delete `partnerId`, `User.role`, `Event.sharedWith`, `isPartnerOf` — **after** the ops steps in REL-3 | P2 | M |
| **M-8** | M-4's leftovers: badges that count across families, `familyId` on pushes, a switcher chip in the top bar | P2 | M |
| **SEC-1 §2** | OAuth token exchange moves to a Cloud Function — the client secret stops shipping in the APK | P0 | M |
| **SEC-2** | Room is not encrypted at rest | P1 | M |
| **CQ-5** | Sync downloads the entire event collection every 15 minutes | P1 | M |
| **CQ-6 + CQ-8** | The chat's Room query is unbounded, and a failed listener gives up for the life of the process. One piece of work, not two | P2 | M |
| **CQ-11** | The declared error model is not the one in use | P3 | S |
| **CQ-13** | Seventeen of twenty-five ViewModels have no tests | P2 | M |
| **CQ-14** | User-facing strings produced inside ViewModels and services | P2 | M |
| **CQ-15** | The last of the dead code, and one decision about it | P3 | S |
| **CQ-17** | Six dependencies worth moving | P3 | S |
| **UX-9** | Five different empty-state anatomies, one of which renders under the top bar | P2 | M |
| **UX-12** | Clerical English success messages — and a branch on the literal that will break when they are localised | P2 | S |
| **UX-14** | Four different brand purples | P3 | S |
| **UX-15** | `ParentColors` is adopted at roughly a quarter | P3 | S |
| **UX-16** | Drag an event to reschedule it (MVP 3) | P3 | S |
| **UX-18** | A ratio agreed before pairing becomes the pair's agreement silently | P2 | S |
| **MON-2** | Verify the market facts — most of them are public pages | P0 | S |
| **MON-3** | Export to PDF/CSV — the first paid feature (needs MON-4 first) | P1 | M |
| **MON-4** | Decide what a court-facing record guarantees | P1 | M |
| **MON-5** | Digitise the official Rodičovský plán | P1 | M |
| **MON-6b** | Half-day custody, so contact afternoons can be described | P2 | L |
| **MON-8** | Bakaláři / EduPage school import — the parsing, once you supply a real export | P2 | L |
| **MON-11** | Payments (MVP 3) — the entitlement model, after MON-1 decides the price | P2 | L |
| **MON-12** | Intelligent suggestions (MVP 3) — behind SEC-1's proxy, never with a key in the client | P3 | M |
| **MON-13** | Five of the six countries in the picker still have no holiday table (the country setting itself is done) | P2 | M |
| **FAM-4** | Custody per child | P2 | L |
| **REL-4 (drafting)** | Fill the placeholders in the legal drafts, write the web account-deletion page | P0 | S |
| **REL-5** | An analytics consent gate for the EU | P0 | M |

### ⚙️ Cloud, but a CI job has to be built first

| Id | What | Why it needs a job |
| --- | --- | --- |
| **CQ-1** | Restore the Room schemas (v15 → v33) and test the migrations | Exporting a schema is a Gradle task, so CI can do it and upload `app/schemas/` as an artifact; **running** a migration test needs an Android emulator, and no workflow starts one. Both jobs are writable here; neither exists. |
| **CQ-12** | Regenerate the detekt baseline, then let detekt gate again | `./gradlew detektBaseline` needs the Android SDK. A one-shot `workflow_dispatch` job that runs it and uploads the XML is enough — commit the artifact, delete `continue-on-error`. |

### 👁 Cloud writes it, only a device or a console can say whether it is right

| Id | What | What has to be seen |
| --- | --- | --- |
| **SEC-1 §1** | Storage rules keyed on Firestore state (cross-service rules — the "this needs the proxy" claim was a factual error) | The **Storage emulator does not resolve cross-service calls**, so `firestore-tests/` cannot cover it. Settle the verification story — a staging bucket against a real project — before writing the rule. |
| **SEC-5** | `androidx.security:security-crypto` is on an alpha holding OAuth tokens | A dependency bump compiles in CI; whether tokens survive it is a sign-in on a real device. |
| **UX-8** | The second half: two surfaces colour a chip from two different sources | An owner's answer to "what does a chip's colour mean" — the event's owner, or whose day it falls on. |
| **UX-13** | Light theme is unverifiable rather than incomplete | Six previews across 148 UI files, none on a main screen; and a white flash on a dark cold start that only a device shows. |
| **FAM-5** | The event chip does not say who it is about | Chips are single-line with ellipsis and every colour channel is spent. Worth an owner's eye on a real device rather than a treatment invented blind. |
| **M-4 (shipped, unseen)** | The colour palette, the family switcher, the second-co-parent invite | Kotlin compiled in CI; nobody has looked at it. |

### 💻 Yours only — no session can do these

| Id | What | Note |
| --- | --- | --- |
| **REL-3 ops** | `firebase deploy --only functions` → invoke `backfillFamilyDocuments` → invoke `backfillRecordFamilyIds` → `firebase deploy --only firestore:rules` | **The order matters.** PR #76's isolation is inert until this runs, and running the rules deploy before the record backfill leaves each co-parent's expenses looking empty on the other phone. `functions/README.md` has the runbook. |
| **REL-3 storage** | `firebase deploy --only storage` | One command that fixes a live bug: every pet and medical photo upload is refused today because the bucket still runs the July rules. |
| **REL-1** | Firebase console, Google Cloud console, a fresh `google-services.json`, the debug and release SHA-1 | A local build fails until this is done — deliberately, since `applicationId` changed to `app.coplanly`. |
| **REL-2** | Generate the release keystore and back it up in two places | The single most irreversible item in this document. |
| **REL-4 (legal)** | A lawyer reads the drafts; both documents get hosted at stable URLs | This app processes a child's health data. No template survives that unread. |
| **REL-6** | Play Console: Data Safety, listing, screenshots, content rating, a closed track with **real co-parent pairs** | This product cannot be tested by one person. |
| **REL-7** | Install a release build and confirm a child's medical profile reaches the co-parent non-empty | The one test CI cannot run: a green `assembleRelease` proves R8 ran, not that Gson still finds its field names. |
| **CQ-16** | Digital Asset Links | Needs a domain you own — the same one REL-4 needs. |
| **CQ-18** | Cross-time-zone chat on two phones | Two devices, two zones. Unit tests already drive the logic; this is the acceptance run. |
| **MON-1** | Price, unit (family, not seat), and what the free tier contains | A decision, and it shapes the code that follows. |
| **MON-9** | Distribution: mediators, Cochem courts, OSPOD, NGOs | Phone calls and meetings. A session can draft the material; it cannot make the call. |
| **MON-8 (input)** | A real Bakaláři or EduPage export | The parser is cloud work; it needs one actual file to be written against. |

### If you want a shortlist of what to hand a session next

In this order, and each is genuinely finishable in the cloud:

1. **CQ-12 then CQ-1** — the two CI jobs. After them, detekt gates again and migrations are tested
   for the first time since v14, which is the precondition for trusting any later schema change.
   Three untestable migrations have been added since this document was written; the number only
   grows.
2. **MON-4 → MON-3** — settle what the record guarantees, then build the export. In that order: an
   export of a record nobody can vouch for is worth nothing to a lawyer.
3. **UX-18** — the last of the honesty gaps: a split ratio agreed before pairing becomes the
   pair's agreement of record without telling the other parent. (**CQ-20** and **UX-17**, which
   were the other two, are done.)

*(**M-6** and **CQ-19**, which headed this list, are done.)*

---

## 2. The three MVP phases, re-baselined against the code

This is the original `MVP_phases.md` matrix with two columns added: what is actually built, and
the item id that now carries whatever is left. Checked against the tree on 2026-08-25, not
remembered. **This closes MON-10** — the roadmap said "MVP 2 is next" for months after MVP 2 had
shipped, and a plan that describes work already done is worse than no plan.

### MVP 1 — Foundation and core calendar · **complete**

| Feature | Original detail | Diff | Pri | Status |
| --- | --- | --- | --- | --- |
| Setup custody model at start | Clear. Also %-wise | M | High | **Done.** `CustodyModel` with four presets (`EVERY_OTHER_WEEKEND` added by MON-6), and the %-wise half is the agreed split ratio in `family_settings/{pairId}` |
| Month first, week next, day third | No 3-day view | S | High | **Done.** `MONTH, WEEK, DAY`; there is no 3-day view and there should not be |
| Switch between "You" and "Him" view | In Day view, besides Week/Month | — | High | **Done.** `ParentFilter` in the calendar filter sheet |
| Have mom and dad selected at once | Show mutual views at the same time | M | High | **Done.** `ParentFilter.BOTH` is the default |
| Event type — default + add your own | Predefined and manual filters | L | High | **Done.** Five defaults plus user-defined types created in the filter sheet |
| Reoccurrence | Clear | S | High | **Done.** `RecurrenceExpander`; CQ-4 removed the two-year cliff |
| Confirm pickup | Other side sees it is picked up | S | High | **Done.** `pickupConfirmedBy` / `pickupConfirmedAt` |
| Notifications | 30 min or 1 h before pickup | M | High | **Done.** `ReminderScheduler` + WorkManager; the permission is asked contextually, never on cold start |
| Holidays and vacations by country | Clear | S | High | **Partly.** The country is now asked for and stored (MON-13), and `CzechHolidays` is computed and correct — but it is still the only table, so picking any other country draws no holidays rather than the wrong ones |
| Add events only you can see | Related to switching views | S | High | **Done.** `isPrivate`, filtered out of every sync path |
| Sat/Sun a different colour | Clear | S | High | **Done.** `DayCellFills` draws the weekend as a base layer under custody, never instead of it |

### MVP 2 — Communication, receipts and dashboards · **complete**

| Feature | Original detail | Diff | Pri | Status |
| --- | --- | --- | --- | --- |
| Receipts | Extra section | L | High | **Done.** On-device OCR (ML Kit → `ReceiptParser`); no receipt text or photo leaves the device |
| Change requests | Shown as notification and in the dashboard | M | High | **Done**, and the honesty gap that outlived it is closed too — a request that has not left the phone says Queued (**CQ-20**) |
| Weekly summary | Dashboard of next week's mutual activities | M | High | **Done.** Exactly one entry point, at the bottom of Home |
| First screen updates | Last 5 changes both parents can see | L | Medium | **Done.** The recent-changes feed on Home |
| Structured chat → change request | Button, new date, notification | M | Medium | **Done** |
| Attach image to the event | Clear | L | Medium | **Done.** `Event.imageUrl` into `event_images/` — which is one of the two Storage prefixes the **live** bucket still covers; `pet_photos/` and `medical_photos/` are refused until REL-3's storage deploy runs |

### MVP 3 — Automation and integrations · **not started, and repriced**

Every line here was **Low** priority. Two of them are the strongest items in the whole plan, and
one of them should probably not be built at all.

| Feature | Original detail | Diff | Pri | Now |
| --- | --- | --- | --- | --- |
| Import (Bakaláři / EduPage) | From a PDF export, broken into events | XL | Low | **MON-8, P2 · L.** Mispriced at Low: every Czech parent's school schedule lives in one of those two systems, it solves cold-start, and no US competitor will build it. Document understanding makes XL smaller than when the line was written |
| Payments | Clear | XL | Low | **MON-11, P2 · L.** Gated on MON-1's pricing decision — and **Onward closed on 8 October 2024** built entirely on expense splitting and payments. Expense reimbursement does not carry a product on its own |
| Exports to PDF/CSV | Summary / punctuality. CSV preferred | M | Low | **MON-3, P1 · M.** Backwards at Low: this is the **first paid feature**. Willingness to pay concentrates on documentation you can hand to a lawyer. Blocked on **MON-4** |
| Intelligent suggestions | Based on past schedules | M-L | Low | **MON-12, P3 · M.** Only behind SEC-1's proxy — the AI subsystem was deleted with its key (MON-7), and it comes back as *one* feature, never eight |
| Time setting by dragging | Whole event by 15 min, corners by the minute | S | Low | **UX-16, P3 · S.** The smallest item here and the one a user notices daily |

### What none of the three phases contains

The MVP plan is a feature plan. It has no line for the release blockers (§3), the security work
(§4), or the multi-family model (§9) — and **none of MVP 3 is worth starting before §3 is done**,
because an unpublished app earns nothing from any of it.

---

## 3. [REL] Release blockers — the app cannot be published until these are done

None of these is engineering. They are decisions, accounts, deploys, and a lawyer.

### REL-1 · **decided, half done** · `applicationId` is now `app.coplanly`

**Where:** 💻 yours only — the code half is committed.

Decided and changed in code while it still could be. After the first Play upload an
`applicationId` can never change — a different one is a different app, with no upgrade path for
anyone who installed the first. The old id said `com.coparently.app` while the product is CoPlanly
and the deep-link scheme is `coplanly://`.

`namespace` stays `com.coparently.app` on purpose: it is the Kotlin package and therefore where `R`
and `BuildConfig` are generated. Renaming it would touch every file in the tree for no user-visible
gain, and the two are allowed to differ.

- [x] Change `applicationId` in `app/build.gradle.kts`.

**The rest needs the consoles, and until it is done a local build fails.** That is deliberate: the
Google Services plugin matches `google-services.json` on the package name and will report *"No
matching client found for package name 'app.coplanly'"*. CI is unaffected — `google-services.json`
is gitignored, so the plugin is not applied there.

- [ ] **Firebase console** → project `coparently-a39c9` → Add app → Android → package name
      `app.coplanly`. Register it alongside the existing app rather than deleting that one.
- [ ] Download the new `google-services.json` and replace `app/google-services.json`. One file can
      hold both clients, so one download covers it.
- [ ] **Google Cloud console** → Credentials → the Android OAuth client used for Calendar: set the
      package name to `app.coplanly` and re-enter the debug SHA-1 (`keytool -list -v -keystore
      ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android`).
      Google Sign-In and the Calendar scope both stop working otherwise, and the failure looks like
      a generic sign-in error rather than a config one.
- [ ] Add the **release** SHA-1 once **REL-2** produces a keystore.
- [ ] Re-check that pairing, guest and chat deep links still open the app. They use a custom scheme
      rather than the applicationId, so they should be unaffected — confirm rather than assume.
- [ ] Uninstall the old build from any test device first: to Android these are two different apps.

### REL-2 · P0 · Signing configuration and the keystore

**Where:** 💻 yours only. The ~20-line `signingConfig` block can be prepared in the cloud; nothing
else here can.

- [ ] Generate a release keystore.
- [ ] **Back it up in two places.** Losing it means the app can never be updated again — not
      re-signed, not recovered, not appealed. The single most irreversible item in this document.
- [ ] Add a `signingConfig` reading passwords from `~/.gradle/gradle.properties` or the
      environment, never a tracked file — the pattern `GOOGLE_CLIENT_SECRET` already uses.
- [ ] Decide whether to enrol in Play App Signing.

### REL-3 · P0 · Deploy the rules, the functions, and the storage rules

**Where:** 💻 yours only. Everything being deployed was written and tested in the cloud; none of it
does anything until this runs.

Everything server-side from both audits is **inert until this runs** — including the fix for a live
full-calendar disclosure (audit §2.1) and the whole of PR #76's family isolation.

**The multi-family ops sequence, in this order** (`functions/README.md` has the detail):

1. [ ] `firebase deploy --only functions`
2. [ ] Invoke `backfillFamilyDocuments` — every live pair gets `members`, `slots`, `caresFor`
3. [ ] Invoke `backfillRecordFamilyIds` — every record gets its `familyId`
4. [ ] `firebase deploy --only firestore:rules,firestore:indexes`

Both callables are idempotent and report per-reason counts. **Running 4 before 3** leaves each
co-parent's expense and budget history looking empty on the other phone until 3 completes — nothing
is lost, since Room is the source of truth, but it is alarming to watch.

**Separately, and it fixes a live bug:**

- [ ] `firebase deploy --only storage`. The bucket still runs its July 2026 rules, which cover
      `receipts/` and `event_images/` only, so `pet_photos/**` and `medical_photos/**` fall through
      to the catch-all `allow read, write: if false` and **every pet and medical photo upload is
      refused today**. The client path is sound and was ruled out end to end. Nothing catches this:
      `firebase.json` configures a Firestore emulator only, and Storage rules have no test coverage
      at all. This also closes the unchecked box at `docs/REVIEW-2026-07-23.md:65`.

**And the accounts:**

- [ ] Set `functions/.env`: `SENDGRID_API_KEY`, `INVITE_FROM_EMAIL`, `INVITE_FROM_NAME`. Until they
      are set, invitations record `emailDelivery: 'not_configured'` and are not sent.
- [ ] Verify the sending domain (SPF/DKIM), or invitations land in spam — which looks identical to
      not being sent.
- [ ] **Decide the Firestore region.** An EU region makes the whole GDPR story simpler and cannot
      be changed once data exists. Check what `coparently-a39c9` uses today.
- [ ] Sign a DPA with Google covering Firebase.

### REL-4 · P0 · Legal documents: review, host, link

**Where:** ☁️ the drafting and the deletion page; 💻 the review and the hosting.

Drafts are in `docs/legal/`. They are drafts. **Have a lawyer read them before publishing** — this
app processes a child's health data, which is special-category data under GDPR Art. 9, and no
template survives that unread.

- [ ] Fill every `{{PLACEHOLDER}}` in `PRIVACY-POLICY.md` and `TERMS-OF-SERVICE.md`: controller
      identity, address, contact. *(cloud, once you supply the identity)*
- [ ] Legal review. *(yours)*
- [ ] Host both at stable URLs. Play requires the privacy-policy URL in the listing. *(yours)*
- [ ] Publish a **web account-deletion page** — Play requires a deletion route that works without
      the app installed; the in-app path alone does not satisfy it. *(the page is cloud work; the
      hosting is yours)*
- [ ] Link both from Settings once the URLs resolve. Deliberately not wired yet: a row pointing at
      a dead URL is exactly the affordance-promising-nothing that design rule #8 forbids.

This unblocks **CQ-16** too — both want the same domain.

### REL-5 · P0 · M · Analytics consent for the EU

**Where:** ☁️ cloud, 👁 worth a look on a device.

`ENABLE_ANALYTICS` / `ENABLE_CRASHLYTICS` are honoured per build type, but a release build still
collects by default. An EU launch needs a consent gate, not a build flag.

- [ ] First-run consent screen, defaulting to **off**, persisted, reachable again from Settings.
- [ ] Wire it to `setAnalyticsCollectionEnabled` / `setCrashlyticsCollectionEnabled` at **runtime**,
      not only at injection time.
- [ ] Update `docs/legal/DATA-SAFETY.md`, which currently records the gate as outstanding.

### REL-6 · P0 · Play Console

**Where:** 💻 yours only. The Data Safety answers can be re-derived from the schema in the cloud.

- [ ] Data Safety declaration — answers derived from the real schema are drafted in
      `docs/legal/DATA-SAFETY.md`. Check them against the code before submitting; a wrong
      declaration is a policy violation, not a typo.
- [ ] Store listing, screenshots, feature graphic. Czech first, English second.
- [ ] Content rating questionnaire.
- [ ] Closed testing track with **real co-parent pairs** — this product cannot be tested by one
      person, and the failure modes only appear across two devices.

### REL-7 · P0 · Prove R8 on a device — the one test CI cannot run

**Where:** 💻 yours only, by definition.

- [ ] Install a **release** build, save a child's medical profile, confirm it reaches the co-parent
      non-empty.

A green `assembleRelease` proves the build survives shrinking. It does **not** prove Gson still
finds its field names afterwards, which is the defect (audit §2.8) the keep rules in
`proguard-rules.pro` were written for, and which had already shipped once. Nothing but a real APK
answers this. While you are there, the same run is the cheapest moment to eyeball what M-4 shipped
unseen: the colour picker, the family switcher, and the second-co-parent invite.

---

## 4. [SEC] Security

The August audit closed fifteen findings, four of them critical, and PR #76 closed the
cross-family expense leak. What follows is what neither closed. Full reasoning in audit §3.

### SEC-1 · P0 · Two independent pieces, not one build

**Where:** ☁️ the OAuth callable; 👁 the Storage rules — they cannot be covered by
`firestore-tests/`, so settle the verification story first.

The item used to read "one Cloud Function proxy closes three holes". Two of the three do not need
a proxy, and one of them does not need one for a reason that was a **factual error** in the
original.

1. **Cloud Storage** — every rule is `request.auth != null`. Any signed-in CoPlanly user who learns
   an object path can **overwrite or delete** it: a receipt, an event photo, a photograph attached
   to a child's medical record. Paths are not secrets — they are built from ids a co-parent has
   held, and an ex-partner's local Room copy survives both sign-out and the unpair sweep. Not
   patched with a plain owner check on purpose: **both** parents legitimately manage the same
   files, so a uid check would deny the co-parent a deletion the app itself offers them.

   **This does not need the proxy.** Both this item and `storage.rules` asserted that Storage rules
   cannot read Firestore. They can: cross-service Security Rules (September 2022) give Storage
   rules `firestore.get()` and `firestore.exists()`, two document reads per evaluation. So the fix
   is a rules change keyed on the state `firestore.rules` already gates on — **S, not L**.

   Two traps, both found by looking rather than by reasoning:
   - A receipt is **uploaded before its expense document exists** (`ExpenseViewModel.addExpense`
     mints a UUID, uploads, then writes the expense). A rule requiring the document would reject
     every first upload. Gate *overwrite and delete*, which is the harm; a create at a fresh UUID
     path is an orphan blob.
   - **The Storage emulator does not resolve cross-service calls.** Verified with both emulators
     running and the document confirmed present (firebase-js-sdk#6803, firebase-tools#5251). This
     project has already shipped one broken delete rule by testing a rule some other way. **Settle
     how it will be verified before writing it** — a staging bucket against a real project is the
     likely answer.
2. **OAuth token exchange** — the Google client secret ships in every APK, with no PKCE. Carried
   over unfixed from the July 2026 audit. This is what the proxy was really for, and it is ordinary
   cloud work: a callable, its mocha tests, and the Android side compiled by CI.
3. **Model calls, if AI ever returns.** The Gemini key used to ship in the APK too; the subsystem
   and the key are gone (**MON-7**), so this is a precondition rather than a live hole — nothing
   goes to a model again without the proxy in front of it.

### SEC-2 · P1 · M · Room is not encrypted at rest

**Where:** ☁️ cloud writes it; ⚙️ the migration wants the emulator job from CQ-1 before it can be
proved.

`EncryptionManager` (AES-256-GCM, Keystore) exists, is correct, and **is not applied to the
database**. A child's medical profile, the full chat history and every expense sit in plain SQLite.
SQLCipher plus a migration, or — as a smaller first step — field-level encryption of the medical
profile alone. Audit §3.3.

### SEC-5 · P3 · S · `androidx.security:security-crypto` is on an alpha

**Where:** 👁 the bump compiles in CI; whether tokens survive it is a sign-in on a real device.

`1.1.0-alpha06`, holding OAuth tokens in production, on a branch that is effectively frozen.
Decide: pin and document, or move off it.

---

## 5. [CQ] Code quality, correctness and platform

### CQ-1 · P0 · M · Restore the Room schemas (v15 → v33)

**Where:** ⚙️ cloud, once two CI jobs exist. Exporting schemas is a Gradle task CI can run and
upload as an artifact; running a migration test needs an Android emulator, which no workflow starts
today.

`CoPlanlyDatabase` is at `version = 33`; `app/schemas/` stops at `14.json`. The files were never
committed, so they cannot be recovered from git — they must be regenerated. Every migration test
above v14 fails with *"Cannot find the schema file in the assets folder"*, which means **migrations
15→33 have never run against real SQLite**. `DatabaseModule` deliberately does not fall back to
destructive migration above v4: a broken migration is a **crash on launch** for a user with real
data, not a wipe.

This is the item that gates several others. **FAM-2**'s dead `childId` columns cannot be dropped
without it (a column drop is a table rebuild, and `MIGRATION_12_13` is only provable because
`12.json` exists), **SEC-4**'s timestamp conversion has no instrumented test for the same reason,
**CQ-19** and **MON-13** have just added two more that nobody can prove, and **SEC-2** will add another.

- [ ] Regenerate per version (`./gradlew kaptDebugKotlin` at each version-bump commit), or accept
      the gap, export 31 only, and document the untested migrations.
- [ ] Write the missing tests for the migrations that shipped in `versionCode 2` untested — this is
      what the old **CQ-2** id referred to; it is folded in here.
- [ ] Add the instrumented job to CI. It is deliberately absent from `.github/workflows/ci.yml`
      today because it would be red from its first run.
- [ ] Have CI assert `version == max(schemas)` so this cannot silently recur.

### CQ-5 · P1 · M · Sync downloads the entire event collection every 15 minutes

**Where:** ☁️ cloud — but settle the two design questions below first, in writing.

`observeEventsSharedWith` has no date window and no limit. Re-measured on 2026-08-25: a bound
appears **twice** in all of `app/src/main` — a `limit(1)` on a user lookup and the chat's
`limitToLast` from CQ-6 — so nothing the calendar reads is bounded at all. (The backlog used to say
"exactly once"; CQ-6 added the second.) A couple with ~4 events a day reaches 4–5 thousand
documents in three years,
and `SyncWorker` runs every 15 minutes on both devices. A Firestore bill that scales with tenure
rather than usage, landing first on the users who stayed longest.

**It is not "a rolling window plus a `lastSyncAt` delta"**, as the backlog used to say:

* a delta on `updatedAt` would miss every deletion, because tombstones deliberately do not move it
  (and `updatedAt` carries SEC-4's ordering defect anyway);
* a date window on `startDateTime` cuts off the master row of a recurring series that began before
  it.

Settle both before writing any of it. Related: `HomeViewModel` holds three subscriptions to the
whole events table plus one to all expenses and filters the current month **in memory**, while
`EventDao.getEventsForParentPaginated` sits written and never called. Audit §8.6.

### CQ-6 + CQ-8 · P2 · M · The chat's last unbounded query, and a listener that gives up

**Where:** ☁️ cloud. One piece of work; doing either alone makes the other harder.

**Two of CQ-6's three costs are already gone.** The home screen answers the unread badge with a
Room `COUNT(*)` instead of loading the thread, and the remote listener is bounded to the newest 200
messages (`limitToLast`, not `limit` — the order is ascending, so `limit` would pin the window to
the oldest messages and a live thread would stop updating at the bound).

**What is left:** `MessageDao.getMessages` is still unbounded, so the chat screen materialises the
whole thread out of Room. Bounding it needs a "load earlier" affordance — silently showing only the
tail would be the CQ-7 defect again in a different collection.

**CQ-8 is the reason it cannot be done alone.** Both mirror branches in `MessageRepositoryImpl` now
go through `reconnecting()` (`retryWhen`, exponential backoff, eight attempts, capped at a minute)
before reaching the `.catch` that ends the mirror — which covers what was seen in production: on
the first launch after install both listeners were denied ~0.5 s before `ensureConversation`
created the document, and that whole session ran on local data while looking entirely healthy. An
outage longer than the backoff still ends in that state and still lasts until the process restarts,
because `.catch` *completes* the flow and `SharingStarted.WhileSubscribed` cannot restart it:
`NavGraph.rememberChatUnreadCount()` holds an Activity-scoped `ChatViewModel` collecting
`unreadCount` for the whole process lifetime, so the subscriber count never reaches zero. That same
collector is what keeps the mirror alive at all, which is why the cheap count cannot replace it
until something else does.

Structural fixes: await `ensureConversation` before subscribing, or drop the Activity-scoped
collector. **Do not** "fix" it by removing the `.catch` — an uncaught failure in
`viewModelScope.launch` terminates the process — and do not make the retry unbounded: a genuinely
broken rule would then reconnect for the life of the process, and any test of the give-up path
spins on the virtual clock instead of finishing.

### CQ-11 · **PARTLY DONE** · P3 · S · Error handling is declared but not wired

**Where:** ☁️ cloud.

**Done:** all ten `printStackTrace()` calls now record to Crashlytics; `SyncWorker` logs and reports
both its failure paths and gained the `NetworkType.CONNECTED` constraint it was missing.

**Still open:** `domain/error/AppError.kt` and `ErrorHandler.kt` have three references outside their
own package, and 228 `catch` blocks — 116 of them `catch (e: Exception)` — are the error model in
use. Audit §8.12. Decide whether `AppError` becomes real or goes; a declared model nobody uses is
worse than none, because it reads as coverage.

### CQ-12 · P2 · S · Regenerate the detekt baseline, then let detekt gate again

**Where:** ⚙️ cloud, through a one-shot CI job. `./gradlew detektBaseline` needs the Android SDK,
so run it in a `workflow_dispatch` job that uploads the XML, then commit the artifact.

CI's first run found **194 weighted issues**, essentially all pre-existing: `AddEditEventScreen` at
1,246 lines, `AnalyticsManager` with 22 functions, and every screen added since the baseline was
last generated. detekt therefore runs with `continue-on-error: true` — a report, not a gate.
Deliberate and temporary, commented as such in the workflow.

- [ ] Generate the baseline, commit it.
- [ ] Delete `continue-on-error: true` so new violations fail again.
- [ ] Optionally work the debt down afterwards; the baseline records what was accepted.

### CQ-13 · P2 · M · Test coverage is concentrated in pure domain logic

**Where:** ☁️ cloud — JVM unit tests are exactly what CI runs.

**Seventeen of twenty-five ViewModels have no tests** — including `ChildInfoViewModel`, whose
overwrite-the-wrong-child defect (**CQ-9**) has no regression test guarding it. `SettingsViewModel`
and `SyncViewModel` were removed as stale and never rewritten; `ChildInfoViewModelTest`,
`PairingViewModelTest` and `SyncServiceTest` are back.

The first four CI runs are the argument: 30 unit tests were failing because their mocks had gone
stale against collaborators added months earlier, and nobody knew. Tests that do not run are not
coverage.

### CQ-14 · P2 · M · User-facing strings produced inside ViewModels and services

**Where:** ☁️ cloud.

`GoogleCalendarSyncState.message`, sync/status errors, `NavGraph`'s "Checking authentication…" —
hardcoded English, unreachable by `stringResource`. Extracting them needs a resource-provider
abstraction. **Do not** inject `Context` into a ViewModel ad hoc to fix one. Blocks **UX-12**.

It used to be recorded as blocking **SEC-3** too. It did not: push text moved to the *receiving*
device, which has a `Context` and all five translations. Worth remembering when the next item
claims to be blocked behind this one — the question to ask is which side of the wire the string is
finally read on.

### CQ-15 · **PARTLY DONE** · P3 · S · Dead code

**Where:** ☁️ cloud.

**Most of the original list was wrong, and it was measured rather than re-read** — three entries had
consumers all along and two more had since been wired. 1,264 lines were deleted; the rest stays.

**Still open, and it is a decision rather than a deletion:** `ErrorDisplay` and `CoPlanlySnackbarHost`
are unreferenced, and this item originally asked for them to be *wired* rather than deleted. UX-2
then decided that a failed list read stays a loaded empty value rather than an error surface, so
the case for `ErrorDisplay` is weaker than when this was written. Decide it before doing either.
Also: `presentation/common/animations/LoadingSkeleton.kt` duplicates `SkeletonBox` — one of the two
files should go.

**Not to be deleted**: the five `EventDao` methods including `getEventsForParentPaginated`, which is
the thing CQ-5's Home-screen half would use. Deleting it now would be deleting the answer.

### CQ-16 · P3 · S · No Digital Asset Links

**Where:** 💻 yours — it needs a domain and the release fingerprint.

`CredManMissingDal` is disabled in `app/build.gradle.kts` with that rationale. Credential Manager's
password sign-in cannot share a credential with a website, and the pairing deep link stays a custom
scheme rather than a verified App Link. Both need the domain **REL-4** needs for hosting.

### CQ-17 · P3 · S · Dependencies worth moving

**Where:** ☁️ cloud for the bumps; 👁 the sign-in ones want a device.

| Dependency | Now | Why |
| --- | --- | --- |
| `androidx.security:security-crypto` | 1.1.0-alpha06 | See **SEC-5**. |
| `play-services-auth` | 21.2.0, deprecated | Both it and Credential Manager are in the graph — two sign-in paths, twice the size. |
| `androidx.work` | 2.9.0 | 2.10.x fixes the Doze/foreground bugs that hit a 15-minute sync. |
| `google-api-services-calendar` | `v3-rev20220715` | A 2022 revision. |
| `firebase-functions` (Node) | ^4.5.0, gen-1 API | Two generations behind; ESLint 8 is EOL. |

*(`retrofit` left the graph with the AI subsystem — MON-7.)*

### CQ-18 · P3 · S · Cross-time-zone chat was implemented but never verified on two devices

**Where:** 💻 yours — two phones, two zones.

Epoch-millis message times are covered by unit tests that drive two zones explicitly
(`ChatReadStateTimeZoneTest`) plus a 12→13 migration test. The **two-phone acceptance run** — set
one phone 2–3 hours apart, send, confirm unread counts, badge clearing and READ ticks — was
deferred, not run. Everything else in that acceptance round passed on real devices.

### CQ-19 · **DONE** · Deleting a child or a pet removed the document outright

`ChildInfoRepositoryImpl.deleteChildInfo` and `PetRepositoryImpl.deletePet` both called the data
source's `.delete()` and both discarded the `Result` — exactly what **CQ-3**'s tombstone rule exists
to prevent. Two consequences: the co-parent's phone never learned of the deletion (nothing
reconciles by absence, correctly), so the record stayed on their device forever; and a refused or
offline remote delete left the local row gone and the document alive, so the next download
re-inserted it.

Pre-existing and systemic across both record types — the multi-child work only made the child half
*reachable*, by putting a Delete action on the editor where before there was none.

Fixed with the treatment `data/sync/Tombstone.kt` already defined, Room schema 32, and no rule
widened: tombstoning turns a `delete` into an `update`, and both collections already admitted the
creator and their co-parent as writers (pinned in `deletion-tombstones.test.js`, including that a
tombstoned record stays *readable* — a deletion nobody may read is a deletion nobody is told
about). Three things that were not obvious going in:

- **`child_info` is synced in two places** — the repository's `pullOnce` *and* `SyncService` — so
  the outbox split had to be made twice. Sending a pending tombstone through `upsertChildInfo`,
  which is a `set()`, would rewrite the document from a row that exists only to record its own
  deletion, wiping the tombstone and resurrecting the child on both phones.
- **`getChildInfoById`/`getPetById` deliberately still return a tombstoned row**, mirroring
  `EventDao.getEventById`. The sync path needs "there is a row this device has deleted" and "there
  is no such row" to be opposite answers; a user's question is filtered at the repository boundary.
- **The hard-delete methods on both Firestore data sources were removed**, not left beside the
  tombstone writers. Neither had a caller left, and a `.delete()` sitting next to a `tombstone()`
  is a trap — unlike `FirestoreEventDataSource.deleteEvent`, which keeps exactly one legitimate
  caller (an event turned private has to leave Firestore with no trace).

The 90-day server sweep now covers all four collections. `budgets` and `change_requests` still
delete by other means, and the two halves must be added together: a collection in the sweep list
with no client writing tombstones sweeps nothing, and a client writing them into an unlisted
collection keeps them for ever.

### CQ-20 · **DONE** · A change request said "Sent" whether or not it left the phone

`ChangeRequestRepositoryImpl.publish` catches everything and returns, leaving
`syncedToFirestore = false` for the outbox to retry, and `RequestChangeViewModel` set `Sent`
either way. The flag reached the domain model and **no** screen read it, so there was no way to
tell a request the co-parent has from one sitting in Room.

The request card's status chip now says **Queued**, with the clock icon `MessagesList` already
uses for a message that has not left, so the two surfaces say "not delivered" the same way. The
rule is `!syncedToFirestore` alone, and it is unambiguous: a request mirrored down from the
co-parent is always written back marked synced, so only this device's own writes can be unsynced.
That also covers a *reply* made offline — accepting an incoming request while disconnected shows
Queued rather than "Accepted", which is the honest report of what the co-parent knows.

The state that lied is renamed `Saved`: the screen pops on it whether or not the write landed, so
the honest report of what happened is the chip, not the state's name.

---

## 6. [UX] Design and usability

The theme layer is genuinely good: contrast documented pair by pair, `ParentColors` solving the
fill-versus-text problem properly, Settings and the month grid exemplary. The gap is between that
layer and the screens.

### UX-8 · **PARTLY DONE** · P3 · S · Two surfaces colour a chip from two different sources

**Where:** 👁 the remaining half is an owner's answer, not code.

The loud half is fixed: "whose day is it" is its own line at `titleMedium` in that parent's colour
through `ParentColors.text`, rather than a 12sp grey suffix on the date.

**Left open:** `CalendarBanners` colours from `entry.dayParent ?: event.parentOwner` while the event
chip colours from `event.parentOwner`, so one visual channel carries two meanings on adjacent
cards. That is a question about what a chip's colour *means* — the event's owner, or whose day it
falls on — and it wants an answer before either call site changes.

### UX-9 · P2 · M · Five different empty-state anatomies

**Where:** ☁️ cloud.

`AnimatedEmptyState` in five places, plus bespoke variants in Contacts, ChildInfo, Pets, Friends and
Home — Home's being the `Card { Text }` pattern the August refresh explicitly outlawed. The previous
design review asked for consolidation to one; the count went from two to five.

**Contacts matters most**: an emergency surface, first on Home, and empty it offers two grey
sentences and no action. Separately, `AnimatedEmptyState` takes no `modifier` and hardcodes
`fillMaxSize().padding(32)`, so `Scaffold` padding does not apply and content renders under the top
bar in `ConversationsScreen` and `BudgetScreen`; it also does not scroll, so it clips at large font
scales. Audit §9.12.

### UX-12 · P2 · S · Clerical English success messages

**Where:** ☁️ cloud. Blocked behind **CQ-14** for the ViewModel-side strings.

"Event created successfully", "Event rescheduled" and friends are still English literals — and
`CalendarScreen` **branches on the literal** `"Event rescheduled"`, so localising that string
silently removes the undo snackbar. Fix the branch first, then the strings.

### UX-13 · P3 · M · Light theme is unverifiable rather than incomplete

**Where:** 👁 cloud writes the previews and the theme fix; only a device shows the flash.

`LightColorScheme` is complete and correct and the setting works — but there are six
`LightDarkPreviews` across 148 UI files, two of them on dead components, and **none on any of the
six main screens**. There is no `values-night/`, and `themes.xml` uses an AppCompat *Light* parent
regardless of theme, so a cold start in dark mode flashes a white window before Compose draws.
Audit §9.15.

### UX-14 · P3 · S · Four different brand purples

**Where:** ☁️ cloud; the launcher icon wants a glance on a device.

`brand_primary` `#6750A4` (system splash), `BrandPrimary` `#4F46E5` (Compose), launcher background
`#6200EE`, and a splash gradient between the first two. Icon, system splash, Compose splash and app
do not agree. Audit §9.16.

### UX-15 · P3 · S · `ParentColors` is adopted at roughly a quarter

**Where:** ☁️ cloud.

Thirteen `ParentColors.*` calls against forty-four direct `MomPink`/`DadBlue` uses outside `theme/`.
Most are legitimate fills — but the rule exists so the decision lives in one place, and the place it
broke (parent hues as 8sp text on Custody Setup) is exactly where it was bypassed. Audit §9.17.
**Now larger than it was**: M-4 made the parent colour a *chosen* value rather than a slot-derived
one, so a direct `MomPink` reference is no longer merely a style violation — it draws the wrong
person's colour for anyone who picked purple or orange.

### UX-16 · P3 · S · Drag an event to reschedule it

**Where:** ☁️ cloud writes it; 👁 nobody can tell whether a drag feels right without a thumb.

MVP 3's "time setting by dragging corners": drag the whole event by 15-minute steps, drag a corner
by single minutes. The smallest item in MVP 3 and the one a user touches daily. Day and week views
are `HorizontalPager` with fling physics, so the gesture has to be nested inside a pager that
already claims horizontal drags — which is the whole difficulty.

### UX-17 · **DONE** · A proposed split ratio could not be withdrawn, and the proposer was told nothing

`SplitRatioTransition.withdraw` existed and was unit-tested from the day the feature landed, and
**nothing called it** — so a parent who proposed 70/30 by mistake could only wait for the co-parent
to answer it. `ExpenseViewModel.pendingRatioProposal` even documented the missing half: "the
proposer sees theirs as a waiting state instead", which was not true of any state that existed.

Now it does. `FamilySettingsRepository.withdrawProposal()`, a `myPendingRatioProposal` mirror of
the existing flow, and a quieter waiting banner with one action.

Three decisions worth keeping. It lives on **Expenses, next to the co-parent's banner**, rather
than in Settings as this item first said — both halves of one conversation belong where the money
is. It is **not dismissible**: "Later" is meaningful for a question somebody else asked, but this
is the parent's own open question and the way out of it is an answer or a withdrawal. And it sends
**no push**: the other three answers announce a decision, a withdrawal decides nothing, and the
co-parent's banner is derived from the document — a type for it would cost the four places SEC-3
requires to agree, to announce that something stopped existing.

### UX-18 · P2 · S · A ratio agreed before pairing reaches the pair silently

**Where:** ☁️ cloud — four places per CLAUDE.md item 15, plus five locales.

`FamilySettingsRepository.publishCachedRatioIfMissing` writes `family_settings/{pairId}` with no
`notifyPartner`, where `submitRatio`'s propose branch sends `PushPayload.SPLIT_RATIO_PROPOSED`.
Deliberate as far as it goes — this is the *first* agreement, so there is no proposal to answer —
but the effect is that a parent who set 70/30 in the wizard has it become the pair's agreement of
record, priced onto every expense from that moment, and the co-parent learns of it only by opening
Settings. The honest fix is a push type of its own — an agreement, not a proposal: "the split is now
X/Y, set before you paired". Do **not** fix it by routing the publish through `propose`: an
unanswered proposal would leave the pair splitting evenly, which is the exact bug
`publishCachedRatioIfMissing` was written to end.

---

## 7. [MON] Monetisation and product

**There is no billing layer at all** — no Play Billing dependency, no purchase code, no entitlement
model, no paywall. Everything below assumes that gets built; **MON-1** is the decision that shapes
it, and it should be made before the code.

### MON-1 · P0 · decision · Pricing, and who pays

**Where:** 💻 yours — it is a decision. Everything after it is cloud work.

The audit's recommendation (§10.4), for a Czech-first launch:

- **99–149 CZK/month**, or **990–1,490 CZK/year.** 149 is the top of the "without thinking about
  it" band; 1,200 CZK/year is about 2.4% of one average monthly wage.
- **One subscription per family, the second parent free.** This is what the winning European
  products do (CoParently.de, 2houses, ParentDocket) and the opposite of the American per-parent
  model — which correlates with OurFamilyWizard's 1.4★ on Trustpilot against 4.6★ in the stores,
  the signature of court-mandated use plus per-seat billing. There is a product reason as well as a
  market one: in a conflicted pair, **one** person will pay. Charging both loses both.
- **A free tier is not optional.** The product does nothing until *both* parents install it, so a
  paywall at the door kills the network effect that makes it work. Free should cover calendar and
  custody **completely**; charge for documentation and export.

**A wrinkle M-4 introduced:** a subscription is per *family*, and a person can now have two. Decide
whether an entitlement follows the payer across their families or is bought per relationship —
`familyId` makes either expressible, and getting it wrong after launch is a refund queue.

- [ ] Decide the price, the unit (family, not seat), and what free contains.
- [ ] Then build: Play Billing, an entitlement model, a paywall, restore-purchases, and the
      server-side check that a second parent inherits the family's entitlement. *(→ MON-11)*

### MON-2 · P0 · S · Verify the market facts before acting on any of this

**Where:** ☁️ mostly cloud — these are public pages, and a session can fetch them. §7 is yours.

Direct page fetching was blocked in the audit environment, so competitor prices, ratings and the
Czech statistics come from search-result summaries. Good enough to plan with, **not** good enough to
publish. In order of how much each answer moves the plan (audit §10.7):

1. **app2us "Rodina": is there an Android build, and what does it cost in CZK?** This single answer
   changes the Czech strategy more than anything else found.
2. Custody X Change's price (sources disagreed: $72 vs $144/year for Bronze).
3. Fayr Premium's price; AppClose and 2houses Play ratings.
4. The registered family-mediator count, against the justice.cz register.
5. Czech mobile ARPU by country (only a global Android figure was available).
6. Current single-parent household numbers — the figure found (~175,700) is from 2015.
7. Czech Facebook groups: closed groups are not indexed and need manual search. *(yours)*

### MON-3 · P1 · M · Export to PDF/CSV — the first paid feature

**Where:** ☁️ cloud. Blocked on **MON-4**, and that order is not negotiable.

Nothing in the app produces CSV or PDF. MVP 3 listed exports at **Low**; for a paid tier that is
backwards. Willingness to pay concentrates on **documentation you can hand to a lawyer or a court**:
an immutable log of who changed what and when, handover punctuality, an expense ledger with
receipts.

CoPlanly already *records* all three — the activity feed, `ChangeRequest`, `HandoverCalculator`,
expenses with per-currency balances and receipt photos. The data exists. What is missing is the one
step that turns a nice app into something a parent pays for in the month they need it. Audit §7.2.

### MON-4 · P1 · M · Decide what a court-facing record guarantees — **prerequisite for MON-3**

**Where:** ☁️ cloud writes it; the guarantee itself is an owner's decision.

An export that says "this is what happened" is only as good as the record behind it. Today `events`
are freely editable by the creator with no history, conversations can be re-pointed, and — until
SEC-4 — the custody schedule was ordered by a naive local date-time.

Before selling documentation, decide: which records are append-only, what an edit does to history,
and whose clock orders writes. This is not a nice-to-have once anything is exported for legal use —
it is what makes the export worth paying for. Audit §7.5.

### MON-5 · P1 · M · Digitise the official Rodičovský plán — the cheapest local moat

**Where:** ☁️ cloud.

The Ministry of Justice publishes an official parenting-plan template
(`vyzivne.justice.cz/rodicovsky-plan`). In practice each parent fills it in separately and a mediator
or OSPOD compares the two to surface agreement and disagreement.

Digitising it — two parents, separate answers, a diff, an export — is a feature no global competitor
has, is hard for a non-Czech team to copy, fits the post-2026 legal emphasis on agreement, and hands
the mediator channel a concrete reason to recommend the app. Cheaper than the school import and
lands in the same place. Audit §10.6.

### MON-6b · P2 · L · Half-day custody, so contact afternoons can be described

**Where:** ☁️ cloud.

`CustodyModel` assigns each day of the cycle to exactly one parent (`momDayIndices`), so an
arrangement of the form "every second weekend **plus Wednesday afternoon**" — which is most Czech
contact orders, not an edge case — can only be entered by rounding the afternoon up to a whole day
or dropping it. MON-6's preset drops it and says so; `CUSTOM` cannot express it either.

Not a small change: it touches the pattern representation, the Room entity, the Firestore document,
`getCustodyFor`, `complemented`, `isEquivalentTo`, the custom-pattern editor and the day-cell fills.
Worth doing before claiming the app describes a Czech family's real schedule; worth costing properly
first.

### MON-8 · P2 · L · Bakaláři / EduPage school import

**Where:** ☁️ cloud for the parser and the mapping — but 💻 you have to supply one real export
file, or it is written blind.

MVP 3 listed it as **XL, Low**. It is the highest strategic item in the roadmap and mispriced. Every
Czech parent's school schedule lives in one of those two systems; an import that fills the calendar
on day one solves cold-start, is a local moat no US competitor will build, and is the most credible
reason to choose CoPlanly over a generic shared calendar. Document understanding makes the XL
estimate smaller than when the line was written. Audit §6.4, §7.3.

**And decide where an import lands**, which M-4 sharpened: Google Calendar imports became private
precisely to dissolve that question, but a *school* import is the opposite — it is about the child,
so it belongs to a family and must be shared. With two families on one account, "the selected one at
import time" is a footgun the first time somebody imports while looking at the wrong family.

### MON-9 · P2 · ongoing · Distribution: the channel is professional, not search

**Where:** 💻 yours — phone calls and meetings. A session can draft the material.

This audience does not search for the category — it is handed to them at a specific moment, by a
professional, during the worst month of their year. Audit §10.5.

- **Mediators.** A Czech court can order a first meeting with a registered mediator, up to three
  hours (§ 100(3) o.s.ř.) — a guaranteed moment with **both parents present at once**, the hardest
  thing to arrange in this market. One source puts registered *family* mediators at ~25, about half
  active; if that holds, the entire channel is coverable personally in a week.
- **Courts running Cochem practice** (Nový Jičín since 2016, Most since 2017).
- **OSPOD** offices at municipalities with extended competence.
- **NGOs and portals**: stridavka.cz (which already publishes a co-parenting tools roundup — a
  directly reachable placement), zustavamerodici.cz, APERIO, sancedetem.cz, Unie otců, Liga
  otevřených mužů.
- **The OFW playbook, localised**: free professional accounts with unlimited clients, plus promo
  codes to hand to families. Revenue-sharing with courts is neither available nor legally plausible
  in Czechia; free professional accounts are.

### MON-10 · **DONE** · Re-baseline the roadmap

This document is it. §2 is the re-baseline, and `MVP_phases.md` and `BACKLOG.md` were merged here so
there is one place to be behind rather than two.

### MON-11 · P2 · L · Payments (MVP 3)

**Where:** ☁️ cloud for the code; 💻 the Play merchant and tax setup is yours. Gated on **MON-1**.

Two different things travel under this word in the MVP plan, and they should not be built together:

- **The entitlement layer** — Play Billing, a paywall, restore-purchases, and the server-side check
  that the second parent inherits the family's entitlement. This is what MON-1 decides the shape of.
- **Parent-to-parent reimbursement** — actually moving money between two parents. **Onward closed on
  8 October 2024** built entirely on this. The balance is already computed per currency; the honest
  first version is an export and a payment link, not a payment rail.

### MON-12 · P3 · M · Intelligent suggestions (MVP 3)

**Where:** ☁️ cloud, and only behind **SEC-1**'s proxy.

MVP 3's "suggestions based on past schedules". Two roads, and only one needs a model at all:
patterns in the existing custody and event data are ordinary computation on-device, while anything
generative goes through the proxy with no key in the client.

If a model does return, the ranking from MON-7 stands: **tone check before sending** is what
competitors charge for everywhere (OFW's ToneMeter, TalkingParents' Sentiment Scanner,
CoParently.de's tone detector at €4.99). Two hard constraints, both non-negotiable: it must **never
block** sending, and the analysis must **never be stored** — a saved "your message was aggressive"
verdict is discoverable material in a custody dispute, which makes it a liability to the user rather
than a feature. Anything resembling emotion inference deserves a legal read under the EU AI Act
before launch.

### MON-13 · **PARTLY DONE** · P2 · M · Holidays exist for Czechia only

**Where:** ☁️ the setting and the registry are done; ⚙️/💻 the remaining tables need a source this
environment cannot reach.

MVP 1 asked for "holidays and vacations by country" and shipped one country. There was **no country
setting anywhere in the app** — no field, no picker, not even a constant — so `CalendarScreen`
called `CzechHolidays` directly and a German, Russian or Ukrainian family got Czech public holidays
on their grid, in an app that ships in five languages.

**Done: the country is now a stored, chosen fact.** `User.countryCode` (Room schema 33,
`NOT NULL DEFAULT 'CZ'`, so every existing account becomes Czechia and nobody's calendar changes),
a picker on the wizard's profile step beside the parent colour, a Settings row, and
`HolidayProvider` + `HolidayCountry` where the hardcoded call used to be. A country is stored
rather than "which holidays to show" because other features will want it — currency defaults,
which legal text applies, whether a school import is even available (MON-8).

**Deliberately per parent, not per family.** Two separated parents can live in two countries, and a
public holiday is a fact about where *you* are. This reverses what an earlier draft of this
document said, and the reversal has a cost worth stating: the school-vacation strips, which are
genuinely about the child's school, follow the viewer too. A per-family school calendar is the
honest fix and is part of what is left.

**Left: five of the six tables — and leaving them is an owner decision, not a deferral** (Aug
2026). Offered the choice between authoring them from knowledge with a "needs a native check"
marker and waiting for verified data, the owner chose to wait. So the five stay listed with no
provider and the picker keeps saying so. Do not fill them in from memory on the way past.

`HolidayCountry` lists Slovakia, Germany, Austria, Ukraine and Russia with no provider, and the
picker says so on the row — a country with no table draws **no** holidays, which is honest, rather than another country's, which was the bug. They are unimplemented
for a stated reason: a holiday table is a set of user-visible facts and a wrong date is worse than
no date. This environment's egress policy blocks every reference site, so they cannot be verified
here, and one search while writing this already turned up a change memory would have got wrong —
Slovakia's 2024–2026 consolidation packages moved several days off the non-working list while
leaving their formal names in place. Each country wants a check against a source before its table
lands. Germany's and Austria's school calendars are set per state and may never be computable at
all, which is the same reason Czechia's district-dependent spring break is excluded.

---

## 8. [FAM] More than one child, more than one pet

Found in August 2026 by asking a question nobody had asked: what happens when a pair is raising two
children, or two children and a dog. Three of the five items are done — the wizard, the "who is this
about" reference, and events knowing who they are about. Two remain.

### FAM-4 · P2 · L · Custody per child

**Where:** ☁️ cloud. **SEC-4** was its prerequisite and is done.

One schedule per pair stays the default; a per-child schedule is an override. It drags Home's
handover hero (singular today), the calendar banners and `getCustody` with it. The reason it waited
was SEC-4: `lastModifiedAt` was a naive local date-time that already decided which phone's schedule
survived, and multiplying the documents would have multiplied that defect before fixing it. That is
now fixed, so the blocker is gone.

Genuinely rarer than FAM-2 and FAM-3 — a teenager who negotiated their own arrangement, an infant
who stays with one parent — which is why it is last rather than never.

### FAM-5 · P2 · S · The event chip does not say who it is about

**Where:** 👁 the treatment is an owner's call on a real device; the code is small once decided.

Only reachable in an unfiltered day or week view, and only for a family with two or more members.
The constraints are the interesting part: `softWrap = false` plus ellipsis means a prefix costs
title, and pink/blue/teal/grey are taken by the parent slots, a calendar friend and the weekend —
and M-4 spent two more hues (purple, orange) on chosen parent colours, so the colour channel is now
comprehensively unavailable. An initial-letter avatar at chip height is the obvious candidate; so is
doing nothing and leaving the filter to answer it.

---

## 9. [M] More than one co-parent

`docs/DESIGN-multi-family.md` is the plan of record. M-1 … M-4 shipped in PR #76: `families/{id}` as
a first-class document, `familyId` on the six shared collections, the slot and `caresFor` moved onto
the family, and the switcher — plus the isolation the whole thing exists for, a chosen parent colour
and a second co-parent you can actually invite.

**None of it is live until REL-3's ops sequence runs.** The rules are deployed last, on purpose.

### M-5 · P2 · M · Cleanup

**Where:** ☁️ cloud — but only after REL-3's ops sequence has run and settled.

Delete `partnerId`, `User.role`, `Event.sharedWith`, `isPartnerOf`. Each is written today so that a
co-parent on an older build keeps working; each is a second source of truth until it goes. Do not
start this while any device might still be on a pre-#76 build.

### M-6 · **DONE** · A calendar friend was granted per person, not per family

The defect PR #76 existed to close, in the one collection it did not reach.
`calendar_friends/{friendUid}` stored `familyParents: [uidA, uidB]`, and `isCalendarFriendOf(owner)`
asked only whether `owner` was in that array — while the `events` read rule reached it with the
event's **creator**. So a grandmother admitted by Alice-and-Bob read every event **Alice** created,
including the ones belonging to Alice's family with Carol, whom she has never met. Same shape as the
expenses leak: the rule asked "am I a friend of this author", never "is this record mine to see".

The grant now carries the `familyId` it was issued for, and the rule requires the record's own
`familyId` to match **and** its creator to be one of that family's two parents. Four things worth
keeping:

- **The second check is not redundant.** An event's `familyId` is client-written and the create
  rule does not pin it (M-2), so without `ownerUid in familyParents` any account could stamp a
  foreign family's id onto its own event and have it appear in that family's friend view.
- **Narrowing a widening disjunct is safe to deploy early.** The friend branch only ever *adds*
  access, so the condition can deny a friend but can never deny the app its own writes — unlike
  pinning `familyId` on a create rule. An unstamped record is simply invisible to a friend until
  `backfillRecordFamilyIds` has run, which now stamps the grants too (one pass, no fifth ops step).
- **The client query shape changed, and the old one is now rejected outright** rather than
  serving a subset — measured in the emulator, not reasoned. `whereIn("createdByFirebaseUid",
  [a, b])` cannot satisfy a rule keyed on the record's own family; `whereEqualTo("familyId", …)`
  plus the creator clause can. Nothing in the app issues either yet — the friend's calendar view
  is still unbuilt — so this cost nothing today and is pinned by a test for whoever builds it.
- **The family is chosen when the code is generated, not when it is redeemed.** Those can be days
  apart, and a parent with two families may be looking at the other one by then. The callable
  never trusts the id it is sent: it checks it against the inviter's live co-parents and falls
  back to the family they are showing, which is also what an invitation from an older build gets.

### M-7 · P3 · S · Which family does an imported calendar belong to

**Where:** ☁️ cloud, once decided.

M-4 dissolved the urgent half by making Google Calendar imports `isPrivate` — a personal calendar's
contents are exactly what a co-parenting app has no business forwarding, and a private event belongs
to nobody but its owner. What is left is only the question of whether a *shared* import should ever
exist. If it should, the honest answer is a per-calendar mapping, which is a screen of its own; the
cheap answer, "the selected family at import time", is a footgun the first time somebody imports
while looking at the wrong family. `CalendarSyncRepository` says so at the call site, and that line
is where the answer goes. Related: **MON-8**, where a school import is the opposite case — it *is*
about the child and must be shared.

### M-8 · P2 · M · What M-4 deliberately left

**Where:** ☁️ cloud.

- **Badges do not count across families.** Something happening in the family you are not looking at
  is quiet until you switch. The counts are per-selected-family because every query resolves through
  the projected `partnerId`; counting across families means querying across them.
- **Pushes carry no `familyId`.** Adding the key alone would be a field nothing reads; the value is
  deep-linking into the right family, which means the tap has to switch the selection first. The
  `notification_queue` rule has no `hasOnly`, so a new key is accepted — but its own comment
  requires a length bound, since "a field the rule does not know about is a field with no bound".
- **A switcher chip in the top bar**, in addition to the Settings row. Two families is when the
  Settings-only route starts costing three taps a day.

---

## 10. The order to actually do it in

Not a wish-list ordering — a dependency ordering. Each block assumes the one above it.

**This week, and none of it is code**

1. **REL-3's ops sequence** — deploy functions, run the two backfills, deploy the rules. Everything
   from both audits *and* all of PR #76 is inert until this runs, and one of the fixes closes a live
   full-calendar disclosure.
2. **REL-3's storage deploy** — one command; without it every pet and medical photo upload is
   refused on a live device today.
3. **REL-1's console half** — a local build fails until it is done.
4. **MON-2 §1** — find out whether app2us "Rodina" has an Android build. One afternoon; it moves the
   plan more than any other single fact, and a session can do the fetching.

**Then the two CI jobs** — cloud work, and the pair everything later leans on

5. ~~**M-6** — the calendar-friend grant is still per person.~~ **Done.**
6. ~~**CQ-19** — a deleted child or pet never reaches the other phone.~~ **Done**, and it added the
   third migration `app/schemas/` cannot describe.
7. **CQ-12** then **CQ-1** — make detekt gate again, then restore the schemas and add the emulator
   job. After them every later schema change is provable, which SEC-2 needs and CQ-19 wanted.

**Before any launch**

8. **REL-2, REL-4, REL-5, REL-6, REL-7** — keystore, legal, consent, Play Console, and the one
   device test CI cannot run.
9. **SEC-1** — the OAuth callable, and the Storage rules once their verification story is settled.

**Then the product bets, in descending confidence**

10. **MON-4 then MON-3** — settle what the record guarantees, then sell the export. Not negotiable:
    an export of a record nobody can vouch for is worth nothing to a lawyer.
11. **MON-5** — the Rodičovský plán. The cheapest local moat and the reason a mediator recommends
    you.
12. **MON-1** then **MON-11** — decide the price before writing the entitlement layer, and decide
    what a subscription means now that a person can have two families.
13. **MON-8** — the school import.

**Structural, whenever it fits**

14. **CQ-5**, and **CQ-6 + CQ-8** together. All three grow worse with tenure, so they land on your
    longest-standing users first.
15. **CQ-13**, **CQ-14** → **UX-12**, **UX-9**, **M-5**.

**One thread runs through this document.** The security holes, the release-only Gson corruption, the
plaintext refresh token, the two-year recurrence bug, thirty unit tests failing against a
constructor that changed months ago — none of it was carelessness. They are the failure modes of a
codebase with careful reasoning and, until recently, no automation to check it. CI is not low on the
list because it was urgent; it was built first because everything above it is a symptom. The two
remaining CI jobs (**CQ-1**, **CQ-12**) are the same argument, one layer down.

---

## 11. Done — so it is not re-litigated

Kept rather than deleted, because the reasoning is what stops each one coming back. Full arguments
in `docs/AUDIT-2026-08.md` under the § numbers cited.

### Security

- **SEC-3 · Notification text is composed on the client.** A push could claim to be anything: the
  sending device wrote `title` and `body`, and the other phone rendered them verbatim with the app's
  own icon, on a lock screen, from someone the reader may be trying to keep at a distance. Composed
  on the **receiving** device now — a payload carries a `type` and the few names it needs
  (`PushPayload`), the app writes the sentence from its own string resources, and **drops a type it
  has no wording for**. That fallback *is* the forgery, so never reintroduce it. Two rules hold it:
  a client payload carrying `title` or `body` **at all** is refused (presence, not size — a length
  bound lets an empty one through), and `data.type` must be in an allow-list that excludes
  `pairing_accepted`, `pairing_removed` and `chat_message`, which only Cloud Functions can produce.
  Adding a type means four places agreeing, and one missing means a push that silently never
  appears.
- **SEC-4 · The custody schedule was ordered by a naive local date-time.** Two phones 2–3 zones
  apart could have the wrong side win **and overwrite**. Now epoch millis (schema 29) and a `>`.
  The interesting half is the wire form — read `domain/custody/CustodyTimestamp.kt` before touching
  it: the field keeps its name *and* its ISO-string type and only the zone changed, because changing
  the type leaves an older build reading a blank (and a blank compares equal to their last
  dismissal, so every future change goes silently un-announced), while adding a numeric field beside
  it puts a new key in `affectedKeys()` and `hasOnly` denies the first such write outright.
- **PR #68/#69** closed: the `calendar_friends` self-issued grant that disclosed a whole family's
  calendar; the `users.partnerId` self-reference revocation bypass; invitations accepting an
  unverified email; `change_requests` forgery; `events` update rewriting the audience; membership
  reads against absent fields; R8 destroying a child's medical profile in release;
  `EncryptedPreferences` falling back to plaintext permanently; personal data in diagnostics;
  telemetry flags nothing read; the Gemini key bound as a bare `String`; unfiltered collection
  queries. Plus **account deletion** (server-side teardown and local wipe — Play's requirement and
  GDPR Art. 17) and **invitation email that actually sends**.

### Correctness

- **CQ-2** — the untested migrations that shipped in `versionCode 2`. Folded into **CQ-1**, which is
  the only place they can be tested from.
- **CQ-3 · Deletions never reached the other parent.** Parent A deleted an event and parent B kept
  it forever; a failed remote delete meant the next sync **restored it locally**. Fixed with
  tombstones (`data/sync/Tombstone.kt`): `update()` with `deletedAtMillis`/`deletedBy` — never
  `set()`, which would replace the fields the read rules are keyed on — plus a Room outbox that
  retries and a 90-day server sweep. Three things not to undo: **do not reconcile by absence** (it
  takes the whole calendar the first time an audience narrows or a snapshot comes back partial),
  **do not decide a deletion by timestamp** (`updatedAt` carries SEC-4's defect; a tombstone wins by
  rule, deliberately), and **do not shorten the sweep** — it is the deadline for the other phone to
  come back and collect the deletion.
- **CQ-4 · Daily recurring events vanished after ~2 years.** `count++` ran per loop iteration rather
  than per occurrence emitted, and the walk always started at the event's start, so a daily event
  stopped 730 days in regardless of the window queried — an empty month three years out, with the
  master row intact. Occurrences are indexed now, so a distant range costs the same as a near one;
  the month-end drift (the 31st becoming the 28th permanently after one February) went with it.
- **CQ-7 · The Google Calendar import silently truncated at 50 events.** `maxResults = 50` and no
  `pageToken` — and "Found 50 events" is also what a complete import reports, so the user believed
  it had finished. Every page is followed now, within a stated window, with a *different* message
  when the cap is reached.
- **CQ-9 · `ChildInfoViewModel` could overwrite the wrong child's record.** `init` collected the
  whole list for the ViewModel's lifetime and set `_currentChildInfo = list.first()` on every
  emission, so a background sync tick while editing child B reset the state to child A — and the
  save then landed on **child A's real row**, id and `createdAt` included. The editor observes one
  child by id now. Keep the split: a list screen reads the list, an editor observes its one record.
- **CQ-10 · `syncWithFirestore()` meant two incompatible things.** A one-shot in three repositories
  and an endless `callbackFlow` in three others — adding the wrong one to `performFullSync()` by
  analogy would have made it never return, WorkManager would have killed it at ten minutes, and sync
  would have stopped entirely with no exception and no log. Renamed by shape: `pullOnce()` versus
  `observeRemote()`, seven repositories. The danger was never in any implementation; it was that the
  two had one word between them.

### Design

- **UX-1 · A paired parent was told they had no co-parent, on every cold start.** `Loading` is its
  own state now and the page asserts nothing while it holds — with a settle window, because a page
  that waits for ever is worse than one that offers something to do.
- **UX-2 · No main screen had a loading state.** Every list started at `emptyList()` and branched on
  `isEmpty()`, so "nothing yet" and "nothing at all" were the same value: Home asserted "$0.00" and
  "All settled" before it knew, and Contacts told a parent opening the emergency surface in a hurry
  that there were none, a frame before showing them. One type rather than six flags: `Loadable<T>`
  plus `stateInLoadable`. **The calendar is deliberately excluded** — its grid is structurally
  present either way, and `Loading` on every re-anchor would flash.
- **UX-3 · Budgets could not be edited or deleted** — a typo in a limit was permanent — plus a
  keyless `remember` inside `items{}` that showed another budget's figure on a recycled row.
- **UX-4 · There was no way to jump to a date.** The dialog was built and never opened.
- **UX-5 · "Today" did not survive midnight.** `remember { LocalDate.now() }` with no key. Reading
  it inline was no better: correct whenever it ran, but nothing made it run. `rememberToday()` makes
  midnight a recomposition trigger.
- **UX-6 · Adaptive sizing and font scale were switched off at the entry point.** The window size
  class never reached the theme, so `adaptiveDimensions()` — the only code reading `fontScale` and
  `isTouchExplorationEnabled` — was dead.
- **UX-7 · Touch targets.** The calendar header's month title *is* the Month/Week/Day switcher and
  was a bare ~28dp `clickable`, which TalkBack did not announce as a control either.
- **UX-10 · Budget status was carried by colour alone.** Now a word and a shape first, colour third
  — a circle and a triangle rather than two tints of one shape, because the point is to survive the
  colour being discarded. Two things fell out that were not in the item: the two screens decided the
  same three states in a *third* palette, and the percentage was painted in the status colour, which
  put amber at ~1.7:1 as text.
- **UX-11 · The Google Calendar row had a switch *and* a chevron.** The switch moved into the
  expanded block, not the chevron out of the row: expanding is what that row *is*.
- **MON-6 · The Czech custody preset.** `EVERY_OTHER_WEEKEND` (výhradní péče se stykem), listed
  second because the enum's order is the picker's order. Its switch asks "who does the child live
  with" rather than "who starts first" — this pattern does not alternate blocks, so a parent asked
  who starts would answer about the first weekend and set it inverted. What it exposed is
  **MON-6b**.
- **MON-7 · The AI subsystem is deleted.** 23 files, ~3,200 lines, reachable from no navigation
  graph, while the Gemini key shipped in every APK. `generativeai`, `retrofit`, `converter-gson`,
  `okhttp` and `logging-interceptor` went with it. It is in git history. See **MON-12** for the
  terms on which it returns. Boundaries that outlive the deletion: receipt OCR stays on-device; AI
  never acts on the co-parent's behalf; AI never adjudicates who is right or who is late more often;
  chat content reaches a model only on an explicit user action.

### Family shape

- **FAM-1 · The wizard could only ever create one child and one pet** — and its relatives step wrote
  emergency contacts onto whichever child was saved first, so with two children they were silently
  mis-filed. Both steps are repeatable lists now, and **nobody is asked how many children they
  have**: the steps collect names and the count falls out of them. A family with one child sees the
  form they saw before.
- **FAM-2 · One reference for "who this is about".** `Expense.childId`/`Budget.childId` — which
  nothing wrote and nothing read — became `forMembers`, a list of `domain/family/FamilyMemberRef`
  (`"child:abc"` / `"pet:xyz"` on the wire, never a Gson serialisation of the type). Covering pets is
  what makes a vet's bill expressible at all. Two rules with an obvious wrong answer one keystroke
  away: **naming nobody is not naming everybody**, and **an unrecognised reference survives a round
  trip** as `Unknown`.
- **FAM-3 · Events know who they are about.** `Event.forMembers`, empty meaning "the whole family",
  and a filter strip that appears at two members and not at one. `firestore.rules` needed no change
  — the `events` block validates with `keys().hasAll([...])`, presence-based. What it left is
  **FAM-5**.
- **M-1 … M-4 · A parent can co-parent with more than one other adult.** `families/{id}` keyed by
  `FamilyKey.of(a, b)`; `familyId` on the six shared collections, stamped at create and never
  re-derived, with null meaning "mine alone"; the slot and `caresFor` moved onto the family (`slots`
  admin-only, `caresFor` member-writable — the asymmetry is the whole security surface); and the
  isolation itself. The lesson worth carrying: **a softening fallback for unstamped documents
  re-opened the leak**, because Firestore validates a query by its *structure*, not by running the
  rule over results — while any branch mentioned `isPartnerOf(createdByFirebaseUid)`, the old
  `whereIn` query was served. Measured in the emulator, not reasoned. Which is why
  `backfillRecordFamilyIds` must finish before the rules deploy.
