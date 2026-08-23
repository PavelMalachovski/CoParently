# First-run onboarding wizard — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Walk a parent through filling in their own details, their child's, their relatives' contacts, the custody schedule and the co-parent invitation on first run — and tell them why the app is asking.

**Architecture:** A wizard owning its own frame and step state, reusing B1's section composables for the three data steps and navigating to the existing `CustodySetupScreen` and `PairingScreen` for the last two. Completion is an ISO string on `users/{uid}`, mirrored to Room so the launch check never waits on the network.

**Tech Stack:** Kotlin 2.1, Jetpack Compose (Material 3), Room 2.7.2, Hilt, Firebase Firestore, JUnit 4 + MockK.

**Spec:** `docs/superpowers/specs/2026-08-23-onboarding-wizard-design.md`

---

## Before starting — two gates

**This plan targets the repository as it will be once PR #49 (`feat/child-parent-profiles-2026-08`) is merged.** Every task consumes something that branch introduces — `MedicalProfileEditor`, `ProfileViewModel`, `User.dateOfBirth`/`phone`/`allergies`/`medicalProfile`, and Room schema version 14. Do not start until it is on `main`, and if it changed during review, re-read the parts of it each task names rather than trusting the signatures quoted here.

**One design question is open** — spec §8. Item 3 says everything is optional *except your own details*. This plan implements the reading that only the parent's **name** is mandatory, because item 4 in the same list says the data is collected for the user's own benefit and such data must not lock them out of their calendar. If the owner wants the strict reading — the whole about-you block mandatory, blood type included — it changes **one predicate in Task 3** and nothing else. Ask before implementing Task 3 if the answer has not arrived.

## Global Constraints

- **Jetpack Compose only.** Never add an XML layout.
- **Stateless composables** — state lives in ViewModels as `StateFlow`; UI takes values and callbacks.
- **Hilt** for all DI. New modules go in `app/src/main/java/com/coparently/app/di/`.
- **A ViewModel never holds a `Context` or an `Activity`.**
- **Never hardcode user-visible text.** `stringResource` only; every new key in `values`, `values-cs`, `values-de`, `values-ru`, `values-uk` **in the same commit**. `MissingTranslation` lint is disabled project-wide and reports nothing — grep is the only check.
- Colours from `MaterialTheme.colorScheme`, never literal hex. Material 3 only.
- **Room schema changes** require: entity change → version bump in `CoPlanlyDatabase` → migration in `DatabaseMigrations` (auto-registered via `ALL_MIGRATIONS`). Exported schemas live in `app/schemas/`.
- **Dates cross the Firestore schema as ISO strings**, never Firestore timestamps.
- **`"role"` must never be added to `UserRepositoryImpl`'s Firestore map** — read the comment above it; a stale echo once put both parents in slot 1.
- KDoc on every public class and function. Code and comments in **English**.
- detekt `MaxLineLength` **120**, comments included; `TooGenericExceptionCaught` active and lists `Exception`. Nothing added to `app/config/detekt/baseline.xml`.
- minSdk 26 — no `java.time` API added after 26 (`LocalDate.ofInstant` is API 34).
- **Conventional Commits.**
- Build with the JDK at `C:\Program Files\Android\Android Studio1\jbr`; the machine's `JAVA_HOME` is broken:
  `JAVA_HOME="C:\Program Files\Android\Android Studio1\jbr" ./gradlew ...`
- `./gradlew detekt` exits non-zero from findings that pre-date any branch. Judge by whether **your** files appear in `app/build/reports/detekt/detekt.xml`, never by the exit code.

---

### Task 1: Completion state — the column, the migration, the mapping

**Files:**
- Modify: `app/src/main/java/com/coparently/app/data/local/entity/UserEntity.kt`
- Modify: `app/src/main/java/com/coparently/app/data/local/CoPlanlyDatabase.kt`
- Modify: `app/src/main/java/com/coparently/app/data/local/DatabaseMigrations.kt`
- Modify: `app/src/main/java/com/coparently/app/domain/model/User.kt`
- Modify: `app/src/main/java/com/coparently/app/data/repository/UserRepositoryImpl.kt`
- Test: `app/src/androidTest/java/com/coparently/app/data/local/CoPlanlyDatabaseMigrationTest.kt` (add a case)

**Interfaces:**
- Consumes: nothing.
- Produces: `User.onboardingCompletedAt: String?` — an ISO date-time string, null until onboarding finishes; `UserEntity.onboardingCompletedAt: String?`; database version **15**.

`UserRepositoryImpl` has **four** places that must carry it: `toDomain`, `toEntity`, `toUser` (the Firestore reader) and the outbound `userData` map. B1 shipped a Critical defect from missing one of exactly these — the parent's profile was write-only for a whole review cycle. Check all four before committing.

- [ ] **Step 1: Add the column**

In `UserEntity.kt`, after the fields B1 added, and document it in the class KDoc:

```kotlin
    /**
     * ISO date-time at which this user finished (or skipped through) first-run onboarding.
     * Null means the wizard has not been completed. A string rather than a converted type
     * because that is how every date crosses this Firestore schema.
     */
    val onboardingCompletedAt: String? = null
```

In `User.kt`, the same property with the same KDoc, and add it to the class `@property` list.

- [ ] **Step 2: Write the migration**

In `DatabaseMigrations.kt`, after `MIGRATION_13_14`:

```kotlin
    /**
     * Records when a user finished first-run onboarding.
     *
     * A single nullable column, so the migration cannot lose anything it does not touch. Null on
     * every existing row is the correct starting state: `OnboardingState` treats an account that
     * already has a profile name and a child as complete regardless, so no existing user is
     * handed a questionnaire about data they already entered.
     */
    val MIGRATION_14_15 = object : Migration(14, 15) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE users ADD COLUMN onboardingCompletedAt TEXT")
        }
    }
```

Add `MIGRATION_14_15` as the last entry of `ALL_MIGRATIONS`, and change `CoPlanlyDatabase`'s `version = 14` to `version = 15`.

- [ ] **Step 3: Carry it through all four mappings**

In `UserRepositoryImpl.kt`:

- `toDomain()` — `onboardingCompletedAt = onboardingCompletedAt,`
- `toEntity()` — `onboardingCompletedAt = onboardingCompletedAt,`
- `toUser()` — `onboardingCompletedAt = (this["onboardingCompletedAt"] as? String)?.takeIf { it.isNotBlank() },`
- the outbound `userData` map — `"onboardingCompletedAt" to (user.onboardingCompletedAt ?: ""),`

Also carry it in `writeLocalProfile`'s **fresh-row** branch, seeded from `remote`, exactly as B1's fix does for the medical fields. That branch is where B1's Critical C2 lived.

Leave the absence of `"role"` from the outbound map exactly as it is.

- [ ] **Step 4: Add the migration test case**

Append to `CoPlanlyDatabaseMigrationTest.kt`, following the file's existing style:

```kotlin
    @Test
    fun migration14To15_addsTheOnboardingMarkerAsNull() {
        val db = helper.createDatabase(TEST_DB, 14)
        db.execSQL(
            """
            INSERT INTO users (id, email, name, role, colorCode, googleCalendarSyncEnabled,
                               allergiesJson, medicalProfileJson)
            VALUES ('u1', 'a@example.com', 'Olya', 'mom', '#FF4081', 0, '[]', '{}')
            """.trimIndent()
        )
        db.close()

        val migrated = helper.runMigrationsAndValidate(
            TEST_DB, 15, true, DatabaseMigrations.MIGRATION_14_15
        )

        migrated.query("SELECT name, onboardingCompletedAt FROM users").use {
            assertTrue(it.moveToFirst())
            assertEquals("Olya", it.getString(0))
            assertTrue("the marker must start null, not empty", it.isNull(1))
        }
    }
```

If the `users` table's non-null columns differ from the list above, read `app/schemas/…/14.json` and match it — an insert missing a `NOT NULL` column fails at runtime, not at compile time.

- [ ] **Step 5: Verify**

```bash
JAVA_HOME="C:\Program Files\Android\Android Studio1\jbr" ./gradlew assembleDebug testDebugUnitTest
```
Then, with the device attached:
```bash
JAVA_HOME="C:\Program Files\Android\Android Studio1\jbr" ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.coparently.app.data.local.CoPlanlyDatabaseMigrationTest
```
Expected: BUILD SUCCESSFUL, and `app/schemas/…/15.json` generated.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/coparently/app/data app/src/main/java/com/coparently/app/domain/model/User.kt app/src/androidTest app/schemas
git commit -m "feat(onboarding): record when a parent finished the first run"
```

---

### Task 2: `OnboardingState` — deciding whether to ask

**Files:**
- Create: `app/src/main/java/com/coparently/app/domain/onboarding/OnboardingState.kt`
- Test: `app/src/test/java/com/coparently/app/domain/onboarding/OnboardingStateTest.kt`

**Interfaces:**
- Consumes: `User.onboardingCompletedAt` (Task 1).
- Produces: `fun OnboardingState.isNeeded(user: User?, hasChildInfo: Boolean): Boolean`, as an object with that one function.

Pure logic, no Android, no repository — so the decision that gates the whole app's start destination is testable without a device.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/coparently/app/domain/onboarding/OnboardingStateTest.kt`:

```kotlin
package com.coparently.app.domain.onboarding

import com.coparently.app.domain.model.User
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Whether a parent should be shown the first-run questionnaire.
 *
 * The case that matters most is the last one. Every existing installation upgrades into this
 * code with `onboardingCompletedAt` null, and handing a questionnaire to someone who has been
 * using the app for months — asking them for a child they already entered — would be the most
 * visible possible regression. An account that already has a name and a child is complete by
 * evidence, whatever the marker says.
 */
class OnboardingStateTest {

    private fun user(name: String = "Olya", completedAt: String? = null) = User(
        id = "u1",
        email = "olya@example.com",
        name = name,
        role = "mom",
        colorCode = "#FF4081",
        onboardingCompletedAt = completedAt
    )

    @Test
    fun `a brand new account is asked`() {
        assertTrue(OnboardingState.isNeeded(user(name = ""), hasChildInfo = false))
    }

    @Test
    fun `an account that finished is never asked again`() {
        assertFalse(
            OnboardingState.isNeeded(
                user(completedAt = "2026-08-23T09:00:00"),
                hasChildInfo = false
            )
        )
    }

    @Test
    fun `an existing installation with real data is not ambushed on upgrade`() {
        // The marker is null because this column did not exist when they signed up.
        assertFalse(OnboardingState.isNeeded(user(), hasChildInfo = true))
    }

    @Test
    fun `a named account with no child is still asked`() {
        // Named but childless: they signed in and stopped. The wizard is the point.
        assertTrue(OnboardingState.isNeeded(user(), hasChildInfo = false))
    }

    @Test
    fun `a child but no name is still asked, because the name is the one required field`() {
        assertTrue(OnboardingState.isNeeded(user(name = "  "), hasChildInfo = true))
    }

    @Test
    fun `a null user is not asked, because there is nobody to ask`() {
        // Sign-out and cold-start races both land here. Showing a wizard to nobody would
        // strand the app on a screen with no account behind it.
        assertFalse(OnboardingState.isNeeded(null, hasChildInfo = false))
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

```bash
JAVA_HOME="C:\Program Files\Android\Android Studio1\jbr" ./gradlew testDebugUnitTest --tests "com.coparently.app.domain.onboarding.OnboardingStateTest"
```
Expected: `Unresolved reference: OnboardingState`.

- [ ] **Step 3: Implement**

Create `app/src/main/java/com/coparently/app/domain/onboarding/OnboardingState.kt`:

```kotlin
package com.coparently.app.domain.onboarding

import com.coparently.app.domain.model.User

/**
 * Decides whether a parent should be walked through the first-run questionnaire.
 *
 * Kept in the domain layer and free of Android because it gates the app's start destination:
 * getting it wrong shows a questionnaire to a long-standing user, or hides it from a new one,
 * and neither should depend on a device to test.
 */
object OnboardingState {

    /**
     * @param user The signed-in user, or null before the profile has loaded
     * @param hasChildInfo Whether this account has at least one child record
     * @return true when the wizard should run
     */
    fun isNeeded(user: User?, hasChildInfo: Boolean): Boolean {
        if (user == null) return false
        if (!user.onboardingCompletedAt.isNullOrBlank()) return false

        // Complete by evidence. Every installation that predates this column upgrades with a
        // null marker; an account that already carries a name and a child has plainly been
        // through this once, whatever the marker says.
        val named = user.name.isNotBlank()
        return !(named && hasChildInfo)
    }
}
```

- [ ] **Step 4: Run it and confirm it passes**

Expected: 6 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/coparently/app/domain/onboarding/ app/src/test/java/com/coparently/app/domain/onboarding/
git commit -m "feat(onboarding): decide who is asked, without ambushing an existing install"
```

---

### Task 3: The wizard's state — steps, validation, persistence

**Files:**
- Create: `app/src/main/java/com/coparently/app/presentation/onboarding/OnboardingStep.kt`
- Create: `app/src/main/java/com/coparently/app/presentation/onboarding/OnboardingViewModel.kt`
- Test: `app/src/test/java/com/coparently/app/presentation/onboarding/OnboardingViewModelTest.kt`

**Interfaces:**
- Consumes: `OnboardingState` (Task 2); `UserRepository`, `ChildInfoRepository`; B1's `MedicalProfile`, `User`, `ChildInfo`, `EmergencyContact`.
- Produces, for Task 4: `enum class OnboardingStep { Intro, Profile, Child, Relatives, Custody, CoParent }`; `OnboardingUiState`; `OnboardingViewModel` with `next()`, `back()`, `skip()`, `finish()` and the field-update callbacks.

**Read spec §8 before writing the validation.** This plan implements "only the parent's name is mandatory". If that has been overruled, the change is confined to `canAdvance` below.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/coparently/app/presentation/onboarding/OnboardingViewModelTest.kt`:

```kotlin
package com.coparently.app.presentation.onboarding

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The wizard's rules, which are almost entirely about what a parent is allowed to leave out.
 *
 * Item 3 asks for a great deal — a blood group, hereditary conditions, a phone number for an
 * aunt — and item 4 explains that it is collected for the parent's own benefit in an emergency.
 * Data gathered for someone's benefit must not lock them out of their calendar, so the only
 * thing that blocks progress is the one field the app genuinely cannot work without: a name to
 * put on the events.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var viewModel: OnboardingViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val userRepository = mockk<com.coparently.app.domain.repository.UserRepository>(relaxed = true) {
            coEvery { getCurrentUser() } returns null
        }
        val childInfoRepository =
            mockk<com.coparently.app.domain.repository.ChildInfoRepository>(relaxed = true) {
                coEvery { getAllChildInfo() } returns flowOf(emptyList())
            }
        viewModel = OnboardingViewModel(userRepository, childInfoRepository)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `the intro can always be advanced, because it asks for nothing`() = runTest(dispatcher) {
        advanceUntilIdle()
        assertEquals(OnboardingStep.Intro, viewModel.uiState.value.step)
        assertTrue(viewModel.uiState.value.canAdvance)
    }

    @Test
    fun `a blank name is the only thing that blocks progress`() = runTest(dispatcher) {
        viewModel.next()
        advanceUntilIdle()
        assertEquals(OnboardingStep.Profile, viewModel.uiState.value.step)

        viewModel.updateName("   ")
        assertFalse(viewModel.uiState.value.canAdvance)

        viewModel.updateName("Olya")
        assertTrue(viewModel.uiState.value.canAdvance)
    }

    @Test
    fun `nothing medical is ever required`() = runTest(dispatcher) {
        viewModel.next()
        viewModel.updateName("Olya")
        advanceUntilIdle()

        // No date of birth, no phone, no blood type, no allergies - and still advanceable.
        assertTrue(viewModel.uiState.value.canAdvance)
    }

    @Test
    fun `every step after the profile can be skipped outright`() = runTest(dispatcher) {
        repeat(2) { viewModel.next() }
        viewModel.updateName("Olya")
        advanceUntilIdle()

        listOf(
            OnboardingStep.Child,
            OnboardingStep.Relatives,
            OnboardingStep.Custody,
            OnboardingStep.CoParent
        ).forEach { expected ->
            assertEquals(expected, viewModel.uiState.value.step)
            assertTrue(viewModel.uiState.value.canSkip, "$expected must be skippable")
            viewModel.skip()
            advanceUntilIdle()
        }
    }

    @Test
    fun `back never leaves the wizard from its first step`() = runTest(dispatcher) {
        advanceUntilIdle()
        viewModel.back()
        advanceUntilIdle()
        assertEquals(OnboardingStep.Intro, viewModel.uiState.value.step)
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Expected: `Unresolved reference: OnboardingViewModel`.

- [ ] **Step 3: Write the step enum**

Create `app/src/main/java/com/coparently/app/presentation/onboarding/OnboardingStep.kt`:

```kotlin
package com.coparently.app.presentation.onboarding

/**
 * The wizard's steps, in the order item 3 lists them.
 *
 * [Custody] and [CoParent] do not render inside the wizard: they hand off to
 * `CustodySetupScreen` and `PairingScreen`, which already do those jobs and are reachable from
 * Settings anyway. They are steps here so the progress indicator tells the truth about how much
 * is left.
 */
enum class OnboardingStep {
    /** Explains what is about to be asked, and why — item 4's text in full. */
    Intro,

    /** The parent's own details. The only step with a required field. */
    Profile,

    /** The child's name, date of birth, allergies and medical profile. */
    Child,

    /** Emergency contacts, saved onto the child's record so both parents may edit them. */
    Relatives,

    /** Hands off to `CustodySetupScreen`. */
    Custody,

    /** Hands off to `PairingScreen`. Finishing here finishes onboarding. */
    CoParent;

    /** True when this step asks for something the app cannot proceed without. */
    val isSkippable: Boolean get() = this != Intro && this != Profile
}
```

- [ ] **Step 4: Write the ViewModel**

Create `OnboardingViewModel.kt` exposing:

```kotlin
data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.Intro,
    val name: String = "",
    val dateOfBirth: LocalDate? = null,
    val phone: String = "",
    val allergies: List<String> = emptyList(),
    val medicalProfile: MedicalProfile = MedicalProfile(),
    val childName: String = "",
    val childDateOfBirth: LocalDate? = null,
    val childAllergies: List<String> = emptyList(),
    val childMedicalProfile: MedicalProfile = MedicalProfile(),
    val relatives: List<EmergencyContact> = emptyList(),
    val isSaving: Boolean = false
) {
    /** Only a blank parent name blocks progress — see the class KDoc and spec §8. */
    val canAdvance: Boolean
        get() = step != OnboardingStep.Profile || name.isNotBlank()

    val canSkip: Boolean get() = step.isSkippable
}
```

Behaviour:

- `next()` — advances when `canAdvance`, **persisting the current step's data first** so a kill mid-wizard resumes rather than restarting (spec §7's device check). `Profile` writes through `UserRepository.updateUser` on a **freshly read** `User`, applying only the fields this wizard owns — the same discipline B1's `ProfileViewModel.save()` had to learn, and for the same reason: a whole-object write carries `partnerId` and would undo an unpair.
- `back()` — steps back; a no-op on `Intro`.
- `skip()` — advances without persisting, only when `canSkip`.
- `finish()` — stamps `onboardingCompletedAt` with `LocalDateTime.now()` formatted ISO and saves, then signals the host to leave.

Prefill from the existing account on init: an account part-way through, or one that already has a name from Google sign-in, must not be asked to retype it.

- [ ] **Step 5: Run it and confirm it passes**

Expected: 5 tests PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/coparently/app/presentation/onboarding/ app/src/test/java/com/coparently/app/presentation/onboarding/
git commit -m "feat(onboarding): the wizard's steps, and the single field that blocks them"
```

---

### Task 4: The wizard's screen and its strings

**Files:**
- Create: `app/src/main/java/com/coparently/app/presentation/onboarding/OnboardingScreen.kt`
- Create: `app/src/main/res/values/onboarding_strings.xml` (+ `values-cs`, `values-de`, `values-ru`, `values-uk`)

**Interfaces:**
- Consumes: `OnboardingViewModel`, `OnboardingStep`, `OnboardingUiState` (Task 3); B1's `MedicalProfileEditor`, `AllergyEditor`, `EmergencyContactEditor`, `DatePickerDialog`; `presentation/common/DesignSystem.kt`.
- Produces, for Task 5: `OnboardingScreen(onFinished: () -> Unit, onOpenCustodySetup: () -> Unit, onOpenPairing: () -> Unit, viewModel: OnboardingViewModel = hiltViewModel())`.

One `Scaffold`: a top bar carrying step progress, a scrolling body switching on `uiState.step`, and a bottom bar with Back / Skip / Next. The three data steps reuse B1's editors — **do not write new field editors**; if one is missing, that is a signal to stop and ask.

**The footnote is item 4 and is the reason the questionnaire is acceptable at all.** Full text on `Intro`; a one-line version under every data-collecting step. It must not claim the data is encrypted (it is not) and must not claim only the user can see it (the co-parent can, by design, and item 5 requires it).

- [ ] **Step 1: Write the English strings**

Create `app/src/main/res/values/onboarding_strings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- First-run questionnaire. -->
<resources>
    <string name="onboarding_title">Getting set up</string>
    <string name="onboarding_progress">Step %1$d of %2$d</string>
    <string name="onboarding_next">Next</string>
    <string name="onboarding_back">Back</string>
    <string name="onboarding_skip">Skip</string>
    <string name="onboarding_finish">Finish</string>
    <string name="onboarding_intro_title">A few questions before you start</string>
    <string name="onboarding_intro_body">CoPlanly can hold your family\'s details so that whoever is with your child can act if something unexpected happens — a blood group, an allergy, a grandparent\'s phone number.\n\nAll of it is optional except your name, and you can fill it in later from Settings.</string>
    <string name="onboarding_footnote">Kept for you and your co-parent, in case of an emergency.</string>
    <string name="onboarding_profile_title">About you</string>
    <string name="onboarding_profile_name_required">Your name is the one thing the app needs — it labels every event and expense.</string>
    <string name="onboarding_child_title">About your child</string>
    <string name="onboarding_child_body">You can add medications, activities and school details later, from Settings.</string>
    <string name="onboarding_relatives_title">People who can help</string>
    <string name="onboarding_relatives_body">A grandparent, an aunt, an uncle — anyone who might collect your child or be called in an emergency.</string>
    <string name="onboarding_custody_title">Your custody schedule</string>
    <string name="onboarding_custody_body">Set out which days your child is with each of you. You can change it at any time, and your co-parent has to agree to a change.</string>
    <string name="onboarding_custody_open">Set up the schedule</string>
    <string name="onboarding_coparent_title">Invite your co-parent</string>
    <string name="onboarding_coparent_body">Send them a link or a code. Once they join, they will see everything you have filled in here and can add to it.</string>
    <string name="onboarding_coparent_open">Send an invitation</string>
</resources>
```

- [ ] **Step 2: Write the Russian strings**

Create `app/src/main/res/values-ru/onboarding_strings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="onboarding_title">Настройка</string>
    <string name="onboarding_progress">Шаг %1$d из %2$d</string>
    <string name="onboarding_next">Далее</string>
    <string name="onboarding_back">Назад</string>
    <string name="onboarding_skip">Пропустить</string>
    <string name="onboarding_finish">Готово</string>
    <string name="onboarding_intro_title">Несколько вопросов перед началом</string>
    <string name="onboarding_intro_body">CoPlanly может хранить данные вашей семьи, чтобы тот, кто окажется рядом с ребёнком, смог действовать, если случится непредвиденное, — группу крови, аллергию, телефон бабушки.\n\nВсё это необязательно, кроме вашего имени, и это можно заполнить позже в настройках.</string>
    <string name="onboarding_footnote">Хранится для вас и второго родителя, на случай непредвиденных обстоятельств.</string>
    <string name="onboarding_profile_title">О себе</string>
    <string name="onboarding_profile_name_required">Имя — единственное, что нужно приложению: им подписывается каждое событие и каждый расход.</string>
    <string name="onboarding_child_title">О ребёнке</string>
    <string name="onboarding_child_body">Лекарства, занятия и данные об учебном заведении можно добавить позже, в настройках.</string>
    <string name="onboarding_relatives_title">Кто может помочь</string>
    <string name="onboarding_relatives_body">Бабушка, дедушка, тётя, дядя — любой, кто может забрать ребёнка или кому позвонят в экстренном случае.</string>
    <string name="onboarding_custody_title">Расписание опеки</string>
    <string name="onboarding_custody_body">Укажите, в какие дни ребёнок с кем из вас. Это можно изменить в любой момент, и второй родитель должен будет согласиться с изменением.</string>
    <string name="onboarding_custody_open">Составить расписание</string>
    <string name="onboarding_coparent_title">Пригласите второго родителя</string>
    <string name="onboarding_coparent_body">Отправьте ссылку или код. Как только он присоединится, он увидит всё, что вы здесь заполнили, и сможет дополнить.</string>
    <string name="onboarding_coparent_open">Отправить приглашение</string>
</resources>
```

- [ ] **Step 3: Write the Czech, German and Ukrainian strings**

Create the same 21 keys in `values-cs`, `values-de` and `values-uk`. **Before writing them, open a neighbouring file in each locale** — `values-cs/settings_account_strings.xml`, `values-de/home_strings.xml`, `values-uk/pairing_strings.xml` — and match its register: German is informal ("du"), Czech and Ukrainian use polite plural imperatives. Do not machine-translate into a different register than the file's neighbours.

Keep the `\n\n` in `onboarding_intro_body` and the `%1$d`/`%2$d` in `onboarding_progress` in every locale.

- [ ] **Step 4: Write the screen**

Create `OnboardingScreen.kt`. `Intro` shows the title, the body and no footnote (the body *is* the explanation). `Profile`, `Child` and `Relatives` show their title, their body where one exists, the reused editors, and the footnote at the bottom. `Custody` and `CoParent` show their title, their body and a button calling `onOpenCustodySetup` / `onOpenPairing`.

The bottom bar shows Back on every step but `Intro`; Skip when `uiState.canSkip`; and Next — labelled `onboarding_finish` on `CoParent` — enabled on `uiState.canAdvance`.

- [ ] **Step 5: Verify every key resolves in five locales**

```bash
for k in $(grep -o 'name="[a-z_]*"' app/src/main/res/values/onboarding_strings.xml | sed 's/name="//;s/"//'); do n=$(git grep -l "name=\"$k\"" -- 'app/src/main/res/values*/*.xml' | wc -l); [ "$n" = "5" ] || echo "MISSING: $k -> $n"; done; echo "--- any line above is a gap ---"
```

- [ ] **Step 6: Confirm no hardcoded text survives**

```bash
grep -nE 'Text\(|label = |placeholder|contentDescription = ' app/src/main/java/com/coparently/app/presentation/onboarding/OnboardingScreen.kt | grep '"'
```
Expected: no output.

- [ ] **Step 7: Build and commit**

```bash
JAVA_HOME="C:\Program Files\Android\Android Studio1\jbr" ./gradlew assembleDebug testDebugUnitTest
git add app/src/main/java/com/coparently/app/presentation/onboarding/ app/src/main/res/values*/onboarding_strings.xml
git commit -m "feat(onboarding): ask the questions, and say why they are being asked"
```

---

### Task 5: Wiring it into the app's start

**Files:**
- Modify: `app/src/main/java/com/coparently/app/presentation/navigation/NavGraph.kt`
- Modify or create: the view model backing `NavGraph`'s start-destination decision (read `AuthStateViewModel` first)

**Interfaces:**
- Consumes: `OnboardingState.isNeeded` (Task 2); `OnboardingScreen` (Task 4).
- Produces: route `Screen.Onboarding` (`"onboarding"`).

- [ ] **Step 1: Add the route**

In `NavGraph.kt`'s `Screen` sealed class:

```kotlin
    data object Onboarding : Screen("onboarding")
```

and a `composable` block rendering:

```kotlin
                OnboardingScreen(
                    onFinished = {
                        navController.navigate(Screen.Home.route) {
                            popUpTo(Screen.Onboarding.route) { inclusive = true }
                        }
                    },
                    onOpenCustodySetup = { navController.navigate(Screen.CustodySetup.route) },
                    onOpenPairing = { navController.navigate(Screen.Pairing.createRoute(null)) }
                )
```

Read `Screen.Pairing`'s existing route builder rather than assuming its shape.

`Onboarding` must **not** be added to `BottomNavDestination.topLevelRoutes` — the bottom bar hides itself for any route not listed there, which is what this screen wants.

- [ ] **Step 2: Extend the start-destination decision**

The current branch is:

```kotlin
    val startDestination = when {
        isLoading -> Screen.Loading.route
        isAuthenticated == true -> Screen.Home.route
        else -> Screen.Auth.route
    }
```

Onboarding becomes a fourth branch, between authentication and Home. **The check is asynchronous** — it reads the user row and whether any child info exists — so it must resolve *inside* the loading state rather than after it. A `null`/unknown answer must map to `Loading`, never to `Home`: flashing the dashboard and then replacing it with a questionnaire is worse than a moment's spinner.

Expose it from the same view model that already owns `isAuthenticated`/`isLoading` so there is one source for the whole decision, rather than a second one that can disagree.

- [ ] **Step 3: Build and check the four states**

```bash
JAVA_HOME="C:\Program Files\Android\Android Studio1\jbr" ./gradlew assembleDebug testDebugUnitTest lint detekt
```

Then reason through each start state and record your reasoning in the report: signed out; signed in and needing onboarding; signed in and not needing it; and the window before the answer is known.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/coparently/app/presentation/navigation/
git commit -m "feat(onboarding): send a new parent through the questionnaire before the dashboard"
```

---

### Task 6: Full verification, and the device walk

**Files:** none changed unless a check fails.

- [ ] **Step 1: Everything offline**

```bash
JAVA_HOME="C:\Program Files\Android\Android Studio1\jbr" ./gradlew clean assembleDebug testDebugUnitTest lint detekt
```
Report the test totals, and whether detekt or lint names any file in `git diff --name-only main..HEAD`.

- [ ] **Step 2: Locale completeness**

Re-run Task 4's grep. Expected: only the trailing line.

- [ ] **Step 3: The migration, on the device**

```bash
JAVA_HOME="C:\Program Files\Android\Android Studio1\jbr" ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.coparently.app.data.local.CoPlanlyDatabaseMigrationTest
```

- [ ] **Step 4: The walk — this one is the point**

A wizard's value is entirely in how it feels to walk. Install and do all four:

1. **Fresh install, end to end.** Every step, filling something in each. Land on Home. Kill and relaunch: the wizard must not reappear.
2. **Fresh install, minimum.** Type a name, skip everything else. Land on Home unpaired, where item 8's "connect your co-parent" CTA lives.
3. **Kill mid-wizard.** Force-stop on step 3 and relaunch: it must resume with what was already entered, not restart empty.
4. **An existing account.** Sign in as an account that already has a name and a child. The wizard must **never** appear.

Then switch the device to Russian and re-walk step 1 — the whole wizard, footnote included, must be Russian.

- [ ] **Step 5: Record the walk**

Append the outcome of each check to the spec's §7, marking any that failed.

```bash
git add docs/superpowers/specs/2026-08-23-onboarding-wizard-design.md
git commit -m "docs: record the onboarding device walk"
```

---

## Notes for the reviewer

**The open question is spec §8** — whether only the parent's name is mandatory, or the whole about-you block. This plan implements the former; the latter is one predicate in `OnboardingUiState.canAdvance`.

**Two disciplines carried from B1, both learned from defects it shipped.** A whole-object `User` write must come from a freshly read row, or it resurrects a `partnerId` cleared while the screen was open. And a field added to `UserRepositoryImpl` must be carried through **all four** mappings plus `writeLocalProfile`'s fresh-row branch — B1 missed the last one and the parent's profile was write-only for a full review cycle.

**What this package deliberately does not do:** add any field B1 lacks; change the sharing policy; offer a way to re-run onboarding; or gate the app on pairing.
