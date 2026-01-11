package com.aiezzy.slideshowmaker.data.face.dao

import androidx.room.*
import com.aiezzy.slideshowmaker.data.face.entities.ClusterStatisticsEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for cluster statistics operations.
 *
 * Each cluster tracks its own internal consistency metrics to determine
 * an adaptive acceptance threshold. Clusters with consistent faces get
 * tighter thresholds, while clusters with variation get looser thresholds.
 */
@Dao
interface ClusterStatisticsDao {

    // ========================================================================
    // INSERT/UPDATE OPERATIONS
    // ========================================================================

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(statistics: ClusterStatisticsEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(statistics: List<ClusterStatisticsEntity>)

    @Update
    suspend fun update(statistics: ClusterStatisticsEntity)

    /**
     * Upsert statistics - insert or replace if exists.
     * Useful when recalculating statistics for a cluster.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(statistics: ClusterStatisticsEntity)

    // ========================================================================
    // DELETE OPERATIONS
    // ========================================================================

    @Delete
    suspend fun delete(statistics: ClusterStatisticsEntity)

    @Query("DELETE FROM cluster_statistics WHERE clusterId = :clusterId")
    suspend fun deleteByClusterId(clusterId: String)

    @Query("DELETE FROM cluster_statistics")
    suspend fun deleteAll()

    // ========================================================================
    // QUERY OPERATIONS - Single Cluster
    // ========================================================================

    /**
     * Get statistics for a specific cluster.
     */
    @Query("SELECT * FROM cluster_statistics WHERE clusterId = :clusterId")
    suspend fun getByClusterId(clusterId: String): ClusterStatisticsEntity?

    /**
     * Get statistics for a cluster as Flow for reactive updates.
     */
    @Query("SELECT * FROM cluster_statistics WHERE clusterId = :clusterId")
    fun getByClusterIdFlow(clusterId: String): Flow<ClusterStatisticsEntity?>

    /**
     * Get acceptance threshold for a specific cluster.
     * Returns null if no statistics exist (use default threshold in that case).
     */
    @Query("SELECT acceptanceThreshold FROM cluster_statistics WHERE clusterId = :clusterId")
    suspend fun getAcceptanceThreshold(clusterId: String): Float?

    // ========================================================================
    // QUERY OPERATIONS - Multiple Clusters
    // ========================================================================

    /**
     * Get all cluster statistics.
     */
    @Query("SELECT * FROM cluster_statistics ORDER BY totalFaceCount DESC")
    suspend fun getAll(): List<ClusterStatisticsEntity>

    /**
     * Get all cluster statistics as Flow.
     */
    @Query("SELECT * FROM cluster_statistics ORDER BY totalFaceCount DESC")
    fun getAllFlow(): Flow<List<ClusterStatisticsEntity>>

    /**
     * Get statistics for multiple clusters.
     */
    @Query("SELECT * FROM cluster_statistics WHERE clusterId IN (:clusterIds)")
    suspend fun getByClusterIds(clusterIds: List<String>): List<ClusterStatisticsEntity>

    /**
     * Get cluster IDs that have statistics.
     */
    @Query("SELECT clusterId FROM cluster_statistics")
    suspend fun getAllClusterIds(): List<String>

    // ========================================================================
    // THRESHOLD-BASED QUERIES
    // ========================================================================

    /**
     * Get clusters with acceptance threshold below a value.
     * Useful for finding clusters that are very consistent (tight threshold).
     */
    @Query("""
        SELECT * FROM cluster_statistics
        WHERE acceptanceThreshold < :threshold
        ORDER BY acceptanceThreshold ASC
    """)
    suspend fun getClustersWithThresholdBelow(threshold: Float): List<ClusterStatisticsEntity>

    /**
     * Get clusters with acceptance threshold above a value.
     * Useful for finding clusters with high variation (loose threshold).
     */
    @Query("""
        SELECT * FROM cluster_statistics
        WHERE acceptanceThreshold > :threshold
        ORDER BY acceptanceThreshold DESC
    """)
    suspend fun getClustersWithThresholdAbove(threshold: Float): List<ClusterStatisticsEntity>

    /**
     * Get acceptance thresholds for multiple clusters as a map.
     */
    @Query("SELECT clusterId, acceptanceThreshold FROM cluster_statistics WHERE clusterId IN (:clusterIds)")
    suspend fun getAcceptanceThresholds(clusterIds: List<String>): List<ClusterThresholdResult>

    // ========================================================================
    // STATISTICS ANALYSIS QUERIES
    // ========================================================================

    /**
     * Get clusters with high internal variance (potential split candidates).
     */
    @Query("""
        SELECT * FROM cluster_statistics
        WHERE similarityVariance > :varianceThreshold
        ORDER BY similarityVariance DESC
    """)
    suspend fun getHighVarianceClusters(varianceThreshold: Float): List<ClusterStatisticsEntity>

    /**
     * Get clusters with low minimum similarity (potential false merges).
     */
    @Query("""
        SELECT * FROM cluster_statistics
        WHERE minSimilarity < :minSimilarityThreshold
        ORDER BY minSimilarity ASC
    """)
    suspend fun getLowMinSimilarityClusters(minSimilarityThreshold: Float): List<ClusterStatisticsEntity>

    /**
     * Get clusters that need statistics recalculation (stale data).
     */
    @Query("""
        SELECT * FROM cluster_statistics
        WHERE lastUpdatedAt < :staleThreshold
        ORDER BY lastUpdatedAt ASC
    """)
    suspend fun getStaleClusters(staleThreshold: Long): List<ClusterStatisticsEntity>

    /**
     * Get average acceptance threshold across all clusters.
     */
    @Query("SELECT AVG(acceptanceThreshold) FROM cluster_statistics")
    suspend fun getAverageAcceptanceThreshold(): Float?

    /**
     * Get total count of clusters with statistics.
     */
    @Query("SELECT COUNT(*) FROM cluster_statistics")
    suspend fun getTotalCount(): Int

    // ========================================================================
    // UPDATE STATISTICS FIELDS
    // ========================================================================

    /**
     * Update acceptance threshold for a cluster.
     */
    @Query("""
        UPDATE cluster_statistics
        SET acceptanceThreshold = :threshold, lastUpdatedAt = :timestamp
        WHERE clusterId = :clusterId
    """)
    suspend fun updateAcceptanceThreshold(clusterId: String, threshold: Float, timestamp: Long)

    /**
     * Update face counts for a cluster.
     */
    @Query("""
        UPDATE cluster_statistics
        SET anchorCount = :anchorCount, totalFaceCount = :totalFaceCount, lastUpdatedAt = :timestamp
        WHERE clusterId = :clusterId
    """)
    suspend fun updateFaceCounts(clusterId: String, anchorCount: Int, totalFaceCount: Int, timestamp: Long)

    /**
     * Update similarity statistics for a cluster.
     */
    @Query("""
        UPDATE cluster_statistics
        SET meanSimilarity = :mean,
            similarityVariance = :variance,
            similarityStdDev = :stdDev,
            minSimilarity = :min,
            maxSimilarity = :max,
            acceptanceThreshold = :threshold,
            sampleCount = :sampleCount,
            lastUpdatedAt = :timestamp
        WHERE clusterId = :clusterId
    """)
    suspend fun updateSimilarityStats(
        clusterId: String,
        mean: Float,
        variance: Float,
        stdDev: Float,
        min: Float,
        max: Float,
        threshold: Float,
        sampleCount: Int,
        timestamp: Long
    )
}

/**
 * Result class for threshold queries.
 */
data class ClusterThresholdResult(
    val clusterId: String,
    val acceptanceThreshold: Float
)
