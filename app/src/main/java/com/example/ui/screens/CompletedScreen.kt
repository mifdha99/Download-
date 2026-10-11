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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.DownloadTaskEntity
import com.example.data.FileCategory
import com.example.ui.components.DownloadItemCard
import com.example.util.FormatUtils

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CompletedScreen(
    completedTasks: List<DownloadTaskEntity>,
    downloadSubfolder: String,
    confirmBeforeDelete: Boolean,
    onOpenFile: (DownloadTaskEntity) -> Unit,
    onShareFile: (DownloadTaskEntity) -> Unit,
    onOpenFolder: () -> Unit,
    onDeleteTask: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<FileCategory?>(null) }

    val filteredTasks = remember(completedTasks, searchQuery, selectedCategory) {
        completedTasks.filter { task ->
            val matchesQuery = searchQuery.isBlank() ||
                task.fileName.contains(searchQuery.trim(), ignoreCase = true)
            val matchesCategory = selectedCategory == null || task.category == selectedCategory
            matchesQuery && matchesCategory
        }
    }

    val totalSavedBytes = remember(completedTasks) {
        completedTasks.sumOf { it.downloadedBytes.coerceAtLeast(0L) }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("completed_screen_list"),
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
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Unduhan Selesai (${completedTasks.size})",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "Total diunduh: ${FormatUtils.formatBytes(totalSavedBytes)} • Disimpan di Download/$downloadSubfolder",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    FilledTonalButton(
                        onClick = onOpenFolder,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("open_downloads_folder_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = "Buka Folder Download",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Buka Folder Download Sistem")
                    }
                }
            }
        }

        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Cari nama file selesai...") },
                leadingIcon = {
                    Icon(imageVector = Icons.Default.Search, contentDescription = null)
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(imageVector = Icons.Default.Clear, contentDescription = "Bersihkan pencarian")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("completed_search_input")
            )
        }

        item {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                FilterChip(
                    selected = selectedCategory == null,
                    onClick = { selectedCategory = null },
                    label = { Text("Semua") }
                )
                FileCategory.entries.forEach { category ->
                    FilterChip(
                        selected = selectedCategory == category,
                        onClick = {
                            selectedCategory = if (selectedCategory == category) null else category
                        },
                        label = { Text(category.labelId) }
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
                            imageVector = Icons.Default.CheckCircleOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(42.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Belum Ada File Selesai",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Semua file yang telah selesai diunduh dan diverifikasi akan tampil di sini dan tetap tersimpan di folder Download perangkat Anda.",
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
                    onPause = {},
                    onResume = {},
                    onCancel = {},
                    onRetry = { _, _ -> },
                    onChangePriority = { _, _ -> },
                    onOpenFile = onOpenFile,
                    onShareFile = onShareFile,
                    onOpenFolder = onOpenFolder,
                    onDelete = onDeleteTask
                )
            }
        }
    }
}
