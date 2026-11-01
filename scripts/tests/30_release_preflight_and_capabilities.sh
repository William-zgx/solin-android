# shellcheck shell=bash
# Validation script tests: release script inventory, signing, preflight, capability matrix, model capability profiles, CI workflow.
# Sourced in order by scripts/test_validation_scripts.sh; shares its shell state
# (helpers, fakes, TMP_DIR, ARTIFACT_DIR). Not runnable on its own.

verify_local_release_scripts=(
  scripts/privacy_scan.sh
  scripts/scan_android_artifacts.sh
  scripts/verify_perf_baseline.sh
  scripts/verify_privacy_review.sh
  scripts/verify_release_record.sh
  scripts/verify_store_policy_record.sh
  scripts/verify_release_operations_record.sh
  scripts/verify_release_validation_record.sh
  scripts/verify_model_license_review.sh
  scripts/verify_model_capability_profiles.sh
  scripts/verify_real_app_search_report.sh
  scripts/verify_capability_matrix.sh
  scripts/release_preflight_fields.sh
  scripts/verify_model_memory_multimodal_local_gates.sh
  scripts/verify_release_mapping.sh
  scripts/verify_release_gate.sh
  scripts/collect_perf_baseline.sh
  scripts/collect_rc_perf_from_device.sh
  scripts/collect_model_license_metadata.sh
  scripts/sign_release_artifacts.sh
)
for script_path in "${verify_local_release_scripts[@]}"; do
  [[ -f "$script_path" ]] || fail "verify_local.sh syntax-check glob must cover $script_path"
done

for signing_script in scripts/sign_release_artifacts.sh scripts/package_bundled_models.sh; do
  if grep -q -- '--ks-pass "pass:' "$signing_script" ||
    grep -q -- '--key-pass "pass:' "$signing_script" ||
    grep -q -- '-storepass "$RELEASE_KEYSTORE_PASSWORD"' "$signing_script" ||
    grep -q -- '-keypass "$RELEASE_KEY_PASSWORD"' "$signing_script"; then
    fail "$signing_script must not pass signing passwords through argv"
  fi
  for expected in '--ks-pass "file:' '--key-pass "file:'; do
    assert_file_contains_text "$signing_script" "$expected"
  done
done

rc_perf_manifest_contract=(
  'android:name=".rcperf.RcPerfHarnessService"'
  'android:permission="android.permission.DUMP"'
  'android:foregroundServiceType="specialUse"'
  com.bytedance.zgx.solin.rcperf.RUN
)
for expected in "${rc_perf_manifest_contract[@]}"; do
  assert_file_contains_text app/src/rcPerfRelease/AndroidManifest.xml "$expected"
done

rc_perf_collector_contract=(
  'RC_PERF_ACTION="${PACKAGE_NAME}.rcperf.RUN"'
  'am start-foreground-service -a "$RC_PERF_ACTION"'
)
for expected in "${rc_perf_collector_contract[@]}"; do
  assert_file_contains_text scripts/collect_rc_perf_from_device.sh "$expected"
done

if grep -q 'android.intent.action.RUN' app/src/rcPerfRelease/AndroidManifest.xml scripts/collect_rc_perf_from_device.sh; then
  fail "rc perf collector/manifest must not use android.intent.action.RUN"
fi
for forbidden_rc_perf_secret in deepseekApiKey deepseekBaseUrl DEEPSEEK_API_KEY DEEPSEEK_BASE_URL; do
  if grep -q "$forbidden_rc_perf_secret" scripts/collect_rc_perf_from_device.sh; then
    fail "rc perf collector must not pass unused remote secret extras: $forbidden_rc_perf_secret"
  fi
done

for RELEASE_PREFLIGHT_VERIFIER in \
  scripts/verify_release_record.sh \
  scripts/verify_store_policy_record.sh \
  scripts/verify_privacy_review.sh \
  scripts/verify_model_license_review.sh \
  scripts/verify_release_validation_record.sh \
  scripts/verify_release_operations_record.sh; do
  for expected in \
    release_preflight_fields.sh \
    missingOwnerFields= \
    missingApprovalRoles= \
    missingEvidenceFiles= \
    deferredDeviceEvidence= \
    requiresHumanApproval=; do
    assert_file_contains_text "$RELEASE_PREFLIGHT_VERIFIER" "$expected"
  done
done

ADAPTIVE_SERVING_SAFE_SOURCE="$TMP_DIR/adaptive-serving-safe"
ADAPTIVE_SERVING_BAD_SOURCE="$TMP_DIR/adaptive-serving-bad"
mkdir -p "$ADAPTIVE_SERVING_SAFE_SOURCE" "$ADAPTIVE_SERVING_BAD_SOURCE"
cat > "$ADAPTIVE_SERVING_SAFE_SOURCE/ChatController.kt" <<'ADAPTIVE_SERVING_SAFE'
class ChatController {
    fun dispatch(binding: RunPlacementBinding) {
        val runtime = if (binding.placement == RunPlacement.Remote) remoteRuntime else localRuntime
        runtime.send()
    }
}
ADAPTIVE_SERVING_SAFE
cat > "$ADAPTIVE_SERVING_BAD_SOURCE/ChatController.kt" <<'ADAPTIVE_SERVING_BAD'
class ChatController {
    fun dispatch(binding: RunPlacementBinding, state: ChatUiState) {
        val auditOnly = binding.placement
        val routeRemote = state.inferenceMode == InferenceMode.Remote
        if (routeRemote) {
            remoteChatRuntime.send()
        } else {
            localChatRuntime.send()
        }
    }
}
ADAPTIVE_SERVING_BAD

ADAPTIVE_SERVING_SEPARATE_FUNCTION_SOURCE="$TMP_DIR/adaptive-serving-separate-function"
mkdir -p "$ADAPTIVE_SERVING_SEPARATE_FUNCTION_SOURCE"
cat > "$ADAPTIVE_SERVING_SEPARATE_FUNCTION_SOURCE/ChatController.kt" <<'ADAPTIVE_SERVING_SEPARATE_FUNCTION'
class ChatController {
    fun showReadiness(binding: RunPlacementBinding, state: ChatUiState) {
        val auditOnly = binding.placement
        if (state.inferenceMode == InferenceMode.Remote) {
            showRemoteReadiness()
        }
    }

    fun stopBoundRun() {
        chatPlacementRuntime.stop("run-1")
    }
}
ADAPTIVE_SERVING_SEPARATE_FUNCTION

CAPABILITY_MATRIX_DEFAULT_SOURCE_REPORT="$ARTIFACT_DIR/capability-matrix-default-serving-source.properties"
scripts/verify_capability_matrix.sh --report "$CAPABILITY_MATRIX_DEFAULT_SOURCE_REPORT" >/dev/null 2>&1 || true
assert_report_contains "$CAPABILITY_MATRIX_DEFAULT_SOURCE_REPORT" \
  "servingSourceDir=app/src/main/java/com/bytedance/zgx/solin"
if grep -q '^servingSourceContract=not-run$' "$CAPABILITY_MATRIX_DEFAULT_SOURCE_REPORT"; then
  fail "capability matrix verifier must inspect serving source by default"
fi

CAPABILITY_MATRIX_SHA="$(shasum -a 256 docs/capability_matrix.json | awk '{print $1}')"
expect_success \
  "capability matrix verifier accepts checked-in capability surface" \
  scripts/verify_capability_matrix.sh \
    --report "$ARTIFACT_DIR/capability-matrix.properties"
assert_release_verifier_report_schema \
  "$ARTIFACT_DIR/capability-matrix.properties" \
  "CapabilityMatrixVerification/v1" \
  "trust-privacy"
assert_report_contains "$ARTIFACT_DIR/capability-matrix.properties" "target=capability-matrix"
assert_report_contains "$ARTIFACT_DIR/capability-matrix.properties" "capabilityMatrixFile=docs/capability_matrix.json"
assert_report_contains "$ARTIFACT_DIR/capability-matrix.properties" "capabilityMatrixSha256=$CAPABILITY_MATRIX_SHA"
assert_report_contains "$ARTIFACT_DIR/capability-matrix.properties" "matrixVersion=1"
assert_report_contains "$ARTIFACT_DIR/capability-matrix.properties" "scenarioCount=6"
assert_report_contains "$ARTIFACT_DIR/capability-matrix.properties" "productCapabilityCount=19"
assert_report_contains "$ARTIFACT_DIR/capability-matrix.properties" "toolCapabilityCount=49"
assert_report_contains "$ARTIFACT_DIR/capability-matrix.properties" "sensitiveDisclosureCount=10"
assert_report_contains "$ARTIFACT_DIR/capability-matrix.properties" "requiredBehaviorBoundaryCount=1"
assert_report_contains "$ARTIFACT_DIR/capability-matrix.properties" "localEvidenceRemoteEligibleCount=0"
assert_report_contains "$ARTIFACT_DIR/capability-matrix.properties" "missingSensitiveDisclosureIds="
assert_report_contains "$ARTIFACT_DIR/capability-matrix.properties" "adaptiveInferenceRolloutDefaultStage=opt_in"
assert_report_contains "$ARTIFACT_DIR/capability-matrix.properties" "releaseLikeRolloutOff=false"
assert_report_contains "$ARTIFACT_DIR/capability-matrix.properties" "releaseLikeRolloutSelectable=true"
assert_report_contains "$ARTIFACT_DIR/capability-matrix.properties" "servingSourceContract=passed"

ADAPTIVE_ROLLOUT_GET_BY_NAME_DEBUG_GRADLE="$TMP_DIR/adaptive-rollout-get-by-name-debug.gradle.kts"
cat > "$ADAPTIVE_ROLLOUT_GET_BY_NAME_DEBUG_GRADLE" <<'ADAPTIVE_ROLLOUT_GET_BY_NAME_DEBUG'
android {
    defaultConfig {
        buildConfigField("String", "ADAPTIVE_INFERENCE_ROLLOUT_STAGE", "\"opt_in\"")
    }
    buildTypes {
        getByName("debug") {
            buildConfigField("String", "ADAPTIVE_INFERENCE_ROLLOUT_STAGE", "\"opt_in\"")
        }
        release {}
        create("rcPerfRelease") {
            initWith(getByName("release"))
        }
        create("bundledModels") {
            initWith(getByName("release"))
        }
    }
}
ADAPTIVE_ROLLOUT_GET_BY_NAME_DEBUG
expect_success \
  "capability matrix verifier accepts getByName debug opt-in with release-like variants selectable" \
  scripts/verify_capability_matrix.sh \
    --gradle-file "$ADAPTIVE_ROLLOUT_GET_BY_NAME_DEBUG_GRADLE" \
    --serving-source-dir "$ADAPTIVE_SERVING_SAFE_SOURCE" \
    --report "$ARTIFACT_DIR/capability-matrix-rollout-get-by-name-debug.properties"
assert_report_contains "$ARTIFACT_DIR/capability-matrix-rollout-get-by-name-debug.properties" \
  "releaseLikeRolloutSelectable=true"

ADAPTIVE_ROLLOUT_UNKNOWN_GRADLE="$TMP_DIR/adaptive-rollout-unknown.gradle.kts"
perl -0pe 's/"\\"opt_in\\""/"\\"future_stage\\""/' app/build.gradle.kts > "$ADAPTIVE_ROLLOUT_UNKNOWN_GRADLE"
expect_failure \
  "capability matrix verifier rejects unknown adaptive inference rollout stage" \
  scripts/verify_capability_matrix.sh \
    --gradle-file "$ADAPTIVE_ROLLOUT_UNKNOWN_GRADLE" \
    --serving-source-dir "$ADAPTIVE_SERVING_SAFE_SOURCE" \
    --report "$ARTIFACT_DIR/capability-matrix-rollout-unknown.properties"
assert_report_contains "$ARTIFACT_DIR/capability-matrix-rollout-unknown.properties" "status=failed"
assert_report_contains_text "$ARTIFACT_DIR/capability-matrix-rollout-unknown.properties" "adaptive-rollout-stage-invalid:future_stage"

ADAPTIVE_ROLLOUT_RELEASE_ENABLED_GRADLE="$TMP_DIR/adaptive-rollout-release-enabled.gradle.kts"
cat > "$ADAPTIVE_ROLLOUT_RELEASE_ENABLED_GRADLE" <<'ADAPTIVE_ROLLOUT_RELEASE_ENABLED'
android {
    defaultConfig {
        buildConfigField("String", "ADAPTIVE_INFERENCE_ROLLOUT_STAGE", "\"off\"")
    }
    buildTypes {
        debug {
            buildConfigField("String", "ADAPTIVE_INFERENCE_ROLLOUT_STAGE", "\"opt_in\"")
        }
        release {
            buildConfigField("String", "ADAPTIVE_INFERENCE_ROLLOUT_STAGE", "\"opt_in\"")
        }
    }
}
ADAPTIVE_ROLLOUT_RELEASE_ENABLED
expect_success \
  "capability matrix verifier accepts explicit release adaptive inference rollout" \
  scripts/verify_capability_matrix.sh \
    --gradle-file "$ADAPTIVE_ROLLOUT_RELEASE_ENABLED_GRADLE" \
    --serving-source-dir "$ADAPTIVE_SERVING_SAFE_SOURCE" \
    --report "$ARTIFACT_DIR/capability-matrix-rollout-release-enabled.properties"
assert_report_contains "$ARTIFACT_DIR/capability-matrix-rollout-release-enabled.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/capability-matrix-rollout-release-enabled.properties" \
  "releaseLikeRolloutSelectable=true"

ADAPTIVE_ROLLOUT_DYNAMIC_GRADLE="$TMP_DIR/adaptive-rollout-dynamic.gradle.kts"
cat > "$ADAPTIVE_ROLLOUT_DYNAMIC_GRADLE" <<'ADAPTIVE_ROLLOUT_DYNAMIC'
val configuredRolloutStage = "\"off\""
android {
    defaultConfig {
        buildConfigField(
            "String",
            "ADAPTIVE_INFERENCE_ROLLOUT_STAGE",
            configuredRolloutStage,
        )
    }
}
ADAPTIVE_ROLLOUT_DYNAMIC
expect_failure \
  "capability matrix verifier rejects dynamic adaptive rollout declarations" \
  scripts/verify_capability_matrix.sh \
    --gradle-file "$ADAPTIVE_ROLLOUT_DYNAMIC_GRADLE" \
    --serving-source-dir "$ADAPTIVE_SERVING_SAFE_SOURCE" \
    --report "$ARTIFACT_DIR/capability-matrix-rollout-dynamic.properties"
assert_report_contains_text "$ARTIFACT_DIR/capability-matrix-rollout-dynamic.properties" \
  "adaptive-rollout-stage-unreadable"

ADAPTIVE_ROLLOUT_RELEASE_INHERITS_DEBUG_GRADLE="$TMP_DIR/adaptive-rollout-release-inherits-debug.gradle.kts"
cat > "$ADAPTIVE_ROLLOUT_RELEASE_INHERITS_DEBUG_GRADLE" <<'ADAPTIVE_ROLLOUT_RELEASE_INHERITS_DEBUG'
android {
    defaultConfig {
        buildConfigField("String", "ADAPTIVE_INFERENCE_ROLLOUT_STAGE", "\"off\"")
    }
    buildTypes {
        debug {
            buildConfigField("String", "ADAPTIVE_INFERENCE_ROLLOUT_STAGE", "\"opt_in\"")
        }
        release {
            initWith(getByName("debug"))
        }
    }
}
ADAPTIVE_ROLLOUT_RELEASE_INHERITS_DEBUG
expect_success \
  "capability matrix verifier accepts release inheriting an enabled debug rollout" \
  scripts/verify_capability_matrix.sh \
    --gradle-file "$ADAPTIVE_ROLLOUT_RELEASE_INHERITS_DEBUG_GRADLE" \
    --serving-source-dir "$ADAPTIVE_SERVING_SAFE_SOURCE" \
    --report "$ARTIFACT_DIR/capability-matrix-rollout-release-inherits-debug.properties"
assert_report_contains "$ARTIFACT_DIR/capability-matrix-rollout-release-inherits-debug.properties" \
  "releaseLikeRolloutSelectable=true"

expect_success \
  "capability matrix verifier accepts serving target from bound placement" \
  scripts/verify_capability_matrix.sh \
    --serving-source-dir "$ADAPTIVE_SERVING_SAFE_SOURCE" \
    --report "$ARTIFACT_DIR/capability-matrix-serving-safe.properties"
assert_report_contains "$ARTIFACT_DIR/capability-matrix-serving-safe.properties" "servingSourceContract=passed"
expect_success \
  "capability matrix verifier does not cross function boundaries from preference checks" \
  scripts/verify_capability_matrix.sh \
    --serving-source-dir "$ADAPTIVE_SERVING_SEPARATE_FUNCTION_SOURCE" \
    --report "$ARTIFACT_DIR/capability-matrix-serving-separate-function.properties"
assert_report_contains "$ARTIFACT_DIR/capability-matrix-serving-separate-function.properties" \
  "servingSourceContract=passed"
expect_failure \
  "capability matrix verifier rejects serving target inferred from inference preference" \
  scripts/verify_capability_matrix.sh \
    --serving-source-dir "$ADAPTIVE_SERVING_BAD_SOURCE" \
    --report "$ARTIFACT_DIR/capability-matrix-serving-bad.properties"
assert_report_contains "$ARTIFACT_DIR/capability-matrix-serving-bad.properties" "status=failed"
assert_report_contains_text "$ARTIFACT_DIR/capability-matrix-serving-bad.properties" "serving-target-derived-from-inference-mode:ChatController.kt"

CAPABILITY_MATRIX_BAD_LOCAL_REMOTE="$TMP_DIR/capability-matrix-bad-local-remote.json"
python3 - docs/capability_matrix.json "$CAPABILITY_MATRIX_BAD_LOCAL_REMOTE" <<'PY'
import json
import pathlib
import sys

data = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
for capability in data["productCapabilities"]:
    if capability["capabilityId"] == "local_offline_chat":
        capability["remoteEligible"] = True
        break
pathlib.Path(sys.argv[2]).write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
PY
expect_failure \
  "capability matrix verifier rejects LocalEvidence remote eligibility drift" \
  scripts/verify_capability_matrix.sh \
    --file "$CAPABILITY_MATRIX_BAD_LOCAL_REMOTE" \
    --serving-source-dir "$ADAPTIVE_SERVING_SAFE_SOURCE" \
    --report "$ARTIFACT_DIR/capability-matrix-bad-local-remote.properties"
assert_release_verifier_report_schema \
  "$ARTIFACT_DIR/capability-matrix-bad-local-remote.properties" \
  "CapabilityMatrixVerification/v1" \
  "trust-privacy"
assert_report_contains "$ARTIFACT_DIR/capability-matrix-bad-local-remote.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/capability-matrix-bad-local-remote.properties" "failedTarget=capability-boundary"
assert_report_contains_text "$ARTIFACT_DIR/capability-matrix-bad-local-remote.properties" "local-evidence-remote-eligible:local_offline_chat"

CAPABILITY_MATRIX_BAD_DISCLOSURE="$TMP_DIR/capability-matrix-bad-disclosure.json"
python3 - docs/capability_matrix.json "$CAPABILITY_MATRIX_BAD_DISCLOSURE" <<'PY'
import json
import pathlib
import sys

data = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
data["sensitiveCapabilityDisclosures"] = [
    disclosure for disclosure in data["sensitiveCapabilityDisclosures"]
    if disclosure["capabilityId"] != "media_projection_screenshot_ocr"
]
pathlib.Path(sys.argv[2]).write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
PY
expect_failure \
  "capability matrix verifier rejects missing required sensitive disclosure" \
  scripts/verify_capability_matrix.sh \
    --file "$CAPABILITY_MATRIX_BAD_DISCLOSURE" \
    --serving-source-dir "$ADAPTIVE_SERVING_SAFE_SOURCE" \
    --report "$ARTIFACT_DIR/capability-matrix-bad-disclosure.properties"
assert_report_contains "$ARTIFACT_DIR/capability-matrix-bad-disclosure.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/capability-matrix-bad-disclosure.properties" "missingSensitiveDisclosureIds=media_projection_screenshot_ocr"
assert_report_contains_text "$ARTIFACT_DIR/capability-matrix-bad-disclosure.properties" "sensitive-disclosure-required-missing:media_projection_screenshot_ocr"
export ADAPTIVE_SERVING_SOURCE_DIR="$ADAPTIVE_SERVING_SAFE_SOURCE"

MODEL_CAPABILITY_PROFILES_SHA="$(shasum -a 256 docs/model_capability_profiles.json | awk '{print $1}')"
expect_success \
  "model capability profiles verifier accepts checked-in profile boundaries" \
  scripts/verify_model_capability_profiles.sh \
    --report "$ARTIFACT_DIR/model-capability-profiles.properties"
assert_release_verifier_report_schema \
  "$ARTIFACT_DIR/model-capability-profiles.properties" \
  "ModelCapabilityProfilesVerification/v1" \
  "model-capability"
assert_report_contains "$ARTIFACT_DIR/model-capability-profiles.properties" "modelCapabilityProfilesFile=docs/model_capability_profiles.json"
assert_report_contains "$ARTIFACT_DIR/model-capability-profiles.properties" "modelCapabilityProfilesSha256=$MODEL_CAPABILITY_PROFILES_SHA"
assert_report_contains "$ARTIFACT_DIR/model-capability-profiles.properties" "profileFile=docs/model_capability_profiles.json"
assert_report_contains "$ARTIFACT_DIR/model-capability-profiles.properties" "profileSha256=$MODEL_CAPABILITY_PROFILES_SHA"
assert_report_contains "$ARTIFACT_DIR/model-capability-profiles.properties" "profileVersion=1"
assert_report_contains "$ARTIFACT_DIR/model-capability-profiles.properties" "contractTest=ModelCapabilityProfilesDocumentationTest"
assert_report_contains "$ARTIFACT_DIR/model-capability-profiles.properties" "privacyBoundaryContainsLocalOnly=true"
assert_report_contains "$ARTIFACT_DIR/model-capability-profiles.properties" "profileCount=4"
assert_report_contains "$ARTIFACT_DIR/model-capability-profiles.properties" "customLocalTemplateCount=1"
assert_report_contains "$ARTIFACT_DIR/model-capability-profiles.properties" "remoteTemplateCount=2"
assert_report_contains "$ARTIFACT_DIR/model-capability-profiles.properties" "memoryEmbeddingProfileCount=1"
assert_report_contains "$ARTIFACT_DIR/model-capability-profiles.properties" "mobileActionProfileCount=1"
assert_report_contains "$ARTIFACT_DIR/model-capability-profiles.properties" "stableLocalChatProfileCount=2"
assert_report_contains "$ARTIFACT_DIR/model-capability-profiles.properties" "remoteCapabilityProfilePersistence=true"
assert_report_contains "$ARTIFACT_DIR/model-capability-profiles.properties" "connectivitySnapshotMemoryOnly=true"

MODEL_CAPABILITY_BAD_SOURCE="$TMP_DIR/model-capability-bad-source"
mkdir -p "$MODEL_CAPABILITY_BAD_SOURCE/data"
cp app/src/main/java/com/bytedance/zgx/solin/RemoteModels.kt "$MODEL_CAPABILITY_BAD_SOURCE/RemoteModels.kt"
cp app/src/main/java/com/bytedance/zgx/solin/data/PreferenceSettingsStore.kt "$MODEL_CAPABILITY_BAD_SOURCE/data/PreferenceSettingsStore.kt"
cp app/src/main/java/com/bytedance/zgx/solin/data/RemoteModelRepository.kt "$MODEL_CAPABILITY_BAD_SOURCE/data/RemoteModelRepository.kt"
cp app/src/main/java/com/bytedance/zgx/solin/data/Stores.kt "$MODEL_CAPABILITY_BAD_SOURCE/data/Stores.kt"
python3 - "$MODEL_CAPABILITY_BAD_SOURCE/data/PreferenceSettingsStore.kt" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
source = path.read_text(encoding="utf-8")
source = source.replace(
    "prefs.remove(Keys.REMOTE_CONNECTIVITY_STATUS)",
    "prefs[Keys.REMOTE_CONNECTIVITY_STATUS] = normalized.connectivityStatus.name",
)
path.write_text(source, encoding="utf-8")
PY
expect_failure \
  "model capability verifier rejects persisted connectivity snapshot state" \
  scripts/verify_model_capability_profiles.sh \
    --source-dir "$MODEL_CAPABILITY_BAD_SOURCE" \
    --report "$ARTIFACT_DIR/model-capability-persisted-connectivity.properties"
assert_report_contains "$ARTIFACT_DIR/model-capability-persisted-connectivity.properties" "status=failed"
assert_report_contains_text "$ARTIFACT_DIR/model-capability-persisted-connectivity.properties" "connectivity-status-persisted"

MODEL_MEMORY_MULTIMODAL_REPORT="$ARTIFACT_DIR/model-memory-multimodal-local-gates.properties"
MODEL_MEMORY_MULTIMODAL_PROFILE_REPORT="$ARTIFACT_DIR/model-memory-multimodal-profile.properties"
MODEL_MEMORY_MULTIMODAL_MATRIX_REPORT="$ARTIFACT_DIR/model-memory-multimodal-capability-matrix.properties"
expect_success \
  "model memory multimodal local gate binds profile, OCR, memory, and remote vision boundaries" \
  env MODEL_PROFILE_REPORT_FILE="$MODEL_MEMORY_MULTIMODAL_PROFILE_REPORT" \
    CAPABILITY_MATRIX_REPORT_FILE="$MODEL_MEMORY_MULTIMODAL_MATRIX_REPORT" \
    scripts/verify_model_memory_multimodal_local_gates.sh \
      --report "$MODEL_MEMORY_MULTIMODAL_REPORT"
assert_release_verifier_report_schema \
  "$MODEL_MEMORY_MULTIMODAL_REPORT" \
  "ModelMemoryMultimodalLocalGates/v1" \
  "model-capability"
assert_report_contains "$MODEL_MEMORY_MULTIMODAL_REPORT" "status=passed"
assert_report_contains "$MODEL_MEMORY_MULTIMODAL_REPORT" "modelCapabilityProfilesReportStatus=passed"
assert_report_contains "$MODEL_MEMORY_MULTIMODAL_REPORT" "capabilityMatrixReportStatus=passed"
assert_report_contains "$MODEL_MEMORY_MULTIMODAL_REPORT" "localVisionProfileCount=2"
assert_report_contains "$MODEL_MEMORY_MULTIMODAL_REPORT" "remoteVisionTemplateConfirmationCount=1"
assert_report_contains "$MODEL_MEMORY_MULTIMODAL_REPORT" "memoryEmbeddingLocalOnlyCount=1"
assert_report_contains "$MODEL_MEMORY_MULTIMODAL_REPORT" "mobileActionLocalOnlyCount=3"
assert_report_contains "$MODEL_MEMORY_MULTIMODAL_REPORT" "ocrLocalOnlyDisclosureCount=2"
assert_report_contains "$MODEL_MEMORY_MULTIMODAL_REPORT" "remoteSendDisclosureCount=1"
assert_report_contains "$MODEL_MEMORY_MULTIMODAL_REPORT" "runtimeUiEvidenceMode=static-contract"
assert_report_contains "$MODEL_MEMORY_MULTIMODAL_REPORT" "targetedJvmTestsRun=0"
assert_report_contains "$MODEL_MEMORY_MULTIMODAL_REPORT" "targetedJvmDeferredReason=targeted-jvm-not-run-in-this-phase"

MODEL_CAPABILITY_BAD_LOCAL_REMOTE="$TMP_DIR/model-capability-bad-local-remote.json"
python3 - docs/model_capability_profiles.json "$MODEL_CAPABILITY_BAD_LOCAL_REMOTE" <<'PY'
import json
import pathlib
import sys

data = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
for profile in data["profiles"]:
    if profile["capability"] == "MemoryEmbedding":
        profile["remoteEligible"] = True
        break
pathlib.Path(sys.argv[2]).write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
PY
expect_failure \
  "model capability profiles verifier rejects LocalOnly profile becoming remote eligible" \
  env MODEL_CAPABILITY_PROFILES_FILE="$MODEL_CAPABILITY_BAD_LOCAL_REMOTE" \
  scripts/verify_model_capability_profiles.sh \
    --report "$ARTIFACT_DIR/model-capability-bad-local-remote.properties"
assert_report_contains "$ARTIFACT_DIR/model-capability-bad-local-remote.properties" "status=failed"
assert_report_contains_text "$ARTIFACT_DIR/model-capability-bad-local-remote.properties" "profile-local-remote-eligible:memory-embedding-gemma-300m"

MODEL_CAPABILITY_BAD_REMOTE_CONFIRMATION="$TMP_DIR/model-capability-bad-remote-confirmation.json"
python3 - docs/model_capability_profiles.json "$MODEL_CAPABILITY_BAD_REMOTE_CONFIRMATION" <<'PY'
import json
import pathlib
import sys

data = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
data["remoteOpenAiCompatibleTemplates"][0]["requiresRemoteSendConfirmation"] = False
pathlib.Path(sys.argv[2]).write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
PY
expect_failure \
  "model capability profiles verifier rejects remote template without send confirmation" \
  env MODEL_CAPABILITY_PROFILES_FILE="$MODEL_CAPABILITY_BAD_REMOTE_CONFIRMATION" \
  scripts/verify_model_capability_profiles.sh \
    --report "$ARTIFACT_DIR/model-capability-bad-remote-confirmation.properties"
assert_report_contains "$ARTIFACT_DIR/model-capability-bad-remote-confirmation.properties" "status=failed"
assert_report_contains_text "$ARTIFACT_DIR/model-capability-bad-remote-confirmation.properties" "profile-remote-confirmation-required:remote-openai-compatible-remote-text-template"

MODEL_CAPABILITY_BAD_VISION="$TMP_DIR/model-capability-bad-vision.json"
python3 - docs/model_capability_profiles.json "$MODEL_CAPABILITY_BAD_VISION" <<'PY'
import json
import pathlib
import sys

data = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
for profile in data["profiles"]:
    if profile["capability"] == "MobileAction":
        profile["inputModalities"].append("Vision")
        profile["features"].append("VisionInput")
        profile["capabilities"]["vision"] = True
        break
pathlib.Path(sys.argv[2]).write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
PY
expect_failure \
  "model capability profiles verifier rejects vision support on non-chat profile" \
  env MODEL_CAPABILITY_PROFILES_FILE="$MODEL_CAPABILITY_BAD_VISION" \
  scripts/verify_model_capability_profiles.sh \
    --report "$ARTIFACT_DIR/model-capability-bad-vision.properties"
assert_report_contains "$ARTIFACT_DIR/model-capability-bad-vision.properties" "status=failed"
assert_report_contains_text "$ARTIFACT_DIR/model-capability-bad-vision.properties" "profile-vision-non-chat:mobile-action-270m"
python3 - <<'PY'
from pathlib import Path
import re
import sys

workflow = Path(".github/workflows/android.yml").read_text()

def fail(message):
    print(f"validation-script-test: {message}", file=sys.stderr)
    sys.exit(1)

actual_trace_input = re.search(
    r"^      ai_behavior_actual_trace_file:\n(?P<body>.*?)(?=^      [A-Za-z0-9_]+:|^  [A-Za-z0-9_-]+:|\Z)",
    workflow,
    re.MULTILINE | re.DOTALL,
)
if not actual_trace_input:
    fail("android workflow missing ai_behavior_actual_trace_file workflow_dispatch input")
for required in (
    "Path to the AI behavior actual-trace JSONL file",
    "required: true",
    "type: string",
):
    if required not in actual_trace_input.group("body"):
        fail(f"ai_behavior_actual_trace_file input missing marker: {required}")

def job_block(name):
    match = re.search(
        rf"^  {re.escape(name)}:\n(?P<body>.*?)(?=^  [A-Za-z0-9_-]+:|\Z)",
        workflow,
        re.MULTILINE | re.DOTALL,
    )
    if not match:
        fail(f"android workflow is missing {name} job")
    return match.group("body")

verify_block = job_block("verify")
emulator_regression_block = job_block("emulator-regression")
emulator_api_matrix_block = job_block("emulator-api-matrix")
release_archive_block = job_block("release-artifact-archive")
protected_signing_block = job_block("protected-signing")
final_release_gate_block = job_block("final-release-gate")

if "workflow_dispatch" not in verify_block:
    fail("verify job must run on workflow_dispatch before release artifacts are archived")
for required in (
    "runs-on: macos-14",
    "ALLOW_EMULATOR_INFRA_UNAVAILABLE=${{ github.event_name != 'workflow_dispatch' && '1' || '0' }}",
    '-no-snapshot-save',
    '-accel off',
    "Summarize emulator infrastructure skip",
    "The skipped report and emulator log are attached as CI artifacts.",
):
    if required not in emulator_regression_block:
        fail(f"emulator-regression missing transparent HVF infrastructure marker: {required}")
if "java-version: \"21\"" not in verify_block:
    fail("verify must use JDK 21 for localagents-rag generated classes")
for required in (
    "runs-on: macos-14",
    'REQUIRED_APIS="28 32 33 34 36"',
    "ALLOW_EMULATOR_INFRA_UNAVAILABLE=${{ github.event_name != 'workflow_dispatch' && '1' || '0' }}",
    '-no-snapshot-save',
    '-accel off',
    "scripts/prepare_emulator_api_matrix.sh",
    "scripts/regression_emulator_api_matrix.sh",
    "android-emulator-api-matrix-evidence",
):
    if required not in emulator_api_matrix_block:
        fail(f"emulator-api-matrix missing matrix marker: {required}")
if "needs: [verify, emulator-regression]" not in release_archive_block:
    fail("release-artifact-archive must depend on verify and emulator-regression")
if "needs: [verify, emulator-regression, release-artifact-archive]" not in protected_signing_block:
    fail("protected-signing must explicitly depend on verify, emulator-regression, and release-artifact-archive")
if "needs: [verify, emulator-regression, emulator-api-matrix, release-artifact-archive, protected-signing]" not in final_release_gate_block:
    fail("final-release-gate must explicitly depend on verify, emulator regression, API matrix, release artifacts, and protected signing")
for required in (
    "PUBLIC_RELEASE=1",
    "scripts/verify_release_gate.sh",
    "PERF_BASELINE_FILE",
    "AI_BEHAVIOR_ACTUAL_TRACE_FILE",
    "inputs.ai_behavior_actual_trace_file",
    "EXPECTED_SIGNING_CERT_SHA256",
    "android-emulator-api-matrix-evidence",
    "android-final-release-gate-evidence",
):
    if required not in final_release_gate_block:
        fail(f"final-release-gate missing release gate marker: {required}")
if "status=skipped" in protected_signing_block:
    fail("protected-signing must not report skipped when production signing secrets are missing")
for required in (
    "status=failed",
    "failedTarget=environment",
    "reason=protected-signing-secrets-not-configured",
    "exit 1",
):
    if required not in protected_signing_block:
        fail(f"protected-signing missing fail-closed marker: {required}")
PY
