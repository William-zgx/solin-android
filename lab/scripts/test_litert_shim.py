#!/usr/bin/env python3
"""Stdlib regression tests for the LiteRT shim's production one-shot output cap."""

import importlib.util
import unittest
from pathlib import Path

SHIM = Path(__file__).resolve().with_name("litert_shim.py")


def load_shim():
    spec = importlib.util.spec_from_file_location("solin_lab_litert_shim_test", SHIM)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class FakeConversation:
    def __init__(self, chunks):
        self.chunks = chunks
        self.cancel_count = 0
        self.sync_count = 0
        self.stream_count = 0

    def send_message(self, _user):
        self.sync_count += 1
        return {"content": "sync"}

    def send_message_async(self, _user):
        self.stream_count += 1
        for chunk in self.chunks:
            yield {"content": [{"type": "text", "text": chunk}]}

    def cancel_process(self):
        self.cancel_count += 1


class OutputCapTest(unittest.TestCase):
    def test_estimate_matches_ascii_runs_and_non_ascii_characters(self):
        shim = load_shim()
        self.assertEqual(0, shim._estimate_output_tokens(" \n"))
        self.assertEqual(1, shim._estimate_output_tokens("abcd"))
        self.assertEqual(2, shim._estimate_output_tokens("abcde"))
        self.assertEqual(3, shim._estimate_output_tokens("ab cd"))
        self.assertEqual(2, shim._estimate_output_tokens("你好"))

    def test_cap_cancels_and_drains_without_appending_more_chunks(self):
        shim = load_shim()
        conversation = FakeConversation(["abcd", "efgh"])
        response, capped = shim._send_one_shot(conversation, "request", 1)
        self.assertTrue(capped)
        self.assertEqual("abcd", response["content"])
        self.assertEqual(1, conversation.cancel_count)
        self.assertEqual(1, conversation.stream_count)
        self.assertEqual(0, conversation.sync_count)

    def test_uncapped_call_keeps_synchronous_shape(self):
        shim = load_shim()
        conversation = FakeConversation([])
        response, capped = shim._send_one_shot(conversation, "request", None)
        self.assertFalse(capped)
        self.assertEqual("sync", response["content"])
        self.assertEqual(1, conversation.sync_count)
        self.assertEqual(0, conversation.cancel_count)


if __name__ == "__main__":
    unittest.main()
