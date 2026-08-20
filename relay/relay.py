#!/usr/bin/env python3
"""
Hermes CoAgent — minimal relay server.

Bridges an Android phone (which dials OUT to us) and a controller (which
POSTs commands to us). Works from anywhere because both sides make outbound
connections; the phone never needs an inbound port through carrier NAT.

Endpoints:
  POST /register  {"device_id","token"}                       -> {"ok":true}
  POST /command   {"device_id","token","action":{...}}        -> {"ok":true,"command_id":"..."}
  GET  /poll?device_id=..&token=..   (long-poll, up to 25s)   -> {"commands":[{command_id, action}, ...]}
  POST /result    {"device_id","token","command_id","result"} -> {"ok":true}
  GET  /result?device_id=..&command_id=..                     -> {"status":"done","result":..} or {"status":"pending"}

In-memory only. Restart wipes state. Stdlib only.
"""

import json
import sys
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

POLL_TIMEOUT_SEC = 25
POLL_TICK_SEC = 0.25
MAX_BODY_BYTES = 512 * 1024
RESULT_TTL_SEC = 300

_lock = threading.Lock()
_tokens = {}          # device_id -> token
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


def _auth_ok(device_id, token):
    """Caller holds _lock (or accepts race — it's an in-memory demo)."""
    known = _tokens.get(device_id)
    return known is not None and known == token


class Handler(BaseHTTPRequestHandler):
    server_version = "HermesRelay/1.0"

    def log_message(self, fmt, *args):
        sys.stderr.write("[relay] %s - %s\n" % (self.address_string(), fmt % args))

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
                cid = uuid.uuid4().hex
                _queues.setdefault(device_id, []).append({"command_id": cid, "action": action})
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
            return self._send_json(200, {"ok": True})

        return self._send_json(404, {"ok": False, "error": "unknown endpoint"})

    # ── GET endpoints ───────────────────────────────────────────────────
    def do_GET(self):
        parsed = urlparse(self.path)
        path = parsed.path
        qs = {k: v[0] for k, v in parse_qs(parsed.query).items()}

        if path == "/poll":
            device_id = qs.get("device_id")
            token = qs.get("token")
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
            if not command_id:
                return self._send_json(400, {"ok": False, "error": "command_id required"})
            with _lock:
                r = _results.get(command_id)
            if r is None:
                return self._send_json(200, {"status": "pending"})
            return self._send_json(200, {"status": "done", "result": r["result"]})

        if path == "/devices":
            with _lock:
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
