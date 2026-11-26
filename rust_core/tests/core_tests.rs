//! Integration tests for the pure-Rust core (no JNI / Android needed).
//! Run: `cargo test -p rust_core`

use rust_core::archives::{compress, extract, list_archive, ArchiveFormat};
use rust_core::files;
use rust_core::text;
use std::fs;
use std::path::{Path, PathBuf};

fn tmp(name: &str) -> PathBuf {
    let dir = std::env::temp_dir().join(format!("rust_core_test_{name}"));
    let _ = fs::remove_dir_all(&dir);
    fs::create_dir_all(&dir).unwrap();
    dir
}

fn write(dir: &Path, name: &str, content: &str) -> PathBuf {
    let p = dir.join(name);
    if let Some(parent) = p.parent() {
        fs::create_dir_all(parent).unwrap();
    }
    fs::write(&p, content).unwrap();
    p
}

#[test]
fn file_ops_list_copy_move_delete_search_sha256() {
    let dir = tmp("files");
    let a = write(&dir, "a.txt", "hello");
    let _b = write(&dir, "sub/b.txt", "world");
    let _c = write(&dir, "sub/note.md", "note");

    let listed = files::list(dir.to_str().unwrap(), false).unwrap();
    assert_eq!(listed.len(), 2); // a.txt + sub

    let all = files::list(dir.to_str().unwrap(), true).unwrap();
    assert_eq!(all.len(), 4);

    let found = files::search(dir.to_str().unwrap(), "b.txt", true, 10).unwrap();
    assert_eq!(found.len(), 1);
    assert!(found[0].path.ends_with("b.txt"));

    // SHA-256 of "hello" (no newline).
    assert_eq!(
        files::sha256(a.to_str().unwrap()).unwrap(),
        "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
    );

    let copy = dir.join("copy.txt");
    files::copy(a.to_str().unwrap(), copy.to_str().unwrap()).unwrap();
    assert_eq!(fs::read_to_string(&copy).unwrap(), "hello");

    let moved = dir.join("moved.txt");
    files::move_path(copy.to_str().unwrap(), moved.to_str().unwrap()).unwrap();
    assert!(moved.exists() && !copy.exists());

    files::delete(moved.to_str().unwrap(), false).unwrap();
    assert!(!moved.exists());

    assert!(files::list(dir.join("nope").to_str().unwrap(), false).is_err());
    assert!(files::sha256(dir.join("nope").to_str().unwrap()).is_err());
}

#[test]
fn write_text_round_trip() {
    let dir = tmp("write");
    let p = dir.join("hello.txt");
    let n = files::write_text(p.to_str().unwrap(), "a\nb\n").unwrap();
    assert_eq!(n, 4);
    assert_eq!(fs::read_to_string(&p).unwrap(), "a\nb\n");
    assert_eq!(text::line_count(p.to_str().unwrap()).unwrap(), 2);
}

#[test]
fn text_lines_and_index() {
    let dir = tmp("text");
    let p = write(&dir, "log.txt", "one\ntwo\nthree\nfour\nfive\n");
    let s = p.to_str().unwrap();

    assert_eq!(text::line_count(s).unwrap(), 5);
    let page = text::read_lines(s, 2, 2).unwrap();
    assert_eq!(page.total_lines, 5);
    assert_eq!(page.lines.len(), 2);
    assert_eq!(page.lines[0].line_no, 2);
    assert_eq!(page.lines[0].text, "two");
    assert_eq!(page.lines[1].text, "three");

    // Empty page past the end.
    assert!(text::read_lines(s, 99, 10).unwrap().lines.is_empty());

    let idx = dir.join("log.txt.rlidx");
    let n = text::create_line_index(s, idx.to_str().unwrap()).unwrap();
    assert_eq!(n, 5);
    let page2 = text::read_lines_indexed(idx.to_str().unwrap(), s, 4, 2).unwrap();
    assert_eq!(page2.lines.len(), 2);
    assert_eq!(page2.lines[0].text, "four");
    assert_eq!(page2.lines[1].text, "five");

    // Index built for another file must be rejected.
    let other = write(&dir, "other.txt", "x\n");
    assert!(
        text::read_lines_indexed(idx.to_str().unwrap(), other.to_str().unwrap(), 1, 1).is_err()
    );
}

#[test]
fn archives_round_trip_all_formats() {
    let dir = tmp("archives");
    let src = dir.join("data");
    write(&src, "a.txt", "alpha");
    write(&src, "sub/b.txt", "beta");

    for (fmt, ext) in [
        (ArchiveFormat::Zip, "zip"),
        (ArchiveFormat::TarGz, "tar.gz"),
        (ArchiveFormat::SevenZ, "7z"),
    ] {
        let archive = dir.join(format!("out.{ext}"));
        let info = compress(fmt, std::slice::from_ref(&src), archive.to_str().unwrap()).unwrap();
        assert!(info.file_count >= 2, "{ext}: {:?}", info);
        assert!(archive.exists());

        let names = list_archive(fmt, archive.to_str().unwrap()).unwrap();
        assert!(
            names.iter().any(|n| n.ends_with("a.txt")),
            "{ext}: {names:?}"
        );

        let out = dir.join(format!("extracted-{ext}"));
        let info2 = extract(fmt, archive.to_str().unwrap(), out.to_str().unwrap()).unwrap();
        assert!(info2.file_count >= 2, "{ext}: {:?}", info2);
        let got = fs::read_to_string(out.join("data").join("sub").join("b.txt"))
            .or_else(|_| fs::read_to_string(out.join("sub").join("b.txt")))
            .unwrap();
        assert_eq!(got, "beta");
    }
}

#[test]
fn archive_detect_and_errors() {
    assert_eq!(ArchiveFormat::detect("x.zip").unwrap(), ArchiveFormat::Zip);
    assert_eq!(
        ArchiveFormat::detect("x.tar.gz").unwrap(),
        ArchiveFormat::TarGz
    );
    assert_eq!(
        ArchiveFormat::detect("x.tgz").unwrap(),
        ArchiveFormat::TarGz
    );
    assert_eq!(
        ArchiveFormat::detect("x.7z").unwrap(),
        ArchiveFormat::SevenZ
    );
    assert!(ArchiveFormat::detect("x.rar").is_err());
    assert!(ArchiveFormat::parse("nope").is_err());
    let dir = tmp("arch_err");
    assert!(compress(
        ArchiveFormat::Zip,
        &[dir.join("missing")],
        dir.join("o.zip").to_str().unwrap()
    )
    .is_err());
}
