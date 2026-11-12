#!/usr/bin/env python3
"""Summarize one lab run: lab/results/<YYYYMMDD>/*.jsonl -> SUMMARY.md and failure_examples.md.

Standard library only. Run with: python3 -I lab/scripts/summarize.py lab/results/<YYYYMMDD>
Audit the Decision L1 annotation with: python3 -I lab/scripts/summarize.py --check <folder>

The tables in SUMMARY.md sit between two GENERATED markers. Text outside the markers is hand-written
(findings, conclusions) and survives a rerun of this script.
"""

import json
import math
import re
import statistics
import sys
from collections import Counter, OrderedDict, defaultdict
from decimal import Decimal, InvalidOperation
from pathlib import Path

SUITE_A = "observation-step"
SUITE_B = "initial-planning"
SUITE_C = "native-tools"
SUITE_D = "routing"
SUITE_E = "action-gate"
SUITE_LETTER = {SUITE_A: "A", SUITE_B: "B", SUITE_C: "C", SUITE_D: "D", SUITE_E: "E"}
LITERT_HOST_LABEL = "devbox CPU / LiteRT-LM 0.12.0 (production .litertlm)"
MODE_ORDER = {
    SUITE_A: ["replanner", "replanner-fix"],
    # `*-lmf` modes mirror production LocalModelFirst: the retrieved top-k plus the `answer`
    # no-tool choice spec (orchestration/InitialToolPlanner.kt:43,52-60).
    SUITE_B: ["R", "M-full", "M-full-fix", "M-topk", "M-topk-fix", "M-topk-fix-lmf", "R+M",
              "R-gated-e2e", "R-gated-e2e-fence"],
    SUITE_C: ["N-full", "N-topk", "N-topk-free", "N-topk-lmf"],
    SUITE_D: ["complexity", "json-prompt", "json-schema"],
    SUITE_E: ["json-prompt"],
}
RULE_MODELS = ("rules",)
EXAMPLES_PER_GROUP = 3
PREVIEW_CHARS = 220
GENERATED_START = "<!-- summarize.py: GENERATED START (edit outside these markers) -->"
GENERATED_END = "<!-- summarize.py: GENERATED END -->"

# Suites B/C case families, derived from the gold first tool so every source is grouped the same way.
TOOL_FAMILY = {
    "open_app_by_name": "launch app",
    "open_camera": "launch app",
    "open_deep_link": "launch app",
    "open_system_settings": "settings",
    "open_wifi_settings": "settings",
    "open_usage_access_settings": "settings",
    "open_flashlight_settings": "settings",
    "set_system_alarm": "time",
    "set_system_timer": "time",
    "schedule_reminder": "time",
    "cancel_reminder": "time",
    "create_calendar_event": "time",
    "configure_periodic_check": "time",
    "compose_sms": "drafts and calls",
    "compose_email": "drafts and calls",
    "share_text": "drafts and calls",
    "create_contact_draft": "drafts and calls",
    "dial_phone_number": "drafts and calls",
    "web_search": "search and maps",
    "search_maps": "search and maps",
    "control_media": "media",
    "calculate": "calculate",
}
FAMILY_ORDER = ["no tool", "launch app", "in-app search", "settings", "time", "drafts and calls",
                "search and maps", "media", "calculate", "private reads", "other"]
FAILURE_ORDER = ["wrong tool", "bad/missing args", "unparseable call", "hallucinated tool name",
                 "answered instead of tool", "tool on no-tool request", "error"]


# ---- Helpers -------------------------------------------------------------------------------------


def load_records(folder):
    records = defaultdict(list)
    for path in sorted(folder.glob("*.jsonl")):
        # The production-fidelity file has its own schema (counts per suite/mode, no per-case
        # fields); it is read by load_fidelity, never merged into the per-case records.
        if path.name.startswith("production-fidelity"):
            continue
        for line in path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line:
                record = json.loads(line)
                records[(record["suite"], record["model"], record["mode"])].append(record)
    return records


def load_fidelity(folder):
    """The production-fidelity records (negation guard, false-positive split), keyed by file order.

    Written by `ProductionFidelitySuiteTest`, not by a model run: one line per (suite, model, mode)
    with the raw and guarded counts. Absent until that test has run.
    """
    path = folder / "production-fidelity__negation-guard.jsonl"
    if not path.is_file():
        return None
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def model_order(manifest, records):
    seen = OrderedDict()
    for model in RULE_MODELS:
        seen[model] = None
    for model in (manifest.get("models") or {}):
        seen[model] = None
    for _, model, _ in sorted(records):
        seen[model] = None
    return list(seen)


def keys_for(suite, records, models):
    modes = MODE_ORDER[suite]
    present = [key for key in records if key[0] == suite]
    extra_modes = sorted({key[2] for key in present} - set(modes))
    ordered = []
    for model in models:
        for mode in modes + extra_modes:
            if (suite, model, mode) in records:
                ordered.append((suite, model, mode))
    return ordered


def frac(k, n):
    if n == 0:
        return "n/a"
    return f"{k}/{n} ({100.0 * k / n:.0f}%)"


def pct(k, n):
    if n == 0:
        return "n/a"
    return f"{100.0 * k / n:.0f}%"


def errored(record):
    """True when a model call behind this record failed.

    A failed call answered nothing, so it must never be counted as a score. Read from
    `metrics.error` on every recorded call (`calls` for Suite A, `modelOutputs` and the flat
    `metrics` for the other suites), with `rawKind` as the fallback for record shapes that carry
    no metrics.
    """
    metrics = [record.get("metrics") or {}]
    metrics += [output.get("metrics") or {} for output in (record.get("modelOutputs") or [])]
    metrics += [call.get("metrics") or {} for call in (record.get("calls") or [])]
    return any(m.get("error") for m in metrics) or record.get("rawKind") in ("error", "missing_model")


# Per-run error counts, computed once from the loaded records (Decision L1). Every renderer that
# prints a number derived from a run reads it through [run_cell]; nothing else may format one.
# key = (suite, model, mode) -> (records in the run, records whose model call failed)
RUN_ERRORS = {}


def set_run_errors(records):
    """Compute the per-run error counts once, before any table is rendered."""
    global RUN_ERRORS
    RUN_ERRORS = {key: (len(rs), sum(1 for r in rs if errored(r))) for key, rs in records.items()}
    return RUN_ERRORS


def run_annotation(key):
    """`""`, `" (E errored)"` or `" (errored N/N)"` for one run (Decision L1)."""
    n, e = RUN_ERRORS.get(key, (0, 0))
    if not e:
        return ""
    if e == n:
        return f" (errored {n}/{n})"
    return f" ({e} errored)"


# Every cell a renderer produced through [run_cell] or [labelled], with the runs it came from.
# `--check` audits this list: it is the machine-checkable form of Decision L1.
RUN_CELLS = []


def record_cell(keys, text):
    RUN_CELLS.append((tuple(keys), str(text)))
    return text


def run_cell(key, text):
    """The one formatting path for a number derived from a run (Decision L1).

    A run is `(suite, model, mode)`. Any number from a run with at least one errored case carries
    that run's error count in the same cell. A run that errored on every case has no score at all:
    the cell reads `errored N/N`, because a failed call answered nothing, and a refusal is shaped
    exactly like a correct no-tool answer (that is how a 151/151-overflow run once read 51/151).
    """
    n, e = RUN_ERRORS.get(key, (0, 0))
    text = f"errored {n}/{n}" if (e and e == n) else str(text) + run_annotation(key)
    return record_cell([key], text)


def score_cell(key, rs, hits, total=None):
    """`k/n` for the answered subset [rs] of a run, formatted through that run's error count.

    [key] is the run the cell belongs to (Decision L1), so a cell built from a subset (tool-gold
    cases, one family) still carries the whole run's error count. [total] is the size of the cell's
    population when [rs] has already had errored cases filtered out of it; it defaults to `len(rs)`.

    A fully errored run has no score in any cell: the cell reads `errored N/N`, where N is the
    population the cell is about, not the run size.
    """
    run_n, run_e = RUN_ERRORS.get(key, (0, 0))
    population = len(rs) if total is None else total
    if run_e and run_e == run_n:
        return record_cell([key], f"errored {population}/{population}")
    if not rs:
        return "n/a" if not run_e else run_cell(key, "n/a")
    return run_cell(key, frac(hits, len(rs)))


def labelled(key, text):
    """A label cell (model, mode, plan source) that carries its run's error count.

    Tables whose cells are not scores (response kinds, per-argument accuracy, latency, Suite A
    rows) still derive every value from one run, so Decision L1 puts the run's error count in a
    cell of that row; the label column is the one that belongs to the whole row.
    """
    n, e = RUN_ERRORS.get(key, (0, 0))
    if not e:
        return record_cell([key], str(text))
    if e == n:
        return record_cell([key], f"{text} (errored {n}/{n})")
    return record_cell([key], f"{text} ({e} errored)")


def cell_for_runs(keys, text):
    """A cell derived from more than one run (a composite), annotated per contributing run.

    One errored contributor reads like every other cell, `text (E errored)`. When more than one
    contributing run has failures, each is named with its mode, because the composite's number
    depends on all of them.
    """
    dirty = [key for key in keys if RUN_ERRORS.get(key, (0, 0))[1]]
    if not dirty:
        return record_cell(keys, str(text))
    if len(dirty) == 1:
        return run_cell(dirty[0], text)
    parts = ", ".join(f"{RUN_ERRORS[key][1]} errored in {key[2]}" for key in dirty)
    return record_cell(keys, f"{text} ({parts})")


def percentile(values, q):
    """Nearest-rank percentile; None when there are no values."""
    values = sorted(v for v in values if v is not None)
    if not values:
        return None
    rank = max(1, math.ceil(q * len(values)))
    return values[rank - 1]


def fmt(value):
    return "n/a" if value is None else f"{value:.0f}"


def mean(values):
    values = [v for v in values if v is not None]
    return f"{statistics.mean(values):.0f}" if values else "n/a"


def preview(text, limit=PREVIEW_CHARS):
    text = " ".join((text or "").split())
    if len(text) > limit:
        text = text[:limit] + "..."
    return text.replace("`", "'") or "(empty)"


def table(header, rows):
    lines = ["| " + " | ".join(header) + " |", "| " + " | ".join("---" for _ in header) + " |"]
    for row in rows:
        lines.append("| " + " | ".join(str(cell).replace("|", "\\|") for cell in row) + " |")
    return "\n".join(lines)


def model_calls(record):
    """Metrics of the model calls a record actually made (responses reused from a cache excluded)."""
    if "calls" in record:
        calls = [c.get("metrics") for c in record["calls"] if not c.get("cached")]
    elif "modelOutputs" in record:
        calls = [o.get("metrics") for o in record["modelOutputs"] if not o.get("cached")]
    elif record.get("metrics") and not record.get("cached"):
        calls = [record["metrics"]]
    else:
        calls = []
    return [m for m in calls if m and not m.get("error")]


def failed_constraint_keys(record):
    """Sorted constraint keys whose negative check failed, for failure examples."""
    return sorted(key for key, passed in (record.get("constraintResults") or {}).items() if not passed)


def answer_text(answer, record=None):
    if not answer:
        return "?"
    if answer.get("kind") == "stop":
        return "stop"
    tool = answer.get("tool") or "?"
    args = answer.get("args") or {}
    shown = ", ".join(f"{k}={preview(str(v), 40)}" for k, v in args.items())
    prefix = "invalid " if answer.get("kind") == "invalid" else ""
    failed = failed_constraint_keys(record or answer)
    suffix = f"; failed constraints: {', '.join(failed)}" if failed else ""
    return f"{prefix}{tool}({shown}){suffix}"


def gold_initial_text(gold):
    tools = gold.get("tools") or []
    if not tools:
        return "no tool"
    args = gold.get("args") or {}
    shown = ", ".join(f"{k}={v}" for k, v in args.items())
    constraint_text = gold.get("constraints")
    suffix = f"; constraints {json.dumps(constraint_text, ensure_ascii=False, sort_keys=True)}" if constraint_text else ""
    return "|".join(tools) + (f"({shown})" if shown else "") + suffix


def gold_observation_text(gold):
    parts = []
    for accept in gold:
        if accept.get("stop"):
            parts.append("stop")
            continue
        target = accept.get("targetNodes") or accept.get("targets") or []
        bits = [accept.get("tool", "?")]
        if target:
            bits.append("target=" + "/".join(t.split("/")[-1] for t in target))
        if "text" in accept:
            bits.append("text=" + accept["text"])
        parts.append(" ".join(bits))
    return " or ".join(parts)


def unsupported_for(manifest, suite):
    return (manifest.get("unsupported") or {}).get(suite) or {}


# ---- Classification ------------------------------------------------------------------------------


def family(record):
    if record.get("category") in ("in_app_search", "in_app_multi_step"):
        return "in-app search"
    tools = (record.get("gold") or {}).get("tools") or []
    if not tools:
        return "no tool"
    tool = tools[0]
    if tool in TOOL_FAMILY:
        return TOOL_FAMILY[tool]
    if tool.startswith(("query_", "read_", "observe_")):
        return "private reads"
    return "other"


def invalid_reason(record):
    if record.get("parseFailure"):
        return record["parseFailure"]
    outputs = record.get("modelOutputs") or []
    if outputs and outputs[-1].get("parseFailure"):
        return outputs[-1]["parseFailure"]
    return record.get("rawKind") or "?"


def initial_failure(record):
    """Failure class of one Suites B/C record, or None when it is correct."""
    if errored(record):
        return "error"
    if record["correct"]:
        return None
    if not record["expectsTool"]:
        return "tool on no-tool request"
    answer = record["answer"]
    if answer.get("kind") == "invalid":
        reason = invalid_reason(record)
        if record["rawKind"] == "unknown_tool" or reason.startswith("Unknown tool"):
            return "hallucinated tool name"
        if record["rawKind"] == "rejected_tool" and not reason.startswith(("Invalid", "Multiple", "Model tool")):
            return "bad/missing args"
        return "unparseable call"
    if answer.get("kind") == "stop":
        return "answered instead of tool"
    if not record["toolCorrect"]:
        return "wrong tool"
    return "bad/missing args"


def copied_placeholder(record):
    return invalid_reason(record) == "Unknown tool: function"


# Diagnostic only: what a whitespace-tolerant reader would take from a `call:` output. Accepts a space
# after `call:`, a missing `call:` prefix, missing `{}` and code fences. It never maps the `function`
# placeholder to a tool, and the gold tool must be named exactly.
LENIENT_CALL = re.compile(r"^\s*(?:```\w*\s*)?(?:call\s*:\s*)?([A-Za-z_][A-Za-z0-9_]*)\s*(\{.*\})?\s*(?:```)?\s*$", re.S)


def lenient_answer(raw):
    match = LENIENT_CALL.match(raw or "")
    if not match:
        return None, None
    name, body = match.group(1), match.group(2)
    if not body:
        return name, {}
    try:
        parsed = json.loads(body)
    except ValueError:
        return name, None
    if not isinstance(parsed, dict):
        return name, None
    return name, {k: ("true" if v is True else "false" if v is False else str(v)) for k, v in parsed.items()}


def arg_value_matches(key, expected, actual):
    """Port of `LabScoring.argValueMatches`."""
    if actual is None:
        return False
    for alternative in expected.split("|"):
        value = actual.strip().lower()
        if alternative.startswith("~"):
            if alternative[1:].strip().lower() in value:
                return True
            continue
        if key in ("phoneNumber", "phone"):
            digits = "".join(c for c in alternative if c.isdigit())
            if digits and digits == "".join(c for c in actual if c.isdigit()):
                return True
            continue
        wanted = alternative.strip().lower()
        try:
            if Decimal(wanted) == Decimal(value):
                return True
        except (InvalidOperation, ValueError):
            pass
        if wanted == value:
            return True
    return False


def lenient_scores(record):
    """(tool right, tool and pinned args right) under the lenient reader, for a tool-gold record.

    This is a diagnostic text match, not an executable-correctness claim; schemaValid is enforced only
    by the strict scorer used for newly written records.
    """
    gold = record.get("gold") or {}
    name, args = lenient_answer(record.get("rawPreview"))
    if name is None or name not in (gold.get("tools") or []):
        return False, False
    if args is None:
        return True, False
    constraints = gold.get("constraints") or {}
    forbidden_args = set(constraints.get("forbiddenArgs") or [])
    forbidden_non_empty = set(constraints.get("forbiddenNonEmptyArgs") or [])
    forbidden_values = constraints.get("forbiddenArgValues") or {}
    constraints_ok = (
        not forbidden_args.intersection(args)
        and all(not (args.get(key) or "").strip() for key in forbidden_non_empty)
        and all(not arg_value_matches(key, value, args.get(key)) for key, value in forbidden_values.items())
    )
    return True, (
        all(arg_value_matches(k, v, args.get(k)) for k, v in (gold.get("args") or {}).items())
        and constraints_ok
    )


def observation_failure(record):
    if record["finalCorrect"]:
        return None
    final = record["finalAnswer"]
    gold = record["gold"]
    if final.get("kind") == "stop":
        return "stopped when an action was expected"
    if all(accept.get("stop") for accept in gold):
        return "acted when the gold is stop"
    if final.get("tool") not in {accept.get("tool") for accept in gold}:
        return "wrong tool"
    return "right tool, wrong target or text"


# ---- Headline ------------------------------------------------------------------------------------


def score_of(suite, rs):
    if suite == SUITE_A:
        return sum(r["finalCorrect"] and not errored(r) for r in rs), len(rs)
    return sum(r["correct"] and not errored(r) for r in rs), len(rs)


def headline_table(records, models, manifest, columns, cell):
    header = ["model"] + [f"{SUITE_LETTER[s]} {m}" for s, m in columns]
    rows = []
    for model in models:
        row = [model]
        for suite, mode in columns:
            key = (suite, model, mode)
            rs = records.get(key)
            if rs:
                row.append(cell(key, rs))
            elif model in unsupported_for(manifest, suite):
                row.append("unsupported")
            else:
                row.append("-")
        if any(cell_text != "-" for cell_text in row[1:]):
            rows.append(row)
    return table(header, rows)


def present_columns(records, models, manifest, suites):
    columns = [(suite, mode) for suite in suites for mode in MODE_ORDER[suite]]
    return [c for c in columns if any((c[0], m, c[1]) in records for m in models)
            or (c[0] == SUITE_C and unsupported_for(manifest, SUITE_C))]


def host_label(model, manifest=None):
    """The exact host label a table heading uses, one label per host.

    A result folder can hold two hosts for one day (the Ollama GGUF runs and the LiteRT shim on
    the production `.litertlm` files). Tables must never mix them, so a label is derived per model
    from its origin, not from the folder's single `hostNote` (which only names the last writer).
    The detailed per-model note, including quantization and the concurrency caveat, stays in
    **Run facts**.
    """
    if model.startswith("litert/"):
        return LITERT_HOST_LABEL
    recorded = ((manifest or {}).get("models") or {}).get(model, {}).get("host") or ""
    match = re.search(r"Ollama\s+(\S+)", recorded)
    return f"devbox CPU / Ollama {match.group(1)}" if match else "devbox CPU / Ollama (version unknown)"


def host_groups(manifest, records, models):
    """Ordered host label -> the models measured on it. `rules` has no host and is not listed."""
    groups = OrderedDict()
    for model in models:
        if model in RULE_MODELS:
            continue
        groups.setdefault(host_label(model, manifest), []).append(model)
    return groups


def headline(records, models, manifest, out):
    initial = present_columns(records, models, manifest, (SUITE_B, SUITE_C))
    if initial:
        out.append("Suites B and C, requests that need a tool: right first tool with every pinned argument.\n")
        out.append(headline_table(records, models, manifest, initial, lambda k, rs: score_cell(
            k,
            [r for r in rs if r["expectsTool"]],
            sum(r["correct"] and not errored(r) for r in rs if r["expectsTool"]),
            sum(r["argsExact"] and not errored(r) for r in rs if r["expectsTool"]))))
        out.append("")
        out.append("Suites B and C, requests that need no tool (chat, questions, explicit negations): nothing "
                   "executed. An invalid call executes nothing and counts as correct here.\n")
        out.append(headline_table(records, models, manifest, initial, lambda k, rs: score_cell(
            k,
            [r for r in rs if not r["expectsTool"]],
            sum(r["correct"] and not errored(r) for r in rs if not r["expectsTool"]))))
        out.append("")
        out.append("Suites B and C, requests measured per model (no shared population is inferred).\n")
        out.append(headline_table(records, models, manifest, initial,
                                  lambda k, rs: score_cell(k, rs, score_of(k[0], rs)[0])))
        out.append("")
    other = present_columns(records, models, manifest, (SUITE_A, SUITE_D))
    if other:
        out.append("Suite A (final step after the replanner guards, 46 screens) and Suite D (placement on 26 "
                   "routing cases):\n")
        out.append(headline_table(records, models, manifest, other,
                                  lambda k, rs: score_cell(k, rs, score_of(k[0], rs)[0])))
        out.append("")
    out.append("`rules` is the production rule path with no model, so it has no host and its row is repeated "
               "under each host as the same reference. `-` means not run. `-fix` modes are the lab-only "
               "placeholder ablation, not what the app sends. `*-lmf` modes are the production-faithful "
               "LocalModelFirst shape. `errored n/n` means every call failed (nothing was answered); "
               "`(k errored)` counts the failures inside a partly errored cell.\n")


# ---- Suite A -------------------------------------------------------------------------------------


def suite_a(records, models, out):
    keys = [k for k in keys_for(SUITE_A, records, models)]
    if not keys:
        out.append("No Suite A results.\n")
        return
    rows = []
    for key in keys:
        rs = records[key]
        n = len(rs)
        stop_cases = [r for r in rs if all(a.get("stop") for a in r["gold"])]
        action_cases = [r for r in rs if r not in stop_cases]
        attempted = [r for r in rs if r["modelCalls"] > 0]
        parse_fail = [r for r in attempted if r["rawKind"] != "call"]
        rows.append([
            key[1],
            key[2],
            n,
            run_cell(key, frac(sum(r["rawCorrect"] and not errored(r) for r in rs), n)),
            run_cell(key, frac(sum(r["finalCorrect"] and not errored(r) for r in rs), n)),
            run_cell(key, frac(sum(r["finalCorrect"] and not errored(r) for r in action_cases), len(action_cases))),
            run_cell(key, frac(sum(r["finalCorrect"] and not errored(r) for r in stop_cases), len(stop_cases))),
            run_cell(key, pct(len(parse_fail), len(attempted))),
            run_cell(key, f"{statistics.mean(r['modelCalls'] for r in rs):.2f}"),
        ])
    out.append(table(
        ["model", "mode", "n", "raw draft correct", "final correct", "final, action gold",
         "final, stop gold", "first call not a valid `call:`", "calls/step"],
        rows,
    ))
    out.append("")
    out.append("Raw draft = the first model call's parsed `call:` line, before any guard. Final = what "
               "`ModelObservationReplanner.planNext` returns after the guards and the optional second call; "
               "`null` counts as stop. A cell from a run with errored calls carries the run's error "
               "count.\n")

    categories = []
    for key in keys:
        for r in records[key]:
            if r["category"] not in categories:
                categories.append(r["category"])
    rows = []
    for key in keys:
        by_cat = defaultdict(list)
        for r in records[key]:
            by_cat[r["category"]].append(r)
        rows.append([key[1], key[2]] + [
            run_cell(key, f"{sum(r['finalCorrect'] and not errored(r) for r in by_cat[c])}/{len(by_cat[c])}")
            if by_cat[c] else "-"
            for c in categories
        ])
    out.append("Final correct by screen category:\n")
    out.append(table(["model", "mode"] + categories, rows))
    out.append("")

    classes = ["stopped when an action was expected", "acted when the gold is stop", "wrong tool",
               "right tool, wrong target or text"]
    rows = []
    for key in keys:
        counter = Counter(observation_failure(r) for r in records[key])
        first_calls = [r["calls"][0] for r in records[key] if r["calls"]]
        unknown = sum(1 for c in first_calls if (c.get("parseFailure") or "").startswith("Unknown tool"))
        malformed = sum(1 for c in first_calls if c.get("parseFailure") and not c["parseFailure"].startswith("Unknown tool"))
        rows.append([key[1], key[2], run_cell(key, len(records[key]))]
                    + [counter.get(c, 0) for c in classes] + [malformed, unknown])
    out.append("Failure taxonomy (final answer), plus first-call format failures:\n")
    out.append(table(["model", "mode", "n"] + classes
                     + ["first call unparseable", "first call hallucinated tool"], rows))
    out.append("")

    reasons = Counter()
    per_key = {}
    for key in keys:
        counter = Counter()
        for r in records[key]:
            for reason in r["guardReasons"]:
                if reason != "accepted":
                    counter[reason] += 1
        per_key[key] = counter
        reasons.update(counter)
    if reasons:
        ordered = [reason for reason, _ in reasons.most_common()]
        out.append("Replanner rejection reasons, counted per model call (a step can have two):\n")
        out.append(table(["model", "mode"] + ordered,
                         [[key[1], key[2]] + [per_key[key].get(r, 0) for r in ordered]
                          for key in keys]))
        out.append("")

    rows = []
    for key in keys:
        sources = Counter(r["finalSource"] for r in records[key])
        rows.append([key[1], key[2],
                     run_cell(key, ", ".join(f"{k} {v}" for k, v in sources.most_common()))])
    out.append("Where the final answer came from:\n")
    out.append(table(["model", "mode", "final source"], rows))
    out.append("")


# ---- Suites B and C ------------------------------------------------------------------------------


def error_reason(record):
    """Short label for a record whose model call failed, from its metrics error text.

    LiteRT-LM reports a rejected call to Python only as `send_message failed`; the cause
    (`INVALID_ARGUMENT: Input token ids are too long ... >= 1024`) goes to the engine's stderr,
    which the shim log keeps. So this says "engine rejected the call" and the run notes say why;
    it never guesses a cause it cannot see.
    """
    message = ((record.get("metrics") or {}).get("error") or "")
    if "too long" in message or "maximum number of tokens" in message:
        return "context overflow"
    if "send_message failed" in message:
        return "engine rejected the call"
    if not message:
        return "error"
    return "error"


def initial_rows(keys, records, native):
    rows = []
    for key in keys:
        rs = records[key]
        tool_cases = [r for r in rs if r["expectsTool"]]
        none_cases = [r for r in rs if not r["expectsTool"]]
        bad = [r for r in rs if errored(r)]
        # An errored case answered nothing. It is never "correct": say so instead of letting a
        # refusal-shaped record inflate the score (the 270M overflow case looked like 51/151 before).
        # The fully errored case also names the cause, which the engine's stderr carries.
        overall = score_cell(key, rs, sum(r["correct"] and not errored(r) for r in rs))
        if bad and len(bad) == len(rs):
            overall = f"errored {len(rs)}/{len(rs)} ({error_reason(bad[0])})"
        attempted = [r for r in rs
                     if r["rawKind"] not in ("text", "blank", "no_tool", "no_tool_choice", "answer", "error")]
        if native:
            parsed = [r for r in attempted if r["rawKind"] in ("call", "multi_call")]
        elif key[2].startswith("M-"):
            parsed = [r for r in attempted if r["rawKind"] == "call"]
        else:
            attempted, parsed = [], []
        schema = [r for r in rs if r.get("schemaValid") is not None]
        answered_tool = [r for r in tool_cases if not errored(r)]
        answered_none = [r for r in none_cases if not errored(r)]
        rows.append([
            key[1],
            key[2],
            overall,
            len(bad),
            score_cell(key, answered_tool, sum(r["toolCorrect"] for r in answered_tool),
                       total=len(tool_cases)),
            score_cell(key, answered_tool, sum(r["argsExact"] for r in answered_tool),
                       total=len(tool_cases)),
            score_cell(key, answered_none, sum(r["falsePositive"] for r in answered_none),
                       total=len(none_cases)),
            pct(len(parsed), len(attempted)) if attempted else run_cell(key, "-"),
            run_cell(key, pct(sum(r["schemaValid"] for r in schema), len(schema))),
        ])
    return rows


INITIAL_HEADER = ["model", "mode", "overall correct", "errored", "tool correct (tool gold)",
                  "tool+args exact (tool gold)", "false positive (no-tool gold)",
                  "valid of attempted calls", "schema-valid"]


def suite_b(records, models, out):
    keys = keys_for(SUITE_B, records, models)
    if not keys:
        out.append("No Suite B results.\n")
        return
    out.append(table(INITIAL_HEADER, initial_rows(keys, records, native=False)))
    out.append("")
    out.append("An invalid call executes nothing, so it is wrong on tool cases and not a false positive on "
               "no-tool cases. `M-full` / `M-topk` run the model alone with the production `actionPrompt` and "
               "parser; the `-fix` variants are a lab-only ablation that replaces the prompt's "
               "`call:function {\"arg\":\"value\"}` placeholder with `call:函数名 {\"参数名\":\"值\"}` and changes "
               "nothing else. `R+M` is the production order (skills, rule intent gate, model, rule fallback).\n")
    topk_rows = []
    for key in keys:
        if key[2] not in ("M-topk", "M-topk-fix", "M-topk-fix-lmf"):
            continue
        rs = [r for r in records[key] if r["expectsTool"]]
        answered = [r for r in rs if not errored(r)]
        hits = [r for r in answered if r.get("retrieverHit")]
        outside = sum(1 for r in records[key] if r.get("outsideAdvertised"))
        topk_rows.append([key[1], key[2], run_cell(key, frac(len(hits), len(answered))),
                          score_cell(key, hits, sum(r["correct"] for r in hits)), run_cell(key, outside)])
    if topk_rows:
        out.append("Top-8 modes with the lexical retriever (errored calls excluded from the recall base; "
                   "a hit cannot be scored if the call never answered; the run's error count is in the "
                   "cell):\n")
        out.append(table(["model", "mode", "retriever recall@8", "correct when gold tool retrieved",
                          "answers outside the 8"], topk_rows))
        out.append("")
    lenient_rows = []
    for key in keys:
        if not key[2].startswith("M-"):
            continue
        rs = [r for r in records[key] if r["expectsTool"]]
        answered = [r for r in rs if not errored(r)]
        scores = [lenient_scores(r) for r in answered]
        lenient_rows.append([key[1], key[2],
                             score_cell(key, answered, sum(r["toolCorrect"] for r in answered), total=len(rs)),
                             score_cell(key, answered, sum(s[0] for s in scores), total=len(rs)),
                             score_cell(key, answered, sum(r["argsExact"] for r in answered), total=len(rs)),
                             score_cell(key, answered, sum(s[1] for s in scores), total=len(rs))])
    if lenient_rows:
        out.append("Format compliance against model knowledge, tool requests only. Strict = the app's parser. "
                   "Lenient = a diagnostic reader that also accepts a space after `call:`, a missing `call:` "
                   "prefix, missing `{}` and code fences (it never maps the `function` placeholder to a tool). "
                   "The gap between the two is lost to the text format, not to tool choice:\n")
        out.append(table(["model", "mode", "tool, strict", "tool, lenient", "tool+args, strict", "tool+args, lenient"],
                         lenient_rows))
        out.append("")
    rm_delta(records, models, out)
    model_vs_rules(records, models, [(SUITE_B, m) for m in MODE_ORDER[SUITE_B] if m.startswith("M-")], out)
    plan_sources(keys, records, out)
    arg_keys(keys, records, out)


def suite_c(records, models, manifest, out):
    keys = keys_for(SUITE_C, records, models)
    unsupported = unsupported_for(manifest, SUITE_C)
    if unsupported:
        out.append("Not run: " + "; ".join(f"`{m}` ({why})" for m, why in unsupported.items()) + ".\n")
    if not keys:
        out.append("No Suite C results.\n")
        return
    out.append(table(INITIAL_HEADER, initial_rows(keys, records, native=True)))
    out.append("")
    out.append("`N-full` and `N-topk` use a strict system prompt (always call one tool); `N-topk-free` allows a "
               "plain-text answer when no phone action is needed. Arguments are converted to strings the way "
               "`RemoteChatRuntime` converts remote tool calls.\n")
    rows = []
    for key in keys:
        kinds = Counter(r["rawKind"] for r in records[key])
        rows.append([key[1], key[2],
                     run_cell(key, ", ".join(f"{k} {v}" for k, v in kinds.most_common()))])
    out.append("Native response kinds (`unknown_tool` and `nested_arguments` would be rejected by the app):\n")
    out.append(table(["model", "mode", "kinds"], rows))
    out.append("")
    model_vs_rules(records, models, [(SUITE_C, mode) for mode in MODE_ORDER[SUITE_C]], out)


def initial_categories(records, models, out):
    keys = keys_for(SUITE_B, records, models) + keys_for(SUITE_C, records, models)
    if not keys:
        return
    present = {family(r) for key in keys for r in records[key]}
    families = [f for f in FAMILY_ORDER if f in present]
    header = ["suite", "model", "mode"] + families
    rows = []
    for key in keys:
        by = defaultdict(list)
        for r in records[key]:
            by[family(r)].append(r)
        rows.append([SUITE_LETTER[key[0]], key[1], key[2]] + [
            score_cell(key, [x for x in by[f] if not errored(x)],
                       sum(x["correct"] for x in by[f] if not errored(x)), total=len(by[f]))
            if by[f] else run_cell(key, "-") for f in families
        ])
    out.append(table(header, rows))
    out.append("")
    out.append("Family = the gold first tool's family; dataset cases labeled `in_app_search` (\"在淘宝搜索牛奶\", gold "
               "`open_app_by_name`) are split out. `no tool` = chat, questions and explicit negations. A cell "
               "from a partly errored run carries that run's error count.\n")


def failure_taxonomy(records, models, out):
    keys = [k for k in keys_for(SUITE_B, records, models) + keys_for(SUITE_C, records, models) if k[1] not in RULE_MODELS]
    if not keys:
        return
    rows = []
    for key in keys:
        failures = [initial_failure(r) for r in records[key]]
        counter = Counter(f for f in failures if f)
        placeholder = sum(1 for r in records[key]
                          if initial_failure(r) == "hallucinated tool name" and copied_placeholder(r))
        # `initial_failure` already classes an errored call as "error"; say how many lines that is
        # so a fully errored mode reads as an errored run, not as 151 discrete failures.
        n, e = RUN_ERRORS.get(key, (0, 0))
        rows.append([SUITE_LETTER[key[0]], key[1], key[2], run_cell(key, sum(counter.values()))]
                    + [run_cell(key, counter.get(c, 0)) for c in FAILURE_ORDER]
                    + [run_cell(key, placeholder), run_cell(key, f"{e}/{n}" if e else "0")])
    out.append(table(["suite", "model", "mode", "wrong"] + FAILURE_ORDER
                     + ["of which literal `call:function`", "errored"], rows))
    out.append("")
    out.append("Classes: wrong tool = a valid call to a registry tool that is not gold; bad/missing args = gold "
               "tool with a pinned argument wrong or absent (or rejected by the schema); unparseable call = a "
               "`call:` / tool call the app's parser rejects for format, multiple calls or nested arguments; "
               "hallucinated tool name = a call to a tool that is not in the registry; answered instead of tool "
               "= plain text or nothing on a request that needs a tool; tool on no-tool request = an executable "
               "tool call where the gold is no tool. `call:function` counts outputs that copied the "
               "`call:function {\"arg\":\"value\"}` placeholder from the prompt as the tool name.\n")


def rm_delta(records, models, out):
    rules = {r["caseId"]: r for r in records.get((SUITE_B, "rules", "R"), [])}
    if not rules:
        return
    rows = []
    for model in models:
        rm = records.get((SUITE_B, model, "R+M"))
        if not rm:
            continue
        gains = [r["caseId"] for r in rm if r["correct"] and not errored(r) and not rules[r["caseId"]]["correct"]]
        losses = [r["caseId"] for r in rm
                  if not r["correct"] and not errored(r) and rules[r["caseId"]]["correct"]]
        model_used = sum(1 for r in rm if r.get("planSource") == "model")
        model_called = sum(1 for r in rm if r.get("modelCalls"))
        key = (SUITE_B, model, "R+M")
        rows.append([model, model_called, model_used, run_cell(key, len(gains)),
                     run_cell(key, len(losses)),
                     ", ".join(losses[:6]) + (" ..." if len(losses) > 6 else "")])
    if rows:
        out.append("`R+M` against `R` (production order with a model installed, against rules only). A cell "
                   "from a partly errored run carries that run's error count:\n")
        out.append(table(["model", "requests that reached the model", "plans from the model", "cases fixed",
                          "cases broken", "broken cases"], rows))
        out.append("")


def model_vs_rules(records, models, suite_modes, out):
    """Per case, what a model-only mode would change if it replaced the rules (`R`)."""
    rules = {r["caseId"]: r for r in records.get((SUITE_B, "rules", "R"), [])}
    if not rules:
        return
    misses = [case_id for case_id, r in rules.items() if not r["correct"]]
    rows = []
    for model in models:
        for suite, mode in suite_modes:
            rs = records.get((suite, model, mode))
            if not rs:
                continue
            by_id = {r["caseId"]: r for r in rs}
            key = (suite, model, mode)
            recovered = [case_id for case_id in misses if by_id.get(case_id, {}).get("correct") and not errored(by_id[case_id])]
            # An errored call produced no answer, so it cannot "break" a case R got right.
            broken = [r["caseId"] for r in rs
                      if not r["correct"] and not errored(r) and rules.get(r["caseId"], {}).get("correct")]
            rows.append([model, mode, run_cell(key, frac(len(recovered), len(misses))),
                         run_cell(key, len(broken)),
                         ", ".join(recovered[:6]) + (" ..." if len(recovered) > 6 else "")])
    if rows:
        out.append(f"Model-only modes against `R`, case by case (`R` misses {len(misses)} cases). A cell "
                   "from a run with errored calls carries its error count, because an errored call is "
                   "neither recovered nor broken:\n")
        out.append(table(["model", "mode", "R misses the model gets right", "R hits the model gets wrong",
                          "recovered cases"], rows))
        out.append("")


def plan_sources(keys, records, out):
    rows = []
    for key in keys:
        if key[2] not in ("R", "R+M"):
            continue
        counter = Counter(r.get("planSource", "?") for r in records[key])
        rows.append([key[1], key[2],
                     run_cell(key, ", ".join(f"{k} {v}" for k, v in counter.most_common()))])
    if rows:
        out.append("Where `R` / `R+M` plans came from:\n")
        out.append(table(["model", "mode", "plan source"], rows))
        out.append("")


def arg_keys(keys, records, out):
    all_keys = Counter()
    stats = {}
    for key in keys:
        counter = defaultdict(lambda: [0, 0])
        for r in records[key]:
            for name, ok in (r.get("argKeys") or {}).items():
                counter[name][1] += 1
                counter[name][0] += 1 if ok else 0
                all_keys[name] += 1
        stats[key] = counter
    if not all_keys:
        return
    names = [name for name, _ in all_keys.most_common(10)]
    rows = [[labelled(key, key[1]), key[2]]
            + [run_cell(key, f"{stats[key][n][0]}/{stats[key][n][1]}") if stats[key][n][1] else "-"
               for n in names]
            for key in keys]
    out.append("Per-argument accuracy, scored only where the tool was right (10 most common gold keys):\n")
    out.append(table(["model", "mode"] + names, rows))
    out.append("")


# ---- Suite D -------------------------------------------------------------------------------------


def suite_d(records, models, out):
    keys = keys_for(SUITE_D, records, models)
    if not keys:
        out.append("No Suite D results.\n")
        return
    rows = []
    for key in keys:
        rs = [r for r in records[key] if not errored(r)]
        if not rs:
            rows.append([key[1], key[2]] + ["errored %d/%d" % (len(records[key]), len(records[key]))] * 7)
            continue
        n = len(rs)
        local = [r for r in rs if r["gold"] == "local"]
        remote = [r for r in rs if r["gold"] == "remote"]
        confident = [r for r in rs if r.get("confident")]
        is_rule = key[1] in RULE_MODELS
        rows.append([
            key[1],
            key[2],
            score_cell(key, rs, sum(r["valid"] for r in rs)),
            score_cell(key, rs, sum(r["correct"] for r in rs)),
            run_cell(key, frac(sum(r["correct"] and not errored(r) for r in local), len(local))),
            run_cell(key, frac(sum(r["correct"] and not errored(r) for r in remote), len(remote))),
            "-" if is_rule else run_cell(key, frac(len(confident), n)),
            "-" if is_rule else run_cell(key, frac(sum(r["correct"] and not errored(r) for r in confident), len(confident))),
            score_cell(key, rs, sum(r["effectiveCorrect"] for r in rs)),
        ])
    out.append(table(
        ["model", "mode", "valid", "accuracy", "local gold", "remote gold", "coverage at >= 0.9",
         "accuracy at >= 0.9", "app outcome (gate + rule fallback)"],
        rows,
    ))
    out.append("")
    out.append("App outcome applies the app's gate: a valid verdict with confidence >= 0.9 decides, anything "
               "else keeps the rule placement (`RequestComplexityAggregator` fed as `chatPlacementInputs` "
               "feeds it).\n")


# ---- Latency and prompt size ---------------------------------------------------------------------


def latency(records, models, out):
    rows = []
    for suite in (SUITE_A, SUITE_B, SUITE_C, SUITE_D, SUITE_E):
        for key in keys_for(suite, records, models):
            if key[1] in RULE_MODELS:
                continue
            calls = [m for r in records[key] for m in model_calls(r)]
            if not calls:
                # Every call in this run failed (`model_calls` drops failed calls), so there is no
                # timing to report. Say the run errored rather than leaving it out of the table.
                n, e = RUN_ERRORS.get(key, (0, 0))
                if e:
                    rows.append([SUITE_LETTER[suite], key[1], key[2], f"errored {n}/{n}"]
                                + [f"errored {n}/{n}"] * 6)
                continue
            walls = [m.get("wallMs") for m in calls]
            prompt = [m.get("promptTokens") for m in calls]
            rows.append([
                SUITE_LETTER[suite], key[1], key[2], run_cell(key, len(calls)),
                fmt(percentile(walls, 0.5)), fmt(percentile(walls, 0.95)),
                fmt(percentile(prompt, 0.5)), fmt(percentile(prompt, 0.95)),
                mean([m.get("promptCachedTokens") for m in calls]),
                mean([m.get("evalTokens") for m in calls]),
            ])
    if not rows:
        return
    out.append(table(["suite", "model", "mode", "model calls", "p50 ms", "p95 ms", "prompt tok p50",
                      "prompt tok p95", "mean cached prompt tok", "mean output tok"], rows))
    out.append("")
    out.append("Per model call, wall time on the desktop host, responses reused from the lab's cache excluded. "
               "Ollama reuses the KV cache for a shared prompt prefix (cached tokens), which a cold phone call "
               "would not. Use these numbers to compare modes and models with each other, not as phone latency. "
               "A failed call adds no timing, so a partly errored run's call count carries its error count; a "
               "fully errored run has no row at all, because there is nothing to time.\n")


def retriever(manifest, out):
    static = (manifest.get("suites") or {}).get("static") or {}
    recall = static.get("retrieverRecallAtK")
    if not recall:
        return
    chars = static.get("actionPromptCharsMeanAtK") or {}
    rows = []
    for k in sorted(recall, key=int):
        info = recall[k]
        rows.append([k, frac(info["hits"], info["cases"]), chars.get(k, "?")])
    rows.append([f"{static.get('registryTools')} (full registry)", "100%", static.get("actionPromptCharsFullRegistry", "?")])
    out.append(table(["k", "recall@k (tool cases)", "mean `actionPrompt` chars"], rows))
    out.append("")
    out.append(f"Native tool schemas for the full registry are {static.get('nativeToolSchemaCharsFullRegistry')} "
               "characters (schema JSON plus descriptions), before the chat template adds its own framing.\n")
    ranks = static.get("retrieverGoldRank") or {}
    ks = sorted(int(k) for k in recall)
    misses_at = {int(k): v for k, v in (static.get("retrieverMissesAtK") or {}).items()}
    if not misses_at or not ks:
        return
    smallest = ks[0]
    rows = []
    for entry in misses_at.get(smallest, []):
        case_id, rest = entry.split(":", 1)
        gold, _, text = rest.partition("]:")
        rank = ranks.get(case_id)
        rank_text = "not ranked" if rank is None or rank > 10_000 else str(rank)
        rows.append([case_id, gold.strip("["), text, rank_text])
    rows.sort(key=lambda row: (int(row[3]) if row[3].isdigit() else 10_000, row[0]))
    out.append(f"Every case the retriever misses at k={smallest}, with the gold tool's rank:\n")
    out.append(table(["case", "gold", "request", "gold rank"], rows))
    out.append("")


# ---- Failure examples (separate file) ------------------------------------------------------------


def failure_examples(records, models, out):
    out.append("# Failure examples\n")
    out.append(f"Generated by `lab/scripts/summarize.py`. Up to {EXAMPLES_PER_GROUP} examples per group; raw "
               f"outputs are whitespace-collapsed and cut to {PREVIEW_CHARS} characters.\n")
    out.append("## Suite A: wrong final step\n")
    for key in keys_for(SUITE_A, records, models):
        wrong = [r for r in records[key] if not r["finalCorrect"]]
        if not wrong:
            continue
        groups = defaultdict(list)
        for r in wrong:
            groups[observation_failure(r)].append(r)
        out.append(f"**{key[1]}** ({len(wrong)} wrong)\n")
        for name, rs in sorted(groups.items(), key=lambda item: -len(item[1])):
            out.append(f"- {name}: {len(rs)}")
            for r in rs[:EXAMPLES_PER_GROUP]:
                raws = " / ".join(preview(c["rawPreview"], 140) for c in r["calls"]) or "(no model call)"
                out.append(f"  - `{r['caseId']}`: gold {gold_observation_text(r['gold'])}; final "
                           f"{answer_text(r['finalAnswer'])}; guards {', '.join(r['guardReasons'])}; raw `{raws}`")
        out.append("")
    for suite, title in ((SUITE_B, "Suite B"), (SUITE_C, "Suite C")):
        out.append(f"## {title}: wrong first tool\n")
        for key in keys_for(suite, records, models):
            wrong = [r for r in records[key] if errored(r) or not r["correct"]]
            if not wrong:
                continue
            groups = defaultdict(list)
            for r in wrong:
                groups[initial_failure(r)].append(r)
            out.append(f"**{key[1]} {key[2]}** ({len(wrong)} wrong)\n")
            for name, rs in sorted(groups.items(), key=lambda item: -len(item[1])):
                out.append(f"- {name}: {len(rs)}")
                for r in rs[:EXAMPLES_PER_GROUP]:
                    out.append(f"  - `{r['caseId']}` 「{preview(r['input'], 60)}」: gold {gold_initial_text(r['gold'])}; "
                               f"got {answer_text(r['answer'], r)}; raw `{preview(r.get('rawPreview'))}`")
            out.append("")
    out.append("## Suite D: wrong placement\n")
    for key in keys_for(SUITE_D, records, models):
        wrong = [r for r in records[key] if errored(r) or not r["correct"]]
        if not wrong:
            continue
        out.append(f"**{key[1]} {key[2]}** ({len(wrong)} wrong): " + "; ".join(
            f"`{r['caseId']}` gold {r['gold']} got {r.get('choice') or r.get('invalidReason')}"
            + (f" ({r['confidence']})" if r.get("confidence") is not None else "")
            for r in wrong
        ))
        out.append("")


# ---- Suite E -------------------------------------------------------------------------------------


def suite_e(records, models, out):
    keys = keys_for(SUITE_E, records, models)
    if not keys:
        out.append("No Suite E results.\n")
        return
    rules = {r["caseId"]: r for r in records.get((SUITE_B, "rules", "R"), [])}
    gap = {case_id for case_id, r in rules.items() if r["rawKind"] != "use_tool"} if rules else set()
    rows = []
    for key in keys:
        all_rs = records[key]
        rs = [r for r in all_rs if not errored(r)]
        if not rs:
            rows.append([key[1], len(all_rs)] + [f"errored {len(all_rs)}/{len(all_rs)}"] * 7)
            continue
        n = len(rs)
        valid = [r for r in rs if r.get("valid")]
        acted = [r for r in rs if r["expectsAction"]]
        idle = [r for r in rs if not r["expectsAction"]]
        # Gate-open is a positive prediction; recall = share of action cases where it opened.
        true_pos = sum(1 for r in acted if r.get("gateOpen"))
        false_pos = sum(1 for r in idle if r.get("gateOpen"))
        precision = f"{true_pos}/{true_pos + false_pos}" if (true_pos + false_pos) else "n/a"
        gap_rs = [r for r in rs if r["caseId"] in gap] if gap else []
        gap_acted = [r for r in gap_rs if r["expectsAction"]]
        rows.append([
            key[1],
            n,
            score_cell(key, rs, len(valid)),
            score_cell(key, rs, sum(1 for r in rs if r.get("gatedCorrect"))),
            run_cell(key, f"{precision} ({pct(true_pos, true_pos + false_pos)})"),
            run_cell(key, frac(true_pos, len(acted))),
            run_cell(key, frac(sum(1 for r in idle if not r.get("gateOpen")), len(idle))),
            score_cell(key, gap_rs, len(gap_rs)),
            score_cell(key, gap_rs, sum(1 for r in gap_rs if r.get("gatedCorrect"))) if gap_rs else "-",
            run_cell(key, frac(sum(1 for r in gap_acted if r.get("gateOpen")), len(gap_acted)))
            if gap_acted else "-",
        ])
    population = rule_gap_population(records)
    out.append(table(
        ["model", "n", "valid JSON", "gate decision correct", "gate precision (true/false open)",
         "recall on action cases", "no-action cases kept closed", "rule-gap n", "correct on rule-gap cases",
         "gate opens on tool cases in the gap"],
        rows,
    ))
    out.append("")
    if population is None:
        out.append("No Suite B rules baseline is available, so gap-specific metrics are not shown.\n")
    else:
        gap_total, gap_no_tool, gap_tool = population
        out.append("The gate opens on `action = true` with confidence >= the app's routing gate "
                   "(`DECISION_MODEL_MIN_CONFIDENCE`); anything else (false, low confidence, invalid "
                   "JSON) keeps it closed, so a broken gate fails closed to no tool. "
                   f"The {gap_total} rule-gap cases are the Suite B requests where the production rules "
                   f"return no tool ({gap_no_tool} no-tool, {gap_tool} tool). Explicit negations are "
                   "labelled no-action. Lab-only experiment: the app has no such gate today.\n")


# ---- Offline composites --------------------------------------------------------------------------


def rule_gap_records(records):
    """Suite B rule records with no tool, or None when the baseline is absent."""
    rules = records.get((SUITE_B, "rules", "R"))
    if not rules:
        return None
    return [r for r in rules if r["rawKind"] != "use_tool"]


def gap_population(gap_records):
    """(total, no-tool, tool) counts for an already filtered gap population."""
    return (
        len(gap_records),
        sum(1 for r in gap_records if not r["expectsTool"]),
        sum(1 for r in gap_records if r["expectsTool"]),
    )


def rule_gap_population(records):
    """Population counts for the current Suite B rule-gap requests, or None without a baseline."""
    gap_records = rule_gap_records(records)
    if gap_records is None:
        return None
    return gap_population(gap_records)


def composite_r_then_model(records, models, out):
    """R keeps its plans; a model mode fills only the cases where R returned no tool.

    Pure post-processing of saved JSONL: no model is called here, so the numbers describe what an
    R-plus-model hand-off would have scored on the same run.
    """
    gap_records = rule_gap_records(records)
    if gap_records is None:
        return
    gap = {r["caseId"] for r in gap_records}
    base = {r["caseId"]: r for r in records[(SUITE_B, "rules", "R")]}
    rows = []
    for suite in (SUITE_B, SUITE_C):
        for model in models:
            if model in RULE_MODELS:
                continue
            for mode in MODE_ORDER[suite]:
                # The end-to-end mode already includes rules, guard, gate and proposal; composing it
                # with R a second time would not describe any production path.
                if mode in ("R-gated-e2e", "R-gated-e2e-fence"):
                    continue
                rs = records.get((suite, model, mode))
                if not rs:
                    continue
                by_id = {r["caseId"]: r for r in rs}
                # Sorted, not set order: this list is written into SUMMARY.md, and iterating the set
                # would change which example cases appear between identical runs.
                filled = [by_id[c] for c in sorted(gap) if c in by_id]
                if not filled:
                    continue
                # Only an answered call can override R; an errored one leaves R's plan in place,
                # which is exactly what a fail-closed hand-off does.
                answered = [r for r in filled if not errored(r)]
                fixed = [r for r in answered if r["correct"] and not base[r["caseId"]]["correct"]]
                broken = [r for r in answered if not r["correct"] and base[r["caseId"]]["correct"]]
                total = sum(base[c]["correct"] for c in base) - sum(base[c]["correct"] for c in gap)
                total += sum(r["correct"] for r in answered)
                total += sum(base[r["caseId"]]["correct"] for r in filled if errored(r))
                total += sum(base[c]["correct"] for c in gap if c not in by_id)
                key = (suite, model, mode)
                rows.append([
                    SUITE_LETTER[suite], model, mode,
                    run_cell(key, f"{total}/{len(base)}"),
                    run_cell(key, len(fixed)), run_cell(key, len(broken)),
                    run_cell(key, sum(1 for r in filled if errored(r))),
                    ", ".join(r["caseId"] for r in fixed[:5]) + (" ..." if len(fixed) > 5 else ""),
                ])
    if not rows:
        return
    gap_total, gap_no_tool, gap_tool = gap_population(gap_records)
    out.append("Offline composite: `R` keeps every plan it makes, and the model answers only the "
               "frames where `R` returned no tool "
               f"({gap_no_tool} no-tool + {gap_tool} tool requests = the {gap_total} rule-gap cases). "
               "Fixed = the model gets right a gap case `R` missed; broken = the model loses "
               "a gap case `R` got right; errored = the call failed, so `R`'s plan stays. No model was "
               "called for this table:\n")
    out.append(table(["suite", "model", "mode", "R+model correct", "fixed", "broken", "errored",
                      "fixed cases"], rows))
    out.append("")


def composite_gate(records, models, out):
    """R, then the action gate, then a native mode only when the gate opens on a case R left alone.

    `N-full` is the production-shaped native mode. The LiteRT files cannot run it (the full registry
    overflows the 1024-token context), so a model that only has `N-topk` is scored with that mode and
    the row says which one it used; the two are never mixed inside a row or presented as the same
    number.
    """
    gap_records = rule_gap_records(records)
    if gap_records is None:
        return
    gap = {r["caseId"] for r in gap_records}
    base = {r["caseId"]: r for r in records[(SUITE_B, "rules", "R")]}
    rows = []
    for model in models:
        gate_rows = {r["caseId"]: r for r in records.get((SUITE_E, model, "json-prompt"), [])
                     if not errored(r)}
        if not gate_rows:
            continue
        # Prefer the mode that is closest to production: N-full, then the production-faithful
        # N-topk-lmf (top-k plus the `answer` choice), then plain N-topk. The mode is named per row.
        native_mode, native = None, {}
        for candidate in ("N-full", "N-topk-lmf", "N-topk"):
            rows_for = {r["caseId"]: r for r in records.get((SUITE_C, model, candidate), [])
                        if not errored(r)}
            if rows_for:
                native_mode, native = candidate, rows_for
                break
        if not native:
            continue
        fixed = broken = opened = 0
        total = 0
        for case_id, rule in base.items():
            if rule["rawKind"] == "use_tool" or case_id not in gate_rows:
                # R acted, or the gate never judged this case: keep R.
                total += rule["correct"]
                continue
            gate = gate_rows[case_id]
            if gate.get("gateOpen") and case_id in native:
                opened += 1
                answer = native[case_id]["correct"]
            else:
                answer = not rule["expectsTool"]  # closed gate = no tool, right only when none is wanted
            total += answer
            if answer and not rule["correct"]:
                fixed += 1
            elif not answer and rule["correct"]:
                broken += 1
        native_key = (SUITE_C, model, native_mode)
        gate_key = (SUITE_E, model, "json-prompt")
        contributors = [gate_key, native_key]
        rows.append([model, native_mode, cell_for_runs(contributors, f"{total}/{len(base)}"),
                     cell_for_runs(contributors, opened),
                     cell_for_runs(contributors, fixed), cell_for_runs(contributors, broken),
                     cell_for_runs(contributors, frac(sum(1 for c in gap if c in gate_rows), len(gap)))])
    if not rows:
        return
    out.append("Offline composite: `R`, then the action gate, then the native mode in its column only "
               "where the gate opens on a request `R` left alone (gate open = `action = true` and "
               "confidence >= 0.9). A closed gate returns no tool, so it fails closed. Fixed/broken are "
               "against `R` alone. The native mode is the first of `N-full`, `N-topk-lmf`, `N-topk` that "
               "the model has, and the row names it: they differ in which tools the model was shown and "
               "whether it could decline, so rows are not comparable across a different mode. A composite "
               "cell carries the error count of every run it draws on (the gate and the native mode).\n")
    out.append(table(["model", "native mode", "R+gate+native correct", "native answers used", "fixed",
                      "broken", "gate coverage of the gap"], rows))
    out.append("")


# ---- Production-faithful LocalModelFirst --------------------------------------------------------


def production_faithful(fidelity, models, out, initial_info=None):
    """Raw vs guard-corrected scores and the false-positive split, per B/C mode and model.

    Written by `ProductionFidelitySuiteTest` from the saved JSONL; no model is called here. A
    guarded case is one whose input starts with an action negation: production does not show it to
    the model and takes the rule plan (`orchestration/InitialToolPlanner.kt:100`).
    """
    if fidelity is None:
        out.append("Not run in this folder: the production-faithful table needs "
                   "`production-fidelity__negation-guard.jsonl` (produced by "
                   "`ProductionFidelitySuiteTest`, offline over the saved JSONL).\n")
        return
    rows = [r for r in fidelity if r.get("model") in models]
    if not rows:
        out.append("No production-fidelity rows for this host: the file exists but has no eligible model rows for the selected dataset/models.\n")
        return
    guarded_dataset_total = next((r["guardedDatasetCases"] for r in rows if "guardedDatasetCases" in r), None)
    answered_total = (initial_info or {}).get("cases") or max(r["answered"] for r in rows)
    if guarded_dataset_total is None and answered_total == 151 and (
        initial_info is None
        or initial_info.get("datasetFile") == "initial_planning.jsonl"
        or ("datasetFile" not in initial_info and initial_info.get("cases") == 151)
    ):
        guarded_dataset_total = 18
    guard_summary = (
        f"The guard fires on {guarded_dataset_total} of the {answered_total} requests (none tool-gold); "
        if guarded_dataset_total is not None and answered_total is not None
        else "The dataset-wide negation count is unavailable for legacy records; "
    )
    out.append("Production's negation guard, offline: a guarded request never reaches the model "
               "(`orchestration/InitialToolPlanner.kt:100` calls `String.startsWithActionNegation()` "
               "at `action/BasicDraftActionParsers.kt:199` and takes the rule plan), so a guarded "
               "case is scored with the rules' `R` answer. Raw is the mode as it ran; guarded is what "
               "production would have done on the same model outputs. No model was called for this "
               "table. " + guard_summary +
               "a mode that answered fewer cases shows a smaller count, because only answered calls are scored.\n")
    table_rows = []
    for r in sorted(rows, key=lambda x: (x["model"], SUITE_LETTER.get(x["suite"], x["suite"]), x["mode"])):
        answered = r["answered"]
        # Same one formatting path as every other table: the annotation comes from the run's error
        # count, never from a second, table-local rule.
        key = (r["suite"], r["model"], r["mode"])
        table_rows.append([
            r["model"],
            f"{SUITE_LETTER.get(r['suite'], r['suite'])} {r['mode']}",
            run_cell(key, f"{r['rawCorrect']}/{answered}"),
            run_cell(key, f"{r['guardedCorrect']}/{answered}"),
            run_cell(key, r["guardedCases"]),
            run_cell(key, f"{r['rawFalsePositive']} -> {r['guardedFalsePositive']}"),
        ])
    out.append(table(["model", "suite / mode", "raw correct", "guarded correct (production)",
                      "negated requests", "no-tool false positives raw -> guarded"], table_rows))
    out.append("")
    split_rows = []
    for r in sorted(rows, key=lambda x: (x["model"], SUITE_LETTER.get(x["suite"], x["suite"]), x["mode"])):
        if not r["rawFalsePositive"]:
            continue
        split_rows.append([
            r["model"],
            f"{SUITE_LETTER.get(r['suite'], r['suite'])} {r['mode']}",
            run_cell((r["suite"], r["model"], r["mode"]), r["rawFalsePositive"]),
            run_cell((r["suite"], r["model"], r["mode"]), r["fpOrchestrationOnly"]),
            run_cell((r["suite"], r["model"], r["mode"]), r["fpDeviceNetworkExternal"]),
            run_cell((r["suite"], r["model"], r["mode"]), r["fpPrivateRead"]),
            run_cell((r["suite"], r["model"], r["mode"]), r["fpNetworkEgress"]),
        ])
    if split_rows:
        out.append("Every no-tool false positive, split. **Orchestration-only** = the production "
                   "`ToolSpec.capability == ToolCapability.Orchestration`, a hallucinated (unregistered) "
                   "name, or `answer`: no device, network or external-app effect. **Device / network / "
                   "external-app** = everything else, i.e. an executable action on the phone. Private "
                   "reads (`read_clipboard`, screen text, notifications) and network egress "
                   "(`web_search`, `search_maps`, `open_deep_link`) are named separately because they "
                   "are the privacy-relevant subset, and are already counted inside the action column:\n")
        out.append(table(["model", "suite / mode", "false positives", "orchestration only",
                          "device/network/external", "of which private reads", "of which network egress"],
                         split_rows))
        out.append("")
    else:
        out.append("No no-tool false positives in the rows above, so nothing to split.\n")


# ---- Production RuleFirstGatedModel, measured end to end -----------------------------------------


def gated_e2e(records, models, out, initial_info=None):
    """Decision stages and false-positive classes from the real production planner, not a composite."""
    keys = [key for key in keys_for(SUITE_B, records, models) if key[2] == "R-gated-e2e"]
    if not keys:
        out.append("Not run: `R-gated-e2e` has no saved per-case records on this host.\n")
        return
    out.append("`R-gated-e2e` drives production `InitialToolPlanner.planLocalModelFirst` and "
               "`HybridActionPlanningRuntime` on the production `.litertlm` files. Rules and the negation "
               "guard run before the strict 0.9 action-intent gate; only an open gate reaches production "
               "retrieval, the `answer` choice, the fixed action prompt, the `web_search` filter, strict "
               "parsing, registry validation and `SafetyPolicy`. These are measured final plans, not an "
               "offline combination of earlier model outputs. Gate JSON validity is the production "
               "parser's verdict: fences and trailing prose are invalid, never stripped by the lab.\n")
    stages = ["rule", "negation guard", "gate closed", "gate open → accepted", "gate open → answer",
              "gate open → failed"]
    rows = []
    for key in keys:
        rs = records[key]
        counter = Counter(r.get("decisionStage") for r in rs)
        valid = sum(1 for r in rs if r.get("gateRawJsonValid") is True)
        invalid = sum(1 for r in rs if r.get("gateRawJsonValid") is False)
        unavailable = sum(1 for r in rs if r.get("modelCalls") and r.get("gateRawJsonValid") is None)
        rows.append([key[1], key[2], run_cell(key, f"{sum(r['correct'] for r in rs)}/{len(rs)}")]
                    + [run_cell(key, counter.get(stage, 0)) for stage in stages]
                    + [run_cell(key, valid), run_cell(key, invalid), run_cell(key, unavailable)])
    out.append(table(["model", "mode", "overall correct"] + stages
                     + ["gate JSON valid", "gate JSON invalid", "gate unavailable"], rows))
    out.append("")
    rows = []
    for key in keys:
        rs = records[key]
        no_tool_rs = [r for r in rs if not r["expectsTool"]]
        rows.append([
            key[1], key[2],
            run_cell(key, len(no_tool_rs)),
            run_cell(key, sum(1 for r in rs if r.get("falsePositive"))),
            run_cell(key, sum(1 for r in rs if r.get("fpOrchestrationOnly"))),
            run_cell(key, sum(1 for r in rs if r.get("fpDeviceNetworkExternal"))),
            run_cell(key, sum(1 for r in rs if r.get("fpPrivateRead"))),
            run_cell(key, sum(1 for r in rs if r.get("fpNetworkEgress"))),
        ])
    out.append("No-tool false positives in the final accepted plan (per-model denominators). "
               "Orchestration-only choices have no device, network or external-app effect; private reads and network egress "
               "are the privacy-relevant subsets of the executable-action column:\n")
    out.append(table(["model", "mode", "no-tool n", "false positives", "orchestration only",
                      "device/network/external", "of which private reads", "of which network egress"], rows))
    out.append("")
    gated_e2e_comparison(records, keys, out, initial_info)


def e2e_proposed_tool(record):
    """The proposed call even when registry/argument validation rejected its final plan."""
    tool = (record.get("answer") or {}).get("tool")
    if tool:
        return tool
    for output in record.get("modelOutputs") or []:
        if output.get("phase") != "proposal":
            continue
        match = re.match(r"call:([^\s]+)", output.get("rawPreview") or "")
        if match:
            return match.group(1)
    return "no tool"


def gated_e2e_fence(records, models, out):
    """The lab-only whole-fence gate transform, with a final-outcome delta against strict production."""
    mode = "R-gated-e2e-fence"
    baseline_mode = "R-gated-e2e"
    keys = [key for key in keys_for(SUITE_B, records, models) if key[2] == mode]
    if not keys:
        out.append(f"Not run: `{mode}` has no saved per-case records on this host.\n")
        return
    out.append(f"`{mode}` changes only the test adapter's gate reply: exactly one whole-reply Markdown "
               "fence is removed before the unchanged production parser and planner run. The original "
               "reply stays in `gateRawPreview`; proposal calls are never transformed. This is not a "
               "production parser change. The delta table includes every case whose final decision "
               "stage, correctness or proposed tool differs from `R-gated-e2e`, and no other case.\n")
    stages = ["rule", "negation guard", "gate closed", "gate open → accepted", "gate open → answer",
              "gate open → failed"]
    score_rows = []
    stage_rows = []
    delta_rows = []
    for key in keys:
        rs = records[key]
        baseline_key = (SUITE_B, key[1], baseline_mode)
        tool_rs = [r for r in rs if r.get("expectsTool")]
        no_tool_rs = [r for r in rs if not r.get("expectsTool")]
        score_rows.append([
            key[1],
            run_cell(key, f"{sum(bool(r['correct']) for r in rs)}/{len(rs)}"),
            run_cell(key, f"{sum(bool(r['toolCorrect']) for r in tool_rs)}/{len(tool_rs)}"),
            run_cell(key, f"{sum(bool(r['argsExact']) for r in tool_rs)}/{len(tool_rs)}"),
            run_cell(key, f"{sum(bool(r.get('falsePositive')) for r in no_tool_rs)}/{len(no_tool_rs)}"),
            run_cell(key, sum(bool(r.get("fpOrchestrationOnly")) for r in no_tool_rs)),
            run_cell(key, sum(bool(r.get("fpDeviceNetworkExternal")) for r in no_tool_rs)),
            run_cell(key, sum(bool(r.get("privacyRelevant")) for r in no_tool_rs)),
            run_cell(key, sum(bool(r.get("fpPrivateRead")) for r in no_tool_rs)),
            run_cell(key, sum(bool(r.get("fpNetworkEgress")) for r in no_tool_rs)),
        ])
        counter = Counter(r.get("decisionStage") for r in rs)
        stage_rows.append(
            [key[1]]
            + [run_cell(key, counter.get(stage, 0)) for stage in stages]
            + [run_cell(key, sum(bool(r.get("gateOpen")) for r in rs)),
               run_cell(key, sum(bool(r.get("gateFenceUnwrapped")) for r in rs))],
        )
        before = {r["caseId"]: r for r in records.get(baseline_key, [])}
        for after in rs:
            prior = before.get(after["caseId"])
            if prior is None:
                continue
            prior_tool = e2e_proposed_tool(prior)
            after_tool = e2e_proposed_tool(after)
            if (prior.get("decisionStage") == after.get("decisionStage")
                    and bool(prior.get("correct")) == bool(after.get("correct"))
                    and prior_tool == after_tool):
                continue
            delta_rows.append([
                key[1], after["caseId"], preview(after.get("input"), 160),
                f"{prior.get('decisionStage') or '?'} → {after.get('decisionStage') or '?'}",
                f"{'✓' if prior.get('correct') else '✗'} → "
                f"{'✓' if after.get('correct') else '✗'}",
                after_tool,
            ])
    out.append(table(
        ["model", "correct", "tool correct", "args exact", "no-tool FP", "FP orchestration only",
         "FP device/network/external", "FP privacy flagged", "of which private reads",
         "of which network egress"],
        score_rows,
    ))
    out.append("")
    out.append(table(
        ["model"] + stages + ["gate open (total)", "gate fence unwrapped"], stage_rows,
    ))
    out.append("")
    if delta_rows:
        out.append(table(
            ["model", "case", "input", "stage before → after", "correct before → after", "tool proposed"],
            delta_rows,
        ))
    else:
        out.append("No case changed its final decision stage, correctness or proposed tool.")
    out.append("")


def closed_answer(rule):
    return {"correct": not rule["expectsTool"], "falsePositive": False,
            "answer": {"kind": "stop", "tool": None, "args": {}}}


def plan_outcome(record):
    answer = record.get("answer") or {}
    tool = answer.get("tool")
    outcome = tool or "no tool"
    return f"{'✓' if record.get('correct') else '✗'} {outcome}"


def e2e_case_explanation(record):
    stage = record.get("decisionStage") or "?"
    if stage == "rule":
        return f"rule fast path ({record.get('planSource') or 'rule'}); zero model calls"
    if stage == "negation guard":
        return "production negation guard; zero model calls"
    reason = record.get("gateReason") or "?"
    if stage == "gate closed":
        if reason == "local_model_action_gate_invalid_output":
            raw = record.get("gateRawPreview") or ""
            shape = "fenced JSON" if raw.lstrip().startswith("```") else "non-strict JSON"
            return f"strict gate rejected {shape}; no proposal call"
        return f"gate closed: {reason}; no proposal call"
    outcome = record.get("proposalOutcome") or "no outcome tag"
    if stage == "gate open → accepted":
        tool = (record.get("answer") or {}).get("tool") or "?"
        return f"gate open; accepted {tool} ({outcome})"
    if stage == "gate open → answer":
        return "gate open; model chose the production `answer` choice"
    if (record.get("metrics") or {}).get("error"):
        return f"gate open; engine error: {(record.get('metrics') or {}).get('error')}"
    return f"gate open; proposal failed closed: {outcome}"


def gated_e2e_comparison(records, e2e_keys, out, initial_info=None):
    """Case-level delta against R and the coordinator's exact saved text-fixed offline estimate."""
    if (initial_info is not None and initial_info.get("datasetFile")
            and initial_info["datasetFile"] != "initial_planning.jsonl"):
        return
    rules_list = records.get((SUITE_B, "rules", "R"), [])
    if len(rules_list) != 151:
        return
    rules = {r["caseId"]: r for r in rules_list}
    rows = []
    differences = []
    for key in e2e_keys:
        model = key[1]
        actual_list = records[key]
        if len(actual_list) != 151:
            continue
        gate_key = (SUITE_E, model, "json-prompt")
        estimate_key = (SUITE_B, model, "M-topk-fix-lmf")
        gates = {r["caseId"]: r for r in records.get(gate_key, [])}
        proposals = {r["caseId"]: r for r in records.get(estimate_key, [])}
        if len(gates) != 151 or len(proposals) != 151:
            continue
        actual = {r["caseId"]: r for r in actual_list}
        negated = {cid for cid, r in actual.items() if r.get("decisionStage") == "negation guard"}
        estimate = {}
        for cid, rule in rules.items():
            gate = gates[cid]
            proposal = proposals[cid]
            if rule["rawKind"] == "use_tool":
                estimate[cid] = rule
            elif cid in negated:
                estimate[cid] = closed_answer(rule)
            elif not errored(gate) and gate.get("gateOpen") and not errored(proposal):
                estimate[cid] = proposal
            else:
                estimate[cid] = closed_answer(rule)
        estimate_total = sum(bool(r["correct"]) and not errored(r) for r in estimate.values())
        estimate_fp = sum(bool(r.get("falsePositive")) and not errored(r) for r in estimate.values())
        actual_total = sum(bool(r["correct"]) and not errored(r) for r in actual.values())
        actual_fp = sum(bool(r.get("falsePositive")) and not errored(r) for r in actual.values())
        rule_total = sum(bool(r["correct"]) and not errored(r) for r in rules.values())
        contributors = [key, gate_key, estimate_key]
        rows.append([
            model,
            run_cell((SUITE_B, "rules", "R"), f"{rule_total}/151"),
            cell_for_runs([gate_key, estimate_key], f"{estimate_total}/151"),
            run_cell(key, f"{actual_total}/151"),
            cell_for_runs(contributors, f"{actual_total - estimate_total:+d}"),
            cell_for_runs(contributors + [(SUITE_B, "rules", "R")], f"{actual_total - rule_total:+d}"),
            cell_for_runs([gate_key, estimate_key], f"{estimate_fp}/51"),
            run_cell(key, f"{actual_fp}/51"),
        ])
        for cid, rule in rules.items():
            e = estimate[cid]
            a = actual[cid]
            if len({bool(rule["correct"]), bool(e["correct"]), bool(a["correct"])}) == 1:
                continue
            differences.append([
                model, cid, preview(rule["input"], 52),
                run_cell((SUITE_B, "rules", "R"), plan_outcome(rule)),
                cell_for_runs([gate_key, estimate_key], plan_outcome(e)),
                run_cell(key, plan_outcome(a)),
                e2e_case_explanation(a),
            ])
    if not rows:
        return
    out.append("Comparison with the same committed `R` records and the coordinator's offline estimate: "
               "rules, then the measured production negation cases, then the saved lenient Suite E gate, "
               "then saved `M-topk-fix-lmf` only where that gate opened. This reconstructs 131/151 for E2B "
               "and 133/151 for E4B with 2/51 false positives; it is a comparison baseline, not the "
               "end-to-end measurement. Δ is measured production minus the named baseline:\n")
    out.append(table(["model", "R correct", "offline estimate", "production e2e", "Δ estimate", "Δ R",
                      "estimate FP", "e2e FP"], rows))
    out.append("")
    out.append("Every case whose correctness differs between `R`, the offline estimate and measured "
               "production. `✓`/`✗` is the existing lab score; the text after it is the final tool or no "
               "tool. The last column is the actual production decision, so fenced-gate closures, answer "
               "choices, wrong accepted tools and retrieval failures are explicit rather than hidden in "
               "the totals:\n")
    out.append(table(["model", "case", "request", "R", "offline estimate", "production e2e",
                      "measured explanation"], differences))
    out.append("")


# ---- Main ----------------------------------------------------------------------------------------


def host_for(model, manifest=None):
    """The recorded host note that produced [model]'s numbers, or a name-prefix fallback.

    One folder can hold two hosts (Ollama and the LiteRT shim on the same day). Prefer the host the
    run recorded on the model itself; fall back to the name prefix, because `litert/*` ids come from
    `lab/scripts/litert_shim.py` and everything else from Ollama. This never inherits the folder's
    single `hostNote`, which only names the last host that wrote there. Tables use [host_label],
    which shortens this to the exact heading; this keeps the full note for prose.
    """
    recorded = ((manifest or {}).get("models") or {}).get(model, {}).get("host")
    if recorded:
        return recorded
    return LITERT_HOST_LABEL if model.startswith("litert/") else "Ollama"


def initial_planning_info(manifest):
    """Dataset metadata whether the run used text planning, native tools, or both."""
    suites = manifest.get("suites") or {}
    return suites.get(SUITE_B) or suites.get("native-tools") or {}


def run_facts(folder, manifest, out):
    out.append("Generated by `lab/scripts/summarize.py` from the JSONL files in this folder. Desktop numbers "
               "on Ollama and LiteRT-LM are not device evidence. Tables are split by host; the exact "
               "wording of each heading is the host label.\n")
    models = manifest.get("models") or {}
    hosts = OrderedDict()
    for name in models:
        hosts.setdefault(host_label(name, manifest), None)
    rows = [
        ["models in this folder, by host", "; ".join(hosts) or "?"],
        ["git HEAD at the last model run" if models else "git HEAD at the last recorded suite run",
         (manifest.get("gitHead") or "?") if models else (manifest.get("latestSuiteGitHead") or manifest.get("gitHead") or "?")],
        ["sampling (older lab modes)", json.dumps(manifest.get("samplingOptions", {}), ensure_ascii=False)],
    ]
    initial_info = initial_planning_info(manifest)
    if initial_info.get("caseSource"):
        rows.append(["initial-planning case source", initial_info["caseSource"]])
        rows.append(["initial-planning dataset", initial_info.get("datasetFile", "?")])
        rows.append(["initial-planning scored cases", initial_info.get("cases", "?")])
    one_shot = initial_info.get("oneShotSampling")
    if one_shot:
        rows.append(["R-gated-e2e production one-shot sampling", json.dumps(one_shot, ensure_ascii=False)])
        rows.append(["R-gated-e2e output caps", f"gate {initial_info.get('gateMaxOutputTokens')}; "
                                                f"proposal {initial_info.get('proposalMaxOutputTokens')}"])
    out.append(table(["item", "value"], rows))
    out.append("")
    if models:
        rows = []
        for name, info in models.items():
            details = info.get("details") or {}
            size = info.get("sizeBytes")
            rows.append([name, host_label(name, manifest), (info.get("digest") or "?")[:12],
                         details.get("parameter_size") or "?",
                         details.get("quantization_level") or "?", details.get("format") or "?",
                         details.get("runner") or "-", f"{size / 1e9:.2f} GB" if size else "?"])
        out.append(table(["model", "host", "digest", "parameters", "quantization", "format", "runner", "size"], rows))
        out.append("")
        notes = OrderedDict()
        for name, info in models.items():
            note = info.get("host")
            if note:
                notes.setdefault(note, []).append(name)
        if notes:
            out.append("Per-model host note as recorded by the run:\n")
            out.append(table(["host note (recorded)", "models"], [[note, ", ".join(names)]
                                                                    for note, names in notes.items()]))
            out.append("")


def is_heldout_initial_planning(manifest, records):
    """This folder is the independent 60-case evaluation, not the tuned 151-case campaign."""
    initial_info = initial_planning_info(manifest)
    return (initial_info.get("caseSource") == "heldout"
            or any(r.get("source") == "heldout_evaluation_only" for rs in records.values() for r in rs))


def heldout_initial_planning(records, models, out):
    """Held-out overall, gated-vs-rules, FP-class and fine-grained category results.

    The ordinary no_tool group alone is the requested FP denominator. Adversarial no-tool cases stay
    visible as their own column and category rows instead of being silently folded into that rate.
    """
    keys = [key for key in keys_for(SUITE_B, records, models)
            if key[2] in ("R", "R-gated-e2e")]
    if not keys:
        out.append("No held-out `R` or `R-gated-e2e` results.\n")
        return
    out.append("**Held-out evaluation only.** This is a different, harder, non-tuned set; do not compare "
               "its absolute numbers with the tuned 151-case campaign. Correct means the exact pinned "
               "arguments on tool-gold cases and no executed tool on no-tool cases. The `no_tool` group "
               "is the ordinary FP denominator; adversarial no-tool cases are reported separately.\n")
    rules = {r["caseId"]: r for r in records.get((SUITE_B, "rules", "R"), [])}
    rules_correct = sum(bool(r["correct"]) and not errored(r) for r in rules.values())
    rows = []
    for key in keys:
        rs = records[key]
        tool_rs = [r for r in rs if r["expectsTool"]]
        ordinary_none = [r for r in rs if r.get("group") == "no_tool" and not r["expectsTool"]]
        adversarial_none = [r for r in rs if r.get("group") == "adversarial" and not r["expectsTool"]]
        by_id = {r["caseId"]: r for r in rs}
        fixed = [case_id for case_id, rule in rules.items()
                 if not rule["correct"] and by_id.get(case_id, {}).get("correct") and not errored(by_id[case_id])]
        broken = [r["caseId"] for r in rs
                  if not r["correct"] and not errored(r) and rules.get(r["caseId"], {}).get("correct")]
        delta = (f"{sum(bool(r['correct']) and not errored(r) for r in rs) - rules_correct:+d}"
                 if key[1] not in RULE_MODELS and len(by_id) == len(rules) else "-")
        rows.append([
            key[1], key[2],
            score_cell(key, rs, sum(bool(r["correct"]) and not errored(r) for r in rs)),
            score_cell(key, tool_rs, sum(bool(r["toolCorrect"]) and not errored(r) for r in tool_rs)),
            score_cell(key, tool_rs, sum(bool(r["argsExact"]) and not errored(r) for r in tool_rs)),
            score_cell(key, ordinary_none, sum(bool(r.get("falsePositive")) and not errored(r) for r in ordinary_none)),
            run_cell(key, sum(bool(r.get("fpOrchestrationOnly")) and not errored(r) for r in ordinary_none)),
            run_cell(key, sum(bool(r.get("fpDeviceNetworkExternal")) and not errored(r) for r in ordinary_none)),
            run_cell(key, sum(bool(r.get("privacyRelevant")) and not errored(r) for r in ordinary_none)),
            run_cell(key, sum(bool(r.get("fpPrivateRead")) and not errored(r) for r in ordinary_none)),
            run_cell(key, sum(bool(r.get("fpNetworkEgress")) and not errored(r) for r in ordinary_none)),
            score_cell(key, adversarial_none,
                       sum(bool(r.get("falsePositive")) for r in adversarial_none)),
            run_cell(key, sum(bool(r.get("gateOpen")) for r in rs))
            if key[2] == "R-gated-e2e" else "-",
            run_cell(key, sum(errored(r) for r in rs)),
            run_cell(key, delta), run_cell(key, len(fixed)), run_cell(key, len(broken)),
        ])
    out.append(table([
        "model", "mode", "correct", "tool correct", "args exact", "ordinary no-tool FP",
        "FP orchestration only", "FP device/network/external", "FP privacy flagged",
        "of which private reads", "of which network egress", "adversarial no-tool FP",
        "gate open", "errors", "correct delta vs R", "fixed vs R", "broken vs R",
    ], rows))
    out.append("")
    out.append("Fixed/broken and the score delta are within this held-out set only. An errored call is "
               "neither a fix nor a break. Every run-derived cell carries its Decision-L1 error "
               "annotation when needed.\n")

    stages = ["rule", "negation guard", "gate closed", "gate open → accepted", "gate open → answer",
              "gate open → failed"]
    rows = []
    for key in keys:
        if key[2] != "R-gated-e2e":
            continue
        rs = records[key]
        counter = Counter(r.get("decisionStage") for r in rs)
        rows.append([key[1], key[2]]
                    + [run_cell(key, counter.get(stage, 0)) for stage in stages]
                    + [run_cell(key, sum(bool(r.get("gateOpen")) for r in rs)),
                       run_cell(key, sum(r.get("gateRawJsonValid") is True for r in rs)),
                       run_cell(key, sum(r.get("gateRawJsonValid") is False for r in rs))])
    if rows:
        out.append("Production `RuleFirstGatedModel` decision stages and gate parse outcomes. The gate "
                   "parser is the merged production parser, including its one whole-reply fence "
                   "handling; no lab-only fence transform is used.\n")
        out.append(table(["model", "mode"] + stages
                         + ["gate open (total)", "gate JSON valid", "gate JSON invalid"], rows))
        out.append("")

    categories = OrderedDict()
    for key in keys:
        for r in records[key]:
            categories[(r.get("group") or "ungrouped", r.get("category") or "uncategorized")] = None
    rows = []
    for key in keys:
        by_category = defaultdict(list)
        for r in records[key]:
            by_category[(r.get("group") or "ungrouped", r.get("category") or "uncategorized")].append(r)
        for group, category in categories:
            rs = by_category[(group, category)]
            rows.append([key[1], key[2], group, category, len(rs),
                         score_cell(key, rs, sum(bool(r["correct"]) and not errored(r) for r in rs))])
    out.append("Per-category correct results. `no_tool / tool_domain_question` and both negation rows "
               "are explicit so tool-domain questions and negations cannot disappear inside a broad "
               "family aggregate.\n")
    out.append(table(["model", "mode", "group", "category", "n", "correct"], rows))
    out.append("")


def generated(folder, manifest, records, models):
    initial_info = initial_planning_info(manifest)
    out = []
    out.append("## Run facts\n")
    run_facts(folder, manifest, out)
    fidelity = load_fidelity(folder)
    # One section per host. A generated table never carries rows from two hosts: the Ollama GGUF
    # numbers and the LiteRT numbers on the production `.litertlm` files measure different weights
    # on different runtimes, and reading down a mixed column would compare them.
    groups = host_groups(manifest, records, models)
    if is_heldout_initial_planning(manifest, records):
        if not groups:
            groups = {None: []}
        for label, group_models in groups.items():
            host_models = [m for m in models if m in RULE_MODELS] + group_models
            out.append(f"## {label}\n" if label else "## Rules only (no model host)\n")
            if label:
                out.append("Every table below was produced on this host only. Devbox CPU measurements "
                           "are not device evidence.\n")
            out.append("### Suite B held-out evaluation\n")
            heldout_initial_planning(records, host_models, out)
            out.append("### Latency and prompt size\n")
            latency(records, host_models, out)
        out.append("Failure examples for every group are in [`failure_examples.md`](failure_examples.md).\n")
        return "\n".join(out).rstrip() + "\n"
    if not groups:
        out.append("## No model results\n")
        out.append("Only rule baselines were recorded in this folder.\n")
        groups = {None: []}
    for label, group_models in groups.items():
        host_models = [m for m in models if m in RULE_MODELS] + group_models
        out.append(f"## {label}\n" if label else "## Rules only (no model host)\n")
        if label:
            out.append("Every table below was produced on this host only.\n")
        out.append("### Results at a glance\n")
        headline(records, host_models, manifest, out)
        out.append("### Suite A: observation step\n")
        suite_a(records, host_models, out)
        out.append("### Suite B: initial planning (`call:` text format)\n")
        suite_b(records, host_models, out)
        out.append("### Suite C: native tool calling\n")
        suite_c(records, host_models, manifest, out)
        out.append("### Suites B and C by request family\n")
        initial_categories(records, host_models, out)
        out.append("### Suites B and C failure taxonomy\n")
        failure_taxonomy(records, host_models, out)
        out.append("### Suite D: cloud/edge routing\n")
        suite_d(records, host_models, out)
        out.append("### Suite E: local model as an action gate\n")
        suite_e(records, host_models, out)
        out.append("### Production-faithful LocalModelFirst\n")
        production_faithful(fidelity, host_models, out, initial_info)
        out.append("### Production RuleFirstGatedModel end-to-end\n")
        gated_e2e(records, host_models, out, initial_info)
        out.append("### R-gated-e2e-fence: lab-only single-fence gate\n")
        gated_e2e_fence(records, host_models, out)
        out.append("### Offline composites\n")
        composite_r_then_model(records, host_models, out)
        composite_gate(records, host_models, out)
        out.append("### Latency and prompt size\n")
        latency(records, host_models, out)
    out.append("## Tool retriever recall (tuning campaign)\n")
    out.append("Host-independent: computed once from the tuned 151-case campaign, not from the selected evaluation dataset. "
               "The lexical retriever and the prompt it builds are the same on every host.\n")
    retriever(manifest, out)
    out.append("Failure examples for every group are in [`failure_examples.md`](failure_examples.md).\n")
    return "\n".join(out).rstrip() + "\n"


def write_summary(folder, manifest, body):
    """Write the generated block into SUMMARY.md, preserving the hand-written text around it.

    Returns the target path; [summary_text] computes the full document without writing it, so
    `--check` can regenerate in memory and compare against the committed file.
    """
    target = folder / "SUMMARY.md"
    target.write_text(summary_text(folder, manifest, body, target), encoding="utf-8")
    return target


def summary_text(folder, manifest, body, target):
    """The whole SUMMARY.md for [body]: the committed document with its generated block replaced.

    The leaf text outside the GENERATED markers is hand-written and survives a rerun, so
    regeneration only swaps the block. Called by `--check` with the committed file as [target],
    which is how drift inside the block is detected without touching the file.
    """
    block = f"{GENERATED_START}\n\n{body}\n{GENERATED_END}"
    if target.is_file():
        text = target.read_text(encoding="utf-8")
        start, end = text.find(GENERATED_START), text.find(GENERATED_END)
        if start >= 0 and end > start:
            return text[:start] + block + text[end + len(GENERATED_END):]
    title = f"# Solin capability lab: run {manifest.get('runDate', folder.name)}\n\n"
    tail = "\n\n## What this means for the phone\n\n_Not written yet._\n"
    return title + block + tail


# ---- Decision L1 check ---------------------------------------------------------------------------
#
# `--check` audits a regenerated folder: every generated row and every hand-written line that
# cites a run with errored cases must carry that run's error count. It reads SUMMARY.md (so it
# covers the narrative outside the GENERATED markers, which this script never rewrites) and
# resolves each run to the labels the file can use for it.
#
# Three guards, one invariant:
#   1. exact annotation tokens. `3 errored` must appear with its own boundaries: the substring
#      `3/151` inside `83/151` is not an annotation, and neither is another run's `1 errored`.
#   2. every errored run a line cites. A row may draw on several runs (one per mode column), and
#      each of them needs its own annotation; one annotation does not cover the others.
#   3. drift. The generated block is regenerated from the JSONL in memory and compared with the
#      committed text, so a hand edit to a generated cell (which no annotation rule could see,
#      because the run labels live in the header) is caught.

# Short model names the narrative uses for a model id. Kept explicit: guessing an alias from a
# model id would silently stop checking that model if the id changed.
MODEL_ALIASES = {
    "litert/gemma-4-e2b": ("E2B",),
    "litert/gemma-4-e4b": ("E4B",),
    "litert/mobile-actions-270m": ("270M",),
}
SUITE_BY_LETTER = {letter: suite for suite, letter in SUITE_LETTER.items()}
# A header cell that names a run column: `B R`, `B M-topk-fix`, `C N-topk-lmf`, `D json-prompt`.
MODE_COLUMN = re.compile(r"^([A-E]) ([A-Za-z][\w-]*)$")
# A row cell that names a run the same way, in tables whose header is `suite / mode`.
SUITE_MODE_CELL = re.compile(r"^([A-E]) `?([A-Za-z][\w-]*)`?$")
# A markdown heading that names the suite a `model | mode | …` table below it belongs to.
SUITE_HEADING = re.compile(r"^#{2,6} Suite ([A-E])\b")


def model_labels(model):
    """Every label SUMMARY.md may use for [model]: the id itself and its short alias."""
    return (model,) + MODEL_ALIASES.get(model, ())


def cites(line, model, mode):
    """True when [line] names this model and this mode, as separate tokens.

    The mode needs a token boundary so `N-topk` does not match `N-topk-lmf`, which is a different
    run with a different error count.
    """
    if mode not in line:
        return False
    if not re.search(r"(?<![\w/-])" + re.escape(mode) + r"(?![\w-])", line):
        return False
    return any(label in line for label in model_labels(model))


def annotation_patterns(n, e, population=None):
    """The exact annotation forms one run's error count may take (Decision L1).

    [population] is the size of the cell the annotation sits in. A generated table cell renders
    `errored K/K` for a fully errored run over that cell's population, which in a per-family or
    per-argument-key table is smaller than the run; it defaults to the run size, so prose and
    hand-written rows must use the run's own `N`. A partly errored run renders `E errored`; the
    hand-written narrative may write the same count as `E/N errored` or `E of N errored`.

    A bare fraction is never accepted: `3/151` must not match inside `83/151`, which is why every
    fraction form is anchored on both boundaries and always carries the word `errored`.
    """
    if e == n:
        if population is None:
            forms = [rf"(?<![\w/])errored {n}/{n}(?![\w/])"]
        else:
            # A table row's cells cover different sub-populations (families, arg keys), so any
            # self-consistent `errored K/K` in the row is the run's own render.
            forms = [r"(?<![\w/])errored (\d+)/\1(?![\w/])"]
        return forms + [rf"(?<![\w/]){n}/{n} errored(?![\w/])",
                        rf"(?<![\w/]){n} of {n} errored(?![\w/])"]
    return [rf"(?<![\w/]){e} errored(?![\w/])", rf"(?<![\w/]){e}/{n} errored(?![\w/])",
            rf"(?<![\w/]){e} of {n} errored(?![\w/])"]


def annotation_forms(n, e):
    """The human-readable annotation forms this run's error count may take (Decision L1)."""
    if e == n:
        return (f"errored N/N (N is the cell's population, the run has {n})",
                f"{n}/{n} errored", f"{n} of {n} errored")
    return (f"{e} errored", f"{e}/{n} errored", f"{e} of {n} errored")


def has_annotation(line, n, e, population=None):
    """True when [line] carries one of this run's exact annotation forms, with boundaries."""
    return any(re.search(pattern, line)
               for pattern in annotation_patterns(n, e, population))


def cells_of(line):
    """The cells of one markdown table row, or None when [line] is not a row."""
    stripped = line.strip()
    if not stripped.startswith("|"):
        return None
    return [cell.strip() for cell in stripped.strip("|").split("|")]


def is_separator(cells):
    """True for a markdown `| --- | :---: |` separator row."""
    return bool(cells) and all(set(cell) <= set("-: ") and cell for cell in cells)


def table_rows(lines):
    """Yield `(index, header, cells)` for every markdown table data row in [lines] (0-based).

    The header is the row immediately above the nearest `| --- |` separator among the contiguous
    `|` lines before the row. Working from the separator, not from the top of the block, keeps a
    table with no blank line before it from picking up the previous table's header.
    """
    for index, line in enumerate(lines):
        cells = cells_of(line)
        if cells is None or is_separator(cells):
            continue
        if index + 1 < len(lines) and is_separator(cells_of(lines[index + 1])):
            continue  # this line is a table header
        header = None
        for above in range(index - 1, -1, -1):
            previous = cells_of(lines[above])
            if previous is None:
                break
            if is_separator(previous) and above > 0:
                candidate = cells_of(lines[above - 1])
                if candidate is not None:
                    header = candidate
                break
        if header is None:
            continue
        yield index, header, cells


def clean_cell(text):
    """Strip decoration and a trailing annotation from a label cell."""
    text = text.replace("`", "").replace("*", "").strip()
    return re.sub(r"\s*\((?:errored \d+/\d+|\d+ errored)\)\s*$", "", text).strip()


def heading_suite(lines, index):
    """The suite letter of the nearest `### Suite X` heading above [index], or None."""
    for line in reversed(lines[:index]):
        match = SUITE_HEADING.match(line)
        if match:
            return SUITE_BY_LETTER[match.group(1)]
    return None


def row_runs(lines, index, header, cells):
    """Every run a table row draws on, resolved from its column headers (Decision L1).

    A column header may name a run directly (`B M-topk`), or the row may name it in a `suite` /
    `mode` cell. A `model | mode` table with no suite column takes the suite of the heading above
    it; when that is absent, every run matching the model and mode is used, because the row then
    stands for all of them.
    """
    columns = {clean_cell(name): position for position, name in enumerate(header)}
    model_position = columns.get("model")
    if model_position is None or model_position >= len(cells):
        return []
    model = clean_cell(cells[model_position])
    if not model:
        return []
    suite_letter = None
    if "suite" in columns and columns["suite"] < len(cells):
        suite_letter = clean_cell(cells[columns["suite"]])[:1]
    runs = []

    def resolve(mode, suite=None):
        mode = clean_cell(mode)
        if not mode:
            return
        if suite:
            key = (suite, model, mode)
            if key in RUN_ERRORS:
                runs.append(key)
            return
        candidates = [key for key in RUN_ERRORS if key[1] == model and key[2] == mode]
        runs.extend(candidates)

    for name, position in columns.items():
        if position >= len(cells):
            continue
        match = MODE_COLUMN.match(name)
        if match:
            resolve(match.group(2), SUITE_BY_LETTER[match.group(1)])
        elif name == "suite / mode":
            cell = SUITE_MODE_CELL.match(clean_cell(cells[position]))
            if cell:
                resolve(cell.group(2), SUITE_BY_LETTER[cell.group(1)])
        elif name == "mode":
            resolve(cells[position], SUITE_BY_LETTER.get(suite_letter)
                    or heading_suite(lines, index))
    return runs


def drift_problems(summary, committed, regenerated):
    """Report the first line where the committed file differs from a regeneration (FU-L1)."""
    if committed == regenerated:
        return []
    for number, (want, got) in enumerate(zip(committed, regenerated), start=1):
        if want != got:
            return [f"{summary}:{number}: generated content drifted from the JSONL "
                    f"(committed: {want.strip()[:120]!r}; regenerated: {got.strip()[:120]!r}). "
                    f"Regenerate with `python3 -I lab/scripts/summarize.py {summary.parent}`, "
                    f"never edit a generated cell."]
    return [f"{summary}: generated block has {len(committed)} lines but regenerates to "
            f"{len(regenerated)}; regenerate rather than editing the block."]


def annotation_problems(summary, lines):
    """Report every line that cites an errored run without that run's own error count."""
    dirty = {key: (n, e) for key, (n, e) in RUN_ERRORS.items() if e}
    table = {index: (header, cells) for index, header, cells in table_rows(lines)}
    problems = []
    for index, line in enumerate(lines):
        cited = set()
        in_table = index in table
        if in_table:
            header, cells = table[index]
            cited.update(row_runs(lines, index, header, cells))
        # Narrative (and any row whose columns do not name a run) cites a run by label.
        for key in dirty:
            if cites(line, key[1], key[2]):
                cited.add(key)
        for key in sorted(cited):
            if key not in dirty:
                continue
            n, e = dirty[key]
            # A generated table cell may carry a sub-population `errored K/K`; prose must use N.
            population = n if in_table else None
            if not has_annotation(line, n, e, population):
                forms = " or ".join(f"`{form}`" for form in annotation_forms(n, e))
                problems.append(
                    f"{summary}:{index + 1}: cites {key[1]} {key[2]}, which has {e} errored of "
                    f"{n}, without its own error count ({forms}): "
                    f"{line.strip()[:160]}")
    return problems


def check_folder(folder):
    """Return the problems a regenerated folder's SUMMARY.md has, plus a summary tuple."""
    manifest_path = folder / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8")) if manifest_path.is_file() else {}
    records = load_records(folder)
    set_run_errors(records)
    models = model_order(manifest, records)
    summary = folder / "SUMMARY.md"
    if not summary.is_file():
        return [f"{summary}: missing (run summarize.py first)"], (0, 0)
    committed = summary.read_text(encoding="utf-8").splitlines()
    # Regenerate every generated section from the JSONL in memory; the hand-written text outside
    # the markers is preserved by [summary_text], so any difference is inside the block.
    regenerated = summary_text(folder, manifest, generated(folder, manifest, records, models),
                               summary).splitlines()
    dirty = sum(1 for _, e in RUN_ERRORS.values() if e)
    problems = drift_problems(summary, committed, regenerated)
    problems += annotation_problems(summary, committed)
    return problems, (dirty, len(committed))


def main(argv):
    args = [a for a in argv[1:] if a != "--check"]
    check = "--check" in argv[1:]
    if len(args) != 1:
        print(__doc__.strip())
        return 2
    folder = Path(args[0])
    if check:
        problems, (runs, lines) = check_folder(folder)
        for problem in problems:
            print(problem)
        if problems:
            drifted = sum(1 for problem in problems if "drifted" in problem)
            missing = len(problems) - drifted
            print(f"FAIL: {len(problems)} problem(s) in {lines} line(s) ({runs} errored run(s)): "
                  f"{missing} line(s) cite an errored run without its own error count, "
                  f"{drifted} markdown line(s) drifted from the JSONL.")
            return 1
        print(f"ok: {runs} errored run(s) annotated on every citing line, generated block matches "
              f"the JSONL ({lines} line(s) checked).")
        return 0
    manifest_path = folder / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8")) if manifest_path.is_file() else {}
    records = load_records(folder)
    set_run_errors(records)
    models = model_order(manifest, records)
    summary = write_summary(folder, manifest, generated(folder, manifest, records, models))
    examples = []
    failure_examples(records, models, examples)
    (folder / "failure_examples.md").write_text("\n".join(examples).rstrip() + "\n", encoding="utf-8")
    print(f"wrote {summary} and {folder / 'failure_examples.md'}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
