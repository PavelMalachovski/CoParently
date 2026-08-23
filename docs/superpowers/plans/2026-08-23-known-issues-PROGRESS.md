# SDD ledger — package H: the known-issues backlog

No spec and no plan document: this package is the "Known issues / do not fix silently" list in
`CLAUDE.md`, worked through in order of how badly each one fails. Every entry it closes is
rewritten there in place — as a record of what the fix costs and what not to undo, not deleted,
because the reasoning is what stops the next change reintroducing them.

Branch: `claude/package-h-known-issues`, cut from `claude/package-g2-guest-access` @ `c8aaf3d`
(package G2, open as PR #60). **Base for review is therefore G2, not `main`.** H1 rewrites the
same screen G2's guest list lives on, so cutting from `main` would have meant resolving that
conflict twice.

Four of the five listed issues are fixed. The fifth — cross-time-zone chat verified on two
devices — is a device run, not a code change, and stays outstanding.

## What was and was not verified here

Same environment as C through G: **no Android SDK, and no route to Google's Maven host**
(`dl.google.com`, and `maven.google.com` by redirect, are refused by the proxy with 403). So
`assembleDebug`, `testDebugUnitTest`, `lint` and `detekt` were **not** run, and no Compose,
Hilt or Room-annotated file in this package has been through a compiler.

Really run:

- **334 pure-Kotlin tests** under a standalone `kotlinc` 2.1, up from 321 at the end of G2.
  H's own are `ChatRetryBackoffTest` (4) and `CustodyTimestampsTest` (9).
- **265 Firestore rules cases** on the emulator, up from 263. Both new ones are H3's.
- **103 Cloud Functions cases** and `eslint` clean — unchanged by this package, run as a
  regression.
- **`MaxLineLength` 120** over every Kotlin line added, and nothing added to
  `app/config/detekt/baseline.xml`.
- **Locale completeness** by grep: two new keys in exactly five files each, both referenced.

## Ledger

H1 (the child editor): `8ccae05`. **Two halves, and either alone changes nothing observable.**

  - `loadChildInfo()` collected `getAllChildInfo()` for the ViewModel's whole lifetime and set
    `_currentChildInfo` to `childInfoList.first()` on every emission. Editing child B, any write
    touching the table — a background sync tick was enough — moved the base to child A, and the
    save overwrote **child A's real row**, id and ownership stamps included.
  - The other half is why it had never been hit: the screen rendered only the first child, and
    the app's only "add a child" affordance was the empty state, so a second child could not
    exist. G2 made it matter — a second child's guest list and revoke action were unreachable.
  - The screen now lists every child, each group its own list item **keyed by child id**. Two
    children with no medications would otherwise collide on one slot and share Compose state.
  - Also dropped the `loadChildInfo()` call at the end of `deleteChildInfo`: the flow has already
    re-emitted, and calling it again started a second collector, one more with every delete.

H2 (the chat listener): `ae8f029`.

  - `retryWhen` with backoff on both mirror branches, **before** the `catch` and **after** the
    `onEach`, so the retry re-runs the whole chain and establishes a new snapshot listener.
  - **No attempt limit.** What a limit buys is a final `catch`, and the state that leaves behind
    is exactly the one this exists to prevent. Every attempt logs the diagnostic the old `catch`
    carried, so a missing index or wrong rule is still one logcat line away.
  - `ChatRetryBackoff` is pure and its own file. The property worth pinning is not that the
    delay grows but that it is **always positive**: `1000L shl 62` is negative and `shl 64` is
    1000 again, either of which busy-loops against a backend already refusing everything. Tested
    over `Long.MAX_VALUE`.

H3 (custody timestamps): `1fdd278`. **The biggest change in this package, and the riskiest.**

  - Room **21 → 22**, the domain model, and a new `lastModifiedAtMillis` on the shared document.
    `CustodyTimestamps` holds the parse, the comparison and the legacy format in one pure place.
  - **The document keeps `lastModifiedAt` as an ISO string beside the number, carried verbatim.**
    Discovered by reading the deployed rule rather than the Kotlin: an older co-parent reads only
    that field, as a string, and a number there comes back blank — their device would then judge
    its own copy newer and re-push it over this one. Verbatim rather than re-derived because
    `swapWriteTouchesOnlyTheSwap` denies a swap that alters it, and the same instant re-derived
    in the other parent's zone is a different string, so a co-parent's swap would start failing.
  - Three rules cases: one per timestamp, plus one that an ordinary swap still passes. Without
    that third, a `hasOnly` list that accidentally excluded a key real swaps do write would read
    as correctly strict while breaking the feature outright.
  - Two one-time costs, both recorded in `CLAUDE.md` rather than hidden. `strftime` reads the
    stored string as UTC, so a pre-migration row is off by the device's own offset — the *order*
    of two local rows is preserved, which is all the local guard uses, and the error is gone at
    the next write. And the banner's dismissal key is now millis, so a preference holding an ISO
    string matches nothing and one already-dismissed change is announced once more.
  - `CustodyProposalTransition.accept` takes both stamps now. Accepting is a genuine pattern
    change; proposing, withdrawing and declining still touch neither.

H4 (the calendar's failed range): `c7fcc6f`.

  - An `Error` branch in the same `LaunchedEffect`, raising an indefinite snackbar with Retry
    wired to `refresh()`. A snackbar rather than an error state in the grid, because the query
    flips to `Loading` on every re-anchor and the grid would flicker on ordinary paging.
    `Indefinite`, because a message about a grid the user is currently reading must not time out
    before they look up from it.

## Still to run

- [ ] `./gradlew clean assembleDebug testDebugUnitTest lint detekt`. This package updated four
      MockK suites (`CustodyModelRepositoryTest`, `FirestoreCustodyDataSourceTest`,
      `CalendarViewModelCustodyChangeTest`, `PairingViewModelTest`) that cannot run here, so the
      first local build is where they are actually exercised.
- [ ] `connectedDebugAndroidTest --tests …CoPlanlyDatabaseMigrationTest` (21→22, and the whole
      chain from 13).
- [ ] **Commit the generated `app/schemas/…/15.json` through `22.json`.**
- [ ] The device checks below.
- [ ] The one issue this package does not close: **cross-time-zone chat on two devices**. It is
      a device run, and it now has a sibling — H3 gives custody the same epoch-millis treatment
      chat already had, so the two are worth checking in the same session with the same two
      phones two zones apart.

## The check that proves this package

**Two children on file, edit the second one, save it, and then look at the first.** H1 is the
only fix here whose failure mode silently corrupts data rather than merely looking wrong, and
the corruption lands on a record the user was not even editing.

## Three things to watch alongside it

1. **A custody pattern saved on one phone while the other is two zones away.** H3 changes which
   side wins a sync, and the loser's pattern is overwritten. If the wrong one still wins, check
   whether the co-parent's build is writing `lastModifiedAtMillis` at all — a mixed pair falls
   back to the ISO string and to the old, zone-dependent answer, by design.
2. **A co-parent's day swap, after H3.** The swap rule now guards two timestamp fields instead
   of one. If swaps start failing with `PERMISSION_DENIED`, something is re-deriving
   `lastModifiedAt` instead of carrying it verbatim.
3. **Airplane mode on, then off, then a message from the other phone.** H2's whole point is that
   it arrives without a restart. The log line says which listener retried and when.
