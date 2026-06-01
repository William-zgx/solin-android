# 真机验收清单

本页用于 device/emulator 验收，要求 Android SDK 中存在 `adb` 并连接一台已授权设备或模拟器。本地 JVM/lint/build 验证见 README 的 Testing 章节，不要求 `adb`。

## 连接手机

1. 手机设置里连续点 7 次“版本号”，打开开发者选项。
2. 在开发者选项里打开“USB 调试”。
3. 用支持数据传输的 USB 线连接 Mac。
4. 手机弹窗选择允许调试。
5. 在项目根目录运行：

```bash
adb devices -l
```

看到一台设备状态为 `device` 后继续。

小米 / HyperOS / MIUI 设备如果出现 `INSTALL_FAILED_USER_RESTRICTED`，需要在开发者选项里打开“USB 安装 / 通过 USB 安装”，并在手机弹出的安装确认里点允许。

## 自动验收

本地 JVM/lint/build 验证不要求连接设备，也不要求 `adb` 在 PATH：

```bash
scripts/doctor.sh
scripts/verify_local.sh
```

真机或模拟器验收要求 Android SDK 中存在 `adb`，并连接一台已授权设备：

```bash
scripts/doctor.sh --device
scripts/install_and_test_device.sh
```

`doctor --device` 只确认 SDK 里的 `adb` 工具可用；是否存在可执行验收的设备由
`install_and_test_device.sh` 检查。没有已授权设备、设备为 `offline` /
`unauthorized`，或多台已授权设备同时连接且没有指定目标时，脚本会在 Gradle
构建、APK 安装和 instrumentation 前退出。

脚本会检查：

- 只连接了一台已授权设备；或通过 `ANDROID_SERIAL` 选择其中一台。
- 设备支持 `arm64-v8a`。
- `/data` 分区大致有 3 GB 以上可用空间。
- Debug APK 可以安装。
- AndroidTest APK 可以安装。
- instrumentation runner 报告的测试总数全部通过。
- App 可以被启动。

默认情况下，脚本不会在测试后删除 App，也不会清空 App 数据；通过后会保留 debug App 并启动它。需要做干净首启验收时，显式运行：

```bash
CLEAN_DEVICE=1 scripts/install_and_test_device.sh
```

`CLEAN_DEVICE=1` 会在测试前卸载旧调试包，已经下载好的模型会被清掉；只在确认可以重新下载或重新导入时使用。

需要保留真机安装时，不要直接运行 `./gradlew :app:connectedDebugAndroidTest`；Android Gradle Plugin 可能会在 instrumentation 结束后清理安装包。

## 模拟器回归

模拟器用于 UI、确认链路、工具失败路径和普通聊天回归；LiteRT-LM 性能和 GPU 行为仍以真机为准。
工具执行矩阵由 JVM 单测覆盖；模拟器/真机仍用于确认卡、runtime permission 弹窗和 UI 审计入口端到端验证。

已启动一个模拟器时，优先使用 emulator-only helper，避免误选真机：

```bash
adb devices -l
ANDROID_SERIAL=emulator-5554 scripts/verify_emulator.sh
```

如果 `adb` 或 `emulator` 不在 `PATH`，先设置 SDK 路径，或直接使用
`$ANDROID_SDK_ROOT/platform-tools/adb` 与 `$ANDROID_SDK_ROOT/emulator/emulator`。

也可以让脚本先启动指定 AVD，再等待 boot completed 后复用安装和 instrumentation 流程：

```bash
AVD_NAME=focus_agent_api36_arm64 scripts/verify_emulator.sh
```

`verify_emulator.sh` 只接受 `emulator-*` 目标；未指定 `ANDROID_SERIAL` 时要求恰好一台已授权模拟器。如果只有真机或同时存在多台模拟器，脚本会在 Gradle 构建、安装和 instrumentation 前退出。
`AVD_NAME` 不存在时会列出可用 AVD 并在 Gradle 前退出。失败时会在
`build/verification/` 下尽量保存截图、UI dump、短 logcat 和 emulator 日志路径。

完整回归记录至少包含设备序列号或 AVD 名称、API、ABI、是否设置
`CLEAN_DEVICE=1`、执行命令和 instrumentation 测试总数。

## 手动模型验收

1. 使用 `CLEAN_DEVICE=1 scripts/install_and_test_device.sh` 或手动清除数据后打开“PocketMind”，确认首屏会展示基础能力包准备向导。
2. 默认只勾选基础对话模型；设备动作模型资产可以手动勾选，或之后到“模型”里补装。本地记忆默认走轻量
   token/hash 索引，不以 memory model asset 为开启条件。
3. 下载中确认能看到进度、字节数和取消入口。
4. 下载完成后确认模型出现在“本地模型”，状态显示“SHA-256 已校验”。
5. 关闭 Wi-Fi/移动网络后输入一个中文问题，确认能生成回答。
6. 回答完成后，确认助手消息附近显示本次 token 数和 token/s。
7. 点“模型”，调整 Temperature、Top P、Top K，确认页面能解释参数效果；调整后下一次生成立即生效。
8. 再下载或导入第二个模型，在“本地模型”里点选切换，确认顶部当前模型名更新并重新加载。
9. 点“会话”新建第二个会话，切回第一个会话，确认历史消息仍在；删除当前会话后还能继续问答。
10. 粘贴一个 `.litertlm` 下载链接，确认可以从链接启动下载。
11. 如果 GPU 初始化失败，确认 App 自动切到 CPU，且仍可问答。
12. 导入或自定义链接下载一个模型，确认状态显示“自定义未校验”，且不会因为文件名和推荐模型相同而显示为已校验。
13. 如果设备上已有旧推荐模型文件，确认它先显示“旧文件未校验”，校验成功后才可加载；失败时不应自动删除文件。

## 导入模型验收

1. 卸载重装或清除 App 数据。
2. 把一个兼容的对话 `.litertlm` 模型放到手机存储。
3. 打开 App，点顶部“模型”，再点击“导入本地文件”。
4. 选择 `.litertlm` 文件，确认导入进度可见。
5. 导入完成后确认自动加载并可离线问答。

## 失败场景

- 用非 `.litertlm` 文件导入，应显示“请选择 .litertlm 模型文件”。
- 输入非 http/https 下载链接，应提示链接无效。
- 存储空间不足时，应显示空间不足提示。
- 断网或非 Wi-Fi 时，下载应等待或失败，并给出可理解状态。

## 远程模型验收

- HTTPS 服务地址加模型名应可保存并切换远程模式。
- 非本机 HTTP 地址应被拒绝；`localhost`、`127.0.0.1`、`10.0.2.2` 可用于本机调试。
- 配置 API Key 后重启 App，应仍可读取配置；SharedPreferences 中不应保存明文 key。
- 远程回答应逐步显示流式片段，取消生成后 UI 应回到可继续输入状态。
- 远程错误不应展示响应体、Authorization 或 API Key 内容。

## 记忆与动作验收

- 开启记忆后，历史会话相关问题应能注入“本地记忆”上下文；关闭后不应显示记忆命中。普通会话召回仍由已保存会话历史重建，不写入长期记忆表。
- 输入本地记忆控制命令“记住：我喜欢简洁的中文回答”后，后续相关问题应能从显式持久化的偏好记忆中召回；已显式持久化的偏好/任务状态记录应出现在长期记忆列表。
- 默认轻量 token/hash 记忆可以通过保守的本地 alias 召回显式回答偏好和结构化活跃任务状态，例如“brief replies”召回“我偏好简洁回答”、“有哪些提醒”召回活跃 Reminder 任务；alias 不应写入 Room、长期记忆列表、`buildContext`、远程 prompt 或普通会话记录。
- 远程模型模式下，`记住：...` / `remember ...` 仍应只在本地更新长期偏好，不应调用远程模型，也不应把控制命令、偏好内容或本地确认消息写入后续远程 history。
- 仍处于运行中的后台任务应自动写入可遗忘的任务状态长期记忆，只包含任务类型、状态、触发时间和不透明任务记录 id；提醒标题、正文、工具参数、prompt 或远程响应不应进入长期记忆。任务取消、完成、失败或删除后，对应自动任务状态记忆应被移除。
- 重复执行同一句“记住：我喜欢简洁的中文回答”后，长期记忆列表不应出现重复偏好。
- 长期记忆应支持单条遗忘和清空；删除后不应再从对应显式持久化记录召回，清空不代表删除普通会话历史。
- 本地记忆不可用时，普通聊天仍应继续；此时不显示记忆命中，长期记忆列表可降级为空。
- 当前默认记忆召回是本地轻量 token/hash 索引；`MemoryRepository` 支持真正语义 runtime 命中时跳过词项重叠过滤，
  但 LiteRT embedding adapter 仍未接入。只有已校验的 memory model path 被 runtime controller 成功切到支持语义召回的 runtime 后，才可认为语义记忆启用。
- 安装或补装 memory model asset 本身不等于 embedding runtime 参与，也不作为当前真机验收通过条件；UI 不应把“资产已安装”误写成“语义检索已启用”。
- 未安装或未校验动作模型时，动作请求应显示“规则回退”的待确认草稿。
- 安装并校验动作模型后，支持的动作请求可以显示“动作模型实验”的待确认草稿；执行前仍必须经过用户确认。
- 确认动作后，聊天中应追加一条结构化执行结果，例如“工具执行结果：已打开网页搜索”。
- 取消动作后，不应打开外部 App 或系统页面，Agent run 应进入 `Cancelled` 并写入审计事件。
- 出现待确认动作后杀进程并重启 App，应恢复同一个确认 UI；恢复瞬间不应执行工具、不应弹 Android runtime permission，只有再次确认后才继续执行链路。
- 需要 Android runtime permission 的工具应在确认卡提前展示友好权限名和用途；如果用户在系统权限弹窗中拒绝权限，不应执行工具、不应自动重试，应显示结构化权限失败并清除待确认状态，同时保留 raw manifest permission 供审计。
- 需要 Usage Access 的前台 App 摘要不应触发 Android runtime permission 弹窗；确认卡应说明系统“使用情况访问权限 / Usage Access”设置入口，未授权时不应读取数据、不应自动重试，应返回结构化权限失败。
- 授予 Usage Access 后再次触发前台 App 摘要，只应返回最小 App metadata；不应展示完整使用历史、通知正文、窗口内容或自动上传到远程模型。
- 通过受确认保护的当前屏幕 Accessibility 文本快照工具读取当前屏幕文字时，应只在用户确认后读取当前 Accessibility 文本节点快照；结果应标记为 `LocalOnly`，raw `screenText` 不应进入 trace、audit、持久消息或远程 runtime。
- 当前屏幕 Accessibility 文本快照不等于截图、OCR、像素读取或语义屏幕理解；无 Accessibility 服务授权或节点读取失败时，应返回结构化失败，不应自动退化为截图/OCR/屏幕扫描。
- “打开链接 https://example.com” 应先出现确认；确认后只打开 HTTPS 链接，`http`、`file`、`content`、`javascript` 和自定义 scheme 应被拒绝。
- “启动微信” 或指定合法包名的 App 启动请求应通过 Skill-first 先出现确认；确认后只打开应用启动页，不接受任意 activity/action/data/extras。
- “打开微信应用详情设置” 或指定合法包名的 `android_app_details_settings` 请求应通过 Skill-first 先出现确认；确认后只打开白名单固定目标，不接受任意 targetId、URI、activity/action/data/extras。微信小程序、支付码、App 内设置或故障/文档问题不应降级成打开 App。
- 外部 Activity、分享面板、草稿页或 App 启动页打开后，UI、Agent trace 和 audit 只能说明“外部界面已打开，最终结果未验证”；不应声称分享、发送、保存或目标 App 内操作已经完成，也不应基于该未验证结果自动规划下一步工具。
- 未知工具、缺少参数或没有可处理 Intent 的设备，应显示明确失败原因，不应崩溃。
- 工具参数错误、权限拒绝或 provider 失败应返回结构化失败；校验拒绝时不应执行 delegate。
- 支持的动作应能在 Agent trace 中形成 `ToolRequested -> UserConfirmed -> ToolObserved -> AssistantResponded` 顺序。
- 支持的动作应先经过 `SafetyPolicy`，中高风险或外发文本工具不允许绕过确认。
- 确认并执行动作后，`tool_audit_events` 应记录计划、请求确认、用户确认和观察结果；记录中不应包含 API Key、完整 prompt 或工具参数明文。
- 通过“后台任务”入口应能查看最近持久化工具审计事件；列表只展示时间、事件类型、工具名、状态、风险、权限和不含参数的安全摘要，不展示工具参数、prompt、远程响应、剪贴板原文、Authorization 或 API Key。
- 通过“后台任务”入口应能查看最近 Agent 轨迹摘要；轨迹 UI 只读展示 run 状态和 step 摘要，不展示工具参数、完整 prompt、原始剪贴板文本或持久化 trace JSON；Room 中的 `agent_runs.input` 不应保存原始 prompt，持久 trace 摘要、JSON 预览和 allowlisted metadata value 应脱敏 key/token/email/bearer 等敏感片段。
- 普通聊天路由可携带最小设备上下文，例如推理模式、能力类别、存储估计和是否存在待确认动作；上下文不应包含联系人、通知、剪贴板或文件内容。
- “读取剪贴板” 应先出现确认；确认后可读取当前文本剪贴板，审计日志不应保存原始剪贴板文本。
- “分享这段文字：...” 应先出现确认；确认后只打开 Android 系统分享面板，不能声明目标 App 已完成发送。

## Skill 验收

- “帮我写封邮件说明明天延期” 应进入邮件草稿 Skill，最终使用 `compose_email` 工具且仍需确认。
- “帮我建个明天下午开会的日程” 应进入日程 Skill，最终使用 `create_calendar_event` 工具且仍需确认。
- “查去机场的路线” 应进入地图 Skill，最终使用 `search_maps` 工具且仍需确认。
- “搜一下 Kotlin 协程” 应进入信息查找 Skill，最终使用 `web_search` 工具且仍需确认。
- “提醒我 15 分钟后喝水” 应可由后台提醒 Skill 直接规划，不依赖动作模型先分类；最终使用 `schedule_reminder` 工具且仍需确认。
- “读取剪贴板” 应进入剪贴板上下文 Skill，最终使用 `read_clipboard` 工具且仍需确认。
- “分享这段文字：明天十点开会” 应进入系统分享 Skill，最终使用 `share_text` 工具且仍需确认。
- “总结剪贴板并分享” 即使不先进入普通动作草稿识别，也应进入剪贴板摘要分享 Skill：先确认 `read_clipboard`，本地摘要后再确认 `share_text`。
- 声明式多步 Skill 的模型输出只能通过 `argumentBindings` 进入后续工具确认卡；缺失 binding 或直接绑定私密工具原文到外发工具时应失败，不应生成确认卡、执行外发工具或泄漏原文。
- “总结剪贴板并分享” 到第二个 `share_text` 确认卡后杀进程并重启 App，如果该确认卡包含模型生成的外发 payload，应 fail closed，不应恢复摘要参数、自动打开分享面板、重跑旧 `read_clipboard`，或让旧 request id 继续推进。
- 多步 Skill 的 pending checkpoint 只能持久化 run/request/step id、manifest identity、输出 key 名和 private-output refs；不得写入 `SkillRunContinuation.outputs` 值、模型输出、剪贴板/OCR/屏幕文本、工具参数明文或原始用户输入。checkpoint 与 redacted `SkillPlan` 或当前工具 registry 不匹配时应 fail closed。
- 多步 Skill 在任一待确认工具处取消后，不应继续执行后续工具；已读取的私密工具输出不应出现在公开 trace、audit 或 UI 摘要里。
- Skill manifest 输入 schema 契约由 JVM 覆盖：有效自然语言输入会以 `input` 字段进入对应 Skill；缺失、空白或额外 Skill 输入字段不应生成确认卡，也不应调用工具。模拟器/真机仍用于验证确认卡和多步 UI 链路。

## 后台任务验收

- 安排提醒前应先出现动作确认，不确认时不应创建 `scheduled_tasks` 记录。
- 确认 `schedule_reminder` 后，`scheduled_tasks` 应写入 `Scheduled` 状态，聊天中应追加结构化工具结果并包含任务 id；工具结果和 Agent trace 应提供 `cancel_reminder` recovery metadata，但不写入提醒标题或正文。
- Android 13+ 首次确认提醒时应先弹出通知权限请求；拒绝后应显示结构化权限失败，不应创建误导性的成功状态。
- 到点后如果通知权限可用，应通过 `pocketmind_agent_reminders` 通知通道弹出提醒，并把任务状态更新为 `Delivered`。
- 如果触发时通知权限不可用，不应崩溃，任务应进入 `Failed`。
- “后台任务”入口应只展示仍处于 `Scheduled` 的运行中任务；`Running` / `Delivered` / `Failed` / `Cancelled` / `Deleted` 不应显示为运行中。
- “后台任务”入口应展示最近已结束任务历史；已送达、失败、已取消或已删除任务只读展示，不应出现取消按钮。
- 安排提醒后，使用返回的 `taskId` 发送“取消提醒 task-...”应出现独立确认卡；确认后才取消对应任务。无 `taskId`、API/实现/解释、否定命令、取消日历/联系人/邮件等负例不应出现确认卡或 tool audit。
- 取消运行中提醒后，应取消底层调度、把 `scheduled_tasks.status` 更新为 `Cancelled`，任务从运行中列表消失，出现在最近历史中，并显示“后台任务已取消”；任务不存在、已送达、已取消或本地状态竞态变化时不应声明回滚成功。
- 取消失败时任务仍保持运行中状态，UI 应显示可理解失败提示，不应误标为已取消。
- 多个同标题、同时间或 task id hash 碰撞的提醒应互不覆盖；取消其中一个提醒或触发 stale alarm 不应影响其他提醒，也不应投递过期标题/正文。
- “后台任务”入口应展示周期检查策略状态，包括启用状态、检查间隔、最小通知间隔、过期宽限、耗电约束、下次允许检查时间和最近运行结果。
- 用户保存或关闭周期检查策略后，应刷新运行中任务和最近历史；关闭成功后 `periodic-check-local` 不应继续显示为运行中。
- 周期检查策略更新失败时，UI 应显示可理解失败提示，不应把 WorkManager 未成功登记的策略展示为健康运行中。
- 周期检查只验收本地提醒巡检策略；不应把它泛化为后台聊天任务执行、屏幕扫描或文件内容扫描。

## 多模态入口验收

- 从 Android 分享菜单把文本分享到 PocketMind，应生成一条用户可见的分享 prompt。
- 点击输入区附件按钮应打开 Android 系统文档选择器；用户选择文本、图片、音频、视频、PDF 或 Office 文件后，应生成同一类用户可见的本地分享 prompt。
- 分享 `text/*` 文档时，App 可以生成用户可见、有界、本地文本摘录；摘录只进入本地 shared-input prompt。
- 分享或选择用户主动提供的 RTF 文档时，App 可以生成用户可见、有界、本地文本层摘录；摘录只进入本地 shared-input prompt，不代表完整富文档解析、版式理解或语义理解。
- 分享或选择用户主动提供的 `.docx` / `.xlsx` / `.pptx` Office Open XML 文件时，App 可以生成用户可见、有界、本地文本层摘录；摘录只进入本地 shared-input prompt，不代表完整文档解析、版式理解或语义理解。
- 分享或选择用户主动提供的 `image/*` 附件时，App 可以在本地生成用户可见、有界的 OCR 文本摘录；摘录只进入本地 shared-input prompt，不代表图片语义理解。
- 分享或选择音频、视频、PDF、旧版 Office、二进制和其他非文本附件时，App 只应读取 MIME 类型、文件名和大小等元数据，不应读取文件正文、像素或二进制内容。
- 自动生成的 shared-input 文本、OCR 摘录、文本/RTF/Office 摘录和附件元数据应标记为 `LocalOnly`；远程模式不应自动上传这些内容，应提示用户手动粘贴愿意发送的内容。
- 如果模型已就绪，分享 prompt 可直接进入普通聊天路由；如果模型未就绪，应落到会话里并提示先准备模型。
- 点击语音输入入口应拉起 Android 系统语音识别；转写成功后，文本只应出现在输入框。
- 用户未点击发送前，语音转写不应进入聊天路由，不应新增用户消息，也不应触发本地或远程模型。
- 语音入口不应读取本地音频文件；音频分享入口仍只读取元数据。
- 通过受确认保护的 `query_recent_files(kind="screenshots")` 查询最近截图时，只应展示截图候选文件的文件名、MIME、大小、修改时间等元数据；不应读取图片像素、文件路径、URI 或截图内容。
- 通过受确认保护的 `read_recent_screenshot_ocr` 识别最近截图文字时，App 只应读取最近 1 张截图并在本地生成 OCR 摘录；结果应标记为 `LocalOnly`，不应在 trace/audit/持久消息里保存截图 URI、路径、原始像素或 OCR 原文。
- 通过受确认保护的 `read_recent_image_ocr` 识别最近图片/照片文字时，App 最多扫描最近 3 张图片并在本地返回第一条有界 OCR 摘录；结果应标记为 `LocalOnly`，不应在 trace/audit/持久消息里保存图片 URI、路径、原始像素或 OCR 原文。
- OCR 摘录可以保留 ML Kit 识别出的文本块/行顺序；不应输出坐标、框选位置、图片标签、看图描述、像素或语义理解结果。
- 远程模型模式下，最近截图 OCR、最近图片 OCR 和当前屏幕 Accessibility 文本快照的后续回答不应自动调用远程 runtime；UI 应提示已保护对应本地内容，并要求切换本地模型或由用户手动粘贴愿意上传的内容。
- 当前只验收分享入口、系统文件选择入口、语音入口、`text/*` 有界摘录、RTF 文本层有界摘录、Office Open XML 文本层有界摘录、用户主动提供 `image/*` 的本地 OCR 有界摘录、最近截图 metadata 查询、最近 1 张截图确认式 OCR、最近 3 张图片确认式 OCR、受确认当前屏幕 Accessibility 文本节点快照与 metadata-only/LocalOnly 边界；截图捕获、语义屏幕理解、PDF/旧 Office 解析、完整文档解析、完整富文本保真、图片语义理解、任意媒体 OCR 和媒体内容理解仍是待实现项。
