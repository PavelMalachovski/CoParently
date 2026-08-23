# SDD ledger — plan: docs/superpowers/plans/2026-08-23-expense-analytics.md

Branch: `claude/start-e-schemas-docs-3kn5c6`.
Base: `main` @ `3122436` — the merge of PR #56, which is package **E**.

F was written on top of E while E's pull request was still open, on the branch this session is
fixed to. E merged first, so the branch was restarted from the new `main` and F's five commits
rebased onto it: this branch and its pull request are now **F alone**, and every line of E in the
diff is gone because it is in `main`.
Tasks: 5. Tasks 1–4 implemented; Task 5 partly run.

## What was and was not verified here

Same environment as packages C, D and E: **no Android SDK, and no route to Google's Maven host**
(`dl.google.com`, and `maven.google.com` by redirect, are refused by the proxy with 403). So
`assembleDebug`, `testDebugUnitTest`, `lint` and `detekt` were **not** run, and no Compose file in
this package has been through a compiler.

Really run:

- **The colour-blindness validator, on both palettes.** This is the check that mattered most here
  and the one this package would otherwise have shipped on taste. Numbers in Task 2 below.
- **Every pure-Kotlin test that compiles without the Android classpath** — 34 classes against 78
  pure main sources under a standalone `kotlinc` 2.1: **312 passing**, up from 293 before this
  package. F's own are `CategoryBreakdownTest` (13) and `CategoryPaletteTest` (6).
- **Locale completeness**, by grep: all 12 new keys in exactly five files each; no key added and
  left unreferenced.
- **`git diff main..HEAD -- app/build.gradle.kts` is empty.** No charting dependency.
- **`MaxLineLength` 120** over every file this package touches.

Not run, and not needed: the Firestore rules suite. This package adds no field, no collection and
no write path — it reads `monthExpenses`, which the screen already collected. (It was run at the
end of package E on this same branch: 237 passing, eslint clean.)

---

## Ledger

Task 1 (the aggregation): `300be47`. Pure, 13 tests.
  - The plan's second test — two currencies never producing a combined total — is the one to read
    first, and it has a **subtler sibling the plan did not name**: two separate totals sharing one
    denominator. That draws a chart that lies just as badly, and it is the version an
    implementation reaches by accident. Pinned as its own case.
  - **Three guards the plan did not name**, each of which fails on screen rather than in a log:
    a currency whose expenses total zero (a refund pair, a mis-typed 0) is dropped rather than
    divided by — every share would be `NaN`, and a `NaN` sweep angle draws no arc at all, so the
    chart would come back silently blank instead of saying it had nothing; a category netting out
    to zero or below is not a slice, because a pie cannot draw a negative one; and filtering to a
    payer with nothing this month yields no breakdown at all rather than a zero-total entry the
    currency chip row would then offer.

Task 2 (the palette): `4d30ec8`. **The plan's premise here is wrong, and measurement is what
showed it.**
  - The plan says "nine categories need nine distinguishable fills". They cannot have them. Nine
    hues at a fixed lightness fail immediately — ΔE 1.0 under deuteranopia, and 8.5 even for
    full-colour vision, against a target of 8 and a floor of 15. Varying lightness and chroma and
    re-ordering the wheel lifts the *adjacent* pairs but never the all-pairs case: a search over
    several hundred lightness/chroma/ordering combinations topped out at ΔE 6.9 in light and 1.3
    in dark. This is a property of nine, not of these nine, and no palette repairs it.
  - What shipped: nine slots keyed to the enum ordinal, each the theme primary's own hue plus a
    fixed offset — nine 40° steps **assigned five slots apart**, so neighbouring categories sit on
    opposite sides of the wheel. Lightness and chroma alternate on top, because hue alone is what
    collapses under red-green colour blindness. Light and dark are separately selected, not
    flipped; the dark steps sit in their own narrower band.

    | | worst adjacent pair, colour-blind | worst adjacent pair, full colour | contrast |
    |---|---|---|---|
    | light | ΔE 10.5 (protanopia) | ΔE 30.3 | 3 of 9 below 3:1 — relieved by the table |
    | dark | ΔE 11.1 (deuteranopia) | ΔE 25.0 | all 9 above 3:1 |

  - The consequence is a constraint on the *chart*, not on the palette, and it shaped Tasks 3 and
    4: this screen may never ask anyone to tell two slices apart by colour.
  - `CategoryPalette` is kept free of Compose so all of it is unit testable, and the test pins the
    exact validated hexes. A change to the theme's primary therefore **fails the test** rather
    than silently shipping a palette nobody re-checked. That is the point of pinning them.
  - The Compose accessor went into `ExpenseCategoryLabel.kt` beside `labelRes` and `iconVector`,
    per that file's own KDoc, rather than into a second file.

Task 3 (the chart): `965afa2`.
  - DEVIATION. Arcs are drawn in **category order, not by amount**. Only neighbouring arcs touch,
    and the palette's whole design is that neighbouring *categories* are far apart on the wheel —
    sorting by amount would seat an arbitrary colour pair together on every filter change, which
    is exactly the pairing Task 2 measured as unguaranteeable. Item 14's "largest first" is a
    *reading* order and belongs to the table, which has one; a pie does not.
  - A 2dp gap of surface separates the arcs, converted to degrees at the drawn radius so it stays
    2dp at any layout size. A stroke was not used: a border around a mark is ink that is not data.
  - Three guards: an empty list draws an empty canvas rather than crashing; a single slice is a
    full circle rather than a 360°-minus-a-gap arc with a seam; and a slice narrower than the gap
    draws nothing rather than a negative sweep, which `drawArc` paints *backwards over its
    neighbour* — a wrong chart rather than a missing one.
  - Semantics are **cleared**, not merely set to null, so the `Canvas` cannot leak anything.

Task 4 (the analytics view): `5f5a956`.
  - DEVIATION, and it follows from Task 2. **There is no separate legend.** The table under the
    chart carries a colour swatch, the category name, the amount and the share on every row, so
    it already is the legend — and a separate one would be a second list of the same nine
    categories beside the first. Identity is never colour alone either way, which is the rule the
    legend exists to serve.
  - The payer filter is hidden unless both parents resolve to **distinct** uids, mirroring
    `ExpenseBalance.splitKnown`. It filters on `paidBy` — a uid, never a slot, because a slot
    would attribute both parents' spending to one of them on a pair whose slots have not been
    separated yet.
  - The currency selection **falls back when the picked currency stops being available**. A payer
    filter or a month change can remove it, and without the fallback the chart goes blank under a
    chip row still showing something else. Not named in the plan; found by asking what happens
    after the second control moves.
  - Two empty states, not one. A month with nothing in it and a filter with nothing in it have
    different remedies, and "nothing spent this month" under a filter the user has just set reads
    as a bug rather than as an answer.
  - `rememberSaveable` on the view choice, so a rotation does not drop a parent back into the list
    they had switched away from.

Task 5 (verification): validator, pure-Kotlin, greps and the dependency check done (above).
Everything Gradle-shaped, and the device run, still outstanding.

---

## Still to run

- [ ] `./gradlew clean assembleDebug testDebugUnitTest lint detekt` — nothing Compose-shaped in
      this package has compiled.
- [ ] The seven device checks, plan Task 5 step 4.
- [ ] Record the run in the spec's §7.

Note this package needs **no** schema, migration or rules work — it is presentation and
arithmetic over data that already exists. The uncommitted `app/schemas/15.json`…`19.json` are
package E's outstanding item, not this one's.

## The check that proves this package

**A month with expenses in two currencies.** The chip row appears, and no view anywhere shows a
total across both. `CategoryBreakdownTest` pins the arithmetic, but only a device shows whether
the *screen* ever puts two currencies in one frame in a way that reads as one figure.

## Two things to watch alongside it

1. **The pie with many categories at once.** The palette clears its standard on adjacent pairs and
   provably cannot on all pairs, so a month using seven, eight or nine categories is where the
   design leans hardest on the table. Look at whether the table is genuinely the thing you read —
   if the chart is doing the work, the fold-the-tail question from the charting standard should be
   reopened. The all-nine `@Preview` renders exactly this case without needing the data.
2. **The filter and the chart moving together.** They are two views of one number. A filter change
   that moves the table but leaves the chart a frame behind would be the failure mode worth
   catching, and it is invisible in a screenshot.
