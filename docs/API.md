# phone-eye API reference

Base URL: the tunnel URL from the app (`https://*.trycloudflare.com`, `https://*.lhr.life`, or `https://*.serveousercontent.com`), or `http://<phone-wifi-ip>:8791` / `http://127.0.0.1:8791` (LAN/USB). Port is always **8791**.

**Auth, every route except `GET /`:** `X-Token: <TOKEN>` header (preferred), or `?t=<TOKEN>`, or `Authorization: Bearer <TOKEN>`. Compared in constant time. Wrong/missing → `401 {"ok":false,"error":"…","hint":…}`-shaped answer (hint text included in the sentence).

**Shapes:** success is JSON with `"ok":true` (+ `version`, `dry_run`, `at` on most). Failure is ALWAYS `{"ok":false,"error":"<a sentence>"}` with one of 400/401/403/404/409/413/415/429/503 (500 only for genuine bugs). **The server always answers** — a connection error means the phone/tunnel is gone, never that your arguments were bad.

**POST rules:** `Content-Type: application/json` required (415 otherwise; `/upload` is the one raw-bytes exception); cross-origin `Origin` refused (403); body ≤ 1 MB JSON (413); strict fields — missing or *unknown* fields are 400s naming the field; booleans are not numbers, fractions are not pixels.

**Rate limits** (token bucket, fail-open): `/act` 120/min (burst 30) · `/shot`+`/photo` 180/min (burst 60) · `/shell`+`/upload` 30/min (burst 10) → `429` + `retry_after_s`.

**Read-only mode** (app switch): state/selftest/act replies carry `"dry_run":true`. Pure-effect mutations return `{"ok":true,"dry_run":true,"note":"recorded, NOT performed",...}`. `/shell` and `/upload` instead **refuse 409** — their replies (stdout, file hash) would be fabrication.

Examples omit the token header for brevity; add `-H "X-Token: $PHONE_EYE_TOKEN"`. `$PHONE_EYE_URL` = the URL line.

---

## GET /

Health text, no auth. Says version, uptime, tunnel provider. `curl $PHONE_EYE_URL/`

## GET /state

Cheap status, **no pixel capture** — poll it freely.

```json
{"ok":true,"version":"1.0.0","dry_run":false,"at":"…Z",
 "device":"Pixel 8","os":"Android 15 (sdk 35)","screen":[1080,2400],"rotation_deg":0,
 "screen_on":true,"locked":false,"accessibility_bound":true,
 "capture_ok":true,"capture_why":null,"foreground_package":"com.example.app",
 "battery":{"known":true,"percent":87,"charging":false,"status":"discharging","temperature_c":31.2},
 "uptime_s":412,"last":{"action":"tap","at":"…","dry_run":false,"args":{"x":200,"y":2000}},
 "coord_space":{"consistent":true,"space":[1080,2400],"detail":"…"},
 "tunnel":{"provider":"cloudflare (trycloudflare.com)","url":"https://…","lan_url":"http://192.168.1.50:8791"}}
```

`capture_ok:false` ⇒ `capture_why` names the fix (accessibility off).

## GET /shot

The screen as an image. Query: `max` sent width px (default 1080; `0` = 1:1; 0–4320), `quality` 1–95 (default 72, JPEG only), `fmt` `jpeg|png`, `changed` previous hash → `304` empty body when identical.

Response headers: `X-Screen-Full: WxH`, `X-Screen-Sent: WxH`, `X-Screen-Hash: 16hex`. Convert tap coordinates: `real = seen * full / sent`.

Refusals: `503` accessibility off / screen off / Android screenshot error (secure screens) · `429` limiter or Android's own screenshot throttle (`ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT`) · `400` bad params.

```
curl -H "X-Token: $T" "$PHONE_EYE_URL/shot?max=0" -o aim.jpg -D -   # 1:1 to aim
curl -H "X-Token: $T" "$PHONE_EYE_URL/shot?changed=9f3ac41b02d4e7a0"  # 304 while waiting
```

## GET /selftest

Exercises capture for real (one small probe frame), then reports:

```json
{"ok":true,"version":"1.0.0","dry_run":false,
 "checks":[{"name":"capture","ok":true,"detail":"320x711 18234B (probe frame)","core":true}, …],
 "note":"ok = every CORE check green…"}
```

Core checks (gate `ok`): `server`, `token`, `tunnel`, `accessibility`, `screen-on`, `unlocked`, `capture`, `coordinate-space`, `log-writable`. Capability checks (`"core":false`): `camera-permission`, `location-permission`, `all-files-access`, `people-permissions`, `battery-unrestricted`, `notifications`, `foreground-service` (+ a `TEST-MODE` row when read-only is on). Every `detail` is a sentence with the fix. **Agents: report all reds out loud, core or not.**

## POST /act

Exactly one action per request. General refusals: `409` coordinate outside `0≤x<W, 0≤y<H` (never clamped), gesture cancelled by Android, screen off, accessibility off. Success: `{"ok":true,"action":…,"did":{…},"delivered":true,"screen":[W,H],"dry_run":false,"at":…}`. Text in `did` is rendered as `"<n> chars"`, never echoed.

| action | required | optional | semantics |
|---|---|---|---|
| `tap` | `x,y` | `duration_ms` 30–2000 (60) | one tap at real pixels |
| `click` | `x,y` | `duration_ms` | alias of `tap` (pc-eye muscle memory) |
| `long_press` | `x,y` | `duration_ms` 200–5000 (600) | press-and-hold |
| `swipe` | `x,y,x2,y2` | `duration_ms` 50–5000 (300) | straight stroke start→end |
| `drag` | `x,y,x2,y2` | `duration_ms` | alias of `swipe` |
| `scroll` | — | `clicks` −100..100 ≠0 (−3), `x,y` together | a calibrated vertical swipe at (x,y) or centre; negative scrolls down. Distance ≈ 9 % of screen height per click, capped 62 %. Reply adds `swiped_to_y` |
| `type` | `text` ≤20 000 chars | — | **appends** to the currently focused editable field via ACTION_SET_TEXT; no focus → 409 (nothing typed). Reply: `typed_chars` |
| `paste` | `text` ≤200 000 | — | sets clipboard, then pastes into focused field; no focus → 409 (clipboard holds text, **nothing pasted** — the sentence says which half happened). Reply: `pasted:true` |
| `key` | `key` | `times` 1–100 (1) | one of: `back` `home` `recents` `notifications` `quick_settings` `power` `lock` `enter` `wake` `volume_up` `volume_down`. `enter` inserts `\n` into the focused field (no raw Enter exists); `wake` turns the screen on to the lockscreen; volume keys ride AudioManager (music stream) |
| `tap_text` | `text` ≤200 | `index` 0–50 (0) | finds the index-th visible node whose text/description matches (findAccessibilityNodeInfosByText semantics: substring, case-insensitive per Android); ACTION_CLICK on it/clickable ancestor if possible, else centre taps. Reply: `matched`, `tapped_at`, `via`. No match → `404` with the match count |

`{"action":"move",…}` is deliberately a **409**: phones have no cursor; the message explains the delivered+screenshot verification story. Unknown actions/keys → 400 with the full list.

## GET /info

`{"ok":true,"info":{manufacturer, model, device, android_release, sdk, abi, screen, rotation_deg, density_dpi, screen_on, locked, battery:{…}, uptime_phone_s, app_version, storage_shared:{total_bytes,free_bytes}, storage_app:{…}, network:{connected,transport,validated,wifi_ssid?,wifi_rssi?,ips[],dns[]}, lan_url, all_files_access, accessibility_bound, foreground_package}}`

## GET /ui

Window tree of the active window. Query: `max_nodes` 1–5000 (1500).

`{"ok":true,"package":"…","count":N,"truncated":bool,"nodes":[{"depth":0,"text":"","desc":"","class":"android.widget.…","id":"com.app:id/send","bounds":[l,t,r,b],"center":[x,y],"clickable":true,"editable":false,"scrollable":false,"focused":false,"enabled":true,"visible":true}]}` — bounds/center are **real screen pixels**, directly tappable. `503` (with the enabling sentence) when accessibility is off or no window is visible.

## GET /location

Query: `timeout_s` 0–30 (8) for one live fix when the last-known is stale (>60 s).

`{"ok":true,"location":{"lat":…,"lon":…,"accuracy_m":…,"altitude_m":…,"speed_mps":…,"bearing_deg":…,"provider":"gps|network|fused","time":"…Z","age_s":…,"live_fix":bool,"coarse_only":bool}}` (optional fields appear only when the fix carries them). Refusals: `409` permission, `503` providers off / no fix within timeout — last-known (with `age_s`) is returned rather than failure whenever one exists.

## GET /photo

Query: `camera=back|front` (back). Returns `image/jpeg` (largest JPEG ≤2048 px wide the camera offers, ~90 quality, EXIF orientation applied via JPEG_ORIENTATION). Headers `X-Photo-Camera`, `X-Photo-Bytes`. Refusals: `409` no CAMERA permission, `503` camera in use / never delivered in 12 s. Android shows its privacy indicator; some regions play a shutter sound — never covert.

## Files

Allowed roots (also returned by `/files`): `internal` = app private files; `app-external` = app's external dir; `sdcard` = shared storage root (needs All-files access or media grants to actually read broadly). Every path is canonicalized; outside ⇒ `409` naming the roots. Relative paths resolve against `sdcard` when readable, else `internal`.

### GET /files?path=
Dir → `{"listing":{"path":…,"type":"dir","entries":[{"name","type":"dir|file","size_bytes","modified"}], "count":N,"truncated":bool},"roots":[…]}` (≤1000 entries, dirs first). File → `{"type":"file","size_bytes","modified"}`. Missing ⇒ 404; unreadable shared dir ⇒ 503 naming the permission.

### GET /download?path=
Raw bytes, guessed content-type, `Content-Disposition: attachment`. Directory ⇒ 409. (No Range support; resumable transfer = re-download.)

### POST /upload?path=
Raw body = file content, ≤25 MB (413). Parent dirs are created *inside the root*. Written via `name.part-<n>` then renamed, so a dropped connection doesn't leave a plausible half-file at the destination. Reply: `{"path","size_bytes","sha256"}` — **verify the hash**. Read-only mode ⇒ 409 (never a fake receipt).

### POST /delete
`{"path"}` → `{"deleted","type"}`. Non-empty directory ⇒ 409 (no recursive delete exists in this API, on purpose).

## GET /apps

`{"apps":[{"label","package","system":bool,"launchable":bool}] (sorted by label, ≤2000), "count"}`

## POST /open

Exactly one of `{"package":"com.example.app"}` / `{"url":"https://…"}` (both/neither ⇒ 400). Unknown/unlaunchable package ⇒ 404. Reply `{"open":{"requested":true,…,"note":"start requested, not proven — verify with /shot"}}` — Android may defer background starts; the note is literal truth.

## POST /shell

`{"cmd":"ls -la; df -h", "timeout_s":10}` (cmd ≤20 000 chars; timeout 1–60, default 10).

`{"shell":{"exit":0|null,"timed_out":bool,"stdout":"…","stderr":"…","stdout_truncated":bool,"stderr_truncated":bool,"runs_as":"the phone-eye app itself…"}}` — runs `sh -c` as the **app UID** in the app's files dir (`HOME`/`TMPDIR` set accordingly), streams capped at 256 KB each, process killed at timeout. The command (first 120 chars) is written to the on-phone log for the human; output never is. Read-only ⇒ 409.

## Clipboard

`GET /clipboard` → `{"clipboard":{"text","chars"}}`, or `503` explaining Android 10+'s background-read block vs. an actually-empty clipboard. `POST /clipboard {"text":≤200000}` → `{"set":true,"chars"}` (sets only; pasting is `/act paste`).

## Small senses

* `POST /toast {"text":≤500}` → `{"shown":true}` (LONG toast)
* `POST /notify {"text":≤2000, "title"?:≤100}` → `{"posted":true}` (channel "Agent messages"; needs notification permission to be seen — /selftest reports it)
* `POST /vibrate {"ms":1..5000}` → `{"vibrated_ms":…}`
* `POST /torch {"on":bool}` → `{"torch":{"on":bool,"camera_id":"…"}}`; `503` when no flash unit exists

## People (opt-in permissions, read-only)

Query all: `limit` 1–500 (50). Missing permission ⇒ 409 naming the grant.

* `GET /contacts` → `contacts:[{"name","number"}]` (phone-number rows, name-sorted)
* `GET /sms` → `sms:[{"address","body","date_ms","type","read"}]` newest-first (Telephony types: 1=inbox, 2=sent…)
* `GET /calllog` → `calls:[{"number","name","date_ms","duration_s","type"}]` newest-first (CallLog types: 1=incoming, 2=outgoing, 3=missed…)

## GET /log

`?n=` 1–400 (100) → `{"lines":["2026-…Z act tap x=… -> ok", …], "log_file":"…/phoneeye.log"}`. Lines never contain the session token (scrubbed at write time) nor typed/pasted text (lengths only) nor shell output/file contents.

---

### Versioning note

All JSON replies carry `"version":"<semver>"`. New fields may appear within a major version; documented fields don't change meaning. The route/action lists in `Server.kt` are the source of truth and CI (`tools/check_docs.py`) fails if this file or PHONE-EYE.md stops mentioning any of them.
