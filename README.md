# Hermes CoAgent — Android

Turn an Android phone into a remotely-drivable device — the Android companion to the [Hermes CoAgent](https://github.com/Predator04/Hermes-CoAgent) Windows desktop agent. An assistant (or anything that can speak HTTP) can tap, swipe, type, navigate, launch apps, and *see* the screen — all from plain JSON over HTTP, plus a full find-my-phone / anti-theft toolkit.

There are **two ways to reach the phone**:

| Mode | Network | How it works |
|------|---------|--------------|
| **LAN (direct)** | Same Wi-Fi | The phone hosts an HTTP server on port `8765`; the controller talks straight to `http://<phone-ip>:8765` |
| **Remote Mode** | Anywhere (cellular/NAT) | The phone *dials out* to a [relay server](relay/) that both sides poll — no inbound port on the phone, no VPN |

---

## Install

1. Build the APK (see below) or grab a **release** from the [Releases](https://github.com/Predator04/Hermes-CoAgent-Android/releases) page.
2. Install it on the phone (allow "unknown apps" when prompted).
3. Open **Hermes CoAgent** → tap **"Enable Accessibility"** and toggle the **Hermes CoAgent** service ON. *(Required — taps, swipes, typing, and UI-tree visibility all run through the accessibility service.)*
4. In the **Permissions** section, check the features you want and tap **Grant permissions**. A few special-access permissions (background location, DND, notification access, battery exemption, display-over-other-apps) must be enabled via **Open Settings** — each row shows numbered "How to enable" steps.
5. Tap **"Start Remote Control"**. The status card turns green **"● Running — port 8765"** and shows the phone's IP + the auth token.

> **Tip:** tap **"Connection details"** to reveal the token and device_id (hidden by default so they're not always on screen).

## Authenticate

Every request needs the app's auth token, generated on-device on first launch and shown in the UI:

```
X-Hermes-Token: <token>
```

The token is created with `SecureRandom` and stored in app-private storage — it is never hard-coded.

## LAN mode — example

```bash
TOKEN="<token-from-app>"
PHONE="http://<phone-ip>:8765"

# Health check
curl -s -X POST "$PHONE/cmd" -H "X-Hermes-Token: $TOKEN" \
  -H "Content-Type: application/json" -d '{"action":"ping"}'

# See the screen (UI tree)
curl -s -X POST "$PHONE/cmd" -H "X-Hermes-Token: $TOKEN" \
  -H "Content-Type: application/json" -d '{"action":"dump"}'

# Tap "Settings" by label
curl -s -X POST "$PHONE/cmd" -H "X-Hermes-Token: $TOKEN" \
  -H "Content-Type: application/json" -d '{"action":"find_tap","query":"settings"}'
```

All actions accept `POST /cmd` with a JSON body and return JSON.

## Remote Mode (works over cellular)

Remote Mode lets you drive the phone from anywhere — carrier NAT can't block it because the phone makes **outbound** requests to a relay. Setup is documented in [`relay/README.md`](relay/README.md); the short version:

1. Run the relay (single-file, stdlib-only): `python3 relay/relay.py` → binds `0.0.0.0:8787`.
2. Expose it publicly (ngrok / Cloudflare Tunnel / a $5 VPS / router port-forward).
3. On the phone: **Remote Mode** → paste the relay base URL → flip **Enable Remote Mode** ON.
4. The controller then sends `POST /command` to the relay instead of talking to the phone directly. Use the relay's `device_id` (shown in Connection details) + the same auth token.

---

## Command reference

Every command is `POST /cmd` (LAN) or relay `POST /command` with `{"action": "…", …params}`.

### Core control (accessibility)

| Action | Params | What it does |
|--------|--------|--------------|
| `ping` | — | Health check → `{"ok":true,"pong":true}` |
| `shutdown` | — | Stop the remote-control service (LAN/relay). Responds `{"ok":true,"shutting_down":true}` then tears down ~400 ms later |
| `tap` | `x`, `y` | Tap a screen coordinate |
| `long_press` | `x`, `y`, `duration?` | Press and hold a screen coordinate (context menus, app shortcuts, text selection) |
| `swipe` | `x1`, `y1`, `x2`, `y2`, `duration?` | Swipe between two points |
| `type` | `text` | Type into the focused field |
| `key` | `code` | Global action: `back` `home` `recents` `notifications` `quick_settings` `power` `lock` |
| `launch` | `package` | Launch an app by package name (e.g. `com.android.settings`) |
| `scroll` | `direction` | Scroll the focused scrollable (`up`/`down`) |
| `wait` | `for`, `until`, `timeout_ms?` | Wait until text appears/disappears in the UI |
| `wake` | — | Wake the screen |
| `screen` | `on?`, `brightness?`, `brightness_mode?` | Display state (on, locked, secure) plus wake/sleep and brightness get/set |

### Seeing the screen

| Action | Params | What it does |
|--------|--------|--------------|
| `dump` | — | Dump the UI tree (text, labels, bounds, clickable) — the agent's "eyes" |
| `find` | `query` | Search the UI tree for text/label |
| `find_tap` | `query` | Find a UI element by text and tap it |
| `screenshot` | `scale?`, `quality?`, `max_edge?` | Screen capture → base64 JPEG in the response |
| `foreground` | — | Report the current foreground app (package, activity class, window title) — the agent's cheap "where am I" check |
| `snapshot` | — | Screen snapshot |
| `watch` | `duration?`, `interval?` | Stream scaled screenshots to the relay (`/frame`) for near-live viewing — `duration` 1–60s (default 20), `interval` 0.5–5s (default 1.5) |
| `screen_size` | — | Width/height/dpi — the coordinate space for taps |

### Device info

| Action | Params | What it does |
|--------|--------|--------------|
| `battery` | — | Battery level + charging state |
| `info` | — | Model, manufacturer, Android version, SDK |
| `list_apps` | — | Installed launchable apps |
| `location` | — | GPS position (lat/lng/accuracy) |
| `wifi` | — | Wi-Fi state + SSID |
| `network` | — | Network connectivity: type, internet status, local IPs, Wi-Fi, cellular signal |
| `charging` | — | Charging state |
| `memory` | — | RAM usage: total/available/used MB, used %, low-memory flag, /proc/meminfo breakdown (buffers, cached, swap), and the service process heap |
| `clipboard_get` | — | Read the clipboard *(Android 10+ restricts background reads)* |
| `clipboard_set` | `text` | Write the clipboard |

### Files & apps

| Action | Params | What it does |
|--------|--------|--------------|
| `file_list` | `path?` | List a directory's contents |
| `file_info` | `path` | File/dir metadata (size, type, timestamps) |
| `file_get` | `path` | Read a file → base64 (capped at 8 MB) |
| `file_put` | `path`, `data` (base64), `append?` | Write/append a file |
| `file_delete` | `path` | Delete a file or empty directory |
| `install_apk` | `url` | Download + install an APK (needs "Install unknown apps" granted once) |
| `uninstall` | `package` | Uninstall a package |
| `app_info` | `package` | Version / target SDK / install dates |
| `kill_background` | `package` | Kill a background process (foreground force-stop needs root) |

### Find-my-phone

| Action | Params | What it does |
|--------|--------|--------------|
| `ring` | — | Play a loud ring — bypasses silent/DND |
| `stop_ring` | — | Stop ringing |
| `find_phone` | — | Combined find-my-phone response (location + ring + battery) |
| `flashlight` | `on` | Toggle the flashlight |
| `speak` | `text` | Text-to-speech on the phone |
| `vibrate` | — | Vibrate |

### Anti-theft

| Action | Params | What it does |
|--------|--------|--------------|
| `stolen` | — | **Full theft response**: screenshot of the screen → front + rear camera photos → GPS → Wi-Fi → battery → **locks the screen**. Silent/invisible throughout. |
| `photo` | `camera` (`front`\|`back`) | Silently capture a front/rear photo |
| `mic` | `seconds?` | Record ambient audio |
| `lock` | — | Lock the screen |
| `tracking` | `on` | Toggle GPS breadcrumb logging (5-min polling) |
| `location_history` | — | Return logged breadcrumbs |
| `sim` | — | Current SIM identity |
| `sim_events` | — | SIM-change events (detects a SIM swap) |
| `open_url` | `url` | Open a URL — scheme-allowlisted to `http` `https` `mailto` `tel` `sms` `smsto` `geo` `market` `maps` |

### Notifications & privacy

| Action | Params | What it does |
|--------|--------|--------------|
| `notifications` | — | List active notifications |
| `dismiss_notification` | `key` \| `package` | Dismiss a notification |
| `privacy` | `on` | Privacy mode — blocks `screenshot`/`dump`/`snapshot`/`notifications` |
| `log` | — | Command execution log |

---

## Building from source

Requirements: JDK 17, Android SDK (compileSdk 34). No external dependencies — pure Android SDK + `org.json`.

```bash
./gradlew :app:assembleRelease
# output: app/build/outputs/apk/release/app-release-unsigned.apk
```

The release APK is unsigned by default — sign it with your own keystore before distributing:

```bash
$ANDROID_HOME/build-tools/35.0.0/apksigner sign \
  --ks your.keystore --ks-key-alias youralias \
  --out HermesCoAgent.apk app/build/outputs/apk/release/app-release-unsigned.apk
```

## Security notes

- The HTTP server requires `X-Hermes-Token` on every request; a missing/wrong token returns `401`.
- The token is generated on-device with `SecureRandom` and stored in app-private `SharedPreferences` — never hard-coded, never committed.
- **LAN mode** runs over plain HTTP, so the token is your only transport protection — keep it on a trusted network, or tunnel it (WireGuard / adb reverse).
- **Remote Mode** traffic can be HTTPS end-to-end when the relay sits behind a TLS terminator (ngrok / Cloudflare / nginx+Let's Encrypt).
- The relay (`relay/relay.py`) is stdlib-only, keeps state in memory, and strips tokens from its log lines.

## Project layout

```
app/src/main/
  AndroidManifest.xml
  java/com/hermescoagent/phone/
    MainActivity.java                 # UI: status card, permissions, remote mode, updates
    RemoteControlService.java         # foreground HTTP server (port 8765)
    RemoteRelayClient.java            # dials out to the relay (Remote Mode)
    HermesAccessibilityService.java   # tap/swipe/type/global-actions + UI-tree dump/find + screenshot
    CommandExecutor.java              # the command dispatcher (every action)
    ControlBanner.java                # overlay "being controlled" pill + STOP button
    PhotoCapture.java                 # silent front/back camera (AE-converged)
    AudioCapture.java                 # silent microphone recording
    LocationTracker.java              # GPS breadcrumbs (tracking)
    SimWatcher.java                   # SIM identity + SIM-swap detection
    PermissionPrefs.java              # per-permission opt-in state
    Redaction.java                    # privacy mode
    HermesNotificationListener.java   # read/dismiss notifications
    UpdateChecker.java                # self-update check
  res/xml/accessibility_service_config.xml
  res/xml/file_paths.xml
  res/values/strings.xml
relay/
  relay.py                           # single-file relay (Remote Mode backend)
  README.md                          # relay setup + ngrok/VPS instructions
```

## Requirements

- Android 7.0+ (API 24), target API 34
- No external dependencies — pure Android SDK + `org.json`

## License

MIT — see [LICENSE](LICENSE).
