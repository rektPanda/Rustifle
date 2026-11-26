package com.example.helloworld.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.helloworld.RustBridge.FileEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    else -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
}

private fun formatDate(modifiedMs: Long?): String {
    if (modifiedMs == null) return ""
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        .format(Date(modifiedMs))
}

@Composable
fun FileBrowserScreen(viewModel: FileBrowserViewModel) {
    var sortMenuOpen by remember { mutableStateOf(false) }
    var newFileName by remember { mutableStateOf("") }
    var renameText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Root chips: Files dir / Cache dir.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            viewModel.roots.forEachIndexed { index, root ->
                val label = if (index == 0) "Files" else "Cache"
                TextButton(
                    onClick = { viewModel.goRoot(index) },
                    enabled = viewModel.currentDir != root,
                ) { Text(label) }
            }
        }

        // Path bar.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { viewModel.navigateUp() }) {
                Icon(Icons.Filled.ArrowUpward, contentDescription = "Up")
            }
            Text(
                text = viewModel.currentDir,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { viewModel.toggleBookmark(viewModel.currentDir) }) {
                Icon(
                    if (viewModel.isBookmarked(viewModel.currentDir)) Icons.Filled.Star
                    else Icons.Filled.StarBorder,
                    contentDescription = "Bookmark this folder",
                )
            }
            IconButton(onClick = { viewModel.refresh() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
            }
        }

        // Search (RustBridge.searchFiles, recursive).
        OutlinedTextField(
            value = viewModel.query,
            onValueChange = { viewModel.search(it) },
            label = { Text("Search in this folder") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (viewModel.query.isNotEmpty()) {
                    IconButton(onClick = { viewModel.clearSearch() }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear search")
                    }
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        // Sorting controls.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(onClick = { sortMenuOpen = true }) {
                Icon(Icons.Filled.Sort, contentDescription = "Sort by")
            }
            DropdownMenu(
                expanded = sortMenuOpen,
                onDismissRequest = { sortMenuOpen = false },
            ) {
                SortBy.entries.forEach { sortBy ->
                    DropdownMenuItem(
                        text = { Text(sortBy.name.lowercase().replaceFirstChar { it.uppercaseChar() }) },
                        onClick = {
                            viewModel.sortBy = sortBy
                            sortMenuOpen = false
                        },
                        trailingIcon = {
                            if (viewModel.sortBy == sortBy) {
                                Icon(Icons.Filled.Check, contentDescription = null)
                            }
                        },
                    )
                }
            }
            IconButton(onClick = { viewModel.sortAscending = !viewModel.sortAscending }) {
                Icon(
                    if (viewModel.sortAscending) Icons.Filled.ArrowUpward
                    else Icons.Filled.ArrowDownward,
                    contentDescription = if (viewModel.sortAscending) "Ascending" else "Descending",
                )
            }
            Checkbox(
                checked = viewModel.dirsFirst,
                onCheckedChange = { viewModel.dirsFirst = it },
            )
            Text("Dirs first", style = MaterialTheme.typography.bodyMedium)
            if (viewModel.isSearching) {
                TextButton(onClick = { viewModel.clearSearch() }) { Text("Clear results") }
            }
        }

        // New file.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = newFileName,
                onValueChange = { newFileName = it },
                label = { Text("New file name") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    viewModel.newFile(newFileName)
                    newFileName = ""
                },
                enabled = newFileName.isNotBlank(),
            ) { Text("New") }
        }

        viewModel.status?.let { status ->
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }

        if (viewModel.visibleEntries.isEmpty()) {
            Text(
                text = if (viewModel.isSearching) "No matches." else "This folder is empty.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(viewModel.visibleEntries, key = { it.path }) { entry ->
                    FileEntryRow(
                        entry = entry,
                        bookmarked = viewModel.isBookmarked(entry.path),
                        onOpen = { viewModel.openEntry(entry) },
                        onToggleBookmark = { viewModel.toggleBookmark(entry.path) },
                        onRename = {
                            renameText = entry.name
                            viewModel.renameTarget = entry
                        },
                        onDelete = { viewModel.deleteConfirm = entry },
                    )
                }
            }
        }
    }

    // Delete confirmation.
    viewModel.deleteConfirm?.let { entry ->
        AlertDialog(
            onDismissRequest = { viewModel.deleteConfirm = null },
            title = { Text("Delete ${entry.name}?") },
            text = {
                Text(
                    if (entry.isDir) "The whole folder will be deleted."
                    else "This file will be deleted."
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteEntry(entry) }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.deleteConfirm = null }) { Text("Cancel") }
            },
        )
    }

    // Rename dialog.
    viewModel.renameTarget?.let { entry ->
        AlertDialog(
            onDismissRequest = { viewModel.renameTarget = null },
            title = { Text("Rename ${entry.name}") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.renameEntry(entry, renameText) }) {
                    Text("Rename")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.renameTarget = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun FileEntryRow(
    entry: FileEntry,
    bookmarked: Boolean,
    onOpen: () -> Unit,
    onToggleBookmark: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (entry.isDir) Icons.Filled.Folder else Icons.Filled.InsertDriveFile,
            contentDescription = if (entry.isDir) "Folder" else "File",
            modifier = Modifier.padding(end = 12.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (entry.isDir) "Folder" else
                    "${formatSize(entry.size)} · ${formatDate(entry.modifiedMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onToggleBookmark) {
            Icon(
                if (bookmarked) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = if (bookmarked) "Remove bookmark" else "Bookmark",
            )
        }
        IconButton(onClick = onRename) {
            Icon(Icons.Filled.Edit, contentDescription = "Rename")
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete")
        }
    }
}
