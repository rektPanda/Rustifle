# Hello World — Kotlin + Jetpack Compose + Rust core

Minimal Android app: a tabbed Material 3 UI — **Home** (centred
**"Hello World"** + live Rust status), **Files** (file browser),
**Bookmarks**, **Editor** (monospace text / code editor), and
**Viewer** (PDF / image / audio / video) — all driven by the Rust
native library.

## Stack

- Kotlin 2.0.20, Android Gradle Plugin 8.5.2, Gradle 8.7
- Jetpack Compose BOM 2024.09.00, Material 3
- `compileSdk` / `targetSdk` 34, `minSdk` 24, Java 17
- Package: `com.example.helloworld`
- **Rust** (`rust_core/`, `cdylib` via JNI, `librust_core.so`):
  file ops, zip / tar.gz / 7z archives, line-indexed text reading
- **UI:** `FileBrowserViewModel` + Compose screens in
  `app/src/main/java/com/example/helloworld/ui/` (lifecycle-viewmodel-compose,
  material-icons-extended, Coil for images, media3 ExoPlayer for audio/video)

## Build locally

```bash
./gradlew assembleDebug
```

The APK is at `app/build/outputs/apk/debug/app-debug.apk`.

Requires JDK 17 and the Android SDK (API 34). Android Studio will
prompt to install both on first open.

That build alone does **not** package the Rust library — the app runs,
but the demo line reports "Rust: librust_core.so not in this build".
To include it, build the native targets first (needs a Rust toolchain
with `cargo-ndk` and an Android NDK):

```bash
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/27.0.12077973
./gradlew assembleDebug -PbuildRust
```

## Rust core (`rust_core/`)

See [rust_core/README.md](rust_core/README.md). In short:

- **Files:** `list` (recursive), `copy` (file / dir tree), `move`
  (rename, copy+delete fallback), `delete`, `search` (filename,
  case-insensitive), `sha256` (streamed)
- **Archives:** compress / extract / list entries for **zip**,
  **tar.gz** and **7z** (zip-slip guarded on extract)
- **Text:** `line_count`, `read_lines` (1-based), plus a persistent
  `.rlidx` line index so large logs can be read by seeking straight
  to a line (`create_line_index` / `read_lines_indexed`), and
  `write_text` (the editor's Save)
- **Kotlin API:** `app/src/main/java/com/example/helloworld/RustBridge.kt`
  — typed wrappers over the JNI surface; every native call returns
  `{"ok":true,"data":...}` / `{"ok":false,"error":"..."}` JSON and
  failures throw `RustBridgeException`
- **Tests:** `cargo test` in `rust_core/` — 5 integration tests
  (file ops + SHA-256 known vector, text + line index, write_text
  round-trip, zip/tar.gz/7z round-trips, format detection / errors)

## CI

`.github/workflows/android.yml` runs on every push / PR to `main`:

1. JDK 17 + Android SDK + NDK 27.0.12077973
2. Rust stable + the four Android targets + `cargo-ndk` 3.5.4
3. `cargo test` in `rust_core/` (host tests)
4. `cargo ndk … build --release` for
   `arm64-v8a` / `armeabi-v7a` / `x86_64` / `x86`, staged into
   `app/src/main/jniLibs/<abi>/librust_core.so` (JNI symbols checked)
5. `./gradlew assembleDebug`, uploads `app-debug.apk` as an artifact

## UI

Four tabs, state in `FileBrowserViewModel` (survives rotation):

- **Home** — Hello World + live Rust status (SHA-256 / line count of a
  demo file in cache), bookmark count, shortcuts to Files / Editor.
- **Files** — file browser rooted at the app's Files dir and Cache dir
  (root switcher, Up button, path bar). Tap a folder to enter it, tap a
  file to open it in the Editor. Search box (recursive filename search
  via `RustBridge.searchFiles`). Sorting: by Name / Size / Date,
  ascending / descending, dirs-first toggle. Per-row actions: bookmark,
  rename (via Rust `move`), delete (with confirmation). "New file"
  creates an empty file and opens it in the Editor.
- **Bookmarks** — folders and files starred in Files, persisted in
  SharedPreferences. Tap a folder to open it in Files, a file to open
  it in the Editor; X removes the bookmark.
- **Editor** — monospace (`FontFamily.Monospace`) text / code editor
  with a line-number gutter (shared scroll, 20sp line height), line
  count + SHA-256 in the header, dirty marker, Save / Reload / Close.
  Files are loaded through RustBridge with paged `readLines`
  (up to 5,000 lines); **Save is disabled until the whole file is
  loaded, so a partial preview can never truncate a file**. Go to line
  jumps with the Rust line index (`createLineIndex` +
  `readLinesIndexed`) and shows a 200-line page (read-only until reload).

When `librust_core.so` is not packaged (plain `./gradlew assembleDebug`
without cargo-ndk), the ViewModel falls back to `java.io.File` for the
same operations, so the UI stays usable; CI builds always use Rust.

## Viewers

Tapping a file in Files (or a bookmark) routes it by extension —
`FileBrowserViewModel.viewerKindFor()` — to the Viewer tab:

- **PDF** (`.pdf`) — `PdfViewerScreen.kt`, framework `PdfRenderer`:
  one page at a time rendered to a bitmap at 2x, Prev / Next,
  page indicator, pinch-to-zoom / pan and a zoom slider (1x–4x).
- **Images** (`.png` `.jpg` `.jpeg` `.webp` `.gif` `.bmp`) —
  `ImageViewerScreen.kt`, Coil `AsyncImage` with pinch-to-zoom / pan
  and a zoom slider (1x–5x), Reset to fit.
- **Audio** (`.mp3` `.wav` `.ogg` `.flac` `.m4a` `.aac` `.opus`) and
  **video** (`.mp4` `.mkv` `.webm` `.mov` `.avi` `.3gp`) —
  `MediaViewerScreen.kt`, `androidx.media3` ExoPlayer with the standard
  `PlayerView` controller; the player is released when the screen
  leaves composition and paused when the app goes to background.
- Text / code files still open in the Editor; anything else opens
  there as plain text.

## Structure

```
settings.gradle.kts
build.gradle.kts            # root: plugin versions
gradle.properties
gradlew / gradle/wrapper/   # Gradle wrapper (8.7)
app/
  build.gradle.kts          # app module, Compose enabled, jniLibs + buildRust task
  src/main/AndroidManifest.xml
  src/main/java/com/example/helloworld/
    MainActivity.kt         # tabbed Scaffold: Home / Files / Bookmarks / Editor / Viewer
    RustBridge.kt           # typed Kotlin API over rust_core (+ writeTextFile)
    ui/
      FileBrowserViewModel.kt  # browser/sort/search/bookmarks/editor state
      FileBrowserScreen.kt     # browser tab (sort menu, search, rename/delete)
      BookmarksScreen.kt       # bookmarks tab (SharedPreferences)
      EditorScreen.kt          # monospace editor + line numbers + Go to line
      ViewerScreen.kt          # viewer tab dispatcher (PDF/image/audio/video)
      PdfViewerScreen.kt       # PdfRenderer: pages, 2x bitmap, pinch zoom
      ImageViewerScreen.kt     # Coil AsyncImage, pinch zoom
      MediaViewerScreen.kt     # media3 ExoPlayer + PlayerView
      HomeScreen.kt            # Hello World + Rust status
  src/main/res/values/strings.xml
rust_core/
  Cargo.toml                # cdylib + rlib, jni/sha2/zip/tar/flate2/sevenz-rust2
  src/{lib,files,archives,text,jni_layer}.rs
  tests/core_tests.rs
.github/workflows/android.yml
```
