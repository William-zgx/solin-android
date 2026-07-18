# 自适应端云推理第一阶段 Implementation Plan

当前已合并事实：[status.md](status.md)（本文件保留实施目标与验收步骤）

**Goal:** 在现有 Android 应用和手工远程模型配置之上，交付隐私优先、可解释、run 级不可变的 Local / Auto / Remote 推理放置，并保证同一 run 永不跨本地与远端双发。

**Architecture:** 保留现有 `InferenceMode` 存储键和 Local/Remote 兼容值，把它明确收敛为“用户偏好”并增加 Auto；新增独立 `RunPlacement` 作为实际执行位置。纯 `ModelPlacementPolicy` 只读取聚合隐私、能力、复杂度、稳定资源和新鲜连接信号；`agent_run_placement_bindings` 作为 canonical run binding，统一 dispatcher 在真正调用 runtime 前原子 claim，并把 placement、invocation 和 receipt 写入现有 Agent trace。

**Tech Stack:** Kotlin、Coroutines/Flow、Android DataStore、Room 17→18 migration、Jetpack Compose、JUnit4、Android instrumentation tests、现有 LiteRT/OkHttp runtime、现有隐私与验证脚本；不新增第三方依赖。

---

## 实施边界与关键决策

1. 第一阶段只复用当前 `RemoteModelConfig` 和 OpenAI-compatible runtime，不加入配对、节点发现、远端负载协议、请求幂等或请求级 edge cancel。
2. 为减少无收益迁移，`InferenceMode` 不做全仓重命名：它在实现后表示 preference，值为 `Local / Auto / Remote`；实际目标只使用新的 `RunPlacement.Local / Remote`。两种语义不会共用字段或从彼此反推。
3. placement 只约束用户可见 Chat serving runtime；embedding、规则分类、action planning 等本地辅助路径不计入 placement。
4. disclosure 前先完成 route、privacy plan、placement 和持久绑定；确认只恢复同一个 prepared run，不重新 route 或重新决策。配置 revision 变化立即使 prepared run 失效。
5. binding 使用独立 Room 表和事务，不从旧 `InferenceMode`、receipt 或通用 `RestoredSummary` 猜测。旧进行中 run 没有 binding 时 fail closed。
6. rollout 使用单一有序值 `off / shadow / opt_in / visible`，避免两个布尔开关形成非法组合。S4、S5 所有变体保持 `off`；只有 S6 完成首次调用、续写、retry、stop/cancel 和恢复的整 run 绑定验证后，debug 才可改为 `opt_in`。release-like 变体继续默认 `off`，且仍由用户显式选择 Auto 并确认披露。
7. 不向 `SolinViewModel.kt`、`SolinScreen.kt` 或 `AgentLoopRuntime.kt` 堆放策略与状态机；分别提取 policy、privacy planner、resource aggregator、binding store、dispatcher 和 prepared-run coordinator。

## 文件结构

| 类型 | 文件 | 职责 |
|---|---|---|
| Create | `app/src/main/java/com/bytedance/zgx/solin/orchestration/ModelPlacementPolicy.kt` | placement 数据合同、候选裁剪、稳定原因码和纯决策表 |
| Create | `app/src/main/java/com/bytedance/zgx/solin/orchestration/RequestComplexityAggregator.kt` | 用结构化 token/reasoning/plan/output 信号判定 Simple/Complex/Unknown |
| Create | `app/src/main/java/com/bytedance/zgx/solin/orchestration/PromptPrivacyPlan.kt` | 独立聚合输入、历史、memory/evidence、工具、steer 与 queued input 的隐私边界 |
| Create | `app/src/main/java/com/bytedance/zgx/solin/resource/StableResourceSnapshotAggregator.kt` | 10 秒、最多 3 样本、2-of-3、15 秒 cooldown |
| Create | `app/src/main/java/com/bytedance/zgx/solin/orchestration/RunPlacementBinding.kt` | canonical binding、恢复判定和 destination 转换 |
| Create | `app/src/main/java/com/bytedance/zgx/solin/orchestration/RunPlacementBindingStore.kt` | Room/in-memory binding CAS 与 trace 同事务写入 |
| Create | `app/src/main/java/com/bytedance/zgx/solin/orchestration/ModelRuntimeDispatcher.kt` | serving runtime 唯一 claim/stop 入口，拒绝跨 placement 调用 |
| Create | `app/src/main/java/com/bytedance/zgx/solin/presentation/PreparedChatRunCoordinator.kt` | disclosure 前后的同一 run 准备态，仅在进程内保存正文 |
| Create | `app/src/main/java/com/bytedance/zgx/solin/AdaptiveInferenceRollout.kt` | rollout stage 解析与 fail-safe 降级 |
| Modify | `app/src/main/java/com/bytedance/zgx/solin/RemoteModels.kt:5-110` | Auto preference、远端 tool/context 能力、profile revision、连接快照合同 |
| Modify | `app/src/main/java/com/bytedance/zgx/solin/data/PreferenceSettingsStore.kt:94-158,230-249` | 兼容 preference codec，保存 capability/revision，停止恢复连接可达性 |
| Modify | `app/src/main/java/com/bytedance/zgx/solin/data/Stores.kt:75-109` | preference/config/connectivity snapshot 接口 |
| Modify | `app/src/main/java/com/bytedance/zgx/solin/data/RemoteModelRepository.kt:12-74` | 进程内 revision-bound 60 秒连接快照 |
| Modify | `app/src/main/java/com/bytedance/zgx/solin/data/LegacyPrefsMigrator.kt:219-247` | 旧 Local/Remote 和损坏值迁移 |
| Modify | `app/src/main/java/com/bytedance/zgx/solin/data/SolinDatabase.kt:158-176,573-746,748-1135` | binding entity/DAO、事务、17→18 migration |
| Modify | `app/src/main/java/com/bytedance/zgx/solin/orchestration/AgentModels.kt:62-181` | placement 与 invocation trace steps，receipt 仍只保留实际 destination |
| Modify | `app/src/main/java/com/bytedance/zgx/solin/orchestration/AgentTraceStore.kt:84-111,301-445,1400-1545` | 新 trace codec、binding store 组合，不承载策略 |
| Modify | `app/src/main/java/com/bytedance/zgx/solin/orchestration/AssistantOrchestrator.kt:53-145,178-390` | 暴露 placement trace/binding 的窄接口，不增长 Agent loop |
| Modify | `app/src/main/java/com/bytedance/zgx/solin/resource/SystemResourceMonitor.kt:14-127` | 完整 thermal 档位并向稳定聚合器记录样本 |
| Modify | `app/src/main/java/com/bytedance/zgx/solin/SolinAppContainer.kt:140-178,316-450` | app-scoped resource monitor、binding store、dispatcher、rollout 注入 |
| Modify | `app/src/main/java/com/bytedance/zgx/solin/MainActivity.kt:79-81,243-377` | UI 和 placement 共用同一 monitor，不新增采样循环 |
| Modify | `app/src/main/java/com/bytedance/zgx/solin/ChatModels.kt:251-351,393-456` | pending disclosure revision、preference 与 active placement/reason 分离 |
| Modify | `app/src/main/java/com/bytedance/zgx/solin/SolinViewModel.kt:199-365,500-565,823-933,1077-1127` | 薄 wiring、Auto opt-in 和状态投影 |
| Modify | `app/src/main/java/com/bytedance/zgx/solin/presentation/ModelLoadController.kt:438-527,1180-1247` | revision 轮换、异步 probe、Auto/Remote readiness |
| Modify | `app/src/main/java/com/bytedance/zgx/solin/presentation/ChatController.kt:120-255,331-1238,1319-1375` | prepare→route→privacy→decide→bind→disclose/dispatch |
| Modify | `app/src/main/java/com/bytedance/zgx/solin/presentation/ChatRemoteSendSupport.kt:33-152,350-450` | 确认恢复同一 prepared run，校验 revision |
| Modify | `app/src/main/java/com/bytedance/zgx/solin/presentation/ChatSharedInputSupport.kt:65-150,295-335` | 附件先本地暂存，隐私不再依赖 preference |
| Modify | `app/src/main/java/com/bytedance/zgx/solin/presentation/ChatToolContinuationSupport.kt:72-569` | 续写只读 binding，LocalOnly observation fail closed |
| Modify | `app/src/main/java/com/bytedance/zgx/solin/presentation/ToolExecutionController.kt:270-330,440-500` | 工具结果边界只读 binding |
| Modify | `app/src/main/java/com/bytedance/zgx/solin/presentation/ChatGenerationSupport.kt:150-259,283-336` | stop/readiness/retry 使用 binding，日志移除 URL |
| Modify | `app/src/main/java/com/bytedance/zgx/solin/ui/components/ModelManagerSheet.kt:237-488` | Local/Auto/Remote 三选一和 tool/context 配置 |
| Modify | `app/src/main/java/com/bytedance/zgx/solin/ui/components/RemoteModeDisclosureSheet.kt:19-87` | Auto 明确启用确认 |
| Modify | `app/src/main/java/com/bytedance/zgx/solin/ui/components/ChatTopBar.kt:387-414` | 同时显示 preference 与本次实际位置 |
| Modify | `app/src/main/java/com/bytedance/zgx/solin/ui/components/ChatEmptyState.kt:38-65` | Auto 就绪/阻断文案 |
| Modify | `app/src/main/java/com/bytedance/zgx/solin/ui/components/FirstRunSetupPanel.kt:130-175` | 不再从 preference 猜当前 runtime |
| Modify | `app/src/main/java/com/bytedance/zgx/solin/ui/SolinScreen.kt:148-190,567-588,912-1046` | actual placement/reason 展示，Auto 附件暂存 |
| Modify | `app/build.gradle.kts:195-260` | rollout stage BuildConfig |
| Modify | `docs/**`, `scripts/**` | 代码落地后同步当前事实、能力/隐私/发布证据和验证脚本 |

### Task 1: 兼容 preference、远端 capability/revision 与新鲜连接快照

**Files:**
- Modify: `app/src/main/java/com/bytedance/zgx/solin/RemoteModels.kt:5-110`
- Modify: `app/src/main/java/com/bytedance/zgx/solin/data/PreferenceSettingsStore.kt:94-158,230-249`
- Modify: `app/src/main/java/com/bytedance/zgx/solin/data/Stores.kt:75-109`
- Modify: `app/src/main/java/com/bytedance/zgx/solin/data/RemoteModelRepository.kt:12-74`
- Modify: `app/src/main/java/com/bytedance/zgx/solin/data/LegacyPrefsMigrator.kt:219-247`
- Test: `app/src/test/java/com/bytedance/zgx/solin/data/InferencePreferenceCodecTest.kt`
- Test: `app/src/test/java/com/bytedance/zgx/solin/data/RemoteModelRepositoryTest.kt`
- Test: `app/src/test/java/com/bytedance/zgx/solin/RemoteModelConfigTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
class InferencePreferenceCodecTest {
    @Test fun decodesCompatibleAndUnknownValuesFailClosed() {
        assertEquals(InferenceMode.Local, decodeInferenceMode("Local"))
        assertEquals(InferenceMode.Remote, decodeInferenceMode("Remote"))
        assertEquals(InferenceMode.Auto, decodeInferenceMode("Auto"))
        assertEquals(InferenceMode.Local, decodeInferenceMode("future-mode"))
        assertEquals(InferenceMode.Local, decodeInferenceMode(""))
    }
}

@Test fun materialConfigChangeRotatesRevisionAndInvalidatesConnectivity() {
    var now = 1_000L
    val repository = repository(elapsedRealtimeMillis = { now }, uuid = sequenceUuid())
    val first = repository.saveConfig(config(model = "model-a", tools = false)).getOrThrow()
    repository.recordConnectivity(first, RemoteModelConnectivityStatus.Reachable)
    assertEquals(RemoteModelConnectivityStatus.Reachable, repository.currentConnectivity(first)?.status)

    val second = repository.saveConfig(first.copy(supportsToolCalls = true)).getOrThrow()
    assertNotEquals(first.profileRevision, second.profileRevision)
    assertNull(repository.currentConnectivity(second))
}

@Test fun connectivityExpiresAtSixtySecondsAndNeverRestoresAcrossRepositoryInstance() {
    var now = 10_000L
    val first = repository(elapsedRealtimeMillis = { now })
    val config = first.saveConfig(config()).getOrThrow()
    first.recordConnectivity(config, RemoteModelConnectivityStatus.Reachable)
    now += 59_999L
    assertEquals(RemoteModelConnectivityStatus.Reachable, first.currentConnectivity(config)?.status)
    now += 1L
    assertNull(first.currentConnectivity(config))
    assertNull(repository(settingsStore = first.settingsStore, elapsedRealtimeMillis = { now }).currentConnectivity(config))
}
```

- [ ] **Step 2: 验证测试失败**

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.solin.data.InferencePreferenceCodecTest' \
  --tests 'com.bytedance.zgx.solin.data.RemoteModelRepositoryTest' \
  --tests 'com.bytedance.zgx.solin.RemoteModelConfigTest'
```

Expected: FAIL，编译器报告 `Auto`、`supportsToolCalls`、`profileRevision`、`recordConnectivity` 和 `currentConnectivity` 尚不存在。

- [ ] **Step 3: 最小实现**

```kotlin
enum class InferenceMode { Local, Auto, Remote }

internal fun decodeInferenceMode(raw: String?): InferenceMode =
    runCatching { InferenceMode.valueOf(raw.orEmpty()) }.getOrDefault(InferenceMode.Local)

data class RemoteModelConfig(
    val baseUrl: String = "",
    val modelName: String = "",
    val apiKey: String = "",
    val supportsVisionInput: Boolean = false,
    val supportsToolCalls: Boolean = false,
    val contextWindowTokens: Int? = null,
    val profileRevision: String = "",
    val connectivityStatus: RemoteModelConnectivityStatus = RemoteModelConnectivityStatus.Unknown,
)

data class RemoteConnectivitySnapshot(
    val configRevision: String,
    val status: RemoteModelConnectivityStatus,
    val checkedAtElapsedRealtimeMs: Long,
    val ttlMs: Long = 60_000L,
) {
    fun isFresh(nowElapsedRealtimeMs: Long): Boolean =
        nowElapsedRealtimeMs - checkedAtElapsedRealtimeMs in 0 until ttlMs
}

interface RemoteModelStore {
    fun loadMode(): InferenceMode
    fun saveMode(mode: InferenceMode): InferenceMode
    fun loadConfig(): RemoteModelConfig
    fun saveConfig(config: RemoteModelConfig): Result<RemoteModelConfig>
    fun recordConnectivity(config: RemoteModelConfig, status: RemoteModelConnectivityStatus)
    fun currentConnectivity(config: RemoteModelConfig): RemoteConnectivitySnapshot?
    fun invalidateConnectivity()
}
```

`RemoteModelRepository.saveConfig` 先比较 base URL、model、API key、vision、tools、context 六个物质字段；任一变化用注入的 UUID provider 生成 revision，并清空内存快照。`PreferenceSettingsStore` 继续复用 `inference_mode` key，新增 tool/context/revision keys，不再保存或恢复 `Reachable`；`loadConfig()` 只把同 revision 且未满 60 秒的内存快照投影到 `connectivityStatus`。`LegacyPrefsMigrator` 对无法识别的旧值显式保存 Local，不触发 probe 或 runtime。

- [ ] **Step 4: 验证测试通过**

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.solin.data.InferencePreferenceCodecTest' \
  --tests 'com.bytedance.zgx.solin.data.RemoteModelRepositoryTest' \
  --tests 'com.bytedance.zgx.solin.RemoteModelConfigTest'
```

Expected: PASS；旧 Local/Remote 兼容，损坏值为 Local，capability 默认 fail closed，连接在 60 秒边界和 repository 重建后不可用于 Auto。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/bytedance/zgx/solin/RemoteModels.kt \
  app/src/main/java/com/bytedance/zgx/solin/data/PreferenceSettingsStore.kt \
  app/src/main/java/com/bytedance/zgx/solin/data/Stores.kt \
  app/src/main/java/com/bytedance/zgx/solin/data/RemoteModelRepository.kt \
  app/src/main/java/com/bytedance/zgx/solin/data/LegacyPrefsMigrator.kt \
  app/src/test/java/com/bytedance/zgx/solin/data/InferencePreferenceCodecTest.kt \
  app/src/test/java/com/bytedance/zgx/solin/data/RemoteModelRepositoryTest.kt \
  app/src/test/java/com/bytedance/zgx/solin/RemoteModelConfigTest.kt
git commit -m "feat(inference): add adaptive preference and remote freshness"
```

### Task 2: 纯复杂度、资源聚合与 placement 决策

**Files:**
- Create: `app/src/main/java/com/bytedance/zgx/solin/orchestration/ModelPlacementPolicy.kt`
- Create: `app/src/main/java/com/bytedance/zgx/solin/orchestration/RequestComplexityAggregator.kt`
- Create: `app/src/main/java/com/bytedance/zgx/solin/resource/StableResourceSnapshotAggregator.kt`
- Modify: `app/src/main/java/com/bytedance/zgx/solin/resource/SystemResourceMonitor.kt:14-127`
- Test: `app/src/test/java/com/bytedance/zgx/solin/orchestration/ModelPlacementPolicyTest.kt`
- Test: `app/src/test/java/com/bytedance/zgx/solin/orchestration/RequestComplexityAggregatorTest.kt`
- Test: `app/src/test/java/com/bytedance/zgx/solin/resource/StableResourceSnapshotAggregatorTest.kt`
- Test: `app/src/test/java/com/bytedance/zgx/solin/resource/SystemResourceMonitorTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
@Test fun seventyPercentOrHighReasoningIsComplex() {
    assertEquals(RequestComplexity.Simple, complexity(tokens = 699, window = 1_000))
    assertEquals(RequestComplexity.Complex, complexity(tokens = 700, window = 1_000))
    assertEquals(RequestComplexity.Complex, complexity(reasoning = ReasoningEffort.High))
    assertEquals(RequestComplexity.Unknown, complexity(tokens = 700, window = null))
}

@Test fun stableWindowUsesTwoOfThreeAndCooldown() {
    val window = StableResourceSnapshotAggregator(elapsedRealtimeMillis = clock::now)
    assertEquals(StableResourceBand.Unknown, window.current())
    window.record(snapshot(ResourcePressure.Hot))
    assertEquals(StableResourceBand.Unknown, window.current())
    window.record(snapshot(ResourcePressure.Normal))
    assertEquals(StableResourceBand.Normal, window.current())
    window.record(snapshot(ResourcePressure.Hot))
    assertEquals(StableResourceBand.Hot, window.current())
    clock.advance(14_999)
    window.record(snapshot(ResourcePressure.Normal)); window.record(snapshot(ResourcePressure.Normal))
    assertEquals(StableResourceBand.Hot, window.current())
    clock.advance(1)
    assertEquals(StableResourceBand.Normal, window.current())
}

@Test fun autoDecisionTableIsPrivacyFirstAndDeterministic() {
    assertBlocked(autoInput(privacy = MessagePrivacy.LocalOnly, local = unavailable(), remote = eligible()))
    assertChosen(RunPlacement.Local, autoInput(complexity = Simple, resource = Normal))
    assertChosen(RunPlacement.Remote, autoInput(complexity = Complex, remote = eligibleFresh()))
    assertChosen(RunPlacement.Local, autoInput(complexity = Complex, remote = stale()))
    assertChosen(RunPlacement.Remote, autoInput(resource = Hot, remote = eligibleFresh()))
    assertChosen(RunPlacement.Local, autoVisionInput(localVision = true, remoteVision = true))
    assertChosen(RunPlacement.Remote, autoVisionInput(localVision = false, remoteVision = true))
}
```

- [ ] **Step 2: 验证测试失败**

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.solin.orchestration.ModelPlacementPolicyTest' \
  --tests 'com.bytedance.zgx.solin.orchestration.RequestComplexityAggregatorTest' \
  --tests 'com.bytedance.zgx.solin.resource.StableResourceSnapshotAggregatorTest' \
  --tests 'com.bytedance.zgx.solin.resource.SystemResourceMonitorTest'
```

Expected: FAIL，placement、complexity 和 stable resource 类型尚不存在，thermal 也无法表达 Severe/Critical。

- [ ] **Step 3: 最小实现**

```kotlin
enum class RunPlacement { Local, Remote }
enum class RequestComplexity { Simple, Complex, Unknown }
enum class StableResourceBand { Unknown, Normal, Warm, Hot }
enum class CandidateState { Eligible, Unavailable, Unauthorized, CapabilityMismatch, PrivacyBlocked, Stale }

enum class PlacementReasonCode {
    USER_FORCED_LOCAL, USER_FORCED_REMOTE, PRIVACY_REQUIRES_LOCAL,
    LOCAL_MODEL_UNAVAILABLE, LOCAL_RESOURCE_BLOCKED, LOCAL_CAPABILITY_MISMATCH,
    REMOTE_NOT_AUTHORIZED, REMOTE_NOT_CONFIGURED, REMOTE_CONNECTIVITY_UNAVAILABLE,
    REMOTE_STATUS_STALE, REMOTE_CAPABILITY_MISMATCH, REMOTE_OVERLOADED,
    AUTO_SIMPLE_LOCAL, AUTO_IMAGE_LOCAL, AUTO_COMPLEX_REMOTE, AUTO_RESOURCE_REMOTE,
    NO_ELIGIBLE_TARGET, PLACEMENT_DECISION_MISSING, PLACEMENT_NOT_RESTORABLE,
    PLACEMENT_LOCAL_CONTINUATION_REQUIRED, MODEL_EXECUTION_FAILED,
}

data class ModelRequirements(
    val requiresVision: Boolean,
    val requiresTools: Boolean,
    val estimatedInputTokens: Int?,
    val requestedOutputTokens: Int,
)

data class ModelCandidateSnapshot(
    val state: CandidateState,
    val supportsText: Boolean,
    val supportsVision: Boolean,
    val supportsTools: Boolean,
    val contextWindowTokens: Int?,
)

data class PlacementDiagnostics(
    val complexity: RequestComplexity,
    val localState: CandidateState,
    val remoteState: CandidateState,
    val secondaryReasons: List<PlacementReasonCode> = emptyList(),
)

sealed interface PlacementDecision {
    val policyVersion: Int
    val preference: InferenceMode
    val primaryReason: PlacementReasonCode
    val diagnostics: PlacementDiagnostics

    data class Chosen(
        override val policyVersion: Int,
        override val preference: InferenceMode,
        override val primaryReason: PlacementReasonCode,
        override val diagnostics: PlacementDiagnostics,
        val placement: RunPlacement,
    ) : PlacementDecision

    data class Blocked(
        override val policyVersion: Int,
        override val preference: InferenceMode,
        override val primaryReason: PlacementReasonCode,
        override val diagnostics: PlacementDiagnostics,
    ) : PlacementDecision
}

data class ModelPlacementInput(
    val preference: InferenceMode,
    val privacy: MessagePrivacy,
    val requiresLocalModel: Boolean,
    val remoteAuthorized: Boolean,
    val requirements: ModelRequirements,
    val local: ModelCandidateSnapshot,
    val remote: ModelCandidateSnapshot,
    val remoteConnectivityFresh: Boolean,
    val remoteManualEligible: Boolean,
    val resourceBand: StableResourceBand,
    val complexity: RequestComplexity,
)

private fun ModelPlacementInput.choose(
    placement: RunPlacement,
    reason: PlacementReasonCode,
): PlacementDecision.Chosen = PlacementDecision.Chosen(
    policyVersion = ModelPlacementPolicy.POLICY_VERSION,
    preference = preference,
    primaryReason = reason,
    diagnostics = PlacementDiagnostics(complexity, local.state, remote.state),
    placement = placement,
)

private fun ModelPlacementInput.block(reason: PlacementReasonCode): PlacementDecision.Blocked =
    PlacementDecision.Blocked(
        policyVersion = ModelPlacementPolicy.POLICY_VERSION,
        preference = preference,
        primaryReason = reason,
        diagnostics = PlacementDiagnostics(complexity, local.state, remote.state),
    )

private fun ModelCandidateSnapshot.primaryFailureReason(): PlacementReasonCode = when (state) {
    CandidateState.Unauthorized -> PlacementReasonCode.REMOTE_NOT_AUTHORIZED
    CandidateState.Stale -> PlacementReasonCode.REMOTE_STATUS_STALE
    CandidateState.CapabilityMismatch -> PlacementReasonCode.REMOTE_CAPABILITY_MISMATCH
    CandidateState.PrivacyBlocked -> PlacementReasonCode.PRIVACY_REQUIRES_LOCAL
    CandidateState.Unavailable -> PlacementReasonCode.REMOTE_CONNECTIVITY_UNAVAILABLE
    CandidateState.Eligible -> PlacementReasonCode.NO_ELIGIBLE_TARGET
}

object ModelPlacementPolicy {
    const val POLICY_VERSION = 1

    fun decide(input: ModelPlacementInput): PlacementDecision {
        val localEligible = input.local.state == CandidateState.Eligible
        val remoteEligible = input.remote.state == CandidateState.Eligible &&
            input.remoteConnectivityFresh
        if (input.requiresLocalModel || input.privacy != MessagePrivacy.RemoteEligible) {
            if (input.preference == InferenceMode.Remote) {
                return input.block(PlacementReasonCode.PRIVACY_REQUIRES_LOCAL)
            }
            return if (localEligible) {
                input.choose(RunPlacement.Local, PlacementReasonCode.PRIVACY_REQUIRES_LOCAL)
            } else {
                input.block(PlacementReasonCode.NO_ELIGIBLE_TARGET)
            }
        }
        return when (input.preference) {
            InferenceMode.Local -> if (localEligible) input.choose(RunPlacement.Local, PlacementReasonCode.USER_FORCED_LOCAL)
                else input.block(PlacementReasonCode.LOCAL_MODEL_UNAVAILABLE)
            InferenceMode.Remote -> if (input.remoteManualEligible) input.choose(RunPlacement.Remote, PlacementReasonCode.USER_FORCED_REMOTE)
                else input.block(input.remote.primaryFailureReason())
            InferenceMode.Auto -> when {
                !input.remoteAuthorized && localEligible -> input.choose(RunPlacement.Local, PlacementReasonCode.REMOTE_NOT_AUTHORIZED)
                input.requirements.requiresVision && localEligible && input.local.supportsVision ->
                    input.choose(RunPlacement.Local, PlacementReasonCode.AUTO_IMAGE_LOCAL)
                input.resourceBand == StableResourceBand.Hot && remoteEligible ->
                    input.choose(RunPlacement.Remote, PlacementReasonCode.AUTO_RESOURCE_REMOTE)
                input.complexity == RequestComplexity.Complex && remoteEligible ->
                    input.choose(RunPlacement.Remote, PlacementReasonCode.AUTO_COMPLEX_REMOTE)
                localEligible -> input.choose(RunPlacement.Local, PlacementReasonCode.AUTO_SIMPLE_LOCAL)
                remoteEligible -> input.choose(RunPlacement.Remote, PlacementReasonCode.LOCAL_MODEL_UNAVAILABLE)
                else -> input.block(PlacementReasonCode.NO_ELIGIBLE_TARGET)
            }
        }
    }
}
```

`RequestComplexityAggregator` 固定 70% 本地 context、Medium/High reasoning、多步计划/工具循环、显式请求输出预算至少 4,096 tokens 四类硬信号；当前 2,048 默认输出预留不触发复杂度，调用方没有显式预算时传 Unknown。缺必要 token/profile 信息返回 Unknown。`StableResourceSnapshotAggregator` 只保留最近 10 秒最多 3 个带 elapsed time 的样本，样本少于 2 个返回 Unknown；至少 2 个样本时需要 2 个 Hot 才为 Hot、需要 2 个 Warm-or-Hot 才为 Warm；Hot 降档保持 15 秒。`SystemResourceMonitor` 保留完整 Android thermal 语义：Unknown/Normal/Warm/Severe/Critical/Emergency/Shutdown；Severe/Critical 样本进入 Hot，但单样本仍不淘汰本地，Emergency/Shutdown 作为独立硬阻断输入交给 placement policy。

- [ ] **Step 4: 验证测试通过**

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.solin.orchestration.ModelPlacementPolicyTest' \
  --tests 'com.bytedance.zgx.solin.orchestration.RequestComplexityAggregatorTest' \
  --tests 'com.bytedance.zgx.solin.resource.StableResourceSnapshotAggregatorTest' \
  --tests 'com.bytedance.zgx.solin.resource.SystemResourceMonitorTest'
```

Expected: PASS；0/1/2/3 样本、CPU null、2/3 Hot、cooldown、69%/70%、能力和完整决策优先级均稳定。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/bytedance/zgx/solin/orchestration/ModelPlacementPolicy.kt \
  app/src/main/java/com/bytedance/zgx/solin/orchestration/RequestComplexityAggregator.kt \
  app/src/main/java/com/bytedance/zgx/solin/resource/StableResourceSnapshotAggregator.kt \
  app/src/main/java/com/bytedance/zgx/solin/resource/SystemResourceMonitor.kt \
  app/src/test/java/com/bytedance/zgx/solin/orchestration/ModelPlacementPolicyTest.kt \
  app/src/test/java/com/bytedance/zgx/solin/orchestration/RequestComplexityAggregatorTest.kt \
  app/src/test/java/com/bytedance/zgx/solin/resource/StableResourceSnapshotAggregatorTest.kt \
  app/src/test/java/com/bytedance/zgx/solin/resource/SystemResourceMonitorTest.kt
git commit -m "feat(inference): add deterministic placement policy"
```

### Task 3: 独立 PromptPrivacyPlan 与全输入链传播

**Files:**
- Create: `app/src/main/java/com/bytedance/zgx/solin/orchestration/PromptPrivacyPlan.kt`
- Modify: `app/src/main/java/com/bytedance/zgx/solin/presentation/ChatSharedInputSupport.kt:65-150,295-335`
- Modify: `app/src/main/java/com/bytedance/zgx/solin/orchestration/AgentLoopRouting.kt:45-155`
- Modify: `app/src/main/java/com/bytedance/zgx/solin/orchestration/AgentObservationReplanner.kt:560-620`
- Test: `app/src/test/java/com/bytedance/zgx/solin/orchestration/PromptPrivacyPlanTest.kt`
- Test: `app/src/test/java/com/bytedance/zgx/solin/multimodal/SharedInputTest.kt`
- Test: `app/src/test/java/com/bytedance/zgx/solin/orchestration/SteerQueueTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
@Test fun requiredLocalOnlySegmentBlocksRemoteForEverySource() {
    PromptSegmentSource.values().forEach { source ->
        val plan = PromptPrivacyPlanner.build(
            listOf(PromptPrivacySegment(source, privacy = MessagePrivacy.LocalOnly, required = true)),
        )
        assertTrue(plan.requiresLocalModel)
        assertEquals(MessagePrivacy.LocalOnly, plan.aggregatePrivacy)
    }
}

@Test fun unknownMetadataFailsClosedAndOptionalHistoryMayBeFiltered() {
    val plan = PromptPrivacyPlanner.build(
        listOf(
            PromptPrivacySegment(PromptSegmentSource.CurrentInput, null, required = true),
            PromptPrivacySegment(PromptSegmentSource.History, MessagePrivacy.LocalOnly, required = false),
        ),
    )
    assertTrue(plan.requiresLocalModel)
    assertEquals(1, plan.optionalLocalOnlyFilteredCount)
}

@Test fun preferenceNeverChangesPrivacyPlan() {
    val segments = listOf(PromptPrivacySegment(CurrentInput, RemoteEligible, required = true))
    assertEquals(
        PromptPrivacyPlanner.build(segments),
        PromptPrivacyPlanner.build(segments),
    )
}
```

- [ ] **Step 2: 验证测试失败**

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.solin.orchestration.PromptPrivacyPlanTest' \
  --tests 'com.bytedance.zgx.solin.multimodal.SharedInputTest' \
  --tests 'com.bytedance.zgx.solin.orchestration.SteerQueueTest'
```

Expected: FAIL，privacy plan 与 segment source 类型尚不存在，shared input 仍读取 `inferenceMode`。

- [ ] **Step 3: 最小实现**

```kotlin
enum class PromptSegmentSource {
    CurrentInput, History, Image, File, ScreenOcr, DeviceContext,
    Memory, Evidence, ToolObservation, Steer, QueuedInput,
}

data class PromptPrivacySegment(
    val source: PromptSegmentSource,
    val privacy: MessagePrivacy?,
    val required: Boolean,
)

data class PromptPrivacyPlan(
    val aggregatePrivacy: MessagePrivacy,
    val requiresLocalModel: Boolean,
    val requiredLocalOnlyCount: Int,
    val optionalLocalOnlyFilteredCount: Int,
    val sourceCounts: Map<PromptSegmentSource, Int>,
)

object PromptPrivacyPlanner {
    fun build(segments: List<PromptPrivacySegment>): PromptPrivacyPlan {
        val normalized = segments.map { it.copy(privacy = it.privacy ?: MessagePrivacy.LocalOnly) }
        val requiredLocal = normalized.count { it.required && it.privacy == MessagePrivacy.LocalOnly }
        return PromptPrivacyPlan(
            aggregatePrivacy = if (requiredLocal > 0) MessagePrivacy.LocalOnly else MessagePrivacy.RemoteEligible,
            requiresLocalModel = requiredLocal > 0,
            requiredLocalOnlyCount = requiredLocal,
            optionalLocalOnlyFilteredCount = normalized.count { !it.required && it.privacy == MessagePrivacy.LocalOnly },
            sourceCounts = normalized.groupingBy { it.source }.eachCount(),
        )
    }
}
```

普通键入文本在来源元数据完整时作为 `RemoteEligible` segment；图片、文件、OCR、device context、memory/evidence 和工具结果沿用其既有 privacy。shared input 在用户点击发送前只在本地暂存，不再因为 preference=Remote 就提前读取/丢弃；route 后把 memory hits/device context 追加到同一个 plan 做发送前复核。Agent steer 与 queued input 保留 `ChatMessage.privacy`，合并 prompt 前再次聚合；未知 privacy 按 LocalOnly。

- [ ] **Step 4: 验证测试通过**

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.solin.orchestration.PromptPrivacyPlanTest' \
  --tests 'com.bytedance.zgx.solin.multimodal.SharedInputTest' \
  --tests 'com.bytedance.zgx.solin.orchestration.SteerQueueTest'
```

Expected: PASS；11 类 segment 的 required LocalOnly/unknown 都 fail closed，可选 LocalOnly 历史只计数过滤，preference 不参与 privacy 计算。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/bytedance/zgx/solin/orchestration/PromptPrivacyPlan.kt \
  app/src/main/java/com/bytedance/zgx/solin/presentation/ChatSharedInputSupport.kt \
  app/src/main/java/com/bytedance/zgx/solin/orchestration/AgentLoopRouting.kt \
  app/src/main/java/com/bytedance/zgx/solin/orchestration/AgentObservationReplanner.kt \
  app/src/test/java/com/bytedance/zgx/solin/orchestration/PromptPrivacyPlanTest.kt \
  app/src/test/java/com/bytedance/zgx/solin/multimodal/SharedInputTest.kt \
  app/src/test/java/com/bytedance/zgx/solin/orchestration/SteerQueueTest.kt
git commit -m "feat(privacy): aggregate prompt privacy before placement"
```

### Task 4: 持久化 run binding、CAS dispatcher 与 trace 证据

**Files:**
- Create: `app/src/main/java/com/bytedance/zgx/solin/orchestration/RunPlacementBinding.kt`
- Create: `app/src/main/java/com/bytedance/zgx/solin/orchestration/RunPlacementBindingStore.kt`
- Create: `app/src/main/java/com/bytedance/zgx/solin/orchestration/ModelRuntimeDispatcher.kt`
- Modify: `app/src/main/java/com/bytedance/zgx/solin/data/SolinDatabase.kt:158-176,573-746,748-1135`
- Modify: `app/src/main/java/com/bytedance/zgx/solin/orchestration/AgentModels.kt:62-181`
- Modify: `app/src/main/java/com/bytedance/zgx/solin/orchestration/AgentTraceStore.kt:84-111,301-445,1400-1545`
- Modify: `app/src/main/java/com/bytedance/zgx/solin/orchestration/AssistantOrchestrator.kt:53-145,178-390`
- Test: `app/src/test/java/com/bytedance/zgx/solin/orchestration/RunPlacementBindingStoreTest.kt`
- Test: `app/src/test/java/com/bytedance/zgx/solin/orchestration/ModelRuntimeDispatcherTest.kt`
- Test: `app/src/test/java/com/bytedance/zgx/solin/orchestration/RunDataReceiptTraceTest.kt`
- Test: `app/src/androidTest/java/com/bytedance/zgx/solin/data/SolinDatabaseMigrationTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
@Test fun concurrentOppositeClaimsPersistExactlyOnePlacement() = runTest {
    val results = listOf(
        async(Dispatchers.Default) { store.bindAndReserve(binding(Local)) },
        async(Dispatchers.Default) { store.bindAndReserve(binding(Remote)) },
    ).awaitAll()
    assertEquals(1, results.count { it is BindPlacementResult.Bound })
    assertEquals(1, trace.steps(runId).count { it is AgentStep.PlacementSelected })
}

@Test fun dispatcherRejectsCrossPlacementAndWritesInvocationBeforeCall() = runTest {
    store.bindAndReserve(binding(Remote))
    dispatcher.dispatch(runId, Remote) {
        assertTrue(trace.steps(runId).last() is AgentStep.ModelRuntimeInvocationStarted)
        remoteCalls++
    }
    assertFailsWith<PlacementDispatchException> {
        dispatcher.dispatch(runId, Local) { localCalls++ }
    }
    assertEquals(0, localCalls)
    assertEquals(1, remoteCalls)
}

@Test fun migration17To18CreatesEmptyBindingTableWithoutGuessingOldRuns() {
    migrate17To18WithInFlightRun()
    assertNull(database.agentTraceDao().placementBinding("legacy-run"))
}
```

- [ ] **Step 2: 验证测试失败**

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.solin.orchestration.RunPlacementBindingStoreTest' \
  --tests 'com.bytedance.zgx.solin.orchestration.ModelRuntimeDispatcherTest' \
  --tests 'com.bytedance.zgx.solin.orchestration.RunDataReceiptTraceTest'
```

Expected: FAIL，binding entity/store、dispatcher 和两个 trace step 尚不存在。

- [ ] **Step 3: 最小实现**

```kotlin
@Entity(
    tableName = "agent_run_placement_bindings",
    foreignKeys = [ForeignKey(
        entity = AgentRunEntity::class,
        parentColumns = ["id"], childColumns = ["runId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class AgentRunPlacementBindingEntity(
    @PrimaryKey val runId: String,
    val schemaVersion: Int,
    val policyVersion: Int,
    val preference: String,
    val placement: String,
    val primaryReason: String,
    val complexity: String,
    val localState: String,
    val remoteState: String,
    val remoteProfileRevision: String?,
    val bootCount: Long,
    val boundAtElapsedRealtimeMillis: Long,
    val dispatchState: String,
    val attempt: Int,
)

data class RunPlacementBinding(
    val schemaVersion: Int = 1,
    val runId: String,
    val decision: PlacementDecision.Chosen,
    val remoteProfileRevision: String?,
    val bootCount: Long,
    val boundAtElapsedRealtimeMillis: Long,
    val dispatchState: ModelDispatchState = ModelDispatchState.Pending,
    val attempt: Int = 0,
)

enum class ModelDispatchState { Pending, Started, Idle, Terminal }

sealed interface BindPlacementResult {
    data class Bound(val binding: RunPlacementBinding) : BindPlacementResult
    data class Rejected(val existing: RunPlacementBinding?) : BindPlacementResult
}

sealed interface StartInvocationResult {
    data class Started(val binding: RunPlacementBinding) : StartInvocationResult
    data class Rejected(val binding: RunPlacementBinding?) : StartInvocationResult
}

class PlacementDispatchException(runId: String) :
    IllegalStateException("Serving placement unavailable for run $runId")

interface RunPlacementBindingStore {
    fun bindAndReserve(binding: RunPlacementBinding): BindPlacementResult
    fun startInvocation(runId: String, placement: RunPlacement): StartInvocationResult
    fun finishInvocation(runId: String, placement: RunPlacement, attempt: Int): Boolean
    fun binding(runId: String): RunPlacementBinding?
}

class ModelRuntimeDispatcher(private val store: RunPlacementBindingStore) {
    suspend fun <T> dispatch(
        runId: String,
        placement: RunPlacement,
        block: suspend (attempt: Int) -> T,
    ): T {
        val claim = store.startInvocation(runId, placement)
        if (claim !is StartInvocationResult.Started) throw PlacementDispatchException(runId)
        return try { block(claim.binding.attempt) }
        finally { store.finishInvocation(runId, placement, claim.binding.attempt) }
    }

    fun stop(runId: String, stopLocal: () -> Unit, stopRemote: () -> Unit) =
        when (store.binding(runId)?.decision?.placement) {
            RunPlacement.Local -> stopLocal()
            RunPlacement.Remote -> stopRemote()
            null -> throw PlacementDispatchException(runId)
        }
}
```

DAO 用 `@Insert(IGNORE)` + `@Transaction` 把 binding 与 `PlacementSelected` 同事务写入；`startInvocation` 用带 `runId + placement + dispatchState` 条件的 UPDATE 将 Pending/Idle→Started、attempt+1，并在同事务追加 `ModelRuntimeInvocationStarted`。相反 placement、重复并发 Started、trace insert、FK 或 CAS 失败均返回失败且不执行 block。`finishInvocation` 只把匹配 attempt 的 Started→Idle；terminal run 变为 Terminal。Room 升到 18，`MIGRATION_17_18` 只建新表和 index，不回填旧 run。恢复要求 schema=1、同 boot、≤30 分钟、runId/revision 匹配；未知枚举或重复绑定返回 `PLACEMENT_NOT_RESTORABLE`。

- [ ] **Step 4: 验证测试通过**

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.solin.orchestration.RunPlacementBindingStoreTest' \
  --tests 'com.bytedance.zgx.solin.orchestration.ModelRuntimeDispatcherTest' \
  --tests 'com.bytedance.zgx.solin.orchestration.RunDataReceiptTraceTest'
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.bytedance.zgx.solin.data.SolinDatabaseMigrationTest
```

Expected: PASS；并发相反 claim 恰好一个成功，同目标 attempt 单调递增，invocation 先于 runtime，17→18 不猜旧 placement，session 删除级联清理 binding。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/bytedance/zgx/solin/orchestration/RunPlacementBinding.kt \
  app/src/main/java/com/bytedance/zgx/solin/orchestration/RunPlacementBindingStore.kt \
  app/src/main/java/com/bytedance/zgx/solin/orchestration/ModelRuntimeDispatcher.kt \
  app/src/main/java/com/bytedance/zgx/solin/data/SolinDatabase.kt \
  app/src/main/java/com/bytedance/zgx/solin/orchestration/AgentModels.kt \
  app/src/main/java/com/bytedance/zgx/solin/orchestration/AgentTraceStore.kt \
  app/src/main/java/com/bytedance/zgx/solin/orchestration/AssistantOrchestrator.kt \
  app/src/test/java/com/bytedance/zgx/solin/orchestration/RunPlacementBindingStoreTest.kt \
  app/src/test/java/com/bytedance/zgx/solin/orchestration/ModelRuntimeDispatcherTest.kt \
  app/src/test/java/com/bytedance/zgx/solin/orchestration/RunDataReceiptTraceTest.kt \
  app/src/androidTest/java/com/bytedance/zgx/solin/data/SolinDatabaseMigrationTest.kt
git commit -m "feat(inference): bind serving placement atomically"
```

### Task 5: rollout、Auto opt-in、配置探测与 app-scoped 资源窗口

**Files:**
- Create: `app/src/main/java/com/bytedance/zgx/solin/AdaptiveInferenceRollout.kt`
- Modify: `app/build.gradle.kts:195-260`
- Modify: `app/src/main/java/com/bytedance/zgx/solin/SolinAppContainer.kt:140-178,316-450`
- Modify: `app/src/main/java/com/bytedance/zgx/solin/MainActivity.kt:79-81,243-377`
- Modify: `app/src/main/java/com/bytedance/zgx/solin/ChatModels.kt:251-351,393-456`
- Modify: `app/src/main/java/com/bytedance/zgx/solin/SolinViewModel.kt:199-365,500-565,823-933,1077-1127`
- Modify: `app/src/main/java/com/bytedance/zgx/solin/presentation/ModelLoadController.kt:438-527,1180-1247`
- Modify: `app/src/main/java/com/bytedance/zgx/solin/ui/components/RemoteModeDisclosureSheet.kt:19-87`
- Test: `app/src/test/java/com/bytedance/zgx/solin/AdaptiveInferenceRolloutTest.kt`
- Test: `app/src/test/java/com/bytedance/zgx/solin/SolinViewModelTest.kt`
- Test: `app/src/test/java/com/bytedance/zgx/solin/data/RemoteModelRepositoryTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
@Test fun unknownOrOffRolloutDowngradesPersistedAutoToLocal() {
    assertEquals(InferenceMode.Local, Off.sanitizePreference(InferenceMode.Auto))
    assertEquals(InferenceMode.Local, AdaptiveInferenceRolloutStage.parse("future").sanitizePreference(Auto))
}

@Test fun selectingAutoDoesNotPersistUntilDisclosureConfirmation() {
    viewModel.selectInferenceMode(InferenceMode.Auto)
    assertEquals(InferenceMode.Local, remoteStore.loadMode())
    val pending = viewModel.uiState.value.pendingRemoteModeDisclosure!!
    assertEquals(InferenceMode.Auto, pending.requestedMode)
    viewModel.confirmRemoteModeDisclosure()
    assertEquals(InferenceMode.Auto, remoteStore.loadMode())
}

@Test fun changingRemoteProfileInvalidatesPendingAutoDisclosure() {
    viewModel.selectInferenceMode(InferenceMode.Auto)
    viewModel.updateRemoteModelConfig(config.copy(modelName = "other-model"))
    viewModel.confirmRemoteModeDisclosure()
    assertEquals(InferenceMode.Local, remoteStore.loadMode())
}
```

- [ ] **Step 2: 验证测试失败**

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.solin.AdaptiveInferenceRolloutTest' \
  --tests 'com.bytedance.zgx.solin.SolinViewModelTest' \
  --tests 'com.bytedance.zgx.solin.data.RemoteModelRepositoryTest'
```

Expected: FAIL，rollout stage、pending requested mode/revision 和 confirm API 尚不存在。

- [ ] **Step 3: 最小实现**

```kotlin
enum class AdaptiveInferenceRolloutStage {
    Off, Shadow, OptIn, Visible;

    val autoSelectable: Boolean get() = this == OptIn || this == Visible
    fun sanitizePreference(mode: InferenceMode): InferenceMode =
        if (mode == InferenceMode.Auto && !autoSelectable) InferenceMode.Local else mode

    companion object {
        fun parse(raw: String): AdaptiveInferenceRolloutStage = when (raw.lowercase()) {
            "shadow" -> Shadow
            "opt_in" -> OptIn
            "visible" -> Visible
            else -> Off
        }
    }
}

data class PendingRemoteModeDisclosure(
    val requestedMode: InferenceMode,
    val remoteProfileRevision: String,
    val remoteModelName: String,
    val supportsVisionInput: Boolean,
    val supportsToolCalls: Boolean,
    val contextWindowTokens: Int?,
    val isConfigured: Boolean,
)
```

Gradle 的 `defaultConfig`/release-like variant 写 `ADAPTIVE_INFERENCE_ROLLOUT_STAGE="off"`，debug 写 `"opt_in"`。composition root 解析一次并注入，policy 不读取 BuildConfig。`selectInferenceMode(Auto)` 只创建 pending disclosure；确认时 revision 仍一致才保存 Auto，取消/变更配置保持原 preference。开启 Auto、保存物质配置、前台且 snapshot 过期或远程失败时只异步 probe，不在发送路径等待。`SystemResourceMonitor` 移到 `SolinAppContainer`，MainActivity 的原 1.5 秒 UI sampler 与 placement 共用同一 aggregator。

- [ ] **Step 4: 验证测试通过**

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.solin.AdaptiveInferenceRolloutTest' \
  --tests 'com.bytedance.zgx.solin.SolinViewModelTest' \
  --tests 'com.bytedance.zgx.solin.data.RemoteModelRepositoryTest'
./gradlew :app:compileDebugKotlin
```

Expected: PASS；Auto 为显式 opt-in，revision 变化使旧确认失效，off/未知 rollout fail-safe 到 Local，debug 编译成功且没有第二个资源采样循环。

- [ ] **Step 5: 提交**

```bash
git add app/build.gradle.kts \
  app/src/main/java/com/bytedance/zgx/solin/AdaptiveInferenceRollout.kt \
  app/src/main/java/com/bytedance/zgx/solin/SolinAppContainer.kt \
  app/src/main/java/com/bytedance/zgx/solin/MainActivity.kt \
  app/src/main/java/com/bytedance/zgx/solin/ChatModels.kt \
  app/src/main/java/com/bytedance/zgx/solin/SolinViewModel.kt \
  app/src/main/java/com/bytedance/zgx/solin/presentation/ModelLoadController.kt \
  app/src/main/java/com/bytedance/zgx/solin/ui/components/RemoteModeDisclosureSheet.kt \
  app/src/test/java/com/bytedance/zgx/solin/AdaptiveInferenceRolloutTest.kt \
  app/src/test/java/com/bytedance/zgx/solin/SolinViewModelTest.kt \
  app/src/test/java/com/bytedance/zgx/solin/data/RemoteModelRepositoryTest.kt
git commit -m "feat(inference): gate and authorize auto placement"
```

### Task 6: 初始 Chat run 的 prepare→bind→disclose→dispatch

**Files:**
- Create: `app/src/main/java/com/bytedance/zgx/solin/presentation/PreparedChatRunCoordinator.kt`
- Modify: `app/src/main/java/com/bytedance/zgx/solin/presentation/ChatController.kt:120-255,331-1238`
- Modify: `app/src/main/java/com/bytedance/zgx/solin/presentation/ChatRemoteSendSupport.kt:33-152,350-450`
- Modify: `app/src/main/java/com/bytedance/zgx/solin/presentation/ChatControllerHelpers.kt:145-255`
- Modify: `app/src/main/java/com/bytedance/zgx/solin/orchestration/AgentRunOptions.kt:3-18`
- Test: `app/src/test/java/com/bytedance/zgx/solin/presentation/PreparedChatRunCoordinatorTest.kt`
- Test: `app/src/test/java/com/bytedance/zgx/solin/SolinViewModelTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
@Test fun autoRemoteDisclosureConfirmationDoesNotCreateSecondRun() = runTest {
    viewModel.sendMessage("结构化分析这个问题")
    val pendingRunId = viewModel.uiState.value.pendingRemoteSendDisclosure!!.runId
    assertEquals(1, router.routeCalls)
    viewModel.confirmRemoteSendDisclosure()
    assertEquals(1, router.routeCalls)
    assertEquals(pendingRunId, remoteRuntime.lastRunId)
}

@Test fun routeBindingOrTraceFailureInvokesNeitherRuntime() = runTest {
    bindingStore.failNextBind = true
    viewModel.sendMessage("complex")
    assertEquals(0, localRuntime.calls)
    assertEquals(0, remoteRuntime.calls)
}

@Test fun localOnlyAndCapabilityMismatchBlockBeforeServingCall() = runTest {
    viewModel.sendMessage("private", MessagePrivacy.LocalOnly)
    assertEquals(0, remoteRuntime.calls)
    remoteConfig = remoteConfig.copy(supportsToolCalls = false, contextWindowTokens = null)
    viewModel.sendMessage("needs tools")
    assertEquals(0, remoteRuntime.calls)
}
```

- [ ] **Step 2: 验证测试失败**

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.solin.presentation.PreparedChatRunCoordinatorTest' \
  --tests 'com.bytedance.zgx.solin.SolinViewModelTest'
```

Expected: FAIL，disclosure 确认仍重新调用完整 `sendMessageInternal`，且没有 binding/dispatcher gate。

- [ ] **Step 3: 最小实现**

```kotlin
data class PreparedChatRun(
    val runId: String,
    val route: AssistantRoute.Chat,
    val privacyPlan: PromptPrivacyPlan,
    val binding: RunPlacementBinding,
    val prompt: String,
    val history: List<ChatMessage>,
    val images: List<ChatImageAttachment>,
    val remoteConfig: RemoteModelConfig?,
)

internal class PreparedChatRunCoordinator {
    private val prepared = mutableMapOf<String, PreparedChatRun>()
    fun put(run: PreparedChatRun) { check(prepared.putIfAbsent(run.runId, run) == null) }
    fun take(runId: String, currentRemoteRevision: String?): PreparedChatRun? {
        val run = prepared.remove(runId) ?: return null
        return run.takeIf { it.binding.remoteProfileRevision == currentRemoteRevision }
    }
    fun discard(runId: String) { prepared.remove(runId) }
}
```

`ChatController` 的顺序固定为：本地确定性 privacy preflight → placement-neutral rule/skill route → 对 `AssistantRoute.Chat` 补 route memory/device context privacy → 聚合 requirements/complexity/candidates → `ModelPlacementPolicy.decide` → `bindAndReserve` → 由 binding 生成 receipt → Remote 时建立 prepared run 和 disclosure，Local 或已确认 Remote 时经 dispatcher 调用。Action/Rejected/MissingModel 保持本地处理且不创建 serving binding。删除 route 异常 synthetic Chat 和 Auto 的 ephemeral fail-open；binding FK、trace 或 CAS 失败时 terminate run，两个 runtime 调用数均为 0。`PendingRemoteSendDisclosure`/marker 保存 runId 和不透明 revision，正文只在进程内 prepared map；确认调用 `take(runId, revision)`，不再 route。receipt.destination 只通过 `binding.toRunDataDestination()` 生成。

- [ ] **Step 4: 验证测试通过**

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.solin.presentation.PreparedChatRunCoordinatorTest' \
  --tests 'com.bytedance.zgx.solin.SolinViewModelTest'
```

Expected: PASS；Remote disclosure 前后 routeCalls=1，LocalOnly/unknown/capability mismatch/bind failure 为零 serving 调用，手动 Local/Remote 回归通过。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/bytedance/zgx/solin/presentation/PreparedChatRunCoordinator.kt \
  app/src/main/java/com/bytedance/zgx/solin/presentation/ChatController.kt \
  app/src/main/java/com/bytedance/zgx/solin/presentation/ChatRemoteSendSupport.kt \
  app/src/main/java/com/bytedance/zgx/solin/presentation/ChatControllerHelpers.kt \
  app/src/main/java/com/bytedance/zgx/solin/orchestration/AgentRunOptions.kt \
  app/src/test/java/com/bytedance/zgx/solin/presentation/PreparedChatRunCoordinatorTest.kt \
  app/src/test/java/com/bytedance/zgx/solin/SolinViewModelTest.kt
git commit -m "feat(inference): dispatch initial chat by bound placement"
```

### Task 7: 工具续写、同目标重试、停止与受限恢复

**Files:**
- Modify: `app/src/main/java/com/bytedance/zgx/solin/presentation/ChatToolContinuationSupport.kt:72-569`
- Modify: `app/src/main/java/com/bytedance/zgx/solin/presentation/ToolExecutionController.kt:270-330,440-500`
- Modify: `app/src/main/java/com/bytedance/zgx/solin/presentation/ChatGenerationSupport.kt:150-259,283-336`
- Modify: `app/src/main/java/com/bytedance/zgx/solin/presentation/ChatController.kt:1190-1238,1319-1375`
- Modify: `app/src/main/java/com/bytedance/zgx/solin/orchestration/PendingConfirmationSupport.kt:60-135`
- Test: `app/src/test/java/com/bytedance/zgx/solin/SolinViewModelTest.kt`
- Test: `app/src/test/java/com/bytedance/zgx/solin/orchestration/RunPlacementRecoveryTest.kt`
- Test: `app/src/test/java/com/bytedance/zgx/solin/runtime/RemoteChatRuntimeTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
@Test fun preferenceSwitchDuringToolConfirmationDoesNotChangePlacement() = runTest {
    startRemoteRunThatAwaitsToolConfirmation()
    viewModel.selectInferenceMode(InferenceMode.Local)
    viewModel.confirmAgentAction()
    assertEquals(0, localRuntime.calls)
    assertEquals(2, remoteRuntime.calls) // initial + continuation
}

@Test fun remoteRunWithLocalOnlyObservationStopsWithoutFallback() = runTest {
    val runId = startRemoteRun()
    completeTool(runId, privacy = MessagePrivacy.LocalOnly)
    assertEquals(1, remoteRuntime.calls)
    assertEquals(0, localRuntime.calls)
    assertEquals(AgentRunState.Failed, trace.run(runId)?.state)
}

@Test fun stopUsesBoundPlacementInsteadOfCurrentPreference() = runTest {
    val runId = startRemoteStreamingRun()
    viewModel.selectInferenceMode(InferenceMode.Local)
    viewModel.stopGeneration()
    assertEquals(runId, remoteRuntime.stoppedRunId)
    assertFalse(localRuntime.stopCalled)
}

@Test fun contextOverflowRetryKeepsRemoteAndIncrementsAttempt() = runTest {
    remoteRuntime.enqueueContextOverflowThenSuccess()
    viewModel.sendMessage("complex")
    assertEquals(listOf(1, 2), invocationAttempts(runId))
    assertEquals(setOf(RunPlacement.Remote), invocationPlacements(runId))
}
```

- [ ] **Step 2: 验证测试失败**

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.solin.SolinViewModelTest' \
  --tests 'com.bytedance.zgx.solin.orchestration.RunPlacementRecoveryTest' \
  --tests 'com.bytedance.zgx.solin.runtime.RemoteChatRuntimeTest'
```

Expected: FAIL，续写、工具结果保护和 stop 仍读取当前 `inferenceMode`，overflow retry 也没有 invocation attempt 证据。

- [ ] **Step 3: 最小实现**

```kotlin
fun continueAfterToolObservation(runId: String, prompt: String, privacy: MessagePrivacy) {
    val binding = bindingStore.binding(runId) ?: return failClosed(runId, PLACEMENT_NOT_RESTORABLE)
    if (binding.decision.placement == RunPlacement.Remote && privacy != MessagePrivacy.RemoteEligible) {
        failClosed(runId, PLACEMENT_LOCAL_CONTINUATION_REQUIRED)
        return
    }
    dispatcher.dispatch(runId, binding.decision.placement) {
        when (binding.decision.placement) {
            RunPlacement.Local -> collectLocalContinuation(prompt)
            RunPlacement.Remote -> collectRemoteContinuation(prompt, binding.remoteProfileRevision)
        }
    }
}

fun stopGeneration() {
    val runId = activeGenerationRunId ?: return
    dispatcher.stop(runId, stopLocal = runtime::stop, stopRemote = remoteRuntime::stop)
    assistantOrchestrator.cancelRun(runId, USER_STOPPED_AGENT_RUN_REASON)
    generationJob?.cancel()
    generationSupport.finishStoppedGeneration(runId)
}
```

初始生成、context overflow retry、citation retry、工具续写和递归续写每次都通过同一个 dispatcher；retry 先 finish 当前 attempt，再 claim 同 placement 下一 attempt。`ToolExecutionController` 用 binding 判断 Remote + LocalOnly，不再读取 preference。恢复只允许同 boot、30 分钟内、schema/runId/revision 完整的 AwaitingUserConfirmation/continuation；缺失或未知 binding 记录稳定失败原因且不调用模型。网络失败标记当前 run Failed，不调用另一 runtime；用户再次发送走 Task 6 全流程并创建新 run。

- [ ] **Step 4: 验证测试通过**

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.solin.SolinViewModelTest' \
  --tests 'com.bytedance.zgx.solin.orchestration.RunPlacementRecoveryTest' \
  --tests 'com.bytedance.zgx.solin.runtime.RemoteChatRuntimeTest'
```

Expected: PASS；切换 preference 不改变续写/停止目标，新 LocalOnly observation 零后续模型调用，恢复缺 binding fail closed，所有 retry 只有一个 placement。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/bytedance/zgx/solin/presentation/ChatToolContinuationSupport.kt \
  app/src/main/java/com/bytedance/zgx/solin/presentation/ToolExecutionController.kt \
  app/src/main/java/com/bytedance/zgx/solin/presentation/ChatGenerationSupport.kt \
  app/src/main/java/com/bytedance/zgx/solin/presentation/ChatController.kt \
  app/src/main/java/com/bytedance/zgx/solin/orchestration/PendingConfirmationSupport.kt \
  app/src/test/java/com/bytedance/zgx/solin/SolinViewModelTest.kt \
  app/src/test/java/com/bytedance/zgx/solin/orchestration/RunPlacementRecoveryTest.kt \
  app/src/test/java/com/bytedance/zgx/solin/runtime/RemoteChatRuntimeTest.kt
git commit -m "feat(inference): keep continuations on bound placement"
```

### Task 8: 三态配置 UI、实际目标解释与 trace/receipt 对账

**Files:**
- Modify: `app/src/main/java/com/bytedance/zgx/solin/ChatModels.kt:167-185,251-340,393-456`
- Modify: `app/src/main/java/com/bytedance/zgx/solin/ui/components/ModelManagerSheet.kt:237-488`
- Modify: `app/src/main/java/com/bytedance/zgx/solin/ui/components/ChatTopBar.kt:387-414`
- Modify: `app/src/main/java/com/bytedance/zgx/solin/ui/components/ChatEmptyState.kt:38-65`
- Modify: `app/src/main/java/com/bytedance/zgx/solin/ui/components/FirstRunSetupPanel.kt:130-175`
- Modify: `app/src/main/java/com/bytedance/zgx/solin/ui/SolinScreen.kt:148-190,567-588,912-1046`
- Modify: `app/src/main/java/com/bytedance/zgx/solin/presentation/AuditUiController.kt:75-105`
- Modify: `app/src/main/java/com/bytedance/zgx/solin/eval/AgentBehaviorEvalModels.kt:500-780`
- Test: `app/src/test/java/com/bytedance/zgx/solin/ui/SolinScreenDisplayTest.kt`
- Test: `app/src/test/java/com/bytedance/zgx/solin/orchestration/RunDataReceiptTraceTest.kt`
- Test: `app/src/test/java/com/bytedance/zgx/solin/eval/AiBehaviorActualTraceGeneratorTest.kt`
- Test: `app/src/androidTest/java/com/bytedance/zgx/solin/MainActivityAdaptiveUiTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
@Test fun autoUiSeparatesPreferenceFromActualPlacement() {
    val local = ChatUiState(
        inferenceMode = InferenceMode.Auto,
        activeRunPlacement = RunPlacement.Local,
        activeRunPlacementReason = PlacementReasonCode.AUTO_SIMPLE_LOCAL,
    )
    assertEquals("偏好：自动", inferencePreferenceDisplayText(local))
    assertEquals("本次使用本地模型：任务较轻，手机状态正常。", activePlacementDisplayText(local))

    val remote = local.copy(
        activeRunPlacement = RunPlacement.Remote,
        activeRunPlacementReason = PlacementReasonCode.AUTO_COMPLEX_REMOTE,
    )
    assertEquals("本次使用远程模型：任务较复杂，且远程连接已验证。", activePlacementDisplayText(remote))
}

@Test fun traceReceiptAndInvocationUseOneDestinationAndContainNoSecrets() {
    val json = completeAutoRemoteRunAndReadTraceJson()
    assertEquals("Remote", json.placementSelected())
    assertEquals("Remote", json.invocationStarted())
    assertEquals("Remote", json.receiptDestination())
    listOf("prompt-secret", "api.example.com", "127.0.0.1", "sk-secret", "toolArgumentSecret")
        .forEach { assertFalse(json.contains(it)) }
}
```

- [ ] **Step 2: 验证测试失败**

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.solin.ui.SolinScreenDisplayTest' \
  --tests 'com.bytedance.zgx.solin.orchestration.RunDataReceiptTraceTest' \
  --tests 'com.bytedance.zgx.solin.eval.AiBehaviorActualTraceGeneratorTest'
```

Expected: FAIL，UI 仍从 preference 猜实际模型，远程页只有二态开关，行为 trace 没有 actual placement/invocation/reason。

- [ ] **Step 3: 最小实现**

```diff
 data class ChatUiState(
+    val activeRunPlacement: RunPlacement? = null,
+    val activeRunPlacementReason: PlacementReasonCode? = null,
+    val activePlacementPolicyVersion: Int? = null,
     val inferenceMode: InferenceMode = InferenceMode.Local,
 )
```

```kotlin
internal fun activePlacementDisplayText(state: ChatUiState): String? =
    when (state.activeRunPlacementReason) {
        PlacementReasonCode.PRIVACY_REQUIRES_LOCAL -> "本次使用本地模型：内容仅限本地处理。"
        PlacementReasonCode.AUTO_SIMPLE_LOCAL -> "本次使用本地模型：任务较轻，手机状态正常。"
        PlacementReasonCode.AUTO_COMPLEX_REMOTE -> "本次使用远程模型：任务较复杂，且远程连接已验证。"
        PlacementReasonCode.AUTO_RESOURCE_REMOTE -> "本次使用远程模型：手机资源压力较高，且远程连接已验证。"
        PlacementReasonCode.NO_ELIGIBLE_TARGET -> "无法执行：没有同时满足隐私、能力和可用性要求的模型。"
        else -> state.activeRunPlacement?.let { "本次使用${if (it == RunPlacement.Local) "本地" else "远程"}模型。" }
    }
```

模型管理页用三个 chip 表示 preference，Auto 仅在 rollout.autoSelectable 时显示；删除远程页重复的二态 mode switch，保留 endpoint/model/API key，并增加 `supportsToolCalls` switch 与正整数 `contextWindowTokens` 输入。Top bar、empty state、first-run 和 attachment 提示分别读取 preference 与 actual placement；界面不显示内部阈值、endpoint/IP、revision。`PlacementSelected`/`ModelRuntimeInvocationStarted` JSON 只含 version、preference、placement、reason、complexity、candidate/resource band、opaque revision、attempt。行为评测的 actual target 取 invocation step；receipt 继续展示聚合计数并从 binding 取 destination。

- [ ] **Step 4: 验证测试通过**

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.solin.ui.SolinScreenDisplayTest' \
  --tests 'com.bytedance.zgx.solin.orchestration.RunDataReceiptTraceTest' \
  --tests 'com.bytedance.zgx.solin.eval.AiBehaviorActualTraceGeneratorTest'
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.bytedance.zgx.solin.MainActivityAdaptiveUiTest
```

Expected: PASS；三项 preference 可选，Auto→Local/Remote/Blocked 文案正确，placement=invocation=receipt，敏感字符串不进入 trace/UI。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/bytedance/zgx/solin/ChatModels.kt \
  app/src/main/java/com/bytedance/zgx/solin/ui/components/ModelManagerSheet.kt \
  app/src/main/java/com/bytedance/zgx/solin/ui/components/ChatTopBar.kt \
  app/src/main/java/com/bytedance/zgx/solin/ui/components/ChatEmptyState.kt \
  app/src/main/java/com/bytedance/zgx/solin/ui/components/FirstRunSetupPanel.kt \
  app/src/main/java/com/bytedance/zgx/solin/ui/SolinScreen.kt \
  app/src/main/java/com/bytedance/zgx/solin/presentation/AuditUiController.kt \
  app/src/main/java/com/bytedance/zgx/solin/eval/AgentBehaviorEvalModels.kt \
  app/src/test/java/com/bytedance/zgx/solin/ui/SolinScreenDisplayTest.kt \
  app/src/test/java/com/bytedance/zgx/solin/orchestration/RunDataReceiptTraceTest.kt \
  app/src/test/java/com/bytedance/zgx/solin/eval/AiBehaviorActualTraceGeneratorTest.kt \
  app/src/androidTest/java/com/bytedance/zgx/solin/MainActivityAdaptiveUiTest.kt
git commit -m "feat(inference): explain adaptive placement in ui and trace"
```

### Task 9: shadow/opt-in 验证、当前事实文档与发布回滚材料

**Files:**
- Modify: `app/src/main/java/com/bytedance/zgx/solin/orchestration/AgentModels.kt:156-181`
- Modify: `app/src/main/java/com/bytedance/zgx/solin/orchestration/AgentTraceStore.kt:1400-1545`
- Modify: `app/src/main/java/com/bytedance/zgx/solin/orchestration/AssistantOrchestrator.kt:121-130,361-379`
- Modify: `app/src/main/java/com/bytedance/zgx/solin/capability/CapabilityMatrix.kt:200-255`
- Modify: `app/src/test/resources/ai_behavior_eval/privacy_boundary.jsonl`
- Modify: `app/src/test/resources/ai_behavior_eval/restart_recovery.jsonl`
- Modify: `app/src/test/resources/ai_behavior_eval/runtime_failure.jsonl`
- Modify: `scripts/verify_model_capability_profiles.sh:161-222`
- Modify: `scripts/verify_ai_behavior_eval.sh`
- Modify: `scripts/collect_ai_behavior_actual_trace.sh`
- Modify: `scripts/verify_release_validation_record.sh:795-838,1019-1058,1512-1559`
- Modify: `scripts/verify_release_operations_record.sh:761-790`
- Modify: `scripts/verify_release_gate.sh:225-290,484-493`
- Modify: `scripts/test_validation_scripts.sh`
- Modify: `README.md:39-54,88-98`
- Modify: `README.zh-CN.md`
- Modify: `docs/agent_core_modules.md:110-132,244-260`
- Modify: `docs/privacy_notice.md`
- Modify: `docs/ai_behavior_eval_plan.md`
- Modify: `docs/phone_acceptance.md`
- Modify: `docs/release_checklist.md`
- Modify: `docs/release_readiness.md`
- Modify: `docs/capability_matrix.json:35-47,315-328,402-417`
- Modify: `docs/model_capability_profiles.json:160-206`
- Modify: `docs/release_validation_record.json:69-76,199-206,263-270`
- Modify: `docs/release_operations_record.json:81-107`
- Modify: `docs/store_policy_record.json:5-45`
- Modify: `docs/privacy_review.json:3-7`
- Modify: `docs/validation_report.md`
- Test: `app/src/test/java/com/bytedance/zgx/solin/docs/ModelCapabilityProfilesDocumentationTest.kt`
- Test: `app/src/test/java/com/bytedance/zgx/solin/docs/CapabilityMatrixDocumentationTest.kt`
- Test: `app/src/test/java/com/bytedance/zgx/solin/architecture/PackageBoundaryArchitectureTest.kt`
- Test: `app/src/test/java/com/bytedance/zgx/solin/SolinViewModelTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
@Test fun shadowComputesAutoDecisionWithoutChangingManualDispatch() = runTest {
    val viewModel = createViewModel(rollout = AdaptiveInferenceRolloutStage.Shadow, preference = InferenceMode.Local)
    viewModel.sendMessage("complex")
    assertEquals(1, localRuntime.calls)
    assertEquals(0, remoteRuntime.calls)
    assertEquals(RunPlacement.Remote, trace.singleShadowDecision().placement)
    assertEquals(RunPlacement.Local, trace.singleInvocation().placement)
}

@Test fun placementPolicyHasNoBuildConfigUiOrAndroidDependency() {
    val source = productionSource("orchestration/ModelPlacementPolicy.kt")
    assertFalse(source.contains("BuildConfig"))
    assertFalse(source.contains("android."))
    assertFalse(source.contains("androidx.compose"))
}
```

在 `scripts/test_validation_scripts.sh` 增加负例：fixture 的 expected placement 与 invocation 不一致、receipt 不一致、LocalOnly 远端 invocation、未知 rollout 值、release Auto flag 开启时，各自必须让对应验证脚本非零退出。

- [ ] **Step 2: 验证测试失败**

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.solin.SolinViewModelTest' \
  --tests 'com.bytedance.zgx.solin.docs.ModelCapabilityProfilesDocumentationTest' \
  --tests 'com.bytedance.zgx.solin.docs.CapabilityMatrixDocumentationTest' \
  --tests 'com.bytedance.zgx.solin.architecture.PackageBoundaryArchitectureTest'
bash scripts/test_validation_scripts.sh
```

Expected: FAIL，shadow trace、placement 边界检查、capability 文档字段和验证脚本负例尚未实现。

- [ ] **Step 3: 最小实现**

```kotlin
data class ShadowPlacementTrace(
    val policyVersion: Int,
    val placement: RunPlacement?,
    val primaryReason: PlacementReasonCode,
    val complexity: RequestComplexity,
    val localState: CandidateState,
    val remoteState: CandidateState,
)

if (rolloutStage == AdaptiveInferenceRolloutStage.Shadow && preference != InferenceMode.Auto) {
    val shadow = ModelPlacementPolicy.decide(input.copy(preference = InferenceMode.Auto))
    assistantOrchestrator.recordShadowPlacement(
        runId,
        ShadowPlacementTrace(
            policyVersion = shadow.policyVersion,
            placement = (shadow as? PlacementDecision.Chosen)?.placement,
            primaryReason = shadow.primaryReason,
            complexity = shadow.diagnostics.complexity,
            localState = shadow.diagnostics.localState,
            remoteState = shadow.diagnostics.remoteState,
        ),
    )
}
```

shadow 只记录枚举/计数档位，不改变 binding 或 dispatcher。能力矩阵新增 Auto placement、run binding、actual invocation 和 LocalOnly fail-closed 条目；AI behavior fixture 增加 `expectedPlacement`/`expectedPlacementReason`，collector 从 `ModelRuntimeInvocationStarted` 读取 actual target，并强制与 `PlacementSelected`/receipt 对账。发布脚本在 stage=off 时不要求 Auto 人工证据，在 opt_in/visible 时要求 privacy、双发、revision invalidation 和真机证据；release-like stage 非 off 时无批准记录直接失败。README/隐私通知只把 Auto 标为实验性显式选择，说明可能把 RemoteEligible 内容发送到用户配置服务；商店/正式发布记录保持 pending，直到人工证据真实完成。`docs/validation_report.md` 只追加本次实际执行命令及结果，不伪造未运行的真机或 release gate 通过。

- [ ] **Step 4: 验证测试通过**

Run:

```bash
bash scripts/doctor.sh
bash scripts/verify_local.sh
./gradlew :app:testDebugUnitTest
./gradlew :app:compileDebugKotlin
bash scripts/privacy_scan.sh
bash scripts/test_validation_scripts.sh
bash scripts/verify_model_capability_profiles.sh
bash scripts/verify_capability_matrix.sh
bash scripts/verify_ai_behavior_eval.sh --require-boundary-map
```

Expected: 全部退出 0；debug opt-in 的 deterministic tests 与文档镜像一致，privacy scan 无 prompt/API key/endpoint 泄漏，release/privacy/store 人工记录仍按其 pending 状态诚实展示而不被误报为批准。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/bytedance/zgx/solin/capability/CapabilityMatrix.kt \
  app/src/main/java/com/bytedance/zgx/solin/orchestration/AgentModels.kt \
  app/src/main/java/com/bytedance/zgx/solin/orchestration/AgentTraceStore.kt \
  app/src/main/java/com/bytedance/zgx/solin/orchestration/AssistantOrchestrator.kt \
  app/src/test/resources/ai_behavior_eval/privacy_boundary.jsonl \
  app/src/test/resources/ai_behavior_eval/restart_recovery.jsonl \
  app/src/test/resources/ai_behavior_eval/runtime_failure.jsonl \
  app/src/test/java/com/bytedance/zgx/solin/docs/ModelCapabilityProfilesDocumentationTest.kt \
  app/src/test/java/com/bytedance/zgx/solin/docs/CapabilityMatrixDocumentationTest.kt \
  app/src/test/java/com/bytedance/zgx/solin/architecture/PackageBoundaryArchitectureTest.kt \
  app/src/test/java/com/bytedance/zgx/solin/SolinViewModelTest.kt \
  scripts/verify_model_capability_profiles.sh scripts/verify_ai_behavior_eval.sh \
  scripts/collect_ai_behavior_actual_trace.sh scripts/verify_release_validation_record.sh \
  scripts/verify_release_operations_record.sh scripts/verify_release_gate.sh \
  scripts/test_validation_scripts.sh README.md README.zh-CN.md docs
git commit -m "docs(inference): validate and document adaptive placement"
```

## 规格覆盖核对

| 需求 | 实施任务 | 验收 |
|---|---|---|
| FR-1.1 | Task 1、5 | AC-1 |
| FR-1.2 | Task 1、5、6 | AC-9、AC-16c |
| FR-1.3 | Task 3、6 | AC-2、AC-3 |
| FR-1.4 | Task 2、3、6 | AC-2、AC-3、AC-16a |
| FR-1.5 | Task 1、2、6 | AC-8、AC-9、AC-10 |
| FR-1.6 | Task 2、5 | AC-4、AC-6、AC-7、AC-16b |
| FR-1.7 | Task 2、6 | AC-4、AC-5 |
| FR-1.8 | Task 4、6、7 | AC-11、AC-14、AC-16、AC-16d |
| FR-1.9 | Task 6、7 | AC-13 |
| FR-1.10 | Task 3、7 | AC-12 |
| FR-1.11 | Task 5、8 | AC-11、AC-15 |
| FR-1.12 | Task 4、6、8、9 | AC-15、AC-16、AC-16d |
| FR-1.13 | Task 3、6、7 | AC-12、AC-16a |

## 风险与回滚

- **迁移风险：** 17→18 只建 binding 表，不回填；旧 run 能展示，旧非终态 run 因缺 binding 不恢复 serving call。
- **隐私风险：** privacy plan 任何未知值按 LocalOnly；policy 只能缩小候选，不能把 LocalOnly 改为 RemoteEligible。
- **双发风险：** FK + binding insert + invocation CAS + trace 同事务；任一失败不进入 runtime block。
- **确认竞态：** prepared run 绑定 profile revision；配置修改、进程重启、取消或过期都丢弃正文和授权。
- **资源抖动：** 单样本 Unknown、2-of-3 和 Hot cooldown 固定在 version 1 policy，阈值不进入 UI/ViewModel。
- **兼容风险：** 手动 Remote 仍允许 Unknown/Checking 尝试，但 LocalOnly 和能力硬门禁不放宽；Auto 只接受新鲜 Reachable。
- **发布回滚：** 将 `ADAPTIVE_INFERENCE_ROLLOUT_STAGE` 设为 `off` 后，已存 Auto 在启动时降级 Local；Local/Remote 手工路径保留。LocalOnly 外传、确认绕过、同 run 双发、trace/receipt/runtime 不一致或敏感日志任一出现时立即回滚。
