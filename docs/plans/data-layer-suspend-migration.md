# Data Layer Suspend Migration — Deferred Proposal

**Status:** an interface-wide migration has not been implemented. Checked on
2026-11-04 against `cb7e4918`: `data/Stores.kt` still exposes synchronous store
methods, and `PreferenceSettingsStore.kt` still bridges DataStore with
`runBlocking`. This page describes the remaining work, not a completed migration.

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
| `PreferenceSettingsStore` | Synchronous interface backed by DataStore bridges | Choose suspend reads/writes or a clearly initialized snapshot contract |
| Session/model repositories | Synchronous facade methods call storage and file operations | Separate real IO from cheap pure getters and validate transaction ownership |
| `MemoryController` | Synchronous public methods; callers own dispatch | Coordinate store, semantic runtime and UI result contracts together |
| Presentation callers | Logic is distributed among focused controllers | Preserve ordering of state, persistence, cancellation and session ownership |
| Startup and composition | Initial state is built before normal interaction | Define readiness and failure behavior without hiding initialization races |

## Proposed migration order

1. **Inventory current execution paths.** Locate actual IO and `runBlocking`, then
   trace callers and dispatchers. Keep pure computation synchronous; do not mark
   an entire facade `suspend` merely for uniform spelling.
2. **Freeze one contract.** Choose one store and its production/test callers,
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
[validation_report.md](../validation_report.md). No code change or execution of
this proposal is implied by a documentation refresh.
