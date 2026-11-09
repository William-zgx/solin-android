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
fields the renderers read) and pins the rule and its three guards:

1. every generated table annotates the partly errored run and renders the fully errored one as
   `errored`, with no score in any cell of that row;
2. `summarize.py --check` passes on the regenerated folder;
3. `--check` fails when one annotation is removed from a copy of that SUMMARY.md;
4. `--check` fails when only a bare fraction is present: `83/151` must not satisfy a `3/151` count;
5. `--check` fails when a line cites two errored runs and only one is annotated;
6. `--check` fails when a generated cell is edited after generation (drift, FU-L1).

Tests 4-6 fail against the `655029cf` check (which used a bare substring test and had no drift
detection) and pass after the fix.
"""

import importlib.util
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


def load_summarize():
    """Import `summarize` by path, because `-I` keeps the cwd off `sys.path`."""
    spec = importlib.util.spec_from_file_location("summarize_under_test", SUMMARIZE)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

# The fixture keeps the real records and only changes the error state, so it exercises the real
# record shapes: Suite B text mode (M-topk-fix) and Suite C native mode (N-topk).
CASES = 6
PARTLY = "initial-planning__litert-gemma-4-e2b__M-topk-fix.jsonl"   # 2 of 6 patched to error
FULLY = "native-tools__litert-gemma-4-e2b__N-topk.jsonl"            # all 6 patched to error
# The second errored run test 5 needs: the same model and suite in another mode (1 of 6 errored),
# so one generated row cites both `M-topk-fix` and `M-topk` and needs two annotations.
SECOND = "initial-planning__litert-gemma-4-e2b__M-topk.jsonl"


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
    """Tiny folder: rules baseline, one partly errored run, a second in another mode, one fully."""
    partly = [make_error(r) if i < 2 else r
              for i, r in enumerate(read_jsonl(RESULTS / PARTLY, CASES))]
    second = [make_error(r) if i == 0 else r
              for i, r in enumerate(read_jsonl(RESULTS / SECOND, CASES))]
    fully = [make_error(r) for r in read_jsonl(RESULTS / FULLY, CASES)]
    # The rules baseline the renderers compare against; no model calls, no errors.
    rules = [r for r in read_jsonl(RESULTS / "initial-planning__rules__R.jsonl")
             if r["caseId"] in {p["caseId"] for p in partly}]
    assert len(rules) == CASES, "the fixture's rule baseline must cover the same cases"
    for name, records in ((PARTLY, partly), (SECOND, second), (FULLY, fully),
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
        if cells and all(set(cell) <= {"-"} for cell in cells):
            continue  # the `| --- | --- |` separator
        following = lines[index + 1] if index + 1 < len(lines) else ""
        if following.lstrip().startswith("| ---"):
            continue  # the header row immediately above a separator
        rows.append(line)
    return rows


def build_real_fixture(folder):
    """The real 3-of-151 run beside the rules baseline, with no synthetic records.

    `N-topk` here is the committed run the reviewer blocked on: 151 cases, 3 errored, overall
    `83/151`. A generated row for it reads `83/151 (3 errored)`, so stripping the annotation
    leaves `83/151` — which the old check's `"3/151" in line` substring test accepted, because
    `3/151` sits inside `83/151`.
    """
    names = ("native-tools__litert-gemma-4-e2b__N-topk.jsonl",
             "initial-planning__rules__R.jsonl", "manifest.json")
    for name in names:
        shutil.copyfile(RESULTS / name, folder / name)


def build_fence_fixture(folder):
    """Three real strict-gate records plus their lab-only fence outcomes.

    golden_014 unwraps to the same closed/no-tool outcome and must not appear in the delta.
    golden_023 opens into a web_search false positive; golden_035 has a rejected schedule_reminder
    proposal; bench_search_xhs_coffee opens into a wrong tool while remaining incorrect, proving the
    delta is not limited to correctness flips.
    """
    source = read_jsonl(RESULTS / "initial-planning__litert-gemma-4-e2b__R-gated-e2e.jsonl")
    case_ids = {"golden_014", "golden_023", "golden_035", "bench_search_xhs_coffee"}
    baseline = [r for r in source if r["caseId"] in case_ids]
    assert len(baseline) == len(case_ids)
    rules = [r for r in read_jsonl(RESULTS / "initial-planning__rules__R.jsonl")
             if r["caseId"] in case_ids]
    fence = []
    for prior in baseline:
        record = json.loads(json.dumps(prior))
        record["mode"] = "R-gated-e2e-fence"
        record["gateFenceUnwrapped"] = True
        record["gateRawJsonValid"] = True
        record["gateUnwrappedPreview"] = (record["gateRawPreview"]
                                                   .removeprefix("```json").removesuffix("```").strip())
        if record["caseId"] == "golden_035":
            record.update(
                decisionStage="gate open → failed",
                gateReason="local_model_action_gate_open",
                gateOpen=True,
                proposalOutcome="local_model_tool_rejected",
                modelCalls=2,
            )
            record["modelOutputs"].append({
                "phase": "proposal",
                "rawPreview": 'call:schedule_reminder {"title":"15分钟英文怎么说"}',
                "metrics": {},
            })
        elif record["caseId"] != "golden_014":
            record.update(
                decisionStage="gate open → accepted",
                planSource="model",
                gateReason="local_model_action_gate_open",
                gateOpen=True,
                rawKind="use_tool",
                correct=False,
                toolCorrect=False,
                argsExact=False,
                answer={"kind": "use_tool", "tool": "web_search", "args": {"query": "coffee"}},
                modelCalls=2,
            )
        if record["caseId"] == "golden_023":
            record.update(
                falsePositive=True,
                falsePositiveClass="device_network_external",
                fpOrchestrationOnly=False,
                fpDeviceNetworkExternal=True,
                fpPrivateRead=False,
                fpNetworkEgress=True,
                privacyRelevant=True,
                fpTool="web_search",
            )
        fence.append(record)
    for name, records in (
        ("initial-planning__litert-gemma-4-e2b__R-gated-e2e.jsonl", baseline),
        ("initial-planning__litert-gemma-4-e2b__R-gated-e2e-fence.jsonl", fence),
        ("initial-planning__rules__R.jsonl", rules),
    ):
        with (folder / name).open("w", encoding="utf-8") as handle:
            for record in records:
                handle.write(json.dumps(record, ensure_ascii=False) + "\n")
    (folder / "manifest.json").write_text(json.dumps({
        "runDate": folder.name,
        "models": {"litert/gemma-4-e2b": {"host": "devbox CPU / LiteRT-LM 0.12.0 (production .litertlm)"}},
        "suites": {},
    }), encoding="utf-8")


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
        self.assertIn("cites litert/gemma-4-e2b M-topk-fix", result.stdout)

    # ---- Guards added by D-l1fix -------------------------------------------------------------

    def test_bare_fraction_is_not_an_annotation(self):
        """`83/151` must not satisfy the 3-error count; only an exact `3 errored` token does.

        This is the committed SUMMARY.md:664 defect: the old check treated `3/151` as present
        inside `83/151` by substring, so a row with no annotation passed.
        """
        summarize = load_summarize()
        self.assertFalse(summarize.has_annotation("| E2B `N-topk` | 39/51 | 83/151 |", 151, 3))
        self.assertFalse(summarize.has_annotation("| E2B `N-topk` | 39/51 | 83/151 |", 151, 13))
        self.assertTrue(summarize.has_annotation(
            "| E2B `N-topk` | 39/51 | 83/151 (3 errored) |", 151, 3))
        # Another run's count is not this run's count.
        self.assertFalse(summarize.has_annotation("| row | 83/151 (1 errored) |", 151, 3))
        # The fully errored form: `errored N/N`, never a bare fraction. A generated table cell may
        # use that cell's population (a family or arg-key subset); prose must use the run's N.
        self.assertFalse(summarize.has_annotation("| row | 51/151 |", 151, 151))
        self.assertFalse(summarize.has_annotation("| row | errored 100/100 |", 151, 151))
        self.assertTrue(summarize.has_annotation("| row | errored 100/100 |", 151, 151, 100))
        self.assertTrue(summarize.has_annotation("| row | errored 151/151 |", 151, 151))
        self.assertTrue(summarize.has_annotation("| row | errored 151/151 |", 151, 151, 151))
        # A self-consistent sub-population is accepted in a table row only; prose needs the run's N.
        self.assertTrue(summarize.has_annotation("| row | errored 9/9 |", 151, 151, 100))
        self.assertFalse(summarize.has_annotation("| row | errored 9/9 |", 151, 151))

    def test_check_fails_on_bare_fraction_for_a_partly_errored_run(self):
        """The committed defect: strip ` (3 errored)` from the real 83/151 row; check must fail.

        `83/151` contains the characters `3/151` but is not the error count. Against `655029cf`
        this passed (exit 0), which is exactly why SUMMARY.md:664 shipped unannotated.
        """
        folder = self.tmp / "real"
        folder.mkdir()
        build_real_fixture(folder)
        self.assertEqual(run(folder).returncode, 0)
        target = folder / "SUMMARY.md"
        lines = target.read_text(encoding="utf-8").splitlines()
        for index, line in enumerate(lines):
            if line in data_rows(lines) and "N-topk" in line and "83/151 (55%) (3 errored)" in line:
                lines[index] = line.replace(" (3 errored)", "", 1)
                break
        else:
            self.fail("the real fixture has no annotated 83/151 N-topk row")
        target.write_text("\n".join(lines) + "\n", encoding="utf-8")
        result = run("--check", folder)
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("without its own error count", result.stdout)

    def test_narrative_citation_by_short_alias_with_backticks(self):
        """A backticked mode label in narrative is a citation, and needs the annotation.

        `E2B \\`N-topk\\`` is how the hand-written narrative names the run; the run has 3 errors
        of 151 and scores 83/151. The unannotated line must fail. Against `655029cf` it passed,
        because `"3/151" in "83/151"` held — the same substring bug as SUMMARY.md:664, in prose.
        """
        summarize = load_summarize()
        for line in ("| E2B `N-topk` | 39/51 | 83/151 |", "E2B `N-topk` shows 0/51 here",
                     "`litert/gemma-4-e2b` N-topk: 83/151", "E2B N-topk: 83/151"):
            self.assertTrue(summarize.cites(line, "litert/gemma-4-e2b", "N-topk"), line)
        # A different mode sharing the prefix is not this run.
        self.assertFalse(summarize.cites("E2B `N-topk-lmf` scores 104/151",
                                         "litert/gemma-4-e2b", "N-topk"))
        folder = self.tmp / "real-narrative"
        folder.mkdir()
        build_real_fixture(folder)
        self.assertEqual(run(folder).returncode, 0)
        target = folder / "SUMMARY.md"
        text = target.read_text(encoding="utf-8")
        end = text.find("GENERATED END")
        self.assertGreater(end, 0)
        marker = "GENERATED END -->"
        after = text[end + len(marker):]
        target.write_text(
            text[:end] + marker + "\n\nE2B `N-topk` scores 83/151 overall.\n" + after,
            encoding="utf-8")
        result = run("--check", folder)
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("litert/gemma-4-e2b N-topk", result.stdout)
        # The same citation with its annotation passes.
        target.write_text(
            text[:end] + marker + "\n\nE2B `N-topk` scores 83/151 overall (3 errored).\n" + after,
            encoding="utf-8")
        result = run("--check", folder)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_row_citing_two_errored_runs_needs_both_annotations(self):
        """One annotation does not cover a second errored run in the same generated row (FU-L1)."""
        copy = self.tmp / "two-runs"
        shutil.copytree(self.folder, copy)
        target = copy / "SUMMARY.md"
        lines = target.read_text(encoding="utf-8").splitlines()
        # The Results-at-a-glance table has one column per mode, so its E2B row cites both
        # M-topk-fix (2 errored) and M-topk (1 errored). Remove exactly the M-topk annotation.
        for index, line in enumerate(lines):
            cells = [cell.strip() for cell in line.strip().strip("|").split("|")]
            if (line in data_rows(lines) and "M-topk-fix" not in line
                    and any("(2 errored)" in cell for cell in cells)
                    and any("(1 errored)" in cell for cell in cells)):
                lines[index] = line.replace(" (1 errored)", "", 1).replace(
                    "(1 errored)", "", 1)
                break
        else:
            self.fail("the fixture has no row citing two errored runs")
        target.write_text("\n".join(lines) + "\n", encoding="utf-8")
        result = run("--check", copy)
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("M-topk", result.stdout)
        self.assertIn("without its own error count", result.stdout)

    def test_generated_cell_edited_after_generation_is_drift(self):
        """A hand edit inside the generated block fails as drift, even with every annotation (FU-L1)."""
        copy = self.tmp / "drift"
        shutil.copytree(self.folder, copy)
        target = copy / "SUMMARY.md"
        lines = target.read_text(encoding="utf-8").splitlines()
        start = next(i for i, line in enumerate(lines) if "GENERATED START" in line)
        end = next(i for i, line in enumerate(lines) if "GENERATED END" in line)
        # Edit one generated data cell (a score, so the line keeps all its annotations).
        for index in range(start, end):
            cells = [cell.strip() for cell in lines[index].strip().strip("|").split("|")]
            if lines[index].startswith("|") and any(
                    cell[:1].isdigit() and "/" in cell and "errored" not in cell
                    for cell in cells):
                lines[index] = lines[index].replace("/6 (", "/9 (", 1)
                self.assertNotEqual(lines[index], target.read_text(encoding="utf-8")
                                    .splitlines()[index])
                break
        else:
            self.fail("the fixture has no generated score cell to edit")
        target.write_text("\n".join(lines) + "\n", encoding="utf-8")
        result = run("--check", copy)
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("drifted", result.stdout)

    def test_narrative_line_needs_the_annotation_too(self):
        """A hand-written line citing the partly errored run by alias must be annotated."""
        copy = self.tmp / "narrative"
        shutil.copytree(self.folder, copy)
        target = copy / "SUMMARY.md"
        text = target.read_text(encoding="utf-8")
        marker = text.find("GENERATED END")
        self.assertGreater(marker, 0)
        target.write_text(text[:marker] + "GENERATED END\n\nE2B `M-topk-fix` scores 1/6 here.\n"
                          + text[marker + len("GENERATED END"):], encoding="utf-8")
        result = run("--check", copy)
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("without its own error count", result.stdout)


class FenceSummaryTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="fence-summary-"))
        self.folder = self.tmp / "20260101"
        self.folder.mkdir()
        build_fence_fixture(self.folder)
        result = run(self.folder)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.target = self.folder / "SUMMARY.md"
        self.summary = self.target.read_text(encoding="utf-8")
        self.section = self.summary.split("### R-gated-e2e-fence: lab-only single-fence gate", 1)[1]
        self.section = self.section.split("### Offline composites", 1)[0]

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_fence_section_counts_and_exact_final_outcome_delta(self):
        self.assertIn("| litert/gemma-4-e2b | 2/4 | 0/1 | 0/1 | 1/3 | 0 | 1 | 1 | 0 | 1 |",
                      self.section)
        self.assertIn("| litert/gemma-4-e2b | 0 | 0 | 1 | 2 | 0 | 1 | 3 | 4 |", self.section)
        delta = self.section.split("| model | case | input | stage before → after |", 1)[1]
        self.assertIn("golden_023", delta)
        self.assertIn("golden_035", delta)
        self.assertIn("bench_search_xhs_coffee", delta)
        self.assertNotIn("golden_014", delta)
        self.assertEqual(2, delta.count("gate closed → gate open → accepted"))
        self.assertEqual(1, delta.count("gate closed → gate open → failed"))
        self.assertIn("schedule_reminder", delta)
        self.assertIn("✓ → ✗", delta)
        self.assertIn("✓ → ✓", delta)
        self.assertIn("✗ → ✗", delta)
        result = run("--check", self.folder)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_check_detects_fence_delta_drift(self):
        self.target.write_text(self.summary.replace("✓ → ✗", "✓ → ✓", 1), encoding="utf-8")
        result = run("--check", self.folder)
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("drifted", result.stdout)


if __name__ == "__main__":
    unittest.main()
