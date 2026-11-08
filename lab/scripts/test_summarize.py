#!/usr/bin/env python3
"""Regression test for Decision L1 in `summarize.py` (stdlib `unittest`, no new dependency).

Run with: `python3 -I lab/scripts/test_summarize.py` (isolated mode, as the other lab scripts are),
or `python3 -m unittest lab/scripts/test_summarize.py` without `-I`.

Note: `python3 -I -m unittest lab/scripts/test_summarize.py` cannot work on this Python. `-I` is
`-E -s`: it drops the cwd from `sys.path` and ignores `PYTHONPATH`, so unittest cannot import a
path-qualified module, and the run fails with `No module named 'lab'` before any test executes.
Running the file as a script keeps the isolation (`-I` still blocks cwd imports by the test) and
runs all four tests.

Decision L1: every number in SUMMARY.md derived from a run with at least one errored case carries
that run's error count in the same cell or sentence; a fully errored run shows `errored N/N` and no
score. This test builds a tiny fixture folder from committed lab JSONL (so every record has the
fields the renderers read) and pins three things:

1. every generated table annotates the partly errored run and renders the fully errored one as
   `errored`, with no score in any cell of that row;
2. `summarize.py --check` passes on the regenerated folder;
3. `--check` fails when one annotation is removed from a copy of that SUMMARY.md.
"""

import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SUMMARIZE = HERE / "summarize.py"
RESULTS = HERE.parent / "results" / "20261108"

# The fixture keeps the real records and only changes the error state, so it exercises the real
# record shapes: Suite B text mode (M-topk-fix) and Suite C native mode (N-topk).
CASES = 6
PARTLY = "initial-planning__litert-gemma-4-e2b__M-topk-fix.jsonl"   # 2 of 6 patched to error
FULLY = "native-tools__litert-gemma-4-e2b__N-topk.jsonl"            # all 6 patched to error


def read_jsonl(path, count=None):
    records = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    return records[:count] if count else records


def make_error(record):
    """The shape `OllamaClient` writes for a failed LiteRT call (see the real 20261108 records)."""
    record = json.loads(json.dumps(record))
    record["rawKind"] = "error"
    record["correct"] = False
    record["toolCorrect"] = False
    record["argsExact"] = False
    record["falsePositive"] = False
    record["answer"] = {"kind": "invalid", "tool": None, "args": {}}
    record["metrics"] = dict(record.get("metrics") or {},
                             error="IOException:ollama_error:litert_error:send_message failed")
    return record


def build_fixture(folder):
    """Tiny folder: rules baseline + one partly errored run + one fully errored run."""
    partly = [make_error(r) if i < 2 else r
              for i, r in enumerate(read_jsonl(RESULTS / PARTLY, CASES))]
    fully = [make_error(r) for r in read_jsonl(RESULTS / FULLY, CASES)]
    # The rules baseline the renderers compare against; no model calls, no errors.
    rules = [r for r in read_jsonl(RESULTS / "initial-planning__rules__R.jsonl")
             if r["caseId"] in {p["caseId"] for p in partly}]
    assert len(rules) == CASES, "the fixture's rule baseline must cover the same cases"
    for name, records in ((PARTLY, partly), (FULLY, fully),
                          ("initial-planning__rules__R.jsonl", rules)):
        with (folder / name).open("w", encoding="utf-8") as handle:
            for record in records:
                handle.write(json.dumps(record, ensure_ascii=False) + "\n")


def run(*args):
    return subprocess.run([sys.executable, "-I", str(SUMMARIZE), *map(str, args)],
                          capture_output=True, text=True)


def data_rows(lines):
    """Markdown table data rows: every `| … |` line that is not a header or a `---` separator."""
    rows = []
    for index, line in enumerate(lines):
        if not line.lstrip().startswith("|"):
            continue
        cells = [cell.strip() for cell in line.strip().strip("|").split("|")]
        if cells and all(set(cell) <= {"-"} and cell for cell in cells):
            continue  # the `| --- | --- |` separator
        following = lines[index + 1] if index + 1 < len(lines) else ""
        if following.lstrip().startswith("| ---"):
            continue  # the header row immediately above a separator
        rows.append(line)
    return rows


class DecisionL1Test(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="l1-fixture-"))
        self.folder = self.tmp / "20260101"
        self.folder.mkdir()
        build_fixture(self.folder)
        result = run(self.folder)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.summary = (self.folder / "SUMMARY.md").read_text(encoding="utf-8")
        self.lines = self.summary.splitlines()
        self.rows = data_rows(self.lines)

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_every_table_annotates_both_runs(self):
        partly = [line for line in self.rows if "M-topk-fix" in line]
        self.assertTrue(partly, "no M-topk-fix rows generated")
        for line in partly:
            self.assertIn("2 errored", line, f"partly errored row lacks its count: {line}")

    def test_fully_errored_run_shows_no_score(self):
        # No row of the fully errored run may carry a `k/n (pct%)` score: a failed call answered
        # nothing, so there is nothing to score (the 270M overflow once read `51/151`).
        fully = [line for line in self.rows if "N-topk" in line and "M-topk-fix" not in line]
        self.assertTrue(fully, "no N-topk rows generated")
        for line in fully:
            self.assertIn("errored", line, f"fully errored row lacks `errored`: {line}")
            self.assertNotRegex(line, r"\d+/\d+ \(\d+%\)",
                                f"fully errored row still shows a score: {line}")

    def test_check_passes_on_regenerated_folder(self):
        result = run("--check", self.folder)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("ok:", result.stdout)

    def test_check_fails_when_an_annotation_is_removed(self):
        copy = self.tmp / "unannotated"
        shutil.copytree(self.folder, copy)
        target = copy / "SUMMARY.md"
        lines = target.read_text(encoding="utf-8").splitlines()
        # Tamper with exactly one partly errored data row: strip every annotation from that row,
        # which is the defect the reviewers blocked on (a bare score for an errored run).
        for index, line in enumerate(lines):
            if line in data_rows(lines) and "M-topk-fix" in line and "2 errored" in line:
                stripped = (line.replace(" (2 errored)", "")
                                .replace("(errored 2/2)", "errored"))
                self.assertNotEqual(stripped, line, "nothing to strip on the chosen row")
                lines[index] = stripped
                break
        else:
            self.fail("the fixture has no annotated M-topk-fix data row to tamper with")
        target.write_text("\n".join(lines) + "\n", encoding="utf-8")
        result = run("--check", copy)
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("without its error count", result.stdout)


if __name__ == "__main__":
    unittest.main()
