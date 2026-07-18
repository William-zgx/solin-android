# AGENTS.md — Solin Android coding-agent protocol

Machine-readable entry for coding agents. Humans should start with `README.md` /
`README.zh-CN.md` and `Guidebook.md`.

## Project

- **Name:** 栖知 Solin (Solin Android)
- **Kind:** Experimental, privacy-first Android agent
- **Harness mode:** **Mainline single-repo** (code + docs + scripts in one tree).
  Not a multi-package monorepo product with distributed harness submodules.
- **Status:** Experimental / personal evaluation. Not production store-ready.
- **Primary module:** `:app` (single app module; package boundaries are
  convention + architecture tests, not separate Gradle modules yet)

## Must-read before code changes

Always read only:

1. The relevant product/build sections of `README.md` or `README.zh-CN.md`
2. `docs/index.md` — use it to locate the owner document for the changed fact

Then read only what the task touches:

- `docs/agent_core_modules.md` for Agent, tool, or lifecycle changes
- `docs/optimization_plan_weaknesses.md` for structural or parallel work
- `docs/privacy_notice.md` for privacy-boundary changes
- The relevant file under `docs/plans/` or the active feature specification

Do not load every architecture or plan document for an unrelated local change.

Also useful: `Guidebook.md` (short architecture index at repo root).

## Code roots

Primary production packages under:

```text
app/src/main/java/com/bytedance/zgx/solin/
  orchestration/   # agent loop, routing, observation
  tool/            # ToolSpec, registry, executor, schemas
  skill/           # built-in skills, planning
  device/          # device context snapshots
  memory/          # local memory index / semantic runtime
  data/            # Room, settings, model/session repos
  ui/              # Compose UI (SolinScreen + components)
  runtime/         # LiteRT-LM + remote chat adapters
  safety/          # risk, confirmation, privacy decisions
```

Related packages you may touch when in scope: `action/`, `audit/`,
`background/`, `download/`, `multimodal/`, `presentation/`, `resource/`,
`storage/`, `app/` (composition root).

Tests: `app/src/test/`, `app/src/androidTest/`.  
Scripts: `scripts/`. Docs: `docs/`.

## Non-negotiables (do not weaken)

1. **LocalOnly default** — chat history, memory, private tool results, screen
   text, OCR, local images/attachments stay on-device unless the user explicitly
   chooses a remote path. Unknown privacy metadata fails closed as `LocalOnly`.
2. **Fail-closed** — invalid schema, missing capability, unsupported model path,
   or unclear privacy → refuse / local-only / require confirmation. Never silent
   upload or silent OCR fallback that bypasses policy.
3. **Confirmation for medium+ risk** — device actions go through permission,
   disclosure, confirmation, and audit. Do not bypass confirmation for medium
   or higher risk tools.
4. **No raw prompts in remote audit** — remote audit events must not persist
   raw user prompts; keep redaction / summary-only surfaces.
5. **Do not weaken `SafetyPolicy`** — tool risk levels, confirmation policy,
   `ToolSpec` tags / continuation policy, and privacy semantics stay compatible
   unless an explicit migration is planned and documented.
6. **No secrets in Git** — no API keys, HF tokens, keystores, model weights,
   user data, or release artifacts committed.

## God objects — extract, do not grow

Do **not** add large new logic into these hotspots:

| File | Role | Instead |
| --- | --- | --- |
| `SolinViewModel.kt` | presentation facade | extract controllers / pure helpers under `presentation/` or existing `memory/` |
| `SolinScreen.kt` | UI shell | extract leaf composables under `ui/components/` (or surface packages) |
| `AgentLoopRuntime.kt` | orchestration facade | extract routing/budget/policy collaborators under `orchestration/` |

Prefer thin facades that forward to focused collaborators. Incremental,
behavior-preserving moves only — no Big Bang rewrites.

## Parallel change ownership

- File ownership is exclusive within an integration batch. Every writing agent
  declares the files or directories it owns before editing.
- Two agents may not edit the same file concurrently. If scopes overlap, the
  integrator assigns one owner and serializes that hotspot.
- A non-owner treats owned files as read-only, waits for integration, and then
  rebases before continuing.
- Keep unrelated changes in separate branches and integration batches.
- Tests, documentation, and production callers that form one atomic contract
  change should have one explicit owner or an explicit integration order.

Shared contract changes, including DTOs, schemas, public interfaces, and
lifecycle semantics, require explicit coordination: freeze the contract and
integration order before parallel implementation.

## Isolated-worktree parallelism

- Parallelize implementation by the dependency DAG, not by task-list order.
  Development may run concurrently; integration must still follow dependency
  order.
- Every writing agent owns one branch and one isolated worktree, with one writer
  per worktree. Follow the file and contract ownership rules above.
- Serialize hotspot integration (`SolinViewModel.kt`, `SolinScreen.kt`,
  `AgentLoopRuntime.kt`, shared models, and database schema), even when
  surrounding work is parallel.
- Keep the primary worktree integration-only while parallel branches are
  active. Integrate small semantic commits in dependency order; do not merge
  unrelated branch history.
- Specification and quality reviews follow the review-convergence rules below
  and may run concurrently. The integrator resolves disagreements and owns the
  final gate.
- Validation follows the risk-based cadence below. Do not make every worktree
  repeat the same Gradle, privacy, or release command.
- Prefer reusing existing agents for follow-up work and retire stale preparation
  tasks so agent/thread limits do not become the bottleneck.
- If isolated worktrees are unavailable, fall back to one implementation writer
  instead of allowing concurrent writes in a shared checkout.

## Review convergence

- Treat one coherent change set, not each micro-commit or individual finding,
  as the default review unit.
- Start review only after the change set has a stable contract, focused tests
  pass, and the writer has produced a coherent review commit.
- Use parallel specification and quality reviewers for shared contracts,
  privacy/safety, persistence, concurrency, lifecycle, migration, build, or
  release behavior. A low-risk documentation, pure UI, fixture, or local helper
  change needs only one combined review unless its governing specification says
  otherwise.
- The integrator collects, deduplicates, and prioritizes all findings before
  sending one consolidated fix batch to the writer. Do not create one
  implementation/review cycle per finding.
- After a fix batch, re-review only the resolved findings and directly affected
  contract, privacy, persistence, concurrency, or lifecycle boundaries. Do not
  restart a full review unless the public contract or architecture
  materially changed.
- Micro-commits do not require independent dual review. High-risk changes must
  be included in the coherent change-set review; request an extra focused review
  only when such a change is integrated separately or materially changes the
  frozen contract.
- When a contract changes, cancel or retire reviews based on the obsolete
  contract before starting replacement reviews.
- When dual review is required, keep one stable specification reviewer and one
  stable quality reviewer for the change set. Avoid adding reviewers after
  every fix unless an uncovered specialist boundary requires it.
- After two fix/review rounds without convergence, stop general review churn.
  The integrator must isolate the disputed invariant, make an explicit design
  decision, add a deterministic regression test, and request one focused
  review. Do not waive unresolved safety or correctness findings.

## Risk-based validation cadence

- Optimize validation frequency, not correctness. Privacy, safety, persistence,
  concurrency, lifecycle, migration, build, and release boundaries remain
  mandatory when changed.
- During implementation, run the smallest deterministic test that covers the
  changed behavior. Do not run the full affected suite after every
  micro-commit.
- The change owner owns the default test execution. Reviewers inspect the test
  design and recorded evidence; they do not rerun identical commands unless
  evidence is missing, suspicious, environment-dependent, or a new reproducer
  is required.
- Reuse successful test evidence while its covered files, contracts, and
  relevant dependencies are unchanged. Comments and unrelated commits do not
  invalidate it.
- Consolidate related fixes, then run the focused affected suite once before
  final review.
- Documentation-only changes require only relevant formatting, link, or doc
  checks. They do not require Gradle compilation unless documentation is
  generated or executable.
- Avoid concurrent heavyweight Gradle executions across worktrees. Lightweight
  deterministic tests may run in parallel.
- Run the applicable full Gradle gate and any affected privacy, migration, or
  release gate once on the integrated branch. Run a gate earlier only when the
  branch directly changes that boundary.
- Device/emulator acceptance remains separate (`docs/phone_acceptance.md`).
  Record unavailable connected tests as pending; never infer device behavior
  from JVM tests.

## Validation commands

Choose the smallest applicable command instead of running this entire list:

```bash
# Environment/bootstrap or build-infrastructure changes
scripts/doctor.sh
scripts/verify_local.sh

# Affected JVM behavior
./gradlew :app:testDebugUnitTest --tests "<affected-test-pattern>"

# Compile-sensitive UI, wiring, API, or resource changes
./gradlew :app:compileDebugKotlin

# Package or architecture-boundary changes
./gradlew :app:testDebugUnitTest --tests "com.bytedance.zgx.solin.architecture.*"

# Integrated branch gate
./gradlew :app:testDebugUnitTest
```

For privacy-sensitive code or documentation, run `scripts/privacy_scan.sh`
after the change as documented in the README.

## How to deliver a change

1. Pick the owner package and list the files or directories you will touch.
2. Prefer extract-and-forward over growing god objects.
3. Keep privacy / safety / audit semantics unchanged unless the task is an
   explicit, documented policy change.
4. Add or update tests near the boundary you change.
5. Run the smallest applicable validation tier above; report commands,
   outcomes, and any honest pending device/release evidence in the PR.
6. Update the **owner** doc only when facts move (`docs/index.md` editing rules).

## Out of scope for agents by default

- Relaxing fail-closed, confirmation, or LocalOnly defaults
- Committing models, keys, or user data
- Multi-module Gradle splits without an explicit architecture proposal and
  stabilized boundary tests
- Unrelated refactors mixed into feature work
