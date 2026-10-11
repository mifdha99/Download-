package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Queue
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.DownloadStatus
import com.example.data.DownloadTaskEntity
import com.example.ui.HomeUiState
import com.example.ui.components.DownloadItemCard
import com.example.ui.theme.StatusCompleted
import com.example.ui.theme.StatusDownloading
import com.example.ui.theme.StatusQueued
import com.example.util.FormatUtils

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    homeState: HomeUiState,
    allTasks: List<DownloadTaskEntity>,
    confirmBeforeDelete: Boolean,
    hasClipboardUrl: Boolean,
    onUrlChanged: (String) -> Unit,
    onCustomFileNameChanged: (String) -> Unit,
    onPasteClick: () -> Unit,
    onProbeClick: () -> Unit,
    onStartDownloadClick: () -> Unit,
    onBatchEnqueue: (String) -> Unit,
    onAcceptClipboardSuggestion: () -> Unit,
    onDismissClipboardSuggestion: () -> Unit,
    onDismissBanner: () -> Unit,
    onPauseTask: (String) -> Unit,
    onResumeTask: (String) -> Unit,
    onCancelTask: (String) -> Unit,
    onRetryTask: (String, Boolean) -> Unit,
    onChangePriority: (String, Int) -> Unit,
    onOpenFile: (DownloadTaskEntity) -> Unit,
    onShareFile: (DownloadTaskEntity) -> Unit,
    onOpenFolder: () -> Unit,
    onDeleteTask: (String, Boolean) -> Unit,
    onNavigateToQueue: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showCustomNameField by remember { mutableStateOf(false) }
    var showBatchDialog by remember { mutableStateOf(false) }

    val activeCount = remember(allTasks) {
        allTasks.count { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.CONNECTING }
    }
    val queuedOrPausedCount = remember(allTasks) {
        allTasks.count { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.PAUSED }
    }
    val completedCount = remember(allTasks) {
        allTasks.count { it.status == DownloadStatus.COMPLETED }
    }
    val totalActiveSpeed = remember(allTasks) {
        allTasks.filter { it.status == DownloadStatus.DOWNLOADING }.sumOf { it.speedBytesPerSec }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("home_screen_list"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Hero Banner dengan Kecepatan Aktif & Sisa Penyimpanan
        item {
            HeroHeaderCard(
                totalSpeed = totalActiveSpeed,
                availableStorageText = homeState.availableStorageText
            )
        }

        // 2. Saran Deteksi Tautan Clipboard (Muncul transparan jika clipboard berisi URL HTTP/HTTPS)
        if (!homeState.clipboardSuggestedUrl.isNullOrBlank()) {
            item {
                ClipboardSuggestionCard(
                    suggestedUrl = homeState.clipboardSuggestedUrl,
                    onAccept = onAcceptClipboardSuggestion,
                    onDismiss = onDismissClipboardSuggestion
                )
            }
        }

        // 3. Banner Umpan Balik Tindakan
        if (!homeState.statusBannerMessage.isNullOrBlank()) {
            item {
                StatusFeedbackBanner(
                    message = homeState.statusBannerMessage,
                    isWarning = homeState.isBannerWarning,
                    onDismiss = onDismissBanner
                )
            }
        }

        // 4. Kartu Input URL & Pemeriksa File
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.AddLink,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Unduh File Baru",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        TextButton(
                            onClick = { showBatchDialog = true },
                            modifier = Modifier.testTag("batch_url_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                                contentDescription = "Tambah Banyak URL",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Banyak URL")
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = homeState.urlInput,
                        onValueChange = onUrlChanged,
                        label = { Text("Masukkan atau tempel URL (HTTP/HTTPS)") },
                        placeholder = { Text("https://contoh.com/file-arsip.zip") },
                        singleLine = true,
                        isError = homeState.urlValidationError != null,
                        supportingText = {
                            if (homeState.urlValidationError != null) {
                                Text(
                                    text = homeState.urlValidationError,
                                    color = MaterialTheme.colorScheme.error
                                )
                            } else {
                                Text("Mendukung APK, PDF, ZIP, Video, Audio, Gambar, & file langsung lainnya")
                            }
                        },
                        trailingIcon = {
                            if (homeState.urlInput.isNotEmpty()) {
                                IconButton(
                                    onClick = { onUrlChanged("") },
                                    modifier = Modifier.testTag("clear_url_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Clear,
                                        contentDescription = "Bersihkan URL"
                                    )
                                }
                            }
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { onStartDownloadClick() }
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("url_input_field")
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = { showCustomNameField = !showCustomNameField }
                        ) {
                            Text(
                                if (showCustomNameField) "Sembunyikan Nama File Kustom"
                                else "Atur Nama File Kustom (Opsional)"
                            )
                        }
                    }

                    AnimatedVisibility(visible = showCustomNameField || homeState.customFileNameInput.isNotEmpty()) {
                        OutlinedTextField(
                            value = homeState.customFileNameInput,
                            onValueChange = onCustomFileNameChanged,
                            label = { Text("Nama File Kustom (Opsional)") },
                            placeholder = { Text("Otomatis dari server jika dikosongkan") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 10.dp)
                                .testTag("custom_filename_field")
                        )
                    }

                    // Hasil Pemeriksaan (Probe) Metadata Server
                    val probe = homeState.probeState
                    if (probe.isLoading) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 8.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Memeriksa informasi file dari server...",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    } else if (probe.metadata != null) {
                        val meta = probe.metadata
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "Informasi File Terdeteksi:",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Nama: ${meta.fileName}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    text = "Ukuran: ${FormatUtils.formatBytes(meta.totalBytes)} • Tipe: ${meta.mimeType}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    text = if (meta.supportsRange) {
                                        "Fitur Lanjutkan (HTTP Range): Didukung oleh server"
                                    } else {
                                        "Fitur Lanjutkan (HTTP Range): Tidak didukung server"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (meta.supportsRange) StatusCompleted else MaterialTheme.colorScheme.error
                                )
                                if (meta.isHtmlWarning) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.WarningAmber,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "Tautan ini mengarah ke halaman web HTML, bukan file unduhan langsung.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    } else if (!probe.errorIndo.isNullOrBlank()) {
                        Text(
                            text = probe.errorIndo,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onStartDownloadClick,
                            enabled = !homeState.isSubmitting && homeState.urlInput.isNotBlank(),
                            modifier = Modifier
                                .weight(1.4f)
                                .testTag("start_download_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = "Mulai Unduh",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (homeState.isSubmitting) "Memproses..." else "Unduh")
                        }

                        FilledTonalButton(
                            onClick = onPasteClick,
                            modifier = Modifier.testTag("paste_url_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentPaste,
                                contentDescription = "Tempel URL dari Clipboard",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (hasClipboardUrl) "Tempel URL" else "Tempel")
                        }

                        OutlinedButton(
                            onClick = onProbeClick,
                            enabled = homeState.urlInput.isNotBlank() && !probe.isLoading,
                            modifier = Modifier.testTag("probe_url_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Periksa Info File",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Cek Info")
                        }
                    }
                }
            }
        }

        // 5. Ringkasan Jumlah Unduhan Aktif, Antrean, dan Selesai
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SummaryStatCard(
                    title = "Aktif",
                    count = activeCount,
                    icon = Icons.Default.Downloading,
                    accentColor = StatusDownloading,
                    modifier = Modifier.weight(1f)
                )
                SummaryStatCard(
                    title = "Antrean / Jeda",
                    count = queuedOrPausedCount,
                    icon = Icons.Default.Queue,
                    accentColor = StatusQueued,
                    modifier = Modifier.weight(1f)
                )
                SummaryStatCard(
                    title = "Selesai",
                    count = completedCount,
                    icon = Icons.Default.CheckCircle,
                    accentColor = StatusCompleted,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // 6. Daftar Unduhan Terbaru
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Daftar Unduhan Terbaru",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                if (allTasks.size > 5) {
                    TextButton(onClick = onNavigateToQueue) {
                        Text("Lihat Semua (${allTasks.size})")
                    }
                }
            }
        }

        if (allTasks.isEmpty()) {
            item {
                EmptyDownloadsPlaceholder()
            }
        } else {
            items(
                items = allTasks.take(15),
                key = { it.id }
            ) { task ->
                DownloadItemCard(
                    task = task,
                    confirmBeforeDelete = confirmBeforeDelete,
                    onPause = onPauseTask,
                    onResume = onResumeTask,
                    onCancel = onCancelTask,
                    onRetry = onRetryTask,
                    onChangePriority = onChangePriority,
                    onOpenFile = onOpenFile,
                    onShareFile = onShareFile,
                    onOpenFolder = onOpenFolder,
                    onDelete = onDeleteTask
                )
            }
        }
    }

    if (showBatchDialog) {
        BatchUrlDialog(
            onDismiss = { showBatchDialog = false },
            onConfirm = { text ->
                showBatchDialog = false
                onBatchEnqueue(text)
            }
        )
    }
}

@Composable
private fun HeroHeaderCard(
    totalSpeed: Long,
    availableStorageText: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(148.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.img_hero_banner_1791721818873),
                contentDescription = "Banner Uda Downloader",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                Color(0xE607132B),
                                Color(0xB30B254E),
                                Color(0x8007132B)
                            )
                        )
                    )
                    .padding(18.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "Uda Downloader",
                            style = MaterialTheme.typography.headlineMedium,
                            color = Color.White
                        )
                        Text(
                            text = "Mesin Unduh HTTP/HTTPS • Resume Range • Antrean Pintar",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFB5EBFF)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = Color(0x3300E5FF),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                text = "Kecepatan Aktif: ${FormatUtils.formatSpeed(totalSpeed)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Storage,
                                contentDescription = null,
                                tint = Color(0xFFB5EBFF),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Tersedia: $availableStorageText",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ClipboardSuggestionCard(
    suggestedUrl: String,
    onAccept: () -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("clipboard_suggestion_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.ContentPaste,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Tautan Terdeteksi di Clipboard",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = suggestedUrl,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth()
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Abaikan")
                }
                Spacer(modifier = Modifier.width(6.dp))
                Button(onClick = onAccept) {
                    Text("Muat URL")
                }
            }
        }
    }
}

@Composable
private fun StatusFeedbackBanner(
    message: String,
    isWarning: Boolean,
    onDismiss: () -> Unit
) {
    val bgColor = if (isWarning) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }
    val textColor = if (isWarning) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }

    Surface(
        color = bgColor,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isWarning) Icons.Default.WarningAmber else Icons.Default.Info,
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = textColor,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Clear,
                    contentDescription = "Tutup Pesan",
                    tint = textColor,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun SummaryStatCard(
    title: String,
    count: Int,
    icon: ImageVector,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = accentColor,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun EmptyDownloadsPlaceholder() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Download,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(44.dp)
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Belum Ada Tugas Unduhan",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Tempel tautan langsung HTTP/HTTPS di atas atau bagikan tautan dari browser melalui menu Bagikan (Sharesheet) ke Uda Downloader.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun BatchUrlDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var multilineUrls by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tambahkan Banyak URL Sekaligus") },
        text = {
            Column {
                Text(
                    text = "Masukkan satu URL (HTTP/HTTPS) pada setiap baris untuk dimasukkan ke dalam antrean unduhan:",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = multilineUrls,
                    onValueChange = { multilineUrls = it },
                    placeholder = {
                        Text("https://contoh.com/file1.zip\nhttps://contoh.com/file2.pdf")
                    },
                    minLines = 4,
                    maxLines = 8,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("batch_url_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(multilineUrls) },
                enabled = multilineUrls.isNotBlank()
            ) {
                Text("Masukkan ke Antrean")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Batal")
            }
        }
    )
}
