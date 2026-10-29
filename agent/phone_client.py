#!/usr/bin/env python3
"""phone-eye agent client — drive the phone from any sandbox/chat with plain Python.

stdlib only (urllib), because the place this runs may have nothing else. The agent
this is written for has already read PHONE-EYE.md; this client exists so the
look-decide-act-look loop is commands, not hand-typed curl with a token in it.

Setup (the token is a secret — env vars, never commit them):
    export PHONE_EYE_URL="https://<something>.trycloudflare.com"
    export PHONE_EYE_TOKEN="<32 hex from the phone-eye app>"

Everyday loop:
    python3 phone_client.py selftest
    python3 phone_client.py state
    python3 phone_client.py shot            # saves shot.jpg + shot.json, prints sizes+hash
    python3 phone_client.py tap-seen 700 300  # coords AS SEEN in shot.jpg (converted for you)
    python3 phone_client.py shot            # look again. ok != done; the image is the verdict.

Raw escape hatches: `get /ui`, `post /shell '{"cmd":"ls"}'`, `act '{"action":"key","key":"home"}'`.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import urllib.error
import urllib.request


def die(msg: str) -> "SystemExit":
    print(f"phone_client: {msg}", file=sys.stderr)
    raise SystemExit(2)


class Phone:
    def __init__(self, url: str, token: str, timeout: float = 30.0) -> None:
        self.url = url.rstrip("/")
        self.token = token
        self.timeout = timeout

    def request(self, method: str, path: str, body: bytes | None = None,
                content_type: str | None = None, raw_query_token: bool = False):
        """Returns (status, headers dict-lowercased, bytes). Never raises for HTTP status:
        phone-eye's refusals ARE answers (print the sentence, act on it)."""
        url = f"{self.url}{path}"
        if raw_query_token:
            sep = "&" if "?" in url else "?"
            url = f"{url}{sep}t={self.token}"
        req = urllib.request.Request(url, data=body, method=method)
        # Prefer the header: query-string tokens land in proxy logs and histories.
        if not raw_query_token:
            req.add_header("X-Token", self.token)
        if content_type:
            req.add_header("Content-Type", content_type)
        req.add_header("User-Agent", "phone-eye-client/1.0")
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as r:
                headers = {k.lower(): v for k, v in r.headers.items()}
                return r.status, headers, r.read()
        except urllib.error.HTTPError as e:
            headers = {k.lower(): v for k, v in e.headers.items()} if e.headers else {}
            return e.code, headers, e.read()
        except OSError as e:
            die(f"cannot reach the phone at {self.url} ({e}). Is phone-eye running, is the "
                f"tunnel up, and is this URL from the CURRENT session (URLs die on Stop)?")

    def get_json(self, path: str):
        status, _, data = self.request("GET", path)
        return status, _as_json(data)

    def post_json(self, path: str, obj) -> tuple[int, object]:
        body = json.dumps(obj).encode("utf-8")
        status, _, data = self.request("POST", path, body=body, content_type="application/json")
        return status, _as_json(data)

    def act(self, obj) -> tuple[int, object]:
        return self.post_json("/act", obj)


def _as_json(data: bytes):
    try:
        return json.loads(data.decode("utf-8"))
    except Exception:
        return {"_raw": data.decode("utf-8", errors="replace")[:2000]}


def show(status: int, obj) -> int:
    print(f"HTTP {status}")
    print(json.dumps(obj, indent=2, ensure_ascii=False, sort_keys=True)[:8000])
    if isinstance(obj, dict) and obj.get("ok") is False:
        print("\n^ phone-eye REFUSED, with its reason above. Read the sentence; "
              "do not retry blindly (PHONE-EYE.md, refusals table).", file=sys.stderr)
        return 1
    return 0


def parse_size(header: str | None) -> list[int]:
    if not header or "x" not in header:
        die(f"missing X-Screen-* size header (got {header!r}) — is this really phone-eye /shot?")
    w, h = header.lower().split("x", 1)
    return [int(w), int(h)]


def convert(seen: int, sent: int, full: int) -> int:
    """real = seen * full / sent — the one formula (see PHONE-EYE.md)."""
    return round(seen * full / sent)


def cmd_shot(ph: Phone, a) -> int:
    path = "/shot"
    params = []
    if a.max is not None:
        params.append(f"max={a.max}")
    if a.quality is not None:
        params.append(f"quality={a.quality}")
    if a.changed:
        params.append(f"changed={a.changed}")
    if params:
        path += "?" + "&".join(params)
    status, headers, data = ph.request("GET", path)
    if status == 304:
        print(f"304 unchanged (hash {headers.get('x-screen-hash')}) — the screen is pixel-identical; no new file written.")
        return 0
    if status != 200:
        return show(status, _as_json(data))
    full = parse_size(headers.get("x-screen-full"))
    sent = parse_size(headers.get("x-screen-sent"))
    digest = headers.get("x-screen-hash") or hashlib.sha256(data).hexdigest()[:16]
    ext = "png" if "png" in headers.get("content-type", "") else "jpg"
    out = a.out or f"shot.{ext}"
    with open(out, "wb") as f:
        f.write(data)
    sidecar = {"file": out, "full": full, "sent": sent, "hash": digest, "bytes": len(data)}
    sidecar_path = (a.out + ".json") if a.out else "shot.json"
    with open(sidecar_path, "w", encoding="utf-8") as f:
        json.dump(sidecar, f, indent=2)
    print(f"saved {out} ({len(data)} bytes) full={full[0]}x{full[1]} sent={sent[0]}x{sent[1]} hash={digest}")
    if full != sent:
        print(f"convert before tapping: x_real = x_seen * {full[0]}/{sent[0]}, y_real = y_seen * {full[1]}/{sent[1]} "
              f"— or use `tap-seen X Y` and let the client convert.")
    print(f"poll cheaply while waiting: phone_client.py shot --changed {digest}")
    return 0


def load_sidecar(a) -> dict:
    path = a.sidecar or "shot.json"
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except OSError:
        die(f"no sidecar at {path}: run `phone_client.py shot` first (tap-seen converts from its sizes).")


def main(argv=None) -> int:
    p = argparse.ArgumentParser(
        prog="phone_client.py",
        description="Drive a phone running phone-eye. Read PHONE-EYE.md first — the loop, the coordinate conversion, and the refusals table live there.",
    )
    p.add_argument("--url", default=os.environ.get("PHONE_EYE_URL", ""), help="or $PHONE_EYE_URL")
    p.add_argument("--token", default=os.environ.get("PHONE_EYE_TOKEN", ""), help="or $PHONE_EYE_TOKEN")
    p.add_argument("--timeout", type=float, default=30.0)
    sub = p.add_subparsers(dest="cmd", required=True)

    sub.add_parser("selftest", help="GET /selftest — do this first, report every check")
    sub.add_parser("state", help="GET /state — cheap, no capture")
    sub.add_parser("info", help="GET /info")
    sub.add_parser("ui", help="GET /ui — the window tree (words, not pixels)")
    sub.add_parser("apps", help="GET /apps")
    sub.add_parser("location", help="GET /location")

    s = sub.add_parser("shot", help="GET /shot — save image + sidecar with full/sent sizes and hash")
    s.add_argument("--out")
    s.add_argument("--max", type=int, help="max sent width px (0 = 1:1; default: phone-eye default)")
    s.add_argument("--quality", type=int)
    s.add_argument("--changed", help="previous X-Screen-Hash; 304 when identical")

    s = sub.add_parser("tap-seen", help="tap coordinates AS SEEN in the last shot (converted here)")
    s.add_argument("x", type=int)
    s.add_argument("y", type=int)
    s.add_argument("--sidecar")

    s = sub.add_parser("tap", help="tap REAL screen pixels")
    s.add_argument("x", type=int)
    s.add_argument("y", type=int)

    s = sub.add_parser("swipe", help="swipe in REAL screen pixels")
    for n in ("x1", "y1", "x2", "y2"):
        s.add_argument(n, type=int)
    s.add_argument("--duration-ms", type=int, default=300)

    s = sub.add_parser("key", help="back/home/recents/notifications/quick_settings/power/lock/enter/wake/volume_up/volume_down")
    s.add_argument("key")
    s.add_argument("--times", type=int, default=1)

    s = sub.add_parser("type", help="append text to the focused field")
    s.add_argument("text")
    s = sub.add_parser("paste", help="clipboard + paste into the focused field")
    s.add_argument("text")
    s = sub.add_parser("tap-text", help="tap the visible element with this text (safer than pixels)")
    s.add_argument("text")
    s.add_argument("--index", type=int, default=0)

    s = sub.add_parser("scroll", help="negative clicks scroll down (default -3)")
    s.add_argument("--clicks", type=int, default=-3)

    s = sub.add_parser("act", help="raw /act JSON")
    s.add_argument("json")

    s = sub.add_parser("shell", help="POST /shell — runs AS THE APP, no root")
    s.add_argument("cmdline")
    s.add_argument("--timeout-s", type=int, default=10)

    s = sub.add_parser("ls", help="GET /files")
    s.add_argument("path", nargs="?", default="")
    s = sub.add_parser("get", help="GET /download PATH -> LOCALFILE")
    s.add_argument("path")
    s.add_argument("localfile")
    s = sub.add_parser("put", help="POST /upload LOCALFILE -> PATH")
    s.add_argument("localfile")
    s.add_argument("path")

    s = sub.add_parser("open", help="launch a package or open a URL")
    s.add_argument("--package")
    s.add_argument("--open-url", dest="open_url")

    s = sub.add_parser("photo", help="GET /photo — one camera frame")
    s.add_argument("--camera", choices=["back", "front"], default="back")
    s.add_argument("--out", default="photo.jpg")

    s = sub.add_parser("get-raw", help="GET any route, JSON out")
    s.add_argument("path")
    s = sub.add_parser("post-raw", help="POST JSON to any route")
    s.add_argument("path")
    s.add_argument("json")

    a = p.parse_args(argv)
    if not a.url or not a.token:
        die("need the two lines: set PHONE_EYE_URL and PHONE_EYE_TOKEN (or --url/--token). "
            "They are on the phone-eye app screen after the human presses Start.")
    if "trycloudflare.com" not in a.url and "lhr.life" not in a.url and "serveo" not in a.url and "127.0.0.1" not in a.url and "192.168." not in a.url and "10." not in a.url:
        print("note: that URL names none of phone-eye's tunnels (trycloudflare / lhr.life / serveo) "
              "— if it is LAN/adb, fine; if not, re-check the two lines with the human.", file=sys.stderr)

    ph = Phone(a.url, a.token, timeout=a.timeout)
    cmd = a.cmd

    if cmd == "selftest":
        return show(*ph.get_json("/selftest"))
    if cmd == "state":
        return show(*ph.get_json("/state"))
    if cmd == "info":
        return show(*ph.get_json("/info"))
    if cmd == "ui":
        return show(*ph.get_json("/ui"))
    if cmd == "apps":
        return show(*ph.get_json("/apps"))
    if cmd == "location":
        return show(*ph.get_json("/location"))
    if cmd == "shot":
        return cmd_shot(ph, a)
    if cmd == "tap-seen":
        sc = load_sidecar(a)
        full, sent = sc["full"], sc["sent"]
        xr, yr = convert(a.x, sent[0], full[0]), convert(a.y, sent[1], full[1])
        print(f"seen ({a.x},{a.y}) in {sent[0]}x{sent[1]} -> real ({xr},{yr}) of {full[0]}x{full[1]}")
        return show(*ph.act({"action": "tap", "x": xr, "y": yr}))
    if cmd == "tap":
        return show(*ph.act({"action": "tap", "x": a.x, "y": a.y}))
    if cmd == "swipe":
        return show(*ph.act({"action": "swipe", "x": a.x1, "y": a.y1, "x2": a.x2, "y2": a.y2,
                              "duration_ms": a.duration_ms}))
    if cmd == "key":
        return show(*ph.act({"action": "key", "key": a.key, "times": a.times}))
    if cmd == "type":
        return show(*ph.act({"action": "type", "text": a.text}))
    if cmd == "paste":
        return show(*ph.act({"action": "paste", "text": a.text}))
    if cmd == "tap-text":
        return show(*ph.act({"action": "tap_text", "text": a.text, "index": a.index}))
    if cmd == "scroll":
        return show(*ph.act({"action": "scroll", "clicks": a.clicks}))
    if cmd == "act":
        return show(*ph.act(json.loads(a.json)))
    if cmd == "shell":
        return show(*ph.post_json("/shell", {"cmd": a.cmdline, "timeout_s": a.timeout_s}))
    if cmd == "ls":
        q = f"?path={urllib.request.quote(a.path)}" if a.path else ""
        return show(*ph.get_json(f"/files{q}"))
    if cmd == "get":
        status, headers, data = ph.request("GET", f"/download?path={urllib.request.quote(a.path)}")
        if status != 200:
            return show(status, _as_json(data))
        with open(a.localfile, "wb") as f:
            f.write(data)
        print(f"saved {a.localfile} ({len(data)} bytes) sha256={hashlib.sha256(data).hexdigest()}")
        return 0
    if cmd == "put":
        with open(a.localfile, "rb") as f:
            data = f.read()
        status, _, resp = ph.request(
            "POST", f"/upload?path={urllib.request.quote(a.path)}",
            body=data, content_type="application/octet-stream", raw_query_token=False)
        return show(status, _as_json(resp))
    if cmd == "open":
        obj = {}
        if a.package:
            obj["package"] = a.package
        if a.open_url:
            obj["url"] = a.open_url
        return show(*ph.post_json("/open", obj))
    if cmd == "photo":
        status, headers, data = ph.request("GET", f"/photo?camera={a.camera}")
        if status != 200:
            return show(status, _as_json(data))
        with open(a.out, "wb") as f:
            f.write(data)
        print(f"saved {a.out} ({len(data)} bytes, camera={a.camera})")
        return 0
    if cmd == "get-raw":
        return show(*ph.get_json(a.path))
    if cmd == "post-raw":
        return show(*ph.post_json(a.path, json.loads(a.json)))
    return 2


if __name__ == "__main__":
    sys.exit(main())
