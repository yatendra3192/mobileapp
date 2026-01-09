package com.aiezzy.slideshowmaker.data.face.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Represents a photo that has been scanned for faces
 */
@Entity(tableName = "scanned_photos")
data class ScannedPhotoEntity(
    @PrimaryKey
    val photoUri: String,
    val dateAdded: Long,
    val dateTaken: Long?,
    val scanStatus: String,  // PENDING, SCANNED, FAILED, NO_FACES
    val lastScanned: Long?,
    val displayName: String? = null,
    val bucketName: String? = null  // Album/folder name
)

/**
 * Represents a detected face in a photo
 */
@Entity(
    tableName = "detected_faces",
    foreignKeys = [
        ForeignKey(
            entity = ScannedPhotoEntity::class,
            parentColumns = ["photoUri"],
            childColumns = ["photoUri"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["photoUri"]),
        Index(value = ["clusterId"])
    ]
)
data class DetectedFaceEntity(
    @PrimaryKey
    val faceId: String,
    val photoUri: String,
    val boundingBoxLeft: Float,
    val boundingBoxTop: Float,
    val boundingBoxRight: Float,
    val boundingBoxBottom: Float,
    val embedding: ByteArray?,  // 128-dim float array serialized
    val confidence: Float,
    val clusterId: String?,
    val imageWidth: Int,
    val imageHeight: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DetectedFaceEntity

        if (faceId != other.faceId) return false
        if (photoUri != other.photoUri) return false
        if (boundingBoxLeft != other.boundingBoxLeft) return false
        if (boundingBoxTop != other.boundingBoxTop) return false
        if (boundingBoxRight != other.boundingBoxRight) return false
        if (boundingBoxBottom != other.boundingBoxBottom) return false
        if (embedding != null) {
            if (other.embedding == null) return false
            if (!embedding.contentEquals(other.embedding)) return false
        } else if (other.embedding != null) return false
        if (confidence != other.confidence) return false
        if (clusterId != other.clusterId) return false

        return true
    }

    override fun hashCode(): Int {
        var result = faceId.hashCode()
        result = 31 * result + photoUri.hashCode()
        result = 31 * result + boundingBoxLeft.hashCode()
        result = 31 * result + boundingBoxTop.hashCode()
        result = 31 * result + boundingBoxRight.hashCode()
        result = 31 * result + boundingBoxBottom.hashCode()
        result = 31 * result + (embedding?.contentHashCode() ?: 0)
        result = 31 * result + confidence.hashCode()
        result = 31 * result + (clusterId?.hashCode() ?: 0)
        return result
    }
}

/**
 * Represents a person (named face group)
 */
@Entity(tableName = "persons")
data class PersonEntity(
    @PrimaryKey
    val personId: String,
    val name: String?,
    val representativeFaceId: String,
    val photoCount: Int,
    val isHidden: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long = createdAt
)

/**
 * Represents a cluster of similar faces (may or may not be assigned to a person)
 */
@Entity(
    tableName = "face_clusters",
    foreignKeys = [
        ForeignKey(
            entity = PersonEntity::class,
            parentColumns = ["personId"],
            childColumns = ["personId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index(value = ["personId"])]
)
data class FaceClusterEntity(
    @PrimaryKey
    val clusterId: String,
    val personId: String?,
    val centroidEmbedding: ByteArray?,
    val faceCount: Int,
    val createdAt: Long,
    val updatedAt: Long = createdAt
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as FaceClusterEntity

        if (clusterId != other.clusterId) return false
        if (personId != other.personId) return false
        if (centroidEmbedding != null) {
            if (other.centroidEmbedding == null) return false
            if (!centroidEmbedding.contentEquals(other.centroidEmbedding)) return false
        } else if (other.centroidEmbedding != null) return false
        if (faceCount != other.faceCount) return false

        return true
    }

    override fun hashCode(): Int {
        var result = clusterId.hashCode()
        result = 31 * result + (personId?.hashCode() ?: 0)
        result = 31 * result + (centroidEmbedding?.contentHashCode() ?: 0)
        result = 31 * result + faceCount
        return result
    }
}

/**
 * Tracks the progress of gallery scanning
 */
@Entity(tableName = "scan_progress")
data class ScanProgressEntity(
    @PrimaryKey
    val id: Int = 1,  // Single row table
    val totalPhotos: Int,
    val scannedPhotos: Int,
    val facesDetected: Int = 0,
    val clustersCreated: Int = 0,
    val lastPhotoUri: String?,
    val isComplete: Boolean,
    val startedAt: Long,
    val completedAt: Long? = null,
    val errorMessage: String? = null
)

// Data class for joining person with their representative face
data class PersonWithFace(
    val personId: String,
    val name: String?,
    val photoCount: Int,
    val isHidden: Boolean,
    val faceId: String,
    val photoUri: String,
    val boundingBoxLeft: Float,
    val boundingBoxTop: Float,
    val boundingBoxRight: Float,
    val boundingBoxBottom: Float
)

// Scan status enum
object ScanStatus {
    const val PENDING = "PENDING"
    const val SCANNED = "SCANNED"
    const val FAILED = "FAILED"
    const val NO_FACES = "NO_FACES"
}
