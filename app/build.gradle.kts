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
        versionCode = 2
        versionName = "0.1.1"

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
}

dependencies {
    implementation(project(":tethering-impl"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

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
