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

Read in order (keep context small):

1. `README.md` or `README.zh-CN.md` — product contract, build, validation
2. `docs/index.md` — which doc owns which fact
3. `docs/agent_core_modules.md` — module ownership and Agent/tool lifecycle
4. `docs/optimization_plan_weaknesses.md` — structural debt plan, Wave ownership
5. For your area only: `docs/privacy_notice.md`, `docs/plans/*`, or
   `docs/ai_friendly_architecture_multi_agent_plan.md`

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

## Parallel agent ownership (Wave 1)

From `docs/optimization_plan_weaknesses.md` §5 — **file ownership is exclusive**
within a PR wave. Do not edit another track’s hotspots.

| Track | May write | Read-only |
| --- | --- | --- |
| Docs (A) | `AGENTS.md`, `Guidebook.md`, `docs/**` | production source |
| UI (B) | `ui/components/**` (new), `ui/SolinScreen.kt` | ViewModel, Loop |
| ViewModel (C) | `presentation/**` (new), `SolinViewModel.kt`, `memory/MemoryController.kt`, `SolinAppContainer.kt` if needed | Screen, Loop |
| Loop (D) | `orchestration/*Support*.kt`, `AgentRunBudget.kt` (new), `AgentLoopRuntime.kt` | Screen, ViewModel |
| Boundary (E) | `app/src/test/**/architecture/**` | production code |

Conflict: two agents need the same file → later finisher rebases/merges, or an
integrator serializes the hotspot. Do not stack unrelated features into a
structural PR.

Contract changes (`ToolRequest`, `ToolResult`, `AgentPlan`, `SkillPlan`,
`ChatUiState`) require explicit coordination — freeze first, then implement.

## Isolated-worktree parallelism

- Parallelize implementation by the dependency DAG, not by task-list order.
  Development may run concurrently; integration must still follow dependency
  order.
- Every writing agent owns one branch and one isolated worktree. A worktree has
  one writer, and a file or hotspot has one owner per integration wave.
- Freeze shared contracts before parallel implementation. Changes to shared
  DTOs, persistence schemas, public interfaces, or lifecycle semantics require
  an explicit contract owner and integration order.
- Serialize hotspot integration (`SolinViewModel.kt`, `SolinScreen.kt`,
  `AgentLoopRuntime.kt`, shared models, and database schema), even when
  surrounding work is parallel.
- Keep the primary worktree integration-only while parallel branches are
  active. Integrate small semantic commits in dependency order; do not merge
  unrelated branch history.
- Specification and quality reviews may run concurrently after a branch
  commit. The integrator resolves disagreements and owns the final gate.
- Run focused tests in each worktree. Run full Gradle, privacy, and release
  validation once on the integrated branch, unless a branch changes build
  infrastructure or a safety-critical boundary.
- Prefer reusing existing agents for follow-up work and retire stale preparation
  tasks so agent/thread limits do not become the bottleneck.
- If isolated worktrees are unavailable, fall back to one implementation writer
  instead of allowing concurrent writes in a shared checkout.

## Validation (mandatory for meaningful changes)

Local quick path:

```bash
scripts/doctor.sh
scripts/verify_local.sh
./gradlew :app:testDebugUnitTest
```

Focused / compile:

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest --tests "com.bytedance.zgx.solin.architecture.*"
```

Device / emulator acceptance is separate (`docs/phone_acceptance.md`); do not
claim device-only behavior from unit tests alone.

Before sensitive doc or code changes: `scripts/privacy_scan.sh` as documented
in the README.

## How to deliver a change

1. Pick the owner package and Wave track; list files you will touch.
2. Prefer extract-and-forward over growing god objects.
3. Keep privacy / safety / audit semantics unchanged unless the task is an
   explicit, documented policy change.
4. Add or update tests near the boundary you change.
5. Run the validation commands above; report commands + outcomes in the PR.
6. Update the **owner** doc only when facts move (`docs/index.md` editing rules).

## Out of scope for agents by default

- Relaxing fail-closed, confirmation, or LocalOnly defaults
- Committing models, keys, or user data
- Multi-module Gradle splits before architecture tests stabilize (Wave 5+)
- Unrelated refactors mixed into feature work
