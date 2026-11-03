#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
fixture_dir="$(mktemp -d)"
trap 'rm -rf "$fixture_dir"' EXIT
cat > "$fixture_dir/adb" <<'PY'
#!/usr/bin/env python3
import json, os, pathlib, sys
root = pathlib.Path(os.environ['BACKGROUND_HELPER_FIXTURE'])
args = sys.argv[1:]
with (root / 'calls.jsonl').open('a') as stream:
    stream.write(json.dumps(args) + '\n')
if args == ['get-state']:
    print('device')
elif args == ['shell', 'getprop', 'ro.build.version.sdk']:
    print('34')
elif args == ['shell', 'date', '+%s']:
    import time
    device_time = int(time.time()) - 3600
    (root / 'device-time').write_text(str(device_time))
    print(device_time)
elif args == ['shell', 'run-as', 'com.bytedance.zgx.solin', 'id', '-u']:
    print('10123')
elif args == ['shell', 'pm', 'path', 'com.bytedance.zgx.solin']:
    print('package:/data/app/~~abc/solin-abc/base.apk')
elif len(args) == 2 and 'cat >' in args[1]:
    (root / 'auth.json').write_text(sys.stdin.read())
elif len(args) == 2 and 'app_process' in args[1]:
    bootstrap = sys.stdin.read().splitlines()
    (root / 'stdin-token').write_text(bootstrap[0])
    (root / 'stdin-expiry').write_text(bootstrap[1])
    print('PORT=45678', flush=True)
elif len(args) == 2 and 'rm -f' in args[1]:
    (root / 'cleaned').write_text('yes')
else:
    raise SystemExit('Unexpected adb operation: ' + repr(args))
PY
chmod +x "$fixture_dir/adb"
export BACKGROUND_HELPER_FIXTURE="$fixture_dir"
ADB="$fixture_dir/adb" ANDROID_SERIAL='' bash "$repo_root/scripts/start_background_phone_helper.sh" >/dev/null
python3 - "$fixture_dir" <<'PY'
import json, pathlib, re, sys, time
root = pathlib.Path(sys.argv[1])
auth = json.loads((root / 'auth.json').read_text())
token = (root / 'stdin-token').read_text()
assert re.fullmatch('[0-9a-f]{64}', token)
assert auth['token'] == token
assert auth['port'] == 45678 and auth['uid'] == 10123
assert auth['expiresAtMs'] == int((root / 'stdin-expiry').read_text())
assert auth['expiresAtMs'] == int((root / 'device-time').read_text()) * 1000 + 900000
calls = (root / 'calls.jsonl').read_text()
assert token not in calls, 'Credential leaked into adb command arguments'
assert 'app_process' in calls
assert 'settings put' not in calls and 'input ' not in calls and 'pm grant' not in calls
assert (root / 'cleaned').is_file()
PY
before="$(wc -l < "$fixture_dir/calls.jsonl")"
if ADB="$fixture_dir/adb" bash "$repo_root/scripts/start_background_phone_helper.sh" 'com.example;id' >/dev/null 2>&1; then
  echo 'Accepted a package containing shell syntax' >&2; exit 1
fi
[[ "$(wc -l < "$fixture_dir/calls.jsonl")" == "$before" ]]
echo 'Background helper provisioning tests passed'
