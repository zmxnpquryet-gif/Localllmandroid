// Versions come from the root `apply false` declarations.
// NOTE: no explicit kotlin.android — AGP 9 auto-applies KGP when on the classpath;
// declaring it again registers the `kotlin` extension twice and fails the build.
plugins {
  alias(libs.plugins.android.library)
}

android {
  namespace = "com.localllm.engine"
  compileSdk = 36
  ndkVersion = "27.2.12479018"
  defaultConfig {
    minSdk = 24
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    externalNativeBuild {
      cmake {
        cppFlags += "-std=c++17"
        abiFilters += listOf("arm64-v8a", "x86_64")
      }
    }
  }
  externalNativeBuild {
    cmake {
      path = file("src/main/cpp/CMakeLists.txt")
      version = "3.22.1"
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  // Kotlin + java.nio + JNI: no Compose, no resources, no manifest components.
}

dependencies {
  testImplementation(libs.junit)
  // Golden-harness tests parse llama.cpp golden JSON files (test-only).
  testImplementation(libs.json)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
  // Real-model tests keep the full dense weight set resident: e.g. Qwen3-30B-A3B
  // Q4_K_M needs ~5.6 GB (attn+embd+head, experts stay paged) plus scratch.
  maxHeapSize = "8g"
}
