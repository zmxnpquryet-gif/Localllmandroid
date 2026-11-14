plugins {
  base
}

val cargoExecutable = if (System.getProperty("os.name").lowercase().contains("win")) "cargo.exe" else "cargo"
val binaryName = if (System.getProperty("os.name").lowercase().contains("win")) "offcon-desktop.exe" else "offcon-desktop"

val cargoTest = tasks.register<Exec>("cargoTest") {
  group = "verification"
  description = "Runs Rust unit and transport tests for OffconAI Desktop."
  workingDir = projectDir
  commandLine(cargoExecutable, "test")
}

val cargoBuildRelease = tasks.register<Exec>("cargoBuildRelease") {
  group = "build"
  description = "Builds the native Rust + Slint release binary for OffconAI Desktop."
  mustRunAfter(cargoTest)
  workingDir = projectDir
  commandLine(cargoExecutable, "build", "--release")
}

val installDist = tasks.register<Copy>("installDist") {
  group = "distribution"
  description = "Stages the compiled native Rust + Slint desktop binary into build/install/desktop."
  dependsOn(cargoBuildRelease)
  from(layout.projectDirectory.file("target/release/$binaryName"))
  into(layout.buildDirectory.dir("install/desktop/bin"))
}

val runDesktop = tasks.register<Exec>("run") {
  group = "application"
  description = "Runs the Rust + Slint desktop workspace."
  workingDir = projectDir
  commandLine(cargoExecutable, "run")
}

tasks.register("test") {
  group = "verification"
  description = "Alias for cargoTest."
  dependsOn(cargoTest)
}

tasks.named("check") {
  dependsOn("test")
}

tasks.named("assemble") {
  dependsOn(installDist)
}
