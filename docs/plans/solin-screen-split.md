# SolinScreen Composable Split -- Detailed Implementation Plan

## Overview

`SolinScreen.kt` at `app/src/main/java/com/bytedance/zgx/solin/ui/SolinScreen.kt` is
**6,324 lines** with **85 `@Composable` functions** and **53 callback parameters**
(reported as 42 in some summaries; the actual parameter count in the function signature
is 53 lambda/function-typed params plus 1 `resourceSampler`). This plan decomposes it into
10 focused UI component files under `ui/components/`, defines a `SolinScreenActions`
data class to replace individual callbacks, and provides a step-by-step migration
strategy.

---

## 1. Complete `@Composable` Function Inventory

All 85 composable functions with their exact starting line numbers:

### Entry Point
| # | Function | Line |
|---|---|---|
| 1 | `SolinScreen` | 167 |

### Glyphs & Background (Shared Primitives)
| # | Function | Line |
|---|---|---|
| 2 | `SolinGlyph` | 565 |
| 3 | `Modifier.solinTechBackdrop` | 744 |

### Top Bar / Model Status
| # | Function | Line |
|---|---|---|
| 4 | `ChatTopBar` | 775 |
| 5 | `CompactModelStatusChip` | 927 |
| 6 | `TopMenuItem` | 984 |
| 7 | `TopActionButton` | 1023 |
| 8 | `RuntimeStatusBadge` | 1056 |

### Empty State / Home Screen
| # | Function | Line |
|---|---|---|
| 9 | `ChatEmptyState` | 1109 |
| 10 | `HomePositioningPanel` | 1215 |
| 11 | `HomeCapabilityPills` | 1244 |

### First-Run Setup
| # | Function | Line |
|---|---|---|
| 12 | `FirstRunSetupPanel` | 1272 |
| 13 | `StatusSummaryRow` | 1361 |
| 14 | `QuickModelSetup` | 1386 |

### Trust Sheet Primitives (Shared)
| # | Function | Line |
|---|---|---|
| 15 | `TrustSheetSurface` | 1545 |
| 16 | `TrustSheetGroup` | 1583 |

### Trust Center / Remote Disclosure
| # | Function | Line |
|---|---|---|
| 17 | `RemoteModeDisclosureSheet` | 1607 |
| 18 | `RemoteModeDisclosureRows` | 1638 |
| 19 | `RemoteSendDisclosureSheet` | 1677 |
| 20 | `RemoteSendDisclosureRows` | 1812 |

### Tool Confirmation (Action Draft / External Outcome)
| # | Function | Line |
|---|---|---|
| 21 | `ActionDraftSheet` | 1865 |
| 22 | `ActionDataBoundary` | 1984 |
| 23 | `ActionParameterRows` | 2009 |
| 24 | `ExpandableActionText` | 2031 |
| 25 | `ExternalOutcomeSheet` | 2084 |

### Prompt Suggestions
| # | Function | Line |
|---|---|---|
| 26 | `PromptSuggestionList` | 2143 |

### Settings / Model Manager Sheet
| # | Function | Line |
|---|---|---|
| 27 | `ModelManagerSheet` | 2188 |
| 28 | `ModelInventoryPanel` | 2361 |
| 29 | `HuggingFaceAuthorizationPanel` | 2508 |
| 30 | `AddModelPanel` | 2629 |
| 31 | `EmptyPanelText` | 2681 |
| 32 | `ModelPathGuidance` | 2698 |
| 33 | `AdvancedModelPanel` | 2753 |
| 34 | `PanelSurface` | 2781 |
| 35 | `CurrentModelPanel` | 2811 |
| 36 | `RemoteModelPanel` | 2901 |
| 37 | `TrustBoundaryPanel` | 3064 |
| 38 | `RemoteSendDisclosurePolicySelector` | 3216 |
| 39 | `RemoteSendAuditList` | 3245 |
| 40 | `RemoteSendAuditRow` | 3271 |
| 41 | `TrustBoundaryRow` | 3330 |

### Memory Panel
| # | Function | Line |
|---|---|---|
| 42 | `MemoryTogglePanel` | 3379 |
| 43 | `LongTermMemoryRow` | 3508 |

### Session Manager
| # | Function | Line |
|---|---|---|
| 44 | `SessionManagerSheet` | 3576 |

### Background Task Panel
| # | Function | Line |
|---|---|---|
| 45 | `BackgroundTaskSheet` | 3647 |
| 46 | `PeriodicCheckPolicySection` | 3869 |
| 47 | `PeriodicCheckChoiceRow` | 4019 |
| 48 | `BackgroundTaskRow` | 4413 |

### Audit Panel
| # | Function | Line |
|---|---|---|
| 49 | `AgentTraceRunRow` | 3779 |
| 50 | `AgentTraceStepRow` | 3831 |
| 51 | `RunDataReceiptSummary` | 3847 |
| 52 | `AuditEventRow` | 4051 |

### Model Cards & Rows (Shared)
| # | Function | Line |
|---|---|---|
| 53 | `RecommendedModelCard` | 4577 |
| 54 | `ModelRow` | 4724 |
| 55 | `CapabilityMark` | 4813 |

### Section & Layout Helpers (Shared)
| # | Function | Line |
|---|---|---|
| 56 | `SectionTitle` | 4839 |
| 57 | `LocalTokenLimitBlock` | 4956 |
| 58 | `ChatUiState.pendingSelectedChatDownloadBytes` | 4988 |
| 59 | `DeviceCheck` | 5001 |
| 60 | `DeviceMetric` | 5060 |
| 61 | `ProgressBlock` | 5102 |
| 62 | `BackendChip` | 5144 |

### Generation Parameters (Shared / Settings)
| # | Function | Line |
|---|---|---|
| 63 | `GenerationParametersPanel` | 5161 |
| 64 | `ParameterSlider` | 5219 |

### Evidence / Context Strips (MessageList)
| # | Function | Line |
|---|---|---|
| 65 | `MemoryContextStrip` | 5269 |
| 66 | `SourcesStrip` | 5313 |
| 67 | `SourceCard` | 5344 |
| 68 | `RunTimelineStrip` | 5384 |
| 69 | `RecoveryActionEntry` | 5500 |

### Message Display
| # | Function | Line |
|---|---|---|
| 70 | `MessageBubble` | 5563 |
| 71 | `MessageContent` | 5652 |
| 72 | `CodeBlock` | 5682 |

### Message Input Bar (Composer)
| # | Function | Line |
|---|---|---|
| 73 | `Composer` | 5757 |
| 74 | `VoiceInputPermissionDisclosureDialog` | 5917 |
| 75 | `ComposerAttachmentButton` | 5948 |
| 76 | `ComposerTextInput` | 5974 |
| 77 | `ComposerVoiceButton` | 6005 |
| 78 | `ComposerModelButton` | 6026 |
| 79 | `ComposerSendButton` | 6047 |
| 80 | `RemoteAttachmentProtectionNotice` | 6101 |
| 81 | `VoiceInputPrivacyNotice` | 6121 |
| 82 | `ComposerIconButton` | 6135 |
| 83 | `PendingSharedInputStrip` | 6165 |
| 84 | `VoiceCaptureBar` | 6212 |
| 85 | `VoiceWaveform` | 6287 |

---

## 2. Logical Component Grouping

Each component below maps to a single file in `ui/components/`. Shared primitives that
multiple components depend on go in `ui/components/CommonUi.kt`.

---

### Component 1: `ModelStatusBar`

**Composables extracted:** `ChatTopBar`, `CompactModelStatusChip`, `RuntimeStatusBadge`,
`TopMenuItem`, `TopActionButton`

**File:** `app/src/main/java/com/bytedance/zgx/solin/ui/components/ModelStatusBar.kt`

**Purpose:** Top app bar showing brand mark, model status chip, session button, overflow
menu (model manager, privacy, background tasks, new session). Also shows the resource
pressure overlay when a sampler is provided.

**Props needed:**
```kotlin
@Composable
fun ModelStatusBar(
    state: ChatUiState,
    resourceSampler: (suspend () -> SystemResourceSnapshot?)?,
    onOpenModelManager: () -> Unit,
    onOpenPrivacyNotice: () -> Unit,
    onOpenSessions: () -> Unit,
    onOpenBackgroundTasks: () -> Unit,
    onCreateSession: () -> Unit,
    modifier: Modifier = Modifier,
)
```

**Callbacks emitted:** 5 callbacks (`onOpenModelManager`, `onOpenPrivacyNotice`,
`onOpenSessions`, `onOpenBackgroundTasks`, `onCreateSession`)

**Dependencies:**
- `ChatUiState` (reads: `isReady`, `isBusy`, `inferenceMode`, `modelHealth`, `statusText`)
- `SolinGlyph` (from CommonUi)
- `ResourcePressureOverlay` (existing: `ui/ResourcePressureOverlay.kt`)
- `R.drawable.solin_brand_mark`
- `LocalSolinColors`

**State read from ChatUiState:** `isReady`, `isBusy`, `isGenerating`, `isDownloading`,
`inferenceMode`, `modelHealth.state`, `statusText`

---

### Component 2: `MessageList`

**Composables extracted:** `MessageBubble`, `MessageContent`, `CodeBlock`,
`MemoryContextStrip`, `SourcesStrip`, `SourceCard`, `RunTimelineStrip`,
`RecoveryActionEntry`

**File:** `app/src/main/java/com/bytedance/zgx/solin/ui/components/MessageList.kt`

**Purpose:** Scrollable conversation display area. Shows message bubbles (user/assistant),
evidence context strips (public web sources, memory evidence, run timeline), and a
recovery action entry when applicable.

**Props needed:**
```kotlin
@Composable
fun MessageList(
    messages: List<ChatMessage>,
    isGenerating: Boolean,
    activePublicWebEvidence: List<PublicWebEvidencePack>,
    activeRunTimeline: List<RunTimelineItemUiSummary>,
    activeMemoryEvidence: List<MemoryEvidenceUiSummary>,
    latestRecoveryAction: AgentRecoveryAction?,
    isBusy: Boolean,
    pendingConfirmation: PendingAgentConfirmation?,
    pendingExternalOutcome: PendingExternalOutcomeConfirmation?,
    onOpenRecoveryAction: (AgentRecoveryAction) -> Unit,
    modifier: Modifier = Modifier,
)
```

**Callbacks emitted:** 1 (`onOpenRecoveryAction`)

**Dependencies:**
- `ChatMessage`, `MessageRole`
- `GenerationStats`
- `PublicWebEvidencePack`, `MemoryEvidenceUiSummary`, `RunTimelineItemUiSummary`
- `AgentRecoveryAction`, `PendingAgentConfirmation`, `PendingExternalOutcomeConfirmation`
- `SolinGlyph` (from CommonUi)
- `LocalSolinColors`
- `publicWebEvidenceDisplayRows()`, `runTimelineItemDisplayText()`,
  `memoryEvidenceDisplayText()`, `publicWebSourceDisplayText()` (internal display helpers)

**Internal helpers to co-locate:** `formatGenerationStats`, `splitMessageSegments`,
`toMessageAnnotatedString`, `appendMarkdownLine`, `publicWebEvidenceDisplayRows`,
`publicWebSourceDisplayText`, `publicWebSourceMetaText`, `safeStripDisplayText`

---

### Component 3: `MessageInputBar`

**Composables extracted:** `Composer`, `ComposerTextInput`, `ComposerSendButton`,
`ComposerAttachmentButton`, `ComposerVoiceButton`, `ComposerModelButton`,
`ComposerIconButton`, `PendingSharedInputStrip`, `RemoteAttachmentProtectionNotice`,
`VoiceInputPrivacyNotice`, `VoiceInputPermissionDisclosureDialog`, `VoiceCaptureBar`,
`VoiceWaveform`

**File:** `app/src/main/java/com/bytedance/zgx/solin/ui/components/MessageInputBar.kt`

**Purpose:** Bottom input area with text field, attachment picker, voice input toggle,
model manager shortcut, and send/stop button. Shows voice capture overlay when active,
remote-mode attachment warning, and pending shared input strip.

**Props needed:**
```kotlin
@Composable
fun MessageInputBar(
    state: ChatUiState,
    input: String,
    onInputChanged: (String) -> Unit,
    onOpenModelManager: () -> Unit,
    onStartVoiceInput: () -> Unit,
    onCancelVoiceInput: () -> Unit,
    onFinishVoiceInput: () -> Unit,
    onPickSharedAttachment: () -> Unit,
    onClearPendingSharedInput: (Long) -> Unit,
    onSend: () -> Unit,
    onStopGeneration: () -> Unit,
    modifier: Modifier = Modifier,
)
```

**Callbacks emitted:** 8 (`onInputChanged`, `onOpenModelManager`, `onStartVoiceInput`,
`onCancelVoiceInput`, `onFinishVoiceInput`, `onPickSharedAttachment`,
`onClearPendingSharedInput`, `onSend`/`onStopGeneration`)

**Dependencies:**
- `ChatUiState` (reads: `isBusy`, `isReady`, `isGenerating`, `inferenceMode`,
  `voiceCapture`, `pendingSharedInputDraft`, `statusText`)
- `InferenceMode`
- `SolinGlyph` (from CommonUi)
- `LocalSolinColors`
- `VoiceCaptureUiState`, `SharedInputDraft`
- `appendComposerInput()` helper
- `isVoiceStatusText()`, `isAgentExecutionOutcomeStatusText()` helpers

**Internal state managed:** `showVoicePermissionDisclosure` (local `rememberSaveable`)

---

### Component 4: `ToolConfirmationDialog`

**Composables extracted:** `ActionDraftSheet`, `ActionDataBoundary`, `ActionParameterRows`,
`ExpandableActionText`, `ExternalOutcomeSheet`

**File:** `app/src/main/java/com/bytedance/zgx/solin/ui/components/ToolConfirmationDialog.kt`

**Purpose:** Modal bottom sheet shown when the agent requests permission to execute a
tool/action. Displays the action draft summary, parameters, data boundary, runtime
permission requirements, and special access requirements. Also handles external outcome
recording sheet.

**Props needed:**
```kotlin
@Composable
fun ActionDraftSheet(
    confirmation: PendingAgentConfirmation,
    grantedSpecialAccessIds: Set<String>,
    onOpenSpecialAccessSettings: (SpecialAccessRequirement) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
)

@Composable
fun ExternalOutcomeSheet(
    pending: PendingExternalOutcomeConfirmation,
    onRecord: (AgentExternalOutcome) -> Unit,
)
```

**Callbacks emitted:**
- ActionDraft: `onOpenSpecialAccessSettings`, `onConfirm`, `onDismiss`
- ExternalOutcome: `onRecord`

**Dependencies:**
- `PendingAgentConfirmation`, `PendingExternalOutcomeConfirmation`
- `SpecialAccessRequirement`, `AgentExternalOutcome`
- `MobileActionFunctions` (for data boundary display)
- `TrustSheetSurface`, `TrustSheetGroup` (from TrustCenter or CommonUi)
- `SolinGlyph` (from CommonUi)
- `SectionTitle` (from CommonUi)
- `runtimePermissionRequirementsFor()`, `specialAccessRequirementsFor()`
- `actionDataBoundaryDisplayRows()`, `actionParameterDisplayRows()`,
  `actionTextDisplay()` helpers

---

### Component 5: `SettingsBottomSheet`

**Composables extracted:** `ModelManagerSheet`, `CurrentModelPanel`, `RemoteModelPanel`,
`ModelInventoryPanel`, `AddModelPanel`, `AdvancedModelPanel`,
`HuggingFaceAuthorizationPanel`, `GenerationParametersPanel`, `ParameterSlider`,
`ModelPathGuidance`, `SessionManagerSheet`, `RecommendedModelCard`, `ModelRow`,
`CapabilityMark`, `LocalTokenLimitBlock`, `DeviceCheck`, `DeviceMetric`, `ProgressBlock`,
`BackendChip`, `ChatUiState.pendingSelectedChatDownloadBytes`

**File:** `app/src/main/java/com/bytedance/zgx/solin/ui/components/SettingsBottomSheet.kt`

**Purpose:** Main settings and model management bottom sheet with 5 tabs: "Current"
(current model, backend, generation params), "Models" (inventory, add models, HF auth),
"Remote" (remote model config), "Advanced" (inference mode, advanced settings), and
"Privacy" (trust boundary -- see TrustCenter component). Also contains the session
management sheet.

**Props needed (main entry):**
```kotlin
@Composable
fun SettingsBottomSheet(
    state: ChatUiState,
    initialSelectedTab: Int,
    customModelUrl: String,
    huggingFaceAccessTokenInput: String,
    onCustomModelUrlChanged: (String) -> Unit,
    onHuggingFaceAccessTokenInputChanged: (String) -> Unit,
    onPickModel: () -> Unit,
    actions: SettingsBottomSheetActions,  // subset of SolinScreenActions
    onDismiss: () -> Unit,
)
```

**Callbacks emitted:** 25+ callbacks covering model import/download/load/select/delete,
inference mode, remote config, backend selection, generation parameters,
HuggingFace token, session create/select/delete, model page navigation,
setup model toggle, first-run skip.

**Dependencies:**
- Full `ChatUiState` (reads 40+ fields)
- `ModelCatalog`, `RecommendedModel`, `InstalledModelSummary`
- `RemoteModelConfig`, `RemoteModelConnectivityStatus`
- `GenerationParameters`, `BackendChoice`, `InferenceMode`
- `SetupTier`, `ModelCapability`, `ModelVerificationStatus`
- `ModelHealthState`, `LocalModelTokenLimits`
- `PeriodicCheckConstraints`
- `SolinGlyph`, `SectionTitle`, `PanelSurface`, `EmptyPanelText` (from CommonUi)
- `HUGGING_FACE_TOKEN_SETTINGS_URL`
- `labelToTabTag()`, `currentModelStatus()`, `compactModelStatus()`,
  `compactModelStatusShort()`, `modelHealthDisplayText()`,
  `pendingSelectedChatDownloadBytes()`, `pendingBasicDownloadBytes()`,
  `capabilityLabel()`, `capabilityIcon()`, `verificationLabel()` helpers

**Internal state managed:** `selectedTab` (local `rememberSaveable`),
`confirmClear` (in MemoryTogglePanel, but MemoryPanel is separate)

---

### Component 6: `MemoryPanel`

**Composables extracted:** `MemoryTogglePanel`, `LongTermMemoryRow`

**File:** `app/src/main/java/com/bytedance/zgx/solin/ui/components/MemoryPanel.kt`

**Purpose:** Memory toggle, semantic memory status display, and long-term memory
management (view, forget individual, clear all). Shown as a panel within the
SettingsBottomSheet "Privacy" tab, but can also be used standalone.

**Props needed:**
```kotlin
@Composable
fun MemoryPanel(
    state: ChatUiState,
    enabled: Boolean,
    onMemoryEnabledChanged: (Boolean) -> Unit,
    onForgetLongTermMemory: (String) -> Unit,
    onClearLongTermMemory: () -> Unit,
    modifier: Modifier = Modifier,
)
```

**Callbacks emitted:** 3 (`onMemoryEnabledChanged`, `onForgetLongTermMemory`,
`onClearLongTermMemory`)

**Dependencies:**
- `ChatUiState` (reads: `memoryEnabled`, `longTermMemories`, `semanticMemoryRuntimeStatus`,
  `semanticMemoryIndexedRecordCount`, `semanticMemoryLastRebuiltAtMillis`,
  `installedCapabilities`, `isBusy`)
- `LongTermMemorySummary`, `MemoryRecordType`
- `SemanticMemoryRuntimeStatus`
- `ModelCapability.MemoryEmbedding`
- `SolinGlyph` (from CommonUi)
- `PanelSurface`, `SectionTitle`, `EmptyPanelText` (from CommonUi)
- `semanticMemoryIndexStatusText()`, `MemoryRecordType.label()` helpers

**Internal state managed:** `confirmClear` (local `rememberSaveable` for clear-all dialog)

---

### Component 7: `BackgroundTaskPanel`

**Composables extracted:** `BackgroundTaskSheet`, `BackgroundTaskRow`,
`PeriodicCheckPolicySection`, `PeriodicCheckChoiceRow`

**File:** `app/src/main/java/com/bytedance/zgx/solin/ui/components/BackgroundTaskPanel.kt`

**Purpose:** Background task management modal. Shows active background tasks, task
history, periodic check policy configuration, and task cancellation.

**Props needed:**
```kotlin
@Composable
fun BackgroundTaskPanel(
    state: ChatUiState,
    onRefresh: () -> Unit,
    onCancelBackgroundTask: (String) -> Unit,
    onSetPeriodicCheckPolicy: (PeriodicCheckScheduleRequest) -> Unit,
    onDisablePeriodicCheckPolicy: () -> Unit,
    modifier: Modifier = Modifier,
)
```

**Callbacks emitted:** 4 (`onRefresh`, `onCancelBackgroundTask`,
`onSetPeriodicCheckPolicy`, `onDisablePeriodicCheckPolicy`)

**Dependencies:**
- `ChatUiState` (reads: `backgroundTasks`, `backgroundTaskHistory`,
  `periodicCheckPolicy`, `isBusy`)
- `BackgroundTaskSummary`, `ScheduledTaskStatus`, `ScheduledTaskType`
- `PeriodicCheckPolicySummary`, `PeriodicCheckScheduleRequest`
- `AgentRunState`
- `SolinGlyph` (from CommonUi)
- `SectionTitle`, `EmptyPanelText` (from CommonUi)
- `BackgroundTaskSummary.triggerLabel()`, `ScheduledTaskType.label()`,
  `ScheduledTaskStatus.label()`, `AgentRunState.label()`,
  `PeriodicCheckPolicySummary.statusLine()`,
  `PeriodicCheckPolicySummary.isSwitchEnabled()` helpers

---

### Component 8: `AuditPanel`

**Composables extracted:** `AgentTraceRunRow`, `AgentTraceStepRow`, `RunDataReceiptSummary`,
`AuditEventRow`

**File:** `app/src/main/java/com/bytedance/zgx/solin/ui/components/AuditPanel.kt`

**Purpose:** Audit event display and agent trace visualization. Currently embedded within
the BackgroundTaskSheet. This component extracts those audit-specific composables so they
can be used in the background task panel or standalone.

**Props needed:**
```kotlin
@Composable
fun AuditEventList(
    events: List<AuditEventSummary>,
    modifier: Modifier = Modifier,
)

@Composable
fun AgentTraceList(
    runs: List<AgentTraceRunUiSummary>,
    modifier: Modifier = Modifier,
)
```

**Callbacks emitted:** None (pure display)

**Dependencies:**
- `AuditEventSummary`, `AgentTraceRunUiSummary`, `AgentTraceStepUiSummary`
- `RunDataReceiptUiSummary`
- `AgentRunState`
- `SolinGlyph` (from CommonUi)
- `runDataReceiptDisplayText()`, `AgentRunState.label()` helpers

---

### Component 9: `DeviceContextStatus`

**Composables extracted:** `DeviceCheck`, `DeviceMetric`

**File:** `app/src/main/java/com/bytedance/zgx/solin/ui/components/DeviceContextStatus.kt`

**Purpose:** Device capability and permission status indicators. Currently used within
the model manager panels (CurrentModelPanel, AddModelPanel) to show ARM64 support and
storage availability. Also surfaces special access requirement status used in
ToolConfirmationDialog.

**Props needed:**
```kotlin
@Composable
fun DeviceContextStatus(
    isArm64Supported: Boolean,
    availableModelStorageBytes: Long,
    isBusy: Boolean,
    modifier: Modifier = Modifier,
)
```

**Callbacks emitted:** None (pure display)

**Dependencies:**
- `SolinGlyph` (from CommonUi)
- `SectionTitle` (from CommonUi)
- `ModelHealthState` (for health state label)

**Note:** `DeviceCheck` and `DeviceMetric` are currently used as inline row composables
in model panels. This component wraps them into a cohesive status block. The individual
`DeviceCheck` and `DeviceMetric` composables remain as `internal` helper composables
within this file.

---

### Component 10: `TrustCenter`

**Composables extracted:** `TrustBoundaryPanel`, `RemoteSendDisclosurePolicySelector`,
`RemoteSendAuditList`, `RemoteSendAuditRow`, `TrustBoundaryRow`,
`RemoteModeDisclosureSheet`, `RemoteModeDisclosureRows`, `RemoteSendDisclosureSheet`,
`RemoteSendDisclosureRows`, `TrustSheetSurface`, `TrustSheetGroup`

**File:** `app/src/main/java/com/bytedance/zgx/solin/ui/components/TrustCenter.kt`

**Purpose:** Privacy indicators, trust boundary explanations, remote mode disclosure
sheet, remote send disclosure sheet, remote send audit log, and remote send disclosure
policy selector. The "Privacy" tab of the SettingsBottomSheet delegates to
`TrustBoundaryPanel`. Standalone modal sheets handle remote mode and remote send
disclosures.

**Props needed:**
```kotlin
@Composable
fun TrustBoundaryPanel(
    state: ChatUiState,
    onRemoteSendDisclosurePolicySelected: (RemoteSendDisclosurePolicy) -> Unit,
    onReduceDeviceActionConfirmationsChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
)

@Composable
fun RemoteModeDisclosureSheet(
    disclosure: PendingRemoteModeDisclosure,
    onDismiss: () -> Unit,
)

@Composable
fun RemoteSendDisclosureSheet(
    disclosure: PendingRemoteSendDisclosure,
    disclosurePolicy: RemoteSendDisclosurePolicy,
    onConfirm: (Boolean) -> Unit,
    onMaskAndSend: () -> Unit,
    onSendAnyway: () -> Unit,
    onDismiss: () -> Unit,
)
```

**Callbacks emitted:**
- TrustBoundaryPanel: `onRemoteSendDisclosurePolicySelected`,
  `onReduceDeviceActionConfirmationsChanged`
- RemoteModeDisclosure: `onDismiss`
- RemoteSendDisclosure: `onConfirm`, `onMaskAndSend`, `onSendAnyway`, `onDismiss`

**Dependencies:**
- `ChatUiState` (reads: `remoteSendDisclosurePolicy`, `reduceDeviceActionConfirmations`,
  `remoteSendAuditEvents`, `isBusy`, `memoryEnabled`, `longTermMemories`)
- `PendingRemoteModeDisclosure`, `PendingRemoteSendDisclosure`
- `RemoteSendDisclosurePolicy`, `RemoteSendDisclosureKind`
- `RemoteSendAuditSummary`
- `SolinGlyph` (from CommonUi)
- `PanelSurface`, `SectionTitle` (from CommonUi)
- `LocalSolinColors`
- `trustDeletionBoundaryText()`, `semanticMemoryIndexStatusText()`,
  `remoteModeDisclosureDisplayRows()`, `remoteSendDisclosureDisplayRows()`,
  `remoteSendDisclosureCanSuppressForSession()`,
  `RemoteSendDisclosurePolicy.remoteSendPolicyLabel()`,
  `RemoteSendDisclosurePolicy.remoteSendPolicyDescription()`,
  `RemoteSendAuditSummary.remoteSendAuditTimeLabel()`,
  `String.userFacingPrivacyLabel()`,
  `sensitiveCapabilityDisclosureDisplayRows()`,
  `trustCenterCapabilityDisplayRows()` helpers
- Static text constants: `PRODUCT_POSITIONING_TEXT`, `PRODUCT_LOCAL_VALUE_TEXT`,
  `PRODUCT_REMOTE_VALUE_TEXT`, `PRODUCT_ACTION_VALUE_TEXT`,
  `TRUST_CENTER_CAPABILITY_TEXT`, `PRIVACY_POLICY_ENTRY_TEXT`,
  `TRUST_LOCAL_BOUNDARY_TEXT`, `TRUST_REMOTE_BOUNDARY_TEXT`,
  `TRUST_PERMISSION_BOUNDARY_TEXT`, `SENSITIVE_CAPABILITY_DISCLOSURE_TEXT`

---

### Shared: `CommonUi`

**Composables extracted:** `SolinGlyph`, `Modifier.solinTechBackdrop`, `SectionTitle`,
`EmptyPanelText`, `PanelSurface`, `SolinGlyphKind` enum

**File:** `app/src/main/java/com/bytedance/zgx/solin/ui/components/CommonUi.kt`

**Purpose:** Reusable UI primitives needed by every other component. Must be extracted
first (Step 0) so other components can depend on it.

**Dependencies:**
- `MaterialTheme`, `Canvas`, basic Compose foundation/layout
- `LocalSolinColors`

---

## 3. `SolinScreenActions` Data Class

Defined in `app/src/main/java/com/bytedance/zgx/solin/ui/components/SolinScreenActions.kt`.

```kotlin
package com.bytedance.zgx.solin.ui.components

import android.net.Uri
import com.bytedance.zgx.solin.BackendChoice
import com.bytedance.zgx.solin.GenerationParameters
import com.bytedance.zgx.solin.InferenceMode
import com.bytedance.zgx.solin.PendingAgentConfirmation
import com.bytedance.zgx.solin.PendingExternalOutcomeConfirmation
import com.bytedance.zgx.solin.RemoteModelConfig
import com.bytedance.zgx.solin.RemoteSendDisclosurePolicy
import com.bytedance.zgx.solin.SpecialAccessRequirement
import com.bytedance.zgx.solin.background.PeriodicCheckScheduleRequest
import com.bytedance.zgx.solin.orchestration.AgentExternalOutcome
import com.bytedance.zgx.solin.orchestration.AgentRecoveryAction

/**
 * Aggregates all user-action callbacks from SolinScreen into a single object.
 * This replaces the 53 individual lambda parameters on SolinScreen.
 */
data class SolinScreenActions(
    // ── Model Management ──
    val onImportModel: (Uri) -> Unit,
    val onDownloadModel: () -> Unit,
    val onDownloadRecommendedModel: (String) -> Unit,
    val onDownloadCustomModel: (String) -> Unit,
    val onSaveHuggingFaceAccessToken: (String) -> Unit,
    val onClearHuggingFaceAccessToken: () -> Unit,
    val onCancelDownload: () -> Unit,
    val onLoadModel: () -> Unit,
    val onRecommendedModelSelected: (String) -> Unit,
    val onInstalledModelSelected: (String) -> Unit,
    val onDeleteInstalledModel: (String) -> Unit,
    val onInferenceModeSelected: (InferenceMode) -> Unit,
    val onRemoteModelConfigChanged: (RemoteModelConfig) -> Unit,
    val onTestRemoteModelConnectivity: () -> Unit,
    val onBackendSelected: (BackendChoice) -> Unit,
    val onGenerationParametersChanged: (GenerationParameters) -> Unit,
    val onResetGenerationParameters: () -> Unit,
    val onOpenModelPage: (String) -> Unit,
    val onSetupModelToggled: (String, Boolean) -> Unit,
    val onDownloadSetupModels: () -> Unit,
    val onSkipFirstRunSetup: () -> Unit,

    // ── Session Management ──
    val onCreateSession: () -> Unit,
    val onSessionSelected: (String) -> Unit,
    val onDeleteSession: () -> Unit,

    // ── Memory ──
    val onMemoryEnabledChanged: (Boolean) -> Unit,
    val onForgetLongTermMemory: (String) -> Unit,
    val onClearLongTermMemory: () -> Unit,
    val onReduceDeviceActionConfirmationsChanged: (Boolean) -> Unit,

    // ── Background Tasks & Audit ──
    val onRefreshBackgroundTasks: () -> Unit,
    val onRefreshAuditEvents: () -> Unit,
    val onCancelBackgroundTask: (String) -> Unit,
    val onSetPeriodicCheckPolicy: (PeriodicCheckScheduleRequest) -> Unit,
    val onDisablePeriodicCheckPolicy: () -> Unit,

    // ── Device / Permissions ──
    val onOpenSpecialAccessSettings: (SpecialAccessRequirement) -> Unit,

    // ── Agent Confirmations ──
    val onConfirmAgentConfirmation: (PendingAgentConfirmation) -> Unit,
    val onDismissAgentConfirmation: (PendingAgentConfirmation?) -> Unit,
    val onRecordExternalOutcome: (PendingExternalOutcomeConfirmation, AgentExternalOutcome) -> Unit,
    val onOpenRecoveryAction: (AgentRecoveryAction) -> Unit,

    // ── Remote / Trust Disclosure ──
    val onDismissRemoteModeDisclosure: () -> Unit,
    val onConfirmRemoteSendDisclosure: (Boolean) -> Unit,
    val onConfirmRemoteSendWithMasking: () -> Unit,
    val onConfirmRemoteSendDespiteSensitive: () -> Unit,
    val onDismissRemoteSendDisclosure: () -> Unit,
    val onRemoteSendDisclosurePolicySelected: (RemoteSendDisclosurePolicy) -> Unit,

    // ── Chat / Input ──
    val onSendMessage: (String) -> Unit,
    val onSendPendingSharedInput: (String) -> Unit,
    val onClearPendingSharedInput: (Long) -> Unit,
    val onStopGeneration: () -> Unit,

    // ── Voice ──
    val onStartVoiceInput: () -> Unit,
    val onCancelVoiceInput: () -> Unit,
    val onFinishVoiceInput: () -> Unit,
    val onPickSharedAttachment: () -> Unit,
    val onVoiceInputConsumed: (Long) -> Unit,
)
```

The new `SolinScreen` signature after migration:

```kotlin
@Composable
fun SolinScreen(
    state: ChatUiState,
    actions: SolinScreenActions,
    resourceSampler: (suspend () -> SystemResourceSnapshot?)? = null,
    modifier: Modifier = Modifier,
)
```

---

## 4. Migration Strategy

### Guiding Principles

1. **Extract first, refactor later.** Each extraction keeps prop drilling; no state
   hoisting changes until all components are extracted.
2. **One component per PR/commit.** Each step produces a working build.
3. **CommonUi goes first.** Every component depends on `SolinGlyph`, `SectionTitle`, etc.
4. **Introduce `SolinScreenActions` last.** Only after all components are in their own
   files, replace the 53 individual callbacks with the single `actions` parameter.

---

### Step 0: Extract `CommonUi` Primitives

**What to do:**
1. Create `ui/components/CommonUi.kt`.
2. Move `SolinGlyphKind` enum, `SolinGlyph`, `Modifier.solinTechBackdrop`, `SectionTitle`,
   `EmptyPanelText`, `PanelSurface` to the new file.
3. Change visibility from `private` to `internal`.
4. In `SolinScreen.kt`, remove local definitions and add imports from
   `com.bytedance.zgx.solin.ui.components.*`.

**Files changed:**
- New: `app/src/main/java/com/bytedance/zgx/solin/ui/components/CommonUi.kt`
- Modified: `app/src/main/java/com/bytedance/zgx/solin/ui/SolinScreen.kt`

**Risk:** **Low.** Pure UI primitives with no business logic.

**Effort:** 1-2 hours

**Testing:** Visual regression -- all icons, backgrounds, section titles, and panel
surfaces render identically.

---

### Step 1: Extract `ModelStatusBar` (Simplest First)

**What to do:**
1. Create `ui/components/ModelStatusBar.kt`.
2. Move `ChatTopBar`, `CompactModelStatusChip`, `RuntimeStatusBadge`, `TopMenuItem`,
   `TopActionButton` to the new file.
3. Make them `internal` and define `ModelStatusBar` as the public entry composable.
4. In `SolinScreen.kt`, replace the inline `ChatTopBar(...)` call with
   `ModelStatusBar(state = state, resourceSampler = resourceSampler, ...)`.
5. Pass the 5 callbacks individually (prop drilling, not yet using `SolinScreenActions`).

**Files changed:**
- New: `app/src/main/java/com/bytedance/zgx/solin/ui/components/ModelStatusBar.kt`
- Modified: `app/src/main/java/com/bytedance/zgx/solin/ui/SolinScreen.kt`

**Risk:** **Low.** Top bar is read-only with simple click callbacks. No internal state
beyond `menuExpanded` which is self-contained.

**Effort:** 2-3 hours

**Testing:**
- Tap model status chip opens model manager
- Tap session button opens sessions
- Tap "More" menu shows dropdown with 4 items
- Each dropdown item triggers correct action
- Status badge updates when model loads/unloads

---

### Step 2: Extract `MessageInputBar`

**What to do:**
1. Create `ui/components/MessageInputBar.kt`.
2. Move all 13 composer-related composables (see Component 3 above) to the new file.
3. Also move `appendComposerInput()`, `isVoiceStatusText()`,
   `isAgentExecutionOutcomeStatusText()` helper functions.
4. Define `MessageInputBar` as the public entry composable with explicit props.
5. In `SolinScreen.kt`, replace the inline `Composer(...)` call.
6. Keep `input` state and `onInputChanged` in `SolinScreen` (hoisted state), pass down.

**Files changed:**
- New: `app/src/main/java/com/bytedance/zgx/solin/ui/components/MessageInputBar.kt`
- Modified: `app/src/main/java/com/bytedance/zgx/solin/ui/SolinScreen.kt`

**Risk:** **Medium.** Reads many state fields and manages internal
`showVoicePermissionDisclosure` state. Voice input flow touches multiple callbacks.

**Effort:** 4-5 hours

**Testing:**
- Type and send message
- Stop generation mid-stream
- Voice input: tap, permission dialog, start, cancel, finish
- Attachment picker opens
- Model button opens model manager
- Pending shared input strip shows and clears
- Remote attachment warning shows in remote mode
- Voice capture bar with waveform renders

---

### Step 3: Extract `MessageList`

**What to do:**
1. Create `ui/components/MessageList.kt`.
2. Move `MessageBubble`, `MessageContent`, `CodeBlock`, `MemoryContextStrip`,
   `SourcesStrip`, `SourceCard`, `RunTimelineStrip`, `RecoveryActionEntry`.
3. Also move display helpers: `formatGenerationStats`, `splitMessageSegments`,
   `toMessageAnnotatedString`, `appendMarkdownLine`, `publicWebEvidenceDisplayRows`,
   `publicWebSourceDisplayText`, `publicWebSourceMetaText`, `safeStripDisplayText`,
   `runTimelineItemDisplayText`, `memoryEvidenceDisplayText`.
4. Define `MessageList` as the public `LazyColumn` composable.
5. The `LazyListState` (`listState`) stays in `SolinScreen` and is passed in, so
   `SolinScreen` retains control over scroll-to-bottom behavior.

**Files changed:**
- New: `app/src/main/java/com/bytedance/zgx/solin/ui/components/MessageList.kt`
- Modified: `app/src/main/java/com/bytedance/zgx/solin/ui/SolinScreen.kt`

**Risk:** **Medium.** Message list is the most frequently recomposed part. Scroll state
preservation and streaming indicator must work correctly.

**Effort:** 4-5 hours

**Testing:**
- Send/receive messages render correctly
- Streaming indicator shows during generation
- Scroll to bottom on new message
- Evidence strips (sources, memory, timeline) render above composer
- Recovery action entry shows and is clickable
- Code blocks render with horizontal scroll
- Markdown bold/lists render correctly

---

### Step 4: Extract `ToolConfirmationDialog`

**What to do:**
1. Create `ui/components/ToolConfirmationDialog.kt`.
2. Move `ActionDraftSheet`, `ActionDataBoundary`, `ActionParameterRows`,
   `ExpandableActionText`, `ExternalOutcomeSheet`.
3. Also move `ACTION_SUMMARY_COLLAPSE_CHARS`, `ACTION_PARAMETER_COMPACT_CHARS`,
   `ACTION_PARAMETER_COLLAPSE_CHARS` constants.
4. Also move `actionDataBoundaryDisplayRows()`, `actionParameterDisplayRows()`,
   `actionTextDisplay()` helper functions.
5. `TrustSheetSurface` and `TrustSheetGroup` are needed here. They can either be:
   - Moved to `CommonUi` (preferred, since TrustCenter also uses them), or
   - Duplicated, or
   - `TrustCenter` is extracted first and `ToolConfirmationDialog` imports from it.
   **Decision:** Move `TrustSheetSurface` and `TrustSheetGroup` to `CommonUi` in Step 0
   (update Step 0 to include these two).

**Files changed:**
- New: `app/src/main/java/com/bytedance/zgx/solin/ui/components/ToolConfirmationDialog.kt`
- Modified: `app/src/main/java/com/bytedance/zgx/solin/ui/SolinScreen.kt`
- Modified (Step 0 update): `CommonUi.kt` gains `TrustSheetSurface`, `TrustSheetGroup`

**Risk:** **Low-Medium.** These are modal sheets with show/hide logic already in
`SolinScreen`. The `ExpandableActionText` has internal saveable state (`expanded`).

**Effort:** 3-4 hours

**Testing:**
- Trigger tool confirmation (agent requests action)
- Confirm executes action
- Dismiss cancels without executing
- Special access requirement shows "Open settings" button
- Expand/collapse long parameter text
- External outcome sheet records success/failure

---

### Step 5: Extract `TrustCenter`

**What to do:**
1. Create `ui/components/TrustCenter.kt`.
2. Move `TrustBoundaryPanel`, `RemoteSendDisclosurePolicySelector`,
   `RemoteSendAuditList`, `RemoteSendAuditRow`, `TrustBoundaryRow`,
   `RemoteModeDisclosureSheet`, `RemoteModeDisclosureRows`,
   `RemoteSendDisclosureSheet`, `RemoteSendDisclosureRows`.
3. Also move all trust-related display helpers: `trustDeletionBoundaryText`,
   `semanticMemoryIndexStatusText`, `remoteModeDisclosureDisplayRows`,
   `remoteSendDisclosureDisplayRows`, `remoteSendDisclosureCanSuppressForSession`,
   `RemoteSendDisclosurePolicy.remoteSendPolicyLabel`,
   `RemoteSendDisclosurePolicy.remoteSendPolicyDescription`,
   `RemoteSendAuditSummary.remoteSendAuditTimeLabel`,
   `String.userFacingPrivacyLabel`,
   `sensitiveCapabilityDisclosureDisplayRows`,
   `trustCenterCapabilityDisplayRows`.
4. Also move all static text constants (PRODUCT_POSITIONING_TEXT, TRUST_*_TEXT, etc.)
   to a `TrustCenterConstants.kt` or keep them internal to `TrustCenter.kt`.

**Files changed:**
- New: `app/src/main/java/com/bytedance/zgx/solin/ui/components/TrustCenter.kt`
- Modified: `app/src/main/java/com/bytedance/zgx/solin/ui/SolinScreen.kt`

**Risk:** **Medium.** TrustBoundaryPanel reads many state fields. Static text constants
are referenced across multiple panels.

**Effort:** 4-5 hours

**Testing:**
- Privacy tab in model manager shows all sections
- Remote mode disclosure sheet appears on mode switch
- Remote send disclosure shows on sensitive content send
- Policy selector chips work
- Remote send audit list renders
- Reduce confirmations toggle works

---

### Step 6: Extract `BackgroundTaskPanel`

**What to do:**
1. Create `ui/components/BackgroundTaskPanel.kt`.
2. Move `BackgroundTaskSheet`, `BackgroundTaskRow`, `PeriodicCheckPolicySection`,
   `PeriodicCheckChoiceRow`.
3. Also move `BackgroundTaskSummary.triggerLabel()`, `ScheduledTaskType.label()`,
   `ScheduledTaskStatus.label()`, `AgentRunState.label()`,
   `PeriodicCheckPolicySummary.statusLine()`,
   `PeriodicCheckPolicySummary.isSwitchEnabled()`,
   `String.safeTestTagPart()`.
4. Define `BackgroundTaskPanel` as the public entry composable.
5. Note: `AuditEventRow` and agent trace rows are NOT extracted here -- they go to
   `AuditPanel` (Step 7). The `BackgroundTaskSheet` body will import `AuditEventList`
   and `AgentTraceList` from `AuditPanel` once that component is available.
   **For this step:** Keep `AuditEventRow`, `AgentTraceRunRow`, etc. as local
   composables within `BackgroundTaskPanel.kt` temporarily.

**Files changed:**
- New: `app/src/main/java/com/bytedance/zgx/solin/ui/components/BackgroundTaskPanel.kt`
- Modified: `app/src/main/java/com/bytedance/zgx/solin/ui/SolinScreen.kt`

**Risk:** **Low.** Self-contained modal. The periodic check section has some internal
state (selected interval) but it's local.

**Effort:** 3-4 hours

**Testing:**
- Open background tasks from top bar
- Active tasks show with cancel button (only for Scheduled status)
- Task history shows read-only
- Periodic check policy toggle and interval selection work
- Audit events and agent traces display (temporarily inline)

---

### Step 7: Extract `AuditPanel`

**What to do:**
1. Create `ui/components/AuditPanel.kt`.
2. Move `AgentTraceRunRow`, `AgentTraceStepRow`, `RunDataReceiptSummary`, `AuditEventRow`.
3. Also move `runDataReceiptDisplayText()`, `AgentRunState.label()` (if not already in
   BackgroundTaskPanel).
4. Define `AuditEventList` and `AgentTraceList` as public composables.
5. In `BackgroundTaskPanel.kt`, replace the local audit/trace composables with imports
   from `AuditPanel`.

**Files changed:**
- New: `app/src/main/java/com/bytedance/zgx/solin/ui/components/AuditPanel.kt`
- Modified: `app/src/main/java/com/bytedance/zgx/solin/ui/components/BackgroundTaskPanel.kt`
- Modified: `app/src/main/java/com/bytedance/zgx/solin/ui/SolinScreen.kt`

**Risk:** **Low.** Pure display components with no callbacks.

**Effort:** 2-3 hours

**Testing:**
- Audit events render in background task panel
- Agent trace runs with steps render
- Run data receipt summary shows token counts and timing

---

### Step 8: Extract `MemoryPanel`

**What to do:**
1. Create `ui/components/MemoryPanel.kt`.
2. Move `MemoryTogglePanel`, `LongTermMemoryRow`.
3. Also move `MemoryRecordType.label()`, `semanticMemoryIndexStatusText()` (if not in
   TrustCenter -- note: `semanticMemoryIndexStatusText` is also used by TrustCenter,
   so it should go to `CommonUi` or a shared helpers file).
4. Define `MemoryPanel` as the public entry composable.
5. In `SettingsBottomSheet` (still in SolinScreen.kt at this point), the
   `MemoryTogglePanel` call becomes `MemoryPanel(...)`.

**Files changed:**
- New: `app/src/main/java/com/bytedance/zgx/solin/ui/components/MemoryPanel.kt`
- Modified: `app/src/main/java/com/bytedance/zgx/solin/ui/SolinScreen.kt`

**Risk:** **Low.** Self-contained panel with clear inputs/outputs.

**Effort:** 2-3 hours

**Testing:**
- Memory toggle on/off
- Long-term memory list shows with forget buttons
- Clear all shows confirmation dialog
- Semantic memory status text updates based on runtime status

---

### Step 9: Extract `DeviceContextStatus`

**What to do:**
1. Create `ui/components/DeviceContextStatus.kt`.
2. Move `DeviceCheck`, `DeviceMetric`.
3. These are currently used inline in `CurrentModelPanel` and `AddModelPanel` (both
   still in `SolinScreen.kt` at this step).
4. Define `DeviceContextStatus` as a convenience wrapper composable that shows both
   device checks. Keep `DeviceCheck` and `DeviceMetric` as `internal` for direct use
   by panels that need individual metrics.

**Files changed:**
- New: `app/src/main/java/com/bytedance/zgx/solin/ui/components/DeviceContextStatus.kt`
- Modified: `app/src/main/java/com/bytedance/zgx/solin/ui/SolinScreen.kt`

**Risk:** **Low.** Small display-only components.

**Effort:** 1-2 hours

**Testing:**
- ARM64 support indicator shows in model panels
- Storage availability shows in model panels

---

### Step 10: Extract `SettingsBottomSheet` (Largest Component)

**What to do:**
1. Create `ui/components/SettingsBottomSheet.kt`.
2. Move `ModelManagerSheet`, `CurrentModelPanel`, `RemoteModelPanel`,
   `ModelInventoryPanel`, `AddModelPanel`, `AdvancedModelPanel`,
   `HuggingFaceAuthorizationPanel`, `GenerationParametersPanel`, `ParameterSlider`,
   `ModelPathGuidance`, `SessionManagerSheet`, `RecommendedModelCard`, `ModelRow`,
   `CapabilityMark`, `LocalTokenLimitBlock`, `ProgressBlock`, `BackendChip`,
   `ChatUiState.pendingSelectedChatDownloadBytes`.
3. Also move all model-related display helpers: `currentModelStatus()`,
   `compactModelStatus()`, `compactModelStatusShort()`, `modelHealthDisplayText()`,
   `capabilityLabel()`, `capabilityIcon()`, `verificationLabel()`,
   `RecommendedModel.requiresHuggingFaceAccessToken()`, `memoryModelStatusText()`,
   `RemoteModelConfig.hasAnySavedValue()`, `remoteConfigStatusText()`,
   `ChatUiState.backendChoiceEnabled()`, `labelToTabTag()`,
   `modelPathGuidanceRows()`, `MODEL_MANAGER_POSITIONING_TEXT`.
4. Also move `FirstRunSetupPanel`, `QuickModelSetup`, `StatusSummaryRow`,
   `ChatEmptyState`, `HomePositioningPanel`, `HomeCapabilityPills`,
   `PromptSuggestionList` -- these are used by `ModelManagerSheet`'s "Current" tab
   (via `CurrentModelPanel`) and by the empty state in `SolinScreen`.
   **Alternative:** Keep `ChatEmptyState` and home-related composables in
   `SolinScreen.kt` since they are part of the main screen layout, not the settings
   sheet. Move only `FirstRunSetupPanel` and `QuickModelSetup` into
   `SettingsBottomSheet.kt` since they are only used within the model manager.
5. `SessionManagerSheet` can go in `SettingsBottomSheet.kt` or be its own component.
   **Decision:** Keep `SessionManagerSheet` in `SettingsBottomSheet.kt` for now.
   It can be extracted later if sessions grow.

**Files changed:**
- New: `app/src/main/java/com/bytedance/zgx/solin/ui/components/SettingsBottomSheet.kt`
- Modified: `app/src/main/java/com/bytedance/zgx/solin/ui/SolinScreen.kt`

**Risk:** **High.** This is the largest and most complex component. It reads 40+ state
fields. Tab switching logic, panel delegation, and model download/import flows all
converge here.

**Effort:** 1.5-2 days

**Testing:**
- All 5 tabs render: Current, Models, Remote, Advanced, Privacy
- Current model panel: load status, backend chips, generation params, device checks
- Model inventory: installed models with select/delete, recommended models with download
- Add model: HF auth, custom URL, file picker
- Remote model: config fields, connectivity test
- Advanced: inference mode selector
- Privacy: delegates to TrustBoundaryPanel (imported from TrustCenter component)
- Session management: create, select, delete
- First-run setup flow (if showFirstRunSetup = true)

---

### Step 11: Introduce `SolinScreenActions` and Slim `SolinScreen`

**What to do:**
1. Create `ui/components/SolinScreenActions.kt` with the data class defined in Section 3.
2. In `SolinScreen.kt`, replace all 53 individual callback parameters with a single
   `actions: SolinScreenActions` parameter.
3. Thread `actions` into each extracted component:
   - `ModelStatusBar` receives `actions` and calls `actions.onCreateSession`, etc.
   - `MessageInputBar` receives `actions` and calls `actions.onSendMessage`, etc.
   - `MessageList` receives `actions.onOpenRecoveryAction`.
   - `ToolConfirmationDialog` receives relevant action callbacks.
   - `SettingsBottomSheet` receives a subset or the full `actions`.
   - `BackgroundTaskPanel` receives relevant action callbacks.
   - `TrustCenter` receives relevant action callbacks.
   - `MemoryPanel` receives relevant action callbacks.
4. `SolinScreen.kt` now contains only:
   - `rememberLauncherForActivityResult` for model file pick
   - `listState` for message list scroll
   - `input`, `customModelUrl`, `huggingFaceAccessTokenInput` local state
   - `showModelManager`, `showSessions`, `showBackgroundTasks` visibility state
   - `modelManagerInitialTab` state
   - The layout scaffolding: `Surface` > `Box` with backdrop > `Column` with
     `ModelStatusBar`, `MessageList` (or empty state), evidence strips, `MessageInputBar`
   - Modal sheet wrappers (the `ModalBottomSheet` calls that instantiate each component)
   - `LaunchedEffect` blocks for scroll-to-bottom and voice input consumption

**Files changed:**
- New: `app/src/main/java/com/bytedance/zgx/solin/ui/components/SolinScreenActions.kt`
- Heavily modified: `app/src/main/java/com/bytedance/zgx/solin/ui/SolinScreen.kt`

**Risk:** **Medium.** Every callback call site changes. Mechanical but error-prone if a
callback is missed or misrouted.

**Effort:** 3-4 hours

**Testing:** Full regression suite -- every user interaction must trigger the correct
ViewModel method. This is best verified by running the app and exercising each flow.

**Expected result:** `SolinScreen.kt` shrinks from 6,324 lines to approximately
400-500 lines of layout scaffolding.

---

### Step 12 (Optional): Per-Component ViewModels with Hilt

Once all components are extracted and `SolinScreenActions` is in place, each component
can optionally get its own ViewModel scoped to the navigation graph or a parent composable.

**Approach:**
1. Define a `SolinScreenViewModel` that holds `ChatUiState` as `State<ChatUiState>` and
   exposes `SolinScreenActions` methods that delegate to internal use cases.
2. Use `@HiltViewModel` with `hiltViewModel()` in each composable, or pass the ViewModel
   down from `SolinScreen`.
3. For complex components like `SettingsBottomSheet`, consider a sub-ViewModel:
   `ModelManagerViewModel` that handles model download state, HF token input, etc.
4. Use `CompositionLocal` to pass `SolinScreenActions` down the tree, avoiding
   explicit prop drilling for callbacks.

**Risk:** **Medium-High.** Introducing Hilt ViewModels changes the DI wiring and
requires testing each component in isolation with a test ViewModel.

**Effort:** 2-3 days for full ViewModel extraction + Hilt wiring.

**Recommendation:** Defer this step until after Steps 0-11 are complete and stable.
Prop drilling with `SolinScreenActions` is sufficient for a screen of this complexity.

---

## 5. Effort Estimate Per Component

| Component | New File | Composables Moved | Effort |
|---|---|---|---|
| CommonUi | `components/CommonUi.kt` | 6 | 1-2 hrs |
| ModelStatusBar | `components/ModelStatusBar.kt` | 5 | 2-3 hrs |
| MessageInputBar | `components/MessageInputBar.kt` | 13 | 4-5 hrs |
| MessageList | `components/MessageList.kt` | 8 | 4-5 hrs |
| ToolConfirmationDialog | `components/ToolConfirmationDialog.kt` | 5 | 3-4 hrs |
| TrustCenter | `components/TrustCenter.kt` | 10 | 4-5 hrs |
| BackgroundTaskPanel | `components/BackgroundTaskPanel.kt` | 4 | 3-4 hrs |
| AuditPanel | `components/AuditPanel.kt` | 4 | 2-3 hrs |
| MemoryPanel | `components/MemoryPanel.kt` | 2 | 2-3 hrs |
| DeviceContextStatus | `components/DeviceContextStatus.kt` | 2 | 1-2 hrs |
| SettingsBottomSheet | `components/SettingsBottomSheet.kt` | 20+ | 12-16 hrs |
| SolinScreenActions | `components/SolinScreenActions.kt` | 0 (data class) | 3-4 hrs |

**Total estimated effort:** 7-9 working days for one developer (assuming familiarity
with the codebase and no unexpected build issues).

---

## 6. Risk Assessment Per Step

| Step | Component | Risk Level | Key Risks | Mitigation |
|---|---|---|---|---|
| 0 | CommonUi | **Low** | Missing imports when removing `private` | Run build after extraction; all call sites in SolinScreen |
| 1 | ModelStatusBar | **Low** | `menuExpanded` state scope; `ResourcePressureOverlay` import | Keep state internal to composable; verify import path |
| 2 | MessageInputBar | **Medium** | Voice input flow has 5 callbacks + internal `showVoicePermissionDisclosure`; `input` state hoisted in SolinScreen | Test voice start/cancel/finish; verify input clearing on send |
| 3 | MessageList | **Medium** | `LazyListState` must be passed from parent; streaming indicator logic depends on `isGenerating + lastMessageId` | Keep `listState` and `LaunchedEffect` in SolinScreen |
| 4 | ToolConfirmationDialog | **Low-Med** | `ExpandableActionText` has `rememberSaveable` per-text-item state; `TrustSheetSurface` dependency | Move `TrustSheetSurface` to CommonUi first |
| 5 | TrustCenter | **Medium** | Many static text constants referenced across panels; `semanticMemoryIndexStatusText` also used by MemoryPanel | Move shared text helpers to CommonUi or keep internal |
| 6 | BackgroundTaskPanel | **Low** | `PeriodicCheckChoiceRow` has internal `selectedInterval` state | Keep state internal; verify periodic check toggle |
| 7 | AuditPanel | **Low** | Pure display; `runDataReceiptDisplayText` may be needed by BackgroundTaskPanel too | Move display helper to shared location |
| 8 | MemoryPanel | **Low** | Clear-all confirmation dialog state (`confirmClear`) is internal | Keep `rememberSaveable` internal |
| 9 | DeviceContextStatus | **Low** | Very small; `DeviceCheck`/`DeviceMetric` used inline in panels | Keep as `internal` for direct import by panels |
| 10 | SettingsBottomSheet | **High** | 20+ composables; 5-tab switching logic; model download state; HF auth flow; session management; depends on TrustCenter, MemoryPanel, DeviceContextStatus | Extract in sub-steps: (a) move model cards, (b) move panels, (c) wire up tab switching |
| 11 | SolinScreenActions | **Medium** | Every callback call site changes; easy to miss a callback or misroute | Mechanical find-replace; run full app test after |
| 12 | ViewModels (opt) | **Medium-High** | DI wiring changes; component isolation testing | Defer until stable; use Hilt `@HiltViewModel` pattern |

---

## 7. Final Directory Structure

```
app/src/main/java/com/bytedance/zgx/solin/ui/
├── SolinScreen.kt                      (~450 lines: layout scaffolding only)
├── MessageMarkdown.kt                  (existing, unchanged)
├── ResourcePressureBadge.kt            (existing, unchanged)
├── ResourcePressureOverlay.kt          (existing, unchanged)
├── TrustCenterDisplayModels.kt         (existing, unchanged)
├── theme/                              (existing, unchanged)
└── components/
    ├── CommonUi.kt                     (SolinGlyph, SectionTitle, PanelSurface, EmptyPanelText, TrustSheetSurface, TrustSheetGroup, solinTechBackdrop)
    ├── SolinScreenActions.kt           (data class replacing 53 callbacks)
    ├── ModelStatusBar.kt               (ChatTopBar, CompactModelStatusChip, RuntimeStatusBadge, TopMenuItem, TopActionButton)
    ├── MessageList.kt                  (MessageBubble, MessageContent, CodeBlock, evidence strips, RecoveryActionEntry)
    ├── MessageInputBar.kt              (Composer, all Composer* buttons, VoiceCaptureBar, VoiceWaveform, voice/attachment notices)
    ├── ToolConfirmationDialog.kt       (ActionDraftSheet, ExternalOutcomeSheet, action parameter/data boundary helpers)
    ├── TrustCenter.kt                  (TrustBoundaryPanel, disclosure sheets, audit list, policy selector)
    ├── BackgroundTaskPanel.kt          (BackgroundTaskSheet, BackgroundTaskRow, periodic check section)
    ├── AuditPanel.kt                   (AuditEventRow, AgentTraceRunRow, AgentTraceStepRow, RunDataReceiptSummary)
    ├── MemoryPanel.kt                  (MemoryTogglePanel, LongTermMemoryRow)
    ├── DeviceContextStatus.kt          (DeviceCheck, DeviceMetric)
    └── SettingsBottomSheet.kt          (ModelManagerSheet, all model panels, session manager, generation params, model cards, first-run setup)
```
