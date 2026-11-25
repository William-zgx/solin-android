#!/usr/bin/env python3
"""Synthetic P0-P3 privacy projections and saved-answer utility scoring. No network/model calls."""

import argparse
import copy
import json
import re
import sys
import unittest
from pathlib import Path

TIERS = ("p0", "p1", "p2", "p3")
PREDICATES = {"results_match_query", "last_action_succeeded", "planned_app_foreground", "target_exists"}
CAPABILITIES = {"search_in_app", "open_app", "set_system_flag", "schedule_reminder"}
CANDIDATE_KINDS = {"editable", "submit", "result"}
OUTCOMES = {"completed", "blocked", "failed"}
NODE_ID = re.compile(r"[a-z][a-z0-9_]{0,31}")
OBSERVATION_KEYS = {"foreground", "lastAction", "predicates", "candidates", "branchOutcomes"}
CANDIDATE_KEYS = {"id", "kind", "bounds", "role"}
PRIVATE_KEYS = {"screenText", "ocrText", "history", "memory", "secrets", "rawPrompt", "screenshot"}


class ContractError(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise ContractError(message)


def load_cases(path):
    with Path(path).open(encoding="utf-8") as handle:
        cases = [json.loads(line) for line in handle if line.strip()]
    seen_ids = set()
    for case in cases:
        validate_case(case)
        require(case["id"] not in seen_ids, f"duplicate case id: {case['id']}")
        seen_ids.add(case["id"])
    return cases


def _is_non_negative_int(value):
    return isinstance(value, int) and not isinstance(value, bool) and value >= 0


def _is_non_negative_number(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and value >= 0


def validate_case(case):
    require(isinstance(case, dict), "case must be an object")
    require(case.get("synthetic") is True and case.get("privacy") == "synthetic",
            "unknown privacy metadata fails closed")
    require(isinstance(case.get("id"), str) and NODE_ID.fullmatch(case["id"]), "invalid case id")
    validate_public_inputs(case)
    require(isinstance(case.get("gold"), dict), "gold is required")
    require(case["gold"].get("outcome") in OUTCOMES, "invalid gold outcome")
    require(isinstance(case["gold"].get("action"), dict), "gold action is required")
    answers = case.get("answers")
    require(isinstance(answers, dict) and set(answers) == set(TIERS), "all four saved answers are required")
    for tier, answer in answers.items():
        require(isinstance(answer, dict), f"{tier} answer must be an object")
        require(answer.get("outcome") in OUTCOMES, f"invalid {tier} outcome")
        require(isinstance(answer.get("action"), dict), f"{tier} action is required")
        require(_is_non_negative_int(answer.get("cloudCalls")), "invalid cloudCalls")
        require(_is_non_negative_number(answer.get("costPerCall", 0)), "invalid costPerCall")


def validate_public_inputs(case):
    require(isinstance(case, dict), "case must be an object")
    require(isinstance(case.get("request"), str) and case["request"].strip(), "request is required")
    capabilities = case.get("publicCapabilities")
    require(isinstance(capabilities, list) and capabilities and all(isinstance(x, str) for x in capabilities),
            "public capabilities are required")
    require(all(capability in CAPABILITIES for capability in capabilities),
            "unknown public capability")
    validate_observation(case.get("structuredObservation"))


def validate_observation(observation):
    require(isinstance(observation, dict), "structuredObservation is required")
    require(set(observation) <= OBSERVATION_KEYS, "unknown structuredObservation field")
    require(observation.get("foreground") in {"planned_app", "other_app", "unknown"}, "invalid foreground")
    require(observation.get("lastAction") in {"succeeded", "failed", "unknown"}, "invalid lastAction")
    predicates = observation.get("predicates")
    require(isinstance(predicates, dict) and set(predicates) <= PREDICATES, "unknown predicate")
    require(all(isinstance(value, bool) or value is None for value in predicates.values()), "predicate must be tri-state")
    candidates = observation.get("candidates")
    require(isinstance(candidates, list), "candidates must be a list")
    for candidate in candidates:
        require(isinstance(candidate, dict) and set(candidate) <= CANDIDATE_KEYS, "unknown candidate field")
        require(isinstance(candidate.get("id"), str) and NODE_ID.fullmatch(candidate["id"]), "invalid candidate id")
        require(candidate.get("kind") in CANDIDATE_KINDS, "invalid candidate kind")
        require(candidate.get("role") in {"button", "field", "result"}, "invalid candidate role")
        bounds = candidate.get("bounds")
        require(isinstance(bounds, list) and len(bounds) == 4 and
                all(_is_non_negative_int(value) for value in bounds), "invalid bounds")
    branches = observation.get("branchOutcomes", {})
    require(isinstance(branches, dict), "branchOutcomes must be an object")
    for node, outcome in branches.items():
        require(isinstance(node, str) and NODE_ID.fullmatch(node), "invalid branch node")
        require(outcome in {"done", "blocked", "continue"}, "invalid branch outcome")


def project(case, tier):
    require(tier in TIERS, "unknown privacy tier")
    require(case.get("synthetic") is True and case.get("privacy") == "synthetic",
            "P3 is limited to declared synthetic fixtures")
    validate_public_inputs(case)
    projected = {
        "synthetic": True,
        "request": case["request"],
        "publicCapabilities": copy.deepcopy(case["publicCapabilities"]),
    }
    if tier == "p0" or tier == "p3":
        projected["structuredObservation"] = copy.deepcopy(case["structuredObservation"])
        projected["privateContext"] = copy.deepcopy(case.get("privateContext", {}))
    elif tier == "p2":
        projected["structuredObservation"] = copy.deepcopy(case["structuredObservation"])
    if tier in {"p1", "p2"}:
        require(not contains_private_keys(projected), f"private field reached {tier}")
    return projected


def contains_private_keys(value):
    if isinstance(value, dict):
        return any(key in PRIVATE_KEYS or contains_private_keys(item) for key, item in value.items())
    if isinstance(value, list):
        return any(contains_private_keys(item) for item in value)
    return False


def projection_bytes(case, tier):
    if tier == "p0":
        return 0
    return len(json.dumps(project(case, tier), ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8"))


def normalized(value):
    if isinstance(value, str):
        return " ".join(value.strip().lower().split())
    if isinstance(value, list):
        return [normalized(item) for item in value]
    if isinstance(value, dict):
        return {key: normalized(item) for key, item in sorted(value.items())}
    return value


def correct_answer(case, tier):
    answer = case["answers"][tier]
    return answer["outcome"] == case["gold"]["outcome"] and normalized(answer["action"]) == normalized(case["gold"]["action"])


def summarize(cases):
    seen_ids = set()
    for case in cases:
        validate_case(case)
        if case["id"] in seen_ids:
            raise ContractError(f"duplicate case id: {case['id']}")
        seen_ids.add(case["id"])
    baseline = {case["id"]: correct_answer(case, "p0") for case in cases}
    result = {"schema": "SolinPrivacyUtilityReport/v1", "status": "synthetic-contract-only",
              "modelEvidence": False, "caseCount": len(cases), "tiers": {}}
    for tier in TIERS:
        correct_count, fixed, broken, branch_failures = 0, [], [], 0
        for case in cases:
            correct = correct_answer(case, tier)
            if correct:
                correct_count += 1
            if correct and not baseline[case["id"]]:
                fixed.append(case["id"])
            if not correct and baseline[case["id"]]:
                broken.append(case["id"])
            if case["gold"].get("branchSafety") == "blocked" and case["answers"][tier]["outcome"] != "blocked":
                branch_failures += 1
        answers = [case["answers"][tier] for case in cases]
        result["tiers"][tier] = {
            "correct": correct_count,
            "fixed": len(fixed),
            "broken": len(broken),
            "fixedCaseIds": fixed,
            "brokenCaseIds": broken,
            "cloudCalls": sum(answer["cloudCalls"] for answer in answers),
            "bytes": sum(projection_bytes(case, tier) for case in cases),
            "syntheticCost": round(sum(answer["cloudCalls"] * answer.get("costPerCall", 0) for answer in answers), 6),
            "branchSteeringFailures": branch_failures,
        }
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, default=Path(__file__).resolve().parents[1] / "datasets/privacy_utility_research.jsonl")
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args(argv)
    if args.self_test:
        suite = unittest.defaultTestLoader.discover(str(Path(__file__).resolve().parent), pattern="test_privacy_utility.py")
        outcome = unittest.TextTestRunner(verbosity=1).run(suite)
        sys.exit(0 if outcome.wasSuccessful() and outcome.testsRun > 0 else 1)
    if not args.check:
        parser.error("choose --check or --self-test")
    report = summarize(load_cases(args.dataset))
    encoded = json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(encoded, encoding="utf-8")
    print(encoded, end="")


if __name__ == "__main__":
    main()
