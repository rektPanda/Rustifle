# rust_core

User-space file operations, archives and line-indexed text reading for
the Hello World Android app, as a Rust `cdylib` (`librust_core.so`)
called from Kotlin through JNI (`RustBridge.kt`).

## Layout

```
Cargo.toml          # lib crate-type: ["cdylib", "rlib"]
src/
  lib.rs            # module map
  files.rs          # list, copy, move, delete, search, SHA-256
  archives.rs       # zip / tar.gz / 7z compress + extract (+ list)
  text.rs           # line count, read_lines, persistent line index
  jni_layer.rs      # thin JNI layer: JSON in, JSON out
tests/
  core_tests.rs     # host tests for the pure-Rust core (no Android)
```

## API (pure Rust)

```rust
files::list(path, recursive) -> Vec<FileEntry>
files::copy(src, dst) -> bytes copied
files::move_path(src, dst)          // rename, copy+delete fallback
files::delete(path, recursive)
files::search(root, query, recursive, max_results) -> Vec<FileEntry>
files::sha256(path) -> hex string
files::write_text(path, content) -> bytes written  // editor Save

archives::compress(format, sources, dest) -> ArchiveInfo
archives::extract(format, src, dest) -> ArchiveInfo
archives::list_archive(format, src) -> Vec<String>   // entry names
// ArchiveFormat: Zip | TarGz | SevenZ — parse("zip"|"tar.gz"|"7z"),
// or detect() from a file name.

text::line_count(path) -> u64
text::read_lines(path, start_line /* 1-based */, count) -> TextPage
text::create_line_index(path, index_path) -> total lines
text::read_lines_indexed(index_path, path, start_line, count) -> TextPage
```

The line index (`.rlidx` sidecar) stores the byte offset of every line,
so `read_lines_indexed` seeks straight to the requested line instead of
scanning from the top — the fast path for large logs. Rebuild it when the
file changes; an index built for another file is rejected.

Extract guards against zip-slip: entries that would escape the
destination directory are rejected.

## JNI contract

Every native function in `jni_layer.rs` is named
`Java_com_example_helloworld_RustBridge_<name>Native` and returns a JSON string:

```json
{"ok": true, "data": ...}
{"ok": false, "error": "..."}
```

Panics are caught and returned as error JSON — they never unwind
across FFI. The Kotlin side (`RustBridge.kt`) parses this JSON into
typed results and throws `RustBridgeException` on `"ok": false`.

## Build & test (host)

```bash
cargo test            # 5 integration tests: files, text/index, write_text, all 3 archive formats
cargo clippy --all-targets
cargo fmt --check
```

## Build for Android

```bash
# from the repo root, with ANDROID_NDK_HOME set:
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -t x86 build --release
# .so files: rust_core/target/<triple>/release/librust_core.so
```

CI does this automatically before Gradle (`.github/workflows/android.yml`)
and stages the `.so` files into `app/src/main/jniLibs/<abi>/`.
Locally, `./gradlew assembleDebug -PbuildRust` does the same via the
`buildRust` task in `app/build.gradle.kts`.
