1) Uninstall the user (Play/sideload) copy of the app on the host phone if the signing key differs, or the package may not upgrade to this priv version cleanly.

2) The real Instant Hotspot APK is **not** stored in git. Gradle builds the app and **injects** it into
   the Magisk module when you run the package tasks, or you can **sync** a built APK into
   `magisk_module/.../InstantHotspot/InstantHotspot.apk` (see README-APK.txt there; that path is gitignored).

3) Staging: full flashable trees and zips live under `app/build/` and `build/`; the
   `magisk_module/` folder is the template (privapp allowlist, module.prop, marker file) plus
   a generated or synced APK.

4) Use JDK 17 or 21 for Gradle (not JDK 25+ with the current Gradle 8.7 wrapper; see top-level README if you see a bare *25.0.2* error).

5) From the project root, build a **flashable zip** with the **current** build (recommended):
   - Debug:  ./gradlew :app:packageMagiskModule
   - Release: ./gradlew :app:packageMagiskModuleRelease
     (local release is signed with the project debug keystore by default; override in CI/Play)

   Optional — copy a built APK into the repo’s `magisk_module/` tree for a **manual** zip of that folder:
   - ./gradlew :app:syncReleaseApkIntoMagiskTemplate
   - ./gradlew :app:syncDebugApkIntoMagiskTemplate

6) Zips: app/build/dist/InstantHotspot-magisk.zip (debug) and
         build/magisk-release-out/InstantHotspot-magisk-release.zip (release)

7) In Magisk: Install from storage -> pick the zip, reboot.

8) After reboot, the app is under /data/adb/modules/instanthotspot/... with marker file ih_marks_magisk
   for in-app Magisk detection (with root). TETHER_PRIVILEGED is granted if the ROM enforces
   the standard privapp allowlist for Magisk /system mirrors.
