package com.aiezzy.slideshowmaker.data.face.dao

import androidx.room.*
import com.aiezzy.slideshowmaker.data.face.entities.PersonEntity
import com.aiezzy.slideshowmaker.data.face.entities.PersonWithFace
import kotlinx.coroutines.flow.Flow

@Dao
interface PersonDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(person: PersonEntity)

    @Update
    suspend fun update(person: PersonEntity)

    @Delete
    suspend fun delete(person: PersonEntity)

    @Query("DELETE FROM persons WHERE personId = :personId")
    suspend fun deleteById(personId: String)

    @Query("SELECT * FROM persons WHERE personId = :personId AND deletedAt IS NULL")
    suspend fun getById(personId: String): PersonEntity?

    @Query("SELECT * FROM persons WHERE personId = :personId")
    suspend fun getByIdIncludingDeleted(personId: String): PersonEntity?

    @Query("SELECT * FROM persons WHERE isHidden = 0 AND deletedAt IS NULL ORDER BY photoCount DESC")
    suspend fun getAllVisible(): List<PersonEntity>

    @Query("SELECT * FROM persons WHERE isHidden = 0 AND deletedAt IS NULL ORDER BY photoCount DESC")
    fun getAllVisibleFlow(): Flow<List<PersonEntity>>

    @Query("SELECT * FROM persons WHERE deletedAt IS NULL ORDER BY photoCount DESC")
    suspend fun getAll(): List<PersonEntity>

    @Query("SELECT COUNT(*) FROM persons WHERE isHidden = 0 AND deletedAt IS NULL")
    suspend fun getVisibleCount(): Int

    @Query("UPDATE persons SET name = :name, updatedAt = :updatedAt WHERE personId = :personId")
    suspend fun updateName(personId: String, name: String?, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE persons SET isHidden = :isHidden, updatedAt = :updatedAt WHERE personId = :personId")
    suspend fun updateHidden(personId: String, isHidden: Boolean, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE persons SET photoCount = :count, updatedAt = :updatedAt WHERE personId = :personId")
    suspend fun updatePhotoCount(personId: String, count: Int, updatedAt: Long = System.currentTimeMillis())

    /**
     * Increment photo count by 1.
     * Used when adding a new face to a cluster for this person.
     */
    @Query("UPDATE persons SET photoCount = photoCount + 1, updatedAt = :updatedAt WHERE personId = :personId")
    suspend fun incrementPhotoCount(personId: String, updatedAt: Long = System.currentTimeMillis())

    /**
     * Delete person by ID. Alias for deleteById.
     */
    @Query("DELETE FROM persons WHERE personId = :personId")
    suspend fun delete(personId: String)

    // Soft delete - set deletedAt timestamp
    @Query("UPDATE persons SET deletedAt = :deletedAt WHERE personId = :personId")
    suspend fun softDelete(personId: String, deletedAt: Long = System.currentTimeMillis())

    // Restore soft-deleted person
    @Query("UPDATE persons SET deletedAt = NULL WHERE personId = :personId")
    suspend fun restore(personId: String)

    // Get all soft-deleted persons for cleanup
    @Query("SELECT * FROM persons WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    suspend fun getSoftDeleted(): List<PersonEntity>

    // Permanently delete soft-deleted records older than threshold
    @Query("DELETE FROM persons WHERE deletedAt IS NOT NULL AND deletedAt < :threshold")
    suspend fun cleanupSoftDeleted(threshold: Long)

    // Get persons with their best face as thumbnail
    // ACCURACY FIX: Prioritizes by QUALITY first, then size
    // 1) Quality tier (ANCHOR/CLUSTERING only - excludes blurry DISPLAY_ONLY faces)
    // 2) Quality score (sharpness, pose, lighting)
    // 3) Match score (how well face matches cluster)
    // This ensures thumbnails are always high-quality, sharp faces
    @Query("""
        SELECT p.personId, p.name, p.photoCount, p.isHidden,
               df.faceId, df.photoUri, df.boundingBoxLeft, df.boundingBoxTop,
               df.boundingBoxRight, df.boundingBoxBottom,
               p.birthday, p.displayNumber
        FROM persons p
        INNER JOIN face_clusters fc ON fc.personId = p.personId AND fc.deletedAt IS NULL
        INNER JOIN detected_faces df ON df.clusterId = fc.clusterId AND df.deletedAt IS NULL
        WHERE p.isHidden = 0 AND p.deletedAt IS NULL
        AND df.faceId = (
            SELECT df2.faceId
            FROM detected_faces df2
            INNER JOIN face_clusters fc2 ON df2.clusterId = fc2.clusterId
            WHERE fc2.personId = p.personId AND df2.deletedAt IS NULL AND fc2.deletedAt IS NULL
            AND df2.qualityTier IN ('ANCHOR', 'CLUSTERING')
            ORDER BY
                df2.qualityScore DESC,
                COALESCE(df2.matchScore, 0) DESC,
                (df2.boundingBoxRight - df2.boundingBoxLeft) * (df2.boundingBoxBottom - df2.boundingBoxTop) DESC
            LIMIT 1
        )
        ORDER BY p.photoCount DESC
    """)
    fun getPersonsWithFaceFlow(): Flow<List<PersonWithFace>>

    @Query("""
        SELECT p.personId, p.name, p.photoCount, p.isHidden,
               df.faceId, df.photoUri, df.boundingBoxLeft, df.boundingBoxTop,
               df.boundingBoxRight, df.boundingBoxBottom,
               p.birthday, p.displayNumber
        FROM persons p
        INNER JOIN face_clusters fc ON fc.personId = p.personId AND fc.deletedAt IS NULL
        INNER JOIN detected_faces df ON df.clusterId = fc.clusterId AND df.deletedAt IS NULL
        WHERE p.isHidden = 0 AND p.deletedAt IS NULL
        AND df.faceId = (
            SELECT df2.faceId
            FROM detected_faces df2
            INNER JOIN face_clusters fc2 ON df2.clusterId = fc2.clusterId
            WHERE fc2.personId = p.personId AND df2.deletedAt IS NULL AND fc2.deletedAt IS NULL
            AND df2.qualityTier IN ('ANCHOR', 'CLUSTERING')
            ORDER BY
                df2.qualityScore DESC,
                COALESCE(df2.matchScore, 0) DESC,
                (df2.boundingBoxRight - df2.boundingBoxLeft) * (df2.boundingBoxBottom - df2.boundingBoxTop) DESC
            LIMIT 1
        )
        ORDER BY p.photoCount DESC
    """)
    suspend fun getPersonsWithFace(): List<PersonWithFace>

    @Query("""
        SELECT p.personId, p.name, p.photoCount, p.isHidden,
               df.faceId, df.photoUri, df.boundingBoxLeft, df.boundingBoxTop,
               df.boundingBoxRight, df.boundingBoxBottom,
               p.birthday, p.displayNumber
        FROM persons p
        INNER JOIN face_clusters fc ON fc.personId = p.personId AND fc.deletedAt IS NULL
        INNER JOIN detected_faces df ON df.clusterId = fc.clusterId AND df.deletedAt IS NULL
        WHERE p.personId = :personId AND p.deletedAt IS NULL
        ORDER BY COALESCE(df.matchScore, 0) DESC, df.confidence DESC
        LIMIT 1
    """)
    suspend fun getPersonWithFace(personId: String): PersonWithFace?

    @Query("UPDATE persons SET representativeFaceId = :faceId WHERE personId = :personId")
    suspend fun updateRepresentativeFace(personId: String, faceId: String)

    @Query("DELETE FROM persons")
    suspend fun deleteAll()

    // Recalculate photo counts, excluding soft-deleted records
    @Query("""
        UPDATE persons SET photoCount = (
            SELECT COUNT(DISTINCT df.photoUri)
            FROM detected_faces df
            INNER JOIN face_clusters fc ON df.clusterId = fc.clusterId
            WHERE fc.personId = persons.personId
            AND df.deletedAt IS NULL AND fc.deletedAt IS NULL
        )
        WHERE deletedAt IS NULL
    """)
    suspend fun recalculateAllPhotoCounts()

    // ============ Chunked/Paginated Queries for Large Datasets ============

    /**
     * Get persons with their best matching face using pagination.
     * Use this for large datasets to avoid loading all persons into memory.
     * ACCURACY FIX: Prioritizes by quality score, excludes DISPLAY_ONLY faces.
     */
    @Query("""
        SELECT p.personId, p.name, p.photoCount, p.isHidden,
               df.faceId, df.photoUri, df.boundingBoxLeft, df.boundingBoxTop,
               df.boundingBoxRight, df.boundingBoxBottom,
               p.birthday, p.displayNumber
        FROM persons p
        INNER JOIN face_clusters fc ON fc.personId = p.personId AND fc.deletedAt IS NULL
        INNER JOIN detected_faces df ON df.clusterId = fc.clusterId AND df.deletedAt IS NULL
        WHERE p.isHidden = 0 AND p.deletedAt IS NULL
        AND df.faceId = (
            SELECT df2.faceId
            FROM detected_faces df2
            INNER JOIN face_clusters fc2 ON df2.clusterId = fc2.clusterId
            WHERE fc2.personId = p.personId AND df2.deletedAt IS NULL AND fc2.deletedAt IS NULL
            AND df2.qualityTier IN ('ANCHOR', 'CLUSTERING')
            ORDER BY
                df2.qualityScore DESC,
                COALESCE(df2.matchScore, 0) DESC,
                (df2.boundingBoxRight - df2.boundingBoxLeft) * (df2.boundingBoxBottom - df2.boundingBoxTop) DESC
            LIMIT 1
        )
        ORDER BY p.photoCount DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getPersonsWithFaceChunked(limit: Int, offset: Int): List<PersonWithFace>

    /**
     * Get total count of visible persons (for pagination).
     */
    @Query("SELECT COUNT(*) FROM persons WHERE isHidden = 0 AND deletedAt IS NULL")
    suspend fun getVisiblePersonCount(): Int

    /**
     * Get all visible persons ordered by photo count, with pagination.
     */
    @Query("""
        SELECT * FROM persons
        WHERE isHidden = 0 AND deletedAt IS NULL
        ORDER BY photoCount DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getAllVisibleChunked(limit: Int, offset: Int): List<PersonEntity>

    /**
     * Get persons by IDs (batch fetch).
     * More efficient than fetching one by one.
     */
    @Query("SELECT * FROM persons WHERE personId IN (:personIds) AND deletedAt IS NULL")
    suspend fun getByIds(personIds: List<String>): List<PersonEntity>

    // ============ Birthday and Display Number Methods ============

    /**
     * Update person's birthday.
     * Format: "MM-DD" (e.g., "01-15" for January 15th)
     */
    @Query("UPDATE persons SET birthday = :birthday, updatedAt = :updatedAt WHERE personId = :personId")
    suspend fun updateBirthday(personId: String, birthday: String?, updatedAt: Long = System.currentTimeMillis())

    /**
     * Update person's name and birthday together.
     */
    @Query("UPDATE persons SET name = :name, birthday = :birthday, updatedAt = :updatedAt WHERE personId = :personId")
    suspend fun updateNameAndBirthday(personId: String, name: String?, birthday: String?, updatedAt: Long = System.currentTimeMillis())

    /**
     * Update person's display number.
     */
    @Query("UPDATE persons SET displayNumber = :displayNumber WHERE personId = :personId")
    suspend fun updateDisplayNumber(personId: String, displayNumber: Int)

    /**
     * Get the next available display number.
     * Returns MAX(displayNumber) + 1, or 1 if no persons exist.
     */
    @Query("SELECT COALESCE(MAX(displayNumber), 0) + 1 FROM persons WHERE deletedAt IS NULL")
    suspend fun getNextDisplayNumber(): Int

    /**
     * Get persons whose birthday is today.
     * Format matching: "MM-DD"
     */
    @Query("SELECT * FROM persons WHERE birthday = :todayMMDD AND deletedAt IS NULL AND isHidden = 0")
    suspend fun getPersonsWithBirthdayToday(todayMMDD: String): List<PersonEntity>

    /**
     * Get all persons with birthdays set (for notification scheduling).
     */
    @Query("SELECT * FROM persons WHERE birthday IS NOT NULL AND deletedAt IS NULL AND isHidden = 0")
    suspend fun getPersonsWithBirthdays(): List<PersonEntity>

    /**
     * Get persons whose birthday is within the next N days.
     * Note: This is a simplified query that checks specific dates.
     * For more complex date range queries, use application logic.
     */
    @Query("SELECT * FROM persons WHERE birthday IN (:dates) AND deletedAt IS NULL AND isHidden = 0")
    suspend fun getPersonsWithUpcomingBirthdays(dates: List<String>): List<PersonEntity>
}

