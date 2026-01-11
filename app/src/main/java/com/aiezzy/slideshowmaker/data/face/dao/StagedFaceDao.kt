package com.aiezzy.slideshowmaker.data.face.dao

import androidx.room.*
import com.aiezzy.slideshowmaker.data.face.entities.StagedFaceEntity
import com.aiezzy.slideshowmaker.data.face.entities.StagingStatus
import kotlinx.coroutines.flow.Flow

/**
 * DAO for the staging buffer.
 * Faces with uncertain similarity scores are held here until batch verification.
 */
@Dao
interface StagedFaceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(face: StagedFaceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(faces: List<StagedFaceEntity>)

    @Update
    suspend fun update(face: StagedFaceEntity)

    @Delete
    suspend fun delete(face: StagedFaceEntity)

    @Query("DELETE FROM staged_faces WHERE faceId = :faceId")
    suspend fun deleteById(faceId: String)

    @Query("DELETE FROM staged_faces WHERE status = :status")
    suspend fun deleteByStatus(status: String)

    @Query("DELETE FROM staged_faces")
    suspend fun deleteAll()

    // ============ Query Methods ============

    @Query("SELECT * FROM staged_faces WHERE faceId = :faceId")
    suspend fun getById(faceId: String): StagedFaceEntity?

    @Query("SELECT * FROM staged_faces WHERE status = :status ORDER BY createdAt ASC")
    suspend fun getByStatus(status: String): List<StagedFaceEntity>

    @Query("SELECT * FROM staged_faces WHERE status = '${StagingStatus.PENDING}' ORDER BY createdAt ASC")
    suspend fun getPendingFaces(): List<StagedFaceEntity>

    @Query("SELECT * FROM staged_faces WHERE status = '${StagingStatus.VERIFIED}' ORDER BY qualityScore DESC")
    suspend fun getVerifiedFaces(): List<StagedFaceEntity>

    @Query("SELECT COUNT(*) FROM staged_faces WHERE status = '${StagingStatus.PENDING}'")
    suspend fun getPendingCount(): Int

    @Query("SELECT COUNT(*) FROM staged_faces")
    suspend fun getTotalCount(): Int

    // ============ Batch Operations ============

    /**
     * Get pending faces for batch verification.
     * Returns faces ordered by creation time (oldest first).
     */
    @Query("""
        SELECT * FROM staged_faces
        WHERE status = '${StagingStatus.PENDING}'
        ORDER BY createdAt ASC
        LIMIT :limit
    """)
    suspend fun getPendingBatch(limit: Int): List<StagedFaceEntity>

    /**
     * Get pending faces older than a certain time.
     * Used to ensure faces don't stay in staging too long.
     */
    @Query("""
        SELECT * FROM staged_faces
        WHERE status = '${StagingStatus.PENDING}'
        AND createdAt < :olderThan
        ORDER BY createdAt ASC
    """)
    suspend fun getPendingOlderThan(olderThan: Long): List<StagedFaceEntity>

    /**
     * Update status for multiple faces.
     */
    @Query("UPDATE staged_faces SET status = :newStatus WHERE faceId IN (:faceIds)")
    suspend fun updateStatusBatch(faceIds: List<String>, newStatus: String)

    /**
     * Mark faces as verified with updated cluster assignment.
     */
    @Query("""
        UPDATE staged_faces
        SET status = '${StagingStatus.VERIFIED}',
            candidateClusterId = :clusterId,
            candidateSimilarity = :similarity
        WHERE faceId = :faceId
    """)
    suspend fun markVerified(faceId: String, clusterId: String, similarity: Float)

    /**
     * Mark face as committed (moved to main detected_faces table).
     */
    @Query("UPDATE staged_faces SET status = '${StagingStatus.COMMITTED}' WHERE faceId = :faceId")
    suspend fun markCommitted(faceId: String)

    /**
     * Mark face as rejected (failed verification or conflict).
     */
    @Query("UPDATE staged_faces SET status = '${StagingStatus.REJECTED}' WHERE faceId = :faceId")
    suspend fun markRejected(faceId: String)

    // ============ Conflict Detection ============

    /**
     * Get faces with conflicting cluster matches.
     * A conflict is when candidateSimilarity and conflictingSimilarity are close.
     */
    @Query("""
        SELECT * FROM staged_faces
        WHERE status = '${StagingStatus.PENDING}'
        AND conflictingClusterId IS NOT NULL
        AND (candidateSimilarity - conflictingSimilarity) < :threshold
        ORDER BY createdAt ASC
    """)
    suspend fun getConflictingFaces(threshold: Float = 0.15f): List<StagedFaceEntity>

    /**
     * Get faces matching a specific candidate cluster.
     */
    @Query("""
        SELECT * FROM staged_faces
        WHERE candidateClusterId = :clusterId
        AND status = '${StagingStatus.PENDING}'
    """)
    suspend fun getFacesByCandidateCluster(clusterId: String): List<StagedFaceEntity>

    // ============ Flow Methods for UI ============

    @Query("SELECT COUNT(*) FROM staged_faces WHERE status = '${StagingStatus.PENDING}'")
    fun getPendingCountFlow(): Flow<Int>

    @Query("SELECT * FROM staged_faces WHERE status = '${StagingStatus.PENDING}' ORDER BY createdAt ASC")
    fun getPendingFacesFlow(): Flow<List<StagedFaceEntity>>

    // ============ Cleanup ============

    /**
     * Delete committed faces (cleanup after successful transfer to main table).
     */
    @Query("DELETE FROM staged_faces WHERE status = '${StagingStatus.COMMITTED}'")
    suspend fun cleanupCommitted()

    /**
     * Delete old rejected faces.
     */
    @Query("DELETE FROM staged_faces WHERE status = '${StagingStatus.REJECTED}' AND createdAt < :olderThan")
    suspend fun cleanupRejected(olderThan: Long)

    /**
     * Delete all faces older than a certain time regardless of status.
     * Safety cleanup to prevent staging buffer from growing too large.
     */
    @Query("DELETE FROM staged_faces WHERE createdAt < :olderThan")
    suspend fun cleanupOldFaces(olderThan: Long)
}
