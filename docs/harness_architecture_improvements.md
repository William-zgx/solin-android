# Harness 架构改进建议（修订版 v2）

## 已完成改进 (Completed 2026-07)

以下改进已在 commit 4ad758e 中合并到 main：

- **结构化日志 (SolinLog)**：新增 `logging/SolinLog.kt` 接口 + `AndroidSolinLog`（runCatching 包裹）+ `NoOpSolinLog` + 12 个标准 tag。替代散落的 `android.util.Log` 调用。
- **集中常量 (SolinConstants)**：新增 `SolinConstants.kt`，含 Network/AgentLoop/Ui/Embedding 嵌套对象，替代散落的 private const val。
- **并发安全 (AgentLoopRuntime)**：8 个 `mutableMapOf` 改为 `ConcurrentHashMap`，消除多协程并发风险。
- **MemoryController 提取**：从 SolinViewModel 提取 385 行 memory 逻辑到独立 `memory/MemoryController.kt`。
- **证据加密**：OnDeviceEvidenceBlobStore 新增 AES/CBC/PKCS5Padding 加密（AndroidKeyStore key），IV 前置，自动迁移旧明文。
- **网络安全配置**：新增 `network_security_config.xml`，默认禁止明文流量，仅放行 loopback。
- **依赖反转**：ModelRepository 构造参数从 `PreferenceSettingsStore` 改为 `SettingsStore` 接口；接口新增 4 个 model-selection 方法。
- **SolinViewModel 去重**：提取 `persistMessagesAndRebuildMemory`（11 处）和 `clearedEvidence()`（5 处）；修复 `result.errorCode` → `result.error` bug。

仍待完成（见 docs/plans/）：
- AgentLoopRuntime 拆分为多组件 + 类型化事件总线
- Data layer 迁移为 suspend 函数
- ViewModel 拆分为 8 个 controller
- UiState 拆分为 10 个子状态
- SolinScreen 91 个 composable 拆分为 12 个组件

参考 pi（earendil-works/pi，TypeScript agent toolkit + coding agent CLI）与 opencode（anomalyco/opencode，基于 Effect.ts 的开源 coding agent）的 Harness 实现，对照 Solin Android 当前的 Harness（`orchestration/`、`tool/`、`safety/`、`runtime/`），给出按优先级排序的改进点。Solin 当前核心编排循环在 `AgentLoopRuntime.kt`（~3960 行），由 `AssistantOrchestrator` 包装；Tool 层有 `ToolRegistry`（~2500 行，硬编码 `BuiltInToolProvider`）；模型层有 `RemoteChatRuntime`（OkHttp streaming）+ `LiteRtRuntime`（本地）；审计层有 `ToolAuditRepository`/`RemoteSendAuditSink` 两个 Room 环形缓冲。整体结构扎实、安全审计比多数开源项目重；下文列出结构性缺口，优先级经 8 个子系统审计与 adversarial panel 校准。

---

## P0 — 结构性可维护性改进（应尽快做）

### 1. 拆分 AgentLoopRuntime 巨型类（3960 行 → 多职责组件）+ 同步引入类型化事件总线

> 部分完成：ConcurrentHashMap 修复已落地；完整拆分计划见 `docs/plans/viewmodel-split.md`

**参考**：opencode 将 runner 拆为 `session/runner/llm.ts`、`session/runner/model.ts`、`tool/registry.ts`、`session/compaction/`、`session/snapshot/`；pi 把 hooks、compaction、session、memory 独立。但 opencode 的 SQLite 事件溯源/versioned manifest/projector-in-tx **不适合单进程 Android**（已有 `AgentTraceStore` + Room 审计表承载持久化，Kotlin sealed class 提供编译期类型清单），应只采纳其 pub/sub + typed-hierarchy 思路。

**现状**：
- `AgentLoopRuntime.kt` 同时承担 run 生命周期、memory 加载、tool 规划（rule/model-first）、safety 校验、执行、观察、重试、skill 推进、外部 outcome 等待、并行 evidence batch、restore cursor，已成 god object；`AgentObservationReplanner.kt` 2053 行也偏大。
- 当前**没有任何 SharedFlow/Channel/EventBus**（grep `SharedFlow|MutableSharedFlow|Channel<|postValue|EventBus` 生产代码零命中；仅有 runtime 层冷流 `Flow<String>`/`Flow<RemoteChatEvent>` 和 ViewModel 上单个 `MutableStateFlow<ChatUiState>`）。所有跨组件通知都是 `AssistantOrchestrator`/`AgentLoopRuntime` 上的同步方法直调；UI 通过 `runEvents(runId)` 每次从 `AgentTraceStore` 重建 `AgentRunEvent` 列表（`AgentLoopRuntime.kt:221-224`）轮询重绘；`AgentRunEventProjector.summarize`、`AgentRunEventSummary`、`AgentRunEventCounts` 是死代码（`AgentRunEvents.kt:453-647` 定义，外部零引用）。
- 审计/追踪/UI 三处扇出是 AgentLoopRuntime 里对 `auditSink.record(...)`、`traceStore.appendStep(...)`、`_uiState.update { ... }` 的并列硬编码，加一个新订阅者（metrics logger、debug overlay、analytics）只能再加直调或轮询。
- `AgentRunEvent` 子类型无 `occurredAtMillis`、无 correlationId、无结构化错误码（`RunFailed.reasonLabel` 是自由文本，见 `AgentRunEvents.kt`）；而 `ToolAuditEvent.createdAtMillis`（`ToolAuditModels.kt:19`）有时间戳，两套约定不一致。
- TTFT、TPS、资源采样（`SystemResourceMonitor.sample()` 1.5s 拉取式，无时间戳无 run 关联）、模型加载等信号各自散落在 `LiteRtRuntime.lastFirstTokenMs`/`ModelHealth` 等 last-value 字段上，无法关联到具体 run/toolCallId。

**拆分建议**（Kotlin 协程风格，不照搬 Effect.ts）：

| 组件 | 职责 |
|---|---|
| `AgentTurnRunner` | 单次"准备上下文 → 调模型 → 流回事件"，参考 opencode `runTurnAttempt` |
| `ToolPlanner` | 初始规划 + observation 后 continuation 规划（把 Replanner 拆细）|
| `ToolExecutor` | 顺序/并行执行、重试、safety 调用；产出 `Flow<ToolExecutionEvent>` |
| `ToolBatchCoordinator` | public-evidence 并行 batch + 普通并行 tool |
| `ContextAssembler` | system prompt 拼装、memory 注入、device snapshot、history 裁剪；预留 `transformContext` hook |
| `AgentRunRepository` | 状态机持久化、`AgentContinuationCursor` restore，对齐 opencode SessionStore |
| `SolinEventBus` | 类型化 pub/sub，取代直调 triple-sites；见下方接口 |

**事件总线最小形状**（与拆分同步做，避免二次迁移；参考 adversarial panel kotlinShape）：

```kotlin
sealed interface SolinEvent {
    val eventId: String
    val occurredAtMillis: Long
    val runId: String?
    val correlationId: String? get() = runId

    sealed interface Agent : SolinEvent {
        data class RunStarted(...) : Agent
        data class TurnStarted(...) : Agent
        data class ToolPlanned(...) : Agent
        data class ToolStarted(...) : Agent
        data class ToolProgress(val toolCallId: String, val partial: String,
                               val progress: Float) : Agent   // 覆盖流式进度
        data class ToolSucceeded(...) : Agent
        data class ToolFailed(val code: ToolErrorCode, ...) : Agent
        data class TextDelta(val text: String) : Agent         // 高频：replay=0, DROP_OLDEST
        data class TurnEnded(val tokensIn: Int, val tokensOut: Int,
                            val ttftMs: Long, val durationMs: Long,
                            val reasoningTokens: Int, val cacheRead: Int) : Agent
        data class RunEnded(...) : Agent
        data class RunFailed(val code: AgentErrorCode, val msg: String) : Agent
        data class RunCancelled(...) : Agent
    }
    sealed interface Runtime : SolinEvent {
        data class ModelLoaded(val model: String, val loadMs: Long) : Runtime
        data class FirstToken(val ttftMs: Long) : Runtime
        data class ResourceSample(val pressure: ResourcePressure, val pssKb: Long,
                                  val cpuPercent: Int) : Runtime
    }
    sealed interface Audit : SolinEvent {
        data class ToolAudited(...); data class RemoteSendAudited(...)
    }
    sealed interface Safety : SolinEvent {
        data class ConfirmationRequested(...); data class PolicyTriggered(...)
    }
}

interface SolinEventBus {
    fun publish(event: SolinEvent)                               // tryEmit，永不挂起发布方
    fun <E : SolinEvent> subscribe(type: KClass<E>): Flow<E>     // filterIsInstance 视口
    fun recent(type: KClass<out SolinEvent>, limit: Int = 50): List<SolinEvent>
}
// 默认实现：MutableSharedFlow(replay=200, extraBufferCapacity=512, onBufferOverflow=DROP_OLDEST)
// 在 AppContainer 单例化；AgentTraceStore/ToolAuditRepository/ResourceMonitor/ViewModel 改为订阅者，
// 取代当前 AgentLoopRuntime 里的 auditSink.record + traceStore.appendStep + _uiState.update 三并列直调点。
```

关键约束：
- 高频 per-token `TextDelta` 继续走现有 runtime 冷 `Flow<String>`，**不**走总线；总线只承载 lifecycle/metric/progress 事件（频率远低于 token 速率）。
- `AgentTraceStore` 仍是 run resume 真值源；"durable"=订阅者写 Room，**不是**总线内事务性 projector。
- `ChatUiState`（`MutableStateFlow`）保留为 UI 渲染态，ViewModel 成为订阅者将相关事件 fold 进 `_uiState.update { ... }`。
- `ToolAuditSink`/`RemoteSendAuditSink` 改为总线订阅者，不再在 runtime 里 fire-and-forget 直调。
- 所有事件携带 `occurredAtMillis`；错误用结构化 `AgentErrorCode`/`ToolErrorCode`（初版 5-10 个知名码：Network/ContextOverflow/SafetyBlocked/ToolNotFound/PermissionDenied/ModelTimeout/Cancelled/Validation，加 `Unknown(cause)` 兜底）。
- 发布用 `tryEmit` + `DROP_OLDEST`；订阅者必须在自己的协程作用域（viewModelScope/applicationScope）collect，禁止在 publish 调用栈内同步做 Room 写入等重活。
- 删除当前死代码 `AgentRunEventProjector.summarize`/`AgentRunEventSummary`/`AgentRunEventCounts`。

预期收益：新增订阅者（metrics、debug overlay、analytics）无需动 4000 行主类；UI timeline 可显示 wall-time 与每步耗时；并行/中断/cancel 边界清晰；单测可独立写。

### 2. 引入 Hook / 拦截器链（beforeToolCall / afterToolCall / transformContext / prepareNextTurn / shouldStopAfterTurn）

**参考**：pi `AgentLoopConfig` hooks 是一等公民，明确 contract（"must not throw; return safe fallback"），签名带 `AbortSignal`。

**现状**：solin 无 hook/interceptor/plugin 点。所有横切逻辑（safety、audit、trace、observation 决策、memory 写入）硬编码在 `AgentLoopRuntime`，加"工具执行前 dry-run 预览"、"工具结果自动摘要"、"turn 结束 memory 归纳"只能改主类。

**最小 Hook 接口**（Kotlin 挂起函数，返回 sealed result）：
```kotlin
interface AgentHooks {
    suspend fun beforeToolCall(ctx: BeforeToolCallContext): BeforeToolCallResult = BeforeToolCallResult.Proceed
    suspend fun afterToolCall(ctx: AfterToolCallContext): AfterToolCallResult = AfterToolCallResult.Keep
    suspend fun transformContext(messages: List<AgentMessage>): List<AgentMessage> = messages
    suspend fun prepareNextTurn(ctx: TurnContext): TurnUpdate? = null
    suspend fun shouldStopAfterTurn(ctx: TurnContext): Boolean = false
    suspend fun getSteeringMessages(): List<AgentMessage> = emptyList()
}
```
关键 contract：
- 所有 hook **禁止抛异常**，失败必须降级 no-op；
- `beforeToolCall` 可 `Block(reason)`，Harness 把 reason 作为 error tool-result 写回模型（不是抛）；
- `afterToolCall` 可替换 content / 标记 isError / 设置 terminate 早停提示；
- `transformContext` 是 context window 管理、注入外部上下文的合法扩展点。

### 3. 真正的 Context Compaction（不是简单截断）

**参考**：pi `packages/agent/src/harness/compaction/` + branch summarization；opencode `compactIfNeeded` 在每次 LLM 请求前做，overflow 时自动 recover（`TurnTransitionError → ContinueAfterOverflowCompaction` 重试同一步）。

**现状**：
- 本地模型 `LiteRtRuntime.budgetLocalRuntimeHistory`（`LiteRtRuntime.kt:421`）是"当前 prompt 保留 + 旧 history 按 token 预算滑窗丢弃"，无摘要无 overflow 恢复；
- 远程模型无自动 compaction；token 估算分散，溢出靠 provider 报错兜底。

**建议**：
1. 在 `ContextAssembler` 内引入 `ContextCompactor`，每次 turn 前做 token 预算检查；
2. 触发：本地 > 70% 窗口、远程 > provider 上报的 `context_limit - output_reserve`；
3. 压缩路径：优先丢可重建 evidence/tool-result（截图、搜索原文）→ 小模型或同模型"只输出摘要"调用对 N 条旧 turn 做 rolling summary → summary 作为 system/user 级消息放回；
4. Overflow recovery：捕获 provider context-length 错误 → 自动 compaction → 同一步重试一次（对齐 opencode `recoverOverflow`）；**必须**有重试次数上限，避免 compact 仍不足时死循环；
5. 保留 `AgentContinuationCursor`，compact 结果写入 trace，保证 crash-restore 不丢。

### 4. 流式工具执行进度（onUpdate / streaming tool output）

**参考**：pi `AgentTool.execute(..., onUpdate?: AgentToolUpdateCallback)` 在执行中多次推 partial result，event stream 有 `tool_execution_update`；opencode `publish-llm-event.ts` 用状态机强制 fragment start/append/end 文法（防止事件流"不合文法"）。

**现状**：Solin 工具是"请求 → 最终 result"两段式。长耗时 tool（web 搜索多引擎、跨 app 抓取、长 OCR、后台任务观察）UI 只能等结束；本地 LiteRT-LM 已经 streaming 输出，但 tool 侧无对称能力。

**建议**：
- `ToolExecutor` 输出 `Flow<ToolExecutionEvent>`（Start/Progress/Update/Complete/Failed）；
- Tool 接口加可选 `executeStreaming(...)`；现有同步 `execute()` 自动适配为单事件；
- 事件总线发布 `SolinEvent.Agent.ToolProgress(toolCallId, partialText, progress)`（P0-1 已定义），UI timeline（commit 2e0b9d8 的 interaction timeline）实时渲染；
- 抽取类似 opencode `publish-llm-event.ts` 的 **ToolProgressPublisher 状态机**，强制"start 先于 append、tool input end 先于 Called、Called 先于 Success/Failed、failAssistant 冲刷在途片段并为未 settle 工具发失败事件"文法，避免事件流不一致；
- Streaming 结果仍走 final `validateResult`，保持 audit/safety 不变。

---

## P1 — 能力补强（显著提升体验）

### 5. 可中断 / 可 Steering 的运行时（Abort + mid-run injection）

**参考**：pi `AbortSignal` 贯穿 tool 和 hooks；`getSteeringMessages` 在 turn 间注入最新指令；opencode `SessionInput.hasPending(db, sessionID, "steer"|"queue")` + fiber interrupt，steer 立即打断、queue 排队到 drain point。

**现状**：`AgentRunState.Cancelled` 存在但缺少 mid-turn 协作式取消传播；`AwaitingExternalOutcome` 支持外部结果回来继续，但没有"用户在 agent 干活时发新消息 → 立即插队"的 steer 通道。

**建议**：
- 每个 tool 执行、每个 model streaming call 都传 `Job`/`CancellationSignal`；tool 实现必须协作响应（OkHttp call、子协程、content observer 都注册 cancel）；
- 引入两级输入队列：`steer`（高优先级，取消当前 turn 立即注入）和 `queue`（排队到 drain point）；
- hooks 里 `getSteeringMessages()` 读 steer 队列。

### 6. 泛化并行 Tool 执行（不止 public evidence batch）

**参考**：pi `ToolExecutionMode = "sequential" | "parallel"`，可全局配置也可单 tool override（`AgentTool.executionMode`）；语义"prepare 顺序（参数校验/确认）→ allow 的工具并发 → tool_execution_end 按完成顺序发 → tool-result 消息按 assistant source 顺序回放"。opencode `FiberSet.run(toolFibers)` 并发、`awaitToolFibers` 等全部 settle。

**现状**：Solin 仅 `PlanToolBatch` 允许 `isPublicEvidenceBatchEligible()` 的工具并行（web search、public fetch）。Device action/skill/non-evidence 工具一律串行；并行粒度是"整批等齐才进 observe"，无按完成顺序回流。

**建议**：
- Tool spec 加 `executionMode: Sequential | Concurrent`（默认 Sequential，高风险设备动作强制 Sequential，safety policy 可覆盖）；
- `ToolExecutor` 用 `supervisorScope` + channel 做并发，允许高延迟独立工具（多源搜索、天气+日历、读多个文件）并发；
- 完成即发 `ToolEnd` 到总线，全部结束后才触发 observeModel（或支持 streaming observe，按 source-order 回放）；
- **护栏**：高风险设备动作（发消息、付款、改设置）始终串行且走 confirmation，绝不进并发池——保留 `isPublicEvidenceBatchEligible` 思路。

### 7. 多 Agent Profile / 模式（build / plan / ask / 本地-only 等）

**参考**：opencode `AgentV2` 支持 build/plan/general/subagent 多个 profile，每个有独立 system prompt、tools 白名单、step limits、权限集；pi `Skill` 可 `disableModelInvocation` 做只读 guidance。

**现状**：Solin 仅有 `InitialPlanningMode`（RuleFirst/ModelFirstRemoteTools）和 `RemoteToolScope`（PublicEvidenceOnly/ModelPlanning），只是 planning 模式，不是"整个 agent 人格/权限/系统提示/工具集切换"。缺"规划模式"（只出方案不执行设备动作）、"严格本地模式"（只走 LiteRT-LM 不出网）、"儿童模式"（严白名单）。

**建议**：
```kotlin
data class AgentProfile(
    val id: String,                           // build/plan/local-only/kid/...
    val systemPrompt: SystemPromptBuilder,
    val toolFilter: (ToolSpec) -> Boolean,
    val remoteToolScope: RemoteToolScope,
    val maxToolSteps: Int,
    val requireConfirmationFor: (ToolSpec) -> Boolean,
    val modelPreference: ModelPreference,     // local-only/remote-only/auto
)
```
`AgentProfileRegistry` + 每 run 绑定一个 profile；这是子 agent 委派的基础。

### 8. SystemPromptBuilder（含最小 ContextContributor 接口）

**参考**：pi `systemPrompt = string | ((ctx) => string)`；opencode `SystemContextRegistry` 每次 run 动态装配。

**现状**：系统指令是 `ChatPrompts.kt:3-18` 中 18 行常量 `DEFAULT_CHAT_SYSTEM_INSTRUCTION`；per-run 证据拼装由 `AgentLoopRuntime.kt:2200-2237` 私有方法 `promptWithContextIfUseful` 完成，硬编码两个 contributor（memory=Memory、device context=DeviceContext），通过 `budgetEvidenceCards`（`:2275`，带 priority 比较、截断、`truncated=true` 标记、`MIN_TRUNCATED_EVIDENCE_TOKENS` 地板）做 token 预算。目前只有两个 contributor 时引入完整 registry 属于 YAGNI，但接口先抽出来避免第三个 contributor 到来时再次改私有方法。

**建议**：
- 先抽 6 行接口（**不**做 DI/ServiceLoader 自动发现）：
  ```kotlin
  interface SystemContextContributor {
      val sourceType: EvidenceSourceType
      suspend fun contribute(input: String, run: AgentRun): EvidenceCard?
  }
  ```
- 独立 `SystemPromptBuilder`（或 `ContextAssembler` 内的 section 装配逻辑），按 sections 组合：baseline 常量 / profile-specific / tool-docs / skills / memory-hint / device-context / 未来其他 contributor；每 section 可被 hook 拦截替换；对本地小模型自动压缩 prompt（8k 窗口下 system prompt 预算紧张）。
- 复用现有 `EvidenceCard`/`EvidenceSourceType`/`budgetEvidenceCards`/`evidencePriorityComparator`/`estimateLocalRuntimeTokens`/`takeFirstEstimatedTokens`；`DEFAULT_CHAT_SYSTEM_INSTRUCTION` 保持为常量，**不**折进 registry（避免破坏现有 `ChatPromptsTest` 快照测试）。
- 待第三个 contributor 出现后再升级为完整注册/排序机制；区分"系统指令（常量、身份/行为规则）"与"answer-context 证据（per-turn、带预算）"，两者生命周期/token 语义不同，不共用一个 keyspace。

### 9. Ask-User 澄清工具 + 权限网关拆分

**参考**：opencode `question` 工具 + Deferred/Event 服务；pi hooks 允许模型在信息不足时提问而非硬猜。

**现状**：
- 确认/拒绝流程存在（`SafetyPolicy.evaluate()` 返回 Allow/RequireConfirmation/Reject，`AgentLoopRuntime.confirmToolRequest`/`cancelToolRequest`、`AgentRunState.AwaitingUserConfirmation`、`PendingAgentConfirmation`，`SafetyPolicy.kt:168-207`），被拒/取消的工具以 `ToolStatus.Rejected/Cancelled` 中文摘要作为 tool_result 回灌模型（`AgentLoopRuntime.kt:414`）；
- `SafetyPolicy`（330 行）混合 PII/敏感内容检测（`maskSensitiveContent`，`:53-166`）和工具授权策略（`:168-207`，约 40 行纯函数）；
- **没有 ask_user/question 工具**（grep `askUser|clarif|promptUser` 零命中；`MobileActionFunctions` 37 个函数里无此能力），多步计划遇歧义只能失败或幻觉默认值。

**建议**（两小项）：

1) Ask-user 作为 typed tool-result 通道（真实缺口）：
- 加 `ASK_USER = "ask_user"` 到 `MobileActionFunctions`，`ToolSpec(riskLevel=LowReadOnly, confirmationPolicy=NotRequired)`，executor 在一个以 request id 为 key 的 Deferred 槽位上挂起 run。
- 新建 `QuestionSubsystem` 单文件服务持有 `PendingUserQuestion(id, runId, questions, deferred: CompletableDeferred<UserAnswers>)`；通过 `AssistantOrchestrator.answerUserQuestion(runId, questionId, answers)`/`rejectUserQuestion(runId, questionId)` 表面化；resolution 把"用户回答：..."作为 tool_result 回灌循环（复用现有 `ToolStatus.Succeeded` 路径 + 文本 data 字段）。
- 事件总线加 `Agent.UserQuestionAsked`/`UserQuestionAnswered`（与 UserConfirmed/UserRejected 并列）。
- 设 per-run ask 预算（与 `TOOL_STEP_BUDGET` 平行），预算耗尽返回 rejected tool_result，防无限问循环；`ask_user` 本身必须走白名单绕过 SafetyPolicy RequireConfirmation（否则模型无跳出确认循环的通道）。

2) 权限网关（与 ask_user 同期做以共享 Deferred 管理，非独立重构）：
- 抽 `PermissionGateway`，提供 `suspend fun assert(spec, request, context): PermissionDecision { Allow | Denied(reason) | AwaitingConfirmation(pendingId) }`；
- `AgentLoopRuntime` 调用 gateway；`AwaitingConfirmation` 时停在现有 `AwaitingUserConfirmation` 状态；`confirmToolRequest`/`cancelToolRequest` 委托给 gateway；
- 将 `SafetyPolicy` 拆为 `ContentSafetyPolicy`（PII 检测/脱敏，已纯函数）和 `ToolAuthorizationPolicy`（riskLevel/权限门控）。v1 不做 saved allow-rules（grep `allowRule|PermissionSaved|always allow` 零命中）。

### 10. Telemetry 指标汇聚（与事件总线正交）

**参考**：opencode 明确把 observability（structured logging + OTLP）与事件总线分离——token/成本/延迟嵌入在 `SessionEvent.Step.Ended` 等 domain event 里，OTLP 是可选订阅者。

**现状**：
- 无 `TelemetrySink/MetricsSink`；TTFT/loadMs/tps 以 last-value 字段挂在 `LiteRtRuntime`/`ModelHealth` 上（`LiteRtRuntime.kt:268-272`），不关联 runId、不聚合、不持久；
- `ToolAuditEvent` 记录时间戳但不计算 tool 延迟（需事后 join ToolPlanned↔ToolObserved by requestId）；
- `SystemResourceMonitor.sample()` 由 composable 拉取（`ResourcePressureOverlay.kt:27-28`，1.5s 间隔），无时间戳无 run 关联；
- `perf/PerfSample.kt` 是死代码（生产零引用）；`rcperf/RcPerf.kt` 跑合成 benchmark 而非真实 turn，抓不到生产回归。

**建议**：独立于事件总线的 TelemetrySink（未来可订阅 N1 总线自动派生，但接口不变）：

```kotlin
interface TelemetrySink {
    fun record(sample: MetricSample)                          // 非阻塞（单线程 executor 入队）
    fun recentSamples(key: MetricKey? = null, limit: Int = 200): List<MetricSample>
    fun snapshot(): TelemetrySnapshot                         // p50/p95、率、比
}
sealed class MetricSample {
    abstract val occurredAtMillis: Long; abstract val runId: String?
    data class GenerationComplete(val ttftMs: Long, val totalMs: Long, val inputTokens: Int,
        val outputTokens: Int, val thinkingTokens: Int, val tps: Double,
        val backend: BackendChoice, val modelId: String,
        override val runId: String, ...) : MetricSample()
    data class ToolCompleted(val toolName: String, val latencyMs: Long, val succeeded: Boolean,
        val retryCount: Int, val requestId: String?, override val runId: String?, ...) : MetricSample()
    data class StepLatency(val stepType: AgentStepKind, val latencyMs: Long,
        override val runId: String, ...) : MetricSample()
    data class CompactionTriggered(val reason: CompactionReason, val contextBefore: Int,
        val contextAfter: Int, override val runId: String, ...) : MetricSample()
    data class ResourceSnapshot(val pressurePercent: Int, val pssKb: Long,
        val nativeHeapKb: Long, val thermalStatus: Int,
        override val runId: String?, ...) : MetricSample()
    data class CounterInc(val name: TelemetryCounter, val delta: Long = 1,
        override val runId: String?, ...) : MetricSample()
}
```
- 默认实现：`InMemoryTelemetrySink(maxPerKey=200)` 环形缓冲 + reservoir sampling；`NoOpTelemetrySink`。可选 Room 实现（1/10 采样，复用 `ToolAuditSummaryRedactor` PII 脱敏）。
- Exporter（独立采样，慢 exporter 不阻塞生成）：`LogcatTelemetryExporter`（DEBUG-only）、`OverlayTelemetryExporter`（在现有 `ResourcePressureOverlay` 显示 p50/p95）、`EvalDatasetExporter`（JSONL，10% 采样，写时 PII 脱敏）。**不**在 v1 引入 OTLP/OpenTelemetry（on-device 默认无 collector，依赖过重）。
- Wiring：AppContainer 单例，注入 AgentLoopRuntime（mirror auditSink 模式）；record() 走单线程 executor 不阻塞推理热路径。
- 清理：删除/改铸 `perf/PerfSample.kt` 到 `MetricSample`，避免两套指标 schema。

---

## P2 — 生态/扩展

### 11. 内部模块 SPI + 工具 Scope 生命周期（先于 MCP）

**参考**：opencode `ToolRegistry.register(tools)` 返回 `Effect<void, RegistrationError, Scope>`，Scope 关闭自动注销；栈式注册可识别 stale tool call。**但 opencode 的 JS 插件 SDK（给插件 agent/catalog/command/skill/integration 可变访问）不适合 Android 隐私-first 场景**——in-process 第三方代码会继承 Solin UID 与所有 accessibility/contact/file/notification 权限，崩溃 Android 权限边界。

**现状**：`ToolRegistry` 由 `BuiltInToolProvider` 启动时一次性填充 map（`ToolRegistry.kt:24,519-522`；生产 `SolinAppContainer.kt:187` 调用 `ToolRegistry()` 无 provider）；`ToolProvider` fun-interface 存在（`ToolModels.kt:30`）但仅用于 test/vararg 构造；Skills（~30 个）在 `BuiltInSkills.kt` 内联为单体；`ModelCatalog.kt` 静态对象；`OkHttpWebSearchProvider` 直接 new。无动态注册/卸载，无 stale call 检测，也无 ServiceLoader/SPI/@AutoService。

**建议（两层模型，拒绝通用 JS/Lua/dex 插件 SDK）**：

(1) **内部编译期模块 SPI**（第一方/bundled 扩展）：
```kotlin
interface SolinModule {
    fun register(registry: SolinModuleRegistry)
}
interface SolinModuleRegistry {
    fun addToolProvider(provider: ToolProvider)
    fun addToolHandler(handler: ToolHandler)     // suspend (ToolRequest) -> ToolResult
    fun addSkillSource(source: SkillSource)
    fun addModelBackend(factory: ModelBackend.Factory)
    fun addWebSearchProvider(factory: WebSearchProvider.Factory)
}
```
- Wire 用 Hilt `@IntoSet<SolinModule>` multibinding（优先）或 `SolinAppContainer.kt` 静态 `listOf(...)`；**不**用 ServiceLoader/@AutoService（Android 上 R8/动态特征分发包不可靠）。
- 替换 `BuiltInToolProvider`/`BuiltInSkillRuntime` 单体；模块必须保留现有 privacy marker（ToolCapability、ToolPermission、privateOutputKeys、confirmationPolicy、RiskLevel），由 `ToolRegistry.validateRuntimePermissionDescriptorContract` 和 LocalOnly 脱敏统一校验（`ToolRegistry.kt:205-291`）。
- Scope 生命周期：`register(tools, source: ToolSource): DisposableHandle`；按 source 维护栈；工具在模型调用后被卸载（stale）时返回结构化错误而非 NPE/IllegalState。

(2) **跨应用第三方扩展走 MCP 客户端**（见 #15），**不**走 in-process 插件。MCP 是该 SPI 的一个消费者，不是理由去建 opencode 风格的全量 PluginContext。

### 12. Evidence Blob 溢出存储（head/tail + ev:// 引用）

**参考**：opencode `ToolOutputStore.bound(...)` 把大输出截断/落盘并返回路径引用。

**现状**：
- `EvidenceCard`（`evidence/EvidenceModels.kt`）已有 sourceType、privacy、quality、freshness、truncated、tokenEstimate；`ToolResultContinuationPolicy.PublicEvidence`/`.LocalEvidence` 分两条管线（`AgentLoopRuntime.kt:2627-2709`），`PublicWebEvidencePack` 做结构化 citation；
- 每工具硬上限存在：截图缩到 1600px（`CurrentScreenshotOcrProvider.kt:21`）、共享图 8MB（`ShareIntentReader.kt:294-295`）、共享文本 4000 字符（`ToolModels.kt:8`）、web 结果 8 条（`PublicWebEvidenceModels.kt:10,73`）、answer-context 走 `budgetEvidenceCards`、本地 prompt 2000 字符（`AgentLoopRuntime.kt:3886`）、multimodal reader 输出 SharedTextPreview（truncated 标记，`MultimodalInputModels.kt:277-283`）；
- **真实缺口**：(a) 所有截断都是 `take(N) + "..."` **前缀丢弃**，长 PDF/长剪贴板/长网页的尾部永久丢失（质量 bug）；(b) 无 content-addressed 身份，cold restore 后 ToolObserved JSON 仅存 allowlisted `completionMetadata`（`AgentTraceStore.kt:1447-1456`），长输出不可恢复；(c) 二进制 blob 被 recycle，citation 只能锚定文本不能锚定原图/字节范围；(d) `AnswerCitationCheck` 锚点是瞬态文本偏移而非稳定引用。

**建议**（窄溢出层，非第一类 session 引用对象）：
```kotlin
data class EvidenceBlobRef(val uri: String, val sha256: String, val sizeBytes: Long,
    val mimeType: String?, val privacy: MessagePrivacy, val ttlUntilMillis: Long?)
// uri 方案 ev://<sha256>，文件落 context.noBackupFilesDir/evidence/<sha256>.bin (LocalOnly)
// 或 context.cacheDir/evidence/<sha256>.bin (RemoteEligible)，不对用户/模型可见

interface EvidenceBlobStore {
    fun putText(text: String, sourceType: EvidenceSourceType, privacy: MessagePrivacy,
                ttlMs: Long = DEFAULT_TTL_MS): EvidenceBlobRef
    fun putBytes(bytes: ByteArray, mimeType: String?, sourceType: EvidenceSourceType,
                 privacy: MessagePrivacy, ttlMs: Long = DEFAULT_TTL_MS): EvidenceBlobRef
    fun readText(ref: EvidenceBlobRef, offset: Int = 0, limit: Int = MAX_INLINE_CHARS): TextWindow
    fun headTailText(ref: EvidenceBlobRef, headChars: Int, tailChars: Int): HeadTail
    fun gc()     // mtime 7 天 TTL + 目录总大小上限（如 64MB）+ 未被非终态 run/近 30 天 message 引用的 blob 回收
}
```
- `EvidenceCard` 加 `val blobRef: EvidenceBlobRef? = null`（溢出指针），保留 inline `text`；截断从 `take(N)` 改为 `headTail`（ceil(limit/2) 头 + floor(limit/2) 尾 + 标记行 "… 中间 N 字已存入 ev://…，可通过后续工具读取 …"），`truncated=true` + 非空 `blobRef` 表示全文可取。
- 统一替换三处 ad-hoc trimmer（`boundedPromptValue` in `AgentLoopRuntime.kt:3886`、`BoundedSharedText` in `MultimodalInputModels.kt:245-250`、`takeFirstEstimatedTokens` in `AgentLoopRuntime.kt:3947`）为 `EvidenceBounds.headTail(text, tokenBudget, store)`。
- Tool plumbing：**不**改 `ToolResult.data: Map<String,String>`（churn 太大），加 out-of-band `ToolResult.overflow: List<EvidenceBlobRef>`，由 `ToolRegistry.validateResult`/executors 为超限输出挂 ref；`ToolObserved` trace JSON 扩展 `evidenceRefs: [sha256,…]` 让 cold restore 能重新解析。
- **不**暴露 ev:// URI 作为模型可调用工具（避免开放读环 + 新权限面）；仅内部使用：(a) prompt 预算溢出、(b) 答复 UI citation 链接（tap-to-source 打开原片段/文件）、(c) 长技能/web 批次跨进程死亡可恢复。
- 不注入 `<available_references>` 类 system prompt 块；head/tail 标记必须明确告知模型中间内容不可见、不可幻觉；红action必须在 putText/putBytes **前**完成；LocalOnly blob 必须进 noBackupFilesDir。

### 13. Thinking / Reasoning Budget

**参考**：pi `ThinkingLevel = off | minimal | low | medium | high | xhigh`；opencode 通过 model variant + provider extended thinking 参数支持。

**现状**：只有 temperature/topP，无 reasoning effort；接 Claude/Gemini/O3 等带思考 token 的模型无对应开关，也无法"规划阶段 high thinking、最终答复 low"调度。

**建议**：在 `GenerationParameters` 加 `reasoningEffort: Minimal | Low | Medium | High | Off`；`RemoteChatRuntime` 映射到各 provider thinking/reasoning_effort 参数；本地模型忽略。

### 14. 凭证 / OAuth 独立层

**参考**：opencode `Credential` + `Integration.Service.connection.active/resolve` 支持 apiKey 与 OAuth，`resolve` 每次请求动态拿（OAuth token 会过期），通过 `getApiKey` hook 每次 LLM 调用前刷新。

**现状**：远程 endpoint 通过 Settings 拿固定 key，无 OAuth 刷新；未来接 OpenAI/Gemini/Anthropic SDK 或 MCP 服务会被阻塞。

**建议**：抽 `CredentialResolver`，支持 `ApiKey`/`OAuth(accessToken, refreshToken, expiry)`；每次 remote call 前取 fresh credential；LiteRt-LM 本地路径无凭证。

### 15. MCP 客户端支持（Binder/进程隔离，基于内部 SPI）

**参考**：opencode 文档列 MCP 集成；pi 在 roadmap。MCP 是 tool/resource/prompt 三种能力的标准 wire protocol。

**现状**：grep 确认代码中无 MCP。**MCP 必须在 #11 内部 SPI 之后做**（否则要么硬编码进 ToolRegistry 产生 throwaway 代码，要么在 MCP client 里鸭式拼插件系统，位置错误）。on Android MCP-over-stdio 在 app sandbox 不可行，transport/sandbox/permission 决策必须与插件模型一起设计。

**建议**：作为可选模块（`mcp/`）；Android 上默认**仅**连用户显式添加的本地/局域网 server，transport 走：
- 跨 app 第三方：绑定 AIDL/Binder Service（自定义签名权限 `com.bytedance.zgx.solin.permission.MCP_SERVER`，protectionLevel=signature|knownSigner 用于 OEM/partner，普通已装应用走用户确认 consent），或 localhost HTTP；
- 本地 sidecar/termux：谨慎，默认关闭；
- **不**走 npx（手机端无 Node 生态，且隐私风险高）。
- MCP client 把 `ListToolsRequest`/`CallToolRequest` 翻译为 `ToolSpec`+`ToolHandler` 对，通过 #11 的内部 SPI 注册到 `ToolRegistry`（Scope 生命周期自动注销）；MCP 工具在暴露给 LLM 前必须走 per-server 用户 consent、per-tool capability 声明、MCP 注解到 Solin `RiskLevel`/`ConfirmationPolicy` 的显式映射，防 intent-spoofing / confused-deputy。

---

## P3 — 锦上添花

### 16. Session Plan / Todo（结构化计划 + step-budget 摘要）

**参考**：opencode `SessionTodo` + `todowrite` + `MAX_STEPS_PROMPT`。**但 opencode 的 snapshot/revert 不移植**（Solin 不是文件编辑 agent；破坏性动作已通过 `PendingAgentConfirmation`/`RemoteSendDisclosure`/risk-level 门控）。

**现状**：
- `SkillRunCheckpoint.kt` 是加密、无标题/状态/备注的恢复 token，覆盖单 skill 内连续性；
- `AgentLoopRuntime` 强制 `maxRunToolSteps=10`（`:98`）但仅以字符串失败原因 `TOOL_STEP_BUDGET_EXCEEDED_REASON`（`:73`）表面化，无"剩余工作"结构化工件；
- 零 todo/plan/task-list 模型、Room 表、工具面、UI 卡片。

**建议**：
- Room 实体 `SessionPlanItemEntity(planItemId, runId, position, title, status: PlanItemStatus[PENDING|IN_PROGRESS|DONE|BLOCKED|SKIPPED], note, updatedAt)`；DAO `replaceAllForRun(runId, items)` 事务整表 upsert + `listForRun`/`markDone`；`SessionPlanStore` 走 DI 注入（mirror `AgentTraceStore`/`MemoryRecordStore` 风格）。
- 两个工具注册到现有硬编码 `builtInToolSpecs`（不是新插件机制）：`plan_write(input: {runId, items:[{title,status,note}]})` 整表替换（紧凑 JSON，非 Markdown checklist，适合语音模型）、`plan_read(input: {runId?})` 读；gated 同其他 mutating tools。
- 在 `TOOL_STEP_BUDGET_EXCEEDED_REASON` 旁注入 step-budget 摘要 prompt，引用剩余 PENDING 项（对齐 opencode MAX_STEPS_PROMPT）。
- UI：计划更新发到事件总线，active-interaction timeline（commit 2e0b9d8）渲染 "Plan" 卡片；每项轻量 tap-to-mark-done；voice 入口：现有 `BuiltInSkills.kt:56-212` skill regex planner 加 "show the plan"/"mark that done"/"add step X" 路由到 store。
- 明确不做：snapshot/revert、ServiceLoader/plugin discovery、跨 session "project" todos（待多 session 工作流证据出现再做）、CLI 风格 CRUD。
- TTL：绑定 `AgentTraceStore` 保留策略，防持久化膨胀；voice 位置引用问题（"第二个"）用序号渲染 + 位置/标题模糊匹配；Plan 卡片应收敛 active skill steps 到同一 timeline 而非维护两份列表。

### 17. Undo / 补偿动作注册（取代通用 snapshot/revert）

**参考**：opencode Snapshot/Revert 在桌面 coding agent 上成立（完整控制 workspace 文件 + content-addressed snapshot store）。**Android 上通用 snapshot/revert 是虚假安全感**——侧效应分三类：(a) 外部 Activity intent（share sheet、邮件/日历/联系人草稿、闹钟/计时器屏、相机、deep link）交控到其他进程，Solin 无法观察/回滚用户行为；(b) AlarmManager 调度的 reminder/periodic-check 在 Solin 控制内，已有 `AgentRecoveryAction`（CANCEL_REMINDER，通过 `isSafeRecoveryTaskId` 严格 taskId 校验，挂在 `latestRecoveryAction`）；(c) accessibility 驱动的 UI 动作（ui_tap/type/scroll）无事务边界可回滚，"undo tap" 需知道前一 UI 状态并发反向手势，跨 app 不可靠。

**建议**：将现有 `AgentRecoveryAction` 模式泛化为 per-tool `UndoPolicy` 注册，**不**做通用文件系统 snapshot：
```kotlin
sealed class UndoPlan {
    data class CompensatingTool(val request: ToolRequest, val draft: ActionDraft,
                                val ttlMillis: Long = 60_000) : UndoPlan()
    data class ExternalHandoff(val reason: String) : UndoPlan()
    data object NotApplicable : UndoPlan()
    data class NotUndoable(val reason: String) : UndoPlan()
}
interface UndoPolicy {
    fun planUndoAfter(request: ToolRequest, result: ToolResult): UndoPlan? = null
}
// ToolRegistry 加 private val undoPolicies: MutableMap<String, UndoPolicy>
// 默认分类：schedule_reminder/configure_periodic_check(enabled=true) → CompensatingTool；
//   open_*/share_text/compose_email/create_calendar_event/create_contact_draft/
//   set_system_alarm/set_system_timer/open_camera/open_deep_link → ExternalHandoff；
//   query_*/read_clipboard/observe_*/read_*_ocr → NotApplicable；
//   ui_tap/ui_type_text/ui_scroll/ui_submit_search → NotUndoable
```
- `UndoEntry(sourceRunId, sourceRequestId, toolName, plan: CompensatingTool, availableUntilMillis)` 栈存在 AgentLoopRuntime/trace；UI 复用 `latestRecoveryAction` 槽位泛化为"last undoable action"，仅 `CompensatingTool` 时显示 undo 按钮（明确不显示给 ExternalHandoff/NotUndoable，防信任崩塌）。
- 补偿 taskId 等 ID 必须来自服务端可信 ToolResult 数据并走 `isSafeRecoveryTaskId` 式严格校验，禁止信任模型提供的 ID；TTL 过期、新动作压栈、用户执行后清除；多 tool 单 turn 按 succeeded tool 顺序逐栈压入，防 undo 错对象。
- step 前后状态证据（原 #16 Snapshot/Diff 的审计部分）收敛为：仅对 Solin 自有状态（reminder 列表、periodic-check 配置）做 before/after  witnesses 入 trace，**不**对其他 app/通知栏/前台 app 做快照（敏感 + 大）。

### 18. Session Branching / Fork

**参考**：pi session 树结构 + branch summarization。**维持 P3，不升级**：(1) 侧效应是无法通过分叉会话撤销的设备动作；(2) 无文件/workspace 模型（git-backed snapshot 才让 revert 自然）；(3) 移动聊天 UI 不暴露会话分支（用户预期线性 chat + edit-and-retry/regenerate）；(4) 审计显示零用户 plan/todo/edit 面，引入分支是为未请求的 UX 模式建基础设施；(5) 隐私-first on-device 约束反对倍增存储的会话历史分支。真正的 P2 缺口是最后一次 tool call 的轻量 undo/regenerate（#17 已覆盖），而非完整 fork。

### 19. Result<T,E> 风格统一异常流

**参考**：pi 所有 agent 循环错误编码为流内事件（`stopReason: "error" | "aborted"` + `errorMessage`），不 throw；opencode 用 Effect 把错误变为 checked 类型。

**现状**：Solin Harness 层主要靠 `Result<T>`/sealed class，但 tool 实现里仍有 RuntimeException 路径。统一约定：**Harness 边界不抛**；所有失败进 `AgentStep.*Failed` 事件；事件总线的 `RunFailed(code, msg)`/`ToolFailed(code, ...)` 用结构化错误码，降低 crash 率、提高 resume 成功率。

### 20. Tool 输出有界化（executor 层面）

**参考**：opencode `ToolOutputStore.bound(...)`。

**现状**：web search、screenshot、OCR 结果偏大，主要靠调用方自裁剪（e.g. PdfTextPreviewReader token 限制）。#12 的 `EvidenceBlobStore` 已覆盖 blob 溢出；executor 层面再统一做 key 级长度上限（复用 EvidenceBounds），tool result 留摘要 + ev:// 引用，显著缓解 context 压力；与 P0 compaction 联动效果更好。

---

## 推荐实施顺序（兼顾收益与风险）

1. **第一步（P0）**：#1 拆分 + 类型化事件总线（拆分时同步引入总线，避免二次迁移）+ #2 Hook 接口（拆分时预留 hook 点）。三件事同批，是后续所有改造的接缝。
2. **第二步（P0）**：#3 Compaction（含 overflow recovery）+ #4 Streaming tool progress（直接改善长任务体验；本地 8k 窗口最急；#4 的 ToolProgress 走 #1 的总线）。
3. **第三步（P1）**：#5 Cancel/Steer + #6 泛化并行 + #7 AgentProfile + #9 Ask-User/PermissionGateway（共同构成"多模式可靠运行时"；#5 的取消信号贯穿 tool 与 hook，#9 共享 Deferred 管理）。
4. **第四步（P1）**：#8 SystemPromptBuilder + 最小 ContextContributor 接口 + #10 TelemetrySink（builder 数据模型先行；telemetry 可订阅总线自动派生）。
5. **第五步（P2）**：#11 内部模块 SPI（Hilt 多绑定）+ #13 ReasoningEffort + #12 EvidenceBlobStore（生态与可配置性；SPI 是 MCP 的前置条件）。
6. **第六步（P2）**：#14 Credential/OAuth + #15 MCP 客户端（Binder/IPC，基于 SPI）。
7. **第七步（P3）**：#16 Session Plan、#17 Undo/补偿动作、#18 Branching、#19 Result<T,E>、#20 Tool 输出有界化，按 dogfood 反馈按需排序。

---

## 验证过的设计决策（保留）

以下决策经对照 opencode/pi 与审计验证是正确的，不应在重构中被"顺手改掉"：

1. **失败关闭安全模型（fail-closed）**：`SafetyPolicy.evaluate()` 默认 Reject、`ToolAuthorizationPolicy` 对未声明 riskLevel 的工具拒绝放行、RemoteSend 默认 MaskedSend——这些默认拒绝的姿态对 accessibility agent 是正确的，不要改成 fail-open。
2. **不引入 Effect.ts/IO 态**：Kotlin 协程 + Flow 已覆盖 opencode Effect 用法（fiber ≈ coroutine/Job、Scope ≈ CoroutineScope/Closeable、Layer ≈ DI 模块），引入 Effect 风格只会抬升 Kotlin 代码认知成本。
3. **不做 JS/Lua/dex 动态插件运行时**：扩展能力必须是编译期/签名白名单的 Kotlin 模块（#11 内部 SPI）+ 跨进程 MCP（#15）。Android 权限模型下 in-process 第三方代码等于把 Solin UID 的全部 accessibility/contact/file/notification 权限拱手让人。
4. **设备动作串行 + 显式确认**：`isPublicEvidenceBatchEligible()` 将并发严格限制在只读/公共证据类工具，高风险动作（发消息、付款、改设置）始终串行 + confirmation。#6 泛化并行时必须保留该护栏。
5. **不 premature 拆 Gradle 模块**：当前单 app module + 清晰 package 划分（`orchestration/`、`tool/`、`safety/`、`runtime/`、`audit/`、`data/`）足以支撑 #1-#20 的内部拆分；多 module 改造本身无用户价值且会打断当前单测/DI/R8 配置，待代码体量实际翻倍再考虑。
6. **`AgentTraceStore` 作为 resume 真值源**：持久化走 trace store + Room DAO；事件总线（#1）是分发机制不做持久化投影，避免双写一致性坑。
7. **`ChatUiState`（MutableStateFlow）保留为 UI 态**：ViewModel 订阅总线事件 fold 进 `_uiState.update`；不追求从事件 replay 派生整棵 UI（opencode 风格 durable stream rendering 是多月重写且无用户可见收益）。
8. **PII 脱敏在边界集中处理**：`ToolAuditSummaryRedactor`、`sanitizeId`/`sanitizeText`（`AgentRunEvents.kt:649-697`）、LocalOnly 隐私标记已经构成边界集中脱敏，不要把脱敏下沉到每个订阅者/工具重复实现。
9. **RC-perf 严格不伪造原则**：`PerfSample.validate()`、`buildRcPerfResult` 返回 Failure 而非伪造数值（`rcperf/RcPerf.kt:112-151`）在指标体系扩展时保持。
10. **审计与指标分离**：`ToolAuditSink`/`RemoteSendAuditSink` 是治理痕迹（谁批了什么、PII 脱敏摘要、环形 500 条），`TelemetrySink`（#10）是性能/可靠度通道，opencode 也保持 observability 与 event bus 正交——不要把两者融合成一个"大事件系统"。

---

## 反模式提醒

- **不要照搬 opencode 的 SQLite 事件溯源/versioned manifest/projector-in-tx**：单进程 Android 已有 `AgentTraceStore` + Room 审计表承载持久化，Kotlin sealed class 提供编译期类型清单；再造一个 event 表 + 乐观并发 + idempotent replay 会制造双写一致性 bug 和迁移成本。durable=订阅者写 Room，不是总线内 projector。
- **不要照搬 opencode 的 31-package monorepo 分层**：opencode 的 `packages/core`/`packages/schema`/`packages/agent` 等拆分服务于多端 TUI/web/desktop 发布与多 npm 包；Solin 是单 Android app，保持 package 内聚 + 类级单一职责即可。
- **不要把 Plugin 系统做成通用脚本运行时**：JS/Lua/动态 dex 插件在 accessibility agent 上等于权限升级磁石；Play 政策对动态加载危险代码有 DCL 检查；R8 混淆下 ServiceLoader/@AutoService 不可靠。扩展面 = 编译期 Hilt 多绑定 + 跨进程 MCP。
- **in-process 插件绝不能运行在宿主权限下**：即便是"签名模块"也不行——进程内代码可通过反射绕过任何自制签名检查；Android 进程/UID/Permission 边界本身就是安全边界，扩展必须跨进程。
- **Compaction 不要丢 tool-result 语义**：evidence 类结果（截图、长搜索原文）优先裁剪到 #12 的 ev:// blob 引用而非摘要成一句话，否则第二轮规划丢失事实基础；overflow recovery 必须有重试上限避免死循环。
- **Hook 禁止抛异常**：所有 hook 失败必须降级 no-op；`beforeToolCall` 的 Block 通过 error tool-result 回灌模型，绝不 throw（否则一个坏 hook 会让整个 agent 循环崩溃）。
- **总线不得承载 per-token 高频流**：token delta 继续走 runtime 冷 `Flow<String>`；总线只承载 lifecycle/metric/progress 事件，否则在百 chunk/s 速率下回压会拖慢推理。
- **总线 publish 必须是非阻塞 tryEmit + DROP_OLDEST**：订阅者慢不能阻塞发布方；订阅者在自己的协程作用域 collect，禁止在 publish 调用栈内同步做 Room 写入等重活。
- **不要把 ev:// blob URI 暴露给模型作为可调用工具**：这会制造开放读环 + 新权限面；blob 仅供运行时（prompt 装配、citation UI 点击、跨进程死亡恢复）内部使用。
- **不要在 v1 引入 OTLP/OpenTelemetry**：on-device 默认无 collector，protobuf/batching/网络依赖/错误路径会 dominate 变更；Logcat + in-app overlay + 可选 JSONL export 足够支撑 dogfood 阶段。
- **错误码枚举不要过度细化**：初版 5-10 个知名码 + `Unknown(cause)` 兜底即可，按 crash log 出现频率渐进添加，不要试图预先穷举所有失败模式。
- **不要在第三个 ContextContributor 出现前做完整 context 注册框架**：目前只有 memory + deviceContext 两个 contributor，YAGNI；先抽 6 行接口（#8），等第三个贡献者出现再升级。
- **不要把 `DEFAULT_CHAT_SYSTEM_INSTRUCTION` 常量折进 registry**：会破坏现有 `ChatPromptsTest` 快照测试且无收益；常量与 per-turn 证据拼装生命周期不同，不共用 keyspace。
- **Undo 按钮不得对 ExternalHandoff/NotUndoable 显示**：对 ui_tap/share/外部 Activity 承诺"undo"会建立虚假安全感，比没有 undo 更伤信任；仅 `CompensatingTool` 可显示。
- **不要为了 fork/branching 牺牲隐私和主交互**：移动 chat 用户预期线性历史 + regenerate，分叉是 IDE 风格探索模式，非当前产品主路径。
