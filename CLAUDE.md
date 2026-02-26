# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Status

This is the **deprecated** ExoPlayer v2 repository (last release: `2.19.1`). Active development has moved to [AndroidX Media3](https://github.com/androidx/media). New contributions and bug reports should go there.

## Build Environment

**Java 17 is required.** Gradle 7.4.2 supports Java 8–17 only; Android Studio's bundled JBR (Java 21) will fail.

```bash
export JAVA_HOME="C:/Program Files/Microsoft/jdk-17.0.18.8-hotspot"
export PATH="$JAVA_HOME/bin:$PATH"
```

A `local.properties` file must exist at `ExoPlayer/local.properties`:
```
sdk.dir=C\:\\Users\\dex\\AppData\\Local\\Android\\Sdk
```

## Build Commands

All commands run from the `ExoPlayer/` directory with Java 17 set (see above).

```bash
# Build the demo app (standard flavor — use this, not :demo:assembleDebug)
./gradlew :demo:assembleNoDecoderExtensionsDebug

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
```

Build output goes to `<module>/buildout/` (configured via `gradle.properties` `buildDir=buildout`).

### Demo APK location
```
ExoPlayer/demos/main/buildout/outputs/apk/noDecoderExtensions/debug/demo-noDecoderExtensions-debug.apk
```

### Demo build flavors
The `:demo` module has two flavors on the `decoderExtensions` dimension:
- **`noDecoderExtensions`** — standard build, no pre-built native libs needed. Use this.
- **`withDecoderExtensions`** — includes av1/ffmpeg/flac/opus/vp9/rtmp extensions; requires pre-built `.so` files not present in this repo.

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

## Demo App Architecture

The main demo (`demos/main/`) is a standalone app that exercises the full library stack. Key classes:

- **`SampleChooserActivity`** — entry point; loads sample lists from JSON assets and the network
- **`PlayerActivity`** — hosts playback; wires `ExoPlayer` → `StyledPlayerView`; handles DRM, IMA ads, downloads, and track selection
- **`DemoUtil`** — factory for `DataSource`, `RenderersFactory`, and `DownloadManager` (singleton pattern)
- **`DemoDebugTextViewHelper`** — extends `DebugTextViewHelper`; on every 1 s refresh, prepends FPS, CPU, memory, network, media format/state/position, URL, DRM scheme/level, and key server URL to the on-screen overlay; also logs a `ExoDemo`-tagged logcat stats block (min/max/mean/median per metric) every ~10 samples and a final block on `STATE_ENDED`/`STATE_IDLE`
- **`DownloadTracker`** — tracks download state and provides `MediaSource` wrappers for offline playback
- **`IntentUtil`** — converts deep-link `Intent` extras into `MediaItem` lists

`PlayerActivity` creates `DemoDebugTextViewHelper` in `onStart()` and starts/stops it alongside the player lifecycle.

## Version / SDK Constants

All SDK versions and dependency versions are centralized in `constants.gradle`:
- `minSdkVersion = 16`, `compileSdkVersion = 33`, `targetSdkVersion = 30`
- `releaseVersion = '2.19.1'`

Module prefix logic (`exoplayerModulePrefix`) allows embedding the entire project as a subproject in another build.
