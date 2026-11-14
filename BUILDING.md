# Building this fork yourself

This fork is built on your own computer and signed with your own key, so the APK you install comes from this source and nobody
else's build. These steps are for Linux; the same tools exist for macOS and Windows.

## What this fork changes

Compared with upstream (`benbishop028-arch/Horizon-Maps`):

- **The in-app updater is off.** `UPDATE_REPO` in `app/build.gradle.kts` is blank, so the app never checks for or downloads a new
  version. New versions come from merging upstream and rebuilding (see below).
- **The app cannot install APKs.** The `REQUEST_INSTALL_PACKAGES` permission is removed from the manifest.
- **No background check for newer offline map data.** It only looks when you open Settings › Offline maps.
- **"Send on GitHub" buttons open this fork's issues** (`FEEDBACK_REPO`), not upstream's.
- **Trip planner** (new): plan a long drive with fuel, rest, meal and overnight stops, a timeline and the fuel cost.
  Code: `TripPlan.kt`, `ui/TripPlanner.kt`, tests in `TripMathTest.kt`.

Still pointing at upstream: offline map packs download from the upstream author's GitHub releases when you tap download.

## One-time setup

You need JDK 17, Gradle 8.14.4 (the repo has no Gradle wrapper) and the Android SDK.

```bash
# JDK 17 and Gradle 8.14.4 with SDKMAN (https://sdkman.io); answer "n" to keep your current defaults
sdk install java 17.0.21.0+9-tem      # any 17.x Temurin from `sdk list java`
sdk install gradle 8.14.4

# Android command-line tools, then the SDK pieces the build uses
mkdir -p ~/Android/Sdk/cmdline-tools && cd ~/Android/Sdk/cmdline-tools
curl -fLo clt.zip https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip
unzip -q clt.zip && mv cmdline-tools latest && rm clt.zip
~/Android/Sdk/cmdline-tools/latest/bin/sdkmanager --licenses
~/Android/Sdk/cmdline-tools/latest/bin/sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"
```

### Your signing key

Android only installs an update over an existing app when both are signed with the same key. Make one once, in the repo root:

```bash
keytool -genkeypair -keystore signing.keystore -storepass android -keypass android \
  -alias androiddebugkey -keyalg RSA -keysize 4096 -validity 10950 -dname "CN=Horizon Nav self-build"
```

The build picks up `signing.keystore` by itself (the password and alias above are what `app/build.gradle.kts` expects).
`*.keystore` is in `.gitignore`: **never commit it, and keep a backup.** If you lose it, future builds can't update the installed
app and you have to uninstall it (losing its settings and downloaded maps) and install afresh.

## Build

From the repo root:

```bash
export JAVA_HOME=~/.sdkman/candidates/java/17.0.21.0+9-tem
export ANDROID_HOME=~/Android/Sdk

~/.sdkman/candidates/gradle/8.14.4/bin/gradle --no-daemon :app:assembleRelease -PbuildNumber=522
```

The APK is `app/build/outputs/apk/release/HorizonNav-public.apk`.

- **`-PbuildNumber`** sets the version: `522` gives version 1.0.522. Use the number of the upstream version you built from
  (the newest `v1.0.N` tag), and never a lower number than the one installed, or Android refuses the update.
- **Short of memory?** If the build dies with "Gradle build daemon disappeared unexpectedly", it ran out of RAM. Use this instead:

  ```bash
  ~/.sdkman/candidates/gradle/8.14.4/bin/gradle --no-daemon --max-workers=2 \
    "-Dorg.gradle.jvmargs=-Xmx2600m -XX:+UseSerialGC -Dfile.encoding=UTF-8" \
    -Pkotlin.compiler.execution.strategy=in-process \
    :app:assembleRelease -PbuildNumber=522
  ```

### Tests

```bash
~/.sdkman/candidates/gradle/8.14.4/bin/gradle --no-daemon :app:testPublicReleaseUnitTest
```

### Check what you built

```bash
~/Android/Sdk/build-tools/36.0.0/apksigner verify --print-certs app/build/outputs/apk/release/HorizonNav-public.apk
keytool -list -keystore signing.keystore -storepass android     # the SHA-256 should match the line above
```

## Install

With USB debugging on and the phone or head unit plugged in:

```bash
adb install -r app/build/outputs/apk/release/HorizonNav-public.apk
```

Or copy the APK to the device and open it.

If the upstream author's build is installed, uninstall it first: it is signed with a different key, so Android won't install
this one over it. Uninstalling removes its settings and downloaded maps (Settings › Back up saves the settings to a file first).

## Getting upstream's changes

```bash
git remote add upstream https://github.com/benbishop028-arch/Horizon-Maps.git   # once
git fetch upstream --tags
git log --stat HEAD..upstream/main      # read what changed before you take it
git merge upstream/main
```

After merging, check the fork's changes survived before building:

```bash
grep -n 'UPDATE_REPO\|FEEDBACK_REPO' app/build.gradle.kts        # UPDATE_REPO must still be ""
grep -c REQUEST_INSTALL_PACKAGES app/src/main/AndroidManifest.xml # only the comment line: 1
grep -n 'Updater.enabled || packBusy' app/src/main/kotlin/com/headunit/launcher/NavEngine.kt
```

Then run the tests and build with the new `-PbuildNumber`.

Two things to know about upstream's layout:

- Upstream's GitHub workflow (`.github/workflows/build.yml`) builds from `HorizonNav.zip`, not from the files in the repo, and
  copies the ZIP's contents over them. This fork's changes are **not** in that ZIP, so don't use that workflow here: build
  locally as above. (GitHub keeps workflows switched off on a fork unless you enable them.)
- Because of that, compare the ZIP with the source when you merge, to be sure you're building what you read:

  ```bash
  git worktree add /tmp/hn-up upstream/main
  mkdir -p /tmp/hn-zip && unzip -qo /tmp/hn-up/HorizonNav.zip -d /tmp/hn-zip
  diff -rq /tmp/hn-up/app /tmp/hn-zip/app && echo "ZIP matches the source"
  git worktree remove --force /tmp/hn-up && rm -rf /tmp/hn-zip
  ```
