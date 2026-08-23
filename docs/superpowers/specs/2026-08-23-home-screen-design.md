# A home screen that says one thing at a time — design

**Date:** 23 August 2026
**Package:** **E** of the nineteen-item improvement list
**Base:** `main` @ `14e00cbe`
**Depends on:** package **B1** (PR #49) for the contacts data. See §5 on package **C**.

Three items:

- **Item 8.** While there is no co-parent, the home screen offers **only** "connect your co-parent" — nothing else. And until the co-parent has agreed the schedule, their days are drawn translucent.
- **Item 13.** Once paired, the home screen leads with the child's events this week; important ones carry an exclamation mark, flagged when the event is created, and mean the co-parent is expected. Expenses stay at the bottom. The weekly summary goes.
- **Item 16.** Important phone numbers — grandparents, doctors, a friend's parents — behind one button, for looking up in a hurry.

---

## 0. Decisions taken without the owner present

| # | Question | Taken | Cost to flip |
|---|---|---|---|
| 1 | Does "only the CTA" hide the gear and the bottom bar too? | **No.** Settings and the tabs stay; the home *content* is the CTA alone. | Small. |
| 2 | What does "important" mean on an event? | **One boolean, set at creation, meaning "the co-parent is expected".** | Small. |
| 3 | Where do contacts live? | **`ChildInfo.emergencyContacts`, which B1 already shares** — widened in meaning, not duplicated. | Moderate. |
| 4 | Does the weekly summary screen get deleted? | **Yes**, with its ViewModel. The button was its only entry point. | Small, and reversible in git. |
| 5 | Which "week"? | **The next seven days from today**, not Monday-to-Sunday. | Small. |

## 1. Item 8, first half — an unpaired home says one thing

Today an unpaired account still sees the handover hero, the two stat tiles, the week's events and the recent-changes list. Almost all of it is empty or meaningless: there is no handover without a second parent, no balance to settle, and no changes for a co-parent to have made. The screen is a set of hollow shells arranged around a small card that says the thing that would fill them.

Unpaired, the content becomes: a short explanation, and one button.

**The gear and the bottom bar stay.** The item says the *page* should contain only the invitation, and it is right about the page. Removing navigation would also strand the user: the calendar and the child's details are still worth having alone, and Settings is where pairing's own screen lives.

## 2. Item 8, second half — an unconfirmed schedule is translucent

> *Пока второй родитель не подтвердил расписание то его дни должны быть прозрачными (розовыми или синими)*

PR #47 gave custody a proposal state: a save while paired writes a `proposal` onto the shared document and changes nobody's pattern until the co-parent accepts. So "not yet confirmed" is a state the data already has — the calendar simply does not draw it.

A day belonging to a **pending proposal** is drawn in the parent's hue at a lower alpha than an agreed day. Same colour, less of it: the meaning is "this is what it would become", and a different colour would read as a different parent.

**The agreed pattern keeps its full strength underneath.** A pending proposal is a preview, not a replacement, and a grid that showed only the proposal would tell a parent their days had changed when they had not.

This is calendar drawing, and package **C** owns `DayCellFills`. §5 says who does it.

## 3. Item 13 — what a paired home leads with

Order, top to bottom:

1. **The next handover** — kept. It answers the question a separated parent opens the app for.
2. **The child's week** — the next seven days of events, each with the parent whose day it falls on, and an exclamation mark on the important ones. This becomes the screen's centre of gravity, where a timeline rail already sits.
3. **Recent changes** — kept, and now largely redundant once package D announces changes in chat. Not removed here; removing it belongs with D, which replaces its job.
4. **Contacts** — item 16's button.
5. **This month's spend** — moved to the bottom, as the item asks.
6. **The weekly summary button — removed**, with the screen and ViewModel behind it.

### "Important" is one boolean, and it means something specific

`Event` gains `isImportant: Boolean = false`, set on the event form, defaulting false so nothing existing changes.

It renders as an exclamation mark next to the title on the home timeline and in the calendar's day agenda. The item also says *«и второй родитель обязателен»* — the co-parent is expected — so the mark's accessible description says that, and the event form's helper text says it when the switch is on. It is a **statement of expectation, not an obligation the app enforces**: nothing blocks saving, nothing chases the other parent. Flagging an event important and having the app refuse to save it would be a worse product than a clear label.

If a stronger reading is wanted — the co-parent must confirm attendance — that is package **D**'s acceptance machinery pointed at a different question, and should be built there rather than duplicated here.

### Deleting the weekly summary

The August refresh made the home button the summary's single entry point, on purpose. Removing the button therefore removes the last way in, so the screen and its ViewModel go with it rather than becoming unreachable code — which is the category CLAUDE.md already has too much of.

## 4. Item 16 — contacts worth finding in a hurry

B1 put `EmergencyContact(name, relationship, phone, alternatePhone)` on the child's record, shared with the co-parent and editable by both. Item 16 wants grandparents, doctors and friends' parents — the same shape, a wider cast.

**Widen the meaning rather than adding a second list.** A doctor is an emergency contact by any reasonable reading, and two parallel lists would mean a parent must guess which one they put grandma in. `relationship` is already free text and already carries "grandmother" or "paediatrician" perfectly well.

A **Contacts** button on the home screen opens a list: name, relationship, and a tap that dials. Dialling is the point — this is the screen someone opens with a hurt child in the other arm.

**One tap to call, via `Intent.ACTION_DIAL`.** Not `ACTION_CALL`: `DIAL` opens the dialler pre-filled and needs no permission, while `CALL` places the call immediately and requires `CALL_PHONE`. Asking a separated parent for permission to place calls, to save one tap, is not a trade this app should make.

Edits go to the same place B1 edits them, so there is one list and one editor.

## 5. Who draws the translucent days

Item 8's second half is `DayCellFills` work, and package **C** rewrites that file for the diagonal handover cell and the pending-swap arrows.

**If C is done first,** this package adds one overlay alpha to a file that already has the shape for it — a small change.

**If E is done first,** it establishes the alpha and C builds around it.

Either way the file must end with **one** decision function covering weekend base, custody overlay, holiday, today, handover diagonal, pending swap and pending proposal. Two functions that each know some of the cases is how the weekend band became unreachable before.

## 6. Verification

| Check | How |
|---|---|
| Unpaired content | JVM: the home state exposes the CTA and nothing else while unpaired. |
| The week | JVM: the next seven days from today; recurring occurrences keyed so two do not collide; a private event of the co-parent's is absent. |
| Important | JVM: the flag round-trips through Room and Firestore; `SyncService`'s own map carries it too. |
| Contacts | JVM: the list is the child's `emergencyContacts`; an entry with no phone is not dialable. |
| Summary removal | grep: no reference to the deleted screen, route or strings survives. |
| Locales | grep, five files per key. |
| Build | `assembleDebug testDebugUnitTest lint detekt` |

**Device checks:** unpaired, the home screen shows one card and one button; the Contacts button dials; an important event carries its mark on home and in the day agenda; and — with two devices — a pending custody proposal draws translucent on the parent who has not agreed yet, while the agreed pattern still shows at full strength.

## 7. Deliberately not in E

- **Enforcing "the co-parent is expected".** §3 — that is D's machinery, pointed at a different question.
- **A contacts model of its own.** §4 — one list, widened.
- **Removing the recent-changes feed.** It belongs with package D, which replaces its job.
- **Any change to the handover hero** beyond what package C's override-aware calculator feeds it.
