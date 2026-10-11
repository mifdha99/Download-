package com.example.data

enum class DownloadStatus(val labelId: String) {
    QUEUED("Dalam Antrean"),
    CONNECTING("Menghubungkan"),
    DOWNLOADING("Mengunduh"),
    PAUSED("Dijeda"),
    COMPLETED("Selesai"),
    FAILED("Gagal"),
    CANCELLED("Dibatalkan");

    fun isActiveOrQueued(): Boolean =
        this == QUEUED || this == CONNECTING || this == DOWNLOADING

    fun canPause(): Boolean =
        this == DOWNLOADING || this == CONNECTING || this == QUEUED

    fun canResume(): Boolean =
        this == PAUSED

    fun canRetry(): Boolean =
        this == FAILED || this == CANCELLED

    fun canCancel(): Boolean =
        this == QUEUED || this == CONNECTING || this == DOWNLOADING || this == PAUSED
}

enum class FileCategory(val labelId: String) {
    ARCHIVE("Arsip"),
    APK("Paket Aplikasi (APK)"),
    DOCUMENT("Dokumen"),
    IMAGE("Gambar"),
    VIDEO("Video"),
    AUDIO("Audio"),
    CODE("Kode / Teks"),
    OTHER("File Lainnya");

    companion object {
        fun fromFileNameAndMime(fileName: String, mimeType: String): FileCategory {
            val lowerName = fileName.lowercase()
            val lowerMime = mimeType.lowercase()

            return when {
                lowerName.endsWith(".apk") || lowerName.endsWith(".aab") ||
                    lowerMime == "application/vnd.android.package-archive" -> APK
                lowerName.endsWith(".zip") || lowerName.endsWith(".rar") ||
                    lowerName.endsWith(".7z") || lowerName.endsWith(".tar") ||
                    lowerName.endsWith(".gz") || lowerName.endsWith(".bz2") ||
                    lowerName.endsWith(".xz") || lowerMime.contains("zip") ||
                    lowerMime.contains("compressed") || lowerMime.contains("tar") -> ARCHIVE
                lowerName.endsWith(".pdf") || lowerName.endsWith(".doc") ||
                    lowerName.endsWith(".docx") || lowerName.endsWith(".xls") ||
                    lowerName.endsWith(".xlsx") || lowerName.endsWith(".ppt") ||
                    lowerName.endsWith(".pptx") || lowerName.endsWith(".epub") ||
                    lowerMime.contains("pdf") || lowerMime.contains("msword") ||
                    lowerMime.contains("officedocument") -> DOCUMENT
                lowerMime.startsWith("image/") || lowerName.endsWith(".jpg") ||
                    lowerName.endsWith(".jpeg") || lowerName.endsWith(".png") ||
                    lowerName.endsWith(".webp") || lowerName.endsWith(".gif") ||
                    lowerName.endsWith(".svg") -> IMAGE
                lowerMime.startsWith("video/") || lowerName.endsWith(".mp4") ||
                    lowerName.endsWith(".mkv") || lowerName.endsWith(".webm") ||
                    lowerName.endsWith(".avi") || lowerName.endsWith(".mov") -> VIDEO
                lowerMime.startsWith("audio/") || lowerName.endsWith(".mp3") ||
                    lowerName.endsWith(".flac") || lowerName.endsWith(".wav") ||
                    lowerName.endsWith(".ogg") || lowerName.endsWith(".m4a") -> AUDIO
                lowerMime.startsWith("text/") || lowerName.endsWith(".json") ||
                    lowerName.endsWith(".xml") || lowerName.endsWith(".csv") ||
                    lowerName.endsWith(".txt") || lowerName.endsWith(".md") ||
                    lowerName.endsWith(".html") -> CODE
                else -> OTHER
            }
        }
    }
}
