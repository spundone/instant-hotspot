# Instant Hotspot (Offline Controller)

Android app scaffold for controlling hotspot on a rooted primary phone from a secondary Android device via offline proximity transport (BLE planned).

## Current status

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

## Open in Android Studio

1. Open this folder as a project.
2. Let Android Studio install/resolve Android Gradle Plugin and SDKs.
3. Run on both devices and set one to Host mode and one to Controller mode.

## Command-line build (no Android Studio)

This repo includes the **Gradle wrapper** (`gradlew`, `gradlew.bat`, `gradle/wrapper/`). You still need a **JDK** and the **Android SDK**; Studio is not required.

1. **JDK**: Use **JDK 17** (recommended for Android Gradle Plugin 8.5.x). This project’s `sourceCompatibility` / `jvmTarget` is 17.
2. **Set `ANDROID_HOME`** to your SDK root, for example on macOS: `export ANDROID_HOME="$HOME/Library/Android/sdk"` (adjust if your SDK is elsewhere).
3. **Install SDK components** with `sdkmanager` (from command-line tools), for example: API **35** platform and **Build-Tools 35.x** to match `compileSdk 35` / `targetSdk 35` in `app/build.gradle.kts`. Accept licenses: `yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses` (path may differ slightly by install).
4. **Build a debug APK** from the project root:

```bash
./gradlew :app:assembleDebug
```

The APK is written under `app/build/outputs/apk/debug/`. Install with `adb install -r` if needed.

## Next implementation steps

1. Add explicit ACK/state characteristic so tile/widget reflects actual hotspot status.
2. Add robust hotspot toggle semantics (query + true on/off branching) across ROM variants.
3. Persist pending-pair state across host service restarts (currently in-memory map).
4. Add optional bonded-device allowlist on host for an extra security layer.
