# OpenPixelCamera — Decisions

Open questions and architectural choices not yet locked in.

> **Agent note:** Keep this doc in sync as decisions are made. Move finalised decisions to `AGENTS.md` if they affect how code is written, or delete them if they become self-evident from the code.

## Open questions

- **Camera2 vs CameraX** — ✅ Locked: **Camera2** directly. Need manual exposure mode for `SENSOR_EXPOSURE_TIME` control.
- **GPU trail processing** — Real-time per-pixel compositing at 30fps needs OpenGL ES shaders. Confirm `GLSurfaceView` or custom `TextureView` approach before starting.
- **Video output** — `MediaRecorder` vs `MediaCodec`? Composited trail frames must be encoded. Depends on how rendering is wired (OpenGL surface → `Surface` → encoder).
- **Compose + OpenGL interop** — Compose `AndroidView` wrapping `GLSurfaceView` has known quirks (touch, lifecycle). Worth prototyping early.
- **Kotlin plugin** — `app/build.gradle.kts` doesn't explicitly declare the Kotlin plugin. May need `kotlin("android")` + Compose compiler config for Compose to work.
- **Dependencies not yet added** — Compose, CameraX, Material3, OpenGL are not in the version catalog.
