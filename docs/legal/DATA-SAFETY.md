# Play Data Safety — draft answers

> **Derived from the code, not from memory.** Every row below was checked against the
> Firestore collections, the Storage rules and the SDKs the app actually initialises. Re-check
> it against the code before submitting: a wrong Data Safety declaration is a policy
> violation, not a typo, and Google compares it against what the binary does.
>
> Sources: `firestore.rules`, `storage.rules`, `data/local/entity/`, `di/FirebaseModule.kt`,
> `data/analytics/AnalyticsManager.kt`, `data/crashlytics/CrashlyticsManager.kt`,
> `data/mlkit/`, `functions/index.js`.

## Summary answers

| Question | Answer | Basis |
| --- | --- | --- |
| Does your app collect or share any of the required user data types? | **Yes** | |
| Is all data encrypted in transit? | **Yes** | Firebase SDKs use TLS throughout |
| Do you provide a way for users to request that their data is deleted? | **Yes** | Settings → Account → Delete account, backed by the `deleteAccount` callable |
| Is data collection required, or can users choose? | **Required** for the account and shared content; **optional** for the medical profile, photos, and Google Calendar |
| Have you committed to Play's Families policy? | {{DECIDE}} — the app is for parents, not children, and offers no child accounts |

## Data types

"Shared" in Play's sense means sent to a third party. The co-parent is not a third party for
this purpose — they are another user of the same account family — but transfers to Google as
our processor are declared.

| Data type | Collected | Shared | Optional | Purpose |
| --- | --- | --- | --- | --- |
| Name | Yes | No | No | Account management, app functionality |
| Email address | Yes | No | No | Account management, invitations |
| User IDs | Yes | No | No | Account management |
| Photos | Yes | No | Yes | App functionality — receipts, event images, medical and pet photos |
| Calendar events | Yes | No | No | App functionality |
| Messages (in-app) | Yes | No | No | App functionality |
| Health info | Yes | No | Yes | App functionality — the child's medical profile |
| Purchase/financial info | Yes | No | Yes | App functionality — shared expenses and budgets. **Not** payment data: the app processes no payments |
| App interactions | Yes | No | No | Analytics |
| Crash logs | Yes | No | No | Diagnostics |
| Diagnostics | Yes | No | No | Diagnostics |
| Approximate/precise location | **No** | — | — | The app requests no location permission |
| Contacts | **No** | — | — | The address book is never read; an emergency contact is typed by hand |
| Payment info | **No** | — | — | No billing exists yet — **revisit when it does** |

## Notes worth writing into the declaration

**Health data.** The child's medical profile is the most sensitive thing the app holds:
allergies, medications, conditions, blood group, vaccinations, notes, and photographs of
documents. It is optional, entered by a parent, and readable only by the two parents and by
guests they explicitly invite for a limited time. Declare it as **Health info → collected,
optional**.

**Photos are stored under unguessable paths, not access-controlled ones.** Cloud Storage rules
cannot read Firestore, so a photograph's URL is protected by being unguessable rather than by
a rule that knows who a parent is. This is documented at length in `storage.rules`. It does
not change the declaration, but it is the honest state of the control and should be fixed
before this ships (see `docs/ROADMAP.md`, **SEC-1**).

**Receipt OCR is on-device.** ML Kit's bundled model recognises receipt text without the
photograph or the text leaving the device. Nothing about it is collected or shared, and it is
worth saying so in the listing — it is a genuine differentiator in this category.

**Private events never leave the device.** Events marked private are excluded from every sync
path. They are not collected in Play's sense.

**Analytics and crash reporting are release-only** as of the August 2026 audit; debug builds
no longer report. A consent gate is still outstanding (`docs/ROADMAP.md`, **REL-5**) — **update this
declaration when it ships**, because at that point collection becomes optional rather than
required.

**No advertising, no ad IDs, no tracking.** The app declares no advertising SDK and does not
link data to third-party identifiers.

**AI.** No user data reaches a generative model today: the AI screens are not reachable from
navigation. **If any AI feature ships, this declaration must be revisited**, because the
prompts would carry calendar contents and message text to a third party.
