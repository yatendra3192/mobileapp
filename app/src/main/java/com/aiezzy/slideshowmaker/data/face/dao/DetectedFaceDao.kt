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

    @Query("SELECT * FROM detected_faces WHERE faceId = :faceId")
    suspend fun getById(faceId: String): DetectedFaceEntity?

    @Query("SELECT * FROM detected_faces WHERE photoUri = :photoUri")
    suspend fun getByPhotoUri(photoUri: String): List<DetectedFaceEntity>

    @Query("SELECT * FROM detected_faces WHERE clusterId = :clusterId")
    suspend fun getByClusterId(clusterId: String): List<DetectedFaceEntity>

    @Query("SELECT * FROM detected_faces WHERE clusterId IS NULL")
    suspend fun getUnclusteredFaces(): List<DetectedFaceEntity>

    @Query("SELECT COUNT(*) FROM detected_faces")
    suspend fun getTotalCount(): Int

    @Query("SELECT COUNT(*) FROM detected_faces WHERE clusterId IS NOT NULL")
    suspend fun getClusteredCount(): Int

    @Query("UPDATE detected_faces SET clusterId = :clusterId WHERE faceId = :faceId")
    suspend fun updateClusterId(faceId: String, clusterId: String)

    @Query("UPDATE detected_faces SET clusterId = :newClusterId WHERE clusterId = :oldClusterId")
    suspend fun updateAllClusterIds(oldClusterId: String, newClusterId: String)

    @Query("SELECT DISTINCT photoUri FROM detected_faces WHERE clusterId = :clusterId")
    suspend fun getPhotoUrisForCluster(clusterId: String): List<String>

    @Query("""
        SELECT DISTINCT df.photoUri
        FROM detected_faces df
        INNER JOIN face_clusters fc ON df.clusterId = fc.clusterId
        WHERE fc.personId = :personId
    """)
    suspend fun getPhotoUrisForPerson(personId: String): List<String>

    @Query("""
        SELECT DISTINCT df.photoUri
        FROM detected_faces df
        INNER JOIN face_clusters fc ON df.clusterId = fc.clusterId
        WHERE fc.personId IN (:personIds)
        GROUP BY df.photoUri
        HAVING COUNT(DISTINCT fc.personId) = :personCount
    """)
    suspend fun getPhotoUrisContainingAllPersons(personIds: List<String>, personCount: Int): List<String>

    @Query("SELECT * FROM detected_faces")
    fun getAllFacesFlow(): Flow<List<DetectedFaceEntity>>

    @Query("DELETE FROM detected_faces WHERE photoUri = :photoUri")
    suspend fun deleteByPhotoUri(photoUri: String)

    @Query("DELETE FROM detected_faces")
    suspend fun deleteAll()
}
