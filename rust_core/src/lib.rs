//! rust_core — user-space file operations, archives and line-indexed text
//! reading, exposed to Kotlin through JNI (`cdylib`, `librust_core.so`).
//!
//! Every JNI entry point takes / returns JSON strings so the Kotlin side
//! only needs one marshalling convention:
//!
//! ```json
//! {"ok": true, "data": ...}        // success
//! {"ok": false, "error": "..."}    // failure
//! ```
//!
//! The pure-Rust core (`files`, `archives`, `text`) is independent of JNI
//! and is covered by the unit tests in `tests/`.

pub mod archives;
pub mod files;
pub mod text;

mod jni_layer;

pub use files::FileEntry;
pub use text::{IndexedLine, LineIndex, TextPage};
