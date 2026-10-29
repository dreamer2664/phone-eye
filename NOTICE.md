# Third-party software in phone-eye

phone-eye's own code is MIT (see LICENSE). It stands on:

| Component | Licence | What it does here | Upstream |
|---|---|---|---|
| **cloudflared** | Apache-2.0 (Cloudflare) | the tunnel binary, bundled as `libcloudflared.so` at build time (pinned + SHA-256-verified; never committed) | https://github.com/cloudflare/cloudflared |
| **NanoHTTPD** | BSD-3-Clause | the HTTP server on :8791 | https://github.com/NanoHttpd/nanohttpd |
| **sshj** | Apache-2.0 | SSH client for the localhost.run / serveo.net fallback tunnels | https://github.com/hierynomus/sshj |
| **Bouncy Castle** (`bcprov-jdk18on`) | Bouncy Castle Licence (MIT-style) | cryptography sshj needs on Android | https://www.bouncycastle.org |
| **SLF4J** | MIT | logging facade sshj links against (bound to nop) | https://www.slf4j.org |
| **Kotlin stdlib** | Apache-2.0 | the language runtime | https://kotlinlang.org |

Tunnel *patterns* (not code) were learned from, and are credited in `docs/ARCHITECTURE.md`:

- ashokvarmamatta, *Running Cloudflare Tunnels on Android — Complete Guide* (gist): the 5 Android traps.
- Thinai PR #72 (`quick_tunnel.dart`): do DNS in the app language, pass edge IPs to cloudflared; the token-in-environment detail; the Gradle fetch-and-strip shape.

The *design* — two lines (`URL:`/`TOKEN:`), a token as the only lock, a server that always answers with a sentence, refusals instead of clamping, look→act-once→look-again — is **pc-eye's**, the Windows sibling in this owner's `pc-eye` repo. phone-eye is a port of that contract to Android's rules, not a fork of its code.
