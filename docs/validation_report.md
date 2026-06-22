# PocketMind 验证报告

## 记录模板

每个新增验证条目固定包含：

- `本轮覆盖项：` 描述本次验证覆盖的行为、文档或脚本契约。
- `验证命令：` 记录实际执行的命令；未执行的设备、模拟器或真机项必须明确说明。
- `结果：` 记录通过、失败或未执行原因，并引用关键 artifact。
- 设备、模拟器和发布门禁失败必须记录机器可读 `failedTarget` / `reason`，以及关键
  evidence path；不能只写“见 artifact”。
- 自动回归与必须手工验收的结论必须分开记录；语音输入、Android 系统文档选择器和
  MediaProjection 前台同意不能因为脚本、mock intent、直接 reader/ViewModel 调用或
  UI 文案存在而写成已手工通过。

完整模拟器回归以 `scripts/regression_emulator.sh` 产出的
`regression-emulator.properties` 为准；只有该文件包含 `status=passed` 时，才能把完整模拟器回归记录为通过。`emulator-verification.properties` 和嵌套
`device-verification.properties` 是配套证据，不替代完整回归结论。

当前 release validation evidence contract 以最新 verifier 为准：API matrix
必须链接 nested per-API regression report 及其 emulator/device/instrumentation
证据；manual acceptance 必须链接正式 `manual-acceptance` 报告；flow matrix 必须链接正式
`release-flow` 报告；performance sanity 必须链接通过的 `perf-baseline` verifier
report；screenshots 必须链接通过的 `release-screenshots` report，并且每张截图文件必须是 PNG。

## 2026-06-22 Agent Behavior Eval Permission Denial Gate

本轮覆盖项：

- `runtime_failure` fixture 新增联系人查询系统权限拒绝恢复 case，锁定
  `query_contacts`、`LocalOnly`、`sensitive` 和 `permissiondenied` 结构化失败模式。
- `scripts/verify_ai_behavior_eval.sh` 新增必需 safety failure mode 覆盖：
  `requiredSafetyFailureModes=permissiondenied`，缺失时以
  `missing-safety-failure-mode-coverage:permissiondenied` fail closed。
- `scripts/test_validation_scripts.sh` 新增负例，删除该 fixture 后断言 verifier 必须失败，
  防止只添加静态 JSONL 文本。

验证命令：

```bash
bash -n scripts/verify_ai_behavior_eval.sh scripts/test_validation_scripts.sh

scripts/verify_ai_behavior_eval.sh \
  --report build/verification/ai-behavior-eval-agent1.properties

bash scripts/test_validation_scripts.sh
```

结果：

- 通过：shell 语法检查。
- 通过：`build/verification/ai-behavior-eval-agent1.properties` 记录
  `status=passed`、`caseCount=39`、`requiredSafetyFailureModes=permissiondenied`、
  `missingSafetyFailureModes=`。
- 通过：完整 `scripts/test_validation_scripts.sh` 输出
  `Validation script tests passed.`；新增权限拒绝 coverage 负例已被 gate 接住。
- 备注：首次长脚本运行期间同文件被并发改写，出现一次 EOF 解析错误；重新确认
  `bash -n` 后复跑通过。

剩余风险：

- 本轮未跑真机/模拟器；权限系统弹窗和 ActivityResult 拒绝链路仍以既有设备测试或后续专项验证为准。
- 本轮只补 fixture/gate，不改 Kotlin runtime 或 actual-trace generator。

## 2026-06-22 Real-App Search Disabled Submit Evidence Gate

本轮覆盖项：

- `UiTargetResolver.explain()` 区分 selectable candidates 和 failure diagnostics：
  disabled 或低于阈值的语义候选不会被 `resolve()` 选中，但在无可执行候选时保留到
  `rankedCandidates`，并绑定对应 `failureKind`。
- 新增 JD 输入态 fixture `jd_disabled_keyboard_submit.xml`，覆盖只有 disabled
  “键盘搜索”提交入口的失败 replay；单测固定 `SubmitNotFound`、空 `selectedNodeId`、
  disabled ranked candidate 和低于提交阈值的 final score。
- `scripts/test_validation_scripts.sh` 的 real-app-search JD submit 失败段补充检查：
  `target_resolution_selected_node_id=`、candidate count/total/archive count、
  `ranked_candidates_file`、disabled candidate `finalScore` 和 failure reason。

验证命令：

```bash
ANDROID_HOME=/data00/home/zouguoxue/android-sdk ANDROID_SDK_ROOT=/data00/home/zouguoxue/android-sdk \
  ./gradlew :app:testDebugUnitTest --tests com.bytedance.zgx.pocketmind.device.UiTargetResolverTest

bash -n scripts/run_real_app_search_eval.sh scripts/test_validation_scripts.sh

scripts/test_validation_scripts.sh
```

结果：

- 预期红灯：新增单测先失败于缺少 disabled “键盘搜索” ranked evidence。
- 通过：实现 resolver evidence/actionable 分层后，`UiTargetResolverTest` 返回
  `BUILD SUCCESSFUL`。
- 通过：shell 语法检查返回 0。
- 通过：`scripts/test_validation_scripts.sh` 返回 `Validation script tests passed`。
- 未执行：真机 `scripts/run_real_app_search_eval.sh`；本轮只增强本地 replay 与 fake ADB
  failure evidence 门禁，不新增 physical-device evidence。

## 2026-06-22 Device Control Debug Eval Fatal Report Contract

本轮覆盖项：

- `scripts/run_device_control_debug_eval.sh` 的 fatal preflight 报告补强
  `failedTarget` / `reason` 分离契约；显式 serial 不可用时写
  `failedTarget=device-selection` 和 `reason=selected-device-unavailable`，不再把具体原因误当失败目标。
- `scripts/test_validation_scripts.sh` 新增 fake adb 负例，验证该失败在 Gradle 前 fail closed，
  并保留 serial、空 API/ABI 和 logcat SHA-256。
- `docs/phone_acceptance.md` 与 `docs/release_checklist.md` 同步说明 debug eval evidence
  与 release physical evidence 的边界。

验证命令：

```bash
bash -n scripts/run_device_control_debug_eval.sh scripts/test_validation_scripts.sh

bash scripts/test_validation_scripts.sh
```

结果：

- 通过：shell 语法检查。
- 通过：最小 fake SDK targeted probe；修复前报告为
  `failedTarget=selected-device-unavailable`，修复后为
  `failedTarget=device-selection` / `reason=selected-device-unavailable`，且
  `logcat_sha256` 为 64 位 SHA-256。
- 通过：完整 `scripts/test_validation_scripts.sh` 输出 `Validation script tests passed.`；
  fake adb 覆盖新增的 device-control selected-serial fail-closed 负例。

剩余风险：

- 本轮按要求未跑真机/模拟器，也未伪造 release evidence；只验证了本地 fake adb
  preflight 报告契约。

## 2026-06-22 Model Capability Profile Fail-Closed Gates

本轮覆盖项：

- `ModelProfile` 构造契约新增 fail-closed 校验：Vision input 只允许 Chat profile 声明；
  Remote OpenAI-compatible profile 只允许 Chat capability，防止远程 profile 被误声明为
  MemoryEmbedding 或 MobileAction。
- `ModelCatalogTest` 新增三个本地 JVM 契约测试，覆盖非 Chat vision、远程记忆 embedding、
  远程手机动作 profile 都必须构造失败。
- 未同步 `docs/model_capability_profiles.json`：已发布 catalog/profile 字段值未变化，文档同步测试覆盖未漂移。

验证命令：

```bash
./gradlew :app:testDebugUnitTest --tests com.bytedance.zgx.pocketmind.ModelCatalogTest

ANDROID_HOME=/data00/home/zouguoxue/android-sdk \
  ./gradlew :app:testDebugUnitTest --tests com.bytedance.zgx.pocketmind.ModelCatalogTest

ANDROID_HOME=/data00/home/zouguoxue/android-sdk \
  ./gradlew :app:testDebugUnitTest \
    --tests com.bytedance.zgx.pocketmind.RemoteModelConfigTest \
    --tests com.bytedance.zgx.pocketmind.docs.ModelCapabilityProfilesDocumentationTest

ANDROID_HOME=/data00/home/zouguoxue/android-sdk \
  ./gradlew :app:testDebugUnitTest --rerun-tasks \
    --tests com.bytedance.zgx.pocketmind.ModelCatalogTest \
    --tests com.bytedance.zgx.pocketmind.RemoteModelConfigTest \
    --tests com.bytedance.zgx.pocketmind.docs.ModelCapabilityProfilesDocumentationTest \
    --tests com.bytedance.zgx.pocketmind.eval.AiBehaviorEvalFixturesTest
```

结果：

- 预期红灯：未设置 SDK 的第一次 Gradle 尝试停在配置阶段，原因是缺少 `ANDROID_HOME` /
  `local.properties`，未执行测试。
- 预期红灯：设置 `ANDROID_HOME` 后，新增三个 `ModelCatalogTest` 用例均因未抛
  `IllegalArgumentException` 失败，确认原契约缺口存在。
- 通过：实现 fail-closed 校验后，`ModelCatalogTest` 目标测试 `BUILD SUCCESSFUL`，
  28 个 task 中 6 executed / 22 up-to-date。
- 通过：`RemoteModelConfigTest` 与 `ModelCapabilityProfilesDocumentationTest`
  `BUILD SUCCESSFUL`，28 个 task 中 1 executed / 27 up-to-date。
- 通过：收尾合并强制复跑 `ModelCatalogTest`、`RemoteModelConfigTest`、
  `ModelCapabilityProfilesDocumentationTest` 与 `AiBehaviorEvalFixturesTest`，
  `BUILD SUCCESSFUL`，28 个 task 全部 executed。

剩余风险：

- 本轮只跑 JVM 单元测试，未跑真机/模拟器；该变更是纯 model profile 构造契约，
  不覆盖实际远程服务返回格式或设备动作 runtime 行为。

## 2026-06-22 Eval Freshness / Browser Submit / Observation Capability Gate

本轮覆盖项：

- `scripts/verify_ai_behavior_eval.sh` 在 strict actual-trace 模式下新增
  `traceRecordedAt` freshness gate：默认超过 30 天 fail closed，并输出
  `actualTraceMaxAgeDays` / `actualTraceNewestRecordedAt`；可用
  `AI_BEHAVIOR_ACTUAL_TRACE_MAX_AGE_DAYS` 明确覆盖窗口。
- Browser profile 新增提交 hint `转到`，并允许已知 browser profile 的非 editable、
  clickable submit candidate 通过 profile hint 得分；未知 App 中同名 `转到` 仍返回
  `SubmitNotFound`。
- Observation replan 阶段继续沿用 `installedCapabilities`：后续 `plannedByModel=true`
  的工具重规划没有 `ModelCapability.MobileAction` 时 fail closed，不创建新的确认卡；规则重规划仍可运行。

验证命令：

```bash
bash -n scripts/verify_ai_behavior_eval.sh scripts/test_validation_scripts.sh
bash scripts/test_validation_scripts.sh

ANDROID_HOME=$HOME/android-sdk ANDROID_SDK_ROOT=$HOME/android-sdk \
  ./gradlew :app:testDebugUnitTest --rerun-tasks \
  --tests com.bytedance.zgx.pocketmind.device.UiTargetResolverTest \
  --tests com.bytedance.zgx.pocketmind.device.UiAutomatorDumpReplayTest \
  --tests com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.modelBackedObservationReplanWithoutMobileActionReturnsMissingModel \
  --tests com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.ruleBackedObservationReplanDoesNotRequireMobileAction \
  --tests com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.modelBackedObservationReplanWorksWhenMobileActionInstalled

ANDROID_HOME=$HOME/android-sdk ANDROID_SDK_ROOT=$HOME/android-sdk \
  scripts/verify_local.sh
```

结果：

- 通过：validation script tests，覆盖 stale actual trace 失败、future trace 失败保留和 fresh trace 通过。
- 通过：聚焦 JVM 测试强制重跑，Gradle `BUILD SUCCESSFUL`，28 个 task 全部 executed。
- 通过：`scripts/verify_local.sh`，Gradle `BUILD SUCCESSFUL`，145 个 task 中
  31 executed / 114 up-to-date；`Android artifact scan passed.`；`Local verification passed.`。
- 备注：Gradle 输出仅包含既有 AndroidX / AppOps deprecation warnings，未导致构建失败。

剩余风险：

- 本轮未跑真机/模拟器；browser `转到` 和 observation replan capability gate 已有 JVM 证据，
  但仍不能替代真实浏览器/Accessibility/arm64 设备验证。

## 2026-06-22 Agent Eval / MobileAction Capability Gate

本轮覆盖项：

- `AgentBehaviorTraceProjector` 现在从 failed `ToolObserved` 结合对应
  `ToolRequested(requestId -> toolName)` 投影真实 App 搜索失败模式；`ui_wait` 验证阶段的
  `failureKind=result_not_verified` + `searchVerificationEvidence=page_not_changed` 会投影为
  `page_not_changed`，但 eval 暴露工具序列仍过滤内部 `ui_wait`。
- `AiBehaviorActualTraceGeneratorTest` 的真实 App 搜索失败 trace 不再手工构造
  `AgentBehaviorActualTrace`，而是用 runtime-style `AgentLoopResult` 交给 projector 生成。
- `AgentLoopRuntime` 将 `installedCapabilities` 接入初始动作规划：`usedModel=true` 的动作规划
  必须安装 `ModelCapability.MobileAction`；规则规划和 skill-first 路径不需要 action model。
- `scripts/verify_ai_behavior_eval.sh` 的 planning trace diff 每条 case 现在输出
  `actualTraceSource` 和 `actualTraceRecordedAt`，便于 release evidence 逐行追溯实际
  runtime trace 来源与采集时间。

验证命令：

```bash
ANDROID_HOME=$HOME/android-sdk ANDROID_SDK_ROOT=$HOME/android-sdk \
  ./gradlew :app:testDebugUnitTest --rerun-tasks \
  --tests com.bytedance.zgx.pocketmind.eval.AiBehaviorPlanningTraceProjectorTest \
  --tests com.bytedance.zgx.pocketmind.eval.AiBehaviorActualTraceGeneratorTest.writesAgentLoopRuntimeActualTraceJsonl \
  --tests com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.modelBackedActionPlanningWithoutMobileActionReturnsMissingModel \
  --tests com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.ruleBackedActionPlanningDoesNotRequireMobileAction \
  --tests com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.modelBackedActionPlanningWorksWhenMobileActionInstalled

ANDROID_HOME=$HOME/android-sdk ANDROID_SDK_ROOT=$HOME/android-sdk \
  ARTIFACT_DIR=build/verification/ai-behavior-actual-trace-jvm \
  scripts/collect_ai_behavior_actual_trace.sh

ANDROID_HOME=$HOME/android-sdk ANDROID_SDK_ROOT=$HOME/android-sdk \
  scripts/verify_local.sh

scripts/verify_ai_behavior_eval.sh \
  --require-boundary-map \
  --report /tmp/ai-behavior-eval-current.properties
```

结果：

- 通过：聚焦 JVM 测试强制重跑，`BUILD SUCCESSFUL`，28 个 task 全部 executed。
- 通过：AI behavior actual trace collector，38 个 case / 7 categories / 6 MVP scenarios；
  `actualTraceSourceBreakdown=agent_loop_runtime:38`，`traceDiffMissingActualCount=0`，
  `traceDiffMismatchCount=0`。
- 通过：`scripts/verify_local.sh`，Gradle `BUILD SUCCESSFUL`，145 个 task 中
  31 executed / 114 up-to-date；`Android artifact scan passed.`；`Local verification passed.`。
- 关键证据：`build/verification/ai-behavior-actual-trace-jvm/ai-behavior-actual-trace-collection.properties`。
- 抽查：`runtime_app_search_page_not_changed` 输出 `failureMode=page_not_changed`、
  `actualTools=[open_app_by_name, ui_tap, ui_type_text, ui_submit_search]`、
  `traceSource=agent_loop_runtime`。
- 通过：`scripts/verify_ai_behavior_eval.sh --require-boundary-map` 返回
  `AI behavior eval fixtures passed: 38 cases across 7 categories and 6 MVP scenarios`；
  报告记录 `caseCount=38`、`traceDiffMissingActualCount=38`（未传 actual trace 的
  fixture-only 模式）和完整 MVP scenario 覆盖。

剩余风险：

- 本轮仍未跑真机/模拟器；真实 App 搜索 runtime trace 合同已在 JVM 侧闭环，实际淘宝/高德等
  UI dump 和 Accessibility 行为仍需后续物理 arm64 设备验证。

## 2026-06-22 Device Eval Report Contract

本轮覆盖项：

- `scripts/run_device_control_debug_eval.sh` 的总报告新增 `artifact_schema`、`artifact_id`、
  `target`、`failedTarget`、`api_level`、`abi` 和 `logcat_sha256`，失败路径会保留
  机器可读失败目标。
- `scripts/run_real_app_search_eval.sh` 的总报告新增同一组机器可读字段，同时保留
  per-case `RealAppSearchCaseArtifact/v1` 与 resolver evidence / diagnostics SHA。
- `scripts/run_real_app_search_eval.sh` 的 fatal 失败（例如没有任何目标 App 安装）
  现在会在顶层 report 输出 `failure_diagnostics_dir`、截图、UIAutomator dump、
  focused/window dump 和 logcat 的路径与 SHA-256；case failure 顶层 report 也会记录
  `failedTarget=real-app-search-case`。
- `scripts/test_validation_scripts.sh` 新增静态契约检查，防止两个设备 eval 报告回退到只写
  `reason` / bare logcat path。

验证命令：

```bash
bash -n scripts/run_device_control_debug_eval.sh \
  scripts/run_real_app_search_eval.sh \
  scripts/test_validation_scripts.sh

bash scripts/test_validation_scripts.sh

ANDROID_HOME=$HOME/android-sdk ANDROID_SDK_ROOT=$HOME/android-sdk \
  scripts/verify_local.sh
```

结果：

- 通过：shell 语法检查。
- 通过：`Validation script tests passed.`；fake adb 覆盖 `failedTarget=real-app-search-case`
  和 `failedTarget=target-apps`，并断言 fatal diagnostics 的 screenshot/UIAutomator/window/logcat
  文件与 SHA-256 已写入顶层 `real-app-search-eval.properties`。
- 通过：`scripts/verify_local.sh` 覆盖 validation script tests、JVM tests、debug/release build、
  AndroidTest assemble、lint 和 artifact scan。

剩余风险：

- 本轮按要求未跑真机/模拟器；设备 eval 报告字段已静态锁定，但实际 serial/API/ABI/logcat
  值仍需要后续在物理 arm64 设备或合规 arm64 emulator 上采集。

## 2026-06-22 Local Verification Gate

本轮覆盖项：

- 当前分支最新本地总门禁：doctor、shell syntax、validation script tests、JVM
  `testDebugUnitTest`、`lintDebug`、`assembleDebug`、`assembleDebugAndroidTest`、
  `assembleRelease`、`bundleRelease`、APK/AAB 不含 `.litertlm`、release artifact scan、
  arm64 native-code badging、artifact size budget、model URL pinning guard、remote API key
  plaintext storage guard。

验证命令：

```bash
ANDROID_HOME="$HOME/android-sdk" ANDROID_SDK_ROOT="$HOME/android-sdk" \
  scripts/verify_local.sh
```

结果：

- 通过：`Validation script tests passed.`
- 通过：Gradle `BUILD SUCCESSFUL`，145 个 task 中 28 executed / 117 up-to-date。
- 通过：`Android artifact scan passed.`
- 通过：`Local verification passed.`
- 备注：`MainActivity.kt` 仍有既有 `unsafeCheckOpNoThrow` deprecation warning，但未导致
  lint/build 失败。
- 未执行：真机 instrumentation、arm64/x86 模拟器、真实 App 搜索；`verify_local.sh`
  明确不要求 adb。

## 2026-06-22 Agent Behavior Real-App And Public Batch Gates

本轮覆盖项：

- 新增 `runtime_app_search_page_not_changed` Agent behavior eval fixture，覆盖真实 App 搜索后
  页面未变化但首页 feed 含 `综合` / `销量` / `筛选` 等结果页提示词的假阳性场景。
- `verify_ai_behavior_eval.sh` 将 `page_not_changed` 加入 real-app search 必达失败模式；
  若 fixture 或 actual trace 丢失该模式，release behavior gate 会 fail closed。
- `AiBehaviorActualTraceGeneratorTest` 生成该 case 的 `agent_loop_runtime` actual trace，并验证
  工具序列、`fail_closed`、`LocalOnly`、低风险和 `page_not_changed` failure mode。
- `test_validation_scripts.sh` 新增负例：把该 fixture 的 allowed failure mode 改成
  `unchanged_page_unclassified` 时，verifier 必须报告
  `missing-real-app-search-failure-mode-coverage:page_not_changed`。
- 新增 `privacy_public_weather_batch_allowed` Agent behavior eval fixture，用两个
  `web_search` 工具调用覆盖真正的 public evidence batch，而不是单次 public search。
- `verify_ai_behavior_eval.sh` 新增必达 boundary gate：
  `public_evidence_multi_search_batch_allowed` 缺失时必须 fail closed。
- `AiBehaviorActualTraceGeneratorTest` 使用 `observeModelToolRequests` 生成两次
  `web_search` 的真实 batch trace，并验证无需确认、`RemoteEligible`、`public_evidence`。
- `test_validation_scripts.sh` 新增负例：篡改该 fixture 的 `expectedBoundary` 时，verifier
  必须报告 `missing-required-boundary-coverage:public_evidence_multi_search_batch_allowed`。

验证命令：

```bash
bash scripts/test_validation_scripts.sh

ANDROID_HOME="$HOME/android-sdk" ANDROID_SDK_ROOT="$HOME/android-sdk" \
  ./gradlew :app:testDebugUnitTest \
    --tests 'com.bytedance.zgx.pocketmind.eval.AiBehaviorActualTraceGeneratorTest' \
    --tests 'com.bytedance.zgx.pocketmind.eval.AiBehaviorEvalFixturesTest'

scripts/verify_ai_behavior_eval.sh --require-boundary-map \
  --report build/verification/ai-behavior-page-not-changed.properties

ANDROID_HOME="$HOME/android-sdk" ANDROID_SDK_ROOT="$HOME/android-sdk" \
  AI_BEHAVIOR_ACTUAL_TRACE_FILE=build/verification/ai-behavior-page-not-changed/actual-trace.jsonl \
  scripts/collect_ai_behavior_actual_trace.sh
```

结果：

- 红灯确认：新增 validation 负例前，`test_validation_scripts.sh` 报
  `AI behavior eval requires unchanged real app page coverage unexpectedly succeeded`。
- 红灯确认：新增 public batch validation 负例前，`test_validation_scripts.sh` 报
  `AI behavior eval requires public evidence multi-search batch coverage unexpectedly succeeded`。
- 通过：`Validation script tests passed.`
- 通过：目标 JVM eval 测试 `BUILD SUCCESSFUL`。
- 通过：strict fixture verifier 返回 `AI behavior eval fixtures passed: 38 cases across 7
  categories and 6 MVP scenarios`。
- 通过：actual trace collector 报告 `caseCount=38`、`traceDiffMismatchCount=0`、
  `traceDiffMissingActualCount=0`，并在 `requiredRealAppSearchFailureModes` 中包含
  `editable_not_found,page_not_changed,required_hint_missing,result_not_verified,search_entry_not_found,submit_not_found`。
- 通过：actual trace diff 中 `privacy_public_weather_batch_allowed` 为 `matched`，实际工具为
  `["web_search","web_search"]`，`requiredBoundaryIds=public_evidence_multi_search_batch_allowed`
  且 `missingRequiredBoundaryIds=`。
- 未执行：真机 instrumentation、arm64/x86 模拟器、真实 App 搜索；本轮只增强本地 behavior eval gate。

## 2026-06-22 Release Evidence Report Schema Hardening

本轮覆盖项：

- `verify_release_operations_record.sh` 的 report 增加
  `artifactSchema=ReleaseOperationsVerification/v1`、`owner=release-engineering`、
  UTC `recordedAt`、可复现 `command`、`reproduciblePath` 和
  `operationsRecordSha256`。
- `sign_release_artifacts.sh` 的 report 增加
  `artifactSchema=ReleaseSigningReport/v1`、`owner=release-engineering`、UTC
  `recordedAt`、可复现 `command`、`reproduciblePath`，并记录 unsigned APK/AAB SHA-256
  和 artifact scan 子报告 SHA-256。
- `privacy_scan.sh` 的 report 增加 `artifactSchema=PrivacyScanReport/v1`、
  `owner=privacy-security`、UTC `recordedAt`、可复现 `command`、`reproduciblePath`，
  并为每个扫描目标输出 path；文件目标额外绑定 SHA-256。
- `scan_android_artifacts.sh` 的 report 增加
  `artifactSchema=AndroidArtifactScanReport/v1`、`owner=release-engineering`、UTC
  `recordedAt`、可复现 `command` 和 `reproduciblePath`；既有 per-artifact path/SHA/size
  绑定继续保留。
- 上述脚本的 `command` 改为 shell-quoted argv，带空格路径可以被解析回原始参数。
- 上述 release evidence 脚本在已知 `--report` 路径后遇到 malformed 参数时 fail-closed：
  unknown option、缺值参数、option-like 参数值都会写出强 schema failure report，并标记
  `failedTarget=argument-parser`。
- `verify_release_operations_record.sh` 现在会强制校验 release artifact archive 和
  protected signing 子证据的 `artifactSchema`、`owner`、UTC `recordedAt`、`command`、
  `reproduciblePath`，并校验其 artifact scan 子报告路径和 SHA-256 绑定。
- `test_validation_scripts.sh` 对 release operations pending/approved report、privacy scan
  pass/fail/file-target report、artifact scan pass/fail report、signing missing-env/missing-AAB
  report 增加 schema/SHA 字段断言；同时覆盖 spaced-path command argv 回放和 weak protected
  signing evidence fail-closed，防止 release evidence 退回弱报告格式。
- 并行审查结论：memory deletion tracking 仍建议用独立 tombstone side table/store 实现，
  但该改动涉及 Room schema 和迁移，单独后续 TDD，不混入本轮 release report schema。

验证命令：

```bash
bash scripts/test_validation_scripts.sh
```

结果：

- 红灯确认：新增 operations schema 断言后，旧 report 缺失
  `artifactSchema=ReleaseOperationsVerification/v1` 导致 validation script 失败。
- 红灯确认：新增 privacy schema 断言后，旧 report 缺失
  `artifactSchema=PrivacyScanReport/v1` 导致 validation script 失败。
- 红灯确认：spaced-path command 回放解析出错误 argv 数量，暴露未引用 `${ORIGINAL_ARGS[*]}`
  不可复现。
- 红灯确认：weak protected signing evidence 仍被 operations verifier 接受，暴露子证据强
  schema 未被消费。
- 红灯确认：`--report <path> --bad-arg` 曾经不产出 report；`--file --bad-arg`、
  `--apk --bad-arg`、`--expected-certificate-sha256 --bad-arg` 曾被误当成参数值；
  `privacy_scan.sh --report <path> --bad-arg` 曾被误当成扫描目标并通过。
- 通过：补齐 schema 字段后 `Validation script tests passed.`
- 抽查：operations report 包含 `operationsRecordSha256`；signing report 包含
  `artifactSchema=ReleaseSigningReport/v1`、`reproduciblePath`、unsigned artifact SHA 和
  `artifactScanReportSha256`；privacy file target report 绑定 `scanTarget1Sha256`；
  artifact scan report 绑定 `artifact1Sha256` 和 `artifact1SizeBytes`。
- 未执行：真机 instrumentation、arm64/x86 模拟器、真实 App 搜索；本轮只增强本地 release evidence schema。

## 2026-06-22 LocalOnly Multimodal Tool Policy and AI Eval Invariants

本轮覆盖项：

- `READ_RECENT_SCREENSHOT_OCR`、`READ_RECENT_IMAGE_OCR`、`READ_CURRENT_SCREEN_TEXT`、
  `CAPTURE_CURRENT_SCREENSHOT_OCR` 的 ToolRegistry policy 显式声明为
  `ToolResultContinuationPolicy.LocalEvidence`，让 OCR/屏幕 LocalOnly 证据不会依赖分散的
  tags/private-output fallback。
- `ToolRegistryTest` 新增契约：上述工具必须有 private outputs、需要确认、不得 remote planning
  eligible、不得 public evidence batch eligible。
- `verify_ai_behavior_eval.sh` 新增语义 gate：`remote_send_confirmation` 必须绑定
  `RemoteEligible/localOnly=false/remoteEligible=true`；actual trace 中
  `routingPath=no_action` 不能同时携带 actual tools、routing tool 或 skill metadata。
- `AiBehaviorEvalFixturesTest` 同步 remote-send confirmation 的 RemoteEligible fixture invariant。

验证命令：

```bash
ANDROID_HOME="$HOME/android-sdk" ANDROID_SDK_ROOT="$HOME/android-sdk" ./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest.localOnlyOcrAndScreenToolsUseLocalEvidenceContinuationPolicy'

ANDROID_HOME="$HOME/android-sdk" ANDROID_SDK_ROOT="$HOME/android-sdk" ./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.recentScreenshotOcrObservationBuildsLocalPromptAndRedactsTrace' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.recentImageOcrObservationBuildsLocalPromptAndRedactsTrace' \
  --tests 'com.bytedance.zgx.pocketmind.multimodal.CurrentScreenshotOcrContractTest'

bash scripts/test_validation_scripts.sh
```

结果：

- 红灯确认：新增 ToolRegistry 契约先失败于
  `ToolRegistryTest.localOnlyOcrAndScreenToolsUseLocalEvidenceContinuationPolicy`，证明四个
  OCR/屏幕工具缺少显式 `LocalEvidence` policy。
- 红灯确认：`privacy_remote_image_preview` 保持 `remote_send_confirmation` 但改为
  `LocalOnly` 时，旧 `verify_ai_behavior_eval.sh` 仍接受；新增 gate 后拒绝
  `remote-confirmation-privacy-mismatch`。
- 红灯确认：actual trace 中 `routingPath=no_action` 同时带 `actualTools` 时，新增 gate 拒绝
  `actual-trace-routing-conflict`。
- 通过：targeted JVM 测试返回 `BUILD SUCCESSFUL`；`scripts/test_validation_scripts.sh`
  返回 `Validation script tests passed`。
- 未执行：真机 instrumentation、arm64/x86 模拟器、真实 App 搜索；本轮只增强本地 JVM 和
  shell eval gate。

## 2026-06-22 Release Gate Missing Child Report Status

本轮覆盖项：

- `release-gate.properties` 的 child report binding 对未生成的子报告明确写入
  `ReportStatus=not-produced` 和空 `ReportSha256`，避免早失败路径留下不可区分的空
  status。
- `scripts/test_validation_scripts.sh` 新增 not-produced helper，并覆盖
  `PUBLIC_RELEASE=1` 缺签名证书时 signing cert 之后所有 downstream child reports
  尚未运行的早失败路径。

验证命令：

```bash
bash -n scripts/verify_release_gate.sh scripts/test_validation_scripts.sh
scripts/test_validation_scripts.sh
```

结果：

- 通过：shell syntax 检查。
- 通过：validation script tests 全量通过，覆盖 release gate 早失败 child report
  `not-produced` 状态。
- 未执行：真机 instrumentation、arm64/x86 模拟器、真实 App 搜索；本轮只做本地
  release gate 脚本验证。

## 2026-06-22 Device Verification Artifact Model Invariants

本轮覆盖项：

- `DeviceVerificationArtifact` 明确区分 failed 与 passed/skipped：failed artifact 必须
  包含非空 `failedTarget` 和 `reason`；passed/skipped artifact 不能携带失败元数据。
- JVM 测试覆盖 failed artifact 缺 failedTarget、缺 reason、passed artifact 携带失败元数据、
  invalid SHA-256 被拒绝，以及现有 phase-one 合同中的失败 artifact 样例。

验证命令：

```bash
ANDROID_HOME="$HOME/android-sdk" ANDROID_SDK_ROOT="$HOME/android-sdk" \
  ./gradlew :app:testDebugUnitTest \
    --tests 'com.bytedance.zgx.pocketmind.evidence.EvidenceModelsTest' \
    --tests 'com.bytedance.zgx.pocketmind.contracts.PhaseOneContractModelsTest'
```

结果：

- 通过：目标 JVM 测试。
- 未执行：真机 instrumentation、arm64/x86 模拟器、真实 App 搜索；本轮只做本地
  evidence model / contract JVM 验证。

## 2026-06-22 Release Gate Evidence Artifact Schema

本轮覆盖项：

- `scripts/verify_release_gate.sh` 的顶层 `release-gate.properties` 升级为一等
  evidence artifact，输出 `artifactSchema=ReleaseGateVerification/v1`、owner、UTC
  `recordedAt`、可复现 `command`、`reproduciblePath`、`headCommitSha`、`reason`、
  `failedTarget` 和 `failedReason`。
- release gate report 绑定已生成子报告的 path/status/SHA-256，包括 signing cert、
  privacy scan、contract tests、AI behavior eval、artifact scan、perf baseline、
  release mapping、release/store/operations/validation records、model license review
  和 privacy review。
- `scripts/test_validation_scripts.sh` 新增 release gate schema helper，并在隐私扫描失败、
  缺 perf baseline、public release 缺签名证书三个不同时序的失败路径上断言顶层 schema
  与 child report 绑定；同时新增 passed release gate smoke，固定成功报告
  `reason=approved` 和 skipped/passed child report 绑定。

验证命令：

```bash
bash -n scripts/verify_release_gate.sh scripts/test_validation_scripts.sh
scripts/test_validation_scripts.sh
```

结果：

- 通过：shell syntax 检查。
- 通过：validation script tests 全量通过，覆盖 release gate 顶层 schema、UTC
  timestamp、command、head commit SHA、reproducible path、child report path/status/SHA
  绑定、passed `reason=approved`，以及既有失败摘要回归。
- 未执行：真机 instrumentation、arm64/x86 模拟器、真实 App 搜索；本轮只做本地
  release gate 脚本验证。

## 2026-06-22 Local Vision Release-Flow Evidence Gate

本轮覆盖项：

- `shareAndPickerInput` release-flow evidence 不再只覆盖 remote vision；正式
  `scripts/record_release_flow_evidence.sh` 和 candidate
  `scripts/collect_release_flow_matrix_evidence.sh` 都输出 local vision 字段。
- 新增 local vision 机器可校验字段：verified local image staging、local runtime image
  attachment sent、LocalOnly persistence、prompt metadata redaction、remote runtime idle、
  unsupported OCR skipped，以及 local runtime send count / remote runtime request count /
  unsupported runtime image send count / unsupported image OCR invocation count。
- `scripts/verify_release_validation_record.sh` 要求 local vision runtime image send
  count 至少为 1，远程 runtime request、unsupported runtime image send 和 unsupported
  image OCR invocation 均为 0。
- `scripts/test_validation_scripts.sh` 增加 count=2 仍通过、count=0 失败、弱
  share/picker evidence 缺 local vision 字段失败，以及 candidate/full flow 输出断言。
- `docs/release_checklist.md` 和 `docs/release_readiness.md` 同步 release-flow 合同。

验证命令：

```bash
bash -n scripts/record_release_flow_evidence.sh scripts/collect_release_flow_matrix_evidence.sh \
  scripts/verify_release_validation_record.sh scripts/test_validation_scripts.sh

scripts/test_validation_scripts.sh

ANDROID_HOME=$HOME/android-sdk ANDROID_SDK_ROOT=$HOME/android-sdk ./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.localVisionSharedImageIsSentToLocalRuntimeAndStaysLocalOnly' \
  --tests 'com.bytedance.zgx.pocketmind.multimodal.SharedInputTest.localVisionPromptOmitsAttachmentMetadataAndDoesNotClaimUnsupported' \
  --tests 'com.bytedance.zgx.pocketmind.MainActivitySharedInputModeTest'

rm -rf build/verification/local-vision-release-flow-smoke && \
  OWNER=QA RELEASE_FLOW_ALL=1 \
  ARTIFACT_DIR=build/verification/local-vision-release-flow-smoke \
  scripts/record_release_flow_evidence.sh && \
  rg -n 'localVision' \
    build/verification/local-vision-release-flow-smoke/flow-shareAndPickerInput.properties
```

结果：

- 通过：shell syntax 检查。
- 通过：validation script tests 全量通过，覆盖 local vision release-flow 正例和负例。
- 通过：目标 JVM 测试覆盖本地视觉共享图片进入本地 runtime、LocalOnly、prompt
  metadata 不泄漏、不误触 unsupported OCR，以及共享输入 UI 模式。
- 通过：release-flow 生成侧 smoke 输出 10 个 `localVision*` 字段。
- 未执行：真机 instrumentation、arm64/x86 模拟器；本轮按要求只做本地脚本/JVM 验证。

剩余风险：

- 本轮强化的是 release-flow evidence contract，不替代最终真机视觉模型性能、真实图片
  选择器端到端或 release candidate 全量矩阵。

## 2026-06-22 Release Operations Monitoring/Rollback Evidence Gate

本轮覆盖项：

- `scripts/verify_release_operations_record.sh` 不再只检查 monitoring/rollback evidence
  的 path 和 SHA。monitoring evidence 现在必须是 typed properties，包含
  `status=passed`、`target=release-monitoring-evidence`、
  `operationsRecordField=monitoring.evidence`，并绑定 owner、signal sources、
  first-24-hour watcher、crash-free/ANR threshold 和 crash SDK privacy review。
- rollback evidence 现在必须是 typed properties，包含
  `status=passed`、`target=release-rollback-evidence`、
  `operationsRecordField=rollback.evidence`，并绑定 owner、decision channel、rollback
  criteria、staged rollout action、Play version-code policy、model manifest rollback
  path、data compatibility，以及 previous-known-good / initial-release metadata。
- `scripts/test_validation_scripts.sh` 的 release operations fixture 改为生成 typed
  monitoring/rollback evidence，并新增 matching SHA 但 `status=pending` 的负例。
- `docs/release_checklist.md` 同步记录 typed properties evidence 要求。

验证命令：

```bash
bash -n scripts/verify_release_operations_record.sh scripts/test_validation_scripts.sh
scripts/test_validation_scripts.sh
```

结果：

- 通过：shell syntax 检查。
- 通过：validation script tests 全量通过，覆盖 approved operations 正例、matching SHA
  但 pending monitoring evidence 失败、matching SHA 但 pending rollback evidence 失败。
- 未执行：真机 instrumentation、arm64/x86 模拟器；本轮按要求只做本地脚本验证。

剩余风险：

- 本轮强化的是 release operations evidence 内容合同，不替代真实 Android Vitals、
  rollout 值班、production signing、CI artifact 或人工 release operations review。
- 真实 App UI 回放仍需继续补 after-tap/input、submit button 和 result page XML
  fixtures；本轮未替代真机 real-app-search eval。

## 2026-06-22 Real-App Search Eval Branch Coverage

本轮覆盖项：

- `scripts/test_validation_scripts.sh` 的 fake adb real-app result 生成器新增
  `submit_not_found`、`result_not_verified` 和 `required_hint_missing` 分支。
- real-app eval self-test 新增 submit failure、result verification failure、required
  hint missing、no target apps installed fail-closed，以及 8 个 fake target apps
  全部通过的本地路径。
- 新断言覆盖 top-level report 的 run/pass/fail/skip 计数，以及对应 case artifact
  的 `reason`、`failure_kind`、`failed_step`、result SHA；submit 失败还绑定
  `UiTargetResolutionEvidenceArtifact/v1` 和 ranked candidates JSON。

验证命令：

```bash
bash -n scripts/run_real_app_search_eval.sh scripts/test_validation_scripts.sh
scripts/test_validation_scripts.sh
```

结果：

- 通过：shell syntax 检查。
- 通过：validation script tests 全量通过，覆盖 real-app eval 的 tap/type/submit/verify
  失败、required hint 缺失、全 skipped fail-closed 和全 fake App 成功路径。
- 未执行：真机 instrumentation、arm64/x86 模拟器、真实 App 搜索；本轮按要求只做
  本地 fake-adb 脚本验证。

## 2026-06-22 Agent Behavior Real-App Failure Modes

本轮覆盖项：

- `runtime_failure.jsonl` 新增真实 App 搜索失败语义：
  `search_entry_not_found`、`editable_not_found`、`submit_not_found`、
  `result_not_verified`、`required_hint_missing`。
- `AiBehaviorEvalFixturesTest` 要求 runtime failure fixtures 必须覆盖上述 5 个
  failure modes。
- `AiBehaviorActualTraceGeneratorTest` 为这 5 个 case 输出 LocalOnly、low-risk、
  fail-closed、`open_app_ui_search_skill` routing 的 actual trace。
- `verify_ai_behavior_eval.sh` 新增必达覆盖 gate，并在 report 写入
  `requiredRealAppSearchFailureModes` / `missingRealAppSearchFailureModes`；
  `scripts/test_validation_scripts.sh` 增加缺失 `submit_not_found` 的负例。

验证命令：

```bash
bash -n scripts/verify_ai_behavior_eval.sh scripts/test_validation_scripts.sh
ANDROID_HOME="$HOME/android-sdk" ANDROID_SDK_ROOT="$HOME/android-sdk" \
  ./gradlew :app:testDebugUnitTest \
    --tests 'com.bytedance.zgx.pocketmind.eval.AiBehaviorEvalFixturesTest' \
    --tests 'com.bytedance.zgx.pocketmind.eval.AiBehaviorActualTraceGeneratorTest'
ANDROID_HOME="$HOME/android-sdk" ANDROID_SDK_ROOT="$HOME/android-sdk" \
  ARTIFACT_DIR=build/verification/ai-behavior-real-app-failure-modes \
  scripts/collect_ai_behavior_actual_trace.sh
scripts/test_validation_scripts.sh
```

结果：

- 通过：目标 JVM 测试。
- 通过：AI behavior actual trace collector，`caseCount=36`，
  `traceDiffMissingActualCount=0`、`traceDiffMismatchCount=0`、
  `traceDiffExtraActualCount=0`。
- 通过：validation script tests 全量通过，覆盖 real-app failure mode 缺失负例。
- 未执行：真机 instrumentation、arm64/x86 模拟器、真实 App 搜索；本轮按要求只做
  本地 eval/fixture/脚本验证。

## 2026-06-22 Real-App Result Dump Replay

本轮覆盖项：

- 新增 `taobao_search_input.xml` UIAutomator fixture，表示淘宝搜索输入页，覆盖
  `EditText` 搜索输入框、可点击“搜索”提交按钮、搜索建议和拍照搜索噪声节点。
- 新增 `taobao_search_results.xml` UIAutomator fixture，表示淘宝搜索“海河牛奶”后的
  结果页，包含搜索框、提交按钮、综合/销量/筛选和商品卡片。
- 新增 `pdd_search_home.xml` UIAutomator fixture，表示拼多多首页搜索入口，覆盖
  `搜索商品 多多搜索` 搜索栏，并固定扫码入口和首页 feed 不会抢占搜索入口。
- 新增 `pdd_search_input.xml` 和 `pdd_search_results.xml` UIAutomator fixtures，
  表示拼多多搜索输入态和结果页，覆盖 `EditText` 商品搜索框、非 editable “搜索”
  提交按钮，以及与真机 eval 对齐的 `纸巾` 结果页验证。
- 新增 `gaode_destination_input.xml` 和 `gaode_destination_results.xml` UIAutomator
  fixtures，表示高德目的地搜索输入态和结果页，覆盖 `EditText` 目的地输入框、非
  editable “搜索”提交按钮，以及 `北京机场` 结果页验证。
- 新增 `jd_search_input.xml` 和 `jd_search_results.xml` UIAutomator fixtures，
  表示京东搜索输入态和结果页，覆盖 `EditText` 商品/店铺搜索框、非 editable “搜索”
  提交按钮，以及 `机械键盘` 结果页验证。
- 新增 `chrome_address_home.xml` UIAutomator fixture，表示 Chrome 首页 omnibox，覆盖
  `搜索或输入网址 地址栏` 地址栏，并固定语音搜索和 feed 不会抢占搜索入口。
- 新增 `android_browser_address_home.xml` 和 `uc_address_home.xml` UIAutomator
  fixtures，表示 Android Browser 与 UC 浏览器首页地址栏，并固定语音搜索、扫一扫、
  web/news feed 不会抢占搜索入口。
- 新增 `quark_search_input.xml` 和 `quark_search_results.xml` UIAutomator fixtures，
  表示 Quark/browser 输入态和结果页，覆盖地址栏 `EditText`、非 editable “搜索”
  提交按钮，以及 `Kotlin 协程` 结果页验证。
- `UiAutomatorDumpReplayTest` 新增输入页 `EditableField` / `SubmitSearch` resolver
  回放测试、拼多多/高德/京东/Chrome/Android Browser/Quark/UC search-entry replay，
  并新增首页 dump -> 结果页 dump 的 `AppSearchResultVerifier` 回放测试，要求 query
  在页面变化后出现在非输入框结果内容中；拼多多/京东输入页同时固定 suggestion
  list 不能进入 editable 或 submit ranked candidates。
- `AppSearchResultVerifier` 新增京东首页回放负例：未变化页面即使包含 `综合`、`销量`、
  `筛选` 等结果页 hint，也必须 `ResultNotVerified/page_not_changed`，避免首页 feed
  被误判为真实搜索结果；只靠多个 hint 的通过路径现在要求页面签名发生变化。
- `UiTargetResolver` 修复 `SubmitSearch` 候选过滤：editable 搜索输入框即使命中
  browser profile hint，也不能进入提交按钮 ranked candidates；实际 Accessibility
  submit path 保持同样的非 editable / clickable 约束。
- `UiTargetResolver.kindForTarget()` 新增高德/地图目标词归类：`目的地`、`去哪儿`、
  `搜地点`、`搜索地点`、`终点` 会进入 search-entry 路径；resolver 与 Accessibility
  runtime 同步排除 `语音搜索`、拍照/相机/图片/扫码类非文本搜索控件作为
  `SubmitSearch` 候选。

验证命令：

```bash
ANDROID_HOME="$HOME/android-sdk" ANDROID_SDK_ROOT="$HOME/android-sdk" \
  ./gradlew :app:testDebugUnitTest \
    --tests 'com.bytedance.zgx.pocketmind.device.UiAutomatorDumpReplayTest' \
    --tests 'com.bytedance.zgx.pocketmind.device.UiTargetResolverTest'
```

结果：

- 通过：目标 JVM 测试。
- 未执行：真机 instrumentation、arm64/x86 模拟器、真实 App 搜索；本轮只补本地
  XML replay fixture。

## 2026-06-22 Privacy/Model License Verifier Evidence Schema Gate

本轮覆盖项：

- `scripts/verify_privacy_review.sh` 和
  `scripts/verify_model_license_review.sh` 的 report 统一输出
  `artifactSchema`、`owner`、UTC `recordedAt`、`command`、`failedTarget`、
  `reason`、`reproduciblePath` 和输入文件 SHA-256。
- privacy review report 绑定 review JSON 与 privacy notice SHA；model license
  review report 绑定 review JSON、metadata JSON、model manifest SHA 和
  metadata freshness window。
- `scripts/test_validation_scripts.sh` 的 release verifier schema helper 支持
  verifier-specific owner，并覆盖 privacy/model-license 的 pending 失败路径和
  approved 成功路径，防止这两类发布审批报告回退成只含 `status/reason` 的弱证据。

验证命令：

```bash
bash -n scripts/verify_privacy_review.sh scripts/verify_model_license_review.sh \
  scripts/test_validation_scripts.sh
scripts/test_validation_scripts.sh
```

结果：

- 通过：shell syntax 检查。
- 通过：validation script tests 全量通过，新增断言覆盖 privacy/model-license 两类
  verifier 的 schema、owner、UTC 时间、命令、可复现路径、失败目标和 SHA-256 绑定。
- 未执行：真机 instrumentation、arm64/x86 模拟器；本轮按要求只做本地脚本门禁。

## 2026-06-22 Release Verifier Evidence Schema Gate

本轮覆盖项：

- `scripts/verify_release_record.sh`、`scripts/verify_store_policy_record.sh` 和
  `scripts/verify_release_validation_record.sh` 的 report 统一输出
  `artifactSchema`、`owner`、UTC `recordedAt`、`command`、`failedTarget`、
  `reason`、`reproduciblePath` 和输入文件 SHA-256。
- release record report 绑定 release JSON 与 Gradle 文件 SHA；store policy report
  绑定 policy JSON、privacy notice 与 Android manifest SHA；release validation report
  绑定 validation JSON SHA。
- `scripts/test_validation_scripts.sh` 新增统一 schema 断言，覆盖 pending 失败路径与
  approved 成功路径，防止发布 verifier report 回退成只含 status/reason 的弱证据。

验证命令：

```bash
bash -n scripts/verify_release_record.sh scripts/verify_store_policy_record.sh \
  scripts/verify_release_validation_record.sh scripts/test_validation_scripts.sh

scripts/test_validation_scripts.sh
```

结果：

- 通过：shell syntax 检查。
- 通过：validation script tests 全量通过，新增断言覆盖 release/store/validation 三类
  verifier 的 schema、owner、UTC 时间、命令、可复现路径、失败目标和 SHA-256 绑定。
- 未执行：真机 instrumentation、arm64/x86 模拟器；本轮按要求只做本地脚本验证。

剩余风险：

- 本轮强化的是 verifier 自身的报告证据链，不替代真实 release record、store policy
  审批、physical device、API matrix、性能基线或发布签名。
- 多 Agent 探索发现的下一批缺口仍待处理：release operations monitoring/rollback
  evidence 内容校验，以及 local vision release-flow contract。

## 2026-06-21 Remote Send Pending Confirmation Fail-Closed

本轮覆盖项：

- 新增 `PendingRemoteSendMarker` / `RemoteSendPendingStore`，只保存远程发送确认的
  kind、模型名、计数、runId 和时间戳；不保存 prompt、图片 bytes、OCR 文本、工具结果或
  host。
- 创建 current input / sensitive input / tool-result continuation 远程发送确认时写入 marker；
  用户确认、打码发送、原样发送、取消、切换会话/模式/远程配置/本地模型时清除 marker。
- App 启动时消费遗留 marker，fail-closed 地丢弃 pending confirmation，追加一条
  `LocalOnly` 本地说明；tool-result continuation marker 会用通用原因 fail 对应 run，
  不会恢复或继续远程发送。

验证命令：

```bash
ANDROID_HOME=$HOME/android-sdk ANDROID_SDK_ROOT=$HOME/android-sdk ./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteSendDisclosurePersistsNonSensitiveMarkerUntilDecision' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.selectingInstalledLocalModelClearsPendingRemoteSendMarker' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.startupConsumesPendingRemoteSendMarkerFailClosedWithoutPromptLeak' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.startupConsumesToolContinuationMarkerAndFailsModelRun'

ANDROID_HOME=$HOME/android-sdk ANDROID_SDK_ROOT=$HOME/android-sdk ./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest' \
  --tests 'com.bytedance.zgx.pocketmind.data.RemoteModelRepositoryTest'
```

结果：

- 通过：目标 JVM 测试验证 marker 保存/清理、启动消费、无 prompt 泄漏、无远程调用、
  tool continuation run fail-closed，以及 pending 远程发送期间切到已安装本地模型会清理
  marker 和 pending disclosure。
- 通过：完整 `PocketMindViewModelTest` 与 `RemoteModelRepositoryTest` 回归。
- 未执行：真机 instrumentation、arm64/x86 模拟器；本轮按要求只做本地 JVM 验证。

## 2026-06-21 Model License Metadata Freshness Gate

本轮覆盖项：

- `scripts/verify_model_license_review.sh` 要求
  `docs/model_license_metadata.json` 的 `recordedAt` 为严格 UTC
  `YYYY-MM-DDTHH:MM:SSZ`、不可来自未来、且默认不超过
  `MODEL_LICENSE_METADATA_MAX_AGE_DAYS=30`。
- metadata `modelSha` 从“非空字符串”收紧为 40-64 位 hex，避免把占位文案当成上游
  API SHA 证据。
- `scripts/test_validation_scripts.sh` 增加 stale / future / non-UTC
  metadata 和 invalid `modelSha` 负例；静态通过用例显式拉长 freshness window，避免测试
  fixture 随时间漂移。
- `docs/release_checklist.md` 同步 release 要求：模型 metadata 必须新鲜、UTC、含上游
  `modelSha`，并且人工 review date 不早于 metadata collection date。

验证命令：

```bash
bash -n scripts/verify_model_license_review.sh scripts/test_validation_scripts.sh
scripts/test_validation_scripts.sh
```

结果：

- 通过：validation script 自测覆盖模型许可证 metadata freshness 和 modelSha 负例。
- 未执行：真机 instrumentation、arm64/x86 模拟器；本轮只做本地 release gate 脚本验证。

## 2026-06-21 Privacy Review Notice SHA Convergence

本轮覆盖项：

- `docs/privacy_notice.md` 当前 SHA-256 同步到 `docs/privacy_review.json` 和
  release/security/legal 三份 pending privacy review evidence。
- 三份 pending evidence 的 `evidenceSha256` 重新计算并写回 privacy review record。
- `scripts/test_validation_scripts.sh` 增加 checked-in pending privacy review 防回归：
  允许因 `pending_manual_review`、reviewer/reviewDate 缺失、pending evidence 状态失败；
  不允许再出现 `notice-sha-mismatch`、`*-evidence-sha-mismatch` 或
  `*-evidence-notice-sha-mismatch`。

验证命令：

```bash
scripts/verify_privacy_review.sh --report /tmp/pocketmind-privacy-review.properties || true
bash -n scripts/verify_privacy_review.sh scripts/test_validation_scripts.sh
scripts/test_validation_scripts.sh
```

结果：

- 通过：默认 checked-in privacy review verifier 仍 fail-closed，但失败原因只剩人工审批
  pending / reviewer / reviewDate / pending evidence 状态，未再出现 notice 或 evidence SHA
  mismatch。
- 未执行：真机 instrumentation、arm64/x86 模拟器；本轮只做本地 evidence/schema 验证。

## 2026-06-21 Model Capability Remote Boundary Gate

本轮覆盖项：

- `ModelProfile` / `ModelCapabilityProfile` 新增 `remoteEligible` 和
  `requiresRemoteSendConfirmation` 派生字段；远程 OpenAI-compatible backend 才能为
  true，本地 LiteRT 模型、记忆模型、动作模型和自定义本地模型均 fail-closed。
- `docs/model_capability_profiles.json` 对所有 profile/template 显式记录
  `requiresRemoteSendConfirmation`，文档契约测试从 runtime profile 反推 JSON，避免文档
  与运行时远程边界分叉。
- 远程 text-only profile 仍要求远程发送确认；vision capability 只影响图片输入能力，不降低
  remote confirmation 要求。

验证命令：

```bash
ANDROID_HOME=$HOME/android-sdk ANDROID_SDK_ROOT=$HOME/android-sdk ./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.ModelCatalogTest' \
  --tests 'com.bytedance.zgx.pocketmind.docs.ModelCapabilityProfilesDocumentationTest' \
  --tests 'com.bytedance.zgx.pocketmind.RemoteModelConfigTest'
```

结果：

- 通过：本地 JVM 单测覆盖 catalog、remote config 和文档 JSON 契约。
- 未执行：真机 instrumentation、arm64/x86 模拟器；本轮按要求只做本地验证。

## 2026-06-21 Release Flow Evidence Semantic Field Gate

本轮覆盖项：

- `upgradeInstall` formal release-flow evidence 新增升级安装语义字段：
  `adb install -r`、firstInstallTime 保留、lastUpdateTime 更新、versionCode 增长和
  instrumentation coverage。
- `encryptedApiKeyClear`、`sessionPersistence`、`memoryControls`、
  `remindersAfterReboot`、`accessibilityText`、`recentMediaOcr` 不再只靠
  `releaseFlowPassed=true`；正式 recorder、candidate collector、release validation
  verifier 和 flow-matrix approved-record checker 都要求机器可读的细字段。
- 新增弱证据负例：upgrade/session/recent-media-OCR 的 status-only flow evidence
  会被 `verify_release_validation_record.sh` fail-closed。
- `docs/release_checklist.md` 同步列出新字段，避免人工验收记录和机器 verifier
  的证据语义分叉。

验证命令：

```bash
bash -n scripts/record_release_flow_evidence.sh \
  scripts/collect_release_flow_matrix_evidence.sh \
  scripts/verify_release_validation_record.sh \
  scripts/test_validation_scripts.sh

scripts/test_validation_scripts.sh
```

结果：

- 通过：validation script tests 返回 `Validation script tests passed`。
- 通过：formal release-flow recorder 与 candidate collector 均输出新增 flow 语义字段。
- 通过：弱 upgrade/session/recent-media-OCR flow evidence 被 release validation
  verifier 拒绝，并输出对应缺失原因。
- 未执行：真机 instrumentation、arm64/x86 模拟器、release flow 人工验收；
  本轮只收紧本地证据契约，不声明任何设备/模拟器/人工验收通过。

## 2026-06-21 Intent Routing Runtime Trace and Eval Gate

本轮覆盖项：

- `AgentStep.IntentRouted` 接入运行时 trace：本地 Skill-first、ActionPlanner、
  remote tool planning、model tool call 和 no-action 都输出统一
  `IntentRoutingDecision`，用于解释“为什么选择/拒绝某个工具路径”。
- persisted trace JSON 只保存 `selectedPath`、tool/skill id、priority、accepted、
  confidence、rejection reason slug 和 confirmation flag，不写入原始用户输入。
- `AgentBehaviorActualTrace` 与 actual-trace JSONL 增加可选 routing 字段；
  `verify_ai_behavior_eval.sh` 会校验 routing path 枚举、routing tool allowlist、
  skill id 格式和 rejection reason slug，并在 planning trace diff 中透传
  `actualRoutingPath` / `actualRoutingToolName` / `actualRoutingSkillId` /
  `actualRoutingRejectionReason`。
- remote 单工具拒绝和 public-evidence batch fail-closed 拒绝都会记录
  `RemoteToolPlanning` rejected routing decision；no-action 答复记录
  `no_action_intent_detected`。

验证命令：

```bash
bash -n scripts/verify_ai_behavior_eval.sh scripts/test_validation_scripts.sh

ANDROID_HOME="$HOME/android-sdk" ANDROID_SDK_ROOT="$HOME/android-sdk" ./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentTraceStoreTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.eval.AiBehaviorPlanningTraceProjectorTest' \
  --tests 'com.bytedance.zgx.pocketmind.eval.AiBehaviorActualTraceGeneratorTest' \
  --tests 'com.bytedance.zgx.pocketmind.contracts.PhaseOneContractModelsTest'

scripts/test_validation_scripts.sh

ANDROID_HOME="$HOME/android-sdk" ANDROID_SDK_ROOT="$HOME/android-sdk" scripts/verify_local.sh
```

结果：

- 通过：定向 JVM 测试 5 个测试类全部通过。
- 通过：validation script tests 返回 `Validation script tests passed`，覆盖 routing
  evidence 字段透传和非法 routing path 负例。
- 通过：`scripts/verify_local.sh` 返回 `Local verification passed`，包含本地
  validation scripts、Debug/Release 构建、Debug unit tests、lint 和 Android artifact scan。
- 未执行：真机 instrumentation、arm64/x86 模拟器、real-app-search eval；
  按本轮目标暂时跳过所有设备/模拟器验证，本条不声明真机或模拟器通过。

## 2026-06-21 Release Emulator ABI Evidence Gate

本轮覆盖项：

- `verify_release_validation_record.sh` 新增 release ABI gate：approved release
  validation 中的顶层 emulator regression、嵌套 emulator report、API matrix report
  和嵌套 device report 都必须包含 `arm64-v8a`，且不能包含 `x86` / `x86_64`。
- `test_validation_scripts.sh` 增加 x86/x86_64 顶层 emulator release evidence
  负例，以及 `arm64-v8a,x86_64` 混合 API matrix 负例。
- 模拟器 report 冒充 physical device evidence 的负例现在断言具体
  `physical-device-serial-invalid` 和 `physical-device-report-serial-is-emulator`
  reason，不再只看 `status=failed`。
- `release_checklist` / `release_readiness` 同步明确：x86 模拟器只能作为
  developer smoke，不能作为 release evidence，也不能混入 API matrix ABI 列表。

验证命令：

```bash
bash -n scripts/verify_release_validation_record.sh scripts/test_validation_scripts.sh

scripts/test_validation_scripts.sh
```

结果：

- 通过：`scripts/test_validation_scripts.sh` 返回 `Validation script tests passed`。
- 未执行：真机 instrumentation、arm64/x86 模拟器启动、API matrix emulator regression；
  本轮只验证本地脚本门禁和 fixture，不声明任何设备或模拟器流程通过。

## 2026-06-21 Custom Local Model Capability Profile Boundary

本轮覆盖项：

- `ModelCatalog` 新增 `custom-local-chat` text-only profile template，用于自定义导入模型；
  它只声明 `TextGeneration`，不声明 vision、embedding、mobile action、context window
  或 preferred backend。
- `InstalledModelSummary.capabilityProfile` 和 `ChatUiState.activeLocalCapabilityProfile`
  成为 active local profile 的单一入口；推荐模型走 catalog profile，自定义模型走
  `custom-local-chat`，unknown/stale recommended id 返回 null。
- `PocketMindViewModel` 的 `modelHealth.profileId`、`loadModel()` runtime capabilities、
  `localMaxTotalTokens` 和 `localPreferredBackends` 全部改走 active capability profile，
  避免自定义 active 模型在健康状态、runtime 配置或 evidence 中回退显示为
  `chat-e2b` / 默认视觉 profile。
- `modelHealthForCurrentSelection()` 的 `Verified` 状态现在要求 active model 可解析为 usable
  capability profile；unknown/stale recommended id 即使带有 `VerifiedRecommended` 标记，也只报告
  `InstalledUnverified`，并保持 vision fail-closed。
- `docs/model_capability_profiles.json` 增加 `customLocalTemplates`，由
  `ModelCapabilityProfilesDocumentationTest` 绑定到 Kotlin custom profile。

验证命令：

```bash
ANDROID_HOME="$HOME/android-sdk" ANDROID_SDK_ROOT="$HOME/android-sdk" ./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.ModelCatalogTest' \
  --tests 'com.bytedance.zgx.pocketmind.ChatUiStateModelVerificationTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.loadCustomImportedModelConfiguresTextOnlyRuntimeCapabilities' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.unknownRecommendedActiveModelDoesNotReportDefaultOrVerifiedProfile' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.loadModelConfiguresRuntimeFromActiveModelCapabilityProfile' \
  --tests 'com.bytedance.zgx.pocketmind.docs.ModelCapabilityProfilesDocumentationTest'

scripts/test_validation_scripts.sh

ANDROID_HOME="$HOME/android-sdk" ANDROID_SDK_ROOT="$HOME/android-sdk" \
  VERIFY_AI_BEHAVIOR_EVAL=0 VERIFY_PERF_BASELINE=0 VERIFY_CONTRACT_TESTS=1 \
  ARTIFACT_DIR=build/verification/release-gate-contract-custom-local-profile-rerun \
  scripts/verify_release_gate.sh

python3 -m json.tool docs/model_capability_profiles.json >/dev/null
git diff --check
```

结果：

- 通过：targeted JVM 测试返回 `BUILD SUCCESSFUL`，覆盖 custom local text-only runtime
  capabilities、custom `modelHealth.profileId=custom-local-chat`、unknown active model 不回退
  `DEFAULT_CHAT_MODEL_ID` 且不报告 verified/vision。
- 通过：`scripts/test_validation_scripts.sh` 返回 `Validation script tests passed`。
- 通过：contract-only release gate 返回 `Release gate passed`；
  本轮仍显式跳过 AI behavior/perf release evidence，不替代最终 public release gate。
- 通过：`docs/model_capability_profiles.json` JSON 格式检查和 `git diff --check` 返回 0。
- 未执行：真机 instrumentation、真实模型加载、real-app-search 真机 eval、arm64 physical perf
  baseline；这些按当前目标暂时跳过，不能作为 physical release evidence。

## 2026-06-21 Model Capability Evidence 与 Allowed-Failure 安全门禁

本轮覆盖项：

- 新增 `docs/model_capability_profiles.json`，把 `ModelCatalog.recommendedProfiles()` 中
  chat、memory embedding、mobile action 和本地 vision/context/backend 能力导出为
  machine-readable evidence，并增加远程 OpenAI-compatible text/vision template。
- 新增 `ModelCapabilityProfilesDocumentationTest`，要求 JSON 中的
  `capabilities.chat/vision/memoryEmbedding/mobileAction`、`inputModalities`、
  `features`、`contextWindowTokens`、`preferredLocalBackends`、
  SHA-256 与 `sourceRevision` 必须与 Kotlin catalog / remote profile 完全一致。
- `scripts/verify_release_gate.sh` 的 contract-test 集合纳入
  `ModelCapabilityProfilesDocumentationTest`，避免模型能力文档漂移绕过 release gate。
- 收紧 `AgentBehaviorPlanningTraceDiff` 和 `scripts/verify_ai_behavior_eval.sh`：
  `allowedFailureModes` 只能解释工具/确认降级，不能掩盖 `risk`、`privacy`、
  `LocalOnly`、`remoteEligible` 漂移；若 expected confirmation 是 `FailClosed`，
  actual confirmation 也必须保持 `fail_closed`。
- planning trace diff 现在输出 `allowedFailureModeMatches`、
  `allowedFailureSafetyMatches`、`safetyBoundaryMatches` 和
  `failClosedInvariantMatches`，便于 release reviewer 解释为什么某个 allowed failure
  仍被拒绝。

验证命令：

```bash
ANDROID_HOME="$HOME/android-sdk" ANDROID_SDK_ROOT="$HOME/android-sdk" ./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.eval.AiBehaviorPlanningTraceProjectorTest' \
  --tests 'com.bytedance.zgx.pocketmind.eval.AiBehaviorActualTraceGeneratorTest' \
  --tests 'com.bytedance.zgx.pocketmind.docs.ModelCapabilityProfilesDocumentationTest' \
  --tests 'com.bytedance.zgx.pocketmind.docs.CapabilityMatrixDocumentationTest' \
  --tests 'com.bytedance.zgx.pocketmind.docs.ModelManifestDocumentationTest' \
  --tests 'com.bytedance.zgx.pocketmind.docs.AgentCoreDocumentationTest'

scripts/test_validation_scripts.sh

scripts/verify_ai_behavior_eval.sh --require-boundary-map \
  --actual-trace build/verification/ai-behavior-actual-trace-collector-memory-forget-v20/ai-behavior-actual-trace.jsonl \
  --trace-diff /tmp/pocketmind-ai-behavior-allowed-failure-tighten.jsonl \
  --require-actual-trace \
  --require-runtime-trace-source \
  --report /tmp/pocketmind-ai-behavior-allowed-failure-tighten.properties

ANDROID_HOME="$HOME/android-sdk" ANDROID_SDK_ROOT="$HOME/android-sdk" \
  VERIFY_AI_BEHAVIOR_EVAL=0 VERIFY_PERF_BASELINE=0 VERIFY_CONTRACT_TESTS=1 \
  ARTIFACT_DIR=build/verification/release-gate-contract-model-profile \
  scripts/verify_release_gate.sh

python3 -m json.tool docs/model_capability_profiles.json >/dev/null
bash -n scripts/verify_ai_behavior_eval.sh scripts/test_validation_scripts.sh scripts/verify_release_gate.sh
git diff --check

ANDROID_HOME="$HOME/android-sdk" ANDROID_SDK_ROOT="$HOME/android-sdk" \
  scripts/verify_local.sh
```

结果：

- 通过：targeted JVM 测试返回 `BUILD SUCCESSFUL`，覆盖模型 profile 文档合同、
  AI behavior actual trace 生成器、planning trace projector，以及 allowed failure
  不能掩盖安全边界漂移 / FailClosed 弱化的负例。
- 通过：`scripts/test_validation_scripts.sh` 返回 `Validation script tests passed`；
  新增 shell 负例会拒绝带白名单 failure mode 但 risk/privacy/LocalOnly/RemoteEligible
  漂移或 FailClosed 被降级的 actual trace。
- 通过：严格 AI behavior eval 返回
  `AI behavior eval fixtures passed: 31 cases across 7 categories and 6 MVP scenarios`。
- 通过：contract-only release gate 返回 `Release gate passed`；
  `build/verification/release-gate-contract-model-profile/contract-tests.properties`
  记录 `status=passed`。本轮按目标暂时关闭 `VERIFY_AI_BEHAVIOR_EVAL` 和
  `VERIFY_PERF_BASELINE`，对应报告为显式 `status=skipped`，不替代最终 release evidence。
- 通过：JSON 格式检查、shell 语法检查和 `git diff --check` 返回 0。
- 通过：`scripts/verify_local.sh` 返回 `Validation script tests passed`、
  Gradle `BUILD SUCCESSFUL`、`Android artifact scan passed` 和
  `Local verification passed`，覆盖 JVM、lint、debug/androidTest APK、release APK/AAB
  构建与 artifact 扫描。
- 未执行：真机 instrumentation、real-app-search 真机 eval、arm64 physical perf baseline；
  这些按当前目标暂时跳过，不能作为 physical release evidence。

## 2026-06-21 Store Policy Mechanical Drift Normalization

本轮覆盖项：

- `docs/store_policy_record.json` 同步当前 `docs/privacy_notice.md` SHA-256：
  `6d1f1f3424fc80a92fa9ffc5c0cedcb127921d460993e9bfe5fbc0026cf62bbc`。
- Store listing 文案补充明确的 `confirmed device actions` 表述，满足 verifier 对
  confirmed actions disclosure 的机器检查。
- Store policy permissions 同步当前 Android manifest 中新增的
  `com.android.alarm.permission.SET_ALARM`、`android.permission.FOREGROUND_SERVICE` 和
  `android.permission.FOREGROUND_SERVICE_SPECIAL_USE`，并保留对应用途说明。
- `docs/store_policy_review_evidence/pending.properties` 仅同步 privacy notice SHA；
  仍保持 `status=pending`、`approvalStatus=not-approved` 和 candidate target，不伪造审批。
- `docs/store_policy_record.json` 同步 pending evidence 文件 SHA：
  `4447efb08e1793217554743e26b23049562c1269a5a734a2fb43435da8c7087b`。

验证命令：

```bash
scripts/privacy_scan.sh --report /tmp/pocketmind-privacy-current.properties app/src/main docs scripts

scripts/verify_store_policy_record.sh \
  --file docs/store_policy_record.json \
  --report /tmp/pocketmind-store-after-mechanical.properties
```

结果：

- 通过：privacy scan 返回 `status=passed`、`scanTargetCount=3`、`findingCount=0`。
- 符合预期失败：store policy verifier 不再报告 `privacy-notice-sha-mismatch`、
  `app-listing-confirmed-actions-missing`、`manifest-permissions-mismatch`、
  `review-evidence-sha-mismatch` 或 `review-evidence-privacy-notice-sha-mismatch`。
- 剩余 blocker 保留为人工/发布资料项：
  `status-not-approved`、`contact-email-placeholder`、`privacy-policy-url-placeholder`、
  `reviewer-missing`、`review-evidence-status-not-approved`、
  `review-evidence-approval-status-not-approved`、`review-evidence-target-invalid`、
  `review-date-missing`。

## 2026-06-21 CI Final Release Gate AI Behavior Actual Trace Input

本轮覆盖项：

- GitHub Actions `workflow_dispatch` 新增必填 `ai_behavior_actual_trace_file` 输入，用于指向
  final public release gate 可访问的 AI behavior actual trace JSONL。
- `final-release-gate` job 将该输入传入 `AI_BEHAVIOR_ACTUAL_TRACE_FILE`，并在
  `PUBLIC_RELEASE=1 scripts/verify_release_gate.sh` 调用环境中继续传递。
- `scripts/test_validation_scripts.sh` 新增 workflow 静态合同检查：`ai_behavior_actual_trace_file`
  必须是 required string，且 final gate 必须同时包含 `AI_BEHAVIOR_ACTUAL_TRACE_FILE` 和
  `inputs.ai_behavior_actual_trace_file`。
- `verify_release_gate.sh` 未放松：`PUBLIC_RELEASE=1` 仍强制
  `REQUIRE_AI_BEHAVIOR_ACTUAL_TRACE=1` 与 `REQUIRE_AI_BEHAVIOR_RUNTIME_TRACE_SOURCE=1`。

验证命令：

```bash
bash -n scripts/verify_release_gate.sh scripts/test_validation_scripts.sh

scripts/test_validation_scripts.sh

scripts/verify_ai_behavior_eval.sh --require-boundary-map \
  --actual-trace build/verification/ai-behavior-actual-trace-collector-memory-forget-v20/ai-behavior-actual-trace.jsonl \
  --trace-diff /tmp/pocketmind-ci-final-gate-ai-trace-diff.jsonl \
  --require-actual-trace \
  --require-runtime-trace-source \
  --report /tmp/pocketmind-ci-final-gate-ai.properties

ARTIFACT_DIR=/tmp/pocketmind-ci-final-gate-public-dry-run \
  PUBLIC_RELEASE=1 \
  VERIFY_CONTRACT_TESTS=0 \
  EXPECTED_SIGNING_CERT_SHA256=0000000000000000000000000000000000000000000000000000000000000000 \
  AI_BEHAVIOR_ACTUAL_TRACE_FILE=build/verification/ai-behavior-actual-trace-collector-memory-forget-v20/ai-behavior-actual-trace.jsonl \
  scripts/verify_release_gate.sh
```

结果：

- 通过：shell 语法检查返回 0。
- 通过：`scripts/test_validation_scripts.sh` 返回 `Validation script tests passed`，覆盖 workflow
  actual trace 输入和 final gate marker。
- 通过：strict `verify_ai_behavior_eval.sh` 返回 `AI behavior eval fixtures passed: 31 cases across
  7 categories and 6 MVP scenarios`，报告
  `/tmp/pocketmind-ci-final-gate-ai.properties` 记录 `requireActualTrace=1`、
  `requireRuntimeTraceSource=1`、`traceDiffMismatchCount=0`。
- 符合预期失败：public gate dry-run 使用
  `AI_BEHAVIOR_ACTUAL_TRACE_FILE=build/verification/ai-behavior-actual-trace-collector-memory-forget-v20/ai-behavior-actual-trace.jsonl`
  后，`ai-behavior-eval.properties` 记录 `status=passed`、
  `requireActualTrace=1`、`requireRuntimeTraceSource=1`、
  `actualTraceSha256=0e84a385f57d4a259984e316f728547c47fdc7d7d0f2efe913eeab67a81363a1`、
  `traceDiffMismatchCount=0`；随后 gate 停在
  `failedTarget=android-artifact-scan`、
  `failedReason=REQUIRE_AAB-but-release-aab-missing`，说明本轮修复的是 CI actual trace
  参数链路，不是生产签名/AAB blocker。
- 限制：本轮只修 CI 参数链路，不改变 release record、签名 AAB、perf baseline、privacy/store/model
  license 等 public release blocker 状态。

## 2026-06-21 Real-App Search Resolver Replay Fixtures and JD Profile Runtime Coverage

本轮覆盖项：

- `AppInteractionProfiles` 扩充淘宝、高德、京东和浏览器族的真实搜索入口提示词：
  淘宝搜索发现/搜索宝贝、 高德“你要去哪儿”/公交地铁、京东商品/店铺搜索、Quark/UC
  常见“搜索词或网址”地址栏。
- `UiTargetResolver` 和 `PocketMindAccessibilityService` 同步搜索入口强语义与负向语义，
  让 explain 证据路径和实际 Accessibility 点击路径都降低拍照搜索、相机、扫一扫、找同款等
  视觉搜索入口的优先级。
- 新增 UIAutomator XML replay 测试基础设施：
  `UiAutomatorDumpReplayTest` 从 `app/src/test/resources/ui_dumps/real_app_search/`
  读取 XML，解析 `text/content-desc/class/package/bounds/clickable/editable/scrollable/enabled`
  为 `ScreenStateSnapshot` 后回放 resolver。
- 新增淘宝和高德真实形态 fixture，固定“文本搜索入口胜过拍照/找同款/大结果容器”和
  “高德目的地搜索入口胜过地图画布/POI 容器”的回归。
- `BuiltInSkillRuntimeTest` 和 `AgentLoopRuntimeTest` 将京东加入 common open-app-search
  profile 覆盖，验证 `打开京东搜索数据线` 的 skill plan、expected package 和 runtime
  搜索结果验证链。

验证命令：

```bash
ANDROID_HOME=/data00/home/zouguoxue/android-sdk ./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.device.UiAutomatorDumpReplayTest' \
  --tests 'com.bytedance.zgx.pocketmind.device.UiTargetResolverTest' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest.plansCurrentAppUiSkillsAsObserveActVerifyTemplates' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.openAppUiSearchRuntimeFlowCoversCommonAppProfiles'

git diff --check

scripts/test_validation_scripts.sh

ANDROID_HOME=/data00/home/zouguoxue/android-sdk scripts/verify_local.sh

/data00/home/zouguoxue/android-sdk/platform-tools/adb devices -l
```

结果：

- 通过：目标 JVM 测试返回 `BUILD SUCCESSFUL`，覆盖 UIAutomator XML replay、resolver ranking、
  Accessibility runtime 同步编译、京东 skill/runtime profile。
- 通过：`git diff --check` 返回 0。
- 通过：`scripts/test_validation_scripts.sh` 返回 `Validation script tests passed`。
- 通过：`scripts/verify_local.sh` 返回 `Local verification passed`，并完成 JVM tests、
  AndroidTest APK 组装、debug APK、release APK/AAB、lint 和 artifact scan。
- 限制：新增 XML 是可回放 fixture，不声明为 2026-06-17 真机 dump；当前 workspace 仍没有
  可引用的真实 `real-app-search` 失败 dump artifact。后续真机失败 dump 应直接补进同一
  `ui_dumps/real_app_search/` 回放路径。
- 未执行真机：`adb devices -l` 只输出 `List of devices attached` 表头，没有可见
  authorized device；机器可读状态仍为 `failedTarget=physical-device-smoke`、
  `reason=adb-no-authorized-device`。本轮没有新增 physical-device real-app-search pass evidence。

## 2026-06-22 Real-App Search Ranked Evidence Gate

本轮覆盖项：

- `DeviceControlEvalResultFormatter` 的 ranked candidates JSON 增加 top-level
  `confidence`、`finalScore`、`riskPenalty`、`noisePenalty`、`totalPenalty`，保留原
  nested `score`，便于脚本门禁直接检查候选证据。
- debug eval receiver 明确输出 `targetResolution.candidateTotalCount` 和
  `targetResolution.archivedCandidateCount`，说明真实 ranked list 与归档前 5 个候选的边界。
- `scripts/run_real_app_search_eval.sh` 的 case artifact 增加
  `expected_package_name`、`expected_app_name`、`failure_kind`、
  `target_resolution_evidence_file/sha256`、`ranked_candidates_file/sha256`，并将
  window dump 拆成独立 `window_dump_file/sha256`。
- `scripts/test_validation_scripts.sh` 的 fake ADB 覆盖淘宝 `search_entry_not_found` 和
  高德 `editable_not_found` 两类失败，要求 candidates JSON 含 label、bounds、
  actionability、profile hint、penalty 和 final score。
- 新增 JD 与 Quark UIAutomator replay fixture，覆盖京东搜索框、浏览器地址栏胜过 feed /
  扫码入口；新增 resolver evidence contract 测试，固定 bounds、hint、penalty 与
  confidence/finalScore 关系。

验证命令：

```bash
bash -n scripts/run_real_app_search_eval.sh scripts/test_validation_scripts.sh

ANDROID_HOME=$HOME/android-sdk ANDROID_SDK_ROOT=$HOME/android-sdk ./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.device.UiAutomatorDumpReplayTest' \
  --tests 'com.bytedance.zgx.pocketmind.device.UiTargetResolverTest'

./scripts/test_validation_scripts.sh

git diff --check
```

结果：

- 通过：shell 语法检查返回 0。
- 通过：目标 JVM 测试返回 `BUILD SUCCESSFUL`，覆盖淘宝/高德/JD/Quark replay 与
  resolver evidence contract。
- 通过：`scripts/test_validation_scripts.sh` 返回 `Validation script tests passed`，覆盖
  ranked candidates 独立文件、target resolution evidence 文件、SHA-256、failure kind、
  expected package/app 和 window dump 归档。
- 通过：`git diff --check` 返回 0。
- 未执行：真机 instrumentation、arm64/x86 模拟器；本轮按要求只做本地 JVM 与脚本验证。

## 2026-06-22 AI Behavior Remote Send Confirmation Gate

本轮覆盖项：

- `scripts/verify_ai_behavior_eval.sh` 将 `remote_send_confirmation` 纳入必需确认类型覆盖，
  不再只要求 tool / second / fail-closed confirmation；缺少远程发送确认 fixture 会
  fail-closed 为 `missing-confirmation-coverage:remote_send_confirmation`。
- `AiBehaviorEvalFixturesTest` 增加 JVM 断言，固定离线 eval suite 必须包含远程发送确认场景。
- `scripts/collect_ai_behavior_actual_trace.sh` 调用 verifier 时强制
  `--require-actual-trace`，并显式拒绝 `traceDiffMismatchCount != 0`，避免 collector
  evidence 在 actual trace 与 fixture 不一致时仍写出 `status=passed`。
- `scripts/test_validation_scripts.sh` 增加两个负例：删除 remote-send confirmation 覆盖必须失败；
  fake Gradle 生成 mismatched actual trace 时 collector 必须失败并写出
  `reason=trace-diff-mismatch`。

验证命令：

```bash
bash -n scripts/collect_ai_behavior_actual_trace.sh scripts/verify_ai_behavior_eval.sh scripts/test_validation_scripts.sh

scripts/verify_ai_behavior_eval.sh --require-boundary-map \
  --report build/verification/ai-behavior-remote-send-confirmation-gate.properties

ANDROID_HOME=$HOME/android-sdk ANDROID_SDK_ROOT=$HOME/android-sdk ./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.eval.AiBehaviorEvalFixturesTest' \
  --tests 'com.bytedance.zgx.pocketmind.eval.AiBehaviorActualTraceGeneratorTest'

ARTIFACT_DIR=build/verification/ai-behavior-actual-trace-remote-send-gate \
  ANDROID_HOME=$HOME/android-sdk ANDROID_SDK_ROOT=$HOME/android-sdk \
  scripts/collect_ai_behavior_actual_trace.sh

./scripts/test_validation_scripts.sh
```

结果：

- 通过：AI behavior fixture gate 返回 `31 cases across 7 categories and 6 MVP scenarios`，
  report 中 `confirmationBreakdown=fail_closed:8,none:14,remote_send_confirmation:1,second_confirmation:5,tool_confirmation:3`。
- 通过：目标 JVM 测试返回 `BUILD SUCCESSFUL`。
- 通过：actual trace collector 返回 `status=passed`，`requireActualTrace=1`、
  `requireRuntimeTraceSource=1`、`traceDiffMatchedCount=21`、
  `traceDiffAllowedFailureCount=10`、`traceDiffMissingActualCount=0`、
  `traceDiffMismatchCount=0`、`traceDiffExtraActualCount=0`。
- 通过：`scripts/test_validation_scripts.sh` 返回 `Validation script tests passed`，覆盖 remote-send
  confirmation 缺失负例和 collector mismatch 负例。
- 未执行：真机 instrumentation、arm64/x86 模拟器；本轮按要求只做本地 JVM 与脚本验证。

## 2026-06-21 Memory Forget Runtime Evidence and Local Gate

本轮覆盖项：

- `memory_forget_language` 的 eval contract 与现有产品行为对齐：显式“忘记我的回答语言偏好”
  是本地记忆删除命令，走 `MemoryRepository` / 显式 forget parser，不进入 remote runtime，
  不需要工具确认；删除失败才应作为产品缺口处理。
- `AiBehaviorActualTraceGeneratorTest` 不复制 fixture expected 值，而是用真实
  `MemoryRepository` 种子数据、`explicitUserPreferenceForgetFrom()` 解析和
  `forgetPreference()` / `forgetUserFact()` 删除路径生成 actual trace。
- actual trace 断言覆盖：无工具调用、`none` confirmation、`sensitive`、
  `LocalOnly`、`remoteEligible=false`，并确认语言偏好被删除、无关简洁回答偏好仍可召回。

验证命令：

```bash
ANDROID_HOME=/data00/home/zouguoxue/android-sdk ./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.eval.AiBehaviorActualTraceGeneratorTest' \
  --tests 'com.bytedance.zgx.pocketmind.memory.MemoryRepositoryTest.forgetPreferenceCanDeleteResponsePreferenceFamily' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.forgetPreferenceFamilyCommandDeletesMatchingPreferenceAndBypassesRemoteRuntime' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.forgetPreferenceCommandDeletesMemoryAndBypassesRouterAndRemoteRuntime'

ANDROID_HOME=/data00/home/zouguoxue/android-sdk \
  ARTIFACT_DIR=build/verification/ai-behavior-actual-trace-collector-memory-forget-v20 \
  scripts/collect_ai_behavior_actual_trace.sh

git diff --check

scripts/test_validation_scripts.sh

ANDROID_HOME=/data00/home/zouguoxue/android-sdk scripts/verify_local.sh

/data00/home/zouguoxue/android-sdk/platform-tools/adb devices -l
```

结果：

- 通过：目标 JVM 测试返回 `BUILD SUCCESSFUL`，覆盖 actual trace 生成器、记忆偏好族删除和
  ViewModel 显式本地遗忘不走 router/remote runtime 的产品合同。
- 通过：`collect_ai_behavior_actual_trace.sh` 生成
  `build/verification/ai-behavior-actual-trace-collector-memory-forget-v20/ai-behavior-actual-trace-collection.properties`，
  记录 `caseCount=31`、`actualTraceSourceBreakdown=agent_loop_runtime:31`、
  `traceDiffMatchedCount=21`、`traceDiffAllowedFailureCount=10`、
  `traceDiffMismatchCount=0`、`traceDiffMissingActualCount=0`、
  `traceDiffExtraActualCount=0`。
- 审计绑定：collection report 记录
  `actualTraceSha256=0e84a385f57d4a259984e316f728547c47fdc7d7d0f2efe913eeab67a81363a1`、
  `traceDiffSha256=6ef4099ed3b55b2123b6d94c36210a62dab368e23add7cab6fca13ae1ee6b2e2`、
  `evalReportSha256=c215dc4aacabc8258b96bb2a78b0a9d8a5722ec00fb98be2a60e696d29ee81d6`。
- 改善：上一轮剩余的 `memory_forget_language` mismatch 已关闭；本地 behavior actual trace
  当前没有 mismatch，剩余 10 条均为显式 allowed failure。
- 通过：`git diff --check` 返回 0。
- 通过：`scripts/test_validation_scripts.sh` 返回 `Validation script tests passed`。
- 通过：`scripts/verify_local.sh` 返回 `Local verification passed`，并完成 JVM tests、
  AndroidTest APK 组装、debug APK、release APK/AAB、lint 和 artifact scan。
- 未执行真机：`adb devices -l` 只输出 `List of devices attached` 表头，没有可见
  authorized device；机器可读状态仍为 `failedTarget=physical-device-smoke`、
  `reason=adb-no-authorized-device`。本轮没有新增 physical-device instrumentation evidence。

## 2026-06-20 Sequential App Search Back Runtime, Checkpoint Evidence, and Local Gate

本轮覆盖项：

- `BuiltInSkillRuntime` 不再让完整顺序请求 `打开淘宝搜索耳机，然后返回` 被
  `open_app_ui_search_skill` 一次性吞掉；首段只规划 `打开淘宝搜索耳机`，尾段 `返回`
  作为 `CURRENT_PAGE_SIMPLE_INTERACTION_SKILL` 进入 checkpoint。
- `AgentLoopRuntime` 的顺序段计数按 `SkillPlanned` 中的 tool request ids 把多步 skill
  归并为一个用户意图段，避免 8 步 App 搜索 skill 被误算成 8 个顺序段。
- `AgentTraceStore` 的 `SkillPlanned` trace JSON 增加 `toolStepCount` 和
  `toolRequestIds`，让持久化 trace 恢复时也能解释 tool 属于哪个 skill segment。
- 显式顺序尾段的 deterministic composite skill 可以在本地证据 continuation 之前规划；
  skill 内部模型步骤仍通过 `blocksSequentialTail` 保持优先。
- `AgentBehaviorTraceProjector` 对低风险 app-control trace 过滤 `ui_wait` /
  `observe_current_screen` 支撑步骤，并把低风险设备导航投影为 eval 侧 `low`；
  external outcome 恢复失败投影为 `external_outcome_missing`。
- `AiBehaviorActualTraceGeneratorTest` 真实驱动淘宝搜索后返回、app-control checkpoint budget、
  mixed public/private remote batch fail-closed、外部 App outcome 不可恢复 fail-closed 和
  reminder reschedule；不复制 expected fixture。

验证命令：

```bash
ANDROID_HOME=/data00/home/zouguoxue/android-sdk ./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest.plansDeviceUiTemplateSkillsWithDeterministicSteps' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.openAppUiSearchThenBackRequestsCheckpointBeforePressingBack' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.openAppUiSearchThenBackPressesBackAfterUserConfirmsCheckpoint' \
  --tests 'com.bytedance.zgx.pocketmind.eval.AiBehaviorActualTraceGeneratorTest.recoveryReminderRescheduledTraceUsesRealReminderReschedulerSummary'

ANDROID_HOME=/data00/home/zouguoxue/android-sdk ./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.eval.AiBehaviorActualTraceGeneratorTest.writesAgentLoopRuntimeActualTraceJsonl' \
  --tests 'com.bytedance.zgx.pocketmind.eval.AiBehaviorActualTraceGeneratorTest.recoveryReminderRescheduledTraceUsesRealReminderReschedulerSummary' \
  --tests 'com.bytedance.zgx.pocketmind.eval.AiBehaviorPlanningTraceProjectorTest.projectsRestoredPendingConfirmationWithoutExecutedTools'

ANDROID_HOME=/data00/home/zouguoxue/android-sdk \
  ARTIFACT_DIR=build/verification/ai-behavior-actual-trace-collector-app-search-back-v18 \
  scripts/collect_ai_behavior_actual_trace.sh

scripts/test_validation_scripts.sh

find scripts -maxdepth 1 -type f -name '*.sh' -exec bash -n {} \;

ANDROID_HOME=/data00/home/zouguoxue/android-sdk scripts/verify_local.sh

git diff --check

/data00/home/zouguoxue/android-sdk/platform-tools/adb devices -l
```

结果：

- 通过：目标 JVM 测试返回 `BUILD SUCCESSFUL`，覆盖 Wi-Fi/App 搜索 skill arbitration、
  搜索后返回 checkpoint、确认后执行返回、真实 `ReminderRescheduler` 恢复证据。
- 通过：`collect_ai_behavior_actual_trace.sh` 生成
  `build/verification/ai-behavior-actual-trace-collector-app-search-back-v18/ai-behavior-actual-trace-collection.properties`，
  记录 `caseCount=31`、`actualTraceSourceBreakdown=agent_loop_runtime:31`、
  `traceDiffMatchedCount=20`、`traceDiffAllowedFailureCount=10`、
  `traceDiffMismatchCount=1`、`traceDiffMissingActualCount=0`、
  `traceDiffExtraActualCount=0`。
- 审计绑定：collection report 记录
  `actualTraceSha256=c2ad57322f0801d37eab0a043c0142f43d008c18be3af282713e68150da3ea9a`、
  `traceDiffSha256=8a6312f41964a69029418734dc37571295687bd9b406596c65f9524253bd3cc2`、
  `evalReportSha256=d6269802ebd34e58dcffc80dfce3665c3bc8d8fd89f43261b3b27d004acbe07c`。
- 改善：`sequence_taobao_search_back` 和 `runtime_app_search_checkpoint_budget`
  均变为 `matched`；`runtime_mixed_batch_rejected` 和
  `recovery_external_outcome_fail_closed` 变为带 failureMode 的 allowed failure。
- 剩余 mismatch：`memory_forget_language` 仍为 `RemoteEligible/low/no confirmation`，
  说明本地记忆删除确认语义尚未实现；这是真实能力缺口，未用 fixture 期望值掩盖。
- 通过：`scripts/test_validation_scripts.sh` 返回 `Validation script tests passed`。
- 通过：shell 语法检查返回 0。
- 通过：`scripts/verify_local.sh` 返回 `Local verification passed`，并完成 JVM tests、
  AndroidTest APK 组装、debug APK、release APK/AAB、lint 和 artifact scan。
- 通过：`git diff --check` 返回 0。
- 未执行真机：`adb devices -l` 只输出 `List of devices attached` 表头，没有可见
  authorized device；机器可读状态为 `failedTarget=physical-device-smoke`、
  `reason=adb-no-authorized-device`。本轮没有新增 physical-device instrumentation evidence。

## 2026-06-20 AI Behavior Actual Trace Runtime Evidence, Remote Scope Filter, OCR Evidence, Recovery Restore, and Gaode Evidence Guard

本轮覆盖项：

- `AgentBehaviorTraceProjector` 将 `RiskLevel.HighExternalSend` 投影为 eval 侧
  `sensitive`，用于表达“本地隐私上下文 + 外发/分享”的发布门禁语义；不改变线上
  `RiskLevel`、SafetyPolicy 或工具执行策略。
- `AgentBehaviorTraceProjector` 不再把 no-tool answer 一律投为 `RemoteEligible/low`；
  会读取 `ContextLoaded.memoryHits`、`RunDataReceiptRecorded` 和
  `ModelOutputQualityGuardTriggered`，把本地记忆、LocalOnly receipt、质量保护和
  metadata-only recovery 投成机器可审计 actual trace。
- `AgentLoopRuntime.observeModelToolRequests()` 在 mixed remote tool batch 被拒时写入
  `attemptedToolNames`，让 actual trace 能从 runtime rejected result 还原模型真实尝试的
  public/private 混合工具批次。
- `AgentLoopRuntime.recordRemoteToolsExposed()` 现在按 `RemoteToolScope` 过滤远程可见工具快照：
  `PublicEvidenceOnly` 只暴露 public evidence 工具，`ModelPlanning` 只暴露远程模型规划允许的工具；
  私有屏幕文本、截图 OCR、未知工具即使被上层误传入，也不会出现在远程可见工具清单。
- `AgentBehaviorTraceProjector` 在 runtime 实际 `ToolRequested` 历史比初始 plan 更丰富时，
  使用真实步骤链投影工具顺序；同时保留 composite `SkillPlan` 中尚未执行的后续步骤，避免
  只看到第一步就截断分享类 skill evidence。
- `AgentBehaviorTraceProjector` 将无私密本地证据的低风险设备入口（例如
  `open_wifi_settings`）投影为 `low/RemoteEligible`，但对本地 OCR、屏幕文字、联系人等
  private local evidence 继续投影为 `sensitive/LocalOnly`。
- `AgentBehaviorTraceProjector` 归一化远程 OCR 边界失败：`read_recent_image_ocr` 远程发送
  阻断输出 `local_only_blocks_remote`，`read_recent_screenshot_ocr` 多截图请求输出
  `multi_screenshot_ocr_rejected`。
- `AiBehaviorActualTraceGeneratorTest` 对 memory、runtime quality guard、GPU fallback、
  trust-center metadata-only、remote private contacts、mixed public/private batch 等 case
  使用 runtime API 生成 actual evidence；不复制 fixture expected 值。
- `AiBehaviorActualTraceGeneratorTest` 真实驱动 `sequence_weather_then_wifi`：先让 runtime 规划
  `web_search`，喂入 Web 搜索成功结果，再由 `SequentialActionObservationReplanner` 规划
  `open_wifi_settings` 确认卡；同时真实驱动远程 OCR 摘录阻断和最近 5 张截图 OCR schema 拒绝。
- `AiBehaviorActualTraceGeneratorTest` 对远程私有屏幕文本/当前截图 OCR 使用中性 remote prompt
  进入 `GeneratingAnswer` 后再模拟远程工具请求，确保 actual evidence 来自 runtime
  fail-closed，而不是本地 planner 抢跑出的确认卡；同时真实驱动
  `sequence_search_then_share` 的 `web_search` -> `share_text` 二次确认链。
- `AgentBehaviorTraceProjector` 识别远程图片发送 receipt：`RunDataReceipt(destination=Remote,
  currentPromptPrivacy=RemoteEligible, imageAttachmentCount>0)` 投影为
  `remote_send_confirmation/medium/RemoteEligible`，对应真实 UI disclosure 后才调用远程
  runtime 的产品路径。
- `SharedInput` 现在为分享文本、PDF/Office/text preview、PDF 扫描页 OCR、图片 OCR 和图片附件
  生成 metadata-only `EvidenceReceiptSummary`；`SharedInputDraft` 将该 summary 带到
  `RunDataReceipt`，只记录 evidence 计数、来源类型、截断/低质量标记，不持久化 raw OCR 文本。
- `AgentBehaviorTraceProjector` 将 no-tool 本地截断 evidence receipt 投影为
  `truncated_local_evidence` allowed failure；`ocr_pdf_scan_truncated` 因此不再错误呈现为
  `RemoteEligible/low`，也不会硬路由到 `query_recent_files`。
- `AiBehaviorActualTraceGeneratorTest` 用真实 `RoomAgentTraceStore` pending confirmation 恢复路径驱动
  `recovery_confirmation_no_auto_execute`：进程重启后仅恢复确认卡，不写入 `UserConfirmed` 或
  `ToolObserved`，actual trace 输出 `actualTools=[]`、`tool_confirmation`、`medium/LocalOnly`。
- `AiBehaviorActualTraceGeneratorTest` 用 `RoomAgentTraceStore` 持久化恢复路径真实驱动
  `recovery_pending_payload_not_restored`：剪贴板摘要分享 pending 在重启后不可恢复，run
  fail-closed，pending 被清空，actual trace 记录 `pending_payload_not_restored`。
- `AiBehaviorPlanningTraceProjectorTest` 同步断言屏幕读取后分享的 skill chain 必须输出
  `SecondConfirmation`、`LocalOnly`、`Sensitive`，并新增 weather→Wi-Fi、远程 OCR 和多截图
  OCR failureMode 投影断言。
- `AgentLoopRuntimeTest.remoteMixedBatchRejectionRecordsAttemptedToolNames` 固化 mixed batch
  reject evidence，防止 rejected result 丢失模型尝试的工具列表。
- `AgentLoopRuntimeTest.remoteToolSnapshotFiltersToolsNotEligibleForScope` 固化远程工具快照
  scope 过滤，防止 LocalOnly/private 工具被记录为已暴露给远程模型。
- `scripts/test_validation_scripts.sh` 的 fake ADB 增加高德 `type_text` 失败路径，覆盖
  `editable_not_found`、`editable_field`、`搜索输入框`、ranked candidates JSON、result file
  SHA、截图、UIAutomator dump、focused window 和 case logcat SHA。

验证命令：

```bash
ANDROID_HOME=/data00/home/zouguoxue/android-sdk ./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.eval.AiBehaviorPlanningTraceProjectorTest' \
  --tests 'com.bytedance.zgx.pocketmind.eval.AiBehaviorActualTraceGeneratorTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.localSharedPdfImageOcrTruncationIsRecordedInRunDataReceipt' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.stagedSharedImageWaitsForExplicitSendAndStaysLocalOnly' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteModeRejectsSharedPdfImageOcrPreviewBeforeBuildingPrompt' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.remoteToolSnapshotFiltersToolsNotEligibleForScope' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.remoteModelCannotRequestScopeEligibleToolMissingFromExposedSnapshot' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.remoteMixedBatchRejectionRecordsAttemptedToolNames'

ANDROID_HOME=/data00/home/zouguoxue/android-sdk \
  ARTIFACT_DIR=build/verification/ai-behavior-actual-trace-collector-ocr-recovery-v13 \
  scripts/collect_ai_behavior_actual_trace.sh

bash -n scripts/run_real_app_search_eval.sh scripts/test_validation_scripts.sh

ANDROID_HOME=/data00/home/zouguoxue/android-sdk \
  scripts/test_validation_scripts.sh

ANDROID_HOME=/data00/home/zouguoxue/android-sdk \
  scripts/verify_local.sh

git diff --check

ANDROID_HOME=/data00/home/zouguoxue/android-sdk \
  /data00/home/zouguoxue/android-sdk/platform-tools/adb devices -l
```

结果：

- 通过：目标 JVM 测试返回 `BUILD SUCCESSFUL`。
- 通过：`collect_ai_behavior_actual_trace.sh` 生成
  `build/verification/ai-behavior-actual-trace-collector-ocr-recovery-v13/ai-behavior-actual-trace-collection.properties`，
  记录 `caseCount=31`、`actualTraceSourceBreakdown=agent_loop_runtime:31`、
  `traceDiffMatchedCount=17`、`traceDiffAllowedFailureCount=9`、
  `traceDiffMismatchCount=5`、
  `traceDiffMissingActualCount=0`、`traceDiffExtraActualCount=0`。
- 审计绑定：collection report 记录 `actualTraceSha256=f562951c050b2318eecaa5288f3c766eb3988d902c6f63fc579b84819418d56f`、
  `traceDiffSha256=f41bbb91818a7ed93610f23b64ddff6b5eecbce6fc5f232d56e8d2225c46fe26`、
  `evalReportSha256=0134e69a9c326bd095cbe931eed106104f60ee6035a1fe4394220106f64a603f`。
- 改善：相较 2026-06-20 早前 `8 matched / 23 mismatch` 的 baseline，本轮 runtime
  evidence 让 memory no-tool、quality guard、metadata-only recovery、contacts remote block、
  mixed public/private batch、weather→Wi-Fi sequential planning、远程 OCR 摘录阻断和多截图
  OCR fail-closed、当前屏幕/当前截图远程阻断、search→share 二次确认、远程图片发送确认和
  重启后 pending payload 不可恢复 fail-closed、PDF 扫描页 OCR 截断 LocalOnly evidence、
  以及重启后确认卡只恢复不自动执行进入 actual trace；剩余 mismatch 主要集中在
  memory forget confirmation、淘宝搜索后返回、真实 App 搜索 checkpoint budget、外部 App
  outcome 恢复/失败语义、提醒重启后 reschedule 证据链。
- 通过：shell 语法检查返回 0。
- 通过：`scripts/test_validation_scripts.sh` 返回 `Validation script tests passed`，新增覆盖
  `gaode.case.properties` 中 `reason=editable_not_found`、`failed_step=type_text`、
  `target_resolution_failure_kind=editable_not_found` 和 candidates JSON/diagnostics SHA。
- 通过：`scripts/verify_local.sh` 返回 `Local verification passed`，并完成 artifact scan。
- 通过：`git diff --check` 返回 0。
- 未执行真机：`adb devices -l` 只输出 `List of devices attached` 表头，没有可见
  authorized device；因此本轮没有新增 physical-device instrumentation evidence。
- 仍未完成：当前 actual trace 仍有 7 条 mismatch，不能作为 public release strict actual trace
  pass；下一步应继续接入 memory forget 产品确认语义、PDF/recent-file OCR、淘宝搜索后返回、
  真实 App 搜索 checkpoint budget 和剩余 restart recovery failureMode，
  而不是复制 fixture expected 值。

## 2026-06-19 Real-App Debug Eval Result Formatter Contract

本轮覆盖项：

- 新增 debug-only `DeviceControlEvalResultFormatter`，把真实 debug receiver 的
  `targetResolution.candidatesJson` 统一为脚本/fake ADB 已使用的对象形态：
  `{"candidates":[...]}`，避免真实 receiver 输出 raw array 导致 case report schema 漂移。
- `DeviceControlEvalReceiver` 继续保留原 key，不重命名
  `targetResolution.*` 字段；只把 candidates JSON、request result 文件名和 value 清洗委托给
  formatter。
- 新增 `DeviceControlEvalResultFormatterTest`，覆盖 ranked candidates JSON 对象合同、换行/NUL
  清洗、request id 文件名安全。

验证命令：

```bash
ANDROID_HOME="$HOME/android-sdk" ANDROID_SDK_ROOT="$HOME/android-sdk" ./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.debug.DeviceControlEvalResultFormatterTest' \
  --tests 'com.bytedance.zgx.pocketmind.device.UiTargetResolverTest' \
  --tests 'com.bytedance.zgx.pocketmind.eval.AiBehaviorActualTraceGeneratorTest'

scripts/test_validation_scripts.sh
```

结果：

- 通过：目标 JVM 测试返回 `BUILD SUCCESSFUL`。
- 通过：`scripts/test_validation_scripts.sh` 返回 `Validation script tests passed`，继续覆盖
  `run_real_app_search_eval.sh` 失败 case report 中的 `target_resolution_candidates_json`。

## 2026-06-19 AI Behavior Eval Evidence Audit Schema

本轮覆盖项：

- 新增 `AiBehaviorActualTraceGeneratorTest`：读取 31 条 eval fixture 的
  `id/category/input`，逐条运行真实 `AgentLoopRuntime.runOnce()`，再通过
  `AgentBehaviorTraceProjector` 生成 actual trace JSONL；actual 工具、确认策略、risk、
  privacy 均来自 runtime/projector，不复制 expected 字段。
- 新增 `scripts/collect_ai_behavior_actual_trace.sh`：运行 generator test、调用
  `verify_ai_behavior_eval.sh` 生成 planning trace diff，并输出
  `AgentBehaviorActualTraceCollection/v1` collection report，绑定 actual trace、diff、
  eval report 的 SHA-256。
- `verify_ai_behavior_eval.sh` 的 report 增加 `artifactSchema=AgentBehaviorEvalVerification/v1`、
  `owner=agent-behavior`、UTC `recordedAt`、可复现 `command`、`reproduciblePath`。
- 当提供 actual trace 或 trace diff 文件时，report 会写出 `actualTraceSha256` 和
  `traceDiffSha256`，把行为评测输入/输出证据纳入 SHA-256 审计链。
- `verify_ai_behavior_eval.sh` 新增 `--require-runtime-trace-source`；严格模式要求每条
  actual trace 带有 `traceSource`（`agent_loop_runtime` / `android_instrumentation` /
  `device_debug_eval`）和 UTC `traceRecordedAt`。`PUBLIC_RELEASE=1` 会强制开启
  `REQUIRE_AI_BEHAVIOR_RUNTIME_TRACE_SOURCE=1`，防止 expected fixture 被复制成“actual”。
- `scripts/test_validation_scripts.sh` 增加字段回归：AI behavior eval report 必须含 schema、
  owner、UTC 时间、命令、可复现路径；missing-actual diff 和 matched actual diff 都必须写出
  trace diff SHA，matched actual trace 必须写出 actual trace SHA。
- `docs/release_checklist.md` 和 `docs/release_readiness.md` 同步 public release 执行要求：
  最终 gate 必须提供 `AI_BEHAVIOR_ACTUAL_TRACE_FILE`，并保存
  `ai-behavior-eval.properties` / `ai-behavior-planning-trace-diff.jsonl` 证据。

验证命令：

```bash
bash -n scripts/verify_ai_behavior_eval.sh scripts/verify_release_gate.sh \
  scripts/test_validation_scripts.sh

scripts/verify_ai_behavior_eval.sh --require-boundary-map \
  --trace-diff /tmp/pocketmind-ai-behavior-audit-check.jsonl \
  --report /tmp/pocketmind-ai-behavior-audit-check.properties

ANDROID_HOME="$HOME/android-sdk" ANDROID_SDK_ROOT="$HOME/android-sdk" ./gradlew \
  -PaiBehaviorActualTraceFile=build/verification/ai-behavior-actual-trace-generator-check/ai-behavior-actual-trace.jsonl \
  :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.eval.AiBehaviorActualTraceGeneratorTest' \
  --rerun-tasks

ANDROID_HOME="$HOME/android-sdk" ANDROID_SDK_ROOT="$HOME/android-sdk" \
  ARTIFACT_DIR=build/verification/ai-behavior-actual-trace-collector-check \
  scripts/collect_ai_behavior_actual_trace.sh

scripts/test_validation_scripts.sh
```

结果：

- 通过：shell 语法检查返回 0。
- 通过：`verify_ai_behavior_eval.sh` 返回
  `AI behavior eval fixtures passed: 31 cases across 7 categories and 6 MVP scenarios`。
- 通过：`AiBehaviorActualTraceGeneratorTest` 返回 `BUILD SUCCESSFUL`，并写出 31 行
  `ai-behavior-actual-trace.jsonl`，每行带 `traceSource=agent_loop_runtime` 和 UTC
  `traceRecordedAt`。
- 通过：`collect_ai_behavior_actual_trace.sh` 写出
  `ai-behavior-actual-trace-collection.properties`，记录 `caseCount=31`、
  `traceDiffMissingActualCount=0`、`traceDiffExtraActualCount=0`、
  `actualTraceSourceBreakdown=agent_loop_runtime:31`。当前真实 runtime trace 只有
  `traceDiffMatchedCount=6`，另有 `traceDiffMismatchCount=25`；这是后续能力缺口，
  不能作为 public release strict actual trace pass。
- 通过：`scripts/test_validation_scripts.sh` 返回 `Validation script tests passed`。

## 2026-06-19 Public Release AI Behavior Actual Trace Gate

本轮覆盖项：

- `PUBLIC_RELEASE=1` profile 自动强制 `REQUIRE_AI_BEHAVIOR_ACTUAL_TRACE=1`，即使调用方显式传
  `REQUIRE_AI_BEHAVIOR_ACTUAL_TRACE=0` 也会在 release gate report 中回写为 `1`。
- `verify_release_gate.sh` 在 AI behavior eval 阶段把 `--require-actual-trace` 接入
  `verify_ai_behavior_eval.sh`；缺少 `AI_BEHAVIOR_ACTUAL_TRACE_FILE` 时 fail-closed 到
  `ai-behavior-eval`，原因是 `actual-trace-file-missing`。
- `scripts/test_validation_scripts.sh` 增加 release gate 负例：非 public 严格 actual trace
  缺失会失败；public 缺 signing cert 的早期失败仍会保留 `requireAiBehaviorActualTrace=1`
  证据，证明 public profile 已覆盖调用方开关。

验证命令：

```bash
bash -n scripts/verify_release_gate.sh scripts/test_validation_scripts.sh

scripts/test_validation_scripts.sh
```

结果：

- 通过：shell 语法检查返回 0。
- 通过：`scripts/test_validation_scripts.sh` 返回 `Validation script tests passed`。
- 覆盖：`release-ai-behavior-actual-required/ai-behavior-eval.properties` 写出
  `status=failed`、`reason=actual-trace-file-missing`；`release-gate.properties` 写出
  `failedTarget=ai-behavior-eval`、`requireAiBehaviorActualTrace=1`。

## 2026-06-19 Phase 3 Agent Behavior Eval Stable Case IDs

本轮覆盖项：

- 7 个 `ai_behavior_eval` JSONL fixture 的 31 条 case 全部增加稳定 `id`，不再依赖
  `category:lineNumber` 作为 trace diff 绑定键。
- `verify_ai_behavior_eval.sh` 将 `id` 纳入必填字段，要求匹配稳定 ASCII 命名
  `^[a-z0-9][a-z0-9_.-]*$`，并保留全局 duplicate id fail-closed 校验。
- `AiBehaviorEvalFixturesTest` 增加跨 category 的 id 非空、格式和唯一性断言。
- `scripts/test_validation_scripts.sh` 增加缺失 id、重复 id 两个负例；matching actual trace
  生成逻辑改为使用 fixture `id`，并断言 diff JSONL 输出稳定 caseId。

验证命令：

```bash
bash -n scripts/verify_ai_behavior_eval.sh scripts/test_validation_scripts.sh

scripts/verify_ai_behavior_eval.sh --require-boundary-map \
  --trace-diff /tmp/pocketmind-ai-behavior-id-check.jsonl \
  --report /tmp/pocketmind-ai-behavior-id-check.properties

scripts/test_validation_scripts.sh

ANDROID_HOME="$HOME/android-sdk" ANDROID_SDK_ROOT="$HOME/android-sdk" ./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.eval.AiBehaviorEvalFixturesTest' \
  --tests 'com.bytedance.zgx.pocketmind.eval.AiBehaviorPlanningTraceProjectorTest'
```

结果：

- 通过：shell 语法检查返回 0。
- 通过：`verify_ai_behavior_eval.sh` 返回 `AI behavior eval fixtures passed: 31 cases across 7 categories and 6 MVP scenarios`，
  trace diff 第一条输出 `caseId=memory_style_concise`。
- 通过：`scripts/test_validation_scripts.sh` 返回 `Validation script tests passed`，覆盖 missing id、
  duplicate id、matching/mismatch/extra actual trace diff。
- 通过：目标 JVM 测试返回 `BUILD SUCCESSFUL`。

## 2026-06-18 Phase 3 Agent Behavior Planning Trace Diff

本轮覆盖项：

- `AgentBehaviorEvalCase` 增加实际规划轨迹投影配套模型：
  `AgentBehaviorActualTrace`、`AgentBehaviorPlanningTraceDiff`、`AgentBehaviorTraceDiffStatus`
  和 `diffAgainst()`，可机器比较 expected/actual 工具序列、确认策略、risk、privacy、
  `LocalOnly` / `RemoteEligible` 与允许失败模式。
- 新增 `AgentBehaviorTraceProjector`，把本地 `AgentLoopResult` 投影为
  `AgentBehaviorActualTrace`：纯回答输出 no-tool trace；`web_search` 输出
  `RemoteEligible/public_evidence/none`；多步 Skill 输出有序工具序列并标记
  `LocalOnly/second_confirmation`；安全拒绝输出 `FailClosed` 与标准化 failure mode。
- `verify_ai_behavior_eval.sh` 新增 `--actual-trace`、`--trace-diff` 和
  `--require-actual-trace`：默认会写 planning trace diff JSONL，缺实际 trace 时标记
  `missing_actual`；严格模式下缺 actual、mismatch 或 extra actual row 都 fail-closed。
- `verify_release_gate.sh` 默认将 AI behavior eval 的 trace diff 写到
  `ai-behavior-planning-trace-diff.jsonl`；后续可通过 `AI_BEHAVIOR_ACTUAL_TRACE_FILE` 和
  `REQUIRE_AI_BEHAVIOR_ACTUAL_TRACE=1` 把真实 planner actual trace 升级为 release gate。

验证命令：

```bash
bash -n scripts/verify_ai_behavior_eval.sh scripts/verify_release_gate.sh \
  scripts/test_validation_scripts.sh

scripts/test_validation_scripts.sh

ANDROID_HOME="$HOME/android-sdk" ANDROID_SDK_ROOT="$HOME/android-sdk" ./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.eval.AiBehaviorPlanningTraceProjectorTest' \
  --tests 'com.bytedance.zgx.pocketmind.contracts.PhaseOneContractModelsTest' \
  --tests 'com.bytedance.zgx.pocketmind.eval.AiBehaviorEvalFixturesTest'
```

结果：

- 通过：shell 语法检查返回 0。
- 通过：`scripts/test_validation_scripts.sh` 返回 `Validation script tests passed`；覆盖无
  actual trace 时的 `missing_actual` diff、匹配 actual trace、mismatch 失败和 extra actual
  失败。
- 通过：目标 JVM 测试返回 `BUILD SUCCESSFUL`，覆盖 projector 的 no-tool、public evidence、
  LocalOnly 多步 Skill 和 allowed failure diff。

剩余风险：

- 当前 projector 是 JVM 本地投影，release gate 默认仍不强制真实 runtime actual trace；要把
  `REQUIRE_AI_BEHAVIOR_ACTUAL_TRACE=1` 打开，还需要接入自动执行/采集 fixture 子集 actual trace。

## 2026-06-18 Phase 6 Perf Baseline Evidence Schema 与 Release Gate 分层

本轮覆盖项：

- `collect_perf_baseline.sh` 产出的成功记录增加 `artifactSchema=PerfBaseline/v1`、
  `target=perf-baseline-record`、`owner`、`collectionCommand`、`reproduciblePath` 和
  `releaseArtifact`，失败记录增加 `artifactSchema=PerfBaselineCollection/v1`、
  `command`、`failedTarget`、`reason`、`recordedAt` 等审计字段。
- `verify_perf_baseline.sh` 的 verifier report 增加
  `artifactSchema=PerfBaselineVerification/v1`、`owner`、`recordedAt`、`command`、
  `failedTarget`、`reproduciblePath` 和 baseline SHA；失败会落到
  `baseline-fields`、`release-artifact`、`runtime-backend`、`model-profile` 等机器可读目标。
- Perf verifier 新增语义约束：`modelId` 仅允许 release chat profile
  `chat-e2b` / `chat-e4b`，`backend` 仅允许 `CPU` / `GPU`，
  `gpuFallbackStatus` 仅允许 `not-needed` / `cpu-fallback-passed`。
- `verify_release_validation_record.sh` 的 performance evidence 校验同步提升：正式 release
  validation record 中的 perf verifier report 和 baseline record 必须携带 schema、owner、命令、
  可复现路径和匹配 SHA。
- `verify_release_gate.sh` 增加 `VERIFY_PERF_BASELINE` 分层开关：默认仍为 `1`；
  `PUBLIC_RELEASE=1` 会强制回 `1`；非 public owner evidence 检查可显式设为 `0`，
  写出 `perf-baseline-verification.properties` 的 skipped 记录后继续进入 store/privacy 等子门禁。

验证命令：

```bash
bash -n scripts/verify_perf_baseline.sh scripts/collect_perf_baseline.sh \
  scripts/verify_release_validation_record.sh scripts/verify_release_gate.sh \
  scripts/test_validation_scripts.sh

scripts/test_validation_scripts.sh

ANDROID_HOME="$HOME/android-sdk" ANDROID_SDK_ROOT="$HOME/android-sdk" \
  "$HOME/android-sdk/platform-tools/adb" devices -l
```

结果：

- 通过：shell 语法检查返回 0。
- 通过：`scripts/test_validation_scripts.sh` 返回 `Validation script tests passed`。
- 覆盖的负例包括：缺字段、artifact SHA mismatch、emulator serial、零耗时、
  `backend=TPU`、未知 `gpuFallbackStatus`、非 chat `modelId`。
- 覆盖的 release gate 行为包括：默认缺 `PERF_BASELINE_FILE` 仍 fail-closed；
  非 public `VERIFY_PERF_BASELINE=0 VERIFY_STORE_POLICY=1` 可以跳过 perf 并继续失败在
  `store-policy-record`；`PUBLIC_RELEASE=1 VERIFY_PERF_BASELINE=0` 仍在 gate report 中强制显示
  `verifyPerfBaseline=1`。

未执行：

- ADB 输出仅有 `List of devices attached`，未列出任何设备；因此未执行 physical validation、
  Android instrumentation、真实 App 搜索真机 eval 或 RC perf baseline。
- 未采集 RC perf baseline；真实模型加载、首 token、tokens/s、OOM/ANR 和 GPU fallback
  仍需要 arm64 真机 evidence。

## 2026-06-18 Phase 5 长期记忆 Metadata 与远程边界

本轮覆盖项：

- `PersistedMemoryRecord` 和 Room `memory_records` 增加长期记忆 metadata：
  `source`、`sensitivity`、`privacy`、`expiresAtMillis`、`conflictKey`。
  新列默认值为 `LegacyImport` / `Normal` / `LocalOnly` / null / null，旧数据不迁移删除。
- `MemoryHit` 携带同一组边界字段；`MemoryRepository` 在显式偏好、用户事实和自动任务状态中分别标记
  `ExplicitUser`、`Sensitive`、`AutoTaskState` / `Internal` 和稳定 conflict key。
- 过期长期记忆不进入 `savedRecords()`、search 或 prompt context，但底层 store 先保留原记录，
  避免迁移阶段物理删除用户数据。
- `MemoryQualityContract` 增加 memory boundary 校验：远程模式任何 memory hit 或
  memory context 都 fail-closed，本地模式只允许 `LocalOnly` memory hit 进入 context。
- `AgentLoopRuntime` 的 prompt evidence 使用 `MemoryHit.isAvailableForLocalContext()` 过滤，
  即使误传非 LocalOnly 或过期 hit，也不会拼进模型 prompt fallback。
- `PocketMindViewModel` 远程语义记忆测试扩展到敏感 `UserFact`，断言 remote prompt/history
  不包含敏感事实原文。
- 数据库版本升至 15，并新增 `MIGRATION_14_15`；Android migration test 覆盖旧
  `memory_records` 默认 metadata。该 instrumentation 测试已编译，尚未在真机执行。

验证命令：

```bash
git diff --check

ANDROID_HOME="$HOME/android-sdk" ANDROID_SDK_ROOT="$HOME/android-sdk" ./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.memory.MemoryRepositoryTest' \
  --tests 'com.bytedance.zgx.pocketmind.memory.MemoryQualityContractTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteModeKeepsSemanticMemoryRuntimeButDoesNotSendMemoryContext' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.memoryContextBuildFailureStillAllowsDeviceContextPrompt'

ANDROID_HOME="$HOME/android-sdk" ANDROID_SDK_ROOT="$HOME/android-sdk" \
  scripts/verify_local.sh
```

结果：

- 通过：`git diff --check` 返回 0。
- 通过：targeted JVM 测试返回 `BUILD SUCCESSFUL`，覆盖长期记忆 metadata、过期过滤、
  memory boundary contract、远程敏感记忆不进 prompt/history，以及 AgentLoop prompt
  fallback 的本地 context 保护。
- 通过：提升权限后运行 `scripts/verify_local.sh`，返回 `Validation script tests passed`、
  Gradle `BUILD SUCCESSFUL`、`Android artifact scan passed`、`Local verification passed`。
- 未执行：`PocketMindDatabaseMigrationTest.migration14To15AddsMemoryRecordMetadataDefaults`
  是 Android instrumentation 测试；本轮因为 ADB 无设备未运行，只通过 `verify_local.sh`
  编译了 debug AndroidTest APK。

剩余风险：

- 记忆 metadata 目前已进入数据层和 `LongTermMemorySummary`，但 UI 仍只展示类型与文本；
  后续若要让用户看到来源、过期时间或敏感标记，需要再补 UI 呈现和截图 evidence。
- 真机 migration、真实 semantic memory 性能、RC perf baseline 仍依赖 arm64 设备 evidence。

## 2026-06-18 Phase 5 ModelCapabilityProfile Runtime/UI 贯通

本轮覆盖项：

- `LocalModelRuntimeCapabilities` 从 `ModelCapabilityProfile` 派生
  `supportsVisionInput`、`contextWindowTokens` 和 `preferredBackends`，并传入
  LiteRT engine config；本地图片输入、上下文窗口和 engine `maxNumTokens` 不再依赖全局假设。
- `AdaptiveGenerationPolicy` 改为按当前模型 `contextWindowTokens` 计算输入预算；
  健康运行时保留 profile 可用窗口，降级/质量保护时仍保守收缩。
- `PocketMindViewModel` 在加载模型前按 active profile 选择可用 backend，拒绝不支持的
  GPU/CPU 切换，并把 profile 的 context/backend 能力同步到 `ChatUiState`。
- Compose 设置面板按 `localPreferredBackends` 禁用不支持的 backend 按钮，token 上限展示继续来自
  active profile。
- `ModelCatalog.isChatModel`、`recommendedChatModelById` 和
  `ModelRepository` active chat 候选收口到 `supportsChatGeneration`，防止已验证的
  embedding/action 推荐模型被误当作对话模型。

验证命令：

```bash
git diff --check

ANDROID_HOME="$HOME/android-sdk" ANDROID_SDK_ROOT="$HOME/android-sdk" ./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.ModelCatalogTest' \
  --tests 'com.bytedance.zgx.pocketmind.data.ModelRepositoryPathTest' \
  --tests 'com.bytedance.zgx.pocketmind.runtime.LiteRtRuntimeConfigTest' \
  --tests 'com.bytedance.zgx.pocketmind.runtime.AdaptiveGenerationPolicyTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.loadModelConfiguresRuntimeFromActiveModelCapabilityProfile'

ANDROID_HOME="$HOME/android-sdk" ANDROID_SDK_ROOT="$HOME/android-sdk" \
  scripts/verify_local.sh
```

结果：

- 通过：`git diff --check` 返回 0。
- 通过：targeted JVM 测试返回 `BUILD SUCCESSFUL`，覆盖 catalog chat gate、active chat
  candidate、LiteRT config、adaptive policy 和 ViewModel runtime capability 配置。
  首次在 sandbox 内执行 Gradle 被 `~/.gradle` wrapper lock 的只读文件系统拒绝，提升权限后通过。
- 通过：提升权限后运行 `scripts/verify_local.sh`，返回 `Validation script tests passed`、
  Gradle `BUILD SUCCESSFUL`、`Android artifact scan passed`、`Local verification passed`。

剩余风险：

- 本轮只完成 runtime/UI 的 profile 能力贯通和 JVM/本地门禁，尚未采集新的真机模型性能
  baseline；加载时间、首 token、tokens/s、OOM/ANR 和 GPU fallback 仍需要 Phase 4 RC
  perf evidence 覆盖。

## 2026-06-18 真机验证探测

本轮覆盖项：

- 按用户已连接真机的上下文检查 ADB 设备可见性，并尝试用标准
  `DeviceVerificationArtifact/v1` 流程生成真机验证证据。

验证命令：

```bash
ANDROID_HOME="$HOME/android-sdk" ANDROID_SDK_ROOT="$HOME/android-sdk" \
  "$HOME/android-sdk/platform-tools/adb" devices -l

ANDROID_HOME="$HOME/android-sdk" ANDROID_SDK_ROOT="$HOME/android-sdk" \
  ARTIFACT_DIR=build/verification/device-probe-20260618-no-device \
  scripts/install_and_test_device.sh
```

结果：

- 未通过：`adb devices -l` 只输出 `List of devices attached`，没有 authorized device。
- 未通过：`scripts/install_and_test_device.sh` 生成
  `build/verification/device-probe-20260618-no-device/device-verification.properties`；
  机器可读字段为 `status=failed`、`failedTarget=device-selection`、
  `reason=device-selection-ambiguous`、`instrumentation=not-run`、`serial=`、
  `api_level=`、`abi=`。

剩余风险：

- 当前机器无法看见真机，因此 arm64 physical instrumentation、device-control eval 和
  real-app-search eval 均未执行；这不能作为 release physical evidence。

## 2026-06-18 Phase 5 隐私/多模态边界加固

本轮覆盖项：

- 远程模式下，用户主动选择的 `image/*` 附件不再受普通文本发送策略静默放行；
  只要请求带有 `ChatImageAttachment`，`PocketMindViewModel` 都会先生成
  `PendingRemoteSendDisclosure`，并在用户确认前保持 remote runtime idle。
- 图片远程发送确认不能被 `OncePerSession` 的“本会话不再提示”静默覆盖；
  UI 也不再在图片确认卡上提供 suppress 选项。
- 新增 ViewModel 回归：远程图片确认后才进入 vision runtime，取消确认不会写会话消息或调用
  remote runtime；`OnlyWhenSensitive` 与 `OncePerSession` 仍只让普通文本静默，图片保持逐次确认。
- 新增当前屏幕截图 OCR 远程保护回归：`capture_current_screenshot_ocr` 工具结果为
  `LocalOnly` / `requiresLocalModel=true` 时，远程模式下不自动续写到 remote runtime，
  不把 OCR 原文写入消息，并将 Agent run 标记为失败。
- `privacy_boundary.jsonl` 增加两条离线行为 eval：远程图片必须
  `remote_send_confirmation`，当前屏幕截图 OCR 必须 `LocalOnly` fail-closed。
- release flow evidence contract 新增 `remoteVisionSendPreviewConfirmed`、
  `remoteVisionCancelKeepsRuntimeIdle`、`mediaProjectionOneShotConsentCovered` 和
  `currentScreenshotOcrRemoteContinuationBlocked`，并同步 recorder、candidate collector、
  verifier、自测和 checklist。

验证命令：

```bash
bash -n scripts/record_release_flow_evidence.sh \
  scripts/collect_release_flow_matrix_evidence.sh \
  scripts/verify_release_validation_record.sh \
  scripts/test_validation_scripts.sh \
  scripts/verify_ai_behavior_eval.sh

scripts/verify_ai_behavior_eval.sh --require-boundary-map
scripts/test_validation_scripts.sh
git diff --check

ANDROID_HOME="$HOME/android-sdk" ANDROID_SDK_ROOT="$HOME/android-sdk" \
  scripts/verify_local.sh
```

结果：

- 通过：shell 语法检查返回 0。
- 通过：`scripts/verify_ai_behavior_eval.sh --require-boundary-map` 返回
  `AI behavior eval fixtures passed: 31 cases across 7 categories and 6 MVP scenarios`。
- 通过：`scripts/test_validation_scripts.sh` 返回 `Validation script tests passed`；
  弱 `shareAndPickerInput` evidence 会因缺少远程图片发送预览确认和取消 idle 证明而被拒绝。
- 通过：`git diff --check` 返回 0。
- 通过：提升权限后运行 `scripts/verify_local.sh`，返回 `Validation script tests passed`、
  Gradle `BUILD SUCCESSFUL`、`Android artifact scan passed`、`Local verification passed`。

剩余风险：

- 本轮是 JVM/脚本级 contract 加固，未替代真机 MediaProjection 前台同意、真实远程视觉 endpoint
  或 arm64 physical validation。
- 远程图片已强制逐次确认，但真实 provider 对 OpenAI-compatible `image_url` 的兼容性仍需发布前
  远程视觉 fixture 和真机 flow 证据继续覆盖。

## 2026-06-18 Phase 5/6 Capability 与 License Evidence 加固

本轮覆盖项：

- `ModelProfile` / `ModelCapabilityProfile` 增加显式 capability 派生字段：
  `supportsChatGeneration`、`supportsMemoryEmbedding`、
  `supportsMobileActionPlanning`、`contextWindowTokens` 和
  `preferredLocalBackends`。
- `ModelProfile` 增加 fail-fast invariant：vision feature 必须声明 vision modality；
  text generation / embedding / action planning 必须与对应 profile capability 匹配；
  只有本地 LiteRT profile 可以声明本地性能 backend。
- 推荐模型 profile 明确区分 chat、embedding、action 的能力、上下文窗口和默认本地
  backend；远程 OpenAI-compatible profile 不声明本地 backend，vision 继续默认
  fail-closed。
- `scripts/verify_model_license_review.sh` 收紧发布证据：Hugging Face license source
  的 revision 必须等于 manifest `upstreamRevision`；每个 review evidence
  properties 必须声明 `status=approved`、`model=<id>`、
  `scope=license-redistribution-attribution` 和
  `redistributionDecision=approved`，并继续校验 SHA-256。
- `scripts/test_validation_scripts.sh` 增加 model license 负例：不同 revision 的
  license source、pending review evidence 内容即使 SHA 匹配也必须被拒绝。

验证命令：

```bash
ANDROID_HOME="$HOME/android-sdk" ANDROID_SDK_ROOT="$HOME/android-sdk" ./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.ModelCatalogTest' \
  --tests 'com.bytedance.zgx.pocketmind.contracts.PhaseOneContractModelsTest'

bash -n scripts/verify_model_license_review.sh scripts/test_validation_scripts.sh
scripts/test_validation_scripts.sh
```

结果：

- 通过：targeted JVM 测试返回 `BUILD SUCCESSFUL`，覆盖 ModelCapabilityProfile 的显式
  chat/vision/embedding/action/context/backend contract 与非法 profile 拒绝。
- 通过：shell 语法检查返回 0。
- 通过：`scripts/test_validation_scripts.sh` 返回 `Validation script tests passed`；
  新增用例覆盖 `license-source-revision-mismatch` 和
  `review-evidence-status-mismatch`。
- 未完成：本轮尚未把 `contextWindowTokens` / `preferredLocalBackends` 贯通到所有 runtime
  和 UI 控件，也未采集新的真机模型性能 baseline；这些仍属于 Phase 5/4 后续切片。

## 2026-06-18 Phase 2 Real App Search 失败证据归档

本轮覆盖项：

- `scripts/run_real_app_search_eval.sh` 的真实 App 搜索矩阵扩展到淘宝、拼多多、高德、
  京东、Chrome、Android Browser、Quark 和 UC；未安装的目标仍按 case 级 skipped
  记录，不扩大自动化到支付、下单、授权等高风险动作。
- 每个 case report 升级为 `RealAppSearchCaseArtifact/v1`：失败时记录
  `failed_step`、debug receiver `result_file` 和 SHA-256、`targetResolution.*`
  resolver evidence、diagnostics 目录、截图、UIAutomator dump、focused-window dump
  和 logcat 的 SHA-256。
- `capture_failure_diagnostics` 产出的 `diagnostics.properties` 绑定截图、XML dump、
  window dump 和 logcat 路径与 SHA，避免真实 App UI 变更时只留下不可复现的人工描述。
- `scripts/test_validation_scripts.sh` 增加 fake ADB 回归，模拟淘宝 tap 阶段
  `search_entry_not_found`，要求 case artifact 带上 resolver failure kind、ranked
  candidates JSON 和所有失败快照文件。

验证命令：

```bash
bash -n scripts/run_real_app_search_eval.sh scripts/test_validation_scripts.sh
scripts/test_validation_scripts.sh
ANDROID_HOME="$HOME/android-sdk" ANDROID_SDK_ROOT="$HOME/android-sdk" scripts/verify_local.sh
```

结果：

- 通过：shell 语法检查返回 0。
- 通过：`scripts/test_validation_scripts.sh` 返回 `Validation script tests passed`；
  新增用例覆盖 `RealAppSearchCaseArtifact/v1`、`target_resolution_failure_kind=search_entry_not_found`、
  `target_resolution_candidate_count=2`、result file SHA，以及 screenshot /
  UIAutomator XML / focused-window / logcat SHA。
- 通过：提升权限后重跑 `scripts/verify_local.sh`，返回 `Validation script tests passed`、
  `BUILD SUCCESSFUL`、`Android artifact scan passed`、`Local verification passed`。首次在
  sandbox 内执行失败于 ADB daemon 权限（`Failed to open netlink socket` /
  `could not install smartsocket listener`），重跑通过。
- 未执行：本轮未连接真机运行新的 `scripts/run_real_app_search_eval.sh`；该脚本仍是
  debug eval receiver 验收，不替代 release physical instrumentation evidence。

## 2026-06-18 Phase 3/4 Eval 与设备证据加固

本轮覆盖项：

- 将 `app/src/test/resources/ai_behavior_eval/*.jsonl` 从场景说明升级为 trace expectation
  contract：每条 fixture 必须声明 `expectedTools`、`expectedConfirmation`、
  `expectedRiskLevel`、`privacy`、`localOnly`、`remoteEligible` 和
  `allowedFailureModes`。
- `scripts/verify_ai_behavior_eval.sh` 增加机器校验：字段类型、LocalOnly /
  RemoteEligible 镜像一致性、fail-closed 允许失败模式、工具名必须在
  `MobileActionFunctions.supported` 中、确认策略/风险/隐私覆盖分布必须完整。
- `AiBehaviorEvalFixturesTest` 使用 `ToolRegistry` 做 JVM 侧权威工具名校验，防止
  JSONL 出现不存在的工具名。
- `scripts/install_and_test_device.sh` 的 `device-verification.properties` 升级为
  `DeviceVerificationArtifact/v1` properties 形态，增加 `artifact_id`、
  `test_count`、`instrumentation_output_sha256` 和 `logcat_sha256`。
- `scripts/verify_release_validation_record.sh` 对 physical device report 和 API matrix
  nested device report 强制校验 instrumentation/logcat SHA-256 与实际文件匹配。

验证命令：

```bash
ANDROID_HOME="$HOME/android-sdk" ANDROID_SDK_ROOT="$HOME/android-sdk" ./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.eval.AiBehaviorEvalFixturesTest'

scripts/verify_ai_behavior_eval.sh --require-boundary-map \
  --report build/verification/ai-behavior-eval-phase3.properties

bash -n scripts/verify_ai_behavior_eval.sh scripts/test_validation_scripts.sh \
  scripts/install_and_test_device.sh scripts/verify_release_validation_record.sh

ANDROID_HOME="$HOME/android-sdk" ANDROID_SDK_ROOT="$HOME/android-sdk" scripts/test_validation_scripts.sh
```

结果：

- 通过：`AiBehaviorEvalFixturesTest` 返回 `BUILD SUCCESSFUL`，覆盖 29 个 eval cases。
- 通过：`verify_ai_behavior_eval.sh` 生成
  `build/verification/ai-behavior-eval-phase3.properties`，记录
  `caseCount=29`、`casesWithExpectedTools=16`、`expectedToolCount=29`、
  `localOnlyCaseCount=21`、`remoteEligibleCaseCount=8`。
- 通过：脚本语法检查返回 0。
- 通过：`scripts/test_validation_scripts.sh` 返回 `Validation script tests passed`，
  覆盖 fake ADB device report v1 字段、AI eval gate 字段分布、未知工具拒绝、
  release validation physical/API nested device report SHA 校验。
- 未执行：本轮未连接真机运行新的 physical instrumentation，也未采集新的 RC perf baseline。

## 2026-06-18 Phase 1 多 Agent 稳定性底座

本轮覆盖项：

- 修复 `打开WiFi` / `打开 WiFi` / `打开 Wi-Fi` 路由回归：系统 Wi-Fi 设置动作优先命中
  `device_settings_skill` / `open_wifi_settings`，不落到 `open_app_by_name`。
- 增加负例：`打开名为 WiFi 的 App` 不应误触系统 Wi-Fi 设置。
- 落地 Phase 1 合同模型：`IntentRoutingDecision`、`UiTargetResolutionEvidence`、
  `AgentBehaviorEvalCase`、`DeviceVerificationArtifact`、`ModelCapabilityProfile`。
- 为真实 App 搜索失败增加 resolver 可解释证据旁路：debug eval receiver 在 tap/type/submit
  result 中输出 `targetResolution.*`、ranked candidates 和 failure kind。
- 新增 `docs/intent_routing_skill_arbitration.md`，记录路由优先级矩阵和 resolver evidence 策略。

验证命令：

```bash
ANDROID_HOME="$HOME/android-sdk" ANDROID_SDK_ROOT="$HOME/android-sdk" ./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest.infersDeviceSettingsDraftsOnlyForExplicitSettingsCommands' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest.plansPlainWifiOpenAsWifiSettingsWithoutSettingsWord' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.naturalWifiCommandsPlanToolBeforeModelAnswer'

ANDROID_HOME="$HOME/android-sdk" ANDROID_SDK_ROOT="$HOME/android-sdk" ./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.device.UiTargetResolverTest.explainIncludesRankedScoreEvidenceAndFailureKind'

ANDROID_HOME="$HOME/android-sdk" ANDROID_SDK_ROOT="$HOME/android-sdk" ./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.contracts.PhaseOneContractModelsTest'

ANDROID_HOME="$HOME/android-sdk" ANDROID_SDK_ROOT="$HOME/android-sdk" ./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.device.UiTargetResolverTest' \
  --tests 'com.bytedance.zgx.pocketmind.contracts.PhaseOneContractModelsTest' \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest.infersDeviceSettingsDraftsOnlyForExplicitSettingsCommands' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest.plansPlainWifiOpenAsWifiSettingsWithoutSettingsWord' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.naturalWifiCommandsPlanToolBeforeModelAnswer'

ANDROID_HOME="$HOME/android-sdk" ANDROID_SDK_ROOT="$HOME/android-sdk" scripts/verify_local.sh
```

结果：

- 通过：三条 Wi-Fi 路由回归目标测试返回 `BUILD SUCCESSFUL`。
- 通过：`UiTargetResolverTest.explainIncludesRankedScoreEvidenceAndFailureKind` 先因缺少
  `UiTargetResolver.explain()` 和 evidence model 编译失败，补齐实现后返回 `BUILD SUCCESSFUL`。
- 通过：`PhaseOneContractModelsTest` 先因缺少合同模型编译失败，补齐实现后返回
  `BUILD SUCCESSFUL`。
- 通过：合并后的 targeted Phase 1 JVM 回归返回 `BUILD SUCCESSFUL`。
- 通过：`scripts/verify_local.sh` 返回 `Validation script tests passed`、
  `Android artifact scan passed`、`Local verification passed`。本轮同时将 release
  verifier 脚本中的 Python 3.9+ `removeprefix` 用法改为 Python 3.7 兼容写法。
- 未执行：本轮尚未运行真机 `scripts/run_real_app_search_eval.sh` 或完整
  instrumentation；真机 release evidence 仍以已记录的失败/通过 artifact 为准。

## 2026-06-17 文档边界同步与真机回归抽测

本轮覆盖项：

- 根据当前代码同步项目文档：远程 chat completion URL 处理、远程图片输入必须支持
  OpenAI-compatible `image_url` 内容块、默认真机 smoke 会清 App 数据、保留模型数据时的
  `RESET_APP_DATA_AFTER_TESTS=0` 要求、以及 2026-06-17 真机实测状态。
- 复核真机 `fb6272c` 的 debug 控制、真实 App 搜索、主界面可启动/交互、以及完整
  instrumentation 失败证据。
- 检查用户提醒后未继续执行卸载、`pm clear` 或默认会清数据的完整验收路径；只做只读
  sandbox/数据库状态核对。

验证命令：

```bash
ANDROID_SERIAL=fb6272c RESET_APP_DATA_AFTER_TESTS=0 scripts/install_and_test_device.sh
SKIP_BUILD=1 SKIP_INSTALL=1 ANDROID_SERIAL=fb6272c scripts/run_real_app_search_eval.sh
SKIP_BUILD=1 SKIP_INSTALL=1 ANDROID_SERIAL=fb6272c scripts/run_device_control_debug_eval.sh
$HOME/Library/Android/sdk/platform-tools/adb -s fb6272c shell am start -W -n com.bytedance.zgx.pocketmind/.MainActivity
sqlite3 build/verification/pocketmind-db-after-device-tests.db "select 'installed_models=' || count(*) from installed_models; select 'download_records=' || count(*) from download_records; select 'chat_sessions=' || count(*) from chat_sessions; select 'chat_messages=' || count(*) from chat_messages;"
```

结果：

- 失败：完整真机 instrumentation 报告
  `build/verification/device-20260617-000355/device-verification.properties`
  记录 `status=failed`、`failedTarget=instrumentation`、
  `reason=instrumentation-failed`、`instrumentation_test_count=56`、
  `reset_app_data_after_tests=0`；`instrumentation.txt` 显示
  `MainActivityAdaptiveUiTest.largeFontChatShellAndModelManagerRemainReachable`
  开始后 `shortMsg=Process crashed.`。该报告不能作为 release physical-device
  passed evidence。
- 通过：debug phone-control primitive eval
  `build/verification/device-control-debug-eval-20260617-001110/device-control-debug-eval.properties`
  记录 `status=passed`、`command_count=39`，覆盖 observe/tap/type/scroll/wait/back
  和 node-not-found recovery。
- 失败：真实 App 搜索 eval
  `build/verification/real-app-search-eval-20260617-000937/real-app-search-eval.properties`
  记录 `status=failed`、`run_count=3`、`pass_count=1`、`fail_count=2`、
  `skip_count=1`；`pdd.case.properties` 通过，`taobao.case.properties` 为
  `search_entry_not_found`，`gaode.case.properties` 为 `editable_not_found`，
  `chrome.case.properties` 因 `package_not_installed:com.android.chrome` 跳过。
- 通过：主 Activity 真机启动返回 `Status: ok`，并保存 UI/交互证据
  `build/verification/pocketmind-main-20260617-0011.xml`、
  `build/verification/pocketmind-model-sheet-20260617-0011.xml`、
  `build/verification/pocketmind-input-20260617-0011.xml` 和
  `build/verification/pocketmind-after-input-20260617-0011.xml`；输入框可输入测试文本并启用发送入口，随后已清空测试文本。
- 数据保留核对：用户提醒不要删除已下载模型数据后，本轮未继续执行卸载、`pm clear` 或默认清数据脚本；只读数据库副本
  `build/verification/pocketmind-db-after-device-tests.db` 显示
  `installed_models=0`、`download_records=0`、`chat_sessions=0`、
  `chat_messages=0`。该结果只能说明当时可见数据库为空，不能替代已有模型数据保护策略。
- 未执行：未用真实 vision-capable 远程 endpoint 做图片发送通过性验证；当前文档只绑定代码行为边界，即远程图片请求使用 OpenAI-compatible
  `image_url` 内容块，文本兼容端点若不支持视觉应保持图片输入关闭或预期失败。

## 2026-06-15 Agent capability architecture and physical-device validation

本轮覆盖项：

- 将 Agent 能力边界从 Agent loop 内部 allowlist 收敛到 `ToolProvider` /
  `ToolSpec` / `ToolCapabilityTag` / `SkillManifest` 元数据：基础能力如
  public evidence search、设备上下文、Accessibility GUI primitives、Android
  runtime permission、special access 和后台调度由 registry 声明；软件/流程能力
  通过 provider 与 Skill Manifest 扩展。
- `ActionPlanningRuntime` 的模型提示从当前 Tool Registry schema 生成；低风险
  App 控制、未验证 App launch 后续、UI checkpoint、后台 Skill allowlist 和
  runtime permission descriptor 均由 registry/manifest 查询。
- 真机安装和全量 AndroidTest instrumentation 以真实设备 `fb6272c` 验证，不把失败
  结果绑定为 release physical-device 通过证据。

验证命令：

```bash
$HOME/Library/Android/sdk/platform-tools/adb devices -l
$HOME/Library/Android/sdk/platform-tools/adb -s fb6272c shell 'getprop ro.product.manufacturer; getprop ro.product.model; getprop ro.build.version.release; getprop ro.build.version.sdk; getprop ro.product.cpu.abilist64; df -k /data | tail -n 1'
ANDROID_SERIAL=fb6272c CLEAN_DEVICE=0 RESET_APP_DATA_AFTER_TESTS=0 scripts/install_and_test_device.sh
$HOME/Library/Android/sdk/platform-tools/adb -s fb6272c shell am start -W -n com.bytedance.zgx.pocketmind/.MainActivity
```

结果：

- 通过：真机识别为 `serial=fb6272c`、`manufacturer=Xiaomi`、
  `model=23127PN0CC`、`device=houji`、Android 16 / API 36、`arm64-v8a`，
  `/data` 可用约 39GB。
- 通过：`assembleDebug assembleDebugAndroidTest` 成功，debug APK 与
  androidTest APK 安装均返回 `Success`。
- 失败：全量 instrumentation 在
  `MainActivityAdaptiveUiTest.largeFontChatShellAndModelManagerRemainReachable`
  开始后超过 900 秒；设备报告
  `build/verification/device-20260615-110000/device-verification.properties`
  记录 `status=failed`、`failedTarget=instrumentation`、
  `reason=instrumentation-timeout`、`instrumentation_test_count=56`、
  `clean_device=0`、`reset_app_data_after_tests=0`。
- 通过：失败后手动启动主 Activity 返回 `Status: ok`、`LaunchState: COLD`、
  `TotalTime=1677`、`WaitTime=1681`，说明安装后的 App 可启动。
- evidence SHA-256：
  `device-verification.properties` 为
  `bfca9791d89be1ccc6614ed309664249572fd8ef89d38db03aa0b5c698c97c88`；
  `instrumentation.txt` 为
  `cc9fd985fc12518a76543de28519bad16d010f0eaf9c6d7a8994fc5524e92718`；
  `logcat.txt` 为
  `2adf7e9bc37c4a0dc7466461a28ca0c65af7e210a8354c4be7e8394f8e350d60`。
- 未绑定：`docs/release_validation_record.json` 的 physical-device evidence 保持
  pending；该失败报告不能替代 release physical-device passed evidence。

## 2026-06-14 App 内搜索闭环与真机覆盖安装

本轮覆盖项：

- 低风险 App 控制闭环：打开 App、观察屏幕、定位搜索入口、输入、提交搜索和验证结果。
- 顶部手机控制进度条、低风险连续执行、结果验证和 `LocalOnly` 屏幕状态边界。
- 真机调试验收后覆盖安装最新签名 release 包，保留已下载模型数据。

验证命令：

```bash
./gradlew :app:testDebugUnitTest :app:assembleRelease
ANDROID_SERIAL=fb6272c scripts/run_real_app_search_eval.sh
ANDROID_SERIAL=emulator-5554 scripts/run_mock_target_app_search_eval.sh
adb -s fb6272c install -r app/build/outputs/apk/release/app-release-local-signed.apk
```

结果：

- 通过：`build/verification/real-app-search-eval-20260614-222945/real-app-search-eval.properties`
  记录 `status=passed`、`serial=fb6272c`、`run_count=3`、`pass_count=3`、
  `fail_count=0`；淘宝、拼多多、高德通过，Chrome 未安装跳过。
- 通过：`build/verification/mock-target-app-search-eval-20260614-205816/mock-target-app-search-eval.properties`
  记录 mock 淘宝、拼多多、高德 `3/3` 通过。
- 通过：本地 release 签名报告
  `build/verification/signing/local-app-control-release-signing.properties`
  记录 `status=passed`，签名 APK SHA-256 为
  `892a2b5fdf74494dcf0fa0efebfe80de408f52fb36740687c503262058951cf8`。
- 说明：真实 App 搜索 eval 使用 debug eval receiver；最后已用
  `adb install -r` 覆盖安装 release APK，不作为 Play production 签名证据。

## 2026-06-07 Remote network failure retry recovery

本轮覆盖项：

- 扩展 `PocketMindViewModelTest.remoteNetworkFailureShowsReadableFailureAndFailsTrace`：
  远程模型网络失败后，UI 必须恢复为非 busy、非 generating、仍可用 remote ready 状态，
  并清空 `pendingRemoteSendDisclosure`。
- 同一测试继续模拟第二次远程发送：先重新出现远程发送确认，确认后 remote runtime
  成功返回 `远程回复`，且不会再次调用 `failModelGeneration`。
- 测试 fake `RecordingRemoteChatRuntime.failure` 改为可在用例内解除，用来证明失败后可恢复，
  不是只证明失败文案存在。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteNetworkFailureShowsReadableFailureAndFailsTrace'
```

结果：

- 通过：targeted JVM 测试覆盖远程网络失败后的 UI 解锁、确认 sheet 清理、二次确认发送和成功回复。
- 未执行：本轮不跑模拟器或真机；模拟器 API 36 完整回归仍以
  `build/verification/product-contract-regression-current/regression-emulator.properties`
  的 51/51 结果为当前完整自动回归证据。

## 2026-06-07 Emulator API matrix readiness check

本轮覆盖项：

- 按 release matrix 要求检查 API 28、32、33、34、36 的 `google_apis/arm64-v8a`
  system image 和 AVD readiness。
- 该检查只判断本地 Android SDK/AVD 环境是否能启动矩阵回归；不代表应用测试失败。

验证命令：

```bash
scripts/check_emulator_api_matrix.sh --required-apis "28 32 33 34 36"
shasum -a 256 build/verification/emulator-api-matrix-readiness.properties
```

结果：

- 失败：`build/verification/emulator-api-matrix-readiness.properties`
  记录 `status=failed`、`failedTarget=api-matrix-readiness`。
- 缺失项：API 28、32、33、34 均缺 system image 和 AVD；API 36 已具备。
- evidence SHA-256：
  `deff3969e6a85e9c58e7967115d242538367a6d41da60f6aabd626dc35992b91`。
- 下一步：补齐 API 28/32/33/34 的 `system-images;android-<api>;google_apis;arm64-v8a`
  和对应 AVD 后，才能运行完整 `scripts/regression_emulator_api_matrix.sh`。

## 2026-06-07 Release screenshots current evidence refresh

本轮覆盖项：

- `MODEL_MANAGER_POSITIONING_TEXT` 改为包含精确发布合同短语“本地可用”，同时保留
  “可离线使用”的产品含义。
- `confirmation-sheet` release 截图合同从旧的剪贴板动作确认，调整为当前 clean
  remote screenshot flow 的远程发送披露：`即将发送到远程模型`、`确认后才会`、`取消`。
  设备动作确认仍由独立 instrumentation / JVM 合同覆盖。
- `capture_release_screenshots.sh` 在后台任务页先等待顶部内容，再滚动到审计区，确保
  `最近审计日志` 和 `最近 Agent 轨迹` 同屏进入截图 UI dump。
- `docs/release_validation_record.json` 重新绑定四张 PNG 和
  `release-screenshots.properties` 的 SHA-256。

验证命令：

```bash
./gradlew :app:testDebugUnitTest --tests 'com.bytedance.zgx.pocketmind.ui.PocketMindScreenDisplayTest'
bash -n scripts/capture_release_screenshots.sh scripts/verify_release_validation_record.sh scripts/test_validation_scripts.sh
ANDROID_HOME="$HOME/Library/Android/sdk" ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" \
  AVD_NAME=pocketmind_api36_arm64 \
  ARTIFACT_DIR=build/verification/release-screenshots-current \
  REPORT_FILE=build/verification/release-screenshots-current/release-screenshots.properties \
  EMULATOR_ARGS='-no-window -no-audio -no-boot-anim -no-snapshot-save' \
  EMULATOR_SELECT_TIMEOUT_SECONDS=180 BOOT_TIMEOUT_SECONDS=360 \
  scripts/capture_release_screenshots.sh
scripts/verify_release_validation_record.sh \
  --file docs/release_validation_record.json \
  --report build/verification/release-validation-after-screenshots-current.properties
```

结果：

- 通过：`build/verification/release-screenshots-current/release-screenshots.properties`
  记录 `status=passed`、`target=release-screenshots`、`clean_device=1`、
  `api_level=36`、`avd=pocketmind_api36_arm64`。
- 通过：四张截图 `chat-home`、`model-manager`、`confirmation-sheet`、
  `background-tasks-or-audit` 均记录 PNG SHA、UI dump、UI dump SHA、
  `visualRegression=passed` 和 `requiredText`。
- evidence SHA-256：`release-screenshots.properties` 为
  `f274bc04f8c730cae2aba783a0e763ae213094d1d4747c800093f6f79fe94b27`。
- 预期失败：release validation record 仍未 approved；剩余失败为真机、API 28/32/33/34、
  manual acceptance、flow matrix、performance sanity、reviewer/date，截图相关失败已消失。

## 2026-06-07 CI API matrix release path

本轮覆盖项：

- `.github/workflows/android.yml` 新增 `emulator-api-matrix` job，只在
  `workflow_dispatch` 和 weekly schedule 运行，避免拉高普通 PR 成本。
- 该 job 使用 `scripts/prepare_emulator_api_matrix.sh` 准备 API 28、32、33、34、36
  的 `google_apis/arm64-v8a` system image 与 AVD，然后运行
  `scripts/regression_emulator_api_matrix.sh` 生成矩阵 evidence。
- `final-release-gate` 现在显式依赖 `emulator-api-matrix`，并下载
  `android-emulator-api-matrix-evidence` artifact，防止 public release gate 在没有
  API matrix 证据的情况下继续。
- `scripts/test_validation_scripts.sh` 增加 workflow 合同检查，锁住 matrix job、
  required API 列表、prepare/regression 脚本和 artifact 名称。

验证命令：

```bash
bash -n scripts/test_validation_scripts.sh scripts/verify_local.sh \
  scripts/prepare_emulator_api_matrix.sh scripts/regression_emulator_api_matrix.sh
scripts/test_validation_scripts.sh
```

结果：

- 通过：validation script tests，覆盖新增 CI API matrix workflow 合同。
- 未执行：本轮未在 GitHub Actions 实际跑 API matrix；本地 Android SDK 仍只具备 API 36
  AVD，API 28/32/33/34 需要由 CI job 或本机 `prepare_emulator_api_matrix.sh --apply`
  准备后才能产生真实 matrix report。
- 仍需真机：`performanceSanity` 不能用 emulator evidence 闭合；`verify_perf_baseline.sh`
  会拒绝 `deviceSerial=emulator-*`。

## 2026-06-07 Release operations API matrix evidence gate

本轮覆盖项：

- `docs/release_operations_record.json` 的 `ci` 区块新增 `apiMatrix` evidence 槽位，
  记录 `jobName`、`artifactName` 和矩阵 report path/SHA。
- `scripts/verify_release_operations_record.sh` 现在要求 `ci.apiMatrix` 指向
  `target=regression-emulator-api-matrix` 的通过报告，并校验：
  `requiredApis=28,32,33,34,36`、`passedApis=28,32,33,34,36`、`failedApis` 为空、
  readiness report 存在，以及每个 API child regression report 的 path/SHA/API level、
  clean-device、AndroidTest count、device report 和 instrumentation output 字段。
- `scripts/test_validation_scripts.sh` 的 operations approved fixture 补齐 API matrix
  evidence，并增加弱 matrix report 负例，防止缺 API 或失败 API 被误收。

验证命令：

```bash
bash -n scripts/verify_release_operations_record.sh scripts/test_validation_scripts.sh
python3 -m json.tool docs/release_operations_record.json >/dev/null
scripts/test_validation_scripts.sh
```

结果：

- 通过：validation script tests，覆盖 release operations verifier 对 CI API matrix evidence
  的正例与弱证据拒绝。
- 未执行：本轮未产生真实 GitHub Actions API matrix artifact；该证据仍需
  `emulator-api-matrix` workflow job 实际运行后绑定到 operations record。

## 2026-06-07 Product positioning and first-run model recovery

本轮覆盖项：

- 首屏空状态新增 `home_positioning_panel`，直接回答“为什么装它”：
  隐私优先的随身 AI 助手，本地可用，远程多模态可选，设备动作必须确认执行。
- `MainActivitySmokeTest` 锁住首屏定位、隐私入口定位、远程配置入口滚动路径；
  `MainActivityFirstRunSetupUiTest` 锁住 first-run 页面也能看到同一产品定位。
- `remote_vision_image_input` 能力矩阵改为 `confirmationPolicy=Required`，
  明确图片只在远程发送预览确认后才会随请求发送；不支持视觉时仍直接提示，不强制 OCR。
- `startSetupModelDownload()` 不再在目录/空间/下载启动失败前提前关闭 first-run；
  只有下载任务真正入队后才标记 setup dismissed。
- 下载失败分支清空 `downloadedBytes` 和 `totalBytes`，避免失败后继续显示旧进度块。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests com.bytedance.zgx.pocketmind.ui.PocketMindScreenDisplayTest \
  --tests com.bytedance.zgx.pocketmind.docs.CapabilityMatrixDocumentationTest \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.startSetupModelDownloadKeepsFirstRunOpenWhenPreflightFails' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.monitorDownloadFailureClearsPendingDeletesTargetAndShowsReason' \
  :app:compileDebugAndroidTestKotlin
ANDROID_HOME="$HOME/Library/Android/sdk" ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" \
  ANDROID_SERIAL=emulator-5554 CLEAN_DEVICE=1 EMULATOR_SELECT_TIMEOUT_SECONDS=60 \
  BOOT_TIMEOUT_SECONDS=180 \
  INSTRUMENTATION_CLASS='com.bytedance.zgx.pocketmind.MainActivitySmokeTest,com.bytedance.zgx.pocketmind.MainActivityFirstRunSetupUiTest' \
  INSTRUMENTATION_TIMEOUT_SECONDS=360 LOGCAT_TAIL_LINES=5000 \
  ARTIFACT_DIR=build/verification/home-positioning-first-run-smoke-current \
  scripts/verify_emulator.sh
```

结果：

- 通过：targeted JVM tests 和 AndroidTest Kotlin 编译。
- 通过：API 36 arm64 `pocketmind_api36_arm64` clean-device emulator smoke，
  `build/verification/home-positioning-first-run-smoke-current/device-verification.properties`
  记录 `status=passed`、`instrumentation_test_count=7`、`clean_device=1`。
- 通过：`build/verification/home-positioning-first-run-smoke-current/crash-anr-smoke.properties`
  记录 `status=passed`、所有 crash/ANR/LiteRT counters 为 0，SHA-256 为
  `cea6602ec98319785bebbd6e8dd94739d75a39526afd1d7a828c67dda8584b78`。
- 未完成：本轮不改变“远程视觉默认可用”的产品决策；未知远程模型若实际不支持图片，
  仍由远程错误处理给出“不支持图片”提示。

## 2026-06-07 Crash/ANR operations evidence binding

本轮覆盖项：

- `verify_release_operations_record.sh` 不再只接受 `crashAnrSmoke.evidence`
  的 path/SHA；必须解析 smoke report，并要求 `status=passed`、
  `target=crash-anr-smoke-evidence`、`operationsRecordField=crashAnrSmoke.evidence`、
  `logcatAnalyzed=true`。
- operations 记录里的 `window`、`track`、`failureEvidencePolicy` 必须和 smoke
  report 一致；五个 `no*` 结果必须为 true，六个 crash/ANR/LiteRT signal counter
  必须为 0。
- smoke report 引用的 device report、instrumentation output、logcat 必须存在，并且
  SHA-256 与 size 都匹配；device report 的 serial/API/ABI/test count/logcat 路径也必须和
  smoke report 对齐。
- 不修改 `docs/release_operations_record.json` 的 pending 状态，不把模拟器 evidence
  伪装成正式运营审批。

验证命令：

```bash
bash -n scripts/verify_release_operations_record.sh scripts/test_validation_scripts.sh
scripts/test_validation_scripts.sh
ANDROID_HOME="$HOME/Library/Android/sdk" ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" \
  ANDROID_SERIAL=emulator-5554 CLEAN_DEVICE=1 EMULATOR_SELECT_TIMEOUT_SECONDS=60 \
  BOOT_TIMEOUT_SECONDS=180 INSTRUMENTATION_CLASS='com.bytedance.zgx.pocketmind.MainActivitySmokeTest' \
  INSTRUMENTATION_TIMEOUT_SECONDS=240 LOGCAT_TAIL_LINES=5000 \
  ARTIFACT_DIR=build/verification/crash-anr-operations-smoke-clean-current \
  scripts/verify_emulator.sh
```

结果：

- 通过：validation script self-tests，覆盖 approved operations 正例、
  failed smoke report 但 SHA 匹配的负例，以及 logcat SHA 被篡改的负例。
- 通过：API 36 arm64 `pocketmind_api36_arm64` clean-device smoke，
  `MainActivitySmokeTest` 6 个测试通过。
- 通过：`build/verification/crash-anr-operations-smoke-clean-current/crash-anr-smoke.properties`
  记录 `status=passed`、`instrumentationTestCount=6`、`logcatSizeBytes=665048`、
  所有 crash/ANR/LiteRT counters 为 0，SHA-256 为
  `8f13e172a916da88725c94f339cd1d180d5154649be032536636a169e9b64930`。
- 未完成：正式 release operations 仍需要真实 CI artifact、production signing、Android
  Vitals/值班、rollback owner 和人工 review 后才能从 pending 变为 approved。

## 2026-06-07 Store data safety privacy notice consistency

本轮覆盖项：

- `verify_store_policy_record.sh` 将 Data Safety 布尔声明和外部接收方映射到
  `docs/privacy_notice.md` 中的具体披露短语；隐私 notice 缺少对应披露时 gate 失败。
- 隐私 notice 匹配改为归一化空白，避免 Markdown 换行拆分短语造成误判。
- `docs/privacy_notice.md` 明确列出 Usage Access、Accessibility、MediaProjection
  与运行时权限分开的特殊授权边界。
- `docs/store_policy_record.json` 同步新的 privacy notice SHA；记录仍保持 pending，
  不把人工 Store/Legal/Policy 审批伪装为已完成。

验证命令：

```bash
scripts/verify_store_policy_record.sh --report build/verification/store-policy-current/store-policy.properties || true
bash -n scripts/verify_store_policy_record.sh scripts/test_validation_scripts.sh
scripts/test_validation_scripts.sh
```

结果：

- 通过：validation script self-tests，覆盖 approved store policy 正例、privacy notice SHA
  mismatch、review evidence SHA mismatch、manifest permission mismatch、placeholder contact/privacy URL，
  以及 Data Safety 与 privacy notice mismatch 负例。
- 当前仓库真实 `docs/store_policy_record.json` 仍按预期失败：
  `build/verification/store-policy-current/store-policy.properties` 记录
  `status=failed`，原因只剩 `status-not-approved`、占位联系邮箱/隐私政策 URL、
  reviewer/date 缺失；不再包含 Data Safety/privacy notice mismatch。
- 未执行模拟器：本轮只加固 Store/Data Safety 发布门禁和文档，不改变 APK runtime。

## 2026-06-07 Media/file permission UI anchors

本轮覆盖项：

- `MainActivityRuntimePermissionUiTest` 新增最近图片 OCR 和最近图片文件摘要确认页覆盖：
  确认页必须展示动作标题、最小读取范围、系统权限说明、LocalOnly 数据边界，并且取消后不执行工具。
- `MainActivityAdaptiveUiTest` 锁定远程模式 composer 附近的附件保护提示：
  主动选择图片才会发送给远程视觉模型，其他附件/分享文本不会读取正文或 OCR。
- Capability Matrix 和 `docs/capability_matrix.json` 把新增 UI 证据挂入
  `share_and_file_picker_input`、`contacts_calendar_reads`、`media_and_recent_ocr`
  和 `accessibility_current_screen_text` 的 required tests。
- `CapabilityMatrixDocumentationTest` 新增敏感能力到 UI/test anchor 的显式映射校验，
  不再只证明 required test class 存在。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests com.bytedance.zgx.pocketmind.docs.CapabilityMatrixDocumentationTest \
  --tests com.bytedance.zgx.pocketmind.ui.PocketMindScreenDisplayTest \
  :app:compileDebugAndroidTestKotlin
AVD_NAME=pocketmind_api36_arm64 EMULATOR_SELECT_TIMEOUT_SECONDS=180 BOOT_TIMEOUT_SECONDS=600 \
  ARTIFACT_DIR=build/verification/media-permission-ui-current \
  INSTRUMENTATION_CLASS='com.bytedance.zgx.pocketmind.MainActivityRuntimePermissionUiTest' \
  INSTRUMENTATION_TIMEOUT_SECONDS=300 scripts/verify_emulator.sh
AVD_NAME=pocketmind_api36_arm64 EMULATOR_SELECT_TIMEOUT_SECONDS=180 BOOT_TIMEOUT_SECONDS=600 \
  ARTIFACT_DIR=build/verification/remote-attachment-notice-current \
  INSTRUMENTATION_CLASS='com.bytedance.zgx.pocketmind.MainActivityAdaptiveUiTest' \
  INSTRUMENTATION_TIMEOUT_SECONDS=300 scripts/verify_emulator.sh
```

结果：

- 通过：targeted JVM tests 和 AndroidTest Kotlin 编译。
- 通过：API 36 arm64 `MainActivityRuntimePermissionUiTest`，
  `build/verification/media-permission-ui-current/device-verification.properties`
  记录 `status=passed`、`instrumentation_test_count=5`。
- 通过：API 36 arm64 `MainActivityAdaptiveUiTest`，
  `build/verification/remote-attachment-notice-current/device-verification.properties`
  记录 `status=passed`、`instrumentation_test_count=3`。

## 2026-06-07 CI final public release gate

本轮覆盖项：

- `workflow_dispatch` 发布路径新增必填 `perf_baseline_file` 输入，可选下载外部 release evidence artifact。
- 新增 `final-release-gate` job，显式依赖 `verify`、`emulator-regression`、
  `release-artifact-archive` 和 `protected-signing`；签名成功后下载 local/emulator/release/signed evidence，
  再以 `PUBLIC_RELEASE=1` 运行 `scripts/verify_release_gate.sh`。
- final gate 上传 `android-final-release-gate-evidence`；正式发布状态以该 job 成败为准。
- `scripts/test_validation_scripts.sh` 锁定 workflow 合同，防止未来回退成“只签名不跑 release gate”。

验证命令：

```bash
bash -n scripts/test_validation_scripts.sh scripts/verify_release_gate.sh
scripts/test_validation_scripts.sh
```

结果：

- 通过：workflow/release validation script self-tests。
- 说明：本轮未把 pending release records 改成 approved；`PUBLIC_RELEASE=1` gate 仍会在
  `docs/release_validation_record.json`、`docs/release_operations_record.json` 等真实 RC 证据未完成时 fail-closed。

## 2026-06-07 Voice input prominent consent gate

本轮覆盖项：

- 语音输入从“点击麦克风后直接进入系统权限/收音流程”改为先展示 App 内明确同意弹窗；
  用户点“同意并开启语音输入”后才会请求麦克风权限或开始收音。
- 语音入口近场文案同步说明：系统语音转写、只进入输入框、不自动发送、不读取本地音频文件、
  开启前先确认。
- Capability Matrix 和 `docs/capability_matrix.json` 同步把 `voice_transcript_input`
  标记为需要确认，并把 `PocketMindVoiceInputConsentUiTest` 纳入必测用例。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests com.bytedance.zgx.pocketmind.ui.PocketMindScreenDisplayTest \
  --tests com.bytedance.zgx.pocketmind.docs.CapabilityMatrixDocumentationTest \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.voicePermissionFailureClearsCaptureAndCanRecoverWithoutSending' \
  :app:compileDebugAndroidTestKotlin
AVD_NAME=pocketmind_api36_arm64 EMULATOR_SELECT_TIMEOUT_SECONDS=180 BOOT_TIMEOUT_SECONDS=600 \
  ARTIFACT_DIR=build/verification/voice-input-consent-current \
  INSTRUMENTATION_CLASS='com.bytedance.zgx.pocketmind.PocketMindVoiceInputConsentUiTest' \
  INSTRUMENTATION_TIMEOUT_SECONDS=240 scripts/verify_emulator.sh
```

结果：

- 通过：targeted JVM tests，覆盖展示文案、Capability Matrix/JSON 合同和语音权限失败恢复。
- 通过：AndroidTest Kotlin 编译。
- 通过：API 36 arm64 `PocketMindVoiceInputConsentUiTest`，
  `build/verification/voice-input-consent-current/device-verification.properties`
  记录 `status=passed`、`instrumentation_test_count=1`。

## 2026-06-07 Memory-disabled controls and remote send disclosure

本轮覆盖项：

- 本地记忆关闭后，已保存的长期记忆仍在模型管理页可见且可删除；关闭状态只停止召回和自动携带，
  不再把用户锁在无法清理旧记忆的状态。
- 记忆关闭时，任务状态记忆仍会被移除并禁止重新索引；偏好/事实类长期记忆保留在 UI 控制面板中。
- 长期记忆删除按钮的无障碍名称包含目标记忆内容，避免多条记录时测试和用户都无法区分删除目标。
- 远程发送确认区分当前输入和工具结果续写，并提示远程服务方可能按其政策记录或保留请求、图片和响应。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.memoryDisabledDoesNotIndexScheduledTaskStateOnStartupRefreshOrSend' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.updateMemoryDisabledRemovesActiveTaskStateMemoryAndPreventsResync' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.memoryDisabledKeepsSavedRecordsVisibleAndClearable' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteSendDisclosureBlocksRuntimeUntilConfirmed' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteModelToolBatchExecutionRequiresConfirmationAndObservationBeforeCompletion' \
  --tests com.bytedance.zgx.pocketmind.ui.PocketMindScreenDisplayTest \
  :app:compileDebugAndroidTestKotlin
AVD_NAME=pocketmind_api36_arm64 EMULATOR_SELECT_TIMEOUT_SECONDS=180 BOOT_TIMEOUT_SECONDS=600 \
  ARTIFACT_DIR=build/verification/memory-disabled-clear-current \
  INSTRUMENTATION_CLASS='com.bytedance.zgx.pocketmind.MainActivityLongTermMemoryUiTest' \
  INSTRUMENTATION_TIMEOUT_SECONDS=240 scripts/verify_emulator.sh
```

结果：

- 通过：targeted JVM tests 和 `PocketMindScreenDisplayTest`，覆盖记忆关闭后的可见/可删控制、
  远程发送保留提示、工具结果续写披露类型。
- 通过：AndroidTest Kotlin 编译。
- 通过：API 36 arm64 `MainActivityLongTermMemoryUiTest`，
  `build/verification/memory-disabled-clear-current/device-verification.properties`
  记录 `status=passed`、`instrumentation_test_count=1`。
- 说明：本轮早期模拟器尝试暴露了测试选择器问题；最终改为按具体记忆内容定位删除按钮后通过。

## 2026-06-07 Release fail-closed and deletion control gate

本轮覆盖项：

- GitHub Actions `workflow_dispatch` 发布包路径收紧：`verify` 也在手动发布时运行；
  `release-artifact-archive` 依赖 local verify 和 emulator regression；
  `protected-signing` 显式依赖 local verify、emulator regression 和 artifact archive。
- 生产签名缺少 keystore、alias、password 或 expected signing certificate 时，
  `protected-signing` 写入 `status=failed`、`failedTarget=environment`、
  `reason=protected-signing-secrets-not-configured` 并退出失败，不再以 skipped 成功。
- 模型管理页文案收敛为“本地离线可用；远程多模态可选”，隐私说明页改为面向用户的数据边界说明。
- 当前会话删除控制覆盖唯一会话：删除最后一个会话会清除旧消息、旧 session Agent 轨迹和待发送分享草稿，
  然后自动创建一个新的空会话。

验证命令：

```bash
scripts/test_validation_scripts.sh
./gradlew :app:testDebugUnitTest \
  --tests com.bytedance.zgx.pocketmind.data.SessionRepositoryTest \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.deleteActiveSessionClearsSessionAgentTraceAndPendingConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.deleteOnlyActiveSessionClearsMessagesAndPendingSharedDraft' \
  --tests com.bytedance.zgx.pocketmind.ui.PocketMindScreenDisplayTest \
  :app:compileDebugAndroidTestKotlin
git diff --check
```

结果：

- 通过：validation script self-tests，覆盖 workflow 发布依赖、protected-signing fail-closed、
  fresh-start helper 和模型管理新文案。
- 通过：targeted JVM tests，覆盖唯一会话删除、旧多会话删除、UI 信任边界文案和仓库删除行为。
- 通过：AndroidTest Kotlin 编译，确认 smoke 文案同步后可编译。
- 通过：`git diff --check`。

## 2026-06-07 First-run setup and skip gate

本轮覆盖项：

- 新用户、无本地模型且无远程配置时，`showFirstRunSetup` 从真实 first-run 设置读取，
  不再写死为 false；首次启动会展示离线基础问答下载/跳过路径。
- 已跳过、已有远程配置或保存远程配置后不再重新弹 first-run；开始下载、导入或校验
  本地模型成功后也会收起 first-run。
- 新增 API 36 模拟器 UI 用例，覆盖 first-run 默认选中聊天模型、下载按钮可见、
  点击“先跳过”后回到主界面。
- `verify_fresh_start_main_shell_emulator.sh` 不再把可跳过的 first-run 引导当失败；
  release flow evidence 为 `firstInstall` 新增 first-run 可见、默认聊天模型和跳过后主界面字段。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.startupShowsSetupOnFreshInstallWhenNoLocalOrRemoteModelIsAvailable' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.startupKeepsSetupDismissedWhenNoLocalOrRemoteModelIsAvailable' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.startupDoesNotReopenSetupWhenRemoteModelIsConfigured' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.savingRemoteConfigDismissesFreshSetup'
./gradlew :app:compileDebugAndroidTestKotlin
scripts/test_validation_scripts.sh
AVD_NAME=pocketmind_api36_arm64 EMULATOR_SELECT_TIMEOUT_SECONDS=180 BOOT_TIMEOUT_SECONDS=600 \
  ARTIFACT_DIR=build/verification/first-run-setup-ui-current \
  INSTRUMENTATION_CLASS='com.bytedance.zgx.pocketmind.MainActivityFirstRunSetupUiTest' \
  INSTRUMENTATION_TIMEOUT_SECONDS=240 scripts/verify_emulator.sh
AVD_NAME=pocketmind_api36_arm64 EMULATOR_SELECT_TIMEOUT_SECONDS=180 BOOT_TIMEOUT_SECONDS=600 \
  ARTIFACT_DIR=build/verification/fresh-start-first-run-skip-current \
  scripts/verify_fresh_start_main_shell_emulator.sh
bash scripts/verify_local.sh
AVD_NAME=pocketmind_api36_arm64 EMULATOR_SELECT_TIMEOUT_SECONDS=180 BOOT_TIMEOUT_SECONDS=600 \
  ARTIFACT_DIR=build/verification/first-run-main-smoke-current \
  INSTRUMENTATION_CLASS='com.bytedance.zgx.pocketmind.MainActivitySmokeTest' \
  INSTRUMENTATION_TIMEOUT_SECONDS=240 scripts/verify_emulator.sh
```

结果：

- 通过：targeted JVM first-run 合同测试。
- 通过：AndroidTest 编译和 validation script self-tests。
- 通过：API 36 arm64 first-run UI 用例，
  `build/verification/first-run-setup-ui-current/device-verification.properties`
  记录 `status=passed`、`instrumentation_test_count=1`。
- 通过：fresh-start main shell helper，
  `build/verification/fresh-start-first-run-skip-current/fresh-start-main-shell.properties`
  记录 `status=passed`、`main_shell_copy_visible=true`、`model_manager_click_opened=true`。
- 通过：`scripts/verify_local.sh`，包含 JVM tests、lint、debug/androidTest APK、
  release APK/AAB 组装和 artifact scan。
- 通过：API 36 arm64 `MainActivitySmokeTest`，
  `build/verification/first-run-main-smoke-current/device-verification.properties`
  记录 `status=passed`、`instrumentation_test_count=6`。

## 2026-06-07 No-model main shell entry polish

本轮覆盖项：

- 无本地/远程模型时，首屏从“接入默认页”观感收敛为主界面：主标题改为
  “开始和 PocketMind 对话”，先展示离线问答、显式记忆、图片/文件和确认动作。
- 模型接入降级为紧凑的“模型未就绪”状态条；首屏移除模型选择 chip，模型切换仍保留在模型管理页。
- 首页按钮文案保持单行：配置远程模型、下载模型、导入模型、模型管理。
- Targeted smoke 固定 `home_capability_pills` 与 `model_startup_banner`，避免回退到大接入面板。

验证命令：

```bash
./gradlew --no-daemon -Pkotlin.incremental=false :app:compileDebugKotlin \
  :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.ui.PocketMindScreenDisplayTest' \
  --tests 'com.bytedance.zgx.pocketmind.docs.CapabilityMatrixDocumentationTest'
ANDROID_SERIAL=emulator-5554 CLEAN_DEVICE=1 \
  ARTIFACT_DIR=build/verification/main-shell-entry-final \
  INSTRUMENTATION_CLASS='com.bytedance.zgx.pocketmind.MainActivitySmokeTest#chatShellShowsModelManager,com.bytedance.zgx.pocketmind.MainActivitySmokeTest#quickRemoteConfigEntryOpensRemoteModelForm,com.bytedance.zgx.pocketmind.MainActivitySmokeTest#privacyButtonOpensAppPrivacyNotice' \
  scripts/verify_emulator.sh
ANDROID_SERIAL=emulator-5554 \
  ARTIFACT_DIR=build/verification/main-shell-entry-fresh-start-final \
  MAIN_COPY_TEXT='开始和 PocketMind 对话' \
  scripts/verify_fresh_start_main_shell_emulator.sh
/Users/bytedance/Library/Android/sdk/platform-tools/adb -s emulator-5554 shell pm clear com.bytedance.zgx.pocketmind
/Users/bytedance/Library/Android/sdk/platform-tools/adb -s emulator-5554 shell am force-stop com.bytedance.zgx.pocketmind.test
/Users/bytedance/Library/Android/sdk/platform-tools/adb -s emulator-5554 shell am force-stop com.bytedance.zgx.pocketmind
/Users/bytedance/Library/Android/sdk/platform-tools/adb -s emulator-5554 uninstall com.bytedance.zgx.pocketmind.test
/Users/bytedance/Library/Android/sdk/platform-tools/adb -s emulator-5554 shell pm clear com.bytedance.zgx.pocketmind
/Users/bytedance/Library/Android/sdk/platform-tools/adb -s emulator-5554 shell am start -W -n com.bytedance.zgx.pocketmind/.MainActivity
/Users/bytedance/Library/Android/sdk/platform-tools/adb -s emulator-5554 exec-out uiautomator dump /dev/tty \
  > build/verification/current-main-shell-after-wording/current.xml
/Users/bytedance/Library/Android/sdk/platform-tools/adb -s emulator-5554 exec-out screencap -p \
  > build/verification/current-main-shell-after-wording/current.png
```

结果：

- 通过：debug Kotlin、androidTest Kotlin 编译和 targeted JVM tests。
- 通过：API 36 arm64 targeted smoke，
  `build/verification/main-shell-entry-final/device-verification.properties`
  包含 `status=passed`、`instrumentation_test_count=3`。
- 通过：API 36 arm64 fresh-start，
  `build/verification/main-shell-entry-fresh-start-final/fresh-start-main-shell.properties`
  包含 `status=passed`、`first_run_setup_visible=false`、`main_shell_copy_visible=true`。
- 通过：当前模拟器已安装并停在新首页；UI dump 显示“开始和 PocketMind 对话”、
  “没有模型时只展示启动选项”、“模型未就绪”、“配置远程模型，立即试用”、
  “下载模型”、“导入模型”和“模型管理”。截图保存于
  `build/verification/current-main-shell-after-wording/current.png`。
- 说明：targeted smoke 后模拟器上曾残留 `com.bytedance.zgx.pocketmind.test` instrumentation，
  导致 `uiautomator dump` 报 UiAutomation already registered，并短暂显示空白测试窗口；force-stop
  并卸载 test APK 后，正式 App 进程表只剩 `com.bytedance.zgx.pocketmind`，当前首页恢复正常。

## 2026-06-07 Calendar local planning and skill smoke stability

本轮覆盖项：

- Safety policy 不再把 ISO 日期/时间窗口误判为手机号，避免“查忙闲 2026-06-01T09:00:00Z
  到 2026-06-01T10:00:00Z”被远程/本地边界拦错。
- 远程模式下，日历忙闲先走远程发送确认；确认后仍在本地生成日历权限动作草稿，不调用远程模型。
- Runtime permission UI 增加日历忙闲确认卡验证：展示日历权限说明，不展示 special access。
- Skill UI smoke 以 ready remote config 启动并等待可用输入框，降低被无模型首屏状态干扰的概率。
- GitHub emulator regression workflow 扩展到 PR/push，防止只在手动/定时任务中发现模拟器回归。

验证命令：

```bash
./gradlew --no-daemon -Pkotlin.incremental=false :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.safety.SafetyPolicyTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteModePlansCalendarAvailabilityLocallyAfterSendDisclosure' \
  --tests 'com.bytedance.zgx.pocketmind.ui.PocketMindScreenDisplayTest'
ANDROID_SERIAL=emulator-5554 CLEAN_DEVICE=1 \
  ARTIFACT_DIR=build/verification/calendar-skill-runtime-final \
  INSTRUMENTATION_CLASS='com.bytedance.zgx.pocketmind.MainActivityRuntimePermissionUiTest#calendarAvailabilityConfirmationShowsRuntimePermissionRequirementWithoutSpecialAccess,com.bytedance.zgx.pocketmind.MainActivitySkillUiTest' \
  scripts/verify_emulator.sh
```

结果：

- 通过：targeted JVM tests，覆盖 ISO 时间窗口安全策略、远程模式日历本地规划和首屏文案合同。
- 通过：API 36 arm64 targeted emulator，
  `build/verification/calendar-skill-runtime-final/device-verification.properties`
  包含 `status=passed`、`instrumentation_test_count=5`，覆盖 4 个 skill UI 用例和 1 个日历权限 UI 用例。

## 2026-06-07 Start path and sensitive capability disclosure contract

本轮覆盖项：

- 无本地/远程模型时，主界面状态文案从失败口吻收敛为
  “选择远程模型或下载本地模型后即可开始”，输入占位改为“配置远程或下载本地后提问”。
- `CapabilityMatrix` 新增 `sensitiveCapabilityDisclosures`，把远程模型发送、语音/麦克风、
  分享和文件选择、设备动作/外部 App、联系人/日历、媒体与最近 OCR、Usage Stats、
  Accessibility 当前屏幕文本、MediaProjection 当前屏幕截图 OCR 的数据范围、同意边界、
  远程边界、撤销/清除路径和必测用例固化为机器可读合同。
- App 内隐私说明页新增“敏感能力披露”区，直接展示上述能力，不再只用笼统的“敏感权限”概括。
- `docs/capability_matrix.json` 与代码逐字段校验；测试禁止披露承诺“清理/删除审计记录”
  这类当前没有用户入口的能力。
- Smoke 隐私入口用例扩展到敏感披露区，确认模拟器中能看到设备动作、Usage Stats 和当前屏幕截图 OCR 披露。

验证命令：

```bash
./gradlew --no-daemon -Pkotlin.incremental=false :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.docs.CapabilityMatrixDocumentationTest' \
  --tests 'com.bytedance.zgx.pocketmind.ui.PocketMindScreenDisplayTest' \
  --tests 'com.bytedance.zgx.pocketmind.docs.AgentCoreDocumentationTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.startupDoesNotShowSetupOnFreshInstallWhenNoLocalOrRemoteModelIsAvailable' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.startupKeepsSetupDismissedWhenNoLocalOrRemoteModelIsAvailable'
ANDROID_SERIAL=emulator-5554 CLEAN_DEVICE=1 \
  ARTIFACT_DIR=build/verification/sensitive-disclosure-ui-current \
  INSTRUMENTATION_CLASS='com.bytedance.zgx.pocketmind.MainActivitySmokeTest#chatShellShowsModelManager,com.bytedance.zgx.pocketmind.MainActivitySmokeTest#quickRemoteConfigEntryOpensRemoteModelForm,com.bytedance.zgx.pocketmind.MainActivitySmokeTest#privacyButtonOpensAppPrivacyNotice' \
  scripts/verify_emulator.sh
ANDROID_SERIAL=emulator-5554 \
  ARTIFACT_DIR=build/verification/start-path-guidance-fresh-start-current \
  scripts/verify_fresh_start_main_shell_emulator.sh
ANDROID_SERIAL=emulator-5554 CLEAN_DEVICE=1 \
  ARTIFACT_DIR=build/verification/runtime-permission-disclosure-current2 \
  INSTRUMENTATION_CLASS='com.bytedance.zgx.pocketmind.MainActivityRuntimePermissionUiTest' \
  scripts/verify_emulator.sh
scripts/verify_local.sh
```

结果：

- 通过：`CapabilityMatrixDocumentationTest` 和 `PocketMindScreenDisplayTest`，覆盖敏感披露
  JSON/代码一致性、必测类存在性、数据/同意/远程/撤销字段完整性，以及隐私页展示文本。
- 通过：`AgentCoreDocumentationTest`，确认 README 顶部产品合同、首屏信任流和 manual/automatic
  验收分离仍符合文档契约。
- 通过：`PocketMindViewModelTest` 两条无模型启动回归，确认无本地/远程模型时保持主界面待准备态，
  状态文案收敛为“选择远程模型或下载本地模型后即可开始”。
- 通过：API 36 arm64 模拟器 targeted smoke，
  `build/verification/sensitive-disclosure-ui-current/device-verification.properties`
  包含 `status=passed`、`instrumentation_test_count=3`，覆盖模型管理、远程配置入口和
  隐私说明里的敏感披露区。
- 通过：API 36 arm64 fresh-start，
  `build/verification/start-path-guidance-fresh-start-current/fresh-start-main-shell.properties`
  包含 `status=passed`、`first_run_setup_visible=false`、`main_shell_copy_visible=true`。
- 通过：API 36 arm64 runtime permission UI smoke，
  `build/verification/runtime-permission-disclosure-current2/device-verification.properties`
  包含 `status=passed`、`instrumentation_test_count=2`；覆盖联系人权限说明和最近截图 OCR
  图片权限说明/取消路径。
- 通过：`scripts/verify_local.sh`，覆盖 validation script self-tests、JVM tests、lint、
  debug/androidTest APK assembly、release APK/AAB assembly 和 Android artifact scan。
- 未作为通过证据：日历忙闲 Activity UI prompt 链路未稳定触发确认卡；日历权限仍由
  `AgentRuntimePermissionPolicyTest` 的 JVM 合同覆盖，正式发布前仍需补稳定 UI 或手工验收证据。
- 未作为手工验收通过证据：麦克风系统弹窗、Android 系统文件选择器、Usage Access、
  Accessibility 服务和 MediaProjection 前台同意仍需要正式 manual acceptance。

## 2026-06-07 Product contract screen check and current regression

本轮覆盖项：

- API 36 arm64 模拟器安装当前包后复核首屏，不再把“仍在待准备/接入态”误判为测试占用或
  UI 卡死。
- 复核“配置远程模型（无需下载）”点击可打开模型管理的远程配置表单；无模型时页面保持
  `待准备` 是因为没有下载/导入本地模型，也没有配置远程模型。
- API 36 arm64 完整模拟器回归重新绑定到当前 product-contract UI 文案后的 35 个
  AndroidTest。
- release flow matrix candidate 继续 fail closed，但失败目标已从 stale source regression
  收敛为缺正式批准的 release flow evidence。

验证命令：

```bash
ARTIFACT_DIR=build/verification/product-contract-regression-current \
  ANDROID_SERIAL=emulator-5554 CLEAN_DEVICE=1 scripts/regression_emulator.sh
/Users/bytedance/Library/Android/sdk/platform-tools/adb devices -l
/Users/bytedance/Library/Android/sdk/platform-tools/adb -s emulator-5554 \
  shell dumpsys package com.bytedance.zgx.pocketmind
/Users/bytedance/Library/Android/sdk/platform-tools/adb -s emulator-5554 \
  exec-out uiautomator dump /dev/tty > build/verification/current-screen/ui.xml
/Users/bytedance/Library/Android/sdk/platform-tools/adb -s emulator-5554 \
  exec-out screencap -p > build/verification/current-screen/current.png
/Users/bytedance/Library/Android/sdk/platform-tools/adb -s emulator-5554 \
  shell input tap 540 1678
ARTIFACT_DIR=build/verification/product-contract-flow-matrix-current \
  REPORT_FILE=build/verification/product-contract-flow-matrix-current/release-flow-matrix-candidate-evidence.properties \
  scripts/collect_release_flow_matrix_evidence.sh
```

结果：

- 通过：API 36 arm64 完整模拟器回归，
  `build/verification/product-contract-regression-current/regression-emulator.properties`
  包含 `status=passed`、`source_android_test_count=35`、
  `expected_android_test_count=35`、`actual_android_test_count=35`、`clean_device=1`；
  report SHA-256 为
  `ed29aeb0a5cfe0508bf273ecfbbc2c6c8a95f8a1d1306c4a6f108410ca8a2844`。
- 通过：当前模拟器安装包前台为 `com.bytedance.zgx.pocketmind/.MainActivity`，
  `lastUpdateTime=2026-06-07 05:37:02`；UI dump 显示“隐私优先的随身 AI 助手”、
  “配置远程模型（无需下载）”和“未找到可用模型，请下载、导入或配置远程模型”。
- 通过：点击“配置远程模型（无需下载）”后打开模型管理远程页，UI dump 显示
  “模型管理”、“选择本地离线或可选远程；远程发送和设备动作仍会先确认。”、
  “服务地址”、“模型名”、“API Key”和“图片输入”。
- 未通过但按预期生成失败证据：当前 flow matrix candidate report
  `build/verification/product-contract-flow-matrix-current/release-flow-matrix-candidate-evidence.properties`
  包含 `status=failed`、`failedTarget=flow-matrix`、
  `reason=missing-approved-release-evidence-firstInstall,...`、
  `sourceAndroidTestCount=35`，并生成 13 条 candidate-only flow evidence。
- 未执行：真机复核。当前 `adb devices -l` 只发现 `emulator-5554`，没有物理设备 serial；
  因此用户手上真机的页面状态不能记录为已验证或已修复。

## 2026-06-07 API36 regression refresh and stale evidence guard

本轮覆盖项：

- API 36 arm64 完整模拟器回归重新绑定到当前 AndroidTest 源测试数：35 个源测试、
  35 个预期测试、35 个实际执行测试全部通过。
- Smoke instrumentation 在每个测试实例启动 Activity 前重置持久状态，避免远程/本地模式状态污染导致
  “配置远程模型”入口和首屏文案用例不稳定。
- release flow 候选证据脚本会比较当前 AndroidTest 源测试数与 regression artifact
  记录的测试数；如果 artifact 仍是旧的 28 个测试，会以
  `failedTarget=source-regression`、`reason=emulator-regression-source-test-count-mismatch`
  fail closed。
- release flow 候选覆盖从 8 条扩展到 13 条，新增本地模型下载校验、重启后提醒、
  分享/文件输入、语音输入、最近媒体 OCR 等候选证据。候选证据仍不能替代正式人工 flow
  acceptance。

验证命令：

```bash
ANDROID_SERIAL=emulator-5554 ARTIFACT_DIR=build/verification/smoke-isolated-current \
  INSTRUMENTATION_CLASS='com.bytedance.zgx.pocketmind.MainActivitySmokeTest' \
  scripts/install_and_test_device.sh
ANDROID_HOME="$HOME/Library/Android/sdk" ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" \
  ANDROID_SERIAL=emulator-5554 \
  ARTIFACT_DIR=build/verification/regression-emulator-api36-flow-expanded-current \
  REGRESSION_REPORT_FILE=build/verification/regression-emulator-api36-flow-expanded-current/regression-emulator.properties \
  EMULATOR_SELECT_TIMEOUT_SECONDS=120 BOOT_TIMEOUT_SECONDS=300 \
  scripts/regression_emulator.sh
bash -n scripts/collect_release_flow_matrix_evidence.sh scripts/test_validation_scripts.sh scripts/verify_local.sh
scripts/test_validation_scripts.sh
ARTIFACT_DIR=build/verification/release-flow-matrix-expanded-current \
  REPORT_FILE=build/verification/release-flow-matrix-expanded-current/release-flow-matrix-candidate-evidence.properties \
  scripts/collect_release_flow_matrix_evidence.sh
scripts/verify_release_validation_record.sh --report build/verification/release-validation-current.properties
scripts/verify_local.sh
git diff --check
```

结果：

- 通过：API 36 arm64 targeted SmokeTest，
  `build/verification/smoke-isolated-current/device-verification.properties`
  包含 `status=passed`、`instrumentation_test_count=6`。
- 通过：API 36 arm64 完整模拟器回归，
  `build/verification/regression-emulator-api36-flow-expanded-current/regression-emulator.properties`
  包含 `status=passed`、`source_android_test_count=35`、
  `expected_android_test_count=35`、`actual_android_test_count=35`；report SHA-256 为
  `3f44dbe853251c3901e5f104a05995242281fe7fb30a97d7e08aa3c1dfa80144`。
- 通过：`scripts/test_validation_scripts.sh`，覆盖 validation record、release record、
  perf baseline、screenshot capture、release flow 候选证据和 APK/AAB 扫描脚本自测。
- 未通过但按预期生成失败证据：当前 flow matrix 候选报告
  `build/verification/release-flow-matrix-expanded-current/release-flow-matrix-candidate-evidence.properties`
  包含 `status=failed`、`failedTarget=flow-matrix`、
  `reason=missing-approved-release-evidence-firstInstall,...`、`sourceAndroidTestCount=35`，
  并生成 13 条 candidate-only flow evidence。
- 未通过但按预期生成失败证据：当前 release validation report
  `build/verification/release-validation-current.properties` 包含 `status=failed`，
  失败原因集中在 pending 的真机、API 28/32/33/34、manual acceptance、flow matrix、
  performance sanity 和 reviewer approval。
- 通过：`scripts/verify_local.sh` 完成 validation script self-tests、JVM tests、lint、
  debug/androidTest APK assembly、release APK/AAB assembly 和 Android artifact scan。
- 通过：`git diff --check` 未发现空白或补丁格式问题；本轮 diff 敏感信息扫描未发现 API key、
  bearer token 或远程模型密钥。
- 未作为正式 release 通过证据：API 28/32/33/34、非模拟器真机验收、正式 flow
  acceptance、performance baseline、reviewer approval 仍保持 pending。

## 2026-06-07 Product positioning entry and flow matrix guard

本轮覆盖项：

- 无模型首屏从“主界面已就绪”收敛为“隐私优先的随身 AI 助手”，并在首屏说明中直接回答
  为什么安装：本地模型让基础问答离线可用，远程多模态是可选入口，远程发送和设备动作都先确认。
- 待准备主操作补充“配置远程模型（无需下载）”，降低 2.4 GB 本地模型下载前的启动门槛。
- Smoke instrumentation 固定首屏定位文案、远程模型直达表单和 App 内隐私说明入口。
- release flow matrix 候选证据扩展到本地模型下载校验、重启后提醒、分享/文件输入、
  语音输入、最近媒体 OCR 等核心链路；同时修复候选证据失败路径在空 evidence path
  数组下触发 `set -u` 崩溃的问题。

验证命令：

```bash
./gradlew --no-daemon -Pkotlin.incremental=false :app:compileDebugKotlin \
  :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.ui.PocketMindScreenDisplayTest'
ARTIFACT_DIR=build/verification/product-positioning-entry-smoke-rerun \
  CLEAN_DEVICE=1 \
  ANDROID_SERIAL=emulator-5554 \
  INSTRUMENTATION_CLASS='com.bytedance.zgx.pocketmind.MainActivitySmokeTest#chatShellShowsModelManager,com.bytedance.zgx.pocketmind.MainActivitySmokeTest#quickRemoteConfigEntryOpensRemoteModelForm,com.bytedance.zgx.pocketmind.MainActivitySmokeTest#privacyButtonOpensAppPrivacyNotice' \
  scripts/verify_emulator.sh
ARTIFACT_DIR=build/verification/product-positioning-fresh-start-current \
  ANDROID_SERIAL=emulator-5554 \
  scripts/verify_fresh_start_main_shell_emulator.sh
bash -n scripts/collect_release_flow_matrix_evidence.sh scripts/test_validation_scripts.sh
scripts/test_validation_scripts.sh
ARTIFACT_DIR=build/verification/release-flow-matrix-expanded-current \
  scripts/collect_release_flow_matrix_evidence.sh
/Users/bytedance/Library/Android/sdk/build-tools/36.0.0/aapt dump badging \
  app/build/outputs/apk/debug/app-debug.apk
/Users/bytedance/Library/Android/sdk/platform-tools/adb -s emulator-5554 exec-out screencap -p \
  > build/verification/product-positioning-entry-screen/current.png
```

结果：

- 通过：debug Kotlin、androidTest Kotlin 编译和 `PocketMindScreenDisplayTest`。
- 通过：API 36 arm64 干净模拟器 targeted smoke，
  `build/verification/product-positioning-entry-smoke-rerun/emulator-verification.properties`
  包含 `status=passed`、`avd=pocketmind_api36_arm64`；嵌套
  `device-verification.properties` 包含 `status=passed`、`instrumentation_test_count=3`。
- 通过：API 36 arm64 真实 fresh-start，
  `build/verification/product-positioning-fresh-start-current/fresh-start-main-shell.properties`
  包含 `status=passed`、`first_run_setup_visible=false`、`main_shell_copy_visible=true`。
- 通过：`scripts/test_validation_scripts.sh`，确认 release-flow 脚本自测通过，且 debug
  APK 自测后恢复为有效 APK；`aapt dump badging` 读取到
  `package: name='com.bytedance.zgx.pocketmind' versionCode='1' versionName='0.1.0'`。
- 通过：最新首屏截图和 UI dump 已保存到
  `build/verification/product-positioning-entry-screen/current.png` 和同名 XML；截图显示
  “隐私优先的随身 AI 助手”和“配置远程模型（无需下载）”。
- 未通过但按预期生成失败证据：当前 flow matrix 候选报告
  `build/verification/release-flow-matrix-expanded-current/release-flow-matrix-candidate-evidence.properties`
  包含 `status=failed`、`failedTarget=source-regression`、
  `reason=emulator-regression-source-test-count-mismatch`；当前源测试数为 35，
  既有 regression artifact 记录为 28。需要重跑完整 regression 后，才能把扩展 flow
  matrix 作为 release gate 通过证据。

## 2026-06-07 Model path guidance and sensitive input disclosure

本轮覆盖项：

- 待准备页和模型管理页新增本地/远程/轻量路径说明：本地 E2B 是约 2.4 GB 大下载，
  可离线问答；远程模型可作为不下载本地对话模型的可选路径；当前没有更小的官方推荐聊天模型，
  记忆/动作小模型不是聊天替代。
- 空间不足提示补充远程模型和可信 `.litertlm` 导入作为恢复路径。
- 远程发送预览明确图片字节会发往当前远程地址；确认前仍不发送。
- 输入区展示可见的语音隐私说明：系统语音转写只进入输入框，不自动发送，不读取本地音频文件。
- 顶栏新增“隐私说明”入口，直接打开 App 内隐私说明页，提高隐私政策入口可发现性。
- 复核 README、`docs/privacy_notice.md`、store policy / privacy review pending evidence 已同步模型下载口径，
  且当前 privacy notice SHA 与记录一致。

验证命令：

```bash
./gradlew --no-daemon -Pkotlin.incremental=false :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.ui.PocketMindScreenDisplayTest'
shasum -a 256 docs/privacy_notice.md docs/store_policy_review_evidence/pending.properties \
  docs/privacy_review_evidence/release-pending.properties \
  docs/privacy_review_evidence/security-pending.properties \
  docs/privacy_review_evidence/legal-pending.properties
ARTIFACT_DIR=build/verification/model-path-guidance-adaptive-ui-final \
  ANDROID_SERIAL=emulator-5554 \
  INSTRUMENTATION_CLASS='com.bytedance.zgx.pocketmind.MainActivityAdaptiveUiTest' \
  scripts/install_and_test_device.sh
ANDROID_SERIAL=emulator-5554 ARTIFACT_DIR=build/verification/privacy-entry-smoke INSTRUMENTATION_CLASS='com.bytedance.zgx.pocketmind.MainActivitySmokeTest#privacyButtonOpensAppPrivacyNotice' scripts/install_and_test_device.sh
scripts/verify_local.sh
```

结果：

- 通过：`PocketMindScreenDisplayTest`，覆盖产品定位、模型路径说明、远程发送图片字节披露、
  远程发送确认、语音隐私说明和 run data receipt 展示合同。
- 通过：privacy notice 当前 SHA 为
  `672d8aa10462659c079c3cb467bbe694b32938e562cc1f8a07f5899df0620430`；store policy /
  privacy review pending evidence SHA 已同步到对应 JSON 记录。
- 通过：API 36 arm64 自适应 UI targeted instrumentation，
  `build/verification/model-path-guidance-adaptive-ui-final/device-verification.properties`
  包含 `status=passed`、`instrumentation_test_count=2`。
- 通过：API 36 arm64 隐私入口 smoke，
  `build/verification/privacy-entry-smoke/device-verification.properties` 包含
  `status=passed`、`instrumentation_test_count=1`。
- 通过：`scripts/verify_local.sh`，覆盖 validation script self-tests、JVM tests、lint、
  debug/androidTest APK assembly、release APK/AAB assembly 和 Android artifact scan。
- 未执行：完整 API 矩阵、真机矩阵和人工 Play policy review；这些仍按 release gate 保持
  pending，不作为本轮完成项。

## 2026-06-07 Permission disclosure and adaptive UI smoke

本轮覆盖项：

- 前台应用查询确认页明确说明 UsageStats 只是估计当前前台应用包名/应用名，不读取屏幕内容
  或使用历史，并展示系统“使用情况访问权限”入口。
- 日历忙闲权限 contract 明确只读忙闲时间段，不读取日历标题、地点或参与人。
- 权限 UI instrumentation 改为每个测试先写入远程调试配置再启动 Activity，避免停在
  “先准备模型”的不可输入状态。
- 自适应 UI smoke 覆盖 1.3x 字体下聊天主控件/模型管理可达，以及核心按钮的可访问标签。
- 手动冷启动复核覆盖最新 debug APK 在无本地模型、无远程配置的干净状态下进入主界面待准备态，
  而不是停在旧的启动接入页或系统桌面。

验证命令：

```bash
./gradlew --no-daemon -Pkotlin.incremental=false :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.AgentRuntimePermissionPolicyTest' \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest.foregroundAppActionParserCreatesUsageStatsReadDraft' \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest.calendarAvailabilityParserCreatesReadOnlyDraft'
./gradlew --no-daemon -Pkotlin.incremental=false assembleDebug assembleDebugAndroidTest
ARTIFACT_DIR=build/verification/permission-ui-stable \
  AVD_NAME=pocketmind_api36_arm64 \
  EMULATOR_SELECT_TIMEOUT_SECONDS=180 \
  BOOT_TIMEOUT_SECONDS=360 \
  INSTRUMENTATION_CLASS='com.bytedance.zgx.pocketmind.MainActivityRuntimePermissionUiTest,com.bytedance.zgx.pocketmind.MainActivitySpecialAccessUiTest' \
  scripts/verify_emulator.sh
ARTIFACT_DIR=build/verification/adaptive-ui-stable-final3 \
  ANDROID_SERIAL=emulator-5554 \
  GRADLE_CMD=/usr/bin/true \
  INSTRUMENTATION_CLASS='com.bytedance.zgx.pocketmind.MainActivityAdaptiveUiTest' \
  scripts/verify_emulator.sh
/Users/bytedance/Library/Android/sdk/platform-tools/adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
/Users/bytedance/Library/Android/sdk/platform-tools/adb -s emulator-5554 shell pm clear com.bytedance.zgx.pocketmind
/Users/bytedance/Library/Android/sdk/platform-tools/adb -s emulator-5554 shell am start -W -n com.bytedance.zgx.pocketmind/.MainActivity
```

结果：

- 通过：目标 JVM contract 测试覆盖日历/联系人 runtime permission、UsageStats special
  access、前台应用动作草稿和日历忙闲动作草稿。
- 通过：`assembleDebug assembleDebugAndroidTest` 生成新的 debug 和 androidTest APK。
- 通过：API 36 arm64 权限 UI emulator smoke，
  `build/verification/permission-ui-stable/emulator-verification.properties` 包含
  `status=passed`，嵌套
  `build/verification/permission-ui-stable/device-verification.properties` 包含
  `instrumentation_test_count=3`。
- 通过：API 36 arm64 自适应 UI emulator smoke，
  `build/verification/adaptive-ui-stable-final3/emulator-verification.properties` 包含
  `status=passed`，嵌套
  `build/verification/adaptive-ui-stable-final3/device-verification.properties` 包含
  `instrumentation_test_count=2`。
- 通过：API 36 arm64 模拟器手动冷启动，`am start -W` 返回 `LaunchState: COLD`、
  `TotalTime: 1645`，Window 焦点为 `com.bytedance.zgx.pocketmind/.MainActivity`；UI dump
  显示 `PocketMind`、`主界面已就绪` 和
  `未找到可用模型，请下载、导入或配置远程模型`。证据：
  `build/verification/manual-cold-start-check/cold-start-screen.png`、
  `build/verification/manual-cold-start-check/cold-start-ui.xml`。
- 未执行：真机安装/页面复核。当前 ADB 只发现 `emulator-5554`，没有物理设备序列号，因此不能把
  用户手上真机的“默认页”现象记录为已验证或已修复。
- 未作为通过证据：日历忙闲 Activity UI 单测在 API 36 模拟器上稳定触发 instrumentation
  process crash，`build/verification/calendar-permission-ui/device-verification.properties`
  记录 `failedTarget=instrumentation`、`reason=instrumentation-failed`；该覆盖已下沉到
  JVM contract 测试。
- 未作为通过证据：横屏远程发送确认 Activity UI 用例在 API 36 模拟器上不稳定，
  `build/verification/adaptive-ui-stable/device-verification.properties` 记录
  `failedTarget=instrumentation`、`reason=instrumentation-failed`；本轮只保留稳定的大字体
  和可访问标签 smoke。

## 2026-06-07 Remote send disclosure gate

本轮覆盖项：

- 远程模型模式下，用户主动发送文本或远程视觉图片前必须先展示发送预览；确认前不调用
  `RemoteChatRuntime`，也不会对 mock OpenAI-compatible server 发出 POST。
- 预览显示远程 host、模型名、可远程发送历史数量、图片数量、LocalOnly 历史过滤数量、
  本地记忆/设备上下文/非图片附件保护说明和 API Key 配置状态；不展示 API Key 原文。
- 远程图片分享确认后保留 `ChatImageAttachment`，直接交给支持视觉的远程模型；不做强制
  OCR 兜底。
- public evidence 工具结果续写也复用同一个远程发送预览；确认前不会进行第二次远程模型
  请求，取消会把工具结果续写标记为未发送。
- 远程模式下的动作/权限 UI 测试已适配新的远程发送确认层，继续验证本地工具确认页。

验证命令：

```bash
./gradlew --no-daemon :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteSendDisclosureBlocksRuntimeUntilConfirmed' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteImageDisclosureKeepsAttachmentForConfirmedVisionSend' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remotePublicEvidenceToolCallBatchExecutesAndContinuesWithModel' \
  --tests 'com.bytedance.zgx.pocketmind.ui.PocketMindScreenDisplayTest.remoteSendDisclosureRowsNameDestinationAndProtectedData'
scripts/verify_local.sh
ARTIFACT_DIR=build/verification/remote-send-disclosure-emulator-rerun \
  AVD_NAME=pocketmind_api36_arm64 \
  EMULATOR_SELECT_TIMEOUT_SECONDS=120 \
  BOOT_TIMEOUT_SECONDS=360 \
  scripts/regression_emulator.sh
```

结果：

- 通过：目标 JVM 测试覆盖文本发送确认、图片附件确认、public evidence 工具结果续写确认和
  远程发送预览文案。
- 通过：`scripts/verify_local.sh`，覆盖 validation script self-tests、JVM tests、lint、
  debug/androidTest APK assembly、release APK/AAB assembly 和 Android artifact scan。
- 通过：API 36 arm64 emulator regression，
  `build/verification/remote-send-disclosure-emulator-rerun/regression-emulator.properties`
  包含 `status=passed`、`actual_android_test_count=30`、`avd=pocketmind_api36_arm64`、
  `abi=arm64-v8a`。
- 首次尝试 `build/verification/remote-send-disclosure-emulator/regression-emulator.properties`
  未进入测试，`failedTarget=emulator-verification`，原因是脚本等待单一授权 emulator 时
  当前 emulator 掉线；已用显式 `AVD_NAME=pocketmind_api36_arm64` 重跑并通过。

## 2026-06-07 Product positioning and CI emulator regression wiring

本轮覆盖项：

- README 首段收敛为“隐私优先的随身 AI 助手”：本地可用、远程多模态可选、
  设备动作必须确认执行。
- 能力矩阵新增 `productPositioning` 和 `targetUserJob`，让产品主线进入机器可读
  release contract，而不是只停留在文档描述。
- 模型管理页把“信任”页签调整为“隐私”，新增“为什么装它”和 App 内隐私说明入口；
  同步补充远程模型发送边界和本地模型下载理由。
- 动作确认页新增“数据去向”区块：外部 App/系统页面动作说明不会自动完成外部操作，
  本地读取说明结果默认 LocalOnly，提醒/后台任务说明默认留在本机。
- 麦克风入口的 accessibility/就近说明明确：使用系统语音转写，结果只进入输入框，
  不自动发送，不读取本地音频文件。
- `.github/workflows/android.yml` 新增 `emulator-regression` job，在
  `workflow_dispatch` 和 weekly `schedule` 事件中创建 API 36 arm64 AVD 并运行
  `scripts/regression_emulator.sh`。
- CI job 上传 `android-emulator-regression-evidence`，包含
  `regression-emulator.properties`、嵌套 emulator/device report、instrumentation
  output、截图/UI/logcat artifact 和 emulator log。
- `docs/release_checklist.md` 明确 RC 必须绑定通过的 CI emulator regression job，
  或记录 CI 不可用原因并链接等价本地 `scripts/regression_emulator.sh` 报告。

验证命令：

```bash
git diff --check
ruby -e 'require "yaml"; YAML.load_file(".github/workflows/android.yml"); puts "workflow yaml parsed"'
bash -n scripts/regression_emulator.sh scripts/verify_emulator.sh scripts/install_and_test_device.sh
./gradlew --no-daemon :app:testDebugUnitTest --tests 'com.bytedance.zgx.pocketmind.ui.PocketMindScreenDisplayTest'
scripts/verify_local.sh
```

结果：

- 通过：本地 whitespace、workflow YAML parse、相关 shell syntax 检查。
- 通过：`PocketMindScreenDisplayTest` 覆盖产品定位、App 内隐私说明入口、远程发送边界、
  本地模型下载理由、动作确认数据去向、麦克风就近说明和信任/删除控制文案。
- 通过：`scripts/verify_local.sh`，覆盖 validation script self-tests、JVM tests、lint、
  debug/androidTest APK assembly、release APK/AAB assembly 和 Android artifact scan。
- 未执行 GitHub Actions macOS runner：本地只能验证 workflow 配置和脚本入口；实际
  `emulator-regression` job 需要在 GitHub Actions 上通过后，才能作为 CI connected
  Android test evidence。

## 2026-06-07 Core emulator regression refresh

本轮覆盖项：

- 重新验证全新安装后默认进入主界面，不再停留在“准备基础能力包”的接入页。
- 重新跑 API 36 arm64 emulator 完整 connected Android regression，覆盖远程配置、
  会话、记忆、受确认保护的设备动作、分享入口、权限提示、模型管理和 Room migration。

验证命令：

```bash
AVD_NAME=pocketmind_api36_arm64 ARTIFACT_DIR=build/verification/core-fresh-start-current scripts/verify_fresh_start_main_shell_emulator.sh
AVD_NAME=pocketmind_api36_arm64 EMULATOR_SELECT_TIMEOUT_SECONDS=120 BOOT_TIMEOUT_SECONDS=360 ARTIFACT_DIR=build/verification/core-emulator-second-current scripts/regression_emulator.sh
```

结果：

- 通过：fresh-start 证据为
  `build/verification/core-fresh-start-current/fresh-start-main-shell.properties`，
  `first_run_setup_visible=false`、`main_shell_copy_visible=true`。
- 通过：完整 emulator regression 证据为
  `build/verification/core-emulator-second-current/regression-emulator.properties`，
  `status=passed`、`actual_android_test_count=28`、`api_level=36`、`abi=arm64-v8a`。
- 备注：首轮完整回归出现一次 instrumentation process crash；随后单独复现综合主链路通过，
  清空 adb/emulator 状态后完整回归通过。当前有效通过证据以上述 second-current artifact 为准。

## 2026-06-07 Fresh start default-page clarity and APK artifact cleanup

本轮覆盖项：

- 将无模型首屏从“PocketMind 已进入，模型待配置”调整为更明确的“主界面已就绪”，并把
  首页次级入口“更多”改为“模型管理”，降低看起来仍停在默认接入页的误解。
- `scripts/verify_fresh_start_main_shell_emulator.sh` 的 main-shell 断言同步到新首屏文案，
  并为 `adb install` 增加最多 3 次重试；安装失败时写入明确
  `failedTarget=install` / `reason=debug-apk-install-failed`。
- `scripts/test_validation_scripts.sh` 现在会在 fake Gradle 测试前备份真实 debug APK，
  退出时恢复或清理，避免把 `app/build/outputs/apk/debug/app-debug.apk` 留成 15B 假包。

验证命令：

```bash
./gradlew --no-daemon :app:assembleDebug --rerun-tasks
scripts/test_validation_scripts.sh
ARTIFACT_DIR=build/verification/current-page-check-final-retry AVD_NAME=pocketmind_api36_arm64 scripts/verify_fresh_start_main_shell_emulator.sh
scripts/verify_local.sh
git diff --check
rg -n "<provider endpoint/model/API-key denylist>" -S . -g '!**/.git/**' -g '!**/.gradle/**' -g '!**/build/**'
```

结果：

- 通过：debug APK 强制重建后为 109 MB zip，SHA-256 为
  `080baa96372de8cb3ef37899acdd41bc4a54171615982c666902eaacd61d808c`。
- 通过：`scripts/test_validation_scripts.sh` 结束后 debug APK 仍为同一真实 zip 包，没有再被
  fake Gradle 留成 15B 文本文件。
- 通过：API 36 emulator fresh start 验证，
  `build/verification/current-page-check-final-retry/fresh-start-main-shell.properties` 记录
  `status=passed`、`first_run_setup_visible=false`、`main_shell_copy_visible=true`；截图为
  `build/verification/current-page-check-final-retry/fresh-start.png`。
- 通过：`scripts/verify_local.sh`，包含 validation script self-tests、JVM tests、lint、
  debug/androidTest APK assembly、release APK/AAB assembly 和 Android artifact scan。
- 通过：`git diff --check` 无 whitespace 问题；敏感配置扫描未发现 DeepSeek endpoint、
  model name 或 API key。
- 未执行真机安装：当前 `adb devices -l` 未发现授权物理设备。

## 2026-06-07 Formal release flow evidence recorder

本轮覆盖项：

- 新增 `scripts/record_release_flow_evidence.sh`，用于人工/真机验收后生成正式
  `target=release-flow`、`releaseFlowPassed=true`、`candidateOnly=false` 的 flow
  evidence 文件。
- recorder 要求显式 `OWNER`，支持 `RELEASE_FLOW_KEYS` partial 记录和
  `RELEASE_FLOW_ALL=1` 全量记录；partial summary 必须失败并列出 `pendingFlows`。
- 不自动修改 `docs/release_validation_record.json`，也不把 candidate evidence 升级为
  passed flow；release validation 仍等待真实 owner、date、evidencePath 和 SHA 绑定。

验证命令：

```bash
bash -n scripts/record_release_flow_evidence.sh scripts/test_validation_scripts.sh scripts/verify_local.sh
scripts/test_validation_scripts.sh
```

结果：

- 通过：validation script self-tests 覆盖 release flow recorder 缺少 owner 失败、
  partial flow 失败、全量 formal evidence 通过，以及每个 flow evidence 的
  `status`、`target`、`flowKey`、`releaseFlowPassed`、`candidateOnly`、owner/date
  字段。
- 通过：`scripts/verify_local.sh` 已在本轮后续总验证中通过，覆盖 shell syntax、
  validation script self-tests、JVM tests、lint、debug/androidTest、release APK/AAB
  和 artifact scan。
- 预期仍未完成：`docs/release_validation_record.json.flowMatrix` 保持 pending，等待真实
  release flow owner sign-off 和 evidence binding。

## 2026-06-07 Store policy evidence packet refresh

本轮覆盖项：

- 同步 `docs/store_policy_record.json.privacyNoticeSha256` 到当前
  `docs/privacy_notice.md`。
- 新增 store policy pending review evidence packet，作为 Play listing / Data safety /
  permissions / special-access reviewer 输入；明确标记 `status=pending`、
  `approvalStatus=not-approved`。
- 不修改 `status`、contact email、privacy policy URL、reviewer 或 review date；Store
  policy gate 仍保持 fail-closed。

验证命令：

```bash
shasum -a 256 docs/privacy_notice.md docs/store_policy_review_evidence/pending.properties
scripts/verify_store_policy_record.sh --report build/verification/store-policy-current.properties
```

结果：

- 通过：当前 privacy notice SHA 为
  `027ffabe4d4be62c3ce14434c8bdcee9d106620222af37d8d937fcbbddf65385`，已写入
  `docs/store_policy_record.json`。
- 通过：store policy pending evidence packet SHA-256 为
  `b811e582501e1d7f18c3644210898e07bf501ff5945fea0e857ec032ca9066ce`，已绑定到
  `docs/store_policy_record.json.review`。
- 预期失败：`scripts/verify_store_policy_record.sh` 仍记录 `status=failed`；当前失败原因
  只剩 pending approval、占位 contact email、占位 privacy policy URL、reviewer 和
  review date。

## 2026-06-07 Privacy review evidence packet refresh

本轮覆盖项：

- 同步 `docs/privacy_review.json.noticeSha256` 到当前 `docs/privacy_notice.md`。
- 新增 release/security/legal 三份 pending privacy review evidence packet，作为 reviewer
  审查输入；它们明确标记 `status=pending`、`approvalStatus=not-approved`。
- 不修改三方 `decision`、reviewer 或 review date；public privacy review 仍保持
  fail-closed。

验证命令：

```bash
shasum -a 256 docs/privacy_notice.md docs/privacy_review_evidence/*.properties
scripts/verify_privacy_review.sh --report build/verification/privacy-review-current.properties
```

结果：

- 通过：当前 privacy notice SHA 为
  `027ffabe4d4be62c3ce14434c8bdcee9d106620222af37d8d937fcbbddf65385`，已写入
  `docs/privacy_review.json`。
- 通过：三份 pending evidence packet 的 SHA-256 已绑定到 review 文件：
  release `9915a7262b5ffcdddd5539e3143a0d689ea0857d85aab6b1ac1a4ca985b3e8b8`，
  security `9a2f6c6348e6063937d0e20ae865f4f7b28fdf8215de87edcb7cca44d6fca3ca`，
  legal `75bbb042f32b460eeacd2bfcf6348a4cb4e33cb7f69741dcbb808b8a417bc51f`。
- 预期失败：`scripts/verify_privacy_review.sh` 仍记录 `status=failed`；当前失败原因
  只剩 release/security/legal 三方 decision、reviewer 和 review date。

## 2026-06-07 Model license source metadata refresh

本轮覆盖项：

- 重新运行 `scripts/collect_model_license_metadata.sh`，从 Hugging Face model API 刷新四个
  推荐模型的 license metadata。
- 在 `docs/model_license_review.json` 中补齐可由 metadata 支撑的 license name 和 pinned
  README license source URL；不修改 `status`、`redistributionDecision`、reviewer、
  review date 或 review evidence。
- 保持 fail-closed：模型 license review 仍必须由人工 reviewer 完成 redistribution、
  attribution/notice 和批准证据。

验证命令：

```bash
scripts/collect_model_license_metadata.sh
scripts/verify_model_license_review.sh --report build/verification/model-license-review-current.properties
```

结果：

- 通过：metadata 刷新完成，`docs/model_license_metadata.json.recordedAt` 更新到
  `2026-06-06T18:34:06Z`。
- 通过：`docs/model_license_review.json` 现在记录 `chat-e2b`、`memory-embedding-300m`
  和 `chat-e4b` 的 `Apache-2.0` license name，以及 `mobile-action-270m` 的 `Gemma`
  license name；四个 `licenseUrl` 均指向 pinned upstream revision 的 README。
- 预期失败：`scripts/verify_model_license_review.sh` 仍记录 `status=failed`；当前失败原因
  只剩每个模型的人工 approval、redistribution approval、attribution notice、reviewer、
  review evidence 和 review date。

## 2026-06-07 Upgrade install and flow candidate refresh

本轮覆盖项：

- 刷新当前 HEAD 的 API 36 emulator upgrade install smoke：从上一个 app 源码版本构建
  base debug APK，再覆盖安装当前 debug APK，并运行 `MainActivitySmokeTest`。
- 刷新 release flow matrix candidate evidence；继续证明 candidate evidence 不能替代正式
  `target=release-flow` / `releaseFlowPassed=true` 的 release evidence。
- 不修改 `docs/release_validation_record.json`：`flowMatrix.upgradeInstall` 和其他 flow
  项仍保持 pending。

验证命令：

```bash
ARTIFACT_DIR=build/verification/release-flow-matrix-current REPORT_FILE=build/verification/release-flow-matrix-current/release-flow-matrix-candidate-evidence.properties scripts/collect_release_flow_matrix_evidence.sh
ANDROID_HOME="$HOME/Library/Android/sdk" ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" AVD_NAME=pocketmind_api36_arm64 ARTIFACT_DIR=build/verification/upgrade-install-emulator-current REPORT_FILE=build/verification/upgrade-install-emulator-current/upgrade-install-emulator.properties EMULATOR_ARGS='-wipe-data -no-window -no-audio -no-boot-anim -no-snapshot-save -gpu swiftshader_indirect' EMULATOR_SELECT_TIMEOUT_SECONDS=120 BOOT_TIMEOUT_SECONDS=300 scripts/verify_upgrade_install_emulator.sh
scripts/verify_release_validation_record.sh --report build/verification/release-validation-current.properties
```

结果：

- 通过：API 36 upgrade install smoke，
  `build/verification/upgrade-install-emulator-current/upgrade-install-emulator.properties`
  记录 `status=passed`、`target=upgrade-install-emulator`、`baseRef=0be4bde...`、
  `currentCommit=61f3be9...`、`signerSha256Matches=true`、`instrumentation=passed`、
  `instrumentation_test_count=4`，SHA-256 为
  `7b380a00f564ed72227dc4ce1e718eb5201afe9ecd997dfd608e8dc3a96bef7a`。
- 通过：upgrade package evidence 显示 `firstInstallTime` 保持
  `2026-06-07 02:30:25`，`lastUpdateTime` 更新到 `2026-06-07 02:30:36`；
  debug base/current `versionCode` 均为 1，因此 `versionCodeIncreased=false`。
- 预期失败：release flow candidate collector 生成 8 个 candidate evidence 文件，但总报告仍
  `status=failed` / `failedTarget=flow-matrix`，SHA-256 为
  `d5b922bc708b0635000db60b028aece633dee29dc5317d3eacd799fc60302f6f`。
- 预期失败：release validation record 仍未 approved，真实剩余项继续是 physical device、
  API 28/32/33/34、manual acceptance、正式 flow evidence、performance 和 reviewer/date。

## 2026-06-07 Manual review install and setup page loop fix

本轮覆盖项：

- 首屏不再把“准备基础能力包”作为全新安装默认页；没有本地模型和远程配置时仍进入
  PocketMind 主界面，并通过主界面卡片提示去下载、导入或配置远程模型。
- 新增 `scripts/install_review_device.sh` 作为人工验收安装入口；它不运行
  instrumentation、不默认清 App 数据，报告标记为 `target=manual-acceptance-install`
  和 `regressionEvidence=false`。
- `scripts/install_and_test_device.sh` 的自动回归洁净行为保持不变，仍默认
  `RESET_APP_DATA_AFTER_TESTS=1`。

验证命令：

```bash
scripts/test_validation_scripts.sh
./gradlew --no-daemon -Pkotlin.incremental=false :app:testDebugUnitTest --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.startupDoesNotShowSetupOnFreshInstallWhenNoLocalOrRemoteModelIsAvailable' --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.startupKeepsSetupDismissedWhenNoLocalOrRemoteModelIsAvailable' --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.startupDoesNotReopenSetupWhenRemoteModelIsConfigured'
ARTIFACT_DIR=build/verification/fresh-start-main-shell-current AVD_NAME=pocketmind_api36_arm64 scripts/verify_fresh_start_main_shell_emulator.sh
scripts/verify_local.sh
```

结果：

- 通过：`scripts/test_validation_scripts.sh`，确认人工验收安装 report 不清 App 数据、
  不运行 instrumentation、不落远程 API key，且不能被 release validation 误当作
  physical regression evidence。
- 通过：targeted ViewModel JVM tests，确认 fresh install 和已跳过状态都不再显示准备区；
  远程模型已配置时仍直接进入 ready 状态。
- 通过：API 36 clean install 手工启动验证，
  `build/verification/fresh-start-main-shell-current/fresh-start-main-shell.properties`
  记录 `status=passed`、`target=fresh-start-main-shell`、
  `first_run_setup_visible=false`、`main_shell_copy_visible=true`；截图为
  `build/verification/fresh-start-main-shell-current/fresh-start.png`，UI dump 为
  `build/verification/fresh-start-main-shell-current/fresh-start.xml`。SHA-256：
  report `84c908da204a5707804951dc5c2d9b39c00140eb97a0749ce4683e6e224cc7e1`，
  screenshot `8ac50c443b862d9fb07e52cbfe0b2fe7d700277ec6d75833942fdb3cd9e4085a`，
  UI dump `f3532b97d99cc51cf08967c8db1063ef431b8e5ac38c1a9183df925fb59336e1`。
- 通过：`scripts/verify_local.sh`，包含 validation script self-tests、JVM tests、lint、
  debug/androidTest APK assembly、release APK/AAB assembly 和 Android artifact scan。

## 2026-06-07 API 36 default emulator startup validation

本轮覆盖项：

- `scripts/verify_emulator.sh` 指定 `AVD_NAME` 且未显式传入 `EMULATOR_ARGS` 时，默认
  使用 headless、wipe-data、no-snapshot 的确定性启动参数。
- fresh API 36 AVD 上的首装准备区 smoke helper 滚动到按钮后再断言和跳过。
- `docs/release_validation_record.json` 的 emulator regression 和 API 36 matrix evidence
  绑定到当前默认启动参数路径下的新完整回归 artifact。

验证命令：

```bash
scripts/test_validation_scripts.sh
AVD_NAME=pocketmind_api36_arm64 EMULATOR_SELECT_TIMEOUT_SECONDS=120 BOOT_TIMEOUT_SECONDS=360 INSTRUMENTATION_CLASS=com.bytedance.zgx.pocketmind.MainActivitySmokeTest ARTIFACT_DIR=build/verification/emulator-api36-default-args-smoke-current scripts/verify_emulator.sh
scripts/verify_local.sh
AVD_NAME=pocketmind_api36_arm64 EMULATOR_SELECT_TIMEOUT_SECONDS=120 BOOT_TIMEOUT_SECONDS=360 ARTIFACT_DIR=build/verification/regression-emulator-api36-default-args-current scripts/regression_emulator.sh
scripts/verify_release_validation_record.sh --report build/verification/release-validation-current.properties
```

结果：

- 通过：`scripts/test_validation_scripts.sh`，覆盖默认 emulator args 和显式
  `EMULATOR_ARGS` 覆盖路径。
- 通过：API 36 smoke，`build/verification/emulator-api36-default-args-smoke-current`
  记录 `MainActivitySmokeTest` 4 条全部通过。
- 通过：`scripts/verify_local.sh`，包含 JVM tests、lint、debug/androidTest APK assembly、
  release APK/AAB assembly 和 Android artifact scan。
- 通过：完整 API 36 regression，
  `build/verification/regression-emulator-api36-default-args-current/regression-emulator.properties`
  记录 `status=passed`、`actual_android_test_count=28`、`api_level=36`、
  `abi=arm64-v8a`、`avd=pocketmind_api36_arm64`，SHA-256 为
  `d2fc72b000b39ad20e77741ba43b1abeb2646fca580108ebeafcf582c215ee4f`。
- 预期失败：release validation record 仍未 approved，当前失败原因继续是未完成的
  physical device full evidence、API 28/32/33/34 matrix、manual acceptance、flow
  matrix、performance sanity 和 reviewer/date。API 36 evidence 已绑定到本轮通过的
  完整回归报告。

## 2026-06-06 Physical install and live remote device validation

本轮覆盖项：

- 在真实设备 `fb6272c` 上重新安装当前 debug APK 和 androidTest APK，并完整跑
  `MainActivitySmokeTest` 4 条发布 smoke。
- `scripts/live_remote_emulator.sh` 保持默认只选 emulator；新增显式
  `POCKETMIND_LIVE_REMOTE_TARGET=device` 后才允许选择真机 serial。
- live remote 输入坐标改为读取设备屏幕尺寸后按比例计算，覆盖 1200x2670 真机；
  成功路径也保存 screenshot、UI dump 和 logcat evidence。
- 使用用户提供的远程模型配置做真机 live remote 验证；API key 只通过静默 stdin/env
  注入，报告和 artifact 不记录实际密钥。

验证命令：

```bash
ANDROID_SERIAL=fb6272c CLEAN_DEVICE=0 INSTRUMENTATION_CLASS=com.bytedance.zgx.pocketmind.MainActivitySmokeTest INSTRUMENTATION_TIMEOUT_SECONDS=600 ARTIFACT_DIR=build/verification/physical-device-install-retry-wide-timeout VERIFICATION_REPORT_FILE=build/verification/physical-device-install-retry-wide-timeout/device-verification.properties INSTRUMENTATION_OUTPUT_FILE=build/verification/physical-device-install-retry-wide-timeout/instrumentation.txt scripts/install_and_test_device.sh
bash -n scripts/live_remote_emulator.sh scripts/test_validation_scripts.sh
scripts/test_validation_scripts.sh
POCKETMIND_LIVE_REMOTE_TARGET=device ANDROID_SERIAL=fb6272c POCKETMIND_LIVE_REMOTE_BASE_URL=<redacted> POCKETMIND_LIVE_REMOTE_MODEL=<redacted> POCKETMIND_LIVE_REMOTE_API_KEY=<provided-secret> POCKETMIND_LIVE_REMOTE_PROMPT="Return POCKETMIND_LIVE_OK" POCKETMIND_LIVE_REMOTE_EXPECTED_TEXT=POCKETMIND_LIVE_OK POCKETMIND_LIVE_REMOTE_WAIT_SECONDS=60 ARTIFACT_DIR=build/verification/live-remote-device-deepseek-final REPORT_FILE=build/verification/live-remote-device-deepseek-final/live-remote-device.properties scripts/live_remote_emulator.sh
```

结果：

- 通过：真机 `fb6272c` 安装成功，`MainActivitySmokeTest` 4 条通过，
  `build/verification/physical-device-install-retry-wide-timeout/device-verification.properties`
  记录 `status=passed`、`target=device`、`api_level=36`、`abi=arm64-v8a`、
  `instrumentation=passed`、`instrumentation_test_count=4`，SHA-256
  `bb50a6deb34bede4681deb9e93437546fd197a239c0b7bf59089e8911b1a18b7`。
- 通过：live remote 真机验证返回 `POCKETMIND_LIVE_OK`，报告
  `build/verification/live-remote-device-deepseek-final/live-remote-device.properties`
  记录 `status=passed`、`target=live-remote-device`、`device_target=device`、
  `serial=fb6272c`，并链接 screenshot、UI dump 和 logcat，SHA-256
  `29845bd67c9b3e1eef3134cf1840944e6cbad4750af45d7faa6e7c1434ac20cc`。
- 通过：live remote 报告只记录 `base_url=<redacted>`、`model=<redacted>` 和
  `api_key_source=POCKETMIND_LIVE_REMOTE_API_KEY`；实际 API key 未落盘。

## 2026-06-06 Physical smoke scope narrowing

本轮覆盖项：

- `MainActivitySmokeTest.backgroundTaskManagerShowsEmptyState` 收窄为发布 smoke
  适合的入口检查：只验证后台任务 sheet 打开、标题、刷新按钮和周期检查策略首屏可见。
- 完整审计日志和 Agent 轨迹内容继续由 `MainActivitySkillUiTest` 的专项用例覆盖；
  不再让真机 smoke 在 bottom sheet 内滚动完整内容，避免发布门禁被非核心滚动路径卡住。

验证命令：

```bash
./gradlew :app:compileDebugAndroidTestKotlin
git diff --check
ANDROID_SERIAL=fb6272c CLEAN_DEVICE=0 INSTRUMENTATION_CLASS=com.bytedance.zgx.pocketmind.MainActivitySmokeTest INSTRUMENTATION_TIMEOUT_SECONDS=180 ARTIFACT_DIR=build/verification/physical-device-smoke-after-narrowing scripts/install_and_test_device.sh
```

结果：

- 通过：`:app:compileDebugAndroidTestKotlin` 和 `git diff --check`。
- 未完成真机重跑：设备 `fb6272c` 仍拒绝 ADB 安装，
  `build/verification/physical-device-smoke-after-narrowing/device-verification.properties`
  记录 `failedTarget=install`、`reason=install-user-restricted`，SHA-256
  `f124cd3836321ab7934ab0f222ace3f102eb4d9eb1bd9fff1b5081d889939009`。
  需要在手机上允许 USB 安装后继续重跑真机 smoke 和远程模型验证。

## 2026-06-06 Physical device validation fail-closed hardening

本轮覆盖项：

- `scripts/install_and_test_device.sh` 新增 `INSTRUMENTATION_CLASS`，支持在真机上按
  class 或 method 分组跑 instrumentation，避免发布 smoke 被长链路综合测试拖死。
- `scripts/install_and_test_device.sh` 新增 `INSTRUMENTATION_TIMEOUT_SECONDS`，并在
  timeout 后强制停止 target/test package，写入 `failedTarget=instrumentation`、
  `reason=instrumentation-timeout`。
- EXIT trap 改为 fail-closed：脚本中断、timeout、未完整结束或无最终成功 marker 时，
  不允许写成 `status=passed`。
- `scripts/test_validation_scripts.sh` 新增 class filter、timeout、force-stop 和
  report path 固定化的回归覆盖。

验证命令：

```bash
bash -n scripts/install_and_test_device.sh scripts/test_validation_scripts.sh
git diff --check
scripts/test_validation_scripts.sh
ANDROID_SERIAL=fb6272c CLEAN_DEVICE=1 INSTRUMENTATION_CLASS=com.bytedance.zgx.pocketmind.MainActivitySmokeTest INSTRUMENTATION_TIMEOUT_SECONDS=180 ARTIFACT_DIR=build/verification/physical-device-smoke scripts/install_and_test_device.sh
ANDROID_SERIAL=fb6272c CLEAN_DEVICE=1 INSTRUMENTATION_CLASS=com.bytedance.zgx.pocketmind.MainActivitySmokeTest#chatShellShowsModelManager INSTRUMENTATION_TIMEOUT_SECONDS=120 ARTIFACT_DIR=build/verification/physical-device-smoke-chat scripts/install_and_test_device.sh
ANDROID_SERIAL=fb6272c CLEAN_DEVICE=1 INSTRUMENTATION_CLASS=com.bytedance.zgx.pocketmind.MainActivitySmokeTest#sessionManagerShowsSessionControls INSTRUMENTATION_TIMEOUT_SECONDS=120 ARTIFACT_DIR=build/verification/physical-device-smoke-session scripts/install_and_test_device.sh
```

结果：

- 通过：shell 语法检查、`git diff --check`、validation script self-tests。
- 通过：真机 `fb6272c` 上
  `MainActivitySmokeTest#chatShellShowsModelManager` 通过，artifact：
  `build/verification/physical-device-smoke-chat/device-verification.properties`，
  SHA-256 `7d773dead695352ee5440806b2fd459235ccde687e4f9120b3590e8761f0b6fc`。
- 失败并正确 fail-closed：真机全 `MainActivitySmokeTest` 卡在
  `backgroundTaskManagerShowsEmptyState`，180s 后生成
  `build/verification/physical-device-smoke/device-verification.properties`，
  `failedTarget=instrumentation`、`reason=instrumentation-timeout`，
  SHA-256 `956785fed9818e3f8e84569dc0091b597a855aca7949384704b8042ff59c4ad9`。
- 失败并正确 fail-closed：重跑 session manager 单测时设备拒绝 ADB 安装，
  `build/verification/physical-device-smoke-session/device-verification.properties`
  记录 `failedTarget=install`、`reason=install-user-restricted`，
  SHA-256 `51cd84b15c4ddfa38f6741533a9159f5d37a4f81d058fa99308172a1cd81d01d`。
- 远程模型真机验证未执行：当前主包已被 clean uninstall，设备只剩
  `com.bytedance.zgx.pocketmind.test`；需要用户在手机上允许 USB 安装后，才能通过
  debug receiver 临时注入远程配置。远程 API key 不写入 repo、报告或 commit。

## 2026-06-06 Release validation API nested evidence hardening

本轮覆盖项：

- `scripts/verify_release_validation_record.sh` 现在要求每个 API matrix evidence
  report 具备真实 `regression-emulator.properties` 的关键字段：`exit_code=0`、
  空 `failedTarget`/`reason`、start/finish 时间、source/expected/actual test count、
  emulator serial、API level、ABI、AVD 和 instrumentation output。
- instrumentation output 必须可读，最终 `OK (N tests)` 的 N 必须和
  `actual_android_test_count` 一致。
- per-API regression report 必须链接 nested `emulator-verification.properties` 和
  `device-verification.properties`；nested reports 的 serial/API/ABI/AVD、
  device report path、instrumentation output 和 test count 必须和 parent report 匹配。
- `scripts/test_validation_scripts.sh` 的 approved validation fixture 改为每个 API
  生成 parent regression report、nested emulator report、nested device report 和
  instrumentation output。
- 新增负例：形式上接近真实 regression report、但缺 nested reports 和
  instrumentation output 的 API evidence 必须失败。
- `docs/release_checklist.md` 同步该门禁口径。

验证命令：

```bash
bash -n scripts/verify_release_validation_record.sh scripts/test_validation_scripts.sh
git diff --check
scripts/test_validation_scripts.sh
scripts/verify_release_validation_record.sh --report build/verification/release-validation-current.properties
ANDROID_HOME="$HOME/Library/Android/sdk" scripts/verify_local.sh
```

结果：

- 通过：shell 语法检查、`git diff --check`、validation script self-tests 和
  `scripts/verify_local.sh`。
- 预期失败：当前 release validation record 仍未 approved；失败原因继续保留真实未完成项：
  真机、API 28/32/33/34、manual acceptance、flow matrix、performance sanity 和
  reviewer/date。现有 API 36 nested evidence 满足本轮新门禁。
- 未执行模拟器：本轮只加固 release validation 门禁脚本、测试 fixture 和文档；
  没有重新跑 API matrix。

## 2026-06-06 Release validation performance semantics hardening

本轮覆盖项：

- `scripts/verify_release_validation_record.sh` 现在要求每个 performance evidence
  verifier report 包含有效 `expectedArtifactSha256` 和非空 `expectedAppVersion`。
- release validation 会读取 linked `baselineFile`，并确认 baseline 的
  `baselineSha256`、`releaseArtifactSha256` / `appVersion` 与 verifier report 的
  expected 字段匹配。
- baseline 必须来自非 emulator 设备、`abi=arm64-v8a`、`oomOrAnrObserved=false`，
  且 `recordedAt` 必须是 UTC、非未来、未超过 `maxRecordAgeDays`。
- `scripts/verify_perf_baseline.sh` 现在会在 verifier report 中写入
  `baselineSha256`；`scripts/collect_perf_baseline.sh` 调用 verifier 时会传入
  `--app-version "$APP_VERSION"`。
- `scripts/test_validation_scripts.sh` 的 approved validation fixture 改为绑定
  baseline SHA、artifact SHA 和 app version，并新增 baseline file SHA mismatch
  与 baseline artifact SHA mismatch 负例。
- `docs/release_checklist.md` 同步该门禁口径。

验证命令：

```bash
bash -n scripts/verify_release_validation_record.sh scripts/test_validation_scripts.sh
git diff --check
scripts/test_validation_scripts.sh
scripts/verify_release_validation_record.sh --report build/verification/release-validation-current.properties
ANDROID_HOME="$HOME/Library/Android/sdk" scripts/verify_local.sh
```

结果：

- 通过：shell 语法检查、`git diff --check`、validation script self-tests 和
  `scripts/verify_local.sh`。
- 预期失败：当前 release validation record 仍未 approved；失败原因继续保留真实未完成项：
  真机、API 28/32/33/34、manual acceptance、flow matrix、performance sanity 和
  reviewer/date。
- 未执行真机：本轮只加固 release validation 门禁脚本、测试 fixture 和文档；
  没有采集新的 perf baseline。

## 2026-06-06 Release validation physical device report hardening

本轮覆盖项：

- `scripts/verify_release_validation_record.sh` 现在要求 physical device report
  具备 `exit_code=0`、空 `failedTarget`/`reason`、非空 start/finish 时间字段、
  `data_free_kb` 不低于 3GB、`instrumentation=passed` 和足够的
  `instrumentation_test_count`。
- physical device report 必须链接可读的 `instrumentation_output_file`；该文件不得为空、
  不得包含 instrumentation failure marker，并且必须包含最终 `OK` 成功标记。
- `OK` 中的测试数必须和 `instrumentation_test_count` 一致；physical ABI report
  必须包含 `arm64-v8a` 和 validation record 中声明的 ABI。
- `debug_apk` / `android_test_apk` 必须匹配项目认可的 debug APK 路径。
- `scripts/test_validation_scripts.sh` 的 approved validation fixture 改为更接近
  `scripts/install_and_test_device.sh` 的 report 形态，并新增弱 physical report 和
  instrumentation output count mismatch 负例。
- `docs/release_checklist.md` 同步该门禁口径。

验证命令：

```bash
bash -n scripts/verify_release_validation_record.sh scripts/test_validation_scripts.sh
git diff --check
scripts/test_validation_scripts.sh
scripts/verify_release_validation_record.sh --report build/verification/release-validation-current.properties
ANDROID_HOME="$HOME/Library/Android/sdk" scripts/verify_local.sh
```

结果：

- 通过：shell 语法检查、`git diff --check`、validation script self-tests 和
  `scripts/verify_local.sh`。
- 预期失败：当前 release validation record 仍未 approved；失败原因继续保留真实未完成项：
  真机、API 28/32/33/34、manual acceptance、flow matrix、performance sanity 和
  reviewer/date。
- 未执行真机：本轮只加固 release validation 门禁脚本、测试 fixture 和文档；
  仍按当前约束不连接真机。

## 2026-06-06 Release validation screenshot report binding

本轮覆盖项：

- `scripts/verify_release_validation_record.sh` 现在会校验每张 screenshot 文件的
  PNG signature，而不只检查文件存在和 SHA。
- 每个 screenshot entry 必须链接 `release-screenshots.properties`，并通过
  `reportSha256` 绑定报告文件。
- screenshot report 必须是 `status=passed`、`target=release-screenshots`、
  `clean_device=1`，并且报告里的 `screenshot.<name>.path`、`sha256`、
  `sanitized` 必须和 JSON entry 匹配。
- `scripts/test_validation_scripts.sh` 的 approved validation fixture 改为真实
  PNG 签名和正式 screenshot report，并新增“文本冒充 PNG”负例。
- `docs/release_validation_record.json` 的四张现有截图都绑定到当前
  `build/verification/release-screenshots-current/release-screenshots.properties`。
- `docs/release_checklist.md` 同步该门禁口径。

验证命令：

```bash
bash -n scripts/verify_release_validation_record.sh scripts/test_validation_scripts.sh
git diff --check
scripts/test_validation_scripts.sh
scripts/verify_release_validation_record.sh --report build/verification/release-validation-current.properties
ANDROID_HOME="$HOME/Library/Android/sdk" scripts/verify_local.sh
```

结果：

- 通过：shell 语法检查、`git diff --check`、validation script self-tests 和
  `scripts/verify_local.sh`。
- 预期失败：当前 release validation record 仍未 approved；失败原因继续保留真实未完成项：
  真机、API 28/32/33/34、manual acceptance、flow matrix、performance sanity 和
  reviewer/date。没有截图 report 或 PNG 相关失败。
- 未执行模拟器：本轮只加固 release validation 门禁脚本、测试 fixture 和文档；
  没有重新采集截图。

## 2026-06-06 Release validation performance key binding

本轮覆盖项：

- `scripts/verify_release_validation_record.sh` 现在要求每个 `performanceSanity`
  evidence report 声明 `performanceKey`，并且必须和当前记录 key 匹配。
- `scripts/test_validation_scripts.sh` 的 approved validation fixture 为每个
  performance report 写入对应 `performanceKey`。
- 新增负例：`modelLoad` 引用 `firstLaunch` 的 perf verifier report 时必须失败，
  并记录 `performance-modelLoad-evidence-key-mismatch`。
- `docs/release_checklist.md` 同步该门禁口径。

验证命令：

```bash
bash -n scripts/verify_release_validation_record.sh scripts/test_validation_scripts.sh
git diff --check
scripts/test_validation_scripts.sh
scripts/verify_release_validation_record.sh --report build/verification/release-validation-current.properties
ANDROID_HOME="$HOME/Library/Android/sdk" scripts/verify_local.sh
```

结果：

- 通过：shell 语法检查、`git diff --check`、validation script self-tests 和
  `scripts/verify_local.sh`。
- 预期失败：当前 release validation record 仍未 approved；失败原因继续保留真实未完成项：
  真机、API 28/32/33/34、manual acceptance、flow matrix、performance sanity 和
  reviewer/date。
- 未执行模拟器：本轮只加固 release validation 门禁脚本、测试 fixture 和文档，
  不改变 APK runtime 或 UI 行为。

## 2026-06-06 Release validation flow evidence hardening

本轮覆盖项：

- `scripts/verify_release_validation_record.sh` 现在会校验 `flowMatrix`
  evidence 文件本身，而不只拒绝 candidate-only 证据。
- flow evidence 必须是正式 release flow 报告：`status=passed`、
  `target=release-flow`、`flowKey` 匹配当前记录 key，并且
  `releaseFlowPassed=true`。
- candidate-only 或 `releaseFlowPassed=false` 仍会被拒绝；只有
  `status=passed` / `flow=firstInstall` 的轻量文件也会被拒绝。
- `scripts/test_validation_scripts.sh` 的 approved validation fixture 改为正式
  release flow 报告，并新增弱证据负例。
- `docs/release_checklist.md` 同步该门禁口径。

验证命令：

```bash
bash -n scripts/verify_release_validation_record.sh scripts/test_validation_scripts.sh
git diff --check
scripts/test_validation_scripts.sh
scripts/verify_release_validation_record.sh --report build/verification/release-validation-current.properties
ANDROID_HOME="$HOME/Library/Android/sdk" scripts/verify_local.sh
```

结果：

- 通过：shell 语法检查、`git diff --check`、validation script self-tests 和
  `scripts/verify_local.sh`。
- 预期失败：当前 release validation record 仍未 approved；失败原因继续保留真实未完成项：
  真机、API 28/32/33/34、manual acceptance、flow matrix、performance sanity 和
  reviewer/date。
- 未执行模拟器：本轮只加固 release validation 门禁脚本、测试 fixture 和文档，
  不改变 APK runtime 或 UI 行为。

## 2026-06-22 Release validation formal evidence audit fields

本轮覆盖项：

- `scripts/record_manual_acceptance_evidence.sh` 产出的汇总 report 和每个正式
  manual evidence 现在包含 schema、owner、UTC `recordedAt`、`command` 和
  `reproduciblePath`。
- `scripts/record_release_flow_evidence.sh` 产出的汇总 report 和每个正式
  release-flow evidence 现在包含 schema、owner、UTC `recordedAt`、`command` 和
  `reproduciblePath`。
- `scripts/verify_release_validation_record.sh` 对 `manualAcceptance` 和
  `flowMatrix` evidence 增加 fail-closed 校验：evidence 文件必须带对应 schema，
  owner/date 必须匹配 JSON record，`recordedAt` 必须是非未来且 30 天内的 UTC
  时间，`command` 非空，`reproduciblePath` 必须等于 evidence 文件路径。
- `scripts/test_validation_scripts.sh` 新增负例：从 SHA 绑定的正式 manual/flow
  evidence 中剥掉 audit 字段后，release validation verifier 必须失败。

验证命令：

```bash
bash -n scripts/record_manual_acceptance_evidence.sh scripts/record_release_flow_evidence.sh scripts/verify_release_validation_record.sh scripts/test_validation_scripts.sh
git diff --check
scripts/test_validation_scripts.sh
```

结果：

- 通过：shell 语法检查、`git diff --check` 和 validation script self-tests。
- 未执行真机/模拟器：本轮只加固本地 release validation evidence 生成和验证。
- 未修改 release validation pending/approved 状态。

## 2026-06-22 Model license review evidence audit fields

本轮覆盖项：

- `scripts/verify_model_license_review.sh` 对 approved model review evidence 增加
  fail-closed 校验：证据文件必须声明
  `artifactSchema=ModelLicenseReviewApprovedEvidence/v1`、approved target/status、
  matching model/reviewer/license/scope/redistribution decision、owner、UTC
  `recordedAt`、非空 `command` 和等于 evidence path 的 `reproduciblePath`。
- `scripts/test_validation_scripts.sh` 的 approved model license fixture 改为完整
  audit evidence，并新增两个负例：缺少 schema 的弱证据和未来 `recordedAt`
  都必须被拒绝。
- `docs/release_checklist.md` 同步模型 license approval evidence 的最小审计字段。

验证命令：

```bash
bash -n scripts/verify_model_license_review.sh scripts/test_validation_scripts.sh
scripts/test_validation_scripts.sh
scripts/verify_model_license_review.sh --report build/verification/model-license-current.properties

ANDROID_HOME=/data00/home/zouguoxue/android-sdk \
ANDROID_SDK_ROOT=/data00/home/zouguoxue/android-sdk \
  scripts/verify_local.sh
```

结果：

- 通过：shell 语法检查、validation script self-tests 和整合后的
  `scripts/verify_local.sh`。
- 预期失败：当前 `docs/model_license_review.json` 仍未 approved，报告继续指向真实
  未完成项：模型 redistribution/attribution 决策、reviewer、review date 和
  review evidence。
- 未执行真机/模拟器：本轮只加固本地 model license review gate 和 fixture。

## 2026-06-06 Release validation manual evidence hardening

本轮覆盖项：

- `scripts/verify_release_validation_record.sh` 现在会校验 `manualAcceptance`
  evidence 文件本身，而不只校验 evidence object、文件存在和 SHA。
- manual evidence 必须是正式手工验收报告：`status=passed`、
  `target=manual-acceptance`、`manualKey` 匹配当前记录 key，并且
  `manualAcceptance=true`。
- `scripts/test_validation_scripts.sh` 的 approved validation fixture 改为正式
  manual acceptance 报告，并新增弱证据负例：只有 `status=passed` /
  `manual=modelSetup` 的文件必须被拒绝。
- `docs/release_checklist.md` 同步该门禁口径。

验证命令：

```bash
bash -n scripts/verify_release_validation_record.sh scripts/test_validation_scripts.sh
git diff --check
scripts/test_validation_scripts.sh
scripts/verify_release_validation_record.sh --report build/verification/release-validation-current.properties
ANDROID_HOME="$HOME/Library/Android/sdk" scripts/verify_local.sh
```

结果：

- 通过：shell 语法检查、`git diff --check`、validation script self-tests 和
  `scripts/verify_local.sh`。
- 预期失败：当前 release validation record 仍未 approved；失败原因继续保留真实未完成项：
  真机、API 28/32/33/34、manual acceptance、flow matrix、performance sanity 和
  reviewer/date。
- 未执行模拟器：本轮只加固 release validation 门禁脚本、测试 fixture 和文档，
  不改变 APK runtime 或 UI 行为。

## 2026-06-06 Release validation performance evidence hardening

本轮覆盖项：

- `scripts/verify_release_validation_record.sh` 现在会校验 `performanceSanity`
  evidence 文件本身，而不只校验 evidence object、文件存在和 SHA。
- performance evidence 必须是 `scripts/verify_perf_baseline.sh` 产出的通过报告：
  `status=passed`、`target=perf-baseline`、`missingFieldCount=0`，并且
  `baselineFile` 非空且可读。
- `scripts/test_validation_scripts.sh` 的 approved validation fixture 改为引用
  perf baseline verifier report，并新增弱证据负例：只有 `status=passed` /
  `performance=firstLaunch` 的文件必须被拒绝。
- `docs/release_checklist.md` 同步该门禁口径。

验证命令：

```bash
bash -n scripts/verify_release_validation_record.sh scripts/test_validation_scripts.sh
git diff --check
scripts/test_validation_scripts.sh
scripts/verify_release_validation_record.sh --report build/verification/release-validation-current.properties
```

结果：

- 通过：shell 语法检查、`git diff --check` 和 validation script self-tests。
- 预期失败：当前 release validation record 仍未 approved；performance sanity
  仍是 pending，失败原因继续保留真实未完成项。
- 未执行模拟器：本轮只加固 release validation 门禁脚本、测试 fixture 和文档，
  不改变 APK runtime 或 UI 行为。

## 2026-06-06 Release validation API matrix evidence hardening

本轮覆盖项：

- `scripts/verify_release_validation_record.sh` 现在会校验每个 `apiMatrix`
  evidence 文件本身，而不只相信 JSON 中的 `status=passed` 和 SHA。
- API matrix evidence 必须是 nested per-API `regression-emulator.properties`：
  `status=passed`、`target=regression-emulator`、`clean_device=1`、API level
  匹配、`abi=arm64-v8a`、AVD 非空，且 `actual_android_test_count` 不低于
  AndroidTest source count。
- `scripts/test_validation_scripts.sh` 的 approved validation fixture 改为完整
  per-API regression report 形态，并新增弱证据负例：只有 `status=passed` /
  `api_level=28` 的文件必须被拒绝。
- `docs/release_checklist.md` 同步该门禁口径。

验证命令：

```bash
bash -n scripts/verify_release_validation_record.sh scripts/test_validation_scripts.sh
git diff --check
scripts/test_validation_scripts.sh
scripts/verify_release_validation_record.sh --report build/verification/release-validation-current.properties
```

结果：

- 通过：shell 语法检查、`git diff --check` 和 validation script self-tests。
- 预期失败：当前 release validation record 仍未 approved；API 36 evidence
  已是完整 nested regression report，失败原因仍集中在未完成的真机、API 28/32/33/34、
  manual acceptance、flow matrix、performance sanity 和 reviewer/date 项。
- 未执行模拟器：本轮只加固 release validation 门禁脚本、测试 fixture 和文档，
  不改变 APK runtime 或 UI 行为。

## 2026-06-06 Release validation API36 evidence refresh after image boundary

本轮覆盖项：

- `docs/release_validation_record.json` 的 `emulatorRegression` 和 API 36 evidence
  从较早的 API 36 emulator report 更新到 post shared-image no-implicit-OCR boundary 的
  API 36 matrix nested report。
- `docs/release_readiness.md` 同步记录当前 API 36 release-candidate emulator
  evidence 和 matrix evidence path。
- 整体 release validation 仍保持 `pending_validation`；没有把 API 36 emulator
  evidence 冒充为真机、API 28/32/33/34、manual acceptance、flow matrix、
  performance sanity 或 reviewer approval。

验证命令：

```bash
shasum -a 256 build/verification/regression-emulator-api36-no-implicit-image-ocr/api-36/regression-emulator.properties
scripts/verify_release_validation_record.sh --report build/verification/release-validation-current.properties
```

结果：

- 通过：API 36 nested regression report SHA-256 为
  `4811a6b53c3096ad5b441c807bc877f54aa7a384a991b0e14ed0d261ad9cd47b`，与
  `docs/release_validation_record.json` 中的 `emulatorRegression.reportSha256` 和
  API 36 `evidenceSha256` 一致。
- 预期失败：当前 release validation record 仍未 approved；
  `build/verification/release-validation-current.properties` 继续记录未完成的真机、
  API 28/32/33/34、manual acceptance、flow matrix、performance sanity 和
  reviewer/date 项。
- 未执行模拟器：本轮只刷新已存在的通过证据引用；不新增 runtime 行为验证。

## 2026-06-06 Release flow candidate evidence rejection

本轮覆盖项：

- `scripts/verify_release_validation_record.sh` 现在会拒绝把 flow candidate evidence
  当成正式 passed flow evidence。
- 若 flow evidence 文件包含 `target=release-flow-matrix-candidate-evidence`、
  `candidateOnly=true` 或 `releaseFlowPassed=false`，release validation record 会失败；
  这些文件只能作为 reviewer input，不能替代真实 release flow。
- `scripts/test_validation_scripts.sh` 新增负例：approved validation fixture 中的
  `firstInstall` 被替换成 candidate evidence 后，验证器必须报告
  `flow-firstInstall-candidate-evidence-not-approved` 和
  `flow-firstInstall-release-flow-not-passed`。
- `docs/release_checklist.md` 同步该门禁口径。

验证命令：

```bash
bash -n scripts/verify_release_validation_record.sh scripts/test_validation_scripts.sh
git diff --check
scripts/test_validation_scripts.sh
scripts/verify_release_validation_record.sh --report build/verification/release-validation-current.properties
```

结果：

- 通过：shell 语法检查、`git diff --check` 和 validation script self-tests。
- 预期失败：当前 `docs/release_validation_record.json` 仍未 approved；
  `build/verification/release-validation-current.properties` 记录
  `status=failed`，失败原因仍是未完成的真机、API 28/32/33/34、
  manual acceptance、flow matrix、performance sanity 和 reviewer 字段。
- 未执行模拟器：本轮只加固 release validation 门禁脚本、测试 fixture 和文档，
  不改变 APK runtime 或 UI 行为。

## 2026-06-06 Shared image no implicit OCR boundary

本轮覆盖项：

- 分享/附件中的普通 `image/*` 在本地模型路径不再自动运行 OCR，也不打开图片 stream；
  图片默认进入远程视觉模型输入，远程模型不支持视觉时直接提示不支持。
- 保留显式、受确认保护的 OCR 工具能力，例如最近图片 OCR、截图 OCR 和 PDF 扫描页
  OCR fallback；本次只移除 shared image 的隐式 OCR 兜底。
- UI prompt、composer 摘要、远程 runtime require message、Capability Matrix、
  privacy notice 和 phone acceptance 文档同步为“不自动 OCR / 不支持视觉”口径。

验证命令：

```bash
./gradlew :app:testDebugUnitTest --tests 'com.bytedance.zgx.pocketmind.multimodal.SharedInputTest' --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.stagedSharedImageWithoutOcrWarnsLocalModelThatVisualContentIsUnavailable' --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteModeRejectsSharedImageOcrPreviewBeforeBuildingPrompt' --tests 'com.bytedance.zgx.pocketmind.runtime.RemoteChatRuntimeTest'
git diff --check
bash -n scripts/check_emulator_api_matrix.sh scripts/regression_emulator_api_matrix.sh scripts/test_validation_scripts.sh scripts/verify_local.sh
scripts/test_validation_scripts.sh
ANDROID_HOME="$HOME/Library/Android/sdk" scripts/check_emulator_api_matrix.sh --report build/verification/emulator-api-matrix-readiness-current.properties
ANDROID_HOME="$HOME/Library/Android/sdk" scripts/verify_local.sh
ANDROID_HOME="$HOME/Library/Android/sdk" ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" EMULATOR_ARGS='-no-window -no-audio -no-boot-anim -no-snapshot-save' EMULATOR_SELECT_TIMEOUT_SECONDS=120 BOOT_TIMEOUT_SECONDS=300 scripts/regression_emulator_api_matrix.sh --required-apis 36 --artifact-dir build/verification/regression-emulator-api36-no-implicit-image-ocr --report build/verification/regression-emulator-api36-no-implicit-image-ocr/regression-emulator-api-matrix.properties --readiness-report build/verification/regression-emulator-api36-no-implicit-image-ocr/emulator-api-matrix-readiness.properties
```

结果：

- 通过：targeted JVM 回归、validation script self-tests、`git diff --check`、
  脚本语法检查和 `scripts/verify_local.sh`。
- 通过：API 36 emulator matrix 回归；
  `build/verification/regression-emulator-api36-no-implicit-image-ocr/regression-emulator-api-matrix.properties`
  记录 `status=passed`、`passedApis=36`、
  `api36ReportSha256=4811a6b53c3096ad5b441c807bc877f54aa7a384a991b0e14ed0d261ad9cd47b`。
- 通过：API 36 per-API 回归；
  `build/verification/regression-emulator-api36-no-implicit-image-ocr/api-36/regression-emulator.properties`
  记录 `status=passed`、`actual_android_test_count=28`、`api_level=36`、
  `abi=arm64-v8a`、`avd=focus_agent_api36_arm64`。
- 预期失败：完整 API matrix readiness 仍未就绪；
  `build/verification/emulator-api-matrix-readiness-current.properties` 记录
  `status=failed`、`failedTarget=api-matrix-readiness`、
  `reason=missing-system-image-api-28,missing-avd-api-28,missing-system-image-api-32,missing-avd-api-32,missing-system-image-api-33,missing-avd-api-33,missing-system-image-api-34,missing-avd-api-34`。
- 未执行：真机验收仍按当前约束跳过；本轮只在模拟器中验证。

## 2026-06-06 Upgrade install emulator evidence

本轮覆盖项：

- 新增 `scripts/verify_upgrade_install_emulator.sh`，默认从最近一次 app 源码变更的前一个
  commit 构建 base debug APK，也支持用 `UPGRADE_BASE_APK` / `UPGRADE_CURRENT_APK`
  显式传入待验证 APK，再在 API 36 emulator 上执行 base install -> current
  `adb install -r` -> current smoke AndroidTest。
- report 记录 base/current APK SHA、base/current signer SHA-256、signer 是否一致、
  install 原始输出文件、base/current package `firstInstallTime` / `lastUpdateTime`、
  versionCode、当前 commit、base commit、AVD、API、ABI 和 instrumentation 结果。
- 该 report 明确写入 `releaseFlowPassed=false`：当前只证明 debug APK 的升级安装
  smoke path，不证明正式 release upgrade flow。`docs/release_validation_record.json`
  的 `flowMatrix.upgradeInstall` 保持 pending。

验证命令：

```bash
bash -n scripts/verify_upgrade_install_emulator.sh scripts/verify_local.sh scripts/test_validation_scripts.sh
scripts/test_validation_scripts.sh
ANDROID_HOME="$HOME/Library/Android/sdk" ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" AVD_NAME=focus_agent_api36_arm64 ARTIFACT_DIR=build/verification/upgrade-install-emulator-current REPORT_FILE=build/verification/upgrade-install-emulator-current/upgrade-install-emulator.properties EMULATOR_ARGS='-no-window -no-audio -no-boot-anim -no-snapshot-save' EMULATOR_SELECT_TIMEOUT_SECONDS=120 BOOT_TIMEOUT_SECONDS=300 scripts/verify_upgrade_install_emulator.sh
ARTIFACT_DIR=build/verification/release-flow-matrix-current REPORT_FILE=build/verification/release-flow-matrix-current/release-flow-matrix-candidate-evidence.properties scripts/collect_release_flow_matrix_evidence.sh
scripts/verify_release_validation_record.sh --report build/verification/release-validation-current.properties
```

结果：

- 通过：真实 API 36 emulator upgrade install smoke；
  `build/verification/upgrade-install-emulator-current/upgrade-install-emulator.properties`
  记录 `status=passed`、`installMode=adb-install-r`、`releaseFlowPassed=false`、
  `instrumentation=passed`、`instrumentation_test_count=4`。
- 通过：upgrade smoke report 保留 `baseInstallOutputFile`、`currentInstallOutputFile`、
  `testInstallOutputFile`、`baseSignerSha256`、`currentSignerSha256` 和
  `signerSha256Matches`，便于后续替换为正式 signed release APK 时复核签名与安装输出。
- 通过：package evidence 显示 `firstInstallTime` 保持 `2026-06-06 20:32:56`，
  `lastUpdateTime` 从 `2026-06-06 20:32:56` 更新到 `2026-06-06 20:33:04`。
- 未通过正式 flow：base/current debug APK 的 `versionCode` 都是 1，
  report 同时保留纯数字 `baseVersionCode=1` / `currentVersionCode=1` 与 raw
  dumpsys 行，`versionCodeIncreased=false`，且尚未验证 seeded
  session/memory/reminder 保留或 `MY_PACKAGE_REPLACED` 后 reminder 重排。
- 通过：validation script self-tests。
- 预期失败：release validation record 仍未 approved；`flow-upgradeInstall-not-passed`
  仍保留，避免把 debug smoke evidence 冒充为正式 release upgrade evidence。

## 2026-06-06 Release flow matrix candidate evidence

本轮覆盖项：

- 新增 `scripts/collect_release_flow_matrix_evidence.sh`，基于已通过且
  `clean_device=1` 的 API 36 `regression-emulator.properties` 生成 flow matrix
  候选 evidence。
- 候选 evidence 明确写入 `candidateOnly=true` 和 `releaseFlowPassed=false`；它只说明
  当前自动化回归覆盖到哪些行为，不会把脚本、mock 或 UI 文案冒充成 release flow 通过。
- collector 仍要求 `docs/release_validation_record.json` 里的 flowMatrix 项提供正式
  `status=passed`、evidence path、SHA、owner 和日期；缺项时报告
  `failedTarget=flow-matrix`。
- `docs/release_validation_record.json` 的 flowMatrix 保持 pending，等待真实 release
  flow、系统 picker、语音、MediaProjection 前台同意、重启提醒、升级安装和模型下载校验
  证据补齐。

验证命令：

```bash
bash -n scripts/collect_release_flow_matrix_evidence.sh scripts/test_validation_scripts.sh scripts/verify_local.sh
ARTIFACT_DIR=build/verification/release-flow-matrix-current REPORT_FILE=build/verification/release-flow-matrix-current/release-flow-matrix-candidate-evidence.properties scripts/collect_release_flow_matrix_evidence.sh
scripts/test_validation_scripts.sh
scripts/verify_release_validation_record.sh --report build/verification/release-validation-current.properties
ANDROID_HOME="$HOME/Library/Android/sdk" ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" scripts/verify_local.sh
ANDROID_HOME="$HOME/Library/Android/sdk" ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" AVD_NAME=focus_agent_api36_arm64 ARTIFACT_DIR=build/verification/regression-emulator-flow-candidate-current REGRESSION_REPORT_FILE=build/verification/regression-emulator-flow-candidate-current/regression-emulator.properties EMULATOR_ARGS='-no-window -no-audio -no-boot-anim -no-snapshot-save' EMULATOR_SELECT_TIMEOUT_SECONDS=120 BOOT_TIMEOUT_SECONDS=300 scripts/regression_emulator.sh
```

结果：

- 预期失败：当前 release validation record 的 flowMatrix 全部仍为 pending；
  `build/verification/release-flow-matrix-current/release-flow-matrix-candidate-evidence.properties`
  记录 `status=failed`、`failedTarget=flow-matrix` 和
  `reason=missing-approved-release-evidence-...`。
- 通过：collector 生成 8 个候选 evidence 文件，覆盖 first install、custom model URL、
  remote config contract、encrypted API key clear、session persistence、memory controls、
  Accessibility confirmation 和 MediaProjection cancellation confirmation 的自动化证据映射。
- 通过：validation script self-tests，覆盖 collector pending 失败、完整 flowMatrix fixture
  通过和 source regression SHA 不匹配失败。
- 通过：`scripts/verify_local.sh`，覆盖 shell syntax、validation script self-tests、JVM
  tests、lint、debug/release assemble、release bundle 和 Android artifact scan。
- 通过：真实 API 36 emulator 回归；
  `build/verification/regression-emulator-flow-candidate-current/regression-emulator.properties`
  记录 `status=passed`、`actual_android_test_count=28`、`serial=emulator-5554`、
  `api_level=36`、`avd=focus_agent_api36_arm64`。
- 未更新 release validation record 为 passed；这些候选文件不能替代系统介入型流程和人工验收。

## 2026-06-06 Release screenshot evidence capture

本轮覆盖项：

- 新增 `scripts/capture_release_screenshots.sh`，在模拟器上安装 debug 包，通过
  UiAutomator 节点导航采集 `chat-home`、`model-manager`、`confirmation-sheet`、
  `background-tasks-or-audit` 四张脱敏发布截图。
- 截图脚本默认只接受 emulator serial；物理设备必须显式设置
  `ALLOW_PHYSICAL_SCREENSHOTS=1`，避免误采个人设备内容。
- Debug-only Activity extra 只在 debuggable build 中启用，用于截图 evidence 时配置
  远程 ready 状态；它不保存 API key，正式包不会启用。
- `docs/release_validation_record.json` 的 screenshots 字段已更新为本次 PNG path 和
  SHA-256；没有把截图 evidence 冒充为物理真机、API 28/32/33/34、人工验收或性能通过。

验证命令：

```bash
bash -n scripts/capture_release_screenshots.sh scripts/test_validation_scripts.sh scripts/verify_local.sh
scripts/test_validation_scripts.sh
./gradlew :app:assembleDebug
ANDROID_HOME="$HOME/Library/Android/sdk" ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" AVD_NAME=focus_agent_api36_arm64 ARTIFACT_DIR=build/verification/release-screenshots-current REPORT_FILE=build/verification/release-screenshots-current/release-screenshots.properties EMULATOR_ARGS='-no-window -no-audio -no-boot-anim -no-snapshot-save' EMULATOR_SELECT_TIMEOUT_SECONDS=120 BOOT_TIMEOUT_SECONDS=300 scripts/capture_release_screenshots.sh
ANDROID_HOME="$HOME/Library/Android/sdk" ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" scripts/verify_local.sh
```

结果：

- 通过：validation script self-tests，覆盖截图脚本默认拒绝物理 serial、MainActivity
  debug extra、四张截图 SHA 和脱敏标记。
- 通过：真实 API 36 emulator 截图采集；
  `build/verification/release-screenshots-current/release-screenshots.properties` 记录
  `status=passed`、`serial=emulator-5554`、`api_level=36`、`clean_device=1`。
- 通过：四张 PNG 已人工视觉检查，分别对应首页、模型管理、工具确认、后台任务页。
- 通过：脚本结束后 `adb devices -l` 为空，没有遗留运行中的 emulator。
- 通过：`scripts/verify_local.sh`，覆盖 shell syntax、validation script self-tests、JVM
  tests、lint、debug/release assemble、release bundle 和 Android artifact scan。

## 2026-06-06 Emulator API matrix regression runner

本轮覆盖项：

- 新增 `scripts/regression_emulator_api_matrix.sh`，先执行
  `scripts/check_emulator_api_matrix.sh`，readiness 通过后再按 API 顺序运行
  `scripts/regression_emulator.sh`。
- 每个 API 生成独立 `api-<level>/regression-emulator.properties`，顶层生成
  `regression-emulator-api-matrix.properties`，记录 `passedApis`、`failedApis`、
  per-API AVD、report path 和 SHA-256。
- runner 默认不允许已有 emulator 混入矩阵；每个 API 跑完后尝试关闭本轮选中的
  emulator，避免后续 API 选择歧义。
- runner 支持 `--artifact-dir`、`--report`、`--readiness-report`、`--required-apis`、
  `--avd-root`、`--check-script`、`--regression-script`、`--allow-existing-emulators`
  和 `--keep-emulators`；显式 `REPORT_FILE` 环境变量不会被 `--artifact-dir` 覆盖。
- `scripts/test_validation_scripts.sh` 用 fake sdkmanager / fake AVD / fake regression
  覆盖 matrix runner 全通过、显式 env report path 和单 API 回归失败路径。

验证命令：

```bash
bash -n scripts/regression_emulator_api_matrix.sh scripts/check_emulator_api_matrix.sh scripts/test_validation_scripts.sh scripts/verify_local.sh
scripts/test_validation_scripts.sh
ARTIFACT_DIR=build/verification/regression-emulator-api-matrix-current REPORT_FILE=build/verification/regression-emulator-api-matrix-current/regression-emulator-api-matrix.properties scripts/regression_emulator_api_matrix.sh
ANDROID_HOME="$HOME/Library/Android/sdk" ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" REQUIRED_APIS=36 ARTIFACT_DIR=build/verification/regression-emulator-api36-current REPORT_FILE=build/verification/regression-emulator-api36-current/regression-emulator-api-matrix.properties EMULATOR_ARGS='-no-window -no-audio -no-boot-anim -no-snapshot-save' EMULATOR_SELECT_TIMEOUT_SECONDS=120 BOOT_TIMEOUT_SECONDS=300 scripts/regression_emulator_api_matrix.sh
ANDROID_HOME="$HOME/Library/Android/sdk" ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" EMULATOR_ARGS='-no-window -no-audio -no-boot-anim -no-snapshot-save' EMULATOR_SELECT_TIMEOUT_SECONDS=120 BOOT_TIMEOUT_SECONDS=300 scripts/regression_emulator_api_matrix.sh --required-apis 36 --artifact-dir build/verification/regression-emulator-api36-cli-current --report build/verification/regression-emulator-api36-cli-current/regression-emulator-api-matrix.properties --readiness-report build/verification/regression-emulator-api36-cli-current/emulator-api-matrix-readiness.properties
```

结果：

- 通过：validation script self-tests，覆盖 matrix runner success 和 API failure report。
- 预期失败：完整默认矩阵在 readiness 阶段失败；
  `build/verification/regression-emulator-api-matrix-current/regression-emulator-api-matrix.properties`
  记录 `failedTarget=readiness`，原因是 API 28/32/33/34 system image 和 AVD 缺失。
- 通过：真实 API 36 matrix runner CLI 回归；
  `build/verification/regression-emulator-api36-cli-current/regression-emulator-api-matrix.properties`
  记录 `status=passed`、`passedApis=36`，嵌套
  `api-36/regression-emulator.properties` 记录 28 个 AndroidTest 全部通过，SHA-256 为
  `ffeeed7942cf4fd13a1cd9a9ef1d1577ff96360a262cea7f6629896fd7151373`。
- 通过：runner 结束后 `adb devices -l` 为空，没有遗留运行中的 emulator。

## 2026-06-06 Emulator API matrix readiness reporting

本轮覆盖项：

- 新增 `scripts/check_emulator_api_matrix.sh`，只检查本机 SDK system image 和 AVD
  是否覆盖 API 28/32/33/34/36 的 `google_apis` / `arm64-v8a` 组合。
- 脚本不安装 SDK 包、不创建 AVD、不启动 emulator；失败时写
  `emulator-api-matrix-readiness.properties`，包含 `failedTarget`、`reason`、
  installed/available/missing API 列表。
- readiness 脚本支持 `--report`、`--required-apis`、`--avd-root`、`--tag`、
  `--abi` 和 `--sdkmanager` CLI 参数。
- `scripts/test_validation_scripts.sh` 用 fake sdkmanager 和 fake AVD 覆盖 matrix
  readiness passed、CLI report path，以及缺少 system image / AVD 的 failed report。
- `scripts/verify_local.sh` 将新脚本纳入 shell syntax check。

验证命令：

```bash
bash -n scripts/check_emulator_api_matrix.sh scripts/verify_local.sh scripts/test_validation_scripts.sh
scripts/test_validation_scripts.sh
REPORT_FILE=build/verification/emulator-api-matrix-readiness.properties scripts/check_emulator_api_matrix.sh
ANDROID_HOME="$HOME/Library/Android/sdk" ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" scripts/check_emulator_api_matrix.sh --report build/verification/emulator-api-matrix-readiness-current.properties
```

结果：

- 通过：validation script self-tests，覆盖 API matrix readiness success 和 failure
  report。
- 预期失败：当前本机 readiness report 记录 `status=failed`、
  `installedSystemImageApis=36`、`availableAvdApis=36`、
  `missingSystemImageApis=28,32,33,34`、`missingAvdApis=28,32,33,34`。
- 未安装 SDK 包：按 Android emulator testing 约束，本轮只记录缺口，不擅自下载
  API 28/32/33/34 system image 或创建 AVD。

## 2026-06-06 Release validation record emulator evidence refresh

本轮覆盖项：

- `docs/release_validation_record.json` 的 `emulatorRegression` 和 API 36 matrix evidence
  更新到最新通过的模拟器回归：
  `build/verification/regression-emulator-20260606-182722/regression-emulator.properties`。
- 该 report 记录 `status=passed`、`actual_android_test_count=28`、API 36、
  `arm64-v8a`、AVD `focus_agent_api36_arm64`，SHA-256 为
  `9ec70151511c741701e9e53bb924e3671b990bd921273d7c11da6a3560fdfa51`。
- 没有把 emulator evidence 冒充为物理真机、API 28/32/33/34、手工验收、
  screenshot 或 performance sanity 通过；这些字段仍保持 pending。

验证命令：

```bash
shasum -a 256 build/verification/regression-emulator-20260606-182722/regression-emulator.properties
scripts/verify_release_validation_record.sh --report build/verification/release-validation-current.properties
```

结果：

- 通过：最新 API 36 emulator evidence 文件存在，SHA-256 与
  `docs/release_validation_record.json` 一致。
- 预期失败：`scripts/verify_release_validation_record.sh --report
  build/verification/release-validation-current.properties` 仍返回 `status=failed`；
  原因是 release validation record 尚未完成非模拟器物理设备、API 28/32/33/34、
  manual acceptance、flow matrix、sanitized screenshots、performance sanity 和 reviewer
  approval。

## 2026-06-06 Model license metadata source candidate hardening

本轮覆盖项：

- `scripts/collect_model_license_metadata.sh` 新增 `REPORT_FILE` 输出，成功和失败都会记录
  `target=model-license-metadata-collector`、`failedTarget`、`reason`、输入文件、API
  base URL 和 model count。
- metadata collector 从 Hugging Face API 的 `siblings` 中提取 license、notice、terms、
  model-card、README 等候选来源，写入
  `docs/model_license_metadata.json.licenseSourceCandidates`。
- `scripts/test_validation_scripts.sh` 使用本地 fake Hugging Face API 覆盖成功采集、
  source candidate 写入，以及缺少 review 文件时的机器可读失败报告。
- 实际刷新了 `docs/model_license_metadata.json`，四个推荐模型均记录了 pinned revision
  下的 README 候选来源。
- 当前 `docs/model_license_review.json` 仍为 `pending_manual_review` /
  `not_approved`；候选来源只服务人工审核，不替代 legal/release approval。

验证命令：

```bash
bash -n scripts/collect_model_license_metadata.sh scripts/verify_model_license_review.sh scripts/test_validation_scripts.sh
scripts/test_validation_scripts.sh
REPORT_FILE=build/verification/model-license-metadata-collector.properties scripts/collect_model_license_metadata.sh
scripts/verify_model_license_review.sh --report build/verification/model-license-current.properties
ANDROID_HOME="$HOME/Library/Android/sdk" ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" scripts/verify_local.sh
ANDROID_HOME="$HOME/Library/Android/sdk" ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" AVD_NAME=focus_agent_api36_arm64 EMULATOR_ARGS='-no-window -no-audio -no-boot-anim -no-snapshot-save' EMULATOR_SELECT_TIMEOUT_SECONDS=120 BOOT_TIMEOUT_SECONDS=300 scripts/regression_emulator.sh
```

结果：

- 通过：validation script self-tests，覆盖 collector success report、
  `licenseSourceCandidates` 写入和 missing review failure report。
- 通过：真实 Hugging Face metadata 刷新；
  `build/verification/model-license-metadata-collector.properties` 记录
  `status=passed`、`modelCount=4`。
- 预期失败：`scripts/verify_model_license_review.sh --report
  build/verification/model-license-current.properties` 返回
  `status=failed`，原因是四个模型尚未完成人工审批、redistribution approval、具体
  license source、reviewer、evidence 和 review date。
- 通过：`scripts/verify_local.sh`。
- 通过：真实模拟器回归 `focus_agent_api36_arm64` / `emulator-5554`，API 36，
  `arm64-v8a`，28 个 AndroidTest 全部通过；
  `build/verification/regression-emulator-20260606-182722/regression-emulator.properties`
  记录 `status=passed`、`failedTarget=`、`reason=`，SHA-256 为
  `9ec70151511c741701e9e53bb924e3671b990bd921273d7c11da6a3560fdfa51`。

## 2026-06-06 Perf baseline collector failure reason hardening

本轮覆盖项：

- `scripts/collect_perf_baseline.sh` 失败时会把 `OUT_FILE` 写成
  `status=failed` 的 `perf-baseline-collector` 报告。
- collector failed report 新增 `exit_code`、`failedTarget`、`reason`、
  release artifact path/SHA、设备元数据、app/model/backend、verification report
  和 verification reason。
- 缺少必填环境变量、release artifact 不存在、ADB 无法读取且未手动提供设备元数据、
  以及 `verify_perf_baseline.sh` 拒绝采集结果时，都会写机器可读失败原因。
- 成功路径仍只记录真实输入的测量值，不发明 timing；失败路径不会写成可发布 baseline。
- `scripts/test_validation_scripts.sh` 覆盖 missing env、missing artifact、emulator
  serial 被 verifier 拒绝，以及成功采集路径。
- `docs/release_checklist.md` 同步 collector failed report 字段要求。

验证命令：

```bash
bash -n scripts/collect_perf_baseline.sh scripts/test_validation_scripts.sh
scripts/test_validation_scripts.sh
ANDROID_HOME="$HOME/Library/Android/sdk" ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" scripts/verify_local.sh
```

结果：

- 通过：validation script self-tests，覆盖 perf collector failedTarget / reason 和
  verificationReason 汇总。
- 通过：`scripts/verify_local.sh`。
- 未记录正式 RC perf baseline：当前未连接目标物理设备，也没有生产签名 release
  artifact；不能用模拟器或手填假数据替代。

## 2026-06-06 Release signing failure reason hardening

本轮覆盖项：

- `scripts/sign_release_artifacts.sh` 现在通过统一 report trap 为成功和失败都生成
  `release-signing` report。
- signing report 新增 `exit_code`、`failedTarget`、`reason`、`allowDebugKeystore`、
  unsigned/signed artifact path、artifact scan report/status/reason，以及已生成产物 SHA。
- 缺少签名环境变量、debug keystore、缺少 production cert pin、缺少 unsigned AAB、
  工具缺失、签名/验证失败、artifact scan 失败都会写机器可读失败原因。
- report 不记录 keystore password、key password 或私钥材料。
- `scripts/test_validation_scripts.sh` 覆盖 signing 早失败路径的 failedTarget/reason。
- `docs/release_checklist.md` 同步 signing failed report 字段要求。

验证命令：

```bash
bash -n scripts/sign_release_artifacts.sh scripts/test_validation_scripts.sh
scripts/test_validation_scripts.sh
ANDROID_HOME="$HOME/Library/Android/sdk" ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" scripts/verify_local.sh
```

结果：

- 通过：validation script self-tests，覆盖 release signing missing env、debug
  keystore、missing production cert pin、missing unsigned AAB 的失败报告。
- 通过：`scripts/verify_local.sh`。
- 未执行 production signing：当前没有外部 production keystore 和
  `EXPECTED_SIGNING_CERT_SHA256`。

## 2026-06-06 Live remote emulator failure reason hardening

本轮覆盖项：

- `scripts/live_remote_emulator.sh` 的 report 新增 `failedTarget`、`reason`、
  `evidence_dir`、`screenshot`、`ui_dump` 和 `logcat_file`。
- 缺少 `POCKETMIND_LIVE_REMOTE_BASE_URL` / model / API key、非 emulator serial、
  assemble/install/config broadcast、UI 输入、远端请求失败、预期文本缺失等路径会写入
  机器可读失败原因。
- 失败且已选中 emulator 时，会尽量捕获截图、UI dump 和短 logcat。
- `scripts/test_validation_scripts.sh` 覆盖缺配置、非 emulator serial、成功 redaction、
  expected text missing，并确认失败 report 不落盘 API key。
- `docs/release_checklist.md` 同步 live remote report 失败字段和证据路径要求。

验证命令：

```bash
bash -n scripts/live_remote_emulator.sh scripts/test_validation_scripts.sh
scripts/test_validation_scripts.sh
ANDROID_HOME="$HOME/Library/Android/sdk" ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" scripts/verify_local.sh
```

结果：

- 通过：validation script self-tests，覆盖 live remote failedTarget / reason、
  screenshot / UI dump / logcat evidence path，以及 API key redaction。
- 通过：`scripts/verify_local.sh`。
- 未执行真实 live remote 模型请求：当前没有临时注入
  `POCKETMIND_LIVE_REMOTE_BASE_URL`、`POCKETMIND_LIVE_REMOTE_MODEL` 和
  `POCKETMIND_LIVE_REMOTE_API_KEY`。

## 2026-06-06 Emulator failure evidence contract hardening

本轮覆盖项：

- `scripts/install_and_test_device.sh` 的 device report 新增机器可读 `failedTarget`
  和 `reason`，覆盖 device selection、ABI、data free space、install 和
  instrumentation 失败。
- `scripts/verify_emulator.sh` 的 emulator report 新增 `failedTarget`、`reason`、
  `evidence_dir`、`screenshot_file`、`window_dump_file`、`logcat_file`，并把 nested
  device report reason 汇总为 emulator 失败原因。
- `scripts/regression_emulator.sh` 的权威 regression report 新增 `failedTarget`
  和 `reason`，覆盖 emulator helper 失败、expected test count 配置错误、
  instrumentation count 缺失或不足。
- `scripts/test_validation_scripts.sh` 锁住 device / emulator / regression failed
  reports 的 reason、failedTarget 和 failure screenshot evidence。
- `docs/release_checklist.md` 同步设备/模拟器验证报告失败字段要求。

验证命令：

```bash
bash -n scripts/install_and_test_device.sh scripts/verify_emulator.sh scripts/regression_emulator.sh scripts/test_validation_scripts.sh
scripts/test_validation_scripts.sh
ANDROID_HOME="$HOME/Library/Android/sdk" ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" scripts/verify_local.sh
ANDROID_HOME="$HOME/Library/Android/sdk" ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" AVD_NAME=focus_agent_api36_arm64 EMULATOR_ARGS='-no-window -no-audio -no-boot-anim -no-snapshot-save' EMULATOR_SELECT_TIMEOUT_SECONDS=120 BOOT_TIMEOUT_SECONDS=300 scripts/regression_emulator.sh
```

结果：

- 通过：validation script self-tests，覆盖 device / emulator / regression failed
  report reason 和 evidence path。
- 通过：`scripts/verify_local.sh`。
- 通过：真实模拟器回归 `focus_agent_api36_arm64` / `emulator-5554`，API 36，
  `arm64-v8a`，28 个 AndroidTest 全部通过；
  `build/verification/regression-emulator-20260606-175158/regression-emulator.properties`
  记录 `status=passed`、`failedTarget=`、`reason=`，SHA-256 为
  `abeaf2f2fc8c108da77d9fbb541bc44be29a1aa739ed985a8eab310e613d1509`。

## 2026-06-06 Privacy scan failure reason hardening

本轮覆盖项：

- `scripts/privacy_scan.sh` 在 failed report 中新增机器可读 `reason` 字段，发现高置信
  secret 模式时写入 `secret-pattern-detected`。
- `scripts/verify_release_gate.sh` 可以把 privacy scan 子报告的 `reason` 提升到
  `release-gate.properties.failedReason`，并支持追加临时 scan target 以验证失败路径，不替换默认扫描范围。
- 追加 scan target 只接受路径；option-like 输入会 fail-closed，避免影响 child report
  输出位置。
- `scripts/test_validation_scripts.sh` 覆盖直接 privacy scan 失败，以及 release gate
  privacy-scan failedTarget / failedReason 汇总，以及无效追加 scan target；测试 secret
  放在临时目录，避免污染仓库工作区。
- `docs/release_checklist.md` 同步 privacy scan failed report 必须提供 reason。

验证命令：

```bash
bash -n scripts/privacy_scan.sh scripts/verify_release_gate.sh scripts/test_validation_scripts.sh
scripts/test_validation_scripts.sh
ANDROID_HOME="$HOME/Library/Android/sdk" ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" scripts/verify_local.sh
```

结果：

- 通过：validation script self-tests，覆盖 privacy scan failure reason 和 release gate
  privacy-scan failedReason 汇总。
- 通过：`scripts/verify_local.sh`。
- 未执行模拟器：本轮只加固 privacy/release gate 报告和测试 fixture，不改变 APK runtime
  或 UI 行为。

## 2026-06-06 Android artifact scan failure reason hardening

本轮覆盖项：

- `scripts/scan_android_artifacts.sh` 在 failed report 中新增机器可读 `reason` 字段，
  汇总 missing artifact、不可读 zip、manifest 结构缺失、禁止打包文件、敏感字符串、
  signing status、debug certificate 和证书 SHA mismatch 等失败原因。
- `scripts/verify_release_gate.sh` 可以把 artifact scan 子报告的 `reason` 提升到
  `release-gate.properties.failedReason`。
- `scripts/test_validation_scripts.sh` 覆盖 bundled model、bad AAB、unsigned artifact、
  debug certificate、certificate mismatch，以及 release gate artifact scan failedReason。
- `docs/release_checklist.md` 同步 artifact scan failed report 必须提供 reason list。

验证命令：

```bash
bash -n scripts/scan_android_artifacts.sh scripts/verify_release_gate.sh scripts/test_validation_scripts.sh
scripts/test_validation_scripts.sh
ANDROID_HOME="$HOME/Library/Android/sdk" ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" scripts/verify_local.sh
```

结果：

- 通过：validation script self-tests，覆盖 Android artifact scan failure reason 和 release
  gate artifact failedReason 汇总。
- 通过：`scripts/verify_local.sh`。
- 未执行模拟器：本轮只加固 artifact/release gate 报告和测试 fixture，不改变 APK runtime
  或 UI 行为。

## 2026-06-06 Perf baseline failure reason hardening

本轮覆盖项：

- `scripts/verify_perf_baseline.sh` 在 failed report 中新增机器可读 `reason` 字段，
  记录缺失字段、emulator 设备、ABI、artifact SHA、app version、数值、时间戳等失败原因。
- `scripts/verify_release_gate.sh` 可以把 perf baseline 子报告的 `reason` 提升到
  `release-gate.properties.failedReason`。
- `scripts/test_validation_scripts.sh` 覆盖 incomplete perf、artifact SHA mismatch、
  emulator serial，以及 release gate 中 perf 子门禁失败摘要。
- `docs/release_checklist.md` 同步 perf baseline failed report 必须提供 reason list。

验证命令：

```bash
bash -n scripts/verify_perf_baseline.sh scripts/verify_release_gate.sh scripts/test_validation_scripts.sh
scripts/test_validation_scripts.sh
ANDROID_HOME="$HOME/Library/Android/sdk" ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" scripts/verify_local.sh
```

结果：

- 通过：validation script self-tests，覆盖 perf baseline failure reason 和 release gate
  perf failedReason 汇总。
- 通过：`scripts/verify_local.sh`。
- 未执行模拟器：本轮只加固 perf/release gate 报告和测试 fixture，不改变 APK runtime
  或 UI 行为。

## 2026-06-06 Release gate failure summary hardening

本轮覆盖项：

- `scripts/verify_release_gate.sh` 的 `release-gate.properties` 新增 `failedTarget` 和
  `failedReason` 字段，失败时指向具体失败子门禁和原因。
- privacy scan、contract tests、artifact scan、perf baseline、mapping、release record、
  store policy、operations、validation、model license、privacy review 的失败路径统一走
  `fail_gate`，避免 `set -e` 直接退出而缺少总 gate 失败摘要。
- 保留所有子报告文件作为权威细节；总报告只做定位索引。
- `scripts/test_validation_scripts.sh` 覆盖 missing perf、signing cert、privacy/model
  review、artifact scan、release record、mapping、store policy、operations、validation
  等失败目标。

验证命令：

```bash
bash -n scripts/verify_release_gate.sh scripts/test_validation_scripts.sh
scripts/test_validation_scripts.sh
ANDROID_HOME="$HOME/Library/Android/sdk" ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" scripts/verify_local.sh
```

结果：

- 通过：validation script self-tests，覆盖 release gate 失败摘要字段。
- 通过：`scripts/verify_local.sh`，覆盖 shell syntax、validation script self-tests、JVM
  tests、lint、debug/release assemble、release bundle 和 Android artifact scan。
- 未执行模拟器：本轮只加固 release gate 报告和测试 fixture，不改变 APK runtime 或 UI
  行为。

## 2026-06-22 Release verification report schema/freshness hardening

本轮覆盖项：

- `scripts/verify_release_record.sh` 对 `release.verificationReports` 增加
  fail-closed 校验：报告文件必须匹配记录中的 SHA-256，且包含
  `artifactSchema`、`status=passed`、`target`、`owner`、UTC `recordedAt`、
  可复现 `command` 和匹配的 `reproduciblePath`。
- verification report 的 `recordedAt` 默认必须在 30 天内；可用
  `RELEASE_RECORD_VERIFICATION_REPORT_MAX_AGE_DAYS` 调整窗口。未来时间、
  stale 时间和缺少 schema 的 passed 报告都会被拒绝。
- `scripts/test_validation_scripts.sh` 增加无 schema passed 报告和 stale 报告
  负例，并把 release record 正例报告升级为机器可读 schema evidence。
- `docs/release_checklist.md` 与 `docs/release_readiness.md` 同步 release owner
  需要提供新鲜、schema/owner 标记、可复现、SHA 绑定的 verification reports。

验证命令：

```bash
bash -n scripts/verify_release_record.sh scripts/test_validation_scripts.sh
scripts/test_validation_scripts.sh
scripts/verify_release_record.sh --report build/verification/release-record-current.properties
```

结果：

- 通过：`bash -n scripts/verify_release_record.sh scripts/test_validation_scripts.sh`。
- 通过：release-record 专项临时 fixture 验证，覆盖 approved 正例、无 schema
  passed 报告负例、stale 报告负例和当前 pending record 失败。
- 通过：集成 Device/Eval evidence 改动后，整条 `scripts/test_validation_scripts.sh`
  返回 `Validation script tests passed.`。
- 当前 `docs/release_record.json` 仍按预期 fail-closed，报告
  `/tmp/release-record-current.properties` 记录 `status=failed`、
  `failedTarget=release-record`，原因包含 `status-not-approved`、
  `verification-reports-missing` 和人工 blocker evidence 缺失；本轮没有伪造 release
  owner 审批、生产签名或真机证据。

## 2026-06-06 Release blocker evidence hardening

本轮覆盖项：

- `scripts/verify_release_record.sh` 要求每个 resolved/accepted blocker 都提供存在的
  `evidencePath`，并匹配 `evidenceSha256`。
- `docs/release_record.json` pending 模板为 privacy review、model license review 和
  production signing blocker 新增 evidence path / sha 字段；状态仍保持
  `pending_release_record`，不替代真实 release owner 决策。
- `scripts/test_validation_scripts.sh` 增加 blocker evidence 正例和 blocker evidence SHA
  mismatch 负例。
- `docs/release_checklist.md` 同步 release blocker decision evidence 必须绑定 SHA 的门禁要求。

验证命令：

```bash
bash -n scripts/verify_release_record.sh scripts/test_validation_scripts.sh
scripts/test_validation_scripts.sh
scripts/verify_release_record.sh --report build/verification/release-record-current.properties
ANDROID_HOME="$HOME/Library/Android/sdk" ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" scripts/verify_local.sh
```

结果：

- 通过：validation script self-tests，覆盖 approved blocker evidence 正例和 blocker
  evidence SHA mismatch 负例。
- 当前 `docs/release_record.json` 仍按预期未通过；真实剩余项仍是 release owner/reviewer、
  release artifact、verification reports、blocker decision 和对应 blocker evidence。
- 未执行模拟器：本轮只加固 release record 脚本、测试 fixture 和文档，不改变 APK
  runtime 或 UI 行为。

## 2026-06-06 Store policy review evidence hardening

本轮覆盖项：

- `scripts/verify_store_policy_record.sh` 要求 approved store policy review
  提供存在的 `review.evidencePath`，并匹配 `review.evidenceSha256`。
- `docs/store_policy_record.json` pending 模板新增 review evidence path / sha 字段；
  状态仍保持 `pending_policy_review`，不替代真实商店政策审批。
- `scripts/test_validation_scripts.sh` 增加 store policy review evidence 正例和 review
  evidence SHA mismatch 负例。
- `docs/release_checklist.md` 同步 store policy review evidence path 必须绑定 SHA
  的门禁要求。

验证命令：

```bash
bash -n scripts/verify_store_policy_record.sh scripts/test_validation_scripts.sh
scripts/test_validation_scripts.sh
scripts/verify_store_policy_record.sh --report build/verification/store-policy-current.properties
ANDROID_HOME="$HOME/Library/Android/sdk" ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" scripts/verify_local.sh
```

结果：

- 通过：validation script self-tests，覆盖 approved review evidence 正例和 review
  evidence SHA mismatch 负例。
- 当前 `docs/store_policy_record.json` 仍按预期未通过；真实剩余项仍是非占位联系邮箱、
  非占位 privacy policy URL、reviewer、review date 和对应 review evidence。
- 未执行模拟器：本轮只加固 store policy review 脚本、测试 fixture 和文档，不改变
  APK runtime 或 UI 行为。

## 2026-06-06 Model license review evidence hardening

本轮覆盖项：

- `scripts/verify_model_license_review.sh` 要求每个 approved model license review
  都提供存在的 `reviewEvidencePath`，并匹配 `reviewEvidenceSha256`。
- `docs/model_license_review.json` pending 模板新增每个模型的 review evidence path /
  sha 字段；状态仍保持 `pending_manual_review`，不替代真实 license 审批。
- `scripts/test_validation_scripts.sh` 增加模型 license review evidence 正例和
  review evidence SHA mismatch 负例。
- `docs/release_checklist.md` 同步模型 license approval evidence path 必须绑定 SHA
  的门禁要求。

验证命令：

```bash
bash -n scripts/verify_model_license_review.sh scripts/test_validation_scripts.sh
scripts/test_validation_scripts.sh
scripts/verify_model_license_review.sh --report build/verification/model-license-current.properties
ANDROID_HOME="$HOME/Library/Android/sdk" ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" scripts/verify_local.sh
```

结果：

- 通过：validation script self-tests，覆盖 approved review evidence 正例和 review
  evidence SHA mismatch 负例。
- 当前 `docs/model_license_review.json` 仍按预期未通过；真实剩余项仍是每个模型的
  license source、redistribution/attribution 决策、reviewer、review date 和对应
  review evidence。
- 未执行模拟器：本轮只加固 model license review 脚本、测试 fixture 和文档，不改变
  APK runtime 或 UI 行为。

## 2026-06-06 Privacy review evidence hardening

本轮覆盖项：

- `scripts/verify_privacy_review.sh` 要求 release/security/legal 三方审批记录必须是
  version 1，且每个 approved role 都提供存在的 evidence file 和匹配 SHA-256。
- `docs/privacy_review.json` pending 模板新增每个 role 的 `evidencePath` /
  `evidenceSha256` 字段；状态仍保持 `pending_manual_review`，不替代真实审批。
- `scripts/test_validation_scripts.sh` 增加三方 privacy review evidence 正例和 release
  evidence SHA mismatch 负例。
- `docs/release_checklist.md` 同步隐私 review evidence path 必须绑定 SHA 的门禁要求。

验证命令：

```bash
bash -n scripts/verify_privacy_review.sh scripts/test_validation_scripts.sh
scripts/test_validation_scripts.sh
scripts/verify_privacy_review.sh --report build/verification/privacy-review-current.properties
ANDROID_HOME="$HOME/Library/Android/sdk" ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" scripts/verify_local.sh
```

结果：

- 通过：validation script self-tests，覆盖 approved evidence 正例和 evidence SHA
  mismatch 负例。
- 当前 `docs/privacy_review.json` 仍按预期未通过；真实剩余项仍是 release/security/legal
  owner 审批、reviewer、review date 和对应 evidence。
- 未执行模拟器：本轮只加固 privacy review 脚本、测试 fixture 和文档，不改变 APK
  runtime 或 UI 行为。

## 2026-06-06 Release validation evidence SHA hardening

本轮覆盖项：

- `scripts/verify_release_validation_record.sh` 要求 release validation 中的 emulator
  regression report、physical device report、API matrix evidence、manual/flow/perf
  evidence 和 sanitized screenshots 都提供匹配 SHA-256。
- `docs/release_validation_record.json` 将已真实存在的 API 36 emulator regression
  证据绑定到当前 `regression-emulator.properties` SHA；pending 项仍保持空证据并继续阻塞
  release。
- `scripts/test_validation_scripts.sh` 的 approved validation fixture 自动注入 evidence
  SHA，并新增 report、API、manual evidence、screenshot SHA mismatch 负例。
- `docs/release_checklist.md` 同步 validation evidence path 必须绑定 SHA 的门禁要求。

验证命令：

```bash
bash -n scripts/verify_release_validation_record.sh scripts/test_validation_scripts.sh
scripts/test_validation_scripts.sh
scripts/verify_release_validation_record.sh --report build/verification/release-validation-current.properties
./gradlew :app:testDebugUnitTest --tests 'com.bytedance.zgx.pocketmind.docs.AgentCoreDocumentationTest'
ANDROID_HOME="$HOME/Library/Android/sdk" ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" scripts/verify_local.sh
```

结果：

- 通过：validation script self-tests，覆盖 approved SHA-bound evidence 正例，以及
  emulator report、API evidence、manual evidence、screenshot SHA mismatch 负例。
- 通过：`AgentCoreDocumentationTest` targeted JVM test。
- 通过：`scripts/verify_local.sh`，覆盖 shell syntax、validation script self-tests、JVM
  tests、lint、debug/release assemble、release bundle 和 Android artifact scan。
- 当前 `docs/release_validation_record.json` 仍按预期未通过；真实剩余项仍是未完成的
  physical device、API 28/32/33/34、manual/flow/perf、截图和 review。
- 未执行模拟器：本轮只加固 release validation 脚本、测试 fixture 和文档，不改变 APK
  runtime 或 UI 行为。

## 2026-06-06 Release operations evidence hardening

本轮覆盖项：

- `scripts/verify_release_operations_record.sh` 要求 monitoring setup、crash/ANR smoke
  和 rollback plan 都提供存在的 evidence file，并匹配 SHA-256。
- `docs/release_operations_record.json` 模板新增三处 evidence path / sha256 字段，状态仍保持
  `pending_operations_review`。
- `scripts/test_validation_scripts.sh` 增加 operations evidence 正例和 crash/ANR smoke SHA
  mismatch 负例。
- `docs/release_checklist.md` 同步该门禁要求。

验证命令：

```bash
bash -n scripts/verify_release_operations_record.sh scripts/test_validation_scripts.sh
scripts/test_validation_scripts.sh
scripts/verify_release_operations_record.sh --report build/verification/release-operations-current.properties
```

结果：

- 通过：validation script self-tests，覆盖 operations evidence 文件正例和 SHA mismatch 负例。
- 当前 `docs/release_operations_record.json` 仍按预期未通过；失败原因包含
  monitoring/crash-anr-smoke/rollback evidence path 缺失，以及真实待补的 owner、watcher、
  crash/ANR smoke、rollback、previous known-good 和 reviewer/date。
- 未执行模拟器：本轮只加固 release operations 脚本、测试 fixture 和文档，不改变 APK
  runtime 或 UI 行为。

## 2026-06-06 Model license source specificity hardening

本轮覆盖项：

- `scripts/verify_model_license_review.sh` 不再接受 Hugging Face repository root
  作为 approved review 的 license source。
- Hugging Face license source 必须指向同一 manifest repository 下的具体文件 URL，
  例如 `blob`、`raw` 或 `resolve` 路径中的 README、LICENSE、NOTICE、terms 或 model card。
- `scripts/test_validation_scripts.sh` 将 approved fixture 改为具体 README blob URL，并新增
  repository root 失败用例。
- `docs/release_checklist.md` 同步该门禁要求。

验证命令：

```bash
bash -n scripts/verify_model_license_review.sh scripts/test_validation_scripts.sh
scripts/test_validation_scripts.sh
scripts/verify_model_license_review.sh --report build/verification/model-license-current.properties
```

结果：

- 通过：validation script self-tests，覆盖具体 Hugging Face source 正例、错误 repository
  负例和 repository root 负例。
- 当前 `docs/model_license_review.json` 仍按预期未通过；失败原因包含每个 pending 模型的
  `license-source-not-concrete`，仍需人工补具体 license/model-card source、license name、
  redistribution decision、attribution notice、reviewer 和 review date。
- 未执行模拟器：本轮只加固 model license review 脚本、测试 fixture 和文档，不改变 APK
  runtime 或 UI 行为。

## 2026-06-06 Release validation manual evidence hardening

本轮覆盖项：

- `scripts/verify_release_validation_record.sh` 不再接受 manual acceptance、flow matrix
  或 performance sanity 中的裸字符串 `passed`。
- 这些项目现在必须是结构化 evidence record：`status=passed`、非空 evidence、
  存在的 `evidencePath`、owner、非未来日期。
- `scripts/test_validation_scripts.sh` 升级 approved fixture，并增加裸 `passed` 失败用例。
- `docs/release_checklist.md` 同步该门禁口径。

验证命令：

```bash
bash -n scripts/verify_release_validation_record.sh scripts/test_validation_scripts.sh
scripts/test_validation_scripts.sh
scripts/verify_release_validation_record.sh --report build/verification/release-validation-current.properties
```

结果：

- 通过：validation script self-tests，覆盖 structured evidence 正例和裸 `passed` 负例。
- 当前 `docs/release_validation_record.json` 仍按预期未通过；真实剩余项仍是未完成的
  physical device、API 28/32/33/34、manual/flow/perf、截图和 review。
- 未执行模拟器：本轮只加固 release validation 脚本、测试 fixture 和文档，不改变 APK
  runtime 或 UI 行为。

## 2026-06-06 Release validation emulator evidence refresh

本轮覆盖项：

- `docs/release_readiness.md` 的 current emulator regression 证据从旧的 26 tests
  记录更新为最新 28/28 AndroidTest 记录。
- `docs/release_validation_record.json` 只填入已真实通过的 API 36 emulator regression
  证据；整体状态继续保持 `pending_validation`，不替代真机、API matrix、manual
  acceptance、截图或 perf 验收。

验证命令：

```bash
scripts/verify_release_validation_record.sh --report build/verification/release-validation-current.properties
```

结果：

- 当前 verifier 按预期未通过；失败原因不包含 emulator regression report mismatch，说明
  `build/verification/regression-emulator-20260606-160247/regression-emulator.properties`
  已被识别为有效 emulator 证据。
- 剩余失败项仍为真实未完成的 release validation 门槛：非 emulator 真机、API 28/32/33/34
  matrix、manual acceptance、flow matrix、净化截图、performance sanity 和 reviewer/date。

## 2026-06-06 Store policy draft hardening

本轮覆盖项：

- `docs/store_policy_record.json` 从空壳补成可审核草案：同步当前 privacy notice SHA，
  补 Store listing 草稿、Data safety 说明、模型下载说明、manifest 权限用途和特殊访问说明。
- `scripts/verify_store_policy_record.sh` 新增占位联系邮箱/隐私 URL 拒绝，避免
  `example.*` 或 `.invalid` 占位值被误审批为正式商店记录。
- `scripts/test_validation_scripts.sh` 增加 store policy 占位值负测。

验证命令：

```bash
scripts/test_validation_scripts.sh
scripts/verify_store_policy_record.sh --report build/verification/store-policy-current.properties
ANDROID_HOME="$HOME/Library/Android/sdk" ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" scripts/verify_local.sh
```

结果：

- 通过：validation script self-tests，覆盖 store policy 占位联系信息负测。
- 通过：local verification，包含 JVM、lint、debug/release 构建、`bundleRelease` 和 APK/AAB scan。
- 当前 `scripts/verify_store_policy_record.sh` 仍按预期未通过；失败原因收敛为
  `status-not-approved`、占位 contact/privacy URL、reviewer/date 缺失，仍需真实商店联系信息、
  外部隐私政策 URL 和人工 policy review。
- 未执行模拟器：本轮只修改发布政策 JSON、验证脚本和文档，不改变 APK runtime 或 UI 行为。

## 2026-06-06 Chat message privacy DB default hardening

本轮覆盖项：

- `chat_messages.privacy` 的 Room schema 默认值从 `RemoteEligible` 收紧为
  `LocalOnly`，让省略 privacy 的持久化写入在数据库边界 fail-closed。
- 新增 `MIGRATION_11_12` 重建 `chat_messages` 表，仅修改默认值，不改变已有行显式
  privacy；新增 instrumentation 回归覆盖 11→12 默认值变更和省略字段插入。

验证命令：

```bash
ANDROID_HOME="$HOME/Library/Android/sdk" ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" \
  ./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.docs.AgentCoreDocumentationTest' \
  --tests 'com.bytedance.zgx.pocketmind.memory.MemoryRepositoryTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest'
AVD_NAME=focus_agent_api36_arm64 EMULATOR_SELECT_TIMEOUT_SECONDS=90 BOOT_TIMEOUT_SECONDS=300 \
  EMULATOR_ARGS="-no-window -no-audio -no-boot-anim -no-snapshot-save" \
  ANDROID_HOME="$HOME/Library/Android/sdk" ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" \
  scripts/regression_emulator.sh
ANDROID_HOME="$HOME/Library/Android/sdk" ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" scripts/verify_local.sh
scripts/verify_privacy_review.sh --report build/verification/privacy-review-current.properties
```

结果：

- 通过：debug Kotlin / AndroidTest Kotlin 编译。
- 通过：targeted JVM 文档、记忆和工具隐私契约回归。
- 通过：emulator regression，`focus_agent_api36_arm64` / API 36 / arm64-v8a / clean device。
- 通过：28 个 AndroidTest，artifact:
  `build/verification/regression-emulator-20260606-160247/regression-emulator.properties`。
- 通过：local verification，包含 JVM、lint、debug/release 构建、`bundleRelease` 和 APK/AAB scan。
- 隐私 notice SHA 已同步到 `docs/privacy_review.json`；public privacy review 仍保持
  `pending_manual_review`，`scripts/verify_privacy_review.sh` 未通过的原因仅为
  release/security/legal 决策、reviewer 和日期缺失，需要人工审批后才能通过公开发布门禁。

## 2026-06-06 Legacy title LocalOnly migration boundary

本轮覆盖项：

- `LegacyPrefsMigrator` 在为旧 SharedPreferences 会话推导标题时，把 legacy message
  显式转成 `LocalOnly`，避免无标题旧会话把分享文本、OCR 摘录或其他本地内容写进
  session title。
- 新增 instrumentation 回归：
  `PocketMindDatabaseMigrationTest.legacyPrefsMigratorDerivesUntitledLegacyMessagesAsLocalOnlyTitle`，
  断言旧私密用户消息导入后标题为 `本地内容`，且不包含原始 secret token。

验证命令：

```bash
AVD_NAME=focus_agent_api36_arm64 EMULATOR_SELECT_TIMEOUT_SECONDS=90 BOOT_TIMEOUT_SECONDS=300 \
  EMULATOR_ARGS="-no-window -no-audio -no-boot-anim -no-snapshot-save" \
  ANDROID_HOME="$HOME/Library/Android/sdk" ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" \
  scripts/regression_emulator.sh
ANDROID_HOME="$HOME/Library/Android/sdk" ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" scripts/verify_local.sh
```

结果：

- 通过：emulator regression，`focus_agent_api36_arm64` / API 36 / arm64-v8a / clean device。
- 通过：27 个 AndroidTest，artifact:
  `build/verification/regression-emulator-20260606-154829/regression-emulator.properties`。
- 通过：local verification，包含 JVM、lint、debug/release 构建、`bundleRelease` 和 APK/AAB scan。

## 2026-06-06 Public evidence remote-continuation boundary

本轮覆盖项：

- `AgentLoopRuntime` 只允许显式 `privacy=RemoteEligible` 且
  `requiresLocalModel=false` 的公开证据结果进入远端续跑；缺失 metadata 不再 fail-open。
- `web_search` 成功结果和 output schema 声明 `RemoteEligible` / `requiresLocalModel=false`。
- Tool Registry 和 Agent Loop 增加合同测试，锁定公开证据结果的远端资格声明。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest.publicEvidenceOutputSchemasRequireRemotePrivacyDeclaration' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.publicEvidenceContinuationRejectsResultMissingRemotePrivacyDeclaration' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.sequentialPublicEvidenceContinuationIncludesPriorEvidence'
ANDROID_HOME="$HOME/Library/Android/sdk" ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" scripts/verify_local.sh
```

结果：

- 通过：targeted public evidence JVM contract tests。
- 通过：local verification，包含 JVM、lint、debug/release 构建、`bundleRelease` 和 APK/AAB scan。
- 通过：emulator regression 中的 remote `web_search` instrumentation path。

## 2026-06-06 Conversation recall assistant-output boundary

本轮覆盖项：

- `MemoryRepository.rebuild()` 只从用户消息重建 `Conversation` 回忆，不再索引助手
  消息；即使助手消息被标记为 `RemoteEligible`，也不会进入自动记忆召回池。
- Capability Matrix 和 privacy notice 同步说明会话回忆只从用户消息重建，助手输出不作为
  conversation recall。

验证命令：

```bash
./gradlew :app:testDebugUnitTest --tests 'com.bytedance.zgx.pocketmind.memory.MemoryRepositoryTest'
ANDROID_HOME="$HOME/Library/Android/sdk" ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" scripts/verify_local.sh
```

结果：

- 通过：MemoryRepository JVM tests。
- 通过：local verification，包含 JVM、lint、debug/release 构建、`bundleRelease` 和 APK/AAB scan。
- 未执行模拟器：本轮只修改本地记忆索引边界和 JVM 回归测试，不改变 Android UI 或系统交互。

## 2026-06-06 Dirty public release record hardening

本轮覆盖项：

- `scripts/verify_release_record.sh` 在 `PUBLIC_RELEASE_CONTEXT=1` 时默认拒绝脏 Git
  工作区，避免正式 release record 绑定到含未提交或未跟踪改动的源码状态。
- 新增 `ALLOW_DIRTY_RELEASE=1` 显式 override，仅用于非生产 dry-run/self-test，并写入
  verifier report。
- `scripts/test_validation_scripts.sh` 增加脏公开发布记录失败用例。

验证命令：

```bash
bash -n scripts/verify_release_record.sh scripts/test_validation_scripts.sh
scripts/test_validation_scripts.sh
ANDROID_HOME="$HOME/Library/Android/sdk" ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" scripts/verify_local.sh
```

结果：

- 通过：validation script self-tests。
- 通过：local verification，包含 release record dirty-worktree gate 自测。
- 未执行模拟器：本轮只加固 release record 脚本和文档，不改变 APK runtime 或 UI 行为。

## 2026-06-06 Remote privacy boundary hardening

本轮覆盖项：

- 新增 `SharedInput.toRemoteVisionPrompt()`，远端视觉图片请求的文字 prompt 只记录图片数量
  和不支持提示，不包含附件文件名、MIME、大小、OCR 或非图片元数据；图片 bytes 仍作为
  `ChatImageAttachment` 直接交给视觉模型。
- 远端初始 Chat 发送前增加 fail-closed 边界：`AssistantRoute.Chat` 若带
  `memoryHits`、`deviceContext`，或把远端 prompt 改写成非用户输入，会本地失败并把诊断
  消息标为 `LocalOnly`，不调用 remote runtime。
- 隐私 notice 和 capability matrix 同步说明远端视觉 prompt 不携带附件元数据。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.multimodal.SharedInputTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteModeSendsSharedImageAttachmentToVisionRuntime' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteImageDraftWhenRemoteIsNotReadyDoesNotEnterLaterRemoteHistory' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteModeFailsClosedWhenRouteIncludesMemoryContext' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteModeFailsClosedWhenRouteRewritesPrompt' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteModeKeepsSemanticMemoryRuntimeButDoesNotSendMemoryContext'
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest' \
  --tests 'com.bytedance.zgx.pocketmind.memory.MemoryQualityContractTest' \
  --tests 'com.bytedance.zgx.pocketmind.runtime.RemoteChatRuntimeTest'
ANDROID_HOME="$HOME/Library/Android/sdk" ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" scripts/verify_local.sh
```

结果：

- 通过：targeted shared-input / remote privacy JVM tests。
- 通过：ViewModel、MemoryQualityContract、RemoteChatRuntime JVM tests。
- 通过：local verification，包含 JVM、lint、debug/release 构建、`bundleRelease` 和 APK/AAB scan。
- 未执行模拟器：本轮修改 ViewModel/multimodal privacy boundary 和 JVM 回归测试，不改变 Android UI 或系统交互。

## 2026-06-06 Release artifact integrity hardening

本轮覆盖项：

- `scripts/scan_android_artifacts.sh` 现在拒绝不可读 zip、缺失 APK manifest、缺失 AAB
  `BundleConfig.pb` 或 `base/manifest/AndroidManifest.xml` 的产物。
- `scripts/verify_release_gate.sh` 只要启用 release record 校验，就把 gate 实际选择的
  artifact path/type/sha 传给 `scripts/verify_release_record.sh`，不再只限制 public release。
- `scripts/sign_release_artifacts.sh` 生产签名默认要求 unsigned AAB 存在，避免 APK-only
  signing report 被误当成 Play candidate。

验证命令：

```bash
bash -n scripts/scan_android_artifacts.sh scripts/verify_release_gate.sh scripts/sign_release_artifacts.sh scripts/test_validation_scripts.sh
scripts/test_validation_scripts.sh
ANDROID_HOME="$HOME/Library/Android/sdk" ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" scripts/verify_local.sh
```

结果：

- 通过：validation script self-tests。
- 通过：local verification，真实 release APK/AAB 通过 artifact integrity scan。
- 未执行模拟器：本轮只加固 release artifact/record/signing 脚本和文档，不改变 APK runtime 或 UI 行为。

## 2026-06-06 Release AAB gate hardening

本轮覆盖项：

- `scripts/verify_local.sh` 现在执行 `bundleRelease`，确认 release AAB 存在，并把
  APK/AAB 一起交给 `scripts/scan_android_artifacts.sh` 扫描。
- `scripts/verify_release_gate.sh` 在要求 signed AAB 时，默认校验
  `app/build/outputs/bundle/release/app-release-signed.aab`，避免正式门禁误验未签名
  `app-release.aab`。
- `scripts/test_validation_scripts.sh` 增加本地 AAB 扫描契约和 signed AAB 默认路径负测。

验证命令：

```bash
bash -n scripts/verify_local.sh scripts/verify_release_gate.sh scripts/test_validation_scripts.sh
scripts/test_validation_scripts.sh
ANDROID_HOME="$HOME/Library/Android/sdk" ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" scripts/verify_local.sh
```

结果：

- 通过：validation script self-tests。
- 通过：local verification，包含 `bundleRelease` 和 APK/AAB artifact scan。
- 未执行模拟器：本轮只加固 release/AAB 脚本和文档，不改变 APK runtime 或 UI 行为。

## 2026-06-06 Release record source commit hardening

本轮覆盖项：

- `scripts/verify_release_record.sh` 要求 `release.gitCommit` 等于当前 `HEAD`，不再接受
  仅作为当前历史祖先的旧提交。
- `scripts/test_validation_scripts.sh` 增加旧 commit release record 的失败用例。

验证命令：

```bash
bash -n scripts/verify_release_record.sh scripts/test_validation_scripts.sh
scripts/test_validation_scripts.sh
```

结果：

- 通过：validation script self-tests。
- 未执行模拟器：本轮只加固 release record 脚本和文档，不改变 APK runtime 或 UI 行为。

## 2026-06-06 ModelHealth generation failure hardening

本轮覆盖项：

- 本地/远程生成失败 catch 分支会将 `modelHealth.state` 更新为 `LoadFailed`，并写入
  `failureReason`，避免 UI 同时显示“生成失败”和“健康：已加载”。
- 覆盖普通本地生成崩溃、本地工具续写崩溃、远程工具 parse failure 三条路径。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.localGenerationFailureUpdatesModelHealth' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.clipboardSummaryShareLocalContinuationFailureFailsAgentRunWithoutSecondConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.malformedRemoteToolCallFailsClosedBeforeConfirmationOrExecution'
```

结果：

- 通过：targeted ViewModel ModelHealth JVM tests。
- 未执行模拟器：本轮只修改 ViewModel 状态模型和 JVM 回归测试，不改变 Android UI 或系统交互。

## 2026-06-06 Remote tool exposure allowlist hardening

本轮覆盖项：

- `ToolRegistryTest.remoteToolExposureRequiresExplicitReviewedAllowlist` 锁定远端公共证据工具
  和远端模型规划工具的完整工具名清单。
- 新增工具若要暴露给远程模型，必须显式修改 allowlist contract test；不能只靠
  `isRemoteModelPlanningEligible()` 派生规则静默进入远端工具 snapshot。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest.remoteToolExposureRequiresExplicitReviewedAllowlist' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest.publicEvidenceBatchEligibilityOnlyAllowsSafePublicReadOnlyTools'
```

结果：

- 通过：targeted ToolRegistry JVM tests。
- 未执行模拟器：本轮只新增工具暴露 contract test，不改变 APK runtime 或 UI 行为。

## 2026-06-06 Memory disabled task-state suppression

本轮覆盖项：

- `syncTaskStateMemories()` 受 `memoryEnabled` gate 控制；本地记忆关闭时会删除自动管理的
  TaskState 记录，并跳过 scheduled/running background task 的自动写入。
- `createInitialState()` 先读取 first-run memory 开关，再同步 TaskState，避免初始化阶段绕过
  用户关闭记忆的选择。
- `updateMemoryEnabled(false)` 会清空 UI 里的长期记忆和 memory hits，并防止 refresh/send
  路径重新生成 TaskState 记忆。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.memoryDisabledDoesNotIndexScheduledTaskStateOnStartupRefreshOrSend' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.updateMemoryDisabledRemovesActiveTaskStateMemoryAndPreventsResync'
```

结果：

- 通过：targeted ViewModel memory JVM tests。
- 未执行模拟器：本轮只修改记忆开关和 JVM 回归测试，不改变 Android UI 或系统交互。

## 2026-06-06 Remote image draft not-ready privacy hardening

本轮覆盖项：

- `sendPendingSharedInput()` 在模型或远程配置未就绪时，保存的 shared-input 用户消息强制标记
  `LocalOnly`，即使原 draft 是带图片的 `RemoteEligible`。
- 新增回归：远程视觉图片 draft 创建后，如果远程配置变为未就绪，发送失败留下的历史不会进入
  后续远程请求。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteImageDraftWhenRemoteIsNotReadyDoesNotEnterLaterRemoteHistory'
```

结果：

- 通过：targeted ViewModel JVM test。
- 未执行模拟器：本轮只修改 ViewModel 未就绪分支和 JVM 回归测试，不改变 Android UI
  控件或平台交互。

## 2026-06-06 Release validation API evidence hardening

本轮覆盖项：

- `scripts/verify_release_validation_record.sh` 要求 `apiMatrix` 中 API 28、32、33、
  34、36 每一行除了 `status=passed` 和非空描述外，还必须提供存在的
  `evidencePath` 文件。
- `docs/release_validation_record.json` 模板同步新增每个 API 行的 `evidencePath`。
- `scripts/test_validation_scripts.sh` 增加 approved record 的 API evidence 文件正例和
  缺失 evidence file 的失败用例。

验证命令：

```bash
bash -n scripts/verify_release_validation_record.sh scripts/test_validation_scripts.sh
scripts/test_validation_scripts.sh
```

结果：

- 通过：validation script self-tests。
- 未执行模拟器：本轮只加固 release validation 证据脚本和文档模板，不改变 APK runtime
  或 UI 行为。

## 2026-06-06 Capability test reference hardening

本轮覆盖项：

- `CapabilityMatrixDocumentationTest` 将 required test class 存在性校验从
  product capabilities 扩展到 `CapabilityMatrix.allDescriptors()`，覆盖产品能力和
  `ToolRegistry` 派生的全部工具能力。
- 目的：能力矩阵中的 `requiredTests` 不能只是非空字符串；拼错、删除或漂移的测试类名会在
  release gate 默认 contract tests 中失败。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.docs.CapabilityMatrixDocumentationTest'
```

结果：

- 通过：capability matrix contract JVM test。
- 未执行模拟器：本轮只加固文档/代码 contract test，不改变 APK runtime 或 UI 行为。

## 2026-06-06 Tool capability matrix materialization

本轮覆盖项：

- `docs/capability_matrix.json` 新增 `toolCapabilities`，按 `ToolRegistry` 当前顺序
  逐项记录 26 个工具能力的 capabilityId、entrypoint、toolName、model capability、
  privacy level、local-model requirement、remote eligibility、confirmation policy、
  failure behavior、required tests 和 owner Agent。
- `CapabilityMatrixDocumentationTest` 校验 `toolCapabilities` 与
  `CapabilityMatrix.toolDescriptors(ToolRegistry())` 逐字段一致，防止 ToolRegistry
  和机器可读能力矩阵漂移。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.docs.CapabilityMatrixDocumentationTest'
```

结果：

- 通过：capability matrix contract JVM test。
- 未执行模拟器：本轮只修改能力矩阵 JSON 和 JVM 文档测试，不改变 Android runtime
  或 UI 行为。

## 2026-06-06 Product capability matrix mainline coverage

本轮覆盖项：

- `CapabilityMatrix.productDescriptors` 从 3 个能力扩展为覆盖产品主线的 10 个能力：
  离线聊天、显式记忆、分享/文件文本输入、远程视觉图片输入、语音转写输入、受确认端侧工具、
  可审计 trace/audit、模型管理、Run Data Receipt 和 release gate。
- 新增 `UserProvided` capability privacy level，区分用户主动提供的文本/图片/语音转写与
  public evidence 工具结果。
- `CapabilityMatrixDocumentationTest` 从只校验 capability ID 扩展为逐字段校验
  `docs/capability_matrix.json` 与代码 descriptor 一致，并校验每个 product capability
  的 required test class 确实存在。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.docs.CapabilityMatrixDocumentationTest' \
  --tests 'com.bytedance.zgx.pocketmind.docs.AgentCoreDocumentationTest' \
  --tests 'com.bytedance.zgx.pocketmind.docs.ModelManifestDocumentationTest'
```

结果：

- 通过：capability matrix / agent core / model manifest contract JVM tests。
- 未执行模拟器：本轮只修改 capability contract、JSON 文档和 JVM 文档测试；不改变
  Android runtime 或 UI 行为。

## 2026-06-06 Remote image input unsupported-vision boundary

本轮覆盖项：

- 远程模式只有在当前 remote profile 声明支持 vision input 时，才进入
  `RemoteVision` 图片 byte 读取和 `ChatImageAttachment` 构造路径。
- 远程模式且 vision 关闭时，分享/选择图片只生成受保护图片信号；不读取图片 bytes、
  不生成 OCR、不创建可发送 draft，并给出本地 `LocalOnly` 不支持提示。
- 远程模式下分享文本保持受保护信号，不读取或发送分享文本值。

验证命令：

```bash
./gradlew testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.multimodal.SharedInputTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest' \
  --tests 'com.bytedance.zgx.pocketmind.MainActivitySharedInputModeTest'

ANDROID_HOME="$HOME/Library/Android/sdk" \
ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" \
scripts/verify_local.sh

ANDROID_HOME="$HOME/Library/Android/sdk" \
ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" \
AVD_NAME=focus_agent_api36_arm64 \
EMULATOR_ARGS='-no-window -no-audio -no-snapshot-save -no-boot-anim' \
EMULATOR_SELECT_TIMEOUT_SECONDS=120 \
BOOT_TIMEOUT_SECONDS=300 \
scripts/regression_emulator.sh
```

结果：

- 通过：目标 JVM 回归，覆盖 `SharedInputTest`、`PocketMindViewModelTest` 和
  `MainActivitySharedInputModeTest`。
- 通过：`scripts/verify_local.sh`，包含 validation script 回归、JVM tests、lint、
  debug/androidTest APK assembly、release assembly 和 APK 内容检查。
- 通过：完整 emulator regression：
  `build/verification/regression-emulator-20260606-140228/regression-emulator.properties`
  为 `status=passed`、`exit_code=0`；nested
  `emulator-verification.properties` 和 `device-verification.properties` 均
  `status=passed`；`instrumentation=passed`，`actual_android_test_count=26`，
  `source_android_test_count=26`。设备为 `focus_agent_api36_arm64` /
  `emulator-5554`，API 36，`arm64-v8a`。

## 2026-06-04 Agent privacy, public evidence, and emulator regression pass

本轮覆盖项：

- 本地模式普通聊天默认保存为 `LocalOnly`，后续切换远程模型时不会进入远程
  history；远程发送前会再次过滤误标为 `RemoteEligible` 的敏感历史。
- 工具 observation 隐私判定改为 fail-closed：未知/本地/缺失隐私结果默认
  `LocalOnly`；只有注册表声明的 public evidence 工具和 runtime 内部
  `public_evidence_batch` 聚合结果可远程续写。
- Public evidence batch 支持部分成功继续综合，失败缺口进入 continuation prompt；
  顺序 public evidence continuation 带上前序公开证据。
- 外部 Activity 启动失败不再把异常 message 写入用户 summary；后台任务查询只返回
  本地元数据，不暴露 reminder title/body。
- 文档与脚本门禁同步最新 emulator regression 通过状态。

验证命令：

```bash
./gradlew --no-daemon -Pkotlin.incremental=false :app:testDebugUnitTest
./gradlew --no-daemon -Pkotlin.incremental=false :app:compileDebugAndroidTestKotlin
bash -n scripts/*.sh && scripts/test_validation_scripts.sh && git diff --check
scripts/doctor.sh
scripts/verify_local.sh

AVD_NAME=focus_agent_api36_arm64 \
EMULATOR_ARGS='-no-window -no-audio -no-snapshot-save -no-boot-anim' \
EMULATOR_SELECT_TIMEOUT_SECONDS=120 \
BOOT_TIMEOUT_SECONDS=300 \
scripts/regression_emulator.sh
```

结果：

- 通过：完整 JVM 单测 `:app:testDebugUnitTest`，892 tests。
- 通过：`:app:compileDebugAndroidTestKotlin`。
- 通过：shell 语法、validation script 回归、`git diff --check`。
- 通过：`scripts/doctor.sh` 与 `scripts/verify_local.sh`，包含 debug/release 构建、
  JVM 单测、lint、androidTest APK 和 release APK 产物检查。
- 通过：完整 emulator regression：
  `build/verification/regression-emulator-20260604-040806/regression-emulator.properties`
  为 `status=passed`、`exit_code=0`；nested
  `emulator-verification.properties` 和 `device-verification.properties` 均
  `status=passed`；`instrumentation=passed`，`actual_android_test_count=26`，
  `source_android_test_count=26`，`instrumentation.txt` 非空。设备为
  `focus_agent_api36_arm64` / `emulator-5554`，API 36，`arm64-v8a`。
- 通过：密钥扫描未命中用户提供的 DeepSeek endpoint/model/key；远程模型配置仍只走
  现有安全配置入口。

## 2026-06-04 Device gate final success marker and emulator failure status

本轮覆盖项：

- `MainActivityComprehensiveTest.createAndSwitchSessions` 不再等待过期的
  `本地内容` 会话标题，改用测试内已发送的稳定远程提问标题
  `用一句话介绍端侧 AI` 作为切回旧会话锚点，并继续验证切回后能看到
  `记忆回答`。
- `scripts/install_and_test_device.sh` 的 instrumentation 通过条件收紧为：
  runner 退出码成功、没有 failure marker，且输出包含最终 `OK` / `OK (N tests)`
  成功 marker。单独出现 `INSTRUMENTATION_STATUS: numtests=N` 不再算通过。
- `scripts/test_validation_scripts.sh` 新增 malformed instrumentation 输出负例，覆盖
  只有 `numtests`、缺少最终 `OK` 时 device helper 必须失败并写出 failed report。

验证命令：

```bash
bash -n scripts/install_and_test_device.sh scripts/test_validation_scripts.sh
scripts/test_validation_scripts.sh
./gradlew --no-daemon -Pkotlin.incremental=false :app:compileDebugAndroidTestKotlin
```

结果：

- 通过：shell 语法检查。
- 通过：fake SDK validation script 回归，包含新增的 `numtests` without final `OK`
  负例。
- 未通过/阻塞：`:app:compileDebugAndroidTestKotlin` 在 `:app:compileDebugKotlin`
  阶段被当前工作区已有主代码编译错误阻塞：
  `AgentLoopRuntime.kt` 调用中 `successfulPairs` 和 `gapPairs` 参数未解析。该文件不在
  本轮写入范围内，本轮未修改业务实现。
- 此前完整 emulator regression 尝试为失败状态，不能作为通过证据：
  `build/verification/regression-emulator-20260604-033011/regression-emulator.properties`
  为 `status=failed`、`exit_code=1`；嵌套 device report 为
  `instrumentation=failed`、`instrumentation_test_count=26`。失败日志显示
  `MainActivityComprehensiveTest.createAndSwitchSessions` 等待旧 `本地内容` 标题超时。
  该轮未重跑完整 emulator regression，因此当时 release-candidate emulator gate
  未通过；最新通过记录见上方 2026-06-04 04:08 artifact。

## 2026-06-04 UI/docs worker acceptance wording

本轮覆盖项：

- 远程模型模式下，composer 附件 picker 显示与分享入口一致的本地保护提示，明确不会读取分享文本、附件元数据、文件流、文本摘录或 OCR 摘录，也不会自动发送。
- 远程规划动作确认卡只优化 UI 展示：长摘要/长参数折叠并显示长度，链接先显示域名，`packageName` 显示为目标包；不改变 planner、executor、权限或安全策略。
- README、Agent core 文档和 phone acceptance 收窄工具结果展示口径：聊天只显示安全摘要，结构化详情和 allowlisted completion metadata 在 trace/audit 查看，不声称已有 typed chat card。
- phone acceptance、validation report 和 release checklist 明确区分自动回归与必须手工验收；语音输入、Android 系统文档选择器和 MediaProjection 前台同意不能用脚本或直接 reader/ViewModel 调用替代。

验证命令：

```bash
./gradlew --no-daemon -Pkotlin.incremental=false :app:testDebugUnitTest --tests com.bytedance.zgx.pocketmind.ui.PocketMindScreenDisplayTest --tests com.bytedance.zgx.pocketmind.docs.AgentCoreDocumentationTest
./gradlew --no-daemon -Pkotlin.incremental=false :app:compileDebugKotlin
git diff --check
rg -n "远程模型模式下，选择附件只生成受保护信号|聊天中只应追加安全摘要|自动回归与必须手工验收的结论必须分开记录|Scripted regression and manual acceptance must be recorded separately|链接域名|目标包" README.md docs app/src/main/java/com/bytedance/zgx/pocketmind/ui/PocketMindScreen.kt app/src/test/java/com/bytedance/zgx/pocketmind -g '!**/build/**'
rg -n "聊天中应追加一条结构化执行结果|Agent run trace, audit log, and chat session|typed chat card" README.md docs app/src/test/java/com/bytedance/zgx/pocketmind/docs/AgentCoreDocumentationTest.kt
```

结果：

- 通过：`:app:compileDebugKotlin`。
- 通过：`git diff --check`。
- 通过：关键文案存在性扫描。旧工具结果口径只出现在本轮验证记录的扫描命令/说明和文档测试的否定断言中；`typed chat card` 只出现在 README 否定说明、本轮验证记录和文档测试断言中。
- 未通过/阻塞：定向 JVM 测试第一次成功编译并执行目标测试集，但旧断言失败；修正断言后，重跑在 `compileDebugUnitTestKotlin` 阶段被当前工作区内 `PocketMindViewModelTest.kt` 的 `lastRouteDeviceContext` 相关未解析符号和 `SkillRunExecutorTest.kt` 的 `reminderCancelSkillPlan` 未解析符号阻塞。这两个文件不在本轮 UI/docs worker 写入范围内，本轮未修改。
- 未执行：语音输入、Android 系统文档选择器和 MediaProjection 前台同意的手工验收；不能据此写成已手工通过。

## 2026-06-04 Agent safety/runtime integration 验证

本轮覆盖项：

- Debug 远程配置 receiver 改为非导出，live remote emulator 脚本通过
  debuggable app uid (`run-as`) 配置测试远程模型，不向源码、文档、报告或日志写入
  API key。
- 远程模型只接收 public-evidence eligible 工具目录；联系人、剪贴板、当前屏幕、
  文件、日历、通知、外部动作等私密或副作用工具不暴露给远程工具规划。
- `web_search` 对公开查询仍可无确认执行；疑似包含手机号、邮箱、地址、身份证、
  工号、账号、密码、token、API key 或类似个人/密钥内容的查询会动态回到用户确认
  路径，确认前不联网。
- 工具执行统一进入 IO coroutine 边界，executor 异常或 timeout 转为 retryable
  `ToolResult`；并发 public evidence 批次只重试失败且 retryable 的 request 一次，
  已成功的 request 不重复执行。
- 迟到的 external outcome 只能写回 `AwaitingExternalOutcome` run；已经
  `Completed`、`Failed` 或 `Cancelled` 的 run 不会被旧回调复活。
- Agent trace/audit 默认不持久化普通工具观察结果摘要或 result data；completion
  metadata 仍只走 allowlist。
- 工具输出 schema 中的 `*Json` payload 字段按当前 `ToolResult.data` string-map
  合同声明为 JSON string，并标注 `contentMediaType=application/json`，不再把
  字符串字段伪装成 array/object。

验证命令：

```bash
./gradlew --no-daemon -Pkotlin.incremental=false :app:testDebugUnitTest --tests com.bytedance.zgx.pocketmind.safety.SafetyPolicyTest --tests com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteModeOnlyExposesPublicEvidenceToolsToRemoteRuntime --tests com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remotePublicEvidenceToolCallBatchRetriesOnlyRetryableFailures --tests com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remotePublicEvidenceToolCallBatchExecutesAndContinuesWithModel --tests com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remotePublicEvidenceToolCallBatchExecutorFailureIsObservedAsToolFailure --tests com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.terminalRunRejectsLateExternalOutcomeConfirmation --tests com.bytedance.zgx.pocketmind.AndroidManifestTest --tests com.bytedance.zgx.pocketmind.AgentRuntimePermissionPolicyTest --tests com.bytedance.zgx.pocketmind.device.ForegroundAppProviderTest --tests com.bytedance.zgx.pocketmind.tool.ToolRegistryTest --tests com.bytedance.zgx.pocketmind.tool.ToolSchemaContractTest --tests com.bytedance.zgx.pocketmind.tool.DeviceContextToolExecutorTest --tests com.bytedance.zgx.pocketmind.tool.RoutingAndValidatingToolExecutorTest --tests com.bytedance.zgx.pocketmind.audit.ToolAuditRepositoryTest --tests com.bytedance.zgx.pocketmind.orchestration.AgentTraceStoreTest.roomStoreDoesNotPersistWebSearchObservationSummaryOrResultData
./gradlew --no-daemon -Pkotlin.incremental=false :app:testDebugUnitTest --tests com.bytedance.zgx.pocketmind.tool.ToolSchemaContractTest
scripts/test_validation_scripts.sh
bash -n scripts/live_remote_emulator.sh scripts/test_validation_scripts.sh
git diff --check
KEY_PREFIX='s''k' FORBIDDEN_REMOTE_MARKERS='e715''d561|deep''seek|api\.deep''seek' rg -n "\b${KEY_PREFIX}-[A-Za-z0-9_-]{16,}\b|${FORBIDDEN_REMOTE_MARKERS}" . --glob '!app/build/**' --glob '!build/**' --glob '!*.iml'
./gradlew --no-daemon -Pkotlin.incremental=false :app:testDebugUnitTest
./gradlew --no-daemon -Pkotlin.incremental=false :app:compileDebugAndroidTestKotlin
```

结果：

- 通过：targeted JVM safety/runtime/device/tool/audit/trace 回归。
- 通过：`scripts/test_validation_scripts.sh` 和 shell syntax check。
- 通过：`git diff --check`。
- 通过：严格 API-key / DeepSeek 配置扫描无命中；仅源码中的任务状态前缀曾触发
  宽松 `sk-` 模式误报，严格 key pattern 无命中。
- 通过：完整 `:app:testDebugUnitTest`，834 tests completed。
- 通过：`:app:compileDebugAndroidTestKotlin`。
- 未执行：模拟器/真机回归；完整设备回归仍以
  `scripts/regression_emulator.sh` 生成的 `regression-emulator.properties`
  `status=passed` 为准。

## 2026-06-04 Device permission boundary 增量验证

本轮覆盖项：

- AndroidManifest 显式声明 Android 14+
  `READ_MEDIA_VISUAL_USER_SELECTED`，权限策略在 API 34+ 对最近图片、
  截图、视频和 OCR 建模 selected visual media，与完整图片/视频权限互为可接受授权结果。
- `AndroidRecentFileProvider` 和最近图片 OCR provider 允许
  `READ_MEDIA_VISUAL_USER_SELECTED` 只查询用户选择的视觉媒体，并在工具结果中输出
  `mediaAccessScope`，避免把部分照片访问误写成完整相册访问。
- `query_recent_files` schema 不再暴露 `documents`、`downloads`、`others`
  作为可直接执行 kind；Android 13+ 非媒体文件必须通过系统文件选择器或分享入口由用户主动提供。
- `query_foreground_app` 成功结果输出 `source=usage_stats_estimate` 和
  `confidence=estimate`，文档说明该结果是 UsageStats 估计，不是窗口管理器真值或屏幕内容。

验证命令：

```bash
./gradlew :app:testDebugUnitTest --tests 'com.bytedance.zgx.pocketmind.AndroidManifestTest' --tests 'com.bytedance.zgx.pocketmind.AgentRuntimePermissionPolicyTest' --tests 'com.bytedance.zgx.pocketmind.device.ForegroundAppProviderTest' --tests 'com.bytedance.zgx.pocketmind.device.RecentFileCollectorTest' --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest' --tests 'com.bytedance.zgx.pocketmind.tool.ToolSchemaContractTest' --tests 'com.bytedance.zgx.pocketmind.tool.DeviceContextToolExecutorTest' --tests 'com.bytedance.zgx.pocketmind.tool.RoutingAndValidatingToolExecutorTest'
./gradlew :app:testDebugUnitTest
```

结果：

- 通过：targeted JVM permission/provider/schema/tool executor 回归。
- 通过：完整 `:app:testDebugUnitTest`，831 tests completed。
- 未执行：模拟器/真机回归；本轮只覆盖 JVM 权限边界和工具契约。

## 2026-06-03 Final code and documentation audit

本轮覆盖项：

- 审计当前 `codex/agent-core-progress` 工作树中的远程 `tool_calls` 批量解析、
  public evidence eligibility、Agent run batch observe、ViewModel 并发执行、
  `web_search` 公开证据回模型综合、文档和 live remote 配置边界。
- 根据并行审计结果修复提交前问题：天气地点解析不再全局删除可能属于地名的
  `和/同/高/低/冷/热` 等字符；本地证据 continuation 优先于 observation replanner；
  batch 工具执行异常转成失败结果进入 Agent observation；batch `Cancelled` 结果映射为
  Agent `Cancel`；streaming 混合 `tool_calls` / legacy `function_call` fail closed。
- 确认远程模型 endpoint/model/key 不作为源码、脚本默认值或文档推荐配置存在；
  live remote 验证只能通过 `POCKETMIND_LIVE_REMOTE_*` 显式临时注入，报告只写来源变量，
  并在脚本退出时清空 debug App 中保存的远程配置。
- 整理提交前证据：旧 stash 属于早期 `feat/agent-loop-second-cycle-query-recent-files`
  分支的 device/action/tool 改动，未纳入本轮已验证提交。

验证命令：

```bash
git status --short
git fetch origin --prune
git stash list --date=local
git stash show --stat stash@{0}
scripts/verify_local.sh
bash -n scripts/*.sh
scripts/test_validation_scripts.sh
./gradlew :app:testDebugUnitTest --tests 'com.bytedance.zgx.pocketmind.docs.AgentCoreDocumentationTest'
./gradlew :app:testDebugUnitTest --tests 'com.bytedance.zgx.pocketmind.tool.WebSearchProviderTest' --tests 'com.bytedance.zgx.pocketmind.runtime.RemoteChatRuntimeTest' --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.publicEvidenceToolBatchCancelledResultCancelsRun' --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.localEvidenceContinuationTakesPriorityOverObservationReplanner' --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remotePublicEvidenceToolCallBatchExecutorFailureIsObservedAsToolFailure'
rg -n "\\bsk-[A-Za-z0-9_-]{20,}\\b" . -g '!**/build/**' -g '!**/.gradle/**' -g '!**/.git/**'
git diff --check
```

结果：

- 通过：`scripts/verify_local.sh` 返回 `Local verification passed`，覆盖
  validation script tests、`:app:testDebugUnitTest`、`:app:lintDebug`、
  `:app:assembleDebug`、`:app:assembleDebugAndroidTest` 和 `:app:assembleRelease`。
- 通过：脚本语法、validation scripts、Agent core documentation JVM test 和
  `git diff --check`。
- 通过：定向回归覆盖 Web search 地名解析、RemoteChatRuntime streaming mixed format
  rejection、AgentLoopRuntime batch cancellation / local evidence priority、ViewModel
  batch executor failure observation。
- 通过：严格扫描未发现用户 API key、provider-specific endpoint/model 或
  provider-specific key 环境变量别名残留在源码、脚本、README、docs 或非 build 产物中。
- 通过：已删除本地 ignored `build/verification` 历史 live remote artifact，避免工作区残留
  provider-specific UI dump 或 properties。
- 远程分支状态：`origin` 当前没有 `codex/agent-core-progress` 分支，提交后需要
  `git push --set-upstream origin codex/agent-core-progress`。
- 未执行完整模拟器回归或真机 instrumentation；不能据此更新
  `regression-emulator.properties status=passed` 结论。

## 2026-06-03 Documentation sync and physical release install

本轮覆盖项：

- 全面同步 README、隐私说明、发布清单、发布就绪、真机验收和历史多 Agent
  技术方案，明确远程 `tool_calls` 的当前行为：单个公开只读 evidence 工具可执行，
  多个公开只读 evidence 工具可并发执行，混入私密读取、外部动作或副作用工具时整批拒绝。
- 移除 live remote emulator 脚本中的 provider-specific 默认 endpoint/model/key；
  真实远程模型验证必须通过 `POCKETMIND_LIVE_REMOTE_BASE_URL`、
  `POCKETMIND_LIVE_REMOTE_MODEL` 和 `POCKETMIND_LIVE_REMOTE_API_KEY` 显式临时注入。
- 记录最新 release 包的 ad hoc 本地签名、覆盖安装和启动 smoke。该安装只用于内部真机
  检查，不代表正式分发签名，也不替代完整真机 instrumentation 或模拟器回归。

验证命令：

```bash
./gradlew :app:assembleRelease
BUILD_TOOLS=/Users/bytedance/Library/Android/sdk/build-tools/36.0.0
"$BUILD_TOOLS/zipalign" -p -f 4 app/build/outputs/apk/release/app-release-unsigned.apk app/build/outputs/apk/release/app-release-local-aligned-20260603-0025.apk
"$BUILD_TOOLS/apksigner" sign --ks "$HOME/.android/debug.keystore" --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out app/build/outputs/apk/release/app-release-local-signed-20260603-0025.apk app/build/outputs/apk/release/app-release-local-aligned-20260603-0025.apk
"$BUILD_TOOLS/apksigner" verify --verbose app/build/outputs/apk/release/app-release-local-signed-20260603-0025.apk
/Users/bytedance/Library/Android/sdk/platform-tools/adb -s fb6272c install -r app/build/outputs/apk/release/app-release-local-signed-20260603-0025.apk
/Users/bytedance/Library/Android/sdk/platform-tools/adb -s fb6272c shell dumpsys package com.bytedance.zgx.pocketmind | rg 'versionCode|versionName|lastUpdateTime|firstInstallTime|Package \['
/Users/bytedance/Library/Android/sdk/platform-tools/adb -s fb6272c shell cmd package resolve-activity --brief com.bytedance.zgx.pocketmind
/Users/bytedance/Library/Android/sdk/platform-tools/adb -s fb6272c shell am start -n com.bytedance.zgx.pocketmind/.MainActivity
/Users/bytedance/Library/Android/sdk/platform-tools/adb -s fb6272c shell pidof -s com.bytedance.zgx.pocketmind
shasum -a 256 app/build/outputs/apk/release/app-release-local-signed-20260603-0025.apk
bash -n scripts/*.sh
rg -n "POCKETMIND_LIVE_REMOTE|\\bsk-[A-Za-z0-9_-]{16,}\\b" app/src/main scripts docs README.md || true
./gradlew :app:testDebugUnitTest --tests 'com.bytedance.zgx.pocketmind.docs.AgentCoreDocumentationTest'
git diff --check
```

结果：

- 通过：release assemble 成功；`apksigner verify --verbose` 显示 `Verifies`，
  v3 签名通过，1 个 signer。
- 通过：真机 `fb6272c` 覆盖安装返回 `Success`，未卸载 App、未清理 App 数据。
- 通过：设备包信息确认 `versionCode=1`、`versionName=0.1.0`、
  `lastUpdateTime=2026-06-03 00:25:28`；activity 解析为
  `com.bytedance.zgx.pocketmind/.MainActivity`；启动后进程 PID 为 `14570`。
- APK：`app/build/outputs/apk/release/app-release-local-signed-20260603-0025.apk`，
  SHA-256 `cefffdfe4c9bd424e3fd8075262b2ac71af3f69183d33b78129c6231f221c951`。
- 通过：脚本语法检查无输出；远程配置扫描显示 App 源码没有真实 API key，
  live 脚本只保留空默认的 `POCKETMIND_LIVE_REMOTE_*` 读取入口；历史 live
  验证记录不展开具体 provider endpoint/model，只记录为用户临时环境变量事实。
- 通过：文档合同测试 `AgentCoreDocumentationTest` 和 `git diff --check`。
- 未执行完整真机 instrumentation 或完整模拟器回归；不能据此更新
  `regression-emulator.properties status=passed` 结论。安装后复查时设备已从
  `adb devices -l` 消失，因此后续设备属性未追加记录。

## 2026-06-02 Public evidence tool-call batch execution

本轮覆盖项：

- 为远程模型一次返回多个 OpenAI-style `tool_calls` 补合同：Remote runtime 应产出
  `RemoteChatEvent.ToolCalls`，而不是把多个工具调用当成 `ParseError`。
- 为批量工具执行边界补 Tool Registry 合同：只有
  `PublicEvidence` / `LowReadOnly` / `NotRequired` / 无 `privateOutputKeys` /
  无设备或副作用 permission 的工具可进入 public evidence 并发批次。
- 为 Agent loop 补合同：全批 public evidence 工具调用应生成
  `PlanToolBatch`，混入任意非 eligible 工具应全批拒绝，成功结果应聚合后回模型综合。
- 为 ViewModel 补端到端 JVM 回归：远程模型返回两个 `web_search` tool call
  时，UI 并发执行两个公开只读工具、批量观察结果，然后再次调用模型生成综合答案。

验证命令：

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest --tests 'com.bytedance.zgx.pocketmind.runtime.RemoteChatRuntimeTest' --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest' --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest'
./gradlew :app:testDebugUnitTest --tests 'com.bytedance.zgx.pocketmind.runtime.RemoteChatRuntimeTest' --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest' --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest'
./gradlew :app:testDebugUnitTest --tests 'com.bytedance.zgx.pocketmind.docs.AgentCoreDocumentationTest'
./gradlew :app:testDebugUnitTest
./gradlew :app:compileDebugAndroidTestKotlin
scripts/doctor.sh
bash -n scripts/*.sh
scripts/verify_local.sh
git diff --check
```

结果：

- 通过：`:app:compileDebugKotlin`、批量工具定向 JVM 回归、完整
  `:app:testDebugUnitTest`、`:app:compileDebugAndroidTestKotlin`、文档合同测试和
  `git diff --check`。
- 通过：`scripts/doctor.sh`、`bash -n scripts/*.sh`、`scripts/verify_local.sh`；
  `verify_local.sh` 完成 debug/release 构建、lint、JVM 测试和 AndroidTest 编译，
  输出 `Local verification passed.`。
- 未执行模拟器或真机回归；不能据此更新 `regression-emulator.properties status=passed` 结论。

## 2026-06-02 Tool evidence continuation and weather facts

本轮覆盖项：

- 新增 `ToolResultContinuationPolicy`：公开证据工具结果可回到模型综合或继续公开只读工具调用；本地证据工具结果只允许本地模型综合；动作/跳转/草稿工具默认不续写。
- `web_search` 仍只返回公开事实证据，不打开浏览器；天气多地点 query 返回有界 `weather_current` facts，比较、计算和多步补查由 Agent runtime/model 负责。
- 本地证据结果仍保持 `LocalOnly` / `requiresLocalModel=true` 边界，trace/audit 中继续使用 redacted result。

验证命令：

```bash
./gradlew :app:testDebugUnitTest --tests 'com.bytedance.zgx.pocketmind.tool.WebSearchProviderTest' --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest' --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest'
./gradlew :app:testDebugUnitTest --tests 'com.bytedance.zgx.pocketmind.docs.AgentCoreDocumentationTest' --tests 'com.bytedance.zgx.pocketmind.tool.WebSearchProviderTest' --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest'
./gradlew :app:testDebugUnitTest
./gradlew :app:compileDebugAndroidTestKotlin
git diff --check
```

结果：

- 通过：定向 provider / registry / Agent loop / docs JVM 回归、完整
  `:app:testDebugUnitTest`、`:app:compileDebugAndroidTestKotlin` 和
  `git diff --check`。
- 未执行完整模拟器或真机回归；不能据此更新 `regression-emulator.properties status=passed` 结论。

## 2026-06-02 Live remote emulator check

本轮覆盖项：

- 使用 debug-only ADB receiver 在 `focus_agent_api36_arm64` 模拟器中写入用户提供的
  OpenAI-compatible endpoint/model。该 endpoint/model 由临时环境变量注入，只是一次 live
  验证输入；不是源码默认值、文档推荐配置或发布配置。
- 修复 `scripts/live_remote_emulator.sh` 的 ADB 文本输入和发送流程：空格使用
  `%s` 编码，输入后先收起键盘再点击发送，并将默认等待时间提升到 45 秒。
- 调整默认 live 提示词，使提示词本身不包含预期 token；通过条件仍要求远程助手
  回复 `POCKETMIND_LIVE_OK`，避免把用户输入误判为成功。
- 验证密钥只经由临时环境变量注入，artifact 和仓库不记录密钥值。

验证命令：

```bash
bash -n scripts/live_remote_emulator.sh
POCKETMIND_LIVE_REMOTE_API_KEY=<hidden> \
POCKETMIND_LIVE_REMOTE_BASE_URL=<user-provided-openai-compatible-base-url> \
POCKETMIND_LIVE_REMOTE_MODEL=<user-provided-model> \
POCKETMIND_LIVE_REMOTE_WAIT_SECONDS=60 \
ARTIFACT_DIR=build/verification/live-remote-20260602-193827 \
ANDROID_SERIAL=emulator-5554 \
scripts/live_remote_emulator.sh
rg -l --hidden --glob '!.git/**' --glob '!build/**' 'sk-[A-Za-z0-9]{20,}' . || true
rg -l --hidden 'sk-[A-Za-z0-9]{20,}' build/verification/live-remote-20260602-193827 || true
```

结果：

- 通过：`build/verification/live-remote-20260602-193827/live-remote-emulator.properties`
  记录 `status=passed`、用户临时注入的 base URL / model 来源变量、
  `expected_text=POCKETMIND_LIVE_OK`。后续脚本版本已改为 redacted actual
  endpoint/model，只保留 `base_url_source` / `model_source`。
- UI 证据：`build/verification/live-remote-20260602-193827/live-remote-result.png`
  和同名 XML 显示用户临时注入的远程模型处于已就绪状态、`远程可用`，并显示远程助手回复
  `POCKETMIND_LIVE_OK`。
- 密钥边界：仓库和本次 artifact 的 OpenAI-style key 模式扫描均无命中；report 仅记录
  `api_key_source=POCKETMIND_LIVE_REMOTE_API_KEY`。后续脚本版本退出时会清空 debug App
  保存的远程配置。

## 2026-06-02 Emulator remote model and Agent capability walkthrough

本轮覆盖项：

- 在已启动的 `focus_agent_api36_arm64` 模拟器上安装并打开 debug APK，手工确认首屏、
  模型管理、远程模型配置入口和远程就绪状态。
- 用模拟器 instrumentation 复跑远程 OpenAI-compatible mock 链路：
  远程普通回答、远程请求体、远程工具调用、工具确认卡、本地记忆隔离、会话切换、
  模型管理控件和动作草稿。
- 补跑 Skill/多模态入口、长期记忆、运行时权限和特殊访问确认卡 UI 测试。
- 未执行真实远程 live 调用：本轮没有可用的安全环境变量或一次性密钥输入入口；
  按本计划约束，不使用也不落盘聊天中出现过的 API key。
- 补充 `scripts/live_remote_emulator.sh` 作为真实远程模型模拟器验收入口：debug-only
  receiver 只存在于 debug APK，脚本不内置 provider endpoint/model/key；必须从
  `POCKETMIND_LIVE_REMOTE_BASE_URL`、`POCKETMIND_LIVE_REMOTE_MODEL` 和
  `POCKETMIND_LIVE_REMOTE_API_KEY` 显式读取配置，artifact 只记录来源变量名并 redacts
  actual endpoint/model/key。

验证命令：

```bash
./gradlew :app:assembleDebug
./gradlew :app:compileDebugAndroidTestKotlin
bash -n scripts/doctor.sh scripts/verify_local.sh scripts/install_and_test_device.sh scripts/verify_emulator.sh scripts/regression_emulator.sh scripts/live_remote_emulator.sh scripts/test_validation_scripts.sh
scripts/test_validation_scripts.sh
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 shell am instrument -w -e class com.bytedance.zgx.pocketmind.MainActivityComprehensiveTest com.bytedance.zgx.pocketmind.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5554 shell am instrument -w -e class com.bytedance.zgx.pocketmind.MainActivitySkillUiTest com.bytedance.zgx.pocketmind.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5554 shell am instrument -w -e class com.bytedance.zgx.pocketmind.MainActivityLongTermMemoryUiTest com.bytedance.zgx.pocketmind.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5554 shell am instrument -w -e class com.bytedance.zgx.pocketmind.MainActivityRuntimePermissionUiTest com.bytedance.zgx.pocketmind.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5554 shell am instrument -w -e class com.bytedance.zgx.pocketmind.MainActivitySpecialAccessUiTest com.bytedance.zgx.pocketmind.test/androidx.test.runner.AndroidJUnitRunner
```

结果：

- 通过：`build/verification/manual-live-20260602-remote/05-mainactivity-comprehensive-instrumentation.txt`
  记录 `OK (2 tests)`。
- 通过：`build/verification/manual-live-20260602-remote/08-mainactivity-skill-ui-instrumentation.txt`
  记录 `OK (4 tests)`。
- 通过：`build/verification/manual-live-20260602-remote/09-mainactivity-memory-ui-instrumentation.txt`
  记录 `OK (1 test)`。
- 通过：`build/verification/manual-live-20260602-remote/10-mainactivity-runtime-permission-ui-instrumentation.txt`
  记录 `OK (1 test)`。
- 通过：`build/verification/manual-live-20260602-remote/11-mainactivity-special-access-ui-instrumentation.txt`
  记录 `OK (1 test)`。
- UI 证据：`build/verification/manual-live-20260602-remote/07-relaunch-after-comprehensive-tests.png`
  和同名 XML 显示 `mock-model · 远程 · 已就绪`、`远程可用`，并保留远程工具调用后的
  `Web 搜索` 动作草稿。
- 真实远程 live 仍未执行：当前环境没有完整的
  `POCKETMIND_LIVE_REMOTE_BASE_URL`、`POCKETMIND_LIVE_REMOTE_MODEL` 和
  `POCKETMIND_LIVE_REMOTE_API_KEY` 配置，因此未生成
  `live-remote-emulator.properties status=passed`。
- 无 key 预检通过失败路径验证：
  `build/verification/live-remote-no-key-20260602/live-remote-emulator.properties`
  记录 `status=failed`、`api_key_source=`，没有记录密钥值。

## 2026-06-02 Emulator full functional walkthrough

本轮覆盖项：

- 在现有复杂模拟器 UI 回归基础上，补充当前屏幕截图 OCR 的确认卡级
  AndroidTest：明确当前屏幕 OCR 请求必须进入 `capture_current_screenshot_ocr`
  确认卡，显示一次性 MediaProjection 摘要，取消后进入审计/Agent trace
  取消链路。
- 完整模拟器回归覆盖远程对话、会话切换、本地记忆写入/读取保护、工具确认、
  Skill-first 多步确认入口、当前屏幕 Accessibility 文本确认、当前屏幕截图 OCR
  确认、分享入口、权限/特殊访问提示、外部结果确认、后台任务入口和数据库迁移。

验证命令：

```bash
./gradlew :app:compileDebugAndroidTestKotlin
./gradlew :app:testDebugUnitTest --tests 'com.bytedance.zgx.pocketmind.docs.AgentCoreDocumentationTest'
AVD_NAME=focus_agent_api36_arm64 EMULATOR_ARGS='-no-window -no-audio -no-snapshot-save -no-boot-anim' EMULATOR_SELECT_TIMEOUT_SECONDS=120 BOOT_TIMEOUT_SECONDS=300 scripts/regression_emulator.sh
```

结果：

- 通过：AndroidTest Kotlin 编译和 `AgentCoreDocumentationTest`。
- 完整模拟器回归通过：
  `build/verification/regression-emulator-20260602-182339/regression-emulator.properties`
  记录 `status=passed`、`source_android_test_count=26`、
  `expected_android_test_count=26`、`actual_android_test_count=26`、
  `serial=emulator-5554`、`api_level=36`、`abi=arm64-v8a`、
  `avd=focus_agent_api36_arm64`；嵌套
  `emulator-verification.properties` 和 `device-verification.properties`
  均记录 `status=passed`，device report 记录 `instrumentation=passed`。
- `build/verification/regression-emulator-20260602-182339/instrumentation.txt`
  非空，包含 `currentScreenshotOcrSkillShowsOneShotMediaProjectionConfirmation`
  和 `OK (26 tests)`。

## 2026-06-02 Agent core contract wave implementation

本轮覆盖项：

- Tool Registry 私密输出成功结果新增 `privacy=LocalOnly` /
  `requiresLocalModel=true` 强制合同，私密工具的 schema-invalid 成功结果也保留
  LocalOnly 失败元数据。
- 新增 `DeviceContextToolReadiness`、当前屏幕 Accessibility 结构摘要、
  `MemoryRecordType.UserFact`、`SkillDefinition` / `SkillCatalog`、
  `AgentContinuationCursorV2`、`BackgroundSkillSpec`、以及
  `capture_current_screenshot_ocr` ToolSpec / Skill / LocalOnly 合同。
- 当前屏幕截图 OCR 已接入 Android ActivityResult MediaProjection 前台同意：
  parser 只接受明确 OCR/text extraction 意图，bare 当前屏幕截图请求不会触发读屏确认；
  consent token 只在 Activity -> provider 内存通道中一次性消费，不进入
  `ToolRequest`、trace、audit 或 pending confirmation；输出 schema 包含
  `truncated`、`ocrTextIncluded` 和 LocalOnly OCR 摘录。
- 多 Agent 只读复核发现的文档引用、parser 过宽、consent id 漂移、InvalidResult
  LocalOnly 元数据缺口均已修复。

验证命令：

```bash
./gradlew :app:testDebugUnitTest --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest' --tests 'com.bytedance.zgx.pocketmind.tool.ToolSchemaContractTest' --tests 'com.bytedance.zgx.pocketmind.tool.RoutingAndValidatingToolExecutorTest' --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest' --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest' --tests 'com.bytedance.zgx.pocketmind.AgentRuntimePermissionPolicyTest' --tests 'com.bytedance.zgx.pocketmind.device.DeviceContextModelsTest' --tests 'com.bytedance.zgx.pocketmind.multimodal.CurrentScreenshotOcrContractTest' --tests 'com.bytedance.zgx.pocketmind.background.BackgroundSkillSpecTest' --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentContinuationCursorV2Test'
./gradlew :app:testDebugUnitTest --tests 'com.bytedance.zgx.pocketmind.docs.AgentCoreDocumentationTest'
./gradlew :app:testDebugUnitTest
./gradlew :app:compileDebugAndroidTestKotlin
bash -n scripts/*.sh
git diff --check
scripts/test_validation_scripts.sh
scripts/doctor.sh
scripts/verify_local.sh
AVD_NAME=focus_agent_api36_arm64 EMULATOR_ARGS='-no-window -no-audio -no-snapshot-save -no-boot-anim' EMULATOR_SELECT_TIMEOUT_SECONDS=120 BOOT_TIMEOUT_SECONDS=300 scripts/regression_emulator.sh
```

结果：

- 通过：targeted Agent core JVM contract、`AgentCoreDocumentationTest`、完整
  `:app:testDebugUnitTest`、`:app:compileDebugAndroidTestKotlin`、shell 语法、
  `git diff --check`、validation script tests、doctor、`scripts/verify_local.sh`
  均通过。
- 完整模拟器回归通过：
  `build/verification/regression-emulator-20260602-161148/regression-emulator.properties`
  记录 `status=passed`、`source_android_test_count=25`、
  `expected_android_test_count=25`、`actual_android_test_count=25`、
  `serial=emulator-5554`、`api_level=36`、`abi=arm64-v8a`、
  `avd=focus_agent_api36_arm64`；嵌套
  `emulator-verification.properties` 和 `device-verification.properties`
  也记录 `status=passed`，device report 记录 `instrumentation=passed`；
  `build/verification/regression-emulator-20260602-161148/instrumentation.txt`
  非空，记录 25 个 AndroidTest。

## 2026-06-02 Skill binding contract validation

本轮覆盖项：

- `SkillPlan.validateStructure()` 改为复用当前 `ToolRegistry` 的 input/output schema：
  binding source 必须是严格 `stepId.outputKey`，且 output key 必须来自前序 Tool/Model
  contract；binding target 必须是目标工具 schema 声明的参数。
- Skill 声明阶段新增 fail-closed 校验：duplicate tool request id、unknown required tool、
  skill risk 低于 required tool risk、literal/bound 参数冲突、request/draft 参数漂移都会在
  用户确认前失败。
- Built-in 单工具 Skill 的 step id 改为稳定工具名，不再使用随机 request id；request id 仍只作为
  本次运行的确认/恢复句柄。
- 私密 tool output 直绑后续 tool 参数现在在 plan validation 阶段拒绝，不再等用户确认读取后才失败。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests com.bytedance.zgx.pocketmind.skill.SkillRunProgressorTest \
  --tests com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest \
  --tests com.bytedance.zgx.pocketmind.skill.SkillRunExecutorTest
```

结果：

- 通过：内置 Skill step contract 稳定；source/target binding schema 错误、duplicate request id、
  私密输出直绑均在执行前 fail closed；SkillRunProgressor、SkillRunExecutor 和 built-in
  manifest/plan contract 测试通过。

## 2026-06-02 External outcome startup repair

本轮覆盖项：

- `RoomAgentTraceStore.failStaleInFlightRuns()` 新增
  `AwaitingExternalOutcome` 启动修复：只有 trace 同时包含匹配的
  `ToolRequested` summary 和未确认 launch-only `ToolObserved` summary 时，才保留
  等待外部结果确认状态。
- 如果外部结果 trace 缺少对应 tool request、缺少 launch-only observation、或 metadata
  已损坏到无法恢复 pending outcome sheet，启动修复会追加 `Failed` step 并把 run 标记为
  `Failed`，避免不可见悬挂。
- 修复只依赖 allowlisted completion metadata，不恢复原始参数、外发 payload 或完整 prompt。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests com.bytedance.zgx.pocketmind.orchestration.AgentTraceStoreTest.roomStoreKeepsRestorablePendingExternalOutcomeOnStartupRepair \
  --tests com.bytedance.zgx.pocketmind.orchestration.AgentTraceStoreTest.roomStoreFailsUnrestorablePendingExternalOutcomeOnStartupRepair \
  --tests com.bytedance.zgx.pocketmind.orchestration.AgentTraceStoreTest.roomStoreFailsAwaitingExternalOutcomeWhenToolRequestedJsonMissingToolNameOnStartupRepair \
  --tests com.bytedance.zgx.pocketmind.orchestration.AgentTraceStoreTest.roomStoreFailsAwaitingExternalOutcomeWhenToolObservedMetadataIsCorruptOnStartupRepair \
  --tests com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.failStaleInFlightRunsClosesUnrestorableExternalOutcomeBeforeRestore
```

结果：

- 通过：可恢复 external outcome run 在启动修复中保留；缺失/损坏 trace、半损坏
  `ToolRequested`、损坏 completion metadata 的 `AwaitingExternalOutcome` run fail closed；
  runtime 启动修复后不再恢复不可恢复 external outcome sheet。

## 2026-06-02 External outcome continuation cursor

本轮覆盖项：

- Launch-only 外部 Activity 进入 `AwaitingExternalOutcome` 时，如果当前 run 已有无参数、
  非 model-planned、单工具 continuation cursor，会额外写入 redacted
  `ContinuationCursorRecorded` trace step；不恢复 raw `nextActionInput` 或完整 run input。
- App 重启后，用户在恢复的 external outcome sheet 中选择 `Completed`，Agent loop 可以从
  trace 恢复该 cursor，并在预算、Tool Registry、SafetyPolicy、trace/audit 边界全部重跑后
  进入下一张用户确认卡。
- `NotCompleted` / `OpenedOnly` 不继续规划；payload-bearing tail 没有 cursor 时继续
  fail closed。
- Store 边界新增 fail-closed 校验：带 request/draft payload 的 cursor 不落库；被污染的
  nested SkillPlan raw input cursor 在恢复时删除 pending 并 fail run。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.restoredExternalOutcomeUsesContinuationCursorForNoPayloadTailAfterCompletion \
  --tests com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.restoredExternalOutcomeDoesNotContinuePayloadTailWithoutContinuationCursor \
  --tests com.bytedance.zgx.pocketmind.PocketMindViewModelTest.restoredPendingExternalOutcomeCompletedCanShowNextPendingConfirmation \
  --tests com.bytedance.zgx.pocketmind.orchestration.AgentTraceStoreTest.roomStoreRestoresContinuationCursorFromTraceAfterPendingConfirmationClears \
  --tests com.bytedance.zgx.pocketmind.orchestration.AgentTraceStoreTest.roomStoreDoesNotPersistContinuationCursorWithExecutablePayload \
  --tests com.bytedance.zgx.pocketmind.orchestration.AgentTraceStoreTest.roomStoreFailsClosedWhenContinuationCursorSkillPlanContainsRawInput
```

结果：

- 通过：external outcome 跨重启 no-payload cursor happy path、payload tail fail-closed、
  UI 下一步确认卡切换、trace cursor 恢复、payload cursor 不落库、污染 SkillPlan
  cursor fail-closed。

## 2026-06-02 Structured sequence continuation cursor

本轮覆盖项：

- `pending_agent_confirmations` 新增 nullable `continuationCursorJson`，数据库版本升到
  11；`MIGRATION_10_11` 不从旧 `nextActionInput` 回填，并清空旧 raw tail。
- `PendingToolConfirmationSnapshot` 新增 `AgentContinuationCursor`。Room 仍把
  `nextActionInput` 写为 `null`，只持久化无参数、非 model-planned、单工具 tail 的
  redacted cursor。
- 恢复 pending confirmation 后，用户重新确认当前工具并观察成功时，Agent loop 可以用
  cursor 规划下一张确认卡；规划仍重跑预算、Tool Registry、SafetyPolicy、trace/audit。
- payload-bearing tail 不生成 cursor；composite Skill tail、model-planned tail 仍保持
  未恢复，等待更完整 value-free cursor。外部 outcome 已打开后的 no-payload cursor
  恢复由后续条目覆盖。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.restoredSequentialPendingUsesContinuationCursorForNoPayloadTailAfterObservation \
  --tests com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.payloadSequentialTailDoesNotPersistContinuationCursor

./gradlew :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin && git diff --check
```

结果：

- 通过：结构化 cursor 单测、全量 debug JVM 单测、AndroidTest Kotlin 编译和
  `git diff --check`。

## 2026-06-02 Awaiting external outcome state

本轮覆盖项：

- `AgentRunState` 新增 `AwaitingExternalOutcome`，launch-only 外部 Activity
  观察不再把 run 标记为 `Completed`。
- `latestPendingExternalOutcome()` 同时兼容新的
  `AwaitingExternalOutcome` 和旧的 launch-only `Completed` trace。
- Agent trace UI 状态文案新增“待确认外部结果”。
- `AgentCoreDocumentationTest` 现在校验 `docs/agent_core_modules.md`
  中的 `Class.method` 测试引用真实存在，防止核心模块文档覆盖漂移。

验证命令：

```bash
./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin \
  :app:testDebugUnitTest \
  --tests com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.unverifiedExternalLaunchDoesNotAutoPlanNextTool \
  --tests com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.completedExternalOutcomeConfirmationCanPlanNextTool \
  --tests com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.restoredUnverifiedExternalLaunchRestoresPendingOutcomeAndRecordsConfirmation \
  --tests com.bytedance.zgx.pocketmind.PocketMindViewModelTest.unverifiedExternalLaunchShowsLaunchOnlyStatus \
  --tests com.bytedance.zgx.pocketmind.PocketMindViewModelTest.restoreStartupStateRestoresPendingExternalOutcomeWithoutExecutingTool \
  --tests com.bytedance.zgx.pocketmind.docs.AgentCoreDocumentationTest

./gradlew :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin && git diff --check
```

结果：

- 通过：launch-only 状态、外部结果确认、跨重启恢复、ViewModel UI
  回归、核心模块文档契约、全量 debug JVM 单测和 AndroidTest Kotlin 编译。

## 2026-06-02 External Activity outcome restore

本轮覆盖项：

- `AgentLoopRuntime.latestPendingExternalOutcome()` 可从 active session 的 allowlisted
  Agent trace metadata 恢复 launch-only 外部结果待确认状态。
- `recordExternalOutcome()` 在重启后的 Room trace summary 场景中可恢复最小
  `ToolRequest` 与 unverified `ToolResult`，记录 `ExternalOutcomeConfirmed`；
  恢复路径不读取原始工具参数、外发文本、URL query 或 raw payload。
- `PocketMindViewModel.restoreStartupState()` 会在普通 pending action 之后恢复
  `pendingExternalOutcome`；有待确认外部结果时，新消息会被阻塞，直到用户明确记录
  outcome。

验证命令：

```bash
./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin \
  :app:testDebugUnitTest \
  --tests com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.restoredUnverifiedExternalLaunchRestoresPendingOutcomeAndRecordsConfirmation \
  --tests com.bytedance.zgx.pocketmind.PocketMindViewModelTest.restoreStartupStateRestoresPendingExternalOutcomeWithoutExecutingTool
```

结果：

- 通过：定向 runtime/ViewModel restored external outcome 回归。

## 2026-06-02 External Activity outcome confirmation

本轮覆盖项：

- 外部 Activity/share sheet/draft 工具成功打开后继续输出 launch-only metadata：
  `completionVerified=false`、`externalOutcome=Unknown`、
  `externalOutcomeSource=Unknown`，不声称目标 App 内操作已完成。
- UI 新增外部结果确认 sheet，要求用户选择“已完成 / 未完成 / 只是打开了”。
  ViewModel 通过 `AssistantRouter.recordExternalOutcome()` 写回确认结果。
- Agent loop 新增 `AgentStep.ExternalOutcomeConfirmed` 和
  `ToolAuditEventType.ExternalOutcomeConfirmed`；确认结果只持久化 allowlisted
  completion metadata，不写 raw URI/text/query。
- Tool schema 将 `externalOutcome` 与 `externalOutcomeSource` 改为枚举，并在
  `ToolRegistry.validateResult()` 中校验跨字段 invariant。只有
  `Completed + UserConfirmed` 会设置 `completionVerified=true` 并允许继续规划下一步；
  `NotCompleted` / `OpenedOnly` 只记录结果。

验证命令：

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:compileDebugAndroidTestKotlin
```

结果：

- 通过：全量 Debug JVM unit tests。
- 通过：Debug AndroidTest Kotlin 编译，覆盖新增 external outcome Compose test。
- 通过：`git diff --check` whitespace 检查。

## 2026-06-02 Agent loop hard budget and run cancellation

本轮覆盖项：

- `AgentLoopRuntime` 新增 run-level `cancelRun(runId, reason)`：生成中 run 可直接进入
  `Cancelled`，迟到模型输出会被状态机拒绝；待确认工具 run 复用现有
  `cancelToolRequest` 路径，留下 `UserRejected -> ToolObserved(Cancelled) ->
  ObservationDecided(Cancel)`，并且不执行工具。
- Agent loop 增加全局工具 step budget 和 observation-decision budget；预算耗尽时在
  保存下一张 pending confirmation、自动 retry、观察后 replan 或模型 continuation 前
  fail closed。失败 trace 使用固定通用原因，不写入 prompt、模型输出或私密工具数据。
- `AssistantRouter` / `AssistantOrchestrator` 暴露 run-level cancel；ViewModel 的
  “停止生成”现在会停止本地/远程 runtime、取消当前 active Agent run，并刷新最近
  Agent trace，而不是只取消 coroutine。
- `AgentTraceStore` 增加按 run 清 pending helper；Room-backed store 会立即删除
  `pending_agent_confirmations` 和对应 skill checkpoint，避免终态 run 在重启后被懒清理或
  二次 fail。
- README、Agent core 文档和真机验收清单同步记录该能力边界。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.cancelGeneratingRunMarksCancelledAndIgnoresLateModelOutput' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.cancelRunAwaitingConfirmationClearsPendingWithoutExecutingTool' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.runBudgetExceededFailsBeforeNextToolConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentTraceStoreTest.roomStoreClearPendingConfirmationsForRunDeletesPersistedPendingAndCheckpoint' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.stopGenerationCancelsActiveAgentRunForLocalChat'
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.docs.AgentCoreDocumentationTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.cancelGeneratingRunMarksCancelledAndIgnoresLateModelOutput' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.cancelRunAwaitingConfirmationClearsPendingWithoutExecutingTool' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.runBudgetExceededFailsBeforeNextToolConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentTraceStoreTest.roomStoreClearPendingConfirmationsForRunDeletesPersistedPendingAndCheckpoint' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.stopGenerationCancelsActiveAgentRunForLocalChat'
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentTraceStoreTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest'
./gradlew testDebugUnitTest :app:compileDebugAndroidTestKotlin
git diff --check
```

结果：

- 通过：定向 Agent loop cancellation / hard budget、Room pending cleanup、ViewModel
  stop-generation run cancel 回归。
- 通过：Agent core documentation、AgentLoopRuntime / AgentTraceStore /
  PocketMindViewModel 完整测试类、完整 JVM 单测、AndroidTest Kotlin 编译和
  diff whitespace 检查。

## 2026-06-02 Emulator regression artifact gate

本轮覆盖项：

- 新增 `scripts/regression_emulator.sh` 作为完整模拟器回归上层入口；默认写入
  `build/verification/regression-emulator-*/regression-emulator.properties`。
- 该入口强制 `CLEAN_DEVICE=1` 调用 `scripts/verify_emulator.sh`，再校验
  `emulator-verification.properties` 与嵌套 `device-verification.properties`
  均为 `status=passed`，且 device report 的 `instrumentation=passed`。
- 自动扫描当前 `app/src/androidTest` 下的 `@Test` 数量并写入
  `source_android_test_count`；当前源码基线为 23。`EXPECTED_ANDROID_TEST_COUNT`
  只能上调不能下调，runner report 的 `instrumentation_test_count` 必须为数字且
  不小于该基线，避免少跑测试却误记为完整回归。
- device helper 现在把 instrumentation runner 输出持久化为 `instrumentation.txt`；
  regression failed report 会在 nested emulator/device 失败时尽量回填 serial、
  API、ABI、actual test count 和 instrumentation artifact，便于复盘真实失败。
- `scripts/test_validation_scripts.sh` 新增 fake SDK 覆盖：成功路径按当前源码基线
  动态生成、调用方 `CLEAN_DEVICE=0` 仍强制清机、低于源码基线失败、高于源码基线
  expected count override、低于源码基线 override 前置失败、`@Test()` /
  `@Test(timeout=...)` / `@org.junit.Test` 计数、非法 expected count 前置失败、
  缺失 instrumentation count 失败、emulator helper preflight 失败时仍写
  regression failed report，以及 nested device instrumentation 失败时 regression
  report 回填证据。
- `MainActivitySkillUiTest` 的后台任务断言改为同一 audit row / 同一 trace run 的
  组合证据，避免多条历史/取消记录让 `onNodeWithText(..., substring=true)` 因
  非唯一节点误失败，同时避免全局任意匹配掩盖真实错误。
- README、真机验收、Agent core regression strategy、release checklist 和 release
  readiness 同步改为优先引用 `regression-emulator.properties` 作为完整模拟器回归证据。

验证命令：

```bash
bash -n scripts/doctor.sh scripts/verify_local.sh scripts/install_and_test_device.sh scripts/verify_emulator.sh scripts/regression_emulator.sh scripts/test_validation_scripts.sh
scripts/test_validation_scripts.sh
./gradlew :app:testDebugUnitTest --tests 'com.bytedance.zgx.pocketmind.docs.AgentCoreDocumentationTest'
./gradlew :app:compileDebugAndroidTestKotlin
AVD_NAME=focus_agent_api36_arm64 EMULATOR_ARGS='-no-window -no-audio -no-snapshot-save -no-boot-anim' EMULATOR_SELECT_TIMEOUT_SECONDS=120 BOOT_TIMEOUT_SECONDS=300 scripts/regression_emulator.sh
```

结果：

- 通过：shell syntax 检查。
- 通过：fake SDK validation script 回归。
- 通过：Agent core documentation unit test。
- 通过：AndroidTest Kotlin 编译。
- 通过：真实模拟器 `focus_agent_api36_arm64` / `emulator-5554`，API 36，
  ABI `arm64-v8a`，`CLEAN_DEVICE=1`。runner 报告 `OK (23 tests)`；
  `build/verification/regression-emulator-20260602-112828/regression-emulator.properties`
  记录 `status=passed`、`source_android_test_count=23`、
  `expected_android_test_count=23`、
  `actual_android_test_count=23`，并链接同目录的
  `emulator-verification.properties`、`device-verification.properties` 和
  `instrumentation.txt`。

## 2026-06-02 Screenshot OCR output schema boundary

本轮覆盖项：

- `read_recent_screenshot_ocr` 不再复用最多 3 张图片 OCR 的输出 schema；
  输入 schema、执行器配置和输出 schema 现在都锁定为最多 1 张截图。
- 新增横向 `ToolSchemaContractTest`，对所有工具扫描输入/输出中同名数值字段，
  要求输出合同的 `minimum` / `maximum` 不能比输入合同更宽。
- 新增截图 OCR 专项 result validation 回归：`maxCount=1` 的成功输出有效，
  `maxCount=2` 会被 `ToolRegistry.validateResult` 作为 `InvalidResult` 拒绝。
- 该切片只收紧工具合同，不改变实际 OCR provider、权限、确认、LocalOnly、
  trace/audit redaction 或 remote-mode 保护边界。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolSchemaContractTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest'
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.docs.AgentCoreDocumentationTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolSchemaContractTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.DeviceContextToolExecutorTest'
./gradlew testDebugUnitTest :app:compileDebugAndroidTestKotlin
git diff --check
```

结果：

- 通过：定向 Tool schema / registry 合同回归。
- 通过：定向 docs/tool/device-context 回归。
- 通过：完整 JVM 单测与 AndroidTest Kotlin 编译。
- 通过：diff whitespace 检查。

## 2026-06-02 Background task query Agent tool

本轮覆盖项：

- 新增 confirmed `query_background_tasks` 工具，输入为闭合 schema：
  `scope=active|history|policy|all` 与 bounded `maxCount`。该工具只读本地后台任务
  store 和周期检查策略，不调度、不取消、不启停周期检查，也不请求通知或 Android
  runtime permission。
- `RoutingToolExecutor` 在 side-effecting `ActionExecutor` 前分发该工具，并只调用
  `BackgroundTaskScheduler.scheduledTasks/recentTasks/periodicCheckPolicy`。生产 wiring
  传入同一个 `AndroidBackgroundTaskScheduler`，scheduler 缺失时返回 `LocalOnly`
  retryable failure。
- 成功结果带 `privacy=LocalOnly`、`requiresLocalModel=true`、
  `metadataPolicy=background_tasks_local_only_no_reminder_body` 和
  `rawPayloadIncluded=false`；`tasksJson` 只含 task id/type/status/title/timestamps，
  不返回 reminder body、prompt、text 或 periodic `lastRunSummary` 原文。
- 新增 `background_tasks_context_skill` 与 conservative parser，接受明确查看后台任务、
  提醒列表、任务历史、周期检查状态/策略请求；拒绝开启/关闭/取消、API、实现、文档、
  测试和解释类输入。
- Trace / Skill private-output policy 将 `activeTaskCount/historyTaskCount/tasksJson/policyJson`
  标记为私密输出，Agent observation 和 trace 中只保留红acted summary。

验证命令：

```bash
./gradlew testDebugUnitTest \
  --tests com.bytedance.zgx.pocketmind.docs.AgentCoreDocumentationTest \
  --tests com.bytedance.zgx.pocketmind.tool.DeviceContextToolExecutorTest \
  --tests com.bytedance.zgx.pocketmind.tool.ToolRegistryTest \
  --tests com.bytedance.zgx.pocketmind.action.ActionPlannerTest \
  --tests com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest \
  --tests com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest \
  --tests com.bytedance.zgx.pocketmind.AgentRuntimePermissionPolicyTest \
  --tests com.bytedance.zgx.pocketmind.orchestration.AgentTraceStoreTest
./gradlew testDebugUnitTest :app:compileDebugAndroidTestKotlin
git diff --check
```

结果：

- 通过：定向 docs/tool/registry/planner/skill/agent-loop/permission/trace 回归。
- 通过：完整 JVM 单测与 AndroidTest Kotlin 编译。
- 通过：diff whitespace 检查。

## 2026-06-02 Public ToolStep recovery projection 增量验证

本轮覆盖项：

- `RoomAgentTraceStore` 的 pending `SkillPlan` 持久化从“按工具 allowlist 保留
  step 参数”改为“保留参数 key 形状、全部 value redacted”。这样 value-free
  `SkillRunCheckpoint` 可以继续校验已完成步骤的 output frontier，但不会把
  `schedule_reminder` title/body/delayMinutes、原始 Skill input 或其他内容型
  payload 写入 Room。
- 当前待确认工具的 pending row 仍只保存 `ToolSpec.pendingArgumentAllowlist`
  允许的可执行参数；`cancel_reminder.taskId` 这类低语义结构化 id 可恢复，
  `share_text.text`、搜索 query、提醒 title/body、深链 URI、模型输出和私密读取结果
  仍 fail closed。
- `AgentLoopRuntimeTest.restoredToolStepOutputBoundPendingContinuesAfterRestart`
  覆盖 `schedule_reminder -> cancel_reminder` 的公开结构化输出恢复：第二张
  `cancel_reminder(taskId)` 确认卡跨重启后仍可恢复，重新确认后才能执行并完成 run。
- `AgentTraceStoreTest.roomStoreKeepsRedactedSkillPlanKeyShapeForPublicToolStepRecovery`
  覆盖 redacted `SkillPlan` 保留已完成步骤参数 key shape 但不保存私密值；
  `roomStoreFailsScheduleReminderPendingWithoutPersistingReminderPayload` 覆盖
  初始 `schedule_reminder` payload 待确认跨重启 fail closed。
- 该切片不实现完整 `SkillRunContinuation` 持久化、任意 Skill continuation、
  model-output 外发 payload 恢复或完整 argument-bearing typed step rehydration。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.restoredToolStepOutputBoundPendingContinuesAfterRestart' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentTraceStoreTest.roomStoreKeepsRedactedSkillPlanKeyShapeForPublicToolStepRecovery' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentTraceStoreTest.roomStoreFailsScheduleReminderPendingWithoutPersistingReminderPayload' \
  --no-daemon
./gradlew :app:testDebugUnitTest \
  --tests com.bytedance.zgx.pocketmind.orchestration.AgentTraceStoreTest \
  --tests com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest \
  --tests com.bytedance.zgx.pocketmind.docs.AgentCoreDocumentationTest \
  --no-daemon
./gradlew :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin --no-daemon
git diff --check
```

结果：

- 通过：targeted public ToolStep recovery projection / payload fail-closed 回归。
- 通过：`AgentTraceStoreTest`、`AgentLoopRuntimeTest` 和
  `AgentCoreDocumentationTest`。
- 通过：完整 JVM 单测与 AndroidTest Kotlin 编译。
- 通过：diff whitespace 检查。

## 2026-06-02 Device report test count and screen-summary Skill smoke 增量验证

本轮覆盖项：

- `scripts/install_and_test_device.sh` 从 instrumentation 输出中解析测试总数，并在
  `device-verification.properties` 写入 `instrumentation_test_count`；支持
  `INSTRUMENTATION_STATUS: numtests=N`、`OK (N tests)` 和
  `Tests run: N` 三类常见 runner 输出。
- fake SDK 回归同步锁定成功设备、失败 instrumentation 和 nested emulator device
  report 的 `instrumentation_test_count`，避免 release/验收记录只写
  `instrumentation=passed` 却没有实际测试数量证据。
- `MainActivitySkillUiTest` 新增
  `currentScreenTextSummaryShareSkillStartsAtScreenTextConfirmation`：远程模式下发送
  “总结当前屏幕文字并分享”时，UI 必须先展示 `读取当前屏幕文本` 确认卡和
  `special_access_requirements`，不得展示 `runtime_permission_requirements` 或第二步
  `分享屏幕摘要`。
- 取消第一步后，测试继续从“后台任务”入口断言最近审计日志包含
  `UserCancelled` / `read_current_screen_text` / “工具执行已取消。”，并断言最近
  Agent 轨迹进入 `已取消` 且包含 `UserRejected`。
- README、真机验收清单、Agent core 文档和 release checklist 同步要求验收记录引用
  verification report 中的 `instrumentation_test_count`。

验证命令：

```bash
bash -n scripts/doctor.sh scripts/verify_local.sh scripts/install_and_test_device.sh scripts/verify_emulator.sh scripts/test_validation_scripts.sh
scripts/test_validation_scripts.sh
./gradlew :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest --tests com.bytedance.zgx.pocketmind.docs.AgentCoreDocumentationTest --no-daemon
./gradlew :app:testDebugUnitTest --no-daemon
git diff --check
```

结果：

- 通过：shell 语法检查。
- 通过：fake SDK device/emulator validation script 回归，覆盖成功、preflight 失败和
  instrumentation 失败 report 断言。
- 通过：AndroidTest Kotlin 编译，包含新增 `MainActivitySkillUiTest` smoke。
- 通过：`AgentCoreDocumentationTest` 文档覆盖单测。
- 通过：完整 JVM 单测。
- 通过：diff whitespace 检查。
- 未执行真机/模拟器 instrumentation：`adb devices -l` 当前没有列出已授权设备或模拟器。

## 2026-06-02 LocalOnly private-output non-success contract 增量验证

本轮覆盖项：

- Tool Registry 对声明 `privateOutputKeys` 的工具新增非成功结果清理：`Failed`、
  `Rejected`、`Cancelled` 不套用 success output schema，但会从 allowlist 重建
  data，丢弃声明的私密输出 key 和未知 debug/raw 键，并强制补齐 `toolName`、
  `privacy=LocalOnly`、`requiresLocalModel=true`。
- `ValidatingToolExecutor` 现在会拦截 delegate 返回的敏感非成功结果，防止
  clipboard raw text 等私密字段跟随失败/拒绝/取消结果进入 trace、audit 或模型
  后续路由；执行侧 summary/error message 也会替换为固定安全文案。
- Tool Registry 自己产生的敏感 invalid-request rejection 也走同一清理路径；
  因此参数校验失败不会只保留 `toolName` 而丢失 LocalOnly 元数据。
- 该切片不改变 successful `ToolResult.data` schema 合同，也不声明完成全局 taint
  propagation；私密 success payload 的跨步骤边界仍由 `privateOutputKeys`、
  local-model continuation 和 Skill public-output fence 分层保护。

验证命令：

```bash
./gradlew testDebugUnitTest \
  --tests com.bytedance.zgx.pocketmind.tool.ToolRegistryTest \
  --tests com.bytedance.zgx.pocketmind.tool.RoutingAndValidatingToolExecutorTest
```

结果：targeted Tool Registry / Validating executor 非成功隐私合同回归通过。
完整 JVM 单测与 AndroidTest Kotlin 编译通过：

```bash
./gradlew testDebugUnitTest compileDebugAndroidTestKotlin
```

## 2026-06-02 Skill ToolStep-to-ToolStep Agent loop 增量验证

本轮覆盖项：

- `SkillRunProgressor` 新增 current-process `ToolStep -> ToolStep` progression：
  schema-valid 的成功工具结果可以把公开输出绑定到同一声明式 Skill 的下一个
  dependent `ToolStep`，不需要插入模型步骤。
- Agent loop 在普通 observation replanner 之前优先尝试当前 `SkillPlan` 的下一个
  ToolStep；绑定出的工具仍重新经过 Tool Registry validation、`SafetyPolicy`、
  trace/audit 和用户确认，不会直接执行。
- Progressor fail-closed 边界：非成功结果不续跑；`result.requestId` 必须属于已请求
  Skill step；后续 ToolStep 依赖未满足时拒绝；缺失 binding 拒绝；私密 tool output
  不能直接绑定到任何后续工具参数。
- Agent loop 回归覆盖 schedule-reminder `ToolStep` 结果绑定到
  `cancel_reminder(taskId)` 后进入第二张确认卡，并在第二次确认观察成功后完成 run。
- Agent loop 负例覆盖 `query_contacts.contactsJson -> share_text.text` 直接私密绑定：
  run 进入 `Failed`，不生成 share pending confirmation，不调用 observation
  replanner，trace/audit 不含联系人原始电话。
- 该切片不实现跨重启的任意 Skill continuation，也不改变完整 typed step
  rehydration；这些仍需要独立切片。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests com.bytedance.zgx.pocketmind.skill.SkillRunProgressorTest \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.toolStepOutputBindsToDependentToolStepInCurrentProcessAndRequestsConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.toolStepToToolStepBindingCannotDirectlyExposePrivateToolOutputToShare' \
  --no-daemon
```

结果：targeted Skill progression / Agent loop ToolStep-to-ToolStep 回归通过；
完整 JVM 单测与 AndroidTest Kotlin 编译通过。

## 2026-06-02 Reminder recovery receiver and rollback schema 增量验证

本轮覆盖项：

- `ReminderBootReceiver` 从只处理 `BOOT_COMPLETED` 扩展为处理
  `BOOT_COMPLETED` 与 `MY_PACKAGE_REPLACED`，应用包更新后即使用户未立即打开
  PocketMind，也能复用现有 `ReminderRescheduler` 重排仍处于 `Scheduled` 的本地
  reminder alarm。
- `AndroidManifestTest` 锁住 reminder recovery receiver 的两个系统恢复 action，
  防止后续改动退回到只覆盖开机恢复。
- `schedule_reminder` successful output schema 现在要求
  `taskStatus=Scheduled`、`recoveryToolName=cancel_reminder` 和安全 `task-*`
  `taskId/recoveryTaskId`；`cancel_reminder` successful output schema 现在要求
  `taskStatus=Cancelled` 和安全 `task-*` `taskId`。
- `ToolRegistryTest` 明确覆盖不安全 rollback metadata：错误 recovery tool、
  token-like recovery task id、错误 task status 都会在 result validation 阶段变成
  non-retryable `InvalidResult`，不会进入 Agent observation 的撤销提示。
- 该切片不改变任意多步 Skill UI 编排、敏感读取非成功结果统一契约、或多模态
  AndroidTest 覆盖；这些仍是后续独立切片。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests com.bytedance.zgx.pocketmind.tool.ToolRegistryTest \
  --tests com.bytedance.zgx.pocketmind.tool.ToolSchemaContractTest \
  --tests com.bytedance.zgx.pocketmind.AndroidManifestTest \
  --no-daemon
```

结果：targeted Tool Registry / schema contract / manifest contract 通过；
完整 JVM 单测与 AndroidTest Kotlin 编译通过。

## 2026-06-02 Tool output schema contract and task-memory suppression 增量验证

本轮覆盖项：

- `ToolSchemaContractTest` 现在从每个注册工具的 `outputSchemaJson` 推导最小
  successful `ToolResult.data`，并验证 schema validation 会接受最小合法输出、
  拒绝缺失 required key、拒绝 undeclared key、拒绝 schema-invalid value。
- 这把“工具执行结果回传给模型前必须满足输出合同”的测试从单个剪贴板样本扩展到
  所有 registry 工具，覆盖 #1 工具层和 #10 工具测试体系。
- 修复 singleton `periodic-check-local` 的长期记忆 suppression：用户清空/遗忘
  活跃 task-state 后，普通 refresh/chat 不会复活；但周期检查先关闭、再成功
  重新开启时会释放 suppression，让新的 `TaskState` 记忆重新出现。
- 该切片不改变语义记忆 runtime 接入状态；生产仍只有已注入 runtime 时才会进入
  semantic recall。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests com.bytedance.zgx.pocketmind.tool.ToolSchemaContractTest \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.reenabledPeriodicCheckUnsuppressesNewTaskStateMemory' \
  --no-daemon
```

结果：targeted 工具输出 schema 合同和周期检查 task-state suppression 回归通过。
完整 JVM 单测与 AndroidTest Kotlin 编译通过。

## 2026-06-02 LocalOnly device private output coverage 增量验证

本轮覆盖项：

- `query_calendar_availability`、`query_recent_notifications`、
  `query_recent_files` 现在声明私密输出字段和脱敏 summary，避免日历忙闲、
  通知标题/时间和最近文件 metadata 绕过 trace redaction / Skill public-output
  边界。
- `query_contacts` 的 `contactCount`、`query_foreground_app` 的
  `lastTimeUsedMillis`、`read_current_screen_text` 的 `capturedAtMillis`、
  `nodeCount`、`packageName` 同步纳入 private output policy。
- Tool Registry 合同测试要求所有 LocalOnly 设备上下文输出 schema 同时声明
  `privacy=LocalOnly` 和 `requiresLocalModel`，并要求 private output key 仍存在
  于 output schema。
- Routing / validating executor 回归覆盖 calendar、foreground app、contacts、
  notifications、recent files、OCR、current screen text 的真实结果仍保留私密
  key，且 continuation 必须留在本地模型边界。
- Observation replanner 回归同步验证新增的前台应用私密 metadata 不会进入模型
  prompt 的 public data key 列表。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests com.bytedance.zgx.pocketmind.tool.ToolRegistryTest \
  --tests com.bytedance.zgx.pocketmind.tool.RoutingAndValidatingToolExecutorTest \
  --tests com.bytedance.zgx.pocketmind.tool.DeviceContextToolExecutorTest \
  --tests com.bytedance.zgx.pocketmind.tool.CalendarAvailabilityToolExecutorTest \
  --tests com.bytedance.zgx.pocketmind.docs.AgentCoreDocumentationTest \
  --no-daemon

./gradlew :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin --no-daemon
```

结果：targeted registry / routing / device-context / documentation 回归通过；
完整 JVM 单测与 AndroidTest Kotlin 编译通过。

## 2026-06-02 Explicit preference forget command 增量验证

本轮覆盖项：

- `explicitUserPreferenceForgetFrom` 支持 `忘记：...` / `forget that ...`
  等中英文显式偏好删除命令，并归一化 `用户偏好：` 前缀后计算同一个
  deterministic preference id。
- `MemoryRepository.rebuild` 会跳过显式 remember / forget 控制命令；用户删除
  偏好后，历史里的控制命令不会把已删除偏好重新索引回来。
- `PocketMindViewModel.sendMessage` 在模型尚未准备或远程模式下也会本地处理
  forget 命令，只写入 `LocalOnly` 控制/status 消息，绕过 chat/action router 与
  remote runtime。
- 后续远程问题不会携带 remember/forget 控制消息、已删除偏好文本或本地状态消息。
- 该切片不改变 LiteRT embedding 边界：生产仍未接入真正 embedding runtime；
  verified memory asset 仍只有 runtime `Active` 时才代表语义召回。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests com.bytedance.zgx.pocketmind.memory.MemoryRepositoryTest \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.forgetPreferenceCommandDeletesMemoryAndBypassesRouterAndRemoteRuntime' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteForgetPreferenceCommandDoesNotEnterLaterRemoteHistory' \
  --no-daemon
```

结果：targeted memory / ViewModel forget-command 回归通过；完整 JVM 单测、
AndroidTest Kotlin 编译、diff whitespace 检查和敏感 diff 扫描通过。

## 2026-06-02 Model observation replanner 增量验证

本轮覆盖项：

- `ModelObservationReplanner` 只在 successful observation 后调用已验证的本地
  mobile action model，并且只接受 `usedModel = true` 的 supported `Draft`。
- Observation prompt 只包含红action 后的 intent preview、工具名、参数 key、
  状态、红action summary 和 data key 名；不包含原始 `ToolResult.data` 值。
- 下一步工具仍经过 Tool Registry、SafetyPolicy、trace/audit 和用户确认；模型
  draft 不会因来自 observe 阶段而自动执行。
- 每个 run 默认最多接受一次 model-backed observation replan；第二个工具成功后
  不会继续无界调用模型。
- 生产 wiring 复用同一个 `ToolRegistry`，无已验证动作模型时模型 replanner
  让位给原有显式顺序 replanner。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest' \
  --no-daemon
```

结果：`AgentLoopRuntimeTest` 单类回归通过；完整 JVM 单测、AndroidTest Kotlin
编译、diff whitespace 检查和敏感 diff 扫描通过。

## 2026-06-02 Clipboard LocalOnly metadata 增量验证

本轮覆盖项：

- `read_clipboard` 成功结果 schema 现在要求 `privacy=LocalOnly` 与
  `requiresLocalModel=true`，缺失这两个 value-free metadata 的成功结果会在
  Tool Registry / Agent observe / Skill resume 边界失败。
- `ActionExecutor.readClipboard()` 的成功和空剪贴板失败结果都写入同一组
  LocalOnly metadata，避免剪贴板读取观察在后续链路被误当成远程可上传内容。
- Agent / Skill 测试夹具同步使用新的 clipboard success contract；Skill
  checkpoint 仍只记录 value-free 的 known output keys，不把所有 schema metadata
  key 当成可恢复执行状态。
- README、核心模块文档和隐私说明同步记录 clipboard 结果 metadata 边界。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.RoutingAndValidatingToolExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AssistantOrchestratorTest' \
  --tests 'com.bytedance.zgx.pocketmind.skill.SkillRunExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentTraceStoreTest'
```

结果：targeted clipboard / registry / Agent / Skill 回归测试通过；完整 JVM 单测、
AndroidTest Kotlin 编译、diff whitespace 检查和敏感 diff 扫描通过。

## 2026-06-02 Agent core documentation contract 增量验证

本轮覆盖项：

- `docs/agent_core_modules.md` 的十个核心目标区域必须保持稳定的顶层模块顺序：
  Tool Layer、Agent Loop、Skill Framework、Device Context、Execution Boundary、
  Safety And Audit、Memory、Background Tasks、Multimodal Inputs、Regression Strategy。
- 除 Regression Strategy 外，每个核心模块都必须记录 code ownership、
  responsibilities、current status 和至少一个测试条目。
- 文档中列出的测试类必须在 `app/src/test` 或 `app/src/androidTest` 下存在，
  避免核心模块文档引用已经删除或未落地的测试文件。
- Regression Strategy 必须继续记录本地 JVM 验证、模拟器回归入口和该文档契约测试。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.docs.AgentCoreDocumentationTest'
```

结果：通过。新增文档契约测试和完整 JVM 单测通过；diff whitespace 检查与敏感
diff 扫描无命中。

## 2026-06-02 Skill pending 恢复 hardening 增量验证

本轮覆盖项：

- 带 redacted `SkillPlan` 的 pending 恢复必须同时存在同 run/request 的
  value-free `SkillRunCheckpoint`；缺失、pending step/tool 不一致、manifest
  不匹配、checkpoint JSON 无效或 value-free 内容漂移都 fail closed，并清理
  pending/checkpoint 恢复行。
- Plain pending 不附加 `SkillPlan` 时仍按普通 pending 边界恢复；保存 plain
  pending 会清掉同 run/request 的 stale checkpoint，避免旧 checkpoint 污染后续
  恢复。
- `pending_agent_confirmations` 与 `agent_skill_run_checkpoints` 的保存和删除通过
  Room transaction helper 成对处理，避免恢复时看到半更新状态。
- `SkillRunCheckpoint` 校验 completed step 的 value-free output key 集合必须与
  当前 `SkillPlan`/`ToolRegistry` 精确一致；缺失 model output key 或多出 tool
  output key 都拒绝恢复。
- Startup repair 复用同一恢复判定，不能把缺 checkpoint 的 Skill pending 留成可确认
  状态。

应跑验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentTraceStoreTest.roomStoreFailsSkillPendingWithoutCheckpoint' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentTraceStoreTest.roomStoreFailsSkillPendingWithoutCheckpointOnStartupRepair' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentTraceStoreTest.roomStoreFailsCheckpointWhenCompletedOutputKeysChange' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentTraceStoreTest.roomStoreClearsStaleCheckpointWhenSavingPlainPendingConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.skill.SkillRunExecutorTest.valueFreeCheckpointRejectsChangedOutputKeysForCompletedStep'
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentTraceStoreTest' \
  --tests 'com.bytedance.zgx.pocketmind.skill.SkillRunExecutorTest'
./gradlew :app:testDebugUnitTest
./gradlew :app:compileDebugAndroidTestKotlin
git diff --check
git diff --unified=0 | rg -n '(sk-[A-Za-z0-9_-]{20,}|B[e]arer [A-Za-z0-9._-]{20,}|(?i)(api[_-]?key|s[e]cret|p[a]ssword|d[e]epseek))' || true
ANDROID_HOME=/Users/bytedance/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/bytedance/Library/Android/sdk \
scripts/verify_local.sh
```

结果：通过。定向 `AgentTraceStoreTest` / `SkillRunExecutorTest` 回归、完整 JVM
单测、AndroidTest Kotlin 编译、diff whitespace 检查、敏感 diff 扫描和本地完整
验证脚本通过。

## 2026-06-02 PDF scanned-page OCR fallback 增量验证

本轮覆盖项：

- 共享输入的 `application/pdf` 附件继续优先读取有界 PDF 文本层；只有文本层
  摘录为空时，才会在本地用 Android `PdfRenderer` 渲染前几页并复用 ML Kit OCR
  生成 `PdfImageOcr` 摘录。
- PDF OCR fallback 有页数、渲染尺寸和 4000 字符上限；输出只进入
  shared-input prompt，不包含 PDF URI/path、原始像素、坐标、图片标签或语义描述。
- `SharedInput` prompt 只允许 `PdfImageOcr` 出现在 `Document + application/pdf`
  附件上；伪造到图片、旧 Office 或其他 MIME 上的 PDF OCR preview 不会展示。
- 远程模式仍在 reader 边界使用 protected signal，不读取分享文本、附件 metadata、
  PDF 文件流、文本层或 PDF OCR 内容。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.multimodal.SharedInputTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteModeRejectsSharedPdfImageOcrPreviewBeforeBuildingPrompt' \
  --no-daemon
./gradlew :app:testDebugUnitTest --no-daemon
./gradlew :app:compileDebugAndroidTestKotlin --no-daemon
git diff --check
git diff --unified=0 | rg -n '(sk-[A-Za-z0-9_-]{20,}|B[e]arer [A-Za-z0-9._-]{20,}|(?i)(api[_-]?key|s[e]cret|p[a]ssword|d[e]epseek))' || true
scripts/verify_local.sh
```

结果：targeted shared-input PDF OCR fallback、全量 JVM 单测、AndroidTest Kotlin
编译、diff whitespace 检查、敏感 diff 扫描和本地完整验证脚本通过。

## 2026-06-02 多模态与审计隐私边界增量验证

本轮覆盖项：

- 文本类共享附件摘录现在使用严格 UTF-8 解码。伪装成 `text/*` 或
  JSON/XML/YAML 的 malformed UTF-8 二进制内容不会被替换字符吞掉后进入 prompt；
  只有字节上限截断造成的尾部不完整 UTF-8 字符会被安全丢弃。
- `ToolAuditSummaryRedactor` 现在覆盖 JSON 风格带引号 credential 赋值；同一
  redactor 也保护 Agent trace 摘要/JSON 预览。
- 最近截图/图片 OCR 的 `name`、`mimeType`、`sizeBytes`、
  `lastModifiedMillis` 与 `ocrText` 一起纳入 private output policy，避免
  OCR 文件元数据落入 trace、audit 或 Skill public outputs。

验证命令：

```bash
./gradlew :app:testDebugUnitTest --tests 'com.bytedance.zgx.pocketmind.multimodal.SharedInputTest'
./gradlew :app:testDebugUnitTest --tests 'com.bytedance.zgx.pocketmind.audit.ToolAuditEventTest' --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentTraceStoreTest.roomStoreRedactsSensitiveTraceTextAcrossSummariesAndJson' --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest.privateToolOutputsAreDeclaredByToolPolicy' --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest.privateDeviceOutputKeysRemainDeclaredInOutputSchemas' --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.recentScreenshotOcrObservationBuildsLocalPromptAndRedactsTrace' --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.recentImageOcrObservationBuildsLocalPromptAndRedactsTrace'
```

结果：targeted JVM 隐私边界测试通过。

## 2026-06-02 远程 tool_calls 解析失败闭环增量验证

本轮覆盖项：

- 新增 `PocketMindViewModelTest.malformedRemoteToolCallFailsClosedBeforeConfirmationOrExecution`。
- 新增 `PocketMindViewModelTest.clipboardSummaryShareLocalContinuationFailureFailsAgentRunWithoutSecondConfirmation`。
- 扩展 `PocketMindViewModelTest.remoteModeProtectsCurrentScreenTextBeforeRemoteContinuation`。
- 新增 `AgentLoopRuntimeTest.modelGenerationFailureMarksGeneratingRunFailedAndIgnoresLateOutput`。
- 新增 `AgentLoopRuntimeTest.modelGenerationFailureIsNoOpAfterRunLeavesGeneratingState`。
- 远程 OpenAI-compatible runtime 返回 `RemoteChatEvent.ParseError` 时，ViewModel
  fail closed：不创建 `pendingConfirmation`、不调用 Agent model-tool observation、
  不执行 Android 工具，并将状态置为 `远程生成失败`。
- 模型生成/解析失败现在会定点调用 `failModelGeneration(runId, reason)`，只把当前
  `GeneratingAnswer` run 记录为 `Failed`，立即刷新 Agent trace；迟到模型输出不能再
  推进这个 run。
- 工具观察后的本地模型续写失败也会关闭同一个 run，不会生成第二个分享确认卡。
- 远程模式下，工具结果需要本地模型续写时会先保护本地内容并关闭当前 run，不会把
  私有文本发送给 remote，也不会留下 `GeneratingAnswer` 等待下次启动修复。
- 测试同时断言远程请求仍携带 tool specs，但错误消息不会伪装成动作草稿，也不会进入
  确认/执行边界。

验证命令：

```bash
./gradlew :app:testDebugUnitTest --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.modelGenerationFailureMarksGeneratingRunFailedAndIgnoresLateOutput'
./gradlew :app:testDebugUnitTest --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.modelGenerationFailureIsNoOpAfterRunLeavesGeneratingState'
./gradlew :app:testDebugUnitTest --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.malformedRemoteToolCallFailsClosedBeforeConfirmationOrExecution'
./gradlew :app:testDebugUnitTest --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.clipboardSummaryShareLocalContinuationFailureFailsAgentRunWithoutSecondConfirmation'
./gradlew :app:testDebugUnitTest --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteModeProtectsCurrentScreenTextBeforeRemoteContinuation'
./gradlew :app:testDebugUnitTest --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest'
./gradlew :app:testDebugUnitTest
ANDROID_HOME=/Users/bytedance/Library/Android/sdk ANDROID_SDK_ROOT=/Users/bytedance/Library/Android/sdk scripts/verify_local.sh
git diff --check
git diff --unified=0 | rg -n '(sk-[A-Za-z0-9_-]{20,}|B[e]arer [A-Za-z0-9._-]{20,}|(?i)(api[_-]?key|s[e]cret|p[a]ssword|d[e]epseek))' || true
```

结果：通过；敏感串扫描无命中。

## 2026-06-02 远程 tool_calls 只读工具 UI 增量验证

本轮覆盖项：

- `MainActivityComprehensiveTest` 新增远程 OpenAI-compatible `tool_calls`
  instrumentation 覆盖：真实 `MainActivity` 切到 mock remote backend，远程 SSE
  返回 `web_search` function tool call。
- 测试断言远程请求体携带 `tools`、`tool_choice=auto`、`web_search` schema 和
  `stream=true`，覆盖 `sendWithTools` 请求边界。
- UI 侧断言远程 `web_search` 工具调用进入只读工具执行态，显示
  `正在使用工具：Web 搜索`，不展示确认卡，也不会调起外部浏览器或系统搜索。

验证命令：

```bash
./gradlew :app:compileDebugAndroidTestKotlin
./gradlew :app:testDebugUnitTest
ANDROID_HOME=/Users/bytedance/Library/Android/sdk ANDROID_SDK_ROOT=/Users/bytedance/Library/Android/sdk scripts/verify_local.sh
ANDROID_HOME=/Users/bytedance/Library/Android/sdk ANDROID_SDK_ROOT=/Users/bytedance/Library/Android/sdk AVD_NAME=focus_agent_api36_arm64 EMULATOR_SELECT_TIMEOUT_SECONDS=120 BOOT_TIMEOUT_SECONDS=300 EMULATOR_ARGS="-no-window -no-audio -no-snapshot-save" CLEAN_DEVICE=1 scripts/verify_emulator.sh
git diff --check
git diff --unified=0 | rg -n '(sk-[A-Za-z0-9_-]{20,}|B[e]arer [A-Za-z0-9._-]{20,}|(?i)(api[_-]?key|s[e]cret|p[a]ssword|d[e]epseek))' || true
```

结果：通过；敏感串扫描无命中。首次 emulator 全量回归暴露 3 个 UI 测试稳定性问题：
远程 `tool_calls` 测试等待聊天消息而非确认卡、运行时权限/特殊授权确认卡测试在
非滚动父布局上调用 `performScrollTo()`。修复后复跑
`focus_agent_api36_arm64`，设备 `emulator-5554`，API 36，ABI `arm64-v8a`，
instrumentation `OK (20 tests)`，脚本输出 `Emulator verification passed`。

## 2026-06-02 系统特殊授权确认卡 UI 增量验证

本轮覆盖项：

- 新增 `MainActivitySpecialAccessUiTest`，通过真实 `MainActivity` 输入
  `总结当前屏幕文字，最多1200字`，覆盖 `read_current_screen_text` 动作草稿到
  Compose 确认卡的 Activity/UI 路径。
- 确认卡现在有 instrumentation 覆盖：显示 `special_access_requirements`
  区块、无障碍屏幕文本权限 rationale，以及
  `open_special_access_accessibility_screen_text` 系统设置入口按钮。
- 测试明确断言当前屏幕文本读取不展示 `runtime_permission_requirements`，避免系统
  特殊授权和 Android runtime permission UI 边界混淆。
- 测试只点击取消，不点击“打开系统设置”或“确认执行”，因此不会跳出 App、不读取
  当前屏幕 Accessibility 文本，也不依赖设备是否已开启该服务。

验证命令：

```bash
./gradlew :app:compileDebugAndroidTestKotlin
```

结果：通过。

## 2026-06-02 运行时权限确认卡 UI 增量验证

本轮覆盖项：

- 新增 `MainActivityRuntimePermissionUiTest`，通过真实 `MainActivity` 输入
  `查联系人 Alice`，覆盖联系人查询动作草稿到 Compose 确认卡的 Activity/UI 路径。
- 确认卡现在有 instrumentation 覆盖：显示 `runtime_permission_requirements`
  区块、联系人权限标题和只读联系人摘要 rationale。
- 测试明确断言联系人查询不展示 `special_access_requirements`，避免 runtime
  permission 和系统特殊授权 UI 边界混淆。
- 测试只点击取消，不点击“确认执行”，因此不会触发 Android 系统权限弹窗，也不读取
  真实通讯录。

验证命令：

```bash
./gradlew :app:compileDebugAndroidTestKotlin
```

结果：通过。

## 2026-06-02 长期记忆真实 UI 路径增量验证

本轮覆盖项：

- 新增 `MainActivityLongTermMemoryUiTest`，以真实 `MainActivity` 和 Compose UI
  路径保存显式“记住”偏好，而不是只测 ViewModel 或 repository。
- 测试启动前清理主 Room 表、active session，并写入 configured localhost remote
  模式，让 composer 处于 ready；显式偏好命令仍会在远程调用前被本地记忆分支
  截获，不访问网络。
- 模型管理 > 高级 > 长期记忆面板现在有 instrumentation 覆盖：记忆开关开启、
  保存后的偏好行可见、单条“遗忘”按钮移除对应记录、清空按钮和确认弹窗清除
  剩余长期记忆。
- 同时抽出 androidTest 共享状态 helper，避免 share intent 和长期记忆 UI 测试
  受 DataStore/DB 持久状态串扰。

验证命令：

```bash
./gradlew :app:compileDebugAndroidTestKotlin
```

结果：通过。

## 2026-06-02 Activity share intent 冷启动边界增量验证

本轮覆盖项：

- 新增 `MainActivitySharedIntentTest`，用 `ActivityScenario` 以自定义
  `ACTION_SEND text/plain` intent 冷启动 `MainActivity`，覆盖真实
  Activity 边界的 `handleSharedIntent -> ShareIntentReader -> ingestSharedInput`
  链路，而不是只测 ViewModel 或 reader。
- 本地模式下，分享文本会作为 `LocalOnly` shared-input 消息进入 UI，并展示
  “已接收分享内容”本地提示。
- 远程模式下，Activity 在读取分享 intent 前选择 protected read mode；测试断言
  UI 只显示隐私保护提示，分享正文 sentinel 不渲染，避免冷启动 share target
  因持久化远程模式而读取或展示私有分享文本。
- 测试启动前显式重置主 Room 表、active session 和推理模式，降低仪器测试之间
  DataStore/DB 持久状态串扰导致的假阳性或假阴性。

验证命令：

```bash
./gradlew :app:compileDebugAndroidTestKotlin
```

结果：通过。

## 2026-06-02 Built-in Skill manifest contract 增量验证

本轮覆盖项：

- `BuiltInSkillRuntimeTest.exposesVersionedManifestsForCoreSkills` 从包含式断言
  收紧为测试自有字面量快照，防止新增、删除、改名或常量同步误改导致 Skill
  静默漂移。
- 每个 built-in Skill manifest 必须有固定 version、固定 risk level、非空
  title/description、精确 trigger examples、精确 required tools、raw closed input
  schema；required tools 的 union 必须覆盖 `MobileActionFunctions.supported`，
  且每个 required tool 必须存在于 `ToolRegistry`。
- 每个 trigger example 必须 route 回声明它的 Skill，单步 tool request 必须通过
  `ToolRegistry` 校验；代表性规划 fixture 集合也必须覆盖全部 built-in manifest。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest'
```

结果：通过。

## 2026-06-02 Background task active-list 语义增量验证

本轮覆盖项：

- 后台任务入口的 active list 只展示仍处于 `Scheduled` 的可管理任务；竞态进入
  `Running` 的任务不会继续显示成可取消项。
- `Running` 任务仍参与 task-state long-term memory 索引和恢复逻辑，避免修 UI
  语义时丢失 Agent 可召回的后台任务状态。
- 取消失败后会重新加载 active/history/policy；若底层任务仍是 `Scheduled` 则保留
  可见，若已进入 `Running` 则从可管理列表隐藏，同时显示失败提示。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.restoreStartupStateLoadsScheduledBackgroundTasksAndIndexesRunningTaskStateWithoutRemoteWork' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.cancelScheduledBackgroundTaskRefreshesUiAndCancelsScheduler' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.cancelScheduledBackgroundTaskFailureKeepsTaskVisible' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.cancelScheduledBackgroundTaskFailureHidesConcurrentlyRunningTask' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.setPeriodicCheckPolicySchedulesDefaultPolicyAndRefreshesUi' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.setPeriodicCheckPolicyFailureDoesNotShowHealthyRunningTask' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.disablePeriodicCheckPolicyMovesTaskToHistory' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.disablePeriodicCheckPolicyFailureKeepsScheduledTaskVisible'
```

结果：通过。

## 2026-06-02 JSON/XML/YAML 共享附件摘录增量验证

本轮覆盖项：

- 文本类共享附件摘录现在使用严格 UTF-8 解码。伪装成 `text/*` 或
  JSON/XML/YAML 的 malformed UTF-8 二进制内容不会被替换字符吞掉后进入 prompt；
  只有字节上限截断造成的尾部不完整 UTF-8 字符会被安全丢弃。
- 用户主动分享的 `application/json`、`application/xml`、`application/yaml`
  和 `application/x-yaml` 现在归类为 Document，并复用受限 UTF-8 文本摘录
  读取器；`application/octet-stream` 等二进制 application MIME 仍保持
  metadata-only，不打开附件流。
- Android manifest 的 SEND / SEND_MULTIPLE 入口和 in-app picker MIME 白名单同步
  接收上述四类 text-like application 文档；未纳入 `application/ld+json`，避免
  扩大到潜在二进制或图谱语义载荷。
- Protected share signal 对 `text/*`、JSON/XML/YAML、RTF、PDF、Office 和 image
  附件均不打开 stream、不跑 OCR、不暴露 protected source 计数。
- 远程模型模式在构建 prompt 前 fail closed：直接分享文本、metadata-only 附件、
  protected share signal、文本、JSON/XML/YAML、RTF/PDF/Office 和 OCR preview
  都只生成本地隐私提示，不向 remote runtime 发送正文、文件名、MIME、大小、摘录
  或 history。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.multimodal.SharedInputTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteModeRejectsDirectSharedTextBeforeBuildingPrompt' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteModeRejectsSharedAttachmentMetadataBeforeBuildingPrompt' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteModeHandlesProtectedShareSignalWithoutBuildingPrompt' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteModeRejectsSharedTextPreviewBeforeBuildingPrompt' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteModeRejectsSharedTextLikeApplicationPreviewBeforeBuildingPrompt' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteModeRejectsSharedRichTextPreviewBeforeBuildingPrompt' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteModeRejectsSharedImageOcrPreviewBeforeBuildingPrompt' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteModeRejectsSharedOfficeDocumentPreviewBeforeBuildingPrompt' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteModeRejectsSharedPdfTextLayerPreviewBeforeBuildingPrompt' \
  --tests 'com.bytedance.zgx.pocketmind.AndroidManifestTest.shareTargetsAcceptPickerSupportedDocumentMimeTypes' \
  --tests 'com.bytedance.zgx.pocketmind.AndroidManifestTest.composerAttachmentPickerUsesShareTargetMimeTypes'
./gradlew :app:testDebugUnitTest
./gradlew :app:compileDebugAndroidTestKotlin
git diff --check
git diff --unified=0 | rg -n "(sk-[A-Za-z0-9_-]{20,}|B[e]arer [A-Za-z0-9._-]{20,}|(?i)(api[_-]?key|s[e]cret|p[a]ssword|d[e]epseek))"
ANDROID_HOME=/Users/bytedance/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/bytedance/Library/Android/sdk \
scripts/verify_local.sh
```

结果：定向 multimodal/ViewModel/manifest 测试、全量 JVM 单测、AndroidTest Kotlin
编译、diff whitespace 检查、敏感串扫描和本地完整验证脚本通过。

## 2026-06-02 Agent retry / Skill checkpoint 状态口径增量验证

本轮覆盖项：

- 可重试的只读工具失败后只调度一次 bounded retry；retry 成功后仍回到正常
  observation path。后续 2026-06-03 边界收紧后，LocalEvidence 成功结果先进入
  本地模型 continuation，再由模型结果决定是否进入下一段动作。
- `SkillPlan` 明确把 `.` 作为 `stepId.outputKey` 引用分隔符；含 `.` 的 step id
  或 model output key 在结构校验阶段 fail closed，避免 value-free checkpoint
  误拆 private-output ref。
- 推荐模型卡片把 memory/action 模型安装状态显示为资产口径，避免把 memory asset
  安装误解为语义记忆 runtime 已启用。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.retryableLocalEvidenceToolContinuesToLocalModelAfterSuccessfulRetry' \
  --tests 'com.bytedance.zgx.pocketmind.skill.SkillRunExecutorTest.valueFreeCheckpointRejectsPrivateRefsWhenCompletedStepIdContainsDot'
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.skill.SkillRunExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest'
./gradlew :app:testDebugUnitTest
./gradlew :app:compileDebugAndroidTestKotlin
git diff --check
git diff --unified=0 | rg -n "<sensitive endpoint/model/key patterns>"
ANDROID_HOME=/Users/bytedance/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/bytedance/Library/Android/sdk \
scripts/verify_local.sh
```

结果：定向 Agent loop / Skill checkpoint 测试、相关 Agent/Skill 测试类、
全量 JVM 单测、AndroidTest Kotlin 编译、diff whitespace 检查、敏感串扫描和
本地完整验证脚本通过。

## 2026-06-02 语义记忆运行时状态增量验证

本轮覆盖项：

- `MemoryRepository` 明确区分 `NoVerifiedModel`、`RuntimeUnavailable`、
  `RuntimeLoadFailed` 和 `Active`；`semanticMemoryEnabled` 只在 `Active` 时为真。
- `PocketMindViewModel`/`ChatUiState` 同步暴露 runtime status，避免把已校验
  memory asset 误显示为已启用语义召回。
- 生产现在注入 fail-closed `LiteRtEmbeddingRuntimeFactory`；当前 LiteRT-LM
  SDK 未暴露公开 embedding vector API，所以已安装 memory asset 时报告
  runtime load failed 并回退轻量索引，测试注入 semantic runtime 才产生
  `MemoryRecallMode.Semantic` 命中。
- 状态机覆盖了无 factory、load failed 后清空模型、同一路径失败后重试成功、
  Active 状态传播，以及无 runtime 时 lexical fallback 仍可用。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.memory.MemoryRepositoryTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.restoreStartupStateSyncsVerifiedMemoryModelBeforeRebuildingMemoryIndex' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.restoreStartupStateReportsUnavailableSemanticRuntimeWhenFactoryIsMissing' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.localModeSemanticMemoryStatusAndPromptUseSemanticHit' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteModeKeepsSemanticMemoryRuntimeButDoesNotSendMemoryContext'
./gradlew :app:testDebugUnitTest
./gradlew :app:compileDebugAndroidTestKotlin
git diff --check
git diff --unified=0 | rg -n "<sensitive endpoint/model/key patterns>"
ANDROID_HOME=/Users/bytedance/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/bytedance/Library/Android/sdk \
scripts/verify_local.sh
```

结果：定向记忆/ViewModel 测试、全量 JVM 单测、AndroidTest Kotlin 编译、
diff whitespace 检查、敏感串扫描和本地完整验证脚本通过。

## 2026-06-02 Emulator regression 验证

本轮覆盖项：

- 在 `focus_agent_api36_arm64` AVD 上启动 API 36 / `arm64-v8a` emulator。
- 构建并安装 debug APK 与 androidTest APK。
- 执行 instrumentation smoke/regression，覆盖主界面、会话、记忆、动作确认、
  自定义下载入口、背景任务和 Room migration。

验证命令：

```bash
ANDROID_HOME=/Users/bytedance/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/bytedance/Library/Android/sdk \
AVD_NAME=focus_agent_api36_arm64 \
EMULATOR_ARGS='-no-snapshot -no-audio -no-window -no-boot-anim' \
EMULATOR_SELECT_TIMEOUT_SECONDS=120 \
BOOT_TIMEOUT_SECONDS=300 \
scripts/verify_emulator.sh
```

结果：通过。设备 `emulator-5554`，API 36，ABI `arm64-v8a`，
instrumentation `OK (14 tests)`，脚本输出 `Emulator verification passed`。
在 sequential composite Skill tail 支持提交后，使用同一 AVD/命令对当前
HEAD 复跑通过，instrumentation 仍为 `OK (14 tests)`。

## 2026-06-02 Sequential composite Skill segment 增量验证

本轮覆盖项：

- 显式顺序输入的第一段现在可以启动 validated composite Skill，例如
  “总结剪贴板并分享，然后打开 Wi-Fi 设置”会先进入
  `clipboard_summary_share_skill` 的 `read_clipboard` 确认。
- 中间 segment 也可以启动 validated composite Skill，例如
  “打开 Wi-Fi 设置，然后总结剪贴板并分享，再打开手电筒设置”会按
  `open_wifi_settings -> read_clipboard -> share_text -> open_flashlight_settings`
  推进。
- 顺序游标改为按 logical segment 计数，而不是按 tool request 计数；同一个
  composite Skill 内部的 `read_clipboard -> local model -> share_text` 不会跳过
  后续 segment。
- 后续 replan 使用当前 segment 文本绑定 Skill，避免把整条顺序输入作为下一段
  Skill 的 `input`。
- composite Skill 内部的剪贴板原文只出现在 local continuation prompt，不进入
  trace、audit 或 pending checkpoint；`share_text.text` 只能使用本地模型输出。
- 单独私密读取首段/中间段（如“读取剪贴板，然后打开 Wi-Fi 设置”）仍 fail closed。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.initialSequentialCompositeSkillSegmentPlansFirstCompositeSkill' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.sequentialCompositeSkillSegmentContinuesToNextSegmentAfterInternalToolsComplete' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.sequentialMiddleCompositeSkillSegmentContinuesToTailAfterInternalToolsComplete' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.sequentialMiddlePrivateReadSegmentDoesNotPlanWhenTailRemains' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.initialSequentialPrivateReadSegmentFallsBackToAnswer' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.defaultSequentialReplannerCanAdvanceThroughThreeExplicitActions'
./gradlew :app:testDebugUnitTest
./gradlew :app:compileDebugAndroidTestKotlin
git diff --check
git diff --unified=0 | rg -n "^\\+.*<sensitive endpoint/model/key patterns>"
ANDROID_HOME=/Users/bytedance/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/bytedance/Library/Android/sdk \
scripts/verify_local.sh
```

结果：通过。`scripts/verify_local.sh` 覆盖 doctor、脚本校验、unit、lint、
debug/androidTest/release assemble 和 APK 检查；新增 diff 行敏感串扫描无命中。

## 2026-06-02 Local verification KSP/lint ordering 增量验证

本轮覆盖项：

- `scripts/verify_local.sh` 在 `lintDebug` / assemble 聚合前先显式执行
  `:app:kspReleaseKotlin`，避免 lint model 读取 release Room/KSP 生成源时与
  `assembleRelease` 并发竞态。
- `scripts/test_validation_scripts.sh` 增加静态顺序断言，防止本地验证脚本重新把
  release KSP 预生成步骤移到 lint 之后。

验证命令：

```bash
bash -n scripts/doctor.sh scripts/verify_local.sh scripts/install_and_test_device.sh scripts/verify_emulator.sh scripts/test_validation_scripts.sh
scripts/test_validation_scripts.sh
ANDROID_HOME=/Users/bytedance/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/bytedance/Library/Android/sdk \
scripts/verify_local.sh
git diff --check
git diff --unified=0 | rg -n "^\\+.*<sensitive endpoint/model/key patterns>"
```

结果：通过。

## 2026-06-02 Initial sequential first-segment planning 增量验证

本轮覆盖项：

- 初始规划在整句显式顺序输入被保守拒绝后，可只用第一段 action segment
  重新尝试单工具/单步 Skill 规划。
- “先搜一下 Kotlin，然后打开 Wi-Fi 设置”现在能用规则路径进入 `web_search`
  确认，观察成功后继续规划 Wi-Fi 确认。
- 第一段如果是 bare 私密读取（如“读取剪贴板”）且后面还有 segment，则不拆，
  避免启动半截流程后必须跨过本地模型 continuation 继续执行后续 segment。
  后续增量已放行带自身 local model boundary 的 composite Skill 首段。
- 后续 replan 也对私密读取做同样保护：当后面还有 explicit segment 时不规划
  剪贴板/OCR/当前屏幕读取；如果它们是最后一段，则仍可进入确认。
- 解释性“先搜索再打开设置这个流程怎么实现”仍走普通回答，不进入确认。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.initialSequentialInputPlansFirstSingleToolSegmentThenContinues' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.initialSequentialCompositeSkillSegmentPlansFirstCompositeSkill' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.initialSequentialPrivateReadSegmentFallsBackToAnswer' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.sequentialReplannerSkipsPrivateReadWhenMoreSegmentsRemain' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.sequentialReplannerAllowsFinalPrivateReadSegment' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.explanatorySequentialTextStillFallsBackToAnswer'
./gradlew :app:testDebugUnitTest
./gradlew :app:compileDebugAndroidTestKotlin
git diff --check
git diff --unified=0 | rg -n "^\\+.*<sensitive endpoint/model/key patterns>"
ANDROID_HOME=/Users/bytedance/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/bytedance/Library/Android/sdk \
scripts/verify_local.sh
```

结果：通过。第一次完整验证遇到 lint/KSP generated file 读取竞态，补跑
`:app:kspReleaseKotlin :app:lintDebug` 后重新执行 `scripts/verify_local.sh`
通过；本轮新增 diff 行敏感串扫描无命中。

## 2026-06-02 Explicit sequential Agent loop 增量验证

本轮覆盖项：

- `SequentialActionObservationReplanner` 从单次后续动作扩展为有上限的显式顺序
  replan：每个成功且已验证的 observation 只规划下一段，并重新进入用户确认。
- pending confirmation 在当前进程内只携带下一段动作；Room 仍不持久化
  `nextActionInput`，重启后不会恢复 raw 剩余 sequence。
- 两段序列在第二步成功后停止，不会重复规划第二段；三段序列按
  search -> Wi-Fi settings -> flashlight settings 顺序产生三次确认后完成。
- Room trace store 在最后一段 pending 时清空 live next-action cursor，避免最后
  一段成功后重复规划。
- ToolPlanned/ConfirmationRequested 审计持久化改为参数无关摘要，避免搜索 query
  等普通工具参数进入 audit DB summary。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.defaultSequentialReplannerCanAdvanceThroughThreeExplicitActions' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.roomSequentialReplannerDoesNotRepeatFinalSegmentWhenNextInputClears' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AssistantOrchestratorTest.defaultSequentialReplannerPlansExplicitNextActionAfterObservation' \
  --tests 'com.bytedance.zgx.pocketmind.audit.ToolAuditRepositoryTest.recordDoesNotPersistToolParametersFromPlannedSummary'
./gradlew :app:testDebugUnitTest
./gradlew :app:compileDebugAndroidTestKotlin
git diff --check
git diff --unified=0 | rg -n "^\\+.*<sensitive endpoint/model/key patterns>"
ANDROID_HOME=/Users/bytedance/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/bytedance/Library/Android/sdk \
scripts/verify_local.sh
```

结果：通过。`scripts/verify_local.sh` 覆盖 doctor、脚本自测、全量 JVM 单测、
lintDebug、debug/release assemble、AndroidTest assemble、APK 模型 artifact 检查和
release APK 体积检查；全仓敏感串扫描仍会命中既有测试 fixture，本轮新增 diff 行
扫描无命中。

## 2026-06-02 Release checklist 增量验证

本轮覆盖项：

- 新增 `docs/release_checklist.md`，覆盖 release scope、store metadata、
  screenshots、privacy/license、signing/build、device/emulator validation、
  rollback 和 final gate。
- `docs/release_readiness.md` 将手工 release checklist 从 Remaining 移到
  Completed；release signing、模型 license 人工核对和 connected/emulator
  release candidate 验证仍保留为 Remaining。
- README 文档目录补充 release checklist。

验证命令：

```bash
git diff --check
rg -n "<sensitive endpoint/model/key patterns>" . --glob '!**/build/**' --glob '!**/.gradle/**'
```

结果：通过。该切片仅更新文档，未运行 Gradle。

## 2026-06-02 Release privacy/model docs 增量验证

本轮覆盖项：

- 新增 `docs/privacy_notice.md`，覆盖本地存储、远程模型模式、设备上下文工具、
  外部 Intent/分享、模型下载、audit/trace 与保留控制。文档明确这是内部测试
  隐私边界说明草案，不是最终公开法律政策。
- `docs/model_manifest.md` 增加每个推荐模型的上游仓库和 license readiness
  checklist；本地缺少足够 license 证据时，保持发布前人工核对 blocker，而不是
  误写成已完成。
- `docs/release_readiness.md` 将 privacy notice 移到 Completed 草案项，并保留
  发布前 release/security/legal 审核；模型 license 核对保留为 Remaining blocker。
- README 文档目录与 License 段落已指向隐私说明和模型 manifest。

验证命令：

```bash
git diff --check
rg -n "<sensitive endpoint/model/key patterns>" . --glob '!**/build/**' --glob '!**/.gradle/**'
```

结果：通过。该切片仅更新文档，未运行 Gradle。

## 2026-06-02 Action preflight gate 收窄验证

本轮覆盖项：

- `MobileActionPlanner.isLikelyAction` 不再使用 app/file/document/image/video/audio
  等泛词作为动作入口信号，而是复用各工具已有的 conservative parser。
- 普通聊天输入如“帮我写一份文档”“这张图片是什么”“这个 app 架构怎么设计”
  会停在普通 Answer 路径；即使测试里注入了一个可执行工具草稿，也不会调用
  action planner 生成确认卡。
- 明确工具意图仍保留：最近图片 metadata 查询、当前应用查询、剪贴板读取等请求
  仍能进入各自 Skill-first 或动作规划路径。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.genericAppFileAndMediaWordsFallBackToAnswerWithoutActionPlanning' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.skillFirstRecentMediaFilesBypassesActionPlannerAndRequestsConfirmation'
git diff --check
rg -n "<sensitive endpoint/model/key patterns>" . --glob '!**/build/**' --glob '!**/.gradle/**'
```

结果：通过。此前一次 Agent loop 定向命令因测试名不存在失败，未反映产品代码问题；
已改用当前存在的测试名重新验证通过。

## 2026-06-01 Current-screen text summary share Skill 增量验证

本轮覆盖项：

- 新增 `current_screen_text_summary_share_skill`：用户明确要求“总结当前屏幕文字并分享”时，
  先进入确认式 `read_current_screen_text`，再用本地 `ModelStep` 生成摘要，最后进入第二个
  `share_text` 确认。
- 该 Skill 只处理 Accessibility 可访问文本快照，不声明截图、OCR、像素读取或语义屏幕理解；
  说明型/否定型请求不会被当作动作。
- raw `screenText` 继续作为 private tool output：只能进入当前进程内的本地模型
  continuation，不能直接绑定到 `share_text.text`，也不进入 trace、audit、public outputs、
  pending checkpoint、持久消息或远程 runtime。
- 到第二个 payload-bearing `share_text` 确认卡后重启，恢复应 fail closed：不恢复摘要参数、
  不打开分享面板、不重跑旧 `read_current_screen_text`，旧 request id 不能继续推进。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.skill.SkillRunExecutorTest.executesCompositeCurrentScreenTextSummaryShareSkillInDependencyOrder' \
  --tests 'com.bytedance.zgx.pocketmind.skill.SkillRunExecutorTest.currentScreenTextPrivateOutputCannotBindDirectlyToLaterShareArgument' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.currentScreenTextSummarySharePlansShareAfterLocalModelResult' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.restoredCurrentScreenTextSummarySharePendingFailsClosedAfterRestart' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.currentScreenTextSummaryShareShowsSecondConfirmationAfterLocalSummary' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteModeProtectsCurrentScreenTextSummaryShareBeforeRemoteContinuation'
./gradlew :app:testDebugUnitTest
./gradlew :app:compileDebugAndroidTestKotlin
git diff --check
rg -n "<sensitive endpoint/model/key patterns>" . --glob '!**/build/**' --glob '!**/.gradle/**'
ANDROID_HOME=/Users/bytedance/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/bytedance/Library/Android/sdk \
scripts/verify_local.sh
```

结果：targeted Skill manifest/plan、executor privacy fence、Agent loop second-confirmation、
restart fail-closed、ViewModel 远程保护、全量 JVM 单测、AndroidTest Kotlin 编译、
diff whitespace 检查、敏感串扫描和本地完整验证脚本通过。

## 2026-06-01 Preference/TaskState alias memory 增量验证

本轮覆盖项：

- 默认 hash 记忆为显式 `Preference` 和结构化活跃 `TaskState` 增加保守的本地
  alias 索引，让回答长度/语言偏好和后台任务状态能通过常见中英文问法召回。
- Alias 只进入内存检索 token 和默认 hash embedding 输入，不写入 Room、
  `MemoryHit.text`、`buildContext`、长期记忆列表、远程 prompt 或普通会话记录。
- 普通 `Conversation`、非回答偏好、非结构化 `TaskState` 和 hidden
  `SuppressedTaskState` 不获得 alias；终态查询如“已取消提醒”不会召回仍活跃的
  Scheduled/Running 任务状态。
- 测试注入的 semantic runtime 启用时，embedding 输入仍使用原展示文本；检索同时保存
  原文 token 与 alias 后 token，避免 alias-only 命中被误标成普通 lexical recall。
  生产默认使用 fail-closed LiteRT embedding runtime factory；当前 SDK 未暴露公开
  embedding vector API，memory asset 不会让该路径启用。

验证命令：

```bash
./gradlew :app:testDebugUnitTest --rerun-tasks \
  --tests com.bytedance.zgx.pocketmind.memory.MemoryRepositoryTest \
  --tests com.bytedance.zgx.pocketmind.PocketMindViewModelTest.restoreStartupStateSyncsVerifiedMemoryModelBeforeRebuildingMemoryIndex \
  --tests com.bytedance.zgx.pocketmind.PocketMindViewModelTest.localModeSemanticMemoryStatusAndPromptUseSemanticHit
./gradlew :app:testDebugUnitTest
./gradlew :app:compileDebugAndroidTestKotlin
git diff --check
rg -n "<sensitive endpoint/model/key patterns>" . --glob '!**/build/**' --glob '!**/.gradle/**'
ANDROID_HOME=/Users/bytedance/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/bytedance/Library/Android/sdk \
scripts/verify_local.sh
```

结果：定向记忆/语义状态测试、全量 JVM 单测、AndroidTest Kotlin 编译、
diff whitespace 检查、敏感串扫描和本地完整验证脚本通过。

## 2026-06-01 Device preflight script coverage 增量验证

本轮覆盖项：

- `scripts/test_validation_scripts.sh` 的 fake adb 增加 `FAKE_ABI_LIST` 和
  `FAKE_DATA_FREE_KB`，可以覆盖设备预检失败分支。
- 新增非 `arm64-v8a` 设备失败用例，确认 `scripts/install_and_test_device.sh`
  在 Gradle 组装前停止，并输出 ABI 不兼容提示。
- 新增 `/data` 可用空间低于 3 GB 失败用例，确认在 Gradle 组装前停止，并输出
  空间不足提示。
- 本轮只锁定既有设备预检行为，不改变真机安装、模拟器选择、instrumentation
  或产品代码。

验证命令：

```bash
bash -n scripts/doctor.sh scripts/verify_local.sh scripts/install_and_test_device.sh scripts/verify_emulator.sh scripts/test_validation_scripts.sh
scripts/test_validation_scripts.sh
```

结果：shell 语法检查和 validation script fake 回归通过，覆盖新增 ABI/空间失败
前置边界。

补充验证：

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:compileDebugAndroidTestKotlin
git diff --check
rg -n "<sensitive endpoint/model/key patterns>" . --glob '!**/build/**' --glob '!**/.gradle/**'
ANDROID_HOME=/Users/bytedance/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/bytedance/Library/Android/sdk \
scripts/verify_local.sh
```

结果：全量 JVM 单测、AndroidTest Kotlin 编译、diff whitespace 检查、敏感串扫描
和本地完整验证脚本通过。

## 2026-06-01 Local remember control command 增量验证

本轮覆盖项：

- `记住：...` / `remember ...` 从远程可发送的普通聊天输入收窄为本地记忆控制命令。
- 显式 remember 命令会绕过 chat/action router、远程 runtime 和本地模型 runtime，
  直接 upsert 本地 `Preference` 长期记忆；会话中可见的控制命令与确认消息均为
  `LocalOnly`。
- 远程模型模式下，remember 命令不会作为当前 prompt 发送，也不会进入后续远程
  history；偏好内容和本地确认消息同样不会自动上传。
- 记忆存储失败时 fail closed：展示本地失败提示，不把 remember prompt 兜底发送
  给远程模型。
- remember 仍受待确认动作门禁保护；有 pending confirmation 时必须先确认或取消。

验证命令：

```bash
./gradlew :app:testDebugUnitTest --rerun-tasks \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.rememberCommandPersistsPreferenceMemoryOnceForDuplicateCommands' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.rememberCommandReplacesConflictingPreferenceMemory' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.rememberCommandPersistsEnglishPreferenceMemory' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.rememberCommandBypassesRouterAndRemoteRuntime' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteRememberCommandDoesNotEnterLaterRemoteHistory' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.rememberCommandMemoryStoreFailureDoesNotFallbackToRemote' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.rememberCommandWorksBeforeModelIsReady' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.forgetRememberCommandMemoryDoesNotReindexFromHistory'
```

结果：targeted remember 本地控制命令、远程模式保护、存储失败 fail-closed 和遗忘
后不从历史重新派生回归测试通过。

补充验证：

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:compileDebugAndroidTestKotlin
git diff --check
rg -n "<sensitive endpoint/model/key patterns>" . --glob '!**/build/**' --glob '!**/.gradle/**'
ANDROID_HOME=/Users/bytedance/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/bytedance/Library/Android/sdk \
scripts/verify_local.sh
```

结果：全量 JVM 单测、AndroidTest Kotlin 编译、diff whitespace 检查、敏感串扫描
和本地完整验证脚本通过。

## 2026-06-01 RTF shared-input text-layer excerpt 增量验证

本轮覆盖项：

- 用户主动分享或选择 `application/rtf` / `text/rtf` 附件时，可以在本地读取
  有界 RTF 文本层摘录，最多读取 96 KiB、最多进入 prompt 4000 字符。
- `text/rtf` 不再走通用 `text/*` raw preview；RTF preview 只在附件 kind 为
  `Document` 且 MIME 为 RTF 时进入 shared-input prompt。
- RTF 摘录跳过常见 metadata / object / pict / style destination，并保持
  `LocalOnly` shared-input 边界；远程模式在构造 prompt 前拒绝，不会自动发送
  分享文本、RTF/Office 摘录、OCR 摘录、附件名或附件元数据。
- 本轮是 best-effort 文本层摘录，不实现完整富文档解析、版式理解、codepage
  保真、PDF 解析或旧版 Office 解析。

验证命令：

```bash
./gradlew :app:testDebugUnitTest --rerun-tasks \
  --tests 'com.bytedance.zgx.pocketmind.multimodal.SharedInputTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteModeRejectsSharedRichTextPreviewBeforeBuildingPrompt' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteModeRejectsSharedTextPreviewBeforeBuildingPrompt' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteModeRejectsSharedImageOcrPreviewBeforeBuildingPrompt' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteModeRejectsSharedOfficeDocumentPreviewBeforeBuildingPrompt'
```

结果：targeted RTF/Text/OCR/Office shared-input 和远程模式保护回归测试通过。

补充验证：

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:compileDebugAndroidTestKotlin
git diff --check
rg -n "<sensitive endpoint/model/key patterns>" . --glob '!**/build/**' --glob '!**/.gradle/**'
ANDROID_HOME=/Users/bytedance/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/bytedance/Library/Android/sdk \
scripts/verify_local.sh
```

结果：全量 JVM 单测、AndroidTest Kotlin 编译、diff whitespace 检查、敏感串扫描
和本地完整验证脚本通过。

## 2026-06-01 Value-free Skill checkpoint persistence 增量验证

本轮覆盖项：

- 新增独立 `agent_skill_run_checkpoints` Room 表和 `8 -> 9` 迁移，用于记录
  pending Skill confirmation 的 value-free checkpoint。
- checkpoint 只保存 schema version、run/request/step id、Skill request id、
  manifest id/version/hash、phase、已完成 step id、输出 key 名和 private-output
  refs；不保存 `SkillRunContinuation.outputs` 值、工具结果值、模型输出、原始
  用户输入、draft payload 或剪贴板/OCR/屏幕文本。
- `RoomAgentTraceStore` 在恢复 pending confirmation 时校验 checkpoint 与
  redacted `SkillPlan`、pending tool step 和当前 `ToolRegistry` 一致；损坏
  JSON、pending step/tool 改变、manifest 改变、输出 key 非规范或 private refs
  漂移都会 fail closed，并删除 pending/checkpoint。
- `SkillRunContinuation` 增加 value-free projection；该 projection 用于检查
  结构边界，不把 continuation 本体序列化到 Room。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.skill.SkillRunExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentTraceStoreTest'
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest'
./gradlew :app:compileDebugAndroidTestKotlin
```

结果：targeted Skill checkpoint、TraceStore restore/fail-closed、Agent loop
回归测试通过；AndroidTest Kotlin 编译通过。

补充验证：

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:compileDebugAndroidTestKotlin
git diff --check
rg -n "<sensitive endpoint/model/key patterns>" . --glob '!**/build/**' --glob '!**/.gradle/**'
ANDROID_HOME=/Users/bytedance/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/bytedance/Library/Android/sdk \
scripts/verify_local.sh
```

结果：全量 JVM 单测、AndroidTest Kotlin 编译、diff whitespace 检查、敏感串扫描
和本地完整验证脚本通过。

## 2026-06-01 OCR layout-preserving text excerpt 增量验证

本轮覆盖项：

- ML Kit OCR 输出从扁平 line 去重改为保留 recognized text block / line 顺序，
  block 之间用空行分隔，Latin/Chinese recognizer 输出跨源稳定去重。
- 输出仍是 bounded text excerpt；不加入坐标、bounding boxes、图片标签、caption、
  像素或视觉语义。
- 共享图片 OCR 与受确认的最近截图/最近图片 OCR 共用该格式化边界，LocalOnly、
  trace/audit redaction 和 remote-mode protection 不变。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.multimodal.ImageTextExtractorTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.DeviceContextToolExecutorTest.recentScreenshotOcrSuccessReturnsLocalOnlyTextWithoutImageIdentifiers' \
  --tests 'com.bytedance.zgx.pocketmind.tool.DeviceContextToolExecutorTest.recentImageOcrSuccessScansImagesAndReturnsLocalOnlyTextWithoutImageIdentifiers'
```

结果：targeted OCR layout-preserving text excerpt 回归测试通过。

补充验证：

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:compileDebugAndroidTestKotlin
git diff --check
rg -n "<sensitive endpoint/model/key patterns>" . --glob '!**/build/**' --glob '!**/.gradle/**'
ANDROID_HOME=/Users/bytedance/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/bytedance/Library/Android/sdk \
scripts/verify_local.sh
```

结果：全量 JVM 单测、AndroidTest Kotlin 编译、diff whitespace 检查、敏感串扫描
和本地完整验证脚本通过。

## 2026-06-01 Office Open XML shared-input excerpt 增量验证

本轮覆盖项：

- 用户主动分享或选择的 Office Open XML `.docx` / `.xlsx` / `.pptx`
  附件可以在本地解析 ZIP XML 文本层，生成最多 4000 字符的用户可见摘录。
- 摘录只进入自动生成的 `LocalOnly` shared-input prompt；远程模型模式下不构造
  包含文件名、附件元数据或文档摘录的 prompt，也不会调用远程 runtime。
- PDF、旧版 Office 二进制、音频、视频、任意二进制文件仍保持 metadata-only；
  本轮不实现完整文档解析、PDF 解析、版式理解或语义理解。
- 解析器限制 ZIP entry 数量、XML entry bytes、总 XML bytes 和 prompt 字符数，
  并使用禁用外部实体的 XML parser。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.multimodal.SharedInputTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteModeRejectsSharedTextPreviewBeforeBuildingPrompt' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteModeRejectsSharedImageOcrPreviewBeforeBuildingPrompt' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteModeRejectsSharedOfficeDocumentPreviewBeforeBuildingPrompt'
```

结果：targeted Office Open XML shared-input excerpt 回归测试通过。

补充验证：

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:compileDebugAndroidTestKotlin
git diff --check
rg -n "<sensitive endpoint/model/key patterns>" . --glob '!**/build/**' --glob '!**/.gradle/**'
ANDROID_HOME=/Users/bytedance/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/bytedance/Library/Android/sdk \
scripts/verify_local.sh
```

结果：全量 JVM 单测、AndroidTest Kotlin 编译、diff whitespace 检查、敏感串扫描
和本地完整验证脚本通过。

## 2026-06-01 Periodic check startup reconcile 增量验证

本轮覆盖项：

- `BackgroundTaskScheduler` 新增 `reconcilePeriodicCheckOnStartup()`，Android
  实现委托 `PeriodicCheckScheduler`。
- App 启动恢复先重排 reminder alarm，再 reconcile `periodic-check-local`，
  然后重新加载 active tasks、history 和 typed periodic policy。
- Periodic check startup reconcile 会恢复 stale `Running`、重入队 enabled
  `Scheduled` WorkManager periodic work；入队失败时将本地任务标为 `Failed`。
- Disabled、terminal 或 fresh `Running` periodic check 不会在启动时被重入队或
  被错误改写。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.background.PeriodicCheckSchedulerTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.restoreStartupStateReconcilesPeriodicCheckBeforeLoadingBackgroundTasks' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.restoreStartupStateReschedulesReminderAlarmsBeforeLoadingBackgroundTasks'
```

结果：targeted periodic check startup reconcile 回归测试通过。

## 2026-06-01 Reminder audit metadata minimization 增量验证

本轮覆盖项：

- `ToolAuditRepository` 不再把 `schedule_reminder` / `cancel_reminder`
  成功事件的 stored summary 原样展示或持久化到审计 UI；写入审计库时只提取
  严格格式的 allowlisted task recovery metadata。
- 允许展示的字段限定为 `taskId`、`taskStatus`、`triggerAtMillis`、
  `recoveryToolName`、`recoveryTaskId`；提醒标题、正文、用户原文、邮箱、token
  或其他未声明 payload 不进入 `ToolAuditRecord.summary`。
- `ToolRegistryTest` 锁定 reminder/cancel output schema 不暴露 title/body/prompt/
  summary/text，避免未来把提醒内容加入成功结果契约。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.audit.ToolAuditRepositoryTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.deniedSpecialAccessFailsPendingToolWithoutExecutingIt' \
  --tests 'com.bytedance.zgx.pocketmind.AgentRuntimePermissionPolicyTest.specialAccessDenialSummaryUsesRequirementTitles' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.accessibilitySpecialAccessReturnUpdatesStatusTextWithoutExecutingTools'
```

结果：targeted reminder audit metadata minimization 回归测试通过。

补充验证：

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:compileDebugAndroidTestKotlin
git diff --check
rg -n "<sensitive endpoint/model/key patterns>" . --glob '!**/build/**' --glob '!**/.gradle/**'
```

结果：全量 JVM 单测、AndroidTest Kotlin 编译、diff whitespace 检查通过；
敏感串扫描无命中。

## 2026-06-01 Accessibility special-access execution boundary 增量验证

本轮覆盖项：

- `read_current_screen_text` 的 Accessibility 屏幕文本授权返回路径与 Usage Access
  一样，只更新 UI 状态，不确认 pending tool、不执行工具、不读取屏幕文本。
- `read_current_screen_text` 确认前会重查 Accessibility special access；若未开启，
  ViewModel 以 `PermissionDenied` fail pending tool，返回
  `specialAccess/settingsAction` 结构化 metadata，且不调用 executor。
- pre-executor 保证由 JVM ViewModel 测试验证；UI-only AndroidTest 在无障碍未开启
  时无法区分 MainActivity 预检失败与 executor 防御失败，因此本轮以
  AndroidTest Kotlin 编译保持 instrumentation 源可用。
- `docs/agent_core_modules.md` 中的 Execution Boundary 状态改为反映当前代码：
  已覆盖 Usage Access 与 Accessibility screen text 两类 bounded special access；
  更广泛的特殊授权面仍待实现。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.accessibilitySpecialAccessReturnUpdatesStatusTextWithoutExecutingTools' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.deniedSpecialAccessFailsPendingToolWithoutExecutingIt' \
  --tests 'com.bytedance.zgx.pocketmind.AgentRuntimePermissionPolicyTest.specialAccessDenialSummaryUsesRequirementTitles' \
  --tests 'com.bytedance.zgx.pocketmind.AgentRuntimePermissionPolicyTest.currentScreenTextDeclaresAccessibilityAsSpecialAccessNotRuntimePermission' \
  --tests 'com.bytedance.zgx.pocketmind.AgentRuntimePermissionPolicyTest.pendingSpecialAccessRequirementRestoresFromCurrentPendingConfirmationOnly'
```

结果：targeted Accessibility special-access boundary 回归测试通过。

## 2026-06-01 ToolResult output schema 执行边界增量验证

本轮覆盖项：

- `ToolSpec` 新增 `outputSchemaJson`，表示 successful `ToolResult.data`
  的结构契约，不是所有
  `ToolResult` 状态的通用契约。
- `Rejected`、`Failed`、`Cancelled` 保持结构化失败/取消结果；这些结果需要携带
  `ToolError`、取消原因或安全拒绝 metadata，但不要求满足 success data schema。
- `ValidatingToolExecutor` 在 delegate 返回后校验 success data；缺少必需字段、
  字段类型不匹配或 request id 不匹配时，返回非 retryable `InvalidResult`
  failed `ToolResult`，且只保留 `toolName` context。
- 设备上下文工具的私密输出字段仍声明在 output schema 中，并继续通过
  `privateOutputKeys` / `LocalOnly` 边界进入后续本地处理。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.RoutingAndValidatingToolExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.DeviceContextToolExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.CalendarAvailabilityToolExecutorTest'
```

结果：targeted Tool output schema / routing / device-context 回归测试通过。

## 2026-06-01 Pending confirmation payload minimization 增量验证

本轮覆盖项：

- `ToolSpec` 新增 `pendingArgumentAllowlist`，Room pending confirmation
  持久化只保留工具声明的安全参数键。当前非空 allowlist 仅覆盖
  `open_app_intent.packageName`、`open_app_deep_target.targetId/packageName`、
  `query_calendar_availability.start/end`、近期通知/文件/OCR/屏幕读取的数值
  上限，以及 `cancel_reminder.taskId`。
- `share_text`、`web_search`、地图搜索、邮件/日历/联系人草稿、联系人查询、
  `schedule_reminder` 和 `open_deep_link` 等 payload-bearing pending 不再跨
  重启恢复；Room row 不保存其 executable payload key/value，恢复时由
  `ToolRegistry.validate` 判定缺少必需参数并 fail closed。
- `nextActionInput` 不再写入 Room。当前进程内仍保留 active pending 的 raw
  snapshot 以支撑 UI/确认，进程重启后不再用 pending row 继续 sequential
  replan。
- Room 恢复路径会拒绝含 redacted executable payload、schema 校验失败或
  SkillPlan 边界不匹配的 pending row，并把所属 awaiting run 标记为
  `Failed`，避免不可见确认卡或 payload 复活。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentTraceStoreTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest'
```

结果：targeted pending restore / fail-closed 回归测试通过。

## 2026-06-01 Restored Agent loop context 增量验证

本轮覆盖项：

- Room pending confirmation 不再保存显式 sequential 后续动作片段
  `nextActionInput`；完整 raw run input 仍不会写入 `agent_runs`、trace
  summary 或 audit。
- pending row 被确认/清理后，DB 不再保留该片段；Room store 仅在内存中保留到
  observation 结束，以便确认后的工具结果仍能规划下一步。
- 恢复 pending 时会从已持久化的 `ToolRequested` trace JSON 恢复历史
  request id 骨架，仅用于去重；不会恢复旧确认卡，也不会恢复旧 arguments /
  reason。
- 数据库版本升到 8，新增 `pending_agent_confirmations.nextActionInput`
  nullable column 并覆盖 7→8 migration；该列保留为兼容字段，当前写入值为
  null。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentTraceStoreTest.roomStoreFailsPayloadPendingConfirmationWithoutPuttingRawArgumentsInTrace' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentTraceStoreTest.roomStoreHydratesPriorToolRequestsForRestoreDedupWithoutOldConfirmations' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.restoredPendingConfirmationContinuesSequentialNextActionAfterObservation' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.restoredPendingConfirmationRejectsReplannedOldRequestId'

./gradlew :app:compileDebugAndroidTestKotlin
```

结果：通过。

## 2026-06-01 Foreground app trace privacy 增量验证

本轮覆盖项：

- `query_foreground_app` 的 `packageName` 与 `appLabel` 声明为 private tool
  outputs。
- Agent trace / audit / assistant observation 使用 redacted result summary，
  不再持久化前台应用名或包名。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest.privateToolOutputsAreDeclaredByToolPolicy' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.foregroundAppObservationRedactsAppIdentityFromTrace'
```

结果：通过。

## 2026-06-01 Background terminal-state race 增量验证

本轮覆盖项：

- Reminder / periodic check 的失败回写改为条件状态转移，旧快照不会把
  `Cancelled`、`Delivered` 或其他终态覆盖为 `Failed`。
- `scheduledOrRunning()` 生产路径现在返回所有 `Scheduled` 与 `Running`
  任务，启动恢复不会漏掉投递中卡住的 reminder。
- 后台任务取消失败分支会重新加载 active/history/policy，避免竞态失败后 UI
  继续显示过期状态。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.background.ScheduledTaskRepositoryTest' \
  --tests 'com.bytedance.zgx.pocketmind.background.ReminderAlarmReceiverTest' \
  --tests 'com.bytedance.zgx.pocketmind.background.PeriodicCheckSchedulerTest' \
  --tests 'com.bytedance.zgx.pocketmind.background.ScheduledTaskRemovalCoordinatorTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.cancelRunningBackgroundTaskFailureRefreshesStaleTaskLists' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.restoreStartupStateLoadsRunningBackgroundTasksWithoutRemoteWork'
```

结果：通过。

## 2026-06-01 Direct parser non-action guard 增量验证

本轮覆盖项：

- Action planner 和 built-in Skill runtime 对句首否定输入 fail closed，覆盖
  地图、邮件、日程、Web 搜索、深链、联系人、当前屏幕、日历忙闲和最近 OCR。
- 剪贴板上下文读取拒绝否定/讨论输入，避免“不要读取剪贴板/如何读取剪贴板”
  进入读取确认。
- 复合顺序输入不再被 skill-first 拒绝后由 rule action planner 接住后半段，
  避免“总结剪贴板并分享，然后打开 Wi-Fi 设置”直接变成 Wi-Fi 确认卡。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.skillFirstSequentialInputFallsBackToAnswerWhenRulePlannerRejectsIt'
```

结果：通过。

## 2026-06-01 Legacy 会话隐私迁移增量验证

本轮覆盖项：

- 3→4 数据库迁移后，已有 `chat_messages` 行写为 `LocalOnly`，避免无
  provenance 的旧历史进入 remote history。
- 旧 SharedPreferences `sessions_json` 导入的消息写为 `LocalOnly`。
- Android instrumentation migration 测试已更新并通过 androidTest 编译。

验证命令：

```bash
./gradlew :app:compileDebugAndroidTestKotlin \
  :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.runtime.RemoteChatRuntimeTest.buildChatCompletionBody_excludesLocalOnlyHistory'
```

结果：通过。

## 2026-06-01 周期检查状态机竞态增量验证

本轮覆盖项：

- 周期检查 worker 只允许 `Scheduled -> Running` 与
  `Running -> Scheduled/Failed` 条件转移。
- 用户在 worker 通知过程中关闭周期检查时，`Cancelled` 终态不会被 worker
  completion 反写成 `Scheduled`。
- `scheduledOrRunning()` 不会在关闭后重新显示 `periodic-check-local`。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.background.PeriodicCheckSchedulerTest' \
  --tests 'com.bytedance.zgx.pocketmind.background.ScheduledTaskRepositoryTest' \
  --tests 'com.bytedance.zgx.pocketmind.background.ScheduledTaskRemovalCoordinatorTest' \
  --tests 'com.bytedance.zgx.pocketmind.background.ReminderAlarmReceiverTest'
```

结果：通过。

## 2026-06-01 Agent 隐私与状态口径增量验证

本轮覆盖项：

- Clipboard context 的 Skill-first 路径拒绝 sequential follow-up，避免
  “读取剪贴板，然后打开 Wi-Fi 设置”只执行前半段。
- Clipboard summary share 的 Skill-first 路径拒绝 sequential follow-up，避免
  “总结剪贴板并分享，然后打开 Wi-Fi 设置”只执行前半段。
- 未知持久化 `MessagePrivacy` 恢复为 `LocalOnly`，从 remote history 边界
  fail-closed。
- Agent trace 的 `ToolRequested` 和 `UseTool` planning 摘要/JSON 不再保存
  参数化 `request.reason`，仅保留工具名、参数 key 和草稿标题。
- 普通 Chat 的 Agent trace 在生成完成后回写 `Completed`，避免重启 stale
  recovery 把已经成功回答的 run 标成 `Failed`。
- 工具结果里的未知 `privacy` metadata 按 `LocalOnly` 处理，防止未来值或坏值
  被当成可远程续写。
- 后台任务 UI 的 active 列表与 task-state memory 口径一致，包含
  `Scheduled` 与 `Running`；取消入口只展示给 still-`Scheduled` 任务。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.data.SessionRepositoryTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentTraceStoreTest'
```

结果：通过。

补充验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest'
```

结果：通过。

补充验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.pureChatAnswerCompletesAgentTraceRun' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.unknownToolResultPrivacyIsTreatedAsLocalOnlyBeforeRemoteContinuation' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteModeProtectsGenericLocalOnlyContinuationAsLocalToolResult'
```

结果：通过。

## 2026-06-01 Cancel reminder skill-first routing 增量验证

本轮覆盖项：

- 显式“取消/撤销提醒 + `task-*` id”请求可由 built-in Skill runtime 直接规划为
  受确认保护的 `cancel_reminder`，不再依赖 action planner。
- Shared cancel-reminder parser 只接受 reminder/background-task 取消语义和
  `task-*` id；拒绝无 task id、API/实现/解释、否定命令，以及取消日历/联系人/
  邮件等非 reminder 任务。
- `cancel_reminder` 继续归属 `reminder_skill`，风险级别为
  `MediumDraftOrNavigation`；registry schema 也要求 `taskId` 匹配
  `^task-[A-Za-z0-9_-]+$`，避免绕过 parser。
- 权限和执行边界保持本地：取消提醒不请求 Android runtime permission 或
  special access；仍必须用户确认，并只在对应 still-`Scheduled` task 被平台取消且
  本地状态更新后报告成功。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest.infersCancelReminderDraftWithTaskId' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest.plansCancelReminderSkillFirstWithoutActionDraft' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest.plansReminderAsBackgroundToolStep' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest.builtInPlansUseSkillInputArgumentsAndValidateAgainstManifestSchema' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.skillFirstCancelReminderBypassesActionPlannerAndRequestsConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.parameterizedSkillFirstDiscussionInputsRemainAnswersWithoutToolAudit' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest.validatesCancelReminderTaskId'
```

结果：通过。

## 2026-06-01 Contact draft skill-first routing 增量验证

本轮覆盖项：

- 显式“新建/添加/创建联系人 + 姓名”请求可由 built-in Skill runtime 直接规划为
  受确认保护的 `create_contact_draft`，不再依赖 action planner。
- Shared contact-draft parser 只提取草稿字段 `name`/`email`/`phone`；拒绝空
  新建请求、联系人权限、ContactsContract/API/页面/组件/实现讨论、删除/编辑/
  导出联系人、联系人查询和否定命令。
- 新增 `contact_draft_skill` manifest，风险级别为 `MediumDraftOrNavigation`；
  registry 继续校验 `create_contact_draft` 的 `name` 必填和 closed schema。
- 权限和数据边界保持分离：`create_contact_draft` 是 ExternalDraft，只打开系统
  联系人插入页，不读取通讯录、不请求 `READ_CONTACTS`、不保存或提交联系人；
  `query_contacts` 仍是唯一需要 `READ_CONTACTS` 的联系人读取工具。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest.contactDraftRequiresExplicitNameAndRejectsNonDraftInputs' \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest.contactQueryRequiresExplicitQueryAndRejectsNonLookupInputs' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest.plansContactDraftWithoutActionDraftWhenCommandIsExplicit' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest.plansContactLookupWithoutActionDraftWhenQueryIsExplicit' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest.exposesVersionedManifestsForCoreSkills' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest.builtInPlansUseSkillInputArgumentsAndValidateAgainstManifestSchema' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.skillFirstContactDraftBypassesActionPlannerAndRequestsConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.parameterizedSkillFirstDiscussionInputsRemainAnswersWithoutToolAudit' \
  --tests 'com.bytedance.zgx.pocketmind.AgentRuntimePermissionPolicyTest.contactDraftSkillFirstConfirmationDoesNotRequestContactsPermission' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest.exposesSpecsForSupportedActionsWithConfirmationRequired'
```

结果：通过。

## 2026-06-01 最近图片 OCR skill-first routing 增量验证

本轮覆盖项：

- 明确最近图片/照片文字 OCR 请求可由 built-in Skill runtime 直接规划为
  受确认保护的 `read_recent_image_ocr`。
- 确认后最多扫描最近 3 张图片像素并在本地提取第一条 OCR 摘录；结果为
  `LocalOnly`，`ocrText` 是 private tool output，不能直接绑定到后续工具参数。
- 普通“最近图片”仍只走 `query_recent_files(kind="images")` metadata-only；
  不读取图片像素或 OCR 文本。
- 权限边界为 Android 13+ `READ_MEDIA_IMAGES`，Android 12- legacy storage
  read permission。
- 该能力不是当前屏幕捕获、图片语义理解、看图描述、任意媒体 OCR 或全相册
  扫描；所有/大量/超过 3 张图片 OCR、实现/API/权限讨论和否定命令应拒绝。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest.infersRecentImageOcrOnlyWhenTextExtractionIsExplicit' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest.plansRecentImageOcrWithoutActionDraftWhenCommandIsExplicit' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest.exposesVersionedManifestsForCoreSkills' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest.builtInPlansUseSkillInputArgumentsAndValidateAgainstManifestSchema' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest.plansRecentMediaFilesWithoutActionDraftWhenMetadataRequestIsExplicit' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.skillFirstRecentImageOcrBypassesActionPlannerAndRequestsConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.parameterizedSkillFirstDiscussionInputsRemainAnswersWithoutToolAudit' \
  --tests 'com.bytedance.zgx.pocketmind.AgentRuntimePermissionPolicyTest.recentImageOcrSkillFirstConfirmationStillRequestsImageReadPermission'
```

结果：通过。

## 2026-06-01 最近截图 OCR skill-first routing 增量验证

本轮覆盖项：

- 明确最近 1 张截图文字/OCR 请求可由 built-in Skill runtime 直接规划为
  受确认保护的 `read_recent_screenshot_ocr`。
- 确认后只读取最近 1 张截图像素并在本地 OCR；结果为 `LocalOnly`，
  `ocrText` 是 private tool output，不能直接绑定到后续工具参数。
- 权限边界为 Android 13+ `READ_MEDIA_IMAGES`，Android 12- legacy storage
  read permission。
- 该能力不是当前屏幕捕获、视觉理解、任意媒体 OCR，也不支持多张截图
  OCR；多张截图 OCR 请求应拒绝。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest.infersRecentScreenshotOcrOnlyWhenTextExtractionIsExplicit' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest.plansRecentScreenshotOcrWithoutActionDraftWhenCommandIsExplicit' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest.exposesVersionedManifestsForCoreSkills' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest.builtInPlansUseSkillInputArgumentsAndValidateAgainstManifestSchema' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest.plansRecentMediaFilesWithoutActionDraftWhenMetadataRequestIsExplicit' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.skillFirstRecentScreenshotOcrBypassesActionPlannerAndRequestsConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.parameterizedSkillFirstDiscussionInputsRemainAnswersWithoutToolAudit' \
  --tests 'com.bytedance.zgx.pocketmind.AgentRuntimePermissionPolicyTest.recentScreenshotOcrSkillFirstConfirmationStillRequestsImageReadPermission'
```

结果：通过。

## 2026-06-01 Current-screen text skill-first routing 增量验证

本轮覆盖项：

- 显式“读取/总结当前屏幕文字”请求可由 built-in Skill runtime 直接规划为
  `read_current_screen_text` 待确认工具，不再依赖 action planner。
- Shared current-screen parser 只接受明确当前屏幕/当前界面的 Accessibility
  文本快照意图；拒绝“这页/页面内容”泛称、截图、OCR、像素、视觉/语义屏幕理解、
  API/实现/权限讨论和否定命令。
- 新增 `current_screen_text_context_skill` manifest，风险级别为
  `MediumDraftOrNavigation`；工具继续走 registry schema、safety、audit 和用户确认。
- 权限和数据边界保持最小化：skill-first pending confirmation 仍只声明
  Accessibility special access，不请求 Android runtime permission；raw
  `screenText` 仍只用于本地 continuation，并由 trace/audit 脱敏。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest.infersCurrentScreenTextOnlyForAccessibleTextRequests' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest.plansCurrentScreenTextWithoutActionDraftWhenCommandIsExplicit' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest.exposesVersionedManifestsForCoreSkills' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest.builtInPlansUseSkillInputArgumentsAndValidateAgainstManifestSchema' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.skillFirstCurrentScreenTextBypassesActionPlannerAndRequestsConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.parameterizedSkillFirstDiscussionInputsRemainAnswersWithoutToolAudit' \
  --tests 'com.bytedance.zgx.pocketmind.AgentRuntimePermissionPolicyTest.currentScreenTextSkillFirstConfirmationDeclaresAccessibilitySpecialAccessOnly'
```

补充回归：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.AgentRuntimePermissionPolicyTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.DeviceContextToolExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolSchemaContractTest'
```

结果：通过。

## 2026-06-01 Calendar availability skill-first routing 增量验证

本轮覆盖项：

- 显式“查忙闲 + 两个 ISO 时间”请求可由 built-in Skill runtime 直接规划为
  `query_calendar_availability` 待确认工具，不再依赖 action planner。
- Shared calendar-availability parser 要求忙闲/availability/free-busy 意图和
  两个带时区 ISO-8601 时间同时存在；拒绝空忙闲请求、自然语言日期、日历权限、
  API/实现/设计讨论。
- 新增 `calendar_availability_skill` manifest，风险级别为 `LowReadOnly`；
  工具继续走 registry schema、provider 31 天窗口校验、safety、audit 和用户确认。
- 权限和数据边界保持最小化：skill-first pending confirmation 仍请求
  `READ_CALENDAR`；结果只返回 busy/free blocks，不返回事件标题、地点、参与人、
  备注或 calendar id。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest.infersCalendarAvailabilityDraftWhenIsoWindowIsExplicit' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest.plansCalendarAvailabilityWithoutActionDraftWhenIsoWindowIsExplicit' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest.exposesVersionedManifestsForCoreSkills' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest.builtInPlansUseSkillInputArgumentsAndValidateAgainstManifestSchema' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.skillFirstCalendarAvailabilityBypassesActionPlannerAndRequestsConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.parameterizedSkillFirstDiscussionInputsRemainAnswersWithoutToolAudit' \
  --tests 'com.bytedance.zgx.pocketmind.AgentRuntimePermissionPolicyTest.calendarAvailabilitySkillFirstConfirmationStillRequestsCalendarPermission' \
  --tests 'com.bytedance.zgx.pocketmind.tool.CalendarAvailabilityToolExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest.validatesCalendarAvailabilityStartAndEndArguments' \
 --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest.exposesSpecsForSupportedActionsWithConfirmationRequired'
```

补充回归：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.AgentRuntimePermissionPolicyTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.CalendarAvailabilityToolExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.DeviceContextToolExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolSchemaContractTest'
```

结果：通过。

## 2026-06-01 Contact lookup skill-first routing 增量验证

本轮覆盖项：

- 显式“查联系人 Alice / look up Alice in contacts”请求可由 built-in Skill
  runtime 直接规划为 `query_contacts` 待确认工具，不再依赖 action planner。
- Shared contact parser 要求明确查询对象；拒绝裸“联系人/contact(s)”、空
  “查询联系人”、联系人权限、ContactsContract/API/实现讨论、否定、导出/全量
  列表、编辑/新建联系人等非查询意图。
- 新增 `contact_lookup_skill` manifest，风险级别为 `LowReadOnly`；registry
  schema 将 `maxCount` 限制为 `1..20`，executor 也按同一上限规范化。
- 权限和隐私边界保持最小化：skill-first pending confirmation 仍只请求
  `READ_CONTACTS`；工具只返回 `name`/`phone`，不返回 email、头像、地址、备注、
  contact id 或全量通讯录导出；`query` 和 `contactsJson` 标记为 private
  output，trace/audit 中使用“已读取联系人摘要”替代手机号明文。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest.parsesContactQueryCallOutput' \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest.contactQueryRequiresExplicitQueryAndRejectsNonLookupInputs' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest.plansContactLookupWithoutActionDraftWhenQueryIsExplicit' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest.exposesVersionedManifestsForCoreSkills' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest.builtInPlansUseSkillInputArgumentsAndValidateAgainstManifestSchema' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.skillFirstContactLookupBypassesActionPlannerAndRequestsConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.contactObservationRedactsPrivateTraceFields' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.parameterizedSkillFirstDiscussionInputsRemainAnswersWithoutToolAudit' \
  --tests 'com.bytedance.zgx.pocketmind.AgentRuntimePermissionPolicyTest.contactLookupSkillFirstConfirmationStillRequestsContactsPermission' \
  --tests 'com.bytedance.zgx.pocketmind.tool.DeviceContextToolExecutorTest.contactSummarySuccessReturnsMinimalLocalOnlyFields' \
  --tests 'com.bytedance.zgx.pocketmind.tool.DeviceContextToolExecutorTest.contactSummaryFailureIsRetryableAndLocalOnly' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest.contactSchemaRejectsMissingQueryAndUnsupportedMaxCount' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest.privateToolOutputsAreDeclaredByToolPolicy' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest.exposesSpecsForSupportedActionsWithConfirmationRequired'
```

补充回归：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.AgentRuntimePermissionPolicyTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.DeviceContextToolExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolSchemaContractTest'
```

结果：通过。

## 2026-06-01 Current-app notification skill-first routing 增量验证

本轮覆盖项：

- 显式“最近通知/当前应用通知摘要”请求可由 built-in Skill runtime 直接规划为
  `query_recent_notifications` 待确认工具，不再依赖 action planner。
- Shared notification parser 继续接受中文“最近通知/最近 N 条通知/通知摘要”
  和英文 `current app` / `this app` / `PocketMind` 通知摘要请求；拒绝裸
  `notification(s)`、`recent app notifications`、通知权限/渠道/push/listener、
  系统/全局/其他应用通知和通知栏语义，避免越过当前应用边界。
- 新增 `recent_notifications_context_skill` manifest，风险级别为
  `LowReadOnly`；registry schema 将 `maxCount` 限制为 `1..20`，executor
  也按同一上限规范化。
- 权限边界保持最小化：`query_recent_notifications` 不声明 Android runtime
  permission 或 special access；通知被系统关闭时返回结构化
  `PermissionDenied`，不会自动请求 `POST_NOTIFICATIONS`。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest.recentNotificationSummaryMatchesCurrentAppOnlyBoundary' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest.plansRecentNotificationsWithoutActionDraftWhenCurrentAppRequestIsExplicit' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest.exposesVersionedManifestsForCoreSkills' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest.builtInPlansUseSkillInputArgumentsAndValidateAgainstManifestSchema' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.skillFirstRecentNotificationsBypassesActionPlannerAndRequestsConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.parameterizedSkillFirstDiscussionInputsRemainAnswersWithoutToolAudit' \
  --tests 'com.bytedance.zgx.pocketmind.AgentRuntimePermissionPolicyTest.recentNotificationsDeclareNoRuntimePermissionOrSpecialAccess' \
  --tests 'com.bytedance.zgx.pocketmind.tool.DeviceContextToolExecutorTest.notificationSummarySuccessReturnsLocalOnlyMetadataOnlyJson' \
  --tests 'com.bytedance.zgx.pocketmind.tool.DeviceContextToolExecutorTest.notificationSummaryPermissionDeniedAndFailureAreStructured' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest.recentNotificationSchemaRejectsUnsupportedMaxCount' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest.exposesSpecsForSupportedActionsWithConfirmationRequired'
```

补充回归：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.AgentRuntimePermissionPolicyTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.DeviceContextToolExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolSchemaContractTest'
```

结果：通过。

## 2026-06-01 Foreground app skill-first routing 增量验证

本轮覆盖项：

- 显式“当前应用/前台应用是什么”请求可由 built-in Skill runtime 直接规划为
  `query_foreground_app` 待确认工具，不再依赖 action planner。
- Shared foreground-app parser 拒绝“前台服务”“current app architecture”
  和实现/设计讨论，避免把 Android 开发语境误触为设备上下文读取。
- 新增 `foreground_app_context_skill` manifest，风险级别保持 `LowReadOnly`；
  工具仍声明 Usage Access 为 special app access，不伪装成 Android runtime
  permission，确认后仅读取应用名与包名。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest.infersForegroundAppOnlyForExplicitCurrentAppRequests' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest.plansForegroundAppWithoutActionDraftWhenCommandIsExplicit' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest.exposesVersionedManifestsForCoreSkills' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest.builtInPlansUseSkillInputArgumentsAndValidateAgainstManifestSchema' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.skillFirstForegroundAppBypassesActionPlannerAndRequestsConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.parameterizedSkillFirstDiscussionInputsRemainAnswersWithoutToolAudit' \
  --tests 'com.bytedance.zgx.pocketmind.AgentRuntimePermissionPolicyTest.foregroundAppDeclaresUsageAccessAsSpecialAccessNotRuntimePermission' \
  --tests 'com.bytedance.zgx.pocketmind.tool.DeviceContextToolExecutorTest.foregroundAppSuccessReturnsLocalOnlyMinimalFields' \
  --tests 'com.bytedance.zgx.pocketmind.tool.DeviceContextToolExecutorTest.foregroundAppPermissionDeniedAndFailureAreRetryableLocalFailures' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest.declaresDeviceContextTools'
```

结果：通过。

## 2026-06-01 HTTPS deep link skill-first routing 增量验证

本轮覆盖项：

- 显式“打开/访问 HTTPS 链接”请求可由 built-in Skill runtime 直接规划为
  `open_deep_link` 待确认工具，不再依赖 action planner。
- Shared deep-link parser 要求打开意图和 `https://` URI 同时存在；裸链接、
  解释类、否定类、`http/file/javascript` 等非 HTTPS scheme 不触发。
- URI-like 文本不会再被 package-name app intent parser 误判成包名启动请求。
- 新增 `deep_link_navigation_skill` manifest，仍经 registry schema、safety、
  audit 和用户确认，工具层继续只允许 HTTPS 外部导航。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest.infersDeepLinkDraftForExplicitUri' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest.plansHttpsDeepLinkWithoutActionDraftWhenCommandIsExplicit' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.skillFirstHttpsDeepLinkBypassesActionPlannerAndRequestsConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.parameterizedSkillFirstDiscussionInputsRemainAnswersWithoutToolAudit'
```

补充回归：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolSchemaContractTest'
```

结果：通过。

## 2026-06-01 Recent media metadata skill-first routing 增量验证

本轮覆盖项：

- 最近图片、截图、视频、音频等媒体 metadata 请求可由 built-in Skill runtime
  直接规划为受确认保护的 `query_recent_files`，不再依赖 action planner。
- Action planner 与 Skill runtime 复用同一组 recent-files parser；Skill-first
  只覆盖媒体元数据，文档、下载、全部文件仍保留在 action fallback 路径。
- Parser 明确拒绝“不要查询最近图片”“最近图片权限怎么申请”、
  “recent screenshots API”和“how to read recent images”等否定/讨论输入，
  避免把权限、API 或解释请求变成文件 metadata 读取确认。
- Parser 明确拒绝“识别/提取/文字/OCR/text”等内容读取意图，避免抢走
  `read_recent_screenshot_ocr` 和 `read_recent_image_ocr`。
- 新增 `recent_files_context_skill` manifest，风险级别保持 `LowReadOnly`，
  工具执行仍经 registry schema、权限策略、safety、audit 和用户确认。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.skillFirstRecentMediaFilesBypassesActionPlannerAndRequestsConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.parameterizedSkillFirstDiscussionInputsRemainAnswersWithoutToolAudit' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolSchemaContractTest'
```

补充回归：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest' \
  --tests 'com.bytedance.zgx.pocketmind.AgentRuntimePermissionPolicyTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.DeviceContextToolExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.RoutingAndValidatingToolExecutorTest'
```

结果：通过。

## 2026-06-01 Information lookup skill-first routing 增量验证

本轮覆盖项：

- `web_search` 的显式搜索请求可由 built-in Skill runtime 直接规划为低风险只读工具，
  无需确认且不再依赖 action planner。
- Action planner 与 Skill runtime 复用同一组搜索 parser。Parser 只接受明确
  搜索/网页搜索/网络搜索/百度/Google/Bing/look up 等表达，或带明确地点的天气查询，
  并要求非空 query；裸“查一下”不再被推断为网页搜索，避免绕过窄口径 Skill-first
  边界。
- 反例覆盖空搜索、解释类、否定类和代码/错误排查语境输入，避免“网页搜索是什么”
  或“查一下这个错误原因”误触工具确认。
- 顺序任务（如“先搜 Kotlin，然后打开 Wi-Fi 设置”）不会被一跳 Skill-first
  parser 抢走，继续交给 Agent loop / action runtime 的观察后重规划链路。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest.infersDraftForNaturalLanguageWebSearch' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest.plansWebSearchWithoutActionDraftWhenCommandIsExplicit' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.skillFirstWebSearchBypassesActionPlannerAndExecutesWithoutConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.successfulObservationCanPlanNextToolAndRequestConfirmationAgain' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.unverifiedExternalLaunchDoesNotAutoPlanNextTool' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.invalidActionDraftIsRejectedBeforeConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.parameterizedSkillFirstDiscussionInputsRemainAnswersWithoutToolAudit'
```

补充回归：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest.validatesWebSearchQueryArgument' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolSchemaContractTest' \
  --tests 'com.bytedance.zgx.pocketmind.audit.ToolAuditRepositoryTest.recentAuditEventsDoesNotExposeToolParametersFromPlannedSummary'
```

结果：通过。

## 2026-06-01 Device settings skill-first routing 增量验证

本轮覆盖项：

- `open_wifi_settings`、`open_usage_access_settings`、`open_flashlight_settings`
  的显式设置入口请求可由 built-in Skill runtime 直接规划为待确认工具，不再依赖
  action planner。
- Action planner 与 Skill runtime 复用同一组设备设置 parser，继续经过 registry
  validation、safety、audit 和用户确认，不直接打开系统设置页。
- 反例覆盖解释类、实现类、API 类、否定类输入，避免“Wi-Fi 是什么”“Wi-Fi 设置页面
  怎么设计”“Usage Access API 怎么用”或“不要打开使用情况访问权限设置”误触工具确认。
- 原 Wi-Fi action-planner fallback 测试保留，通过关闭 direct `plan(input)` 的
  test skill runtime 继续验证 action draft 附加 SkillPlan 的兼容路径。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.wifiActionInputRequestsConfirmationBeforeExecution' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.skillFirstDeviceSettingsBypassActionPlannerAndRequestConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.parameterizedSkillFirstDiscussionInputsRemainAnswersWithoutToolAudit'
```

结果：通过。

## 2026-06-01 Parameterized skill-first draft routing 增量验证

本轮覆盖项：

- `search_maps`、`compose_email`、`create_calendar_event` 的显式命令可由
  built-in Skill runtime 直接规划为待确认工具，不再依赖 action planner 先抽参。
- Action planner 与 Skill runtime 复用同一组参数 parser，继续经过 registry
  validation、safety、audit 和用户确认，不直接执行外部 App。
- 反例覆盖解释类、否定类和编程语境输入，避免“查到错误原因”“不要发邮件”
  或 `add event listener` 误触工具确认。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.skillFirstMapEmailAndCalendarBypassActionPlannerAndRequestConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.parameterizedSkillFirstDiscussionInputsRemainAnswersWithoutToolAudit' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest.validatesRequiredArgumentsForDraftTools' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolSchemaContractTest'
```

结果：通过。

## 2026-06-01 Shared text and scheduled task state boundary 增量验证

本轮覆盖项：

- `SharedInput` 对 Android share intent / in-app picker 的直传文本做换行归一、
  控制字符过滤和 4000 字符上限。
- 超长直传文本在 prompt 中显式标记“分享文本（已截断）”；普通短文本不增加额外前缀。
- 附件 `text/*` 摘录、image OCR 摘录、PDF/Office metadata-only 边界保持不变。
- `cancelScheduled` / `deleteScheduled` 改为 Scheduled-only 条件更新，避免旧快照
  覆盖已进入 `Running` / `Delivered` 等状态的后台任务。
- Fake DAO 与 Room DAO 使用相同条件更新语义，新增取消与提醒投递竞争的回归测试。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.background.ScheduledTaskRepositoryTest' \
  --tests 'com.bytedance.zgx.pocketmind.background.ScheduledTaskRemovalCoordinatorTest' \
  --tests 'com.bytedance.zgx.pocketmind.background.PeriodicCheckSchedulerTest' \
  --tests 'com.bytedance.zgx.pocketmind.background.ReminderAlarmReceiverTest' \
  --tests 'com.bytedance.zgx.pocketmind.multimodal.SharedInputTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.localSharedInputDoesNotEnterLaterRemoteHistory' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteModeRejectsSharedTextPreviewBeforeBuildingPrompt'
```

结果：通过。

## 2026-06-01 Skill-first direct text sharing 增量验证

本轮覆盖项：

- `share_text` 的显式文本请求（如“分享这段文字：...”）可由 built-in Skill
  runtime 直接规划为待确认工具，不再依赖 action planner 判定。
- share 文本解析逻辑抽到 `ShareTextActionParser`，Action planner 与 Skill
  runtime 复用同一组触发和参数提取规则。
- 普通讨论类和否定类输入不触发系统分享工具，避免“分享一下你的看法”、
  “不要分享这段文字”、“how to share this text”或 “don't share this text”
  进入分享确认。
- `ShareTextActionParser` 只在命令头部判断否定/讨论意图，待分享正文中的
  “不要分享”或 “don't share” 不会误杀明确分享请求。
- 剪贴板总结并分享的 Skill-first 路径拒绝否定和讨论输入，避免读取剪贴板
  的组合 skill 被“不要总结剪贴板并分享/如何总结剪贴板并分享”误触发。
- Agent loop skill-first 路径会进入确认，不执行工具；audit 计划事件不记录待分享原文。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.skillFirstShareTextBypassesActionPlannerAndRequestsConfirmation'
```

结果：通过。

## 2026-06-01 Tool private-output policy / special-access restore 增量验证

本轮覆盖项：

- `ToolSpec` 增加私密输出字段策略，`read_clipboard.text`、
  recent screenshot/image OCR 的 `ocrText`、以及 current-screen
  Accessibility 的 `screenText` 由 Tool Registry 统一声明。
- `SkillRunProgressor` 的 private-output fence 与
  `AgentLoopRuntime` 的 trace redaction 复用同一策略来源，避免新增私密工具时
  下游各自硬编码字段名。
- special-access 设置页跳转前保存 pending requirement id；Activity 重建后只从
  当前 pending confirmation 的 requirements 中反查恢复。设置页返回仍只汇报
  special-access 结果，不确认 pending tool、不执行工具。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest' \
  --tests 'com.bytedance.zgx.pocketmind.skill.SkillRunProgressorTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.clipboardObservationBuildsContinuationPromptAndRedactsTrace' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.recentScreenshotOcrObservationBuildsLocalPromptAndRedactsTrace' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.recentImageOcrObservationBuildsLocalPromptAndRedactsTrace' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.currentScreenTextObservationBuildsLocalPromptAndRedactsTrace' \
  --tests 'com.bytedance.zgx.pocketmind.AgentRuntimePermissionPolicyTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.specialAccessReturnUpdatesStatusTextWithoutExecutingTools'
```

结果：通过。

## 2026-06-01 Current-screen Accessibility text snapshot 增量验证

本轮覆盖项：

- 新增受确认保护的 `read_current_screen_text` 工具；它通过 Android
  Accessibility service 读取当前 Accessibility 文本节点快照，并将结果标记为
  `LocalOnly` / `requiresLocalModel=true`。
- 该能力不是截图、不是 OCR、不是像素读取，也不是视觉或语义屏幕理解；
  失败时不应自动退化为截图、OCR 或屏幕扫描。
- raw `screenText` 不进入 Agent trace、tool audit、持久工具观察消息或远程
  模型 prompt；远程模式应阻断自动 continuation，并提示用户切换本地模型或
  手动粘贴愿意上传的内容。
- Accessibility 授权按 special access 建模，不进入 Android runtime permission；
  Manifest 只声明受系统绑定的 `AccessibilityService`，不请求手势、截图或按键过滤能力。
- Skill private-output fence 禁止将 `screenText` 直接绑定到分享或外部工具参数。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolSchemaContractTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.DeviceContextToolExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.RoutingAndValidatingToolExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.AgentRuntimePermissionPolicyTest' \
  --tests 'com.bytedance.zgx.pocketmind.AndroidManifestTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.currentScreenTextObservationBuildsLocalPromptAndRedactsTrace' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteModeProtectsCurrentScreenTextBeforeRemoteContinuation' \
  --tests 'com.bytedance.zgx.pocketmind.skill.SkillRunProgressorTest'

./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest' \
  --tests 'com.bytedance.zgx.pocketmind.skill.SkillRunProgressorTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.DeviceContextToolExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.RoutingAndValidatingToolExecutorTest'

bash -n scripts/doctor.sh scripts/verify_local.sh scripts/install_and_test_device.sh scripts/test_validation_scripts.sh
scripts/test_validation_scripts.sh
git diff --check
scripts/verify_local.sh
```

设备验证：

```bash
scripts/install_and_test_device.sh
```

结果：当前环境没有连接已授权 Android 设备，脚本在执行安装前以
`Connect exactly one authorized Android device, or set ANDROID_SERIAL to select one.`
退出。

## 2026-06-01 Recent image OCR tool 增量验证

本轮覆盖项：

- 新增独立受确认保护的 `read_recent_image_ocr` 工具，用于用户明确要求
  识别最近图片/照片文字时扫描最近图片像素并在本地提取 OCR 摘录。
- `query_recent_files(kind="images")` 继续保持 metadata-only；图片 OCR
  是单独工具，不返回 MediaStore id、URI、路径、原图或像素。
- 默认/最大扫描窗口限制为最近 3 张图片，返回第一条有界 OCR 摘录；
  结果标记 `LocalOnly` / `requiresLocalModel=true`。
- Agent observation 会用本地模型 continuation 处理图片 OCR；远程模式
  阻断自动 continuation，并提示已保护图片 OCR 内容。
- trace/audit 会把 `ocrText` 脱敏；Skill runner 将
  `read_recent_image_ocr.ocrText` 视为私有输出，不能直接绑定到后续
  `share_text` 等外发工具参数。
- Android 13+ 只请求 `READ_MEDIA_IMAGES`，Android 12- 使用
  `READ_EXTERNAL_STORAGE`；权限 rationale 明确像素读取和 OCR 边界。
- 该能力不声明当前屏幕捕获、图片语义理解、任意媒体 OCR 或媒体内容理解。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolSchemaContractTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.RoutingAndValidatingToolExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.DeviceContextToolExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.AgentRuntimePermissionPolicyTest' \
  --tests 'com.bytedance.zgx.pocketmind.skill.SkillRunProgressorTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.recentImageOcrObservationBuildsLocalPromptAndRedactsTrace' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteModeProtectsRecentImageOcrBeforeRemoteContinuation'
```

## 2026-06-01 Preference memory conflict resolution 增量验证

本轮覆盖项：

- `MemoryRepository` 为显式长期偏好新增轻量冲突族识别：回答长短
  (`response-length`) 与回答语言 (`response-language`)。
- 新的同族显式偏好会删除旧的同族偏好记录，再写入当前偏好，避免
  `记住：回答尽量简洁` 与 `记住：回答要详细` 同时进入长期记忆。
- 不同偏好族仍可共存，例如回答长短和回答语言不会相互覆盖。
- 当时 `PocketMindViewModel` 仍在用户消息成功进入会话后持久化 `记住` 偏好；
  冲突替换后长期记忆 UI 和 in-memory 索引同步展示当前偏好。该入口口径已被
  2026-06-01 Local remember control command 增量验证收窄为本地控制命令。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.memory.MemoryRepositoryTest.explicitPreferenceConflictKeyRecognizesResponseFamilies' \
  --tests 'com.bytedance.zgx.pocketmind.memory.MemoryRepositoryTest.conflictingResponseLengthPreferenceReplacesOlderRecord' \
  --tests 'com.bytedance.zgx.pocketmind.memory.MemoryRepositoryTest.unrelatedResponsePreferenceFamiliesCanCoexist' \
  --tests 'com.bytedance.zgx.pocketmind.memory.MemoryRepositoryTest.combinedResponsePreferenceReplacesBothFamilies' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.rememberCommandReplacesConflictingPreferenceMemory'
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.memory.MemoryRepositoryTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest'
bash -n scripts/doctor.sh scripts/verify_local.sh scripts/install_and_test_device.sh scripts/test_validation_scripts.sh
scripts/test_validation_scripts.sh
scripts/verify_local.sh
git diff --check
```

验证层补充：

- `scripts/verify_local.sh` 现在会先执行 shell 语法检查和
  `scripts/test_validation_scripts.sh`，避免设备选择、无设备提前退出、
  `ANDROID_SERIAL` 路径在本地 gate 外悄悄失效。
- README 与真机验收文档不再硬编码 instrumentation 测试数量，要求记录
  runner 实际报告的测试总数。

## 2026-05-31 Skill private-read continuation precedence 增量验证

本轮覆盖项：

- `AgentLoopRuntime` 在工具观察成功后会优先检查当前 `SkillPlan` 是否有依赖该工具的
  `ModelStep`；命中时使用 Skill 声明的 title/instruction/input binding 生成
  continuation prompt，不再被 clipboard/OCR 硬编码 prompt 抢先。
- clipboard/OCR 仍保持本地模型要求和 trace/audit 脱敏；没有声明式 `ModelStep` 的
  one-off 私密读取继续走原有兜底 prompt。
- Room 恢复后的 run input 如果已经是 `[redacted]`，Skill continuation 会回退到
  pending `SkillPlan.request.arguments["input"]`，避免模型 prompt 丢失用户原始请求。
- generic `ModelStep` 会尊重工具结果里的 `privacy=LocalOnly` /
  `requiresLocalModel=true`，即使该模型步骤声明 `keepsSensitiveInputLocal=false`，
  也会要求本地模型继续处理。
- 远程模式遇到非 clipboard/OCR 的 local-only continuation 时，UI 使用“本地工具结果”
  保护文案，不再误报为“剪贴板内容”，也不会调用 remote runtime。
- `READ_RECENT_SCREENSHOT_OCR.ocrText` 直接绑定到后续工具参数会被
  `SkillRunProgressor` 拒绝，和 clipboard private output fence 对齐。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.clipboardSummarySharePlansShareAfterLocalModelResult' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.modelStepOutputBindsToDependentToolStepAndRequestsConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.restoredClipboardSummaryPendingContinuesWithModelAndPlansShareConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.clipboardReadObservationBuildsLocalPromptAndRedactsTrace' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.recentScreenshotOcrObservationBuildsLocalPromptAndRedactsTrace'
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.ocrSkillModelStepTakesPrecedenceOverPrivateReadFallbackPrompt' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.localOnlyToolResultMetadataForcesGenericModelContinuationLocal' \
  --tests 'com.bytedance.zgx.pocketmind.skill.SkillRunProgressorTest.rejectsScreenshotOcrPrivateOutputBindingToToolArgument' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteModeProtectsGenericLocalOnlyContinuationAsLocalToolResult'
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.skill.SkillRunProgressorTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest'
scripts/verify_local.sh
git diff --check
rg -n --hidden -S \
  "(AKIA[0-9A-Z]{16}|ASIA[0-9A-Z]{16}|AIza[0-9A-Za-z_-]{35}|sk-[A-Za-z0-9_-]{20,}|xox[baprs]-[A-Za-z0-9-]{10,}|-----BEGIN (RSA |DSA |EC |OPENSSH |PGP )?PRIVATE KEY-----|password\s*=\s*['\"][^'\"]+['\"]|secret\s*=\s*['\"][^'\"]+['\"]|token\s*=\s*['\"][^'\"]+['\"])" \
  --glob '!build/**' --glob '!**/.gradle/**' --glob '!app/build/**' \
  --glob '!**/src/test/**' --glob '!**/src/androidTest/**' .
```

结果：

- 通过：上述 targeted JVM 回归测试，以及 AgentLoopRuntime、SkillRunProgressor、
  PocketMindViewModel 的全量 targeted JVM 回归。
- 通过：`scripts/verify_local.sh`，覆盖 `testDebugUnitTest`、`lintDebug`、
  `assembleDebug`、`assembleDebugAndroidTest`、`assembleRelease` 和 APK 检查。
- 通过：`git diff --check`。
- 通过：排除测试夹具后的敏感配置扫描无匹配。

## 2026-05-31 Generic Skill model continuation 增量验证

本轮覆盖项：

- `AgentLoopRuntime` 不再只为 clipboard/OCR 生成硬编码 continuation；当
  当前工具属于恢复后的 `SkillPlan` 且后续存在依赖该工具的 `ModelStep` 时，
  会从工具结果绑定模型输入、生成下一段模型 prompt，并继续进入
  `GeneratingAnswer`。
- 模型输出仍通过 `SkillRunProgressor.nextToolAfterModelOutput()` 绑定到后续
  `ToolStep.argumentBindings`，然后重新进入 `AwaitingUserConfirmation`；后续工具
  不会被自动执行。
- 本轮保持既有隐私边界：不持久化完整原始 `SkillRunContinuation` 对象；Room
  只恢复无私密 executable payload 的 active pending confirmation snapshot +
  redacted `SkillPlan`，私密 `outputs/privateOutputRefs/trace` 不进入持久层。
- 多段流程仍覆盖 `web_search -> model -> share_text -> model ->
  open_wifi_settings` 的进程内 continuation。当前 pending payload 最小化策略
  下，若在 `share_text` payload 确认点进程重启，则该 pending 不再恢复，而是
  fail closed。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.skill.SkillRunProgressorTest'
scripts/verify_local.sh
git diff --check
rg -n --hidden -S \
  "(AKIA[0-9A-Z]{16}|ASIA[0-9A-Z]{16}|AIza[0-9A-Za-z_-]{35}|sk-[A-Za-z0-9_-]{20,}|xox[baprs]-[A-Za-z0-9-]{10,}|-----BEGIN (RSA |DSA |EC |OPENSSH |PGP )?PRIVATE KEY-----|password\s*=\s*['\"][^'\"]+['\"]|secret\s*=\s*['\"][^'\"]+['\"]|token\s*=\s*['\"][^'\"]+['\"])" \
  --glob '!build/**' --glob '!**/.gradle/**' --glob '!app/build/**' \
  --glob '!**/src/test/**' --glob '!**/src/androidTest/**' .
```

结果：

- 通过：targeted JVM AgentLoopRuntime 和 SkillRunProgressor 回归测试。
- 通过：`scripts/verify_local.sh`，覆盖 `testDebugUnitTest`、`lintDebug`、
  `assembleDebug`、`assembleDebugAndroidTest`、`assembleRelease` 和 APK 检查。
- 通过：`git diff --check`。
- 通过：排除测试夹具后的敏感配置扫描无匹配。

## 2026-05-31 Reminder PendingIntent identity 增量验证

本轮覆盖项：

- Reminder alarm `PendingIntent` identity 不再只依赖 `task.id.hashCode()`；
  新版 alarm 使用固定 requestCode 和包含 URL-escaped opaque task id 的 Intent
  `data`，因此 `"Aa"` / `"BB"` 这类 Java hash 碰撞 id 不会共享同一个 alarm
  identity。
- reminder 取消会同时尝试新的 data URI identity 和旧版 no-data identity，
  兼容已经由旧构建登记的 alarm。
- reminder boot reschedule 在登记新版 alarm 后会清理旧版 hash-only identity，
  避免升级后新旧 alarm 双重触发。
- 保留 alarm Intent extras 里的 task id 作为 receiver 读取入口；identity 用
  data URI，投递仍以本地 DB task row 为准。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.background.ReminderAlarmIdentityTest' \
  --tests 'com.bytedance.zgx.pocketmind.background.ScheduledTaskRepositoryTest'
./gradlew :app:assembleDebugAndroidTest
scripts/verify_local.sh
git diff --check
rg credential-pattern scan excluding build, .gradle, and test fixtures
```

结果：

- 通过：targeted JVM ReminderAlarmIdentity 和 ScheduledTaskRepository
  reschedule cleanup 回归测试。
- 通过：`assembleDebugAndroidTest`，新增真实 Android `PendingIntent`
  identity instrumentation 测试编译通过；连接设备执行仍属于后续真机验收。
- 通过：`scripts/verify_local.sh`，覆盖 `testDebugUnitTest`、`lintDebug`、
  `assembleDebug`、`assembleDebugAndroidTest`、`assembleRelease` 和 APK 检查。
- 通过：`git diff --check`。
- 通过：排除测试夹具后的敏感配置扫描无匹配。

## 2026-05-31 Recent files cursor boundary 增量验证

本轮覆盖项：

- `AndroidRecentFileProvider` 将 MediaStore cursor 行转换为惰性 metadata
  sequence，并在收集到 `maxCount` 个匹配文件后立即停止读取后续行。
- `RecentFilesToolExecutor` 保留 provider 返回的 `PermissionDenied(reason)`，
  因此 Android 13+ 非媒体文件需要系统文件选择器授权时，用户可见失败原因不再被
  泛化为普通文件权限缺失。
- `ToolRegistry`、动作规划 prompt 和确认摘要都明确 Android 13+ `all` 只表示
  已授权媒体，`documents`、`downloads`、`others` 需要系统文件选择器授权；这类
  非媒体拒绝不会被标记为同一工具可重试。
- `query_recent_files.maxCount` schema 同时覆盖下界和上界拒绝，避免无效计数进入
  provider 层。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.device.RecentFileCollectorTest' \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.DeviceContextToolExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.RoutingAndValidatingToolExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest'
scripts/verify_local.sh
git diff --check
rg credential-pattern scan excluding build, .gradle, and test fixtures
```

结果：

- 通过：targeted JVM RecentFile collector、DeviceContext executor、
  ActionPlanner、Routing/Validating executor 和 ToolRegistry 回归测试。
- 通过：`scripts/verify_local.sh`，覆盖 `testDebugUnitTest`、`lintDebug`、
  `assembleDebug`、`assembleDebugAndroidTest`、`assembleRelease` 和 APK 检查。
- 通过：`git diff --check`。
- 通过：排除测试夹具后的敏感配置扫描无匹配。

## 2026-05-31 Private read safety invariant 增量验证

本轮覆盖项：

- `SafetyPolicy` 对包含 `ReadsClipboard`、`ReadsContacts`、`ReadsFiles`、
  `ReadsCalendar` 或 `ReadsDeviceContext` 的工具增加强制确认 invariant。
- 如果未来私密读取工具的 `ToolSpec.confirmationPolicy` 被误配为 `Optional` 或
  `NotRequired`，SafetyPolicy 会直接 `Reject`，而不是在未确认状态下放行。
- `ToolRegistryTest` 增加全量 registry invariant，当前所有私密读取工具都必须声明
  `ConfirmationPolicy.Required`。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.safety.SafetyPolicyTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest'
scripts/verify_local.sh
git diff --check
rg credential-pattern scan excluding build, .gradle, and test fixtures
```

结果：

- 通过：targeted JVM SafetyPolicy 和 ToolRegistry 私密读取确认边界回归测试。
- 通过：`scripts/verify_local.sh`，覆盖 `testDebugUnitTest`、`lintDebug`、
  `assembleDebug`、`assembleDebugAndroidTest`、`assembleRelease` 和 APK 检查。
- 通过：`git diff --check`。
- 通过：排除测试夹具后的敏感配置扫描无匹配。

## 2026-05-31 Reminder task id collision 增量验证

本轮覆盖项：

- `ScheduledTaskRepository.createReminder()` 不再用 `task-$timestamp-$titleHash`
  生成 id，避免同一毫秒、同标题提醒覆盖同一条本地记录。
- reminder task id 改为由 `UUID` 风格工厂生成，并在持久化前检查本地碰撞；测试
  注入始终重复的 id factory 时，第二条提醒会重试并保留为独立记录。
- rollback/recovery metadata 继续只暴露不透明 task id，但不再因标题相同而指向
  被覆盖的提醒。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.background.ScheduledTaskRepositoryTest'
scripts/verify_local.sh
git diff --check
rg credential-pattern scan excluding build, .gradle, and test fixtures
```

结果：

- 通过：targeted JVM ScheduledTaskRepository reminder id collision 回归测试。
- 通过：`scripts/verify_local.sh`，覆盖 `testDebugUnitTest`、`lintDebug`、
  `assembleDebug`、`assembleDebugAndroidTest`、`assembleRelease` 和 APK 检查。
- 通过：`git diff --check`。
- 通过：排除测试夹具后的敏感配置扫描无匹配。

## 2026-05-31 Stale reminder alarm delivery 增量验证

本轮覆盖项：

- `ReminderAlarmReceiver` 不再读取 alarm Intent 中的 title/body extras；新的
  reminder PendingIntent 只携带不透明 task id。
- `ReminderAlarmDeliveryHandler` 投递前通过 `ScheduledTaskRepository` 重新读取
  本地任务，并通过 DAO 条件更新只允许仍存在、类型为 `Reminder`、状态为
  `Scheduled` 的任务进入 `Running`。
- 本地 DB 记录成为提醒标题/正文的唯一投递来源；旧 alarm 即使携带过期 extras，
  也不会覆盖当前持久化任务内容。
- missing、`Cancelled`、`Deleted`、`Failed` 等 stale alarm 不发通知、不创建新
  状态，也不把终态任务改回 `Running` / `Delivered`。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.background.ReminderAlarmReceiverTest' \
  --tests 'com.bytedance.zgx.pocketmind.background.ScheduledTaskRepositoryTest' \
  --tests 'com.bytedance.zgx.pocketmind.background.ScheduledTaskRemovalCoordinatorTest' \
  --tests 'com.bytedance.zgx.pocketmind.background.PeriodicCheckSchedulerTest'
scripts/verify_local.sh
git diff --check
rg credential-pattern scan excluding build, .gradle, and test fixtures
```

结果：

- 通过：targeted JVM reminder alarm delivery stale-boundary、repository
  delivery-start、removal coordinator 和 periodic scheduler 回归测试。
- 通过：`scripts/verify_local.sh`，覆盖 `testDebugUnitTest`、`lintDebug`、
  `assembleDebug`、`assembleDebugAndroidTest`、`assembleRelease` 和 APK 检查。
- 通过：`git diff --check`。
- 通过：排除测试夹具后的敏感配置扫描无匹配。

## 2026-05-31 Device validation serial selection 增量验证

本轮覆盖项：

- `scripts/install_and_test_device.sh` 支持通过 `ANDROID_SERIAL` 在多台已授权
  设备/模拟器中选择目标；所有后续 `adb shell`、安装、instrumentation 和启动命令
  都绑定到该序列号。
- 未指定 `ANDROID_SERIAL` 时仍要求恰好一台 `device` 状态目标；无已授权
  设备或多台已授权设备会在 Gradle 构建、APK 安装和 instrumentation 前退出。
- 指定 `ANDROID_SERIAL` 时，目标必须存在且状态为 `device`；`unauthorized` /
  `offline` / missing serial 都会在 Gradle 构建、APK 安装和 instrumentation 前
  退出。
- `scripts/doctor.sh --device` 的输出收窄为 device toolchain check，避免把
  SDK `adb` 存在误读为“已连接可验收设备”。
- README、真机验收清单和 Agent core 文档同步说明无设备预期行为、
  `ANDROID_SERIAL` 用法，以及完整回归报告需要记录 instrumentation runner
  报告的测试总数和通过结果。

验证命令：

```bash
bash -n scripts/doctor.sh scripts/verify_local.sh scripts/install_and_test_device.sh scripts/test_validation_scripts.sh
scripts/test_validation_scripts.sh
scripts/doctor.sh
scripts/doctor.sh --device
scripts/install_and_test_device.sh
scripts/verify_local.sh
git diff --check
rg credential-pattern scan excluding build, .gradle, and test fixtures
```

结果：

- 通过：`bash -n` 覆盖 `doctor.sh`、`verify_local.sh`、
  `install_and_test_device.sh` 和 `test_validation_scripts.sh`。
- 通过：`scripts/test_validation_scripts.sh`，覆盖 fake SDK 下 local doctor 无
  adb 通过、device doctor 缺 adb 失败、无设备 / unauthorized / offline /
  多已授权设备 / missing serial 都不调用 Gradle，以及单设备和
  `ANDROID_SERIAL` 选择设备的 happy path。
- 通过：`scripts/doctor.sh`。
- 通过：`scripts/doctor.sh --device`，确认 SDK `adb` 存在且输出为 device
  toolchain check。
- 预期失败：`scripts/install_and_test_device.sh` 在当前真实环境无已授权设备时
  退出；输出设备列表后停止，没有进入 Gradle 构建、APK 安装或 instrumentation。
- 通过：`scripts/verify_local.sh`，覆盖 `testDebugUnitTest`、`lintDebug`、
  `assembleDebug`、`assembleDebugAndroidTest`、`assembleRelease` 和 APK 检查。
- 通过：`git diff --check`。
- 通过：排除测试夹具后的敏感配置扫描无匹配。

## 2026-05-31 Skill progression boundary 增量验证

本轮覆盖项：

- 新增纯 Kotlin `SkillRunProgressor`，集中处理 Skill plan 校验、step limit、
  tool/model argument binding、工具结果输出整理和 private output fence。
- `SkillRunExecutor` 改为复用 `SkillRunProgressor`，保留工具执行、模型执行、
  safety/registry gate、trace 和 continuation 这些副作用边界。
- `AgentLoopRuntime.observeModelResult()` 的模型输出续跑改为复用
  `SkillRunProgressor.nextToolAfterModelOutput()`，不再维护独立的
  `nextToolStepForModelOutput` / binding parser。
- `read_clipboard.text` 与 `read_recent_screenshot_ocr.ocrText` 都由 progressor
  统一标记为私有工具输出，不能直接绑定到后续工具参数。
- 该切片不持久化完整 `SkillRunContinuation`，仍沿用现有 pending confirmation
  snapshot 恢复边界。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.skill.SkillRunProgressorTest' \
  --tests 'com.bytedance.zgx.pocketmind.skill.SkillRunExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.modelStepOutputBindsToDependentToolStepAndRequestsConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.modelStepBindingRejectsMissingOutputBeforeConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.modelStepBindingCannotDirectlyExposePrivateToolOutputToShare' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.clipboardSummarySharePlansShareAfterLocalModelResult' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.blankCompositeModelResultFailsWithoutPlanningShare'

scripts/verify_local.sh
git diff --check
rg credential-pattern scan excluding build, .gradle, and test fixtures
```

结果：

- 通过：targeted JVM Skill progressor、Skill executor 和 Agent loop model-output
  progression 回归测试。
- 通过：`scripts/verify_local.sh`，覆盖 `testDebugUnitTest`、`lintDebug`、
  `assembleDebug`、`assembleDebugAndroidTest`、`assembleRelease` 和 APK 检查。
- 通过：`git diff --check`。
- 通过：排除测试夹具后的敏感配置扫描无匹配。

## 2026-05-31 本地验证脚本分层增量验证

本轮覆盖项：

- `scripts/doctor.sh` 默认进入 local 模式，只检查 JDK、Android SDK 36、
  build-tools/aapt 和 Gradle wrapper，不再要求 `adb`。
- `scripts/doctor.sh --device` 保留设备/模拟器验收前的 `adb` 检查。
- `scripts/verify_local.sh` 调用 `doctor --local`，让 JVM 单测、lint、APK 构建和
  APK 内容检查不被缺失 `adb` 阻断。
- `scripts/install_and_test_device.sh` 先调用 `doctor --device`，继续要求
  Android SDK platform-tools/adb、单台已授权设备、arm64-v8a 和设备空间。
- README 与真机验收文档同步区分 local verification 与 device/emulator validation。

验证命令：

```bash
for f in scripts/doctor.sh scripts/verify_local.sh scripts/install_and_test_device.sh; do bash -n "$f"; done
scripts/doctor.sh
scripts/doctor.sh --local
scripts/doctor.sh --device
# 临时 SDK fixture：仅复制 android-36 与 aapt，不提供 platform-tools/adb。
ANDROID_SDK_ROOT="$TMP_SDK" ANDROID_HOME="$TMP_SDK" scripts/doctor.sh --local
ANDROID_SDK_ROOT="$TMP_SDK" ANDROID_HOME="$TMP_SDK" scripts/doctor.sh --device
scripts/verify_local.sh
scripts/install_and_test_device.sh
git diff --check
rg credential-pattern scan excluding build, .gradle, and test fixtures
```

结果：

- 通过：三个脚本 `bash -n`。
- 通过：`scripts/doctor.sh`、`scripts/doctor.sh --local` 和
  `scripts/doctor.sh --device`；当前 SDK 下存在
  `/Users/bytedance/Library/Android/sdk/platform-tools/adb`。
- 通过：临时 SDK 中只提供 Android platform 与 `aapt`、不提供 platform-tools/adb
  时，`doctor --local` 通过。
- 预期失败：同一临时 SDK 下 `doctor --device` 报告缺少 `adb`，证明设备模式仍保留硬性检查。
- 通过：`scripts/verify_local.sh`，覆盖 `testDebugUnitTest`、`lintDebug`、
  `assembleDebug`、`assembleDebugAndroidTest`、`assembleRelease`、APK model
  artifact 检查、badging 检查、release size 检查、immutable model URL 检查和
  plaintext remote API key 检查。
- 预期失败：`scripts/install_and_test_device.sh` 通过 device doctor 后因当前没有已授权设备而停止，没有进入安装或 instrumentation。
- 通过：`git diff --check`。
- 通过：排除测试夹具后的敏感配置扫描无匹配。

## 2026-05-31 最近截图 OCR 增量验证

本轮覆盖项：

- 新增受确认保护的 `read_recent_screenshot_ocr` 工具：仅在用户明确要求识别最近截图文字时，读取最近 1 张截图并在本地提取 OCR 摘录。
- `query_recent_files(kind="screenshots")` 继续保持 metadata-only；截图 OCR 是独立工具，不返回 MediaStore id、URI、路径、原图或像素。
- OCR 文本标记为 `LocalOnly`，进入本地 continuation 前会在 trace/audit/persisted observation 中脱敏；远程模式不会自动发送截图 OCR 内容。
- Skill runner 将 `ocrText` 视为私有工具输出，不能直接绑定到后续工具参数；失败路径不回显底层异常里的 URI/path。
- Android 权限说明改为明确披露会读取最近 1 张截图像素并提取 OCR；工具风险等级升为 `MediumDraftOrNavigation`。
- MediaStore 查询优先按 `DATE_ADDED` 排序，再按修改时间兜底，减少编辑旧截图后被误当作最近截图的概率；仍不声明当前屏幕捕获或图片语义理解。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.skill.SkillRunExecutorTest.recentScreenshotOcrTextCannotBindDirectlyToLaterToolArgument' \
  --tests 'com.bytedance.zgx.pocketmind.AgentRuntimePermissionPolicyTest.recentScreenshotOcrPermissionRationaleDisclosesPixelAndOcrRead' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest.exposesSpecsForSupportedActionsWithConfirmationRequired' \
  --tests 'com.bytedance.zgx.pocketmind.tool.DeviceContextToolExecutorTest.recentScreenshotOcrPermissionDeniedAndFailureAreStructured' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteModeProtectsRecentScreenshotOcrBeforeRemoteContinuation' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.recentScreenshotOcrObservationBuildsLocalPromptAndRedactsTrace'

./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
git diff --check
rg credential-pattern scan excluding build, .gradle, and test fixtures
adb devices -l
```

结果：

- 通过：targeted JVM 最近截图 OCR 隐私边界、权限文案、registry、ViewModel 远程保护和 Agent trace 脱敏回归。
- 通过：完整 `:app:compileDebugKotlin :app:compileDebugUnitTestKotlin
  :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest`。
- 通过：`:app:lintDebug`。
- 通过：`:app:assembleDebug :app:assembleDebugAndroidTest`。
- 通过：`git diff --check`。
- 通过：排除测试夹具后的敏感配置扫描无匹配。
- 未执行模拟器回归：当前环境缺少 `adb` 命令。

## 2026-05-31 Share target MIME, wording, and skill restore 增量验证

本轮覆盖项：

- Android share-target 的 `ACTION_SEND` / `ACTION_SEND_MULTIPLE` MIME 覆盖与
  in-app document picker 对齐，补齐 RTF、legacy Office 和 OOXML Office 类型。
- 该轮的 Office 与 RTF 分享仍复用当时的 `SharedInput` 只读元数据边界，
  不读取正文、不做 PDF/Office 解析。
- `query_recent_notifications` 的草稿文案收窄为“当前应用最近通知摘要”，与
  provider 只读取本应用 active notification 摘要的实现一致，不再暗示“未读”或
  跨 App 通知读取。
- Room 恢复 pending skill confirmation 时，会校验持久化 `SkillPlan` 是否包含
  当前待确认的 tool request id 和 tool name；损坏或旧格式行会被跳过，避免恢复
  到无法由 skill plan 解释的确认卡。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.AndroidManifestTest.shareTargetsAcceptPickerSupportedDocumentMimeTypes' \
  --tests 'com.bytedance.zgx.pocketmind.multimodal.SharedInputTest.officeAndRtfAttachmentsRemainMetadataOnlyWithoutPreview' \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest.recentNotificationSummaryMatchesCurrentAppOnlyBoundary' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest.exposesSpecsForSupportedActionsWithConfirmationRequired' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentTraceStoreTest.roomStoreSkipsPendingSkillPlanThatDoesNotContainPendingToolRequest'

./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
git diff --check
rg credential-pattern scan excluding build, .gradle, and test fixtures
adb devices -l
```

结果：

- 通过：targeted JVM share MIME、metadata-only、notification wording 和 skill
  pending restore guard 测试。
- 通过：完整 `:app:compileDebugKotlin :app:compileDebugUnitTestKotlin
  :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest`。
- 通过：`:app:lintDebug`。
- 通过：`:app:assembleDebug :app:assembleDebugAndroidTest`。
- 通过：`git diff --check`。
- 通过：排除测试夹具后的敏感配置扫描无匹配；测试目录中的命中为 redaction
  回归用例中的 dummy 字符串。
- 未执行模拟器回归：当前环境缺少 `adb` 命令。

## 2026-05-31 Usage Access settings return 增量验证

本轮覆盖项：

- `ActionDraftSheet` 的特殊授权入口现在传递完整 `SpecialAccessRequirement`，
  而不是只传 settings action 字符串。
- `MainActivity` 使用 `ActivityResultContracts.StartActivityForResult` 打开
  Usage Access 设置页；用户返回 App 后通过 AppOps 重新检查
  `OPSTR_GET_USAGE_STATS`，并把结果写入 ViewModel 状态。
- 返回状态只更新 UI 文案，不确认 pending tool、不执行工具、不读取前台 App
  provider；真正执行仍必须点击确认卡的确认按钮。
- `SPECIAL_ACCESS_USAGE_STATS` 成为稳定 id，避免 UI / Activity / 测试使用裸字符串。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.specialAccessReturnUpdatesStatusTextWithoutExecutingTools' \
  --tests 'com.bytedance.zgx.pocketmind.AgentRuntimePermissionPolicyTest.foregroundAppDeclaresUsageAccessAsSpecialAccessNotRuntimePermission'

./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
git diff --check
rg credential-pattern scan excluding build and .gradle outputs
adb devices -l
```

结果：

- 通过：targeted JVM Usage Access settings return 状态测试。
- 通过：完整 `:app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest`。
- 通过：`:app:lintDebug`。
- 通过：`:app:assembleDebug :app:assembleDebugAndroidTest`。
- 通过：`git diff --check`。
- 通过：敏感配置扫描无匹配。
- 未执行模拟器回归：当前环境缺少 `adb` 命令。

## 2026-05-31 Agent stale in-flight recovery 增量验证

本轮覆盖项：

- `AgentTraceStore.failStaleInFlightRuns()` 会在进程重启恢复边界把无法安全继续的
  `Created` / `LoadingContext` / `Planning` / `ExecutingTool` / `RetryingTool` /
  `Observing` / `GeneratingAnswer` run 标记为 `Failed`，并追加 `Failed`
  trace step。
- `AwaitingUserConfirmation` 不会被清理，因为它有 `pending_agent_confirmations`
  作为明确恢复快照，仍可恢复到待确认 UI。
- `PocketMindViewModel.restoreStartupState()` 启动时执行该 stale run 清理，
  避免 Agent trace UI 长期展示已经不可能继续的运行中状态。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentTraceStoreTest.roomStoreFailsStaleInFlightRunsButKeepsPendingConfirmationsOnStartup'

./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
git diff --check
rg credential-pattern scan excluding build and .gradle outputs
adb devices -l
```

结果：

- 通过：targeted JVM stale in-flight Agent run 恢复边界测试。
- 通过：完整 `:app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest`。
- 通过：`:app:lintDebug`。
- 通过：`:app:assembleDebug :app:assembleDebugAndroidTest`。
- 通过：`git diff --check`。
- 通过：敏感配置扫描无匹配。
- 未执行模拟器回归：当前环境缺少 `adb` 命令。

## 2026-05-31 Reminder recovery confirmation entry 增量验证

本轮覆盖项：

- 聊天输入区上方新增 latest recovery entry，展示最近一次提醒 observation 提供的
  `AgentRecoveryAction`，点击后只进入确认流程，不直接调用后台任务取消。
- `AssistantRouter.requestRecoveryAction()` 会为 typed reminder recovery 创建新的
  Agent run，重新执行工具 schema 校验、安全策略、`ToolPlanned` /
  `ConfirmationRequested` audit，并保存 `pending_agent_confirmations`。
- ViewModel 将 recovery route 转成普通 `PendingAgentConfirmation`；确认前不执行
  `cancel_reminder`，确认后才走现有 `confirmAgentConfirmation()`、
  `UserConfirmed`、`ToolObserved` 链路。
- 工具 observation 完成后补齐 `isBusy=false` / `isGenerating=false`，避免已完成的
  工具结果阻塞后续 recovery 入口。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.reminderRecoveryActionRequestsAuditedCancelConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.reminderUndoEntryCreatesPendingCancelConfirmationAndDoesNotExecuteUntilConfirmed'

./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
git diff --check
rg credential-pattern scan excluding build and .gradle outputs
adb devices -l
```

结果：

- 通过：targeted JVM reminder recovery confirmation entry 回归测试。
- 通过：完整 `:app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest`。
- 通过：`:app:lintDebug`。
- 通过：`:app:assembleDebug :app:assembleDebugAndroidTest`。
- 通过：`git diff --check`。
- 通过：敏感配置扫描无匹配。
- 未执行模拟器回归：当前环境缺少 `adb` 命令。

## 2026-05-31 Semantic memory runtime boundary 增量验证

本轮覆盖项：

- `ModelRepository` 暴露 `verifiedMemoryEmbeddingModelPath()`，只返回已存在、已通过
  recommended 校验且 capability 为 `MemoryEmbedding` 的模型路径；路径选择要求
  DB 中的 catalog size/revision/SHA-256 evidence 与当前文件 size/SHA-256 都匹配，
  Chat/Action、未校验、缺文件、替换文件或伪装成 memory asset 的自定义模型不会被
  误用为语义记忆 runtime。
- `MemoryRepository` 新增 `SemanticMemoryRuntimeController`，可在默认 hash runtime
  与注入的 semantic runtime 间切换；切换时会重算当前 memory entry embedding。
- 生产默认仍不声明语义召回已启用。安装 memory model asset 不等于 runtime 已接入；
  生产 model-path 路径上，只有 controller 成功切到 `supportsSemanticRecall=true`
  的 runtime 才产生 `MemoryRecallMode.Semantic` 命中。
- Production `AppContainer` 已注入 fail-closed LiteRT embedding runtime factory；
  verified memory asset 在当前 SDK 路径上会报告 runtime load failed 并回退轻量索引。
- `PocketMindViewModel` 在 memory rebuild 前同步 verified memory model path，确保
  启动/模型校验后的索引使用当前 runtime 边界，同时不要求聊天模型加载。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.memory.MemoryRepositoryTest.semanticRuntimeControllerSwitchesBetweenFallbackAndSemanticRuntime' \
  --tests 'com.bytedance.zgx.pocketmind.memory.MemoryRepositoryTest.semanticRuntimeFactoryReturningNullFallsBackAndReembedsExistingEntries' \
  --tests 'com.bytedance.zgx.pocketmind.memory.MemoryRepositoryTest.switchingSemanticRuntimeReembedsExistingEntriesWithNewRuntime' \
  --tests 'com.bytedance.zgx.pocketmind.memory.MemoryRepositoryTest.memoryModelPathDoesNotEnableSemanticRecallWithoutRuntimeSupport' \
  --tests 'com.bytedance.zgx.pocketmind.data.ModelRepositoryPathTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.restoreStartupStateSyncsVerifiedMemoryModelBeforeRebuildingMemoryIndex'
```

## 2026-05-31 Task-state memory sync 增量验证

本轮覆盖项：

- ViewModel 会把仍处于 `Scheduled` / `Running` 的后台任务同步为稳定 id 的
  `TaskState` 长期记忆，让 Agent 可以召回当前任务状态。
- 自动任务状态记忆只保存任务类型、状态、触发时间和不透明任务记录 id；提醒标题、
  正文、工具参数、prompt、远程响应不写入长期记忆、长期记忆 UI 或远程上下文。
- 后台任务取消、完成、失败、删除或从活跃列表消失时，对应自动 `TaskState`
  记忆会被遗忘；手动创建的非 auto-managed task-state 记录不受此同步清理。
- Room-backed Agent trace 会在持久化 `agent_runs.input` 时脱敏完整原始
  prompt；当前进程内仍保留 raw run input 和 active pending confirmation 的
  raw snapshot，Room 只保留声明安全参数，不再持久化 bounded next-action suffix。
- 持久 trace 的 summary、JSON 预览与 allowlisted metadata value 复用 audit
  redactor，对工具 reason、draft title、工具观察 summary、assistant preview
  中的 key/token/email/bearer 片段做脱敏。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.memory.MemoryRepositoryTest.taskStateMemoryRecordIdIsStableForWhitespace' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.restoreStartupStateIndexesScheduledTasksAsForgettableTaskState' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.backgroundTaskStateMemoryDoesNotEnterRemotePromptOrHistory' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.cancelBackgroundTaskForgetsTaskStateMemory' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.refreshBackgroundTasksDropsTerminalTaskStateMemory' \
  --tests 'com.bytedance.zgx.pocketmind.audit.ToolAuditEventTest.sanitizedSummaryRedactsGenericTokenAndKeyAssignments' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentTraceStoreTest.roomStorePersistsRunAndStepSummariesWithoutRawToolArguments' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentTraceStoreTest.roomStoreRedactsSensitiveTraceTextAcrossSummariesAndJson' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentTraceStoreTest.roomStoreRedactsAllowlistedCompletionMetadataValues' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentTraceStoreTest.roomStoreFailsPayloadPendingConfirmationWithoutPuttingRawArgumentsInTrace'
```

## 2026-06-01 Task-state memory suppression 增量验证

本轮覆盖项：

- 用户显式遗忘或清空仍处于 `Scheduled` / `Running` 的 auto-managed
  `TaskState` 长期记忆后，系统会持久化隐藏 suppression marker。
- 后续后台任务 refresh、startup sync 或 `sendMessage` 触发的 task-state sync
  会跳过被 suppress 的记录，确保已忘记的任务状态不会重新出现在长期记忆 UI、
  召回索引或远程上下文中。
- Hidden suppression marker 不进入 `savedRecords()`、memory rebuild 或长期记忆
  UI；普通 terminal/missing task-state 清理语义保持不变。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.memory.MemoryRepositoryTest.suppressedTaskStateRecordsAreHiddenAndNotIndexed' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.forgetActiveTaskStateMemoryDoesNotReappearOnRefreshOrChat' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.clearLongTermMemorySuppressesActiveTaskStateMemoryResync'
```

## 2026-06-01 Emulator verification helper 增量验证

本轮覆盖项：

- 新增 `scripts/verify_emulator.sh`，作为 emulator-only 验证入口；它复用
  `doctor --device` 和 `install_and_test_device.sh`，但只允许 `emulator-*`
  目标，避免 instrumentation 误跑到真机。
- 未指定 `ANDROID_SERIAL` 时，脚本要求恰好一台已授权模拟器；指定 serial 时，
  目标必须是 `device` 状态的 emulator serial。
- 支持 `AVD_NAME=...` 先启动 AVD，等待 `sys.boot_completed=1` 后记录 serial、
  API、ABI、AVD 名称和 `CLEAN_DEVICE`，再执行 build/install/instrumentation。
- 失败时尽量收集截图、UI dump 和短 logcat 到 `build/verification/`，用于模拟器
  回归排查。

验证命令：

```bash
bash -n scripts/doctor.sh scripts/verify_local.sh scripts/install_and_test_device.sh scripts/verify_emulator.sh scripts/test_validation_scripts.sh
scripts/test_validation_scripts.sh
git diff --check
rg credential-pattern scan excluding build, .gradle, and test fixtures
```

结果：

- 通过：`bash -n` 覆盖 `doctor.sh`、`verify_local.sh`、
  `install_and_test_device.sh`、`verify_emulator.sh` 和
  `test_validation_scripts.sh`。
- 通过：`scripts/test_validation_scripts.sh`，覆盖 fake SDK 下 emulator helper
  拒绝 physical serial、拒绝仅真机目标、选择唯一授权 emulator、启动指定
  AVD，以及继续复用 install helper 的安装路径。
- 未执行真实模拟器 instrumentation：当前切片只固化脚本入口和 fake adb/emulator
  选择边界；真实设备/模拟器回归需在有可启动 AVD 的环境执行
  `ANDROID_SERIAL=emulator-5554 scripts/verify_emulator.sh` 或
  `AVD_NAME=<name> scripts/verify_emulator.sh`。

## 2026-06-01 Reminder catch-up trigger persistence 增量验证

本轮覆盖项：

- 开机重排过期 reminder 时，`ReminderRescheduler` 会把实际安排的 catch-up
  alarm 时间条件写回 `scheduled_tasks.triggerAtMillis`，避免后台任务列表、
  周期检查和 auto-managed `TaskState` 长期记忆继续看到过去的触发时间。
- 写回使用 `Reminder + Scheduled` 条件更新；如果任务已被取消、投递或并发进入
  终态，不通过普通 upsert 覆盖状态。
- alarm 安排失败时仍标记任务 `Failed`，且不把 catch-up 时间误写入失败任务。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.background.ScheduledTaskRepositoryTest' \
  --tests 'com.bytedance.zgx.pocketmind.background.ScheduledTaskRemovalCoordinatorTest' \
  --tests 'com.bytedance.zgx.pocketmind.background.PeriodicCheckSchedulerTest' \
  --tests 'com.bytedance.zgx.pocketmind.background.ReminderAlarmReceiverTest'
git diff --check
rg credential-pattern scan excluding build, .gradle, and test fixtures
```

## 2026-06-01 Skill model dependency guard 增量验证

本轮覆盖项：

- `SkillRunProgressor.nextToolAfterModelOutput()` 在绑定模型输出到后续
  `ToolStep` 前，会确认该工具声明的所有依赖都已满足。
- 如果后续工具还依赖另一个未请求/未完成的前置步骤，进度器返回 rejected，
  `AgentLoopRuntime` 进入 `Failed`，不生成新的外发工具确认卡。
- 私密输出 fence、缺失 binding 失败和正常模型输出绑定路径保持不变。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.skill.SkillRunProgressorTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.modelStepBindingRejectsUnmetDependenciesBeforeConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.modelStepBindingRejectsMissingOutputBeforeConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.modelStepOutputBindsToDependentToolStepAndRequestsConfirmation'
git diff --check
rg credential-pattern scan excluding build, .gradle, and test fixtures
```

## 2026-05-31 Reminder rollback metadata 增量验证

本轮覆盖项：

- `schedule_reminder` 成功结果新增受限 recovery metadata：
  `recoveryToolName=cancel_reminder` 和 `recoveryTaskId`，让 Agent/UI/audit
  层能识别该后台任务的回滚工具入口。
- Agent trace 只持久化 recovery tool 名称和 task id，不记录提醒标题、正文或
  其他未 allowlist 的任务内容。
- 现有 `cancel_reminder` 仍是显式工具，需要独立确认和 scheduler 取消路径。
  它只有在仍处于 `Scheduled` 的任务成功取消平台调度并更新本地状态后才返回成功；
  missing、已送达、已取消或本地状态竞态变化会作为 non-retryable stale
  rollback 失败返回，不声称回滚成功。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionExecutorTest.schedulesReminderThroughBackgroundScheduler' \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionExecutorTest.reportsStaleReminderCancellationAsNonRetryableInvalidRequest' \
  --tests 'com.bytedance.zgx.pocketmind.background.ScheduledTaskRemovalCoordinatorTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentTraceStoreTest.roomStorePersistsReminderRecoveryMetadataWithoutReminderContent'
```

## 2026-05-31 Reminder typed recovery action 增量验证

本轮覆盖项：

- `schedule_reminder` 成功 observation 会把 allowlisted
  `recoveryToolName=cancel_reminder` / `recoveryTaskId` 提升为 typed
  `AgentRecoveryAction`，而不是只停留在通用 `ToolResult.data` 字段。
- typed recovery action 只接受 `schedule_reminder -> cancel_reminder(taskId)`
  这一条受控路径；task id 必须是安全的 `task-*` 形式。
- ViewModel 将最新 typed recovery action 写入 UI state，为后续显式“撤销提醒”
  入口提供结构化数据源；本轮不声称已完成可点击 UI。
- observation 文案只展示 bounded task id，不展示提醒标题、正文或其他任务内容。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.reminderObservationSurfacesBoundedRecoveryHint' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.reminderObservationIgnoresUnsafeRecoveryMetadata' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.reminderObservationStoresTypedRecoveryActionForUi'

./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest
git diff --check
rg credential-pattern scan excluding build and .gradle outputs
adb devices -l
```

结果：

- 通过：targeted JVM typed recovery action / unsafe metadata 回归测试。
- 通过：完整 `:app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest`。
- 通过：`git diff --check`。
- 通过：敏感配置扫描无匹配。
- 未执行模拟器回归：当前环境缺少 `adb` 命令。

## 2026-05-31 Generic Skill model-step binding 增量验证

本轮覆盖项：

- Agent loop 不再按 `clipboard_summary_share_skill` 特判模型输出续跑；任意声明式
  Skill 只要 `ToolStep` 依赖 `ModelStep`，就可通过 `argumentBindings` 绑定模型输出并进入下一次确认。
- 绑定出的工具请求仍走 ToolRegistry 校验、SafetyPolicy、trace/audit 和
  `AwaitingUserConfirmation`，不会因为前一个工具已确认而直接执行。
- 缺失的模型输出 binding 会 fail closed；恶意将 `read_clipboard.text` 等私密工具输出直接绑定到
  `share_text.text` 不会产生分享确认，也不会把原始剪贴板写入 trace/audit/pending。
- Orchestrator 恢复第二个待确认动作时保留新的 `share_text` request id 和模型摘要参数，不复用旧
  `read_clipboard` request id。
- Room-backed pending 恢复覆盖到第二个 `share_text` 确认点：重启后旧
  `read_clipboard` request id 不能再次确认、观察或清空 pending，确认新的
  `share_text` request 后可完成 run，持久 trace 不写入原始剪贴板。
- 恢复出的 `share_text` pending 可以包含模型生成的待分享摘要，用于确认卡预览；
  该 payload 不进入普通 trace/audit 摘要，且 ViewModel 在恢复或伪造旧确认时
  不执行工具，只有当前 pending confirmation 才会打开分享面板。
- `SkillRunExecutor` 同步补上私密输出直绑保护：`read_clipboard.text` 等
  private output 可以进入本地 model step，但不能通过 `ToolStep.argumentBindings`
  直接成为后续外发工具参数；违规 plan 会 fail closed，不产生 `share_text`
  pending，也不暴露原文。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.clipboardSummarySharePlansShareAfterLocalModelResult' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.modelStepOutputBindsToDependentToolStepAndRequestsConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.modelStepBindingRejectsMissingOutputBeforeConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.modelStepBindingCannotDirectlyExposePrivateToolOutputToShare' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.compositeSkillIgnoresOldRequestIdsAfterShareIsPendingOrExecuting' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.restoredClipboardSummarySharePendingFailsClosedAfterRestart' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AssistantOrchestratorTest.clipboardSummaryShareAdvancesFromModelOutputToShareConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.restoredSharePendingPreviewDoesNotExecuteUntilCurrentConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.skill.SkillRunExecutorTest.privateToolOutputCannotBindDirectlyToLaterToolArgument'
```

## 2026-05-31 Launch-only external result 增量验证

本轮覆盖项：

- `ToolResult` 可识别 `completionState=ExternalActivityOpened` 且
  `completionVerified=false` 的外部界面启动结果。
- Agent observation 对未验证外部启动使用“外部界面已打开，最终结果未验证”文案，
  且不会基于该结果自动规划下一步工具。
- Tool audit 和 ViewModel UI 状态不再把分享面板、草稿页、外部 Activity 启动
  误写成“工具执行成功”。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.unverifiedExternalLaunchDoesNotAutoPlanNextTool' \
  --tests 'com.bytedance.zgx.pocketmind.audit.ToolAuditRepositoryTest.unverifiedExternalLaunchAuditDoesNotClaimExecutionSuccess' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.unverifiedExternalLaunchShowsLaunchOnlyStatus'
```

## 2026-05-31 SkillRun cancellation state 增量验证

本轮覆盖项：

- `SkillRunExecutor` 新增显式 `Cancelled` 状态，pending continuation 可以被取消而不是被记录为失败。
- 多步 Skill 取消会停在当前待确认工具前，不调用 `ToolExecutor` 执行后续工具。
- 取消后的公开 outputs/trace 仍过滤私密工具输出，例如已确认读取过的剪贴板原文不会从取消结果泄漏。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.skill.SkillRunExecutorTest'
```

## 2026-05-31 Reminder Skill-first 增量验证

本轮覆盖项：

- 提醒请求的相对时间解析抽到共享 `ReminderActionParser`，ActionPlanner 和
  Built-in Reminder Skill 共用同一套 `title/body/delayMinutes` 参数边界。
- “提醒我 15 分钟后喝水” / `remind me in 1 hour ...` 可在 action runtime 未分类为
  likely action 时直接由 `BuiltInSkillRuntime.plan(input)` 生成 `schedule_reminder`
  ToolStep。
- 多时间片段时只使用选中的相对延迟作为 `delayMinutes`，不删除标题里的第二个
  时间描述；“15 分钟后是什么意思”这类 timing discussion 不会触发 reminder
  或 calendar 确认。
- Skill-first reminder 仍进入 `AwaitingUserConfirmation`，走 ToolRegistry 校验、
  SafetyPolicy、runtime permission policy 和后续 AlarmManager 执行边界。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.skillFirstReminderBypassesActionPlannerAndRequestsConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.skillFirstEnglishReminderBypassesActionPlannerAndRequestsConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.reminderTimingDiscussionFallsBackToAnswerWithoutConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest'
```

## 2026-05-31 Persisted Trace Summary Rehydration 增量验证

本轮覆盖项：

- `RoomAgentTraceStore.steps()` 在无 live steps 时可从已持久化 trace rows 恢复
  summary-only `AgentStep.RestoredSummary`。
- 恢复出的 step 保留 persisted type、summary 和已脱敏 trace JSON，不恢复
  `ToolRequested` 的原始 arguments，也不会伪装成可继续执行的 pending request。
- pending confirmation 仍走独立 `pending_agent_confirmations` 恢复链路；完成 run 的
  summary-only rehydration 不改变确认/观察执行边界。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentTraceStoreTest'
```

## 2026-05-31 Usage Access 特殊授权增量验证

本轮覆盖项：

- Usage Access (`PACKAGE_USAGE_STATS`) 被建模为 special app access，不进入
  Android dangerous runtime permission policy，也不展示系统 runtime permission 弹窗。
- 前台 App 摘要工具仍先经过 Agent 确认；未授权或从设置返回后仍未授权时，返回
  结构化权限失败和恢复入口，不执行 provider 读取、不自动重试。
- 授权后的结果保持 `LocalOnly` 最小 metadata；trace/audit 只记录工具名、权限/状态
  和安全摘要，不保存 usage history 或 App 内容。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.AndroidManifestTest' \
  --tests 'com.bytedance.zgx.pocketmind.AgentRuntimePermissionPolicyTest' \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest' \
  --tests 'com.bytedance.zgx.pocketmind.device.ForegroundAppProviderTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentTraceStoreTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.DeviceContextToolExecutorTest'
```

## 2026-05-31 Runtime Permission 说明与策略一致性增量验证

本轮覆盖项：

- `AgentRuntimePermissionPolicy` 新增 `RuntimePermissionRequirement`，在保留
  raw manifest permission 请求链路的同时，提供确认卡可展示的友好权限名和用途说明。
- 待确认动作 Sheet 会在执行前展示“确认后可能请求系统权限”，覆盖联系人、日历、
  通知、媒体和 legacy storage 等当前可请求 runtime permission。
- 权限拒绝后的结构化 `ToolResult.data` 同时保留 raw `deniedPermissions` 和友好
  `deniedPermissionLabels`；用户文案不再只拼接 `android.permission.*`。
- Registry 的 `RequiresAndroidRuntimePermission` 标记与实际 runtime policy 对齐：
  `query_foreground_app` 和 `cancel_reminder` 不再伪装为 Android dangerous runtime
  permission flow。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.AgentRuntimePermissionPolicyTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.deniedRuntimePermissionFailsPendingToolWithoutExecutingIt' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest'
```

## 2026-05-31 Allowlisted App Deep Target 增量验证

本轮覆盖项：

- 新增 `open_app_deep_target` 工具，参数只接受 allowlisted `targetId` 和该
  target 声明的参数；首个 target 为 `android_app_details_settings`，使用固定
  Android 应用详情设置 action 和 `package:` URI。
- `open_app_intent` 保持 package launcher 语义，不接受 `targetId`、任意
  activity/action/data/extras；应用深层目标和启动页目标分离。
- `open_app_intent` 与 `open_app_deep_target` 现在共用 `app_navigation_skill`
  的 Skill-first 路由；显式“启动微信”“打开微信应用详情设置”可直接进入确认卡，
  不再等待 action planner。
- `ActionExecutor` 对未知 target、额外 URI/action/extras、非法包名在启动外部
  Activity 前拒绝；结果 metadata 只包含 `targetId`、`targetPackage`、completion
  状态和 allowlist policy，不保存 raw URI path/query。
- `MobileActionPlanner` 只在用户明确指定 App/包名和“应用详情设置”时生成
  deep target 草稿；模糊“打开应用详情设置”不自动执行。
- 反例覆盖否定、故障/文档/API/Intent payload、裸 app 目标、微信小程序/支付码/
  App 内设置等未白名单深层目标，避免把这些请求降级成普通 App 启动。
- Agent trace 的 `ToolObserved` completion metadata allowlist 新增 `targetId`，
  仍过滤 raw payload。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentTraceStoreTest.roomStorePersistsOnlyAllowlistedToolObservationCompletionMetadata' \
  --tests 'com.bytedance.zgx.pocketmind.AgentRuntimePermissionPolicyTest.deepLinkAndAppIntentDoNotRequestRuntimePermissions'
```

## 2026-05-31 语义记忆运行时边界增量验证

本轮覆盖项：

- `MemoryRepository` 将默认轻量 token/hash 召回与真正 semantic runtime
  边界拆开：hash runtime 仍要求词项重叠，声明支持 semantic recall 的 runtime
  才能用高分阈值召回无词项重叠命中。
- Production `AppContainer` 当前注入 fail-closed LiteRT embedding runtime factory；
  安装并校验 memory model asset 不会让语义召回路径启用，除非 SDK 提供真实
  embedding vector API 且 runtime probe 通过。
- `MemoryHit` 标记命中来源为 `Lexical` 或 `Semantic`，便于后续接入 LiteRT
  embedding adapter 后验证真实语义召回路径。
- 模型管理高级页的本地记忆开关不再绑定 memory model asset 安装状态；文案明确
  当前使用本地轻量索引，安装 asset 不等于 embedding runtime 参与检索；UI 状态区分
  asset installed 与 `semanticMemoryEnabled`。
- 本地模型回答若使用本地记忆，或用户输入/工具结果已标记 `LocalOnly`，会作为
  `LocalOnly` turn 保存，避免后续切换到远程模型时进入远程 history。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.memory.MemoryRepositoryTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AssistantOrchestratorTest.injectsMemoryContextWhenMemoryIsEnabledWithoutRequiringEmbeddingModel' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeCompatibilityTest.memoryContextRemainsCompatibleWithoutEmbeddingCapability'

./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:compileDebugAndroidTestKotlin
git diff --check
```

## 2026-05-31 最近 Agent 轨迹摘要增量验证

本轮覆盖项：

- `AgentTraceStore` 新增 recent run summary 读取能力，按 run 更新时间倒序返回
  持久化 `AgentTraceStepSummary` 摘要，并限制每个 run 展示的 step 数。
- `AssistantRouter` / ViewModel 将最近 Agent 轨迹加载到 UI state；读取异常降级为空
  列表，不阻断后台活动面板。
- 后台活动面板新增只读“最近 Agent 轨迹”区域，仅展示 run id 后缀、状态和
  step type/summary，不展示 trace JSON、工具参数、完整 prompt 或原始私密内容。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentTraceStoreTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.refreshAuditEventsAlsoLoadsAgentTraceSummaries'
```

## 2026-05-31 周期检查策略 UX 增量验证

本轮覆盖项：

- 后台任务面板新增周期检查策略区域，展示 enabled、interval、min
  notification spacing、overdue grace、battery constraints、next allowed check、
  task status 和 latest run summary。
- `BackgroundTaskScheduler` 暴露 typed periodic check policy summary；ViewModel
  保存或关闭策略后刷新运行中任务、最近历史和策略状态。
- `ScheduledTaskRepository.recordPeriodicCheckRun()` 保留已保存策略字段，只追加
  latest run summary，避免 Worker 跑完后 UI 读不回用户策略。
- 关闭策略成功后 `periodic-check-local` 进入 `Cancelled` 历史；关闭失败时保留原
  running 状态并显示失败提示。
- 周期检查 UI 只管理本地提醒巡检策略，不执行聊天任务、不读取远程内容、不绕过通知
  权限和 WorkManager 约束。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.setPeriodicCheckPolicySchedulesDefaultPolicyAndRefreshesUi' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.setPeriodicCheckPolicyFailureDoesNotShowHealthyRunningTask' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.disablePeriodicCheckPolicyMovesTaskToHistory' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.disablePeriodicCheckPolicyFailureKeepsRunningTaskVisible' \
  --tests 'com.bytedance.zgx.pocketmind.background.PeriodicCheckSchedulerTest'
```

## 2026-05-31 最近截图元数据查询增量验证

本轮覆盖项：

- `query_recent_files` 新增 `screenshots` kind，仅作为 recent image metadata
  的筛选条件；Android 13+ 权限映射到 `READ_MEDIA_IMAGES`，Android 12- 仍使用
  legacy storage permission。
- Android provider 将 `screenshots` 限制在 `image/*`，并按文件名或截图目录
  特征筛选；返回的 `RecentFileItem.kind` 标记为 `screenshots`。
- Tool result 仍为 `LocalOnly`，且 `filesJson` 只包含 `name`、`mimeType`、
  `kind`、`sizeBytes`、`lastModifiedMillis`，不返回 MediaStore id、路径、
  URI、文件内容、像素或 OCR 文本。
- Planner 覆盖“最近截图”/`recent screenshots` 到
  `query_recent_files(kind="screenshots")` 的路由，不声明当前屏幕理解能力。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.RoutingAndValidatingToolExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.AgentRuntimePermissionPolicyTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.DeviceContextToolExecutorTest'
```

## 2026-05-31 后台任务历史查看增量验证

本轮覆盖项：

- `ScheduledTaskRepository` 新增最近任务查询，按 `updatedAtMillis`
  倒序返回全部状态，用于历史视图而不影响运行中任务查询。
- ViewModel 启动、刷新和取消后台任务后同时刷新运行中列表与最近历史；运行中
  只保留 `Scheduled`，历史只展示 `Delivered` / `Cancelled` / `Deleted` /
  `Failed`。
- 后台任务面板新增只读“最近后台任务”区域；已结束任务不会显示取消按钮，
  不会被误当成仍在运行。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.background.ScheduledTaskRepositoryTest' \
  --tests 'com.bytedance.zgx.pocketmind.background.ScheduledTaskRemovalCoordinatorTest' \
  --tests 'com.bytedance.zgx.pocketmind.background.PeriodicCheckSchedulerTest' \
  --tests 'com.bytedance.zgx.pocketmind.background.ReminderAlarmReceiverTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.restoreStartupStateLoadsRunningBackgroundTasksWithoutRemoteWork' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.cancelRunningBackgroundTaskRefreshesUiAndCancelsScheduler' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.cancelRunningBackgroundTaskFailureKeepsTaskVisible'
```

## 2026-05-31 记忆兜底与显式偏好持久化增量验证

本轮覆盖项：

- `memoryIndex.search` 抛异常时降级为空 `memoryHits`，普通聊天继续生成
  Answer 计划，trace 记录空记忆上下文。
- 长期记忆 store 读取或重建失败时不阻断启动、恢复或远程聊天；长期记忆
  列表降级为空。
- 当时用户发送 `记住：...` / `remember ...` 时，显式偏好在 `sendMessage`
  生产路径的消息落会话后持久化为 Preference 记录；该入口口径已被
  2026-06-01 Local remember control command 增量验证收窄为本地控制命令。
- 同一规范化偏好文本使用确定性 id/upsert，重复发送同一句 remember 命令
  不产生重复长期记忆。
- 遗忘显式偏好后，`rebuild` 不会从历史 remember 控制消息重新派生同一偏好。
- CJK 召回收紧为多字符 token 优先匹配，避免 `简洁回答` 被 `远程回复` 的
  单字重叠误命中。
- 当前默认本地记忆检索仍是轻量 token/hash 索引；`MemoryRepository`
  保留真正 semantic runtime 接入点，语义命中可跳过词项重叠过滤，但 LiteRT
  embedding adapter 尚未接入。
- 安装或补装 memory model asset 本身不代表 embedding runtime 已参与检索。
- `memoryIndex.buildContext` 异常时也降级为空记忆块，设备上下文仍可进入 prompt。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.memory.MemoryRepositoryTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest'
```

## 2026-05-31 外部 Activity 完成语义 Metadata 增量验证

本轮覆盖项：

- 外部 Activity/Intent 工具成功结果不再只表达“完成”，而是显式标记
  `completionState=ExternalActivityOpened`、`completionVerified=false` 和
  `externalOutcome=Unknown`，说明当前只验证外部页面/chooser 已打开。
- `ActivityNotFoundException` 仍返回 `NoActivityFound`；其他启动异常返回
  `ExecutionFailed`，并带 `completionState=NotStarted` 与 `exceptionType`。
- `share_text`、深链、package launcher 和 app deep target 结果只输出 allowlisted metadata；
  不把分享文本、URI path/query 等 raw payload 写入 `ToolResult.data`。
- Agent trace 的 `ToolObserved` 只持久化 completion metadata allowlist，
  不保存 raw payload；`open_app_intent` 描述与 package-only schema 对齐，深层目标
  使用单独 `open_app_deep_target` schema。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentTraceStoreTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest'
```

结果：targeted JVM 外部 Activity completion metadata 测试通过。

## 2026-05-31 Pending Confirmation 恢复回归增量验证

本轮覆盖项：

- Room 恢复出的 pending confirmation 可以再次确认、执行结果 observe，并清除
  pending snapshot。
- 恢复带 `clipboard_summary_share_skill` 的 pending 后，确认读取剪贴板可继续
  到本地模型续写，再规划第二个 `share_text` 待确认。
- ViewModel 启动恢复出的 pending confirmation 重复点击确认也只执行/observe
  一次。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentTraceStoreTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest'
```

结果：targeted JVM pending confirmation 恢复回归测试通过。

## 2026-05-31 后台任务运行态生命周期增量验证

本轮覆盖项：

- 周期检查 Worker 执行前将 `periodic-check-local` 标记为 `Running`；
  成功扫描后回到 `Scheduled`，执行异常时标记为 `Failed`。
- Worker 层异常兜底会再次尝试把周期检查任务标记为 `Failed`，避免
  WorkManager failure 后本地状态仍显示为健康 scheduled。
- 提醒 alarm 回调通过 `ReminderAlarmDeliveryHandler` 先进入 `Running`；
  通知投递成功标记为 `Delivered`，通知被阻止或抛异常标记为 `Failed`。
- `schedule_reminder` / `cancel_reminder` 工具结果增加 `taskStatus`
  metadata，便于 Agent observation 和调试确认任务状态。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.background.PeriodicCheckSchedulerTest' \
  --tests 'com.bytedance.zgx.pocketmind.background.ReminderAlarmReceiverTest' \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionExecutorTest'
```

结果：targeted JVM 后台生命周期与提醒工具结果测试通过。

## 2026-05-31 Skill 执行恢复与输入契约增量验证

本轮覆盖项：

- `SkillPlan.validateStructure()` 会校验 `SkillRequest.skillId` 与 manifest
  一致，并按 `SkillManifest.inputSchemaJson` 校验 Skill 输入。
- 内置 Skill 的 `SkillRequest.arguments` 收敛为 `{ "input": 原始用户输入 }`；
  工具参数继续保留在 `ToolRequest.arguments` / `ActionDraft.parameters`，由
  Tool Registry 单独校验。
- 缺失 required、空白 required string、额外字段、类型错误、enum、pattern 和
  数值范围错误会让 Skill plan 在确认或执行前拒绝。
- `SkillRunExecutor` 在非法 Skill 输入时不会执行工具或模型步骤。
- `SkillRunExecutor` 在需要用户确认的 tool step 处返回
  `SkillRunContinuation`；确认后的 `ToolResult` 可从该 step 继续执行后续
  model/tool step，不会重跑已完成步骤。
- 多确认 Skill 可在第二个确认点再次停下；错误 `requestId` 的结果会被拒绝，
  不进入后续 model step。
- action-planner 附加的 Skill plan 与 observation replan 附加的 Skill plan
  也必须通过 manifest 输入契约，不能只靠 tool registry 校验。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.skill.SkillRunExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.actionPlannerAttachedSkillPlanMustSatisfyManifestSchemaBeforeConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.replannedToolAttachedSkillPlanMustSatisfyManifestSchemaBeforeConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolSchemaContractTest'
```

结果：targeted JVM Skill manifest contract 测试通过；完整 JVM 单测、Debug
构建、AndroidTest 构建和 lint 通过；`git diff --check` 和敏感扫描通过；当前环境缺少
`adb`，未执行设备列表与模拟器回归。

## 2026-05-31 Runtime 权限拒绝执行边界增量验证

本轮覆盖项：

- Android runtime permission 回调会检查实际 grant result；权限仍缺失时不再
  调用 `confirmAgentConfirmation`。
- runtime permission 被拒后，ViewModel 通过 `failPendingToolRequest` 把
  `PermissionDenied` 作为 Agent observation 回写，清除 pending
  confirmation，且不执行工具。
- Agent loop 支持 pending confirmation 阶段的 pre-execution failure
  observation，用于记录系统权限拒绝等“未开始执行”的失败。
- `PermissionDenied` 失败不触发自动 retry，即使低层结果误标为
  `retryable=true`。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.AgentRuntimePermissionPolicyTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.deniedRuntimePermissionFailsPendingToolWithoutExecutingIt' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.permissionDeniedToolFailureDoesNotScheduleAutomaticRetry' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.pendingToolPermissionDenialIsObservedWithoutEnteringExecutionState'
```

结果：targeted runtime permission denial 边界测试通过；完整 JVM 单测、Debug
构建、AndroidTest 构建和 lint 通过；`git diff --check` 和敏感扫描通过；当前环境缺少
`adb`，未执行设备列表与模拟器回归。

## 2026-05-31 JVM 工具执行矩阵增量验证

本轮覆盖项：

- `RoutingToolExecutor` 正确分发设备上下文工具和普通外部动作工具。
- `ValidatingToolExecutor` 在 delegate 前拒绝未知工具、缺参、错参。
- delegate 异常会包装为 retryable `ExecutionFailed`，并保留 `toolName`
  context。
- foreground app、notification summary、contact summary、calendar
  availability、recent files executor 覆盖 success、permission denied、provider
  failure 和 wrong-tool 分支。
- 设备上下文工具结果保持 `LocalOnly`、最小字段、结构化 error code，不泄露
  path、URI、通知正文、剪贴板或 API key。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.tool.DeviceContextToolExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.RoutingAndValidatingToolExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.CalendarAvailabilityToolExecutorTest'

./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
git diff --check
rg credential-pattern scan excluding build and .gradle outputs
adb devices -l
```

结果：targeted 工具执行矩阵测试通过；完整 JVM/构建/lint 回归通过；
`git diff --check` 和敏感扫描通过；当前环境缺少 `adb`，未执行设备列表与模拟器回归。

## 2026-05-31 Skill-first 剪贴板 Skill 路由增量验证

本轮覆盖项：

- `SkillRuntime` 增加可选 Skill-first planner，用于无需 action-planner
  参数抽取的明确 Skill 请求。
- 内置 Skill runtime 可直接把“读取剪贴板”和“总结剪贴板并分享”规划为
  SkillPlan。
- Agent loop 在 action planner 前尝试 Skill-first；“总结剪贴板并分享”即使
  `ActionPlanningRuntime.isLikelyAction=false` 也会进入首个 `read_clipboard`
  确认。
- Skill-first 仍走 Tool Registry 校验、SafetyPolicy、trace、audit 和
  pending confirmation，不直接执行工具。
- 邮件、日程、路线、提醒等需要结构化参数的 Skill 仍依赖 action planner
  抽取参数。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AssistantOrchestratorTest'

./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
git diff --check
rg credential-pattern scan excluding build and .gradle outputs
adb devices -l
```

结果：

- 通过：targeted `BuiltInSkillRuntimeTest`、`AgentLoopRuntimeTest` 和
  `AssistantOrchestratorTest`。
- 通过：完整 `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`。
- 通过：`git diff --check`。
- 通过：敏感配置扫描无匹配。
- 未执行模拟器回归：当前环境缺少 `adb` 命令。

## 2026-05-31 App 内附件选择入口增量验证

本轮覆盖项：

- Composer 新增附件按钮，调用 Android 系统文档选择器。
- 用户主动选择的 text/image/audio/video/PDF/Office 文件复用 `SharedInput`
  入口；`text/*` 仍只生成有界本地文本摘录。
- 图片、音频、视频、PDF、Office 和其他非文本选择结果保持 metadata-only，
  不读取正文、像素或二进制内容。
- 自动生成的 picked/shared input 仍标记为 `LocalOnly`，远程模式不自动上传。
- 系统选择器入口可被 instrumentation smoke test 发现。

验证命令：

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
git diff --check
rg credential-pattern scan excluding build and .gradle outputs
adb devices -l
```

结果：

- 通过：完整 `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`。
- 通过：`git diff --check`。
- 通过：敏感配置扫描无匹配。
- 未执行模拟器回归：当前环境缺少 `adb` 命令。

## 2026-05-31 共享 text/* 文档摘录边界增量验证

本轮覆盖项：

- Shared text 和 `text/*` documents 可以产生用户可见、有界、本地文本摘录。
- 自动生成的 shared-input 文本摘录和附件元数据标记为 `LocalOnly`，只用于本地
  shared-input prompt。
- 二进制、图片、音频、视频、PDF、Office 和其他非文本附件保持
  metadata-only，不读取正文或二进制内容。
- 远程模式不会自动上传 shared-input 文本、文本摘录或附件元数据；用户必须手动
  粘贴愿意发送的内容。
- 该切片不覆盖图片/OCR；完整文档解析、Office/PDF 解析和媒体内容理解仍待实现。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.multimodal.SharedInputTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest'

./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew :app:lintDebug
git diff --check
rg credential-pattern scan excluding build and .gradle outputs
```

结果：

- 通过：targeted `SharedInputTest` 与 `PocketMindViewModelTest`。
- 通过：完整 `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest`。
- 通过：`:app:lintDebug`。
- 通过：`git diff --check`。
- 通过：敏感配置扫描无匹配。
- 未执行模拟器回归：当前环境缺少 `adb` 命令。

## 2026-05-31 用户主动提供 image/* 本地 OCR 摘录增量验证

本轮覆盖项：

- 用户通过 Android 分享入口或 App 内系统文件选择器主动提供 `image/*` 附件时，可以生成用户可见、有界、本地 OCR 文本摘录。
- OCR 摘录只进入 shared-input prompt，并标记为 `LocalOnly`；远程模型模式不会自动上传 OCR 文本、分享文本、文本摘录或附件元数据。
- 该能力不读取当前屏幕，不捕获截图，不处理 `query_recent_files(kind="screenshots")` 返回的最近截图候选，也不声明图片语义理解。
- 音频、视频、PDF、Office、二进制和其他不支持 OCR 的附件继续保持 metadata-only。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.multimodal.SharedInputTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteModeRejectsSharedImageOcrPreviewBeforeBuildingPrompt'

./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
git diff --check
rg credential-pattern scan excluding build and .gradle outputs
adb devices -l
```

结果：

- 通过：targeted `SharedInputTest` 与 `remoteModeRejectsSharedImageOcrPreviewBeforeBuildingPrompt`。
- 通过：完整 `:app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest`。
- 通过：`:app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`。
- 通过：`git diff --check`。
- 通过：敏感配置扫描无匹配。
- 未执行模拟器回归：当前环境缺少 `adb` 命令。

## 2026-05-31 语音输入入口增量验证

本轮覆盖项：

- Composer 新增语音输入按钮，调用 Android 系统语音识别。
- 识别文本作为一次性草稿回填输入框；未点击发送前不创建用户消息、
  不进入聊天路由，也不触发本地或远程模型。
- 语音入口不读取音频文件内容；音频分享入口仍保持 metadata-only 边界。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest'

./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew :app:lintDebug
git diff --check
```

结果：通过。

补充检查：

- 严格敏感信息扫描未发现 OpenAI-style API Key、provider-specific URL/model 或真实
  Authorization Bearer token 被写入文件。
- 当前 shell 中 `adb` 不在 PATH，因此本轮未执行连接设备/模拟器语音入口回归。

## 2026-05-31 最近审计日志可查看 UI 增量验证

本轮覆盖项：

- “后台任务”入口补充最近持久化工具审计事件查看能力，便于在真机上核对
  Agent 计划、确认、拒绝、取消、观察等工具审计链路。
- 审计列表保持 metadata-only：只展示时间、事件类型、工具名、状态、风险、
  权限和不含参数的安全摘要。
- UI 不展示工具参数、prompt、远程响应、剪贴板原文、Authorization 或 API
  Key；剪贴板和外部服务相关事件只能看到安全摘要。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.audit.ToolAuditRepositoryTest' \
  --tests 'com.bytedance.zgx.pocketmind.audit.ToolAuditEventTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest'

./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew :app:lintDebug
git diff --check
```

结果：通过。

补充检查：

- 严格敏感信息扫描未发现 OpenAI-style API Key、provider-specific URL/model 或真实
  Authorization Bearer token 被写入文件。
- 当前 shell 中 `adb` 不在 PATH，因此本轮未执行连接设备/模拟器回归。

## 2026-05-31 运行中后台任务查看/取消 UI 增量验证

本轮覆盖项：

- `BackgroundTaskScheduler` 新增运行中任务读取与 type-aware 取消边界；
  reminder 取消会撤销 AlarmManager `PendingIntent`，periodic check 取消会撤销
  WorkManager unique work。
- `ScheduledTaskRepository` 可区分 `Scheduled`、`Running`、`Delivered`、
  `Cancelled`、`Deleted` 和 `Failed`，并只把仍处于 `Scheduled` 的任务暴露给
  当前 UI 的运行中列表。
- `ActionExecutor` 补齐 `cancel_reminder` 执行分支，Tool Registry 中已注册的
  取消提醒工具现在能返回结构化取消结果或失败原因。
- `PocketMindViewModel` 新增运行中后台任务状态、刷新和取消事件；启动时读取
  活跃任务，不展示历史完成/失败/取消记录，取消失败时保留原任务并显示失败提示。
- UI 新增“后台任务”入口，显示任务标题、触发/检查时间、状态和取消入口；空
  列表显示“暂无运行中的后台任务”。
- 该 UI 只管理已确认创建的后台任务，不绕过 `schedule_reminder` 的 Agent 确认
  和 Android 通知权限链路。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.background.ScheduledTaskRepositoryTest' \
  --tests 'com.bytedance.zgx.pocketmind.background.PeriodicCheckSchedulerTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest' \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest' \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest'

./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew :app:lintDebug
git diff --check
```

结果：通过。

补充检查：

- 严格敏感信息扫描未发现 OpenAI-style API Key、provider-specific URL/model 或真实
  Authorization Bearer token 被写入文件。
- 当前 shell 中 `adb` 不在 PATH，因此本轮未执行连接设备/模拟器回归。

## 2026-05-31 长期记忆查看/遗忘控制增量验证

本轮覆盖项：

- `MemoryRepository` 新增已保存长期记忆读取边界，只列出显式持久化的偏好
  与任务状态记录，不把普通会话索引或历史 `记住：...` 临时抽取项展示为
  长期记忆。
- `PocketMindViewModel` 新增长期记忆状态流和单条遗忘/清空事件；遗忘后会
  同步刷新 UI、内存索引和 Room 记录，清空长期记忆不会删除聊天会话。
- “模型管理 > 高级 > 本地记忆”现在可查看已保存长期记忆、单条遗忘，并通过
  二次确认清空显式长期记忆记录。
- 远程模式下查看、遗忘或清空本地长期记忆不发起远程模型请求，也不会上传
  记忆内容。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.memory.MemoryRepositoryTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest'

./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew :app:lintDebug
git diff --check
```

结果：通过。

补充检查：

- 严格敏感信息扫描未发现 OpenAI-style API Key、provider-specific URL/model 或真实
  Authorization Bearer token 被写入文件。
- 当前 shell 中 `adb` 不在 PATH，因此本轮未执行连接设备/模拟器回归。

## 2026-05-31 Deep Link / App Intent 执行边界增量验证

本轮覆盖项：

- `open_deep_link` 从已注册/可规划补齐到确认后执行；执行边界仅允许安全
  `https://` 链接，并拒绝 `http`、`file`、`content`、`javascript`、自定义
  scheme、带 user info 或超长 URI。
- `open_app_intent` 从已注册/可规划补齐到确认后执行；当前只支持
  `packageName` 打开应用启动页，不暴露任意 activity/action/data/extras。
- `ActionPlanningRuntime` prompt、`MobileActionPlanner`、`ToolRegistry` schema、
  `ActionExecutor` 和 runtime permission policy 均同步到这个收敛后的安全边界。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolSchemaContractTest' \
  --tests 'com.bytedance.zgx.pocketmind.AgentRuntimePermissionPolicyTest'

./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew :app:lintDebug
git diff --check
```

结果：通过。

补充检查：

- 严格敏感信息扫描未发现 OpenAI-style API Key、provider-specific URL/model 或真实
  Authorization Bearer token 被写入文件。
- 当前 shell 中 `adb` 不在 PATH，因此本轮未执行连接设备/模拟器回归。

## 2026-05-31 Pending Agent Confirmation Recovery 增量验证

本轮覆盖项：

- 新增 `pending_agent_confirmations` Room 表，用于保存最新待确认工具请求的
  恢复快照；普通 Agent trace/audit 仍不写入原始工具参数。
- `RoomAgentTraceStore` 重启后只恢复仍处于 `AwaitingUserConfirmation` 的
  pending run，并跳过/清理 stale pending；查询顺序加入稳定 tie-breaker。
- 恢复会补回确认所需的 typed live steps，包括多步骤 skill 的
  `SkillPlanned`，保证 “总结剪贴板并分享” 这类后续模型观察还能继续规划
  第二个待确认工具。
- `PocketMindViewModel.restoreStartupState` 只恢复 UI confirmation state，
  不执行工具，也不触发 Android runtime permission；新消息仍会被待确认动作
  拦截。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentTraceStoreTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AssistantOrchestratorTest'

./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew :app:lintDebug
git diff --check
```

结果：通过。

补充检查：

- `stash@{0}` 的 recent-files 候选实现未直接应用；当前主线已覆盖其核心能力，
  且补强了编译、权限请求和最小化返回字段。
- 当前 shell 中 `adb` 不在 PATH，因此本轮未执行连接设备/模拟器回归。

## 2026-05-31 Runtime Permission Request 增量验证

本轮覆盖项：

- 新增 `AgentRuntimePermissionPolicy`，把已确认的 `PendingAgentConfirmation`
  映射到具体 Android runtime permission。
- `MainActivity` 改为统一使用 `RequestMultiplePermissions`：用户先确认 Agent
  工具请求，再触发 Android 权限弹窗；权限返回后用同一个 confirmation 继续执行。
- 补齐 `READ_CONTACTS` manifest 声明；保留 provider/executor 的最终权限检查，
  denied 仍走结构化 `PermissionDenied` tool result。
- 权限映射覆盖提醒通知、日历忙闲、联系人查询和最近文件媒体权限；Android 13+
  的最近文件 `kind` 会映射到最小媒体权限，非媒体文件不伪装成可请求 runtime
  permission。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.AgentRuntimePermissionPolicyTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.CalendarAvailabilityToolExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest'

./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew :app:lintDebug
git diff --check
```

结果：通过。

补充检查：

- 严格敏感信息扫描未发现 OpenAI-style API Key、provider-specific URL/model 或真实
  Authorization Bearer token 被写入文件。
- 当前 shell 中 `adb` 不在 PATH，因此本轮未执行连接设备/模拟器回归。

## 2026-05-31 Recent Files 设备上下文工具增量验证

本轮覆盖项：

- 从 `query_recent_files` stash 候选实现中提取可用部分，并按当前主线分层
  重新接入 `ToolRegistry`、`RoutingToolExecutor`、`MobileActionPlanner` 和
  `PocketMindAppContainer`。
- 新增 `ReadsFiles` 权限声明与 Android 文件读取权限 manifest 声明；工具仍需
  用户确认，并把结果标记为 `LocalOnly`。
- `RecentFilesToolExecutor` 只返回文件名、MIME、粗粒度 kind、大小和修改时间，
  不返回 MediaStore id、路径、URI 或文件内容。
- `AndroidRecentFileProvider` 在 Android 13+ 下只查询已授权的媒体类型；
  非媒体文件类型在缺少系统文件选择器授权时返回结构化 `PermissionDenied`。
- Planner 覆盖 “查询最近5个图片文件列表” 和 “最近 3 张图片” 这类中文路由。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.CalendarAvailabilityToolExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest'

./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew :app:lintDebug
git diff --check
```

结果：通过。

补充检查：

- 严格敏感信息扫描未发现 OpenAI-style API Key、provider-specific URL/model 或真实
  Authorization Bearer token 被写入文件。
- 当前 shell 中 `adb` 不在 PATH，因此本轮未执行连接设备/模拟器回归。

## 2026-05-31 长期记忆持久化增量验证

本轮覆盖项：

- 新增 `memory_records` Room 表和 `4 -> 5` 数据库迁移，用于保存显式用户偏好
  与任务状态记忆。
- `MemoryRepository` 会把 `indexPreference` / `indexTaskState` 写入持久化
  store，并在 `rebuild` 时重新载入；普通会话记忆仍从已保存聊天消息重建，避免
  重复写入长期记忆表。
- `forget(id)` 同时删除内存索引和持久化记录，`clear()` 清空长期记忆记录。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.memory.MemoryRepositoryTest'
```

结果：通过。

## 2026-05-31 Clipboard Summary Share Skill 接入增量验证

本轮覆盖项：

- `clipboard_summary_share_skill` 不再只停留在独立 Skill contract；输入
  “总结剪贴板并分享”会先规划受确认保护的 `read_clipboard`。
- 剪贴板 observe 成功后仍优先进入本地模型续写，不调用普通 observation
  replanner。
- 本地模型生成摘要后，Agent loop 绑定摘要到 `share_text.text`，回到
  `AwaitingUserConfirmation` 等待第二次确认；不会直接打开分享面板。
- Agent loop 只接受当前 pending / confirmed request id，避免旧
  `read_clipboard` request 在第二步确认或执行阶段被重复确认/观察。
- ViewModel 在本地摘要生成后会展示第二次 `share_text` 确认，并保持剪贴板
  派生消息为 `LocalOnly`；存在待确认动作时会阻止新消息越过旧确认卡。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.skill.SkillRunExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AssistantOrchestratorTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest'

./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew :app:lintDebug
git diff --check
```

结果：通过。

补充检查：

- 严格敏感信息扫描未发现 OpenAI-style API Key、provider-specific URL/model 或真实
  Authorization Bearer token 被写入文件。
- 当前 `adb devices -l` 无已连接设备，因此本轮未执行
  `connectedDebugAndroidTest` 或真机分享面板验证。

## 2026-05-31 Agent Replan / ViewModel 隐私回归增量验证

本轮覆盖项：

- Agent observe 成功后可通过 `AgentObservationReplanner` 产出下一步工具计划。
- 默认生产策略 `SequentialActionObservationReplanner` 会在用户输入含明确顺序
  连接词（如“然后”/`then`）且 run 目前只有一个工具计划时，规划下一步动作。
- Room 恢复路径会恢复历史 `ToolRequested` request id 去重骨架，但当前策略
  不再跨重启恢复 `nextActionInput`；确认或观察不能复用旧 request id。
- 下一步工具会重新经过 Tool Registry 参数校验、SafetyPolicy、trace、audit
  和用户确认；不会因为来自 observe 阶段就直接执行。
- Replanned request id 不能复用已有 `ToolRequested` id，避免确认/观察串到旧
  请求。
- Clipboard continuation 优先于 replan；当观察结果需要本地模型续写时，不会
  调用 replanner，也不会产生被忽略的拒绝 trace/audit 副作用。
- ViewModel 构造边界改为窄接口，新增 JVM 回归测试覆盖远程 `LocalOnly`
  当前输入保护，以及本地 share input / local assistant 回复不进入后续远程
  history。
- LiteRT native logging 配置从 ViewModel 构造移到 AppContainer，降低 JVM
  单测副作用。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AssistantOrchestratorTest'

./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew :app:lintDebug
git diff --check
```

结果：通过。

补充检查：

- 仓库扫描未发现 provider-specific/OpenAI-style API Key 或 provider-specific 远程配置被写入文件。
- 当前 `adb devices -l` 无已连接设备，因此本轮未执行 `connectedDebugAndroidTest`。

## 2026-05-30 隐私边界 / Skill 执行器 / DB 迁移增量验证

本轮覆盖项：

- `MessagePrivacy.LocalOnly` 持久化到会话消息，并在远程历史构造前过滤。
- 远程模式下当前输入若标记为 `LocalOnly`，会在 ViewModel 层直接保护，
  不调用远程模型。
- Android 分享入口与剪贴板派生续写的自动消息标记为 `LocalOnly`，避免之后
  切换远程模式时进入 history。
- `RemoteChatRuntime` 在请求体构造和真实 `send` 路径中都过滤 `LocalOnly`
  history。
- `SkillRunExecutor` 默认通过工具 registry 和 safety policy gate；需确认的
  tool step 返回 `AwaitingConfirmation`，不会直接执行工具。
- `SkillRunExecutor` 的公开 outputs 不再暴露 `read_clipboard.text` 等私有工具
  绑定输出。
- Agent observe 阶段新增显式 `AgentObservationDecision`，覆盖 complete、
  continue-with-model、retry、fail、cancel，并写入 trace 但不存储私有续写 prompt。
- Room `chat_messages.privacy` 增加实体默认值和 `3 -> 4` 迁移；新增
  instrumentation 迁移测试。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.skill.SkillRunExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.runtime.RemoteChatRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.data.SessionRepositoryTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolSchemaContractTest'

./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew :app:testDebugUnitTest
```

结果：通过。

模拟器说明：

- SDK 工具位于 `/Users/bytedance/Library/Android/sdk`。
- `pocketmind_api36_arm64` 与 `focus_agent_api36_arm64` 两个 AVD 在本轮尝试
  中均未能在限定时间内完成启动，且 emulator 进程自动退出，因此本轮未能执行
  `connectedDebugAndroidTest`。
- `PocketMindDatabaseMigrationTest` 已通过 `assembleDebugAndroidTest` 编译，
  仍需要在可启动模拟器或真机上执行。

验证时间：2026-05-30

## Agent 核心能力增量验证

环境：

- AVD：`focus_agent_api36_arm64`
- Android：API 36 Google APIs ARM64
- 设备序列号：`emulator-5554`
- SDK：`/Users/bytedance/Library/Android/sdk`

覆盖项：

- Tool Registry 增加权限声明、结构化错误模型、参数拒绝和执行结果。
- Tool Registry 参数校验改为由 JSON schema 驱动，覆盖 required、额外参数拒绝、`minLength` 和 `pattern`。
- Agent Loop 增加确认后的 `ToolResult` observe 回写、trace step 和完成状态。
- Agent Loop 对 retryable 本地只读工具失败增加一次有界自动重试，记录 `ToolRetryScheduled` trace/audit 事件，重试预算耗尽后才进入 `Failed`；外部界面启动、外部发送、通知/后台调度以及高/关键风险工具即使被底层标记为 retryable 也不会自动重放。
- 用户取消动作确认时，Agent run 会进入 `Cancelled`，并记录取消/观察审计事件。
- Built-in Skill Runtime 增加版本化 manifest，并将邮件、日程、地图、信息查找、设备设置映射为一跳工具 skill。
- Device Context 增加最小非敏感设备状态快照，并接入 Agent trace / prompt context。
- Safety Policy 增加风险分级执行策略，阻止中高风险工具绕过确认。
- Tool Audit 增加 `tool_audit_events` Room 表，记录计划、确认、执行观察和拒绝事件。
- Memory 增加显式偏好/任务状态记录与遗忘控制。
  默认召回仍为 token/hash；semantic runtime 边界已存在，但 LiteRT embedding
  adapter 尚未参与检索。
- Background Tasks 增加 `schedule_reminder` 工具、`reminder_skill`、`scheduled_tasks` Room 表、AlarmManager 调度和提醒通知通道。
- Reminder 执行前会请求 Android 通知权限；拒绝后返回结构化 `PermissionDenied`。
- Device Context 增加受确认保护的 `read_clipboard` 工具和剪贴板上下文 Skill。
- Clipboard observe 成功后会生成一次本地模型续写 prompt，剪贴板原文只进入即时内存续写链路；trace、audit 和持久化工具观察消息保留脱敏摘要，远程模型模式不会自动上传剪贴板内容。
- 远程模型普通聊天不再自动注入本地记忆和设备上下文；Android 分享入口在远程模式下不自动上传分享文本、文本摘录或附件元数据。
- 远程 API Key 清空时会同步清除已加密保存的旧值。
- Execution Boundary 增加 `share_text` 工具和系统分享 Skill；结果语义为打开系统分享面板。
- Multimodal Inputs 增加 Android 分享入口，接收 shared text 和有界 `text/*`
  文档摘录；图片/音频/视频/PDF/Office/二进制附件保持 metadata-only。
- 模型管理 sheet 增加显式关闭按钮，避免模拟器回归依赖 Back 键状态。
- 远程模型回归使用本地 mock OpenAI-compatible 服务，不写入真实 API Key。

验证命令：

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug

ANDROID_HOME=/Users/bytedance/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/bytedance/Library/Android/sdk \
ANDROID_SERIAL=emulator-5554 \
./gradlew :app:connectedDebugAndroidTest
```

结果：通过。

- JVM 单测：通过。
- Debug APK 构建：通过。
- 模拟器 instrumentation：`4 tests, 0 skipped, 0 failed`。

说明：

- 用户提供的远程模型配置仅作为可选手工验证输入，未写入源码或测试代码；
  后续文档如需记录 live 验证事实，只能作为临时环境变量/用户配置的验证记录，
  不能作为源码默认、推荐配置或发布配置。
- 当前仍未完成的核心能力包括语义屏幕理解、LiteRT embedding adapter 参与记忆检索、special-access permission flows beyond Usage Access、当前屏幕像素/截图捕获、任意媒体 OCR 和实际图片/文档语义理解；状态见 `docs/agent_core_modules.md`。

## 历史验证记录

验证时间：2026-05-24

## 模拟器完整功能回归

环境：

- AVD：`pocketmind_api36_arm64`
- Android：API 36 Google APIs ARM64
- 安装包：`app/build/outputs/apk/release/app-release-local-signed.apk`
- 模型目录：`/sdcard/Android/data/com.bytedance.zgx.pocketmind/files/Download/`

模型补齐结果：

- `gemma-4-E2B-it.litertlm`：已安装，约 2.4 GB。
- `embeddinggemma-300m.litertlm`：已补装，约 171 MB；该 asset 仅表示文件已安装，
  不表示 LiteRT embedding adapter 已参与记忆检索。
- `mobile-actions_q8_ekv1024.litertlm`：已补装，约 271 MB。
- 模型管理页确认三类推荐资产均出现在“本地模型”，设备检查在基础能力齐全后显示 `待下载：已就绪`。

真实交互覆盖：

- 普通聊天：保留既有 `用三句话解释端侧大模型` 成功回答验证。
- 记忆增强：新会话发送 `Remember my rcode is xb83`，停止生成后会重建本地轻量
  token/hash 记忆索引；追问 `What is my rcode` 时 UI 显示 `已引用本地记忆 1 条`，
  回答 `你的 rcode 是 xb83。`
- 动作草稿：发送 `open wifi settings` 后只展示确认 Sheet；未确认时仍停留在 App；点击 `确认并打开` 后进入系统 Wi-Fi 设置页。
- 会话管理：会话列表展示当前会话、消息数量和历史会话，入口可正常打开。
- 模型管理：推荐模型区支持基础对话、记忆 asset、动作模型逐项补装/重下；
  记忆 asset 的安装不代表 runtime 参与；Top K 滑条不再展示密集刻度点；
  下载完成后不再残留旧进度条。

修复项：

- 记忆检索增加词项重叠过滤，避免哈希向量碰撞导致无关旧记忆注入。
- 英文停用词过滤加入 `remember`、`is`、`my` 等常见词，避免“记住某事”类命令互相误召回。
- 停止生成后会重建本地记忆索引，已经发送的用户事实可被后续检索。
- 设备检查按仍需下载的基础能力大小提示空间；基础能力齐全时不再误报 2.4 GB 对话模型空间不足。

验证命令：

```bash
ANDROID_SDK_ROOT=/Users/bytedance/Documents/Codex/2026-05-24/gemma4-e2b/android-sdk \
ANDROID_HOME=/Users/bytedance/Documents/Codex/2026-05-24/gemma4-e2b/android-sdk \
scripts/verify_local.sh
```

结果：通过。覆盖 `testDebugUnitTest`、`lintDebug`、`assembleDebug`、`assembleDebugAndroidTest`、`assembleRelease`、APK 不含 `.litertlm`、仅 `arm64-v8a` native code、release size <= 75 MB。

## 模拟器真实对话验证

环境：

- AVD：`pocketmind_api36_arm64`
- Android：API 36 Google APIs ARM64
- 安装包：`app/build/outputs/apk/release/app-release-local-signed.apk`
- 模型文件：在模拟器内通过 App 首装向导下载 `基础对话 E2B`，文件位于 `/sdcard/Android/data/com.bytedance.zgx.pocketmind/files/Download/gemma-4-E2B-it.litertlm`

流程：

- 首装向导默认展示基础能力包；为缩短真实对话验证时间，只保留对话模型，取消记忆与动作模型。
- 模拟器内 DownloadManager 完成约 2.4 GB 对话模型下载。
- App 自动注册模型并加载；GPU dispatch 初始化不可用时自动回退 CPU，界面显示 `基础对话 E2B · CPU · 已就绪`。
- 新建会话后点击开场问题 `用三句话解释端侧大模型`。

首次结果：

- 模型下载与加载成功，但生成结束后因为 LiteRT benchmark 未启用，`getBenchmarkInfo()` 抛错，UI 显示 `生成失败，建议重新加载`。

修复：

- `PocketMindViewModel` 读取生成统计时忽略 benchmark 不可用错误。
- `RealLiteRtRuntime.lastGenerationStats()` 对 LiteRT benchmark API 做容错，统计不可用时返回 `null`。

复测结果：

- 同一模拟器保留已下载模型，覆盖安装修复后的 release 包。
- 新建会话再次发送 `用三句话解释端侧大模型`，成功返回三句话中文回答。
- 生成结束后状态回到 `基础对话 E2B · CPU · 已就绪`，未再出现 benchmark 导致的生成失败。
- 截图：`/tmp/pocketmind-real-dialogue-fixed.png`

验证命令：

```bash
ANDROID_SDK_ROOT=/Users/bytedance/Documents/Codex/2026-05-24/gemma4-e2b/android-sdk \
ANDROID_HOME=/Users/bytedance/Documents/Codex/2026-05-24/gemma4-e2b/android-sdk \
./gradlew testDebugUnitTest assembleRelease
```

结果：通过。

## 最新增量验证

命令：

```bash
ANDROID_SDK_ROOT=/Users/bytedance/Documents/Codex/2026-05-24/pocketmind-model/android-sdk \
ANDROID_HOME=/Users/bytedance/Documents/Codex/2026-05-24/pocketmind-model/android-sdk \
scripts/doctor.sh

ANDROID_SDK_ROOT=/Users/bytedance/Documents/Codex/2026-05-24/pocketmind-model/android-sdk \
ANDROID_HOME=/Users/bytedance/Documents/Codex/2026-05-24/pocketmind-model/android-sdk \
scripts/verify_local.sh
```

结果：通过。

覆盖项：

- `PocketMindViewModel` 已拆到 runtime、model repository、download service 和 session repository 边界。
- `MainActivity` 仅保留 Activity wiring；Compose UI 移到 `ui/`，markdown 分段逻辑已可 JVM 测试。
- 下载取消会先取消 monitor job 并清除 active download id，避免取消后被轮询覆盖为“下载任务不存在”。
- Release 已开启 R8/resource shrink，并在本地门禁中加入 75 MB APK 预算。
- `scripts/doctor.sh` 已验证 JDK、Android SDK 36、aapt、adb 和 Gradle wrapper。

产物：

- `app/build/outputs/apk/debug/app-debug.apk`，约 100 MB
- `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`，约 3.4 MB
- `app/build/outputs/apk/release/app-release-unsigned.apk`，约 25 MB

真机：

- 已连接设备 `fb6272c`，型号 `23127PN0CC`，状态为 `device`。
- 重新允许 USB 安装后，`./gradlew :app:connectedDebugAndroidTest --console=plain` 通过。
- 真机执行 `3 tests, 0 skipped, 0 failed`。

## 之前增量验证

命令：

```bash
ANDROID_SDK_ROOT=/Users/bytedance/Documents/Codex/2026-05-24/pocketmind-model/android-sdk \
ANDROID_HOME=/Users/bytedance/Documents/Codex/2026-05-24/pocketmind-model/android-sdk \
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
```

结果：通过。

覆盖项：

- 推荐模型目录包含 基础对话模型 E2B 与 高质量对话模型 E4B。
- 首屏直接暴露推荐模型选择、下载、导入、设备检查和状态提示。
- 顶部常驻展示运行状态，模型管理弹层展示当前模型、本地模型、推荐模型、添加模型和进度。
- 底部输入区会根据无模型、忙碌、就绪状态切换提示与主操作。
- 下载/导入进入加载阶段时会清理进度字段，避免 100% 下载进度残留。
- Compose 冒烟测试已补充首屏模型准备入口断言。

真机：

- 已连接设备 `23127PN0CC`，设备状态为 `device`。
- `connectedDebugAndroidTest` 首次尝试在已有模型状态下进入 instrumentation 后卡住；清理调试包后重跑，设备安装阶段返回 `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`。
- 需要在 Xiaomi / HyperOS / MIUI 开发者选项中允许“USB 安装 / 通过 USB 安装”，并确认手机弹窗后重跑真机自动化。

## 本地验证

命令：

```bash
ANDROID_SDK_ROOT=/Users/bytedance/Documents/Codex/2026-05-24/pocketmind-model/android-sdk \
ANDROID_HOME=/Users/bytedance/Documents/Codex/2026-05-24/pocketmind-model/android-sdk \
GRADLE_CMD=/tmp/gradle-9.5.1/bin/gradle \
scripts/verify_local.sh
```

结果：通过。

覆盖项：

- `testDebugUnitTest`
- `lintDebug`
- `assembleDebug`
- `assembleDebugAndroidTest`
- APK 不包含 `.litertlm` 模型文件
- APK 仅包含 `arm64-v8a` native code
- Manifest 包名和联网权限正确
- Hugging Face 模型下载 URL 可访问，`Content-Length = 2588147712`

产物：

- `app/build/outputs/apk/debug/app-debug.apk`，约 84 MB
- `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`，约 2.4 MB

## 真机验证

设备：

- 厂商：Xiaomi
- 型号：23127PN0CC
- Android：16
- ABI：arm64-v8a
- `/data` 可用空间：约 50 GB

自动验证：

```bash
scripts/install_and_test_device.sh
```

结果：通过。`MainActivitySmokeTest.firstLaunchShowsModelSetupActions` 在真机上执行 `1 tests, 0 failures`。

## 模型下载与加载

App 内点击“下载推荐模型”后，模型文件成功下载到：

```text
/storage/emulated/0/Android/data/com.bytedance.zgx.pocketmind/files/Download/chat-model.litertlm
```

文件大小：

```text
2588147712 bytes
```

App 偏好已保存模型路径：

```xml
<string name="model_path">/storage/emulated/0/Android/data/com.bytedance.zgx.pocketmind/files/Download/chat-model.litertlm</string>
```

加载结果：

```text
就绪 · GPU
离线可用
```

覆盖安装新 APK 后保留模型文件，强停并重启 App，仍能自动加载到 `就绪 · GPU`。

## 真机问答

问题：

```text
用三句话解释什么是端侧大模型
```

真机回答：

```text
端侧大模型是指将大型语言模型（LLM）的能力部署在本地设备（如手机、边缘计算设备）上。

这意味着模型可以在没有连接到云端服务器的情况下，直接在用户设备上进行推理和应用。

其主要优势在于提高响应速度、增强隐私性，并降低对网络带宽和云端算力的依赖。
```

结果：真机本地模型问答成功，生成结束后状态回到 `就绪 · GPU`。

## 参数与生成统计验证

日期：2026-05-24

覆盖内容：

- “模型”入口已在顶部和输入框发送按钮旁保留，便于发送前调整模型与参数。
- 模型管理页新增全局生成参数：Temperature、Top P、Top K；页面文案说明低/高取值对稳定性、多样性和候选范围的影响。
- 参数修改后立即持久化；模型已加载时会重建当前会话 runtime，使后续生成直接使用新参数。
- 端侧后端说明已补充：GPU 通常更快但更依赖驱动/内存条件，CPU 更稳但更慢；GPU 初始化失败会自动切到 CPU。
- 每次回答生成结束后，助手消息下方显示本次生成 token 数和 token/s；非法或不可用速度值会被过滤。
- 停止生成现在会调用 LiteRT `cancelProcess()`，避免 UI 停止后 native 侧继续生成。
- 会话数据读取兼容旧 top-level array 格式；不会在 repository 构造时立刻重写旧格式，降低回滚风险。

验证命令：

```bash
ANDROID_SDK_ROOT=/Users/bytedance/Documents/Codex/2026-05-24/pocketmind-model/android-sdk \
ANDROID_HOME=/Users/bytedance/Documents/Codex/2026-05-24/pocketmind-model/android-sdk \
scripts/verify_local.sh
```

结果：通过。覆盖 `testDebugUnitTest`、`lintDebug`、`assembleDebug`、`assembleDebugAndroidTest`、`assembleRelease`、APK 不含 `.litertlm`、仅 `arm64-v8a` native code、release size <= 75 MB。

真机命令：

```bash
ANDROID_SDK_ROOT=/Users/bytedance/Documents/Codex/2026-05-24/pocketmind-model/android-sdk \
ANDROID_HOME=/Users/bytedance/Documents/Codex/2026-05-24/pocketmind-model/android-sdk \
./gradlew :app:connectedDebugAndroidTest --console=plain
```

结果：通过。设备 `23127PN0CC - 16` 执行 `3 tests, 0 skipped, 0 failed`。测试在保留已有模型和会话数据的设备状态下通过。

安装命令：

```bash
/Users/bytedance/Documents/Codex/2026-05-24/pocketmind-model/android-sdk/platform-tools/adb \
  -s fb6272c install -r app/build/outputs/apk/debug/app-debug.apk
```

结果：`Success`。仅覆盖安装，未卸载 App，未清除 App 数据。

## 主分支合并检查

日期：2026-05-24

分支与提交：

- `main`
- 合并提交：`b0b4306 Add model capability setup and assistant orchestration`

代码检查：

```bash
rg "com.bytedance.zgx.gemmalocalqa|GemmaChatViewModel|GemmaChatScreen|GemmaModelRules|GEMMA_|FunctionGemmaActionPlanner|gemma_local_qa" \
  app/src build.gradle.kts settings.gradle.kts docs scripts
```

结果：通过，未发现旧产品级包名、类名或脚本符号残留。

本地验证：

```bash
ANDROID_SDK_ROOT=/Users/bytedance/Documents/Codex/2026-05-24/gemma4-e2b/android-sdk \
ANDROID_HOME=/Users/bytedance/Documents/Codex/2026-05-24/gemma4-e2b/android-sdk \
scripts/verify_local.sh
```

结果：通过。覆盖 `testDebugUnitTest`、`lintDebug`、`assembleDebug`、`assembleDebugAndroidTest`、`assembleRelease`、APK 不含 `.litertlm`、仅 `arm64-v8a` native code。

真机 UI 验证：

```bash
ANDROID_SDK_ROOT=/Users/bytedance/Documents/Codex/2026-05-24/gemma4-e2b/android-sdk \
ANDROID_HOME=/Users/bytedance/Documents/Codex/2026-05-24/gemma4-e2b/android-sdk \
./gradlew connectedDebugAndroidTest --console=plain
```

结果：通过。设备 `23127PN0CC - 16` 执行 `3 tests, 0 skipped, 0 failed`，`BUILD SUCCESSFUL in 1m 39s`。

补充修复：instrumentation 进程内自动跳过启动期 pending download / 模型加载工作，并禁止测试期间打开模型来源外链；`MainActivitySmokeTest` 使用稳定的 `testTag` 定位模型管理与自定义下载入口，避免依赖长滚动和占位文案。该改动已由本地验证和真机 UI 测试覆盖。

## 2026-06-01 Emulator regression closure and audit hardening

本轮覆盖项：

- `scripts/install_and_test_device.sh` 不再只信 `adb shell am instrument`
  的退出码；当 instrumentation 输出包含失败用例、stack trace、`shortMsg`
  或 failed status code 时直接失败，避免 “OK/FAILURES” 被误判。
- `scripts/verify_emulator.sh` 在启动前校验 `AVD_NAME`，失败时列出可用
  AVD，并稳定打印 emulator 日志路径；fake SDK 回归覆盖缺 emulator binary、
  未知 AVD 和 instrumentation 输出失败。
- AVD UI 测试修复了会话 sheet 关闭不稳、审计/轨迹 empty-state 受前置测试
  污染、远程模式本地动作历史泄漏断言缺失的问题。
- Agent tool observed audit metadata 改为以已确认的 `ToolRequest.toolName`
  为准，不信工具结果里可伪造的 `toolName`；`taskId`/`recoveryTaskId`
  也必须满足安全任务 id 格式。
- App 启动时会触发 reminder alarm 重排，覆盖 “DB 已有 Scheduled reminder
  但普通重启后 AlarmManager 未注册” 的恢复窗口；WorkManager periodic
  enqueue/cancel 现在等待 `Operation.result`。
- 远程模式下被拒绝的本地动作消息标记为 `LocalOnly`，不会进入后续远程
  prompt/history。

验证命令：

```bash
scripts/test_validation_scripts.sh
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.audit.ToolAuditRepositoryTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest' \
  --tests 'com.bytedance.zgx.pocketmind.background.PeriodicCheckSchedulerTest' \
  --tests 'com.bytedance.zgx.pocketmind.background.ScheduledTaskRepositoryTest'
./gradlew :app:compileDebugAndroidTestKotlin
./gradlew :app:testDebugUnitTest
ANDROID_HOME=/Users/bytedance/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/bytedance/Library/Android/sdk \
scripts/verify_local.sh
ANDROID_HOME=/Users/bytedance/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/bytedance/Library/Android/sdk \
AVD_NAME=pocketmind_api36_arm64 \
EMULATOR_ARGS='-no-window -no-snapshot -no-audio -gpu swiftshader_indirect' \
CLEAN_DEVICE=1 \
BOOT_TIMEOUT_SECONDS=360 \
EMULATOR_SELECT_TIMEOUT_SECONDS=120 \
scripts/verify_emulator.sh
```

结果：

- 通过：validation script fake SDK 回归。
- 通过：目标单测、全量 `:app:testDebugUnitTest`、`compileDebugAndroidTestKotlin`。
- 通过：`scripts/verify_local.sh`，覆盖 validation script tests、unit tests、
  lintDebug、debug/androidTest/release 构建、APK 资产和 ABI/体积约束。
- 通过：`pocketmind_api36_arm64` AVD，serial `emulator-5554`，API 36，
  ABI `arm64-v8a`，`CLEAN_DEVICE=1`，instrumentation `OK (12 tests)`。
- 记录：真机 `fb6272c` 构建与主 APK 安装通过，但测试 APK 安装被设备策略
  拦截为 `INSTALL_FAILED_USER_RESTRICTED`；需要在手机开发者选项中允许 USB
  安装后再跑真机 instrumentation。

## 2026-06-02 Remote memory privacy emulator regression

本轮覆盖项：

- 修正 `MainActivityComprehensiveTest` 中过时的远程模型期望：
  `请记住：...` / `remember ...` 是本地长期记忆控制命令，应等待本地确认，
  不应产生远程 chat completion 请求。
- 综合 emulator walkthrough 新增远程隐私断言：后续远程请求不得携带原始
  记忆命令、显式偏好内容、本地确认消息、本地记忆上下文或设备上下文。
- 保留普通远程聊天、远程流式取消、本地动作草稿、session 切换、模型管理与
  自定义下载入口的端到端覆盖。

验证命令：

```bash
./gradlew :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.rememberCommandBypassesRouterAndRemoteRuntime' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteRememberCommandDoesNotEnterLaterRemoteHistory'

AVD_NAME=focus_agent_api36_arm64 \
EMULATOR_ARGS='-no-snapshot -no-audio -no-window -no-boot-anim' \
EMULATOR_SELECT_TIMEOUT_SECONDS=120 \
BOOT_TIMEOUT_SECONDS=300 \
scripts/verify_emulator.sh
```

结果：

- 通过：AndroidTest Kotlin 编译与远程记忆隐私契约单测。
- 通过：`focus_agent_api36_arm64` AVD，serial `emulator-5554`，API 36，
  ABI `arm64-v8a`，instrumentation `OK (13 tests)`。
- 失败根因关闭：先前失败停在 `请记住：蓝色机器人喜欢端侧 AI` 后等待
  `模拟器回答`；当前实现和单测契约要求该命令本地处理、绕过 router 和远程
  runtime，因此应修 instrumentation 断言而不是产品代码。

## 2026-06-02 PDF shared-input text-layer and remote read guard

本轮覆盖项：

- 用户主动分享或选择 `application/pdf` 附件时，可以在本地读取有界 PDF
  文本层摘录。该能力只扫描受限 PDF bytes/content streams，支持普通/Flate
  text-showing stream，最多进入 shared-input prompt 4000 字符。
- 该轮 PDF 摘录仅覆盖文本层预览，不是版式理解或完整 PDF 解析；当时图片型/
  扫描型 PDF 没有可读文本层时保持 metadata-only，已由上方
  “PDF scanned-page OCR fallback” 增量扩展为受限本地 OCR fallback。
- `MainActivity` 会按当前推理模式选择 `ShareIntentReader` 读取策略。远程模式
  下只生成 value-free protected share signal；不会读取 `EXTRA_TEXT` 值、查询
  附件 metadata、打开文件流、解析文本层或运行 OCR。
- 远程模式 `ingestSharedInput` 的本地提示更新为“不会读取或自动发送”分享文本、
  RTF/PDF/Office 摘录、OCR 摘录或附件元数据。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.multimodal.SharedInputTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteModeRejectsShared*'
```

结果：

- 通过：PDF text-layer reader、protected shared-input prompt、远程模式 shared
  input 保护回归测试。

## 2026-06-02 Safety boundary confirmation hardening

本轮覆盖项：

- `SafetyPolicy` 不再只硬拦截私密读取和高风险外发；凡是 ToolSpec 声明会
  启动外部 Activity、外发文本、请求 Android runtime permission、调度后台任务、
  发通知，或读取剪贴板/联系人/文件/日历/Accessibility/设备上下文，都必须声明
  `ConfirmationPolicy.Required`。
- 新增真实 `ToolRegistry` 遍历回归：所有已注册边界工具在用户确认前必须返回
  `RequireConfirmation`，确认后才允许执行。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.safety.SafetyPolicyTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest'
```

结果：

- 通过：SafetyPolicy 边界权限硬门禁和真实 registry confirmation 回归测试。

## 2026-06-02 ToolResult observe/resume schema revalidation

本轮覆盖项：

- `AgentLoopRuntime.observeToolResult` 在生成本地 continuation prompt、trace
  脱敏、audit、retry 或 replan 之前，重新按当前 `ToolRegistry` 校验 successful
  `ToolResult.data`。malformed success 会转成 non-retryable `InvalidResult`
  失败，不把原始 data 或 summary 当作成功观察继续传播。
- `SkillRunExecutor` 在直接执行 tool step 和从确认点 resume 时，都先校验
  successful `ToolResult.data`，再写 `ToolFinished` trace 或绑定 step output。
  这样 schema-extra / 缺字段的成功结果不会进入后续模型步骤或 Skill outputs。
- 旧单测 fixture 同步补齐真实 output schema 字段，避免测试继续依赖生产路径不会
  接受的 sparse success。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.skill.SkillRunExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest'

./gradlew :app:testDebugUnitTest
```

结果：

- 通过：Skill executor malformed success、Agent observe malformed success、防泄漏
  回归测试。
- 通过：完整 JVM 单测 `:app:testDebugUnitTest`。

## 2026-06-02 Session-scoped Agent trace cleanup

本轮覆盖项：

- `AgentRun` / `agent_runs` 新增 nullable `sessionId`，新建普通对话 run 和
  typed recovery run 都绑定当前 active chat session；旧 Room 数据经
  `MIGRATION_9_10` 保持 `sessionId = null`，不猜测回填。
- pending action 恢复按当前 active session 过滤。切换、新建或删除 session 时
  不再把其他会话的 pending confirmation 留在 UI 上阻塞或误执行。
- 删除 active session 后，同步清理该 session 对应的 `agent_runs`、
  `agent_steps`、`pending_agent_confirmations` 和
  `agent_skill_run_checkpoints`，避免已删除会话的工具确认在重启后恢复。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentTraceStoreTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AssistantOrchestratorTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.sendMessagePassesActiveSessionIdToAgentRoute' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.deleteActiveSessionClearsSessionAgentTraceAndPendingConfirmation'

./gradlew :app:assembleDebugAndroidTest

./gradlew :app:testDebugUnitTest
```

结果：

- 通过：session-scoped trace deletion、route/recovery session binding、
  ViewModel 删除会话 pending 清理回归测试。
- 通过：AndroidTest APK 编译，包含 Room 9->10 migration 编译覆盖。
- 通过：完整 JVM 单测 `:app:testDebugUnitTest`。

## 2026-06-02 Remote OpenAI tool_calls confirmation path

本轮覆盖项：

- `RemoteChatRuntime` 保留旧 `send(): Flow<String>`，新增 tool-aware
  `sendWithTools()` 事件流；请求体可序列化 `ToolSpec` 为 OpenAI-compatible
  `tools[]`，并继续过滤 `LocalOnly` history。
- SSE / 非流式 Chat Completions 响应可解析 OpenAI-style `tool_calls`，
  支持流式 `function.arguments` 分片累积；一次多个 tool call、malformed
  arguments JSON 会结构化拒绝，不进入执行。混合 `tool_calls` /
  legacy `function_call`、以及缺少 `index` 的多工具流式片段也会拒绝。
- ViewModel 远程分支把 `RemoteChatEvent.ToolCall` 交给
  `AssistantRouter.observeModelToolRequest()`，由 Agent loop 复用
  `ToolRegistry.validate`、`SafetyPolicy`、trace/audit 和 pending
  confirmation 链路。远程模型不能直接执行 Android tool。
- 远程 tool-call 草稿消息写为 `LocalOnly`，后续远程 history 不会带上本地
  待确认动作提示；被拒绝的远程 tool-call 会刷新 trace 并停在
  `动作不可执行` 状态；用户确认后仍走现有 runtime permission /
  special access / `confirmToolRequest` / tool result privacy gate。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.runtime.RemoteChatRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AssistantOrchestratorTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteToolCallBecomesPendingConfirmationWithoutExecutingTool' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.rejectedRemoteToolCallShowsActionFailureAndRefreshesTrace'

./gradlew :app:testDebugUnitTest --rerun-tasks

./gradlew :app:assembleDebugAndroidTest
```

结果：

- 通过：RemoteChatRuntime tools body、非流式/流式 tool_calls parser、
  混合/缺 index 多工具拒绝、AssistantOrchestrator remote ToolRequest
  确认边界、ViewModel pending / rejected UI 回归测试。
- 通过：完整 JVM 单测 `:app:testDebugUnitTest --rerun-tasks`。
- 通过：AndroidTest APK 编译。

## 2026-06-02 Local model call:function confirmation path

本轮覆盖项：

- 本地模型生成的整段 `call:function{...}` 输出会被严格解析为模型工具请求；
  普通回答保持纯聊天完成，不会因为包含 `call:` 片段被抽取成工具。
- parser 只接受单个调用和 JSON object primitive 参数；坏 JSON、多段 call、
  嵌套 object/array、未知工具会 fail closed，不生成确认卡、不执行工具。
- 有效本地 call 会进入 Agent loop 的 `ToolRegistry.validate`、`SafetyPolicy`、
  trace/audit 和 pending confirmation 链路，`ToolRequest.reason` 使用固定
  `local model tool call`，避免参数进入 ToolPlanned audit summary。
- ViewModel 初始本地聊天分支会消费 `observeModelResult()` 的
  `PlanNextTool`，把 raw call 输出替换成 `LocalOnly` 标题级确认提示后再持久化；
  取消该确认并切到远程聊天时，remote history 不包含本地 call 参数。
- `observeModelToolRequest()` 增加重复 request id 防线，远程/本地模型工具请求
  不能复用同一 run 中已有的 tool request id。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.localModelToolCallOutputRequestsConfirmationAfterAnswerGeneration' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.localModelToolCallAuditSummariesDoNotPersistArguments' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.ordinaryModelAnswerStillCompletesWithoutActionParsingFallback' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.localModelUnknownToolCallOutputFailsRunWithoutPendingConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.localModelInvalidToolArgumentsFailBeforeConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.modelToolRequestCannotReusePriorToolRequestId' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.localModelCallOutputBecomesPendingConfirmationWithoutLeakingToRemoteHistory'

./gradlew :app:testDebugUnitTest

./gradlew :app:assembleDebugAndroidTest
```

结果：

- 通过：strict parser、Agent loop 本地模型工具调用确认边界、参数无关 audit
  summary、未知/非法调用 fail-closed、重复 request id 拒绝、ViewModel 本地 call
  参数不进入远程 history。
- 通过：完整 JVM 单测 `:app:testDebugUnitTest`。
- 通过：AndroidTest APK 编译。

## 2026-06-02 Device/emulator verification artifact reports

本轮覆盖项：

- `scripts/install_and_test_device.sh` 在成功、preflight 失败和 instrumentation
  失败时都会写入 `device-verification.properties`，包含状态、退出码、serial、
  API、ABI、`CLEAN_DEVICE`、`/data` 可用空间、instrumentation 状态和 APK 路径。
- `scripts/verify_emulator.sh` 写入 `emulator-verification.properties`，包含
  emulator serial、API、ABI、AVD、emulator log 路径和复用 device helper 的
  `device-verification.properties` 路径。
- fake SDK 脚本回归新增 artifact 断言，锁定无设备失败为
  `status=failed` / `instrumentation=not-run`，设备成功为
  `status=passed` / `instrumentation=passed`，instrumentation 输出失败为
  `instrumentation=failed`，模拟器成功同时产出 emulator report 和 nested device
  report。
- README、真机验收清单和 Agent core 文档同步要求 release/验收记录引用这些
  `.properties` artifact；没有 `status=passed` 摘要时，不应把设备或模拟器回归写成已通过。

验证命令：

```bash
scripts/test_validation_scripts.sh
```

结果：

- 通过：fake SDK 覆盖 device/emulator success、preflight failure 和
  instrumentation failure 的 verification report 断言。

## 2026-06-02 Skill-first web search audit/trace device smoke

本轮覆盖项：

- 新增 `MainActivitySkillUiTest.webSearchSkillFirstExecutesReadOnlyToolWithoutRemoteRuntime`，
  以真实 `MainActivity` 和 Compose UI 重置到远程模式，跳过启动期模型运行时工作后发送
  “搜一下 Kotlin 协程”。
- 测试断言该请求不依赖远程 runtime 先返回，而是直接展示
  `正在使用工具：Web 搜索`，不出现确认卡，也不打开外部浏览器。
- Agent core 文档和真机验收清单同步登记该设备/模拟器 smoke；完整 Skill 设备回归仍需后续继续扩展到多步恢复、确认后 trace/audit 和权限真实路径。

验证命令：

```bash
./gradlew :app:compileDebugAndroidTestKotlin \
  :app:testDebugUnitTest --tests com.bytedance.zgx.pocketmind.docs.AgentCoreDocumentationTest \
  --no-daemon
```

结果：

- 通过：AndroidTest 编译包含 `MainActivitySkillUiTest`。
- 通过：`AgentCoreDocumentationTest` 文档覆盖单测。
- 未执行真机/模拟器 instrumentation：当前 `adb devices -l` 没有列出已授权设备或模拟器。

## 2026-06-02 Clipboard summary/share multi-step Skill device smoke

本轮覆盖项：

- `MainActivitySkillUiTest` 新增 `clipboardSummaryShareSkillStartsAtLocalReadConfirmation`。
- 远程模式下发送“总结剪贴板并分享”时，测试断言直接进入本地多步
  `clipboard_summary_share_skill` 的第一步 `读取剪贴板` 确认卡，摘要为
  `将读取当前剪贴板文本，用于生成可分享摘要。`。
- 该 smoke 不确认读取剪贴板，因此不会读取真实剪贴板、不会进入本地模型摘要步骤、
  也不会提前展示第二步 `分享摘要` 确认卡或打开系统分享面板。
- 取消第一步后，测试继续从“后台任务”入口断言最近审计日志包含
  `UserCancelled` / `read_clipboard` / “工具执行已取消。”，并断言最近 Agent 轨迹进入
  `已取消` 且包含 `UserRejected` step。

验证命令：

```bash
./gradlew :app:compileDebugAndroidTestKotlin \
  :app:testDebugUnitTest --tests com.bytedance.zgx.pocketmind.docs.AgentCoreDocumentationTest \
  --no-daemon
```

结果：

- 通过：AndroidTest 编译包含新增多步 Skill smoke。
- 通过：`AgentCoreDocumentationTest` 文档覆盖单测。
- 未执行真机/模拟器 instrumentation：当前 `adb devices -l` 没有列出已授权设备或模拟器。

## 2026-06-02 LiteRT memory embedding fail-closed wiring

本轮覆盖项：

- 生产 `PocketMindAppContainer` 现在向 `MemoryRepository` 注入
  `LiteRtEmbeddingRuntimeFactory`，让已校验 memory asset 走统一 runtime probe
  边界。
- `LiteRtEmbeddingRuntimeFactory` 明确 fail closed：当前 `litertlm-android`
  公开 API 只有 chat/generation surface，未暴露 embedding vector API，因此不会伪造
  `EmbeddingRuntime.supportsSemanticRecall`。
- `MemoryRepository` 在 active semantic runtime 后续 query/index embedding 失败时，
  会清除 active memory model path，标记 `RuntimeLoadFailed`，用默认 hash runtime
  重算已有 entry，并继续返回 lexical fallback 命中。
- 新增 JVM 单测锁定生产 factory 的 load-failed fallback、query-time runtime 失败
  fallback，以及 index-time runtime 失败后长期记忆仍可持久化并用轻量索引召回。

验证命令：

```bash
./gradlew testDebugUnitTest --tests 'com.bytedance.zgx.pocketmind.memory.MemoryRepositoryTest'
```

结果：

- 通过：`MemoryRepositoryTest` 全类。

## 2026-06-02 Current-screen Accessibility text routing hardening

本轮覆盖项：

- `CurrentScreenTextActionParser` 和 `current_screen_text_summary_share_skill`
  现在只接受明确的屏幕文字/文本/可访问文本/visible text/Accessibility text 表达。
- 模糊屏幕理解请求，如“总结当前屏幕内容”“总结这个界面”“summarize current screen
  content”“summarize this page”“describe current screen”“what is on my
  screen”，不再规划 `read_current_screen_text` 或当前屏幕摘要分享 Skill。
- `read_current_screen_text` 工具标题、描述、输入/输出 schema 和 continuation prompt
  统一为 Accessibility 可访问文本快照边界，并明确不是截图、OCR、视觉/VLM 或语义屏幕理解。
- Tool Registry 输出合同将 `source` 固定为 `accessibility_active_window`，将
  `metadataPolicy` 固定为
  `accessibility_text_local_only_no_node_ids_bounds_or_hierarchy_persisted`；
  `source=screenshot` 或 OCR metadata policy 会被 schema validation 拒绝。
- Skill executor 测试 fixture 同步到新 source 合同，避免多步 Skill 把旧
  `source=accessibility` 结果当成有效工具输出。

验证命令：

```bash
./gradlew testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.memory.MemoryRepositoryTest' \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.skill.SkillRunExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest'
git diff --check
./gradlew testDebugUnitTest :app:compileDebugAndroidTestKotlin
```

结果：

- 通过：定向 memory/action/skill/tool/agent-loop JVM 回归。
- 通过：diff whitespace 检查。
- 通过：全量 JVM 单测和 AndroidTest Kotlin 编译。

## 2026-06-02 Value-free Skill frontier recovery

本轮覆盖项：

- `SkillRunProgressor.nextToolAfterToolResult` 新增 value-free `satisfiedStepIds`
  输入，只用于依赖满足，不注入任何旧工具或模型输出值。
- `AgentLoopRuntime.latestPendingConfirmation()` 在恢复 pending Skill 确认时缓存
  checkpoint 的 `completedStepIds` frontier；用户重新确认并观察当前工具成功后，
  后续 no-payload 工具可继续规划。
- `AgentLoopRuntime.confirmToolRequest(runId, requestId)` 即使未先调用
  `latestPendingConfirmation()`，也会从匹配的 Room pending snapshot 取回并缓存
  value-free frontier；直接确认恢复卡后的 no-payload 后续工具仍可继续规划。
- 如果后续工具参数依赖旧模型输出或私密工具输出，恢复后的 frontier 只会满足依赖，
  但 binding 因缺少真实输出值而 fail closed，不生成外发 payload 确认卡。
- `ToolRegistry` 轻量 schema 校验器开始执行 `format: date-time`，避免
  `query_calendar_availability.start/end` 这类 allowlisted pending 参数以畸形值
  跨重启恢复到确认态。
- `ToolSchemaContractTest` 同步覆盖 schema `format` fixture 与注册表 enforcement，
  防止后续声明格式但注册表未执行。
- Room pending 恢复新增矩阵覆盖全部非空 `ToolSpec.pendingArgumentAllowlist`：
  app launch/app details、calendar availability、recent notifications/files/OCR、
  current-screen text 和 cancel reminder。测试同时断言 reason/title/summary 与
  `nextActionInput` 不持久化。

验证命令：

```bash
./gradlew testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentTraceStoreTest' \
  --tests 'com.bytedance.zgx.pocketmind.skill.SkillRunProgressorTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.restoredValueFreeModelFrontierLetsMiddleToolContinueToNextNoPayloadTool' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.restoredValueFreeModelFrontierSurvivesDirectConfirmWithoutPendingLookup' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.restoredValueFreeFrontierDoesNotRecoverModelOutputForPayloadBinding'
```

结果：

- 通过：定向 ToolRegistry / TraceStore / SkillRunProgressor / Agent loop frontier
  恢复回归。
- 通过：`AgentCoreDocumentationTest` 文档契约。
- 通过：`git diff --check` whitespace 检查。
- 通过：全量 `./gradlew testDebugUnitTest :app:compileDebugAndroidTestKotlin`。

## 2026-06-02 Periodic check Agent tool

本轮覆盖项：

- 新增 confirmed `configure_periodic_check` 工具，输入为闭合 schema：
  `enabled` 加 bounded interval/spacing/grace 与 battery constraints。该工具只管理
  WorkManager-backed 本地提醒巡检策略，不执行后台聊天、屏幕扫描、文件扫描或任意周期任务。
- `ActionExecutor` 将该工具接入 `BackgroundTaskScheduler.setPeriodicCheckPolicy`
  和 `disablePeriodicCheckPolicy`；启用时沿用通知权限检查，缺 scheduler、缺权限、
  参数非法或 WorkManager 入队失败都返回结构化 `ToolResult`。
- `ToolRegistry` 声明 BackgroundTask capability、通知/后台调度权限、closed output
  schema 和低语义 pending argument allowlist。成功启用可输出 bounded
  `recoveryToolName=configure_periodic_check`/`recoveryEnabled=false`，但 Agent loop
  当前不会把它升级成可点击 recovery action；关闭仍走独立 confirmed tool。
- `PeriodicCheckActionParser` 和 `BuiltInSkillRuntime` 新增 conservative Skill-first
  路由，接受明确开启/关闭周期检查与可选间隔，拒绝 API/实现/解释类讨论输入。
- Agent loop 防重试矩阵加入该后台副作用工具，即使底层失败标记 retryable 也不自动重放。

验证命令：

```bash
./gradlew testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest' \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest' \
  --tests 'com.bytedance.zgx.pocketmind.AgentRuntimePermissionPolicyTest' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.skillFirstPeriodicCheckBypassesActionPlannerAndRequestsConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.retryableSideEffectToolFailuresDoNotScheduleAutomaticRetry' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.periodicCheckObservationDoesNotSurfaceUnsupportedRecoveryAction' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentTraceStoreTest.roomStoreRestoresEveryToolSpecAllowlistedPendingArgumentShape'
```

结果：

- 通过：定向 action/tool/permission/skill/agent-loop/pending restore 回归。
- 通过：`AgentCoreDocumentationTest` 文档契约。
- 通过：`git diff --check` whitespace 检查。
- 通过：全量 `./gradlew testDebugUnitTest :app:compileDebugAndroidTestKotlin`。

## 2026-06-02 Skill manifest runtime reauthorization

本轮覆盖项：

- 新增 `SkillManifest.authorizationContractHash()`，把当前 runtime 重新授权范围收窄到
  id/version/risk、规范化后的 required tools 与 canonical input schema；title、
  description、trigger examples 和 schema JSON 空白/字段顺序不影响旧 pending 恢复。
- `AgentLoopRuntime` 在初始 Skill-first plan、replanner/continuation 附带的
  `SkillPlan`、恢复 pending confirmation、direct `confirmToolRequest(runId, requestId)`
  以及 observation 后的 Skill continuation 入口统一执行当前
  `SkillRuntime.manifests()` 重新授权。
- 持久化 checkpoint 仍使用 `checkpointHash()` 校验 checkpoint 与 persisted
  `SkillPlan.manifest` 的完整性；runtime 授权使用独立 contract hash，避免把展示文案漂移
  误判成执行合约漂移。
- 恢复出的 Skill pending 如果当前 runtime 已删除该 manifest，或 version/risk/
  required tools/input schema contract 变化，会清理 pending/checkpoint、记录 rejection
  和 failed step，并把 run fail closed；direct confirm 不会 fallback 到旧
  `ToolRequested` trace 继续执行。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.skillFirstPlanMustMatchCurrentRuntimeManifestContract \
  --tests com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.restoredSkillPendingSurvivesDisplayOnlyManifestDrift \
  --tests com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.restoredSkillPendingFailsClosedWhenCurrentRuntimeManifestContractChanged \
  --tests com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.directConfirmRestoredSkillPendingFailsClosedWhenCurrentRuntimeManifestContractChanged
```

结果：

- 通过：定向 Agent loop manifest reauthorization 回归。
- 通过：`AgentCoreDocumentationTest` 文档契约。
- 通过：`git diff --check` whitespace 检查。
- 通过：全量 `./gradlew :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin`。

## 2026-06-02 Preference-family forget commands

本轮覆盖项：

- `LongTermMemoryControls` 新增 `forgetPreference(target)`，在精确 deterministic
  preference id 删除之外，支持按 answer-style preference family 删除。用户可以用
  “忘记：回答语言偏好” 或 “forget answer length preference” 删除对应 response-language
  / response-length 偏好，而不删除无关长期记忆。
- `MemoryRepository` 的 answer-style family 判断改为 token-aware Latin term 匹配，
  避免 `english` 误命中 `length` 这类子串；中文关键词仍保持子串匹配。
- `PocketMindViewModel` 的显式 forget 命令继续作为 `LocalOnly` 控制命令处理，
  不进入 chat/action router 或 remote runtime；忘记偏好族后 memory hits 和长期记忆
  UI 同步刷新。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests com.bytedance.zgx.pocketmind.memory.MemoryRepositoryTest.explicitPreferenceForgetConflictKeysRecognizeFamilyTargets \
  --tests com.bytedance.zgx.pocketmind.memory.MemoryRepositoryTest.forgetPreferenceCanDeleteResponsePreferenceFamily \
  --tests com.bytedance.zgx.pocketmind.memory.MemoryRepositoryTest.forgetPreferenceStillDeletesExactUnrelatedPreference \
  --tests com.bytedance.zgx.pocketmind.PocketMindViewModelTest.forgetPreferenceFamilyCommandDeletesMatchingPreferenceAndBypassesRemoteRuntime
```

结果：

- 通过：定向 MemoryRepository / ViewModel preference-family forget 回归。
- 通过：`AgentCoreDocumentationTest` 文档契约。
- 通过：`git diff --check` whitespace 检查。
- 通过：全量 `./gradlew :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin`。

## 2026-06-04 Remote model-first planning and typed web evidence

本轮覆盖项：

- 远程模型模式新增 `AgentRunOptions(initialPlanningMode=ModelFirstRemoteTools)`；
  本地模式仍保持 `RuleFirst`。远程模式先执行 direct built-in Skill preflight，
  保护本地确认/LocalOnly 工具路径；未被 direct Skill 命中的输入再由模型选择
  safe planning tools。
- 远程工具 schema 新增 `RemoteToolScope.ModelPlanning`：公开证据工具可无确认执行；
  非私密、非 critical 的草稿/导航/分享/本地后台计划类工具只作为模型规划候选，
  仍必须经过本地 registry、safety 和用户确认。LocalOnly 设备上下文工具不暴露给
  远程 planning。
- `web_search` 改成 typed evidence provider。`general` 不再因 query 含天气词自动走
  Open-Meteo；只有 `weather_current` 进入天气 evidence path。`resultsJson` 使用
  evidence schema v1，携带 `schemaVersion`、`searchMode`、`retrievedAt`、
  `freshness`、`sources` 和 bounded `results`。
- 新增 `ToolExecutionBoundary`，集中处理工具执行异常/timeout 映射和公开证据批次
  retry，只重试 retryable 失败 request，不重复执行成功 request。

验证命令：

```bash
./gradlew --no-daemon -Pkotlin.incremental=false :app:testDebugUnitTest \
  --tests com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest \
  --tests com.bytedance.zgx.pocketmind.orchestration.AssistantOrchestratorTest \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteModeUsesModelFirstPlanningAndExposesSafePlanningToolsToRemoteRuntime'

./gradlew --no-daemon -Pkotlin.incremental=false :app:testDebugUnitTest \
  --tests com.bytedance.zgx.pocketmind.tool.WebSearchProviderTest \
  --tests com.bytedance.zgx.pocketmind.tool.ToolRegistryTest \
  --tests com.bytedance.zgx.pocketmind.tool.RoutingAndValidatingToolExecutorTest \
  --tests com.bytedance.zgx.pocketmind.tool.ToolExecutionBoundaryTest

scripts/doctor.sh && scripts/verify_local.sh

AVD_NAME=focus_agent_api36_arm64 \
EMULATOR_ARGS='-no-window -no-audio -no-snapshot-save -no-boot-anim' \
EMULATOR_SELECT_TIMEOUT_SECONDS=120 \
BOOT_TIMEOUT_SECONDS=300 \
scripts/regression_emulator.sh
```

结果：

- 通过：Agent loop / orchestrator / ViewModel 远程模型优先规划 targeted 回归。
- 通过：Web search typed evidence、Tool Registry、RoutingAndValidating executor、
  ToolExecutionBoundary targeted 回归。
- 通过：`scripts/doctor.sh && scripts/verify_local.sh`，包含本地环境检查、脚本校验、
  debug/release 构建、JVM 单测和 lint。
- 首次完整 emulator regression（`build/verification/regression-emulator-20260604-013007`）
  暴露 7 个远程模式 UI timeout，根因是无条件 model-first 跳过了 direct local
  Skill confirmations。修复为 direct Skill preflight 后重跑完整 regression。
- 通过：`build/verification/regression-emulator-20260604-013940/regression-emulator.properties`
  `status=passed`；nested emulator/device reports 均 `status=passed`，
  `instrumentation=passed`，`actual_android_test_count=26`，且
  `instrumentation.txt` 非空。

## 2026-06-06 Release signing, AAB, and license evidence

本轮覆盖项：

- 新增 AAB release artifact 生成与扫描验证；`scan_android_artifacts.sh` 现在同时支持
  APK/AAB，且 AAB 签名验证必须看到 `jar verified.`，不会再把 `jar is unsigned`
  误判为通过。
- 新增 `sign_release_artifacts.sh`，生产签名只接受外部注入的
  `RELEASE_KEYSTORE`、`RELEASE_KEY_ALIAS`、`RELEASE_KEYSTORE_PASSWORD`、
  `RELEASE_KEY_PASSWORD`，不在仓库内保存私钥或密码。
- 新增 `collect_model_license_metadata.sh` 与
  `docs/model_license_metadata.json`，记录 Hugging Face API 返回的 license tag、
  card license、model sha、last modified 与 gated 状态；该文件只作为证据，
  不替代 `docs/model_license_review.json` 的人工批准。
- `verify_release_gate.sh` 在 public AAB 场景下不会把默认本地 unsigned APK 混入
  signed AAB 扫描；当要求 `REQUIRE_AAB=1` 与
  `REQUIRE_SIGNED_ARTIFACT=1` 时，最终分发 artifact 必须单独显式通过签名扫描。

验证命令：

```bash
./gradlew :app:bundleRelease
scripts/collect_model_license_metadata.sh
scripts/test_validation_scripts.sh
scripts/verify_local.sh

RELEASE_KEYSTORE="$HOME/.android/debug.keystore" \
RELEASE_KEY_ALIAS=androiddebugkey \
RELEASE_KEYSTORE_PASSWORD=android \
RELEASE_KEY_PASSWORD=android \
ALLOW_DEBUG_KEYSTORE=1 \
SIGNED_APK=app/build/outputs/apk/release/app-release-local-signed-smoke.apk \
SIGNED_AAB=app/build/outputs/bundle/release/app-release-local-signed-smoke.aab \
REPORT_FILE=build/verification/signing-smoke/signing.properties \
scripts/sign_release_artifacts.sh

ARTIFACT_DIR=build/verification/release-gate-signed-smoke \
PERF_BASELINE_FILE=build/verification/release-gate-signed-smoke/perf-baseline.properties \
RELEASE_AAB=app/build/outputs/bundle/release/app-release-local-signed-smoke.aab \
REQUIRE_AAB=1 \
REQUIRE_SIGNED_ARTIFACT=1 \
VERIFY_MODEL_LICENSES=0 \
VERIFY_CONTRACT_TESTS=0 \
scripts/verify_release_gate.sh
```

结果：

- 通过：AAB 构建与 artifact scan；unsigned AAB 在 `--require-signed` 下会失败。
- 通过：debug keystore signing smoke，证明 APK/AAB 签名脚本和证书指纹扫描链路可用；
  该证书不是 production signing，不能作为正式发布签名。
- 通过：signed AAB release gate smoke（关闭 license gate）；
  `build/verification/release-gate-signed-smoke/release-gate.properties`
  `status=passed`。
- 通过：license 负向门禁；在 signed AAB、privacy scan、artifact scan 和 perf
  baseline 都通过后，`VERIFY_MODEL_LICENSES=1` 的唯一失败原因是
  `Model license review is incomplete.`。

仍阻塞正式 RC：

- 需要 release owner 提供 production keystore 并运行 `sign_release_artifacts.sh`。
- 需要人工完成 `docs/model_license_review.json` 中所有模型的 license /
  redistribution 批准。
- 需要在固定真机上生成真实 `perf-baseline`，当前 signed gate smoke 只验证门禁链路，
  不代表正式性能基线。

## 2026-06-06 Debug-keystore release signing guard

本轮覆盖项：

- `sign_release_artifacts.sh` 默认拒绝 Android debug keystore 或 Android Debug
  certificate，避免把本地 smoke 签名误当作 production signing。
- `scan_android_artifacts.sh --require-signed` 默认拒绝 Android Debug
  certificate；`--allow-debug-certificate` 只用于 smoke scan，因此 release gate
  不会接受外部传入的 debug-signed APK/AAB。
- 只有显式设置 `ALLOW_DEBUG_KEYSTORE=1` 时，debug keystore 才能用于本地 signing
  smoke；生成的 signing report 会记录 `signingMode=debug-smoke`。
- 通过 SDK adb 检查当前没有 attached device，因此真机安装、真机 instrumentation 和
  真机 perf baseline 仍不能完成。

验证命令：

```bash
scripts/test_validation_scripts.sh

RELEASE_KEYSTORE="$HOME/.android/debug.keystore" \
RELEASE_KEY_ALIAS=androiddebugkey \
RELEASE_KEYSTORE_PASSWORD=android \
RELEASE_KEY_PASSWORD=android \
ALLOW_DEBUG_KEYSTORE=1 \
SIGNED_APK=app/build/outputs/apk/release/app-release-local-signed-smoke.apk \
SIGNED_AAB=app/build/outputs/bundle/release/app-release-local-signed-smoke.aab \
REPORT_FILE=build/verification/signing-smoke/signing.properties \
scripts/sign_release_artifacts.sh

ARTIFACT_DIR=build/verification/release-gate-debug-cert-negative \
PERF_BASELINE_FILE=build/verification/release-gate-signed-smoke/perf-baseline.properties \
RELEASE_AAB=app/build/outputs/bundle/release/app-release-local-signed-smoke.aab \
REQUIRE_AAB=1 \
REQUIRE_SIGNED_ARTIFACT=1 \
VERIFY_MODEL_LICENSES=0 \
VERIFY_CONTRACT_TESTS=0 \
scripts/verify_release_gate.sh

scripts/verify_local.sh
```

结果：

- 通过：脚本单测覆盖 “未提供 keystore 环境失败” 和 “debug keystore 默认拒绝”。
- 通过：脚本单测现场生成 debug-signed fake AAB，确认普通 signed artifact scan
  拒绝它，只有 `--allow-debug-certificate` smoke scan 会放行。
- 通过：显式 `ALLOW_DEBUG_KEYSTORE=1` 的 debug signing smoke；
  `build/verification/signing-smoke/signing.properties` 包含
  `status=passed` 和 `signingMode=debug-smoke`。
- 通过：release gate 负向验证；debug-signed AAB 的 signing status 为
  `verified`，但 artifact scan 因 `CN=Android Debug` certificate 失败。
- 通过：`scripts/verify_local.sh`。

仍阻塞正式 RC：

- 需要 release owner 提供非 debug 的 production keystore 并保持
  `ALLOW_DEBUG_KEYSTORE` 未设置。
- 需要连接固定目标真机后运行正式设备验收和真实 perf baseline。
- 需要人工完成模型 license / redistribution review。

## 2026-06-06 Latest emulator regression on current HEAD

本轮覆盖项：

- 按当前要求不连接真机，直接使用模拟器验证最新提交。
- 使用 `focus_agent_api36_arm64` AVD，以 headless 参数启动，执行完整
  `scripts/regression_emulator.sh`。

验证命令：

```bash
ANDROID_HOME=/Users/bytedance/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/bytedance/Library/Android/sdk \
AVD_NAME=focus_agent_api36_arm64 \
EMULATOR_ARGS='-no-window -no-audio -no-snapshot-save -no-boot-anim' \
EMULATOR_SELECT_TIMEOUT_SECONDS=120 \
BOOT_TIMEOUT_SECONDS=300 \
scripts/regression_emulator.sh
```

结果：

- 通过：`build/verification/regression-emulator-20260606-131714/regression-emulator.properties`
  `status=passed`。
- 通过：模拟器 `emulator-5554`，API 36，`arm64-v8a`，AVD
  `focus_agent_api36_arm64`。
- 通过：debug APK 与 androidTest APK 均安装成功。
- 通过：instrumentation `OK (26 tests)`，实际运行 26 个 AndroidTest，与源码计数一致。
- 通过：嵌套 `emulator-verification.properties` 和
  `device-verification.properties` 均记录 `status=passed`，且
  `clean_device=1`。

仍阻塞正式 RC：

- 当前验证满足模拟器回归要求，但不替代 production signing、模型 license /
  redistribution 人工批准、正式发布隐私审查、目标真机验收和真实 perf baseline。

## 2026-06-06 Release validation physical-device evidence hardening

本轮覆盖项：

- 使用只读多 Agent 审查 release validation gate，发现
  `physicalDevice.reportPath` 可能引用 emulator helper 生成的 nested
  `target=device` report，从而把 `emulator-*` serial 冒充成真机证据。
- 修复 `scripts/verify_release_validation_record.sh`，要求
  `physicalDevice.serial` 非空且不能以 `emulator-` 开头，并拒绝 report 中
  `serial=emulator-*` 的 device report。
- `scripts/test_validation_scripts.sh` 增加回归：emulator nested device report
  即使 `target=device`、`instrumentation=passed`、测试数量满足，也不能作为
  physical-device evidence 通过。

验证命令：

```bash
scripts/test_validation_scripts.sh
scripts/verify_local.sh
```

结果：

- 通过：validation 脚本单测覆盖 emulator-as-physical 负向用例。
- 通过：`scripts/verify_local.sh`。

仍阻塞正式 RC：

- 当前只修门禁逻辑，不连接真机；正式 RC 仍需要目标真机验收、真实 perf
  baseline 和人工发布记录批准。

## 2026-06-06 Previous emulator regression on current HEAD

本轮覆盖项：

- 按当前要求不连接真机，直接使用模拟器验证最新提交。
- 使用 `focus_agent_api36_arm64` AVD，以 headless 参数启动，执行完整
  `scripts/regression_emulator.sh`。

验证命令：

```bash
AVD_NAME=focus_agent_api36_arm64 \
EMULATOR_ARGS='-no-window -no-audio -no-snapshot-save -no-boot-anim' \
EMULATOR_SELECT_TIMEOUT_SECONDS=120 \
BOOT_TIMEOUT_SECONDS=300 \
scripts/regression_emulator.sh
```

结果：

- 通过：`build/verification/regression-emulator-20260606-113802/regression-emulator.properties`
  `status=passed`。
- 通过：模拟器 `emulator-5554`，API 36，`arm64-v8a`，AVD
  `focus_agent_api36_arm64`。
- 通过：debug APK 与 androidTest APK 均安装成功。
- 通过：instrumentation `OK (26 tests)`，实际运行 26 个 AndroidTest，与源码计数一致。

仍阻塞正式 RC：

- 当前验证满足模拟器回归要求，但不替代 production signing、模型 license /
  redistribution 人工批准和正式发布隐私审查。

## 2026-06-06 Privacy review release gate

本轮覆盖项：

- 新增 `docs/privacy_review.json`，记录 release/security/legal 三方对当前
  `docs/privacy_notice.md` SHA 的 review 状态；当前状态为
  `pending_manual_review`，用于阻塞 public RC。
- 新增 `scripts/verify_privacy_review.sh`，校验 review 文件、notice 路径、notice
  SHA、三方 reviewer、approved decision，以及真实且不晚于当前日期的 review date。
- `scripts/verify_release_gate.sh` 新增 `VERIFY_PRIVACY_REVIEW=1`，public gate
  开启后会要求隐私 review 完成。

验证命令：

```bash
scripts/test_validation_scripts.sh

ARTIFACT_DIR=build/verification/release-gate-privacy-negative \
VERIFY_PRIVACY_REVIEW=1 \
PERF_BASELINE_FILE=build/verification/release-gate-privacy-negative/perf-baseline.properties \
VERIFY_MODEL_LICENSES=0 \
VERIFY_CONTRACT_TESTS=0 \
scripts/verify_release_gate.sh
```

结果：

- 通过：脚本单测覆盖 pending privacy review 失败、approved 当前 notice 成功。
- 通过：脚本单测覆盖 privacy review 未来日期失败。
- 通过：release gate 集成负向验证；`VERIFY_PRIVACY_REVIEW=1` 时，当前 pending
  `docs/privacy_review.json` 生成
  `build/verification/release-gate-privacy-negative/privacy-review.properties`
  `status=failed`。

仍阻塞正式 RC：

- 需要 release/security/legal owner 在 `docs/privacy_review.json` 中批准当前
  privacy notice SHA。

## 2026-06-06 Public release profile and signing certificate pin

本轮覆盖项：

- `scan_android_artifacts.sh` 新增 `--expected-certificate-sha256`，signed APK/AAB
  可以绑定 production upload certificate SHA-256；证书指纹支持大小写和冒号格式规范化。
- `sign_release_artifacts.sh` 支持 `EXPECTED_SIGNING_CERT_SHA256`，生产签名后立即
  校验产物证书是否匹配预期；production signing 未提供该值会 fail closed。
- `verify_release_gate.sh` 新增 `PUBLIC_RELEASE=1` profile，自动启用
  `VERIFY_PRIVACY_REVIEW=1`、`VERIFY_MODEL_LICENSES=1`、`REQUIRE_AAB=1`、
  `REQUIRE_SIGNED_ARTIFACT=1`，并要求 `EXPECTED_SIGNING_CERT_SHA256`。

验证命令：

```bash
scripts/test_validation_scripts.sh

ARTIFACT_DIR=build/verification/release-gate-public-profile-negative \
PUBLIC_RELEASE=1 \
PERF_BASELINE_FILE=build/verification/release-gate-public-profile-negative/perf-baseline.properties \
scripts/verify_release_gate.sh
```

结果：

- 通过：脚本单测覆盖 signed artifact 证书指纹匹配成功与不匹配失败。
- 通过：脚本单测覆盖 production signing 未提供
  `EXPECTED_SIGNING_CERT_SHA256` 时失败；显式 debug smoke 不受影响。
- 通过：脚本单测覆盖 `PUBLIC_RELEASE=1` 缺少
  `EXPECTED_SIGNING_CERT_SHA256` 时失败，并确认 public profile 自动打开 privacy
  review、model license、AAB 和 signed-artifact gate。
- 通过：真实 release gate 负向验证；
  `build/verification/release-gate-public-profile-negative/release-gate.properties`
  记录 `publicRelease=1`、`verifyPrivacyReview=1`、`verifyModelLicenses=1`、
  `requireAab=1`、`requireSignedArtifact=1`，并因缺少
  `EXPECTED_SIGNING_CERT_SHA256` 生成 failed `signing-cert.properties`。

仍阻塞正式 RC：

- 需要 release owner 提供 production upload certificate SHA-256，并用
  `PUBLIC_RELEASE=1 EXPECTED_SIGNING_CERT_SHA256=...` 运行最终 gate。

## 2026-06-06 Production signing requires certificate pin

本轮覆盖项：

- `sign_release_artifacts.sh` 在 production signing 模式下强制要求
  `EXPECTED_SIGNING_CERT_SHA256`；未提供时直接失败，不会进入签名步骤。
- `ALLOW_DEBUG_KEYSTORE=1` 的本地 smoke signing 仍允许不传 production cert pin，
  且报告继续标记 `signingMode=debug-smoke`。

验证命令：

```bash
scripts/test_validation_scripts.sh
scripts/verify_local.sh

RELEASE_KEYSTORE="$HOME/.android/debug.keystore" \
RELEASE_KEY_ALIAS=androiddebugkey \
RELEASE_KEYSTORE_PASSWORD=android \
RELEASE_KEY_PASSWORD=android \
ALLOW_DEBUG_KEYSTORE=1 \
SIGNED_APK=app/build/outputs/apk/release/app-release-local-signed-smoke.apk \
SIGNED_AAB=app/build/outputs/bundle/release/app-release-local-signed-smoke.aab \
REPORT_FILE=build/verification/signing-smoke/signing.properties \
scripts/sign_release_artifacts.sh
```

结果：

- 通过：脚本单测覆盖 production signing 缺少
  `EXPECTED_SIGNING_CERT_SHA256` 时失败。
- 通过：`scripts/verify_local.sh`。
- 通过：显式 debug signing smoke；`build/verification/signing-smoke/signing.properties`
  包含 `status=passed`、`signingMode=debug-smoke` 和空
  `expectedSigningCertSha256`。

仍阻塞正式 RC：

- 需要 release owner 提供 production keystore 和 production upload certificate
  SHA-256。

## 2026-06-06 Structured model license review gate

本轮覆盖项：

- 新增 `scripts/verify_model_license_review.sh`，结构化校验
  `docs/model_license_review.json` 与 `docs/model_license_metadata.json`。
- 校验项包括：metadata 对齐的模型 ID、`status=approved`、
  `redistributionDecision=approved`、license 名称、HTTPS license URL 或本地 license
  文件路径、attribution/notice、reviewer、真实且不晚于当前日期的
  `YYYY-MM-DD` review date。
- `scripts/verify_release_gate.sh` 的 `VERIFY_MODEL_LICENSES=1` 不再使用 grep，
  改为调用结构化 license review verifier。

验证命令：

```bash
scripts/test_validation_scripts.sh
scripts/verify_local.sh

ARTIFACT_DIR=build/verification/release-gate-model-license-negative \
VERIFY_MODEL_LICENSES=1 \
PERF_BASELINE_FILE=build/verification/release-gate-model-license-negative/perf-baseline.properties \
scripts/verify_release_gate.sh
```

结果：

- 通过：脚本单测覆盖 incomplete model license review 失败。
- 通过：脚本单测覆盖 metadata-aligned approved review 成功。
- 通过：脚本单测覆盖 privacy review 和 model license review 的未来日期失败。
- 通过：release gate 集成负向验证；当前 pending
  `docs/model_license_review.json` 会生成
  `build/verification/release-gate-model-license-negative/model-license-review.properties`
  `status=failed`，并列出每个模型缺失的批准字段。

仍阻塞正式 RC：

- 需要人工完成所有推荐模型的 license / redistribution / attribution review。

## 2026-06-06 Model license manifest binding hardening

本轮覆盖项：

- 使用只读多 Agent 审查 release gates，确认模型 license gate 不能只让
  `docs/model_license_review.json` 和 `docs/model_license_metadata.json` 互相背书；
  必须直接绑定 `docs/model_manifest.md` 中当前推荐模型的 ID、repository 和
  pinned upstream revision。
- `scripts/verify_model_license_review.sh` 新增 manifest 输入，要求 review、
  metadata、manifest 三者模型 ID 顺序一致；metadata repository/API URL/
  manifest revision 必须匹配 manifest；review repository/upstreamRevision 必须匹配
  manifest。
- license source 如果是 Hugging Face URL，必须指向同一个 manifest repository；
  approved review date 不能早于 metadata `recordedAt` 日期；license name 必须和
  metadata card license 或 license tag 对齐。
- `scripts/collect_model_license_metadata.sh` 改为从 `docs/model_manifest.md`
  派生推荐模型列表，review 文件只提供人工状态，不再决定 metadata 覆盖范围。

验证命令：

```bash
bash -n scripts/verify_model_license_review.sh scripts/collect_model_license_metadata.sh scripts/test_validation_scripts.sh
scripts/test_validation_scripts.sh
scripts/verify_local.sh
```

结果：

- 通过：脚本单测覆盖 manifest/metadata/review aligned approval 成功。
- 通过：脚本单测覆盖 Hugging Face license source 指向错误 repository 失败。
- 通过：脚本单测覆盖 review date 早于 metadata collection 失败。
- 通过：当前 pending `docs/model_license_review.json` 仍保持 fail-closed。

仍阻塞正式 RC：

- 需要人工完成所有推荐模型的 license / redistribution / attribution review。

## 2026-06-06 Review date validity gates

本轮覆盖项：

- `scripts/verify_privacy_review.sh` 的 `reviewDate` 校验升级为真实
  `YYYY-MM-DD` 日期，且不得晚于当前日期。
- `scripts/verify_model_license_review.sh` 的 `reviewDate` 校验同样升级为真实
  `YYYY-MM-DD` 日期，且不得晚于当前日期。

验证命令：

```bash
scripts/test_validation_scripts.sh
scripts/verify_local.sh
```

结果：

- 通过：脚本单测覆盖 privacy review 的未来日期失败。
- 通过：脚本单测覆盖 model license review 的未来日期失败。
- 通过：`scripts/verify_local.sh`。

## 2026-06-06 Release mapping gate

本轮覆盖项：

- 新增 `scripts/verify_release_mapping.sh`，校验 release R8/ProGuard
  `mapping.txt` 存在且非空，并生成 `release-mapping.properties`，记录
  mapping 路径、SHA-256 和字节数。
- `scripts/verify_release_gate.sh` 新增 `VERIFY_RELEASE_MAPPING=1`；正式
  `PUBLIC_RELEASE=1` profile 会自动开启 mapping gate。
- release checklist 明确要求归档 `release-mapping.properties` 和对应
  mapping 文件，避免正式包发布后无法反混淆 crash stack。

验证命令：

```bash
scripts/test_validation_scripts.sh
scripts/verify_local.sh
scripts/verify_release_mapping.sh --report build/verification/release-mapping/release-mapping.properties

AVD_NAME=focus_agent_api36_arm64 \
EMULATOR_ARGS='-no-window -no-audio -no-snapshot-save -no-boot-anim' \
EMULATOR_SELECT_TIMEOUT_SECONDS=120 \
BOOT_TIMEOUT_SECONDS=300 \
scripts/regression_emulator.sh
```

结果：

- 通过：脚本单测覆盖 release mapping verifier 成功、缺失失败、空文件失败。
- 通过：脚本单测覆盖 `PUBLIC_RELEASE=1` 自动启用 `verifyReleaseMapping=1`。
- 通过：脚本单测覆盖 `VERIFY_RELEASE_MAPPING=1` 时 release gate 对缺失
  mapping fail-closed。
- 通过：`scripts/verify_local.sh`。
- 通过：真实 release mapping verifier；报告为
  `build/verification/release-mapping/release-mapping.properties`。
- 通过：仅模拟器回归；`focus_agent_api36_arm64` / `emulator-5554` / API 36 /
  `arm64-v8a`，`build/verification/regression-emulator-20260606-121722/regression-emulator.properties`
  记录 `status=passed`，instrumentation 为 `OK (26 tests)`。

## 2026-06-06 Release record gate

本轮覆盖项：

- 新增 `docs/release_record.json`，把 release owner、reviewer、target
  channel、Gradle version、Git commit、artifact checksum、signing certificate
  fingerprint、verification reports、unsupported capabilities、Agent behavior
  summary 和 blocker 决策收敛为机器可读记录。
- 新增 `scripts/verify_release_record.sh`，校验 release record 已 approved，
  且 Gradle 版本、当前 checkout 可追溯的 Git commit、artifact SHA/size、证书
  SHA、证据文件和 blocker resolved/accepted 状态都一致。
- `scripts/verify_release_gate.sh` 新增 `VERIFY_RELEASE_RECORD=1`；
  `PUBLIC_RELEASE=1` profile 会自动开启 release record gate。

验证命令：

```bash
scripts/test_validation_scripts.sh
scripts/verify_local.sh
```

结果：

- 通过：脚本单测覆盖 pending release record 失败。
- 通过：脚本单测覆盖 approved release record 成功。
- 通过：脚本单测覆盖 future release date 和 artifact SHA mismatch 失败。
- 通过：脚本单测覆盖 `PUBLIC_RELEASE=1` 自动启用 `verifyReleaseRecord=1`。
- 通过：脚本单测覆盖 `VERIFY_RELEASE_RECORD=1` 时 release gate 对 pending
  record fail-closed。

## 2026-06-06 Public release record artifact binding

本轮覆盖项：

- 使用只读多 Agent 审查 release gates，发现 `PUBLIC_RELEASE=1` 会扫描 signed AAB，
  但 `scripts/verify_release_record.sh` 仍可接受记录中的内部渠道或另一个 APK/AAB。
- `scripts/verify_release_gate.sh` 在 public profile 下向 release record verifier
  传入 public context、最终 `RELEASE_AAB` 路径、AAB SHA-256 和
  `EXPECTED_SIGNING_CERT_SHA256`。
- `scripts/verify_release_record.sh` 在 public context 下要求 `targetChannel`
  为 `open_testing`、`staged_production` 或 `full_production`，artifact type
  必须为 `aab`，并且 artifact path、SHA-256、签名证书 SHA-256 必须与
  release gate 期望值一致。

验证命令：

```bash
bash -n scripts/verify_release_record.sh scripts/verify_release_gate.sh scripts/test_validation_scripts.sh
scripts/test_validation_scripts.sh
scripts/verify_local.sh
```

结果：

- 通过：脚本单测覆盖 public context 拒绝 `internal_testing`。
- 通过：脚本单测覆盖 public context 接受匹配的 public AAB record。
- 通过：脚本单测覆盖 public context 拒绝 artifact path/SHA 与最终 AAB 不一致。

仍阻塞正式 RC：

- 需要 production signing、真实 final AAB 和 release owner 批准后的
  `docs/release_record.json`。

## 2026-06-06 Perf baseline physical-device gate hardening

本轮覆盖项：

- 使用只读多 Agent 审查 release gates，发现 `scripts/verify_perf_baseline.sh`
  主要检查字段存在，无法充分证明“最终真机 RC SLO”。
- `scripts/verify_perf_baseline.sh` 新增严格语义：拒绝 `emulator-*` serial，
  要求 `abi=arm64-v8a`、Android API 28-36、release artifact SHA-256 为 64 位
  hex，关键耗时/内存字段必须大于 0，`recordedAt` 必须是 UTC、非未来且在
  `MAX_RECORD_AGE_DAYS` 窗口内。
- `scripts/verify_release_gate.sh` 读取当前 Gradle `versionName` 并传给 perf
  verifier，要求 RC baseline 的 `appVersion` 与本次 release 版本一致。

验证命令：

```bash
bash -n scripts/verify_perf_baseline.sh scripts/verify_release_gate.sh scripts/collect_perf_baseline.sh scripts/test_validation_scripts.sh
scripts/test_validation_scripts.sh
scripts/verify_local.sh
```

结果：

- 通过：脚本单测覆盖完整 perf baseline 成功和 artifact SHA 匹配成功。
- 通过：脚本单测覆盖 emulator serial、0ms 关键耗时、future `recordedAt`、
  mismatched artifact SHA 失败。
- 通过：release gate fixture 覆盖当前 Gradle `versionName` 绑定。

仍阻塞正式 RC：

- 需要在目标真机上采集真实 RC perf baseline；当前不连接真机。

## 2026-06-06 Store policy gate

本轮覆盖项：

- 新增 `docs/store_policy_record.json`，把 Store listing、Data safety、隐私
  policy URL、模型下载说明、Android manifest 权限用途和 Usage
  Access/Accessibility/MediaProjection 特殊访问披露收敛为机器可读记录。
- 新增 `scripts/verify_store_policy_record.sh`，校验 store policy 已
  approved，隐私 notice SHA 与当前文件一致，manifest 权限与记录完全一致，
  且每个权限/特殊访问都有用途说明。
- `scripts/verify_release_gate.sh` 新增 `VERIFY_STORE_POLICY=1`；
  `PUBLIC_RELEASE=1` profile 会自动开启 store policy gate。

验证命令：

```bash
scripts/test_validation_scripts.sh
scripts/verify_local.sh
```

结果：

- 通过：脚本单测覆盖 pending store policy 失败。
- 通过：脚本单测覆盖 approved store policy 成功。
- 通过：脚本单测覆盖 privacy notice SHA mismatch 和 manifest permission
  mismatch 失败。
- 通过：脚本单测覆盖 `PUBLIC_RELEASE=1` 自动启用 `verifyStorePolicy=1`。
- 通过：脚本单测覆盖 `VERIFY_STORE_POLICY=1` 时 release gate 对 pending
  record fail-closed。

## 2026-06-06 Release operations gate

本轮覆盖项：

- 新增 `docs/release_operations_record.json`，把 crash/ANR monitoring owner、
  Android Vitals 信号源、首 24 小时 watcher、分阶段 rollout 阈值、RC
  crash/ANR smoke 结果和 rollback 计划收敛为机器可读记录。
- 新增 `scripts/verify_release_operations_record.sh`，校验 operations record
  已 approved，包含 Android Vitals、有效 crash-free/ANR 阈值、无 launch/install
  crash、无 crash loop、无 fatal native LiteRT-LM failure、无可复现 ANR、完整
  rollback criteria、Play versionCode 策略、模型 manifest 回滚路径和数据兼容说明。
- `scripts/verify_release_gate.sh` 新增 `VERIFY_RELEASE_OPERATIONS=1`；
  `PUBLIC_RELEASE=1` profile 会自动开启 release operations gate。

验证命令：

```bash
scripts/test_validation_scripts.sh
scripts/verify_local.sh
```

结果：

- 通过：脚本单测覆盖 pending release operations 失败。
- 通过：脚本单测覆盖 approved initial-release operations 成功。
- 通过：脚本单测覆盖 missing Android Vitals 和 future review date 失败。
- 通过：脚本单测覆盖 `PUBLIC_RELEASE=1` 自动启用
  `verifyReleaseOperations=1`。
- 通过：脚本单测覆盖 `VERIFY_RELEASE_OPERATIONS=1` 时 release gate 对 pending
  record fail-closed。

## 2026-06-06 Release validation gate

本轮覆盖项：

- 新增 `docs/release_validation_record.json`，把 emulator regression、physical
  device instrumentation、API matrix、manual acceptance、系统中介手工项、remote
  public evidence 样例、mixed-batch fail-closed、sanitized screenshots、flow
  matrix 和 performance sanity 证据收敛为机器可读记录。
- 新增 `scripts/verify_release_validation_record.sh`，校验 validation record
  已 approved，关联 emulator/device report 真实存在且通过，instrumentation test
  count 不低于当前 AndroidTest 源码数量，API 28/32/33/34/36 都有通过证据，截图
  文件存在且标记 sanitized。
- `scripts/verify_release_gate.sh` 新增 `VERIFY_RELEASE_VALIDATION=1`；
  `PUBLIC_RELEASE=1` profile 会自动开启 release validation gate。

验证命令：

```bash
scripts/test_validation_scripts.sh
scripts/verify_local.sh
```

结果：

- 通过：脚本单测覆盖 pending release validation 失败。
- 通过：脚本单测覆盖 approved validation record 成功。
- 通过：脚本单测覆盖缺失 physical report、API matrix 缺口和截图未 sanitized 失败。
- 通过：脚本单测覆盖 `PUBLIC_RELEASE=1` 自动启用
  `verifyReleaseValidation=1`。
- 通过：脚本单测覆盖 `VERIFY_RELEASE_VALIDATION=1` 时 release gate 对 pending
  record fail-closed。

## 2026-06-07 Crash/ANR smoke evidence collector

本轮覆盖项：

- 新增 `scripts/collect_crash_anr_smoke_evidence.sh`，从 device verification
  report、instrumentation 输出和 `adb logcat` 生成 `crashAnrSmoke.evidence`
  可引用的 properties 证据。
- 证据默认 fail-closed：缺 device report、缺 instrumentation 输出、缺 logcat、
  device/instrumentation 未通过、logcat 有 crash/ANR/fatal LiteRT-LM 信号时都失败。
- `scripts/test_validation_scripts.sh` 的 release operations 正例改为使用 collector
  生成 smoke evidence，并覆盖 ANR、instrumentation process crash 和单次 Java crash
  不误判 crash loop。
- `docs/release_checklist.md` 和 `docs/release_readiness.md` 增加 collector 使用入口。

验证命令：

```bash
bash -n scripts/collect_crash_anr_smoke_evidence.sh scripts/test_validation_scripts.sh scripts/verify_local.sh
scripts/test_validation_scripts.sh
bash scripts/verify_local.sh
git diff --check
```

结果：

- 通过：collector 成功生成 clean instrumentation/logcat 的 smoke evidence。
- 通过：collector 对 ANR logcat 信号 fail-closed。
- 通过：collector 对 instrumentation process crash 信号 fail-closed。
- 通过：collector 将单次 Java crash 计为一次 launch crash，不误判 crash loop。
- 通过：`verify_local` 全链路，包括脚本自测、unit test、lint、debug/release APK、
  release AAB 和 artifact scan。
- 通过：DeepSeek 测试配置的 key、endpoint 和 model 字面量没有落入仓库源码。

## 2026-06-07 Emulator Crash/ANR smoke evidence chain

本轮覆盖项：

- `scripts/install_and_test_device.sh` 新增成功/失败路径的 `adb logcat` 采集，
  device report 写入 `logcat_file`、`logcat_captured` 和 `logcat_tail_lines`。
- `scripts/verify_emulator.sh` 在 device verification 通过后自动调用
  `scripts/collect_crash_anr_smoke_evidence.sh`，并在 emulator report 写入
  `crash_anr_smoke_report_file`。
- collector 在给定 device report 时优先使用 report 里的 instrumentation/logcat
  路径，避免外部环境变量污染证据来源。
- `scripts/test_validation_scripts.sh` 新增 direct device logcat、emulator smoke
  report 和 report-path 优先级覆盖。

验证命令：

```bash
bash -n scripts/install_and_test_device.sh scripts/verify_emulator.sh scripts/collect_crash_anr_smoke_evidence.sh scripts/test_validation_scripts.sh
scripts/test_validation_scripts.sh
bash scripts/verify_local.sh
```

结果：

- 通过：direct device fake flow 生成非空 `logcat.txt` 并记录在
  `device-verification.properties`。
- 通过：emulator fake flow 自动生成 `crash-anr-smoke.properties` 且状态为 passed。
- 通过：collector 不再被外部 `INSTRUMENTATION_OUTPUT_FILE` 环境变量覆盖 report
  内的证据路径。
- 通过：`verify_local` 全链路，包括脚本自测、unit test、lint、debug/release APK、
  release AAB 和 artifact scan。

## 2026-06-07 Release validation consumes Crash/ANR smoke evidence

本轮覆盖项：

- `scripts/verify_release_validation_record.sh` 新增 Crash/ANR smoke report 校验：
  要求 nested emulator report 指向 `crash_anr_smoke_report_file`，且该 report
  为 `status=passed`、`target=crash-anr-smoke-evidence`、`logcatAnalyzed=true`。
- 校验 smoke report 中的 `noLaunchCrash`、`noInstallCrash`、`noCrashLoop`、
  `noFatalNativeLiteRtLmFailure`、`noReproducibleAnr` 都为 true，并要求 crash、
  ANR、LiteRT fatal 和 instrumentation failure counters 为 0。
- 校验 smoke report 绑定的 device report、instrumentation output、logcat path
  与 nested emulator/device evidence 一致，并验证 report 中记录的 SHA-256。
- `scripts/test_validation_scripts.sh` 的 release validation fixture 新增 top-level
  emulator 和 API matrix 的 smoke evidence；新增缺失 smoke report 的 fail-closed
  负例。

验证命令：

```bash
bash -n scripts/verify_release_validation_record.sh scripts/test_validation_scripts.sh
scripts/test_validation_scripts.sh
bash scripts/verify_local.sh
```

结果：

- 通过：approved release validation fixture 必须包含 nested Crash/ANR smoke
  evidence 才能通过。
- 通过：API matrix nested emulator report 缺少 `crash_anr_smoke_report_file` 时
  release validation verifier fail-closed。
- 通过：`verify_local` 全链路，包括脚本自测、unit test、lint、debug/release APK、
  release AAB 和 artifact scan。

## 2026-06-07 Model flow release evidence hardening

本轮覆盖项：

- `scripts/record_release_flow_evidence.sh` 写入模型 flow 的结构化验收字段。
  `localModelDownloadVerification` 必须声明本地模型下载验证、SHA-256 校验、
  存储空间预检、失败恢复、远程 fallback 说明和轻量替代说明。
- `customModelImportOrUrlRejection` 必须声明 `.litertlm` 导入、自定义下载
  HTTPS-only、非法 URL 拒绝、带凭据 URL 拒绝和未校验自定义模型标记。
- `scripts/verify_release_validation_record.sh` 对上述字段 fail-closed，避免
  仅凭 `releaseFlowPassed=true` 的薄证据通过正式 release validation。
- `scripts/collect_release_flow_matrix_evidence.sh` 的候选证据同步展示这些字段，
  并在判断已批准 release flow 时使用同一套模型证据契约。
- `docs/release_checklist.md` 同步记录验收人需要检查的模型 flow 字段。

验证命令：

```bash
bash -n scripts/collect_release_flow_matrix_evidence.sh scripts/record_release_flow_evidence.sh scripts/verify_release_validation_record.sh scripts/test_validation_scripts.sh
scripts/test_validation_scripts.sh
bash scripts/verify_local.sh
git diff --check
```

结果：

- 通过：release validation verifier 接受包含模型 flow 字段的 approved fixture。
- 通过：缺少本地模型下载结构化字段的 flow evidence 被拒绝。
- 通过：缺少自定义模型 HTTPS/非法 URL/未校验标记字段的 flow evidence 被拒绝。
- 通过：flow matrix candidate evidence 和正式 release flow recorder 都输出模型字段。
- 通过：`verify_local` 全链路，包括脚本自测、unit test、lint、debug/release APK、
  release AAB 和 artifact scan。
- 通过：使用用户提供的 DeepSeek endpoint、model 和 key 字面量做仓库扫描，
  均未命中；具体敏感 literal 未写入文档。

## 2026-06-07 Fresh-start main shell interaction smoke

本轮覆盖项：

- `scripts/verify_fresh_start_main_shell_emulator.sh` 在 clean install 后不只检查
  主 Shell 文案，还会从真实 UI dump 中定位 clickable `模型管理` 节点并执行
  `adb shell input tap`。
- 点击后脚本采集 `model-manager.xml`，要求模型管理 sheet 出现
  `选择本地离线或可选远程；远程发送和设备动作仍会先确认。`，否则以
  `model-manager-click-no-response` fail-closed。
- report 新增 `model_manager_window_dump` 和 `model_manager_click_opened`，
  用于区分“首屏文案存在”与“关键控件确实可交互”。
- `scripts/test_validation_scripts.sh` 新增 fake adb 正例和“点击后仍停留主页面”
  负例，覆盖用户反馈的“页面看起来卡住、点击没反应”风险。

验证命令：

```bash
bash -n scripts/verify_fresh_start_main_shell_emulator.sh scripts/test_validation_scripts.sh
scripts/test_validation_scripts.sh
AVD_NAME=pocketmind_api36_arm64 ARTIFACT_DIR=build/verification/fresh-start-interactive-current scripts/verify_fresh_start_main_shell_emulator.sh
bash scripts/verify_local.sh
git diff --check
```

结果：

- 通过：API 36 arm64 clean install fresh-start，
  `build/verification/fresh-start-interactive-current/fresh-start-main-shell.properties`
  记录 `first_run_setup_visible=false`、`main_shell_copy_visible=true`、
  `model_manager_click_opened=true`。
- 通过：`fresh-start.xml` 包含 `隐私优先的随身 AI 助手` 和
  `开始和 PocketMind 对话`，不包含 `离线基础问答可选下载`。
- 通过：点击后 `model-manager.xml` 包含 `模型管理`、`当前模型` 和
  `选择本地离线或可选远程；远程发送和设备动作仍会先确认。`
- 通过：`verify_local` 全链路，包括脚本自测、unit test、lint、debug/release APK、
  release AAB 和 artifact scan。

## 2026-06-07 Live remote emulator input targeting and evidence redaction

本轮覆盖项：

- 修复 `scripts/live_remote_emulator.sh` 的真实设备输入流程：不再只按屏幕比例盲点
  prompt/send 坐标，而是在输入前、发送前、发送后分别采集 UI dump，并从
  `EditText`、`发送`、`确认发送` 节点坐标执行 `adb shell input tap`。
- live remote 脚本现在能识别 `即将发送到远程模型` 披露 sheet，并在真实发送前
  点击 `确认发送`；report 新增 `input_dump`、`send_ready_dump`、
  `after_send_dump` 和 `remote_confirmation_handled`。
- 退出前清洗文本证据中的 API key、远程 base URL、host 和 model，避免归档的
  XML/logcat artifact 保留远程配置 literal；report 继续只写 `<redacted>` 和来源变量名。
- `scripts/test_validation_scripts.sh` 新增 fake UI dump 覆盖输入框、发送按钮、
  远程发送确认和 artifact redaction。

验证命令：

```bash
bash -n scripts/live_remote_emulator.sh scripts/test_validation_scripts.sh
scripts/test_validation_scripts.sh
POCKETMIND_LIVE_REMOTE_BASE_URL=<provided-remote-base-url> \
POCKETMIND_LIVE_REMOTE_MODEL=<provided-remote-model> \
POCKETMIND_LIVE_REMOTE_API_KEY=<provided-secret> \
POCKETMIND_LIVE_REMOTE_PROMPT='Return POCKETMIND_LIVE_OK' \
POCKETMIND_LIVE_REMOTE_EXPECTED_TEXT=POCKETMIND_LIVE_OK \
POCKETMIND_LIVE_REMOTE_WAIT_SECONDS=75 \
ARTIFACT_DIR=build/verification/live-remote-emulator-deepseek-current \
REPORT_FILE=build/verification/live-remote-emulator-deepseek-current/live-remote-emulator.properties \
scripts/live_remote_emulator.sh
bash scripts/verify_local.sh
git diff --check
```

结果：

- 通过：API 36 arm64 emulator 真实远程模型请求返回 `POCKETMIND_LIVE_OK`，
  `live-remote-emulator.properties` 记录 `status=passed`、`target=live-remote-emulator`、
  `device_target=emulator`、`remote_confirmation_handled=true`。
- 通过：`after_send_dump` 证明远程发送前出现确认 sheet，最终 `ui_dump` 证明收到
  远程响应，且没有 `远程模型请求失败`。
- 通过：live remote 文本证据目录扫描未命中用户提供的 API key、endpoint 或 model literal；
  相关 UI/logcat 文本已替换为 `<redacted>`。
- 通过：仓库源码扫描未命中用户提供的 API key、endpoint 或 model literal。
- 通过：`verify_local` 全链路，包括脚本自测、unit test、lint、debug/release APK、
  release AAB 和 artifact scan。

## 2026-06-07 Share and picker release evidence hardening

本轮覆盖项：

- `shareAndPickerInput` release flow evidence 现在必须显式记录 ACTION_SEND 文本暂存、
  远程模式文本分享保护、远程视觉图片附件暂存、远程视觉不支持时的保护信号、
  不做隐式 OCR、文档摘录边界和 picker 附件提示。
- `scripts/verify_release_validation_record.sh` 会拒绝只有粗粒度分享证据的 release
  record；`scripts/record_release_flow_evidence.sh` 和
  `scripts/collect_release_flow_matrix_evidence.sh` 会输出对应结构化字段。
- 现有 Android/JVM 用例已经覆盖 ACTION_SEND 文本、远程模式文本保护、远程视觉图片暂存、
  远程视觉不支持保护、图片不强制 OCR、文档摘录边界和 picker 提示，本轮把这些能力变成
  release gate 的必填证据。
- `docs/release_checklist.md` 新增 share/picker 输入验收项，避免正式包只凭“流程存在”
  而未证明多模态和隐私边界。

验证命令：

```bash
bash -n scripts/record_release_flow_evidence.sh scripts/collect_release_flow_matrix_evidence.sh scripts/verify_release_validation_record.sh scripts/test_validation_scripts.sh
scripts/test_validation_scripts.sh
bash scripts/verify_local.sh
git diff --check
```

结果：

- 通过：弱 `shareAndPickerInput` flow evidence 会被拒绝，并返回远程文本保护、远程视觉图片暂存、
  不做隐式 OCR 等缺失原因。
- 通过：候选 release flow 和 formal release flow 均生成新的 share/picker 必填字段。
- 通过：`verify_local` 全链路，包括脚本自测、unit test、lint、debug/release APK、
  release AAB 和 artifact scan。

## 2026-06-07 Privacy and data controls release gate

本轮覆盖项：

- `docs/release_validation_record.json` 新增 `privacyAndDataControls` flow，
  正式 release validation 必须单独证明 App 内隐私说明入口、长期记忆清空/遗忘、
  当前会话删除、远程配置清除和用户可删除/清除文案。
- `scripts/record_release_flow_evidence.sh`、
  `scripts/collect_release_flow_matrix_evidence.sh` 和
  `scripts/verify_release_validation_record.sh` 均新增上述机器可读字段；弱证据会被拒绝。
- 隐私入口 smoke 测试现在覆盖“用户控制”里的长期记忆、当前会话和远程配置清除口径；
  UI 文案合同也固定这些删除/清除控制。
- 修复 `scripts/collect_crash_anr_smoke_evidence.sh`：Android instrumentation
  成功输出里的最终 `INSTRUMENTATION_CODE: -1` 不再被误判为 failure signal。

验证命令：

```bash
scripts/test_validation_scripts.sh
./gradlew :app:testDebugUnitTest --tests com.bytedance.zgx.pocketmind.ui.PocketMindScreenDisplayTest
AVD_NAME=pocketmind_api36_arm64 EMULATOR_SELECT_TIMEOUT_SECONDS=120 BOOT_TIMEOUT_SECONDS=240 ARTIFACT_DIR=build/verification/privacy-data-controls-smoke-current3 INSTRUMENTATION_CLASS='com.bytedance.zgx.pocketmind.MainActivitySmokeTest#privacyButtonOpensAppPrivacyNotice' INSTRUMENTATION_TIMEOUT_SECONDS=180 scripts/verify_emulator.sh
scripts/collect_crash_anr_smoke_evidence.sh --device-report build/verification/privacy-data-controls-smoke-current2/device-verification.properties --instrumentation-output build/verification/privacy-data-controls-smoke-current2/instrumentation.txt --logcat build/verification/privacy-data-controls-smoke-current2/logcat.txt --report build/verification/privacy-data-controls-smoke-current2/crash-anr-smoke-rerun.properties --window 'privacy data controls targeted emulator rerun' --track local-emulator
```

结果：

- 通过：release validation verifier 会拒绝缺少隐私入口、记忆清除和远程配置清除字段的
  `privacyAndDataControls` 弱证据。
- 通过：API 36 arm64 targeted emulator smoke，
  `build/verification/privacy-data-controls-smoke-current3/emulator-verification.properties`
  记录 `status=passed`，instrumentation 输出 `OK (1 test)`，crash/ANR smoke 通过。
- 通过：先前失败的 `privacy-data-controls-smoke-current2` 在修复后重跑 crash/ANR 收集器通过，
  证明失败原因是 `INSTRUMENTATION_CODE: -1` 的 verifier 误判，不是 App 崩溃。

## 2026-06-07 CI release operations gate

本轮覆盖项：

- 扩展 `.github/workflows/android.yml`：现有 `verify` job 继续跑
  `scripts/verify_local.sh`，并生成/上传 `ci-local-verification` evidence；
  现有 `emulator-regression` job 继续作为 connected Android test evidence。
- 新增 workflow_dispatch 的 `release-artifact-archive` job：构建 release APK/AAB，
  扫描 artifact，并归档 APK、AAB、mapping 和机器可读 evidence。
- 新增 `protected-signing` job：在 `android-production-signing` 受保护环境中使用
  signing secrets 运行 `scripts/sign_release_artifacts.sh`；secrets 未配置时只生成
  skipped evidence，正式 release operations verifier 不接受 skipped signing。
- `docs/release_operations_record.json` 新增 `ci` 区块，要求 local verification、
  connected Android tests、release artifact archive 和 protected signing 均有 evidence path
  与 SHA-256。
- `scripts/verify_release_operations_record.sh` 新增 CI 门禁：connected test evidence 必须是
  `target=regression-emulator` 且有实际 instrumentation count；artifact archive 必须记录 AAB
  和 mapping SHA；protected signing 必须是 `target=release-signing`、`signingMode=production`
  且 artifact scan 通过。

验证命令：

```bash
bash -n scripts/verify_release_operations_record.sh scripts/test_validation_scripts.sh scripts/verify_release_gate.sh
ruby -e 'require "yaml"; YAML.load_file(".github/workflows/android.yml"); puts "workflow yaml ok"'
scripts/test_validation_scripts.sh
```

结果：

- 通过：workflow YAML 可解析。
- 通过：release operations verifier 接受包含 CI evidence 的 approved initial-release record。
- 通过：release operations verifier 拒绝把本地验证 evidence 冒充 connected Android test evidence。

## 2026-06-07 Release screenshot visual contract

本轮覆盖项：

- `scripts/capture_release_screenshots.sh` 在每张 release 截图后同步保存 UI dump，
  记录 `uiDump`、`uiDumpSha256`、`visualRegression=passed` 和 `requiredText`。
- 固定 4 个发布截图的可见文字合同：chat home、model manager、confirmation sheet、
  background tasks / audit。截图即使是 PNG，只要停在默认页、空白页或错误页面，UI dump
  缺少这些文字就会失败。
- `scripts/verify_release_validation_record.sh` 会验证截图报告的 visual contract、
  UI dump SHA-256 和必需文案；缺少 UI dump 或 visualRegression 不通过的报告会被拒绝。
- `scripts/test_validation_scripts.sh` 新增弱 visual report 负例，并增强 release screenshot
  capture 自测，确保截图报告实际写出 UI dump、requiredText 和 visualRegression 字段。

验证命令：

```bash
bash -n scripts/capture_release_screenshots.sh scripts/verify_release_validation_record.sh scripts/test_validation_scripts.sh
scripts/test_validation_scripts.sh
```

结果：

- 预期：approved release validation record 必须携带 release screenshot visual contract 才能通过。
- 预期：缺少 UI dump 或 `visualRegression=passed` 的截图报告会被 release validation verifier 拒绝。

## 2026-06-07 Adaptive UI release flow gate

本轮覆盖项：

- `adaptiveUi` 新增为正式 `flowMatrix` 必填项，当前
  `docs/release_validation_record.json` 中保持 `pending`，等待真实 release owner 证据绑定。
- `scripts/collect_release_flow_matrix_evidence.sh` 和
  `scripts/record_release_flow_evidence.sh` 都会生成/要求 `adaptiveUi` 证据。
- `scripts/verify_release_validation_record.sh` 对 `adaptiveUi` 正式 flow evidence
  强制校验 3 个字段：大字体可达、横屏可达、核心控件可访问标签/动作。
- `MainActivityAdaptiveUiTest` 新增横屏远程发送确认可达测试；横屏下会滚动确认 sheet，
  确认“确认发送”和“取消”按钮可见。

验证命令：

```bash
bash -n scripts/collect_release_flow_matrix_evidence.sh scripts/record_release_flow_evidence.sh scripts/verify_release_validation_record.sh scripts/test_validation_scripts.sh
scripts/test_validation_scripts.sh
AVD_NAME=pocketmind_api36_arm64 EMULATOR_SELECT_TIMEOUT_SECONDS=120 BOOT_TIMEOUT_SECONDS=360 ARTIFACT_DIR=build/verification/adaptive-ui-flow-gate-current3 INSTRUMENTATION_CLASS='com.bytedance.zgx.pocketmind.MainActivityAdaptiveUiTest' INSTRUMENTATION_TIMEOUT_SECONDS=240 scripts/verify_emulator.sh
bash scripts/verify_local.sh
```

结果：

- 通过：validation script self-tests，覆盖 `adaptiveUi` 正式证据字段、弱证据拒绝、
  recorder 全量/部分 flow 行为。
- 通过：API 36 targeted emulator，`build/verification/adaptive-ui-flow-gate-current3`
  记录 `MainActivityAdaptiveUiTest` 的 `OK (3 tests)`，覆盖大字体、横屏远程发送确认、
  核心控件无障碍标签。
- 通过：`verify_local` 全链路，包括脚本自测、unit test、lint、debug/release APK、
  release AAB 和 artifact scan。

## 2026-06-07 Voice input release flow gate

本轮覆盖项：

- `voiceInput` release flow evidence 新增正式必填字段：就近语音说明、一次性
  transcript draft 且不自动发送、麦克风权限失败/恢复、语音 capture 取消路径。
- `scripts/collect_release_flow_matrix_evidence.sh` 和
  `scripts/record_release_flow_evidence.sh` 都会写出这些 voice contract 字段。
- `scripts/verify_release_validation_record.sh` 会拒绝缺少上述字段的正式
  `voiceInput` release flow evidence；`scripts/test_validation_scripts.sh` 新增弱证据负例。
- `PocketMindViewModelTest` 新增权限失败/恢复契约测试：权限失败后清空 capture、
  不生成 draft、不发送 session message、不触发远程 runtime 或工具动作，之后可以重新进入收音状态。
- 系统麦克风权限弹窗的文案和按钮交互不作为 Compose AndroidTest 门禁；release gate
  验证 PocketMind 自身的失败/恢复行为，系统 permission controller 交互保留为手工/真机验收证据。

验证命令：

```bash
bash -n scripts/collect_release_flow_matrix_evidence.sh scripts/record_release_flow_evidence.sh scripts/verify_release_validation_record.sh scripts/test_validation_scripts.sh
scripts/test_validation_scripts.sh
./gradlew :app:testDebugUnitTest --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.voicePermissionFailureClearsCaptureAndCanRecoverWithoutSending'
```

结果：

- 通过：validation script self-tests，覆盖 `voiceInput` 正式证据字段和弱证据拒绝。
- 通过：目标 JVM 测试，覆盖麦克风权限失败后的业务恢复和 no-auto-send 边界。

## 2026-06-07 Model download failure recovery gate

本轮覆盖项：

- 自定义模型下载源现在必须显式指向 `.litertlm` 文件；HTTPS 下的 `.bin`、`.gguf`、
  空路径或目录路径会被拒绝，不再被自动改名成 `.litertlm`。
- 自定义下载失败文案明确提示需要 HTTPS `.litertlm` 模型链接。
- `localModelDownloadVerification` release flow evidence 新增必填字段：下载目录不可用、
  SHA 失败清理、pending 下载任务丢失恢复。
- `customModelImportOrUrlRejection` release flow evidence 新增必填字段：
  非 `.litertlm` 自定义下载 URL 必须被拒绝。
- `PocketMindViewModelTest` 新增下载失败恢复契约：目录不可用不 enqueue、下载失败清
  pending 并删除目标、SHA 失败删除坏文件且不注册模型、启动恢复时下载任务丢失会清理 pending。
- `ModelRepositoryTest` 新增下载源和校验契约：非 `.litertlm` HTTPS URL 拒绝、
  expected size 不匹配拒绝、SHA 不匹配拒绝、无 SHA 的自定义文件仅在大小匹配时接受。

验证命令：

```bash
./gradlew :app:testDebugUnitTest --tests 'com.bytedance.zgx.pocketmind.data.ModelRepositoryTest' --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.startModelDownloadReportsUnavailableDirectoryWithoutEnqueueing' --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.startCustomModelDownloadRejectsInvalidUrlWithoutEnqueueing' --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.monitorDownloadFailureClearsPendingDeletesTargetAndShowsReason' --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.monitorDownloadShaFailureDeletesFileClearsPendingAndStopsDownloading' --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.restoreStartupStateClearsPendingDownloadWhenDownloadTaskMissing'
scripts/test_validation_scripts.sh
bash scripts/verify_local.sh
AVD_NAME=pocketmind_api36_arm64 EMULATOR_SELECT_TIMEOUT_SECONDS=120 BOOT_TIMEOUT_SECONDS=360 ARTIFACT_DIR=build/verification/model-download-gate-smoke-current INSTRUMENTATION_CLASS='com.bytedance.zgx.pocketmind.MainActivitySmokeTest' INSTRUMENTATION_TIMEOUT_SECONDS=240 scripts/verify_emulator.sh
```

结果：

- 通过：目标 JVM 测试覆盖模型下载 URL 边界、失败原因、坏文件清理和 pending 恢复。
- 通过：validation script self-tests，覆盖新增 release flow 字段和弱证据拒绝。
- 通过：`verify_local` 全链路，包括 unit test、lint、debug/release APK、release AAB 和 artifact scan。
- 通过：API 36 targeted emulator，`build/verification/model-download-gate-smoke-current`
  记录 `MainActivitySmokeTest` 的 `OK (6 tests)`，覆盖启动、模型管理、远程配置入口、
  隐私入口、会话管理和后台任务空态。

## 2026-06-07 Local model import file-flow gate

本轮覆盖项：

- `ModelRepository.importModel()` 的 Android `Uri/ContentResolver` 读取保持在 Android 适配层；
  文件名校验、空间预检、临时文件、移动、失败清理和导入结果被抽成 JVM 可测的
  `importModelFileToModelDir()`。
- 系统文件提供方缺少 `DISPLAY_NAME` 时不再 fallback 到推荐模型文件名；只使用 URI 末段作为
  最后文件名来源，否则走 `.litertlm` 校验失败，避免未知来源绕过后缀检查。
- 本地导入新增 JVM 契约：非 `.litertlm` 拒绝且不 copy、存储不足时不 copy、空文件拒绝并删除
  `.tmp`、copy 失败删除 `.tmp`、成功导入结果声明 `recommendedModelId=null` 且
  `verificationStatus=UnverifiedCustom`。
- `customModelImportOrUrlRejection` release flow evidence 新增必填字段：本地非 `.litertlm`
  导入拒绝、导入存储预检、空文件拒绝、copy/move 失败临时文件清理。
- `localModelDownloadVerification` release flow evidence 新增必填字段：
  insufficient-storage 下载失败覆盖。

验证命令：

```bash
./gradlew :app:testDebugUnitTest --tests 'com.bytedance.zgx.pocketmind.data.ModelRepositoryTest'
scripts/test_validation_scripts.sh
bash scripts/verify_local.sh
AVD_NAME=pocketmind_api36_arm64 EMULATOR_SELECT_TIMEOUT_SECONDS=120 BOOT_TIMEOUT_SECONDS=360 ARTIFACT_DIR=build/verification/model-import-gate-smoke-current INSTRUMENTATION_CLASS='com.bytedance.zgx.pocketmind.MainActivitySmokeTest' INSTRUMENTATION_TIMEOUT_SECONDS=240 scripts/verify_emulator.sh
```

结果：

- 通过：仓库层 JVM 测试覆盖本地模型导入文件流边界。
- 通过：validation script self-tests，覆盖新增 import/download release flow 字段和弱证据拒绝。
- 通过：`verify_local` 全链路，包括 unit test、lint、debug/release APK、release AAB 和 artifact scan。
- 通过：API 36 targeted emulator，`build/verification/model-import-gate-smoke-current`
  记录 `MainActivitySmokeTest` 的 `OK (6 tests)`，覆盖启动、模型管理、远程配置入口、
  隐私入口、会话管理和后台任务空态。

## 2026-06-07 Remote boundary and recovery gate

本轮覆盖项：

- 远程模式未配置时，发送尝试不再静默返回；会写入本地 `LocalOnly` 提示，说明需要先配置远程模型，
  并明确“还没有发送你的内容”，避免用户点击后无反馈。
- `PocketMindViewModelTest` 新增远程记忆边界合同：远程发送时 route 层收到
  `memoryEnabled=false`，设备上下文为 null，Run Data Receipt 标记 destination 为 `Remote`、
  `memoryContextIncluded=false`，并声明本地记忆/设备上下文被保护。
- 远程未配置发送尝试新增 JVM 合同：不调用 remote runtime、不进入 router、不持久化用户 prompt，
  UI 状态为“请配置远程模型”，`ModelHealth` 为 `LoadFailed`。
- `remoteHttpsConfiguration` release flow evidence 新增必填字段：远程网络失败恢复、
  远程未配置失败、远程不自动携带本地记忆。
- `shareAndPickerInput` release flow evidence 新增必填字段：远程模式非图片附件不自动携带。
- 正式 release-flow 记录脚本、候选证据收集脚本和 release record verifier 已同步这些字段；
  弱远程/弱分享证据会被验证脚本拒绝。

验证命令：

```bash
./gradlew :app:testDebugUnitTest --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteModeDoesNotEnableMemoryContextOrReceiptForRemoteSend' --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteModeUnconfiguredSendAttemptShowsLocalNoticeWithoutCallingRuntime' --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteNetworkFailureShowsReadableFailureAndFailsTrace'
bash -n scripts/collect_release_flow_matrix_evidence.sh scripts/record_release_flow_evidence.sh scripts/verify_release_validation_record.sh scripts/test_validation_scripts.sh
scripts/test_validation_scripts.sh
bash scripts/verify_local.sh
AVD_NAME=pocketmind_api36_arm64 EMULATOR_SELECT_TIMEOUT_SECONDS=120 BOOT_TIMEOUT_SECONDS=360 ARTIFACT_DIR=build/verification/remote-boundary-gate-smoke-current INSTRUMENTATION_CLASS='com.bytedance.zgx.pocketmind.MainActivitySmokeTest' INSTRUMENTATION_TIMEOUT_SECONDS=240 scripts/verify_emulator.sh
```

结果：

- 通过：目标 JVM 测试覆盖远程记忆/receipt 边界、未配置远程点击反馈和网络失败恢复。
- 通过：validation script self-tests，覆盖新增 release flow 字段和弱证据拒绝。
- 通过：`verify_local` 全链路，包括 unit test、lint、debug/release APK、release AAB 和 artifact scan。
- 通过：API 36 targeted emulator，`build/verification/remote-boundary-gate-smoke-current`
  记录 `MainActivitySmokeTest` 的 `OK (6 tests)`，覆盖启动、模型管理、远程配置入口、
  隐私入口、会话管理和后台任务空态。

## 2026-06-07 Remote vision share stream-count gate

本轮覆盖项：

- 新增 debug-only `CountingSharedContentProvider`，只进入 debug APK，不进入 release；
  androidTest 通过 `ContentResolver.call()` 重置/读取计数，避免依赖静态变量跨进程行为。
- `MainActivitySharedIntentTest` 新增远程视觉分享计数合同：
  支持视觉时，混合分享中的 PNG 图片流打开 1 次、文本附件流打开 0 次、图片 OCR 调用 0 次；
  不支持视觉时，图片流打开 0 次、文本流打开 0 次、图片 OCR 调用 0 次。
- 支持视觉的 direct reader 测试精确断言 tiny PNG 被转为
  `data:image/png;base64,...`，并确认 remote vision prompt 不含文件名、URI 或文本附件内容。
- `RemoteChatRuntimeTest` 新增 HTTP fixture：实际远程请求必须使用 OpenAI 兼容
  `image_url` content part，且 `stream=true`。
- `shareAndPickerInput` release flow evidence 新增 exact-value 字段：
  `remoteVisionHttpFixtureImagePartCount=1`、支持视觉图片流 `1`、OCR `0`、
  不支持视觉图片流 `0`、OCR `0`、混合分享受保护非图片数 `1`。
- release flow collector、formal recorder、validation verifier 和脚本自测已同步这些字段；
  collector 的 approved-record 校验也补齐，避免只跑 collector 时漏过弱证据。

验证命令：

```bash
./gradlew :app:testDebugUnitTest --tests 'com.bytedance.zgx.pocketmind.runtime.RemoteChatRuntimeTest.sendWithImageUsesOpenAiVisionContentPartInHttpFixture'
./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin
scripts/test_validation_scripts.sh
AVD_NAME=pocketmind_api36_arm64 EMULATOR_SELECT_TIMEOUT_SECONDS=120 BOOT_TIMEOUT_SECONDS=360 ARTIFACT_DIR=build/verification/remote-vision-share-gate-current INSTRUMENTATION_CLASS='com.bytedance.zgx.pocketmind.MainActivitySharedIntentTest' INSTRUMENTATION_TIMEOUT_SECONDS=300 scripts/verify_emulator.sh
bash scripts/verify_local.sh
AVD_NAME=pocketmind_api36_arm64 EMULATOR_SELECT_TIMEOUT_SECONDS=120 BOOT_TIMEOUT_SECONDS=360 ARTIFACT_DIR=build/verification/remote-vision-share-smoke-current INSTRUMENTATION_CLASS='com.bytedance.zgx.pocketmind.MainActivitySmokeTest' INSTRUMENTATION_TIMEOUT_SECONDS=240 scripts/verify_emulator.sh
```

结果：

- 通过：Runtime HTTP fixture JVM 测试覆盖远程视觉请求体。
- 通过：validation script self-tests，覆盖新增 exact-value evidence 字段和弱证据拒绝。
- 通过：API 36 targeted emulator，`build/verification/remote-vision-share-gate-current`
  记录 `MainActivitySharedIntentTest` 的 `OK (5 tests)`。
- 通过：`verify_local` 全链路，包括 unit test、lint、debug/release APK、release AAB 和 artifact scan。
- 通过：API 36 targeted emulator smoke，`build/verification/remote-vision-share-smoke-current`
  记录 `MainActivitySmokeTest` 的 `OK (6 tests)`。

## 2026-06-07 Remote share unconfigured and cancellation polish

本轮覆盖项：

- 远程模式但远程模型未配置时，分享图片/附件不再只显示泛化“已保护分享内容”；
  UI 会明确提示需要先配置远程模型，并声明本次未读取、OCR 或发送分享内容。
- 已暂存的远程图片草稿如果在发送前远程配置被清空，发送时会 fail-closed：
  不调用 remote runtime，不把图片 prompt 写入后续远程 history，并给出本地配置提示。
- 远程生成 `stopGeneration()` 新增 ViewModel 级回归：远程流挂起时会调用
  `remoteRuntime.stop()`、取消 active Agent run，并把 UI 恢复为非 busy/generating。
- 远程工具结果续写确认新增取消回归：用户取消后不会发起第二次远程调用，
  Agent run 进入失败路径，工具结果不会发送到远程模型。
- 远程发送披露文案按图片数量分支：0 张图片时不再说“图片字节会发往该远程地址”
  或“图片和响应”，避免隐私文案过度声明。
- `MainActivitySharedIntentTest` 新增未配置远程图片分享的模拟器合同：
  不出现附件草稿，不读取图片流，不暴露文件名或 `data:image`。

验证命令：

```bash
./gradlew :app:testDebugUnitTest --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest' --tests 'com.bytedance.zgx.pocketmind.MainActivitySharedInputModeTest' --tests 'com.bytedance.zgx.pocketmind.ui.PocketMindScreenDisplayTest'
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.bytedance.zgx.pocketmind.MainActivitySharedIntentTest#actionSendTextUsesProtectedSignalWhenActivityStartsInRemoteMode
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.bytedance.zgx.pocketmind.MainActivitySharedIntentTest
git diff --check
rg -n --hidden -g '!build/**' -g '!**/.git/**' 'sk-[A-Za-z0-9]{16,}' . || true
```

结果：

- 通过：目标 JVM 测试覆盖远程未配置分享提示、远程图片草稿 fail-closed、
  远程 stop、远程工具续写取消和披露文案分支。
- 通过：API 36 emulator targeted 单测，远程文本分享保护可见。
- 通过：API 36 emulator `MainActivitySharedIntentTest`，记录 `OK (6 tests)`，
  覆盖 ACTION_SEND 文本、远程文本保护、远程视觉图片、未配置远程图片配置提示、
  远程视觉不支持保护和 reader 流读取计数。
- 通过：diff whitespace 检查。
- 通过：工作区敏感配置扫描未命中用户提供的 API key、远程 endpoint 或模型名 literal。

## 2026-06-07 First-screen positioning and remote clear guard

本轮覆盖项：

- 顶栏从“标题和五个入口挤在同一行”改为“定位标题行 + 操作入口行”，小屏首屏可完整显示
  `PocketMind` 与“隐私优先的随身 AI 助手”。
- 空状态主卡标题从“开始和 PocketMind 对话”收敛为“隐私优先的随身 AI 助手”，副标题第一句直接说明：
  本地可用、远程多模态可选、设备动作必须确认执行。
- `MainActivitySmokeTest` 锁住首屏定位副标题和主卡主定位第一眼可见。
- 新增 `clearingRemoteConfigWhileInRemoteModeBlocksRemoteSend` JVM 回归：
  用户清除远程配置后继续发送，不调用 remote runtime，不进入 router，
  UI 提示“请配置远程模型”，`ModelHealth` 标记远程模型未配置。

验证命令：

```bash
./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin
./gradlew :app:testDebugUnitTest --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.clearingRemoteConfigWhileInRemoteModeBlocksRemoteSend' --tests 'com.bytedance.zgx.pocketmind.ui.PocketMindScreenDisplayTest'
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.bytedance.zgx.pocketmind.MainActivitySmokeTest#chatShellShowsModelManager
./gradlew :app:installDebug
adb shell am start -n com.bytedance.zgx.pocketmind/.MainActivity --ez com.bytedance.zgx.pocketmind.extra.SKIP_STARTUP_MODEL_RUNTIME_WORK true
adb exec-out screencap -p > /tmp/pocketmind-positioning-first-screen.png
```

结果：

- 通过：debug 和 androidTest Kotlin 编译。
- 通过：目标 JVM 测试覆盖首屏定位文案和清除远程配置后的发送拦截。
- 通过：API 36 emulator `MainActivitySmokeTest#chatShellShowsModelManager`。
- 通过：截图 `/tmp/pocketmind-positioning-first-screen.png` 显示首屏主卡标题为
  “隐私优先的随身 AI 助手”，顶栏完整显示 `PocketMind` 与定位副标题。

## 2026-06-07 Store positioning, privacy entry, and release screenshot contract

本轮覆盖项：

- 首页空状态主卡新增可见“隐私说明”入口；不再只依赖顶栏盾牌图标。
- `MainActivitySmokeTest` 新增首页隐私入口打开 App 内隐私说明页的模拟器合同，并把
  首屏可滚动区域的能力胶囊断言改为滚动可达。
- `docs/store_policy_record.json` 的 Store listing 草稿收敛为：
  privacy-first pocket AI、local usable、optional remote multimodal、confirmed device actions。
- `verify_store_policy_record.sh` 新增 Store listing 主定位检查；脚本自测新增缺失主定位的负例。
- `docs/privacy_notice.md` 开头从 internal testing 草稿口径收敛为 Android release candidate
  隐私边界说明；同步 store policy 和 privacy review 的 notice SHA / evidence SHA。
- release screenshot capture / validation 合同不再要求旧文案“开始和 PocketMind 对话”，
  改为要求 `PocketMind | 隐私优先的随身 AI 助手 | 为什么装它 | 模型管理`。

验证命令：

```bash
bash -n scripts/capture_release_screenshots.sh scripts/verify_release_validation_record.sh scripts/verify_store_policy_record.sh scripts/test_validation_scripts.sh
scripts/test_validation_scripts.sh
./gradlew :app:testDebugUnitTest --tests 'com.bytedance.zgx.pocketmind.ui.PocketMindScreenDisplayTest'
scripts/verify_store_policy_record.sh --report build/verification/store-policy-current/store-policy.properties || true
scripts/verify_privacy_review.sh --report build/verification/privacy-review-current/privacy-review.properties || true
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.bytedance.zgx.pocketmind.MainActivitySmokeTest#chatShellShowsModelManager,com.bytedance.zgx.pocketmind.MainActivitySmokeTest#homePrivacyEntryOpensAppPrivacyNotice
```

结果：

- 通过：validation script self-tests，覆盖 Store listing 主定位正例和缺失主定位负例。
- 通过：目标 JVM display contract。
- 通过：API 36 emulator 两条 Smoke 测试，覆盖首页主定位和首页隐私说明入口。
- 通过：store policy 当前记录只因 pending approval、占位联系邮箱/隐私政策 URL、
  reviewer/date 缺失失败；不再因 privacy SHA 或 listing 主定位失败。
- 通过：privacy review 当前记录只因 release/security/legal pending 失败；不再因
  notice SHA 或 evidence SHA 失败。

剩余风险：

- 麦克风系统权限拒绝/恢复、设备动作确认后 ActivityResult 权限拒绝仍需补模拟器测试。
- 真实联系邮箱、公开隐私政策 URL、release/security/legal/store reviewer 和日期仍未填写；
  因此公发门禁继续 fail-closed。

## 2026-06-07 Remote vision unsupported share Activity contract

本轮覆盖项：

- `MainActivitySharedIntentTest` 新增远程模型已配置但关闭图片输入时的 ACTION_SEND 图片分享测试。
- 测试从 Activity 分享入口进入，验证 UI 明确提示当前远程模型未启用图片输入能力，
  并声明未读取、OCR 或发送图片。
- 测试断言不会出现 `pending_shared_input_strip`，不会泄漏 `counting-image.png` 或 `data:image`。
- `CountingSharedContentProvider` 的 image/text open count 均保持 0，证明该路径不打开图片流、
  不触发 OCR，也不读取文本附件。
- Debug-only remote config extra 增加 `supportsVisionInput`，让截图/Activity 测试可以稳定覆盖
  远程视觉开启和关闭两类配置。

验证命令：

```bash
./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin
./gradlew :app:testDebugUnitTest --tests 'com.bytedance.zgx.pocketmind.MainActivitySharedInputModeTest'
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.bytedance.zgx.pocketmind.MainActivitySharedIntentTest#actionSendImageInRemoteModeWithVisionDisabledShowsUnsupportedNoticeWithoutReadingImage
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.bytedance.zgx.pocketmind.MainActivitySharedIntentTest
```

结果：

- 通过：debug 和 androidTest Kotlin 编译。
- 通过：JVM mode contract，覆盖远程已配置但不支持视觉时选择 `RemoteVisionUnsupportedSignal`。
- 通过：API 36 emulator targeted 新用例，覆盖 Activity 分享入口的不支持图片提示和 0 读取计数。
- 通过：API 36 emulator `MainActivitySharedIntentTest` 整组 7 条用例。

## 2026-06-07 Contact runtime permission denial ActivityResult

本轮覆盖项：

- `MainActivityRuntimePermissionUiTest` 新增联系人查询确认后真实系统权限拒绝链路：
  prompt -> 工具确认页 -> 系统权限弹窗 -> 拒绝 -> 确认页关闭 -> 状态显示
  “权限被拒，工具未执行” -> 无工具结果。
- 新增 UiAutomator androidTest 依赖，用于点击 Android 系统权限弹窗；Compose test 仍只负责 App 内 UI。
- `Composer` 对工具未执行/权限拒绝/特殊权限拒绝/屏幕截图同意取消等安全结果保持紧凑状态可见，
  并提供 `app_status_text` 测试锚点。
- 权限重置 helper 等待 shell 命令完成，并在拒绝用例结束后清理 `READ_CONTACTS` user-set/user-fixed
  标记，避免测试间污染。

验证命令：

```bash
./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.bytedance.zgx.pocketmind.MainActivityRuntimePermissionUiTest#contactLookupConfirmThenDenyPermissionShowsDeniedStateAndNoToolResult
./gradlew :app:testDebugUnitTest --tests 'com.bytedance.zgx.pocketmind.AgentRuntimePermissionPolicyTest' --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.deniedRuntimePermissionFailsPendingToolWithoutExecutingIt'
./gradlew :app:compileDebugAndroidTestKotlin && ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.bytedance.zgx.pocketmind.MainActivityRuntimePermissionUiTest
```

结果：

- 通过：debug 和 androidTest Kotlin 编译。
- 通过：API 36 emulator targeted 新用例，覆盖 ActivityResult 系统拒绝、状态可见、权限仍 denied、
  无 `工具执行结果`。
- 通过：JVM 权限策略和 ViewModel 拒绝逻辑定向测试。
- 通过：API 36 emulator `MainActivityRuntimePermissionUiTest` 整组 6 条用例。

剩余风险：

- 麦克风系统权限拒绝/恢复还未补真实系统弹窗模拟器测试。
- production signing、公开隐私政策 URL、Store/Legal/Security/Release 人工审批仍未完成；
  公发门禁继续 fail-closed。

## 2026-06-07 Voice microphone permission denial Activity contract

本轮覆盖项：

- 新增 `MainActivityVoicePermissionUiTest`，覆盖真实 Activity 语音入口：
  点击麦克风 -> App 内语音输入说明 -> 确认 -> Android 系统麦克风权限弹窗 -> 拒绝。
- 测试断言拒绝后 `app_status_text` 显示“未授权麦克风权限”，`voice_capture_bar` 不出现，
  `composer_input` 仍可用，`RECORD_AUDIO` 仍为 denied。
- 测试断言拒绝后语音入口仍可再次打开 App 内说明弹窗，证明恢复入口仍可达；
  不依赖模拟器是否有可用 `SpeechRecognizer`。
- Capability Matrix / `docs/capability_matrix.json` 将 `MainActivityVoicePermissionUiTest`
  纳入 `voice_transcript_input` required tests；文档锚点同步锁定真实系统拒绝证据。
- `AndroidManifestTest` 新增 `RECORD_AUDIO` 声明断言，避免语音能力和 Manifest 脱节。

验证命令：

```bash
./gradlew :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest --tests 'com.bytedance.zgx.pocketmind.docs.CapabilityMatrixDocumentationTest'
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.bytedance.zgx.pocketmind.MainActivityVoicePermissionUiTest
./gradlew :app:testDebugUnitTest --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.voicePermissionFailureClearsCaptureAndCanRecoverWithoutSending' --tests 'com.bytedance.zgx.pocketmind.docs.CapabilityMatrixDocumentationTest'
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.bytedance.zgx.pocketmind.PocketMindVoiceInputConsentUiTest
./gradlew :app:testDebugUnitTest --tests 'com.bytedance.zgx.pocketmind.AndroidManifestTest' --tests 'com.bytedance.zgx.pocketmind.docs.CapabilityMatrixDocumentationTest' --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.voicePermissionFailureClearsCaptureAndCanRecoverWithoutSending'
ANDROID_HOME="$HOME/Library/Android/sdk" ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" \
  ANDROID_SERIAL=emulator-5554 CLEAN_DEVICE=0 EMULATOR_SELECT_TIMEOUT_SECONDS=60 \
  BOOT_TIMEOUT_SECONDS=180 \
  INSTRUMENTATION_CLASS='com.bytedance.zgx.pocketmind.MainActivityVoicePermissionUiTest' \
  INSTRUMENTATION_TIMEOUT_SECONDS=240 LOGCAT_TAIL_LINES=5000 \
  ARTIFACT_DIR=build/verification/voice-permission-denial-current \
  scripts/verify_emulator.sh
```

结果：

- 通过：androidTest Kotlin 编译、Capability Matrix 文档契约、Manifest 契约和 ViewModel
  语音权限失败/恢复状态机定向测试。
- 通过：API 36 emulator `MainActivityVoicePermissionUiTest`，裸 Gradle connected test 1/1 通过。
- 通过：API 36 emulator `PocketMindVoiceInputConsentUiTest`，App 内语音显著同意弹窗 1/1 通过。
- 通过：`scripts/verify_emulator.sh` 生成
  `build/verification/voice-permission-denial-current/device-verification.properties`，
  记录 `status=passed`、`instrumentation_test_count=1`、
  `instrumentation_class=com.bytedance.zgx.pocketmind.MainActivityVoicePermissionUiTest`。
- 通过：`build/verification/voice-permission-denial-current/emulator-verification.properties`
  记录 `status=passed`、`api_level=36`、`abi=arm64-v8a`、`avd=pocketmind_api36_arm64`。

剩余风险：

- 本轮不把真实 `SpeechRecognizer.onResults()` 进入输入框写成自动化通过；该路径仍由
  ViewModel draft 测试和后续真机/手工语音验收共同覆盖。
- Android 设置页恢复授权、永久拒绝后的设置跳转和真实语音服务可用性仍属于手工验收或后续更专门的设备测试。
- production signing、公开隐私政策 URL、Store/Legal/Security/Release 人工审批仍未完成；
  公发门禁继续 fail-closed。

## 2026-06-07 External outcome fail-closed UI selections

本轮覆盖项：

- `PocketMindExternalOutcomeUiTest` 从单一“已完成”按钮覆盖扩展为三种外部结果选择：
  `Completed`、`NotCompleted`、`OpenedOnly`。
- 测试仍先断言外部结果确认 sheet 和三个按钮全部可见，再点击指定按钮，
  验证 UI 写回同一个 `PendingExternalOutcomeConfirmation` 和对应
  `AgentExternalOutcome`。
- 这条证据锁定产品原则：外部 Activity、分享面板、草稿页或 App 启动页打开后，
  UI 必须要求用户显式记录结果；“未完成”和“只是打开了”不能被误记成完成。

验证命令：

```bash
./gradlew :app:compileDebugAndroidTestKotlin
ANDROID_HOME="$HOME/Library/Android/sdk" ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" \
  ANDROID_SERIAL=emulator-5554 CLEAN_DEVICE=0 EMULATOR_SELECT_TIMEOUT_SECONDS=60 \
  BOOT_TIMEOUT_SECONDS=180 \
  INSTRUMENTATION_CLASS='com.bytedance.zgx.pocketmind.PocketMindExternalOutcomeUiTest' \
  INSTRUMENTATION_TIMEOUT_SECONDS=240 LOGCAT_TAIL_LINES=5000 \
  ARTIFACT_DIR=build/verification/external-outcome-ui-current \
  scripts/verify_emulator.sh
```

结果：

- 通过：Debug AndroidTest Kotlin 编译。
- 通过：API 36 emulator `PocketMindExternalOutcomeUiTest` 3/3：
  `externalOutcomeSheetReportsCompletedSelection`、
  `externalOutcomeSheetReportsNotCompletedSelection`、
  `externalOutcomeSheetReportsOpenedOnlySelection`。
- 通过：`scripts/verify_emulator.sh` 生成
  `build/verification/external-outcome-ui-current/device-verification.properties`，
  记录 `status=passed`、`instrumentation_test_count=3`、
  `instrumentation_class=com.bytedance.zgx.pocketmind.PocketMindExternalOutcomeUiTest`。
- 通过：敏感配置扫描未发现 DeepSeek endpoint、模型名或 API key 被写入仓库。

剩余风险：

- 本轮只覆盖外部结果确认 sheet 的 UI 写回；真实外部 App 内部操作结果仍只能由用户补录，
  App 不应自动推断。
- Godel 审计指出语音 `SpeechRecognizer.onResults()` 到 composer 输入框还缺 UI 自动化证据；
  该项已在后续 `Voice transcript draft composer UI contract` 补齐。
- production signing、公开隐私政策 URL、Store/Legal/Security/Release 人工审批仍未完成；
  公发门禁继续 fail-closed。

## 2026-06-07 Voice transcript draft composer UI contract

本轮覆盖项：

- `PocketMindVoiceInputConsentUiTest` 新增 `voiceTranscriptDraftFillsComposerOnceWithoutSending`。
- 测试先在 `composer_input` 输入已有文本，再注入
  `ChatUiState.voiceInputDraft = VoiceInputDraft(id = 42, text = "  语音转写结果  ")`。
- 测试断言输入框最终为 `已有内容\n语音转写结果`，证明 UI 层会清理转写文本并追加到
  composer，而不是直接发送。
- 测试断言 `onVoiceInputConsumed(42)` 只触发一次，并且 `onSendMessage` 未触发；
  强化语音转写“只生成草稿、不自动发送”的产品边界。
- `PocketMindVoiceInputConsentUiTest` 抽出共用 `PocketMindScreen` helper，
  保留原有语音显著同意弹窗覆盖。

验证命令：

```bash
./gradlew :app:compileDebugAndroidTestKotlin
./gradlew :app:testDebugUnitTest --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.voiceTranscriptDraftIsOneShotAndDoesNotSendMessage'
ANDROID_HOME="$HOME/Library/Android/sdk" ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" \
  ANDROID_SERIAL=emulator-5554 CLEAN_DEVICE=0 EMULATOR_SELECT_TIMEOUT_SECONDS=60 \
  BOOT_TIMEOUT_SECONDS=180 \
  INSTRUMENTATION_CLASS='com.bytedance.zgx.pocketmind.PocketMindVoiceInputConsentUiTest' \
  INSTRUMENTATION_TIMEOUT_SECONDS=240 LOGCAT_TAIL_LINES=5000 \
  ARTIFACT_DIR=build/verification/voice-transcript-draft-ui-current \
  scripts/verify_emulator.sh
```

结果：

- 通过：Debug AndroidTest Kotlin 编译。
- 通过：JVM `voiceTranscriptDraftIsOneShotAndDoesNotSendMessage`，覆盖转写清洗、
  draft 消费、不新增消息、不触发远程模型或工具。
- 通过：API 36 emulator `PocketMindVoiceInputConsentUiTest` 2/2：
  `voiceButtonRequiresAppConsentBeforeStartingVoiceInput`、
  `voiceTranscriptDraftFillsComposerOnceWithoutSending`。
- 通过：`scripts/verify_emulator.sh` 生成
  `build/verification/voice-transcript-draft-ui-current/device-verification.properties`，
  记录 `status=passed`、`instrumentation_test_count=2`、
  `instrumentation_class=com.bytedance.zgx.pocketmind.PocketMindVoiceInputConsentUiTest`。

剩余风险：

- 本轮证明 `voiceInputDraft` 进入 composer；真实系统 `SpeechRecognizer` 服务可用性、
  语言识别质量、永久拒绝后设置页恢复仍属于真机/手工验收或后续专项设备测试。
- production signing、公开隐私政策 URL、Store/Legal/Security/Release 人工审批仍未完成；
  公发门禁继续 fail-closed。

## 2026-06-07 First-run download failure recovery

本轮覆盖项：

- 首启向导下载入队后不再立即持久标记 setup dismissed；下载期间仍临时隐藏首启面板，
  但只有基础能力包成功准备后才写入 dismissed。
- `DownloadManager.STATUS_FAILED + ERROR_INSUFFICIENT_SPACE` 等下载失败后，
  如果这是首启向导触发且本地模型仍未就绪，UI 状态会恢复 `showFirstRunSetup=true`，
  让用户继续看到“为什么下载、空间不足、可先远程/导入”的恢复入口。
- 新增 `PocketMindViewModelTest.setupModelDownloadFailureAfterEnqueueRestoresFirstRunRecovery`，
  覆盖下载已入队、目标文件写入 partial、随后存储不足失败的交叉状态。
- 保留普通下载失败合同：pending download 清理、partial 文件删除、进度清零和
  “下载失败：存储空间不足”文案。

验证命令：

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.setupModelDownloadFailureAfterEnqueueRestoresFirstRunRecovery' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.startSetupModelDownloadKeepsFirstRunOpenWhenPreflightFails' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.monitorDownloadFailureClearsPendingDeletesTargetAndShowsReason'
./gradlew :app:compileDebugAndroidTestKotlin
ANDROID_HOME="$HOME/Library/Android/sdk" ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" \
  ANDROID_SERIAL=emulator-5554 CLEAN_DEVICE=0 EMULATOR_SELECT_TIMEOUT_SECONDS=60 \
  BOOT_TIMEOUT_SECONDS=180 \
  INSTRUMENTATION_CLASS='com.bytedance.zgx.pocketmind.MainActivityFirstRunSetupUiTest' \
  INSTRUMENTATION_TIMEOUT_SECONDS=240 LOGCAT_TAIL_LINES=5000 \
  ARTIFACT_DIR=build/verification/first-run-download-failure-recovery-current \
  scripts/verify_emulator.sh
```

结果：

- 通过：Debug Kotlin 编译。
- 通过：JVM 首启下载预检失败、入队后存储不足失败、普通下载失败三条定向回归。
- 通过：Debug AndroidTest Kotlin 编译。
- 通过：API 36 emulator `MainActivityFirstRunSetupUiTest` 1/1，
  确认首启向导、默认基础对话模型、下载按钮和跳过恢复路径仍可用。
- 通过：`scripts/verify_emulator.sh` 生成
  `build/verification/first-run-download-failure-recovery-current/device-verification.properties`，
  记录 `status=passed`、`instrumentation_test_count=1`、
  `instrumentation_class=com.bytedance.zgx.pocketmind.MainActivityFirstRunSetupUiTest`。

剩余风险：

- 本轮通过 JVM 模拟 DownloadManager 存储不足，不等于真实低存储设备矩阵已经完成。
- 真机/不同 Android 版本的真实下载、校验、加载耗时和空间压力仍需要发布前矩阵验收。
- production signing、公开隐私政策 URL、Store/Legal/Security/Release 人工审批仍未完成；
  公发门禁继续 fail-closed。

## 2026-06-07 API 36 regression evidence refresh after core flow fixes

本轮覆盖项：

- 当前 `app/src/androidTest` 源测试数已增长到 51；旧
  `product-contract-regression-current` report 仍绑定 35 个 AndroidTest，
  release flow matrix candidate 因 stale source count 正确拒绝复用旧证据。
- 重新执行 API 36 arm64 clean-device 完整模拟器回归，刷新
  `build/verification/product-contract-regression-current/regression-emulator.properties`。
- `docs/release_validation_record.json` 的 `emulatorRegression.reportSha256` 和
  API 36 `apiMatrix.evidenceSha256` 同步到当前 51-test 回归 report。
- 重新生成 release flow matrix candidate；它不再因为 stale source count 或 SHA mismatch
  失败，而是继续因正式 flow evidence 未批准而 fail closed。

验证命令：

```bash
ANDROID_HOME="$HOME/Library/Android/sdk" ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" \
  ANDROID_SERIAL=emulator-5554 AVD_NAME=pocketmind_api36_arm64 CLEAN_DEVICE=1 \
  EMULATOR_SELECT_TIMEOUT_SECONDS=120 BOOT_TIMEOUT_SECONDS=360 \
  ARTIFACT_DIR=build/verification/product-contract-regression-current \
  REGRESSION_REPORT_FILE=build/verification/product-contract-regression-current/regression-emulator.properties \
  scripts/regression_emulator.sh

ARTIFACT_DIR=build/verification/release-flow-matrix-after-core-fixes-current \
  REPORT_FILE=build/verification/release-flow-matrix-after-core-fixes-current/release-flow-matrix-candidate-evidence.properties \
  scripts/collect_release_flow_matrix_evidence.sh

scripts/verify_release_validation_record.sh \
  --report build/verification/release-validation-after-core-fixes-current.properties
```

结果：

- 通过：API 36 arm64 `pocketmind_api36_arm64` clean-device 完整模拟器回归，
  `actual_android_test_count=51`、`source_android_test_count=51`、
  `expected_android_test_count=51`。
- 通过：当前 regression report SHA-256 为
  `bcc2fb1ceee0d37d3a2204ea533256b4d24891b59c3f69fc051b4a36a6f5bfe0`，
  已同步到 release validation record 的 emulator regression 和 API 36 matrix entry。
- 预期失败：`collect_release_flow_matrix_evidence.sh` 生成 candidate evidence，
  `sourceAndroidTestCount=51`，失败目标为 `flow-matrix`，原因是正式 flow evidence
  仍未 approved。
- 预期失败：`verify_release_validation_record.sh` 不再报告 API 36 SHA/source-count
  mismatch；当前失败项集中在 non-emulator physical device、API 28/32/33/34、
  manual acceptance、正式 flow evidence、截图视觉回归、performance sanity 和 reviewer/date。

剩余风险：

- 本轮只刷新 API 36 当前源码面的完整模拟器证据，不替代 API 28/32/33/34 矩阵、
  真机、性能基线、视觉回归截图或人工验收。
- production signing、公开隐私政策 URL、Store/Legal/Security/Release 人工审批仍未完成；
  公发门禁继续 fail-closed。

## 2026-06-07 release gate review hardening

本轮覆盖项：

- 隐私扫描和 Android 制品扫描不再向 stderr/report 暴露 raw secret；artifact 扫描也不再把
  sensitive string grep 结果落到可预测 `/tmp` 文件。
- Store policy 和 Privacy review record 不再只信任 evidence 路径/SHA，同时校验
  approved status、approval target、notice path/SHA、scope 和 required decision。
- Release operations record 绑定当前 commit、release artifact、mapping 和 signing cert；
  local/archive CI 子 evidence 必须声明并匹配 workflow/runId/commit/job。
- Release validation record 在传入当前 artifact SHA 时，同时校验 emulator、physical device、
  API matrix、manual acceptance、release flow、screenshots 和 performance evidence。
- manual/flow/screenshot/emulator evidence 生成脚本新增 `RELEASE_ARTIFACT_SHA256` 输出支持。

验证命令：

```bash
bash -n scripts/privacy_scan.sh scripts/scan_android_artifacts.sh \
  scripts/verify_store_policy_record.sh scripts/verify_privacy_review.sh \
  scripts/verify_release_operations_record.sh scripts/verify_release_validation_record.sh \
  scripts/verify_release_gate.sh scripts/test_validation_scripts.sh

bash scripts/test_validation_scripts.sh

git diff --check
```

结果：

- 通过：validation script tests 全量通过。
- 通过：新增 negative tests 覆盖 pending/candidate review evidence、raw secret redaction、
  stale release artifact、stale mapping/signing cert 和 stale CI child evidence。
- 通过：diff whitespace 检查。

剩余风险：

- 本轮强化的是门禁和证据契约，不替代真实 API matrix、真机、性能基线、截图视觉回归和人工审批。
- 已有 pending release record 会继续 fail-closed，直到重新生成并批准绑定当前 artifact 的证据。

## 2026-06-22 local memory deletion tombstones

本轮覆盖项：

- 长期记忆删除新增 `memory_deletion_events` side table 和 `15 -> 16` Room migration；
  删除审计只记录 record id、类型、来源、敏感度、删除操作、时间和 `recordTextHash`，不保存 raw memory text。
- `MemoryRepository` 在用户/UI 删除、显式 forget、clear、冲突替换时写删除事件；
  `SuppressedTaskState` 和 conversation 记录不进入删除审计。
- `MemoryRepository` 删除持久记忆时通过 `MemoryDeletionEventStore` atomic hook 写审计；
  Room 实现使用 `MemoryDeletionTransactionDao` 将 `memory_records` 删除/清空与
  `memory_deletion_events` 写入放进同一事务。
- 后台任务状态 terminal/missing 自动清理改走 `forgetAutoManagedTaskState`，不伪装成用户删除墓碑。
- AppContainer 已把 Room deletion event DAO 注入到本地 memory repository。

验证命令：

```bash
ANDROID_HOME=$HOME/android-sdk ANDROID_SDK_ROOT=$HOME/android-sdk \
  ./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.memory.MemoryRepositoryTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.refreshBackgroundTasksDropsTerminalTaskStateMemory'

ANDROID_HOME=$HOME/android-sdk ANDROID_SDK_ROOT=$HOME/android-sdk \
  ./gradlew :app:compileDebugAndroidTestKotlin

ANDROID_HOME=$HOME/android-sdk ANDROID_SDK_ROOT=$HOME/android-sdk \
  scripts/verify_local.sh
```

结果：

- 通过：`MemoryRepositoryTest` 删除审计新增用例验证 Preference/UserFact/TaskState/clear
  均生成 hash-only tombstone，且事件字符串不包含原始偏好/事实文本。
- 通过：`refreshBackgroundTasksDropsTerminalTaskStateMemory` 验证自动任务状态清理不会写用户删除事件。
- 通过：`compileDebugAndroidTestKotlin`，包含 `migration15To16AddsMemoryDeletionEventsTableAndRoomCanOpen`
  的 instrumentation 源码编译通过。
- 通过：新增 atomic hook 单测验证持久记忆单条删除和 clear 均走 delete/append 原子入口。
- 通过：`scripts/verify_local.sh`，包含 validation script tests、JVM tests、debug/release build、
  AndroidTest assemble、lint 和 artifact scan。

剩余风险：

- 本轮按要求未跑真机/模拟器 instrumentation；`MIGRATION_15_16` 的实际 Room migration
  运行仍需要后续在设备或 emulator 上执行。
- `recordTextHash` 是 hash-only tombstone，不保存 raw text；但未加盐 SHA-256 不是匿名化，
  低熵文本理论上仍可能被字典猜测。

## 2026-06-22 remote image OCR preview fail-closed

本轮覆盖项：

- `SharedInput` 对已携带模型图片 payload 的附件 fail-closed：无论是远程图片
  `imageAttachment` 还是本地图片 `localImageAttachment`，都不再采信同一附件上的
  `textPreview` / OCR 摘录。
- 新增单测构造 `imageAttachment + ImageOcr textPreview` 的异常组合，验证 prompt 不包含
  OCR 原文，receipt summary 只记录 `ImageAttachment`，不记录 `LocalOnly` / `OcrText`
  证据。

验证命令：

```bash
ANDROID_HOME=/data00/home/zouguoxue/android-sdk \
  ./gradlew testDebugUnitTest \
  --tests com.bytedance.zgx.pocketmind.multimodal.SharedInputTest.remoteImageAttachmentIgnoresStaleOcrPreviewInPromptAndReceiptSummary

ANDROID_HOME=/data00/home/zouguoxue/android-sdk \
  ./gradlew testDebugUnitTest \
  --tests com.bytedance.zgx.pocketmind.multimodal.SharedInputTest \
  --rerun-tasks
```

结果：

- 通过：新增契约测试先在修复前失败，失败点为 prompt 仍包含私有 OCR 文本。
- 通过：修复后新增契约测试通过。
- 通过：`SharedInputTest` 全类通过，覆盖分享文本、附件预览、PDF/Office/OCR、受保护远程分享和
  RemoteVision prompt。

剩余风险：

- 本轮只跑本地 JVM 单测；按要求未跑真机/模拟器，也未生成或伪造 release evidence。
- 该修复防御异常组合和上游回归；真实 Android `Intent` provider 的 MIME/stream 行为仍需设备侧验证。

## 2026-06-22 Model profile context-window fail-closed

本轮覆盖项：

- `ModelProfile` 构造契约新增 fail-closed 校验：只有 Chat capability 可以声明
  `tokenBudget` / `contextWindowTokens`；memory embedding 和 mobile action profile
  不允许伪装成长上下文生成模型。
- `ModelCatalogTest` 新增两个 JVM 契约测试，覆盖 embedding/action profile 声明
  context window 时必须构造失败。

验证命令：

```bash
ANDROID_HOME=/data00/home/zouguoxue/android-sdk \
ANDROID_SDK_ROOT=/data00/home/zouguoxue/android-sdk \
  ./gradlew :app:testDebugUnitTest \
    --tests com.bytedance.zgx.pocketmind.ModelCatalogTest
```

结果：

- 通过：`ModelCatalogTest` 目标测试 `BUILD SUCCESSFUL`，覆盖推荐模型 profile、
  远程/本地 backend、vision、embedding、action 和 context-window 契约。
- 未执行真机/模拟器：本轮是纯 JVM model capability contract。

## 2026-06-22 Multi-agent real-app and AI eval gate hardening

本轮覆盖项：

- 多 Agent 并行审计 release evidence、AI behavior eval 和真实 App resolver；
  本轮只落地本地可机器验证的门禁补强，不伪造真机、模拟器、perf 或人工审批证据。
- `scripts/run_real_app_search_eval.sh` 的 case artifact 增加步骤级 evidence index：
  pass/fail 路径都会记录 tap、type_text、submit_search、verify 的 result 文件、SHA-256、
  target-resolution 字段、step evidence 文件和 ranked candidates 文件。
- `scripts/verify_ai_behavior_eval.sh` strict actual trace 模式收紧：
  `--require-actual-trace` 下 actual row 必须声明稳定且已知的 `caseId`，重复/未知 case
  fail-closed；`expectedConfirmation=fail_closed` 的 actual trace 必须带命中 allowlist 的
  `failureMode`。
- `PUBLIC_RELEASE=1` 强制启用 `VERIFY_AI_BEHAVIOR_EVAL=1`，即使环境变量显式传 0 也不能跳过
  AI behavior release gate。
- `AiBehaviorEvalFixturesTest` 同步 shell verifier 的安全覆盖要求：真实 App
  `page_not_changed`、runtime `permissiondenied` 和 public evidence batch boundary
  均进入 JVM fixture gate。

验证命令：

```bash
scripts/test_validation_scripts.sh

ANDROID_HOME=/data00/home/zouguoxue/android-sdk \
ANDROID_SDK_ROOT=/data00/home/zouguoxue/android-sdk \
  ./gradlew :app:testDebugUnitTest \
    --tests 'com.bytedance.zgx.pocketmind.eval.AiBehaviorEvalFixturesTest' \
    --tests 'com.bytedance.zgx.pocketmind.eval.AiBehaviorActualTraceGeneratorTest' \
    --tests 'com.bytedance.zgx.pocketmind.eval.AiBehaviorPlanningTraceProjectorTest'

ANDROID_HOME=/data00/home/zouguoxue/android-sdk \
ANDROID_SDK_ROOT=/data00/home/zouguoxue/android-sdk \
  ./gradlew :app:testDebugUnitTest \
    --tests 'com.bytedance.zgx.pocketmind.device.UiTargetResolverTest' \
    --tests 'com.bytedance.zgx.pocketmind.device.UiAutomatorDumpReplayTest'

ANDROID_HOME=/data00/home/zouguoxue/android-sdk \
ANDROID_SDK_ROOT=/data00/home/zouguoxue/android-sdk \
  scripts/verify_local.sh
```

结果：

- 通过：`scripts/test_validation_scripts.sh`，覆盖 strict actual trace 的 missing/unknown
  caseId、missing fail-closed failureMode、public-release 强制 AI eval、real-app pass/fail
  step evidence 和 ranked candidates hash。
- 通过：AI behavior targeted JVM tests。
- 通过：UI resolver / UIAutomator replay targeted JVM tests。
- 通过：`scripts/verify_local.sh`，包含 validation script tests、JVM tests、debug/release
  build、AndroidTest assemble、lint 和 artifact scan。

剩余风险：

- 按本轮要求未跑真机/模拟器 instrumentation；真实 App 搜索闭环、arm64 API matrix、
  physical validation、截图和性能基线仍需后续设备证据。
- Release/privacy/store/model license/manual acceptance 仍保持 pending/fail-closed，
  需要真实 owner 审批和可复现证据后才能进入 public release。

## 2026-06-22 Browser replay, diagnostics, and review evidence gates

本轮覆盖项：

- 多 Agent 只读审计后落地本地可验证项：浏览器真实 App replay、真实 App 失败
  diagnostics 覆盖、privacy/store review evidence 绑定规则。
- Chrome、Android Browser、UC 新增搜索输入态和结果态 UIAutomator dump fixture；
  `UiAutomatorDumpReplayTest` 覆盖地址栏 editable、提交按钮和 query-visible-after-change
  结果验证。
- `scripts/test_validation_scripts.sh` 对 JD submit、Chrome verify、PDD required hint
  失败报告统一断言截图、UIAutomator dump、focused window、window dump、logcat 和 SHA-256。
- `scripts/verify_privacy_review.sh` 现在拒绝未知 review role 和重复 review role；
  `scripts/verify_store_policy_record.sh` 在 review evidence 写入 reviewer 时要求与记录 reviewer
  一致。

Roadmap 状态：

- Phase 1 本地稳定性底座继续保持通过：Wi-Fi/skill routing、JVM、validation scripts、
  build/lint/artifact scan 仍由 `scripts/verify_local.sh` 覆盖。
- Phase 2 正在推进：真实 App 搜索已覆盖淘宝、拼多多、高德、京东、Chrome、Android
  Browser、Quark、UC 的 replay fixture / resolver evidence；但真实手机上的 App 闭环仍未作为
  passed evidence 绑定。
- Phase 3/4 已有 gate 雏形：AI behavior、privacy、store、release validation、model capability
  等都有机器可验证失败模式；但正式 release 仍等待真机、arm64 matrix、perf、截图、人工审批和生产签名。

验证命令：

```bash
bash -n scripts/verify_privacy_review.sh scripts/verify_store_policy_record.sh scripts/test_validation_scripts.sh

scripts/test_validation_scripts.sh

ANDROID_HOME=/data00/home/zouguoxue/android-sdk \
ANDROID_SDK_ROOT=/data00/home/zouguoxue/android-sdk \
  ./gradlew :app:testDebugUnitTest \
    --tests 'com.bytedance.zgx.pocketmind.device.UiAutomatorDumpReplayTest' \
    --tests 'com.bytedance.zgx.pocketmind.device.UiTargetResolverTest'

ANDROID_HOME=/data00/home/zouguoxue/android-sdk \
ANDROID_SDK_ROOT=/data00/home/zouguoxue/android-sdk \
  scripts/verify_local.sh
```

结果：

- 通过：validation script self-tests，覆盖 real-app failure diagnostics、privacy review
  unknown/duplicate role、store review evidence reviewer mismatch。
- 通过：UI resolver / UIAutomator replay targeted JVM tests。
- 通过：`scripts/verify_local.sh`，包含 validation script tests、JVM tests、debug/release
  build、AndroidTest assemble、lint 和 artifact scan。

剩余风险：

- 本轮仍按要求跳过真机/模拟器 instrumentation；arm64 真机、arm64 emulator API matrix、
  真实 App 搜索闭环、截图和 perf baseline 不能标记为 passed。
- Release/privacy/store/model license/manual acceptance/production signing 仍保持
  pending/fail-closed，需要真实 owner 证据和生产环境材料。

## 2026-06-22 Release evidence, strict trace, and local vision hardening

本轮覆盖项：

- 多 Agent 并行审计 Phase 3/4/5 缺口；本轮吸收本地可验证、无需真机/模拟器的三类改进。
- `scripts/collect_crash_anr_smoke_evidence.sh` 产出
  `CrashAnrSmokeEvidence/v1` 审计头，包含 owner、UTC `recordedAt`、可复现 command 和
  `reproduciblePath`。
- `scripts/verify_release_operations_record.sh` 要求 monitoring、crash/ANR smoke、rollback
  evidence 均携带对应 schema 审计头；弱 evidence 即使 SHA 正确也会 fail-closed。
- `scripts/install_and_test_device.sh` 与 `scripts/verify_release_validation_record.sh` 拒绝
  同时包含 `FAILURES!!!` 和 `OK (...)` 的矛盾 instrumentation 输出，避免失败标记被最终 OK
  覆盖。
- `scripts/verify_ai_behavior_eval.sh --require-actual-trace` 要求 actual trace 的 `caseId`、
  `category`、`input` 与 fixture 同时匹配，避免用正确 caseId 包装错误场景。
- `ChatUiStateModelVerificationTest` 新增本地视觉正例：active verified recommended chat model
  必须暴露 text+vision capability，并让 UI state 报告支持本地图片输入。

验证命令：

```bash
bash -n scripts/verify_ai_behavior_eval.sh scripts/install_and_test_device.sh \
  scripts/verify_release_validation_record.sh scripts/collect_crash_anr_smoke_evidence.sh \
  scripts/verify_release_operations_record.sh scripts/test_validation_scripts.sh

scripts/test_validation_scripts.sh

ANDROID_HOME=/data00/home/zouguoxue/android-sdk \
ANDROID_SDK_ROOT=/data00/home/zouguoxue/android-sdk \
  ./gradlew :app:testDebugUnitTest \
    --tests 'com.bytedance.zgx.pocketmind.ChatUiStateModelVerificationTest' \
    --tests 'com.bytedance.zgx.pocketmind.eval.AiBehaviorActualTraceGeneratorTest' \
    --tests 'com.bytedance.zgx.pocketmind.eval.AiBehaviorPlanningTraceProjectorTest'

ANDROID_HOME=/data00/home/zouguoxue/android-sdk \
ANDROID_SDK_ROOT=/data00/home/zouguoxue/android-sdk \
  scripts/verify_local.sh
```

结果：

- 通过：validation script self-tests，覆盖 operations evidence audit headers、弱 monitoring /
  rollback / crash smoke evidence 拒绝、矛盾 instrumentation 输出拒绝、strict actual trace
  category/input drift 拒绝。
- 通过：Chat UI state / AI behavior targeted JVM tests。
- 通过：`scripts/verify_local.sh`，包含 validation script tests、JVM tests、debug/release
  build、AndroidTest assemble、lint 和 artifact scan。

剩余风险：

- 本轮仍未跑真机或模拟器 instrumentation；physical validation、arm64 emulator API matrix、
  真实 App 真机闭环、截图和 perf baseline 仍未完成。
- 子 Agent 发现的 `permissiondenied` actual trace 和 AI collector 全量 runtime 来源收紧已在
  下一节补齐；recent image/screenshot OCR metadata 策略、release memory boundary 机器字段、
  store/privacy 远程多模态边界文案仍可继续细化。

## 2026-06-22 CI readiness, runtime provenance, and capability invariant hardening

本轮覆盖项：

- `ToolResult.error.code == PermissionDenied` 现在投影为 Agent eval `permissiondenied`
  failure mode。`runtime_contacts_permission_denied` fixture 通过真实
  `AgentLoopRuntime.failPendingToolRequest(...)` 记录 `query_contacts` 权限拒绝，actual trace
  断言为 `fail_closed`、`sensitive`、`LocalOnly`、`remoteEligible=false`。
- `scripts/collect_ai_behavior_actual_trace.sh` 不再接受混合来源 actual trace；collector
  要求 `actualTraceSourceBreakdown == agent_loop_runtime:<caseCount>`，并增加
  `device_debug_eval` 混入负例。
- `scripts/regression_emulator.sh` 和 `scripts/regression_emulator_api_matrix.sh` 输出
  `workflow`、`job`、`runId`、`commitSha`。API matrix report 同时绑定 readiness report
  SHA-256。
- `scripts/verify_release_operations_record.sh` 对 connected Android tests 和 API matrix
  evidence 执行 CI 身份校验；API matrix readiness 还校验 `status`、`target`、
  required/installed/available API 列表、missing 列表、`tag` 和 `abi`。
- 模型能力不变量补强：memory embedding 与 mobile action profile 必须保持
  `LocalLiteRt`、非 remote eligible；远程 OpenAI-compatible 模板不能隐式获得 memory/action
  capability；remote UI state 不会创建本地 capability profile，且仍要求 remote send 确认。

验证命令：

```bash
bash -n scripts/collect_ai_behavior_actual_trace.sh scripts/regression_emulator.sh \
  scripts/regression_emulator_api_matrix.sh scripts/verify_release_operations_record.sh \
  scripts/test_validation_scripts.sh

scripts/test_validation_scripts.sh

ANDROID_HOME=/data00/home/zouguoxue/android-sdk \
ANDROID_SDK_ROOT=/data00/home/zouguoxue/android-sdk \
  ./gradlew :app:testDebugUnitTest \
    --tests 'com.bytedance.zgx.pocketmind.docs.ModelCapabilityProfilesDocumentationTest' \
    --tests 'com.bytedance.zgx.pocketmind.ChatUiStateModelVerificationTest' \
    --tests 'com.bytedance.zgx.pocketmind.eval.AiBehaviorActualTraceGeneratorTest'

ANDROID_HOME=/data00/home/zouguoxue/android-sdk \
ANDROID_SDK_ROOT=/data00/home/zouguoxue/android-sdk \
  scripts/verify_local.sh
```

结果：

- 通过：validation script self-tests，覆盖 mixed-source actual trace 拒绝、CI identity
  report 字段、API readiness SHA/status/list/tag/ABI 校验、stale connected/API matrix CI 证据
  拒绝。
- 通过：model capability / Chat UI state / AI behavior actual trace targeted JVM tests。
- 通过：`scripts/verify_local.sh`，包含 validation script tests、JVM tests、debug/release
  build、AndroidTest assemble、lint 和 artifact scan。

剩余风险：

- 仍按要求跳过真机和模拟器验证；arm64 真机完整 instrumentation、arm64 emulator API matrix、
  真实 App 真机搜索闭环、截图、perf baseline、release owner/manual/legal/signing 证据不能标记
  为 passed。

## 2026-06-23 OCR raw payload and remote memory release-flow hardening

本轮覆盖项：

- 多 Agent 并行只读审计 OCR/multimodal、memory validation、store/privacy verifier。已吸收本地
  可验证且不依赖真机/模拟器的两项改进。
- `capture_current_screenshot_ocr` 的输出 schema 和执行结果现在显式包含
  `rawPayloadIncluded=false`，使当前屏幕截图 OCR 的“不持久化原始截图/像素”从隐含策略变成
  schema 合同。
- `recentMediaOcr` release-flow evidence 增加机器字段：
  `recentScreenshotMaxCount=1`、`recentImageMaxCount=3`、
  `recentMediaOcrRawPayloadPersisted=false`、
  `recentMediaOcrPrivateMetadataRedacted=true`、
  `recentMediaOcrOcrTextTraceRedacted=true`。`verify_release_validation_record.sh` 和
  `collect_release_flow_matrix_evidence.sh` 都会校验这些字段。
- `remoteHttpsConfiguration` release-flow evidence 增加远程记忆/设备上下文边界 exact 字段：
  `remoteMemoryContextIncluded=false`、`remoteMemoryHitCount=0`、
  `remoteSemanticMemoryHitCount=0`、`remoteLexicalMemoryHitCount=0`、
  `remoteDeviceContextIncluded=false`、`remoteRawContentPersisted=false`，并要求声明
  `remoteProtectedMemoryDeclared=true` 和 `remoteProtectedDeviceContextDeclared=true`。

验证命令：

```bash
bash -n scripts/record_release_flow_evidence.sh \
  scripts/collect_release_flow_matrix_evidence.sh \
  scripts/verify_release_validation_record.sh scripts/test_validation_scripts.sh

ANDROID_HOME=/data00/home/zouguoxue/android-sdk \
ANDROID_SDK_ROOT=/data00/home/zouguoxue/android-sdk \
  ./gradlew :app:testDebugUnitTest \
    --tests 'com.bytedance.zgx.pocketmind.multimodal.CurrentScreenshotOcrContractTest' \
    --tests 'com.bytedance.zgx.pocketmind.tool.RoutingAndValidatingToolExecutorTest.currentScreenshotOcrUsesOneShotProviderResultAfterConsent' \
    --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest.registersCurrentScreenshotOcrToolSpec'

scripts/test_validation_scripts.sh

ANDROID_HOME=/data00/home/zouguoxue/android-sdk \
ANDROID_SDK_ROOT=/data00/home/zouguoxue/android-sdk \
  scripts/verify_local.sh
```

结果：

- 通过：shell 静态检查。
- 通过：current screenshot OCR contract / routing executor / ToolRegistry targeted JVM tests。
- 通过：validation script self-tests，覆盖弱 recentMediaOcr evidence、approved structured flow
  records、candidate/full release-flow evidence，以及 remote memory boundary exact 字段。
- 通过：`scripts/verify_local.sh`，包含 validation script tests、JVM tests、debug/release
  build、AndroidTest assemble、lint 和 artifact scan。

剩余风险：

- 本轮仍按要求跳过真机和模拟器验证；arm64 真机、arm64 emulator API matrix、真实 App 真机闭环、
  perf baseline、截图、release owner/manual/legal/signing 证据仍未完成。
- 子 Agent 发现但未在本轮改动的后续项：store policy 仍可加强 model capability profile
  绑定；manual acceptance 和 memory pressure evidence 可继续增加 key-specific 字段。

## 2026-06-23 Recent OCR runtime metadata minimization

本轮覆盖项：

- 在前一轮 release-flow evidence 明确 recent media OCR raw payload / trace redaction
  后，继续把运行时 `ToolResult` 源头收紧：`READ_RECENT_SCREENSHOT_OCR` 和
  `READ_RECENT_IMAGE_OCR` 成功结果不再返回 `name`、`mimeType`、`kind`、`sizeBytes`、
  `lastModifiedMillis` 等媒体身份字段。
- `recentOcrOutputSchemaJson` 同步移除这些字段；recent screenshot/image OCR 的
  `privateOutputKeys` 现在只保留 `ocrText`，避免 schema 暗示运行时仍可携带媒体文件身份。
- Agent loop OCR 观察测试现在断言 OCR 文本仍进入本地 continuation prompt、trace 中继续
  redacted，同时媒体身份字段在 observed result 和 `ToolObserved` 中直接不存在。
- `docs/agent_core_modules.md` 同步说明 recent OCR continuation 只需要 bounded text、
  scan counts、truncation、LocalOnly flags 和 metadata policy，不需要文件身份元数据。

验证命令：

```bash
ANDROID_HOME=/data00/home/zouguoxue/android-sdk \
ANDROID_SDK_ROOT=/data00/home/zouguoxue/android-sdk \
  ./gradlew :app:testDebugUnitTest \
    --tests 'com.bytedance.zgx.pocketmind.tool.DeviceContextToolExecutorTest' \
    --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest.privateToolOutputsAreDeclaredByToolPolicy' \
    --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest.privateDeviceOutputKeysRemainDeclaredInOutputSchemas' \
    --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.recentScreenshotOcrObservationBuildsLocalPromptAndRedactsTrace' \
    --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.recentImageOcrObservationBuildsLocalPromptAndRedactsTrace'

ANDROID_HOME=/data00/home/zouguoxue/android-sdk \
ANDROID_SDK_ROOT=/data00/home/zouguoxue/android-sdk \
  scripts/verify_local.sh
```

结果：

- 通过：targeted JVM tests 覆盖 recent screenshot/image OCR executor、ToolRegistry
  private-output/schema 合同、Agent loop 本地 continuation 与 trace redaction。
- 通过：`scripts/verify_local.sh`，包含 validation script tests、JVM tests、debug/release
  build、AndroidTest assemble、lint 和 artifact scan。

Roadmap 状态：

- Phase 1 本地稳定性底座继续保持通过。
- Phase 2 本地 replay / resolver evidence 已经扩展到淘宝、拼多多、高德、京东、Chrome、
  Android Browser、Quark、UC；真机真实 App 搜索闭环仍未标记 passed。
- Phase 3 安全与隐私边界继续推进；recent OCR runtime 现在从“红线字段被 trace redacted”
  前移到“工具结果不产生媒体身份字段”。
- Phase 4 release evidence gate 继续可本地验证，但 physical arm64 validation、arm64
  emulator API matrix、perf baseline、截图、release owner/manual/legal/signing 仍是外部阻塞项。

剩余风险：

- 本轮仍按要求跳过真机和模拟器验证；arm64 真机、arm64 emulator API matrix、真实 App 真机闭环、
  perf baseline、截图、release owner/manual/legal/signing 证据仍未完成。

## 2026-06-23 Privacy review capability binding and perf provenance gate

本轮覆盖项：

- 多 Agent 只读审计指出两个本地可推进缺口：privacy review 尚未绑定 Capability Matrix，
  standalone perf baseline verifier 尚未强制 baseline provenance 字段。两项都不需要真机或模拟器。
- `scripts/verify_privacy_review.sh` 现在要求 `docs/privacy_review.json` 绑定
  `docs/capability_matrix.json` 的路径和 SHA-256，并验证 Capability Matrix 至少包含结构化
  sensitive capability disclosure。三方 privacy review evidence 也必须绑定相同
  capability matrix path/SHA。
- Checked-in pending privacy review record 和 release/security/legal pending evidence
  已同步当前 capability matrix SHA；当前记录仍按预期失败在人工审批字段，而不是 stale SHA。
- `scripts/verify_perf_baseline.sh` 现在要求 baseline 文件自身包含
  `artifactSchema=PerfBaseline/v1`、`target=perf-baseline-record`、非空 `owner`、
  非空 `collectionCommand`，以及等于被验证 baseline 路径的 `reproduciblePath`。
  这让 `PERF_BASELINE_FILE=... scripts/verify_release_gate.sh` 不能接受缺 provenance 的薄
  performance evidence。
- `scripts/test_validation_scripts.sh` 新增 privacy capability SHA mismatch 负例和 perf
  missing provenance 负例，并同步所有 valid perf fixture 的 provenance 字段。

验证命令：

```bash
bash -n scripts/verify_privacy_review.sh scripts/verify_perf_baseline.sh \
  scripts/test_validation_scripts.sh

scripts/test_validation_scripts.sh

scripts/verify_privacy_review.sh \
  --report build/verification/privacy-review-current.properties || true

ANDROID_HOME=/data00/home/zouguoxue/android-sdk \
ANDROID_SDK_ROOT=/data00/home/zouguoxue/android-sdk \
  scripts/verify_local.sh
```

结果：

- 通过：shell 静态检查。
- 通过：validation script self-tests，覆盖 privacy review capability matrix SHA 绑定、
  checked-in pending review 不因 stale SHA 失败、approved fixture 正例、capability SHA
  mismatch 负例、perf baseline provenance 缺失负例，以及 release gate perf 子报告路径。
- 通过：当前 checked-in privacy review verifier 仍 fail-closed，失败原因保持在
  release/security/legal pending 审批，不再因为 notice/capability/evidence SHA 漂移失败。
- 通过：`scripts/verify_local.sh`，包含 validation script tests、JVM tests、debug/release
  build、AndroidTest assemble、lint 和 artifact scan。

Roadmap 状态：

- Phase 3 安全/隐私边界继续推进：privacy review 的审查对象现在绑定隐私声明和能力矩阵两份事实源。
- Phase 4 release evidence 继续推进：perf baseline verifier 在 standalone release gate
  路径上也强制 provenance，而不只依赖 release validation record 二次拦截。

剩余风险：

- 本轮仍按要求跳过真机和模拟器验证；arm64 真机、arm64 emulator API matrix、真实 App 真机闭环、
  perf baseline、截图、release owner/manual/legal/signing 证据仍未完成。
- Store policy 与 model capability/download-size 的机器绑定仍可按子 Agent 建议继续加强。
