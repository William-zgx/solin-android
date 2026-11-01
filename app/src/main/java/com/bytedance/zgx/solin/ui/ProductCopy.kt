package com.bytedance.zgx.solin.ui

internal const val REMOTE_ATTACHMENT_PROTECTION_NOTICE =
    "远程模式：图片发送前会确认；非图片附件、分享文本和 OCR 不会自动发送。"

internal const val AUTO_ATTACHMENT_PROTECTION_NOTICE =
    "自动偏好：本次位置会在发送时决定；如使用远程，图片发送前会确认，其他附件不会自动发送。"

internal const val ACTUAL_REMOTE_ATTACHMENT_PROTECTION_NOTICE =
    "本次使用远程模型：图片发送前会确认；非图片附件、分享文本和 OCR 不会自动发送。"

internal const val PRODUCT_POSITIONING_TEXT =
    "隐私优先的随身助手；远程可选，设备动作必须确认。"

internal const val PRODUCT_POSITIONING_SHORT_TEXT =
    "隐私优先的随身 AI 助手"

internal data class HomeSuggestion(
    val text: String,
    val category: String,
)

/** Ready-home rows: a task the user can start in one tap, plus its capability category. */
internal val HOME_SUGGESTIONS = listOf(
    HomeSuggestion(text = "明早 7 点叫我起床", category = "闹钟"),
    HomeSuggestion(text = "把这周的日程列给我", category = "日历"),
    HomeSuggestion(text = "打开微信扫一扫", category = "操作手机"),
    HomeSuggestion(text = "记住：我对花生过敏", category = "记忆"),
)

// Home: not-ready state picks one of three placements.
internal const val PRODUCT_HOME_NOT_READY_TITLE = "先选一个大脑"
internal const val PRODUCT_HOME_NOT_READY_LINE = "选好之前不读取手机内容，也不向外发送"
internal const val PRODUCT_HOME_LOCAL_CHOICE_TITLE = "在本机运行"
internal const val PRODUCT_HOME_LOCAL_CHOICE_LINE = "断网也能用，对话不离开手机"
internal const val PRODUCT_HOME_REMOTE_CHOICE_TITLE = "连接远程模型"
internal const val PRODUCT_HOME_REMOTE_CHOICE_LINE = "Key 加密保存在本机；图片发送前会确认"
internal const val PRODUCT_HOME_IMPORT_CHOICE_TITLE = "导入 .litertlm 文件"
internal const val PRODUCT_HOME_IMPORT_CHOICE_LINE = "已有模型文件时使用"
internal const val PRODUCT_HOME_DOWNLOAD_ACTION = "下载"
internal const val PRODUCT_HOME_CANCEL_DOWNLOAD_ACTION = "取消下载"
internal const val PRODUCT_HOME_MODEL_SELECTION_ROW = "选择要下载的模型"
internal const val PRODUCT_HOME_SKIP_ACTION = "稍后再说"
internal const val PRODUCT_HOME_MODEL_GATED_LABEL = "需 HF 授权"
internal const val PRODUCT_HOME_ARM64_REQUIRED_LINE = "此模型需要 64 位 ARM 设备"

// Home: ready state.
internal const val PRODUCT_HOME_GREETING = "今天要我帮你做什么？"
internal const val PRODUCT_HOME_PRIVACY_LINK = "哪些内容会留在本机？"
internal const val PRODUCT_HOME_PLACEMENT_LOCAL = "在本机运行，对话留在手机上"
internal const val PRODUCT_HOME_PLACEMENT_REMOTE = "远程模型回答"
internal const val PRODUCT_HOME_PLACEMENT_AUTO = "自动选择运行位置"

internal const val MODEL_MANAGER_POSITIONING_TEXT =
    "可下载或导入本地模型离线使用；远程多模态可选。切换远程会提醒，设备动作仍会先确认。"

internal const val PRODUCT_LOCAL_VALUE_TEXT =
    "下载或导入已验证本地模型后，基础问答可在手机上运行；支持图片的本地模型可处理主动选择的图片。会话、记忆和本地工具结果默认留在本机。"

internal const val PRODUCT_REMOTE_VALUE_TEXT =
    "远程模型只在你配置并切换后使用；主动选择的图片只在逐次确认后发送给远程视觉模型，不支持图片时直接提示不支持。"

internal const val PRODUCT_ACTION_VALUE_TEXT =
    "联系人、日历、系统页面、分享、提醒和屏幕相关能力都先展示用途、权限和风险，再由你确认或取消。"

internal const val PRIVACY_POLICY_ENTRY_TEXT =
    "说明哪些内容留在本机、什么时候会发送到远程，以及哪些设备能力必须你确认后才执行。"

internal const val REMOTE_MODE_DISCLOSURE_TEXT =
    "OpenAI 兼容地址和模型名；API Key 加密保存在本机。图片需开启并逐次确认后才会发送。"

internal const val MODEL_DOWNLOAD_RATIONALE_TEXT =
    "本地模型让基础问答离线可用；基础对话模型约 2.4 GB，缺少、下载失败或文件不完整时可在这里补装，也可以先配置远程模型。"

internal const val VOICE_INPUT_PRIVACY_DESCRIPTION =
    "语音输入；使用系统语音转写，结果只进入输入框，不自动发送，不读取本地音频文件；开启前会先确认"

internal const val VOICE_INPUT_PERMISSION_DISCLOSURE_TITLE =
    "开启语音输入"

internal const val VOICE_INPUT_PERMISSION_DISCLOSURE_BODY =
    "语音会交由 Android 系统语音识别服务处理；Solin 不保存音频文件，转写结果只进入输入框，不会自动发送。确认后才会请求麦克风权限或开始收音。"

internal const val TRUST_LOCAL_BOUNDARY_TEXT =
    "会话、长期记忆、设备上下文和本地工具结果默认留在本机；切到远程模型时，标记为仅本机的隐私消息和工具结果不会进入远程历史。"

internal const val TRUST_REMOTE_BOUNDARY_TEXT =
    "远程模型会收到当前可远程发送的对话上下文；你主动附加的图片会在逐次确认后随请求发送，非图片附件、分享文本、OCR 摘录和本地工具私密结果不会自动发送。"

internal const val TRUST_PERMISSION_BOUNDARY_TEXT =
    "联系人、日历、媒体、通知、当前屏幕、Accessibility 文本和截图 OCR 都需要运行时权限、系统特殊授权或前台一次性确认；动作工具仍需用户确认后执行。"

internal const val SENSITIVE_CAPABILITY_DISCLOSURE_TEXT =
    "麦克风、媒体、联系人/日历、Usage Stats、Accessibility、截图、远程图片发送和设备动作都有数据范围、同意边界，以及取消、撤销或清除路径。"
