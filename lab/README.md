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
| `lab/scripts/summarize.py` | Turns `lab/results/<date>/*.jsonl` into `summary.md` |
| `lab/results/<YYYYMMDD>/` | One JSONL per (suite, model, mode), `manifest.json`, `run.log`, `summary.md` |

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
`SOLIN_LAB_OLLAMA_URL` and `SOLIN_LAB_DATE` (overrides the results folder name).

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
| `M-full` | Model only, prompt advertises the whole built-in registry (what the 270M FunctionGemma sees today) |
| `M-topk` | Model only, prompt advertises the top 8 tools from `LabToolRetriever` (lexical, deterministic) |
| `R+M` | Production order with a model installed: skills, then rule intent gate, then model, then rule fallback |

Metrics: tool-name accuracy, argument accuracy (all gold keys, and per key), false-positive tool rate
on no-tool cases, parse/format validity (a known tool in the `call:` format) and schema validity
(`ToolRegistry.validate`), latency, prompt tokens. `M-topk` also reports retriever recall@8.

### Suite C: native tools (`native-tools`)

The Suite B cases with the tools passed through Ollama's native `tools` field (JSON schema from
`ToolSpec.inputSchemaJson`) instead of the `call:` text format:

| Mode | What runs |
| --- | --- |
| `N-full` | Whole registry, system prompt says to always call one tool (like the app's) |
| `N-topk` | Top 8 retrieved tools, same system prompt |
| `N-topk-free` | Top 8 retrieved tools, system prompt allows a plain-text answer when no phone action is needed |

This tells us whether moving the app to LiteRT-LM native tool calling would help.

### Suite D: routing (`routing`)

The 26 labeled cases of `docs/decision_routing_eval.json`. The local model acts as the router, using
`DECISION_MODEL_PLACEMENT_INSTRUCTIONS` and `_CRITERIA` and answering `{"choice", "confidence"}`.
Modes: `json-prompt` (format requested in the prompt only) and `json-schema` (Ollama structured
output). The rule baseline is `RequestComplexityAggregator` fed exactly as `chatPlacementInputs`
feeds it. Metrics: accuracy, coverage and accuracy at confidence >= 0.9 (the app's gate).

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
- The bench models are Qwen 3.5 (2B, 4B) and Gemma 4 (E2B, E4B). The app's shipped action model is
  the 270M FunctionGemma, which is not in Ollama. These results bound what a stronger local model
  could do with today's prompts. They do not measure FunctionGemma.
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
