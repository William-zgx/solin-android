#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ANDROID_SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
export ANDROID_HOME="${ANDROID_HOME:-$ANDROID_SDK}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_SDK}"
GRADLE_CMD="${GRADLE_CMD:-./gradlew}"
ADB_BIN="${ANDROID_SDK}/platform-tools/adb"
BUILD_TOOLS_DIR="${BUILD_TOOLS_DIR:-$({ find "$ANDROID_SDK/build-tools" -maxdepth 1 -type d 2>/dev/null || true; } | sort | tail -n 1)}"
AAPT2="${AAPT2:-$BUILD_TOOLS_DIR/aapt2}"
D8="${D8:-$BUILD_TOOLS_DIR/d8}"
ZIPALIGN="${ZIPALIGN:-$BUILD_TOOLS_DIR/zipalign}"
APKSIGNER="${APKSIGNER:-$BUILD_TOOLS_DIR/apksigner}"
ANDROID_JAR="${ANDROID_JAR:-$ANDROID_SDK/platforms/android-36/android.jar}"
ARTIFACT_DIR="${ARTIFACT_DIR:-build/verification/mock-target-app-search-eval-$(date +%Y%m%d-%H%M%S)}"
REPORT_FILE="${REPORT_FILE:-${ARTIFACT_DIR}/mock-target-app-search-eval.properties}"
LOGCAT_FILE="${LOGCAT_FILE:-${ARTIFACT_DIR}/logcat.txt}"
SKIP_BUILD="${SKIP_BUILD:-0}"
SKIP_SOLIN_INSTALL="${SKIP_SOLIN_INSTALL:-0}"
KEEP_MOCK_APPS="${KEEP_MOCK_APPS:-0}"
STARTED_AT_UTC="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

PACKAGE_NAME="com.bytedance.zgx.solin"
MAIN_ACTIVITY="${PACKAGE_NAME}/.MainActivity"
RECEIVER_NAME="${PACKAGE_NAME}/.debug.DeviceControlEvalReceiver"
ACTION_NAME="${PACKAGE_NAME}.debug.DEVICE_CONTROL_EVAL"
RESULT_FILE="files/device_control_eval_result.properties"
DEBUG_APK="app/build/outputs/apk/debug/app-debug.apk"
SOLIN_ACCESSIBILITY_SERVICE="${PACKAGE_NAME}/${PACKAGE_NAME}.device.SolinAccessibilityService"

SELECTED_SERIAL=""
STATUS="failed"
FAILURE_REASON=""
RUN_COUNT=0
PASS_COUNT=0
FAIL_COUNT=0
MOCK_PACKAGES=()

mkdir -p "$ARTIFACT_DIR"

write_report() {
  local exit_code="$1"
  [[ "$exit_code" -eq 0 ]] && STATUS="passed"
  {
    echo "status=$STATUS"
    echo "exit_code=$exit_code"
    echo "reason=$FAILURE_REASON"
    echo "started_at_utc=$STARTED_AT_UTC"
    echo "finished_at_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "serial=$SELECTED_SERIAL"
    echo "skip_build=$SKIP_BUILD"
    echo "skip_solin_install=$SKIP_SOLIN_INSTALL"
    echo "keep_mock_apps=$KEEP_MOCK_APPS"
    echo "run_count=$RUN_COUNT"
    echo "pass_count=$PASS_COUNT"
    echo "fail_count=$FAIL_COUNT"
    echo "mock_packages=${MOCK_PACKAGES[*]}"
    echo "debug_apk=$DEBUG_APK"
    echo "logcat_file=$LOGCAT_FILE"
    echo "cases=taobao,pdd,gaode"
  } > "$REPORT_FILE"
  echo "Mock target app search eval report: $REPORT_FILE"
}

cleanup() {
  if [[ "$KEEP_MOCK_APPS" == "1" || -z "$SELECTED_SERIAL" || ! -x "$ADB_BIN" ]]; then
    return 0
  fi
  for package_name in "${MOCK_PACKAGES[@]}"; do
    "$ADB_BIN" -s "$SELECTED_SERIAL" uninstall "$package_name" >/dev/null 2>&1 || true
  done
}

on_exit() {
  local status="$?"
  trap - EXIT
  if [[ -n "$SELECTED_SERIAL" && -x "$ADB_BIN" ]]; then
    "$ADB_BIN" -s "$SELECTED_SERIAL" logcat -d -t 500 > "$LOGCAT_FILE" 2>/dev/null || true
  fi
  cleanup
  write_report "$status"
  exit "$status"
}

trap on_exit EXIT

fail_with_reason() {
  FAILURE_REASON="$1"
  shift
  echo "$*" >&2
  exit 1
}

require_tool() {
  local path="$1"
  local name="$2"
  if [[ -z "$path" || ! -x "$path" ]]; then
    fail_with_reason "${name}-not-found" "$name not found or not executable: $path"
  fi
}

require_tool "$AAPT2" aapt2
require_tool "$D8" d8
require_tool "$ZIPALIGN" zipalign
require_tool "$APKSIGNER" apksigner
command -v javac >/dev/null 2>&1 || fail_with_reason javac-not-found "javac not found in PATH."
command -v zip >/dev/null 2>&1 || fail_with_reason zip-not-found "zip not found in PATH."
[[ -f "$ANDROID_JAR" ]] || fail_with_reason android-jar-not-found "Android platform jar not found: $ANDROID_JAR"

if ! scripts/doctor.sh --device; then
  fail_with_reason doctor-device-failed "Android device environment check failed."
fi

if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  SELECTED_SERIAL="$ANDROID_SERIAL"
  SELECTED_STATE="$("$ADB_BIN" devices | awk -v serial="$SELECTED_SERIAL" '$1 == serial {print $2; found = 1} END {if (!found) print ""}')"
  if [[ "$SELECTED_STATE" != "device" ]]; then
    "$ADB_BIN" devices
    fail_with_reason selected-device-unavailable \
      "ANDROID_SERIAL=$SELECTED_SERIAL is not an authorized Android device; state is ${SELECTED_STATE:-missing}."
  fi
else
  DEVICE_COUNT="$("$ADB_BIN" devices | awk 'NR > 1 && $2 == "device" {count += 1} END{print count + 0}')"
  if [[ "$DEVICE_COUNT" != "1" ]]; then
    "$ADB_BIN" devices
    fail_with_reason device-selection-ambiguous \
      "Connect exactly one authorized Android device, or set ANDROID_SERIAL to select one."
  fi
  SELECTED_SERIAL="$("$ADB_BIN" devices | awk 'NR > 1 && $2 == "device" {print $1; exit}')"
fi

ADB=("$ADB_BIN" -s "$SELECTED_SERIAL")
echo "Using Android device: $SELECTED_SERIAL"
"${ADB[@]}" logcat -c >/dev/null 2>&1 || true

if [[ "$("${ADB[@]}" shell getprop ro.kernel.qemu | tr -d '\r')" != "1" ]]; then
  fail_with_reason emulator-required "This mock target app eval installs packages using common app ids; run it only on an emulator."
fi

if [[ "$SKIP_BUILD" != "1" ]]; then
  "$GRADLE_CMD" :app:assembleDebug
fi
if [[ "$SKIP_SOLIN_INSTALL" != "1" ]]; then
  "${ADB[@]}" install -r "$DEBUG_APK"
fi

solin_accessibility_enabled() {
  local dump bound_section enabled_line
  dump="$("${ADB[@]}" shell dumpsys accessibility 2>/dev/null | tr -d '\r')"
  bound_section="$(awk '
    /Bound services:/ {printing = 1}
    printing {print}
    /Enabled services:/ {printing = 0}
  ' <<<"$dump")"
  enabled_line="$(grep -m 1 'Enabled services:' <<<"$dump" || true)"
  grep -Fq "$SOLIN_ACCESSIBILITY_SERVICE" <<<"${bound_section}${enabled_line}"
}

if ! solin_accessibility_enabled; then
  fail_with_reason solin-accessibility-not-enabled \
    "Solin Accessibility is not enabled. Enable it in system Accessibility settings, then rerun with SKIP_SOLIN_INSTALL=1."
fi

"${ADB[@]}" shell am start -W -n "$MAIN_ACTIVITY" >/dev/null
sleep 1

escape_java() {
  printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'
}

build_mock_app() {
  local package_name="$1"
  local app_title="$2"
  local search_hint="$3"
  local input_description="$4"
  local submit_text="$5"
  local submit_description="$6"
  local result_hints="$7"
  local source_dir="${ARTIFACT_DIR}/mock-src/${package_name}"
  mkdir -p "$source_dir" "$ARTIFACT_DIR"
  source_dir="$(cd "$source_dir" && pwd)"
  local class_dir="${source_dir}/classes"
  local dex_dir="${source_dir}/dex"
  local java_pkg_path="com/bytedance/zgx/solin/mocktarget"
  local signed_apk="$(cd "$ARTIFACT_DIR" && pwd)/${package_name}.apk"

  rm -rf "$source_dir"
  mkdir -p "${source_dir}/src/${java_pkg_path}" "$class_dir" "$dex_dir"
  cat > "${source_dir}/AndroidManifest.xml" <<EOF
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="${package_name}">
  <application android:theme="@android:style/Theme.Material.Light.NoActionBar" android:label="${app_title}">
    <activity android:name="com.bytedance.zgx.solin.mocktarget.MockTargetActivity" android:exported="true">
      <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
      </intent-filter>
    </activity>
  </application>
</manifest>
EOF
  cat > "${source_dir}/src/${java_pkg_path}/MockTargetActivity.java" <<EOF
package com.bytedance.zgx.solin.mocktarget;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MockTargetActivity extends Activity {
    private EditText input;
    private TextView result;
    private TextView status;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 48, 32, 32);
        root.setContentDescription("Mock target app root");

        TextView title = new TextView(this);
        title.setText("$(escape_java "$app_title")");
        title.setTextSize(22f);
        root.addView(title);

        status = new TextView(this);
        status.setText("Eval status idle");
        status.setContentDescription("Eval status");
        root.addView(status);

        input = new EditText(this);
        input.setHint("$(escape_java "$search_hint")");
        input.setContentDescription("$(escape_java "$input_description")");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView view, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    submitSearch();
                    return true;
                }
                return false;
            }
        });
        root.addView(input);

        Button submit = new Button(this);
        submit.setText("$(escape_java "$submit_text")");
        submit.setContentDescription("$(escape_java "$submit_description")");
        submit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                submitSearch();
            }
        });
        root.addView(submit);

        result = new TextView(this);
        result.setText("Eval search result idle");
        result.setContentDescription("Eval search result");
        root.addView(result);

        Button filter = new Button(this);
        filter.setText("筛选");
        filter.setContentDescription("筛选 FilterEntry");
        filter.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                status.setText("Filter applied");
            }
        });
        root.addView(filter);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setContentDescription("EvalScrollContainer");
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            360
        ));
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        for (int i = 1; i <= 40; i++) {
            TextView item = new TextView(this);
            item.setText(i == 35 ? "Scroll target item 35" : "Eval list item " + i);
            item.setTextSize(18f);
            item.setPadding(0, 18, 0, 18);
            list.addView(item);
        }
        scrollView.addView(list);
        root.addView(scrollView);

        setContentView(root);
    }

    private void submitSearch() {
        result.setText("Eval search result: $(escape_java "$app_title") " +
            input.getText().toString() + " $(escape_java "$result_hints")");
    }
}
EOF

  javac -source 8 -target 8 -bootclasspath "$ANDROID_JAR" \
    -d "$class_dir" \
    "${source_dir}/src/${java_pkg_path}/MockTargetActivity.java" >/dev/null
  "$D8" --min-api 28 --output "$dex_dir" $(find "$class_dir" -name '*.class')
  "$AAPT2" link -I "$ANDROID_JAR" --manifest "${source_dir}/AndroidManifest.xml" \
    --min-sdk-version 28 \
    --target-sdk-version 36 \
    -o "${source_dir}/unsigned.apk"
  (cd "$dex_dir" && zip -q "${source_dir}/unsigned.apk" classes.dex)
  "$ZIPALIGN" -p -f 4 "${source_dir}/unsigned.apk" "${source_dir}/aligned.apk"
  "$APKSIGNER" sign \
    --ks "$HOME/.android/debug.keystore" \
    --ks-key-alias androiddebugkey \
    --ks-pass pass:android \
    --key-pass pass:android \
    --out "$signed_apk" \
    "${source_dir}/aligned.apk"
  echo "$signed_apk"
}

install_mock_app() {
  local package_name="$1"
  shift
  local apk
  apk="$(build_mock_app "$package_name" "$@")"
  MOCK_PACKAGES+=("$package_name")
  "${ADB[@]}" uninstall "$package_name" >/dev/null 2>&1 || true
  "${ADB[@]}" install -r "$apk" >/dev/null
}

result_file_for() {
  local name="$1"
  echo "${ARTIFACT_DIR}/${name}.properties"
}

read_result() {
  local name="$1"
  local output_file
  output_file="$(result_file_for "$name")"
  "${ADB[@]}" exec-out run-as "$PACKAGE_NAME" cat "$RESULT_FILE" > "$output_file"
  echo "$output_file"
}

broadcast_command() {
  local name="$1"
  local request_id output_file
  shift
  request_id="${name}-$(date +%s)"
  "${ADB[@]}" shell run-as "$PACKAGE_NAME" am broadcast --user 0 \
    -n "$RECEIVER_NAME" -a "$ACTION_NAME" --es requestId "$request_id" "$@" >/dev/null
  for _ in {1..60}; do
    output_file="$(read_result "$name")"
    if grep -Fq "requestId=$request_id" "$output_file"; then
      echo "$output_file"
      return
    fi
    sleep 0.25
  done
  echo "Timed out waiting for debug eval result: $request_id" >&2
  cat "$output_file" >&2 || true
  return 1
}

assert_file_contains() {
  local file="$1"
  local expected="$2"
  if ! grep -Fq "$expected" "$file"; then
    echo "Expected $file to contain: $expected" >&2
    cat "$file" >&2
    return 1
  fi
}

launch_package() {
  local package_name="$1"
  local component
  component="$("${ADB[@]}" shell cmd package resolve-activity --brief "$package_name" 2>/dev/null |
    tr -d '\r' |
    awk '/\// {line = $0} END {print line}')"
  [[ -n "$component" ]] || fail_with_reason launcher-not-found "Launcher activity not found for $package_name"
  "${ADB[@]}" shell am start -W -n "$component" >/dev/null
  sleep 1
}

run_case() {
  local case_name="$1"
  local package_name="$2"
  local app_name="$3"
  local query="$4"
  local result_hint="$5"
  local open_file observe_file tap_file type_file submit_file verify_file filter_file scroll_file

  RUN_COUNT=$((RUN_COUNT + 1))
  echo "Running mock target app case: $case_name ($package_name)"

  open_file="$(broadcast_command "${case_name}-open" --es command open_app_by_name --es appName "$app_name")"
  assert_file_contains "$open_file" "resultType=tool_result"
  assert_file_contains "$open_file" "status=Succeeded"
  assert_file_contains "$open_file" "data.toolName=open_app_by_name"
  assert_file_contains "$open_file" "data.completionState=ExternalActivityOpened"
  sleep 1

  observe_file="$(broadcast_command "${case_name}-observe" --es command observe)"
  assert_file_contains "$observe_file" "resultType=available"
  assert_file_contains "$observe_file" "packageName=$package_name"
  assert_file_contains "$observe_file" "$app_name"

  tap_file="$(broadcast_command "${case_name}-tap" --es command tap --es target "搜索入口" --el timeoutMillis 1500)"
  assert_file_contains "$tap_file" "status=Succeeded"
  assert_file_contains "$tap_file" "packageName=$package_name"

  type_file="$(broadcast_command "${case_name}-type" --es command type_text --es target "搜索输入框" --es text "$query" --el timeoutMillis 1500)"
  assert_file_contains "$type_file" "status=Succeeded"
  assert_file_contains "$type_file" "$query"

  submit_file="$(broadcast_command "${case_name}-submit" --es command submit_search --el timeoutMillis 1500)"
  assert_file_contains "$submit_file" "status=Succeeded"
  assert_file_contains "$submit_file" "$query"
  assert_file_contains "$submit_file" "$result_hint"

  verify_file="$(broadcast_command "${case_name}-verify" --es command wait --es verifySearchQuery "$query" --es expectedPackageName "$package_name" --es expectedAppName "$app_name" --el timeoutMillis 500)"
  assert_file_contains "$verify_file" "status=Succeeded"
  assert_file_contains "$verify_file" "searchVerificationStatus=verified"

  filter_file="$(broadcast_command "${case_name}-filter" --es command tap --es target "筛选" --el timeoutMillis 1500)"
  assert_file_contains "$filter_file" "status=Succeeded"
  assert_file_contains "$filter_file" "Filter applied"

  scroll_file="$(broadcast_command "${case_name}-scroll" --es command scroll --es target EvalScrollContainer --es direction down --el timeoutMillis 1500)"
  assert_file_contains "$scroll_file" "status=Succeeded"

  PASS_COUNT=$((PASS_COUNT + 1))
}

install_mock_app \
  com.taobao.taobao \
  "淘宝" \
  "搜索商品" \
  "淘宝搜索商品 EvalSearchInput" \
  "搜索" \
  "淘宝搜索 EvalSearchSubmit" \
  "综合 销量 筛选 商品列表"
install_mock_app \
  com.xunmeng.pinduoduo \
  "拼多多" \
  "多多搜索 搜索商品" \
  "拼多多搜索商品 EvalSearchInput" \
  "搜索" \
  "拼多多搜索 EvalSearchSubmit" \
  "综合 销量 筛选 百亿补贴"
install_mock_app \
  com.autonavi.minimap \
  "高德地图" \
  "搜索地点 目的地" \
  "高德地图搜索地点 EvalSearchInput" \
  "搜索" \
  "高德地图搜索 EvalSearchSubmit" \
  "路线 导航 到这去 地点列表"

run_case taobao com.taobao.taobao "淘宝" "海河牛奶" "筛选"
run_case pdd com.xunmeng.pinduoduo "拼多多" "纸巾" "百亿补贴"
run_case gaode com.autonavi.minimap "高德" "机场" "路线"

echo "Mock target app search eval passed for $PASS_COUNT case(s)."
