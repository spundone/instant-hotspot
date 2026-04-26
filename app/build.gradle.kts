import org.gradle.api.tasks.bundling.Zip
import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.spandan.instanthotspot"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.spandan.instanthotspot"
        minSdk = 29
        targetSdk = 35
        versionCode = 10
        versionName = "0.6.2"
        val gitSha = providers.exec {
            commandLine("git", "rev-parse", "--short=8", "HEAD")
        }.standardOutput.asText.get().trim().ifBlank { "dev" }
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
        buildConfigField("String", "GITHUB_OWNER", "\"spundone\"")
        buildConfigField("String", "GITHUB_REPO", "\"instant-hotspot\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Local / OSS convenience so assembleRelease and Magisk zip do not need a
            // separate keystore. Override in CI or for Play.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(project(":tethering-impl"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    implementation("androidx.viewpager2:viewpager2:1.1.0")

    testImplementation("junit:junit:4.13.2")
}

/** The git `magisk_module/` tree is a template: no APK. These checks catch empty hand-zips. */
fun verifyStagedApk(stagedModuleRoot: java.io.File) {
    val f = stagedModuleRoot.resolve("system/priv-app/InstantHotspot/InstantHotspot.apk")
    if (!f.isFile) {
        throw org.gradle.api.GradleException(
            "Magisk stage has no app at $f. " +
                "The repo `magisk_module/` directory does not include the APK. " +
                "Run :app:packageRelease (or :app:packageMagiskModuleRelease), not a plain zip of `magisk_module/`. " +
                "Or run :app:syncReleaseApkIntoMagiskTemplate, then zip that folder."
        )
    }
    if (f.length() < 50_000L) {
        throw org.gradle.api.GradleException("Staged InstantHotspot.apk too small or corrupt: $f")
    }
}

fun verifyMagiskZipContainsApk(archive: java.io.File) {
    if (!archive.isFile) {
        throw org.gradle.api.GradleException("Expected Magisk zip at ${archive.absolutePath}")
    }
    ZipFile(archive).use { zf ->
        val e = zf.getEntry("system/priv-app/InstantHotspot/InstantHotspot.apk")
        requireNotNull(e) {
            "Magisk zip is missing system/priv-app/InstantHotspot/InstantHotspot.apk. " +
                "The git `magisk_module/` folder is template-only. Build with :app:packageMagiskModuleRelease."
        }
        require(e.size > 50_000) {
            "APK in zip is too small (${e.size} bytes), build may be corrupt: ${archive.name}"
        }
    }
}

val magiskModuleDir = rootProject.layout.projectDirectory.dir("magisk_module")
val magiskStagingDebug = layout.buildDirectory.dir("magisk-staging-debug")
val magiskStagingRelease = layout.buildDirectory.dir("magisk-staging-release")

// Stage in build/ so debug and release zips do not share mutable outputs in magisk_module/ (Gradle 8 validation).
tasks.register<Copy>("stageMagiskDebug") {
    group = "distribution"
    description = "Assemble a debug flashable module tree under app/build/magisk-staging-debug"
    // packageDebug materializes the APK; assembleDebug alone is not a formal output dependency for the file
    dependsOn("packageDebug")
    duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.FAIL
    from(magiskModuleDir) {
        exclude("system/priv-app/InstantHotspot/InstantHotspot.apk", "**/InstantHotspot.apk")
    }
    from(layout.buildDirectory.dir("outputs/apk/debug")) {
        include("*.apk")
        into("system/priv-app/InstantHotspot")
        rename { "InstantHotspot.apk" }
    }
    into(magiskStagingDebug)
    doLast { verifyStagedApk(magiskStagingDebug.get().asFile) }
}

tasks.register<Zip>("packageMagiskModule") {
    group = "distribution"
    description = "Debug APK → magisk/ tree → flashable zip (app/build/dist/InstantHotspot-magisk.zip)"
    dependsOn("stageMagiskDebug")
    destinationDirectory.set(layout.buildDirectory.dir("dist"))
    archiveFileName.set("InstantHotspot-magisk.zip")
    from(magiskStagingDebug)
    doLast { verifyMagiskZipContainsApk(archiveFile.get().asFile) }
}

val magiskOutRelease = rootProject.layout.buildDirectory.dir("magisk-release-out")

tasks.register<Copy>("stageMagiskRelease") {
    group = "distribution"
    description = "Assemble a release flashable module tree under app/build/magisk-staging-release"
    dependsOn("packageRelease")
    duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.FAIL
    from(magiskModuleDir) {
        exclude("system/priv-app/InstantHotspot/InstantHotspot.apk", "**/InstantHotspot.apk")
    }
    from(layout.buildDirectory.dir("outputs/apk/release")) {
        include("*.apk")
        into("system/priv-app/InstantHotspot")
        rename { "InstantHotspot.apk" }
    }
    into(magiskStagingRelease)
    doLast { verifyStagedApk(magiskStagingRelease.get().asFile) }
}

tasks.register<Zip>("packageMagiskModuleRelease") {
    group = "distribution"
    description = "Release APK → magisk zip (root build/magisk-release-out/InstantHotspot-magisk-release.zip)"
    dependsOn("stageMagiskRelease")
    destinationDirectory.set(magiskOutRelease)
    archiveFileName.set("InstantHotspot-magisk-release.zip")
    from(magiskStagingRelease)
    doLast { verifyMagiskZipContainsApk(archiveFile.get().asFile) }
}

/**
 * Copy the **built** app into `magisk_module/.../InstantHotspot.apk` (gitignored) so you can zip
 * `magisk_module/` yourself; the real app is the Gradle output, not a stub.
 */
tasks.register<Copy>("syncReleaseApkIntoMagiskTemplate") {
    group = "distribution"
    description = "Assemble release APK and copy to magisk_module/.../InstantHotspot/InstantHotspot.apk (gitignored)"
    dependsOn("packageRelease")
    from(layout.buildDirectory.dir("outputs/apk/release")) {
        include("*.apk")
        rename { "InstantHotspot.apk" }
    }
    into(magiskModuleDir.dir("system/priv-app/InstantHotspot"))
    doLast { verifyStagedApk(magiskModuleDir.asFile) }
}

tasks.register<Copy>("syncDebugApkIntoMagiskTemplate") {
    group = "distribution"
    description = "Assemble debug APK and copy to magisk_module/.../InstantHotspot/InstantHotspot.apk (gitignored)"
    dependsOn("packageDebug")
    from(layout.buildDirectory.dir("outputs/apk/debug")) {
        include("*.apk")
        rename { "InstantHotspot.apk" }
    }
    into(magiskModuleDir.dir("system/priv-app/InstantHotspot"))
    doLast { verifyStagedApk(magiskModuleDir.asFile) }
}
