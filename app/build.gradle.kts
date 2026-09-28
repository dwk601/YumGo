plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

/**
 * OpenRouter key for photo AI, read at build time from the OPENROUTER_API_KEY environment
 * variable or Gradle property (e.g. `-POPENROUTER_API_KEY=...`, gradle.properties).
 * Provider-based so the configuration cache stays valid; blank means the analyzer reports
 * Unavailable and never uploads. Never commit a key.
 */
val openRouterApiKey: String = providers.environmentVariable("OPENROUTER_API_KEY")
  .orElse(providers.gradleProperty("OPENROUTER_API_KEY"))
  .orElse("")
  .get()

/** Quote a string for buildConfigField("String", ...) . */
fun String.toBuildConfigLiteral(): String = buildString {
  append('"')
  for (char in this@toBuildConfigLiteral) {
    when (char) {
      '\\' -> append("\\\\")
      '"' -> append("\\\"")
      '\n' -> append("\\n")
      '\r' -> append("\\r")
      '\t' -> append("\\t")
      else -> if (char < ' ') append("\\u%04x".format(char.code)) else append(char)
    }
  }
  append('"')
}

android {
    namespace = "com.dwk.yumgo"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.dwk.yumgo"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Empty when no key is supplied; the analyzer then reports Unavailable and never uploads.
        buildConfigField("String", "OPENROUTER_API_KEY", openRouterApiKey.toBuildConfigLiteral())
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)

  // CameraX capture. Compose viewfinder, not a wrapped PreviewView.
  implementation(libs.androidx.camera.core)
  implementation(libs.androidx.camera.camera2)
  implementation(libs.androidx.camera.lifecycle)
  implementation(libs.androidx.camera.compose)

  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)
}
