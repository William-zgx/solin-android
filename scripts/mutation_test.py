#!/usr/bin/env python3
"""Deterministic, opt-in mutation testing against a disposable repository copy."""

import argparse
import fcntl
import json
import os
import shutil
import signal
import subprocess
import sys
import tempfile
import unittest
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import patch

REPO = Path(__file__).resolve().parents[1]
TEST_PATTERN = "com.bytedance.zgx.solin.common.PersistedEnumTest"
STRICT_PATTERN = "com.bytedance.zgx.solin.orchestration.collab.StrictJsonScanTest"
JSON_READERS = "app/src/main/java/com/bytedance/zgx/solin/common/JsonReaders.kt"


@dataclass(frozen=True)
class Mutation:
    name: str
    file: str
    original: str
    replacement: str


MUTATIONS = [
    Mutation("enum-null-becomes-exception", JSON_READERS, "firstOrNull { it.name == raw }", "first { it.name == raw }"),
    Mutation("enum-case-insensitive", JSON_READERS, "it.name == raw", "it.name.equals(raw, ignoreCase = true)"),
    Mutation("enum-empty-values", JSON_READERS, "enumValues<T>()", "emptyArray<T>()"),
    Mutation("json-trailing-inverted", JSON_READERS, "if (cursor.atEnd()) null else StrictJsonRejection.TrailingContent", "if (cursor.atEnd()) StrictJsonRejection.TrailingContent else null"),
    Mutation("json-object-guard-inverted", JSON_READERS, "if (cursor.current() != '{') return StrictJsonRejection.NotJsonObject", "if (cursor.current() == '{') return StrictJsonRejection.NotJsonObject"),
    Mutation("json-depth-off-by-one", JSON_READERS, "const val MAX_DEPTH = 8", "const val MAX_DEPTH = 9"),
]


class MutationError(RuntimeError):
    pass


def replace_once(text, original, replacement):
    count = text.count(original)
    if count != 1:
        raise MutationError(f"Expected exactly one occurrence of {original!r}, found {count}")
    return text.replace(original, replacement, 1)


def copy_repository(repo, destination):
    def ignore(directory, names):
        relative = Path(directory).relative_to(repo)
        if relative == Path("."):
            return {".git", ".gradle", ".serena", ".claude", "build"}
        if relative.parent == Path("."):
            return {"build"}
        return set()
    shutil.copytree(repo, destination, ignore=ignore)


def campaign_lock(repo):
    path = repo / "build/verification/mutation/.lock"
    path.parent.mkdir(parents=True, exist_ok=True)
    handle = path.open("w")
    fcntl.flock(handle, fcntl.LOCK_EX)
    return handle


def ensure_source_unchanged(run_command, repo):
    result = run_command(["git", "-C", str(repo), "diff", "--quiet", "HEAD"], capture_output=True, text=True)
    if result.returncode != 0:
        raise MutationError("Tracked sources changed during the campaign")


def ensure_clean(run_command, repo):
    result = run_command(["git", "-C", str(repo), "status", "--porcelain"], capture_output=True, text=True)
    if result.returncode != 0 or result.stdout.strip():
        raise MutationError("Mutation testing requires a clean Git worktree")


def ensure_no_active_gradle(run_command):
    result = run_command(["pgrep", "-f", "GradleDaemon|GradleWrapperMain"], capture_output=True, text=True)
    if result.returncode == 0:
        raise MutationError("A Gradle process is already running; stop it before mutation testing")
    if result.returncode != 1:
        raise MutationError(f"Unable to inspect Gradle processes: {result.stderr}")


def test_command():
    return [
        "./gradlew",
        ":app:testDebugUnitTest",
        "--tests", TEST_PATTERN,
        "--tests", STRICT_PATTERN,
        "-x", ":app:downloadZvecNativeLibs",
        "--console=plain",
        "--no-daemon",
    ]


def run_tests(folder, popen_factory=subprocess.Popen):
    env = os.environ.copy()
    if not env.get("JAVA_HOME") or not (env.get("ANDROID_SDK_ROOT") or env.get("ANDROID_HOME")):
        raise MutationError("Set JAVA_HOME and ANDROID_SDK_ROOT or ANDROID_HOME before the campaign")
    process = popen_factory(test_command(), cwd=folder, env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            text=True, start_new_session=True)
    try:
        stdout, stderr = process.communicate(timeout=300)
        return subprocess.CompletedProcess(test_command(), process.returncode, stdout=stdout, stderr=stderr)
    except subprocess.TimeoutExpired as error:
        for sig in (signal.SIGTERM, signal.SIGKILL):
            try:
                os.killpg(process.pid, sig)
            except ProcessLookupError:
                break
            try:
                process.communicate(timeout=5)
                break
            except subprocess.TimeoutExpired:
                continue
        raise MutationError(f"Focused test run timed out: {error}")


def mutant_killed(result):
    output = f"{result.stdout}\n{result.stderr}"
    if result.returncode == 0:
        return False
    if "tests completed" in output and "FAILED" in output:
        return True
    raise MutationError(f"Mutant run did not execute focused tests:\n{output}")


def write_report(path, status, **fields):
    payload = {"schema": "SolinMutationReport/v2", "status": status,
               "recordedAtUtc": datetime.now(timezone.utc).isoformat(), **fields}
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2) + "\n")


def run_campaign(run_command=subprocess.run, repo=REPO, report_path=None, popen_factory=subprocess.Popen):
    with campaign_lock(repo):
        ensure_clean(run_command, repo)
        ensure_no_active_gradle(run_command)
        report_path = report_path or repo / "build/verification/mutation/mutation-report.json"
        head = run_command(["git", "-C", str(repo), "rev-parse", "HEAD"], capture_output=True, text=True)
        git_head = head.stdout.strip() or "unknown"
        write_report(report_path, "running", gitHead=git_head)
        report = {}
        try:
            with tempfile.TemporaryDirectory(prefix="solin-mutation-") as temporary:
                copied = Path(temporary) / "repo"
                copy_repository(repo, copied)
                ensure_source_unchanged(run_command, repo)
                baseline = run_tests(copied, popen_factory)
                if baseline.returncode != 0:
                    raise MutationError(f"Baseline test run failed:\n{baseline.stdout}\n{baseline.stderr}")
                original_sources = {
                    filename: (copied / filename).read_text()
                    for filename in {mutation.file for mutation in MUTATIONS}
                }
                results = []
                for mutation in MUTATIONS:
                    target = copied / mutation.file
                    target.write_text(replace_once(original_sources[mutation.file], mutation.original, mutation.replacement))
                    result = run_tests(copied, popen_factory)
                    results.append({"name": mutation.name, "killed": mutant_killed(result)})
                report = {
                    "baseline": "passed",
                    "killed": sum(result["killed"] for result in results),
                    "survived": sum(not result["killed"] for result in results),
                    "results": results,
                }
                if report["survived"]:
                    raise MutationError(f"Surviving mutants: {report['survived']} (see {report_path})")
                write_report(report_path, "passed", gitHead=git_head, **report)
                ensure_source_unchanged(run_command, repo)
                return report
        except MutationError as error:
            write_report(report_path, "failed", gitHead=git_head, error=str(error), **report)
            raise


class MutationTest(unittest.TestCase):
    class FakeProcess:
        def __init__(self, args, returncode, stdout="", stderr=""):
            self.args = args
            self.returncode = returncode
            self.stdout = stdout
            self.stderr = stderr
            self.pid = 4321

        def communicate(self, timeout=None):
            return self.stdout, self.stderr

    def assert_campaign_locked(self, repo):
        with (repo / "build/verification/mutation/.lock").open("w") as probe:
            with self.assertRaises(BlockingIOError):
                fcntl.flock(probe, fcntl.LOCK_EX | fcntl.LOCK_NB)

    def test_replace_once_requires_unique_pattern(self):
        with self.assertRaises(MutationError):
            replace_once("x x", "x", "y")
        self.assertEqual("y z", replace_once("x z", "x", "y"))

    def test_dirty_tree_and_active_gradle_fail_closed(self):
        def dirty(_args, **_kwargs):
            return subprocess.CompletedProcess([], 0, stdout=" M file\n", stderr="")
        with self.assertRaises(MutationError):
            ensure_clean(dirty, Path("repo"))

        def active(_args, **_kwargs):
            return subprocess.CompletedProcess([], 0, stdout="123\n", stderr="")
        with self.assertRaises(MutationError):
            ensure_no_active_gradle(active)

        def probe_error(_args, **_kwargs):
            return subprocess.CompletedProcess([], 2, stdout="", stderr="probe failed")
        with self.assertRaises(MutationError):
            ensure_no_active_gradle(probe_error)

    def test_copy_excludes_generated_outputs_but_not_source_build_packages(self):
        with tempfile.TemporaryDirectory() as temp:
            repo = Path(temp) / "repo"
            generated = repo / "modelpackE2b/build/generated.bin"
            generated.parent.mkdir(parents=True)
            generated.write_text("generated")
            app_output = repo / "app/build/output.bin"
            app_output.parent.mkdir(parents=True)
            app_output.write_text("generated")
            source = repo / "app/src/main/java/build/Source.kt"
            source.parent.mkdir(parents=True)
            source.write_text("source")
            copied = Path(temp) / "copied"

            copy_repository(repo, copied)

            self.assertFalse((copied / "modelpackE2b/build").exists())
            self.assertFalse((copied / "app/build").exists())
            self.assertEqual("source", (copied / "app/src/main/java/build/Source.kt").read_text())

    @patch.dict(os.environ, {"JAVA_HOME": "/jdk", "ANDROID_SDK_ROOT": "/sdk"})
    def test_campaign_kills_mutants_without_touching_primary(self):
        gradle_calls = []
        repo = None

        def fake_run(args, **_kwargs):
            self.assert_campaign_locked(repo)
            if args[0] == "pgrep":
                return subprocess.CompletedProcess(args, 1, stdout="", stderr="")
            return subprocess.CompletedProcess(args, 0, stdout="", stderr="")

        def fake_popen(args, **_kwargs):
            self.assert_campaign_locked(repo)
            gradle_calls.append(args)
            code = 0 if len(gradle_calls) == 1 else 1
            return self.FakeProcess(args, code, stdout="1 tests completed, 1 failed\nFooTest > test FAILED")

        with tempfile.TemporaryDirectory() as temp:
            repo = Path(temp) / "primary"
            source = repo / JSON_READERS
            source.parent.mkdir(parents=True)
            source.write_text((REPO / JSON_READERS).read_text())
            original = source.read_text()
            report = run_campaign(fake_run, repo=repo, report_path=repo / "report.json", popen_factory=fake_popen)
            self.assertEqual(len(MUTATIONS), report["killed"])
            self.assertEqual(0, report["survived"])
            self.assertEqual(original, source.read_text())
            self.assertTrue(any(call[0] == "./gradlew" for call in gradle_calls))

    @patch.dict(os.environ, {"JAVA_HOME": "/jdk", "ANDROID_SDK_ROOT": "/sdk"})
    def test_survivor_is_a_failure(self):
        repo = None

        def fake_run(args, **_kwargs):
            self.assert_campaign_locked(repo)
            code = 1 if args[0] == "pgrep" else 0
            return subprocess.CompletedProcess(args, code, stdout="", stderr="")

        def fake_popen(args, **_kwargs):
            self.assert_campaign_locked(repo)
            return self.FakeProcess(args, 0)

        with tempfile.TemporaryDirectory() as temp:
            repo = Path(temp) / "primary"
            source = repo / JSON_READERS
            source.parent.mkdir(parents=True)
            source.write_text((REPO / JSON_READERS).read_text())
            with self.assertRaises(MutationError):
                run_campaign(fake_run, repo=repo, report_path=repo / "report.json", popen_factory=fake_popen)
            self.assertEqual("failed", json.loads((repo / "report.json").read_text())["status"])

    @patch.dict(os.environ, {"JAVA_HOME": "/jdk", "ANDROID_SDK_ROOT": "/sdk"})
    def test_timeout_escalates_with_bounded_grace_periods(self):
        responses = [
            subprocess.TimeoutExpired(cmd="gradle", timeout=300),
            subprocess.TimeoutExpired(cmd="gradle", timeout=5),
            ("", ""),
        ]
        timeouts = []

        class TimeoutProcess:
            pid = 4321
            returncode = -signal.SIGKILL

            def communicate(self, timeout=None):
                timeouts.append(timeout)
                response = responses.pop(0)
                if isinstance(response, Exception):
                    raise response
                return response

        with patch("os.killpg") as killpg:
            with self.assertRaises(MutationError):
                run_tests(Path("."), popen_factory=lambda *_args, **_kwargs: TimeoutProcess())

        self.assertEqual([300, 5, 5], timeouts)
        self.assertEqual([signal.SIGTERM, signal.SIGKILL], [call.args[1] for call in killpg.call_args_list])
        self.assertTrue(all(call.args[0] == 4321 for call in killpg.call_args_list))

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-test", action="store_true", help="run lightweight runner tests without Gradle")
    parser.add_argument("--run", action="store_true", help="run the full mutation campaign")
    args = parser.parse_args()
    if args.self_test:
        unittest.main(argv=[sys.argv[0]], exit=True)
    elif args.run:
        try:
            result = run_campaign()
            print(f"ok: {result['killed']} killed, {result['survived']} survived")
        except MutationError as error:
            print(f"error: {error}", file=sys.stderr)
            sys.exit(1)
    else:
        parser.print_help()
