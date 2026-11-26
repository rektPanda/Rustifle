package com.example.helloworld.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File

/**
 * Bookmarks tab. Tapping a bookmarked folder opens it in Files;
 * tapping a bookmarked file opens it in the Editor or the Viewer —
 * the ViewModel's open requests switch the tab (see MainActivity),
 * so no file callback is needed here.
 */
@Composable
fun BookmarksScreen(
    viewModel: FileBrowserViewModel,
    onOpenFolder: () -> Unit,
) {
    val bookmarks = viewModel.bookmarks.sorted()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
    ) {
        Text(
            text = "Bookmarks",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        if (bookmarks.isEmpty()) {
            Text(
                text = "No bookmarks yet. Star a folder or file in Files " +
                    "to keep it here.",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(bookmarks, key = { it }) { path ->
                    val file = File(path)
                    val isDir = file.isDirectory
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (isDir) {
                                    viewModel.loadDir(path)
                                    onOpenFolder()
                                } else {
                                    viewModel.openPath(path)
                                }
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (isDir) Icons.Filled.Folder else Icons.Filled.InsertDriveFile,
                            contentDescription = if (isDir) "Folder" else "File",
                            modifier = Modifier.padding(end = 12.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = file.name.ifEmpty { path },
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = path,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { viewModel.removeBookmark(path) }) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove bookmark")
                        }
                    }
                }
            }
        }
    }
}
