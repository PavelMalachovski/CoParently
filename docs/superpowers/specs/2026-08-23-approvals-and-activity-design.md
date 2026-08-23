# An event the other parent has to accept, and a chat that says what changed — design

**Date:** 23 August 2026
**Package:** **D** of the nineteen-item improvement list
**Base:** `main` @ `14e00cbe`
**Depends on:** nothing unmerged, but see §6 on package **C**.

Two items:

- **Item 9.** An event created *for* the other parent must be accepted by them before it appears in the calendar.
- **Item 12.** Every change to an event, an expense or a change request is announced to the co-parent in chat, immediately.

---

## 0. Decisions taken without the owner present

| # | Question | Taken | Cost to flip |
|---|---|---|---|
| 1 | Does an unaccepted event show anywhere? | **Not in the grid.** It sits in the inbox for the recipient and in a "waiting" strip for the creator. | Small — one filter. |
| 2 | Which events need acceptance? | **Only those whose `parentOwner` is the other parent** and that a paired account created. | Small. |
| 3 | What does the chat card contain? | **A structured payload the reader renders in their own language** — not a sentence composed by the sender. | Large; it is §3's spine. |
| 4 | Does item 12 announce a parent's own changes back to them? | **No.** Only the co-parent is told. | Small. |
| 5 | Is an announcement retried if chat is down? | **No** — it is queued as an ordinary message and inherits chat's existing delivery. | Would need its own outbox. |

## 1. What exists, and what it is not

**`Event.pickupConfirmedBy` / `pickupConfirmedAt` are live and mean something else.** `EventViewModel` sets them when a parent confirms *they collected the child* for an event; `DayWeekView` renders it. That is a handover receipt after the fact. Item 9 asks for consent **before** the event counts at all. Reusing the field would collapse two different questions into one column and break the display that already depends on it.

**`ChangeRequest` is adjacent but not it.** It proposes a new *time* for an event both parents already have. Item 9 is about the event's existence.

So item 9 needs its own field. `Event` gains:

```kotlin
val acceptance: EventAcceptance = EventAcceptance.NOT_REQUIRED
val acceptedBy: String? = null       // Firebase UID
val acceptedAt: LocalDateTime? = null
```

```
EventAcceptance = NOT_REQUIRED | PENDING | ACCEPTED | DECLINED
```

`NOT_REQUIRED` is the default and covers every event that exists today, every event a parent creates for themselves, and every event created while unpaired. That default is what makes the Room migration additive and what stops the change rewriting history.

## 2. Item 9 — where an unaccepted event lives

**Not in the grid, on either phone.** Item 3's wording is *«только потом оно покажется в календаре»*, and a grid that draws unagreed events is the problem the item describes.

But it must be visible somewhere to both, or it is simply lost:

- **The recipient** sees it in the change-request inbox, alongside event time changes and (if package C has landed) day swaps. Accept or decline.
- **The creator** sees it in a "waiting for your co-parent" strip at the top of the event list, so they can tell the difference between "they haven't answered" and "I never created it".

Declining leaves the event `DECLINED` rather than deleting it: the creator needs to know the answer was no, and a silently vanished event reads as a bug.

**`getEventsByDateRange` filters `PENDING` and `DECLINED` out.** That is the single place the grid, the week view and the day view all draw from, and CLAUDE.md already requires range queries to go through it — so one filter covers every view rather than three that can disagree.

**Recurring events accept as a whole, not per occurrence.** Occurrences share the master event's id and are expanded at query time by `RecurrenceExpander`; there is no per-occurrence row to carry a status. Accepting "football every Tuesday" accepts the series, which is also what a parent means.

## 3. Item 12 — why the card must not be a sentence

`RequestChangeViewModel.postChatMessage` builds its card like this today:

```kotlin
append("🔁 Change requested for \"${event.title}\" → ")
```

Hardcoded English, composed inside a ViewModel. CLAUDE.md tracks exactly this class of defect and forbids the usual workaround of injecting a `Context`.

Generalising that shape to every event, expense and change request would fill the chat with English on a Russian phone. And translating it at the sender would still be wrong, for a reason worth stating plainly: **the two parents may not read the same language.** A card composed in the sender's locale is a card the recipient may not be able to read. The sender's language is not a property of the message.

So an announcement carries **facts, not prose**:

```
messageType = ACTIVITY
attachments = [<entityType>, <entityId>]
content     = <a plain-text fallback, sender's locale>
activity    = { kind, entityType, entityId, title, whenIso?, amount?, currency? }
```

The reader's device renders `activity` through `stringResource` in **its own** language. `content` exists only so that a co-parent on an older build, whose app knows nothing about `ACTIVITY`, still sees something rather than an empty bubble.

**That fallback works because the read path already degrades safely.** `ChatMappers` parses the type with `runCatching { MessageType.valueOf(...) }.getOrDefault(MessageType.TEXT)`, so an unknown value becomes a plain text message carrying `content`. A new enum member is forward-compatible by construction — verified in the mapper, not assumed.

### What is announced

| Change | Announced |
|---|---|
| Event created, edited, deleted | yes |
| Event acceptance decided | yes |
| Change request raised or decided | yes — replacing today's bespoke card |
| Expense added, edited, deleted | yes |
| Budget changed | no — a budget is a private planning tool, not a shared fact |
| A parent's own profile or child info | no — package B1 syncs those; a chat card per medical edit is noise |

**Only the co-parent is told.** A card announcing your own change back to you is clutter in the one place both parents look.

**Private events are never announced.** `isPrivate` events never reach Firestore at all; announcing one would leak through a channel the sync path is careful to close.

## 4. One announcer, called from the write paths

A single domain component turns a change into a message:

```kotlin
ActivityAnnouncer.announce(kind, entity, toPartnerUid)
```

Called from `EventRepositoryImpl`, `ExpenseRepositoryImpl` and the change-request path — **not** from each ViewModel. Repositories are where a change becomes durable, and a card posted from a ViewModel is a card that does not appear when the same change arrives from sync, an undo, or a future screen.

It must never break its caller. Chat delivery is best-effort: an announcement that fails is logged and dropped, exactly as `savePassword` in package A must not be able to fail a successful sign-in. A parent's expense must not fail to save because their co-parent's chat listener is down.

## 5. Rules, functions and migration

**Rules:** `messages` and `conversations` already allow both participants to write. `activity` is one more field on a message the sender owns; the existing `create` validation needs to permit it and nothing more. `events` needs no new rule — acceptance fields live on a document the audience already reads, and the writer is already constrained.

Worth pinning in the emulator: **the recipient may write the acceptance fields on an event they did not create.** The current update rule requires `createdByFirebaseUid` to be unchanged, which permits it — but it is exactly the sort of thing that fails once the fields exist, and finding out on a phone is what CLAUDE.md forbids.

**Functions:** none. `onEventCreated` already queues a push; whether an acceptance request should also push is a product decision left out of this package.

**Migration:** three columns on `events`, all nullable or defaulted — additive.

## 6. Interaction with package C

If C ships first, it will have added a bespoke chat card for day swaps, mirroring the change-request one. This package **replaces** both with `ActivityAnnouncer`. If D ships first, C's plan says to use D's mechanism instead of adding a second.

Whichever order they land in, there must be exactly one way a change reaches chat when both are done.

## 7. Verification

| Check | How |
|---|---|
| Acceptance transitions | JVM: only the recipient may decide; a decided event cannot be re-decided; the creator may cancel. |
| Grid filtering | JVM: `PENDING` and `DECLINED` are absent from a range query; `NOT_REQUIRED` and `ACCEPTED` are present; recurring occurrences follow their master. |
| Announcement payload | JVM: the payload names the entity and the kind; a private event produces none; a parent's own change produces none. |
| Reader rendering | JVM: every `kind` maps to a distinct string resource, exhaustively, with no `else`. |
| Rules | emulator — §5's acceptance-write case. |
| Migration | instrumented, on the device. |
| Locales | grep, five files per key. |

**Two devices, in two languages.** Set phone A to Russian and phone B to English. Create an event for B from A. B's inbox shows it; B's chat card is in **English**; A's chat card for the same change is in **Russian**. Accept on B; the event appears in both grids. That last check is the whole point of §3 and cannot be verified any other way.

## 8. Deliberately not in D

- **Push notifications for acceptance.** `onEventCreated` exists; whether an acceptance request should also buzz a phone is a separate product decision.
- **Retrying a failed announcement.** It inherits chat's existing delivery, including the known non-retrying listener CLAUDE.md records.
- **Announcing profile or child-info edits.** B1 syncs those; a card per medical field would drown the thread.
- **Per-occurrence acceptance of a recurring event** — §2.
