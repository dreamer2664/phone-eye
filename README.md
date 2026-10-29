# phone-eye

**An eye and a hand on your Android phone, for an agent that lives in a chat.**

`phone-eye` is [pc-eye](https://github.com/dreamer2664/pc-eye)'s younger sibling. Same problem, smaller screen: an agent in a chat can make HTTP requests but cannot hold a socket to your phone. So this is the smallest honest thing that closes the gap — an app you start yourself, which runs a tiny server on the phone, opens a tunnel to the internet, and shows you **two lines**. You paste those two lines (plus one instruction file) to the agent, and it can *look* at your phone's screen and *act* on it. The off switch is a Stop button on a permanent notification. Nothing autostarts, ever.

```
GET  /state      screen, battery, foreground app, uptime, last action   (no capture — poll freely)
GET  /shot       the screen, as an image the agent looks at (+ X-Screen-Hash; ?changed=HASH -> 304)
POST /act        one action: tap / long_press / swipe / scroll / type / paste / key / tap_text
GET  /selftest   what this phone can and cannot do, reported honestly
   + phone extras: /ui /info /location /photo /files /download /upload /shell /apps /open ...
```

Requirements: **Android 11+ (API 30), arm64 phone** (any mainstream phone since ~2017). No root. No account. No API keys.

---

## Run it (3 minutes, first time)

1. **Install the APK.** On the phone, download `phone-eye-v*.apk` from this repo's [GitHub Releases](https://github.com/dreamer2664/phone-eye/releases) and open it. Android will ask you to allow "install unknown apps" for your browser/Downloads app — that's normal for any app installed outside the Play Store; allow it for this install. *(Verify if you like: `SHA256SUMS.txt` is attached to every release.)*
2. **Open phone-eye and do the 4-item checklist** on its screen. It tells you, live and in plain words, what's still missing:
   - **Accessibility — "phone-eye hand" → ON.** This is how Android lets anything screenshot/tap without root. It is *the* eyes + hand switch. (Android may make you wait 10 seconds and re-confirm. It does that to everyone.)
   - **Permissions** — camera, location, media, contacts/SMS/call log, notifications. Grant what you want the agent to use; anything you deny simply makes that one tool refuse, with a sentence explaining itself. Nothing breaks.
   - **All-files access** (optional) — lets the file tools range over shared storage instead of only media + app folders.
   - **Battery → Unrestricted** — or Android will politely freeze phone-eye mid-session and the tunnel dies.
3. **Tap ▶ START.** The app starts the server, opens tunnel, and shows:

   ```
   URL:   https://<something>.trycloudflare.com
   TOKEN: 32 hex characters        <- 128 bits; the token is the ONLY lock on the door
   ```

4. **Tap "Copy URL + TOKEN" and paste them to the agent — together with [`PHONE-EYE.md`](PHONE-EYE.md)**, the instruction file it works from. That's the whole installation ceremony on the agent's side.

Every Start mints a **fresh URL and TOKEN**; Stop kills the old pair forever. The token rides in `?t=` or an `X-Token` header, so it can land in proxy logs and browser history: **paste it only into the chat that owns the session — never into a group channel, a screenshot, or a commit.**

Yours meanwhile: `URL/shot?t=TOKEN` in any browser is the same screen image the agent is looking at, `URL/?t=TOKEN` is the health page, and the app screen shows a **live log of every action the agent takes**. There's also a **Read-only mode** switch: agent can look, never touch. And a **STOP** button right in the notification shade.

### The first test (give the agent exactly this)

> Read PHONE-EYE.md, then do the "first run" in it: /selftest (report every check), /state, one /shot, describe what's on my screen in one sentence, then tap one harmless visible thing, /shot again, and show me what changed. Nothing else.

If that works, everything works. If something's red, `/selftest` already said why, in a sentence with the name of the switch.

---

## What the agent can do

| | |
|---|---|
| **See** | `/shot` screenshots (hash + 304 polling), `/ui` the full window tree (tap things by their *words*, not just pixels), `/photo` a real camera frame |
| **Touch** | tap, long-press, swipe, scroll, global keys (back/home/recents/…), type & paste into the focused field, launch apps / open URLs |
| **Know** | device/battery/network (`/info`, `/state`), GPS (`/location`), installed apps (`/apps`), clipboard |
| **Files** | list/download/upload/delete inside sandboxed roots (`/files`, `/download`, `/upload`, `/delete`) — canonical-path checked, no `../` games |
| **Shell** | `/shell` — a real `sh`, running **as the app** (no root, SELinux sandbox; it says so in every reply) |
| **Say** | toast, notification, vibrate, torch (`/toast`, `/notify`, `/vibrate`, `/torch`) |
| **People** (optional, off unless you grant it) | read contacts / SMS / call log (`/contacts`, `/sms`, `/calllog`) |

Full contract: [`docs/API.md`](docs/API.md). Agent's operating manual: [`PHONE-EYE.md`](PHONE-EYE.md). There's also a stdlib-only Python driver for agents: [`agent/phone_client.py`](agent/phone_client.py) (`selftest`, `state`, `shot`, `tap-seen 700 300`, … — it does the coordinate conversion for them).

## How the tunnel works (30-second version)

The server listens on the phone at :8791 (pc-eye is :8790 — a matched pair). To be reachable from a chat, phone-eye tries, in order:

1. **Cloudflare quick tunnel** → `https://*.trycloudflare.com` — the same `cloudflared` binary pc-eye downloads, baked into the APK. Android twist: `cloudflared` is a Linux Go binary and Go finds no `/etc/resolv.conf` on Android, so the app does the DNS itself (it asks `api.trycloudflare.com` for the tunnel and resolves Cloudflare's edge IPs, then hands `cloudflared` the IP addresses). Details: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).
2. **localhost.run** (pure-Java SSH reverse tunnel) → `https://*.lhr.life`
3. **serveo.net** (SSH) → `https://*.serveousercontent.com`
4. **LAN only**, said out loud in the app — an agent outside your Wi-Fi *cannot* reach the phone, and phone-eye never fakes a URL. It retries every minute, because phones wander back into coverage.

A tunnel that dies mid-session (tower handover etc.) is restarted automatically — with a **new URL**, because free quick tunnels don't do stable names. The app screen always shows the live one; the agent re-asks you for it. Token survives tunnel restarts; only Stop/Start rotates it.

## Safety, stated plainly

- **The token is the only lock, and the door faces the internet.** Anyone with the two lines can view and drive the phone until you press Stop. That is the product; treat the lines like a password of the session.
- The tunnels (Cloudflare / localhost.run / serveo) **terminate TLS** — they can technically see the traffic they relay. Don't do banking through an agent session. (Banking apps mostly refuse screenshots anyway — see limits.)
- Every action is **bounded** (text/tap/scroll caps), **rate-limited** (120 actions/min, 180 shots/min, limiter fails *open*), **logged** on-screen and to `phoneeye.log` (typed text is logged as a length, never as content), and **never hidden**: the notification says "phone-eye is ON" the whole time.
- **No autostart, no boot receiver, START_NOT_STICKY.** If Android kills it, it's off, and it stays off until *you* tap Start again.
- `/act` **refuses** (409) a coordinate off the screen instead of clamping it, refuses to act on a sleeping screen instead of reporting a tap that hit nothing, and reports `delivered` from Android's own gesture verdict. An `ok` that didn't happen is the one bug this family of tools refuses to ship.

## Known limits, stated rather than hidden

- **Locked phone = look, don't unlock.** The agent can screenshot the lockscreen and wake the screen, but entering your PIN/pattern is forbidden in its instructions. Unlock it yourself.
- **Banking/password (FLAG_SECURE) screens** capture black or refuse. Android's design, not a bug.
- **No cursor exists**, so pc-eye's `move` action is a 409 explaining itself; the phone's aiming proof is `delivered=true` + your next screenshot.
- **Typing needs a focused field.** `type`/`paste` write into whatever field has the cursor, or refuse saying so. Tapping the field first is part of the loop.
- **Android throttles accessibility screenshots** — poll with `?changed=HASH` (304s) while waiting, exactly like pc-eye.
- **Clipboard *reading* from the background** is blocked by Android 10+; the refusal says so. Writing works.
- **Shell is the app, not root** — `ls /data` will say Permission denied, correctly.
- **32-bit-only and pre-Android-11 phones are not supported** in this build (clear install failure, not a broken tunnel).

## Documentation map

| File | For whom |
|---|---|
| [`PHONE-EYE.md`](PHONE-EYE.md) | **The agent.** The whole job: connection, loop, coordinate math, refusals, first-run acceptance test. Give it this file + the two lines. |
| [`docs/API.md`](docs/API.md) | Anyone integrating: every route, parameter, bound, and error shape |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | Maintainers: components, the cloudflared-on-Android DNS workaround, security model, how to add a route |
| [`docs/BUILDING.md`](docs/BUILDING.md) | Building the APK yourself, signing, releasing, CI |
| [`docs/TROUBLESHOOTING.md`](docs/TROUBLESHOOTING.md) | When a check is red: tunnels, battery killers, black screenshots, "agent says 409" |
| [`CHANGELOG.md`](CHANGELOG.md) | What changed, append-only |
| [`agent/phone_client.py`](agent/phone_client.py) | Agent-side CLI (stdlib Python) |

## Tests & building

```
./gradlew :app:testDebugUnitTest assembleDebug   # contract tests + debug APK (JDK 17, SDK 35)
python3 tools/check_docs.py                       # docs may not drift from the code (CI gates on this)
python3 agent/phone_client.py --help              # the agent driver
```

CI (GitHub Actions, `.github/workflows/build.yml`) does all of that on every push, uploads APKs as artifacts, and publishes signed releases for `v*` tags. Full story: [`docs/BUILDING.md`](docs/BUILDING.md).

## Relationship to pc-eye

phone-eye is a *port of the contract*, not of the code: two lines to connect, a server that always answers with a sentence, refusals instead of silent clamp/fake-ok, `dry_run` you can see, a log a human can read. Where Android's rules differ from Windows', the difference is printed in the refusal, the selftest, and [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md). If you run both, an agent who knows one already knows the other.

*Tunnel ingredients: cloudflared © Cloudflare (Apache-2.0); the Android DNS workaround follows ashokvarmamatta's guide and Thinai PR #72 — credits in [`NOTICE.md`](NOTICE.md). phone-eye itself: MIT.*
