package com.example.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FormatUtils {

    fun formatBytes(bytes: Long): String {
        if (bytes < 0L) return "Ukuran belum diketahui"
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format(Locale.US, "%.2f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.2f GB", gb)
    }

    fun formatSpeed(bytesPerSec: Long): String {
        if (bytesPerSec <= 0L) return "0 B/dtk"
        if (bytesPerSec < 1024L) return "$bytesPerSec B/dtk"
        val kb = bytesPerSec / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.1f KB/dtk", kb)
        val mb = kb / 1024.0
        return String.format(Locale.US, "%.2f MB/dtk", mb)
    }

    fun formatEta(seconds: Long?): String {
        if (seconds == null || seconds <= 0L) return "-"
        if (seconds < 60L) return "${seconds} dtk"
        val minutes = seconds / 60L
        val remSec = seconds % 60L
        if (minutes < 60L) {
            return if (remSec > 0L) "${minutes} mnt ${remSec} dtk" else "${minutes} mnt"
        }
        val hours = minutes / 60L
        val remMin = minutes % 60L
        return if (remMin > 0L) "${hours} jam ${remMin} mnt" else "${hours} jam"
    }

    fun formatDateTime(timestampMillis: Long): String {
        val sdf = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.forLanguageTag("id-ID"))
        return sdf.format(Date(timestampMillis))
    }
}
