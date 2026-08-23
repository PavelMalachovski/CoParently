# Swapping a day, and seeing whose it is — design

**Date:** 23 August 2026
**Package:** **C** of the nineteen-item improvement list
**Base:** `main` @ `14e00cbe`
**Depends on:** nothing unmerged. PR #47 (custody proposal) is already on `main`.

Four items, all about the calendar grid and the custody it draws:

- **Item 6.** Remove the "Today with X" banner — the colours already say it.
- **Item 7.** Long-press a day to offer the co-parent a **one-off** swap, which they accept or decline.
- **Item 10.** A handover day's cell is split diagonally between the two parents, and the home screen says when the child changes hands.
- **Item 11.** A day with a pending swap request shows two arrows, one under the other.

---

## 0. Decisions taken without the owner present

| # | Question | Taken | Cost to flip |
|---|---|---|---|
| 1 | Is a one-off swap a new concept, or a `ChangeRequest`? | **New.** `DayOverride`, carried on the pair's shared custody document. | Large — it is the spec's spine. |
| 2 | Does an accepted swap move the whole day, or a time range? | **The whole day.** | Moderate: a range needs two more fields and a UI for them. |
| 3 | Can several swaps be pending at once? | **Yes**, one per date, newest wins per date. | Small. |
| 4 | Does the handover time come from the custody model? | **No — there is no handover time in the schema.** Item 10's "at a certain time" is deferred; the home reminder says the day, not the hour. | Needs a new field and a product decision. See §5. |
| 5 | Does removing the banner lose the handover countdown? | It moves to the home screen's existing hero, which already shows it. | None. |

## 1. Why a one-off swap is not a `ChangeRequest` and not a `CustodyProposal`

Two agreement mechanisms already exist and neither fits.

**`ChangeRequest`** is bound to an `eventId` — it proposes a new start and end time for one event. A custody day is not an event; it is a property of the pattern. There is no row to point at.

**`CustodyProposal`** (PR #47) proposes a whole replacement pattern: `modelType`, `patternDays`, `momDayIndices`, `startDate`. Expressing "just next Saturday, we swap" as a pattern would mean generating a bespoke pattern that differs from the agreed one in one day and never repeats — which is not a pattern, and would overwrite the agreed one on acceptance.

So: a third thing, deliberately small.

```
custody_models/{pairId}
  … the agreed pattern …
  proposal:     { … }        <- PR #47, a whole-pattern change
  lastDecision: { … }        <- PR #47
  dayOverrides: {                          <- NEW
    "2026-09-05": {
      toParent: "dad",        // the slot taking the day
      requestedBy: <uid>,
      requestedAt: <iso>,
      status: "PENDING" | "ACCEPTED" | "DECLINED",
      decidedBy: <uid>?, decidedAt: <iso>?, note: <string>?
    },
    …
  }
```

**A map keyed by ISO date, not a list.** One override per date is the whole semantics — "we swap next Saturday" cannot mean two different things at once — and a map makes that structurally true instead of a rule the code has to enforce. It also makes the calendar's lookup an O(1) read on a date it already has in hand, for every cell it draws.

**On the same document as the pattern, for the same reason PR #47 put the proposal there:** one document is one listener and one rule block, and deciding is a single write rather than two that need a transaction. The listener on this path has already produced one production defect; this does not add a second.

## 2. Custody with overrides — one lookup, not two

Today `CalendarScreen.getCustody` resolves a day through the active `CustodyModel`, falling back to the legacy `CustodyScheduleEntity`. Every caller — the month grid, the week grid, the ribbon, the handover calculator — goes through it, and CLAUDE.md requires that.

Overrides join **inside** that function, not beside it:

```kotlin
fun custodyFor(date: LocalDate): String   // "mom" or "dad"
```

resolves in order: an **accepted** override for that date wins; otherwise the active model; otherwise the legacy schedule. A pending or declined override changes nothing — it is a marker only.

This ordering is the whole correctness of the feature and belongs in a pure function with its own tests, not spread across composables. Getting it wrong in the other direction — letting the pattern win over an accepted swap — would show both parents a day that neither of them agreed to.

**`HandoverCalculator` must consume the same function.** It currently walks forward through `model.getCustodyFor`; with overrides in play, a swap creates and removes handovers, and a calculator that cannot see them tells the home screen the wrong date. This is the change most likely to be forgotten, because nothing fails loudly when it is.

## 3. Item 7 — the gesture and the flow

Long-press a day in the month grid → a bottom sheet naming the date, who has it now, who would take it, and an optional note → **Offer swap**. The co-parent sees it in the change-request inbox alongside event requests, and accepts or declines.

Long-press rather than a menu because the calendar's tap is already spoken for (select the day) and the design refresh removed the unlabelled header actions on purpose. Long-press on a day cell is unused today.

**Only a paired account may offer a swap.** Unpaired, the long-press does nothing — there is nobody to accept, and a swap that applies itself is just an edit, which the custody editor already does.

**Accepting writes `status: ACCEPTED` and nothing else.** The pattern is untouched; `custodyFor` reads the override. This is what keeps a swap one-off: no code path folds it back into the pattern, so it cannot silently become permanent.

## 4. Items 6, 10 and 11 — what the grid draws

**Item 6.** `CustodyRibbon` is removed from `CalendarScreen`. The component file goes with it; nothing else calls it. The handover countdown it also carried is already on the home screen's `HandoverHero`, so nothing is lost — and the design refresh's own rule is that a screen should not carry two answers to the same question.

**Item 10 — the diagonal cell.** A day is a *handover day* when `custodyFor(date) != custodyFor(date - 1)`: the child changes hands that morning. Its cell is painted with both parents' hues split on a diagonal — the previous day's parent in the top-left triangle, the new day's parent in the bottom-right, reading the way time does.

This lands in `DayCellFills`, which already separates a cell's `base` from its `overlay` for exactly this kind of reason. A handover becomes a third overlay shape rather than a fourth colour, so the weekend band underneath still shows through and the file's existing invariant — the weekend is a base, never a competitor — is unchanged.

**Item 11 — two arrows.** A date carrying a **pending** override shows two small arrows stacked, `→` over `←`, in the cell. Not a colour: the cell's colour still means whose day it is *now*, and a pending swap has not changed that. The arrows say "this is being negotiated", which is a different fact and deserves a different channel.

## 5. What item 10 asks for and this package cannot give

> *На главной странице должно быть напоминание, что ребенка передадут в определенное время.*

**There is no handover time anywhere in the schema.** `CustodyModel` carries `modelType`, `patternDays`, `momDayIndices`, `startDate`, `repeatYearly` — days, never hours. `HandoverInfo` carries a `LocalDate` and a day count.

So the home reminder in this package says **which day** the child changes hands and to whom, not at what hour. Adding an hour is a real feature with its own decisions — is it one time for the whole pattern, or per handover; whose timezone, given CLAUDE.md already records that the custody document's own timestamp is a naive local time and can pick the wrong winner across zones — and it drags a Room migration and a Firestore field with it.

Recorded here rather than guessed at. If the owner wants the hour, it is a small package of its own and should be one.

## 6. Rules and functions

`custody_models` already has a rule block gated on `participants`, with `allow get` rather than `allow read` so no list query can be issued, and `lastModifiedBy == request.auth.uid` required on write.

`dayOverrides` needs its own validation in the same block, and it is the interesting part: **the parent who requested a swap must not be able to accept it.** The rule must require that a transition to `ACCEPTED` or `DECLINED` is written by someone who is *not* the entry's `requestedBy` — the same shape PR #47 required for `proposal`, and for the same reason. Without it either parent can grant themselves a day and the other is merely told.

Emulator cases, in `firestore-tests/rules/custody-models.test.js`: a participant may add a pending override; a participant may decide one they did not request; the requester **may not** decide their own; a non-participant may do neither.

No Cloud Function change. `unpairCoParent` deletes the whole `custody_models` document already, so overrides go with it.

## 7. Migration

`dayOverrides` lives on the Firestore document. The Room mirror (`CustodyModelEntity`) needs a column to hold it — one nullable JSON string, additive, schema **15 → 16** (or 14 → 15 if B2 has not landed; the plan resolves the number at implementation time rather than guessing).

## 8. Verification

| Check | How |
|---|---|
| Override resolution | JVM tests for `custodyFor`: accepted wins over the pattern; pending and declined do not; the legacy fallback still works. |
| Handover detection | JVM tests: a swap creates a handover on the swapped day and removes the one it displaced. |
| Transitions | JVM tests on the pure transition function: the requester cannot decide their own; a decided override cannot be re-decided. |
| Rules | `firestore-tests` — the four cases in §6. |
| Migration | Instrumented, on the device. |
| Locales | grep, five files per new key. |
| Build | `assembleDebug testDebugUnitTest lint detekt` |

**A two-device run is the point of this package.** Offer a swap on phone A, accept on phone B, and confirm both grids show the swapped day, both show the diagonal on the right dates, and the home hero on both agrees about when the child changes hands.

## 9. Deliberately not in C

- **A handover time** — §5.
- **Recurring swaps.** "Every other Friday from now on" is a pattern change, which `CustodyProposal` already does.
- **Swaps in the past.** The sheet offers only today and later; a swap of a day already lived is a record-keeping feature, not a scheduling one.
- **Any change to `CustodyProposal`.** Whole-pattern agreement is done and this package does not touch it.
