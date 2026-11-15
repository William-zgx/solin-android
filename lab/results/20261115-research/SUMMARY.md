# Production-model request-only planning — 2026-11-15

## Evidence boundary

This is a real **devbox CPU / LiteRT-LM 0.12.0** run over the production `.litertlm` files through the lab
Ollama-compatible shim. It is not a cloud endpoint run, an Android phone run, an emulator regression, or a
paired device benchmark. The measured plans do not execute tools; correctness is scored against exact
milestones, predicates and branch destinations.

The final machine-readable report is [`local-cloud-planning-summary.json`](local-cloud-planning-summary.json).
The first failed E2B attempt is preserved as [`local-cloud-planning-summary-initial.json`](local-cloud-planning-summary-initial.json)
so the schema-learning work is not hidden by the final success.

## Final results

| model | sequential correct | branching correct | fixed / broken | sequential wall ms | branching wall ms | request bytes per mode |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| E2B | 3/3 | 3/3 | 0 / 0 | 17276 | 24594 | 2902 |
| E4B | 3/3 | 3/3 | 0 / 0 | 31080 | 44122 | 2902 |

Every accepted plan also matched the expected app, query/tool and branch structure. The report retains bounded
raw previews, parser rejection reasons and indices when applicable, and per-case wall time. No API key, full
error body or device observation is included.

## What the iterations established

- The initial E2B output did not produce a single accepted sequential or branching plan: numeric IDs, string
  milestones, boolean destinations and Markdown fences were rejected.
- Exact schema examples, a self-consistent `open_app → search_in_app` ID chain, the `use_tool` alarm shape,
  and static app-alias-to-package mappings improved the output **without weakening any parser or capability rule**.
- Accepted-but-wrong packages were still rejected by the caller's capability policy. That is why both
  `accepted` and exact `correct` are reported separately.
- The final `manifest.json` records `num_predict=1024`, matching the adapter. Each production file was
  independently streamed through SHA-256, and the same digest was required from the shim's `/api/tags`
  response. Model selection, serving verification and manifest metadata use that single tags snapshot.

## Reproduce

Start the shim, then run the suite. An authorized local endpoint is not a cloud API and no remote credential is
required. The current shim auto-creates its run cache under the bounded `/data00` scratch root and removes it on
success, failure or SIGTERM; no `/tmp` cache directory is needed.

```bash
SOLIN_LITERT_SCRATCH_ROOT=/data00/home/zouguoxue/.cache/solin-litert \
LD_LIBRARY_PATH=/data00/home/zouguoxue/edge-litert/deb/root/usr/lib/x86_64-linux-gnu \
SOLIN_LAB_LITERT_MODEL_ROOT=/data00/home/zouguoxue/edge-litert/models \
/data00/home/zouguoxue/edge-litert/venv/bin/python -I lab/scripts/litert_shim.py --port 11437

SOLIN_LAB=1 \
SOLIN_LAB_OLLAMA_URL=http://127.0.0.1:11437 \
SOLIN_LAB_DATE=20261115-research \
SOLIN_LAB_MODELS='litert/gemma-4-e2b,litert/gemma-4-e4b' \
SOLIN_LAB_LITERT_MODEL_ROOT=/data00/home/zouguoxue/edge-litert/models \
SOLIN_LAB_HOST_NOTE='devbox CPU / LiteRT-LM 0.12.0 (production .litertlm)' \
./gradlew :app:testDebugUnitTest --tests "com.bytedance.zgx.solin.lab.LocalCloudPlanningSuiteTest" --rerun-tasks
```

Use `SOLIN_LAB_CASE_IDS=alarm` for a single case. Do not interpret `UP-TO-DATE` or an assumption skip as a live
model result; the JSON report and its `models` entries are required.
