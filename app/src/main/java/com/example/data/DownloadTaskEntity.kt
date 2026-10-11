package com.example.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlin.math.roundToInt

@Entity(
    tableName = "download_tasks",
    indices = [
        Index(value = ["status"]),
        Index(value = ["priority", "createdAt"])
    ]
)
data class DownloadTaskEntity(
    @PrimaryKey
    val id: String,
    val url: String,
    val finalUrl: String = url,
    val fileName: String,
    val mimeType: String = "application/octet-stream",
    val totalBytes: Long = -1L,
    val downloadedBytes: Long = 0L,
    val tempFilePath: String,
    val destinationUri: String? = null,
    val customSubfolder: String = "UdaDownloader",
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val priority: Int = 0,
    val speedBytesPerSec: Long = 0L,
    val etaSeconds: Long = -1L,
    val supportsRange: Boolean = false,
    val eTag: String? = null,
    val lastModified: String? = null,
    val resumeWarning: String? = null,
    val isHtmlWarning: Boolean = false,
    val segmentCount: Int = 1,
    val retryCount: Int = 0,
    val errorMessage: String? = null,
    val checksumSha256: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    /**
     * Menghitung persentase progres nyata (0..100) hanya jika totalBytes diketahui (> 0).
     * Mengembalikan null jika ukuran total tidak diketahui dari server.
     */
    val progressPercent: Int?
        get() {
            if (totalBytes <= 0L) return null
            if (status == DownloadStatus.COMPLETED) return 100
            val ratio = (downloadedBytes.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0)
            return (ratio * 100.0).roundToInt()
        }

    /**
     * Nilai float 0f..1f untuk LinearProgressIndicator. Null jika ukuran file tidak diketahui.
     */
    val progressFraction: Float?
        get() {
            if (totalBytes <= 0L) return null
            if (status == DownloadStatus.COMPLETED) return 1.0f
            return (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        }

    /**
     * Estimasi waktu tersisa (detik) hanya jika sedang mengunduh, ukuran total diketahui,
     * dan kecepatan aktual > 0 byte/detik.
     */
    val calculatedEtaSeconds: Long?
        get() {
            if (status != DownloadStatus.DOWNLOADING) return null
            if (totalBytes <= 0L || downloadedBytes >= totalBytes || speedBytesPerSec <= 0L) return null
            val remainingBytes = totalBytes - downloadedBytes
            return (remainingBytes / speedBytesPerSec).coerceAtLeast(1L)
        }

    val category: FileCategory
        get() = FileCategory.fromFileNameAndMime(fileName, mimeType)
}
