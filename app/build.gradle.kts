import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ksp)
}

// Release signing comes from local.properties (never committed):
//   charon.keystore=/absolute/path/to/release.keystore
//   charon.keystore.password=…
//   charon.key.alias=…
//   charon.key.password=…
// Absent those, release builds fall back to the debug keystore (sideload/dev convenience).
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val releaseKeystorePath: String? = localProps.getProperty("charon.keystore")

// Premium overlay: when a sibling checkout named charon-obol sits beside this
// repo, an "obol" flavor appears with its sources. The public repo is complete
// without it — foss is the whole free app and the only flavor most builders see.
val obolDir = rootProject.file("../charon-obol")

android {
    namespace = "com.cocakova.charon"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.cocakova.charon"
        minSdk = 24
        targetSdk = 36
        versionCode = 22
        versionName = "1.1.2"
    }

    // ---- Tiers ------------------------------------------------------------
    // foss = the whole free Charon; everything functional lives here, always.
    // obol = the ferryman's thanks for a coin: cosmetics only, built from the
    // private sibling checkout and handed over by ADB — never through the
    // public repo or its releases. Same applicationId and signing key, so a
    // foss install and an obol install update over each other in place.
    flavorDimensions += "tier"
    productFlavors {
        create("foss") {
            dimension = "tier"
            isDefault = true
        }
        if (obolDir.exists()) {
            create("obol") {
                dimension = "tier"
                versionNameSuffix = "+obol"
            }
        }
    }
    sourceSets {
        // Built-in Kotlin (no kotlin-android plugin here) compiles only what the
        // kotlin source dirs list — java.srcDir alone would leave .kt files unseen.
        if (obolDir.exists()) {
            getByName("obol") {
                java.srcDir(obolDir.resolve("src/main/java"))
                kotlin.srcDir(obolDir.resolve("src/main/java"))
            }
            getByName("testObol") {
                java.srcDir(obolDir.resolve("src/test/java"))
                kotlin.srcDir(obolDir.resolve("src/test/java"))
            }
        }
    }

    signingConfigs {
        if (releaseKeystorePath != null) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = localProps.getProperty("charon.keystore.password")
                keyAlias = localProps.getProperty("charon.key.alias")
                keyPassword = localProps.getProperty("charon.key.password")
            }
        }
    }

    buildTypes {
        release {
            // Minification stays OFF: sshj + BouncyCastle are reflection/provider-heavy and an
            // untested minified build is worse than a slightly larger honest one.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (releaseKeystorePath != null) signingConfigs.getByName("release")
                else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = true
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
  implementation(project(":terminal-core"))

  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.biometric)
  // Overrides the ancient fragment that biometric drags in; see libs.versions.toml.
  implementation(libs.androidx.fragment.ktx)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.material.icons.extended)
  debugImplementation(libs.androidx.compose.ui.tooling)

  // Coroutines
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.coroutines.android)

  // SSH engine: sshj needs the real BouncyCastle (Android's bundled BC is stripped);
  // CharonApp swaps the provider at startup. slf4j-simple routes sshj logs to logcat
  // via System.out.
  implementation(libs.sshj)
  implementation(libs.bouncycastle.bcprov)
  implementation(libs.slf4j.simple)

  // Serialization
  implementation(libs.kotlinx.serialization.json)

  // Host vault
  implementation(libs.room.runtime)
  implementation(libs.room.ktx)
  ksp(libs.room.compiler)

  // Local tests
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
}
