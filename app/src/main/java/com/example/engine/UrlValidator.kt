package com.example.engine

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

sealed class UrlValidationResult {
    data class Valid(val normalizedUrl: String, val host: String) : UrlValidationResult()
    data class Invalid(val messageId: String) : UrlValidationResult()
}

data class ContentRangeInfo(
    val startByte: Long,
    val endByte: Long,
    val totalBytes: Long
)

data class HttpStatusInterpretation(
    val code: Int,
    val isSuccess: Boolean,
    val isPartialContent: Boolean,
    val isTransientError: Boolean,
    val isPermanentError: Boolean,
    val messageIndo: String
)

object UrlValidator {

    private val URL_EXTRACT_REGEX = Regex(
        """https?://[^\s"'<>]+""",
        RegexOption.IGNORE_CASE
    )

    private val FILENAME_STAR_REGEX = Regex(
        """filename\*\s*=\s*(?:UTF-8|ISO-8859-1)?'[^']*'([^;\s]+)""",
        RegexOption.IGNORE_CASE
    )

    private val FILENAME_QUOTED_REGEX = Regex(
        """filename\s*=\s*"([^"]+)"""",
        RegexOption.IGNORE_CASE
    )

    private val FILENAME_UNQUOTED_REGEX = Regex(
        """filename\s*=\s*([^;\s]+)""",
        RegexOption.IGNORE_CASE
    )

    private val CONTENT_RANGE_REGEX = Regex(
        """bytes\s+(\d+)-(\d+)/(\d+|\*)""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Memvalidasi URL dengan aturan keamanan:
     * 1. Hanya mengizinkan skema http dan https.
     * 2. Menolak host kosong atau format URI rusak.
     * 3. Menolak alamat internal/loopback/link-local (SSRF protection) kecuali diizinkan saat pengujian unit lokal.
     */
    fun validateUrl(
        rawUrl: String,
        allowLoopbackForTesting: Boolean = false
    ): UrlValidationResult {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) {
            return UrlValidationResult.Invalid("URL tidak boleh kosong.")
        }
        if (trimmed.contains(" ") || trimmed.contains("\n") || trimmed.contains("\r")) {
            return UrlValidationResult.Invalid("URL tidak boleh mengandung spasi atau baris baru.")
        }

        val uri = try {
            URI(trimmed)
        } catch (e: Exception) {
            return UrlValidationResult.Invalid("Format URL tidak valid. Pastikan penulisan karakter benar.")
        }

        val scheme = uri.scheme?.lowercase(Locale.ROOT)
            ?: return UrlValidationResult.Invalid("URL harus menyertakan skema http:// atau https://.")

        if (scheme != "http" && scheme != "https") {
            return UrlValidationResult.Invalid("Skema '$scheme' tidak didukung. Hanya HTTP dan HTTPS yang diizinkan.")
        }

        if (uri.userInfo != null) {
            return UrlValidationResult.Invalid("URL dengan kredensial tertanam (user:pass@host) ditolak demi keamanan.")
        }

        val host = uri.host?.lowercase(Locale.ROOT)?.trim()
        if (host.isNullOrEmpty()) {
            return UrlValidationResult.Invalid("Nama domain atau host pada URL tidak ditemukan.")
        }

        if (!allowLoopbackForTesting && isRestrictedInternalHost(host)) {
            return UrlValidationResult.Invalid(
                "Akses ke alamat jaringan lokal atau internal ($host) diblokir demi keamanan."
            )
        }

        return UrlValidationResult.Valid(normalizedUrl = uri.toASCIIString(), host = host)
    }

    /**
     * Memeriksa apakah host merupakan alamat lokal/private/internal (mencegah SSRF).
     */
    fun isRestrictedInternalHost(host: String): Boolean {
        val cleanHost = host.removeSurrounding("[", "]").lowercase(Locale.ROOT)
        if (cleanHost == "localhost" ||
            cleanHost == "::1" ||
            cleanHost == "0:0:0:0:0:0:0:1" ||
            cleanHost == "0.0.0.0" ||
            cleanHost.endsWith(".local") ||
            cleanHost.endsWith(".internal") ||
            cleanHost.endsWith(".localhost")
        ) {
            return true
        }

        // Cek IPv4 literal
        val ipv4Parts = cleanHost.split(".")
        if (ipv4Parts.size == 4 && ipv4Parts.all { it.toIntOrNull() in 0..255 }) {
            val b0 = ipv4Parts[0].toInt()
            val b1 = ipv4Parts[1].toInt()
            // 127.0.0.0/8 (Loopback)
            if (b0 == 127) return true
            // 10.0.0.0/8 (Private Class A)
            if (b0 == 10) return true
            // 172.16.0.0/12 (Private Class B: 172.16 - 172.31)
            if (b0 == 172 && b1 in 16..31) return true
            // 192.168.0.0/16 (Private Class C)
            if (b0 == 192 && b1 == 168) return true
            // 169.254.0.0/16 (Link-local / cloud metadata 169.254.169.254)
            if (b0 == 169 && b1 == 254) return true
            // 0.0.0.0/8
            if (b0 == 0) return true
        }

        // Cek IPv6 unique local / link-local
        if (cleanHost.startsWith("fe80:") || cleanHost.startsWith("fc00:") || cleanHost.startsWith("fd00:")) {
            return true
        }

        return false
    }

    /**
     * Mengekstrak URL HTTP/HTTPS pertama yang valid dari teks clipboard atau intent bagikan.
     */
    fun extractFirstValidUrl(
        rawText: String?,
        allowLoopbackForTesting: Boolean = false
    ): String? {
        if (rawText.isNullOrBlank()) return null
        val match = URL_EXTRACT_REGEX.find(rawText) ?: return null
        val candidate = match.value.trimEnd('.', ',', ';', ')', ']', '"', '\'')
        return when (val res = validateUrl(candidate, allowLoopbackForTesting)) {
            is UrlValidationResult.Valid -> res.normalizedUrl
            is UrlValidationResult.Invalid -> null
        }
    }

    /**
     * Menentukan nama file yang aman dengan urutan prioritas:
     * 1. Header Content-Disposition (filename* atau filename)
     * 2. Segmen path terakhir dari URL
     * 3. Nama cadangan aman ("unduhan_file") dengan ekstensi dari MIME type bila ada
     */
    fun resolveFileName(
        url: String,
        contentDisposition: String?,
        mimeType: String?
    ): String {
        val fromHeader = parseContentDispositionFileName(contentDisposition)
        val rawCandidate = when {
            !fromHeader.isNullOrBlank() -> fromHeader
            else -> extractFileNameFromUrl(url)
        }

        val sanitized = sanitizeFileName(rawCandidate ?: "unduhan_file")
        val extFromMime = extensionFromMimeType(mimeType)

        return if (!sanitized.contains('.') && !extFromMime.isNullOrEmpty()) {
            "$sanitized.$extFromMime"
        } else {
            sanitized
        }
    }

    fun parseContentDispositionFileName(header: String?): String? {
        if (header.isNullOrBlank()) return null

        FILENAME_STAR_REGEX.find(header)?.groupValues?.getOrNull(1)?.let { encoded ->
            val decoded = runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrDefault(encoded)
            val cleaned = sanitizeFileName(decoded)
            if (cleaned.isNotBlank()) return cleaned
        }

        FILENAME_QUOTED_REGEX.find(header)?.groupValues?.getOrNull(1)?.let { quoted ->
            val decoded = runCatching { URLDecoder.decode(quoted, "UTF-8") }.getOrDefault(quoted)
            val cleaned = sanitizeFileName(decoded)
            if (cleaned.isNotBlank()) return cleaned
        }

        FILENAME_UNQUOTED_REGEX.find(header)?.groupValues?.getOrNull(1)?.let { unquoted ->
            val cleaned = sanitizeFileName(unquoted.trim('"', '\''))
            if (cleaned.isNotBlank()) return cleaned
        }

        return null
    }

    fun extractFileNameFromUrl(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val path = uri.path ?: return null
        val lastSegment = path.substringAfterLast('/').trim()
        if (lastSegment.isEmpty()) return null
        val decoded = runCatching { URLDecoder.decode(lastSegment, "UTF-8") }.getOrDefault(lastSegment)
        return sanitizeFileName(decoded).takeIf { it.isNotBlank() && it != "unduhan_file" }
    }

    /**
     * Membersihkan nama file dari karakter berbahaya, path traversal (.. / \), dan karakter kontrol.
     */
    fun sanitizeFileName(rawName: String): String {
        var cleaned = rawName
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace("..", "_")
            .replace(Regex("""[<>:"/\\|?*\u0000-\u001F]"""), "_")
            .trim()
            .trim('.')

        if (cleaned.isEmpty()) {
            cleaned = "unduhan_file"
        }

        if (cleaned.length > 180) {
            val dotIndex = cleaned.lastIndexOf('.')
            cleaned = if (dotIndex in 1 until cleaned.length - 1 && cleaned.length - dotIndex <= 12) {
                val ext = cleaned.substring(dotIndex)
                cleaned.substring(0, 180 - ext.length) + ext
            } else {
                cleaned.take(180)
            }
        }
        return cleaned
    }

    /**
     * Menghasilkan nama file unik yang aman ketika terdapat duplikat:
     * contoh: "dokumen.pdf" -> "dokumen (1).pdf" -> "dokumen (2).pdf"
     */
    fun resolveUniqueFileName(desiredName: String, existingNames: Set<String>): String {
        val safeBase = sanitizeFileName(desiredName)
        val lowerExisting = existingNames.map { it.lowercase(Locale.ROOT) }.toSet()
        if (safeBase.lowercase(Locale.ROOT) !in lowerExisting) {
            return safeBase
        }

        val dotIndex = safeBase.lastIndexOf('.')
        val (stem, ext) = if (dotIndex > 0 && dotIndex < safeBase.length - 1) {
            safeBase.substring(0, dotIndex) to safeBase.substring(dotIndex)
        } else {
            safeBase to ""
        }

        var counter = 1
        while (counter < 10_000) {
            val candidate = "$stem ($counter)$ext"
            if (candidate.lowercase(Locale.ROOT) !in lowerExisting) {
                return candidate
            }
            counter++
        }
        return "${stem}_${System.currentTimeMillis()}$ext"
    }

    /**
     * Mem-parsing header Content-Range dari respons HTTP 206 Partial Content.
     */
    fun parseContentRange(header: String?): ContentRangeInfo? {
        if (header.isNullOrBlank()) return null
        val match = CONTENT_RANGE_REGEX.find(header.trim()) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val totalStr = match.groupValues[3]
        val total = if (totalStr == "*") -1L else (totalStr.toLongOrNull() ?: return null)
        if (start < 0L || end < start) return null
        return ContentRangeInfo(startByte = start, endByte = end, totalBytes = total)
    }

    /**
     * Menginterpretasikan kode status HTTP menjadi informasi yang jelas dalam Bahasa Indonesia,
     * serta menentukan apakah kegagalan bersifat sementara (bisa di-retry) atau permanen.
     */
    fun interpretHttpStatus(code: Int): HttpStatusInterpretation {
        return when (code) {
            200 -> HttpStatusInterpretation(
                code = 200,
                isSuccess = true,
                isPartialContent = false,
                isTransientError = false,
                isPermanentError = false,
                messageIndo = "OK (Transfer penuh)"
            )
            206 -> HttpStatusInterpretation(
                code = 206,
                isSuccess = true,
                isPartialContent = true,
                isTransientError = false,
                isPermanentError = false,
                messageIndo = "Konten Parsial (Mendukung lanjutkan unduhan / Range)"
            )
            400 -> HttpStatusInterpretation(
                code = 400,
                isSuccess = false,
                isPartialContent = false,
                isTransientError = false,
                isPermanentError = true,
                messageIndo = "Permintaan tidak valid (HTTP 400). Periksa kembali URL."
            )
            401 -> HttpStatusInterpretation(
                code = 401,
                isSuccess = false,
                isPartialContent = false,
                isTransientError = false,
                isPermanentError = true,
                messageIndo = "Akses ditolak: Server memerlukan autentikasi (HTTP 401)."
            )
            403 -> HttpStatusInterpretation(
                code = 403,
                isSuccess = false,
                isPartialContent = false,
                isTransientError = false,
                isPermanentError = true,
                messageIndo = "Akses dilarang oleh server atau tautan telah kedaluwarsa (HTTP 403)."
            )
            404 -> HttpStatusInterpretation(
                code = 404,
                isSuccess = false,
                isPartialContent = false,
                isTransientError = false,
                isPermanentError = true,
                messageIndo = "File tidak ditemukan di server (HTTP 404)."
            )
            408 -> HttpStatusInterpretation(
                code = 408,
                isSuccess = false,
                isPartialContent = false,
                isTransientError = true,
                isPermanentError = false,
                messageIndo = "Waktu tunggu permintaan ke server habis (HTTP 408)."
            )
            412 -> HttpStatusInterpretation(
                code = 412,
                isSuccess = false,
                isPartialContent = false,
                isTransientError = false,
                isPermanentError = true,
                messageIndo = "File sumber di server telah berubah sejak terakhir diunduh (HTTP 412)."
            )
            416 -> HttpStatusInterpretation(
                code = 416,
                isSuccess = false,
                isPartialContent = false,
                isTransientError = false,
                isPermanentError = true,
                messageIndo = "Rentang byte tidak valid atau file di server telah berubah ukuran (HTTP 416)."
            )
            429 -> HttpStatusInterpretation(
                code = 429,
                isSuccess = false,
                isPartialContent = false,
                isTransientError = true,
                isPermanentError = false,
                messageIndo = "Terlalu banyak permintaan ke server (HTTP 429). Akan dicoba kembali."
            )
            in 500..599 -> HttpStatusInterpretation(
                code = code,
                isSuccess = false,
                isPartialContent = false,
                isTransientError = true,
                isPermanentError = false,
                messageIndo = "Gangguan sementara pada server (HTTP $code)."
            )
            else -> HttpStatusInterpretation(
                code = code,
                isSuccess = code in 200..299,
                isPartialContent = false,
                isTransientError = false,
                isPermanentError = code !in 200..299,
                messageIndo = "Respons HTTP $code dari server."
            )
        }
    }

    fun isHtmlContentType(mimeType: String?, fileName: String): Boolean {
        val cleanMime = mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT) ?: ""
        val lowerName = fileName.lowercase(Locale.ROOT)
        val hasDirectBinaryExt = listOf(
            ".apk", ".zip", ".rar", ".7z", ".tar", ".gz", ".pdf",
            ".mp4", ".mkv", ".mp3", ".flac", ".wav", ".jpg", ".jpeg",
            ".png", ".webp", ".iso", ".bin", ".exe", ".dmg", ".doc",
            ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".csv", ".json"
        ).any { lowerName.endsWith(it) }

        return cleanMime == "text/html" || cleanMime == "application/xhtml+xml" ||
            (lowerName.endsWith(".html") && !hasDirectBinaryExt)
    }

    fun extensionFromMimeType(mimeType: String?): String? {
        val clean = mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT) ?: return null
        return when (clean) {
            "application/vnd.android.package-archive" -> "apk"
            "application/pdf" -> "pdf"
            "application/zip", "application/x-zip-compressed" -> "zip"
            "application/x-rar-compressed", "application/vnd.rar" -> "rar"
            "application/x-7z-compressed" -> "7z"
            "application/gzip" -> "gz"
            "application/json" -> "json"
            "text/plain" -> "txt"
            "text/csv" -> "csv"
            "text/html" -> "html"
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "video/mp4" -> "mp4"
            "video/x-matroska" -> "mkv"
            "video/webm" -> "webm"
            "audio/mpeg" -> "mp3"
            "audio/flac" -> "flac"
            "audio/wav" -> "wav"
            "audio/ogg" -> "ogg"
            else -> null
        }
    }

    fun guessMimeTypeFromFileName(fileName: String, serverMimeType: String?): String {
        val cleanServer = serverMimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        if (!cleanServer.isNullOrBlank() && cleanServer != "application/octet-stream" && cleanServer != "*/*") {
            return cleanServer
        }
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (ext) {
            "apk" -> "application/vnd.android.package-archive"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "rar" -> "application/vnd.rar"
            "7z" -> "application/x-7z-compressed"
            "gz", "tgz" -> "application/gzip"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "json" -> "application/json"
            "txt", "log", "md" -> "text/plain"
            "csv" -> "text/csv"
            "html", "htm" -> "text/html"
            else -> cleanServer ?: "application/octet-stream"
        }
    }
}
