# Memory bench summary, 20261107

Evidence class: desktop Ollama 0.40.3 on an Apple M3 Pro (MLX runner, nvfp4), greedy decoding.
Synthetic data, not held out: the guards were tuned on these cases. **Not device evidence.**

## Fact extraction (106 cases)

Regex alone: precision 1.000, recall 0.403. Replay of the saved outputs matches the saved results on every key.

| model | merged P | merged R | negation FP | raw hallucination | parse failure | p50 / p95 ms |
| --- | --- | --- | --- | --- | --- | --- |
| gemma4:e2b | 1.000 | 0.839 | 0.000 | 0.016 | 0.038 | 144 / 219 |
| gemma4:e4b | 0.982 | 0.903 | 0.022 | 0.000 | 0.000 | 217 / 313 |
| qwen3.5:2b | 1.000 | 0.903 | 0.000 | 0.040 | 0.000 | 700 / 1109 |
| qwen3.5:4b | 1.000 | 0.968 | 0.000 | 0.000 | 0.000 | 549 / 978 |

## Compaction summaries (12 cases, 66 must-keep items; heuristic retention 0.682)

Columns: saved output / numbers-only rule / labels rule applied to the old outputs.

| model | retention | summary accepted | ungrounded-number rate | p50 / p95 ms |
| --- | --- | --- | --- | --- |
| gemma4:e2b | 0.955 / 0.955 / 0.682 | 11/12 / 11/12 / 0/12 | 0 / 0 | 1466 / 1901 |
| gemma4:e4b | 0.970 / 0.970 / 0.682 | 10/12 / 10/12 / 0/12 | 0 / 0 | 2408 / 2832 |
| qwen3.5:2b | 0.924 / 0.864 / 0.682 | 11/12 / 10/12 / 0/12 | 0.083 / 0.167 | 2787 / 4120 |
| qwen3.5:4b | 0.985 / 0.924 / 0.682 | 11/12 / 10/12 / 0/12 | 0.083 / 0.167 | 4009 / 4701 |

Label cost: 0 of 48 saved summaries have every line labelled. The labels-rule column applies the new rule to
the old outputs; it is not a run of the new prompt.

## Recommendation

Neither flag should default on.

- If facts are considered: qwen3.5:4b (best merged recall, no negation false positives, about 4x gemma4:e2b's p50).
- If compaction is considered: gemma4:e2b (no ungrounded numbers, highest acceptance), and only after a live run
  of the labelled prompt.

Gates before any default-on: a held-out set, a live labelled-prompt run, on-device latency and memory, and
LiteRT adapter tests.

## Not computed

- Labelled-prompt acceptance (no live run; the replay reuses saved outputs).
- Anything on a phone.
- Held-out accuracy.
- Latency under replay (saved times are reused).
