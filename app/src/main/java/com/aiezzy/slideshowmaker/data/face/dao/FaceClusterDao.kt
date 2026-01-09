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

    @Query("SELECT * FROM face_clusters WHERE clusterId = :clusterId")
    suspend fun getById(clusterId: String): FaceClusterEntity?

    @Query("SELECT * FROM face_clusters WHERE personId = :personId")
    suspend fun getByPersonId(personId: String): List<FaceClusterEntity>

    @Query("SELECT * FROM face_clusters WHERE personId IS NULL")
    suspend fun getUnassignedClusters(): List<FaceClusterEntity>

    @Query("SELECT * FROM face_clusters")
    suspend fun getAll(): List<FaceClusterEntity>

    @Query("SELECT * FROM face_clusters")
    fun getAllFlow(): Flow<List<FaceClusterEntity>>

    @Query("SELECT COUNT(*) FROM face_clusters")
    suspend fun getTotalCount(): Int

    @Query("UPDATE face_clusters SET personId = :personId, updatedAt = :updatedAt WHERE clusterId = :clusterId")
    suspend fun assignToPerson(clusterId: String, personId: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE face_clusters SET faceCount = :count, updatedAt = :updatedAt WHERE clusterId = :clusterId")
    suspend fun updateFaceCount(clusterId: String, count: Int, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE face_clusters SET centroidEmbedding = :embedding, updatedAt = :updatedAt WHERE clusterId = :clusterId")
    suspend fun updateCentroid(clusterId: String, embedding: ByteArray?, updatedAt: Long = System.currentTimeMillis())

    @Query("""
        UPDATE face_clusters SET faceCount = (
            SELECT COUNT(*) FROM detected_faces WHERE clusterId = face_clusters.clusterId
        )
    """)
    suspend fun recalculateAllFaceCounts()

    @Query("DELETE FROM face_clusters WHERE clusterId = :clusterId")
    suspend fun deleteById(clusterId: String)

    @Query("DELETE FROM face_clusters")
    suspend fun deleteAll()

    // Merge clusters: move all faces from source to target, delete source
    @Transaction
    suspend fun mergeClusters(sourceClusterId: String, targetClusterId: String) {
        // This would be implemented in the repository with multiple operations
    }
}
