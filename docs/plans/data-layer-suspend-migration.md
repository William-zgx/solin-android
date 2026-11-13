# Data Layer Suspend Migration — Incremental Plan

**Status:** the first vertical slice is implemented at `b435164f` (2026-11-13).
`RemoteSendPendingStore.consumePendingRemoteSend` is now suspend and atomic; its
startup callers propagate suspension and retry safely. The interface-wide migration
has not been implemented: other stores and `PreferenceSettingsStore` methods still
use synchronous contracts and DataStore `runBlocking` bridges.

Current ownership lives in [Data layer](../agent_core_modules.md#data-layer),
[Memory controller](../agent_core_modules.md#memory-controller) and
[Presentation controllers](../agent_core_modules.md#presentation-controllers).

## Problem and scope

A blocking bridge occupies its calling thread and does not naturally inherit the
caller's coroutine cancellation. The current thread must be established for each
call site: many callers already dispatch persistence and initialization work to
background execution. The presence of `runBlocking` alone does not prove that
every user flow currently blocks the main thread.

The original inventory covered synchronous stores, repositories and roughly 60
ViewModel call sites. Those callers have since moved into controllers and other
owners, so its line numbers and mechanical replacement examples are obsolete.
The original inventory is available in
[the plan at `cb7e4918`](https://github.com/William-zgx/solin-android/blob/cb7e4918057fb3a6ed71df87ba24ecc8875567c7/docs/plans/data-layer-suspend-migration.md).

| Boundary | Current fact | Migration decision needed |
| --- | --- | --- |
| `PreferenceSettingsStore` | Pending-send consumption is suspend and uses one atomic DataStore edit; other methods retain synchronous bridges | Migrate remaining genuine IO independently; do not make cheap getters suspend for uniform spelling |
| Session/model repositories | Synchronous facade methods call storage and file operations | Separate real IO from cheap pure getters and validate transaction ownership |
| `MemoryController` | Synchronous public methods; callers own dispatch | Coordinate store, semantic runtime and UI result contracts together |
| Presentation callers | Logic is distributed among focused controllers | Preserve ordering of state, persistence, cancellation and session ownership |
| Startup and composition | Pending-send recovery completes before post-persistence work; each initialization attempt has a stable completion and can be retried | Preserve this contract while migrating other startup IO and defining their readiness/failure behavior |

## Implemented vertical slice

- `consumePendingRemoteSend` captures and removes the marker in the same
  DataStore transaction. A cancelled update cannot erase an unconsumed marker,
  and an older consumer cannot delete a newer marker through a separate read/clear.
- Startup recovery runs in `NonCancellable` after consumption so a consumed marker
  still fails closed. `ChatRemoteSendSupport`, `ChatController`,
  `StartupStateRestorer`, `SolinViewModel` and the Activity startup path propagate
  suspension without adding a new blocking UI shim.
- `MainActivity` uses one ordered lifecycle launch for restore, permission snapshot,
  content readiness and post-persistence work. Each deferred startup attempt settles
  its own `CompletableDeferred`; existing waiters observe failure or cancellation,
  and only a later retry installs a fresh deferred.
- Focused JVM tests cover atomic consumption, malformed markers, cancellation,
  fail-closed recovery and failure/cancellation retry with an early waiter. The full
  local gate passed at `b435164f`; commands and limits are recorded in
  [validation_report.md](../validation_report.md).

This is not evidence of interface-wide migration, main-thread responsiveness on a
phone, or process-death recovery on a device.

## Remaining migration order

1. **Inventory current execution paths.** Locate actual IO and `runBlocking`, then
   trace callers and dispatchers. Keep pure computation synchronous; do not mark
   an entire facade `suspend` merely for uniform spelling.
2. **Freeze one contract.** Choose the next store and its production/test callers,
   define dispatcher ownership, cancellation, failure and transaction behavior,
   and give the atomic change one writer.
3. **Migrate that boundary.** Convert genuine IO methods and propagate suspension
   through the owned callers. Remove replaced blocking bridges in the same batch.
   Do not introduce a new `runBlocking` shim in a UI callback to preserve a signature.
4. **Verify lifecycle ordering.** Cover session switch/delete, pending confirmation
   restoration, run cancellation, model download completion and startup failure
   where affected. A cancelled caller must not persist into a newer session.
5. **Integrate before expanding.** Run the smallest relevant tests during work and
   the required integrated gate once. Reuse valid evidence; do not run concurrent
   heavyweight builds in multiple worktrees.

## Non-negotiable contracts

- LocalOnly, encrypted secrets/database behavior and redacted audit surfaces stay
  unchanged; concurrency migration is not permission to upload data.
- A coroutine launch is not proof of completed persistence. APIs whose callers
  depend on durability must expose completion or failure explicitly.
- Cancellation must not split a transaction or leak an obsolete write into a new
  run/session. Define non-cancellable cleanup only for operations that require it.
- Preserve repository error semantics. Replacing blocking code with suspension
  must not convert failure into an empty successful result.
- [Architecture depth](../architecture_depth.md) governs new abstractions. Avoid
  a second forwarding facade solely to postpone migrating callers.

## Acceptance and evidence

Each migrated contract needs focused store/controller tests, applicable Room
migration or persistence checks, cancellation ordering tests and architecture
checks. Run the applicable integrated Gradle gate after integration. Main-thread
responsiveness and process-death recovery require separate device evidence;
JVM tests alone are insufficient.

Record actual commands, failures and pending device work in
[validation_report.md](../validation_report.md). The completed pending-send slice
does not imply completion of the remaining migration.
