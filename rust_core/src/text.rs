//! Line-indexed text reading.
//!
//! Lines are 1-based. [`read_lines`] streams from the start of the file
//! (fine for ordinary files); [`LineIndex`] is the fast path for large
//! logs — scan once, persist byte offsets per line to a `.rlidx` sidecar,
//! then seek straight to any line afterwards.

use crate::files::RResult;
use serde::Serialize;
use std::fs::File;
use std::io::{BufRead, BufReader, Read, Seek, SeekFrom, Write};
use std::path::Path;

const INDEX_MAGIC: &[u8; 6] = b"RLIX1\n";

#[derive(Debug, Clone, Serialize)]
pub struct IndexedLine {
    /// 1-based line number.
    pub line_no: u64,
    pub text: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct TextPage {
    pub path: String,
    pub start_line: u64,
    pub total_lines: u64,
    pub lines: Vec<IndexedLine>,
}

/// Count lines without loading the file. A trailing line without `\n`
/// still counts. Invalid UTF-8 is tolerated (counting is byte-based).
pub fn line_count(path: &str) -> RResult<u64> {
    let file = File::open(path).map_err(|e| format!("line_count {path}: {e}"))?;
    count_lines_in(BufReader::new(file))
}

fn count_lines_in<R: BufRead>(mut reader: R) -> RResult<u64> {
    let mut count = 0u64;
    let mut buf = Vec::new();
    loop {
        buf.clear();
        let n = reader
            .read_until(b'\n', &mut buf)
            .map_err(|e| format!("line_count read: {e}"))?;
        if n == 0 {
            break;
        }
        count += 1;
    }
    Ok(count)
}

/// Read `count` lines starting at 1-based `start_line`.
/// `count == 0` reads to end of file.
pub fn read_lines(path: &str, start_line: u64, count: usize) -> RResult<TextPage> {
    let start_line = start_line.max(1);
    let file = File::open(path).map_err(|e| format!("read_lines {path}: {e}"))?;
    let total = count_lines_in(BufReader::new(
        File::open(path).map_err(|e| format!("read_lines {path}: {e}"))?,
    ))?;
    let mut reader = BufReader::new(file);
    let mut lines = Vec::new();
    let mut buf = Vec::new();
    let mut line_no = 0u64;
    loop {
        buf.clear();
        let n = reader
            .read_until(b'\n', &mut buf)
            .map_err(|e| format!("read_lines read: {e}"))?;
        if n == 0 {
            break;
        }
        line_no += 1;
        if line_no < start_line {
            continue;
        }
        if count > 0 && lines.len() >= count {
            break;
        }
        lines.push(IndexedLine {
            line_no,
            text: strip_eol(&buf),
        });
    }
    Ok(TextPage {
        path: path.to_string(),
        start_line,
        total_lines: total,
        lines,
    })
}

fn strip_eol(bytes: &[u8]) -> String {
    let mut b = bytes;
    if b.ends_with(b"\n") {
        b = &b[..b.len() - 1];
    }
    if b.ends_with(b"\r") {
        b = &b[..b.len() - 1];
    }
    String::from_utf8_lossy(b).into_owned()
}

/// Byte offsets of every line start, plus the total line count.
/// `offsets[i]` is the byte offset of line `i + 1`; `offsets.len() ==
/// total_lines + 1` with the last entry being the file size (EOF).
#[derive(Debug, Clone)]
pub struct LineIndex {
    pub source_path: String,
    pub offsets: Vec<u64>,
    pub total_lines: u64,
}

impl LineIndex {
    pub fn build(path: &str) -> RResult<Self> {
        let file = File::open(path).map_err(|e| format!("index {path}: {e}"))?;
        let mut reader = BufReader::new(file);
        let mut offsets = vec![0u64];
        let mut buf = Vec::new();
        let mut total = 0u64;
        loop {
            buf.clear();
            let n = reader
                .read_until(b'\n', &mut buf)
                .map_err(|e| format!("index read: {e}"))?;
            if n == 0 {
                break;
            }
            total += 1;
            // Every line gets an offset (exact index, not checkpoints).
            offsets.push(offsets[total as usize - 1] + n as u64);
        }
        Ok(Self {
            source_path: path.to_string(),
            offsets,
            total_lines: total,
        })
    }

    /// Persist the index. Format: magic, u64 source-path length,
    /// source path bytes, u64 total_lines, then (total_lines + 1) u64
    /// offsets, all little-endian.
    pub fn save(&self, index_path: &str) -> RResult<u64> {
        let mut f =
            File::create(index_path).map_err(|e| format!("index save {index_path}: {e}"))?;
        f.write_all(INDEX_MAGIC)
            .map_err(|e| format!("index save: {e}"))?;
        f.write_all(&(self.source_path.len() as u64).to_le_bytes())
            .map_err(|e| format!("index save: {e}"))?;
        f.write_all(self.source_path.as_bytes())
            .map_err(|e| format!("index save: {e}"))?;
        f.write_all(&self.total_lines.to_le_bytes())
            .map_err(|e| format!("index save: {e}"))?;
        for off in &self.offsets {
            f.write_all(&off.to_le_bytes())
                .map_err(|e| format!("index save: {e}"))?;
        }
        Ok(self.total_lines)
    }

    pub fn load(index_path: &str) -> RResult<Self> {
        let mut f = File::open(index_path).map_err(|e| format!("index load {index_path}: {e}"))?;
        let mut magic = [0u8; 6];
        f.read_exact(&mut magic)
            .map_err(|e| format!("index load: bad header: {e}"))?;
        if &magic != INDEX_MAGIC {
            return Err(format!(
                "index load: not a rust_core line index: {index_path}"
            ));
        }
        let mut u = [0u8; 8];
        f.read_exact(&mut u)
            .map_err(|e| format!("index load: {e}"))?;
        let path_len = u64::from_le_bytes(u) as usize;
        let mut path_bytes = vec![0u8; path_len];
        f.read_exact(&mut path_bytes)
            .map_err(|e| format!("index load: {e}"))?;
        let source_path = String::from_utf8_lossy(&path_bytes).into_owned();
        f.read_exact(&mut u)
            .map_err(|e| format!("index load: {e}"))?;
        let total_lines = u64::from_le_bytes(u);
        let mut offsets = Vec::with_capacity((total_lines + 1) as usize);
        for _ in 0..=total_lines {
            f.read_exact(&mut u)
                .map_err(|e| format!("index load: {e}"))?;
            offsets.push(u64::from_le_bytes(u));
        }
        Ok(Self {
            source_path,
            offsets,
            total_lines,
        })
    }
}

/// Build a line index for `path` and save it to `index_path`
/// (default: `<path>.rlidx`). Returns the total line count.
pub fn create_line_index(path: &str, index_path: &str) -> RResult<u64> {
    let index_path = if index_path.is_empty() {
        format!("{path}.rlidx")
    } else {
        index_path.to_string()
    };
    if let Some(parent) = Path::new(&index_path).parent() {
        if !parent.as_os_str().is_empty() {
            std::fs::create_dir_all(parent).map_err(|e| format!("index create parent: {e}"))?;
        }
    }
    let index = LineIndex::build(path)?;
    index.save(&index_path)
}

/// Read lines using a previously built index — seeks straight to
/// `start_line` instead of scanning from the top of the file.
pub fn read_lines_indexed(
    index_path: &str,
    path: &str,
    start_line: u64,
    count: usize,
) -> RResult<TextPage> {
    let start_line = start_line.max(1);
    let index = LineIndex::load(index_path)?;
    if !index.source_path.is_empty() && index.source_path != path {
        return Err(format!(
            "index {index_path} was built for {}, not {path}",
            index.source_path
        ));
    }
    if start_line > index.total_lines {
        return Ok(TextPage {
            path: path.to_string(),
            start_line,
            total_lines: index.total_lines,
            lines: Vec::new(),
        });
    }
    let offset = index.offsets[(start_line - 1) as usize];
    let mut file = File::open(path).map_err(|e| format!("read_lines_indexed {path}: {e}"))?;
    file.seek(SeekFrom::Start(offset))
        .map_err(|e| format!("read_lines_indexed seek: {e}"))?;
    let mut reader = BufReader::new(file);
    let mut lines = Vec::new();
    let mut buf = Vec::new();
    let mut line_no = start_line;
    loop {
        buf.clear();
        let n = reader
            .read_until(b'\n', &mut buf)
            .map_err(|e| format!("read_lines_indexed read: {e}"))?;
        if n == 0 {
            break;
        }
        if count > 0 && lines.len() >= count {
            break;
        }
        lines.push(IndexedLine {
            line_no,
            text: strip_eol(&buf),
        });
        line_no += 1;
    }
    Ok(TextPage {
        path: path.to_string(),
        start_line,
        total_lines: index.total_lines,
        lines,
    })
}
