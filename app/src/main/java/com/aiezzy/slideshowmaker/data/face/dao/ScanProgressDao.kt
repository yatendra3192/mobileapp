package com.aiezzy.slideshowmaker.data.face.dao

import androidx.room.*
import com.aiezzy.slideshowmaker.data.face.entities.ScanProgressEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ScanProgressDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(progress: ScanProgressEntity)

    @Update
    suspend fun update(progress: ScanProgressEntity)

    @Query("SELECT * FROM scan_progress WHERE id = 1")
    suspend fun getProgress(): ScanProgressEntity?

    @Query("SELECT * FROM scan_progress WHERE id = 1")
    fun getProgressFlow(): Flow<ScanProgressEntity?>

    @Query("""
        UPDATE scan_progress SET
            scannedPhotos = :scannedPhotos,
            lastPhotoUri = :lastPhotoUri
        WHERE id = 1
    """)
    suspend fun updateProgress(scannedPhotos: Int, lastPhotoUri: String?)

    @Query("""
        UPDATE scan_progress SET
            scannedPhotos = scannedPhotos + 1,
            facesDetected = facesDetected + :newFaces,
            lastPhotoUri = :lastPhotoUri
        WHERE id = 1
    """)
    suspend fun incrementProgress(lastPhotoUri: String, newFaces: Int)

    @Query("""
        UPDATE scan_progress SET
            isComplete = 1,
            completedAt = :completedAt
        WHERE id = 1
    """)
    suspend fun markComplete(completedAt: Long = System.currentTimeMillis())

    @Query("""
        UPDATE scan_progress SET
            errorMessage = :errorMessage
        WHERE id = 1
    """)
    suspend fun setError(errorMessage: String)

    @Query("UPDATE scan_progress SET clustersCreated = :count WHERE id = 1")
    suspend fun updateClustersCreated(count: Int)

    @Query("DELETE FROM scan_progress")
    suspend fun clear()

    @Transaction
    suspend fun startNewScan(totalPhotos: Int) {
        clear()
        insert(
            ScanProgressEntity(
                id = 1,
                totalPhotos = totalPhotos,
                scannedPhotos = 0,
                facesDetected = 0,
                clustersCreated = 0,
                lastPhotoUri = null,
                isComplete = false,
                startedAt = System.currentTimeMillis()
            )
        )
    }
}
