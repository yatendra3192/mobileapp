package com.aiezzy.slideshowmaker.data.face.dao

import androidx.room.*
import com.aiezzy.slideshowmaker.data.face.entities.ClusteringConstraintEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for clustering constraint operations.
 */
@Dao
interface ClusteringConstraintDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(constraint: ClusteringConstraintEntity)

    @Delete
    suspend fun delete(constraint: ClusteringConstraintEntity)

    @Query("DELETE FROM clustering_constraints WHERE constraintId = :constraintId")
    suspend fun deleteById(constraintId: String)

    @Query("SELECT * FROM clustering_constraints WHERE constraintId = :constraintId")
    suspend fun getById(constraintId: String): ClusteringConstraintEntity?

    @Query("""
        SELECT * FROM clustering_constraints
        WHERE faceId1 = :faceId OR faceId2 = :faceId
    """)
    suspend fun getConstraintsForFace(faceId: String): List<ClusteringConstraintEntity>

    @Query("""
        SELECT * FROM clustering_constraints
        WHERE constraintType = :constraintType
    """)
    suspend fun getByType(constraintType: String): List<ClusteringConstraintEntity>

    @Query("SELECT * FROM clustering_constraints")
    suspend fun getAll(): List<ClusteringConstraintEntity>

    @Query("SELECT * FROM clustering_constraints")
    fun getAllFlow(): Flow<List<ClusteringConstraintEntity>>

    @Query("DELETE FROM clustering_constraints")
    suspend fun deleteAll()

    /**
     * Check if a must-link constraint exists between two faces.
     */
    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM clustering_constraints
            WHERE constraintType = 'MUST_LINK'
            AND ((faceId1 = :faceId1 AND faceId2 = :faceId2)
                 OR (faceId1 = :faceId2 AND faceId2 = :faceId1))
        )
    """)
    suspend fun hasMustLink(faceId1: String, faceId2: String): Boolean

    /**
     * Check if a cannot-link constraint exists between two faces.
     */
    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM clustering_constraints
            WHERE constraintType = 'CANNOT_LINK'
            AND ((faceId1 = :faceId1 AND faceId2 = :faceId2)
                 OR (faceId1 = :faceId2 AND faceId2 = :faceId1))
        )
    """)
    suspend fun hasCannotLink(faceId1: String, faceId2: String): Boolean

    /**
     * Get all faces that must be linked with a given face.
     */
    @Query("""
        SELECT CASE WHEN faceId1 = :faceId THEN faceId2 ELSE faceId1 END AS linkedFaceId
        FROM clustering_constraints
        WHERE constraintType = 'MUST_LINK'
        AND (faceId1 = :faceId OR faceId2 = :faceId)
    """)
    suspend fun getMustLinkFaces(faceId: String): List<String>

    /**
     * Get all faces that cannot be linked with a given face.
     */
    @Query("""
        SELECT CASE WHEN faceId1 = :faceId THEN faceId2 ELSE faceId1 END AS linkedFaceId
        FROM clustering_constraints
        WHERE constraintType = 'CANNOT_LINK'
        AND (faceId1 = :faceId OR faceId2 = :faceId)
    """)
    suspend fun getCannotLinkFaces(faceId: String): List<String>

    /**
     * Alias for getCannotLinkFaces - returns face IDs that cannot be in the same cluster.
     */
    @Query("""
        SELECT CASE WHEN faceId1 = :faceId THEN faceId2 ELSE faceId1 END AS linkedFaceId
        FROM clustering_constraints
        WHERE constraintType = 'CANNOT_LINK'
        AND (faceId1 = :faceId OR faceId2 = :faceId)
    """)
    suspend fun getCannotLinkFaceIds(faceId: String): List<String>

    /**
     * Delete constraint between two faces regardless of order.
     */
    @Query("""
        DELETE FROM clustering_constraints
        WHERE (faceId1 = :faceId1 AND faceId2 = :faceId2)
           OR (faceId1 = :faceId2 AND faceId2 = :faceId1)
    """)
    suspend fun deleteConstraintBetween(faceId1: String, faceId2: String)

    /**
     * Count constraints by type.
     */
    @Query("SELECT COUNT(*) FROM clustering_constraints WHERE constraintType = :constraintType")
    suspend fun countByType(constraintType: String): Int
}
