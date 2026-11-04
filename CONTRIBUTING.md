# Contributing to OffconAI

Thanks for your interest in contributing! Bug reports, feature requests, and pull requests are all welcome.

## Reporting bugs

Please use the [bug report template](https://github.com/zmxnpquryet-gif/OffconAI/issues/new?template=bug_report.yml) and include:

- Your device model and Android version
- The model you were using (name and quantization)
- Steps to reproduce
- If possible, adb logcat output

## Suggesting features

Use the [feature request template](https://github.com/zmxnpquryet-gif/OffconAI/issues/new?template=feature_request.yml).

## Building from source

1. Install Android Studio Ladybug or later with JDK 17
2. Android SDK 36, min SDK 24
3. Android NDK 27.2 + CMake 3.22 or later (required by the app's llama.cpp native bridge —
   a missing NDK fails the build with a CMake error, not a Kotlin error)
4. Clone the repo and open it in Android Studio
5. Generate the throwaway debug keystore once (it is gitignored, so a fresh clone
   cannot sign otherwise — CI does the same in `.github/workflows/android.yml`):

```bash
keytool -genkeypair -v -keystore debug.keystore \
  -storepass android -alias androiddebugkey -keypass android \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Android Debug,O=Android,C=US"
```

6. Let Gradle sync finish, then build:

```bash
# Debug compile
./gradlew :app:compileDebugKotlin

# Run local unit tests
./gradlew :app:testDebugUnitTest

# Desktop transport tests and distributable runtime
./gradlew :desktop:test :desktop:installDist

# Release bundle / APK
./gradlew assembleRelease
```

## Pull requests

- Keep changes focused; one feature or fix per PR
- Make sure `./gradlew :app:testDebugUnitTest :desktop:test` passes
- Follow the existing Kotlin code style
- Update the English, Korean, and Chinese README files if you change user-facing behavior

## Code of conduct

Be respectful and constructive. Any harassment or spam will be removed.
