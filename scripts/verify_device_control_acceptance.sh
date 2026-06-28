#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ANDROID_SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
export ANDROID_HOME="${ANDROID_HOME:-$ANDROID_SDK}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_SDK}"

GRADLE_CMD="${GRADLE_CMD:-./gradlew}"
ARTIFACT_DIR="${ARTIFACT_DIR:-build/verification/device-control-acceptance-$(date +%Y%m%d-%H%M%S)}"
REPORT_FILE="${REPORT_FILE:-${ARTIFACT_DIR}/device-control-acceptance.properties}"
UNIT_OUTPUT_FILE="${ARTIFACT_DIR}/unit-output.txt"
DEVICE_ARTIFACT_DIR="${ARTIFACT_DIR}/device"
DEVICE_REPORT_FILE="${DEVICE_ARTIFACT_DIR}/device-verification.properties"
INSTRUMENTATION_OUTPUT_FILE="${DEVICE_ARTIFACT_DIR}/instrumentation.txt"
LOGCAT_FILE="${DEVICE_ARTIFACT_DIR}/logcat.txt"
STARTED_AT_UTC="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

INSTRUMENTATION_CLASS="${INSTRUMENTATION_CLASS:-com.bytedance.zgx.solin.SolinAccessibilityServiceDeviceControlTest}"
DEVICE_CONTROL_SKIP_BUILD="${DEVICE_CONTROL_SKIP_BUILD:-${SKIP_BUILD:-0}}"
DEVICE_CONTROL_SKIP_INSTALL="${DEVICE_CONTROL_SKIP_INSTALL:-${SKIP_INSTALL:-0}}"
DEVICE_CONTROL_REQUIRE_ACCESSIBILITY="${DEVICE_CONTROL_REQUIRE_ACCESSIBILITY:-1}"
DEVICE_CONTROL_RESET_APP_DATA_AFTER_TESTS="${DEVICE_CONTROL_RESET_APP_DATA_AFTER_TESTS:-0}"
DEVICE_CONTROL_DEVICE_RUNNER="${DEVICE_CONTROL_DEVICE_RUNNER:-debug_broadcast}"

UNIT_TEST_ARGS=(
  :app:testDebugUnitTest
  --tests "com.bytedance.zgx.solin.AndroidManifestTest.declaresAccessibilityServiceForScreenStateAndConfirmedGestures"
  --tests "com.bytedance.zgx.solin.AgentRuntimePermissionPolicyTest.deviceControlToolsDeclareAccessibilityControlSpecialAccessOnly"
  --tests "com.bytedance.zgx.solin.safety.SafetyPolicyTest.boundaryPermissionsCannotSkipConfirmationPolicy"
  --tests "com.bytedance.zgx.solin.skill.BuiltInSkillRuntimeTest.plansCurrentAppUiSkillsAsObserveActVerifyTemplates"
  --tests "com.bytedance.zgx.solin.tool.RoutingAndValidatingToolExecutorTest.validatingExecutorRejectsInvalidDeviceControlArguments"
  --tests "com.bytedance.zgx.solin.tool.RoutingAndValidatingToolExecutorTest.deviceControlPermissionDeniedKeepsRecoverableLocalOnlyBoundary"
  --tests "com.bytedance.zgx.solin.tool.ToolRegistryTest.exposesSpecsForSupportedActionsWithConfirmationRequired"
  --tests "com.bytedance.zgx.solin.tool.ToolRegistryTest.localOnlyDeviceContextOutputsRequireLocalModelMetadata"
  --tests "com.bytedance.zgx.solin.tool.ToolRegistryTest.validateResultRejectsPrivateOutputWithRequiresLocalModelFalse"
  --tests "com.bytedance.zgx.solin.orchestration.AgentLoopRuntimeTest.failedDeviceControlObservationPlansSafeObserveCheckpoint"
  --tests "com.bytedance.zgx.solin.orchestration.AgentLoopRuntimeTest.successfulObservationCanPlanNextToolAndRequestConfirmationAgain"
)

mkdir -p "$ARTIFACT_DIR"

report_value() {
  local file="$1"
  local key="$2"
  if [[ -f "$file" ]]; then
    awk -F= -v key="$key" '$1 == key {sub(/^[^=]*=/, ""); print; exit}' "$file"
  fi
}

write_report() {
  local status="$1"
  local reason="${2:-}"
  local unit_status="${3:-}"
  local device_status="${4:-}"
  local instrumentation_count
  instrumentation_count="$(report_value "$DEVICE_REPORT_FILE" "instrumentation_test_count")"
  {
    echo "status=$status"
    echo "reason=$reason"
    echo "started_at_utc=$STARTED_AT_UTC"
    echo "finished_at_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "unit_status=$unit_status"
    echo "unit_output_file=$UNIT_OUTPUT_FILE"
    echo "device_status=$device_status"
    echo "device_report_file=$DEVICE_REPORT_FILE"
    echo "instrumentation_class=$INSTRUMENTATION_CLASS"
    echo "device_control_skip_build=$DEVICE_CONTROL_SKIP_BUILD"
    echo "device_control_skip_install=$DEVICE_CONTROL_SKIP_INSTALL"
    echo "device_control_require_accessibility=$DEVICE_CONTROL_REQUIRE_ACCESSIBILITY"
    echo "device_control_reset_app_data_after_tests=$DEVICE_CONTROL_RESET_APP_DATA_AFTER_TESTS"
    echo "device_control_device_runner=$DEVICE_CONTROL_DEVICE_RUNNER"
    echo "instrumentation_test_count=${instrumentation_count:-}"
    echo "instrumentation_output_file=$INSTRUMENTATION_OUTPUT_FILE"
    echo "logcat_file=$LOGCAT_FILE"
    echo "coverage_directions=screen_state_foundation,ui_action_runtime,accessibility_service,observe_act_verify_loop,safety_permission,app_skills,device_eval,privacy_boundary"
    echo "device_primitives=observe,tap,type_text,scroll,wait,press_back,node_not_found_recovery"
    echo "recoverable_failure_paths=permission_missing,node_not_found"
    echo "permission_missing_coverage=unit_required_device_when_secure_settings_restorable"
    echo "app_skill_templates=settings,browser,maps,draft_form"
    echo "privacy_boundary=local_only_requires_local_model_no_remote_screen_payload"
  } > "$REPORT_FILE"
  echo "Device control acceptance report: $REPORT_FILE"
}

echo "Running device-control unit acceptance tests..."
set +e
"$GRADLE_CMD" "${UNIT_TEST_ARGS[@]}" >"$UNIT_OUTPUT_FILE" 2>&1
UNIT_STATUS=$?
set -e
cat "$UNIT_OUTPUT_FILE"
if [[ "$UNIT_STATUS" -ne 0 ]]; then
  write_report failed unit-tests-failed failed not-run
  exit "$UNIT_STATUS"
fi

echo "Running device-control device acceptance tests..."
set +e
if [[ "$DEVICE_CONTROL_DEVICE_RUNNER" == "instrumentation" ]]; then
  ARTIFACT_DIR="$DEVICE_ARTIFACT_DIR" \
    VERIFICATION_REPORT_FILE="$DEVICE_REPORT_FILE" \
    INSTRUMENTATION_OUTPUT_FILE="$INSTRUMENTATION_OUTPUT_FILE" \
    LOGCAT_FILE="$LOGCAT_FILE" \
    INSTRUMENTATION_CLASS="$INSTRUMENTATION_CLASS" \
    SKIP_BUILD="$DEVICE_CONTROL_SKIP_BUILD" \
    SKIP_INSTALL="$DEVICE_CONTROL_SKIP_INSTALL" \
    REQUIRE_SOLIN_ACCESSIBILITY="$DEVICE_CONTROL_REQUIRE_ACCESSIBILITY" \
    RESET_APP_DATA_AFTER_TESTS="$DEVICE_CONTROL_RESET_APP_DATA_AFTER_TESTS" \
    scripts/install_and_test_device.sh
else
  ARTIFACT_DIR="$DEVICE_ARTIFACT_DIR" \
    REPORT_FILE="$DEVICE_REPORT_FILE" \
    LOGCAT_FILE="$LOGCAT_FILE" \
    SKIP_BUILD="$DEVICE_CONTROL_SKIP_BUILD" \
    SKIP_INSTALL="$DEVICE_CONTROL_SKIP_INSTALL" \
    scripts/run_device_control_debug_eval.sh
fi
DEVICE_STATUS=$?
set -e
if [[ "$DEVICE_STATUS" -ne 0 ]]; then
  nested_reason="$(report_value "$DEVICE_REPORT_FILE" "reason")"
  [[ -n "$nested_reason" ]] || nested_reason="device-tests-failed"
  write_report failed "$nested_reason" passed failed
  exit "$DEVICE_STATUS"
fi

write_report passed "" passed passed
