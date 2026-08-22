# The first run, and why the app asks — design

**Date:** 23 August 2026
**Branch:** `docs/onboarding-wizard-2026-08` (spec and plan only)
**Base:** `main` @ `14e00cbe`
**Depends on:** PR #49 (`feat/child-parent-profiles-2026-08`). Nothing here can be implemented until that merges.

This is package **B2** of the nineteen-item improvement list, covering:

- **Item 3.** On first run, fill in a questionnaire: your own details (name, date of birth, phone, medical), the same for the child, contacts for close relatives, the custody schedule, and then the co-parent — their details, and an invitation by link or code.
- **Item 4.** A footnote saying the information is collected **for you**, in case of unforeseen circumstances.

**B1 already built the data.** Every field item 3 asks for now exists, is editable, and reaches the co-parent. What is missing is the guided path through it on a first run, which is all this package adds.

---

## 0. Decisions taken without the owner present

The owner was asleep when this was written and asked for the recommended option at each fork. Each one below is a real fork, and each is cheap to flip — the point of listing them is that flipping one should cost a sentence, not a re-design.

| # | Question | Taken | Cost to flip |
|---|---|---|---|
| 1 | What is mandatory? | **Only the parent's own name.** Everything else, medical included, is skippable at every step. | One validation predicate. |
| 2 | Where does "onboarding done" live? | **`users/{uid}.onboardingCompletedAt`**, an ISO string, mirrored into Room. | A Room column and a map entry. |
| 3 | Do the custody and pairing steps get new screens? | **No** — the wizard navigates to the existing `CustodySetupScreen` and `PairingScreen`. | Would be a large refactor to change. |
| 4 | Does an existing user see the wizard? | **No.** An account that already has a name and any child info is marked complete silently on first launch after upgrade. | One predicate in the same place. |
| 5 | Where does the item 4 footnote appear? | **On the intro step in full, and as a one-line footnote under every data-collecting step.** | Delete one composable call. |

### Why item 1 is the one worth arguing about

Item 3 says *«все эти данные необязательно, кроме твоих личных»* — everything optional except your own. Read strictly, that makes the whole "about you" block mandatory, blood type included.

This spec does not read it strictly, and the reason is item 4. The footnote the same list asks for says the data is collected **for you**, against an emergency. Data gathered for the user's own benefit must not become a gate on the product: a parent who does not know their own blood group would be locked out of their calendar until they looked it up. Name stays mandatory because the app genuinely cannot function without it — every event, expense and custody day is labelled with a parent's name, and `ParentLabels` has no honest fallback.

If the owner wants the strict reading, it is one predicate in `OnboardingStep.Profile`'s validation and nothing else moves.

## 1. What exists to build on

| Piece | State |
|---|---|
| Parent profile — name, DOB, phone, allergies, medical | `ProfileScreen` + `ProfileViewModel` (B1) |
| Child — name, DOB, medications, activities, allergies, medical, school | `AddEditChildInfoScreen` (B1 extended it) |
| Relatives' contacts | `EmergencyContact` on `ChildInfo`, edited by `EmergencyContactEditor` |
| Custody schedule | `CustodySetupScreen` |
| Co-parent invitation by link, code and QR | `PairingScreen` |
| Medical editor, reusable | `MedicalProfileEditor(profile, onChange, modifier, enabled)` (B1) |

So four of the five steps are composition. The genuinely new parts are the wizard's own frame, its completion state, and the footnote.

## 2. The five steps

```
Intro  →  1. About you  →  2. About your child  →  3. Relatives  →  4. Custody  →  5. Co-parent
```

**Intro** states what the app is about to ask for and why — item 4's text in full, not a footnote. It is the only screen that exists purely to explain, and it earns its place: a questionnaire asking a separated parent for their blood type, unexplained, reads as intrusive.

**Step 1, About you.** Name (required), date of birth, phone, allergies, medical profile. Reuses B1's section composables, not `ProfileScreen` itself — that screen owns a `Scaffold` and a save bar the wizard supplies instead.

**Step 2, About your child.** Name, date of birth, allergies, medical profile. Deliberately **not** the full `AddEditChildInfoScreen`: medications, activities and school are not first-run questions, and a wizard that asks for a teacher's email before the calendar has been seen once will be abandoned. They stay one tap away in Settings.

**Step 3, Relatives.** A list of `EmergencyContact`s — aunt, uncle, grandmother, grandfather — with a relationship field. Reuses `EmergencyContactEditor`. These are saved onto the **child's** record, not the parent's, because that is the document both parents may write (B1, spec §7) and item 5 says the second parent may add to them.

**Step 4, Custody.** Navigates to the existing `CustodySetupScreen`, returning to the wizard on completion.

**Step 5, Co-parent.** Navigates to the existing `PairingScreen`. Finishing here finishes onboarding.

Every step after the intro has **Skip**. Step 1 requires only a non-blank name.

## 3. Where the wizard sits in navigation

`NavGraph`'s `startDestination` is currently `Loading` → `Auth` → `Home`. Onboarding becomes a fourth branch:

```
isLoading            -> Loading
isAuthenticated != true -> Auth
onboarding needed    -> Onboarding
otherwise            -> Home
```

"Onboarding needed" is a suspend check, not a synchronous field, so the `Loading` state has to cover it — the branch cannot flicker Home and then replace it.

**An upgrade must not be ambushed.** An account with a profile name and at least one child info row is treated as complete and the marker is written, silently, on first launch after upgrade. Without this every existing user is handed a questionnaire about data they have already entered.

## 4. Completion state

`users/{uid}.onboardingCompletedAt` — an ISO date-time string, matching how every other date crosses this Firestore schema. Mirrored to `UserEntity.onboardingCompletedAt`, so the check is a Room read and the wizard does not wait on the network.

**Not `EncryptedPreferences`.** CLAUDE.md records that `clear()` wipes it on sign-out except for one deliberately exempted prefix; onboarding state would be lost on every sign-out and the wizard would reappear over data that already exists. Room plus Firestore also gets the second-device case right for free.

Requires a Room migration **14 → 15**, one nullable column, additive.

## 5. The footnote — item 4

One string, shown in full on the intro and as a one-line footnote beneath each data-collecting step:

> *These details are kept for you and your co-parent, so that whoever is with your child can act if something unexpected happens.*

Two things it must not claim. It must not say the data is encrypted — it is not (B1 spec §9), and a false security promise about medical data is worse than none. It must not say "only you can see this" — the co-parent can, by design, and item 5 requires it.

The Russian, Czech, German and Ukrainian wording lands with the implementation, in all five locales as usual.

## 6. What this package does not do

- **No new fields.** Every field is B1's. If the wizard wants something B1 lacks, that is a signal the wizard is over-reaching, not that B1 is short.
- **No change to sharing.** B1's audience policy already covers everything the wizard writes.
- **No re-run entry point.** There is no "redo onboarding" in Settings; every screen the wizard visits is already reachable there individually.
- **Not a gate on pairing.** Skipping step 5 leaves the user unpaired and on Home, where item 8's CTA already lives.

## 7. Verification

| Check | How |
|---|---|
| Step validation | JVM tests: only a blank name blocks step 1; every other step's Skip is always enabled. |
| Completion predicate | JVM tests: a fresh account needs onboarding; an account with a name and child info does not; a completed marker wins over both. |
| Migration 14 → 15 | Instrumented, on the device already used for 13 → 14. |
| Locales | grep, five files per new key. |
| Build | `assembleDebug testDebugUnitTest lint detekt` |

**A device run is required for this one**, and unlike B1 it is the point rather than a formality: a wizard is a sequence of screens whose value is entirely in how it feels to walk through. Minimum: walk it end to end on a fresh install; walk it skipping everything after step 1; kill the app mid-wizard and confirm it resumes rather than restarting; and confirm an account that already has data never sees it.

## 8. The one thing to decide before implementing

Whether the strict reading of item 3 governs — see §0 item 1. Everything else in this document can be implemented as written and adjusted later; that one changes what the first screen does to a user who cannot answer it.
