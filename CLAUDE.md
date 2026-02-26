# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Status

This is the **deprecated** ExoPlayer v2 repository (last release: `2.19.1`). Active development has moved to [AndroidX Media3](https://github.com/androidx/media). New contributions and bug reports should go there.

## Build Commands

```bash
# Build all modules
./gradlew build

# Run unit tests (Robolectric) for all modules
./gradlew test

# Run unit tests for a specific module
./gradlew :library-core:test

# Run a single test class
./gradlew :library-core:test --tests "com.google.android.exoplayer2.ExoPlayerTest"

# Run a single test method
./gradlew :library-core:test --tests "com.google.android.exoplayer2.ExoPlayerTest.testMethodName"

# Run instrumentation tests (requires connected device/emulator)
./gradlew connectedAndroidTest

# Build a specific module
./gradlew :library-core:assembleRelease

# Build the main demo app
./gradlew :demo:assembleDebug
```

Build output goes to the `buildout/` directory (configured via `gradle.properties`).

## Module Architecture

The project is a multi-module Gradle project. The core dependency hierarchy flows as:

```
library-common  (Player interface, Format, MediaItem, Timeline)
    ↑
library-datasource  (DataSource, HttpDataSource, cache)
library-decoder     (Decoder interface)
library-extractor   (Extractor, container parsers)
library-database    (SQLite-backed download state)
    ↑ (all of above)
library-core        (ExoPlayer, Renderers, MediaSource, TrackSelector)
    ↑
library-dash / library-hls / library-rtsp / library-smoothstreaming
library-ui          (PlayerView, StyledPlayerView, PlayerControlView)
library-transformer (media transcoding/editing)
library-effect      (video effects/GL shaders)
library-muxer       (muxing output)
library             (all-in-one: depends on everything above)
```

Extensions live in `extensions/` and are optional add-ons: codec extensions (`av1`, `ffmpeg`, `flac`, `opus`, `vp9`), network extensions (`cronet`, `okhttp`, `rtmp`), and platform extensions (`cast`, `ima`, `leanback`, `media2`, `mediasession`, `workmanager`).

## Key Abstractions

**Playback pipeline (library-core):**
- `SimpleExoPlayer` / `ExoPlayer` — the primary public API; `ExoPlayerImpl` is the implementation, `ExoPlayerImplInternal` is the background thread core
- `Renderer` — processes a single track type (audio, video, text, metadata)
- `MediaSource` → `MediaPeriod` → `SampleStream` — the media loading abstraction chain
- `TrackSelector` / `DefaultTrackSelector` — selects which tracks to play
- `LoadControl` / `DefaultLoadControl` — controls buffering behaviour
- `RenderersFactory` / `DefaultRenderersFactory` — creates `Renderer` instances

**Data loading (library-datasource):**
- `DataSource` / `DataSpec` — core abstraction for reading bytes from any URI
- `DefaultDataSource` → delegates to `HttpDataSource`, `FileDataSource`, `ContentDataSource`, etc.
- Cache lives under `upstream/cache/`

**Format parsing (library-extractor):**
- `Extractor` — parses a container format, outputs to `ExtractorOutput` / `TrackOutput`
- `DefaultExtractorsFactory` — registers all built-in extractors
- Supported containers: MP4, MKV, TS, FLV, OGG, WAV, MP3, FLAC, ADTS, AMR, AVI, JPEG

**Adaptive streaming:**
- `DashMediaSource` (DASH), `HlsMediaSource` (HLS), `SsMediaSource` (SmoothStreaming), `RtspMediaSource` (RTSP) — each has its own `MediaPeriod` and `ChunkSource`

**UI (library-ui):**
- `StyledPlayerView` / `PlayerView` — full-featured player UI
- `PlayerControlView` / `StyledPlayerControlView` — standalone controls
- `SubtitleView` — subtitle rendering

**Transformation (library-transformer):**
- `Transformer` — top-level API for transcoding/editing
- `EditedMediaItem` / `Composition` — describe the edit to perform
- `AssetLoader` → `SamplePipeline` → `Muxer` — the internal pipeline

## Testing Infrastructure

- **Unit tests** use Robolectric and live in `src/test/` within each module
- **Instrumentation tests** live in `src/androidTest/` and require a device
- `testutils/` — shared fakes and test helpers (e.g., `FakeMediaSource`, `FakeRenderer`, `ActionSchedule`)
- `robolectricutils/` — Robolectric-specific helpers
- `testdata/` — shared test asset files, mounted as assets in test source sets

## Version / SDK Constants

All SDK versions and dependency versions are centralized in `constants.gradle`:
- `minSdkVersion = 16`, `compileSdkVersion = 33`, `targetSdkVersion = 30`
- `releaseVersion = '2.19.1'`

Module prefix logic (`exoplayerModulePrefix`) allows embedding the entire project as a subproject in another build.
