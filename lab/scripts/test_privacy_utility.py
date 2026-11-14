#!/usr/bin/env python3
import copy
import importlib.util
import json
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).with_name("privacy_utility.py")
SPEC = importlib.util.spec_from_file_location("privacy_utility", MODULE_PATH)
privacy_utility = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(privacy_utility)
DATASET = MODULE_PATH.parents[1] / "datasets/privacy_utility_research.jsonl"


class PrivacyUtilityTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.cases = privacy_utility.load_cases(DATASET)

    def test_dataset_and_paired_summary(self):
        report = privacy_utility.summarize(self.cases)
        self.assertEqual(12, report["caseCount"])
        self.assertEqual("synthetic-contract-only", report["status"])
        self.assertFalse(report["modelEvidence"])
        self.assertEqual(4, report["tiers"]["p0"]["correct"])
        self.assertEqual(0, report["tiers"]["p0"]["cloudCalls"])
        self.assertEqual(0, report["tiers"]["p0"]["bytes"])
        self.assertEqual(4, report["tiers"]["p1"]["broken"])
        self.assertEqual(11, report["tiers"]["p2"]["correct"])
        self.assertEqual(7, report["tiers"]["p2"]["fixed"])
        self.assertEqual(0, report["tiers"]["p2"]["broken"])
        self.assertEqual(11, report["tiers"]["p3"]["correct"])
        self.assertEqual(0, report["tiers"]["p2"]["branchSteeringFailures"])
        self.assertEqual(1, report["tiers"]["p3"]["branchSteeringFailures"])

    def test_p1_and_p2_never_project_private_context(self):
        for case in self.cases:
            for tier in ("p1", "p2"):
                encoded = json.dumps(privacy_utility.project(case, tier), ensure_ascii=False)
                self.assertNotIn("13800000000", encoded)
                self.assertNotIn("alice@example.com", encoded)
                self.assertNotIn("synthetic-only-token", encoded)
                self.assertNotIn("北京市朝阳区", encoded)

    def test_projection_bytes_are_deterministic_and_p0_is_local(self):
        case = self.cases[0]
        self.assertEqual(0, privacy_utility.projection_bytes(case, "p0"))
        self.assertIn("privateContext", privacy_utility.project(case, "p0"))
        self.assertEqual(
            privacy_utility.projection_bytes(case, "p2"),
            privacy_utility.projection_bytes(case, "p2"),
        )
        self.assertNotIn("privateContext", privacy_utility.project(case, "p2"))

    def test_unknown_privacy_and_tier_fail_closed(self):
        case = copy.deepcopy(self.cases[0])
        case["privacy"] = "unknown"
        with self.assertRaises(privacy_utility.ContractError):
            privacy_utility.project(case, "p3")
        with self.assertRaises(privacy_utility.ContractError):
            privacy_utility.project(self.cases[0], "p4")
        with self.assertRaises(privacy_utility.ContractError):
            privacy_utility.validate_case(case)

    def test_structured_observation_rejects_free_text_and_unknown_predicates(self):
        case = copy.deepcopy(self.cases[0])
        case["structuredObservation"]["candidates"][0]["text"] = "private"
        with self.assertRaises(privacy_utility.ContractError):
            privacy_utility.validate_observation(case["structuredObservation"])
        case = copy.deepcopy(self.cases[0])
        case["structuredObservation"]["predicates"]["screen_text"] = True
        with self.assertRaises(privacy_utility.ContractError):
            privacy_utility.validate_observation(case["structuredObservation"])

    def test_exact_boolean_and_argument_shape_scoring(self):
        optional = next(case for case in self.cases if case["id"] == "optional_boolean")
        self.assertFalse(privacy_utility.correct_answer(optional, "p0"))
        self.assertTrue(privacy_utility.correct_answer(optional, "p2"))
        shaped = next(case for case in self.cases if case["id"] == "argument_shape")
        self.assertFalse(privacy_utility.correct_answer(shaped, "p2"))
        self.assertTrue(privacy_utility.correct_answer(shaped, "p3"))


if __name__ == "__main__":
    unittest.main()
