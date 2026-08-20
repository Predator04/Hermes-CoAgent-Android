#!/usr/bin/env python3
"""
Hermes CoAgent — minimal relay server.

Bridges an Android phone (which dials OUT to us) and a controller (which
POSTs commands to us). Works from anywhere because both sides make outbound
connections; the phone never needs an inbound port through carrier NAT.

Endpoints:
  POST /register  {"device_id","token"}                       -> {"ok":true}
  POST /command   {"device_id","token","action":{...}}        -> {"ok":true,"command_id":"..."}
  GET  /poll?device_id=..    (long-poll, up to 25s)           -> {"commands":[{command_id, action}, ...]}
  POST /result    {"device_id","token","command_id","result"} -> {"ok":true}
  GET  /result?command_id=..[&device_id=..]                   -> {"status":"done","result":..} or {"status":"pending"}
  GET  /devices                                               -> {"ok":true,"devices":[...]}

Auth: tokens for GET requests should be passed via the `X-Hermes-Token`
header. A legacy `?token=` query param is still accepted but discouraged
(URLs land in access logs / proxies). GET /result and /devices require
`token` — either the registered token of the owning device, or the
optional controller token in the env var HERMES_CONTROLLER_TOKEN.
GET /devices requires HERMES_CONTROLLER_TOKEN when it is set; when it is
unset, any registered device token is accepted (single-device dev mode).

In-memory only. Restart wipes state. Stdlib only.
"""

import json
import os
import sys
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

POLL_TIMEOUT_SEC = 25
POLL_TICK_SEC = 0.25
MAX_BODY_BYTES = 8 * 1024 * 1024   # 8 MB — photo/audio base64 results (4MP JPEG ≈ 2–3 MB base64)
RESULT_TTL_SEC = 300
MAX_QUEUE_LEN = 200          # cap pending commands per device (queue-flood guard)
MAX_DEVICES = 1000           # cap distinct registered device_ids (memory guard)

# Optional out-of-band controller token, set via env. When present, callers
# may pass ?token=<CONTROLLER_TOKEN> to authenticate to GET /result and
# /devices without knowing any device's token.
_CONTROLLER_TOKEN = os.environ.get("HERMES_CONTROLLER_TOKEN", "").strip()

_lock = threading.Lock()
_tokens = {}          # device_id -> token
_result_owner = {}    # command_id -> device_id (who submitted the result)
_queues = {}          # device_id -> [ {command_id, action}, ... ]
_results = {}         # command_id -> {"result": obj, "ts": epoch}
_events = {}          # device_id -> threading.Event


def _event_for(device_id):
    ev = _events.get(device_id)
    if ev is None:
        ev = threading.Event()
        _events[device_id] = ev
    return ev


def _prune_results_locked():
    cutoff = time.time() - RESULT_TTL_SEC
    stale = [cid for cid, r in _results.items() if r["ts"] < cutoff]
    for cid in stale:
        _results.pop(cid, None)
        _result_owner.pop(cid, None)


def _auth_ok(device_id, token):
    """Caller holds _lock (or accepts race — it's an in-memory demo)."""
    known = _tokens.get(device_id)
    return known is not None and known == token


def _controller_auth_ok_locked(token, device_id=None):
    """Token accepted if it matches the configured controller token, or
    matches the specified device's registered token, or (when device_id is
    None) matches ANY registered device token. Caller holds _lock."""
    if not token:
        return False
    if _CONTROLLER_TOKEN and token == _CONTROLLER_TOKEN:
        return True
    if device_id is not None:
        return _auth_ok(device_id, token)
    for dev_token in _tokens.values():
        if token == dev_token:
            return True
    return False


def _strip_token_from_url(s):
    """Remove `token=...` from a URL/log line so tokens never land on disk."""
    if not s or "token=" not in s:
        return s
    out = []
    for part in s.split():
        if "token=" in part:
            # Split on ? and & so we can rebuild without any token= pair.
            base, _, query = part.partition("?")
            if not query:
                # token= might appear in a bare query fragment (rare)
                keep = "&".join(seg for seg in part.split("&")
                                if not seg.startswith("token="))
                out.append(keep)
                continue
            pairs = [seg for seg in query.split("&") if not seg.startswith("token=")]
            rebuilt = base + ("?" + "&".join(pairs) if pairs else "")
            out.append(rebuilt)
        else:
            out.append(part)
    return " ".join(out)


class Handler(BaseHTTPRequestHandler):
    server_version = "HermesRelay/1.0"

    def log_message(self, fmt, *args):
        msg = fmt % args
        sys.stderr.write("[relay] %s - %s\n" % (self.address_string(),
                                                _strip_token_from_url(msg)))

    def _header_token(self):
        """Prefer the X-Hermes-Token header over any ?token= query param."""
        h = self.headers.get("X-Hermes-Token")
        return h.strip() if h else None

    def _read_body(self):
        length = int(self.headers.get("Content-Length", "0") or "0")
        if length <= 0:
            return {}
        if length > MAX_BODY_BYTES:
            return None
        raw = self.rfile.read(length)
        try:
            return json.loads(raw.decode("utf-8"))
        except Exception:
            return None

    def _send_json(self, code, obj):
        body = json.dumps(obj).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)

    # ── POST endpoints ──────────────────────────────────────────────────
    def do_POST(self):
        path = urlparse(self.path).path
        body = self._read_body()
        if body is None:
            return self._send_json(400, {"ok": False, "error": "invalid JSON body"})

        if path == "/register":
            device_id = body.get("device_id")
            token = body.get("token")
            if not device_id or not token:
                return self._send_json(400, {"ok": False, "error": "device_id and token required"})
            with _lock:
                existing = _tokens.get(device_id)
                if existing is not None and existing != token:
                    # Someone else already claimed this device_id. Allow rotation
                    # only if the caller proves knowledge of the previous token
                    # (X-Hermes-Token header) or holds the controller token.
                    proof = self._header_token()
                    if proof != existing and not (_CONTROLLER_TOKEN and proof == _CONTROLLER_TOKEN):
                        return self._send_json(409, {"ok": False, "error": "device_id already registered"})
                if existing is None and len(_tokens) >= MAX_DEVICES:
                    return self._send_json(429, {"ok": False, "error": "device registry full"})
                _tokens[device_id] = token
                _queues.setdefault(device_id, [])
                _event_for(device_id)
            return self._send_json(200, {"ok": True})

        if path == "/command":
            device_id = body.get("device_id")
            token = body.get("token")
            action = body.get("action")
            if not device_id or not token or action is None:
                return self._send_json(400, {"ok": False, "error": "device_id, token, action required"})
            with _lock:
                if not _auth_ok(device_id, token):
                    return self._send_json(401, {"ok": False, "error": "unknown device or bad token"})
                q = _queues.setdefault(device_id, [])
                if len(q) >= MAX_QUEUE_LEN:
                    return self._send_json(429, {"ok": False, "error": "device queue full"})
                cid = uuid.uuid4().hex
                q.append({"command_id": cid, "action": action})
                ev = _event_for(device_id)
            ev.set()
            return self._send_json(200, {"ok": True, "command_id": cid})

        if path == "/result":
            device_id = body.get("device_id")
            token = body.get("token")
            command_id = body.get("command_id")
            result = body.get("result")
            if not device_id or not token or not command_id:
                return self._send_json(400, {"ok": False, "error": "device_id, token, command_id required"})
            with _lock:
                if not _auth_ok(device_id, token):
                    return self._send_json(401, {"ok": False, "error": "unknown device or bad token"})
                _prune_results_locked()
                _results[command_id] = {"result": result, "ts": time.time()}
                _result_owner[command_id] = device_id
            return self._send_json(200, {"ok": True})

        return self._send_json(404, {"ok": False, "error": "unknown endpoint"})

    # ── GET endpoints ───────────────────────────────────────────────────
    def do_GET(self):
        parsed = urlparse(self.path)
        path = parsed.path
        qs = {k: v[0] for k, v in parse_qs(parsed.query).items()}

        if path == "/poll":
            device_id = qs.get("device_id")
            token = self._header_token() or qs.get("token")
            if not device_id or not token:
                return self._send_json(400, {"ok": False, "error": "device_id and token required"})
            with _lock:
                if not _auth_ok(device_id, token):
                    return self._send_json(401, {"ok": False, "error": "unknown device or bad token"})
                queued = _queues.get(device_id, [])
                if queued:
                    _queues[device_id] = []
                    return self._send_json(200, {"commands": queued})
                ev = _event_for(device_id)
                ev.clear()

            # Long-poll: wait up to POLL_TIMEOUT_SEC for a command.
            deadline = time.time() + POLL_TIMEOUT_SEC
            while time.time() < deadline:
                remaining = deadline - time.time()
                if remaining <= 0:
                    break
                if ev.wait(min(POLL_TICK_SEC, remaining)):
                    break

            with _lock:
                queued = _queues.get(device_id, [])
                _queues[device_id] = []
            return self._send_json(200, {"commands": queued})

        if path == "/result":
            command_id = qs.get("command_id")
            token = self._header_token() or qs.get("token")
            device_id = qs.get("device_id")
            if not command_id:
                return self._send_json(400, {"ok": False, "error": "command_id required"})
            if not token:
                return self._send_json(401, {"ok": False, "error": "token required"})
            with _lock:
                # If caller passed device_id, the token must belong to that
                # device (or the controller). Otherwise resolve the device
                # from the result's owner so the token check is not just
                # "any known token" — it must match the owning device.
                owner = _result_owner.get(command_id)
                bound_device = device_id or owner
                if not _controller_auth_ok_locked(token, bound_device):
                    return self._send_json(401, {"ok": False, "error": "bad token"})
                r = _results.get(command_id)
            if r is None:
                return self._send_json(200, {"status": "pending"})
            return self._send_json(200, {"status": "done", "result": r["result"]})

        if path == "/devices":
            token = self._header_token() or qs.get("token")
            if not token:
                return self._send_json(401, {"ok": False, "error": "token required"})
            with _lock:
                # When HERMES_CONTROLLER_TOKEN is configured, ONLY it may list
                # devices — a compromised device token must not enumerate peers.
                if _CONTROLLER_TOKEN:
                    if token != _CONTROLLER_TOKEN:
                        return self._send_json(401, {"ok": False, "error": "bad token"})
                elif not _controller_auth_ok_locked(token):
                    return self._send_json(401, {"ok": False, "error": "bad token"})
                ids = list(_tokens.keys())
            return self._send_json(200, {"ok": True, "devices": ids})

        if path == "/" or path == "/health":
            return self._send_json(200, {"ok": True, "service": "hermes-relay"})

        return self._send_json(404, {"ok": False, "error": "unknown endpoint"})


def main():
    port = 8787
    if len(sys.argv) > 1:
        try:
            port = int(sys.argv[1])
        except ValueError:
            print("usage: relay.py [port]", file=sys.stderr)
            sys.exit(2)
    server = ThreadingHTTPServer(("0.0.0.0", port), Handler)
    print("hermes-relay listening on 0.0.0.0:%d" % port, flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
