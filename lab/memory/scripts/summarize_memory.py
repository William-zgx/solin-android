#!/usr/bin/env python3
"""Write lab/memory/results/<date>/SUMMARY.md from the bench's own metrics JSON.

The scoring is not re-implemented here: `MemoryFactExtractionBenchTest` and
`MemoryCompactionBenchTest` already compute every number with the production guards, and write it to
`facts__<model>.metrics.json` / `compaction__<model>.metrics.json`. This script only tabulates those
files, so it uses exactly the same scoring code as the Mac 20261107 bench.

Usage: python3 -I lab/memory/scripts/summarize_memory.py <results dir>
"""
import json
import sys
from pathlib import Path




def errored_calls(d):
    """Model calls whose engine failed, from the bench's `fallbacks` map.

    `EngineUnavailable` is what `LocalModelMemoryFactExtractor` records when the call itself threw
    (`LocalMemoryModelAnswer.Failed(EngineUnavailable)`), so it is the errored-case marker here, the
    same role `metrics.error` plays in the capability bench (Decision L1). It is counted separately
    from a guard rejection such as `Ungrounded` or `OverLength`, which means the model answered but
    the production guard refused the answer.
    """
    return sum(v for k, v in (d.get("fallbacks") or {}).items() if k in ("EngineUnavailable", "Error"))


def load(folder, suite):
    rows = []
    for path in sorted(folder.glob(f"{suite}__*.metrics.json")):
        data = json.loads(path.read_text(encoding="utf-8"))
        model = (data.get("model") or {})
        rows.append({
            "model": model.get("model") or path.name[len(suite) + 2:-len(".metrics.json")],
            "host": model.get("hostNote") or "",
            "runner": (model.get("details") or {}).get("runner") or model.get("ollamaVersion") or "?",
            "data": data,
        })
    return rows


def pct(value):
    return "n/a" if value is None else f"{100.0 * value:.1f}%"


def n(value, digits=3):
    return "n/a" if value is None else f"{value:.{digits}f}"


def num(value, digits=3):
    """A fraction value that the bench stores as a float in [0, 1]."""
    return pct(value) if isinstance(value, float) else str(value)


def lat(latency):
    latency = latency or {}
    return f"{latency.get('p50', '?')} / {latency.get('p95', '?')}"


def facts_section(rows, out):
    out.append("## Fact extraction (106 cases, 46 negative messages)\n")
    if not rows:
        out.append("_No fact-extraction metrics in this folder._\n")
        return
    out.append("Scored by the production `LocalModelMemoryFactExtractor` over `fact_extraction.jsonl`; "
               "precision/recall are over the merged suggestions the user would see, which is what the "
               "guards produce. `raw` is the model's facts before the guards. Every number comes from "
               "the bench's own metrics file, the same code the Mac bench used.\n")
    out.append("| model | merged P | merged R | raw P | raw R | negation FP rate | raw hallucination | parse failure | errors | p50 / p95 ms |")
    out.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |")
    for row in rows:
        d = row["data"]
        merged = d.get("merged") or {}
        raw = d.get("rawModel") or {}
        calls = d.get("modelCalls") or 0
        errored = errored_calls(d)
        out.append(f"| {row['model']} | {n(merged.get('precision'))} | {n(merged.get('recall'))} | "
                   f"{n(raw.get('precision'))} | {n(raw.get('recall'))} | "
                   f"{pct(d.get('mergedNegativeFalsePositiveRate'))} | "
                   f"{pct(d.get('hallucinationRate'))} | {pct(d.get('parseFailureRate'))} | "
                   f"{errored}/{calls} | {lat(d.get('latencyMs'))} |")
    out.append("")


def compaction_section(rows, out):
    out.append("## Compaction summaries (12 cases; must-keep retention)\n")
    if not rows:
        out.append("_No compaction metrics in this folder._\n")
        return
    out.append("Scored by the production `DefaultContextCompactor` with `modelSummaryEnabled = true`: "
               "the current **source-label** prompt (threat T1), whole-token number grounding and the "
               "bounded third-party gap. A summary is accepted when every line starts with a label that "
               "was sent, every number is grounded and the third-party guard passes; otherwise the "
               "heuristic summary is used. The old (unlabelled) outputs scored 0/48 acceptable under "
               "this rule, so these are the first acceptance numbers for the prompt production would "
               "actually send.\n")
    out.append("| model | must-keep retention (final) | heuristic retention | model summary accepted | "
               "fallbacks | ungrounded-number rate | p50 / p95 ms |")
    out.append("| --- | --- | --- | --- | --- | --- | --- |")
    for row in rows:
        d = row["data"]
        cases = d.get("cases") or 0
        used = d.get("modelSummaryUsedRate") or 0
        fallbacks = d.get("fallbacks") or {}
        errored = errored_calls(d)
        fb = ", ".join(f"{k} {v}" for k, v in sorted(fallbacks.items())) or "none"
        if errored:
            fb += f" (**{errored} engine errors**)"
        out.append(f"| {row['model']} | {pct(d.get('retentionFinal'))} | "
                   f"{pct(d.get('retentionHeuristic'))} | {round(used * cases)}/{cases} | {fb} | "
                   f"{pct(d.get('hallucinationRate'))} | {lat(d.get('latencyMs'))} |")
    out.append("")
    out.append("The third-party gap is part of the accepted/rejected decision: a summary line that "
               "attributes a fact to someone other than the user (我的好朋友…, 我们老板…) is rejected by "
               "the production guard, so it never counts as accepted. ACL-3 (`fx-129`, 我们公司的同事…) "
               "is the labelled probe for it in `fact_extraction.jsonl`.\n")


def evidence_line(rows):
    """The evidence class, read from what the bench recorded, never assumed by this script.

    One run can hold more than one host (the Mac Ollama run, the devbox LiteRT run), and a table
    must never mix them, so the line names every host it finds and, if there are several, says so.
    """
    hosts = sorted({row["host"] or "host not recorded" for row in rows})
    if not hosts:
        return "No model metrics in this folder."
    if len(hosts) == 1:
        return f"Evidence class: **{hosts[0]}**. Synthetic data, not held out: the guards were tuned on these cases. **Not device evidence.**"
    return ("Evidence classes, one table per host would be required before comparing them: "
            + "; ".join(f"**{host}**" for host in hosts)
            + ". Synthetic data, not held out. **Not device evidence.**")


def main(argv):
    if len(argv) != 2:
        print(__doc__.strip())
        return 2
    folder = Path(argv[1])
    facts = load(folder, "facts")
    compaction = load(folder, "compaction")
    models = sorted({row["model"] for row in facts + compaction})
    out = [f"# Memory bench summary, {folder.name}\n"]
    out.append(evidence_line(facts + compaction) + "\n")
    out.append(f"Models in this folder: {', '.join(models) or 'none'}.\n")
    facts_section(facts, out)
    compaction_section(compaction, out)
    out.append("## The question this answers\n")
    out.append("Which model, if any, is a candidate for default-on memory features. The answer is in "
               "\"What this means for the phone\" in the capability-bench SUMMARY; it is not restated "
               "here so the two cannot drift.\n")
    target = folder / "SUMMARY.md"
    target.write_text("\n".join(out).rstrip() + "\n", encoding="utf-8")
    print(f"wrote {target}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
