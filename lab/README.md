# Solin capability lab

An opt-in bench that runs the app's **real Kotlin planning code** against small models served locally
by [Ollama](https://ollama.com), and records what those models can and cannot do. It answers one
question before any cloud-edge work: where is the capability boundary of a 2B–5B on-device model for
Solin's planning tasks?

Nothing here ships in the APK. The harness lives in the JVM unit-test source set
(`app/src/test/java/com/bytedance/zgx/solin/lab/`) and is skipped unless you opt in.

## Layout

| Path | What it is |
| --- | --- |
| `lab/datasets/observation_step.jsonl` | Suite A cases: user goal, screen, prior steps, gold next step |
| `lab/datasets/initial_planning.jsonl` | Suites B and C cases: user request, gold first tool and arguments |
| `lab/scripts/build_datasets.py` | Generates both datasets from the label tables in the script |
| `lab/scripts/summarize.py` | Turns `lab/results/<date>/*.jsonl` into `SUMMARY.md` and `failure_examples.md` |
| `lab/results/<YYYYMMDD>/` | One JSONL per (suite, model, mode), `manifest.json`, `run.log`, `SUMMARY.md`, `failure_examples.md` |

Suite D reads `docs/decision_routing_eval.json` directly.

## How to run

Prerequisites: Ollama on `http://127.0.0.1:11434` with the models pulled, JDK 21 and the Android SDK
(`local.properties` with `sdk.dir`).

```bash
export SOLIN_LAB=1                                  # opt in; without it every lab test is skipped
export SOLIN_LAB_MODELS=qwen3.5:2b,qwen3.5:4b,gemma4:e2b,gemma4:e4b   # optional, this is the default
export SOLIN_LAB_SUITES=A,B,C,D                     # optional, this is the default
export SOLIN_LAB_LIMIT=5                            # optional smoke run: first N cases per suite
./gradlew :app:testDebugUnitTest --tests "com.bytedance.zgx.solin.lab.*" --rerun --console=plain
python3 -I lab/scripts/summarize.py lab/results/$(date +%Y%m%d)
```

`--rerun` matters. The opt-in variable is not a Gradle task input, so without it Gradle can report
the task up to date or serve a previous (skipped) result from the build cache. Other knobs:
`SOLIN_LAB_OLLAMA_URL`, `SOLIN_LAB_DATE` (overrides the results folder name, 8 digits),
`SOLIN_LAB_HOST_NOTE` (names the machine that produced the numbers; the default is the Mac bench
host) and `SOLIN_LAB_MODES` (comma-separated model modes to run, for example `N-topk,json-schema`;
rule baselines always run).

## Errored runs never look like scores (Decision L1)

A case is **errored** when its `metrics.error` is non-null: the model call failed, so it answered
nothing. Every number in `SUMMARY.md` that comes from a run with at least one errored case carries
that run's error count in the same cell or sentence:

- fully errored run → `errored N/N`, and no score;
- partly errored run → `<value> (E errored)`.

This applies to every generated table and to the hand-written narrative. `summarize.py` computes the
per-run counts once and every renderer formats through one function (`run_cell` / `score_cell`), so
there is no second place to forget the annotation.

```bash
python3 -I lab/scripts/summarize.py lab/results/20261108          # regenerate
python3 -I lab/scripts/summarize.py --check lab/results/20261108   # exit 1 on a missing annotation
python3 -I lab/scripts/test_summarize.py                           # regression test (stdlib unittest)
```

`--check` reads the regenerated `SUMMARY.md`, including the hand-written text outside the GENERATED
markers, and fails on any line that cites an errored run (by model and mode) without its error
count. It is the deterministic gate for this rule; fix the narrative rather than the check. Three
guards:

- **Exact annotation, never a bare fraction.** A line satisfies the rule only if it carries that
  run's own token: `E errored` (or `E/N errored`) for a partly errored run, and `errored N/N` (or
  `N/N errored`) for a fully errored run. A bare fraction is not an annotation — `83/151` does not
  count as `3/151`, the bug that once let an unannotated row through.
- **Every errored run on the line.** A generated row can draw on several runs (one per mode column)
  and each of them is resolved from the column headers (or the `suite` / `mode` cell) and needs its
  own annotation. A narrative line is resolved by model+mode label, in any rendering: with or without
  backticks, the `litert/gemma-4-e2b` id, or the `E2B` / `E4B` / `270M` short form.
- **Drift.** `--check` regenerates every generated section from the JSONL in memory and fails on the
  first committed line that differs. A hand edit to a generated cell is therefore caught even when
  the row still carries every annotation. Regenerate instead of editing between the GENERATED
  markers.

`python3 -I -m unittest <path>` does not work here: `-I` drops the cwd from `sys.path`, so unittest
cannot import a path-qualified module. Run the test file as a script instead.

**Label the host.** Every devbox run must set `SOLIN_LAB_HOST_NOTE` (for example
`devbox CPU, Ollama 0.40.13, no GPU`) so its numbers land in a different table from the Mac MLX
run. A devbox CPU number is not comparable to a Mac MLX number and the two must never share a
column.

Run one model per Gradle invocation so the model is unloaded between models. A model whose Ollama
`/api/show` capabilities lack `tools` is recorded under `unsupported` in `manifest.json` and skipped
in Suite C. `think: false` is only sent to models that declare `thinking`.

To regenerate the datasets after changing a label table: `python3 -I lab/scripts/build_datasets.py`.

## What each suite measures

All model calls use `/api/chat` with `"think": false` (the app runs non-thinking) and greedy
sampling: `temperature 0, top_k 1, top_p 0.1, seed 7`. `num_ctx` is 8192 unless a mode needs more;
the JSONL records the prompt token count of every call.

### Suite A: observation step (`observation-step`)

Drives the production `ModelObservationReplanner` with `OllamaActionPlanningRuntime`, a byte-for-byte
mirror of `HybridActionPlanningRuntime`/`ModelBackedActionPlanner`: same `actionPrompt`, same system
instruction, same parser, same fallback to the rule planner. It is wired like `SolinAppContainer`
(observation-only tool registry, `maxModelReplans = 5`). Each case builds an
`AgentObservationReplanContext` from a UI Automator dump (`ui_dumps/real_app_search/`), a P4 replay
case (`ui_dumps/p4_replay_eval/`), `ObservationReplanPromptFixtures`, or an inline screen, with
observation data shaped like `DeviceControlToolExecutor` output.

Per case it records:
- **raw draft correctness**: the first model call's parsed draft, before any guard;
- **final correctness**: what `planNext` returns after the 8 guards and the optional second call
  (minimal prompt or corrective re-prompt). `null` means the loop stops;
- the guard rejection reasons, parse failures, model calls per step, latency and tokens.

### Suite B: initial planning (`initial-planning`)

| Mode | What runs |
| --- | --- |
| `R` | Production `InitialToolPlanner.planToolIfSupported` with no action model: skills, then `MobileActionPlanner` rules |
| `M-full` | Model only, prompt advertises the whole built-in registry (the retired pre-PF1 configuration seen by the 270M FunctionGemma) |
| `M-topk` | Model only, prompt advertises the top 8 tools from `LabToolRetriever` (lexical, deterministic) |
| `R+M` | Production order with a model installed: skills, then rule intent gate, then model, then rule fallback |
| `M-full-fix`, `M-topk-fix` | Lab-only ablation: `M-full` / `M-topk` with the prompt's `call:function {"arg":"value"}` placeholder replaced by `call:函数名 {"参数名":"值"}` |
| `M-topk-fix-lmf` | Production-faithful LocalModelFirst text shape: top 8 retrieved tools **plus** production's `answer` no-tool choice, with the placeholder fix |

The `-fix` modes (and Suite A's `replanner-fix`) exist because small models copy the placeholder
`function` as the tool name. They change only that text (`PlaceholderAblation`); they are not what
the app sends.

The `-lmf` mode is what production LocalModelFirst actually sends: the retrieved shortlist plus a
`answer` tool declaration (`LOCAL_MODEL_NO_TOOL_CHOICE`, title `直接回答`,
`orchestration/InitialToolPlanner.kt:43,52-60,136`). Choosing `answer` scores as **no tool**
(`rawKind no_tool_choice`), the way production reads it (`:144`); it is never registered, so nothing
can execute it. Here it is built on the placeholder fix because the production prompt text still
copies `function` on both files that can run it (see SUMMARY Suite F).

Metrics: tool-name accuracy, argument accuracy (all gold keys, and per key), false-positive tool rate
on no-tool cases, parse/format validity (a known tool in the `call:` format) and schema validity
(`ToolRegistry.validate`), latency, prompt tokens. `M-topk` also reports retriever recall@8;
`LabDatasetValidationTest` (no model) records recall@k for k in 4, 8, 12, 16 with the misses and
the mean `actionPrompt` size at each k.

### Suite C: native tools (`native-tools`)

The Suite B cases with the tools passed through Ollama's native `tools` field (JSON schema from
`ToolSpec.inputSchemaJson`) instead of the `call:` text format:

| Mode | What runs |
| --- | --- |
| `N-full` | Whole registry, system prompt says to always call one tool (like the app's) |
| `N-topk` | Top 8 retrieved tools, same system prompt |
| `N-topk-free` | Top 8 retrieved tools, system prompt allows a plain-text answer when no phone action is needed |
| `N-topk-lmf` | Production-faithful LocalModelFirst native shape: top 8 retrieved tools plus the `answer` no-tool choice (`N-topk` + `LOCAL_MODEL_NO_TOOL_CHOICE`) |

The `-lmf` modes are the candidate production configurations: they are the only ones that offer the
model the same choice the app does. `answer` scores as no tool.

### Production-faithful composite (`production-fidelity`, no model calls)

`ProductionFidelitySuiteTest` reads the saved Suite B/C JSONL and calls the production
`String.startsWithActionNegation()` (`action/BasicDraftActionParsers.kt:199`) on every case input.
Production never shows a negated request to the model (`orchestration/InitialToolPlanner.kt:100`
takes `planRuleFirst()`), so a guarded case is scored with the rules' `R` answer. It emits
`production-fidelity__negation-guard.jsonl` with raw vs guarded counting per mode, and splits each
no-tool false positive into orchestration-only (`ToolCapability.Orchestration` / unregistered name /
`answer`) versus a device, network or external-app action, naming private reads and network egress.

This tells us whether moving the app to LiteRT-LM native tool calling would help.

### Suite D: routing (`routing`)

The 26 labeled cases of `docs/decision_routing_eval.json`. The local model acts as the router, using
`DECISION_MODEL_PLACEMENT_INSTRUCTIONS` and `_CRITERIA` and answering `{"choice", "confidence"}`.
Modes: `json-prompt` (format requested in the prompt only) and `json-schema` (Ollama structured
output). The rule baseline is `RequestComplexityAggregator` fed exactly as `chatPlacementInputs`
feeds it. Metrics: accuracy, coverage and accuracy at confidence >= 0.9 (the app's gate).

Read the accuracy as "can this model answer the routing question", not as end-to-end app behavior.
The question text (`DECISION_MODEL_PLACEMENT_INSTRUCTIONS` / `_CRITERIA`) is the one production sends,
but the transport is not: the lab uses a flat `{"choice", "confidence"}` response under a system
prompt, while the app's only router (`runtime/CodivDecisionModelRouter.kt`) posts to a remote
`/systemone` endpoint and parses a nested `{"answers": {"placement": {..., "probabilities": {...}}}}`.
There is no local-model decision parser in the app. A fenced ```` ```json ```` answer, which the LiteRT
files emit, is stripped by the lab tests only; production's `parseDecisionResponse` would reject it.

### Suite E: the local model as an action gate (`action-gate`)

A lab-only experiment the app does not have today. One short call per Suite B request asks only
whether the request needs a phone action at all, as JSON `{"action": true|false, "confidence": 0..1}`
(same acceptance shape as Suite D). The gate opens on `action = true` with confidence >= the app's
routing gate (`DECISION_MODEL_MIN_CONFIDENCE`, 0.9); false, low confidence or invalid JSON keeps it
closed, so a broken gate fails closed to no tool. Metrics: precision and recall of the gate decision,
and the same over the 74 rule-gap cases. Explicit negations are labelled no-action.

### Offline composites

`summarize.py` also prints composites computed from the saved JSONL with no model calls:

- **R, then the model on the rule gap.** `R` keeps every plan it makes; the model mode answers only
  the 74 requests where `R` returned no tool (51 no-tool + 23 tool). Reported for every B and C mode,
  with the count of cases fixed and broken against `R` alone.
- **R, then the gate, then `N-full`.** `R` keeps its plans; where `R` returned no tool the gate
  decides, and only an open gate lets `N-full` answer. A closed gate yields no tool. A model that only
  has `N-topk` (the LiteRT files cannot run `N-full`) is scored with `N-topk`, and the row names the
  mode it used; the two are never mixed in one row.

## Labeling rules

Labels are written in `lab/scripts/build_datasets.py`, generated to `lab/datasets/`, and committed
before any model run. A label is never edited to match model output. If a label turns out wrong, fix
it in a separate commit that says why, and rerun.

### Suite A (next step on a screen)

1. The gold is the **next single step** a careful human operator would take toward the goal, given
   the prior steps. It is not the whole plan.
2. A case may accept several answers (`gold.accept`). A tool answer matches when the tool name is
   equal and:
   - `targetNodes`: the target equals one of these node ids, or the visible text / content
     description of that node (the executor resolves both). Comparison ignores case and whitespace.
   - `targets`: extra literal target values that are also correct (for example OCR element ids).
   - `targetOptional`: a missing target is also correct (`ui_type_text` may type into the focused
     field).
   - `text`: the typed text equals the user's query, ignoring case and whitespace.
   - `args`: every listed argument is equal.
3. `{"stop": true}` means the right move is no device action: the task is done, nothing on the
   screen supports progress, or the next control is critical (pay, send, delete, submit an order).
   In the app, a stopped observation loop returns control to the user. A model that outputs plain
   text, or a replanner that returns `null`, counts as stop.
4. App search follows tap entry, then type, then submit. Typing into a field that is not yet on the
   screen is wrong. On a results page that already shows the query, the search is done: stop, unless
   the goal asks for a follow-up step (sort, scroll, open map).
5. Critical screens (confirm order, chat with a typed message, multi-select with delete) have gold
   stop, because Solin's non-negotiables say the user taps those controls.
6. Taobao's input dump already shows the query in the field, so its "type" step is not labeled; only
   its submit step is.

### Suites B and C (first tool for a request)

1. The gold is the **first tool call** the app should make for the request, or no tool when the
   request is chat, a question, an explanation, a translation, or an explicit negation
   ("不要…", "别…", "只解释").
2. `gold.tools` lists every acceptable first tool. Some requests have two reasonable first tools (for
   example `search_maps` or `open_app_by_name` for "用高德地图导航去天安门").
3. `gold.args` lists only arguments the request pins down. Unlisted arguments are not scored. Value
   syntax: plain value means equal after trimming and lowercasing, and numbers compare numerically;
   `~x` means the value contains `x`; `a|b` means either alternative. `phoneNumber` compares digits
   only.
4. An in-app search ("在淘宝搜索牛奶") starts with `open_app_by_name` for that app. The search itself
   happens later in the observation loop (Suite A).
5. `planning_golden` labels are written for this bench. They are not copied from
   `planning_golden/expected.txt`, which pins what the rules do today, right or wrong.
6. `phone_task_benchmark` tasks get a label only when the first tool and key arguments are
   unambiguous. Read, impossible, ask-user, critical and multi-step tasks stay `labeled: false` and
   are excluded from scoring. `{run}` is replaced by `LAB01`.
7. Every `planner_false_positive.jsonl` case has gold no tool.

## Caveats

- **Desktop numbers are not device evidence.** Ollama runs these models on an Apple M3 Pro with the
  MLX runner (NVFP4 / MXFP8 weights). The app runs LiteRT-LM int4/int8 on a phone CPU or GPU.
  Accuracy is the portable signal. Latency is only useful to compare modes and models with each
  other.
- The bench models are Qwen 3.5 (2B, 4B) and Gemma 4 (E2B, E4B). The retired pre-PF1 app
  configuration shipped the 270M FunctionGemma as its action model; it is no longer offered or
  selected for planning and is not in Ollama. These results bound what a stronger local model could
  do with the prompts used by this bench. They do not measure FunctionGemma.
- Ollama applies each model's own chat template. LiteRT-LM applies the template baked into the
  `.litertlm` file. The prompt text, system instruction and parser are identical, but the template
  around them may differ.
- The lab caps output at `num_predict = 384`. A valid `call:` line is far shorter, so the cap only
  cuts outputs that would fail parsing anyway.
- Ollama reuses the KV cache for an identical prompt prefix (`promptCachedTokens`). Long shared
  prefixes (the tool list) make later calls cheaper than a cold phone call would be.
- Suite A screens are fixture dumps, not live apps. Each case scores one step with a fixed history;
  it does not measure multi-step drift.
- The built-in registry (`ToolRegistry()`) is used for Suites B and C. Production adds module tool
  providers (MCP, plan tools) when they are configured.
