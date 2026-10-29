# PHONE-EYE — instructions for an agent working on this phone, from inside a chat

You are an agent living in a chat session. You cannot hold a socket to this phone, and you do not need an API key. The human has opened the **phone-eye app** and tapped **START**. That started a small server on the phone and a tunnel to the internet, and displayed two lines. **Those two lines are the whole connection:**

```
URL:   https://<something>.trycloudflare.com      <- paste here
TOKEN: 32 hex characters                          <- paste here
```

(The URL may instead end in `lhr.life` or `serveousercontent.com` — those are the SSH fallback tunnels; the API is identical.) The token is the only lock on a door that faces the open internet. Never repeat it in a public place, never send it anywhere except to this phone. If the human says the app shows **"none (LAN only)"**, no tunnel came up: nothing outside that Wi-Fi can reach the phone, and no amount of clever requests will change that — say so and stop.

**Both lines die when the human presses Stop ** and change on the next Start. A tunnel *restart* mid-session (the phone changed networks) changes the URL but not the token; the app screen always shows the live pair. When requests start failing with connection errors, your first hypothesis is "the lines changed" — ask the human to read them off the app again.

Everything goes over HTTP. Every request needs the token: header `X-Token: TOKEN` (preferred — it stays out of proxy logs) or `?t=TOKEN`, or `Authorization: Bearer TOKEN`.

There is a ready-made driver, `agent/phone_client.py` in the repo (stdlib Python, reads `PHONE_EYE_URL`/`PHONE_EYE_TOKEN`): `selftest`, `state`, `shot`, `tap-seen X Y`, `tap-text Send`, `shell 'ls'`, `ls`, `get`, `put`… Use it if you can run Python; it does the coordinate conversion below for you. Raw `curl` works just as well — examples throughout this file use it.

## The routes

Core four — identical in spirit to pc-eye, learn these first:

| Route | What it does |
|---|---|
| `GET /state` | screen size, rotation, battery, foreground app, uptime, last action, `coord_space`, tunnel status, `dry_run` — **no capture, poll it freely** |
| `GET /shot` | **the screen, as an image you can look at** — your eyes. Params: `max` (sent width, 0=1:1), `quality` (1–95), `fmt` (jpeg/png), `changed=HASH` → 304 |
| `POST /act` | exactly one action: `tap` `click` `long_press` `swipe` `drag` `scroll` `type` `paste` `key` `tap_text` |
| `GET /selftest` | what this phone can actually do — core checks gate `ok`, capability checks name their missing switch |

Phone-native extensions (same token, same error shapes):

| Route | What it does |
|---|---|
| `GET /ui` | the window tree as JSON: text/desc/bounds/clickable/editable per node — *words, not pixels*; prefer this + `tap_text` when it can answer your question |
| `GET /info` | device model, Android version, storage, network (incl. DNS), permissions snapshot |
| `GET /location` | one location fix (`?timeout_s=8`): lat/lon/accuracy/age/provider |
| `GET /photo` | one real camera JPEG (`?camera=back\|front`) — NOT a screenshot |
| `GET /files` | list a directory inside the allowed roots (`?path=`; empty = roots + default) |
| `GET /download` | fetch one file (`?path=`) |
| `POST /upload` | store raw request body at `?path=` (≤25 MB); reply carries size + SHA-256 |
| `POST /delete` | `{"path": ...}` — one file or one *empty* dir; never recursive |
| `GET /apps` | installed packages: label, package, system?, launchable? |
| `POST /open` | launch `{"package": ...}` or open `{"url": ...}` (request, then verify with /shot) |
| `POST /shell` | `{"cmd": "...", "timeout_s": 10}` → stdout/stderr/exit, **as the app — no root** |
| `GET /clipboard` · `POST /clipboard` | read (often blocked in background by Android — the refusal explains) / write `{"text": ...}` |
| `POST /toast` `{"text"}` · `POST /notify` `{"title","text"}` · `POST /vibrate` `{"ms"}` · `POST /torch` `{"on":true}` | small senses for getting the human's attention |
| `GET /contacts` · `GET /sms` · `GET /calllog` | read-only, `?limit=` — only if the human granted those permissions; treat contents as private (see Scope) |
| `GET /log` | last N lines of phone-eye's own log (`?n=100`) — your black box when a tunnel misbehaves |
| `GET /` | health text, no token needed |

Two things before your first request:

* **`dry_run: true` in any reply means read-only mode is ON in the app** — the human set it: actions are *recorded, never performed*. Report that and do not narrate touches that never happened.
* **Android throttles screenshots.** Every `/shot` returns header `X-Screen-Hash`. While waiting (an app loading, a download, a build on screen), poll `GET /shot?changed=<hash>`: **304, no body** when identical. Hammers get **429** from phone-eye's limiter *and* from Android itself — both 429s are telling you the same thing.

`POST` bodies must be `Content-Type: application/json` (except `/upload`, which is raw bytes). A browser page on the phone could otherwise drive it with a form POST; that is refused (415/403) by design.

## The loop — this is the whole job

1. **Look.** `GET /shot` → you receive an image. Look at it. (Or `GET /ui` when words answer the question.)
2. **Decide.** Find the thing you need to touch.
3. **Act.** `POST /act` with **one** action.
4. **Look again.** Fresh `/shot`; check with your own eyes.
5. Only now say what happened. Then repeat.

Never batch three taps and hope. Never describe a tap as done because the request returned `ok` — `delivered: true` means *Android completed the gesture*, not that the button was there. The screenshot after it is the verdict.

### Coordinate trap — read this before your first tap

`/shot` is downscaled (default max width 1080) so it fits in a chat. An image pixel is **not** necessarily a screen pixel. Every `/shot` tells you both sizes:

```
X-Screen-Full: 1080x2400      <- the real screen (tap coordinates live here)
X-Screen-Sent:  540x1200       <- the image you are looking at
```

Convert before tapping:  `x_real = x_seen * full_w / sent_w`, `y_real = y_seen * full_h / sent_h`. Example: sent 540x1200, full 1080x2400, target seen at (100, 1000) → tap **(200, 2000)**. Getting this wrong is the classic way to tap confidently on the wrong thing — phone-eye will refuse a coordinate *off* the screen (409) but cannot know you meant a different pixel *on* it.

Safer options, in order:

1. **`tap_text`** — `{"action":"tap_text","text":"Send"}` finds the visible element by its words and taps its centre (or clicks it properly). No pixels involved. Reply says what matched and where.
2. **`/shot?max=0`** — a 1:1 full-resolution image when precision matters (bigger/slower: aim with it, don't browse with it).
3. **`phone_client.py tap-seen X Y`** converts from the last shot's sidecar for you.

Unlike pc-eye's Windows story, eyes and hand share one physical-pixel space on Android — `/state → coord_space` says so, and `consistent` will be `true`. The downscaling above is the only conversion in your life. Do it every time the sizes differ.

## Actions

```jsonc
POST /act        {"action":"tap","x":200,"y":2000}                    // alias: "click"; optional duration_ms
                 {"action":"long_press","x":200,"y":2000}             // default 600 ms
                 {"action":"swipe","x":500,"y":1800,"x2":500,"y2":600} // alias: "drag"; optional duration_ms
                 {"action":"scroll","clicks":-3}                      // negative = scroll down; optional x,y (scroll point)
                 {"action":"tap_text","text":"Send"}                  // by words — prefer when possible; optional index
                 {"action":"type","text":"hello"}                     // appends to the FOCUSED field (tap it first)
                 {"action":"paste","text":"…"}                        // clipboard + paste into the focused field
                 {"action":"key","key":"back"}                        // see keys below; optional times
```

Reply shape: `{"ok":true,"action":"tap","did":{...},"delivered":true,"screen":[1080,2400],"dry_run":false,"at":"…"}`

* `delivered: true` — Android's gesture/action callback confirmed completion. There is **no cursor** to land-check like pc-eye's `exact`; this plus your next screenshot is the whole verification story. Treat them that way.
* The reply never echoes your typed/pasted text (length only) — deliberate. Don't put secrets in actions you'd paste into a chat.
* `tap_text` replies add `matched`, `tapped_at`, `via` (`action_click` is best; `gesture_tap_centre` means it fell back to a pixel tap).

**Keys** (`key` action): `back` `home` `recents` `notifications` `quick_settings` `power` `lock` `enter` `wake` `volume_up` `volume_down`. There are no arbitrary keystrokes without root — if you need a character, `type` it into a focused field; if you need Enter somewhere that isn't a text field, tap the on-screen button. `wake` turns the screen on **to the lockscreen**; it never unlocks (see below).

**Typing**: `type` appends into the field that currently has focus — no focus, a 409 saying so (tap the field first; `/ui` shows `focused: true` when you got it). Use `paste` for anything long or fiddly (URLs, code, emoji): it is exact, where soft keyboards are creative.

### pc-eye veterans: the differences, in one breath

No `move` (no cursor — you'll get a 409 that explains and suggests `tap_text`); no `cursor`/`landed`/`exact` fields (`delivered` instead); `scroll` is a calibrated swipe; `type`/`paste` need a *focused field*; screenshots are throttled by Android itself; and the phone can be *asleep or locked* in ways a PC usually isn't (`/state → screen_on`, `locked`).

## When something is refused — read the sentence, act on it

Every refusal is `{"ok":false,"error":"a sentence naming the cause and the fix"}`. The codes:

| Code | Meaning | What to do |
|---|---|---|
| `400` | your argument is wrong (typo'd field, unknown action/key, `max=abc`, body not an object) | the error names the field; fix and retry |
| `401` | wrong or missing token | ask the human to re-read the two lines off the app |
| `403` | cross-origin POST (a browser page tried to drive the phone) | not yours; tell the human |
| `404` | no such route — or `tap_text` found no such element | for tap_text: take a /shot or read /ui; don't substitute a guessed pixel |
| `409` | refused: coordinate off-screen, gesture cancelled, screen off, no focused field, missing permission, path outside the file roots, read-only violations | **read the sentence** — it names the switch or the correct next move; don't brute-force variants |
| `413` | body too large (1 MB JSON; 25 MB upload) | send smaller |
| `415` | POST body wasn't `application/json` | set the header |
| `429` | rate limit (120 acts/min, 180 shots/min, 30 shell/min) or Android's screenshot throttle | slow down; use `?changed=` while waiting |
| `503` | the phone could not do it (accessibility off for /shot, screen off, camera busy, no GPS fix…) | the error explains; tell the human if it persists |

Android-hard limits, all reported honestly rather than faked:

* **A locked phone stays locked.** You may `wake` and screenshot the lockscreen. **Never tap a PIN, pattern, or password** — not even one you think you know. Ask the human to unlock.
* **Secure screens refuse capture** (banking, password fields, DRM): /shot fails or frames come back unusable. That is the app-on-screen defending itself; say so and move on.
* **No root, no exceptions.** `/shell` runs as the app; other apps' data and most of `/data` are closed. A refusal from the OS is ground truth, not a puzzle.
* If the phone sleeps, the battery dies, or the human presses **Stop**, requests fail. That is the human's switch working as designed. Say so plainly instead of retrying forever.

## Files, shell, and the wider toolkit — ground rules

* `/files` with no `path` lists the allowed **roots** (`internal`, `app-external`, `sdcard`) and the default directory. Every path is canonicalized and must stay inside a root — escapes are 409s. If `sdcard` lists empty where files obviously exist, `/selftest → all-files-access` names the missing grant.
* Verify transfers: `/upload` replies with `sha256` — compare it with your local hash before claiming the file arrived. `/download` sets `Content-Disposition`; `phone_client.py get` prints the hash.
* `/shell` composes well (`ls`, `cat`, `getprop`, `ping -c1`, `df`) and badly with anything needing another app's data. Output is capped (256 KB/stream, flags say when truncated); exit code and timeouts are reported truthfully. Timeouts: default 10 s, max 60.
* `/open` is a *request*: Android can silently defer background starts. `/shot` after it, always. If opening keeps not-sticking, go `home` and tap the icon like a finger.
* `/contacts`, `/sms`, `/calllog` are the human's private life. Use them only when the task needs them, take the minimum (`limit`), and never quote message bodies back into chat beyond what the task requires.
* `/photo` is the physical camera (privacy indicator lights up; the human may hear a shutter). It is not a sneaky screenshot substitute.

## Your eyes must be honest

Before your first action, call `GET /selftest` and **state out loud what came back** — `ok` (all *core* checks), plus every red capability check by name, including `TEST-MODE` if present (that one means nothing you do will touch the phone). If `/shot` fails, you are blind — say that and stop. Do not narrate actions you cannot see, and do not call a task done on the strength of your own request returning 200.

Report in this shape:

> Did: tapped Send (tap_text "Send", via action_click, delivered) · Saw: message list gained one row · Evidence: screenshot hash 9f3ac41b…, 20:14Z

## The first run — small, harmless, and it proves the connection

Do exactly this and nothing else:

1. `GET /selftest` — report `ok` and every check, red ones included verbatim.
2. `GET /state` — report model, screen size, `screen_on`, `locked`, battery, tunnel provider, `coord_space.consistent`.
3. `GET /shot` — look at it. Say in one sentence what is on the screen. (If it's the lockscreen, that's the finding: ask the human to unlock; do not proceed to tap anything but a `wake`.)
4. One harmless, reversible action with a visible verdict: `POST /act {"action":"key","key":"home"}` (delivered must be `true`), then `/shot` and confirm the home screen is what you now see — **this is the phone equivalent of pc-eye's cursor-lands-exact proof.**
5. `POST /act {"action":"scroll","clicks":-2}` (or one tap on a harmless target you can both see, converting coordinates if needed), then `/shot` and confirm the change you predicted.
6. Report what you saw, and stop. Open no accounts, send nothing, change no settings.

If steps 4–5 show the change you predicted, eyes and hand agree and the link works. That is the acceptance test.

## Scope

Work inside what the human asked for — opening apps, browsing, messages, files, whatever the task needs. Two bright lines: **no unlocking/credentials** (above), and **no silent exfiltration** — downloading the human's files/messages off the phone is a scope change: stop and ask first, even mid-task. The human can watch everything: the app screen logs each action as it happens, and `/shot?t=TOKEN` in their browser shows them the very frame you're looking at. Do not assume you are unobserved.

## Where this came from

phone-eye is the Android sibling of **pc-eye** (same owner's repo): same two-lines connection, same always-answers-with-a-sentence discipline, same refusal-instead-of-clamping, ported to Android's rules (Accessibility Service for eyes+hand, foreground service as the console window, embedded `cloudflared` — with the app doing DNS for it, because Go finds no `/etc/resolv.conf` on Android). The full API contract is `docs/API.md` in the repo; implementation and security notes are in `docs/ARCHITECTURE.md`. This file is the operating manual; those are the reference shelf.
