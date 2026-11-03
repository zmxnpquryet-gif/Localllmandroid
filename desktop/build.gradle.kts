plugins {
  application
}

java {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
}

application {
  mainClass.set("ai.offcon.desktop.OffconAIDesktop")
}

dependencies {
  testImplementation(libs.junit)
}

tasks.test {
  useJUnit()
}

tasks.withType<JavaCompile>().configureEach {
  options.release.set(17)
}
