#!/usr/bin/env python3
"""Deterministic, opt-in mutation testing against a disposable repository copy."""

import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from dataclasses import dataclass
from pathlib import Path
from unittest.mock import patch

REPO = Path(__file__).resolve().parents[1]
TEST_PATTERN = "com.bytedance.zgx.solin.common.PersistedEnumTest"
STRICT_PATTERN = "com.bytedance.zgx.solin.orchestration.collab.StrictJsonScanTest"
JSON_READERS = "app/src/main/java/com/bytedance/zgx/solin/common/JsonReaders.kt"
SCAN = "app/src/main/java/com/bytedance/zgx/solin/common/JsonReaders.kt"


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
    Mutation("json-trailing-inverted", SCAN, "if (cursor.atEnd()) null else StrictJsonRejection.TrailingContent", "if (cursor.atEnd()) StrictJsonRejection.TrailingContent else null"),
    Mutation("json-object-guard-inverted", SCAN, "if (cursor.current() != '{') return StrictJsonRejection.NotJsonObject", "if (cursor.current() == '{') return StrictJsonRejection.NotJsonObject"),
    Mutation("json-depth-off-by-one", SCAN, "const val MAX_DEPTH = 8", "const val MAX_DEPTH = 0"),
]


class MutationError(RuntimeError):
    pass


def replace_once(text, original, replacement):
    count = text.count(original)
    if count != 1:
        raise MutationError(f"Expected exactly one occurrence of {original!r}, found {count}")
    return text.replace(original, replacement, 1)


def copy_repository(repo, destination):
    ignored = shutil.ignore_patterns(".git", ".gradle", ".serena", ".claude", "build", "app/build", "releaseSmoke/build")
    shutil.copytree(repo, destination, ignore=ignored)


def ensure_clean(run_command):
    result = run_command(["git", "status", "--porcelain"], capture_output=True, text=True)
    if result.returncode != 0 or result.stdout.strip():
        raise MutationError("Mutation testing requires a clean Git worktree")


def ensure_no_active_gradle(run_command):
    result = run_command(["pgrep", "-f", "GradleDaemon|GradleWrapperMain"], capture_output=True, text=True)
    if result.returncode == 0:
        raise MutationError("A Gradle process is already running; stop it before mutation testing")


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


def run_tests(run_command, folder):
    env = os.environ.copy()
    if not env.get("JAVA_HOME") or not (env.get("ANDROID_SDK_ROOT") or env.get("ANDROID_HOME")):
        raise MutationError("Set JAVA_HOME and ANDROID_SDK_ROOT or ANDROID_HOME before the campaign")
    return run_command(test_command(), cwd=folder, env=env, capture_output=True, text=True, timeout=300)


def run_campaign(run_command=subprocess.run, repo=REPO, report_path=None):
    ensure_clean(run_command)
    ensure_no_active_gradle(run_command)
    report_path = report_path or repo / "build/verification/mutation/mutation-report.json"
    with tempfile.TemporaryDirectory(prefix="solin-mutation-") as temporary:
        copied = Path(temporary) / "repo"
        copy_repository(repo, copied)
        baseline = run_tests(run_command, copied)
        if baseline.returncode != 0:
            raise MutationError(f"Baseline test run failed:\n{baseline.stdout}\n{baseline.stderr}")
        original_sources = {mutation.file: (copied / mutation.file).read_text() for mutation in MUTATIONS}
        results = []
        for mutation in MUTATIONS:
            target = copied / mutation.file
            target.write_text(replace_once(original_sources[mutation.file], mutation.original, mutation.replacement))
            result = run_tests(run_command, copied)
            results.append({"name": mutation.name, "killed": result.returncode != 0})
        report = {
            "schema": "SolinMutationReport/v1",
            "baseline": "passed",
            "killed": sum(result["killed"] for result in results),
            "survived": sum(not result["killed"] for result in results),
            "results": results,
        }
        report_path.parent.mkdir(parents=True, exist_ok=True)
        report_path.write_text(json.dumps(report, indent=2) + "\n")
        if report["survived"]:
            raise MutationError(f"Surviving mutants: {report['survived']} (see {report_path})")
        return report


class MutationTest(unittest.TestCase):
    def test_replace_once_requires_unique_pattern(self):
        with self.assertRaises(MutationError):
            replace_once("x x", "x", "y")
        self.assertEqual("y z", replace_once("x z", "x", "y"))

    def test_dirty_tree_and_active_gradle_fail_closed(self):
        def dirty(_args, **_kwargs):
            return subprocess.CompletedProcess([], 0, stdout=" M file\n", stderr="")
        with self.assertRaises(MutationError):
            ensure_clean(dirty)

        def active(_args, **_kwargs):
            return subprocess.CompletedProcess([], 0, stdout="123\n", stderr="")
        with self.assertRaises(MutationError):
            ensure_no_active_gradle(active)

    @patch.dict(os.environ, {"JAVA_HOME": "/jdk", "ANDROID_SDK_ROOT": "/sdk"})
    def test_campaign_kills_mutants_without_touching_primary(self):
        gradle_calls = []

        def fake_run(args, **kwargs):
            if args[0] == "git":
                return subprocess.CompletedProcess(args, 0, stdout="", stderr="")
            if args[0] == "pgrep":
                return subprocess.CompletedProcess(args, 1, stdout="", stderr="")
            if args[0] == "./gradlew":
                gradle_calls.append(args)
                code = 0 if len(gradle_calls) == 1 else 1
                return subprocess.CompletedProcess(args, code, stdout="mutant failed", stderr="")
            return subprocess.CompletedProcess(args, 0, stdout="ok", stderr="")

        with tempfile.TemporaryDirectory() as temp:
            repo = Path(temp) / "primary"
            source = repo / JSON_READERS
            source.parent.mkdir(parents=True)
            source.write_text((REPO / JSON_READERS).read_text())
            original = source.read_text()
            report = run_campaign(fake_run, repo=repo, report_path=repo / "report.json")
            self.assertEqual(len(MUTATIONS), report["killed"])
            self.assertEqual(0, report["survived"])
            self.assertEqual(original, source.read_text())
            self.assertTrue(any(call[0] == "./gradlew" for call in gradle_calls))

    @patch.dict(os.environ, {"JAVA_HOME": "/jdk", "ANDROID_SDK_ROOT": "/sdk"})
    def test_survivor_is_a_failure(self):
        def fake_run(args, **kwargs):
            if args[0] == "git":
                return subprocess.CompletedProcess(args, 0, stdout="", stderr="")
            if args[0] == "pgrep":
                return subprocess.CompletedProcess(args, 1, stdout="", stderr="")
            return subprocess.CompletedProcess(args, 0, stdout="ok", stderr="")

        with tempfile.TemporaryDirectory() as temp:
            repo = Path(temp) / "primary"
            source = repo / JSON_READERS
            source.parent.mkdir(parents=True)
            source.write_text((REPO / JSON_READERS).read_text())
            with self.assertRaises(MutationError):
                run_campaign(fake_run, repo=repo, report_path=repo / "report.json")


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
