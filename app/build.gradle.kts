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

android {
    namespace = "com.cocakova.charon"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.cocakova.charon"
        minSdk = 24
        targetSdk = 36
        versionCode = 8
        versionName = "0.5.0"
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
