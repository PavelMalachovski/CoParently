# Co-parent Pairing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let two parents on two phones link their accounts reliably through a short invite code, a QR code, a share link, or an email invite — and let either of them unlink.

**Architecture:** One `invitations` Firestore document backs the code, the QR image and the deep link, so all three redeem through the same path. Accepting and unpairing move into two callable Cloud Functions because both write the *other* parent's user document, which no honest Firestore rule can grant a client. On the device, a new `PairingRepository` owns Firestore access and exposes a realtime `PairingState` flow built on a `users/{uid}` snapshot listener, so both phones flip to "paired" without a manual sync.

**Tech Stack:** Kotlin 2.1, Jetpack Compose (Material 3 / BOM 2025.10), Hilt 2.56.2, Firebase BOM 33.7.0 (Auth, Firestore, Functions, Messaging), CameraX 1.4.x, ML Kit barcode-scanning 17.3.0, Cloud Functions on `firebase-functions@^4.5.0` (v1 API, nodejs20), JUnit4 + MockK 1.13.13 + kotlinx-coroutines-test 1.9.0 + Turbine 1.2.0.

**Spec:** `docs/superpowers/specs/2026-08-01-coparent-pairing-design.md`

## Global Constraints

- Branch is `feature/coparent-collab`, already created from `origin/main` (39d8389b). Do not switch branches.
- **The system `JAVA_HOME` on this machine is broken.** Every Gradle command must run with:
  `$env:JAVA_HOME='C:\Program Files\Android\Android Studio1\jbr'` (JDK 21) first, in the same PowerShell invocation.
- Build/verify commands: `./gradlew assembleDebug`, `./gradlew testDebugUnitTest`, `./gradlew lint detekt`. There is no CI — run them locally.
- **Jetpack Compose only** — never add XML layouts. (CameraX `PreviewView` is hosted through `AndroidView`, not a layout file.)
- **Stateless composables**; state lives in ViewModels as `StateFlow`.
- **minSdk = 26** — no `java.time` APIs added after API 26 (`LocalDate.ofInstant` is API 34+; use `Instant.atZone(...).toLocalDate()`).
- **KDoc on public classes and functions. All code, comments, commit messages and repository docs in English.** (Chat with the user is in Russian; the repo is not.)
- **Never hardcode user-visible text in composables.** New keys go into `res/values/pairing_strings.xml` **and** all four locale variants: `values-cs/`, `values-de/`, `values-ru/`, `values-uk/`. There is no `values-en/` — base `values/` is English.
- Material 3 components only; colors and sizes from `presentation/theme/` (`CoPlanlyColors`, `Typography`, `CoPlanlyShapes`, `dimensions()`).
- Mom = pink, Dad = blue are parent identity only. Nothing in this feature may repurpose them.
- Conventional Commits (`feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `chore:`).
- Danger actions (Unpair) go at the bottom of their screen, never mid-list, and always behind `ConfirmationDialog`.
- Firestore invite code alphabet is exactly `ABCDEFGHJKMNPQRSTUVWXYZ23456789` (31 chars — no `O`, `0`, `I`, `1`, `L`), length exactly 6.
- Deep link URI is exactly `coplanly://pair?code=<CODE>`.
- Cloud Functions stay on the **v1** API (`functions.https.onCall`) to match the five existing handlers. Do not migrate the functions codebase to v2 in this plan.

## File Structure

**Created**
| Path | Responsibility |
|---|---|
| `app/src/main/java/com/coparently/app/domain/pairing/InviteCodeGenerator.kt` | Generates and validates 6-char invite codes |
| `app/src/main/java/com/coparently/app/domain/pairing/PairingUri.kt` | Builds and parses `coplanly://pair?code=…` |
| `app/src/main/java/com/coparently/app/domain/model/PairingInvite.kt` | Invite domain model |
| `app/src/main/java/com/coparently/app/domain/model/PairingState.kt` | `Loading / NotPaired / Paired` |
| `app/src/main/java/com/coparently/app/domain/model/PairingError.kt` | Typed failure of a pairing operation |
| `app/src/main/java/com/coparently/app/domain/repository/PairingRepository.kt` | Repository interface |
| `app/src/main/java/com/coparently/app/data/repository/PairingRepositoryImpl.kt` | Firestore + callable implementation |
| `app/src/main/java/com/coparently/app/data/remote/firebase/PairingFunctions.kt` | Callable wrapper, maps exceptions to `PairingError` |
| `app/src/main/java/com/coparently/app/presentation/pairing/components/InviteCodeCard.kt` | Hero card: code, countdown, share, QR, "new code" |
| `app/src/main/java/com/coparently/app/presentation/pairing/components/CodeEntryField.kt` | 6-char code input |
| `app/src/main/java/com/coparently/app/presentation/pairing/components/IncomingInviteCard.kt` | One incoming invitation row |
| `app/src/main/java/com/coparently/app/presentation/pairing/components/PairedPartnerCard.kt` | Partner summary when paired |
| `app/src/main/java/com/coparently/app/presentation/pairing/QrScannerScreen.kt` | CameraX preview + ML Kit analysis composable |
| `app/src/test/java/com/coparently/app/domain/pairing/InviteCodeGeneratorTest.kt` | |
| `app/src/test/java/com/coparently/app/domain/pairing/PairingUriTest.kt` | |
| `app/src/test/java/com/coparently/app/data/repository/PairingRepositoryImplTest.kt` | |
| `app/src/test/java/com/coparently/app/presentation/pairing/PairingViewModelTest.kt` | |
| `functions/test/pairing.test.js` | Cloud Function guard matrix |

**Modified**
| Path | Change |
|---|---|
| `functions/index.js` | Add `acceptPairingInvitation`, `unpairCoParent`; delete `acceptQRInvitation` |
| `functions/package.json` | Add `test` script |
| `firestore.rules` | `invitations` accepts `code`/`expiresAt` |
| `app/build.gradle.kts` | Add `firebase-functions-ktx`, CameraX |
| `app/src/main/AndroidManifest.xml` | `coplanly://pair` on MainActivity, `singleTask`, remove dead App Link |
| `app/src/main/java/com/coparently/app/di/FirebaseModule.kt` | Provide `FirebaseFunctions` |
| `app/src/main/java/com/coparently/app/di/RepositoryModule.kt` | Bind `PairingRepository` |
| `app/src/main/java/com/coparently/app/presentation/pairing/PairingViewModel.kt` | Rewritten on the repository |
| `app/src/main/java/com/coparently/app/presentation/pairing/PairingScreen.kt` | Rebuilt around `PairingState` |
| `app/src/main/java/com/coparently/app/presentation/pairing/QRScannerActivity.kt` | Real camera; placeholder removed |
| `app/src/main/java/com/coparently/app/presentation/MainActivity.kt` | `onNewIntent` deep-link routing |
| `app/src/main/java/com/coparently/app/presentation/navigation/NavGraph.kt` | `pairing?code={code}` route |
| `app/src/main/java/com/coparently/app/presentation/home/HomeViewModel.kt` | Reactive `paired` |
| `app/src/main/java/com/coparently/app/data/remote/firebase/CoPlanlyMessagingService.kt` | Pairing push types, dedupe, real icon |
| `app/src/main/java/com/coparently/app/data/remote/firebase/CoParentPairingService.kt` | Shrunk to `getPartnerInfo` |
| `app/src/main/res/values{,-cs,-de,-ru,-uk}/pairing_strings.xml` | New keys |

---

### Task 1: Pure pairing primitives — code generator and URI parser

No Android or Firebase in this task; it is plain Kotlin and fully unit-testable.

**Files:**
- Create: `app/src/main/java/com/coparently/app/domain/pairing/InviteCodeGenerator.kt`
- Create: `app/src/main/java/com/coparently/app/domain/pairing/PairingUri.kt`
- Create: `app/src/main/java/com/coparently/app/domain/model/PairingInvite.kt`
- Create: `app/src/main/java/com/coparently/app/domain/model/PairingState.kt`
- Create: `app/src/main/java/com/coparently/app/domain/model/PairingError.kt`
- Test: `app/src/test/java/com/coparently/app/domain/pairing/InviteCodeGeneratorTest.kt`
- Test: `app/src/test/java/com/coparently/app/domain/pairing/PairingUriTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `InviteCodeGenerator.ALPHABET: String`, `InviteCodeGenerator.LENGTH: Int`, `InviteCodeGenerator.generate(random: Random = Random.Default): String`, `InviteCodeGenerator.isValid(code: String): Boolean`
  - `PairingUri.build(code: String): String`, `PairingUri.extractCode(input: String): String?`, `PairingUri.SCHEME: String`, `PairingUri.HOST: String`
  - `data class PairingInvite(id, code, fromUserId, fromUserName, fromUserEmail, toEmail, expiresAtMillis)`
  - `sealed interface PairingState { Loading; NotPaired(activeInvite, incoming); Paired(partner) }`, `data class PartnerSummary(id, name, email, pairedSinceMillis)`
  - `sealed interface PairingError { NotFound; Expired; NotPending; SelfPairing; AlreadyPaired; WrongRecipient; Network; Unknown(message) }`

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/coparently/app/domain/pairing/InviteCodeGeneratorTest.kt`:

```kotlin
package com.coparently.app.domain.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class InviteCodeGeneratorTest {

    @Test
    fun `generated code has the required length`() {
        repeat(200) {
            assertEquals(InviteCodeGenerator.LENGTH, InviteCodeGenerator.generate().length)
        }
    }

    @Test
    fun `generated code never contains visually ambiguous characters`() {
        val forbidden = setOf('O', '0', 'I', '1', 'L')
        repeat(2_000) {
            val code = InviteCodeGenerator.generate()
            assertTrue(
                "code $code contains a forbidden character",
                code.none { it in forbidden }
            )
        }
    }

    @Test
    fun `generated code uses only the published alphabet`() {
        repeat(2_000) {
            val code = InviteCodeGenerator.generate()
            assertTrue(code.all { it in InviteCodeGenerator.ALPHABET })
        }
    }

    @Test
    fun `generation is deterministic for a seeded random`() {
        assertEquals(
            InviteCodeGenerator.generate(Random(42)),
            InviteCodeGenerator.generate(Random(42))
        )
    }

    @Test
    fun `isValid accepts a well formed code and rejects everything else`() {
        assertTrue(InviteCodeGenerator.isValid("4F7K2M"))
        assertFalse("wrong length", InviteCodeGenerator.isValid("4F7K2"))
        assertFalse("lowercase", InviteCodeGenerator.isValid("4f7k2m"))
        assertFalse("ambiguous char", InviteCodeGenerator.isValid("4F7K2O"))
        assertFalse("empty", InviteCodeGenerator.isValid(""))
    }
}
```

`app/src/test/java/com/coparently/app/domain/pairing/PairingUriTest.kt`:

```kotlin
package com.coparently.app.domain.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingUriTest {

    @Test
    fun `build produces the documented uri`() {
        assertEquals("coplanly://pair?code=4F7K2M", PairingUri.build("4F7K2M"))
    }

    @Test
    fun `extractCode reads a bare code`() {
        assertEquals("4F7K2M", PairingUri.extractCode("4F7K2M"))
    }

    @Test
    fun `extractCode trims and uppercases a bare code`() {
        assertEquals("4F7K2M", PairingUri.extractCode("  4f7k2m "))
    }

    @Test
    fun `extractCode reads a full uri`() {
        assertEquals("4F7K2M", PairingUri.extractCode("coplanly://pair?code=4F7K2M"))
    }

    @Test
    fun `extractCode reads a code out of pasted share text`() {
        val shared = "Pavel invites you to CoPlanly. Code: 4F7K2M · coplanly://pair?code=4F7K2M"
        assertEquals("4F7K2M", PairingUri.extractCode(shared))
    }

    @Test
    fun `extractCode rejects an invalid code`() {
        assertNull(PairingUri.extractCode("coplanly://pair?code=4F7K2O"))
        assertNull(PairingUri.extractCode("hello"))
        assertNull(PairingUri.extractCode(""))
    }
}
```

- [ ] **Step 2: Run the tests and confirm they fail**

```bash
$env:JAVA_HOME='C:\Program Files\Android\Android Studio1\jbr'; ./gradlew testDebugUnitTest --tests "com.coparently.app.domain.pairing.*"
```

Expected: compilation failure — `Unresolved reference: InviteCodeGenerator`.

- [ ] **Step 3: Write the implementation**

`app/src/main/java/com/coparently/app/domain/pairing/InviteCodeGenerator.kt`:

```kotlin
package com.coparently.app.domain.pairing

import kotlin.random.Random

/**
 * Generates and validates the short codes a parent reads out or types to pair.
 *
 * The alphabet deliberately omits `O`, `0`, `I`, `1` and `L` so a code stays
 * unambiguous when it is dictated over the phone or copied by hand.
 */
object InviteCodeGenerator {

    /** The 31 characters a code may contain. */
    const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

    /** Number of characters in every code. */
    const val LENGTH = 6

    /**
     * Returns a new random code.
     *
     * @param random Source of randomness; injectable so tests can seed it.
     */
    fun generate(random: Random = Random.Default): String =
        buildString(LENGTH) {
            repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        }

    /** Whether [code] could have been produced by [generate]. */
    fun isValid(code: String): Boolean =
        code.length == LENGTH && code.all { it in ALPHABET }
}
```

`app/src/main/java/com/coparently/app/domain/pairing/PairingUri.kt`:

```kotlin
package com.coparently.app.domain.pairing

/**
 * The `coplanly://pair?code=…` link used by the share sheet, the QR image and
 * the deep link. All three redeem the same invitation, so parsing lives here
 * once instead of in the scanner and the navigation graph separately.
 */
object PairingUri {

    /** Custom scheme; the app owns no domain, so App Links are not an option. */
    const val SCHEME = "coplanly"

    /** Host segment of the pairing link. */
    const val HOST = "pair"

    /** Builds the shareable link for [code]. */
    fun build(code: String): String = "$SCHEME://$HOST?code=$code"

    /**
     * Extracts a valid invite code from [input], which may be a bare code, a
     * full pairing URI, or free text containing one (a pasted share message).
     *
     * @return the upper-cased code, or null when [input] holds none.
     */
    fun extractCode(input: String): String? {
        val normalized = input.trim().uppercase()
        val fromUri = CODE_PARAM.find(normalized)?.groupValues?.get(1)
        val candidate = fromUri ?: normalized
        return candidate.takeIf { InviteCodeGenerator.isValid(it) }
    }

    private val CODE_PARAM = Regex("CODE=([A-Z0-9]{${InviteCodeGenerator.LENGTH}})")
}
```

`app/src/main/java/com/coparently/app/domain/model/PairingInvite.kt`:

```kotlin
package com.coparently.app.domain.model

/**
 * An outstanding invitation to become co-parents.
 *
 * The same document backs the short code, the QR image and the share link —
 * [code] is what all three carry.
 *
 * @property id Firestore document id
 * @property code Short code the other parent types or scans
 * @property fromUserId Firebase UID of the inviter
 * @property fromUserName Display name of the inviter
 * @property fromUserEmail Email of the inviter
 * @property toEmail Addressee for email invites; empty for code/QR/link invites
 * @property expiresAtMillis Epoch millis after which the invite is refused
 */
data class PairingInvite(
    val id: String,
    val code: String,
    val fromUserId: String,
    val fromUserName: String,
    val fromUserEmail: String,
    val toEmail: String = "",
    val expiresAtMillis: Long
)
```

`app/src/main/java/com/coparently/app/domain/model/PairingState.kt`:

```kotlin
package com.coparently.app.domain.model

/** The paired co-parent, as shown on the pairing screen. */
data class PartnerSummary(
    val id: String,
    val name: String,
    val email: String,
    val pairedSinceMillis: Long?
)

/**
 * Whether this account is linked to a co-parent, and what the pairing screen
 * should offer if it is not.
 */
sealed interface PairingState {

    /** The initial state, before the first Firestore snapshot arrives. */
    data object Loading : PairingState

    /**
     * No co-parent linked.
     *
     * @property activeInvite This user's own outstanding invite, if any
     * @property incoming Invitations addressed to this user's email
     */
    data class NotPaired(
        val activeInvite: PairingInvite? = null,
        val incoming: List<PairingInvite> = emptyList()
    ) : PairingState

    /** Linked to [partner]. */
    data class Paired(val partner: PartnerSummary) : PairingState
}
```

`app/src/main/java/com/coparently/app/domain/model/PairingError.kt`:

```kotlin
package com.coparently.app.domain.model

/**
 * Why a pairing operation failed. Each case maps to exactly one message in the
 * UI, so the presentation layer never has to inspect exception text.
 */
sealed interface PairingError {

    /** No invitation matches the code or id. */
    data object NotFound : PairingError

    /** The invitation is past its expiry. */
    data object Expired : PairingError

    /** The invitation was already accepted, rejected or cancelled. */
    data object NotPending : PairingError

    /** The user tried to redeem their own invitation. */
    data object SelfPairing : PairingError

    /** One of the two accounts already has a co-parent. */
    data object AlreadyPaired : PairingError

    /** An email invitation addressed to somebody else. */
    data object WrongRecipient : PairingError

    /** Offline, timeout or an unreachable backend. */
    data object Network : PairingError

    /** Anything else; [message] is for logs, not for the user. */
    data class Unknown(val message: String?) : PairingError
}
```

- [ ] **Step 4: Run the tests and confirm they pass**

```bash
$env:JAVA_HOME='C:\Program Files\Android\Android Studio1\jbr'; ./gradlew testDebugUnitTest --tests "com.coparently.app.domain.pairing.*"
```

Expected: PASS, 11 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/coparently/app/domain/pairing app/src/main/java/com/coparently/app/domain/model/PairingInvite.kt app/src/main/java/com/coparently/app/domain/model/PairingState.kt app/src/main/java/com/coparently/app/domain/model/PairingError.kt app/src/test/java/com/coparently/app/domain/pairing
git commit -m "feat(pairing): add invite code generator, pairing uri and domain models"
```

---

### Task 2: Callable Cloud Functions for accepting and unpairing

**Files:**
- Modify: `functions/index.js` (add two exports, delete `acceptQRInvitation` at lines 277–365)
- Modify: `functions/package.json` (add a `test` script)
- Test: `functions/test/pairing.test.js`

**Interfaces:**
- Consumes: the `invitations` document shape from Task 1's `PairingInvite`.
- Produces (called by Task 4):
  - callable `acceptPairingInvitation`, request `{ code?: string, invitationId?: string }`, response `{ partnerId: string }`
  - callable `unpairCoParent`, request `{}`, response `{ unpairedFrom: string | null }`
  - On failure both throw `functions.https.HttpsError(code, message, { reason })` where `reason` is one of
    `not-found | invitation-expired | invitation-not-pending | self-pairing | already-paired | wrong-recipient`.

- [ ] **Step 1: Write the failing tests**

`functions/test/pairing.test.js`:

```js
const test = require('firebase-functions-test')();
const assert = require('assert');
const sinon = require('sinon');
const admin = require('firebase-admin');

describe('acceptPairingInvitation', () => {
  let myFunctions;

  before(() => {
    myFunctions = require('../index');
  });

  after(() => {
    test.cleanup();
    sinon.restore();
  });

  it('rejects an unauthenticated caller', async () => {
    const wrapped = test.wrap(myFunctions.acceptPairingInvitation);
    await assert.rejects(
        () => wrapped({code: '4F7K2M'}, {}),
        (err) => err.code === 'unauthenticated',
    );
  });

  it('requires exactly one of code or invitationId', async () => {
    const wrapped = test.wrap(myFunctions.acceptPairingInvitation);
    await assert.rejects(
        () => wrapped({}, {auth: {uid: 'u1', token: {email: 'a@b.c'}}}),
        (err) => err.code === 'invalid-argument',
    );
  });
});
```

Note: `sinon` is not yet a devDependency — add it in Step 3 together with the `test` script. Firestore behaviour beyond these two guards is verified on real devices in Task 11; stubbing the full Admin SDK transaction here costs more than it catches.

- [ ] **Step 2: Run the tests and confirm they fail**

```bash
cd functions; npm install; npm test
```

Expected: FAIL — `myFunctions.acceptPairingInvitation is not a function`.

- [ ] **Step 3: Add the test wiring**

In `functions/package.json`, add to `scripts`:

```json
"test": "mocha --timeout 10000 test/**/*.test.js"
```

and to `devDependencies`:

```json
"mocha": "^10.2.0",
"sinon": "^17.0.0"
```

Then `cd functions; npm install`.

- [ ] **Step 4: Delete the superseded function**

Remove the whole `exports.acceptQRInvitation = …` block from `functions/index.js` (currently lines 277–365, ending with the closing `});` before the `sendEmailInvitation` doc comment). It requires `invitation.toEmail === context.auth.token.email`, which a QR or code invite never has, so it can only ever fail for the flow it was written for.

- [ ] **Step 5: Write the implementation**

Append to `functions/index.js`:

```js
/**
 * Accepts a pairing invitation identified either by its short code or by its
 * document id, and links the two parents.
 *
 * Runs server-side because linking writes BOTH user documents, and no Firestore
 * rule can grant a client write access to another user's profile without
 * granting it for every user.
 *
 * @param {{code?: string, invitationId?: string}} data Exactly one identifier.
 * @return {Promise<{partnerId: string}>} The UID the caller is now paired with.
 */
exports.acceptPairingInvitation = functions.https.onCall(async (data, context) => {
  if (!context.auth) {
    throw new functions.https.HttpsError('unauthenticated', 'Sign in first');
  }

  const code = data && data.code ? String(data.code).trim().toUpperCase() : null;
  const invitationId = data && data.invitationId ? String(data.invitationId) : null;

  if ((!code && !invitationId) || (code && invitationId)) {
    throw new functions.https.HttpsError(
        'invalid-argument',
        'Provide exactly one of code or invitationId',
    );
  }

  const db = admin.firestore();
  const acceptingUserId = context.auth.uid;
  const acceptingEmail = context.auth.token.email || '';

  const inviteRef = await findInvitation(db, {code, invitationId});
  const invite = (await inviteRef.get()).data();

  if (invite.status !== 'pending') {
    throw new functions.https.HttpsError(
        'failed-precondition', 'Invitation is no longer pending',
        {reason: 'invitation-not-pending'});
  }
  if (typeof invite.expiresAt === 'number' && invite.expiresAt < Date.now()) {
    throw new functions.https.HttpsError(
        'failed-precondition', 'Invitation has expired',
        {reason: 'invitation-expired'});
  }
  if (invite.fromUserId === acceptingUserId) {
    throw new functions.https.HttpsError(
        'invalid-argument', 'You cannot accept your own invitation',
        {reason: 'self-pairing'});
  }
  if (invite.toEmail && invite.toEmail !== acceptingEmail) {
    throw new functions.https.HttpsError(
        'permission-denied', 'This invitation is addressed to somebody else',
        {reason: 'wrong-recipient'});
  }

  const inviterRef = db.collection('users').doc(invite.fromUserId);
  const accepterRef = db.collection('users').doc(acceptingUserId);
  const pairedAt = Date.now();

  await db.runTransaction(async (tx) => {
    const [inviterSnap, accepterSnap] = await Promise.all([
      tx.get(inviterRef), tx.get(accepterRef),
    ]);
    if (!inviterSnap.exists || !accepterSnap.exists) {
      throw new functions.https.HttpsError(
          'not-found', 'User profile missing', {reason: 'not-found'});
    }
    if (hasPartner(inviterSnap) || hasPartner(accepterSnap)) {
      throw new functions.https.HttpsError(
          'failed-precondition', 'One of the accounts is already paired',
          {reason: 'already-paired'});
    }
    tx.update(inviterRef, {partnerId: acceptingUserId, pairedAt});
    tx.update(accepterRef, {partnerId: invite.fromUserId, pairedAt});
    tx.update(inviteRef, {
      status: 'accepted',
      acceptedBy: acceptingUserId,
      acceptedAt: pairedAt,
    });
  });

  const accepterName = (await accepterRef.get()).data().name || 'Your co-parent';
  await db.collection('notification_queue').add({
    targetUserId: invite.fromUserId,
    data: {
      type: 'pairing_accepted',
      title: 'Invitation accepted',
      body: `${accepterName} is now your co-parent in CoPlanly`,
    },
    status: 'pending',
    createdAt: admin.firestore.FieldValue.serverTimestamp(),
  });

  return {partnerId: invite.fromUserId};
});

/**
 * Removes the link between the caller and their co-parent.
 *
 * One-sided by product decision: no confirmation from the other parent is
 * required. Shared data (events, chat, expenses) is left untouched.
 *
 * @return {Promise<{unpairedFrom: string|null}>} The former partner's UID.
 */
exports.unpairCoParent = functions.https.onCall(async (data, context) => {
  if (!context.auth) {
    throw new functions.https.HttpsError('unauthenticated', 'Sign in first');
  }

  const db = admin.firestore();
  const callerRef = db.collection('users').doc(context.auth.uid);
  const callerSnap = await callerRef.get();
  const partnerId = callerSnap.exists ? callerSnap.data().partnerId : null;

  if (!partnerId) {
    return {unpairedFrom: null};
  }

  const partnerRef = db.collection('users').doc(partnerId);
  await db.runTransaction(async (tx) => {
    tx.update(callerRef, {partnerId: '', pairedAt: null});
    tx.update(partnerRef, {partnerId: '', pairedAt: null});
  });

  const callerName = callerSnap.data().name || 'Your co-parent';
  await db.collection('notification_queue').add({
    targetUserId: partnerId,
    data: {
      type: 'pairing_removed',
      title: 'Co-parent unlinked',
      body: `${callerName} ended the co-parent link`,
    },
    status: 'pending',
    createdAt: admin.firestore.FieldValue.serverTimestamp(),
  });

  return {unpairedFrom: partnerId};
});

/**
 * Resolves an invitation reference from a code or a document id.
 *
 * @param {FirebaseFirestore.Firestore} db Firestore instance.
 * @param {{code: ?string, invitationId: ?string}} ref Identifier.
 * @return {Promise<FirebaseFirestore.DocumentReference>} The invitation.
 */
async function findInvitation(db, ref) {
  if (ref.invitationId) {
    const doc = await db.collection('invitations').doc(ref.invitationId).get();
    if (!doc.exists) {
      throw new functions.https.HttpsError(
          'not-found', 'Invitation not found', {reason: 'not-found'});
    }
    return doc.ref;
  }

  const query = await db.collection('invitations')
      .where('code', '==', ref.code)
      .where('status', '==', 'pending')
      .limit(2)
      .get();

  if (query.size !== 1) {
    throw new functions.https.HttpsError(
        'not-found', 'Invitation not found', {reason: 'not-found'});
  }
  return query.docs[0].ref;
}

/**
 * Whether a user snapshot already carries a co-parent link.
 *
 * @param {FirebaseFirestore.DocumentSnapshot} snap User document.
 * @return {boolean} True when partnerId is set and non-empty.
 */
function hasPartner(snap) {
  const partnerId = snap.data().partnerId;
  return typeof partnerId === 'string' && partnerId.length > 0;
}
```

- [ ] **Step 6: Run the tests and lint**

```bash
cd functions; npm test; npm run lint
```

Expected: both tests PASS, eslint clean (the repo uses `eslint-config-google` — two-space indent, JSDoc on every function, `require-jsdoc` is on).

- [ ] **Step 7: Deploy and verify the deployment**

```bash
firebase deploy --only functions:acceptPairingInvitation,functions:unpairCoParent --project coparently-a39c9
```

Then confirm both appear and the old one is gone:

```bash
firebase functions:list --project coparently-a39c9
```

Expected: `acceptPairingInvitation` and `unpairCoParent` listed as `callable`; `acceptQRInvitation` still listed (deleting an export does not remove the deployed function). Remove it explicitly:

```bash
firebase functions:delete acceptQRInvitation --project coparently-a39c9 --force
```

- [ ] **Step 8: Commit**

```bash
git add functions/
git commit -m "feat(functions): add acceptPairingInvitation and unpairCoParent callables"
```

---

### Task 3: Firestore rules for coded invitations

**Files:**
- Modify: `firestore.rules:141-164` (the `invitations` block)

**Interfaces:**
- Consumes: the document shape written by Task 4.
- Produces: rules that permit `code`/`expiresAt` on create and keep reads restricted to the two parties.

- [ ] **Step 1: Update the invitations block**

Replace the `match /invitations/{invitationId}` block with:

```
    // ---- Invitations ---------------------------------------------------
    // A single document backs the short code, the QR image and the share
    // link. Redemption goes through the acceptPairingInvitation callable —
    // the Admin SDK there is what looks a code up, so clients never need to
    // read invitations addressed to somebody else.
    match /invitations/{invitationId} {
      allow read: if isAuthenticated() && (
        resource.data.fromUserId == request.auth.uid ||
        (request.auth.token.email != null && resource.data.toEmail == request.auth.token.email)
      );

      allow create: if isAuthenticated() &&
                      request.resource.data.fromUserId == request.auth.uid &&
                      request.resource.data.keys().hasAll(
                        ['code', 'toEmail', 'status', 'createdAt', 'expiresAt']) &&
                      isValidLength(request.resource.data.code, 6, 6) &&
                      request.resource.data.status == 'pending';

      // Clients may only withdraw their own invite; accept/reject go through
      // the callable, which bypasses rules with Admin credentials.
      allow update: if isAuthenticated() &&
                      resource.data.fromUserId == request.auth.uid &&
                      resource.data.status == 'pending' &&
                      request.resource.data.status == 'cancelled';

      allow delete: if isAuthenticated() &&
                      resource.data.fromUserId == request.auth.uid;
    }
```

- [ ] **Step 2: Add the missing chat rules**

The strict file has no `conversations` or `messages` block at all, so both collections are deny-by-default. Pairing creates a conversation as soon as two parents link (Task 5), so this file cannot ship without them. Add before the closing braces:

```
    // ---- Conversations & messages --------------------------------------
    // 1:1 threads between the two paired parents. Deliberately minimal —
    // the chat feature's own hardening is a separate spec; without these
    // blocks the collections are deny-by-default and pairing's auto-created
    // conversation fails to sync.
    match /conversations/{conversationId} {
      allow read, update: if isAuthenticated() &&
                            request.auth.uid in resource.data.participants;

      allow create: if isAuthenticated() &&
                      request.auth.uid in request.resource.data.participants &&
                      request.resource.data.participants.size() == 2;

      allow delete: if false;
    }

    match /messages/{messageId} {
      allow read: if isAuthenticated() &&
                    request.auth.uid in
                      get(/databases/$(database)/documents/conversations/$(resource.data.conversationId))
                        .data.participants;

      allow create: if isAuthenticated() &&
                      request.resource.data.senderId == request.auth.uid &&
                      request.auth.uid in
                        get(/databases/$(database)/documents/conversations/$(request.resource.data.conversationId))
                          .data.participants;

      // Only the read flag is amended after the fact; content is immutable.
      allow update: if isAuthenticated() &&
                      request.resource.data.content == resource.data.content &&
                      request.resource.data.senderId == resource.data.senderId;

      allow delete: if false;
    }
```

**Do not deploy in this task.** The client still writes `users/{partnerId}` through `CoParentPairingService` until Task 11 removes it, so deploying the strict file now would break pairing on the two phones mid-plan. The deploy step lives in Task 11, after the client is clean.

- [ ] **Step 3: Commit**

```bash
git add firestore.rules
git commit -m "feat(rules): allow coded pairing invitations, add chat collection rules"
```

---

### Task 4: Callable wrapper and Firestore error mapping on the client

**Files:**
- Modify: `app/build.gradle.kts` (Firebase dependency block, after line 222)
- Modify: `app/src/main/java/com/coparently/app/di/FirebaseModule.kt`
- Create: `app/src/main/java/com/coparently/app/data/remote/firebase/PairingFunctions.kt`

**Interfaces:**
- Consumes: `PairingError` (Task 1); the callables from Task 2.
- Produces:
  - `class PairingFunctions @Inject constructor(functions: FirebaseFunctions)`
  - `suspend fun PairingFunctions.acceptInvitation(code: String? = null, invitationId: String? = null): Result<String>` — success value is the new partner's UID
  - `suspend fun PairingFunctions.unpair(): Result<String?>` — success value is the former partner's UID or null
  - `fun PairingFunctions.Companion.toPairingError(e: Throwable): PairingError`

- [ ] **Step 1: Add the dependency**

In `app/build.gradle.kts`, in the Firebase block right after `implementation("com.google.firebase:firebase-storage-ktx")`:

```kotlin
    // Callable Cloud Functions — pairing accept/unpair write both parents'
    // user documents, which is only safe server-side.
    implementation("com.google.firebase:firebase-functions-ktx")
```

- [ ] **Step 2: Provide FirebaseFunctions in Hilt**

In `FirebaseModule`, after `provideFirebaseMessaging`:

```kotlin
    /**
     * Provides Firebase Functions for the pairing callables.
     *
     * The functions are deployed to us-central1, which is the SDK default, so
     * no explicit region is set here.
     */
    @Provides
    @Singleton
    fun provideFirebaseFunctions(): com.google.firebase.functions.FirebaseFunctions {
        return com.google.firebase.functions.FirebaseFunctions.getInstance()
    }
```

- [ ] **Step 3: Write the wrapper**

`app/src/main/java/com/coparently/app/data/remote/firebase/PairingFunctions.kt`:

```kotlin
package com.coparently.app.data.remote.firebase

import com.coparently.app.domain.model.PairingError
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin client for the pairing Cloud Functions.
 *
 * Translates [FirebaseFunctionsException] into a [PairingError] so the layers
 * above never inspect exception text or Firebase error codes.
 */
@Singleton
class PairingFunctions @Inject constructor(
    private val functions: FirebaseFunctions
) {

    /**
     * Redeems an invitation by [code] or by [invitationId] — exactly one.
     *
     * @return the co-parent's Firebase UID on success.
     */
    suspend fun acceptInvitation(
        code: String? = null,
        invitationId: String? = null
    ): Result<String> = call("acceptPairingInvitation", buildMap {
        code?.let { put("code", it) }
        invitationId?.let { put("invitationId", it) }
    }) { it["partnerId"] as? String ?: "" }

    /**
     * Removes the co-parent link.
     *
     * @return the former partner's UID, or null when there was no link.
     */
    suspend fun unpair(): Result<String?> =
        call("unpairCoParent", emptyMap()) { it["unpairedFrom"] as? String }

    private suspend fun <T> call(
        name: String,
        payload: Map<String, Any>,
        parse: (Map<*, *>) -> T
    ): Result<T> = try {
        val result = functions.getHttpsCallable(name).call(payload).await()
        @Suppress("UNCHECKED_CAST")
        Result.success(parse((result.data as? Map<*, *>) ?: emptyMap<String, Any>()))
    } catch (
        // Any backend failure becomes a typed PairingError; the caller decides
        // how to surface it. Rethrowing would only crash the UI layer.
        @Suppress("TooGenericExceptionCaught") e: Exception
    ) {
        Result.failure(PairingException(toPairingError(e)))
    }

    companion object {

        /** Maps a callable failure to the matching [PairingError]. */
        fun toPairingError(e: Throwable): PairingError {
            val reason = ((e as? FirebaseFunctionsException)?.details as? Map<*, *>)
                ?.get("reason") as? String
            return when (reason) {
                "not-found" -> PairingError.NotFound
                "invitation-expired" -> PairingError.Expired
                "invitation-not-pending" -> PairingError.NotPending
                "self-pairing" -> PairingError.SelfPairing
                "already-paired" -> PairingError.AlreadyPaired
                "wrong-recipient" -> PairingError.WrongRecipient
                else -> when ((e as? FirebaseFunctionsException)?.code) {
                    FirebaseFunctionsException.Code.UNAVAILABLE,
                    FirebaseFunctionsException.Code.DEADLINE_EXCEEDED -> PairingError.Network
                    FirebaseFunctionsException.Code.NOT_FOUND -> PairingError.NotFound
                    else -> PairingError.Unknown(e.message)
                }
            }
        }
    }
}

/** Carries a [PairingError] through `Result.failure`. */
class PairingException(val error: PairingError) : Exception(error.toString())
```

- [ ] **Step 4: Build**

```bash
$env:JAVA_HOME='C:\Program Files\Android\Android Studio1\jbr'; ./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts app/src/main/java/com/coparently/app/di/FirebaseModule.kt app/src/main/java/com/coparently/app/data/remote/firebase/PairingFunctions.kt
git commit -m "feat(pairing): add callable client for accept and unpair"
```

---

### Task 5: PairingRepository

The heart of the feature: a realtime `PairingState`, invite creation with reuse, and the four actions.

**Files:**
- Create: `app/src/main/java/com/coparently/app/domain/repository/PairingRepository.kt`
- Create: `app/src/main/java/com/coparently/app/data/repository/PairingRepositoryImpl.kt`
- Modify: `app/src/main/java/com/coparently/app/di/RepositoryModule.kt`
- Test: `app/src/test/java/com/coparently/app/data/repository/PairingRepositoryImplTest.kt`

**Interfaces:**
- Consumes: `PairingState`, `PairingInvite`, `PairingError`, `PartnerSummary`, `InviteCodeGenerator` (Task 1); `PairingFunctions`, `PairingException` (Task 4); existing `FirebaseAuthService`, `MessageRepository`, `Conversation`.
- Produces:
```kotlin
interface PairingRepository {
    fun observePairingState(): Flow<PairingState>
    suspend fun createOrReuseInviteCode(): Result<PairingInvite>
    suspend fun revokeActiveInvite(): Result<Unit>
    suspend fun sendEmailInvitation(email: String): Result<Unit>
    suspend fun redeem(code: String): Result<Unit>
    suspend fun acceptIncoming(invitationId: String): Result<Unit>
    suspend fun rejectIncoming(invitationId: String): Result<Unit>
    suspend fun unpair(): Result<Unit>
}
```

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/coparently/app/data/repository/PairingRepositoryImplTest.kt`:

```kotlin
package com.coparently.app.data.repository

import com.coparently.app.data.remote.firebase.FirebaseAuthService
import com.coparently.app.data.remote.firebase.PairingException
import com.coparently.app.data.remote.firebase.PairingFunctions
import com.coparently.app.domain.model.PairingError
import com.coparently.app.domain.repository.MessageRepository
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PairingRepositoryImplTest {

    private lateinit var firestore: FirebaseFirestore
    private lateinit var authService: FirebaseAuthService
    private lateinit var pairingFunctions: PairingFunctions
    private lateinit var messageRepository: MessageRepository
    private lateinit var repository: PairingRepositoryImpl

    @Before
    fun setUp() {
        firestore = mockk(relaxed = true)
        authService = mockk(relaxed = true)
        pairingFunctions = mockk(relaxed = true)
        messageRepository = mockk(relaxed = true)

        val firebaseUser = mockk<FirebaseUser>(relaxed = true)
        every { firebaseUser.uid } returns "user-a"
        every { firebaseUser.email } returns "a@example.com"
        every { authService.getCurrentUser() } returns firebaseUser

        repository = PairingRepositoryImpl(
            firestore = firestore,
            authService = authService,
            pairingFunctions = pairingFunctions,
            messageRepository = messageRepository
        )
    }

    @Test
    fun `redeem rejects a malformed code without calling the backend`() = runTest {
        val result = repository.redeem("nope")

        assertTrue(result.isFailure)
        assertEquals(
            PairingError.NotFound,
            (result.exceptionOrNull() as PairingException).error
        )
    }

    @Test
    fun `redeem normalizes the code before calling the backend`() = runTest {
        coEvery { pairingFunctions.acceptInvitation(code = "4F7K2M") } returns
            Result.success("user-b")

        val result = repository.redeem("  4f7k2m ")

        assertTrue(result.isSuccess)
    }

    @Test
    fun `redeem surfaces the backend error unchanged`() = runTest {
        coEvery { pairingFunctions.acceptInvitation(code = "4F7K2M") } returns
            Result.failure(PairingException(PairingError.AlreadyPaired))

        val result = repository.redeem("4F7K2M")

        assertEquals(
            PairingError.AlreadyPaired,
            (result.exceptionOrNull() as PairingException).error
        )
    }

    @Test
    fun `unpair delegates to the callable`() = runTest {
        coEvery { pairingFunctions.unpair() } returns Result.success("user-b")

        assertTrue(repository.unpair().isSuccess)
    }
}
```

- [ ] **Step 2: Run the test and confirm it fails**

```bash
$env:JAVA_HOME='C:\Program Files\Android\Android Studio1\jbr'; ./gradlew testDebugUnitTest --tests "com.coparently.app.data.repository.PairingRepositoryImplTest"
```

Expected: compilation failure — `Unresolved reference: PairingRepositoryImpl`.

- [ ] **Step 3: Write the interface**

`app/src/main/java/com/coparently/app/domain/repository/PairingRepository.kt`:

```kotlin
package com.coparently.app.domain.repository

import com.coparently.app.domain.model.PairingInvite
import com.coparently.app.domain.model.PairingState
import kotlinx.coroutines.flow.Flow

/**
 * Owns the co-parent link: whether it exists, how to offer one, and how to end it.
 *
 * Failures come back as `Result.failure(PairingException)` carrying a
 * `PairingError`, so callers map one typed value to one message.
 */
interface PairingRepository {

    /**
     * Emits the current pairing state and every subsequent change, driven by
     * Firestore snapshot listeners — the inviting phone learns that its
     * invitation was accepted without polling or a push.
     */
    fun observePairingState(): Flow<PairingState>

    /**
     * Returns this user's outstanding invite, creating one only when there is
     * no pending, unexpired invite already. Reuse matters: a code the user has
     * already sent by message must not silently stop working.
     */
    suspend fun createOrReuseInviteCode(): Result<PairingInvite>

    /** Withdraws the active invite so its code stops working. */
    suspend fun revokeActiveInvite(): Result<Unit>

    /** Creates an invitation addressed to [email]. */
    suspend fun sendEmailInvitation(email: String): Result<Unit>

    /** Redeems an invitation by its short [code]. */
    suspend fun redeem(code: String): Result<Unit>

    /** Accepts an invitation addressed to this user by document id. */
    suspend fun acceptIncoming(invitationId: String): Result<Unit>

    /** Declines an invitation addressed to this user. */
    suspend fun rejectIncoming(invitationId: String): Result<Unit>

    /** Ends the co-parent link for both parents. Shared data is kept. */
    suspend fun unpair(): Result<Unit>
}
```

- [ ] **Step 4: Write the implementation**

`app/src/main/java/com/coparently/app/data/repository/PairingRepositoryImpl.kt`:

```kotlin
package com.coparently.app.data.repository

import com.coparently.app.data.remote.firebase.FirebaseAuthService
import com.coparently.app.data.remote.firebase.PairingException
import com.coparently.app.data.remote.firebase.PairingFunctions
import com.coparently.app.domain.model.Conversation
import com.coparently.app.domain.model.PairingError
import com.coparently.app.domain.model.PairingInvite
import com.coparently.app.domain.model.PairingState
import com.coparently.app.domain.model.PartnerSummary
import com.coparently.app.domain.pairing.InviteCodeGenerator
import com.coparently.app.domain.repository.MessageRepository
import com.coparently.app.domain.repository.PairingRepository
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.tasks.await
import java.time.LocalDateTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Firestore-backed [PairingRepository].
 *
 * Reads are realtime snapshot listeners; the two writes that touch the other
 * parent's document (accept, unpair) go through Cloud Functions.
 */
@Singleton
class PairingRepositoryImpl @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val authService: FirebaseAuthService,
    private val pairingFunctions: PairingFunctions,
    private val messageRepository: MessageRepository
) : PairingRepository {

    override fun observePairingState(): Flow<PairingState> {
        val user = authService.getCurrentUser() ?: return flowOf(PairingState.Loading)
        return combine(
            observeUserDocument(user.uid),
            observeOwnInvites(user.uid),
            observeIncomingInvites(user.email.orEmpty())
        ) { userSnapshot, own, incoming ->
            val partnerId = userSnapshot?.getString("partnerId").orEmpty()
            if (partnerId.isEmpty()) {
                PairingState.NotPaired(activeInvite = own.firstOrNull(), incoming = incoming)
            } else {
                PairingState.Paired(
                    partner = loadPartner(partnerId, userSnapshot?.getLong("pairedAt"))
                )
            }
        }.distinctUntilChanged()
    }

    override suspend fun createOrReuseInviteCode(): Result<PairingInvite> = runPairing {
        val user = requireUser()
        val existing = firestore.collection(INVITATIONS)
            .whereEqualTo("fromUserId", user.uid)
            .whereEqualTo("status", STATUS_PENDING)
            .get()
            .await()
            .documents
            .mapNotNull { it.toInvite() }
            .firstOrNull { it.toEmail.isEmpty() && it.expiresAtMillis > System.currentTimeMillis() }

        existing ?: writeNewInvite(toEmail = "")
    }

    override suspend fun revokeActiveInvite(): Result<Unit> = runPairing {
        val user = requireUser()
        firestore.collection(INVITATIONS)
            .whereEqualTo("fromUserId", user.uid)
            .whereEqualTo("status", STATUS_PENDING)
            .get()
            .await()
            .documents
            .forEach { it.reference.update("status", STATUS_CANCELLED).await() }
    }

    override suspend fun sendEmailInvitation(email: String): Result<Unit> = runPairing {
        writeNewInvite(toEmail = email.trim().lowercase())
    }

    override suspend fun redeem(code: String): Result<Unit> {
        val normalized = code.trim().uppercase()
        if (!InviteCodeGenerator.isValid(normalized)) {
            return Result.failure(PairingException(PairingError.NotFound))
        }
        return pairingFunctions.acceptInvitation(code = normalized)
            .onSuccess { partnerId -> ensureConversationWith(partnerId) }
            .map { }
    }

    override suspend fun acceptIncoming(invitationId: String): Result<Unit> =
        pairingFunctions.acceptInvitation(invitationId = invitationId)
            .onSuccess { partnerId -> ensureConversationWith(partnerId) }
            .map { }

    override suspend fun rejectIncoming(invitationId: String): Result<Unit> = runPairing {
        firestore.collection(INVITATIONS).document(invitationId)
            .update("status", STATUS_REJECTED)
            .await()
    }

    override suspend fun unpair(): Result<Unit> = pairingFunctions.unpair().map { }

    // ---- Firestore plumbing -------------------------------------------

    private fun observeUserDocument(uid: String): Flow<DocumentSnapshot?> = callbackFlow {
        val registration = firestore.collection(USERS).document(uid)
            .addSnapshotListener { snapshot, _ -> trySend(snapshot) }
        awaitClose { registration.remove() }
    }

    private fun observeOwnInvites(uid: String): Flow<List<PairingInvite>> = callbackFlow {
        val registration = firestore.collection(INVITATIONS)
            .whereEqualTo("fromUserId", uid)
            .whereEqualTo("status", STATUS_PENDING)
            .addSnapshotListener { snapshot, _ ->
                trySend(snapshot?.documents.orEmpty().mapNotNull { it.toInvite() })
            }
        awaitClose { registration.remove() }
    }

    private fun observeIncomingInvites(email: String): Flow<List<PairingInvite>> {
        if (email.isEmpty()) return flowOf(emptyList())
        return callbackFlow {
            val registration = firestore.collection(INVITATIONS)
                .whereEqualTo("toEmail", email)
                .whereEqualTo("status", STATUS_PENDING)
                .addSnapshotListener { snapshot, _ ->
                    trySend(snapshot?.documents.orEmpty().mapNotNull { it.toInvite() })
                }
            awaitClose { registration.remove() }
        }
    }

    private suspend fun loadPartner(partnerId: String, pairedAt: Long?): PartnerSummary {
        val data = firestore.collection(USERS).document(partnerId).get().await()
        return PartnerSummary(
            id = partnerId,
            name = data.getString("name").orEmpty(),
            email = data.getString("email").orEmpty(),
            pairedSinceMillis = pairedAt
        )
    }

    private suspend fun writeNewInvite(toEmail: String): PairingInvite {
        val user = requireUser()
        val profile = firestore.collection(USERS).document(user.uid).get().await()
        val ttl = if (toEmail.isEmpty()) CODE_TTL_MILLIS else EMAIL_TTL_MILLIS
        val invite = PairingInvite(
            id = UUID.randomUUID().toString(),
            code = InviteCodeGenerator.generate(),
            fromUserId = user.uid,
            fromUserName = profile.getString("name") ?: user.email.orEmpty(),
            fromUserEmail = user.email.orEmpty(),
            toEmail = toEmail,
            expiresAtMillis = System.currentTimeMillis() + ttl
        )
        firestore.collection(INVITATIONS).document(invite.id).set(
            mapOf(
                "id" to invite.id,
                "code" to invite.code,
                "fromUserId" to invite.fromUserId,
                "fromUserName" to invite.fromUserName,
                "fromUserEmail" to invite.fromUserEmail,
                "toEmail" to invite.toEmail,
                "status" to STATUS_PENDING,
                "createdAt" to System.currentTimeMillis(),
                "expiresAt" to invite.expiresAtMillis,
                "acceptedBy" to null
            )
        ).await()
        return invite
    }

    /**
     * Creates the 1:1 conversation after pairing if it does not exist yet.
     *
     * Runs on whichever device completed the pairing; the other picks the
     * conversation up through the message sync, so both ends get a thread.
     */
    private suspend fun ensureConversationWith(partnerId: String) {
        val uid = authService.getCurrentUser()?.uid ?: return
        if (partnerId.isEmpty()) return
        val partnerName = firestore.collection(USERS).document(partnerId).get().await()
            .getString("name").orEmpty().ifEmpty { "Co-parent" }
        messageRepository.createConversation(
            Conversation(
                id = UUID.randomUUID().toString(),
                participants = listOf(uid, partnerId),
                title = partnerName,
                createdAt = LocalDateTime.now()
            )
        )
    }

    private fun requireUser() = authService.getCurrentUser()
        ?: throw PairingException(PairingError.Unknown("Not signed in"))

    private fun DocumentSnapshot.toInvite(): PairingInvite? {
        val code = getString("code") ?: return null
        return PairingInvite(
            id = getString("id") ?: id,
            code = code,
            fromUserId = getString("fromUserId").orEmpty(),
            fromUserName = getString("fromUserName").orEmpty(),
            fromUserEmail = getString("fromUserEmail").orEmpty(),
            toEmail = getString("toEmail").orEmpty(),
            expiresAtMillis = getLong("expiresAt") ?: 0L
        )
    }

    /**
     * Runs a Firestore block and normalizes any failure into a [PairingException].
     *
     * The lambda is `suspend` because every body calls `await()`; an inline
     * non-suspending version will not compile here.
     */
    private suspend fun <T> runPairing(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: PairingException) {
        Result.failure(e)
    } catch (
        // Firestore failures become a typed error instead of crashing the caller.
        @Suppress("TooGenericExceptionCaught") e: Exception
    ) {
        Result.failure(PairingException(PairingError.Unknown(e.message)))
    }

    private companion object {
        const val USERS = "users"
        const val INVITATIONS = "invitations"
        const val STATUS_PENDING = "pending"
        const val STATUS_REJECTED = "rejected"
        const val STATUS_CANCELLED = "cancelled"
        const val CODE_TTL_MILLIS = 24L * 60 * 60 * 1000
        const val EMAIL_TTL_MILLIS = 7L * 24 * 60 * 60 * 1000
    }
}
```

- [ ] **Step 5: Bind the repository in Hilt**

In `app/src/main/java/com/coparently/app/di/RepositoryModule.kt`, add a `@Binds` following the file's existing pattern:

```kotlin
    /** Binds the Firestore-backed pairing repository. */
    @Binds
    @Singleton
    abstract fun bindPairingRepository(
        impl: com.coparently.app.data.repository.PairingRepositoryImpl
    ): com.coparently.app.domain.repository.PairingRepository
```

If `RepositoryModule` is an `object` rather than an `abstract class`, add the binding to `FirebaseRepositoryModule` in `FirebaseModule.kt` instead, which is already an `abstract class` with `@Binds`.

- [ ] **Step 6: Run the tests and confirm they pass**

```bash
$env:JAVA_HOME='C:\Program Files\Android\Android Studio1\jbr'; ./gradlew testDebugUnitTest --tests "com.coparently.app.data.repository.PairingRepositoryImplTest"
```

Expected: PASS, 4 tests.

- [ ] **Step 7: Build**

```bash
$env:JAVA_HOME='C:\Program Files\Android\Android Studio1\jbr'; ./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/coparently/app/domain/repository/PairingRepository.kt app/src/main/java/com/coparently/app/data/repository/PairingRepositoryImpl.kt app/src/main/java/com/coparently/app/di app/src/test/java/com/coparently/app/data/repository/PairingRepositoryImplTest.kt
git commit -m "feat(pairing): add PairingRepository with realtime state and invite reuse"
```

---

### Task 6: PairingViewModel, screen and strings

The ViewModel and its only consumer ship together: the screen is the ViewModel's sole caller, so splitting them would leave the module non-compiling at a commit boundary and give the reviewer a ViewModel it cannot judge in use.

**Files:**
- Modify: `app/src/main/java/com/coparently/app/presentation/pairing/PairingViewModel.kt` (full rewrite, 583 → ~150 lines)
- Modify: `app/src/main/java/com/coparently/app/presentation/pairing/PairingScreen.kt` (full rewrite)
- Create: `app/src/main/java/com/coparently/app/presentation/pairing/components/InviteCodeCard.kt`
- Create: `app/src/main/java/com/coparently/app/presentation/pairing/components/CodeEntryField.kt`
- Create: `app/src/main/java/com/coparently/app/presentation/pairing/components/IncomingInviteCard.kt`
- Create: `app/src/main/java/com/coparently/app/presentation/pairing/components/PairedPartnerCard.kt`
- Modify: `app/src/main/res/values/pairing_strings.xml` and the `values-cs/`, `values-de/`, `values-ru/`, `values-uk/` variants
- Test: `app/src/test/java/com/coparently/app/presentation/pairing/PairingViewModelTest.kt`

**Interfaces:**
- Consumes: `PairingRepository` (Task 5), `PairingError`/`PairingState`/`PairingInvite`/`PartnerSummary`/`PairingUri` (Task 1), existing `QRCodeService`, `AnalyticsManager`, `ConfirmationDialog`.
- Produces:
  - `PairingScreen(onNavigateBack: () -> Unit, prefilledCode: String? = null, viewModel: PairingViewModel = hiltViewModel())` — `prefilledCode` is what Task 8's deep link uses
  - `PairingViewModel.state: StateFlow<PairingState>`
  - `PairingViewModel.form: StateFlow<PairingFormState>` where
    `data class PairingFormState(codeInput: String, emailInput: String, isBusy: Boolean, errorRes: Int?, emailErrorRes: Int?, qrBitmap: Bitmap?, showQrDialog: Boolean)`
  - actions: `onCodeInputChange(String)`, `onEmailInputChange(String)`, `redeemCode()`, `sendEmailInvitation()`, `acceptIncoming(String)`, `rejectIncoming(String)`, `unpair()`, `refreshInvite()`, `regenerateInvite()`, `showQr()`, `dismissQr()`, `clearError()`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/coparently/app/presentation/pairing/PairingViewModelTest.kt`:

```kotlin
package com.coparently.app.presentation.pairing

import app.cash.turbine.test
import com.coparently.app.R
import com.coparently.app.data.analytics.AnalyticsManager
import com.coparently.app.data.remote.firebase.PairingException
import com.coparently.app.data.remote.firebase.QRCodeService
import com.coparently.app.domain.model.PairingError
import com.coparently.app.domain.model.PairingState
import com.coparently.app.domain.repository.PairingRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PairingViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var repository: PairingRepository
    private lateinit var viewModel: PairingViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = mockk(relaxed = true)
        coEvery { repository.observePairingState() } returns
            flowOf(PairingState.NotPaired())
        viewModel = PairingViewModel(
            pairingRepository = repository,
            qrCodeService = mockk<QRCodeService>(relaxed = true),
            analyticsManager = mockk<AnalyticsManager>(relaxed = true)
        )
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `code input is upper-cased and trimmed to the code length`() = runTest {
        viewModel.onCodeInputChange("4f7k2mXX")

        assertEquals("4F7K2M", viewModel.form.value.codeInput)
    }

    @Test
    fun `redeeming a short code does not hit the repository`() = runTest {
        viewModel.onCodeInputChange("4F7")
        viewModel.redeemCode()

        coVerify(exactly = 0) { repository.redeem(any()) }
    }

    @Test
    fun `an already-paired failure maps to its own message`() = runTest {
        coEvery { repository.redeem("4F7K2M") } returns
            Result.failure(PairingException(PairingError.AlreadyPaired))

        viewModel.onCodeInputChange("4F7K2M")
        viewModel.redeemCode()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(R.string.pairing_error_already_paired, viewModel.form.value.errorRes)
    }

    @Test
    fun `state mirrors the repository`() = runTest {
        viewModel.state.test {
            assertEquals(PairingState.NotPaired(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
```

- [ ] **Step 2: Run the test and confirm it fails**

```bash
$env:JAVA_HOME='C:\Program Files\Android\Android Studio1\jbr'; ./gradlew testDebugUnitTest --tests "com.coparently.app.presentation.pairing.PairingViewModelTest"
```

Expected: compilation failure — the current `PairingViewModel` constructor takes six different dependencies.

- [ ] **Step 3: Add the error strings**

These are needed by the ViewModel; the rest of the keys and all four translations follow in Steps 6–7 of this task. For now add just these English keys to `app/src/main/res/values/pairing_strings.xml` so the code compiles:

```xml
    <string name="pairing_error_not_found">No invitation matches that code.</string>
    <string name="pairing_error_expired">That code has expired. Ask for a new one.</string>
    <string name="pairing_error_not_pending">That invitation is no longer active.</string>
    <string name="pairing_error_self_pairing">That is your own invitation.</string>
    <string name="pairing_error_already_paired">One of the accounts already has a co-parent.</string>
    <string name="pairing_error_wrong_recipient">That invitation was sent to a different email address.</string>
    <string name="pairing_error_network">No connection. Check your internet and try again.</string>
    <string name="pairing_error_unknown">Something went wrong. Please try again.</string>
    <string name="pairing_error_code_incomplete">Enter all 6 characters of the code.</string>
```

- [ ] **Step 4: Write the implementation**

Replace the whole contents of `app/src/main/java/com/coparently/app/presentation/pairing/PairingViewModel.kt`:

```kotlin
package com.coparently.app.presentation.pairing

import android.graphics.Bitmap
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coparently.app.R
import com.coparently.app.data.analytics.AnalyticsManager
import com.coparently.app.data.remote.firebase.PairingException
import com.coparently.app.data.remote.firebase.QRCodeService
import com.coparently.app.domain.model.PairingError
import com.coparently.app.domain.model.PairingState
import com.coparently.app.domain.pairing.InviteCodeGenerator
import com.coparently.app.domain.pairing.PairingUri
import com.coparently.app.domain.repository.PairingRepository
import com.coparently.app.utils.ValidationResult
import com.coparently.app.utils.ValidationUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Transient state of the pairing form — everything that is not the pairing
 * itself. Errors are string resource ids, never English literals, so the
 * screen renders them in the user's language.
 */
data class PairingFormState(
    val codeInput: String = "",
    val emailInput: String = "",
    val isBusy: Boolean = false,
    @StringRes val errorRes: Int? = null,
    @StringRes val emailErrorRes: Int? = null,
    val qrBitmap: Bitmap? = null,
    val showQrDialog: Boolean = false
)

/**
 * ViewModel for the pairing screen: exposes the realtime [PairingState] from
 * the repository plus the local form state, and forwards the five actions.
 */
@HiltViewModel
class PairingViewModel @Inject constructor(
    private val pairingRepository: PairingRepository,
    private val qrCodeService: QRCodeService,
    private val analyticsManager: AnalyticsManager
) : ViewModel() {

    val state: StateFlow<PairingState> = pairingRepository.observePairingState()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), PairingState.Loading)

    private val _form = MutableStateFlow(PairingFormState())
    val form: StateFlow<PairingFormState> = _form.asStateFlow()

    init {
        refreshInvite()
    }

    /** Ensures an invite code exists so the hero card always has one to show. */
    fun refreshInvite() {
        viewModelScope.launch { pairingRepository.createOrReuseInviteCode() }
    }

    /** Withdraws the current code and issues a fresh one. */
    fun regenerateInvite() {
        viewModelScope.launch {
            pairingRepository.revokeActiveInvite()
            pairingRepository.createOrReuseInviteCode()
        }
    }

    /**
     * Accepts typed or pasted input: a bare code, a full pairing URI, or share
     * text containing one. Anything else is kept as up-cased characters so the
     * user can keep typing.
     */
    fun onCodeInputChange(raw: String) {
        val code = PairingUri.extractCode(raw)
            ?: raw.trim().uppercase().filter { it in InviteCodeGenerator.ALPHABET }
                .take(InviteCodeGenerator.LENGTH)
        _form.value = _form.value.copy(codeInput = code, errorRes = null)
    }

    fun onEmailInputChange(email: String) {
        _form.value = _form.value.copy(emailInput = email, emailErrorRes = null, errorRes = null)
    }

    /** Redeems the code currently in the input field. */
    fun redeemCode() {
        val code = _form.value.codeInput
        if (!InviteCodeGenerator.isValid(code)) {
            _form.value = _form.value.copy(errorRes = R.string.pairing_error_code_incomplete)
            return
        }
        launchAction { pairingRepository.redeem(code) }
    }

    fun sendEmailInvitation() {
        val email = _form.value.emailInput
        val validation = ValidationUtils.validateEmail(email)
        if (validation is ValidationResult.Error) {
            _form.value = _form.value.copy(emailErrorRes = R.string.pairing_error_invalid_email)
            return
        }
        launchAction(onSuccess = { _form.value = _form.value.copy(emailInput = "") }) {
            pairingRepository.sendEmailInvitation(email).also { analyticsManager.logInvitationSent() }
        }
    }

    fun acceptIncoming(invitationId: String) = launchAction(
        onSuccess = { analyticsManager.logInvitationAccepted() }
    ) { pairingRepository.acceptIncoming(invitationId) }

    fun rejectIncoming(invitationId: String) =
        launchAction { pairingRepository.rejectIncoming(invitationId) }

    fun unpair() = launchAction { pairingRepository.unpair() }

    /** Renders the active invite's link as a QR bitmap and opens the dialog. */
    fun showQr() {
        val invite = (state.value as? PairingState.NotPaired)?.activeInvite ?: return
        viewModelScope.launch {
            val bitmap = qrCodeService.generatePairingQRCode(
                invitationId = PairingUri.build(invite.code),
                inviterName = invite.fromUserName,
                inviterEmail = invite.fromUserEmail
            )
            _form.value = _form.value.copy(qrBitmap = bitmap, showQrDialog = bitmap != null)
        }
    }

    fun dismissQr() {
        _form.value = _form.value.copy(showQrDialog = false, qrBitmap = null)
    }

    fun clearError() {
        _form.value = _form.value.copy(errorRes = null, emailErrorRes = null)
    }

    private fun launchAction(
        onSuccess: () -> Unit = {},
        action: suspend () -> Result<*>
    ) {
        _form.value = _form.value.copy(isBusy = true, errorRes = null)
        viewModelScope.launch {
            val result = action()
            _form.value = _form.value.copy(
                isBusy = false,
                errorRes = result.exceptionOrNull()?.let { messageFor(it) }
            )
            if (result.isSuccess) onSuccess()
        }
    }

    @StringRes
    private fun messageFor(throwable: Throwable): Int =
        when ((throwable as? PairingException)?.error) {
            PairingError.NotFound -> R.string.pairing_error_not_found
            PairingError.Expired -> R.string.pairing_error_expired
            PairingError.NotPending -> R.string.pairing_error_not_pending
            PairingError.SelfPairing -> R.string.pairing_error_self_pairing
            PairingError.AlreadyPaired -> R.string.pairing_error_already_paired
            PairingError.WrongRecipient -> R.string.pairing_error_wrong_recipient
            PairingError.Network -> R.string.pairing_error_network
            else -> R.string.pairing_error_unknown
        }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
```

Add the one further key used above to `values/pairing_strings.xml`:

```xml
    <string name="pairing_error_invalid_email">Enter a valid email address.</string>
```

- [ ] **Step 5: Run the ViewModel tests and confirm they pass**

```bash
$env:JAVA_HOME='C:\Program Files\Android\Android Studio1\jbr'; ./gradlew testDebugUnitTest --tests "com.coparently.app.presentation.pairing.PairingViewModelTest"
```

Expected: PASS, 4 tests. `PairingScreen.kt` still references the old ViewModel API at this point — the remaining steps of this task rewrite it, and nothing is committed until the module builds again.

- [ ] **Step 6: Add every new string to the base file**

Append to `app/src/main/res/values/pairing_strings.xml` (the error keys from Task 6 are already there):

```xml
    <!-- Invite code hero card -->
    <string name="pairing_your_code_title">Your invite code</string>
    <string name="pairing_your_code_hint">Give this code to your co-parent. It is valid for 24 hours.</string>
    <string name="pairing_code_copied">Code copied</string>
    <string name="pairing_share_invite">Share</string>
    <string name="pairing_show_qr">Show QR</string>
    <string name="pairing_new_code">New code</string>
    <string name="pairing_expires_in_hours">Valid for %1$d h %2$d min</string>
    <string name="pairing_expires_in_minutes">Valid for %1$d min</string>
    <string name="pairing_code_expired_generate">Code expired — tap "New code"</string>
    <string name="pairing_share_message">%1$s invites you to CoPlanly. Code: %2$s · %3$s</string>
    <!-- Code entry -->
    <string name="pairing_have_a_code">I have a code</string>
    <string name="pairing_code_field_label">Invite code</string>
    <string name="pairing_link_accounts">Link accounts</string>
    <string name="pairing_or">or</string>
    <!-- Email invite -->
    <string name="pairing_invite_by_email">Invite by email</string>
    <!-- Paired state -->
    <string name="pairing_paired_since">Paired since %1$s</string>
    <string name="pairing_unpair_confirm_title">Unlink your co-parent?</string>
    <string name="pairing_unpair_confirm_message">You will stop sharing the calendar, chat and expenses with %1$s. Data already on this phone is kept.</string>
    <string name="pairing_unpair_confirm_action">Unlink</string>
    <!-- Deep link confirmation -->
    <string name="pairing_link_confirm_title">Link with %1$s?</string>
    <string name="pairing_link_confirm_message">%1$s (%2$s) will see your shared calendar, chat and expenses.</string>
</resources>
```

Also fix the stale comment at the top of the file: `strings.xml` is tracked now (secrets moved to BuildConfig long ago), so the sentence claiming it is gitignored is wrong. Replace the comment with:

```xml
<!-- Co-parent pairing strings (PairingScreen, QR scanner, deep link confirmation).
     Feature-named resource file per the project's i18n convention; every key here
     must also exist in values-cs, values-de, values-ru and values-uk. -->
```

- [ ] **Step 7: Translate every new key into the four locales**

Add the same keys to `values-cs/pairing_strings.xml`, `values-de/pairing_strings.xml`, `values-ru/pairing_strings.xml`, `values-uk/pairing_strings.xml`. Russian, for example:

```xml
    <string name="pairing_your_code_title">Ваш код приглашения</string>
    <string name="pairing_your_code_hint">Передайте код второму родителю. Действителен 24 часа.</string>
    <string name="pairing_code_copied">Код скопирован</string>
    <string name="pairing_share_invite">Поделиться</string>
    <string name="pairing_show_qr">Показать QR</string>
    <string name="pairing_new_code">Новый код</string>
    <string name="pairing_expires_in_hours">Действителен ещё %1$d ч %2$d мин</string>
    <string name="pairing_expires_in_minutes">Действителен ещё %1$d мин</string>
    <string name="pairing_code_expired_generate">Код истёк — нажмите «Новый код»</string>
    <string name="pairing_share_message">%1$s приглашает вас в CoPlanly. Код: %2$s · %3$s</string>
    <string name="pairing_have_a_code">У меня есть код</string>
    <string name="pairing_code_field_label">Код приглашения</string>
    <string name="pairing_link_accounts">Связать аккаунты</string>
    <string name="pairing_or">или</string>
    <string name="pairing_invite_by_email">Пригласить по email</string>
    <string name="pairing_paired_since">Связаны с %1$s</string>
    <string name="pairing_unpair_confirm_title">Разорвать связь?</string>
    <string name="pairing_unpair_confirm_message">Вы перестанете делиться календарём, чатом и расходами с %1$s. Данные на этом телефоне сохранятся.</string>
    <string name="pairing_unpair_confirm_action">Отвязаться</string>
    <string name="pairing_link_confirm_title">Связаться с %1$s?</string>
    <string name="pairing_link_confirm_message">%1$s (%2$s) увидит ваш общий календарь, чат и расходы.</string>
    <string name="pairing_error_not_found">Приглашение с таким кодом не найдено.</string>
    <string name="pairing_error_expired">Код истёк. Попросите новый.</string>
    <string name="pairing_error_not_pending">Это приглашение больше не активно.</string>
    <string name="pairing_error_self_pairing">Это ваше собственное приглашение.</string>
    <string name="pairing_error_already_paired">Один из аккаунтов уже связан со вторым родителем.</string>
    <string name="pairing_error_wrong_recipient">Приглашение отправлено на другой адрес.</string>
    <string name="pairing_error_network">Нет соединения. Проверьте интернет и попробуйте снова.</string>
    <string name="pairing_error_unknown">Что-то пошло не так. Попробуйте ещё раз.</string>
    <string name="pairing_error_code_incomplete">Введите все 6 символов кода.</string>
    <string name="pairing_error_invalid_email">Введите корректный адрес email.</string>
```

Czech, German and Ukrainian get equivalent translations of the same key set. Do not leave any key missing — a missing key silently falls back to English at runtime and the `MissingTranslation` lint check is only a warning here.

- [ ] **Step 8: Write the components**

`components/InviteCodeCard.kt`:

```kotlin
package com.coparently.app.presentation.pairing.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coparently.app.R
import com.coparently.app.domain.model.PairingInvite
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/**
 * The hero card of the unpaired state: the invite code, how long it lasts, and
 * the three ways to hand it over.
 */
@Composable
fun InviteCodeCard(
    invite: PairingInvite,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onShowQr: () -> Unit,
    onRegenerate: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.pairing_your_code_title),
                style = MaterialTheme.typography.titleMedium
            )
            TextButton(onClick = onCopy) {
                Text(
                    text = invite.code,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 8.sp,
                    style = MaterialTheme.typography.displaySmall
                )
            }
            Text(
                text = countdownText(invite.expiresAtMillis),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Text(
                text = stringResource(R.string.pairing_your_code_hint),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onShare) {
                    Icon(Icons.Default.Share, contentDescription = null)
                    Text(
                        text = stringResource(R.string.pairing_share_invite),
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
                OutlinedButton(onClick = onShowQr) {
                    Icon(Icons.Default.QrCode, contentDescription = null)
                    Text(
                        text = stringResource(R.string.pairing_show_qr),
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
            TextButton(onClick = onRegenerate) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Text(
                    text = stringResource(R.string.pairing_new_code),
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}

/** Live "valid for …" text, recomputed once a minute. */
@Composable
private fun countdownText(expiresAtMillis: Long): String {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(expiresAtMillis) {
        while (true) {
            now = System.currentTimeMillis()
            delay(TimeUnit.SECONDS.toMillis(30))
        }
    }
    val remaining = (expiresAtMillis - now).coerceAtLeast(0)
    if (remaining == 0L) return stringResource(R.string.pairing_code_expired_generate)
    val hours = TimeUnit.MILLISECONDS.toHours(remaining)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(remaining) % 60
    return if (hours > 0) {
        stringResource(R.string.pairing_expires_in_hours, hours, minutes)
    } else {
        stringResource(R.string.pairing_expires_in_minutes, minutes)
    }
}
```

`components/CodeEntryField.kt`:

```kotlin
package com.coparently.app.presentation.pairing.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import com.coparently.app.R
import com.coparently.app.domain.pairing.InviteCodeGenerator

/**
 * Input for a code the user was given. Accepts a pasted pairing link or share
 * message too — the ViewModel extracts the code from it.
 */
@Composable
fun CodeEntryField(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    errorText: String?,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.pairing_have_a_code),
            style = MaterialTheme.typography.titleMedium
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(stringResource(R.string.pairing_code_field_label)) },
            singleLine = true,
            enabled = enabled,
            isError = errorText != null,
            supportingText = errorText?.let { { Text(it) } },
            textStyle = TextStyle(
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 6.sp
            ),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = onSubmit,
            enabled = enabled && value.length == InviteCodeGenerator.LENGTH,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.pairing_link_accounts))
        }
    }
}
```

`components/IncomingInviteCard.kt`:

```kotlin
package com.coparently.app.presentation.pairing.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.coparently.app.R
import com.coparently.app.domain.model.PairingInvite

/** One invitation addressed to this user, with accept and decline. */
@Composable
fun IncomingInviteCard(
    invite: PairingInvite,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = invite.fromUserName.ifEmpty {
                        stringResource(R.string.pairing_unknown_sender)
                    },
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = invite.fromUserEmail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row {
                TextButton(onClick = onAccept) {
                    Text(stringResource(R.string.pairing_accept_button))
                }
                TextButton(onClick = onReject) {
                    Text(stringResource(R.string.pairing_reject_button))
                }
            }
        }
    }
}
```

`components/PairedPartnerCard.kt`:

```kotlin
package com.coparently.app.presentation.pairing.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.coparently.app.R
import com.coparently.app.domain.model.PartnerSummary
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Summary of the linked co-parent. */
@Composable
fun PairedPartnerCard(
    partner: PartnerSummary,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = partner.name.take(1).uppercase(),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            Column(modifier = Modifier.padding(start = 16.dp)) {
                Text(text = partner.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = partner.email,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                partner.pairedSinceMillis?.let { millis ->
                    Text(
                        text = stringResource(R.string.pairing_paired_since, formatDate(millis)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Formats an epoch-millis instant as a localized date.
 *
 * `LocalDate.ofInstant` is API 34+, and minSdk here is 26 — go through the zone
 * explicitly instead.
 */
private fun formatDate(millis: Long): String =
    Instant.ofEpochMilli(millis)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
        .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
```

- [ ] **Step 9: Rewrite the screen**

Replace `PairingScreen.kt` with a screen that switches on `PairingState` and delegates to the four components. The Unpair button sits alone at the bottom behind `ConfirmationDialog`; sharing uses `Intent.ACTION_SEND` built from `R.string.pairing_share_message` and `PairingUri.build(invite.code)`; copy uses `LocalClipboardManager`. The deep-link confirmation dialog is wired here, driven by the `prefilledCode` parameter:

```kotlin
package com.coparently.app.presentation.pairing

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.coparently.app.R
import com.coparently.app.domain.model.PairingState
import com.coparently.app.domain.pairing.PairingUri
import com.coparently.app.presentation.common.ConfirmationDialog
import com.coparently.app.presentation.pairing.components.CodeEntryField
import com.coparently.app.presentation.pairing.components.IncomingInviteCard
import com.coparently.app.presentation.pairing.components.InviteCodeCard
import com.coparently.app.presentation.pairing.components.PairedPartnerCard

/**
 * Co-parent pairing: hand over a code, scan a QR, open a shared link, or send
 * an email invitation — and unlink again.
 *
 * @param onNavigateBack Up navigation.
 * @param prefilledCode Code carried by a `coplanly://pair` deep link, if any.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScreen(
    onNavigateBack: () -> Unit,
    prefilledCode: String? = null,
    viewModel: PairingViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val form by viewModel.form.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var showUnpairConfirm by remember { mutableStateOf(false) }
    var pendingDeepLinkCode by remember { mutableStateOf(prefilledCode) }

    LaunchedEffect(prefilledCode) {
        prefilledCode?.let { viewModel.onCodeInputChange(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.pairing_screen_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.pairing_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (val current = state) {
                is PairingState.Loading -> item {
                    CircularProgressIndicator(Modifier.padding(32.dp))
                }

                is PairingState.Paired -> {
                    item { PairedPartnerCard(partner = current.partner) }
                    item {
                        Button(
                            onClick = { showUnpairConfirm = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.pairing_unpair_button)) }
                    }
                }

                is PairingState.NotPaired -> {
                    current.activeInvite?.let { invite ->
                        item {
                            InviteCodeCard(
                                invite = invite,
                                onCopy = { clipboard.setText(AnnotatedString(invite.code)) },
                                onShare = {
                                    context.startActivity(
                                        android.content.Intent.createChooser(
                                            android.content.Intent(
                                                android.content.Intent.ACTION_SEND
                                            ).apply {
                                                type = "text/plain"
                                                putExtra(
                                                    android.content.Intent.EXTRA_TEXT,
                                                    context.getString(
                                                        R.string.pairing_share_message,
                                                        invite.fromUserName,
                                                        invite.code,
                                                        PairingUri.build(invite.code)
                                                    )
                                                )
                                            },
                                            context.getString(R.string.pairing_share_invite)
                                        )
                                    )
                                },
                                onShowQr = viewModel::showQr,
                                onRegenerate = viewModel::regenerateInvite
                            )
                        }
                    }

                    item { HorizontalDivider() }

                    item {
                        CodeEntryField(
                            value = form.codeInput,
                            onValueChange = viewModel::onCodeInputChange,
                            onSubmit = viewModel::redeemCode,
                            errorText = form.errorRes?.let { stringResource(it) },
                            enabled = !form.isBusy
                        )
                    }

                    item {
                        OutlinedButton(
                            onClick = {
                                context.startActivity(
                                    android.content.Intent(context, QRScannerActivity::class.java)
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                            Text(
                                text = stringResource(R.string.pairing_scan_qr_code),
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }

                    item {
                        OutlinedTextField(
                            value = form.emailInput,
                            onValueChange = viewModel::onEmailInputChange,
                            label = { Text(stringResource(R.string.pairing_partner_email_label)) },
                            isError = form.emailErrorRes != null,
                            supportingText = form.emailErrorRes?.let {
                                { Text(stringResource(it)) }
                            },
                            singleLine = true,
                            enabled = !form.isBusy,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    item {
                        OutlinedButton(
                            onClick = viewModel::sendEmailInvitation,
                            enabled = !form.isBusy,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.pairing_invite_by_email)) }
                    }

                    if (current.incoming.isNotEmpty()) {
                        items(current.incoming, key = { it.id }) { invite ->
                            IncomingInviteCard(
                                invite = invite,
                                onAccept = { viewModel.acceptIncoming(invite.id) },
                                onReject = { viewModel.rejectIncoming(invite.id) }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showUnpairConfirm) {
        val partnerName = (state as? PairingState.Paired)?.partner?.name.orEmpty()
        ConfirmationDialog(
            title = stringResource(R.string.pairing_unpair_confirm_title),
            message = stringResource(R.string.pairing_unpair_confirm_message, partnerName),
            confirmText = stringResource(R.string.pairing_unpair_confirm_action),
            dismissText = stringResource(R.string.pairing_cancel),
            onConfirm = {
                viewModel.unpair()
                showUnpairConfirm = false
            },
            onDismiss = { showUnpairConfirm = false }
        )
    }

    // A shared link may have been forwarded by a third party — never redeem it
    // without the user saying yes.
    pendingDeepLinkCode?.let { code ->
        ConfirmationDialog(
            title = stringResource(R.string.pairing_link_confirm_title, code),
            message = stringResource(R.string.pairing_link_confirm_message, code, ""),
            confirmText = stringResource(R.string.pairing_link_accounts),
            dismissText = stringResource(R.string.pairing_cancel),
            onConfirm = {
                viewModel.redeemCode()
                pendingDeepLinkCode = null
            },
            onDismiss = { pendingDeepLinkCode = null }
        )
    }

    if (form.showQrDialog && form.qrBitmap != null) {
        AlertDialog(
            onDismissRequest = viewModel::dismissQr,
            title = { Text(stringResource(R.string.pairing_qr_dialog_title)) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.pairing_qr_dialog_message))
                    form.qrBitmap?.let { bitmap ->
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = stringResource(
                                R.string.pairing_qr_code_content_description
                            ),
                            modifier = Modifier.size(256.dp).padding(top = 16.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::dismissQr) {
                    Text(stringResource(R.string.pairing_close))
                }
            }
        )
    }
}
```

- [ ] **Step 10: Build, test and check lint**

```bash
$env:JAVA_HOME='C:\Program Files\Android\Android Studio1\jbr'; ./gradlew assembleDebug testDebugUnitTest lint detekt
```

Expected: BUILD SUCCESSFUL, all unit tests green. Resolve any `MissingTranslation` warnings by adding the missing keys rather than suppressing them.

- [ ] **Step 11: Commit**

```bash
git add app/src/main/java/com/coparently/app/presentation/pairing app/src/test/java/com/coparently/app/presentation/pairing app/src/main/res/values/pairing_strings.xml app/src/main/res/values-cs/pairing_strings.xml app/src/main/res/values-de/pairing_strings.xml app/src/main/res/values-ru/pairing_strings.xml app/src/main/res/values-uk/pairing_strings.xml
git commit -m "feat(pairing): rebuild the pairing ViewModel and screen around invite codes"
```

---

### Task 7: A QR scanner with an actual camera

**Files:**
- Modify: `app/build.gradle.kts` (CameraX dependencies)
- Create: `app/src/main/java/com/coparently/app/presentation/pairing/QrScannerScreen.kt`
- Modify: `app/src/main/java/com/coparently/app/presentation/pairing/QRScannerActivity.kt` (delete the placeholder screen and the hand-rolled JSON parser)

**Interfaces:**
- Consumes: `PairingUri.extractCode` (Task 1), `PairingFunctions` via `PairingRepository.redeem` (Task 5).
- Produces: `QRScannerActivity` returns `RESULT_OK` with `EXTRA_CODE` (`"pairing_code"`) — a plain 6-character code, replacing `EXTRA_INVITATION_ID`, `EXTRA_INVITER_NAME` and `EXTRA_INVITER_EMAIL`.

- [ ] **Step 1: Add the CameraX dependencies**

In `app/build.gradle.kts`, next to the ML Kit block:

```kotlin
    // CameraX — live preview for the pairing QR scanner. ML Kit only analyses
    // frames; something has to produce them.
    val cameraxVersion = "1.4.1"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")
```

- [ ] **Step 2: Write the scanner composable**

`app/src/main/java/com/coparently/app/presentation/pairing/QrScannerScreen.kt`:

```kotlin
package com.coparently.app.presentation.pairing

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.coparently.app.R
import com.coparently.app.domain.pairing.PairingUri
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

/**
 * Live camera preview that reports the first pairing code it sees.
 *
 * @param onCodeScanned Invoked once with a validated 6-character code.
 */
@OptIn(ExperimentalGetImage::class)
@Composable
fun QrScannerScreen(onCodeScanned: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    if (!hasPermission) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = stringResource(R.string.pairing_camera_permission_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )
            Text(
                text = stringResource(R.string.pairing_camera_permission_message),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 16.dp)
            )
            Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                Text(stringResource(R.string.pairing_grant_permission))
            }
        }
        return
    }

    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
        )
    }
    var delivered by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            analysisExecutor.shutdown()
            scanner.close()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                val previewView = PreviewView(viewContext)
                val providerFuture = ProcessCameraProvider.getInstance(viewContext)
                providerFuture.addListener({
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                        val mediaImage = imageProxy.image
                        if (mediaImage == null || delivered) {
                            imageProxy.close()
                            return@setAnalyzer
                        }
                        val input = InputImage.fromMediaImage(
                            mediaImage,
                            imageProxy.imageInfo.rotationDegrees
                        )
                        scanner.process(input)
                            .addOnSuccessListener { barcodes ->
                                barcodes.asSequence()
                                    .mapNotNull { it.rawValue }
                                    .mapNotNull { PairingUri.extractCode(it) }
                                    .firstOrNull()
                                    ?.let { code ->
                                        if (!delivered) {
                                            delivered = true
                                            onCodeScanned(code)
                                        }
                                    }
                            }
                            .addOnCompleteListener { imageProxy.close() }
                    }
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis
                    )
                }, ContextCompat.getMainExecutor(viewContext))
                previewView
            }
        )
        Text(
            text = stringResource(R.string.pairing_qr_hint),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.BottomCenter).padding(32.dp)
        )
    }
}
```

- [ ] **Step 3: Slim the activity down**

Rewrite `QRScannerActivity.kt` so it only hosts `QrScannerScreen` and returns the code. Delete `parseQRCodeContent`, `extractJsonValue`, `processInvitation`, `QRInvitationData`, `QRScannerViewModel`, `QRScannerUiState` and the old `QRScannerScreen` composable — the hand-rolled JSON parsing has no caller once the QR carries a `coplanly://` URI.

```kotlin
package com.coparently.app.presentation.pairing

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.coparently.app.presentation.theme.CoPlanlyTheme

/**
 * Full-screen QR scanner for pairing.
 *
 * Returns `RESULT_OK` with [EXTRA_CODE] set to a validated 6-character invite
 * code. Redeeming it is the caller's job — this activity only reads the camera.
 *
 * Stays an [AppCompatActivity] so per-app language selection keeps applying
 * (see the i18n rules in CLAUDE.md).
 */
class QRScannerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CoPlanlyTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    QrScannerScreen(
                        onCodeScanned = { code ->
                            setResult(RESULT_OK, Intent().putExtra(EXTRA_CODE, code))
                            finish()
                        }
                    )
                }
            }
        }
    }

    companion object {
        /** Result extra holding the scanned invite code. */
        const val EXTRA_CODE = "pairing_code"
    }
}
```

- [ ] **Step 4: Wire the result back into the screen**

In `PairingScreen.kt`, replace the plain `context.startActivity(...)` for the scanner with a `rememberLauncherForActivityResult`:

```kotlin
    val qrScannerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            result.data?.getStringExtra(QRScannerActivity.EXTRA_CODE)?.let { code ->
                viewModel.onCodeInputChange(code)
                viewModel.redeemCode()
            }
        }
    }
```

and launch it with `qrScannerLauncher.launch(Intent(context, QRScannerActivity::class.java))`.

- [ ] **Step 5: Verify on a device**

```bash
$env:JAVA_HOME='C:\Program Files\Android\Android Studio1\jbr'; ./gradlew installDebug
```

Then, on the Pixel, open Pairing → Show QR; on the Samsung, open Pairing → Scan QR and point it at the Pixel's screen. Expected: the Samsung's code field fills in and the accounts link.

Before any `adb shell input` on these devices, confirm the app is actually in focus — both phones are the user's personal devices:

```bash
$env:Path="$env:LOCALAPPDATA\Android\Sdk\platform-tools;$env:Path"; adb -s adb-RZGL424E9DY-DpsmkB._adb-tls-connect._tcp shell dumpsys window | Select-String -Pattern "mCurrentFocus"
```

- [ ] **Step 6: Commit**

```bash
git add app/build.gradle.kts app/src/main/java/com/coparently/app/presentation/pairing
git commit -m "feat(pairing): replace the QR scanner placeholder with a CameraX preview"
```

---

### Task 8: Deep link `coplanly://pair?code=…`

**Files:**
- Modify: `app/src/main/AndroidManifest.xml:30-56`
- Modify: `app/src/main/java/com/coparently/app/presentation/MainActivity.kt`
- Modify: `app/src/main/java/com/coparently/app/presentation/navigation/NavGraph.kt:404-416` and the `Screen.Pairing` definition at line 620

**Interfaces:**
- Consumes: `PairingUri` (Task 1), `PairingScreen(prefilledCode = …)` (Task 6).
- Produces: `Screen.Pairing.route = "pairing?code={code}"`, `Screen.Pairing.ARG_CODE = "code"`, `Screen.Pairing.routeWithCode(code: String?): String`.

- [ ] **Step 1: Update the manifest**

On `MainActivity`, change `android:launchMode="singleTop"` to `android:launchMode="singleTask"` and add a second intent filter:

```xml
            <!-- Pairing deep link. A custom scheme, not an App Link: the project
                 owns no domain, so https links could never be verified. -->
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:scheme="coplanly" android:host="pair" />
            </intent-filter>
```

Delete the whole `<intent-filter android:autoVerify="true">` block on `QRScannerActivity` (lines 46–55): `coparently.app` is not ours, so verification always failed and the filter only ever added noise.

- [ ] **Step 2: Route the intent in MainActivity**

Add a `StateFlow` for the pending code, set it in `onCreate` and `onNewIntent`, and pass it into `NavGraph`:

```kotlin
    private val _pendingPairingCode = MutableStateFlow<String?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readPairingCode(intent)
    }

    /**
     * Extracts a pairing code from a `coplanly://pair?code=…` intent.
     *
     * The code is only pre-filled — redeeming it still needs an explicit
     * confirmation, because a share link may have been forwarded on.
     */
    private fun readPairingCode(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != PairingUri.SCHEME || data.host != PairingUri.HOST) return
        _pendingPairingCode.value = PairingUri.extractCode(data.toString())
    }
```

Call `readPairingCode(intent)` at the end of `onCreate` (before `setContent`), collect `_pendingPairingCode` inside `setContent`, and navigate when it becomes non-null:

```kotlin
                    val pairingCode by _pendingPairingCode.collectAsState()
                    LaunchedEffect(pairingCode) {
                        pairingCode?.let { code ->
                            navController.navigate(Screen.Pairing.routeWithCode(code))
                            _pendingPairingCode.value = null
                        }
                    }
```

- [ ] **Step 3: Add the argument to the route**

In `NavGraph.kt`, change the `Screen.Pairing` object:

```kotlin
    data object Pairing : Screen("pairing?code={code}") {
        /** Optional invite code carried by a `coplanly://pair` deep link. */
        const val ARG_CODE = "code"

        /** Builds the route, with [code] pre-filled when a deep link supplied one. */
        fun routeWithCode(code: String?): String =
            if (code.isNullOrEmpty()) "pairing" else "pairing?code=$code"
    }
```

and the composable:

```kotlin
            composable(
                route = Screen.Pairing.route,
                arguments = listOf(
                    navArgument(Screen.Pairing.ARG_CODE) {
                        type = NavType.StringType
                        defaultValue = ""
                    }
                ),
                enterTransition = { slideInFromRight() },
                exitTransition = { slideOutToLeft() },
                popEnterTransition = { slideInFromLeft() },
                popExitTransition = { slideOutToRight() }
            ) { backStackEntry ->
                PairingScreen(
                    onNavigateBack = { navController.popBackStack() },
                    prefilledCode = backStackEntry.arguments
                        ?.getString(Screen.Pairing.ARG_CODE)
                        ?.takeIf { it.isNotEmpty() }
                )
            }
```

The three existing `navController.navigate(Screen.Pairing.route)` call sites (lines 150, 351, 455) must become `navController.navigate(Screen.Pairing.routeWithCode(null))` — navigating to the raw route string with an unresolved `{code}` placeholder would not match.

- [ ] **Step 4: Verify the deep link on a device**

```bash
$env:JAVA_HOME='C:\Program Files\Android\Android Studio1\jbr'; ./gradlew installDebug
$env:Path="$env:LOCALAPPDATA\Android\Sdk\platform-tools;$env:Path"; adb -s adb-RZGL424E9DY-DpsmkB._adb-tls-connect._tcp shell am start -a android.intent.action.VIEW -d "coplanly://pair?code=4F7K2M"
```

Expected: CoPlanly opens on the pairing screen with `4F7K2M` in the code field and the confirmation dialog showing. (The code is fictional, so confirming shows "No invitation matches that code" — that is the correct outcome.)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/java/com/coparently/app/presentation/MainActivity.kt app/src/main/java/com/coparently/app/presentation/navigation/NavGraph.kt
git commit -m "feat(pairing): route coplanly://pair deep links to the pairing screen"
```

---

### Task 9: Make the Home pairing CTA reactive

`HomeScreen` already renders a `PairingCta` when unpaired, but `HomeViewModel.paired` is read once in `init` and never updates — so after pairing on this phone the banner stays, and after being paired from the other phone it never clears.

**Files:**
- Modify: `app/src/main/java/com/coparently/app/presentation/home/HomeViewModel.kt:96-110`

**Interfaces:**
- Consumes: `PairingRepository.observePairingState()` (Task 5).
- Produces: `HomeViewModel.paired: StateFlow<Boolean>` — same name and type, now reactive.

- [ ] **Step 1: Inject the repository and derive the flag**

Add `private val pairingRepository: PairingRepository` to the constructor, then replace the `_paired` field and the `paired` property:

```kotlin
    /**
     * Whether a co-parent is linked. Driven by the pairing repository's
     * realtime state, so the CTA disappears the moment the other parent
     * accepts — without the user reopening the screen.
     */
    val paired: StateFlow<Boolean> = pairingRepository.observePairingState()
        .map { it is PairingState.Paired }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)
```

Delete `private val _paired = MutableStateFlow(false)` and the `_paired.value = …` assignment in `init`; leave `_partnerId` and `_userId` as they are — other flows read them.

- [ ] **Step 2: Build and verify on two devices**

```bash
$env:JAVA_HOME='C:\Program Files\Android\Android Studio1\jbr'; ./gradlew installDebug
```

With both phones unpaired, sit on Home on the Pixel while pairing from the Samsung. Expected: the Pixel's "invite your co-parent" card disappears on its own.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/coparently/app/presentation/home/HomeViewModel.kt
git commit -m "fix(home): drive the pairing CTA from realtime pairing state"
```

---

### Task 10: Pairing push notifications

**Files:**
- Modify: `app/src/main/java/com/coparently/app/data/remote/firebase/CoPlanlyMessagingService.kt:34-95`

**Interfaces:**
- Consumes: the `pairing_accepted` / `pairing_removed` payloads queued by Task 2.
- Produces: notifications that deep-link into the pairing screen.

- [ ] **Step 1: Fix the double notification**

`onMessageReceived` currently posts once for `remoteMessage.data` and again for `remoteMessage.notification`. The Cloud Function sends both, so every foreground push shows twice. Prefer the data payload and fall back to the notification payload only when the data payload has no title:

```kotlin
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        // The Cloud Function sends `notification` AND `data` on every push.
        // Handling both posts the same message twice in the foreground —
        // take the data payload and fall back only when it is empty.
        val data = remoteMessage.data
        val title = data["title"] ?: remoteMessage.notification?.title ?: getString(R.string.app_name)
        val body = data["body"] ?: remoteMessage.notification?.body.orEmpty()
        if (title.isEmpty() && body.isEmpty()) return

        showNotification(title, body, data["type"])
    }
```

- [ ] **Step 2: Deep-link the pairing types and use a real icon**

```kotlin
    private fun showNotification(title: String, body: String, type: String?) {
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val intent = when (type) {
            TYPE_PAIRING_ACCEPTED, TYPE_PAIRING_REMOVED ->
                Intent(Intent.ACTION_VIEW, Uri.parse("coplanly://pair")).apply {
                    setPackage(packageName)
                }
            else -> packageManager.getLaunchIntentForPackage(packageName)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            type.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            // android.R.drawable.ic_dialog_info is a framework placeholder and
            // renders as a grey blob in the status bar.
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }
```

Add to the companion object:

```kotlin
        private const val TYPE_PAIRING_ACCEPTED = "pairing_accepted"
        private const val TYPE_PAIRING_REMOVED = "pairing_removed"
```

If `R.drawable.ic_notification` does not exist, create it as a monochrome 24dp vector drawable at `app/src/main/res/drawable/ic_notification.xml` (white silhouette on transparent — Android tints status-bar icons and discards color):

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24"
    android:tint="#FFFFFF">
    <path
        android:fillColor="#FFFFFF"
        android:pathData="M19,3h-1V1h-2v2H8V1H6v2H5C3.89,3 3.01,3.9 3.01,5L3,19c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2V5c0,-1.1 -0.9,-2 -2,-2zM19,19H5V8h14v11z" />
</vector>
```

- [ ] **Step 3: Verify with a real push**

Pair the two phones, then unpair from the Samsung with the Pixel's app swiped away. Expected: the Pixel shows exactly one notification, and tapping it opens the pairing screen.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/coparently/app/data/remote/firebase/CoPlanlyMessagingService.kt app/src/main/res/drawable/ic_notification.xml
git commit -m "fix(notifications): stop double-posting pushes and deep-link pairing events"
```

---

### Task 11: Deploy the rules, remove the superseded code, run the acceptance pass

**Files:**
- Modify: `app/src/main/java/com/coparently/app/data/remote/firebase/CoParentPairingService.kt`
- Modify: `app/src/main/java/com/coparently/app/data/remote/firebase/QRCodeService.kt` (only if it still encodes the old JSON)
- Modify: `docs/CoPlanly/MVP_phases.md` is **not** touched; `CLAUDE.md` gets a short pairing note.

**Interfaces:**
- Consumes: everything above.
- Produces: no dead pairing code paths.

- [ ] **Step 1: Delete the superseded service methods**

From `CoParentPairingService.kt` remove `sendInvitation`, `acceptInvitation`, `rejectInvitation`, `getPendingInvitations`, `removePartnership` and `sendPairingNotification`. Keep only `getPartnerInfo`, which `PairingRepositoryImpl` does not replace and other call sites still use. `acceptInvitation` and `removePartnership` in particular wrote `users/{partnerId}` from the client — the write that strict rules reject and that Task 2 moved server-side.

Check for remaining callers before deleting:

```bash
git grep -n "CoParentPairingService" -- app/src/main/java
```

Every hit other than `getPartnerInfo` and the Hilt provider must be gone by now; if one is not, fix that call site rather than keeping the method.

- [ ] **Step 2: Confirm QRCodeService encodes the pairing URI**

`PairingViewModel.showQr` passes `PairingUri.build(invite.code)` as `invitationId`. If `QRCodeService.generatePairingQRCode` wraps its arguments in the old `{"type":"coparent_invitation",…}` JSON, change it to encode the passed string verbatim and rename the parameter to `content`, updating the KDoc. The scanner's `PairingUri.extractCode` finds a code inside JSON too, so either encoding scans — but the plain URI is what non-CoPlanly QR readers can act on.

- [ ] **Step 3: Full verification**

```bash
$env:JAVA_HOME='C:\Program Files\Android\Android Studio1\jbr'; ./gradlew clean assembleDebug testDebugUnitTest lint detekt
```

Expected: BUILD SUCCESSFUL, all unit tests green, no new detekt or lint errors. Report the actual output — do not claim success without it.

```bash
cd functions; npm test; npm run lint
```

- [ ] **Step 4: Deploy the strict Firestore rules**

This is the first point at which the client no longer writes another user's document, so the strict file from Task 3 can finally go live and replace the permissive `firestore.rules.simple` that the project has been running on.

```bash
firebase deploy --only firestore:rules --project coparently-a39c9
```

Expected: `Deploy complete!`.

Then install the build on both phones and check for permission regressions across the app — calendar, chat, expenses — not only pairing:

```bash
$env:Path="$env:LOCALAPPDATA\Android\Sdk\platform-tools;$env:Path"; adb -s adb-46061FDAS002YU-9SFSG6._adb-tls-connect._tcp logcat -c
```

Use the app, then:

```bash
$env:Path="$env:LOCALAPPDATA\Android\Sdk\platform-tools;$env:Path"; adb -s adb-46061FDAS002YU-9SFSG6._adb-tls-connect._tcp logcat -d | Select-String -Pattern "PERMISSION_DENIED"
```

Expected: no matches. If a collection is denied, fix the rule for it — do not roll back to `firestore.rules.simple` and do not work around it in the client.

- [ ] **Step 5: Two-phone acceptance run**

Install on both:

```bash
$env:JAVA_HOME='C:\Program Files\Android\Android Studio1\jbr'; $env:Path="$env:LOCALAPPDATA\Android\Sdk\platform-tools;$env:Path"; ./gradlew installDebug
```

Walk the eight scenarios from the spec and record the result of each:

1. Pixel generates a code → Samsung types it → **both** flip to Paired without restarting.
2. Unpair from Samsung → Pixel notices on its own.
3. Pixel shows QR → Samsung scans → paired.
4. Pixel shares the link → open on Samsung → confirmation dialog → paired.
5. Email invite from Pixel → shows on Samsung's Home → accepted.
6. Expired code (age one manually in the Firebase console) → "code expired" message.
7. Redeem a code while already paired → "already paired", no corruption.
8. Kill the app on the Pixel, accept from the Samsung → Pixel receives a push.

For any step needing `adb shell input`, first check the app has focus — both devices are the user's personal phones:

```bash
$env:Path="$env:LOCALAPPDATA\Android\Sdk\platform-tools;$env:Path"; adb -s adb-RZGL424E9DY-DpsmkB._adb-tls-connect._tcp shell dumpsys window | Select-String -Pattern "mCurrentFocus"
```

- [ ] **Step 6: Update CLAUDE.md**

Add to the "Things that are easy to get wrong" list:

```markdown
11. **Pairing writes never touch the other parent's user document from the client.**
    Accepting an invitation and unpairing go through the `acceptPairingInvitation` /
    `unpairCoParent` callables (`functions/index.js`) — `firestore.rules` allows a user
    to write only their own `users/{uid}`, and the old client-side path is why the
    permissive `firestore.rules.simple` had to be deployed. The strict rules are live
    as of this change.
```

And in "Known issues", strike the sentence saying strict rules still need deploying, replacing it with the deployed state.

- [ ] **Step 7: Commit and open the PR**

```bash
git add -A
git commit -m "refactor(pairing): drop the superseded client pairing service"
git push -u origin feature/coparent-collab
```

```bash
gh pr create --title "feat(pairing): finish co-parent pairing (code, QR, link, email)" --body "Implements docs/superpowers/specs/2026-08-01-coparent-pairing-design.md. Part A of three; chat (C) and custody-day swaps (B) follow on their own specs."
```

---

## Self-Review

**Spec coverage**

| Spec section | Task |
|---|---|
| Invitation document with `code` / `expiresAt` | 5 (write), 3 (rules) |
| One active invite, reused | 5 (`createOrReuseInviteCode`) |
| Code alphabet and length | 1 |
| `acceptPairingInvitation` guard matrix | 2 |
| `unpairCoParent` | 2 |
| `acceptQRInvitation` deleted | 2 |
| `PairingState` / `PairingInvite` / `PairingError` | 1 |
| `PairingRepository` + impl | 5 |
| `PairingFunctions` + error mapping | 4 |
| Realtime `users/{uid}` listener | 5 |
| Conversation created on pairing | 5 (`ensureConversationWith`) |
| Rebuilt screen, four components | 6 |
| CameraX QR scanner | 7 |
| `coplanly://pair` deep link, dead App Link removed | 8 |
| Home discoverability | 9 |
| Pairing pushes, double-notification fix, real icon | 10 |
| Strings in five locales | 6 |
| Unit tests | 1, 5, 6 |
| Cloud Function tests | 2 |
| Strict rules deployed | 11 |
| Manual two-phone run | 11 |
| Out of scope items | not implemented anywhere — correct |

`FirebaseErrorMapper.kt` from the spec's file table is **not** created as a separate file: the duplicated `when` block it was meant to hold now lives as `PairingFunctions.toPairingError` (Firebase side) and `PairingViewModel.messageFor` (resource side), which is where each half actually belongs. The spec's intent — map the error once, return a resource id, no English literals in the ViewModel — is met.

**Type consistency** — `PairingInvite.expiresAtMillis` is used under that name in Tasks 1, 5, 6. `PairingError` cases are spelled identically in Tasks 1, 4, 6. `EXTRA_CODE` is defined and consumed inside Task 7. `Screen.Pairing.routeWithCode` is defined and used in Task 8. `PairingViewModel.onCodeInputChange` / `redeemCode` are defined in Task 6 and called in Tasks 7 and 8.

**Known follow-ups, deliberately not in this plan** — the chat and change-request defects found during exploration (`ChatViewModel` flows built from constructor-time values, `MessageRepositoryImpl.syncWithFirestore` nesting infinite collects) belong to spec C. Custody sharing belongs to spec B. The `conversations`/`messages` Firestore rules are the one chat item pulled forward into Task 3: pairing auto-creates a conversation, so the strict rules file cannot be deployed without them.

**Amendments made before execution** (agreed with the project owner):
1. Task 3 no longer deploys the rules — it only edits the file, and adds the missing `conversations`/`messages` blocks. The deploy moved to Task 11 Step 4, because until then the client still writes `users/{partnerId}` and the two test phones would break mid-plan.
2. The original Tasks 6 (ViewModel) and 7 (screen) are merged into Task 6, and later tasks are renumbered down by one (11 tasks total). Splitting them left the module non-compiling at a commit boundary.
