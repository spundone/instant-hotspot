This directory must contain InstantHotspot.apk for a flashable module.

The real APK is never committed to git. Use one of these:

1) Full zip (recommended)
   From repo root:
     ./gradlew :app:packageMagiskModuleRelease
   Output:
     build/magisk-release-out/InstantHotspot-magisk-release.zip
   (Debug: :app:packageMagiskModule → app/build/dist/InstantHotspot-magisk.zip)

2) Put the APK in this tree for a manual zip
   ./gradlew :app:syncReleaseApkIntoMagiskTemplate
   (or :app:syncDebugApkIntoMagiskTemplate)
   Then zip the whole magisk_module/ folder and flash in Magisk.

Reboot after flashing.
