# AI-Friendly Architecture Multi-Agent Refactor Plan

This plan translates the Codinx AI Friendly architecture and Harness guidance
into an executable refactor plan for Solin Android. It is a development plan,
not an implementation diff.

## Related Detailed Plans

This document outlines the multi-agent architecture vision. For step-by-step implementation plans, see:

- `docs/plans/viewmodel-split.md` — Splitting SolinViewModel into 8 focused controllers
- `docs/plans/uistate-split.md` — Decomposing ChatUiState into 10 sub-states
- `docs/plans/solin-screen-split.md` — Breaking SolinScreen's 91 composables into 12 components
- `docs/plans/data-layer-suspend-migration.md` — Migrating 9 data layer interfaces to suspend functions

The following foundational improvements have already landed:

- SolinLog structured logging facade (test-safe)
- SolinConstants centralized tuning parameters
- MemoryController extracted from ViewModel
- Evidence encryption at rest (AES/AndroidKeyStore)
- Network security config (HTTPS-by-default)
- AgentLoopRuntime ConcurrentHashMap concurrency fix
- ModelRepository dependency inversion (SettingsStore interface)

## Source Principles

- **Harness first**: an agent entering the repo must know where the rules live,
  where the code lives, and how to deliver a change without guessing.
- **Mainline mode**: Solin is a single Android repo, not a distributed harness
  with submodules. Code and docs live together.
- **Strict boundaries before Gradle modules**: establish package contracts and
  architecture tests first; move to physical Gradle modules only after the
  seams are stable.
- **Business-free mechanisms**: reusable mechanisms such as tool contracts,
  runtime adapters, storage primitives, schema validation, and OCR readers must
  not depend on UI or feature-specific business flow.
- **Small local context**: large files must become thin facades plus focused
  collaborators so humans and agents can modify one area without loading the
  entire app.

## Current Fit And Gaps

What already fits:

- `docs/index.md` and `docs/agent_core_modules.md` provide a useful project and
  Agent architecture entry.
- Production code is grouped by capability packages such as `tool`, `skill`,
  `orchestration`, `runtime`, `memory`, `device`, `data`, `storage`, and `ui`.
- `SolinAppContainer` is a clear composition root.
- Model packs are already separate dynamic feature modules.
- Verification scripts and release records are stronger than usual for an
  experimental Android app.

Main gaps:

- No root `AGENTS.md` / `Guidebook.md` protocol tells agents this is a
  mainline harness, which docs to read, which code roots to modify, and which
  validation commands are mandatory.
- The main app is still one physical Gradle module, so package boundaries are
  convention-only.
- Several files are too large for AI-friendly local context:
  `SolinViewModel.kt`, `SolinScreen.kt`, `AgentLoopRuntime.kt`,
  `ToolRegistry.kt`, `ToolExecutor.kt`, `BuiltInSkills.kt`,
  `ActionExecutor.kt`, and `MemoryRepository.kt`.
- Tool contract, registry, policy, validation, and executor logic are mixed.
- ViewModel, Activity, and Compose UI know too much about domain workflows.
- Tests are comprehensive but large; `SolinViewModelTest` and
  `AgentLoopRuntimeTest` are integration conflict points for parallel agents.

## Target Architecture

Keep the user-facing product architecture stable. Change internal ownership.

```text
root
  AGENTS.md                         # machine-readable harness protocol
  Guidebook.md                      # human/agent architecture index
  docs/
    index.md
    agent_core_modules.md
    ai_friendly_architecture_multi_agent_plan.md

app/src/main/java/com/bytedance/zgx/solin/
  app/                              # Android shell and composition entry
  presentation/                     # ViewModel facade, UI actions, coordinators
  ui/                               # Compose components grouped by surface
  orchestration/                    # Agent run facade and focused policies
  tool/                             # contract, registry, policy, validation, executor
  skill/                            # catalog, planning, execution
  action/                           # parsers and tool-request planning only
  runtime/                          # local/remote model runtime mechanisms
  memory/                           # memory domain service and indexing
  data/                             # Room, DataStore, repositories
  storage/                          # local document/vector storage mechanisms
  multimodal/                       # shared input and OCR/preview mechanisms
  background/                       # scheduled task domain and Android adapters
```

Future physical modules, only after boundary tests stabilize:

```text
:core:tool-contract
:core:tool-policy
:core:orchestration
:core:skill
:core:runtime
:core:storage
:feature:app
:app
:modelpackE2b
:modelpackE2bExtra
:modelpackE4b
:modelpackE4bExtra
```

## Non-Negotiable Invariants

- Local-first privacy stays intact. Unknown privacy metadata fails closed as
  `LocalOnly`.
- Remote sends involving sensitive text or images keep explicit confirmation.
- Tool names, schema meaning, risk level, confirmation policy, and trace fields
  remain backward compatible unless a migration is explicitly planned.
- Pending remote sends and pending tool confirmations must not replay after
  restart without a fresh user action.
- Low-risk app control remains bounded to observe, tap, type, submit search,
  scroll, back, and wait.
- Compose `testTag` and accessibility labels remain stable unless tests and
  docs are updated in the same change.
- Model weights, keys, signing secrets, user data, and release artifacts remain
  out of Git.

## Execution Waves

### Wave 1: Harness And Boundary Freeze

Goal: make the repo navigable and freeze contracts before large parallel edits.

| Agent | Scope | Deliverable | Acceptance |
| --- | --- | --- | --- |
| A1 Harness | `AGENTS.md`, `Guidebook.md`, `docs/index.md` | Mainline harness protocol, SPEC/code/validation entry points | A new agent can find docs, code, and required commands from the root |
| A2 Boundary Tests | `app/src/test/.../architecture/` | Import/package boundary tests | Tests catch forbidden dependencies such as orchestration -> Compose/Activity |
| A3 Contract Freeze | `ToolRequest`, `ToolResult`, `AgentPlan`, `SkillPlan`, `ChatUiState` | Compatibility notes and tests for public contracts | Parallel agents have stable facades to build against |
| A4 Test Fixtures | `app/src/test/` shared fixture packages | Shared fixtures for ViewModel, tool, skill, runtime, memory | Large tests can be split without copy/paste fakes |
| A5 Gradle Assessment | `settings.gradle.kts`, Gradle files | Module migration matrix, no module move yet | Clear future module graph and build risk list |

### Wave 2: Parallel Package Refactors

Run these in parallel after Wave 1. Each worker owns disjoint files and must not
rewrite another worker's facade.

| Agent | Module | Main Files | Target Shape | Local Acceptance |
| --- | --- | --- | --- | --- |
| B1 UI Chat | `ui` | `SolinScreen.kt` | `ui/chat`, `ui/trace` components | `SolinScreenDisplayTest` |
| B2 UI Model | `ui` | model manager sections | `ui/model` components | model manager UI tests/compile |
| B3 UI Trust | `ui` | remote disclosure, trust, audit panels | `ui/trust` components | remote send/audit UI tests |
| B4 UI Composer/Session/Background | `ui` | composer, session, background sheets | `ui/composer`, `ui/session`, `ui/background` | Android UI compile |
| B5 ViewModel Model/Startup | `presentation` | model download/load/setup methods | `StartupCoordinator`, `ModelManagerCoordinator` | model/startup ViewModel tests |
| B6 ViewModel Trust/Share | `presentation` | remote send and shared input methods | `RemoteTrustCoordinator`, `SharedInputCoordinator` | remote/share tests |
| B7 ViewModel Agent/Memory/Background | `presentation` | agent confirmation, memory, tasks | `AgentRunCoordinator`, `MemoryCoordinator`, `BackgroundTaskCoordinator` | agent/memory/task tests |
| B8 Activity Bridges | Android app shell | `MainActivity.kt` | permission, voice, share, MediaProjection bridges | `compileDebugAndroidTestKotlin` |

### Wave 3: Agent Orchestration Split

Preserve `AssistantOrchestrator` and `AgentLoopRuntime` as stable facades while
moving policy and persistence into smaller collaborators.

| Agent | Scope | New Boundary | Acceptance |
| --- | --- | --- | --- |
| C1 Contract | `AgentModels.kt` | run, plan, step, recovery model files | external API compatibility |
| C2 Trace | `AgentTraceStore.kt` | codec, pending restore, repair, redaction | trace privacy and restore tests |
| C3 Replanner | `AgentObservationReplanner.kt` | prompt builder, evidence extractor, guard, sequential replanner | replanner tests |
| C4 Initial Planning | `AgentLoopRuntime.runOnce` and initial plan helpers | `InitialAgentPlanner`, `ToolPlanFactory` | initial route tests |
| C5 Observation Core | observe result and decision helpers | `ToolObservationCoordinator`, `ObservationDecisionPolicy`, `RetryPolicy` | single-tool observation tests |
| C6 Public Evidence | public batch and remote tool exposure | `PublicEvidenceBatchObserver`, `RemoteToolPlanningGateway` | mixed batch fail-closed tests |
| C7 Skill/Low Risk | skill continuation and low-risk app control | `SkillProgressCoordinator`, `LowRiskAppControlPolicy` | skill checkpoint tests |
| C8 Recovery/External Outcome | pending restore, external outcome, cursor | `ExternalOutcomeCoordinator`, `ContinuationCursorService` | restart and external outcome tests |

### Wave 4: Tool, Skill, And Action Split

First extract contract/catalog files, then validation/policy/executor. This
prevents every worker from editing the same `ToolRegistry.kt` and
`BuiltInSkills.kt`.

| Agent | Scope | Target Files | Acceptance |
| --- | --- | --- | --- |
| D1 Tool Contract | `ToolModels.kt`, `ToolRegistry.kt` | `tool/contract/*` | tool names/spec order unchanged |
| D2 Tool Validation | registry validation and schema validation | `tool/validation/*` | invalid request/result tests unchanged |
| D3 Tool Policy | tag and safety helpers | `tool/policy/*` | confirmation/remote planning tests unchanged |
| D4 Tool Executors | `ToolExecutor.kt` | `tool/executor/*` | routing executor tests unchanged |
| D5 Android Intent Executor | `ActionExecutor.kt` | `tool/executor/AndroidIntentToolExecutor.kt`, action facade | action tests unchanged |
| D6 Action Parsers | action parser files | `action/parser/*`, `action/planning/*` | planner parser tests unchanged |
| D7 Skill Catalog | `BuiltInSkills.kt` | `skill/catalog/*` | built-in manifest list unchanged |
| D8 Skill Templates | skill planning code | `skill/planning/*` | skill runtime/progress tests unchanged |

### Wave 5: Mechanism And Infra Split

This wave clarifies which code is domain policy and which code is reusable
mechanism.

Business/domain semantics to keep above infra:

- Memory: remember/forget behavior, preference conflicts, user facts, task-state
  memories, privacy visibility, recall ranking.
- Runtime: local/remote generation request models, history budgeting, remote
  image capability, output quality policy.
- Multimodal: shared-input privacy, local/remote vision prompts, attachment
  readability, evidence summaries.
- Background: reminder and periodic-check task semantics, state transitions,
  background Skill constraints.
- Data-facing app policy: session, model selection, recommended model
  verification, remote model config, first-run settings.

Business-free infra/mechanisms:

- Room database, entities, DAO, and migrations.
- DataStore, encrypted preferences, DownloadManager, WorkManager,
  BroadcastReceiver, MediaProjection, MLKit, LiteRT, TFLite, OkHttp, PDF/RTF/
  OOXML parsers, Zvec native bridge, and shared-preferences document storage.

Target package shape for this wave:

```text
data/
  database/          RoomDatabase, Entity, DAO, migrations only
  settings/          Preference and secret stores
  session/           SessionRepository
  model/             ModelRepository and model selection
  migration/         Legacy prefs and local-storage migration

memory/
  contract/          MemoryIndex, LongTermMemoryControls, EmbeddingRuntime
  domain/            records, rules, conflict keys, ranking
  app/               MemoryRepository facade
  infra/room/        RoomMemory*Store
  infra/zvec/        ZvecMemory*Store

storage/
  contract/          Local storage contracts
  infra/sharedprefs/ SharedPreferencesLocalDocumentStore
  infra/zvec/        Zvec native/local vector adapters

runtime/
  contract/          LocalChatRuntime, RemoteChatRuntime, request/event models
  local/             LiteRT adapter, config, history budget, token estimate
  remote/            OkHttp transport, OpenAI body codec, SSE/tool-call parser
  embedding/         TFLite embedding runtime factory
  policy/            Adaptive generation and output quality guards

multimodal/
  contract/          SharedInput/OCR models and provider ports
  policy/            prompt/evidence/privacy decisions
  preview/           text/pdf/rtf/ooxml/image preview readers
  infra/android/     ShareIntentReader, MediaProjection OCR, MLKit adapter

download/
  contract/          ModelDownloadClient and download info
  infra/android/     Android DownloadManager service
  infra/http/        Hugging Face URL resolver

background/
  domain/            task models, policies, BackgroundSkillSpec
  app/               use cases and runners
  infra/android/     scheduler, worker, receivers, notification helper
  infra/room/        scheduled task store
```

| Agent | Scope | Target Shape | Acceptance |
| --- | --- | --- | --- |
| E1 Data Repositories | `data/` | separate repository interfaces, Room adapters, DataStore settings | data tests |
| E2 Memory Domain | `MemoryRepository.kt` | record service, semantic index controller, deletion audit, explicit memory parser | memory tests |
| E3 Storage Mechanisms | `storage/` | local document store and vector index remain business-free | storage parity tests |
| E4 Runtime Local | `LiteRtRuntime.kt` | runtime facade, engine config, history budget, token estimator | runtime config tests |
| E5 Runtime Remote | `RemoteChatRuntime.kt` | body builder, event parser, tool-call accumulator, HTTP adapter | remote runtime tests |
| E6 Multimodal | `MultimodalInputModels.kt` and readers | shared input models, mime policy, preview readers, OCR JSON | multimodal tests |
| E7 Background | `background/` | scheduled task domain, Android scheduler/worker adapters | background tests |
| E8 Composition Root | `SolinAppContainer.kt` | inject coordinators/use cases instead of raw repository graph | compile and integration tests |

### Wave 6: Tests, Scripts, Documentation, And Optional Gradle Modules

| Agent | Scope | Deliverable | Acceptance |
| --- | --- | --- | --- |
| F1 Test Split | large unit tests | smaller scenario tests with shared fixtures | full unit suite |
| F2 Architecture Gate | `scripts/verify_local.sh`, docs | architecture tests documented or wired into local verification | local verification path includes boundary gate |
| F3 Documentation | `docs/agent_core_modules.md`, README links | new ownership map and diagrams | docs contract tests |
| F4 Gradle Modules | Gradle files | only if package boundaries are stable | compile/test time remains acceptable |

## Cross-Agent Coordination Rules

- One agent owns one facade per wave. No two workers edit the same facade unless
  one is explicitly designated as integrator.
- Contract workers land first; implementation workers branch from that shape.
- Workers must list changed files, local validation, and any public contract
  drift.
- Main integrator resolves imports, runs full verification, and updates docs.
- If a worker needs to change `ToolRequest`, `ToolResult`, `AgentPlan`,
  `SkillPlan`, or `ChatUiState`, stop and route it through the contract owner.

## Verification Matrix

Local checks for every integration wave:

```bash
git diff --check
ANDROID_HOME=/data00/home/zouguoxue/android-sdk ./gradlew :app:compileDebugKotlin --no-daemon -Pkotlin.incremental=false
ANDROID_HOME=/data00/home/zouguoxue/android-sdk ./gradlew :app:testDebugUnitTest --no-daemon -Pkotlin.incremental=false
ANDROID_HOME=/data00/home/zouguoxue/android-sdk ./gradlew :app:compileDebugAndroidTestKotlin --no-daemon -Pkotlin.incremental=false
bash -n scripts/*.sh scripts/lib/*.sh
scripts/test_validation_scripts.sh
```

Focused checks:

```bash
ANDROID_HOME=/data00/home/zouguoxue/android-sdk ./gradlew :app:testDebugUnitTest --tests 'com.bytedance.zgx.solin.SolinViewModelTest' --no-daemon -Pkotlin.incremental=false
ANDROID_HOME=/data00/home/zouguoxue/android-sdk ./gradlew :app:testDebugUnitTest --tests 'com.bytedance.zgx.solin.orchestration.*' --no-daemon -Pkotlin.incremental=false
ANDROID_HOME=/data00/home/zouguoxue/android-sdk ./gradlew :app:testDebugUnitTest --tests 'com.bytedance.zgx.solin.tool.*' --no-daemon -Pkotlin.incremental=false
ANDROID_HOME=/data00/home/zouguoxue/android-sdk ./gradlew :app:testDebugUnitTest --tests 'com.bytedance.zgx.solin.skill.*' --no-daemon -Pkotlin.incremental=false
ANDROID_HOME=/data00/home/zouguoxue/android-sdk ./gradlew :app:testDebugUnitTest --tests 'com.bytedance.zgx.solin.runtime.*' --no-daemon -Pkotlin.incremental=false
ANDROID_HOME=/data00/home/zouguoxue/android-sdk ./gradlew :app:testDebugUnitTest --tests 'com.bytedance.zgx.solin.memory.*' --no-daemon -Pkotlin.incremental=false
scripts/verify_ai_behavior_eval.sh
scripts/verify_local.sh
```

## Rollout Recommendation

Do not start with Gradle modules. The fastest safe path is:

1. Add root harness protocol and architecture tests.
2. Freeze public contracts.
3. Extract file-level facades and focused collaborators inside the current
   `:app` module.
4. Split large tests and fixtures.
5. Only then migrate stable packages into Gradle modules.

This sequence reduces merge conflicts, keeps the app buildable after every
wave, and matches the Harness requirement that agents can find rules, find
code, finish work, and deliver evidence without guessing.
