# Instant Hotspot (Offline Controller)

Android app scaffold for controlling hotspot on a rooted primary phone from a secondary Android device via offline proximity transport (BLE planned).

## License

This project is dual-licensed under **MIT** and **GPL-3.0-or-later**.
You may choose either license for your use/distribution:

- `LICENSE-MIT`
- `LICENSE-GPL-3.0`

## Current status

- **v0.6.8+** / **0.6.7**: **Host command verification + BLE privacy:** signed hotspot commands are accepted when Android reports a **different BLE address** than at pairing time (random/private MAC rotation). The host tries the HMAC against **each paired controller secret**, then **updates the stored address** on the first successful write so the paired-controllers list stays accurate. **v0.6.7** context: multi-pair registries, simple/full parity, onboarding pairing step trim.
- **v0.6.7+** / **0.6.6**: **Multi-pair registries:** **Saved hosts** on the controller **Pair** tab (and on **simple controller home**) with **Use for commands** / **Remove**; **paired controllers** list on the host **Pair** tab with **Remove**. Host BLE path updated for **multiple controllers** paired over time. **Simple / full parity:** simple overflow adds **Sync hotspot settings**, **Open Wi‑Fi with typed credentials**, and **Unpair**; **Tools** adds **Simple controller home** (controller only) and **Run set-up again**; **Settings** adds a **Simple controller home** toggle; simple mode gets the same periodic status refresh as the full console. **Onboarding:** leaner pairing step (network handling stays on the network step). **v0.6.6** context: host after onboarding, pairing UI refresh, phrase field.
- **v0.6.6+** / **0.6.5**: **Host after onboarding:** finishing set-up keeps **host mode** and **full console** (no forced controller simple home); `commitHostMode` uses **commit()**. **Pairing UI:** LocalSend-style flow (one **Connect nearby** block, large code, **or** + phrase path), updated copy, onboarding controller **phrase** field. **v0.6.5** context: onboarding state default, pairing re-layout, **v0.6.4** prefs.
- **v0.6.5+** / **0.6.4**: **Onboarding host path:** `onboarding_v2_state` default and pairing step **re-layout** fix so choosing **Host** on the role step shows **host pairing** (not controller). **v0.6.4** context: pairing mode after onboarding, **Pair** tab toggle, prefs **commit** for BLE.
- **v0.6.4+** / **0.6.3**: **Pairing mode** no longer gets turned off when onboarding finishes; **commit**-sync on toggle so BLE sees the flag immediately; **Enable/disable pairing** on the **Host → Pair** card as well as **Hotspot**.
- **v0.6.3+** / **0.6.2**: Restores **pairing passphrase** editing on **simple home** and **host** pair card, and a **Tools** tab button for **5G / ring / ping / Wi‑Fi** (same list as the overflow “Project, updates &amp; tools” menu).
- **v0.6.2+** / **0.6.1**: Onboarding: host walkthrough (pairing, controls, network) now follows the **host** path when the user picked **Host** but root/module checks are not done yet (prefs stay on controller until verified); role step includes a **KernelSU** note to enable “View system apps” when granting **Superuser**. **0.6.1** also fixed a first-launch **ViewPager** crash on the role step. Builds: <https://github.com/spundone/instant-hotspot/releases> (**APK** + **Magisk** zip per release).
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
- `app/src/main/java/com/spandan/instanthotspot/core/LocalAlertPlayer.kt`, `NetworkPing.kt`, `NetworkRadioTuning.kt` - attention / ping / network-mode helpers (controller UI + host BLE commands)
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

1. **Build a flashable zip** that contains the current app (the repo’s `magisk_module/` path alone does not include an APK; it is template + allowlist). From the project root, run **`./gradlew :app:packageMagiskModuleRelease`**, then flash **`build/magisk-release-out/InstantHotspot-magisk-release.zip`** in Magisk (or use the debug variant: `packageMagiskModule` → `app/build/dist/InstantHotspot-magisk.zip`). To drop a built APK into the tree and zip the folder yourself, use **`./gradlew :app:syncReleaseApkIntoMagiskTemplate`** and see `magisk_module/.../InstantHotspot/README-APK.txt`.
2. Reboot the host device.
3. Open Instant Hotspot on the host and verify compatibility status.
4. Pair the controller again if needed and test ON/OFF + sync.

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

5. **Magisk / KernelSU host module** (priv-app with allowlist; includes the **built** app in the zip, not a stub from git):

```bash
./gradlew :app:packageMagiskModuleRelease
```

The flashable zip is `build/magisk-release-out/InstantHotspot-magisk-release.zip` (or `:app:packageMagiskModule` for debug under `app/build/dist/`). To copy a built APK into `magisk_module/.../InstantHotspot/` for a manual zip, use `:app:syncReleaseApkIntoMagiskTemplate` (see `magisk_module/.../README-APK.txt`).

### If Gradle fails with only a version number (e.g. `25.0.2`)

That string is almost always the **JVM** Gradle is using. **Gradle 8.7** cannot run the build on **JDK 25**; you need a supported JDK to launch Gradle (see JDK step above) or a future upgrade of the wrapper to **Gradle 9.1+** together with a compatible Android Gradle plugin.

## Next implementation steps

1. **CLI:** extend `cli/` to sign commands (HMAC) and match `CommandCodec` / pairing wire format; today the folder includes BLE **scan** + docs.
2. **Testing:** more OEM coverage for the state characteristic + soft AP probe edge cases.
3. **Optional:** host-side notifications when AP state flips (without polling from controller).

**Release artifacts:** each [GitHub release](https://github.com/spundone/instant-hotspot/releases) publishes a versioned **APK** and **`InstantHotspot-magisk-*.zip`**; see **v0.6.8+** above for the latest feature set. (Older milestones remain in the version bullets, e.g. v0.2.1+ for GATT `STATE` and the `cli/` scanner.)

## Contributing

Pull requests, bug reports, and feature requests are welcome.

- Read `CONTRIBUTING.md` for contribution flow.
- Use the issue templates in `.github/ISSUE_TEMPLATE/`.
- Open PRs against `main`.
