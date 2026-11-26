//! User-space file operations: list, copy, move, delete, search, SHA-256.
//!
//! All paths are plain filesystem paths handed in by the Kotlin side
//! (e.g. `context.cacheDir` / app-private storage). Nothing here needs
//! root or any Android permission beyond what the app already has for
//! its own directories.

use serde::Serialize;
use sha2::{Digest, Sha256};
use std::fs;
use std::io::{BufReader, Read};
use std::path::{Path, PathBuf};
use std::time::SystemTime;
use walkdir::WalkDir;

pub type RResult<T> = Result<T, String>;

pub fn err<T>(msg: impl Into<String>) -> RResult<T> {
    Err(msg.into())
}

#[derive(Debug, Clone, Serialize)]
pub struct FileEntry {
    pub name: String,
    pub path: String,
    pub is_dir: bool,
    pub size: u64,
    /// Last-modified time, milliseconds since the Unix epoch (null if unknown).
    pub modified_ms: Option<u64>,
}

pub fn entry_from_path(path: &Path) -> RResult<FileEntry> {
    let meta = fs::metadata(path).map_err(|e| format!("stat {}: {e}", path.display()))?;
    Ok(FileEntry {
        name: path
            .file_name()
            .map(|n| n.to_string_lossy().into_owned())
            .unwrap_or_else(|| path.to_string_lossy().into_owned()),
        path: path.to_string_lossy().into_owned(),
        is_dir: meta.is_dir(),
        size: if meta.is_dir() { 0 } else { meta.len() },
        modified_ms: meta
            .modified()
            .ok()
            .and_then(|t| t.duration_since(SystemTime::UNIX_EPOCH).ok())
            .map(|d| d.as_millis() as u64),
    })
}

/// List the entries of `path`. With `recursive`, walks the whole tree
/// (depth-first, sorted by path); otherwise lists only direct children.
pub fn list(path: &str, recursive: bool) -> RResult<Vec<FileEntry>> {
    let root = Path::new(path);
    if !root.exists() {
        return err(format!("list: path does not exist: {path}"));
    }
    if root.is_file() {
        return Ok(vec![entry_from_path(root)?]);
    }
    let mut out = Vec::new();
    if recursive {
        for e in WalkDir::new(root).sort_by_file_name().into_iter() {
            let e = e.map_err(|e| format!("list walk: {e}"))?;
            if e.path() == root {
                continue;
            }
            out.push(entry_from_path(e.path())?);
        }
    } else {
        for e in fs::read_dir(root).map_err(|e| format!("list {path}: {e}"))? {
            let e = e.map_err(|e| format!("list {path}: {e}"))?;
            out.push(entry_from_path(&e.path())?);
        }
        out.sort_by(|a, b| a.path.cmp(&b.path));
    }
    Ok(out)
}

/// Copy a file, or a whole directory tree (recursively) when `src` is a dir.
pub fn copy(src: &str, dst: &str) -> RResult<u64> {
    let src_p = Path::new(src);
    let dst_p = Path::new(dst);
    if !src_p.exists() {
        return err(format!("copy: source does not exist: {src}"));
    }
    if src_p.is_file() {
        if let Some(parent) = dst_p.parent() {
            fs::create_dir_all(parent)
                .map_err(|e| format!("copy: create parent {}: {e}", parent.display()))?;
        }
        let n = fs::copy(src_p, dst_p).map_err(|e| format!("copy {src} -> {dst}: {e}"))?;
        Ok(n)
    } else {
        copy_dir(src_p, dst_p)
    }
}

fn copy_dir(src: &Path, dst: &Path) -> RResult<u64> {
    fs::create_dir_all(dst).map_err(|e| format!("copy dir {}: {e}", dst.display()))?;
    let mut total = 0u64;
    for e in fs::read_dir(src).map_err(|e| format!("copy dir {}: {e}", src.display()))? {
        let e = e.map_err(|e| format!("copy dir: {e}"))?;
        let from = e.path();
        let to = dst.join(e.file_name());
        if from.is_dir() {
            total += copy_dir(&from, &to)?;
        } else {
            total += fs::copy(&from, &to)
                .map_err(|e| format!("copy {} -> {}: {e}", from.display(), to.display()))?;
        }
    }
    Ok(total)
}

/// Move / rename. Falls back to copy + delete when `rename` fails
/// (e.g. cross-device / cross-volume moves, common on Android).
pub fn move_path(src: &str, dst: &str) -> RResult<()> {
    let src_p = Path::new(src);
    let dst_p = Path::new(dst);
    if !src_p.exists() {
        return err(format!("move: source does not exist: {src}"));
    }
    if let Some(parent) = dst_p.parent() {
        fs::create_dir_all(parent)
            .map_err(|e| format!("move: create parent {}: {e}", parent.display()))?;
    }
    match fs::rename(src_p, dst_p) {
        Ok(()) => Ok(()),
        Err(rename_err) => {
            copy(src, dst).map_err(|e| format!("move fallback copy failed ({rename_err}): {e}"))?;
            delete(src, true).map_err(|e| format!("move fallback delete failed: {e}"))?;
            Ok(())
        }
    }
}

/// Delete a file, or a directory. Directories need `recursive = true`
/// (non-recursive delete of a non-empty dir fails, by design).
pub fn delete(path: &str, recursive: bool) -> RResult<()> {
    let p = Path::new(path);
    if !p.exists() {
        return err(format!("delete: path does not exist: {path}"));
    }
    if p.is_file() || p.is_symlink() {
        fs::remove_file(p).map_err(|e| format!("delete {path}: {e}"))?;
    } else if recursive {
        fs::remove_dir_all(p).map_err(|e| format!("delete {path}: {e}"))?;
    } else {
        fs::remove_dir(p).map_err(|e| format!("delete {path} (use recursive=true): {e}"))?;
    }
    Ok(())
}

/// Filename search under `root`: entries whose file name contains
/// `query` (case-insensitive). `max_results` caps the returned list
/// (0 = no cap). Returns matches sorted by path.
pub fn search(
    root: &str,
    query: &str,
    recursive: bool,
    max_results: usize,
) -> RResult<Vec<FileEntry>> {
    let root_p = Path::new(root);
    if !root_p.exists() {
        return err(format!("search: root does not exist: {root}"));
    }
    let needle = query.to_lowercase();
    let mut out = Vec::new();
    let walker = WalkDir::new(root_p)
        .max_depth(if recursive { usize::MAX } else { 1 })
        .sort_by_file_name();
    for e in walker.into_iter() {
        let e = e.map_err(|e| format!("search walk: {e}"))?;
        if e.path() == root_p {
            continue;
        }
        let name = e.file_name().to_string_lossy().to_lowercase();
        if name.contains(&needle) {
            out.push(entry_from_path(e.path())?);
            if max_results > 0 && out.len() >= max_results {
                break;
            }
        }
    }
    Ok(out)
}

/// SHA-256 of a file, streamed in 64 KiB chunks (safe for large files).
pub fn sha256(path: &str) -> RResult<String> {
    let file = fs::File::open(path).map_err(|e| format!("sha256 {path}: {e}"))?;
    let mut reader = BufReader::new(file);
    let mut hasher = Sha256::new();
    let mut buf = [0u8; 65536];
    loop {
        let n = reader
            .read(&mut buf)
            .map_err(|e| format!("sha256 read {path}: {e}"))?;
        if n == 0 {
            break;
        }
        hasher.update(&buf[..n]);
    }
    Ok(hex::encode(hasher.finalize()))
}

/// Write a whole text string to a file, creating parent directories.
/// Returns the number of bytes written. Used by the text editor's Save.
pub fn write_text(path: &str, content: &str) -> RResult<u64> {
    let p = Path::new(path);
    if let Some(parent) = p.parent() {
        if !parent.as_os_str().is_empty() {
            fs::create_dir_all(parent)
                .map_err(|e| format!("write_text parent {}: {e}", parent.display()))?;
        }
    }
    fs::write(p, content).map_err(|e| format!("write_text {path}: {e}"))?;
    Ok(content.len() as u64)
}

/// Resolve a path to an absolute, canonical path (for display / dedup).
pub fn canonical(path: &str) -> RResult<PathBuf> {
    fs::canonicalize(path).map_err(|e| format!("canonicalize {path}: {e}"))
}
