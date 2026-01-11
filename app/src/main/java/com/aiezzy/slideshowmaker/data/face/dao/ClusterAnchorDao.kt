package com.aiezzy.slideshowmaker.data.face.dao

import androidx.room.*
import com.aiezzy.slideshowmaker.data.face.entities.ClusterAnchorEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for cluster anchor operations.
 *
 * Anchors are high-quality faces that define cluster identity.
 * Only ANCHOR-tier faces (quality >= 65, sharpness >= 15, pose <= 20°, eyes >= 7)
 * are stored here. New faces are matched ONLY against anchors, never against
 * other regular faces.
 */
@Dao
interface ClusterAnchorDao {

    // ========================================================================
    // INSERT OPERATIONS
    // ========================================================================

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(anchor: ClusterAnchorEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(anchors: List<ClusterAnchorEntity>)

    // ========================================================================
    // UPDATE OPERATIONS
    // ========================================================================

    @Update
    suspend fun update(anchor: ClusterAnchorEntity)

    /**
     * Update anchor statistics after a successful match.
     */
    @Query("""
        UPDATE cluster_anchors
        SET lastMatchedAt = :timestamp, matchCount = matchCount + 1
        WHERE anchorId = :anchorId
    """)
    suspend fun updateMatchStats(anchorId: String, timestamp: Long)

    /**
     * Update intra-cluster mean similarity for an anchor.
     */
    @Query("""
        UPDATE cluster_anchors
        SET intraClusterMeanSimilarity = :meanSimilarity
        WHERE anchorId = :anchorId
    """)
    suspend fun updateIntraClusterSimilarity(anchorId: String, meanSimilarity: Float)

    /**
     * Activate or deactivate an anchor.
     */
    @Query("UPDATE cluster_anchors SET isActive = :isActive WHERE anchorId = :anchorId")
    suspend fun setActive(anchorId: String, isActive: Boolean)

    /**
     * Move anchors from one cluster to another during merge.
     */
    @Query("UPDATE cluster_anchors SET clusterId = :targetClusterId WHERE clusterId = :sourceClusterId")
    suspend fun moveToCluster(sourceClusterId: String, targetClusterId: String)

    // ========================================================================
    // DELETE OPERATIONS
    // ========================================================================

    @Delete
    suspend fun delete(anchor: ClusterAnchorEntity)

    @Query("DELETE FROM cluster_anchors WHERE anchorId = :anchorId")
    suspend fun deleteById(anchorId: String)

    @Query("DELETE FROM cluster_anchors WHERE clusterId = :clusterId")
    suspend fun deleteByClusterId(clusterId: String)

    @Query("DELETE FROM cluster_anchors WHERE faceId = :faceId")
    suspend fun deleteByFaceId(faceId: String)

    @Query("DELETE FROM cluster_anchors")
    suspend fun deleteAll()

    // ========================================================================
    // QUERY OPERATIONS - Single Anchor
    // ========================================================================

    @Query("SELECT * FROM cluster_anchors WHERE anchorId = :anchorId")
    suspend fun getById(anchorId: String): ClusterAnchorEntity?

    @Query("SELECT * FROM cluster_anchors WHERE faceId = :faceId")
    suspend fun getByFaceId(faceId: String): ClusterAnchorEntity?

    // ========================================================================
    // QUERY OPERATIONS - By Cluster
    // ========================================================================

    /**
     * Get all anchors for a cluster, sorted by quality (best first).
     */
    @Query("""
        SELECT * FROM cluster_anchors
        WHERE clusterId = :clusterId AND isActive = 1
        ORDER BY qualityScore DESC
    """)
    suspend fun getActiveAnchorsForCluster(clusterId: String): List<ClusterAnchorEntity>

    /**
     * Get all anchors for a cluster (including inactive), sorted by quality.
     */
    @Query("""
        SELECT * FROM cluster_anchors
        WHERE clusterId = :clusterId
        ORDER BY qualityScore DESC
    """)
    suspend fun getAllAnchorsForCluster(clusterId: String): List<ClusterAnchorEntity>

    /**
     * Get anchors for a cluster as Flow for reactive updates.
     */
    @Query("""
        SELECT * FROM cluster_anchors
        WHERE clusterId = :clusterId AND isActive = 1
        ORDER BY qualityScore DESC
    """)
    fun getActiveAnchorsForClusterFlow(clusterId: String): Flow<List<ClusterAnchorEntity>>

    /**
     * Get count of active anchors for a cluster.
     */
    @Query("SELECT COUNT(*) FROM cluster_anchors WHERE clusterId = :clusterId AND isActive = 1")
    suspend fun getActiveCountForCluster(clusterId: String): Int

    /**
     * Get total count of anchors for a cluster.
     */
    @Query("SELECT COUNT(*) FROM cluster_anchors WHERE clusterId = :clusterId")
    suspend fun getTotalCountForCluster(clusterId: String): Int

    // ========================================================================
    // QUERY OPERATIONS - By Pose Category
    // ========================================================================

    /**
     * Get anchors for a cluster filtered by pose category.
     */
    @Query("""
        SELECT * FROM cluster_anchors
        WHERE clusterId = :clusterId AND poseCategory = :poseCategory AND isActive = 1
        ORDER BY qualityScore DESC
    """)
    suspend fun getByClusterAndPose(clusterId: String, poseCategory: String): List<ClusterAnchorEntity>

    /**
     * Get best anchor for each pose category in a cluster.
     */
    @Query("""
        SELECT * FROM cluster_anchors a1
        WHERE clusterId = :clusterId AND isActive = 1
        AND qualityScore = (
            SELECT MAX(qualityScore) FROM cluster_anchors a2
            WHERE a2.clusterId = a1.clusterId
            AND a2.poseCategory = a1.poseCategory
            AND a2.isActive = 1
        )
        ORDER BY
            CASE poseCategory
                WHEN 'FRONTAL' THEN 1
                WHEN 'SLIGHT_LEFT' THEN 2
                WHEN 'SLIGHT_RIGHT' THEN 3
                WHEN 'PROFILE_LEFT' THEN 4
                WHEN 'PROFILE_RIGHT' THEN 5
                ELSE 6
            END
    """)
    suspend fun getBestAnchorPerPoseCategory(clusterId: String): List<ClusterAnchorEntity>

    /**
     * Get distinct pose categories that have anchors for a cluster.
     */
    @Query("""
        SELECT DISTINCT poseCategory FROM cluster_anchors
        WHERE clusterId = :clusterId AND isActive = 1
    """)
    suspend fun getPoseCategoriesForCluster(clusterId: String): List<String>

    /**
     * Check if a cluster has an anchor for a specific pose category.
     */
    @Query("""
        SELECT COUNT(*) > 0 FROM cluster_anchors
        WHERE clusterId = :clusterId AND poseCategory = :poseCategory AND isActive = 1
    """)
    suspend fun hasPoseCategory(clusterId: String, poseCategory: String): Boolean

    // ========================================================================
    // QUERY OPERATIONS - Global
    // ========================================================================

    /**
     * Get all active anchors across all clusters.
     */
    @Query("SELECT * FROM cluster_anchors WHERE isActive = 1 ORDER BY clusterId, qualityScore DESC")
    suspend fun getAllActiveAnchors(): List<ClusterAnchorEntity>

    /**
     * Get all anchors (including inactive).
     */
    @Query("SELECT * FROM cluster_anchors ORDER BY clusterId, qualityScore DESC")
    suspend fun getAll(): List<ClusterAnchorEntity>

    /**
     * Get all cluster IDs that have anchors.
     */
    @Query("SELECT DISTINCT clusterId FROM cluster_anchors WHERE isActive = 1")
    suspend fun getAllClusterIdsWithAnchors(): List<String>

    /**
     * Get total count of active anchors.
     */
    @Query("SELECT COUNT(*) FROM cluster_anchors WHERE isActive = 1")
    suspend fun getTotalActiveCount(): Int

    // ========================================================================
    // MATCHING SUPPORT OPERATIONS
    // ========================================================================

    /**
     * Get top N anchors for a cluster by quality for matching.
     * Used in multi-anchor matching where we compare against top N anchors.
     */
    @Query("""
        SELECT * FROM cluster_anchors
        WHERE clusterId = :clusterId AND isActive = 1
        ORDER BY qualityScore DESC
        LIMIT :limit
    """)
    suspend fun getTopAnchorsForMatching(clusterId: String, limit: Int): List<ClusterAnchorEntity>

    /**
     * Get anchors with embeddings for all clusters (for index building).
     * Returns only active anchors with their embeddings.
     */
    @Query("""
        SELECT * FROM cluster_anchors
        WHERE isActive = 1 AND embedding IS NOT NULL
        ORDER BY clusterId
    """)
    suspend fun getAllAnchorsWithEmbeddings(): List<ClusterAnchorEntity>

    /**
     * Get the best quality anchor for a cluster (used as cluster representative).
     */
    @Query("""
        SELECT * FROM cluster_anchors
        WHERE clusterId = :clusterId AND isActive = 1
        ORDER BY qualityScore DESC
        LIMIT 1
    """)
    suspend fun getBestAnchorForCluster(clusterId: String): ClusterAnchorEntity?

    // ========================================================================
    // STATISTICS SUPPORT OPERATIONS
    // ========================================================================

    /**
     * Get anchors for computing cluster statistics.
     * Returns active anchors with embeddings for pairwise similarity calculation.
     */
    @Query("""
        SELECT * FROM cluster_anchors
        WHERE clusterId = :clusterId AND isActive = 1 AND embedding IS NOT NULL
    """)
    suspend fun getAnchorsForStatistics(clusterId: String): List<ClusterAnchorEntity>

    /**
     * Get clusters that need statistics recalculation (based on anchor count change).
     */
    @Query("""
        SELECT DISTINCT clusterId FROM cluster_anchors
        WHERE isActive = 1
        GROUP BY clusterId
        HAVING COUNT(*) >= 2
    """)
    suspend fun getClustersNeedingStatistics(): List<String>
}
