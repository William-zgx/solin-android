#!/usr/bin/env bash

preflight_append_unique() {
  local var_name="$1"
  local value="$2"
  local current="${!var_name:-}"
  if [[ -z "$value" ]]; then
    return
  fi
  case ",$current," in
    *",$value,"*)
      ;;
    *)
      if [[ -z "$current" ]]; then
        printf -v "$var_name" '%s' "$value"
      else
        printf -v "$var_name" '%s,%s' "$current" "$value"
      fi
      ;;
  esac
}

preflight_reason_has_any() {
  local reason="$1"
  shift
  local token pattern
  IFS=',' read -r -a preflight_tokens <<< "$reason"
  for token in "${preflight_tokens[@]}"; do
    for pattern in "$@"; do
      case "$token" in
        $pattern)
          return 0
          ;;
      esac
    done
  done
  return 1
}

preflight_missing_owner_fields() {
  local target="$1"
  local reason="$2"
  local result=""
  local token model_id
  IFS=',' read -r -a preflight_tokens <<< "$reason"

  case "$target" in
    release-record)
      preflight_reason_has_any "$reason" owner-missing && preflight_append_unique result release.owner
      preflight_reason_has_any "$reason" reviewer-missing && preflight_append_unique result release.reviewer
      preflight_reason_has_any "$reason" privacy-review-owner-missing && preflight_append_unique result blockers.privacyReview.owner
      preflight_reason_has_any "$reason" model-license-review-owner-missing && preflight_append_unique result blockers.modelLicenseReview.owner
      preflight_reason_has_any "$reason" production-signing-owner-missing && preflight_append_unique result blockers.productionSigning.owner
      ;;
    store-policy-record)
      preflight_reason_has_any "$reason" contact-email-placeholder contact-email-missing && preflight_append_unique result appListing.contactEmail
      preflight_reason_has_any "$reason" privacy-policy-url-placeholder privacy-policy-url-missing && preflight_append_unique result appListing.privacyPolicyUrl
      preflight_reason_has_any "$reason" reviewer-missing && preflight_append_unique result review.reviewer
      ;;
    privacy-review)
      preflight_reason_has_any "$reason" release-reviewer-missing && preflight_append_unique result reviews.release.reviewer
      preflight_reason_has_any "$reason" release-review-date-missing && preflight_append_unique result reviews.release.reviewDate
      preflight_reason_has_any "$reason" security-reviewer-missing && preflight_append_unique result reviews.security.reviewer
      preflight_reason_has_any "$reason" security-review-date-missing && preflight_append_unique result reviews.security.reviewDate
      preflight_reason_has_any "$reason" legal-reviewer-missing && preflight_append_unique result reviews.legal.reviewer
      preflight_reason_has_any "$reason" legal-review-date-missing && preflight_append_unique result reviews.legal.reviewDate
      ;;
    model-license-review)
      for token in "${preflight_tokens[@]}"; do
        case "$token" in
          *-reviewer-missing)
            model_id="${token%-reviewer-missing}"
            preflight_append_unique result "models.${model_id}.reviewer"
            ;;
          *-review-date-missing)
            model_id="${token%-review-date-missing}"
            preflight_append_unique result "models.${model_id}.reviewDate"
            ;;
        esac
      done
      ;;
    release-validation-record)
      preflight_reason_has_any "$reason" reviewer-missing && preflight_append_unique result review.reviewer
      preflight_reason_has_any "$reason" review-date-missing && preflight_append_unique result review.reviewDate
      ;;
    release-operations-record)
      preflight_reason_has_any "$reason" ci-owner-missing && preflight_append_unique result ci.owner
      preflight_reason_has_any "$reason" monitoring-owner-missing && preflight_append_unique result monitoring.owner
      preflight_reason_has_any "$reason" rollback-owner-missing && preflight_append_unique result rollback.owner
      preflight_reason_has_any "$reason" reviewer-missing && preflight_append_unique result review.reviewer
      preflight_reason_has_any "$reason" review-date-missing && preflight_append_unique result review.reviewDate
      ;;
  esac

  printf '%s' "$result"
}

preflight_missing_approval_roles() {
  local target="$1"
  local reason="$2"
  local status="$3"
  local result=""
  local token model_id
  IFS=',' read -r -a preflight_tokens <<< "$reason"

  case "$target" in
    release-record)
      preflight_reason_has_any "$reason" status-not-approved reviewer-missing review-date-missing && preflight_append_unique result release-record-review
      preflight_reason_has_any "$reason" privacy-review-* && preflight_append_unique result privacy:release
      preflight_reason_has_any "$reason" model-license-review-* && preflight_append_unique result model-license-review
      preflight_reason_has_any "$reason" production-signing-* signing-certificate-* && preflight_append_unique result release-signing
      ;;
    store-policy-record)
      if [[ "$status" != "passed" ]]; then
        preflight_append_unique result store-policy-review
      fi
      ;;
    privacy-review)
      preflight_reason_has_any "$reason" release-* && preflight_append_unique result privacy:release
      preflight_reason_has_any "$reason" security-* && preflight_append_unique result privacy:security
      preflight_reason_has_any "$reason" legal-* && preflight_append_unique result privacy:legal
      ;;
    model-license-review)
      for token in "${preflight_tokens[@]}"; do
        case "$token" in
          *-status-not-approved)
            model_id="${token%-status-not-approved}"
            preflight_append_unique result "model-license:${model_id}"
            ;;
          *-redistribution-not-approved)
            model_id="${token%-redistribution-not-approved}"
            preflight_append_unique result "model-license:${model_id}"
            ;;
          *-attribution-notice-missing)
            model_id="${token%-attribution-notice-missing}"
            preflight_append_unique result "model-license:${model_id}"
            ;;
          *-reviewer-missing)
            model_id="${token%-reviewer-missing}"
            preflight_append_unique result "model-license:${model_id}"
            ;;
          *-review-evidence-path-missing)
            model_id="${token%-review-evidence-path-missing}"
            preflight_append_unique result "model-license:${model_id}"
            ;;
          *-review-date-missing)
            model_id="${token%-review-date-missing}"
            preflight_append_unique result "model-license:${model_id}"
            ;;
        esac
      done
      ;;
    release-validation-record)
      if [[ "$status" != "passed" ]]; then
        preflight_append_unique result release-validation-review
      fi
      ;;
    release-operations-record)
      if [[ "$status" != "passed" ]]; then
        preflight_append_unique result release-operations-review
      fi
      ;;
  esac

  printf '%s' "$result"
}

preflight_missing_evidence_files() {
  local reason="$1"
  local result=""
  local token stem
  IFS=',' read -r -a preflight_tokens <<< "$reason"
  for token in "${preflight_tokens[@]}"; do
    case "$token" in
      *evidence-path-missing)
        stem="${token%-evidence-path-missing}"
        preflight_append_unique result "${stem}:path-missing"
        ;;
      *report-path-missing)
        stem="${token%-report-path-missing}"
        preflight_append_unique result "${stem}:path-missing"
        ;;
      *artifact-path-missing)
        stem="${token%artifact-path-missing}"
        if [[ -z "$stem" ]]; then
          stem="artifact"
        fi
        preflight_append_unique result "${stem}:path-missing"
        ;;
      *path-missing)
        stem="${token%-path-missing}"
        preflight_append_unique result "${stem}:path-missing"
        ;;
      *evidence-file-missing)
        stem="${token%-evidence-file-missing}"
        preflight_append_unique result "${stem}:file-missing"
        ;;
      *report-missing)
        stem="${token%-report-missing}"
        preflight_append_unique result "${stem}:file-missing"
        ;;
      *screenshot-missing)
        stem="${token%-screenshot-missing}"
        preflight_append_unique result "${stem}:file-missing"
        ;;
      *file-missing)
        stem="${token%-file-missing}"
        preflight_append_unique result "${stem}:file-missing"
        ;;
      *sha-missing)
        stem="${token%-sha-missing}"
        preflight_append_unique result "${stem}:sha-missing"
        ;;
      *sha-mismatch)
        stem="${token%-sha-mismatch}"
        preflight_append_unique result "${stem}:sha-mismatch"
        ;;
      *sha-invalid)
        stem="${token%-sha-invalid}"
        preflight_append_unique result "${stem}:sha-missing"
        ;;
    esac
  done

  printf '%s' "$result"
}

preflight_deferred_device_evidence() {
  local target="$1"
  local reason="$2"
  case "$target" in
    release-validation-record)
      if preflight_reason_has_any "$reason" \
        emulator-* api-* physical-device-* device-* manual-* flow-* performance-* perf-* screenshot-* *-screenshot-*; then
        printf '1'
      else
        printf '0'
      fi
      ;;
    release-operations-record)
      if preflight_reason_has_any "$reason" \
        ci-connected-android-tests-* ci-api-matrix-* *device-report* crash-anr-smoke-*; then
        printf '1'
      else
        printf '0'
      fi
      ;;
    *)
      printf '0'
      ;;
  esac
}

preflight_requires_human_approval() {
  local status="$1"
  local approval_roles="$2"
  local owner_fields="$3"
  if [[ "$status" != "passed" && ( -n "$approval_roles" || -n "$owner_fields" ) ]]; then
    printf '1'
  else
    printf '0'
  fi
}
