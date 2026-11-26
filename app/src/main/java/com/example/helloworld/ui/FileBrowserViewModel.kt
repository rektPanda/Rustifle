package com.example.helloworld.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.helloworld.RustBridge
import com.example.helloworld.RustBridge.FileEntry
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How the file list is ordered. */
enum class SortBy { NAME, SIZE, DATE }

/** Persistence for bookmarks (a set of absolute paths). */
interface BookmarkStore {
    fun load(): Set<String>
    fun save(paths: Set<String>)
}

/** [BookmarkStore] backed by SharedPreferences. */
class SharedPreferencesBookmarkStore(context: Context) : BookmarkStore {
    private val prefs = context.getSharedPreferences("hello_world_bookmarks", Context.MODE_PRIVATE)

    override fun load(): Set<String> =
        prefs.getStringSet(KEY, emptySet())?.toSet() ?: emptySet()

    override fun save(paths: Set<String>) {
        prefs.edit().putStringSet(KEY, paths).apply()
    }

    companion object {
        private const val KEY = "paths"
    }
}

/** What built-in viewer handles a file. */
enum class ViewerKind { PDF, IMAGE, AUDIO, VIDEO, HTML }

data class ViewerFile(val path: String, val kind: ViewerKind)

data class EditorState(
    val path: String? = null,
    val text: String = "",
    val dirty: Boolean = false,
    val loading: Boolean = false,
    /** False for partial loads (preview / indexed jump): Save is disabled. */
    val fullyLoaded: Boolean = false,
    val totalLines: Long = 0,
    val sha256: String? = null,
    val error: String? = null,
    val info: String? = null,
)

/**
 * State for the tabbed file browser + editor.
 *
 * Every operation goes through [RustBridge] first. When the native
 * library was not packaged into the build (`RustBridge.isAvailable ==
 * false`, e.g. a plain `./gradlew assembleDebug` with no cargo-ndk),
 * the same operations fall back to `java.io.File` so the UI stays
 * usable — the Rust path is the real one in CI builds.
 */
class FileBrowserViewModel(
    private val bookmarkStore: BookmarkStore,
    val roots: List<String>,
) : ViewModel() {

    val rustAvailable: Boolean = RustBridge.isAvailable

    var currentDir by mutableStateOf(roots.firstOrNull() ?: "/")
        private set

    var entries by mutableStateOf<List<FileEntry>>(emptyList())
        private set

    var sortBy by mutableStateOf(SortBy.NAME)
    var sortAscending by mutableStateOf(true)
    var dirsFirst by mutableStateOf(true)

    var query by mutableStateOf("")
        private set

    var searchResults by mutableStateOf<List<FileEntry>?>(null)
        private set

    var bookmarks by mutableStateOf(bookmarkStore.load())
        private set

    /** Last info / error message shown in the UI; null = nothing to show. */
    var status by mutableStateOf<String?>(null)

    var deleteConfirm by mutableStateOf<FileEntry?>(null)
    var renameTarget by mutableStateOf<FileEntry?>(null)

    var editor by mutableStateOf(EditorState())
        private set

    /**
     * Bumped every time a file is opened in the editor; MainActivity
     * observes it and switches to the Editor tab.
     */
    var openEditorRequest by mutableStateOf(0L)
        private set

    var viewerFile by mutableStateOf<ViewerFile?>(null)
        private set

    /**
     * Bumped every time a file is opened in a viewer; MainActivity
     * observes it and switches to the Viewer tab.
     */
    var openViewerRequest by mutableStateOf(0L)
        private set

    init {
        loadDir(currentDir)
    }

    /** Entries as currently displayed (search results or dir listing), sorted. */
    val visibleEntries: List<FileEntry>
        get() {
            val base = searchResults ?: entries
            val comparator = when (sortBy) {
                SortBy.NAME -> compareBy<FileEntry> { it.name.lowercase() }
                SortBy.SIZE -> compareBy { it.size }
                SortBy.DATE -> compareBy { it.modifiedMs ?: 0L }
            }
            val sorted = if (sortAscending) {
                base.sortedWith(comparator)
            } else {
                base.sortedWith(comparator.reversed())
            }
            // Stable sort, so the previous order survives inside each group.
            return if (dirsFirst) sorted.sortedWith(compareByDescending { it.isDir }) else sorted
        }

    val isSearching: Boolean get() = searchResults != null

    // ---- navigation -------------------------------------------------------

    fun loadDir(path: String) {
        currentDir = path
        searchResults = null
        query = ""
        status = null
        entries = try {
            listDir(path)
        } catch (e: Exception) {
            status = "Could not list $path: ${e.message}"
            emptyList()
        }
    }

    fun refresh() = loadDir(currentDir)

    fun navigateUp() {
        val parent = File(currentDir).parentFile ?: return
        if (parent.absolutePath != currentDir) loadDir(parent.absolutePath)
    }

    fun goRoot(index: Int) {
        roots.getOrNull(index)?.let { loadDir(it) }
    }

    // ---- search -----------------------------------------------------------

    fun search(text: String) {
        query = text
        if (text.isBlank()) {
            searchResults = null
            return
        }
        searchResults = try {
            searchIn(currentDir, text)
        } catch (e: Exception) {
            status = "Search failed: ${e.message}"
            emptyList()
        }
    }

    fun clearSearch() {
        query = ""
        searchResults = null
    }

    // ---- bookmarks ----------------------------------------------------------

    fun isBookmarked(path: String): Boolean = path in bookmarks

    fun toggleBookmark(path: String) {
        bookmarks = if (path in bookmarks) bookmarks - path else bookmarks + path
        bookmarkStore.save(bookmarks)
    }

    fun removeBookmark(path: String) {
        bookmarks = bookmarks - path
        bookmarkStore.save(bookmarks)
    }

    // ---- file actions -------------------------------------------------------

    fun openEntry(entry: FileEntry) {
        if (entry.isDir) {
            loadDir(entry.path)
        } else {
            routeFile(entry.path)
        }
    }

    /** Open any path (used by Bookmarks): dir -> Files, file -> viewer/editor. */
    fun openPath(path: String) {
        if (File(path).isDirectory) {
            loadDir(path)
        } else {
            routeFile(path)
        }
    }

    /** Route a file by extension: PDF / image / audio / video -> viewer,
     *  text & code -> editor, anything else -> editor as plain text. */
    private fun routeFile(path: String) {
        when (viewerKindFor(path)) {
            ViewerKind.PDF, ViewerKind.IMAGE, ViewerKind.AUDIO, ViewerKind.VIDEO, ViewerKind.HTML -> {
                openViewer(path)
            }
            null -> loadEditor(path)
        }
    }

    fun openViewer(path: String) {
        val kind = viewerKindFor(path) ?: return
        viewerFile = ViewerFile(path, kind)
        openViewerRequest = System.currentTimeMillis()
    }

    fun closeViewer() {
        viewerFile = null
    }

    fun deleteEntry(entry: FileEntry) {
        deleteConfirm = null
        // refresh() clears status (loadDir), so hold the message locally.
        var message: String
        try {
            if (rustAvailable) {
                RustBridge.deleteFile(entry.path, recursive = true)
            } else {
                File(entry.path).deleteRecursively()
            }
            message = "Deleted ${entry.name}"
        } catch (e: Exception) {
            message = "Delete failed: ${e.message}"
        }
        refresh()
        status = message
    }

    fun renameEntry(entry: FileEntry, newName: String) {
        renameTarget = null
        val trimmed = newName.trim()
        if (trimmed.isEmpty() || trimmed == entry.name) return
        val dest = File(entry.path).parentFile?.resolve(trimmed)?.absolutePath ?: return
        var message: String
        try {
            if (rustAvailable) {
                RustBridge.moveFile(entry.path, dest)
            } else {
                check(File(entry.path).renameTo(File(dest))) { "rename failed" }
            }
            message = "Renamed to $trimmed"
        } catch (e: Exception) {
            message = "Rename failed: ${e.message}"
        }
        refresh()
        status = message
    }

    fun newFile(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val path = File(currentDir, trimmed).absolutePath
        try {
            if (rustAvailable) {
                RustBridge.writeTextFile(path, "")
            } else {
                File(path).writeText("")
            }
            loadEditor(path)
        } catch (e: Exception) {
            status = "Could not create $trimmed: ${e.message}"
        }
    }

    // ---- editor -----------------------------------------------------------

    fun loadEditor(path: String) {
        editor = EditorState(path = path, loading = true)
        openEditorRequest = System.currentTimeMillis()
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { loadFullText(path) }
            }
            editor = result.fold(
                onSuccess = { (text, total) ->
                    EditorState(
                        path = path,
                        text = text,
                        fullyLoaded = total <= MAX_EDITOR_LINES,
                        totalLines = total,
                        sha256 = runCatching { sha256Of(path) }.getOrNull(),
                        info = if (total > MAX_EDITOR_LINES) {
                            "Preview only: $total lines, showing first $MAX_EDITOR_LINES. " +
                                "Too large to edit safely."
                        } else {
                            null
                        },
                    )
                },
                onFailure = { e ->
                    EditorState(path = path, error = "Could not open: ${e.message}")
                },
            )
        }
    }

    /** Reload the open file, discarding unsaved edits. */
    fun reloadEditor() {
        editor.path?.let { loadEditor(it) }
    }

    fun onEditorTextChange(newText: String) {
        editor = editor.copy(text = newText, dirty = true)
    }

    fun saveEditor() {
        val state = editor
        val path = state.path ?: return
        if (!state.fullyLoaded) {
            status = "Load the full file before saving."
            return
        }
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    if (rustAvailable) {
                        RustBridge.writeTextFile(path, state.text)
                    } else {
                        File(path).writeText(state.text)
                        state.text.length.toLong()
                    }
                }
            }
            result.fold(
                onSuccess = {
                    editor = state.copy(
                        dirty = false,
                        sha256 = runCatching { sha256Of(path) }.getOrNull(),
                    )
                    refresh()
                    status = "Saved ${File(path).name}"
                },
                onFailure = { e -> status = "Save failed: ${e.message}" },
            )
        }
    }

    /**
     * Jump to a line using the Rust line index (seeks straight to it
     * instead of scanning from the top). Shows a 200-line page;
     * Save stays disabled until the full file is loaded again.
     */
    fun goToLine(line: Long) {
        val path = editor.path ?: return
        if (editor.dirty) {
            status = "Save or reload first — jumping would discard unsaved edits."
            return
        }
        editor = editor.copy(loading = true)
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    if (rustAvailable) {
                        val indexPath = "$path.rlidx"
                        RustBridge.createLineIndex(path, indexPath)
                        val page = RustBridge.readLinesIndexed(indexPath, path, line, PAGE_SIZE)
                        page.lines.joinToString("\n") { it.text }
                    } else {
                        readLinesJava(path, line, PAGE_SIZE).joinToString("\n")
                    }
                }
            }
            editor = result.fold(
                onSuccess = { text ->
                    editor.copy(
                        text = text,
                        loading = false,
                        fullyLoaded = false,
                        info = "Showing lines from $line (indexed jump). " +
                            "Reload to edit the full file.",
                    )
                },
                onFailure = { e ->
                    editor.copy(loading = false, error = "Go to line failed: ${e.message}")
                },
            )
        }
    }

    fun closeEditor() {
        editor = EditorState()
    }

    // ---- Rust-first operations, java fallback ------------------------------

    private fun listDir(path: String): List<FileEntry> =
        if (rustAvailable) {
            RustBridge.listFiles(path)
        } else {
            File(path).listFiles()
                ?.map { f ->
                    FileEntry(
                        name = f.name,
                        path = f.absolutePath,
                        isDir = f.isDirectory,
                        size = if (f.isDirectory) 0 else f.length(),
                        modifiedMs = f.lastModified(),
                    )
                }
                ?: throw IllegalStateException("not a directory: $path")
        }

    private fun searchIn(root: String, text: String): List<FileEntry> =
        if (rustAvailable) {
            RustBridge.searchFiles(root, text, recursive = true)
        } else {
            File(root).walkTopDown()
                .filter { it.name.contains(text, ignoreCase = true) }
                .take(1000)
                .map { f ->
                    FileEntry(
                        name = f.name,
                        path = f.absolutePath,
                        isDir = f.isDirectory,
                        size = if (f.isDirectory) 0 else f.length(),
                        modifiedMs = f.lastModified(),
                    )
                }
                .toList()
        }

    private fun sha256Of(path: String): String =
        if (rustAvailable) {
            RustBridge.sha256File(path)
        } else {
            val digest = MessageDigest.getInstance("SHA-256")
            File(path).inputStream().use { input ->
                val buf = ByteArray(65536)
                var n: Int
                while (input.read(buf).also { n = it } > 0) digest.update(buf, 0, n)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }

    /** Reads the whole file (up to [MAX_EDITOR_LINES]) via paged Rust reads. */
    private fun loadFullText(path: String): Pair<String, Long> {
        val total = if (rustAvailable) {
            RustBridge.lineCount(path)
        } else {
            File(path).useLines { it.count().toLong() }
        }
        val wanted = minOf(total, MAX_EDITOR_LINES.toLong())
        val lines = mutableListOf<String>()
        var start = 1L
        while (start <= wanted) {
            val count = minOf(PAGE_SIZE.toLong(), wanted - start + 1).toInt()
            val page = if (rustAvailable) {
                RustBridge.readLines(path, start, count).lines.map { it.text }
            } else {
                readLinesJava(path, start, count)
            }
            if (page.isEmpty()) break
            lines += page
            start += page.size
        }
        var text = lines.joinToString("\n")
        if (lines.isNotEmpty() && fileEndsWithNewline(path)) text += "\n"
        return text to total
    }

    private fun readLinesJava(path: String, startLine: Long, count: Int): List<String> {
        val out = mutableListOf<String>()
        File(path).bufferedReader().useLines { seq ->
            seq.drop((startLine - 1).toInt()).take(count).forEach { out += it }
        }
        return out
    }

    private fun fileEndsWithNewline(path: String): Boolean {
        return try {
            RandomAccessFile(path, "r").use { raf ->
                if (raf.length() == 0L) false
                else {
                    raf.seek(raf.length() - 1)
                    raf.read() == '\n'.code
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        const val PAGE_SIZE = 200
        const val MAX_EDITOR_LINES = 5000

        private val PDF_EXTS = setOf("pdf")
        private val HTML_EXTS = setOf("html", "htm", "xhtml")
        private val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")
        private val AUDIO_EXTS = setOf("mp3", "wav", "ogg", "oga", "flac", "m4a", "aac", "opus")
        private val VIDEO_EXTS = setOf("mp4", "mkv", "webm", "mov", "avi", "3gp", "m4v")

        /** Viewer for a path by its extension, or null for text/unknown. */
        fun viewerKindFor(path: String): ViewerKind? {
            val ext = path.substringAfterLast('.', "").lowercase()
            return when (ext) {
                in PDF_EXTS -> ViewerKind.PDF
                in HTML_EXTS -> ViewerKind.HTML
                in IMAGE_EXTS -> ViewerKind.IMAGE
                in AUDIO_EXTS -> ViewerKind.AUDIO
                in VIDEO_EXTS -> ViewerKind.VIDEO
                else -> null
            }
        }
    }
}

class FileBrowserViewModelFactory(
    private val bookmarkStore: BookmarkStore,
    private val roots: List<String>,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(FileBrowserViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return FileBrowserViewModel(bookmarkStore, roots) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
