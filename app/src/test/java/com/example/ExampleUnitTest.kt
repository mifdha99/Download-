package com.example

import com.example.data.DownloadStatus
import com.example.data.DownloadTaskEntity
import com.example.engine.EngineTransferOutcome
import com.example.engine.HttpDownloadEngine
import com.example.engine.UrlValidationResult
import com.example.engine.UrlValidator
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun `1 - validasi URL mengizinkan HTTP dan HTTPS publik serta menolak skema berbahaya atau IP internal`() {
        val httpsValid = UrlValidator.validateUrl("https://releases.ubuntu.com/24.04/ubuntu.iso")
        assertTrue(httpsValid is UrlValidationResult.Valid)

        val httpValid = UrlValidator.validateUrl("http://speedtest.tele2.net/10MB.zip")
        assertTrue(httpValid is UrlValidationResult.Valid)

        // Tolak skema selain HTTP/HTTPS
        assertTrue(UrlValidator.validateUrl("ftp://files.example.com/data.zip") is UrlValidationResult.Invalid)
        assertTrue(UrlValidator.validateUrl("file:///sdcard/Download/rahasia.txt") is UrlValidationResult.Invalid)
        assertTrue(UrlValidator.validateUrl("javascript:alert(1)") is UrlValidationResult.Invalid)
        assertTrue(UrlValidator.validateUrl("") is UrlValidationResult.Invalid)

        // Tolak alamat internal / lokal (SSRF protection)
        assertTrue(UrlValidator.validateUrl("http://localhost:8080/admin") is UrlValidationResult.Invalid)
        assertTrue(UrlValidator.validateUrl("http://127.0.0.1/secret") is UrlValidationResult.Invalid)
        assertTrue(UrlValidator.validateUrl("http://192.168.1.1/router.bin") is UrlValidationResult.Invalid)
        assertTrue(UrlValidator.validateUrl("http://10.0.0.5/internal.pdf") is UrlValidationResult.Invalid)
        assertTrue(UrlValidator.validateUrl("http://169.254.169.254/latest/meta-data") is UrlValidationResult.Invalid)
    }

    @Test
    fun `2 - interpretasi status HTTP membedakan sukses, parsial, error sementara, dan error permanen`() {
        val ok200 = UrlValidator.interpretHttpStatus(200)
        assertTrue(ok200.isSuccess)
        assertFalse(ok200.isPartialContent)

        val partial206 = UrlValidator.interpretHttpStatus(206)
        assertTrue(partial206.isSuccess)
        assertTrue(partial206.isPartialContent)

        val err401 = UrlValidator.interpretHttpStatus(401)
        assertFalse(err401.isSuccess)
        assertTrue(err401.isPermanentError)
        assertFalse(err401.isTransientError)

        val err403 = UrlValidator.interpretHttpStatus(403)
        assertTrue(err403.isPermanentError)

        val err404 = UrlValidator.interpretHttpStatus(404)
        assertTrue(err404.isPermanentError)
        assertFalse(err404.isTransientError)

        val err416 = UrlValidator.interpretHttpStatus(416)
        assertTrue(err416.isPermanentError)

        val err503 = UrlValidator.interpretHttpStatus(503)
        assertTrue(err503.isTransientError)
        assertFalse(err503.isPermanentError)
    }

    @Test
    fun `3 - penghitungan progres dan ETA akurat serta tidak menampilkan persentase palsu jika ukuran tidak diketahui`() {
        val taskUnknownSize = DownloadTaskEntity(
            id = "t1",
            url = "https://example.com/stream.bin",
            fileName = "stream.bin",
            totalBytes = -1L,
            downloadedBytes = 512_000L,
            tempFilePath = "/tmp/t1.part",
            status = DownloadStatus.DOWNLOADING,
            speedBytesPerSec = 128_000L
        )
        assertNull(taskUnknownSize.progressPercent)
        assertNull(taskUnknownSize.progressFraction)
        assertNull(taskUnknownSize.calculatedEtaSeconds)

        val taskKnownSize = DownloadTaskEntity(
            id = "t2",
            url = "https://example.com/archive.zip",
            fileName = "archive.zip",
            totalBytes = 1000L,
            downloadedBytes = 250L,
            tempFilePath = "/tmp/t2.part",
            status = DownloadStatus.DOWNLOADING,
            speedBytesPerSec = 250L
        )
        assertEquals(25, taskKnownSize.progressPercent)
        assertEquals(0.25f, taskKnownSize.progressFraction!!, 0.001f)
        assertEquals(3L, taskKnownSize.calculatedEtaSeconds)
    }

    @Test
    fun `4 - penanganan nama file duplikat dan ekstraksi Content-Disposition aman dari path traversal`() {
        val headerName = UrlValidator.parseContentDispositionFileName(
            """attachment; filename="laporan_keuangan_2026.pdf""""
        )
        assertEquals("laporan_keuangan_2026.pdf", headerName)

        val traversalSanitized = UrlValidator.sanitizeFileName("../../etc/passwd")
        assertEquals("passwd", traversalSanitized)

        val existing = setOf("dokumen.pdf", "dokumen (1).pdf")
        val unique = UrlValidator.resolveUniqueFileName("dokumen.pdf", existing)
        assertEquals("dokumen (2).pdf", unique)
    }

    @Test
    fun `5 - resume HTTP 206 Partial Content meneruskan dari byte terakhir secara presisi`() = runBlocking {
        val server = MockWebServer()
        val fullContent = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789" // 36 bytes
        val remainingContent = fullContent.substring(10) // 26 bytes dari offset 10

        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Accept-Ranges", "bytes")
                .setHeader("Content-Range", "bytes 10-35/36")
                .setHeader("ETag", "\"etag-v1\"")
                .setBody(remainingContent)
        )
        server.start()

        val tempDir = Files.createTempDirectory("uda_test_resume").toFile()
        val tempFile = File(tempDir, "resume_task.part")
        // Tulis 10 byte pertama seolah-olah sudah diunduh sebelum dijeda
        tempFile.writeText(fullContent.substring(0, 10))

        try {
            val engine = HttpDownloadEngine(allowLoopbackForTesting = true)
            val url = server.url("/file_lengkap.txt").toString()

            val outcome = engine.executeTransfer(
                url = url,
                tempFile = tempFile,
                initialFileName = "file_lengkap.txt",
                initialMimeType = "text/plain",
                initialTotalBytes = 36L,
                initialSupportsRange = true,
                storedETag = "\"etag-v1\"",
                storedLastModified = null,
                enableMultiSegment = false,
                desiredSegments = 1,
                isPausedCheck = { false },
                isCancelledCheck = { false },
                spaceCheck = {},
                onProgress = {}
            )

            val recordedReq = server.takeRequest()
            assertEquals("bytes=10-", recordedReq.getHeader("Range"))
            assertEquals("\"etag-v1\"", recordedReq.getHeader("If-Range"))

            assertTrue(outcome is EngineTransferOutcome.Success)
            val success = outcome as EngineTransferOutcome.Success
            assertEquals(36L, success.downloadedBytes)
            assertEquals(fullContent, tempFile.readText())
        } finally {
            tempDir.deleteRecursively()
            server.shutdown()
        }
    }

    @Test
    fun `6 - server yang tidak mendukung resume dan mengembalikan HTTP 200 tidak merusak file parsial lama`() = runBlocking {
        val server = MockWebServer()
        val fullPayload = "DATA_BARU_UTUH_DARI_SERVER_200_OK"

        // Server mengabaikan header Range dan mengembalikan HTTP 200 beserta seluruh isi file dari awal
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/plain")
                .setBody(fullPayload)
        )
        server.start()

        val tempDir = Files.createTempDirectory("uda_test_norange").toFile()
        val tempFile = File(tempDir, "norange_task.part")
        // Isi file parsial lama yang seharusnya TIDAK digabung jika server membalas HTTP 200
        tempFile.writeText("PARSIAL_LAMA_")

        try {
            val engine = HttpDownloadEngine(allowLoopbackForTesting = true)
            val url = server.url("/tanpa_range.txt").toString()

            val outcome = engine.executeTransfer(
                url = url,
                tempFile = tempFile,
                initialFileName = "tanpa_range.txt",
                initialMimeType = "text/plain",
                initialTotalBytes = -1L,
                initialSupportsRange = false,
                storedETag = null,
                storedLastModified = null,
                enableMultiSegment = false,
                desiredSegments = 1,
                isPausedCheck = { false },
                isCancelledCheck = { false },
                spaceCheck = {},
                onProgress = {}
            )

            assertTrue(outcome is EngineTransferOutcome.Success)
            val success = outcome as EngineTransferOutcome.Success
            // Pastikan isi file sama persis dengan fullPayload (bukan "PARSIAL_LAMA_" + fullPayload)
            assertEquals(fullPayload, tempFile.readText())
            assertNotNull(success.resumeWarning)
        } finally {
            tempDir.deleteRecursively()
            server.shutdown()
        }
    }
}
