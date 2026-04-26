# Instant Hotspot (Offline Controller)

Android app scaffold for controlling hotspot on a rooted primary phone from a secondary Android device via offline proximity transport (BLE planned).

## License

This project is dual-licensed under **MIT** and **GPL-3.0-or-later**.
You may choose either license for your use/distribution:

- `LICENSE-MIT`
- `LICENSE-GPL-3.0`

## Current status

- **v0.4.0+**: **M3** home refactor (split layouts, **tablet / landscape** two-pane, entrance + **Material fade** for mode and verbose), **host foreground live status** notification (hotspot on/off, paired controller, paired-since) with promoted-ongoing support where the OS allows, BLE **host state** + **config** plumbing, **`cli/`** companion. Repo: <https://github.com/spundone/instant-hotspot>.
- **v0.3.0+**: In-app **GitHub** and **Releases (APK + Magisk / KernelSU zip)** links, **Check for updates** (GitHub API), shortcuts to **tethering / Bluetooth / app info / battery** optimization, **copy debug log**; `lint` clean with manifest and BLE suppressions.
- **v0.2.1+**: BLE **state** characteristic for on/off/unknown AP (tile + widget), **pending pairing** survives host service restarts, optional **bond-only** command allowlist on the host, shared **HotspotConfigParser**, and an experimental **`cli/`** scanner (Python + bleak).
- **v0.2.0+**: Multi-step in-app set-up (welcome → role → pairing → remote controls → Wi‑Fi / sync of hotspot
  settings → tiles & widgets), then a **minimal home** for the controller, or the full “console” for
  the host. Existing installs skip the set-up; use **Run set-up again** in the app menu to replay it.
- **Version label includes commit id** in-app (for example `Version 0.2.0 (3) • d9e1904d`) so builds are easy to trace.
- **Simple / verbose toggle** is available in-app (including host) to show/hide verbose logs without changing mode.
- **Expressive MD3 pass**: larger rounded action buttons, dynamic system accent support (Android 12+), darker layered surfaces.
- **Sync hotspot settings** now tries to parse host credentials (SSID/password), shows them, and opens Wi-Fi settings to connect.
- **Quick Settings tile** sends the same ON/OFF command family as app buttons and supports tile-preferences long-press target.
- Single Android app with runtime mode selection:
  - Host mode (primary/rooted device)
  - Controller mode (tablet/secondary device)
- Quick Settings tile entry point
- Home screen widget entry point
- Root hotspot command executor with command fallback:
  - `cmd connectivity tether start/stop`
  - `cmd wifi start-softap/stop-softap`
- Host BLE GATT server + BLE advertising for command intake
- Controller BLE scanner/client that discovers host and writes signed commands
- HMAC signature check and timestamp replay protection on host
- Offline ECDH + SAS pairing over BLE:
  - Controller sends `PAIR_ECDH_INIT` with ephemeral public key
  - Host replies with its ephemeral public key + short auth code (SAS)
  - Both sides compute the same SAS from ECDH secret and display/verify it
  - Host must explicitly approve the pending code in-app before `PAIR_ECDH_CONFIRM`
  - Host commits derived secret only after both confirms

## Project layout

- `app/src/main/java/com/spandan/instanthotspot/MainActivity.kt` - mode switch + permission bootstrap
- `app/src/main/java/com/spandan/instanthotspot/host/HostBleService.kt` - host listener service
- `app/src/main/java/com/spandan/instanthotspot/controller/ControllerCommandSender.kt` - controller command dispatch
- `app/src/main/java/com/spandan/instanthotspot/core/HotspotController.kt` - root command execution
- `app/src/main/java/com/spandan/instanthotspot/tile/HotspotTileService.kt` - Quick Settings tile
- `app/src/main/java/com/spandan/instanthotspot/widget/HotspotWidgetProvider.kt` - widget trigger

## Host Magisk module: when, why, how

### When you need it

- You are using a **sideloaded host app install** (normal APK install, debug install, not ROM-integrated priv-app).
- You want reliable remote hotspot control from controller app buttons, widget, and QS tile.

### Why it is needed

- Android's in-process tethering API requires **`TETHER_PRIVILEGED`**.
- Root alone does not grant this app-privilege model by itself.
- On many ROMs, shell-only fallback commands can be inconsistent, while priv-app + allowlist remains stable.

### How to enable it

1. Flash the host module from **`magisk_module/`**.
2. Reboot host device.
3. Open Instant Hotspot on host and verify compatibility status.
4. Pair controller again if needed and test ON/OFF + sync.

Alternative: install as a true ROM-integrated `priv-app` with proper allowlist.

## Open in Android Studio

1. Open this folder as a project.
2. Let Android Studio install/resolve Android Gradle Plugin and SDKs.
3. Run on both devices and set one to Host mode and one to Controller mode.

## Command-line build (no Android Studio)

This repo includes the **Gradle wrapper** (`gradlew`, `gradlew.bat`, `gradle/wrapper/`). You still need a **JDK** and the **Android SDK**; Studio is not required.

1. **JDK**: Use **JDK 17** or **JDK 21** to **run** Gradle. Android Gradle Plugin 8.5.x does not test against very new runtimes, and **Gradle 8.7 (this repo) does not run on JDK 25+**; if the only “error” is a bare line like `25.0.2`, that is your `java` version. Point `JAVA_HOME` at 17/21, for example: `export JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || /usr/libexec/java_home -v 21)"` on macOS. This project’s `sourceCompatibility` / `jvmTarget` is 17.
2. **Set `ANDROID_HOME`** to your SDK root, for example on macOS: `export ANDROID_HOME="$HOME/Library/Android/sdk"` (adjust if your SDK is elsewhere).
3. **Install SDK components** with `sdkmanager` (from command-line tools), for example: API **35** platform and **Build-Tools 35.x** to match `compileSdk 35` / `targetSdk 35` in `app/build.gradle.kts`. Accept licenses: `yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses` (path may differ slightly by install).
4. **Build a debug APK** from the project root:

```bash
./gradlew :app:assembleDebug
```

The APK is written under `app/build/outputs/apk/debug/`. Install with `adb install -r` if needed.

### If Gradle fails with only a version number (e.g. `25.0.2`)

That string is almost always the **JVM** Gradle is using. **Gradle 8.7** cannot run the build on **JDK 25**; you need a supported JDK to launch Gradle (see JDK step above) or a future upgrade of the wrapper to **Gradle 9.1+** together with a compatible Android Gradle plugin.

## Next implementation steps

1. **CLI:** extend `cli/` to sign commands (HMAC) and match `CommandCodec` / pairing wire format; today the folder includes BLE **scan** + docs.
2. **Testing:** more OEM coverage for the state characteristic + soft AP probe edge cases.
3. **Optional:** host-side notifications when AP state flips (without polling from controller).

**Recently added (v0.2.1+):** GATT `STATE` read (`ap=0|1|2`) for real hotspot on/off in the tile and widget, hardened SSID/password parsing, persisted BLE pending-pairing map across host process restarts, host toggle **only Bluetooth-bonded** senders, widget shows paired name + AP state, and a starter **Python + bleak** CLI under `cli/`.

## Contributing

Pull requests, bug reports, and feature requests are welcome.

- Read `CONTRIBUTING.md` for contribution flow.
- Use the issue templates in `.github/ISSUE_TEMPLATE/`.
- Open PRs against `main`.
