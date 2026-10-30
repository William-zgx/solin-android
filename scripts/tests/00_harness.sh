# shellcheck shell=bash
# Validation script tests: temp dirs, cleanup trap, assertion helpers, fake SDK/adb/emulator/gradle, fixtures.
# Sourced in order by scripts/test_validation_scripts.sh; shares its shell state
# (helpers, fakes, TMP_DIR, ARTIFACT_DIR). Not runnable on its own.

TMP_DIR="$(mktemp -d)"
CLEANUP_PATHS=("$TMP_DIR/.cleanup-sentinel")
DEBUG_APK_UNDER_TEST="app/build/outputs/apk/debug/app-debug.apk"
DEBUG_APK_BACKUP="$TMP_DIR/app-debug.apk.backup"
DEBUG_APK_EXISTED=0
if [[ -f "$DEBUG_APK_UNDER_TEST" ]]; then
  mkdir -p "$(dirname "$DEBUG_APK_BACKUP")"
  cp "$DEBUG_APK_UNDER_TEST" "$DEBUG_APK_BACKUP"
  DEBUG_APK_EXISTED=1
fi
cleanup_validation_test() {
  if [[ "$DEBUG_APK_EXISTED" == "1" && -f "$DEBUG_APK_BACKUP" ]]; then
    mkdir -p "$(dirname "$DEBUG_APK_UNDER_TEST")"
    cp "$DEBUG_APK_BACKUP" "$DEBUG_APK_UNDER_TEST"
  else
    rm -f "$DEBUG_APK_UNDER_TEST"
  fi
  rm -rf "$TMP_DIR"
  local path
  for path in "${CLEANUP_PATHS[@]}"; do
    rm -f "$path"
  done
}
trap cleanup_validation_test EXIT

fail() {
  echo "validation-script-test: $*" >&2
  exit 1
}

fixture_dir_sha256() {
  local dir="$1"
  find "$dir" -maxdepth 1 -type f -name '*.jsonl' -print |
    LC_ALL=C sort |
    while IFS= read -r fixture_file; do
      printf '%s %s\n' \
        "$(shasum -a 256 "$fixture_file" | awk '{print $1}')" \
        "$(basename "$fixture_file")"
    done |
    shasum -a 256 |
    awk '{print $1}'
}

LAST_OUTPUT=""

expect_success() {
  local name="$1"
  shift
  if ! output="$("$@" 2>&1)"; then
    printf '%s\n' "$output" >&2
    fail "$name unexpectedly failed"
  fi
  LAST_OUTPUT="$output"
}

expect_failure() {
  local name="$1"
  shift
  if output="$("$@" 2>&1)"; then
    printf '%s\n' "$output" >&2
    fail "$name unexpectedly succeeded"
  fi
  LAST_OUTPUT="$output"
}

create_base_sdk() {
  local sdk="$1"
  mkdir -p "$sdk/platforms/android-36" "$sdk/build-tools/36.0.0"
  cat > "$sdk/build-tools/36.0.0/aapt" <<'FAKE_AAPT'
#!/usr/bin/env bash
if [[ "$*" == *"dump badging"* ]]; then
  printf "package: name='com.bytedance.zgx.solin' versionCode='%s' versionName='%s'\n" \
    "${FAKE_PACKAGE_VERSION_CODE:-1}" \
    "${FAKE_PACKAGE_VERSION_NAME:-0.1.0}"
fi
exit 0
FAKE_AAPT
  chmod +x "$sdk/build-tools/36.0.0/aapt"
}

create_fake_adb() {
  local sdk="$1"
  mkdir -p "$sdk/platform-tools"
  cat > "$sdk/platform-tools/adb" <<'FAKE_ADB'
#!/usr/bin/env bash
set -euo pipefail

printf '%s\n' "$*" >> "${FAKE_ADB_LOG:?}"

if [[ "${1:-}" == "devices" ]]; then
  echo "List of devices attached"
  if [[ -n "${FAKE_ADB_DEVICES:-}" ]]; then
    printf '%s\n' "$FAKE_ADB_DEVICES"
  fi
  exit 0
fi

if [[ "${1:-}" == "-s" ]]; then
  FAKE_ADB_SELECTED_SERIAL="${2:-}"
  shift 2
fi

case "${1:-}" in
  shell)
    shift
    case "$*" in
      "getprop ro.product.cpu.abilist64")
        echo "${FAKE_ABI_LIST:-arm64-v8a,armeabi-v7a}"
        ;;
      "getprop ro.product.cpu.abi")
        echo "${FAKE_PRODUCT_ABI:-arm64-v8a}"
        ;;
      "getprop ro.product.model")
        echo "${FAKE_DEVICE_MODEL:-Pixel Test}"
        ;;
      "getprop ro.build.version.sdk")
        echo "36"
        ;;
      "getprop sys.boot_completed")
        echo "${FAKE_BOOT_COMPLETED:-1}"
        ;;
      "getprop init.svc.bootanim")
        echo "${FAKE_BOOTANIM:-running}"
        ;;
      "wm size")
        echo "Physical size: ${FAKE_WM_SIZE:-1080x2400}"
        ;;
      "df -k /data")
        printf 'Filesystem 1K-blocks Used Available Use%% Mounted on\n'
        printf '/dev/block 5000000 1000 %s 1%% /data\n' "${FAKE_DATA_FREE_KB:-4000000}"
        ;;
      settings\ get\ global\ stay_on_while_plugged_in)
        echo "${FAKE_STAY_ON_WHILE_PLUGGED_IN:-0}"
        ;;
      settings\ get\ system\ screen_off_timeout)
        echo "${FAKE_SCREEN_OFF_TIMEOUT:-120000}"
        ;;
      settings\ put\ global\ stay_on_while_plugged_in\ *|settings\ put\ system\ screen_off_timeout\ *)
        echo "OK"
        ;;
      settings\ delete\ global\ stay_on_while_plugged_in|settings\ delete\ system\ screen_off_timeout)
        echo "OK"
        ;;
      cmd\ power\ suppress-ambient-display\ *|cmd\ power\ wakeup\ *|cmd\ statusbar\ collapse)
        echo "OK"
        ;;
      svc\ power\ stayon\ *|wm\ dismiss-keyguard)
        echo "OK"
        ;;
      cmd\ appops\ get\ *|cmd\ appops\ set\ *)
        echo "OK"
        ;;
      am\ instrument\ -w\ -r*)
        if [[ -n "${FAKE_INSTRUMENTATION_SLEEP_SECONDS:-}" ]]; then
          sleep "$FAKE_INSTRUMENTATION_SLEEP_SECONDS"
        fi
        if [[ -n "${FAKE_INSTRUMENTATION_OUTPUT:-}" ]]; then
          printf '%s\n' "$FAKE_INSTRUMENTATION_OUTPUT"
        else
          echo "INSTRUMENTATION_STATUS: numtests=20"
          echo "OK (20 tests)"
        fi
        exit "${FAKE_INSTRUMENTATION_EXIT_CODE:-0}"
        ;;
      am\ start\ -W\ -n*)
        echo "Status: ok"
        if [[ -n "${FAKE_RC_PERF_TOTAL_TIME_MS:-}" ]]; then
          echo "TotalTime: ${FAKE_RC_PERF_TOTAL_TIME_MS}"
        fi
        if [[ -n "${FAKE_RC_PERF_WAIT_TIME_MS:-}" ]]; then
          echo "WaitTime: ${FAKE_RC_PERF_WAIT_TIME_MS}"
        fi
        ;;
      am\ start-foreground-service\ -a\ com.bytedance.zgx.solin.rcperf.RUN\ -n\ com.bytedance.zgx.solin/.rcperf.RcPerfHarnessService*)
        echo "Starting service: Intent { act=com.bytedance.zgx.solin.rcperf.RUN cmp=com.bytedance.zgx.solin/.rcperf.RcPerfHarnessService }"
        ;;
      am\ broadcast\ -a\ com.bytedance.zgx.solin.debug.DEVICE_CONTROL_EVAL*)
        echo "Broadcast completed: result=-1"
        ;;
      am\ force-stop\ *)
        echo "OK"
        ;;
      cmd\ package\ path\ *)
        package_name="${*:4}"
        if [[ ",${FAKE_REAL_APP_SEARCH_INSTALLED_PACKAGES:-}," == *",$package_name,"* ]]; then
          echo "package:/data/app/${package_name}/base.apk"
        else
          exit 1
        fi
        ;;
      cmd\ package\ resolve-activity\ --brief\ *)
        package_name="${*:5}"
        if [[ ",${FAKE_REAL_APP_SEARCH_INSTALLED_PACKAGES:-}," == *",$package_name,"* ]]; then
          echo "${package_name}/.MainActivity"
        else
          exit 1
        fi
        ;;
      dumpsys\ meminfo\ com.bytedance.zgx.solin)
        printf '** MEMINFO in pid 12345 [com.bytedance.zgx.solin] **\n'
        printf '                   Pss  Private  Private\n'
        printf '                 Total    Dirty    Clean\n'
        printf '   TOTAL %s   400000        0\n' "${FAKE_RC_PERF_MEMINFO_KB:-524288}"
        ;;
      dumpsys\ accessibility)
        cat <<'FAKE_ACCESSIBILITY_DUMPSYS'
Bound services:
  ServiceRecord{com.bytedance.zgx.solin/com.bytedance.zgx.solin.device.SolinAccessibilityService}
FAKE_ACCESSIBILITY_DUMPSYS
        if [[ "${FAKE_SOLIN_ACCESSIBILITY_ENABLED:-1}" == "1" ]]; then
          echo "Enabled services: com.bytedance.zgx.solin/com.bytedance.zgx.solin.device.SolinAccessibilityService"
        else
          echo "Enabled services: "
        fi
        echo "Binding services: "
        echo "Crashed services: "
        ;;
      dumpsys\ activity\ activities)
        if [[ "${FAKE_SOLIN_MAIN_SHELL_VISIBLE:-0}" == "1" ]]; then
          cat <<'FAKE_ACTIVITY_DUMPSYS'
topResumedActivity=ActivityRecord{com.bytedance.zgx.solin/.MainActivity}
mCurrentFocus=Window{com.bytedance.zgx.solin/com.bytedance.zgx.solin.MainActivity}
mFocusedWindow=Window{com.bytedance.zgx.solin/com.bytedance.zgx.solin.MainActivity}
FAKE_ACTIVITY_DUMPSYS
        else
          echo "topResumedActivity=ActivityRecord{com.taobao.taobao/.MainActivity}"
        fi
        ;;
      dumpsys\ window|dumpsys\ window\ windows)
        if [[ "${FAKE_SOLIN_MAIN_SHELL_VISIBLE:-0}" == "1" ]]; then
          cat <<'FAKE_SOLIN_WINDOW_DUMPSYS'
mCurrentFocus=Window{com.bytedance.zgx.solin/com.bytedance.zgx.solin.MainActivity}
mFocusedApp=ActivityRecord{com.bytedance.zgx.solin/.MainActivity}
  Window #8 Window{com.bytedance.zgx.solin/com.bytedance.zgx.solin.MainActivity}:
    WindowStateAnimator{com.bytedance.zgx.solin/com.bytedance.zgx.solin.MainActivity}:
      Surface: shown=true      mDrawState=HAS_DRAWN       mLastHidden=false
    isOnScreen=true
    isVisible=true
FAKE_SOLIN_WINDOW_DUMPSYS
        else
          cat <<'FAKE_WINDOW_DUMPSYS'
mCurrentFocus=Window{com.taobao.taobao/com.taobao.taobao.MainActivity}
mFocusedApp=ActivityRecord{com.taobao.taobao/.MainActivity}
mFocusedWindow=Window{com.taobao.taobao/com.taobao.taobao.MainActivity}
FAKE_WINDOW_DUMPSYS
        fi
        ;;
      dumpsys\ package\ com.bytedance.zgx.solin)
        install_count="$(grep -c ' install ' "${FAKE_ADB_LOG:?}" 2>/dev/null || true)"
        if [[ "$install_count" -ge 2 ]]; then
          last_update_time="${FAKE_CURRENT_LAST_UPDATE_TIME:-2026-06-06 20:00:05}"
        else
          last_update_time="${FAKE_BASE_LAST_UPDATE_TIME:-2026-06-06 20:00:00}"
        fi
        cat <<FAKE_PACKAGE_DUMPSYS
Packages:
  Package [com.bytedance.zgx.solin] (abc123):
    firstInstallTime=${FAKE_FIRST_INSTALL_TIME:-2026-06-06 20:00:00}
    lastUpdateTime=$last_update_time
    versionCode=${FAKE_PACKAGE_VERSION_CODE:-1} minSdk=28 targetSdk=36
    versionName=${FAKE_PACKAGE_VERSION_NAME:-0.1.0}
FAKE_PACKAGE_DUMPSYS
        ;;
      pm\ path\ com.bytedance.zgx.solin)
        echo "package:/data/app/com.bytedance.zgx.solin/base.apk"
        ;;
      pm\ path\ com.bytedance.zgx.solin.test)
        echo "package:/data/app/com.bytedance.zgx.solin.test/base.apk"
        ;;
      pm\ path\ com.bytedance.zgx.solin.releasesmoke)
        echo "package:/data/app/com.bytedance.zgx.solin.releasesmoke/base.apk"
        ;;
      run-as\ com.bytedance.zgx.solin\ am\ broadcast*\ -n\ com.bytedance.zgx.solin/.debug.DebugRemoteConfigReceiver*)
        if [[ "$*" == *"--ez clearRemoteConfig true"* ]]; then
          echo "Broadcast completed: result=-1, data=\"remote config cleared\""
        else
          echo "Broadcast completed: result=-1, data=\"remote config saved\""
        fi
        ;;
      run-as\ com.bytedance.zgx.solin\ sh\ -s)
        stdin_script="$(cat || true)"
        if [[ "$stdin_script" == *"com.bytedance.zgx.solin/.debug.DebugRemoteConfigReceiver"* ]]; then
          {
            printf '%s\n' "-s ${FAKE_ADB_SELECTED_SERIAL:-unknown} shell run-as com.bytedance.zgx.solin am broadcast --user 0 -n com.bytedance.zgx.solin/.debug.DebugRemoteConfigReceiver --es baseUrl <redacted> --es modelName <redacted> --es apiKey <redacted> --ez clearState true"
          } >> "${FAKE_ADB_LOG:?}"
          echo "Broadcast completed: result=-1, data=\"remote config saved\""
        else
          echo "unexpected run-as script" >&2
          exit 2
        fi
        ;;
      run-as\ com.bytedance.zgx.solin\ am\ broadcast*\ -n\ com.bytedance.zgx.solin/.debug.DeviceControlEvalReceiver*)
        echo "Broadcast completed: result=-1"
        ;;
      pm\ clear\ com.bytedance.zgx.solin|pm\ clear\ com.bytedance.zgx.solin.test|pm\ clear\ com.bytedance.zgx.solin.releasesmoke)
        echo "Success"
        ;;
      pm\ path\ android)
        echo "package:/system/framework/framework-res.apk"
        ;;
      run-as\ com.bytedance.zgx.solin\ rm\ -f\ files/device_control_eval_result_*.properties)
        echo "OK"
        ;;
      rm\ -f\ /sdcard/solin-eval-*.xml)
        echo "OK"
        ;;
      input\ tap*|input\ text*|input\ keyevent*|input\ swipe*)
        echo "OK"
        ;;
      uiautomator\ dump\ /sdcard/solin-eval-*.xml)
        echo "UI hierchary dumped to: ${*:3}"
        ;;
      uiautomator\ dump\ /sdcard/solin-live-remote.xml)
        echo "UI hierchary dumped to: /sdcard/solin-live-remote.xml"
        ;;
      uiautomator\ dump\ /sdcard/solin-live-remote-before-input.xml)
        echo "UI hierchary dumped to: /sdcard/solin-live-remote-before-input.xml"
        ;;
      uiautomator\ dump\ /sdcard/solin-live-remote-before-send.xml)
        echo "UI hierchary dumped to: /sdcard/solin-live-remote-before-send.xml"
        ;;
      uiautomator\ dump\ /sdcard/solin-live-remote-after-send.xml)
        echo "UI hierchary dumped to: /sdcard/solin-live-remote-after-send.xml"
        ;;
      uiautomator\ dump\ /sdcard/solin-fresh-start.xml)
        echo "UI hierchary dumped to: /sdcard/solin-fresh-start.xml"
        ;;
      uiautomator\ dump\ /sdcard/solin-model-manager.xml)
        echo "UI hierchary dumped to: /sdcard/solin-model-manager.xml"
        ;;
      uiautomator\ dump\ /sdcard/solin-release-*.xml)
        echo "UI hierchary dumped to: ${*:3}"
        ;;
      *)
        echo "unexpected shell command: $*" >&2
        exit 2
        ;;
    esac
    ;;
  emu)
    shift
    case "$*" in
      "avd name")
        echo "test-avd"
        echo "OK"
        ;;
      "kill")
        echo "OK"
        ;;
      *)
        echo "unexpected emulator command: $*" >&2
        exit 2
        ;;
    esac
    ;;
  install|uninstall)
    echo "Success"
    ;;
  exec-out)
    shift
    case "$*" in
      "screencap -p")
        printf 'fake-png\n'
        ;;
      run-as\ com.bytedance.zgx.solin\ cat\ files/device_control_eval_result_*.properties)
        result_path="${*:4}"
        request_id="${result_path#files/device_control_eval_result_}"
        request_id="${request_id%.properties}"
        case_name="${request_id%%-*}"
        package_name="com.example.app"
        case "$case_name" in
          taobao) package_name="com.taobao.taobao" ;;
          model_driven_taobao) package_name="com.taobao.taobao" ;;
          pdd) package_name="com.xunmeng.pinduoduo" ;;
          model_driven_pdd) package_name="com.xunmeng.pinduoduo" ;;
          gaode) package_name="com.autonavi.minimap" ;;
          model_driven_gaode) package_name="com.autonavi.minimap" ;;
          jd) package_name="com.jingdong.app.mall" ;;
          model_driven_jd) package_name="com.jingdong.app.mall" ;;
          chrome) package_name="com.android.chrome" ;;
          model_driven_chrome) package_name="com.android.chrome" ;;
          android_browser) package_name="com.android.browser" ;;
          model_driven_android_browser) package_name="com.android.browser" ;;
          quark) package_name="com.quark.browser" ;;
          model_driven_quark) package_name="com.quark.browser" ;;
          uc) package_name="com.UCMobile" ;;
          model_driven_uc) package_name="com.UCMobile" ;;
        esac
        {
          echo "requestId=$request_id"
          if [[ "$request_id" == model_driven_* ]]; then
            echo "resultType=model_driven_app_search"
            echo "status=Succeeded"
            echo "reason="
            echo "input=打开淘宝搜索海河牛奶"
            echo "finalState=Completed"
            echo "verificationRequired=true"
            echo "verifySearchQuery=海河牛奶"
            echo "expectedPackageName=$package_name"
            echo "expectedAppName=淘宝"
            echo "stepCount=7"
            echo "modelPlannedStepCount=4"
            echo "tools=open_app_by_name,ui_wait,observe_current_screen,ui_tap,ui_type_text,ui_submit_search,ui_wait"
            echo "toolStatuses=Succeeded,Succeeded,Succeeded,Succeeded,Succeeded,Succeeded,Succeeded"
            echo "evalGuardAppliedCount=4"
            echo "modelReplanTraceCount=4"
            echo "modelReplanAttemptCount=4"
            echo "modelReplanAcceptedCount=4"
            echo "modelReplanRejectedCount=0"
            echo "modelReplanAttempted=true"
            echo "modelReplanReason=accepted"
            echo "modelReplanPromptIndex=0"
            echo "modelReplanUsedModel=true"
            echo "modelReplanModelAttempted=true"
            echo "modelReplanModelPlanKind=Draft"
            echo "modelReplanModelFailureReason="
            echo "modelReplanModelOutputPreview=call:ui_wait{}"
            echo "uiActionOutcome=verified"
            echo "uiActionOutcomeReason=search_verified"
            echo "appSearchProgressStage=verified"
            echo "modelReplanTrace_0_attempted=true"
            echo "modelReplanTrace_0_reason=accepted"
            echo "modelReplanTrace_0_promptIndex=0"
            echo "modelReplanTrace_0_usedModel=true"
            echo "modelReplanTrace_0_modelAttempted=true"
            echo "modelReplanTrace_0_modelPlanKind=Draft"
            echo "searchVerificationStatus=verified"
            echo "step_0_tool=open_app_by_name"
            echo "step_0_plannedByModel=false"
            echo "step_0_status=Succeeded"
            echo "step_0_uiActionOutcome=advanced"
            echo "step_0_uiActionOutcomeReason=status_succeeded"
            echo "step_0_appSearchProgressStage=opened"
            echo "step_1_tool=ui_wait"
            echo "step_1_plannedByModel=false"
            echo "step_1_status=Succeeded"
            echo "step_1_expectedPackageName=$package_name"
            echo "step_2_tool=observe_current_screen"
            echo "step_2_plannedByModel=false"
            echo "step_2_status=Succeeded"
            echo "step_2_uiActionOutcome=unknown"
            echo "step_2_uiActionOutcomeReason=unknown"
            echo "step_2_appSearchProgressStage=observed_entry"
            echo "step_3_tool=ui_tap"
            echo "step_3_plannedByModel=true"
            echo "step_3_status=Succeeded"
            echo "step_3_target=搜索入口"
            echo "step_3_expectedPackageName=$package_name"
            echo "step_3_uiActionOutcome=advanced"
            echo "step_3_uiActionOutcomeReason=screen_changed"
            echo "step_3_appSearchProgressStage=input_ready"
            echo "step_3_evalGuardApplied=true"
            echo "step_4_tool=ui_type_text"
            echo "step_4_plannedByModel=true"
            echo "step_4_status=Succeeded"
            echo "step_4_textLength=4"
            echo "step_4_expectedPackageName=$package_name"
            echo "step_4_uiActionOutcome=advanced"
            echo "step_4_uiActionOutcomeReason=type_text_succeeded"
            echo "step_4_appSearchProgressStage=query_typed"
            echo "step_4_evalGuardApplied=true"
            echo "step_5_tool=ui_submit_search"
            echo "step_5_plannedByModel=true"
            echo "step_5_status=Succeeded"
            echo "step_5_expectedPackageName=$package_name"
            echo "step_5_uiActionOutcome=advanced"
            echo "step_5_uiActionOutcomeReason=submit_search_succeeded"
            echo "step_5_appSearchProgressStage=submitted"
            echo "step_5_evalGuardApplied=true"
            echo "step_6_tool=ui_wait"
            echo "step_6_plannedByModel=true"
            echo "step_6_status=Succeeded"
            echo "step_6_verifySearchQuery=海河牛奶"
            echo "step_6_expectedPackageName=$package_name"
            echo "step_6_expectedAppName=淘宝"
            echo "step_6_searchVerificationStatus=verified"
            echo "step_6_uiActionOutcome=verified"
            echo "step_6_uiActionOutcomeReason=search_verified"
            echo "step_6_appSearchProgressStage=verified"
            echo "step_6_evalGuardApplied=true"
          elif [[ "$request_id" == *"-observe-"* ]]; then
            echo "resultType=available"
            echo "packageName=$package_name"
          elif [[ "$request_id" == *"-tap-"* && "${FAKE_REAL_APP_SEARCH_FAIL_STEP:-}" == "tap" ]]; then
            echo "status=Failed"
            echo "actionType=tap"
            echo "failureKind=search_entry_not_found"
            echo "targetResolution.available=true"
            echo "targetResolution.kind=search_entry"
            echo "targetResolution.target=搜索入口"
            echo "targetResolution.packageName=$package_name"
            echo "targetResolution.selectedNodeId="
            echo "targetResolution.failureKind=search_entry_not_found"
            echo "targetResolution.candidateCount=2"
            echo "targetResolution.candidateTotalCount=2"
            echo "targetResolution.archivedCandidateCount=2"
            echo 'targetResolution.candidatesJson={"candidates":[{"nodeId":"top-card","label":"搜索推荐","bounds":{"left":0,"top":96,"right":1080,"bottom":220},"clickable":true,"editable":false,"scrollable":false,"enabled":true,"matchedProfileHint":"搜索","confidence":430,"finalScore":430,"riskPenalty":360,"noisePenalty":0,"totalPenalty":360,"reason":"matched 搜索推荐","score":{"semantic":300,"profileHint":100,"targetText":0,"actionability":120,"position":270,"riskPenalty":360,"noisePenalty":0,"final":430}},{"nodeId":"camera-search","label":"拍照搜索","bounds":{"left":920,"top":96,"right":1012,"bottom":172},"clickable":true,"editable":false,"scrollable":false,"enabled":true,"matchedProfileHint":"搜索","confidence":120,"finalScore":120,"riskPenalty":520,"noisePenalty":0,"totalPenalty":520,"reason":"matched 拍照搜索","score":{"semantic":300,"profileHint":100,"targetText":0,"actionability":120,"position":120,"riskPenalty":520,"noisePenalty":0,"final":120}}]}'
          elif [[ "$request_id" == *"-type-"* && "${FAKE_REAL_APP_SEARCH_FAIL_STEP:-}" == "type" ]]; then
            echo "status=Failed"
            echo "actionType=type_text"
            echo "failureKind=editable_not_found"
            echo "targetResolution.available=true"
            echo "targetResolution.kind=editable_field"
            echo "targetResolution.target=搜索输入框"
            echo "targetResolution.packageName=$package_name"
            echo "targetResolution.selectedNodeId="
            echo "targetResolution.failureKind=editable_not_found"
            echo "targetResolution.candidateCount=2"
            echo "targetResolution.candidateTotalCount=2"
            echo "targetResolution.archivedCandidateCount=2"
            echo 'targetResolution.candidatesJson={"candidates":[{"nodeId":"map-search-entry","label":"你要去哪儿 搜地点、公交、地铁","bounds":{"left":40,"top":96,"right":1040,"bottom":176},"clickable":true,"editable":false,"scrollable":false,"enabled":true,"matchedProfileHint":"你要去哪儿","confidence":360,"finalScore":360,"riskPenalty":0,"noisePenalty":0,"totalPenalty":0,"reason":"matched 你要去哪儿 搜地点、公交、地铁","score":{"semantic":300,"profileHint":400,"targetText":0,"actionability":120,"position":140,"riskPenalty":0,"noisePenalty":0,"final":360}},{"nodeId":"nearby-poi-list","label":"附近 美食 酒店 查看地图 展开列表","bounds":{"left":0,"top":220,"right":1080,"bottom":1900},"clickable":true,"editable":false,"scrollable":true,"enabled":true,"matchedProfileHint":"","confidence":85,"finalScore":85,"riskPenalty":820,"noisePenalty":0,"totalPenalty":820,"reason":"matched 附近 美食 酒店 查看地图 展开列表","score":{"semantic":0,"profileHint":0,"targetText":0,"actionability":200,"position":0,"riskPenalty":820,"noisePenalty":0,"final":85}}]}'
          elif [[ "$request_id" == *"-submit-"* && "${FAKE_REAL_APP_SEARCH_FAIL_STEP:-}" == "submit" ]]; then
            echo "status=Failed"
            echo "actionType=submit_search"
            echo "failureKind=submit_not_found"
            echo "targetResolution.available=true"
            echo "targetResolution.kind=submit_button"
            echo "targetResolution.target=搜索提交"
            echo "targetResolution.packageName=$package_name"
            echo "targetResolution.selectedNodeId="
            echo "targetResolution.failureKind=submit_not_found"
            echo "targetResolution.candidateCount=1"
            echo "targetResolution.candidateTotalCount=1"
            echo "targetResolution.archivedCandidateCount=1"
            echo 'targetResolution.candidatesJson={"candidates":[{"nodeId":"keyboard-action","label":"键盘搜索","bounds":{"left":820,"top":2100,"right":1080,"bottom":2316},"clickable":true,"editable":false,"scrollable":false,"enabled":false,"matchedProfileHint":"搜索","confidence":180,"finalScore":180,"riskPenalty":0,"noisePenalty":80,"totalPenalty":80,"reason":"matched disabled keyboard action","score":{"semantic":300,"profileHint":100,"targetText":0,"actionability":0,"position":120,"riskPenalty":0,"noisePenalty":80,"final":180}}]}'
          elif [[ "$request_id" == *"-tap-"* ]]; then
            echo "status=Succeeded"
            echo "actionType=tap"
            echo "targetResolution.available=true"
            echo "targetResolution.kind=search_entry"
            echo "targetResolution.target=搜索入口"
            echo "targetResolution.packageName=$package_name"
            echo "targetResolution.selectedNodeId=search-entry"
            echo "targetResolution.failureKind="
            echo "targetResolution.candidateCount=1"
            echo "targetResolution.candidateTotalCount=1"
            echo "targetResolution.archivedCandidateCount=1"
            echo 'targetResolution.candidatesJson={"candidates":[{"nodeId":"search-entry","label":"搜索入口","bounds":{"left":40,"top":96,"right":1040,"bottom":176},"clickable":true,"editable":false,"scrollable":false,"enabled":true,"matchedProfileHint":"搜索","confidence":760,"finalScore":760,"riskPenalty":0,"noisePenalty":0,"totalPenalty":0,"reason":"matched search entry","score":{"semantic":680,"profileHint":100,"targetText":0,"actionability":120,"position":140,"riskPenalty":0,"noisePenalty":0,"final":760}}]}'
          elif [[ "$request_id" == *"-type-"* ]]; then
            echo "status=Succeeded"
            echo "actionType=type_text"
            echo "targetResolution.available=true"
            echo "targetResolution.kind=editable_field"
            echo "targetResolution.target=搜索输入框"
            echo "targetResolution.packageName=$package_name"
            echo "targetResolution.selectedNodeId=search-edit-text"
            echo "targetResolution.failureKind="
            echo "targetResolution.candidateCount=1"
            echo "targetResolution.candidateTotalCount=1"
            echo "targetResolution.archivedCandidateCount=1"
            echo 'targetResolution.candidatesJson={"candidates":[{"nodeId":"search-edit-text","label":"搜索输入框","bounds":{"left":40,"top":96,"right":1040,"bottom":176},"clickable":true,"editable":true,"scrollable":false,"enabled":true,"matchedProfileHint":"搜索","confidence":980,"finalScore":980,"riskPenalty":0,"noisePenalty":0,"totalPenalty":0,"reason":"matched editable search field","score":{"semantic":520,"profileHint":100,"targetText":0,"actionability":300,"position":140,"riskPenalty":0,"noisePenalty":0,"final":980}}]}'
          elif [[ "$request_id" == *"-submit-"* ]]; then
            echo "status=Succeeded"
            echo "actionType=submit_search"
            echo "targetResolution.available=true"
            echo "targetResolution.kind=submit_button"
            echo "targetResolution.target=搜索提交"
            echo "targetResolution.packageName=$package_name"
            echo "targetResolution.selectedNodeId=search-submit"
            echo "targetResolution.failureKind="
            echo "targetResolution.candidateCount=1"
            echo "targetResolution.candidateTotalCount=1"
            echo "targetResolution.archivedCandidateCount=1"
            echo 'targetResolution.candidatesJson={"candidates":[{"nodeId":"search-submit","label":"搜索","bounds":{"left":900,"top":96,"right":1060,"bottom":176},"clickable":true,"editable":false,"scrollable":false,"enabled":true,"matchedProfileHint":"搜索","confidence":840,"finalScore":840,"riskPenalty":0,"noisePenalty":0,"totalPenalty":0,"reason":"matched submit search button","score":{"semantic":700,"profileHint":100,"targetText":0,"actionability":120,"position":120,"riskPenalty":0,"noisePenalty":0,"final":840}}]}'
          elif [[ "$request_id" == *"-verify-"* && "${FAKE_REAL_APP_SEARCH_FAIL_STEP:-}" == "verify" ]]; then
            echo "status=Failed"
            echo "failureKind=result_not_verified"
            echo "searchVerificationStatus=not_verified"
            echo "searchVerificationEvidence=query_missing"
          elif [[ "$request_id" == *"-verify-"* && "${FAKE_REAL_APP_SEARCH_FAIL_STEP:-}" == "required_hint" ]]; then
            echo "status=Succeeded"
            echo "searchVerificationStatus=verified"
            echo "searchVerificationEvidence=query_visible"
            echo "SolinAgentChrome"
            echo "SolinAgentBrowser"
            echo "SolinAgentQuark"
            echo "SolinAgentUC"
          elif [[ "$request_id" == *"-verify-"* ]]; then
            echo "status=Succeeded"
            echo "searchVerificationStatus=verified"
            echo "searchVerificationEvidence=query_visible"
            echo "SolinAgentChrome"
            echo "SolinAgentBrowser"
            echo "SolinAgentQuark"
            echo "SolinAgentUC"
            echo "筛选"
            echo "查看地图"
            echo "数据线"
          else
            echo "status=Succeeded"
          fi
        }
        ;;
      cat\ /sdcard/solin-eval-*.xml)
        cat <<'FAKE_REAL_APP_UI'
<hierarchy>
  <node text="搜索商品" enabled="true" clickable="true" bounds="[16,72][1064,152]" />
  <node text="扫一扫" enabled="true" clickable="true" bounds="[880,72][1064,152]" />
</hierarchy>
FAKE_REAL_APP_UI
        ;;
      cat\ /sdcard/Android/data/com.bytedance.zgx.solin/files/rc_perf_result_*.properties)
        # The in-app RC perf harness only writes its result once it has finished a real local
        # model run. Until FAKE_RC_PERF_RESULT_READY is set we report nothing so the collector
        # exercises its polling loop and timeout path.
        if [[ -z "${FAKE_RC_PERF_RESULT_READY:-}" ]]; then
          exit 1
        fi
        echo "rcPerfSchema=RcPerfResult/v1"
        if [[ "${FAKE_RC_PERF_RESULT_TYPE:-success}" != "success" ]]; then
          echo "resultType=${FAKE_RC_PERF_RESULT_TYPE:-failed}"
          echo "reason=${FAKE_RC_PERF_REASON:-benchmark-unavailable}"
        else
          echo "resultType=success"
          echo "modelId=${FAKE_RC_PERF_MODEL_ID:-chat-e2b}"
          echo "backend=${FAKE_RC_PERF_BACKEND:-GPU}"
          echo "modelLoadMs=${FAKE_RC_PERF_MODEL_LOAD_MS:-3500}"
          echo "firstTokenMs=${FAKE_RC_PERF_FIRST_TOKEN_MS:-900}"
          echo "tokenCount=${FAKE_RC_PERF_TOKEN_COUNT:-128}"
          echo "tokensPerSecond=${FAKE_RC_PERF_TOKENS_PER_SECOND:-12.5}"
          echo "stopGenerationRecoveryMs=${FAKE_RC_PERF_STOP_RECOVERY_MS:-200}"
          echo "gpuFallbackStatus=${FAKE_RC_PERF_GPU_FALLBACK_STATUS:-not-needed}"
          echo "visionInputMs=${FAKE_RC_PERF_VISION_INPUT_MS:-500}"
          echo "memorySearch5kMs=${FAKE_RC_PERF_MEMORY_SEARCH_5K_MS:-25}"
          echo "zvecMemoryIndex50kMs=${FAKE_RC_PERF_ZVEC_MEMORY_INDEX_50K_MS:-1500}"
          echo "zvecMemorySearch50kMs=${FAKE_RC_PERF_ZVEC_MEMORY_SEARCH_50K_MS:-35}"
        fi
        ;;
      cat\ /sdcard/solin-release-main-shell.xml)
        printf '<hierarchy><node text="Solin"/></hierarchy>\n'
        ;;
      *)
        echo "unexpected exec-out command: $*" >&2
        exit 2
        ;;
    esac
    ;;
  pull)
    source="${2:-}"
    destination="${3:-}"
    if [[ -z "$destination" ]]; then
      echo "unexpected pull command: $*" >&2
      exit 2
    fi
    mkdir -p "$(dirname "$destination")"
    case "$source" in
      /sdcard/solin-live-remote.xml)
        printf '<hierarchy><node text="%s" /></hierarchy>\n' "${FAKE_LIVE_REMOTE_UI_TEXT:-${SOLIN_LIVE_REMOTE_EXPECTED_TEXT:-SOLIN_LIVE_OK}}" > "$destination"
        ;;
      /sdcard/solin-live-remote-before-input.xml)
        cat > "$destination" <<'FAKE_LIVE_REMOTE_INPUT_UI'
<hierarchy>
  <node class="android.widget.EditText" enabled="true" clickable="true" bounds="[169,2103][621,2313]" />
  <node class="android.widget.Button" content-desc="发送" enabled="false" clickable="false" bounds="[911,2176][1048,2313]" />
</hierarchy>
FAKE_LIVE_REMOTE_INPUT_UI
        ;;
      /sdcard/solin-live-remote-before-send.xml)
        cat > "$destination" <<'FAKE_LIVE_REMOTE_SEND_UI'
<hierarchy>
  <node class="android.widget.EditText" enabled="true" clickable="true" bounds="[169,2103][621,2313]" />
  <node class="android.widget.Button" content-desc="发送" enabled="true" clickable="true" bounds="[911,2176][1048,2313]" />
</hierarchy>
FAKE_LIVE_REMOTE_SEND_UI
        ;;
      /sdcard/solin-live-remote-after-send.xml)
        cat > "$destination" <<'FAKE_LIVE_REMOTE_CONFIRM_UI'
<hierarchy>
  <node text="即将发送到远程模型" enabled="true" clickable="false" bounds="[80,900][980,980]" />
  <node text="远程地址：remote.example.test" enabled="true" clickable="false" bounds="[80,1000][980,1080]" />
  <node text="模型：validation-model" enabled="true" clickable="false" bounds="[80,1100][980,1180]" />
  <node class="android.view.View" enabled="true" clickable="true" bounds="[80,1850][980,1970]">
    <node text="确认发送" enabled="true" clickable="false" bounds="[420,1880][660,1930]" />
  </node>
</hierarchy>
FAKE_LIVE_REMOTE_CONFIRM_UI
        ;;
      /sdcard/solin-fresh-start.xml)
        if [[ "${FAKE_FRESH_START_SHOW_FIRST_RUN_ONCE:-0}" == "1" || "${FAKE_FRESH_START_SHOW_STUCK_FIRST_RUN:-0}" == "1" ]]; then
          fresh_start_tap_count="$(grep -c 'input tap' "${FAKE_ADB_LOG:?}" 2>/dev/null || true)"
          if [[ "${FAKE_FRESH_START_SHOW_STUCK_FIRST_RUN:-0}" == "1" || "$fresh_start_tap_count" == "0" ]]; then
            cat > "$destination" <<'FAKE_FRESH_START_FIRST_RUN_UI'
<hierarchy>
  <node text="Solin" enabled="true" clickable="false" bounds="[76,150][303,223]" />
  <node text="隐私优先的随身 AI 助手" enabled="true" clickable="false" bounds="[76,226][303,275]" />
  <node text="离线基础问答可选下载" enabled="true" clickable="false" bounds="[94,600][778,691]" />
  <node text="先跳过" enabled="true" clickable="true" bounds="[80,1800][500,1900]" />
</hierarchy>
FAKE_FRESH_START_FIRST_RUN_UI
          else
            cat > "$destination" <<'FAKE_FRESH_START_AFTER_SKIP_UI'
<hierarchy>
  <node text="Solin" enabled="true" clickable="false" bounds="[76,150][303,223]" />
  <node text="隐私优先的随身 AI 助手" enabled="true" clickable="false" bounds="[76,226][303,275]" />
  <node content-desc="模型管理" enabled="true" clickable="true" bounds="[471,149][597,275]" />
  <node content-desc="更多" enabled="true" clickable="true" bounds="[924,149][1004,275]" />
  <node text="为什么装它" enabled="true" clickable="false" bounds="[94,600][778,691]" />
</hierarchy>
FAKE_FRESH_START_AFTER_SKIP_UI
          fi
        elif [[ -n "${FAKE_FRESH_START_UI_TEXT:-}" ]]; then
          printf '<hierarchy><node text="%s" /></hierarchy>\n' "$FAKE_FRESH_START_UI_TEXT" > "$destination"
        else
          cat > "$destination" <<'FAKE_FRESH_START_UI'
<hierarchy>
  <node text="Solin" enabled="true" clickable="false" bounds="[76,150][303,223]" />
  <node text="隐私优先的随身 AI 助手" enabled="true" clickable="false" bounds="[76,226][303,275]" />
  <node content-desc="模型管理" enabled="true" clickable="true" bounds="[471,149][597,275]" />
  <node content-desc="更多" enabled="true" clickable="true" bounds="[924,149][1004,275]" />
  <node text="为什么装它" enabled="true" clickable="false" bounds="[94,600][778,691]" />
</hierarchy>
FAKE_FRESH_START_UI
        fi
        ;;
      /sdcard/solin-model-manager.xml)
        printf '<hierarchy><node text="%s" /></hierarchy>\n' "${FAKE_MODEL_MANAGER_UI_TEXT:-可下载或导入本地模型离线使用；远程多模态可选。切换远程会提醒，设备动作仍会先确认。}" > "$destination"
        ;;
      /sdcard/solin-release-*.xml)
        cat > "$destination" <<'FAKE_RELEASE_SCREENSHOT_UI'
<hierarchy>
  <node text="Solin" bounds="[80,80][360,140]" />
  <node text="隐私优先的随身 AI 助手" bounds="[80,145][640,205]" />
  <node text="为什么装它" bounds="[80,460][780,560]" />
  <node text="模型管理" content-desc="模型管理" bounds="[760,80][980,180]" />
  <node text="当前模型" bounds="[80,260][420,340]" />
  <node text="本地可用" bounds="[80,360][420,430]" />
  <node text="远程多模态可选" bounds="[80,440][620,510]" />
  <node text="后台任务" content-desc="后台任务" bounds="[520,80][740,180]" />
  <node text="输入问题" bounds="[120,2100][780,2220]" />
  <node content-desc="发送" bounds="[920,2100][1020,2220]" />
  <node content-desc="关闭模型管理" bounds="[920,120][1010,210]" />
  <node text="即将发送到远程模型" bounds="[80,1180][900,1260]" />
  <node text="确认后才会把本次内容交给远程模型" bounds="[80,1850][980,1970]" />
  <node text="取消" bounds="[80,1990][980,2110]" />
  <node text="暂无运行中的后台任务" bounds="[80,720][980,800]" />
  <node text="最近审计日志" bounds="[80,1420][980,1500]" />
  <node text="最近 Agent 轨迹" bounds="[80,1660][980,1740]" />
</hierarchy>
FAKE_RELEASE_SCREENSHOT_UI
        ;;
      /data/app/com.bytedance.zgx.solin/base.apk)
        cp "${FAKE_INSTALLED_APP_APK_SOURCE:?}" "$destination"
        ;;
      /data/app/com.bytedance.zgx.solin.test/base.apk)
        cp "${FAKE_INSTALLED_TEST_APK_SOURCE:?}" "$destination"
        ;;
      /data/app/com.bytedance.zgx.solin.releasesmoke/base.apk)
        cp "${FAKE_INSTALLED_TEST_APK_SOURCE:?}" "$destination"
        ;;
      *)
        echo "unexpected pull command: $*" >&2
        exit 2
        ;;
    esac
    echo "1 file pulled"
    ;;
  logcat)
    shift
    if [[ "${1:-}" == "-c" ]]; then
      exit 0
    fi
    if [[ "${1:-}" == "-d" ]]; then
      if [[ -n "${FAKE_RC_PERF_LOGCAT_OOM_ANR:-}" ]]; then
        printf '%s\n' "$FAKE_RC_PERF_LOGCAT_OOM_ANR"
      else
        echo "fake live remote logcat"
      fi
      exit 0
    fi
    echo "fake live remote logcat"
    ;;
  *)
    echo "unexpected adb command: $*" >&2
    exit 2
    ;;
esac
FAKE_ADB
  chmod +x "$sdk/platform-tools/adb"
}

create_fake_emulator() {
  local sdk="$1"
  mkdir -p "$sdk/emulator"
  cat > "$sdk/emulator/emulator" <<'FAKE_EMULATOR'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == "-list-avds" ]]; then
  if [[ -n "${FAKE_EMULATOR_AVDS+x}" ]]; then
    printf '%s\n' "$FAKE_EMULATOR_AVDS"
  else
    echo "test-avd"
  fi
  exit 0
fi
printf '%s\n' "$*" >> "${FAKE_EMULATOR_LOG:?}"
if [[ -n "${FAKE_EMULATOR_OUTPUT:-}" ]]; then
  printf '%s\n' "$FAKE_EMULATOR_OUTPUT"
fi
FAKE_EMULATOR
  chmod +x "$sdk/emulator/emulator"
}

create_fake_apksigner() {
  local sdk="$1"
  cat > "$sdk/build-tools/36.0.0/apksigner" <<'FAKE_APKSIGNER'
#!/usr/bin/env bash
set -euo pipefail
if [[ "$*" == *"--print-certs"* ]]; then
  printf 'Signer #1 certificate SHA-256 digest: %s\n' "${FAKE_APK_SIGNER_SHA256:-1111111111111111111111111111111111111111111111111111111111111111}"
  exit 0
fi
echo "Verified"
FAKE_APKSIGNER
  chmod +x "$sdk/build-tools/36.0.0/apksigner"
}

create_fake_sdkmanager() {
  local path="$1"
  cat > "$path" <<'FAKE_SDKMANAGER'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "${FAKE_SDKMANAGER_LOG:-/dev/null}"
if [[ "$*" == *"--list_installed"* ]]; then
  printf '%s\n' "${FAKE_SDKMANAGER_INSTALLED:-}"
  exit 0
fi
echo "install complete"
FAKE_SDKMANAGER
  chmod +x "$path"
}

create_fake_avdmanager() {
  local path="$1"
  cat > "$path" <<'FAKE_AVDMANAGER'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "${FAKE_AVDMANAGER_LOG:-/dev/null}"
case "$*" in
  create\ avd\ *)
    echo "AVD created"
    ;;
  *)
    echo "unexpected avdmanager command: $*" >&2
    exit 2
    ;;
esac
FAKE_AVDMANAGER
  chmod +x "$path"
}

create_fake_gradle() {
  local path="$1"
  cat > "$path" <<'FAKE_GRADLE'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "${FAKE_GRADLE_LOG:?}"
if [[ "$*" == *":app:assembleDebug"* || "$*" == *" assembleDebug"* ]]; then
  mkdir -p app/build/outputs/apk/debug
  printf 'fake debug apk\n' > app/build/outputs/apk/debug/app-debug.apk
fi
if [[ "$*" == *":app:assembleDebugAndroidTest"* || "$*" == *" assembleDebugAndroidTest"* ]]; then
  mkdir -p app/build/outputs/apk/androidTest/debug
  printf 'fake androidTest apk\n' > app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
fi
if [[ -n "${AI_BEHAVIOR_ACTUAL_TRACE_FILE:-}" && -n "${FAKE_AI_BEHAVIOR_ACTUAL_TRACE_SOURCE:-}" ]]; then
  mkdir -p "$(dirname "$AI_BEHAVIOR_ACTUAL_TRACE_FILE")"
  cp "$FAKE_AI_BEHAVIOR_ACTUAL_TRACE_SOURCE" "$AI_BEHAVIOR_ACTUAL_TRACE_FILE"
fi
FAKE_GRADLE
  chmod +x "$path"
}

create_fake_matrix_regression() {
  local path="$1"
  cat > "$path" <<'FAKE_MATRIX_REGRESSION'
#!/usr/bin/env bash
set -euo pipefail
api="${AVD_NAME#api}"
api="${api%%_*}"
report="${REGRESSION_REPORT_FILE:?}"
mkdir -p "$(dirname "$report")"
if [[ "$api" == "${FAKE_MATRIX_FAIL_API:-}" ]]; then
  {
    echo "status=failed"
    echo "target=regression-emulator"
    echo "reason=regression-failed"
    echo "api_level=$api"
    echo "abi=arm64-v8a"
    echo "avd=$AVD_NAME"
    echo "actual_android_test_count=${FAKE_MATRIX_TEST_COUNT:-1}"
  } > "$report"
  exit 1
fi
if [[ "$api" == "${FAKE_MATRIX_SKIP_API:-}" ]]; then
  {
    echo "status=skipped"
    echo "target=regression-emulator"
    echo "reason=emulator-infra-hvf-unsupported"
    echo "api_level=$api"
    echo "abi=arm64-v8a"
    echo "avd=$AVD_NAME"
    echo "actual_android_test_count=0"
  } > "$report"
  exit 0
fi
{
  echo "status=passed"
  echo "target=regression-emulator"
  echo "reason="
  echo "serial=emulator-$api"
  echo "api_level=$api"
  echo "abi=arm64-v8a"
  echo "avd=$AVD_NAME"
  echo "actual_android_test_count=${FAKE_MATRIX_TEST_COUNT:-1}"
} > "$report"
FAKE_MATRIX_REGRESSION
  chmod +x "$path"
}

assert_no_gradle_call() {
  if [[ -s "$FAKE_GRADLE_LOG" ]]; then
    cat "$FAKE_GRADLE_LOG" >&2
    fail "Gradle should not be called before device preflight succeeds"
  fi
}

assert_gradle_called() {
  grep -q ":app:assembleDebug :app:assembleDebugAndroidTest" "$FAKE_GRADLE_LOG" ||
    fail "Expected install helper to assemble debug and androidTest APKs"
}

assert_report_contains() {
  local file="$1"
  local expected="$2"
  if [[ ! -f "$file" ]]; then
    {
      printf 'Missing expected report: %s\n' "$file"
      printf 'Last helper output:\n%s\n' "${LAST_OUTPUT:-}"
      printf 'Report directory listing:\n'
      ls -la "$(dirname "$file")" 2>&1 || true
    } >&2
    fail "Expected verification report at $file"
  fi
  grep -qxF "$expected" "$file" ||
    fail "Expected $file to contain: $expected"
}

assert_report_contains_text() {
  assert_file_contains_text "$@"
}

assert_file_contains_text() {
  local file="$1"
  local expected="$2"
  [[ -f "$file" ]] || fail "Expected file at $file"
  grep -qF -- "$expected" "$file" ||
    fail "Expected $file to contain text: $expected"
}

assert_file_matches() {
  local file="$1"
  local expected_pattern="$2"
  [[ -f "$file" ]] || fail "Expected file at $file"
  grep -Eq -- "$expected_pattern" "$file" ||
    fail "Expected $file to match pattern: $expected_pattern"
}

assert_text_contains() {
  local label="$1"
  local text="$2"
  local expected="$3"
  grep -qF -- "$expected" <<<"$text" ||
    fail "Expected $label to contain text: $expected"
}

assert_real_app_case_diagnostics() {
  local case_report="$1"
  local diagnostics_dir="$2"
  assert_report_contains "$case_report" "diagnostics_dir=$diagnostics_dir"
  assert_report_contains "$case_report" "screenshot_file=$diagnostics_dir/screenshot.png"
  assert_report_contains "$case_report" "uiautomator_dump_file=$diagnostics_dir/uiautomator.xml"
  assert_report_contains "$case_report" "focused_window_file=$diagnostics_dir/focused-window.txt"
  assert_report_contains "$case_report" "window_dump_file=$diagnostics_dir/window-dump.txt"
  assert_report_contains "$case_report" "case_logcat_file=$diagnostics_dir/logcat.txt"
  for key in screenshot_sha256 uiautomator_dump_sha256 focused_window_sha256 window_dump_sha256 case_logcat_sha256; do
    grep -Eq "^${key}=[0-9a-f]{64}$" "$case_report" ||
      fail "Expected real app case report to include $key"
  done
  [[ -s "$diagnostics_dir/uiautomator.xml" ]] ||
    fail "Expected real app failure diagnostics to preserve a UIAutomator dump"
  [[ -s "$diagnostics_dir/screenshot.png" ]] ||
    fail "Expected real app failure diagnostics to preserve a screenshot"
  [[ -s "$diagnostics_dir/window-dump.txt" ]] ||
    fail "Expected real app failure diagnostics to preserve a window dump"
  [[ -s "$diagnostics_dir/logcat.txt" ]] ||
    fail "Expected real app failure diagnostics to preserve logcat"
}

assert_release_verifier_report_schema() {
  local file="$1"
  local schema="$2"
  local owner="${3:-release-engineering}"
  [[ -f "$file" ]] || fail "Expected release verifier report at $file"
  assert_report_contains "$file" "artifactSchema=$schema"
  assert_report_contains "$file" "owner=$owner"
  assert_report_contains "$file" "reproduciblePath=$file"
  grep -Eq '^command=.+$' "$file" ||
    fail "Expected command in $file"
  grep -Eq '^recordedAt=20[0-9]{2}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$' "$file" ||
    fail "Expected UTC recordedAt in $file"
}

assert_report_command_argv() {
  local file="$1"
  shift
  [[ -f "$file" ]] || fail "Expected report at $file"

  local command
  command="$(awk -F= '$1 == "command" {sub(/^[^=]*=/, ""); print; exit}' "$file")"
  [[ -n "$command" ]] || fail "Expected command in $file"

  local actual_file
  actual_file="$TMP_DIR/command-argv-$(basename "$file").txt"
  COMMAND_TO_PARSE="$command" bash -c 'eval "set -- $COMMAND_TO_PARSE"; printf "%s\n" "$@"' > "$actual_file"

  local expected_count="$#"
  local actual_count
  actual_count="$(wc -l < "$actual_file" | tr -d ' ')"
  [[ "$actual_count" == "$expected_count" ]] ||
    fail "Expected $file command argv count $expected_count, got $actual_count: $command"

  local expected
  local index=0
  while [[ $# -gt 0 ]]; do
    expected="$1"
    shift
    index=$((index + 1))
    sed -n "${index}p" "$actual_file" | grep -qxF -- "$expected" ||
      fail "Expected $file command argv[$index] to be: $expected"
  done
}

assert_release_verifier_passed_report() {
  local file="$1"
  local schema="$2"
  local owner="${3:-release-engineering}"
  assert_release_verifier_report_schema "$file" "$schema" "$owner"
  assert_report_contains "$file" "status=passed"
  assert_report_contains "$file" "failedTarget="
  assert_report_contains "$file" "reason=approved"
}

assert_release_verifier_failed_report() {
  local file="$1"
  local schema="$2"
  local owner="${3:-release-engineering}"
  assert_release_verifier_report_schema "$file" "$schema" "$owner"
  assert_report_contains "$file" "status=failed"
  grep -Eq '^reason=.+$' "$file" ||
    fail "Expected non-empty failure reason in $file"
}

assert_release_gate_report_schema() {
  local file="$1"
  assert_release_verifier_report_schema "$file" "ReleaseGateVerification/v1"
  grep -Eq '^headCommitSha=[0-9a-f]{40}$' "$file" ||
    fail "Expected release gate report to include current git head SHA"
  grep -Eq '^releaseRecordFile=' "$file" ||
    fail "Expected release gate report to include releaseRecordFile"
  grep -Eq '^releaseArtifactSha256=' "$file" ||
    fail "Expected release gate report to include releaseArtifactSha256"
}

assert_release_gate_child_report_schema() {
  local file="$1"
  local expected_status="$2"
  local expected_target="$3"
  assert_release_verifier_report_schema "$file" "ReleaseGateChildReport/v1"
  assert_report_contains "$file" "status=$expected_status"
  assert_report_contains "$file" "target=$expected_target"
  grep -Eq '^releaseGateReport=' "$file" ||
    fail "Expected release gate child report to include releaseGateReport"
  grep -Eq '^releaseRecordFile=' "$file" ||
    fail "Expected release gate child report to include releaseRecordFile"
  grep -Eq '^releaseArtifactSha256=' "$file" ||
    fail "Expected release gate child report to include releaseArtifactSha256"
}

assert_release_gate_child_report_bound() {
  local gate_report="$1"
  local child_key="$2"
  local child_report="$3"
  local expected_status="$4"
  assert_report_contains "$gate_report" "${child_key}ReportPath=$child_report"
  assert_report_contains "$gate_report" "${child_key}ReportStatus=$expected_status"
  grep -Eq "^${child_key}ReportSha256=[0-9a-f]{64}$" "$gate_report" ||
    fail "Expected release gate report to bind $child_key report SHA"
}

assert_release_gate_child_report_not_produced() {
  local gate_report="$1"
  local child_key="$2"
  local child_report="$3"
  assert_report_contains "$gate_report" "${child_key}ReportPath=$child_report"
  assert_report_contains "$gate_report" "${child_key}ReportStatus=not-produced"
  assert_report_contains "$gate_report" "${child_key}ReportSha256="
}

assert_release_gate_children_not_produced() {
  local gate_report="$1"
  local report_dir="$2"
  shift 2
  local child_key
  local child_report_name
  for child_binding in "$@"; do
    child_key="${child_binding%%:*}"
    child_report_name="${child_binding#*:}"
    assert_release_gate_child_report_not_produced \
      "$gate_report" \
      "$child_key" \
      "$report_dir/$child_report_name"
  done
}

write_model_release_flow_contract_fixture() {
  local flow="$1"
  case "$flow" in
    firstInstall)
      printf 'firstRunSetupVisibleCovered=true\n'
      printf 'firstRunDefaultChatModelSelected=true\n'
      printf 'firstRunSkipReachesMainShell=true\n'
      ;;
    upgradeInstall)
      printf 'upgradeInstallUsesAdbInstallR=true\n'
      printf 'upgradeInstallPreservesFirstInstallTime=true\n'
      printf 'upgradeInstallUpdatesLastUpdateTime=true\n'
      printf 'upgradeInstallVersionCodeIncreased=true\n'
      printf 'upgradeInstallInstrumentationCovered=true\n'
      ;;
    remoteHttpsConfiguration)
      printf 'remoteNetworkFailureRecoveryCovered=true\n'
      printf 'remoteUnconfiguredModelFailureCovered=true\n'
      printf 'remoteLocalMemoryNotAutoIncluded=true\n'
      printf 'remoteMemoryContextIncluded=false\n'
      printf 'remoteMemoryHitCount=0\n'
      printf 'remoteSemanticMemoryHitCount=0\n'
      printf 'remoteLexicalMemoryHitCount=0\n'
      printf 'remoteDeviceContextIncluded=false\n'
      printf 'remoteRawContentPersisted=false\n'
      printf 'remoteProtectedMemoryDeclared=true\n'
      printf 'remoteProtectedDeviceContextDeclared=true\n'
      ;;
    encryptedApiKeyClear)
      printf 'encryptedApiKeyBlankInputClearsSecret=true\n'
      printf 'legacyPlaintextApiKeyNotPersisted=true\n'
      ;;
    sessionPersistence)
      printf 'sessionCreateSwitchRestoreCovered=true\n'
      printf 'activeSessionPersistenceCovered=true\n'
      printf 'sessionDeleteCovered=true\n'
      ;;
    memoryControls)
      printf 'memoryCreateControlCovered=true\n'
      printf 'memoryForgetControlCovered=true\n'
      printf 'memoryClearControlCovered=true\n'
      printf 'memoryPanelControlCovered=true\n'
      ;;
    localModelDownloadVerification)
      printf 'localModelDownloadVerified=true\n'
      printf 'modelSha256VerificationCovered=true\n'
      printf 'storagePreflightCovered=true\n'
      printf 'downloadFailureRecoveryCovered=true\n'
      printf 'downloadDirectoryUnavailableCovered=true\n'
      printf 'downloadShaFailureCleanupCovered=true\n'
      printf 'downloadInsufficientStorageFailureCovered=true\n'
      printf 'pendingDownloadMissingTaskRecoveryCovered=true\n'
      printf 'remoteFallbackExplained=true\n'
      printf 'lightweightAlternativeExplained=true\n'
      ;;
    customModelImportOrUrlRejection)
      printf 'customLitertlmImportCovered=true\n'
      printf 'customLocalNonLitertlmImportRejected=true\n'
      printf 'customImportStoragePreflightCovered=true\n'
      printf 'customImportEmptyFileRejected=true\n'
      printf 'customImportTempCleanupOnCopyFailureCovered=true\n'
      printf 'customDownloadHttpsOnly=true\n'
      printf 'customNonLitertlmDownloadRejected=true\n'
      printf 'customInvalidUrlRejected=true\n'
      printf 'customCredentialedUrlRejected=true\n'
      printf 'customUnverifiedModelMarked=true\n'
      ;;
    shareAndPickerInput)
      printf 'actionSendTextStaged=true\n'
      printf 'remoteTextShareProtected=true\n'
      printf 'remoteVisionImageAttachmentStaged=true\n'
      printf 'remoteVisionUnsupportedProtected=true\n'
      printf 'noImplicitImageOcr=true\n'
      printf 'remoteNonImageAttachmentNotAutoIncluded=true\n'
      printf 'remoteVisionSupportedOpenStreamCountCovered=true\n'
      printf 'remoteVisionSupportedOcrSkipped=true\n'
      printf 'remoteVisionUnsupportedOpenStreamCountCovered=true\n'
      printf 'remoteVisionUnsupportedOcrSkipped=true\n'
      printf 'remoteVisionMixedShareNonImageProtected=true\n'
      printf 'remoteVisionSendPreviewConfirmed=true\n'
      printf 'remoteVisionCancelKeepsRuntimeIdle=true\n'
      printf 'remoteVisionHttpFixtureImagePartCount=1\n'
      printf 'remoteVisionHttpFixtureStreamRequested=true\n'
      printf 'remoteVisionSupportedImageStreamOpenCount=1\n'
      printf 'remoteVisionSupportedImageOcrInvocationCount=0\n'
      printf 'remoteVisionUnsupportedImageStreamOpenCount=0\n'
      printf 'remoteVisionUnsupportedImageOcrInvocationCount=0\n'
      printf 'remoteVisionMixedProtectedNonImageCount=1\n'
      printf 'localVisionVerifiedModelImageAttachmentStaged=true\n'
      printf 'localVisionRuntimeImageAttachmentSent=true\n'
      printf 'localVisionLocalOnlyPersistenceCovered=true\n'
      printf 'localVisionPromptMetadataRedacted=true\n'
      printf 'localVisionRemoteRuntimeIdle=true\n'
      printf 'localVisionUnsupportedOcrSkipped=true\n'
      printf 'localVisionRuntimeImageAttachmentSendCount=1\n'
      printf 'localVisionRemoteRuntimeRequestCount=0\n'
      printf 'localVisionUnsupportedRuntimeImageSendCount=0\n'
      printf 'localVisionUnsupportedImageOcrInvocationCount=0\n'
      printf 'documentExcerptBounded=true\n'
      printf 'pickerAttachmentPromptCovered=true\n'
      ;;
    voiceInput)
      printf 'voiceEntryDisclosureVisible=true\n'
      printf 'voiceDraftNoAutoSendCovered=true\n'
      printf 'voicePermissionFailureRecoveryCovered=true\n'
      printf 'voiceCancelCovered=true\n'
      ;;
    privacyAndDataControls)
      printf 'privacyNoticeEntryVisible=true\n'
      printf 'memoryClearControlCovered=true\n'
      printf 'memoryForgetControlCovered=true\n'
      printf 'sessionDeleteControlCovered=true\n'
      printf 'remoteConfigClearCovered=true\n'
      printf 'dataDeletionCopyCovered=true\n'
      ;;
    remindersAfterReboot)
      printf 'bootCompletedReminderRescheduleCovered=true\n'
      printf 'packageReplacedReminderRescheduleCovered=true\n'
      printf 'reminderCatchUpSchedulingCovered=true\n'
      printf 'staleRunningReminderRecoveryCovered=true\n'
      printf 'reminderAuditMetadataOnly=true\n'
      ;;
    adaptiveUi)
      printf 'largeFontReachabilityCovered=true\n'
      printf 'landscapeReachabilityCovered=true\n'
      printf 'accessibleLabelsCovered=true\n'
      ;;
    accessibilityText)
      printf 'accessibilityTextConfirmationCovered=true\n'
      printf 'accessibilityTextCancellationCovered=true\n'
      printf 'accessibilityTextLocalOnlyMetadataCovered=true\n'
      printf 'accessibilityTextTraceRecorded=true\n'
      ;;
    recentMediaOcr)
      printf 'recentScreenshotOcrRoutingCovered=true\n'
      printf 'recentImageOcrRoutingCovered=true\n'
      printf 'recentMediaOcrConfirmationCovered=true\n'
      printf 'recentScreenshotOneItemLimitCovered=true\n'
      printf 'recentScreenshotMaxCount=1\n'
      printf 'recentImageMaxCount=3\n'
      printf 'recentMediaOcrRawPayloadPersisted=false\n'
      printf 'recentMediaOcrPrivateMetadataRedacted=true\n'
      printf 'recentMediaOcrOcrTextTraceRedacted=true\n'
      printf 'recentMediaOcrLocalOnlyProtected=true\n'
      printf 'recentMediaOcrRemoteLeakageBlocked=true\n'
      ;;
    mediaProjectionCancellation)
      printf 'mediaProjectionOneShotConsentCovered=true\n'
      printf 'currentScreenshotOcrRemoteContinuationBlocked=true\n'
      ;;
  esac
}

write_manual_acceptance_contract_fixture() {
  local key="$1"
  case "$key" in
    modelSetup)
      printf 'modelManagerOpened=true\n'
      printf 'recommendedModelAvailabilityChecked=true\n'
      ;;
    remoteModePrivacy)
      printf 'remoteModeExplicitlySelected=true\n'
      printf 'localMemoryNotAutoIncluded=true\n'
      printf 'remoteRawPrivateContextSent=false\n'
      ;;
    toolConfirmation)
      printf 'confirmationSheetObserved=true\n'
      printf 'toolCancelPreventsExecution=true\n'
      printf 'toolExecutedWithoutConfirmation=false\n'
      ;;
    permissions)
      printf 'runtimePermissionPromptObserved=true\n'
      printf 'permissionDeniedRecoveryCovered=true\n'
      printf 'permissionGrantedSilently=false\n'
      ;;
    backgroundReminders)
      printf 'reminderCreateUpdateCancelObserved=true\n'
      printf 'backgroundReminderDeliveryObserved=true\n'
      ;;
    sharing)
      printf 'shareSheetBoundaryObserved=true\n'
      printf 'externalOutcomeNotAutoClaimed=true\n'
      ;;
    multimodalEntryPoints)
      printf 'localVisionCapabilityObserved=true\n'
      printf 'unsupportedVisionFailClosedObserved=true\n'
      ;;
    voiceInput)
      printf 'systemSpeechRecognizerObserved=true\n'
      printf 'voiceDraftNoAutoSend=true\n'
      printf 'voiceCancelCovered=true\n'
      ;;
    filePicker)
      printf 'systemDocumentPickerObserved=true\n'
      printf 'documentExcerptBounded=true\n'
      printf 'remoteNonImageAttachmentNotAutoIncluded=true\n'
      ;;
    mediaProjection)
      printf 'systemMediaProjectionPromptObserved=true\n'
      printf 'mediaProjectionCancelBlocksCapture=true\n'
      printf 'mediaProjectionOneShotConsentCovered=true\n'
      printf 'screenshotRawPayloadPersisted=false\n'
      ;;
    remoteSinglePublicEvidence)
      printf 'singlePublicEvidenceSelected=true\n'
      printf 'privateEvidenceExcluded=true\n'
      printf 'remoteRequestCount=1\n'
      ;;
    remoteMultiEvidenceComparison)
      printf 'multiplePublicEvidenceCompared=true\n'
      printf 'publicEvidenceCount=2\n'
      printf 'privateEvidenceSent=false\n'
      ;;
    mixedPrivateActionBatchFailClosed)
      printf 'mixedBatchRejected=true\n'
      printf 'partialActionExecution=false\n'
      printf 'remoteRequestCount=0\n'
      ;;
  esac
}

write_validation_record_with_manual_evidence() {
  local source_record="$1"
  local target_record="$2"
  local manual_key="$3"
  local evidence_path="$4"
  python3 - "$source_record" "$target_record" "$manual_key" "$evidence_path" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
manual_key = sys.argv[3]
evidence = Path(sys.argv[4])
record = json.loads(source.read_text())
record["manualAcceptance"][manual_key]["evidencePath"] = str(evidence)
record["manualAcceptance"][manual_key]["evidenceSha256"] = hashlib.sha256(evidence.read_bytes()).hexdigest()
target.write_text(json.dumps(record, indent=2))
PY
}

write_crash_anr_smoke_fixture() {
  local report_file="$1"
  local device_report="$2"
  local instrumentation_output="$3"
  local logcat_file="$4"
  local serial="$5"
  local api_level="$6"
  local abi="$7"
  local operations_record_file="${8:-docs/release_operations_record.json}"

  mkdir -p "$(dirname "$report_file")"
  cat > "$report_file" <<CRASH_ANR_SMOKE_FIXTURE_PROPERTIES
status=passed
target=crash-anr-smoke-evidence
reason=
operationsRecordField=crashAnrSmoke.evidence
operationsRecordFile=$operations_record_file
window=test fixture
track=local-emulator
packageName=com.bytedance.zgx.solin
deviceReportFile=$device_report
deviceReportSha256=$(shasum -a 256 "$device_report" | awk '{print $1}')
deviceReportSizeBytes=$(wc -c < "$device_report" | tr -d ' ')
deviceStatus=passed
instrumentationStatus=passed
instrumentationTestCount=$SOURCE_ANDROID_TEST_COUNT
serial=$serial
apiLevel=$api_level
abi=$abi
instrumentationOutputFile=$instrumentation_output
instrumentationOutputSha256=$(shasum -a 256 "$instrumentation_output" | awk '{print $1}')
instrumentationOutputSizeBytes=$(wc -c < "$instrumentation_output" | tr -d ' ')
logcatFile=$logcat_file
logcatSha256=$(shasum -a 256 "$logcat_file" | awk '{print $1}')
logcatSizeBytes=$(wc -c < "$logcat_file" | tr -d ' ')
logcatAnalyzed=true
instrumentationCrashSignalCount=0
instrumentationFailureSignalCount=0
crashSignalCount=0
installCrashSignalCount=0
anrSignalCount=0
fatalLiteRtLmSignalCount=0
noLaunchCrash=true
noInstallCrash=true
noCrashLoop=true
noFatalNativeLiteRtLmFailure=true
noReproducibleAnr=true
failureEvidencePolicy=Attach logcat, tombstones, and ANR traces for any failure; state no crash or ANR when none were observed.
CRASH_ANR_SMOKE_FIXTURE_PROPERTIES
}

reset_logs() {
  unset ANDROID_SERIAL
  export VERIFICATION_REPORT_FILE="$ARTIFACT_DIR/device-verification.properties"
  export INSTRUMENTATION_OUTPUT_FILE="$ARTIFACT_DIR/instrumentation.txt"
  export LOGCAT_FILE="$ARTIFACT_DIR/logcat.txt"
  : > "$FAKE_ADB_LOG"
  : > "$FAKE_EMULATOR_LOG"
  : > "$FAKE_GRADLE_LOG"
  rm -rf "$ARTIFACT_DIR"
  mkdir -p "$ARTIFACT_DIR"
}

count_android_tests() {
  find app/src/androidTest \( -name '*.kt' -o -name '*.java' \) -print0 |
    xargs -0 awk '/^[[:space:]]*@(org[.]junit[.])?Test([[:space:](]|$)/ {count += 1} END {print count + 0}'
}

NO_ADB_SDK="$TMP_DIR/no-adb-sdk"
NO_EMULATOR_SDK="$TMP_DIR/no-emulator-sdk"
FAKE_SDK="$TMP_DIR/fake-sdk"
FAKE_GRADLE="$TMP_DIR/fake-gradle"
export FAKE_ADB_LOG="$TMP_DIR/fake-adb.log"
export FAKE_EMULATOR_LOG="$TMP_DIR/fake-emulator.log"
export FAKE_GRADLE_LOG="$TMP_DIR/fake-gradle.log"
export ARTIFACT_DIR="$TMP_DIR/verification"

create_base_sdk "$NO_ADB_SDK"
create_base_sdk "$NO_EMULATOR_SDK"
create_fake_adb "$NO_EMULATOR_SDK"
create_base_sdk "$FAKE_SDK"
create_fake_adb "$FAKE_SDK"
create_fake_emulator "$FAKE_SDK"
create_fake_apksigner "$FAKE_SDK"
create_fake_gradle "$FAKE_GRADLE"
reset_logs

SOURCE_ANDROID_TEST_COUNT="$(count_android_tests)"
if [[ "$SOURCE_ANDROID_TEST_COUNT" -le 1 ]]; then
  fail "Expected more than one AndroidTest source method for regression count tests"
fi
LOW_ANDROID_TEST_COUNT=$((SOURCE_ANDROID_TEST_COUNT - 1))
HIGH_ANDROID_TEST_COUNT=$((SOURCE_ANDROID_TEST_COUNT + 1))
SOURCE_INSTRUMENTATION_OUTPUT="$(printf 'INSTRUMENTATION_STATUS: numtests=%s\nOK (%s tests)' "$SOURCE_ANDROID_TEST_COUNT" "$SOURCE_ANDROID_TEST_COUNT")"
LOW_INSTRUMENTATION_OUTPUT="$(printf 'INSTRUMENTATION_STATUS: numtests=%s\nOK (%s tests)' "$LOW_ANDROID_TEST_COUNT" "$LOW_ANDROID_TEST_COUNT")"
HIGH_INSTRUMENTATION_OUTPUT="$(printf 'INSTRUMENTATION_STATUS: numtests=%s\nOK (%s tests)' "$HIGH_ANDROID_TEST_COUNT" "$HIGH_ANDROID_TEST_COUNT")"
