# Hermes CoAgent — Relay Server

A single-file Python 3 relay that lets your Hermes controller talk to a
phone running Hermes CoAgent even when the phone sits behind carrier NAT
(i.e. cellular data). Both sides make **outbound** HTTP requests to this
server — no inbound port on the phone is ever needed.

Stdlib only. No Flask, no third-party packages.

## Run it

```bash
python3 relay.py            # binds 0.0.0.0:8787
python3 relay.py 9000       # custom port
```

On startup it prints:

```
hermes-relay listening on 0.0.0.0:8787
```

State is in-memory only — restart wipes registered devices and queued
commands.

## Exposing it to the phone

The phone needs to reach the relay over the internet. Pick one:

- **Ngrok / Cloudflare Tunnel** (fastest for testing):
  `ngrok http 8787` → copy the `https://…ngrok-free.app` URL into the app.
- **Small VPS**: run `relay.py` on a $5/mo VPS and give the phone
  `http://your.vps.ip:8787` (or put nginx + Let's Encrypt in front).
- **Home LAN + port forward**: forward TCP 8787 on your router.

## Configure the phone

1. Open **Hermes CoAgent** → scroll to **Remote Mode**.
2. Paste the relay base URL (no trailing slash needed).
3. Flip the **Enable Remote Mode** switch.
4. Status should turn green: `remote: connected → https://…`.

The phone reuses the same **X-Hermes-Token** shown at the top of the app,
so LAN and Remote share one secret.

## Protocol

All bodies are JSON. `device_id` is the phone's persistent UUID (auto-
generated once and stored in SharedPreferences). `token` matches the
phone's auth token.

### `POST /register` — phone → relay
```json
{"device_id": "abc-123", "token": "hex..."}
```
Reply: `{"ok": true}`

### `POST /command` — controller → relay (enqueue)
```json
{"device_id": "abc-123", "token": "hex...", "action": {"action":"tap","x":100,"y":200}}
```
Reply: `{"ok": true, "command_id": "cid..."}`

### `GET /poll?device_id=…&token=…` — phone → relay (long-poll, ≤25s)
Reply when a command arrives:
```json
{"commands": [{"command_id": "cid...", "action": {...}}]}
```
Or on timeout: `{"commands": []}`

### `POST /result` — phone → relay
```json
{"device_id":"abc-123","token":"hex...","command_id":"cid...","result":{...}}
```
Reply: `{"ok": true}`

### `GET /result?device_id=…&command_id=…` — controller → relay
```json
{"status":"done","result":{"ok":true,"pong":true}}
```
Or `{"status":"pending"}` if the phone hasn't answered yet.

## Curl example — controller side

```bash
BASE=https://your-relay.example.com
DEV=abc-123
TOK=your-hex-token

# 1. Enqueue a tap.
CID=$(curl -s "$BASE/command" -H 'content-type: application/json' \
    -d "{\"device_id\":\"$DEV\",\"token\":\"$TOK\",\"action\":{\"action\":\"ping\"}}" \
    | python3 -c 'import sys,json; print(json.load(sys.stdin)["command_id"])')

# 2. Poll for the phone's answer.
until curl -s "$BASE/result?device_id=$DEV&command_id=$CID" | grep -q '"done"'; do
    sleep 0.2
done
curl -s "$BASE/result?device_id=$DEV&command_id=$CID"
```

## Reconnect behavior

If the phone loses network, changes networks, or gets a non-2xx from
`/poll`, it sleeps ~3 s and re-runs the full cycle: register → poll →
execute → result. The background thread never dies; toggling **Remote
Mode** off is the only way to stop it.

## Auth / thread-safety notes

- Tokens are compared as plain strings under a single `threading.Lock`.
- Unknown device or bad token → `401 {"ok": false, "error": "..."}`.
- Each device has its own `threading.Event` so `/command` wakes the
  matching `/poll` immediately — round-trip latency is bounded by the
  network, not by polling cadence.
- Missing fields → `400 {"ok": false, "error": "..."}`.
