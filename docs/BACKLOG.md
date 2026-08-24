# CoPlanly — release backlog

What is left before CoPlanly can be published, and what was deliberately deferred.

Everything here is **outside what a cloud session can do**: it needs the Android SDK, a
keystore, the Firebase console, the Play Console, or a decision only the owner can make.
Work that *was* doable in the cloud is done — see `docs/AUDIT-2026-08.md` and the branch
history.

Last updated: 2026-08-24, after the August audit.

---

## 1. Blockers — the app cannot ship until these are done

### 1.1 Publish the legal documents and link them

Drafts are in `docs/legal/`. They are drafts: **have them reviewed by a lawyer before
publishing.** This app processes a child's health data, which is special-category data under
GDPR Art. 9, and no template survives that unread.

- [ ] Fill in every `{{PLACEHOLDER}}` in `docs/legal/PRIVACY-POLICY.md` and
      `docs/legal/TERMS-OF-SERVICE.md` — controller identity, address, contact address.
- [ ] Legal review.
- [ ] Host both at stable URLs. Play requires the privacy-policy URL in the store listing.
- [ ] Publish a **web-accessible account-deletion page** as well. Play requires a deletion
      route that does not depend on having the app installed; the in-app path alone is not
      enough. It can be a simple page explaining the in-app steps plus a contact address for
      people who no longer have the app.
- [ ] Link both from Settings once the URLs exist. Deliberately not wired yet: a Settings row
      pointing at a URL that does not resolve is precisely the "affordance promising something
      that does not exist" the project's own design rule #8 forbids.

### 1.2 Signing configuration and the keystore

- [ ] Generate a release keystore.
- [ ] **Back it up in two places.** Losing it means the app can never be updated again — not
      re-signed, not recovered, not appealed. This is the single most irreversible item in
      this document.
- [ ] Add a `signingConfig` reading the passwords from `~/.gradle/gradle.properties` or the
      environment, never from a tracked file — the same pattern `GEMINI_API_KEY` already uses.
- [ ] Decide whether to enrol in Play App Signing.

### 1.3 Decide the `applicationId` — this expires at the first upload

`applicationId` is `com.coparently.app`; the product is called CoPlanly. After the first Play
upload it is **permanent**. The Firebase project id (`coparently-a39c9`) and the deep-link
scheme (`coplanly://`) already disagree with each other.

- [ ] Decide: keep `com.coparently.app`, or change to `app.coplanly` / `com.coplanly.app`.
- [ ] If changing: register a new app in the Firebase console, download a fresh
      `google-services.json`, update the OAuth client's package name and SHA-1, and re-check
      the deep-link filters.

Cost today: an afternoon. Cost after launch: impossible.

### 1.4 Analytics consent for the EU

`ENABLE_ANALYTICS` / `ENABLE_CRASHLYTICS` are now honoured per build type, but a release build
still collects by default. For an EU launch that needs a consent gate, not a build flag.

- [ ] A first-run consent screen, defaulting to **off**, with the choice persisted and
      reachable again from Settings.
- [ ] Wire it to `setAnalyticsCollectionEnabled` / `setCrashlyticsCollectionEnabled` at
      runtime rather than only at injection time.

### 1.5 Play Console

- [ ] Data Safety declaration — answers derived from the real schema are drafted in
      `docs/legal/DATA-SAFETY.md`. Check them against the code before submitting; a wrong
      declaration is a policy violation, not a typo.
- [ ] Store listing, screenshots, feature graphic. Czech first, then English.
- [ ] Content rating questionnaire.
- [ ] Closed testing track with real co-parent pairs before production.

---

## 2. Needs the local toolchain

### 2.1 Restore the Room schemas — **P0**

`CoPlanlyDatabase` is at `version = 24`; `app/schemas/` stops at `14.json`. The files for
15–24 were never committed, so they cannot be recovered from git — they have to be
regenerated. Until then every migration test above v14 fails with "Cannot find the schema file
in the assets folder", which means **migrations 15→24 have never run against real SQLite**,
and `DatabaseModule` deliberately does not fall back to destructive migration above v4: a
broken migration is a crash on launch for a user with real data, not a wipe.

- [ ] Check out each commit that bumped the version and run `./gradlew kaptDebugKotlin` to
      regenerate that version's schema, or accept the gap and export 24 only, keeping the
      untested migrations documented.
- [ ] Write the missing migration tests for 21→22, 22→23, 23→24, which shipped in
      `versionCode 2` untested.
- [ ] Once green, add the instrumented job to CI — it is deliberately absent from
      `.github/workflows/ci.yml` today because it would be red from its first run.

### 2.2 Regenerate the detekt baseline, then make detekt gate again

CI's first run found **194 weighted detekt issues**, essentially all pre-existing:
`AddEditEventScreen` at 1,246 lines, `AnalyticsManager` with 22 functions, and the
friends/pets/expense screens added since `app/config/detekt/baseline.xml` was last generated.
Nobody had run detekt, so nobody had baselined them.

`.github/workflows/ci.yml` therefore runs detekt with `continue-on-error: true` — it reports,
it does not gate. That is a deliberate, temporary state and it is commented as such in the
workflow.

- [ ] `./gradlew detektBaseline` (needs the Android SDK), commit the regenerated baseline.
- [ ] Delete `continue-on-error: true` from the detekt step, so new violations fail again.
- [ ] Optionally, work the debt down afterwards — the baseline records what was accepted.

### 2.3 Verify this branch actually builds — **done by CI, except the device check**

Written while this section said the opposite: the Kotlin from the audit had never been
compiled, because no Android SDK was available here. CI has since run all of it on a clean
checkout, and it is green.

- [x] `assembleDebug`, `testDebugUnitTest`, `lint`, `detekt` — green on
      `.github/workflows/ci.yml`. Getting there took four rounds and every failure was
      pre-existing on `main`, surfaced for the first time because nothing had ever run the
      build: three compile errors, one lint error, and 30 unit tests whose mocks had gone
      stale against collaborators added since.
- [x] `assembleRelease` — R8 ran for the first time in this project's history and succeeded,
      against the new `proguard-rules.pro`.
- [ ] **Still needs a device.** Save a child's medical profile from a *release* build and
      confirm it reaches the co-parent non-empty. A green R8 run proves the build survives
      shrinking; it does not prove Gson still finds the field names, which is the defect the
      keep rules exist for. Nothing but a real APK answers this.

---

## 3. Needs the Firebase console

- [ ] **Deploy the rules**: `firebase deploy --only firestore:rules`. The security fixes from
      the audit are inert until this runs — including the one that let any account read any
      family's calendar.
- [ ] **Deploy the functions**: `firebase deploy --only functions`. `deleteAccount` and the
      real invitation-email delivery do not exist in production until then.
- [ ] Set the function environment (`functions/.env`):
      - `SENDGRID_API_KEY`, `INVITE_FROM_EMAIL`, `INVITE_FROM_NAME` — until these are set,
        invitation emails record `emailDelivery: 'not_configured'` and are not sent.
      - `BACKFILL_ADMIN_UIDS` — only if the slot backfill is ever needed.
- [ ] Verify the sending domain with the mail provider (SPF/DKIM), or the invitations will be
      delivered to spam, which looks identical to not being sent.
- [ ] **Decide the Firestore region.** An EU region makes the GDPR story much simpler and
      cannot be changed after data exists. Check what `coparently-a39c9` currently uses.
- [ ] Sign a DPA with Google covering Firebase and, if AI ever ships, Gemini.

---

## 4. Deferred engineering, in rough priority order

| # | Item | Why it waits |
| --- | --- | --- |
| 1 | **Deletions do not replicate.** The downstream sync path only inserts; there are no tombstones. Parent A deletes an event, parent B keeps it forever — and a failed remote delete is resurrected by the next sync. | Needs a Room migration, a rules change and a reconciliation pass. Largest item here and the most visible to users. |
| 2 | **Cloud Function proxy.** One piece of infrastructure closes three holes: Storage writes (any signed-in user can delete or replace any receipt or medical photo whose path they know), the OAuth token exchange (the client secret ships in every APK, with no PKCE), and AI calls (the Gemini key likewise). | Substantial, and each half needs its own client change. |
| 3 | **Sync window and pagination.** Every sync downloads the whole event collection, every 15 minutes, on both devices — roughly 4–5k documents after three years. Chat has no limit at either end. | Bill grows with tenure, so it lands on the longest-standing users first. |
| 4 | **Daily recurring events vanish after ~2 years.** `RecurrenceExpander` counts loop iterations rather than emitted occurrences, and always walks from the event's start date. | One-line class of fix, but changing date arithmetic without running `RecurrenceExpanderTest` is exactly the speculative edit that costs a cycle. Do it with the build available. |
| 5 | **Room is not encrypted at rest.** `EncryptionManager` (AES-256-GCM, Keystore) exists, is correct, and is not applied to the database. | SQLCipher plus a migration, or field-level encryption of the medical profile as a smaller first step. |
| 6 | **`syncWithFirestore()` means two incompatible things** — a one-shot in two repositories, an endless listener in three. Adding the wrong one to `performFullSync()` would make it never return, and `SyncWorker` would die at WorkManager's ten-minute ceiling with no error. | Rename to `pullOnce()` / `observeRemote()`. Cheap, but touches five files. |
| 7 | **Notification text is composed on the client**, so a push can claim to be anything. Length is now bounded; composition is not. | Needs the service-layer strings localised first, itself a tracked follow-up. |
| 8 | **No Digital Asset Links, so `CredManMissingDal` is disabled in `app/build.gradle.kts`.** Credential Manager's password sign-in cannot share a credential with a website, and the pairing deep link stays a custom scheme rather than an App Link. | Both need a domain the project does not own; the same domain the legal documents in §1.1 need hosting on. Do all three together. |
| 9 | **`ChildInfoViewModel` can overwrite the wrong child's record.** Documented in `CLAUDE.md`, still open, still untested. | Fixing the subscription strategy is its own change. |

---

## 5. Product decisions, not engineering

- [ ] **Monetisation.** No billing layer exists at all. The audit recommends 99–149 CZK/month
      or 990–1,490 CZK/year, **one subscription per family with the second parent free** —
      in a conflicted pair only one person will pay, and charging both loses both. See
      `docs/AUDIT-2026-08.md` §10.4.
- [ ] **Verify app2us "Rodina"** — whether the Czech incumbent has an Android build and what
      it costs. This single answer changes the Czech strategy more than anything else found.
- [ ] **Custody presets are American.** `2-2-3` and `3-4-4-3` are US family-law vocabulary,
      while *výhradní péče se stykem* — every other weekend plus a midweek afternoon, the most
      common Czech arrangement — has no preset at all.
- [ ] **What the free tier contains.** The product does nothing until both parents install it,
      so a paywall at the door kills the network effect that makes it work.
- [ ] **What happens to the AI code.** 22 files and ~3,100 lines are unreachable from
      navigation while the Gemini key ships in every APK. Ship one feature behind the proxy,
      or delete the subsystem — but stop documenting it as shipped.
