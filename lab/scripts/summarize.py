#!/usr/bin/env python3
"""Summarize one lab run: lab/results/<YYYYMMDD>/*.jsonl -> SUMMARY.md and failure_examples.md.

Standard library only. Run with: python3 -I lab/scripts/summarize.py lab/results/<YYYYMMDD>

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
SUITE_LETTER = {SUITE_A: "A", SUITE_B: "B", SUITE_C: "C", SUITE_D: "D"}
MODE_ORDER = {
    SUITE_A: ["replanner", "replanner-fix"],
    SUITE_B: ["R", "M-full", "M-full-fix", "M-topk", "M-topk-fix", "R+M"],
    SUITE_C: ["N-full", "N-topk", "N-topk-free"],
    SUITE_D: ["complexity", "json-prompt", "json-schema"],
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
        for line in path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line:
                record = json.loads(line)
                records[(record["suite"], record["model"], record["mode"])].append(record)
    return records


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


def answer_text(answer):
    if not answer:
        return "?"
    if answer.get("kind") == "stop":
        return "stop"
    tool = answer.get("tool") or "?"
    args = answer.get("args") or {}
    shown = ", ".join(f"{k}={preview(str(v), 40)}" for k, v in args.items())
    prefix = "invalid " if answer.get("kind") == "invalid" else ""
    return f"{prefix}{tool}({shown})"


def gold_initial_text(gold):
    tools = gold.get("tools") or []
    if not tools:
        return "no tool"
    args = gold.get("args") or {}
    shown = ", ".join(f"{k}={v}" for k, v in args.items())
    return "|".join(tools) + (f"({shown})" if shown else "")


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
    if record["correct"]:
        return None
    if record["rawKind"] == "error":
        return "error"
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
    """(tool right, tool and pinned args right) under the lenient reader, for a tool-gold record."""
    gold = record.get("gold") or {}
    name, args = lenient_answer(record.get("rawPreview"))
    if name is None or name not in (gold.get("tools") or []):
        return False, False
    if args is None:
        return True, False
    return True, all(arg_value_matches(k, v, args.get(k)) for k, v in (gold.get("args") or {}).items())


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
        return sum(r["finalCorrect"] for r in rs), len(rs)
    return sum(r["correct"] for r in rs), len(rs)


def headline_table(records, models, manifest, columns, cell):
    header = ["model"] + [f"{SUITE_LETTER[s]} {m}" for s, m in columns]
    rows = []
    for model in models:
        row = [model]
        for suite, mode in columns:
            rs = records.get((suite, model, mode))
            if rs:
                row.append(cell(suite, rs))
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


def headline(records, models, manifest, out):
    initial = present_columns(records, models, manifest, (SUITE_B, SUITE_C))
    if initial:
        out.append("Suites B and C, requests that need a tool: right first tool with every pinned argument.\n")
        out.append(headline_table(records, models, manifest, initial, lambda s, rs: frac(
            sum(r["argsExact"] for r in rs if r["expectsTool"]), sum(1 for r in rs if r["expectsTool"]))))
        out.append("")
        out.append("Suites B and C, requests that need no tool (chat, questions, explicit negations): nothing "
                   "executed. An invalid call executes nothing and counts as correct here.\n")
        out.append(headline_table(records, models, manifest, initial, lambda s, rs: frac(
            sum(r["correct"] for r in rs if not r["expectsTool"]), sum(1 for r in rs if not r["expectsTool"]))))
        out.append("")
        out.append("Suites B and C, all 151 requests together:\n")
        out.append(headline_table(records, models, manifest, initial, lambda s, rs: frac(*score_of(s, rs))))
        out.append("")
    other = present_columns(records, models, manifest, (SUITE_A, SUITE_D))
    if other:
        out.append("Suite A (final step after the replanner guards, 46 screens) and Suite D (placement on 26 "
                   "routing cases):\n")
        out.append(headline_table(records, models, manifest, other, lambda s, rs: frac(*score_of(s, rs))))
        out.append("")
    out.append("`rules` is the production rule path with no model. `-` means not run. `-fix` modes are the "
               "lab-only placeholder ablation, not what the app sends.\n")


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
            n,
            frac(sum(r["rawCorrect"] for r in rs), n),
            frac(sum(r["finalCorrect"] for r in rs), n),
            frac(sum(r["finalCorrect"] for r in action_cases), len(action_cases)),
            frac(sum(r["finalCorrect"] for r in stop_cases), len(stop_cases)),
            pct(len(parse_fail), len(attempted)),
            f"{statistics.mean(r['modelCalls'] for r in rs):.2f}",
        ])
    out.append(table(
        ["model", "n", "raw draft correct", "final correct", "final, action gold", "final, stop gold",
         "first call not a valid `call:`", "calls/step"],
        rows,
    ))
    out.append("")
    out.append("Raw draft = the first model call's parsed `call:` line, before any guard. Final = what "
               "`ModelObservationReplanner.planNext` returns after the guards and the optional second call; "
               "`null` counts as stop.\n")

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
        rows.append([key[1]] + [
            f"{sum(r['finalCorrect'] for r in by_cat[c])}/{len(by_cat[c])}" if by_cat[c] else "-"
            for c in categories
        ])
    out.append("Final correct by screen category:\n")
    out.append(table(["model"] + categories, rows))
    out.append("")

    classes = ["stopped when an action was expected", "acted when the gold is stop", "wrong tool",
               "right tool, wrong target or text"]
    rows = []
    for key in keys:
        counter = Counter(observation_failure(r) for r in records[key])
        first_calls = [r["calls"][0] for r in records[key] if r["calls"]]
        unknown = sum(1 for c in first_calls if (c.get("parseFailure") or "").startswith("Unknown tool"))
        malformed = sum(1 for c in first_calls if c.get("parseFailure") and not c["parseFailure"].startswith("Unknown tool"))
        rows.append([key[1]] + [counter.get(c, 0) for c in classes] + [malformed, unknown])
    out.append("Failure taxonomy (final answer), plus first-call format failures:\n")
    out.append(table(["model"] + classes + ["first call unparseable", "first call hallucinated tool"], rows))
    out.append("")

    reasons = Counter()
    per_model = {}
    for key in keys:
        counter = Counter()
        for r in records[key]:
            for reason in r["guardReasons"]:
                if reason != "accepted":
                    counter[reason] += 1
        per_model[key[1]] = counter
        reasons.update(counter)
    if reasons:
        ordered = [reason for reason, _ in reasons.most_common()]
        out.append("Replanner rejection reasons, counted per model call (a step can have two):\n")
        out.append(table(["model"] + ordered, [[m] + [per_model[m].get(r, 0) for r in ordered] for m in per_model]))
        out.append("")

    rows = []
    for key in keys:
        sources = Counter(r["finalSource"] for r in records[key])
        rows.append([key[1], ", ".join(f"{k} {v}" for k, v in sources.most_common())])
    out.append("Where the final answer came from:\n")
    out.append(table(["model", "final source"], rows))
    out.append("")


# ---- Suites B and C ------------------------------------------------------------------------------


def initial_rows(keys, records, native):
    rows = []
    for key in keys:
        rs = records[key]
        n = len(rs)
        tool_cases = [r for r in rs if r["expectsTool"]]
        none_cases = [r for r in rs if not r["expectsTool"]]
        attempted = [r for r in rs if r["rawKind"] not in ("text", "blank", "no_tool", "answer", "error")]
        if native:
            parsed = [r for r in attempted if r["rawKind"] in ("call", "multi_call")]
        elif key[2].startswith("M-"):
            parsed = [r for r in attempted if r["rawKind"] == "call"]
        else:
            attempted, parsed = [], []
        schema = [r for r in rs if r.get("schemaValid") is not None]
        rows.append([
            key[1],
            key[2],
            frac(sum(r["correct"] for r in rs), n),
            frac(sum(r["toolCorrect"] for r in tool_cases), len(tool_cases)),
            frac(sum(r["argsExact"] for r in tool_cases), len(tool_cases)),
            frac(sum(r["falsePositive"] for r in none_cases), len(none_cases)),
            pct(len(parsed), len(attempted)) if attempted else "-",
            pct(sum(r["schemaValid"] for r in schema), len(schema)),
        ])
    return rows


INITIAL_HEADER = ["model", "mode", "overall correct", "tool correct (tool gold)", "tool+args exact (tool gold)",
                  "false positive (no-tool gold)", "valid of attempted calls", "schema-valid"]


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
        if key[2] not in ("M-topk", "M-topk-fix"):
            continue
        rs = [r for r in records[key] if r["expectsTool"]]
        hits = [r for r in rs if r.get("retrieverHit")]
        outside = sum(1 for r in records[key] if r.get("outsideAdvertised"))
        topk_rows.append([key[1], key[2], frac(len(hits), len(rs)), frac(sum(r["correct"] for r in hits), len(hits)),
                          outside])
    if topk_rows:
        out.append("Top-8 modes with the lexical retriever:\n")
        out.append(table(["model", "mode", "retriever recall@8", "correct when gold tool retrieved",
                          "answers outside the 8"], topk_rows))
        out.append("")
    lenient_rows = []
    for key in keys:
        if not key[2].startswith("M-"):
            continue
        rs = [r for r in records[key] if r["expectsTool"]]
        scores = [lenient_scores(r) for r in rs]
        lenient_rows.append([key[1], key[2], frac(sum(r["toolCorrect"] for r in rs), len(rs)),
                             frac(sum(s[0] for s in scores), len(rs)), frac(sum(r["argsExact"] for r in rs), len(rs)),
                             frac(sum(s[1] for s in scores), len(rs))])
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
        rows.append([key[1], key[2], ", ".join(f"{k} {v}" for k, v in kinds.most_common())])
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
    sizes = Counter(family(r) for r in records[keys[0]])
    header = ["suite", "model", "mode"] + [f"{f} (n={sizes.get(f, 0)})" for f in families]
    rows = []
    for key in keys:
        by = defaultdict(list)
        for r in records[key]:
            by[family(r)].append(r)
        rows.append([SUITE_LETTER[key[0]], key[1], key[2]] + [
            f"{sum(r['correct'] for r in by[f])}/{len(by[f])}" if by[f] else "-" for f in families
        ])
    out.append(table(header, rows))
    out.append("")
    out.append("Family = the gold first tool's family; dataset cases labeled `in_app_search` (\"在淘宝搜索牛奶\", gold "
               "`open_app_by_name`) are split out. `no tool` = chat, questions and explicit negations.\n")


def failure_taxonomy(records, models, out):
    keys = [k for k in keys_for(SUITE_B, records, models) + keys_for(SUITE_C, records, models) if k[1] not in RULE_MODELS]
    if not keys:
        return
    rows = []
    for key in keys:
        failures = [initial_failure(r) for r in records[key]]
        counter = Counter(f for f in failures if f)
        placeholder = sum(1 for r in records[key] if initial_failure(r) == "hallucinated tool name" and copied_placeholder(r))
        rows.append([SUITE_LETTER[key[0]], key[1], key[2], sum(counter.values())]
                    + [counter.get(c, 0) for c in FAILURE_ORDER] + [placeholder])
    out.append(table(["suite", "model", "mode", "wrong"] + FAILURE_ORDER + ["of which literal `call:function`"], rows))
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
        gains = [r["caseId"] for r in rm if r["correct"] and not rules[r["caseId"]]["correct"]]
        losses = [r["caseId"] for r in rm if not r["correct"] and rules[r["caseId"]]["correct"]]
        model_used = sum(1 for r in rm if r.get("planSource") == "model")
        model_called = sum(1 for r in rm if r.get("modelCalls"))
        rows.append([model, model_called, model_used, len(gains), len(losses),
                     ", ".join(losses[:6]) + (" ..." if len(losses) > 6 else "")])
    if rows:
        out.append("`R+M` against `R` (production order with a model installed, against rules only):\n")
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
            recovered = [case_id for case_id in misses if by_id.get(case_id, {}).get("correct")]
            broken = [r["caseId"] for r in rs if not r["correct"] and rules.get(r["caseId"], {}).get("correct")]
            rows.append([model, mode, frac(len(recovered), len(misses)), len(broken),
                         ", ".join(recovered[:6]) + (" ..." if len(recovered) > 6 else "")])
    if rows:
        out.append(f"Model-only modes against `R`, case by case (`R` misses {len(misses)} cases):\n")
        out.append(table(["model", "mode", "R misses the model gets right", "R hits the model gets wrong",
                          "recovered cases"], rows))
        out.append("")


def plan_sources(keys, records, out):
    rows = []
    for key in keys:
        if key[2] not in ("R", "R+M"):
            continue
        counter = Counter(r.get("planSource", "?") for r in records[key])
        rows.append([key[1], key[2], ", ".join(f"{k} {v}" for k, v in counter.most_common())])
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
    rows = [[key[1], key[2]] + [f"{stats[key][n][0]}/{stats[key][n][1]}" if stats[key][n][1] else "-" for n in names]
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
        rs = records[key]
        n = len(rs)
        local = [r for r in rs if r["gold"] == "local"]
        remote = [r for r in rs if r["gold"] == "remote"]
        confident = [r for r in rs if r.get("confident")]
        is_rule = key[1] in RULE_MODELS
        rows.append([
            key[1],
            key[2],
            frac(sum(r["valid"] for r in rs), n),
            frac(sum(r["correct"] for r in rs), n),
            frac(sum(r["correct"] for r in local), len(local)),
            frac(sum(r["correct"] for r in remote), len(remote)),
            "-" if is_rule else frac(len(confident), n),
            "-" if is_rule else frac(sum(r["correct"] for r in confident), len(confident)),
            frac(sum(r["effectiveCorrect"] for r in rs), n),
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
    for suite in (SUITE_A, SUITE_B, SUITE_C, SUITE_D):
        for key in keys_for(suite, records, models):
            if key[1] in RULE_MODELS:
                continue
            calls = [m for r in records[key] for m in model_calls(r)]
            if not calls:
                continue
            walls = [m.get("wallMs") for m in calls]
            prompt = [m.get("promptTokens") for m in calls]
            rows.append([
                SUITE_LETTER[suite], key[1], key[2], len(calls),
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
               "would not. Use these numbers to compare modes and models with each other, not as phone latency.\n")


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
            wrong = [r for r in records[key] if not r["correct"]]
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
                               f"got {answer_text(r['answer'])}; raw `{preview(r.get('rawPreview'))}`")
            out.append("")
    out.append("## Suite D: wrong placement\n")
    for key in keys_for(SUITE_D, records, models):
        wrong = [r for r in records[key] if not r["correct"]]
        if not wrong:
            continue
        out.append(f"**{key[1]} {key[2]}** ({len(wrong)} wrong): " + "; ".join(
            f"`{r['caseId']}` gold {r['gold']} got {r.get('choice') or r.get('invalidReason')}"
            + (f" ({r['confidence']})" if r.get("confidence") is not None else "")
            for r in wrong
        ))
        out.append("")


# ---- Main ----------------------------------------------------------------------------------------


def run_facts(folder, manifest, out):
    out.append("Generated by `lab/scripts/summarize.py` from the JSONL files in this folder. Desktop numbers "
               "on Ollama are not device evidence.\n")
    rows = [
        ["Ollama", manifest.get("ollamaVersion", "?")],
        ["git HEAD at the last model run", manifest.get("gitHead", "?")],
        ["host", manifest.get("hostNote", "?")],
        ["sampling", json.dumps(manifest.get("samplingOptions", {}), ensure_ascii=False)],
    ]
    out.append(table(["item", "value"], rows))
    out.append("")
    models = manifest.get("models") or {}
    if models:
        rows = []
        for name, info in models.items():
            details = info.get("details") or {}
            size = info.get("sizeBytes")
            rows.append([name, (info.get("digest") or "?")[:12], details.get("parameter_size") or "?",
                         details.get("quantization_level") or "?", details.get("format") or "?",
                         details.get("runner") or "-", f"{size / 1e9:.2f} GB" if size else "?"])
        out.append(table(["model", "digest", "parameters", "quantization", "format", "runner", "size"], rows))
        out.append("")


def generated(folder, manifest, records, models):
    out = []
    out.append("## Results at a glance\n")
    headline(records, models, manifest, out)
    out.append("## Run facts\n")
    run_facts(folder, manifest, out)
    out.append("## Suite A: observation step\n")
    suite_a(records, models, out)
    out.append("## Suite B: initial planning (`call:` text format)\n")
    suite_b(records, models, out)
    out.append("## Suite C: native tool calling\n")
    suite_c(records, models, manifest, out)
    out.append("## Suites B and C by request family\n")
    initial_categories(records, models, out)
    out.append("## Suites B and C failure taxonomy\n")
    failure_taxonomy(records, models, out)
    out.append("## Suite D: cloud/edge routing\n")
    suite_d(records, models, out)
    out.append("## Latency and prompt size\n")
    latency(records, models, out)
    out.append("## Tool retriever recall\n")
    retriever(manifest, out)
    out.append("Failure examples for every group are in [`failure_examples.md`](failure_examples.md).\n")
    return "\n".join(out).rstrip() + "\n"


def write_summary(folder, manifest, body):
    target = folder / "SUMMARY.md"
    block = f"{GENERATED_START}\n\n{body}\n{GENERATED_END}"
    if target.is_file():
        text = target.read_text(encoding="utf-8")
        start, end = text.find(GENERATED_START), text.find(GENERATED_END)
        if start >= 0 and end > start:
            text = text[:start] + block + text[end + len(GENERATED_END):]
            target.write_text(text, encoding="utf-8")
            return target
    title = f"# Solin capability lab: run {manifest.get('runDate', folder.name)}\n\n"
    tail = "\n\n## What this means for the phone\n\n_Not written yet._\n"
    target.write_text(title + block + tail, encoding="utf-8")
    return target


def main(argv):
    if len(argv) != 2:
        print(__doc__.strip())
        return 2
    folder = Path(argv[1])
    manifest_path = folder / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8")) if manifest_path.is_file() else {}
    records = load_records(folder)
    models = model_order(manifest, records)
    summary = write_summary(folder, manifest, generated(folder, manifest, records, models))
    examples = []
    failure_examples(records, models, examples)
    (folder / "failure_examples.md").write_text("\n".join(examples).rstrip() + "\n", encoding="utf-8")
    print(f"wrote {summary} and {folder / 'failure_examples.md'}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
