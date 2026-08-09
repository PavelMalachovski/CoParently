# Full UX/UI test plan — August 2026

Two-device acceptance pass over the whole app, on `main` after the August 2026 design refresh
(`ebb45390`). It covers what the app does, how it feels to use, and what should change.

This plan is meant to be executed and marked up in place. Fill the **Result** column as you go
and add anything that surprises you to the findings table in §13 — a plan that only records
pass/fail throws away the part that matters most.

---

## How to read a step

| Mark | Meaning |
|---|---|
| `[A]` | Verifiable from a screenshot, a log line or a database query. Can be driven over ADB. |
| `[H]` | Hands only. Gesture inertia, scroll feel, haptics, animation timing, perceived latency. No screenshot settles these. |
| `[2]` | Needs both phones at once. |

Severity for anything that fails:

| | |
|---|---|
| **Blocker** | Data loss, crash, or a co-parent cannot complete the core job. |
| **High** | A feature is unusable or silently wrong; user would reasonably uninstall. |
| **Medium** | Works, but the user has to think or retry. |
| **Low** | Noticeable rough edge, no functional cost. |
| **Polish** | Spacing, wording, timing. |

## Devices and roles

| | Phone | Account | State at start |
|---|---|---|---|
| **Parent A** | Samsung SM-A176B | `pavel.malakhouski@gmail.com` | **Fresh install.** App uninstalled first. |
| **Parent B** | Pixel 9 Pro XL | `p.malakhouski@gmail.com` | Existing install, existing data, already paired before the unpair in §2. |

Every step below is written against the **roles**, not the phones, so swapping which handset plays
which part changes only this table.

Both are on wireless ADB. Before any scripted tap, confirm `mCurrentFocus` is CoPlanly —
a tap into whatever else has focus is not a test result.

## §0 Setup

| # | Step | Expected | Mark | Result |
|---|---|---|---|---|
| 0.1 | Record the build: `git rev-parse --short HEAD` and the APK's build time | Both written into §13 header | `[A]` | |
| 0.2 | `./gradlew clean assembleDebug` | Builds | `[A]` | |
| 0.3 | Uninstall on Parent A: `adb -s <A> uninstall com.coparently.app` | Gone | `[A]` | |
| 0.4 | Install the same APK on both phones | Same version on both | `[A]` | |
| 0.5 | Set both phones to the same language and time zone to start | Baseline is comparable | `[A]` | |

Do **not** start `adb logcat` filtering only for crashes — capture the whole app tag for the
session (`adb logcat -v time | grep -i coparently`). Half the findings in a UX pass are warnings
nobody would have gone looking for.

---

## §1 Onboarding and sign-in — Parent A, fresh install

| # | Step | Expected | Mark | Result |
|---|---|---|---|---|
| 1.1 | Launch for the first time | Splash, then the auth screen. No crash on a database that does not exist yet | `[A]` | |
| 1.2 | Look at the auth screen before touching it | It says what the app is for. A parent who was sent a link knows what they are signing into | `[H]` | |
| 1.3 | Sign in with Google | Account chooser, then Home | `[A]` | |
| 1.4 | Cancel the Google chooser instead of picking | Returns to auth with a usable message, not a dead spinner | `[A]` | |
| 1.5 | Deny the notification permission if asked | App continues. Permission is **not** requested on cold start — only from the push toggle or a reminder | `[A]` | |
| 1.6 | Home on a brand-new account | Empty states read as guidance, not as breakage. Pairing call to action is visible | `[H]` | |
| 1.7 | Rotate the phone on Home | State survives | `[A]` | |
| 1.8 | Kill and relaunch | Still signed in | `[A]` | |

**Impressions prompt.** You are a parent who just separated and someone recommended this app.
After 60 seconds, do you know what to do next? Write the first sentence that came to mind.

---

## §2 Pairing — both phones

The unpaired pairing screen has **never been seen running**. It is the only redesigned screen with
no device evidence at all. Treat this section as the highest-value part of the pass.

| # | Step | Expected | Mark | Result |
|---|---|---|---|---|
| 2.1 | On Parent B, Settings → Co-Parent Pairing → Unpair, confirm | Unpairs. Red row, confirmation dialog | `[A]` `[2]` | |
| 2.2 | Both phones now show the unpaired pairing screen | Two explicit modes: "Share my code" / "Enter a code" | `[A]` | |
| 2.3 | Read the mode toggle | It is obvious which side you want. Nobody would type their own code back in | `[H]` | |
| 2.4 | Look at the invite code container | Dashed border, tap-to-copy affordance, validity countdown | `[A]` | |
| 2.5 | Tap the code | Copies, with confirmation | `[A]` | |
| 2.6 | Check the QR renders inline, not behind a dialog | Visible without another tap | `[A]` | |
| 2.7 | Read the trust panel | States what pairing shares and that private events never leave the device | `[A]` | |
| 2.8 | Parent B scans Parent A's QR | Camera permission asked in context; pairing completes | `[A]` `[2]` | |
| 2.9 | Repeat with the typed code path instead (unpair again) | Same result | `[A]` `[2]` | |
| 2.10 | Enter a wrong / expired code | Clear error, not a silent no-op | `[A]` | |
| 2.11 | Regenerate the code, then try the old one | Old one refused | `[A]` | |
| 2.12 | Send an email invite | Mail app opens with a sensible body | `[A]` | |
| 2.13 | After pairing, both Settings show the co-parent | Name, address, "paired since" | `[A]` `[2]` | |
| 2.14 | Watch the first sync | Events, expenses and conversations arrive on Parent A | `[A]` `[2]` | |
| 2.15 | Time the first sync | How long until the phone looks correct? Anything over a few seconds needs a visible state | `[H]` `[2]` | |

**Known-issue probe (see §12.1).** Parent A is a first launch after install. Watch logcat during
2.14 for a `PERMISSION_DENIED` on the chat listeners. If it appears, the thread runs on local data
for the whole process and looks perfectly healthy while receiving nothing.

**Impressions prompt.** Pairing is the moment a parent decides whether to trust the app with a
custody schedule. Did the trust panel answer the question you actually had?

### §2 run — 9 August 2026

**Build** `df593234` (`main`, after PR #46 merged), `app-debug.apk` built 09:03 CEST and installed
on both handsets. Firestore rules deployed by the owner before this run. **Parent A** = Samsung
SM-A176B (`pavel.malakhouski@gmail.com`, uid `azJHH…`), **Parent B** = Pixel 9 Pro XL
(`p.malakhouski@gmail.com`, uid `F7wE4…`). Both were reconnected over wireless ADB via mDNS
mid-run — the Samsung refused its advertised port for ~40 minutes and only returned when it began
advertising a second one.

Scope: `[A]` and `[2]` only, as instructed. `[H]` rows are untouched and stay open. **2.1 was
performed by the owner outside this run**, so the unpaired screen was already the starting state.
Parent A was *not* a fresh install, so the §12.1 chat-listener reproduction window did not apply.

| # | Result |
|---|---|
| 2.1 | **Done by owner, not observed.** Parent A's Room still held `partnerId` pointing at B when this run started — the unpair was issued from B and A had not synced since. It cleared on A's next sync, so this is lag, not loss; noted as F-04. |
| 2.2 | **Pass**, on both. Two explicit modes as a segmented toggle: "Мой код" / "Ввести код". |
| 2.3 | `[H]` — not run. But see F-02: typing your own code back in is refused with a specific, localised message, which is the failure mode this row worries about. |
| 2.4 | **Pass.** Dashed-border container, tap-to-copy caption and a live countdown ("Действителен ещё 23 ч 49 мин") in one line under the code. |
| 2.5 | **Pass.** Tapping copies. The confirmation observed is Android 13+'s own clipboard chip, which usefully shows the copied value (`RKBECJ`) — see F-05 for what this means below Android 13. |
| 2.6 | **Pass.** QR renders inline under the code, no extra tap. |
| 2.7 | **Pass.** "Что становится общим" states the shared surfaces, that private events never leave the device, and that the link can be broken at any time. In "Мой код" it sits **below the fold**, under the QR (F-06); in "Ввести код" it is visible without scrolling. |
| 2.8 | **Not runnable headless.** Scanning needs one handset's camera physically aimed at the other's screen. Still open. |
| 2.9 | **Pass.** A generated `UWEVMK`; B typed it and paired. Both phones moved to the paired state within ~14 s. |
| 2.10 | **Pass.** `ZZZZZZ` → "Приглашение с таким кодом не найдено", field outlined in the error colour. Not a silent no-op. |
| 2.11 | **Pass, and provable on one device.** Regenerating replaced `RKBECJ` with `FRUVF7`, reset the countdown to 23 ч 59 мин and redrew the QR. Re-entering the superseded code then returned "**не найдено**" rather than "это ваше собственное приглашение" — since the self-check is what fires for an invitation that exists and is yours, lookup failing first proves the old code was genuinely revoked, not merely superseded. This row does not need the second phone. |
| 2.12 | **Expectation does not match the implementation.** "По эл. почте" does not hand off to a mail app; it expands an inline "Эл. почта партнёра" field plus a "Пригласить по email" button, and the mail is sent server-side by the `sendEmailInvitation` trigger. The affordance was verified; **sending was deliberately not exercised** — it would email a real person. The plan's expected column should be rewritten, not the app. |
| 2.13 | **Pass**, on both. A shows "Pavel · p.malakhouski@gmail.com · Связаны с 9 авг. 2026 г."; B shows the same for A. Unpair is a red row, per the destructive-action anatomy. |
| 2.14 | **Partial pass.** B's Home left the unpaired state and filled its activity list with the co-parent's changes. **Nothing new could arrive**, because both accounts already held identical local data from before the unpair — Room survives unpairing — so this run cannot distinguish "sync delivered it" from "it was never gone". See F-01: the mechanism that should have re-shared it did not run. |
| 2.15 | `[H]` — not run. |

**Known-issue probe (§12.1).** Not applicable: Parent A was not a fresh install. No
`PERMISSION_DENIED` appeared on either handset during pairing — the first run in which that is
true, because the `custody_models` `allow get` defect (fixed in PR #46) had been denying every
custody read until this build.

---

## §3 Home

| # | Step | Expected | Mark | Result |
|---|---|---|---|---|
| 3.1 | Open Home with a custody model set | Handover hero card: who has the child now, when the next handover is | `[A]` | |
| 3.2 | Tap the hero's action | Opens the change-request inbox | `[A]` | |
| 3.3 | Tap the spend tile | Goes to Expenses **and** the bottom bar highlights Expenses | `[A]` | |
| 3.4 | Tap the unread tile | Goes to Chat, bottom bar follows | `[A]` | |
| 3.5 | Read everything with zero unread | The unread tile collapses; spend tile takes the width | `[A]` | |
| 3.6 | Check the spend line states the settle-up position | Not just a total — who owes whom | `[A]` | |
| 3.7 | With two currencies this month | Per-currency subtotals joined, never one wrong sum | `[A]` | |
| 3.8 | "This week" list | Upcoming events with parent colour and day | `[A]` | |
| 3.9 | Activity list with a pending change request | Inline **Review** action works | `[A]` | |
| 3.10 | "View weekly summary" button | Opens the weekly summary; it is the only route to it | `[A]` | |
| 3.11 | Gear icon | Settings opens as a detail screen: up arrow, no bottom bar | `[A]` | |
| 3.12 | Scroll Home | Nothing clips under the bottom bar | `[H]` | |

**Impressions prompt.** If you opened this screen once a day, would it save you a message to your
co-parent? Which tile did your eye go to first?

---

## §4 Calendar

### 4.1 Month view

| # | Step | Expected | Mark | Result |
|---|---|---|---|---|
| 4.1.1 | Header is one row | Title-as-picker, Today, Filters, gear | `[A]` | |
| 4.1.2 | Tap the title | Month / Week / Day menu | `[A]` | |
| 4.1.3 | Tap a day with events | Day is selected, agenda card below fills. Does **not** jump to Day view | `[A]` | |
| 4.1.4 | Tap a day with no events | Agenda card says so | `[A]` | |
| 4.1.5 | Custody colouring with an active custody model | Day background is the parent hue at low alpha; run-start edge bar | `[A]` | |
| 4.1.6 | Custody colouring with only a legacy schedule | Still coloured — the unified lookup must not fall through | `[A]` | |
| 4.1.7 | Czech public holidays | Marked and legible in both themes | `[A]` | |
| 4.1.8 | School holidays | One month-level banner, never a strip under every cell | `[A]` | |
| 4.1.9 | A day with more events than fit | Dots plus an overflow marker; titles live in the agenda card | `[A]` | |
| 4.1.10 | Pending change requests | Inline banner with a Review action | `[A]` | |
| 4.1.11 | Today pill | Returns to today from any month | `[A]` | |
| 4.1.12 | Filters chip | Filled when filters are active, outlined when not | `[A]` | |
| 4.1.13 | Filter by parent and by event type | Grid and agenda both respect it | `[A]` | |
| 4.1.14 | Weekend tint with **no** custody model | The dark-theme weekend fill is visible. Confirm it still reads acceptably — it is deliberately kept | `[H]` | |

### 4.2 Gestures — the swipe complaint

| # | Step | Expected | Mark | Result |
|---|---|---|---|---|
| 4.2.1 | Swipe **left to right** repeatedly, slowly counting | Every month transition takes the same time | `[H]` | |
| 4.2.2 | Swipe **right to left** repeatedly | Same duration as 4.2.1 | `[H]` | |
| 4.2.3 | Compare the two directions back to back | Reported defect: first right-swipe is slow, the next is fast; left swipes are all fast. Confirm and describe precisely | `[H]` | |
| 4.2.4 | Fling hard vs. drag slowly and release | Both settle on one month; no double-jump | `[H]` | |
| 4.2.5 | Swipe during the settle animation | No месяц skipped, no stuck state | `[H]` | |
| 4.2.6 | Week and Day view paging | Same physics as month | `[H]` | |

### 4.3 Events

| # | Step | Expected | Mark | Result |
|---|---|---|---|---|
| 4.3.1 | Tap an event | Preview bottom sheet with details and Edit / Delete | `[A]` | |
| 4.3.2 | Edit an event, change only the title, save | Everything else preserved — sharing, permissions, creator. Verify in the database, not by eye | `[A]` | |
| 4.3.3 | Create an all-day event | Renders correctly in month, week and day | `[A]` | |
| 4.3.4 | Create an overnight event (22:00–02:00) | Appears on **both** days | `[A]` | |
| 4.3.5 | Create a multi-day event | Appears on every day it spans | `[A]` | |
| 4.3.6 | Create a recurring event | Occurrences expand; editing the master behaves sanely | `[A]` | |
| 4.3.7 | Set a reminder, then delete the event | Reminder is cancelled, not orphaned | `[A]` | |
| 4.3.8 | Mark an event private | Never reaches Firestore. Confirm it does not appear on Parent B | `[A]` `[2]` | |
| 4.3.9 | Attach a photo to an event | Uploads, renders, survives a restart | `[A]` | |
| 4.3.10 | Event list screen: swipe a row to delete | Undo snackbar restores it with the same id | `[A]` | |
| 4.3.11 | Create an event on A | Appears on B without a manual refresh | `[A]` `[2]` | |

**Impressions prompt.** Look at a month with a real custody pattern. Can you answer "who has them
on the 19th" without touching anything?

---

## §5 Chat — both phones

| # | Step | Expected | Mark | Result |
|---|---|---|---|---|
| 5.1 | Open the Chat tab with exactly one conversation | Thread opens in place. Bottom bar stays. Back does not bounce | `[A]` | |
| 5.2 | Header | Co-parent initial, name, status line | `[A]` | |
| 5.3 | Open the thread with many messages | **Reported defect:** it lands somewhere in the middle. It should open at the newest message | `[A]` | |
| 5.4 | Send from A | Appears on B within seconds | `[A]` `[2]` | |
| 5.5 | Tick progression on A | Sending → sent → delivered → read as B receives and opens | `[A]` `[2]` | |
| 5.6 | Unread badge on the Chat tab | Counts B's unread messages, clears on open | `[A]` `[2]` | |
| 5.7 | Day separators | Correct, and consistent with the times printed under bubbles | `[A]` | |
| 5.8 | Consecutive messages from one sender | Grouped, timestamp outside the bubble | `[A]` | |
| 5.9 | "Templates" chip | **Reported defect:** picking a template sends immediately. It should fill the composer for editing | `[A]` | |
| 5.10 | "Request change" chip | Event picker, then a structured change request in the thread | `[A]` | |
| 5.11 | The change-request card in the thread | **Reported gap:** no way to open the request itself from the message | `[A]` | |
| 5.12 | Send with no connection | Queues, shows sending, delivers on reconnect | `[A]` | |
| 5.13 | Long message, emoji, newlines | Bubble wraps; nothing clipped | `[A]` | |
| 5.14 | Scroll a long thread | Smooth, no jump when a new message arrives while scrolled up | `[H]` | |
| 5.15 | Send while the other phone has the thread open | Read mark is immediate | `[A]` `[2]` | |

**Time-zone scenario — this has never been run.** Set Parent A to a zone 2–3 hours from Parent B.
Send from B. On A, confirm: it counts as unread, the badge clears on open, ticks reach READ, the
printed time matches the day separator, and neither phone shows a message "in the future".

**Impressions prompt.** Chat between separated parents is often the tensest surface in the product.
Does anything here escalate rather than defuse?

---

## §6 Expenses

| # | Step | Expected | Mark | Result |
|---|---|---|---|---|
| 6.1 | Summary card | Month switcher inside it, shared total, settle-up strip with a filled action | `[A]` | |
| 6.2 | Month navigation by button | Moves one month, count updates | `[A]` | |
| 6.3 | Month navigation by swipe | **Reported gap:** no swipe today, buttons only | `[H]` | |
| 6.4 | A month with no expenses but others populated | Switcher stays reachable — not a dead end | `[A]` | |
| 6.5 | A month with two currencies | One summary card per currency; no cross-currency total anywhere | `[A]` | |
| 6.6 | Add an expense, split evenly | Balance and settle-up update on both phones | `[A]` `[2]` | |
| 6.7 | Scan a receipt | OCR runs **on device**; amount pre-filled; nothing sent to a remote service | `[A]` | |
| 6.8 | Receipt photo thumbnail | Opens the full-screen viewer; tap does not leak to the editor | `[A]` | |
| 6.9 | Expense dated in a past month | Visible after switching to that month | `[A]` | |
| 6.10 | Swipe a row to delete | Undo snackbar; row restored | `[A]` | |
| 6.11 | Settle up | Drafts a message to chat; never sends by itself | `[A]` | |
| 6.12 | Budget chips | Progress per category, correct currency | `[A]` | |
| 6.13 | Budgets screen from the top bar | Create, edit, delete; alert threshold respected | `[A]` | |
| 6.14 | Budget created before the owner-field fix (§12.3) | Confirm whether legacy budgets are missing from a reinstall | `[A]` | |
| 6.15 | Row corners | No error colour bleeding around rows at rest | `[A]` | |

**Impressions prompt.** Money is the second-most argued surface. Is "who owes whom" stated plainly
enough that neither parent has to compute anything?

---

## §7 Change requests, end to end

| # | Step | Expected | Mark | Result |
|---|---|---|---|---|
| 7.1 | From A, request a change to an event owned by B | B gets it: Home activity, calendar banner, chat card | `[A]` `[2]` | |
| 7.2 | B accepts | Event moves on both phones; A is told | `[A]` `[2]` | |
| 7.3 | B declines with a note | A sees the decline and the note | `[A]` `[2]` | |
| 7.4 | A cancels a pending request | Disappears from B | `[A]` `[2]` | |
| 7.5 | Change-requests inbox | Pending, accepted, declined, cancelled all render | `[A]` | |
| 7.6 | Request a change on a recurring event | Behaviour is defined and explained, not silently odd | `[A]` | |
| 7.7 | Two requests for the same event | No corruption; the second is handled sensibly | `[A]` `[2]` | |

---

## §8 Settings and its detail screens

| # | Step | Expected | Mark | Result |
|---|---|---|---|---|
| 8.1 | Four groups in order: Family, Sync, App, Account | Family first | `[A]` | |
| 8.2 | Every row has at most one trailing control | Google Calendar's toggle plus expander is the one deliberate exception — judge whether it reads as one row | `[H]` | |
| 8.3 | Co-Parent Pairing row | Opens pairing | `[A]` | |
| 8.4 | Child Information | Create, edit, delete a child; medications, activities, allergies, emergency contacts | `[A]` | |
| 8.5 | Custody Schedule | Set week-on/week-off, 2-2-3 and a custom pattern; calendar reflects each | `[A]` | |
| 8.6 | Co-Parent Sync row | Manual refresh works; status wording is truthful | `[A]` | |
| 8.7 | Google Calendar row | Expands on tap; sign-in completes; events flow both ways; sign-out is clean | `[A]` | |
| 8.8 | Theme: System / Light / Dark | Applies immediately, survives restart | `[A]` | |
| 8.9 | Language | Switches immediately; survives restart; mirrors into the Android per-app language setting | `[A]` | |
| 8.10 | Currency | New expenses default to it | `[A]` | |
| 8.11 | Push notifications toggle | Permission requested here, in context | `[A]` | |
| 8.12 | Receive a push while the app is backgrounded | Arrives, opens the right screen | `[A]` `[2]` | |
| 8.13 | About row | Version matches the build | `[A]` | |
| 8.14 | Sign out | Red row, confirms, returns to auth, local data no longer readable by the next account | `[A]` | |

---

## §9 Weekly summary

| # | Step | Expected | Mark | Result |
|---|---|---|---|---|
| 9.1 | Open from Home | Renders this week: events, custody split, spend | `[A]` | |
| 9.2 | An empty week | Says so; not a blank screen | `[A]` | |
| 9.3 | Confirm there is exactly one entry point | No second route from the calendar header | `[A]` | |

---

## §10 Cross-cutting

| # | Step | Expected | Mark | Result |
|---|---|---|---|---|
| 10.1 | Airplane mode: browse calendar, chat, expenses | Everything readable — Room is the source of truth | `[A]` | |
| 10.2 | Create an event, an expense and a message offline, then reconnect | All three sync; no duplicates | `[A]` `[2]` | |
| 10.3 | Both phones edit the same event offline, then reconnect | Conflict resolution is defined, not last-writer-silently-wins | `[A]` `[2]` | |
| 10.4 | Full pass: English, dark | Baseline | `[H]` | |
| 10.5 | Full pass: Russian, light | Baseline | `[H]` | |
| 10.6 | Spot-check Czech, German, Ukrainian | Truncation in chips, buttons, row titles, tab labels. German is the long one | `[A]` | |
| 10.7 | Any English string left in a translated build | Note it — strings produced inside ViewModels are known to be hardcoded | `[A]` | |
| 10.8 | Rotate on every screen | No state loss, no crash | `[A]` | |
| 10.9 | Predictive back from every detail screen | Correct destination, correct animation | `[H]` | |
| 10.10 | Bottom bar shows only on Home, Calendar, Chat, Expenses | Detail screens hide it and show an up arrow | `[A]` | |
| 10.11 | System font size at maximum | Nothing clipped or overlapping | `[A]` | |
| 10.12 | Talkback on the four main screens | Every actionable control is reachable and named | `[H]` | |
| 10.13 | Kill the app from recents mid-edit | No data loss beyond the unsaved form | `[A]` | |
| 10.14 | Watch logcat for the whole session | Note every warning and exception, even non-fatal | `[A]` | |

---

## §11 Baseline for the nine improvements

Record how each behaves **today**, before any of them is implemented. Without this there is
nothing to compare the fixes against.

The requested improvement 1 is two separable capabilities — a pet is a new subject in the family
record, and attachments are a new capability that could ship for children alone — so it is listed
as 1a and 1b. That makes ten.

| # | Improvement | What to record now |
|---|---|---|
| 1a | Pets as a subject alongside children | What the family record holds today; where a pet would have to live |
| 1b | Upload documents and photos for children **and** pets | What a parent tries to attach and cannot. Note that storage today handles exactly one compressed JPEG per entity — no documents, no multiple files |
| 2 | Share the custody schedule at pairing | After pairing, does the second phone have the custody pattern at all? Today there is no Firestore path for custody whatsoever |
| 3 | Edit the custody schedule and propose it to the co-parent | Editing exists locally; is there any path to the other parent's agreement? |
| 4 | Chat opens at the newest message | Where exactly does it land? Does it depend on thread length? |
| 5 | Link to the change request from the chat card | Is the card inert? |
| 6 | Template prepares a message instead of sending it | Confirm it sends immediately |
| 7 | Month swipe in Expenses | Confirm buttons only |
| 8 | Symmetric month swipe in Calendar | Time both directions; describe the asymmetry precisely (see 4.2.3) |
| 9 | Agenda strip shows today, only in the current month | Confirm paging a month selects the 1st; note how often the strip shows "nothing scheduled" for a day nobody chose |

### Baseline recorded — 3 August 2026

**Run.** Samsung SM-A176B (the Parent A handset), Android 15, dark theme, device language Russian,
density 450, wireless ADB. Build `main` @ `ebb45390`; `app-debug.apk` rebuilt and reinstalled over
the existing install before the run (`assembleDebug` → `adb install -r`), versionName 1.1.0
(versionCode 2). Account state: paired, one child record ("test 2"), one conversation with ~20
messages, expenses in July but none in August, **no active custody model**.

This is a one-phone pass. Every row below is `[A]` — a screenshot, a frame counter or the code
path that produces the behaviour. The one `[H]` question inside item 8 (what the asymmetry *feels*
like, per 4.2.3) is left open and marked as such. Nothing here was unpaired, deleted or reset.

**1a — Pets as a subject.** The family record is Settings → Family → "Информация о ребёнке": a list
of `ChildInfo` rows, one per child. A row holds name, date of birth, medications, activities,
allergies, medical notes, emergency contacts and school info — and nothing else. There is no
species, kind or subject-type field anywhere in the model, and no second subject entity: `ChildInfo`
is the only one. Downstream, only `Expense.childId` and `Budget.childId` point at a subject at all
(both nullable, both unset in this account); `Event` has no link to a child. Sync is
`FirestoreChildInfoDataSource` → collection `child_info`, which has its own rule block.
**Where a pet would have to live today:** in a fake child row — a `ChildInfo` named "Rex" with the
vet stuffed into `medicalNotes` or `schoolInfo`. Nothing in the UI would mark it as anything other
than a child, and the screen title is singular ("Информация о ребёнке").

**1b — Documents and photos.** There is no attach affordance in the child record at all: the view
screen shows name and DOB only, and the editor, scrolled top to bottom, offers Medications /
Activities / Allergies / Medical notes / Emergency contacts / School info and a Save button. A
parent has nothing to tap.

The only binary storage in the app is `FirebaseImageStorage`, implementing `ReceiptStorage` and
`EventImageStorage`. Both write to a path derived from the owning entity's id —
`receipts/{expenseId}.jpg`, `event_images/{eventId}.jpg` — so a second upload **replaces** the
first: exactly one object per entity, by construction. Every upload is decoded as a bitmap,
downscaled to ≤1600 px and re-encoded (`Bitmap.compress(JPEG, 85)`) with `contentType` hardcoded to
`image/jpeg`. A PDF or a Word file cannot pass through that path at all — it is not a bitmap, and it
would fail in `compressImage` before it ever reached Storage. So the improvement is not a widening
of the current mechanism: it needs a per-subject *collection* of objects, the original MIME type
preserved, and no re-encode.

**2 — Custody at pairing.** There is no Firestore path for custody, in either direction.
`CustodyModelRepository` talks only to `CustodyModelDao`; there is no custody data source among the
Firestore sources, and the `custody_schedules` rule block in `firestore.rules` matches no client
code (already recorded in `CLAUDE.md`). Pairing itself (`PairingFunctions` → the
`acceptPairingInvitation` callable) exchanges user documents only. So after pairing, the second
phone has **no** custody pattern: each parent sets one locally, the two are never compared, and
nothing tells either parent that they disagree. Not re-verified by unpairing — that is destructive
and this account is paired; the conclusion is from the code and from the absence of any custody
Firestore source.

**3 — Edit and propose.** Settings → Family → "График опеки" is: four radio cards (Week On/Week
Off, 2-2-3, 3-4-4-3, Custom), a cycle anchor date, and one button, "Сохранить график опеки". Saving
runs `CustodyModelRepository.saveAndActivate`, which deactivates every existing model and inserts
the new one — locally, silently, immediately. The screen never mentions the co-parent, never shows
what the co-parent has, and has no propose/accept step. The only agreement mechanism that exists in
the app is `ChangeRequest`, and it is bound to a single event (`eventId`, `requestedBy`,
`requestedTo`, PENDING/ACCEPTED/DECLINED/CANCELLED) — it cannot carry a custody pattern. (The
option cards are also still untranslated English on a Russian device.)

**4 — Where the chat opens.** At the **oldest** message, not the newest. After a cold start, opening
the Chat tab lands on the "Вчера" day separator and yesterday's first three bubbles; today's ten-odd
messages, including the newest (22:10), are roughly four screens below. Reproduced twice.

It does depend on thread length, and sharply. `MessagesList` has no initial scroll at all. Its only
scroll is `LaunchedEffect(entries.size)`, which animates to the last item **only if** the list is
already near the bottom (`lastVisibleIndex >= total-2 || firstVisibleIndex >= total-3`). On first
composition both indices are 0, so that condition holds only when the thread is ≤3 entries — and day
separators count as entries. Anything longer opens at the top. A short thread therefore looks
correct and hides the defect, which is why it reads as "sometimes fine". Within a session the
landing point is the last scroll offset instead (`rememberLazyListState` is saveable), so switching
tabs and back keeps the bottom — the wrong landing is what you get after launch and after process
death.

The same gap swallows outgoing messages: a message sent while the list is parked at the top
produces no visible change (see 6).

**5 — The change-request card is inert.** Starting a change request from a thread does post a card:
`RequestChangeViewModel.postChatMessage` sends a message with `messageType = EVENT_LINK` and
`attachments = listOf(event.id)`. But `MessagesList.MessageItem` renders `message.content` and
nothing else — it never reads `messageType` or `attachments`, and no bubble carries a click
modifier. On device, three taps (single, then double) on 🔁 `Change requested for "Работа" → po,
srp 3 · 18:30 – 19:50` did nothing: no navigation, no ripple, no state change, screen pixel-identical.

Two things to know before wiring the link: the card carries the **event** id, not the change-request
id, so the target has to be looked up by event (or the payload changed; note an event can have more
than one request over its life). And the card's text is composed on the *sender's* device from a
hardcoded English prefix plus a locale-formatted date, then stored — which is why a Russian-locale
phone is showing an English sentence with a Czech date in it.

**6 — Templates send immediately.** Confirmed, and worse than "no preview". `onTemplateSelected`
calls `viewModel.sendTemplateMessage(template, template.content)` and closes the sheet;
`sendTemplateMessage` delegates straight to `sendMessage`. No preview, no edit, no confirmation, no
undo — and the placeholders go out raw. Three taps on "Ранний приезд" put three identical bubbles
into the co-parent's thread at 22:10: *"Привет! Я приеду раньше обычного, примерно в [время]. Это
удобно?"* — `[время]` unfilled, three times.

The three taps were not a stress test. Because the list was parked at the top (item 4), the first
send produced no visible change, so it read as a missed tap and was repeated. That is precisely how
a real parent sends the same message three times, and it makes 4 and 6 one defect with two halves.

The plumbing for the fix already exists: `ChatScreen(draft = …)` → `MessageInput(initialText = …)`
already pre-fills the composer for the Expenses settle-up flow, documented there as *"Never sent
automatically — a message to the co-parent is the user's to send."* Templates simply do not use it.
(The sheet's own chrome — "Message Templates", "Pickup & Drop-off", "Illness & Medical" — is
hardcoded English.)

**7 — Expenses is buttons only.** Confirmed. `MonthSwitcherBar` / `MonthNavigation` chevrons are the
whole navigation; there is no `pointerInput`, `draggable` or pager anywhere in
`presentation/expenses`. On device, swipes in both directions across the summary card, across the
list area and across the month bar itself changed nothing — August 2026 stayed August, July 2026
stayed July — while the chevron moved months instantly. Two things for whoever implements the swipe:
on an empty month the summary card is not rendered, so those two chevrons are the only affordance on
the screen; and the expense rows already own the horizontal gesture for swipe-to-delete, so a month
pager has to coexist with it.

**8 — Calendar swipe is still asymmetric.** Two cold-start runs, five scripted swipes each
(`input swipe`, 250 ms, 1.5 s apart), `dumpsys gfxinfo` reset before each run, both starting from
the current month:

| Direction | Frames rendered | Janky | p50 | p90 |
|---|---|---|---|---|
| Forward (next month, right→left) | 142 | 20.4% | 15 ms | 105 ms |
| Backward (previous month, left→right) | 36 | 58.3% | 93 ms | 200 ms |

A warm repeat of the same pair gave 155 vs 62 frames. Both runs moved exactly five months, so the
gestures all registered. Same distance, same duration, same content: backward draws roughly a
quarter of the frames — the transition is a handful of long frames rather than an animation.

Worth flagging for whoever picks this up: `MonthView` already carries three fixes aimed at this
symptom — a stable `anchorMonth` so the pager's loaded range does not shift on every settle,
propagating the month only after `isScrollInProgress` goes false, and `OutDateStyle.EndOfGrid` so
short and tall months are the same height. Whatever remains is *not* those three causes.

`[H]` **not done:** the 4.2.3 characterisation ("first right-swipe slow, the next fast") needs a
hand on the glass. Frame counters cannot express inertia, and this pass was scripted.

**9 — The agenda strip.** Confirmed exactly as suspected. Paging one month forward moved the
selection to the 1st — `MonthView.onMonthChange` → `setSelectedDate(newMonth.atDay(1))`
(`CalendarScreen.kt:527`) — and the card below read **"вт, сент. 1 · Ничего не запланировано."**
Paging backward behaves the same way: five swipes back landed on "вс, мар. 1", again nothing
scheduled. In the current month the card does show today, because `selectedDate` starts at
`LocalDate.now()` — so "today" survives exactly until the first swipe.

With this account's data the card said "Ничего не запланировано" on **every** month paged to, in
both directions: August has two days with events (the 3rd and the 21st) and neither is the 1st, and
September and March have none at all. The strip is showing an empty day nobody chose, on every
screen except the one you started on.

### Fixed in batch 1 — 4 August 2026

Five of the ten now behave differently; each was re-checked on the Samsung against the branch
build, not just against its unit tests. The baseline above is deliberately left as written — it is
the before-picture and only stays useful if it keeps saying what the app did on 3 August.

| # | Now |
|---|---|
| 4 | The thread opens on the **newest** message. The near-bottom rule still decides whether an *arriving* message pulls the view down, so reading history is not interrupted. |
| 5 | The card carries a chevron and opens the change-request inbox with that request highlighted and scrolled to; a request that is genuinely gone gets a snackbar instead, and never on the initial empty load. The inbox itself now renders in the device's language — its translations existed in all five locales and no code had ever referenced them. |
| 6 | A template **fills the composer**; nothing is sent until the user presses Send. Placeholders arrive intact for editing, which is the point. |
| 7 | The month header — the summary card and the switcher bar — pages on a horizontal swipe. The list keeps swipe-to-delete as its only horizontal gesture. |
| 9 | Paging a month no longer selects anything: the agenda card appears only for a day the user tapped, and the grid takes the freed height. The current month still opens on today, and the Today pill still returns to it from any distance. |

**Item 8 is not fixed, and now has a diagnosis instead** — see the spec
(`docs/superpowers/specs/2026-08-03-plan11-batch-1-design.md`). Two device experiments showed the
cost is neither the pager nor per-cell composition but the state round-trip a settle triggers;
stripping every per-cell lookup changed nothing (26 frames vs 30), while cutting the month
propagation made backward paging match forward exactly (124 frames vs 30). It was carved out rather
than fixed on an unvalidated hunch. The `[H]` half of 4.2.3 — how it feels under a thumb — is still
unrun.

**Items 1a, 1b, 2 and 3 are untouched.** Each needs a data-model change, Firestore rules and product
decisions this batch did not make.

### Item 8 — measured after the attempted fix — 4 August 2026 (Task 4)

`docs/superpowers/specs/2026-08-04-calendar-swipe-symmetry-design.md` (Tasks 1–3, branch
`fix/calendar-swipe-symmetry`) shipped a sticky query-anchor window (±3 months, re-anchoring only
past a 2-month threshold) and collapsed `EventViewModel` to a single `flatMapLatest` collector, aimed
at reproducing the diagnosis's row-4 result — backward paging matching forward at roughly 124 frames,
~16% janky. This is the device measurement of that fix, same Samsung, same protocol
(`dumpsys gfxinfo … reset`, five `input swipe` gestures 250 ms long, 1.5 s apart, cold start per run,
Calendar tab confirmed open before each reset). Two runs per direction, both reported:

| Run | Frames | Janky | p50 | p90 |
|---|---|---|---|---|
| Forward, run 1 | 121 | 27.3% | 17 ms | 200 ms |
| Forward, run 2 | 144 | 20.8% | 15 ms | 121 ms |
| Backward, run 1 | 26 | 69.2% | 200 ms | 300 ms |
| Backward, run 2 | 31 | 64.5% | 125 ms | 200 ms |

Forward — the control — is consistent with the July baseline (142 frames, 20.4% janky, p50 14 ms)
once run-to-run variance is accounted for: run 2 lands within a couple of points of it, run 1 is a
noisier outlier but the same order of magnitude. Nothing suggests the forward path regressed, so the
backward comparison is clean.

**Backward, after the fix, still lands at 26–31 frames and 64–69% janky with p50 125–200 ms** —
indistinguishable from the pre-fix baseline (30 frames, 70%, p50 121 ms) and closer to the
diagnosis's dead-end "per-cell work stripped" experiment (26 frames, 69.2%, p50 200 ms) than to the
"month propagation cut" experiment (124 frames, 16.1%, p50 16 ms) the fix was built to reproduce.

**The fix did not close the gap.** Event dots on Aug 3 and Aug 21 were confirmed intact after five
swipes each direction, so the wider query window is not silently dropping data — it simply is not
delivering the frame-rate improvement the diagnosis's isolated experiment predicted. Item 8 stays
open: this is a fresh investigation into what the shipped code is still doing differently from that
experiment, not a partial win. `[H]` (whether the "first swipe back is sticky" feeling is gone) was
correctly not run given these numbers — it remains outstanding regardless, per the spec's own rule
that the item cannot be marked closed without it.

**But the negative result narrows the cause, and that is the round's real output.** Three
experiments now bracket it: stripping every per-cell lookup changed nothing (26 frames); making
`onMonthChange` a *complete* no-op fixed it (124 frames); removing only the *event query* that
`onMonthChange` triggered — this branch — changed nothing (26–31 frames). Row 4 removed three things
at once, and the diagnosis attributed the whole gain to the query. That attribution is now falsified.
What is left of row 4's delta is the ViewModel state write and the whole-screen recomposition it
causes: `showMonth` still writes `_displayedMonth` and `_selectedDate`, and `CalendarScreen` collects
both near the top of its composable body, so every settle still recomposes the entire screen even
though no query is issued. The next experiment is the mirror image of this one — keep the query
wiring, cut only the state write, re-run the protocol. Full reasoning in the spec's "What the
negative result narrows it to".

Tasks 1–3 stay on the branch: the collector leak and the per-settle re-query were real defects with
their own unit tests, and they are fixed. They are simply not the frame-rate fix.

---

## §12 Known issues — confirm or clear

Each of these is documented in `CLAUDE.md`. The point is evidence, not rediscovery.

**12.1 The chat listener never retries.** Both mirror branches end in `.catch { Log.w(...) }`,
which completes the flow, so `merge(mirror, local)` runs on Room alone for the rest of the process.
`SharingStarted.WhileSubscribed` cannot restart it because an Activity-scoped `ChatViewModel`
holds a subscriber for the whole process. Observed once in production, on the first launch after
install, when both listeners were denied about half a second before `ensureConversation` created
the conversation document. **Parent A's fresh install in §2 is the exact reproduction window.**
Watch logcat. If it fires, the app looks healthy while receiving nothing until a cold restart.

**12.2 Cross-time-zone chat is implemented but never verified on two devices.** Unit tests cover
it; the two-phone run was deferred. §5 finally runs it.

**12.3 Budgets written before the owner-field fix carry no `createdByFirebaseUid`,** so the
filtered read query silently excludes them. Nothing disappears on the device that created them,
but they will not restore on a reinstall. **Parent A's fresh install shows whether any exist.**

**12.4 Unreachable code.** `MedicalRepositoryImpl` / `EducationRepositoryImpl` are not bound in
`RepositoryModule`; the `presentation/ai` screens (`EventSuggestionsScreen`,
`NaturalLanguageEventScreen`, `ConflictAlertCard`) are not in `NavGraph`; `custody_schedules` has
a Firestore rule but no data source. None of this is testable — it needs a decision: delete, or
wire up and cover. Record the decision here rather than testing around it.

**12.5 Detekt fails on `main`** with 22 weighted issues, all pre-existing. Not a runtime concern;
noted so nobody reports it as a regression.

---

## §13 Findings

Build: `df593234` · Date: 9 August 2026 · Tester: Claude (scripted, `[A]`/`[2]` only)

| ID | § | Area | Severity | What happened | Expected | Device | Evidence |
|---|---|---|---|---|---|---|---|
| F-01 | 2.14 | Sync / pairing | **High** | Re-pairing with the **same** co-parent does not re-share the events unpairing revoked. `unpairCoParent`'s sweep narrows every shared document's `sharedWith`; on re-pair, `SyncService.backfillAudienceForPartner` skips because its marker already equals that partner's uid, and the accepter's `reslotOwner` also does nothing because the slot did not change (A stayed inviter, B stayed accepter). Neither `Audience backfill` nor a re-stamp line appeared in either log. | Re-pairing restores the co-parent's access to what they could read before | Both | Logcat on both after 2.9 (no backfill/re-stamp line); mechanism in `SyncService.backfillAudienceForPartner` + `ParentSlotMigrator.reslot`'s `from == to` early return |
| F-02 | 4.1 / 3.1 | Home / Calendar | Medium | An unpaired account still shows the custody handover hero, and the co-parent's name degrades to the literal word: "Передача **(Родитель)** через 2 дня". The pattern describes two people, one of whom no longer exists for this account. | Either hide the handover hero while unpaired, or say the schedule needs a co-parent | Both | `a-home.png`, `b-home.png` — reproduced independently on each handset |
| F-03 | 2.13 | Pairing | Low | After the owner unpaired from B, Parent A's Room still held `partnerId` pointing at B until A's next sync. A's UI would have shown a co-parent who no longer exists. | Local pairing state reflects the unpair promptly, or the UI says it is stale | A | `pre-A.db` (`partnerId=F7wE4…` post-unpair); cleared after A launched and synced |
| F-04 | 2.5 | Pairing | Low | The only confirmation seen after tapping the invite code is Android 13+'s system clipboard chip. `minSdk` is 26, so on Android 12 and below the same tap plausibly looks like nothing happened. | The app confirms the copy itself, independent of OS version | B (Android 15) | `b-copy.png`; no in-app snackbar survived the chip |
| F-05 | 2.7 | Pairing | Low | In "Мой код" the trust panel sits below the fold, under the QR — a parent deciding whether to trust the app with a custody schedule must scroll past the code to find the answer. In "Ввести код" it is visible without scrolling. | The trust statement is visible where the decision is made | B | `b-pairing-1.png` vs `b-pairing-bottom.png` |
| F-06 | 2.4 | Pairing | Polish | "Отправить ссылку" wraps mid-word — "Отправит / ь ссылку" — on the Samsung's narrower layout, in Russian. §10.6 anticipates this for German; it already happens in the run locale. | Button labels wrap on word boundaries or shrink to fit | A | `a-pairing.png` |
| F-07 | 2.12 | Pairing | Plan bug | The plan expects a mail app to open. The app instead expands an inline address field and sends server-side via `sendEmailInvitation`. The implementation is the deliberate one; the plan's expected column is wrong. | — | B | `b-email.png` |
| F-08 | 2.9 | Pairing / slots | **Blocker** | **Both parents ended up in slot 1.** After pairing, A (inviter) and B (accepter) both read `role = mom`, and B stayed `mom` through a force-stop, a cold start and a completed sync. This is *not* the documented 15-minute `role` lag: `SyncWorker` logged `SUCCESS` and `FirestoreUserDataSource` logged `Got user from server: F7wE4…`, and `SyncService.syncUserData` writes `role` from exactly that document — so B's Firestore document does not hold `dad`. `acceptPairingInvitationImpl` does write `role: slots.accepterRole` inside its transaction (`assignSlots('mom')` → `dad`), so something after the transaction is putting `mom` back; the untested hypothesis is a client profile write re-stamping `UserRepositoryImpl.DEFAULT_ROLE`. Consequence: both parents render in the same colour, and `momDayIndices` — defined as "the days slot 1 has custody" — no longer distinguishes anybody, which makes the custody pattern meaningless for the pair. | Accepter is moved to slot 2 (`dad`) and stays there | Both | `post-A.db`, `post-B.db`, `cold-B.db` (all `role=mom`); B logcat showing `SyncWorker` SUCCESS + server read with no `Reacted to a remote slot change` line |

**Not run, and why.** 2.8 (QR scan) needs a camera physically aimed at the other handset. Every
`[H]` row is untouched by instruction. §3 onward was not started in this session.

**Process note.** One scripted tap in this run landed outside CoPlanly: two `KEYCODE_BACK` presses
exited the app and the next tap opened an unrelated launcher icon. Nothing was recorded from it,
and focus is now asserted before each tap — the guard this plan already states in "Devices and
roles" and which this run confirms is not optional.

### Impressions summary

Three sentences per surface, written after the pass, not during it: Home, Calendar, Chat,
Expenses, Pairing, Settings. What would a separated parent tell a friend about this app after a
week of using it?

### Ranked improvements

Everything from the findings table plus anything from the impressions, ordered by what would
change a real parent's day the most — not by how easy it is to fix.
