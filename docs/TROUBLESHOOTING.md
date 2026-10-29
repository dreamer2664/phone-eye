# Troubleshooting

**First resort, always:** `GET /selftest` (agent) or the checklist on the app screen (human). phone-eye's whole personality is "name the switch" — most entries below are just expansions of a red check's sentence. Second resort: `GET /log?n=200` — tunnel funerals are all recorded there.

## "The agent can't connect at all"

- **App shows `none (LAN only)`** → no public tunnel exists; outside-the-Wi-Fi agents cannot connect, period. Read the line under "Tunnel:" (it lists why each provider failed) and `/log`. Common causes: captive-portal Wi-Fi (sign in via browser first), a network blocking outbound 7844/SSH/HTTPS, DNS filtering. Try mobile data or another Wi-Fi; phone-eye retries every minute on its own.
- **URL looks right, requests hang/timeout** → the session was probably Stopped/Started (new URL+TOKEN) or the tunnel restarted after a network change (new URL, same token). Re-copy the two lines from the app, *this minute's* pair. Old lines never revive.
- **`401`** → token typo/truncated paste, or lines from a previous session. Re-copy; check for a missing last character (32 hex chars exactly).
- **Agent on same Wi-Fi?** The `LAN: http://192.168…:8791` line works without any tunnel. USB debugging route also exists: `adb reverse tcp:8791 tcp:8791` + `http://127.0.0.1:8791`.

## Accessibility (the eyes + hand)

- **`accessibility` red / `/shot` 503 / `/act` 409 with the enabling sentence** → Settings → Accessibility → Installed apps → **phone-eye hand** → ON → Allow. Android 13+ may first say "restricted setting" for sideloaded apps: Settings → Apps → phone-eye → ⋮ → *Allow restricted settings*, then retry. Some ROMs hide it under *Installed services*/*Downloaded apps*.
- **It was ON and turned itself OFF** → classic OEM battery-manager behaviour (and it recurs after some system updates). Set Battery → Unrestricted (checklist item 4), lock the app in Recents if your ROM offers it, and see your brand at dontkillmyapp.com. Re-enable in Settings; no reinstall needed.
- **`capture` red with NO_ACCESSIBILITY_ACCESS** → toggle phone-eye hand OFF → ON. Android occasionally revokes the screenshot capability after updates.
- **Gestures keep returning "cancelled"** → a real finger (or a screen protector edge, a palm) is touching during dispatch, or another overlay is eating touches. Hands off the glass during agent runs; the retry-once advice in the refusal is literal.

## Screenshots

- **Black frame or screenshot refusal on one specific app** → that app set FLAG_SECURE (banking, password managers, some video/DRM). Working as designed; there's no override short of root, and phone-eye doesn't do root.
- **`429 … screenshots throttled`** → slow down; poll `/shot?changed=<last hash>` while waiting. Both phone-eye (180/min) and Android throttle independently.
- **Shot shows the lockscreen** → true and useful: the phone is locked. Human unlocks; agents never enter credentials (PHONE-EYE.md is blunt about this).
- **Colours look shifted / HDR apps look odd** → accessibility frames are the composited display; HDR tone-mapping quirks on some devices are cosmetic for UI work. For ground truth of pixels use `/shot?max=0&fmt=png` sparingly (huge).

## Actions land in the wrong place

- **Off by a constant factor** → you tapped in *sent* pixels. Convert with `X-Screen-Full`/`X-Screen-Sent`, shoot `?max=0` to aim, or use `tap_text`/`phone_client.py tap-seen`. (Agents: this is PC-EYE.md's sermon, phone edition — reread PHONE-EYE.md § Coordinate trap.)
- **Off after rotating / foldables / multi-window** → re-`/state` (rotation_deg, screen) and re-`/shot`; geometry from before a rotation is stale evidence.
- **`type`/`paste` 409 "no editable field is focused"** → tap the field first and confirm the cursor in a screenshot (or `focused:true` in `/ui`). Some custom editors (games, some chat apps' rich inputs) don't expose set-text; fall back to `paste`, or tap the keyboard like a human — slowly, verifying with shots.
- **`/open` says requested but nothing opened** → background-start restrictions on that ROM. Ensure the phone is unlocked, go `home`, tap the icon via `tap_text`/pixels. The reply's note told you to verify for exactly this reason.

## Files & shell

- **`sdcard` lists but files are missing/empty reads** → grant **All-files access** (checklist item 3) or at least media permissions. Scoped storage shows non-media files as absent, which looks exactly like this.
- **Upload lands, app can't see it** → media files written outside media dirs may need a media-scan before gallery apps notice; the file *is* there (`/files` proves it; compare the reply's `sha256`).
- **Shell `Permission denied` everywhere interesting** → correct: it runs as the app. Its home is the app's files dir; `getprop`, `df`, `ping`, `ls` of allowed roots work.
- **Path 409 "outside phone-eye's allowed roots"** → work inside `internal`, `app-external`, or `sdcard` (absolute or relative to sdcard). The refusal lists them.

## Performance & battery

- A session holds a partial wake lock + an outbound tunnel: expect real battery draw. That's the cost of the phone being *reachable*; Stop when done.
- `/shot?fmt=png&max=0` is the deliberate tank: multi-MB. Default JPEG at `max=1080` is the browsing mode.
- Tunnel over cellular + aggressive carrier NAT can flap; the supervisor's reconnect (new URL) is the visible symptom. Wi-Fi is kinder; either way the app screen shows the live URL.

## Building/installing

- **`INSTALL_FAILED_NO_MATCHING_ABIS`** → 32-bit-only device; v1 ships arm64 (cloudflared). The phone is also likely pre-Android-11 in spirit if not in fact — check `sdk` via any device-info app.
- **`App not installed` / signature conflict on upgrade** → the new APK was signed with a different key than the installed one (debug vs release, or a fork's key). Uninstall, reinstall, redo grants — or always install official release assets, which share one key.
- **Play Protect warning on install** → standard for sideloaded apps requesting powerful permissions; review and choose *Install anyway*. You can audit every line in this repo — that's the point of it being here.
- **16 KB page-size warning mentioning `libcloudflared.so`** (Android 15 devices with 16 KB pages) → upstream cloudflared ELF alignment; the app installs and non-tunnel features work, the binary may refuse to exec on strict 16 KB-only kernels. SSH fallback tunnels are unaffected. Watch the repo for an aligned build.
- **Gradle: checksum mismatch for cloudflared** → don't bypass it. Either your network served you junk, or Cloudflare re-published the asset; update the pin (BUILDING.md) after eyeballing the release page.

## When in doubt, collect this before asking for help

`GET /selftest` output + `GET /state` + last 100 `GET /log` lines + phone model/Android version. Never paste a live TOKEN anywhere public — redact it; a dead session's token is harmless but the habit isn't.
