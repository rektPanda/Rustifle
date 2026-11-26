//! Thin JNI layer: JSON in, JSON out.
//!
//! Kotlin side is `com.example.helloworld.RustBridge` (a Kotlin `object`,
//! so the second JNI argument is the singleton instance, `JObject`).
//! Every function returns a JSON string:
//! `{"ok":true,"data":...}` or `{"ok":false,"error":"..."}`.
//! Panics are caught and turned into error JSON — never unwind across FFI.

use crate::{archives, files, text};
use jni::objects::{JObject, JString};
use jni::sys::{jboolean, jint, jstring};
use jni::JNIEnv;
use serde_json::{json, Value};
use std::path::PathBuf;

fn ok(data: Value) -> String {
    json!({"ok": true, "data": data}).to_string()
}

fn err_json(msg: impl Into<String>) -> String {
    json!({"ok": false, "error": msg.into()}).to_string()
}

fn to_json<T: serde::Serialize>(r: Result<T, String>) -> String {
    match r {
        Ok(v) => match serde_json::to_value(v) {
            Ok(data) => ok(data),
            Err(e) => err_json(e.to_string()),
        },
        Err(e) => err_json(e),
    }
}

/// Run `f` with the env, catching panics, and return its JSON as a jstring.
/// Written as an ordinary function (not a macro) so the closure parameter
/// `env` in each JNI function below is in the same hygiene scope as its body.
fn run_json<F>(env: &mut JNIEnv, f: F) -> jstring
where
    F: FnOnce(&mut JNIEnv) -> String + std::panic::UnwindSafe,
{
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| f(env)));
    let text = match result {
        Ok(s) => s,
        Err(_) => err_json("rust_core: internal panic"),
    };
    match env.new_string(text) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

fn jstr(env: &mut JNIEnv, s: JString) -> Result<String, String> {
    let java_str = env
        .get_string(&s)
        .map_err(|e| format!("jni get_string: {e}"))?;
    Ok(java_str.into())
}

fn parse_sources(json_str: &str) -> Result<Vec<PathBuf>, String> {
    let v: Value = serde_json::from_str(json_str)
        .map_err(|e| format!("sources must be a JSON array of paths: {e}"))?;
    let arr = v
        .as_array()
        .ok_or_else(|| "sources must be a JSON array of paths".to_string())?;
    let mut out = Vec::new();
    for item in arr {
        let s = item
            .as_str()
            .ok_or_else(|| "each source must be a string path".to_string())?;
        out.push(PathBuf::from(s));
    }
    Ok(out)
}

// ---- file operations -------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_example_helloworld_RustBridge_listFilesNative<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    path: JString<'local>,
    recursive: jboolean,
) -> jstring {
    run_json(&mut env, |env| {
        let path = match jstr(env, path) {
            Ok(p) => p,
            Err(e) => return err_json(e),
        };
        to_json(files::list(&path, recursive != 0))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_helloworld_RustBridge_copyFileNative<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    src: JString<'local>,
    dst: JString<'local>,
) -> jstring {
    run_json(&mut env, |env| {
        let (src, dst) = match (jstr(env, src), jstr(env, dst)) {
            (Ok(a), Ok(b)) => (a, b),
            _ => return err_json("jni: bad string argument"),
        };
        to_json(files::copy(&src, &dst).map(|n| json!({"bytes_copied": n})))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_helloworld_RustBridge_moveFileNative<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    src: JString<'local>,
    dst: JString<'local>,
) -> jstring {
    run_json(&mut env, |env| {
        let (src, dst) = match (jstr(env, src), jstr(env, dst)) {
            (Ok(a), Ok(b)) => (a, b),
            _ => return err_json("jni: bad string argument"),
        };
        to_json(files::move_path(&src, &dst).map(|_| json!({"moved": true})))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_helloworld_RustBridge_deleteFileNative<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    path: JString<'local>,
    recursive: jboolean,
) -> jstring {
    run_json(&mut env, |env| {
        let path = match jstr(env, path) {
            Ok(p) => p,
            Err(e) => return err_json(e),
        };
        to_json(files::delete(&path, recursive != 0).map(|_| json!({"deleted": true})))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_helloworld_RustBridge_searchFilesNative<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    root: JString<'local>,
    query: JString<'local>,
    recursive: jboolean,
    max_results: jint,
) -> jstring {
    run_json(&mut env, |env| {
        let (root, query) = match (jstr(env, root), jstr(env, query)) {
            (Ok(a), Ok(b)) => (a, b),
            _ => return err_json("jni: bad string argument"),
        };
        to_json(files::search(
            &root,
            &query,
            recursive != 0,
            max_results.max(0) as usize,
        ))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_helloworld_RustBridge_sha256FileNative<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    path: JString<'local>,
) -> jstring {
    run_json(&mut env, |env| {
        let path = match jstr(env, path) {
            Ok(p) => p,
            Err(e) => return err_json(e),
        };
        to_json(files::sha256(&path).map(|h| json!({"sha256": h})))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_helloworld_RustBridge_writeTextFileNative<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    path: JString<'local>,
    content: JString<'local>,
) -> jstring {
    run_json(&mut env, |env| {
        let (path, content) = match (jstr(env, path), jstr(env, content)) {
            (Ok(a), Ok(b)) => (a, b),
            _ => return err_json("jni: bad string argument"),
        };
        to_json(files::write_text(&path, &content).map(|n| json!({"bytes_written": n})))
    })
}

// ---- archives -------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_example_helloworld_RustBridge_compressArchiveNative<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    format: JString<'local>,
    sources_json: JString<'local>,
    dest: JString<'local>,
) -> jstring {
    run_json(&mut env, |env| {
        let (format, sources_json, dest) =
            match (jstr(env, format), jstr(env, sources_json), jstr(env, dest)) {
                (Ok(a), Ok(b), Ok(c)) => (a, b, c),
                _ => return err_json("jni: bad string argument"),
            };
        let result = (|| -> Result<archives::ArchiveInfo, String> {
            let fmt = archives::ArchiveFormat::parse(&format)?;
            let sources = parse_sources(&sources_json)?;
            archives::compress(fmt, &sources, &dest)
        })();
        to_json(result)
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_helloworld_RustBridge_extractArchiveNative<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    format: JString<'local>,
    src: JString<'local>,
    dest: JString<'local>,
) -> jstring {
    run_json(&mut env, |env| {
        let (format, src, dest) = match (jstr(env, format), jstr(env, src), jstr(env, dest)) {
            (Ok(a), Ok(b), Ok(c)) => (a, b, c),
            _ => return err_json("jni: bad string argument"),
        };
        let result = (|| -> Result<archives::ArchiveInfo, String> {
            // Empty format string = auto-detect from the file name.
            let fmt = if format.is_empty() {
                archives::ArchiveFormat::detect(&src)?
            } else {
                archives::ArchiveFormat::parse(&format)?
            };
            archives::extract(fmt, &src, &dest)
        })();
        to_json(result)
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_helloworld_RustBridge_listArchiveNative<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    format: JString<'local>,
    src: JString<'local>,
) -> jstring {
    run_json(&mut env, |env| {
        let (format, src) = match (jstr(env, format), jstr(env, src)) {
            (Ok(a), Ok(b)) => (a, b),
            _ => return err_json("jni: bad string argument"),
        };
        let result = (|| -> Result<Vec<String>, String> {
            let fmt = if format.is_empty() {
                archives::ArchiveFormat::detect(&src)?
            } else {
                archives::ArchiveFormat::parse(&format)?
            };
            archives::list_archive(fmt, &src)
        })();
        to_json(result)
    })
}

// ---- line-indexed text ----------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_example_helloworld_RustBridge_lineCountNative<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    path: JString<'local>,
) -> jstring {
    run_json(&mut env, |env| {
        let path = match jstr(env, path) {
            Ok(p) => p,
            Err(e) => return err_json(e),
        };
        to_json(text::line_count(&path).map(|n| json!({"total_lines": n})))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_helloworld_RustBridge_readLinesNative<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    path: JString<'local>,
    start_line: jint,
    count: jint,
) -> jstring {
    run_json(&mut env, |env| {
        let path = match jstr(env, path) {
            Ok(p) => p,
            Err(e) => return err_json(e),
        };
        to_json(text::read_lines(
            &path,
            start_line.max(1) as u64,
            count.max(0) as usize,
        ))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_helloworld_RustBridge_createLineIndexNative<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    path: JString<'local>,
    index_path: JString<'local>,
) -> jstring {
    run_json(&mut env, |env| {
        let (path, index_path) = match (jstr(env, path), jstr(env, index_path)) {
            (Ok(a), Ok(b)) => (a, b),
            _ => return err_json("jni: bad string argument"),
        };
        to_json(text::create_line_index(&path, &index_path).map(|n| json!({"total_lines": n})))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_helloworld_RustBridge_readLinesIndexedNative<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    index_path: JString<'local>,
    path: JString<'local>,
    start_line: jint,
    count: jint,
) -> jstring {
    run_json(&mut env, |env| {
        let (index_path, path) = match (jstr(env, index_path), jstr(env, path)) {
            (Ok(a), Ok(b)) => (a, b),
            _ => return err_json("jni: bad string argument"),
        };
        to_json(text::read_lines_indexed(
            &index_path,
            &path,
            start_line.max(1) as u64,
            count.max(0) as usize,
        ))
    })
}
