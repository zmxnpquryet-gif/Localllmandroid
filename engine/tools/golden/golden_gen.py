#!/usr/bin/env python3
"""Golden-logit generator for SDengine correctness verification.

Runs llama.cpp's llama-server against a GGUF model, feeds a fixed set of
prompts through the OpenAI-compatible completions API with greedy decoding,
and records per prompt:

  - the exact prompt token ids llama.cpp used (/tokenize, add_special=true)
  - the top-20 logprobs at the last prompt position (i.e. the distribution
    of the first generated token)
  - the greedy continuation (up to N tokens) with per-position top-20
    logprobs and logprobs of the sampled token

Token strings are mapped back to token ids via the GGUF vocab itself, so the
golden file carries ids (the OpenAI API only returns strings).

Raw logits are not exposed by the API; logprobs are. The comparison against
SDengine uses logit gaps (logit_i - logit_max == logprob_i - logprob_max),
which are exactly recoverable from logprobs.

Usage:
  python golden_gen.py -m <model.gguf> -o <out.json> \
      [--llama-server llama-server] [--port 8017] [--ctx 2048] \
      [--max-tokens 32] [--top-logprobs 20]

Requires: llama-server on PATH or an explicit path. Python 3.8+, stdlib only.
"""

import argparse
import json
import hashlib
import os
import shutil
import signal
import struct
import subprocess
import sys
import time
import urllib.request
import urllib.error

PROMPTS = [
    "The capital of France is",
    "def fibonacci(n):",
    "1, 1, 2, 3, 5, 8,",
    "\ub300\ud55c\ubbfc\uad6d\uc758 \uc218\ub3c4\ub294",  # Korean: "the capital of Korea is"
    "Q: What is the largest planet in the solar system? A:",
]


def fail(msg):
    print("ERROR: " + msg, file=sys.stderr)
    sys.exit(1)


# ---------------------------------------------------------------------------
# Minimal GGUF metadata reader (enough to pull tokenizer.ggml.tokens).
# ---------------------------------------------------------------------------

def read_gguf_tokens(path):
    """Returns (list_of_token_byte_strings, arch) from a GGUF file header."""
    f = open(path, "rb")
    magic = f.read(4)
    if magic != b"GGUF":
        fail("not a GGUF file: %s" % path)
    struct.unpack("<i", f.read(4))  # version
    n_tensors, n_meta = struct.unpack("<QQ", f.read(16))

    def rstr():
        n = struct.unpack("<Q", f.read(8))[0]
        return f.read(n)

    def rval(t):
        if t == 0:
            return f.read(1)[0]
        if t == 1:
            return struct.unpack("<b", f.read(1))[0]
        if t == 2:
            return struct.unpack("<H", f.read(2))[0]
        if t == 3:
            return struct.unpack("<h", f.read(2))[0]
        if t == 4:
            return struct.unpack("<I", f.read(4))[0]
        if t == 5:
            return struct.unpack("<i", f.read(4))[0]
        if t == 6:
            return struct.unpack("<f", f.read(4))[0]
        if t == 7:
            return f.read(1)[0] != 0
        if t == 8:
            return rstr()
        if t in (10, 11):
            return struct.unpack("<q", f.read(8))[0]
        if t == 12:
            return struct.unpack("<d", f.read(8))[0]
        if t == 9:
            et = struct.unpack("<I", f.read(4))[0]
            n = struct.unpack("<Q", f.read(8))[0]
            return [rval(et) for _ in range(n)]
        fail("unknown gguf metadata type %d" % t)

    meta = {}
    for _ in range(n_meta):
        key = rstr().decode("utf-8")
        t = struct.unpack("<I", f.read(4))[0]
        meta[key] = rval(t)
    f.close()

    tokens = meta.get("tokenizer.ggml.tokens")
    if tokens is None:
        fail("GGUF has no tokenizer.ggml.tokens")
    return [t.decode("utf-8", errors="replace") for t in tokens], meta


def build_reverse_map(token_strings):
    """token spelling -> id. On duplicate spellings keeps the FIRST id and
    records the collision so the generator can cross-check via /tokenize."""
    rev = {}
    dup = {}
    for i, s in enumerate(token_strings):
        if s in rev and rev[s] != i:
            dup.setdefault(s, [rev[s]]).append(i)
        else:
            rev.setdefault(s, i)
    return rev, dup


# ---------------------------------------------------------------------------
# llama-server control + HTTP helpers
# ---------------------------------------------------------------------------

def http_json(url, payload=None, timeout=600):
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(url, data=data, method="GET" if payload is None else "POST")
    req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8"))


def wait_healthy(port, timeout_s):
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        try:
            with urllib.request.urlopen("http://127.0.0.1:%d/health" % port, timeout=5) as r:
                if r.status == 200:
                    return True
        except Exception:
            pass
        time.sleep(2.0)
    return False


def top_logprobs_entries(entry):
    """Normalizes one top_logprobs entry across llama.cpp server formats:
    dict {token: logprob} or list of {token, logprob}."""
    if entry is None:
        return []
    if isinstance(entry, dict):
        # two known dict shapes: {"tok": logprob} or {"token": ..., "logprob": ...}
        if "logprob" in entry:
            return [(entry.get("token", entry.get("tok", "")), entry["logprob"])]
        return [(k, v) for k, v in entry.items()]
    if isinstance(entry, list):
        out = []
        for e in entry:
            if isinstance(e, dict):
                out.append((e.get("token", e.get("tok", "")), float(e.get("logprob", e.get("prob", 0.0)))))
        return out
    return []


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-m", "--model", required=True)
    ap.add_argument("-o", "--out", required=True)
    ap.add_argument("--llama-server", default="llama-server")
    ap.add_argument("--port", type=int, default=8017)
    ap.add_argument("--ctx", type=int, default=2048)
    ap.add_argument("--max-tokens", type=int, default=32)
    ap.add_argument("--top-logprobs", type=int, default=20)
    ap.add_argument("--keep-server", action="store_true",
                    help="do not shut the server down at the end")
    args = ap.parse_args()

    if not os.path.isfile(args.model):
        fail("model file not found: %s" % args.model)

    server_bin = shutil.which(args.llama_server) or args.llama_server
    version = subprocess.run([server_bin, "--version"], capture_output=True, text=True)
    version_text = (version.stdout + version.stderr).strip().splitlines()
    version_text = version_text[0] if version_text else "unknown"

    print("llama-server: %s (%s)" % (server_bin, version_text))
    print("model: %s (%d bytes)" % (args.model, os.path.getsize(args.model)))

    token_strings, meta = read_gguf_tokens(args.model)
    rev, dup = build_reverse_map(token_strings)
    arch = meta.get("general.architecture", "unknown")
    print("arch=%s vocab=%d dup-spellings=%d" % (arch, len(token_strings), len(dup)))

    h = hashlib.sha256()
    with open(args.model, "rb") as f:
        h.update(f.read(1024 * 1024))

    cmd = [
        server_bin, "-m", args.model,
        "--port", str(args.port),
        "-c", str(args.ctx),
        "-ngl", "0",
        "--parallel", "1",
    ]
    print("starting: %s" % " ".join(cmd))
    server = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        if not wait_healthy(args.port, timeout_s=1800):
            fail("llama-server did not become healthy in 30 min")
        print("server healthy")

        base = "http://127.0.0.1:%d" % args.port

        def resolve_id(tok_str, ids_used):
            if tok_str in rev:
                return rev[tok_str]
            # Ambiguous spelling (or absent): ask the server to tokenize it.
            try:
                r = http_json(base + "/tokenize", {"content": tok_str}, timeout=120)
                tids = r.get("tokens", [])
                if len(tids) == 1:
                    ids_used.add(("tokenize-resolved", tok_str))
                    return tids[0]
                ids_used.add(("unresolved", tok_str))
            except Exception as e:
                ids_used.add(("tokenize-failed:%s" % e, tok_str))
            return -1

        goldens = []
        ids_used = set()
        for pi, prompt in enumerate(PROMPTS):
            tk = http_json(base + "/tokenize", {"content": prompt, "add_special": True}, timeout=300)
            prompt_ids = tk["tokens"]
            print("prompt[%d] %r -> %d ids %s" % (pi, prompt[:40], len(prompt_ids), prompt_ids[:12]))

            comp = http_json(base + "/v1/completions", {
                "prompt": prompt_ids,
                "max_tokens": args.max_tokens,
                "temperature": 0.0,
                "logprobs": args.top_logprobs,
                "stream": False,
            }, timeout=3600)
            ch = comp["choices"][0]
            lp = ch.get("logprobs") or {}
            toks = lp.get("tokens") or []
            tok_lp = lp.get("token_logprobs") or []
            tops = lp.get("top_logprobs") or []

            finish = ch.get("finish_reason")
            usage = comp.get("usage") or {}
            n_prompt = usage.get("prompt_tokens", len(prompt_ids))
            if n_prompt != len(prompt_ids):
                print("WARNING: server used %d prompt tokens, /tokenize said %d"
                      % (n_prompt, len(prompt_ids)))

            greedy = []
            for i, ts in enumerate(toks):
                tid = resolve_id(ts, ids_used)
                greedy.append({
                    "id": tid,
                    "token": ts,
                    "logprob": tok_lp[i] if i < len(tok_lp) else None,
                })

            # top-20 at the last prompt position == entry 0 of the generation.
            top20 = []
            if tops:
                for ts, v in top_logprobs_entries(tops[0])[: args.top_logprobs]:
                    top20.append({"id": resolve_id(ts, ids_used), "token": ts, "logprob": v})

            goldens.append({
                "prompt": prompt,
                "prompt_ids": prompt_ids,
                "top20_last_prompt_pos": top20,
                "greedy": greedy,
                "finish_reason": finish,
            })
            print("  generated %d tokens, finish=%s, top1=%r(%.4f)"
                  % (len(greedy), finish,
                     greedy[0]["token"] if greedy else None,
                     greedy[0]["logprob"] if greedy else 0.0))

        out = {
            "generator": {
                "tool": "engine/tools/golden/golden_gen.py",
                "llama_cpp_version": version_text,
            },
            "model": {
                "file": os.path.basename(args.model),
                "arch": arch,
                "bytes": os.path.getsize(args.model),
                "sha256_first_1mb": h.hexdigest(),
            },
            "params": {
                "max_tokens": args.max_tokens,
                "temperature": 0.0,
                "top_logprobs": args.top_logprobs,
            },
            "prompts": goldens,
            "notes": [
                "top20_last_prompt_pos entries: logprobs of the first generated position,",
                "i.e. the distribution over the token following the last prompt token.",
                "Logit gaps are recoverable as logprob_i - logprob_max.",
                "resolve fallbacks: %s" % (sorted(ids_used) if ids_used else "none"),
            ],
        }
        with open(args.out, "w", encoding="utf-8") as f:
            json.dump(out, f, ensure_ascii=False, indent=1)
        print("wrote %s" % args.out)
    finally:
        if args.keep_server:
            print("server left running on port %d (pid %d)" % (args.port, server.pid))
        else:
            server.terminate()
            try:
                server.wait(timeout=30)
            except subprocess.TimeoutExpired:
                server.kill()
            print("server stopped")


if __name__ == "__main__":
    main()
