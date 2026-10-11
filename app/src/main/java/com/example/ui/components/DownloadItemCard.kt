package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.DownloadStatus
import com.example.data.DownloadTaskEntity
import com.example.data.FileCategory
import com.example.ui.theme.StatusCancelled
import com.example.ui.theme.StatusCompleted
import com.example.ui.theme.StatusDownloading
import com.example.ui.theme.StatusFailed
import com.example.ui.theme.StatusPaused
import com.example.ui.theme.StatusQueued
import com.example.util.FormatUtils

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DownloadItemCard(
    task: DownloadTaskEntity,
    confirmBeforeDelete: Boolean,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onCancel: (String) -> Unit,
    onRetry: (String, Boolean) -> Unit,
    onChangePriority: (String, Int) -> Unit,
    onOpenFile: (DownloadTaskEntity) -> Unit,
    onShareFile: (DownloadTaskEntity) -> Unit,
    onOpenFolder: () -> Unit,
    onDelete: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showDetailsDialog by remember { mutableStateOf(false) }

    val statusColor = remember(task.status) {
        when (task.status) {
            DownloadStatus.DOWNLOADING, DownloadStatus.CONNECTING -> StatusDownloading
            DownloadStatus.QUEUED -> StatusQueued
            DownloadStatus.PAUSED -> StatusPaused
            DownloadStatus.COMPLETED -> StatusCompleted
            DownloadStatus.FAILED -> StatusFailed
            DownloadStatus.CANCELLED -> StatusCancelled
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("download_item_${task.id}"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Baris Atas: Ikon Kategori File + Nama File + Badge Status + Detail/Hapus
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(statusColor.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = iconForCategory(task.category),
                        contentDescription = task.category.labelId,
                        tint = statusColor,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.fileName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        StatusBadge(status = task.status, color = statusColor)
                        Text(
                            text = "• ${task.category.labelId}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (task.segmentCount > 1) {
                            Text(
                                text = "• ${task.segmentCount} Segmen",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                IconButton(
                    onClick = { showDetailsDialog = true },
                    modifier = Modifier.testTag("details_btn_${task.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Info Detail Unduhan",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(
                    onClick = {
                        if (task.status == DownloadStatus.COMPLETED && !confirmBeforeDelete) {
                            onDelete(task.id, false)
                        } else {
                            showDeleteDialog = true
                        }
                    },
                    modifier = Modifier.testTag("delete_btn_${task.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Hapus dari Daftar",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Baris Data Ukuran, Persentase Nyata, Kecepatan, dan ETA
            val percent = task.progressPercent
            val fraction = task.progressFraction
            val etaSec = task.calculatedEtaSeconds

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val byteProgressText = if (task.totalBytes > 0L) {
                    "${FormatUtils.formatBytes(task.downloadedBytes)} / ${FormatUtils.formatBytes(task.totalBytes)}"
                } else {
                    "${FormatUtils.formatBytes(task.downloadedBytes)} diterima (Ukuran total tidak diketahui)"
                }

                Text(
                    text = byteProgressText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (percent != null) {
                    Text(
                        text = "$percent%",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = statusColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Progress Bar: Determinate jika ukuran total diketahui, Indeterminate hanya saat aktif tanpa Content-Length
            if (fraction != null) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(CircleShape),
                    color = statusColor,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            } else if (task.status == DownloadStatus.DOWNLOADING || task.status == DownloadStatus.CONNECTING) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(CircleShape),
                    color = statusColor,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            } else {
                LinearProgressIndicator(
                    progress = { if (task.status == DownloadStatus.COMPLETED) 1f else 0f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(CircleShape),
                    color = statusColor,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }

            // Baris Kecepatan & Estimasi Waktu Tersisa (hanya saat sedang mengunduh)
            AnimatedVisibility(visible = task.status == DownloadStatus.DOWNLOADING || task.status == DownloadStatus.CONNECTING) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Kecepatan: ${FormatUtils.formatSpeed(task.speedBytesPerSec)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = if (etaSec != null) {
                            "Sisa waktu: ${FormatUtils.formatEta(etaSec)}"
                        } else {
                            "Sisa waktu: Menghitung..."
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Informasi Dukungan Range & Prioritas Antrean
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (task.supportsRange) {
                        "Resume (Range): Didukung server"
                    } else {
                        "Resume (Range): Belum dikonfirmasi / Tidak didukung"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (task.status == DownloadStatus.QUEUED || task.status == DownloadStatus.PAUSED) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Prioritas: ${task.priority}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        IconButton(
                            onClick = { onChangePriority(task.id, 1) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowUp,
                                contentDescription = "Naikkan Prioritas",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(
                            onClick = { onChangePriority(task.id, -1) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = "Turunkan Prioritas",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            // Peringatan Halaman HTML
            if (task.isHtmlWarning) {
                WarningBanner(
                    icon = Icons.Default.WarningAmber,
                    message = "Tautan ini terdeteksi sebagai halaman web HTML (text/html), bukan file biner langsung.",
                    isError = false
                )
            }

            // Peringatan Resume / Range
            if (!task.resumeWarning.isNullOrBlank()) {
                WarningBanner(
                    icon = Icons.Default.Info,
                    message = task.resumeWarning,
                    isError = false
                )
            }

            // Pesan Kesalahan / Retry
            if (!task.errorMessage.isNullOrBlank()) {
                WarningBanner(
                    icon = Icons.Default.ErrorOutline,
                    message = task.errorMessage,
                    isError = task.status == DownloadStatus.FAILED
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Tombol Tindakan Sesuai Status
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (task.status.canPause()) {
                    FilledTonalButton(
                        onClick = { onPause(task.id) },
                        modifier = Modifier.testTag("pause_btn_${task.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Pause,
                            contentDescription = "Jeda",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Jeda")
                    }
                }

                if (task.status.canResume()) {
                    Button(
                        onClick = { onResume(task.id) },
                        modifier = Modifier.testTag("resume_btn_${task.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Lanjutkan",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Lanjutkan")
                    }
                }

                if (task.status.canRetry()) {
                    Button(
                        onClick = { onRetry(task.id, false) },
                        modifier = Modifier.testTag("retry_btn_${task.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Coba Lagi",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (task.downloadedBytes > 0L && task.supportsRange) "Lanjutkan / Coba Lagi" else "Coba Lagi")
                    }
                }

                if (task.status.canCancel()) {
                    OutlinedButton(
                        onClick = { onCancel(task.id) },
                        modifier = Modifier.testTag("cancel_btn_${task.id}"),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Batalkan",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Batalkan")
                    }
                }

                if (task.status == DownloadStatus.COMPLETED) {
                    Button(
                        onClick = { onOpenFile(task) },
                        modifier = Modifier.testTag("open_file_btn_${task.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = "Buka File",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Buka File")
                    }

                    FilledTonalButton(
                        onClick = { onOpenFolder() },
                        modifier = Modifier.testTag("open_folder_btn_${task.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = "Lokasi Unduhan",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Lokasi")
                    }

                    FilledTonalIconButton(
                        onClick = { onShareFile(task) },
                        modifier = Modifier.testTag("share_file_btn_${task.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Bagikan File"
                        )
                    }
                }
            }
        }
    }

    if (showDeleteDialog) {
        DeleteTaskConfirmationDialog(
            task = task,
            onDismiss = { showDeleteDialog = false },
            onConfirm = { deletePhysical ->
                showDeleteDialog = false
                onDelete(task.id, deletePhysical)
            }
        )
    }

    if (showDetailsDialog) {
        TaskDetailsDialog(
            task = task,
            onDismiss = { showDetailsDialog = false }
        )
    }
}

@Composable
private fun StatusBadge(status: DownloadStatus, color: Color) {
    Surface(
        color = color.copy(alpha = 0.14f),
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            text = status.labelId,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun WarningBanner(
    icon: ImageVector,
    message: String,
    isError: Boolean
) {
    val bgColor = if (isError) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f)
    }
    val contentColor = if (isError) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = contentColor
        )
    }
}

@Composable
private fun DeleteTaskConfirmationDialog(
    task: DownloadTaskEntity,
    onDismiss: () -> Unit,
    onConfirm: (Boolean) -> Unit
) {
    var alsoDeletePhysicalFile by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Hapus Tugas Unduhan?") },
        text = {
            Column {
                Text(
                    text = "Apakah Anda ingin menghapus '${task.fileName}' dari daftar riwayat Uda Downloader?",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (task.status == DownloadStatus.COMPLETED) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Checkbox(
                            checked = alsoDeletePhysicalFile,
                            onCheckedChange = { alsoDeletePhysicalFile = it }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Ikut hapus file selesai dari penyimpanan perangkat (Folder Download)",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(alsoDeletePhysicalFile) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Hapus")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Batal")
            }
        }
    )
}

@Composable
private fun TaskDetailsDialog(
    task: DownloadTaskEntity,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Detail Metadata Unduhan") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DetailRow("Nama File", task.fileName)
                DetailRow("Kategori", "${task.category.labelId} (${task.mimeType})")
                DetailRow("Status", task.status.labelId)
                DetailRow("URL Sumber", task.url)
                if (task.finalUrl != task.url) {
                    DetailRow("URL Akhir (Redirect)", task.finalUrl)
                }
                DetailRow(
                    "Ukuran Diterima / Total",
                    "${FormatUtils.formatBytes(task.downloadedBytes)} / ${FormatUtils.formatBytes(task.totalBytes)}"
                )
                DetailRow(
                    "Dukungan HTTP Range",
                    if (task.supportsRange) "Ya (Dapat dijeda & dilanjutkan)" else "Tidak / Belum terkonfirmasi"
                )
                if (!task.eTag.isNullOrBlank()) {
                    DetailRow("Validator ETag", task.eTag)
                }
                if (!task.lastModified.isNullOrBlank()) {
                    DetailRow("Last-Modified", task.lastModified)
                }
                DetailRow("Jumlah Percobaan Ulang", "${task.retryCount} kali")
                DetailRow("Subfolder Tujuan", "Download/${task.customSubfolder}")
                if (!task.checksumSha256.isNullOrBlank()) {
                    DetailRow("SHA-256 Checksum", task.checksumSha256)
                }
                DetailRow("Waktu Dibuat", FormatUtils.formatDateTime(task.createdAt))
                DetailRow("Pembaruan Terakhir", FormatUtils.formatDateTime(task.updatedAt))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Tutup")
            }
        }
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

fun iconForCategory(category: FileCategory): ImageVector = when (category) {
    FileCategory.APK -> Icons.Default.Android
    FileCategory.ARCHIVE -> Icons.Default.Archive
    FileCategory.DOCUMENT -> Icons.Default.Description
    FileCategory.IMAGE -> Icons.Default.Image
    FileCategory.VIDEO -> Icons.Default.VideoFile
    FileCategory.AUDIO -> Icons.Default.AudioFile
    FileCategory.CODE -> Icons.Default.Code
    FileCategory.OTHER -> Icons.AutoMirrored.Outlined.InsertDriveFile
}
