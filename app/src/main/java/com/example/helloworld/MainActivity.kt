package com.example.helloworld

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.helloworld.ui.BookmarksScreen
import com.example.helloworld.ui.EditorScreen
import com.example.helloworld.ui.FileBrowserScreen
import com.example.helloworld.ui.FileBrowserViewModel
import com.example.helloworld.ui.FileBrowserViewModelFactory
import com.example.helloworld.ui.HomeScreen
import com.example.helloworld.ui.SharedPreferencesBookmarkStore
import com.example.helloworld.ui.ViewerScreen

private enum class TabPage(val title: String) {
    HOME("Home"),
    FILES("Files"),
    BOOKMARKS("Bookmarks"),
    EDITOR("Editor"),
    VIEWER("Viewer"),
}

private fun checkStorageAccess(context: android.content.Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
    }
}

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                val context = LocalContext.current.applicationContext
                val lifecycleOwner = this@MainActivity
                var hasStorageAccess by rememberSaveable { mutableStateOf(checkStorageAccess(context)) }

                val legacyPermLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions(),
                ) {
                    hasStorageAccess = checkStorageAccess(context)
                }

                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            hasStorageAccess = checkStorageAccess(context)
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                val extRoot = Environment.getExternalStorageDirectory().absolutePath
                val roots = listOf(
                    extRoot,
                    context.filesDir.absolutePath,
                    context.cacheDir.absolutePath,
                )
                val viewModel: FileBrowserViewModel = viewModel(
                    factory = FileBrowserViewModelFactory(
                        SharedPreferencesBookmarkStore(context),
                        roots,
                    ),
                )
                var selectedTab by rememberSaveable { mutableStateOf(TabPage.FILES.ordinal) }

                LaunchedEffect(viewModel.openEditorRequest) {
                    if (viewModel.openEditorRequest != 0L) {
                        selectedTab = TabPage.EDITOR.ordinal
                    }
                }
                LaunchedEffect(viewModel.openViewerRequest) {
                    if (viewModel.openViewerRequest != 0L) {
                        selectedTab = TabPage.VIEWER.ordinal
                    }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopAppBar(title = { Text("Rust File Manager") })
                    },
                ) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                    ) {
                        if (!hasStorageAccess) {
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = "Izin penyimpanan diperlukan untuk mengakses /storage",
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                                    )
                                    Button(onClick = {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                            val intent = Intent(
                                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                                Uri.parse("package:$packageName"),
                                            )
                                            startActivity(intent)
                                        } else {
                                            legacyPermLauncher.launch(
                                                arrayOf(
                                                    Manifest.permission.READ_EXTERNAL_STORAGE,
                                                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                                                ),
                                            )
                                        }
                                    }) {
                                        Text("Izinkan")
                                    }
                                }
                            }
                        }

                        ScrollableTabRow(selectedTabIndex = selectedTab, edgePadding = 0.dp) {
                            TabPage.entries.forEachIndexed { index, page ->
                                Tab(
                                    selected = selectedTab == index,
                                    onClick = { selectedTab = index },
                                    text = { Text(page.title) },
                                )
                            }
                        }
                        when (TabPage.entries[selectedTab]) {
                            TabPage.HOME -> HomeScreen(
                                viewModel = viewModel,
                                onOpenFiles = { selectedTab = TabPage.FILES.ordinal },
                                onOpenEditor = { selectedTab = TabPage.EDITOR.ordinal },
                            )
                            TabPage.FILES -> FileBrowserScreen(viewModel = viewModel)
                            TabPage.BOOKMARKS -> BookmarksScreen(
                                viewModel = viewModel,
                                onOpenFolder = { selectedTab = TabPage.FILES.ordinal },
                            )
                            TabPage.EDITOR -> EditorScreen(viewModel = viewModel)
                            TabPage.VIEWER -> ViewerScreen(viewModel = viewModel)
                        }
                    }
                }
            }
        }
    }
}
