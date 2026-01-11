package com.aiezzy.slideshowmaker.data.face.dao

import androidx.room.*
import com.aiezzy.slideshowmaker.data.face.entities.DetectedFaceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DetectedFaceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(face: DetectedFaceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(faces: List<DetectedFaceEntity>)

    @Update
    suspend fun update(face: DetectedFaceEntity)

    @Delete
    suspend fun delete(face: DetectedFaceEntity)

    @Query("SELECT * FROM detected_faces WHERE faceId = :faceId AND deletedAt IS NULL")
    suspend fun getById(faceId: String): DetectedFaceEntity?

    @Query("SELECT * FROM detected_faces WHERE faceId = :faceId")
    suspend fun getByIdIncludingDeleted(faceId: String): DetectedFaceEntity?

    @Query("SELECT * FROM detected_faces WHERE photoUri = :photoUri AND deletedAt IS NULL")
    suspend fun getByPhotoUri(photoUri: String): List<DetectedFaceEntity>

    @Query("SELECT * FROM detected_faces WHERE clusterId = :clusterId AND deletedAt IS NULL")
    suspend fun getByClusterId(clusterId: String): List<DetectedFaceEntity>

    @Query("SELECT * FROM detected_faces WHERE clusterId IS NULL AND deletedAt IS NULL")
    suspend fun getUnclusteredFaces(): List<DetectedFaceEntity>

    /**
     * Get all unclustered faces that have valid embeddings.
     * Used by BatchClusteringService for post-scan clustering.
     */
    @Query("""
        SELECT * FROM detected_faces
        WHERE clusterId IS NULL
        AND deletedAt IS NULL
        AND embedding IS NOT NULL
        ORDER BY confidence DESC
    """)
    suspend fun getUnclusteredFacesWithEmbeddings(): List<DetectedFaceEntity>

    /**
     * Assign a face to a cluster with match score.
     */
    @Query("UPDATE detected_faces SET clusterId = :clusterId, matchScore = :matchScore WHERE faceId = :faceId")
    suspend fun assignToCluster(faceId: String, clusterId: String, matchScore: Float)

    /**
     * Move all faces from one cluster to another.
     * Used during cluster merging.
     */
    @Query("UPDATE detected_faces SET clusterId = :targetClusterId WHERE clusterId = :sourceClusterId AND deletedAt IS NULL")
    suspend fun moveAllFacesToCluster(sourceClusterId: String, targetClusterId: String)

    @Query("SELECT COUNT(*) FROM detected_faces WHERE deletedAt IS NULL")
    suspend fun getTotalCount(): Int

    @Query("SELECT COUNT(*) FROM detected_faces WHERE clusterId IS NOT NULL AND deletedAt IS NULL")
    suspend fun getClusteredCount(): Int

    @Query("UPDATE detected_faces SET clusterId = :clusterId WHERE faceId = :faceId")
    suspend fun updateClusterId(faceId: String, clusterId: String)

    @Query("UPDATE detected_faces SET clusterId = :clusterId, matchScore = :matchScore WHERE faceId = :faceId")
    suspend fun updateClusterIdWithMatchScore(faceId: String, clusterId: String, matchScore: Float)

    @Query("UPDATE detected_faces SET clusterId = :newClusterId WHERE clusterId = :oldClusterId AND deletedAt IS NULL")
    suspend fun updateAllClusterIds(oldClusterId: String, newClusterId: String)

    @Query("SELECT DISTINCT photoUri FROM detected_faces WHERE clusterId = :clusterId AND deletedAt IS NULL")
    suspend fun getPhotoUrisForCluster(clusterId: String): List<String>

    // Soft delete - set deletedAt timestamp
    @Query("UPDATE detected_faces SET deletedAt = :deletedAt WHERE faceId = :faceId")
    suspend fun softDelete(faceId: String, deletedAt: Long = System.currentTimeMillis())

    // Restore soft-deleted face
    @Query("UPDATE detected_faces SET deletedAt = NULL WHERE faceId = :faceId")
    suspend fun restore(faceId: String)

    // Get all soft-deleted faces for cleanup
    @Query("SELECT * FROM detected_faces WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    suspend fun getSoftDeleted(): List<DetectedFaceEntity>

    // Permanently delete soft-deleted records older than threshold
    @Query("DELETE FROM detected_faces WHERE deletedAt IS NOT NULL AND deletedAt < :threshold")
    suspend fun cleanupSoftDeleted(threshold: Long)

    // Get photo URIs for a person, sorted by best matching face (highest matchScore first)
    // Falls back to confidence if matchScore is not available
    @Query("""
        SELECT df.photoUri
        FROM detected_faces df
        INNER JOIN face_clusters fc ON df.clusterId = fc.clusterId
        WHERE fc.personId = :personId
        AND df.deletedAt IS NULL AND fc.deletedAt IS NULL
        GROUP BY df.photoUri
        ORDER BY MAX(COALESCE(df.matchScore, 0)) DESC, MAX(df.confidence) DESC
    """)
    suspend fun getPhotoUrisForPerson(personId: String): List<String>

    // Get solo photo URIs for a person - photos where ONLY this person appears
    // This is used for "Only them" filter feature
    @Query("""
        SELECT df.photoUri
        FROM detected_faces df
        INNER JOIN face_clusters fc ON df.clusterId = fc.clusterId
        WHERE fc.personId = :personId
        AND df.deletedAt IS NULL AND fc.deletedAt IS NULL
        AND df.photoUri NOT IN (
            SELECT df2.photoUri
            FROM detected_faces df2
            INNER JOIN face_clusters fc2 ON df2.clusterId = fc2.clusterId
            WHERE fc2.personId != :personId
            AND df2.deletedAt IS NULL AND fc2.deletedAt IS NULL
        )
        GROUP BY df.photoUri
        ORDER BY MAX(COALESCE(df.matchScore, 0)) DESC, MAX(df.confidence) DESC
    """)
    suspend fun getSoloPhotoUrisForPerson(personId: String): List<String>

    // Get photos containing all specified persons, excluding soft-deleted records
    @Query("""
        SELECT DISTINCT df.photoUri
        FROM detected_faces df
        INNER JOIN face_clusters fc ON df.clusterId = fc.clusterId
        WHERE fc.personId IN (:personIds)
        AND df.deletedAt IS NULL AND fc.deletedAt IS NULL
        GROUP BY df.photoUri
        HAVING COUNT(DISTINCT fc.personId) = :personCount
    """)
    suspend fun getPhotoUrisContainingAllPersons(personIds: List<String>, personCount: Int): List<String>

    @Query("SELECT * FROM detected_faces WHERE deletedAt IS NULL")
    fun getAllFacesFlow(): Flow<List<DetectedFaceEntity>>

    @Query("DELETE FROM detected_faces WHERE photoUri = :photoUri")
    suspend fun deleteByPhotoUri(photoUri: String)

    @Query("DELETE FROM detected_faces")
    suspend fun deleteAll()

    // Get faces for a person (with photo URI and bounding box), excluding soft-deleted
    @Query("""
        SELECT df.*
        FROM detected_faces df
        INNER JOIN face_clusters fc ON df.clusterId = fc.clusterId
        WHERE fc.personId = :personId
        AND df.deletedAt IS NULL AND fc.deletedAt IS NULL
        ORDER BY df.matchScore DESC, df.qualityScore DESC
    """)
    suspend fun getFacesForPerson(personId: String): List<DetectedFaceEntity>

    /**
     * Get faces for a person ordered by match score (similarity to cluster).
     * Best matches appear first, worst matches last.
     */
    @Query("""
        SELECT df.*
        FROM detected_faces df
        INNER JOIN face_clusters fc ON df.clusterId = fc.clusterId
        WHERE fc.personId = :personId
        AND df.deletedAt IS NULL AND fc.deletedAt IS NULL
        ORDER BY df.matchScore DESC, df.qualityScore DESC
    """)
    suspend fun getFacesForPersonByMatchScore(personId: String): List<DetectedFaceEntity>

    /**
     * Get the best face for thumbnail selection.
     * Prioritizes faces with high quality AND high eye visibility.
     */
    @Query("""
        SELECT df.*
        FROM detected_faces df
        INNER JOIN face_clusters fc ON df.clusterId = fc.clusterId
        WHERE fc.personId = :personId
        AND df.deletedAt IS NULL AND fc.deletedAt IS NULL
        AND df.eyeVisibilityScore > 0
        ORDER BY df.qualityScore DESC, df.matchScore DESC
        LIMIT 1
    """)
    suspend fun getBestFaceForThumbnail(personId: String): DetectedFaceEntity?

    // Remove face from cluster (set cluster to null - will be re-clustered or ignored)
    @Query("UPDATE detected_faces SET clusterId = NULL WHERE faceId = :faceId")
    suspend fun removeFaceFromCluster(faceId: String)

    // Get face by photo URI and person (to find specific face in a photo for a person)
    @Query("""
        SELECT df.*
        FROM detected_faces df
        INNER JOIN face_clusters fc ON df.clusterId = fc.clusterId
        WHERE df.photoUri = :photoUri AND fc.personId = :personId
        AND df.deletedAt IS NULL AND fc.deletedAt IS NULL
        LIMIT 1
    """)
    suspend fun getFaceByPhotoAndPerson(photoUri: String, personId: String): DetectedFaceEntity?

    // Get best (highest confidence) face for a cluster - used for orphan recovery
    @Query("""
        SELECT * FROM detected_faces
        WHERE clusterId = :clusterId AND deletedAt IS NULL
        ORDER BY confidence DESC
        LIMIT 1
    """)
    suspend fun getBestFaceForCluster(clusterId: String): DetectedFaceEntity?

    // Get photo count for a cluster - used for orphan recovery
    @Query("""
        SELECT COUNT(DISTINCT photoUri)
        FROM detected_faces
        WHERE clusterId = :clusterId AND deletedAt IS NULL
    """)
    suspend fun getPhotoCountForCluster(clusterId: String): Int

    // Alias for getByClusterId - used by FaceRepository
    @Query("SELECT * FROM detected_faces WHERE clusterId = :clusterId AND deletedAt IS NULL")
    suspend fun getFacesByClusterId(clusterId: String): List<DetectedFaceEntity>

    /**
     * Get face IDs in a cluster - used for constraint checking.
     * Returns only face IDs (not full entities) for efficiency.
     */
    @Query("SELECT faceId FROM detected_faces WHERE clusterId = :clusterId AND deletedAt IS NULL")
    suspend fun getFaceIdsInCluster(clusterId: String): List<String>

    // ============ Chunked/Paginated Queries for Large Datasets ============

    /**
     * Get faces for a cluster with pagination.
     * Use this for clusters with many faces to avoid loading all into memory.
     */
    @Query("""
        SELECT * FROM detected_faces
        WHERE clusterId = :clusterId AND deletedAt IS NULL
        ORDER BY confidence DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getFacesByClusterIdChunked(
        clusterId: String,
        limit: Int,
        offset: Int
    ): List<DetectedFaceEntity>

    /**
     * Get faces for a person with pagination.
     */
    @Query("""
        SELECT df.*
        FROM detected_faces df
        INNER JOIN face_clusters fc ON df.clusterId = fc.clusterId
        WHERE fc.personId = :personId
        AND df.deletedAt IS NULL AND fc.deletedAt IS NULL
        ORDER BY df.confidence DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getFacesForPersonChunked(
        personId: String,
        limit: Int,
        offset: Int
    ): List<DetectedFaceEntity>

    /**
     * Get photo URIs for a person with pagination.
     */
    @Query("""
        SELECT DISTINCT df.photoUri
        FROM detected_faces df
        INNER JOIN face_clusters fc ON df.clusterId = fc.clusterId
        WHERE fc.personId = :personId
        AND df.deletedAt IS NULL AND fc.deletedAt IS NULL
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getPhotoUrisForPersonChunked(
        personId: String,
        limit: Int,
        offset: Int
    ): List<String>

    /**
     * Get total face count for a cluster.
     */
    @Query("SELECT COUNT(*) FROM detected_faces WHERE clusterId = :clusterId AND deletedAt IS NULL")
    suspend fun getFaceCountForCluster(clusterId: String): Int

    /**
     * Get total face count for a person.
     */
    @Query("""
        SELECT COUNT(*)
        FROM detected_faces df
        INNER JOIN face_clusters fc ON df.clusterId = fc.clusterId
        WHERE fc.personId = :personId
        AND df.deletedAt IS NULL AND fc.deletedAt IS NULL
    """)
    suspend fun getFaceCountForPerson(personId: String): Int

    /**
     * Get all faces with embeddings for a cluster (for clustering analysis).
     * Only returns faces that have valid embeddings.
     */
    @Query("""
        SELECT * FROM detected_faces
        WHERE clusterId = :clusterId
        AND deletedAt IS NULL
        AND embedding IS NOT NULL
        ORDER BY confidence DESC
    """)
    suspend fun getFacesWithEmbeddingsByClusterId(clusterId: String): List<DetectedFaceEntity>

    /**
     * Get all faces with embeddings for a person (for medoid calculation).
     * Only returns faces that have valid embeddings.
     */
    @Query("""
        SELECT df.*
        FROM detected_faces df
        INNER JOIN face_clusters fc ON df.clusterId = fc.clusterId
        WHERE fc.personId = :personId
        AND df.deletedAt IS NULL AND fc.deletedAt IS NULL
        AND df.embedding IS NOT NULL
        ORDER BY df.confidence DESC
    """)
    suspend fun getFacesWithEmbeddingsForPerson(personId: String): List<DetectedFaceEntity>

    /**
     * Batch update cluster IDs for multiple faces.
     * More efficient than updating one by one.
     */
    @Query("UPDATE detected_faces SET clusterId = :newClusterId WHERE faceId IN (:faceIds)")
    suspend fun updateClusterIdBatch(faceIds: List<String>, newClusterId: String)

    /**
     * Get the best matching face for a cluster (highest matchScore).
     * This is used for thumbnail selection - the face that best represents the cluster.
     */
    @Query("""
        SELECT * FROM detected_faces
        WHERE clusterId = :clusterId AND deletedAt IS NULL AND matchScore IS NOT NULL
        ORDER BY matchScore DESC
        LIMIT 1
    """)
    suspend fun getBestMatchingFaceForCluster(clusterId: String): DetectedFaceEntity?

    /**
     * Get the best matching face for a person across all their clusters.
     * Falls back to highest detection confidence if no matchScore available.
     */
    @Query("""
        SELECT df.*
        FROM detected_faces df
        INNER JOIN face_clusters fc ON df.clusterId = fc.clusterId
        WHERE fc.personId = :personId
        AND df.deletedAt IS NULL AND fc.deletedAt IS NULL
        ORDER BY COALESCE(df.matchScore, 0) DESC, df.confidence DESC
        LIMIT 1
    """)
    suspend fun getBestMatchingFaceForPerson(personId: String): DetectedFaceEntity?
}

