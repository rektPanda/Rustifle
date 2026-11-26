package com.example.helloworld.ui

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * PDF viewing with the framework [PdfRenderer]: one page at a time,
 * rendered to a bitmap at 2x for crisp zooming. Pinch to zoom / pan,
 * or use the slider; page buttons move between pages.
 */
@Composable
fun PdfViewerScreen(path: String) {
    var pageCount by remember(path) { mutableStateOf(0) }
    var currentPage by remember(path) { mutableStateOf(0) }
    var bitmap by remember(path) { mutableStateOf<Bitmap?>(null) }
    var error by remember(path) { mutableStateOf<String?>(null) }
    var scale by remember(path) { mutableStateOf(1f) }
    var offset by remember(path) { mutableStateOf(Offset.Zero) }

    val renderer = remember(path) {
        try {
            val fd = ParcelFileDescriptor.open(
                File(path),
                ParcelFileDescriptor.MODE_READ_ONLY,
            )
            PdfRenderer(fd)
        } catch (e: Exception) {
            error = "Could not open PDF: ${e.message}"
            null
        }
    }
    DisposableEffect(path) {
        onDispose { renderer?.close() }
    }

    LaunchedEffect(path) {
        pageCount = renderer?.pageCount ?: 0
    }

    LaunchedEffect(path, currentPage) {
        val old = bitmap
        bitmap = null
        old?.recycle()
        val r = renderer ?: return@LaunchedEffect
        if (r.pageCount == 0) return@LaunchedEffect
        val pageToRender = currentPage.coerceIn(0, r.pageCount - 1)
        bitmap = withContext(Dispatchers.IO) {
            r.openPage(pageToRender).use { page ->
                val renderScale = 2f
                val bmp = Bitmap.createBitmap(
                    (page.width * renderScale).toInt(),
                    (page.height * renderScale).toInt(),
                    Bitmap.Config.ARGB_8888,
                )
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp
            }
        }
    }

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val newScale = (scale * zoomChange).coerceIn(1f, 4f)
        scale = newScale
        offset = if (newScale <= 1f) Offset.Zero else offset + panChange
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = File(path).name,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = {
                    if (currentPage > 0) {
                        currentPage--
                        scale = 1f
                        offset = Offset.Zero
                    }
                },
                enabled = currentPage > 0,
            ) { Text("‹ Prev") }
            Text(
                text = if (pageCount > 0) "Page ${currentPage + 1} / $pageCount" else "…",
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(
                onClick = {
                    if (currentPage < pageCount - 1) {
                        currentPage++
                        scale = 1f
                        offset = Offset.Zero
                    }
                },
                enabled = currentPage < pageCount - 1,
            ) { Text("Next ›") }
            Slider(
                value = scale,
                onValueChange = { scale = it },
                valueRange = 1f..4f,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { scale = 1f; offset = Offset.Zero }) { Text("Reset") }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .transformable(transformState),
            contentAlignment = Alignment.Center,
        ) {
            when {
                error != null -> Text(
                    text = error!!,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )
                bitmap == null -> CircularProgressIndicator()
                else -> Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = "PDF page ${currentPage + 1}",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y,
                        ),
                )
            }
        }
    }
}
