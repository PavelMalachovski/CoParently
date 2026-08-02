# Chat: sync, read state and notifications — design

**Date:** 2026-08-02
**Branch:** continues on `feature/coparent-collab` (or a fresh branch off it once pairing merges)
**Scope:** part C1 of the chat work. C2 — the change-request card in the thread and photo attachments — follows on its own spec.

## Problem

Chat compiles, opens and sends, but the two parents do not converge on the same conversation, nothing is ever marked read, and no one is told a message arrived.

Four concrete defects, all verified in the code and three of them observed on the owner's two devices:

1. **The sync loop can only ever process one conversation.** `MessageRepositoryImpl.syncWithFirestore` collects `getConversations(uid)` — an infinite snapshot flow — and inside that collector calls `syncMessagesForConversation`, which collects another infinite snapshot flow. The inner collector never returns, so the outer one never advances past its first emission. New conversation snapshots are never processed.
2. **Conversations are created with a random UUID.** `UUID.randomUUID()` on each device, so two phones that both decide a conversation is needed create two different documents with identical participants. Combined with defect 1, the two phones settle on different threads and never see each other's messages. This is exactly what the owner observed.
3. **`FirestoreMessageDataSource.markAsRead` is an empty function** with a comment saying it will be done later. Read state exists only in Room, so it never crosses to the other device — and the unread badge it was meant to feed cannot work.
4. **`unreadCount` is never incremented.** It is written to Firestore from a local field that nothing ever raises, so it is always zero.

There is also no notification when a message arrives, and `MessageSendStatus` has only `SENDING`, `SENT` and `ERROR` — there is nowhere to record that the other parent received or opened a message.

## Decisions taken

| Question | Decision |
|---|---|
| How many conversations exist | **Exactly one per parent pair.** A pair has a single thread; there is no conversation list to maintain. |
| Where read state lives | **On the conversation**, as a `{uid: timestamp}` map — one write per read event, not one per message. |
| Photo attachments | Deferred to C2. |
| Change-request card in the thread | Deferred to C2. |
| Scope split | C1 makes chat correct: sync, read, unread, notifications, ticks. C2 adds the two features. |
| Notification content | Sender name **and a message preview**. Stated explicitly because previews appear on a lock screen and this app's messages can be contentious; the owner accepted the default. |

## Architecture

### A deterministic conversation id

The root fix for defect 2 is to stop generating an id at all. The conversation id is derived from the two participant UIDs, sorted lexicographically and joined:

```
conversationId = listOf(uidA, uidB).sorted().joinToString("_")
```

Both devices compute the same value independently, with no query and no coordination. Creation becomes idempotent by construction — a second `set()` overwrites the same document rather than making a rival one. The whole class of "two conversations, same participants" bugs stops being something to defend against.

This also removes the get-or-create lookup in `ChatViewModel.startConversationWithPartner` and the one in `PairingRepositoryImpl.ensureConversationWith`; both become "compute the id, write if absent".

**Migration.** Existing conversations have random UUIDs, and the owner's two phones currently hold real messages in two divergent threads. A one-time migration runs after pairing state is known: find every conversation whose participant set equals the current pair, and if any of them is not the canonical id, re-point its messages at the canonical conversation and mark the legacy document `archived: true`. Messages keep their own ids, so re-pointing is a `conversationId` field update and cannot duplicate them. The migration is idempotent and safe to run on every launch; it does nothing once no legacy document remains.

### Sync: delete the loop rather than untangle it

With one conversation whose id is known before any query, the outer conversation-list traversal has no reason to exist. `syncWithFirestore` — and with it the nested collector — is deleted. What remains is two independent realtime sources the UI collects:

- `observeConversation(conversationId): Flow<Conversation?>`
- `observeMessages(conversationId): Flow<List<Message>>`

Each writes what it receives into Room and each is collected on its own; neither is nested inside the other. Room stays the offline-first source of truth and the UI reads from it, unchanged in spirit from the rest of the app.

This is a net deletion of code.

### Read, delivery and unread

The conversation document gains two maps:

```
conversations/{id}
  participants: [uidA, uidB]
  lastReadAt:      { uidA: 1754... , uidB: 1754... }   // epoch millis
  lastDeliveredAt: { uidA: 1754... , uidB: 1754... }
  lastMessageAt:   1754...
  archived: boolean            // set by the migration on superseded documents
```

- **Read** — opening the thread writes `lastReadAt.{myUid}` once. One write, regardless of how many messages were unread.
- **Delivered** — when a device ingests messages from the listener it writes `lastDeliveredAt.{myUid}` once per batch.
- **Unread count** — derived locally from Room: messages with `timestamp > lastReadAt[myUid]` whose `senderId` is not me. No extra reads, no counter to keep in step. The existing `Conversation.unreadCount` field stops being stored state and becomes a computed value.

**Ticks** fall out of the same two maps with no further fields. `MessageSendStatus` gains two cases:

| State | Meaning |
|---|---|
| `SENDING` | written to Room, not yet acknowledged by Firestore |
| `SENT` | Firestore accepted the write |
| `DELIVERED` | `lastDeliveredAt[otherUid] >= message.timestamp` |
| `READ` | `lastReadAt[otherUid] >= message.timestamp` |
| `ERROR` | the write failed |

**Stored versus derived.** Only `SENDING`, `SENT` and `ERROR` are ever written to the message row — they describe this device's own write attempt. `DELIVERED` and `READ` are computed at render time by comparing the message's timestamp against the conversation's two maps, and are never persisted on the message. The enum carries all five because it is what the UI renders; the persistence layer must reject or ignore an attempt to store the two derived cases rather than silently writing them.

### Rules

The current `conversations` update rule lets any participant write any field except `participants`. That means one parent can write the *other* parent's `lastReadAt` — marking their own messages as read on the recipient's behalf. Tighten it so a participant may only touch their own key in `lastReadAt` and `lastDeliveredAt`, and cover it in the emulator suite, which exists for exactly this.

The tightened rule must still permit the writes this design depends on: `lastMessageAt` (written by the sender), `archived` (written by the migration), and `title`. Only the two per-user maps are key-restricted. Verify each of those writes in the emulator rather than assuming, since an over-tight rule here fails silently into the same "message never syncs" symptom this spec exists to remove.

`messages` rules are unchanged: the existing update rule already restricts a participant to flipping `isRead` via `diff().affectedKeys().hasOnly(['isRead'])`, and this design stops using per-message read state, so nothing new is needed there.

### Notification on a new message

A Cloud Function triggers on `messages/{messageId}` create, reads the conversation to find the other participant, and queues a `notification_queue` document with `type: "chat_message"`, the sender's name as title and a preview of the content as body. This matches the existing pairing notifications exactly — same collection, same shape, drained by the same deployed `sendNotification`.

The client handles `chat_message` by deep-linking to the thread via `coplanly://chat`, declared alongside the existing `coplanly://pair` filter and routed the same way.

Suppression: the function skips the notification when the recipient's `lastReadAt` is already at or past the message — the case where they are looking at the thread as it arrives.

### Badge

The Chat tab badge shows the same locally computed unread count. `CoPlanlyBottomBar` already builds each tab with `NavigationBarItem`, which takes a `badge` slot, so this is a parameter through the existing composable rather than a new component.

## Testing

**Unit (JVM, MockK + coroutines-test + Turbine)**
- Conversation id: the two argument orders produce the same value; the value is stable across calls; two different pairs never collide.
- Unread count: messages from the other parent after `lastReadAt` count; my own messages never count; equal timestamps are treated as read.
- Tick derivation: each of the five states from a conversation and a message timestamp, including the boundary where the timestamps are equal.
- Migration: a legacy conversation's messages are re-pointed and the legacy document archived; running it twice changes nothing the second time; a pair with only a canonical conversation is untouched.

**Emulator rules suite** (`firestore-tests/`, already standing)
- A participant may write their own `lastReadAt` key and not the other's; likewise `lastDeliveredAt`.
- A non-participant may not read or write the conversation.
- The existing message rules still hold after the change.

**Cloud Functions** (`functions/test/`)
- The new trigger queues exactly one notification, addressed to the other participant, and skips when the recipient has already read past the message.

**On two devices** — the acceptance run: a message sent from one phone appears on the other without either being restarted; the sender's tick goes single → double → coloured as the other device receives and opens it; the badge appears and clears; a message arriving with the app killed produces a push that opens the thread; and the owner's currently divergent threads are merged into one by the migration.

## Out of scope

Photo and file attachments; the change-request card in the thread (both C2). Editing and deleting messages, typing indicators, group threads, message search, retention or export.
