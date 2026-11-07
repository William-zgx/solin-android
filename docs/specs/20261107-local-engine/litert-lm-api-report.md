# LiteRT-LM 0.12.0 API report

Status: desk survey of the published Android AAR. Nothing here was run on a phone.

## Method and scope

- Artifact: `com.google.ai.edge.litertlm:litertlm-android:0.12.0`, found in the Gradle cache
  (`~/.gradle/caches/modules-2/files-2.1/com.google.ai.edge.litertlm/litertlm-android/0.12.0/`).
  The `-api` and `-runtime` jars were inspected with `javap -p` (JDK 21), and the bytecode of
  `Engine.initialize`, `Engine.createConversation`, `Conversation.getBenchmarkInfo` and
  `Conversation.cancelProcess` was read with `javap -c -p`. The native `libLiteRtLm` strings were
  scanned with `strings`.
- Commands, reproducible from the cache path above:
  `javap -p -cp litertlm-android-0.12.0-api.jar:litertlm-android-0.12.0-runtime.jar com.google.ai.edge.litertlm.<Class>`
- What this is not: a device test. No phone or GPU was available on the devbox, and no model was run
  through these APIs. Every "available" below means "present in the public Kotlin surface and wired in
  the bytecode", not "behaves correctly on a device".

## Summary

| Capability | In 0.12.0 public API | Solin today | Recommendation |
| --- | --- | --- | --- |
| Native tool declarations | Yes (`ConversationConfig.tools`, `OpenApiTool`, `Message.toolCalls`) | Declared only, `automaticToolCalling = false`; the planner still parses `call:name{json}` text | Keep the text planner. Spike native calls on a device only if the spike beats the text format on the same suite |
| Constrained decoding | Native llguidance is in the library, behind `ExperimentalFlags.enableConversationConstrainedDecoding`. No per-request or per-conversation constraint parameter in the public API | Not used | Do not enable. Solin's own parser, `SafetyPolicy` and confirmation remain the gate |
| Image input | Yes (`Content.ImageBytes`, `Content.ImageFile`, `EngineConfig.visionBackend`, `maxNumImages`, `ExperimentalFlags.visualTokenBudget`) | Used for chat with `supportsVisionInput` profiles | Keep; no change from this survey |
| Audio input | Yes (`Content.AudioBytes`, `Content.AudioFile`, `EngineConfig.audioBackend`) | Not used | Out of scope for this slice. Needs a model that declares audio support |
| Session cloning or KV reuse | No clone or fork API. Reuse only by keeping one live `Conversation` | Chat keeps its live conversation under strict equality; one-shots always recreate | Keep this split. A one-shot is never reused |
| Benchmark info | Yes, but gated: `Conversation.getBenchmarkInfo()` needs `ExperimentalFlags.enableBenchmark` set before `Engine.initialize()` | Flag never set; `getBenchmarkInfo()` expected to fail on device | Leave it off in production. Turn it on only in a debug device bench, then re-measure generation |
| Backend selection | CPU, GPU and NPU classes exist. NPU needs a `nativeLibraryDir` | CPU and GPU through `BackendChoice`; NPU unused | No NPU path. Keep CPU for the planner fallback |

## 1. Native tool or function declarations

Available at the API level.

- Declare: `ConversationConfig(tools: List<ToolProvider>, automaticToolCalling: Boolean, ...)`.
  A `ToolProvider` is built with the top-level `com.google.ai.edge.litertlm.tool(OpenApiTool)`.
- `OpenApiTool` has two methods: `getToolDescriptionJsonString(): String` and
  `execute(paramsJsonString: String): String`.
- Calls come back on the response: `Message.toolCalls: List<ToolCall>`, where `ToolCall` has
  `name: String` and `arguments: Map<String, Any>`.
- `Engine.createConversation` builds a `ToolManager` from the tools and serializes their descriptions
  into the native conversation config.

Solin:

- `LiteRtConversationSpec.tools` maps each `LocalToolDeclaration` to a `DeclaredOnlyTool`, whose
  `execute` throws. `automaticToolCalling` is `false`, so the native layer never runs a tool.
  `LocalOneShotResult.Generated.toolCalls` carries the calls back as data.
- Whether a given model emits native calls is a model property. The API does not promise it.
- Decision: the production planner stays on the `call:` text format. The Mac suites in `lab/` showed
  that the bottleneck is deciding whether to act at all, not choosing the tool. Gemma 4 E4B native
  calls reached 97/100 tool-correct but 34/51 false positives on no-tool requests. Native calls do not
  fix the gate. A device spike may still compare the two formats on the same suite before any switch.
- Unverified: whether `Message.toolCalls` is populated when `automaticToolCalling = false` on a real
  model. The bytecode shows the field is read, and the JVM fakes cover the Solin side only.

## 2. Constrained decoding

Partly present, not usable from Solin as the public API stands.

- The native library contains a llguidance grammar path. Its strings include `json_schema`,
  `lark_grammar`, and the error "grammar must have either lark_grammar or json_schema".
- `Engine` reads `ExperimentalFlags.enableConversationConstrainedDecoding` at creation (bytecode
  confirmed). `ExperimentalFlags` is a process-wide object.
- No public parameter takes a grammar or JSON schema. `ConversationConfig`, `SamplerConfig` and
  `SessionConfig` have no constraint field. `ConversationConfig.extraContext` is a free-form
  `Map<String, Any>`, and its keys are not documented. It was not probed.

Solin:

- Do not set the flag. Its effect on the planner's output is unverified, and it would be a second,
  untested gate on top of `SafetyPolicy`. The gate that matters is the `call:` parser plus
  `SafetyPolicy` and confirmation, which run on every path.
- If constrained decoding is ever wanted, it needs a device spike and a documented way to pass the
  grammar. A flag alone is not enough.

## 3. Image input

Available.

- `Content.ImageBytes(bytes)`, `Content.ImageFile(path)`, and `InputData.Image` in the low-level
  `Session` API.
- `EngineConfig(visionBackend, maxNumImages)`. `ExperimentalFlags.visualTokenBudget` is an optional
  integer read by `Conversation`.
- Solin already uses `Content.ImageBytes` through `LocalModelRequest.toLiteRtContents()`, with
  `maxNumImages = 5`, and sets `visionBackend` only when the profile reports `supportsVisionInput`.

Solin: no change from this survey. Image input is still unverified on a device for each model
profile.

## 4. Audio input

Available in the API, unused by Solin.

- `Content.AudioBytes(bytes)`, `Content.AudioFile(path)`, `InputData.Audio`, and
  `EngineConfig.audioBackend`.
- Whether a model accepts audio depends on the model file. Solin's profiles do not declare audio.

Solin: out of scope for this slice. Adding it needs a model profile that declares audio support and a
device check.

## 5. Session cloning and KV reuse

No clone, fork, or snapshot API.

- `Engine` has `createConversation`, `createSession`, `initialize`, `close`, and `isInitialized`.
- `Conversation` has `sendMessage`, `sendMessageAsync` (blocking, callback and Flow forms),
  `cancelProcess`, `getBenchmarkInfo`, `renderMessageIntoString`, `close`, and `isAlive`. No clone.
- `Session` is a separate low-level API (`runPrefill`, `runDecode`, `generateContent`,
  `generateContentStream`, `cancelProcess`). It has no clone either.
- The only way to seed history is `ConversationConfig.initialMessages`, which re-prefills the whole
  history when a conversation is created.

Solin:

- Chat keeps its live `Conversation` only when the system instruction, `GenerationParameters` and the
  budgeted history all equal what the conversation already holds (`ChatConversation.canContinueAs`).
  A cancelled, failed or image-bearing send, a one-shot, a model load, a capability change, or
  `close` ends that reuse.
- One-shots never reuse. Every call creates a conversation with an empty history and closes it.
  This is the "recreate per call" fallback the spec asked for, because no clone exists to make a
  per-task reuse safe.

## 6. Benchmark info

Available, but gated, and probably dead in Solin today.

- `Conversation.getBenchmarkInfo(): BenchmarkInfo` calls `nativeConversationGetBenchmarkInfo`. The
  `BenchmarkInfo` fields are `initTimeInSecond`, `timeToFirstTokenInSecond`, `lastPrefillTokenCount`,
  `lastDecodeTokenCount`, `lastPrefillTokensPerSecond`, and `lastDecodeTokensPerSecond`.
- `Engine.initialize()` reads `ExperimentalFlags.enableBenchmark` (bytecode confirmed). The native
  library reports "Benchmark is not enabled. Please make sure the BenchmarkParams is set in the
  EngineSettings." when the flag was off at engine creation.
- Solin never sets the flag. `RealLiteRtRuntime.configureNativeBenchmark` exists, but nothing calls it.
  So `getBenchmarkInfo()` is expected to throw on every device build. Expected, not verified.
- Effects in Solin: `lastBenchmarkResult()` returns `Unavailable("getBenchmarkInfo failed: ...")`,
  `lastGenerationStats()` returns null, and `LocalCallMetrics.inputTokens` and `outputTokens` stay
  null. Load time, first-token time, total time and accelerator are measured by Solin and do not need
  the benchmark.
- A top-level `com.google.ai.edge.litertlm.benchmark(modelPath, backend, ...)` also exists. It was not
  exercised.

Solin: keep the flag off in production. For a debug device bench only, set it before the engine is
initialized, and record that benchmark mode may change generation. The existing code comment already
says this is unconfirmed.

## 7. Backend selection (GPU, NPU, CPU)

- `Backend.CPU(numOfThreads: Int?)`, `Backend.GPU()`, and `Backend.NPU(nativeLibraryDir: String)`.
- `EngineConfig(modelPath, backend, visionBackend, audioBackend, maxNumTokens, maxNumImages, cacheDir)`.
  Each backend has its own slot.
- `Engine.initialize()` checks the CPU thread count and the NPU library directory, and reads
  `ExperimentalFlags.enableSpeculativeDecoding` (a nullable Boolean).
- Solin: `BackendChoice` maps to CPU and GPU only. `LocalAccelerator.NPU` exists in the runtime
  contract, but nothing produces it. The dedicated planner engine always uses CPU, as the spec asks.

What Solin should do: keep CPU for the planner fallback and keep GPU as the chat preference. Add no
NPU path until there is a device with an NPU runtime library to test against.

Unverified: whether GPU initialization succeeds on the target phones, and whether the GPU path matches
the CPU output. The devbox has no GPU and no phone was attached.

## Could not verify

- Any behaviour on a phone: load times, first-token times, memory, and thermal effects.
- GPU and NPU availability, and whether GPU output matches CPU output.
- Whether a model emits native tool calls with `automaticToolCalling = false`.
- The effect of `enableConversationConstrainedDecoding` and of `extraContext` keys.
- Whether `Conversation.cancelProcess()` from another thread ends `sendMessageAsync` with a
  `CancellationException` on the native side. The JVM fakes assume it does.
- Whether benchmark mode leaves generation unchanged.

## What this means for the planner

- The planner keeps the `call:name{json}` text format for now, through `ActionPlanningRuntime.plan`.
- Native tool declarations are a possible later spike, not a migration. The Mac results do not
  justify switching the format.
- No constrained decoding, no NPU, no audio, and no benchmark flag in production.
