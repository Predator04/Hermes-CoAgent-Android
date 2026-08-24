# AGENTS.md — guidance for AI agents working in this repo

This is the **Hermes CoAgent Android** app: it turns an Android phone into a remotely-drivable device over HTTP. If you're an AI agent dropped into this repo, here's what you need to know to build, run, and drive it.

## What this repo is

A self-contained Android app (`com.hermescoagent.phone`, package dir `app/`) plus a single-file Python relay (`relay/relay.py`). The app hosts a JSON-over-HTTP command server; an assistant controls the phone by POSTing `{"action": …}` commands.

- **Build:** `./gradlew :app:assembleRelease` → `app/build/outputs/apk/release/app-release-unsigned.apk` (JDK 17, Android SDK 34, no external deps).
- **Sign:** `apksigner sign --ks <keystore> --out app.apk app/build/outputs/apk/release/app-release-unsigned.apk`.
- **Full docs:** [`README.md`](README.md) (commands + install) and [`relay/README.md`](relay/README.md) (Remote Mode protocol).

## Two ways to reach a phone

1. **LAN (direct)** — the phone runs an HTTP server on port **8765**. Controller talks to `http://<phone-ip>:8765/cmd`.
2. **Remote Mode** — the phone dials *out* to the relay (`relay/relay.py`, default `0.0.0.0:8787`); the controller talks to the relay. Works over cellular/NAT. Needed for any phone you can't reach on the same Wi-Fi.

## Auth

Every request carries a shared-secret token in the `X-Hermes-Token` header. The token is generated on-device on first launch and shown in the app UI (Connection details). For LAN mode that's the only credential; for Remote Mode, `device_id` + token are used against the relay.

## The core control loop (how to actually drive the phone)

1. **See** the screen with `dump` (UI tree — instant, gives every element's text + bounds) or `screenshot` (base64 JPEG).
2. **Act** with `tap {x,y}`, `long_press {x,y}`, `swipe`, `type {text}`, `key {code}`, `launch {package}`, `find_tap {query}`.
3. **Verify** with a follow-up `dump`/`screenshot`.

Prefer `dump`/`find`/`find_tap` for navigation (instant, precise); use `screenshot` only to confirm you landed where you expected or to read toggle states.

## Most important commands

| Need | Command |
|------|---------|
| Health | `{"action":"ping"}` |
| See the UI | `{"action":"dump"}` |
| Tap by label | `{"action":"find_tap","query":"settings"}` |
| Tap by coord | `{"action":"tap","x":540,"y":1200}` |
| Type | `{"action":"type","text":"hello"}` |
| Launch app | `{"action":"launch","package":"com.android.settings"}` |
| Back/Home | `{"action":"key","code":"back"}` |
| Screen capture | `{"action":"screenshot"}` |
| Screen size | `{"action":"screen_size"}` |
| Device info | `{"action":"info"}` / `{"action":"battery"}` |
| GPS | `{"action":"location"}` |
| Lost phone | `{"action":"ring"}` / `{"action":"stolen"}` |
| Live view | `{"action":"watch","duration":20,"interval":1.5}` (frames land on relay `/frame`) |

## Working on this repo

- **Pure Android SDK + `org.json`.** Do not add dependencies without a strong reason — the app is deliberately dependency-free.
- **UI is built programmatically** in `MainActivity.java` (no layout XML). Button/card backgrounds are XML drawables under `app/src/main/res/drawable/`.
- **`CommandExecutor.java`** is the single dispatcher — every action routes through its `switch`. Add new commands there.
- **`HermesAccessibilityService.java`** owns input injection (tap/swipe/type/key) and UI-tree/screenshot access.
- **Version bumps** live in `app/build.gradle` (`versionCode` / `versionName`). A new feature = bump both + create a GitHub release with the signed APK.
- **Never commit** signing keys (`*.keystore`, `*.jks`, `keystore.properties` — already in `.gitignore`), tokens, or APK binaries.

## Rules for correctness

- Syntax checks are NOT enough — a runtime crash (e.g. double route registration, bad import) passes a syntax check. Real verification = `assembleRelease` **and** boot/run the app.
- Stealth actions (`stolen`, `photo`, `mic`) must never reveal the control bar or make noise.
- Preserve the existing JSON response shape (`{"ok":…}`) and error handling for any command you touch.
