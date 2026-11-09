#!/usr/bin/env python3
"""Ollama-compatible HTTP shim over LiteRT-LM 0.12.0, for the lab bench only.

Why: the capability bench talks to Ollama (`OllamaClient.kt`). The app ships LiteRT-LM
0.12.0 with `.litertlm` weights, so the bench can only measure the real production files if
something answers in Ollama's shape. This shim does exactly that, and nothing else.

Run (in a LiteRT-LM 0.12.0 venv, with the locally unpacked GL/Vulkan loader libraries;
the paths below are placeholders):

    export LITERT_HOME=/path/to/edge-litert
    export LD_LIBRARY_PATH="$LITERT_HOME/deb/root/usr/lib/x86_64-linux-gnu"
    export SOLIN_LAB_LITERT_MODEL_ROOT="$LITERT_HOME/models"
    "$LITERT_HOME/venv/bin/python" -I lab/scripts/litert_shim.py \\
        --port 11437 --cache-dir /path/to/litert-cache

`--model-root` overrides `SOLIN_LAB_LITERT_MODEL_ROOT`; one of them is required.

Then point the harness at it:

    export SOLIN_LAB_OLLAMA_URL=http://127.0.0.1:11437

Endpoints: `POST /api/chat`, `POST /api/show`, `GET /api/tags`, `GET /api/version`.
A `format` field (Ollama structured output / JSON schema) is rejected with HTTP 400
`format_unsupported`: LiteRT-LM 0.12.0 has constrained decoding, but wiring it here would make a
json-schema mode look supported while it is not the app's path, so the bench records it as
unsupported instead.

Everything the bench measures came out of `litert_lm`; this file only translates shapes. It is
never imported by production code, and the app is not changed by it.
"""

import argparse
import hashlib
import json
import os
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


# Model id -> (file name, max_num_tokens). The 270M keeps the file's own ekv1024 default, the way
# ActionPlanningRuntime opens it; the Gemma 4 files match LocalModelTokenLimits.MAX_TOTAL_TOKENS.
MODELS = {
    "litert/mobile-actions-270m": ("mobile-actions_q8_ekv1024.litertlm", None),
    "litert/gemma-4-e2b": ("gemma-4-E2B-it.litertlm", 8192),
    "litert/gemma-4-e4b": ("gemma-4-E4B-it.litertlm", 8192),
}

# One engine per model, loaded lazily, and only one model resident at a time: loading two Gemma 4
# files at once is more memory than the bench needs and would also contend for CPU.
_engine_lock = threading.Lock()
_loaded = {"name": None, "engine": None, "cache": None}


def _import_litert():
    try:
        import litert_lm  # noqa: F401
    except Exception as error:  # pragma: no cover - environment problem, not a code path
        sys.exit(
            "litert_lm is not importable: %s\n"
            "Use the coordinator venv and set LD_LIBRARY_PATH first (see the module docstring)." % error
        )
    import litert_lm

    return litert_lm


def _model_path(model_root, name):
    file_name, _ = MODELS[name]
    return os.path.join(model_root, file_name)


def _digest(path):
    """SHA-256 of the model file, computed once per process and cached."""
    cached = _digest.cache.get(path)
    if cached:
        return cached
    h = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            h.update(chunk)
    value = h.hexdigest()
    _digest.cache[path] = value
    return value


_digest.cache = {}


def _engine_for(model_root, name, cache_dir=""):
    litert = _import_litert()
    with _engine_lock:
        if _loaded["name"] == name and _loaded["cache"] == cache_dir:
            return _loaded["engine"]
        # Unload the previous model before loading the next one.
        _loaded["engine"] = None
        _loaded["name"] = None
        _, max_tokens = MODELS[name]
        kwargs = {} if max_tokens is None else {"max_num_tokens": max_tokens}
        if cache_dir:
            # A second shim process must not share a compiled-model cache with the first.
            kwargs["cache_dir"] = cache_dir
        started = time.time()
        engine = litert.Engine(_model_path(model_root, name), backend=litert.interfaces.CPU(), **kwargs)
        print(
            "[shim] loaded %s in %.1fs%s%s" % (
                name,
                time.time() - started,
                "" if max_tokens is None else " max_num_tokens=%d" % max_tokens,
                "" if not cache_dir else " cache_dir=%s" % cache_dir,
            ),
            flush=True,
        )
        _loaded["engine"] = engine
        _loaded["name"] = name
        _loaded["cache"] = cache_dir
        return engine


def _tool_classes(litert, tools_json):
    """One [litert.interfaces.Tool] subclass per Ollama function declaration.

    `get_tool_description()` returns the declaration wrapped as `{"function": {...}}`, which is the
    shape `create_conversation` requires (it reads `description["function"]["name"]`). The model
    therefore sees exactly the schema the bench passed. `execute()` is never reached:
    `automatic_tool_calling=False` means the model only proposes a call, which the bench records
    instead of running.
    """
    classes = []
    for entry in tools_json or []:
        function = entry.get("function") or entry
        name = function.get("name") or ""
        if not name:
            continue
        description = {
            "type": "function",
            "function": {
                "name": name,
                "description": function.get("description") or "",
                "parameters": function.get("parameters") or {"type": "object", "properties": {}},
            },
        }

        class ProposedTool(litert.interfaces.Tool):
            def get_tool_description(self, _description=description):
                return _description

            def execute(self, param):
                return {"not_executed": True, "param": dict(param)}

        ProposedTool.__name__ = "tool_" + name
        classes.append(ProposedTool())
    return classes


def _text_of(response):
    """Flatten a send_message result to the assistant's text, ignoring text parts we cannot use."""
    if not isinstance(response, dict):
        return str(response or "")
    content = response.get("content")
    if isinstance(content, str):
        return content
    parts = []
    for part in content or []:
        if isinstance(part, dict) and part.get("type") == "text":
            parts.append(part.get("text") or "")
    return "".join(parts)


def _tool_calls_of(response):
    """Ollama-shaped tool calls from a LiteRT response, or an empty list."""
    if not isinstance(response, dict):
        return []
    calls = []
    for call in response.get("tool_calls") or []:
        function = (call or {}).get("function") or {}
        arguments = function.get("arguments")
        if isinstance(arguments, str):
            try:
                arguments = json.loads(arguments)
            except ValueError:
                arguments = {}
        calls.append({"function": {"name": function.get("name") or "", "arguments": arguments or {}}})
    return calls


def _estimate_output_tokens(text):
    """The same conservative streaming-chunk estimate as LocalEngine.collectOneShot."""
    if not text or text.isspace():
        return 0
    tokens = 0
    ascii_run = 0
    for char in text:
        if not char.isspace() and 0x20 <= ord(char) <= 0x7E:
            ascii_run += 1
        else:
            tokens += (ascii_run + 3) // 4
            ascii_run = 0
            tokens += 1
    return tokens + (ascii_run + 3) // 4


def _send_one_shot(conversation, user, max_output_tokens):
    """Send with the production streaming output cap, draining after native cancellation."""
    if max_output_tokens is None:
        return conversation.send_message(user), False
    text_parts = []
    tool_calls = []
    output_tokens = 0
    capped = False
    for chunk in conversation.send_message_async(user):
        if capped:
            continue
        text = _text_of(chunk)
        text_parts.append(text)
        tool_calls.extend(_tool_calls_of(chunk))
        output_tokens += _estimate_output_tokens(text)
        if output_tokens >= max_output_tokens:
            capped = True
            conversation.cancel_process()
    return {"role": "assistant", "content": "".join(text_parts), "tool_calls": tool_calls}, capped


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    model_root = "."
    cache_dir = ""
    litert = None

    def log_message(self, fmt, *args):  # quieter than the default
        sys.stderr.write("[shim] " + (fmt % args) + "\n")

    def _send(self, status, payload):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _body(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b"{}"
        return json.loads(raw or b"{}")

    def do_GET(self):
        if self.path == "/api/version":
            return self._send(200, {"version": "litert-lm-0.12.0"})
        if self.path == "/api/tags":
            models = []
            for name in MODELS:
                path = _model_path(self.model_root, name)
                if os.path.isfile(path):
                    models.append(
                        {"name": name, "model": name, "size": os.path.getsize(path), "digest": _digest(path)}
                    )
            return self._send(200, {"models": models})
        return self._send(404, {"error": "not_found"})

    def do_POST(self):
        try:
            body = self._body()
        except ValueError as error:
            return self._send(400, {"error": "bad_json:%s" % error})
        if self.path == "/api/show":
            name = body.get("model")
            if name not in MODELS:
                return self._send(404, {"error": "unknown_model:%s" % name})
            return self._send(200, {"capabilities": ["completion", "tools"]})
        if self.path == "/api/chat":
            return self._chat(body)
        return self._send(404, {"error": "not_found"})

    def _chat(self, body):
        name = body.get("model")
        if name not in MODELS:
            return self._send(404, {"error": "unknown_model:%s" % name})
        if body.get("format") is not None:
            # Never silently drop a structured-output request: the bench records it as unsupported.
            return self._send(400, {"error": "format_unsupported"})
        path = _model_path(self.model_root, name)
        if not os.path.isfile(path):
            return self._send(404, {"error": "model_file_missing:%s" % path})

        messages = body.get("messages") or []
        system = "\n".join(m.get("content") or "" for m in messages if m.get("role") == "system")
        user = ""
        for message in messages:
            if message.get("role") == "user":
                user = message.get("content") or ""
        options = body.get("options") or {}
        max_output_tokens = options.get("num_predict")
        if max_output_tokens is not None:
            max_output_tokens = int(max_output_tokens)
            if max_output_tokens <= 0:
                return self._send(400, {"error": "invalid_num_predict"})
        sampler = self.litert.SamplerConfig(
            top_k=int(options.get("top_k") or 1),
            top_p=float(options.get("top_p") or 0.1),
            temperature=float(options.get("temperature") or 0.0),
            seed=int(options["seed"]) if "seed" in options else None,
        )
        engine = _engine_for(self.model_root, name, self.cache_dir)
        started = time.time()
        try:
            # A fresh conversation per request with empty history, as a one-shot in the app.
            conversation = engine.create_conversation(
                system_message=system or None,
                tools=_tool_classes(self.litert, body.get("tools")) or None,
                automatic_tool_calling=False,
                sampler_config=sampler,
            )
            try:
                response, output_capped = _send_one_shot(conversation, user, max_output_tokens)
            finally:
                conversation.close()
        except Exception as error:
            # An overflow or an engine failure is a recorded failure, never a truncated prompt.
            text = str(error)
            print("[shim] chat error for %s: %s" % (name, text), flush=True)
            return self._send(
                200,
                {
                    "message": {"role": "assistant", "content": ""},
                    "error": "litert_error:%s" % text,
                    "done_reason": "error",
                },
            )
        elapsed_ms = int((time.time() - started) * 1000)
        return self._send(
            200,
            {
                "model": name,
                "message": {"role": "assistant", "content": _text_of(response), "tool_calls": _tool_calls_of(response)},
                "done": True,
                "done_reason": "length" if output_capped else "stop",
                "litert_output_capped": output_capped,
                # LiteRT-LM 0.12.0 does not report token counts here; 0 means "not available".
                "total_duration": elapsed_ms * 1_000_000,
                "load_duration": 0,
                "prompt_eval_count": 0,
                "prompt_eval_duration": 0,
                "eval_count": 0,
                "eval_duration": 0,
                "litert_wall_ms": elapsed_ms,
            },
        )


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=11437)
    parser.add_argument("--model-root", default=os.environ.get("SOLIN_LAB_LITERT_MODEL_ROOT") or None)
    parser.add_argument("--cache-dir", default="", help="compiled-model cache; keep one per shim process")
    args = parser.parse_args(argv[1:])
    if not args.model_root:
        parser.error("--model-root or SOLIN_LAB_LITERT_MODEL_ROOT is required")
    Handler.model_root = args.model_root
    Handler.cache_dir = args.cache_dir
    Handler.litert = _import_litert()
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print("[shim] listening on http://127.0.0.1:%d (model root %s)" % (args.port, args.model_root), flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
