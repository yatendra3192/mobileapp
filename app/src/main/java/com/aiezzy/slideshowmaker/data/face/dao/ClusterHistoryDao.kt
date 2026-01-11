package com.aiezzy.slideshowmaker.data.face.dao

import androidx.room.*
import com.aiezzy.slideshowmaker.data.face.entities.ClusterHistoryEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for cluster history operations (undo support).
 */
@Dao
interface ClusterHistoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(history: ClusterHistoryEntity)

    @Delete
    suspend fun delete(history: ClusterHistoryEntity)

    @Query("DELETE FROM cluster_history WHERE historyId = :historyId")
    suspend fun deleteById(historyId: String)

    @Query("SELECT * FROM cluster_history WHERE historyId = :historyId")
    suspend fun getById(historyId: String): ClusterHistoryEntity?

    @Query("SELECT * FROM cluster_history WHERE clusterId = :clusterId ORDER BY timestamp DESC")
    suspend fun getByClusterId(clusterId: String): List<ClusterHistoryEntity>

    @Query("SELECT * FROM cluster_history WHERE canUndo = 1 ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentUndoable(limit: Int = 10): List<ClusterHistoryEntity>

    @Query("SELECT * FROM cluster_history WHERE canUndo = 1 ORDER BY timestamp DESC LIMIT 1")
    suspend fun getMostRecentUndoable(): ClusterHistoryEntity?

    @Query("SELECT * FROM cluster_history ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 50): List<ClusterHistoryEntity>

    @Query("SELECT * FROM cluster_history ORDER BY timestamp DESC")
    fun getAllFlow(): Flow<List<ClusterHistoryEntity>>

    @Query("DELETE FROM cluster_history")
    suspend fun deleteAll()

    @Query("DELETE FROM cluster_history WHERE expiresAt IS NOT NULL AND expiresAt < :currentTime")
    suspend fun deleteExpired(currentTime: Long = System.currentTimeMillis())

    @Query("UPDATE cluster_history SET canUndo = 0 WHERE historyId = :historyId")
    suspend fun markAsUndone(historyId: String)

    @Query("SELECT COUNT(*) FROM cluster_history WHERE canUndo = 1")
    suspend fun getUndoableCount(): Int

    /**
     * Get history entries for a specific operation type.
     */
    @Query("SELECT * FROM cluster_history WHERE operationType = :operationType ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getByOperationType(operationType: String, limit: Int = 50): List<ClusterHistoryEntity>

    /**
     * Get merge history to track which clusters were merged.
     */
    @Query("""
        SELECT * FROM cluster_history
        WHERE operationType = 'MERGE' AND (clusterId = :clusterId OR sourceClusterId = :clusterId)
        ORDER BY timestamp DESC
    """)
    suspend fun getMergeHistoryForCluster(clusterId: String): List<ClusterHistoryEntity>
}
