# Build Guide — Comet-X

## Prerequisites

| Tool | Version used | Notes |
|---|---|---|
| JDK | 17 or 21 (21 used) | AGP 8.5 requires 17+ |
| Android SDK | platform 34, build-tools 34.0.0, platform-tools | via `sdkmanager` |
| Gradle | 8.7 (wrapper included) | wrapper downloads the dist automatically |
| AGP / Kotlin | 8.5.2 / 1.9.24 | resolved by the wrapper |

## One-time setup

```bash
# 1) SDK (if not already installed)
export ANDROID_HOME=$HOME/android-sdk
mkdir -p $ANDROID_HOME/cmdline-tools
curl -sSL -o /tmp/ct.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip /tmp/ct.zip -d $ANDROID_HOME/cmdline-tools
mv $ANDROID_HOME/cmdline-tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest
yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"

# 2) local.properties (or ANDROID_HOME env)
echo "sdk.dir=$ANDROID_HOME" > local.properties
```

## Build

```bash
./gradlew assembleDebug          # debug APK → app/build/outputs/apk/debug/
./gradlew assembleRelease        # release APK → app/build/outputs/apk/release/
./gradlew testDebugUnitTest      # full unit + Robolectric suite
```

First build downloads Gradle + AGP + Kotlin artifacts (~800 MB); subsequent builds are incremental (seconds).

## Signing model (security note)

- The release keystore is **auto-generated locally** on first `assembleRelease` (`app/keystore/cometx-release.jks` + `keystore.properties`), both **gitignored** — no signing material is ever committed.
- Consequence: every machine produces its own keystore, so APK signatures are not portable across machines. For a stable public signing key, generate your own keystore and wire it into `app/build.gradle.kts` (replace the `ensureReleaseKeystore` task).

## Verify an APK

```bash
$ANDROID_HOME/build-tools/34.0.0/apksigner verify --print-certs app-release.apk
$ANDROID_HOME/build-tools/34.0.0/aapt2 dump badging app-release.apk | head -5
sha256sum app-release.apk
```

## Install & smoke-test on a device

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell monkey -p com.cometx.browser 1      # launch
```

Manual smoke checklist (mirrors brief §40):
1. App launches; browser loads a URL
2. Agent panel opens (bottom bar → Open)
3. Settings → enter a Groq (or other) API key
4. Agent executes a basic task (e.g., "open example.com and extract the heading")
5. Menu → Agent self-test → run a task against the local test pages
6. Pause/Take Control → interact manually → Resume → agent re-observes and continues

## Configure providers

In-app: Settings → AI Providers → paste key(s) for Groq / OpenRouter / Hugging Face / Custom, pick the active provider, optionally set per-role model IDs (FAST/REASONING/VISION/STRONG/CHEAP). Keys are Keystore-encrypted on device. No keys exist in the repository or the APK.

## Agent self-test pages

Menu → "Agent self-test (local pages)" starts a loopback-only server (127.0.0.1:8081) serving: normal, dynamic, difficult (menus/modals), injection, phish, challenge (simulated verification), long, and tarpit pages — each targets one agent capability (brief §29).
