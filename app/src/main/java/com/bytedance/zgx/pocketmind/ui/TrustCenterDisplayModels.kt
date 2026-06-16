package com.bytedance.zgx.pocketmind.ui

import com.bytedance.zgx.pocketmind.capability.CapabilityMatrix

internal data class SensitiveCapabilityDisclosureDisplayRow(
    val title: String,
    val body: String,
)

internal fun sensitiveCapabilityDisclosureDisplayRows(): List<SensitiveCapabilityDisclosureDisplayRow> =
    CapabilityMatrix.sensitiveCapabilityDisclosures.map { disclosure ->
        SensitiveCapabilityDisclosureDisplayRow(
            title = disclosure.displayName,
            body = "数据：${disclosure.dataAccessed}\n" +
                "同意：${disclosure.consentBoundary}\n" +
                "远程：${disclosure.remoteBoundary}\n" +
                "撤销/清除：${disclosure.revokeOrClearControl}",
        )
    }

internal data class TrustCenterCapabilityDisplayRow(
    val title: String,
    val body: String,
)

internal fun trustCenterCapabilityDisplayRows(): List<TrustCenterCapabilityDisplayRow> =
    CapabilityMatrix.nextStageMvpDescriptors.map { descriptor ->
        TrustCenterCapabilityDisplayRow(
            title = CapabilityMatrix.nextStageMvpScenarioTitle(descriptor.capabilityId),
            body = "入口：${descriptor.entrypoint}\n" +
                "边界：${descriptor.privacyLevel.name}；" +
                "确认：${descriptor.confirmationPolicy.name}；" +
                "远程：${if (descriptor.remoteEligible) "可用" else "不可自动发送"}\n" +
                "失败：${descriptor.failureBehavior}",
        )
    }

internal val TRUST_CENTER_CAPABILITY_TEXT: String
    get() = "集中展示下一阶段 MVP 场景：" +
        CapabilityMatrix.nextStageMvpScenarioTitles.values.joinToString("、") +
        "，并说明 LocalOnly、确认策略和 fail-closed 行为。"
