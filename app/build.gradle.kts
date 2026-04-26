import org.gradle.api.tasks.bundling.Zip

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
        versionCode = 7
        versionName = "0.5.0"
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

val magiskModuleDir = rootProject.layout.projectDirectory.dir("magisk_module")
val magiskStagingDebug = layout.buildDirectory.dir("magisk-staging-debug")
val magiskStagingRelease = layout.buildDirectory.dir("magisk-staging-release")

// Stage in build/ so debug and release zips do not share mutable outputs in magisk_module/ (Gradle 8 validation).
tasks.register<Copy>("stageMagiskDebug") {
    group = "distribution"
    description = "Assemble a debug flashable module tree under app/build/magisk-staging-debug"
    dependsOn("assembleDebug")
    from(magiskModuleDir) {
        exclude("system/priv-app/InstantHotspot/InstantHotspot.apk", "**/InstantHotspot.apk")
    }
    from(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk")) {
        into("system/priv-app/InstantHotspot")
        rename { "InstantHotspot.apk" }
    }
    into(magiskStagingDebug)
}

tasks.register<Zip>("packageMagiskModule") {
    group = "distribution"
    description = "Debug APK → magisk/ tree → flashable zip (app/build/dist/InstantHotspot-magisk.zip)"
    dependsOn("stageMagiskDebug")
    destinationDirectory.set(layout.buildDirectory.dir("dist"))
    archiveFileName.set("InstantHotspot-magisk.zip")
    from(magiskStagingDebug)
}

val magiskOutRelease = rootProject.layout.buildDirectory.dir("magisk-release-out")

tasks.register<Copy>("stageMagiskRelease") {
    group = "distribution"
    description = "Assemble a release flashable module tree under app/build/magisk-staging-release"
    dependsOn("assembleRelease")
    from(magiskModuleDir) {
        exclude("system/priv-app/InstantHotspot/InstantHotspot.apk", "**/InstantHotspot.apk")
    }
    from(layout.buildDirectory.file("outputs/apk/release/app-release.apk")) {
        into("system/priv-app/InstantHotspot")
        rename { "InstantHotspot.apk" }
    }
    into(magiskStagingRelease)
}

tasks.register<Zip>("packageMagiskModuleRelease") {
    group = "distribution"
    description = "Release APK → magisk zip (root build/magisk-release-out/InstantHotspot-magisk-release.zip)"
    dependsOn("stageMagiskRelease")
    destinationDirectory.set(magiskOutRelease)
    archiveFileName.set("InstantHotspot-magisk-release.zip")
    from(magiskStagingRelease)
}

/**
 * Copy the **built** app into `magisk_module/.../InstantHotspot.apk` (gitignored) so you can zip
 * `magisk_module/` yourself; the real app is the Gradle output, not a stub.
 */
tasks.register<Copy>("syncReleaseApkIntoMagiskTemplate") {
    group = "distribution"
    description = "Assemble release APK and copy to magisk_module/.../InstantHotspot/InstantHotspot.apk (gitignored)"
    dependsOn("assembleRelease")
    from(layout.buildDirectory.file("outputs/apk/release/app-release.apk")) {
        rename { "InstantHotspot.apk" }
    }
    into(magiskModuleDir.dir("system/priv-app/InstantHotspot"))
}

tasks.register<Copy>("syncDebugApkIntoMagiskTemplate") {
    group = "distribution"
    description = "Assemble debug APK and copy to magisk_module/.../InstantHotspot/InstantHotspot.apk (gitignored)"
    dependsOn("assembleDebug")
    from(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk")) {
        rename { "InstantHotspot.apk" }
    }
    into(magiskModuleDir.dir("system/priv-app/InstantHotspot"))
}
