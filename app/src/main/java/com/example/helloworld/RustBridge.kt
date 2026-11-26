package com.example.helloworld

import org.json.JSONArray
import org.json.JSONObject

/**
 * Kotlin bridge to `rust_core` (`librust_core.so`, built with cargo-ndk).
 *
 * Every native call returns one JSON string from Rust:
 * `{"ok":true,"data":...}` or `{"ok":false,"error":"..."}`.
 * The private `external` functions are the raw JNI surface (their names
 * must match `Java_com_example_helloworld_RustBridge_*` in
 * `rust_core/src/jni_layer.rs`); the public functions below are the
 * typed API the app uses. Failures throw [RustBridgeException].
 *
 * The library is loaded lazily on first use, so a build without the
 * `.so` (plain `./gradlew assembleDebug` with no NDK / cargo-ndk)
 * fails only when a Rust function is actually called, not at startup.
 */
object RustBridge {

    /** Thrown when the Rust side reports an error (bad path, I/O, …). */
    class RustBridgeException(message: String) : RuntimeException(message)

    private val loaded: Boolean by lazy {
        try {
            System.loadLibrary("rust_core")
            true
        } catch (e: UnsatisfiedLinkError) {
            false
        }
    }

    /** True once `librust_core.so` has been loaded successfully. */
    val isAvailable: Boolean get() = loaded

    private fun requireLoaded() {
        if (!loaded) {
            throw RustBridgeException(
                "librust_core.so is not packaged in this build. " +
                    "Build the Rust targets first (see README: cargo-ndk), " +
                    "or run CI, which does it before Gradle."
            )
        }
    }

    // ---- data types -------------------------------------------------------

    data class FileEntry(
        val name: String,
        val path: String,
        val isDir: Boolean,
        val size: Long,
        /** Last-modified, ms since epoch, or null if unknown. */
        val modifiedMs: Long?,
    )

    data class IndexedLine(val lineNo: Long, val text: String)

    data class TextPage(
        val path: String,
        val startLine: Long,
        val totalLines: Long,
        val lines: List<IndexedLine>,
    )

    data class ArchiveInfo(
        val format: ArchiveFormat,
        val path: String,
        val size: Long,
        val fileCount: Long,
    )

    enum class ArchiveFormat(val id: String) {
        ZIP("zip"),
        TAR_GZ("tar.gz"),
        SEVEN_Z("7z");

        companion object {
            fun fromId(id: String): ArchiveFormat =
                values().firstOrNull { it.id == id.lowercase() }
                    ?: throw RustBridgeException("unknown archive format: $id")

            /** Detect from a file name (`.zip` / `.tar.gz` / `.tgz` / `.7z`). */
            fun detect(path: String): ArchiveFormat {
                val lower = path.lowercase()
                return when {
                    lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> TAR_GZ
                    lower.endsWith(".zip") -> ZIP
                    lower.endsWith(".7z") -> SEVEN_Z
                    else -> throw RustBridgeException(
                        "cannot detect archive format from name: $path"
                    )
                }
            }
        }
    }

    // ---- JSON plumbing ----------------------------------------------------

    private fun call(json: String): JSONObject {
        val obj = JSONObject(json)
        if (!obj.optBoolean("ok", false)) {
            throw RustBridgeException(obj.optString("error", "unknown rust_core error"))
        }
        return obj
    }

    private fun JSONObject.fileEntry(): FileEntry = FileEntry(
        name = getString("name"),
        path = getString("path"),
        isDir = getBoolean("is_dir"),
        size = getLong("size"),
        modifiedMs = if (isNull("modified_ms")) null else getLong("modified_ms"),
    )

    private fun JSONArray.fileEntries(): List<FileEntry> =
        (0 until length()).map { getJSONObject(it).fileEntry() }

    private fun JSONObject.textPage(): TextPage = TextPage(
        path = getString("path"),
        startLine = getLong("start_line"),
        totalLines = getLong("total_lines"),
        lines = getJSONArray("lines").let { arr ->
            (0 until arr.length()).map {
                val line = arr.getJSONObject(it)
                IndexedLine(line.getLong("line_no"), line.getString("text"))
            }
        },
    )

    private fun JSONObject.archiveInfo(): ArchiveInfo {
        val data = getJSONObject("data")
        return ArchiveInfo(
            format = ArchiveFormat.fromId(data.getString("format")),
            path = data.getString("path"),
            size = data.getLong("size"),
            fileCount = data.getLong("file_count"),
        )
    }

    // ---- file operations --------------------------------------------------

    /** List entries of [path]; [recursive] walks the whole tree. */
    fun listFiles(path: String, recursive: Boolean = false): List<FileEntry> {
        requireLoaded()
        return call(listFilesNative(path, recursive)).getJSONArray("data").fileEntries()
    }

    /** Copy a file, or a whole directory tree. Returns bytes copied. */
    fun copyFile(src: String, dst: String): Long {
        requireLoaded()
        return call(copyFileNative(src, dst)).getJSONObject("data").getLong("bytes_copied")
    }

    /** Move / rename; falls back to copy + delete across volumes. */
    fun moveFile(src: String, dst: String) {
        requireLoaded()
        call(moveFileNative(src, dst))
    }

    /** Delete a file, or a directory ([recursive] required for non-empty dirs). */
    fun deleteFile(path: String, recursive: Boolean = false) {
        requireLoaded()
        call(deleteFileNative(path, recursive))
    }

    /**
     * Filename search under [root]: names containing [query]
     * (case-insensitive). [maxResults] = 0 means no cap.
     */
    fun searchFiles(
        root: String,
        query: String,
        recursive: Boolean = true,
        maxResults: Int = 1000,
    ): List<FileEntry> {
        requireLoaded()
        return call(searchFilesNative(root, query, recursive, maxResults))
            .getJSONArray("data").fileEntries()
    }

    /** Write a whole text string to a file (creates parents). Returns bytes written. */
    fun writeTextFile(path: String, content: String): Long {
        requireLoaded()
        return call(writeTextFileNative(path, content))
            .getJSONObject("data").getLong("bytes_written")
    }

    /** SHA-256 hex digest of a file (streamed, safe for large files). */
    fun sha256File(path: String): String {
        requireLoaded()
        return call(sha256FileNative(path)).getJSONObject("data").getString("sha256")
    }

    // ---- archives ---------------------------------------------------------

    /**
     * Compress [sources] (files and/or directories) into [dest].
     * Pass [ArchiveFormat] explicitly, or use [compressArchiveAuto] which
     * detects it from [dest]'s extension.
     */
    fun compressArchive(format: ArchiveFormat, sources: List<String>, dest: String): ArchiveInfo {
        requireLoaded()
        val sourcesJson = JSONArray(sources).toString()
        return call(compressArchiveNative(format.id, sourcesJson, dest)).archiveInfo()
    }

    fun compressArchiveAuto(sources: List<String>, dest: String): ArchiveInfo =
        compressArchive(ArchiveFormat.detect(dest), sources, dest)

    /**
     * Extract [src] into [dest]. [format] = null auto-detects from [src]'s
     * extension; zip-slip entries that would escape [dest] are rejected.
     */
    fun extractArchive(src: String, dest: String, format: ArchiveFormat? = null): ArchiveInfo {
        requireLoaded()
        return call(extractArchiveNative(format?.id ?: "", src, dest)).archiveInfo()
    }

    /** Entry names of an archive, without extracting it. */
    fun listArchive(src: String, format: ArchiveFormat? = null): List<String> {
        requireLoaded()
        val arr = call(listArchiveNative(format?.id ?: "", src)).getJSONArray("data")
        return (0 until arr.length()).map { arr.getString(it) }
    }

    // ---- line-indexed text ------------------------------------------------

    /** Number of lines in a text file (1-based lines, trailing line counts). */
    fun lineCount(path: String): Long {
        requireLoaded()
        return call(lineCountNative(path)).getJSONObject("data").getLong("total_lines")
    }

    /**
     * Read [count] lines starting at 1-based [startLine].
     * [count] = 0 reads to end of file.
     */
    fun readLines(path: String, startLine: Long = 1, count: Int = 100): TextPage {
        requireLoaded()
        return call(readLinesNative(path, startLine.toInt(), count)).getJSONObject("data").textPage()
    }

    /**
     * Build a persistent line index for [path] (byte offset per line) and
     * save it to [indexPath] (default `<path>.rlidx`). Returns total lines.
     * Rebuilding is needed only when the file changes.
     */
    fun createLineIndex(path: String, indexPath: String = ""): Long {
        requireLoaded()
        return call(createLineIndexNative(path, indexPath))
            .getJSONObject("data").getLong("total_lines")
    }

    /**
     * Read lines using a previously built index — seeks straight to
     * [startLine] instead of scanning from the top of the file.
     */
    fun readLinesIndexed(
        indexPath: String,
        path: String,
        startLine: Long = 1,
        count: Int = 100,
    ): TextPage {
        requireLoaded()
        return call(readLinesIndexedNative(indexPath, path, startLine.toInt(), count)).getJSONObject("data").textPage()
    }

    // ---- raw JNI surface (names must match rust_core/src/jni_layer.rs) ---

    private external fun listFilesNative(path: String, recursive: Boolean): String
    private external fun copyFileNative(src: String, dst: String): String
    private external fun moveFileNative(src: String, dst: String): String
    private external fun deleteFileNative(path: String, recursive: Boolean): String
    private external fun searchFilesNative(
        root: String,
        query: String,
        recursive: Boolean,
        maxResults: Int,
    ): String

    private external fun writeTextFileNative(path: String, content: String): String
    private external fun sha256FileNative(path: String): String
    private external fun compressArchiveNative(
        format: String,
        sourcesJson: String,
        dest: String,
    ): String

    private external fun extractArchiveNative(format: String, src: String, dest: String): String
    private external fun listArchiveNative(format: String, src: String): String
    private external fun lineCountNative(path: String): String
    private external fun readLinesNative(path: String, startLine: Int, count: Int): String
    private external fun createLineIndexNative(path: String, indexPath: String): String
    private external fun readLinesIndexedNative(
        indexPath: String,
        path: String,
        startLine: Int,
        count: Int,
    ): String
}
