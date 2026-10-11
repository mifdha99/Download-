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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.NetworkWifi
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.AppSettings
import com.example.data.ThemeMode
import com.example.util.FormatUtils
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onMaxConcurrentChange: (Int) -> Unit,
    onSubfolderChange: (String) -> Unit,
    onWifiOnlyChange: (Boolean) -> Unit,
    onAutoRetryChange: (Boolean) -> Unit,
    onMaxRetriesChange: (Int) -> Unit,
    onMultiSegmentChange: (Boolean) -> Unit,
    onSegmentsCountChange: (Int) -> Unit,
    onNotificationsChange: (Boolean) -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
    onConfirmBeforeDeleteChange: (Boolean) -> Unit,
    onClipboardDetectionChange: (Boolean) -> Unit,
    onCleanupTempFiles: ((Int, Long) -> Unit) -> Unit,
    modifier: Modifier = Modifier
) {
    var showSubfolderDialog by remember { mutableStateOf(false) }
    var cleanupResultMessage by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("settings_screen_list"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Pengaturan Antrean & Optimasi Kecepatan
        item {
            SettingsSectionCard(
                title = "Antrean & Optimasi Transfer",
                icon = Icons.Default.Speed
            ) {
                Text(
                    text = "Batas Unduhan Bersamaan: ${settings.maxConcurrentDownloads} file",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = "Membatasi jumlah transfer aktif sekaligus agar perangkat dan jaringan tetap stabil (Default: 3).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = settings.maxConcurrentDownloads.toFloat(),
                    onValueChange = { onMaxConcurrentChange(it.roundToInt()) },
                    valueRange = 1f..5f,
                    steps = 3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("slider_max_concurrent")
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                SettingSwitchRow(
                    title = "Unduhan Multi-Segmen (Paralel)",
                    subtitle = "Membagi file besar (> 2 MB) menjadi beberapa segmen rentang byte berbeda hanya jika server mengonfirmasi dukungan HTTP Range 206. Tidak semua server menjadi lebih cepat; nonaktifkan jika server membatasi koneksi.",
                    checked = settings.multiSegmentEnabled,
                    onCheckedChange = onMultiSegmentChange,
                    testTag = "switch_multi_segment"
                )

                if (settings.multiSegmentEnabled) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Jumlah Segmen per File: ${settings.segmentsPerDownload} segmen",
                        style = MaterialTheme.typography.labelLarge
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(2, 3, 4).forEach { seg ->
                            FilterChip(
                                selected = settings.segmentsPerDownload == seg,
                                onClick = { onSegmentsCountChange(seg) },
                                label = { Text("$seg Segmen") }
                            )
                        }
                    }
                }
            }
        }

        // 2. Pengaturan Jaringan & Retry Otomatis
        item {
            SettingsSectionCard(
                title = "Jaringan & Percobaan Ulang (Retry)",
                icon = Icons.Default.NetworkWifi
            ) {
                SettingSwitchRow(
                    title = "Hanya Unduh melalui Wi-Fi",
                    subtitle = "Tahan antrean unduhan saat menggunakan data seluler dan lanjutkan otomatis saat terhubung ke Wi-Fi.",
                    checked = settings.wifiOnly,
                    onCheckedChange = onWifiOnlyChange,
                    testTag = "switch_wifi_only"
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                SettingSwitchRow(
                    title = "Retry Otomatis dengan Jeda Bertahap",
                    subtitle = "Mencoba ulang secara otomatis untuk gangguan sementara (timeout / 5xx). Berhenti otomatis untuk kesalahan permanen (404 / 403).",
                    checked = settings.autoRetryEnabled,
                    onCheckedChange = onAutoRetryChange,
                    testTag = "switch_auto_retry"
                )

                if (settings.autoRetryEnabled) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Batas Maksimum Retry: ${settings.maxRetries} kali",
                        style = MaterialTheme.typography.labelLarge
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (1..5).forEach { count ->
                            FilterChip(
                                selected = settings.maxRetries == count,
                                onClick = { onMaxRetriesChange(count) },
                                label = { Text("${count}x") }
                            )
                        }
                    }
                }
            }
        }

        // 3. Penyimpanan File & Pembersihan
        item {
            SettingsSectionCard(
                title = "Penyimpanan & Manajemen File",
                icon = Icons.Default.Folder
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Lokasi Folder Unduhan Publik",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = "Download/${settings.downloadSubfolder} (Kompatibel dengan Scoped Storage & MediaStore)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(
                        onClick = { showSubfolderDialog = true },
                        modifier = Modifier.testTag("change_subfolder_button")
                    ) {
                        Text("Ubah")
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                SettingSwitchRow(
                    title = "Konfirmasi Sebelum Menghapus File Selesai",
                    subtitle = "Tampilkan dialog konfirmasi agar file selesai di penyimpanan tidak terhapus secara tidak sengaja.",
                    checked = settings.confirmBeforeDeleteFile,
                    onCheckedChange = onConfirmBeforeDeleteChange,
                    testTag = "switch_confirm_delete"
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                FilledTonalButton(
                    onClick = {
                        onCleanupTempFiles { count, bytes ->
                            cleanupResultMessage = if (count > 0) {
                                "Berhasil membersihkan $count file sementara (.part) dan membebaskan ${FormatUtils.formatBytes(bytes)}."
                            } else {
                                "Tidak ada file sementara (.part) yatim yang perlu dibersihkan. Semua ruang bersih."
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("cleanup_temp_files_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.CleaningServices,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Bersihkan File Sementara (.part) Tidak Terpakai")
                }

                if (!cleanupResultMessage.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = cleanupResultMessage!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // 4. Notifikasi, Clipboard & Tema Antarmuka
        item {
            SettingsSectionCard(
                title = "Notifikasi, Deteksi Tautan & Tampilan",
                icon = Icons.Default.Palette
            ) {
                SettingSwitchRow(
                    title = "Notifikasi Progres Unduhan",
                    subtitle = "Tampilkan progres real-time dan tombol Jeda/Batalkan pada bilah notifikasi saat unduhan berjalan.",
                    checked = settings.notificationsEnabled,
                    onCheckedChange = onNotificationsChange,
                    testTag = "switch_notifications"
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                SettingSwitchRow(
                    title = "Saran Tautan Clipboard di Beranda",
                    subtitle = "Periksa clipboard secara lokal hanya saat aplikasi dibuka di foreground tanpa pernah mengunduh otomatis atau mengirim isi clipboard ke jaringan.",
                    checked = settings.clipboardDetectionEnabled,
                    onCheckedChange = onClipboardDetectionChange,
                    testTag = "switch_clipboard_detection"
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Text(
                    text = "Tema Aplikasi",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.themeMode == mode,
                            onClick = { onThemeModeChange(mode) },
                            label = { Text(mode.label) }
                        )
                    }
                }
            }
        }
    }

    if (showSubfolderDialog) {
        SubfolderEditDialog(
            currentSubfolder = settings.downloadSubfolder,
            onDismiss = { showSubfolderDialog = false },
            onConfirm = { newFolder ->
                showSubfolderDialog = false
                onSubfolderChange(newFolder)
            }
        )
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag)
        )
    }
}

@Composable
private fun SubfolderEditDialog(
    currentSubfolder: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var folderInput by remember { mutableStateOf(currentSubfolder) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ubah Subfolder Unduhan") },
        text = {
            Column {
                Text(
                    text = "File yang selesai akan disimpan di dalam folder publik Download/<NamaSubfolder>:",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = folderInput,
                    onValueChange = { folderInput = it },
                    label = { Text("Nama Subfolder") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(folderInput) }) {
                Text("Simpan")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Batal")
            }
        }
    )
}
