package com.example

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.DownloadStatus
import com.example.data.ThemeMode
import com.example.engine.UrlValidator
import com.example.ui.AppTab
import com.example.ui.DownloaderViewModel
import com.example.ui.screens.CompletedScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.QueueScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.UdaDownloaderTheme

class MainActivity : ComponentActivity() {

    private val viewModel: DownloaderViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            extractAndPassIntentUrl(intent)
        }

        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            val useDarkTheme = when (settings.themeMode) {
                ThemeMode.SYSTEM -> systemDark
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            UdaDownloaderTheme(darkTheme = useDarkTheme) {
                UdaDownloaderMainContent(viewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractAndPassIntentUrl(intent)
    }

    private fun extractAndPassIntentUrl(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                if (intent.type == "text/plain") {
                    val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                        ?: intent.getStringExtra(Intent.EXTRA_SUBJECT)
                    viewModel.handleIncomingSharedText(sharedText)
                }
            }
            Intent.ACTION_VIEW -> {
                val dataString = intent.dataString
                if (!dataString.isNullOrBlank()) {
                    viewModel.handleIncomingSharedText(dataString)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UdaDownloaderMainContent(viewModel: DownloaderViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
    val homeState by viewModel.homeState.collectAsStateWithLifecycle()
    val allTasks by viewModel.allTasks.collectAsStateWithLifecycle()
    val queueTasks by viewModel.activeQueueTasks.collectAsStateWithLifecycle()
    val completedTasks by viewModel.completedTasks.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    var hasClipboardUrl by remember { mutableStateOf(false) }

    // Kembalikan ke Beranda saat pengguna menekan tombol Kembali pada halaman selain Beranda
    BackHandler(enabled = selectedTab != AppTab.HOME) {
        viewModel.selectTab(AppTab.HOME)
    }

    // Periksa clipboard secara aman hanya ketika aplikasi berada di foreground (ON_RESUME)
    DisposableEffect(lifecycleOwner, settings.clipboardDetectionEnabled) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val clipText = readClipboardTextSafely(context)
                val validUrl = UrlValidator.extractFirstValidUrl(clipText)
                hasClipboardUrl = validUrl != null
                if (validUrl != null) {
                    viewModel.checkClipboardForSuggestion(clipText)
                }
                viewModel.refreshAvailableStorage()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Peluncur izin POST_NOTIFICATIONS untuk Android 13 (API 33) ke atas
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        viewModel.startDownloadFromInput()
    }

    val startDownloadWithPermissionCheck = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            settings.notificationsEnabled &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.startDownloadFromInput()
        }
    }

    val activeRunningOrQueuedCount = remember(queueTasks) {
        queueTasks.count {
            it.status == DownloadStatus.DOWNLOADING ||
                it.status == DownloadStatus.CONNECTING ||
                it.status == DownloadStatus.QUEUED
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when (selectedTab) {
                            AppTab.HOME -> "Uda Downloader"
                            AppTab.DOWNLOADS -> "Antrean & Unduhan"
                            AppTab.COMPLETED -> "Unduhan Selesai"
                            AppTab.SETTINGS -> "Pengaturan"
                        },
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        bottomBar = {
            NavigationBar(
                modifier = Modifier.testTag("bottom_navigation_bar")
            ) {
                NavigationBarItem(
                    selected = selectedTab == AppTab.HOME,
                    onClick = { viewModel.selectTab(AppTab.HOME) },
                    icon = {
                        Icon(
                            imageVector = if (selectedTab == AppTab.HOME) Icons.Filled.Home else Icons.Outlined.Home,
                            contentDescription = AppTab.HOME.title
                        )
                    },
                    label = { Text(AppTab.HOME.title) },
                    modifier = Modifier.testTag("nav_tab_home")
                )

                NavigationBarItem(
                    selected = selectedTab == AppTab.DOWNLOADS,
                    onClick = { viewModel.selectTab(AppTab.DOWNLOADS) },
                    icon = {
                        BadgedBox(
                            badge = {
                                if (activeRunningOrQueuedCount > 0) {
                                    Badge { Text(activeRunningOrQueuedCount.toString()) }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = if (selectedTab == AppTab.DOWNLOADS) Icons.Filled.Download else Icons.Outlined.Download,
                                contentDescription = AppTab.DOWNLOADS.title
                            )
                        }
                    },
                    label = { Text(AppTab.DOWNLOADS.title) },
                    modifier = Modifier.testTag("nav_tab_downloads")
                )

                NavigationBarItem(
                    selected = selectedTab == AppTab.COMPLETED,
                    onClick = { viewModel.selectTab(AppTab.COMPLETED) },
                    icon = {
                        BadgedBox(
                            badge = {
                                if (completedTasks.isNotEmpty()) {
                                    Badge { Text(completedTasks.size.toString()) }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = if (selectedTab == AppTab.COMPLETED) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircleOutline,
                                contentDescription = AppTab.COMPLETED.title
                            )
                        }
                    },
                    label = { Text(AppTab.COMPLETED.title) },
                    modifier = Modifier.testTag("nav_tab_completed")
                )

                NavigationBarItem(
                    selected = selectedTab == AppTab.SETTINGS,
                    onClick = { viewModel.selectTab(AppTab.SETTINGS) },
                    icon = {
                        Icon(
                            imageVector = if (selectedTab == AppTab.SETTINGS) Icons.Filled.Settings else Icons.Outlined.Settings,
                            contentDescription = AppTab.SETTINGS.title
                        )
                    },
                    label = { Text(AppTab.SETTINGS.title) },
                    modifier = Modifier.testTag("nav_tab_settings")
                )
            }
        }
    ) { innerPadding ->
        when (selectedTab) {
            AppTab.HOME -> {
                HomeScreen(
                    homeState = homeState,
                    allTasks = allTasks,
                    confirmBeforeDelete = settings.confirmBeforeDeleteFile,
                    hasClipboardUrl = hasClipboardUrl,
                    onUrlChanged = viewModel::onUrlInputChanged,
                    onCustomFileNameChanged = viewModel::onCustomFileNameChanged,
                    onPasteClick = {
                        val clip = readClipboardTextSafely(context)
                        viewModel.pasteUrlFromClipboard(clip)
                    },
                    onProbeClick = viewModel::probeCurrentUrl,
                    onStartDownloadClick = startDownloadWithPermissionCheck,
                    onBatchEnqueue = viewModel::enqueueMultipleUrls,
                    onAcceptClipboardSuggestion = viewModel::acceptClipboardSuggestion,
                    onDismissClipboardSuggestion = viewModel::dismissClipboardSuggestion,
                    onDismissBanner = viewModel::clearBannerMessage,
                    onPauseTask = viewModel::pauseTask,
                    onResumeTask = viewModel::resumeTask,
                    onCancelTask = viewModel::cancelTask,
                    onRetryTask = viewModel::retryTask,
                    onChangePriority = viewModel::changeTaskPriority,
                    onOpenFile = viewModel::openCompletedFile,
                    onShareFile = viewModel::shareCompletedFile,
                    onOpenFolder = viewModel::openSystemDownloadsFolder,
                    onDeleteTask = viewModel::deleteTask,
                    onNavigateToQueue = { viewModel.selectTab(AppTab.DOWNLOADS) },
                    modifier = Modifier.padding(innerPadding)
                )
            }

            AppTab.DOWNLOADS -> {
                QueueScreen(
                    queueTasks = queueTasks,
                    maxConcurrent = settings.maxConcurrentDownloads,
                    confirmBeforeDelete = settings.confirmBeforeDeleteFile,
                    onPauseAll = viewModel::pauseAllTasks,
                    onResumeAll = viewModel::resumeAllTasks,
                    onPauseTask = viewModel::pauseTask,
                    onResumeTask = viewModel::resumeTask,
                    onCancelTask = viewModel::cancelTask,
                    onRetryTask = viewModel::retryTask,
                    onChangePriority = viewModel::changeTaskPriority,
                    onOpenFile = viewModel::openCompletedFile,
                    onShareFile = viewModel::shareCompletedFile,
                    onOpenFolder = viewModel::openSystemDownloadsFolder,
                    onDeleteTask = viewModel::deleteTask,
                    modifier = Modifier.padding(innerPadding)
                )
            }

            AppTab.COMPLETED -> {
                CompletedScreen(
                    completedTasks = completedTasks,
                    downloadSubfolder = settings.downloadSubfolder,
                    confirmBeforeDelete = settings.confirmBeforeDeleteFile,
                    onOpenFile = viewModel::openCompletedFile,
                    onShareFile = viewModel::shareCompletedFile,
                    onOpenFolder = viewModel::openSystemDownloadsFolder,
                    onDeleteTask = viewModel::deleteTask,
                    modifier = Modifier.padding(innerPadding)
                )
            }

            AppTab.SETTINGS -> {
                SettingsScreen(
                    settings = settings,
                    onMaxConcurrentChange = viewModel::updateMaxConcurrent,
                    onSubfolderChange = viewModel::updateDownloadSubfolder,
                    onWifiOnlyChange = viewModel::updateWifiOnly,
                    onAutoRetryChange = viewModel::updateAutoRetry,
                    onMaxRetriesChange = viewModel::updateMaxRetries,
                    onMultiSegmentChange = viewModel::updateMultiSegment,
                    onSegmentsCountChange = viewModel::updateSegmentsPerDownload,
                    onNotificationsChange = viewModel::updateNotificationsEnabled,
                    onThemeModeChange = viewModel::updateThemeMode,
                    onConfirmBeforeDeleteChange = viewModel::updateConfirmBeforeDelete,
                    onClipboardDetectionChange = viewModel::updateClipboardDetection,
                    onCleanupTempFiles = viewModel::cleanupOrphanedTempFiles,
                    modifier = Modifier.padding(innerPadding)
                )
            }
        }
    }
}

private fun readClipboardTextSafely(context: Context): String? {
    return runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        if (!cm.hasPrimaryClip()) return null
        val item = cm.primaryClip?.getItemAt(0) ?: return null
        item.coerceToText(context)?.toString()
    }.getOrNull()
}
