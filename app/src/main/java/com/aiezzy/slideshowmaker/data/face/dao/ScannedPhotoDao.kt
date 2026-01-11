package com.aiezzy.slideshowmaker.data.face.dao

import androidx.room.*
import com.aiezzy.slideshowmaker.data.face.entities.ScannedPhotoEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ScannedPhotoDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(photo: ScannedPhotoEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(photos: List<ScannedPhotoEntity>)

    @Update
    suspend fun update(photo: ScannedPhotoEntity)

    @Delete
    suspend fun delete(photo: ScannedPhotoEntity)

    @Query("SELECT * FROM scanned_photos WHERE photoUri = :uri")
    suspend fun getByUri(uri: String): ScannedPhotoEntity?

    @Query("SELECT * FROM scanned_photos WHERE scanStatus = :status")
    suspend fun getByStatus(status: String): List<ScannedPhotoEntity>

    @Query("SELECT * FROM scanned_photos WHERE scanStatus = 'PENDING' LIMIT :limit")
    suspend fun getPendingPhotos(limit: Int = 100): List<ScannedPhotoEntity>

    @Query("SELECT COUNT(*) FROM scanned_photos")
    suspend fun getTotalCount(): Int

    @Query("SELECT COUNT(*) FROM scanned_photos WHERE scanStatus = 'SCANNED'")
    suspend fun getScannedCount(): Int

    @Query("SELECT COUNT(*) FROM scanned_photos WHERE scanStatus = 'PENDING'")
    suspend fun getPendingCount(): Int

    @Query("SELECT * FROM scanned_photos ORDER BY dateTaken DESC")
    fun getAllPhotosFlow(): Flow<List<ScannedPhotoEntity>>

    @Query("DELETE FROM scanned_photos")
    suspend fun deleteAll()

    @Query("SELECT EXISTS(SELECT 1 FROM scanned_photos WHERE photoUri = :uri)")
    suspend fun exists(uri: String): Boolean

    @Query("UPDATE scanned_photos SET scanStatus = :status, lastScanned = :timestamp WHERE photoUri = :uri")
    suspend fun updateStatus(uri: String, status: String, timestamp: Long)

    /**
     * Reset a photo to PENDING status for retry (clears lastScanned).
     */
    @Query("UPDATE scanned_photos SET scanStatus = 'PENDING', lastScanned = NULL WHERE photoUri = :uri")
    suspend fun resetToPending(uri: String)

    /**
     * Get count of failed photos.
     */
    @Query("SELECT COUNT(*) FROM scanned_photos WHERE scanStatus = 'FAILED'")
    suspend fun getFailedCount(): Int

    /**
     * Get all failed photos for retry or review.
     */
    @Query("SELECT * FROM scanned_photos WHERE scanStatus = 'FAILED' ORDER BY lastScanned DESC")
    suspend fun getFailedPhotos(): List<ScannedPhotoEntity>
}
