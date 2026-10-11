package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Queue
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.DownloadStatus
import com.example.data.DownloadTaskEntity
import com.example.ui.components.DownloadItemCard

private enum class QueueFilter(val label: String) {
    ALL("Semua"),
    DOWNLOADING("Mengunduh"),
    QUEUED("Menunggu"),
    PAUSED("Dijeda"),
    FAILED("Gagal / Batal")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QueueScreen(
    queueTasks: List<DownloadTaskEntity>,
    maxConcurrent: Int,
    confirmBeforeDelete: Boolean,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit,
    onPauseTask: (String) -> Unit,
    onResumeTask: (String) -> Unit,
    onCancelTask: (String) -> Unit,
    onRetryTask: (String, Boolean) -> Unit,
    onChangePriority: (String, Int) -> Unit,
    onOpenFile: (DownloadTaskEntity) -> Unit,
    onShareFile: (DownloadTaskEntity) -> Unit,
    onOpenFolder: () -> Unit,
    onDeleteTask: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedFilter by remember { mutableStateOf(QueueFilter.ALL) }

    val filteredTasks = remember(queueTasks, selectedFilter) {
        when (selectedFilter) {
            QueueFilter.ALL -> queueTasks
            QueueFilter.DOWNLOADING -> queueTasks.filter {
                it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.CONNECTING
            }
            QueueFilter.QUEUED -> queueTasks.filter { it.status == DownloadStatus.QUEUED }
            QueueFilter.PAUSED -> queueTasks.filter { it.status == DownloadStatus.PAUSED }
            QueueFilter.FAILED -> queueTasks.filter {
                it.status == DownloadStatus.FAILED || it.status == DownloadStatus.CANCELLED
            }
        }
    }

    val activeRunningCount = remember(queueTasks) {
        queueTasks.count { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.CONNECTING }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("queue_screen_list"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Manajemen Antrean & Transfer",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "Slot aktif: $activeRunningCount / $maxConcurrent unduhan bersamaan",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        FilledTonalButton(
                            onClick = onPauseAll,
                            enabled = queueTasks.any { it.status.canPause() },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("pause_all_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Pause,
                                contentDescription = "Jeda Semua",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Jeda Semua")
                        }

                        FilledTonalButton(
                            onClick = onResumeAll,
                            enabled = queueTasks.any { it.status == DownloadStatus.PAUSED },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("resume_all_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Lanjutkan Semua",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Lanjutkan Semua")
                        }
                    }
                }
            }
        }

        item {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                QueueFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = selectedFilter == filter,
                        onClick = { selectedFilter = filter },
                        label = { Text(filter.label) }
                    )
                }
            }
        }

        if (filteredTasks.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
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
                            imageVector = Icons.Default.Queue,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(42.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Tidak Ada Unduhan pada Kategori Ini",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Tambahkan URL di halaman Beranda untuk memulai transfer atau mengatur prioritas antrean.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(
                items = filteredTasks,
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
}
