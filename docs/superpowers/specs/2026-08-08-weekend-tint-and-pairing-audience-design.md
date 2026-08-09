# Weekend tint, and the events a co-parent never receives — design

**Date:** 8 August 2026
**Branch:** `fix/weekend-tint-and-pairing-audience`
**Base:** `main` @ `8cde4179` (PR #45, custody over Firestore, merged)

Three defects reported from a two-device session, closed together because two of them are one
change to the month grid and the third is the sync half of the same screen. The custody
propose/approve flow the same session asked for is a separate, larger spec and is **not** in
this one.

---

## 1. Weekends are not grey, and most of them are not tinted at all

### What the app does today

`MonthView.DayCell` (`MonthView.kt:282`) picks exactly one background per cell, first match wins:

```
!isCurrentMonth  -> colorScheme.surface
custody == mom   -> MomPink   @ 14%
custody == dad   -> DadBlue   @ 14%
isPublicHoliday  -> HolidayRed @ 10%
isWeekend        -> weekendColor
else             -> colorScheme.surface
```

`CustodyModel.getCustodyFor` (`CustodyModel.kt:30`) reduces any date into the pattern cycle and
returns `"mom"` or `"dad"` — **never null**. So on any account with an active custody model, every
in-month cell matches a custody branch and the weekend branch is unreachable. The only cells that
can still show a weekend tint are the ones from the neighbouring months in the first and last grid
rows, which is why the tint appears in some grid rows and not others with no pattern a user could
infer. Confirmed on the Samsung: `custody_models` holds one active `custom` model, 14-day cycle,
`momDaysPattern=[0,1,4,9,10,11,12]`, anchored 2026-08-08; `custody_schedules` is empty.

The colours are also not grey. `CoPlanlyColors.WeekendBackgroundLight` is `#FFF8E1` (warm cream)
and `WeekendBackgroundDark` is `#2D2D1E` (dark olive) — `Color.kt:99`.

`DayWeekView` (`DayWeekView.kt:533`) has the same shape with `isToday` inserted, and the same two
tokens at a further 0.3/0.5 alpha.

### The change

**The weekend stops competing for the cell and becomes the layer underneath it.**

1. Compute a base fill for every cell in the grid, including `DayPosition.InDate`/`OutDate` days:
   `isWeekend ? weekendGrey : colorScheme.surface`. This is what makes Saturday and Sunday read as
   one continuous band down all six rows.
2. Draw custody, holiday and today **over** that base, at their existing tokens and alphas. In
   Compose this is two chained `Modifier.background(…, shape)` calls; the overlay already carries
   alpha, so the base shows through.
3. Keep the existing precedence *among the overlays* untouched — custody still beats holiday, and
   in the week view custody still beats today. Nothing about the custody read changes except the
   colour it composites onto on two days out of seven.
4. Neighbouring-month days get the grey base and **no** overlay, matching their already-dimmed day
   numbers.

Recolour the two tokens to neutral greys, keeping them in `CoPlanlyColors` as fill-only values
(they are a semantic the Material roles do not name, the same argument that already keeps
`HolidayRed` and `VacationTint` there):

| Token | Was | Becomes |
|---|---|---|
| `WeekendBackgroundLight` | `#FFF8E1` warm cream | `#ECECEF` neutral light grey |
| `WeekendBackgroundDark` | `#2D2D1E` dark olive | `#2A2A31` neutral dark grey |

Both are chosen as a small neutral step from the surface they sit on (`#FFFFFF` light,
`#1B1B21` dark) so the band is legible without competing with a 14% custody wash.

The per-call-site alpha multipliers in `DayWeekView` (0.3 / 0.5) and `MonthView` (0.5 in light)
go away: one token per theme, applied at full strength, is what makes month and week views read as
the same system. Theme detection stays `surface.luminance() < DARK_LUMINANCE_THRESHOLD` — the app
can force light while the system is dark, and `isSystemInDarkTheme()` gets that wrong.

### Deliberately not done

Weekend does **not** win over custody. Weekends are the days a separated parent checks first;
replacing the parent hue there with grey would remove the answer from exactly the cells the
screen exists to answer.

---

## 2. The co-parent never receives events created before pairing

### What the app does today

`SyncService.syncEvents` (`SyncService.kt:88`) uploads `eventDao.getUnsyncedEvents()` — rows with
`syncedToFirestore = 0` — and computes `sharedWith` from live state at upload time
(`shareTargets`, `SyncService.kt:216`). An event created while the account was unpaired is
uploaded with an audience of one uid and marked synced. Nothing ever clears that flag again, so
the audience is never recomputed and the co-parent cannot read the document.

Half of this is already fixed, by accident. `EventDao.reslotOwner` (`EventDao.kt:184`) sets
`syncedToFirestore = 0` alongside the slot re-stamp, and its own KDoc names the side effect:
*"is also what finally delivers pre-pairing events to the co-parent at all."* But that statement
only runs when this device's slot actually moves, and `PairingViewModel.kt:207` records the rule:
**"the inviter keeps its slot"**. `ParentSlotMigrator.reslot` returns 0 on `from == to`
(`ParentSlotMigrator.kt:67`).

So the parent who *created* the invitation keeps every pre-pairing event to themselves,
permanently — including everything imported from Google Calendar, which is how the defect was
reported. The accepter's history crosses; the inviter's does not.

Consistent with the Samsung's database: two events, both authored by the co-parent's uid, none of
its own.

### The change

A one-shot audience backfill, keyed by partner, run from the sync path rather than from pairing.

- New key `PreferenceKeys.EVENT_AUDIENCE_BACKFILL_PREFIX`, per user uid, storing the partner uid
  the backfill last ran for.
- New `EventDao.markOwnEventsUnsynced(myUid)`:
  `UPDATE events SET syncedToFirestore = 0 WHERE createdByFirebaseUid = :myUid AND isPrivate = 0`.
  Private events are excluded in the statement, not downstream, so a private event is never
  queued for upload even momentarily.
- In `performFullSync`, before `syncEvents`: if `partnerId` is non-blank and differs from the
  stored marker, run the statement, then advance the marker. The existing upload half does the
  rest, because it recomputes `sharedWith` for every row it uploads.

**Why not a hook on pairing.** A hook only helps pairs formed after the fix ships; this repairs
the pair that exists now, on both phones, without unpairing — and unpairing was ruled out for this
round. It also covers the accepter's path without depending on a slot having moved, so the two
sides stop differing for a reason no product rule justifies.

Marker semantics follow `ParentSlotMigrator`'s precedent exactly: per-uid key, advanced after the
write it guards, idempotent on re-run, and re-armed by a *different* partner uid so re-pairing to
someone else backfills again.

### Residual, disclosed

The marker advances after the Room `UPDATE` commits but before the uploads finish. A process death
in between leaves rows flagged unsynced with the marker already advanced — harmless, because
`getUnsyncedEvents()` picks them up on the next pass anyway; the marker only guards the *flagging*,
not the upload. The opposite ordering would re-flag every event on every sync.

`EventEntity.createdByFirebaseUid` is nullable (`EventEntity.kt:47`), and `= :myUid` does not match
`NULL`. A row old enough to predate that column being stamped is therefore not backfilled by this
change and stays invisible to the co-parent. It is left alone on purpose: nothing distinguishes
"my un-stamped event" from "an un-stamped event that arrived from someone else", and a statement
that guessed would publish the wrong person's history. `EventDao.reslotOwner` scopes on the same
column for the same reason.

Scope decision taken this round: **everything except private events is shared**, Google imports
included. That is what the upload already does for post-pairing events; this change only makes
pre-pairing events behave the same way. No per-source sharing toggle.

---

## 3. Testing

Unit tests, in the style already in the repo:

- `MonthViewWeekendTest` / a pure helper: the base fill is grey for Saturday and Sunday in every
  `DayPosition`, and surface otherwise; the overlay is unchanged for custody and holiday.
  Extracting the decision into a testable function (as `CustodyChangeAnnouncement` did for the
  banner) is preferred over asserting on a composition.
- `EventDaoTest`: `markOwnEventsUnsynced` touches only this uid's rows and never a private one.
- `SyncServiceTest`: backfill runs once for a partner, does not run again on the next sync, runs
  again for a different partner uid, and does not run at all when unpaired.

Device verification, on the existing pair, no unpairing:

- Weekend band visible in all six grid rows, light and dark, with and without a custody model, in
  month and week views.
- After installing the build on both phones and letting one sync run, each phone shows the other's
  pre-pairing events. This is also the acceptance test for the reported Google-calendar symptom,
  once Google Calendar is signed in on the Samsung — it is currently signed out, so there is
  nothing to import there yet.

`detekt` is already red on `main` with pre-existing issues; only this branch's delta is in scope.

---

## 4. Out of scope

- Custody propose/accept/decline with a push notification. Separate spec; the existing behaviour
  is last-write-wins with an after-the-fact banner (`CustodyChangeAnnouncement`).
- Any Firestore rules deploy. The owner deploys rules.
- Any change to which Google calendars are imported, or a per-source sharing toggle.
