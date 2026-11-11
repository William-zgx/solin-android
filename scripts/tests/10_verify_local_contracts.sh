# shellcheck shell=bash
# Validation script tests: verify_local.sh, emulator/upgrade/eval script contracts, perf template.
# Sourced in order by scripts/test_validation_scripts.sh; shares its shell state
# (helpers, fakes, TMP_DIR, ARTIFACT_DIR). Not runnable on its own.

spotless_line="$(grep -n 'GRADLE_CMD.*spotlessCheck' scripts/verify_local.sh | cut -d: -f1 | head -n 1)"
ksp_line="$(grep -n 'GRADLE_CMD.*:app:kspReleaseKotlin' scripts/verify_local.sh | cut -d: -f1 | head -n 1)"
verify_line="$(grep -n 'GRADLE_CMD.*:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :app:bundleRelease :releaseSmoke:assembleRelease' scripts/verify_local.sh | cut -d: -f1 | head -n 1)"
if [[ -z "$spotless_line" || -z "$ksp_line" || "$spotless_line" -ge "$ksp_line" ]]; then
  fail "verify_local.sh must check changed Kotlin formatting before compilation"
fi
assert_file_contains_text scripts/verify_local.sh 'SOLIN_SPOTLESS_RATCHET_REF'
assert_file_contains_text scripts/verify_local.sh 'SOLIN_SPOTLESS_SKIP=1'
assert_file_contains_text build.gradle.kts 'onlyIf { !spotlessSkip }'
if [[ -z "$ksp_line" || -z "$verify_line" || "$ksp_line" -ge "$verify_line" ]]; then
  fail "verify_local.sh must generate release KSP sources before lintDebug"
fi
grep -q 'RELEASE_AAB="app/build/outputs/bundle/release/app-release.aab"' scripts/verify_local.sh ||
  fail "verify_local.sh must verify the release AAB artifact"
grep -q -- '--aab "$RELEASE_AAB"' scripts/verify_local.sh ||
  fail "verify_local.sh must scan the release AAB artifact"
grep -q 'RELEASE_SMOKE_APK="releaseSmoke/build/outputs/apk/release/releaseSmoke-release.apk"' scripts/verify_local.sh ||
  fail "verify_local.sh must verify the dedicated release smoke APK"
grep -q 'ReleaseSmokeInstrumentation' scripts/verify_local.sh ||
  fail "verify_local.sh must verify the dedicated release smoke runner"

verify_local_shellcheck_scripts=(
  scripts/regression_emulator.sh
  scripts/check_emulator_api_matrix.sh
  scripts/prepare_emulator_api_matrix.sh
  scripts/prepare_x86_emulator.sh
  scripts/check_x86_emulator_host.sh
  scripts/capture_x86_release_screenshots.sh
  scripts/regression_emulator_api_matrix.sh
  scripts/install_review_device.sh
  scripts/verify_fresh_start_main_shell_emulator.sh
  scripts/live_remote_emulator.sh
  scripts/run_mock_target_app_search_eval.sh
  scripts/capture_release_screenshots.sh
  scripts/collect_release_flow_matrix_evidence.sh
  scripts/collect_crash_anr_smoke_evidence.sh
  scripts/record_manual_acceptance_evidence.sh
  scripts/record_release_flow_evidence.sh
  scripts/verify_upgrade_install_emulator.sh
  scripts/verify_ai_behavior_eval.sh
  scripts/collect_ai_behavior_actual_trace.sh
)
assert_file_contains_text scripts/verify_local.sh 'for script_path in scripts/*.sh scripts/lib/*.sh scripts/tests/*.sh; do'
assert_file_contains_text scripts/verify_local.sh 'bash -n "$script_path"'
for script_path in "${verify_local_shellcheck_scripts[@]}"; do
  [[ -f "$script_path" ]] || fail "verify_local.sh syntax-check glob must cover $script_path"
done
BROKEN_SYNTAX_SCRIPT="$TMP_DIR/broken-syntax.sh"
printf 'if then\n' > "$BROKEN_SYNTAX_SCRIPT"
if bash -n "$BROKEN_SYNTAX_SCRIPT" 2>/dev/null; then
  fail "bash -n must reject a script with a syntax error"
fi

MOCK_MODEL_DRIVEN_CASE_BLOCK="$(sed -n '/^run_model_driven_case()/,/^}/p' scripts/run_mock_target_app_search_eval.sh)"
for expected in \
  '--es verifySearchQuery "$query"' \
  '--es expectedPackageName "$package_name"' \
  '--es expectedAppName "$app_name"'; do
  assert_text_contains run_model_driven_case "$MOCK_MODEL_DRIVEN_CASE_BLOCK" "$expected"
done
assert_file_contains_text scripts/run_mock_target_app_search_eval.sh \
  'run_model_driven_case taobao com.taobao.taobao "淘宝" "海河牛奶"'

upgrade_install_contract=(
  releaseFlowPassed=false
  versionCodeIncreased=
  UPGRADE_BASE_APK
  UPGRADE_CURRENT_APK
  baseSignerSha256=
  currentSignerSha256=
  signerSha256Matches=
  baseInstallOutputFile=
  currentInstallOutputFile=
  testInstallOutputFile=
  baseApkMode=
  currentApkMode=
  currentSourceApk=
  currentAndroidTestSourceApk=
  baseVersionCodeRaw=
  currentVersionCodeRaw=
)
for expected in "${upgrade_install_contract[@]}"; do
  assert_file_contains_text scripts/verify_upgrade_install_emulator.sh "$expected"
done

eval_report_contract=(
  artifact_schema=
  artifact_id=
  failedTarget=
  api_level=
  abi=
  logcat_sha256=
)
for eval_script in scripts/run_device_control_debug_eval.sh scripts/run_real_app_search_eval.sh; do
  for expected in "${eval_report_contract[@]}"; do
    assert_file_contains_text "$eval_script" "$expected"
  done
done

release_gate_ai_contract=(
  '--trace-diff "$ARTIFACT_DIR/ai-behavior-planning-trace-diff.jsonl"'
  REQUIRE_AI_BEHAVIOR_ACTUAL_TRACE=1
  REQUIRE_AI_BEHAVIOR_RUNTIME_TRACE_SOURCE=1
  REQUIRE_AI_BEHAVIOR_AGENT_LOOP_RUNTIME_TRACE_SOURCE=1
  REQUIRE_AI_BEHAVIOR_NO_ALLOWED_FAILURES=1
  REQUIRE_AI_BEHAVIOR_PLACEMENT_RECONCILIATION=1
  VERIFY_AI_BEHAVIOR_EVAL=1
  --require-boundary-map
)
for expected in "${release_gate_ai_contract[@]}"; do
  assert_file_contains_text scripts/verify_release_gate.sh "$expected"
done
assert_file_contains_text \
  scripts/collect_ai_behavior_actual_trace.sh \
  --require-agent-loop-runtime-trace-source
assert_file_contains_text \
  scripts/collect_ai_behavior_actual_trace.sh \
  --require-placement-reconciliation

assert_report_contains docs/perf_baseline_template.properties "artifactSchema=PerfBaseline/v1"
assert_report_contains docs/perf_baseline_template.properties "target=perf-baseline-record"
assert_file_matches docs/perf_baseline_template.properties '^owner=.+$'
assert_file_matches \
  docs/perf_baseline_template.properties \
  '^collectionCommand=.*scripts/collect_rc_perf_from_device\.sh'
assert_file_matches docs/perf_baseline_template.properties '^reproduciblePath=/.+'
