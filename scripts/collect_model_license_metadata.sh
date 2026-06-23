#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

OUT_FILE="${OUT_FILE:-docs/model_license_metadata.json}"
REVIEW_FILE="${REVIEW_FILE:-docs/model_license_review.json}"
MANIFEST_FILE="${MANIFEST_FILE:-docs/model_manifest.md}"
REPORT_FILE="${REPORT_FILE:-}"
MODEL_LICENSE_API_BASE_URL="${MODEL_LICENSE_API_BASE_URL:-https://huggingface.co/api/models}"
EVIDENCE_OWNER="${EVIDENCE_OWNER:-${OWNER:-model-license-review}}"
FAILED_TARGET=""
FAILURE_REASON=""
ORIGINAL_ARGS=("$@")

command_line() {
  local quoted=()
  local arg
  quoted+=("$(printf '%q' "scripts/collect_model_license_metadata.sh")")
  for arg in "${ORIGINAL_ARGS[@]}"; do
    quoted+=("$(printf '%q' "$arg")")
  done
  local IFS=' '
  printf '%s' "${quoted[*]}"
}

sha256_or_empty() {
  local path="$1"
  if [[ -n "$path" && -f "$path" ]]; then
    shasum -a 256 "$path" | awk '{print $1}'
  fi
}

write_report() {
  local status="$1"
  local reason="${2:-}"
  local model_count="${3:-0}"
  if [[ -n "$REPORT_FILE" ]]; then
    mkdir -p "$(dirname "$REPORT_FILE")"
    {
      printf 'artifactSchema=ModelLicenseMetadataCollection/v1\n'
      printf 'status=%s\n' "$status"
      printf 'target=model-license-metadata-collector\n'
      printf 'owner=%s\n' "$EVIDENCE_OWNER"
      printf 'recordedAt=%s\n' "$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
      printf 'command=%s\n' "$(command_line)"
      printf 'failedTarget=%s\n' "$FAILED_TARGET"
      printf 'reason=%s\n' "$reason"
      printf 'reproduciblePath=%s\n' "$REPORT_FILE"
      printf 'outFile=%s\n' "$OUT_FILE"
      printf 'outFileSha256=%s\n' "$(sha256_or_empty "$OUT_FILE")"
      printf 'reviewFile=%s\n' "$REVIEW_FILE"
      printf 'reviewSha256=%s\n' "$(sha256_or_empty "$REVIEW_FILE")"
      printf 'manifestFile=%s\n' "$MANIFEST_FILE"
      printf 'manifestSha256=%s\n' "$(sha256_or_empty "$MANIFEST_FILE")"
      printf 'apiBaseUrl=%s\n' "$MODEL_LICENSE_API_BASE_URL"
      printf 'modelCount=%s\n' "$model_count"
    } > "$REPORT_FILE"
  fi
}

trap 'status=$?; if [[ "$status" -ne 0 ]]; then write_report failed "${FAILURE_REASON:-metadata-collection-failed}" 0; fi; exit "$status"' EXIT

fail() {
  FAILED_TARGET="$1"
  FAILURE_REASON="$2"
  shift 2
  echo "$*" >&2
  exit 1
}

if [[ ! -f "$REVIEW_FILE" ]]; then
  fail input-file missing-review-file "Missing model license review file: $REVIEW_FILE"
fi

if [[ ! -f "$MANIFEST_FILE" ]]; then
  fail input-file missing-manifest-file "Missing model manifest file: $MANIFEST_FILE"
fi

TMP_RESULT="$(mktemp)"
trap 'status=$?; rm -f "$TMP_RESULT"; if [[ "$status" -ne 0 ]]; then write_report failed "${FAILURE_REASON:-metadata-collection-failed}" 0; fi; exit "$status"' EXIT

set +e
MODEL_LICENSE_API_BASE_URL="$MODEL_LICENSE_API_BASE_URL" python3 - "$REVIEW_FILE" "$OUT_FILE" "$MANIFEST_FILE" > "$TMP_RESULT" <<'PY'
import datetime
import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path

review_path = Path(sys.argv[1])
out_path = Path(sys.argv[2])
manifest_path = Path(sys.argv[3])
review = json.loads(review_path.read_text())
api_base_url = os.environ.get("MODEL_LICENSE_API_BASE_URL", "https://huggingface.co/api/models").rstrip("/")

def repo_id_from_url(url: str) -> str:
    prefix = "https://huggingface.co/"
    if not url.startswith(prefix):
        raise ValueError(f"unsupported model URL: {url}")
    return url[len(prefix):].strip("/")

def parse_manifest(path: Path):
    models = []
    for raw_line in path.read_text().splitlines():
        line = raw_line.strip()
        if not line.startswith("| `"):
            continue
        columns = [part.strip() for part in line.strip("|").split("|")]
        if len(columns) < 6:
            continue
        model_id = columns[0].strip("`")
        repository_url = columns[2].strip("`")
        revision = columns[3].strip("`")
        if not model_id or model_id == "ID":
            continue
        models.append({
            "id": model_id,
            "repository": repo_id_from_url(repository_url),
            "manifestRevision": revision,
        })
    return models

def license_source_candidates(repo_id: str, revision: str, metadata: dict):
    markers = ("license", "licence", "copying", "notice", "readme", "model_card", "model-card", "terms")
    candidates = []
    seen = set()
    siblings = metadata.get("siblings") or []
    if not isinstance(siblings, list):
        siblings = []
    for sibling in siblings:
        if not isinstance(sibling, dict):
            continue
        filename = sibling.get("rfilename", "")
        if not isinstance(filename, str) or not filename:
            continue
        lowered = filename.lower()
        if not any(marker in lowered for marker in markers):
            continue
        url = f"https://huggingface.co/{repo_id}/blob/{revision}/{filename}"
        if url in seen:
            continue
        seen.add(url)
        candidates.append(url)
    return candidates

reviews_by_id = {
    model.get("id", ""): model
    for model in review.get("models", [])
    if isinstance(model, dict) and model.get("id")
}

records = []
for model in parse_manifest(manifest_path):
    review_entry = reviews_by_id.get(model["id"], {})
    repo_id = model["repository"]
    api_url = f"{api_base_url}/{repo_id}"
    with urllib.request.urlopen(api_url, timeout=30) as response:
        metadata = json.loads(response.read().decode("utf-8"))
    tags = metadata.get("tags") or []
    license_tags = [
        tag[len("license:"):]
        for tag in tags
        if isinstance(tag, str) and tag.startswith("license:")
    ]
    card_data = metadata.get("cardData") or {}
    card_license = card_data.get("license") if isinstance(card_data, dict) else None
    gated = bool(metadata.get("gated", False))
    records.append({
        "id": model["id"],
        "repository": repo_id,
        "manifestRevision": model["manifestRevision"],
        "apiUrl": api_url,
        "modelSha": metadata.get("sha", ""),
        "lastModified": metadata.get("lastModified", ""),
        "gated": gated,
        "requiresUserAuthorization": gated,
        "licenseTags": license_tags,
        "cardLicense": card_license or "",
        "licenseSourceCandidates": license_source_candidates(repo_id, model["manifestRevision"], metadata),
        "manualReviewStatus": review_entry.get("status", ""),
        "redistributionDecision": review_entry.get("redistributionDecision", ""),
        "metadataOnly": True,
    })

out = {
    "version": 1,
    "recordedAt": datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
    "source": "Hugging Face model API",
    "policy": "Metadata collection is not legal approval; docs/model_license_review.json remains the release gate.",
    "models": records,
}
out_path.parent.mkdir(parents=True, exist_ok=True)
out_path.write_text(json.dumps(out, ensure_ascii=False, indent=2) + "\n")
print(len(records))
PY
collect_status=$?
set -e
if [[ "$collect_status" -ne 0 ]]; then
  FAILED_TARGET="metadata-api"
  FAILURE_REASON="metadata-api-collection-failed"
  echo "Model license metadata collection failed." >&2
  exit "$collect_status"
fi
MODEL_COUNT="$(cat "$TMP_RESULT")"
write_report passed "" "$MODEL_COUNT"

echo "Model license metadata written to $OUT_FILE"
