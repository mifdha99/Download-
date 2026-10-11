package com.example.engine

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

class InsufficientStorageException(message: String) : IOException(message)

data class PublishedFileResult(
    val finalFileName: String,
    val destinationUri: String,
    val mimeType: String
)

class MediaStorePublisher(private val context: Context) {

    /**
     * Direktori internal khusus untuk menyimpan file sementara (.part) selama proses transfer.
     */
    fun getTempDirectory(): File {
        val dir = File(context.filesDir, "partial_downloads")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun createTempPartFile(taskId: String): File {
        return File(getTempDirectory(), "$taskId.part")
    }

    /**
     * Memeriksa sisa ruang penyimpanan pada direktori internal maupun eksternal.
     */
    fun getAvailableBytes(): Long {
        return runCatching {
            val stat = StatFs(context.filesDir.absolutePath)
            if (stat.totalBytes <= 0L) Long.MAX_VALUE else stat.availableBytes
        }.getOrDefault(Long.MAX_VALUE)
    }

    fun ensureEnoughSpace(requiredRemainingBytes: Long) {
        if (requiredRemainingBytes <= 0L) return
        val available = getAvailableBytes()
        if (available == Long.MAX_VALUE) return
        // Sisakan minimal buffer keselamatan 20 MB
        val safetyMargin = 20L * 1024L * 1024L
        if (available < requiredRemainingBytes + safetyMargin) {
            throw InsufficientStorageException(
                "Ruang penyimpanan perangkat tidak mencukupi. Dibutuhkan minimal ${(requiredRemainingBytes / (1024 * 1024))} MB lagi."
            )
        }
    }

    /**
     * Memindahkan / mempublikasikan file sementara (.part) yang telah selesai dan terverifikasi
     * ke folder Download publik secara aman tanpa memuat seluruh file ke RAM.
     */
    fun publishCompletedDownload(
        tempFile: File,
        desiredFileName: String,
        mimeType: String,
        subfolder: String
    ): PublishedFileResult {
        if (!tempFile.exists()) {
            throw IOException("File sementara (.part) tidak ditemukan untuk dipublikasikan.")
        }

        val resolvedMime = UrlValidator.guessMimeTypeFromFileName(desiredFileName, mimeType)
        val cleanSubfolder = subfolder.trim().trim('/').ifEmpty { "UdaDownloader" }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publishViaMediaStoreQ(tempFile, desiredFileName, resolvedMime, cleanSubfolder)
        } else {
            publishViaLegacyDownloadsDir(tempFile, desiredFileName, resolvedMime, cleanSubfolder)
        }
    }

    private fun publishViaMediaStoreQ(
        tempFile: File,
        desiredFileName: String,
        mimeType: String,
        subfolder: String
    ): PublishedFileResult {
        val resolver = context.contentResolver
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$subfolder"

        // Cek apakah ada nama file yang sama di MediaStore Downloads pada subfolder tersebut
        val existingNames = queryExistingMediaStoreNames(relativePath)
        val uniqueFileName = UrlValidator.resolveUniqueFileName(desiredFileName, existingNames)

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, uniqueFileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val itemUri = runCatching { resolver.insert(collection, values) }.getOrNull()
            ?: return publishViaLegacyDownloadsDir(tempFile, desiredFileName, mimeType, subfolder)

        try {
            val outStream = runCatching { resolver.openOutputStream(itemUri) }.getOrNull()
            if (outStream == null) {
                runCatching { resolver.delete(itemUri, null, null) }
                return publishViaLegacyDownloadsDir(tempFile, desiredFileName, mimeType, subfolder)
            }
            outStream.use { stream ->
                FileInputStream(tempFile).use { inStream ->
                    val buffer = ByteArray(64 * 1024)
                    var read: Int
                    while (inStream.read(buffer).also { read = it } != -1) {
                        stream.write(buffer, 0, read)
                    }
                    stream.flush()
                }
            }

            val completeValues = ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
            }
            resolver.update(itemUri, completeValues, null, null)

            // Hapus file sementara (.part) setelah berhasil dipublikasikan
            tempFile.delete()

            return PublishedFileResult(
                finalFileName = uniqueFileName,
                destinationUri = itemUri.toString(),
                mimeType = mimeType
            )
        } catch (e: Exception) {
            runCatching { resolver.delete(itemUri, null, null) }
            return publishViaLegacyDownloadsDir(tempFile, desiredFileName, mimeType, subfolder)
        }
    }

    private fun queryExistingMediaStoreNames(relativePath: String): Set<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptySet()
        val names = mutableSetOf<String>()
        runCatching {
            val projection = arrayOf(MediaStore.Downloads.DISPLAY_NAME)
            val selection = "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
            val selectionArgs = arrayOf("$relativePath%")
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    cursor.getString(nameCol)?.let { names.add(it) }
                }
            }
        }
        return names
    }

    private fun publishViaLegacyDownloadsDir(
        tempFile: File,
        desiredFileName: String,
        mimeType: String,
        subfolder: String
    ): PublishedFileResult {
        val baseDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val targetDir = File(baseDownloads, subfolder)
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            // Fallback ke app external files dir jika perangkat lama menolak akses
            val fallbackDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), subfolder)
            fallbackDir.mkdirs()
            return copyTempToDirectory(tempFile, fallbackDir, desiredFileName, mimeType)
        }
        return copyTempToDirectory(tempFile, targetDir, desiredFileName, mimeType)
    }

    private fun copyTempToDirectory(
        tempFile: File,
        targetDir: File,
        desiredFileName: String,
        mimeType: String
    ): PublishedFileResult {
        val existingNames = targetDir.list()?.toSet() ?: emptySet()
        val uniqueFileName = UrlValidator.resolveUniqueFileName(desiredFileName, existingNames)
        val destFile = File(targetDir, uniqueFileName)

        FileInputStream(tempFile).use { inStream ->
            FileOutputStream(destFile).use { outStream ->
                val buffer = ByteArray(64 * 1024)
                var read: Int
                while (inStream.read(buffer).also { read = it } != -1) {
                    outStream.write(buffer, 0, read)
                }
                outStream.flush()
            }
        }
        tempFile.delete()

        val uri = runCatching {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                destFile
            )
        }.getOrElse { Uri.fromFile(destFile) }

        return PublishedFileResult(
            finalFileName = uniqueFileName,
            destinationUri = uri.toString(),
            mimeType = mimeType
        )
    }

    /**
     * Menghapus file hasil unduhan hanya jika pengguna secara eksplisit memilih menghapus file fisik.
     */
    fun deletePublishedFile(destinationUriString: String?): Boolean {
        if (destinationUriString.isNullOrBlank()) return false
        return runCatching {
            val uri = Uri.parse(destinationUriString)
            if (uri.scheme == "content") {
                context.contentResolver.delete(uri, null, null) > 0
            } else {
                val path = uri.path ?: return false
                File(path).delete()
            }
        }.getOrDefault(false)
    }

    /**
     * Membuka file yang sudah selesai dengan aplikasi yang sesuai di perangkat.
     */
    fun openCompletedFile(destinationUriString: String?, fileName: String, mimeType: String): Result<Unit> {
        if (destinationUriString.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Lokasi file tidak ditemukan."))
        }
        return runCatching {
            val uri = Uri.parse(destinationUriString)
            val resolvedMime = UrlValidator.guessMimeTypeFromFileName(fileName, mimeType)
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, resolvedMime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(viewIntent, "Buka $fileName").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        }
    }

    /**
     * Membuka aplikasi pengelola file / folder Download sistem.
     */
    fun openDownloadsFolder(): Result<Unit> {
        return runCatching {
            val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    /**
     * Membagikan file yang sudah selesai melalui Android Sharesheet.
     */
    fun shareCompletedFile(destinationUriString: String?, fileName: String, mimeType: String): Result<Unit> {
        if (destinationUriString.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Lokasi file tidak ditemukan."))
        }
        return runCatching {
            val uri = Uri.parse(destinationUriString)
            val resolvedMime = UrlValidator.guessMimeTypeFromFileName(fileName, mimeType)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = resolvedMime
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TITLE, fileName)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(shareIntent, "Bagikan $fileName").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        }
    }

    /**
     * Membersihkan file sementara (.part) yang yatim (tidak lagi dimiliki oleh tugas di database).
     * Mengembalikan jumlah file dan total byte yang dibersihkan.
     */
    fun cleanupOrphanedTempFiles(activeTaskIds: Set<String>): Pair<Int, Long> {
        val dir = getTempDirectory()
        val files = dir.listFiles() ?: return 0 to 0L
        var deletedCount = 0
        var freedBytes = 0L
        for (file in files) {
            val taskId = file.name.removeSuffix(".part")
            if (taskId !in activeTaskIds) {
                val len = file.length()
                if (file.delete()) {
                    deletedCount++
                    freedBytes += len
                }
            }
        }
        return deletedCount to freedBytes
    }
}
