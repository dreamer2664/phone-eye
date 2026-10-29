# phone-eye architecture

For maintainers and the curious. Humans: README.md. Agents: PHONE-EYE.md. Wire contract: docs/API.md.

## The one-sentence version

An Android app runs an HTTP server (NanoHTTPD, :8791) inside a user-started foreground service; an Accessibility Service supplies screenshots and gestures; a tunnel (Cloudflare quick tunnel first, SSH fallbacks second) makes the server reachable from a chat; a per-session 128-bit token is the only lock; the human's Stop button is the only off switch that matters.

```
                        THE PHONE
 ┌──────────────────────────────────────────────────────────────┐
 │ MainActivity (the human's screen)                            │
 │   START/STOP · URL+TOKEN · setup checklist · live log       │
 │        │ startForegroundService            ▲ 1 Hz poll       │
 │        ▼                                   │                 │
 │ PhoneEyeService (foreground service = "the console window")  │
 │   ├─ PhoneEyeServer (NanoHTTPD :8791)  ──routes──► Device.kt │
 │   │        │                                  PhotoGrabber │
 │   │        └── eyes/hands ──► PhoneAccessibilityService      │
 │   │                 (takeScreenshot, dispatchGesture, /ui)   │
 │   ├─ TunnelManager                                           │
 │   │    1 CloudflareQuickTunnel (cloudflared child process)   │
 │   │    2 SshReverseTunnel → localhost.run                    │
 │   │    3 SshReverseTunnel → serveo.net                       │
 │   │    4 LAN only (said out loud, retried every 60 s)        │
 │   └─ WakeLock (partial) · token (fresh per Start)            │
 └───────────────┬──────────────────────────────────────────────┘
                 │ outbound only (tunnel)         pc-eye is :8790
        ┌────────▼─────────┐   trycloudflare / lhr.life / serveo
        │  tunnel provider │   (terminates TLS — see Security)
        └────────┬─────────┘
                 │ HTTPS + X-Token
        ┌────────▼─────────┐
        │  agent in a chat │  curl, or agent/phone_client.py
        └──────────────────┘
```

Everything runs in **one process**. `EyeState` (volatile fields + listeners) is how the service, server and activity share truth; `EyeLog` is the ring-buffer + file log. No IPC, no database, no analytics, no third-party SDK beyond the server/SSH/crypto libraries in NOTICE.md.

## Source map

| File | Owns |
|---|---|
| `Core.kt` | Pure JVM rules, unit-tested: token mint/compare, coordinate conversion, content hash, `RateBucket`, `PathSandbox`, `RunToken`, `Refusal` |
| `Server.kt` | Routing, auth, strict validation, rate limits, `/state` `/shot` `/act` `/selftest` + extensions. `ROUTES`/`ACTIONS` here are the docs' source of truth |
| `PhoneAccessibilityService.kt` | Screenshots (hardware-buffer → software copy → scale/compress), gestures with delivered/cancelled verdicts, focused-field text, `tap_text`, `/ui` tree walk |
| `Device.kt` | Info/battery/network, location, shell-as-app, sandboxed files, apps/open, clipboard, toast/notify/vibrate/torch, contacts/SMS/call-log |
| `PhotoGrabber.kt` | Camera2 one-shot JPEG against a dummy preview surface |
| `QuickTunnel.kt` | Cloudflare provisioning from Kotlin + the `cloudflared` child process |
| `SshTunnel.kt` | sshj reverse tunnels + banner URL parsing for localhost.run / serveo.net |
| `TunnelManager.kt` | Provider ordering, supervision, restart-with-new-URL, LAN-only honesty |
| `PhoneEyeService.kt` | Foreground service lifecycle, notification + STOP action, wake lock, per-Start token |
| `MainActivity.kt` | The human UI (framework widgets only — zero AndroidX by choice) |
| `EyeLog.kt` | Redacting logger + `EyeState` |

## The cloudflared-on-Android problem (read this before "just run the binary")

pc-eye's launcher downloads `cloudflared.exe` and runs `tunnel --url http://127.0.0.1:8790`. Three Android facts break the naive port:

1. **Exec policy (W^X).** Since Android 10 an app cannot `exec` files from its writable data dir. The one exec-able location is `applicationInfo.nativeLibraryDir` — where `jniLibs/*.so` are extracted at install. Hence the old trick: ship cloudflared as `app/src/main/jniLibs/arm64-v8a/libcloudflared.so` with `useLegacyPackaging = true` so it lands on disk as a real file, then `ProcessBuilder(File(nativeLibraryDir, "libcloudflared.so"), …)`.
2. **DNS.** cloudflared is a static Go binary; Go's pure resolver reads `/etc/resolv.conf`, which doesn't exist on Android, and the cgo resolver isn't linked. Every lookup cloudflared attempts itself fails (`lookup … on [::1]:53: connection refused`). The fix (ashokvarmamatta's guide; Thinai PR #72): **the app does both lookups in Kotlin**, where DNS works:
   - `POST https://api.trycloudflare.com/tunnel` (header `User-Agent: cloudflared/phone-eye`, empty body) → `result.{hostname,id,account_tag,secret}`;
   - `InetAddress.getAllByName("region1.v2.argotunnel.com")` (+ `region2…`), first IPv4 each;
   - run token = `base64({"a":account_tag,"t":id,"s":secret})` in env `TUNNEL_TOKEN` (never argv — argv is world-readable via `ps`);
   - launch: `libcloudflared.so tunnel --no-autoupdate --edge <ip1>:7844 --edge <ip2>:7844 --protocol http2 run --url http://127.0.0.1:8791`, plus `HOME`=filesDir, `TMPDIR`=cacheDir, `SSL_CERT_DIR=/system/etc/security/cacerts` (Go's x509 roots don't live where Go expects on Android; matters for cloudflared's own HTTPS calls, harmless otherwise);
   - success = a stderr line containing `Registered tunnel connection`; the public URL is `https://<hostname>` from step 1 — cloudflared's own banner is never parsed for it.
3. **Binary provenance/size.** The binary is *never committed* (37 MB). The Gradle task `fetchCloudflared` (and `scripts/fetch-cloudflared.sh` for offline work) downloads a **pinned** release asset, verifies **SHA-256**, and strips Go debug info via the NDK's `llvm-strip` when available (≈ halves the APK growth). `-PnoCloudflared` builds without it; the app then reports "cloudflared is not inside this APK" as a tunnel refusal and falls back. **ABI**: arm64-v8a only (`abiFilters`), so 32-bit phones fail installation loudly instead of tunnelling silently never. Note for the future: Android 15's 16 KB page-size devices may warn about ELF alignment of the upstream binary — harmless on 4 KB devices; see TROUBLESHOOTING.

Failed avenues worth not retrying (from the guide, so the next person doesn't): creating `/etc/resolv.conf` (read-only), `RES_CONF` (not a Go thing), `GODEBUG=netdns=cgo` (no cgo in the static build), binding a DNS relay on :53 (EACCES unprivileged), `HTTPS_PROXY` (cloudflared uses a custom transport that ignores it). Compiling cloudflared with `GOOS=android` would fix DNS natively at the cost of building Go in CI — a legitimate future upgrade if the `--edge` dance ever breaks.

## SSH fallbacks

`ssh -R 80:localhost:8791` services need no binary and no Go: sshj (pure Java) connects, authenticates, opens a session shell **first** (the URL banner is printed to the session; a late reader misses it), then binds `RemotePortForwarder.Forward(80)` → `SocketForwardingConnectListener(127.0.0.1:8791)`, and scans ANSI-stripped lines for the provider's URL shape:

- localhost.run — user `nokey`, `https://[a-z0-9]+.lhr.life` (first banner line: `"<hex>.lhr.life tunneled with tls termination, https://…"`; also emits an ANSI QR code, ignored). `admin.lhr.life` is blocklisted.
- serveo.net — any username, `https://[a-z0-9-]+.(serveousercontent.com|serveo.net)` (`"Forwarding HTTP traffic from …"`). `console.serveo.net` blocklisted. Serveo may show browsers a one-click warning page; API clients don't see it. Noted wherever a serveo URL is surfaced.

Auth strategies are tried in order with fresh connections — `none`, ephemeral-RSA `publickey`, empty `password` — because these services each accept a different subset. Android's built-in "BC" provider is a cut-down Bouncy Castle; `SshReverseTunnel.ensureBouncyCastle()` swaps the full `bcprov` to position 1 before any `SSHClient` exists. Core-library desugaring is enabled for sshj/BC's newer `java.*` calls.

Supervision: `TunnelManager` polls liveness every 3 s; a dropped tunnel restarts the whole provider ladder after 10 s, up to 12 restarts, then stays LAN-only with the reason on screen. A restart means a **new URL** — the token does *not* change (it is minted at Start, not per tunnel).

## Security model

**Trust boundaries.** (1) Anyone holding URL+TOKEN *is* the agent — there is no second factor; the token is 128 bits of SecureRandom precisely so that guessing is not a strategy. (2) Tunnel operators terminate TLS and can read relayed traffic, so the token (in `X-Token`, or worse `?t=`) and everything else transits providers the owner didn't choose as confidants. Prefer `X-Token`, treat the pair as session credentials, and don't do high-sensitivity work through it. This is inherited from pc-eye's design and stated there too. (3) The LAN listener binds all interfaces so `http://<wifi-ip>:8791` works without a tunnel — token still required, but anyone on the LAN can *attempt* the token. On hostile networks, prefer tunnels + Stop promptly. (4) The app sandbox is the last wall: shell/files run as the app UID; there is no root path anywhere in the design.

**Web-page drive-by.** Every POST requires `application/json` (415) and refuses cross-site `Origin` (403): a page open in the phone's browser cannot form-POST its way into driving the phone just because a tunnel URL leaked into its history.

**Runaway agents.** Rate buckets (fail-open), hard caps on every magnitude (chars, times, ms, bytes, nodes, entries), one action per request, serialized input (`inputLock`), gesture verdicts from the OS. `/act` refuses out-of-display coordinates — clamping is how a wrong tap learns to report success (pc-eye's founding bug).

**The log is a safety feature.** Every action lands timestamped in the UI + `phoneeye.log` (app-private). Never logged: the token (scrubbed even if a caller slips it in), typed/pasted text (lengths only), file contents, shell *output* (the command's first 120 chars *are* logged — the human deserves to see what ran). `GET /log` exposes the same redacted lines to the agent for tunnel forensics.

**Lifecycle.** No `RECEIVE_BOOT_COMPLETED`, no autostart, `START_NOT_STICKY`, ongoing notification with STOP. Revoking the accessibility service is an independent second off switch (eyes/hands die; info routes live) — documented as such in the Settings description string the human reads while granting it.

**Read-only mode** (`dry_run`) validates then records without performing for effect-only calls, and *refuses* (409) the two calls whose receipts would fabricate evidence (`/shell` stdout, `/upload` sha256).

**Signing.** Releases are signed in CI from a dedicated PKCS12 keystore held in Actions secrets (owner keeps the backup — see BUILDING.md). Without the secrets, release falls back to the debug key (forks still build; upgrades across keys will need an uninstall, and the workflow log says so).

## Deliberate non-goals (v1)

No streaming video of the screen (poll `/shot?changed=` — agents decide at screenshot speed, not 30 fps); no mTLS/pairing ceremony (the two-lines ritual *is* the pairing); no Play Store (QUERY_ALL_PACKAGES, MANAGE_EXTERNAL_STORAGE, SMS/call-log permissions are sideload freedoms — Play policy would gut the tool); no mic recording, no sending SMS/calls, no credential entry — feature requests that change the risk shape need a threat-model paragraph in the PR, not just code.

## Adding a route (the checklist CI enforces)

1. Implement in `Server.kt`; add to `ROUTES` (and `POST_ROUTES` if POST); add `/act` actions to `ACTIONS` with required/optional fields.
2. Validate at the door with `validateFields` + `bodyInt/bodyStr/…`; refuse with a sentence that names the fix; bound every magnitude; log the effect (no text content); honour `dry_run`.
3. Document in **PHONE-EYE.md** and **docs/API.md** (same commit — `tools/check_docs.py` fails otherwise), add a CHANGELOG line, and a unit test if any logic is pure enough to live in `Core.kt` (prefer it to live there).
4. On-device: run PHONE-EYE.md's first-run ritual. Green CI proves it compiles; only glass proves it taps.
