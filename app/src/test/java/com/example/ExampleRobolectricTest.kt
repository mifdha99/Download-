package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.DownloadStatus
import com.example.data.DownloadTaskEntity
import com.example.data.SettingsRepository
import com.example.engine.DownloadQueueManager
import com.example.engine.HttpDownloadEngine
import com.example.engine.MediaStorePublisher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var publisher: MediaStorePublisher
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        settingsRepository = SettingsRepository(context, overrideSettings = com.example.data.AppSettings())
        publisher = MediaStorePublisher(context)
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
    }

    @Test
    fun `1 - nama aplikasi sesuai dengan Uda Downloader`() {
        val appName = context.getString(R.string.app_name)
        assertEquals("Uda Downloader", appName)
    }

    @Test
    fun `2 - pengelolaan antrean, retry otomatis saat server 503, dan penyelesaian transfer`() = runBlocking {
        // Respons pertama saat worker mengunduh gagal sementara (HTTP 503),
        // respons kedua pada percobaan retry berhasil (HTTP 200)
        server.enqueue(MockResponse().setResponseCode(503).setBody("Server Sibuk Sementara"))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/plain")
                .setBody("Isi file berhasil diunduh setelah retry otomatis!")
        )

        val dao = db.downloadDao()
        val tempFile = publisher.createTempPartFile("task_retry_503")
        val queuedTask = DownloadTaskEntity(
            id = "task_retry_503",
            url = server.url("/dokumen_uji.txt").toString(),
            fileName = "dokumen_uji.txt",
            mimeType = "text/plain",
            tempFilePath = tempFile.absolutePath,
            status = DownloadStatus.QUEUED
        )
        dao.insertTask(queuedTask)

        val engine = HttpDownloadEngine(allowLoopbackForTesting = true)
        val manager = DownloadQueueManager(
            context = context,
            downloadDao = dao,
            settingsRepository = settingsRepository,
            mediaStorePublisher = publisher,
            httpEngine = engine,
            externalScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            retryBaseDelayMs = 100L,
            enforceNetworkCheck = false,
            startForegroundServiceCallback = {}
        )

        manager.processQueue()

        // Tunggu hingga retry selesai dan tugas berstatus COMPLETED
        var finalTask: DownloadTaskEntity? = null
        repeat(40) {
            val current = dao.getTaskById("task_retry_503")
            if (current?.status == DownloadStatus.COMPLETED) {
                finalTask = current
                return@repeat
            }
            delay(100L)
        }

        val debugState = dao.getTaskById("task_retry_503")
        assertNotNull(
            "Tugas seharusnya COMPLETED setelah retry, tetapi status saat ini=${debugState?.status}, error=${debugState?.errorMessage}",
            finalTask
        )
        assertEquals(DownloadStatus.COMPLETED, finalTask?.status)
        assertEquals(1, finalTask?.retryCount)
        assertNotNull(finalTask?.destinationUri)
        assertNotNull(finalTask?.checksumSha256)
    }

    @Test
    fun `3 - peralihan status jeda, ubah prioritas, dan batalkan bekerja konsisten di Room`() = runBlocking {
        val dao = db.downloadDao()
        val tempFile = publisher.createTempPartFile("task_prio_1")
        tempFile.writeText("12345")

        val task = DownloadTaskEntity(
            id = "task_prio_1",
            url = "https://example.com/besar.zip",
            fileName = "besar.zip",
            totalBytes = 1000L,
            downloadedBytes = 5L,
            tempFilePath = tempFile.absolutePath,
            status = DownloadStatus.DOWNLOADING
        )
        dao.insertTask(task)

        val engine = HttpDownloadEngine(allowLoopbackForTesting = true)
        val manager = DownloadQueueManager(
            context = context,
            downloadDao = dao,
            settingsRepository = settingsRepository,
            mediaStorePublisher = publisher,
            httpEngine = engine,
            externalScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            startForegroundServiceCallback = {}
        )

        // Jeda tugas
        manager.pauseTask("task_prio_1")
        val paused = dao.getTaskById("task_prio_1")
        assertEquals(DownloadStatus.PAUSED, paused?.status)
        assertEquals(5L, paused?.downloadedBytes)

        // Ubah prioritas
        manager.changePriority("task_prio_1", 3)
        val updatedPrio = dao.getTaskById("task_prio_1")
        assertEquals(3, updatedPrio?.priority)

        // Batalkan tugas -> status menjadi CANCELLED dan file .part dihapus
        manager.cancelTask("task_prio_1")
        val cancelled = dao.getTaskById("task_prio_1")
        assertEquals(DownloadStatus.CANCELLED, cancelled?.status)
        assertTrue(!tempFile.exists())
    }

    @Test
    fun `4 - pemulihan setelah proses dihentikan merekonsiliasi byte file sementara dan menjadwalkan ulang`() = runBlocking {
        val dao = db.downloadDao()
        val tempFile = publisher.createTempPartFile("interrupted_task")
        val initialPart = "BAGIAN_AWAL_"
        val secondPart = "LANJUTAN_SETELAH_PROSES_PULIH"
        val fullExpected = initialPart + secondPart
        tempFile.writeText(initialPart)

        // Simulasikan tugas yang tertinggal pada status DOWNLOADING saat proses aplikasi mati mendadak
        val stuckTask = DownloadTaskEntity(
            id = "interrupted_task",
            url = server.url("/pulih.txt").toString(),
            fileName = "pulih.txt",
            mimeType = "text/plain",
            totalBytes = fullExpected.length.toLong(),
            downloadedBytes = 0L, // Belum sempat ter-update di DB saat proses mati, tapi ada 12 byte di .part
            tempFilePath = tempFile.absolutePath,
            status = DownloadStatus.DOWNLOADING,
            supportsRange = true
        )
        dao.insertTask(stuckTask)

        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Accept-Ranges", "bytes")
                .setHeader(
                    "Content-Range",
                    "bytes ${initialPart.length}-${fullExpected.length - 1}/${fullExpected.length}"
                )
                .setBody(secondPart)
        )

        val engine = HttpDownloadEngine(allowLoopbackForTesting = true)
        val manager = DownloadQueueManager(
            context = context,
            downloadDao = dao,
            settingsRepository = settingsRepository,
            mediaStorePublisher = publisher,
            httpEngine = engine,
            externalScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            retryBaseDelayMs = 100L,
            enforceNetworkCheck = false,
            startForegroundServiceCallback = {}
        )

        // Panggil fungsi pemulihan saat aplikasi dibuka kembali
        manager.reconcileAndRestoreQueue()

        var recoveredTask: DownloadTaskEntity? = null
        repeat(35) {
            val current = dao.getTaskById("interrupted_task")
            if (current?.status == DownloadStatus.COMPLETED) {
                recoveredTask = current
                return@repeat
            }
            delay(100L)
        }

        assertNotNull("Tugas yang terputus harus berhasil dipulihkan hingga COMPLETED", recoveredTask)
        assertEquals(DownloadStatus.COMPLETED, recoveredTask?.status)
        assertEquals(fullExpected.length.toLong(), recoveredTask?.downloadedBytes)
    }
}
