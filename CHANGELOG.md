# CHANGELOG — phone-eye

Append-only. Correcting an old entry means adding a new one, not editing history.

## 1.0.1 — 2026-10-29

**Live-acceptance fix.** The owner's first on-device run proved the core chain end to end (selftest, screenshots, converted taps, typing/pasting, gestures, Settings navigation, files, shell, location, clipboard rules, toast/notification/vibrate/torch, and a rear-camera photo). It also found one honest mismatch: on the launcher, `/ui` could show widget words that Android's `findAccessibilityNodeInfosByText` would not return to `tap_text`, so `tap_text` correctly refused 404 instead of guessing.

- `tap_text` now falls back, only when Android's search finds no visible match, to a manual walk of the same accessibility tree `/ui` prints, matching `text` or `contentDescription` case-insensitively and requiring non-empty on-screen bounds. The returned `matched` label prefers whichever field actually matched.

## 1.0.0 — 2026-10-29

**First release. pc-eye's contract, ported to Android's rules.**

What's here:

- The core four with pc-eye's discipline intact: `GET /state` (no capture), `GET /shot` (`X-Screen-Full`/`X-Screen-Sent`/`X-Screen-Hash`, `?changed=` → 304), `POST /act` (exactly one action, strict validation, refuses off-screen coordinates instead of clamping, reports Android's own `delivered` verdict), `GET /selftest` (core checks gate `ok`; capability checks name their switch; capture really exercised).
- Phone extensions behind the same token and error shapes: `/ui` (window tree), `/info`, `/location`, `/photo`, `/files` + `/download` + `/upload` + `/delete` (canonical-path sandbox, upload receipts carry SHA-256), `/apps`, `/open`, `/shell` (as the app, says so), `/clipboard`, `/toast`, `/notify`, `/vibrate`, `/torch`, `/contacts`, `/sms`, `/calllog`, `/log`.
- Tunnels in ladder order: Cloudflare quick tunnel (`*.trycloudflare.com`) with the Android DNS workaround (app provisions the tunnel and resolves `--edge` IPs in Kotlin; official `cloudflared-linux-arm64` 2026.10.5 baked in at build time, pinned + SHA-256-verified, run from `nativeLibraryDir`), pure-Java SSH fallbacks (localhost.run, then serveo.net), LAN-only with the failure said out loud and retried. A dropped tunnel restarts with a new URL; the token changes only per Start.
- The human's side: one-screen app (Start/Stop, copyable URL+TOKEN lines, live 4-item setup checklist, read-only/dry-run switch, log of every agent action), foreground-service notification with a STOP action, no autostart anywhere.
- Agent's side: `PHONE-EYE.md` (the operating manual, incl. the coordinate conversion and a first-run acceptance test) and `agent/phone_client.py` (stdlib CLI; `tap-seen` converts from the last shot).
- Build/release: Gradle 8.9 + AGP 8.6.1 + Kotlin 2.0.20, contract unit tests, `tools/check_docs.py` docs gate, GitHub Actions producing named + hashed APKs, signed releases from a dedicated keystore in secrets.

Known limits are stated in README.md (locked phones stay locked, FLAG_SECURE stays black, no cursor so no `move`, clipboard reads are background-blocked by Android, arm64 + Android 11+ only).

**Evidence status at tagging** (the honest part): CI builds the APK and runs the contract tests + docs gate; the SSH fallback banners were verified live against both services at build time; the cloudflared recipe follows the two public, on-device-proven implementations credited in NOTICE.md. What has *not* happened until the owner runs it: a tap landing on the owner's actual glass through the whole chain. PHONE-EYE.md's first run is that proof, and its outcome belongs in the next entry here.
