import java.net.URI
import java.security.MessageDigest
import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
}

// Release signing credentials. Environment variables win (CI); a local, gitignored
// keystore.properties at the repository root is the fallback, so a local release
// build does not depend on a shell that has the secrets exported. Expected keys:
//   storeFile=my-upload-key.jks
//   storePassword=...
//   keyAlias=upload
//   keyPassword=...
val keystorePropertiesFile = rootProject.file("keystore.properties")

fun keystoreProperties(): Properties = Properties().apply {
  if (keystorePropertiesFile.isFile) keystorePropertiesFile.inputStream().use { load(it) }
}

fun signingCredential(environmentVariable: String, propertyName: String): String? =
  System.getenv(environmentVariable)?.takeIf { it.isNotBlank() }
    ?: keystoreProperties().getProperty(propertyName)?.takeIf { it.isNotBlank() }

fun resolveKeystoreFile(path: String?): File = when {
  path.isNullOrBlank() -> rootProject.file("my-upload-key.jks")
  File(path).isAbsolute -> File(path)
  else -> rootProject.file(path)
}

val releaseKeystoreFile = resolveKeystoreFile(signingCredential("KEYSTORE_PATH", "storeFile"))
val releaseStorePassword = signingCredential("STORE_PASSWORD", "storePassword")
val releaseKeyPassword = signingCredential("KEY_PASSWORD", "keyPassword")
val releaseKeyAlias = signingCredential("KEY_ALIAS", "keyAlias") ?: "upload"
val hasReleaseSigning = releaseKeystoreFile.isFile &&
  !releaseStorePassword.isNullOrBlank() && !releaseKeyPassword.isNullOrBlank()

val validateReleaseCredentials = tasks.register("validateReleaseCredentials") {
  // Reads credentials at execution time: not configuration-cache safe by design
  // (a cached pass/fail would be a lie when the credentials change).
  notCompatibleWithConfigurationCache("validates release signing credentials at execution time")
  doLast {
    val ksFile = resolveKeystoreFile(signingCredential("KEYSTORE_PATH", "storeFile"))
    val storePw = signingCredential("STORE_PASSWORD", "storePassword")
    val keyPw = signingCredential("KEY_PASSWORD", "keyPassword")
    check(ksFile.isFile && !storePw.isNullOrBlank() && !keyPw.isNullOrBlank()) {
      "Release signing requires a keystore (KEYSTORE_PATH / keystore.properties storeFile, or my-upload-key.jks), " +
        "storePassword and keyPassword (env STORE_PASSWORD/KEY_PASSWORD or keystore.properties). " +
        "Debug signing is never used for release builds."
    }
  }
}

tasks.configureEach {
  if (name == "preReleaseBuild") dependsOn(validateReleaseCredentials)
}

// sherpa-onnx ships no Maven artifact, so its official release AAR (ARM/x86 native
// speech libraries, ~50 MB) is fetched on demand instead of being committed to git.
// The version and SHA-256 are pinned to the official release asset; bump both together.
// Values are declared inside the task so its action captures only serializable locals
// (script-level vals would drag the script/Project into the configuration cache).
val fetchSherpaAar = tasks.register("fetchSherpaAar") {
  group = "build setup"
  description = "Downloads the pinned sherpa-onnx Android AAR into app/libs when it is absent."
  val version = "1.13.8"
  val expectedSha256 = "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"
  val expectedBytes = 50_129_134L
  val archive = file("libs/sherpa-onnx.aar")
  val downloadUrl =
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$version/sherpa-onnx-$version.aar"
  outputs.file(archive)
  doLast {
    if (archive.isFile && archive.length() == expectedBytes) return@doLast
    archive.parentFile.mkdirs()
    val temp = File(archive.parentFile, "sherpa-onnx.aar.part")
    URI(downloadUrl).toURL().openStream().use { input ->
      temp.outputStream().use { output -> input.copyTo(output) }
    }
    val digest = MessageDigest.getInstance("SHA-256")
      .digest(temp.readBytes())
      .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    if (digest != expectedSha256) {
      temp.delete()
      error("sherpa-onnx AAR checksum mismatch (expected $expectedSha256, got $digest)")
    }
    if (archive.exists()) archive.delete()
    check(temp.renameTo(archive)) { "Could not move the downloaded AAR into app/libs" }
  }
}

tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(fetchSherpaAar) }

android {
  namespace = "com.localllm.android"
  compileSdk { version = release(36) { minorApiLevel = 1 } }
  defaultConfig {
    applicationId = "com.localllm.android"
    minSdk = 24
    targetSdk = 36
    versionCode = 16
    versionName = "1.5.0"
    ndk { abiFilters.addAll(listOf("arm64-v8a", "x86_64")) }
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }
  signingConfigs {
    if (hasReleaseSigning) {
      create("release") {
        storeFile = releaseKeystoreFile
        storePassword = releaseStorePassword
        keyAlias = releaseKeyAlias
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
