# OpenPixelCamera

Single-module Android app (Kotlin). Package: `com.mitchelllustig.openpixelcamera`.

**Current state:** Empty project scaffold — no Activity, no layouts, no camera code yet.

## Build

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug          # build debug APK
./gradlew test                   # local unit tests (JUnit 4)
./gradlew connectedAndroidTest   # instrumented tests (requires device/emulator)
```

## Key facts

- **AGP 9.0.1**, compile/target SDK 36, min SDK 26, Java 11 source compat
- Single module `:app` — no multi-module setup
- Version catalog at `gradle/libs.versions.toml` — add new deps there, reference via `libs.*` in build scripts
- ProGuard/R8 disabled for now
- `AndroidManifest.xml` has no `<activity>` — must add one before the app can launch
- No Kotlin plugin explicitly declared in `app/build.gradle.kts` (only `com.android.application`) — Kotlin compilation comes implicitly via AGP
- **UI: Jetpack Compose** — no XML layouts. Camera preview and trail processing use Camera2 + OpenGL/Canvas underneath
- **Camera: Camera2 API** (not CameraX) — manual exposure mode for `SENSOR_EXPOSURE_TIME` control
- **Settings persistence:** All user-facing settings (sliders, toggles) must be persisted via `SharedPreferences` (`PREFS_NAME = "open_pixel_camera"`). Read defaults in `remember { mutableFloatStateOf(prefs.getFloat(...)) }`, write in the corresponding `LaunchedEffect`. Keys defined as `KEY_*` constants in `CameraScreen.kt`.

## Other docs

- `docs/DESIGN.md` — product design and feature specs
- `docs/DECISIONS.md` — open architectural questions. **Keep this in sync** — move finalised decisions here to `AGENTS.md` or delete when self-evident from code
