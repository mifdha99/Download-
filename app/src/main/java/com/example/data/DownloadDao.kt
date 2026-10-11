package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {

    @Query("SELECT * FROM download_tasks ORDER BY createdAt DESC")
    fun observeAllTasks(): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_tasks WHERE status IN ('QUEUED', 'CONNECTING', 'DOWNLOADING', 'PAUSED', 'FAILED', 'CANCELLED') ORDER BY priority DESC, createdAt ASC")
    fun observeActiveQueueTasks(): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_tasks WHERE status = 'COMPLETED' ORDER BY updatedAt DESC")
    fun observeCompletedTasks(): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_tasks WHERE id = :id LIMIT 1")
    suspend fun getTaskById(id: String): DownloadTaskEntity?

    @Query("SELECT * FROM download_tasks WHERE id = :id LIMIT 1")
    fun observeTaskById(id: String): Flow<DownloadTaskEntity?>

    @Query("SELECT * FROM download_tasks")
    suspend fun getAllTasksSync(): List<DownloadTaskEntity>

    @Query("SELECT * FROM download_tasks WHERE status = 'QUEUED' ORDER BY priority DESC, createdAt ASC")
    suspend fun getQueuedTasksOrdered(): List<DownloadTaskEntity>

    @Query("SELECT * FROM download_tasks WHERE status IN ('DOWNLOADING', 'CONNECTING')")
    suspend fun getRunningTasksSync(): List<DownloadTaskEntity>

    @Query("SELECT fileName FROM download_tasks")
    suspend fun getAllFileNames(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: DownloadTaskEntity)

    @Update
    suspend fun updateTask(task: DownloadTaskEntity)

    @Query(
        """
        UPDATE download_tasks 
        SET downloadedBytes = :downloadedBytes,
            totalBytes = :totalBytes,
            speedBytesPerSec = :speedBytesPerSec,
            etaSeconds = :etaSeconds,
            status = :status,
            updatedAt = :updatedAt
        WHERE id = :id
        """
    )
    suspend fun updateProgress(
        id: String,
        downloadedBytes: Long,
        totalBytes: Long,
        speedBytesPerSec: Long,
        etaSeconds: Long,
        status: DownloadStatus,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query(
        """
        UPDATE download_tasks
        SET status = :status,
            speedBytesPerSec = 0,
            etaSeconds = -1,
            errorMessage = :errorMessage,
            updatedAt = :updatedAt
        WHERE id = :id
        """
    )
    suspend fun updateStatus(
        id: String,
        status: DownloadStatus,
        errorMessage: String? = null,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query(
        """
        UPDATE download_tasks
        SET priority = :priority,
            updatedAt = :updatedAt
        WHERE id = :id
        """
    )
    suspend fun updatePriority(
        id: String,
        priority: Int,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query(
        """
        UPDATE download_tasks
        SET status = 'COMPLETED',
            downloadedBytes = :downloadedBytes,
            totalBytes = :totalBytes,
            destinationUri = :destinationUri,
            checksumSha256 = :checksumSha256,
            speedBytesPerSec = 0,
            etaSeconds = 0,
            errorMessage = NULL,
            updatedAt = :updatedAt
        WHERE id = :id
        """
    )
    suspend fun markCompleted(
        id: String,
        downloadedBytes: Long,
        totalBytes: Long,
        destinationUri: String,
        checksumSha256: String?,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("DELETE FROM download_tasks WHERE id = :id")
    suspend fun deleteTaskById(id: String)

    @Query("DELETE FROM download_tasks WHERE status = 'COMPLETED'")
    suspend fun clearCompletedMetadata()
}
