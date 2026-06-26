#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ROADMAP_FILE="$ROOT_DIR/docs/roadmap_gap_matrix.json"
READINESS_FILE="$ROOT_DIR/docs/release_readiness.md"
# Sources: docs/roadmap_gap_matrix.json and docs/release_readiness.md.
PYTHON_BIN="${PYTHON_BIN:-}"
if [[ -z "$PYTHON_BIN" ]]; then
  if [[ -x /usr/bin/python3 ]]; then
    PYTHON_BIN="/usr/bin/python3"
  else
    PYTHON_BIN="python3"
  fi
fi

"$PYTHON_BIN" - "$ROADMAP_FILE" "$READINESS_FILE" <<'PY'
import json
import re
import sys
from pathlib import Path

roadmap_path = Path(sys.argv[1])
readiness_path = Path(sys.argv[2])

roadmap = json.loads(roadmap_path.read_text(encoding="utf-8"))
readiness_text = readiness_path.read_text(encoding="utf-8")

LABELS = {
    "phase2-real-app-local-resolver-evidence": "Real-app search replay coverage",
    "phase2-real-app-physical-pass-rate": "Real-app search physical pass rate",
    "phase3-agent-behavior-eval-gate": "Agent behavior actual runtime trace",
    "phase4-release-records-and-policy-evidence": "Privacy / store / release approvals",
    "phase4-device-and-emulator-validation": "Physical validation and arm64 API matrix",
    "phase4-performance-baseline": "Perf baseline on physical arm64",
    "phase5-model-memory-multimodal-local-gates": "Real model runtime validation",
    "phase6-public-release-readiness": "Public release approvals, signing, validation, perf baseline",
}


def rel(path):
    try:
        return path.relative_to(Path.cwd())
    except ValueError:
        return path


def compact(text, limit=180):
    text = re.sub(r"\s+", " ", text).strip()
    if len(text) <= limit:
        return text
    return text[: limit - 1].rstrip() + "..."


def clean_cell(text):
    return compact(text.replace("|", "/"))


def readable_slug(slug):
    return slug.replace("-", " ")


def roadmap_item(item_id):
    return next(item for item in roadmap["items"] if item["id"] == item_id)


def parse_readiness_blockers(markdown):
    rows = []
    in_section = False
    for raw_line in markdown.splitlines():
        line = raw_line.strip()
        if line == "## Remaining release blockers by ownership":
            in_section = True
            continue
        if in_section and line.startswith("## "):
            break
        if not in_section or not line.startswith("|"):
            continue
        if set(line.replace("|", "").replace("-", "").replace(" ", "")) == set():
            continue
        cells = [cell.strip() for cell in line.strip("|").split("|")]
        if len(cells) < 4 or cells[0] == "Status":
            continue
        rows.append(
            {
                "status": cells[0],
                "owner": cells[1],
                "item": cells[2],
                "gate": cells[3],
            }
        )
    return rows


readiness_rows = parse_readiness_blockers(readiness_text)
active_items = [
    item for item in roadmap["items"]
    if item.get("status") != "done" or item.get("blockedBy")
]

human_rows = [
    row for row in readiness_rows
    if "Owner evidence required" in row["status"]
    or "Manual approval required" in row["status"]
    or "Manual/legal approval required" in row["status"]
]
physical_rows = [
    row for row in readiness_rows
    if "Physical hardware required" in row["status"]
]
deferred_items = [
    item for item in active_items
    if item.get("status") == "deferred"
    or any("deferred" in blocker or "no-device" in blocker for blocker in item.get("blockedBy", []))
]

next_commands = [
    "VERIFY_RELEASE_RECORD=1 scripts/verify_release_gate.sh",
    "VERIFY_STORE_POLICY=1 scripts/verify_release_gate.sh",
    "VERIFY_PRIVACY_REVIEW=1 scripts/verify_release_gate.sh",
    "VERIFY_MODEL_LICENSES=1 scripts/verify_release_gate.sh",
    "scripts/check_emulator_api_matrix.sh",
    "scripts/prepare_emulator_api_matrix.sh",
    "ANDROID_SERIAL=<physical-device-serial> scripts/install_and_test_device.sh",
    "ANDROID_SERIAL=<physical-device-serial> scripts/run_real_app_search_eval.sh",
    "ANDROID_SERIAL=<physical-device-serial> scripts/collect_perf_baseline.sh",
    (
        "PUBLIC_RELEASE=1 EXPECTED_SIGNING_CERT_SHA256=<production upload cert> "
        "PERF_BASELINE_FILE=<rc perf baseline> "
        "AI_BEHAVIOR_ACTUAL_TRACE_FILE=<actual-trace.jsonl> scripts/verify_release_gate.sh"
    ),
]

print("# Release Blocker Dashboard")
print()
print(
    "Generated from `docs/roadmap_gap_matrix.json` and "
    "`docs/release_readiness.md` only. This is a compact local evidence view; "
    "it does not resolve device, performance, signing, store, legal, privacy, "
    "or release-owner blockers."
)
print()
print(f"- Roadmap updated: `{roadmap.get('updatedDate', 'unknown')}`")
print(f"- Policy: {compact(roadmap.get('policy', ''), 240)}")
print()

print("## Active Blockers")
print()
print("| Blocker | Status | Owner / phase | Blocking evidence | Next evidence |")
print("| --- | --- | --- | --- | --- |")
for item in active_items:
    label = LABELS.get(item["id"], item["id"])
    blocked_by = ", ".join(readable_slug(blocker) for blocker in item.get("blockedBy", []))
    if not blocked_by:
        blocked_by = "Follow-up evidence required"
    owner_phase = f'{item["ownerAgent"]} / {item["roadmapPhase"]}'
    print(
        "| "
        + " | ".join(
            [
                clean_cell(label),
                clean_cell(item["status"]),
                clean_cell(owner_phase),
                clean_cell(blocked_by),
                clean_cell(item["remainingWork"]),
            ]
        )
        + " |"
    )
print()

print("## Deferred")
print()
for item in deferred_items:
    label = LABELS.get(item["id"], item["id"])
    blockers = ", ".join(readable_slug(blocker) for blocker in item.get("blockedBy", []))
    print(f"- **{clean_cell(label)}**: `{item['status']}`; {clean_cell(blockers)}.")
print()

print("## Human Approval")
print()
for row in human_rows:
    owner = row["owner"]
    item = row["item"]
    if "Store / policy" in owner:
        label = "Store approvals"
    elif "Release, security, legal" in owner:
        label = "Privacy approvals"
    elif "Release owner" in owner:
        label = "Release approvals"
    elif "Model/license" in owner:
        label = "Model/license approvals"
    else:
        label = owner
    print(f"- **{label}**: {clean_cell(item)}")
print()

print("## Physical Hardware")
print()
for row in physical_rows:
    label = "Perf baseline" if "Performance owner" in row["owner"] else "Physical validation"
    print(f"- **{label}**: {clean_cell(row['item'])}")
real_app = roadmap_item("phase2-real-app-physical-pass-rate")
print(f"- **Real-app search**: {clean_cell(real_app['remainingWork'])}")
print()

print("## Next Commands")
print()
for command in next_commands:
    print(f"- `{command}`")
PY
