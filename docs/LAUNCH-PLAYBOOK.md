# CoPlanly — launch playbook

**What is left of MVP 3, how the app reaches Google Play, how it is advertised, and how it earns.**

Written 2026-08-26. Companion to `docs/ROADMAP.md`, which stays the plan of record — this document
does not restate it. Where the roadmap says *what* is open, this says *in what order*, *by whom*,
and *what the words on the store page and the invoice should be*.

---

## How to read this

**Three kinds of statement appear below, and they are not equally reliable.**

- **Facts about this codebase** — checked against the code on `main` at `baa2519`. Trust these.
- **Facts about the market** — competitor prices, ratings, Czech statistics. Every one of them comes
  from search-result summaries rather than primary pages, because direct fetching was blocked in
  the audit environment and is still blocked in this one (`date.nager.at` and `en.wikipedia.org`
  both refuse). Good enough to plan with, **not good enough to publish.** `docs/ROADMAP.md` MON-2
  lists them in the order that verifying them changes the plan.
- **Facts about Google Play policy** — from training, not from the console. Play's requirements
  change on their own schedule and have changed twice in the last two years in ways that matter
  here. **Verify each one in the Play Console as you hit it**; they are marked ⚠︎ below.

The market half of this document is a hypothesis to test, not a plan to execute on faith.

---

## 1. MVP 3 — what is actually left

### The short answer: all five items, and none of them should be started yet

`docs/ROADMAP.md` §2 re-baselined MVP 3 as **not started, and repriced**. Nothing has changed that
since. What *has* changed is everything around it: MVP 1 and MVP 2 are complete, the August 2026
security work is done, and the app now cannot be published for reasons that have nothing to do with
features.

**An unpublished app earns nothing from any MVP 3 feature.** §3 of the roadmap — the release
blockers — is the whole critical path, and every one of its items is a decision, an account, a
deploy, or a lawyer. Building exports while the bucket still refuses photo uploads and the rules
have never been deployed is building on a foundation that is not there.

### The five items, repriced

| MVP 3 line | Roadmap id | Original | Repriced | Why the reprice |
| --- | --- | --- | --- | --- |
| Exports to PDF/CSV | **MON-3** | M, Low | **P1 · M** | This is the **first paid feature**. Willingness to pay in this category concentrates on documentation you can hand to a lawyer. Listing it Low was backwards. Blocked on MON-4. |
| Import (Bakaláři / EduPage) | **MON-8** | XL, Low | **P2 · L** | The highest strategic item in the plan. Every Czech parent's school schedule lives in one of those two systems; an import that fills the calendar on day one solves cold-start and is a moat no US competitor will build. |
| Payments | **MON-11** | XL, Low | **P2 · L** | Two different things travel under this word. The entitlement layer is necessary; the parent-to-parent payment rail is what **Onward** was built on, and Onward closed on 8 October 2024. |
| Intelligent suggestions | **MON-12** | M-L, Low | **P3 · M** | Only behind SEC-1's proxy, and only as *one* feature. The Gemini subsystem was deleted with its key in MON-7. |
| Time setting by dragging | **UX-16** | S, Low | **P3 · S** | The smallest item here and the one a user notices daily. |

### The order that is not negotiable

```
REL-3 (deploy)  →  REL-1, REL-2  →  REL-4, REL-5, REL-6, REL-7  →  publish
                                                                      ↓
                                        MON-4  →  MON-3  →  MON-1  →  MON-11
                                                                      ↓
                                                         MON-5, MON-8, MON-12, UX-16
```

Three edges in that graph are hard constraints rather than preferences:

- **MON-4 before MON-3.** An export that says "this is what happened" is worth exactly as much as
  the record behind it. Today `events` are freely editable by their creator with no history. Decide
  which records are append-only and whose clock orders writes *before* selling the export, or the
  first lawyer who reads it closely destroys the feature's reputation and the app's.
- **MON-1 before MON-11.** The price, the unit, and what free contains shape the entitlement code.
  Building billing first means rewriting it.
- **Everything after publishing.** Not because the code cannot be written — most of it is cloud
  work — but because the answers to MON-1 and MON-2 come from real users, and there are none.

### What is *not* in MVP 3 and matters more than three of its items

- **MON-5 — digitise the official Rodičovský plán** (`vyzivne.justice.cz/rodicovsky-plan`). Two
  parents answer separately, the app diffs the answers, and a mediator gets a document. No global
  competitor has this, it is hard for a non-Czech team to copy, it fits the post-2026 legal
  emphasis on agreement, and it hands the professional channel a concrete reason to recommend the
  app. Cheaper than the school import and lands in the same place.
- **MON-6b — half-day custody.** `CustodyModel` assigns each cycle day to exactly one parent, so
  "every second weekend **plus Wednesday afternoon**" — which is most Czech contact orders, not an
  edge case — cannot be entered. The app cannot honestly claim to describe a Czech family's real
  schedule until this exists.
- **CQ-5 — the sync downloads every event every fifteen minutes**, on both phones, forever. It gets
  worse with tenure, so it lands on your longest-standing users first. This is a bill as well as a
  bug.

---

## 2. Publishing to Google Play

⚠︎ **Everything in this section is from training, not from the console.** Play changes these
requirements; verify each as you reach it.

### 2.0 Before the console: the deploys that are already written and inert

None of this is Play, and all of it must be true before a tester installs anything.

**In this order** (`functions/README.md` has the runbook):

1. Set `functions/.env`: `GOOGLE_OAUTH_CLIENT_ID`, `GOOGLE_OAUTH_CLIENT_SECRET`. The client secret
   is no longer in the APK (SEC-1 §2), so **Google Calendar sign-in does not work until these are
   set and the functions deployed.** Also `SENDGRID_API_KEY`, `INVITE_FROM_EMAIL`,
   `INVITE_FROM_NAME`.
2. `firebase deploy --only functions`
3. Invoke `backfillFamilyDocuments` — every live pair gets `members`, `slots`, `caresFor`
4. Invoke `backfillRecordFamilyIds` — every record gets its `familyId`
5. `firebase deploy --only firestore:rules,firestore:indexes`

Both callables are idempotent and report per-reason counts rather than "ok". **Running 5 before 4**
leaves each co-parent's expense and budget history looking empty on the other phone until 4
completes — nothing is lost, since Room is the source of truth, but it is alarming to watch.

**Separately, and it fixes a live bug today:**

6. `firebase deploy --only storage`. The bucket still runs its July 2026 rules, which cover
   `receipts/` and `event_images/` only, so `pet_photos/**` and `medical_photos/**` fall through to
   the catch-all `allow read, write: if false` and **every pet and medical photo upload is refused
   right now.** The client path is sound and was ruled out end to end.

**And one decision that cannot be revisited:** the Firestore region. An EU region makes the whole
GDPR story simpler and **cannot be changed once data exists.** Check what `coparently-a39c9` uses
today before adding a single tester.

### 2.1 The account

- A Google Play developer account: **one-time US$25**. ⚠︎
- **Personal vs organisation matters more than it looks.** An organisation account needs a **D-U-N-S
  number** (free, takes days to weeks to issue) and is exempt from the closed-testing requirement in
  §2.6. A personal account is instant and is not. ⚠︎ If CoPlanly is going to be a business at all,
  start the D-U-N-S application now — the wait is the long pole, and it runs in parallel with
  everything else.
- **EU trader status.** Under the DSA, an app distributed to EU users by a trader requires trader
  status plus a real contact address, published on the listing. This is not optional for a paid app
  in Czechia. ⚠︎

### 2.2 The app identity — REL-1's console half

`applicationId` is `app.coplanly`, changed in code while it still could be. **After the first
upload it can never change**: a different id is a different app, with no upgrade path for anyone
who installed the first. Until the console half is done a *local* build fails, deliberately — the
Google Services plugin reports *"No matching client found for package name 'app.coplanly'"*. CI is
unaffected, since `google-services.json` is gitignored.

1. Firebase console → project `coparently-a39c9` → Add app → Android → `app.coplanly`. **Register it
   alongside** the existing app rather than deleting that one.
2. Download the new `google-services.json`, replace `app/google-services.json`. One file holds both
   clients, so one download covers it.
3. Google Cloud console → Credentials → the Android OAuth client used for Calendar: set the package
   name to `app.coplanly`, re-enter the debug SHA-1:
   ```
   keytool -list -v -keystore ~/.android/debug.keystore \
           -alias androiddebugkey -storepass android -keypass android
   ```
   Google Sign-In and the Calendar scope both stop working otherwise, and the failure surfaces as a
   generic sign-in error rather than a configuration one.
4. Add the **release** SHA-1 once §2.3 produces a key. Under Play App Signing this is the SHA-1 of
   the key **Google holds**, which the console shows you after the first upload — not your upload
   key's. Getting this wrong is the single most common cause of "sign-in works in debug, fails in
   production".
5. Uninstall the old build from every test device first: to Android these are two different apps.

### 2.3 Signing — and a correction to how irreversible it is

⚠︎ **New apps on Play are enrolled in Play App Signing and must upload an AAB, not an APK.** That
changes the risk profile that `docs/ROADMAP.md` REL-2 describes.

- Google holds the **app signing key**. You cannot lose it, and you cannot leak it.
- You hold an **upload key**. If you lose it, you request a reset through Play support and carry on.

So "losing the keystore means the app can never be updated again" is true of the legacy
self-signing model and **not** of the model a new app is placed in. The upload key is still worth
backing up in two places — a reset costs days you will not want to spend — but it is not the
irreversible item the roadmap calls it. *(The genuinely irreversible items are the `applicationId`,
the Firestore region, and the first published price tier's currency set.)*

- [x] The `signingConfig` block is in `app/build.gradle.kts`. It reads four properties, from
      `~/.gradle/gradle.properties` or the environment, and **never** from a tracked file:

      ```properties
      COPLANLY_RELEASE_STORE_FILE=/absolute/path/to/coplanly-upload.jks
      COPLANLY_RELEASE_STORE_PASSWORD=…
      COPLANLY_RELEASE_KEY_ALIAS=upload
      COPLANLY_RELEASE_KEY_PASSWORD=…
      ```

      **All four or none.** With any of them missing — which is every CI runner, deliberately —
      the release build still succeeds and comes out *unsigned*. That matters: `assembleRelease`
      on every pull request is the only place R8 runs, and it has to keep running on a machine
      that has no key and should never be given one.

- [ ] Generate the upload keystore and set those four. One command, and the alias is yours to pick:

      ```bash
      keytool -genkeypair -v -keystore coplanly-upload.jks \
              -alias upload -keyalg RSA -keysize 4096 -validity 10000
      ```

- [ ] Back it up in two places (a reset through Play support costs days — see the correction above
      for why it is not the unrecoverable event the roadmap used to describe).
- [ ] `./gradlew bundleRelease`, and keep the mapping file for each release: without it Crashlytics
      stack traces are unreadable.

### 2.4 What the listing needs

| Asset | Spec ⚠︎ | Note |
| --- | --- | --- |
| App name | 30 characters | `CoPlanly` leaves room — see §3.3 |
| Short description | 80 characters | The single highest-leverage string in the whole listing |
| Full description | 4,000 characters | Drafts in §3.3 |
| App icon | 512 × 512 PNG, 32-bit | |
| Feature graphic | 1,024 × 500 | Shown at the top of the listing and in some surfaces |
| Phone screenshots | 2–8, 16:9 or 9:16 | 4–6 is the practical number |
| Tablet screenshots | optional | Skip for launch; the app is phone-first |
| Privacy policy URL | required | Blocked on REL-4 |

**Localise the listing to Czech first, English second.** The app ships five locales
(`values`, `-cs`, `-de`, `-ru`, `-uk`); the *listing* should start with two and grow when there is
evidence anyone in DACH is installing it.

### 2.5 The declarations — where a wrong answer is a policy violation, not a typo

- **Data Safety.** Answers derived from the real schema are drafted in `docs/legal/DATA-SAFETY.md`.
  **Check them against the code before submitting.** Three that need care for this app specifically:
  it collects **health information** (a child's medical profile), it collects **photos**
  (receipts, event images), and data **is** shared with another user (the co-parent) — which is the
  product, but must be declared as such.
- **Account deletion.** Play requires a deletion route that works **without the app installed**.
  In-app deletion ships (PR #68, server-side teardown plus a local wipe) and is not sufficient on
  its own; a public web page is also required. That page is cloud work, its hosting is not.
- **Content rating** (IARC questionnaire), **target audience and content**, **ads declaration**
  (none), **financial features** (none until MON-11), **health apps declaration** ⚠︎ — the last is
  worth reading carefully, because a shared calendar that stores a child's allergies and medication
  is closer to that category than it feels.
- **Consent for analytics.** REL-5. A release build currently collects by default; an EU launch
  needs a first-run consent gate defaulting to **off**, wired to
  `setAnalyticsCollectionEnabled` / `setCrashlyticsCollectionEnabled` **at runtime**, not only at
  injection time.

### 2.6 Closed testing — the requirement that sets the launch date

⚠︎ **Personal developer accounts created after November 2023 must run a closed test with at least
12 testers opted in continuously for 14 days before applying for production access.** Google has
already changed this number once (it was 20), so check the current figure — but plan on the shape of
it: **a two-week floor between "the app is ready" and "the app can be published", and twelve real
people.**

For CoPlanly this is harder than for most apps and better than for most apps:

- **Harder:** this product cannot be tested by one person. Every failure mode that matters — pairing,
  sync, custody proposals, chat delivery, the expense split — only appears across two devices on two
  accounts. Twelve testers means **six real co-parent pairs**, and co-parent pairs who will both
  install a beta are not easy to find.
- **Better:** fourteen days with six real pairs is exactly the trial the product needs anyway. Do
  not treat it as a Play formality to be satisfied with twelve colleagues; treat it as the only
  chance to see the app used by people whose situation it was built for, before strangers rate it.

**Where to find six pairs:** the professional channel in §4.4 is the answer, and it is the reason to
start those conversations *now* rather than after launch. A mediator who is willing to hand the app
to three of their families solves the closed track and the first distribution problem in one call.

### 2.7 The one test CI cannot run — REL-7

- [ ] Install a **release** build, save a child's medical profile, confirm it reaches the co-parent
      **non-empty**.

A green `assembleRelease` proves the build survives shrinking. It does **not** prove Gson still
finds its field names afterwards, which is the defect (audit §2.8) the keep rules in
`proguard-rules.pro` were written for and which **had already shipped once**. Nothing but a real APK
answers this.

While the release build is on a device, the same session is the cheapest moment to eyeball what
shipped unseen: the colour picker, the family switcher, the second co-parent invite, and — from the
August 2026 calendar work — whether the borrowed days at the edges of the month grid read as
*present but secondary* rather than as a second full-strength month.

### 2.8 Publication checklist, in order

```
[ ]  1. functions/.env, deploy functions, backfills ×2, rules+indexes, storage   (§2.0)
[ ]  2. Firestore region confirmed as EU                                          (§2.0)
[ ]  3. D-U-N-S application started, if going organisation                        (§2.1)
[ ]  4. Firebase + Cloud console: app.coplanly, new google-services.json, SHA-1s   (§2.2)
[ ]  5. Upload keystore, signingConfig, bundleRelease builds green                 (§2.3)
[ ]  6. Legal review of PRIVACY-POLICY.md and TERMS-OF-SERVICE.md                  (REL-4)
[ ]  7. Both hosted at stable URLs + a web account-deletion page                   (REL-4)
[ ]  8. Settings rows linked to those URLs, once they resolve                      (REL-4)
[ ]  9. Analytics consent gate                                                     (REL-5)
[ ] 10. REL-7 on a real device: medical profile survives R8                        (§2.7)
[ ] 11. Play Console: listing, assets, Data Safety, content rating, declarations   (§2.4–2.5)
[ ] 12. Closed track, 6 real pairs, 14 days                                        (§2.6)
[ ] 13. Production
```

Items 1–2 and 6–7 are the long poles. 6 depends on a lawyer's calendar and 12 on a fortnight that
cannot be compressed; start both before the engineering is finished.

---

## 3. Advertising it

### 3.1 The positioning, in one paragraph

> Two parents who are no longer together still have to run one child's life. CoPlanly is the shared
> calendar for that: whose day it is, what changes, what it cost. It works offline, it speaks Czech,
> and it does not ask you to talk to each other more than the situation needs.

Three things that paragraph deliberately does not say, and why.

- **It does not say "for divorced parents".** Separation, unmarried parents who split, and parents
  in the process of divorcing are the same user, and the word "divorce" excludes two of the three
  and lands badly on the third.
- **It does not promise evidence.** Until MON-4 and MON-3 ship there is no export and no
  guarantee behind the record. Design rule #8 in `CLAUDE.md` — *no affordance may promise a feature
  that doesn't exist* — governs the store listing exactly as it governs a button. A listing that
  advertises court-ready documentation is the largest possible instance of that defect.
- **It does not use conflict as the hook.** See §3.5.

### 3.2 Who is being spoken to, and when

The audience does not search for this category. **It is handed to them at a specific moment, by a
professional, during the worst month of their year.** That single fact should shape the entire
spend: almost none of it belongs in search ads.

Three arrival moments, in descending order of how well they convert:

1. **A mediator or OSPOD worker says "use this".** Highest trust, both parents present, and — under
   § 100(3) o.s.ř., where a court can order a first meeting with a registered mediator of up to
   three hours — a guaranteed moment with **both parents in the same room**, which is the hardest
   thing to arrange in this market.
2. **The other parent invites them.** The product's own growth loop. It only works if the invite is
   frictionless, which is why "invite by email does not send an email" was the growth path being
   broken rather than a missing nicety.
3. **They go looking, at 1 a.m., after an argument about a Wednesday.** Rare, but this is who the
   store listing is for.

### 3.3 Store listing drafts

**Czech copy below is a draft and needs a native pass before publication.** It is written to be
edited, not shipped.

#### Short description (80 characters — the one string worth agonising over)

| # | Czech (draft) | English | Angle |
| --- | --- | --- | --- |
| A | `Sdílený kalendář pro rodiče, kteří spolu nežijí. Péče, výdaje, domluva.` | Shared calendar for parents who live apart. Care, expenses, agreement. | Plain. Says the category and the audience in one line. **Start here.** |
| B | `Čí je to den? Kalendář střídavé i nesymetrické péče — česky a offline.` | Whose day is it? A calendar for shared and asymmetric care — in Czech, offline. | Leads with the question the app answers. Names the two differentiators. |
| C | `Jeden kalendář pro dvě domácnosti. Bez dohadování, co jsme si řekli.` | One calendar for two households. No more arguing about what was agreed. | Emotional. Test it, but it edges toward the conflict framing §3.5 warns about. |

A/B test A against B once there is traffic; do not guess between them.

#### Full description — structure

Play's full description is read by roughly nobody in full and by the ranking system in full. Write
for both: the first two lines carry the decision, the rest carries the vocabulary.

```
Sdílený kalendář pro rodiče, kteří spolu nežijí.

Čí je to den, co se změnilo, co to stálo. Jedno místo pro obojí domácnost —
i když zrovna nemáte signál.

PÉČE, JAK JI POPISUJE SOUD
• Střídavá i nesymetrická péče — vzory i vlastní rozvrh
• Barevný kalendář: na první pohled vidíte, čí je který den
• Předání dítěte je v kalendáři vidět, ne dohadované
• Návrh změny rozvrhu, který druhý rodič potvrdí nebo odmítne
• Jednorázová výměna dnů

CO SE DĚJE S DÍTĚTEM
• Události, připomenutí, opakované termíny
• Kroužky, lékař, škola — a kdo tam jde
• Profil dítěte: alergie, léky, kontakty
• Prázdniny a státní svátky přímo v kalendáři

VÝDAJE BEZ DOHADOVÁNÍ
• Kdo co zaplatil, kolik kdo dluží
• Účtenka vyfocená a přečtená přímo v telefonu — nic se nikam neposílá
• Dohodnutý poměr dělení, který platí od chvíle, kdy jste se dohodli
• Rozpočty a měsíční přehled

DOMLUVA
• Chat jen mezi vámi dvěma, s doručenkami
• Připravené formulace pro chvíle, kdy se hůř hledají slova

TAKÉ
• Funguje offline, synchronizuje se, až je připojení
• Import z Google Kalendáře
• Prarodič nebo chůva může vidět kalendář, aniž by cokoli měnil
• Česky, anglicky, německy, rusky a ukrajinsky

CoPlanly nevzniklo proto, abyste spolu mluvili víc. Vzniklo proto, abyste
se nemuseli domlouvat na tom, co už jste si jednou řekli.
```

Every bullet above corresponds to something that exists in the code today. **Nothing about export,
PDF, court documents, payments, tone analysis, or school import appears** — those are §1's future,
and the day MON-3 ships is the day two bullets get added, not before.

#### App name (30 characters)

`CoPlanly` alone wastes the field. `CoPlanly – kalendář pro rodiče` is 30 exactly and carries the
category into search. ⚠︎ Play has periodically tightened rules on promotional text in the title;
a plain category descriptor has always been allowed, a superlative has not.

### 3.4 The professional one-pager — the highest-value asset here

Not an ad. A single A4 a mediator can hand to two parents, or leave on a table. This is worth more
than any paid channel and costs printing.

```
┌────────────────────────────────────────────────────────────┐
│  CoPlanly                                                  │
│  Sdílený kalendář pro rodiče, kteří spolu nežijí           │
│                                                            │
│  [ screenshot: month grid, custody colours ]               │
│                                                            │
│  OD 1. LEDNA 2026 soud neurčuje typ péče, ale rozsah       │
│  péče každého rodiče — konkrétní dny.                      │
│  Přesně tak, jak to zapisuje kalendář.                     │
│                                                            │
│  • Rozvrh péče, který vidí oba rodiče stejně               │
│  • Změny se navrhují a potvrzují, ne oznamují              │
│  • Výdaje a dohodnutý poměr dělení                         │
│  • Funguje offline · česky · Android i bez placení         │
│                                                            │
│  Zdarma pro rodiče:  kalendář a péče, bez omezení          │
│  Pro odborníky:      účet zdarma, neomezeně rodin          │
│                                                            │
│  coplanly.app          [ QR na Google Play ]               │
└────────────────────────────────────────────────────────────┘
```

The legal line at the top is the whole argument. **Zákon č. 268/2025 Sb.**, in force since
1 January 2026, merged divorce and custody proceedings and stopped courts awarding custody as a
category — where parents have not agreed, the court now determines *the scope of each parent's care,
for example specific days of the week*. Czech custody is now described in law the way a calendar
describes it, which is what `CustodyModel` already stores. That is a justification for the product's
existence that can be quoted almost verbatim, and it is eight months old, which means every
professional in the channel is currently rethinking their own materials. ⚠︎ **Have a lawyer confirm
the wording before printing it** — a marketing claim about a statute is a legal claim.

**Free professional accounts with unlimited families** is OurFamilyWizard's playbook and the single
most transferable thing in it. It is a distribution channel dressed as a feature. Revenue-sharing
with courts is neither available nor legally plausible in Czechia; free professional accounts are.

### 3.5 What never to say

This matters more than the copy that does get written, because the obvious copy in this category is
the wrong copy, and competitors have already shown what it costs.

- **Never "gather evidence against your ex".** The Czech incumbent app2us — built by two practising
  lawyer-mediators — frames its unalterable messages not as evidence-gathering but as *a reason to
  choose your words carefully*. That framing is correct and it is also better marketing: parents in
  the middle of this do not want to be told they are in a war, and the professionals who are the
  distribution channel will not hand out something that says they are.
- **Never imply the app takes a side.** Parent colours in CoPlanly identify a *person*, never a
  role; the app never shows the words "Mom" or "Dad". The marketing has to hold the same line.
- **Never promise what MON-3 has not built.** See §3.1.
- **Never use a real family's data in a screenshot.** Seed a demo account. This is obvious right up
  until the day someone is in a hurry.
- **Never advertise the tone check as emotion detection**, if MON-12 ever ships it. It must never
  block sending and the analysis must never be stored — a saved "your message was aggressive"
  verdict is discoverable material in a custody dispute, which makes it a liability to the user
  rather than a feature. Anything resembling emotion inference also deserves a legal read under the
  EU AI Act before launch.

### 3.6 Channels, ranked by expected return

| Rank | Channel | Cost | Why |
| --- | --- | --- | --- |
| 1 | **Registered family mediators** | Phone calls | One source puts them at ~25, about half active. If that holds, **the entire channel is coverable personally in a week.** Verify against the justice.cz register (MON-2 #4). |
| 2 | **Courts running Cochem practice** (Nový Jičín since 2016, Most since 2017) | Meetings | Cochem practice is built on parental agreement; this is the tool for it. |
| 3 | **OSPOD** at municipalities with extended competence | Meetings, slow | Public bodies move slowly and then recommend consistently for years. |
| 4 | **stridavka.cz** | An email | Already publishes a co-parenting tools roundup — a directly reachable placement. |
| 5 | NGOs and portals: zustavamerodici.cz, APERIO, sancedetem.cz, Unie otců, Liga otevřených mužů | Emails | |
| 6 | **The in-app invite** | Engineering | Already the growth loop; make sure the email actually sends. |
| 7 | Closed Facebook groups for single parents | Time, and tact | Not indexed; needs manual search and a real presence, not a drive-by post. Marketing at people mid-crisis in a support group backfires hard. |
| 8 | Google Play ASO | Free | The listing has to be good regardless; it is not a channel on its own for a category nobody searches. |
| 9 | **Paid search** | Money | Last. The audience does not search for this. Spend here only after 1–5 are exhausted and only to defend the brand term. |

---

## 4. How it earns

### 4.1 Where this starts: there is no billing layer at all

No Play Billing dependency, no purchase code, no entitlement model, no paywall. Everything below is
a decision (MON-1) followed by construction (MON-11). Make the decision first — it shapes the code,
and getting the *unit* wrong after launch is a refund queue.

### 4.2 The model

**One subscription per family. The second parent free. A permanently free tier that is not a
crippled trial.**

Three reasons, and the third is the one that decides it:

1. **It is what the winning European products do.** CoParently.de charges €4.99/month covering the
   whole family and keeps a permanently free basic tier; 2houses and ParentDocket are also
   family-unit. The American per-parent model correlates with OurFamilyWizard's 1.4★ on Trustpilot
   against 4.6★ in the stores — the signature of court-mandated use plus per-seat billing.
2. **The product does nothing until both parents install it.** A paywall at the door kills the
   network effect that makes it work at all. Free must cover calendar and custody **completely**.
3. **In a conflicted pair, one person will pay.** Charging both means losing both. This is not a
   pricing preference; it is the central fact about this market.

### 4.3 The price

**99–149 CZK/month, or 990–1,490 CZK/year.**

Anchors: Instagram Plus 70 CZK/month, Prima+ 99–149, Skylink TV+ 99, Netflix 259–419. Average gross
wage in 2025 ~49,200 CZK/month, so 1,200 CZK/year is about 2.4% of one average monthly wage. The
Czech press treats ~966 CZK/month across four streaming services as a problem worth writing about:
**this is a price-sensitive market**, and 149 is the top of the band a family pays without thinking
about it.

Competitors converted to CZK per year per family (rates unverified): Cozi Gold ~820,
CoParently.de ~1,470, 2houses ~2,700, Coparently ~4,160, OurFamilyWizard ~4,600–12,600,
TalkingParents Ultimate ~16,100. Entering at ~1,200 puts CoPlanly at the bottom of the serious
range and above the "not a real product" line.

**Annual should be roughly ten months of monthly**, not eleven — the conversion to annual is worth
more here than the margin, because the paying month is the month of a court date and the churn risk
is the month after it.

### 4.4 Where the line goes

| Free, permanently | Paid |
| --- | --- |
| Shared calendar, month/week/day | **Export: PDF and CSV** (MON-3) |
| Custody schedule, presets, custom patterns | Handover punctuality report |
| Change proposals, one-off swaps | Expense ledger export with receipts |
| Events, reminders, recurrence | Longer history retention |
| Chat between the two parents | Rodičovský plán: the diff and its export (MON-5) |
| Expenses, receipts, on-device OCR, budgets | School import (MON-8) |
| Agreed split ratio and balances | Tone check before sending, if it ever ships (MON-12) |
| Children and pets, medical and school contacts | |
| Google Calendar import | |
| Calendar friend (grandparent) read-only access | |
| All five languages, offline | |

The principle: **free is everything you use every week; paid is everything you need in the week it
goes wrong.** Nobody pays a subscription to know whose Tuesday it is. People pay, in the month they
need it, for a document they can hand to a lawyer.

That is also why MON-3 is the first paid feature and why MON-4 gates it. Unalterable records and
court-ready exports are the most expensive feature in this category *everywhere*: OFW's Premium
notarises and posts a physical court packet, TalkingParents puts Unalterable Records in its
$32/month Ultimate, AppClose announced Certified Electronic Business Records in 2026.

### 4.5 The wrinkle M-4 introduced

A subscription is per *family*, and since M-4 a person can have **two**. Decide whether an
entitlement follows the payer across their families or is bought per relationship. `familyId` makes
either expressible; getting it wrong after launch is a refund queue and a support load.

The defensible answer is **per relationship**: the paying parent's *other* family has a different
co-parent who is not party to that agreement, and an entitlement that spilled across would let one
person's payment silently give a stranger paid features. But it is a decision, not a derivation, and
it should be made explicitly and written down.

### 4.6 What not to build

- **A parent-to-parent payment rail.** Onward closed on 8 October 2024 built entirely on expense
  splitting and payments. The balance is already computed per currency; the honest first version is
  an export and a payment link, not a payment rail.
- **A per-seat tier.** See §4.2.
- **A free tier that expires.** AppClose ended its free plan on 1 January 2026 and TalkingParents
  removed theirs on 30 March 2026 — which reads as an opening for honest freemium and is
  simultaneously evidence that monetising a free tier here is hard. Take the opening, but price the
  paid tier so it does not depend on converting the free one.
- **Ads.** In an app holding a child's medical profile and two parents' conflict, an ad network is a
  data-protection problem and a trust problem at once.

### 4.7 The revenue arithmetic, honestly

At 1,200 CZK/year per family and a 3–5% free-to-paid conversion — the realistic band for a
freemium utility, not the 10% people quote — **1,000 paying families is ~1.2M CZK/year and needs
20,000–33,000 installed families.** In a country whose single-parent household count was last
firmly measured at ~175,700 (2015 — verify, MON-2 #6), that is a meaningful share of the
addressable market and not an absurd one.

Which is the real conclusion: **Czechia alone supports a good side business, not a company.** The
DACH expansion the audit sketches is what changes the ceiling, and it is more expensive than it
looks — CoParently.de is cheaper than the CZ price at €4.99 covering a family, and
Getrennt-Gemeinsam (Väter-Netzwerk e.V.) is entirely free and professionally endorsed. Going there
means competing with a cheaper local incumbent, not with American pricing.

---

## 5. The next 90 days

Not a schedule — an order. Each line unblocks the ones under it.

**Weeks 1–2 — unblock what is already built**
1. `functions/.env`, deploy, both backfills, rules, indexes, storage (§2.0)
2. Confirm the Firestore region is in the EU, before another byte lands
3. Start the D-U-N-S application if going organisation; start the lawyer conversation for REL-4
4. Call the mediators (§3.6 rank 1) — this is what supplies the six pairs in week 8

**Weeks 3–5 — make it installable**
5. REL-1 console half, REL-2 upload key, `bundleRelease` green
6. REL-5 analytics consent gate
7. REL-7 on a device: the medical profile survives R8
8. Legal documents hosted; the web deletion page live; Settings rows linked

**Weeks 6–8 — the listing**
9. Screenshots from a seeded demo account, feature graphic, the one-pager printed
10. Czech listing copy through a native pass; Data Safety checked against the schema
11. Closed track opens with six real pairs

**Weeks 9–10 — the fourteen days**
12. Watch, and fix what six real pairs find. This is the most valuable fortnight in the whole plan;
    do not fill it with feature work.

**Weeks 11–13 — publish, then MON-4**
13. Production
14. **MON-4** — decide what a court-facing record guarantees. It is the gate on the first paid
    feature, and it is a decision, so it can be made while the store review runs.

Everything else — MON-3, MON-1, MON-11, MON-5, MON-8 — comes after there are users to price for.
