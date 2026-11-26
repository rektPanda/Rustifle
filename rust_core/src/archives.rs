//! Archive compress / extract: zip, tar.gz and 7z.
//!
//! Zip-slip is guarded on extract: entries that would escape the
//! destination directory are rejected instead of written.

use crate::files::RResult;
use flate2::read::GzDecoder;
use flate2::write::GzEncoder;
use flate2::Compression;
use serde::Serialize;
use std::fs::{self, File};
use std::io::{self, Write};
use std::path::{Path, PathBuf};
use walkdir::WalkDir;
use zip::write::SimpleFileOptions;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum ArchiveFormat {
    Zip,
    TarGz,
    SevenZ,
}

impl ArchiveFormat {
    pub fn parse(s: &str) -> RResult<Self> {
        match s.to_ascii_lowercase().as_str() {
            "zip" => Ok(Self::Zip),
            "tar.gz" | "tgz" | "tar_gz" => Ok(Self::TarGz),
            "7z" | "sevenz" | "7zip" => Ok(Self::SevenZ),
            other => Err(format!("unknown archive format: {other} (zip, tar.gz, 7z)")),
        }
    }

    /// Detect from a file name / path extension.
    pub fn detect(path: &str) -> RResult<Self> {
        let lower = path.to_ascii_lowercase();
        if lower.ends_with(".tar.gz") || lower.ends_with(".tgz") {
            Ok(Self::TarGz)
        } else if lower.ends_with(".zip") {
            Ok(Self::Zip)
        } else if lower.ends_with(".7z") {
            Ok(Self::SevenZ)
        } else {
            Err(format!("cannot detect archive format from name: {path}"))
        }
    }
}

#[derive(Debug, Clone, Serialize)]
pub struct ArchiveInfo {
    pub format: ArchiveFormat,
    pub path: String,
    pub size: u64,
    /// Files packed (compress) or entries extracted (extract).
    pub file_count: u64,
}

fn archive_size(path: &Path) -> u64 {
    fs::metadata(path).map(|m| m.len()).unwrap_or(0)
}

/// Collect (source path, archive entry name) pairs for compression.
/// A file contributes itself under its file name; a directory contributes
/// every file under it, prefixed with the directory's name.
fn collect_sources(sources: &[PathBuf]) -> RResult<Vec<(PathBuf, String)>> {
    let mut out = Vec::new();
    for src in sources {
        if !src.exists() {
            return Err(format!("archive source does not exist: {}", src.display()));
        }
        let base = src
            .file_name()
            .map(|n| n.to_string_lossy().into_owned())
            .unwrap_or_else(|| "root".to_string());
        if src.is_file() {
            out.push((src.clone(), base));
        } else {
            for e in WalkDir::new(src).sort_by_file_name().into_iter() {
                let e = e.map_err(|e| format!("archive walk: {e}"))?;
                let p = e.path();
                if p.is_file() {
                    let rel = p
                        .strip_prefix(src)
                        .map_err(|e| format!("archive walk: {e}"))?;
                    let name = Path::new(&base).join(rel).to_string_lossy().into_owned();
                    out.push((p.to_path_buf(), name));
                }
            }
        }
    }
    Ok(out)
}

pub fn compress(format: ArchiveFormat, sources: &[PathBuf], dest: &str) -> RResult<ArchiveInfo> {
    if sources.is_empty() {
        return Err("compress: no sources given".to_string());
    }
    let dest_p = Path::new(dest);
    if let Some(parent) = dest_p.parent() {
        if !parent.as_os_str().is_empty() {
            fs::create_dir_all(parent)
                .map_err(|e| format!("compress: create parent {}: {e}", parent.display()))?;
        }
    }
    let count = match format {
        ArchiveFormat::Zip => compress_zip(sources, dest_p)?,
        ArchiveFormat::TarGz => compress_tar_gz(sources, dest_p)?,
        ArchiveFormat::SevenZ => compress_7z(sources, dest_p)?,
    };
    Ok(ArchiveInfo {
        format,
        path: dest.to_string(),
        size: archive_size(dest_p),
        file_count: count,
    })
}

fn compress_zip(sources: &[PathBuf], dest: &Path) -> RResult<u64> {
    let files = collect_sources(sources)?;
    let out = File::create(dest).map_err(|e| format!("zip create {}: {e}", dest.display()))?;
    let mut zip = zip::ZipWriter::new(out);
    let options = SimpleFileOptions::default().compression_method(zip::CompressionMethod::Deflated);
    for (path, name) in &files {
        zip.start_file(name, options)
            .map_err(|e| format!("zip start {name}: {e}"))?;
        let mut f = File::open(path).map_err(|e| format!("zip open {}: {e}", path.display()))?;
        io::copy(&mut f, &mut zip).map_err(|e| format!("zip write {name}: {e}"))?;
    }
    zip.finish()
        .map_err(|e| format!("zip finish {}: {e}", dest.display()))?;
    Ok(files.len() as u64)
}

fn compress_tar_gz(sources: &[PathBuf], dest: &Path) -> RResult<u64> {
    let files = collect_sources(sources)?;
    let out = File::create(dest).map_err(|e| format!("tar.gz create {}: {e}", dest.display()))?;
    let enc = GzEncoder::new(out, Compression::default());
    let mut tar = tar::Builder::new(enc);
    let mut count = 0u64;
    for (path, name) in &files {
        tar.append_path_with_name(path, name)
            .map_err(|e| format!("tar.gz append {name}: {e}"))?;
        count += 1;
    }
    let enc = tar
        .into_inner()
        .map_err(|e| format!("tar.gz finish: {e}"))?;
    enc.finish()
        .map_err(|e| format!("tar.gz finish {}: {e}", dest.display()))?;
    Ok(count)
}

fn compress_7z(sources: &[PathBuf], dest: &Path) -> RResult<u64> {
    let files = collect_sources(sources)?;
    if sources.len() == 1 {
        sevenz_rust2::compress_to_path(&sources[0], dest)
            .map_err(|e| format!("7z compress {}: {e}", dest.display()))?;
    } else {
        let mut writer = sevenz_rust2::ArchiveWriter::create(dest)
            .map_err(|e| format!("7z create {}: {e}", dest.display()))?;
        for src in sources {
            writer
                .push_source_path(src, |_| true)
                .map_err(|e| format!("7z add {}: {e}", src.display()))?;
        }
        writer
            .finish()
            .map_err(|e| format!("7z finish {}: {e}", dest.display()))?;
    }
    Ok(files.len() as u64)
}

pub fn extract(format: ArchiveFormat, src: &str, dest: &str) -> RResult<ArchiveInfo> {
    let src_p = Path::new(src);
    if !src_p.exists() {
        return Err(format!("extract: archive does not exist: {src}"));
    }
    let dest_p = Path::new(dest);
    fs::create_dir_all(dest_p).map_err(|e| format!("extract: create dest {dest}: {e}"))?;
    let count = match format {
        ArchiveFormat::Zip => extract_zip(src_p, dest_p)?,
        ArchiveFormat::TarGz => extract_tar_gz(src_p, dest_p)?,
        ArchiveFormat::SevenZ => extract_7z(src_p, dest_p)?,
    };
    Ok(ArchiveInfo {
        format,
        path: dest.to_string(),
        size: archive_size(src_p),
        file_count: count,
    })
}

fn extract_zip(src: &Path, dest: &Path) -> RResult<u64> {
    let file = File::open(src).map_err(|e| format!("zip open {}: {e}", src.display()))?;
    let mut archive =
        zip::ZipArchive::new(file).map_err(|e| format!("zip open {}: {e}", src.display()))?;
    let count = archive.len() as u64;
    let dest_canon = fs::canonicalize(dest).unwrap_or_else(|_| dest.to_path_buf());
    for i in 0..archive.len() {
        let mut entry = archive
            .by_index(i)
            .map_err(|e| format!("zip entry {i}: {e}"))?;
        let name = match entry.enclosed_name() {
            Some(n) => n.to_owned(),
            None => return Err(format!("zip entry {i}: unsafe path in archive")),
        };
        let out_path = dest.join(&name);
        // Zip-slip guard: the resolved path must stay under dest.
        if !out_path.starts_with(&dest_canon) && !out_path.starts_with(dest) {
            return Err(format!(
                "zip entry {i}: path escapes destination: {}",
                name.display()
            ));
        }
        if entry.is_dir() {
            fs::create_dir_all(&out_path).map_err(|e| format!("zip mkdir: {e}"))?;
        } else {
            if let Some(parent) = out_path.parent() {
                fs::create_dir_all(parent).map_err(|e| format!("zip mkdir: {e}"))?;
            }
            let mut out = File::create(&out_path)
                .map_err(|e| format!("zip create {}: {e}", out_path.display()))?;
            io::copy(&mut entry, &mut out).map_err(|e| format!("zip extract: {e}"))?;
        }
    }
    Ok(count)
}

fn extract_tar_gz(src: &Path, dest: &Path) -> RResult<u64> {
    let file = File::open(src).map_err(|e| format!("tar.gz open {}: {e}", src.display()))?;
    let dec = GzDecoder::new(file);
    let mut archive = tar::Archive::new(dec);
    // Count entries first (tar is a stream, so count then re-open).
    let count = {
        let file2 = File::open(src).map_err(|e| format!("tar.gz open {}: {e}", src.display()))?;
        let mut a2 = tar::Archive::new(GzDecoder::new(file2));
        a2.entries()
            .map_err(|e| format!("tar.gz entries: {e}"))?
            .count() as u64
    };
    archive
        .unpack(dest)
        .map_err(|e| format!("tar.gz unpack {}: {e}", src.display()))?;
    Ok(count)
}

fn extract_7z(src: &Path, dest: &Path) -> RResult<u64> {
    let count = sevenz_rust2::ArchiveReader::open(src, sevenz_rust2::Password::empty())
        .map(|r| r.archive().files.len() as u64)
        .unwrap_or(0);
    sevenz_rust2::decompress_file(src, dest)
        .map_err(|e| format!("7z extract {}: {e}", src.display()))?;
    Ok(count)
}

/// List the entry names of an archive without extracting it.
pub fn list_archive(format: ArchiveFormat, src: &str) -> RResult<Vec<String>> {
    let src_p = Path::new(src);
    match format {
        ArchiveFormat::Zip => {
            let file = File::open(src_p).map_err(|e| format!("zip open {src}: {e}"))?;
            let mut archive =
                zip::ZipArchive::new(file).map_err(|e| format!("zip open {src}: {e}"))?;
            let mut names = Vec::new();
            for i in 0..archive.len() {
                let entry = archive
                    .by_index(i)
                    .map_err(|e| format!("zip entry {i}: {e}"))?;
                names.push(entry.name().to_string());
            }
            Ok(names)
        }
        ArchiveFormat::TarGz => {
            let file = File::open(src_p).map_err(|e| format!("tar.gz open {src}: {e}"))?;
            let mut archive = tar::Archive::new(GzDecoder::new(file));
            let mut names = Vec::new();
            for e in archive
                .entries()
                .map_err(|e| format!("tar.gz entries: {e}"))?
            {
                let e = e.map_err(|e| format!("tar.gz entry: {e}"))?;
                names.push(
                    e.path()
                        .map_err(|e| format!("tar.gz path: {e}"))?
                        .to_string_lossy()
                        .into_owned(),
                );
            }
            Ok(names)
        }
        ArchiveFormat::SevenZ => {
            let reader = sevenz_rust2::ArchiveReader::open(src_p, sevenz_rust2::Password::empty())
                .map_err(|e| format!("7z open {src}: {e}"))?;
            Ok(reader
                .archive()
                .files
                .iter()
                .map(|f| f.name().to_string())
                .collect())
        }
    }
}

/// Helper used by the Kotlin side: write a whole text string to a file
/// (used by tests / demos that round-trip through the bridge).
pub fn write_text(path: &str, content: &str) -> RResult<u64> {
    if let Some(parent) = Path::new(path).parent() {
        if !parent.as_os_str().is_empty() {
            fs::create_dir_all(parent).map_err(|e| format!("write_text parent: {e}"))?;
        }
    }
    let mut f = File::create(path).map_err(|e| format!("write_text {path}: {e}"))?;
    f.write_all(content.as_bytes())
        .map_err(|e| format!("write_text {path}: {e}"))?;
    Ok(content.len() as u64)
}
