#!/usr/bin/env python3
"""Phone task benchmark: success rate, steps, and latency on a real device.

Runs every task in docs/phone_task_benchmark.json through the debug-build prompt hook
(MainActivity EXTRA_DEBUG_PROMPT), reads the finished run from the debug
AgentRunReportReceiver, checks the task expectations against the run report and the
device screen, and writes one JSON line per task plus a summary.

The run report never contains the prompt or tool arguments. Screen text is only used
for matching on the host and is not written to the results file.

Usage:
  python3 -I scripts/run_phone_task_benchmark.py --dry-run
  ANDROID_SERIAL=<serial> python3 -I scripts/run_phone_task_benchmark.py [--only id,id] [--category c]
"""

import argparse
import json
import os
import re
import shlex
import statistics
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_TASKS = ROOT / "docs" / "phone_task_benchmark.json"
PACKAGE = "com.bytedance.zgx.solin"
ACTIVITY = f"{PACKAGE}/.MainActivity"
REPORT_RECEIVER = f"{PACKAGE}/.debug.AgentRunReportReceiver"
PROMPT_EXTRA = f"{PACKAGE}.extra.DEBUG_PROMPT"
TERMINAL_STATES = {"Completed", "Cancelled", "Failed"}
PAUSED_STATES = {"AwaitingUserConfirmation", "AwaitingUserAnswer", "AwaitingExternalOutcome"}
EXPECT_KEYS = {
    "foregroundPackage", "screenContainsAny", "toolsInclude", "toolsIncludeAny",
    "noToolFailed", "states", "toolCallsAtMost",
}


def load_tasks(path):
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    tasks = data.get("tasks")
    if not isinstance(tasks, list) or not tasks:
        raise ValueError("tasks must be a non-empty list")
    seen = set()
    for task in tasks:
        for key in ("id", "category", "prompt", "expect"):
            if not task.get(key):
                raise ValueError(f"task {task.get('id')!r} is missing {key}")
        if task["id"] in seen:
            raise ValueError(f"duplicate task id {task['id']}")
        seen.add(task["id"])
        unknown = set(task["expect"]) - EXPECT_KEYS
        if unknown:
            raise ValueError(f"task {task['id']} has unknown expectations {sorted(unknown)}")
    return tasks


class Device:
    def __init__(self, adb, serial):
        self.base = [adb] + (["-s", serial] if serial else [])

    def shell(self, command, timeout=30):
        result = subprocess.run(
            self.base + ["shell", command], capture_output=True, text=True, timeout=timeout,
        )
        return result.stdout

    def installed_packages(self):
        return {
            line.split(":", 1)[1].strip()
            for line in self.shell("pm list packages").splitlines()
            if line.startswith("package:")
        }

    def run_report(self):
        out = self.shell(f"am broadcast -n {REPORT_RECEIVER}")
        match = re.search(r'data="(.*)"\s*$', out.strip(), re.S)
        if not match:
            return None
        try:
            return json.loads(match.group(1))
        except json.JSONDecodeError:
            return None

    def foreground_package(self):
        out = self.shell("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'")
        match = re.search(r"\s([a-zA-Z0-9_.]+)/", out)
        return match.group(1) if match else None

    def screen_text(self):
        self.shell("uiautomator dump /sdcard/solin_benchmark_ui.xml", timeout=30)
        xml = self.shell("cat /sdcard/solin_benchmark_ui.xml")
        self.shell("rm -f /sdcard/solin_benchmark_ui.xml")
        return " ".join(re.findall(r'(?:text|content-desc)="([^"]*)"', xml))

    def reset(self, packages):
        for package in packages:
            self.shell(f"am force-stop {shlex.quote(package)}")
        self.shell("input keyevent KEYCODE_HOME")

    def send_prompt(self, prompt):
        self.shell(f"am start -n {ACTIVITY} --es {PROMPT_EXTRA} {shlex.quote(prompt)}")


def wait_for_run(device, previous_run_id, timeout_s, poll_s=1.5):
    deadline = time.monotonic() + timeout_s
    latest = None
    while time.monotonic() < deadline:
        report = device.run_report()
        if report and report.get("runId") and report.get("runId") != previous_run_id:
            latest = report
            if report.get("state") in TERMINAL_STATES | PAUSED_STATES:
                return report, False
        time.sleep(poll_s)
    return latest, True


def evaluate(expect, report, foreground, screen):
    failures = []
    tools = [call.get("tool") for call in (report or {}).get("toolCalls", [])]
    state = (report or {}).get("state")
    if report is None:
        return ["no_run_report"]
    if "foregroundPackage" in expect and foreground != expect["foregroundPackage"]:
        failures.append("foreground_package")
    if "screenContainsAny" in expect and not any(t in screen for t in expect["screenContainsAny"]):
        failures.append("screen_text")
    if "toolsInclude" in expect and not all(t in tools for t in expect["toolsInclude"]):
        failures.append("tools_include")
    if "toolsIncludeAny" in expect and not any(t in tools for t in expect["toolsIncludeAny"]):
        failures.append("tools_include_any")
    if expect.get("noToolFailed") and any(
        call.get("status") == "Failed" for call in report.get("toolCalls", [])
    ):
        failures.append("tool_failed")
    if "states" in expect and state not in expect["states"]:
        failures.append("state")
    if "toolCallsAtMost" in expect and report.get("toolCallCount", 0) > expect["toolCallsAtMost"]:
        failures.append("too_many_tool_calls")
    if state == "Failed":
        failures.append("run_failed")
    return failures


def summarize(results):
    ran = [r for r in results if not r["skipped"]]
    passed = [r for r in ran if r["passed"]]
    durations = [r["durationMillis"] for r in ran if r.get("durationMillis") is not None]
    categories = {}
    for r in ran:
        bucket = categories.setdefault(r["category"], {"ran": 0, "passed": 0})
        bucket["ran"] += 1
        bucket["passed"] += 1 if r["passed"] else 0
    return {
        "tasks": len(results),
        "ran": len(ran),
        "skipped": len(results) - len(ran),
        "passed": len(passed),
        "successRate": round(len(passed) / len(ran), 3) if ran else None,
        "medianDurationMillis": int(statistics.median(durations)) if durations else None,
        "meanToolCalls": round(statistics.mean(r["toolCallCount"] for r in ran), 2) if ran else None,
        "confirmationsRequested": sum(r["confirmationsRequested"] for r in ran),
        "criticalControlStops": sum(r["criticalControlStops"] for r in ran),
        "timeouts": sum(1 for r in ran if r["timedOut"]),
        "byCategory": categories,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--tasks", default=str(DEFAULT_TASKS))
    parser.add_argument("--only", default="", help="comma-separated task ids")
    parser.add_argument("--category", default="")
    parser.add_argument("--timeout", type=float, default=120.0, help="seconds per task")
    parser.add_argument("--settle", type=float, default=2.0, help="seconds to wait before reading the screen")
    parser.add_argument("--out", default="")
    parser.add_argument("--dry-run", action="store_true", help="validate the task file only")
    args = parser.parse_args()

    tasks = load_tasks(args.tasks)
    if args.only:
        wanted = set(args.only.split(","))
        tasks = [t for t in tasks if t["id"] in wanted]
    if args.category:
        tasks = [t for t in tasks if t["category"] == args.category]
    if args.dry_run:
        print(f"ok: {len(tasks)} tasks")
        return 0

    sdk = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME") or ""
    adb = str(Path(sdk) / "platform-tools" / "adb") if sdk else "adb"
    device = Device(adb, os.environ.get("ANDROID_SERIAL"))
    installed = device.installed_packages()
    if PACKAGE not in installed:
        print(f"error: {PACKAGE} (debug build) is not installed", file=sys.stderr)
        return 2

    out_dir = Path(args.out) if args.out else ROOT / "build" / "verification" / (
        "phone-task-benchmark-" + time.strftime("%Y%m%d-%H%M%S")
    )
    out_dir.mkdir(parents=True, exist_ok=True)
    results = []
    with open(out_dir / "results.jsonl", "w", encoding="utf-8") as sink:
        for task in tasks:
            required = task.get("requiredPackages", [])
            missing = [p for p in required if p not in installed]
            result = {"id": task["id"], "category": task["category"], "skipped": bool(missing)}
            if missing:
                result["missingPackages"] = missing
            else:
                device.reset(required)
                previous = (device.run_report() or {}).get("runId")
                started = time.monotonic()
                device.send_prompt(task["prompt"])
                report, timed_out = wait_for_run(device, previous, args.timeout)
                wall_ms = int((time.monotonic() - started) * 1000)
                time.sleep(args.settle)
                foreground = device.foreground_package()
                screen = device.screen_text() if "screenContainsAny" in task["expect"] else ""
                failures = evaluate(task["expect"], report, foreground, screen)
                if timed_out:
                    failures.append("timeout")
                report = report or {}
                result.update({
                    "passed": not failures,
                    "failures": failures,
                    "state": report.get("state"),
                    "timedOut": timed_out,
                    "durationMillis": report.get("durationMillis"),
                    "wallMillis": wall_ms,
                    "toolCallCount": report.get("toolCallCount", 0),
                    "tools": [c.get("tool") for c in report.get("toolCalls", [])],
                    "confirmationsRequested": report.get("confirmationsRequested", 0),
                    "questionsAsked": report.get("questionsAsked", 0),
                    "criticalControlStops": report.get("criticalControlStops", 0),
                    "placement": report.get("placement"),
                    "foregroundPackage": foreground,
                })
            results.append(result)
            sink.write(json.dumps(result, ensure_ascii=False) + "\n")
            sink.flush()
            status = "skip" if result["skipped"] else ("PASS" if result["passed"] else "FAIL")
            print(f"{status} {task['id']} {result.get('failures', '')}")

    summary = summarize(results)
    (out_dir / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    print(f"results: {out_dir}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
