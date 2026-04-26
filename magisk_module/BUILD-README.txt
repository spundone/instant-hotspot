1) Uninstall the user (Play/sideload) copy of the app on the host phone if the signing key differs, or the package may not upgrade to this priv version cleanly.

2) Staging: APKs and final trees live under app/build/ (the magisk_module/ folder in the repo
   is only a template; the Gradle tasks copy it and inject InstantHotspot.apk).

3) Use JDK 17 or 21 for Gradle (not JDK 25+ with the current Gradle 8.7 wrapper; see top-level README if you see a bare *25.0.2* error).

4) From the project root, build a flashable zip (choose one):
   - Debug:  ./gradlew :app:packageMagiskModule
   - Release: ./gradlew :app:packageMagiskModuleRelease
     (release is signed with the debug keystore in this project for local/Magisk use; override in CI/Play)

5) Zips: app/build/dist/InstantHotspot-magisk.zip (debug) and
         build/magisk-release-out/InstantHotspot-magisk-release.zip (release)

6) In Magisk: Install from storage -> pick the zip, reboot.

7) After reboot, the app is under /data/adb/modules/instanthotspot/... with marker file ih_marks_magisk
   for in-app Magisk detection (with root). TETHER_PRIVILEGED is granted if the ROM enforces
   the standard privapp allowlist for Magisk /system mirrors.
