# Evoke

Evoke is an Android app container that imports APKs into a host-managed sandbox and launches them through isolated stub processes.

This project was developed with Codex GPT-5.4.

## What It Does

- Imports installed apps or local APK files into an internal managed space
- Launches imported apps through host-controlled stub activities and process slots
- Routes intents and deep links to imported packages
- Persists app metadata, instances, and granted permissions
- Hooks selected framework-facing behaviors such as package, activity, broadcast, content provider, and permission access
- Uses a native layer for low-level runtime work, I/O redirection, and native hook integration via Frida Gum

## Architecture

Evoke is split into three modules:

- `app`: host application, Jetpack Compose UI, navigation, test entry points, runtime receivers, and stub components
- `core-virtual`: app container runtime, AIDL services, metadata parsing, sandbox management, hooks, persistence, and app lifecycle orchestration
- `core-native`: JNI and C++ layer for native engine integration and I/O redirection

## Tech Stack

- Kotlin 2.0
- Android Gradle Plugin 8.5
- Jetpack Compose + Material 3
- Hilt for dependency injection
- Room for local persistence
- WorkManager for background import work
- AIDL for in-process and cross-process service boundaries
- JNI + C++20 + CMake for native runtime support
- Frida Gum for native-layer hook support
- Hidden API bypass support for selected system integrations
- JUnit, AndroidX Test, Espresso, and UI Automator for testing

## Requirements

- Android Studio with JDK 17
- Android SDK 35
- Minimum Android version: Android 12 (`minSdk 31`)
- NDK and CMake for building `core-native`

## Build

```bash
./gradlew :app:assembleDebug
```

Build app and instrumentation test APKs:

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
```

Run unit tests:

```bash
./gradlew :core-virtual:testDebugUnitTest
```

## Device Test

Install and run instrumentation tests:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.smartdone.vm.test/androidx.test.runner.AndroidJUnitRunner
```

## Project Layout

```text
app/            Host app and Compose UI
core-virtual/   Container runtime, hooks, AIDL services, persistence
core-native/    Native runtime bridge and I/O redirection
```

## Status

This project is experimental and focused on container runtime behavior, process bootstrapping, app import, and intent routing on modern Android.

## Compatibility

At this stage, Evoke has only been verified to work on Android 16. Other Android versions have not been tested yet.
