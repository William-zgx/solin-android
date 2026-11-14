# Synthetic privacy-utility contract report — 2026-11-14

## Evidence boundary

This report validates **synthetic data projections and saved-answer scoring only**. It contains no real
model or network calls, no user data, no production privacy-policy change, and no emulator/device evidence.
P3 is an upper-bound fixture projection, not a production `FullContext` option. The numbers below must not
be quoted as model accuracy, privacy risk reduction, or cloud cost measurements.

The machine-readable report is [`report.json`](report.json). `syntheticCost` is computed solely from the
cost inputs declared by each fixture; it is not a real price.

## Synthetic saved-answer outcomes

| tier | correct | fixed vs P0 | broken vs P0 | cloud calls | projected bytes | Branch Steering failures |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| P0 local | 4/12 | 0 | 0 | 0 | 0 | 1 |
| P1 request + public capabilities | 0/12 | 0 | 4 | 12 | 1106 | 1 |
| P2 + structured observation | 11/12 | 7 | 0 | 12 | 3724 | 0 |
| P3 synthetic full context | 11/12 | 7 | 0 | 24 | 4236 | 1 |

The fixture set deliberately includes local successes so both fixed and broken cases are tested. P2 avoids
raw screen/OCR/history/memory/secrets by structural allowlists; it is not a semantic PII detector. The full
context fixture also contains a Branch Steering failure to prevent interpreting more context as inherently
safer or more accurate.

## Reproduce

```bash
python3 -I lab/scripts/test_privacy_utility.py
python3 -I lab/scripts/privacy_utility.py --check --output lab/results/20261114-privacy-utility/report.json
```
