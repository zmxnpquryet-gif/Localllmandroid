plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
}

val releaseKeystoreFile = file(System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks")
val releaseStorePassword = System.getenv("STORE_PASSWORD")
val releaseKeyPassword = System.getenv("KEY_PASSWORD")
val hasReleaseSigning = releaseKeystoreFile.isFile &&
  !releaseStorePassword.isNullOrBlank() && !releaseKeyPassword.isNullOrBlank()

val validateReleaseCredentials = tasks.register("validateReleaseCredentials") {
  // Reads process environment at execution time: not configuration-cache safe by
  // design (a cached pass/fail would be a lie when credentials change).
  notCompatibleWithConfigurationCache("validates release signing credentials from the environment at execution time")
  doLast {
    val ksFile = file(System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks")
    val storePw = System.getenv("STORE_PASSWORD")
    val keyPw = System.getenv("KEY_PASSWORD")
    check(ksFile.isFile && !storePw.isNullOrBlank() && !keyPw.isNullOrBlank()) {
      "Release signing requires a keystore (KEYSTORE_PATH or my-upload-key.jks), STORE_PASSWORD and KEY_PASSWORD. Debug signing is never used for release builds."
    }
  }
}

tasks.configureEach {
  if (name == "preReleaseBuild") dependsOn(validateReleaseCredentials)
}

android {
  namespace = "com.localllm.android"
  compileSdk { version = release(36) { minorApiLevel = 1 } }
  defaultConfig {
    applicationId = "com.localllm.android"
    minSdk = 24
    targetSdk = 36
    versionCode = 15
    versionName = "1.4.0"
    ndk { abiFilters.addAll(listOf("arm64-v8a", "x86_64")) }
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }
  signingConfigs {
    if (hasReleaseSigning) {
      create("release") {
        storeFile = releaseKeystoreFile
        storePassword = releaseStorePassword
        keyAlias = "upload"
        keyPassword = releaseKeyPassword
      }
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }
  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.findByName("release")
    }
    debug { signingConfig = signingConfigs.getByName("debugConfig") }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.coil.compose)
  implementation(libs.converter.moshi)
  implementation(libs.llamacpp)
  implementation("com.google.ai.edge.litertlm:litertlm-android:0.17.0") {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-reflect")
  }
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(project(":engine"))
  // On-device speech recognition (sherpa-onnx, official AAR — no Maven artifact exists)
  implementation(files("libs/sherpa-onnx.aar"))
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  implementation(libs.retrofit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}

configurations.all {
  resolutionStrategy {
    // Pinned with evidence (not a fudge): without this, transitive deps drag
    // kotlin-stdlib to 2.3.20 (verified via dependencyInsight on
    // debugRuntimeClasspath) — NEWER than the KGP 2.2.10 compiler. A newer
    // stdlib under an older compiler is a latent NoSuchMethodError hazard for
    // those deps, so stdlib/reflect stay lockstep with the compiler.
    // Revisit in the KGP>=2.4 migration (same ticket as the metadata flag above).
    force("org.jetbrains.kotlin:kotlin-stdlib:2.2.10")
    force("org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.2.10")
    force("org.jetbrains.kotlin:kotlin-stdlib-jdk7:2.2.10")
    force("org.jetbrains.kotlin:kotlin-reflect:2.2.10")
  }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
  // REQUIRED, not a shortcut: bundled AARs ship newer Kotlin metadata than this
  // module's compiler — llamacpp-kotlin mv=2.3.0, litertlm mv=2.4.0 vs KGP 2.2.10
  // (verified via javap @Metadata on both AARs). Without this flag the compiler
  // rejects those classes outright. Remove only after migrating KGP to >= 2.4,
  // which additionally needs compose-BOM/KSP/Room re-validation.
  compilerOptions.freeCompilerArgs.add("-Xskip-metadata-version-check")
}
