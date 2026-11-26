package com.example.helloworld.ui

import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun HtmlViewerScreen(path: String) {
    var jsEnabled by remember(path) { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = jsEnabled,
                onClick = { jsEnabled = !jsEnabled },
                label = { Text(if (jsEnabled) "JS: ON" else "JS: OFF") },
            )
        }
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.allowFileAccess = true
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    settings.javaScriptEnabled = jsEnabled
                    loadUrl("file://$path")
                }
            },
            update = { webView ->
                if (webView.settings.javaScriptEnabled != jsEnabled) {
                    webView.settings.javaScriptEnabled = jsEnabled
                    webView.reload()
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
