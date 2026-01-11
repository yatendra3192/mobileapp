package com.aiezzy.slideshowmaker.data.face.dao

import androidx.room.*
import com.aiezzy.slideshowmaker.data.face.entities.ClusterRepresentativeEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for cluster representative operations.
 */
@Dao
interface ClusterRepresentativeDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(representative: ClusterRepresentativeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(representatives: List<ClusterRepresentativeEntity>)

    @Update
    suspend fun update(representative: ClusterRepresentativeEntity)

    @Delete
    suspend fun delete(representative: ClusterRepresentativeEntity)

    @Query("DELETE FROM cluster_representatives WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM cluster_representatives WHERE clusterId = :clusterId")
    suspend fun deleteByClusterId(clusterId: String)

    @Query("SELECT * FROM cluster_representatives WHERE id = :id")
    suspend fun getById(id: String): ClusterRepresentativeEntity?

    @Query("SELECT * FROM cluster_representatives WHERE clusterId = :clusterId ORDER BY rank ASC")
    suspend fun getByClusterId(clusterId: String): List<ClusterRepresentativeEntity>

    /**
     * Get all representatives for a cluster, sorted by rank.
     * Alias for getByClusterId for semantic clarity.
     */
    @Query("SELECT * FROM cluster_representatives WHERE clusterId = :clusterId ORDER BY rank ASC")
    suspend fun getRepresentativesForCluster(clusterId: String): List<ClusterRepresentativeEntity>

    @Query("SELECT * FROM cluster_representatives WHERE clusterId = :clusterId ORDER BY rank ASC")
    fun getByClusterIdFlow(clusterId: String): Flow<List<ClusterRepresentativeEntity>>

    @Query("SELECT * FROM cluster_representatives WHERE clusterId = :clusterId AND rank = 0 LIMIT 1")
    suspend fun getBestRepresentative(clusterId: String): ClusterRepresentativeEntity?

    @Query("SELECT * FROM cluster_representatives ORDER BY clusterId, rank")
    suspend fun getAll(): List<ClusterRepresentativeEntity>

    @Query("SELECT COUNT(*) FROM cluster_representatives WHERE clusterId = :clusterId")
    suspend fun getCountForCluster(clusterId: String): Int

    @Query("DELETE FROM cluster_representatives")
    suspend fun deleteAll()

    /**
     * Update ranks after removing a representative.
     */
    @Query("""
        UPDATE cluster_representatives
        SET rank = rank - 1
        WHERE clusterId = :clusterId AND rank > :removedRank
    """)
    suspend fun updateRanksAfterRemoval(clusterId: String, removedRank: Int)

    /**
     * Get all clusters that have representatives.
     */
    @Query("SELECT DISTINCT clusterId FROM cluster_representatives")
    suspend fun getAllClusterIds(): List<String>

    /**
     * Move representatives from one cluster to another during merge.
     */
    @Query("UPDATE cluster_representatives SET clusterId = :targetClusterId WHERE clusterId = :sourceClusterId")
    suspend fun moveToCluster(sourceClusterId: String, targetClusterId: String)

    /**
     * Get representatives for a cluster by pose category.
     */
    @Query("SELECT * FROM cluster_representatives WHERE clusterId = :clusterId AND poseCategory = :poseCategory ORDER BY qualityScore DESC")
    suspend fun getByClusterIdAndPose(clusterId: String, poseCategory: String): List<ClusterRepresentativeEntity>

    /**
     * Get best representative for each pose category in a cluster.
     */
    @Query("""
        SELECT * FROM cluster_representatives r1
        WHERE clusterId = :clusterId
        AND qualityScore = (
            SELECT MAX(qualityScore) FROM cluster_representatives r2
            WHERE r2.clusterId = r1.clusterId AND r2.poseCategory = r1.poseCategory
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
    suspend fun getBestPerPoseCategory(clusterId: String): List<ClusterRepresentativeEntity>

    /**
     * Get distinct pose categories that have representatives for a cluster.
     */
    @Query("SELECT DISTINCT poseCategory FROM cluster_representatives WHERE clusterId = :clusterId")
    suspend fun getPoseCategoriesForCluster(clusterId: String): List<String>

    /**
     * Check if a cluster has a representative for a specific pose category.
     */
    @Query("SELECT COUNT(*) > 0 FROM cluster_representatives WHERE clusterId = :clusterId AND poseCategory = :poseCategory")
    suspend fun hasPoseCategory(clusterId: String, poseCategory: String): Boolean
}
