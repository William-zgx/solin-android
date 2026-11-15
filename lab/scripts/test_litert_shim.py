#!/usr/bin/env python3
"""Stdlib regressions for LiteRT one-shot output and shim-owned cache lifecycle."""

import importlib.util
import os
import tempfile
import types
import unittest
from pathlib import Path
from unittest import mock

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


class FakeServer:
    def __init__(self, _address, _handler):
        self.closed = False

    def serve_forever(self):
        return None

    def server_close(self):
        self.closed = True


class CacheLifecycleTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="solin-shim-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.model_root = self.root / "models"
        self.model_root.mkdir()
        self.scratch = self.root / "scratch"
        self.shim = load_shim()
        self.environment = mock.patch.dict(
            os.environ,
            {
                "SOLIN_LITERT_MIN_FREE_BYTES": "1",
                "SOLIN_LITERT_SCRATCH_MAX_DIRS": "2",
                "SOLIN_LITERT_SCRATCH_MAX_BYTES": "10000",
            },
        )
        self.environment.start()
        self.addCleanup(self.environment.stop)

    def args(self, cache_dir=""):
        return types.SimpleNamespace(
            model_root=str(self.model_root),
            cache_dir=str(cache_dir),
            scratch_root=str(self.scratch),
        )

    def main_args(self, cache_dir):
        return ["litert_shim.py", "--model-root", str(self.model_root), "--cache-dir", str(cache_dir)]

    def test_main_removes_explicit_empty_cache_on_success_and_import_failure(self):
        with mock.patch.object(self.shim.signal, "signal"), \
                mock.patch.object(self.shim, "_validate_cache_filesystem"), \
                mock.patch.object(self.shim, "_import_litert", return_value=object()), \
                mock.patch.object(self.shim, "ThreadingHTTPServer", FakeServer):
            cache = self.root / "successful-run"
            self.assertEqual(0, self.shim.main(self.main_args(cache)))
            self.assertFalse(cache.exists())

        with mock.patch.object(self.shim.signal, "signal"), \
                mock.patch.object(self.shim, "_validate_cache_filesystem"), \
                mock.patch.object(self.shim, "_import_litert", side_effect=RuntimeError("import failed")):
            cache = self.root / "failed-run"
            with self.assertRaisesRegex(RuntimeError, "import failed"):
                self.shim.main(self.main_args(cache))
            self.assertFalse(cache.exists())

    def test_low_space_rejects_before_import_and_removes_owned_empty_cache(self):
        cache = self.root / "low-space"
        cache.mkdir()
        with mock.patch.object(self.shim.signal, "signal"), \
                mock.patch.object(self.shim.shutil, "disk_usage", return_value=mock.Mock(free=0)), \
                mock.patch.object(self.shim, "_import_litert") as import_litert:
            with self.assertRaises(SystemExit):
                self.shim.main(self.main_args(cache))
        import_litert.assert_not_called()
        self.assertFalse(cache.exists())

    def test_nonempty_explicit_cache_is_rejected_and_preserved(self):
        cache = self.root / "existing"
        cache.mkdir()
        payload = cache / "payload"
        payload.write_bytes(b"keep")
        with self.assertRaisesRegex(ValueError, "existing files were preserved"):
            self.shim._prepare_cache(self.args(cache))
        self.assertEqual(b"keep", payload.read_bytes())

    def test_explicit_cache_cannot_bypass_data00_filesystem(self):
        with mock.patch.object(self.shim.os.path, "isdir", return_value=True), \
                mock.patch.object(
                    self.shim.os,
                    "stat",
                    side_effect=lambda path: types.SimpleNamespace(st_dev=1 if os.fspath(path) == "/data00" else 2),
                ):
            with self.assertRaisesRegex(RuntimeError, "/data00 filesystem"):
                self.shim._validate_cache_filesystem(self.root / "root-partition-cache")

    def test_explicit_lock_conflict_does_not_delete_possible_active_directory(self):
        cache = self.root / "shared-empty-cache"
        cache.mkdir()
        with mock.patch.object(self.shim, "_try_lock", return_value=None):
            with self.assertRaisesRegex(RuntimeError, "could not lock"):
                self.shim._prepare_cache(self.args(cache))
        self.assertTrue(cache.is_dir())

    def test_second_auto_run_does_not_prune_locked_current(self):
        with mock.patch.dict(os.environ, {"SOLIN_LITERT_SCRATCH_MAX_DIRS": "1"}):
            first = self.shim._prepare_cache(self.args())
            second = self.shim._prepare_cache(self.args())
            try:
                self.assertTrue(first.path.is_dir())
                self.assertTrue(second.path.is_dir())
                self.assertNotEqual(first.path, second.path)
            finally:
                second.close()
                first.close()

    def make_stale(self, name, size, mtime):
        path = self.scratch / (self.shim.CACHE_PREFIX + name)
        path.mkdir(parents=True)
        (path / "payload").write_bytes(b"x" * size)
        os.utime(path, (mtime, mtime))
        return path

    def test_stale_retention_enforces_count_and_keeps_newest(self):
        oldest = self.make_stale("oldest", 100, 1)
        middle = self.make_stale("middle", 100, 2)
        newest = self.make_stale("newest", 100, 3)
        lease = self.shim._prepare_cache(self.args())
        try:
            self.assertTrue(lease.path.is_relative_to(self.scratch))
            self.assertFalse(oldest.exists())
            self.assertFalse(middle.exists())
            self.assertTrue(newest.exists())
        finally:
            lease.close()
        self.assertFalse(lease.path.exists())

    def test_stale_retention_enforces_bytes(self):
        with mock.patch.dict(os.environ, {"SOLIN_LITERT_SCRATCH_MAX_DIRS": "10", "SOLIN_LITERT_SCRATCH_MAX_BYTES": "150"}):
            oldest = self.make_stale("oldest", 100, 1)
            middle = self.make_stale("middle", 200, 2)
            newest = self.make_stale("newest", 300, 3)
            lease = self.shim._prepare_cache(self.args())
            lease.close()
        self.assertTrue(oldest.exists())
        self.assertFalse(middle.exists())
        self.assertFalse(newest.exists())

    def test_active_cache_is_never_pruned_even_over_limits(self):
        stale = self.make_stale("stale", 100, 1)
        active = self.make_stale("active", 500, 2)
        active_lock = self.shim._try_lock(active)
        self.assertIsNotNone(active_lock)
        self.addCleanup(active_lock.close)
        with mock.patch.dict(os.environ, {"SOLIN_LITERT_SCRATCH_MAX_DIRS": "1", "SOLIN_LITERT_SCRATCH_MAX_BYTES": "1"}):
            lease = self.shim._prepare_cache(self.args())
            lease.close()
        self.assertFalse(stale.exists())
        self.assertTrue(active.exists())


if __name__ == "__main__":
    unittest.main()
