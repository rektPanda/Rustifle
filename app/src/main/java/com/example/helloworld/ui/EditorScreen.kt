package com.example.helloworld.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Monospace text / code editor, connected to RustBridge through the
 * ViewModel: files are read with paged `readLines` (line-indexed
 * `readLinesIndexed` for Go to line) and saved with `writeTextFile`.
 *
 * Save is only enabled once the whole file is loaded, so a partial
 * preview can never silently truncate a file.
 */
@Composable
fun EditorScreen(viewModel: FileBrowserViewModel) {
    val editor = viewModel.editor
    var goToText by remember { mutableStateOf("") }

    if (editor.path == null) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "No file open.",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Tap a file in the Files tab to open it here, " +
                    "or create a new file from Files.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {

        // Header: file name, dirty marker, actions.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = (if (editor.dirty) "• " else "") + editor.path.substringAfterLast('/'),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { viewModel.saveEditor() },
                enabled = editor.dirty && editor.fullyLoaded && !editor.loading,
            ) {
                Icon(Icons.Filled.Save, contentDescription = "Save")
            }
            IconButton(onClick = { viewModel.reloadEditor() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Reload (discard edits)")
            }
            IconButton(onClick = { viewModel.closeEditor() }) {
                Icon(Icons.Filled.Close, contentDescription = "Close")
            }
        }
        Text(
            text = editor.path,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Status line: lines, SHA-256, loading, errors.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (editor.loading) {
                CircularProgressIndicator(modifier = Modifier.padding(end = 4.dp))
            }
            Text(
                text = if (editor.totalLines > 0) "${editor.totalLines} lines" else "…",
                style = MaterialTheme.typography.bodySmall,
            )
            editor.sha256?.let { hash ->
                Text(
                    text = "sha256 ${hash.take(12)}…",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        editor.error?.let { error ->
            Text(text = error, color = MaterialTheme.colorScheme.error)
        }
        editor.info?.let { info ->
            Text(
                text = info,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }

        // Go to line (Rust line index).
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = goToText,
                onValueChange = { goToText = it.filter { ch -> ch.isDigit() } },
                label = { Text("Go to line") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    goToText.toLongOrNull()?.let { viewModel.goToLine(it) }
                },
                enabled = goToText.isNotEmpty() && !editor.loading,
            ) { Text("Go") }
        }

        // Monospace editing area with a line-number gutter. Both columns
        // share one vertical scroll and the same 20sp line height, so the
        // numbers stay aligned with the text.
        val scrollState = rememberScrollState()
        val lineHeight = 20.sp
        val mono = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            lineHeight = lineHeight,
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                val lineCount = editor.text.lines().size
                Column(
                    modifier = Modifier
                        .width(48.dp)
                        .padding(end = 8.dp),
                    horizontalAlignment = Alignment.End,
                ) {
                    repeat(lineCount) { index ->
                        Text(
                            text = (index + 1).toString(),
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = lineHeight,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.End,
                            ),
                        )
                    }
                }
                BasicTextField(
                    value = editor.text,
                    onValueChange = { viewModel.onEditorTextChange(it) },
                    textStyle = mono,
                    readOnly = !editor.fullyLoaded,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
