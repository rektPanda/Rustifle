package com.example.helloworld.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File

/**
 * Viewer tab dispatcher: PDF, image, audio or video, chosen by the
 * ViewModel from the file extension (see `FileBrowserViewModel`).
 */
@Composable
fun ViewerScreen(viewModel: FileBrowserViewModel) {
    val file = viewModel.viewerFile

    if (file == null) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
        ) {
            Text(
                text = "No file open.",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Tap a PDF, image, audio or video file in the Files tab " +
                    "to view or play it here.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = when (file.kind) {
                    ViewerKind.PDF -> "PDF"
                    ViewerKind.IMAGE -> "Image"
                    ViewerKind.AUDIO -> "Audio"
                    ViewerKind.VIDEO -> "Video"
                    ViewerKind.HTML -> "HTML"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp),
            )
            Text(
                text = File(file.path).name,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            )
            IconButton(onClick = { viewModel.closeViewer() }) {
                Icon(Icons.Filled.Close, contentDescription = "Close viewer")
            }
        }
        when (file.kind) {
            ViewerKind.PDF -> PdfViewerScreen(path = file.path)
            ViewerKind.IMAGE -> ImageViewerScreen(path = file.path)
            ViewerKind.AUDIO -> MediaViewerScreen(path = file.path, isVideo = false)
            ViewerKind.VIDEO -> MediaViewerScreen(path = file.path, isVideo = true)
            ViewerKind.HTML -> HtmlViewerScreen(path = file.path)
        }
    }
}
