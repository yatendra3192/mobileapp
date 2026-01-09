package com.aiezzy.slideshowmaker.data.face.dao

import androidx.room.*
import com.aiezzy.slideshowmaker.data.face.entities.PersonEntity
import com.aiezzy.slideshowmaker.data.face.entities.PersonWithFace
import kotlinx.coroutines.flow.Flow

@Dao
interface PersonDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(person: PersonEntity)

    @Update
    suspend fun update(person: PersonEntity)

    @Delete
    suspend fun delete(person: PersonEntity)

    @Query("SELECT * FROM persons WHERE personId = :personId")
    suspend fun getById(personId: String): PersonEntity?

    @Query("SELECT * FROM persons WHERE isHidden = 0 ORDER BY photoCount DESC")
    suspend fun getAllVisible(): List<PersonEntity>

    @Query("SELECT * FROM persons WHERE isHidden = 0 ORDER BY photoCount DESC")
    fun getAllVisibleFlow(): Flow<List<PersonEntity>>

    @Query("SELECT * FROM persons ORDER BY photoCount DESC")
    suspend fun getAll(): List<PersonEntity>

    @Query("SELECT COUNT(*) FROM persons WHERE isHidden = 0")
    suspend fun getVisibleCount(): Int

    @Query("UPDATE persons SET name = :name, updatedAt = :updatedAt WHERE personId = :personId")
    suspend fun updateName(personId: String, name: String?, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE persons SET isHidden = :isHidden, updatedAt = :updatedAt WHERE personId = :personId")
    suspend fun updateHidden(personId: String, isHidden: Boolean, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE persons SET photoCount = :count, updatedAt = :updatedAt WHERE personId = :personId")
    suspend fun updatePhotoCount(personId: String, count: Int, updatedAt: Long = System.currentTimeMillis())

    @Query("""
        SELECT p.personId, p.name, p.photoCount, p.isHidden,
               df.faceId, df.photoUri, df.boundingBoxLeft, df.boundingBoxTop,
               df.boundingBoxRight, df.boundingBoxBottom
        FROM persons p
        INNER JOIN detected_faces df ON p.representativeFaceId = df.faceId
        WHERE p.isHidden = 0
        ORDER BY p.photoCount DESC
    """)
    fun getPersonsWithFaceFlow(): Flow<List<PersonWithFace>>

    @Query("""
        SELECT p.personId, p.name, p.photoCount, p.isHidden,
               df.faceId, df.photoUri, df.boundingBoxLeft, df.boundingBoxTop,
               df.boundingBoxRight, df.boundingBoxBottom
        FROM persons p
        INNER JOIN detected_faces df ON p.representativeFaceId = df.faceId
        WHERE p.isHidden = 0
        ORDER BY p.photoCount DESC
    """)
    suspend fun getPersonsWithFace(): List<PersonWithFace>

    @Query("""
        SELECT p.personId, p.name, p.photoCount, p.isHidden,
               df.faceId, df.photoUri, df.boundingBoxLeft, df.boundingBoxTop,
               df.boundingBoxRight, df.boundingBoxBottom
        FROM persons p
        INNER JOIN detected_faces df ON p.representativeFaceId = df.faceId
        WHERE p.personId = :personId
    """)
    suspend fun getPersonWithFace(personId: String): PersonWithFace?

    @Query("DELETE FROM persons")
    suspend fun deleteAll()

    @Query("""
        UPDATE persons SET photoCount = (
            SELECT COUNT(DISTINCT df.photoUri)
            FROM detected_faces df
            INNER JOIN face_clusters fc ON df.clusterId = fc.clusterId
            WHERE fc.personId = persons.personId
        )
    """)
    suspend fun recalculateAllPhotoCounts()
}
