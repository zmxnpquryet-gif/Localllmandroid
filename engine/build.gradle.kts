// Versions come from the root `apply false` declarations.
// NOTE: no explicit kotlin.android — AGP 9 auto-applies KGP when on the classpath;
// declaring it again registers the `kotlin` extension twice and fails the build.
plugins {
  alias(libs.plugins.android.library)
}

android {
  namespace = "com.localllm.engine"
  compileSdk = 36
  defaultConfig {
    minSdk = 24
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  // Pure Kotlin + java.nio: no Compose, no resources, no manifest components.
}

dependencies {
  testImplementation(libs.junit)
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
  // Real-model tests (opt-in via LOCALENGINE_MODEL) decode hundreds of MB.
  maxHeapSize = "5g"
}
