// Pure Kotlin/JVM module — the terminal emulation core. ZERO Android imports allowed here:
// everything in this module must be unit-testable on a plain JVM (that's where the
// correctness of the emulator lives — corpus goldens, fuzz totality, throughput).
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
}

tasks.withType<Test>().configureEach {
    // Golden regeneration: ./gradlew :terminal-core:test -Dcharon.regenGoldens=<dir>
    systemProperty("charon.regenGoldens", System.getProperty("charon.regenGoldens") ?: "")
}
