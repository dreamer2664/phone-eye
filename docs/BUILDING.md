# Building phone-eye

## Prerequisites

- **JDK 17** exactly-ish (AGP 8.6.1 demands 17; newer JDKs mostly work, 17 is what's tested)
- **Android SDK**: platform `android-35`, `build-tools;35.0.0` (Android Studio or `sdkmanager`)
- Network on first build (Gradle 8.9 distribution, dependencies, and the pinned cloudflared binary)
- Optional: an **NDK** — only used to `llvm-strip` cloudflared (≈ halves its APK cost). Without it the build says so and ships unstripped.

Versions are pinned in the root `build.gradle.kts` (AGP 8.6.1, Kotlin 2.0.20, compileSdk/targetSdk 35, minSdk 30). Bump deliberately, then run the on-device ritual (PHONE-EYE.md § first run) before tagging.

## Build

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug     # tests + debug APK
./gradlew :app:assembleRelease                          # release APK (signed, see below)
# APKs land in app/build/outputs/apk/{debug,release}/
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The first build downloads **cloudflared-linux-arm64** at the version + SHA-256 pinned in `app/build.gradle.kts` (`fetchCloudflared` task), verifies it, and stages it as `app/src/main/jniLibs/arm64-v8a/libcloudflared.so` (git-ignored). If the checksum ever fails, the build refuses with instructions — that is supply-chain hygiene working, not a network flake to retry past.

Variants:

```bash
./scripts/fetch-cloudflared.sh               # pre-fetch by hand (offline builds; same pins, checked by CI)
./gradlew :app:assembleDebug -PnoCloudflared # no binary baked in: SSH/LAN tunnels only, tiny APK, fast dev loops
```

## Signing

Release signing is environment-driven so no key ever touches git:

| Env var | Meaning |
|---|---|
| `PHONE_EYE_KEYSTORE` | path to a PKCS12 (`.p12`) keystore |
| `PHONE_EYE_KEYSTORE_PASSWORD` | store password |
| `PHONE_EYE_KEY_ALIAS` | key alias (`phoneeye` for the official key) |
| `PHONE_EYE_KEY_PASSWORD` | key password |

With all set, `assembleRelease` signs properly. Without them it falls back to the **debug key** and says so — fine for forks and testing, but remember Android's rule: an update must match the installed app's signature, or it's uninstall-first (losing runtime grants; accessibility stays enabled in Settings but re-binding a fresh install is a re-confirm on some ROMs).

**The official keystore** lives in two places only: the owner's private backup (`phone-eye-signing/` next to — never inside — the checkout; `.gitignore` blocks it) and this repo's encrypted Actions secrets (`PHONE_EYE_KEYSTORE_B64` = base64 of the `.p12`, plus the two passwords + alias). Losing both means every installed phone-eye must be uninstalled before the next update. Back it up like a password; share it like one too (never).

Making your own (forks):

```bash
openssl req -x509 -newkey rsa:4096 -keyout key.pem -out cert.pem -days 10950 -nodes -subj "/CN=phone-eye"
openssl pkcs12 -export -name phoneeye -inkey key.pem -in cert.pem -out phone-eye-keystore.p12
# then set the four env vars (CI: base64 the .p12 into a secret named PHONE_EYE_KEYSTORE_B64)
```

## CI / releases

`.github/workflows/build.yml` on every push/PR: wrapper validation → JDK 17 → SDK 35 → `tools/check_docs.py` → agent-client smoke test → unit tests → `assembleDebug + assembleRelease` → named, hashed APKs as artifacts. A red docs gate fails the build before Gradle even warms up.

To release:

1. Bump **both** `versionName`/`versionCode` (`app/build.gradle.kts`) and `Core.VERSION` — `check_docs.py` enforces their agreement — and add the `CHANGELOG.md` section.
2. Run, locally or by watching CI on the branch: unit tests, `check_docs.py`, and the on-device first-run ritual with the exact artifact candidate.
3. `git tag vX.Y.Z && git push origin vX.Y.Z` — the tag build publishes a GitHub Release with `phone-eye-vX.Y.Z.apk` + `SHA256SUMS.txt`.
4. Install the *release asset* on a real phone once (not just the adb build): confirm the signature, the first-run ritual, and that an over-the-top upgrade from the previous release works — signature continuity is the whole point of the keystore.

## Tests

- **Unit** (`app/src/test/`, JVM): the pure contract — token shape/comparison, pc-eye's coordinate example, hash stability, bucket burst, run-token encoding against the trycloudflare fixture, path sandbox escapes. `./gradlew :app:testDebugUnitTest`.
- **Docs gate**: `python3 tools/check_docs.py` — routes/actions/version/cloudflared-pin agreement between code and prose.
- **Client smoke**: `py_compile` + `--help` + the refuse-without-two-lines path (CI).
- **On-device**: there is no emulator substitute for an accessibility gesture landing on glass, an OEM battery killer, or a carrier NAT eating a tunnel. PHONE-EYE.md's first run is the acceptance test; run it per release and record the outcome in the CHANGELOG entry. (pc-eye learned this as "invisible from Linux, obvious within minutes on the real machine." Same here, with more radios.)
