# SDD ledger — plan: docs/superpowers/plans/2026-08-02-chat-sync.md

Branch: feature/coparent-collab
Plan committed at: 480ac66f
Tasks: 9.

Standing constraint carried from the pairing run: the two attached phones are the owner's
personal devices, and Chat holds his real private conversation. Never drive the Chat thread
with `adb shell input` — a subagent already opened it once by a mistapped coordinate.

Ledger lines below are appended as tasks complete.

Task 1: review found 2 Important (both plan-mandated) + 1 honesty finding.
Task 1: OWNER RULING - message timestamps stay a naive LocalDateTime; the single-timezone limitation is accepted and must be documented accurately in code (the existing KDoc justification was factually wrong). Real fix (epoch millis on Message.timestamp) deferred. CONSEQUENCE IF THE PARENTS EVER SPLIT TIMEZONES: messages stay unread forever, badge never clears, ticks never advance.
Task 1: OWNER RULING - add a guard rejecting a uid that contains the ConversationKey separator, plus the failing test.
Task 1: honesty finding - the implementer claimed the Kotlin compiler forced an out-of-scope MessagesList.kt edit; the reviewer showed the when is in statement position with no strict compiler flags, so it likely was not forced. Asked the implementer to test it and report what it actually observed.
Task 1: exhaustiveness dispute SETTLED by compilation - Kotlin 2.1 (K2) enforces enum when exhaustiveness regardless of statement position. The implementer was right; the first reviewer reasoned from pre-K2 semantics. The MessagesList.kt edit was genuinely forced.
Task 1: fix round 1/5 (2 addressed + 4 tests, 0 open; commits b807899..68d5db8; tests 16 -> 20)
Task 1: minor (deferred): the corrected timezone KDoc describes only the reader-behind direction; the reader-ahead case (premature DELIVERED/READ) is unmentioned, and "permanently" overstates a skew bounded by ~26h.
Task 1: minor (deferred): MessagesList.kt comment hardcodes "Task 6", which goes stale if the plan is renumbered.
Task 1: complete (commits f2d3f83..68d5db8, review clean)

Task 2: review (opus) approved the migration - audited column by column against 11.json/12.json, createdAt confirmed TEXT, INSERT preserves every surviving value, MIGRATION_11_12 registered. 3 Important findings.
Task 2: CONTROLLER RULING - two of the three belong to Task 4, not Task 2, and I am WIDENING TASK 4'S SCOPE accordingly:
  (a) syncWithFirestore rebuilds Conversation with the four new fields at defaults and REPLACEs the row, so every Chat open resets the marks. Harmless only because nothing writes them yet. Task 4 deletes the method - this is a hard gate on Task 4 landing.
  (b) The Home "Unread" tile now counts CONVERSATIONS, not messages, under a label that says messages. Use ChatReadState.unreadCount instead. Task 4 must open HomeViewModel.kt anyway.
  (c) Task 4's file list in the plan omits HomeViewModel.kt and ConversationsScreen.kt, both of which call the getConversations it removes - Task 4 will not compile as scoped. Widen it.
Task 2: fix dispatched for the third finding - no test covers the project's first table-rebuild migration; only one cold launch on the owner's live phone validated it.
Task 2: minor (deferred): ConversationsScreen computes hasUnread with an empty currentUserId before auth resolves, so it can flash a false badge once Task 4 populates lastMessageAtMillis.
Task 2: minor (deferred): ORDER BY lastMessageAtMillis DESC buries a message-less conversation forever; COALESCE or a createdAt tiebreaker would be honest.
Task 2: minor (deferred): malformed JSON in the mark columns throws out of toDomain rather than degrading (same habit as the pre-existing participantsJson line).
Task 2: fix round 1/5 (1 addressed, 0 open; commits 1234555..5fac8b2 - instrumented MigrationTestHelper test, 2 cases, verified to detect both a dropped column and per-row conflation; ran on a Pixel_7 API 36 emulator because both owner phones were unreachable over wireless adb)
Task 2: minor (deferred): the migration test is not wired into any gate - this project has no CI, so it only protects future migrations when someone remembers to run connectedAndroidTest.
Task 2: complete (commits 68d5db8..5fac8b2, review clean)

Task 3: review found 1 Important - allow create for conversations does not constrain the mark maps, so a participant can plant a forged foreign mark at creation time and bypass ownMarkOnly entirely. Reviewer confirmed it empirically with a temporary emulator test (assertSucceeds), then removed it. Matters because the deterministic id means whoever writes first performs the create.
Task 3: minor (deferred): a participant may delete their OWN mark via FieldValue.delete(), returning themselves to "never read". Affects only the caller's own state.
Task 3: minor (deferred): "modify an existing foreign key" is not tested separately from "add" - equivalent by affectedKeys() contract, so inference not a gap.
Task 3: fix round 1/5 (1 addressed, 0 open; commits ab38021..4f77377 - noForeignMarksOnCreate; suite 175 -> 179)
Task 3: complete (commits 5fac8b2..4f77377, review clean)

Task 4: review (opus) approved the core - nested loop gone, observers independent, dotted-path mark writes match the deployed rule, derived statuses never persisted, hard gate closed with a merge test that would catch a rebuild. 4 Important findings.
Task 4: OWNER RULING - the read mark must derive from the newest message timestamp, not System.currentTimeMillis(). A clock briefly set forward would otherwise write a far-future mark that the monotonic merge can never lower, zeroing that user's unread count forever with no recovery.
Task 4: controller check - the reviewer flagged a malformed comment at firestore.rules:287 that would break the deploy. FALSE ALARM, verified by reading the file: the comment is well-formed, an em-dash rendered oddly. No action.
Task 4: minor (deferred): mirrorConversation writes on every snapshot including Firestore's own echo of this device's markRead.
Task 4: minor (deferred): HomeViewModel subscribes the full message history purely to compute a count, and re-mirrors the whole batch per snapshot.
Task 4: minor (deferred): an offline send does not advance lastMessageAtMillis locally, so the sender's own row does not reorder until connectivity returns.
Task 4: fix round 1/5 (4 Important + 4 minors addressed, 0 open; commits 0975d02..41651fd; tests 295 -> 303). Falsification: the OLD absent-map test still passed under a wrong "local + remote" merge - the new stale-mark test is what catches it. Re-nesting the observers now fails the never-completing-flow test.
Task 4: CARRY INTO TASK 6 - the first open of a thread whose messages Room has not ingested yet records no mark at all (newestMessageMillis returns null), so the Home tile keeps its count until the user re-enters. Fix is to re-assert markRead when the messages flow emits for the open conversation, which also covers messages arriving while the thread is open.
Task 4: CARRY INTO TASK 6 - ChatScreen and ConversationsScreen use `conversation?.title ?: fallback`, which does not cover a BLANK title. Now reachable: a row mirrored before any successful ensureConversation has title = "". Use takeIf { isNotBlank() }.
Task 4: minor (deferred): MessageDao KDoc still attributes getMessagesOnce to the legacy merge; its only production caller is now newestMessageMillis.
Task 4: minor (deferred): bumpConversation advances the SENDER's lastReadAt to their own message timestamp, so a partner whose clock runs fast can show a later message as READ before opening the thread.
Task 4: complete (commits 4f77377..41651fd, review clean)

Task 5: implementer returned NEEDS_CONTEXT before writing code - found a real spec contradiction. Spec line 45 requires re-pointing conversationId on remote message docs; spec line 93 claims the messages rules need no change; the deployed messages update rule allows ONLY the isRead flag. As scoped, every remote re-point would have been PERMISSION_DENIED while the migration logged success and archived the legacy thread - the owner's two histories would never have converged, and MockK tests could not have caught it because they mock data-source success.
Task 5: OWNER RULING - extend the messages update rule narrowly: a message update may change EXACTLY conversationId and nothing else, only when the caller participates in both the source and destination conversation. Existing isRead-only path unchanged. Requires emulator coverage since this loosens a rule the owner already ruled on three times.
Task 5: spec correction required - the design doc sentence claiming the messages rules need no change is now false and must be fixed.
Task 5: review (opus) found 1 CRITICAL, demonstrated end-to-end in the emulator: the loosened re-point rule is a message-hiding primitive. A parent can mint a same-participants conversation at an arbitrary id, re-point the OTHER parent's message into it, and that message disappears from the canonical thread the other parent queries - permanently on a reinstall. The messages block states allow delete: if false three lines away; this is a path to the same outcome. NOT plan-mandated - the brief offered "constrain to the canonical id" and the implementer chose a looser third reading.
Task 5: fix dispatched - constrain the destination to the canonical id, pin the id derivation against Kotlin/Rules drift on both sides, add the attack as a test.
Task 5: also dispatched - the remote re-point loop is driven off LOCAL Room rows, so a message in Firestore under the legacy id but not in this device's Room is stranded permanently once archived; and the migration is a total no-op until Task 9 deploys the rules, which nothing said out loud.
Task 5: minor (deferred, being fixed in the same round): a pre-existing test now passes for the wrong reason and contradicts a new one; archiveLocally nulls lastMessageId via a REPLACE round trip; the rule is near Firestore's document-access ceiling.
Task 5: fix round 1/5 (1 Critical + 2 Important + 3 minors addressed, 0 open; commits 23683b5..ee1fb80; unit 313, emulator 188). Re-reviewer independently wrote 20 attack tests (208 passing with them) including UTF-16 collation-drift probes across five uid shapes - the Rules sort and Kotlin's agree. Attack denied in every variant; a canonical-thread message cannot be moved anywhere at all.
Task 5: CARRY INTO TASK 9 - FirestoreMessageDataSource.fetchMessageIds uses Source.DEFAULT, so with offline persistence on the one-shot remote read can return a CACHE result instead of throwing. Nothing ever listens to a legacy id, so that cache is normally empty - the union would silently revert to local-only and archive, which is the exact stranding finding 2 existed to prevent. Narrow window (needs a connectivity drop between ensureConversation's server ack and the query). One-line fix: Source.SERVER or reject when snapshot.metadata.isFromCache. Its KDoc also misstates the failure mode.
Task 5: minor (deferred): the new rule dropped the destination-existence check, so before the canonical conversation document exists remotely a parent can re-point a message to that id and it becomes unreadable until ensureConversation creates it. Self-heals on the next launch; cannot be re-opened.
Task 5: minor (deferred): a Room message that never reached Firestore makes repointMessage fail NOT_FOUND forever, so that legacy conversation is retried every launch and never archived. Non-lossy, but a permanently stalled merge with only a Log.w.
Task 5: complete (commits 41651fd..ee1fb80, review clean)

Task 6: review found 1 Important - the mark-writing collector triggers on _currentConversationId changing, but ConversationsScreen's own ChatViewModel instance also sets it via the FAB path (openConversationWith). So resolving the id marks the thread read/delivered before ChatScreen composes, discarding the co-parent's unread signal, and it sticks even if navigation is interrupted. The test named "a thread that is never opened never has its marks written" is vacuous - it never calls startConversationWithPartner.
Task 6: verified by the reviewer, not assumed - no write loop exists: a conversation-table write cannot invalidate a Room query scoped to the messages table.
Task 6: minor (in the same fix round): the mark collector's catch has an empty recovery body, so one transient error kills the auto re-assert for the ViewModel's lifetime; unreadCount's flatMapLatest restarts the inner listener on every mark write; ru and uk share identical delivered/read strings.
Task 6: fix round 1/5 (1 Important + 3 minors addressed, 0 open; commits e591d16..19e7798; tests 322 -> 323). _openedConversationId set only by onThreadOpened and cleared by onThreadClosed from a DisposableEffect keyed on conversationId; ConversationsScreen's separate ViewModel instance never sets it, so a FAB tap cannot mark anything.
Task 6: minor (deferred): the mark collector now retries indefinitely every 2s with no cap or backoff - better than the previous silent death, but a permissions error would retry forever without surfacing.
Task 6: minor (deferred): with a thread open, currentThreadMessages and openedThreadMessages each subscribe observeMessages(id) independently - one more concurrent listener than before.
Task 6: complete (commits ee1fb80..19e7798, review clean)

Task 7: review found 2 Important - the Czech "many" plural (non-integer category) was copied from "other" and uses genitive plural where Czech needs genitive singular; and the NavGraph growth (489 -> 492) was avoidable, since hiltViewModel resolves by composition position, not enclosing function, so an extraction preserves Activity-level scoping.
Task 7: verified by the reviewer beyond the diff - the badge is correct on every tab (ViewModel resolved against the Activity store owner, before NavHost), it clears without Chat being composed, and the graph-scoped instance can never write a mark because only onThreadOpened sets the gate and it never calls it.
Task 7: minor (deferred unless cheap): two concurrent listener chains on the same conversation while Chat is open - functionally harmless, a cost question only.
Task 7: fix round 1/5 (2 addressed, 0 open; commits 1b14157..2128bc2). Czech "many" corrected to genitive singular - textually identical to "few" by genuine Czech morphology, not copy-paste, and distinct in case from "other". NavGraph 492 -> 491 via rememberChatUnreadCount() called from the same composition position, so the Activity store owner still resolves.
Task 7: complete (commits 19e7798..2128bc2, review clean)

---

## How to resume this work in a fresh session

Everything needed is committed:

- Spec: `docs/superpowers/specs/2026-08-02-chat-sync-design.md`
- Plan: `docs/superpowers/plans/2026-08-02-chat-sync.md` (9 tasks)
- This file: the ledger, copied out of the git-ignored `.superpowers/sdd/` scratch so it survives.

Resume with the `superpowers:subagent-driven-development` skill pointed at the plan. Tasks with a
`complete` line above are done - do not re-dispatch them. Task reports live in
`.superpowers/sdd/2026-08-02-chat-sync/` on this machine only; they are useful but not required,
because every completed task names its commits and those are in git.

State at the time of writing: C1 Tasks 1-7 complete and reviewed, Task 8 (chat push) implemented
and awaiting its review, Task 9 (deploy + two-phone acceptance) not started. The preceding pairing
spec (`2026-08-01-coparent-pairing`) is fully complete and its rules, indexes and Cloud Functions
are deployed to coparently-a39c9. Nothing from THIS chat plan is deployed yet - Task 9 owns that,
and until it runs, the legacy-conversation merge is a no-op by design.
