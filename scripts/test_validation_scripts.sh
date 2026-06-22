#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

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
  shift 2
fi

case "${1:-}" in
  shell)
    shift
    case "$*" in
      "getprop ro.product.cpu.abilist64")
        echo "${FAKE_ABI_LIST:-arm64-v8a,armeabi-v7a}"
        ;;
      "getprop ro.build.version.sdk")
        echo "36"
        ;;
      "getprop sys.boot_completed")
        echo "1"
        ;;
      "wm size")
        echo "Physical size: ${FAKE_WM_SIZE:-1080x2400}"
        ;;
      "df -k /data")
        printf 'Filesystem 1K-blocks Used Available Use%% Mounted on\n'
        printf '/dev/block 5000000 1000 %s 1%% /data\n' "${FAKE_DATA_FREE_KB:-4000000}"
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
        ;;
      am\ start\ -W\ -n*)
        echo "Status: ok"
        ;;
      am\ broadcast\ -a\ com.bytedance.zgx.pocketmind.debug.DEVICE_CONTROL_EVAL*)
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
      dumpsys\ accessibility)
        cat <<'FAKE_ACCESSIBILITY_DUMPSYS'
Bound services:
  ServiceRecord{com.bytedance.zgx.pocketmind/com.bytedance.zgx.pocketmind.device.PocketMindAccessibilityService}
Enabled services: com.bytedance.zgx.pocketmind/com.bytedance.zgx.pocketmind.device.PocketMindAccessibilityService
FAKE_ACCESSIBILITY_DUMPSYS
        ;;
      dumpsys\ window|dumpsys\ window\ windows)
        cat <<'FAKE_WINDOW_DUMPSYS'
mCurrentFocus=Window{com.taobao.taobao/com.taobao.taobao.MainActivity}
mFocusedApp=ActivityRecord{com.taobao.taobao/.MainActivity}
mFocusedWindow=Window{com.taobao.taobao/com.taobao.taobao.MainActivity}
FAKE_WINDOW_DUMPSYS
        ;;
      dumpsys\ package\ com.bytedance.zgx.pocketmind)
        install_count="$(grep -c ' install ' "${FAKE_ADB_LOG:?}" 2>/dev/null || true)"
        if [[ "$install_count" -ge 2 ]]; then
          last_update_time="${FAKE_CURRENT_LAST_UPDATE_TIME:-2026-06-06 20:00:05}"
        else
          last_update_time="${FAKE_BASE_LAST_UPDATE_TIME:-2026-06-06 20:00:00}"
        fi
        cat <<FAKE_PACKAGE_DUMPSYS
Packages:
  Package [com.bytedance.zgx.pocketmind] (abc123):
    firstInstallTime=${FAKE_FIRST_INSTALL_TIME:-2026-06-06 20:00:00}
    lastUpdateTime=$last_update_time
    versionCode=${FAKE_PACKAGE_VERSION_CODE:-1} minSdk=28 targetSdk=36
    versionName=${FAKE_PACKAGE_VERSION_NAME:-0.1.0}
FAKE_PACKAGE_DUMPSYS
        ;;
      run-as\ com.bytedance.zgx.pocketmind\ am\ broadcast*\ -n\ com.bytedance.zgx.pocketmind/.debug.DebugRemoteConfigReceiver*)
        if [[ "$*" == *"--ez clearRemoteConfig true"* ]]; then
          echo "Broadcast completed: result=-1, data=\"remote config cleared\""
        else
          echo "Broadcast completed: result=-1, data=\"remote config saved\""
        fi
        ;;
      pm\ clear\ com.bytedance.zgx.pocketmind|pm\ clear\ com.bytedance.zgx.pocketmind.test)
        echo "Success"
        ;;
      run-as\ com.bytedance.zgx.pocketmind\ rm\ -f\ files/device_control_eval_result_*.properties)
        echo "OK"
        ;;
      rm\ -f\ /sdcard/pocketmind-eval-*.xml)
        echo "OK"
        ;;
      input\ tap*|input\ text*|input\ keyevent*|input\ swipe*)
        echo "OK"
        ;;
      uiautomator\ dump\ /sdcard/pocketmind-eval-*.xml)
        echo "UI hierchary dumped to: ${*:3}"
        ;;
      uiautomator\ dump\ /sdcard/pocketmind-live-remote.xml)
        echo "UI hierchary dumped to: /sdcard/pocketmind-live-remote.xml"
        ;;
      uiautomator\ dump\ /sdcard/pocketmind-live-remote-before-input.xml)
        echo "UI hierchary dumped to: /sdcard/pocketmind-live-remote-before-input.xml"
        ;;
      uiautomator\ dump\ /sdcard/pocketmind-live-remote-before-send.xml)
        echo "UI hierchary dumped to: /sdcard/pocketmind-live-remote-before-send.xml"
        ;;
      uiautomator\ dump\ /sdcard/pocketmind-live-remote-after-send.xml)
        echo "UI hierchary dumped to: /sdcard/pocketmind-live-remote-after-send.xml"
        ;;
      uiautomator\ dump\ /sdcard/pocketmind-fresh-start.xml)
        echo "UI hierchary dumped to: /sdcard/pocketmind-fresh-start.xml"
        ;;
      uiautomator\ dump\ /sdcard/pocketmind-model-manager.xml)
        echo "UI hierchary dumped to: /sdcard/pocketmind-model-manager.xml"
        ;;
      uiautomator\ dump\ /sdcard/pocketmind-release-*.xml)
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
      run-as\ com.bytedance.zgx.pocketmind\ cat\ files/device_control_eval_result_*.properties)
        result_path="${*:4}"
        request_id="${result_path#files/device_control_eval_result_}"
        request_id="${request_id%.properties}"
        case_name="${request_id%%-*}"
        package_name="com.example.app"
        case "$case_name" in
          taobao) package_name="com.taobao.taobao" ;;
          pdd) package_name="com.xunmeng.pinduoduo" ;;
          gaode) package_name="com.autonavi.minimap" ;;
          jd) package_name="com.jingdong.app.mall" ;;
          chrome) package_name="com.android.chrome" ;;
          android_browser) package_name="com.android.browser" ;;
          quark) package_name="com.quark.browser" ;;
          uc) package_name="com.UCMobile" ;;
        esac
        {
          echo "requestId=$request_id"
          if [[ "$request_id" == *"-observe-"* ]]; then
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
          elif [[ "$request_id" == *"-verify-"* && "${FAKE_REAL_APP_SEARCH_FAIL_STEP:-}" == "verify" ]]; then
            echo "status=Failed"
            echo "failureKind=result_not_verified"
            echo "searchVerificationStatus=not_verified"
            echo "searchVerificationEvidence=query_missing"
          elif [[ "$request_id" == *"-verify-"* && "${FAKE_REAL_APP_SEARCH_FAIL_STEP:-}" == "required_hint" ]]; then
            echo "status=Succeeded"
            echo "searchVerificationStatus=verified"
            echo "searchVerificationEvidence=query_visible"
            echo "PocketMindAgentChrome"
            echo "PocketMindAgentBrowser"
            echo "PocketMindAgentQuark"
            echo "PocketMindAgentUC"
          elif [[ "$request_id" == *"-verify-"* ]]; then
            echo "status=Succeeded"
            echo "searchVerificationStatus=verified"
            echo "searchVerificationEvidence=query_visible"
            echo "PocketMindAgentChrome"
            echo "PocketMindAgentBrowser"
            echo "PocketMindAgentQuark"
            echo "PocketMindAgentUC"
            echo "筛选"
            echo "查看地图"
          else
            echo "status=Succeeded"
          fi
        }
        ;;
      cat\ /sdcard/pocketmind-eval-*.xml)
        cat <<'FAKE_REAL_APP_UI'
<hierarchy>
  <node text="搜索商品" enabled="true" clickable="true" bounds="[16,72][1064,152]" />
  <node text="扫一扫" enabled="true" clickable="true" bounds="[880,72][1064,152]" />
</hierarchy>
FAKE_REAL_APP_UI
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
      /sdcard/pocketmind-live-remote.xml)
        printf '<hierarchy><node text="%s" /></hierarchy>\n' "${FAKE_LIVE_REMOTE_UI_TEXT:-${POCKETMIND_LIVE_REMOTE_EXPECTED_TEXT:-POCKETMIND_LIVE_OK}}" > "$destination"
        ;;
      /sdcard/pocketmind-live-remote-before-input.xml)
        cat > "$destination" <<'FAKE_LIVE_REMOTE_INPUT_UI'
<hierarchy>
  <node class="android.widget.EditText" enabled="true" clickable="true" bounds="[169,2103][621,2313]" />
  <node class="android.widget.Button" content-desc="发送" enabled="false" clickable="false" bounds="[911,2176][1048,2313]" />
</hierarchy>
FAKE_LIVE_REMOTE_INPUT_UI
        ;;
      /sdcard/pocketmind-live-remote-before-send.xml)
        cat > "$destination" <<'FAKE_LIVE_REMOTE_SEND_UI'
<hierarchy>
  <node class="android.widget.EditText" enabled="true" clickable="true" bounds="[169,2103][621,2313]" />
  <node class="android.widget.Button" content-desc="发送" enabled="true" clickable="true" bounds="[911,2176][1048,2313]" />
</hierarchy>
FAKE_LIVE_REMOTE_SEND_UI
        ;;
      /sdcard/pocketmind-live-remote-after-send.xml)
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
      /sdcard/pocketmind-fresh-start.xml)
        if [[ "${FAKE_FRESH_START_SHOW_FIRST_RUN_ONCE:-0}" == "1" || "${FAKE_FRESH_START_SHOW_STUCK_FIRST_RUN:-0}" == "1" ]]; then
          fresh_start_tap_count="$(grep -c 'input tap' "${FAKE_ADB_LOG:?}" 2>/dev/null || true)"
          if [[ "${FAKE_FRESH_START_SHOW_STUCK_FIRST_RUN:-0}" == "1" || "$fresh_start_tap_count" == "0" ]]; then
            cat > "$destination" <<'FAKE_FRESH_START_FIRST_RUN_UI'
<hierarchy>
  <node text="PocketMind" enabled="true" clickable="false" bounds="[76,150][303,223]" />
  <node text="隐私优先的随身 AI 助手" enabled="true" clickable="false" bounds="[76,226][303,275]" />
  <node text="离线基础问答可选下载" enabled="true" clickable="false" bounds="[94,600][778,691]" />
  <node text="先跳过" enabled="true" clickable="true" bounds="[80,1800][500,1900]" />
</hierarchy>
FAKE_FRESH_START_FIRST_RUN_UI
          else
            cat > "$destination" <<'FAKE_FRESH_START_AFTER_SKIP_UI'
<hierarchy>
  <node text="PocketMind" enabled="true" clickable="false" bounds="[76,150][303,223]" />
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
  <node text="PocketMind" enabled="true" clickable="false" bounds="[76,150][303,223]" />
  <node text="隐私优先的随身 AI 助手" enabled="true" clickable="false" bounds="[76,226][303,275]" />
  <node content-desc="模型管理" enabled="true" clickable="true" bounds="[471,149][597,275]" />
  <node content-desc="更多" enabled="true" clickable="true" bounds="[924,149][1004,275]" />
  <node text="为什么装它" enabled="true" clickable="false" bounds="[94,600][778,691]" />
</hierarchy>
FAKE_FRESH_START_UI
        fi
        ;;
      /sdcard/pocketmind-model-manager.xml)
        printf '<hierarchy><node text="%s" /></hierarchy>\n' "${FAKE_MODEL_MANAGER_UI_TEXT:-本地对话和本地视觉可用，可离线使用；远程多模态可选。远程发送和设备动作仍会先确认。}" > "$destination"
        ;;
      /sdcard/pocketmind-release-*.xml)
        cat > "$destination" <<'FAKE_RELEASE_SCREENSHOT_UI'
<hierarchy>
  <node text="PocketMind" bounds="[80,80][360,140]" />
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
      *)
        echo "unexpected pull command: $*" >&2
        exit 2
        ;;
    esac
    echo "1 file pulled"
    ;;
  logcat)
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
  grep -q "assembleDebug assembleDebugAndroidTest" "$FAKE_GRADLE_LOG" ||
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
  local file="$1"
  local expected="$2"
  [[ -f "$file" ]] || fail "Expected verification report at $file"
  grep -qF "$expected" "$file" ||
    fail "Expected $file to contain text: $expected"
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
      printf 'recentMediaOcrLocalOnlyProtected=true\n'
      printf 'recentMediaOcrRemoteLeakageBlocked=true\n'
      ;;
    mediaProjectionCancellation)
      printf 'mediaProjectionOneShotConsentCovered=true\n'
      printf 'currentScreenshotOcrRemoteContinuationBlocked=true\n'
      ;;
  esac
}

write_crash_anr_smoke_fixture() {
  local report_file="$1"
  local device_report="$2"
  local instrumentation_output="$3"
  local logcat_file="$4"
  local serial="$5"
  local api_level="$6"
  local abi="$7"

  mkdir -p "$(dirname "$report_file")"
  cat > "$report_file" <<CRASH_ANR_SMOKE_FIXTURE_PROPERTIES
status=passed
target=crash-anr-smoke-evidence
reason=
operationsRecordField=crashAnrSmoke.evidence
window=test fixture
track=local-emulator
packageName=com.bytedance.zgx.pocketmind
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

ksp_line="$(grep -n 'GRADLE_CMD.*:app:kspReleaseKotlin' scripts/verify_local.sh | cut -d: -f1 | head -n 1)"
verify_line="$(grep -n 'GRADLE_CMD.*testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest assembleRelease bundleRelease' scripts/verify_local.sh | cut -d: -f1 | head -n 1)"
if [[ -z "$ksp_line" || -z "$verify_line" || "$ksp_line" -ge "$verify_line" ]]; then
  fail "verify_local.sh must generate release KSP sources before lintDebug"
fi
grep -q 'RELEASE_AAB="app/build/outputs/bundle/release/app-release.aab"' scripts/verify_local.sh ||
  fail "verify_local.sh must verify the release AAB artifact"
grep -q -- '--aab "$RELEASE_AAB"' scripts/verify_local.sh ||
  fail "verify_local.sh must scan the release AAB artifact"
grep -q 'scripts/regression_emulator.sh' scripts/verify_local.sh ||
  fail "verify_local.sh must include regression_emulator.sh in shell syntax checks"
grep -q 'scripts/check_emulator_api_matrix.sh' scripts/verify_local.sh ||
  fail "verify_local.sh must include check_emulator_api_matrix.sh in shell syntax checks"
grep -q 'scripts/prepare_emulator_api_matrix.sh' scripts/verify_local.sh ||
  fail "verify_local.sh must include prepare_emulator_api_matrix.sh in shell syntax checks"
grep -q 'scripts/regression_emulator_api_matrix.sh' scripts/verify_local.sh ||
  fail "verify_local.sh must include regression_emulator_api_matrix.sh in shell syntax checks"
grep -q 'scripts/install_review_device.sh' scripts/verify_local.sh ||
  fail "verify_local.sh must include install_review_device.sh in shell syntax checks"
grep -q 'scripts/verify_fresh_start_main_shell_emulator.sh' scripts/verify_local.sh ||
  fail "verify_local.sh must include verify_fresh_start_main_shell_emulator.sh in shell syntax checks"
grep -q 'scripts/live_remote_emulator.sh' scripts/verify_local.sh ||
  fail "verify_local.sh must include live_remote_emulator.sh in shell syntax checks"
grep -q 'scripts/capture_release_screenshots.sh' scripts/verify_local.sh ||
  fail "verify_local.sh must include capture_release_screenshots.sh in shell syntax checks"
grep -q 'scripts/collect_release_flow_matrix_evidence.sh' scripts/verify_local.sh ||
  fail "verify_local.sh must include collect_release_flow_matrix_evidence.sh in shell syntax checks"
grep -q 'scripts/collect_crash_anr_smoke_evidence.sh' scripts/verify_local.sh ||
  fail "verify_local.sh must include collect_crash_anr_smoke_evidence.sh in shell syntax checks"
grep -q 'scripts/record_manual_acceptance_evidence.sh' scripts/verify_local.sh ||
  fail "verify_local.sh must include record_manual_acceptance_evidence.sh in shell syntax checks"
grep -q 'scripts/record_release_flow_evidence.sh' scripts/verify_local.sh ||
  fail "verify_local.sh must include record_release_flow_evidence.sh in shell syntax checks"
grep -q 'scripts/verify_upgrade_install_emulator.sh' scripts/verify_local.sh ||
  fail "verify_local.sh must include verify_upgrade_install_emulator.sh in shell syntax checks"
grep -q 'releaseFlowPassed=false' scripts/verify_upgrade_install_emulator.sh ||
  fail "upgrade install emulator report must not claim release flow approval"
grep -q 'versionCodeIncreased=' scripts/verify_upgrade_install_emulator.sh ||
  fail "upgrade install emulator report must expose versionCodeIncreased"
grep -q 'UPGRADE_BASE_APK' scripts/verify_upgrade_install_emulator.sh ||
  fail "upgrade install emulator verifier must accept an explicit base APK"
grep -q 'UPGRADE_CURRENT_APK' scripts/verify_upgrade_install_emulator.sh ||
  fail "upgrade install emulator verifier must accept an explicit current APK"
grep -q 'baseSignerSha256=' scripts/verify_upgrade_install_emulator.sh ||
  fail "upgrade install emulator report must expose base signer SHA-256"
grep -q 'currentSignerSha256=' scripts/verify_upgrade_install_emulator.sh ||
  fail "upgrade install emulator report must expose current signer SHA-256"
grep -q 'signerSha256Matches=' scripts/verify_upgrade_install_emulator.sh ||
  fail "upgrade install emulator report must expose signer SHA-256 match status"
grep -q 'baseInstallOutputFile=' scripts/verify_upgrade_install_emulator.sh ||
  fail "upgrade install emulator report must preserve base install output"
grep -q 'currentInstallOutputFile=' scripts/verify_upgrade_install_emulator.sh ||
  fail "upgrade install emulator report must preserve current install output"
grep -q 'testInstallOutputFile=' scripts/verify_upgrade_install_emulator.sh ||
  fail "upgrade install emulator report must preserve AndroidTest install output"
grep -q 'baseApkMode=' scripts/verify_upgrade_install_emulator.sh ||
  fail "upgrade install emulator report must expose base APK mode"
grep -q 'currentApkMode=' scripts/verify_upgrade_install_emulator.sh ||
  fail "upgrade install emulator report must expose current APK mode"
grep -q 'currentSourceApk=' scripts/verify_upgrade_install_emulator.sh ||
  fail "upgrade install emulator report must expose current APK source"
grep -q 'currentAndroidTestSourceApk=' scripts/verify_upgrade_install_emulator.sh ||
  fail "upgrade install emulator report must expose current AndroidTest APK source"
grep -q 'baseVersionCodeRaw=' scripts/verify_upgrade_install_emulator.sh ||
  fail "upgrade install emulator report must preserve raw base versionCode"
grep -q 'currentVersionCodeRaw=' scripts/verify_upgrade_install_emulator.sh ||
  fail "upgrade install emulator report must preserve raw current versionCode"
for eval_script in scripts/run_device_control_debug_eval.sh scripts/run_real_app_search_eval.sh; do
  grep -q 'artifact_schema=' "$eval_script" ||
    fail "$eval_script report must expose an artifact schema"
  grep -q 'artifact_id=' "$eval_script" ||
    fail "$eval_script report must expose an artifact id"
  grep -q 'failedTarget=' "$eval_script" ||
    fail "$eval_script report must expose failedTarget"
  grep -q 'api_level=' "$eval_script" ||
    fail "$eval_script report must expose API level"
  grep -q 'abi=' "$eval_script" ||
    fail "$eval_script report must expose ABI"
  grep -q 'logcat_sha256=' "$eval_script" ||
    fail "$eval_script report must bind logcat SHA-256"
done
grep -q 'scripts/verify_ai_behavior_eval.sh' scripts/verify_local.sh ||
  fail "verify_local.sh must include verify_ai_behavior_eval.sh in shell syntax checks"
grep -q 'scripts/collect_ai_behavior_actual_trace.sh' scripts/verify_local.sh ||
  fail "verify_local.sh must include collect_ai_behavior_actual_trace.sh in shell syntax checks"
grep -q -- '--trace-diff "$ARTIFACT_DIR/ai-behavior-planning-trace-diff.jsonl"' scripts/verify_release_gate.sh ||
  fail "release gate must write AI behavior planning trace diff"
grep -q 'REQUIRE_AI_BEHAVIOR_ACTUAL_TRACE=1' scripts/verify_release_gate.sh ||
  fail "public release gate must require AI behavior actual trace"
grep -q 'REQUIRE_AI_BEHAVIOR_RUNTIME_TRACE_SOURCE=1' scripts/verify_release_gate.sh ||
  fail "public release gate must require AI behavior runtime trace source"
grep -q -- '--require-boundary-map' scripts/verify_release_gate.sh ||
  fail "release gate must require AI behavior eval boundary mapping"
grep -q 'docs/capability_matrix.json' scripts/verify_ai_behavior_eval.sh ||
  fail "AI behavior eval gate must read MVP scenarios from capability matrix JSON"
grep -q 'nextStageMvpScenarios' scripts/verify_ai_behavior_eval.sh ||
  fail "AI behavior eval gate must use nextStageMvpScenarios as the MVP scenario source"
if grep -q 'REQUIRED_MVP_SCENARIOS=' scripts/verify_ai_behavior_eval.sh; then
  fail "AI behavior eval gate must not keep a hard-coded MVP scenario array"
fi
python3 - <<'PY' || fail "Capability matrix JSON MVP scenarios must match CapabilityMatrix"
import json
import pathlib
import re
import sys

kotlin = pathlib.Path("app/src/main/java/com/bytedance/zgx/pocketmind/capability/CapabilityMatrix.kt").read_text()
capability_matrix = json.loads(pathlib.Path("docs/capability_matrix.json").read_text())

def extract_quoted(pattern, source, label):
    match = re.search(pattern, source, re.S)
    if not match:
        print(f"missing {label}", file=sys.stderr)
        sys.exit(1)
    return re.findall(r'"([^"]+)"', match.group(1))

kotlin_ids = extract_quoted(
    r"nextStageMvpScenarioIds:\s*List<String>\s*=\s*listOf\((.*?)\)",
    kotlin,
    "CapabilityMatrix.nextStageMvpScenarioIds",
)
json_ids = [scenario.get("capabilityId", "") for scenario in capability_matrix.get("nextStageMvpScenarios", [])]
json_titles = [scenario.get("title", "") for scenario in capability_matrix.get("nextStageMvpScenarios", [])]
if kotlin_ids != json_ids:
    print(f"CapabilityMatrix MVP scenarios {kotlin_ids} != docs/capability_matrix.json {json_ids}", file=sys.stderr)
    sys.exit(1)
if len(set(json_ids)) != len(json_ids) or any(not title.strip() for title in json_titles):
    print("docs/capability_matrix.json nextStageMvpScenarios must have unique IDs and non-blank titles", file=sys.stderr)
    sys.exit(1)
PY
grep -q 'underCoveredMvpScenarios=' scripts/verify_ai_behavior_eval.sh ||
  fail "AI behavior eval report must expose under-covered MVP scenarios"
grep -q 'missingRequiredMvpScenarios=' scripts/verify_ai_behavior_eval.sh ||
  fail "AI behavior eval report must expose missing MVP scenarios"
grep -q 'expectedTools' scripts/verify_ai_behavior_eval.sh ||
  fail "AI behavior eval gate must validate expected tool fixtures"
grep -q 'expectedConfirmation' scripts/verify_ai_behavior_eval.sh ||
  fail "AI behavior eval gate must validate confirmation fixtures"
grep -q 'expectedRiskLevel' scripts/verify_ai_behavior_eval.sh ||
  fail "AI behavior eval gate must validate risk level fixtures"
grep -q 'privacy-mismatch' scripts/verify_ai_behavior_eval.sh ||
  fail "AI behavior eval gate must reject inconsistent privacy fixtures"
grep -q 'val supported: Set<String>' app/src/main/java/com/bytedance/zgx/pocketmind/action/ActionModels.kt ||
  fail "MobileActionFunctions must expose supported tool names for eval validation"
grep -q 'supported_tool_names' scripts/verify_ai_behavior_eval.sh ||
  fail "AI behavior eval gate must validate expected tools against supported tool names"
grep -q 'unknown-tool' scripts/verify_ai_behavior_eval.sh ||
  fail "AI behavior eval gate must reject unknown expected tools"
grep -q 'casesWithExpectedTools=' scripts/verify_ai_behavior_eval.sh ||
  fail "AI behavior eval report must expose expected tool coverage"
grep -q 'confirmationBreakdown=' scripts/verify_ai_behavior_eval.sh ||
  fail "AI behavior eval report must expose confirmation breakdown"
grep -q 'riskLevelBreakdown=' scripts/verify_ai_behavior_eval.sh ||
  fail "AI behavior eval report must expose risk level breakdown"
grep -q 'privacyBreakdown=' scripts/verify_ai_behavior_eval.sh ||
  fail "AI behavior eval report must expose privacy breakdown"
grep -q 'missing-confirmation-coverage' scripts/verify_ai_behavior_eval.sh ||
  fail "AI behavior eval gate must require confirmation coverage"
grep -q 'remote_send_confirmation' scripts/verify_ai_behavior_eval.sh ||
  fail "AI behavior eval gate must require remote send confirmation coverage"
grep -q 'missing-risk-coverage' scripts/verify_ai_behavior_eval.sh ||
  fail "AI behavior eval gate must require risk coverage"
grep -q 'traceDiffMissingActualCount=' scripts/verify_ai_behavior_eval.sh ||
  fail "AI behavior eval report must expose planning trace diff missing-actual count"
grep -q 'actualTools' scripts/verify_ai_behavior_eval.sh ||
  fail "AI behavior eval trace diff must include actual tools"
grep -q 'routingPath' scripts/verify_ai_behavior_eval.sh ||
  fail "AI behavior eval trace diff must validate routing path evidence"
grep -q 'actualRoutingPath' scripts/verify_ai_behavior_eval.sh ||
  fail "AI behavior eval trace diff must emit routing path evidence"
grep -q 'actualTraceSource' scripts/verify_ai_behavior_eval.sh ||
  fail "AI behavior eval trace diff must emit per-case trace source evidence"
grep -q 'actualTraceRecordedAt' scripts/verify_ai_behavior_eval.sh ||
  fail "AI behavior eval trace diff must emit per-case trace timestamp evidence"
grep -q 'AI_BEHAVIOR_ACTUAL_TRACE_MAX_AGE_DAYS' scripts/verify_ai_behavior_eval.sh ||
  fail "AI behavior eval gate must expose an actual trace max-age override"
grep -q 'actual-trace-recordedAt-stale' scripts/verify_ai_behavior_eval.sh ||
  fail "AI behavior eval gate must reject stale actual trace timestamps"
AI_BEHAVIOR_MISSING_ID_DIR="$TMP_DIR/ai-behavior-missing-id"
mkdir -p "$AI_BEHAVIOR_MISSING_ID_DIR"
cp app/src/test/resources/ai_behavior_eval/*.jsonl "$AI_BEHAVIOR_MISSING_ID_DIR/"
sed '1s/"id":"[^"]*",//' "$AI_BEHAVIOR_MISSING_ID_DIR/memory_recall.jsonl" > "$AI_BEHAVIOR_MISSING_ID_DIR/memory_recall.tmp"
mv "$AI_BEHAVIOR_MISSING_ID_DIR/memory_recall.tmp" "$AI_BEHAVIOR_MISSING_ID_DIR/memory_recall.jsonl"
expect_failure \
  "AI behavior eval rejects missing stable case id" \
  scripts/verify_ai_behavior_eval.sh \
    --dir "$AI_BEHAVIOR_MISSING_ID_DIR" \
    --require-boundary-map \
    --report "$ARTIFACT_DIR/ai-behavior-missing-id.properties"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-missing-id.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-missing-id.properties" "reason=missing-field:memory_recall:1:id"
AI_BEHAVIOR_DUPLICATE_ID_DIR="$TMP_DIR/ai-behavior-duplicate-id"
mkdir -p "$AI_BEHAVIOR_DUPLICATE_ID_DIR"
cp app/src/test/resources/ai_behavior_eval/*.jsonl "$AI_BEHAVIOR_DUPLICATE_ID_DIR/"
sed '2s/"id":"[^"]*"/"id":"memory_style_concise"/' "$AI_BEHAVIOR_DUPLICATE_ID_DIR/memory_recall.jsonl" > "$AI_BEHAVIOR_DUPLICATE_ID_DIR/memory_recall.tmp"
mv "$AI_BEHAVIOR_DUPLICATE_ID_DIR/memory_recall.tmp" "$AI_BEHAVIOR_DUPLICATE_ID_DIR/memory_recall.jsonl"
expect_failure \
  "AI behavior eval rejects duplicate stable case id" \
  scripts/verify_ai_behavior_eval.sh \
    --dir "$AI_BEHAVIOR_DUPLICATE_ID_DIR" \
    --require-boundary-map \
    --report "$ARTIFACT_DIR/ai-behavior-duplicate-id.properties"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-duplicate-id.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-duplicate-id.properties" "reason=duplicate-trace-case-id:memory_style_concise"
AI_BEHAVIOR_MISSING_REMOTE_CONFIRMATION_DIR="$TMP_DIR/ai-behavior-missing-remote-confirmation"
mkdir -p "$AI_BEHAVIOR_MISSING_REMOTE_CONFIRMATION_DIR"
cp app/src/test/resources/ai_behavior_eval/*.jsonl "$AI_BEHAVIOR_MISSING_REMOTE_CONFIRMATION_DIR/"
python3 - "$AI_BEHAVIOR_MISSING_REMOTE_CONFIRMATION_DIR/privacy_boundary.jsonl" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
for row in rows:
    if row["id"] == "privacy_remote_image_preview":
        row["expectedConfirmation"] = "none"
path.write_text(
    "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in rows),
    encoding="utf-8",
)
PY
expect_failure \
  "AI behavior eval requires remote send confirmation coverage" \
  scripts/verify_ai_behavior_eval.sh \
    --dir "$AI_BEHAVIOR_MISSING_REMOTE_CONFIRMATION_DIR" \
    --require-boundary-map \
    --report "$ARTIFACT_DIR/ai-behavior-missing-remote-confirmation.properties"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-missing-remote-confirmation.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-missing-remote-confirmation.properties" "reason=missing-confirmation-coverage:remote_send_confirmation"
AI_BEHAVIOR_REMOTE_CONFIRMATION_LOCALONLY_DIR="$TMP_DIR/ai-behavior-remote-confirmation-localonly"
mkdir -p "$AI_BEHAVIOR_REMOTE_CONFIRMATION_LOCALONLY_DIR"
cp app/src/test/resources/ai_behavior_eval/*.jsonl "$AI_BEHAVIOR_REMOTE_CONFIRMATION_LOCALONLY_DIR/"
python3 - "$AI_BEHAVIOR_REMOTE_CONFIRMATION_LOCALONLY_DIR/privacy_boundary.jsonl" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
for row in rows:
    if row["id"] == "privacy_remote_image_preview":
        row["privacy"] = "LocalOnly"
        row["localOnly"] = True
        row["remoteEligible"] = False
path.write_text(
    "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in rows),
    encoding="utf-8",
)
PY
expect_failure \
  "AI behavior eval rejects remote confirmation on LocalOnly fixture boundary" \
  scripts/verify_ai_behavior_eval.sh \
    --dir "$AI_BEHAVIOR_REMOTE_CONFIRMATION_LOCALONLY_DIR" \
    --require-boundary-map \
    --report "$ARTIFACT_DIR/ai-behavior-remote-confirmation-localonly.properties"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-remote-confirmation-localonly.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-remote-confirmation-localonly.properties" "reason=remote-confirmation-privacy-mismatch:privacy_boundary:6"
AI_BEHAVIOR_MISSING_REAL_APP_FAILURE_DIR="$TMP_DIR/ai-behavior-missing-real-app-failure"
mkdir -p "$AI_BEHAVIOR_MISSING_REAL_APP_FAILURE_DIR"
cp app/src/test/resources/ai_behavior_eval/*.jsonl "$AI_BEHAVIOR_MISSING_REAL_APP_FAILURE_DIR/"
python3 - "$AI_BEHAVIOR_MISSING_REAL_APP_FAILURE_DIR/runtime_failure.jsonl" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
for row in rows:
    if row["id"] == "runtime_app_search_submit_not_found":
        row["allowedFailureModes"] = ["submit_failure_unclassified"]
path.write_text(
    "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in rows),
    encoding="utf-8",
)
PY
expect_failure \
  "AI behavior eval requires real app search failure mode coverage" \
  scripts/verify_ai_behavior_eval.sh \
    --dir "$AI_BEHAVIOR_MISSING_REAL_APP_FAILURE_DIR" \
    --require-boundary-map \
    --report "$ARTIFACT_DIR/ai-behavior-missing-real-app-failure.properties"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-missing-real-app-failure.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-missing-real-app-failure.properties" "reason=missing-real-app-search-failure-mode-coverage:submit_not_found"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-missing-real-app-failure.properties" "missingRealAppSearchFailureModes=submit_not_found"
AI_BEHAVIOR_MISSING_PAGE_NOT_CHANGED_DIR="$TMP_DIR/ai-behavior-missing-page-not-changed"
mkdir -p "$AI_BEHAVIOR_MISSING_PAGE_NOT_CHANGED_DIR"
cp app/src/test/resources/ai_behavior_eval/*.jsonl "$AI_BEHAVIOR_MISSING_PAGE_NOT_CHANGED_DIR/"
python3 - "$AI_BEHAVIOR_MISSING_PAGE_NOT_CHANGED_DIR/runtime_failure.jsonl" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
for row in rows:
    if row["id"] == "runtime_app_search_page_not_changed":
        row["allowedFailureModes"] = ["unchanged_page_unclassified"]
path.write_text(
    "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in rows),
    encoding="utf-8",
)
PY
expect_failure \
  "AI behavior eval requires unchanged real app page coverage" \
  scripts/verify_ai_behavior_eval.sh \
    --dir "$AI_BEHAVIOR_MISSING_PAGE_NOT_CHANGED_DIR" \
    --require-boundary-map \
    --report "$ARTIFACT_DIR/ai-behavior-missing-page-not-changed.properties"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-missing-page-not-changed.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-missing-page-not-changed.properties" "reason=missing-real-app-search-failure-mode-coverage:page_not_changed"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-missing-page-not-changed.properties" "missingRealAppSearchFailureModes=page_not_changed"
AI_BEHAVIOR_MISSING_PUBLIC_BATCH_DIR="$TMP_DIR/ai-behavior-missing-public-batch"
mkdir -p "$AI_BEHAVIOR_MISSING_PUBLIC_BATCH_DIR"
cp app/src/test/resources/ai_behavior_eval/*.jsonl "$AI_BEHAVIOR_MISSING_PUBLIC_BATCH_DIR/"
python3 - "$AI_BEHAVIOR_MISSING_PUBLIC_BATCH_DIR/privacy_boundary.jsonl" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
for row in rows:
    if row["id"] == "privacy_public_weather_batch_allowed":
        row["expectedBoundary"] = "public_evidence_single_search_allowed"
path.write_text(
    "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in rows),
    encoding="utf-8",
)
PY
expect_failure \
  "AI behavior eval requires public evidence multi-search batch coverage" \
  scripts/verify_ai_behavior_eval.sh \
    --dir "$AI_BEHAVIOR_MISSING_PUBLIC_BATCH_DIR" \
    --require-boundary-map \
    --report "$ARTIFACT_DIR/ai-behavior-missing-public-batch.properties"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-missing-public-batch.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-missing-public-batch.properties" "reason=missing-required-boundary-coverage:public_evidence_multi_search_batch_allowed"
AI_TRACE_DIFF_MISSING="$ARTIFACT_DIR/ai-behavior-trace-diff-missing.jsonl"
expect_success \
  "AI behavior eval writes planning trace diff without actual trace" \
  scripts/verify_ai_behavior_eval.sh \
    --require-boundary-map \
    --trace-diff "$AI_TRACE_DIFF_MISSING" \
    --report "$ARTIFACT_DIR/ai-behavior-trace-diff-missing.properties"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-missing.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-missing.properties" "artifactSchema=AgentBehaviorEvalVerification/v1"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-missing.properties" "owner=agent-behavior"
grep -Eq '^recordedAt=20[0-9]{2}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$' "$ARTIFACT_DIR/ai-behavior-trace-diff-missing.properties" ||
  fail "AI behavior eval report must include UTC recordedAt"
grep -q '^command=.*scripts/verify_ai_behavior_eval.sh.*--trace-diff ' "$ARTIFACT_DIR/ai-behavior-trace-diff-missing.properties" ||
  fail "AI behavior eval report must include reproducible command"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-missing.properties" \
  "reproduciblePath=$ARTIFACT_DIR/ai-behavior-trace-diff-missing.properties"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-missing.properties" "traceDiffFile=$AI_TRACE_DIFF_MISSING"
grep -Eq '^traceDiffSha256=[0-9a-f]{64}$' "$ARTIFACT_DIR/ai-behavior-trace-diff-missing.properties" ||
  fail "AI behavior eval report must hash trace diff evidence"
ai_trace_missing_count="$(awk -F= '$1 == "caseCount" {print $2; exit}' "$ARTIFACT_DIR/ai-behavior-trace-diff-missing.properties")"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-missing.properties" "traceDiffMissingActualCount=$ai_trace_missing_count"
assert_report_contains_text "$AI_TRACE_DIFF_MISSING" '"actualTools": []'
assert_report_contains_text "$AI_TRACE_DIFF_MISSING" '"status": "missing_actual"'

AI_ACTUAL_TRACE="$TMP_DIR/ai-behavior-actual-trace.jsonl"
AI_TRACE_FRESH_RECORDED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
AI_TRACE_STALE_RECORDED_AT="2000-01-01T00:00:00Z"
AI_TRACE_FUTURE_RECORDED_AT="2099-01-01T00:00:00Z"
python3 - "$AI_ACTUAL_TRACE" "$AI_TRACE_FRESH_RECORDED_AT" <<'PY'
import json
import pathlib
import sys

fixture_dir = pathlib.Path("app/src/test/resources/ai_behavior_eval")
categories = [
    "memory_recall",
    "planner_false_positive",
    "tool_sequence",
    "ocr_noise",
    "runtime_failure",
    "privacy_boundary",
    "restart_recovery",
]
out = pathlib.Path(sys.argv[1])
trace_recorded_at = sys.argv[2]
with out.open("w", encoding="utf-8") as handle:
    for category in categories:
        path = fixture_dir / f"{category}.jsonl"
        for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
            line = line.strip()
            if not line:
                continue
            row = json.loads(line)
            trace = {
                "caseId": row["id"],
                "category": category,
                "input": row["input"],
                "actualTools": row["expectedTools"],
                "actualConfirmation": row["expectedConfirmation"],
                "actualRiskLevel": row["expectedRiskLevel"],
                "privacy": row["privacy"],
                "localOnly": row["localOnly"],
                "remoteEligible": row["remoteEligible"],
                "traceRecordedAt": trace_recorded_at,
                "traceSource": "agent_loop_runtime",
            }
            if row["expectedTools"]:
                trace["routingPath"] = "action_planner"
                trace["routingToolName"] = row["expectedTools"][0]
            else:
                trace["routingPath"] = "no_action"
                trace["routingRejectionReason"] = "no_action_intent_detected"
            handle.write(json.dumps(trace, ensure_ascii=False, sort_keys=True) + "\n")
PY
AI_ACTUAL_TRACE_SHA="$(shasum -a 256 "$AI_ACTUAL_TRACE" | awk '{print $1}')"
AI_ACTUAL_TRACE_MISSING_SOURCE="$TMP_DIR/ai-behavior-actual-trace-missing-source.jsonl"
python3 - "$AI_ACTUAL_TRACE" "$AI_ACTUAL_TRACE_MISSING_SOURCE" <<'PY'
import json
import pathlib
import sys

rows = [json.loads(line) for line in pathlib.Path(sys.argv[1]).read_text(encoding="utf-8").splitlines() if line.strip()]
rows[0].pop("traceSource", None)
pathlib.Path(sys.argv[2]).write_text(
    "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in rows),
    encoding="utf-8",
)
PY
expect_failure \
  "AI behavior eval rejects missing runtime trace source in strict provenance mode" \
  scripts/verify_ai_behavior_eval.sh \
    --require-boundary-map \
    --actual-trace "$AI_ACTUAL_TRACE_MISSING_SOURCE" \
    --trace-diff "$ARTIFACT_DIR/ai-behavior-trace-diff-missing-source.jsonl" \
    --require-actual-trace \
    --require-runtime-trace-source \
    --report "$ARTIFACT_DIR/ai-behavior-trace-diff-missing-source.properties"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-missing-source.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-missing-source.properties" "reason=invalid-actual-trace:1:traceSource"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-missing-source.properties" "requireRuntimeTraceSource=1"

AI_ACTUAL_TRACE_STALE="$TMP_DIR/ai-behavior-actual-trace-stale.jsonl"
python3 - "$AI_ACTUAL_TRACE" "$AI_ACTUAL_TRACE_STALE" "$AI_TRACE_STALE_RECORDED_AT" <<'PY'
import json
import pathlib
import sys

rows = [json.loads(line) for line in pathlib.Path(sys.argv[1]).read_text(encoding="utf-8").splitlines() if line.strip()]
for row in rows:
    row["traceRecordedAt"] = sys.argv[3]
pathlib.Path(sys.argv[2]).write_text(
    "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in rows),
    encoding="utf-8",
)
PY
expect_failure \
  "AI behavior eval rejects stale actual trace in strict provenance mode" \
  scripts/verify_ai_behavior_eval.sh \
    --require-boundary-map \
    --actual-trace "$AI_ACTUAL_TRACE_STALE" \
    --trace-diff "$ARTIFACT_DIR/ai-behavior-trace-diff-stale.jsonl" \
    --require-actual-trace \
    --require-runtime-trace-source \
    --report "$ARTIFACT_DIR/ai-behavior-trace-diff-stale.properties"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-stale.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-stale.properties" "reason=actual-trace-recordedAt-stale:1"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-stale.properties" "actualTraceMaxAgeDays=30"

AI_ACTUAL_TRACE_FUTURE="$TMP_DIR/ai-behavior-actual-trace-future.jsonl"
python3 - "$AI_ACTUAL_TRACE" "$AI_ACTUAL_TRACE_FUTURE" "$AI_TRACE_FUTURE_RECORDED_AT" <<'PY'
import json
import pathlib
import sys

rows = [json.loads(line) for line in pathlib.Path(sys.argv[1]).read_text(encoding="utf-8").splitlines() if line.strip()]
rows[0]["traceRecordedAt"] = sys.argv[3]
pathlib.Path(sys.argv[2]).write_text(
    "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in rows),
    encoding="utf-8",
)
PY
expect_failure \
  "AI behavior eval preserves future actual trace rejection" \
  scripts/verify_ai_behavior_eval.sh \
    --require-boundary-map \
    --actual-trace "$AI_ACTUAL_TRACE_FUTURE" \
    --trace-diff "$ARTIFACT_DIR/ai-behavior-trace-diff-future.jsonl" \
    --require-actual-trace \
    --require-runtime-trace-source \
    --report "$ARTIFACT_DIR/ai-behavior-trace-diff-future.properties"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-future.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-future.properties" "reason=actual-trace-recordedAt-future:1"

AI_TRACE_DIFF_MATCHED="$ARTIFACT_DIR/ai-behavior-trace-diff-matched.jsonl"
expect_success \
  "AI behavior eval accepts matching planning trace diff" \
  scripts/verify_ai_behavior_eval.sh \
    --require-boundary-map \
    --actual-trace "$AI_ACTUAL_TRACE" \
    --trace-diff "$AI_TRACE_DIFF_MATCHED" \
    --require-actual-trace \
    --require-runtime-trace-source \
    --report "$ARTIFACT_DIR/ai-behavior-trace-diff-matched.properties"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-matched.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-matched.properties" "actualTraceFile=$AI_ACTUAL_TRACE"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-matched.properties" "actualTraceSha256=$AI_ACTUAL_TRACE_SHA"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-matched.properties" "requireRuntimeTraceSource=1"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-matched.properties" "actualTraceMaxAgeDays=30"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-matched.properties" "actualTraceSourceBreakdown=agent_loop_runtime:$ai_trace_missing_count"
grep -Eq '^traceDiffSha256=[0-9a-f]{64}$' "$ARTIFACT_DIR/ai-behavior-trace-diff-matched.properties" ||
  fail "AI behavior eval report must hash matched trace diff evidence"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-matched.properties" "traceDiffMissingActualCount=0"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-matched.properties" "traceDiffMismatchCount=0"
assert_report_contains_text "$AI_TRACE_DIFF_MATCHED" '"caseId": "memory_style_concise"'
assert_report_contains_text "$AI_TRACE_DIFF_MATCHED" '"status": "matched"'
assert_report_contains_text "$AI_TRACE_DIFF_MATCHED" '"actualRoutingPath": "no_action"'
assert_report_contains_text "$AI_TRACE_DIFF_MATCHED" '"actualRoutingPath": "action_planner"'
assert_report_contains_text "$AI_TRACE_DIFF_MATCHED" '"actualTraceSource": "agent_loop_runtime"'
assert_report_contains_text "$AI_TRACE_DIFF_MATCHED" "\"actualTraceRecordedAt\": \"$AI_TRACE_FRESH_RECORDED_AT\""

AI_BAD_ROUTING_TRACE="$TMP_DIR/ai-behavior-actual-trace-bad-routing.jsonl"
python3 - "$AI_ACTUAL_TRACE" "$AI_BAD_ROUTING_TRACE" <<'PY'
import json
import pathlib
import sys

source = pathlib.Path(sys.argv[1])
target = pathlib.Path(sys.argv[2])
rows = [json.loads(line) for line in source.read_text(encoding="utf-8").splitlines() if line.strip()]
rows[0]["routingPath"] = "model guessed path"
target.write_text(
    "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in rows),
    encoding="utf-8",
)
PY
expect_failure \
  "AI behavior eval rejects invalid routing evidence" \
  scripts/verify_ai_behavior_eval.sh \
    --require-boundary-map \
    --actual-trace "$AI_BAD_ROUTING_TRACE" \
    --trace-diff "$ARTIFACT_DIR/ai-behavior-trace-diff-bad-routing.jsonl" \
    --require-actual-trace \
    --report "$ARTIFACT_DIR/ai-behavior-trace-diff-bad-routing.properties"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-bad-routing.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-bad-routing.properties" "reason=invalid-actual-trace:1:routingPath"
AI_NO_ACTION_WITH_TOOL_TRACE="$TMP_DIR/ai-behavior-actual-trace-no-action-with-tool.jsonl"
python3 - "$AI_ACTUAL_TRACE" "$AI_NO_ACTION_WITH_TOOL_TRACE" <<'PY'
import json
import pathlib
import sys

source = pathlib.Path(sys.argv[1])
target = pathlib.Path(sys.argv[2])
rows = [json.loads(line) for line in source.read_text(encoding="utf-8").splitlines() if line.strip()]
for row in rows:
    if row.get("actualTools"):
        row["routingPath"] = "no_action"
        row["routingRejectionReason"] = "no_action_intent_detected"
        break
target.write_text(
    "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in rows),
    encoding="utf-8",
)
PY
expect_failure \
  "AI behavior eval rejects no-action routing with actual tool evidence" \
  scripts/verify_ai_behavior_eval.sh \
    --require-boundary-map \
    --actual-trace "$AI_NO_ACTION_WITH_TOOL_TRACE" \
    --trace-diff "$ARTIFACT_DIR/ai-behavior-trace-diff-no-action-with-tool.jsonl" \
    --require-actual-trace \
    --report "$ARTIFACT_DIR/ai-behavior-trace-diff-no-action-with-tool.properties"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-no-action-with-tool.properties" "status=failed"
assert_report_contains_text "$ARTIFACT_DIR/ai-behavior-trace-diff-no-action-with-tool.properties" "reason=actual-trace-routing-conflict:"

AI_REMOTE_CONFIRMATION_LOCALONLY_TRACE="$TMP_DIR/ai-behavior-actual-trace-remote-confirmation-localonly.jsonl"
python3 - "$AI_ACTUAL_TRACE" "$AI_REMOTE_CONFIRMATION_LOCALONLY_TRACE" <<'PY'
import json
import pathlib
import sys

source = pathlib.Path(sys.argv[1])
target = pathlib.Path(sys.argv[2])
rows = [json.loads(line) for line in source.read_text(encoding="utf-8").splitlines() if line.strip()]
for row in rows:
    if row.get("actualConfirmation") == "remote_send_confirmation":
        row["privacy"] = "LocalOnly"
        row["localOnly"] = True
        row["remoteEligible"] = False
        break
target.write_text(
    "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in rows),
    encoding="utf-8",
)
PY
expect_failure \
  "AI behavior eval rejects actual remote confirmation on LocalOnly boundary" \
  scripts/verify_ai_behavior_eval.sh \
    --require-boundary-map \
    --actual-trace "$AI_REMOTE_CONFIRMATION_LOCALONLY_TRACE" \
    --trace-diff "$ARTIFACT_DIR/ai-behavior-trace-diff-remote-confirmation-localonly.jsonl" \
    --require-actual-trace \
    --report "$ARTIFACT_DIR/ai-behavior-trace-diff-remote-confirmation-localonly.properties"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-remote-confirmation-localonly.properties" "status=failed"
assert_report_contains_text "$ARTIFACT_DIR/ai-behavior-trace-diff-remote-confirmation-localonly.properties" "reason=actual-trace-remote-confirmation-privacy-mismatch:"

AI_BEHAVIOR_ALLOWED_FAILURE_SAFETY_DIR="$TMP_DIR/ai-behavior-allowed-failure-safety"
AI_ALLOWED_FAILURE_SAFETY_TRACE="$TMP_DIR/ai-behavior-actual-trace-allowed-failure-safety.jsonl"
AI_ALLOWED_FAILURE_SAFETY_DIFF="$ARTIFACT_DIR/ai-behavior-trace-diff-allowed-failure-safety.jsonl"
AI_ALLOWED_FAILURE_SAFETY_MODE="allowed_script_safety_drift"
mkdir -p "$AI_BEHAVIOR_ALLOWED_FAILURE_SAFETY_DIR"
cp app/src/test/resources/ai_behavior_eval/*.jsonl "$AI_BEHAVIOR_ALLOWED_FAILURE_SAFETY_DIR/"
python3 - "$AI_BEHAVIOR_ALLOWED_FAILURE_SAFETY_DIR" "$AI_ACTUAL_TRACE" "$AI_ALLOWED_FAILURE_SAFETY_TRACE" "$AI_ALLOWED_FAILURE_SAFETY_MODE" <<'PY'
import json
import pathlib
import sys

fixture_dir = pathlib.Path(sys.argv[1])
source = pathlib.Path(sys.argv[2])
target = pathlib.Path(sys.argv[3])
mode = sys.argv[4]

fixture_path = fixture_dir / "memory_recall.jsonl"
fixture_rows = [json.loads(line) for line in fixture_path.read_text(encoding="utf-8").splitlines() if line.strip()]
fixture_rows[0]["allowedFailureModes"] = [mode]
fixture_path.write_text(
    "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in fixture_rows),
    encoding="utf-8",
)

trace_rows = [json.loads(line) for line in source.read_text(encoding="utf-8").splitlines() if line.strip()]
trace_rows[0]["failureMode"] = mode
trace_rows[0]["actualRiskLevel"] = "sensitive" if trace_rows[0]["actualRiskLevel"] != "sensitive" else "low"
if trace_rows[0]["privacy"] == "LocalOnly":
    trace_rows[0]["privacy"] = "RemoteEligible"
    trace_rows[0]["localOnly"] = False
    trace_rows[0]["remoteEligible"] = True
else:
    trace_rows[0]["privacy"] = "LocalOnly"
    trace_rows[0]["localOnly"] = True
    trace_rows[0]["remoteEligible"] = False
target.write_text(
    "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in trace_rows),
    encoding="utf-8",
)
PY
expect_failure \
  "AI behavior eval rejects allowed failure with safety boundary drift" \
  scripts/verify_ai_behavior_eval.sh \
    --dir "$AI_BEHAVIOR_ALLOWED_FAILURE_SAFETY_DIR" \
    --require-boundary-map \
    --actual-trace "$AI_ALLOWED_FAILURE_SAFETY_TRACE" \
    --trace-diff "$AI_ALLOWED_FAILURE_SAFETY_DIFF" \
    --require-actual-trace \
    --require-runtime-trace-source \
    --report "$ARTIFACT_DIR/ai-behavior-trace-diff-allowed-failure-safety.properties"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-allowed-failure-safety.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-allowed-failure-safety.properties" "reason=trace-diff-mismatch"
assert_report_contains_text "$AI_ALLOWED_FAILURE_SAFETY_DIFF" '"actualFailureMode": "allowed_script_safety_drift"'
assert_report_contains_text "$AI_ALLOWED_FAILURE_SAFETY_DIFF" '"status": "mismatch"'

AI_BEHAVIOR_ALLOWED_FAILURE_FAIL_CLOSED_DIR="$TMP_DIR/ai-behavior-allowed-failure-fail-closed"
AI_ALLOWED_FAILURE_FAIL_CLOSED_TRACE="$TMP_DIR/ai-behavior-actual-trace-allowed-failure-fail-closed.jsonl"
AI_ALLOWED_FAILURE_FAIL_CLOSED_DIFF="$ARTIFACT_DIR/ai-behavior-trace-diff-allowed-failure-fail-closed.jsonl"
AI_ALLOWED_FAILURE_FAIL_CLOSED_MODE="allowed_script_fail_closed_drift"
mkdir -p "$AI_BEHAVIOR_ALLOWED_FAILURE_FAIL_CLOSED_DIR"
cp app/src/test/resources/ai_behavior_eval/*.jsonl "$AI_BEHAVIOR_ALLOWED_FAILURE_FAIL_CLOSED_DIR/"
python3 - "$AI_BEHAVIOR_ALLOWED_FAILURE_FAIL_CLOSED_DIR" "$AI_ACTUAL_TRACE" "$AI_ALLOWED_FAILURE_FAIL_CLOSED_TRACE" "$AI_ALLOWED_FAILURE_FAIL_CLOSED_MODE" <<'PY'
import json
import pathlib
import sys

fixture_dir = pathlib.Path(sys.argv[1])
source = pathlib.Path(sys.argv[2])
target = pathlib.Path(sys.argv[3])
mode = sys.argv[4]

fixture_path = fixture_dir / "memory_recall.jsonl"
fixture_rows = [json.loads(line) for line in fixture_path.read_text(encoding="utf-8").splitlines() if line.strip()]
fixture_rows[0]["expectedConfirmation"] = "fail_closed"
fixture_rows[0]["allowedFailureModes"] = [mode]
fixture_path.write_text(
    "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in fixture_rows),
    encoding="utf-8",
)

trace_rows = [json.loads(line) for line in source.read_text(encoding="utf-8").splitlines() if line.strip()]
trace_rows[0]["actualConfirmation"] = "none"
trace_rows[0]["failureMode"] = mode
target.write_text(
    "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in trace_rows),
    encoding="utf-8",
)
PY
expect_failure \
  "AI behavior eval rejects allowed failure that weakens fail closed" \
  scripts/verify_ai_behavior_eval.sh \
    --dir "$AI_BEHAVIOR_ALLOWED_FAILURE_FAIL_CLOSED_DIR" \
    --require-boundary-map \
    --actual-trace "$AI_ALLOWED_FAILURE_FAIL_CLOSED_TRACE" \
    --trace-diff "$AI_ALLOWED_FAILURE_FAIL_CLOSED_DIFF" \
    --require-actual-trace \
    --require-runtime-trace-source \
    --report "$ARTIFACT_DIR/ai-behavior-trace-diff-allowed-failure-fail-closed.properties"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-allowed-failure-fail-closed.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-allowed-failure-fail-closed.properties" "reason=trace-diff-mismatch"
assert_report_contains_text "$AI_ALLOWED_FAILURE_FAIL_CLOSED_DIFF" '"actualFailureMode": "allowed_script_fail_closed_drift"'
assert_report_contains_text "$AI_ALLOWED_FAILURE_FAIL_CLOSED_DIFF" '"status": "mismatch"'

reset_logs
AI_COLLECTOR_DIR="$ARTIFACT_DIR/ai-behavior-actual-trace-collector"
expect_success \
  "AI behavior actual trace collector records runtime trace evidence" \
  env ARTIFACT_DIR="$AI_COLLECTOR_DIR" \
  GRADLE_CMD="$FAKE_GRADLE" \
  FAKE_AI_BEHAVIOR_ACTUAL_TRACE_SOURCE="$AI_ACTUAL_TRACE" \
  scripts/collect_ai_behavior_actual_trace.sh
grep -q -- ":app:testDebugUnitTest --tests com.bytedance.zgx.pocketmind.eval.AiBehaviorActualTraceGeneratorTest" "$FAKE_GRADLE_LOG" ||
  fail "AI behavior actual trace collector must run the generator test"
AI_COLLECTOR_REPORT="$AI_COLLECTOR_DIR/ai-behavior-actual-trace-collection.properties"
AI_COLLECTOR_TRACE="$AI_COLLECTOR_DIR/ai-behavior-actual-trace.jsonl"
AI_COLLECTOR_DIFF="$AI_COLLECTOR_DIR/ai-behavior-planning-trace-diff.jsonl"
AI_COLLECTOR_EVAL="$AI_COLLECTOR_DIR/ai-behavior-eval.properties"
assert_report_contains "$AI_COLLECTOR_REPORT" "status=passed"
assert_report_contains "$AI_COLLECTOR_REPORT" "artifactSchema=AgentBehaviorActualTraceCollection/v1"
assert_report_contains "$AI_COLLECTOR_REPORT" "owner=agent-behavior"
assert_report_contains "$AI_COLLECTOR_REPORT" "actualTraceFile=$AI_COLLECTOR_TRACE"
assert_report_contains "$AI_COLLECTOR_REPORT" "actualTraceSha256=$(shasum -a 256 "$AI_COLLECTOR_TRACE" | awk '{print $1}')"
assert_report_contains "$AI_COLLECTOR_REPORT" "traceDiffFile=$AI_COLLECTOR_DIFF"
assert_report_contains "$AI_COLLECTOR_REPORT" "traceDiffSha256=$(shasum -a 256 "$AI_COLLECTOR_DIFF" | awk '{print $1}')"
assert_report_contains "$AI_COLLECTOR_REPORT" "evalReportFile=$AI_COLLECTOR_EVAL"
assert_report_contains "$AI_COLLECTOR_REPORT" "traceDiffMissingActualCount=0"
assert_report_contains "$AI_COLLECTOR_REPORT" "traceDiffExtraActualCount=0"
assert_report_contains "$AI_COLLECTOR_REPORT" "actualTraceSourceBreakdown=agent_loop_runtime:$ai_trace_missing_count"
assert_report_contains "$AI_COLLECTOR_EVAL" "requireRuntimeTraceSource=1"
assert_report_contains_text "$AI_COLLECTOR_DIFF" '"status": "matched"'

AI_BAD_ACTUAL_TRACE="$TMP_DIR/ai-behavior-actual-trace-bad.jsonl"
python3 - "$AI_ACTUAL_TRACE" "$AI_BAD_ACTUAL_TRACE" <<'PY'
import json
import pathlib
import sys

source = pathlib.Path(sys.argv[1])
target = pathlib.Path(sys.argv[2])
rows = [json.loads(line) for line in source.read_text(encoding="utf-8").splitlines() if line.strip()]
rows[0]["actualTools"] = ["share_text"]
rows[0]["routingPath"] = "action_planner"
rows[0]["routingToolName"] = "share_text"
rows[0].pop("routingRejectionReason", None)
target.write_text(
    "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in rows),
    encoding="utf-8",
)
PY
AI_TRACE_DIFF_MISMATCH="$ARTIFACT_DIR/ai-behavior-trace-diff-mismatch.jsonl"
expect_failure \
  "AI behavior eval rejects mismatched required planning trace diff" \
  scripts/verify_ai_behavior_eval.sh \
    --require-boundary-map \
    --actual-trace "$AI_BAD_ACTUAL_TRACE" \
    --trace-diff "$AI_TRACE_DIFF_MISMATCH" \
    --require-actual-trace \
    --report "$ARTIFACT_DIR/ai-behavior-trace-diff-mismatch.properties"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-mismatch.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-mismatch.properties" "reason=trace-diff-mismatch"
assert_report_contains_text "$AI_TRACE_DIFF_MISMATCH" '"status": "mismatch"'

reset_logs
AI_COLLECTOR_MISMATCH_DIR="$ARTIFACT_DIR/ai-behavior-actual-trace-collector-mismatch"
expect_failure \
  "AI behavior actual trace collector rejects mismatched runtime trace evidence" \
  env ARTIFACT_DIR="$AI_COLLECTOR_MISMATCH_DIR" \
  GRADLE_CMD="$FAKE_GRADLE" \
  FAKE_AI_BEHAVIOR_ACTUAL_TRACE_SOURCE="$AI_BAD_ACTUAL_TRACE" \
  scripts/collect_ai_behavior_actual_trace.sh
AI_COLLECTOR_MISMATCH_REPORT="$AI_COLLECTOR_MISMATCH_DIR/ai-behavior-actual-trace-collection.properties"
AI_COLLECTOR_MISMATCH_EVAL="$AI_COLLECTOR_MISMATCH_DIR/ai-behavior-eval.properties"
assert_report_contains "$AI_COLLECTOR_MISMATCH_REPORT" "status=failed"
assert_report_contains "$AI_COLLECTOR_MISMATCH_REPORT" "reason=trace-diff-mismatch"
assert_report_contains "$AI_COLLECTOR_MISMATCH_REPORT" "traceDiffMismatchCount=1"
assert_report_contains "$AI_COLLECTOR_MISMATCH_EVAL" "requireActualTrace=1"
assert_report_contains "$AI_COLLECTOR_MISMATCH_EVAL" "traceDiffMismatchCount=1"
AI_EXTRA_ACTUAL_TRACE="$TMP_DIR/ai-behavior-actual-trace-extra.jsonl"
cp "$AI_ACTUAL_TRACE" "$AI_EXTRA_ACTUAL_TRACE"
cat >> "$AI_EXTRA_ACTUAL_TRACE" <<AI_EXTRA_ACTUAL_TRACE_JSONL
{"caseId":"extra-case","category":"memory_recall","input":"extra","actualTools":[],"actualConfirmation":"none","actualRiskLevel":"low","privacy":"RemoteEligible","localOnly":false,"remoteEligible":true,"traceRecordedAt":"$AI_TRACE_FRESH_RECORDED_AT","traceSource":"agent_loop_runtime"}
AI_EXTRA_ACTUAL_TRACE_JSONL
expect_failure \
  "AI behavior eval rejects extra required planning trace rows" \
  scripts/verify_ai_behavior_eval.sh \
    --require-boundary-map \
    --actual-trace "$AI_EXTRA_ACTUAL_TRACE" \
    --trace-diff "$ARTIFACT_DIR/ai-behavior-trace-diff-extra.jsonl" \
    --require-actual-trace \
    --report "$ARTIFACT_DIR/ai-behavior-trace-diff-extra.properties"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-extra.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-extra.properties" "reason=trace-diff-extra-actual"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-extra.properties" "traceDiffExtraActualCount=1"
grep -q 'scripts/privacy_scan.sh' scripts/verify_local.sh ||
  fail "verify_local.sh must include privacy_scan.sh in shell syntax checks"
grep -q 'scripts/scan_android_artifacts.sh' scripts/verify_local.sh ||
  fail "verify_local.sh must include scan_android_artifacts.sh in shell syntax checks"
grep -q 'scripts/verify_perf_baseline.sh' scripts/verify_local.sh ||
  fail "verify_local.sh must include verify_perf_baseline.sh in shell syntax checks"
grep -q 'scripts/verify_privacy_review.sh' scripts/verify_local.sh ||
  fail "verify_local.sh must include verify_privacy_review.sh in shell syntax checks"
grep -q 'scripts/verify_release_record.sh' scripts/verify_local.sh ||
  fail "verify_local.sh must include verify_release_record.sh in shell syntax checks"
grep -q 'scripts/verify_store_policy_record.sh' scripts/verify_local.sh ||
  fail "verify_local.sh must include verify_store_policy_record.sh in shell syntax checks"
grep -q 'scripts/verify_release_operations_record.sh' scripts/verify_local.sh ||
  fail "verify_local.sh must include verify_release_operations_record.sh in shell syntax checks"
grep -q 'scripts/verify_release_validation_record.sh' scripts/verify_local.sh ||
  fail "verify_local.sh must include verify_release_validation_record.sh in shell syntax checks"
grep -q 'scripts/verify_model_license_review.sh' scripts/verify_local.sh ||
  fail "verify_local.sh must include verify_model_license_review.sh in shell syntax checks"
grep -q 'scripts/verify_release_mapping.sh' scripts/verify_local.sh ||
  fail "verify_local.sh must include verify_release_mapping.sh in shell syntax checks"
grep -q 'scripts/verify_release_gate.sh' scripts/verify_local.sh ||
  fail "verify_local.sh must include verify_release_gate.sh in shell syntax checks"
grep -q 'scripts/collect_perf_baseline.sh' scripts/verify_local.sh ||
  fail "verify_local.sh must include collect_perf_baseline.sh in shell syntax checks"
grep -q 'scripts/collect_model_license_metadata.sh' scripts/verify_local.sh ||
  fail "verify_local.sh must include collect_model_license_metadata.sh in shell syntax checks"
grep -q 'scripts/sign_release_artifacts.sh' scripts/verify_local.sh ||
  fail "verify_local.sh must include sign_release_artifacts.sh in shell syntax checks"
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
if "ALLOW_EMULATOR_INFRA_UNAVAILABLE=${{ github.event_name != 'workflow_dispatch' && '1' || '0' }}" not in emulator_regression_block:
    fail("emulator-regression must only allow infra skips outside workflow_dispatch release gates")
for required in (
    'REQUIRED_APIS="28 32 33 34 36"',
    "ALLOW_EMULATOR_INFRA_UNAVAILABLE=${{ github.event_name != 'workflow_dispatch' && '1' || '0' }}",
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

UPGRADE_FAKE_BASE_APK="$TMP_DIR/upgrade-base.apk"
UPGRADE_FAKE_CURRENT_APK="$TMP_DIR/upgrade-current.apk"
UPGRADE_FAKE_ANDROID_TEST_APK="$TMP_DIR/upgrade-current-android-test.apk"
printf 'base apk\n' > "$UPGRADE_FAKE_BASE_APK"
printf 'current apk\n' > "$UPGRADE_FAKE_CURRENT_APK"
printf 'current android test apk\n' > "$UPGRADE_FAKE_ANDROID_TEST_APK"
reset_logs
UPGRADE_FAKE_ARTIFACT_DIR="$ARTIFACT_DIR/upgrade-install-fake"
UPGRADE_FAKE_REPORT="$UPGRADE_FAKE_ARTIFACT_DIR/upgrade-install-emulator.properties"
expect_success \
  "upgrade install emulator verifier records explicit APK signing evidence" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'emulator-5554\tdevice' \
  UPGRADE_BASE_APK="$UPGRADE_FAKE_BASE_APK" \
  UPGRADE_CURRENT_APK="$UPGRADE_FAKE_CURRENT_APK" \
  UPGRADE_CURRENT_ANDROID_TEST_APK="$UPGRADE_FAKE_ANDROID_TEST_APK" \
  GRADLE_CMD="$FAKE_GRADLE" \
  ARTIFACT_DIR="$UPGRADE_FAKE_ARTIFACT_DIR" \
  REPORT_FILE="$UPGRADE_FAKE_REPORT" \
  scripts/verify_upgrade_install_emulator.sh
assert_report_contains "$UPGRADE_FAKE_REPORT" "status=passed"
assert_report_contains "$UPGRADE_FAKE_REPORT" "releaseFlowPassed=false"
assert_report_contains "$UPGRADE_FAKE_REPORT" "baseApkMode=explicit"
assert_report_contains "$UPGRADE_FAKE_REPORT" "currentApkMode=explicit"
assert_report_contains "$UPGRADE_FAKE_REPORT" "currentAndroidTestApkMode=explicit"
assert_report_contains "$UPGRADE_FAKE_REPORT" "baseSourceApk=$UPGRADE_FAKE_BASE_APK"
assert_report_contains "$UPGRADE_FAKE_REPORT" "currentSourceApk=$UPGRADE_FAKE_CURRENT_APK"
assert_report_contains "$UPGRADE_FAKE_REPORT" "currentAndroidTestSourceApk=$UPGRADE_FAKE_ANDROID_TEST_APK"
assert_report_contains "$UPGRADE_FAKE_REPORT" "baseVersionCode=1"
assert_report_contains "$UPGRADE_FAKE_REPORT" "baseVersionCodeRaw=1 minSdk=28 targetSdk=36"
assert_report_contains "$UPGRADE_FAKE_REPORT" "currentVersionCode=1"
assert_report_contains "$UPGRADE_FAKE_REPORT" "currentVersionCodeRaw=1 minSdk=28 targetSdk=36"
assert_report_contains "$UPGRADE_FAKE_REPORT" "signerSha256Matches=true"
assert_report_contains "$UPGRADE_FAKE_REPORT" "versionCodeIncreased=false"
assert_report_contains "$UPGRADE_FAKE_REPORT" "baseInstallOutputFile=$UPGRADE_FAKE_ARTIFACT_DIR/install-base.txt"
assert_report_contains "$UPGRADE_FAKE_REPORT" "currentInstallOutputFile=$UPGRADE_FAKE_ARTIFACT_DIR/install-current.txt"
assert_report_contains "$UPGRADE_FAKE_REPORT" "testInstallOutputFile=$UPGRADE_FAKE_ARTIFACT_DIR/install-android-test.txt"
[[ -f "$UPGRADE_FAKE_ARTIFACT_DIR/install-base.txt" ]] ||
  fail "upgrade install fake run must preserve base install output"
[[ -f "$UPGRADE_FAKE_ARTIFACT_DIR/install-current.txt" ]] ||
  fail "upgrade install fake run must preserve current install output"
[[ -f "$UPGRADE_FAKE_ARTIFACT_DIR/install-android-test.txt" ]] ||
  fail "upgrade install fake run must preserve AndroidTest install output"

VALID_PERF="$TMP_DIR/perf-baseline.properties"
VALID_PERF_SHA="1111111111111111111111111111111111111111111111111111111111111111"
PERF_RECORDED_AT="$(date -u +%Y-%m-%dT00:00:00Z)"
cat > "$VALID_PERF" <<VALID_PERF_BASELINE
status=passed
deviceSerial=device-a
deviceModel=Pixel Test
androidApi=36
abi=arm64-v8a
appVersion=0.1.0
releaseArtifactSha256=$VALID_PERF_SHA
modelId=chat-e2b
backend=GPU
firstLaunchInteractiveMs=1200
modelLoadMs=3500
firstTokenMs=900
tokensPerSecond=12.5
stopGenerationRecoveryMs=200
gpuFallbackStatus=not-needed
visionInputMs=500
memorySearch5kMs=25
memoryPeakMb=512
oomOrAnrObserved=false
recordedAt=$PERF_RECORDED_AT
VALID_PERF_BASELINE
expect_success \
  "perf baseline verifier accepts complete record" \
  scripts/verify_perf_baseline.sh --file "$VALID_PERF" --app-version 0.1.0 --report "$ARTIFACT_DIR/perf.properties"
assert_report_contains "$ARTIFACT_DIR/perf.properties" "artifactSchema=PerfBaselineVerification/v1"
assert_report_contains "$ARTIFACT_DIR/perf.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/perf.properties" "owner=release-engineering"
assert_report_contains "$ARTIFACT_DIR/perf.properties" "failedTarget="
grep -q '^recordedAt=' "$ARTIFACT_DIR/perf.properties" ||
  fail "perf verifier report must include recordedAt"
grep -q '^command=.*scripts/verify_perf_baseline.sh' "$ARTIFACT_DIR/perf.properties" ||
  fail "perf verifier report must include reproducible command"
assert_report_contains "$ARTIFACT_DIR/perf.properties" "reproduciblePath=$VALID_PERF"
assert_report_contains "$ARTIFACT_DIR/perf.properties" "baselineSha256=$(shasum -a 256 "$VALID_PERF" | awk '{print $1}')"
expect_success \
  "perf baseline verifier accepts matching artifact sha" \
  scripts/verify_perf_baseline.sh --file "$VALID_PERF" --artifact-sha256 "$VALID_PERF_SHA" --report "$ARTIFACT_DIR/perf-sha.properties"
assert_report_contains "$ARTIFACT_DIR/perf-sha.properties" "expectedArtifactSha256=$VALID_PERF_SHA"
expect_success \
  "perf baseline verifier records performance key" \
  scripts/verify_perf_baseline.sh \
    --file "$VALID_PERF" \
    --artifact-sha256 "$VALID_PERF_SHA" \
    --app-version 0.1.0 \
    --performance-key firstLaunch \
    --report "$ARTIFACT_DIR/perf-first-launch.properties"
assert_report_contains "$ARTIFACT_DIR/perf-first-launch.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/perf-first-launch.properties" "performanceKey=firstLaunch"
assert_report_contains "$ARTIFACT_DIR/perf-first-launch.properties" "expectedArtifactSha256=$VALID_PERF_SHA"
assert_report_contains "$ARTIFACT_DIR/perf-first-launch.properties" "expectedAppVersion=0.1.0"

INVALID_PERF="$TMP_DIR/perf-baseline-invalid.properties"
printf 'status=failed\n' > "$INVALID_PERF"
expect_failure \
  "perf baseline verifier rejects incomplete record" \
  scripts/verify_perf_baseline.sh --file "$INVALID_PERF" --report "$ARTIFACT_DIR/perf-invalid.properties"
assert_report_contains "$ARTIFACT_DIR/perf-invalid.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/perf-invalid.properties" "failedTarget=baseline-fields"
assert_report_contains_text "$ARTIFACT_DIR/perf-invalid.properties" "status-not-passed"
expect_failure \
  "perf baseline verifier rejects mismatched artifact sha" \
  scripts/verify_perf_baseline.sh --file "$VALID_PERF" --artifact-sha256 different-sha --report "$ARTIFACT_DIR/perf-sha-failed.properties"
assert_report_contains "$ARTIFACT_DIR/perf-sha-failed.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/perf-sha-failed.properties" "failedTarget=release-artifact"
assert_report_contains_text "$ARTIFACT_DIR/perf-sha-failed.properties" "release-artifact-sha-mismatch"
EMULATOR_PERF="$TMP_DIR/perf-baseline-emulator.properties"
sed 's/deviceSerial=device-a/deviceSerial=emulator-5554/' "$VALID_PERF" > "$EMULATOR_PERF"
expect_failure \
  "perf baseline verifier rejects emulator serials" \
  scripts/verify_perf_baseline.sh --file "$EMULATOR_PERF" --report "$ARTIFACT_DIR/perf-emulator.properties"
assert_report_contains "$ARTIFACT_DIR/perf-emulator.properties" "status=failed"
assert_report_contains_text "$ARTIFACT_DIR/perf-emulator.properties" "device-serial-is-emulator"
ZERO_PERF="$TMP_DIR/perf-baseline-zero.properties"
sed 's/firstTokenMs=900/firstTokenMs=0/' "$VALID_PERF" > "$ZERO_PERF"
expect_failure \
  "perf baseline verifier rejects zero critical timings" \
  scripts/verify_perf_baseline.sh --file "$ZERO_PERF" --report "$ARTIFACT_DIR/perf-zero.properties"
assert_report_contains "$ARTIFACT_DIR/perf-zero.properties" "status=failed"
BACKEND_INVALID_PERF="$TMP_DIR/perf-baseline-backend-invalid.properties"
sed 's/backend=GPU/backend=TPU/' "$VALID_PERF" > "$BACKEND_INVALID_PERF"
expect_failure \
  "perf baseline verifier rejects unsupported backend" \
  scripts/verify_perf_baseline.sh --file "$BACKEND_INVALID_PERF" --report "$ARTIFACT_DIR/perf-backend-invalid.properties"
assert_report_contains "$ARTIFACT_DIR/perf-backend-invalid.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/perf-backend-invalid.properties" "failedTarget=runtime-backend"
assert_report_contains_text "$ARTIFACT_DIR/perf-backend-invalid.properties" "backend-invalid"
GPU_FALLBACK_INVALID_PERF="$TMP_DIR/perf-baseline-gpu-fallback-invalid.properties"
sed 's/gpuFallbackStatus=not-needed/gpuFallbackStatus=unknown/' "$VALID_PERF" > "$GPU_FALLBACK_INVALID_PERF"
expect_failure \
  "perf baseline verifier rejects unsupported gpu fallback status" \
  scripts/verify_perf_baseline.sh --file "$GPU_FALLBACK_INVALID_PERF" --report "$ARTIFACT_DIR/perf-gpu-fallback-invalid.properties"
assert_report_contains "$ARTIFACT_DIR/perf-gpu-fallback-invalid.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/perf-gpu-fallback-invalid.properties" "failedTarget=runtime-backend"
assert_report_contains_text "$ARTIFACT_DIR/perf-gpu-fallback-invalid.properties" "gpu-fallback-status-invalid"
MODEL_INVALID_PERF="$TMP_DIR/perf-baseline-model-invalid.properties"
sed 's/modelId=chat-e2b/modelId=memory-embedding-gemma-300m/' "$VALID_PERF" > "$MODEL_INVALID_PERF"
expect_failure \
  "perf baseline verifier rejects non-chat model profile" \
  scripts/verify_perf_baseline.sh --file "$MODEL_INVALID_PERF" --report "$ARTIFACT_DIR/perf-model-invalid.properties"
assert_report_contains "$ARTIFACT_DIR/perf-model-invalid.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/perf-model-invalid.properties" "failedTarget=model-profile"
assert_report_contains_text "$ARTIFACT_DIR/perf-model-invalid.properties" "model-id-invalid"
FUTURE_PERF="$TMP_DIR/perf-baseline-future.properties"
sed 's/recordedAt=.*/recordedAt=2999-01-01T00:00:00Z/' "$VALID_PERF" > "$FUTURE_PERF"
expect_failure \
  "perf baseline verifier rejects future recordedAt" \
  scripts/verify_perf_baseline.sh --file "$FUTURE_PERF" --report "$ARTIFACT_DIR/perf-future.properties"
assert_report_contains "$ARTIFACT_DIR/perf-future.properties" "status=failed"

RELEASE_MAPPING="$TMP_DIR/mapping.txt"
printf 'com.bytedance.zgx.pocketmind.Sample -> a:\n' > "$RELEASE_MAPPING"
expect_success \
  "release mapping verifier accepts non-empty mapping file" \
  scripts/verify_release_mapping.sh --file "$RELEASE_MAPPING" --report "$ARTIFACT_DIR/release-mapping.properties"
assert_report_contains "$ARTIFACT_DIR/release-mapping.properties" "status=passed"
grep -q '^mappingSha256=' "$ARTIFACT_DIR/release-mapping.properties" ||
  fail "release mapping report must include mapping sha"
grep -q '^mappingSizeBytes=' "$ARTIFACT_DIR/release-mapping.properties" ||
  fail "release mapping report must include mapping size"
expect_failure \
  "release mapping verifier rejects missing mapping file" \
  scripts/verify_release_mapping.sh --file "$TMP_DIR/missing-mapping.txt" --report "$ARTIFACT_DIR/release-mapping-missing.properties"
assert_report_contains "$ARTIFACT_DIR/release-mapping-missing.properties" "status=failed"
EMPTY_RELEASE_MAPPING="$TMP_DIR/empty-mapping.txt"
: > "$EMPTY_RELEASE_MAPPING"
expect_failure \
  "release mapping verifier rejects empty mapping file" \
  scripts/verify_release_mapping.sh --file "$EMPTY_RELEASE_MAPPING" --report "$ARTIFACT_DIR/release-mapping-empty.properties"
assert_report_contains "$ARTIFACT_DIR/release-mapping-empty.properties" "status=failed"

RELEASE_RECORD_ARTIFACT="$TMP_DIR/release-record.aab"
RELEASE_RECORD_REPORT="$TMP_DIR/release-record-report.properties"
RELEASE_RECORD_FAILED_REPORT="$TMP_DIR/release-record-failed-report.properties"
RELEASE_RECORD_WEAK_REPORT="$TMP_DIR/release-record-weak-report.properties"
RELEASE_RECORD_STALE_REPORT="$TMP_DIR/release-record-stale-report.properties"
RELEASE_RECORD_PENDING="$TMP_DIR/release-record-pending.json"
RELEASE_RECORD_APPROVED="$TMP_DIR/release-record-approved.json"
RELEASE_RECORD_BLOCKER_EVIDENCE="$TMP_DIR/release-record-privacy-review-blocker.properties"
printf 'release artifact\n' > "$RELEASE_RECORD_ARTIFACT"
RELEASE_RECORD_RECORDED_AT="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
cat > "$RELEASE_RECORD_REPORT" <<RELEASE_RECORD_REPORT_PROPERTIES
artifactSchema=LocalReleaseVerification/v1
status=passed
target=local-verification
owner=release-engineering
recordedAt=$RELEASE_RECORD_RECORDED_AT
command=scripts/verify_local.sh
reproduciblePath=$RELEASE_RECORD_REPORT
RELEASE_RECORD_REPORT_PROPERTIES
cat > "$RELEASE_RECORD_FAILED_REPORT" <<RELEASE_RECORD_FAILED_REPORT_PROPERTIES
artifactSchema=LocalReleaseVerification/v1
status=failed
target=local-verification
owner=release-engineering
recordedAt=$RELEASE_RECORD_RECORDED_AT
command=scripts/verify_local.sh
reproduciblePath=$RELEASE_RECORD_FAILED_REPORT
RELEASE_RECORD_FAILED_REPORT_PROPERTIES
printf 'status=passed\ntarget=local-verification\n' > "$RELEASE_RECORD_WEAK_REPORT"
sed 's/^recordedAt=.*/recordedAt=2000-01-01T00:00:00Z/' "$RELEASE_RECORD_REPORT" > "$RELEASE_RECORD_STALE_REPORT"
printf 'status=accepted\nblocker=privacy-review\nscope=internal-testing-risk\n' > "$RELEASE_RECORD_BLOCKER_EVIDENCE"
RELEASE_RECORD_ARTIFACT_SHA="$(shasum -a 256 "$RELEASE_RECORD_ARTIFACT" | awk '{print $1}')"
RELEASE_RECORD_ARTIFACT_SIZE="$(wc -c < "$RELEASE_RECORD_ARTIFACT" | tr -d ' ')"
RELEASE_RECORD_REPORT_SHA="$(shasum -a 256 "$RELEASE_RECORD_REPORT" | awk '{print $1}')"
RELEASE_RECORD_FAILED_REPORT_SHA="$(shasum -a 256 "$RELEASE_RECORD_FAILED_REPORT" | awk '{print $1}')"
RELEASE_RECORD_WEAK_REPORT_SHA="$(shasum -a 256 "$RELEASE_RECORD_WEAK_REPORT" | awk '{print $1}')"
RELEASE_RECORD_STALE_REPORT_SHA="$(shasum -a 256 "$RELEASE_RECORD_STALE_REPORT" | awk '{print $1}')"
RELEASE_RECORD_BLOCKER_EVIDENCE_SHA="$(shasum -a 256 "$RELEASE_RECORD_BLOCKER_EVIDENCE" | awk '{print $1}')"
RELEASE_RECORD_HEAD="$(git rev-parse HEAD)"
RELEASE_RECORD_NON_HEAD="$(git rev-parse HEAD^)"
RELEASE_RECORD_DATE="$(date +%F)"
cat > "$RELEASE_RECORD_PENDING" <<'RELEASE_RECORD_PENDING_JSON'
{
  "version": 1,
  "status": "pending_release_record",
  "release": {}
}
RELEASE_RECORD_PENDING_JSON
RELEASE_RECORD_PENDING_SHA="$(shasum -a 256 "$RELEASE_RECORD_PENDING" | awk '{print $1}')"
expect_failure \
  "release record verifier rejects pending records" \
  scripts/verify_release_record.sh --file "$RELEASE_RECORD_PENDING" --report "$ARTIFACT_DIR/release-record-pending.properties"
assert_release_verifier_failed_report "$ARTIFACT_DIR/release-record-pending.properties" "ReleaseRecordVerification/v1"
assert_report_contains "$ARTIFACT_DIR/release-record-pending.properties" "failedTarget=release-record"
assert_report_contains "$ARTIFACT_DIR/release-record-pending.properties" "recordSha256=$RELEASE_RECORD_PENDING_SHA"
cat > "$RELEASE_RECORD_APPROVED" <<RELEASE_RECORD_APPROVED_JSON
{
  "version": 1,
  "status": "approved",
  "release": {
    "applicationId": "com.bytedance.zgx.pocketmind",
    "versionCode": 1,
    "versionName": "0.1.0",
    "gitCommit": "$RELEASE_RECORD_HEAD",
    "gitBranch": "main",
    "targetChannel": "internal_testing",
    "releaseDate": "$RELEASE_RECORD_DATE",
    "owner": "Release Owner",
    "reviewer": "Release Reviewer",
    "changelog": "Initial release candidate.",
    "releaseNotes": "Initial internal release.",
    "agentBehaviorSummary": "Remote OpenAI-style public read-only tool calls execute through the local Agent runtime and mixed private/action batches fail closed before execution.",
    "unsupportedCapabilities": [
      "Full PDF parsing",
      "Local image semantic understanding without a configured vision model"
    ],
    "artifact": {
      "type": "aab",
      "path": "$RELEASE_RECORD_ARTIFACT",
      "sha256": "$RELEASE_RECORD_ARTIFACT_SHA",
      "sizeBytes": $RELEASE_RECORD_ARTIFACT_SIZE,
      "signingCertificateSha256": "1111111111111111111111111111111111111111111111111111111111111111"
    },
    "verificationReports": [
      {
        "name": "local",
        "path": "$RELEASE_RECORD_REPORT",
        "sha256": "$RELEASE_RECORD_REPORT_SHA"
      }
    ],
    "blockers": [
      {
        "id": "privacy-review",
        "status": "accepted",
        "owner": "Release Owner",
        "date": "$RELEASE_RECORD_DATE",
        "evidencePath": "$RELEASE_RECORD_BLOCKER_EVIDENCE",
        "evidenceSha256": "$RELEASE_RECORD_BLOCKER_EVIDENCE_SHA",
        "riskNote": "Accepted for internal testing only."
      }
    ]
  }
}
RELEASE_RECORD_APPROVED_JSON
RELEASE_RECORD_APPROVED_SHA="$(shasum -a 256 "$RELEASE_RECORD_APPROVED" | awk '{print $1}')"
expect_success \
  "release record verifier accepts approved current record" \
  scripts/verify_release_record.sh --file "$RELEASE_RECORD_APPROVED" --report "$ARTIFACT_DIR/release-record-approved.properties"
assert_release_verifier_passed_report "$ARTIFACT_DIR/release-record-approved.properties" "ReleaseRecordVerification/v1"
assert_report_contains "$ARTIFACT_DIR/release-record-approved.properties" "recordSha256=$RELEASE_RECORD_APPROVED_SHA"
grep -Eq '^gradleSha256=[0-9a-f]{64}$' "$ARTIFACT_DIR/release-record-approved.properties" ||
  fail "release record verifier report must include gradleSha256"
RELEASE_RECORD_WEAK_REPORT_JSON="$TMP_DIR/release-record-weak-report.json"
python3 - "$RELEASE_RECORD_APPROVED" "$RELEASE_RECORD_WEAK_REPORT_JSON" "$RELEASE_RECORD_WEAK_REPORT" "$RELEASE_RECORD_WEAK_REPORT_SHA" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
record["release"]["verificationReports"][0]["path"] = sys.argv[3]
record["release"]["verificationReports"][0]["sha256"] = sys.argv[4]
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release record verifier rejects verification report without evidence schema" \
  scripts/verify_release_record.sh --file "$RELEASE_RECORD_WEAK_REPORT_JSON" --report "$ARTIFACT_DIR/release-record-weak-report.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-record-weak-report.properties" "verification-report-0-artifact-schema-missing"
RELEASE_RECORD_STALE_REPORT_JSON="$TMP_DIR/release-record-stale-report.json"
python3 - "$RELEASE_RECORD_APPROVED" "$RELEASE_RECORD_STALE_REPORT_JSON" "$RELEASE_RECORD_STALE_REPORT" "$RELEASE_RECORD_STALE_REPORT_SHA" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
record["release"]["verificationReports"][0]["path"] = sys.argv[3]
record["release"]["verificationReports"][0]["sha256"] = sys.argv[4]
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release record verifier rejects stale verification report evidence" \
  scripts/verify_release_record.sh --file "$RELEASE_RECORD_STALE_REPORT_JSON" --report "$ARTIFACT_DIR/release-record-stale-report.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-record-stale-report.properties" "verification-report-0-recorded-at-stale"
expect_failure \
  "release record verifier rejects internal channel in public context" \
  env PUBLIC_RELEASE_CONTEXT=1 \
  ALLOW_DIRTY_RELEASE=1 \
  EXPECTED_RELEASE_ARTIFACT_PATH="$RELEASE_RECORD_ARTIFACT" \
  EXPECTED_RELEASE_ARTIFACT_TYPE=aab \
  EXPECTED_RELEASE_ARTIFACT_SHA256="$RELEASE_RECORD_ARTIFACT_SHA" \
  EXPECTED_SIGNING_CERT_SHA256=1111111111111111111111111111111111111111111111111111111111111111 \
  scripts/verify_release_record.sh --file "$RELEASE_RECORD_APPROVED" --report "$ARTIFACT_DIR/release-record-public-internal-channel.properties"
assert_report_contains "$ARTIFACT_DIR/release-record-public-internal-channel.properties" "status=failed"
RELEASE_RECORD_PUBLIC="$TMP_DIR/release-record-public.json"
sed 's/"targetChannel": "internal_testing"/"targetChannel": "open_testing"/' "$RELEASE_RECORD_APPROVED" > "$RELEASE_RECORD_PUBLIC"
expect_success \
  "release record verifier accepts matching public aab record" \
  env PUBLIC_RELEASE_CONTEXT=1 \
  ALLOW_DIRTY_RELEASE=1 \
  EXPECTED_RELEASE_ARTIFACT_PATH="$RELEASE_RECORD_ARTIFACT" \
  EXPECTED_RELEASE_ARTIFACT_TYPE=aab \
  EXPECTED_RELEASE_ARTIFACT_SHA256="$RELEASE_RECORD_ARTIFACT_SHA" \
  EXPECTED_SIGNING_CERT_SHA256=1111111111111111111111111111111111111111111111111111111111111111 \
  scripts/verify_release_record.sh --file "$RELEASE_RECORD_PUBLIC" --report "$ARTIFACT_DIR/release-record-public.properties"
assert_report_contains "$ARTIFACT_DIR/release-record-public.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/release-record-public.properties" "allowDirtyRelease=1"
DIRTY_RELEASE_MARKER="$ROOT_DIR/release-record-dirty-test.tmp"
CLEANUP_PATHS+=("$DIRTY_RELEASE_MARKER")
printf 'dirty release record test\n' > "$DIRTY_RELEASE_MARKER"
expect_failure \
  "release record verifier rejects dirty public source tree" \
  env PUBLIC_RELEASE_CONTEXT=1 \
  EXPECTED_RELEASE_ARTIFACT_PATH="$RELEASE_RECORD_ARTIFACT" \
  EXPECTED_RELEASE_ARTIFACT_TYPE=aab \
  EXPECTED_RELEASE_ARTIFACT_SHA256="$RELEASE_RECORD_ARTIFACT_SHA" \
  EXPECTED_SIGNING_CERT_SHA256=1111111111111111111111111111111111111111111111111111111111111111 \
  scripts/verify_release_record.sh --file "$RELEASE_RECORD_PUBLIC" --report "$ARTIFACT_DIR/release-record-public-dirty.properties"
assert_report_contains "$ARTIFACT_DIR/release-record-public-dirty.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/release-record-public-dirty.properties" "reason=git-worktree-dirty"
rm -f "$DIRTY_RELEASE_MARKER"
RELEASE_RECORD_OTHER_ARTIFACT="$TMP_DIR/release-record-other.aab"
printf 'other release artifact\n' > "$RELEASE_RECORD_OTHER_ARTIFACT"
expect_failure \
  "release record verifier rejects mismatched public artifact path" \
  env PUBLIC_RELEASE_CONTEXT=1 \
  ALLOW_DIRTY_RELEASE=1 \
  EXPECTED_RELEASE_ARTIFACT_PATH="$RELEASE_RECORD_OTHER_ARTIFACT" \
  EXPECTED_RELEASE_ARTIFACT_TYPE=aab \
  EXPECTED_RELEASE_ARTIFACT_SHA256="$(shasum -a 256 "$RELEASE_RECORD_OTHER_ARTIFACT" | awk '{print $1}')" \
  EXPECTED_SIGNING_CERT_SHA256=1111111111111111111111111111111111111111111111111111111111111111 \
  scripts/verify_release_record.sh --file "$RELEASE_RECORD_PUBLIC" --report "$ARTIFACT_DIR/release-record-public-artifact-mismatch.properties"
assert_report_contains "$ARTIFACT_DIR/release-record-public-artifact-mismatch.properties" "status=failed"
RELEASE_RECORD_FUTURE="$TMP_DIR/release-record-future.json"
sed 's/"releaseDate": "'"$RELEASE_RECORD_DATE"'"/"releaseDate": "2999-01-01"/' "$RELEASE_RECORD_APPROVED" > "$RELEASE_RECORD_FUTURE"
expect_failure \
  "release record verifier rejects future release dates" \
  scripts/verify_release_record.sh --file "$RELEASE_RECORD_FUTURE" --report "$ARTIFACT_DIR/release-record-future.properties"
assert_report_contains "$ARTIFACT_DIR/release-record-future.properties" "status=failed"
RELEASE_RECORD_OLD_COMMIT="$TMP_DIR/release-record-old-commit.json"
sed 's/"gitCommit": "'"$RELEASE_RECORD_HEAD"'"/"gitCommit": "'"$RELEASE_RECORD_NON_HEAD"'"/' "$RELEASE_RECORD_APPROVED" > "$RELEASE_RECORD_OLD_COMMIT"
expect_failure \
  "release record verifier rejects non-head source commit" \
  scripts/verify_release_record.sh --file "$RELEASE_RECORD_OLD_COMMIT" --report "$ARTIFACT_DIR/release-record-old-commit.properties"
assert_report_contains "$ARTIFACT_DIR/release-record-old-commit.properties" "status=failed"
RELEASE_RECORD_BAD_SHA="$TMP_DIR/release-record-bad-sha.json"
sed 's/"sha256": "'"$RELEASE_RECORD_ARTIFACT_SHA"'"/"sha256": "0000000000000000000000000000000000000000000000000000000000000000"/' "$RELEASE_RECORD_APPROVED" > "$RELEASE_RECORD_BAD_SHA"
expect_failure \
  "release record verifier rejects artifact sha mismatch" \
  scripts/verify_release_record.sh --file "$RELEASE_RECORD_BAD_SHA" --report "$ARTIFACT_DIR/release-record-bad-sha.properties"
assert_report_contains "$ARTIFACT_DIR/release-record-bad-sha.properties" "status=failed"
RELEASE_RECORD_BAD_BLOCKER_EVIDENCE_SHA="$TMP_DIR/release-record-bad-blocker-evidence-sha.json"
sed 's/"evidenceSha256": "'"$RELEASE_RECORD_BLOCKER_EVIDENCE_SHA"'"/"evidenceSha256": "0000000000000000000000000000000000000000000000000000000000000000"/' "$RELEASE_RECORD_APPROVED" > "$RELEASE_RECORD_BAD_BLOCKER_EVIDENCE_SHA"
expect_failure \
  "release record verifier rejects blocker evidence sha mismatch" \
  scripts/verify_release_record.sh --file "$RELEASE_RECORD_BAD_BLOCKER_EVIDENCE_SHA" --report "$ARTIFACT_DIR/release-record-bad-blocker-evidence-sha.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-record-bad-blocker-evidence-sha.properties" "privacy-review-evidence-sha-mismatch"
RELEASE_RECORD_FAILED_REPORT_JSON="$TMP_DIR/release-record-failed-report.json"
sed \
  -e 's#'"$RELEASE_RECORD_REPORT"'#'"$RELEASE_RECORD_FAILED_REPORT"'#' \
  -e 's#'"$RELEASE_RECORD_REPORT_SHA"'#'"$RELEASE_RECORD_FAILED_REPORT_SHA"'#' \
  "$RELEASE_RECORD_APPROVED" > "$RELEASE_RECORD_FAILED_REPORT_JSON"
expect_failure \
  "release record verifier rejects failed verification report" \
  scripts/verify_release_record.sh --file "$RELEASE_RECORD_FAILED_REPORT_JSON" --report "$ARTIFACT_DIR/release-record-failed-report.properties"
assert_report_contains "$ARTIFACT_DIR/release-record-failed-report.properties" "status=failed"

STORE_POLICY_NOTICE="$TMP_DIR/store-privacy-notice.md"
STORE_POLICY_MANIFEST="$TMP_DIR/AndroidManifest.xml"
STORE_POLICY_PENDING="$TMP_DIR/store-policy-pending.json"
STORE_POLICY_APPROVED="$TMP_DIR/store-policy-approved.json"
STORE_POLICY_REVIEW_EVIDENCE="$TMP_DIR/store-policy-review.properties"
cat > "$STORE_POLICY_NOTICE" <<'STORE_POLICY_NOTICE_MD'
# PocketMind store privacy notice

PocketMind stores chat sessions, user-entered chat text, model records, memory
records, and audit metadata in local Android app storage. Users can delete chat
sessions, use forgetting individual records, and use clearing explicit memory
records.

Remote model mode sends requests only to a user-configured OpenAI-compatible
chat endpoint. Remote transport requires HTTPS. The configured endpoint may
receive remote prompts and responses. Android external intents may share data
with a destination app or Android system component after confirmation.

The app does not contain a first-party analytics upload path. Model downloads
contact model hosts; network operators and model hosts may receive normal
download metadata. Android runtime permissions are disclosed for system speech
recognition, contacts, calendar, media, Usage Access, Accessibility, and
MediaProjection.
STORE_POLICY_NOTICE_MD
STORE_POLICY_NOTICE_SHA="$(shasum -a 256 "$STORE_POLICY_NOTICE" | awk '{print $1}')"
cat > "$STORE_POLICY_REVIEW_EVIDENCE" <<STORE_POLICY_REVIEW_EVIDENCE_PROPERTIES
status=approved
target=store-policy-review-approved-evidence
privacyNoticePath=$STORE_POLICY_NOTICE
privacyNoticeSha256=$STORE_POLICY_NOTICE_SHA
scope=store-listing-data-safety-permissions-special-access
requiredDecision=approved
approvalStatus=approved
reviewer=Store Reviewer
STORE_POLICY_REVIEW_EVIDENCE_PROPERTIES
STORE_POLICY_REVIEW_EVIDENCE_SHA="$(shasum -a 256 "$STORE_POLICY_REVIEW_EVIDENCE" | awk '{print $1}')"
cat > "$STORE_POLICY_MANIFEST" <<'STORE_POLICY_MANIFEST_XML'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
</manifest>
STORE_POLICY_MANIFEST_XML
STORE_POLICY_MANIFEST_SHA="$(shasum -a 256 "$STORE_POLICY_MANIFEST" | awk '{print $1}')"
cat > "$STORE_POLICY_PENDING" <<'STORE_POLICY_PENDING_JSON'
{
  "version": 1,
  "status": "pending_policy_review"
}
STORE_POLICY_PENDING_JSON
STORE_POLICY_PENDING_SHA="$(shasum -a 256 "$STORE_POLICY_PENDING" | awk '{print $1}')"
expect_failure \
  "store policy verifier rejects pending records" \
  env PRIVACY_NOTICE_FILE="$STORE_POLICY_NOTICE" MANIFEST_FILE="$STORE_POLICY_MANIFEST" \
  scripts/verify_store_policy_record.sh --file "$STORE_POLICY_PENDING" --report "$ARTIFACT_DIR/store-policy-pending.properties"
assert_release_verifier_failed_report "$ARTIFACT_DIR/store-policy-pending.properties" "StorePolicyRecordVerification/v1"
assert_report_contains "$ARTIFACT_DIR/store-policy-pending.properties" "failedTarget=store-policy-record"
assert_report_contains "$ARTIFACT_DIR/store-policy-pending.properties" "storePolicySha256=$STORE_POLICY_PENDING_SHA"
cat > "$STORE_POLICY_APPROVED" <<STORE_POLICY_APPROVED_JSON
{
  "version": 1,
  "status": "approved",
  "privacyNoticePath": "$STORE_POLICY_NOTICE",
  "privacyNoticeSha256": "$STORE_POLICY_NOTICE_SHA",
  "appListing": {
    "appName": "PocketMind",
    "shortDescription": "Privacy-first pocket AI: local, optional remote, confirmed actions.",
    "fullDescription": "PocketMind is a privacy-first pocket AI assistant for Android: it is locally usable with downloaded or imported models, can optionally use user-configured remote multimodal models for text and image requests, and only executes device actions after explicit confirmation. It stores user sessions locally, protects private context with confirmation, and clearly separates optional remote model calls from local-only data.",
    "category": "Productivity",
    "contactEmail": "release@pocketmind.app",
    "privacyPolicyUrl": "https://pocketmind.app/privacy"
  },
  "dataSafety": {
    "userDataCollected": true,
    "userDataShared": true,
    "encryptedInTransit": true,
    "userDeletable": true,
    "optionalRemoteModelEndpoints": true,
    "externalRecipients": [
      "User-configured remote model endpoints",
      "Recommended and custom model download hosts",
      "Android system or destination apps opened by confirmed external intents"
    ],
    "noFirstPartyAnalyticsUpload": true,
    "localStorageDisclosed": true,
    "remoteModelCallsDisclosed": true,
    "modelDownloadsDisclosed": true,
    "androidPermissionsDisclosed": true
  },
  "modelDownloads": {
    "describedAsLargeOptionalAssets": true,
    "declaresNotBundledInApk": true
  },
  "permissions": [
    {
      "name": "android.permission.INTERNET",
      "purpose": "Connects only to user-configured remote model endpoints and model download hosts."
    },
    {
      "name": "android.permission.RECORD_AUDIO",
      "purpose": "Lets the user dictate text through explicit voice input before sending."
    }
  ],
  "specialAccessDisclosures": [
    {
      "name": "UsageAccess",
      "purpose": "Used only after confirmation to summarize the current foreground app."
    },
    {
      "name": "AccessibilityService",
      "purpose": "Used only after confirmation to read current-screen text nodes."
    },
    {
      "name": "MediaProjection",
      "purpose": "Used only after confirmation for one-shot current screenshot OCR."
    }
  ],
  "review": {
    "reviewer": "Store Reviewer",
    "reviewDate": "$(date +%F)",
    "evidencePath": "$STORE_POLICY_REVIEW_EVIDENCE",
    "evidenceSha256": "$STORE_POLICY_REVIEW_EVIDENCE_SHA"
  }
}
STORE_POLICY_APPROVED_JSON
STORE_POLICY_APPROVED_SHA="$(shasum -a 256 "$STORE_POLICY_APPROVED" | awk '{print $1}')"
expect_success \
  "store policy verifier accepts approved manifest-aligned record" \
  env PRIVACY_NOTICE_FILE="$STORE_POLICY_NOTICE" MANIFEST_FILE="$STORE_POLICY_MANIFEST" \
  scripts/verify_store_policy_record.sh --file "$STORE_POLICY_APPROVED" --report "$ARTIFACT_DIR/store-policy-approved.properties"
assert_release_verifier_passed_report "$ARTIFACT_DIR/store-policy-approved.properties" "StorePolicyRecordVerification/v1"
assert_report_contains "$ARTIFACT_DIR/store-policy-approved.properties" "storePolicySha256=$STORE_POLICY_APPROVED_SHA"
assert_report_contains "$ARTIFACT_DIR/store-policy-approved.properties" "privacyNoticeSha256=$STORE_POLICY_NOTICE_SHA"
assert_report_contains "$ARTIFACT_DIR/store-policy-approved.properties" "manifestSha256=$STORE_POLICY_MANIFEST_SHA"
STORE_POLICY_INCOMPLETE_NOTICE="$TMP_DIR/store-policy-incomplete-notice.md"
printf 'PocketMind stores user-entered chat text locally.\n' > "$STORE_POLICY_INCOMPLETE_NOTICE"
expect_failure \
  "store policy verifier rejects data safety privacy notice mismatch" \
  env PRIVACY_NOTICE_FILE="$STORE_POLICY_INCOMPLETE_NOTICE" MANIFEST_FILE="$STORE_POLICY_MANIFEST" \
  scripts/verify_store_policy_record.sh --file "$STORE_POLICY_APPROVED" --report "$ARTIFACT_DIR/store-policy-notice-mismatch.properties"
assert_report_contains_text "$ARTIFACT_DIR/store-policy-notice-mismatch.properties" "privacy-notice-mismatch"
STORE_POLICY_BAD_POSITIONING="$TMP_DIR/store-policy-bad-positioning.json"
sed \
  -e 's#Privacy-first pocket AI: local, optional remote, confirmed actions.#Local-first AI assistant.#' \
  -e 's#PocketMind is a privacy-first pocket AI assistant for Android: it is locally usable with downloaded or imported models, can optionally use user-configured remote multimodal models for text and image requests, and only executes device actions after explicit confirmation. ##' \
  "$STORE_POLICY_APPROVED" > "$STORE_POLICY_BAD_POSITIONING"
expect_failure \
  "store policy verifier rejects app listing without product positioning" \
  env PRIVACY_NOTICE_FILE="$STORE_POLICY_NOTICE" MANIFEST_FILE="$STORE_POLICY_MANIFEST" \
  scripts/verify_store_policy_record.sh --file "$STORE_POLICY_BAD_POSITIONING" --report "$ARTIFACT_DIR/store-policy-bad-positioning.properties"
assert_report_contains_text "$ARTIFACT_DIR/store-policy-bad-positioning.properties" "app-listing-privacy-first-missing"
assert_report_contains_text "$ARTIFACT_DIR/store-policy-bad-positioning.properties" "app-listing-remote-multimodal-missing"
assert_report_contains_text "$ARTIFACT_DIR/store-policy-bad-positioning.properties" "app-listing-confirmed-actions-missing"
STORE_POLICY_BAD_SHA="$TMP_DIR/store-policy-bad-sha.json"
sed 's/"privacyNoticeSha256": "'"$STORE_POLICY_NOTICE_SHA"'"/"privacyNoticeSha256": "0000000000000000000000000000000000000000000000000000000000000000"/' "$STORE_POLICY_APPROVED" > "$STORE_POLICY_BAD_SHA"
expect_failure \
  "store policy verifier rejects privacy notice sha mismatch" \
  env PRIVACY_NOTICE_FILE="$STORE_POLICY_NOTICE" MANIFEST_FILE="$STORE_POLICY_MANIFEST" \
  scripts/verify_store_policy_record.sh --file "$STORE_POLICY_BAD_SHA" --report "$ARTIFACT_DIR/store-policy-bad-sha.properties"
assert_report_contains "$ARTIFACT_DIR/store-policy-bad-sha.properties" "status=failed"
STORE_POLICY_BAD_REVIEW_EVIDENCE_SHA="$TMP_DIR/store-policy-bad-review-evidence-sha.json"
sed 's/"evidenceSha256": "'"$STORE_POLICY_REVIEW_EVIDENCE_SHA"'"/"evidenceSha256": "0000000000000000000000000000000000000000000000000000000000000000"/' "$STORE_POLICY_APPROVED" > "$STORE_POLICY_BAD_REVIEW_EVIDENCE_SHA"
expect_failure \
  "store policy verifier rejects review evidence sha mismatch" \
  env PRIVACY_NOTICE_FILE="$STORE_POLICY_NOTICE" MANIFEST_FILE="$STORE_POLICY_MANIFEST" \
  scripts/verify_store_policy_record.sh --file "$STORE_POLICY_BAD_REVIEW_EVIDENCE_SHA" --report "$ARTIFACT_DIR/store-policy-bad-review-evidence-sha.properties"
assert_report_contains_text "$ARTIFACT_DIR/store-policy-bad-review-evidence-sha.properties" "review-evidence-sha-mismatch"
STORE_POLICY_PENDING_REVIEW_EVIDENCE="$TMP_DIR/store-policy-pending-review.properties"
cat > "$STORE_POLICY_PENDING_REVIEW_EVIDENCE" <<STORE_POLICY_PENDING_REVIEW_EVIDENCE_PROPERTIES
status=pending
target=store-policy-review-candidate-evidence
privacyNoticePath=$STORE_POLICY_NOTICE
privacyNoticeSha256=$STORE_POLICY_NOTICE_SHA
scope=store-listing-data-safety-permissions-special-access
requiredDecision=approved
approvalStatus=not-approved
STORE_POLICY_PENDING_REVIEW_EVIDENCE_PROPERTIES
STORE_POLICY_PENDING_REVIEW_EVIDENCE_SHA="$(shasum -a 256 "$STORE_POLICY_PENDING_REVIEW_EVIDENCE" | awk '{print $1}')"
STORE_POLICY_PENDING_REVIEW_RECORD="$TMP_DIR/store-policy-pending-review-evidence.json"
python3 - "$STORE_POLICY_APPROVED" "$STORE_POLICY_PENDING_REVIEW_RECORD" "$STORE_POLICY_PENDING_REVIEW_EVIDENCE" "$STORE_POLICY_PENDING_REVIEW_EVIDENCE_SHA" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
record["review"]["evidencePath"] = sys.argv[3]
record["review"]["evidenceSha256"] = sys.argv[4]
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "store policy verifier rejects pending review evidence content" \
  env PRIVACY_NOTICE_FILE="$STORE_POLICY_NOTICE" MANIFEST_FILE="$STORE_POLICY_MANIFEST" \
  scripts/verify_store_policy_record.sh --file "$STORE_POLICY_PENDING_REVIEW_RECORD" --report "$ARTIFACT_DIR/store-policy-pending-review-evidence.properties"
assert_report_contains_text "$ARTIFACT_DIR/store-policy-pending-review-evidence.properties" "review-evidence-status-not-approved"
assert_report_contains_text "$ARTIFACT_DIR/store-policy-pending-review-evidence.properties" "review-evidence-approval-status-not-approved"
STORE_POLICY_BAD_REVIEW_NOTICE_EVIDENCE="$TMP_DIR/store-policy-bad-review-notice.properties"
sed "s/privacyNoticeSha256=$STORE_POLICY_NOTICE_SHA/privacyNoticeSha256=0000000000000000000000000000000000000000000000000000000000000000/" \
  "$STORE_POLICY_REVIEW_EVIDENCE" > "$STORE_POLICY_BAD_REVIEW_NOTICE_EVIDENCE"
STORE_POLICY_BAD_REVIEW_NOTICE_EVIDENCE_SHA="$(shasum -a 256 "$STORE_POLICY_BAD_REVIEW_NOTICE_EVIDENCE" | awk '{print $1}')"
STORE_POLICY_BAD_REVIEW_NOTICE_RECORD="$TMP_DIR/store-policy-bad-review-notice-evidence.json"
python3 - "$STORE_POLICY_APPROVED" "$STORE_POLICY_BAD_REVIEW_NOTICE_RECORD" "$STORE_POLICY_BAD_REVIEW_NOTICE_EVIDENCE" "$STORE_POLICY_BAD_REVIEW_NOTICE_EVIDENCE_SHA" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
record["review"]["evidencePath"] = sys.argv[3]
record["review"]["evidenceSha256"] = sys.argv[4]
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "store policy verifier rejects review evidence notice sha mismatch" \
  env PRIVACY_NOTICE_FILE="$STORE_POLICY_NOTICE" MANIFEST_FILE="$STORE_POLICY_MANIFEST" \
  scripts/verify_store_policy_record.sh --file "$STORE_POLICY_BAD_REVIEW_NOTICE_RECORD" --report "$ARTIFACT_DIR/store-policy-bad-review-notice-evidence.properties"
assert_report_contains_text "$ARTIFACT_DIR/store-policy-bad-review-notice-evidence.properties" "review-evidence-privacy-notice-sha-mismatch"
STORE_POLICY_EXTRA_PERMISSION="$TMP_DIR/store-policy-extra-permission.json"
sed 's/"name": "android.permission.RECORD_AUDIO"/"name": "android.permission.READ_CONTACTS"/' "$STORE_POLICY_APPROVED" > "$STORE_POLICY_EXTRA_PERMISSION"
expect_failure \
  "store policy verifier rejects manifest permission mismatch" \
  env PRIVACY_NOTICE_FILE="$STORE_POLICY_NOTICE" MANIFEST_FILE="$STORE_POLICY_MANIFEST" \
  scripts/verify_store_policy_record.sh --file "$STORE_POLICY_EXTRA_PERMISSION" --report "$ARTIFACT_DIR/store-policy-permission-mismatch.properties"
assert_report_contains "$ARTIFACT_DIR/store-policy-permission-mismatch.properties" "status=failed"
STORE_POLICY_PLACEHOLDER_CONTACT="$TMP_DIR/store-policy-placeholder-contact.json"
sed \
  -e 's#release@pocketmind.app#release@example.com#' \
  -e 's#https://pocketmind.app/privacy#https://example.com/privacy#' \
  "$STORE_POLICY_APPROVED" > "$STORE_POLICY_PLACEHOLDER_CONTACT"
expect_failure \
  "store policy verifier rejects placeholder contact and privacy URL" \
  env PRIVACY_NOTICE_FILE="$STORE_POLICY_NOTICE" MANIFEST_FILE="$STORE_POLICY_MANIFEST" \
  scripts/verify_store_policy_record.sh --file "$STORE_POLICY_PLACEHOLDER_CONTACT" --report "$ARTIFACT_DIR/store-policy-placeholder-contact.properties"
assert_report_contains_text "$ARTIFACT_DIR/store-policy-placeholder-contact.properties" "contact-email-placeholder"
assert_report_contains_text "$ARTIFACT_DIR/store-policy-placeholder-contact.properties" "privacy-policy-url-placeholder"

OPERATIONS_PENDING="$TMP_DIR/release-operations-pending.json"
OPERATIONS_APPROVED="$TMP_DIR/release-operations-approved.json"
OPERATIONS_DATE="$(date +%F)"
OPERATIONS_MONITORING_EVIDENCE="$TMP_DIR/release-operations-monitoring.properties"
OPERATIONS_MONITORING_PENDING_EVIDENCE="$TMP_DIR/release-operations-monitoring-pending.properties"
OPERATIONS_MONITORING_PENDING_RECORD="$TMP_DIR/release-operations-monitoring-pending.json"
OPERATIONS_SMOKE_EVIDENCE="$TMP_DIR/release-operations-smoke.properties"
OPERATIONS_SMOKE_FAILED_EVIDENCE="$TMP_DIR/release-operations-smoke-failed.properties"
OPERATIONS_SMOKE_FAILED_RECORD="$TMP_DIR/release-operations-smoke-failed.json"
OPERATIONS_SMOKE_BAD_LOGCAT_EVIDENCE="$TMP_DIR/release-operations-smoke-bad-logcat.properties"
OPERATIONS_SMOKE_BAD_LOGCAT_RECORD="$TMP_DIR/release-operations-smoke-bad-logcat.json"
OPERATIONS_SMOKE_DEVICE_REPORT="$TMP_DIR/release-operations-smoke-device.properties"
OPERATIONS_SMOKE_INSTRUMENTATION="$TMP_DIR/release-operations-smoke-instrumentation.txt"
OPERATIONS_SMOKE_LOGCAT="$TMP_DIR/release-operations-smoke-logcat.txt"
OPERATIONS_SMOKE_ANR_LOGCAT="$TMP_DIR/release-operations-smoke-anr-logcat.txt"
OPERATIONS_SMOKE_ANR_REPORT="$TMP_DIR/release-operations-smoke-anr.properties"
OPERATIONS_SMOKE_JAVA_CRASH_LOGCAT="$TMP_DIR/release-operations-smoke-java-crash-logcat.txt"
OPERATIONS_SMOKE_JAVA_CRASH_REPORT="$TMP_DIR/release-operations-smoke-java-crash.properties"
OPERATIONS_SMOKE_INSTRUMENTATION_CRASH="$TMP_DIR/release-operations-smoke-instrumentation-crash.txt"
OPERATIONS_SMOKE_INSTRUMENTATION_CRASH_REPORT="$TMP_DIR/release-operations-smoke-instrumentation-crash.properties"
OPERATIONS_ROLLBACK_EVIDENCE="$TMP_DIR/release-operations-rollback.properties"
OPERATIONS_ROLLBACK_PENDING_EVIDENCE="$TMP_DIR/release-operations-rollback-pending.properties"
OPERATIONS_ROLLBACK_PENDING_RECORD="$TMP_DIR/release-operations-rollback-pending.json"
OPERATIONS_CI_LOCAL_EVIDENCE="$TMP_DIR/release-operations-ci-local.properties"
OPERATIONS_CI_CONNECTED_EVIDENCE="$TMP_DIR/release-operations-ci-connected.properties"
OPERATIONS_CI_API_MATRIX_EVIDENCE="$TMP_DIR/release-operations-ci-api-matrix.properties"
OPERATIONS_CI_API_MATRIX_WEAK_EVIDENCE="$TMP_DIR/release-operations-ci-api-matrix-weak.properties"
OPERATIONS_CI_ARTIFACT_EVIDENCE="$TMP_DIR/release-operations-ci-artifact-archive.properties"
OPERATIONS_CI_SIGNING_EVIDENCE="$TMP_DIR/release-operations-ci-signing.properties"
OPERATIONS_CI_SIGNING_WEAK_EVIDENCE="$TMP_DIR/release-operations-ci-signing-weak.properties"
OPERATIONS_CI_CONNECTED_WEAK_EVIDENCE="$TMP_DIR/release-operations-ci-connected-weak.properties"
OPERATIONS_COMMIT_SHA="$(git rev-parse HEAD)"
OPERATIONS_FAKE_SHA_1="1111111111111111111111111111111111111111111111111111111111111111"
OPERATIONS_FAKE_SHA_2="2222222222222222222222222222222222222222222222222222222222222222"
OPERATIONS_FAKE_SHA_3="3333333333333333333333333333333333333333333333333333333333333333"
OPERATIONS_FAKE_SHA_4="4444444444444444444444444444444444444444444444444444444444444444"
cat > "$OPERATIONS_MONITORING_EVIDENCE" <<OPERATIONS_MONITORING_EVIDENCE_PROPERTIES
status=passed
target=release-monitoring-evidence
operationsRecordField=monitoring.evidence
owner=Release Owner
signalSources=Android Vitals,Internal dogfood feedback
first24HoursWatcher=Launch Watcher
crashFreeRateThresholdPercent=99.5
anrRateThresholdPercent=1.0
privacyReviewedForCrashSdk=true
OPERATIONS_MONITORING_EVIDENCE_PROPERTIES
cat > "$OPERATIONS_CI_LOCAL_EVIDENCE" <<OPERATIONS_CI_LOCAL_EVIDENCE_PROPERTIES
status=passed
target=ci-local-verification
workflow=Android Verification
job=verify
runId=123456
commitSha=$OPERATIONS_COMMIT_SHA
command=scripts/verify_local.sh
OPERATIONS_CI_LOCAL_EVIDENCE_PROPERTIES
cat > "$OPERATIONS_CI_CONNECTED_EVIDENCE" <<OPERATIONS_CI_CONNECTED_EVIDENCE_PROPERTIES
status=passed
exit_code=0
target=regression-emulator
failedTarget=
reason=
clean_device=1
source_android_test_count=20
expected_android_test_count=20
actual_android_test_count=20
serial=emulator-5554
api_level=36
abi=arm64-v8a
avd=pocketmind_ci_api36_arm64
instrumentation_output_file=$TMP_DIR/ci-instrumentation.txt
device_report_file=$TMP_DIR/ci-device-verification.properties
OPERATIONS_CI_CONNECTED_EVIDENCE_PROPERTIES
OPERATIONS_CI_API_MATRIX_READINESS="$TMP_DIR/ci-api-matrix-readiness.properties"
cat > "$OPERATIONS_CI_API_MATRIX_READINESS" <<OPERATIONS_CI_API_MATRIX_READINESS_PROPERTIES
status=passed
target=emulator-api-matrix-readiness
requiredApis=28,32,33,34,36
installedSystemImageApis=28,32,33,34,36
availableAvdApis=28,32,33,34,36
OPERATIONS_CI_API_MATRIX_READINESS_PROPERTIES
OPERATIONS_CI_API_MATRIX_LINES=()
for api_level in 28 32 33 34 36; do
  api_report="$TMP_DIR/ci-api-${api_level}-regression.properties"
  cat > "$api_report" <<OPERATIONS_CI_API_REGRESSION_PROPERTIES
status=passed
target=regression-emulator
clean_device=1
source_android_test_count=20
expected_android_test_count=20
actual_android_test_count=20
serial=emulator-${api_level}
api_level=${api_level}
abi=arm64-v8a
avd=pocketmind_ci_api${api_level}_arm64_v8a
instrumentation_output_file=$TMP_DIR/ci-api-${api_level}-instrumentation.txt
device_report_file=$TMP_DIR/ci-api-${api_level}-device-verification.properties
OPERATIONS_CI_API_REGRESSION_PROPERTIES
  api_report_sha="$(shasum -a 256 "$api_report" | awk '{print $1}')"
  OPERATIONS_CI_API_MATRIX_LINES+=("api${api_level}Status=passed")
  OPERATIONS_CI_API_MATRIX_LINES+=("api${api_level}Avd=pocketmind_ci_api${api_level}_arm64_v8a")
  OPERATIONS_CI_API_MATRIX_LINES+=("api${api_level}ReportFile=$api_report")
  OPERATIONS_CI_API_MATRIX_LINES+=("api${api_level}ReportSha256=$api_report_sha")
done
{
  printf 'status=passed\n'
  printf 'target=regression-emulator-api-matrix\n'
  printf 'failedTarget=\n'
  printf 'reason=\n'
  printf 'artifactDir=%s\n' "$TMP_DIR/ci-api-matrix"
  printf 'requiredApis=28,32,33,34,36\n'
  printf 'tag=google_apis\n'
  printf 'abi=arm64-v8a\n'
  printf 'readinessReportFile=%s\n' "$OPERATIONS_CI_API_MATRIX_READINESS"
  printf 'passedApis=28,32,33,34,36\n'
  printf 'failedApis=\n'
  printf '%s\n' "${OPERATIONS_CI_API_MATRIX_LINES[@]}"
} > "$OPERATIONS_CI_API_MATRIX_EVIDENCE"
cat > "$OPERATIONS_CI_API_MATRIX_WEAK_EVIDENCE" <<OPERATIONS_CI_API_MATRIX_WEAK_EVIDENCE_PROPERTIES
status=passed
target=regression-emulator-api-matrix
requiredApis=28,32
passedApis=28
failedApis=32
readinessReportFile=$OPERATIONS_CI_API_MATRIX_READINESS
OPERATIONS_CI_API_MATRIX_WEAK_EVIDENCE_PROPERTIES
OPERATIONS_CI_ARTIFACT_SCAN_REPORT="$TMP_DIR/release-operations-ci-artifact-scan.properties"
cat > "$OPERATIONS_CI_ARTIFACT_SCAN_REPORT" <<OPERATIONS_CI_ARTIFACT_SCAN_PROPERTIES
status=passed
target=android-artifact-scan
artifactSchema=AndroidArtifactScanReport/v1
owner=release-engineering
recordedAt=2026-06-06T00:00:00Z
command=scripts/scan_android_artifacts.sh --aab app/build/outputs/bundle/release/app-release.aab --apk app/build/outputs/apk/release/app-release-unsigned.apk --report $OPERATIONS_CI_ARTIFACT_SCAN_REPORT
reproduciblePath=$OPERATIONS_CI_ARTIFACT_SCAN_REPORT
reason=approved
artifactCount=2
findingCount=0
artifact1Path=app/build/outputs/bundle/release/app-release.aab
artifact1Sha256=$OPERATIONS_FAKE_SHA_1
artifact1SizeBytes=123
artifact2Path=app/build/outputs/apk/release/app-release-unsigned.apk
artifact2Sha256=$OPERATIONS_FAKE_SHA_2
artifact2SizeBytes=456
OPERATIONS_CI_ARTIFACT_SCAN_PROPERTIES
OPERATIONS_CI_ARTIFACT_SCAN_SHA="$(shasum -a 256 "$OPERATIONS_CI_ARTIFACT_SCAN_REPORT" | awk '{print $1}')"
cat > "$OPERATIONS_CI_ARTIFACT_EVIDENCE" <<OPERATIONS_CI_ARTIFACT_EVIDENCE_PROPERTIES
status=passed
target=ci-release-artifact-archive
artifactSchema=ReleaseArtifactArchiveEvidence/v1
owner=release-engineering
recordedAt=2026-06-06T00:00:00Z
command=scripts/archive_release_artifacts.sh
reproduciblePath=$OPERATIONS_CI_ARTIFACT_EVIDENCE
workflow=Android Verification
job=release-artifact-archive
runId=123456
commitSha=$OPERATIONS_COMMIT_SHA
artifactUploadName=android-release-artifacts
aabPath=app/build/outputs/bundle/release/app-release.aab
aabSha256=$OPERATIONS_FAKE_SHA_1
apkPath=app/build/outputs/apk/release/app-release-unsigned.apk
apkSha256=$OPERATIONS_FAKE_SHA_2
mappingPath=app/build/outputs/mapping/release/mapping.txt
mappingSha256=$OPERATIONS_FAKE_SHA_3
artifactScanReport=$OPERATIONS_CI_ARTIFACT_SCAN_REPORT
artifactScanReportSha256=$OPERATIONS_CI_ARTIFACT_SCAN_SHA
artifactScanStatus=passed
OPERATIONS_CI_ARTIFACT_EVIDENCE_PROPERTIES
OPERATIONS_CI_SIGNING_ARTIFACT_SCAN_REPORT="$TMP_DIR/release-operations-ci-signing-artifact-scan.properties"
cat > "$OPERATIONS_CI_SIGNING_ARTIFACT_SCAN_REPORT" <<OPERATIONS_CI_SIGNING_ARTIFACT_SCAN_PROPERTIES
status=passed
target=android-artifact-scan
artifactSchema=AndroidArtifactScanReport/v1
owner=release-engineering
recordedAt=2026-06-06T00:00:00Z
command=scripts/scan_android_artifacts.sh --require-signed --aab app/build/outputs/bundle/release/app-release-signed.aab --report $OPERATIONS_CI_SIGNING_ARTIFACT_SCAN_REPORT
reproduciblePath=$OPERATIONS_CI_SIGNING_ARTIFACT_SCAN_REPORT
reason=approved
artifactCount=1
findingCount=0
artifact1Path=app/build/outputs/bundle/release/app-release-signed.aab
artifact1Sha256=$OPERATIONS_FAKE_SHA_1
artifact1SizeBytes=123
OPERATIONS_CI_SIGNING_ARTIFACT_SCAN_PROPERTIES
OPERATIONS_CI_SIGNING_ARTIFACT_SCAN_SHA="$(shasum -a 256 "$OPERATIONS_CI_SIGNING_ARTIFACT_SCAN_REPORT" | awk '{print $1}')"
cat > "$OPERATIONS_CI_SIGNING_EVIDENCE" <<OPERATIONS_CI_SIGNING_EVIDENCE_PROPERTIES
status=passed
exit_code=0
target=release-signing
artifactSchema=ReleaseSigningReport/v1
owner=release-engineering
recordedAt=2026-06-06T00:00:00Z
command=scripts/sign_release_artifacts.sh
reproduciblePath=$OPERATIONS_CI_SIGNING_EVIDENCE
workflow=Android Verification
job=protected-signing
runId=123456
commitSha=$OPERATIONS_COMMIT_SHA
signingMode=production
allowDebugKeystore=0
requireAab=1
expectedSigningCertSha256=$OPERATIONS_FAKE_SHA_4
signedAab=app/build/outputs/bundle/release/app-release-signed.aab
signedAabSha256=$OPERATIONS_FAKE_SHA_1
artifactScanReport=$OPERATIONS_CI_SIGNING_ARTIFACT_SCAN_REPORT
artifactScanReportSha256=$OPERATIONS_CI_SIGNING_ARTIFACT_SCAN_SHA
artifactScanStatus=passed
OPERATIONS_CI_SIGNING_EVIDENCE_PROPERTIES
cat > "$OPERATIONS_CI_SIGNING_WEAK_EVIDENCE" <<OPERATIONS_CI_SIGNING_WEAK_PROPERTIES
status=passed
exit_code=0
target=release-signing
workflow=Android Verification
job=protected-signing
runId=123456
commitSha=$OPERATIONS_COMMIT_SHA
signingMode=production
allowDebugKeystore=0
requireAab=1
expectedSigningCertSha256=$OPERATIONS_FAKE_SHA_4
signedAab=app/build/outputs/bundle/release/app-release-signed.aab
signedAabSha256=$OPERATIONS_FAKE_SHA_1
artifactScanReport=$OPERATIONS_CI_SIGNING_ARTIFACT_SCAN_REPORT
artifactScanStatus=passed
OPERATIONS_CI_SIGNING_WEAK_PROPERTIES
cat > "$OPERATIONS_CI_CONNECTED_WEAK_EVIDENCE" <<OPERATIONS_CI_CONNECTED_WEAK_EVIDENCE_PROPERTIES
status=passed
target=ci-local-verification
command=scripts/verify_local.sh
OPERATIONS_CI_CONNECTED_WEAK_EVIDENCE_PROPERTIES
cat > "$OPERATIONS_SMOKE_INSTRUMENTATION" <<'OPERATIONS_SMOKE_INSTRUMENTATION_TXT'
INSTRUMENTATION_STATUS: numtests=3
OK (3 tests)
INSTRUMENTATION_CODE: -1
OPERATIONS_SMOKE_INSTRUMENTATION_TXT
cat > "$OPERATIONS_SMOKE_DEVICE_REPORT" <<OPERATIONS_SMOKE_DEVICE_REPORT_TXT
status=passed
target=device
serial=emulator-5554
api_level=36
abi=arm64-v8a
instrumentation=passed
instrumentation_test_count=3
instrumentation_output_file=$OPERATIONS_SMOKE_INSTRUMENTATION
logcat_file=$OPERATIONS_SMOKE_LOGCAT
OPERATIONS_SMOKE_DEVICE_REPORT_TXT
cat > "$OPERATIONS_SMOKE_LOGCAT" <<'OPERATIONS_SMOKE_LOGCAT_TXT'
06-06 20:00:00.000  1000  1000 I ActivityTaskManager: Displayed com.bytedance.zgx.pocketmind/.MainActivity
06-06 20:00:01.000  1000  1000 I LiteRT: nativeCheckLoaded returned true
OPERATIONS_SMOKE_LOGCAT_TXT
expect_success \
  "crash/ANR smoke collector accepts clean instrumentation and logcat" \
  scripts/collect_crash_anr_smoke_evidence.sh \
    --device-report "$OPERATIONS_SMOKE_DEVICE_REPORT" \
    --instrumentation-output "$OPERATIONS_SMOKE_INSTRUMENTATION" \
    --report "$OPERATIONS_SMOKE_EVIDENCE" \
    --window "2026-06-06 internal smoke" \
    --track internal_testing
assert_report_contains "$OPERATIONS_SMOKE_EVIDENCE" "status=passed"
assert_report_contains "$OPERATIONS_SMOKE_EVIDENCE" "operationsRecordField=crashAnrSmoke.evidence"
assert_report_contains "$OPERATIONS_SMOKE_EVIDENCE" "logcatAnalyzed=true"
assert_report_contains "$OPERATIONS_SMOKE_EVIDENCE" "instrumentationFailureSignalCount=0"
assert_report_contains "$OPERATIONS_SMOKE_EVIDENCE" "noLaunchCrash=true"
assert_report_contains "$OPERATIONS_SMOKE_EVIDENCE" "noInstallCrash=true"
assert_report_contains "$OPERATIONS_SMOKE_EVIDENCE" "noCrashLoop=true"
assert_report_contains "$OPERATIONS_SMOKE_EVIDENCE" "noFatalNativeLiteRtLmFailure=true"
assert_report_contains "$OPERATIONS_SMOKE_EVIDENCE" "noReproducibleAnr=true"
cat > "$OPERATIONS_SMOKE_ANR_LOGCAT" <<'OPERATIONS_SMOKE_ANR_LOGCAT_TXT'
06-06 20:00:02.000  1000  1000 E ActivityManager: ANR in com.bytedance.zgx.pocketmind
OPERATIONS_SMOKE_ANR_LOGCAT_TXT
expect_failure \
  "crash/ANR smoke collector rejects ANR logcat signal" \
  scripts/collect_crash_anr_smoke_evidence.sh \
    --device-report "$OPERATIONS_SMOKE_DEVICE_REPORT" \
    --instrumentation-output "$OPERATIONS_SMOKE_INSTRUMENTATION" \
    --logcat "$OPERATIONS_SMOKE_ANR_LOGCAT" \
    --report "$OPERATIONS_SMOKE_ANR_REPORT"
assert_report_contains "$OPERATIONS_SMOKE_ANR_REPORT" "status=failed"
assert_report_contains "$OPERATIONS_SMOKE_ANR_REPORT" "noReproducibleAnr=false"
assert_report_contains_text "$OPERATIONS_SMOKE_ANR_REPORT" "anr-signal-detected"
cat > "$OPERATIONS_SMOKE_JAVA_CRASH_LOGCAT" <<'OPERATIONS_SMOKE_JAVA_CRASH_LOGCAT_TXT'
06-06 20:00:03.000  1000  1000 E AndroidRuntime: FATAL EXCEPTION: main
06-06 20:00:03.001  1000  1000 E AndroidRuntime: Process: com.bytedance.zgx.pocketmind, PID: 12345
OPERATIONS_SMOKE_JAVA_CRASH_LOGCAT_TXT
expect_failure \
  "crash/ANR smoke collector treats one Java crash as one launch crash" \
  scripts/collect_crash_anr_smoke_evidence.sh \
    --device-report "$OPERATIONS_SMOKE_DEVICE_REPORT" \
    --instrumentation-output "$OPERATIONS_SMOKE_INSTRUMENTATION" \
    --logcat "$OPERATIONS_SMOKE_JAVA_CRASH_LOGCAT" \
    --report "$OPERATIONS_SMOKE_JAVA_CRASH_REPORT"
assert_report_contains "$OPERATIONS_SMOKE_JAVA_CRASH_REPORT" "status=failed"
assert_report_contains "$OPERATIONS_SMOKE_JAVA_CRASH_REPORT" "crashSignalCount=1"
assert_report_contains "$OPERATIONS_SMOKE_JAVA_CRASH_REPORT" "noLaunchCrash=false"
assert_report_contains "$OPERATIONS_SMOKE_JAVA_CRASH_REPORT" "noCrashLoop=true"
assert_report_contains_text "$OPERATIONS_SMOKE_JAVA_CRASH_REPORT" "crash-signal-detected"
cat > "$OPERATIONS_SMOKE_INSTRUMENTATION_CRASH" <<'OPERATIONS_SMOKE_INSTRUMENTATION_CRASH_TXT'
INSTRUMENTATION_RESULT: shortMsg=Process crashed.
INSTRUMENTATION_CODE: 0
OPERATIONS_SMOKE_INSTRUMENTATION_CRASH_TXT
expect_failure \
  "crash/ANR smoke collector rejects instrumentation process crash signal" \
  scripts/collect_crash_anr_smoke_evidence.sh \
    --device-report "$OPERATIONS_SMOKE_DEVICE_REPORT" \
    --instrumentation-output "$OPERATIONS_SMOKE_INSTRUMENTATION_CRASH" \
    --logcat "$OPERATIONS_SMOKE_LOGCAT" \
    --report "$OPERATIONS_SMOKE_INSTRUMENTATION_CRASH_REPORT"
assert_report_contains "$OPERATIONS_SMOKE_INSTRUMENTATION_CRASH_REPORT" "status=failed"
assert_report_contains "$OPERATIONS_SMOKE_INSTRUMENTATION_CRASH_REPORT" "noLaunchCrash=false"
assert_report_contains_text "$OPERATIONS_SMOKE_INSTRUMENTATION_CRASH_REPORT" "instrumentation-crash-signal-detected"
cat > "$OPERATIONS_ROLLBACK_EVIDENCE" <<OPERATIONS_ROLLBACK_EVIDENCE_PROPERTIES
status=passed
target=release-rollback-evidence
operationsRecordField=rollback.evidence
owner=Release Owner
decisionChannel=#pocketmind-release
criteria=install failure,crash loop,model download verification failure,privacy boundary failure,critical tool execution regression
firstStagedRolloutAction=Halt rollout, keep collecting Android Vitals and user reports, then decide whether to resume, replace, or ship a fixed build.
playVersionCodePolicy=Any replacement artifact must use a higher versionCode; Play cannot ordinary-update users to a lower versionCode.
modelManifestRollbackPath=Revert model download metadata when supported; otherwise ship a fixed APK with a higher versionCode.
userDataCompatibility=Room migrations are forward-only, so downgrade is unsupported unless explicitly tested.
previousKnownGoodStatus=not_applicable_initial_release
previousKnownGoodReleaseNotes=Initial release has no previous production artifact.
OPERATIONS_ROLLBACK_EVIDENCE_PROPERTIES
OPERATIONS_MONITORING_SHA="$(shasum -a 256 "$OPERATIONS_MONITORING_EVIDENCE" | awk '{print $1}')"
OPERATIONS_SMOKE_SHA="$(shasum -a 256 "$OPERATIONS_SMOKE_EVIDENCE" | awk '{print $1}')"
OPERATIONS_ROLLBACK_SHA="$(shasum -a 256 "$OPERATIONS_ROLLBACK_EVIDENCE" | awk '{print $1}')"
OPERATIONS_CI_LOCAL_SHA="$(shasum -a 256 "$OPERATIONS_CI_LOCAL_EVIDENCE" | awk '{print $1}')"
OPERATIONS_CI_CONNECTED_SHA="$(shasum -a 256 "$OPERATIONS_CI_CONNECTED_EVIDENCE" | awk '{print $1}')"
OPERATIONS_CI_API_MATRIX_SHA="$(shasum -a 256 "$OPERATIONS_CI_API_MATRIX_EVIDENCE" | awk '{print $1}')"
OPERATIONS_CI_API_MATRIX_WEAK_SHA="$(shasum -a 256 "$OPERATIONS_CI_API_MATRIX_WEAK_EVIDENCE" | awk '{print $1}')"
OPERATIONS_CI_ARTIFACT_SHA="$(shasum -a 256 "$OPERATIONS_CI_ARTIFACT_EVIDENCE" | awk '{print $1}')"
OPERATIONS_CI_SIGNING_SHA="$(shasum -a 256 "$OPERATIONS_CI_SIGNING_EVIDENCE" | awk '{print $1}')"
OPERATIONS_CI_SIGNING_WEAK_SHA="$(shasum -a 256 "$OPERATIONS_CI_SIGNING_WEAK_EVIDENCE" | awk '{print $1}')"
OPERATIONS_CI_CONNECTED_WEAK_SHA="$(shasum -a 256 "$OPERATIONS_CI_CONNECTED_WEAK_EVIDENCE" | awk '{print $1}')"
cat > "$OPERATIONS_PENDING" <<'OPERATIONS_PENDING_JSON'
{
  "version": 1,
  "status": "pending_operations_review"
}
OPERATIONS_PENDING_JSON
expect_failure \
  "release operations verifier rejects pending records" \
  scripts/verify_release_operations_record.sh --file "$OPERATIONS_PENDING" --report "$ARTIFACT_DIR/release-operations-pending.properties"
assert_report_contains "$ARTIFACT_DIR/release-operations-pending.properties" "status=failed"
assert_release_verifier_report_schema \
  "$ARTIFACT_DIR/release-operations-pending.properties" \
  "ReleaseOperationsVerification/v1"
OPERATIONS_SPACED_PENDING="$TMP_DIR/release operations pending.json"
OPERATIONS_SPACED_REPORT="$ARTIFACT_DIR/release operations spaced command.properties"
cat > "$OPERATIONS_SPACED_PENDING" <<'OPERATIONS_SPACED_PENDING_JSON'
{
  "version": 1,
  "status": "pending_operations_review"
}
OPERATIONS_SPACED_PENDING_JSON
expect_failure \
  "release operations report command preserves spaced argv" \
  scripts/verify_release_operations_record.sh --file "$OPERATIONS_SPACED_PENDING" --report "$OPERATIONS_SPACED_REPORT"
assert_report_command_argv \
  "$OPERATIONS_SPACED_REPORT" \
  scripts/verify_release_operations_record.sh \
  --file "$OPERATIONS_SPACED_PENDING" \
  --report "$OPERATIONS_SPACED_REPORT"
OPERATIONS_MALFORMED_REPORT="$ARTIFACT_DIR/release-operations-malformed-argument.properties"
expect_failure \
  "release operations verifier reports malformed arguments when report path is known" \
  scripts/verify_release_operations_record.sh --report "$OPERATIONS_MALFORMED_REPORT" --bad-arg
assert_release_verifier_report_schema \
  "$OPERATIONS_MALFORMED_REPORT" \
  "ReleaseOperationsVerification/v1"
assert_report_contains "$OPERATIONS_MALFORMED_REPORT" "status=failed"
assert_report_contains "$OPERATIONS_MALFORMED_REPORT" "reason=unknown-argument"
assert_report_contains "$OPERATIONS_MALFORMED_REPORT" "failedTarget=argument-parser"
OPERATIONS_MISSING_VALUE_REPORT="$ARTIFACT_DIR/release-operations-missing-value.properties"
expect_failure \
  "release operations verifier reports missing argument values when report path is known" \
  scripts/verify_release_operations_record.sh --report "$OPERATIONS_MISSING_VALUE_REPORT" --file
assert_release_verifier_report_schema \
  "$OPERATIONS_MISSING_VALUE_REPORT" \
  "ReleaseOperationsVerification/v1"
assert_report_contains "$OPERATIONS_MISSING_VALUE_REPORT" "status=failed"
assert_report_contains "$OPERATIONS_MISSING_VALUE_REPORT" "reason=missing-file-argument"
assert_report_contains "$OPERATIONS_MISSING_VALUE_REPORT" "failedTarget=argument-parser"
OPERATIONS_OPTION_VALUE_REPORT="$ARTIFACT_DIR/release-operations-option-like-value.properties"
expect_failure \
  "release operations verifier treats option-like file value as missing" \
  scripts/verify_release_operations_record.sh --report "$OPERATIONS_OPTION_VALUE_REPORT" --file --bad-arg
assert_release_verifier_report_schema \
  "$OPERATIONS_OPTION_VALUE_REPORT" \
  "ReleaseOperationsVerification/v1"
assert_report_contains "$OPERATIONS_OPTION_VALUE_REPORT" "status=failed"
assert_report_contains "$OPERATIONS_OPTION_VALUE_REPORT" "reason=missing-file-argument"
assert_report_contains "$OPERATIONS_OPTION_VALUE_REPORT" "failedTarget=argument-parser"
cat > "$OPERATIONS_APPROVED" <<OPERATIONS_APPROVED_JSON
{
  "version": 1,
  "status": "approved",
  "ci": {
    "owner": "Release Engineering",
    "provider": "GitHub Actions",
    "workflowName": "Android Verification",
    "runId": "123456",
    "commitSha": "$OPERATIONS_COMMIT_SHA",
    "localVerification": {
      "status": "passed",
      "jobName": "verify",
      "evidence": {
        "path": "$OPERATIONS_CI_LOCAL_EVIDENCE",
        "sha256": "$OPERATIONS_CI_LOCAL_SHA"
      }
    },
    "connectedAndroidTests": {
      "status": "passed",
      "jobName": "emulator-regression",
      "evidence": {
        "path": "$OPERATIONS_CI_CONNECTED_EVIDENCE",
        "sha256": "$OPERATIONS_CI_CONNECTED_SHA"
      }
    },
    "apiMatrix": {
      "status": "passed",
      "jobName": "emulator-api-matrix",
      "artifactName": "android-emulator-api-matrix-evidence",
      "evidence": {
        "path": "$OPERATIONS_CI_API_MATRIX_EVIDENCE",
        "sha256": "$OPERATIONS_CI_API_MATRIX_SHA"
      }
    },
    "releaseArtifactArchive": {
      "status": "passed",
      "jobName": "release-artifact-archive",
      "artifactName": "android-release-artifacts",
      "evidence": {
        "path": "$OPERATIONS_CI_ARTIFACT_EVIDENCE",
        "sha256": "$OPERATIONS_CI_ARTIFACT_SHA"
      }
    },
    "protectedSigning": {
      "status": "passed",
      "jobName": "protected-signing",
      "signingEnvironment": "android-production-signing",
      "evidence": {
        "path": "$OPERATIONS_CI_SIGNING_EVIDENCE",
        "sha256": "$OPERATIONS_CI_SIGNING_SHA"
      }
    }
  },
  "monitoring": {
    "owner": "Release Owner",
    "signalSources": ["Android Vitals", "Internal dogfood feedback"],
    "first24HoursWatcher": "Launch Watcher",
    "crashFreeRateThresholdPercent": 99.5,
    "anrRateThresholdPercent": 1.0,
    "privacyReviewedForCrashSdk": true,
    "evidence": {
      "path": "$OPERATIONS_MONITORING_EVIDENCE",
      "sha256": "$OPERATIONS_MONITORING_SHA"
    }
  },
  "crashAnrSmoke": {
    "window": "2026-06-06 internal smoke",
    "track": "internal_testing",
    "noLaunchCrash": true,
    "noInstallCrash": true,
    "noCrashLoop": true,
    "noFatalNativeLiteRtLmFailure": true,
    "noReproducibleAnr": true,
    "failureEvidencePolicy": "Attach logcat, tombstones, and ANR traces for any failure; state no crash or ANR when none were observed.",
    "evidence": {
      "path": "$OPERATIONS_SMOKE_EVIDENCE",
      "sha256": "$OPERATIONS_SMOKE_SHA"
    }
  },
  "rollback": {
    "owner": "Release Owner",
    "decisionChannel": "#pocketmind-release",
    "criteria": [
      "install failure",
      "crash loop",
      "model download verification failure",
      "privacy boundary failure",
      "critical tool execution regression"
    ],
    "firstStagedRolloutAction": "Halt rollout, keep collecting Android Vitals and user reports, then decide whether to resume, replace, or ship a fixed build.",
    "playVersionCodePolicy": "Any replacement artifact must use a higher versionCode; Play cannot ordinary-update users to a lower versionCode.",
    "modelManifestRollbackPath": "Revert model download metadata when supported; otherwise ship a fixed APK with a higher versionCode.",
    "userDataCompatibility": "Room migrations are forward-only, so downgrade is unsupported unless explicitly tested.",
    "evidence": {
      "path": "$OPERATIONS_ROLLBACK_EVIDENCE",
      "sha256": "$OPERATIONS_ROLLBACK_SHA"
    },
    "previousKnownGood": {
      "status": "not_applicable_initial_release",
      "versionCode": 0,
      "versionName": "",
      "gitCommit": "",
      "artifactPath": "",
      "artifactSha256": "",
      "releaseNotes": "Initial release has no previous production artifact."
    }
  },
  "review": {
    "reviewer": "Release Reviewer",
    "reviewDate": "$OPERATIONS_DATE"
  }
}
OPERATIONS_APPROVED_JSON
OPERATIONS_APPROVED_SHA="$(shasum -a 256 "$OPERATIONS_APPROVED" | awk '{print $1}')"
expect_success \
  "release operations verifier accepts approved initial-release record" \
  scripts/verify_release_operations_record.sh --file "$OPERATIONS_APPROVED" --report "$ARTIFACT_DIR/release-operations-approved.properties"
assert_report_contains "$ARTIFACT_DIR/release-operations-approved.properties" "status=passed"
assert_release_verifier_report_schema \
  "$ARTIFACT_DIR/release-operations-approved.properties" \
  "ReleaseOperationsVerification/v1"
assert_report_contains "$ARTIFACT_DIR/release-operations-approved.properties" "operationsRecordSha256=$OPERATIONS_APPROVED_SHA"
expect_success \
  "release operations verifier accepts current release artifact context" \
  env EXPECTED_COMMIT_SHA="$OPERATIONS_COMMIT_SHA" \
  EXPECTED_RELEASE_ARTIFACT_TYPE=aab \
  EXPECTED_RELEASE_ARTIFACT_SHA256="$OPERATIONS_FAKE_SHA_1" \
  EXPECTED_RELEASE_MAPPING_SHA256="$OPERATIONS_FAKE_SHA_3" \
  EXPECTED_SIGNING_CERT_SHA256="$OPERATIONS_FAKE_SHA_4" \
  scripts/verify_release_operations_record.sh --file "$OPERATIONS_APPROVED" --report "$ARTIFACT_DIR/release-operations-current-context.properties"
assert_report_contains "$ARTIFACT_DIR/release-operations-current-context.properties" "status=passed"
sed 's/^status=passed$/status=pending/' "$OPERATIONS_MONITORING_EVIDENCE" > "$OPERATIONS_MONITORING_PENDING_EVIDENCE"
python3 - "$OPERATIONS_APPROVED" "$OPERATIONS_MONITORING_PENDING_RECORD" "$OPERATIONS_MONITORING_PENDING_EVIDENCE" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

record_path, target_path, evidence_path = map(Path, sys.argv[1:])
record = json.loads(record_path.read_text())
record["monitoring"]["evidence"]["path"] = str(evidence_path)
record["monitoring"]["evidence"]["sha256"] = hashlib.sha256(evidence_path.read_bytes()).hexdigest()
target_path.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release operations verifier rejects pending monitoring evidence with matching sha" \
  scripts/verify_release_operations_record.sh --file "$OPERATIONS_MONITORING_PENDING_RECORD" --report "$ARTIFACT_DIR/release-operations-monitoring-pending.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-operations-monitoring-pending.properties" "monitoring-evidence-status-not-passed"
sed 's/^status=passed$/status=pending/' "$OPERATIONS_ROLLBACK_EVIDENCE" > "$OPERATIONS_ROLLBACK_PENDING_EVIDENCE"
python3 - "$OPERATIONS_APPROVED" "$OPERATIONS_ROLLBACK_PENDING_RECORD" "$OPERATIONS_ROLLBACK_PENDING_EVIDENCE" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

record_path, target_path, evidence_path = map(Path, sys.argv[1:])
record = json.loads(record_path.read_text())
record["rollback"]["evidence"]["path"] = str(evidence_path)
record["rollback"]["evidence"]["sha256"] = hashlib.sha256(evidence_path.read_bytes()).hexdigest()
target_path.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release operations verifier rejects pending rollback evidence with matching sha" \
  scripts/verify_release_operations_record.sh --file "$OPERATIONS_ROLLBACK_PENDING_RECORD" --report "$ARTIFACT_DIR/release-operations-rollback-pending.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-operations-rollback-pending.properties" "rollback-evidence-status-not-passed"
expect_failure \
  "release operations verifier rejects stale release artifact context" \
  env EXPECTED_COMMIT_SHA="$OPERATIONS_COMMIT_SHA" \
  EXPECTED_RELEASE_ARTIFACT_TYPE=aab \
  EXPECTED_RELEASE_ARTIFACT_SHA256="$OPERATIONS_FAKE_SHA_2" \
  EXPECTED_RELEASE_MAPPING_SHA256="$OPERATIONS_FAKE_SHA_3" \
  EXPECTED_SIGNING_CERT_SHA256="$OPERATIONS_FAKE_SHA_4" \
  scripts/verify_release_operations_record.sh --file "$OPERATIONS_APPROVED" --report "$ARTIFACT_DIR/release-operations-stale-artifact.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-operations-stale-artifact.properties" "ci-release-artifact-archive-aab-sha-mismatch"
assert_report_contains_text "$ARTIFACT_DIR/release-operations-stale-artifact.properties" "ci-protected-signing-aab-sha-mismatch"
expect_failure \
  "release operations verifier rejects stale commit context" \
  env EXPECTED_COMMIT_SHA=0000000000000000000000000000000000000000 \
  EXPECTED_RELEASE_ARTIFACT_TYPE=aab \
  EXPECTED_RELEASE_ARTIFACT_SHA256="$OPERATIONS_FAKE_SHA_1" \
  EXPECTED_RELEASE_MAPPING_SHA256="$OPERATIONS_FAKE_SHA_3" \
  EXPECTED_SIGNING_CERT_SHA256="$OPERATIONS_FAKE_SHA_4" \
  scripts/verify_release_operations_record.sh --file "$OPERATIONS_APPROVED" --report "$ARTIFACT_DIR/release-operations-stale-commit.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-operations-stale-commit.properties" "ci-commit-sha-mismatch"
expect_failure \
  "release operations verifier rejects stale mapping context" \
  env EXPECTED_COMMIT_SHA="$OPERATIONS_COMMIT_SHA" \
  EXPECTED_RELEASE_ARTIFACT_TYPE=aab \
  EXPECTED_RELEASE_ARTIFACT_SHA256="$OPERATIONS_FAKE_SHA_1" \
  EXPECTED_RELEASE_MAPPING_SHA256="$OPERATIONS_FAKE_SHA_2" \
  EXPECTED_SIGNING_CERT_SHA256="$OPERATIONS_FAKE_SHA_4" \
  scripts/verify_release_operations_record.sh --file "$OPERATIONS_APPROVED" --report "$ARTIFACT_DIR/release-operations-stale-mapping.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-operations-stale-mapping.properties" "ci-release-artifact-archive-mapping-sha-mismatch"
expect_failure \
  "release operations verifier rejects stale signing certificate context" \
  env EXPECTED_COMMIT_SHA="$OPERATIONS_COMMIT_SHA" \
  EXPECTED_RELEASE_ARTIFACT_TYPE=aab \
  EXPECTED_RELEASE_ARTIFACT_SHA256="$OPERATIONS_FAKE_SHA_1" \
  EXPECTED_RELEASE_MAPPING_SHA256="$OPERATIONS_FAKE_SHA_3" \
  EXPECTED_SIGNING_CERT_SHA256="$OPERATIONS_FAKE_SHA_2" \
  scripts/verify_release_operations_record.sh --file "$OPERATIONS_APPROVED" --report "$ARTIFACT_DIR/release-operations-stale-signing-cert.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-operations-stale-signing-cert.properties" "ci-protected-signing-cert-sha-mismatch"
OPERATIONS_CI_STALE_LOCAL_EVIDENCE="$TMP_DIR/release-operations-ci-local-stale-commit.properties"
sed "s/commitSha=$OPERATIONS_COMMIT_SHA/commitSha=0000000000000000000000000000000000000000/" \
  "$OPERATIONS_CI_LOCAL_EVIDENCE" > "$OPERATIONS_CI_STALE_LOCAL_EVIDENCE"
OPERATIONS_CI_STALE_LOCAL_SHA="$(shasum -a 256 "$OPERATIONS_CI_STALE_LOCAL_EVIDENCE" | awk '{print $1}')"
OPERATIONS_CI_STALE_LOCAL_RECORD="$TMP_DIR/release-operations-ci-stale-local.json"
python3 - "$OPERATIONS_APPROVED" "$OPERATIONS_CI_STALE_LOCAL_RECORD" "$OPERATIONS_CI_STALE_LOCAL_EVIDENCE" "$OPERATIONS_CI_STALE_LOCAL_SHA" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
record["ci"]["localVerification"]["evidence"]["path"] = sys.argv[3]
record["ci"]["localVerification"]["evidence"]["sha256"] = sys.argv[4]
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release operations verifier rejects stale local CI evidence commit" \
  scripts/verify_release_operations_record.sh --file "$OPERATIONS_CI_STALE_LOCAL_RECORD" --report "$ARTIFACT_DIR/release-operations-ci-stale-local.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-operations-ci-stale-local.properties" "ci-local-verification-evidence-commit-sha-mismatch"
OPERATIONS_CI_STALE_ARTIFACT_EVIDENCE="$TMP_DIR/release-operations-ci-artifact-stale-run.properties"
sed 's/runId=123456/runId=654321/' "$OPERATIONS_CI_ARTIFACT_EVIDENCE" > "$OPERATIONS_CI_STALE_ARTIFACT_EVIDENCE"
OPERATIONS_CI_STALE_ARTIFACT_SHA="$(shasum -a 256 "$OPERATIONS_CI_STALE_ARTIFACT_EVIDENCE" | awk '{print $1}')"
OPERATIONS_CI_STALE_ARTIFACT_RECORD="$TMP_DIR/release-operations-ci-stale-artifact-run.json"
python3 - "$OPERATIONS_APPROVED" "$OPERATIONS_CI_STALE_ARTIFACT_RECORD" "$OPERATIONS_CI_STALE_ARTIFACT_EVIDENCE" "$OPERATIONS_CI_STALE_ARTIFACT_SHA" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
record["ci"]["releaseArtifactArchive"]["evidence"]["path"] = sys.argv[3]
record["ci"]["releaseArtifactArchive"]["evidence"]["sha256"] = sys.argv[4]
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release operations verifier rejects stale artifact CI evidence run" \
  scripts/verify_release_operations_record.sh --file "$OPERATIONS_CI_STALE_ARTIFACT_RECORD" --report "$ARTIFACT_DIR/release-operations-ci-stale-artifact-run.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-operations-ci-stale-artifact-run.properties" "ci-release-artifact-archive-evidence-run-id-mismatch"
OPERATIONS_CI_WEAK_CONNECTED="$TMP_DIR/release-operations-ci-weak-connected.json"
python3 - "$OPERATIONS_APPROVED" "$OPERATIONS_CI_WEAK_CONNECTED" "$OPERATIONS_CI_CONNECTED_WEAK_EVIDENCE" "$OPERATIONS_CI_CONNECTED_WEAK_SHA" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
record["ci"]["connectedAndroidTests"]["evidence"]["path"] = sys.argv[3]
record["ci"]["connectedAndroidTests"]["evidence"]["sha256"] = sys.argv[4]
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release operations verifier rejects weak connected Android test evidence" \
  scripts/verify_release_operations_record.sh --file "$OPERATIONS_CI_WEAK_CONNECTED" --report "$ARTIFACT_DIR/release-operations-ci-weak-connected.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-operations-ci-weak-connected.properties" "ci-connected-android-tests-evidence-target-invalid"
assert_report_contains_text "$ARTIFACT_DIR/release-operations-ci-weak-connected.properties" "ci-connected-android-tests-count-invalid"
OPERATIONS_CI_WEAK_API_MATRIX="$TMP_DIR/release-operations-ci-weak-api-matrix.json"
python3 - "$OPERATIONS_APPROVED" "$OPERATIONS_CI_WEAK_API_MATRIX" "$OPERATIONS_CI_API_MATRIX_WEAK_EVIDENCE" "$OPERATIONS_CI_API_MATRIX_WEAK_SHA" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
record["ci"]["apiMatrix"]["evidence"]["path"] = sys.argv[3]
record["ci"]["apiMatrix"]["evidence"]["sha256"] = sys.argv[4]
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release operations verifier rejects weak API matrix evidence" \
  scripts/verify_release_operations_record.sh --file "$OPERATIONS_CI_WEAK_API_MATRIX" --report "$ARTIFACT_DIR/release-operations-ci-weak-api-matrix.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-operations-ci-weak-api-matrix.properties" "ci-api-matrix-required-apis-invalid"
assert_report_contains_text "$ARTIFACT_DIR/release-operations-ci-weak-api-matrix.properties" "ci-api-matrix-passed-apis-invalid"
OPERATIONS_CI_WEAK_SIGNING="$TMP_DIR/release-operations-ci-weak-signing.json"
python3 - "$OPERATIONS_APPROVED" "$OPERATIONS_CI_WEAK_SIGNING" "$OPERATIONS_CI_SIGNING_WEAK_EVIDENCE" "$OPERATIONS_CI_SIGNING_WEAK_SHA" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
record["ci"]["protectedSigning"]["evidence"]["path"] = sys.argv[3]
record["ci"]["protectedSigning"]["evidence"]["sha256"] = sys.argv[4]
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release operations verifier rejects weak protected signing evidence" \
  scripts/verify_release_operations_record.sh --file "$OPERATIONS_CI_WEAK_SIGNING" --report "$ARTIFACT_DIR/release-operations-ci-weak-signing.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-operations-ci-weak-signing.properties" "ci-protected-signing-evidence-artifact-schema-invalid"
assert_report_contains_text "$ARTIFACT_DIR/release-operations-ci-weak-signing.properties" "ci-protected-signing-artifact-scan-report-sha-invalid"
OPERATIONS_NO_VITALS="$TMP_DIR/release-operations-no-vitals.json"
sed 's/"Android Vitals", //' "$OPERATIONS_APPROVED" > "$OPERATIONS_NO_VITALS"
expect_failure \
  "release operations verifier requires Android Vitals source" \
  scripts/verify_release_operations_record.sh --file "$OPERATIONS_NO_VITALS" --report "$ARTIFACT_DIR/release-operations-no-vitals.properties"
assert_report_contains "$ARTIFACT_DIR/release-operations-no-vitals.properties" "status=failed"
OPERATIONS_FUTURE="$TMP_DIR/release-operations-future.json"
sed 's/"reviewDate": "'"$OPERATIONS_DATE"'"/"reviewDate": "2999-01-01"/' "$OPERATIONS_APPROVED" > "$OPERATIONS_FUTURE"
expect_failure \
  "release operations verifier rejects future review dates" \
  scripts/verify_release_operations_record.sh --file "$OPERATIONS_FUTURE" --report "$ARTIFACT_DIR/release-operations-future.properties"
assert_report_contains "$ARTIFACT_DIR/release-operations-future.properties" "status=failed"
OPERATIONS_SMOKE_BAD_SHA="$TMP_DIR/release-operations-smoke-bad-sha.json"
sed 's/"sha256": "'"$OPERATIONS_SMOKE_SHA"'"/"sha256": "0000000000000000000000000000000000000000000000000000000000000000"/' "$OPERATIONS_APPROVED" > "$OPERATIONS_SMOKE_BAD_SHA"
expect_failure \
  "release operations verifier rejects crash smoke evidence sha mismatch" \
  scripts/verify_release_operations_record.sh --file "$OPERATIONS_SMOKE_BAD_SHA" --report "$ARTIFACT_DIR/release-operations-smoke-bad-sha.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-operations-smoke-bad-sha.properties" "crash-anr-smoke-evidence-sha-mismatch"
python3 - "$OPERATIONS_APPROVED" "$OPERATIONS_SMOKE_FAILED_RECORD" "$OPERATIONS_SMOKE_EVIDENCE" "$OPERATIONS_SMOKE_FAILED_EVIDENCE" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

record_path, target_path, source_evidence_path, failed_evidence_path = map(Path, sys.argv[1:])
text = source_evidence_path.read_text()
text = text.replace("status=passed\n", "status=failed\n", 1)
text = text.replace("noLaunchCrash=true\n", "noLaunchCrash=false\n", 1)
failed_evidence_path.write_text(text)
record = json.loads(record_path.read_text())
record["crashAnrSmoke"]["evidence"]["path"] = str(failed_evidence_path)
record["crashAnrSmoke"]["evidence"]["sha256"] = hashlib.sha256(failed_evidence_path.read_bytes()).hexdigest()
target_path.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release operations verifier rejects failed crash smoke evidence with matching sha" \
  scripts/verify_release_operations_record.sh --file "$OPERATIONS_SMOKE_FAILED_RECORD" --report "$ARTIFACT_DIR/release-operations-smoke-failed.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-operations-smoke-failed.properties" "crash-anr-smoke-evidence-status-not-passed"
assert_report_contains_text "$ARTIFACT_DIR/release-operations-smoke-failed.properties" "crash-anr-smoke-evidence-no-launch-crash-not-true"
python3 - "$OPERATIONS_APPROVED" "$OPERATIONS_SMOKE_BAD_LOGCAT_RECORD" "$OPERATIONS_SMOKE_EVIDENCE" "$OPERATIONS_SMOKE_BAD_LOGCAT_EVIDENCE" <<'PY'
import hashlib
import json
import re
import sys
from pathlib import Path

record_path, target_path, source_evidence_path, bad_logcat_evidence_path = map(Path, sys.argv[1:])
text = source_evidence_path.read_text()
text = re.sub(
    r"^logcatSha256=[0-9a-f]{64}$",
    "logcatSha256=0000000000000000000000000000000000000000000000000000000000000000",
    text,
    count=1,
    flags=re.MULTILINE,
)
bad_logcat_evidence_path.write_text(text)
record = json.loads(record_path.read_text())
record["crashAnrSmoke"]["evidence"]["path"] = str(bad_logcat_evidence_path)
record["crashAnrSmoke"]["evidence"]["sha256"] = hashlib.sha256(bad_logcat_evidence_path.read_bytes()).hexdigest()
target_path.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release operations verifier rejects crash smoke evidence with bad logcat sha" \
  scripts/verify_release_operations_record.sh --file "$OPERATIONS_SMOKE_BAD_LOGCAT_RECORD" --report "$ARTIFACT_DIR/release-operations-smoke-bad-logcat.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-operations-smoke-bad-logcat.properties" "crash-anr-smoke-logcat-logcat-sha256-mismatch"

VALIDATION_PENDING="$TMP_DIR/release-validation-pending.json"
VALIDATION_APPROVED="$TMP_DIR/release-validation-approved.json"
VALIDATION_EMULATOR_REPORT="$TMP_DIR/regression-emulator.properties"
VALIDATION_EMULATOR_HELPER_REPORT="$TMP_DIR/emulator-verification.properties"
VALIDATION_DEVICE_REPORT="$TMP_DIR/device-verification.properties"
VALIDATION_EMULATOR_DEVICE_REPORT="$TMP_DIR/emulator-device-verification.properties"
VALIDATION_EMULATOR_LOGCAT="$TMP_DIR/emulator-logcat.txt"
VALIDATION_EMULATOR_SMOKE_REPORT="$TMP_DIR/emulator-crash-anr-smoke.properties"
VALIDATION_SCREENSHOT_REPORT="$TMP_DIR/release-screenshots.properties"
VALIDATION_INSTRUMENTATION_OUTPUT="$TMP_DIR/instrumentation.txt"
VALIDATION_DATE="$(date +%F)"
printf 'OK (%s tests)\n' "$SOURCE_ANDROID_TEST_COUNT" > "$VALIDATION_INSTRUMENTATION_OUTPUT"
printf 'clean emulator validation logcat\n' > "$VALIDATION_EMULATOR_LOGCAT"
mkdir -p "$TMP_DIR/validation-screenshots"
python3 - "$TMP_DIR/validation-screenshots" <<'PY'
import base64
import sys
from pathlib import Path

target = Path(sys.argv[1])
target.mkdir(parents=True, exist_ok=True)
png = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII="
)
for name in ("chat-home", "model-manager", "confirmation-sheet", "background-tasks-or-audit"):
    (target / f"{name}.png").write_bytes(png)
PY
{
  printf 'status=passed\n'
  printf 'exit_code=0\n'
  printf 'target=release-screenshots\n'
  printf 'clean_device=1\n'
  printf 'serial=emulator-5554\n'
  printf 'api_level=36\n'
  printf 'abi=arm64-v8a\n'
  printf 'avd=test-avd\n'
  printf 'releaseArtifactSha256=%s\n' "$VALID_PERF_SHA"
  printf 'screenshot_dir=%s\n' "$TMP_DIR/validation-screenshots"
  for screenshot_name in chat-home model-manager confirmation-sheet background-tasks-or-audit; do
    screenshot_path="$TMP_DIR/validation-screenshots/$screenshot_name.png"
    screenshot_ui_dump="$TMP_DIR/validation-screenshots/$screenshot_name.xml"
    screenshot_sha="$(shasum -a 256 "$screenshot_path" | awk '{print $1}')"
    case "$screenshot_name" in
      chat-home)
        screenshot_required_text="PocketMind|隐私优先的随身 AI 助手|为什么装它|模型管理"
        ;;
      model-manager)
        screenshot_required_text="模型管理|当前模型|本地可用|远程多模态可选"
        ;;
      confirmation-sheet)
        screenshot_required_text="即将发送到远程模型|确认后才会|取消"
        ;;
      background-tasks-or-audit)
        screenshot_required_text="后台任务|最近审计日志|最近 Agent 轨迹|暂无运行中的后台任务"
        ;;
    esac
    {
      printf '<hierarchy>\n'
      old_ifs="$IFS"
      IFS='|' read -r -a screenshot_required_texts <<< "$screenshot_required_text"
      IFS="$old_ifs"
      for screenshot_text in "${screenshot_required_texts[@]}"; do
        printf '  <node text="%s" />\n' "$screenshot_text"
      done
      printf '</hierarchy>\n'
    } > "$screenshot_ui_dump"
    screenshot_ui_dump_sha="$(shasum -a 256 "$screenshot_ui_dump" | awk '{print $1}')"
    printf 'screenshot.%s.path=%s\n' "$screenshot_name" "$screenshot_path"
    printf 'screenshot.%s.sha256=%s\n' "$screenshot_name" "$screenshot_sha"
    printf 'screenshot.%s.sanitized=true\n' "$screenshot_name"
    printf 'screenshot.%s.uiDump=%s\n' "$screenshot_name" "$screenshot_ui_dump"
    printf 'screenshot.%s.uiDumpSha256=%s\n' "$screenshot_name" "$screenshot_ui_dump_sha"
    printf 'screenshot.%s.visualRegression=passed\n' "$screenshot_name"
    printf 'screenshot.%s.requiredText=%s\n' "$screenshot_name" "$screenshot_required_text"
  done
} > "$VALIDATION_SCREENSHOT_REPORT"
mkdir -p "$TMP_DIR/validation-api-evidence"
for api_level in 28 32 33 34 36; do
  api_evidence_dir="$TMP_DIR/validation-api-evidence/api-$api_level"
  mkdir -p "$api_evidence_dir"
  api_instrumentation_output="$api_evidence_dir/instrumentation.txt"
  api_logcat_file="$api_evidence_dir/logcat.txt"
  api_device_report="$api_evidence_dir/device-verification.properties"
  api_emulator_report="$api_evidence_dir/emulator-verification.properties"
  api_smoke_report="$api_evidence_dir/crash-anr-smoke.properties"
  api_regression_report="$TMP_DIR/validation-api-evidence/api-$api_level.properties"
  printf 'OK (%s tests)\n' "$SOURCE_ANDROID_TEST_COUNT" > "$api_instrumentation_output"
  printf 'clean api %s emulator validation logcat\n' "$api_level" > "$api_logcat_file"
  api_instrumentation_sha="$(shasum -a 256 "$api_instrumentation_output" | awk '{print $1}')"
  api_logcat_sha="$(shasum -a 256 "$api_logcat_file" | awk '{print $1}')"
  cat > "$api_device_report" <<VALIDATION_API_DEVICE_EVIDENCE_PROPERTIES
artifact_schema=DeviceVerificationArtifact/v1
artifact_id=device-emulator-$api_level-api$api_level-2026-06-06T000000Z
status=passed
exit_code=0
target=device
failedTarget=
reason=
started_at_utc=2026-06-06T00:00:00Z
finished_at_utc=2026-06-06T00:01:00Z
serial=emulator-$api_level
api_level=$api_level
abi=arm64-v8a
clean_device=1
data_free_kb=4194304
instrumentation=passed
instrumentation_test_count=$SOURCE_ANDROID_TEST_COUNT
test_count=$SOURCE_ANDROID_TEST_COUNT
instrumentation_output_file=$api_instrumentation_output
instrumentation_output_sha256=$api_instrumentation_sha
logcat_file=$api_logcat_file
logcat_sha256=$api_logcat_sha
logcat_captured=1
logcat_tail_lines=500
debug_apk=app/build/outputs/apk/debug/app-debug.apk
android_test_apk=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
VALIDATION_API_DEVICE_EVIDENCE_PROPERTIES
  cat > "$api_emulator_report" <<VALIDATION_API_EMULATOR_EVIDENCE_PROPERTIES
status=passed
exit_code=0
target=emulator
failedTarget=
reason=
started_at_utc=2026-06-06T00:00:00Z
finished_at_utc=2026-06-06T00:01:00Z
serial=emulator-$api_level
api_level=$api_level
abi=arm64-v8a
avd=api${api_level}-avd
clean_device=1
evidence_dir=$api_evidence_dir
screenshot_file=$api_evidence_dir/screenshot.png
window_dump_file=$api_evidence_dir/window.xml
logcat_file=$api_evidence_dir/logcat.txt
crash_anr_smoke_report_file=$api_smoke_report
emulator_log=$api_evidence_dir-emulator.log
device_report_file=$api_device_report
VALIDATION_API_EMULATOR_EVIDENCE_PROPERTIES
  write_crash_anr_smoke_fixture \
    "$api_smoke_report" \
    "$api_device_report" \
    "$api_instrumentation_output" \
    "$api_logcat_file" \
    "emulator-$api_level" \
    "$api_level" \
    "arm64-v8a"
  cat > "$api_regression_report" <<VALIDATION_API_EVIDENCE_PROPERTIES
status=passed
exit_code=0
target=regression-emulator
failedTarget=
reason=
started_at_utc=2026-06-06T00:00:00Z
finished_at_utc=2026-06-06T00:01:00Z
clean_device=1
source_android_test_count=$SOURCE_ANDROID_TEST_COUNT
expected_android_test_count=$SOURCE_ANDROID_TEST_COUNT
actual_android_test_count=$SOURCE_ANDROID_TEST_COUNT
releaseArtifactSha256=$VALID_PERF_SHA
serial=emulator-$api_level
api_level=$api_level
abi=arm64-v8a
avd=api${api_level}-avd
instrumentation_output_file=$api_instrumentation_output
logcat_file=$api_logcat_file
emulator_report_file=$api_emulator_report
device_report_file=$api_device_report
VALIDATION_API_EVIDENCE_PROPERTIES
done
mkdir -p "$TMP_DIR/validation-manual-evidence" "$TMP_DIR/validation-flow-evidence" "$TMP_DIR/validation-performance-evidence"
for manual_key in \
  modelSetup remoteModePrivacy toolConfirmation permissions backgroundReminders sharing \
  multimodalEntryPoints voiceInput filePicker mediaProjection remoteSinglePublicEvidence \
  remoteMultiEvidenceComparison mixedPrivateActionBatchFailClosed; do
  manual_evidence_path="$TMP_DIR/validation-manual-evidence/$manual_key.properties"
  cat > "$manual_evidence_path" <<VALIDATION_MANUAL_EVIDENCE_PROPERTIES
artifactSchema=ManualAcceptanceEvidence/v1
status=passed
target=manual-acceptance
manualKey=$manual_key
manualAcceptance=true
owner=QA
date=$VALIDATION_DATE
recordedAt=$PERF_RECORDED_AT
command=scripts/record_manual_acceptance_evidence.sh
reproduciblePath=$manual_evidence_path
releaseArtifactSha256=$VALID_PERF_SHA
VALIDATION_MANUAL_EVIDENCE_PROPERTIES
done
for flow_key in \
  firstInstall upgradeInstall localModelDownloadVerification customModelImportOrUrlRejection \
  remoteHttpsConfiguration encryptedApiKeyClear sessionPersistence memoryControls \
  privacyAndDataControls remindersAfterReboot shareAndPickerInput voiceInput adaptiveUi accessibilityText \
  recentMediaOcr mediaProjectionCancellation; do
  flow_evidence_path="$TMP_DIR/validation-flow-evidence/$flow_key.properties"
  cat > "$flow_evidence_path" <<VALIDATION_FLOW_EVIDENCE_PROPERTIES
artifactSchema=ReleaseFlowEvidence/v1
status=passed
target=release-flow
flowKey=$flow_key
releaseFlowPassed=true
owner=QA
date=$VALIDATION_DATE
recordedAt=$PERF_RECORDED_AT
command=scripts/record_release_flow_evidence.sh
reproduciblePath=$flow_evidence_path
releaseArtifactSha256=$VALID_PERF_SHA
VALIDATION_FLOW_EVIDENCE_PROPERTIES
  write_model_release_flow_contract_fixture "$flow_key" >> "$flow_evidence_path"
done
VALIDATION_PERF_BASELINE="$TMP_DIR/validation-performance-evidence/perf-baseline.properties"
cat > "$VALIDATION_PERF_BASELINE" <<VALIDATION_PERF_BASELINE_PROPERTIES
artifactSchema=PerfBaseline/v1
status=passed
target=perf-baseline-record
owner=release-engineering
collectionCommand=scripts/collect_perf_baseline.sh
reproduciblePath=$VALIDATION_PERF_BASELINE
deviceSerial=device-a
deviceModel=Pixel Test
androidApi=36
abi=arm64-v8a
appVersion=0.1.0
releaseArtifactSha256=$VALID_PERF_SHA
modelId=chat-e2b
backend=GPU
firstLaunchInteractiveMs=1200
modelLoadMs=3500
firstTokenMs=900
tokensPerSecond=12.5
stopGenerationRecoveryMs=200
gpuFallbackStatus=not-needed
visionInputMs=500
memorySearch5kMs=25
memoryPeakMb=512
oomOrAnrObserved=false
recordedAt=$PERF_RECORDED_AT
VALIDATION_PERF_BASELINE_PROPERTIES
VALIDATION_PERF_BASELINE_SHA="$(shasum -a 256 "$VALIDATION_PERF_BASELINE" | awk '{print $1}')"
for perf_key in firstLaunch modelLoad firstToken streamingStopCancel backgroundReminderDelivery memoryPressure; do
  cat > "$TMP_DIR/validation-performance-evidence/$perf_key.properties" <<VALIDATION_PERFORMANCE_EVIDENCE_PROPERTIES
artifactSchema=PerfBaselineVerification/v1
status=passed
target=perf-baseline
owner=release-engineering
recordedAt=$PERF_RECORDED_AT
command=scripts/verify_perf_baseline.sh --file $VALIDATION_PERF_BASELINE --performance-key $perf_key
performanceKey=$perf_key
failedTarget=
reproduciblePath=$VALIDATION_PERF_BASELINE
baselineFile=$VALIDATION_PERF_BASELINE
baselineSha256=$VALIDATION_PERF_BASELINE_SHA
missingFieldCount=0
expectedArtifactSha256=$VALID_PERF_SHA
expectedAppVersion=0.1.0
maxRecordAgeDays=30
VALIDATION_PERFORMANCE_EVIDENCE_PROPERTIES
done
cat > "$VALIDATION_PENDING" <<'VALIDATION_PENDING_JSON'
{
  "version": 1,
  "status": "pending_validation"
}
VALIDATION_PENDING_JSON
VALIDATION_PENDING_SHA="$(shasum -a 256 "$VALIDATION_PENDING" | awk '{print $1}')"
expect_failure \
  "release validation verifier rejects pending records" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_PENDING" --report "$ARTIFACT_DIR/release-validation-pending.properties"
assert_release_verifier_failed_report "$ARTIFACT_DIR/release-validation-pending.properties" "ReleaseValidationRecordVerification/v1"
assert_report_contains "$ARTIFACT_DIR/release-validation-pending.properties" "failedTarget=release-validation-record"
assert_report_contains "$ARTIFACT_DIR/release-validation-pending.properties" "validationRecordSha256=$VALIDATION_PENDING_SHA"
cat > "$VALIDATION_EMULATOR_REPORT" <<VALIDATION_EMULATOR_REPORT_PROPERTIES
status=passed
exit_code=0
target=regression-emulator
failedTarget=
reason=
started_at_utc=2026-06-06T00:00:00Z
finished_at_utc=2026-06-06T00:01:00Z
clean_device=1
source_android_test_count=$SOURCE_ANDROID_TEST_COUNT
expected_android_test_count=$SOURCE_ANDROID_TEST_COUNT
actual_android_test_count=$SOURCE_ANDROID_TEST_COUNT
releaseArtifactSha256=$VALID_PERF_SHA
serial=emulator-5554
avd=test-avd
api_level=36
abi=arm64-v8a
instrumentation_output_file=$VALIDATION_INSTRUMENTATION_OUTPUT
logcat_file=$VALIDATION_EMULATOR_LOGCAT
emulator_report_file=$VALIDATION_EMULATOR_HELPER_REPORT
device_report_file=$VALIDATION_EMULATOR_DEVICE_REPORT
VALIDATION_EMULATOR_REPORT_PROPERTIES
cat > "$VALIDATION_DEVICE_REPORT" <<VALIDATION_DEVICE_REPORT_PROPERTIES
artifact_schema=DeviceVerificationArtifact/v1
artifact_id=device-device-a-api36-2026-06-06T000000Z
status=passed
exit_code=0
target=device
failedTarget=
reason=
started_at_utc=2026-06-06T00:00:00Z
finished_at_utc=2026-06-06T00:01:00Z
serial=device-a
api_level=36
abi=arm64-v8a
clean_device=1
data_free_kb=4194304
instrumentation=passed
instrumentation_test_count=$SOURCE_ANDROID_TEST_COUNT
test_count=$SOURCE_ANDROID_TEST_COUNT
releaseArtifactSha256=$VALID_PERF_SHA
instrumentation_output_file=$VALIDATION_INSTRUMENTATION_OUTPUT
instrumentation_output_sha256=$(shasum -a 256 "$VALIDATION_INSTRUMENTATION_OUTPUT" | awk '{print $1}')
logcat_file=$VALIDATION_EMULATOR_LOGCAT
logcat_sha256=$(shasum -a 256 "$VALIDATION_EMULATOR_LOGCAT" | awk '{print $1}')
debug_apk=app/build/outputs/apk/debug/app-debug.apk
android_test_apk=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
VALIDATION_DEVICE_REPORT_PROPERTIES
cat > "$VALIDATION_EMULATOR_DEVICE_REPORT" <<VALIDATION_EMULATOR_DEVICE_REPORT_PROPERTIES
artifact_schema=DeviceVerificationArtifact/v1
artifact_id=device-emulator-5554-api36-2026-06-06T000000Z
status=passed
exit_code=0
target=device
failedTarget=
reason=
started_at_utc=2026-06-06T00:00:00Z
finished_at_utc=2026-06-06T00:01:00Z
serial=emulator-5554
api_level=36
abi=arm64-v8a
clean_device=1
data_free_kb=4194304
instrumentation=passed
instrumentation_test_count=$SOURCE_ANDROID_TEST_COUNT
test_count=$SOURCE_ANDROID_TEST_COUNT
instrumentation_output_file=$VALIDATION_INSTRUMENTATION_OUTPUT
instrumentation_output_sha256=$(shasum -a 256 "$VALIDATION_INSTRUMENTATION_OUTPUT" | awk '{print $1}')
logcat_file=$VALIDATION_EMULATOR_LOGCAT
logcat_sha256=$(shasum -a 256 "$VALIDATION_EMULATOR_LOGCAT" | awk '{print $1}')
logcat_captured=1
logcat_tail_lines=500
debug_apk=app/build/outputs/apk/debug/app-debug.apk
android_test_apk=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
VALIDATION_EMULATOR_DEVICE_REPORT_PROPERTIES
cat > "$VALIDATION_EMULATOR_HELPER_REPORT" <<VALIDATION_EMULATOR_HELPER_REPORT_PROPERTIES
status=passed
exit_code=0
target=emulator
failedTarget=
reason=
started_at_utc=2026-06-06T00:00:00Z
finished_at_utc=2026-06-06T00:01:00Z
serial=emulator-5554
api_level=36
abi=arm64-v8a
avd=test-avd
clean_device=1
evidence_dir=$TMP_DIR
screenshot_file=$TMP_DIR/screenshot.png
window_dump_file=$TMP_DIR/window.xml
logcat_file=$VALIDATION_EMULATOR_LOGCAT
crash_anr_smoke_report_file=$VALIDATION_EMULATOR_SMOKE_REPORT
emulator_log=$TMP_DIR-emulator.log
device_report_file=$VALIDATION_EMULATOR_DEVICE_REPORT
VALIDATION_EMULATOR_HELPER_REPORT_PROPERTIES
write_crash_anr_smoke_fixture \
  "$VALIDATION_EMULATOR_SMOKE_REPORT" \
  "$VALIDATION_EMULATOR_DEVICE_REPORT" \
  "$VALIDATION_INSTRUMENTATION_OUTPUT" \
  "$VALIDATION_EMULATOR_LOGCAT" \
  "emulator-5554" \
  "36" \
  "arm64-v8a"
cat > "$VALIDATION_APPROVED" <<VALIDATION_APPROVED_JSON
{
  "version": 1,
  "status": "approved",
  "emulatorRegression": {
    "status": "passed",
    "reportPath": "$VALIDATION_EMULATOR_REPORT",
    "avd": "test-avd",
    "apiLevel": 36,
    "abi": "arm64-v8a",
    "cleanDevice": true
  },
  "physicalDevice": {
    "status": "passed",
    "reportPath": "$VALIDATION_DEVICE_REPORT",
    "serial": "device-a",
    "apiLevel": 36,
    "abi": "arm64-v8a",
    "cleanDevice": true
  },
  "apiMatrix": [
    {"apiLevel": 28, "status": "passed", "evidence": "API 28 smoke passed.", "evidencePath": "$TMP_DIR/validation-api-evidence/api-28.properties"},
    {"apiLevel": 32, "status": "passed", "evidence": "API 32 legacy storage path passed.", "evidencePath": "$TMP_DIR/validation-api-evidence/api-32.properties"},
    {"apiLevel": 33, "status": "passed", "evidence": "API 33 media and notification path passed.", "evidencePath": "$TMP_DIR/validation-api-evidence/api-33.properties"},
    {"apiLevel": 34, "status": "passed", "evidence": "API 34 selected visual media path passed.", "evidencePath": "$TMP_DIR/validation-api-evidence/api-34.properties"},
    {"apiLevel": 36, "status": "passed", "evidence": "API 36 target behavior passed.", "evidencePath": "$TMP_DIR/validation-api-evidence/api-36.properties"}
  ],
  "manualAcceptance": {
    "modelSetup": {"status": "passed", "evidence": "Model setup manual acceptance passed.", "evidencePath": "$TMP_DIR/validation-manual-evidence/modelSetup.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "remoteModePrivacy": {"status": "passed", "evidence": "Remote mode privacy manual acceptance passed.", "evidencePath": "$TMP_DIR/validation-manual-evidence/remoteModePrivacy.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "toolConfirmation": {"status": "passed", "evidence": "Tool confirmation manual acceptance passed.", "evidencePath": "$TMP_DIR/validation-manual-evidence/toolConfirmation.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "permissions": {"status": "passed", "evidence": "Permission prompt manual acceptance passed.", "evidencePath": "$TMP_DIR/validation-manual-evidence/permissions.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "backgroundReminders": {"status": "passed", "evidence": "Background reminders manual acceptance passed.", "evidencePath": "$TMP_DIR/validation-manual-evidence/backgroundReminders.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "sharing": {"status": "passed", "evidence": "Sharing manual acceptance passed.", "evidencePath": "$TMP_DIR/validation-manual-evidence/sharing.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "multimodalEntryPoints": {"status": "passed", "evidence": "Multimodal entry points manual acceptance passed.", "evidencePath": "$TMP_DIR/validation-manual-evidence/multimodalEntryPoints.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "voiceInput": {"status": "passed", "evidence": "Voice input manual acceptance passed.", "evidencePath": "$TMP_DIR/validation-manual-evidence/voiceInput.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "filePicker": {"status": "passed", "evidence": "File picker manual acceptance passed.", "evidencePath": "$TMP_DIR/validation-manual-evidence/filePicker.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "mediaProjection": {"status": "passed", "evidence": "MediaProjection manual acceptance passed.", "evidencePath": "$TMP_DIR/validation-manual-evidence/mediaProjection.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "remoteSinglePublicEvidence": {"status": "passed", "evidence": "Remote single public evidence manual acceptance passed.", "evidencePath": "$TMP_DIR/validation-manual-evidence/remoteSinglePublicEvidence.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "remoteMultiEvidenceComparison": {"status": "passed", "evidence": "Remote multi evidence comparison manual acceptance passed.", "evidencePath": "$TMP_DIR/validation-manual-evidence/remoteMultiEvidenceComparison.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "mixedPrivateActionBatchFailClosed": {"status": "passed", "evidence": "Mixed private/action batch fail-closed manual acceptance passed.", "evidencePath": "$TMP_DIR/validation-manual-evidence/mixedPrivateActionBatchFailClosed.properties", "owner": "QA", "date": "$VALIDATION_DATE"}
  },
  "flowMatrix": {
    "firstInstall": {"status": "passed", "evidence": "First install flow passed.", "evidencePath": "$TMP_DIR/validation-flow-evidence/firstInstall.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "upgradeInstall": {"status": "passed", "evidence": "Upgrade install flow passed.", "evidencePath": "$TMP_DIR/validation-flow-evidence/upgradeInstall.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "localModelDownloadVerification": {"status": "passed", "evidence": "Local model download verification flow passed.", "evidencePath": "$TMP_DIR/validation-flow-evidence/localModelDownloadVerification.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "customModelImportOrUrlRejection": {"status": "passed", "evidence": "Custom model import or URL rejection flow passed.", "evidencePath": "$TMP_DIR/validation-flow-evidence/customModelImportOrUrlRejection.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "remoteHttpsConfiguration": {"status": "passed", "evidence": "Remote HTTPS configuration flow passed.", "evidencePath": "$TMP_DIR/validation-flow-evidence/remoteHttpsConfiguration.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "encryptedApiKeyClear": {"status": "passed", "evidence": "Encrypted API key clear flow passed.", "evidencePath": "$TMP_DIR/validation-flow-evidence/encryptedApiKeyClear.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "sessionPersistence": {"status": "passed", "evidence": "Session persistence flow passed.", "evidencePath": "$TMP_DIR/validation-flow-evidence/sessionPersistence.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "memoryControls": {"status": "passed", "evidence": "Memory controls flow passed.", "evidencePath": "$TMP_DIR/validation-flow-evidence/memoryControls.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "privacyAndDataControls": {"status": "passed", "evidence": "Privacy and data controls flow passed.", "evidencePath": "$TMP_DIR/validation-flow-evidence/privacyAndDataControls.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "remindersAfterReboot": {"status": "passed", "evidence": "Reminders after reboot flow passed.", "evidencePath": "$TMP_DIR/validation-flow-evidence/remindersAfterReboot.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "shareAndPickerInput": {"status": "passed", "evidence": "Share and picker input flow passed.", "evidencePath": "$TMP_DIR/validation-flow-evidence/shareAndPickerInput.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "voiceInput": {"status": "passed", "evidence": "Voice input flow passed.", "evidencePath": "$TMP_DIR/validation-flow-evidence/voiceInput.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "adaptiveUi": {"status": "passed", "evidence": "Adaptive UI flow passed.", "evidencePath": "$TMP_DIR/validation-flow-evidence/adaptiveUi.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "accessibilityText": {"status": "passed", "evidence": "Accessibility text flow passed.", "evidencePath": "$TMP_DIR/validation-flow-evidence/accessibilityText.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "recentMediaOcr": {"status": "passed", "evidence": "Recent media OCR flow passed.", "evidencePath": "$TMP_DIR/validation-flow-evidence/recentMediaOcr.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "mediaProjectionCancellation": {"status": "passed", "evidence": "MediaProjection cancellation flow passed.", "evidencePath": "$TMP_DIR/validation-flow-evidence/mediaProjectionCancellation.properties", "owner": "QA", "date": "$VALIDATION_DATE"}
  },
  "screenshots": [
    {"name": "chat-home", "path": "$TMP_DIR/validation-screenshots/chat-home.png", "reportPath": "$VALIDATION_SCREENSHOT_REPORT", "sanitized": true},
    {"name": "model-manager", "path": "$TMP_DIR/validation-screenshots/model-manager.png", "reportPath": "$VALIDATION_SCREENSHOT_REPORT", "sanitized": true},
    {"name": "confirmation-sheet", "path": "$TMP_DIR/validation-screenshots/confirmation-sheet.png", "reportPath": "$VALIDATION_SCREENSHOT_REPORT", "sanitized": true},
    {"name": "background-tasks-or-audit", "path": "$TMP_DIR/validation-screenshots/background-tasks-or-audit.png", "reportPath": "$VALIDATION_SCREENSHOT_REPORT", "sanitized": true}
  ],
  "performanceSanity": {
    "firstLaunch": {"status": "passed", "evidence": "First launch performance sanity passed.", "evidencePath": "$TMP_DIR/validation-performance-evidence/firstLaunch.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "modelLoad": {"status": "passed", "evidence": "Model load performance sanity passed.", "evidencePath": "$TMP_DIR/validation-performance-evidence/modelLoad.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "firstToken": {"status": "passed", "evidence": "First token performance sanity passed.", "evidencePath": "$TMP_DIR/validation-performance-evidence/firstToken.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "streamingStopCancel": {"status": "passed", "evidence": "Streaming stop/cancel performance sanity passed.", "evidencePath": "$TMP_DIR/validation-performance-evidence/streamingStopCancel.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "backgroundReminderDelivery": {"status": "passed", "evidence": "Background reminder delivery performance sanity passed.", "evidencePath": "$TMP_DIR/validation-performance-evidence/backgroundReminderDelivery.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "memoryPressure": {"status": "passed", "evidence": "Memory pressure performance sanity passed.", "evidencePath": "$TMP_DIR/validation-performance-evidence/memoryPressure.properties", "owner": "QA", "date": "$VALIDATION_DATE"}
  },
  "review": {
    "reviewer": "Validation Reviewer",
    "reviewDate": "$VALIDATION_DATE"
  }
}
VALIDATION_APPROVED_JSON
python3 - "$VALIDATION_APPROVED" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

record_path = Path(sys.argv[1])
record = json.loads(record_path.read_text())

def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()

record["emulatorRegression"]["reportSha256"] = sha(record["emulatorRegression"]["reportPath"])
record["physicalDevice"]["reportSha256"] = sha(record["physicalDevice"]["reportPath"])
for entry in record["apiMatrix"]:
    entry["evidenceSha256"] = sha(entry["evidencePath"])
for section in ("manualAcceptance", "flowMatrix", "performanceSanity"):
    for item in record[section].values():
        item["evidenceSha256"] = sha(item["evidencePath"])
for entry in record["screenshots"]:
    entry["sha256"] = sha(entry["path"])
    entry["reportSha256"] = sha(entry["reportPath"])

record_path.write_text(json.dumps(record, indent=2))
PY
VALIDATION_APPROVED_SHA="$(shasum -a 256 "$VALIDATION_APPROVED" | awk '{print $1}')"
expect_success \
  "release validation verifier accepts approved evidence record" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_APPROVED" --report "$ARTIFACT_DIR/release-validation-approved.properties"
assert_release_verifier_passed_report "$ARTIFACT_DIR/release-validation-approved.properties" "ReleaseValidationRecordVerification/v1"
assert_report_contains "$ARTIFACT_DIR/release-validation-approved.properties" "validationRecordSha256=$VALIDATION_APPROVED_SHA"
expect_success \
  "release validation verifier accepts current release artifact context" \
  env EXPECTED_RELEASE_ARTIFACT_SHA256="$VALID_PERF_SHA" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_APPROVED" --report "$ARTIFACT_DIR/release-validation-current-artifact.properties"
assert_release_verifier_passed_report "$ARTIFACT_DIR/release-validation-current-artifact.properties" "ReleaseValidationRecordVerification/v1"
assert_report_contains "$ARTIFACT_DIR/release-validation-current-artifact.properties" "expectedReleaseArtifactSha256=$VALID_PERF_SHA"
VALIDATION_LOCAL_VISION_COUNT_TWO="$TMP_DIR/release-validation-local-vision-count-two.json"
VALIDATION_LOCAL_VISION_COUNT_TWO_EVIDENCE="$TMP_DIR/validation-flow-evidence/local-vision-count-two.properties"
sed \
  -e 's/^localVisionRuntimeImageAttachmentSendCount=1$/localVisionRuntimeImageAttachmentSendCount=2/' \
  -e "s#^reproduciblePath=.*#reproduciblePath=$VALIDATION_LOCAL_VISION_COUNT_TWO_EVIDENCE#" \
  "$TMP_DIR/validation-flow-evidence/shareAndPickerInput.properties" > "$VALIDATION_LOCAL_VISION_COUNT_TWO_EVIDENCE"
python3 - "$VALIDATION_APPROVED" "$VALIDATION_LOCAL_VISION_COUNT_TWO" "$VALIDATION_LOCAL_VISION_COUNT_TWO_EVIDENCE" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
evidence = Path(sys.argv[3])
record = json.loads(source.read_text())
record["flowMatrix"]["shareAndPickerInput"]["evidencePath"] = str(evidence)
record["flowMatrix"]["shareAndPickerInput"]["evidenceSha256"] = hashlib.sha256(evidence.read_bytes()).hexdigest()
target.write_text(json.dumps(record, indent=2))
PY
expect_success \
  "release validation verifier accepts local vision runtime send count above minimum" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_LOCAL_VISION_COUNT_TWO" --report "$ARTIFACT_DIR/release-validation-local-vision-count-two.properties"
assert_report_contains "$ARTIFACT_DIR/release-validation-local-vision-count-two.properties" "status=passed"
VALIDATION_X86_EMULATOR="$TMP_DIR/release-validation-x86-emulator.json"
VALIDATION_X86_EMULATOR_REPORT="$TMP_DIR/x86-regression-emulator.properties"
VALIDATION_X86_EMULATOR_HELPER_REPORT="$TMP_DIR/x86-emulator-verification.properties"
VALIDATION_X86_EMULATOR_DEVICE_REPORT="$TMP_DIR/x86-emulator-device-verification.properties"
sed 's/^abi=.*/abi=x86_64/' "$VALIDATION_EMULATOR_DEVICE_REPORT" > "$VALIDATION_X86_EMULATOR_DEVICE_REPORT"
sed \
  -e 's/^abi=.*/abi=x86_64/' \
  -e "s#^device_report_file=.*#device_report_file=$VALIDATION_X86_EMULATOR_DEVICE_REPORT#" \
  "$VALIDATION_EMULATOR_HELPER_REPORT" > "$VALIDATION_X86_EMULATOR_HELPER_REPORT"
sed \
  -e 's/^abi=.*/abi=x86_64/' \
  -e "s#^emulator_report_file=.*#emulator_report_file=$VALIDATION_X86_EMULATOR_HELPER_REPORT#" \
  -e "s#^device_report_file=.*#device_report_file=$VALIDATION_X86_EMULATOR_DEVICE_REPORT#" \
  "$VALIDATION_EMULATOR_REPORT" > "$VALIDATION_X86_EMULATOR_REPORT"
python3 - "$VALIDATION_APPROVED" "$VALIDATION_X86_EMULATOR" "$VALIDATION_X86_EMULATOR_REPORT" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
report = Path(sys.argv[3])
record = json.loads(source.read_text())
record["emulatorRegression"]["abi"] = "x86_64"
record["emulatorRegression"]["reportPath"] = str(report)
record["emulatorRegression"]["reportSha256"] = hashlib.sha256(report.read_bytes()).hexdigest()
target.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects x86 emulator release evidence" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_X86_EMULATOR" --report "$ARTIFACT_DIR/release-validation-x86-emulator.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-x86-emulator.properties" "emulator-regression-abi-not-arm64"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-x86-emulator.properties" "emulator-regression-abi-x86-not-allowed"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-x86-emulator.properties" "emulator-report-abi-not-arm64"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-x86-emulator.properties" "emulator-report-abi-x86-not-allowed"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-x86-emulator.properties" "emulator-regression-emulator-report-abi-x86-not-allowed"
VALIDATION_API_MIXED_ABI="$TMP_DIR/release-validation-api-mixed-abi.json"
VALIDATION_API_MIXED_ABI_DIR="$TMP_DIR/validation-api-evidence/mixed-abi-api-28"
VALIDATION_API_MIXED_ABI_EVIDENCE="$TMP_DIR/validation-api-evidence/mixed-abi-api-28.properties"
VALIDATION_API_MIXED_ABI_EMULATOR_REPORT="$VALIDATION_API_MIXED_ABI_DIR/emulator-verification.properties"
VALIDATION_API_MIXED_ABI_DEVICE_REPORT="$VALIDATION_API_MIXED_ABI_DIR/device-verification.properties"
mkdir -p "$VALIDATION_API_MIXED_ABI_DIR"
sed 's/^abi=.*/abi=arm64-v8a,x86_64/' \
  "$TMP_DIR/validation-api-evidence/api-28/device-verification.properties" > "$VALIDATION_API_MIXED_ABI_DEVICE_REPORT"
sed \
  -e 's/^abi=.*/abi=arm64-v8a,x86_64/' \
  -e "s#^device_report_file=.*#device_report_file=$VALIDATION_API_MIXED_ABI_DEVICE_REPORT#" \
  "$TMP_DIR/validation-api-evidence/api-28/emulator-verification.properties" > "$VALIDATION_API_MIXED_ABI_EMULATOR_REPORT"
sed \
  -e 's/^abi=.*/abi=arm64-v8a,x86_64/' \
  -e "s#^emulator_report_file=.*#emulator_report_file=$VALIDATION_API_MIXED_ABI_EMULATOR_REPORT#" \
  -e "s#^device_report_file=.*#device_report_file=$VALIDATION_API_MIXED_ABI_DEVICE_REPORT#" \
  "$TMP_DIR/validation-api-evidence/api-28.properties" > "$VALIDATION_API_MIXED_ABI_EVIDENCE"
python3 - "$VALIDATION_APPROVED" "$VALIDATION_API_MIXED_ABI" "$VALIDATION_API_MIXED_ABI_EVIDENCE" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
evidence = Path(sys.argv[3])
record = json.loads(source.read_text())
record["apiMatrix"][0]["evidencePath"] = str(evidence)
record["apiMatrix"][0]["evidenceSha256"] = hashlib.sha256(evidence.read_bytes()).hexdigest()
target.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects mixed x86 api matrix evidence" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_API_MIXED_ABI" --report "$ARTIFACT_DIR/release-validation-api-mixed-abi.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-api-mixed-abi.properties" "api-28-evidence-abi-x86-not-allowed"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-api-mixed-abi.properties" "api-28-emulator-report-abi-x86-not-allowed"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-api-mixed-abi.properties" "api-28-device-report-abi-x86-not-allowed"
expect_failure \
  "release validation verifier rejects stale release artifact context" \
  env EXPECTED_RELEASE_ARTIFACT_SHA256=2222222222222222222222222222222222222222222222222222222222222222 \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_APPROVED" --report "$ARTIFACT_DIR/release-validation-stale-artifact.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-stale-artifact.properties" "emulator-report-release-artifact-sha-mismatch"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-stale-artifact.properties" "physical-device-report-release-artifact-sha-mismatch"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-stale-artifact.properties" "api-28-evidence-release-artifact-sha-mismatch"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-stale-artifact.properties" "manual-modelSetup-evidence-release-artifact-sha-mismatch"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-stale-artifact.properties" "flow-firstInstall-evidence-release-artifact-sha-mismatch"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-stale-artifact.properties" "chat-home-screenshot-report-release-artifact-sha-mismatch"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-stale-artifact.properties" "performance-firstLaunch-evidence-expected-artifact-sha-mismatch"
VALIDATION_CANDIDATE_FLOW="$TMP_DIR/release-validation-candidate-flow.json"
VALIDATION_CANDIDATE_FLOW_EVIDENCE="$TMP_DIR/validation-flow-evidence/candidate-firstInstall.properties"
cat > "$VALIDATION_CANDIDATE_FLOW_EVIDENCE" <<'VALIDATION_CANDIDATE_FLOW_EVIDENCE_PROPERTIES'
status=passed
target=release-flow-matrix-candidate-evidence
flow=firstInstall
candidateOnly=true
releaseFlowPassed=false
VALIDATION_CANDIDATE_FLOW_EVIDENCE_PROPERTIES
python3 - "$VALIDATION_APPROVED" "$VALIDATION_CANDIDATE_FLOW" "$VALIDATION_CANDIDATE_FLOW_EVIDENCE" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
candidate = Path(sys.argv[3])
record = json.loads(source.read_text())
record["flowMatrix"]["firstInstall"]["evidencePath"] = str(candidate)
record["flowMatrix"]["firstInstall"]["evidenceSha256"] = hashlib.sha256(candidate.read_bytes()).hexdigest()
target.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects candidate-only flow evidence" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_CANDIDATE_FLOW" --report "$ARTIFACT_DIR/release-validation-candidate-flow.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-candidate-flow.properties" "flow-firstInstall-candidate-evidence-not-approved"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-candidate-flow.properties" "flow-firstInstall-release-flow-not-passed"
VALIDATION_FLOW_MISSING_AUDIT="$TMP_DIR/release-validation-flow-missing-audit.json"
VALIDATION_FLOW_MISSING_AUDIT_EVIDENCE="$TMP_DIR/validation-flow-evidence/first-install-missing-audit.properties"
grep -Ev '^(artifactSchema|recordedAt|command|reproduciblePath)=' \
  "$TMP_DIR/validation-flow-evidence/firstInstall.properties" > "$VALIDATION_FLOW_MISSING_AUDIT_EVIDENCE"
python3 - "$VALIDATION_APPROVED" "$VALIDATION_FLOW_MISSING_AUDIT" "$VALIDATION_FLOW_MISSING_AUDIT_EVIDENCE" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
evidence = Path(sys.argv[3])
record = json.loads(source.read_text())
record["flowMatrix"]["firstInstall"]["evidencePath"] = str(evidence)
record["flowMatrix"]["firstInstall"]["evidenceSha256"] = hashlib.sha256(evidence.read_bytes()).hexdigest()
target.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects flow evidence missing audit fields" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_FLOW_MISSING_AUDIT" --report "$ARTIFACT_DIR/release-validation-flow-missing-audit.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-flow-missing-audit.properties" "flow-firstInstall-evidence-artifact-schema-invalid"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-flow-missing-audit.properties" "flow-firstInstall-evidence-recorded-at-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-flow-missing-audit.properties" "flow-firstInstall-evidence-command-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-flow-missing-audit.properties" "flow-firstInstall-evidence-reproducible-path-invalid"
VALIDATION_WEAK_FLOW="$TMP_DIR/release-validation-weak-flow.json"
VALIDATION_WEAK_FLOW_EVIDENCE="$TMP_DIR/validation-flow-evidence/weak-firstInstall.properties"
cat > "$VALIDATION_WEAK_FLOW_EVIDENCE" <<'VALIDATION_WEAK_FLOW_EVIDENCE_PROPERTIES'
status=passed
flow=firstInstall
VALIDATION_WEAK_FLOW_EVIDENCE_PROPERTIES
python3 - "$VALIDATION_APPROVED" "$VALIDATION_WEAK_FLOW" "$VALIDATION_WEAK_FLOW_EVIDENCE" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
evidence = Path(sys.argv[3])
record = json.loads(source.read_text())
record["flowMatrix"]["firstInstall"]["evidencePath"] = str(evidence)
record["flowMatrix"]["firstInstall"]["evidenceSha256"] = hashlib.sha256(evidence.read_bytes()).hexdigest()
target.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects weak flow matrix evidence" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_WEAK_FLOW" --report "$ARTIFACT_DIR/release-validation-weak-flow.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-flow.properties" "flow-firstInstall-evidence-target-invalid"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-flow.properties" "flow-firstInstall-evidence-key-mismatch"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-flow.properties" "flow-firstInstall-release-flow-not-passed"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-flow.properties" "flow-firstInstall-first-run-setup-visibility-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-flow.properties" "flow-firstInstall-first-run-default-chat-model-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-flow.properties" "flow-firstInstall-first-run-skip-main-shell-missing"
VALIDATION_WEAK_UPGRADE_FLOW="$TMP_DIR/release-validation-weak-upgrade-flow.json"
VALIDATION_WEAK_UPGRADE_FLOW_EVIDENCE="$TMP_DIR/validation-flow-evidence/weak-upgrade-install.properties"
cat > "$VALIDATION_WEAK_UPGRADE_FLOW_EVIDENCE" <<'VALIDATION_WEAK_UPGRADE_FLOW_EVIDENCE_PROPERTIES'
status=passed
target=release-flow
flowKey=upgradeInstall
releaseFlowPassed=true
candidateOnly=false
VALIDATION_WEAK_UPGRADE_FLOW_EVIDENCE_PROPERTIES
python3 - "$VALIDATION_APPROVED" "$VALIDATION_WEAK_UPGRADE_FLOW" "$VALIDATION_WEAK_UPGRADE_FLOW_EVIDENCE" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
evidence = Path(sys.argv[3])
record = json.loads(source.read_text())
record["flowMatrix"]["upgradeInstall"]["evidencePath"] = str(evidence)
record["flowMatrix"]["upgradeInstall"]["evidenceSha256"] = hashlib.sha256(evidence.read_bytes()).hexdigest()
target.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects weak upgrade install evidence" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_WEAK_UPGRADE_FLOW" --report "$ARTIFACT_DIR/release-validation-weak-upgrade-flow.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-upgrade-flow.properties" "flow-upgradeInstall-upgrade-install-adb-install-r-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-upgrade-flow.properties" "flow-upgradeInstall-upgrade-install-first-install-time-preservation-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-upgrade-flow.properties" "flow-upgradeInstall-upgrade-install-version-code-increase-missing"
VALIDATION_WEAK_LOCAL_MODEL_FLOW="$TMP_DIR/release-validation-weak-local-model-flow.json"
VALIDATION_WEAK_LOCAL_MODEL_FLOW_EVIDENCE="$TMP_DIR/validation-flow-evidence/weak-local-model-download.properties"
cat > "$VALIDATION_WEAK_LOCAL_MODEL_FLOW_EVIDENCE" <<'VALIDATION_WEAK_LOCAL_MODEL_FLOW_EVIDENCE_PROPERTIES'
status=passed
target=release-flow
flowKey=localModelDownloadVerification
releaseFlowPassed=true
candidateOnly=false
VALIDATION_WEAK_LOCAL_MODEL_FLOW_EVIDENCE_PROPERTIES
python3 - "$VALIDATION_APPROVED" "$VALIDATION_WEAK_LOCAL_MODEL_FLOW" "$VALIDATION_WEAK_LOCAL_MODEL_FLOW_EVIDENCE" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
evidence = Path(sys.argv[3])
record = json.loads(source.read_text())
record["flowMatrix"]["localModelDownloadVerification"]["evidencePath"] = str(evidence)
record["flowMatrix"]["localModelDownloadVerification"]["evidenceSha256"] = hashlib.sha256(evidence.read_bytes()).hexdigest()
target.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects weak local model download evidence" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_WEAK_LOCAL_MODEL_FLOW" --report "$ARTIFACT_DIR/release-validation-weak-local-model-flow.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-local-model-flow.properties" "flow-localModelDownloadVerification-model-download-verification-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-local-model-flow.properties" "flow-localModelDownloadVerification-storage-preflight-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-local-model-flow.properties" "flow-localModelDownloadVerification-download-directory-unavailable-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-local-model-flow.properties" "flow-localModelDownloadVerification-download-sha-failure-cleanup-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-local-model-flow.properties" "flow-localModelDownloadVerification-download-insufficient-storage-failure-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-local-model-flow.properties" "flow-localModelDownloadVerification-pending-download-missing-task-recovery-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-local-model-flow.properties" "flow-localModelDownloadVerification-lightweight-alternative-explanation-missing"
VALIDATION_WEAK_CUSTOM_MODEL_FLOW="$TMP_DIR/release-validation-weak-custom-model-flow.json"
VALIDATION_WEAK_CUSTOM_MODEL_FLOW_EVIDENCE="$TMP_DIR/validation-flow-evidence/weak-custom-model.properties"
cat > "$VALIDATION_WEAK_CUSTOM_MODEL_FLOW_EVIDENCE" <<'VALIDATION_WEAK_CUSTOM_MODEL_FLOW_EVIDENCE_PROPERTIES'
status=passed
target=release-flow
flowKey=customModelImportOrUrlRejection
releaseFlowPassed=true
candidateOnly=false
VALIDATION_WEAK_CUSTOM_MODEL_FLOW_EVIDENCE_PROPERTIES
python3 - "$VALIDATION_APPROVED" "$VALIDATION_WEAK_CUSTOM_MODEL_FLOW" "$VALIDATION_WEAK_CUSTOM_MODEL_FLOW_EVIDENCE" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
evidence = Path(sys.argv[3])
record = json.loads(source.read_text())
record["flowMatrix"]["customModelImportOrUrlRejection"]["evidencePath"] = str(evidence)
record["flowMatrix"]["customModelImportOrUrlRejection"]["evidenceSha256"] = hashlib.sha256(evidence.read_bytes()).hexdigest()
target.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects weak custom model evidence" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_WEAK_CUSTOM_MODEL_FLOW" --report "$ARTIFACT_DIR/release-validation-weak-custom-model-flow.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-custom-model-flow.properties" "flow-customModelImportOrUrlRejection-custom-https-only-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-custom-model-flow.properties" "flow-customModelImportOrUrlRejection-custom-local-non-litertlm-import-rejection-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-custom-model-flow.properties" "flow-customModelImportOrUrlRejection-custom-import-storage-preflight-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-custom-model-flow.properties" "flow-customModelImportOrUrlRejection-custom-import-empty-file-rejection-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-custom-model-flow.properties" "flow-customModelImportOrUrlRejection-custom-import-temp-cleanup-on-copy-failure-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-custom-model-flow.properties" "flow-customModelImportOrUrlRejection-custom-non-litertlm-rejection-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-custom-model-flow.properties" "flow-customModelImportOrUrlRejection-invalid-url-rejection-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-custom-model-flow.properties" "flow-customModelImportOrUrlRejection-custom-unverified-marker-missing"
VALIDATION_WEAK_REMOTE_FLOW="$TMP_DIR/release-validation-weak-remote-flow.json"
VALIDATION_WEAK_REMOTE_FLOW_EVIDENCE="$TMP_DIR/validation-flow-evidence/weak-remote.properties"
cat > "$VALIDATION_WEAK_REMOTE_FLOW_EVIDENCE" <<'VALIDATION_WEAK_REMOTE_FLOW_EVIDENCE_PROPERTIES'
status=passed
target=release-flow
flowKey=remoteHttpsConfiguration
releaseFlowPassed=true
candidateOnly=false
VALIDATION_WEAK_REMOTE_FLOW_EVIDENCE_PROPERTIES
python3 - "$VALIDATION_APPROVED" "$VALIDATION_WEAK_REMOTE_FLOW" "$VALIDATION_WEAK_REMOTE_FLOW_EVIDENCE" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
evidence = Path(sys.argv[3])
record = json.loads(source.read_text())
record["flowMatrix"]["remoteHttpsConfiguration"]["evidencePath"] = str(evidence)
record["flowMatrix"]["remoteHttpsConfiguration"]["evidenceSha256"] = hashlib.sha256(evidence.read_bytes()).hexdigest()
target.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects weak remote evidence" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_WEAK_REMOTE_FLOW" --report "$ARTIFACT_DIR/release-validation-weak-remote-flow.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-remote-flow.properties" "flow-remoteHttpsConfiguration-remote-network-failure-recovery-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-remote-flow.properties" "flow-remoteHttpsConfiguration-remote-unconfigured-model-failure-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-remote-flow.properties" "flow-remoteHttpsConfiguration-remote-local-memory-boundary-missing"
VALIDATION_WEAK_SESSION_FLOW="$TMP_DIR/release-validation-weak-session-flow.json"
VALIDATION_WEAK_SESSION_FLOW_EVIDENCE="$TMP_DIR/validation-flow-evidence/weak-session.properties"
cat > "$VALIDATION_WEAK_SESSION_FLOW_EVIDENCE" <<'VALIDATION_WEAK_SESSION_FLOW_EVIDENCE_PROPERTIES'
status=passed
target=release-flow
flowKey=sessionPersistence
releaseFlowPassed=true
candidateOnly=false
VALIDATION_WEAK_SESSION_FLOW_EVIDENCE_PROPERTIES
python3 - "$VALIDATION_APPROVED" "$VALIDATION_WEAK_SESSION_FLOW" "$VALIDATION_WEAK_SESSION_FLOW_EVIDENCE" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
evidence = Path(sys.argv[3])
record = json.loads(source.read_text())
record["flowMatrix"]["sessionPersistence"]["evidencePath"] = str(evidence)
record["flowMatrix"]["sessionPersistence"]["evidenceSha256"] = hashlib.sha256(evidence.read_bytes()).hexdigest()
target.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects weak session persistence evidence" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_WEAK_SESSION_FLOW" --report "$ARTIFACT_DIR/release-validation-weak-session-flow.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-session-flow.properties" "flow-sessionPersistence-session-create-switch-restore-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-session-flow.properties" "flow-sessionPersistence-active-session-persistence-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-session-flow.properties" "flow-sessionPersistence-session-delete-missing"
VALIDATION_WEAK_SHARE_FLOW="$TMP_DIR/release-validation-weak-share-flow.json"
VALIDATION_WEAK_SHARE_FLOW_EVIDENCE="$TMP_DIR/validation-flow-evidence/weak-share-picker.properties"
cat > "$VALIDATION_WEAK_SHARE_FLOW_EVIDENCE" <<'VALIDATION_WEAK_SHARE_FLOW_EVIDENCE_PROPERTIES'
status=passed
target=release-flow
flowKey=shareAndPickerInput
releaseFlowPassed=true
candidateOnly=false
VALIDATION_WEAK_SHARE_FLOW_EVIDENCE_PROPERTIES
python3 - "$VALIDATION_APPROVED" "$VALIDATION_WEAK_SHARE_FLOW" "$VALIDATION_WEAK_SHARE_FLOW_EVIDENCE" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
evidence = Path(sys.argv[3])
record = json.loads(source.read_text())
record["flowMatrix"]["shareAndPickerInput"]["evidencePath"] = str(evidence)
record["flowMatrix"]["shareAndPickerInput"]["evidenceSha256"] = hashlib.sha256(evidence.read_bytes()).hexdigest()
target.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects weak share and picker evidence" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_WEAK_SHARE_FLOW" --report "$ARTIFACT_DIR/release-validation-weak-share-flow.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-share-flow.properties" "flow-shareAndPickerInput-remote-text-share-protection-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-share-flow.properties" "flow-shareAndPickerInput-remote-vision-image-staging-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-share-flow.properties" "flow-shareAndPickerInput-no-implicit-image-ocr-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-share-flow.properties" "flow-shareAndPickerInput-remote-non-image-attachment-protection-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-share-flow.properties" "flow-shareAndPickerInput-remote-vision-supported-open-stream-count-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-share-flow.properties" "flow-shareAndPickerInput-remote-vision-unsupported-ocr-skip-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-share-flow.properties" "flow-shareAndPickerInput-remote-vision-send-preview-confirmation-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-share-flow.properties" "flow-shareAndPickerInput-remote-vision-cancel-runtime-idle-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-share-flow.properties" "flow-shareAndPickerInput-remote-vision-http-fixture-image-part-count-mismatch"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-share-flow.properties" "flow-shareAndPickerInput-remote-vision-supported-image-stream-count-mismatch"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-share-flow.properties" "flow-shareAndPickerInput-local-vision-image-staging-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-share-flow.properties" "flow-shareAndPickerInput-local-vision-runtime-image-attachment-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-share-flow.properties" "flow-shareAndPickerInput-local-vision-local-only-persistence-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-share-flow.properties" "flow-shareAndPickerInput-local-vision-prompt-metadata-redaction-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-share-flow.properties" "flow-shareAndPickerInput-local-vision-remote-runtime-idle-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-share-flow.properties" "flow-shareAndPickerInput-local-vision-unsupported-ocr-skip-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-share-flow.properties" "flow-shareAndPickerInput-local-vision-runtime-image-attachment-send-count-mismatch"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-share-flow.properties" "flow-shareAndPickerInput-local-vision-remote-runtime-request-count-mismatch"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-share-flow.properties" "flow-shareAndPickerInput-local-vision-unsupported-runtime-image-send-count-mismatch"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-share-flow.properties" "flow-shareAndPickerInput-local-vision-unsupported-image-ocr-count-mismatch"
VALIDATION_LOCAL_VISION_ZERO_COUNT="$TMP_DIR/release-validation-local-vision-zero-count.json"
VALIDATION_LOCAL_VISION_ZERO_COUNT_EVIDENCE="$TMP_DIR/validation-flow-evidence/local-vision-zero-count.properties"
sed 's/^localVisionRuntimeImageAttachmentSendCount=1$/localVisionRuntimeImageAttachmentSendCount=0/' \
  "$TMP_DIR/validation-flow-evidence/shareAndPickerInput.properties" > "$VALIDATION_LOCAL_VISION_ZERO_COUNT_EVIDENCE"
python3 - "$VALIDATION_APPROVED" "$VALIDATION_LOCAL_VISION_ZERO_COUNT" "$VALIDATION_LOCAL_VISION_ZERO_COUNT_EVIDENCE" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
evidence = Path(sys.argv[3])
record = json.loads(source.read_text())
record["flowMatrix"]["shareAndPickerInput"]["evidencePath"] = str(evidence)
record["flowMatrix"]["shareAndPickerInput"]["evidenceSha256"] = hashlib.sha256(evidence.read_bytes()).hexdigest()
target.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects zero local vision runtime image send count" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_LOCAL_VISION_ZERO_COUNT" --report "$ARTIFACT_DIR/release-validation-local-vision-zero-count.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-local-vision-zero-count.properties" "flow-shareAndPickerInput-local-vision-runtime-image-attachment-send-count-mismatch"
VALIDATION_WEAK_VOICE_FLOW="$TMP_DIR/release-validation-weak-voice-flow.json"
VALIDATION_WEAK_VOICE_FLOW_EVIDENCE="$TMP_DIR/validation-flow-evidence/weak-voice.properties"
cat > "$VALIDATION_WEAK_VOICE_FLOW_EVIDENCE" <<'VALIDATION_WEAK_VOICE_FLOW_EVIDENCE_PROPERTIES'
status=passed
target=release-flow
flowKey=voiceInput
releaseFlowPassed=true
candidateOnly=false
VALIDATION_WEAK_VOICE_FLOW_EVIDENCE_PROPERTIES
python3 - "$VALIDATION_APPROVED" "$VALIDATION_WEAK_VOICE_FLOW" "$VALIDATION_WEAK_VOICE_FLOW_EVIDENCE" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
evidence = Path(sys.argv[3])
record = json.loads(source.read_text())
record["flowMatrix"]["voiceInput"]["evidencePath"] = str(evidence)
record["flowMatrix"]["voiceInput"]["evidenceSha256"] = hashlib.sha256(evidence.read_bytes()).hexdigest()
target.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects weak voice input evidence" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_WEAK_VOICE_FLOW" --report "$ARTIFACT_DIR/release-validation-weak-voice-flow.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-voice-flow.properties" "flow-voiceInput-entry-disclosure-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-voice-flow.properties" "flow-voiceInput-draft-no-auto-send-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-voice-flow.properties" "flow-voiceInput-permission-failure-recovery-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-voice-flow.properties" "flow-voiceInput-cancel-path-missing"
VALIDATION_WEAK_PRIVACY_CONTROLS_FLOW="$TMP_DIR/release-validation-weak-privacy-controls-flow.json"
VALIDATION_WEAK_PRIVACY_CONTROLS_FLOW_EVIDENCE="$TMP_DIR/validation-flow-evidence/weak-privacy-controls.properties"
cat > "$VALIDATION_WEAK_PRIVACY_CONTROLS_FLOW_EVIDENCE" <<'VALIDATION_WEAK_PRIVACY_CONTROLS_FLOW_EVIDENCE_PROPERTIES'
status=passed
target=release-flow
flowKey=privacyAndDataControls
releaseFlowPassed=true
candidateOnly=false
VALIDATION_WEAK_PRIVACY_CONTROLS_FLOW_EVIDENCE_PROPERTIES
python3 - "$VALIDATION_APPROVED" "$VALIDATION_WEAK_PRIVACY_CONTROLS_FLOW" "$VALIDATION_WEAK_PRIVACY_CONTROLS_FLOW_EVIDENCE" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
evidence = Path(sys.argv[3])
record = json.loads(source.read_text())
record["flowMatrix"]["privacyAndDataControls"]["evidencePath"] = str(evidence)
record["flowMatrix"]["privacyAndDataControls"]["evidenceSha256"] = hashlib.sha256(evidence.read_bytes()).hexdigest()
target.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects weak privacy and data controls evidence" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_WEAK_PRIVACY_CONTROLS_FLOW" --report "$ARTIFACT_DIR/release-validation-weak-privacy-controls-flow.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-privacy-controls-flow.properties" "flow-privacyAndDataControls-privacy-notice-entry-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-privacy-controls-flow.properties" "flow-privacyAndDataControls-memory-clear-control-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-privacy-controls-flow.properties" "flow-privacyAndDataControls-remote-config-clear-control-missing"
VALIDATION_WEAK_ADAPTIVE_UI_FLOW="$TMP_DIR/release-validation-weak-adaptive-ui-flow.json"
VALIDATION_WEAK_ADAPTIVE_UI_FLOW_EVIDENCE="$TMP_DIR/validation-flow-evidence/weak-adaptive-ui.properties"
cat > "$VALIDATION_WEAK_ADAPTIVE_UI_FLOW_EVIDENCE" <<'VALIDATION_WEAK_ADAPTIVE_UI_FLOW_EVIDENCE_PROPERTIES'
status=passed
target=release-flow
flowKey=adaptiveUi
releaseFlowPassed=true
candidateOnly=false
VALIDATION_WEAK_ADAPTIVE_UI_FLOW_EVIDENCE_PROPERTIES
python3 - "$VALIDATION_APPROVED" "$VALIDATION_WEAK_ADAPTIVE_UI_FLOW" "$VALIDATION_WEAK_ADAPTIVE_UI_FLOW_EVIDENCE" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
evidence = Path(sys.argv[3])
record = json.loads(source.read_text())
record["flowMatrix"]["adaptiveUi"]["evidencePath"] = str(evidence)
record["flowMatrix"]["adaptiveUi"]["evidenceSha256"] = hashlib.sha256(evidence.read_bytes()).hexdigest()
target.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects weak adaptive UI evidence" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_WEAK_ADAPTIVE_UI_FLOW" --report "$ARTIFACT_DIR/release-validation-weak-adaptive-ui-flow.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-adaptive-ui-flow.properties" "flow-adaptiveUi-large-font-reachability-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-adaptive-ui-flow.properties" "flow-adaptiveUi-landscape-reachability-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-adaptive-ui-flow.properties" "flow-adaptiveUi-accessible-labels-missing"
VALIDATION_WEAK_RECENT_MEDIA_OCR_FLOW="$TMP_DIR/release-validation-weak-recent-media-ocr-flow.json"
VALIDATION_WEAK_RECENT_MEDIA_OCR_FLOW_EVIDENCE="$TMP_DIR/validation-flow-evidence/weak-recent-media-ocr.properties"
cat > "$VALIDATION_WEAK_RECENT_MEDIA_OCR_FLOW_EVIDENCE" <<'VALIDATION_WEAK_RECENT_MEDIA_OCR_FLOW_EVIDENCE_PROPERTIES'
status=passed
target=release-flow
flowKey=recentMediaOcr
releaseFlowPassed=true
candidateOnly=false
VALIDATION_WEAK_RECENT_MEDIA_OCR_FLOW_EVIDENCE_PROPERTIES
python3 - "$VALIDATION_APPROVED" "$VALIDATION_WEAK_RECENT_MEDIA_OCR_FLOW" "$VALIDATION_WEAK_RECENT_MEDIA_OCR_FLOW_EVIDENCE" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
evidence = Path(sys.argv[3])
record = json.loads(source.read_text())
record["flowMatrix"]["recentMediaOcr"]["evidencePath"] = str(evidence)
record["flowMatrix"]["recentMediaOcr"]["evidenceSha256"] = hashlib.sha256(evidence.read_bytes()).hexdigest()
target.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects weak recent media OCR evidence" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_WEAK_RECENT_MEDIA_OCR_FLOW" --report "$ARTIFACT_DIR/release-validation-weak-recent-media-ocr-flow.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-recent-media-ocr-flow.properties" "flow-recentMediaOcr-recent-screenshot-ocr-routing-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-recent-media-ocr-flow.properties" "flow-recentMediaOcr-recent-media-ocr-local-only-protection-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-recent-media-ocr-flow.properties" "flow-recentMediaOcr-recent-media-ocr-remote-leakage-block-missing"
VALIDATION_BARE_MANUAL="$TMP_DIR/release-validation-bare-manual.json"
python3 - "$VALIDATION_APPROVED" "$VALIDATION_BARE_MANUAL" <<'PY'
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
record = json.loads(source.read_text())
record["manualAcceptance"]["modelSetup"] = "passed"
target.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects bare passed manual acceptance" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_BARE_MANUAL" --report "$ARTIFACT_DIR/release-validation-bare-manual.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-bare-manual.properties" "manual-modelSetup-evidence-record-invalid"
VALIDATION_WEAK_MANUAL="$TMP_DIR/release-validation-weak-manual.json"
VALIDATION_WEAK_MANUAL_EVIDENCE="$TMP_DIR/validation-manual-evidence/weak-modelSetup.properties"
cat > "$VALIDATION_WEAK_MANUAL_EVIDENCE" <<'VALIDATION_WEAK_MANUAL_EVIDENCE_PROPERTIES'
status=passed
manual=modelSetup
VALIDATION_WEAK_MANUAL_EVIDENCE_PROPERTIES
python3 - "$VALIDATION_APPROVED" "$VALIDATION_WEAK_MANUAL" "$VALIDATION_WEAK_MANUAL_EVIDENCE" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
evidence = Path(sys.argv[3])
record = json.loads(source.read_text())
record["manualAcceptance"]["modelSetup"]["evidencePath"] = str(evidence)
record["manualAcceptance"]["modelSetup"]["evidenceSha256"] = hashlib.sha256(evidence.read_bytes()).hexdigest()
target.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects weak manual acceptance evidence" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_WEAK_MANUAL" --report "$ARTIFACT_DIR/release-validation-weak-manual.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-manual.properties" "manual-modelSetup-evidence-target-invalid"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-manual.properties" "manual-modelSetup-evidence-key-mismatch"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-manual.properties" "manual-modelSetup-evidence-manual-acceptance-not-true"
VALIDATION_MANUAL_MISSING_AUDIT="$TMP_DIR/release-validation-manual-missing-audit.json"
VALIDATION_MANUAL_MISSING_AUDIT_EVIDENCE="$TMP_DIR/validation-manual-evidence/modelSetup-missing-audit.properties"
grep -Ev '^(artifactSchema|recordedAt|command|reproduciblePath)=' \
  "$TMP_DIR/validation-manual-evidence/modelSetup.properties" > "$VALIDATION_MANUAL_MISSING_AUDIT_EVIDENCE"
python3 - "$VALIDATION_APPROVED" "$VALIDATION_MANUAL_MISSING_AUDIT" "$VALIDATION_MANUAL_MISSING_AUDIT_EVIDENCE" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
evidence = Path(sys.argv[3])
record = json.loads(source.read_text())
record["manualAcceptance"]["modelSetup"]["evidencePath"] = str(evidence)
record["manualAcceptance"]["modelSetup"]["evidenceSha256"] = hashlib.sha256(evidence.read_bytes()).hexdigest()
target.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects manual evidence missing audit fields" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_MANUAL_MISSING_AUDIT" --report "$ARTIFACT_DIR/release-validation-manual-missing-audit.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-manual-missing-audit.properties" "manual-modelSetup-evidence-artifact-schema-invalid"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-manual-missing-audit.properties" "manual-modelSetup-evidence-recorded-at-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-manual-missing-audit.properties" "manual-modelSetup-evidence-command-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-manual-missing-audit.properties" "manual-modelSetup-evidence-reproducible-path-invalid"
VALIDATION_EMULATOR_BAD_SHA="$TMP_DIR/release-validation-emulator-bad-sha.json"
python3 - "$VALIDATION_APPROVED" "$VALIDATION_EMULATOR_BAD_SHA" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
record["emulatorRegression"]["reportSha256"] = "0" * 64
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects emulator report sha mismatch" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_EMULATOR_BAD_SHA" --report "$ARTIFACT_DIR/release-validation-emulator-bad-sha.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-emulator-bad-sha.properties" "emulator-report-sha-mismatch"
VALIDATION_API_BAD_SHA="$TMP_DIR/release-validation-api-bad-sha.json"
python3 - "$VALIDATION_APPROVED" "$VALIDATION_API_BAD_SHA" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
record["apiMatrix"][0]["evidenceSha256"] = "0" * 64
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects api evidence sha mismatch" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_API_BAD_SHA" --report "$ARTIFACT_DIR/release-validation-api-bad-sha.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-api-bad-sha.properties" "api-28-evidence-sha-mismatch"
VALIDATION_API_WEAK_EVIDENCE="$TMP_DIR/release-validation-api-weak-evidence.json"
VALIDATION_API_WEAK_EVIDENCE_FILE="$TMP_DIR/validation-api-evidence/weak-api-28.properties"
cat > "$VALIDATION_API_WEAK_EVIDENCE_FILE" <<'VALIDATION_API_WEAK_EVIDENCE_PROPERTIES'
status=passed
api_level=28
VALIDATION_API_WEAK_EVIDENCE_PROPERTIES
python3 - "$VALIDATION_APPROVED" "$VALIDATION_API_WEAK_EVIDENCE" "$VALIDATION_API_WEAK_EVIDENCE_FILE" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
evidence = Path(sys.argv[3])
record = json.loads(source.read_text())
record["apiMatrix"][0]["evidencePath"] = str(evidence)
record["apiMatrix"][0]["evidenceSha256"] = hashlib.sha256(evidence.read_bytes()).hexdigest()
target.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects weak api matrix evidence" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_API_WEAK_EVIDENCE" --report "$ARTIFACT_DIR/release-validation-api-weak-evidence.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-api-weak-evidence.properties" "api-28-evidence-target-invalid"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-api-weak-evidence.properties" "api-28-evidence-test-count-invalid"
VALIDATION_API_MISSING_NESTED="$TMP_DIR/release-validation-api-missing-nested.json"
VALIDATION_API_MISSING_NESTED_FILE="$TMP_DIR/validation-api-evidence/missing-nested-api-28.properties"
cat > "$VALIDATION_API_MISSING_NESTED_FILE" <<VALIDATION_API_MISSING_NESTED_PROPERTIES
status=passed
exit_code=0
target=regression-emulator
failedTarget=
reason=
started_at_utc=2026-06-06T00:00:00Z
finished_at_utc=2026-06-06T00:01:00Z
clean_device=1
source_android_test_count=$SOURCE_ANDROID_TEST_COUNT
expected_android_test_count=$SOURCE_ANDROID_TEST_COUNT
actual_android_test_count=$SOURCE_ANDROID_TEST_COUNT
serial=emulator-28
api_level=28
abi=arm64-v8a
avd=api28-avd
VALIDATION_API_MISSING_NESTED_PROPERTIES
python3 - "$VALIDATION_APPROVED" "$VALIDATION_API_MISSING_NESTED" "$VALIDATION_API_MISSING_NESTED_FILE" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
evidence = Path(sys.argv[3])
record = json.loads(source.read_text())
record["apiMatrix"][0]["evidencePath"] = str(evidence)
record["apiMatrix"][0]["evidenceSha256"] = hashlib.sha256(evidence.read_bytes()).hexdigest()
target.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects api matrix evidence without nested reports" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_API_MISSING_NESTED" --report "$ARTIFACT_DIR/release-validation-api-missing-nested.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-api-missing-nested.properties" "api-28-evidence-instrumentation-output-file-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-api-missing-nested.properties" "api-28-emulator-report-path-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-api-missing-nested.properties" "api-28-device-report-path-missing"
VALIDATION_API_MISSING_SMOKE="$TMP_DIR/release-validation-api-missing-smoke.json"
VALIDATION_API_MISSING_SMOKE_DIR="$TMP_DIR/validation-api-evidence/missing-smoke-api-28"
VALIDATION_API_MISSING_SMOKE_EMULATOR_REPORT="$VALIDATION_API_MISSING_SMOKE_DIR/emulator-verification.properties"
VALIDATION_API_MISSING_SMOKE_EVIDENCE="$TMP_DIR/validation-api-evidence/missing-smoke-api-28.properties"
mkdir -p "$VALIDATION_API_MISSING_SMOKE_DIR"
grep -v '^crash_anr_smoke_report_file=' "$TMP_DIR/validation-api-evidence/api-28/emulator-verification.properties" > "$VALIDATION_API_MISSING_SMOKE_EMULATOR_REPORT"
sed "s#^emulator_report_file=.*#emulator_report_file=$VALIDATION_API_MISSING_SMOKE_EMULATOR_REPORT#" \
  "$TMP_DIR/validation-api-evidence/api-28.properties" > "$VALIDATION_API_MISSING_SMOKE_EVIDENCE"
python3 - "$VALIDATION_APPROVED" "$VALIDATION_API_MISSING_SMOKE" "$VALIDATION_API_MISSING_SMOKE_EVIDENCE" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
evidence = Path(sys.argv[3])
record = json.loads(source.read_text())
record["apiMatrix"][0]["evidencePath"] = str(evidence)
record["apiMatrix"][0]["evidenceSha256"] = hashlib.sha256(evidence.read_bytes()).hexdigest()
target.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects api matrix nested emulator report without crash smoke evidence" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_API_MISSING_SMOKE" --report "$ARTIFACT_DIR/release-validation-api-missing-smoke.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-api-missing-smoke.properties" "api-28-emulator-report-crash-anr-smoke-path-missing"
VALIDATION_MANUAL_BAD_SHA="$TMP_DIR/release-validation-manual-bad-sha.json"
python3 - "$VALIDATION_APPROVED" "$VALIDATION_MANUAL_BAD_SHA" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
record["manualAcceptance"]["modelSetup"]["evidenceSha256"] = "0" * 64
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects manual evidence sha mismatch" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_MANUAL_BAD_SHA" --report "$ARTIFACT_DIR/release-validation-manual-bad-sha.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-manual-bad-sha.properties" "manual-modelSetup-evidence-sha-mismatch"
VALIDATION_PERF_WEAK_EVIDENCE="$TMP_DIR/release-validation-perf-weak-evidence.json"
VALIDATION_PERF_WEAK_EVIDENCE_FILE="$TMP_DIR/validation-performance-evidence/weak-firstLaunch.properties"
cat > "$VALIDATION_PERF_WEAK_EVIDENCE_FILE" <<'VALIDATION_PERF_WEAK_EVIDENCE_PROPERTIES'
status=passed
performance=firstLaunch
VALIDATION_PERF_WEAK_EVIDENCE_PROPERTIES
python3 - "$VALIDATION_APPROVED" "$VALIDATION_PERF_WEAK_EVIDENCE" "$VALIDATION_PERF_WEAK_EVIDENCE_FILE" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
evidence = Path(sys.argv[3])
record = json.loads(source.read_text())
record["performanceSanity"]["firstLaunch"]["evidencePath"] = str(evidence)
record["performanceSanity"]["firstLaunch"]["evidenceSha256"] = hashlib.sha256(evidence.read_bytes()).hexdigest()
target.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects weak performance sanity evidence" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_PERF_WEAK_EVIDENCE" --report "$ARTIFACT_DIR/release-validation-perf-weak-evidence.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-perf-weak-evidence.properties" "performance-firstLaunch-evidence-target-invalid"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-perf-weak-evidence.properties" "performance-firstLaunch-evidence-key-mismatch"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-perf-weak-evidence.properties" "performance-firstLaunch-evidence-expected-artifact-sha-invalid"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-perf-weak-evidence.properties" "performance-firstLaunch-evidence-expected-app-version-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-perf-weak-evidence.properties" "performance-firstLaunch-evidence-baseline-file-missing"
VALIDATION_PERF_KEY_MISMATCH="$TMP_DIR/release-validation-perf-key-mismatch.json"
python3 - "$VALIDATION_APPROVED" "$VALIDATION_PERF_KEY_MISMATCH" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
first_launch_evidence = Path(record["performanceSanity"]["firstLaunch"]["evidencePath"])
record["performanceSanity"]["modelLoad"]["evidencePath"] = str(first_launch_evidence)
record["performanceSanity"]["modelLoad"]["evidenceSha256"] = hashlib.sha256(first_launch_evidence.read_bytes()).hexdigest()
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects performance evidence key mismatch" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_PERF_KEY_MISMATCH" --report "$ARTIFACT_DIR/release-validation-perf-key-mismatch.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-perf-key-mismatch.properties" "performance-modelLoad-evidence-key-mismatch"
VALIDATION_PERF_BASELINE_SHA_MISMATCH="$TMP_DIR/release-validation-perf-baseline-sha-mismatch.json"
VALIDATION_PERF_BASELINE_SHA_MISMATCH_EVIDENCE="$TMP_DIR/validation-performance-evidence/firstLaunch-sha-mismatch.properties"
cat > "$VALIDATION_PERF_BASELINE_SHA_MISMATCH_EVIDENCE" <<VALIDATION_PERF_BASELINE_SHA_MISMATCH_EVIDENCE_PROPERTIES
status=passed
target=perf-baseline
performanceKey=firstLaunch
baselineFile=$VALIDATION_PERF_BASELINE
baselineSha256=$VALIDATION_PERF_BASELINE_SHA
missingFieldCount=0
expectedArtifactSha256=2222222222222222222222222222222222222222222222222222222222222222
expectedAppVersion=0.1.0
maxRecordAgeDays=30
VALIDATION_PERF_BASELINE_SHA_MISMATCH_EVIDENCE_PROPERTIES
python3 - "$VALIDATION_APPROVED" "$VALIDATION_PERF_BASELINE_SHA_MISMATCH" "$VALIDATION_PERF_BASELINE_SHA_MISMATCH_EVIDENCE" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
evidence = Path(sys.argv[3])
record = json.loads(source.read_text())
record["performanceSanity"]["firstLaunch"]["evidencePath"] = str(evidence)
record["performanceSanity"]["firstLaunch"]["evidenceSha256"] = hashlib.sha256(evidence.read_bytes()).hexdigest()
target.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects performance baseline artifact sha mismatch" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_PERF_BASELINE_SHA_MISMATCH" --report "$ARTIFACT_DIR/release-validation-perf-baseline-sha-mismatch.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-perf-baseline-sha-mismatch.properties" "performance-firstLaunch-evidence-baseline-artifact-sha-mismatch"
VALIDATION_PERF_BASELINE_FILE_SHA_MISMATCH="$TMP_DIR/release-validation-perf-baseline-file-sha-mismatch.json"
VALIDATION_PERF_BASELINE_FILE_SHA_MISMATCH_EVIDENCE="$TMP_DIR/validation-performance-evidence/firstLaunch-file-sha-mismatch.properties"
cat > "$VALIDATION_PERF_BASELINE_FILE_SHA_MISMATCH_EVIDENCE" <<VALIDATION_PERF_BASELINE_FILE_SHA_MISMATCH_EVIDENCE_PROPERTIES
status=passed
target=perf-baseline
performanceKey=firstLaunch
baselineFile=$VALIDATION_PERF_BASELINE
baselineSha256=0000000000000000000000000000000000000000000000000000000000000000
missingFieldCount=0
expectedArtifactSha256=$VALID_PERF_SHA
expectedAppVersion=0.1.0
maxRecordAgeDays=30
VALIDATION_PERF_BASELINE_FILE_SHA_MISMATCH_EVIDENCE_PROPERTIES
python3 - "$VALIDATION_APPROVED" "$VALIDATION_PERF_BASELINE_FILE_SHA_MISMATCH" "$VALIDATION_PERF_BASELINE_FILE_SHA_MISMATCH_EVIDENCE" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
evidence = Path(sys.argv[3])
record = json.loads(source.read_text())
record["performanceSanity"]["firstLaunch"]["evidencePath"] = str(evidence)
record["performanceSanity"]["firstLaunch"]["evidenceSha256"] = hashlib.sha256(evidence.read_bytes()).hexdigest()
target.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects performance baseline file sha mismatch" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_PERF_BASELINE_FILE_SHA_MISMATCH" --report "$ARTIFACT_DIR/release-validation-perf-baseline-file-sha-mismatch.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-perf-baseline-file-sha-mismatch.properties" "performance-firstLaunch-evidence-baseline-sha-mismatch"
VALIDATION_SCREENSHOT_BAD_SHA="$TMP_DIR/release-validation-screenshot-bad-sha.json"
python3 - "$VALIDATION_APPROVED" "$VALIDATION_SCREENSHOT_BAD_SHA" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
record["screenshots"][0]["sha256"] = "0" * 64
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects screenshot sha mismatch" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_SCREENSHOT_BAD_SHA" --report "$ARTIFACT_DIR/release-validation-screenshot-bad-sha.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-screenshot-bad-sha.properties" "chat-home-screenshot-sha-mismatch"
VALIDATION_SCREENSHOT_NOT_PNG="$TMP_DIR/release-validation-screenshot-not-png.json"
VALIDATION_SCREENSHOT_NOT_PNG_FILE="$TMP_DIR/validation-screenshots/not-png-chat-home.png"
VALIDATION_SCREENSHOT_NOT_PNG_REPORT="$TMP_DIR/release-screenshots-not-png.properties"
python3 - "$VALIDATION_APPROVED" "$VALIDATION_SCREENSHOT_NOT_PNG" "$VALIDATION_SCREENSHOT_NOT_PNG_FILE" "$VALIDATION_SCREENSHOT_NOT_PNG_REPORT" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
screenshot = Path(sys.argv[3])
report = Path(sys.argv[4])
record = json.loads(source.read_text())
screenshot.write_text("not a png\n")
screenshot_sha = hashlib.sha256(screenshot.read_bytes()).hexdigest()
ui_dump = screenshot.with_suffix(".xml")
ui_dump.write_text(
    "\n".join(
        [
            "<hierarchy>",
            '  <node text="PocketMind" />',
            '  <node text="隐私优先的随身 AI 助手" />',
            '  <node text="为什么装它" />',
            '  <node text="模型管理" />',
            "</hierarchy>",
            "",
        ]
    )
)
ui_dump_sha = hashlib.sha256(ui_dump.read_bytes()).hexdigest()
report.write_text(
    "\n".join(
        [
            "status=passed",
            "exit_code=0",
            "target=release-screenshots",
            "clean_device=1",
            "serial=emulator-5554",
            "api_level=36",
            "abi=arm64-v8a",
            "avd=test-avd",
            f"screenshot.chat-home.path={screenshot}",
            f"screenshot.chat-home.sha256={screenshot_sha}",
            "screenshot.chat-home.sanitized=true",
            f"screenshot.chat-home.uiDump={ui_dump}",
            f"screenshot.chat-home.uiDumpSha256={ui_dump_sha}",
            "screenshot.chat-home.visualRegression=passed",
            "screenshot.chat-home.requiredText=PocketMind|隐私优先的随身 AI 助手|为什么装它|模型管理",
            "",
        ]
    )
)
record["screenshots"][0]["path"] = str(screenshot)
record["screenshots"][0]["sha256"] = screenshot_sha
record["screenshots"][0]["reportPath"] = str(report)
record["screenshots"][0]["reportSha256"] = hashlib.sha256(report.read_bytes()).hexdigest()
target.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects non-png screenshot evidence" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_SCREENSHOT_NOT_PNG" --report "$ARTIFACT_DIR/release-validation-screenshot-not-png.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-screenshot-not-png.properties" "chat-home-screenshot-not-png"
VALIDATION_SCREENSHOT_WEAK_VISUAL="$TMP_DIR/release-validation-screenshot-weak-visual.json"
VALIDATION_SCREENSHOT_WEAK_VISUAL_REPORT="$TMP_DIR/release-screenshots-weak-visual.properties"
python3 - "$VALIDATION_APPROVED" "$VALIDATION_SCREENSHOT_WEAK_VISUAL" "$VALIDATION_SCREENSHOT_REPORT" "$VALIDATION_SCREENSHOT_WEAK_VISUAL_REPORT" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
report = Path(sys.argv[3])
weak_report = Path(sys.argv[4])
record = json.loads(source.read_text())
weak_lines = [
    line
    for line in report.read_text().splitlines()
    if not line.startswith("screenshot.chat-home.uiDump")
    and not line.startswith("screenshot.chat-home.uiDumpSha256")
    and not line.startswith("screenshot.chat-home.visualRegression")
]
weak_report.write_text("\n".join(weak_lines) + "\n")
record["screenshots"][0]["reportPath"] = str(weak_report)
record["screenshots"][0]["reportSha256"] = hashlib.sha256(weak_report.read_bytes()).hexdigest()
target.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects screenshot report without visual contract" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_SCREENSHOT_WEAK_VISUAL" --report "$ARTIFACT_DIR/release-validation-screenshot-weak-visual.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-screenshot-weak-visual.properties" "chat-home-screenshot-visual-regression-not-passed"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-screenshot-weak-visual.properties" "chat-home-screenshot-ui-dump-missing"
VALIDATION_MISSING_DEVICE="$TMP_DIR/release-validation-missing-device.json"
sed 's#"reportPath": "'"$VALIDATION_DEVICE_REPORT"'"#"reportPath": "'"$TMP_DIR/missing-device.properties"'"#' "$VALIDATION_APPROVED" > "$VALIDATION_MISSING_DEVICE"
expect_failure \
  "release validation verifier rejects missing physical report" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_MISSING_DEVICE" --report "$ARTIFACT_DIR/release-validation-missing-device.properties"
assert_report_contains "$ARTIFACT_DIR/release-validation-missing-device.properties" "status=failed"
VALIDATION_WEAK_DEVICE_REPORT_RECORD="$TMP_DIR/release-validation-weak-device-report.json"
VALIDATION_WEAK_DEVICE_REPORT="$TMP_DIR/weak-device-verification.properties"
cat > "$VALIDATION_WEAK_DEVICE_REPORT" <<VALIDATION_WEAK_DEVICE_REPORT_PROPERTIES
status=passed
target=device
serial=device-a
api_level=36
abi=arm64-v8a
clean_device=1
instrumentation=passed
instrumentation_test_count=$SOURCE_ANDROID_TEST_COUNT
VALIDATION_WEAK_DEVICE_REPORT_PROPERTIES
python3 - "$VALIDATION_APPROVED" "$VALIDATION_WEAK_DEVICE_REPORT_RECORD" "$VALIDATION_WEAK_DEVICE_REPORT" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
report = Path(sys.argv[3])
record = json.loads(source.read_text())
record["physicalDevice"]["reportPath"] = str(report)
record["physicalDevice"]["reportSha256"] = hashlib.sha256(report.read_bytes()).hexdigest()
target.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects weak physical device report" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_WEAK_DEVICE_REPORT_RECORD" --report "$ARTIFACT_DIR/release-validation-weak-device-report.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-device-report.properties" "physical-device-report-exit-code-invalid"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-device-report.properties" "physical-device-report-started-at-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-device-report.properties" "physical-device-report-data-free-invalid"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-device-report.properties" "physical-device-report-instrumentation-output-file-missing"
VALIDATION_MANUAL_INSTALL_AS_DEVICE_RECORD="$TMP_DIR/release-validation-manual-install-as-device.json"
VALIDATION_MANUAL_INSTALL_REPORT="$TMP_DIR/manual-acceptance-install-device.properties"
cat > "$VALIDATION_MANUAL_INSTALL_REPORT" <<VALIDATION_MANUAL_INSTALL_REPORT_PROPERTIES
status=passed
exit_code=0
target=manual-acceptance-install
manualAcceptanceInstall=true
regressionEvidence=false
started_at_utc=2026-06-06T00:00:00Z
finished_at_utc=2026-06-06T00:01:00Z
serial=device-a
api_level=36
abi=arm64-v8a
clean_device=0
kept_app_data=1
instrumentation=not-run
VALIDATION_MANUAL_INSTALL_REPORT_PROPERTIES
python3 - "$VALIDATION_APPROVED" "$VALIDATION_MANUAL_INSTALL_AS_DEVICE_RECORD" "$VALIDATION_MANUAL_INSTALL_REPORT" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
report = Path(sys.argv[3])
record = json.loads(source.read_text())
record["physicalDevice"]["reportPath"] = str(report)
record["physicalDevice"]["reportSha256"] = hashlib.sha256(report.read_bytes()).hexdigest()
target.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects manual install report as physical regression evidence" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_MANUAL_INSTALL_AS_DEVICE_RECORD" --report "$ARTIFACT_DIR/release-validation-manual-install-as-device.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-manual-install-as-device.properties" "physical-device-report-target-invalid"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-manual-install-as-device.properties" "physical-device-report-instrumentation-not-passed"
VALIDATION_DEVICE_OUTPUT_COUNT_MISMATCH="$TMP_DIR/release-validation-device-output-count-mismatch.json"
VALIDATION_DEVICE_OUTPUT_COUNT_MISMATCH_REPORT="$TMP_DIR/device-output-count-mismatch.properties"
VALIDATION_DEVICE_OUTPUT_COUNT_MISMATCH_OUTPUT="$TMP_DIR/instrumentation-count-mismatch.txt"
printf 'OK (1 test)\n' > "$VALIDATION_DEVICE_OUTPUT_COUNT_MISMATCH_OUTPUT"
cat > "$VALIDATION_DEVICE_OUTPUT_COUNT_MISMATCH_REPORT" <<VALIDATION_DEVICE_OUTPUT_COUNT_MISMATCH_PROPERTIES
status=passed
exit_code=0
target=device
failedTarget=
reason=
started_at_utc=2026-06-06T00:00:00Z
finished_at_utc=2026-06-06T00:01:00Z
serial=device-a
api_level=36
abi=arm64-v8a
clean_device=1
data_free_kb=4194304
instrumentation=passed
instrumentation_test_count=$SOURCE_ANDROID_TEST_COUNT
instrumentation_output_file=$VALIDATION_DEVICE_OUTPUT_COUNT_MISMATCH_OUTPUT
debug_apk=app/build/outputs/apk/debug/app-debug.apk
android_test_apk=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
VALIDATION_DEVICE_OUTPUT_COUNT_MISMATCH_PROPERTIES
python3 - "$VALIDATION_APPROVED" "$VALIDATION_DEVICE_OUTPUT_COUNT_MISMATCH" "$VALIDATION_DEVICE_OUTPUT_COUNT_MISMATCH_REPORT" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
report = Path(sys.argv[3])
record = json.loads(source.read_text())
record["physicalDevice"]["reportPath"] = str(report)
record["physicalDevice"]["reportSha256"] = hashlib.sha256(report.read_bytes()).hexdigest()
target.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects physical report output count mismatch" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_DEVICE_OUTPUT_COUNT_MISMATCH" --report "$ARTIFACT_DIR/release-validation-device-output-count-mismatch.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-device-output-count-mismatch.properties" "physical-device-report-instrumentation-output-count-mismatch"
VALIDATION_EMULATOR_AS_PHYSICAL="$TMP_DIR/release-validation-emulator-as-physical.json"
sed \
  -e 's#"reportPath": "'"$VALIDATION_DEVICE_REPORT"'"#"reportPath": "'"$VALIDATION_EMULATOR_DEVICE_REPORT"'"#' \
  -e 's/"serial": "device-a"/"serial": "emulator-5554"/' \
  "$VALIDATION_APPROVED" > "$VALIDATION_EMULATOR_AS_PHYSICAL"
expect_failure \
  "release validation verifier rejects emulator device report as physical evidence" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_EMULATOR_AS_PHYSICAL" --report "$ARTIFACT_DIR/release-validation-emulator-as-physical.properties"
assert_report_contains "$ARTIFACT_DIR/release-validation-emulator-as-physical.properties" "status=failed"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-emulator-as-physical.properties" "physical-device-serial-invalid"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-emulator-as-physical.properties" "physical-device-report-serial-is-emulator"
VALIDATION_API_GAP="$TMP_DIR/release-validation-api-gap.json"
python3 - "$VALIDATION_APPROVED" "$VALIDATION_API_GAP" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
for entry in record["apiMatrix"]:
    if entry.get("apiLevel") == 34:
        entry["status"] = "pending"
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects incomplete api matrix" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_API_GAP" --report "$ARTIFACT_DIR/release-validation-api-gap.properties"
assert_report_contains "$ARTIFACT_DIR/release-validation-api-gap.properties" "status=failed"
VALIDATION_API_MISSING_EVIDENCE="$TMP_DIR/release-validation-api-missing-evidence.json"
sed 's#'"$TMP_DIR"'/validation-api-evidence/api-34.properties#'"$TMP_DIR"'/validation-api-evidence/missing-api-34.properties#' "$VALIDATION_APPROVED" > "$VALIDATION_API_MISSING_EVIDENCE"
expect_failure \
  "release validation verifier rejects missing api evidence file" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_API_MISSING_EVIDENCE" --report "$ARTIFACT_DIR/release-validation-api-missing-evidence.properties"
assert_report_contains "$ARTIFACT_DIR/release-validation-api-missing-evidence.properties" "status=failed"
VALIDATION_UNSANITIZED_SCREENSHOT="$TMP_DIR/release-validation-unsanitized-screenshot.json"
python3 - "$VALIDATION_APPROVED" "$VALIDATION_UNSANITIZED_SCREENSHOT" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
for entry in record["screenshots"]:
    if entry.get("name") == "chat-home":
        entry["sanitized"] = False
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects unsanitized screenshots" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_UNSANITIZED_SCREENSHOT" --report "$ARTIFACT_DIR/release-validation-unsanitized.properties"
assert_report_contains "$ARTIFACT_DIR/release-validation-unsanitized.properties" "status=failed"

FLOW_CANDIDATE_EMULATOR_REPORT="$TMP_DIR/flow-candidate-regression-emulator.properties"
FLOW_CANDIDATE_STALE_EMULATOR_REPORT="$TMP_DIR/flow-candidate-stale-regression-emulator.properties"
FLOW_CANDIDATE_RECORD_PENDING="$TMP_DIR/flow-candidate-pending.json"
FLOW_CANDIDATE_RECORD_APPROVED="$TMP_DIR/flow-candidate-approved.json"
FLOW_CANDIDATE_RECORD_BAD_SHA="$TMP_DIR/flow-candidate-bad-sha.json"
FLOW_CANDIDATE_RECORD_STALE_REPORT="$TMP_DIR/flow-candidate-stale-report.json"
FLOW_CANDIDATE_EVIDENCE_DIR="$TMP_DIR/flow-candidate-approved-evidence"
mkdir -p "$FLOW_CANDIDATE_EVIDENCE_DIR"
cat > "$FLOW_CANDIDATE_EMULATOR_REPORT" <<FLOW_CANDIDATE_EMULATOR_REPORT_PROPERTIES
status=passed
target=regression-emulator
clean_device=1
source_android_test_count=$SOURCE_ANDROID_TEST_COUNT
expected_android_test_count=$SOURCE_ANDROID_TEST_COUNT
actual_android_test_count=$SOURCE_ANDROID_TEST_COUNT
avd=test-avd
api_level=36
abi=arm64-v8a
FLOW_CANDIDATE_EMULATOR_REPORT_PROPERTIES
FLOW_CANDIDATE_EMULATOR_SHA="$(shasum -a 256 "$FLOW_CANDIDATE_EMULATOR_REPORT" | awk '{print $1}')"
cat > "$FLOW_CANDIDATE_RECORD_PENDING" <<FLOW_CANDIDATE_RECORD_PENDING_JSON
{
  "version": 1,
  "status": "pending_validation",
  "emulatorRegression": {
    "status": "passed",
    "reportPath": "$FLOW_CANDIDATE_EMULATOR_REPORT",
    "reportSha256": "$FLOW_CANDIDATE_EMULATOR_SHA"
  },
  "flowMatrix": {
    "firstInstall": "pending"
  }
}
FLOW_CANDIDATE_RECORD_PENDING_JSON
expect_failure \
  "release flow matrix collector writes candidate evidence while approved record is incomplete" \
  scripts/collect_release_flow_matrix_evidence.sh \
    --file "$FLOW_CANDIDATE_RECORD_PENDING" \
    --artifact-dir "$ARTIFACT_DIR/release-flow-candidate-pending" \
    --report "$ARTIFACT_DIR/release-flow-candidate-pending.properties"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending.properties" "target=release-flow-matrix-candidate-evidence"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending.properties" "failedTarget=flow-matrix"
assert_report_contains_text "$ARTIFACT_DIR/release-flow-candidate-pending.properties" "missing-approved-release-evidence"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending.properties" "generatedCandidateFlows=firstInstall,localModelDownloadVerification,customModelImportOrUrlRejection,remoteHttpsConfiguration,encryptedApiKeyClear,sessionPersistence,memoryControls,privacyAndDataControls,remindersAfterReboot,shareAndPickerInput,voiceInput,adaptiveUi,accessibilityText,recentMediaOcr,mediaProjectionCancellation"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-firstInstall.properties" "candidateOnly=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-firstInstall.properties" "releaseFlowPassed=false"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-firstInstall.properties" "firstRunSetupVisibleCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-firstInstall.properties" "firstRunDefaultChatModelSelected=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-firstInstall.properties" "firstRunSkipReachesMainShell=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-encryptedApiKeyClear.properties" "encryptedApiKeyBlankInputClearsSecret=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-encryptedApiKeyClear.properties" "legacyPlaintextApiKeyNotPersisted=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-sessionPersistence.properties" "sessionCreateSwitchRestoreCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-memoryControls.properties" "memoryPanelControlCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-remindersAfterReboot.properties" "bootCompletedReminderRescheduleCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-accessibilityText.properties" "accessibilityTextLocalOnlyMetadataCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-recentMediaOcr.properties" "recentMediaOcrRemoteLeakageBlocked=true"
for generated_flow_key in \
  firstInstall localModelDownloadVerification customModelImportOrUrlRejection \
  remoteHttpsConfiguration encryptedApiKeyClear sessionPersistence memoryControls \
  privacyAndDataControls remindersAfterReboot shareAndPickerInput voiceInput adaptiveUi accessibilityText \
  recentMediaOcr mediaProjectionCancellation; do
  assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-$generated_flow_key.properties" "status=passed"
  assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-$generated_flow_key.properties" "target=release-flow-matrix-candidate-evidence"
  assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-$generated_flow_key.properties" "flow=$generated_flow_key"
  assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-$generated_flow_key.properties" "candidateOnly=true"
  assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-$generated_flow_key.properties" "releaseFlowPassed=false"
done
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-localModelDownloadVerification.properties" "localModelDownloadVerified=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-localModelDownloadVerification.properties" "modelSha256VerificationCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-localModelDownloadVerification.properties" "storagePreflightCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-localModelDownloadVerification.properties" "downloadDirectoryUnavailableCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-localModelDownloadVerification.properties" "downloadShaFailureCleanupCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-localModelDownloadVerification.properties" "downloadInsufficientStorageFailureCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-localModelDownloadVerification.properties" "pendingDownloadMissingTaskRecoveryCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-localModelDownloadVerification.properties" "lightweightAlternativeExplained=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-customModelImportOrUrlRejection.properties" "customLocalNonLitertlmImportRejected=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-customModelImportOrUrlRejection.properties" "customImportStoragePreflightCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-customModelImportOrUrlRejection.properties" "customImportEmptyFileRejected=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-customModelImportOrUrlRejection.properties" "customImportTempCleanupOnCopyFailureCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-customModelImportOrUrlRejection.properties" "customDownloadHttpsOnly=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-customModelImportOrUrlRejection.properties" "customNonLitertlmDownloadRejected=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-customModelImportOrUrlRejection.properties" "customInvalidUrlRejected=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-customModelImportOrUrlRejection.properties" "customUnverifiedModelMarked=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-remoteHttpsConfiguration.properties" "remoteNetworkFailureRecoveryCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-remoteHttpsConfiguration.properties" "remoteUnconfiguredModelFailureCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-remoteHttpsConfiguration.properties" "remoteLocalMemoryNotAutoIncluded=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-shareAndPickerInput.properties" "actionSendTextStaged=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-shareAndPickerInput.properties" "remoteTextShareProtected=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-shareAndPickerInput.properties" "remoteVisionImageAttachmentStaged=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-shareAndPickerInput.properties" "remoteVisionUnsupportedProtected=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-shareAndPickerInput.properties" "noImplicitImageOcr=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-shareAndPickerInput.properties" "remoteNonImageAttachmentNotAutoIncluded=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-shareAndPickerInput.properties" "remoteVisionSupportedOpenStreamCountCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-shareAndPickerInput.properties" "remoteVisionSupportedOcrSkipped=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-shareAndPickerInput.properties" "remoteVisionUnsupportedOpenStreamCountCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-shareAndPickerInput.properties" "remoteVisionUnsupportedOcrSkipped=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-shareAndPickerInput.properties" "remoteVisionMixedShareNonImageProtected=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-shareAndPickerInput.properties" "remoteVisionSendPreviewConfirmed=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-shareAndPickerInput.properties" "remoteVisionCancelKeepsRuntimeIdle=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-shareAndPickerInput.properties" "remoteVisionHttpFixtureImagePartCount=1"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-shareAndPickerInput.properties" "remoteVisionHttpFixtureStreamRequested=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-shareAndPickerInput.properties" "remoteVisionSupportedImageStreamOpenCount=1"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-shareAndPickerInput.properties" "remoteVisionSupportedImageOcrInvocationCount=0"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-shareAndPickerInput.properties" "remoteVisionUnsupportedImageStreamOpenCount=0"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-shareAndPickerInput.properties" "remoteVisionUnsupportedImageOcrInvocationCount=0"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-shareAndPickerInput.properties" "remoteVisionMixedProtectedNonImageCount=1"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-shareAndPickerInput.properties" "localVisionVerifiedModelImageAttachmentStaged=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-shareAndPickerInput.properties" "localVisionRuntimeImageAttachmentSent=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-shareAndPickerInput.properties" "localVisionLocalOnlyPersistenceCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-shareAndPickerInput.properties" "localVisionPromptMetadataRedacted=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-shareAndPickerInput.properties" "localVisionRemoteRuntimeIdle=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-shareAndPickerInput.properties" "localVisionUnsupportedOcrSkipped=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-shareAndPickerInput.properties" "localVisionRuntimeImageAttachmentSendCount=1"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-shareAndPickerInput.properties" "localVisionRemoteRuntimeRequestCount=0"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-shareAndPickerInput.properties" "localVisionUnsupportedRuntimeImageSendCount=0"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-shareAndPickerInput.properties" "localVisionUnsupportedImageOcrInvocationCount=0"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-voiceInput.properties" "voiceEntryDisclosureVisible=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-voiceInput.properties" "voiceDraftNoAutoSendCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-voiceInput.properties" "voicePermissionFailureRecoveryCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-voiceInput.properties" "voiceCancelCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-privacyAndDataControls.properties" "privacyNoticeEntryVisible=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-privacyAndDataControls.properties" "memoryClearControlCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-privacyAndDataControls.properties" "sessionDeleteControlCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-privacyAndDataControls.properties" "remoteConfigClearCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-adaptiveUi.properties" "largeFontReachabilityCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-adaptiveUi.properties" "landscapeReachabilityCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-adaptiveUi.properties" "accessibleLabelsCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-mediaProjectionCancellation.properties" "mediaProjectionOneShotConsentCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-mediaProjectionCancellation.properties" "currentScreenshotOcrRemoteContinuationBlocked=true"
cat > "$FLOW_CANDIDATE_STALE_EMULATOR_REPORT" <<FLOW_CANDIDATE_STALE_EMULATOR_REPORT_PROPERTIES
status=passed
target=regression-emulator
clean_device=1
source_android_test_count=$LOW_ANDROID_TEST_COUNT
expected_android_test_count=$LOW_ANDROID_TEST_COUNT
actual_android_test_count=$LOW_ANDROID_TEST_COUNT
avd=test-avd
api_level=36
abi=arm64-v8a
FLOW_CANDIDATE_STALE_EMULATOR_REPORT_PROPERTIES
FLOW_CANDIDATE_STALE_EMULATOR_SHA="$(shasum -a 256 "$FLOW_CANDIDATE_STALE_EMULATOR_REPORT" | awk '{print $1}')"
python3 - "$FLOW_CANDIDATE_RECORD_PENDING" "$FLOW_CANDIDATE_RECORD_STALE_REPORT" "$FLOW_CANDIDATE_STALE_EMULATOR_REPORT" "$FLOW_CANDIDATE_STALE_EMULATOR_SHA" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
record["emulatorRegression"]["reportPath"] = sys.argv[3]
record["emulatorRegression"]["reportSha256"] = sys.argv[4]
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release flow matrix collector rejects stale emulator regression source count" \
  scripts/collect_release_flow_matrix_evidence.sh \
    --file "$FLOW_CANDIDATE_RECORD_STALE_REPORT" \
    --artifact-dir "$ARTIFACT_DIR/release-flow-candidate-stale-report" \
    --report "$ARTIFACT_DIR/release-flow-candidate-stale-report.properties"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-stale-report.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-stale-report.properties" "failedTarget=source-regression"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-stale-report.properties" "reason=emulator-regression-source-test-count-mismatch"
for flow_key in \
  firstInstall upgradeInstall localModelDownloadVerification customModelImportOrUrlRejection \
  remoteHttpsConfiguration encryptedApiKeyClear sessionPersistence memoryControls \
  privacyAndDataControls remindersAfterReboot shareAndPickerInput voiceInput adaptiveUi accessibilityText \
  recentMediaOcr mediaProjectionCancellation; do
  {
    printf 'status=passed\n'
    printf 'target=release-flow\n'
    printf 'flowKey=%s\n' "$flow_key"
    printf 'releaseFlowPassed=true\n'
    printf 'owner=QA\n'
    printf 'date=%s\n' "$VALIDATION_DATE"
    printf 'summary=%s release flow was explicitly approved.\n' "$flow_key"
    write_model_release_flow_contract_fixture "$flow_key"
  } > "$FLOW_CANDIDATE_EVIDENCE_DIR/$flow_key.properties"
done
python3 - "$FLOW_CANDIDATE_RECORD_APPROVED" "$FLOW_CANDIDATE_EMULATOR_REPORT" "$FLOW_CANDIDATE_EMULATOR_SHA" "$FLOW_CANDIDATE_EVIDENCE_DIR" "$VALIDATION_DATE" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

target = Path(sys.argv[1])
emulator_report = sys.argv[2]
emulator_sha = sys.argv[3]
evidence_dir = Path(sys.argv[4])
validation_date = sys.argv[5]
flows = [
    "firstInstall",
    "upgradeInstall",
    "localModelDownloadVerification",
    "customModelImportOrUrlRejection",
    "remoteHttpsConfiguration",
    "encryptedApiKeyClear",
    "sessionPersistence",
    "memoryControls",
    "privacyAndDataControls",
    "remindersAfterReboot",
    "shareAndPickerInput",
    "voiceInput",
    "adaptiveUi",
    "accessibilityText",
    "recentMediaOcr",
    "mediaProjectionCancellation",
]

def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()

record = {
    "version": 1,
    "status": "approved",
    "emulatorRegression": {
        "status": "passed",
        "reportPath": emulator_report,
        "reportSha256": emulator_sha,
    },
    "flowMatrix": {},
}
for flow in flows:
    evidence_path = evidence_dir / f"{flow}.properties"
    record["flowMatrix"][flow] = {
        "status": "passed",
        "evidence": f"{flow} approved flow evidence.",
        "evidencePath": str(evidence_path),
        "evidenceSha256": sha(evidence_path),
        "owner": "QA",
        "date": validation_date,
    }
target.write_text(json.dumps(record, indent=2))
PY
expect_success \
  "release flow matrix collector accepts approved structured flow records" \
  scripts/collect_release_flow_matrix_evidence.sh \
    --file "$FLOW_CANDIDATE_RECORD_APPROVED" \
    --artifact-dir "$ARTIFACT_DIR/release-flow-candidate-approved" \
    --report "$ARTIFACT_DIR/release-flow-candidate-approved.properties"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-approved.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-approved.properties" "passedRecordFlows=firstInstall,upgradeInstall,localModelDownloadVerification,customModelImportOrUrlRejection,remoteHttpsConfiguration,encryptedApiKeyClear,sessionPersistence,memoryControls,privacyAndDataControls,remindersAfterReboot,shareAndPickerInput,voiceInput,adaptiveUi,accessibilityText,recentMediaOcr,mediaProjectionCancellation"
FLOW_CANDIDATE_WEAK_EVIDENCE="$TMP_DIR/flow-candidate-weak-evidence.properties"
FLOW_CANDIDATE_RECORD_WEAK_EVIDENCE="$TMP_DIR/flow-candidate-record-weak-evidence.json"
printf 'status=passed\nflow=firstInstall\n' > "$FLOW_CANDIDATE_WEAK_EVIDENCE"
python3 - "$FLOW_CANDIDATE_RECORD_APPROVED" "$FLOW_CANDIDATE_RECORD_WEAK_EVIDENCE" "$FLOW_CANDIDATE_WEAK_EVIDENCE" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
evidence = Path(sys.argv[3])
record["flowMatrix"]["firstInstall"]["evidencePath"] = str(evidence)
record["flowMatrix"]["firstInstall"]["evidenceSha256"] = hashlib.sha256(evidence.read_bytes()).hexdigest()
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release flow matrix collector rejects weak structured flow evidence" \
  scripts/collect_release_flow_matrix_evidence.sh \
    --file "$FLOW_CANDIDATE_RECORD_WEAK_EVIDENCE" \
    --artifact-dir "$ARTIFACT_DIR/release-flow-candidate-weak-evidence" \
    --report "$ARTIFACT_DIR/release-flow-candidate-weak-evidence.properties"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-weak-evidence.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-weak-evidence.properties" "failedTarget=flow-matrix"
assert_report_contains_text "$ARTIFACT_DIR/release-flow-candidate-weak-evidence.properties" "pendingRecordFlows=firstInstall"
python3 - "$FLOW_CANDIDATE_RECORD_APPROVED" "$FLOW_CANDIDATE_RECORD_BAD_SHA" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
record["emulatorRegression"]["reportSha256"] = "0" * 64
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release flow matrix collector rejects mismatched source regression sha" \
  scripts/collect_release_flow_matrix_evidence.sh \
    --file "$FLOW_CANDIDATE_RECORD_BAD_SHA" \
    --artifact-dir "$ARTIFACT_DIR/release-flow-candidate-bad-sha" \
    --report "$ARTIFACT_DIR/release-flow-candidate-bad-sha.properties"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-bad-sha.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-bad-sha.properties" "failedTarget=source-regression"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-bad-sha.properties" "reason=emulator-regression-report-sha-mismatch"

expect_failure \
  "manual acceptance evidence recorder requires owner" \
  env ARTIFACT_DIR="$ARTIFACT_DIR/manual-acceptance-missing-owner" \
  MANUAL_ACCEPTANCE_ALL=1 scripts/record_manual_acceptance_evidence.sh
assert_report_contains "$ARTIFACT_DIR/manual-acceptance-missing-owner/manual-acceptance-evidence.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/manual-acceptance-missing-owner/manual-acceptance-evidence.properties" "reason=missing-owner"
expect_failure \
  "manual acceptance evidence recorder reports pending keys" \
  env ARTIFACT_DIR="$ARTIFACT_DIR/manual-acceptance-partial" \
  OWNER="QA" MANUAL_ACCEPTANCE_KEYS="modelSetup,toolConfirmation" \
  scripts/record_manual_acceptance_evidence.sh
assert_report_contains "$ARTIFACT_DIR/manual-acceptance-partial/manual-acceptance-evidence.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/manual-acceptance-partial/manual-acceptance-evidence.properties" "reason=missing-required-manual-keys"
assert_report_contains "$ARTIFACT_DIR/manual-acceptance-partial/manual-acceptance-evidence.properties" "acceptedManualKeys=modelSetup,toolConfirmation"
assert_report_contains_text "$ARTIFACT_DIR/manual-acceptance-partial/manual-acceptance-evidence.properties" "pendingManualKeys="
assert_report_contains "$ARTIFACT_DIR/manual-acceptance-partial/manual-modelSetup.properties" "target=manual-acceptance"
assert_report_contains "$ARTIFACT_DIR/manual-acceptance-partial/manual-modelSetup.properties" "manualKey=modelSetup"
assert_report_contains "$ARTIFACT_DIR/manual-acceptance-partial/manual-modelSetup.properties" "manualAcceptance=true"
expect_success \
  "manual acceptance evidence recorder writes all formal evidence" \
  env ARTIFACT_DIR="$ARTIFACT_DIR/manual-acceptance-full" \
  OWNER="QA" MANUAL_ACCEPTANCE_ALL=1 VALIDATION_DATE="$VALIDATION_DATE" \
  scripts/record_manual_acceptance_evidence.sh
assert_release_verifier_report_schema \
  "$ARTIFACT_DIR/manual-acceptance-full/manual-acceptance-evidence.properties" \
  "ManualAcceptanceEvidenceCollection/v1" \
  "QA"
assert_report_contains "$ARTIFACT_DIR/manual-acceptance-full/manual-acceptance-evidence.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/manual-acceptance-full/manual-acceptance-evidence.properties" "target=manual-acceptance-evidence"
assert_report_contains "$ARTIFACT_DIR/manual-acceptance-full/manual-acceptance-evidence.properties" "pendingManualKeys="
for manual_key in \
  modelSetup remoteModePrivacy toolConfirmation permissions backgroundReminders sharing \
  multimodalEntryPoints voiceInput filePicker mediaProjection remoteSinglePublicEvidence \
  remoteMultiEvidenceComparison mixedPrivateActionBatchFailClosed; do
  assert_report_contains "$ARTIFACT_DIR/manual-acceptance-full/manual-$manual_key.properties" "status=passed"
  assert_report_contains "$ARTIFACT_DIR/manual-acceptance-full/manual-$manual_key.properties" "artifactSchema=ManualAcceptanceEvidence/v1"
  assert_report_contains "$ARTIFACT_DIR/manual-acceptance-full/manual-$manual_key.properties" "target=manual-acceptance"
  assert_report_contains "$ARTIFACT_DIR/manual-acceptance-full/manual-$manual_key.properties" "manualKey=$manual_key"
  assert_report_contains "$ARTIFACT_DIR/manual-acceptance-full/manual-$manual_key.properties" "manualAcceptance=true"
  assert_report_contains "$ARTIFACT_DIR/manual-acceptance-full/manual-$manual_key.properties" "owner=QA"
  assert_report_contains "$ARTIFACT_DIR/manual-acceptance-full/manual-$manual_key.properties" "date=$VALIDATION_DATE"
  assert_report_contains "$ARTIFACT_DIR/manual-acceptance-full/manual-$manual_key.properties" "command=scripts/record_manual_acceptance_evidence.sh"
  assert_report_contains "$ARTIFACT_DIR/manual-acceptance-full/manual-$manual_key.properties" "reproduciblePath=$ARTIFACT_DIR/manual-acceptance-full/manual-$manual_key.properties"
  grep -Eq '^recordedAt=20[0-9]{2}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$' \
    "$ARTIFACT_DIR/manual-acceptance-full/manual-$manual_key.properties" ||
    fail "Expected UTC recordedAt in manual acceptance evidence for $manual_key"
done

expect_failure \
  "release flow evidence recorder requires owner" \
  env ARTIFACT_DIR="$ARTIFACT_DIR/release-flow-missing-owner" \
  RELEASE_FLOW_ALL=1 scripts/record_release_flow_evidence.sh
assert_report_contains "$ARTIFACT_DIR/release-flow-missing-owner/release-flow-evidence.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/release-flow-missing-owner/release-flow-evidence.properties" "target=release-flow-evidence"
assert_report_contains "$ARTIFACT_DIR/release-flow-missing-owner/release-flow-evidence.properties" "reason=missing-owner"
expect_failure \
  "release flow evidence recorder reports pending flows" \
  env ARTIFACT_DIR="$ARTIFACT_DIR/release-flow-partial" \
  OWNER="QA" RELEASE_FLOW_KEYS="firstInstall,sessionPersistence" \
  scripts/record_release_flow_evidence.sh
assert_report_contains "$ARTIFACT_DIR/release-flow-partial/release-flow-evidence.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/release-flow-partial/release-flow-evidence.properties" "target=release-flow-evidence"
assert_report_contains "$ARTIFACT_DIR/release-flow-partial/release-flow-evidence.properties" "reason=missing-required-release-flows"
assert_report_contains "$ARTIFACT_DIR/release-flow-partial/release-flow-evidence.properties" "acceptedFlows=firstInstall,sessionPersistence"
assert_report_contains_text "$ARTIFACT_DIR/release-flow-partial/release-flow-evidence.properties" "pendingFlows="
assert_report_contains "$ARTIFACT_DIR/release-flow-partial/flow-firstInstall.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/release-flow-partial/flow-firstInstall.properties" "target=release-flow"
assert_report_contains "$ARTIFACT_DIR/release-flow-partial/flow-firstInstall.properties" "flowKey=firstInstall"
assert_report_contains "$ARTIFACT_DIR/release-flow-partial/flow-firstInstall.properties" "releaseFlowPassed=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-partial/flow-firstInstall.properties" "candidateOnly=false"
assert_report_contains "$ARTIFACT_DIR/release-flow-partial/flow-firstInstall.properties" "firstRunSetupVisibleCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-partial/flow-firstInstall.properties" "firstRunDefaultChatModelSelected=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-partial/flow-firstInstall.properties" "firstRunSkipReachesMainShell=true"
expect_success \
  "release flow evidence recorder writes all formal evidence" \
  env ARTIFACT_DIR="$ARTIFACT_DIR/release-flow-full" \
  OWNER="QA" RELEASE_FLOW_ALL=1 VALIDATION_DATE="$VALIDATION_DATE" \
  scripts/record_release_flow_evidence.sh
assert_release_verifier_report_schema \
  "$ARTIFACT_DIR/release-flow-full/release-flow-evidence.properties" \
  "ReleaseFlowEvidenceCollection/v1" \
  "QA"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/release-flow-evidence.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/release-flow-evidence.properties" "target=release-flow-evidence"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/release-flow-evidence.properties" "pendingFlows="
for flow_key in \
  firstInstall upgradeInstall localModelDownloadVerification customModelImportOrUrlRejection \
  remoteHttpsConfiguration encryptedApiKeyClear sessionPersistence memoryControls \
  privacyAndDataControls remindersAfterReboot shareAndPickerInput voiceInput adaptiveUi accessibilityText \
  recentMediaOcr mediaProjectionCancellation; do
  assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-$flow_key.properties" "status=passed"
  assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-$flow_key.properties" "artifactSchema=ReleaseFlowEvidence/v1"
  assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-$flow_key.properties" "target=release-flow"
  assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-$flow_key.properties" "flowKey=$flow_key"
  assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-$flow_key.properties" "releaseFlowPassed=true"
  assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-$flow_key.properties" "candidateOnly=false"
  assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-$flow_key.properties" "evidenceKind=formal-release-flow"
  assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-$flow_key.properties" "owner=QA"
  assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-$flow_key.properties" "date=$VALIDATION_DATE"
  assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-$flow_key.properties" "command=scripts/record_release_flow_evidence.sh"
  assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-$flow_key.properties" "reproduciblePath=$ARTIFACT_DIR/release-flow-full/flow-$flow_key.properties"
  grep -Eq '^recordedAt=20[0-9]{2}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$' \
    "$ARTIFACT_DIR/release-flow-full/flow-$flow_key.properties" ||
    fail "Expected UTC recordedAt in release flow evidence for $flow_key"
done
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-localModelDownloadVerification.properties" "localModelDownloadVerified=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-localModelDownloadVerification.properties" "modelSha256VerificationCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-localModelDownloadVerification.properties" "downloadFailureRecoveryCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-localModelDownloadVerification.properties" "downloadDirectoryUnavailableCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-localModelDownloadVerification.properties" "downloadShaFailureCleanupCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-localModelDownloadVerification.properties" "downloadInsufficientStorageFailureCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-localModelDownloadVerification.properties" "pendingDownloadMissingTaskRecoveryCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-localModelDownloadVerification.properties" "remoteFallbackExplained=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-upgradeInstall.properties" "upgradeInstallUsesAdbInstallR=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-upgradeInstall.properties" "upgradeInstallPreservesFirstInstallTime=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-upgradeInstall.properties" "upgradeInstallUpdatesLastUpdateTime=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-upgradeInstall.properties" "upgradeInstallVersionCodeIncreased=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-upgradeInstall.properties" "upgradeInstallInstrumentationCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-customModelImportOrUrlRejection.properties" "customLitertlmImportCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-customModelImportOrUrlRejection.properties" "customLocalNonLitertlmImportRejected=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-customModelImportOrUrlRejection.properties" "customImportStoragePreflightCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-customModelImportOrUrlRejection.properties" "customImportEmptyFileRejected=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-customModelImportOrUrlRejection.properties" "customImportTempCleanupOnCopyFailureCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-customModelImportOrUrlRejection.properties" "customNonLitertlmDownloadRejected=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-customModelImportOrUrlRejection.properties" "customCredentialedUrlRejected=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-customModelImportOrUrlRejection.properties" "customUnverifiedModelMarked=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-remoteHttpsConfiguration.properties" "remoteNetworkFailureRecoveryCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-remoteHttpsConfiguration.properties" "remoteUnconfiguredModelFailureCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-remoteHttpsConfiguration.properties" "remoteLocalMemoryNotAutoIncluded=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-encryptedApiKeyClear.properties" "encryptedApiKeyBlankInputClearsSecret=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-encryptedApiKeyClear.properties" "legacyPlaintextApiKeyNotPersisted=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-sessionPersistence.properties" "sessionCreateSwitchRestoreCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-sessionPersistence.properties" "activeSessionPersistenceCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-sessionPersistence.properties" "sessionDeleteCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-memoryControls.properties" "memoryCreateControlCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-memoryControls.properties" "memoryForgetControlCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-memoryControls.properties" "memoryClearControlCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-memoryControls.properties" "memoryPanelControlCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-remindersAfterReboot.properties" "bootCompletedReminderRescheduleCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-remindersAfterReboot.properties" "packageReplacedReminderRescheduleCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-remindersAfterReboot.properties" "reminderAuditMetadataOnly=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-shareAndPickerInput.properties" "actionSendTextStaged=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-shareAndPickerInput.properties" "remoteTextShareProtected=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-shareAndPickerInput.properties" "remoteVisionImageAttachmentStaged=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-shareAndPickerInput.properties" "remoteVisionUnsupportedProtected=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-shareAndPickerInput.properties" "remoteNonImageAttachmentNotAutoIncluded=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-shareAndPickerInput.properties" "remoteVisionSupportedOpenStreamCountCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-shareAndPickerInput.properties" "remoteVisionSupportedOcrSkipped=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-shareAndPickerInput.properties" "remoteVisionUnsupportedOpenStreamCountCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-shareAndPickerInput.properties" "remoteVisionUnsupportedOcrSkipped=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-shareAndPickerInput.properties" "remoteVisionMixedShareNonImageProtected=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-shareAndPickerInput.properties" "remoteVisionSendPreviewConfirmed=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-shareAndPickerInput.properties" "remoteVisionCancelKeepsRuntimeIdle=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-shareAndPickerInput.properties" "remoteVisionHttpFixtureImagePartCount=1"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-shareAndPickerInput.properties" "remoteVisionHttpFixtureStreamRequested=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-shareAndPickerInput.properties" "remoteVisionSupportedImageStreamOpenCount=1"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-shareAndPickerInput.properties" "remoteVisionSupportedImageOcrInvocationCount=0"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-shareAndPickerInput.properties" "remoteVisionUnsupportedImageStreamOpenCount=0"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-shareAndPickerInput.properties" "remoteVisionUnsupportedImageOcrInvocationCount=0"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-shareAndPickerInput.properties" "remoteVisionMixedProtectedNonImageCount=1"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-shareAndPickerInput.properties" "localVisionVerifiedModelImageAttachmentStaged=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-shareAndPickerInput.properties" "localVisionRuntimeImageAttachmentSent=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-shareAndPickerInput.properties" "localVisionLocalOnlyPersistenceCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-shareAndPickerInput.properties" "localVisionPromptMetadataRedacted=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-shareAndPickerInput.properties" "localVisionRemoteRuntimeIdle=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-shareAndPickerInput.properties" "localVisionUnsupportedOcrSkipped=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-shareAndPickerInput.properties" "localVisionRuntimeImageAttachmentSendCount=1"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-shareAndPickerInput.properties" "localVisionRemoteRuntimeRequestCount=0"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-shareAndPickerInput.properties" "localVisionUnsupportedRuntimeImageSendCount=0"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-shareAndPickerInput.properties" "localVisionUnsupportedImageOcrInvocationCount=0"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-shareAndPickerInput.properties" "documentExcerptBounded=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-mediaProjectionCancellation.properties" "mediaProjectionOneShotConsentCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-mediaProjectionCancellation.properties" "currentScreenshotOcrRemoteContinuationBlocked=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-voiceInput.properties" "voiceEntryDisclosureVisible=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-voiceInput.properties" "voiceDraftNoAutoSendCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-voiceInput.properties" "voicePermissionFailureRecoveryCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-voiceInput.properties" "voiceCancelCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-privacyAndDataControls.properties" "privacyNoticeEntryVisible=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-privacyAndDataControls.properties" "memoryForgetControlCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-privacyAndDataControls.properties" "sessionDeleteControlCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-privacyAndDataControls.properties" "dataDeletionCopyCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-adaptiveUi.properties" "largeFontReachabilityCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-adaptiveUi.properties" "landscapeReachabilityCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-adaptiveUi.properties" "accessibleLabelsCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-accessibilityText.properties" "accessibilityTextConfirmationCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-accessibilityText.properties" "accessibilityTextLocalOnlyMetadataCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-accessibilityText.properties" "accessibilityTextTraceRecorded=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-recentMediaOcr.properties" "recentScreenshotOcrRoutingCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-recentMediaOcr.properties" "recentImageOcrRoutingCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-recentMediaOcr.properties" "recentMediaOcrRemoteLeakageBlocked=true"

FAKE_SDKMANAGER="$TMP_DIR/fake-sdkmanager"
FAKE_AVDMANAGER="$TMP_DIR/fake-avdmanager"
FAKE_MATRIX_REGRESSION="$TMP_DIR/fake-matrix-regression"
FAKE_AVD_ROOT="$TMP_DIR/fake-avd-root"
FAKE_SDKMANAGER_LOG="$TMP_DIR/fake-sdkmanager.log"
FAKE_AVDMANAGER_LOG="$TMP_DIR/fake-avdmanager.log"
create_fake_sdkmanager "$FAKE_SDKMANAGER"
create_fake_avdmanager "$FAKE_AVDMANAGER"
create_fake_matrix_regression "$FAKE_MATRIX_REGRESSION"
mkdir -p "$FAKE_AVD_ROOT/api28.avd" "$FAKE_AVD_ROOT/api36.avd"
cat > "$FAKE_AVD_ROOT/api28.avd/config.ini" <<'API28_AVD_CONFIG'
abi.type=arm64-v8a
image.sysdir.1=system-images/android-28/google_apis/arm64-v8a/
tag.id=google_apis
target=android-28
API28_AVD_CONFIG
cat > "$FAKE_AVD_ROOT/api36.avd/config.ini" <<'API36_AVD_CONFIG'
abi.type=arm64-v8a
image.sysdir.1=system-images/android-36/google_apis/arm64-v8a/
tag.id=google_apis
target=android-36
API36_AVD_CONFIG
FAKE_SDKMANAGER_INSTALLED=$'system-images;android-28;google_apis;arm64-v8a | 1 | Fake\nsystem-images;android-36;google_apis;arm64-v8a | 1 | Fake'
expect_success \
  "emulator api matrix readiness accepts installed images and avds" \
  env ANDROID_HOME="$FAKE_SDK" \
  FAKE_SDKMANAGER_INSTALLED="$FAKE_SDKMANAGER_INSTALLED" \
  scripts/check_emulator_api_matrix.sh \
    --sdkmanager "$FAKE_SDKMANAGER" \
    --avd-root "$FAKE_AVD_ROOT" \
    --required-apis "28 36" \
    --report "$ARTIFACT_DIR/emulator-api-matrix-readiness.properties"
assert_report_contains "$ARTIFACT_DIR/emulator-api-matrix-readiness.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/emulator-api-matrix-readiness.properties" "target=emulator-api-matrix-readiness"
assert_report_contains "$ARTIFACT_DIR/emulator-api-matrix-readiness.properties" "installedSystemImageApis=28,36"
assert_report_contains "$ARTIFACT_DIR/emulator-api-matrix-readiness.properties" "availableAvdApis=28,36"
rm -rf "$FAKE_AVD_ROOT/api32.avd"
FAKE_SDKMANAGER_INSTALLED_PREPARE=$'system-images;android-36;google_apis;arm64-v8a | 1 | Fake\nplatforms;android-36 | 1 | Fake'
: > "$FAKE_SDKMANAGER_LOG"
: > "$FAKE_AVDMANAGER_LOG"
expect_success \
  "emulator api matrix prepare dry-run reports missing work without applying" \
  env ANDROID_HOME="$FAKE_SDK" \
  FAKE_SDKMANAGER_LOG="$FAKE_SDKMANAGER_LOG" \
  FAKE_AVDMANAGER_LOG="$FAKE_AVDMANAGER_LOG" \
  FAKE_SDKMANAGER_INSTALLED="$FAKE_SDKMANAGER_INSTALLED_PREPARE" \
  scripts/prepare_emulator_api_matrix.sh \
    --sdkmanager "$FAKE_SDKMANAGER" \
    --avdmanager "$FAKE_AVDMANAGER" \
    --avd-root "$FAKE_AVD_ROOT" \
    --required-apis "32 36" \
    --report "$ARTIFACT_DIR/emulator-api-matrix-prepare-dry-run.properties"
assert_report_contains "$ARTIFACT_DIR/emulator-api-matrix-prepare-dry-run.properties" "status=pending"
assert_report_contains "$ARTIFACT_DIR/emulator-api-matrix-prepare-dry-run.properties" "reason=apply-required"
assert_report_contains "$ARTIFACT_DIR/emulator-api-matrix-prepare-dry-run.properties" "missingSystemImageApis=32"
assert_report_contains "$ARTIFACT_DIR/emulator-api-matrix-prepare-dry-run.properties" "missingAvdApis=32"
assert_report_contains_text "$ARTIFACT_DIR/emulator-api-matrix-prepare-dry-run.properties" "platforms;android-32"
assert_report_contains_text "$ARTIFACT_DIR/emulator-api-matrix-prepare-dry-run.properties" "system-images;android-32;google_apis;arm64-v8a"
if grep -q 'system-images;android-32;google_apis;arm64-v8a' "$FAKE_SDKMANAGER_LOG"; then
  fail "prepare dry-run must not install missing SDK packages"
fi
if [[ -s "$FAKE_AVDMANAGER_LOG" ]]; then
  fail "prepare dry-run must not create AVDs"
fi
: > "$FAKE_SDKMANAGER_LOG"
: > "$FAKE_AVDMANAGER_LOG"
expect_success \
  "emulator api matrix prepare applies missing packages and avds" \
  env ANDROID_HOME="$FAKE_SDK" \
  FAKE_SDKMANAGER_LOG="$FAKE_SDKMANAGER_LOG" \
  FAKE_AVDMANAGER_LOG="$FAKE_AVDMANAGER_LOG" \
  FAKE_SDKMANAGER_INSTALLED="$FAKE_SDKMANAGER_INSTALLED_PREPARE" \
  APPLY=1 scripts/prepare_emulator_api_matrix.sh \
    --sdkmanager "$FAKE_SDKMANAGER" \
    --avdmanager "$FAKE_AVDMANAGER" \
    --avd-root "$FAKE_AVD_ROOT" \
    --required-apis "32 36" \
    --report "$ARTIFACT_DIR/emulator-api-matrix-prepare-apply.properties"
assert_report_contains "$ARTIFACT_DIR/emulator-api-matrix-prepare-apply.properties" "status=passed"
grep -q -- '--sdk_root=.*platforms;android-32.*system-images;android-32;google_apis;arm64-v8a' "$FAKE_SDKMANAGER_LOG" ||
  fail "prepare apply must install missing API 32 platform and system image"
grep -q -- 'create avd --force --name pocketmind_api32_arm64_v8a --package system-images;android-32;google_apis;arm64-v8a' "$FAKE_AVDMANAGER_LOG" ||
  fail "prepare apply must create the missing API 32 arm64 AVD"
rm -rf "$FAKE_AVD_ROOT/api28.avd"
FAKE_SDKMANAGER_INSTALLED=$'system-images;android-36;google_apis;arm64-v8a | 1 | Fake'
expect_failure \
  "emulator api matrix readiness reports missing image and avd" \
  env ANDROID_HOME="$FAKE_SDK" \
  FAKE_SDKMANAGER_INSTALLED="$FAKE_SDKMANAGER_INSTALLED" \
  scripts/check_emulator_api_matrix.sh \
    --sdkmanager "$FAKE_SDKMANAGER" \
    --avd-root "$FAKE_AVD_ROOT" \
    --required-apis "28 36" \
    --report "$ARTIFACT_DIR/emulator-api-matrix-readiness-missing.properties"
assert_report_contains "$ARTIFACT_DIR/emulator-api-matrix-readiness-missing.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/emulator-api-matrix-readiness-missing.properties" "failedTarget=api-matrix-readiness"
assert_report_contains "$ARTIFACT_DIR/emulator-api-matrix-readiness-missing.properties" "reason=missing-system-image-api-28,missing-avd-api-28"
assert_report_contains "$ARTIFACT_DIR/emulator-api-matrix-readiness-missing.properties" "missingSystemImageApis=28"
assert_report_contains "$ARTIFACT_DIR/emulator-api-matrix-readiness-missing.properties" "missingAvdApis=28"
mkdir -p "$FAKE_AVD_ROOT/api28.avd"
cat > "$FAKE_AVD_ROOT/api28.avd/config.ini" <<'API28_AVD_CONFIG'
abi.type=arm64-v8a
image.sysdir.1=system-images/android-28/google_apis/arm64-v8a/
tag.id=google_apis
target=android-28
API28_AVD_CONFIG
FAKE_SDKMANAGER_INSTALLED=$'system-images;android-28;google_apis;arm64-v8a | 1 | Fake\nsystem-images;android-36;google_apis;arm64-v8a | 1 | Fake'
MATRIX_ARTIFACT_DIR="$ARTIFACT_DIR/emulator-api-matrix"
MATRIX_REPORT="$ARTIFACT_DIR/regression-emulator-api-matrix.properties"
expect_success \
  "emulator api matrix regression accepts all api reports" \
  env ANDROID_HOME="$FAKE_SDK" SDKMANAGER_CMD="$FAKE_SDKMANAGER" \
  FAKE_SDKMANAGER_INSTALLED="$FAKE_SDKMANAGER_INSTALLED" \
  scripts/regression_emulator_api_matrix.sh \
    --avd-root "$FAKE_AVD_ROOT" \
    --required-apis "28 36" \
    --check-script scripts/check_emulator_api_matrix.sh \
    --regression-script "$FAKE_MATRIX_REGRESSION" \
    --keep-emulators \
    --artifact-dir "$MATRIX_ARTIFACT_DIR" \
    --report "$MATRIX_REPORT"
assert_report_contains "$MATRIX_REPORT" "status=passed"
assert_report_contains "$MATRIX_REPORT" "target=regression-emulator-api-matrix"
assert_report_contains "$MATRIX_REPORT" "artifactDir=$MATRIX_ARTIFACT_DIR"
assert_report_contains "$MATRIX_REPORT" "passedApis=28,36"
assert_report_contains "$MATRIX_REPORT" "skippedApis="
assert_report_contains "$MATRIX_REPORT" "api28Status=passed"
assert_report_contains "$MATRIX_REPORT" "api36Status=passed"
MATRIX_SKIPPED_ARTIFACT_DIR="$ARTIFACT_DIR/emulator-api-matrix-skipped"
MATRIX_SKIPPED_REPORT="$ARTIFACT_DIR/regression-emulator-api-matrix-skipped.properties"
expect_success \
  "emulator api matrix regression records allowed infra skips without passing them" \
  env ANDROID_HOME="$FAKE_SDK" SDKMANAGER_CMD="$FAKE_SDKMANAGER" \
  FAKE_SDKMANAGER_INSTALLED="$FAKE_SDKMANAGER_INSTALLED" \
  FAKE_MATRIX_SKIP_API=28 ALLOW_EMULATOR_INFRA_UNAVAILABLE=1 \
  scripts/regression_emulator_api_matrix.sh \
    --avd-root "$FAKE_AVD_ROOT" \
    --required-apis "28 36" \
    --check-script scripts/check_emulator_api_matrix.sh \
    --regression-script "$FAKE_MATRIX_REGRESSION" \
    --keep-emulators \
    --artifact-dir "$MATRIX_SKIPPED_ARTIFACT_DIR" \
    --report "$MATRIX_SKIPPED_REPORT"
assert_report_contains "$MATRIX_SKIPPED_REPORT" "status=skipped"
assert_report_contains "$MATRIX_SKIPPED_REPORT" "failedTarget=emulator-infra"
assert_report_contains "$MATRIX_SKIPPED_REPORT" "reason=emulator-infra-hvf-unsupported"
assert_report_contains "$MATRIX_SKIPPED_REPORT" "passedApis=36"
assert_report_contains "$MATRIX_SKIPPED_REPORT" "skippedApis=28"
assert_report_contains "$MATRIX_SKIPPED_REPORT" "api28Status=skipped"
assert_report_contains "$MATRIX_SKIPPED_REPORT" "api36Status=passed"
MATRIX_EXPLICIT_ENV_REPORT="$ARTIFACT_DIR/regression-emulator-api-matrix-explicit-env.properties"
MATRIX_EXPLICIT_ENV_ARTIFACT_DIR="$ARTIFACT_DIR/emulator-api-matrix-explicit-env"
expect_success \
  "emulator api matrix artifact-dir keeps explicit env report" \
  env ANDROID_HOME="$FAKE_SDK" SDKMANAGER_CMD="$FAKE_SDKMANAGER" \
  FAKE_SDKMANAGER_INSTALLED="$FAKE_SDKMANAGER_INSTALLED" \
  REPORT_FILE="$MATRIX_EXPLICIT_ENV_REPORT" \
  scripts/regression_emulator_api_matrix.sh \
    --avd-root "$FAKE_AVD_ROOT" \
    --required-apis "28 36" \
    --check-script scripts/check_emulator_api_matrix.sh \
    --regression-script "$FAKE_MATRIX_REGRESSION" \
    --keep-emulators \
    --artifact-dir "$MATRIX_EXPLICIT_ENV_ARTIFACT_DIR"
assert_report_contains "$MATRIX_EXPLICIT_ENV_REPORT" "status=passed"
assert_report_contains "$MATRIX_EXPLICIT_ENV_REPORT" "artifactDir=$MATRIX_EXPLICIT_ENV_ARTIFACT_DIR"
expect_failure \
  "emulator api matrix regression reports api failure" \
  env ANDROID_HOME="$FAKE_SDK" SDKMANAGER_CMD="$FAKE_SDKMANAGER" \
  FAKE_SDKMANAGER_INSTALLED="$FAKE_SDKMANAGER_INSTALLED" \
  FAKE_MATRIX_FAIL_API=28 \
  scripts/regression_emulator_api_matrix.sh \
    --avd-root "$FAKE_AVD_ROOT" \
    --required-apis "28 36" \
    --check-script scripts/check_emulator_api_matrix.sh \
    --regression-script "$FAKE_MATRIX_REGRESSION" \
    --keep-emulators \
    --artifact-dir "$ARTIFACT_DIR/emulator-api-matrix-failed" \
    --report "$ARTIFACT_DIR/regression-emulator-api-matrix-failed.properties"
assert_report_contains "$ARTIFACT_DIR/regression-emulator-api-matrix-failed.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/regression-emulator-api-matrix-failed.properties" "failedTarget=api-28-regression"
assert_report_contains "$ARTIFACT_DIR/regression-emulator-api-matrix-failed.properties" "reason=api-28-regression-regression-failed"
assert_report_contains "$ARTIFACT_DIR/regression-emulator-api-matrix-failed.properties" "failedApis=28"
assert_report_contains "$ARTIFACT_DIR/regression-emulator-api-matrix-failed.properties" "api28Status=failed"

SAFE_PRIVACY_DIR="$TMP_DIR/privacy-safe"
mkdir -p "$SAFE_PRIVACY_DIR"
SAFE_PRIVACY_FILE="$SAFE_PRIVACY_DIR/readme.txt"
printf 'hello pocketmind\n' > "$SAFE_PRIVACY_FILE"
SAFE_PRIVACY_FILE_SHA="$(shasum -a 256 "$SAFE_PRIVACY_FILE" | awk '{print $1}')"
expect_success \
  "privacy scan accepts safe directory" \
  scripts/privacy_scan.sh --report "$ARTIFACT_DIR/privacy.properties" "$SAFE_PRIVACY_DIR"
assert_report_contains "$ARTIFACT_DIR/privacy.properties" "status=passed"
assert_release_verifier_report_schema "$ARTIFACT_DIR/privacy.properties" "PrivacyScanReport/v1" "privacy-security"
assert_report_contains "$ARTIFACT_DIR/privacy.properties" "scanTarget1Path=$SAFE_PRIVACY_DIR"
expect_success \
  "privacy scan binds file target sha" \
  scripts/privacy_scan.sh --report "$ARTIFACT_DIR/privacy-file.properties" "$SAFE_PRIVACY_FILE"
assert_release_verifier_report_schema "$ARTIFACT_DIR/privacy-file.properties" "PrivacyScanReport/v1" "privacy-security"
assert_report_contains "$ARTIFACT_DIR/privacy-file.properties" "scanTarget1Path=$SAFE_PRIVACY_FILE"
assert_report_contains "$ARTIFACT_DIR/privacy-file.properties" "scanTarget1Sha256=$SAFE_PRIVACY_FILE_SHA"
SPACED_PRIVACY_DIR="$TMP_DIR/privacy spaced command"
SPACED_PRIVACY_FILE="$SPACED_PRIVACY_DIR/safe target.txt"
SPACED_PRIVACY_REPORT="$ARTIFACT_DIR/privacy spaced command.properties"
mkdir -p "$SPACED_PRIVACY_DIR"
printf 'safe\n' > "$SPACED_PRIVACY_FILE"
expect_success \
  "privacy scan report command preserves spaced argv" \
  scripts/privacy_scan.sh --report "$SPACED_PRIVACY_REPORT" "$SPACED_PRIVACY_FILE"
assert_report_command_argv \
  "$SPACED_PRIVACY_REPORT" \
  scripts/privacy_scan.sh \
  --report "$SPACED_PRIVACY_REPORT" \
  "$SPACED_PRIVACY_FILE"
PRIVACY_MISSING_VALUE_REPORT="$ARTIFACT_DIR/privacy-missing-report-value.properties"
expect_failure \
  "privacy scan reports malformed arguments when report path is known" \
  scripts/privacy_scan.sh --report "$PRIVACY_MISSING_VALUE_REPORT" --report
assert_release_verifier_report_schema "$PRIVACY_MISSING_VALUE_REPORT" "PrivacyScanReport/v1" "privacy-security"
assert_report_contains "$PRIVACY_MISSING_VALUE_REPORT" "status=failed"
assert_report_contains "$PRIVACY_MISSING_VALUE_REPORT" "reason=missing-report-argument"
assert_report_contains "$PRIVACY_MISSING_VALUE_REPORT" "failedTarget=argument-parser"
PRIVACY_UNKNOWN_OPTION_REPORT="$ARTIFACT_DIR/privacy-unknown-option.properties"
expect_failure \
  "privacy scan rejects option-like unknown arguments instead of scanning them" \
  scripts/privacy_scan.sh --report "$PRIVACY_UNKNOWN_OPTION_REPORT" --bad-arg
assert_release_verifier_report_schema "$PRIVACY_UNKNOWN_OPTION_REPORT" "PrivacyScanReport/v1" "privacy-security"
assert_report_contains "$PRIVACY_UNKNOWN_OPTION_REPORT" "status=failed"
assert_report_contains "$PRIVACY_UNKNOWN_OPTION_REPORT" "reason=unknown-argument"
assert_report_contains "$PRIVACY_UNKNOWN_OPTION_REPORT" "failedTarget=argument-parser"

UNSAFE_PRIVACY_DIR="$TMP_DIR/privacy-unsafe"
mkdir -p "$UNSAFE_PRIVACY_DIR"
PRIVACY_SCAN_SECRET_BODY="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
PRIVACY_SCAN_SECRET="sk-${PRIVACY_SCAN_SECRET_BODY}"
printf 'token=%s\n' "$PRIVACY_SCAN_SECRET" > "$UNSAFE_PRIVACY_DIR/secret.txt"
expect_failure \
  "privacy scan rejects high-confidence token" \
  scripts/privacy_scan.sh --report "$ARTIFACT_DIR/privacy-failed.properties" "$UNSAFE_PRIVACY_DIR"
assert_report_contains "$ARTIFACT_DIR/privacy-failed.properties" "status=failed"
assert_release_verifier_report_schema "$ARTIFACT_DIR/privacy-failed.properties" "PrivacyScanReport/v1" "privacy-security"
assert_report_contains "$ARTIFACT_DIR/privacy-failed.properties" "scanTarget1Path=$UNSAFE_PRIVACY_DIR"
assert_report_contains "$ARTIFACT_DIR/privacy-failed.properties" "reason=secret-pattern-detected"
if grep -q "$PRIVACY_SCAN_SECRET" <<<"$LAST_OUTPUT"; then
  fail "privacy scan stderr must redact raw secret values"
fi
if grep -q "$PRIVACY_SCAN_SECRET" "$ARTIFACT_DIR/privacy-failed.properties"; then
  fail "privacy scan report must not contain raw secret values"
fi

PRIVACY_NOTICE="$TMP_DIR/privacy-notice.md"
PRIVACY_REVIEW_PENDING="$TMP_DIR/privacy-review-pending.json"
PRIVACY_REVIEW_APPROVED="$TMP_DIR/privacy-review-approved.json"
PRIVACY_REVIEW_RELEASE_EVIDENCE="$TMP_DIR/privacy-review-release.properties"
PRIVACY_REVIEW_SECURITY_EVIDENCE="$TMP_DIR/privacy-review-security.properties"
PRIVACY_REVIEW_LEGAL_EVIDENCE="$TMP_DIR/privacy-review-legal.properties"
printf 'PocketMind privacy notice\n' > "$PRIVACY_NOTICE"
PRIVACY_NOTICE_SHA="$(shasum -a 256 "$PRIVACY_NOTICE" | awk '{print $1}')"
for privacy_review_role in release security legal; do
  case "$privacy_review_role" in
    release) privacy_review_evidence="$PRIVACY_REVIEW_RELEASE_EVIDENCE" ;;
    security) privacy_review_evidence="$PRIVACY_REVIEW_SECURITY_EVIDENCE" ;;
    legal) privacy_review_evidence="$PRIVACY_REVIEW_LEGAL_EVIDENCE" ;;
  esac
  cat > "$privacy_review_evidence" <<PRIVACY_REVIEW_EVIDENCE_PROPERTIES
status=approved
target=privacy-review-approved-evidence
role=$privacy_review_role
noticePath=$PRIVACY_NOTICE
noticeSha256=$PRIVACY_NOTICE_SHA
scope=privacy-notice
requiredDecision=approved
approvalStatus=approved
PRIVACY_REVIEW_EVIDENCE_PROPERTIES
done
PRIVACY_REVIEW_RELEASE_EVIDENCE_SHA="$(shasum -a 256 "$PRIVACY_REVIEW_RELEASE_EVIDENCE" | awk '{print $1}')"
PRIVACY_REVIEW_SECURITY_EVIDENCE_SHA="$(shasum -a 256 "$PRIVACY_REVIEW_SECURITY_EVIDENCE" | awk '{print $1}')"
PRIVACY_REVIEW_LEGAL_EVIDENCE_SHA="$(shasum -a 256 "$PRIVACY_REVIEW_LEGAL_EVIDENCE" | awk '{print $1}')"
cat > "$PRIVACY_REVIEW_PENDING" <<'PRIVACY_REVIEW_PENDING_JSON'
{
  "version": 1,
  "noticePath": "PLACEHOLDER",
  "noticeSha256": "PLACEHOLDER",
  "status": "pending_manual_review",
  "reviews": []
}
PRIVACY_REVIEW_PENDING_JSON
expect_failure \
  "privacy review verifier rejects pending records" \
  env PRIVACY_REVIEW_FILE="$PRIVACY_REVIEW_PENDING" PRIVACY_NOTICE_FILE="$PRIVACY_NOTICE" \
  scripts/verify_privacy_review.sh --report "$ARTIFACT_DIR/privacy-review-pending.properties"
assert_release_verifier_failed_report \
  "$ARTIFACT_DIR/privacy-review-pending.properties" \
  "PrivacyReviewVerification/v1" \
  "privacy-security"
assert_report_contains "$ARTIFACT_DIR/privacy-review-pending.properties" "failedTarget=privacy-review-record"
assert_report_contains "$ARTIFACT_DIR/privacy-review-pending.properties" "noticeSha256=$PRIVACY_NOTICE_SHA"
expect_failure \
  "checked-in privacy review candidate has current notice and evidence hashes" \
  scripts/verify_privacy_review.sh --report "$ARTIFACT_DIR/privacy-review-checked-in-pending.properties"
assert_release_verifier_failed_report \
  "$ARTIFACT_DIR/privacy-review-checked-in-pending.properties" \
  "PrivacyReviewVerification/v1" \
  "privacy-security"
for privacy_review_sha_failure in \
  "notice-sha-mismatch" \
  "release-evidence-sha-mismatch" \
  "security-evidence-sha-mismatch" \
  "legal-evidence-sha-mismatch" \
  "release-evidence-notice-sha-mismatch" \
  "security-evidence-notice-sha-mismatch" \
  "legal-evidence-notice-sha-mismatch"; do
  if grep -q "$privacy_review_sha_failure" "$ARTIFACT_DIR/privacy-review-checked-in-pending.properties"; then
    fail "checked-in privacy review candidate must not fail on stale SHA: $privacy_review_sha_failure"
  fi
done
cat > "$PRIVACY_REVIEW_APPROVED" <<PRIVACY_REVIEW_APPROVED_JSON
{
  "version": 1,
  "noticePath": "$PRIVACY_NOTICE",
  "noticeSha256": "$PRIVACY_NOTICE_SHA",
  "status": "approved",
  "reviews": [
    {
      "role": "release",
      "decision": "approved",
      "reviewer": "Release Reviewer",
      "reviewDate": "2026-06-06",
      "evidencePath": "$PRIVACY_REVIEW_RELEASE_EVIDENCE",
      "evidenceSha256": "$PRIVACY_REVIEW_RELEASE_EVIDENCE_SHA"
    },
    {
      "role": "security",
      "decision": "approved",
      "reviewer": "Security Reviewer",
      "reviewDate": "2026-06-06",
      "evidencePath": "$PRIVACY_REVIEW_SECURITY_EVIDENCE",
      "evidenceSha256": "$PRIVACY_REVIEW_SECURITY_EVIDENCE_SHA"
    },
    {
      "role": "legal",
      "decision": "approved",
      "reviewer": "Legal Reviewer",
      "reviewDate": "2026-06-06",
      "evidencePath": "$PRIVACY_REVIEW_LEGAL_EVIDENCE",
      "evidenceSha256": "$PRIVACY_REVIEW_LEGAL_EVIDENCE_SHA"
    }
  ]
}
PRIVACY_REVIEW_APPROVED_JSON
expect_success \
  "privacy review verifier accepts approved current notice" \
  env PRIVACY_REVIEW_FILE="$PRIVACY_REVIEW_APPROVED" PRIVACY_NOTICE_FILE="$PRIVACY_NOTICE" \
  scripts/verify_privacy_review.sh --report "$ARTIFACT_DIR/privacy-review-approved.properties"
assert_release_verifier_passed_report \
  "$ARTIFACT_DIR/privacy-review-approved.properties" \
  "PrivacyReviewVerification/v1" \
  "privacy-security"
assert_report_contains "$ARTIFACT_DIR/privacy-review-approved.properties" "reviewSha256=$(shasum -a 256 "$PRIVACY_REVIEW_APPROVED" | awk '{print $1}')"
PRIVACY_REVIEW_FUTURE="$TMP_DIR/privacy-review-future.json"
sed 's/2026-06-06/2999-01-01/g' "$PRIVACY_REVIEW_APPROVED" > "$PRIVACY_REVIEW_FUTURE"
expect_failure \
  "privacy review verifier rejects future review dates" \
  env PRIVACY_REVIEW_FILE="$PRIVACY_REVIEW_FUTURE" PRIVACY_NOTICE_FILE="$PRIVACY_NOTICE" \
  scripts/verify_privacy_review.sh --report "$ARTIFACT_DIR/privacy-review-future.properties"
assert_report_contains "$ARTIFACT_DIR/privacy-review-future.properties" "status=failed"
PRIVACY_REVIEW_BAD_EVIDENCE_SHA="$TMP_DIR/privacy-review-bad-evidence-sha.json"
sed 's/"evidenceSha256": "'"$PRIVACY_REVIEW_RELEASE_EVIDENCE_SHA"'"/"evidenceSha256": "0000000000000000000000000000000000000000000000000000000000000000"/' "$PRIVACY_REVIEW_APPROVED" > "$PRIVACY_REVIEW_BAD_EVIDENCE_SHA"
expect_failure \
  "privacy review verifier rejects evidence sha mismatch" \
  env PRIVACY_REVIEW_FILE="$PRIVACY_REVIEW_BAD_EVIDENCE_SHA" PRIVACY_NOTICE_FILE="$PRIVACY_NOTICE" \
  scripts/verify_privacy_review.sh --report "$ARTIFACT_DIR/privacy-review-bad-evidence-sha.properties"
assert_report_contains_text "$ARTIFACT_DIR/privacy-review-bad-evidence-sha.properties" "release-evidence-sha-mismatch"
PRIVACY_REVIEW_PENDING_EVIDENCE="$TMP_DIR/privacy-review-release-pending.properties"
cat > "$PRIVACY_REVIEW_PENDING_EVIDENCE" <<PRIVACY_REVIEW_PENDING_EVIDENCE_PROPERTIES
status=pending
target=privacy-review-candidate-evidence
role=release
noticePath=$PRIVACY_NOTICE
noticeSha256=$PRIVACY_NOTICE_SHA
scope=privacy-notice
requiredDecision=approved
approvalStatus=not-approved
PRIVACY_REVIEW_PENDING_EVIDENCE_PROPERTIES
PRIVACY_REVIEW_PENDING_EVIDENCE_SHA="$(shasum -a 256 "$PRIVACY_REVIEW_PENDING_EVIDENCE" | awk '{print $1}')"
PRIVACY_REVIEW_PENDING_EVIDENCE_RECORD="$TMP_DIR/privacy-review-pending-evidence.json"
python3 - "$PRIVACY_REVIEW_APPROVED" "$PRIVACY_REVIEW_PENDING_EVIDENCE_RECORD" "$PRIVACY_REVIEW_PENDING_EVIDENCE" "$PRIVACY_REVIEW_PENDING_EVIDENCE_SHA" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
record["reviews"][0]["evidencePath"] = sys.argv[3]
record["reviews"][0]["evidenceSha256"] = sys.argv[4]
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "privacy review verifier rejects pending evidence content" \
  env PRIVACY_REVIEW_FILE="$PRIVACY_REVIEW_PENDING_EVIDENCE_RECORD" PRIVACY_NOTICE_FILE="$PRIVACY_NOTICE" \
  scripts/verify_privacy_review.sh --report "$ARTIFACT_DIR/privacy-review-pending-evidence.properties"
assert_report_contains_text "$ARTIFACT_DIR/privacy-review-pending-evidence.properties" "release-evidence-status-not-approved"
assert_report_contains_text "$ARTIFACT_DIR/privacy-review-pending-evidence.properties" "release-evidence-approval-status-not-approved"
PRIVACY_REVIEW_ROLE_MISMATCH_EVIDENCE="$TMP_DIR/privacy-review-release-role-mismatch.properties"
sed 's/role=release/role=security/' "$PRIVACY_REVIEW_RELEASE_EVIDENCE" > "$PRIVACY_REVIEW_ROLE_MISMATCH_EVIDENCE"
PRIVACY_REVIEW_ROLE_MISMATCH_EVIDENCE_SHA="$(shasum -a 256 "$PRIVACY_REVIEW_ROLE_MISMATCH_EVIDENCE" | awk '{print $1}')"
PRIVACY_REVIEW_ROLE_MISMATCH_RECORD="$TMP_DIR/privacy-review-role-mismatch-evidence.json"
python3 - "$PRIVACY_REVIEW_APPROVED" "$PRIVACY_REVIEW_ROLE_MISMATCH_RECORD" "$PRIVACY_REVIEW_ROLE_MISMATCH_EVIDENCE" "$PRIVACY_REVIEW_ROLE_MISMATCH_EVIDENCE_SHA" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
record["reviews"][0]["evidencePath"] = sys.argv[3]
record["reviews"][0]["evidenceSha256"] = sys.argv[4]
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "privacy review verifier rejects evidence role mismatch" \
  env PRIVACY_REVIEW_FILE="$PRIVACY_REVIEW_ROLE_MISMATCH_RECORD" PRIVACY_NOTICE_FILE="$PRIVACY_NOTICE" \
  scripts/verify_privacy_review.sh --report "$ARTIFACT_DIR/privacy-review-role-mismatch-evidence.properties"
assert_report_contains_text "$ARTIFACT_DIR/privacy-review-role-mismatch-evidence.properties" "release-evidence-role-mismatch"
PRIVACY_REVIEW_NOTICE_MISMATCH_EVIDENCE="$TMP_DIR/privacy-review-release-notice-mismatch.properties"
sed "s/noticeSha256=$PRIVACY_NOTICE_SHA/noticeSha256=0000000000000000000000000000000000000000000000000000000000000000/" \
  "$PRIVACY_REVIEW_RELEASE_EVIDENCE" > "$PRIVACY_REVIEW_NOTICE_MISMATCH_EVIDENCE"
PRIVACY_REVIEW_NOTICE_MISMATCH_EVIDENCE_SHA="$(shasum -a 256 "$PRIVACY_REVIEW_NOTICE_MISMATCH_EVIDENCE" | awk '{print $1}')"
PRIVACY_REVIEW_NOTICE_MISMATCH_RECORD="$TMP_DIR/privacy-review-notice-mismatch-evidence.json"
python3 - "$PRIVACY_REVIEW_APPROVED" "$PRIVACY_REVIEW_NOTICE_MISMATCH_RECORD" "$PRIVACY_REVIEW_NOTICE_MISMATCH_EVIDENCE" "$PRIVACY_REVIEW_NOTICE_MISMATCH_EVIDENCE_SHA" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
record["reviews"][0]["evidencePath"] = sys.argv[3]
record["reviews"][0]["evidenceSha256"] = sys.argv[4]
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "privacy review verifier rejects evidence notice sha mismatch" \
  env PRIVACY_REVIEW_FILE="$PRIVACY_REVIEW_NOTICE_MISMATCH_RECORD" PRIVACY_NOTICE_FILE="$PRIVACY_NOTICE" \
  scripts/verify_privacy_review.sh --report "$ARTIFACT_DIR/privacy-review-notice-mismatch-evidence.properties"
assert_report_contains_text "$ARTIFACT_DIR/privacy-review-notice-mismatch-evidence.properties" "release-evidence-notice-sha-mismatch"

MODEL_LICENSE_METADATA="$TMP_DIR/model-license-metadata.json"
MODEL_LICENSE_MANIFEST="$TMP_DIR/model-manifest.md"
MODEL_LICENSE_PENDING="$TMP_DIR/model-license-pending.json"
MODEL_LICENSE_APPROVED="$TMP_DIR/model-license-approved.json"
MODEL_LICENSE_CHAT_EVIDENCE="$TMP_DIR/model-license-chat-e2b-review.properties"
MODEL_LICENSE_MEMORY_EVIDENCE="$TMP_DIR/model-license-memory-embedding-300m-review.properties"
MODEL_LICENSE_REVIEW_RECORDED_AT="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
cat > "$MODEL_LICENSE_CHAT_EVIDENCE" <<MODEL_LICENSE_CHAT_EVIDENCE_PROPERTIES
artifactSchema=ModelLicenseReviewApprovedEvidence/v1
status=approved
target=model-license-review-approved-evidence
owner=model-license-reviewer
recordedAt=$MODEL_LICENSE_REVIEW_RECORDED_AT
command=manual-model-license-review chat-e2b
reproduciblePath=$MODEL_LICENSE_CHAT_EVIDENCE
model=chat-e2b
scope=license-redistribution-attribution
redistributionDecision=approved
licenseName=Apache-2.0
reviewer=Model Reviewer
MODEL_LICENSE_CHAT_EVIDENCE_PROPERTIES
cat > "$MODEL_LICENSE_MEMORY_EVIDENCE" <<MODEL_LICENSE_MEMORY_EVIDENCE_PROPERTIES
artifactSchema=ModelLicenseReviewApprovedEvidence/v1
status=approved
target=model-license-review-approved-evidence
owner=model-license-reviewer
recordedAt=$MODEL_LICENSE_REVIEW_RECORDED_AT
command=manual-model-license-review memory-embedding-300m
reproduciblePath=$MODEL_LICENSE_MEMORY_EVIDENCE
model=memory-embedding-300m
scope=license-redistribution-attribution
redistributionDecision=approved
licenseName=Apache-2.0
reviewer=Model Reviewer
MODEL_LICENSE_MEMORY_EVIDENCE_PROPERTIES
MODEL_LICENSE_CHAT_EVIDENCE_SHA="$(shasum -a 256 "$MODEL_LICENSE_CHAT_EVIDENCE" | awk '{print $1}')"
MODEL_LICENSE_MEMORY_EVIDENCE_SHA="$(shasum -a 256 "$MODEL_LICENSE_MEMORY_EVIDENCE" | awk '{print $1}')"
cat > "$MODEL_LICENSE_MANIFEST" <<'MODEL_LICENSE_MANIFEST_MD'
| ID | File | Repository | Upstream revision | Bytes | SHA-256 | License status |
| --- | --- | --- | --- | ---: | --- | --- |
| `chat-e2b` | `chat.litertlm` | `https://huggingface.co/example/chat-e2b` | `chat-revision-a` | `1` | `abc` | Pending. |
| `memory-embedding-300m` | `memory.litertlm` | `https://huggingface.co/example/memory-embedding-300m` | `memory-revision-a` | `1` | `def` | Pending. |
MODEL_LICENSE_MANIFEST_MD
cat > "$MODEL_LICENSE_METADATA" <<'MODEL_LICENSE_METADATA_JSON'
{
  "version": 1,
  "recordedAt": "2026-06-05T00:00:00Z",
  "models": [
    {
      "id": "chat-e2b",
      "repository": "example/chat-e2b",
      "manifestRevision": "chat-revision-a",
      "apiUrl": "https://huggingface.co/api/models/example/chat-e2b",
      "modelSha": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "gated": false,
      "requiresUserAuthorization": false,
      "licenseTags": ["apache-2.0"],
      "cardLicense": "apache-2.0",
      "metadataOnly": true
    },
    {
      "id": "memory-embedding-300m",
      "repository": "example/memory-embedding-300m",
      "manifestRevision": "memory-revision-a",
      "apiUrl": "https://huggingface.co/api/models/example/memory-embedding-300m",
      "modelSha": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
      "gated": false,
      "requiresUserAuthorization": false,
      "licenseTags": ["apache-2.0"],
      "cardLicense": "apache-2.0",
      "metadataOnly": true
    }
  ]
}
MODEL_LICENSE_METADATA_JSON
cat > "$MODEL_LICENSE_PENDING" <<'MODEL_LICENSE_PENDING_JSON'
{
  "version": 1,
  "models": [
    {
      "id": "chat-e2b",
      "status": "pending_manual_review",
      "licenseName": "",
      "licenseUrl": "https://example.com/model",
      "redistributionDecision": "not_approved",
      "attributionNotice": "",
      "reviewer": "",
      "reviewDate": "",
      "reviewEvidencePath": "",
      "reviewEvidenceSha256": ""
    }
  ]
}
MODEL_LICENSE_PENDING_JSON
MODEL_LICENSE_FAKE_API_ROOT="$TMP_DIR/model-license-fake-api"
mkdir -p "$MODEL_LICENSE_FAKE_API_ROOT/example"
cat > "$MODEL_LICENSE_FAKE_API_ROOT/example/chat-e2b" <<'MODEL_LICENSE_CHAT_API_JSON'
{
  "sha": "chat-current-api-sha",
  "lastModified": "2026-06-05T00:00:00.000Z",
  "gated": false,
  "tags": ["license:apache-2.0"],
  "cardData": {"license": "apache-2.0"},
  "siblings": [
    {"rfilename": "README.md"},
    {"rfilename": "LICENSE"},
    {"rfilename": "model.litertlm"}
  ]
}
MODEL_LICENSE_CHAT_API_JSON
cat > "$MODEL_LICENSE_FAKE_API_ROOT/example/memory-embedding-300m" <<'MODEL_LICENSE_MEMORY_API_JSON'
{
  "sha": "memory-current-api-sha",
  "lastModified": "2026-06-05T00:00:00.000Z",
  "gated": false,
  "tags": ["license:apache-2.0"],
  "cardData": {"license": "apache-2.0"},
  "siblings": [
    {"rfilename": "README.md"},
    {"rfilename": "NOTICE.txt"},
    {"rfilename": "memory.litertlm"}
  ]
}
MODEL_LICENSE_MEMORY_API_JSON
MODEL_LICENSE_COLLECTED="$TMP_DIR/model-license-collected.json"
expect_success \
  "model license metadata collector records source candidates" \
  env REVIEW_FILE="$MODEL_LICENSE_PENDING" \
  MANIFEST_FILE="$MODEL_LICENSE_MANIFEST" \
  OUT_FILE="$MODEL_LICENSE_COLLECTED" \
  REPORT_FILE="$ARTIFACT_DIR/model-license-collector.properties" \
  MODEL_LICENSE_API_BASE_URL="file://$MODEL_LICENSE_FAKE_API_ROOT" \
  scripts/collect_model_license_metadata.sh
assert_report_contains "$ARTIFACT_DIR/model-license-collector.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/model-license-collector.properties" "target=model-license-metadata-collector"
assert_report_contains "$ARTIFACT_DIR/model-license-collector.properties" "modelCount=2"
grep -q '"licenseSourceCandidates"' "$MODEL_LICENSE_COLLECTED" ||
  fail "Expected collected model license metadata to include source candidates"
grep -q 'https://huggingface.co/example/chat-e2b/blob/chat-revision-a/README.md' "$MODEL_LICENSE_COLLECTED" ||
  fail "Expected collected metadata to include chat README license source candidate"
grep -q 'https://huggingface.co/example/memory-embedding-300m/blob/memory-revision-a/NOTICE.txt' "$MODEL_LICENSE_COLLECTED" ||
  fail "Expected collected metadata to include memory NOTICE license source candidate"
expect_failure \
  "model license metadata collector reports missing review file" \
  env REVIEW_FILE="$TMP_DIR/missing-model-license-review.json" \
  MANIFEST_FILE="$MODEL_LICENSE_MANIFEST" \
  OUT_FILE="$TMP_DIR/model-license-missing-review-collected.json" \
  REPORT_FILE="$ARTIFACT_DIR/model-license-collector-missing-review.properties" \
  MODEL_LICENSE_API_BASE_URL="file://$MODEL_LICENSE_FAKE_API_ROOT" \
  scripts/collect_model_license_metadata.sh
assert_report_contains "$ARTIFACT_DIR/model-license-collector-missing-review.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/model-license-collector-missing-review.properties" "failedTarget=input-file"
assert_report_contains "$ARTIFACT_DIR/model-license-collector-missing-review.properties" "reason=missing-review-file"
expect_failure \
  "model license verifier rejects incomplete review records" \
  env MODEL_LICENSE_METADATA_MAX_AGE_DAYS=36500 MODEL_LICENSE_REVIEW_FILE="$MODEL_LICENSE_PENDING" MODEL_LICENSE_METADATA_FILE="$MODEL_LICENSE_METADATA" MODEL_MANIFEST_FILE="$MODEL_LICENSE_MANIFEST" \
  scripts/verify_model_license_review.sh --report "$ARTIFACT_DIR/model-license-pending.properties"
assert_release_verifier_failed_report \
  "$ARTIFACT_DIR/model-license-pending.properties" \
  "ModelLicenseReviewVerification/v1" \
  "model-license-review"
assert_report_contains "$ARTIFACT_DIR/model-license-pending.properties" "metadataMaxAgeDays=36500"
assert_report_contains "$ARTIFACT_DIR/model-license-pending.properties" "failedTarget=model-license-review-record"
cat > "$MODEL_LICENSE_APPROVED" <<MODEL_LICENSE_APPROVED_JSON
{
  "version": 1,
  "models": [
    {
      "id": "chat-e2b",
      "repository": "example/chat-e2b",
      "upstreamRevision": "chat-revision-a",
      "status": "approved",
      "licenseName": "Apache-2.0",
      "licenseUrl": "https://huggingface.co/example/chat-e2b/blob/chat-revision-a/README.md",
      "redistributionDecision": "approved",
      "attributionNotice": "Include Apache-2.0 notice.",
      "reviewer": "Model Reviewer",
      "reviewDate": "2026-06-06",
      "reviewEvidencePath": "$MODEL_LICENSE_CHAT_EVIDENCE",
      "reviewEvidenceSha256": "$MODEL_LICENSE_CHAT_EVIDENCE_SHA"
    },
    {
      "id": "memory-embedding-300m",
      "repository": "example/memory-embedding-300m",
      "upstreamRevision": "memory-revision-a",
      "status": "approved",
      "licenseName": "Apache-2.0",
      "licenseUrl": "https://huggingface.co/example/memory-embedding-300m/blob/memory-revision-a/README.md",
      "redistributionDecision": "approved",
      "attributionNotice": "Include Apache-2.0 notice.",
      "reviewer": "Model Reviewer",
      "reviewDate": "2026-06-06",
      "reviewEvidencePath": "$MODEL_LICENSE_MEMORY_EVIDENCE",
      "reviewEvidenceSha256": "$MODEL_LICENSE_MEMORY_EVIDENCE_SHA"
    }
  ]
}
MODEL_LICENSE_APPROVED_JSON
expect_success \
  "model license verifier accepts approved metadata-aligned records" \
  env MODEL_LICENSE_METADATA_MAX_AGE_DAYS=36500 MODEL_LICENSE_REVIEW_FILE="$MODEL_LICENSE_APPROVED" MODEL_LICENSE_METADATA_FILE="$MODEL_LICENSE_METADATA" MODEL_MANIFEST_FILE="$MODEL_LICENSE_MANIFEST" \
  scripts/verify_model_license_review.sh --report "$ARTIFACT_DIR/model-license-approved.properties"
assert_release_verifier_passed_report \
  "$ARTIFACT_DIR/model-license-approved.properties" \
  "ModelLicenseReviewVerification/v1" \
  "model-license-review"
assert_report_contains "$ARTIFACT_DIR/model-license-approved.properties" "reviewSha256=$(shasum -a 256 "$MODEL_LICENSE_APPROVED" | awk '{print $1}')"
MODEL_LICENSE_SOURCE_MISMATCH="$TMP_DIR/model-license-source-mismatch.json"
sed 's#https://huggingface.co/example/chat-e2b/blob/chat-revision-a/README.md#https://huggingface.co/example/wrong-model/blob/chat-revision-a/README.md#' "$MODEL_LICENSE_APPROVED" > "$MODEL_LICENSE_SOURCE_MISMATCH"
expect_failure \
  "model license verifier rejects Hugging Face license source for a different repository" \
  env MODEL_LICENSE_METADATA_MAX_AGE_DAYS=36500 MODEL_LICENSE_REVIEW_FILE="$MODEL_LICENSE_SOURCE_MISMATCH" MODEL_LICENSE_METADATA_FILE="$MODEL_LICENSE_METADATA" MODEL_MANIFEST_FILE="$MODEL_LICENSE_MANIFEST" \
  scripts/verify_model_license_review.sh --report "$ARTIFACT_DIR/model-license-source-mismatch.properties"
assert_report_contains "$ARTIFACT_DIR/model-license-source-mismatch.properties" "status=failed"
MODEL_LICENSE_REPO_ROOT="$TMP_DIR/model-license-repo-root.json"
sed 's#https://huggingface.co/example/chat-e2b/blob/chat-revision-a/README.md#https://huggingface.co/example/chat-e2b#' "$MODEL_LICENSE_APPROVED" > "$MODEL_LICENSE_REPO_ROOT"
expect_failure \
  "model license verifier rejects Hugging Face repository root as license source" \
  env MODEL_LICENSE_METADATA_MAX_AGE_DAYS=36500 MODEL_LICENSE_REVIEW_FILE="$MODEL_LICENSE_REPO_ROOT" MODEL_LICENSE_METADATA_FILE="$MODEL_LICENSE_METADATA" MODEL_MANIFEST_FILE="$MODEL_LICENSE_MANIFEST" \
  scripts/verify_model_license_review.sh --report "$ARTIFACT_DIR/model-license-repo-root.properties"
assert_report_contains_text "$ARTIFACT_DIR/model-license-repo-root.properties" "chat-e2b-license-source-not-concrete"
MODEL_LICENSE_SOURCE_REVISION_MISMATCH="$TMP_DIR/model-license-source-revision-mismatch.json"
sed 's#https://huggingface.co/example/chat-e2b/blob/chat-revision-a/README.md#https://huggingface.co/example/chat-e2b/blob/other-revision/README.md#' "$MODEL_LICENSE_APPROVED" > "$MODEL_LICENSE_SOURCE_REVISION_MISMATCH"
expect_failure \
  "model license verifier rejects Hugging Face license source for a different revision" \
  env MODEL_LICENSE_METADATA_MAX_AGE_DAYS=36500 MODEL_LICENSE_REVIEW_FILE="$MODEL_LICENSE_SOURCE_REVISION_MISMATCH" MODEL_LICENSE_METADATA_FILE="$MODEL_LICENSE_METADATA" MODEL_MANIFEST_FILE="$MODEL_LICENSE_MANIFEST" \
  scripts/verify_model_license_review.sh --report "$ARTIFACT_DIR/model-license-source-revision-mismatch.properties"
assert_report_contains_text "$ARTIFACT_DIR/model-license-source-revision-mismatch.properties" "chat-e2b-license-source-revision-mismatch"
MODEL_LICENSE_BAD_REVIEW_EVIDENCE_SHA="$TMP_DIR/model-license-bad-review-evidence-sha.json"
sed 's/"reviewEvidenceSha256": "'"$MODEL_LICENSE_CHAT_EVIDENCE_SHA"'"/"reviewEvidenceSha256": "0000000000000000000000000000000000000000000000000000000000000000"/' "$MODEL_LICENSE_APPROVED" > "$MODEL_LICENSE_BAD_REVIEW_EVIDENCE_SHA"
expect_failure \
  "model license verifier rejects review evidence sha mismatch" \
  env MODEL_LICENSE_METADATA_MAX_AGE_DAYS=36500 MODEL_LICENSE_REVIEW_FILE="$MODEL_LICENSE_BAD_REVIEW_EVIDENCE_SHA" MODEL_LICENSE_METADATA_FILE="$MODEL_LICENSE_METADATA" MODEL_MANIFEST_FILE="$MODEL_LICENSE_MANIFEST" \
  scripts/verify_model_license_review.sh --report "$ARTIFACT_DIR/model-license-bad-review-evidence-sha.properties"
assert_report_contains_text "$ARTIFACT_DIR/model-license-bad-review-evidence-sha.properties" "chat-e2b-review-evidence-sha-mismatch"
MODEL_LICENSE_PENDING_REVIEW_EVIDENCE="$TMP_DIR/model-license-pending-review-evidence.properties"
sed 's/status=approved/status=pending/' "$MODEL_LICENSE_CHAT_EVIDENCE" > "$MODEL_LICENSE_PENDING_REVIEW_EVIDENCE"
MODEL_LICENSE_PENDING_REVIEW_EVIDENCE_SHA="$(shasum -a 256 "$MODEL_LICENSE_PENDING_REVIEW_EVIDENCE" | awk '{print $1}')"
MODEL_LICENSE_BAD_REVIEW_EVIDENCE_CONTENT="$TMP_DIR/model-license-bad-review-evidence-content.json"
python3 - "$MODEL_LICENSE_APPROVED" "$MODEL_LICENSE_BAD_REVIEW_EVIDENCE_CONTENT" "$MODEL_LICENSE_PENDING_REVIEW_EVIDENCE" "$MODEL_LICENSE_PENDING_REVIEW_EVIDENCE_SHA" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
record["models"][0]["reviewEvidencePath"] = sys.argv[3]
record["models"][0]["reviewEvidenceSha256"] = sys.argv[4]
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "model license verifier rejects pending review evidence content" \
  env MODEL_LICENSE_METADATA_MAX_AGE_DAYS=36500 MODEL_LICENSE_REVIEW_FILE="$MODEL_LICENSE_BAD_REVIEW_EVIDENCE_CONTENT" MODEL_LICENSE_METADATA_FILE="$MODEL_LICENSE_METADATA" MODEL_MANIFEST_FILE="$MODEL_LICENSE_MANIFEST" \
  scripts/verify_model_license_review.sh --report "$ARTIFACT_DIR/model-license-bad-review-evidence-content.properties"
assert_report_contains_text "$ARTIFACT_DIR/model-license-bad-review-evidence-content.properties" "chat-e2b-review-evidence-status-mismatch"
MODEL_LICENSE_WEAK_REVIEW_EVIDENCE="$TMP_DIR/model-license-weak-review-evidence.properties"
sed '/^artifactSchema=/d' "$MODEL_LICENSE_CHAT_EVIDENCE" > "$MODEL_LICENSE_WEAK_REVIEW_EVIDENCE"
MODEL_LICENSE_WEAK_REVIEW_EVIDENCE_SHA="$(shasum -a 256 "$MODEL_LICENSE_WEAK_REVIEW_EVIDENCE" | awk '{print $1}')"
MODEL_LICENSE_WEAK_REVIEW_EVIDENCE_RECORD="$TMP_DIR/model-license-weak-review-evidence.json"
python3 - "$MODEL_LICENSE_APPROVED" "$MODEL_LICENSE_WEAK_REVIEW_EVIDENCE_RECORD" "$MODEL_LICENSE_WEAK_REVIEW_EVIDENCE" "$MODEL_LICENSE_WEAK_REVIEW_EVIDENCE_SHA" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
record["models"][0]["reviewEvidencePath"] = sys.argv[3]
record["models"][0]["reviewEvidenceSha256"] = sys.argv[4]
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "model license verifier rejects review evidence without schema" \
  env MODEL_LICENSE_METADATA_MAX_AGE_DAYS=36500 MODEL_LICENSE_REVIEW_FILE="$MODEL_LICENSE_WEAK_REVIEW_EVIDENCE_RECORD" MODEL_LICENSE_METADATA_FILE="$MODEL_LICENSE_METADATA" MODEL_MANIFEST_FILE="$MODEL_LICENSE_MANIFEST" \
  scripts/verify_model_license_review.sh --report "$ARTIFACT_DIR/model-license-weak-review-evidence.properties"
assert_report_contains_text "$ARTIFACT_DIR/model-license-weak-review-evidence.properties" "chat-e2b-review-evidence-artifactSchema-mismatch"
MODEL_LICENSE_FUTURE_REVIEW_EVIDENCE="$TMP_DIR/model-license-future-review-evidence.properties"
sed 's/^recordedAt=.*/recordedAt=2999-01-01T00:00:00Z/' "$MODEL_LICENSE_CHAT_EVIDENCE" > "$MODEL_LICENSE_FUTURE_REVIEW_EVIDENCE"
MODEL_LICENSE_FUTURE_REVIEW_EVIDENCE_SHA="$(shasum -a 256 "$MODEL_LICENSE_FUTURE_REVIEW_EVIDENCE" | awk '{print $1}')"
MODEL_LICENSE_FUTURE_REVIEW_EVIDENCE_RECORD="$TMP_DIR/model-license-future-review-evidence.json"
python3 - "$MODEL_LICENSE_APPROVED" "$MODEL_LICENSE_FUTURE_REVIEW_EVIDENCE_RECORD" "$MODEL_LICENSE_FUTURE_REVIEW_EVIDENCE" "$MODEL_LICENSE_FUTURE_REVIEW_EVIDENCE_SHA" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
record["models"][0]["reviewEvidencePath"] = sys.argv[3]
record["models"][0]["reviewEvidenceSha256"] = sys.argv[4]
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "model license verifier rejects future review evidence timestamp" \
  env MODEL_LICENSE_METADATA_MAX_AGE_DAYS=36500 MODEL_LICENSE_REVIEW_FILE="$MODEL_LICENSE_FUTURE_REVIEW_EVIDENCE_RECORD" MODEL_LICENSE_METADATA_FILE="$MODEL_LICENSE_METADATA" MODEL_MANIFEST_FILE="$MODEL_LICENSE_MANIFEST" \
  scripts/verify_model_license_review.sh --report "$ARTIFACT_DIR/model-license-future-review-evidence.properties"
assert_report_contains_text "$ARTIFACT_DIR/model-license-future-review-evidence.properties" "chat-e2b-review-evidence-recorded-at-in-future"
MODEL_LICENSE_STALE_METADATA="$TMP_DIR/model-license-stale-metadata.json"
sed 's/"recordedAt": "2026-06-05T00:00:00Z"/"recordedAt": "2000-01-01T00:00:00Z"/' "$MODEL_LICENSE_METADATA" > "$MODEL_LICENSE_STALE_METADATA"
expect_failure \
  "model license verifier rejects stale metadata collection" \
  env MODEL_LICENSE_METADATA_MAX_AGE_DAYS=7 MODEL_LICENSE_REVIEW_FILE="$MODEL_LICENSE_APPROVED" MODEL_LICENSE_METADATA_FILE="$MODEL_LICENSE_STALE_METADATA" MODEL_MANIFEST_FILE="$MODEL_LICENSE_MANIFEST" \
  scripts/verify_model_license_review.sh --report "$ARTIFACT_DIR/model-license-stale-metadata.properties"
assert_report_contains_text "$ARTIFACT_DIR/model-license-stale-metadata.properties" "metadata-recorded-at-stale"
MODEL_LICENSE_FUTURE_METADATA="$TMP_DIR/model-license-future-metadata.json"
sed 's/"recordedAt": "2026-06-05T00:00:00Z"/"recordedAt": "2999-01-01T00:00:00Z"/' "$MODEL_LICENSE_METADATA" > "$MODEL_LICENSE_FUTURE_METADATA"
expect_failure \
  "model license verifier rejects future metadata collection time" \
  env MODEL_LICENSE_METADATA_MAX_AGE_DAYS=36500 MODEL_LICENSE_REVIEW_FILE="$MODEL_LICENSE_APPROVED" MODEL_LICENSE_METADATA_FILE="$MODEL_LICENSE_FUTURE_METADATA" MODEL_MANIFEST_FILE="$MODEL_LICENSE_MANIFEST" \
  scripts/verify_model_license_review.sh --report "$ARTIFACT_DIR/model-license-future-metadata.properties"
assert_report_contains_text "$ARTIFACT_DIR/model-license-future-metadata.properties" "metadata-recorded-at-in-future"
MODEL_LICENSE_NON_UTC_METADATA="$TMP_DIR/model-license-non-utc-metadata.json"
sed 's/"recordedAt": "2026-06-05T00:00:00Z"/"recordedAt": "2026-06-05"/' "$MODEL_LICENSE_METADATA" > "$MODEL_LICENSE_NON_UTC_METADATA"
expect_failure \
  "model license verifier rejects non-UTC metadata collection time" \
  env MODEL_LICENSE_METADATA_MAX_AGE_DAYS=36500 MODEL_LICENSE_REVIEW_FILE="$MODEL_LICENSE_APPROVED" MODEL_LICENSE_METADATA_FILE="$MODEL_LICENSE_NON_UTC_METADATA" MODEL_MANIFEST_FILE="$MODEL_LICENSE_MANIFEST" \
  scripts/verify_model_license_review.sh --report "$ARTIFACT_DIR/model-license-non-utc-metadata.properties"
assert_report_contains_text "$ARTIFACT_DIR/model-license-non-utc-metadata.properties" "metadata-recorded-at-not-utc"
MODEL_LICENSE_BAD_MODEL_SHA="$TMP_DIR/model-license-bad-model-sha.json"
sed 's/"modelSha": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"/"modelSha": "chat-current-api-sha"/' "$MODEL_LICENSE_METADATA" > "$MODEL_LICENSE_BAD_MODEL_SHA"
expect_failure \
  "model license verifier rejects invalid metadata model sha" \
  env MODEL_LICENSE_METADATA_MAX_AGE_DAYS=36500 MODEL_LICENSE_REVIEW_FILE="$MODEL_LICENSE_APPROVED" MODEL_LICENSE_METADATA_FILE="$MODEL_LICENSE_BAD_MODEL_SHA" MODEL_MANIFEST_FILE="$MODEL_LICENSE_MANIFEST" \
  scripts/verify_model_license_review.sh --report "$ARTIFACT_DIR/model-license-bad-model-sha.properties"
assert_report_contains_text "$ARTIFACT_DIR/model-license-bad-model-sha.properties" "chat-e2b-metadata-model-sha-invalid"
MODEL_LICENSE_STALE_REVIEW="$TMP_DIR/model-license-stale-review.json"
sed 's/2026-06-06/2026-06-04/g' "$MODEL_LICENSE_APPROVED" > "$MODEL_LICENSE_STALE_REVIEW"
expect_failure \
  "model license verifier rejects review dates before metadata collection" \
  env MODEL_LICENSE_METADATA_MAX_AGE_DAYS=36500 MODEL_LICENSE_REVIEW_FILE="$MODEL_LICENSE_STALE_REVIEW" MODEL_LICENSE_METADATA_FILE="$MODEL_LICENSE_METADATA" MODEL_MANIFEST_FILE="$MODEL_LICENSE_MANIFEST" \
  scripts/verify_model_license_review.sh --report "$ARTIFACT_DIR/model-license-stale-review.properties"
assert_report_contains "$ARTIFACT_DIR/model-license-stale-review.properties" "status=failed"
MODEL_LICENSE_FUTURE="$TMP_DIR/model-license-future.json"
sed 's/2026-06-06/2999-01-01/g' "$MODEL_LICENSE_APPROVED" > "$MODEL_LICENSE_FUTURE"
expect_failure \
  "model license verifier rejects future review dates" \
  env MODEL_LICENSE_METADATA_MAX_AGE_DAYS=36500 MODEL_LICENSE_REVIEW_FILE="$MODEL_LICENSE_FUTURE" MODEL_LICENSE_METADATA_FILE="$MODEL_LICENSE_METADATA" MODEL_MANIFEST_FILE="$MODEL_LICENSE_MANIFEST" \
  scripts/verify_model_license_review.sh --report "$ARTIFACT_DIR/model-license-future.properties"
assert_report_contains "$ARTIFACT_DIR/model-license-future.properties" "status=failed"

SAFE_APK="$TMP_DIR/safe.apk"
SAFE_AAB="$TMP_DIR/safe.aab"
BAD_AAB="$TMP_DIR/bad.aab"
UNSAFE_APK="$TMP_DIR/unsafe.apk"
mkdir -p "$TMP_DIR/safe-apk/assets" "$TMP_DIR/safe-aab/base/manifest" "$TMP_DIR/unsafe-zip/assets"
printf '<manifest />\n' > "$TMP_DIR/safe-apk/AndroidManifest.xml"
printf 'bundle-config\n' > "$TMP_DIR/safe-aab/BundleConfig.pb"
printf '<manifest />\n' > "$TMP_DIR/safe-aab/base/manifest/AndroidManifest.xml"
printf 'ok\n' > "$TMP_DIR/safe-apk/assets/readme.txt"
printf 'ok\n' > "$TMP_DIR/safe-aab/base/readme.txt"
printf 'model\n' > "$TMP_DIR/unsafe-zip/assets/model.litertlm"
ARTIFACT_SCAN_SECRET_BODY="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
ARTIFACT_SCAN_SECRET="sk-${ARTIFACT_SCAN_SECRET_BODY}"
printf 'token=%s\n' "$ARTIFACT_SCAN_SECRET" > "$TMP_DIR/unsafe-zip/assets/secret.txt"
(cd "$TMP_DIR/safe-apk" && zip -qr "$SAFE_APK" .)
(cd "$TMP_DIR/safe-aab" && zip -qr "$SAFE_AAB" .)
(cd "$TMP_DIR/unsafe-zip" && zip -qr "$UNSAFE_APK" .)
printf 'not a bundle\n' > "$BAD_AAB"
expect_success \
  "artifact scan accepts safe zip" \
  scripts/scan_android_artifacts.sh --apk "$SAFE_APK" --report "$ARTIFACT_DIR/artifact.properties"
assert_report_contains "$ARTIFACT_DIR/artifact.properties" "status=passed"
assert_release_verifier_report_schema "$ARTIFACT_DIR/artifact.properties" "AndroidArtifactScanReport/v1"
SAFE_APK_SHA="$(shasum -a 256 "$SAFE_APK" | awk '{print $1}')"
SAFE_APK_SIZE="$(wc -c < "$SAFE_APK" | tr -d ' ')"
assert_report_contains "$ARTIFACT_DIR/artifact.properties" "artifact1Path=$SAFE_APK"
assert_report_contains "$ARTIFACT_DIR/artifact.properties" "artifact1Sha256=$SAFE_APK_SHA"
assert_report_contains "$ARTIFACT_DIR/artifact.properties" "artifact1SizeBytes=$SAFE_APK_SIZE"
SPACED_APK="$TMP_DIR/safe artifact spaced.apk"
SPACED_ARTIFACT_REPORT="$ARTIFACT_DIR/artifact spaced command.properties"
cp "$SAFE_APK" "$SPACED_APK"
expect_success \
  "artifact scan report command preserves spaced argv" \
  scripts/scan_android_artifacts.sh --apk "$SPACED_APK" --report "$SPACED_ARTIFACT_REPORT"
assert_report_command_argv \
  "$SPACED_ARTIFACT_REPORT" \
  scripts/scan_android_artifacts.sh \
  --apk "$SPACED_APK" \
  --report "$SPACED_ARTIFACT_REPORT"
ARTIFACT_MALFORMED_REPORT="$ARTIFACT_DIR/artifact-malformed-argument.properties"
expect_failure \
  "artifact scan reports malformed arguments when report path is known" \
  scripts/scan_android_artifacts.sh --report "$ARTIFACT_MALFORMED_REPORT" --bad-arg
assert_release_verifier_report_schema "$ARTIFACT_MALFORMED_REPORT" "AndroidArtifactScanReport/v1"
assert_report_contains "$ARTIFACT_MALFORMED_REPORT" "status=failed"
assert_report_contains "$ARTIFACT_MALFORMED_REPORT" "reason=unknown-argument"
assert_report_contains "$ARTIFACT_MALFORMED_REPORT" "failedTarget=argument-parser"
ARTIFACT_MISSING_VALUE_REPORT="$ARTIFACT_DIR/artifact-missing-value.properties"
expect_failure \
  "artifact scan reports missing argument values when report path is known" \
  scripts/scan_android_artifacts.sh --report "$ARTIFACT_MISSING_VALUE_REPORT" --apk
assert_release_verifier_report_schema "$ARTIFACT_MISSING_VALUE_REPORT" "AndroidArtifactScanReport/v1"
assert_report_contains "$ARTIFACT_MISSING_VALUE_REPORT" "status=failed"
assert_report_contains "$ARTIFACT_MISSING_VALUE_REPORT" "reason=missing-apk-argument"
assert_report_contains "$ARTIFACT_MISSING_VALUE_REPORT" "failedTarget=argument-parser"
ARTIFACT_OPTION_VALUE_REPORT="$ARTIFACT_DIR/artifact-option-like-value.properties"
expect_failure \
  "artifact scan treats option-like artifact path as missing" \
  scripts/scan_android_artifacts.sh --report "$ARTIFACT_OPTION_VALUE_REPORT" --apk --bad-arg
assert_release_verifier_report_schema "$ARTIFACT_OPTION_VALUE_REPORT" "AndroidArtifactScanReport/v1"
assert_report_contains "$ARTIFACT_OPTION_VALUE_REPORT" "status=failed"
assert_report_contains "$ARTIFACT_OPTION_VALUE_REPORT" "reason=missing-apk-argument"
assert_report_contains "$ARTIFACT_OPTION_VALUE_REPORT" "failedTarget=argument-parser"
ARTIFACT_EXPECTED_CERT_OPTION_VALUE_REPORT="$ARTIFACT_DIR/artifact-expected-cert-option-like-value.properties"
expect_failure \
  "artifact scan treats option-like expected certificate sha as missing" \
  scripts/scan_android_artifacts.sh --report "$ARTIFACT_EXPECTED_CERT_OPTION_VALUE_REPORT" --apk "$SAFE_APK" --expected-certificate-sha256 --bad-arg
assert_release_verifier_report_schema "$ARTIFACT_EXPECTED_CERT_OPTION_VALUE_REPORT" "AndroidArtifactScanReport/v1"
assert_report_contains "$ARTIFACT_EXPECTED_CERT_OPTION_VALUE_REPORT" "status=failed"
assert_report_contains "$ARTIFACT_EXPECTED_CERT_OPTION_VALUE_REPORT" "reason=missing-expected-certificate-sha256-argument"
assert_report_contains "$ARTIFACT_EXPECTED_CERT_OPTION_VALUE_REPORT" "failedTarget=argument-parser"
grep -q '^artifact1Sha256=' "$ARTIFACT_DIR/artifact.properties" ||
  fail "artifact scan report must include artifact sha"
grep -q '^artifact1SizeBytes=' "$ARTIFACT_DIR/artifact.properties" ||
  fail "artifact scan report must include artifact size"
expect_failure \
  "artifact scan rejects bundled model" \
  scripts/scan_android_artifacts.sh --apk "$UNSAFE_APK" --report "$ARTIFACT_DIR/artifact-failed.properties"
assert_report_contains "$ARTIFACT_DIR/artifact-failed.properties" "status=failed"
assert_release_verifier_report_schema "$ARTIFACT_DIR/artifact-failed.properties" "AndroidArtifactScanReport/v1"
assert_report_contains_text "$ARTIFACT_DIR/artifact-failed.properties" "forbidden-artifact-file"
assert_report_contains_text "$ARTIFACT_DIR/artifact-failed.properties" "sensitive-string"
if grep -q "$ARTIFACT_SCAN_SECRET" <<<"$LAST_OUTPUT"; then
  fail "artifact scan stderr must redact raw secret values"
fi
if grep -q "$ARTIFACT_SCAN_SECRET" "$ARTIFACT_DIR/artifact-failed.properties"; then
  fail "artifact scan report must not contain raw secret values"
fi
expect_failure \
  "artifact scan rejects unreadable aab" \
  scripts/scan_android_artifacts.sh --aab "$BAD_AAB" --report "$ARTIFACT_DIR/artifact-bad-aab.properties"
assert_report_contains "$ARTIFACT_DIR/artifact-bad-aab.properties" "status=failed"
assert_report_contains_text "$ARTIFACT_DIR/artifact-bad-aab.properties" "artifact-not-readable-zip"
expect_failure \
  "artifact scan require-signed rejects unsigned zip" \
  scripts/scan_android_artifacts.sh --apk "$SAFE_APK" --require-signed --report "$ARTIFACT_DIR/artifact-unsigned.properties"
assert_report_contains "$ARTIFACT_DIR/artifact-unsigned.properties" "status=failed"
assert_report_contains_text "$ARTIFACT_DIR/artifact-unsigned.properties" "signing-status"
expect_failure \
  "artifact scan require-signed rejects unsigned aab" \
  scripts/scan_android_artifacts.sh --aab "$SAFE_AAB" --require-signed --report "$ARTIFACT_DIR/artifact-unsigned-aab.properties"
assert_report_contains "$ARTIFACT_DIR/artifact-unsigned-aab.properties" "status=failed"
assert_report_contains_text "$ARTIFACT_DIR/artifact-unsigned-aab.properties" "signing-status"
DEBUG_SCAN_KEYSTORE="$TMP_DIR/debug-scan.keystore"
DEBUG_SIGNED_AAB="$TMP_DIR/debug-signed.aab"
cp "$SAFE_AAB" "$DEBUG_SIGNED_AAB"
keytool -genkeypair \
  -keystore "$DEBUG_SCAN_KEYSTORE" \
  -storepass android \
  -keypass android \
  -alias androiddebugkey \
  -dname "CN=Android Debug,O=Android,C=US" \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 >/dev/null
jarsigner \
  -keystore "$DEBUG_SCAN_KEYSTORE" \
  -storepass android \
  -keypass android \
  "$DEBUG_SIGNED_AAB" \
  androiddebugkey >/dev/null
DEBUG_SIGNED_AAB_CERT_SHA="$(
  keytool -printcert -jarfile "$DEBUG_SIGNED_AAB" 2>/dev/null |
    awk -F': ' '/SHA256:/ {gsub(":", "", $2); print tolower($2); exit}'
)"
expect_failure \
  "artifact scan require-signed rejects debug certificate" \
  scripts/scan_android_artifacts.sh --aab "$DEBUG_SIGNED_AAB" --require-signed --report "$ARTIFACT_DIR/artifact-debug-cert.properties"
assert_report_contains "$ARTIFACT_DIR/artifact-debug-cert.properties" "status=failed"
assert_report_contains_text "$ARTIFACT_DIR/artifact-debug-cert.properties" "debug-certificate"
expect_success \
  "artifact scan allows debug certificate only for smoke" \
  scripts/scan_android_artifacts.sh --aab "$DEBUG_SIGNED_AAB" --require-signed --allow-debug-certificate --report "$ARTIFACT_DIR/artifact-debug-cert-smoke.properties"
assert_report_contains "$ARTIFACT_DIR/artifact-debug-cert-smoke.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/artifact-debug-cert-smoke.properties" "allowDebugCertificate=1"
expect_failure \
  "artifact scan rejects unexpected signing certificate" \
  scripts/scan_android_artifacts.sh --aab "$DEBUG_SIGNED_AAB" --require-signed --allow-debug-certificate --expected-certificate-sha256 0000000000000000000000000000000000000000000000000000000000000000 --report "$ARTIFACT_DIR/artifact-cert-mismatch.properties"
assert_report_contains "$ARTIFACT_DIR/artifact-cert-mismatch.properties" "status=failed"
assert_report_contains_text "$ARTIFACT_DIR/artifact-cert-mismatch.properties" "certificate-sha-mismatch"
expect_success \
  "artifact scan accepts expected signing certificate" \
  scripts/scan_android_artifacts.sh --aab "$DEBUG_SIGNED_AAB" --require-signed --allow-debug-certificate --expected-certificate-sha256 "$DEBUG_SIGNED_AAB_CERT_SHA" --report "$ARTIFACT_DIR/artifact-cert-match.properties"
assert_report_contains "$ARTIFACT_DIR/artifact-cert-match.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/artifact-cert-match.properties" "expectedCertificateSha256=$DEBUG_SIGNED_AAB_CERT_SHA"

VALID_GATE_PERF="$TMP_DIR/perf-baseline-safe-apk.properties"
SAFE_APK_SHA="$(shasum -a 256 "$SAFE_APK" | awk '{print $1}')"
SAFE_AAB_SHA="$(shasum -a 256 "$SAFE_AAB" | awk '{print $1}')"
cat > "$VALID_GATE_PERF" <<VALID_GATE_PERF_BASELINE
status=passed
deviceSerial=device-a
deviceModel=Pixel Test
androidApi=36
abi=arm64-v8a
appVersion=0.1.0
releaseArtifactSha256=$SAFE_APK_SHA
modelId=chat-e2b
backend=GPU
firstLaunchInteractiveMs=1200
modelLoadMs=3500
firstTokenMs=900
tokensPerSecond=12.5
stopGenerationRecoveryMs=200
gpuFallbackStatus=not-needed
visionInputMs=500
memorySearch5kMs=25
memoryPeakMb=512
oomOrAnrObserved=false
recordedAt=$PERF_RECORDED_AT
VALID_GATE_PERF_BASELINE
VALID_GATE_AAB_PERF="$TMP_DIR/perf-baseline-safe-aab.properties"
sed "s/releaseArtifactSha256=$SAFE_APK_SHA/releaseArtifactSha256=$SAFE_AAB_SHA/" "$VALID_GATE_PERF" > "$VALID_GATE_AAB_PERF"
PRIVACY_GATE_TARGET="$TMP_DIR/privacy-gate-scan-target"
mkdir -p "$PRIVACY_GATE_TARGET"
PRIVACY_GATE_SECRET="$PRIVACY_GATE_TARGET/privacy-scan-gate-secret.tmp"
printf 'token=sk-%s\n' "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" > "$PRIVACY_GATE_SECRET"
expect_success \
  "release gate passed report has evidence schema" \
  env ARTIFACT_DIR="$ARTIFACT_DIR/release-gate-passed-schema" \
  RELEASE_APK="$TMP_DIR/missing.apk" \
  RELEASE_AAB="$TMP_DIR/missing.aab" \
  VERIFY_CONTRACT_TESTS=0 \
  VERIFY_AI_BEHAVIOR_EVAL=0 \
  VERIFY_PERF_BASELINE=0 \
  scripts/verify_release_gate.sh
assert_release_gate_report_schema "$ARTIFACT_DIR/release-gate-passed-schema/release-gate.properties"
assert_report_contains "$ARTIFACT_DIR/release-gate-passed-schema/release-gate.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/release-gate-passed-schema/release-gate.properties" "failedTarget="
assert_report_contains "$ARTIFACT_DIR/release-gate-passed-schema/release-gate.properties" "failedReason="
assert_report_contains "$ARTIFACT_DIR/release-gate-passed-schema/release-gate.properties" "reason=approved"
assert_release_gate_child_report_bound \
  "$ARTIFACT_DIR/release-gate-passed-schema/release-gate.properties" \
  "privacyScan" \
  "$ARTIFACT_DIR/release-gate-passed-schema/privacy-scan.properties" \
  "passed"
assert_release_gate_child_report_bound \
  "$ARTIFACT_DIR/release-gate-passed-schema/release-gate.properties" \
  "aiBehaviorEval" \
  "$ARTIFACT_DIR/release-gate-passed-schema/ai-behavior-eval.properties" \
  "skipped"
assert_release_gate_child_report_bound \
  "$ARTIFACT_DIR/release-gate-passed-schema/release-gate.properties" \
  "androidArtifactScan" \
  "$ARTIFACT_DIR/release-gate-passed-schema/android-artifact-scan.properties" \
  "skipped"
assert_release_gate_child_report_bound \
  "$ARTIFACT_DIR/release-gate-passed-schema/release-gate.properties" \
  "perfBaseline" \
  "$ARTIFACT_DIR/release-gate-passed-schema/perf-baseline-verification.properties" \
  "skipped"
expect_failure \
  "release gate reports privacy scan child reason" \
  env ARTIFACT_DIR="$ARTIFACT_DIR/release-privacy-scan-failed" \
  EXTRA_PRIVACY_SCAN_TARGETS="$PRIVACY_GATE_TARGET" \
  PERF_BASELINE_FILE="$VALID_GATE_PERF" \
  RELEASE_APK="$SAFE_APK" \
  RELEASE_AAB="$TMP_DIR/missing.aab" \
  VERIFY_CONTRACT_TESTS=0 \
  scripts/verify_release_gate.sh
assert_report_contains "$ARTIFACT_DIR/release-privacy-scan-failed/privacy-scan.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/release-privacy-scan-failed/privacy-scan.properties" "reason=secret-pattern-detected"
assert_report_contains "$ARTIFACT_DIR/release-privacy-scan-failed/release-gate.properties" "failedTarget=privacy-scan"
assert_report_contains "$ARTIFACT_DIR/release-privacy-scan-failed/release-gate.properties" "failedReason=secret-pattern-detected"
assert_release_gate_report_schema "$ARTIFACT_DIR/release-privacy-scan-failed/release-gate.properties"
assert_release_gate_child_report_bound \
  "$ARTIFACT_DIR/release-privacy-scan-failed/release-gate.properties" \
  "privacyScan" \
  "$ARTIFACT_DIR/release-privacy-scan-failed/privacy-scan.properties" \
  "failed"
expect_failure \
  "release gate rejects option-like extra privacy scan target" \
  env ARTIFACT_DIR="$ARTIFACT_DIR/release-privacy-scan-invalid-extra-target" \
  EXTRA_PRIVACY_SCAN_TARGETS="--report:$TMP_DIR/redirected-privacy-scan.properties" \
  PERF_BASELINE_FILE="$VALID_GATE_PERF" \
  RELEASE_APK="$SAFE_APK" \
  RELEASE_AAB="$TMP_DIR/missing.aab" \
  VERIFY_CONTRACT_TESTS=0 \
  scripts/verify_release_gate.sh
assert_report_contains "$ARTIFACT_DIR/release-privacy-scan-invalid-extra-target/privacy-scan.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/release-privacy-scan-invalid-extra-target/privacy-scan.properties" "reason=invalid-extra-privacy-scan-target"
assert_report_contains "$ARTIFACT_DIR/release-privacy-scan-invalid-extra-target/release-gate.properties" "failedTarget=privacy-scan"
assert_report_contains "$ARTIFACT_DIR/release-privacy-scan-invalid-extra-target/release-gate.properties" "failedReason=invalid-extra-privacy-scan-target"
expect_failure \
  "release gate reports missing perf baseline in gate summary" \
  env ARTIFACT_DIR="$ARTIFACT_DIR/release-missing-perf" \
  RELEASE_APK="$SAFE_APK" \
  RELEASE_AAB="$TMP_DIR/missing.aab" \
  VERIFY_CONTRACT_TESTS=0 \
  scripts/verify_release_gate.sh
assert_report_contains "$ARTIFACT_DIR/release-missing-perf/release-gate.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/release-missing-perf/release-gate.properties" "verifyPerfBaseline=1"
assert_report_contains "$ARTIFACT_DIR/release-missing-perf/release-gate.properties" "failedTarget=perf-baseline"
assert_report_contains "$ARTIFACT_DIR/release-missing-perf/release-gate.properties" "failedReason=PERF_BASELINE_FILE-not-set"
assert_release_gate_report_schema "$ARTIFACT_DIR/release-missing-perf/release-gate.properties"
assert_release_gate_child_report_bound \
  "$ARTIFACT_DIR/release-missing-perf/release-gate.properties" \
  "perfBaseline" \
  "$ARTIFACT_DIR/release-missing-perf/perf-baseline-verification.properties" \
  "failed"
assert_release_gate_child_report_bound \
  "$ARTIFACT_DIR/release-missing-perf/release-gate.properties" \
  "aiBehaviorEval" \
  "$ARTIFACT_DIR/release-missing-perf/ai-behavior-eval.properties" \
  "passed"
assert_report_contains "$ARTIFACT_DIR/release-missing-perf/ai-behavior-eval.properties" \
  "traceDiffFile=$ARTIFACT_DIR/release-missing-perf/ai-behavior-planning-trace-diff.jsonl"
[[ -s "$ARTIFACT_DIR/release-missing-perf/ai-behavior-planning-trace-diff.jsonl" ]] ||
  fail "release gate must preserve AI behavior planning trace diff"
expect_failure \
  "release gate fails closed when required AI behavior actual trace is missing" \
  env ARTIFACT_DIR="$ARTIFACT_DIR/release-ai-behavior-actual-required" \
  PERF_BASELINE_FILE="$VALID_GATE_PERF" \
  RELEASE_APK="$SAFE_APK" \
  RELEASE_AAB="$TMP_DIR/missing.aab" \
  VERIFY_CONTRACT_TESTS=0 \
  REQUIRE_AI_BEHAVIOR_ACTUAL_TRACE=1 \
  scripts/verify_release_gate.sh
assert_report_contains "$ARTIFACT_DIR/release-ai-behavior-actual-required/ai-behavior-eval.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/release-ai-behavior-actual-required/ai-behavior-eval.properties" "reason=actual-trace-file-missing"
assert_report_contains "$ARTIFACT_DIR/release-ai-behavior-actual-required/release-gate.properties" "failedTarget=ai-behavior-eval"
assert_report_contains "$ARTIFACT_DIR/release-ai-behavior-actual-required/release-gate.properties" "failedReason=actual-trace-file-missing"
assert_report_contains "$ARTIFACT_DIR/release-ai-behavior-actual-required/release-gate.properties" "requireAiBehaviorActualTrace=1"
expect_failure \
  "release gate fails closed when required AI behavior runtime trace source is missing" \
  env ARTIFACT_DIR="$ARTIFACT_DIR/release-ai-behavior-runtime-source-required" \
  PERF_BASELINE_FILE="$VALID_GATE_PERF" \
  RELEASE_APK="$SAFE_APK" \
  RELEASE_AAB="$TMP_DIR/missing.aab" \
  VERIFY_CONTRACT_TESTS=0 \
  AI_BEHAVIOR_ACTUAL_TRACE_FILE="$AI_ACTUAL_TRACE_MISSING_SOURCE" \
  REQUIRE_AI_BEHAVIOR_ACTUAL_TRACE=1 \
  REQUIRE_AI_BEHAVIOR_RUNTIME_TRACE_SOURCE=1 \
  scripts/verify_release_gate.sh
assert_report_contains "$ARTIFACT_DIR/release-ai-behavior-runtime-source-required/ai-behavior-eval.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/release-ai-behavior-runtime-source-required/ai-behavior-eval.properties" "reason=invalid-actual-trace:1:traceSource"
assert_report_contains "$ARTIFACT_DIR/release-ai-behavior-runtime-source-required/release-gate.properties" "failedTarget=ai-behavior-eval"
assert_report_contains "$ARTIFACT_DIR/release-ai-behavior-runtime-source-required/release-gate.properties" "requireAiBehaviorRuntimeTraceSource=1"
expect_failure \
  "release gate can skip perf baseline for non-public owner evidence checks" \
  env ARTIFACT_DIR="$ARTIFACT_DIR/release-store-policy-without-perf" \
  RELEASE_APK="$SAFE_APK" \
  RELEASE_AAB="$TMP_DIR/missing.aab" \
  VERIFY_CONTRACT_TESTS=0 \
  VERIFY_PERF_BASELINE=0 \
  VERIFY_STORE_POLICY=1 \
  scripts/verify_release_gate.sh
assert_report_contains "$ARTIFACT_DIR/release-store-policy-without-perf/perf-baseline-verification.properties" "status=skipped"
assert_report_contains "$ARTIFACT_DIR/release-store-policy-without-perf/perf-baseline-verification.properties" "reason=VERIFY_PERF_BASELINE-not-enabled"
assert_report_contains "$ARTIFACT_DIR/release-store-policy-without-perf/release-gate.properties" "verifyPerfBaseline=0"
assert_report_contains "$ARTIFACT_DIR/release-store-policy-without-perf/release-gate.properties" "failedTarget=store-policy-record"
VALID_GATE_BAD_SHA_PERF="$TMP_DIR/perf-baseline-safe-apk-bad-sha.properties"
sed 's/releaseArtifactSha256='"$SAFE_APK_SHA"'/releaseArtifactSha256=0000000000000000000000000000000000000000000000000000000000000000/' "$VALID_GATE_PERF" > "$VALID_GATE_BAD_SHA_PERF"
expect_failure \
  "release gate reports perf baseline child reason" \
  env ARTIFACT_DIR="$ARTIFACT_DIR/release-perf-bad-sha" \
  PERF_BASELINE_FILE="$VALID_GATE_BAD_SHA_PERF" \
  RELEASE_APK="$SAFE_APK" \
  RELEASE_AAB="$TMP_DIR/missing.aab" \
  VERIFY_CONTRACT_TESTS=0 \
  scripts/verify_release_gate.sh
assert_report_contains "$ARTIFACT_DIR/release-perf-bad-sha/perf-baseline-verification.properties" "status=failed"
assert_report_contains_text "$ARTIFACT_DIR/release-perf-bad-sha/perf-baseline-verification.properties" "release-artifact-sha-mismatch"
assert_report_contains "$ARTIFACT_DIR/release-perf-bad-sha/release-gate.properties" "failedTarget=perf-baseline"
assert_report_contains_text "$ARTIFACT_DIR/release-perf-bad-sha/release-gate.properties" "failedReason=release-artifact-sha-mismatch"
expect_failure \
  "release gate requires approved privacy review when enabled" \
  env ARTIFACT_DIR="$ARTIFACT_DIR/release-privacy-review" \
  PERF_BASELINE_FILE="$VALID_GATE_PERF" \
  RELEASE_APK="$SAFE_APK" \
  RELEASE_AAB="$TMP_DIR/missing.aab" \
  VERIFY_PRIVACY_REVIEW=1 \
  VERIFY_CONTRACT_TESTS=0 \
  scripts/verify_release_gate.sh
assert_report_contains "$ARTIFACT_DIR/release-privacy-review/privacy-review.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/release-privacy-review/release-gate.properties" "failedTarget=privacy-review"
expect_failure \
  "release gate requires approved model license review when enabled" \
  env ARTIFACT_DIR="$ARTIFACT_DIR/release-model-license" \
  PERF_BASELINE_FILE="$VALID_GATE_PERF" \
  RELEASE_APK="$SAFE_APK" \
  RELEASE_AAB="$TMP_DIR/missing.aab" \
  VERIFY_MODEL_LICENSES=1 \
  VERIFY_CONTRACT_TESTS=0 \
  scripts/verify_release_gate.sh
assert_report_contains "$ARTIFACT_DIR/release-model-license/model-license-review.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/release-model-license/release-gate.properties" "failedTarget=model-license-review"
expect_failure \
  "public release profile requires expected signing certificate" \
  env ARTIFACT_DIR="$ARTIFACT_DIR/public-release-missing-cert" \
  PERF_BASELINE_FILE="$VALID_GATE_PERF" \
  RELEASE_APK="$SAFE_APK" \
  RELEASE_AAB="$TMP_DIR/missing.aab" \
  PUBLIC_RELEASE=1 \
  VERIFY_PERF_BASELINE=0 \
  REQUIRE_AI_BEHAVIOR_ACTUAL_TRACE=0 \
  REQUIRE_AI_BEHAVIOR_RUNTIME_TRACE_SOURCE=0 \
  VERIFY_CONTRACT_TESTS=0 \
  scripts/verify_release_gate.sh
assert_report_contains "$ARTIFACT_DIR/public-release-missing-cert/release-gate.properties" "publicRelease=1"
assert_report_contains "$ARTIFACT_DIR/public-release-missing-cert/release-gate.properties" "verifyReleaseRecord=1"
assert_report_contains "$ARTIFACT_DIR/public-release-missing-cert/release-gate.properties" "verifyStorePolicy=1"
assert_report_contains "$ARTIFACT_DIR/public-release-missing-cert/release-gate.properties" "verifyReleaseOperations=1"
assert_report_contains "$ARTIFACT_DIR/public-release-missing-cert/release-gate.properties" "verifyReleaseValidation=1"
assert_report_contains "$ARTIFACT_DIR/public-release-missing-cert/release-gate.properties" "verifyPrivacyReview=1"
assert_report_contains "$ARTIFACT_DIR/public-release-missing-cert/release-gate.properties" "verifyModelLicenses=1"
assert_report_contains "$ARTIFACT_DIR/public-release-missing-cert/release-gate.properties" "verifyPerfBaseline=1"
assert_report_contains "$ARTIFACT_DIR/public-release-missing-cert/release-gate.properties" "requireAiBehaviorActualTrace=1"
assert_report_contains "$ARTIFACT_DIR/public-release-missing-cert/release-gate.properties" "requireAiBehaviorRuntimeTraceSource=1"
assert_report_contains "$ARTIFACT_DIR/public-release-missing-cert/release-gate.properties" "requireAab=1"
assert_report_contains "$ARTIFACT_DIR/public-release-missing-cert/release-gate.properties" "requireSignedArtifact=1"
assert_report_contains "$ARTIFACT_DIR/public-release-missing-cert/release-gate.properties" "verifyReleaseMapping=1"
assert_report_contains "$ARTIFACT_DIR/public-release-missing-cert/release-gate.properties" "failedTarget=signing-cert"
assert_report_contains "$ARTIFACT_DIR/public-release-missing-cert/release-gate.properties" "failedReason=PUBLIC_RELEASE-EXPECTED_SIGNING_CERT_SHA256-not-set"
assert_release_gate_report_schema "$ARTIFACT_DIR/public-release-missing-cert/release-gate.properties"
assert_release_gate_child_report_bound \
  "$ARTIFACT_DIR/public-release-missing-cert/release-gate.properties" \
  "signingCert" \
  "$ARTIFACT_DIR/public-release-missing-cert/signing-cert.properties" \
  "failed"
assert_release_gate_children_not_produced \
  "$ARTIFACT_DIR/public-release-missing-cert/release-gate.properties" \
  "$ARTIFACT_DIR/public-release-missing-cert" \
  "privacyScan:privacy-scan.properties" \
  "contractTests:contract-tests.properties" \
  "aiBehaviorEval:ai-behavior-eval.properties" \
  "androidArtifactScan:android-artifact-scan.properties" \
  "perfBaseline:perf-baseline-verification.properties" \
  "releaseMapping:release-mapping.properties" \
  "releaseRecord:release-record.properties" \
  "storePolicyRecord:store-policy-record.properties" \
  "releaseOperationsRecord:release-operations-record.properties" \
  "releaseValidationRecord:release-validation-record.properties" \
  "modelLicenseReview:model-license-review.properties" \
  "privacyReview:privacy-review.properties"
assert_report_contains "$ARTIFACT_DIR/public-release-missing-cert/signing-cert.properties" "status=failed"

expect_failure \
  "release gate requires aab when public gate requests it" \
  env ARTIFACT_DIR="$ARTIFACT_DIR/release-require-aab" \
  PERF_BASELINE_FILE="$VALID_PERF" \
  RELEASE_APK="$SAFE_APK" \
  RELEASE_AAB="$TMP_DIR/missing.aab" \
  REQUIRE_AAB=1 \
  VERIFY_CONTRACT_TESTS=0 \
  scripts/verify_release_gate.sh
assert_report_contains "$ARTIFACT_DIR/release-require-aab/android-artifact-scan.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/release-require-aab/release-gate.properties" "failedTarget=android-artifact-scan"
rm -f app/build/outputs/bundle/release/app-release-signed.aab
expect_failure \
  "release gate defaults signed aab path when signed aab is required" \
  env ARTIFACT_DIR="$ARTIFACT_DIR/release-signed-default-aab" \
  PERF_BASELINE_FILE="$VALID_GATE_PERF" \
  RELEASE_APK="$SAFE_APK" \
  REQUIRE_AAB=1 \
  REQUIRE_SIGNED_ARTIFACT=1 \
  EXPECTED_SIGNING_CERT_SHA256=1111111111111111111111111111111111111111111111111111111111111111 \
  VERIFY_CONTRACT_TESTS=0 \
  scripts/verify_release_gate.sh
assert_report_contains "$ARTIFACT_DIR/release-signed-default-aab/android-artifact-scan.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/release-signed-default-aab/android-artifact-scan.properties" "releaseAab=app/build/outputs/bundle/release/app-release-signed.aab"
assert_report_contains "$ARTIFACT_DIR/release-signed-default-aab/release-gate.properties" "releaseAab=app/build/outputs/bundle/release/app-release-signed.aab"
assert_report_contains "$ARTIFACT_DIR/release-signed-default-aab/release-gate.properties" "failedTarget=android-artifact-scan"
assert_report_contains "$ARTIFACT_DIR/release-signed-default-aab/release-gate.properties" "failedReason=REQUIRE_AAB-but-release-aab-missing"
expect_failure \
  "release gate binds release record to scanned artifact in non-public mode" \
  env ARTIFACT_DIR="$ARTIFACT_DIR/release-record-artifact-mismatch" \
  PERF_BASELINE_FILE="$VALID_GATE_AAB_PERF" \
  RELEASE_APK="$SAFE_APK" \
  RELEASE_AAB="$SAFE_AAB" \
  VERIFY_RELEASE_RECORD=1 \
  RELEASE_RECORD_FILE="$RELEASE_RECORD_APPROVED" \
  VERIFY_CONTRACT_TESTS=0 \
  scripts/verify_release_gate.sh
assert_report_contains "$ARTIFACT_DIR/release-record-artifact-mismatch/release-record.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/release-record-artifact-mismatch/release-record.properties" "expectedReleaseArtifactPath=$SAFE_AAB"
assert_report_contains "$ARTIFACT_DIR/release-record-artifact-mismatch/release-gate.properties" "failedTarget=release-record"
expect_failure \
  "release gate requires mapping when mapping gate is enabled" \
  env ARTIFACT_DIR="$ARTIFACT_DIR/release-mapping-gate" \
  PERF_BASELINE_FILE="$VALID_GATE_PERF" \
  RELEASE_APK="$SAFE_APK" \
  RELEASE_AAB="$TMP_DIR/missing.aab" \
  VERIFY_RELEASE_MAPPING=1 \
  RELEASE_MAPPING_FILE="$TMP_DIR/missing-mapping.txt" \
  VERIFY_CONTRACT_TESTS=0 \
  scripts/verify_release_gate.sh
assert_report_contains "$ARTIFACT_DIR/release-mapping-gate/release-mapping.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/release-mapping-gate/release-gate.properties" "failedTarget=release-mapping"
expect_failure \
  "release gate requires approved release record when enabled" \
  env ARTIFACT_DIR="$ARTIFACT_DIR/release-record-gate" \
  PERF_BASELINE_FILE="$VALID_GATE_PERF" \
  RELEASE_APK="$SAFE_APK" \
  RELEASE_AAB="$TMP_DIR/missing.aab" \
  VERIFY_RELEASE_RECORD=1 \
  RELEASE_RECORD_FILE="$RELEASE_RECORD_PENDING" \
  VERIFY_CONTRACT_TESTS=0 \
  scripts/verify_release_gate.sh
assert_report_contains "$ARTIFACT_DIR/release-record-gate/release-record.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/release-record-gate/release-gate.properties" "failedTarget=release-record"
expect_failure \
  "release gate requires approved store policy when enabled" \
  env ARTIFACT_DIR="$ARTIFACT_DIR/release-store-policy-gate" \
  PERF_BASELINE_FILE="$VALID_GATE_PERF" \
  RELEASE_APK="$SAFE_APK" \
  RELEASE_AAB="$TMP_DIR/missing.aab" \
  VERIFY_STORE_POLICY=1 \
  STORE_POLICY_FILE="$STORE_POLICY_PENDING" \
  PRIVACY_NOTICE_FILE="$STORE_POLICY_NOTICE" \
  MANIFEST_FILE="$STORE_POLICY_MANIFEST" \
  VERIFY_CONTRACT_TESTS=0 \
  scripts/verify_release_gate.sh
assert_report_contains "$ARTIFACT_DIR/release-store-policy-gate/store-policy-record.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/release-store-policy-gate/release-gate.properties" "failedTarget=store-policy-record"
expect_failure \
  "release gate requires approved operations record when enabled" \
  env ARTIFACT_DIR="$ARTIFACT_DIR/release-operations-gate" \
  PERF_BASELINE_FILE="$VALID_GATE_PERF" \
  RELEASE_APK="$SAFE_APK" \
  RELEASE_AAB="$TMP_DIR/missing.aab" \
  VERIFY_RELEASE_OPERATIONS=1 \
  OPERATIONS_RECORD_FILE="$OPERATIONS_PENDING" \
  VERIFY_CONTRACT_TESTS=0 \
  scripts/verify_release_gate.sh
assert_report_contains "$ARTIFACT_DIR/release-operations-gate/release-operations-record.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/release-operations-gate/release-gate.properties" "failedTarget=release-operations-record"
expect_failure \
  "release gate requires approved validation record when enabled" \
  env ARTIFACT_DIR="$ARTIFACT_DIR/release-validation-gate" \
  PERF_BASELINE_FILE="$VALID_GATE_PERF" \
  RELEASE_APK="$SAFE_APK" \
  RELEASE_AAB="$TMP_DIR/missing.aab" \
  VERIFY_RELEASE_VALIDATION=1 \
  VALIDATION_RECORD_FILE="$VALIDATION_PENDING" \
  VERIFY_CONTRACT_TESTS=0 \
  scripts/verify_release_gate.sh
assert_report_contains "$ARTIFACT_DIR/release-validation-gate/release-validation-record.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/release-validation-gate/release-gate.properties" "failedTarget=release-validation-record"
expect_failure \
  "signing helper requires private keystore environment" \
  env REPORT_FILE="$ARTIFACT_DIR/signing-missing-env.properties" \
  scripts/sign_release_artifacts.sh
assert_report_contains "$ARTIFACT_DIR/signing-missing-env.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/signing-missing-env.properties" "target=release-signing"
assert_release_verifier_report_schema \
  "$ARTIFACT_DIR/signing-missing-env.properties" \
  "ReleaseSigningReport/v1"
assert_report_contains "$ARTIFACT_DIR/signing-missing-env.properties" "failedTarget=environment"
assert_report_contains "$ARTIFACT_DIR/signing-missing-env.properties" "reason=missing-release-keystore"
expect_failure \
  "signing helper reports malformed arguments when report path is known" \
  env REPORT_FILE="$ARTIFACT_DIR/signing-malformed-argument.properties" \
  scripts/sign_release_artifacts.sh --bad-arg
assert_release_verifier_report_schema \
  "$ARTIFACT_DIR/signing-malformed-argument.properties" \
  "ReleaseSigningReport/v1"
assert_report_contains "$ARTIFACT_DIR/signing-malformed-argument.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/signing-malformed-argument.properties" "failedTarget=argument-parser"
assert_report_contains "$ARTIFACT_DIR/signing-malformed-argument.properties" "reason=unknown-argument"
SIGNING_CLI_MALFORMED_REPORT="$ARTIFACT_DIR/signing-cli-malformed-argument.properties"
expect_failure \
  "signing helper reports malformed CLI arguments when report path is known" \
  scripts/sign_release_artifacts.sh --report "$SIGNING_CLI_MALFORMED_REPORT" --bad-arg
assert_release_verifier_report_schema \
  "$SIGNING_CLI_MALFORMED_REPORT" \
  "ReleaseSigningReport/v1"
assert_report_contains "$SIGNING_CLI_MALFORMED_REPORT" "status=failed"
assert_report_contains "$SIGNING_CLI_MALFORMED_REPORT" "failedTarget=argument-parser"
assert_report_contains "$SIGNING_CLI_MALFORMED_REPORT" "reason=unknown-argument"
SIGNING_CLI_MISSING_VALUE_REPORT="$ARTIFACT_DIR/signing-cli-missing-value.properties"
expect_failure \
  "signing helper reports option-like missing CLI report value" \
  scripts/sign_release_artifacts.sh --report "$SIGNING_CLI_MISSING_VALUE_REPORT" --report --bad-arg
assert_release_verifier_report_schema \
  "$SIGNING_CLI_MISSING_VALUE_REPORT" \
  "ReleaseSigningReport/v1"
assert_report_contains "$SIGNING_CLI_MISSING_VALUE_REPORT" "status=failed"
assert_report_contains "$SIGNING_CLI_MISSING_VALUE_REPORT" "failedTarget=argument-parser"
assert_report_contains "$SIGNING_CLI_MISSING_VALUE_REPORT" "reason=missing-report-argument"
DEBUG_KEYSTORE="$TMP_DIR/debug.keystore"
printf 'not-a-real-keystore\n' > "$DEBUG_KEYSTORE"
expect_failure \
  "signing helper rejects debug keystore by default" \
  env RELEASE_KEYSTORE="$DEBUG_KEYSTORE" \
  RELEASE_KEY_ALIAS=androiddebugkey \
  RELEASE_KEYSTORE_PASSWORD=android \
  RELEASE_KEY_PASSWORD=android \
  REPORT_FILE="$ARTIFACT_DIR/signing-debug-keystore.properties" \
  scripts/sign_release_artifacts.sh
assert_report_contains "$ARTIFACT_DIR/signing-debug-keystore.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/signing-debug-keystore.properties" "failedTarget=keystore"
assert_report_contains "$ARTIFACT_DIR/signing-debug-keystore.properties" "reason=debug-keystore-not-allowed"
grep -q 'Refusing Android debug keystore' <<<"$LAST_OUTPUT" ||
  fail "Expected signing helper to refuse debug keystore before signing"
PRODUCTION_KEYSTORE="$TMP_DIR/production-upload.keystore"
printf 'not-a-real-keystore\n' > "$PRODUCTION_KEYSTORE"
expect_failure \
  "signing helper requires expected production certificate" \
  env RELEASE_KEYSTORE="$PRODUCTION_KEYSTORE" \
  RELEASE_KEY_ALIAS=upload \
  RELEASE_KEYSTORE_PASSWORD=secret \
  RELEASE_KEY_PASSWORD=secret \
  REPORT_FILE="$ARTIFACT_DIR/signing-missing-cert.properties" \
  scripts/sign_release_artifacts.sh
assert_report_contains "$ARTIFACT_DIR/signing-missing-cert.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/signing-missing-cert.properties" "failedTarget=signing-policy"
assert_report_contains "$ARTIFACT_DIR/signing-missing-cert.properties" "reason=expected-signing-cert-sha256-missing"
grep -q 'Production release signing requires EXPECTED_SIGNING_CERT_SHA256' <<<"$LAST_OUTPUT" ||
  fail "Expected signing helper to require expected production certificate before signing"
expect_failure \
  "signing helper requires unsigned aab for production signing" \
  env RELEASE_KEYSTORE="$PRODUCTION_KEYSTORE" \
  RELEASE_KEY_ALIAS=upload \
  RELEASE_KEYSTORE_PASSWORD=secret \
  RELEASE_KEY_PASSWORD=secret \
  EXPECTED_SIGNING_CERT_SHA256=1111111111111111111111111111111111111111111111111111111111111111 \
  UNSIGNED_APK="$SAFE_APK" \
  UNSIGNED_AAB="$TMP_DIR/missing-release.aab" \
  REPORT_FILE="$ARTIFACT_DIR/signing-missing-aab.properties" \
  scripts/sign_release_artifacts.sh
assert_report_contains "$ARTIFACT_DIR/signing-missing-aab.properties" "status=failed"
assert_release_verifier_report_schema \
  "$ARTIFACT_DIR/signing-missing-aab.properties" \
  "ReleaseSigningReport/v1"
assert_report_contains "$ARTIFACT_DIR/signing-missing-aab.properties" "unsignedApkSha256=$SAFE_APK_SHA"
assert_report_contains "$ARTIFACT_DIR/signing-missing-aab.properties" "unsignedAabSha256="
assert_report_contains "$ARTIFACT_DIR/signing-missing-aab.properties" "artifactScanReportSha256="
assert_report_contains "$ARTIFACT_DIR/signing-missing-aab.properties" "failedTarget=input-artifact"
assert_report_contains "$ARTIFACT_DIR/signing-missing-aab.properties" "reason=unsigned-aab-missing"
grep -q 'Release signing requires unsigned AAB' <<<"$LAST_OUTPUT" ||
  fail "Expected signing helper to require unsigned AAB before production signing"

COLLECTED_PERF_MISSING_ENV="$ARTIFACT_DIR/collected-perf-missing-env.properties"
expect_failure \
  "perf baseline collector reports missing required env" \
  env ADB="$TMP_DIR/missing-adb" \
  OUT_FILE="$COLLECTED_PERF_MISSING_ENV" \
  APP_VERSION=1.0 \
  MODEL_ID=chat-e2b \
  BACKEND=GPU \
  FIRST_LAUNCH_INTERACTIVE_MS=1200 \
  MODEL_LOAD_MS=3500 \
  FIRST_TOKEN_MS=900 \
  TOKENS_PER_SECOND=12.5 \
  STOP_GENERATION_RECOVERY_MS=200 \
  GPU_FALLBACK_STATUS=not-needed \
  VISION_INPUT_MS=500 \
  MEMORY_SEARCH_5K_MS=25 \
  MEMORY_PEAK_MB=512 \
  OOM_OR_ANR_OBSERVED=false \
  scripts/collect_perf_baseline.sh
assert_report_contains "$COLLECTED_PERF_MISSING_ENV" "status=failed"
assert_report_contains "$COLLECTED_PERF_MISSING_ENV" "artifactSchema=PerfBaselineCollection/v1"
assert_report_contains "$COLLECTED_PERF_MISSING_ENV" "target=perf-baseline-collector"
assert_report_contains "$COLLECTED_PERF_MISSING_ENV" "owner=release-engineering"
grep -q '^command=.*scripts/collect_perf_baseline.sh' "$COLLECTED_PERF_MISSING_ENV" ||
  fail "perf collector failure report must include reproducible command"
assert_report_contains "$COLLECTED_PERF_MISSING_ENV" "reproduciblePath=$COLLECTED_PERF_MISSING_ENV"
assert_report_contains "$COLLECTED_PERF_MISSING_ENV" "failedTarget=environment"
assert_report_contains "$COLLECTED_PERF_MISSING_ENV" "reason=missing-release-artifact"

COLLECTED_PERF_MISSING_ARTIFACT="$ARTIFACT_DIR/collected-perf-missing-artifact.properties"
expect_failure \
  "perf baseline collector reports missing release artifact" \
  env ADB="$TMP_DIR/missing-adb" \
  OUT_FILE="$COLLECTED_PERF_MISSING_ARTIFACT" \
  RELEASE_ARTIFACT="$TMP_DIR/missing-release-artifact.apk" \
  ANDROID_SERIAL=device-a \
  DEVICE_MODEL="Pixel Test" \
  ANDROID_API=36 \
  ABI=arm64-v8a \
  APP_VERSION=1.0 \
  MODEL_ID=chat-e2b \
  BACKEND=GPU \
  FIRST_LAUNCH_INTERACTIVE_MS=1200 \
  MODEL_LOAD_MS=3500 \
  FIRST_TOKEN_MS=900 \
  TOKENS_PER_SECOND=12.5 \
  STOP_GENERATION_RECOVERY_MS=200 \
  GPU_FALLBACK_STATUS=not-needed \
  VISION_INPUT_MS=500 \
  MEMORY_SEARCH_5K_MS=25 \
  MEMORY_PEAK_MB=512 \
  OOM_OR_ANR_OBSERVED=false \
  scripts/collect_perf_baseline.sh
assert_report_contains "$COLLECTED_PERF_MISSING_ARTIFACT" "status=failed"
assert_report_contains "$COLLECTED_PERF_MISSING_ARTIFACT" "failedTarget=input-artifact"
assert_report_contains "$COLLECTED_PERF_MISSING_ARTIFACT" "reason=release-artifact-missing"

COLLECTED_PERF_EMULATOR="$ARTIFACT_DIR/collected-perf-emulator.properties"
expect_failure \
  "perf baseline collector reports verifier rejection reason" \
  env ADB="$TMP_DIR/missing-adb" \
  OUT_FILE="$COLLECTED_PERF_EMULATOR" \
  RELEASE_ARTIFACT="$SAFE_APK" \
  ANDROID_SERIAL=emulator-5554 \
  DEVICE_MODEL="Pixel Test" \
  ANDROID_API=36 \
  ABI=arm64-v8a \
  APP_VERSION=1.0 \
  MODEL_ID=chat-e2b \
  BACKEND=GPU \
  FIRST_LAUNCH_INTERACTIVE_MS=1200 \
  MODEL_LOAD_MS=3500 \
  FIRST_TOKEN_MS=900 \
  TOKENS_PER_SECOND=12.5 \
  STOP_GENERATION_RECOVERY_MS=200 \
  GPU_FALLBACK_STATUS=not-needed \
  VISION_INPUT_MS=500 \
  MEMORY_SEARCH_5K_MS=25 \
  MEMORY_PEAK_MB=512 \
  OOM_OR_ANR_OBSERVED=false \
  scripts/collect_perf_baseline.sh
assert_report_contains "$COLLECTED_PERF_EMULATOR" "status=failed"
assert_report_contains "$COLLECTED_PERF_EMULATOR" "failedTarget=perf-baseline-verification"
assert_report_contains "$COLLECTED_PERF_EMULATOR" "reason=device-serial-is-emulator"
assert_report_contains "$COLLECTED_PERF_EMULATOR" "verificationReport=$COLLECTED_PERF_EMULATOR.verification.properties"
assert_report_contains "$COLLECTED_PERF_EMULATOR" "verificationReason=device-serial-is-emulator"

COLLECTED_PERF="$ARTIFACT_DIR/collected-perf.properties"
expect_success \
  "perf baseline collector writes verifiable record from measured inputs" \
  env ADB="$TMP_DIR/missing-adb" \
  OUT_FILE="$COLLECTED_PERF" \
  RELEASE_ARTIFACT="$SAFE_APK" \
  ANDROID_SERIAL=device-a \
  DEVICE_MODEL="Pixel Test" \
  ANDROID_API=36 \
  ABI=arm64-v8a \
  APP_VERSION=1.0 \
  MODEL_ID=chat-e2b \
  BACKEND=GPU \
  FIRST_LAUNCH_INTERACTIVE_MS=1200 \
  MODEL_LOAD_MS=3500 \
  FIRST_TOKEN_MS=900 \
  TOKENS_PER_SECOND=12.5 \
  STOP_GENERATION_RECOVERY_MS=200 \
  GPU_FALLBACK_STATUS=not-needed \
  VISION_INPUT_MS=500 \
  MEMORY_SEARCH_5K_MS=25 \
  MEMORY_PEAK_MB=512 \
  OOM_OR_ANR_OBSERVED=false \
  scripts/collect_perf_baseline.sh
assert_report_contains "$COLLECTED_PERF" "status=passed"
assert_report_contains "$COLLECTED_PERF" "artifactSchema=PerfBaseline/v1"
assert_report_contains "$COLLECTED_PERF" "target=perf-baseline-record"
assert_report_contains "$COLLECTED_PERF" "owner=release-engineering"
grep -q '^collectionCommand=.*scripts/collect_perf_baseline.sh' "$COLLECTED_PERF" ||
  fail "perf collector success report must include collection command"
assert_report_contains "$COLLECTED_PERF" "reproduciblePath=$COLLECTED_PERF"
assert_report_contains "$COLLECTED_PERF" "releaseArtifact=$SAFE_APK"
assert_report_contains "$COLLECTED_PERF.verification.properties" "artifactSchema=PerfBaselineVerification/v1"
assert_report_contains "$COLLECTED_PERF.verification.properties" "expectedAppVersion=1.0"
assert_report_contains "$COLLECTED_PERF.verification.properties" "baselineSha256=$(shasum -a 256 "$COLLECTED_PERF" | awk '{print $1}')"

expect_success \
  "doctor local without adb" \
  env ANDROID_SDK_ROOT="$NO_ADB_SDK" ANDROID_HOME="$NO_ADB_SDK" \
  scripts/doctor.sh --local

expect_failure \
  "doctor device without adb" \
  env ANDROID_SDK_ROOT="$NO_ADB_SDK" ANDROID_HOME="$NO_ADB_SDK" \
  scripts/doctor.sh --device

reset_logs
expect_failure \
  "real app search eval archives resolver failure evidence" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' ANDROID_SERIAL="device-a" \
  FAKE_REAL_APP_SEARCH_INSTALLED_PACKAGES="com.taobao.taobao" \
  FAKE_REAL_APP_SEARCH_FAIL_STEP="tap" \
  SKIP_BUILD=1 SKIP_INSTALL=1 FORCE_STOP_TARGET_APP=0 \
  REPORT_FILE="$ARTIFACT_DIR/real-app-search-eval.properties" \
  LOGCAT_FILE="$ARTIFACT_DIR/real-app-search-logcat.txt" \
  DIAGNOSTICS_DIR="$ARTIFACT_DIR/real-app-diagnostics" \
  GRADLE_CMD="$FAKE_GRADLE" scripts/run_real_app_search_eval.sh
assert_no_gradle_call
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "failedTarget=real-app-search-case"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "reason=real-app-search-case-failed"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "run_count=1"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "fail_count=1"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "skip_count=7"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "case_artifact_schema=RealAppSearchCaseArtifact/v1"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "cases=taobao,pdd,gaode,jd,chrome,android_browser,quark,uc"
REAL_APP_CASE_REPORT="$ARTIFACT_DIR/taobao.case.properties"
REAL_APP_RANKED_CANDIDATES="$ARTIFACT_DIR/taobao.ranked-candidates.json"
REAL_APP_TARGET_RESOLUTION="$ARTIFACT_DIR/taobao.target-resolution.properties"
assert_report_contains "$REAL_APP_CASE_REPORT" "artifact_schema=RealAppSearchCaseArtifact/v1"
assert_report_contains "$REAL_APP_CASE_REPORT" "case=taobao"
assert_report_contains "$REAL_APP_CASE_REPORT" "expected_package_name=com.taobao.taobao"
assert_report_contains "$REAL_APP_CASE_REPORT" "expected_app_name=淘宝"
assert_report_contains "$REAL_APP_CASE_REPORT" "status=failed"
assert_report_contains "$REAL_APP_CASE_REPORT" "reason=search_entry_not_found"
assert_report_contains "$REAL_APP_CASE_REPORT" "failure_kind=search_entry_not_found"
assert_report_contains "$REAL_APP_CASE_REPORT" "failed_step=tap"
assert_report_contains "$REAL_APP_CASE_REPORT" "result_file=$ARTIFACT_DIR/taobao-tap.properties"
grep -Eq '^result_file_sha256=[0-9a-f]{64}$' "$REAL_APP_CASE_REPORT" ||
  fail "Expected real app case report to hash the debug receiver result file"
assert_report_contains "$REAL_APP_CASE_REPORT" "target_resolution_available=true"
assert_report_contains "$REAL_APP_CASE_REPORT" "target_resolution_kind=search_entry"
assert_report_contains "$REAL_APP_CASE_REPORT" "target_resolution_target=搜索入口"
assert_report_contains "$REAL_APP_CASE_REPORT" "target_resolution_package_name=com.taobao.taobao"
assert_report_contains "$REAL_APP_CASE_REPORT" "target_resolution_failure_kind=search_entry_not_found"
assert_report_contains "$REAL_APP_CASE_REPORT" "target_resolution_candidate_count=2"
assert_report_contains "$REAL_APP_CASE_REPORT" "target_resolution_candidate_total_count=2"
assert_report_contains "$REAL_APP_CASE_REPORT" "target_resolution_archived_candidate_count=2"
assert_report_contains_text "$REAL_APP_CASE_REPORT" 'target_resolution_candidates_json={"candidates"'
assert_report_contains "$REAL_APP_CASE_REPORT" "target_resolution_evidence_file=$REAL_APP_TARGET_RESOLUTION"
grep -Eq '^target_resolution_evidence_sha256=[0-9a-f]{64}$' "$REAL_APP_CASE_REPORT" ||
  fail "Expected real app case report to hash target resolution evidence"
assert_report_contains "$REAL_APP_CASE_REPORT" "ranked_candidates_file=$REAL_APP_RANKED_CANDIDATES"
grep -Eq '^ranked_candidates_sha256=[0-9a-f]{64}$' "$REAL_APP_CASE_REPORT" ||
  fail "Expected real app case report to hash ranked resolver candidates"
assert_report_contains "$REAL_APP_TARGET_RESOLUTION" "artifact_schema=UiTargetResolutionEvidenceArtifact/v1"
assert_report_contains "$REAL_APP_TARGET_RESOLUTION" "case=taobao"
assert_report_contains "$REAL_APP_TARGET_RESOLUTION" "target_resolution_failure_kind=search_entry_not_found"
assert_report_contains "$REAL_APP_TARGET_RESOLUTION" "ranked_candidates_file=$REAL_APP_RANKED_CANDIDATES"
assert_report_contains_text "$REAL_APP_RANKED_CANDIDATES" '"label":"搜索推荐"'
assert_report_contains_text "$REAL_APP_RANKED_CANDIDATES" '"bounds":{"left":0'
assert_report_contains_text "$REAL_APP_RANKED_CANDIDATES" '"clickable":true'
assert_report_contains_text "$REAL_APP_RANKED_CANDIDATES" '"editable":false'
assert_report_contains_text "$REAL_APP_RANKED_CANDIDATES" '"matchedProfileHint":"搜索"'
assert_report_contains_text "$REAL_APP_RANKED_CANDIDATES" '"riskPenalty":360'
assert_report_contains_text "$REAL_APP_RANKED_CANDIDATES" '"noisePenalty":0'
assert_report_contains_text "$REAL_APP_RANKED_CANDIDATES" '"finalScore":430'
assert_report_contains "$REAL_APP_CASE_REPORT" "diagnostics_dir=$ARTIFACT_DIR/real-app-diagnostics/assert-taobao-tap"
assert_report_contains "$REAL_APP_CASE_REPORT" "screenshot_file=$ARTIFACT_DIR/real-app-diagnostics/assert-taobao-tap/screenshot.png"
assert_report_contains "$REAL_APP_CASE_REPORT" "uiautomator_dump_file=$ARTIFACT_DIR/real-app-diagnostics/assert-taobao-tap/uiautomator.xml"
assert_report_contains "$REAL_APP_CASE_REPORT" "focused_window_file=$ARTIFACT_DIR/real-app-diagnostics/assert-taobao-tap/focused-window.txt"
assert_report_contains "$REAL_APP_CASE_REPORT" "window_dump_file=$ARTIFACT_DIR/real-app-diagnostics/assert-taobao-tap/window-dump.txt"
assert_report_contains "$REAL_APP_CASE_REPORT" "case_logcat_file=$ARTIFACT_DIR/real-app-diagnostics/assert-taobao-tap/logcat.txt"
for key in screenshot_sha256 uiautomator_dump_sha256 focused_window_sha256 window_dump_sha256 case_logcat_sha256; do
  grep -Eq "^${key}=[0-9a-f]{64}$" "$REAL_APP_CASE_REPORT" ||
    fail "Expected real app case report to include $key"
done
[[ -s "$ARTIFACT_DIR/real-app-diagnostics/assert-taobao-tap/uiautomator.xml" ]] ||
  fail "Expected real app failure diagnostics to preserve a UIAutomator dump"
[[ -s "$ARTIFACT_DIR/real-app-diagnostics/assert-taobao-tap/screenshot.png" ]] ||
  fail "Expected real app failure diagnostics to preserve a screenshot"
[[ -s "$ARTIFACT_DIR/real-app-diagnostics/assert-taobao-tap/window-dump.txt" ]] ||
  fail "Expected real app failure diagnostics to preserve a window dump"
[[ -s "$ARTIFACT_DIR/real-app-diagnostics/assert-taobao-tap/logcat.txt" ]] ||
  fail "Expected real app failure diagnostics to preserve logcat"

reset_logs
expect_failure \
  "real app search eval archives editable failure evidence" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' ANDROID_SERIAL="device-a" \
  FAKE_REAL_APP_SEARCH_INSTALLED_PACKAGES="com.autonavi.minimap" \
  FAKE_REAL_APP_SEARCH_FAIL_STEP="type" \
  SKIP_BUILD=1 SKIP_INSTALL=1 FORCE_STOP_TARGET_APP=0 \
  REPORT_FILE="$ARTIFACT_DIR/real-app-search-eval.properties" \
  LOGCAT_FILE="$ARTIFACT_DIR/real-app-search-logcat.txt" \
  DIAGNOSTICS_DIR="$ARTIFACT_DIR/real-app-diagnostics" \
  GRADLE_CMD="$FAKE_GRADLE" scripts/run_real_app_search_eval.sh
assert_no_gradle_call
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "reason=real-app-search-case-failed"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "run_count=1"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "fail_count=1"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "skip_count=7"
GAODE_CASE_REPORT="$ARTIFACT_DIR/gaode.case.properties"
GAODE_RANKED_CANDIDATES="$ARTIFACT_DIR/gaode.ranked-candidates.json"
GAODE_TARGET_RESOLUTION="$ARTIFACT_DIR/gaode.target-resolution.properties"
assert_report_contains "$GAODE_CASE_REPORT" "artifact_schema=RealAppSearchCaseArtifact/v1"
assert_report_contains "$GAODE_CASE_REPORT" "case=gaode"
assert_report_contains "$GAODE_CASE_REPORT" "expected_package_name=com.autonavi.minimap"
assert_report_contains "$GAODE_CASE_REPORT" "expected_app_name=高德"
assert_report_contains "$GAODE_CASE_REPORT" "status=failed"
assert_report_contains "$GAODE_CASE_REPORT" "reason=editable_not_found"
assert_report_contains "$GAODE_CASE_REPORT" "failure_kind=editable_not_found"
assert_report_contains "$GAODE_CASE_REPORT" "failed_step=type_text"
assert_report_contains "$GAODE_CASE_REPORT" "result_file=$ARTIFACT_DIR/gaode-type.properties"
grep -Eq '^result_file_sha256=[0-9a-f]{64}$' "$GAODE_CASE_REPORT" ||
  fail "Expected Gaode case report to hash the debug receiver result file"
assert_report_contains "$GAODE_CASE_REPORT" "target_resolution_available=true"
assert_report_contains "$GAODE_CASE_REPORT" "target_resolution_kind=editable_field"
assert_report_contains "$GAODE_CASE_REPORT" "target_resolution_target=搜索输入框"
assert_report_contains "$GAODE_CASE_REPORT" "target_resolution_package_name=com.autonavi.minimap"
assert_report_contains "$GAODE_CASE_REPORT" "target_resolution_failure_kind=editable_not_found"
assert_report_contains "$GAODE_CASE_REPORT" "target_resolution_candidate_count=2"
assert_report_contains "$GAODE_CASE_REPORT" "target_resolution_candidate_total_count=2"
assert_report_contains "$GAODE_CASE_REPORT" "target_resolution_archived_candidate_count=2"
assert_report_contains_text "$GAODE_CASE_REPORT" 'target_resolution_candidates_json={"candidates"'
assert_report_contains "$GAODE_CASE_REPORT" "target_resolution_evidence_file=$GAODE_TARGET_RESOLUTION"
grep -Eq '^target_resolution_evidence_sha256=[0-9a-f]{64}$' "$GAODE_CASE_REPORT" ||
  fail "Expected Gaode case report to hash target resolution evidence"
assert_report_contains "$GAODE_CASE_REPORT" "ranked_candidates_file=$GAODE_RANKED_CANDIDATES"
grep -Eq '^ranked_candidates_sha256=[0-9a-f]{64}$' "$GAODE_CASE_REPORT" ||
  fail "Expected Gaode case report to hash ranked resolver candidates"
assert_report_contains "$GAODE_TARGET_RESOLUTION" "artifact_schema=UiTargetResolutionEvidenceArtifact/v1"
assert_report_contains "$GAODE_TARGET_RESOLUTION" "case=gaode"
assert_report_contains "$GAODE_TARGET_RESOLUTION" "target_resolution_failure_kind=editable_not_found"
assert_report_contains "$GAODE_TARGET_RESOLUTION" "ranked_candidates_file=$GAODE_RANKED_CANDIDATES"
assert_report_contains_text "$GAODE_RANKED_CANDIDATES" '"label":"你要去哪儿 搜地点、公交、地铁"'
assert_report_contains_text "$GAODE_RANKED_CANDIDATES" '"bounds":{"left":40'
assert_report_contains_text "$GAODE_RANKED_CANDIDATES" '"clickable":true'
assert_report_contains_text "$GAODE_RANKED_CANDIDATES" '"editable":false'
assert_report_contains_text "$GAODE_RANKED_CANDIDATES" '"matchedProfileHint":"你要去哪儿"'
assert_report_contains_text "$GAODE_RANKED_CANDIDATES" '"riskPenalty":0'
assert_report_contains_text "$GAODE_RANKED_CANDIDATES" '"noisePenalty":0'
assert_report_contains_text "$GAODE_RANKED_CANDIDATES" '"finalScore":360'
assert_report_contains "$GAODE_CASE_REPORT" "diagnostics_dir=$ARTIFACT_DIR/real-app-diagnostics/assert-gaode-type"
assert_report_contains "$GAODE_CASE_REPORT" "screenshot_file=$ARTIFACT_DIR/real-app-diagnostics/assert-gaode-type/screenshot.png"
assert_report_contains "$GAODE_CASE_REPORT" "uiautomator_dump_file=$ARTIFACT_DIR/real-app-diagnostics/assert-gaode-type/uiautomator.xml"
assert_report_contains "$GAODE_CASE_REPORT" "focused_window_file=$ARTIFACT_DIR/real-app-diagnostics/assert-gaode-type/focused-window.txt"
assert_report_contains "$GAODE_CASE_REPORT" "window_dump_file=$ARTIFACT_DIR/real-app-diagnostics/assert-gaode-type/window-dump.txt"
assert_report_contains "$GAODE_CASE_REPORT" "case_logcat_file=$ARTIFACT_DIR/real-app-diagnostics/assert-gaode-type/logcat.txt"
for key in screenshot_sha256 uiautomator_dump_sha256 focused_window_sha256 window_dump_sha256 case_logcat_sha256; do
  grep -Eq "^${key}=[0-9a-f]{64}$" "$GAODE_CASE_REPORT" ||
    fail "Expected Gaode case report to include $key"
done
[[ -s "$ARTIFACT_DIR/real-app-diagnostics/assert-gaode-type/uiautomator.xml" ]] ||
  fail "Expected Gaode failure diagnostics to preserve a UIAutomator dump"
[[ -s "$ARTIFACT_DIR/real-app-diagnostics/assert-gaode-type/screenshot.png" ]] ||
  fail "Expected Gaode failure diagnostics to preserve a screenshot"
[[ -s "$ARTIFACT_DIR/real-app-diagnostics/assert-gaode-type/window-dump.txt" ]] ||
  fail "Expected Gaode failure diagnostics to preserve a window dump"
[[ -s "$ARTIFACT_DIR/real-app-diagnostics/assert-gaode-type/logcat.txt" ]] ||
  fail "Expected Gaode failure diagnostics to preserve logcat"

reset_logs
expect_failure \
  "real app search eval archives submit failure evidence" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' ANDROID_SERIAL="device-a" \
  FAKE_REAL_APP_SEARCH_INSTALLED_PACKAGES="com.jingdong.app.mall" \
  FAKE_REAL_APP_SEARCH_FAIL_STEP="submit" \
  SKIP_BUILD=1 SKIP_INSTALL=1 FORCE_STOP_TARGET_APP=0 \
  REPORT_FILE="$ARTIFACT_DIR/real-app-search-eval.properties" \
  LOGCAT_FILE="$ARTIFACT_DIR/real-app-search-logcat.txt" \
  DIAGNOSTICS_DIR="$ARTIFACT_DIR/real-app-diagnostics" \
  GRADLE_CMD="$FAKE_GRADLE" scripts/run_real_app_search_eval.sh
assert_no_gradle_call
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "reason=real-app-search-case-failed"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "run_count=1"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "fail_count=1"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "skip_count=7"
JD_CASE_REPORT="$ARTIFACT_DIR/jd.case.properties"
JD_RANKED_CANDIDATES="$ARTIFACT_DIR/jd.ranked-candidates.json"
JD_TARGET_RESOLUTION="$ARTIFACT_DIR/jd.target-resolution.properties"
assert_report_contains "$JD_CASE_REPORT" "case=jd"
assert_report_contains "$JD_CASE_REPORT" "expected_package_name=com.jingdong.app.mall"
assert_report_contains "$JD_CASE_REPORT" "status=failed"
assert_report_contains "$JD_CASE_REPORT" "reason=submit_not_found"
assert_report_contains "$JD_CASE_REPORT" "failure_kind=submit_not_found"
assert_report_contains "$JD_CASE_REPORT" "failed_step=submit_search"
assert_report_contains "$JD_CASE_REPORT" "result_file=$ARTIFACT_DIR/jd-submit.properties"
grep -Eq '^result_file_sha256=[0-9a-f]{64}$' "$JD_CASE_REPORT" ||
  fail "Expected JD case report to hash the submit result file"
assert_report_contains "$JD_CASE_REPORT" "target_resolution_available=true"
assert_report_contains "$JD_CASE_REPORT" "target_resolution_kind=submit_button"
assert_report_contains "$JD_CASE_REPORT" "target_resolution_failure_kind=submit_not_found"
assert_report_contains "$JD_CASE_REPORT" "target_resolution_candidate_count=1"
assert_report_contains "$JD_CASE_REPORT" "target_resolution_evidence_file=$JD_TARGET_RESOLUTION"
assert_report_contains "$JD_CASE_REPORT" "ranked_candidates_file=$JD_RANKED_CANDIDATES"
assert_report_contains "$JD_TARGET_RESOLUTION" "target_resolution_failure_kind=submit_not_found"
assert_report_contains_text "$JD_RANKED_CANDIDATES" '"label":"键盘搜索"'
assert_report_contains_text "$JD_RANKED_CANDIDATES" '"enabled":false'

reset_logs
expect_failure \
  "real app search eval archives result verification failure evidence" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' ANDROID_SERIAL="device-a" \
  FAKE_REAL_APP_SEARCH_INSTALLED_PACKAGES="com.android.chrome" \
  FAKE_REAL_APP_SEARCH_FAIL_STEP="verify" \
  SKIP_BUILD=1 SKIP_INSTALL=1 FORCE_STOP_TARGET_APP=0 \
  REPORT_FILE="$ARTIFACT_DIR/real-app-search-eval.properties" \
  LOGCAT_FILE="$ARTIFACT_DIR/real-app-search-logcat.txt" \
  DIAGNOSTICS_DIR="$ARTIFACT_DIR/real-app-diagnostics" \
  GRADLE_CMD="$FAKE_GRADLE" scripts/run_real_app_search_eval.sh
assert_no_gradle_call
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "reason=real-app-search-case-failed"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "run_count=1"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "fail_count=1"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "skip_count=7"
CHROME_CASE_REPORT="$ARTIFACT_DIR/chrome.case.properties"
assert_report_contains "$CHROME_CASE_REPORT" "case=chrome"
assert_report_contains "$CHROME_CASE_REPORT" "expected_package_name=com.android.chrome"
assert_report_contains "$CHROME_CASE_REPORT" "status=failed"
assert_report_contains "$CHROME_CASE_REPORT" "reason=result_not_verified"
assert_report_contains "$CHROME_CASE_REPORT" "failure_kind=result_not_verified"
assert_report_contains "$CHROME_CASE_REPORT" "failed_step=verify"
assert_report_contains "$CHROME_CASE_REPORT" "result_file=$ARTIFACT_DIR/chrome-verify.properties"
grep -Eq '^result_file_sha256=[0-9a-f]{64}$' "$CHROME_CASE_REPORT" ||
  fail "Expected Chrome case report to hash the verify result file"
assert_report_contains "$CHROME_CASE_REPORT" "target_resolution_available=false"

reset_logs
expect_failure \
  "real app search eval archives required hint failure evidence" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' ANDROID_SERIAL="device-a" \
  FAKE_REAL_APP_SEARCH_INSTALLED_PACKAGES="com.xunmeng.pinduoduo" \
  FAKE_REAL_APP_SEARCH_FAIL_STEP="required_hint" \
  SKIP_BUILD=1 SKIP_INSTALL=1 FORCE_STOP_TARGET_APP=0 \
  REPORT_FILE="$ARTIFACT_DIR/real-app-search-eval.properties" \
  LOGCAT_FILE="$ARTIFACT_DIR/real-app-search-logcat.txt" \
  DIAGNOSTICS_DIR="$ARTIFACT_DIR/real-app-diagnostics" \
  GRADLE_CMD="$FAKE_GRADLE" scripts/run_real_app_search_eval.sh
assert_no_gradle_call
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "reason=real-app-search-case-failed"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "run_count=1"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "fail_count=1"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "skip_count=7"
PDD_CASE_REPORT="$ARTIFACT_DIR/pdd.case.properties"
assert_report_contains "$PDD_CASE_REPORT" "case=pdd"
assert_report_contains "$PDD_CASE_REPORT" "expected_package_name=com.xunmeng.pinduoduo"
assert_report_contains "$PDD_CASE_REPORT" "status=failed"
assert_report_contains "$PDD_CASE_REPORT" "reason=required_hint_missing"
assert_report_contains "$PDD_CASE_REPORT" "failure_kind=required_hint_missing"
assert_report_contains "$PDD_CASE_REPORT" "failed_step=verify"
assert_report_contains "$PDD_CASE_REPORT" "result_file=$ARTIFACT_DIR/pdd-verify.properties"
grep -Eq '^result_file_sha256=[0-9a-f]{64}$' "$PDD_CASE_REPORT" ||
  fail "Expected PDD case report to hash the verify result file"
assert_report_contains "$PDD_CASE_REPORT" "target_resolution_available=false"

reset_logs
expect_failure \
  "real app search eval fails closed when no target apps are installed" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' ANDROID_SERIAL="device-a" \
  FAKE_REAL_APP_SEARCH_INSTALLED_PACKAGES="" \
  SKIP_BUILD=1 SKIP_INSTALL=1 FORCE_STOP_TARGET_APP=0 \
  REPORT_FILE="$ARTIFACT_DIR/real-app-search-eval.properties" \
  LOGCAT_FILE="$ARTIFACT_DIR/real-app-search-logcat.txt" \
  DIAGNOSTICS_DIR="$ARTIFACT_DIR/real-app-diagnostics" \
  GRADLE_CMD="$FAKE_GRADLE" scripts/run_real_app_search_eval.sh
assert_no_gradle_call
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "failedTarget=target-apps"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "reason=no-target-apps-installed"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "serial=device-a"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "api_level=36"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "abi=arm64-v8a,armeabi-v7a"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "run_count=0"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "pass_count=0"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "fail_count=0"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "skip_count=8"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "failure_diagnostics_dir=$ARTIFACT_DIR/real-app-diagnostics/fatal-no-target-apps-installed"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "failure_screenshot_file=$ARTIFACT_DIR/real-app-diagnostics/fatal-no-target-apps-installed/screenshot.png"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "failure_uiautomator_dump_file=$ARTIFACT_DIR/real-app-diagnostics/fatal-no-target-apps-installed/uiautomator.xml"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "failure_focused_window_file=$ARTIFACT_DIR/real-app-diagnostics/fatal-no-target-apps-installed/focused-window.txt"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "failure_window_dump_file=$ARTIFACT_DIR/real-app-diagnostics/fatal-no-target-apps-installed/window-dump.txt"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "failure_logcat_file=$ARTIFACT_DIR/real-app-diagnostics/fatal-no-target-apps-installed/logcat.txt"
for key in failure_screenshot_sha256 failure_uiautomator_dump_sha256 failure_focused_window_sha256 failure_window_dump_sha256 failure_logcat_sha256; do
  grep -Eq "^${key}=[0-9a-f]{64}$" "$ARTIFACT_DIR/real-app-search-eval.properties" ||
    fail "Expected real app search fatal report to include $key"
done
[[ -s "$ARTIFACT_DIR/real-app-diagnostics/fatal-no-target-apps-installed/screenshot.png" ]] ||
  fail "Expected real app search fatal diagnostics to preserve a screenshot"
[[ -s "$ARTIFACT_DIR/real-app-diagnostics/fatal-no-target-apps-installed/uiautomator.xml" ]] ||
  fail "Expected real app search fatal diagnostics to preserve a UIAutomator dump"
[[ -s "$ARTIFACT_DIR/real-app-diagnostics/fatal-no-target-apps-installed/window-dump.txt" ]] ||
  fail "Expected real app search fatal diagnostics to preserve a window dump"
[[ -s "$ARTIFACT_DIR/real-app-diagnostics/fatal-no-target-apps-installed/logcat.txt" ]] ||
  fail "Expected real app search fatal diagnostics to preserve logcat"
assert_report_contains "$ARTIFACT_DIR/taobao.case.properties" "case=taobao"
assert_report_contains "$ARTIFACT_DIR/taobao.case.properties" "status=skipped"
assert_report_contains "$ARTIFACT_DIR/taobao.case.properties" "reason=package_not_installed:com.taobao.taobao"
assert_report_contains "$ARTIFACT_DIR/taobao.case.properties" "failed_step=package_check"

reset_logs
expect_success \
  "real app search eval passes when all fake target apps verify" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' ANDROID_SERIAL="device-a" \
  FAKE_REAL_APP_SEARCH_INSTALLED_PACKAGES="com.taobao.taobao,com.xunmeng.pinduoduo,com.autonavi.minimap,com.jingdong.app.mall,com.android.chrome,com.android.browser,com.quark.browser,com.UCMobile" \
  SKIP_BUILD=1 SKIP_INSTALL=1 FORCE_STOP_TARGET_APP=0 \
  REPORT_FILE="$ARTIFACT_DIR/real-app-search-eval.properties" \
  LOGCAT_FILE="$ARTIFACT_DIR/real-app-search-logcat.txt" \
  DIAGNOSTICS_DIR="$ARTIFACT_DIR/real-app-diagnostics" \
  GRADLE_CMD="$FAKE_GRADLE" scripts/run_real_app_search_eval.sh
assert_no_gradle_call
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "reason="
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "run_count=8"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "pass_count=8"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "fail_count=0"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "skip_count=0"
assert_report_contains "$ARTIFACT_DIR/taobao.case.properties" "case=taobao"
assert_report_contains "$ARTIFACT_DIR/taobao.case.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/taobao.case.properties" "result_file=$ARTIFACT_DIR/taobao-verify.properties"
assert_report_contains "$ARTIFACT_DIR/uc.case.properties" "case=uc"
assert_report_contains "$ARTIFACT_DIR/uc.case.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/uc.case.properties" "result_file=$ARTIFACT_DIR/uc-verify.properties"

reset_logs
expect_failure \
  "install helper without devices" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES="" GRADLE_CMD="$FAKE_GRADLE" \
  scripts/install_and_test_device.sh
assert_no_gradle_call
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "target=device"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "failedTarget=device-selection"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "reason=device-selection-ambiguous"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "instrumentation=not-run"

reset_logs
expect_failure \
  "install helper with unauthorized device" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tunauthorized' GRADLE_CMD="$FAKE_GRADLE" \
  scripts/install_and_test_device.sh
assert_no_gradle_call

reset_logs
expect_failure \
  "install helper with multiple devices and no serial" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice\ndevice-b\tdevice' GRADLE_CMD="$FAKE_GRADLE" \
  scripts/install_and_test_device.sh
assert_no_gradle_call

reset_logs
expect_failure \
  "install helper with offline selected serial" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\toffline' ANDROID_SERIAL="device-a" GRADLE_CMD="$FAKE_GRADLE" \
  scripts/install_and_test_device.sh
assert_no_gradle_call

reset_logs
expect_failure \
  "install helper with missing selected serial" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' ANDROID_SERIAL="device-b" GRADLE_CMD="$FAKE_GRADLE" \
  scripts/install_and_test_device.sh
assert_no_gradle_call

reset_logs
expect_failure \
  "install helper rejects non arm64 device before Gradle" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' FAKE_ABI_LIST="armeabi-v7a,x86" \
  GRADLE_CMD="$FAKE_GRADLE" scripts/install_and_test_device.sh
assert_no_gradle_call
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "failedTarget=device-abi"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "reason=device-abi-not-arm64"
grep -q "not arm64-v8a compatible" <<<"$LAST_OUTPUT" ||
  fail "Expected install helper to reject non arm64-v8a devices"
grep -q -- "-s device-a shell getprop ro.product.cpu.abilist64" "$FAKE_ADB_LOG" ||
  fail "Expected install helper to check selected device ABI before Gradle"

reset_logs
expect_failure \
  "install helper rejects low data partition space before Gradle" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' FAKE_ABI_LIST="arm64-v8a,armeabi-v7a" \
  FAKE_DATA_FREE_KB="3145727" \
  GRADLE_CMD="$FAKE_GRADLE" scripts/install_and_test_device.sh
assert_no_gradle_call
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "failedTarget=data-free-space"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "reason=data-free-below-threshold"
grep -q "less than 3 GB free on /data" <<<"$LAST_OUTPUT" ||
  fail "Expected install helper to reject devices with low /data free space"
grep -q -- "-s device-a shell df -k /data" "$FAKE_ADB_LOG" ||
  fail "Expected install helper to check /data free space before Gradle"

reset_logs
expect_success \
  "install helper selects the only authorized device" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' RELEASE_ARTIFACT_SHA256="$VALID_PERF_SHA" \
  GRADLE_CMD="$FAKE_GRADLE" \
  scripts/install_and_test_device.sh
assert_gradle_called
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "artifact_schema=DeviceVerificationArtifact/v1"
grep -Eq '^artifact_id=device-device-a-api36-' "$ARTIFACT_DIR/device-verification.properties" ||
  fail "Expected install helper to write a stable device artifact id"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "failedTarget="
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "reason="
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "serial=device-a"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "api_level=36"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "abi=arm64-v8a,armeabi-v7a"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "reset_app_data_after_tests=1"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "instrumentation=passed"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "instrumentation_test_count=20"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "test_count=20"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "releaseArtifactSha256=$VALID_PERF_SHA"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "instrumentation_output_file=$ARTIFACT_DIR/instrumentation.txt"
grep -Eq '^instrumentation_output_sha256=[0-9a-f]{64}$' "$ARTIFACT_DIR/device-verification.properties" ||
  fail "Expected install helper to write instrumentation output SHA-256"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "logcat_file=$ARTIFACT_DIR/logcat.txt"
grep -Eq '^logcat_sha256=[0-9a-f]{64}$' "$ARTIFACT_DIR/device-verification.properties" ||
  fail "Expected install helper to write logcat SHA-256"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "logcat_captured=1"
grep -q "OK (20 tests)" "$ARTIFACT_DIR/instrumentation.txt" ||
  fail "Expected install helper to persist instrumentation output"
[[ -s "$ARTIFACT_DIR/logcat.txt" ]] ||
  fail "Expected install helper to persist logcat output"
grep -q -- "-s device-a shell getprop ro.product.cpu.abilist64" "$FAKE_ADB_LOG" ||
  fail "Expected adb device commands to target the only authorized device"
grep -q -- "-s device-a logcat -c" "$FAKE_ADB_LOG" ||
  fail "Expected install helper to clear the logcat window before validation"
grep -q -- "-s device-a logcat -d -t 500" "$FAKE_ADB_LOG" ||
  fail "Expected install helper to capture logcat after validation"
grep -q -- "-s device-a shell pm clear com.bytedance.zgx.pocketmind" "$FAKE_ADB_LOG" ||
  fail "Expected install helper to clear target app data before default success launch"
grep -q -- "-s device-a shell pm clear com.bytedance.zgx.pocketmind.test" "$FAKE_ADB_LOG" ||
  fail "Expected install helper to clear test app data before default success launch"

reset_logs
expect_success \
  "install helper scopes instrumentation class" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' \
  INSTRUMENTATION_CLASS="com.bytedance.zgx.pocketmind.MainActivitySmokeTest" \
  GRADLE_CMD="$FAKE_GRADLE" scripts/install_and_test_device.sh
assert_gradle_called
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "instrumentation=passed"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "instrumentation_class=com.bytedance.zgx.pocketmind.MainActivitySmokeTest"
grep -q -- "-s device-a shell am instrument -w -r -e class com.bytedance.zgx.pocketmind.MainActivitySmokeTest com.bytedance.zgx.pocketmind.test/androidx.test.runner.AndroidJUnitRunner" "$FAKE_ADB_LOG" ||
  fail "Expected install helper to pass the requested instrumentation class"

reset_logs
expect_success \
  "install helper clears clean-device app data before success launch" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' \
  CLEAN_DEVICE=1 \
  GRADLE_CMD="$FAKE_GRADLE" scripts/install_and_test_device.sh
assert_gradle_called
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "clean_device=1"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "reset_app_data_after_tests=1"
grep -q -- "-s device-a shell pm clear com.bytedance.zgx.pocketmind" "$FAKE_ADB_LOG" ||
  fail "Expected clean-device install helper to clear target app data before success launch"
grep -q -- "-s device-a shell pm clear com.bytedance.zgx.pocketmind.test" "$FAKE_ADB_LOG" ||
  fail "Expected clean-device install helper to clear test app data before success launch"
pm_clear_line="$(grep -n -- "-s device-a shell pm clear com.bytedance.zgx.pocketmind" "$FAKE_ADB_LOG" | head -n 1 | cut -d: -f1)"
launch_line="$(grep -n -- "-s device-a shell am start -W -n com.bytedance.zgx.pocketmind/.MainActivity" "$FAKE_ADB_LOG" | head -n 1 | cut -d: -f1)"
if [[ -z "$pm_clear_line" || -z "$launch_line" || "$pm_clear_line" -ge "$launch_line" ]]; then
  fail "Expected target app data to be cleared before launching app after successful clean-device validation"
fi

reset_logs
expect_failure \
  "install helper times out stuck instrumentation" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' \
  FAKE_INSTRUMENTATION_SLEEP_SECONDS=2 \
  INSTRUMENTATION_TIMEOUT_SECONDS=1 \
  GRADLE_CMD="$FAKE_GRADLE" scripts/install_and_test_device.sh
assert_gradle_called
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "serial=device-a"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "instrumentation=failed"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "failedTarget=instrumentation"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "reason=instrumentation-timeout"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "instrumentation_timeout_seconds=1"
grep -q "timed out after 1s" <<<"$LAST_OUTPUT" ||
  fail "Expected install helper to report instrumentation timeout"
grep -q -- "-s device-a shell am force-stop com.bytedance.zgx.pocketmind" "$FAKE_ADB_LOG" ||
  fail "Expected install helper to stop target package after instrumentation timeout"

reset_logs
expect_failure \
  "install helper clears clean-device state after timeout" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' \
  FAKE_INSTRUMENTATION_SLEEP_SECONDS=2 \
  CLEAN_DEVICE=1 \
  INSTRUMENTATION_TIMEOUT_SECONDS=1 \
  GRADLE_CMD="$FAKE_GRADLE" scripts/install_and_test_device.sh
assert_gradle_called
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "clean_device=1"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "reset_app_data_after_tests=1"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "reason=instrumentation-timeout"
grep -q -- "-s device-a shell pm clear com.bytedance.zgx.pocketmind" "$FAKE_ADB_LOG" ||
  fail "Expected clean-device install helper to clear target app data after timeout"
grep -q -- "-s device-a shell pm clear com.bytedance.zgx.pocketmind.test" "$FAKE_ADB_LOG" ||
  fail "Expected clean-device install helper to clear test app data after timeout"
grep -q -- "-s device-a uninstall com.bytedance.zgx.pocketmind.test" "$FAKE_ADB_LOG" ||
  fail "Expected clean-device install helper to uninstall test package after timeout"

reset_logs
expect_failure \
  "install helper rejects instrumentation numtests without final OK" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' \
  FAKE_INSTRUMENTATION_OUTPUT=$'INSTRUMENTATION_STATUS: numtests=20' \
  GRADLE_CMD="$FAKE_GRADLE" scripts/install_and_test_device.sh
assert_gradle_called
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "serial=device-a"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "instrumentation=failed"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "failedTarget=instrumentation"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "reason=instrumentation-success-marker-missing"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "instrumentation_test_count=20"
grep -q "final OK/success marker" <<<"$LAST_OUTPUT" ||
  fail "Expected install helper to reject malformed instrumentation output without final OK"

reset_logs
expect_success \
  "install helper selects requested serial" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice\ndevice-b\tdevice' ANDROID_SERIAL="device-b" \
  GRADLE_CMD="$FAKE_GRADLE" scripts/install_and_test_device.sh
assert_gradle_called
grep -q -- "-s device-b shell getprop ro.product.cpu.abilist64" "$FAKE_ADB_LOG" ||
  fail "Expected adb device commands to target ANDROID_SERIAL"
grep -q -- "-s device-b install -r app/build/outputs/apk/debug/app-debug.apk" "$FAKE_ADB_LOG" ||
  fail "Expected debug APK install to target ANDROID_SERIAL"

reset_logs
expect_success \
  "review install helper preserves app data and does not run instrumentation" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' GRADLE_CMD="$FAKE_GRADLE" \
  scripts/install_review_device.sh
grep -q -- ":app:assembleDebug" "$FAKE_GRADLE_LOG" ||
  fail "Expected review install helper to assemble debug APK"
assert_report_contains "$ARTIFACT_DIR/manual-acceptance-install-device.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/manual-acceptance-install-device.properties" "target=manual-acceptance-install"
assert_report_contains "$ARTIFACT_DIR/manual-acceptance-install-device.properties" "manualAcceptanceInstall=true"
assert_report_contains "$ARTIFACT_DIR/manual-acceptance-install-device.properties" "regressionEvidence=false"
assert_report_contains "$ARTIFACT_DIR/manual-acceptance-install-device.properties" "clean_device=0"
assert_report_contains "$ARTIFACT_DIR/manual-acceptance-install-device.properties" "kept_app_data=1"
assert_report_contains "$ARTIFACT_DIR/manual-acceptance-install-device.properties" "instrumentation=not-run"
assert_report_contains "$ARTIFACT_DIR/manual-acceptance-install-device.properties" "remote_config=not-requested"
assert_report_contains "$ARTIFACT_DIR/manual-acceptance-install-device.properties" "install_output_file=$ARTIFACT_DIR/install.txt"
assert_report_contains "$ARTIFACT_DIR/manual-acceptance-install-device.properties" "start_output_file=$ARTIFACT_DIR/start.txt"
[[ -s "$ARTIFACT_DIR/install.txt" ]] ||
  fail "Expected review install helper to preserve install output"
[[ -s "$ARTIFACT_DIR/start.txt" ]] ||
  fail "Expected review install helper to preserve start output"
grep -q -- "-s device-a install -r app/build/outputs/apk/debug/app-debug.apk" "$FAKE_ADB_LOG" ||
  fail "Expected review install helper to install debug APK"
if grep -q -- "pm clear com.bytedance.zgx.pocketmind" "$FAKE_ADB_LOG"; then
  fail "Review install helper must not clear app data"
fi
if grep -q -- "uninstall com.bytedance.zgx.pocketmind" "$FAKE_ADB_LOG"; then
  fail "Review install helper must not uninstall app by default"
fi
if grep -q -- "am instrument" "$FAKE_ADB_LOG"; then
  fail "Review install helper must not run instrumentation"
fi

reset_logs
expect_success \
  "review install helper can inject debug remote config without logging secrets" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' GRADLE_CMD="$FAKE_GRADLE" \
  POCKETMIND_REVIEW_REMOTE_BASE_URL="https://example.invalid" \
  POCKETMIND_REVIEW_REMOTE_MODEL="example-model" \
  POCKETMIND_REVIEW_REMOTE_API_KEY="example-secret" \
  scripts/install_review_device.sh
assert_report_contains "$ARTIFACT_DIR/manual-acceptance-install-device.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/manual-acceptance-install-device.properties" "remote_config=saved"
assert_report_contains "$ARTIFACT_DIR/manual-acceptance-install-device.properties" "remote_config_source=POCKETMIND_REVIEW_REMOTE_BASE_URL,POCKETMIND_REVIEW_REMOTE_MODEL,POCKETMIND_REVIEW_REMOTE_API_KEY"
grep -q -- "-s device-a shell run-as com.bytedance.zgx.pocketmind am broadcast --user 0 -n com.bytedance.zgx.pocketmind/.debug.DebugRemoteConfigReceiver" "$FAKE_ADB_LOG" ||
  fail "Expected review install helper to use debug receiver via run-as"
if grep -q "example-secret" "$ARTIFACT_DIR/manual-acceptance-install-device.properties"; then
  fail "Review install report must not persist remote API key"
fi

reset_logs
expect_failure \
  "install helper fails failed instrumentation output even when adb exits zero" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' \
  FAKE_INSTRUMENTATION_OUTPUT=$'FAILURES!!!\nTests run: 3,  Failures: 1\nINSTRUMENTATION_CODE: -1' \
  GRADLE_CMD="$FAKE_GRADLE" scripts/install_and_test_device.sh
assert_gradle_called
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "serial=device-a"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "instrumentation=failed"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "failedTarget=instrumentation"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "reason=instrumentation-failed"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "instrumentation_test_count=3"
grep -q "FAILURES!!!" "$ARTIFACT_DIR/instrumentation.txt" ||
  fail "Expected failed instrumentation output to be persisted"

reset_logs
expect_failure \
  "emulator helper rejects physical serial" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' ANDROID_SERIAL="device-a" \
  EMULATOR_SELECT_TIMEOUT_SECONDS=0 GRADLE_CMD="$FAKE_GRADLE" \
  scripts/verify_emulator.sh
assert_no_gradle_call
assert_report_contains "$ARTIFACT_DIR/emulator-verification.properties" "failedTarget=android-serial"
assert_report_contains "$ARTIFACT_DIR/emulator-verification.properties" "reason=android-serial-not-emulator"

reset_logs
expect_failure \
  "emulator helper rejects physical-only devices" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' EMULATOR_SELECT_TIMEOUT_SECONDS=0 \
  GRADLE_CMD="$FAKE_GRADLE" scripts/verify_emulator.sh
assert_no_gradle_call
assert_report_contains "$ARTIFACT_DIR/emulator-verification.properties" "failedTarget=emulator-selection"
assert_report_contains "$ARTIFACT_DIR/emulator-verification.properties" "reason=no-single-authorized-emulator"

reset_logs
expect_failure \
  "emulator helper without emulator binary" \
  env ANDROID_SDK_ROOT="$NO_EMULATOR_SDK" ANDROID_HOME="$NO_EMULATOR_SDK" \
  FAKE_ADB_DEVICES=$'emulator-5554\tdevice' GRADLE_CMD="$FAKE_GRADLE" \
  scripts/verify_emulator.sh
assert_no_gradle_call
assert_report_contains "$ARTIFACT_DIR/emulator-verification.properties" "failedTarget=emulator-binary"
assert_report_contains "$ARTIFACT_DIR/emulator-verification.properties" "reason=emulator-binary-missing"
grep -q "Android emulator binary not found" <<<"$LAST_OUTPUT" ||
  fail "Expected emulator helper to report missing emulator binary"

reset_logs
expect_failure \
  "emulator helper rejects unknown requested AVD" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES="" FAKE_EMULATOR_AVDS="other-avd" AVD_NAME="test-avd" \
  GRADLE_CMD="$FAKE_GRADLE" scripts/verify_emulator.sh
assert_no_gradle_call
assert_report_contains "$ARTIFACT_DIR/emulator-verification.properties" "failedTarget=requested-avd"
assert_report_contains "$ARTIFACT_DIR/emulator-verification.properties" "reason=requested-avd-not-found"
grep -q "AVD_NAME=test-avd not found" <<<"$LAST_OUTPUT" ||
  fail "Expected emulator helper to report unknown AVD"

reset_logs
expect_success \
  "emulator helper selects the only authorized emulator" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'emulator-5554\tdevice' GRADLE_CMD="$FAKE_GRADLE" \
  scripts/verify_emulator.sh
assert_gradle_called
assert_report_contains "$ARTIFACT_DIR/emulator-verification.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/emulator-verification.properties" "target=emulator"
assert_report_contains "$ARTIFACT_DIR/emulator-verification.properties" "failedTarget="
assert_report_contains "$ARTIFACT_DIR/emulator-verification.properties" "reason="
assert_report_contains "$ARTIFACT_DIR/emulator-verification.properties" "serial=emulator-5554"
assert_report_contains "$ARTIFACT_DIR/emulator-verification.properties" "api_level=36"
assert_report_contains "$ARTIFACT_DIR/emulator-verification.properties" "avd=test-avd"
assert_report_contains "$ARTIFACT_DIR/emulator-verification.properties" "evidence_dir=$ARTIFACT_DIR"
assert_report_contains "$ARTIFACT_DIR/emulator-verification.properties" "screenshot_file=$ARTIFACT_DIR/screenshot.png"
assert_report_contains "$ARTIFACT_DIR/emulator-verification.properties" "window_dump_file=$ARTIFACT_DIR/window.xml"
assert_report_contains "$ARTIFACT_DIR/emulator-verification.properties" "logcat_file=$ARTIFACT_DIR/logcat.txt"
assert_report_contains "$ARTIFACT_DIR/emulator-verification.properties" "crash_anr_smoke_report_file=$ARTIFACT_DIR/crash-anr-smoke.properties"
assert_report_contains "$ARTIFACT_DIR/emulator-verification.properties" "device_report_file=$ARTIFACT_DIR/device-verification.properties"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "artifact_schema=DeviceVerificationArtifact/v1"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "serial=emulator-5554"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "instrumentation=passed"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "instrumentation_test_count=20"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "test_count=20"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "instrumentation_output_file=$ARTIFACT_DIR/instrumentation.txt"
grep -Eq '^instrumentation_output_sha256=[0-9a-f]{64}$' "$ARTIFACT_DIR/device-verification.properties" ||
  fail "Expected emulator nested device report to write instrumentation output SHA-256"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "logcat_file=$ARTIFACT_DIR/logcat.txt"
grep -Eq '^logcat_sha256=[0-9a-f]{64}$' "$ARTIFACT_DIR/device-verification.properties" ||
  fail "Expected emulator nested device report to write logcat SHA-256"
assert_report_contains "$ARTIFACT_DIR/crash-anr-smoke.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/crash-anr-smoke.properties" "logcatFile=$ARTIFACT_DIR/logcat.txt"
assert_report_contains "$ARTIFACT_DIR/crash-anr-smoke.properties" "noReproducibleAnr=true"
grep -q -- "-s emulator-5554 shell getprop sys.boot_completed" "$FAKE_ADB_LOG" ||
  fail "Expected emulator helper to wait for emulator boot completion"
grep -q -- "-s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk" "$FAKE_ADB_LOG" ||
  fail "Expected emulator helper to install debug APK on selected emulator"

reset_logs
expect_success \
  "emulator helper starts requested AVD" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'emulator-5556\tdevice' AVD_NAME="test-avd" \
  GRADLE_CMD="$FAKE_GRADLE" scripts/verify_emulator.sh
assert_gradle_called
grep -q "Starting emulator AVD: test-avd" <<<"$LAST_OUTPUT" ||
  fail "Expected emulator helper to enter AVD startup path"
grep -q -- "-avd test-avd -wipe-data -no-window -no-audio -no-snapshot-save -no-boot-anim -gpu swiftshader_indirect" "$FAKE_EMULATOR_LOG" ||
  fail "Expected emulator helper to start requested AVD with deterministic default emulator args"

reset_logs
expect_success \
  "emulator helper honors explicit emulator args" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'emulator-5556\tdevice' AVD_NAME="test-avd" \
  EMULATOR_ARGS="-no-window -no-audio" \
  GRADLE_CMD="$FAKE_GRADLE" scripts/verify_emulator.sh
assert_gradle_called
grep -q -- "-avd test-avd -no-window -no-audio" "$FAKE_EMULATOR_LOG" ||
  fail "Expected emulator helper to pass explicit emulator args"
if grep -q -- "-wipe-data" "$FAKE_EMULATOR_LOG"; then
  fail "Explicit EMULATOR_ARGS should override default emulator args"
fi

reset_logs
SAVED_ARTIFACT_DIR="$ARTIFACT_DIR"
expect_success \
  "emulator helper keeps default device artifacts with emulator artifacts" \
  env -u ARTIFACT_DIR ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'emulator-5554\tdevice' GRADLE_CMD="$FAKE_GRADLE" \
  scripts/verify_emulator.sh
EMULATOR_DEFAULT_REPORT="$(sed -nE 's/^Emulator verification report: (.*)$/\1/p' <<<"$LAST_OUTPUT" | tail -n 1)"
[[ -n "$EMULATOR_DEFAULT_REPORT" && -f "$EMULATOR_DEFAULT_REPORT" ]] ||
  fail "Expected emulator helper to print its default report path"
DEFAULT_DEVICE_REPORT="$(awk -F= '$1 == "device_report_file" {print $2}' "$EMULATOR_DEFAULT_REPORT")"
[[ -n "$DEFAULT_DEVICE_REPORT" && -f "$DEFAULT_DEVICE_REPORT" ]] ||
  fail "Expected default emulator report to link an existing nested device report"
DEFAULT_INSTRUMENTATION_OUTPUT="$(awk -F= '$1 == "instrumentation_output_file" {print $2}' "$DEFAULT_DEVICE_REPORT")"
[[ "$DEFAULT_INSTRUMENTATION_OUTPUT" == "$(dirname "$DEFAULT_DEVICE_REPORT")/instrumentation.txt" ]] ||
  fail "Expected default nested instrumentation output to live beside the device report"
[[ -s "$DEFAULT_INSTRUMENTATION_OUTPUT" ]] ||
  fail "Expected default nested instrumentation output to be non-empty"
ARTIFACT_DIR="$SAVED_ARTIFACT_DIR"
export ARTIFACT_DIR

reset_logs
expect_success \
  "fresh start main shell helper accepts clean install main shell" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'emulator-5554\tdevice' GRADLE_CMD="$FAKE_GRADLE" \
  scripts/verify_fresh_start_main_shell_emulator.sh
grep -q -- ":app:assembleDebug" "$FAKE_GRADLE_LOG" ||
  fail "Expected fresh start helper to assemble debug APK"
assert_report_contains "$ARTIFACT_DIR/fresh-start-main-shell.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/fresh-start-main-shell.properties" "target=fresh-start-main-shell"
assert_report_contains "$ARTIFACT_DIR/fresh-start-main-shell.properties" "serial=emulator-5554"
assert_report_contains "$ARTIFACT_DIR/fresh-start-main-shell.properties" "first_run_setup_visible=false"
assert_report_contains "$ARTIFACT_DIR/fresh-start-main-shell.properties" "main_shell_copy_visible=true"
assert_report_contains "$ARTIFACT_DIR/fresh-start-main-shell.properties" "model_manager_click_opened=true"
assert_report_contains "$ARTIFACT_DIR/fresh-start-main-shell.properties" "screenshot=$ARTIFACT_DIR/fresh-start.png"
assert_report_contains "$ARTIFACT_DIR/fresh-start-main-shell.properties" "window_dump=$ARTIFACT_DIR/fresh-start.xml"
assert_report_contains "$ARTIFACT_DIR/fresh-start-main-shell.properties" "model_manager_window_dump=$ARTIFACT_DIR/model-manager.xml"
grep -q -- "-s emulator-5554 uninstall com.bytedance.zgx.pocketmind" "$FAKE_ADB_LOG" ||
  fail "Expected fresh start helper to clean install the app"
grep -q -- "-s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk" "$FAKE_ADB_LOG" ||
  fail "Expected fresh start helper to install debug APK"
grep -q -- "-s emulator-5554 shell am start -W -n com.bytedance.zgx.pocketmind/.MainActivity" "$FAKE_ADB_LOG" ||
  fail "Expected fresh start helper to launch MainActivity"
grep -q -- "-s emulator-5554 shell input tap 534 212" "$FAKE_ADB_LOG" ||
  fail "Expected fresh start helper to tap the model manager button"
assert_report_contains_text "$ARTIFACT_DIR/model-manager.xml" "远程多模态可选"

reset_logs
expect_success \
  "fresh start main shell helper skips first-run setup then validates main shell" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'emulator-5554\tdevice' \
  FAKE_FRESH_START_SHOW_FIRST_RUN_ONCE=1 \
  GRADLE_CMD="$FAKE_GRADLE" \
  scripts/verify_fresh_start_main_shell_emulator.sh
assert_report_contains "$ARTIFACT_DIR/fresh-start-main-shell.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/fresh-start-main-shell.properties" "first_run_setup_visible=true"
assert_report_contains "$ARTIFACT_DIR/fresh-start-main-shell.properties" "first_run_setup_skipped=true"
assert_report_contains "$ARTIFACT_DIR/fresh-start-main-shell.properties" "main_shell_copy_visible=true"
assert_report_contains "$ARTIFACT_DIR/fresh-start-main-shell.properties" "model_manager_click_opened=true"

reset_logs
expect_failure \
  "fresh start main shell helper rejects model manager click with no response" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'emulator-5554\tdevice' \
  FAKE_MODEL_MANAGER_UI_TEXT="仍停留在主页面" \
  GRADLE_CMD="$FAKE_GRADLE" \
  scripts/verify_fresh_start_main_shell_emulator.sh
assert_report_contains "$ARTIFACT_DIR/fresh-start-main-shell.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/fresh-start-main-shell.properties" "failedTarget=ui"
assert_report_contains "$ARTIFACT_DIR/fresh-start-main-shell.properties" "reason=model-manager-click-no-response"
assert_report_contains "$ARTIFACT_DIR/fresh-start-main-shell.properties" "main_shell_copy_visible=true"
assert_report_contains "$ARTIFACT_DIR/fresh-start-main-shell.properties" "model_manager_click_opened=false"

reset_logs
expect_failure \
  "fresh start main shell helper rejects stuck first-run setup page" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'emulator-5554\tdevice' \
  FAKE_FRESH_START_SHOW_STUCK_FIRST_RUN=1 \
  GRADLE_CMD="$FAKE_GRADLE" \
  scripts/verify_fresh_start_main_shell_emulator.sh
assert_report_contains "$ARTIFACT_DIR/fresh-start-main-shell.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/fresh-start-main-shell.properties" "failedTarget=ui"
assert_report_contains "$ARTIFACT_DIR/fresh-start-main-shell.properties" "reason=first-run-setup-skip-failed"
assert_report_contains "$ARTIFACT_DIR/fresh-start-main-shell.properties" "first_run_setup_visible=true"
assert_report_contains "$ARTIFACT_DIR/fresh-start-main-shell.properties" "first_run_setup_skipped=false"

reset_logs
LIVE_REMOTE_TEST_TOKEN="$TMP_DIR/live-remote-token-from-env"
expect_failure \
  "live remote emulator reports missing base url reason before Gradle" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'emulator-5554\tdevice' GRADLE_CMD="$FAKE_GRADLE" \
  POCKETMIND_LIVE_REMOTE_MODEL="validation-model" \
  POCKETMIND_LIVE_REMOTE_API_KEY="$LIVE_REMOTE_TEST_TOKEN" \
  POCKETMIND_LIVE_REMOTE_WAIT_SECONDS=0 \
  scripts/live_remote_emulator.sh
assert_no_gradle_call
assert_report_contains "$ARTIFACT_DIR/live-remote-emulator.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/live-remote-emulator.properties" "target=live-remote-emulator"
assert_report_contains "$ARTIFACT_DIR/live-remote-emulator.properties" "device_target=emulator"
assert_report_contains "$ARTIFACT_DIR/live-remote-emulator.properties" "failedTarget=configuration"
assert_report_contains "$ARTIFACT_DIR/live-remote-emulator.properties" "reason=missing-base-url"
assert_report_contains "$ARTIFACT_DIR/live-remote-emulator.properties" "screenshot=$ARTIFACT_DIR/live-remote-result.png"
assert_report_contains "$ARTIFACT_DIR/live-remote-emulator.properties" "ui_dump=$ARTIFACT_DIR/live-remote-result.xml"
assert_report_contains "$ARTIFACT_DIR/live-remote-emulator.properties" "logcat_file=$ARTIFACT_DIR/live-remote-logcat.txt"

reset_logs
expect_failure \
  "live remote emulator rejects physical serial" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' ANDROID_SERIAL="device-a" GRADLE_CMD="$FAKE_GRADLE" \
  POCKETMIND_LIVE_REMOTE_BASE_URL="https://remote.example.test/v1" \
  POCKETMIND_LIVE_REMOTE_MODEL="validation-model" \
  POCKETMIND_LIVE_REMOTE_API_KEY="$LIVE_REMOTE_TEST_TOKEN" \
  POCKETMIND_LIVE_REMOTE_WAIT_SECONDS=0 \
  scripts/live_remote_emulator.sh
assert_no_gradle_call
assert_report_contains "$ARTIFACT_DIR/live-remote-emulator.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/live-remote-emulator.properties" "failedTarget=emulator-selection"
assert_report_contains "$ARTIFACT_DIR/live-remote-emulator.properties" "reason=android-serial-not-emulator"

reset_logs
expect_success \
  "live remote helper allows explicit physical target" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' ANDROID_SERIAL="device-a" GRADLE_CMD="$FAKE_GRADLE" \
  POCKETMIND_LIVE_REMOTE_TARGET=device \
  POCKETMIND_LIVE_REMOTE_BASE_URL="https://remote.example.test/v1" \
  POCKETMIND_LIVE_REMOTE_MODEL="validation-model" \
  POCKETMIND_LIVE_REMOTE_API_KEY="$LIVE_REMOTE_TEST_TOKEN" \
  POCKETMIND_LIVE_REMOTE_WAIT_SECONDS=0 \
  scripts/live_remote_emulator.sh
grep -q -- "-s device-a install -r app/build/outputs/apk/debug/app-debug.apk" "$FAKE_ADB_LOG" ||
  fail "Expected explicit physical live remote helper to install the debug APK on the selected device"
grep -q -- "-s device-a shell wm size" "$FAKE_ADB_LOG" ||
  fail "Expected explicit physical live remote helper to read the device screen size"
grep -q -- "-s device-a shell input tap 395 2208" "$FAKE_ADB_LOG" ||
  fail "Expected explicit physical live remote helper to tap the prompt field from UI dump coordinates"
grep -q -- "-s device-a shell input tap 979 2244" "$FAKE_ADB_LOG" ||
  fail "Expected explicit physical live remote helper to tap the send button from UI dump coordinates"
grep -q -- "-s device-a shell input tap 530 1910" "$FAKE_ADB_LOG" ||
  fail "Expected explicit physical live remote helper to confirm remote send from UI dump coordinates"
receiver_broadcast_count="$(
  grep -cE -- "shell run-as com[.]bytedance[.]zgx[.]pocketmind am broadcast .* -n com[.]bytedance[.]zgx[.]pocketmind/[.]debug[.]DebugRemoteConfigReceiver" "$FAKE_ADB_LOG" || true
)"
[[ "$receiver_broadcast_count" -ge 2 ]] ||
  fail "Expected explicit physical live remote helper to configure and clear the debug receiver"
assert_report_contains "$ARTIFACT_DIR/live-remote-device.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/live-remote-device.properties" "target=live-remote-device"
assert_report_contains "$ARTIFACT_DIR/live-remote-device.properties" "device_target=device"
assert_report_contains "$ARTIFACT_DIR/live-remote-device.properties" "serial=device-a"
assert_report_contains "$ARTIFACT_DIR/live-remote-device.properties" "api_key_source=POCKETMIND_LIVE_REMOTE_API_KEY"
assert_report_contains "$ARTIFACT_DIR/live-remote-device.properties" "base_url=<redacted>"
assert_report_contains "$ARTIFACT_DIR/live-remote-device.properties" "model=<redacted>"
assert_report_contains "$ARTIFACT_DIR/live-remote-device.properties" "input_dump=$ARTIFACT_DIR/live-remote-before-input.xml"
assert_report_contains "$ARTIFACT_DIR/live-remote-device.properties" "send_ready_dump=$ARTIFACT_DIR/live-remote-before-send.xml"
assert_report_contains "$ARTIFACT_DIR/live-remote-device.properties" "after_send_dump=$ARTIFACT_DIR/live-remote-after-send.xml"
assert_report_contains "$ARTIFACT_DIR/live-remote-device.properties" "remote_confirmation_handled=true"
assert_report_contains_text "$ARTIFACT_DIR/live-remote-after-send.xml" "远程地址：<redacted>"
assert_report_contains_text "$ARTIFACT_DIR/live-remote-after-send.xml" "模型：<redacted>"
if grep -Fq "$LIVE_REMOTE_TEST_TOKEN" "$ARTIFACT_DIR/live-remote-after-send.xml" ||
  grep -Fq "remote.example.test" "$ARTIFACT_DIR/live-remote-after-send.xml" ||
  grep -Fq "validation-model" "$ARTIFACT_DIR/live-remote-after-send.xml"; then
  fail "Live remote text evidence must redact remote secret/config values"
fi
[[ -s "$ARTIFACT_DIR/live-remote-logcat.txt" ]] ||
  fail "Expected physical live remote success logcat evidence"
if grep -Fq "$LIVE_REMOTE_TEST_TOKEN" "$ARTIFACT_DIR/live-remote-device.properties"; then
  fail "Physical live remote report must not persist the remote API key"
fi

reset_logs
expect_success \
  "live remote emulator uses app uid for debug receiver broadcasts" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'emulator-5554\tdevice' GRADLE_CMD="$FAKE_GRADLE" \
  POCKETMIND_LIVE_REMOTE_BASE_URL="https://remote.example.test/v1" \
  POCKETMIND_LIVE_REMOTE_MODEL="validation-model" \
  POCKETMIND_LIVE_REMOTE_API_KEY="$LIVE_REMOTE_TEST_TOKEN" \
  POCKETMIND_LIVE_REMOTE_WAIT_SECONDS=0 \
  scripts/live_remote_emulator.sh
grep -q -- ":app:assembleDebug" "$FAKE_GRADLE_LOG" ||
  fail "Expected live remote helper to assemble the debug APK"
receiver_broadcast_count="$(
  grep -cE -- "shell run-as com[.]bytedance[.]zgx[.]pocketmind am broadcast .* -n com[.]bytedance[.]zgx[.]pocketmind/[.]debug[.]DebugRemoteConfigReceiver" "$FAKE_ADB_LOG" || true
)"
[[ "$receiver_broadcast_count" -ge 2 ]] ||
  fail "Expected live remote helper to configure and clear the debug receiver through run-as"
if grep -q -- "-s emulator-5554 shell am broadcast" "$FAKE_ADB_LOG"; then
  fail "Live remote helper must not broadcast to the debug receiver from the shell uid"
fi
grep -q -- "--ez clearState true" "$FAKE_ADB_LOG" ||
  fail "Expected live remote helper to request state clearing during setup"
grep -q -- "--ez clearRemoteConfig true" "$FAKE_ADB_LOG" ||
  fail "Expected live remote helper to clear remote config on exit"
grep -q -- "am broadcast --user 0 -n com.bytedance.zgx.pocketmind/.debug.DebugRemoteConfigReceiver" "$FAKE_ADB_LOG" ||
  fail "Expected live remote helper to pin debug receiver broadcasts to user 0"
assert_report_contains "$ARTIFACT_DIR/live-remote-emulator.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/live-remote-emulator.properties" "target=live-remote-emulator"
assert_report_contains "$ARTIFACT_DIR/live-remote-emulator.properties" "device_target=emulator"
assert_report_contains "$ARTIFACT_DIR/live-remote-emulator.properties" "failedTarget="
assert_report_contains "$ARTIFACT_DIR/live-remote-emulator.properties" "reason="
assert_report_contains "$ARTIFACT_DIR/live-remote-emulator.properties" "api_key_source=POCKETMIND_LIVE_REMOTE_API_KEY"
assert_report_contains "$ARTIFACT_DIR/live-remote-emulator.properties" "base_url=<redacted>"
assert_report_contains "$ARTIFACT_DIR/live-remote-emulator.properties" "evidence_dir=$ARTIFACT_DIR"
assert_report_contains "$ARTIFACT_DIR/live-remote-emulator.properties" "screenshot=$ARTIFACT_DIR/live-remote-result.png"
assert_report_contains "$ARTIFACT_DIR/live-remote-emulator.properties" "ui_dump=$ARTIFACT_DIR/live-remote-result.xml"
assert_report_contains "$ARTIFACT_DIR/live-remote-emulator.properties" "input_dump=$ARTIFACT_DIR/live-remote-before-input.xml"
assert_report_contains "$ARTIFACT_DIR/live-remote-emulator.properties" "send_ready_dump=$ARTIFACT_DIR/live-remote-before-send.xml"
assert_report_contains "$ARTIFACT_DIR/live-remote-emulator.properties" "after_send_dump=$ARTIFACT_DIR/live-remote-after-send.xml"
assert_report_contains "$ARTIFACT_DIR/live-remote-emulator.properties" "logcat_file=$ARTIFACT_DIR/live-remote-logcat.txt"
assert_report_contains "$ARTIFACT_DIR/live-remote-emulator.properties" "remote_confirmation_handled=true"
assert_report_contains_text "$ARTIFACT_DIR/live-remote-after-send.xml" "远程地址：<redacted>"
assert_report_contains_text "$ARTIFACT_DIR/live-remote-after-send.xml" "模型：<redacted>"
[[ -s "$ARTIFACT_DIR/live-remote-logcat.txt" ]] ||
  fail "Expected live remote success logcat evidence"
if grep -Fq "$LIVE_REMOTE_TEST_TOKEN" "$ARTIFACT_DIR/live-remote-emulator.properties"; then
  fail "Live remote report must not persist the remote API key"
fi

reset_logs
expect_failure \
  "live remote emulator reports expected text missing with evidence" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'emulator-5554\tdevice' GRADLE_CMD="$FAKE_GRADLE" \
  FAKE_LIVE_REMOTE_UI_TEXT="WRONG_REMOTE_TEXT" \
  POCKETMIND_LIVE_REMOTE_BASE_URL="https://remote.example.test/v1" \
  POCKETMIND_LIVE_REMOTE_MODEL="validation-model" \
  POCKETMIND_LIVE_REMOTE_API_KEY="$LIVE_REMOTE_TEST_TOKEN" \
  POCKETMIND_LIVE_REMOTE_WAIT_SECONDS=0 \
  scripts/live_remote_emulator.sh
grep -q -- ":app:assembleDebug" "$FAKE_GRADLE_LOG" ||
  fail "Expected live remote helper to assemble the debug APK before expected-text validation"
assert_report_contains "$ARTIFACT_DIR/live-remote-emulator.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/live-remote-emulator.properties" "failedTarget=expected-response"
assert_report_contains "$ARTIFACT_DIR/live-remote-emulator.properties" "reason=expected-text-not-found"
assert_report_contains "$ARTIFACT_DIR/live-remote-emulator.properties" "screenshot=$ARTIFACT_DIR/live-remote-result.png"
assert_report_contains "$ARTIFACT_DIR/live-remote-emulator.properties" "ui_dump=$ARTIFACT_DIR/live-remote-result.xml"
assert_report_contains "$ARTIFACT_DIR/live-remote-emulator.properties" "logcat_file=$ARTIFACT_DIR/live-remote-logcat.txt"
[[ -s "$ARTIFACT_DIR/live-remote-result.png" ]] ||
  fail "Expected live remote failure screenshot evidence"
[[ -s "$ARTIFACT_DIR/live-remote-result.xml" ]] ||
  fail "Expected live remote failure UI dump evidence"
[[ -s "$ARTIFACT_DIR/live-remote-logcat.txt" ]] ||
  fail "Expected live remote failure logcat evidence"
if grep -Fq "$LIVE_REMOTE_TEST_TOKEN" "$ARTIFACT_DIR/live-remote-emulator.properties"; then
  fail "Live remote failure report must not persist the remote API key"
fi

reset_logs
expect_failure \
  "release screenshot capture rejects physical serial by default" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' ANDROID_SERIAL="device-a" GRADLE_CMD="$FAKE_GRADLE" \
  scripts/capture_release_screenshots.sh
assert_no_gradle_call
assert_report_contains "$ARTIFACT_DIR/release-screenshots.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/release-screenshots.properties" "failedTarget=android-serial"
assert_report_contains "$ARTIFACT_DIR/release-screenshots.properties" "reason=android-serial-not-emulator"

reset_logs
expect_success \
  "release screenshot capture records sanitized emulator evidence" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'emulator-5554\tdevice' GRADLE_CMD="$FAKE_GRADLE" \
  scripts/capture_release_screenshots.sh
grep -q -- ":app:assembleDebug" "$FAKE_GRADLE_LOG" ||
  fail "Expected release screenshot helper to assemble the debug APK"
receiver_broadcast_count="$(
  grep -cE -- "shell run-as com[.]bytedance[.]zgx[.]pocketmind am broadcast .* -n com[.]bytedance[.]zgx[.]pocketmind/[.]debug[.]DebugRemoteConfigReceiver" "$FAKE_ADB_LOG" || true
)"
[[ "$receiver_broadcast_count" -ge 1 ]] ||
  fail "Expected release screenshot helper to clear the debug receiver through run-as"
if grep -q -- "-s emulator-5554 shell am broadcast" "$FAKE_ADB_LOG"; then
  fail "Release screenshot helper must not broadcast to the debug receiver from the shell uid"
fi
grep -q -- "com.bytedance.zgx.pocketmind.extra.DEBUG_SCREENSHOT_REMOTE_BASE_URL" "$FAKE_ADB_LOG" ||
  fail "Expected release screenshot helper to configure debug remote base URL through MainActivity extras"
grep -q -- "com.bytedance.zgx.pocketmind.extra.DEBUG_SCREENSHOT_REMOTE_MODEL_NAME" "$FAKE_ADB_LOG" ||
  fail "Expected release screenshot helper to configure debug remote model through MainActivity extras"
grep -q -- "--ez clearRemoteConfig true" "$FAKE_ADB_LOG" ||
  fail "Expected release screenshot helper to clear remote config on exit"
grep -q -- "am broadcast --user 0 -n com.bytedance.zgx.pocketmind/.debug.DebugRemoteConfigReceiver" "$FAKE_ADB_LOG" ||
  fail "Expected release screenshot helper to pin debug receiver broadcasts to user 0"
assert_report_contains "$ARTIFACT_DIR/release-screenshots.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/release-screenshots.properties" "failedTarget="
assert_report_contains "$ARTIFACT_DIR/release-screenshots.properties" "reason="
assert_report_contains "$ARTIFACT_DIR/release-screenshots.properties" "target=release-screenshots"
assert_report_contains "$ARTIFACT_DIR/release-screenshots.properties" "clean_device=1"
assert_report_contains "$ARTIFACT_DIR/release-screenshots.properties" "screenshot.chat-home.path=$ARTIFACT_DIR/screenshots/chat-home.png"
assert_report_contains "$ARTIFACT_DIR/release-screenshots.properties" "screenshot.model-manager.path=$ARTIFACT_DIR/screenshots/model-manager.png"
assert_report_contains "$ARTIFACT_DIR/release-screenshots.properties" "screenshot.confirmation-sheet.path=$ARTIFACT_DIR/screenshots/confirmation-sheet.png"
assert_report_contains "$ARTIFACT_DIR/release-screenshots.properties" "screenshot.background-tasks-or-audit.path=$ARTIFACT_DIR/screenshots/background-tasks-or-audit.png"
for screenshot_name in chat-home model-manager confirmation-sheet background-tasks-or-audit; do
  [[ -s "$ARTIFACT_DIR/screenshots/$screenshot_name.png" ]] ||
    fail "Expected release screenshot evidence for $screenshot_name"
  assert_report_contains "$ARTIFACT_DIR/release-screenshots.properties" "screenshot.${screenshot_name}.sanitized=true"
  assert_report_contains "$ARTIFACT_DIR/release-screenshots.properties" "screenshot.${screenshot_name}.uiDump=$ARTIFACT_DIR/ui/screenshot-${screenshot_name}.xml"
  assert_report_contains "$ARTIFACT_DIR/release-screenshots.properties" "screenshot.${screenshot_name}.visualRegression=passed"
  assert_report_contains_text "$ARTIFACT_DIR/release-screenshots.properties" "screenshot.${screenshot_name}.requiredText="
  [[ -s "$ARTIFACT_DIR/ui/screenshot-${screenshot_name}.xml" ]] ||
    fail "Expected release screenshot UI dump for $screenshot_name"
  grep -Eq "^screenshot[.]${screenshot_name}[.]sha256=[0-9a-f]{64}$" "$ARTIFACT_DIR/release-screenshots.properties" ||
    fail "Expected release screenshot SHA for $screenshot_name"
  grep -Eq "^screenshot[.]${screenshot_name}[.]uiDumpSha256=[0-9a-f]{64}$" "$ARTIFACT_DIR/release-screenshots.properties" ||
    fail "Expected release screenshot UI dump SHA for $screenshot_name"
done
REGRESSION_COUNT_FIXTURE="$TMP_DIR/android-test-count-fixture"
mkdir -p "$REGRESSION_COUNT_FIXTURE/java/example"
cat > "$REGRESSION_COUNT_FIXTURE/java/example/FixtureTest.kt" <<'COUNT_FIXTURE'
package example

class FixtureTest {
    @Test
    fun bareAnnotation() = Unit

    @Test()
    fun emptyArguments() = Unit

    @Test(timeout = 1)
    fun annotationArguments() = Unit

    @org.junit.Test
    fun fullyQualifiedAnnotation() = Unit
}
COUNT_FIXTURE

reset_logs
expect_success \
  "regression emulator validates reports and forces clean device" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'emulator-5554\tdevice' \
  FAKE_INSTRUMENTATION_OUTPUT="$SOURCE_INSTRUMENTATION_OUTPUT" \
  CLEAN_DEVICE=0 GRADLE_CMD="$FAKE_GRADLE" scripts/regression_emulator.sh
assert_gradle_called
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "target=regression-emulator"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "failedTarget="
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "reason="
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "clean_device=1"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "source_android_test_count=$SOURCE_ANDROID_TEST_COUNT"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "expected_android_test_count=$SOURCE_ANDROID_TEST_COUNT"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "actual_android_test_count=$SOURCE_ANDROID_TEST_COUNT"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "instrumentation_output_file=$ARTIFACT_DIR/instrumentation.txt"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "logcat_file=$ARTIFACT_DIR/logcat.txt"
grep -Eq '^logcat_sha256=[0-9a-f]{64}$' "$ARTIFACT_DIR/regression-emulator.properties" ||
  fail "Expected regression emulator report to bind nested device logcat SHA-256"
assert_report_contains "$ARTIFACT_DIR/emulator-verification.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/emulator-verification.properties" "clean_device=1"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "clean_device=1"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "instrumentation_output_file=$ARTIFACT_DIR/instrumentation.txt"
grep -q -- "-s emulator-5554 uninstall com.bytedance.zgx.pocketmind" "$FAKE_ADB_LOG" ||
  fail "Expected regression emulator to force CLEAN_DEVICE=1 through device helper"

reset_logs
expect_failure \
  "regression emulator fails when instrumentation count is below source count" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'emulator-5554\tdevice' \
  FAKE_INSTRUMENTATION_OUTPUT="$LOW_INSTRUMENTATION_OUTPUT" GRADLE_CMD="$FAKE_GRADLE" \
  scripts/regression_emulator.sh
assert_gradle_called
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "failedTarget=instrumentation-test-count"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "reason=instrumentation-test-count-below-expected"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "expected_android_test_count=$SOURCE_ANDROID_TEST_COUNT"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "actual_android_test_count=$LOW_ANDROID_TEST_COUNT"
grep -q "expected at least $SOURCE_ANDROID_TEST_COUNT" <<<"$LAST_OUTPUT" ||
  fail "Expected regression emulator to explain insufficient instrumentation count"

reset_logs
expect_success \
  "regression emulator honors higher expected count override" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'emulator-5554\tdevice' \
  FAKE_INSTRUMENTATION_OUTPUT="$HIGH_INSTRUMENTATION_OUTPUT" EXPECTED_ANDROID_TEST_COUNT="$HIGH_ANDROID_TEST_COUNT" \
  GRADLE_CMD="$FAKE_GRADLE" scripts/regression_emulator.sh
assert_gradle_called
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "source_android_test_count=$SOURCE_ANDROID_TEST_COUNT"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "expected_android_test_count=$HIGH_ANDROID_TEST_COUNT"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "actual_android_test_count=$HIGH_ANDROID_TEST_COUNT"

reset_logs
expect_failure \
  "regression emulator rejects expected count override below source count before Gradle" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'emulator-5554\tdevice' EXPECTED_ANDROID_TEST_COUNT="$LOW_ANDROID_TEST_COUNT" \
  GRADLE_CMD="$FAKE_GRADLE" scripts/regression_emulator.sh
assert_no_gradle_call
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "failedTarget=expected-android-test-count"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "reason=expected-android-test-count-below-source"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "source_android_test_count=$SOURCE_ANDROID_TEST_COUNT"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "expected_android_test_count=$LOW_ANDROID_TEST_COUNT"
grep -q "cannot be lower than AndroidTest source count" <<<"$LAST_OUTPUT" ||
  fail "Expected regression emulator to reject lowered expected count override"

reset_logs
expect_success \
  "regression emulator counts supported JUnit test annotations" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  ANDROID_TEST_SOURCE_DIR="$REGRESSION_COUNT_FIXTURE" \
  FAKE_ADB_DEVICES=$'emulator-5554\tdevice' \
  FAKE_INSTRUMENTATION_OUTPUT=$'INSTRUMENTATION_STATUS: numtests=4\nOK (4 tests)' \
  GRADLE_CMD="$FAKE_GRADLE" scripts/regression_emulator.sh
assert_gradle_called
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "expected_android_test_count=4"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "actual_android_test_count=4"

reset_logs
expect_failure \
  "regression emulator rejects invalid expected count before Gradle" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'emulator-5554\tdevice' EXPECTED_ANDROID_TEST_COUNT=abc \
  GRADLE_CMD="$FAKE_GRADLE" scripts/regression_emulator.sh
assert_no_gradle_call
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "failedTarget=expected-android-test-count"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "reason=expected-android-test-count-invalid"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "expected_android_test_count=abc"

reset_logs
expect_failure \
  "regression emulator fails when instrumentation count is missing" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'emulator-5554\tdevice' FAKE_INSTRUMENTATION_OUTPUT="OK" \
  EXPECTED_ANDROID_TEST_COUNT="$SOURCE_ANDROID_TEST_COUNT" GRADLE_CMD="$FAKE_GRADLE" scripts/regression_emulator.sh
assert_gradle_called
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "failedTarget=instrumentation-test-count"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "reason=instrumentation-test-count-missing"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "expected_android_test_count=$SOURCE_ANDROID_TEST_COUNT"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "actual_android_test_count="
grep -q "instrumentation_test_count" <<<"$LAST_OUTPUT" ||
  fail "Expected regression emulator to explain missing instrumentation count"

reset_logs
expect_failure \
  "regression emulator writes failed report when emulator helper fails preflight" \
  env ANDROID_SDK_ROOT="$NO_EMULATOR_SDK" ANDROID_HOME="$NO_EMULATOR_SDK" \
  FAKE_ADB_DEVICES=$'emulator-5554\tdevice' EXPECTED_ANDROID_TEST_COUNT="$SOURCE_ANDROID_TEST_COUNT" \
  GRADLE_CMD="$FAKE_GRADLE" scripts/regression_emulator.sh
assert_no_gradle_call
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "target=regression-emulator"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "failedTarget=emulator-verification"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "reason=emulator-verification-emulator-binary-missing"

reset_logs
expect_success \
  "regression emulator records hosted HVF infra failure as skipped when allowed" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES="" FAKE_EMULATOR_AVDS="test-avd" AVD_NAME="test-avd" \
  FAKE_EMULATOR_OUTPUT=$'HVF error: HV_UNSUPPORTED\nqemu-system-aarch64-headless: failed to initialize HVF: Invalid argument' \
  EMULATOR_SELECT_TIMEOUT_SECONDS=0 ALLOW_EMULATOR_INFRA_UNAVAILABLE=1 \
  EXPECTED_ANDROID_TEST_COUNT="$SOURCE_ANDROID_TEST_COUNT" GRADLE_CMD="$FAKE_GRADLE" scripts/regression_emulator.sh
assert_no_gradle_call
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "status=skipped"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "failedTarget=emulator-infra"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "reason=emulator-infra-hvf-unsupported"
assert_report_contains "$ARTIFACT_DIR/emulator-verification.properties" "reason=no-single-authorized-emulator"
grep -q "HV_UNSUPPORTED" "$ARTIFACT_DIR-emulator.log" ||
  fail "Expected hosted HVF skip to preserve the emulator log evidence"

reset_logs
expect_failure \
  "regression emulator failed report harvests nested device evidence" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'emulator-5554\tdevice' \
  FAKE_INSTRUMENTATION_OUTPUT=$'INSTRUMENTATION_STATUS: numtests=3\nFAILURES!!!\nTests run: 3,  Failures: 1\nINSTRUMENTATION_CODE: -1' \
  EXPECTED_ANDROID_TEST_COUNT="$SOURCE_ANDROID_TEST_COUNT" GRADLE_CMD="$FAKE_GRADLE" scripts/regression_emulator.sh
assert_gradle_called
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "failedTarget=emulator-verification"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "reason=emulator-verification-device-verification-instrumentation-failed"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "serial=emulator-5554"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "api_level=36"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "abi=arm64-v8a,armeabi-v7a"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "actual_android_test_count=3"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "instrumentation_output_file=$ARTIFACT_DIR/instrumentation.txt"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "logcat_file=$ARTIFACT_DIR/logcat.txt"
grep -Eq '^logcat_sha256=[0-9a-f]{64}$' "$ARTIFACT_DIR/regression-emulator.properties" ||
  fail "Expected failed regression emulator report to bind nested device logcat SHA-256"
assert_report_contains "$ARTIFACT_DIR/emulator-verification.properties" "failedTarget=device-verification"
assert_report_contains "$ARTIFACT_DIR/emulator-verification.properties" "reason=device-verification-instrumentation-failed"
assert_report_contains "$ARTIFACT_DIR/emulator-verification.properties" "screenshot_file=$ARTIFACT_DIR/screenshot.png"
[[ -s "$ARTIFACT_DIR/screenshot.png" ]] ||
  fail "Expected emulator helper to capture a failure screenshot"
grep -q "FAILURES!!!" "$ARTIFACT_DIR/instrumentation.txt" ||
  fail "Expected regression failed report to link persisted instrumentation failure output"

echo "Validation script tests passed."
