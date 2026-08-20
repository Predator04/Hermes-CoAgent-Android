# Hermes CoAgent — Android

Turn an Android phone into a remotely-drivable device — the Android companion to the [Hermes CoAgent](https://github.com/Predator04/Hermes-CoAgent) Windows desktop agent. An assistant (or anything that can speak HTTP) can tap, swipe, type, navigate, launch apps, and *see* the on-screen UI — all from plain JSON over HTTP.

## What it can do

| Action | Description |
|--------|-------------|
| `ping` | Health check |
| `tap` | Tap a screen coordinate |
| `swipe` | Swipe between two coordinates |
| `type` | Type text into the focused field |
| `key` | Global actions: `back`, `home`, `recents`, `notifications`, `quick_settings`, `power` |
| `launch` | Launch an app by package name |
| `battery` | Battery level |
| `info` | Device info (model, manufacturer, Android version, SDK) |
| `screen_size` | Screen width/height/Dpi — the coordinate space for taps |
| `list_apps` | Installed launchable apps |
| `dump` | Dump the on-screen UI tree (text, labels, bounds, clickable) — the "eyes" |
| `find` | Search the UI tree for a text/label |
| `find_tap` | Find a UI element by text/label and tap it |

## How it works

The app runs a tiny foreground service hosting an HTTP JSON server on port **8765**. Every request is authenticated with a shared-secret token (`X-Hermes-Token` header) generated on first launch and shown in the app UI.

Input injection (tap/swipe/type/global-actions) is done through an Android **AccessibilityService**; UI-tree visibility is done through the same service's `getRootInActiveWindow()`.

## Install

1. Build the APK (see below) or grab a release.
2. Install it on the phone (allow "unknown apps").
3. Open **Hermes CoAgent** → tap **"Enable Accessibility"** and toggle the service on.
4. Tap **"Start Remote Control"**.
5. The app shows the phone's IP and an **auth token** — give both to your assistant.

## Using it (example)

```bash
TOKEN="<token-from-app>"
PHONE="http://<phone-ip>:8765"

# See the screen (UI tree)
curl -s -X POST "$PHONE/cmd" \
  -H "X-Hermes-Token: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"action":"dump"}'

# Tap the Settings app by label
curl -s -X POST "$PHONE/cmd" \
  -H "X-Hermes-Token: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"action":"find_tap","query":"settings"}'

# Raw tap at x=540, y=1200
curl -s -X POST "$PHONE/cmd" \
  -H "X-Hermes-Token: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"action":"tap","x":540,"y":1200}'
```

All actions accept `POST /cmd` with a JSON body and return JSON.

## Building from source

Requirements: JDK 17, Android SDK (compileSdk 34).

```bash
./gradlew :app:assembleRelease
# output: app/build/outputs/apk/release/app-release-unsigned.apk
```

The release APK is unsigned by default — sign it with your own keystore before distributing:

```bash
$ANDROID_HOME/build-tools/34.0.0/apksigner sign \
  --ks your.keystore --ks-key-alias youralias \
  --out HermesCoAgent.apk app/build/outputs/apk/release/app-release-unsigned.apk
```

## Security notes

- The HTTP server requires the `X-Hermes-Token` header on every request; missing/wrong token returns `401`.
- The token is generated on-device with `SecureRandom` and stored in app-private `SharedPreferences` — it is never hard-coded.
- Commands run over **plain HTTP on your LAN**, so the token is your only transport protection. Only expose this on a trusted network, or tunnel it (e.g. WireGuard / adb reverse).

## Project layout

```
app/
  src/main/
    AndroidManifest.xml
    java/com/hermescoagent/phone/
      MainActivity.java                 # UI: enable accessibility, start server, show IP + token
      RemoteControlService.java         # foreground HTTP server (port 8765)
      HermesAccessibilityService.java   # tap/swipe/type/global-actions + UI-tree dump/find
    res/xml/accessibility_service_config.xml
    res/values/strings.xml
```

## Requirements

- Android 7.0+ (API 24), target API 34
- No external dependencies — pure Android SDK + `org.json`

## License

MIT — see [LICENSE](LICENSE).
