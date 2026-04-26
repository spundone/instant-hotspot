plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// Compile-only stand-ins for types missing from the public android.jar in some SDKs.
// Do not add runtime dependencies on this from the app: only compileOnly in tethering-impl.
