package com.example.helloworld.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.helloworld.RustBridge
import java.io.File

/** Home tab: the original Hello World screen + live Rust status. */
@Composable
fun HomeScreen(
    viewModel: FileBrowserViewModel,
    onOpenFiles: () -> Unit,
    onOpenEditor: () -> Unit,
) {
    val context = LocalContext.current
    var rustStatus by remember { mutableStateOf("Rust: checking…") }

    LaunchedEffect(Unit) {
        rustStatus = try {
            if (!RustBridge.isAvailable) {
                "Rust: librust_core.so not in this build — " +
                    "the browser and editor use the built-in fallback."
            } else {
                val demo = File(context.cacheDir, "rust_demo.txt")
                demo.writeText("one\ntwo\nthree\n")
                val hash = RustBridge.sha256File(demo.absolutePath)
                val lines = RustBridge.lineCount(demo.absolutePath)
                val entries = RustBridge.listFiles(context.cacheDir.absolutePath)
                "Rust OK: ${entries.size} files in cache · " +
                    "demo.txt: $lines lines · sha256 ${hash.take(12)}…"
            }
        } catch (e: Exception) {
            "Rust unavailable: ${e.message}"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Hello World",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = rustStatus,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = "${viewModel.bookmarks.size} bookmark(s) · " +
                "Rust ${if (viewModel.rustAvailable) "loaded" else "not loaded (fallback active)"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        Button(onClick = onOpenFiles, modifier = Modifier.padding(top = 24.dp)) {
            Text("Browse files")
        }
        Button(onClick = onOpenEditor, modifier = Modifier.padding(top = 8.dp)) {
            Text("Open editor")
        }
    }
}
