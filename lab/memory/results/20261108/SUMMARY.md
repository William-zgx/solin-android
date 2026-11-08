# Memory bench summary, 20261108

Evidence class: **devbox CPU / LiteRT-LM 0.12.0 (production .litertlm)**. Synthetic data, not held out: the guards were tuned on these cases. **Not device evidence.**

Models in this folder: litert/gemma-4-e2b.

## Fact extraction (106 cases, 46 negative messages)

Scored by the production `LocalModelMemoryFactExtractor` over `fact_extraction.jsonl`; precision/recall are over the merged suggestions the user would see, which is what the guards produce. `raw` is the model's facts before the guards. Every number comes from the bench's own metrics file, the same code the Mac bench used.

| model | merged P | merged R | raw P | raw R | negation FP rate | raw hallucination | parse failure | errors | p50 / p95 ms |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| litert/gemma-4-e2b | 0.982 | 0.903 | 0.778 | 0.903 | 2.2% | 2.8% | 0.0% | 0/104 | 4633 / 4981 |

## Compaction summaries (12 cases; must-keep retention)

Scored by the production `DefaultContextCompactor` with `modelSummaryEnabled = true`: the current **source-label** prompt (threat T1), whole-token number grounding and the bounded third-party gap. A summary is accepted when every line starts with a label that was sent, every number is grounded and the third-party guard passes; otherwise the heuristic summary is used. The old (unlabelled) outputs scored 0/48 acceptable under this rule, so these are the first acceptance numbers for the prompt production would actually send.

| model | must-keep retention (final) | heuristic retention | model summary accepted | fallbacks | ungrounded-number rate | p50 / p95 ms |
| --- | --- | --- | --- | --- | --- | --- |
| litert/gemma-4-e2b | 78.8% | 68.2% | 5/12 | OverLength 7 | 0.0% | 15546 / 26437 |

The third-party gap is part of the accepted/rejected decision: a summary line that attributes a fact to someone other than the user (我的好朋友…, 我们老板…) is rejected by the production guard, so it never counts as accepted. ACL-3 (`fx-129`, 我们公司的同事…) is the labelled probe for it in `fact_extraction.jsonl`.

## The question this answers

Which model, if any, is a candidate for default-on memory features. The answer is in "What this means for the phone" in the capability-bench SUMMARY; it is not restated here so the two cannot drift.
