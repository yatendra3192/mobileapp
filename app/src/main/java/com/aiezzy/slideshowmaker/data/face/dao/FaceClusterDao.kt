package com.aiezzy.slideshowmaker.data.face.dao

import androidx.room.*
import com.aiezzy.slideshowmaker.data.face.entities.FaceClusterEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FaceClusterDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(cluster: FaceClusterEntity)

    @Update
    suspend fun update(cluster: FaceClusterEntity)

    @Delete
    suspend fun delete(cluster: FaceClusterEntity)

    @Query("SELECT * FROM face_clusters WHERE clusterId = :clusterId AND deletedAt IS NULL")
    suspend fun getById(clusterId: String): FaceClusterEntity?

    @Query("SELECT * FROM face_clusters WHERE clusterId = :clusterId")
    suspend fun getByIdIncludingDeleted(clusterId: String): FaceClusterEntity?

    @Query("SELECT * FROM face_clusters WHERE personId = :personId AND deletedAt IS NULL")
    suspend fun getByPersonId(personId: String): List<FaceClusterEntity>

    @Query("SELECT * FROM face_clusters WHERE personId IS NULL AND deletedAt IS NULL")
    suspend fun getUnassignedClusters(): List<FaceClusterEntity>

    @Query("SELECT * FROM face_clusters WHERE deletedAt IS NULL")
    suspend fun getAll(): List<FaceClusterEntity>

    @Query("SELECT * FROM face_clusters WHERE deletedAt IS NULL")
    fun getAllFlow(): Flow<List<FaceClusterEntity>>

    @Query("SELECT COUNT(*) FROM face_clusters WHERE deletedAt IS NULL")
    suspend fun getTotalCount(): Int

    @Query("UPDATE face_clusters SET personId = :personId, updatedAt = :updatedAt WHERE clusterId = :clusterId")
    suspend fun assignToPerson(clusterId: String, personId: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE face_clusters SET faceCount = :count, updatedAt = :updatedAt WHERE clusterId = :clusterId")
    suspend fun updateFaceCount(clusterId: String, count: Int, updatedAt: Long = System.currentTimeMillis())

    /**
     * Increment face count by 1.
     * Used when adding a new face to an existing cluster.
     */
    @Query("UPDATE face_clusters SET faceCount = faceCount + 1, updatedAt = :updatedAt WHERE clusterId = :clusterId")
    suspend fun incrementFaceCount(clusterId: String, updatedAt: Long = System.currentTimeMillis())

    /**
     * Delete cluster by ID.
     */
    @Query("DELETE FROM face_clusters WHERE clusterId = :clusterId")
    suspend fun delete(clusterId: String)

    @Query("UPDATE face_clusters SET centroidEmbedding = :embedding, updatedAt = :updatedAt WHERE clusterId = :clusterId")
    suspend fun updateCentroid(clusterId: String, embedding: ByteArray?, updatedAt: Long = System.currentTimeMillis())

    // Recalculate face counts excluding soft-deleted faces
    @Query("""
        UPDATE face_clusters SET faceCount = (
            SELECT COUNT(*) FROM detected_faces
            WHERE clusterId = face_clusters.clusterId AND deletedAt IS NULL
        )
        WHERE deletedAt IS NULL
    """)
    suspend fun recalculateAllFaceCounts()

    @Query("DELETE FROM face_clusters WHERE clusterId = :clusterId")
    suspend fun deleteById(clusterId: String)

    @Query("DELETE FROM face_clusters")
    suspend fun deleteAll()

    // Soft delete - set deletedAt timestamp
    @Query("UPDATE face_clusters SET deletedAt = :deletedAt WHERE clusterId = :clusterId")
    suspend fun softDelete(clusterId: String, deletedAt: Long = System.currentTimeMillis())

    // Restore soft-deleted cluster
    @Query("UPDATE face_clusters SET deletedAt = NULL WHERE clusterId = :clusterId")
    suspend fun restore(clusterId: String)

    // Get all soft-deleted clusters for cleanup
    @Query("SELECT * FROM face_clusters WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    suspend fun getSoftDeleted(): List<FaceClusterEntity>

    // Permanently delete soft-deleted records older than threshold
    @Query("DELETE FROM face_clusters WHERE deletedAt IS NOT NULL AND deletedAt < :threshold")
    suspend fun cleanupSoftDeleted(threshold: Long)

    // Merge clusters: move all faces from source to target, delete source
    @Transaction
    suspend fun mergeClusters(sourceClusterId: String, targetClusterId: String) {
        // This would be implemented in the repository with multiple operations
    }

    // ============ Chunked/Paginated Queries for Large Datasets ============

    /**
     * Get clusters with pagination.
     * Use this for large datasets to avoid loading all clusters into memory.
     */
    @Query("""
        SELECT * FROM face_clusters
        WHERE deletedAt IS NULL
        ORDER BY faceCount DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getAllChunked(limit: Int, offset: Int): List<FaceClusterEntity>

    /**
     * Get clusters for a person with pagination.
     */
    @Query("""
        SELECT * FROM face_clusters
        WHERE personId = :personId AND deletedAt IS NULL
        ORDER BY faceCount DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getByPersonIdChunked(
        personId: String,
        limit: Int,
        offset: Int
    ): List<FaceClusterEntity>

    /**
     * Get clusters that have centroids (for HNSW index building).
     */
    @Query("""
        SELECT * FROM face_clusters
        WHERE centroidEmbedding IS NOT NULL AND deletedAt IS NULL
        ORDER BY clusterId
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getClustersWithCentroidChunked(limit: Int, offset: Int): List<FaceClusterEntity>

    /**
     * Get cluster count for building progress indicators.
     */
    @Query("SELECT COUNT(*) FROM face_clusters WHERE centroidEmbedding IS NOT NULL AND deletedAt IS NULL")
    suspend fun getClustersWithCentroidCount(): Int

    /**
     * Get clusters by IDs (batch fetch).
     * More efficient than fetching one by one.
     */
    @Query("SELECT * FROM face_clusters WHERE clusterId IN (:clusterIds) AND deletedAt IS NULL")
    suspend fun getByIds(clusterIds: List<String>): List<FaceClusterEntity>

    /**
     * Get clusters with face count in range (for analysis).
     */
    @Query("""
        SELECT * FROM face_clusters
        WHERE deletedAt IS NULL
        AND faceCount >= :minCount AND faceCount <= :maxCount
        ORDER BY faceCount DESC
    """)
    suspend fun getByFaceCountRange(minCount: Int, maxCount: Int): List<FaceClusterEntity>

    /**
     * Get clusters ordered by last update (for incremental processing).
     */
    @Query("""
        SELECT * FROM face_clusters
        WHERE deletedAt IS NULL
        ORDER BY updatedAt DESC
        LIMIT :limit
    """)
    suspend fun getRecentlyUpdated(limit: Int): List<FaceClusterEntity>
}

